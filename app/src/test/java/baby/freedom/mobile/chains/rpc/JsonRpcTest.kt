package baby.freedom.mobile.chains.rpc

import java.math.BigInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonRpcTest {
    @Test
    fun envelopes() {
        assertEquals(JsonRpc.Envelope.Result("0x1"), JsonRpc.parse("""{"jsonrpc":"2.0","id":1,"result":"0x1"}"""))
        assertEquals(JsonRpc.Envelope.Result(null), JsonRpc.parse("""{"id":1,"result":null}"""))
        val revert = JsonRpc.parse("""{"error":{"code":3,"message":"execution reverted","data":"0x08c379a0"}}""")
        val e = (revert as JsonRpc.Envelope.Error).error
        assertEquals("0x08c379a0", e.data)
        assertTrue(e.deterministic)
        val nested = JsonRpc.parse("""{"error":{"code":-32000,"message":"reverted","data":{"data":"0xabcd"}}}""")
        assertEquals("0xabcd", (nested as JsonRpc.Envelope.Error).error.data)
        val limited = JsonRpc.parse("""{"error":{"code":-32005,"message":"limit exceeded","data":"try later"}}""")
        assertFalse((limited as JsonRpc.Envelope.Error).error.deterministic)
        for (bad in listOf("", "<html>", "[]", """[{"result":1}]""", """{"id":1}""", """{"error":{"message":"x"}}""")) {
            assertTrue(bad, JsonRpc.parse(bad) is JsonRpc.Envelope.Malformed)
        }
    }

    @Test
    fun deterministicErrorsVoteOnDataOrCode() {
        val a = ChainRpcException.Rpc(3, "execution reverted: X", "0xABCD")
        val b = ChainRpcException.Rpc(-32000, "reverted", "0xabcd")
        assertEquals(a.voteKey, b.voteKey)
        assertEquals(
            ChainRpcException.Rpc(-32602, "invalid argument 0", null).voteKey,
            ChainRpcException.Rpc(-32602, "missing value for required argument", null).voteKey,
        )
        assertTrue(ChainRpcException.Rpc(-32000, "Insufficient funds for gas * price + value", null).deterministic)
        assertFalse(ChainRpcException.Rpc(-32000, "nonce too low", null).deterministic)
    }

    @Test
    fun stableSerializationIgnoresKeyOrder() {
        val x = JSONObject("""{"b":[1,{"d":null,"c":"x"}],"a":true}""")
        val y = JSONObject("""{"a":true,"b":[1,{"c":"x","d":null}]}""")
        assertEquals(JsonRpc.stable(x), JsonRpc.stable(y))
        assertEquals("""{"a":true,"b":[1,{"c":"x","d":null}]}""", JsonRpc.stable(x))
        assertFalse(JsonRpc.stable("0x1") == JsonRpc.stable("0x01"))
    }

    @Test
    fun callObjectsAreNormalized() {
        val params = JSONArray().put(
            JSONObject().put("to", "0x1").put("input", "0xAB").put("value", "1000000000000000000")
                .put("gas", 21000).put("nonce", "0x00").put("gasPrice", "not a number"),
        ).put("latest")
        val out = JsonRpc.normalizeParams("eth_call", params)
        val call = out.getJSONObject(0)
        assertEquals("0xAB", call.getString("data"))
        assertEquals("0xde0b6b3a7640000", call.getString("value"))
        assertEquals("0x5208", call.getString("gas"))
        assertEquals("0x0", call.getString("nonce"))
        assertEquals("left for the node to reject", "not a number", call.getString("gasPrice"))
        assertEquals("latest", out.getString(1))
        assertEquals("the caller's params aren't touched", 21000, params.getJSONObject(0).getInt("gas"))

        val withData = JSONArray().put(JSONObject().put("data", "0x01").put("input", "0x02"))
        assertEquals("0x01", JsonRpc.normalizeParams("eth_estimateGas", withData).getJSONObject(0).getString("data"))
        val other = JSONArray().put("0xabc").put("latest")
        assertSame(other, JsonRpc.normalizeParams("eth_getBalance", other))
    }

    @Test
    fun quantities() {
        assertEquals("0x0", JsonRpc.quantity("0"))
        assertEquals("0xff", JsonRpc.quantity("0x00FF"))
        assertEquals("0x10", JsonRpc.quantity(16L))
        assertNull(JsonRpc.quantity("-1"))
        assertNull(JsonRpc.quantity("1.5"))
        assertEquals(BigInteger.TEN, WalletRpc.quantity("0xa"))
    }
}
