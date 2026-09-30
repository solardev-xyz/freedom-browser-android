package baby.freedom.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A tab whose renderer process went away (#260,
 * [TabsState.rendererGone]): the tab stays, parked to be rebuilt from its
 * dead WebView's state, and says why until it's brought back. Destroying
 * and rebuilding the WebView happens in [BrowserWebViewHost] and is
 * exercised on the device.
 */
class TabsRendererGoneTest {

    private fun BrowserState.visit(page: String) {
        url = "https://$page.example/"
        title = page
        addressBarText = url
    }

    /** Tabs on pages a and b; a active. */
    private fun twoTabs(): TabsState {
        val tabs = TabsState(homepage = HOME_URL)
        tabs.tabs[0].visit("a")
        tabs.newTab(activate = false).visit("b")
        return tabs
    }

    @Test
    fun `the tabs stay, with their pages, parked to be rebuilt`() {
        val tabs = twoTabs()
        val before = tabs.tabs.toList()
        val asked = mutableListOf<Long>()
        for (tab in tabs.tabs) tabs.rendererGone(tab, crashed = false) { asked += it.id; null }
        assertEquals(before, tabs.tabs.toList())
        assertEquals(0, tabs.activeIndex)
        assertEquals(before.map { it.id }, asked)
        assertEquals(listOf("a", "b"), tabs.tabs.map { it.title })
        assertEquals(listOf("https://a.example/", "https://b.example/"), tabs.tabs.map { it.addressBarText })
        for (tab in tabs.tabs) {
            assertEquals(tab.url, tab.pendingRestore!!.fallbackUrl)
            assertNotNull(tab.rendererGone)
        }
    }

    @Test
    fun `the tab on screen waits for Reload, a background one reloads when shown`() {
        val tabs = twoTabs()
        val (shown, background) = tabs.tabs
        tabs.rendererGone(shown, crashed = true) { null }
        tabs.rendererGone(background, crashed = false) { null }
        assertFalse(shown.rendererGone!!.reloadWhenShown)
        assertTrue(shown.rendererGone!!.crashed)
        assertTrue(background.rendererGone!!.reloadWhenShown)
        assertFalse(background.rendererGone!!.crashed)
    }

    @Test
    fun `the tab on screen killed for memory while the app was away reloads when shown`() {
        val tabs = twoTabs()
        tabs.rendererGone(tabs.active, crashed = false, appVisible = false) { null }
        assertTrue(tabs.active.rendererGone!!.reloadWhenShown)
    }

    @Test
    fun `the tab on screen crashed while the app was away still waits for Reload`() {
        val tabs = twoTabs()
        tabs.rendererGone(tabs.active, crashed = true, appVisible = false) { null }
        assertFalse(tabs.active.rendererGone!!.reloadWhenShown)
    }

    @Test
    fun `crashed and killed for memory say different things`() {
        val crashed = BrowserState.RendererGone(crashed = true, reloadWhenShown = false)
        val killed = BrowserState.RendererGone(crashed = false, reloadWhenShown = false)
        assertEquals("This page crashed", rendererGoneTitle(crashed))
        assertEquals("This page was closed to free memory", rendererGoneTitle(killed))
    }

    @Test
    fun `recovering clears the state and leaves the restore for the host`() {
        val tabs = twoTabs()
        val tab = tabs.active
        tabs.rendererGone(tab, crashed = true) { null }
        val restore = tab.pendingRestore
        tab.recoverRenderer()
        assertNull(tab.rendererGone)
        assertSame(restore, tab.pendingRestore)
    }

    @Test
    fun `a page mid-load over another gets its address back after the rebuild`() {
        val tabs = twoTabs()
        val tab = tabs.active // on a
        tab.addressBarText = "https://next.example/" // submitted over a, not committed
        tab.progress = 10
        tabs.rendererGone(tab, crashed = false) { null }
        // What only mirrored the dead WebView is gone.
        assertEquals(-1, tab.progress)
        val after = tab.pendingRestore!!.afterBlank(restored = true, currentEntryUrl = "https://a.example/")!!
        assertEquals("https://next.example/", after.address)
        assertTrue(after.overPage)
    }

    @Test
    fun `a stopped first load comes back in the bar without being fetched`() {
        val tabs = TabsState(homepage = HOME_URL)
        val tab = tabs.active
        tab.addressBarText = "https://slow.example/"
        tab.stopProgress()
        tabs.rendererGone(tab, crashed = false) { null }
        val after = tab.pendingRestore!!.afterBlank(restored = false, currentEntryUrl = ABOUT_BLANK)!!
        assertEquals("https://slow.example/", after.address)
        assertFalse(after.submit)
    }

    @Test
    fun `a tab reopened and not yet rebuilt keeps what it was to be rebuilt from`() {
        val tabs = twoTabs()
        val tab = tabs.active
        val earlier = BrowserState.PendingRestore(webViewState = null, fallbackUrl = "https://earlier.example/")
        tab.pendingRestore = earlier
        var asked = false
        tabs.rendererGone(tab, crashed = true) { asked = true; null }
        assertSame(earlier, tab.pendingRestore)
        assertFalse(asked)
    }

    @Test
    fun `fullscreen and the context menu go only with their own tab`() {
        val tabs = twoTabs()
        val (shown, background) = tabs.tabs
        tabs.pageContextMenu = PageContextMenuPin(shown.id, shown.url, shown.navCounter)
            .request(PageContextTarget(linkUrl = null, imageUrl = null, linkText = null))
        tabs.rendererGone(background, crashed = false) { null }
        assertNotNull(tabs.pageContextMenu)
        tabs.rendererGone(shown, crashed = false) { null }
        assertNull(tabs.pageContextMenu)
    }

    @Test
    fun `without history the rebuild keeps the address and how to load it`() {
        val restore = BrowserState.PendingRestore.of(
            url = "",
            address = "https://slow.example/",
            loadStopped = true,
            webViewState = null,
        )
        val bare = restore.withoutHistory()
        assertNull(bare.webViewState)
        assertEquals(restore.fallbackUrl, bare.fallbackUrl)
        assertEquals(restore.resubmitUrl, bare.resubmitUrl)
        assertEquals(restore.submit, bare.submit)
        assertEquals(restore.overPage, bare.overPage)
    }

    @Test
    fun `a gone tab offers no find or print until it is rebuilt`() {
        val tabs = twoTabs()
        val tab = tabs.active
        assertTrue(tab.hasPageToActOn)
        tabs.rendererGone(tab, crashed = false) { null }
        assertFalse(tab.hasPageToActOn)
        tab.recoverRenderer()
        assertTrue(tab.hasPageToActOn)
        tab.navigateHome()
        assertFalse(tab.hasPageToActOn)
    }

    @Test
    fun `a step with nowhere to go is dropped only while a put-back waits`() {
        val none: (Int) -> Boolean = { false }
        val both: (Int) -> Boolean = { true }
        // Rebuilt without history (R2-F1): Back and Forward keep the page.
        assertTrue(stepDroppedForPutBack(HISTORY_BACK_JS, putBackArmed = true, canStep = none))
        assertTrue(stepDroppedForPutBack(HISTORY_FORWARD_JS, putBackArmed = true, canStep = none))
        // A restored history has the entry: the step goes in.
        assertFalse(stepDroppedForPutBack(HISTORY_BACK_JS, putBackArmed = true, canStep = both))
        // Asks about its own direction.
        val backOnly: (Int) -> Boolean = { it == -1 }
        assertFalse(stepDroppedForPutBack(HISTORY_BACK_JS, putBackArmed = true, canStep = backOnly))
        assertTrue(stepDroppedForPutBack(HISTORY_FORWARD_JS, putBackArmed = true, canStep = backOnly))
        // Nothing to put back: handed as ever.
        assertFalse(stepDroppedForPutBack(HISTORY_BACK_JS, putBackArmed = false, canStep = none))
        // Not a step: the user's own navigation supersedes the put-back.
        assertFalse(stepDroppedForPutBack("https://c.example/", putBackArmed = true, canStep = none))
        assertFalse(stepDroppedForPutBack(HOME_URL, putBackArmed = true, canStep = none))
    }
}
