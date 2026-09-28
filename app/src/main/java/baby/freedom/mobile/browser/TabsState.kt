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
     * it. [loadStopped] is the tab's Stop latch
     * ([BrowserState.loadAborted]): a first navigation the user stopped
     * isn't fetched again on reopen.
     *
     * [placeholderId] is the blank tab [closeTab] put in this one's
     * place because it was the last tab, so undoing *this* close (and
     * no other) can drop that tab again if it was never used.
     */
    class ClosedTab(
        val index: Int,
        val url: String,
        val title: String,
        val addressBarText: String,
        val override: BrowserState.Override?,
        val thumbnail: ImageBitmap?,
        val webViewState: Bundle?,
        val loadStopped: Boolean = false,
        val placeholderId: Long? = null,
    )

    /** Most recently closed last. Capped at [MAX_CLOSED_TABS]. */
    private val closedTabs = mutableStateListOf<ClosedTab>()

    /** There is a closed tab [reopenClosedTab] can bring back. */
    val canReopenClosedTab: Boolean
        get() = closedTabs.isNotEmpty()

    /**
     * Hook installed by the [BrowserWebViewHost] so the find bar can drive
     * the given tab's `findAllAsync` / `findNext` / `clearMatches`. Like
     * [stopLoading], only the host knows which physical WebView backs a
     * tab. `null` before the host has composed, or after it disposes.
     */
    @Volatile
    var find: ((BrowserState, FindAction) -> Unit)? = null

    /**
     * Hook installed by the [BrowserWebViewHost] so the overflow menu's
     * Print item can hand the given tab's page to the system print
     * framework (#89) — like [stopLoading], only the host knows which
     * [android.webkit.WebView] backs the tab. `null` before the host has
     * composed, or after it disposes.
     */
    @Volatile
    var printPage: ((BrowserState) -> Unit)? = null

    /**
     * Hook installed by the [BrowserWebViewHost]: mute or unmute the
     * given tab's WebView (#91, `WebViewCompat.setAudioMuted`) — only the
     * host knows which WebView backs a tab. `null` before the host has
     * composed, after it disposes, and when the device's WebView doesn't
     * support muting (then the tab switcher shows the audio indicator
     * without a mute toggle). Snapshot state, so the switcher picks up
     * the hook's arrival.
     */
    var setAudioMuted: ((BrowserState, Boolean) -> Unit)? by mutableStateOf(null)

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
     * Hook installed by [BrowserScreen]: open a URL in a new tab through
     * the screen's submit flow — in the background (with a snackbar to
     * switch to it) or in front. Used by the page context menu and the
     * text-selection "Search" (#84). With `private` (asked from a
     * private tab) the new tab is private too (#86). `null` before the
     * screen has composed.
     */
    @Volatile
    var requestOpenInNewTab: ((url: String, background: Boolean, private: Boolean) -> Unit)? = null

    /**
     * Hook installed by [BrowserScreen]: search [query] with the engine
     * chosen in Settings, in a new tab in front — the text-selection
     * toolbar's "Search" (#84). The screen owns the engine setting, so
     * the URL is built there ([UrlParser.searchUrl]). `null` before the
     * screen has composed.
     */
    @Volatile
    var requestSearchInNewTab: ((query: String, private: Boolean) -> Unit)? = null

    /**
     * The link / image menu currently raised over a page, set by
     * [BrowserWebViewHost] on a long-press and drawn by [BrowserScreen]
     * (which also drops it the moment it goes stale — see
     * [PageContextMenuRequest]). `null` when no menu is up.
     */
    internal var pageContextMenu: PageContextMenuRequest? by mutableStateOf(null)

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
     * "open link in new tab" flow). With [activate] false the tab opens
     * behind the current one, which stays on screen. With [private] it
     * is a private tab (#86, see [BrowserState.private]).
     */
    fun newTab(url: String? = null, activate: Boolean = true, private: Boolean = false): BrowserState {
        val tab = newBlankTab(private)
        tabs.add(tab)
        if (activate) activeIndex = tabs.lastIndex
        if (url != null) tab.loadUrl(url)
        return tab
    }

    /**
     * Adopt a window the page in [opener] asked for — a `target=_blank`
     * link or `window.open()` (WebView's `onCreateWindow`) — as a new
     * tab, placed right after its opener and made active. The caller
     * hands the tab's WebView back to Chromium, which then loads the
     * popup's URL into it itself: nothing is scheduled here, and the
     * `window.opener` link to the page that asked stays intact (OAuth-
     * style popups post their result back through it).
     *
     * A private tab's popup is private too: Chromium hands the popup
     * the opener's session, and its WebView has to be on the same
     * profile for that.
     */
    fun adoptPopup(opener: BrowserState): BrowserState {
        val tab = newBlankTab(opener.private)
        tab.openerId = opener.id
        // Its blank document is the page's until something commits: not
        // the home overlay (see [BrowserState.blankIsPage]).
        tab.blankIsPage = true
        tab.showBlankPage()
        val openerIndex = tabs.indexOfFirst { it.id == opener.id }
        val at = if (openerIndex < 0) tabs.size else openerIndex + 1
        captureActiveThumbnail?.invoke()
        // Fullscreen belongs to the active tab only (see [fullscreen]):
        // a player's own `_blank` link must not leave the opener's
        // session covering the popup that is now on screen.
        exitFullscreen()
        tabs.add(at, tab)
        activeIndex = at
        return tab
    }

    /**
     * The page in [tab] closed its own window (`window.close()`,
     * WebView's `onCloseWindow`) — typically an OAuth popup that has
     * posted its result to its opener. The tab goes away; if it was the
     * one on screen, the user lands back on the opener when that is
     * still open, and a background popup closing leaves the active tab
     * where it is.
     *
     * Only a window a page opened can be closed this way. Chromium also
     * honours `window.close()` in a tab whose session history has a
     * single entry (e.g. right after "Clear cookies & site data" clears
     * every tab's history), which would let any page's script silently
     * close a tab the user opened — so a tab with no opener ignores it.
     */
    fun closePopup(tab: BrowserState) {
        if (tab.openerId == null) return
        val index = tabs.indexOfFirst { it.id == tab.id }
        if (index < 0) return
        val wasActive = index == activeIndex
        val previouslyActive = active
        // The page closed it, not the user: nothing to undo (and a
        // finished OAuth popup brought back would have lost the
        // `window.opener` it was for).
        closeTab(index, remember = false)
        val back = if (wasActive) tab.openerId else previouslyActive.id
        val target = tabs.indexOfFirst { it.id == back }
        if (target >= 0) activeIndex = target
    }

    fun switchTo(index: Int) {
        if (index !in tabs.indices) return
        if (index != activeIndex) captureActiveThumbnail?.invoke()
        activeIndex = index
    }

    /**
     * Close the tab at [index]. With [remember] (the user closing it)
     * the tab goes onto the reopen stack ([reopenClosedTab]); a window
     * the page closed itself ([closePopup]) doesn't, and neither does a
     * private tab: closing it is the end of it (#86).
     */
    fun closeTab(index: Int, remember: Boolean = true) {
        if (index !in tabs.indices) return
        // Let the WebView wind its fullscreen session down before the
        // host destroys it (see [BrowserWebViewHost]).
        if (fullscreen?.tabId == tabs[index].id) exitFullscreen()
        // The list is never empty: the last tab is replaced by a blank one.
        val placeholder = if (tabs.size == 1) newBlankTab() else null
        if (remember && !tabs[index].private) rememberClosed(index, placeholder?.id)
        tabs.removeAt(index)
        if (placeholder != null) {
            tabs.add(placeholder)
            activeIndex = 0
            return
        }
        activeIndex = (if (index >= tabs.size) tabs.lastIndex else index)
            .coerceIn(0, tabs.lastIndex)
    }

    /**
     * Push the tab at [index] onto the reopen stack. Only a tab with
     * nothing in it is skipped (the same rule as desktop's Ctrl+Shift+T
     * stack): the home overlay with no back/forward history and nothing
     * submitted. A tab closed before its first page committed (an
     * address submitted, `url` still blank) is kept and comes back
     * loading that address; a tab sent Home after browsing is kept for
     * its history.
     */
    private fun rememberClosed(index: Int, placeholderId: Long?) {
        val tab = tabs[index]
        // A popup nothing has committed in yet comes back as the blank
        // home entry (see [BrowserState.restorableAddress]).
        val (url, address) = tab.restorableAddress()
        if (url.isBlank() && address.isBlank() && !tab.canGoBack && !tab.canGoForward) return
        closedTabs.add(
            ClosedTab(
                index = index,
                url = url,
                title = tab.title,
                addressBarText = address,
                override = tab.override,
                thumbnail = tab.thumbnail,
                webViewState = saveWebViewState?.invoke(tab),
                loadStopped = tab.loadAborted,
                placeholderId = placeholderId,
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
            // Closed before its page committed: the saved state ends on
            // the blank entry, so the address goes back — and is
            // submitted again, unless the user had stopped that load
            // (the bar then showed it with Reload).
            pendingRestore = BrowserState.PendingRestore.of(
                url = closed.url,
                address = closed.addressBarText,
                loadStopped = closed.loadStopped,
                webViewState = closed.webViewState,
            )
        }
        // Undoing the close of the last tab: the blank tab [closeTab]
        // put in its place was only there so the list isn't empty. If
        // it's still untouched, the reopened tab takes its place.
        // Tied to this entry, so reopening some later-closed tab
        // leaves it alone.
        val placeholder = closed.placeholderId?.let { id ->
            tabs.indexOfFirst { it.id == id && it.isUntouched() }
        } ?: -1
        if (placeholder >= 0) tabs.removeAt(placeholder)
        val at = closed.index.coerceIn(0, tabs.size)
        if (tabs.isNotEmpty()) captureActiveThumbnail?.invoke()
        tabs.add(at, tab)
        activeIndex = at
        return tab
    }

    /** Still the fresh home overlay it was created as. */
    private fun BrowserState.isUntouched(): Boolean =
        isHome && !canGoBack && !canGoForward && !resolving && progress < 0

    /**
     * The first load of this tab list — the homepage, or the App Link
     * the app was cold-started from — has been submitted. Kept here
     * rather than in the screen, so a screen rebuilt over tabs that
     * outlived it (#183) doesn't submit it again into the active tab.
     */
    var initialLoadDone: Boolean = false

    /**
     * Every tab's WebView is about to be destroyed while the tabs
     * themselves live on: the Activity is being relaunched for a change
     * `configChanges` can't cover, such as a resource-overlay switch
     * (navigation mode, wallpaper colours; #183). [saveState] is the
     * WebView's own [android.webkit.WebView.saveState] for a tab, which
     * the next host restores the tab from, as for [reopenClosedTab] —
     * the tab comes back on its page with its back/forward history.
     *
     * Fullscreen and the page context menu belonged to the destroyed
     * views and are dropped.
     */
    fun parkForRelaunch(saveState: (BrowserState) -> Bundle?) {
        exitFullscreen()
        pageContextMenu = null
        for (tab in tabs) {
            // Reopened, and the host never got to build its WebView:
            // what it was to be rebuilt from still stands.
            if (tab.pendingRestore == null) {
                val (url, address) = tab.restorableAddress()
                tab.pendingRestore = BrowserState.PendingRestore.of(
                    url = url,
                    address = address,
                    loadStopped = tab.loadAborted,
                    webViewState = saveState(tab),
                )
            }
            tab.webViewLost()
        }
    }

    /**
     * What survives the app's process being killed in the background
     * (#183): each regular tab's page and title, and which of them was
     * active. Private tabs are left out — nothing of theirs outlives
     * the process (#86). Their back/forward history and the reopen stack
     * aren't kept either: WebView state bundles are too large for the
     * saved-instance-state transaction.
     */
    class SavedTabs(val tabs: List<SavedTab>, val activeIndex: Int)

    class SavedTab(
        val url: String,
        val title: String,
        val address: String,
        val loadStopped: Boolean,
    )

    fun saveForProcessDeath(): SavedTabs {
        val kept = tabs.filter { !it.private }
        val at = activeIndex.coerceIn(0, tabs.lastIndex)
        val before = tabs.take(at).count { !it.private }
        // A private tab was on screen: the regular tab before it (or the
        // first) comes back active.
        val activeAt = if (tabs[at].private) (before - 1).coerceAtLeast(0) else before
        return SavedTabs(
            tabs = kept.map { tab ->
                val (url, address) = tab.restorableAddress()
                SavedTab(url = url, title = tab.title, address = address, loadStopped = tab.loadAborted)
            },
            activeIndex = activeAt,
        )
    }

    /**
     * Replace the tab list with [saved], from a process killed in the
     * background (#183). Each tab shows its page's address and title
     * from the first frame and loads that address once its WebView is
     * up (see [BrowserState.PendingRestore]). The initial load is done:
     * these are the tabs the user had.
     */
    fun restoreAfterProcessDeath(saved: SavedTabs) {
        if (saved.tabs.isEmpty()) return
        val restored = saved.tabs.map { s ->
            newBlankTab().apply {
                url = s.url
                title = s.title
                addressBarText = s.address
                pendingRestore = BrowserState.PendingRestore.of(
                    url = s.url,
                    address = s.address,
                    loadStopped = s.loadStopped,
                    webViewState = null,
                )
            }
        }
        tabs.clear()
        tabs.addAll(restored)
        activeIndex = saved.activeIndex.coerceIn(0, tabs.lastIndex)
        initialLoadDone = true
    }

    /**
     * Forget every closed tab. Their saved WebView state carries
     * back/forward history (and the entries keep page titles, URLs and
     * thumbnails), so both "Clear history" and "Clear cookies & site
     * data" drop it.
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

    /** Any private tab (#86) is open. */
    val hasPrivateTabs: Boolean
        get() = tabs.any { it.private }

    private fun newBlankTab(private: Boolean = false): BrowserState =
        BrowserState(id = idSeq.incrementAndGet(), private = private)

    companion object {
        /** How many closed tabs [reopenClosedTab] can walk back through. */
        const val MAX_CLOSED_TABS = 20

        /**
         * Process-wide, not per [TabsState]: the process-scoped
         * [DownloadManager] keys pending download offers by tab id, and
         * a rebuilt screen's new tabs mustn't inherit the old ones'.
         */
        private val idSeq = AtomicLong(0L)
    }
}
