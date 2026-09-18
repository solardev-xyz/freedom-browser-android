package baby.freedom.mobile.browser

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
 */
class BrowserState(val id: Long) {
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
     * Compact-on-scroll state of the floating capsule for this tab.
     * Fed by the tab's WebView scroll callbacks (see
     * [BrowserWebViewHost]) and read by the chrome in [BrowserScreen].
     * Per-tab, because scroll position is: switching to a tab the user
     * left at the top of a page shows that tab's chrome at rest.
     */
    internal val capsuleCollapse = CapsuleCollapseState()

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
     */
    fun loadUrl(url: String, displayPrefix: String? = null) {
        cancelPendingProbe()
        // A new load supersedes whatever the last Stop aborted, so the
        // progress latch opens again.
        loadAborted = false
        val loadable = Gateways.toLoadable(url)
        pendingUrl = loadable
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
        cancelPendingProbe()
        capsuleCollapse.expand()
        override = null
        url = ""
        title = ""
        addressBarText = ""
        progress = -1
        resolving = false
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
        canGoBack = false
        canGoForward = false
        override = null
        thumbnail = null
    }
}
