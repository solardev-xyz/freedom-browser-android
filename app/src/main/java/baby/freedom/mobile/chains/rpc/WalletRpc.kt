package baby.freedom.mobile.chains.rpc

import java.math.BigInteger
import org.json.JSONArray
import org.json.JSONObject

/**
 * The wallet's typed view of [ChainDataRouter] (#108), iOS's `WalletRPC`:
 * the reads a wallet makes (balances, nonce, gas, calls, receipts) and
 * its broadcast, as the wallet's own requests ([RoutingContext.WALLET])
 * unless built for a site's ([context]), each value with the [ChainTrust] behind it.
 * Wallet code goes through here rather than to an RPC URL, so every read
 * gets the chain's verification ladder.
 *
 * Throws what the router throws ([ChainRpcException]), plus
 * [ChainRpcException.InvalidResponse] for an answer of the wrong shape.
 */
class WalletRpc(
    private val router: ChainDataRouter,
    /**
     * Whose reads these are: the wallet's own by default. A read of
     * something a site chose — an x402 offer's token contract — is the
     * site's ([RoutingContext.forPage]), so a slow or unprovable contract
     * of its choosing gets a page's share of the proof tiers' slots and
     * can't back them off for the wallet's own reads (#329 R4-F1).
     */
    private val context: RoutingContext = RoutingContext.WALLET,
) {
    /** A value read from a chain, and how it was checked. */
    data class Reading<T>(val value: T, val trust: ChainTrust)

    suspend fun blockNumber(chainId: Long): Reading<Long> =
        read(chainId, "eth_blockNumber", JSONArray()) {
            quantity(it).takeIf { n -> n.bitLength() < 63 }?.toLong() ?: throw invalid("eth_blockNumber", it)
        }

    /** [address]'s native balance in wei. */
    suspend fun balance(chainId: Long, address: String, block: String = "latest"): Reading<BigInteger> =
        read(chainId, "eth_getBalance", JSONArray().put(address).put(block), ::quantity)

    /** [address]'s nonce: transactions sent, counting those still pending by default. */
    suspend fun transactionCount(chainId: Long, address: String, block: String = "pending"): Reading<BigInteger> =
        read(chainId, "eth_getTransactionCount", JSONArray().put(address).put(block), ::quantity)

    suspend fun gasPrice(chainId: Long): Reading<BigInteger> =
        read(chainId, "eth_gasPrice", JSONArray(), ::quantity)

    /** The tip a node suggests for a type-2 transaction (`eth_maxPriorityFeePerGas`). */
    suspend fun maxPriorityFeePerGas(chainId: Long): Reading<BigInteger> =
        read(chainId, "eth_maxPriorityFeePerGas", JSONArray(), ::quantity)

    /**
     * The latest block's `baseFeePerGas`, or null on a chain without
     * EIP-1559 (the block has none).
     */
    suspend fun latestBaseFee(chainId: Long): Reading<BigInteger?> =
        read(chainId, "eth_getBlockByNumber", JSONArray().put("latest").put(false)) {
            val block = it as? JSONObject ?: throw invalid("eth_getBlockByNumber", it)
            val fee = block.opt("baseFeePerGas")
            if (fee == null || fee == JSONObject.NULL) null else quantity(fee)
        }

    /**
     * The base fee block [block] paid, from `eth_feeHistory` — a small
     * answer that RPCs a block apart, and on different clients, still
     * give the same way for one pinned block, where a whole block
     * object rarely matches byte for byte (#233). The quorum compares
     * only the block and its base fees ([feeHistoryBaseFees]): providers
     * shape the rest differently (`"reward":[[]]` present or absent,
     * blob fields, how `gasUsedRatio` is printed), and agreement mustn't
     * hang on which of them hold the seats (R2-M1). Null on a chain
     * without EIP-1559.
     */
    suspend fun baseFeeAt(chainId: Long, block: Long): Reading<BigInteger?> =
        read(
            chainId, "eth_feeHistory", JSONArray().put("0x1").put("0x" + block.toString(16)).put(JSONArray()),
            agreeOn = ::feeHistoryBaseFees,
        ) {
            val history = it as? JSONObject ?: throw invalid("eth_feeHistory", it)
            val fees = history.optJSONArray("baseFeePerGas") ?: throw invalid("eth_feeHistory", it)
            // The block asked for, not another the RPCs happen to agree on.
            if (history.opt("oldestBlock") != "0x" + block.toString(16)) throw invalid("eth_feeHistory", it)
            val fee = fees.opt(0)
            if (fee == null || fee == JSONObject.NULL) null else quantity(fee).takeIf { f -> f.signum() > 0 }
        }

    /**
     * Whether block [block] exists yet, as the chain's RPCs answer
     * `eth_getBlockByNumber` for it (only whether the answer is null is
     * compared, not the block). A verified "no" bounds how far behind
     * the real head any block number below it can be (#233 R2-F1).
     */
    suspend fun blockExists(chainId: Long, block: Long): Reading<Boolean> =
        read(
            chainId, "eth_getBlockByNumber", JSONArray().put("0x" + block.toString(16)).put(false),
            agreeOn = { it != null && it != JSONObject.NULL },
        ) { it == true }

    /** Gas for the call object [tx] (`from`, `to`, `value`, `data`, …). */
    suspend fun estimateGas(chainId: Long, tx: JSONObject): Reading<BigInteger> =
        read(chainId, "eth_estimateGas", JSONArray().put(tx), ::quantity)

    /**
     * `eth_call`: the return data (`0x…`). A revert is thrown as a
     * deterministic [ChainRpcException.Rpc] carrying its data.
     */
    suspend fun call(chainId: Long, tx: JSONObject, block: String = "latest"): Reading<String> =
        read(chainId, "eth_call", JSONArray().put(tx).put(block)) {
            (it as? String)?.takeIf { s -> HEX.matches(s) } ?: throw invalid("eth_call", it)
        }

    /** The contract code at [address] (`0x` for none: an account, or a contract not deployed yet). */
    suspend fun code(chainId: Long, address: String, block: String = "latest"): Reading<String> =
        read(chainId, "eth_getCode", JSONArray().put(address).put(block)) {
            (it as? String)?.takeIf { s -> HEX.matches(s) } ?: throw invalid("eth_getCode", it)
        }

    /**
     * The receipt, or `null` while the transaction is pending (or unknown).
     * One that doesn't name [txHash] as its `transactionHash` — another
     * transaction's, or a made-up one — is [ChainRpcException.InvalidResponse]:
     * a node can't have a send shown as landed by answering with any receipt
     * at all (#229).
     */
    suspend fun receipt(chainId: Long, txHash: String): Reading<JSONObject?> =
        read(chainId, "eth_getTransactionReceipt", JSONArray().put(txHash)) {
            when (it) {
                null -> null
                is JSONObject -> it.takeIf { r -> (r.opt("transactionHash") as? String).equals(txHash, ignoreCase = true) }
                    ?: throw invalid("eth_getTransactionReceipt", it)
                else -> throw invalid("eth_getTransactionReceipt", it)
            }
        }

    /** Send a signed transaction; its hash. */
    suspend fun sendRawTransaction(chainId: Long, rawTransaction: String): Reading<String> {
        val r = router.broadcast(chainId, rawTransaction)
        val hash = (r.result as? String)?.takeIf { TX_HASH.matches(it) }
            ?: throw invalid("eth_sendRawTransaction", r.result)
        return Reading(hash, r.trust)
    }

    private suspend fun <T> read(chainId: Long, method: String, params: JSONArray, parse: (Any?) -> T): Reading<T> =
        read(chainId, method, params, null, parse)

    private suspend fun <T> read(
        chainId: Long,
        method: String,
        params: JSONArray,
        agreeOn: ((Any?) -> Any?)?,
        parse: (Any?) -> T,
    ): Reading<T> {
        val r = router.request(chainId, method, params, context, agreeOn)
        return Reading(parse(r.result), r.trust)
    }

    companion object {
        private val HEX = Regex("^0x[0-9a-fA-F]*$")
        private val QUANTITY = Regex("^0x[0-9a-fA-F]{1,64}$")
        private val TX_HASH = Regex("^0x[0-9a-fA-F]{64}$")

        internal fun quantity(v: Any?): BigInteger =
            (v as? String)?.takeIf { QUANTITY.matches(it) }?.let { BigInteger(it.substring(2), 16) }
                ?: throw invalid("quantity", v)

        /**
         * The part of an `eth_feeHistory` answer [baseFeeAt] needs, in one
         * spelling: `oldestBlock` and each `baseFeePerGas` as canonical
         * hex. Anything else (not an object, a malformed quantity) is kept
         * as it came, to disagree or be refused as usual.
         */
        internal fun feeHistoryBaseFees(v: Any?): Any? {
            val history = v as? JSONObject ?: return v
            val fees = history.optJSONArray("baseFeePerGas") ?: return v
            fun canonical(x: Any?): Any? =
                (x as? String)?.takeIf { QUANTITY.matches(it) }?.let { "0x" + BigInteger(it.substring(2), 16).toString(16) } ?: x
            val out = JSONArray()
            for (i in 0 until fees.length()) out.put(canonical(fees.opt(i)))
            return JSONObject().put("oldestBlock", canonical(history.opt("oldestBlock"))).put("baseFeePerGas", out)
        }

        private fun invalid(what: String, v: Any?) =
            ChainRpcException.InvalidResponse("$what: ${v?.toString()?.take(80)}")
    }
}
