package baby.freedom.swarm

import java.math.BigInteger
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * The chain-data router's reads (#329), answered by one chain's Myotis
 * engine: a JSON-RPC read ([METHODS]) turned into the engine's verified
 * call, and the engine's JSON turned back into a JSON-RPC `result` — or
 * into "can't answer", so the router moves on to its next tier.
 *
 * Every reply is one JSON object:
 * - `{"result": …, "blockNumber": n}`: proven at block `n`;
 * - `{"revert": "0x…", "blockNumber": n}`: an `eth_call` that reverted,
 *   itself proven;
 * - `{"status":"unavailable","reason": …}` (with `"busy": true` for the
 *   engine's back-pressure): no proven answer.
 *
 * Only what the engine can prove is answered. So:
 * - account, code and call reads only at `latest` (or no tag): the engine
 *   proves them at its verified head, and a `pending` nonce, which counts
 *   the mempool, is nothing it can prove — desktop's router refuses the
 *   same;
 * - an `eth_call` carrying anything but `from`/`to`/`data`/`value` (gas,
 *   fees, a nonce, state overrides) isn't answered: the engine would run
 *   it without them;
 * - a `null` receipt or block is "not seen in the blocks this engine
 *   scanned" — a transaction mined before it synced, or a block past its
 *   head, reads the same — so it's never answered as a proven "no";
 * - a state proof the engine couldn't anchor to the beacon chain
 *   (`failReason`) isn't an answer either.
 */
object MyotisReads {
    /** The engine's reads for one running chain; [MyotisNode] hands its handle's in. */
    interface Reader {
        fun requestAccount(address: String, block: String): String?
        fun getCode(address: String, block: String): String?
        fun ethCall(from: String, to: String, data: String, value: String, block: String): String?
        fun transactionReceipt(txHash: String): String?
        fun blockByNumber(tag: String, fullTransactions: Boolean): String?
    }

    /** The reads Myotis answers. */
    val METHODS = setOf(
        "eth_blockNumber", "eth_getBalance", "eth_getTransactionCount", "eth_getCode",
        "eth_call", "eth_getTransactionReceipt", "eth_getBlockByNumber",
    )

    /** Reads that run the EVM, which the engine admits at most eight of at once across the process. */
    fun executesEvm(method: String): Boolean = method == "eth_call"

    /** Answer [method] with [params] through [reader]; never throws. */
    fun serve(reader: Reader, method: String, params: JSONArray): String = try {
        when (method) {
            "eth_blockNumber" -> block(reader.blockByNumber("latest", false)) { b, n -> quantity(n).takeIf { b.has("number") } }
            "eth_getBalance", "eth_getTransactionCount" -> {
                val address = address(params.opt(0))
                headTag(method, params.opt(1))
                account(reader.requestAccount(address, "latest")) { a ->
                    if (method == "eth_getBalance") {
                        if (!a.optBoolean("exists")) "0x0" else {
                            val wei = a.opt("balanceWei") as? String ?: unavailable("no balance in the proof")
                            "0x" + (wei.toBigIntegerOrNull()?.takeIf { it.signum() >= 0 } ?: unavailable("bad balance")).toString(16)
                        }
                    } else {
                        val nonce = a.optLong("nonce", -1)
                        if (!a.optBoolean("exists")) "0x0" else if (nonce < 0) unavailable("bad nonce") else quantity(nonce)
                    }
                }
            }
            "eth_getCode" -> {
                val address = address(params.opt(0))
                headTag(method, params.opt(1))
                account(reader.getCode(address, "latest")) { c ->
                    if (!c.optBoolean("exists")) "0x" else (c.opt("codeHex") as? String)?.takeIf(HEX::matches)
                        ?: unavailable("no code in the proof")
                }
            }
            "eth_call" -> call(reader, params)
            "eth_getTransactionReceipt" -> {
                val hash = (params.opt(0) as? String)?.takeIf(HASH::matches) ?: unavailable("not a transaction hash")
                val text = reader.transactionReceipt(hash) ?: unavailable("no result from the engine")
                val receipt = parse(text) as? JSONObject
                    ?: unavailable("not seen in the blocks the light client scanned")
                engineError(receipt)
                if (!(receipt.opt("transactionHash") as? String).equals(hash, ignoreCase = true)) {
                    unavailable("the receipt names another transaction")
                }
                answer(receipt, blockOf(receipt.opt("blockNumber")))
            }
            "eth_getBlockByNumber" -> {
                val tag = params.opt(0) as? String
                if (tag != "latest" && (tag == null || !BLOCK_NUMBER.matches(tag))) unavailable("serves only latest or a block number")
                val full = params.opt(1) == true
                block(reader.blockByNumber(tag, full)) { b, _ -> b }
            }
            else -> unavailable("the light client doesn't serve $method")
        }
    } catch (e: Unanswered) {
        e.reply
    } catch (e: Exception) {
        unavailableJson(e.message ?: e.javaClass.simpleName)
    }

    private fun call(reader: Reader, params: JSONArray): String {
        if (params.length() > 2 && params.opt(2) != null && params.opt(2) != JSONObject.NULL) unavailable("state overrides")
        headTag("eth_call", params.opt(1))
        val tx = params.opt(0) as? JSONObject ?: unavailable("no call object")
        for (key in tx.keys()) {
            val v = tx.opt(key)
            if (key !in CALL_FIELDS && v != null && v != JSONObject.NULL && v != "") unavailable("a call with \"$key\"")
        }
        val to = address(tx.opt("to"))
        val from = tx.opt("from").takeIf { it != null && it != JSONObject.NULL && it != "" }?.let(::address) ?: ""
        val data = calldata(tx.opt("data"))
        val input = calldata(tx.opt("input"))
        if (data != null && input != null && !data.equals(input, ignoreCase = true)) unavailable("conflicting data and input")
        val value = tx.opt("value").takeIf { it != null && it != JSONObject.NULL && it != "" }?.let { v ->
            (v as? String)?.takeIf(QUANTITY::matches)?.let { BigInteger(it.substring(2), 16).toString() }
                ?: unavailable("bad value")
        } ?: "0"
        val text = reader.ethCall(from, to, data ?: input ?: "0x", value, "latest") ?: unavailable("no result from the engine")
        val o = parse(text) as? JSONObject ?: unavailable("unexpected answer")
        val block = blockOf(o.opt("blockNumber"))
        return when (o.optString("status")) {
            "ok" -> answer((o.opt("resultHex") as? String)?.takeIf(HEX::matches) ?: unavailable("no return data"), block)
            "revert" -> JSONObject()
                .put("revert", (o.opt("dataHex") as? String)?.takeIf(HEX::matches) ?: "0x")
                .apply { block?.let { put("blockNumber", it) } }
                .toString()
            else -> {
                engineError(o)
                unavailable(o.optString("reason").ifEmpty { "unavailable" }, busy = o.optBoolean("busy"))
            }
        }
    }

    /** An account or code proof: [value] of it, when the engine anchored it. */
    private inline fun account(text: String?, value: (JSONObject) -> Any): String {
        val o = parse(text ?: unavailable("no result from the engine")) as? JSONObject ?: unavailable("unexpected answer")
        engineError(o)
        o.opt("failReason").takeIf { it is String && it.isNotEmpty() }?.let { unavailable("proof not anchored: $it") }
        if (o.opt("verifyMethod") !is String) unavailable("proof not anchored")
        return answer(value(o), blockOf(o.opt("blockNumber")))
    }

    /** A block read: [value] of the block, or no answer for `null`. */
    private inline fun block(text: String?, value: (JSONObject, Long) -> Any?): String {
        val parsed = parse(text ?: unavailable("no result from the engine"))
        val b = parsed as? JSONObject ?: unavailable("block not seen by the light client")
        engineError(b)
        val n = blockOf(b.opt("number")) ?: unavailable("block without a number")
        return answer(value(b, n) ?: unavailable("unexpected block"), n)
    }

    /** Throw [Unanswered] for the engine's `{"error"}`. */
    private fun engineError(o: JSONObject) {
        val error = o.opt("error") as? String ?: return
        unavailable(error.take(200), busy = error.contains("execution busy", ignoreCase = true))
    }

    private fun answer(result: Any, block: Long?): String =
        JSONObject().put("result", result).apply { block?.let { put("blockNumber", it) } }.toString()

    private fun headTag(method: String, tag: Any?) {
        if (tag == null || tag == JSONObject.NULL || tag == "latest") return
        unavailable("$method at \"$tag\": the light client proves only the latest block")
    }

    private fun address(v: Any?): String = (v as? String)?.takeIf(ADDRESS::matches) ?: unavailable("not an address")

    private fun calldata(v: Any?): String? = when {
        v == null || v == JSONObject.NULL || v == "" -> null
        v is String && HEX.matches(v) -> v
        else -> unavailable("bad calldata")
    }

    private fun quantity(n: Long) = "0x" + n.toString(16)

    /** A block number as the engine prints it: a JSON number or a `0x` quantity. */
    internal fun blockOf(v: Any?): Long? = when (v) {
        is Number -> v.toLong().takeIf { it >= 0 }
        is String -> v.takeIf(QUANTITY::matches)?.let { runCatching { java.lang.Long.parseUnsignedLong(it.substring(2), 16) }.getOrNull() }
            ?.takeIf { it >= 0 }
        else -> null
    }

    private fun parse(text: String): Any? = try {
        JSONTokener(text).nextValue()
    } catch (_: Exception) {
        null
    }?.takeIf { it != JSONObject.NULL }

    private class Unanswered(val reply: String) : Exception(null, null, false, false)

    private fun unavailable(reason: String, busy: Boolean = false): Nothing = throw Unanswered(unavailableJson(reason, busy))

    private fun unavailableJson(reason: String, busy: Boolean = false): String =
        JSONObject().put("status", "unavailable").put("reason", reason).apply { if (busy) put("busy", true) }.toString()

    private val CALL_FIELDS = setOf("from", "to", "data", "input", "value")
    private val HEX = Regex("^0x[0-9a-fA-F]*$")
    private val QUANTITY = Regex("^0x[0-9a-fA-F]{1,64}$")
    private val BLOCK_NUMBER = Regex("^0x[1-9a-fA-F][0-9a-fA-F]{0,15}$")
    private val ADDRESS = Regex("^0x[0-9a-fA-F]{40}$")
    private val HASH = Regex("^0x[0-9a-fA-F]{64}$")
}
