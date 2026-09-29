package baby.freedom.mobile.chains.rpc

import java.math.BigInteger
import org.json.JSONArray
import org.json.JSONObject

/**
 * The wallet's typed view of [ChainDataRouter] (#108), iOS's `WalletRPC`:
 * the reads a wallet makes (balances, nonce, gas, calls, receipts) and
 * its broadcast, all as the wallet's own requests
 * ([RoutingContext.WALLET]), each value with the [ChainTrust] behind it.
 * Wallet code goes through here rather than to an RPC URL, so every read
 * gets the chain's verification ladder.
 *
 * Throws what the router throws ([ChainRpcException]), plus
 * [ChainRpcException.InvalidResponse] for an answer of the wrong shape.
 */
class WalletRpc(private val router: ChainDataRouter) {
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
     * object rarely matches byte for byte (#233). Null on a chain
     * without EIP-1559.
     */
    suspend fun baseFeeAt(chainId: Long, block: Long): Reading<BigInteger?> =
        read(chainId, "eth_feeHistory", JSONArray().put("0x1").put("0x" + block.toString(16)).put(JSONArray())) {
            val history = it as? JSONObject ?: throw invalid("eth_feeHistory", it)
            val fees = history.optJSONArray("baseFeePerGas") ?: throw invalid("eth_feeHistory", it)
            val fee = fees.opt(0)
            if (fee == null || fee == JSONObject.NULL) null else quantity(fee).takeIf { f -> f.signum() > 0 }
        }

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

    private suspend fun <T> read(chainId: Long, method: String, params: JSONArray, parse: (Any?) -> T): Reading<T> {
        val r = router.request(chainId, method, params, RoutingContext.WALLET)
        return Reading(parse(r.result), r.trust)
    }

    companion object {
        private val HEX = Regex("^0x[0-9a-fA-F]*$")
        private val QUANTITY = Regex("^0x[0-9a-fA-F]{1,64}$")
        private val TX_HASH = Regex("^0x[0-9a-fA-F]{64}$")

        internal fun quantity(v: Any?): BigInteger =
            (v as? String)?.takeIf { QUANTITY.matches(it) }?.let { BigInteger(it.substring(2), 16) }
                ?: throw invalid("quantity", v)

        private fun invalid(what: String, v: Any?) =
            ChainRpcException.InvalidResponse("$what: ${v?.toString()?.take(80)}")
    }
}
