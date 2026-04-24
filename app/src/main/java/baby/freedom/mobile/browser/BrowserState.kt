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
     * `ens://<name>/path` visible while browsing under the resolved
     * content manifest (Swarm today; IPFS/IPNS after port-plan §2).
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

    /** What the user is currently typing in the address bar. */
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
     * Most recent page-preview bitmap for this tab, shown in the tab
     * switcher grid. Captured from the live WebView after each successful
     * load and whenever the user opens the switcher (so the thumbnail
     * reflects their current scroll / DOM state). `null` means we don't
     * have a snapshot yet — the card falls back to a letter placeholder.
     */
    var thumbnail: ImageBitmap? by mutableStateOf<ImageBitmap?>(null)
        internal set

    /**
     * When the page currently in the WebView is served from `/bzz/<hash>/`,
     * this holds the fully-qualified root (with trailing slash). Used by
     * [BrowserWebView] to rewrite subresource requests that escape the bzz
     * namespace — e.g. a Next.js site referencing `/_next/static/...` would
     * otherwise miss the bzz hash and 404.
     */
    @Volatile
    var currentBzzRoot: String? = null
        internal set

    /**
     * In-flight [GatewayProbe] job for this tab (if any). Set by
     * [BrowserScreen] when a bzz / ens-to-bzz navigation enters the
     * "peers warming up" gate, and cleared either by the probe finishing
     * or by [cancelPendingProbe].
     *
     * Held on the per-tab state (rather than, say, a
     * `BrowserScreen`-level `remember`) so that switching tabs while a
     * probe is running doesn't leak the probe into the new tab, and a
     * fresh submit on the same tab cancels the old one cleanly.
     */
    @Volatile
    var pendingProbeJob: Job? = null

    /**
     * Cancel any in-flight [pendingProbeJob]. No-op if there isn't one.
     * Called from [loadUrl] so a fresh navigation supersedes whatever
     * probe the tab was previously waiting on.
     */
    fun cancelPendingProbe() {
        val job = pendingProbeJob
        pendingProbeJob = null
        job?.cancel()
    }

    /**
     * Schedule a navigation. [url] is the *canonical* URL (may be `bzz://…`);
     * the pending URL handed to the WebView is the rewritten form that it
     * can actually fetch (`http://127.0.0.1:1633/bzz/…` for Swarm content).
     *
     * If [ensName] is provided, the address bar will show
     * `ens://<ensName>[<path>]` for this navigation and any subsequent
     * navigation under the same Swarm manifest. Passing `null` (the
     * default) leaves any existing ENS override untouched — reload,
     * back, and forward all reuse the current override. Call
     * [clearEnsOverride] to reset.
     */
    fun loadUrl(url: String, ensName: String? = null) {
        cancelPendingProbe()
        val loadable = Gateways.toLoadable(url)
        pendingUrl = loadable
        // Seed `currentBzzRoot` right here so that subresource escape
        // rewrites (see `BrowserWebView.rewriteGatewayEscape`) work for
        // the very earliest fetches the page kicks off. Chromium's
        // HTML preload scanner races ahead of `onPageStarted` for
        // `<link rel=preload>` and other early subresources, and if
        // they hit `shouldInterceptRequest` while the previous page's
        // root (or `null`) is still in place, we fail to rewrite them
        // and the page renders unstyled. Clearing on a non-bzz load
        // is equally important so a subsequent https:// page doesn't
        // still see the previous site's root.
        //
        // `extractRoot` only matches when there's a trailing slash, but
        // `SwarmResolver.toLoadable("bzz://<hash>")` yields `/bzz/<hash>`
        // without one. Fall back to `extractBase` + "/" in that case so
        // bare-root loads still seed the right root for subresources.
        currentBzzRoot = GatewayUrls.extractRoot(loadable)
            ?: GatewayUrls.extractBase(loadable)?.let { it.prefix + "/" }
        if (ensName != null) {
            val base = GatewayUrls.extractBase(loadable)
            override = if (base != null) {
                Override(baseUrl = base.prefix, prefix = "ens://$ensName")
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
        override = null
        currentBzzRoot = null
        url = ""
        title = ""
        addressBarText = ""
        progress = -1
        resolving = false
        loadUrl(HOME_URL)
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
        url = ""
        title = ""
        progress = -1
        resolving = false
        canGoBack = false
        canGoForward = false
        override = null
        currentBzzRoot = null
        thumbnail = null
    }
}
