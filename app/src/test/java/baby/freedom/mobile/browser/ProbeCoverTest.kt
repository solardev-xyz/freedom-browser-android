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
        handLoadToWebView()
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
        tab.webViewLoadSettled(samePage = true)
        assertFalse(tab.coversPageForProbe)
    }

    @Test
    fun `a load that ends without a document uncovers the page on screen`() {
        val tab = probingTab()
        tab.handOff()
        // A 204: the finish is of the handed URL, which never committed.
        tab.webViewLoadSettled(samePage = false)
        assertFalse(tab.coversPageForProbe)
    }

    @Test
    fun `a server redirect of the handed load keeps it covered`() {
        val tab = probingTab()
        tab.handOff()
        tab.beginLoad(inWebView = true, redirect = true)
        assertTrue(tab.coversPageForProbe)
        tab.commit()
        tab.webViewLoadSettled(samePage = true)
        assertFalse(tab.coversPageForProbe)
    }

    @Test
    fun `a late callback of the old page doesn't uncover the probe`() {
        val tab = probingTab()
        // The old page's own finish arrives while the probe is still
        // polling…
        tab.webViewLoadSettled(samePage = true)
        tab.webViewLoadSettled(samePage = false)
        assertTrue(tab.coversPageForProbe)
        // …or once the load was handed over, posted by the hand-off's
        // stopLoading().
        tab.handOff()
        tab.webViewLoadSettled(samePage = true)
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
        tab.webViewLoadSettled(samePage = true)
        tab.webViewLoadSettled(samePage = false)
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
        tab.webViewLoadSettled(samePage = true)
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
        // The probe answers before the hop's target commits: the hand-off
        // stops that navigation, and the cover is the probe's load's.
        tab.handOff()
        tab.webViewLoadSettled(samePage = true)
        assertTrue(tab.coversPageForProbe)
        tab.commit()
        assertTrue(tab.coversPageForProbe)
        tab.webViewLoadSettled(samePage = true)
        assertFalse(tab.coversPageForProbe)
    }

    @Test
    fun `the finish of a navigation the hand-off aborted doesn't uncover the probe`() {
        // A link to a slow page was on its way when the address was
        // submitted (R3-F1).
        val slow = "https://example.org/slowpage"
        val tab = probingTab()
        tab.webViewNavigating(slow)
        // The probe answers first: the hand-off's stopLoading() aborts
        // the link, whose finish comes in after the hand-off.
        tab.handOff()
        tab.webViewLoadSettled(samePage = false, url = slow)
        assertTrue(tab.coversPageForProbe)
        tab.commit()
        assertTrue(tab.coversPageForProbe)
        tab.webViewLoadSettled(samePage = true)
        assertFalse(tab.coversPageForProbe)
    }

    @Test
    fun `the finish of a redirect hop the hand-off aborted doesn't uncover the probe`() {
        val tab = probingTab()
        tab.webViewNavigating("https://example.org/old")
        tab.beginLoad(inWebView = true, redirect = true)
        tab.webViewNavigating("https://example.org/new")
        tab.handOff()
        tab.webViewLoadSettled(samePage = false, url = "https://example.org/new")
        assertTrue(tab.coversPageForProbe)
    }

    @Test
    fun `the handed load's own end without a document still uncovers after an aborted one`() {
        val slow = "https://example.org/slowpage"
        val tab = probingTab()
        tab.webViewNavigating(slow)
        tab.handOff()
        tab.webViewLoadSettled(samePage = false, url = slow)
        // The probe's load answers 204: the page on screen stays.
        tab.webViewLoadSettled(samePage = false, url = "https://gateway.example/bzz/x")
        assertFalse(tab.coversPageForProbe)
    }

    @Test
    fun `a navigation that finished before the hand-off isn't taken for an aborted one`() {
        val other = "https://example.org/204"
        val tab = probingTab()
        tab.webViewNavigating(other)
        // It ended without a document while the probe still ran.
        tab.webViewLoadSettled(samePage = false, url = other)
        tab.handOff()
        // The covered load answers 204 from the same address.
        tab.webViewLoadSettled(samePage = false, url = other)
        assertFalse(tab.coversPageForProbe)
    }

    @Test
    fun `a navigation already in flight that ends without a document keeps the probe covered`() {
        val tab = probingTab()
        // A 204, or a load the WebView turned into a download.
        tab.webViewLoadSettled(samePage = false)
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
