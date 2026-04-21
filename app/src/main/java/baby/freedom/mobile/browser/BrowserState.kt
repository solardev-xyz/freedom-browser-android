package baby.freedom.mobile.browser

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Observable state for a single browser "tab" (we only have one in Phase 2).
 * The [android.webkit.WebView] itself holds the canonical navigation state;
 * this class mirrors just enough for Compose to reflect it in the chrome.
 */
class BrowserState {
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

    /**
     * If set, any actual URL starting with [displayOverrideBaseUrl] is
     * rendered in the address bar as [displayOverridePrefix] + the tail.
     * Used to keep "ens://<name>/path" visible instead of the resolved
     * Swarm-gateway URL while browsing under an ENS-resolved manifest.
     */
    var displayOverrideBaseUrl: String? = null
        internal set
    var displayOverridePrefix: String? = null
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
            displayOverrideBaseUrl = extractBzzBase(loadable)
            displayOverridePrefix = "ens://$ensName"
        }
        navCounter++
    }

    /** Drop any active ENS display override. Call before loading a URL
     *  that the user explicitly typed (and that isn't an ENS name). */
    fun clearEnsOverride() {
        displayOverrideBaseUrl = null
        displayOverridePrefix = null
    }

    /**
     * Given an address bar string, reconstruct what should actually be
     * fetched, honoring any active display override. When the user hits
     * reload (icon, not a typed URL), we want to reload the real URL
     * under the override, not the friendly form.
     */
    fun effectiveFetchUrl(raw: String): String {
        val prefix = displayOverridePrefix
        val base = displayOverrideBaseUrl
        if (prefix != null && base != null && raw.startsWith(prefix)) {
            return base + raw.substring(prefix.length)
        }
        return raw
    }

    private fun extractBzzBase(url: String): String? {
        // http://127.0.0.1:1633/bzz/<hash>[/...] → drop the /... tail.
        val m = Regex("^(https?://[^/]+/bzz/[a-fA-F0-9]+)").find(url)
        return m?.groupValues?.get(1)
    }

    /** Tokens the WebView client should not treat as "new" navigations. */
    fun reset() {
        url = ""
        title = ""
        progress = -1
        canGoBack = false
        canGoForward = false
        displayOverrideBaseUrl = null
        displayOverridePrefix = null
        currentBzzRoot = null
    }
}
