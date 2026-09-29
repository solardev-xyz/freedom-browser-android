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
}
