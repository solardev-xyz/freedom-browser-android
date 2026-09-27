package baby.freedom.mobile.browser

import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
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
     * Hook installed by the [BrowserWebViewHost] so the capsule's Stop
     * control can abort the given tab's in-flight load. Only the host
     * knows which physical [android.webkit.WebView] backs a tab, and
     * `stopLoading()` has to be called on that instance — the tab state
     * itself can't do it. `null` before the host has composed, or after
     * it disposes.
     */
    @Volatile
    var stopLoading: ((BrowserState) -> Unit)? = null

    /**
     * Hook installed by the [BrowserWebViewHost]: serialise the given
     * tab's WebView — its back/forward list and current entry, via
     * [android.webkit.WebView.saveState] — so [closeTab] can keep it
     * for [reopenClosedTab]. Only the host knows which WebView backs a
     * tab, and the state has to be taken before the host destroys it.
     * `null` before the host has composed, or after it disposes.
     */
    @Volatile
    var saveWebViewState: ((BrowserState) -> Bundle?)? = null

    /**
     * A tab the user closed, kept so [reopenClosedTab] can bring it
     * back where it was, with the history it had.
     *
     * [webViewState] is the closed WebView's own
     * [android.webkit.WebView.saveState] bundle (null if the host
     * couldn't take one); the host hands it to the replacement
     * WebView's `restoreState`. The address fields ride along so the
     * reopened tab shows its page in the chrome and the switcher from
     * the first frame, rather than flashing the home overlay until the
     * restored navigation reports in — and [override] because the
     * restored virtual-origin URLs only display as `name.eth/…` under
     * it.
     */
    class ClosedTab(
        val index: Int,
        val url: String,
        val title: String,
        val addressBarText: String,
        val override: BrowserState.Override?,
        val thumbnail: ImageBitmap?,
        val webViewState: Bundle?,
    )

    /** Most recently closed last. Capped at [MAX_CLOSED_TABS]. */
    private val closedTabs = mutableStateListOf<ClosedTab>()

    /** There is a closed tab [reopenClosedTab] can bring back. */
    val canReopenClosedTab: Boolean
        get() = closedTabs.isNotEmpty()

    /**
     * Hook installed by [BrowserScreen] so the WebView layer can bounce
     * `bzz://` / `ens://` navigations (in-page link clicks, error-page
     * "Try Again" button) back through the screen's probe-gated submit
     * flow. Without this detour, in-page bzz clicks would short-circuit
     * to a raw gateway load and skip the [GatewayProbe]-based
     * peer-warmup gate.
     *
     * Everything on this hook is renderer-initiated by construction, so
     * [BrowserScreen] submits it as `SubmitSource.Renderer` — the
     * address bar keeps describing the page currently on screen until
     * the new navigation commits.
     */
    @Volatile
    var requestSubmit: ((BrowserState, String) -> Unit)? = null

    /**
     * Hook installed by [BrowserScreen]: a main-frame dweb fetch failed
     * although the node reports Running, so the WebView layer wants the
     * nodes prompted to redial before it retries once. `null` before
     * the screen has composed.
     */
    @Volatile
    var requestNodeRecovery: (() -> Unit)? = null

    /**
     * An HTML5 fullscreen session (`element.requestFullscreen()`) in
     * progress. Android WebView hands the fullscreen content over as a
     * plain [View] via `WebChromeClient.onShowCustomView`; the browser
     * chrome renders it in a screen-covering overlay (see
     * [FullscreenCustomView]) and hides the system bars for as long as
     * this is non-null.
     *
     * At most one tab can be fullscreen at a time, and only the active
     * one — a request from a background tab is refused so it can't
     * paint over what the user is looking at.
     */
    class Fullscreen(
        val tabId: Long,
        val view: View,
        val callback: WebChromeClient.CustomViewCallback?,
    )

    var fullscreen: Fullscreen? by mutableStateOf(null)
        private set

    /**
     * `WebChromeClient.onShowCustomView` for [tab]. Refused (the
     * callback is told the view was hidden straight away) if another
     * fullscreen session is already up or [tab] isn't the active one.
     */
    fun enterFullscreen(
        tab: BrowserState,
        view: View,
        callback: WebChromeClient.CustomViewCallback?,
    ) {
        if (fullscreen != null || tab !== active) {
            callback?.onCustomViewHidden()
            return
        }
        fullscreen = Fullscreen(tab.id, view, callback)
    }

    /**
     * `WebChromeClient.onHideCustomView` for [tab] — the page itself
     * exited fullscreen (`document.exitFullscreen()`, or the video
     * player's own button). Ignored if [tab] isn't the one fullscreen.
     */
    fun onFullscreenHidden(tab: BrowserState) {
        if (fullscreen?.tabId == tab.id) fullscreen = null
    }

    /**
     * Leave fullscreen from the browser side (system back, tab closed).
     * Telling the WebView via its [WebChromeClient.CustomViewCallback]
     * makes the page see a proper `fullscreenchange`; the WebView then
     * echoes `onHideCustomView`, which is a no-op by the time it lands.
     */
    fun exitFullscreen() {
        val fs = fullscreen ?: return
        fullscreen = null
        fs.callback?.onCustomViewHidden()
    }

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
        // Let the WebView wind its fullscreen session down before the
        // host destroys it (see [BrowserWebViewHost]).
        if (fullscreen?.tabId == tabs[index].id) exitFullscreen()
        rememberClosed(index)
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
     * Push the tab at [index] onto the reopen stack. The home overlay
     * isn't worth bringing back (the same rule as desktop's
     * Ctrl+Shift+T stack): a tab with nothing committed is skipped.
     */
    private fun rememberClosed(index: Int) {
        val tab = tabs[index]
        if (tab.url.isBlank()) return
        closedTabs.add(
            ClosedTab(
                index = index,
                url = tab.url,
                title = tab.title,
                addressBarText = tab.addressBarText,
                override = tab.override,
                thumbnail = tab.thumbnail,
                webViewState = saveWebViewState?.invoke(tab),
            ),
        )
        while (closedTabs.size > MAX_CLOSED_TABS) closedTabs.removeAt(0)
    }

    /**
     * Bring back the most recently closed tab, at the position it was
     * closed from (clamped to the current list), and make it active.
     * Its WebView is rebuilt from the saved state, so it comes back on
     * the same page with the same back/forward history.
     *
     * Returns the reopened tab, or null if there was nothing to reopen.
     */
    fun reopenClosedTab(): BrowserState? {
        val closed = closedTabs.removeLastOrNull() ?: return null
        val tab = newBlankTab().apply {
            url = closed.url
            title = closed.title
            addressBarText = closed.addressBarText
            override = closed.override
            thumbnail = closed.thumbnail
            pendingRestore = BrowserState.PendingRestore(
                webViewState = closed.webViewState,
                fallbackUrl = closed.addressBarText.ifBlank { closed.url },
            )
        }
        val at = closed.index.coerceIn(0, tabs.size)
        if (tabs.isNotEmpty()) captureActiveThumbnail?.invoke()
        tabs.add(at, tab)
        activeIndex = at
        return tab
    }

    /**
     * Forget every closed tab. Their saved WebView state carries
     * back/forward history, so "clear browsing data" has to drop it
     * along with the live tabs' history.
     */
    fun forgetClosedTabs() {
        closedTabs.clear()
    }

    /**
     * Move the tab at [from] to [to] (the index it ends up at), keeping
     * the same tab active. Tabs map to their WebViews by id, so nothing
     * on the WebView side changes — only the order the switcher shows.
     */
    fun moveTab(from: Int, to: Int) {
        if (from !in tabs.indices || to !in tabs.indices || from == to) return
        val activeId = active.id
        tabs.add(to, tabs.removeAt(from))
        activeIndex = tabs.indexOfFirst { it.id == activeId }.coerceAtLeast(0)
    }

    /**
     * The homepage URL. Exposed so callers that want to load it lazily
     * (e.g. "wait for node to be Running before loading `ens://...`") can
     * read it without hard-coding a literal.
     */
    val homepageUrl: String
        get() = homepage

    private fun newBlankTab(): BrowserState = BrowserState(id = idSeq.incrementAndGet())

    companion object {
        /** How many closed tabs [reopenClosedTab] can walk back through. */
        const val MAX_CLOSED_TABS = 20
    }
}
