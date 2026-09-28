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
    fun `a relaunch mid-load over a page puts the load back once that page is restored`() {
        val tabs = threeTabs()
        val tab = tabs.active // on b
        tab.addressBarText = "https://next.example/" // submitted over b, not committed
        tab.progress = 10
        tabs.parkForRelaunch { null }
        val restore = tab.pendingRestore!!
        // Restored onto b: the load goes in once b has finished.
        val after = restore.afterBlank(restored = true, currentEntryUrl = "https://b.example/")!!
        assertEquals("https://next.example/", after.address)
        assertTrue(after.submit)
        assertTrue(after.overPage)
        // Not restored: the in-flight address is what loads.
        assertEquals("https://next.example/", restore.afterBlank(restored = false, currentEntryUrl = ABOUT_BLANK)!!.address)
    }

    private fun armedOverPage(): BrowserState {
        val tabs = threeTabs()
        val tab = tabs.active // on b
        tab.addressBarText = "https://next.example/"
        tab.progress = 10
        tabs.parkForRelaunch { null }
        tab.armAfterRestore(tab.pendingRestore!!.afterBlank(restored = true, currentEntryUrl = "https://b.example/"))
        return tab
    }

    @Test
    fun `the load put back over a restored page goes in when that page commits, redirected or not`() {
        val tab = armedOverPage()
        // b's reload committed under another URL (a 302): still that load.
        val after = tab.afterPageCommitted()!!
        assertEquals("https://next.example/", after.address)
        assertTrue(tab.claimAfterPage(after))
        // Claimed once only.
        assertFalse(tab.claimAfterPage(after))
        assertNull(tab.afterPageCommitted())
    }

    @Test
    fun `the load put back over a restored page isn't waiting on that page's finish`() {
        // Put back at the commit, it is in flight before the page takes
        // input: a POST form the user submits on it (never seen by
        // shouldOverrideUrlLoading) replaces it in the WebView, and the
        // page's finish has nothing left to fire over the result (#185
        // R3-F1).
        val tab = armedOverPage()
        val after = tab.afterPageCommitted()!!
        assertTrue(tab.claimAfterPage(after))
        tab.afterPageFinished()
        assertNull(tab.afterPageCommitted())
        // A finish with nothing committed doesn't drop it either.
        val uncommitted = armedOverPage()
        uncommitted.afterPageFinished()
        assertTrue(uncommitted.claimAfterPage(uncommitted.afterPageCommitted()!!))
    }

    @Test
    fun `a navigation after the restore drops the load it had waiting`() {
        // The user's submit, Home, Back / Forward, Stop before the
        // restored page commits (#185 R2-F1).
        val beforeCommit = armedOverPage()
        beforeCommit.restoreLoadSuperseded()
        assertNull(beforeCommit.afterPageCommitted())
        // Superseded between the commit and its posted submit.
        val afterCommit = armedOverPage()
        val after = afterCommit.afterPageCommitted()!!
        afterCommit.restoreLoadSuperseded()
        assertFalse(afterCommit.claimAfterPage(after))
    }

    @Test
    fun `the load put back over a restored page isn't submitted from home or after a stop`() {
        val home = armedOverPage()
        assertNull(home.takeAfterBlankEntry())
        assertNull(home.afterPageCommitted())
        val stopped = armedOverPage()
        val after = stopped.afterPageCommitted()!!
        stopped.stopProgress()
        assertFalse(stopped.claimAfterPage(after))
    }

    @Test
    fun `a navigation before the blank entry finishes drops the address it had waiting`() {
        val tabs = threeTabs()
        val loading = tabs.newTab()
        loading.addressBarText = "https://d.example/"
        tabs.parkForRelaunch { null }
        loading.armAfterRestore(loading.pendingRestore!!.afterBlank(restored = true, currentEntryUrl = ABOUT_BLANK))
        loading.restoreLoadSuperseded()
        assertNull(loading.takeAfterBlankEntry())
    }

    @Test
    fun `a relaunch over a page with nothing uncommitted doesn't load anything more`() {
        val tabs = threeTabs()
        val idle = tabs.tabs[0]
        val stopped = tabs.tabs[1]
        stopped.addressBarText = "https://next.example/"
        stopped.progress = 10
        stopped.stopProgress()
        val following = tabs.tabs[2] // a link the page follows keeps its address
        following.progress = 30
        val resolving = tabs.newTab().apply { visit("d") }
        resolving.addressBarText = "ens.eth"
        resolving.resolving = true
        tabs.parkForRelaunch { null }
        for (tab in listOf(idle, stopped, following)) {
            assertNull(tab.pendingRestore!!.afterBlank(restored = true, currentEntryUrl = tab.url))
        }
        // A name still being resolved over d is a load in flight too.
        assertEquals(
            "ens.eth",
            resolving.pendingRestore!!.afterBlank(restored = true, currentEntryUrl = "https://d.example/")!!.address,
        )
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
    fun `a restored tab has no url until its page loads again`() {
        val tabs = threeTabs()
        // A dweb page: its fetch target is on a gateway port that dies
        // with the process.
        tabs.active.url = "bzz://site.eth/"
        tabs.active.addressBarText = "bzz://site.eth/"
        val restored = TabsState(homepage = HOME_URL)
        restored.restoreAfterProcessDeath(tabs.saveForProcessDeath())
        assertEquals(listOf("", "", ""), restored.tabs.map { it.url })
        assertEquals("bzz://site.eth/", restored.active.addressBarText)
        assertFalse(restored.active.isHome)
        val after = restored.active.pendingRestore!!.afterBlank(restored = false, currentEntryUrl = ABOUT_BLANK)!!
        assertEquals("bzz://site.eth/", after.address)
        assertTrue(after.submit)
    }

    @Test
    fun `process death leaves out a tab whose address is too long to save`() {
        val tabs = threeTabs()
        val huge = "https://b.example/?" + "x".repeat(600_000) // history.replaceState
        tabs.active.url = huge
        tabs.active.addressBarText = huge
        tabs.tabs[2].title = "t".repeat(100_000)
        val saved = tabs.saveForProcessDeath()
        assertEquals(listOf("https://a.example/", "https://c.example/"), saved.tabs.map { it.address })
        assertEquals(TabsState.MAX_SAVED_TITLE, saved.tabs[1].title.length)
        // b was active: the kept tab before it comes back active.
        assertEquals(0, saved.activeIndex)
    }

    @Test
    fun `process death keeps the saved tabs inside a fixed budget, the active one first`() {
        val tabs = TabsState(homepage = HOME_URL)
        val long = "x".repeat(TabsState.MAX_SAVED_ADDRESS - 40)
        tabs.tabs[0].url = "https://0.example/?$long"
        tabs.tabs[0].addressBarText = tabs.tabs[0].url
        repeat(19) { n ->
            tabs.newTab().apply {
                url = "https://${n + 1}.example/?$long"
                addressBarText = url
            }
        }
        tabs.switchTo(15)
        val saved = tabs.saveForProcessDeath()
        assertTrue(saved.tabs.sumOf { it.address.length + it.title.length } <= TabsState.MAX_SAVED_CHARS)
        assertTrue(saved.tabs.size in 1 until 20)
        assertEquals("https://15.example/?$long", saved.tabs[saved.activeIndex].address)
        // The rest are kept in order from the first.
        assertEquals("https://0.example/?$long", saved.tabs[0].address)
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
