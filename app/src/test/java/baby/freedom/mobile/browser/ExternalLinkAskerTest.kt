package baby.freedom.mobile.browser

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    @Test
    fun `a tap during a pending address-bar load is offered for the page on screen`() {
        commit("https://evil.example/pay")
        // The user submits another address: it's pending, the old page
        // is still on screen. The WebView's own `url` already names the
        // pending load; the tab's state says what's on screen.
        val pendingUrl = "https://bank.example/"
        tab.addressBarText = pendingUrl
        val asker = externalLinkAsker(tab)
        assertEquals("https://evil.example", asker?.origin)
        assertNotEquals(permissionOriginKey(pendingUrl), asker?.origin)
        assertTrue(externalLinkPageCurrent(tab, asker!!.doc))
    }

    @Test
    fun `an offer that runs after the next page committed is for a document that is gone`() {
        commit("https://evil.example/")
        val asker = externalLinkAsker(tab)!!
        commit("https://bank.example/")
        // offerExternalLink drops it: the page that asked isn't the tab's document.
        assertFalse(externalLinkPageCurrent(tab, asker.doc))
        // A tap on the new page is offered for it, as its own document.
        val next = externalLinkAsker(tab)!!
        assertEquals("https://bank.example", next.origin)
        assertTrue(externalLinkPageCurrent(tab, next.doc))
    }

    @Test
    fun `a page with no web origin has nobody to offer for`() {
        commit("file:///sdcard/page.html")
        assertNull(externalLinkAsker(tab))
    }
}
