package baby.freedom.mobile.browser

import baby.freedom.mobile.R
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

    // Closing a background tab from the switcher used to make whatever
    // tab slid into the closed slot active (post-merge sweep, #181).
    @Test
    fun `closing a background tab keeps the active tab active`() {
        val tabs = threeTabs()
        tabs.switchTo(2) // c
        tabs.closeTab(0) // a, before the active tab
        assertEquals(listOf("b", "c"), tabs.titles)
        assertEquals("c", tabs.active.title)

        tabs.newTab().visit("d") // [b, c, d], d active
        tabs.switchTo(0) // b
        tabs.closeTab(2) // d, after the active tab
        assertEquals(listOf("b", "c"), tabs.titles)
        assertEquals("b", tabs.active.title)
    }

    @Test
    fun `closing the active tab activates its right neighbour, or the new last tab`() {
        val tabs = threeTabs()
        tabs.switchTo(1) // b
        tabs.closeTab(1)
        assertEquals("c", tabs.active.title)
        tabs.closeTab(1) // c, the last one
        assertEquals("a", tabs.active.title)
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

    @Test
    fun `the stack cap counts tabs, not closes, and keeps the newest close whole`() {
        val tabs = TabsState(homepage = HOME_URL)
        fun bulk(prefix: String, n: Int) {
            repeat(n) { i -> tabs.newTab().visit("$prefix$i") }
            tabs.closeAllTabs()
        }
        // 3 × 8 = 24 tabs > 20: the oldest close goes as a whole.
        bulk("a", 8)
        bulk("b", 8)
        bulk("c", 8)
        assertEquals("c7", tabs.reopenClosedTab()?.title)
        assertEquals(8, tabs.tabs.size)
        tabs.closeAllTabs()
        tabs.reopenClosedTab() // the c tabs again, from their own entry
        assertEquals("b7", tabs.reopenClosedTab()?.title)
        assertNull(tabs.reopenClosedTab())

        // One close of more tabs than the cap is kept whole, alone.
        tabs.closeAllTabs()
        bulk("d", TabsState.MAX_CLOSED_TABS + 5)
        val back = tabs.reopenClosedTab()
        assertEquals("d${TabsState.MAX_CLOSED_TABS + 4}", back?.title)
        assertEquals(TabsState.MAX_CLOSED_TABS + 5, tabs.tabs.size)
        assertNull(tabs.reopenClosedTab())
    }

    @Test
    fun `a popup the page closes itself is not offered for reopen`() {
        val tabs = threeTabs()
        val popup = tabs.adoptPopup(tabs.active)
        popup.visit("login")
        tabs.closePopup(popup)
        assertFalse(tabs.canReopenClosedTab)
    }

    @Test
    fun `a popup the user closes reopens as an ordinary tab`() {
        val tabs = threeTabs()
        val popup = tabs.adoptPopup(tabs.active)
        popup.visit("login")
        tabs.closeTab(tabs.tabs.indexOf(popup))
        val reopened = tabs.reopenClosedTab()!!
        assertEquals("https://login.example/", reopened.url)
        assertNull(reopened.openerId)
        assertSame(reopened, tabs.tabs[1])
    }

    @Test
    fun `a popup nothing has loaded into yet is skipped like an empty tab`() {
        val tabs = threeTabs()
        val popup = tabs.adoptPopup(tabs.active)
        tabs.closeTab(tabs.tabs.indexOf(popup))
        assertFalse(tabs.canReopenClosedTab)
    }

    @Test
    fun `a popup closed before its page committed comes back loading that address`() {
        val tabs = threeTabs()
        val popup = tabs.adoptPopup(tabs.active)
        popup.addressBarText = "https://login.example/"
        tabs.closeTab(tabs.tabs.indexOf(popup))
        val reopened = tabs.reopenClosedTab()!!
        assertEquals("", reopened.url)
        val restore = reopened.pendingRestore!!
        assertEquals("https://login.example/", restore.resubmitUrl)
        assertEquals("https://login.example/", restore.fallbackUrl)
    }

    @Test
    fun `a closed private tab is not remembered for reopening`() {
        val tabs = threeTabs()
        tabs.newTab(private = true).visit("secret")
        assertTrue(tabs.hasPrivateTabs)
        tabs.closeTab(tabs.activeIndex)
        assertFalse(tabs.hasPrivateTabs)
        assertFalse(tabs.canReopenClosedTab)
        // A normal tab still is.
        tabs.closeTab(0)
        assertEquals("a", tabs.reopenClosedTab()?.title)
        assertNull(tabs.reopenClosedTab())
    }

    @Test
    fun `a private tab's popup is private, a normal tab's isn't`() {
        val tabs = threeTabs()
        assertFalse(tabs.adoptPopup(opener = tabs.tabs[0]).private)
        val opener = tabs.newTab(private = true)
        assertTrue(tabs.adoptPopup(opener = opener).private)
    }

    @Test
    fun `the last tab closing as a private one leaves a normal blank tab`() {
        val tabs = TabsState(homepage = HOME_URL)
        tabs.newTab(private = true).visit("secret")
        tabs.closeTab(0)
        tabs.closeTab(0)
        assertEquals(1, tabs.tabs.size)
        assertFalse(tabs.active.private)
    }

    // Close all tabs / Close other tabs (#320).

    @Test
    fun `close all leaves one blank tab and one undo brings every tab back in order`() {
        val tabs = threeTabs()
        tabs.switchTo(1) // b
        val closed = tabs.closeAllTabs()
        assertEquals(3, closed.count)
        assertEquals(listOf(""), tabs.titles)
        assertFalse(tabs.active.private)
        assertTrue(tabs.reopenClosed(closed.undo!!))
        // The placeholder went, and the tab that was active is again.
        assertEquals(listOf("a", "b", "c"), tabs.titles)
        assertEquals("b", tabs.active.title)
        // Each rebuilt from its snapshot, as a single reopen is.
        assertTrue(tabs.tabs.all { it.pendingRestore != null })
        assertFalse(tabs.canReopenClosedTab)
        // Done once: a second Undo does nothing.
        assertFalse(tabs.reopenClosed(closed.undo!!))
        assertEquals(3, tabs.tabs.size)
    }

    @Test
    fun `a bulk close is one entry of the reopen stack`() {
        val tabs = threeTabs()
        tabs.closeAllTabs()
        assertEquals("a", tabs.reopenClosedTab()?.title)
        assertEquals(listOf("a", "b", "c"), tabs.titles)
        assertEquals("a", tabs.active.title)
        assertNull(tabs.reopenClosedTab())
    }

    @Test
    fun `undo after the placeholder was used keeps it`() {
        val tabs = threeTabs()
        val closed = tabs.closeAllTabs()
        tabs.active.visit("new")
        tabs.reopenClosed(closed.undo!!)
        assertEquals(listOf("a", "b", "c", "new"), tabs.titles)
        assertEquals("a", tabs.active.title)
    }

    @Test
    fun `undo of a close that was reopened or forgotten does nothing`() {
        val tabs = threeTabs()
        val closed = tabs.closeAllTabs()
        tabs.forgetClosedTabs()
        assertFalse(tabs.reopenClosed(closed.undo!!))
        assertEquals(listOf(""), tabs.titles)
    }

    @Test
    fun `close others keeps that tab active and undo restores the order and the active tab`() {
        val tabs = threeTabs()
        tabs.newTab().visit("d")
        tabs.switchTo(3) // d
        val keep = tabs.tabs[1] // b
        val closed = tabs.closeOtherTabs(keep)
        assertEquals(3, closed.count)
        assertEquals(listOf("b"), tabs.titles)
        assertSame(keep, tabs.active)
        tabs.reopenClosed(closed.undo!!)
        assertEquals(listOf("a", "b", "c", "d"), tabs.titles)
        assertSame(keep, tabs.tabs[1])
        assertEquals("d", tabs.active.title)
    }

    @Test
    fun `close others of the only tab closes nothing`() {
        val tabs = TabsState(homepage = HOME_URL)
        tabs.tabs[0].visit("a")
        val closed = tabs.closeOtherTabs(tabs.active)
        assertEquals(0, closed.count)
        assertNull(closed.undo)
        assertFalse(tabs.canReopenClosedTab)
    }

    @Test
    fun `closed private tabs are not brought back by undo`() {
        val tabs = threeTabs()
        tabs.newTab(private = true).visit("secret")
        tabs.moveTab(3, 1) // [a, secret, b, c]
        tabs.newTab(private = true).visit("hidden")
        tabs.switchTo(0)
        val closed = tabs.closeAllTabs()
        assertEquals(5, closed.count)
        assertFalse(tabs.hasPrivateTabs)
        tabs.reopenClosed(closed.undo!!)
        assertEquals(listOf("a", "b", "c"), tabs.titles)
        assertFalse(tabs.hasPrivateTabs)
        assertEquals("a", tabs.active.title)
    }

    @Test
    fun `closing every tab but a normal one ends the private group`() {
        val tabs = threeTabs()
        tabs.newTab(private = true).visit("secret")
        val closed = tabs.closeOtherTabs(tabs.tabs[2]) // c
        assertFalse(tabs.hasPrivateTabs)
        assertEquals("c", tabs.active.title)
        tabs.reopenClosed(closed.undo!!)
        assertEquals(listOf("a", "b", "c"), tabs.titles)
        // The private tab was active; it's gone, so the kept one stays.
        assertEquals("c", tabs.active.title)
    }

    @Test
    fun `close private tabs has no undo and keeps the normal tabs`() {
        val tabs = threeTabs()
        tabs.newTab(private = true).visit("p1")
        tabs.switchTo(1) // b
        tabs.newTab(private = true).visit("p2") // active
        val closed = tabs.closePrivateTabs()
        assertEquals(2, closed.count)
        assertNull(closed.undo)
        assertFalse(tabs.hasPrivateTabs)
        assertFalse(tabs.canReopenClosedTab)
        assertEquals(listOf("a", "b", "c"), tabs.titles)
        // p2 was last: the new last tab takes over.
        assertEquals("c", tabs.active.title)
    }

    @Test
    fun `closing only private tabs of an all-private list leaves a normal blank tab`() {
        val tabs = TabsState(homepage = HOME_URL)
        tabs.newTab(private = true).visit("p")
        tabs.closeTab(0) // the blank first tab
        val closed = tabs.closePrivateTabs()
        assertEquals(1, closed.count)
        assertNull(closed.undo)
        assertEquals(1, tabs.tabs.size)
        assertFalse(tabs.active.private)
    }

    @Test
    fun `a bulk close doesn't keep empty tabs`() {
        val tabs = threeTabs()
        tabs.newTab() // an untouched home tab
        val closed = tabs.closeAllTabs()
        assertEquals(4, closed.count)
        assertEquals(3, closed.undo!!.tabs.size)
    }

    @Test
    fun `closing active and earlier tabs moves to the tab that slid into its place`() {
        val tabs = threeTabs()
        tabs.newTab().visit("d")
        tabs.newTab(private = true).visit("p0")
        tabs.moveTab(4, 0) // [p0, a, b, c, d]
        tabs.newTab(private = true).visit("p2")
        tabs.moveTab(5, 2) // [p0, a, p2, b, c, d]
        tabs.switchTo(2) // p2
        tabs.closePrivateTabs()
        assertEquals(listOf("a", "b", "c", "d"), tabs.titles)
        assertEquals("b", tabs.active.title)
    }

    @Test
    fun `undo of close others puts the kept tabs back on the same side of the kept tab`() {
        for (private in listOf(false, true)) {
            val tabs = threeTabs() // [a, b, c]
            val gap = tabs.newTab(private = private) // untouched, or private
            if (private) gap.visit("secret")
            tabs.moveTab(3, 1) // [a, gap, b, c]
            tabs.tabs[3].visit("k") // c becomes k
            val keep = tabs.tabs[3]
            val closed = tabs.closeOtherTabs(keep)
            assertEquals(listOf("k"), tabs.titles)
            tabs.reopenClosed(closed.undo!!)
            assertEquals(listOf("a", "b", "k"), tabs.titles)
            assertSame(keep, tabs.active)
        }
    }

    @Test
    fun `undo of close others that kept one tab leaves the kept tab active`() {
        val tabs = TabsState(homepage = HOME_URL)
        tabs.tabs[0].visit("a")
        tabs.newTab().visit("k")
        val keep = tabs.tabs[1]
        tabs.switchTo(1)
        val closed = tabs.closeOtherTabs(keep)
        tabs.reopenClosed(closed.undo!!)
        assertEquals(listOf("a", "k"), tabs.titles)
        assertSame(keep, tabs.active)
        // A single closed tab still comes back active.
        tabs.closeTab(0)
        tabs.reopenClosedTab()
        assertEquals("a", tabs.active.title)
    }

    @Test
    fun `a close made while an undo is offered doesn't drop that undo`() {
        val tabs = TabsState(homepage = HOME_URL)
        tabs.tabs[0].visit("k")
        repeat(TabsState.MAX_CLOSED_TABS + 1) { i -> tabs.newTab().visit("t$i") }
        val keep = tabs.tabs[0]
        val closed = tabs.closeOtherTabs(keep)
        assertEquals(TabsState.MAX_CLOSED_TABS + 1, closed.undo!!.tabs.size)
        tabs.newTab().visit("x")
        tabs.closeTab(tabs.tabs.lastIndex)
        assertTrue(tabs.reopenClosed(closed.undo!!))
        assertEquals(TabsState.MAX_CLOSED_TABS + 2, tabs.tabs.size)
        assertEquals("x", tabs.reopenClosedTab()?.title)

        // Once its notice is gone, the cap may drop it again.
        val again = tabs.closeOtherTabs(keep)
        tabs.undoWithdrawn(again.undo!!)
        tabs.newTab().visit("y")
        tabs.closeTab(tabs.tabs.lastIndex)
        assertFalse(tabs.reopenClosed(again.undo!!))
    }

    // A single close's Undo, and the switcher's panes (#418).

    @Test
    fun `closing one tab from the switcher offers an undo that puts it back in place`() {
        val tabs = threeTabs()
        tabs.switchTo(2) // c
        val closed = tabs.closeTab(1, offerUndo = true) // b, not the active tab
        assertTrue(closed.single)
        assertEquals(1, closed.count)
        assertEquals(listOf("a", "c"), tabs.titles)
        assertTrue(tabs.reopenClosed(closed.undo!!))
        assertEquals(listOf("a", "b", "c"), tabs.titles)
        // An Undo puts things back: the tab on screen stays on screen.
        assertEquals("c", tabs.active.title)
        assertFalse(tabs.canReopenClosedTab)
        // Done once.
        assertFalse(tabs.reopenClosed(closed.undo!!))
    }

    @Test
    fun `undoing the close of the active tab makes it active again`() {
        val tabs = threeTabs()
        tabs.switchTo(1) // b
        val closed = tabs.closeTab(1, offerUndo = true)
        assertEquals("c", tabs.active.title)
        tabs.reopenClosed(closed.undo!!)
        assertEquals(listOf("a", "b", "c"), tabs.titles)
        assertEquals("b", tabs.active.title)
    }

    @Test
    fun `undoing the close of the last tab drops its untouched placeholder`() {
        val tabs = TabsState(homepage = HOME_URL)
        tabs.tabs[0].visit("only")
        val closed = tabs.closeTab(0, offerUndo = true)
        assertEquals(listOf(""), tabs.titles)
        tabs.reopenClosed(closed.undo!!)
        assertEquals(listOf("only"), tabs.titles)
        assertEquals("only", tabs.active.title)
    }

    @Test
    fun `Reopen of a single close still brings the tab to the front`() {
        val tabs = threeTabs()
        tabs.closeTab(2, offerUndo = true) // c
        assertEquals("a", tabs.active.title)
        assertEquals("c", tabs.reopenClosedTab()?.title)
        assertEquals("c", tabs.active.title)
    }

    @Test
    fun `closing a private or an empty tab has no undo`() {
        val tabs = threeTabs()
        tabs.newTab(private = true).visit("secret")
        val private = tabs.closeTab(tabs.tabs.lastIndex, offerUndo = true)
        assertTrue(private.single)
        assertEquals(1, private.count)
        assertNull(private.undo)
        tabs.newTab()
        val empty = tabs.closeTab(tabs.tabs.lastIndex, offerUndo = true)
        assertNull(empty.undo)
        assertFalse(tabs.canReopenClosedTab)
    }

    @Test
    fun `a single close's undo is held while its notice is up and released after`() {
        val tabs = threeTabs()
        val closed = tabs.closeTab(0, offerUndo = true)
        // More closes than the stack keeps: the offered one stays.
        repeat(TabsState.MAX_CLOSED_TABS + 1) { i ->
            tabs.newTab().visit("t$i")
            tabs.closeTab(tabs.tabs.lastIndex)
        }
        assertTrue(tabs.isOnReopenStack(closed.undo!!))
        tabs.undoWithdrawn(closed.undo!!)
        // Once its notice is gone, the cap may drop it again.
        tabs.newTab().visit("last")
        tabs.closeTab(tabs.tabs.lastIndex)
        assertFalse(tabs.isOnReopenStack(closed.undo!!))
    }

    @Test
    fun `a close without a notice is not held past the cap`() {
        val tabs = threeTabs()
        val closed = tabs.closeTab(0) // Ctrl+W: Reopen only
        repeat(TabsState.MAX_CLOSED_TABS) { i ->
            tabs.newTab().visit("t$i")
            tabs.closeTab(tabs.tabs.lastIndex)
        }
        assertFalse(tabs.isOnReopenStack(closed.undo!!))
    }

    @Test
    fun `the Tabs pane's close all leaves the private tabs`() {
        val tabs = threeTabs()
        tabs.newTab(private = true).visit("p1")
        tabs.switchTo(1) // b
        val closed = tabs.closeRegularTabs()
        assertEquals(3, closed.count)
        // A blank normal tab takes their place: the active tab doesn't
        // turn into the private one.
        assertEquals(listOf("", "p1"), tabs.titles)
        assertFalse(tabs.active.private)
        assertTrue(tabs.reopenClosed(closed.undo!!))
        assertEquals(listOf("a", "b", "c", "p1"), tabs.titles)
        assertEquals("b", tabs.active.title)
    }

    @Test
    fun `the Tabs pane's close all with no private tab is close all`() {
        val tabs = threeTabs()
        tabs.closeRegularTabs()
        assertEquals(listOf(""), tabs.titles)
        assertFalse(tabs.active.private)
    }

    @Test
    fun `close others from a pane closes only that pane's tabs and activates the kept one`() {
        val tabs = threeTabs()
        tabs.newTab(private = true).visit("p1")
        tabs.newTab(private = true).visit("p2")
        val keep = tabs.tabs[1] // b
        val closed = tabs.closeOtherTabsOfItsKind(keep)
        assertEquals(2, closed.count)
        assertEquals(listOf("b", "p1", "p2"), tabs.titles)
        assertEquals("b", tabs.active.title)

        val privateKeep = tabs.tabs[2] // p2
        val closedPrivate = tabs.closeOtherTabsOfItsKind(privateKeep)
        assertEquals(1, closedPrivate.count)
        assertNull(closedPrivate.undo)
        assertEquals(listOf("b", "p2"), tabs.titles)
        assertEquals("p2", tabs.active.title)
    }

    @Test
    fun `panes split the tabs by kind in their order`() {
        val tabs = threeTabs()
        tabs.newTab(private = true).visit("p1")
        tabs.moveTab(3, 1)
        assertEquals(listOf("a", "b", "c"), paneTabs(tabs.tabs, privatePane = false).map { it.title })
        assertEquals(listOf("p1"), paneTabs(tabs.tabs, privatePane = true).map { it.title })
        assertTrue(switcherHasPanes(privateTabsOffered = true, anyPrivate = false))
        assertTrue(switcherHasPanes(privateTabsOffered = false, anyPrivate = true))
        assertFalse(switcherHasPanes(privateTabsOffered = false, anyPrivate = false))
    }

    @Test
    fun `the Tabs pane's close item says Close all only when it closes every tab`() {
        val tabs = threeTabs()
        assertEquals(R.string.browser_tabs_close_all, closeTabsPaneLabel(anyPrivate = tabs.hasPrivateTabs))
        tabs.newTab(private = true).visit("p1")
        assertEquals(R.string.browser_tabs_close_normal, closeTabsPaneLabel(anyPrivate = tabs.hasPrivateTabs))
        tabs.closeRegularTabs()
        // The item names what happened: the private tab is still open.
        assertTrue(tabs.hasPrivateTabs)
    }

    @Test
    fun `closing a private pane's active tab passes to another private tab`() {
        val tabs = TabsState(homepage = HOME_URL)
        tabs.active.visit("a")
        tabs.newTab(private = true).visit("p1")
        tabs.newTab().visit("b")
        tabs.newTab(private = true).visit("p2")
        tabs.switchTo(1) // p1
        tabs.closeTab(1, offerUndo = true)
        assertEquals("p2", tabs.active.title)
        assertTrue(tabs.active.private)
        // Nearest before, when none follows.
        tabs.newTab(private = true).visit("p3")
        tabs.closeTab(tabs.tabs.lastIndex, offerUndo = true)
        assertEquals("p2", tabs.active.title)
    }

    @Test
    fun `closing a tabs pane's active tab passes to another normal tab`() {
        val tabs = TabsState(homepage = HOME_URL)
        tabs.active.visit("a")
        tabs.newTab().visit("b")
        tabs.newTab(private = true).visit("p")
        tabs.switchTo(1) // b
        tabs.closeTab(1, offerUndo = true)
        assertEquals("a", tabs.active.title)
        assertFalse(tabs.active.private)
    }

    @Test
    fun `closing the last normal tab with private tabs open leaves a blank normal tab`() {
        val tabs = TabsState(homepage = HOME_URL)
        tabs.active.visit("a")
        tabs.newTab(private = true).visit("p")
        tabs.switchTo(0)
        val closed = tabs.closeTab(0, offerUndo = true)
        assertEquals(listOf("", "p"), tabs.titles)
        assertFalse(tabs.active.private)
        // Undo takes the blank tab's place again.
        assertTrue(tabs.reopenClosed(closed.undo!!))
        assertEquals(listOf("a", "p"), tabs.titles)
        assertEquals("a", tabs.active.title)
    }

    @Test
    fun `closing the last private tab hands over to a normal tab`() {
        val tabs = TabsState(homepage = HOME_URL)
        tabs.active.visit("a")
        tabs.newTab(private = true).visit("p")
        tabs.closeTab(1)
        assertEquals(listOf("a"), tabs.titles)
        assertEquals("a", tabs.active.title)
    }
}
