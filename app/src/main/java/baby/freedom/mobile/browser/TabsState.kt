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
     * Hook installed by the [BrowserWebViewHost] so Delete browsing data
     * (#400) can wipe every tab's WebView-side state in one shot:
     * `siteData` — cookies, site storage, form data, back/forward stacks,
     * zoom levels, desktop-site choices, unfinished downloads; `cache` —
     * the HTTP cache and the camera captures handed to pages. `null`
     * before the host has composed, or after it disposes.
     */
    @Volatile
    var clearWebViewData: ((siteData: Boolean, cache: Boolean) -> Unit)? = null

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
     * [index] is where it stood in the list when it was closed, not
     * counting tabs the same close took that don't come back (private
     * or empty ones), so it lands between the same neighbours.
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
    )

    /**
     * One entry of the reopen stack: the tabs one close took away — a
     * single tab ([closeTab]), or every tab *Close all tabs* or *Close
     * other tabs* closed (#320), which come back together, each at the
     * position it had ([ClosedTab.index], ascending).
     *
     * [activeAt] is the entry of [tabs] that was the active tab when
     * they closed, so it is active again once they're back; null when
     * the active tab stayed open (or wasn't kept: private, or empty).
     *
     * [placeholderId] is the blank tab put in their place because they
     * were the last tabs, so undoing *this* close (and no other) can
     * drop that tab again if it was never used.
     *
     * [bulk] is whether this was *Close all*, *other* or *private tabs*
     * rather than one tab's own close: reopening a single closed tab
     * makes it active, reopening a bulk close (even of one tab) leaves
     * the active tab alone unless [activeAt] names one of [tabs].
     */
    class ClosedGroup internal constructor(
        val tabs: List<ClosedTab>,
        internal val activeAt: Int?,
        internal val placeholderId: Long?,
        internal val bulk: Boolean = false,
    ) {
        /** Brought back by [reopenClosed] or [reopenClosedTab], not forgotten. Observable. */
        var reopened by mutableStateOf(false)
            internal set
    }

    /**
     * What a bulk close ([closeAllTabs], [closeOtherTabs],
     * [closePrivateTabs]) did: [count] tabs closed, and [undo] the
     * reopen-stack entry that brings back the ones that are kept — null
     * when none is: private tabs are never kept (#86), nor tabs with
     * nothing in them ([rememberClosed]). [single] is one tab's own
     * close ([closeTab]) rather than a bulk one.
     */
    class BulkClose(val count: Int, val undo: ClosedGroup?, val single: Boolean = false)

    /**
     * Most recently closed last. Capped at [MAX_CLOSED_TABS] tabs in
     * all, dropping the oldest entries — except the newest, kept whole
     * even when it alone holds more ([rememberClosed]).
     */
    private val closedTabs = mutableStateListOf<ClosedGroup>()

    /**
     * The entry whose Undo is on screen right now ([BulkClose.undo],
     * until [undoWithdrawn]). The stack's cap never drops it, so a
     * single close made while the notice is up can't leave its Undo
     * with nothing to bring back.
     */
    private var offeredUndo: ClosedGroup? = null

    /**
     * The entry of saved tabs a crashed restore skipped ([keepForReopen]),
     * held until its offer is answered ([offerWithdrawn]). Its own slot,
     * apart from [offeredUndo]: a bulk close's Undo going on screen
     * meanwhile mustn't let the cap drop it — that would end the offer
     * (and delete its disk copy) without the user ever answering it.
     */
    private var heldOffer: ClosedGroup? = null

    /**
     * The Undo notice of [group] is gone (timed out, used, or replaced
     * by a newer one): it may be dropped by the stack's cap again.
     */
    fun undoWithdrawn(group: ClosedGroup) {
        if (offeredUndo === group) {
            offeredUndo = null
            trimClosed()
        }
    }

    /**
     * The offer of [group] ([keepForReopen]) has been answered or has
     * ended: it may be dropped by the stack's cap again.
     */
    fun offerWithdrawn(group: ClosedGroup) {
        if (heldOffer === group) {
            heldOffer = null
            trimClosed()
        }
    }

    /**
     * [group] is still on the reopen stack: not reopened (by its notice
     * or Reopen) or forgotten ([forgetClosedTabs]) yet. Observable.
     */
    fun isOnReopenStack(group: ClosedGroup): Boolean = closedTabs.any { it === group }

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
     * Hook installed by the [BrowserWebViewHost]: drop the in-memory
     * resource cache of the renderer behind the given tab's WebView
     * (`WebView.clearCache(false)`; the disk cache stays). The page
     * menu's ad-blocking switch calls it before the reload that turns
     * blocking back on for a site: Blink reuses an image it already
     * holds in memory without a request, so `shouldInterceptRequest`
     * never sees it and an ad fetched while the site was allowed would
     * otherwise stay on the page. `null` before the host has composed,
     * or after it disposes.
     */
    @Volatile
    var dropMemoryCache: ((BrowserState) -> Unit)? = null

    /**
     * Hook installed by the [BrowserWebViewHost]: the certificate of the
     * page on screen in the given tab (`WebView.getCertificate()`), for
     * Page info (#442), or null where there is none (not https, a page
     * the app answered itself, no WebView yet). `null` before the host
     * has composed, or after it disposes.
     */
    @Volatile
    internal var pageCertificate: ((BrowserState) -> CertFacts?)? = null

    /**
     * Hook installed by the [BrowserWebViewHost]: have the given tab's
     * document, if it is on the given origin, clear that origin's data
     * and unregister its service workers itself, then reload
     * ([siteDataInPageJs]) — Page info's Delete data (#442). Whether it
     * was asked to; `false` when the tab has no WebView or its committed
     * document is on another origin. `null` before the host has
     * composed, or after it disposes.
     */
    @Volatile
    internal var cleanSiteInPage: ((BrowserState, String) -> Boolean)? = null

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
        if (activate) {
            // Fullscreen belongs to the active tab only (see [fullscreen]):
            // a link from another app arriving mid-video must not open
            // its tab under the old page's fullscreen view.
            exitFullscreen()
            activeIndex = tabs.lastIndex
        }
        if (url != null) tab.loadUrl(url)
        return tab
    }

    /**
     * Adopt a window the page in [opener] asked for — a `target=_blank`
     * link or `window.open()` (WebView's `onCreateWindow`) — as a new
     * tab, placed right after its opener and made active — or, with
     * [activate] false, opened behind the active tab (a no-gesture
     * window of a site allowed pop-ups whose page isn't on screen, #261:
     * a timer mustn't switch the tab under the user). The caller
     * hands the tab's WebView back to Chromium, which then loads the
     * popup's URL into it itself: nothing is scheduled here, and the
     * `window.opener` link to the page that asked stays intact (OAuth-
     * style popups post their result back through it).
     *
     * A private tab's popup is private too: Chromium hands the popup
     * the opener's session, and its WebView has to be on the same
     * profile for that.
     */
    fun adoptPopup(opener: BrowserState, activate: Boolean = true): BrowserState {
        val tab = newBlankTab(opener.private)
        tab.openerId = opener.id
        // Its blank document is the page's until something commits: not
        // the home overlay (see [BrowserState.blankIsPage]).
        tab.blankIsPage = true
        // …and that document is the opener's origin's: its own permission
        // requests are asked in that site's name (#363). The opener's
        // own top, not its committed origin: an opener that is itself a
        // script-written blank popup has no committed origin, but its
        // document — and so this one's — is still its opener's site.
        tab.blankOpenerOrigin = opener.permissionTop
        tab.showBlankPage()
        val openerIndex = tabs.indexOfFirst { it.id == opener.id }
        val at = if (openerIndex < 0) tabs.size else openerIndex + 1
        if (!activate) {
            // Behind: the tab on screen stays the active one, wherever
            // the new tab lands relative to it.
            tabs.add(at, tab)
            if (at <= activeIndex) activeIndex++
            return tab
        }
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
     * single entry (e.g. right after *Delete browsing data* clears
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
        if (index != activeIndex) {
            captureActiveThumbnail?.invoke()
            // As in [newTab]: the tab leaving the screen takes its
            // fullscreen session with it.
            exitFullscreen()
        }
        activeIndex = index
    }

    /**
     * Close the tab at [index]. With [remember] (the user closing it)
     * the tab goes onto the reopen stack ([reopenClosedTab]); a window
     * the page closed itself ([closePopup]) doesn't, and neither does a
     * private tab: closing it is the end of it (#86) — its WebView goes,
     * and with the last private tab the whole private session, so there
     * is nothing an Undo could bring back.
     *
     * With [offerUndo] (the switcher's ×, #418) the caller puts a
     * "Tab closed · Undo" notice on screen for the returned
     * [BulkClose.undo], held like a bulk close's ([undoWithdrawn]).
     * Without it (Ctrl+W) the tab is only on the reopen stack.
     */
    fun closeTab(index: Int, remember: Boolean = true, offerUndo: Boolean = false): BulkClose {
        if (index !in tabs.indices) return BulkClose(0, null)
        val closing = tabs[index]
        return closeWhere(remember, bulk = false, offerUndo = offerUndo) { it === closing }
    }

    /**
     * The switcher's *Close all tabs* (#320): every tab, normal and
     * private, as closing each in turn would — a fresh blank tab is left,
     * the private session ends with the last private tab, and the normal
     * tabs go onto the reopen stack as one entry, so one Undo (or
     * Reopen) brings them all back in their order.
     */
    fun closeAllTabs(): BulkClose = closeWhere { true }

    /**
     * A tab's *Close other tabs* (#320): every tab but [keep], which is
     * left active. The normal tabs closed come back together on Undo;
     * the private ones don't, and closing a private [keep]'s others
     * keeps the private session it is in.
     */
    fun closeOtherTabs(keep: BrowserState): BulkClose {
        if (tabs.none { it.id == keep.id }) return BulkClose(0, null)
        return closeWhere { it.id != keep.id }
    }

    /**
     * *Close other tabs* from a card in one of the switcher's panes
     * (#418): only the tabs of [keep]'s own kind — the other normal
     * tabs for a normal tab, the other private ones for a private tab —
     * since the other pane's tabs aren't on screen. [keep] is left
     * active.
     */
    fun closeOtherTabsOfItsKind(keep: BrowserState): BulkClose {
        if (tabs.none { it.id == keep.id }) return BulkClose(0, null)
        val closed = closeWhere { it.id != keep.id && it.private == keep.private }
        val at = tabs.indexOfFirst { it.id == keep.id }
        if (at >= 0 && at != activeIndex) switchTo(at)
        return closed
    }

    /**
     * The *Tabs* pane's *Close all tabs* (#418): every normal tab, the
     * private ones staying in their own pane. The normal tabs go onto
     * the reopen stack as one entry, as [closeAllTabs] has them, and a
     * blank normal tab takes their place (active, if a normal tab was) —
     * the active tab doesn't become a private one. With no private tab
     * open it is the same as [closeAllTabs].
     */
    fun closeRegularTabs(): BulkClose = closeWhere { !it.private }

    /**
     * The switcher's *Close private tabs* (#320): the private group
     * only. Ends the private session as closing its last tab does; there
     * is nothing to undo (#86).
     */
    fun closePrivateTabs(): BulkClose = closeWhere { it.private }

    /**
     * Close every tab [closing] picks, in one change to the list (so the
     * host tears their WebViews down together, and the private session
     * with the last private one). With [remember] the non-private ones
     * with something in them go onto the reopen stack as one entry.
     *
     * The list is never left empty: closing every tab puts a blank one
     * in their place, and so does closing the last normal tabs while
     * the active one is among them and private tabs stay open. If the
     * active tab closes, the next tab of its kind — the one that slid
     * into its place, or the nearest before it ([nextActiveOfKind]) —
     * becomes active; otherwise the active tab stays.
     */
    private fun closeWhere(
        remember: Boolean = true,
        bulk: Boolean = true,
        offerUndo: Boolean = bulk,
        closing: (BrowserState) -> Boolean,
    ): BulkClose {
        val indices = tabs.indices.filter { closing(tabs[it]) }
        if (indices.isEmpty()) return BulkClose(0, null)
        val oldActive = activeIndex.coerceIn(0, tabs.lastIndex)
        val activeId = tabs[oldActive].id
        for (i in indices) {
            val tab = tabs[i]
            // Let the WebView wind its fullscreen session down before the
            // host destroys it (see [BrowserWebViewHost]).
            if (fullscreen?.tabId == tab.id) exitFullscreen()
            // A dialog its page is blocked on is answered, not left for a
            // WebView about to be destroyed (#246).
            tab.jsDialog?.withdraw()
        }
        val activeCloses = oldActive in indices
        val activePrivate = tabs[oldActive].private
        // The list is never empty: the last tabs are replaced by a blank
        // one. Nor is a normal active tab's place handed to a private
        // tab (#418): closing the last normal tabs while private ones
        // stay open puts a blank normal tab in their place too, so the
        // tab on screen after the switcher isn't private by surprise.
        val noneLeft = indices.size == tabs.size
        val noNormalLeft = activeCloses && !activePrivate &&
            tabs.indices.none { it !in indices && !tabs[it].private }
        val placeholder = if (noneLeft || noNormalLeft) newBlankTab() else null
        val undo = if (remember) rememberClosed(indices, oldActive, placeholder?.id, bulk, offerUndo) else null
        for (i in indices.asReversed()) tabs.removeAt(i)
        // The survivors before the active tab keep their places: the one
        // after them is the tab that slid into its place.
        val before = oldActive - indices.count { it < oldActive }
        if (placeholder != null) {
            val at = before.coerceIn(0, tabs.size)
            tabs.add(at, placeholder)
            activeIndex = at
        } else if (activeCloses) {
            activeIndex = nextActiveOfKind(before, activePrivate)
        } else {
            // Shifts left by however many closed before it.
            activeIndex = tabs.indexOfFirst { it.id == activeId }.coerceIn(0, tabs.lastIndex)
        }
        return BulkClose(indices.size, undo, single = !bulk)
    }

    /**
     * Which tab takes over from a closed active tab of kind [private]:
     * the first of that kind from [slidInto] (the tab that slid into its
     * place) on, else the nearest one before it — a pane's active tab
     * passes to a tab in the same pane (#418) — and only when none of
     * its kind is left, the tab at [slidInto] (closing the last private
     * tab ends the private session, and a normal tab takes over).
     */
    private fun nextActiveOfKind(slidInto: Int, private: Boolean): Int {
        val from = slidInto.coerceIn(0, tabs.lastIndex)
        val after = (from..tabs.lastIndex).firstOrNull { tabs[it].private == private }
        val beforeIt = (from - 1 downTo 0).firstOrNull { tabs[it].private == private }
        return after ?: beforeIt ?: from
    }

    /**
     * Push the tabs at [indices] (ascending) onto the reopen stack as
     * one entry, and return it — or null if none of them is kept.
     * Private tabs aren't (#86), and neither is a tab with nothing in it
     * (the same rule as desktop's Ctrl+Shift+T stack): the home overlay
     * with no back/forward history and nothing submitted. A tab closed
     * before its first page committed (an address submitted, `url`
     * still blank) is kept and comes back loading that address; a tab
     * sent Home after browsing is kept for its history.
     */
    private fun rememberClosed(
        indices: List<Int>,
        activeAtClose: Int,
        placeholderId: Long?,
        bulk: Boolean,
        offerUndo: Boolean,
    ): ClosedGroup? {
        var activeAt: Int? = null
        val kept = ArrayList<ClosedTab>()
        // Closed tabs that won't come back (private, empty) take no
        // place when the kept ones are reinserted: each kept tab's index
        // counts only the tabs that stayed and the kept ones before it.
        var dropped = 0
        for (index in indices) {
            val tab = tabs[index]
            if (tab.private) { dropped++; continue }
            // A popup nothing has committed in yet comes back as the blank
            // home entry (see [BrowserState.restorableAddress]).
            val (url, address) = tab.restorableAddress()
            if (url.isBlank() && address.isBlank() && !tab.canGoBack && !tab.canGoForward) { dropped++; continue }
            if (index == activeAtClose) activeAt = kept.size
            kept += ClosedTab(
                index = index - dropped,
                url = url,
                title = tab.title,
                addressBarText = address,
                override = tab.override,
                thumbnail = tab.thumbnail,
                webViewState = saveWebViewState?.invoke(tab),
                loadStopped = tab.loadAborted,
            )
        }
        if (kept.isEmpty()) return null
        val group = ClosedGroup(kept, activeAt, placeholderId, bulk)
        closedTabs.add(group)
        // A bulk close's Undo goes on screen ([BulkClose.undo]), and so
        // does a single close's from the switcher (#418); Ctrl+W's is
        // undone by Reopen (Ctrl+Shift+T), not by a notice.
        if (offerUndo) offeredUndo = group
        trimClosed()
        return group
    }

    private fun trimClosed() {
        // Bounded by tabs, not entries: each carries a thumbnail and a
        // saved WebView state, so twenty bulk closes of many tabs each
        // would otherwise pile up. Oldest entries go first; the newest
        // always stays whole, so its Undo brings back every tab it
        // closed (those were all open a moment ago anyway). So does the
        // entry whose Undo is on screen ([offeredUndo]), and the held
        // tabs whose offer is still unanswered ([heldOffer]).
        var held = closedTabs.sumOf { it.tabs.size }
        var i = 0
        while (i < closedTabs.size - 1 && held > MAX_CLOSED_TABS) {
            val pinned = closedTabs[i].let { it === offeredUndo || it === heldOffer }
            if (pinned) i++ else held -= closedTabs.removeAt(i).tabs.size
        }
    }

    /**
     * Bring back the most recently closed tab — or tabs, when the last
     * close was a bulk one (#320) — at the position each was closed
     * from (clamped to the current list). A single closed tab becomes
     * active; a bulk close (even one only a single tab of came back) makes the tab active that was active when it closed, if
     * it's among them. Each WebView is rebuilt from the saved state, so
     * it comes back on the same page with the same back/forward
     * history.
     *
     * Returns the reopened tab (for a group, the one that is active
     * now if it's one of them, else the last one back), or null if
     * there was nothing to reopen.
     */
    fun reopenClosedTab(): BrowserState? {
        val group = closedTabs.removeLastOrNull() ?: return null
        return reopen(group)
    }

    /**
     * The Undo of a close (#320, and a single tab's, #418): bring back
     * [group] if it is still on the reopen stack — not if Reopen already
     * did, or the stack was forgotten ([forgetClosedTabs]) since.
     * Returns whether it was. An Undo puts things back as they were: a
     * tab comes back active only if it was the active one when it
     * closed, otherwise the tab on screen now stays on screen.
     */
    fun reopenClosed(group: ClosedGroup): Boolean {
        if (!closedTabs.remove(group)) return false
        reopen(group, undo = true)
        return true
    }

    private fun reopen(group: ClosedGroup, undo: Boolean = false): BrowserState {
        group.reopened = true
        val activeId = active.id
        // Undoing the close of the last tabs: the blank tab put in their
        // place was only there so the list isn't empty. If it's still
        // untouched, the reopened tabs take its place. Tied to this
        // entry, so reopening some later-closed tab leaves it alone.
        val placeholder = group.placeholderId?.let { id ->
            tabs.indexOfFirst { it.id == id && it.isUntouched() }
        } ?: -1
        if (placeholder >= 0) tabs.removeAt(placeholder)
        if (tabs.isNotEmpty()) captureActiveThumbnail?.invoke()
        // Ascending, so each lands where it was among the tabs that
        // stayed (when those haven't changed since).
        val reopened = group.tabs.map { closed ->
            val tab = restoredTab(closed)
            tabs.add(closed.index.coerceIn(0, tabs.size), tab)
            tab
        }
        val target = when {
            group.activeAt != null -> reopened[group.activeAt]
            !group.bulk && !undo -> reopened[0]
            else -> tabs.firstOrNull { it.id == activeId } ?: reopened[0]
        }
        activeIndex = tabs.indexOfFirst { it.id == target.id }
        return if (target in reopened) target else reopened.last()
    }

    private fun restoredTab(closed: ClosedTab): BrowserState = newBlankTab().apply {
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

    /**
     * Nothing has happened to this tab list yet: the one regular tab it
     * was created with, untouched, and no first load submitted.
     */
    val pristine: Boolean
        get() = !initialLoadDone && tabs.size == 1 && !tabs[0].private && tabs[0].isUntouched()

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
        for (tab in tabs) parkTab(tab, saveState)
    }

    /**
     * [tab]'s WebView is about to be destroyed while the tab lives on:
     * record what to rebuild it from ([BrowserState.pendingRestore]) —
     * the WebView's own saved state ([saveState]), its address and its
     * Stop latch — and drop what only mirrored the WebView. Shared by an
     * Activity relaunch ([parkForRelaunch]) and a tab whose renderer went
     * away ([rendererGone], #260).
     */
    private fun parkTab(tab: BrowserState, saveState: (BrowserState) -> Bundle?) {
        // Reopened, and the host never got to build its WebView:
        // what it was to be rebuilt from still stands.
        if (tab.pendingRestore == null) {
            val (url, address) = tab.restorableAddress()
            tab.pendingRestore = BrowserState.PendingRestore.of(
                url = url,
                address = address,
                loadStopped = tab.loadAborted,
                webViewState = saveState(tab),
                inFlight = tab.uncommittedLoad(),
            )
        }
        tab.webViewLost()
    }

    /**
     * The renderer process [tab]'s page ran in went away (#260) and the
     * host is about to destroy the tab's WebView. The tab stays, parked
     * like one across a relaunch ([parkTab]), and shows why
     * ([BrowserState.rendererGone]) until it gets a new WebView — at once
     * when it's next shown if the user wasn't looking at it, else when
     * they ask (Reload, or any navigation of theirs). They weren't when
     * it's a background tab, or when the app itself was off screen
     * ([appVisible] false) and the renderer was killed for memory: as in
     * Chrome, coming back to the app brings the page back. A crash while
     * away still waits for Reload, so a page that crashes its renderer
     * isn't loaded again and again. Fullscreen and the page context menu
     * go if they were this tab's — fullscreen without a word to the page,
     * which is gone.
     */
    fun rendererGone(
        tab: BrowserState,
        crashed: Boolean,
        appVisible: Boolean = true,
        saveState: (BrowserState) -> Bundle?,
    ) {
        if (fullscreen?.tabId == tab.id) fullscreen = null
        if (pageContextMenu?.tabId == tab.id) pageContextMenu = null
        parkTab(tab, saveState)
        tab.rendererGone = BrowserState.RendererGone(
            crashed = crashed,
            reloadWhenShown = tab !== active || (!appVisible && !crashed),
        )
    }

    /**
     * What survives the app's process being killed in the background
     * (#183): each regular tab's address and title, and which of them
     * was active. Private tabs are left out — nothing of theirs outlives
     * the process (#86). Their back/forward history and the reopen stack
     * aren't kept either: WebView state bundles are too large for the
     * saved-instance-state transaction. Neither is the tab's committed
     * [BrowserState.url]: [committed] only says there was a page, whose
     * [address] loads again.
     */
    data class SavedTabs(val tabs: List<SavedTab>, val activeIndex: Int) {
        /** Nothing worth bringing back: no tab, or one on the home page. */
        fun isJustHome(): Boolean = tabs.isEmpty() || (tabs.size == 1 && tabs[0].address.isBlank())
    }

    data class SavedTab(
        val title: String,
        val address: String,
        val committed: Boolean,
        val loadStopped: Boolean,
    )

    /**
     * The saved instance state goes through a binder transaction with a
     * hard limit of about 1 MB shared by the whole process, and a page
     * can make its own URL (`history.replaceState`) or title as long as
     * it likes. So an address longer than [MAX_SAVED_ADDRESS] isn't
     * kept — that tab doesn't come back — a title is cut to
     * [MAX_SAVED_TITLE] (it's only shown until the page loads again),
     * and the whole list stops at [MAX_SAVED_CHARS], the active tab
     * counted first (R1-F1).
     */
    fun saveForProcessDeath(): SavedTabs {
        val at = activeIndex.coerceIn(0, tabs.lastIndex)
        val candidates = tabs.withIndex().filter { !it.value.private }.mapNotNull { (index, tab) ->
            val (url, address) = tab.restorableAddress()
            val saved = SavedTab(
                title = tab.title.take(MAX_SAVED_TITLE),
                address = address.ifBlank { url },
                committed = url.isNotBlank(),
                loadStopped = tab.loadAborted,
            )
            if (saved.address.length > MAX_SAVED_ADDRESS) null else index to saved
        }
        var budget = MAX_SAVED_CHARS
        val fits = HashSet<Int>()
        for ((index, saved) in candidates.sortedBy { (index, _) -> if (index == at) -1 else index }) {
            val size = saved.address.length + saved.title.length
            if (size > budget) continue
            budget -= size
            fits += index
        }
        val kept = candidates.filter { (index, _) -> index in fits }
        // The active tab isn't kept (private, or too long): the kept tab
        // before it (or the first) comes back active.
        val activeAt = kept.indexOfLast { (index, _) -> index <= at }.coerceAtLeast(0)
        return SavedTabs(tabs = kept.map { it.second }, activeIndex = activeAt)
    }

    /**
     * Replace the tab list with [saved], from a process killed in the
     * background (#183). Each tab shows its page's address and title
     * from the first frame and loads that address once its WebView is
     * up (see [BrowserState.PendingRestore]). Its [BrowserState.url]
     * stays blank until that load commits: the previous process's
     * fetch target can be a loopback gateway URL on a port that's gone
     * (R1-F3). The initial load is done: these are the tabs the user
     * had.
     */
    fun restoreAfterProcessDeath(saved: SavedTabs) {
        if (saved.tabs.isEmpty()) return
        val restored = saved.tabs.map { s ->
            newBlankTab().apply {
                title = s.title
                addressBarText = s.address
                pendingRestore = BrowserState.PendingRestore.of(
                    // Only whether there was a page matters here: the
                    // address is what loads.
                    url = if (s.committed) s.address else "",
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
     * Saved tabs that aren't loaded — the last restore from disk crashed
     * the app ([TabsStore]) — go on the reopen stack instead, as one
     * entry, so the user can still bring them back (Reopen closed tab,
     * or the notice's Restore) once they're past whatever crashed. They
     * come back as [restoreAfterProcessDeath] would have brought them,
     * in place of the home tab if it's still untouched. Returns the
     * entry, or null if there was nothing to keep.
     */
    fun keepForReopen(saved: SavedTabs): ClosedGroup? {
        // Home tabs among them don't come back; the rest close up.
        val kept = saved.tabs.withIndex().filter { it.value.address.isNotBlank() }
        if (kept.isEmpty()) return null
        val group = ClosedGroup(
            tabs = kept.mapIndexed { i, (_, s) ->
                ClosedTab(
                    index = i,
                    url = if (s.committed) s.address else "",
                    title = s.title,
                    addressBarText = s.address,
                    override = null,
                    thumbnail = null,
                    webViewState = null,
                    loadStopped = s.loadStopped,
                )
            },
            activeAt = kept.indexOfFirst { it.index == saved.activeIndex }.takeIf { it >= 0 },
            placeholderId = tabs.singleOrNull()?.takeIf { it.isUntouched() && !it.private }?.id,
            bulk = true,
        )
        closedTabs.add(group)
        // Held until its offer is answered, whatever else is closed and
        // undone meanwhile.
        heldOffer = group
        trimClosed()
        return group
    }

    /**
     * Forget every closed tab. Their saved WebView state carries
     * back/forward history (and the entries keep page titles, URLs and
     * thumbnails), so Delete browsing data drops it with either browsing
     * history or cookies and site data.
     */
    fun forgetClosedTabs() {
        closedTabs.clear()
        offeredUndo = null
        heldOffer = null
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
        /**
         * How many closed tabs the reopen stack keeps, across all its
         * entries: oldest entries are dropped once it holds more. The
         * newest entry is kept whole even past this, so a bulk close
         * (#320) of more tabs can still be undone in full.
         */
        const val MAX_CLOSED_TABS = 20

        /**
         * Bounds on what [saveForProcessDeath] keeps, in chars (two bytes
         * each in the parcel): 32k chars is 64 KB of the ~1 MB binder
         * buffer. An address past 8k chars is rare (browsers and servers
         * commonly cap URLs around there) and is what a page stuffing
         * state into its URL produces.
         */
        const val MAX_SAVED_ADDRESS = 8 * 1024
        const val MAX_SAVED_TITLE = 1024
        const val MAX_SAVED_CHARS = 32 * 1024

        /**
         * Process-wide, not per [TabsState]: the process-scoped
         * [DownloadManager] keys pending download offers by tab id, and
         * a rebuilt screen's new tabs mustn't inherit the old ones'.
         */
        private val idSeq = AtomicLong(0L)
    }
}
