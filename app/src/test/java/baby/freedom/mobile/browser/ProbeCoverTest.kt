package baby.freedom.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #616: while a dweb address the user named is still being probed, the
 * tab shows a loading screen for it ([BrowserState.coversPageForProbe]),
 * not the previous page under the new address — until the new load's
 * page (or error page) is drawn, the user stops it, or a newer load
 * takes over.
 */
class ProbeCoverTest {

    private val oldPage = "freemap.eth/missing"
    private val newAddress = "bzz://" + "ab".repeat(32)

    /** A tab on [oldPage] whose user just submitted [newAddress]: its probe is running. */
    private fun probingTab(): BrowserState {
        val tab = BrowserState(id = 1L)
        tab.url = oldPage
        tab.addressBarText = newAddress
        tab.beginLoad()
        tab.coverPageForProbe(tab.loadGeneration)
        return tab
    }

    /** The probe answered and handed its load to the WebView. */
    private fun BrowserState.handOff() {
        loadUrl(newAddress)
        handLoadToWebView(newAddress)
    }

    /** `onPageStarted` of the handed load: the bar takes its address. */
    private fun BrowserState.commit() {
        webViewDocumentCommitted()
        url = newAddress
        addressBarText = newAddress
    }

    /** The chrome's Stop. */
    private fun BrowserState.userStop() {
        cancelPendingProbe()
        stopProgress()
        stopProbeCover()
    }

    @Test
    fun `the previous page is covered while the new address is probed`() {
        assertTrue(probingTab().coversPageForProbe)
    }

    @Test
    fun `no cover unless a submit asked for one`() {
        val tab = BrowserState(id = 1L)
        tab.url = oldPage
        tab.addressBarText = newAddress
        tab.beginLoad()
        assertFalse(tab.coversPageForProbe)
    }

    @Test
    fun `the cover stays from the probe's answer until the new page is drawn`() {
        val tab = probingTab()
        tab.handOff()
        // Handed, not yet committed: the old page is still what the
        // WebView draws.
        assertTrue(tab.coversPageForProbe)
        // Committed: still the old page's pixels until the new document
        // first paints.
        tab.commit()
        assertTrue(tab.coversPageForProbe)
        // `onPageCommitVisible`.
        tab.webViewPageDrawn()
        assertFalse(tab.coversPageForProbe)
    }

    @Test
    fun `a load that ends without a document uncovers the page on screen`() {
        val tab = probingTab()
        tab.handOff()
        // A 204: the finish is of the handed URL, which never committed —
        // as Chromium spells it, with a slash added.
        assertEquals(null, tab.webViewLoadSettled("$newAddress/"))
        assertFalse(tab.coversPageForProbe)
    }

    @Test
    fun `a server redirect of the handed load keeps it covered`() {
        val tab = probingTab()
        tab.handOff()
        tab.beginLoad(inWebView = true, redirect = true)
        tab.webViewNavigating("https://gateway.example/bzz/moved")
        assertTrue(tab.coversPageForProbe)
        tab.commit()
        tab.webViewPageDrawn()
        assertFalse(tab.coversPageForProbe)
    }

    @Test
    fun `a redirect hop of the handed load that ends without a document uncovers`() {
        val tab = probingTab()
        tab.handOff()
        tab.beginLoad(inWebView = true, redirect = true)
        tab.webViewNavigating("https://gateway.example/bzz/moved")
        tab.webViewLoadSettled("https://gateway.example/bzz/moved")
        assertFalse(tab.coversPageForProbe)
    }

    @Test
    fun `a late callback of the old page doesn't uncover the probe`() {
        val tab = probingTab()
        // The old page's own finish and paint arrive while the probe is
        // still polling…
        tab.webViewLoadSettled(oldPage)
        tab.webViewPageDrawn()
        assertTrue(tab.coversPageForProbe)
        // …or once the load was handed over, posted by the hand-off's
        // stopLoading().
        tab.handOff()
        assertEquals(null, tab.webViewLoadSettled(oldPage))
        tab.webViewPageDrawn()
        assertTrue(tab.coversPageForProbe)
    }

    @Test
    fun `the covered page's finish before its first paint keeps it covered`() {
        // R4-F2: `onPageFinished` can come ahead of `onPageCommitVisible`,
        // while the WebView still draws the old page.
        val tab = probingTab()
        tab.handOff()
        tab.commit()
        val token = tab.webViewLoadSettled(newAddress)
        assertTrue(tab.coversPageForProbe)
        tab.webViewPageDrawn()
        assertFalse(tab.coversPageForProbe)
        // The fallback then finds nothing to do.
        tab.probeCoverPaintOverdue(token!!)
        assertFalse(tab.coversPageForProbe)
    }

    @Test
    fun `a covered page no paint is reported for is uncovered after the grace`() {
        val tab = probingTab()
        tab.handOff()
        tab.commit()
        val token = tab.webViewLoadSettled(newAddress)!!
        assertTrue(tab.coversPageForProbe)
        tab.probeCoverPaintOverdue(token)
        assertFalse(tab.coversPageForProbe)
    }

    @Test
    fun `a stale paint fallback doesn't uncover a newer covered load`() {
        val tab = probingTab()
        tab.handOff()
        tab.commit()
        val token = tab.webViewLoadSettled(newAddress)!!
        // The user submits another dweb address before the grace ran out.
        tab.addressBarText = "bzz://" + "cd".repeat(32)
        tab.beginLoad()
        tab.coverPageForProbe(tab.loadGeneration)
        tab.probeCoverPaintOverdue(token)
        assertTrue(tab.coversPageForProbe)
        // Nor one of the same load before it committed.
        tab.loadUrl(tab.addressBarText)
        tab.handLoadToWebView(tab.addressBarText)
        tab.probeCoverPaintOverdue(tab.loadGeneration)
        assertTrue(tab.coversPageForProbe)
    }

    @Test
    fun `reloading the page on screen doesn't cover it`() {
        // Reload, Hard reload, or the chrome's Reload on an error page:
        // the bar names the page that is on screen.
        val tab = BrowserState(id = 1L)
        tab.url = newAddress
        tab.addressBarText = newAddress
        tab.beginLoad()
        tab.coverPageForProbe(tab.loadGeneration)
        assertFalse(tab.coversPageForProbe)
    }

    @Test
    fun `Stop uncovers the page on screen`() {
        val tab = probingTab()
        tab.userStop()
        assertFalse(tab.coversPageForProbe)
        // …for good: a later load of the same generation doesn't bring
        // it back.
        tab.handOff()
        assertFalse(tab.coversPageForProbe)
    }

    @Test
    fun `Stop doesn't keep a later submit's probe uncovered`() {
        val tab = probingTab()
        tab.userStop()
        // The stopped probe arming late changes nothing…
        tab.coverPageForProbe(tab.loadGeneration)
        assertFalse(tab.coversPageForProbe)
        // …but the user's next dweb submit is covered for its whole probe,
        // though the Stop latch only opens at that probe's own loadUrl.
        tab.cancelPendingProbe()
        tab.beginLoad()
        tab.coverPageForProbe(tab.loadGeneration)
        assertTrue(tab.loadAborted)
        assertTrue(tab.coversPageForProbe)
        tab.handOff()
        assertTrue(tab.coversPageForProbe)
    }

    @Test
    fun `a newer load takes the cover away from the superseded one`() {
        val tab = probingTab()
        val superseded = tab.loadGeneration
        // A link, Back / Forward or a page's own submit: a new generation
        // the cover wasn't asked for.
        tab.beginLoad(inWebView = true)
        assertFalse(tab.coversPageForProbe)
        // The superseded probe arming late changes nothing.
        tab.coverPageForProbe(superseded)
        assertFalse(tab.coversPageForProbe)
    }

    @Test
    fun `a newer covered submit keeps its cover when the superseded one ends`() {
        val tab = probingTab()
        tab.beginLoad()
        tab.coverPageForProbe(tab.loadGeneration)
        // The cancelled first probe's late callbacks: nothing was handed
        // for the new one yet.
        tab.webViewLoadSettled(oldPage)
        tab.webViewLoadSettled(newAddress)
        tab.webViewPageDrawn()
        assertTrue(tab.coversPageForProbe)
    }

    @Test
    fun `a navigation already in flight that commits mid-probe uncovers its page`() {
        // A slow link the WebView was following when the user submitted
        // the dweb address: it lands while the probe still polls, and the
        // bar takes its address.
        val tab = probingTab()
        tab.webViewDocumentCommitted()
        tab.url = "http://127.0.0.1:8720/slowpage"
        tab.addressBarText = tab.url
        assertFalse(tab.coversPageForProbe)
        // Its paint and finish change nothing more, and the probe's own
        // answer later loads as usual, uncovered.
        tab.webViewPageDrawn()
        tab.webViewLoadSettled(tab.url)
        tab.handOff()
        assertFalse(tab.coversPageForProbe)
    }

    @Test
    fun `a redirect of a navigation already in flight keeps the old page covered until it commits`() {
        val tab = probingTab()
        // The in-flight link's 302: still the old page on screen.
        tab.beginLoad(inWebView = true, redirect = true)
        assertTrue(tab.coversPageForProbe)
        // The hop's target lands: its own page, uncovered.
        tab.webViewDocumentCommitted()
        assertFalse(tab.coversPageForProbe)
    }

    @Test
    fun `a redirect of a navigation already in flight hands the cover on to the probe's load`() {
        val tab = probingTab()
        tab.beginLoad(inWebView = true, redirect = true)
        tab.webViewNavigating("https://example.org/new")
        // The probe answers before the hop's target commits: the hand-off
        // stops that navigation, and the cover is the probe's load's.
        tab.handOff()
        tab.webViewLoadSettled("https://example.org/new")
        assertTrue(tab.coversPageForProbe)
        tab.commit()
        assertTrue(tab.coversPageForProbe)
        tab.webViewPageDrawn()
        assertFalse(tab.coversPageForProbe)
    }

    @Test
    fun `the finish of any navigation the hand-off aborted doesn't uncover the probe`() {
        // A navigation was on its way when the address was submitted: a
        // link (R3-F1), or one the WebView was handed itself — a typed or
        // bookmarked address, a History entry, Back / Forward (R4-F1) — or
        // a form POST, none of which the cover was told of.
        for (aborted in listOf("https://example.org/slowpage", "http://slow-host/slowpage", oldPage + "/before")) {
            val tab = probingTab()
            // The probe answers first: the hand-off's stopLoading()
            // aborts it, and its finish comes in after the hand-off.
            tab.handOff()
            tab.webViewLoadSettled(aborted)
            assertTrue(aborted, tab.coversPageForProbe)
            tab.commit()
            assertTrue(tab.coversPageForProbe)
            // Even once the covered load committed: no early uncover.
            tab.webViewLoadSettled(aborted)
            assertTrue(tab.coversPageForProbe)
            tab.webViewPageDrawn()
            assertFalse(tab.coversPageForProbe)
        }
    }

    @Test
    fun `a browser-initiated load handed before the probe's isn't the covered load`() {
        // The typed slow address was handed to the WebView before the
        // dweb submit: its URL isn't the covered load's (R4-F1).
        val slow = "http://slow-host/slowpage"
        val tab = BrowserState(id = 1L)
        tab.url = oldPage
        tab.addressBarText = slow
        tab.beginLoad()
        tab.loadUrl(slow)
        tab.handLoadToWebView(slow)
        tab.addressBarText = newAddress
        tab.beginLoad()
        tab.coverPageForProbe(tab.loadGeneration)
        tab.handOff()
        tab.webViewLoadSettled(slow)
        assertTrue(tab.coversPageForProbe)
    }

    @Test
    fun `the handed load's own end without a document still uncovers after an aborted one`() {
        val slow = "https://example.org/slowpage"
        val tab = probingTab()
        tab.handOff()
        tab.webViewLoadSettled(slow)
        // The probe's load answers 204: the page on screen stays.
        tab.webViewLoadSettled(newAddress)
        assertFalse(tab.coversPageForProbe)
    }

    @Test
    fun `the cover's URL match ignores a trailing slash and the scheme and host case only`() {
        assertEquals(probeCoverUrlKey("bzz://abc"), probeCoverUrlKey("BZZ://ABC/"))
        assertEquals(probeCoverUrlKey("https://Example.org/a/"), probeCoverUrlKey("https://example.org/a"))
        assertFalse(probeCoverUrlKey("https://example.org/A") == probeCoverUrlKey("https://example.org/a"))
    }

    @Test
    fun `a navigation already in flight that ends without a document keeps the probe covered`() {
        val tab = probingTab()
        // A 204, or a load the WebView turned into a download.
        tab.webViewLoadSettled("https://example.org/204")
        tab.stopProgress()
        assertTrue(tab.coversPageForProbe)
    }

    @Test
    fun `the covered load ending without a page uncovers the page on screen`() {
        // It became a download, went to another app, or the page on
        // screen refused to be left.
        val tab = probingTab()
        tab.handOff()
        tab.stopProgress()
        assertFalse(tab.coversPageForProbe)
    }

    @Test
    fun `the loading screen names the address the cover was armed for`() {
        val tab = probingTab()
        // The old page rewrites the bar (`pushState`) under the cover.
        tab.addressBarText = "freemap.eth/elsewhere"
        assertTrue(tab.coversPageForProbe)
        assertEquals(newAddress, tab.probeCoverAddress)
    }

    @Test
    fun `Home uncovers`() {
        val tab = probingTab()
        tab.navigateHome()
        assertFalse(tab.coversPageForProbe)
    }

    @Test
    fun `losing the WebView uncovers`() {
        val tab = probingTab()
        tab.webViewLost()
        assertFalse(tab.coversPageForProbe)
    }

    @Test
    fun `only a submit that puts its address in the bar covers the page`() {
        // What `gateGatewayNavigation`'s `coverPage` is passed.
        assertTrue(SubmitSource.User.namesDestination)
        assertTrue(SubmitSource.External.namesDestination)
        // The error page retrying its own address, or a page's link.
        assertFalse(SubmitSource.Renderer.namesDestination)
    }
}
