package baby.freedom.mobile.browser

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The x402 holds to keep (true) or lift (false) across restarts (#347),
 * written in the order they happened. A write that fails (a full disk) is
 * tried again, sooner at first and then every [MAX_RETRY_MS], until it
 * lands or a later hold or lift of the same site replaces it — so a hold
 * this run kept in memory reaches the disk once the disk takes it, and a
 * restart after that still holds (#347 R1-M1). One written before the
 * store was last cleared ([era]: Remove wallet) is dropped, not written
 * back after the clear.
 */
internal class X402HoldWrites(
    private val write: suspend (origin: String, held: Boolean) -> Boolean,
    /** Bumped each time the store is cleared; a write from an earlier era is dropped. */
    private val era: () -> Long = { 0L },
    private val onFailed: (held: Boolean) -> Unit = {},
) {
    private class Write(val held: Boolean, val era: Long)

    private val queue = Channel<Pair<String, Write>>(Channel.UNLIMITED)

    fun send(origin: String, held: Boolean) {
        queue.trySend(origin to Write(held, era()))
    }

    /** Writes until [close]. */
    suspend fun run() {
        // Not yet written, oldest first; one per site, its latest.
        val pending = LinkedHashMap<String, Write>()
        var retryMs = FIRST_RETRY_MS
        while (true) {
            val next = if (pending.isEmpty()) {
                queue.receiveCatching()
            } else {
                // Null: time to try the failed ones again.
                withTimeoutOrNull(retryMs) { queue.receiveCatching() }
            }
            if (next != null) {
                val (origin, w) = next.getOrNull() ?: return
                pending.remove(origin)
                pending[origin] = w
                while (true) {
                    val (o, more) = queue.tryReceive().getOrNull() ?: break
                    pending.remove(o)
                    pending[o] = more
                }
            }
            val failedBefore = pending.isNotEmpty() && next == null
            val it = pending.entries.iterator()
            var failed = false
            while (it.hasNext()) {
                val (origin, w) = it.next()
                if (w.era != era() || write(origin, w.held)) {
                    it.remove()
                } else {
                    if (!failed) onFailed(w.held)
                    failed = true
                }
            }
            retryMs = when {
                !failed -> FIRST_RETRY_MS
                failedBefore -> (retryMs * 2).coerceAtMost(MAX_RETRY_MS)
                else -> retryMs
            }
        }
    }

    fun close() {
        queue.close()
    }

    companion object {
        const val FIRST_RETRY_MS = 1_000L
        const val MAX_RETRY_MS = 60_000L
    }
}
