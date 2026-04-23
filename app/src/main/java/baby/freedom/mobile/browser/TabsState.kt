package baby.freedom.mobile.browser

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns the full list of browser tabs plus which one is active.
 *
 * All state is Compose-observable: the tab list is a `SnapshotStateList`,
 * and [activeIndex] is a `MutableIntState`. Mutations outside a composition
 * (e.g. from a lifecycle callback) are safe because snapshot state is
 * thread-safe.
 *
 * There is always at least one tab. Closing the last tab replaces it with a
 * fresh homepage tab rather than leaving the list empty.
 */
class TabsState(
    private val homepage: String,
) {
    private val idSeq = AtomicLong(0L)

    val tabs: MutableList<BrowserState> = mutableStateListOf<BrowserState>().apply {
        add(newBlankTab())
    }

    var activeIndex: Int by mutableIntStateOf(0)
        private set

    val active: BrowserState
        get() = tabs[activeIndex.coerceIn(0, tabs.lastIndex)]

    /**
     * Hook installed by the [BrowserWebViewHost] so we can snapshot the
     * currently-active WebView at moments when a thumbnail matters:
     *   • right before a tab-switcher render
     *   • right before swapping tabs (so the one we're leaving gets a
     *     preview that matches what the user last saw).
     *
     * `null` before the host has composed, or after it disposes.
     */
    @Volatile
    var captureActiveThumbnail: (() -> Unit)? = null

    /**
     * Hook installed by the [BrowserWebViewHost] so the settings screen
     * can wipe every tab's WebView-side state (cache, form data, back/
     * forward stack, cookies, site storage) in one shot. `null` before
     * the host has composed, or after it disposes.
     */
    @Volatile
    var clearWebViewData: (() -> Unit)? = null

    /**
     * Open a new tab. If [url] is null (typical "+" button) the tab starts
     * blank and the caller is expected to load the homepage once the node
     * is running; otherwise [url] is submitted immediately (typical
     * "open link in new tab" flow).
     */
    fun newTab(url: String? = null): BrowserState {
        val tab = newBlankTab()
        tabs.add(tab)
        activeIndex = tabs.lastIndex
        if (url != null) tab.loadUrl(url)
        return tab
    }

    fun switchTo(index: Int) {
        if (index !in tabs.indices) return
        if (index != activeIndex) captureActiveThumbnail?.invoke()
        activeIndex = index
    }

    fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        tabs.removeAt(index)
        if (tabs.isEmpty()) {
            tabs.add(newBlankTab())
            activeIndex = 0
            return
        }
        activeIndex = (if (index >= tabs.size) tabs.lastIndex else index)
            .coerceIn(0, tabs.lastIndex)
    }

    /**
     * The homepage URL. Exposed so callers that want to load it lazily
     * (e.g. "wait for node to be Running before loading `ens://...`") can
     * read it without hard-coding a literal.
     */
    val homepageUrl: String
        get() = homepage

    private fun newBlankTab(): BrowserState = BrowserState(id = idSeq.incrementAndGet())
}
