package baby.freedom.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tab switcher's *Recently closed* list (#546) on [TabsState]. */
class TabsRecentlyClosedTest {

    /** Four tabs on pages a, b, c, d; the first one active. */
    private fun fourTabs(): TabsState {
        val tabs = TabsState(homepage = HOME_URL)
        tabs.tabs[0].visit("a")
        for (page in listOf("b", "c", "d")) tabs.newTab().visit(page)
        tabs.switchTo(0)
        return tabs
    }

    private fun BrowserState.visit(page: String) {
        url = "https://$page.example/"
        title = page
        addressBarText = url
    }

    private val TabsState.titles get() = tabs.map { it.title }
    private val TabsState.listed get() = recentlyClosed.map { g -> g.tabs.map { it.title } }

    @Test
    fun `the list holds every close, most recent first`() {
        val tabs = fourTabs()
        tabs.closeTab(1) // b
        tabs.closeTab(1) // c
        tabs.closeTab(1) // d
        assertEquals(listOf(listOf("d"), listOf("c"), listOf("b")), tabs.listed)
        assertTrue(fourTabs().recentlyClosed.isEmpty())
    }

    @Test
    fun `reopening an older entry brings that one back, active, where it was`() {
        val tabs = fourTabs()
        tabs.closeTab(1) // b
        tabs.closeTab(1) // c
        tabs.closeTab(1) // d
        val c = tabs.recentlyClosed[1]
        val reopened = tabs.reopenClosedTab(c)
        assertEquals("c", reopened?.title)
        assertSame(reopened, tabs.active)
        assertEquals(listOf("a", "c"), tabs.titles)
        // The others stay listed, in their order.
        assertEquals(listOf(listOf("d"), listOf("b")), tabs.listed)
        assertEquals("d", tabs.reopenClosedTab()?.title)
        assertEquals("b", tabs.reopenClosedTab()?.title)
        // Each went back to the place it closed from (the second tab,
        // every time), among the tabs open now.
        assertEquals(listOf("a", "b", "d", "c"), tabs.titles)
    }

    @Test
    fun `a bulk close is one entry and comes back whole`() {
        val tabs = fourTabs()
        tabs.switchTo(3) // d
        tabs.closeTab(3) // d
        tabs.switchTo(1) // b
        tabs.closeOtherTabs(tabs.active) // a and c
        assertEquals(listOf(listOf("a", "c"), listOf("d")), tabs.listed)
        tabs.reopenClosedTab(tabs.recentlyClosed[0])
        assertEquals(listOf("a", "b", "c"), tabs.titles)
        // A bulk close comes back as Reopen has it: b stays on screen.
        assertEquals("b", tabs.active.title)
        assertEquals(listOf(listOf("d")), tabs.listed)
    }

    @Test
    fun `an entry no longer on the stack reopens nothing`() {
        val tabs = fourTabs()
        tabs.closeTab(1) // b
        tabs.closeTab(1) // c
        val c = tabs.recentlyClosed[0]
        assertEquals("c", tabs.reopenClosedTab()?.title) // Ctrl+Shift+T took it
        assertNull(tabs.reopenClosedTab(c))
        assertEquals(listOf("a", "c", "d"), tabs.titles)
        assertEquals(listOf(listOf("b")), tabs.listed)

        val b = tabs.recentlyClosed[0]
        tabs.forgetClosedTabs()
        assertNull(tabs.reopenClosedTab(b))
        assertEquals(listOf("a", "c", "d"), tabs.titles)
    }

    @Test
    fun `an entry reopened from the list has no Undo left`() {
        val tabs = fourTabs()
        val closed = tabs.closeTab(1, offerUndo = true) // b, its notice up
        tabs.reopenClosedTab(tabs.recentlyClosed.single())
        assertFalse(tabs.isOnReopenStack(closed.undo!!))
        assertFalse(tabs.reopenClosed(closed.undo!!))
        assertEquals(listOf("a", "b", "c", "d"), tabs.titles)
    }

    @Test
    fun `private tabs are never listed`() {
        val tabs = fourTabs()
        tabs.newTab(private = true).visit("p1")
        tabs.closeTab(tabs.activeIndex)
        assertTrue(tabs.recentlyClosed.isEmpty())

        tabs.newTab(private = true).visit("p2")
        tabs.newTab(private = true).visit("p3")
        tabs.closePrivateTabs()
        assertTrue(tabs.recentlyClosed.isEmpty())

        tabs.newTab(private = true).visit("p4")
        tabs.closeAllTabs()
        assertEquals(listOf(listOf("a", "b", "c", "d")), tabs.listed)
    }

    @Test
    fun `a single tab's row is its title over its address`() {
        val tabs = fourTabs()
        tabs.closeTab(1)
        assertEquals(
            RecentlyClosedRow("b", "https://b.example/"),
            recentlyClosedRow(tabs.recentlyClosed.single()),
        )
    }

    @Test
    fun `a row shows the address the bar showed, not the virtual origin`() {
        val tabs = fourTabs()
        tabs.tabs[1].apply {
            url = "https://abc.eth.freedom.local/page"
            addressBarText = "abc.eth/page"
            title = ""
        }
        tabs.closeTab(1)
        assertEquals(RecentlyClosedRow("abc.eth/page", ""), recentlyClosedRow(tabs.recentlyClosed.single()))
    }

    @Test
    fun `a tab closed before its page committed is named by its address`() {
        val tabs = fourTabs()
        tabs.tabs[1].apply {
            url = ""
            title = ""
            addressBarText = "https://slow.example/"
        }
        tabs.closeTab(1)
        assertEquals(
            RecentlyClosedRow("https://slow.example/", ""),
            recentlyClosedRow(tabs.recentlyClosed.single()),
        )
    }

    @Test
    fun `a tab sent home after browsing is named as its card names it`() {
        val tabs = fourTabs()
        tabs.tabs[1].apply {
            url = ""
            title = ""
            addressBarText = ""
            canGoBack = true
        }
        tabs.closeTab(1)
        assertEquals(RecentlyClosedRow("New tab", ""), recentlyClosedRow(tabs.recentlyClosed.single()))
    }

    @Test
    fun `a bulk close's row counts its tabs and names them`() {
        val tabs = fourTabs()
        tabs.closeOtherTabs(tabs.active)
        assertEquals(RecentlyClosedRow("3 tabs", "b, c, d"), recentlyClosedRow(tabs.recentlyClosed.single()))
    }
}
