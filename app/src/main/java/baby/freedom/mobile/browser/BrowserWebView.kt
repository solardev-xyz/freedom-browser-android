package baby.freedom.mobile.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.createBitmap
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import baby.freedom.mobile.data.BrowsingRepository
import baby.freedom.swarm.SwarmNode
import kotlinx.coroutines.flow.collectLatest
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

private const val ABOUT_BLANK = "about:blank"
private const val LOG_TAG = "BrowserWebView"

// Paths under `http://127.0.0.1:1633/` that legitimately belong to the Bee
// gateway's own HTTP API. Any other gateway-origin request from a page is
// almost certainly a subresource that forgot to include its /bzz/<hash>/
// prefix, so we transparently refetch it from under the current bzz root.
private val BEE_API_SEGMENTS = setOf(
    "bzz", "bzz-chunk", "bytes", "chunks", "tags", "pins", "stewardship",
    "feeds", "soc", "pss", "stamps", "wallet", "chequebook", "settlements",
    "accounting", "redistributionstate", "reservestate", "addresses", "peers",
    "topology", "welcome-message", "node", "health", "readiness", "status",
    "auth", "refresh",
)

// Response headers we strip when proxying — they're either managed by the
// WebView's own transport or would confuse it if passed through verbatim.
private val HEADERS_TO_STRIP = setOf(
    "transfer-encoding", "content-encoding", "connection", "keep-alive",
    "content-length",
)

private val bzzRootRegex = Regex("^(https?://[^/]+/bzz/[a-fA-F0-9]+/)")

private fun extractBzzRoot(url: String?): String? {
    if (url == null) return null
    return bzzRootRegex.find(url)?.groupValues?.get(1)
}

// Max width (in px) of a thumbnail bitmap. Anything bigger is wasteful
// since we only ever render these at half-screen-ish sizes in the grid.
private const val THUMBNAIL_MAX_WIDTH_PX = 640

/**
 * Capture the current WebView to a down-scaled [Bitmap] and publish it as
 * [BrowserState.thumbnail]. Runs synchronously — callers must be on the
 * UI thread (WebView.draw requires it). Silent-no-ops if the view hasn't
 * been laid out yet.
 */
internal fun captureThumbnail(view: WebView, state: BrowserState) {
    val w = view.width
    val h = view.height
    if (w <= 0 || h <= 0) return
    val scale = if (w > THUMBNAIL_MAX_WIDTH_PX) THUMBNAIL_MAX_WIDTH_PX.toFloat() / w else 1f
    val bw = (w * scale).toInt().coerceAtLeast(1)
    val bh = (h * scale).toInt().coerceAtLeast(1)
    val bitmap = createBitmap(bw, bh)
    val canvas = Canvas(bitmap)
    if (scale != 1f) canvas.scale(scale, scale)
    try {
        view.draw(canvas)
    } catch (t: Throwable) {
        Log.w(LOG_TAG, "thumbnail capture failed", t)
        return
    }
    state.thumbnail = bitmap.asImageBitmap()
}

/**
 * Host for N browser tabs.
 *
 * Each tab keeps its own live [WebView] so switching tabs preserves
 * scroll position, JS state, form contents etc. All WebViews are children
 * of the same [FrameLayout]; only the active tab's view is visible — the
 * rest are [View.GONE] so they stop drawing but retain their state.
 *
 * When [TabsState.tabs] shrinks (tab closed) we remove and `destroy()` the
 * orphaned WebView so we don't leak native resources.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserWebViewHost(
    tabs: TabsState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val repo = remember(context) { BrowsingRepository.get(context) }

    val frame = remember {
        FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(0xFF121212.toInt())
        }
    }

    // One WebView per tab id — each wrapped in its own SwipeRefreshLayout
    // so pull-to-refresh can reload the current page. The SwipeRefreshLayout
    // is what actually lives under [frame]; the WebView is its only child.
    val webViews = remember { mutableMapOf<Long, WebView>() }
    val refreshLayouts = remember { mutableMapOf<Long, SwipeRefreshLayout>() }

    // Per-tab navigation observers (coroutine jobs, tracked so we can cancel
    // them if the tab is closed).
    // Drive navigation + lifecycle for each current tab.
    val currentIds: List<Long> = tabs.tabs.map { it.id }

    // Create any WebViews that don't yet exist; tear down any that belong
    // to tabs that have been closed.
    run {
        val idsNow = currentIds.toSet()
        for (tab in tabs.tabs) {
            if (webViews[tab.id] == null) {
                val (layout, wv) = buildRefreshableWebView(context, tab, repo)
                webViews[tab.id] = wv
                refreshLayouts[tab.id] = layout
                frame.addView(layout)
            }
        }
        val toRemove = webViews.keys.filter { it !in idsNow }
        for (id in toRemove) {
            val wv = webViews.remove(id) ?: continue
            val layout = refreshLayouts.remove(id)
            if (layout != null) frame.removeView(layout)
            wv.stopLoading()
            wv.destroy()
        }
    }

    // Visibility: only the active tab draws. Toggle the SwipeRefreshLayout
    // (the actual child of [frame]) rather than the WebView itself.
    val activeId = tabs.active.id
    for ((id, layout) in refreshLayouts) {
        val targetVisibility = if (id == activeId) View.VISIBLE else View.GONE
        if (layout.visibility != targetVisibility) layout.visibility = targetVisibility
    }

    // Drive loads for each tab as its navCounter changes. `snapshotFlow`
    // turns the mutable counter into a flow we can collect for the lifetime
    // of the tab; `key(tab.id)` scopes the effect so closing a tab cancels
    // its observer.
    for (tab in tabs.tabs) {
        androidx.compose.runtime.key(tab.id) {
            LaunchedEffect(tab.id) {
                snapshotFlow { tab.navCounter to tab.pendingUrl }
                    .collectLatest { (counter, pending) ->
                        if (counter > 0 && pending.isNotEmpty()) {
                            webViews[tab.id]?.loadUrl(pending)
                        }
                    }
            }
        }
    }

    AndroidView(
        factory = { frame },
        modifier = modifier.fillMaxSize(),
    )

    // Expose a "snapshot the active tab" hook to TabsState. The tab
    // switcher invokes this right before it renders and [TabsState.switchTo]
    // invokes it right before swapping, so every card has a preview that
    // matches what the user last saw.
    DisposableEffect(tabs) {
        tabs.captureActiveThumbnail = {
            val wv = webViews[tabs.active.id]
            if (wv != null) captureThumbnail(wv, tabs.active)
        }
        onDispose { tabs.captureActiveThumbnail = null }
    }

    DisposableEffect(Unit) {
        onDispose {
            for (wv in webViews.values) {
                wv.stopLoading()
                wv.destroy()
            }
            webViews.clear()
            refreshLayouts.clear()
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun buildRefreshableWebView(
    context: Context,
    state: BrowserState,
    repo: BrowsingRepository,
): Pair<SwipeRefreshLayout, WebView> {
    val refreshLayout = SwipeRefreshLayout(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
    }

    val webView = WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )

        // Use a white WebView background (the browser default) so that
        // pages without their own styling — most notably Chromium's
        // built-in error pages, which render dark text on whatever
        // canvas the WebView provides — stay readable even though the
        // rest of the app chrome is dark-themed. Pages that style
        // themselves (home page, most real sites) are unaffected; they
        // paint their own background over this base colour.
        setBackgroundColor(0xFFFFFFFF.toInt())

        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadsImagesAutomatically = true
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            mediaPlaybackRequiresUserGesture = true
        }

        // Force an initial paint so the WebView's compositor surface
        // is valid even before the user submits a URL.
        loadUrl(ABOUT_BLANK)

        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (url == ABOUT_BLANK) return
                state.currentBzzRoot = extractBzzRoot(url)
                url?.let { state.url = displayFor(it, state) }
                state.progress = 0
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (url == ABOUT_BLANK) return
                // Dismiss the pull-to-refresh spinner once the page has
                // finished loading (or errored out). Happens regardless
                // of whether the load was user-initiated reload or not.
                refreshLayout.isRefreshing = false
                state.currentBzzRoot = extractBzzRoot(url)
                val display = displayFor(url.orEmpty(), state)
                state.url = display
                state.title = view?.title.orEmpty()
                state.canGoBack = view?.canGoBack() == true
                state.canGoForward = view?.canGoForward() == true
                state.progress = -1
                state.addressBarText = display
                // Record the *displayed* URL (bzz://, ens://, https://) — not
                // the gateway-rewritten one — so history reflects what the
                // user actually visited. The local home page is hidden from
                // the address bar (displayFor returns "") and shouldn't
                // clutter the history either.
                if (display.isNotBlank()) repo.recordVisit(display, state.title)
                // Give the renderer a beat to paint, then capture a
                // thumbnail. 400ms is enough for most pages; if the load
                // is still progressing we'll re-capture on the next
                // onPageFinished anyway.
                view?.postDelayed({
                    if (view.isShown) {
                        captureThumbnail(view, state)
                    }
                }, 400)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                val target = request?.url?.toString() ?: return false
                if (target.startsWith("bzz://")) {
                    view?.loadUrl(SwarmResolver.toLoadable(target))
                    return true
                }
                return false
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
            ): WebResourceResponse? = rewriteGatewayEscape(state, request)
        }

        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                state.progress = if (newProgress in 1..99) newProgress else -1
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                state.title = title.orEmpty()
            }
        }
    }

    refreshLayout.addView(webView)
    refreshLayout.setOnRefreshListener { webView.reload() }
    // Only arm the pull-down gesture when the WebView is scrolled to the
    // top. Without this override SwipeRefreshLayout can trigger in the
    // middle of a page because WebView's canScrollUp reporting is flaky
    // for nested scrollers.
    refreshLayout.setOnChildScrollUpCallback { _, _ -> webView.scrollY > 0 }
    return refreshLayout to webView
}

/**
 * Next-style sites served from `/bzz/<hash>/` often reference resources via
 * absolute-root paths (e.g. `<link href="/_next/static/...">`). When loaded
 * through the Bee gateway that resolves to `http://127.0.0.1:1633/_next/...`
 * — outside the bzz namespace — and 404s, leaving the page unstyled.
 *
 * If the current page is a bzz site, we transparently rewrite any such
 * "escaped" gateway-origin subresource request to live under the current
 * `/bzz/<hash>/` root, fetch it via [HttpURLConnection], and hand the body
 * back to the WebView.
 */
internal fun rewriteGatewayEscape(
    state: BrowserState,
    request: WebResourceRequest?,
): WebResourceResponse? {
    val req = request ?: return null
    val url = req.url?.toString() ?: return null
    if (req.method != "GET") return null

    val bzzRoot = state.currentBzzRoot ?: return null
    val gatewayPrefix = "${SwarmNode.GATEWAY_URL}/"
    if (!url.startsWith(gatewayPrefix)) return null

    val tail = url.substring(gatewayPrefix.length)
    if (tail.isEmpty()) return null
    val firstSegment = tail
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
    if (firstSegment.isEmpty()) return null
    if (firstSegment in BEE_API_SEGMENTS) return null

    val rewritten = bzzRoot + tail
    return try {
        val conn = (URL(rewritten).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            req.requestHeaders?.forEach { (k, v) ->
                if (!k.equals("Host", ignoreCase = true) &&
                    !k.equals("Content-Length", ignoreCase = true)
                ) {
                    try { setRequestProperty(k, v) } catch (_: Throwable) {}
                }
            }
        }
        conn.connect()
        val status = conn.responseCode
        val reason = conn.responseMessage?.ifBlank { null } ?: "OK"
        val rawCt = conn.contentType ?: "application/octet-stream"
        val mime = rawCt.substringBefore(';').trim().ifBlank { "application/octet-stream" }
        val charset = rawCt
            .substringAfter("charset=", "")
            .trim()
            .trim('"')
            .ifBlank { null }

        val headers = conn.headerFields
            .asSequence()
            .mapNotNull { (k, v) ->
                if (k == null || v == null) null
                else k to v.joinToString(",")
            }
            .filter { (k, _) -> k.lowercase() !in HEADERS_TO_STRIP }
            .toMap()

        val body = if (status in 200..399) {
            conn.inputStream
        } else {
            conn.errorStream ?: ByteArrayInputStream(ByteArray(0))
        }

        WebResourceResponse(mime, charset, status, reason, headers, body)
    } catch (t: Throwable) {
        Log.w(LOG_TAG, "gateway-escape rewrite failed: $url → $rewritten", t)
        null
    }
}

/**
 * Map a "real" URL (what the WebView actually loaded — `http://127.0.0.1:…`
 * for Swarm content, or an external origin) to the friendly string for the
 * address bar. Prefers the active ENS override when the URL is under its
 * base prefix, otherwise falls back to the `bzz://` rewrite. Returns an
 * empty string for the local home page so the address bar looks blank
 * there instead of exposing the `file:///android_asset/...` URL.
 */
internal fun displayFor(actualUrl: String, state: BrowserState): String {
    if (actualUrl == HOME_URL) return ""
    val base = state.displayOverrideBaseUrl
    val prefix = state.displayOverridePrefix
    if (base != null && prefix != null && actualUrl.startsWith(base)) {
        return prefix + actualUrl.substring(base.length)
    }
    return SwarmResolver.toDisplay(actualUrl)
}
