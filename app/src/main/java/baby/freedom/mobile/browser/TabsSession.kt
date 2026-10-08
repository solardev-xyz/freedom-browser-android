package baby.freedom.mobile.browser

import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Holds the browser's [TabsState] outside composition, so the tabs
 * outlive the Activity (#183).
 *
 * `configChanges` keeps the Activity across rotation, theme, locale and
 * the like, but Android still relaunches it for a change to the app's
 * resource overlays — switching the navigation mode, or a wallpaper
 * change regenerating the dynamic-colour overlays. A `ViewModel`
 * survives that relaunch; the tabs' WebViews don't (they're built with
 * the Activity context), so [BrowserWebViewHost] saves each one's state
 * into its tab on the way out ([TabsState.parkForRelaunch]) and the next
 * host restores them from it.
 *
 * A process killed in the background loses this too; for that, the
 * regular tabs' addresses and titles go into the saved instance state
 * ([TabsState.saveForProcessDeath], bounded to stay well inside the
 * binder transaction limit) and come back as tabs that load their page
 * again.
 *
 * The app closed for real (swiped away, force-stopped, updated, the phone
 * rebooted) leaves no saved instance state, so the same list also goes to
 * disk ([TabsStore], #400): written a moment after it changes and when
 * the app goes to the background ([persistNow]), and read back on a cold
 * start, where its tabs come back the same way. Private tabs are never
 * written ([TabsState.saveForProcessDeath] leaves them out).
 */
class TabsSession(
    homepage: String,
    handle: SavedStateHandle,
    private val store: TabsStore? = null,
) : ViewModel() {
    val tabs = TabsState(homepage = homepage)

    /**
     * Completed once the tab list is the one this run starts from: the
     * saved instance state's, the disk's, or a fresh one. The first load
     * waits for it ([BrowserScreen]), so it doesn't submit the homepage
     * into a tab the restore then replaces — and a link the app was
     * cold-started from opens in a new tab beside the restored ones.
     */
    val ready = CompletableDeferred<Unit>()

    /**
     * Saved tabs this run didn't load — the last restore crashed the app
     * ([TabsStore.load]), or the tab list was already in use when the
     * disk read landed — put on the reopen stack as [group], and held on
     * disk ([TabsStore.HELD]) until the user answers the screen's offer
     * ([restoreHeld], [dismissHeld]) or the group leaves the stack some
     * other way (Reopen closed tab, *Delete browsing data*).
     */
    class HeldTabs(val group: TabsState.ClosedGroup, val afterCrash: Boolean)

    /** The offer that's up, if any. Survives an Activity relaunch, so its notice is shown again. */
    var heldTabs by mutableStateOf<HeldTabs?>(null)
        private set

    private var heldWatch: Job? = null

    /** How many times [forgetHeld] has run, so a disk read still in flight knows. */
    private var clears = 0

    /** The disk has been read, so writing it can't lose what it held. */
    private var persisting = false

    init {
        val bundle = handle.get<Bundle>(KEY)
        bundle?.let { savedTabsFrom(it) }?.let(tabs::restoreAfterProcessDeath)
        handle.setSavedStateProvider(KEY) { bundleOf(tabs.saveForProcessDeath()) }
        when {
            store == null -> ready.complete(Unit)
            // Back from a process killed in the background: the saved
            // instance state is the newer of the two. What the previous
            // process left on disk still counts, though: an offer it
            // never answered is made again (so *Delete browsing data* and
            // the rest can end it, R3-F1), and a restore it hadn't settled
            // is the same tabs loading again, so it's marked again and
            // settled by this run's page load (R3-M2).
            bundle != null -> {
                startPersisting(store)
                val clearsBefore = clears
                viewModelScope.launch {
                    val resumed = withContext(Dispatchers.IO) { store.resume() }
                    if (resumed.unsettled) markRestored(store)
                    val held = resumed.held ?: return@launch
                    // History cleared while the file was being read: the
                    // held tabs went with it.
                    if (clears != clearsBefore) store.releaseHeld() else offer(store, held, afterCrash = true)
                }
            }
            else -> viewModelScope.launch {
                val found = withContext(Dispatchers.IO) { store.load() }
                restoreFromDisk(store, found)
                startPersisting(store)
            }
        }
    }

    private suspend fun restoreFromDisk(store: TabsStore, found: TabsStore.Found) {
        when (found) {
            TabsStore.Found.Nothing -> Unit
            is TabsStore.Found.Tabs ->
                if (tabs.pristine) {
                    tabs.restoreAfterProcessDeath(found.saved)
                    markRestored(store)
                    found.held?.let { offer(store, it, afterCrash = true) }
                } else {
                    // Nothing should have happened yet (the first load
                    // waits for [ready]); if something did, the tabs
                    // aren't thrown over it but held and offered back.
                    val held = withContext(Dispatchers.IO) { store.hold(found.saved) }
                    offer(store, held, afterCrash = found.held != null)
                }
            is TabsStore.Found.SkippedAfterCrash -> offer(store, found.saved, afterCrash = true)
        }
    }

    private fun offer(store: TabsStore, saved: TabsState.SavedTabs, afterCrash: Boolean) {
        val group = tabs.keepForReopen(saved) ?: return store.releaseHeld()
        heldTabs = HeldTabs(group, afterCrash)
        // However it leaves the reopen stack — the offer's Restore, Reopen
        // closed tab, *Delete browsing data* — the offer is over and the disk copy
        // goes: reopened, they're open tabs saved (and marked) as such
        // first; forgotten, it just goes.
        heldWatch = viewModelScope.launch {
            snapshotFlow { tabs.isOnReopenStack(group) }.first { !it }
            releaseHeld(store, group)
        }
    }

    private fun releaseHeld(store: TabsStore, group: TabsState.ClosedGroup) {
        if (heldTabs?.group !== group) return
        heldTabs = null
        heldWatch?.cancel()
        heldWatch = null
        tabs.offerWithdrawn(group)
        if (!group.reopened) return store.releaseHeld()
        // Back among the open tabs (Restore, or Reopen closed tab, R4-M1):
        // the held file goes only once the open list holding them and the
        // restore's mark are on disk (R4-F1), not a debounce later.
        // Its place among the saves is taken now, on this thread, ahead
        // of any the debounce makes from here on — and those read the
        // list as it is then, the reopened tabs in it (R5-M1).
        val adopted = store.adoptHeld(tabs.saveForProcessDeath())
        val restored = restoredIds()
        viewModelScope.launch { settleWhenLoaded(store, adopted.await(), restored) }
    }

    /** The offer's Restore: bring the held tabs back, marked like any restore from disk. */
    fun restoreHeld() {
        val held = heldTabs ?: return
        val store = store ?: return
        // Ended here, with the whole group back, not by the watcher.
        heldWatch?.cancel()
        tabs.reopenClosed(held.group)
        releaseHeld(store, held.group)
    }

    /**
     * The offer's ×: the user doesn't want them back now. They stay on
     * this run's reopen stack like any closed tabs, but no longer on disk.
     */
    fun dismissHeld() {
        val held = heldTabs ?: return
        releaseHeld(store ?: return, held.group)
    }

    /**
     * *Delete browsing data*, with Browsing history or Cookies and site
     * data checked: the held tabs are
     * history too. Ends the offer if one is up, and drops the held file
     * whether or not one is (a disk read may not have landed yet).
     */
    fun forgetHeld() {
        clears++
        val store = store ?: return
        heldTabs?.let { releaseHeld(store, it.group) }
        store.releaseHeld()
    }

    /**
     * Mark the restore just done ([TabsStore.markRestored]), and settle
     * it once the page on screen has loaded and [TabsStore.CRASH_WINDOW_MS]
     * more have passed without a crash: counted from the page, not from
     * the launch, so a dweb page that loads (and crashes) only once its
     * node is up is still caught. One that never loads stays to blame up
     * to [TabsStore.MARK_MAX_MS] after the restore. A home tab on screen
     * has no page to load, so its window starts at once.
     */
    private suspend fun markRestored(store: TabsStore) {
        val restored = restoredIds()
        val at = withContext(Dispatchers.IO) { store.markRestored() }
        settleWhenLoaded(store, at, restored)
    }

    /** The regular tabs open as a restore is marked: the ones it brought back, and any beside them. */
    private fun restoredIds(): Set<Long> = tabs.tabs.filter { !it.private }.map { it.id }.toSet()

    /**
     * Settle the restore marked [at] once the tab on screen has loaded
     * its page, no [restored] tab is still loading the page it was
     * restored to, and [TabsStore.CRASH_WINDOW_MS] more have passed.
     *
     * Whichever tab is on screen at the time, not the one that was when
     * the restore ran: a link the app was cold-started from opens a new
     * tab over the restored ones (R4-M3). A restored tab that has
     * started its load is waited for: opening a new (home) tab over a
     * restored dweb page still waiting on its node doesn't start the
     * window, its load finishing does (R5-M2). A tab still
     * [BrowserState.pendingRestore] counts as done, since its load
     * hasn't started (R6-M1): the instant before the host composes, one
     * parked across an Activity relaunch, and every restored tab in the
     * background, whose WebView and load wait until it's shown (#460).
     */
    private fun settleWhenLoaded(store: TabsStore, at: Long, restored: Set<Long>) {
        viewModelScope.launch {
            snapshotFlow {
                val tab = tabs.active
                // A home tab on screen loads no page (R3-M1); a restored
                // tab behind it may still be loading one, though.
                val onScreen = tab.isHome || restoreLoaded(tab)
                val behind = restored.all { id ->
                    val other = tabs.tabs.firstOrNull { it.id == id }
                    other == null || other.isHome || other.pendingRestore != null || restoreLoaded(other)
                }
                onScreen && behind
            }.first { it }
            delay(TabsStore.CRASH_WINDOW_MS)
            withContext(Dispatchers.IO) { store.settleRestore(at) }
        }
    }

    /** [tab]'s restored page has loaded (or won't: it was closed, or its load had been stopped). */
    private fun restoreLoaded(tab: BrowserState): Boolean {
        // Every state this depends on is read each time, so the snapshot
        // flow above sees each of them change ([BrowserState.pendingRestore]
        // itself isn't observable; it's cleared as the load starts).
        val closed = tabs.tabs.none { it.id == tab.id }
        val stopped = tab.loadAborted
        val loaded = tab.url.isNotBlank() && tab.progress < 0 && !tab.resolving
        return closed || (tab.pendingRestore == null && (stopped || loaded))
    }

    private fun startPersisting(store: TabsStore) {
        persisting = true
        ready.complete(Unit)
        viewModelScope.launch {
            // Each change re-reads the list; an unchanged one (a private
            // tab's page, say) isn't a change. Written once it has
            // settled for a moment, so a page's loading doesn't write on
            // every title.
            snapshotFlow { tabs.saveForProcessDeath() }.collectLatest {
                delay(TabsStore.SAVE_DEBOUNCE_MS)
                // The list as it is now, not as it was a second ago: a
                // change whose emission is still on its way (a Restore of
                // held tabs just now) is in it, so this save can't undo
                // it on disk (R5-M1).
                store.saveLater(tabs.saveForProcessDeath())
            }
        }
    }

    /**
     * Write the tab list now, not after the usual pause: the app is going
     * to the background (and may be swiped away or killed without another
     * word), or the user just closed tabs or cleared their history and
     * expects them gone from disk too. Nothing before the disk was read.
     */
    fun persistNow() {
        if (persisting) store?.saveLater(tabs.saveForProcessDeath())
    }

    override fun onCleared() {
        persistNow()
    }

    private companion object {
        const val KEY = "tabs"
        const val COMMITTED = "committed"
        const val TITLES = "titles"
        const val ADDRESSES = "addresses"
        const val STOPPED = "stopped"
        const val ACTIVE = "active"

        fun bundleOf(saved: TabsState.SavedTabs) = Bundle().apply {
            putStringArray(TITLES, saved.tabs.map { it.title }.toTypedArray())
            putStringArray(ADDRESSES, saved.tabs.map { it.address }.toTypedArray())
            putBooleanArray(COMMITTED, saved.tabs.map { it.committed }.toBooleanArray())
            putBooleanArray(STOPPED, saved.tabs.map { it.loadStopped }.toBooleanArray())
            putInt(ACTIVE, saved.activeIndex)
        }

        fun savedTabsFrom(bundle: Bundle): TabsState.SavedTabs? {
            val titles = bundle.getStringArray(TITLES) ?: return null
            val addresses = bundle.getStringArray(ADDRESSES) ?: return null
            val committed = bundle.getBooleanArray(COMMITTED) ?: return null
            val stopped = bundle.getBooleanArray(STOPPED) ?: return null
            val n = addresses.size
            if (titles.size != n || committed.size != n || stopped.size != n) return null
            return TabsState.SavedTabs(
                tabs = addresses.indices.map {
                    TabsState.SavedTab(titles[it], addresses[it], committed[it], stopped[it])
                },
                activeIndex = bundle.getInt(ACTIVE),
            )
        }
    }
}
