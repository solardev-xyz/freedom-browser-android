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
    private val reader = AtomicReference<((String) -> String)?>(null)

    /**
     * Answer ant's reads with [read] (a JSON-RPC request body in, a
     * response body out) from now on, in place of any earlier reader.
     */
    fun install(read: (String) -> String) {
        reader.set(read)
    }

    /**
     * Called by the shim on one of ant's threads, for each read: the
     * request and the answer as UTF-8 JSON. Blocks while the reader
     * works; never throws.
     */
    @JvmStatic
    fun serve(request: ByteArray): ByteArray {
        val answer = try {
            reader.get()?.invoke(String(request, Charsets.UTF_8)) ?: NOT_READY
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
