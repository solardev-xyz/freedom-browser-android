package baby.freedom.mobile.browser

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
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

    private val main = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(main)

    /** Every session a test made, stopped before the next test swaps Main. */
    private val sessions = mutableListOf<TabsSession>()

    private fun session(store: TabsStore = store()): TabsSession =
        TabsSession(HOME_URL, SavedStateHandle(), store).also { sessions += it }

    @After
    fun tearDown() {
        sessions.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    private val dir: File get() = File(folder.root, "tabs")

    private var crashAt: Long? = null
    private var now = 1_000_000L

    private fun store() = TabsStore(dir, crashAfter = { crashAt }, clock = { now })

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

    /** A launch that loads what it finds and marks the restore, as [TabsSession] does. */
    private suspend fun restoreAndMark(store: TabsStore = store()): Long {
        assertTrue(store.load() is TabsStore.Found.Tabs)
        return store.markRestored()
    }

    @Test
    fun `a crash soon after a restore skips the next one, and holds the tabs until answered`() = runBlocking {
        store().save(threeTabs().saveForProcessDeath())
        restoreAndMark() // restored at `now`, and marked
        crashAt = now + 5_000 // …and the app crashed five seconds later
        now += 10_000
        val skipped = store().load()
        assertTrue(skipped is TabsStore.Found.SkippedAfterCrash)
        assertEquals(listOf("a", "b", "c"), (skipped as TabsStore.Found.SkippedAfterCrash).saved.tabs.map { it.title })
        assertTrue(File(dir, TabsStore.HELD).exists())
        // The skip loaded nothing, so it left no mark — but the tabs
        // are only offered, never loaded, until the user answers.
        assertTrue(store().load() is TabsStore.Found.SkippedAfterCrash)
        store().releaseHeld()
        awaitHeldGone()
        assertSame(TabsStore.Found.Nothing, store().load())
    }

    @Test
    fun `a crash long after the restore, or none, or after it settled, still restores`() = runBlocking {
        store().save(threeTabs().saveForProcessDeath())
        restoreAndMark()
        crashAt = now + TabsStore.MARK_MAX_MS + 1
        assertTrue(store().load() is TabsStore.Found.Tabs)
        // A crash from before this restore (an older run) isn't its fault.
        restoreAndMark()
        crashAt = now - 1
        assertTrue(store().load() is TabsStore.Found.Tabs)
        restoreAndMark()
        crashAt = null
        assertTrue(store().load() is TabsStore.Found.Tabs)
        // The restored page loaded and the window after it passed: a
        // crash after that is the user's browsing, not the restore.
        val store = store()
        val at = restoreAndMark(store)
        store.settleRestore(at)
        crashAt = now + 1_000
        assertTrue(store().load() is TabsStore.Found.Tabs)
    }

    @Test
    fun `settling an older restore leaves a newer one's mark`() = runBlocking {
        store().save(threeTabs().saveForProcessDeath())
        val store = store()
        val older = store.markRestored()
        now += 1
        store.markRestored()
        store.settleRestore(older)
        crashAt = now + 1
        assertTrue(store().load() is TabsStore.Found.SkippedAfterCrash)
    }

    @Test
    fun `the crash window`() {
        assertFalse(TabsStore.skipsRestore(markAt = null, crashAt = 10))
        assertFalse(TabsStore.skipsRestore(markAt = 10, crashAt = null))
        assertTrue(TabsStore.skipsRestore(markAt = 10, crashAt = 10))
        // Unsettled, a restore stays to blame well past a minute: a dweb
        // page can need its node first.
        assertTrue(TabsStore.skipsRestore(markAt = 10, crashAt = 10 + 5 * TabsStore.CRASH_WINDOW_MS))
        assertTrue(TabsStore.skipsRestore(markAt = 10, crashAt = 10 + TabsStore.MARK_MAX_MS))
        assertFalse(TabsStore.skipsRestore(markAt = 10, crashAt = 11 + TabsStore.MARK_MAX_MS))
        assertFalse(TabsStore.skipsRestore(markAt = 10, crashAt = 9))
    }

    @Test
    fun `held tabs from repeated crashes add up, bounded`() {
        val a = TabsState.SavedTabs(listOf(TabsState.SavedTab("a", "https://a.example/", true, false)), 0)
        val bc = TabsState.SavedTabs(
            listOf(TabsState.SavedTab("b", "https://b.example/", true, false), TabsState.SavedTab("c", "https://c.example/", true, false)),
            1,
        )
        val both = TabsStore.merged(a, bc)
        assertEquals(listOf("a", "b", "c"), both.tabs.map { it.title })
        assertEquals(2, both.activeIndex)
        val big = TabsState.SavedTabs(listOf(TabsState.SavedTab("big", "https://x.example/?" + "x".repeat(TabsState.MAX_SAVED_CHARS - 40), true, false)), 0)
        val bounded = TabsStore.merged(TabsStore.merged(a, big), bc)
        assertEquals(listOf("b", "c"), bounded.tabs.map { it.title })
        assertEquals(1, bounded.activeIndex)
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
        val session = session()
        withTimeout(5_000) { session.ready.await() }
        assertEquals(listOf("a", "b", "c"), session.tabs.tabs.map { it.title })
        assertEquals(1, session.tabs.activeIndex)
        assertTrue(session.tabs.initialLoadDone)
        assertNull(session.heldTabs)
        // Marked: a crash now skips the next launch's restore.
        crashAt = now + 1_000
        assertTrue(store().load() is TabsStore.Found.SkippedAfterCrash)
    }

    @Test
    fun `a cold start after a crashed restore starts on home and offers the tabs`() = runBlocking {
        store().save(threeTabs().saveForProcessDeath())
        restoreAndMark()
        crashAt = now + 1_000
        val session = session()
        withTimeout(5_000) { session.ready.await() }
        assertEquals(1, session.tabs.tabs.size)
        assertTrue(session.tabs.active.isHome)
        assertFalse(session.tabs.initialLoadDone)
        assertEquals(3, session.heldTabs!!.group.tabs.size)
        assertTrue(session.heldTabs!!.afterCrash)
    }

    /** A cold start whose last restore crashed, as far as its session's ready. */
    private fun crashedRestoreSession(): TabsSession = runBlocking {
        store().save(threeTabs().saveForProcessDeath())
        restoreAndMark()
        crashAt = now + 1_000
        session().also { withTimeout(5_000) { it.ready.await() } }
    }

    @Test
    fun `tabs held after a crash survive the home tab being saved and another launch`() = runBlocking {
        val session = crashedRestoreSession()
        // The session saves its lone home tab (the debounce, or ON_STOP)…
        store().save(session.tabs.saveForProcessDeath())
        assertFalse(savedFile().exists())
        // …and the app dies before the user answers the offer: the
        // next launch offers the same tabs again, without loading them.
        val next = session()
        withTimeout(5_000) { next.ready.await() }
        assertTrue(next.tabs.active.isHome)
        assertEquals(listOf("a", "b", "c"), next.heldTabs!!.group.tabs.map { it.title })
    }

    @Test
    fun `tabs opened after a crash restore normally, the held ones offered beside them`() = runBlocking {
        val session = crashedRestoreSession()
        session.tabs.tabs[0].visit("x")
        session.tabs.newTab().visit("y")
        store().save(session.tabs.saveForProcessDeath())
        crashAt = null
        val next = session()
        withTimeout(5_000) { next.ready.await() }
        assertEquals(listOf("x", "y"), next.tabs.tabs.map { it.title })
        assertEquals(listOf("a", "b", "c"), next.heldTabs!!.group.tabs.map { it.title })
    }

    /** The held-tabs file is gone, once the store's background release has run. */
    private fun awaitHeldGone() = runBlocking {
        withTimeout(5_000) { while (File(dir, TabsStore.HELD).exists()) kotlinx.coroutines.delay(10) }
    }

    @Test
    fun `restoring the held tabs opens them, ends the offer and marks the restore`() = runBlocking {
        val session = crashedRestoreSession()
        val group = session.heldTabs!!.group
        session.restoreHeld()
        assertNull(session.heldTabs)
        assertFalse(session.tabs.isOnReopenStack(group))
        assertEquals(listOf("a", "b", "c"), session.tabs.tabs.map { it.title })
        awaitHeldGone()
        // The held copy is gone only once the open list holds them and the
        // restore is marked — no debounce, no ON_STOP needed (R4-F1)…
        assertTrue(File(dir, TabsStore.RESTORE_MARK).exists())
        assertEquals(listOf("a", "b", "c"), TabsStore.decode(savedFile().readText())!!.tabs.map { it.title })
        // …so a crash soon after this restore holds them back again…
        crashAt = now + 1_000
        val skipped = store().load() as TabsStore.Found.SkippedAfterCrash
        assertEquals(listOf("a", "b", "c"), skipped.saved.tabs.map { it.title })
    }

    @Test
    fun `restored held tabs survive a crash before the next save`() = runBlocking {
        val session = crashedRestoreSession()
        session.restoreHeld()
        awaitHeldGone()
        // The app dies right away, before any debounced save: the next
        // launch still has the tabs (open, or held again after the crash).
        session.viewModelScope.cancel()
        crashAt = null
        val found = store().load() as TabsStore.Found.Tabs
        assertEquals(listOf("a", "b", "c"), found.saved.tabs.map { it.title })
    }

    @Test
    fun `dismissing the offer, Reopen closed tab or Clear history each end it and drop the held copy`() {
        // The ×: still on this run's reopen stack, no longer pinned or on disk.
        crashedRestoreSession().apply {
            val group = heldTabs!!.group
            dismissHeld()
            assertNull(heldTabs)
            assertTrue(tabs.isOnReopenStack(group))
            awaitHeldGone()
        }
        // Reopen closed tab (Ctrl+Shift+T) while the notice is up.
        crashedRestoreSession().apply {
            // Applied at once, as the main thread would: the watcher sees the whole group back.
            androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot { assertNotNull(tabs.reopenClosedTab()) }
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            runBlocking { withTimeout(5_000) { while (heldTabs != null) kotlinx.coroutines.delay(10) } }
            assertEquals(3, tabs.tabs.size)
            awaitHeldGone()
            // Open tabs now, saved and marked like the offer's Restore (R4-M1).
            assertTrue(File(dir, TabsStore.RESTORE_MARK).exists())
            assertEquals(3, TabsStore.decode(savedFile().readText())!!.tabs.size)
            File(dir, TabsStore.RESTORE_MARK).delete() // for the next case's own restore
        }
        // Clear history forgets the reopen stack, and the held tabs with it.
        crashedRestoreSession().apply {
            tabs.forgetClosedTabs()
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            runBlocking { withTimeout(5_000) { while (heldTabs != null) kotlinx.coroutines.delay(10) } }
            awaitHeldGone()
        }
    }

    @Test
    fun `held tabs outlast the stack's cap through a later Close all and its Undo`() = runBlocking {
        // A crashed restore of 15 tabs; the user opens 10 more and closes
        // them all. The bulk close's Undo goes on screen, and together the
        // two groups are past the cap — the held ones must still stay.
        val many = TabsState(homepage = HOME_URL)
        many.tabs[0].visit("h0")
        for (i in 1 until 15) many.newTab().visit("h$i")
        store().save(many.saveForProcessDeath())
        restoreAndMark()
        crashAt = now + 1_000
        val session = session().also { withTimeout(5_000) { it.ready.await() } }
        val group = session.heldTabs!!.group
        session.tabs.tabs[0].visit("n0")
        for (i in 1 until 10) session.tabs.newTab().visit("n$i")
        val closed = session.tabs.closeAllTabs()
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        assertTrue(session.tabs.isOnReopenStack(group))
        assertSame(group, session.heldTabs?.group)
        // The Close all notice times out: its group may be trimmed now,
        // the unanswered held one still not.
        session.tabs.undoWithdrawn(closed.undo!!)
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        assertTrue(session.tabs.isOnReopenStack(group))
        assertSame(group, session.heldTabs?.group)
        assertTrue(File(dir, TabsStore.HELD).exists())
        // A launch before the answer still offers them.
        store().save(session.tabs.saveForProcessDeath())
        crashAt = null
        val next = session().also { withTimeout(5_000) { it.ready.await() } }
        assertEquals(15, next.heldTabs!!.group.tabs.size)
    }

    @Test
    fun `an offer not yet answered is still up for a relaunched screen`() {
        val session = crashedRestoreSession()
        // A screen's notice cancelled by an Activity relaunch answers nothing.
        assertNotNull(session.heldTabs)
        assertTrue(File(dir, TabsStore.HELD).exists())
    }

    @Test
    fun `tabs the disk read finds after the list was already used are held and offered, not lost`() = runBlocking {
        store().save(threeTabs().saveForProcessDeath())
        // Hold the IO thread's read back until the list has been touched.
        val gate = java.util.concurrent.CountDownLatch(1)
        val store = TabsStore(dir, crashAfter = { gate.await(); null }, clock = { now })
        val session = session(store)
        session.tabs.tabs[0].visit("x")
        gate.countDown()
        withTimeout(5_000) { session.ready.await() }
        assertEquals(listOf("x"), session.tabs.tabs.map { it.title })
        assertFalse(session.heldTabs!!.afterCrash)
        assertEquals(listOf("a", "b", "c"), session.heldTabs!!.group.tabs.map { it.title })
        assertTrue(File(dir, TabsStore.HELD).exists())
        // Not loaded, so not marked: a crash now doesn't count against a restore.
        assertFalse(File(dir, TabsStore.RESTORE_MARK).exists())
        // Answered, it lets go of its place on the reopen stack.
        session.dismissHeld()
        awaitHeldGone()
    }

    @Test
    fun `a restore settles only once its page has loaded and the crash window passed`() = runBlocking {
        store().save(threeTabs().saveForProcessDeath())
        val session = session()
        withTimeout(5_000) { session.ready.await() }
        val mark = File(dir, TabsStore.RESTORE_MARK)
        withTimeout(5_000) { while (!mark.exists()) kotlinx.coroutines.delay(10) }
        // Long after launch, the page still hasn't loaded (its node is slow): still to blame.
        main.scheduler.advanceTimeBy(5 * TabsStore.CRASH_WINDOW_MS)
        kotlinx.coroutines.delay(50)
        assertTrue(mark.exists())
        // It loads…
        session.tabs.active.apply {
            pendingRestore = null
            url = "https://b.example/"
            progress = 50
        }
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        session.tabs.active.progress = -1
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        // …and a crash within the window after that still counts…
        main.scheduler.advanceTimeBy(TabsStore.CRASH_WINDOW_MS - 1_000)
        kotlinx.coroutines.delay(50)
        assertTrue(mark.exists())
        // …but not once it has passed.
        main.scheduler.advanceTimeBy(2_000)
        withTimeout(5_000) { while (mark.exists()) kotlinx.coroutines.delay(10) }
    }

    /** A session relaunched from saved instance state (a process killed in the background). */
    private fun relaunchedSession(store: TabsStore = store()): TabsSession =
        TabsSession(HOME_URL, SavedStateHandle(mapOf("tabs" to android.os.Bundle())), store).also { sessions += it }

    @Test
    fun `a relaunch from saved instance state offers the held tabs again, and Clear history drops them`() = runBlocking {
        crashedRestoreSession().also { assertTrue(File(dir, TabsStore.HELD).exists()) }
        // The process is killed in the background; the next one comes
        // back from its saved instance state, not from the disk list.
        val session = relaunchedSession()
        withTimeout(5_000) { session.ready.await() }
        withTimeout(5_000) { while (session.heldTabs == null) kotlinx.coroutines.delay(10) }
        assertEquals(listOf("a", "b", "c"), session.heldTabs!!.group.tabs.map { it.title })
        assertTrue(session.heldTabs!!.afterCrash)
        // Clear history, as Settings runs it: the held tabs go too.
        session.tabs.forgetClosedTabs()
        session.forgetHeld()
        assertNull(session.heldTabs)
        awaitHeldGone()
        // …so the next cold start has nothing to offer back.
        crashAt = null
        val next = session().also { withTimeout(5_000) { it.ready.await() } }
        assertNull(next.heldTabs)
    }

    @Test
    fun `Clear history drops the held tabs even before a relaunch has read them`() = runBlocking {
        crashedRestoreSession()
        val session = relaunchedSession()
        session.forgetHeld()
        awaitHeldGone()
        withTimeout(5_000) { session.ready.await() }
        kotlinx.coroutines.delay(100)
        assertNull(session.heldTabs)
        assertFalse(File(dir, TabsStore.HELD).exists())
    }

    @Test
    fun `a relaunch from saved instance state takes the last run's unsettled mark and settles its own`() = runBlocking {
        store().save(threeTabs().saveForProcessDeath())
        restoreAndMark() // marked at `now`, never settled: killed in the background
        now += 5_000
        val session = relaunchedSession()
        withTimeout(5_000) { session.ready.await() }
        val mark = File(dir, TabsStore.RESTORE_MARK)
        // The old mark is gone, replaced by this run's own…
        withTimeout(5_000) { while (mark.takeIf { it.exists() }?.readText()?.trim() != now.toString()) kotlinx.coroutines.delay(10) }
        kotlinx.coroutines.delay(50)
        // …which settles like any restore's (home on screen: nothing to load).
        main.scheduler.advanceTimeBy(TabsStore.CRASH_WINDOW_MS + 1_000)
        withTimeout(5_000) { while (mark.exists()) kotlinx.coroutines.delay(10) }
        // An unrelated crash now doesn't hold the tabs back next time.
        session.viewModelScope.cancel()
        store().save(threeTabs().saveForProcessDeath())
        crashAt = now + 1_000
        assertTrue(store().load() is TabsStore.Found.Tabs)
    }

    @Test
    fun `a restore with a home tab on screen settles once the crash window passes`() = runBlocking {
        val tabs = threeTabs()
        tabs.newTab() // a New tab, left active
        store().save(tabs.saveForProcessDeath())
        val session = session()
        withTimeout(5_000) { session.ready.await() }
        assertTrue(session.tabs.active.isHome)
        val mark = File(dir, TabsStore.RESTORE_MARK)
        withTimeout(5_000) { while (!mark.exists()) kotlinx.coroutines.delay(10) }
        kotlinx.coroutines.delay(50)
        main.scheduler.advanceTimeBy(TabsStore.CRASH_WINDOW_MS - 1_000)
        kotlinx.coroutines.delay(50)
        assertTrue(mark.exists())
        main.scheduler.advanceTimeBy(2_000)
        withTimeout(5_000) { while (mark.exists()) kotlinx.coroutines.delay(10) }
    }

    @Test
    fun `only the first exit after the restore decides whether it crashed`() {
        val crash = TabsStore.Exit(at = 2_000, crashed = true)
        val reclaimed = TabsStore.Exit(at = 5_000, crashed = false)
        // A start in the background later, reclaimed for memory, doesn't mask the crash (R4-M2)…
        assertEquals(2_000L, TabsStore.firstExitCrash(listOf(reclaimed, crash), since = 1_000))
        // …nor does an older crash, before the restore, count.
        assertNull(TabsStore.firstExitCrash(listOf(TabsStore.Exit(500, true), TabsStore.Exit(1_500, false)), since = 1_000))
        assertNull(TabsStore.firstExitCrash(emptyList(), since = 1_000))
    }

    @Test
    fun `a restore settles from the page on screen when a link opened a tab over it`() = runBlocking {
        store().save(threeTabs().saveForProcessDeath())
        val session = session()
        withTimeout(5_000) { session.ready.await() }
        val mark = File(dir, TabsStore.RESTORE_MARK)
        withTimeout(5_000) { while (!mark.exists()) kotlinx.coroutines.delay(10) }
        // The app was cold-started from a link: it opens in a new tab,
        // and the restored one behind it never loads (R4-M3).
        session.tabs.newTab().apply {
            url = "https://link.example/"
            addressBarText = url
            progress = 50
        }
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        session.tabs.active.progress = -1
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        kotlinx.coroutines.delay(50)
        main.scheduler.advanceTimeBy(TabsStore.CRASH_WINDOW_MS + 1_000)
        withTimeout(5_000) { while (mark.exists()) kotlinx.coroutines.delay(10) }
    }

    @Test
    fun `a debounced save due just after Restore can't drop the restored tabs from disk`() = runBlocking {
        val session = crashedRestoreSession()
        // The home tab's own save has gone by.
        kotlinx.coroutines.delay(50)
        main.scheduler.advanceTimeBy(2 * TabsStore.SAVE_DEBOUNCE_MS)
        kotlinx.coroutines.delay(50)
        // A change a moment before Restore leaves a save pending on the debounce…
        session.tabs.tabs[0].visit("x")
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        kotlinx.coroutines.delay(50)
        main.scheduler.advanceTimeBy(TabsStore.SAVE_DEBOUNCE_MS - 100)
        kotlinx.coroutines.delay(50)
        assertFalse(savedFile().exists())
        // …the user taps Restore, and the debounce runs out before the
        // tab list's change has reached it (R5-M1).
        session.restoreHeld()
        main.scheduler.advanceTimeBy(200)
        main.scheduler.runCurrent()
        awaitHeldGone()
        kotlinx.coroutines.delay(200)
        // The open list still holds them: a crash now loses nothing.
        val onDisk = TabsStore.decode(savedFile().readText())!!.tabs.map { it.title }
        assertTrue(onDisk.toString(), onDisk.containsAll(listOf("x", "a", "b", "c")))
    }

    @Test
    fun `a restored page still loading behind a new home tab keeps the restore to blame`() = runBlocking {
        store().save(threeTabs().saveForProcessDeath())
        val session = session()
        withTimeout(5_000) { session.ready.await() }
        val mark = File(dir, TabsStore.RESTORE_MARK)
        withTimeout(5_000) { while (!mark.exists()) kotlinx.coroutines.delay(10) }
        // The restored page starts loading (a dweb page, waiting on its node)…
        val restored = session.tabs.active.apply {
            pendingRestore = null
            url = "https://b.example/"
            progress = 10
        }
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        // …and the user opens a new tab over it (R5-M2).
        session.tabs.newTab()
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        assertTrue(session.tabs.active.isHome)
        kotlinx.coroutines.delay(50)
        main.scheduler.advanceTimeBy(3 * TabsStore.CRASH_WINDOW_MS)
        kotlinx.coroutines.delay(50)
        assertTrue(mark.exists())
        // It finishes off screen: the window starts from there.
        restored.progress = -1
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        kotlinx.coroutines.delay(50)
        main.scheduler.advanceTimeBy(TabsStore.CRASH_WINDOW_MS - 1_000)
        kotlinx.coroutines.delay(50)
        assertTrue(mark.exists())
        main.scheduler.advanceTimeBy(2_000)
        withTimeout(5_000) { while (mark.exists()) kotlinx.coroutines.delay(10) }
    }

    @Test
    fun `nothing on disk leaves the fresh tab list`() = runBlocking {
        val session = session()
        withTimeout(5_000) { session.ready.await() }
        assertTrue(session.tabs.pristine)
    }
}
