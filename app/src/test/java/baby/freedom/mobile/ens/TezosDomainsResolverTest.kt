package baby.freedom.mobile.ens

import baby.freedom.mobile.ens.TezosDomainsResolver.Leg
import baby.freedom.mobile.ens.TezosDomainsResolver.Outcome
import java.util.Collections
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TezosDomainsResolver], scripted through the [EnsHttp] seam. Ports the
 * cases of desktop's `src/main/tezos-domains-resolver.test.js`, with the
 * same registry fixtures (proxy → registry → `%records` / `%expiry_map`).
 */
class TezosDomainsResolverTest {

    private val one = "https://rpc-one.test"
    private val two = "https://rpc-two.test"
    private val three = "https://rpc-three.test"
    private val threeEndpoints = listOf(one, two, three)

    private val proxyScript = JSONObject(
        """{"code":[{"prim":"storage","args":[{"prim":"pair","args":[
            {"prim":"address","annots":["%contract"]},{"prim":"address","annots":["%owner"]}]}]}],
          "storage":{"prim":"Pair","args":[{"string":"KT1GBZmSxmnKJXGMdMLbugPfLyUPmuLSMwKS"},
            {"string":"KT1BzeXvLtPR83aj5FHemXmia6DmdXkeV3Uk"}]}}""",
    )

    private val registryScript = JSONObject(
        """{"code":[{"prim":"storage","args":[{"prim":"pair","args":[
            {"prim":"big_map","args":[{"prim":"bytes"},{"prim":"pair","args":[
              {"prim":"map","annots":["%data"]},{"prim":"option","annots":["%expiry_key"]}]}],
             "annots":["%records"]},
            {"prim":"big_map","annots":["%expiry_map"]}]}]}],
          "storage":{"prim":"Pair","args":[{"int":"1264"},{"int":"1262"}]}}""",
    )

    private fun jsonHex(value: Any): String =
        JSONArray().put(value).toString().removePrefix("[").removeSuffix("]")
            .toByteArray(Charsets.UTF_8).toHex()

    private fun record(vararg entries: Pair<String, Any>): String {
        val data = JSONArray()
        for ((k, v) in entries) {
            data.put(
                JSONObject().put("prim", "Elt").put(
                    "args",
                    JSONArray().put(JSONObject().put("string", k)).put(JSONObject().put("bytes", jsonHex(v))),
                ),
            )
        }
        return JSONObject().put("prim", "Pair").put(
            "args",
            JSONArray().put(data).put(
                JSONObject().put("prim", "Some").put("args", JSONArray().put(JSONObject().put("bytes", "aabbcc"))),
            ),
        ).toString()
    }

    private class Rpc(
        private val defaultRecord: String?,
        private val expiry: String = """{"string":"2099-01-01T00:00:00Z"}""",
        private val headLevels: Map<String, Int> = emptyMap(),
        private val recordsByEndpoint: Map<String, String?>? = null,
        private val anchorHashes: Map<String, String> = emptyMap(),
        private val down: Set<String> = emptySet(),
        private val scripts: Map<String, JSONObject>,
        /** Head block timestamps per provider; none by default. */
        private val headTimes: Map<String, String> = emptyMap(),
    ) : EnsHttp {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override fun request(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: String?,
            timeoutMs: Int,
            maxBytes: Long,
            followRedirects: Boolean,
        ): EnsHttp.Reply {
            calls += url
            val origin = Regex("^https://[^/]+").find(url)!!.value
            if (origin in down) throw java.io.IOException("connection refused")
            fun ok(json: String) = EnsHttp.Reply(200, json)
            return when {
                url.endsWith("/chains/main/chain_id") -> ok("\"NetXdQprcVkpaWU\"")
                url.endsWith("/blocks/head/header") -> ok(
                    JSONObject().put("level", headLevels[origin] ?: 1000)
                        .apply { headTimes[origin]?.let { put("timestamp", it) } }.toString(),
                )
                Regex("/blocks/\\d+/hash$").containsMatchIn(url) ->
                    ok("\"${anchorHashes[origin] ?: "BLockHashSharedByProviders"}\"")
                url.contains("KT1F7JKNqwaoLzRsMio1MQC7zv3jG9dHcDdJ/script/normalized") -> ok(scripts["proxy"].toString())
                url.contains("KT1GBZmSxmnKJXGMdMLbugPfLyUPmuLSMwKS/script/normalized") -> ok(scripts["registry"].toString())
                url.contains("/big_maps/1264/") -> {
                    val rec = if (recordsByEndpoint != null) recordsByEndpoint[origin] else defaultRecord
                    if (rec == null) EnsHttp.Reply(404, "") else ok(rec)
                }
                url.contains("/big_maps/1262/") -> ok(expiry)
                else -> throw IllegalStateException("Unexpected RPC request: $url")
            }
        }
    }

    private fun rpc(
        record: String?,
        expiry: String = """{"string":"2099-01-01T00:00:00Z"}""",
        headLevels: Map<String, Int> = emptyMap(),
        recordsByEndpoint: Map<String, String?>? = null,
        anchorHashes: Map<String, String> = emptyMap(),
        down: Set<String> = emptySet(),
        headTimes: Map<String, String> = emptyMap(),
    ) = Rpc(
        record, expiry, headLevels, recordsByEndpoint, anchorHashes, down,
        mapOf("proxy" to proxyScript, "registry" to registryScript), headTimes,
    )

    private fun answer(outcome: Outcome): Outcome.Answer {
        assertTrue("expected an answer, got $outcome", outcome is Outcome.Answer)
        return outcome as Outcome.Answer
    }

    @Test
    fun `BLAKE2b matches the RFC 7693 vectors`() {
        // BLAKE2b-512("abc"), RFC 7693 Appendix A.
        assertEquals(
            "ba80a53f981c4d0d6a2797b69f12f6e94c212f14685ac4b74b12bb6fdbffa2d1" +
                "7d87c5392aab792dc252d5de4533cc9518d38aa8dbf1925ab92386edd4009923",
            Blake2b.hash("abc".toByteArray(), 64).toHex(),
        )
        // BLAKE2b-256 of the empty input, and of a multi-block input.
        assertEquals(
            "0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8",
            Blake2b.hash(ByteArray(0), 32).toHex(),
        )
        assertEquals(
            "3a486e3fe3ee414853000269ac020030aeef748cb05cd62ba85939ec298ef25c",
            Blake2b.hash(ByteArray(300) { it.toByte() }, 32).toHex(),
        )
    }

    @Test
    fun `derives the canonical Tezos ScriptExpr hash`() {
        assertEquals(
            "exprusUkj4PJBxvW1zeyb2JWiGTWF77vDLxMzPBv8LKtHrKGbDzmeB",
            TezosDomainsResolver.scriptExprHash("awesome-tezos.tez".toByteArray()),
        )
    }

    @Test
    fun `validates tez names`() {
        assertTrue(TezosDomainsResolver.isTezosDomainName("docs.example.tez"))
        assertFalse(TezosDomainsResolver.isTezosDomainName("example.eth"))
        assertFalse(TezosDomainsResolver.isTezosDomainName("bad..tez"))
        assertFalse(TezosDomainsResolver.isTezosDomainName("a b.tez"))
        assertEquals(NameSystem.TEZOS, NameSystem.forName("Alice.TEZ"))
        assertEquals(EnsInput.Parsed("alice.tez", "/docs"), EnsInput.parse("alice.tez/docs"))
        assertEquals(
            EnsInput.Constrained("alice.tez", "", "ipfs"),
            EnsInput.parseConstrained("ipfs://alice.tez"),
        )
    }

    @Test
    fun `parses IPFS publication paths and rejects non-HTTP redirects`() {
        val leg = TezosDomainsResolver.parsePublishedUri("ipfs://bafybeigdyrzt/site/", redirect = false)
        assertEquals(Leg(Leg.Type.OK, protocol = "ipfs", uri = "ipfs://bafybeigdyrzt/site"), leg)
        assertEquals(
            "ipfs://bafybeigdyrzt",
            TezosDomainsResolver.parsePublishedUri("ipfs://bafybeigdyrzt/", redirect = false).uri,
        )
        assertEquals(
            Leg.Type.UNSUPPORTED,
            TezosDomainsResolver.parsePublishedUri("ipfs://bafybeigdyrzt", redirect = true).type,
        )
        assertEquals(
            "https://kukai.app/",
            TezosDomainsResolver.parsePublishedUri("https://kukai.app", redirect = true).uri,
        )
        assertEquals(
            Leg.Type.UNSUPPORTED,
            TezosDomainsResolver.parsePublishedUri("https://user:pw@example.com/", redirect = false).type,
        )
        assertEquals(
            Leg.Type.UNSUPPORTED,
            TezosDomainsResolver.parsePublishedUri("ftp://example.com/", redirect = false).type,
        )
    }

    @Test
    fun `rejects website records that point back at a dweb name`() {
        for (uri in listOf("ipns://self.tez", "ipfs://other.tez", "ipns://vitalik.eth", "ipns://self.tez.", "ipns://self%2Etez")) {
            val leg = TezosDomainsResolver.parsePublishedUri(uri, redirect = false)
            assertEquals(uri, Leg.Type.UNSUPPORTED, leg.type)
            assertTrue(uri, leg.reason!!.contains("must reference content, not a name"))
        }
        // DNSLink hosts stay valid — only dweb *names* are refused.
        assertEquals(Leg.Type.OK, TezosDomainsResolver.parsePublishedUri("ipns://docs.example.org", false).type)
    }

    @Test
    fun `appends the typed path to an http content URL, keeping its base path`() {
        val f = TezosDomainsResolver::appendWebsiteSuffix
        assertEquals("https://example.com/site/", f("https://example.com/site/", ""))
        assertEquals("https://example.com/site/docs", f("https://example.com/site/", "/docs"))
        assertEquals("https://example.com/page?v=2", f("https://example.com/page?v=2", ""))
        assertEquals("https://example.com/page/x?v=2", f("https://example.com/page?v=2", "/x"))
        assertEquals("https://example.com/x?q=1#h", f("https://example.com/", "/x?q=1#h"))
    }

    @Test
    fun `requires matching public RPC results and prefers redirect_url`() = runBlocking {
        val http = rpc(
            record(
                "web:content_url" to "ipfs://bafybeigdyrzt/site",
                "web:redirect_url" to "https://example.com/welcome",
                "td:ttl" to 120,
            ),
        )
        val a = answer(TezosDomainsResolver(threeEndpoints, http).resolveOutcome("example.tez"))
        assertEquals(Leg.Type.OK, a.leg.type)
        assertEquals("https", a.leg.protocol)
        assertEquals("https://example.com/welcome", a.leg.uri)
        assertTrue(a.leg.redirect)
        assertTrue(a.verified)
        assertEquals(3, a.agreed)
        assertEquals(3, a.asked)

        val ens = TezosDomainsResolver.toEnsResult("example.tez", a) as EnsResult.Ok
        assertEquals("https://example.com/welcome", ens.uri)
        assertTrue(ens.redirect)
    }

    @Test
    fun `returns native IPNS content with its published base path`() = runBlocking {
        val http = rpc(record("web:content_url" to "ipns://docs.example/site"))
        val resolver = TezosDomainsResolver(listOf(one, two), http)
        val a = answer(resolver.resolveOutcome("docs.tez"))
        assertTrue(a.verified)
        assertEquals(2, a.agreed)
        assertEquals(
            EnsResult.Ok("docs.tez", "ipns", "ipns://docs.example/site", "docs.example"),
            resolver.resolve("docs.tez"),
        )
    }

    @Test
    fun `a name with no website record, or not registered, is not found`() = runBlocking {
        val none = TezosDomainsResolver(threeEndpoints, rpc(record("openid:name" to "Bob")))
        assertEquals(EnsResult.NotFound("bob.tez", "NO_WEBSITE_RECORD"), none.resolve("bob.tez"))
        val unregistered = TezosDomainsResolver(threeEndpoints, rpc(null))
        assertEquals(EnsResult.NotFound("nobody.tez", "NOT_REGISTERED"), unregistered.resolve("nobody.tez"))
    }

    @Test
    fun `flags conflicting provider answers instead of picking a side`() = runBlocking {
        val http = rpc(
            null,
            recordsByEndpoint = mapOf(
                one to record("web:content_url" to "ipfs://bafybeigdyrzt/site"),
                two to record("web:content_url" to "ipfs://bafyother/site"),
            ),
        )
        val outcome = TezosDomainsResolver(listOf(one, two), http).resolveOutcome("contested.tez")
        assertTrue(outcome is Outcome.Conflict)
        outcome as Outcome.Conflict
        assertEquals("Tezos RPC providers returned conflicting results", outcome.reason)
        assertTrue(outcome.detail, outcome.detail.contains("ipfs://bafybeigdyrzt/site: rpc-one.test"))
        assertTrue(outcome.detail, outcome.detail.contains("ipfs://bafyother/site: rpc-two.test"))
        val ens = TezosDomainsResolver.toEnsResult("contested.tez", outcome) as EnsResult.Error
        assertEquals("PROVIDER_CONFLICT", ens.reason)
        assertFalse(ens.retryable)
    }

    @Test
    fun `refuses expired domains`() = runBlocking {
        val http = rpc(
            record("web:content_url" to "ipfs://bafybeigdyrzt/site"),
            expiry = """{"string":"2001-01-01T00:00:00Z"}""",
        )
        assertEquals(
            EnsResult.NotFound("stale.tez", "EXPIRED"),
            TezosDomainsResolver(threeEndpoints, http).resolve("stale.tez"),
        )
    }

    @Test
    fun `excludes a provider whose head level deviates wildly from the median`() = runBlocking {
        val http = rpc(record("web:content_url" to "ipfs://bafybeigdyrzt/site"), headLevels = mapOf(three to 100))
        val a = answer(TezosDomainsResolver(threeEndpoints, http).resolveOutcome("anchored.tez"))
        assertTrue(a.verified)
        assertEquals(2, a.asked)
        assertFalse(http.calls.any { it.startsWith(three) && it.contains("/big_maps/") })
    }

    @Test
    fun `refuses to resolve when two providers disagree about the chain head`() = runBlocking {
        val http = rpc(
            null,
            headLevels = mapOf(two to 400),
            recordsByEndpoint = mapOf(
                one to record("web:content_url" to "ipfs://bafybeigdyrzt/site"),
                two to record("web:content_url" to "ipfs://bafyEVIL/site"),
            ),
        )
        val outcome = TezosDomainsResolver(listOf(one, two), http).resolveOutcome("lagged.tez")
        assertTrue(outcome is Outcome.Conflict)
        outcome as Outcome.Conflict
        assertEquals("Tezos RPC providers disagree about the chain head", outcome.reason)
        assertTrue(outcome.detail.contains("chain head #1000: rpc-one.test"))
        assertTrue(outcome.detail.contains("chain head #400: rpc-two.test"))
        assertFalse(outcome.toString().contains("bafyEVIL"))
    }

    // The public-RPC failure mode behind the PR's refusals: one node stuck
    // thousands of blocks (hours) behind, one live. A median of two is
    // the stuck one, which used to push the live one out as the outlier.
    private val clock = java.time.Instant.parse("2026-09-28T00:00:00Z").toEpochMilli()
    private val liveTime = "2026-09-27T23:59:50Z"
    private val stuckTime = "2026-09-27T17:37:37Z"

    @Test
    fun `a stuck provider sits out the round and doesn't vouch for the live answer`() = runBlocking {
        // The stuck node would read the same record at its old block —
        // but so it would if the live provider were serving a record
        // rolled back to before the stall. It can't attest to the chain
        // since, so the live answer is one provider's word.
        val http = rpc(
            record("web:content_url" to "https://hicetnunc.example/"),
            headLevels = mapOf(one to 15_130_198, two to 15_133_180),
            headTimes = mapOf(one to stuckTime, two to liveTime),
        )
        val resolver = TezosDomainsResolver(listOf(one, two), http) { clock }
        val a = answer(resolver.resolveOutcome("hicetnunc.tez"))
        assertEquals("https://hicetnunc.example/", a.leg.uri)
        assertFalse(a.verified)
        assertEquals(1, a.agreed)
        assertEquals(2, a.asked)
        assertEquals(TezosDomainsResolver.UNVERIFIED_TTL_MS, resolver.cacheDuration(a))
        // Asked for its head only: no anchor, no record.
        assertFalse(http.calls.any { it.startsWith(one) && (it.contains("/hash") || it.contains("/context/")) })
    }

    @Test
    fun `with the device clock slow, a stuck provider is still told from two live ones`() = runBlocking {
        // 12 h slow: octez stuck 11 h and the live heads all look
        // "current" by the device clock. Heads from the future say the
        // clock is off, and two of them outvote the stuck one.
        val slow = clock - 12 * 3_600_000L
        val http = rpc(
            record("web:content_url" to "ipfs://bafybeigdyrzt"),
            headLevels = mapOf(one to 15_128_200, two to 15_133_180, three to 15_133_180),
            headTimes = mapOf(one to "2026-09-27T13:00:00Z", two to liveTime, three to liveTime),
        )
        val a = answer(TezosDomainsResolver(threeEndpoints, http) { slow }.resolveOutcome("slow.tez"))
        assertEquals("ipfs://bafybeigdyrzt", a.leg.uri)
        assertTrue(a.verified)
        assertEquals(2, a.agreed)
        assertEquals(3, a.asked)
        assertFalse(http.calls.any { it.startsWith(one) && it.contains("/hash") })
    }

    @Test
    fun `one against one, a head from the future sets nothing aside`() = runBlocking {
        // A slow clock with one stuck provider looks exactly like a
        // correct clock with a live provider and one lying about its
        // head's timestamp. Neither side is picked.
        val slow = clock - 12 * 3_600_000L
        val http = rpc(
            record("web:content_url" to "ipfs://bafybeigdyrzt"),
            headLevels = mapOf(one to 15_128_200, two to 15_133_180),
            headTimes = mapOf(one to "2026-09-27T13:00:00Z", two to liveTime),
            down = setOf(three),
        )
        val outcome = TezosDomainsResolver(threeEndpoints, http) { slow }.resolveOutcome("slow.tez")
        assertTrue("$outcome", outcome is Outcome.Conflict)
    }

    @Test
    fun `a lone provider claiming a head from the future can't push a live one aside`() = runBlocking {
        val http = rpc(
            null,
            headLevels = mapOf(one to 15_133_180, two to 15_133_180),
            headTimes = mapOf(one to liveTime, two to "2026-09-28T06:00:00Z"),
            recordsByEndpoint = mapOf(
                one to record("web:content_url" to "ipfs://bafyreal"),
                two to record("web:content_url" to "ipfs://bafyEVIL"),
            ),
            down = setOf(three),
        )
        val outcome = TezosDomainsResolver(threeEndpoints, http) { clock }.resolveOutcome("future.tez")
        assertTrue("$outcome", outcome is Outcome.Conflict)
    }

    @Test
    fun `a provider claiming a head from the future can't push two live ones aside`() = runBlocking {
        val http = rpc(
            null,
            headLevels = mapOf(one to 15_133_180, two to 15_133_180, three to 15_200_000),
            headTimes = mapOf(one to liveTime, two to liveTime, three to "2026-09-28T12:00:00Z"),
            recordsByEndpoint = mapOf(
                one to record("web:content_url" to "ipfs://bafyreal"),
                two to record("web:content_url" to "ipfs://bafyreal"),
                three to record("web:content_url" to "ipfs://bafyEVIL"),
            ),
        )
        val a = answer(TezosDomainsResolver(threeEndpoints, http) { clock }.resolveOutcome("future.tez"))
        assertEquals("ipfs://bafyreal", a.leg.uri)
        assertTrue(a.verified)
        assertEquals(2, a.agreed)
    }

    @Test
    fun `a stuck provider with an older record leaves the live answer unverified`() = runBlocking {
        val http = rpc(
            null,
            headLevels = mapOf(one to 15_130_198, two to 15_133_180),
            headTimes = mapOf(one to stuckTime, two to liveTime),
            recordsByEndpoint = mapOf(
                one to record("web:content_url" to "ipfs://bafyold"),
                two to record("web:content_url" to "ipfs://bafynew"),
            ),
        )
        val resolver = TezosDomainsResolver(listOf(one, two), http) { clock }
        val a = answer(resolver.resolveOutcome("updated.tez"))
        assertEquals("ipfs://bafynew", a.leg.uri)
        assertFalse(a.verified)
        assertEquals(1, a.agreed)
        assertEquals(TezosDomainsResolver.UNVERIFIED_TTL_MS, resolver.cacheDuration(a))
        assertFalse((resolver.resolve("updated.tez") as EnsResult.Ok).verified)
    }

    @Test
    fun `a live provider can't be made to look stuck by one reporting a higher head`() = runBlocking {
        // The honest provider's head is recent; the other claims a far
        // higher one. Neither is stuck, so there is still no majority.
        val http = rpc(
            record("web:content_url" to "ipfs://bafybeigdyrzt"),
            headLevels = mapOf(one to 15_133_180, two to 15_200_000),
            headTimes = mapOf(one to liveTime, two to liveTime),
        )
        val outcome = TezosDomainsResolver(listOf(one, two), http) { clock }.resolveOutcome("liar.tez")
        assertTrue("$outcome", outcome is Outcome.Conflict)
        assertEquals("Tezos RPC providers disagree about the chain head", (outcome as Outcome.Conflict).reason)
    }

    @Test
    fun `if every head looks stuck, the device clock is off and heads are judged by each other`() = runBlocking {
        val http = rpc(
            record("web:content_url" to "ipfs://bafybeigdyrzt"),
            headTimes = mapOf(one to stuckTime, two to stuckTime),
        )
        val a = answer(TezosDomainsResolver(listOf(one, two), http) { clock }.resolveOutcome("clock.tez"))
        assertTrue(a.verified)
        assertEquals(2, a.agreed)
    }

    @Test
    fun `with the device clock fast, a stuck provider is still told from a live one`() = runBlocking {
        // 20 min fast: even the live head looks 20 min old. Measured
        // against the newest head, only the stuck one is stale — it must
        // not become the median and push the live provider out.
        val fast = clock + 20 * 60_000
        val http = rpc(
            record("web:content_url" to "ipfs://bafybeigdyrzt"),
            headLevels = mapOf(one to 15_130_198, two to 15_133_180),
            headTimes = mapOf(one to stuckTime, two to liveTime),
        )
        val a = answer(TezosDomainsResolver(listOf(one, two), http) { fast }.resolveOutcome("skew.tez"))
        assertEquals("ipfs://bafybeigdyrzt", a.leg.uri)
        // Resolved from the live provider alone: the stuck one sits out.
        assertFalse(a.verified)
        assertEquals(1, a.agreed)
        assertFalse(http.calls.any { it.startsWith(one) && it.contains("/hash") })
    }

    @Test
    fun `when every reachable provider is stuck, the less stuck one isn't verified by the more stuck one`() = runBlocking {
        // Clock correct; one stuck 12 h, two stuck 6 h, three down. Two is
        // only less stuck — not live — so one agreeing at an older block
        // must not turn two's hours-old answer into a verified one.
        val http = rpc(
            record("web:content_url" to "ipfs://bafybeigdyrzt"),
            headLevels = mapOf(one to 15_126_718, two to 15_130_198),
            headTimes = mapOf(one to "2026-09-27T12:00:00Z", two to stuckTime),
            down = setOf(three),
        )
        val resolver = TezosDomainsResolver(threeEndpoints, http) { clock }
        val a = answer(resolver.resolveOutcome("allstuck.tez"))
        assertEquals("ipfs://bafybeigdyrzt", a.leg.uri)
        assertFalse(a.verified)
        assertEquals(1, a.agreed)
        assertEquals(TezosDomainsResolver.UNVERIFIED_TTL_MS, resolver.cacheDuration(a))
    }

    @Test
    fun `refuses to resolve when providers return different anchor block hashes`() = runBlocking {
        val http = rpc(
            record("web:content_url" to "ipfs://bafybeigdyrzt/site"),
            anchorHashes = mapOf(two to "BForgedChain"),
        )
        val outcome = TezosDomainsResolver(listOf(one, two), http).resolveOutcome("forked.tez")
        assertTrue(outcome is Outcome.Conflict)
        assertEquals("Tezos RPC providers returned conflicting anchor blocks", (outcome as Outcome.Conflict).reason)
    }

    @Test
    fun `a lone reachable provider resolves, unverified`() = runBlocking {
        val http = rpc(record("web:content_url" to "ipfs://bafybeigdyrzt/site"), down = setOf(two, three))
        val a = answer(TezosDomainsResolver(threeEndpoints, http).resolveOutcome("lonely.tez"))
        assertFalse(a.verified)
        assertEquals(1, a.agreed)
    }

    @Test
    fun `every provider down is a retryable error`() = runBlocking {
        val http = rpc(null, down = threeEndpoints.toSet())
        val result = TezosDomainsResolver(threeEndpoints, http).resolve("offline.tez") as EnsResult.Error
        assertEquals("PROVIDER_ERROR", result.reason)
        assertTrue(result.retryable)
    }

    @Test
    fun `coalesces concurrent resolutions of the same name into one quorum round`() = runBlocking {
        val http = rpc(record("web:content_url" to "ipfs://bafybeigdyrzt/site"))
        val resolver = TezosDomainsResolver(threeEndpoints, http)
        val results = (1..4).map { async { resolver.resolve("shared.tez") } }.awaitAll()
        assertEquals(1, results.toSet().size)
        assertEquals(3, http.calls.count { it.endsWith("/chains/main/chain_id") })
    }

    @Test
    fun `reuses discovered registry big maps across names`() = runBlocking {
        val http = rpc(record("web:content_url" to "ipfs://bafybeigdyrzt/site"))
        val resolver = TezosDomainsResolver(threeEndpoints, http)
        fun scriptCalls() = http.calls.count { it.contains("/script/normalized") }
        resolver.resolve("first.tez")
        assertEquals(6, scriptCalls()) // proxy + registry script per provider
        resolver.resolve("second.tez")
        assertEquals(6, scriptCalls())
    }

    @Test
    fun `short-caches unverified results so trust can recover`() = runBlocking {
        var clock = 1_000_000L
        val http = rpc(record("web:content_url" to "ipfs://bafybeigdyrzt/site"), down = setOf(two, three))
        val resolver = TezosDomainsResolver(threeEndpoints, http) { clock }
        assertFalse(answer(resolver.resolveOutcome("solo.tez")).verified)
        val rounds = { http.calls.count { it.endsWith("/chains/main/chain_id") } }
        val afterFirst = rounds()
        // Within the short TTL the answer is served from cache…
        assertFalse(answer(resolver.resolveOutcome("solo.tez")).verified)
        assertEquals(afterFirst, rounds())
        // …but not beyond it: the next lookup re-runs the quorum.
        clock += TezosDomainsResolver.UNVERIFIED_TTL_MS + 1_000
        resolver.resolveOutcome("solo.tez")
        assertEquals(afterFirst + 3, rounds())
    }

    @Test
    fun `verified answers cache for the record TTL, capped, and never past expiry`() {
        val now = 10_000_000L
        val resolver = TezosDomainsResolver(threeEndpoints, rpc(null)) { now }
        fun ok(ttl: String?, expiry: String? = null) = Outcome.Answer(
            Leg(Leg.Type.OK, protocol = "ipfs", uri = "ipfs://x", ttl = ttl, expiry = expiry),
            verified = true, agreed = 3, asked = 3,
        )
        assertEquals(120_000L, resolver.cacheDuration(ok("120")))
        assertEquals(TezosDomainsResolver.DEFAULT_TTL_MS, resolver.cacheDuration(ok(null)))
        assertEquals(TezosDomainsResolver.MAX_TTL_MS, resolver.cacheDuration(ok("999999")))
        val soon = java.time.Instant.ofEpochMilli(now + 5_000).toString()
        assertEquals(5_000L, resolver.cacheDuration(ok("120", expiry = soon)))
        assertEquals(
            TezosDomainsResolver.NEGATIVE_TTL_MS,
            resolver.cacheDuration(Outcome.Answer(Leg(Leg.Type.NOT_FOUND), true, 3, 3)),
        )
    }

    @Test
    fun `EnsResolver hands tez names to the Tezos resolver`() = runBlocking {
        val tezHttp = rpc(record("web:content_url" to "ipfs://bafybeigdyrzt/"))
        val ethCalls = mutableListOf<String>()
        val eth = object : EnsHttp {
            override fun request(
                method: String, url: String, headers: Map<String, String>, body: String?,
                timeoutMs: Int, maxBytes: Long, followRedirects: Boolean,
            ): EnsHttp.Reply {
                ethCalls += url
                throw java.io.IOException("no Ethereum RPC for .tez")
            }
        }
        val resolver = EnsResolver(listOf("https://eth.test"), eth, TezosDomainsResolver(threeEndpoints, tezHttp))
        assertEquals(
            EnsResult.Ok("alice.tez", "ipfs", "ipfs://bafybeigdyrzt", "bafybeigdyrzt"),
            resolver.resolveContenthash("Alice.tez"),
        )
        assertTrue(ethCalls.isEmpty())
    }
}
