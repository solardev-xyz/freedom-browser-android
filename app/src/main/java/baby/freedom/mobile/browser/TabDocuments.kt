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
 * A page a service worker answers never reaches the interceptor, so it
 * has no answer: its commit prunes what was requested before its
 * navigation started instead ([navigationStarted]) — earlier than the
 * answer would have been, so in doubt the outgoing document keeps a few
 * origins it requested meanwhile. A commit with neither (a navigation
 * not seen starting, such as page script's `history.back()`, onto a page
 * a worker answered) prunes nothing, so in doubt the tab keeps an origin
 * and is reloaded once too often.
 */
internal class TabDocuments {
    private val origins = ConcurrentHashMap<String, Long>()

    /** Main-frame answers not yet committed: url to tick, oldest first. */
    private val answers = ArrayDeque<Pair<String, Long>>()

    /**
     * Main-frame navigations started and not yet committed: URL (as
     * [documentKey]) to tick, oldest first. Guarded by [answers].
     */
    private val starts = ArrayDeque<Pair<String, Long>>()

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
     * The tab is about to navigate its main frame to [url] (UI thread,
     * before the navigation is handed to Chromium): the incoming
     * document's requests all come after this, whether or not the
     * interceptor ever sees its answer.
     */
    fun navigationStarted(url: String) {
        if (url.startsWith("javascript:", ignoreCase = true)) return
        val tick = DocumentClock.next()
        synchronized(answers) {
            starts.addLast(documentKey(url) to tick)
            while (starts.size > MAX_PENDING_ANSWERS) starts.removeFirst()
        }
    }

    /**
     * A main-frame document for [url] committed ([origin]: its virtual
     * origin, if any). Its answer is the latest one for that URL; with
     * none (a page a service worker answered), the start of the latest
     * navigation to it; with neither — redirected on the network, where
     * the interceptor sees no new request — the latest answer of all.
     */
    fun committed(url: String?, origin: String?) {
        val since = synchronized(answers) {
            val key = url?.let(::documentKey)
            val tick = answers.lastOrNull { it.first == url }?.second
                ?: starts.lastOrNull { it.first == key }?.second
                ?: answers.lastOrNull()?.second
            if (tick != null) {
                // What was answered or started before it can't commit after it.
                answers.removeAll { it.second <= tick }
                starts.removeAll { it.second <= tick }
            }
            tick
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

/**
 * [url] as a navigation to it commits: scheme and host lower-cased, an
 * empty path as `/`, no fragment — so the URL a navigation was started
 * with matches the one Chromium reports at its commit.
 */
internal fun documentKey(url: String): String = runCatching {
    val u = java.net.URI(url)
    val authority = u.rawAuthority
    if (u.isOpaque || u.scheme == null || authority == null) return@runCatching url.substringBefore('#')
    val path = u.rawPath?.ifEmpty { null } ?: "/"
    val query = u.rawQuery?.let { "?$it" }.orEmpty()
    "${u.scheme.lowercase()}://${authority.lowercase()}$path$query"
}.getOrElse { url.substringBefore('#') }
