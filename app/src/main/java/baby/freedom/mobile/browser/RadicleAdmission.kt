package baby.freedom.mobile.browser

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Admission control for the calls [RadicleClient] makes on the node
 * (#533), desktop's `rad-read-admission.js` (#947):
 *
 *  - at most [Limits.inFlight] run at once, across every tab;
 *  - each requester (a tab; [NO_TAB] for the app's own calls) may have at
 *    most [Limits.perRequester] reads waiting, and no more than
 *    [Limits.queueMax] wait in all; past that a read is refused at once
 *    ([Admit.Busy]);
 *  - each requester waits in its own FIFO, and a free slot goes to the
 *    requesters in turn (round-robin): a tab keeping its queue full of
 *    slow reads gets one slot per turn, so another tab's read waits
 *    behind at most one read per requester ahead of it;
 *  - a read that waits longer than [Limits.waitMs] for a slot is refused
 *    ([Admit.Busy]);
 *  - a read whose document goes away — the tab commits another or closes
 *    ([documentStarted], [tabClosed]) — leaves the queue without reaching
 *    the node, and a later call it makes is refused ([Admit.Cancelled]);
 *    a running one is told so ([Slot.cancelled]) and its caller stops
 *    waiting for it.
 *
 * A write (a COB write the user allowed) is neither capped nor cancelled:
 * dropping the answer of a write that went through invites the page to
 * make it again. It waits for a slot in its tab's turn like a read.
 *
 * Which document a request is from is its tab's [Requester.generation]
 * when it arrived. A new one starts when the tab's main-frame request
 * reaches the interceptor ([navigationRequested], on its thread, before
 * any of the new document's own requests can), or, for a document no
 * request was seen for (a service worker's answer, `about:blank`, a
 * history entry), when it commits ([documentStarted]). Calls from older
 * generations are dropped at the commit, not at the request: the page on
 * screen stays live until then, and a navigation may never commit.
 *
 * Each request that started a generation stays pending, with its URL,
 * until a commit on that URL claims it (and retires any older). A commit
 * on no pending URL — a redirect's, or a history entry's or service
 * worker's that landed after the next request — guesses the oldest
 * request not guessed yet and keeps it pending, so that request's own
 * commit still finds it. A commit drops only the generations before the
 * one it claimed, never a later request's: navigations requested before
 * any of them commits each find their own at their commit. Only a commit
 * with nothing left to claim starts a generation of its own.
 *
 * Known gap: a request the page on screen makes between the next
 * navigation's request and its commit counts as the incoming document's,
 * and so does one made after a navigation that never committed, until
 * the tab's next commit that claims a later request. Such a call only
 * ends at its own deadline. The gap only ever leaves a call running, it
 * never drops one from the document on screen.
 */
internal class RadicleAdmission(private val limits: Limits = Limits()) {
    data class Limits(
        val inFlight: Int = 2,
        val perRequester: Int = 8,
        val queueMax: Int = 32,
        val waitMs: Long = 15_000L,
    )

    /** Who asks: [tab] ([NO_TAB] for none) and its document then ([generation]). */
    data class Requester(val tab: Long, val generation: Long)

    /** A running call's hold on a slot; [release] it once the call is done. */
    inner class Slot internal constructor(internal val entry: Entry) {
        /** The call's document went away: stop waiting for its answer. */
        val cancelled: Boolean get() = entry.cancelled

        /** Give the slot back. Idempotent. */
        fun release() = finished(entry)
    }

    sealed interface Admit {
        data class Granted(val slot: Slot) : Admit
        /** Too many waiting, or no slot came free in time. */
        data object Busy : Admit
        /** The document that asked is gone. */
        data object Cancelled : Admit
    }

    internal class Entry(val requester: Requester, val cancellable: Boolean) {
        var state = State.Waiting
        @Volatile
        var cancelled = false
    }

    internal enum class State { Waiting, Running, Done }

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private var running = 0
    private var waitingTotal = 0

    /** Requester tab → its FIFO. Map order is the rotation. */
    private val queues = LinkedHashMap<Long, ArrayDeque<Entry>>()
    private val runningEntries = HashSet<Entry>()
    private val generations = HashMap<Long, Long>()

    /** Per tab, the generation its calls from before were last dropped at. */
    private val droppedBelow = HashMap<Long, Long>()

    /**
     * Per tab, the main-frame requests no commit has claimed yet, oldest
     * first: the generation each started and its URL (fragment dropped).
     * At most [MAX_PENDING]; past that the oldest is forgotten, which
     * only leaves its calls to their own deadline.
     */
    private val requested = HashMap<Long, ArrayDeque<Pending>>()

    private class Pending(val generation: Long, val url: String?) {
        /** A commit on another URL took this for its own; its own may still come. */
        var guessed = false
    }

    /** [tab]'s document now, for a request it makes. */
    fun requester(tab: Long?): Requester =
        if (tab == null) Requester(NO_TAB, 0) else lock.withLock { Requester(tab, generations[tab] ?: 0) }

    /**
     * [tab]'s main-frame request for [url] reached the interceptor:
     * requests from here on are the incoming document's. The page on
     * screen keeps what it has queued or running until the navigation
     * commits ([documentStarted]) — it may never (a download, a 204, Stop).
     */
    fun navigationRequested(tab: Long, url: String? = null) = lock.withLock {
        val next = (generations[tab] ?: 0) + 1
        generations[tab] = next
        val pending = requested.getOrPut(tab) { ArrayDeque() }
        pending.addLast(Pending(next, url?.let(::withoutFragment)))
        while (pending.size > MAX_PENDING) pending.removeFirst()
    }

    /**
     * [tab] committed a new document on [url] (UI thread); [gone]: its
     * document is being torn down with no new one. What earlier documents
     * still have queued or running is dropped. If a navigation's own
     * request already started a generation for it ([navigationRequested];
     * which one: see the class), that generation is the committed
     * document's; otherwise one starts now.
     *
     * [blank]: the document is `about:blank`, which no request is made
     * for, so it can't be the commit of one [navigationRequested] saw: a
     * new tab commits its blank page after the request for the address it
     * was opened on has already reached the interceptor, and that
     * address's own commit must still find it.
     */
    fun documentStarted(
        tab: Long,
        url: String? = null,
        gone: Boolean = false,
        blank: Boolean = false,
    ) = lock.withLock {
        val pending = requested[tab] ?: ArrayDeque()
        val key = url?.let(::withoutFragment)
        val matched = if (gone || blank || key == null) null else pending.firstOrNull { it.url == key }
        val guess = pending.firstOrNull { !it.guessed }
        when {
            gone -> {
                requested.remove(tab)
                advance(tab)
            }
            matched != null -> {
                while (pending.first() !== matched) pending.removeFirst()
                pending.removeFirst()
                if (pending.isEmpty()) requested.remove(tab)
                cancel(tab, matched.generation)
            }
            guess == null -> {
                // Nothing left to claim: what's still pending was guessed
                // by a commit before and is older than this document.
                requested.remove(tab)
                advance(tab)
            }
            // A blank page claims nothing (see above).
            blank -> cancel(tab, guess.generation)
            else -> {
                while (pending.first() !== guess) pending.removeFirst()
                guess.guessed = true
                cancel(tab, guess.generation)
            }
        }
    }

    /**
     * [tab] closed: everything it has queued or running is dropped, and
     * any call it makes later is refused — one whose requester was
     * captured after the close too (an interceptor call racing it). Its
     * generations stay (two numbers per closed tab); tab ids aren't
     * reused within a process.
     */
    fun tabClosed(tab: Long) = lock.withLock {
        requested.remove(tab)
        advance(tab)
        droppedBelow[tab] = Long.MAX_VALUE
    }

    private fun advance(tab: Long) {
        val next = (generations[tab] ?: 0) + 1
        generations[tab] = next
        cancel(tab, next)
    }

    /** Drop [tab]'s cancellable calls from generations before [below]; under [lock]. */
    private fun cancel(tab: Long, below: Long) {
        if (below > (droppedBelow[tab] ?: 0)) droppedBelow[tab] = below
        var any = false
        queues[tab]?.let { queue ->
            val dropped = queue.filter { it.cancellable && it.requester.generation < below }
            for (e in dropped) {
                queue.remove(e)
                waitingTotal -= 1
                e.cancelled = true
                e.state = State.Done
                any = true
            }
            if (queue.isEmpty()) queues.remove(tab)
        }
        for (e in runningEntries) {
            if (e.requester.tab == tab && e.cancellable && e.requester.generation < below) e.cancelled = true
        }
        if (any) changed.signalAll()
    }

    /**
     * Wait for a slot for a call from [requester], at most [waitMs] (and
     * for a read never longer than [Limits.waitMs]). [write]: see the class.
     * Blocks; never on the main thread.
     */
    fun admit(requester: Requester, write: Boolean, waitMs: Long): Admit = lock.withLock {
        val cancellable = !write && requester.tab != NO_TAB
        if (cancellable && requester.generation < (droppedBelow[requester.tab] ?: 0)) return Admit.Cancelled
        val entry = Entry(requester, cancellable)
        if (running < limits.inFlight && waitingTotal == 0) {
            start(entry)
            return Admit.Granted(Slot(entry))
        }
        if (!write && (
                (queues[requester.tab]?.size ?: 0) >= limits.perRequester ||
                    waitingTotal >= limits.queueMax
                )
        ) {
            return Admit.Busy
        }
        queues.getOrPut(requester.tab) { ArrayDeque() }.addLast(entry)
        waitingTotal += 1
        val wait = if (write) waitMs else minOf(waitMs, limits.waitMs)
        var left = TimeUnit.MILLISECONDS.toNanos(wait.coerceAtLeast(0))
        while (entry.state == State.Waiting && left > 0) {
            left = try {
                changed.awaitNanos(left)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                0
            }
        }
        when {
            entry.state == State.Running -> Admit.Granted(Slot(entry))
            entry.cancelled -> Admit.Cancelled
            else -> {
                // Out of time (or interrupted) while still queued.
                dequeue(entry)
                entry.state = State.Done
                Admit.Busy
            }
        }
    }

    private fun start(entry: Entry) {
        entry.state = State.Running
        running += 1
        runningEntries += entry
    }

    private fun dequeue(entry: Entry) {
        val tab = entry.requester.tab
        val queue = queues[tab] ?: return
        if (!queue.remove(entry)) return
        waitingTotal -= 1
        if (queue.isEmpty()) queues.remove(tab)
    }

    private fun finished(entry: Entry) = lock.withLock {
        if (entry.state != State.Running) return@withLock
        entry.state = State.Done
        running -= 1
        runningEntries -= entry
        pump()
    }

    /** Hand free slots to the waiting requesters in turn; under [lock]. */
    private fun pump() {
        var any = false
        while (running < limits.inFlight && waitingTotal > 0) {
            val (tab, queue) = queues.entries.first()
            val entry = queue.removeFirst()
            queues.remove(tab)
            if (queue.isNotEmpty()) queues[tab] = queue // back of the rotation
            waitingTotal -= 1
            start(entry)
            any = true
        }
        if (any) changed.signalAll()
    }

    /** Running and waiting calls, for tests. */
    internal fun stats(): Pair<Int, Int> = lock.withLock { running to waitingTotal }

    companion object {
        /** The requester of a call no tab made (the app's own reads, a service worker's). */
        const val NO_TAB = -1L

        /** Main-frame requests a tab keeps waiting for their commit. */
        private const val MAX_PENDING = 8

        private fun withoutFragment(url: String): String = url.substringBefore('#')
    }
}
