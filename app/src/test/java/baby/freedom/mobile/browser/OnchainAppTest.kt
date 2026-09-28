package baby.freedom.mobile.browser

import baby.freedom.mobile.chains.BuiltInChains
import baby.freedom.mobile.chains.Chain
import baby.freedom.mobile.chains.rpc.ChainDataResult
import baby.freedom.mobile.chains.rpc.ChainRpcException
import baby.freedom.mobile.chains.rpc.ChainSource
import baby.freedom.mobile.chains.rpc.ChainTrust
import baby.freedom.mobile.chains.rpc.RoutingContext
import baby.freedom.mobile.ens.toHex
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** ERC-8244 onchain apps (#123): URLs, `html()` decoding, the loader and the gate. */
class OnchainAppTest {
    private val zswap = "0x00000095643CFfA7D9fae407a84dfCB6406456c6"
    private val zswapLower = zswap.lowercase()

    // ---- URLs ----

    @Test
    fun `friendly form defaults to mainnet and keeps the tail`() {
        val (app, tail) = OnchainAppRef.parse("web3://$zswap")!!
        assertEquals(OnchainAppRef(zswapLower, 1), app)
        assertEquals("", tail)
        assertEquals("", OnchainAppRef.parse("web3://$zswap/")!!.second)
        assertEquals("/swap?x=1#top", OnchainAppRef.parse("web3://$zswap/swap?x=1#top")!!.second)
        assertEquals("?x=1", OnchainAppRef.parse("WEB3://$zswapLower?x=1")!!.second)
        assertEquals(8453L, OnchainAppRef.parse("web3://$zswap:8453/")!!.first.chainId)
    }

    @Test
    fun `desktop's canonical form parses too, and is what links use`() {
        val app = OnchainAppRef(zswapLower, 424242)
        assertEquals("web3://$zswapLower.eip155-424242/a?b", app.linkUrl("/a?b"))
        assertEquals(app to "/a?b", OnchainAppRef.parse(app.linkUrl("/a?b")))
        assertEquals(OnchainAppRef(zswapLower, 1) to "", OnchainAppRef.parse("web3://$zswap.EIP155-1"))
        assertNull(OnchainAppRef.parse("web3://$zswapLower.eip155-/"))
        assertNull(OnchainAppRef.parse("web3://$zswapLower.eip155-1:5/"))
    }

    @Test
    fun `names, bad addresses and out-of-range chains don't parse`() {
        assertNull(OnchainAppRef.parse("web3://vitalik.eth/"))
        assertNull(OnchainAppRef.parse("web3://0x1234/"))
        assertNull(OnchainAppRef.parse("web3://${zswap}00/"))
        assertNull(OnchainAppRef.parse("web3://$zswap:0/"))
        assertNull(OnchainAppRef.parse("web3://$zswap:9007199254740992/"))
        assertNull(OnchainAppRef.parse("web3://$zswap:/"))
        assertNull(OnchainAppRef.parse("https://$zswap/"))
        assertTrue(OnchainAppRef.isWeb3Scheme(" Web3://vitalik.eth"))
        assertFalse(OnchainAppRef.isWeb3Scheme("web3.example"))
    }

    @Test
    fun `display form is EIP-55 and drops the default chain`() {
        val app = OnchainAppRef(zswapLower, 1)
        assertEquals(zswap, app.checksumAddress)
        assertEquals("web3://$zswap/", app.displayUrl())
        assertEquals("web3://$zswap:100/a?b", OnchainAppRef(zswapLower, 100).displayUrl("/a?b"))
        // EIP-55's own test vector.
        assertEquals(
            "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
            OnchainAppRef.checksum("0x5aaeb6053f3e94c9b9a09f33669435e7ef1beaed"),
        )
    }

    @Test
    fun `every contract and chain pair gets its own origin, and it maps back`() {
        val mainnet = OnchainAppRef(zswapLower, 1)
        val gnosis = OnchainAppRef(zswapLower, 100)
        assertEquals("https://$zswapLower-1.web3.freedom.baby", mainnet.origin)
        assertTrue(mainnet.origin != gnosis.origin)
        val biggest = OnchainAppRef(zswapLower, Chain.MAX_ID)
        assertTrue(biggest.host.substringBefore('.').length <= 63)

        val virtual = OnchainAppRef.toVirtualUrl("web3://$zswap:100/swap?x=1#top")!!
        assertEquals("https://$zswapLower-100.web3.freedom.baby/swap?x=1#top", virtual)
        assertEquals("web3://$zswap:100/swap?x=1#top", OnchainAppRef.displayUrlFor(virtual))
        assertEquals("web3://$zswap/", OnchainAppRef.displayUrlFor("https://$zswapLower-1.web3.freedom.baby"))
        assertEquals("web3://$zswap/", Gateways.toDisplay("https://$zswapLower-1.web3.freedom.baby/"))
        assertEquals(mainnet.virtualUrl(), Gateways.toLoadable("web3://$zswap"))
    }

    @Test
    fun `only exact chain-scoped hosts are ours`() {
        assertNull(OnchainAppRef.parseVirtual("https://$zswapLower-1.web3.freedom.baby.evil.example/"))
        assertNull(OnchainAppRef.parseVirtual("https://x.$zswapLower-1.web3.freedom.baby/"))
        assertNull(OnchainAppRef.parseVirtual("https://$zswapLower-0.web3.freedom.baby/"))
        assertNull(OnchainAppRef.parseVirtual("https://$zswapLower.web3.freedom.baby/"))
        assertNull(OnchainAppRef.parseVirtual("http://$zswapLower-1.web3.freedom.baby/"))
        assertNull(OnchainAppRef.parseVirtual("https://$zswapLower-1.web3.freedom.baby:8443/"))
        assertTrue(OnchainAppRef.isVirtualUrl("https://${zswap}-1.WEB3.freedom.baby/"))
    }

    @Test
    fun `userinfo doesn't hide an app's own origin`() {
        val (app, tail) = OnchainAppRef.parseVirtual("https://a:b@$zswapLower-1.web3.freedom.baby/x?y")!!
        assertEquals(OnchainAppRef(zswapLower, 1), app)
        assertEquals("/x?y", tail)
        assertNull(OnchainAppRef.parseVirtual("https://$zswapLower-1.web3.freedom.baby@evil.example/"))
    }

    @Test
    fun `everything under the suffix is the interceptor's, apps or not`() {
        for (url in listOf(
            "https://$zswapLower-1.web3.freedom.baby/",
            "https://a@$zswapLower-1.web3.freedom.baby/",
            "https://$zswapLower-1.web3.freedom.baby:8443/",
            "https://$zswapLower-1.web3.freedom.baby./",
            "http://$zswapLower-1.web3.freedom.baby/",
            "https://x.$zswapLower-1.web3.freedom.baby/",
            "https://other.web3.freedom.baby/",
            "https://WEB3.freedom.baby",
            "https://u:p@web3.freedom.baby.:443?q",
        )) assertTrue(url, OnchainAppRef.isUnderSuffix(url))
        for (url in listOf(
            "https://$zswapLower-1.web3.freedom.baby.evil.example/",
            "https://evilweb3.freedom.baby/",
            "https://freedom.baby/",
            "https://web3.freedom.baby@evil.example/",
            "https://evil.example/?https://web3.freedom.baby/",
            "ftp://web3.freedom.baby/",
        )) assertFalse(url, OnchainAppRef.isUnderSuffix(url))
    }

    private fun request(url: String, mainFrame: Boolean = true) = object : android.webkit.WebResourceRequest {
        override fun getUrl(): android.net.Uri? = null
        override fun isForMainFrame() = mainFrame
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = "GET"
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }

    @Test
    fun `interceptor never lets a name under the suffix reach DNS`() {
        for (url in listOf(
            "https://$zswapLower-1.web3.freedom.baby:8443/",
            "https://$zswapLower-1.web3.freedom.baby./",
            "https://other.web3.freedom.baby/",
            "https://web3.freedom.baby/",
        )) {
            assertNotNull(url, interceptOnchainAppRequest(request(url), url, null))
            assertNotNull(url, interceptOnchainAppRequest(request(url, mainFrame = false), url, null))
        }
        assertNull(interceptOnchainAppRequest(request("https://example.com/"), "https://example.com/", null))
    }

    @Test
    fun `the cookie sweep covers an app's own host`() {
        assertEquals(
            "$zswapLower-424242.web3.freedom.baby",
            CookieHygiene.hostToSweep("https://a@$zswapLower-424242.web3.freedom.baby/x"),
        )
        assertTrue(CookieHygiene.coversNavigation("https://$zswapLower-1.web3.freedom.baby/"))
        assertFalse(CookieHygiene.coversNavigation("https://example.com/"))
        assertFalse(CookieHygiene.coversNavigation(null))
    }

    @Test
    fun `every cookie expiry is Secure, host and domain scoped, plain and partitioned`() {
        // R6-F1: without Secure, Chromium drops the expiry of a __Secure-/__Host- cookie.
        assertEquals(
            listOf(
                "Path=/a; Max-Age=0; Secure",
                "Path=/a; Max-Age=0; Secure; Partitioned",
                "Domain=.freedom.baby; Path=/a; Max-Age=0; Secure",
                "Domain=.freedom.baby; Path=/a; Max-Age=0; Secure; Partitioned",
            ),
            CookieHygiene.expiryAttributes("/a", ".freedom.baby", hostScoped = true),
        )
        // The base domain's own host-only cookies are the real site's.
        assertEquals(
            listOf(
                "Domain=.freedom.baby; Path=/; Max-Age=0; Secure",
                "Domain=.freedom.baby; Path=/; Max-Age=0; Secure; Partitioned",
            ),
            CookieHygiene.expiryAttributes("/", ".freedom.baby", hostScoped = false),
        )
    }

    @Test
    fun `the cookie sweep reads at the document's own path and expires every path that reaches it`() {
        assertEquals("/", CookieHygiene.pathOf("https://$zswapLower-1.web3.freedom.baby"))
        assertEquals("/", CookieHygiene.pathOf("https://$zswapLower-1.web3.freedom.baby/?q=/a#/b"))
        assertEquals("/swap", CookieHygiene.pathOf("https://a@$zswapLower-1.web3.freedom.baby/swap?x=1#y"))
        assertEquals(listOf("/"), CookieHygiene.cookiePathsMatching("/"))
        assertEquals(listOf("/", "/swap"), CookieHygiene.cookiePathsMatching("/swap"))
        assertEquals(
            listOf("/", "/swap", "/swap/", "/swap/x"),
            CookieHygiene.cookiePathsMatching("/swap/x"),
        )
        assertEquals(listOf("/", "/swap", "/swap/"), CookieHygiene.cookiePathsMatching("/swap/"))
    }

    @Test
    fun `cookie path candidates stay strictly increasing and linear in a deep path`() {
        assertEquals(listOf("/", "//"), CookieHygiene.cookiePathsMatching("//"))
        // R4-F1: a pushState'd '/a' x 4000 path gives 8000 candidates as
        // plain end offsets — the sweep bisects them rather than
        // expiring every name at each one.
        val deep = "/a".repeat(4000)
        val ends = CookieHygiene.cookiePathEnds(deep)
        assertEquals(8000, ends.size)
        assertTrue((1 until ends.size).all { ends[it] > ends[it - 1] })
        assertEquals(deep.length, ends.last())
    }

    @Test
    fun `the coalesced sweep queue keeps only the newest URLs`() {
        // R5-F2: a pushState loop during a running sweep can't grow the
        // waiting set without bound.
        val set = LinkedHashSet<String>()
        CookieHygiene.addPending(set, (0 until 10_000).map { "https://a.bzz.freedom.baby/p$it" })
        assertEquals(CookieHygiene.MAX_PENDING, set.size)
        assertEquals("https://a.bzz.freedom.baby/p9999", set.last())
        // Re-asking for an old URL moves it to the newest end, so it
        // survives the next overflow.
        val old = set.first()
        CookieHygiene.addPending(set, listOf(old, "https://b.bzz.freedom.baby/"))
        assertEquals(CookieHygiene.MAX_PENDING, set.size)
        assertEquals(listOf(old, "https://b.bzz.freedom.baby/"), set.toList().takeLast(2))
    }

    @Test
    fun `an overflowing sweep keeps the newest host passes`() {
        // R1-F1: past MAX_PATHS distinct (host, path) pairs, the pairs
        // dropped are the oldest ones, never the page just navigated to.
        val urls = (0 until 200).map { "https://a.bzz.freedom.baby/p$it" } +
            "https://b.bzz.freedom.baby/now"
        val covered = CookieHygiene.coveredPairs(urls)
        assertEquals(CookieHygiene.MAX_PATHS, covered.size)
        assertEquals("b.bzz.freedom.baby" to "/now", covered.first())
        assertTrue(("a.bzz.freedom.baby" to "/p199") in covered)
        assertFalse(("a.bzz.freedom.baby" to "/p0") in covered)
        // A re-visited old URL counts as newest, not as its first slot.
        val again = CookieHygiene.coveredPairs(urls + urls.first())
        assertEquals("a.bzz.freedom.baby" to "/p0", again.first())
        assertEquals(CookieHygiene.MAX_PATHS, again.size)
        // The suffix passes always read at `/` and keep the newest paths.
        val paths = CookieHygiene.sweepPaths(covered)
        assertEquals(CookieHygiene.MAX_PATHS, paths.size)
        assertEquals(listOf("/", "/now"), paths.take(2))
        // Non-virtual URLs never take a slot.
        assertEquals(
            listOf("b.bzz.freedom.baby" to "/"),
            CookieHygiene.coveredPairs(listOf("https://b.bzz.freedom.baby/", "https://example.com/x")),
        )
    }

    @Test
    fun `private approvals end with the private session, normal ones don't`() {
        val d = doc(ChainTrust.Level.UNVERIFIED, "private-session")
        OnchainApps.approvalsFor(private = true).add(d)
        OnchainApps.approvalsFor(private = false).add(d)
        OnchainApps.onPrivateSessionEnded()
        assertFalse(OnchainApps.approvalsFor(private = true).contains(d))
        assertTrue(
            decideOnchainDocument(OnchainLoad.Loaded(d), OnchainApps.approvalsFor(private = true), null)
                is OnchainDecision.Refuse,
        )
        assertTrue(OnchainApps.approvalsFor(private = false).contains(d))
    }

    @Test
    fun `permission key is lowercase, chain only when not mainnet`() {
        assertEquals("web3://$zswapLower", OnchainAppRef(zswapLower, 1).permissionKey)
        assertEquals("web3://$zswapLower:10", OnchainAppRef(zswapLower, 10).permissionKey)
    }

    // ---- html() decoding ----

    private fun abiString(s: String): String {
        val bytes = s.toByteArray(Charsets.UTF_8)
        val padded = bytes.copyOf((bytes.size + 31) / 32 * 32)
        return "0x" + "%064x".format(32) + "%064x".format(bytes.size) + padded.toHex()
    }

    @Test
    fun `decodes an ABI string, UTF-8 included`() {
        val html = "<!doctype html><p>héllo ⛓</p>"
        val decoded = OnchainAppLoader.decodeHtml(abiString(html))
        assertEquals(html, (decoded as OnchainAppLoader.Decoded.Html).html)
        assertEquals("", (OnchainAppLoader.decodeHtml(abiString("")) as OnchainAppLoader.Decoded.Html).html)
    }

    @Test
    fun `malformed answers are not apps`() {
        for (bad in listOf(null, "0x", "0x1234", "nothex", "0x" + "zz".repeat(64), abiString("x").dropLast(1))) {
            assertSame(bad.toString(), OnchainAppLoader.Decoded.Malformed, OnchainAppLoader.decodeHtml(bad))
        }
        // Length word pointing past the data.
        val lying = "0x" + "%064x".format(32) + "%064x".format(1000) + "00".repeat(32)
        assertSame(OnchainAppLoader.Decoded.Malformed, OnchainAppLoader.decodeHtml(lying))
        // Invalid UTF-8.
        val invalid = "0x" + "%064x".format(32) + "%064x".format(1) + "ff" + "00".repeat(31)
        assertSame(OnchainAppLoader.Decoded.Malformed, OnchainAppLoader.decodeHtml(invalid))
    }

    @Test
    fun `oversized documents are refused before they are decoded`() {
        val claimsHuge = "0x" + "%064x".format(32) + "%064x".format(OnchainAppLoader.MAX_HTML_BYTES + 1) + "00".repeat(32)
        assertSame(OnchainAppLoader.Decoded.TooLarge, OnchainAppLoader.decodeHtml(claimsHuge))
        val tooLong = "0x" + "00".repeat(OnchainAppLoader.MAX_HTML_BYTES + 96)
        assertSame(OnchainAppLoader.Decoded.TooLarge, OnchainAppLoader.decodeHtml(tooLong))
    }

    // ---- loader ----

    private fun trust(
        level: ChainTrust.Level,
        agreed: List<String> = listOf("rpc.example"),
        dissented: List<String> = emptyList(),
    ) = ChainTrust(level, if (level == ChainTrust.Level.VERIFIED) ChainSource.QUORUM else ChainSource.DIRECT,
        agreed, dissented, agreed + dissented, 1, 1, null)

    private fun loader(
        answer: suspend (RoutingContext, JSONArray) -> ChainDataResult,
    ) = OnchainAppLoader(
        request = { chainId, method, params, ctx ->
            assertEquals("eth_call", method)
            assertEquals(1L, chainId)
            answer(ctx, params)
        },
        chains = { BuiltInChains.ALL },
    )

    @Test
    fun `loader calls html() as the app's page and keeps who answered`() = runBlocking {
        var seen: Pair<RoutingContext, JSONArray>? = null
        val load = loader { ctx, params ->
            seen = ctx to params
            ChainDataResult(abiString("<p>hi</p>"), trust(ChainTrust.Level.VERIFIED))
        }.load(OnchainAppRef(zswapLower, 1))
        val doc = (load as OnchainLoad.Loaded).document
        assertEquals("<p>hi</p>", doc.html)
        assertEquals(OnchainAppLoader.htmlHash("<p>hi</p>"), doc.hash)
        assertEquals("Ethereum", doc.network)
        assertTrue(doc.trusted)
        assertEquals(RoutingContext.forPage("web3://$zswapLower"), seen!!.first)
        val call = seen!!.second.getJSONObject(0)
        assertEquals(zswapLower, call.getString("to"))
        assertEquals("0x33c34ac3", call.getString("data"))
        assertEquals("latest", seen!!.second.getString(1))
    }

    @Test
    fun `loader failures map to their error pages`() = runBlocking {
        val app = OnchainAppRef(zswapLower, 1)
        assertEquals(
            "web3_not_an_app",
            (loader { _, _ -> throw ChainRpcException.Rpc(3, "execution reverted", "0x") }.load(app) as OnchainLoad.Failed).code,
        )
        assertEquals(
            "web3_lookup_failed",
            (loader { _, _ -> throw ChainRpcException.AllSourcesFailed(listOf("direct: down"), null) }.load(app) as OnchainLoad.Failed).code,
        )
        assertEquals(
            "web3_lookup_failed",
            (loader { _, _ -> awaitCancellation() }.load(app, timeoutMs = 50) as OnchainLoad.Failed).code,
        )
        assertEquals(
            "web3_not_an_app",
            (loader { _, _ -> ChainDataResult("0x", trust(ChainTrust.Level.VERIFIED)) }.load(app) as OnchainLoad.Failed).code,
        )
        assertEquals(
            "web3_unknown_chain",
            (loader { _, _ -> error("not asked") }.load(OnchainAppRef(zswapLower, 424242)) as OnchainLoad.Failed).code,
        )
    }

    // ---- the gate ----

    private fun doc(level: ChainTrust.Level, html: String = "<p>app</p>", dissented: List<String> = emptyList()) =
        OnchainDocument(OnchainAppRef(zswapLower, 1), html, OnchainAppLoader.htmlHash(html),
            trust(level, dissented = dissented), "Ethereum")

    @Test
    fun `trust levels`() {
        assertTrue(doc(ChainTrust.Level.VERIFIED).trusted)
        assertTrue(doc(ChainTrust.Level.USER_CONFIGURED).trusted)
        assertFalse(doc(ChainTrust.Level.UNVERIFIED).trusted)
        // Dissent that no verified tier settled blocks outright.
        assertTrue(doc(ChainTrust.Level.UNVERIFIED, dissented = listOf("b.example")).conflict)
        assertTrue(doc(ChainTrust.Level.USER_CONFIGURED, dissented = listOf("b.example")).conflict)
        assertFalse(doc(ChainTrust.Level.USER_CONFIGURED, dissented = listOf("b.example")).trusted)
        assertFalse(doc(ChainTrust.Level.VERIFIED, dissented = listOf("b.example")).conflict)
    }

    @Test
    fun `interceptor serves trusted or already-approved code, refuses the rest`() {
        val approvals = OnchainApprovals()
        val unverified = doc(ChainTrust.Level.UNVERIFIED)
        assertEquals(
            "web3_unverified",
            (decideOnchainDocument(OnchainLoad.Loaded(unverified), approvals, null) as OnchainDecision.Refuse).code,
        )
        approvals.add(unverified)
        assertTrue(decideOnchainDocument(OnchainLoad.Loaded(unverified), approvals, null) is OnchainDecision.Serve)
        // Changed bytes ask again.
        val changed = doc(ChainTrust.Level.UNVERIFIED, html = "<p>app v2</p>")
        assertTrue(decideOnchainDocument(OnchainLoad.Loaded(changed), approvals, null) is OnchainDecision.Refuse)
        // A conflict is refused even for approved bytes.
        val conflict = doc(ChainTrust.Level.UNVERIFIED, dissented = listOf("b.example"))
        assertEquals(
            "web3_conflict",
            (decideOnchainDocument(OnchainLoad.Loaded(conflict), approvals, null) as OnchainDecision.Refuse).code,
        )
        assertTrue(decideOnchainDocument(OnchainLoad.Loaded(doc(ChainTrust.Level.VERIFIED, "x")), approvals, null)
            is OnchainDecision.Serve)
    }

    @Test
    fun `a failed read falls back on the tab's copy, an answer doesn't`() {
        val approvals = OnchainApprovals()
        val last = doc(ChainTrust.Level.VERIFIED)
        val failed = OnchainLoad.Failed("web3_lookup_failed", "down")
        assertSame(last, (decideOnchainDocument(failed, approvals, last) as OnchainDecision.Serve).document)
        assertTrue(decideOnchainDocument(failed, approvals, null) is OnchainDecision.Refuse)
        val reverts = OnchainLoad.Failed("web3_not_an_app", "reverted")
        assertTrue(decideOnchainDocument(reverts, approvals, last) is OnchainDecision.Refuse)
    }

    @Test
    fun `approvals are bounded`() {
        val approvals = OnchainApprovals(capacity = 2)
        val a = doc(ChainTrust.Level.UNVERIFIED, "a")
        val b = doc(ChainTrust.Level.UNVERIFIED, "b")
        val c = doc(ChainTrust.Level.UNVERIFIED, "c")
        approvals.add(a); approvals.add(b); approvals.add(c)
        assertFalse(approvals.contains(a))
        assertTrue(approvals.contains(b) && approvals.contains(c))
    }

    @Test
    fun `hand-off is taken once and goes stale`() {
        var now = 0L
        val tab = OnchainAppTab(private = false, clock = { now })
        val d = doc(ChainTrust.Level.VERIFIED)
        tab.handOff(d)
        assertSame(d, tab.takeHandoff(d.app))
        assertNull(tab.takeHandoff(d.app))
        assertSame(d, tab.lastFor(d.app))
        tab.handOff(d)
        now = OnchainAppTab.HANDOFF_TTL_MS + 1
        assertNull(tab.takeHandoff(d.app))
    }

    @Test
    fun `continue runs only the exact bytes the warning showed`() {
        val tab = OnchainAppTab(private = false)
        val d = doc(ChainTrust.Level.UNVERIFIED)
        tab.offer(d)
        assertNull(tab.takePending(d.app, OnchainAppLoader.htmlHash("other")))
        // Taken (and cleared) by the failed attempt too.
        assertNull(tab.takePending(d.app, d.hash))
        tab.offer(d)
        assertNull(tab.takePending(OnchainAppRef(zswapLower, 100), d.hash))
        tab.offer(d)
        assertSame(d, tab.takePending(d.app, d.hash.uppercase().replace("0X", "0x")))
    }

    @Test
    fun `a stale web3 warning's continue re-runs its navigation`() {
        val page = ErrorPage.url(
            errorCode = "web3_unverified",
            displayUrl = "web3://$zswap/",
            protocol = "web3",
            retryUrl = "web3://$zswap/",
            continueUrl = "freedom-ens-continue:stale",
        )
        assertEquals("web3://$zswap/", EnsGate.continueDestination("freedom-ens-continue:stale", null, page))
    }

    @Test
    fun `web3 links in the main frame go through the submit flow`() {
        assertTrue(submitDetourForNavigation("web3://$zswap/", isForMainFrame = true))
        assertFalse(submitDetourForNavigation("web3://$zswap/", isForMainFrame = false))
    }
}
