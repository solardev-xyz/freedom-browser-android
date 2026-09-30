package baby.freedom.swarm

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
     * with an error; reads that come after are answered as usual.
     */
    fun install(read: (String) -> String, cancelInFlight: () -> Unit = {}) {
        reader.set(Reader(read, cancelInFlight))
    }

    /**
     * A node is about to stop ([SwarmNode.stop]): end the reads it's
     * waiting on now rather than let ant's gateway stop and shutdown —
     * which wait for every read inside the transport — sit behind them
     * for up to the reader's own deadline. Only called once no storage
     * call (a spend) is using the node any more.
     */
    fun cancelInFlight() {
        try {
            reader.get()?.cancelInFlight?.invoke()
        } catch (_: Throwable) {
        }
    }

    /**
     * Called by the shim on one of ant's threads, for each read: the
     * request and the answer as UTF-8 JSON. Blocks while the reader
     * works; never throws.
     */
    @JvmStatic
    fun serve(request: ByteArray): ByteArray {
        val answer = try {
            reader.get()?.read?.invoke(String(request, Charsets.UTF_8)) ?: NOT_READY
        } catch (t: Throwable) {
            FAILED
        }
        return answer.toByteArray(Charsets.UTF_8)
    }

    // ant reads neither the id nor the code but for -32000 (can't serve).
    private const val NOT_READY =
        """{"jsonrpc":"2.0","id":null,"error":{"code":-32002,"message":"Chain request failed: Freedom's chain reads aren't available"}}"""
    private const val FAILED =
        """{"jsonrpc":"2.0","id":null,"error":{"code":-32002,"message":"Chain request failed"}}"""
}
