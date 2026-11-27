package baby.freedom.mobile.browser

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
        tab.stopProgress()
        assertFalse(tab.coversPageForProbe)
        // …for good: a later load of the same generation doesn't bring
        // it back.
        tab.handOff()
        assertFalse(tab.coversPageForProbe)
    }

    @Test
    fun `Stop doesn't keep a later submit's probe uncovered`() {
        val tab = probingTab()
        tab.stopProgress()
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
        tab.webViewDocumentCommitted()
        tab.webViewLoadSettled(samePage = true)
        tab.webViewLoadSettled(samePage = false)
        assertTrue(tab.coversPageForProbe)
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
