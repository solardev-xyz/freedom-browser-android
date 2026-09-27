package baby.freedom.mobile.browser

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide order of document requests and main-frame answers, so a
 * tab can tell what it requested before or after a given answer, and a
 * service worker's fetch can be placed against a tab's commits (#125).
 */
internal object DocumentClock {
    private val ticks = AtomicLong()

    /** A tick later than every one handed out before. Any thread. */
    fun next(): Long = ticks.incrementAndGet()
}

/**
 * The virtual origins a tab may have a live document on — its main
 * frame's and its frames' — for [UnverifiedOrigins]' sweep (#125).
 *
 * Requests arrive on the interceptor's IO threads ([requested],
 * [mainFrameAnswered]); the commit on the UI thread ([committed]). A
 * frame of the incoming document can be requested before the commit
 * reaches the UI thread, so the commit can't just reset the set. Each
 * origin is stamped with the [DocumentClock] tick it was last requested
 * at instead, and a commit drops what was requested before the main-frame
 * answer it commits: the outgoing document's frames go (a tab that left
 * an origin isn't reloaded for it any more), the incoming one's stay.
 *
 * A commit with no answer the interceptor saw (a page a service worker
 * answered, a history entry restored without a request) prunes nothing,
 * so in doubt the tab keeps an origin and is reloaded once too often.
 */
internal class TabDocuments {
    private val origins = ConcurrentHashMap<String, Long>()

    /** Main-frame answers not yet committed: url to tick, oldest first. */
    private val answers = ArrayDeque<Pair<String, Long>>()

    /** The tick of the answer the document on screen was committed from. */
    @Volatile
    var committedAt: Long = 0L
        private set

    /** A document (main frame or frame) was requested on [origin]. */
    fun requested(origin: String) {
        origins[origin] = DocumentClock.next()
    }

    /**
     * The interceptor handed Chromium the main-frame answer for [url],
     * one that replaces the document on screen once it commits.
     */
    fun mainFrameAnswered(url: String) {
        val tick = DocumentClock.next()
        synchronized(answers) {
            answers.addLast(url to tick)
            while (answers.size > MAX_PENDING_ANSWERS) answers.removeFirst()
        }
    }

    /**
     * A main-frame document for [url] committed ([origin]: its virtual
     * origin, if any). Its answer is the latest one for that URL, or —
     * redirected on the network, where the interceptor sees no new
     * request — the latest one of all.
     */
    fun committed(url: String?, origin: String?) {
        val since = synchronized(answers) {
            val i = answers.indexOfLast { it.first == url }.takeIf { it >= 0 } ?: answers.lastIndex
            if (i < 0) {
                null
            } else {
                val tick = answers[i].second
                repeat(i + 1) { answers.removeFirst() }
                tick
            }
        }
        if (since != null) {
            committedAt = since
            // Conditional per entry: one re-requested meanwhile keeps its newer tick.
            for ((origin, tick) in origins) if (tick < since) origins.remove(origin, tick)
        }
        origin?.let(::requested)
    }

    /** The origins the tab may have a document on. */
    fun origins(): Set<String> = origins.keys.toSet()

    /**
     * Could a frame document a service worker fetched at [tick] be in
     * this tab? Not if the document on screen was answered after it.
     */
    fun mayHoldWorkerFetchAt(tick: Long): Boolean = tick > committedAt

    private companion object {
        const val MAX_PENDING_ANSWERS = 8
    }
}
