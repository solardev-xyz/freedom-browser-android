package baby.freedom.mobile.browser

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException

/**
 * The interceptor's in-process buffer of fully-fetched media bodies, keyed
 * by gateway URL, so a `<video>`'s successive Range requests for one file
 * don't each re-fetch it from the gateway.
 *
 * A Hard reload's (#262) first request for a URL is [load]ed `fresh`: the
 * buffered body is dropped and fetched again past every cache. While that
 * fresh fetch runs, it is the only one that may decide what the buffer
 * holds for the URL:
 *
 * - a non-fresh request for the URL (the same document's overlapping Range
 *   request, or another tab's) waits for it and gets its body rather than
 *   fetching one of its own, which an external gateway or proxy could
 *   answer from its stale cache. If the fresh fetch fails, the waiter
 *   fetches for itself, still past the caches;
 * - a fetch that started before the fresh one (or before any later fresh
 *   one) hands its body back to its own request but never buffers it, even
 *   if it finishes last, so it can't put the stale body back.
 *
 * Every fetch records the URL's epoch — bumped by each fresh fetch — when
 * it starts, and buffers its body only if the epoch is unchanged when it
 * ends. Thread-safe: called on the interceptor's threads.
 */
internal class MediaBodyBuffer<B : Any>(
    maxEntries: Int = 4,
    maxEpochs: Int = 64,
) {
    private val bodies = lru<B>(maxEntries)

    // The epoch of the last fresh fetch started per URL. Values never
    // recur, so an entry evicted from here only ever makes an older fetch
    // skip buffering, never lets it through.
    private val epochs = lru<Long>(maxEpochs)
    private var nextEpoch = 0L
    private val freshFetches = HashMap<String, CompletableFuture<B?>>()

    /**
     * The body for [url]: buffered, or fetched with [fetch] (its argument
     * is whether to ask the gateway not to answer from a cache) and
     * buffered. [fresh] fetches it again past the buffer (see the class).
     * Null if the fetch failed.
     */
    fun load(url: String, fresh: Boolean, fetch: (noCache: Boolean) -> B?): B? {
        val epoch: Long?
        val mine: CompletableFuture<B?>?
        val joined: CompletableFuture<B?>?
        synchronized(this) {
            if (fresh) {
                bodies.remove(url)
                epoch = ++nextEpoch
                epochs[url] = epoch
                mine = CompletableFuture()
                freshFetches[url] = mine
                joined = null
            } else {
                bodies[url]?.let { return it }
                epoch = epochs[url]
                mine = null
                joined = freshFetches[url]
            }
        }
        if (joined != null) {
            val body = try {
                joined.get()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            } catch (_: ExecutionException) {
                null
            }
            if (body != null) return body
        }
        var body: B? = null
        try {
            body = fetch(fresh || joined != null)
            if (body != null) {
                synchronized(this) { if (epochs[url] == epoch) bodies[url] = body }
            }
        } finally {
            if (mine != null) {
                synchronized(this) { if (freshFetches[url] === mine) freshFetches.remove(url) }
                mine.complete(body)
            }
        }
        return body
    }

    private companion object {
        fun <V> lru(max: Int): MutableMap<String, V> =
            object : java.util.LinkedHashMap<String, V>(8, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>?) =
                    size > max
            }
    }
}
