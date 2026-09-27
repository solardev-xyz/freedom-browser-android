package baby.freedom.mobile.ens

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Name-resolution settings (#102): the order [EnsRpcConfig] hands the
 * resolver, and the resolver picking up a change on its next lookup.
 */
class EnsRpcConfigTest {

    // ---- EnsRpcConfig ----

    @Test
    fun `defaults are the public endpoints with CCIP-Read on`() {
        val c = EnsRpcConfig()
        assertEquals(EnsRpcConfig.PUBLIC_ENDPOINTS, c.endpoints)
        assertTrue(c.ccipRead)
        assertEquals(EnsResolver.DEFAULT_RPC_ENDPOINTS, c.endpoints)
    }

    @Test
    fun `order is yours, then keyed providers, then enabled public endpoints`() {
        val c = EnsRpcConfig(
            customEndpoints = listOf("https://b.node", "https://a.node"),
            disabledPublicEndpoints = setOf("https://1rpc.io/eth", "https://eth.drpc.org"),
            apiKeys = mapOf("infura" to " KEY2 ", "alchemy" to "KEY1"),
        )
        assertEquals(
            listOf(
                "https://b.node",
                "https://a.node",
                "https://eth-mainnet.g.alchemy.com/v2/KEY1",
                "https://mainnet.infura.io/v3/KEY2",
                "https://ethereum.publicnode.com",
                "https://eth-mainnet.public.blastapi.io",
                "https://eth.merkle.io",
            ),
            c.endpoints,
        )
        assertEquals(
            listOf(
                EnsRpcConfig.Kind.CUSTOM, EnsRpcConfig.Kind.CUSTOM,
                EnsRpcConfig.Kind.KEYED, EnsRpcConfig.Kind.KEYED,
                EnsRpcConfig.Kind.PUBLIC, EnsRpcConfig.Kind.PUBLIC, EnsRpcConfig.Kind.PUBLIC,
            ),
            c.sources.map { it.kind },
        )
    }

    @Test
    fun `an endpoint listed twice is tried once, at its first place`() {
        val c = EnsRpcConfig(customEndpoints = listOf("https://eth.merkle.io"))
        assertEquals("https://eth.merkle.io", c.endpoints.first())
        assertEquals(1, c.endpoints.count { it == "https://eth.merkle.io" })
    }

    @Test
    fun `the last endpoint can't be removed`() {
        val allButOne = EnsRpcConfig.PUBLIC_ENDPOINTS.drop(1).toSet()
        val c = EnsRpcConfig(disabledPublicEndpoints = allButOne)
        assertFalse(c.canRemove(EnsRpcConfig.PUBLIC_ENDPOINTS[0]))
        val withOwn = c.copy(customEndpoints = listOf("https://my.node"))
        assertTrue(withOwn.canRemove(EnsRpcConfig.PUBLIC_ENDPOINTS[0]))
        assertTrue(withOwn.canRemove("https://my.node"))
    }

    @Test
    fun `endpoint validation`() {
        assertEquals("https://my.node:8545/rpc", EnsRpcConfig.normalizeEndpoint("  https://my.node:8545/rpc "))
        assertEquals("http://192.168.1.5:8545", EnsRpcConfig.normalizeEndpoint("http://192.168.1.5:8545"))
        fun rejection(s: String) = EnsRpcConfig.validateEndpoint(s).rejection
        assertEquals(EnsRpcConfig.Rejection.EMPTY, rejection("   "))
        assertEquals(EnsRpcConfig.Rejection.SCHEME, rejection("wss://my.node"))
        assertEquals(EnsRpcConfig.Rejection.SCHEME, rejection("ftp://my.node"))
        assertEquals(EnsRpcConfig.Rejection.NOT_A_URL, rejection("my.node"))
        assertEquals(EnsRpcConfig.Rejection.NOT_A_URL, rejection("https://my node"))
        assertEquals(EnsRpcConfig.Rejection.NOT_A_URL, rejection("https:///path"))
        assertEquals(EnsRpcConfig.Rejection.USER_INFO, rejection("https://u:p@my.node"))
        assertEquals(EnsRpcConfig.Rejection.TOO_LONG, rejection("https://a.b/" + "x".repeat(2100)))
    }

    @Test
    fun `redact keeps the key out of logs`() {
        assertEquals("https://mainnet.infura.io/…", EnsRpcConfig.redact("https://mainnet.infura.io/v3/SECRET"))
        assertEquals("https://eth.merkle.io", EnsRpcConfig.redact("https://eth.merkle.io"))
        assertEquals("http://10.0.0.2:8545", EnsRpcConfig.redact("http://10.0.0.2:8545/"))
        assertEquals("••••cdef", EnsRpcConfig.maskKey("0123456789abcdef"))
        assertEquals("••••", EnsRpcConfig.maskKey("abc"))
    }

    @Test
    fun `storage round-trips and drops unknown providers`() {
        val list = listOf("https://a.node", "https://b.node/x?y=1")
        assertEquals(list, EnsRpcConfig.decodeList(EnsRpcConfig.encodeList(list)))
        assertEquals(emptyList<String>(), EnsRpcConfig.decodeList("not json"))
        val keys = EnsRpcConfig.decodeKeys("""{"alchemy":"k1","nope":"k2","drpc":"  "}""")
        assertEquals(mapOf("alchemy" to "k1"), keys)
        assertEquals(keys, EnsRpcConfig.decodeKeys(EnsRpcConfig.encodeKeys(keys)))
    }

    // ---- RpcEndpointCheck ----

    private fun check(code: Int, reply: String): RpcEndpointCheck.Outcome {
        val http = object : EnsHttp {
            override fun request(
                method: String, url: String, headers: Map<String, String>, body: String?,
                timeoutMs: Int, maxBytes: Long, followRedirects: Boolean,
            ): EnsHttp.Reply {
                assertEquals("eth_chainId", org.json.JSONObject(body!!).getString("method"))
                return EnsHttp.Reply(code, reply)
            }
        }
        return RpcEndpointCheck.check("https://x.test", http)
    }

    @Test
    fun `endpoint check wants Ethereum mainnet`() {
        assertTrue(check(200, """{"jsonrpc":"2.0","id":1,"result":"0x1"}""") is RpcEndpointCheck.Outcome.Ok)
        assertEquals(
            RpcEndpointCheck.Outcome.WrongChain("100"),
            check(200, """{"jsonrpc":"2.0","id":1,"result":"0x64"}"""),
        )
        assertEquals(RpcEndpointCheck.Outcome.Failed("HTTP 401"), check(401, "unauthorized"))
        assertEquals(
            RpcEndpointCheck.Outcome.Failed("invalid key"),
            check(200, """{"jsonrpc":"2.0","id":1,"error":{"code":-32000,"message":"invalid key"}}"""),
        )
        assertEquals(RpcEndpointCheck.Outcome.Failed("not a JSON-RPC endpoint"), check(200, "<html>"))
    }

    // ---- EnsResolver picks up settings live ----

    /** Answers `ResolverNotFound` (a cacheable answer) from [good], fails the rest. */
    private class Http(var good: Set<String>) : EnsHttp {
        val asked = mutableListOf<String>()
        override fun request(
            method: String, url: String, headers: Map<String, String>, body: String?,
            timeoutMs: Int, maxBytes: Long, followRedirects: Boolean,
        ): EnsHttp.Reply {
            asked += url
            if (url !in good) throw IOException("down")
            return EnsHttp.Reply(
                200,
                """{"jsonrpc":"2.0","id":1,"error":{"code":3,"message":"reverted","data":"0x77209fe8"}}""",
            )
        }
    }

    @Test
    fun `a settings change applies to the next lookup, cache included`() {
        var settings = EnsResolver.Settings(listOf("https://a.test"))
        val http = Http(good = setOf("https://a.test", "https://b.test"))
        val resolver = EnsResolver({ settings }, http)
        runBlocking {
            assertTrue(resolver.resolveContenthash("x.eth") is EnsResult.NotFound)
            // Cached: no second request.
            resolver.resolveContenthash("x.eth")
            assertEquals(listOf("https://a.test"), http.asked)

            settings = EnsResolver.Settings(listOf("https://b.test"))
            http.asked.clear()
            resolver.resolveContenthash("x.eth")
            assertEquals(listOf("https://b.test"), http.asked)
        }
    }

    @Test
    fun `endpoints are tried in the configured order, a failed one moves last`() {
        val http = Http(good = setOf("https://b.test"))
        val resolver = EnsResolver({ EnsResolver.Settings(listOf("https://a.test", "https://b.test")) }, http)
        runBlocking {
            resolver.resolveContenthash("one.eth")
            assertEquals(listOf("https://a.test", "https://b.test"), http.asked)
            http.asked.clear()
            // a.test failed a moment ago: b.test is asked first now.
            resolver.resolveContenthash("two.eth")
            assertEquals(listOf("https://b.test"), http.asked)
        }
    }

    @Test
    fun `a success doesn't pin later lookups to that endpoint`() {
        // The old rotation stayed on whichever endpoint answered last;
        // the user's first endpoint must keep coming first.
        val http = Http(good = setOf("https://a.test", "https://b.test"))
        val resolver = EnsResolver({ EnsResolver.Settings(listOf("https://a.test", "https://b.test")) }, http)
        runBlocking {
            resolver.resolveContenthash("one.eth")
            resolver.resolveContenthash("two.eth")
        }
        assertEquals(listOf("https://a.test", "https://a.test"), http.asked)
    }

    @Test
    fun `no endpoints is an error, not a crash`() {
        val resolver = EnsResolver({ EnsResolver.Settings(emptyList()) }, Http(emptySet()))
        val r = runBlocking { resolver.resolveContenthash("x.eth") }
        require(r is EnsResult.Error) { "got $r" }
        assertEquals("NO_RPC_ENDPOINTS", r.reason)
    }

    @Test
    fun `with CCIP-Read off an OffchainLookup isn't followed`() {
        val revert = javaClass.getResource("/ccip/offchainexample-revert.hex")!!.readText().trim()
        val asked = mutableListOf<String>()
        val http = object : EnsHttp {
            override fun request(
                method: String, url: String, headers: Map<String, String>, body: String?,
                timeoutMs: Int, maxBytes: Long, followRedirects: Boolean,
            ): EnsHttp.Reply {
                asked += url
                return EnsHttp.Reply(
                    200,
                    """{"jsonrpc":"2.0","id":1,"error":{"code":3,"message":"reverted","data":"$revert"}}""",
                )
            }
        }
        var ccip = false
        val resolver = EnsResolver({ EnsResolver.Settings(listOf("https://rpc.test"), ccipRead = ccip) }, http)
        val r = runBlocking { resolver.resolveContenthash("1.offchainexample.eth") }
        require(r is EnsResult.Error) { "got $r" }
        assertEquals("CCIP_DISABLED", r.reason)
        assertFalse(r.retryable)
        // Only the RPC was asked; no gateway saw the name.
        assertEquals(listOf("https://rpc.test"), asked)

        // Turning it back on follows the lookup straight away (the
        // refusal wasn't cached): the gateway is asked now.
        ccip = true
        asked.clear()
        runBlocking { resolver.resolveContenthash("1.offchainexample.eth") }
        assertTrue(asked.any { it.startsWith("https://ccip-v3.ens.xyz") })
    }
}
