package baby.freedom.mobile.chains.rpc

import baby.freedom.mobile.ens.EnsColibri
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject

/**
 * The router's [ChainSource.COLIBRI] tier (#329): corpus.core's Colibri
 * stateless verifier ([EnsColibri], the one name resolution uses) checks
 * a remote prover's proof on this device against the chain's sync
 * committee, on Ethereum and Gnosis. With *privacy mode basic* the state
 * a read touches is fetched from the chain's own RPCs (the router's pool,
 * the user's first) and checked against the proven state root; the
 * prover learns which accounts and storage a read touches, as the RPCs
 * do.
 *
 * It answers [METHODS] — what the core can prove — and nothing else:
 * a `pending` nonce or balance (the mempool is nothing a proof covers)
 * goes to the next tier, as does a `null` receipt, transaction or block,
 * which says only that the prover didn't find it, never a proven "no".
 * `eth_blockNumber` is answered from a proven `latest` block, no older
 * than [EnsColibri.MAX_LATEST_AGE_SECONDS]. At most [maxInFlight] reads
 * at once: past that the router moves straight on rather than queueing
 * behind the verifier's one lock (which name resolution shares). Never
 * broadcasts.
 */
internal class ColibriChainSource(
    private val colibri: EnsColibri,
    /** Whether the verifier may be usable here without loading it: [isAvailable] runs on the UI thread. */
    private val present: () -> Boolean,
    private val maxInFlight: Int = MAX_IN_FLIGHT,
) : VerifiedChainSource {
    private val inFlight = AtomicInteger()

    override fun isAvailable(chainId: Long): Boolean =
        chainId in EnsColibri.CHAINS && ChainAccessPolicy.supports(ChainSource.COLIBRI, chainId) && present()

    override suspend fun request(chainId: Long, method: String, params: JSONArray, rpcs: List<String>): ChainDataResult {
        if (method !in METHODS) throw Unanswered("Colibri doesn't prove $method")
        TAG_PARAM[method]?.let { i ->
            val tag = params.opt(i)
            if (tag == "pending") throw Unanswered("$method at \"pending\" can't be proven")
        }
        if (inFlight.incrementAndGet() > maxInFlight) {
            inFlight.decrementAndGet()
            throw Unanswered("Colibri is busy")
        }
        try {
            val blockNumber = method == "eth_blockNumber"
            val (status, provers) = if (blockNumber) {
                colibri.request(chainId, "eth_getBlockByNumber", JSONArray().put("latest").put(false), rpcs)
            } else {
                colibri.request(chainId, method, params, rpcs)
            }
            if (status.optString("status") == "revert") {
                if (method != "eth_call") throw Unanswered("the verifier reported a revert for $method")
                throw ChainRpcException.Rpc(
                    ChainRpcException.EXECUTION_REVERTED,
                    "execution reverted",
                    status.optString("data", "0x").ifEmpty { "0x" },
                )
            }
            val raw = status.opt("result")
            if (raw == null || raw == JSONObject.NULL) throw Unanswered("no proven answer for $method")
            val result: Any = if (blockNumber) {
                (raw as? JSONObject)?.opt("number") as? String ?: throw Unanswered("a block without a number")
            } else {
                raw
            }
            val hosts = provers.ifEmpty {
                listOfNotNull(EnsColibri.CHAINS[chainId]?.provers?.firstOrNull()?.let(EnsColibri::hostOf))
            }
            return ChainDataResult(
                result,
                ChainTrust(
                    level = ChainTrust.Level.VERIFIED,
                    source = ChainSource.COLIBRI,
                    agreed = hosts,
                    dissented = emptyList(),
                    queried = hosts,
                    k = 1,
                    m = 1,
                    block = blockOf(raw),
                ),
            )
        } finally {
            inFlight.decrementAndGet()
        }
    }

    /** No proven answer; the router moves on. */
    class Unanswered(message: String) : Exception(message)

    companion object {
        const val MAX_IN_FLIGHT = 4

        /** What the core proves (colibri.h, `c4_get_method_support` PROOFABLE), and the router asks. */
        val METHODS = setOf(
            "eth_blockNumber", "eth_getBalance", "eth_getTransactionCount", "eth_getCode", "eth_getStorageAt",
            "eth_call", "eth_getTransactionReceipt", "eth_getTransactionByHash",
            "eth_getBlockByNumber", "eth_getBlockByHash",
        )

        /** Where each state read's block tag sits in its params. */
        private val TAG_PARAM = mapOf(
            "eth_getBalance" to 1, "eth_getTransactionCount" to 1, "eth_getCode" to 1,
            "eth_getStorageAt" to 2, "eth_call" to 1, "eth_getBlockByNumber" to 0,
        )

        /** The block a proven receipt, transaction or block is in, when it says. */
        private fun blockOf(result: Any?): Long? {
            val o = result as? JSONObject ?: return null
            val hex = (o.opt("blockNumber") ?: o.opt("number")) as? String ?: return null
            return hex.takeIf { it.startsWith("0x") && it.length in 3..17 }
                ?.let { runCatching { it.substring(2).toLong(16) }.getOrNull() }
        }
    }
}
