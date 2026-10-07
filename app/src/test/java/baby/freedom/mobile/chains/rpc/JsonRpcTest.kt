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

    /**
     * An answer nested past [JsonRpc.MAX_DEPTH] is malformed before the
     * parser sees it (the platform's `org.json` overflows the stack a few
     * thousand levels down; `HostileRpcJsonDeviceTest` runs that one), and
     * brackets inside strings don't count towards it.
     */
    @Test
    fun deeplyNestedAnswersAreMalformedBeforeTheyAreParsed() {
        fun nested(n: Int) = """{"jsonrpc":"2.0","id":1,"result":""" + "[".repeat(n) + "]".repeat(n) + "}"
        // Envelope plus 63 levels: the limit, still an answer.
        assertTrue(JsonRpc.parse(nested(JsonRpc.MAX_DEPTH - 1)) is JsonRpc.Envelope.Result)
        for (n in listOf(JsonRpc.MAX_DEPTH, 5_000)) {
            assertEquals("$n levels", JsonRpc.Envelope.Malformed("nested too deeply"), JsonRpc.parse(nested(n)))
        }
        val deepError = """{"error":{"code":3,"message":"x","data":""" + "{\"a\":".repeat(100) + "1" + "}".repeat(100) + "}}"
        assertEquals(JsonRpc.Envelope.Malformed("nested too deeply"), JsonRpc.parse(deepError))
        // Brackets in a string (escaped quotes too) aren't nesting.
        val text = "\\\"" + "[".repeat(500) + "{".repeat(500)
        assertEquals(JsonRpc.Envelope.Result(text), JsonRpc.parse(JSONObject().put("result", text).toString()))
        assertEquals(2, JsonRpc.depth("""[{"a":"\\","b":"\"{[[","c":"]"}]"""))
        // A real block with access lists is far from it.
        val block = """{"jsonrpc":"2.0","id":1,"result":{"number":"0x1","transactions":[{"hash":"0x00",""" +
            """"accessList":[{"address":"0x00","storageKeys":["0x00"]}]}]}}"""
        assertEquals(7, JsonRpc.depth(block))
        assertTrue(JsonRpc.parse(block) is JsonRpc.Envelope.Result)
    }

    /**
     * The platform's `JSONTokener` is lenient — comments, single-quoted
     * strings, unquoted literals — and the depth count reads the body the
     * same way: a stray `"` the parser skips mustn't hide the brackets
     * after it. Past the count, the parsed tree is measured again.
     */
    @Test
    fun lenientSyntaxDoesNotHideNesting() {
        fun deep(n: Int) = "[".repeat(n) + "]".repeat(n)
        val n = 3_000
        for (prefix in listOf("/* \" */", "// \"\n", "# \"\n", "'\"':1,", "a\":1,", "\"x\"=>\"]]]\",", "\"x\"=\"]]]\",")) {
            val body = """{"jsonrpc":"2.0","id":1,$prefix"result":${deep(n)}}"""
            assertTrue("$prefix: ${JsonRpc.depth(body)}", JsonRpc.depth(body) > JsonRpc.MAX_DEPTH)
            assertEquals(prefix, JsonRpc.Envelope.Malformed("nested too deeply"), JsonRpc.parse(body))
        }
        // An unquoted literal with a quote in it, in an array: `a"` is one value.
        assertEquals(4, JsonRpc.depth("""[a",[[[1]]],b"]"""))
        // What the lexer skips the same way the parser does still doesn't count.
        assertEquals(1, JsonRpc.depth("""{/* [[ */"a":'[[\'[',// [[
b:1 # [[
}"""))
        assertEquals(1, JsonRpc.depth("{/* [[[ unterminated"))

        // The parsed tree, whatever the lexer read.
        var tree: Any = JSONArray()
        repeat(JsonRpc.MAX_DEPTH - 1) { tree = JSONArray().put(tree) }
        assertFalse(JsonRpc.tooDeep(tree))
        assertTrue(JsonRpc.tooDeep(JSONObject().put("r", tree)))
        assertFalse(JsonRpc.tooDeep("0x1"))
    }
}
