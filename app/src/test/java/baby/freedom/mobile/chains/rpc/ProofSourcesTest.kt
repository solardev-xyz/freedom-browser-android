package baby.freedom.mobile.chains.rpc

import baby.freedom.mobile.ens.EnsColibri
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The router's proof tiers (#329): [MyotisChainSource] over a scripted light client, [ColibriChainSource] over a scripted verifier. */
class ProofSourcesTest {
    private val address = "0x" + "ab".repeat(20)
    private val hash = "0x" + "cd".repeat(32)

    // ---- Myotis ----

    private class Link(var ready: Set<Long> = setOf(1L, 100L), val reply: (String) -> String) : MyotisChainSource.Link {
        val asked = mutableListOf<String>()
        override fun isReady(chainId: Long) = chainId in ready
        override suspend fun read(chainId: Long, method: String, paramsJson: String): String {
            asked += "$chainId $method $paramsJson"
            return reply(method)
        }
    }

    @Test
    fun `the light client is available only on a chain it has ready`() {
        val link = Link(ready = setOf(100L)) { "{}" }
        val source = MyotisChainSource(link)
        assertTrue(source.isAvailable(100))
        // Mainnet switched off, still syncing, parked on a stale anchor or
        // asleep in the background: the service publishes it as not ready.
        assertFalse(source.isAvailable(1))
        // Not a chain the light client covers, even if something says ready.
        link.ready = setOf(137L)
        assertFalse(source.isAvailable(137))
        assertFalse(source.canBroadcast)
    }

    @Test
    fun `a proven answer is verified by the light client, at its block`() = runTest {
        val link = Link { """{"result":"0x2a","blockNumber":1234}""" }
        val r = MyotisChainSource(link).request(100, "eth_getBalance", JSONArray().put(address).put("latest"), emptyList())
        assertEquals("0x2a", r.result)
        assertEquals(ChainTrust.Level.VERIFIED, r.trust.level)
        assertEquals(ChainSource.MYOTIS, r.trust.source)
        assertEquals(1234L, r.trust.block)
        assertEquals(listOf("""100 eth_getBalance ["$address","latest"]"""), link.asked)
    }

    @Test
    fun `a receipt keeps its shape`() = runTest {
        val link = Link { """{"result":{"transactionHash":"$hash","status":"0x1"},"blockNumber":16}""" }
        val r = MyotisChainSource(link).request(1, "eth_getTransactionReceipt", JSONArray().put(hash), emptyList())
        assertEquals(hash, (r.result as JSONObject).getString("transactionHash"))
    }

    @Test
    fun `a light client's proven revert is a deterministic error`() = runTest {
        val link = Link { """{"revert":"0x08c379a0","blockNumber":9}""" }
        try {
            MyotisChainSource(link).request(1, "eth_call", JSONArray().put(JSONObject().put("to", address)), emptyList())
            fail()
        } catch (e: ChainRpcException.Rpc) {
            assertTrue(e.deterministic)
            assertEquals("0x08c379a0", e.data)
        }
    }

    @Test
    fun `no proven answer, or a stale or parked chain, is a failure the router moves on from`() = runTest {
        val replies = listOf(
            """{"status":"unavailable","reason":"light client not ready","notReady":true}""",
            """{"status":"unavailable","reason":"busy","busy":true}""",
            """{"status":"unavailable","reason":"not seen in the blocks the light client scanned"}""",
            """{"result":null}""",
            "not json",
        )
        for (reply in replies) {
            try {
                MyotisChainSource(Link { reply }).request(1, "eth_getTransactionReceipt", JSONArray().put(hash), emptyList())
                fail(reply)
            } catch (e: ChainRpcException) {
                fail("$reply: ${e.message}")
            } catch (e: MyotisChainSource.Unanswered) {
            }
        }
    }

    @Test
    fun `a method it doesn't prove never reaches the light client`() = runTest {
        val link = Link { """{"result":"0x1"}""" }
        try {
            MyotisChainSource(link).request(1, "eth_getLogs", JSONArray().put(JSONObject()), emptyList())
            fail()
        } catch (_: MyotisChainSource.Unanswered) {
        }
        assertTrue(link.asked.isEmpty())
    }

    // ---- Colibri ----

    /** A verifier that answers [final] at once; records what it was asked. */
    private class Verifier(private val final: JSONObject) : EnsColibri.Engine {
        override val available = true
        val created = mutableListOf<Triple<String, String, Long>>()
        var freed = 0
        override fun create(method: String, params: String, chainId: Long, proverFlags: Int, verifyFlags: Int, proverMode: Int): Long {
            created += Triple(method, params, chainId)
            return 7L
        }
        override fun setMinLatestBlockTs(ctx: Long, unixSeconds: Long) = Unit
        override fun execute(ctx: Long) = final.toString()
        override fun setResponse(req: Long, data: ByteArray, nodeIndex: Int) = Unit
        override fun setError(req: Long, error: String, nodeIndex: Int) = Unit
        override fun free(ctx: Long) {
            freed++
        }
    }

    private fun colibri(v: Verifier) = ColibriChainSource(
        EnsColibri(v, http = { _, _, _, _, _ -> throw IOException("no network in this test") }),
        present = { true },
    )

    @Test
    fun `a proof is verified by the Colibri prover`() = runTest {
        val v = Verifier(JSONObject().put("status", "success").put("result", "0x64"))
        val r = colibri(v).request(100, "eth_getBalance", JSONArray().put(address).put("latest"), listOf("https://rpc.gnosis.test"))
        assertEquals("0x64", r.result)
        assertEquals(ChainTrust.Level.VERIFIED, r.trust.level)
        assertEquals(ChainSource.COLIBRI, r.trust.source)
        assertEquals(listOf("gnosis.colibri-proof.tech"), r.trust.agreed)
        assertEquals(Triple("eth_getBalance", """["$address","latest"]""", 100L), v.created.single())
        assertEquals(1, v.freed)
    }

    @Test
    fun `a proof failure is a failure the router moves on from`() = runTest {
        val v = Verifier(JSONObject().put("status", "error").put("error", "invalid proof: state root mismatch"))
        try {
            colibri(v).request(1, "eth_getBalance", JSONArray().put(address).put("latest"), emptyList())
            fail()
        } catch (e: ChainRpcException) {
            fail("a proof failure isn't the chain's answer: ${e.message}")
        } catch (e: EnsColibri.Failure) {
            assertTrue(e.message!!.contains("state root mismatch"))
        }
        assertEquals(1, v.freed)
    }

    @Test
    fun `a Colibri-proven revert is a deterministic error`() = runTest {
        val v = Verifier(JSONObject().put("status", "revert").put("data", "0xdead"))
        try {
            colibri(v).request(1, "eth_call", JSONArray().put(JSONObject().put("to", address)).put("latest"), emptyList())
            fail()
        } catch (e: ChainRpcException.Rpc) {
            assertTrue(e.deterministic)
            assertEquals("0xdead", e.data)
        }
    }

    @Test
    fun `a null receipt is not a proven no`() = runTest {
        val v = Verifier(JSONObject().put("status", "success").put("result", JSONObject.NULL))
        try {
            colibri(v).request(1, "eth_getTransactionReceipt", JSONArray().put(hash), emptyList())
            fail()
        } catch (_: ColibriChainSource.Unanswered) {
        }
    }

    @Test
    fun `a pending nonce and unprovable methods never reach the verifier`() = runTest {
        val v = Verifier(JSONObject().put("status", "success").put("result", "0x1"))
        for ((method, params) in listOf(
            "eth_getTransactionCount" to JSONArray().put(address).put("pending"),
            "eth_gasPrice" to JSONArray(),
            "eth_getLogs" to JSONArray().put(JSONObject()),
        )) {
            try {
                colibri(v).request(1, method, params, emptyList())
                fail(method)
            } catch (_: ColibriChainSource.Unanswered) {
            }
        }
        assertTrue(v.created.isEmpty())
    }

    @Test
    fun `the block number comes from a proven latest block`() = runTest {
        val v = Verifier(JSONObject().put("status", "success").put("result", JSONObject().put("number", "0x2a").put("hash", "0x01")))
        val r = colibri(v).request(1, "eth_blockNumber", JSONArray(), emptyList())
        assertEquals("0x2a", r.result)
        assertEquals(42L, r.trust.block)
        assertEquals(Triple("eth_getBlockByNumber", """["latest",false]""", 1L), v.created.single())
    }

    @Test
    fun `Colibri covers Ethereum and Gnosis only, and only when the verifier may be present`() {
        val source = colibri(Verifier(JSONObject()))
        assertTrue(source.isAvailable(1))
        assertTrue(source.isAvailable(100))
        assertFalse(source.isAvailable(137))
        assertFalse(ColibriChainSource(EnsColibri(Verifier(JSONObject())), present = { false }).isAvailable(1))
        assertFalse(source.canBroadcast)
        assertNull(EnsColibri.CHAINS[137])
    }
}
