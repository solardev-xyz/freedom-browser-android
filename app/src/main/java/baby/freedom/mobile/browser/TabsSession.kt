package baby.freedom.mobile.browser

import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel

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
 */
class TabsSession(homepage: String, handle: SavedStateHandle) : ViewModel() {
    val tabs = TabsState(homepage = homepage)

    init {
        handle.get<Bundle>(KEY)?.let { bundle ->
            savedTabsFrom(bundle)?.let(tabs::restoreAfterProcessDeath)
        }
        handle.setSavedStateProvider(KEY) { bundleOf(tabs.saveForProcessDeath()) }
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
