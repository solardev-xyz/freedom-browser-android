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
    fun `undoing the close of the last tab replaces the blank tab put in its place`() {
        val tabs = TabsState(homepage = HOME_URL)
        tabs.tabs[0].visit("a")
        tabs.closeTab(0)
        assertEquals(listOf(""), tabs.titles) // fresh home tab
        tabs.reopenClosedTab()
        assertEquals(listOf("a"), tabs.titles)
        assertEquals("a", tabs.active.title)
    }

    @Test
    fun `the replacement tab is kept once it has been used`() {
        val tabs = TabsState(homepage = HOME_URL)
        tabs.tabs[0].visit("a")
        tabs.closeTab(0)
        tabs.tabs[0].visit("b")
        tabs.reopenClosedTab()
        assertEquals(listOf("a", "b"), tabs.titles)

        // Only the replacement of *that* close: an ordinary blank tab
        // opened later stays.
        val other = TabsState(homepage = HOME_URL)
        other.tabs[0].visit("a")
        other.newTab()
        other.closeTab(0)
        other.reopenClosedTab()
        assertEquals(listOf("a", ""), other.titles)
    }

    @Test
    fun `home tabs are not remembered`() {
        val tabs = threeTabs()
        tabs.newTab() // still on the home overlay
        tabs.closeTab(3)
        assertFalse(tabs.canReopenClosedTab)
    }

    @Test
    fun `a tab closed before its first page committed comes back loading it`() {
        val tabs = threeTabs()
        val pending = tabs.newTab()
        pending.addressBarText = "https://d.example/" // submitted, not committed
        tabs.closeTab(3)
        assertTrue(tabs.canReopenClosedTab)
        val reopened = tabs.reopenClosedTab()!!
        assertEquals("https://d.example/", reopened.addressBarText)
        assertEquals("https://d.example/", reopened.pendingRestore?.fallbackUrl)
        assertEquals("https://d.example/", reopened.pendingRestore?.resubmitUrl)
    }

    @Test
    fun `a first load the user stopped comes back without refetching`() {
        val tabs = threeTabs()
        val stopped = tabs.newTab()
        stopped.addressBarText = "https://d.example/" // submitted…
        stopped.stopProgress() // …then stopped before it committed
        tabs.closeTab(3)
        val restore = tabs.reopenClosedTab()!!.pendingRestore!!
        assertEquals("https://d.example/", restore.resubmitUrl)
        assertFalse(restore.submit)
        val after = restore.afterBlank(restored = true, currentEntryUrl = ABOUT_BLANK)!!
        assertEquals("https://d.example/", after.address)
        assertFalse(after.submit)

        // A stopped load on top of a committed page still restores the
        // page (and refetches it if the saved state can't be restored).
        tabs.switchTo(1)
        tabs.active.stopProgress()
        tabs.closeTab(1)
        val committed = tabs.reopenClosedTab()!!.pendingRestore!!
        assertTrue(committed.submit)
        assertNull(committed.afterBlank(restored = true, currentEntryUrl = "https://b.example/"))
        assertTrue(committed.afterBlank(restored = false, currentEntryUrl = ABOUT_BLANK)!!.submit)
    }

    @Test
    fun `a pending address is only armed while the WebView is on the blank entry`() {
        val restore = BrowserState.PendingRestore(
            webViewState = null,
            fallbackUrl = "https://d.example/",
            resubmitUrl = "https://d.example/",
        )
        assertTrue(restore.afterBlank(restored = true, currentEntryUrl = ABOUT_BLANK)!!.submit)
        assertNotNull(restore.afterBlank(restored = true, currentEntryUrl = null))
        // Restored onto a real page (or an error page): nothing left
        // armed to fire on a later trip Home.
        assertNull(restore.afterBlank(restored = true, currentEntryUrl = "https://d.example/"))
        assertNull(
            BrowserState.PendingRestore(null, fallbackUrl = "https://d.example/")
                .afterBlank(restored = true, currentEntryUrl = ABOUT_BLANK),
        )
    }

    @Test
    fun `reopening an unrelated tab keeps the last-tab placeholder`() {
        val tabs = TabsState(homepage = HOME_URL)
        tabs.tabs[0].visit("a")
        tabs.closeTab(0) // [P]
        tabs.newTab().visit("n")
        tabs.closeTab(1) // close N, P untouched
        tabs.reopenClosedTab() // undoes N, not A
        assertEquals(listOf("", "n"), tabs.titles)
        tabs.reopenClosedTab() // undoes A: P goes now
        assertEquals(listOf("a", "n"), tabs.titles)
    }

    @Test
    fun `a tab sent home after browsing is remembered for its history`() {
        val tabs = threeTabs()
        tabs.switchTo(1)
        tabs.active.apply {
            url = ""
            title = ""
            addressBarText = ""
            canGoBack = true
        }
        tabs.closeTab(1)
        assertTrue(tabs.canReopenClosedTab)
        val reopened = tabs.reopenClosedTab()!!
        assertTrue(reopened.isHome)
        assertNotNull(reopened.pendingRestore)
        // Nothing to submit on top: the restored list ends on home.
        assertEquals("", reopened.pendingRestore?.resubmitUrl)
    }

    @Test
    fun `a committed tab is restored without a resubmit`() {
        val tabs = threeTabs()
        tabs.closeTab(1)
        assertEquals("", tabs.reopenClosedTab()?.pendingRestore?.resubmitUrl)
    }

    @Test
    fun `move actions offer only moves that go somewhere`() {
        assertEquals(emptyList<Pair<String, Int>>(), tabMoveTargets(0, 1))
        assertEquals(listOf("Move tab later" to 1), tabMoveTargets(0, 2))
        assertEquals(
            listOf("Move tab later" to 1, "Move tab to end" to 3),
            tabMoveTargets(0, 4),
        )
        assertEquals(
            listOf(
                "Move tab earlier" to 1,
                "Move tab to start" to 0,
                "Move tab later" to 3,
            ),
            tabMoveTargets(2, 4),
        )
        assertEquals(
            listOf("Move tab earlier" to 2, "Move tab to start" to 0),
            tabMoveTargets(3, 4),
        )
        assertEquals(emptyList<Pair<String, Int>>(), tabMoveTargets(4, 4))
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
