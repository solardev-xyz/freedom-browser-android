package baby.freedom.mobile.browser

import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.test.ext.junit.runners.AndroidJUnit4
import baby.freedom.mobile.ens.EnsResult
import baby.freedom.mobile.ens.EnsTrust
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The virtual-origin dapp compatibility contract, as executable
 * assertions. Each test method is cited by name from
 * `docs/dapp-compatibility.md` — a guarantee only belongs in that
 * document if a test here proves it, and vice versa.
 *
 * Hermetic: content is served by [FixtureGateway] on the gateway's own
 * loopback address, through the production [interceptVirtualRequest]
 * path. No p2p, no external network.
 */
@RunWith(AndroidJUnit4::class)
class VirtualOriginContractTest {

    private val gateway = FixtureGateway()
    private val harness = WebViewHarness()

    private val originA = VirtualOrigin.toVirtualUrl("bzz://${FixtureGateway.REF_A}")!!
    private val originB = VirtualOrigin.toVirtualUrl("bzz://${FixtureGateway.REF_B}")!!

    private val realEnsLookup = Gateways.ensLookup

    /**
     * What `testdapp.eth` resolves to right now — the fixture's stand-in
     * for the name's on-chain contenthash. `null` = no contenthash.
     */
    @Volatile
    private var testdappContent: String? = null

    /** RPC unreachable: every lookup fails (not "no contenthash"). */
    @Volatile
    private var rpcDown = false
    private val ensLookups = java.util.concurrent.atomic.AtomicInteger(0)

    @Before
    fun setUp() {
        gateway.start()
        harness.setUp()
        KnownEnsNames.clear()
        Gateways.resetEnsLookupState()
        Gateways.ensLookup = { name ->
            ensLookups.incrementAndGet()
            val ref = testdappContent
            if (rpcDown) {
                EnsResult.Error(name, "PROVIDER_ERROR", "RPC unreachable", retryable = true)
            } else if (name == "testdapp.eth" && ref != null) {
                EnsResult.Ok(name, "bzz", "bzz://$ref", ref, EnsTrust.ASSUMED)
            } else {
                EnsResult.NotFound(name, "NO_CONTENTHASH", EnsTrust.ASSUMED)
            }
        }
        clearWebStorage()
    }

    @After
    fun tearDown() {
        harness.tearDown()
        gateway.shutdown()
        KnownEnsNames.clear()
        Gateways.ensLookup = realEnsLookup
        Gateways.resetEnsLookupState()
    }

    private fun clearWebStorage() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            WebStorage.getInstance().deleteAllData()
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }
        // deleteAllData is asynchronous with no callback; give it a beat.
        Thread.sleep(300)
    }

    // ------------------------------------------------------------------
    // Storage isolation
    // ------------------------------------------------------------------

    @Test
    fun storageWrittenUnderRootAIsInvisibleUnderRootB() {
        harness.load(originA)
        harness.awaitJsTrue("window.results && window.results.loaded === true")
        harness.js("localStorage.setItem('secret', 'root-a-only')")
        assertEquals("\"root-a-only\"", harness.js("localStorage.getItem('secret')"))

        harness.load(originB)
        harness.awaitJsTrue("window.results && window.results.loaded === true")
        assertEquals("null", harness.js("localStorage.getItem('secret')"))
    }

    @Test
    fun ensSiteKeepsStorageAcrossAContenthashUpdate() {
        // The name resolves to root A…
        testdappContent = FixtureGateway.REF_A
        val ensUrl = VirtualOrigin.toVirtualUrl("ens://testdapp.eth")!!
        harness.load(ensUrl)
        harness.awaitJsTrue("window.results && window.results.loaded === true")
        assertEquals("\"VERSION_A\"", harness.js("document.getElementById('version').textContent"))
        harness.js("localStorage.setItem('kept', 'yes')")

        // …the site publishes an update (contenthash now points at B)…
        testdappContent = FixtureGateway.REF_B
        harness.load(ensUrl)
        harness.awaitJsTrue("window.results && window.results.loaded === true")

        // …new content, same name-derived origin, same storage.
        assertEquals("\"VERSION_B\"", harness.js("document.getElementById('version').textContent"))
        assertEquals("\"yes\"", harness.js("localStorage.getItem('kept')"))
    }

    // ------------------------------------------------------------------
    // Back / Forward re-check the name (#99)
    // ------------------------------------------------------------------

    @Test
    fun backAndForwardReResolveAnEnsNameInsteadOfRestoringTheFirstAnswer() {
        val ensUrl = VirtualOrigin.toVirtualUrl("ens://testdapp.eth")!!
        testdappContent = FixtureGateway.REF_A
        harness.load(ensUrl)
        harness.awaitJsTrue("window.results && window.results.loaded === true")
        assertEquals("\"VERSION_A\"", harness.js("document.getElementById('version').textContent"))

        // Leave the name, and while away it moves to B.
        harness.load(originB)
        harness.awaitJsTrue("window.results && window.results.loaded === true")
        testdappContent = FixtureGateway.REF_B
        val before = ensLookups.get()

        // Back to the name's history entry: the document is fetched
        // from the name's *current* answer, not the one recorded when
        // it was first visited.
        harness.goBack()
        assertEquals(ensUrl, harness.js("location.href").trim('"'))
        harness.awaitJsTrue("window.results && window.results.loaded === true")
        assertEquals("\"VERSION_B\"", harness.js("document.getElementById('version').textContent"))
        assertTrue("Back looked the name up", ensLookups.get() > before)

        // Forward away and Back again after it moves once more: same.
        harness.goForward()
        harness.awaitJsTrue("window.results && window.results.loaded === true")
        testdappContent = FixtureGateway.REF_A
        harness.goBack()
        harness.awaitJsTrue("window.results && window.results.loaded === true")
        assertEquals("\"VERSION_A\"", harness.js("document.getElementById('version').textContent"))
    }

    @Test
    fun forwardToAnEnsNameThatNoLongerResolvesIsRefused() {
        val ensUrl = VirtualOrigin.toVirtualUrl("ens://testdapp.eth")!!
        harness.load(originA)
        harness.awaitJsTrue("window.results && window.results.loaded === true")
        testdappContent = FixtureGateway.REF_B
        harness.load(ensUrl)
        harness.awaitJsTrue("window.results && window.results.loaded === true")
        harness.goBack()
        harness.awaitJsTrue("window.results && window.results.loaded === true")

        // The name loses its contenthash; Forward must not serve B from
        // the answer recorded on the first visit.
        testdappContent = null
        harness.goForward()
        assertEquals(404, harness.lastHttpError.get())
        assertEquals("ens_not_found", nameResolutionErrorIn(harness.lastHttpErrorHeaders.get()))
        val text = harness.js("document.body.innerText")
        assertFalse(text.contains("VERSION_B"))
        // The refusal is the error page itself, in the entry's place.
        assertTrue(text, text.contains("No content for this ENS name"))
    }

    @Test
    fun aRefusedBackKeepsTheForwardEntry() {
        val ensUrl = VirtualOrigin.toVirtualUrl("ens://testdapp.eth")!!
        testdappContent = FixtureGateway.REF_A
        harness.load(ensUrl)
        harness.awaitJsTrue("window.results && window.results.loaded === true")
        harness.load(originB)
        harness.awaitJsTrue("window.results && window.results.loaded === true")

        // The name loses its contenthash; Back is refused in place — the
        // production client leaves a refusal carrying the header alone
        // rather than loading ErrorPage on top (which would truncate the
        // forward entry the user just came from).
        testdappContent = null
        harness.goBack()
        assertEquals(404, harness.lastHttpError.get())
        assertEquals("ens_not_found", nameResolutionErrorIn(harness.lastHttpErrorHeaders.get()))
        assertEquals(ensUrl, harness.js("location.href").trim('"'))
        var canGoForward = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            canGoForward = harness.webView.canGoForward()
        }
        assertTrue("the page Back came from is still in history", canGoForward)
        harness.goForward()
        harness.awaitJsTrue("window.results && window.results.loaded === true")
        assertEquals("\"VERSION_B\"", harness.js("document.getElementById('version').textContent"))
    }

    @Test
    fun backWithTheRpcDownServesTheLastAnswer() {
        val ensUrl = VirtualOrigin.toVirtualUrl("ens://testdapp.eth")!!
        testdappContent = FixtureGateway.REF_A
        harness.load(ensUrl)
        harness.awaitJsTrue("window.results && window.results.loaded === true")
        harness.load(originB)
        harness.awaitJsTrue("window.results && window.results.loaded === true")

        // A failed lookup isn't an answer: Back serves what the name last
        // resolved to instead of refusing the page.
        rpcDown = true
        harness.goBack()
        assertEquals(0, harness.lastHttpError.get())
        harness.awaitJsTrue("window.results && window.results.loaded === true")
        assertEquals("\"VERSION_A\"", harness.js("document.getElementById('version').textContent"))
    }

    @Test
    fun anEnsIframeReChecksTheName() {
        val ensUrl = VirtualOrigin.toVirtualUrl("ens://testdapp.eth")!!
        testdappContent = FixtureGateway.REF_A
        harness.load(ensUrl)
        harness.awaitJsTrue("window.results && window.results.loaded === true")
        harness.load(originB)
        harness.awaitJsTrue("window.results && window.results.loaded === true")

        // The name moves; another page embeds it. The iframe's document
        // is a document too, and is served from the current answer —
        // not the session's first one. (Leaving the name's page dropped
        // this tab's pin for it.)
        testdappContent = FixtureGateway.REF_B
        KnownEnsNames.record("bzz://${FixtureGateway.REF_A}", "testdapp.eth", EnsTrust.ASSUMED)
        assertEquals(null, harness.ensPins.uriFor("testdapp.eth"))
        val before = ensLookups.get()
        harness.js(
            "(function(){var f=document.createElement('iframe');" +
                "f.onload=function(){window.__framed=true};" +
                "f.src='$ensUrl';document.body.appendChild(f)})()",
        )
        harness.awaitJsTrue("window.__framed === true")
        assertTrue("the iframe looked the name up", ensLookups.get() > before)
        assertEquals("bzz://${FixtureGateway.REF_B}", harness.ensPins.uriFor("testdapp.eth"))
    }

    @Test
    fun aSameNameIframeKeepsThePagesRoot() {
        val ensUrl = VirtualOrigin.toVirtualUrl("ens://testdapp.eth")!!
        testdappContent = FixtureGateway.REF_A
        harness.load(ensUrl)
        harness.awaitJsTrue("window.results && window.results.loaded === true")

        // The name moves while its page is on screen, and the page embeds
        // itself: the iframe is part of the page already served from A, so
        // it must not re-pin the name and move the page's later
        // subresources to B under A's HTML.
        testdappContent = FixtureGateway.REF_B
        val before = ensLookups.get()
        harness.js(
            "(function(){var f=document.createElement('iframe');" +
                "f.onload=function(){window.__framed=true};" +
                "f.src='$ensUrl';document.body.appendChild(f)})()",
        )
        harness.awaitJsTrue("window.__framed === true")
        assertEquals("no re-check for the page's own name", before, ensLookups.get())
        assertEquals("bzz://${FixtureGateway.REF_A}", harness.ensPins.uriFor("testdapp.eth"))
        assertEquals(
            "\"VERSION_A\"",
            harness.js(
                "document.querySelector('iframe').contentDocument" +
                    ".getElementById('version').textContent",
            ),
        )
    }

    @Test
    fun aNavigationThatNeverCommitsKeepsThePagesRoot() {
        val ensUrl = VirtualOrigin.toVirtualUrl("ens://testdapp.eth")!!
        testdappContent = FixtureGateway.REF_A
        harness.load(ensUrl)
        harness.awaitJsTrue("window.results && window.results.loaded === true")
        assertEquals("bzz://${FixtureGateway.REF_A}", harness.ensPins.uriFor("testdapp.eth"))

        // The name moves; the page follows a link to a download. With no
        // DownloadListener the navigation is dropped: A's document stays
        // on screen, so its subresources must keep coming from A even
        // though the link's own request was re-checked against B.
        testdappContent = FixtureGateway.REF_B
        val before = ensLookups.get()
        harness.js("location.href = 'file.bin'")
        val deadline = System.currentTimeMillis() + 10_000
        while (ensLookups.get() == before && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        assertTrue("the link's request was re-checked", ensLookups.get() > before)
        Thread.sleep(1_500)
        assertEquals("\"$ensUrl\"", harness.js("location.href"))
        assertEquals("\"VERSION_A\"", harness.js("document.getElementById('version').textContent"))
        assertEquals("bzz://${FixtureGateway.REF_A}", harness.ensPins.uriFor("testdapp.eth"))
        harness.js(
            "fetch('index.html').then(r => r.text())" +
                ".then(t => { window.__xhr = t.includes('VERSION_B') ? 'B' : 'A' })",
        )
        harness.awaitJsTrue("window.__xhr !== undefined")
        assertEquals("\"A\"", harness.js("window.__xhr"))

        // A navigation that does commit moves the page on.
        harness.load(ensUrl)
        harness.awaitJsTrue("window.results && window.results.loaded === true")
        assertEquals("\"VERSION_B\"", harness.js("document.getElementById('version').textContent"))
        assertEquals("bzz://${FixtureGateway.REF_B}", harness.ensPins.uriFor("testdapp.eth"))
    }

    @Test
    fun aNavigationStoppedDuringItsReCheckKeepsThePagesRoot() {
        val ensUrl = VirtualOrigin.toVirtualUrl("ens://testdapp.eth")!!
        testdappContent = FixtureGateway.REF_A
        harness.load(ensUrl)
        harness.awaitJsTrue("window.results && window.results.loaded === true")
        assertEquals("bzz://${FixtureGateway.REF_A}", harness.ensPins.uriFor("testdapp.eth"))

        // The name moves and the lookup is slow; the page navigates and
        // the user hits Stop while the re-check is still running. The
        // interceptor later hands WebView B's document, but the
        // navigation is gone: A stays on screen and keeps A's root.
        testdappContent = FixtureGateway.REF_B
        val real = Gateways.ensLookup
        val answered = java.util.concurrent.CountDownLatch(1)
        Gateways.ensLookup = { name ->
            Thread.sleep(2_000)
            real(name).also { answered.countDown() }
        }
        try {
            harness.js("location.href = 'index.html?next=1'")
            Thread.sleep(300)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                harness.webView.stopLoading()
            }
            assertTrue("the re-check ran", answered.await(10, java.util.concurrent.TimeUnit.SECONDS))
            Thread.sleep(1_000)
        } finally {
            Gateways.ensLookup = real
        }
        assertEquals("\"$ensUrl\"", harness.js("location.href"))
        assertEquals("\"VERSION_A\"", harness.js("document.getElementById('version').textContent"))
        assertEquals("bzz://${FixtureGateway.REF_A}", harness.ensPins.uriFor("testdapp.eth"))
        harness.js(
            "fetch('index.html').then(r => r.text())" +
                ".then(t => { window.__xhr = t.includes('VERSION_B') ? 'B' : 'A' })",
        )
        harness.awaitJsTrue("window.__xhr !== undefined")
        assertEquals("\"A\"", harness.js("window.__xhr"))

        // A navigation that does commit moves the page on.
        harness.load(ensUrl)
        harness.awaitJsTrue("window.results && window.results.loaded === true")
        assertEquals("\"VERSION_B\"", harness.js("document.getElementById('version').textContent"))
        assertEquals("bzz://${FixtureGateway.REF_B}", harness.ensPins.uriFor("testdapp.eth"))
    }

    @Test
    fun backWithTheRpcStalledServesTheLastAnswerWithinTheDeadline() {
        val ensUrl = VirtualOrigin.toVirtualUrl("ens://testdapp.eth")!!
        testdappContent = FixtureGateway.REF_A
        harness.load(ensUrl)
        harness.awaitJsTrue("window.results && window.results.loaded === true")
        harness.load(originB)
        harness.awaitJsTrue("window.results && window.results.loaded === true")

        // Every RPC black-holes. Back must not wait out the resolver's
        // minute of timeouts before serving the last answer.
        val release = java.util.concurrent.CountDownLatch(1)
        val real = Gateways.ensLookup
        Gateways.ensLookup = { name -> release.await(); real(name) }
        try {
            val t = System.currentTimeMillis()
            harness.goBack(timeoutSeconds = 20)
            harness.awaitJsTrue("window.results && window.results.loaded === true")
            val took = System.currentTimeMillis() - t
            assertTrue("Back took ${took}ms", took < Gateways.reverifyDeadlineMs + 5_000)
            assertEquals(0, harness.lastHttpError.get())
            assertEquals(
                "\"VERSION_A\"",
                harness.js("document.getElementById('version').textContent"),
            )
        } finally {
            release.countDown()
        }
    }

    // ------------------------------------------------------------------
    // Path resolution
    // ------------------------------------------------------------------

    @Test
    fun relativeAndAbsoluteRootSubresourcesResolveOnAVirtualOrigin() {
        harness.load(originA)
        harness.awaitJsTrue("window.results && window.results.loaded === true")
        // Relative <script> and <img>.
        assertEquals("true", harness.js("window.results.relJs === true"))
        assertEquals("true", harness.js("window.results.relImg === true"))
        // Absolute-root `/style.css` — the shape that used to need the
        // gateway-escape rewrite heuristics.
        assertEquals("true", harness.js("window.results.absCss === true"))
    }

    // ------------------------------------------------------------------
    // Fetch
    // ------------------------------------------------------------------

    @Test
    fun sameOriginFetchWorks() {
        harness.load(originA)
        harness.awaitJsTrue("window.results && window.results.loaded === true")
        harness.js(
            "fetch('/app.js').then(r => { window.results.fetchStatus = r.status; })",
        )
        harness.awaitJsTrue("window.results.fetchStatus === 200")
    }

    @Test
    fun crossRootFetchSucceedsUnderThePermissiveCorsPolicy() {
        harness.load(originA)
        harness.awaitJsTrue("window.results && window.results.loaded === true")
        harness.js(
            "fetch('$originB' + 'app.js').then(r => { " +
                "window.results.xStatus = r.status; })" +
                ".catch(e => { window.results.xStatus = 'blocked'; })",
        )
        harness.awaitJsTrue("window.results.xStatus === 200")
    }

    @Test
    fun aSubresourceBodyThatPausesFor15sIsNotAborted() {
        harness.load(originA)
        harness.awaitJsTrue("window.results && window.results.loaded === true")
        harness.js(
            "fetch('/slow-segment').then(r => r.arrayBuffer())" +
                ".then(b => { window.results.slowLen = b.byteLength; })" +
                ".catch(e => { window.results.slowLen = 'error: ' + e; })",
        )
        harness.awaitJsTrue("window.results.slowLen !== undefined", timeoutSeconds = 60)
        assertEquals("${FixtureGateway.SLOW_SEGMENT_BYTES}", harness.js("String(window.results.slowLen)").trim('"'))
        // Fetched once: not thrown away and fetched again after the pause.
        assertEquals(1, gateway.requests["/bzz/${FixtureGateway.REF_A}/slow-segment"]?.get())
    }

    @Test
    fun aSubresource404IsPassedThroughAtOnce() {
        harness.load(originA)
        harness.awaitJsTrue("window.results && window.results.loaded === true")
        harness.js(
            "window.results.t0 = Date.now();" +
                "fetch('/no-such-file').then(r => { window.results.missing = r.status; " +
                "window.results.missingMs = Date.now() - window.results.t0; })",
        )
        harness.awaitJsTrue("window.results.missing !== undefined")
        assertEquals("404", harness.js("String(window.results.missing)").trim('"'))
        val ms = harness.js("window.results.missingMs").toLong()
        assertTrue("took $ms ms", ms < 3_000)
        assertEquals(1, gateway.requests["/bzz/${FixtureGateway.REF_A}/no-such-file"]?.get())
    }

    // ------------------------------------------------------------------
    // Media
    // ------------------------------------------------------------------

    @Test
    fun rangeRequestsGetA206Slice() {
        harness.load(originA)
        harness.awaitJsTrue("window.results && window.results.loaded === true")
        harness.js(
            "fetch('/clip.wav', { headers: { 'Range': 'bytes=0-99' } })" +
                ".then(r => { window.results.rangeStatus = r.status; " +
                "window.results.contentRange = r.headers.get('Content-Range'); })",
        )
        harness.awaitJsTrue("window.results.rangeStatus === 206")
        assertTrue(
            harness.js("window.results.contentRange").contains("bytes 0-99/"),
        )
    }

    // ------------------------------------------------------------------
    // Scheme subresources
    // ------------------------------------------------------------------

    @Test
    fun bzzSchemeImgSubresourceLoads() {
        harness.load(originA)
        harness.awaitJsTrue("window.results && window.results.loaded === true")
        harness.js(
            "var i = document.createElement('img');" +
                "i.onload = () => { window.results.schemeImg = i.naturalWidth > 0; };" +
                "i.onerror = () => { window.results.schemeImg = 'error'; };" +
                "i.src = 'bzz://${FixtureGateway.REF_A}/sub/pixel.png';" +
                "document.body.appendChild(i);",
        )
        harness.awaitJsTrue("window.results.schemeImg === true")
    }

    // ------------------------------------------------------------------
    // Error flows
    // ------------------------------------------------------------------

    @Test
    fun nodeStoppedYieldsACleanSynthesized502() {
        gateway.shutdown()
        harness.load(originA, timeoutSeconds = 30)
        assertEquals(502, harness.lastHttpError.get())
        // Error-page derivation from the failed virtual URL.
        assertEquals("bzz://${FixtureGateway.REF_A}", retryUrlFor(originA.removeSuffix("/")))
    }

    @Test
    fun badHashYieldsContentNotFoundWithAWorkingRetryTarget() {
        val missing = VirtualOrigin.toVirtualUrl("bzz://${FixtureGateway.REF_MISSING}")!!
        // The interceptor retries transient 404s for ~17s before giving
        // up (cold-node semantics) — budget for it.
        harness.load(missing, timeoutSeconds = 90)
        assertEquals(404, harness.lastHttpError.get())
        assertEquals(
            "bzz://${FixtureGateway.REF_MISSING}",
            retryUrlFor(missing.removeSuffix("/")),
        )
    }

    // ------------------------------------------------------------------
    // Cookie hygiene
    // ------------------------------------------------------------------

    @Test
    fun tossedDomainCookieFromRootAIsNotVisibleUnderRootBAfterSweep() {
        harness.load(originA)
        harness.awaitJsTrue("window.results && window.results.loaded === true")
        harness.js(
            "document.cookie = 'tossed=evil; domain=.bzz.freedom.baby; path=/';",
        )
        CookieHygiene.sweepBlocking(originA)
        harness.load(originB)
        harness.awaitJsTrue("window.results && window.results.loaded === true")
        assertFalse(harness.js("document.cookie").contains("tossed"))
    }

    @Test
    fun tossedBaseDomainCookieIsSweptButTheRealSitesHostCookieStays() {
        val cm = CookieManager.getInstance()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            cm.setCookie("https://freedom.baby/", "own=keep; Path=/")
        }
        harness.load(originA)
        harness.awaitJsTrue("window.results && window.results.loaded === true")
        // One level above the per-protocol suffix: reaches every dweb
        // origin and every onchain app (web3.freedom.baby) alike.
        harness.js("document.cookie = 'toss=fromA; domain=freedom.baby; path=/';")
        assertTrue(cm.getCookie("https://0x1-1.web3.freedom.baby/").orEmpty().contains("toss"))
        CookieHygiene.sweepBlocking(originA)
        harness.load(originB)
        harness.awaitJsTrue("window.results && window.results.loaded === true")
        assertFalse(harness.js("document.cookie").contains("toss"))
        assertFalse(cm.getCookie("https://0x1-1.web3.freedom.baby/").orEmpty().contains("toss"))
        // The real freedom.baby site's own host-only cookie is untouched.
        assertTrue(cm.getCookie("https://freedom.baby/").orEmpty().contains("own=keep"))
    }

    @Test
    fun cookiesTossedAtANonRootPathAreSweptAtThatPath() {
        // R3-F1: a `Path=/swap` cookie is invisible to a `/` read, so a
        // root-only sweep left it for every other app at /swap.
        val cm = CookieManager.getInstance()
        val appA = "https://0x${"1".repeat(40)}-1.web3.freedom.baby/"
        val appB = "https://0x${"2".repeat(40)}-1.web3.freedom.baby/swap"
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            cm.setCookie(appA, "tossw3=1; Domain=.web3.freedom.baby; Path=/swap")
            cm.setCookie(appA, "tossfb=1; Domain=.freedom.baby; Path=/swap/")
            cm.setCookie(originA, "tossbzz=1; Domain=.bzz.freedom.baby; Path=/swap")
        }
        assertTrue(cm.getCookie(appB).orEmpty().contains("tossw3"))
        assertTrue(cm.getCookie("$appB/deep").orEmpty().contains("tossfb"))
        CookieHygiene.sweepBlocking("$appB/deep")
        val left = cm.getCookie("$appB/deep").orEmpty()
        assertFalse(left, left.contains("tossw3"))
        assertFalse(left, left.contains("tossfb"))
        assertFalse(cm.getCookie("${originA}swap").orEmpty().contains("tossbzz"))
    }

    @Test
    fun namelessTossedCookiesAreSwept() {
        // R5-F1: a nameless cookie serializes as just its value, so the
        // sweep read 'NAMELESS_FB' (or 'k' of '=k=v') as a name and
        // expired a different key, leaving the tossed cookie in place.
        val cm = CookieManager.getInstance()
        val app = "https://0x${"4".repeat(40)}-1.web3.freedom.baby/"
        harness.load(originA)
        harness.awaitJsTrue("window.results && window.results.loaded === true")
        harness.js("document.cookie = 'NAMELESS_FB; domain=freedom.baby; path=/';")
        harness.js("document.cookie = '=k=v; domain=bzz.freedom.baby; path=/';")
        harness.js("document.cookie = '=deep; domain=freedom.baby; path=/swap';")
        assertTrue(cm.getCookie(app).orEmpty().contains("NAMELESS_FB"))
        assertTrue(cm.getCookie(originB).orEmpty().contains("k=v"))
        CookieHygiene.sweepBlocking(listOf(originA, "${app}swap"))
        assertEquals("", cm.getCookie("${app}swap").orEmpty())
        harness.load(originB)
        harness.awaitJsTrue("window.results && window.results.loaded === true")
        assertEquals("", harness.js("document.cookie").trim('"'))
    }

    @Test
    fun prefixedAndPartitionedTossedCookiesAreSwept() {
        // R6-F1: Chromium drops a `__Secure-`/`__Host-` cookie written
        // without `Secure` — the `Max-Age=0` rewrite included — so an
        // unsecured expiry left prefixed tossed cookies in place.
        val cm = CookieManager.getInstance()
        val app = "https://0x${"5".repeat(40)}-1.web3.freedom.baby/"
        val elsewhere = "https://example.com/"
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            // Outside the covered origins: must survive the sweep.
            cm.setCookie(elsewhere, "__Secure-other=1; Domain=example.com; Path=/; Secure")
            cm.setCookie(elsewhere, "__Host-other=1; Path=/; Secure")
            cm.setCookie(elsewhere, "otherPart=1; Path=/; Secure; SameSite=None; Partitioned")
            cm.setCookie("https://freedom.baby/", "__Host-own=keep; Path=/; Secure")
            // Planted from an onchain app's origin, one level down.
            cm.setCookie(app, "__Secure-w3=1; Domain=web3.freedom.baby; Path=/; Secure")
        }
        harness.load(originA)
        harness.awaitJsTrue("window.results && window.results.loaded === true")
        harness.js("document.cookie = '__Secure-toss=1; domain=freedom.baby; path=/; Secure';")
        harness.js("document.cookie = '__Host-h=1; path=/; Secure';")
        harness.js("document.cookie = 'plain=1; domain=freedom.baby; path=/';")
        harness.js(
            "document.cookie = 'part=1; domain=freedom.baby; path=/; Secure; SameSite=None; Partitioned';",
        )
        harness.js("document.cookie = 'hostPart=1; path=/; Secure; SameSite=None; Partitioned';")
        val planted = harness.js("document.cookie")
        for (n in listOf("__Secure-toss", "__Host-h", "plain", "part", "hostPart")) {
            assertTrue("$n not planted: $planted", planted.contains("$n=1"))
        }
        assertTrue(cm.getCookie(app).orEmpty().contains("__Secure-w3"))

        CookieHygiene.sweepBlocking(listOf(originA, app))

        assertEquals("", cm.getCookie(originA).orEmpty())
        assertEquals("", cm.getCookie(app).orEmpty())
        harness.load(originB)
        harness.awaitJsTrue("window.results && window.results.loaded === true")
        assertEquals("", harness.js("document.cookie").trim('"'))
        harness.load(originA)
        harness.awaitJsTrue("window.results && window.results.loaded === true")
        assertEquals("", harness.js("document.cookie").trim('"'))
        // Untouched elsewhere.
        val other = cm.getCookie(elsewhere).orEmpty()
        assertTrue(other, other.contains("__Secure-other=1"))
        assertTrue(other, other.contains("__Host-other=1"))
        assertTrue(other, other.contains("otherPart=1"))
        assertTrue(cm.getCookie("https://freedom.baby/").orEmpty().contains("__Host-own=keep"))
    }

    @Test
    fun deepPathSweepIsBoundedByCookiesNotDepth() {
        // R4-F1: expiring every name at every candidate path took ~35 s
        // for 50 cookies under '/a' x 4000. The sweep now bisects the
        // candidate chain, so it stays fast — and still finds a cookie
        // tossed at a deep path and one at the root.
        val cm = CookieManager.getInstance()
        val app = "https://0x${"3".repeat(40)}-1.web3.freedom.baby"
        val deepPath = "/a".repeat(4000)
        val mid = "/a".repeat(1234) + "/"
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            for (n in 0 until 50) cm.setCookie("$app/", "h$n=1; Path=/")
            cm.setCookie("$app/", "tossmid=1; Domain=.web3.freedom.baby; Path=$mid")
            cm.setCookie("$app/", "tossdeep=1; Domain=.freedom.baby; Path=$deepPath")
        }
        assertTrue(cm.getCookie("$app$deepPath").orEmpty().contains("tossmid"))
        val started = System.nanoTime()
        CookieHygiene.sweepBlocking("$app$deepPath")
        val ms = (System.nanoTime() - started) / 1_000_000
        val left = cm.getCookie("$app$deepPath").orEmpty()
        assertTrue("sweep took $ms ms", ms < 3_000)
        assertEquals("", left)
    }
}
