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
 * ends and no fresh fetch started since then was forgotten ([epochs] is
 * bounded). Thread-safe: called on the interceptor's threads.
 *
 * Memory (#355): the buffer also keeps one byte budget, [maxBytes], for
 * every body it holds *and* every body still being read — a fetch
 * [reserve]s the bytes it is about to allocate before allocating them, so
 * any number of parallel loads together stay inside the budget. A body
 * [fetch] returns owns a reservation of exactly [sizeOf] it; the buffer
 * takes that over when it keeps the body and [release]s it when it
 * evicts, replaces or doesn't keep it. Bodies of size 0 (a "too large to
 * buffer" marker) count only toward [maxEntries], which is set well
 * above the number of real bodies the byte budget allows, so a marker
 * doesn't push a real body out. A body [keep] refuses is handed back to
 * its request but never buffered; one it keeps is buffered as [stored]
 * of it (same [sizeOf]), so a marker can drop what only its own request
 * should see. A buffered body [refetch] judges stale — given the bytes a
 * new read could get by evicting every buffered body ([obtainableBytes])
 * — is fetched again instead of answered (R4-M3: a "no room right now"
 * marker, once there is room).
 *
 * A body [reserve] evicts is replaced by the size-0 marker [evicted]
 * returns, if any, instead of being forgotten (R5-M2): the URL's later
 * requests are answered from that marker (streamed) rather than starting
 * a full download that would evict another body in turn, so pages
 * cycling through more bodies than the budget holds don't download every
 * one again on each request. [refetch] sees the free bytes too, so such a
 * marker can be fetched again once there is room without evicting.
 * While one request fetches a marker's body again, the marker stays
 * buffered and the URL's other non-fresh requests are answered from it
 * (R6-M1), so an overlapping request can't start a second full download
 * whose reservation, the free room being taken by the first, evicts
 * another body.
 */
internal class MediaBodyBuffer<B : Any>(
    private val maxEntries: Int = 64,
    private val maxEpochs: Int = 64,
    private val maxBytes: Long = Long.MAX_VALUE,
    private val sizeOf: (B) -> Long = { 0L },
    private val keep: (B) -> Boolean = { true },
    private val stored: (B) -> B = { it },
    private val refetch: (body: B, free: Long, obtainable: Long) -> Boolean = { _, _, _ -> false },
    private val evicted: (B) -> B? = { null },
) {
    // Bytes held by buffered bodies plus every outstanding reservation.
    private var used = 0L

    private val bodies = object : java.util.LinkedHashMap<String, B>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, B>?): Boolean {
            if (size <= maxEntries) return false
            if (eldest != null) used -= sizeOf(eldest.value)
            return true
        }
    }

    /**
     * Reserve [bytes] of the budget for a body being read, evicting the
     * least recently used buffered bodies to make room. False (nothing
     * reserved) if even an empty buffer couldn't fit them alongside the
     * other reads in flight: the caller should not buffer then, and
     * nothing is evicted for it (R4-M1) — a buffered body thrown out for a
     * reservation that fails anyway would only be downloaded again.
     */
    @Synchronized
    fun reserve(bytes: Long): Boolean {
        if (bytes < 0) return false
        if (used + bytes > maxBytes) {
            if (bytes > obtainable()) return false
            val it = bodies.entries.iterator()
            while (used + bytes > maxBytes && it.hasNext()) {
                val entry = it.next()
                val size = sizeOf(entry.value)
                if (size > 0) {
                    // Replacing a value isn't an access: the marker keeps
                    // the evicted body's place in the LRU order.
                    val marker = evicted(entry.value)?.takeIf { m -> sizeOf(m) == 0L }
                    if (marker != null) entry.setValue(marker) else it.remove()
                    used -= size
                }
            }
        }
        if (used + bytes > maxBytes) return false
        used += bytes
        return true
    }

    // Free bytes plus every buffered body's: what a reservation could get
    // by evicting them all, the reads in flight being untouchable.
    private fun obtainable(): Long = maxBytes - used + bodies.values.sumOf { sizeOf(it) }

    /** What [reserve] could get right now by evicting every buffered body. */
    @get:Synchronized
    val obtainableBytes: Long get() = obtainable()

    /**
     * Reserve [bytes] only if they fit beside what is already held,
     * evicting nothing: for an optional allocation (trimming a body's last
     * chunk) whose saving is worth less than any buffered body (R3-M2).
     */
    @Synchronized
    fun reserveFree(bytes: Long): Boolean {
        if (bytes < 0 || used + bytes > maxBytes) return false
        used += bytes
        return true
    }

    /** Give back [bytes] taken by [reserve] or [reserveFree]. */
    @Synchronized
    fun release(bytes: Long) {
        used = (used - bytes).coerceAtLeast(0)
    }

    /** Bytes held by buffered bodies and reads in flight (for tests). */
    @get:Synchronized
    val usedBytes: Long get() = used

    // The epoch of the last fresh fetch started per URL. Values never
    // recur. Evicting an entry would make a fetch that started before that
    // URL's first fresh fetch (it recorded no epoch) match again (R2-M3),
    // so the highest epoch ever evicted is kept: a fetch that recorded no
    // epoch doesn't buffer if a fresh fetch started after it and was then
    // evicted, whatever its URL. Skipping the buffer is always safe.
    private var evictedUpTo = 0L
    private val epochs = object : java.util.LinkedHashMap<String, Long>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
            if (size <= maxEpochs) return false
            if (eldest != null) evictedUpTo = maxOf(evictedUpTo, eldest.value)
            return true
        }
    }
    private var nextEpoch = 0L
    private val freshFetches = HashMap<String, CompletableFuture<B?>>()
    // URLs whose stored marker one request is fetching again (R6-M1).
    private val refetches = HashMap<String, Any>()

    /**
     * The body for [url]: buffered, or fetched with [fetch] (its argument
     * is whether to ask the gateway not to answer from a cache) and
     * buffered. [fresh] fetches it again past the buffer (see the class).
     * [pastCaches]: a later request of a document whose first one was
     * [fresh] — whatever it does fetch (a stored marker [refetch]ed, or a
     * miss after the fresh body was evicted) goes past the gateway's
     * caches too (R5-M1), so the body it buffers is no staler than the
     * fresh one. Null if the fetch failed.
     */
    fun load(
        url: String,
        fresh: Boolean,
        pastCaches: Boolean = false,
        fetch: (noCache: Boolean) -> B?,
    ): B? {
        val epoch: Long?
        val startedAt: Long
        val mine: CompletableFuture<B?>?
        val joined: CompletableFuture<B?>?
        var refetching: Any? = null
        synchronized(this) {
            startedAt = nextEpoch
            if (fresh) {
                bodies.remove(url)?.let { used -= sizeOf(it) }
                epoch = ++nextEpoch
                epochs[url] = epoch
                mine = CompletableFuture()
                freshFetches[url] = mine
                joined = null
            } else {
                bodies[url]?.let { held ->
                    // Another request is already fetching this marker's
                    // body again: answer from the marker, as before the
                    // refetch, rather than start a second full download
                    // whose reservation would evict another body (R6-M1).
                    if (url in refetches) return held
                    if (!refetch(held, maxBytes - used, obtainable())) return held
                    if (sizeOf(held) == 0L) {
                        // A marker costs nothing: it stays until the
                        // refetched body replaces it, and stays if that
                        // fetch fails or isn't kept.
                        refetching = Any().also { refetches[url] = it }
                    } else {
                        bodies.remove(url)
                        used -= sizeOf(held)
                    }
                }
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
            body = fetch(fresh || pastCaches || joined != null)
            if (body != null) {
                synchronized(this) {
                    val current = epochs[url]
                    val unchanged = current == epoch && (epoch != null || evictedUpTo <= startedAt)
                    if (unchanged && keep(body)) {
                        // A parallel non-fresh fetch of the same URL may
                        // have buffered first: its body goes, with its bytes.
                        val kept = stored(body)
                        bodies.put(url, kept)?.let { if (it !== kept) used -= sizeOf(it) }
                    } else {
                        used -= sizeOf(body)
                    }
                }
            }
        } finally {
            refetching?.let { token ->
                synchronized(this) { if (refetches[url] === token) refetches.remove(url) }
            }
            if (mine != null) {
                synchronized(this) { if (freshFetches[url] === mine) freshFetches.remove(url) }
                mine.complete(body)
            }
        }
        return body
    }
}
