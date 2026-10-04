package baby.freedom.mobile.browser

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The open tabs on disk (#400 item 13): what [TabsStore] writes, what a
 * cold start reads back, that nothing of a private tab is ever written,
 * and the crash guard that keeps a restored page from crash-looping the
 * app.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TabsStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val dir: File get() = File(folder.root, "tabs")

    private var crashAt: Long? = null
    private var now = 1_000_000L

    private fun store() = TabsStore(dir, lastCrashAt = { crashAt }, clock = { now })

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

    private fun savedFile() = File(dir, TabsStore.FILE)

    /** Every byte under the store's directory, temp files included. */
    private fun everythingOnDisk(): String =
        dir.walk().filter { it.isFile }.joinToString("\n") { it.name + ":" + it.readText() }

    @Test
    fun `the saved list comes back in order with the same tab active`() = runBlocking {
        val tabs = threeTabs()
        // The third never committed: its load was stopped first.
        tabs.tabs[2].url = ""
        tabs.tabs[2].loadAborted = true
        store().save(tabs.saveForProcessDeath())

        val found = store().load() as TabsStore.Found.Tabs
        assertEquals(tabs.saveForProcessDeath(), found.saved)
        val restored = TabsState(homepage = HOME_URL)
        restored.restoreAfterProcessDeath(found.saved)
        assertEquals(listOf("a", "b", "c"), restored.tabs.map { it.title })
        assertEquals(listOf("https://a.example/", "https://b.example/", "https://c.example/"), restored.tabs.map { it.addressBarText })
        assertEquals(1, restored.activeIndex)
        assertTrue(restored.initialLoadDone)
        // Each loads its page when shown — but not a load the user had stopped.
        assertEquals("https://b.example/", restored.tabs[1].pendingRestore!!.afterBlank(false, ABOUT_BLANK)!!.address)
        val stopped = restored.tabs[2].pendingRestore!!.afterBlank(false, ABOUT_BLANK)!!
        assertEquals("https://c.example/", stopped.address)
        assertFalse(stopped.submit)
    }

    @Test
    fun `encode and decode round-trip every field`() {
        val saved = TabsState.SavedTabs(
            tabs = listOf(
                TabsState.SavedTab("Tïtle \"quoted\"\n", "https://x.example/?q=1&r=☃", committed = true, loadStopped = false),
                TabsState.SavedTab("", "name.eth", committed = false, loadStopped = true),
            ),
            activeIndex = 1,
        )
        assertEquals(saved, TabsStore.decode(TabsStore.encode(saved)))
    }

    @Test
    fun `private tabs are never written, nor anything derived from them`() = runBlocking {
        val tabs = threeTabs()
        val regularOnly = threeTabs().saveForProcessDeath()
        // Private tabs before, between and after the regular ones, the
        // last of them on screen, each with its own page and title.
        tabs.newTab(private = true).apply { url = "https://secret-one.example/"; title = "SecretOne"; addressBarText = url }
        tabs.newTab(private = true).apply { url = "https://secret-two.example/"; title = "SecretTwo"; addressBarText = "secret-typed" }
        tabs.moveTab(tabs.tabs.lastIndex, 0)
        tabs.switchTo(tabs.tabs.lastIndex)
        val store = store()
        store.save(tabs.saveForProcessDeath())

        val disk = everythingOnDisk()
        for (secret in listOf("secret", "Secret")) assertFalse(disk, disk.contains(secret))
        // Byte for byte what the same regular tabs write without them —
        // apart from which one is active, which falls back to the regular
        // tab before the private one on screen, as if it never existed.
        assertEquals(
            TabsStore.encode(TabsState.SavedTabs(regularOnly.tabs, activeIndex = 2)),
            savedFile().readText(),
        )

        // Private tabs alone (beside the untouched home tab) leave no file at all.
        val onlyPrivate = TabsState(homepage = HOME_URL)
        onlyPrivate.newTab(private = true).visit("secret")
        store.save(onlyPrivate.saveForProcessDeath())
        assertFalse(savedFile().exists())
        assertFalse(everythingOnDisk().contains("secret"))
    }

    @Test
    fun `a lone home tab removes the saved list rather than writing one`() = runBlocking {
        val store = store()
        store.save(threeTabs().saveForProcessDeath())
        assertTrue(savedFile().exists())
        // Close all tabs leaves one fresh home tab.
        val tabs = threeTabs()
        tabs.closeAllTabs()
        store.save(tabs.saveForProcessDeath())
        assertFalse(savedFile().exists())
        assertSame(TabsStore.Found.Nothing, store.load())
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    @Test
    fun `the last save wins and leaves no temp file behind`() = runBlocking {
        val store = store()
        store.save(threeTabs().saveForProcessDeath())
        val two = TabsState(homepage = HOME_URL).apply { tabs[0].visit("x"); newTab().visit("y") }
        store.save(two.saveForProcessDeath())
        assertEquals(listOf(TabsStore.FILE), dir.list()!!.toList())
        assertEquals(listOf("x", "y"), (store.load() as TabsStore.Found.Tabs).saved.tabs.map { it.title })
    }

    @Test
    fun `an unreadable, foreign or oversized file restores nothing`() {
        dir.mkdirs()
        for (text in listOf("", "{", "[]", """{"version":99,"active":0,"tabs":[]}""", """{"version":1,"tabs":[{"title":"x"}]}""", "[".repeat(100_000))) {
            savedFile().writeText(text)
            assertSame(text.take(40), TabsStore.Found.Nothing, store().load())
        }
        savedFile().writeText(
            TabsStore.encode(TabsState.SavedTabs(listOf(TabsState.SavedTab("t", "https://a.example/?" + "x".repeat(600_000), true, false)), 0)),
        )
        assertSame(TabsStore.Found.Nothing, store().load())
    }

    @Test
    fun `an active index out of range lands on a tab`() {
        val text = TabsStore.encode(threeTabs().saveForProcessDeath()).replace("\"active\":1", "\"active\":7")
        assertEquals(2, TabsStore.decode(text)!!.activeIndex)
    }

    @Test
    fun `a crash soon after a restore skips the next one, once`() = runBlocking {
        store().save(threeTabs().saveForProcessDeath())
        assertTrue(store().load() is TabsStore.Found.Tabs) // restored at `now`, and marked
        crashAt = now + 5_000 // …and the app crashed five seconds later
        now += 10_000
        val skipped = store().load()
        assertTrue(skipped is TabsStore.Found.SkippedAfterCrash)
        assertEquals(listOf("a", "b", "c"), (skipped as TabsStore.Found.SkippedAfterCrash).saved.tabs.map { it.title })
        // The skip loaded nothing, so it left no mark: the same crash
        // doesn't count against the launch after.
        assertTrue(store().load() is TabsStore.Found.Tabs)
    }

    @Test
    fun `a crash long after the restore, or none, still restores`() = runBlocking {
        store().save(threeTabs().saveForProcessDeath())
        assertTrue(store().load() is TabsStore.Found.Tabs)
        crashAt = now + TabsStore.CRASH_WINDOW_MS + 1
        assertTrue(store().load() is TabsStore.Found.Tabs)
        // A crash from before this restore (an older run) isn't its fault.
        crashAt = now - 1
        assertTrue(store().load() is TabsStore.Found.Tabs)
        crashAt = null
        assertTrue(store().load() is TabsStore.Found.Tabs)
    }

    @Test
    fun `the crash window`() {
        assertFalse(TabsStore.skipsRestore(markAt = null, crashAt = 10))
        assertFalse(TabsStore.skipsRestore(markAt = 10, crashAt = null))
        assertTrue(TabsStore.skipsRestore(markAt = 10, crashAt = 10))
        assertTrue(TabsStore.skipsRestore(markAt = 10, crashAt = 10 + TabsStore.CRASH_WINDOW_MS))
        assertFalse(TabsStore.skipsRestore(markAt = 10, crashAt = 11 + TabsStore.CRASH_WINDOW_MS))
        assertFalse(TabsStore.skipsRestore(markAt = 10, crashAt = 9))
    }

    @Test
    fun `tabs skipped after a crash wait on the reopen stack, in place of the home tab`() {
        val saved = threeTabs().apply { switchTo(2) }.saveForProcessDeath()
        val tabs = TabsState(homepage = HOME_URL)
        assertTrue(tabs.pristine)
        val group = tabs.keepForReopen(saved)!!
        assertEquals(1, tabs.tabs.size)
        assertTrue(tabs.active.isHome)
        assertTrue(tabs.canReopenClosedTab)
        assertTrue(tabs.reopenClosed(group))
        assertEquals(listOf("a", "b", "c"), tabs.tabs.map { it.title })
        assertEquals(2, tabs.activeIndex)
        // Not loaded yet: each comes back loading its address.
        assertNotNull(tabs.active.pendingRestore)
    }

    @Test
    fun `home tabs among the skipped ones don't come back`() {
        val saved = TabsState.SavedTabs(
            listOf(TabsState.SavedTab("", "", false, false), TabsState.SavedTab("b", "https://b.example/", true, false)),
            activeIndex = 0,
        )
        val tabs = TabsState(homepage = HOME_URL)
        val group = tabs.keepForReopen(saved)!!
        assertEquals(1, group.tabs.size)
        tabs.reopenClosed(group)
        assertEquals(listOf("b"), tabs.tabs.map { it.title })
        assertNull(tabs.keepForReopen(TabsState.SavedTabs(listOf(TabsState.SavedTab("", "", false, false)), 0)))
    }

    @Test
    fun `a tab list stops being pristine once anything happens`() {
        assertTrue(TabsState(homepage = HOME_URL).pristine)
        assertFalse(TabsState(homepage = HOME_URL).apply { initialLoadDone = true }.pristine)
        assertFalse(TabsState(homepage = HOME_URL).apply { newTab() }.pristine)
        assertFalse(TabsState(homepage = HOME_URL).apply { tabs[0].visit("a") }.pristine)
    }

    @Test
    fun `a cold start's session brings the saved tabs back before its first load`() = runBlocking {
        store().save(threeTabs().saveForProcessDeath())
        val session = TabsSession(HOME_URL, SavedStateHandle(), store())
        withTimeout(5_000) { session.ready.await() }
        assertEquals(listOf("a", "b", "c"), session.tabs.tabs.map { it.title })
        assertEquals(1, session.tabs.activeIndex)
        assertTrue(session.tabs.initialLoadDone)
        assertNull(session.skippedAfterCrash)
    }

    @Test
    fun `a cold start after a crashed restore starts on home and offers the tabs`() = runBlocking {
        store().save(threeTabs().saveForProcessDeath())
        store().load()
        crashAt = now + 1_000
        val session = TabsSession(HOME_URL, SavedStateHandle(), store())
        withTimeout(5_000) { session.ready.await() }
        assertEquals(1, session.tabs.tabs.size)
        assertTrue(session.tabs.active.isHome)
        assertFalse(session.tabs.initialLoadDone)
        assertEquals(3, session.skippedAfterCrash!!.tabs.size)
    }

    @Test
    fun `nothing on disk leaves the fresh tab list`() = runBlocking {
        val session = TabsSession(HOME_URL, SavedStateHandle(), store())
        withTimeout(5_000) { session.ready.await() }
        assertTrue(session.tabs.pristine)
    }
}
