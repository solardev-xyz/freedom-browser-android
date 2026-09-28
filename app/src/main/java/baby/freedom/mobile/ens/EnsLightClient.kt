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
     * id on every transition, so an answer from one stretch is never
     * mistaken for the next (a read re-checks it around every call).
     */
    fun readyGeneration(): Long?

    /**
     * `eth_call` of [data] (0x-hex) on [to] at the light client's verified
     * head. Blocking: returns within [timeoutMs], [Call.Unavailable] if
     * there was no answer by then. The engine can't run at an older
     * block (a block number still means head state), so a caller needing
     * two calls at one state compares their [Call.Ok.block]s.
     *
     * A call the engine has started can't be cancelled (the engine ABI
     * only drains started work, within its own ~90 s budget), so one the
     * caller gave up on at [timeoutMs] still holds an engine slot until
     * the engine returns. [released], if given, is called exactly once
     * when that happens — or at once, if the call never reached the
     * engine — so the caller can keep an abandoned call counted where it
     * belongs ([EnsResolver]'s per-site cap) until it truly ends.
     * [probe]: the caller's name-independent health probe, served from a
     * slot lookups can't take, so lookups filling the engine can't make
     * a healthy light client look broken.
     */
    fun ethCall(
        to: String,
        data: String,
        timeoutMs: Long,
        probe: Boolean = false,
        released: (() -> Unit)? = null,
    ): Call

    /** One verified call's outcome. */
    sealed class Call {
        /** The call returned [resultHex], proven at [block]. */
        data class Ok(val resultHex: String, val block: Long?) : Call()

        /** The call reverted with [dataHex] — itself a verified answer. */
        data class Revert(val dataHex: String, val block: Long?) : Call()

        /**
         * No verified answer: not ready, busy, an engine or transport error.
         * [notReady]: the host's read gate was closed (or there was no
         * host to ask) — its readiness moved before this caller heard of
         * it, which is no sign the engine itself is struggling, so it
         * doesn't warrant backing off the way a busy or failing engine does.
         * [timedOut]: no answer came within the call's `timeoutMs` — the
         * caller's budget ran out while it waited, and whose fault that
         * was (the engine's, or a slow CCIP gateway's earlier in the same
         * lookup) is for the caller to judge, not a failure in itself.
         * [busy]: every engine slot was taken — back-pressure from calls
         * already running (possibly abandoned ones, see [ethCall]), not a
         * sign the light client can't serve; worth falling back once for,
         * never backing off for.
         */
        data class Unavailable(
            val reason: String,
            val notReady: Boolean = false,
            val timedOut: Boolean = false,
            val busy: Boolean = false,
        ) : Call()
    }

    companion object {
        /**
         * The engine's `myotis_eth_call_json` shape as a [Call]: anything
         * but a well-formed `ok` / `revert` is [Call.Unavailable] — and so
         * is one carrying a `failReason`, the engine's way of reporting a
         * verification failure inside the normal result shape
         * (`myotis_engine.h`). `verified` is not a failure flag: it only
         * says whether the call ran at the finalized block rather than the
         * verified head, and either is a proven answer.
         */
        fun parse(json: String?): Call {
            val o = try {
                JSONObject(json ?: return Call.Unavailable("no answer"))
            } catch (e: Exception) {
                return Call.Unavailable("malformed answer")
            }
            if (o.has("error")) {
                val error = o.optString("error").ifEmpty { "error" }
                // The engine's own full house (`myotis_engine.h`: "native
                // execution busy") is back-pressure, like the host's.
                return Call.Unavailable(error, busy = error.contains("execution busy", ignoreCase = true))
            }
            // Present at all — even empty or non-string — means the proof failed.
            if (o.has("failReason") && !o.isNull("failReason")) {
                return Call.Unavailable("verification failed: ${o.optString("failReason").ifEmpty { "unspecified" }}")
            }
            val block = blockOf(o.opt("blockNumber"))
            return when (o.optString("status")) {
                "ok" -> o.optString("resultHex").takeIf(::isHex)?.let { Call.Ok(it, block) }
                    ?: Call.Unavailable("malformed result")
                "revert" -> o.optString("dataHex").takeIf(::isHex)?.let { Call.Revert(it, block) }
                    ?: Call.Unavailable("malformed revert")
                "unavailable" -> {
                    val reason = o.optString("reason").ifEmpty { "unavailable" }
                    Call.Unavailable(
                        reason,
                        notReady = o.optBoolean("notReady", false),
                        busy = o.optBoolean("busy", reason == "busy"),
                    )
                }
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
