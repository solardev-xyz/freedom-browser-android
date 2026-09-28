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
 * ([TabsState.saveForProcessDeath]) and come back as tabs that load
 * their page again.
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
        const val URLS = "urls"
        const val TITLES = "titles"
        const val ADDRESSES = "addresses"
        const val STOPPED = "stopped"
        const val ACTIVE = "active"

        fun bundleOf(saved: TabsState.SavedTabs) = Bundle().apply {
            putStringArray(URLS, saved.tabs.map { it.url }.toTypedArray())
            putStringArray(TITLES, saved.tabs.map { it.title }.toTypedArray())
            putStringArray(ADDRESSES, saved.tabs.map { it.address }.toTypedArray())
            putBooleanArray(STOPPED, saved.tabs.map { it.loadStopped }.toBooleanArray())
            putInt(ACTIVE, saved.activeIndex)
        }

        fun savedTabsFrom(bundle: Bundle): TabsState.SavedTabs? {
            val urls = bundle.getStringArray(URLS) ?: return null
            val titles = bundle.getStringArray(TITLES) ?: return null
            val addresses = bundle.getStringArray(ADDRESSES) ?: return null
            val stopped = bundle.getBooleanArray(STOPPED) ?: return null
            if (titles.size != urls.size || addresses.size != urls.size || stopped.size != urls.size) return null
            return TabsState.SavedTabs(
                tabs = urls.indices.map {
                    TabsState.SavedTab(urls[it], titles[it], addresses[it], stopped[it])
                },
                activeIndex = bundle.getInt(ACTIVE),
            )
        }
    }
}
