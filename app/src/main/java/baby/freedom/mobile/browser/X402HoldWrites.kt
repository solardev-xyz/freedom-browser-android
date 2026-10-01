package baby.freedom.mobile.browser

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select

/**
 * The x402 holds to keep (true) or lift (false) across restarts (#347),
 * written in the order they happened. A write that fails (a full disk) is
 * tried again, sooner at first and then every [MAX_RETRY_MS], until it
 * lands or a later hold or lift of the same site replaces it — so a hold
 * this run kept in memory reaches the disk once the disk takes it, and a
 * restart after that still holds (#347 R1-M1). One queued before the
 * store was cleared ([cleared]: Remove wallet) is dropped once that clear
 * has landed, not written back after it; while the clear is still being
 * written it waits, and if the clear fails it is written after all
 * (#347 R2-M1).
 */
internal class X402HoldWrites(
    private val write: suspend (origin: String, held: Boolean) -> Boolean,
    /** Tag for a write queued now, handed back to [cleared]. */
    private val era: () -> Long = { 0L },
    /**
     * Whether the store was cleared after a write tagged [era]: true
     * (drop it), null (a clear is still being written: wait) or false.
     */
    private val cleared: (era: Long) -> Boolean? = { false },
    private val onFailed: (held: Boolean) -> Unit = {},
) {
    private class Write(val held: Boolean, val era: Long)

    private val queue = Channel<Pair<String, Write>>(Channel.UNLIMITED)

    fun send(origin: String, held: Boolean) {
        queue.trySend(origin to Write(held, era()))
    }

    /** Writes until [close]. */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun run() {
        // Not yet written, oldest first; one per site, its latest.
        val pending = LinkedHashMap<String, Write>()
        var retryMs = FIRST_RETRY_MS
        while (true) {
            val next: ChannelResult<Pair<String, Write>>? = if (pending.isEmpty()) {
                queue.receiveCatching()
            } else {
                // Null: time to try the failed ones again. A select, not
                // withTimeoutOrNull around the receive: the timeout racing
                // a send that already handed its element over would cancel
                // the receive and lose that hold or lift (R2-F1); in a
                // select only one of the two clauses can win.
                select {
                    queue.onReceiveCatching { it }
                    onTimeout(retryMs) { null }
                }
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
                when (cleared(w.era)) {
                    true -> it.remove()
                    // A clear is being written: try again once it has landed or failed.
                    null -> Unit
                    false -> if (write(origin, w.held)) {
                        it.remove()
                    } else {
                        if (!failed) onFailed(w.held)
                        failed = true
                    }
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
