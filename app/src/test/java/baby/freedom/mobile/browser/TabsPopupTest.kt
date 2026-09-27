package baby.freedom.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
    fun `closing an already closed popup is a no-op`() {
        val tabs = tabsWith(2)
        val popup = tabs.adoptPopup(tabs.tabs[0])
        tabs.closeTab(tabs.tabs.indexOf(popup))
        val before = tabs.active
        tabs.closePopup(popup)
        assertEquals(2, tabs.tabs.size)
        assertSame(before, tabs.active)
    }
}
