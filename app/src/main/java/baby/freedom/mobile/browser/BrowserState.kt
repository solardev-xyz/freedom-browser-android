package baby.freedom.mobile.browser

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap

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
        val loadable = SwarmResolver.toLoadable(url)
        pendingUrl = loadable
        if (ensName != null) {
            val base = GatewayUrls.extractBase(loadable)
            override = if (base != null) {
                Override(baseUrl = base.prefix, prefix = "ens://$ensName")
            } else {
                null
            }
        }
        navCounter++
    }

    /** Drop any active ENS display override. Call before loading a URL
     *  that the user explicitly typed (and that isn't an ENS name). */
    fun clearEnsOverride() {
        override = null
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
        url = ""
        title = ""
        progress = -1
        canGoBack = false
        canGoForward = false
        override = null
        currentBzzRoot = null
        thumbnail = null
    }
}
