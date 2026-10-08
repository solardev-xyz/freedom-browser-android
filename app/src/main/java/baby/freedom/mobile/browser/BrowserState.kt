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
 * remembered zoom levels, remembered desktop sites, remembered site
 * permissions or the download list on disk. It never goes on the reopen-closed-tab stack.
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

    /**
     * The opener's site-permission top ([permissionTop]: its committed
     * origin, or what its own blank document inherited) when this popup
     * was opened ([TabsState.adoptPopup]). A popup's
     * `about:blank` document inherits the origin of the page that opened
     * it, but commits nothing, so [permissionOrigin] stays null while it
     * shows; this stands in for it as the top-level site of that blank
     * document's own permission requests (see [permissionTop]).
     */
    internal var blankOpenerOrigin: String? = null

    /**
     * The top-level site a site-permission request from this tab's
     * document is decided under (#363): the committed [permissionOrigin],
     * or, for a popup's own blank document ([blankIsPage]), the origin it
     * inherited from its opener ([blankOpenerOrigin]). Null when the page
     * has no site that can hold a permission.
     */
    val permissionTop: String?
        get() = permissionOrigin
            ?: blankOpenerOrigin.takeIf { blankIsPage }

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
     *
     * A bare-name [prefix] (a generic `name.eth` submit) makes no claim
     * about the transport, so what the bar shows ([shown]) follows the
     * name's current answer — `ipfs://vitalik.eth` (#97). A scheme
     * prefix (`bzz://name.eth`, typed) is an assertion
     * ([assertedProtocol]) and is shown as typed.
     */
    data class Override(val baseUrl: String, val prefix: String) {
        /** The prefix as the address bar shows it; see [DisplayUrl.withTransport]. */
        val shown: String get() = DisplayUrl.withTransport(prefix)

        /**
         * Is [url] on [baseUrl] — the base itself, or it followed by a
         * path, query or fragment? Not just a string prefix: the host
         * `<base host>.evil.com` starts with the base too.
         */
        fun covers(url: String): Boolean = startsWithPrefix(url, baseUrl)

        /**
         * `bzz` / `ipfs` / `ipns` when the prefix is a typed-scheme form
         * — the transport this tab's address asserts, which a document
         * re-check holds the name to ([Gateways.reverifyEnsDocument]).
         * `null` for a bare name, which accepts any transport.
         */
        val assertedProtocol: String?
            get() = prefix.substringBefore("://", "").lowercase()
                .takeIf { it in ASSERTING_SCHEMES }
    }

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
     * What the capsule's load bar shows for this tab ([CapsuleLoadBar]),
     * kept with the tab so every bar that shows it — the address field,
     * the find bar — continues the same curve.
     */
    internal val loadMeter = CapsuleLoadMeter()

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
     * How the name the page on screen was reached through was checked
     * (#96) — the trust shield on the protocol badge (#97, [TrustShield]).
     * Taken at each document's commit from the answer it was served
     * from; `null` for a page that isn't a name's (or is an error page).
     */
    internal var nameTrust by mutableStateOf<NameTrust?>(null)

    /**
     * The "Continue once" the tab's *not cross-checked* warning offers,
     * if that's what it is showing (#96, see [EnsGate]). Replaced by the
     * next such warning; used up by the Continue it was made for.
     */
    internal var ensGate: EnsGate? = null

    /**
     * The tab's onchain-app documents (#123): the one its submit flow
     * hands the interceptor, what it served last per app, and the one a
     * not-cross-checked warning is asking about ([OnchainAppTab]).
     */
    val onchain = OnchainAppTab(private)

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
     * Like [loadGeneration], but not bumped by a server redirect hop
     * ([beginLoad]'s `redirect`): one per load the user would call a new
     * one. The capsule's load bar ([CapsuleLoadMeter]) starts over when it
     * changes, and a redirect hop mustn't throw the bar back (R3-F1).
     */
    var loadStarts by mutableIntStateOf(0)
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
     * [redirect]: a server redirect hop of a navigation already under way
     * — a new generation, but not a new [loadStarts].
     */
    internal fun beginLoad(inWebView: Boolean = false, redirect: Boolean = false) {
        loadGeneration++
        if (!redirect) loadStarts++
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
     * The WebView URL of a Hard reload's own load (#262), scheduled as
     * [pendingUrl] by its submit's [loadUrl] (`bypassCache`), until the
     * WebView takes it ([takeBypassCacheLoad]).
     */
    private var bypassCachePendingUrl: String? = null

    /**
     * Whether the load of [url] the tab's WebView is starting from
     * [pendingUrl] is a Hard reload's (#262): it goes out with the HTTP
     * cache bypassed ([CacheBypass]). One load's worth: taken here, so
     * no later load — an error page, a Back step — inherits it.
     */
    internal fun takeBypassCacheLoad(url: String): Boolean =
        (bypassCachePendingUrl == url).also { bypassCachePendingUrl = null }

    /**
     * The document a Hard reload loaded (#262), by the [loadGeneration]
     * of its navigation, and the gateway URLs the interceptor already
     * fetched afresh for it. Null until the tab's first Hard reload;
     * a later navigation has another generation, so it no longer
     * matches. Read on the interceptor's threads.
     */
    @Volatile
    private var freshDocument: FreshDocument? = null

    private class FreshDocument(val generation: Int) {
        val fetched: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    }

    /**
     * The navigation the WebView was just handed ([webViewGeneration])
     * is a Hard reload's: its document's gateway fetches skip the
     * interceptor's own caches ([takeFreshFetch]).
     */
    internal fun bypassCacheForHandedLoad() {
        freshDocument = FreshDocument(webViewGeneration)
    }

    /**
     * Should the interceptor fetch [target] (a gateway URL) for a request
     * of load [generation] past the gateway's caches (#262)? True the
     * first time the Hard-reloaded document asks for it. Its later
     * requests for the same URL (a streamed video's seeks) go past them
     * too ([fetchedFresh], R4-M2). False for every other document, in
     * this tab or any other.
     */
    internal fun takeFreshFetch(generation: Int, target: String): Boolean {
        val doc = freshDocument ?: return false
        return doc.generation == generation && doc.fetched.add(target)
    }

    /**
     * Has the Hard-reloaded document of load [generation] already fetched
     * [target] fresh ([takeFreshFetch])? Its later media requests for it
     * — a streamed video's seeks — still go past the gateway's cache
     * (R4-M2). False for every other document.
     */
    internal fun fetchedFresh(generation: Int, target: String): Boolean {
        val doc = freshDocument ?: return false
        return doc.generation == generation && target in doc.fetched
    }

    /**
     * The site-permission prompt this tab is waiting on (#81), or null.
     * Owned by [SitePermissionBroker]; [BrowserScreen] shows it while
     * this tab is the active one, so a background tab can never put a
     * prompt over the page the user is looking at.
     */
    var permissionPrompt: PermissionPrompt? by mutableStateOf<PermissionPrompt?>(null)
        internal set

    /**
     * The pop-ups this tab's page tried to open without the user's
     * gesture and the app blocked (#261), for the notice [BrowserScreen]
     * shows while this tab is the active one. Cleared by each new
     * document.
     */
    val blockedPopups = BlockedPopups()

    /**
     * The `window.radicle` consent prompt this tab is waiting on (#124),
     * or null. Owned by [RadicleProviders]; shown like [permissionPrompt].
     */
    var radiclePrompt: RadiclePromptRequest? by mutableStateOf<RadiclePromptRequest?>(null)
        internal set

    /**
     * The `window.ethereum` approval sheet this tab is waiting on (#110),
     * or null. Owned by [EthereumProviders]; shown like [permissionPrompt].
     */
    var ethereumPrompt: EthereumPromptRequest? by mutableStateOf<EthereumPromptRequest?>(null)
        internal set

    /**
     * The `window.swarm` approval sheet this tab is waiting on (#120),
     * or null. Owned by [SwarmProviders]; shown like [permissionPrompt].
     */
    var swarmPrompt: SwarmPromptRequest? by mutableStateOf<SwarmPromptRequest?>(null)
        internal set

    /**
     * The JavaScript dialog (`alert`, `confirm`, `prompt`,
     * `beforeunload`) this tab's page is blocked on (#246), or null.
     * Set by the tab's `WebChromeClient`; [BrowserScreen] shows it in
     * turn with the prompts above ([modalPromptTurn]) while this is the
     * active tab. The moment it isn't — or while it's still waiting
     * under a full-screen panel — BrowserScreen answers it with
     * [JsDialogRequest.withdraw] rather than Cancel: `alert` returns,
     * `confirm` is false, and a `beforeunload` the user never saw lets
     * the navigation go (Leave); one they were shown keeps the page
     * (Stay). A dialog already up keeps its turn over a panel.
     */
    internal var jsDialog: JsDialogRequest? by mutableStateOf<JsDialogRequest?>(null)

    /** Whether this tab's pages may still show dialogs ([JsDialogGate], #466). */
    internal val jsDialogGate = JsDialogGate()

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
     * What a tab brought back by [TabsState.reopenClosedTab] (or whose
     * WebView was rebuilt, #183) should be rebuilt from: the closed WebView's saved state, the URL to
     * bring back afresh if that state can't be restored, and — for a
     * tab closed before its page committed — the address to put back
     * even after a successful restore ([resubmitUrl], blank otherwise).
     * [submit] is false when that first navigation was one the user
     * had stopped: the address comes back in the bar, with Reload, but
     * isn't fetched again. [overPage]: the tab had a committed page
     * with a load still in flight over it (an Activity relaunch, #183
     * R1-F2), so [resubmitUrl] goes in once that page is restored.
     * Consumed (and cleared) by [BrowserWebViewHost] when it creates
     * this tab's WebView; null for every other tab.
     */
    class PendingRestore(
        val webViewState: Bundle?,
        val fallbackUrl: String,
        val resubmitUrl: String = "",
        val submit: Boolean = true,
        val overPage: Boolean = false,
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
            if (address.isBlank()) return null
            val onBlank = currentEntryUrl == null || currentEntryUrl == ABOUT_BLANK
            return when {
                onBlank -> AfterBlank(address, submit)
                // A load that hadn't committed over a real page yet
                // (#183 R1-F2): the restored list ends on that page, so
                // the address goes in once *its* reload has finished —
                // whatever URL that ends on (a redirect, R2-F2) — and
                // supersedes it as it did before the relaunch.
                restored && overPage && submit -> AfterBlank(address, submit = true, overPage = true)
                else -> null
            }
        }

        /**
         * The same, with the saved back/forward list dropped: the tab
         * comes back on its address alone, as a WebView whose history was
         * cleared (*Delete browsing data*'s *Cookies and site data*) would.
         */
        fun withoutHistory(): PendingRestore = PendingRestore(
            webViewState = null,
            fallbackUrl = fallbackUrl,
            resubmitUrl = resubmitUrl,
            submit = submit,
            overPage = overPage,
        )

        companion object {
            /**
             * What to rebuild a tab from, given its committed [url], its
             * [address] and its Stop latch ([loadStopped]) as
             * [restorableAddress] reports them. A tab whose page hadn't
             * committed yet gets its address back — submitted again,
             * unless the user had stopped that load. So does a tab with
             * a load still [inFlight] over its committed page (see
             * [uncommittedLoad]), once that page is back.
             */
            fun of(
                url: String,
                address: String,
                loadStopped: Boolean,
                webViewState: Bundle?,
                inFlight: Boolean = false,
            ): PendingRestore {
                val overPage = url.isNotBlank() && inFlight && !loadStopped
                return PendingRestore(
                    webViewState = webViewState,
                    fallbackUrl = address.ifBlank { url },
                    resubmitUrl = address.takeIf { url.isBlank() || overPage }.orEmpty(),
                    submit = !(url.isBlank() && loadStopped),
                    overPage = overPage,
                )
            }
        }
    }

    /**
     * This tab's committed URL and address as a rebuilt tab should get
     * them back. A popup nothing has committed in yet shows its
     * `about:blank` as a page (see [blankIsPage]); rebuilt, that's the
     * blank home entry, not an address to load.
     */
    internal fun restorableAddress(): Pair<String, String> {
        val popupBlank = blankIsPage && url == ABOUT_BLANK
        val restoredUrl = if (popupBlank) "" else url
        val address = if (popupBlank && addressBarText == ABOUT_BLANK) "" else addressBarText
        return restoredUrl to address
    }

    /**
     * A navigation is under way over the committed page and hasn't
     * committed yet: a submit still being resolved or probed, or one
     * the WebView is fetching, whose address the bar already shows in
     * place of the page's (#183 R1-F2). Not one the user stopped. A
     * link the page follows itself keeps the page's address until it
     * commits, so it doesn't count — there's no address to put back.
     */
    internal fun uncommittedLoad(): Boolean =
        !loadAborted && url.isNotBlank() && addressBarText.isNotBlank() &&
            addressBarText != url && (resolving || progress >= 0)

    /**
     * The [navCounter] of the last navigation the tab's WebView was
     * handed. Survives with the tab when its WebView doesn't (#183, see
     * [TabsState.parkForRelaunch]), so the rebuilt WebView isn't handed
     * that same navigation again on top of its restored state.
     */
    internal var handedNavCounter: Int = 0

    /**
     * The tab's WebView is being destroyed while the tab lives on (#183,
     * an Activity relaunch). What only mirrored that WebView goes with
     * it: a load in flight (the rebuilt WebView reports its own), a
     * name still being resolved (its job belonged to the screen being
     * torn down), find-in-page matches, playing audio.
     */
    internal fun webViewLost() {
        cancelPendingProbe()
        progress = -1
        resolving = false
        find.close()
        playingAudio = false
    }

    internal var pendingRestore: PendingRestore? = null

    /**
     * The renderer process this tab's page ran in went away (#260) — it
     * crashed, or Android killed it for memory — and took the tab's
     * WebView with it. The tab keeps its address, title and a
     * [pendingRestore] saved from the dead WebView; the host builds a
     * new WebView from that only once this is cleared ([recoverRenderer]).
     * Null while the tab has a working WebView (or is about to get one).
     */
    var rendererGone: RendererGone? by mutableStateOf<RendererGone?>(null)
        internal set

    /**
     * Why a tab lost its renderer ([rendererGone]). [crashed]: the page
     * crashed it (`RenderProcessGoneDetail.didCrash()`), rather than the
     * system killing it to free memory. [reloadWhenShown]: the tab wasn't
     * on screen when it happened (a background tab, or the app itself was
     * away and the renderer was killed for memory), so it loads its page
     * again by itself the next time it is shown; the tab on screen shows
     * what happened, with Reload, instead.
     */
    class RendererGone(val crashed: Boolean, val reloadWhenShown: Boolean)

    /**
     * Put this tab back on a working renderer: its host builds a new
     * WebView from [pendingRestore] and loads the page again, with its
     * back/forward history. No-op for a tab that didn't lose its renderer.
     */
    fun recoverRenderer() {
        rendererGone = null
    }

    /**
     * Whether there's a page for Find in page and Print to act on: not
     * the home surface, and not a tab whose renderer went away (#260),
     * which has no WebView to search or print until it's rebuilt.
     */
    val hasPageToActOn: Boolean
        get() = url.isNotBlank() && rendererGone == null

    /**
     * An address to put back once the WebView's blank home entry has
     * finished loading — and, if [submit], to submit. Set by
     * [BrowserWebViewHost] for a reopened tab whose restored (or
     * unrestorable) state leaves the WebView *on* that entry, and
     * consumed by the first `onPageFinished` that follows, whichever
     * entry it is for: only the blank one acts on it. With [overPage]
     * set, the restored list ends on a real page instead, under a load
     * that hadn't committed yet (#183 R1-F2): the commit of that page's
     * own reload submits the address ([afterPageCommitted]).
     *
     * Either way it belongs to the restore's own load and nothing
     * after it: any navigation handed to the WebView after the restore
     * — the user's submit, Home, Back / Forward, Reload, Stop, a link
     * the user taps on the page — drops it ([restoreLoadSuperseded],
     * #185 R2-F1) before it has gone in.
     */
    class AfterBlank(val address: String, val submit: Boolean, val overPage: Boolean = false)

    internal var afterBlank: AfterBlank? = null
        private set

    internal fun armAfterRestore(after: AfterBlank?) {
        afterBlank = after
    }

    /**
     * A navigation other than the restore's own load was handed to this
     * tab's WebView (or its load was stopped): the address the restore
     * had waiting ([afterBlank]) is no longer the next thing to load.
     */
    internal fun restoreLoadSuperseded() {
        afterBlank = null
        putBackOverLoadingPage = false
    }

    /**
     * The load put back over the restored page ([claimAfterPage]) is on
     * its way to the WebView, which is still loading that page: it goes
     * in without stopping it first (#185 R4-F1). The restored page's own
     * reload committed a moment ago and its HTML and subresources are
     * still coming in — a stop would leave it truncated, for good if the
     * put-back load then doesn't commit (a Stop, a 204, a download).
     * Before the relaunch the load was in flight over a complete page;
     * Chromium keeps that page loading until the new one commits. One
     * that needs the other user agent (#180) can't go in under a page
     * still loading, so it waits for that page's finish instead, for at
     * most a few seconds ([PutBackHold]).
     *
     * One handoff's worth, taken by the WebView's nav observer
     * ([takePutBackKeepsPage]), and dropped by whatever supersedes the
     * put-back before it gets there — the same things that drop
     * [afterBlank] ([restoreLoadSuperseded]), the user's own submit or
     * Home ([userNavigated]) — or by the restored page finishing, when
     * there is nothing left to cut.
     */
    private var putBackOverLoadingPage = false

    /** Whether the load being handed to the WebView now is the put-back one (see [putBackOverLoadingPage]). */
    internal fun takePutBackKeepsPage(): Boolean =
        putBackOverLoadingPage.also { putBackOverLoadingPage = false }

    /**
     * The user named a navigation of their own (a submit, Home): a load
     * put back over the restored page and not yet handed to the WebView
     * isn't the next one any more, so what's handed next stops the page
     * first as usual.
     */
    internal fun userNavigated() {
        putBackOverLoadingPage = false
    }

    /**
     * The blank home entry finished: the address waiting for it, if it
     * was armed for that entry. One armed over a restored page
     * ([AfterBlank.overPage]) is dropped instead — the tab is Home.
     */
    internal fun takeAfterBlankEntry(): AfterBlank? =
        afterBlank.also { afterBlank = null }?.takeUnless { it.overPage }

    /**
     * A real page finished. A pending blank-entry address is dropped: it
     * can't apply to a later Home. One armed over the restored page
     * ([AfterBlank.overPage]) was already taken at that page's commit
     * ([afterPageCommitted]); if it's still here, nothing committed.
     */
    internal fun afterPageFinished() {
        if (afterBlank?.overPage == false) afterBlank = null
        putBackOverLoadingPage = false
    }

    /**
     * A main-frame document committed. The address armed over the
     * restored page ([AfterBlank.overPage]) is returned, still armed,
     * for the caller to [claimAfterPage] once this commit has updated
     * the tab: the first commit after the restore is that page's
     * reload, under whatever URL it ended on (a redirect, #185 R2-F2).
     * It goes in at the commit, not at the page's finish: the load was
     * in flight over this page before the relaunch, and is again from
     * before the page can take any input — so a navigation the user
     * starts on it (a tapped link, a POST form, which never reaches
     * `shouldOverrideUrlLoading`) replaces that load in the WebView
     * itself, as it would have before, instead of being overwritten by
     * it once the page finishes (#185 R3-F1).
     */
    internal fun afterPageCommitted(): AfterBlank? = afterBlank?.takeIf { it.overPage }

    /**
     * Whether [after] (from [afterPageCommitted]) is still the load to
     * submit now: nothing superseded it in between, it wasn't claimed
     * already, and the user hasn't stopped the tab. Disarms it either way.
     * A claimed load goes to the WebView without stopping the restored
     * page ([takePutBackKeepsPage]).
     */
    internal fun claimAfterPage(after: AfterBlank): Boolean {
        if (afterBlank !== after) return false
        afterBlank = null
        putBackOverLoadingPage = !loadAborted
        return putBackOverLoadingPage
    }

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
     * The document on screen is one of Freedom's error pages rather than
     * the page [url] names: [ErrorPage] itself, or a page served in
     * place in the failed entry — a failed web load (#259), the
     * certificate page, a name or onion refusal (#99, #143). [url] can't
     * say so: on all of them it holds the address that failed. Set by
     * the tab's WebView at navigation commit (and when a failed load's
     * page goes up); what offers to act on "the page" (Add to Home
     * screen, Desktop site) reads it.
     */
    var showsErrorPage: Boolean by mutableStateOf(false)
        internal set

    /**
     * The address the user last typed into this tab's address bar and
     * the web URL it became ([typedAddressFor]), so that URL's "address
     * not found" page can offer to search for it instead (#419).
     * Replaced on every submit, and dropped once a document for any
     * other address commits ([typedAddressAfterCommit]).
     */
    @Volatile
    internal var typedAddress: TypedAddress? = null

    /**
     * The document on screen's provider origin key ([providerOriginKey]):
     * what the Wallet's publisher identities page offers to set up
     * (#119). Null for home and for anything that isn't a secure origin.
     * Set by the tab's WebView at navigation commit, with [zoomSite].
     */
    var providerOrigin: String? by mutableStateOf<String?>(null)
        internal set

    /**
     * The document on screen's site-permission origin key
     * ([permissionOriginKey]): the site the page menu's **Site
     * permissions** sheet is about (#266). Null for home and anything
     * that can't hold a permission. Set by the tab's WebView at
     * navigation commit, with [zoomSite].
     */
    var permissionOrigin: String? by mutableStateOf<String?>(null)
        internal set

    /**
     * The document on screen's own origin ([documentOrigin]): what Page
     * info's **Site data** counts and deletes. The same as
     * [permissionOrigin], except on a content gateway's own origin,
     * which holds no permission but does hold the data its pages wrote
     * (#457 R5-M1). Set and cleared with [permissionOrigin].
     */
    var siteOrigin: String? by mutableStateOf<String?>(null)
        internal set

    /**
     * The site Page info's **Site data** is about: the committed
     * [siteOrigin], or, for a popup's own blank document, the site it
     * inherited from its opener ([permissionTop]).
     */
    val siteDataOrigin: String?
        get() = siteOrigin ?: permissionTop

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
     *
     * [bypassCache]: this is the load of the user's Hard reload (#262,
     * [takeBypassCacheLoad]), passed the same way.
     */
    fun loadUrl(
        url: String,
        displayPrefix: String? = null,
        namedByUser: Boolean = false,
        bypassCache: Boolean = false,
    ) {
        cancelPendingProbe()
        // A new load supersedes whatever the last Stop aborted, so the
        // progress latch opens again.
        loadAborted = false
        // For a name, the session's answer is only a first guess: the
        // main-frame interceptor re-checks it and, before the fetch
        // starts, sets the flag from the answer it actually serves —
        // which a failed re-check can hold on this tab's older one
        // (see `noteMainFrameContentLoad`, #179 R5-F1).
        ipfsLoad = ipfsLoadFor(url, ipfsLoad)
        val loadable = Gateways.toLoadable(url)
        pendingUrl = loadable
        // Named by the user only when their submit's own load says so
        // (#173): never an error page, a restore, or a Back step that
        // happens to come after it (R2-F2).
        userNamedPendingUrl = loadable.takeIf { namedByUser }
        // Likewise a Hard reload's bypassing of the cache (#262).
        bypassCachePendingUrl = loadable.takeIf { bypassCache }
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

    /**
     * The transport this tab's address asserts for [name] (#97): the
     * typed scheme of the display override, if that override is the
     * name's own origin. `null` — any transport — otherwise. Read from
     * the interceptor's threads for a document's re-check.
     */
    fun assertedProtocolFor(name: String): String? {
        val o = override ?: return null
        if (o.baseUrl != VirtualOrigin.originFor(ContentRoot.Ens(name.lowercase()))) return null
        return o.assertedProtocol
    }

    /** Is [url] on the display override's origin (its manifest)? */
    fun isUnderOverride(url: String): Boolean {
        return override?.covers(url) == true
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
        blankOpenerOrigin = null
        userNavigated()
        cancelPendingProbe()
        capsuleCollapse.expand()
        override = null
        nameTrust = null
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
        for (p in listOfNotNull(o.prefix, shownPrefixOf(o)).distinct()) {
            if (startsWithPrefix(raw, p)) return o.baseUrl + raw.substring(p.length)
        }
        return raw
    }

    /**
     * What Reload submits: the page on screen ([url]), or the pending
     * address if nothing has committed yet.
     *
     * A generic ENS load shows its transport (`ipfs://vitalik.eth/p`,
     * #97), but that scheme is the bar describing the answer, not the
     * user asserting one — so Reload hands back the override's own
     * bare form, which [effectiveFetchUrl] maps onto the loaded
     * manifest, instead of re-submitting the shown string as a
     * typed-scheme assertion.
     *
     * Typing the scheme the bar already shows for the name gets the
     * same treatment: [effectiveFetchUrl] maps it through the shown
     * prefix onto the generic override, so it doesn't assert either
     * (R1-F4). A typed scheme asserts when it is a different one
     * (`bzz://vitalik.eth` over a shown `ipfs://vitalik.eth/`) or
     * when the tab isn't already on the name.
     */
    fun reloadUrl(): String {
        val shown = url.ifBlank { addressBarText }
        val o = override ?: return shown
        val p = shownPrefixOf(o) ?: return shown
        if (p != o.prefix && startsWithPrefix(shown, p)) {
            return o.prefix + shown.substring(p.length)
        }
        return shown
    }

    /**
     * The override's prefix in the form the bar *showed* it for the page
     * on screen — the scheme [url] was committed with, not the one
     * [Override.shown] would pick now. A generic name's transport is
     * read at display time, so it can have moved since (another tab's
     * re-check, R1-F1); what the user saw, and edits, is this form.
     * Falls back to [Override.shown] before anything has committed.
     */
    private fun shownPrefixOf(o: Override): String? {
        if (o.prefix.contains("://")) return o.prefix
        if (url.isBlank()) return o.shown
        val scheme = url.substringBefore("://", "")
        if (scheme !in ASSERTING_SCHEMES) return null
        val p = "$scheme://${o.prefix}"
        return p.takeIf { startsWithPrefix(url, it) }
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
        nameTrust = null
        thumbnail = null
    }
}

/**
 * Does [s] start with [prefix] at an address boundary — the prefix is
 * the whole host, not `vitalik.eth` inside `vitalik.ethx`?
 */
private fun startsWithPrefix(s: String, prefix: String): Boolean =
    s.startsWith(prefix) && (s.length == prefix.length || s[prefix.length] in "/?#")

/** Schemes that, typed in front of a name, assert its transport (#97). */
private val ASSERTING_SCHEMES = setOf("bzz", "ipfs", "ipns")
