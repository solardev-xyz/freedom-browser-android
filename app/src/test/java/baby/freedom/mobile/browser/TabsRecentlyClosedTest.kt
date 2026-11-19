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
        // Each went back between the neighbours it closed from.
        assertEquals(listOf("a", "b", "c", "d"), tabs.titles)
    }

    @Test
    fun `tabs reopened from the list in any order come back in their old order`() {
        // b then c closed (both from the second place), and c then b
        // (from the third place, then the second), each reopened in
        // either order, as well as a bulk close between them.
        for (closeCFirst in listOf(false, true)) for (reopenOlderFirst in listOf(false, true)) {
            val tabs = fourTabs()
            if (closeCFirst) { tabs.closeTab(2); tabs.closeTab(1) } else { tabs.closeTab(1); tabs.closeTab(1) }
            assertEquals(listOf("a", "d"), tabs.titles)
            val order = if (reopenOlderFirst) tabs.recentlyClosed.asReversed() else tabs.recentlyClosed
            for (entry in order) tabs.reopenClosedTab(entry)
            assertEquals("closeCFirst=$closeCFirst reopenOlderFirst=$reopenOlderFirst", listOf("a", "b", "c", "d"), tabs.titles)
        }
        val tabs = fourTabs()
        tabs.closeTab(1) // b
        tabs.switchTo(1) // c
        tabs.closeOtherTabs(tabs.active) // a and d
        assertEquals(listOf("c"), tabs.titles)
        tabs.reopenClosedTab(tabs.recentlyClosed[1]) // b, before the bulk close
        assertEquals(listOf("b", "c"), tabs.titles)
        tabs.reopenClosedTab()
        assertEquals(listOf("a", "b", "c", "d"), tabs.titles)
    }

    @Test
    fun `an older entry reopened first leaves the later ones their places`() {
        // d, then c, then b closed; d reopened from the list, then the
        // others by Reopen: c's place still counts b, open when c closed.
        val tabs = fourTabs()
        tabs.closeTab(3) // d
        tabs.closeTab(2) // c
        tabs.closeTab(1) // b
        assertEquals("d", tabs.reopenClosedTab(tabs.recentlyClosed[2])?.title)
        assertEquals(listOf("a", "d"), tabs.titles)
        assertEquals("b", tabs.reopenClosedTab()?.title)
        assertEquals("c", tabs.reopenClosedTab()?.title)
        assertEquals(listOf("a", "b", "c", "d"), tabs.titles)
    }

    @Test
    fun `any closes reopened in any order come back in their old order`() {
        for (seed in 0 until 2_000) {
            val random = kotlin.random.Random(seed)
            val tabs = TabsState(homepage = HOME_URL)
            val pages = (0 until 3 + random.nextInt(5)).map { "p$it" }
            tabs.tabs[0].visit(pages[0])
            for (page in pages.drop(1)) tabs.newTab().visit(page)
            // Close some, one or several at a time, never the last tab.
            repeat(1 + random.nextInt(pages.size)) {
                if (tabs.tabs.size < 2) return@repeat
                if (random.nextInt(4) == 0) {
                    tabs.closeOtherTabs(tabs.tabs[random.nextInt(tabs.tabs.size)])
                } else {
                    tabs.closeTab(random.nextInt(tabs.tabs.size))
                }
            }
            // Reopen entries from the list at random, then the rest by Reopen.
            repeat(random.nextInt(tabs.recentlyClosed.size + 1)) {
                tabs.reopenClosedTab(tabs.recentlyClosed[random.nextInt(tabs.recentlyClosed.size)])
            }
            while (tabs.reopenClosedTab() != null) Unit
            assertEquals("seed=$seed", pages, tabs.titles)
        }
    }

    @Test
    fun `an undo of an older close lands where it was too`() {
        val tabs = fourTabs()
        val b = tabs.closeTab(1, offerUndo = true).undo!!
        tabs.closeTab(1) // c, by Ctrl+W
        assertTrue(tabs.reopenClosed(b))
        assertEquals(listOf("a", "b", "d"), tabs.titles)
        tabs.reopenClosedTab()
        assertEquals(listOf("a", "b", "c", "d"), tabs.titles)
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
