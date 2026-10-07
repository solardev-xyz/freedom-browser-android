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
    fun `a popup opened behind lands after its opener without switching the tab on screen`() {
        // #261 / #292 R1-M3: a background tab's allowed, gesture-less
        // window.open must not take the screen from the tab the user is on.
        val tabs = tabsWith(3)
        tabs.switchTo(2)
        val onScreen = tabs.active
        val popup = tabs.adoptPopup(tabs.tabs[0], activate = false)
        assertEquals(4, tabs.tabs.size)
        assertSame(popup, tabs.tabs[1])
        assertSame(onScreen, tabs.active)
        // Opened after the active tab: the index needs no shift.
        tabs.switchTo(0)
        val later = tabs.adoptPopup(tabs.tabs[3], activate = false)
        assertSame(later, tabs.tabs[4])
        assertSame(tabs.tabs[0], tabs.active)
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
    fun `a popup's blank document asks in its opener's name until it commits or goes Home`() {
        // #445 R1-F1: `window.open('')` + `document.write` leaves the popup
        // on an uncommitted `about:blank` that inherits the opener's origin;
        // its own getUserMedia/geolocation must still be asked about in
        // that site's name, not refused for having no site.
        val tabs = tabsWith(1)
        val opener = tabs.active
        opener.permissionOrigin = "http://localhost:8730"
        val popup = tabs.adoptPopup(opener)
        assertNull(popup.permissionOrigin)
        assertEquals("http://localhost:8730", popup.permissionTop)
        assertEquals(
            PermissionScope("http://localhost:8730"),
            permissionScopeFor("http://localhost:8730/", popup.permissionTop),
        )
        // A frame inside that blank document is the frame's pair, as anywhere.
        assertEquals(
            PermissionScope("https://meet.example", "http://localhost:8730"),
            permissionScopeFor("https://meet.example/", popup.permissionTop),
        )
        // The popup's first commit takes over…
        popup.blankIsPage = false
        popup.permissionOrigin = "https://other.example"
        assertEquals("https://other.example", popup.permissionTop)
        // …and once it has no blank page of its own, nothing stands in.
        popup.permissionOrigin = null
        assertNull(popup.permissionTop)
        // Home ends it too.
        val again = tabs.adoptPopup(opener)
        again.navigateHome()
        assertNull(again.permissionTop)
        // A user-opened tab never inherits anything.
        assertNull(tabs.newTab().permissionTop)
    }

    @Test
    fun `a popup opened from a blank popup still asks in the first opener's name`() {
        // #445 R2-F1: `w = window.open(''); w2 = w.open('')` — the middle
        // popup has no committed origin, but its blank document is the
        // opener's site, and so is the grandchild's.
        val tabs = tabsWith(1)
        val opener = tabs.active
        opener.permissionOrigin = "http://localhost:8730"
        val popup = tabs.adoptPopup(opener)
        val grandchild = tabs.adoptPopup(popup)
        assertNull(grandchild.permissionOrigin)
        assertEquals("http://localhost:8730", grandchild.permissionTop)
        assertEquals(
            PermissionScope("http://localhost:8730"),
            permissionScopeFor("http://localhost:8730/", grandchild.permissionTop),
        )
        // A middle popup that has committed elsewhere passes on its own site.
        popup.blankIsPage = false
        popup.permissionOrigin = "https://other.example"
        assertEquals("https://other.example", tabs.adoptPopup(popup).permissionTop)
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

    private class Hidden : WebChromeClient.CustomViewCallback {
        var count = 0
        override fun onCustomViewHidden() { count++ }
    }

    @Test
    fun `a new active tab ends the old tab's fullscreen`() {
        // A link from another app arriving while a page is fullscreen
        // opens a new active tab (BrowserScreen's deep-link branch): the
        // old page's fullscreen view must not stay over it.
        val tabs = tabsWith(1)
        val playing = tabs.active
        val hidden = Hidden()
        tabs.enterFullscreen(playing, View(null), hidden)
        val fresh = tabs.newTab()
        assertSame(fresh, tabs.active)
        assertNull(tabs.fullscreen)
        assertEquals(1, hidden.count)
    }

    @Test
    fun `a tab opened behind leaves the fullscreen tab on screen in fullscreen`() {
        val tabs = tabsWith(1)
        val playing = tabs.active
        val hidden = Hidden()
        tabs.enterFullscreen(playing, View(null), hidden)
        tabs.newTab(activate = false)
        assertSame(playing, tabs.active)
        assertEquals(playing.id, tabs.fullscreen?.tabId)
        assertEquals(0, hidden.count)
    }

    @Test
    fun `switching to another tab ends fullscreen, staying on the same tab doesn't`() {
        val tabs = tabsWith(2)
        tabs.switchTo(0)
        val playing = tabs.active
        val hidden = Hidden()
        tabs.enterFullscreen(playing, View(null), hidden)
        tabs.switchTo(0)
        assertEquals(playing.id, tabs.fullscreen?.tabId)
        tabs.switchTo(1)
        assertNull(tabs.fullscreen)
        assertEquals(1, hidden.count)
    }
}
