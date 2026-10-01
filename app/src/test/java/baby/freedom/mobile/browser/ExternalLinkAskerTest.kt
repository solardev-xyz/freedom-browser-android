package baby.freedom.mobile.browser

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who an app or payment link a page's tap opens is offered for (#342):
 * the origin the tab committed, never the pending address of a load the
 * browser started meanwhile (`WebView.getUrl()` already reports that one
 * while the old page is still on screen and taking taps).
 */
class ExternalLinkAskerTest {
    private val tab = BrowserState(9_700L)

    @After
    fun tearDown() {
        EthereumProviders.onTabClosed(tab.id)
    }

    /** What the tab's WebView does at `onPageStarted` for [url]. */
    private fun commit(url: String) {
        EthereumProviders.onDocumentStarted(tab, url)
        tab.permissionOrigin = permissionOriginKey(url)
        tab.url = url
    }

    /** What the tab's WebView does at `onPageFinished` for Back/Forward onto Home's blank entry, which gets no `onPageStarted`. */
    private fun backToHome() {
        tab.url = ""
        tab.addressBarText = ""
        tab.permissionOrigin = null
    }

    /**
     * The WebView itself can't run in a JVM test, so `getUrl()`'s pending
     * address isn't modelled here: [externalLinkAsker] takes only the
     * tab's state, so it can't read it. What this pins down is that of
     * the tab's own fields it reads the committed origin, not the
     * addresses that already show what comes next.
     */
    @Test
    fun `the asker is the origin the tab committed, not an address naming what comes next`() {
        commit("https://evil.example/pay")
        tab.addressBarText = "https://bank.example/"
        tab.url = "https://bank.example/"
        val asker = externalLinkAsker(tab)
        assertEquals("https://evil.example", asker?.origin)
        assertTrue(externalLinkPageCurrent(tab, asker!!.origin, asker.doc))
    }

    @Test
    fun `an offer that runs after the next page committed is for a document that is gone`() {
        commit("https://evil.example/")
        val asker = externalLinkAsker(tab)!!
        commit("https://bank.example/")
        // offerExternalLink drops it: the page that asked isn't the tab's document.
        assertFalse(externalLinkPageCurrent(tab, asker.origin, asker.doc))
        // A tap on the new page is offered for it, as its own document.
        val next = externalLinkAsker(tab)!!
        assertEquals("https://bank.example", next.origin)
        assertTrue(externalLinkPageCurrent(tab, next.origin, next.doc))
    }

    @Test
    fun `an offer that runs after Back onto Home is for a page that is gone`() {
        commit("https://evil.example/")
        val asker = externalLinkAsker(tab)!!
        // Back/Forward onto the blank entry fires no onPageStarted: same
        // document number, but the tab is on Home, with no origin.
        backToHome()
        assertFalse(externalLinkPageCurrent(tab, asker.origin, asker.doc))
        assertNull(externalLinkAsker(tab))
    }

    @Test
    fun `an offer for another origin than the committed one is not current`() {
        commit("https://evil.example/")
        val asker = externalLinkAsker(tab)!!
        assertFalse(externalLinkPageCurrent(tab, "https://bank.example", asker.doc))
    }

    @Test
    fun `a page with no web origin has nobody to offer for`() {
        commit("file:///sdcard/page.html")
        assertNull(externalLinkAsker(tab))
    }
}
