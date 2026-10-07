package baby.freedom.swarm

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [MyotisReads] (#329): which reads the light client answers, and how the engine's JSON becomes a JSON-RPC result. */
class MyotisReadsTest {
    private val address = "0x" + "ab".repeat(20)
    private val hash = "0x" + "cd".repeat(32)

    private class Script(
        val account: String? = null,
        val code: String? = null,
        val call: String? = null,
        val receipt: String? = null,
        val block: String? = null,
    ) : MyotisReads.Reader {
        val asked = mutableListOf<String>()
        override fun requestAccount(address: String, block: String) = account.also { asked += "account $address $block" }
        override fun getCode(address: String, block: String) = code.also { asked += "code $address $block" }
        override fun ethCall(from: String, to: String, data: String, value: String, block: String) =
            call.also { asked += "call $from $to $data $value $block" }
        override fun transactionReceipt(txHash: String) = receipt.also { asked += "receipt $txHash" }
        override fun blockByNumber(tag: String, fullTransactions: Boolean) = block.also { asked += "block $tag $fullTransactions" }
    }

    private fun serve(reader: Script, method: String, vararg params: Any?): JSONObject =
        JSONObject(MyotisReads.serve(reader, method, JSONArray().apply { params.forEach { put(it ?: JSONObject.NULL) } }))

    private fun unavailable(reply: JSONObject) = reply.optString("status") == "unavailable" && !reply.has("result")

    private val anchored = """"verifyMethod":"headerChain","failReason":null,"blockNumber":1234"""

    @Test
    fun `balance and nonce come from the proven account, as quantities`() {
        val r = Script(account = """{"exists":true,"nonce":12,"balanceWei":"1000000000000000000",$anchored}""")
        val balance = serve(r, "eth_getBalance", address, "latest")
        assertEquals("0xde0b6b3a7640000", balance.get("result"))
        assertEquals(1234L, balance.getLong("blockNumber"))
        assertEquals("0xc", serve(r, "eth_getTransactionCount", address).get("result"))
        assertEquals(listOf("account $address latest", "account $address latest"), r.asked)
    }

    @Test
    fun `a proven absent account reads as zero`() {
        val r = Script(account = """{"exists":false,"nonce":-1,"balanceWei":null,$anchored}""")
        assertEquals("0x0", serve(r, "eth_getBalance", address, "latest").get("result"))
        assertEquals("0x0", serve(r, "eth_getTransactionCount", address, "latest").get("result"))
    }

    @Test
    fun `a pending nonce or a past block is never asked of the engine`() {
        val r = Script(account = """{"exists":true,"nonce":12,"balanceWei":"1",$anchored}""")
        assertTrue(unavailable(serve(r, "eth_getTransactionCount", address, "pending")))
        assertTrue(unavailable(serve(r, "eth_getBalance", address, "0x10")))
        assertTrue(r.asked.isEmpty())
    }

    @Test
    fun `a proof the engine couldn't anchor is no answer`() {
        val r = Script(account = """{"exists":true,"nonce":1,"balanceWei":"1","verifyMethod":null,"failReason":"beaconNotSynced","blockNumber":1}""")
        val reply = serve(r, "eth_getBalance", address, "latest")
        assertTrue(unavailable(reply))
        assertTrue(reply.getString("reason").contains("beaconNotSynced"))
    }

    @Test
    fun `an engine error is no answer, and a busy engine says so`() {
        assertTrue(unavailable(serve(Script(account = """{"error":"engine unavailable"}"""), "eth_getBalance", address, "latest")))
        val busy = serve(Script(call = """{"error":"native execution busy"}"""), "eth_call", JSONObject().put("to", address).put("data", "0x01"), "latest")
        assertTrue(unavailable(busy))
        assertTrue(busy.optBoolean("busy"))
    }

    @Test
    fun `an eth_call runs with its caller and value, and a proven revert is passed on`() {
        val ok = Script(call = """{"status":"ok","resultHex":"0x2a","blockNumber":9,"verified":false}""")
        val tx = JSONObject().put("from", address).put("to", address).put("input", "0x70a08231").put("value", "0x10")
        assertEquals("0x2a", serve(ok, "eth_call", tx, "latest").get("result"))
        assertEquals(listOf("call $address $address 0x70a08231 16 latest"), ok.asked)

        val reverted = serve(Script(call = """{"status":"revert","dataHex":"0x08c379a0","blockNumber":9}"""), "eth_call", tx)
        assertEquals("0x08c379a0", reverted.get("revert"))
        assertFalse(reverted.has("result"))
    }

    @Test
    fun `a call the engine would run without some of its fields isn't asked`() {
        val r = Script(call = """{"status":"ok","resultHex":"0x2a","blockNumber":9}""")
        for (extra in listOf("gas", "gasPrice", "maxFeePerGas", "nonce", "accessList")) {
            val tx = JSONObject().put("to", address).put("data", "0x01").put(extra, "0x5")
            assertTrue(extra, unavailable(serve(r, "eth_call", tx, "latest")))
        }
        // State overrides, conflicting calldata, contract creation.
        assertTrue(unavailable(serve(r, "eth_call", JSONObject().put("to", address), "latest", JSONObject())))
        assertTrue(unavailable(serve(r, "eth_call", JSONObject().put("to", address).put("data", "0x01").put("input", "0x02"))))
        assertTrue(unavailable(serve(r, "eth_call", JSONObject().put("data", "0x01"))))
        assertTrue(r.asked.isEmpty())
    }

    @Test
    fun `a receipt the engine hasn't seen is no answer, never a proven null`() {
        val notSeen = serve(Script(receipt = "null"), "eth_getTransactionReceipt", hash)
        assertTrue(unavailable(notSeen))
        val other = serve(Script(receipt = """{"transactionHash":"0x${"ee".repeat(32)}","blockNumber":"0x10"}"""), "eth_getTransactionReceipt", hash)
        assertTrue(unavailable(other))
        val mine = serve(Script(receipt = """{"transactionHash":"$hash","blockNumber":"0x10","status":"0x1"}"""), "eth_getTransactionReceipt", hash)
        assertEquals(hash, (mine.get("result") as JSONObject).getString("transactionHash"))
        assertEquals(16L, mine.getLong("blockNumber"))
    }

    @Test
    fun `the block number is the verified head's, and a block past it is no answer`() {
        val head = Script(block = """{"number":"0x1f4","hash":"0x01"}""")
        val n = serve(head, "eth_blockNumber")
        assertEquals("0x1f4", n.get("result"))
        assertEquals(500L, n.getLong("blockNumber"))
        assertEquals(listOf("block latest false"), head.asked)
        assertTrue(unavailable(serve(Script(block = "null"), "eth_getBlockByNumber", "0x999999", false)))
        assertTrue(unavailable(serve(Script(block = """{"number":"0x1"}"""), "eth_getBlockByNumber", "pending", false)))
    }

    @Test
    fun `methods it can't prove aren't answered`() {
        for (m in listOf("eth_getLogs", "eth_estimateGas", "eth_gasPrice", "eth_newFilter")) {
            assertTrue(m, unavailable(serve(Script(), m)))
        }
        assertEquals(setOf(
            "eth_blockNumber", "eth_getBalance", "eth_getTransactionCount", "eth_getCode",
            "eth_call", "eth_getTransactionReceipt", "eth_getBlockByNumber",
        ), MyotisReads.METHODS)
    }
}
