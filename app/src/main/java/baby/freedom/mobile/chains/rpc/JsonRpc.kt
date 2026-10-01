package baby.freedom.mobile.chains.rpc

import java.math.BigInteger
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** What reaches a [ChainDataRouter] caller besides an answer (and `CancellationException`). */
sealed class ChainRpcException(message: String) : Exception(message) {
    class UnknownChain(val chainId: Long) : ChainRpcException("Unknown chain $chainId")

    class UnsupportedMethod(val method: String) : ChainRpcException("Not a chain read: $method")

    /**
     * A node's JSON-RPC error. [deterministic]: the error *is* the
     * answer — a revert (with its [data]), invalid params, insufficient
     * funds — so every other source would say the same and the walk ends
     * with it. Otherwise it's one node's trouble (rate limit, a node
     * rejecting a transaction), and the walk moves on.
     */
    class Rpc(
        val code: Int,
        val rpcMessage: String,
        /** Revert data (`0x…`) when the node gave any. */
        val data: String?,
    ) : ChainRpcException("RPC error $code: $rpcMessage") {
        val insufficientFunds: Boolean get() = INSUFFICIENT_FUNDS.containsMatchIn(rpcMessage)

        val deterministic: Boolean
            get() = data != null || code == INVALID_PARAMS || code == EXECUTION_REVERTED || insufficientFunds

        /**
         * What two nodes must share to be giving the same deterministic
         * answer: the revert data, else the code — wording differs from
         * client to client.
         */
        internal val voteKey: String
            get() = when {
                data != null -> "revert:${data.lowercase()}"
                insufficientFunds -> "insufficient-funds"
                else -> "error:$code"
            }
    }

    /** An answer that isn't the shape the method promises. */
    class InvalidResponse(detail: String) : ChainRpcException("Invalid response: $detail")

    /**
     * No source answered. [failures] says why each tier didn't
     * (`"quorum: …"`), with hosts only — never a URL, which can carry a
     * key. [nodeError]: the last node that answered with an error of its
     * own, e.g. a transaction rejection. [unanswered]: for a broadcast,
     * some source was asked and never gave a verdict (a timeout, a
     * dropped connection) — it may have taken the transaction whatever
     * the others said.
     */
    class AllSourcesFailed(
        val failures: List<String>,
        val nodeError: Rpc?,
        val unanswered: Boolean = false,
        /**
         * The most useful failure a caller's `rankError` picked out of the
         * walk ([ChainDataRouter.request]), when it ranked one above
         * [ChainDataRouter.ErrorRank.ENDPOINT]; null otherwise.
         */
        val kept: ChainFailure? = null,
    ) : ChainRpcException(
        "No chain source answered (" + failures.joinToString("; ") + ")" +
            (nodeError?.let { " — ${it.message}" } ?: ""),
    )

    /** A broadcast that may have gone out; reconcile the signed transaction rather than resend it. */
    class BroadcastUncertain(detail: String) : ChainRpcException("Broadcast outcome uncertain: $detail")

    companion object {
        const val INVALID_PARAMS = -32602

        /** Geth's (and most clients') code for `execution reverted`. */
        const val EXECUTION_REVERTED = 3

        private val INSUFFICIENT_FUNDS = Regex("insufficient funds", RegexOption.IGNORE_CASE)
    }
}

/**
 * One way one source failed a read, as [ChainDataRouter.request]'s
 * `rankError` sees it: a node's JSON-RPC error ([code] set, its [message]
 * and revert [data]), or a failure no node answered with — a transport
 * error, a source that isn't ready ([code] null). [timeout]: the attempt
 * ran out of time rather than being refused.
 */
data class ChainFailure(
    val code: Int?,
    val message: String,
    val data: String?,
    val timeout: Boolean,
) {
    internal companion object {
        fun of(e: ChainRpcException.Rpc) = ChainFailure(e.code, e.rpcMessage, e.data, timeout = false)
    }
}

/** JSON-RPC 2.0 over the wire: bodies, envelopes, and the stable form answers are compared in. */
internal object JsonRpc {
    sealed interface Envelope {
        data class Result(val value: Any?) : Envelope
        data class Error(val error: ChainRpcException.Rpc) : Envelope
        data class Malformed(val reason: String) : Envelope
    }

    /** One request body. Every endpoint gets these exact bytes. */
    fun body(method: String, params: JSONArray): String = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", 1)
        .put("method", method)
        .put("params", params)
        .toString()

    /**
     * [text], an RPC's answer, as an envelope. A body nested deeper than
     * [MAX_DEPTH] is [Envelope.Malformed] before it reaches the parser:
     * the platform's `org.json` recurses once per level with no limit of
     * its own, and a few thousand levels — a 10 KB answer — overflow the
     * stack with an error no `catch (Exception)` on the way up stops,
     * which took the app down from a quorum leg's detached scope.
     */
    fun parse(text: String): Envelope {
        if (depth(text) > MAX_DEPTH) return Envelope.Malformed("nested too deeply")
        val obj = try {
            JSONTokener(text).nextValue() as? JSONObject
        } catch (_: Exception) {
            null
        } catch (_: StackOverflowError) {
            null
        } ?: return Envelope.Malformed("not a JSON-RPC object")
        obj.optJSONObject("error")?.let { err ->
            val code = (err.opt("code") as? Number)?.toInt() ?: return Envelope.Malformed("error without a code")
            val data = when (val d = err.opt("data")) {
                is String -> d.takeIf { HEX_DATA.matches(it) }
                // Some nodes nest it: {"data": {"data": "0x…"}}.
                is JSONObject -> (d.opt("data") as? String)?.takeIf { HEX_DATA.matches(it) }
                else -> null
            }
            return Envelope.Error(ChainRpcException.Rpc(code, err.optString("message").take(500), data))
        }
        if (!obj.has("result")) return Envelope.Malformed("neither result nor error")
        val value = obj.opt("result")
        return Envelope.Result(if (value == JSONObject.NULL) null else value)
    }

    /**
     * [value] serialized with object keys sorted, so two nodes that send
     * the same answer with keys in another order still agree —
     * desktop's `stableValue`.
     */
    fun stable(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().sorted()
            .joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + stable(value.opt(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { stable(value.opt(it)) }
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }

    private val HEX_DATA = Regex("^0x[0-9a-fA-F]*$")

    /**
     * How deep an answer may nest, envelope included. The deepest a read
     * the router carries goes is a block with its transactions' access
     * lists (`{result:{transactions:[{accessList:[{storageKeys:[`, 7).
     */
    internal const val MAX_DEPTH = 64

    /**
     * The deepest `[`/`{` nesting in [text], brackets inside strings not
     * counted; stops counting past [MAX_DEPTH]. Not a validator: the
     * parser still refuses what isn't JSON.
     */
    internal fun depth(text: String): Int {
        var depth = 0
        var max = 0
        var inString = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                if (c == '\\') i++ else if (c == '"') inString = false
            } else {
                when (c) {
                    '"' -> inString = true
                    '[', '{' -> {
                        depth++
                        if (depth > max) max = depth
                        if (max > MAX_DEPTH) return max
                    }
                    ']', '}' -> depth--
                }
            }
            i++
        }
        return max
    }

    private val CALL_OBJECT_METHODS = setOf("eth_call", "eth_estimateGas")
    private val CALL_QUANTITY_FIELDS = listOf("value", "gas", "gasPrice", "maxFeePerGas", "maxPriorityFeePerGas", "nonce")

    /**
     * Desktop's `normalizeParams`: a call object's numbers become hex
     * QUANTITYs (a decimal makes strict nodes answer `-32602`), and
     * `input` — the standard calldata field — is copied to `data`, the
     * legacy alias some nodes and every light-client path read. Done once,
     * before any tier, so every endpoint sees the same bytes.
     */
    fun normalizeParams(method: String, params: JSONArray): JSONArray {
        if (method !in CALL_OBJECT_METHODS || params.length() == 0) return params
        val call = params.opt(0) as? JSONObject ?: return params
        val out = JSONObject(call.toString())
        val data = calldata(call.opt("data"))
        val input = calldata(call.opt("input"))
        if (input != null && data == null) out.put("data", call.opt("input"))
        for (field in CALL_QUANTITY_FIELDS) {
            val v = call.opt(field) ?: continue
            if (v == JSONObject.NULL || v == "") continue
            quantity(v)?.let { out.put(field, it) }
        }
        val copy = JSONArray().put(out)
        for (i in 1 until params.length()) copy.put(params.opt(i))
        return copy
    }

    private fun calldata(v: Any?): String? =
        (v as? String)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != "0x" }

    /** Canonical hex for a number given as hex, a decimal string or a JSON number; `null` if it's none of those. */
    internal fun quantity(v: Any): String? {
        val n = try {
            when (v) {
                is String -> {
                    val t = v.trim()
                    if (t.startsWith("0x", ignoreCase = true)) BigInteger(t.substring(2), 16) else BigInteger(t)
                }
                is Int, is Long -> BigInteger.valueOf((v as Number).toLong())
                is BigInteger -> v
                is java.math.BigDecimal -> v.toBigIntegerExact()
                else -> return null
            }
        } catch (_: Exception) {
            return null
        }
        if (n.signum() < 0) return null
        return "0x" + n.toString(16)
    }
}
