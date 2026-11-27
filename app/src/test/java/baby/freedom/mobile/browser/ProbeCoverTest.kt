package baby.freedom.mobile.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #616: while a dweb address the user named is still being probed, the
 * tab shows a loading screen for it ([BrowserState.coversPageForProbe]),
 * not the previous page under the new address — until the new load's
 * page (or error page) commits, the user stops it, or a newer load
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
    fun `the cover stays from the probe's answer until the page commits`() {
        val tab = probingTab()
        tab.handOff()
        // Handed, not yet committed: the old page is still what the
        // WebView draws.
        assertTrue(tab.coversPageForProbe)
        tab.webViewLoadSettled()
        assertFalse(tab.coversPageForProbe)
    }

    @Test
    fun `a late callback of the old page doesn't uncover the probe`() {
        val tab = probingTab()
        // The old page's own finish (or a commit of a load it had going)
        // arrives while the probe is still polling.
        tab.webViewLoadSettled()
        assertTrue(tab.coversPageForProbe)
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
        tab.webViewLoadSettled()
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
