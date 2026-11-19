package baby.freedom.mobile.browser

/**
 * The short-term half of the limit on writes a site's "always allow
 * writes" rule answers without a prompt (#544; desktop's
 * `auto-write-limiter.js`): per origin, a burst of [BURST] writes, then
 * one more per [REFILL_MS]. The daily half ([baby.freedom.mobile.data.RadicleGrantStore.countAutoWrite],
 * at most 50 in any 24 hours) is kept on disk, so a restart doesn't
 * reset it; this half starts full again after one, as on desktop.
 *
 * [now] is a clock no setting moves.
 */
internal class AutoWriteBurst(private val now: () -> Long) {
    private class Bucket(var tokens: Double, var at: Long)

    private val buckets = HashMap<String, Bucket>()

    private fun bucket(origin: String, time: Long): Bucket {
        val b = buckets.getOrPut(origin) { Bucket(BURST.toDouble(), time) }
        b.tokens = minOf(BURST.toDouble(), b.tokens + maxOf(0L, time - b.at).toDouble() / REFILL_MS)
        b.at = time
        return b
    }

    /** Take one token for [origin]; false if none is left (the write needs a prompt). */
    @Synchronized
    fun take(origin: String): Boolean {
        val b = bucket(origin, now())
        if (b.tokens < 1) return false
        b.tokens -= 1
        return true
    }

    /** Give back a token [take] handed out for a write that didn't go ahead without a prompt. */
    @Synchronized
    fun giveBack(origin: String) {
        val b = buckets[origin] ?: return
        b.tokens = minOf(BURST.toDouble(), b.tokens + 1)
    }

    /** [origin] disconnected: it starts over. */
    @Synchronized
    fun forget(origin: String) {
        buckets.remove(origin)
    }

    companion object {
        const val BURST = 5
        const val REFILL_MS = 30_000L
    }
}
