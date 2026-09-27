package baby.freedom.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reorder and reopen-closed-tab (#90) on [TabsState]. The WebView side
 * (saving and restoring the back/forward list) lives in
 * [BrowserWebViewHost] and is exercised on the device.
 */
class TabsStateTest {

    /** Three tabs on pages a, b, c; the first one active. */
    private fun threeTabs(): TabsState {
        val tabs = TabsState(homepage = HOME_URL)
        tabs.tabs[0].visit("a")
        tabs.newTab().visit("b")
        tabs.newTab().visit("c")
        tabs.switchTo(0)
        return tabs
    }

    private fun BrowserState.visit(page: String) {
        url = "https://$page.example/"
        title = page
        addressBarText = url
    }

    private val TabsState.titles get() = tabs.map { it.title }

    @Test
    fun `moving a tab reorders the list and keeps the same tab active`() {
        val tabs = threeTabs()
        val active = tabs.active
        tabs.moveTab(0, 2)
        assertEquals(listOf("b", "c", "a"), tabs.titles)
        assertSame(active, tabs.active)
        assertEquals(2, tabs.activeIndex)

        tabs.switchTo(1) // c
        tabs.moveTab(2, 0)
        assertEquals(listOf("a", "b", "c"), tabs.titles)
        assertEquals("c", tabs.active.title)
    }

    @Test
    fun `out of range moves are ignored`() {
        val tabs = threeTabs()
        tabs.moveTab(0, 3)
        tabs.moveTab(-1, 0)
        tabs.moveTab(1, 1)
        assertEquals(listOf("a", "b", "c"), tabs.titles)
    }

    @Test
    fun `reopen brings the last closed tab back where it was, active`() {
        val tabs = threeTabs()
        val closedId = tabs.tabs[1].id
        tabs.closeTab(1)
        assertTrue(tabs.canReopenClosedTab)
        assertEquals(listOf("a", "c"), tabs.titles)

        val reopened = tabs.reopenClosedTab()
        assertNotNull(reopened)
        reopened!!
        assertEquals(listOf("a", "b", "c"), tabs.titles)
        assertSame(reopened, tabs.active)
        assertEquals("https://b.example/", reopened.url)
        assertEquals("https://b.example/", reopened.addressBarText)
        // A fresh tab — never the closed one's id, whose WebView is gone.
        assertTrue(reopened.id != closedId)
        // Handed to the host to rebuild the WebView from.
        assertEquals("https://b.example/", reopened.pendingRestore?.fallbackUrl)
        assertFalse(tabs.canReopenClosedTab)
        assertNull(tabs.reopenClosedTab())
    }

    @Test
    fun `reopen walks back most recent first and clamps the position`() {
        val tabs = threeTabs()
        tabs.closeTab(2) // c, from index 2
        tabs.closeTab(0) // a, from index 0
        assertEquals(listOf("b"), tabs.titles)
        assertEquals("a", tabs.reopenClosedTab()?.title)
        assertEquals(listOf("a", "b"), tabs.titles)
        assertEquals("c", tabs.reopenClosedTab()?.title)
        assertEquals(listOf("a", "b", "c"), tabs.titles)
    }

    @Test
    fun `closing the last tab can still be undone`() {
        val tabs = TabsState(homepage = HOME_URL)
        tabs.tabs[0].visit("a")
        tabs.closeTab(0)
        assertEquals(listOf(""), tabs.titles) // fresh home tab
        tabs.reopenClosedTab()
        assertEquals(listOf("a", ""), tabs.titles)
        assertEquals("a", tabs.active.title)
    }

    @Test
    fun `home tabs are not remembered`() {
        val tabs = threeTabs()
        tabs.newTab() // still on the home overlay
        tabs.closeTab(3)
        assertFalse(tabs.canReopenClosedTab)
    }

    @Test
    fun `the stack is capped and can be forgotten`() {
        val tabs = TabsState(homepage = HOME_URL)
        repeat(TabsState.MAX_CLOSED_TABS + 5) { i ->
            tabs.newTab().visit("p$i")
            tabs.closeTab(tabs.activeIndex)
        }
        var reopened = 0
        while (tabs.reopenClosedTab() != null) reopened++
        assertEquals(TabsState.MAX_CLOSED_TABS, reopened)
        // Oldest dropped first: the last one back is p5, not p0.
        assertEquals("p5", tabs.active.title)

        tabs.tabs[0].visit("x")
        tabs.closeTab(0)
        tabs.forgetClosedTabs()
        assertFalse(tabs.canReopenClosedTab)
    }
}
