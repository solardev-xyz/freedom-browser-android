package baby.freedom.mobile.browser

import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.Job

/**
 * Observable state for a single browser tab.
 * The [android.webkit.WebView] itself holds the canonical navigation state;
 * this class mirrors just enough for Compose to reflect it in the chrome.
 *
 * [id] is a process-unique, stable identifier so the multi-tab host can
 * map a tab to its physical [android.webkit.WebView] instance across
 * recompositions. It should never be reused within a session.
 *
 * [private] marks a private tab (#86), fixed for the tab's life: its
 * WebView runs on the throwaway [PrivateProfile] (its own cookies,
 * storage and cache, deleted once the last private tab closes), and
 * nothing it browses is written to history, the favicon cache,
 * remembered zoom levels, remembered site permissions or the download
 * list on disk. It never goes on the reopen-closed-tab stack.
 */
class BrowserState(val id: Long, val private: Boolean = false) {
    /**
     * The tab whose page opened this one as a new window (`target=_blank`,
     * `window.open()`; see [TabsState.adoptPopup]), or null for a tab the
     * user opened. Closing the popup from script returns to it.
     */
    var openerId: Long? = null
        internal set

    /**
     * `about:blank` in this tab is the *page's* document, not our home
     * sentinel. True for a popup ([TabsState.adoptPopup]) until it first
     * commits a real URL or the user takes it Home: the page that opened
     * it may be writing into its blank document (`window.open('')` +
     * `document.write`) or about to navigate it, and in either case the
     * home overlay must not cover it. While set, the WebView client
     * shows the blank document as the page `about:blank` (see
     * [showBlankPage]) instead of clearing the tab back to home.
     * Plain field: the chrome reads [isHome], which this feeds through
     * [url]/[addressBarText].
     */
    internal var blankIsPage: Boolean = false

    /** Present the WebView's `about:blank` as a page; see [blankIsPage]. */
    internal fun showBlankPage() {
        url = ABOUT_BLANK
        addressBarText = ABOUT_BLANK
    }

    /**
     * Active per-tab address-bar rewrite. While set, any actual URL
     * starting with [baseUrl] is shown as `prefix + tail` — used to keep
     * `<name>/path` (or the scheme-constrained `bzz://<name>/path`)
     * visible while browsing under the resolved content manifest.
     *
     * Separate from the process-wide [KnownEnsNames] registry: the
     * override only catches in-manifest navigation within a tab, the
     * registry catches raw `bzz://<hash>` loads of a previously-resolved
     * hash in any tab.
     */
    data class Override(val baseUrl: String, val prefix: String)

    var url by mutableStateOf("")
        internal set
    var title by mutableStateOf("")
        internal set

    /**
     * The tab's *committed* address: the display form of the page the
     * WebView loaded, or of a URL the user has just submitted. This is
     * what the address bar presents as "the site you are on" (the
     * resting domain label in [BottomToolbar] reads it), so nothing but
     * a navigation or a submit may write it — in-progress typing lives
     * in the address field's own edit buffer until it is submitted.
     */
    var addressBarText by mutableStateOf("")

    /**
     * The tab is showing the home overlay: no committed page and nothing
     * pending in the address bar. Back has nowhere to go from here unless
     * the WebView has history (see [backActionFor]).
     */
    val isHome: Boolean get() = url.isBlank() && addressBarText.isBlank()

    /** 0..100, or -1 when idle. */
    var progress by mutableIntStateOf(-1)

    /**
     * Indicates this tab is in the indeterminate pre-navigation phase:
     * ENS name being resolved, or peer-warmup [GatewayProbe] still
     * running. Drives the top LinearProgressIndicator independently
     * of [progress] (which is the WebView's 0..100 load counter and
     * only starts ticking once an actual URL is handed to the
     * WebView). Scoped per-tab so a background tab resolving in the
     * background doesn't animate a spinner on whatever tab the user
     * is currently viewing.
     */
    var resolving by mutableStateOf(false)
        internal set

    /**
     * The "Continue once" the tab's *not cross-checked* warning offers,
     * if that's what it is showing (#96, see [EnsGate]). Replaced by the
     * next such warning; used up by the Continue it was made for.
     */
    internal var ensGate: EnsGate? = null

    /**
     * This tab's current (or pending) load is content the embedded IPFS
     * node serves — an `ipfs://` / `ipns://` page, or an ENS name whose
     * contenthash points there. While it is and the tab is busy, the
     * chrome polls the node's retrieval progress and shows which phase
     * the fetch is in (#94). Kept by [loadUrl] and navigation commit via
     * [ipfsLoadFor], and set outright by the probe-gated submit path,
     * which is the one place that knows where an ENS name leads.
     */
    var ipfsLoad by mutableStateOf(false)
        internal set

    /**
     * Bumped each time a new navigation starts on this tab — a submit
     * (typed, bookmark, reload button, detoured link), a main-frame link
     * the WebView follows itself, Back / Forward, and pull-to-refresh.
     * Not by the probe → WebView hand-off inside one submit, which is
     * the same load. The IPFS phase line (#94) keys its counter reading
     * on it, so a load that supersedes one still in flight is measured
     * from its own start, not the previous load's.
     */
    var loadGeneration by mutableIntStateOf(0)
        private set

    /**
     * The [loadGeneration] of the navigation the WebView itself was last
     * handed. It trails [loadGeneration] while a submit is still in its
     * probe phase — the WebView is still on (or still fetching) the
     * previous load then. Read on the request-interceptor thread, so a
     * late main-frame request of the outgoing navigation is attributed
     * to that navigation and not to the submit that is still probing.
     */
    @Volatile
    internal var webViewGeneration: Int = 0
        private set

    /**
     * The [loadGeneration] the WebView's subresource requests belong to:
     * that of the last navigation whose main-frame answer the
     * interceptor handed to Chromium ([mainFrameAnswered]), or that a
     * same-document history step adopted ([historyUpdated]).
     *
     * Advanced on the interceptor thread as the answer goes out, not at
     * `onPageStarted`: Chromium starts the new document's subresource
     * fetches as soon as it has the main-frame response, well before
     * the posted `onPageStarted` runs on the UI thread, so a commit-time
     * advance would file a load's own requests under the load before it
     * (R3-F1). The cost is the other way round and bounded: a request
     * the outgoing document starts between that answer and the commit
     * counts as the new load's.
     */
    @Volatile
    internal var documentGeneration: Int = 0
        private set

    /** The [webViewGeneration] of the last main-frame request seen. */
    @Volatile
    private var mainFrameGeneration: Int = 0

    private val documentLock = Any()

    /** This tab's open gateway requests, by load (see [GatewayWork]). */
    internal val gatewayWork = GatewayWork()

    /**
     * Mark the start of a new navigation (see [loadGeneration]).
     * [inWebView]: the WebView is already navigating (a link it follows,
     * a reload) rather than waiting for a probe to hand it the URL.
     */
    internal fun beginLoad(inWebView: Boolean = false) {
        loadGeneration++
        if (inWebView) webViewGeneration = loadGeneration
    }

    /** The WebView is being handed this tab's current navigation. */
    internal fun handLoadToWebView() {
        webViewGeneration = loadGeneration
    }

    /**
     * The WebView is requesting a main frame (interceptor thread): the
     * request belongs to the navigation it was last handed, whose
     * generation this returns.
     */
    internal fun mainFrameRequested(): Int {
        val generation = webViewGeneration
        synchronized(documentLock) {
            if (generation > mainFrameGeneration) mainFrameGeneration = generation
        }
        return generation
    }

    /**
     * The main-frame answer of navigation [generation] is being handed
     * to Chromium (interceptor thread), so the subresource requests from
     * here on are that navigation's (see [documentGeneration]).
     *
     * [replacesDocument] false — a 204, an attachment: the navigation
     * ends there and the document on screen stays. It is this load's
     * document now, so its open requests move to [generation] too
     * instead of counting as a superseded load's for the rest of the
     * load (R3-F2).
     */
    internal fun mainFrameAnswered(generation: Int, replacesDocument: Boolean) {
        synchronized(documentLock) {
            val kept = documentGeneration
            if (generation <= kept) return
            documentGeneration = generation
            if (replacesDocument) {
                replacedDocument = kept
            } else {
                replacedDocument = null
                gatewayWork.retag(from = kept, to = generation)
            }
        }
    }

    /**
     * The [documentGeneration] a main-frame answer took over from while
     * that answer's document has yet to commit: the page still on
     * screen. Null once it commits or the answer kept the page anyway.
     */
    private var replacedDocument: Int? = null

    /**
     * The last main-frame answer committed a new document (UI thread,
     * `onPageStarted`): the page it took over from is gone.
     */
    internal fun documentCommitted() {
        synchronized(documentLock) { replacedDocument = null }
    }

    /**
     * The last main-frame answer, taken for a new document, went to the
     * download listener instead (UI thread). Chromium downloads every
     * type it can't render, not only what [mainFrameAnswerReplacesDocument]
     * can tell from the headers (an inline `application/zip`, say), so
     * the page on screen stayed after all: adopt its open requests the
     * way [mainFrameAnswered] does for a known non-replacing answer
     * (R2-F1). Likewise when a later redirect hop of that navigation is
     * cancelled as a link to another app (#85): nothing commits.
     */
    internal fun mainFrameKeptPage() {
        synchronized(documentLock) {
            val kept = replacedDocument ?: return
            replacedDocument = null
            gatewayWork.retag(from = kept, to = documentGeneration)
        }
    }

    /**
     * The WebView updated its history (UI thread). With no main-frame
     * request for the navigation it was last handed, that navigation
     * was a same-document one — Back / Forward onto a hash or
     * `pushState` entry — and the document on screen is now that
     * load's (R3-F2). [isHome]: the `about:blank` home entry, which is
     * loaded without a request but does replace the document.
     */
    internal fun historyUpdated(isHome: Boolean) {
        if (isHome) return
        val generation = webViewGeneration
        if (mainFrameGeneration >= generation) return
        mainFrameAnswered(generation, replacesDocument = false)
    }

    /**
     * True between a Stop tap and the tab's next navigation.
     *
     * Chromium answers `stopLoading()` on an *uncommitted* navigation
     * with one last `onProgressChanged` carrying whatever percentage
     * the aborted fetch had reached — and then, because that document
     * never commits and never finishes, nothing further. Left alone,
     * that single late callback re-lights the capsule's edge trace and
     * puts the Stop control back in the trailing slot for good (#41).
     * It is the same late-progress quirk the home path already guards
     * against in [BrowserWebViewHost]'s chrome client; this latch is
     * the general form of that guard.
     *
     * Set by [stopProgress], cleared by anything that starts a fresh
     * load: [loadUrl], a renderer-initiated main-frame navigation, and
     * navigation commit itself.
     */
    var loadAborted by mutableStateOf(false)
        internal set

    /**
     * The WebView URL of the navigation the user named, scheduled as
     * [pendingUrl] by their submit's own [loadUrl] (`namedByUser`),
     * until the WebView takes it ([takeUserNamedLoad]).
     */
    private var userNamedPendingUrl: String? = null

    /**
     * Whether the load of [url] the tab's WebView is starting from
     * [pendingUrl] is the one the user named: its server redirects may
     * then end in a link to another app without a tap on any page
     * (#173, see [externalLinkVerdict]). One load's worth: taken here.
     */
    internal fun takeUserNamedLoad(url: String): Boolean =
        (userNamedPendingUrl == url).also { userNamedPendingUrl = null }

    /**
     * The site-permission prompt this tab is waiting on (#81), or null.
     * Owned by [SitePermissionBroker]; [BrowserScreen] shows it while
     * this tab is the active one, so a background tab can never put a
     * prompt over the page the user is looking at.
     */
    var permissionPrompt: PermissionPrompt? by mutableStateOf<PermissionPrompt?>(null)
        internal set

    var canGoBack by mutableStateOf(false)
        internal set
    var canGoForward by mutableStateOf(false)
        internal set

    /**
     * Bumped whenever we want the WebView to navigate to [pendingUrl].
     * The Compose-side `AndroidView` reads this via a side-effect keyed on
     * [navCounter] so the same URL can be re-entered and still triggers a load.
     */
    var navCounter by mutableIntStateOf(0)
        private set

    var pendingUrl: String = ""
        private set

    var override: Override? by mutableStateOf<Override?>(null)
        internal set

    /**
     * What a tab brought back by [TabsState.reopenClosedTab] should be
     * rebuilt from: the closed WebView's saved state, the URL to
     * bring back afresh if that state can't be restored, and — for a
     * tab closed before its page committed — the address to put back
     * even after a successful restore ([resubmitUrl], blank otherwise).
     * [submit] is false when that first navigation was one the user
     * had stopped: the address comes back in the bar, with Reload, but
     * isn't fetched again. Consumed (and cleared) by
     * [BrowserWebViewHost] when it creates this tab's WebView; null for
     * every other tab.
     */
    class PendingRestore(
        val webViewState: Bundle?,
        val fallbackUrl: String,
        val resubmitUrl: String = "",
        val submit: Boolean = true,
    ) {
        /**
         * What the rebuilt WebView should do once its blank entry
         * finishes: [restored] says whether `restoreState` took the
         * saved state (else the host loaded the blank entry itself),
         * [currentEntryUrl] is the entry the WebView is now on. Null
         * unless there is an address to put back *and* the WebView is
         * on the blank entry — an address left armed while it sits on
         * a real page would fire on some later trip Home.
         */
        fun afterBlank(restored: Boolean, currentEntryUrl: String?): AfterBlank? {
            val address = if (restored) resubmitUrl else fallbackUrl
            val onBlank = currentEntryUrl == null || currentEntryUrl == ABOUT_BLANK
            return if (address.isNotBlank() && onBlank) AfterBlank(address, submit) else null
        }
    }

    internal var pendingRestore: PendingRestore? = null

    /**
     * An address to put back once the WebView's blank home entry has
     * finished loading — and, if [submit], to submit. Set by
     * [BrowserWebViewHost] for a reopened tab whose restored (or
     * unrestorable) state leaves the WebView *on* that entry, and
     * consumed by the first `onPageFinished` that follows, whichever
     * entry it is for: only the blank one acts on it.
     */
    class AfterBlank(val address: String, val submit: Boolean)

    internal var afterBlank: AfterBlank? = null

    /**
     * Compact-on-scroll state of the floating capsule for this tab.
     * Fed by the tab's WebView scroll callbacks (see
     * [BrowserWebViewHost]) and read by the chrome in [BrowserScreen].
     * Per-tab, because scroll position is: switching to a tab the user
     * left at the top of a page shows that tab's chrome at rest.
     */
    internal val capsuleCollapse = CapsuleCollapseState()

    /**
     * This tab's find-in-page session (#83). Per-tab like Chrome's: the
     * bar, query and count belong to the tab, not to the window.
     */
    val find = FindInPageState()

    /**
     * The site the document on screen is zoomed as (#88, [zoomSiteKey]),
     * or null when it isn't a site (home, an error page) and shows at the
     * default level. Set by the tab's WebView at navigation commit.
     */
    var zoomSite: String? by mutableStateOf<String?>(null)
        internal set

    /**
     * Whether the page area stops above the bottom chrome for the
     * document on screen (#66). Set by the tab's WebView from its page's
     * bottom-nav detector (see [BottomChromeSlot]); back to overlay on
     * every new document.
     */
    var bottomChromeMode by mutableStateOf(BottomChromeMode.Overlay)
        internal set

    /**
     * The strip colour under the bar while the page area is shortened (an
     * `rgb(r, g, b)` string, see [bottomStripArgb]): the one the page
     * reported with a reserved mode, or the page's own bottom row sampled
     * for a reveal (#65); null for "none found".
     */
    var bottomStripRgb: String? by mutableStateOf<String?>(null)
        internal set

    /**
     * The document on screen's `<meta name="theme-color">` as opaque
     * ARGB, or null for none (#92): the band behind the status bar takes
     * it. Set by the tab's WebView from [THEME_COLOR_JS] after first
     * paint, load finished, same-document history changes and any
     * `<meta>` change the bottom-UI detector sees (see
     * [ThemeColorSlot]); cleared on the way home.
     */
    var themeColorArgb: Int? by mutableStateOf<Int?>(null)
        internal set

    /**
     * The chrome is busy with the address bar or the keyboard, so a push
     * at the end of the page doesn't reveal it (#65, [revealAllowed]).
     * Written by [BrowserScreen] for the active tab; read by the tab's
     * WebView on touch-down. Plain field: nothing recomposes on it.
     */
    internal var chromeEditing: Boolean = false

    /**
     * The theme surface as opaque ARGB, the colour a reveal (#65) falls
     * back to when its sample of the page's bottom row failed: under the
     * drag, and as the strip (a null [bottomStripRgb] resolves to it,
     * see [bottomStripArgb]). Written by [BrowserScreen]; plain field.
     */
    internal var surfaceArgb: Int = 0xFF000000.toInt()

    /**
     * Something in this tab's pages is audible (#91): a media element
     * playing, unmuted by the page, volume above zero — as the tab's
     * frames report it (see [TabAudioFrames]). Stays true while the tab
     * is muted with [audioMuted]: the page is still playing, the user just
     * doesn't hear it.
     */
    var playingAudio by mutableStateOf(false)
        internal set

    /** The user muted this tab's WebView (#91, [TabsState.setAudioMuted]). */
    var audioMuted by mutableStateOf(false)
        internal set

    /**
     * Most recent page-preview bitmap for this tab, shown in the tab
     * switcher grid. Captured from the live WebView after each successful
     * load and whenever the user opens the switcher (so the thumbnail
     * reflects their current scroll / DOM state). `null` means we don't
     * have a snapshot yet — the card falls back to a letter placeholder.
     */
    var thumbnail: ImageBitmap? by mutableStateOf<ImageBitmap?>(null)
        internal set

    /**
     * In-flight [GatewayProbe] job for this tab (if any). Registered by
     * [BrowserScreen] via [beginPendingProbe] when a bzz / ens-to-bzz
     * navigation enters the "peers warming up" gate, and cleared either
     * by the probe finishing ([finishPendingProbe]) or by
     * [cancelPendingProbe].
     *
     * Held on the per-tab state (rather than, say, a
     * `BrowserScreen`-level `remember`) so that switching tabs while a
     * probe is running doesn't leak the probe into the new tab, and a
     * fresh submit on the same tab cancels the old one cleanly.
     */
    @Volatile
    var pendingProbeJob: Job? = null
        private set

    /**
     * Who asked for the navigation [pendingProbeJob] is gating — the
     * user, or the page currently on screen. `null` when no probe is in
     * flight.
     *
     * Kept next to the job because "may this submit cancel that probe?"
     * is answered from it: a page looping `location.href='ens://…'`
     * would otherwise re-arm the gate on every tick and a typed
     * navigation away from that page could never finish (#35, see
     * [submitSupersedesPendingProbe]).
     */
    @Volatile
    internal var pendingProbeSource: SubmitSource? = null
        private set

    /**
     * The URL [pendingProbeJob] will hand to the WebView if it succeeds,
     * in the loadable form `onPageStarted` reports — so a commit can be
     * told apart from the probe's own navigation (see
     * [commitCancelsPendingProbe]). `null` when no probe is in flight,
     * or when the probe's destination isn't known up front.
     */
    @Volatile
    internal var pendingProbeTarget: String? = null
        private set

    /**
     * Register [job] as this tab's in-flight probe, asked for by
     * [source] and aimed at [target] (the canonical URL it will load —
     * `bzz://…`, `ens://…`; stored in its loadable form). The caller
     * decides whether an existing probe may be superseded (see
     * [submitSupersedesPendingProbe]) and cancels it via
     * [cancelPendingProbe] first.
     */
    internal fun beginPendingProbe(job: Job, source: SubmitSource, target: String? = null) {
        pendingProbeJob = job
        pendingProbeSource = source
        pendingProbeTarget = target?.let { Gateways.toLoadable(it) }
    }

    /**
     * Clear the registration above once [job] has run to completion —
     * but only if it is *still* the tab's probe.
     *
     * A cancelled coroutine's `finally` block runs a beat after the
     * submit that cancelled it has already registered its own probe, so
     * an unconditional clear would deregister the *new* probe and leave
     * the tab looking idle while it is still resolving — which is
     * exactly the state a renderer submit is allowed to cancel.
     */
    internal fun finishPendingProbe(job: Job) {
        if (pendingProbeJob !== job) return
        pendingProbeJob = null
        pendingProbeSource = null
        pendingProbeTarget = null
    }

    /**
     * Cancel any in-flight [pendingProbeJob]. No-op if there isn't one.
     * Called from [loadUrl] so a fresh navigation supersedes whatever
     * probe the tab was previously waiting on, and from navigation
     * commit for a probe the *page* started (#54, see
     * [commitCancelsPendingProbe]).
     */
    fun cancelPendingProbe() {
        val job = pendingProbeJob
        pendingProbeJob = null
        pendingProbeSource = null
        pendingProbeTarget = null
        job?.cancel()
    }

    /**
     * Schedule a navigation. [url] is the *canonical* URL (may be `bzz://…`
     * or `ens://…`); the pending URL handed to the WebView is the rewritten
     * form it can actually fetch — the per-root virtual https origin
     * (`https://<label>.bzz.freedom.baby/…`) for dweb content.
     *
     * If [displayPrefix] is provided, the address bar will show
     * `<displayPrefix>[<path>]` for this navigation and any subsequent
     * navigation under the same content manifest — the bare `name.eth`
     * for generic ENS, or the typed `bzz://name.eth` form for a
     * scheme-constrained ENS load. Passing `null` (the default) leaves
     * any existing override untouched — reload, back, and forward all
     * reuse the current override. Call [clearEnsOverride] to reset.
     *
     * [namedByUser]: this is the load a user's own submit scheduled
     * (#173, [takeUserNamedLoad]). Only [BrowserScreen]'s submit passes
     * it, at the load that submit makes.
     */
    fun loadUrl(url: String, displayPrefix: String? = null, namedByUser: Boolean = false) {
        cancelPendingProbe()
        // A new load supersedes whatever the last Stop aborted, so the
        // progress latch opens again.
        loadAborted = false
        ipfsLoad = ipfsLoadFor(url, ipfsLoad)
        val loadable = Gateways.toLoadable(url)
        pendingUrl = loadable
        // Named by the user only when their submit's own load says so
        // (#173): never an error page, a restore, or a Back step that
        // happens to come after it (R2-F2).
        userNamedPendingUrl = loadable.takeIf { namedByUser }
        if (displayPrefix != null) {
            // The override base is the virtual origin the content is
            // served from — in-manifest navigation stays under it, so
            // prefix substitution keeps `name.eth/path` (or the typed
            // `bzz://name.eth/path`) in the address bar.
            val root = VirtualOrigin.parseHostOfUrl(loadable)
            override = if (root != null) {
                VirtualOrigin.originFor(root)?.let {
                    Override(baseUrl = it, prefix = displayPrefix)
                }
            } else {
                null
            }
        }
        // Optimistically flip `canGoBack` the instant a real navigation
        // is scheduled. The WebView updates its back-stack synchronously
        // inside [WebView.loadUrl], but `onPageStarted` / `onPageFinished`
        // only echo that back asynchronously — so if we waited for them
        // to refresh this flag, a user who tapped a home-page link and
        // then hit the system-back button before the first frame arrived
        // would sail past the disabled `BackHandler` and minimize the
        // app instead of returning to home.
        //
        // `javascript:` URLs never push a history entry, so they can't
        // change the stack; and `about:blank` is our home sentinel (its
        // own lifecycle callbacks reset the flag correctly).
        if (url != HOME_URL && !url.startsWith("javascript:")) {
            canGoBack = true
        }
        navCounter++
    }

    /** Drop any active ENS display override. Call before loading a URL
     *  that the user explicitly typed (and that isn't an ENS name). */
    fun clearEnsOverride() {
        override = null
    }

    /**
     * Reset this tab back to the home state. Clears every last-loaded-
     * page field and navigates the underlying WebView to `about:blank`
     * so the previous page stops drawing — the Compose-side home
     * overlay renders on top of the (now-blank) WebView whenever
     * [url] is empty.
     *
     * [BrowserWebView]'s client treats `about:blank` as a home sentinel
     * in its `onPageStarted` / `onPageFinished`, so the subsequent
     * WebView lifecycle keeps [url] / [title] pinned to empty instead
     * of clobbering them back with display strings for a real page.
     */
    fun navigateHome() {
        // Home is home, even for a popup whose opener left it blank.
        blankIsPage = false
        cancelPendingProbe()
        capsuleCollapse.expand()
        override = null
        url = ""
        title = ""
        addressBarText = ""
        progress = -1
        resolving = false
        ipfsLoad = false
        loadUrl(HOME_URL)
    }

    /**
     * Drop every "this tab is busy" signal the capsule reads, without
     * touching the address or the page itself. Called when the user
     * hits Stop: the actual abort (cancelling an in-flight resolve,
     * telling the WebView to stop fetching) is the caller's job — this
     * is just the part the chrome renders, cleared on the same frame as
     * the tap instead of whenever Chromium's last progress callback
     * happens to arrive.
     */
    fun stopProgress() {
        progress = -1
        resolving = false
        // …and keep it cleared: see [loadAborted] for the late
        // callback this latches out.
        loadAborted = true
    }

    /**
     * Given an address bar string, reconstruct what should actually be
     * fetched, honoring any active display override. When the user hits
     * reload (icon, not a typed URL), we want to reload the real URL
     * under the override, not the friendly form.
     */
    fun effectiveFetchUrl(raw: String): String {
        val o = override ?: return raw
        if (raw.startsWith(o.prefix)) {
            return o.baseUrl + raw.substring(o.prefix.length)
        }
        return raw
    }

    /** Tokens the WebView client should not treat as "new" navigations. */
    fun reset() {
        cancelPendingProbe()
        capsuleCollapse.expand()
        url = ""
        title = ""
        progress = -1
        resolving = false
        loadAborted = false
        ipfsLoad = false
        canGoBack = false
        canGoForward = false
        override = null
        thumbnail = null
    }
}
