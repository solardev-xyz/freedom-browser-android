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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
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
     * Tabs the last restore crashed the app with ([TabsStore.load]), put
     * on the reopen stack rather than loaded; the screen offers them
     * back once, then clears this.
     */
    var skippedAfterCrash by mutableStateOf<TabsState.ClosedGroup?>(null)

    /** The disk has been read, so writing it can't lose what it held. */
    private var persisting = false

    init {
        val bundle = handle.get<Bundle>(KEY)
        bundle?.let { savedTabsFrom(it) }?.let(tabs::restoreAfterProcessDeath)
        handle.setSavedStateProvider(KEY) { bundleOf(tabs.saveForProcessDeath()) }
        when {
            store == null -> ready.complete(Unit)
            // Back from a process killed in the background: the saved
            // instance state is the newer of the two.
            bundle != null -> startPersisting(store)
            else -> viewModelScope.launch {
                val found = withContext(Dispatchers.IO) { store.load() }
                restoreFromDisk(found)
                startPersisting(store)
            }
        }
    }

    private fun restoreFromDisk(found: TabsStore.Found) {
        when (found) {
            TabsStore.Found.Nothing -> Unit
            is TabsStore.Found.Tabs ->
                // Nothing can have happened yet (the first load waits for
                // [ready]); if something did, the tabs aren't thrown over
                // it but wait on the reopen stack.
                if (tabs.pristine) tabs.restoreAfterProcessDeath(found.saved) else tabs.keepForReopen(found.saved)
            is TabsStore.Found.SkippedAfterCrash -> skippedAfterCrash = tabs.keepForReopen(found.saved)
        }
    }

    private fun startPersisting(store: TabsStore) {
        persisting = true
        ready.complete(Unit)
        viewModelScope.launch {
            // Each change re-reads the list; an unchanged one (a private
            // tab's page, say) isn't a change. Written once it has
            // settled for a moment, so a page's loading doesn't write on
            // every title.
            snapshotFlow { tabs.saveForProcessDeath() }.collectLatest { saved ->
                delay(TabsStore.SAVE_DEBOUNCE_MS)
                store.saveLater(saved)
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
