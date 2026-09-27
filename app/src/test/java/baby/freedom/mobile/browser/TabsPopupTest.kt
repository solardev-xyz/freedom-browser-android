package baby.freedom.mobile.browser

import android.view.View
import android.webkit.WebChromeClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A page's own new windows (`target=_blank`, `window.open()`) become
 * tabs next to their opener, and `window.close()` from one of them
 * lands the user back where they came from (#82).
 */
class TabsPopupTest {

    private fun tabsWith(count: Int): TabsState {
        val tabs = TabsState(homepage = "ens://freedom.eth")
        repeat(count - 1) { tabs.newTab() }
        return tabs
    }

    @Test
    fun `popup opens right after its opener and becomes active`() {
        val tabs = tabsWith(3)
        tabs.switchTo(0)
        val opener = tabs.active
        val popup = tabs.adoptPopup(opener)
        assertEquals(4, tabs.tabs.size)
        assertSame(popup, tabs.tabs[1])
        assertSame(popup, tabs.active)
        assertEquals(opener.id, popup.openerId)
    }

    @Test
    fun `popup schedules no load of its own`() {
        // Chromium loads the popup's URL into the handed-back WebView
        // itself; a load from us would break the window.opener link.
        val tabs = tabsWith(1)
        val popup = tabs.adoptPopup(tabs.active)
        assertEquals(0, popup.navCounter)
        assertEquals("", popup.pendingUrl)
    }

    @Test
    fun `user-opened tabs have no opener`() {
        val tabs = tabsWith(1)
        assertNull(tabs.newTab().openerId)
    }

    @Test
    fun `closing the active popup returns to its opener`() {
        val tabs = tabsWith(3)
        tabs.switchTo(0)
        val opener = tabs.active
        val popup = tabs.adoptPopup(opener)
        tabs.closePopup(popup)
        assertEquals(3, tabs.tabs.size)
        assertSame(opener, tabs.active)
    }

    @Test
    fun `closing the active popup returns to its opener when it sits elsewhere`() {
        val tabs = tabsWith(2)
        val opener = tabs.tabs[1]
        tabs.switchTo(1)
        val popup = tabs.adoptPopup(opener)
        // The user reorders nothing, but moves on: open another tab and
        // come back to the popup, so the opener is no longer adjacent.
        tabs.newTab()
        tabs.switchTo(tabs.tabs.indexOf(popup))
        tabs.closePopup(popup)
        assertSame(opener, tabs.active)
    }

    @Test
    fun `closing a background popup leaves the active tab alone`() {
        val tabs = tabsWith(1)
        val opener = tabs.active
        val popup = tabs.adoptPopup(opener)
        val other = tabs.newTab()
        tabs.closePopup(popup)
        assertEquals(2, tabs.tabs.size)
        assertSame(other, tabs.active)
    }

    @Test
    fun `closing a popup whose opener is gone falls back to a neighbour`() {
        val tabs = tabsWith(2)
        val opener = tabs.tabs[0]
        tabs.switchTo(0)
        val popup = tabs.adoptPopup(opener)
        tabs.closeTab(tabs.tabs.indexOf(opener))
        tabs.switchTo(tabs.tabs.indexOf(popup))
        tabs.closePopup(popup)
        assertEquals(1, tabs.tabs.size)
        assertSame(tabs.tabs[0], tabs.active)
    }

    @Test
    fun `window close from a user-opened tab is ignored`() {
        // Chromium allows window.close() in a tab with one history entry;
        // only windows a page opened may close themselves.
        val tabs = tabsWith(2)
        val tab = tabs.tabs[1]
        tabs.switchTo(1)
        tabs.closePopup(tab)
        assertEquals(2, tabs.tabs.size)
        assertSame(tab, tabs.active)
    }

    @Test
    fun `window close from the only user-opened tab is ignored`() {
        val tabs = tabsWith(1)
        val tab = tabs.active
        tabs.closePopup(tab)
        assertEquals(1, tabs.tabs.size)
        assertSame(tab, tabs.active)
    }

    @Test
    fun `closing an already closed popup is a no-op`() {
        val tabs = tabsWith(2)
        val popup = tabs.adoptPopup(tabs.tabs[0])
        tabs.closeTab(tabs.tabs.indexOf(popup))
        val before = tabs.active
        tabs.closePopup(popup)
        assertEquals(2, tabs.tabs.size)
        assertSame(before, tabs.active)
    }

    @Test
    fun `a popup's blank document is a page, not the home overlay`() {
        // `window.open('')` + `document.write(...)` never commits a
        // non-blank URL; the home overlay must not cover what was
        // written.
        val tabs = tabsWith(1)
        val popup = tabs.adoptPopup(tabs.active)
        assertTrue(popup.blankIsPage)
        assertFalse(popup.isHome)
        assertEquals(ABOUT_BLANK, popup.addressBarText)
    }

    @Test
    fun `a popup taken Home is home`() {
        val tabs = tabsWith(1)
        val popup = tabs.adoptPopup(tabs.active)
        popup.navigateHome()
        assertFalse(popup.blankIsPage)
        assertTrue(popup.isHome)
    }

    @Test
    fun `user-opened tabs start at home`() {
        val tabs = tabsWith(1)
        val tab = tabs.newTab()
        assertFalse(tab.blankIsPage)
        assertTrue(tab.isHome)
    }

    @Test
    fun `a popup from a fullscreen opener ends the opener's fullscreen`() {
        // Fullscreen belongs to the active tab only; the popup becomes
        // active, so the opener's session must go, and the page is told.
        val tabs = tabsWith(1)
        val opener = tabs.active
        var hidden = 0
        val callback = object : WebChromeClient.CustomViewCallback {
            override fun onCustomViewHidden() { hidden++ }
        }
        tabs.enterFullscreen(opener, View(null), callback)
        assertEquals(opener.id, tabs.fullscreen?.tabId)
        tabs.adoptPopup(opener)
        assertNull(tabs.fullscreen)
        assertEquals(1, hidden)
    }
}
