package baby.freedom.mobile.browser

import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.test.ext.junit.runners.AndroidJUnit4
import baby.freedom.mobile.ens.EnsResult
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
    private val ensLookups = java.util.concurrent.atomic.AtomicInteger(0)

    @Before
    fun setUp() {
        gateway.start()
        harness.setUp()
        KnownEnsNames.clear()
        Gateways.ensLookup = { name ->
            ensLookups.incrementAndGet()
            val ref = testdappContent
            if (name == "testdapp.eth" && ref != null) {
                EnsResult.Ok(name, "bzz", "bzz://$ref", ref)
            } else {
                EnsResult.NotFound(name, "NO_CONTENTHASH")
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
        assertEquals(
            "ens_not_found",
            errorCodeForMainFrameHttpError(404, harness.lastHttpErrorHeaders.get()),
        )
        assertFalse(harness.js("document.body.innerText").contains("VERSION_B"))
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
}
