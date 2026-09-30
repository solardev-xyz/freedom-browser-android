package baby.freedom.mobile.chains.rpc

import baby.freedom.mobile.node.MyotisLink
import baby.freedom.swarm.MyotisReads
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * The router's [ChainSource.MYOTIS] tier (#329): the embedded P2P light
 * client, when the user runs it for the chain and it's ready — synced, a
 * state peer at the head, not parked on a stale anchor or recovering,
 * not asleep with the app in the background. Any of those, or a chain
 * that's switched off, and [isAvailable] is false, so the router starts
 * at Colibri.
 *
 * It answers [MyotisReads.METHODS] — block number, balance, nonce,
 * code, `eth_call`, receipt and block reads, each proven at the light
 * client's verified head — and nothing the engine can't prove: a
 * `pending` nonce, a call with gas or fee fields, a receipt or block it
 * hasn't seen (a transaction mined before it synced reads the same as
 * one that doesn't exist, so that `null` is never passed on as a
 * verified "no"). Those move on to the next tier. Never broadcasts.
 */
internal class MyotisChainSource(private val link: Link = Link.Default) : VerifiedChainSource {
    /** The light client as this source sees it; a seam for tests. */
    interface Link {
        fun isReady(chainId: Long): Boolean
        suspend fun read(chainId: Long, method: String, paramsJson: String): String

        object Default : Link {
            override fun isReady(chainId: Long) = MyotisLink.isReady(chainId)
            override suspend fun read(chainId: Long, method: String, paramsJson: String) =
                MyotisLink.read(chainId, method, paramsJson)
        }
    }

    override fun isAvailable(chainId: Long): Boolean =
        ChainAccessPolicy.supports(ChainSource.MYOTIS, chainId) && link.isReady(chainId)

    override suspend fun request(chainId: Long, method: String, params: JSONArray, rpcs: List<String>): ChainDataResult {
        if (method !in MyotisReads.METHODS) throw Unanswered("the light client doesn't serve $method")
        val reply = try {
            JSONTokener(link.read(chainId, method, params.toString())).nextValue() as? JSONObject
        } catch (_: Exception) {
            null
        } ?: throw Unanswered("unexpected answer from the light client")
        val block = reply.opt("blockNumber").let { (it as? Number)?.toLong() }
        if (reply.has("revert")) {
            throw ChainRpcException.Rpc(
                ChainRpcException.EXECUTION_REVERTED,
                "execution reverted",
                (reply.opt("revert") as? String) ?: "0x",
            )
        }
        if (!reply.has("result")) {
            throw Unanswered(reply.optString("reason").ifEmpty { "no answer" }.take(200))
        }
        val result = reply.get("result")
        if (result == JSONObject.NULL) throw Unanswered("no proven answer")
        return ChainDataResult(
            result,
            ChainTrust(
                level = ChainTrust.Level.VERIFIED,
                source = ChainSource.MYOTIS,
                agreed = listOf(ChainSource.MYOTIS.key),
                dissented = emptyList(),
                queried = listOf(ChainSource.MYOTIS.key),
                k = 1,
                m = 1,
                block = block,
            ),
        )
    }

    /** No proven answer; the router moves on. */
    class Unanswered(message: String) : Exception(message)
}
