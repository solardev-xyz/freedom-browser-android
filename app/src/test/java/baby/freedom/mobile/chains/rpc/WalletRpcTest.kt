package baby.freedom.mobile.chains.rpc

import baby.freedom.mobile.chains.Chain
import java.math.BigInteger
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/** [WalletRpc]: the wallet's reads, through the router, typed. */
class WalletRpcTest {
    private val urls = listOf("https://a.example", "https://b.example", "https://c.example")
    private val sent = mutableListOf<String>()

    private fun wallet(answer: (String) -> String) = WalletRpc(
        ChainDataRouter(
            chains = { listOf(Chain(id = 10, name = "Test", symbol = "T", rpcUrls = urls)) },
            transport = RpcTransport { _, body, _ ->
                synchronized(sent) { sent += body }
                val method = JSONObject(body).getString("method")
                """{"jsonrpc":"2.0","id":1,${answer(method)}}"""
            },
        ),
    )

    @Test
    fun readsGoThroughTheQuorumAsTheWalletsOwn() = runBlocking {
        val w = wallet { m ->
            when (m) {
                "eth_getBalance" -> "\"result\":\"0xde0b6b3a7640000\""
                "eth_getTransactionCount" -> "\"result\":\"0x5\""
                "eth_blockNumber" -> "\"result\":\"0x10\""
                "eth_getTransactionReceipt" -> "\"result\":null"
                else -> "\"result\":\"0x\""
            }
        }
        val balance = w.balance(10, "0x0000000000000000000000000000000000000001")
        assertEquals(BigInteger.TEN.pow(18), balance.value)
        assertEquals(ChainTrust.Level.VERIFIED, balance.trust.level)
        assertEquals(BigInteger.valueOf(5), w.transactionCount(10, "0x01").value)
        assertEquals(16L, w.blockNumber(10).value)
        assertNull(w.receipt(10, "0x" + "0".repeat(64)).value)
        val nonce = JSONObject(sent.first { "eth_getTransactionCount" in it }).getJSONArray("params")
        assertEquals("pending", nonce.getString(1))
    }

    @Test
    fun aReceiptForAnotherTransactionOrNoneNamedIsRefused() = runBlocking {
        val hash = "0x" + "ab".repeat(32)
        var receipt = "{\"status\":\"0x1\",\"blockNumber\":\"0x10\",\"transactionHash\":\"${hash.uppercase().replace("0X", "0x")}\"}"
        val w = wallet { "\"result\":$receipt" }
        assertEquals(16, Integer.decode(w.receipt(10, hash).value!!.getString("blockNumber")))
        for (other in listOf(
            // Another transaction's receipt, and one that names none (#229).
            "{\"status\":\"0x1\",\"blockNumber\":\"0x10\",\"transactionHash\":\"0x${"cd".repeat(32)}\"}",
            "{\"status\":\"0x1\",\"blockNumber\":\"0x10\"}",
        )) {
            receipt = other
            try {
                w.receipt(10, hash)
                fail(other)
            } catch (e: ChainRpcException) {
                // Refused, whichever way the router words an answer no source gave in a usable shape.
            }
        }
    }

    @Test
    fun aRevertComesBackWithItsData() = runBlocking {
        val w = wallet { """"error":{"code":3,"message":"execution reverted","data":"0x08c379a0"}""" }
        try {
            w.call(10, JSONObject().put("to", "0x01").put("data", "0x"))
            fail()
        } catch (e: ChainRpcException.Rpc) {
            assertEquals("0x08c379a0", e.data)
        }
    }

    @Test
    fun aWrongShapeIsAnInvalidResponse() = runBlocking {
        val w = wallet { "\"result\":\"12\"" }
        try {
            w.balance(10, "0x01")
            fail()
        } catch (_: ChainRpcException.InvalidResponse) {
        }
    }

    @Test
    fun aSitesReadsReachTheProofTiersAsTheSites() = runBlocking {
        // An x402 offer's token contract is the site's choice: its reads go
        // to the proof tiers as a page's, never as the wallet's (#329 R4-F1).
        val seen = mutableListOf<RoutingContext>()
        val proof = object : VerifiedChainSource {
            override fun isAvailable(chainId: Long) = true
            override suspend fun request(
                chainId: Long,
                method: String,
                params: org.json.JSONArray,
                rpcs: List<String>,
                context: RoutingContext,
            ): ChainDataResult {
                seen += context
                return ChainDataResult(
                    "0x" + "0".repeat(63) + "6",
                    ChainTrust(ChainTrust.Level.VERIFIED, ChainSource.MYOTIS, emptyList(), emptyList(), emptyList(), 1, 1, null),
                )
            }
        }
        val router = ChainDataRouter(
            chains = { listOf(Chain(id = 100, name = "Gnosis", symbol = "XDAI", rpcUrls = urls)) },
            transport = RpcTransport { _, _, _ -> error("the proof tier answers") },
            verifiedSources = mapOf(ChainSource.MYOTIS to proof),
        )
        val tx = JSONObject().put("to", "0x" + "11".repeat(20)).put("data", "0x313ce567")
        WalletRpc(router).call(100, tx)
        WalletRpc(router, RoutingContext.forSiteChoice("https://pay.example")).call(100, tx)
        assertEquals(listOf(RoutingContext.WALLET, RoutingContext.forSiteChoice("https://pay.example")), seen.toList())
    }

    @Test
    fun aBlocksTimestampIsAgreedOnByNumberAndTimeAlone() = runBlocking {
        // Each provider shapes the rest of the block its own way; only the
        // number and timestamp are compared (#439).
        var number = "0xfe"
        val w = WalletRpc(
            ChainDataRouter(
                chains = { listOf(Chain(id = 10, name = "Test", symbol = "T", rpcUrls = urls)) },
                transport = RpcTransport { url, _, _ ->
                    """{"jsonrpc":"2.0","id":1,"result":{"number":"$number","timestamp":"0x6b49d200","extra":"$url"}}"""
                },
            ),
        )
        val t = w.blockTimestamp(10, 0xfe)
        assertEquals(1_800_000_000L, t.value)
        assertEquals(ChainTrust.Level.VERIFIED, t.trust.level)
        // Another block than the one asked for is refused.
        number = "0xff"
        try {
            w.blockTimestamp(10, 0xfe)
            fail()
        } catch (_: ChainRpcException) {
        }
    }
}
