package baby.freedom.mobile.ens

import org.json.JSONObject

/**
 * The embedded Myotis light client as name resolution sees it (#101):
 * a verified `eth_call` on Ethereum mainnet, executed on this device
 * against state proven to the chain's sync committee — no RPC server's
 * word involved. [EnsResolver] asks it first whenever it's ready and
 * falls back to its RPC servers when it isn't, or doesn't answer.
 *
 * The app's implementation talks to the `:myotis` process
 * ([baby.freedom.mobile.node.MyotisLink]); tests pass a fake.
 */
interface EnsLightClient {
    /**
     * An id for the light client's current stretch of readiness on
     * Ethereum mainnet, or `null` while it can't serve a verified read
     * (off, still syncing, paused in the background, recovering). A new
     * id on every transition, so an answer — or a cache — from one
     * stretch is never mistaken for the next ([EnsResolver.Settings.lightClient]).
     */
    fun readyGeneration(): Long?

    /**
     * `eth_call` of [data] (0x-hex) on [to] at the light client's verified
     * head. Blocking: returns within [timeoutMs], [Call.Unavailable] if
     * there was no answer by then.
     */
    fun ethCall(to: String, data: String, timeoutMs: Long): Call

    /** One verified call's outcome. */
    sealed class Call {
        /** The call returned [resultHex], proven at [block]. */
        data class Ok(val resultHex: String, val block: Long?) : Call()

        /** The call reverted with [dataHex] — itself a verified answer. */
        data class Revert(val dataHex: String, val block: Long?) : Call()

        /** No verified answer: not ready, busy, an engine or transport error. */
        data class Unavailable(val reason: String) : Call()
    }

    companion object {
        /**
         * The engine's `myotis_eth_call_json` shape as a [Call]: anything
         * but a well-formed `ok` / `revert` is [Call.Unavailable].
         */
        fun parse(json: String?): Call {
            val o = try {
                JSONObject(json ?: return Call.Unavailable("no answer"))
            } catch (e: Exception) {
                return Call.Unavailable("malformed answer")
            }
            if (o.has("error")) return Call.Unavailable(o.optString("error").ifEmpty { "error" })
            val block = blockOf(o.opt("blockNumber"))
            return when (o.optString("status")) {
                "ok" -> o.optString("resultHex").takeIf(::isHex)?.let { Call.Ok(it, block) }
                    ?: Call.Unavailable("malformed result")
                "revert" -> o.optString("dataHex").takeIf(::isHex)?.let { Call.Revert(it, block) }
                    ?: Call.Unavailable("malformed revert")
                "unavailable" -> Call.Unavailable(o.optString("reason").ifEmpty { "unavailable" })
                else -> Call.Unavailable("unknown status")
            }
        }

        private fun isHex(s: String): Boolean =
            s.startsWith("0x") && s.length % 2 == 0 && s.drop(2).all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

        private fun blockOf(v: Any?): Long? = when (v) {
            is Number -> v.toLong().takeIf { it > 0 }
            is String -> (if (v.startsWith("0x")) v.drop(2).toLongOrNull(16) else v.toLongOrNull())?.takeIf { it > 0 }
            else -> null
        }
    }
}
