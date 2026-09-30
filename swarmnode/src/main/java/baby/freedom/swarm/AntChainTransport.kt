package baby.freedom.swarm

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Where ant's chain reads are answered (#273). The shim's chain transport
 * (`ant_jni.c`) sends every request ant makes that isn't a broadcast
 * here — broadcasts stay with [SpendGuard] — and returns [serve]'s answer
 * to ant as is. The `:node` process installs the app's reader (the
 * chain-data router's bridge) when its node service is created.
 *
 * With no reader installed every read gets a JSON-RPC error: never
 * "can't serve", which would send ant to its one configured RPC for an
 * unverified answer, and never an empty result.
 */
object AntChainTransport {
    private class Reader(val read: (String) -> String, val cancelInFlight: () -> Unit)

    private val reader = AtomicReference<Reader?>(null)

    /**
     * Answer ant's reads with [read] (a JSON-RPC request body in, a
     * response body out) from now on, in place of any earlier reader.
     * [cancelInFlight] ends every read [read] is working on at once, each
     * with an error; reads that come after are answered as usual
     * ([whileStopping] refuses those itself).
     */
    fun install(read: (String) -> String, cancelInFlight: () -> Unit = {}) {
        reader.set(Reader(read, cancelInFlight))
    }

    /** How many [whileStopping] blocks are running; reads are refused while any is. */
    private val stopping = AtomicInteger(0)

    /** Reads that got past the first [stopping] check and haven't returned yet. */
    private val serving = AtomicInteger(0)

    /**
     * Run [block] — ant's gateway stop or shutdown, which wait for every
     * read inside the transport — with no read holding it up (#273,
     * #300 R2-M1): reads already in the reader are ended at once (its
     * cancel, repeated until none is left, for at most [DRAIN_MS]), and
     * every read that comes in while [block] runs is refused at once with
     * an error rather than served, so none can start after the cancel and
     * hold the stop for the reader's whole deadline. Reads after [block]
     * are served as usual. Only for a node going away ([SwarmNode.stop],
     * under its write lock, so no storage call — a spend — is using it any
     * more): the transport is process-wide, and a spend still reading its
     * nonce or receipt must never be failed by it.
     */
    fun <T> whileStopping(block: () -> T): T {
        stopping.incrementAndGet()
        try {
            drain()
            return block()
        } finally {
            stopping.decrementAndGet()
        }
    }

    private fun drain() {
        val until = System.nanoTime() + DRAIN_MS * 1_000_000
        while (true) {
            try {
                reader.get()?.cancelInFlight?.invoke()
            } catch (_: Throwable) {
            }
            // A read counted here passed its check before [stopping] went
            // up, so it may have reached the reader after the cancel: cancel
            // again until it's gone.
            if (serving.get() == 0 || System.nanoTime() >= until) return
            try {
                Thread.sleep(DRAIN_POLL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    /**
     * Called by the shim on one of ant's threads, for each read: the
     * request and the answer as UTF-8 JSON. Blocks while the reader
     * works; never throws.
     */
    @JvmStatic
    fun serve(request: ByteArray): ByteArray {
        if (stopping.get() > 0) return STOPPING.toByteArray(Charsets.UTF_8)
        serving.incrementAndGet()
        val answer = try {
            // Checked again once counted: a [whileStopping] that began in
            // between either shows here or waits for this read (drain).
            if (stopping.get() > 0) {
                STOPPING
            } else {
                reader.get()?.read?.invoke(String(request, Charsets.UTF_8)) ?: NOT_READY
            }
        } catch (t: Throwable) {
            FAILED
        } finally {
            serving.decrementAndGet()
        }
        return answer.toByteArray(Charsets.UTF_8)
    }

    // ant reads neither the id nor the code but for -32000 (can't serve).
    private const val NOT_READY =
        """{"jsonrpc":"2.0","id":null,"error":{"code":-32002,"message":"Chain request failed: Freedom's chain reads aren't available"}}"""
    private const val STOPPING =
        """{"jsonrpc":"2.0","id":null,"error":{"code":-32002,"message":"Chain request failed: the Swarm node is stopping"}}"""
    private const val FAILED =
        """{"jsonrpc":"2.0","id":null,"error":{"code":-32002,"message":"Chain request failed"}}"""

    /** The longest [whileStopping] keeps cancelling a read that won't end before stopping anyway. */
    internal const val DRAIN_MS = 2_000L
    private const val DRAIN_POLL_MS = 5L
}
