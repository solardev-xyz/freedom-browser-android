package baby.freedom.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tabs outliving their WebViews (#183): an Activity relaunch
 * ([TabsState.parkForRelaunch]) and a process killed in the background
 * ([TabsState.saveForProcessDeath] / [TabsState.restoreAfterProcessDeath]).
 * Saving and restoring the WebViews themselves happens in
 * [BrowserWebViewHost] and is exercised on the device.
 */
class TabsRelaunchTest {

    private fun BrowserState.visit(page: String) {
        url = "https://$page.example/"
        title = page
        addressBarText = url
    }

    /** Tabs on pages a, b, c; the second one active. */
    private fun threeTabs(): TabsState {
        val tabs = TabsState(homepage = HOME_URL)
        tabs.tabs[0].visit("a")
        tabs.newTab().visit("b")
        tabs.newTab().visit("c")
        tabs.switchTo(1)
        return tabs
    }

    @Test
    fun `a relaunch keeps every tab, in order, with the same one active`() {
        val tabs = threeTabs()
        val before = tabs.tabs.toList()
        val asked = mutableListOf<Long>()
        tabs.parkForRelaunch { asked += it.id; null }
        assertEquals(before, tabs.tabs.toList())
        assertEquals(1, tabs.activeIndex)
        assertEquals(before.map { it.id }, asked)
        assertEquals(listOf("a", "b", "c"), tabs.tabs.map { it.title })
        // Each is rebuilt from what it was showing.
        for (tab in tabs.tabs) {
            val restore = tab.pendingRestore!!
            assertEquals(tab.url, restore.fallbackUrl)
            assertEquals("", restore.resubmitUrl)
        }
    }

    @Test
    fun `a relaunched tab whose state can't be restored loads its page again`() {
        val tabs = threeTabs()
        tabs.parkForRelaunch { null }
        val after = tabs.active.pendingRestore!!.afterBlank(restored = false, currentEntryUrl = ABOUT_BLANK)!!
        assertEquals("https://b.example/", after.address)
        assertTrue(after.submit)
    }

    @Test
    fun `a relaunch mid-load puts the address back, unless the user had stopped it`() {
        val tabs = threeTabs()
        val loading = tabs.newTab()
        loading.addressBarText = "https://d.example/" // submitted, not committed
        val stopped = tabs.newTab()
        stopped.addressBarText = "https://e.example/"
        stopped.stopProgress()
        tabs.parkForRelaunch { null }

        val resubmit = loading.pendingRestore!!.afterBlank(restored = true, currentEntryUrl = ABOUT_BLANK)!!
        assertEquals("https://d.example/", resubmit.address)
        assertTrue(resubmit.submit)
        val kept = stopped.pendingRestore!!.afterBlank(restored = true, currentEntryUrl = ABOUT_BLANK)!!
        assertEquals("https://e.example/", kept.address)
        assertFalse(kept.submit)
    }

    @Test
    fun `a relaunch drops what only mirrored the destroyed WebView`() {
        val tabs = threeTabs()
        val tab = tabs.active
        tab.progress = 40
        tab.resolving = true
        tab.playingAudio = true
        tab.find.show()
        tabs.parkForRelaunch { null }
        assertEquals(-1, tab.progress)
        assertFalse(tab.resolving)
        assertFalse(tab.playingAudio)
        assertFalse(tab.find.open)
    }

    @Test
    fun `a reopened tab not yet rebuilt keeps what it was to be rebuilt from`() {
        val tabs = threeTabs()
        tabs.closeTab(2)
        val reopened = tabs.reopenClosedTab()!!
        val restore = reopened.pendingRestore
        tabs.parkForRelaunch { null }
        assertSame(restore, reopened.pendingRestore)
    }

    @Test
    fun `a popup's blank document comes back as the home entry`() {
        val tabs = threeTabs()
        val popup = tabs.adoptPopup(opener = tabs.tabs[0])
        tabs.parkForRelaunch { null }
        assertNull(popup.pendingRestore!!.afterBlank(restored = false, currentEntryUrl = ABOUT_BLANK))
    }

    @Test
    fun `the initial load is done once per tab list`() {
        val tabs = threeTabs()
        assertFalse(tabs.initialLoadDone)
        tabs.initialLoadDone = true
        tabs.parkForRelaunch { null }
        assertTrue(tabs.initialLoadDone)
    }

    @Test
    fun `process death keeps the regular tabs and which one was active`() {
        val tabs = threeTabs()
        val saved = tabs.saveForProcessDeath()
        assertEquals(listOf("a", "b", "c"), saved.tabs.map { it.title })
        assertEquals(listOf("https://a.example/", "https://b.example/", "https://c.example/"), saved.tabs.map { it.address })
        assertEquals(1, saved.activeIndex)

        val restored = TabsState(homepage = HOME_URL)
        restored.restoreAfterProcessDeath(saved)
        assertEquals(listOf("a", "b", "c"), restored.tabs.map { it.title })
        assertEquals("b", restored.active.title)
        assertTrue(restored.initialLoadDone)
        // Shown from the first frame, loaded once the WebView's blank
        // entry is up.
        assertFalse(restored.active.isHome)
        val after = restored.active.pendingRestore!!.afterBlank(restored = false, currentEntryUrl = ABOUT_BLANK)!!
        assertEquals("https://b.example/", after.address)
        assertTrue(after.submit)
    }

    @Test
    fun `private tabs don't outlive the process`() {
        val tabs = threeTabs()
        tabs.newTab(private = true).visit("secret") // now active, index 3
        val saved = tabs.saveForProcessDeath()
        assertEquals(listOf("a", "b", "c"), saved.tabs.map { it.title })
        // The regular tab before it comes back active.
        assertEquals(2, saved.activeIndex)

        // A private tab first in the list, and on screen: the first
        // regular tab is active.
        val first = TabsState(homepage = HOME_URL)
        first.tabs[0].visit("a")
        first.newTab(private = true).visit("secret")
        first.moveTab(1, 0)
        first.switchTo(0)
        assertEquals(0, first.saveForProcessDeath().activeIndex)
    }

    @Test
    fun `nothing to restore leaves the fresh tab list alone`() {
        val tabs = TabsState(homepage = HOME_URL)
        tabs.newTab(private = true).visit("secret")
        val saved = tabs.saveForProcessDeath()
        assertEquals(listOf(""), saved.tabs.map { it.title }) // the home tab
        val restored = TabsState(homepage = HOME_URL)
        restored.restoreAfterProcessDeath(TabsState.SavedTabs(emptyList(), 0))
        assertEquals(1, restored.tabs.size)
        assertFalse(restored.initialLoadDone)
    }

    @Test
    fun `a home tab comes back on home`() {
        val tabs = TabsState(homepage = HOME_URL)
        tabs.newTab().visit("a")
        val restored = TabsState(homepage = HOME_URL)
        restored.restoreAfterProcessDeath(tabs.saveForProcessDeath())
        assertTrue(restored.tabs[0].isHome)
        assertNull(restored.tabs[0].pendingRestore!!.afterBlank(restored = false, currentEntryUrl = ABOUT_BLANK))
    }
}
