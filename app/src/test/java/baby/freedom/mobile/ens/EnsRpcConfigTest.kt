package baby.freedom.mobile.ens

import baby.freedom.mobile.browser.publicEndpointHint
import baby.freedom.mobile.browser.tooFewEndpointsHint
import baby.freedom.mobile.chains.RpcUrls
import baby.freedom.mobile.chains.rpc.ChainDataRouter
import baby.freedom.mobile.data.ChainStore
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
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
        assertFalse(c.canDisablePublic(EnsRpcConfig.PUBLIC_ENDPOINTS[0]))
        val withOwn = c.copy(customEndpoints = listOf("https://my.node"))
        assertTrue(withOwn.canDisablePublic(EnsRpcConfig.PUBLIC_ENDPOINTS[0]))
        assertTrue(withOwn.canRemoveCustom("https://my.node"))
        val onlyOwn = withOwn.copy(disabledPublicEndpoints = EnsRpcConfig.PUBLIC_ENDPOINTS.toSet())
        assertFalse(onlyOwn.canRemoveCustom("https://my.node"))
        val onlyKey = onlyOwn.copy(customEndpoints = emptyList(), apiKeys = mapOf("infura" to "K"))
        assertFalse(onlyKey.canRemoveKey("infura"))
        assertTrue(onlyKey.copy(customEndpoints = listOf("https://my.node")).canRemoveKey("infura"))
    }

    @Test
    fun `an own endpoint equal to a public one doesn't lock either`() {
        val drpc = "https://eth.drpc.org"
        val c = EnsRpcConfig(
            customEndpoints = listOf("$drpc/"),
            disabledPublicEndpoints = EnsRpcConfig.PUBLIC_ENDPOINTS.toSet() - drpc,
        )
        // Listed once (a trailing slash is the same endpoint)…
        assertEquals(listOf("$drpc/"), c.endpoints)
        // …but dropping either copy still leaves the other.
        assertTrue(c.canRemoveCustom("$drpc/"))
        assertTrue(c.canDisablePublic(drpc))
        assertEquals(listOf(drpc), c.copy(customEndpoints = emptyList()).endpoints)
    }

    @Test
    fun `duplicate endpoints are spotted regardless of case and trailing slash`() {
        val c = EnsRpcConfig(customEndpoints = listOf("https://My.Node:8545/rpc"))
        assertTrue(c.hasCustomEndpoint("https://my.node:8545/rpc/"))
        assertTrue(c.hasCustomEndpoint("HTTPS://MY.NODE:8545/rpc"))
        assertFalse(c.hasCustomEndpoint("https://my.node:8545/RPC"))
        assertFalse(c.hasCustomEndpoint("http://my.node:8545/rpc"))
        assertFalse(c.hasCustomEndpoint("https://my.node:8546/rpc"))
    }

    @Test
    fun `an endpoint key is the request, not the spelling`() {
        val key = EnsRpcConfig::endpointKey
        assertEquals(key("https://eth.drpc.org"), key("https://eth.drpc.org:443"))
        assertEquals(key("https://eth.drpc.org"), key("https://eth.drpc.org.:443/"))
        assertEquals(key("https://eth.drpc.org"), key("https://eth.drpc.org#x"))
        assertEquals(key("http://localhost/rpc"), key("http://LOCALHOST:80/rpc"))
        assertEquals(key("https://1rpc.io/eth"), key("https://1rpc.io/%65%74%68"))
        assertEquals(key("https://1rpc.io/eth"), key("https://1rpc.io/./x/../eth"))
        assertEquals(key("https://a.example/p?k=v"), key("https://a.example/p?%6B=%76"))
        assertEquals(key("https://a.example/a%2fb"), key("https://a.example/a%2Fb"))
        // Escaped dot segments are dot segments once decoded.
        assertEquals(key("https://1rpc.io/eth"), key("https://1rpc.io/x/%2e%2e/eth"))
        assertEquals(key("https://1rpc.io/eth"), key("https://1rpc.io/x/%2E%2E/eth"))
        assertEquals(key("https://1rpc.io/eth"), key("https://1rpc.io/x/.%2e/eth"))
        assertEquals(key("https://1rpc.io/eth"), key("https://1rpc.io/%2e/eth"))
        assertEquals(key("https://1rpc.io/eth"), key("https://1rpc.io/%2e%2e/%2e%2e/eth"))
        assertEquals(key("https://1rpc.io/eth"), key("https://1rpc.io/eth/%2e"))
        assertEquals(key("https://1rpc.io"), key("https://1rpc.io/eth/%2e%2e"))
        // An empty query sends nothing more.
        assertEquals(key("https://eth.drpc.org"), key("https://eth.drpc.org/?"))
        assertEquals(key("https://eth.drpc.org"), key("https://eth.drpc.org?#x"))
        // Still different endpoints:
        assertFalse(key("https://eth.drpc.org") == key("https://eth.drpc.org:8443"))
        assertFalse(key("http://localhost/rpc") == key("http://localhost:443/rpc"))
        assertFalse(key("https://a.example/a%2Fb") == key("https://a.example/a/b"))
        assertFalse(key("https://1rpc.io/eth") == key("https://1rpc.io/ETH"))
        assertFalse(key("https://1rpc.io/eth") == key("https://1rpc.io/x/%2e%2eeth"))
        assertFalse(key("https://1rpc.io/eth") == key("https://1rpc.io/x%2f..%2feth"))
        assertFalse(key("https://eth.drpc.org") == key("https://eth.drpc.org/?a"))
    }

    @Test
    fun `an escaped dot segment doesn't make a public endpoint yours`() {
        val c = EnsRpcConfig(customEndpoints = listOf("https://1rpc.io/x/%2e%2e/eth", "https://eth.drpc.org/?"))
        assertTrue(c.isPublicEndpoint("https://1rpc.io/x/%2e%2e/eth"))
        assertTrue(c.isPublicEndpoint("https://eth.drpc.org/?"))
        assertEquals(1, c.sources.count { it.url.contains("1rpc.io") })
        assertEquals(1, c.sources.count { it.url.contains("eth.drpc.org") })
    }

    @Test
    fun `the default port doesn't make a public endpoint yours`() {
        val c = EnsRpcConfig(customEndpoints = listOf("https://eth.drpc.org:443"))
        assertTrue(c.isPublicEndpoint("https://eth.drpc.org:443"))
        assertEquals(1, c.sources.count { it.url.contains("eth.drpc.org") })
    }

    // ---- one provider, one voter (R6-F1) ----

    /** The four spellings the R6 review got a second quorum vote with. */
    private val r6Spellings = listOf(
        "https://eth.drpc.org/?x=1",
        "https://1rpc.io//eth",
        "https://1rpc.io/eth?x=1",
        "https://ethereum.publicnode.com/?a",
    )

    @Test
    fun `any URL on a built-in public endpoint's host is that endpoint`() {
        val c = EnsRpcConfig()
        for (url in r6Spellings) {
            assertTrue(url, c.isPublicEndpoint(url))
            assertTrue(url, ChainStore.isNameResolutionPublic(1, url))
        }
        assertEquals("eth.drpc.org", EnsRpcConfig.publicEndpointHost("https://ETH.drpc.org./anything?at=all"))
        assertEquals("1rpc.io", EnsRpcConfig.publicEndpointHost("https://1rpc.io/arb"))
        // Another host is not a built-in, even of the same provider…
        assertEquals(null, EnsRpcConfig.publicEndpointHost("https://lb.drpc.org/ogrpc?network=ethereum"))
        assertFalse(c.isPublicEndpoint("https://rpc.flashbots.net"))
        // …and only mainnet's own RPCs are name resolution's.
        assertFalse(ChainStore.isNameResolutionPublic(10, "https://1rpc.io/op"))
    }

    @Test
    fun `refused as yours with the host it is already provided by`() {
        assertTrue(
            publicEndpointHint("https://eth.drpc.org/?x=1"),
            publicEndpointHint("https://eth.drpc.org/?x=1").startsWith("already provided by eth.drpc.org"),
        )
        assertTrue(publicEndpointHint("https://1rpc.io//eth").startsWith("already provided by 1rpc.io"))
    }

    @Test
    fun `each R6 spelling next to its built-in twin is one voter per host`() {
        for (url in r6Spellings) {
            // Stored anyway (an old install's list, or a write that
            // predates the refusal): it takes its provider's one seat.
            val c = EnsRpcConfig(customEndpoints = listOf(url))
            val host = java.net.URI(url).host
            assertEquals(url, 1, c.endpoints.count { java.net.URI(it).host == host })
            assertEquals(url, c.endpoints.first())
            assertEquals(url, 1, EnsQuorum.voters(c.endpoints).count { java.net.URI(it).host == host })
            // Even a pool that lists both spellings gives the host one vote.
            val pool = listOf(url) + EnsRpcConfig.PUBLIC_ENDPOINTS
            assertEquals(url, 1, EnsQuorum.voters(pool).count { java.net.URI(it).host == host })
            assertEquals(url, 1, EnsQuorum.waveOrder(pool, pool).count { java.net.URI(it).host == host })
            assertEquals(url, 5, EnsQuorum.voters(pool).size)
        }
    }

    @Test
    fun `a keyed DRPC and the public DRPC are one voter`() {
        val c = EnsRpcConfig(apiKeys = mapOf("drpc" to "KEY"))
        val keyed = "https://lb.drpc.live/ethereum/KEY"
        assertEquals(ChainDataRouter.providerOf("https://eth.drpc.org"), ChainDataRouter.providerOf(keyed))
        // Your key takes DRPC's seat; the public DRPC isn't asked.
        assertTrue(keyed in c.endpoints)
        assertFalse("https://eth.drpc.org" in c.endpoints)
        assertEquals(keyed, c.publicSkippedFor("https://eth.drpc.org")?.url)
        assertEquals(null, c.publicSkippedFor("https://eth.merkle.io"))
        assertEquals(1, EnsQuorum.voters(listOf(keyed, "https://eth.drpc.org")).size)
        assertEquals(5, c.providerCount)
    }

    @Test
    fun `your own endpoint on another host of a public provider takes its seat`() {
        val mine = "https://lb.drpc.org/ogrpc?network=ethereum"
        val c = EnsRpcConfig(customEndpoints = listOf(mine))
        assertEquals(mine, c.endpoints.first())
        assertFalse("https://eth.drpc.org" in c.endpoints)
        assertEquals(EnsRpcConfig.PUBLIC_ENDPOINTS.size, c.providerCount)
    }

    @Test
    fun `same-host endpoints only are not cross-checked`() {
        val sameHost = listOf("https://my.node/a", "https://my.node/b", "https://rpc.my.node/c")
        assertFalse(EnsQuorum.canCrossCheck(sameHost))
        assertEquals(1, EnsQuorum.voters(sameHost).size)
        val c = EnsRpcConfig(customEndpoints = sameHost, disabledPublicEndpoints = EnsRpcConfig.PUBLIC_ENDPOINTS.toSet())
        assertEquals(3, c.endpoints.size)
        assertEquals(1, c.providerCount)
        val hint = tooFewEndpointsHint(c.providerCount, c.endpoints.size)!!
        assertTrue(hint, hint.contains("only 1 provider") && hint.contains("aren't cross-checked"))
        // Three spellings of one on-device node are one device.
        assertFalse(EnsQuorum.canCrossCheck(listOf("http://localhost:8545", "http://127.0.0.1:8545", "http://[::1]:8545")))
        assertTrue(EnsQuorum.canCrossCheck(listOf("https://my.node/a", "https://eth.drpc.org", "https://1rpc.io/eth")))
    }

    @Test
    fun `runs of slashes are one slash`() {
        val key = EnsRpcConfig::endpointKey
        assertEquals(key("https://1rpc.io/eth"), key("https://1rpc.io//eth"))
        assertEquals(key("https://1rpc.io/eth"), key("https://1rpc.io///eth//"))
        assertEquals(key("https://1rpc.io/eth"), key("https://1rpc.io//x/..//eth"))
    }

    @Test
    fun `endpoint validation is the chains' RPC validation`() {
        assertEquals("https://my.node:8545/rpc", EnsRpcConfig.normalizeEndpoint("  https://my.node:8545/rpc "))
        // Your endpoints are mainnet's own RPCs: http only to this device.
        assertEquals("http://127.0.0.1:8545", EnsRpcConfig.normalizeEndpoint("http://127.0.0.1:8545"))
        assertEquals("http://localhost:8545", EnsRpcConfig.normalizeEndpoint("http://localhost:8545"))
        fun rejection(s: String) = EnsRpcConfig.validateEndpoint(s).rejection
        assertEquals(RpcUrls.Rejection.SCHEME, rejection("http://my.node:8545"))
        assertEquals(RpcUrls.Rejection.INTERNAL_HOST, rejection("https://192.168.1.5:8545"))
        assertEquals(RpcUrls.Rejection.EMPTY, rejection("   "))
        assertEquals(RpcUrls.Rejection.SCHEME, rejection("wss://my.node"))
        assertEquals(RpcUrls.Rejection.SCHEME, rejection("ftp://my.node"))
        assertEquals(RpcUrls.Rejection.NOT_A_URL, rejection("my.node"))
        assertEquals(RpcUrls.Rejection.NOT_A_URL, rejection("https://my node"))
        assertEquals(RpcUrls.Rejection.NOT_A_URL, rejection("https:///path"))
        assertEquals(RpcUrls.Rejection.CREDENTIALS, rejection("https://u:p@my.node"))
        assertEquals(RpcUrls.Rejection.TOO_LONG, rejection("https://a.b/" + "x".repeat(2100)))
        assertEquals(RpcUrls.Rejection.PLACEHOLDER, rejection("https://a.b/v3/{API_KEY}"))
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
        assertEquals(listOf("https://a.node", "https://b.node/x?y=1"), EnsRpcConfig.decodeList("""["https://a.node","https://b.node/x?y=1"]"""))
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
    fun `a lookup that outlives a settings change doesn't fill the new cache`() {
        // Lookup A starts under [a.test] and is still waiting on it when
        // the user swaps it for [b.test]; lookup B runs under the new
        // settings. A's answer (from the dropped endpoint) must not be
        // served to lookups under the new settings.
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val asked = java.util.Collections.synchronizedList(mutableListOf<String>())
        val http = object : EnsHttp {
            override fun request(
                method: String, url: String, headers: Map<String, String>, body: String?,
                timeoutMs: Int, maxBytes: Long, followRedirects: Boolean,
            ): EnsHttp.Reply {
                asked += url
                if (url == "https://a.test") {
                    entered.countDown()
                    release.await(10, TimeUnit.SECONDS)
                }
                return EnsHttp.Reply(
                    200,
                    """{"jsonrpc":"2.0","id":1,"error":{"code":3,"message":"reverted","data":"0x77209fe8"}}""",
                )
            }
        }
        val settings = AtomicReference(EnsResolver.Settings(listOf("https://a.test")))
        val resolver = EnsResolver({ settings.get() }, http)
        val a = thread { runBlocking { resolver.resolveContenthash("x.eth") } }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        settings.set(EnsResolver.Settings(listOf("https://b.test")))
        runBlocking { resolver.resolveContenthash("other.eth") }
        release.countDown()
        a.join(10_000)

        asked.clear()
        runBlocking { resolver.resolveContenthash("x.eth") }
        assertEquals(listOf("https://b.test"), asked.toList())
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
