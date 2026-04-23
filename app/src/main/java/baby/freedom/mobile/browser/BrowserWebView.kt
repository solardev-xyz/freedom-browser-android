package baby.freedom.mobile.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
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
import java.io.IOException
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

// First-segment prefixes that identify user-facing content served from
// the Bee gateway (vs. the node's own JSON API under /health, /status,
// etc.). Subresources that land on one of these but 404 are retried —
// a cold Bee node regularly answers the top-level manifest before every
// chunk is in hand, so these transient failures resolve a few seconds
// later without anything visibly breaking on the page.
private val GATEWAY_CONTENT_SEGMENTS = setOf("bzz", "ipfs", "ipns")

// Response headers we strip when proxying — they're either managed by the
// WebView's own transport or would confuse it if passed through verbatim.
// `content-length` is stripped because `HttpURLConnection` auto-decodes
// gzip responses for us and the incoming length is for the compressed
// body, which would be wrong for what we hand back to the WebView.
private val HEADERS_TO_STRIP = setOf(
    "transfer-encoding", "content-encoding", "connection", "keep-alive",
)

private fun extractBzzRoot(url: String?): String? = GatewayUrls.extractRoot(url)

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

    // Enable Chrome DevTools inspection for debug builds so we can
    // diagnose broken subresources on Swarm-hosted pages. Cheap no-op
    // once set and idempotent.
    remember {
        if ((context.applicationInfo.flags and
                android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        ) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        Unit
    }

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
                val (layout, wv) = buildRefreshableWebView(
                    context = context,
                    state = tab,
                    repo = repo,
                    onSubmitUrl = { target, url ->
                        tabs.requestSubmit?.invoke(target, url)
                    },
                )
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
        tabs.clearWebViewData = {
            // Globally-scoped stores: cookies and DOM storage / IndexedDB /
            // WebSQL are shared across every WebView in the process, so
            // wiping them once is enough.
            runCatching { CookieManager.getInstance().removeAllCookies(null) }
            runCatching { CookieManager.getInstance().flush() }
            runCatching { WebStorage.getInstance().deleteAllData() }
            // Per-instance state: HTTP cache, autofill form data, and the
            // back/forward stack live on each WebView, so clear them on
            // every live tab.
            for (wv in webViews.values) {
                runCatching { wv.clearCache(true) }
                runCatching { wv.clearFormData() }
                runCatching { wv.clearHistory() }
            }
        }
        onDispose {
            tabs.captureActiveThumbnail = null
            tabs.clearWebViewData = null
        }
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
    onSubmitUrl: (BrowserState, String) -> Unit,
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
            // Allow muted `<video autoplay>` backgrounds (swarm.eth, and
            // every other modern Next.js hero video) to kick off without
            // a user tap. Matches Chrome-on-Android's own policy, which
            // lets muted media autoplay without interaction. Audio that
            // actually requires a tap is still gated by the browser's
            // own per-frame autoplay policy.
            mediaPlaybackRequiresUserGesture = false
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
                state.title = sanitizeTitle(view?.title, url)
                state.canGoBack = view?.canGoBack() == true
                state.canGoForward = view?.canGoForward() == true
                state.progress = -1
                state.addressBarText = display
                // Record the *displayed* URL (bzz://, ens://, https://) — not
                // the gateway-rewritten one — so history reflects what the
                // user actually visited. The local home page is hidden from
                // the address bar (displayFor returns "") and shouldn't
                // clutter the history either. The error page is also
                // deliberately kept out of history — it's a transient
                // state, not a destination the user meant to visit.
                if (display.isNotBlank() && !ErrorPage.isErrorPage(url)) {
                    repo.recordVisit(display, state.title)
                }

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
                // Route bzz:// and ens:// through the screen's submit flow
                // so in-page clicks + error-page "Try Again" go through
                // the same GatewayProbe gate the top address bar uses.
                // Falls back to a direct gateway load if no submit hook
                // is wired (defensive — the hook is installed before the
                // first tab ever renders).
                if (target.startsWith("bzz://") || target.startsWith("ens://")) {
                    onSubmitUrl(state, target)
                    return true
                }
                return false
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
            ): WebResourceResponse? = rewriteGatewayEscape(state, request)

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                val req = request ?: return
                if (!req.isForMainFrame) return
                val failed = req.url?.toString() ?: return
                // Already on the error page? Don't loop.
                if (ErrorPage.isErrorPage(failed)) return
                if (!isLocalGatewayUrl(failed)) return

                val display = displayFor(failed, state).ifBlank { failed }
                val retryUri = SwarmResolver.toDisplay(failed)
                val code = error?.errorCode?.let { "ERR_$it" } ?: "ERR_FAILED"
                val page = ErrorPage.url(
                    errorCode = code,
                    displayUrl = display,
                    protocol = "swarm",
                    retryUrl = retryUri,
                )
                state.clearEnsOverride()
                view?.loadUrl(page)
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?,
            ) {
                val req = request ?: return
                if (!req.isForMainFrame) return
                val failed = req.url?.toString() ?: return
                if (ErrorPage.isErrorPage(failed)) return
                if (!isLocalGatewayUrl(failed)) return

                val status = errorResponse?.statusCode ?: 0
                // The probe already waited out transient 404/500s — if we
                // got one here, the gateway answered but the content
                // genuinely isn't available (misspelled hash, etc).
                val display = displayFor(failed, state).ifBlank { failed }
                val retryUri = SwarmResolver.toDisplay(failed)
                val page = ErrorPage.url(
                    errorCode = "swarm_content_not_found",
                    displayUrl = display,
                    protocol = "swarm",
                    retryUrl = retryUri,
                )
                Log.i(LOG_TAG, "main-frame HTTP $status for $failed → error page")
                state.clearEnsOverride()
                view?.loadUrl(page)
            }
        }

        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                state.progress = if (newProgress in 1..99) newProgress else -1
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                state.title = sanitizeTitle(title, view?.url)
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

// Back-off schedule for the gateway-escape retry loop. Kept short so we
// never block the WebView's network thread long enough for its internal
// request timeout to fire (~30s Chromium default). With 5 attempts and
// these delays the worst-case budget is ~5.5s of sleeping plus N
// connection attempts, so even a slow bee-lite peer warmup still lands
// inside the WebView's own window.
private val ESCAPE_RETRY_DELAYS_MS: LongArray = longArrayOf(0L, 250L, 500L, 1000L, 2000L)

/**
 * Proxy subresource fetches that the Bee gateway can't fulfill on its own
 * fast enough or in a WebView-friendly format.
 *
 * Three cases get intercepted:
 *
 * 1. **Gateway-escape rewrites.** Next-style sites served from
 *    `/bzz/<hash>/` often reference resources via absolute-root paths
 *    (`<link href="/_next/static/…">`). Those resolve to
 *    `http://127.0.0.1:1633/_next/…` — outside the bzz namespace — and
 *    404, leaving the page unstyled. We transparently rewrite such
 *    requests to live under the current `/bzz/<hash>/` root.
 *
 * 2. **Content-path retries.** Subresources already correctly rooted
 *    at `/bzz/`, `/ipfs/`, or `/ipns/` that hit a transient 404/500
 *    get retried with short backoff. A cold Bee node sometimes answers
 *    the top-level manifest before every chunk is in hand, so the
 *    resource exists but isn't retrievable yet.
 *
 * 3. **Media range-request synthesis.** Bee returns the whole body for
 *    every `Range` request (no 206, no `Accept-Ranges`, empty
 *    `Content-Type`), which stalls Chromium's media pipeline at
 *    `HAVE_METADATA`. [fetchMediaWithRangeSupport] buffers the body once
 *    and synthesises proper Partial Content responses.
 *
 * Main-frame requests (and non-GETs) are left to the WebView's own
 * network stack — the top-level navigation is already gated by
 * [GatewayProbe], and interception only buys us retries we don't need.
 * Requests to the node's own JSON API (/health, /status, etc.) also
 * pass through untouched.
 */
internal fun rewriteGatewayEscape(
    state: BrowserState,
    request: WebResourceRequest?,
): WebResourceResponse? {
    val req = request ?: return null
    val url = req.url?.toString() ?: return null
    if (req.method != "GET") return null
    if (req.isForMainFrame) return null

    val gatewayPrefix = "${SwarmNode.GATEWAY_URL}/"
    if (!url.startsWith(gatewayPrefix)) return null

    val tail = url.substring(gatewayPrefix.length)
    if (tail.isEmpty()) return null
    val firstSegment = tail
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
    if (firstSegment.isEmpty()) return null

    // Case 2 + 3: request is already on a content path. Handle media
    // specially (range-aware), and retry on transient 404/500 for other
    // content subresources. Non-content API endpoints fall through to
    // Chromium's native fetch.
    if (firstSegment in GATEWAY_CONTENT_SEGMENTS) {
        return if (isMediaLikeUrl(url)) {
            fetchMediaWithRangeSupport(req, url)
        } else {
            fetchWithRetry(req, url, url)
        }
    }
    if (firstSegment in BEE_API_SEGMENTS) return null

    // Case 1: path escaped the current bzz/ipfs/ipns root. Rewrite it
    // back under the root and fetch. Requires the current bzz root to
    // be known — we can't escape-rewrite from a non-content page.
    val bzzRoot = state.currentBzzRoot ?: return null
    val rewritten = bzzRoot + tail
    Log.v(LOG_TAG, "gateway-escape rewrite: $url → $rewritten")
    return if (isMediaLikeUrl(rewritten)) {
        fetchMediaWithRangeSupport(req, rewritten)
    } else {
        fetchWithRetry(req, rewritten, url)
    }
}

// In-process LRU of fully-buffered media bodies keyed by bzz URL, so
// successive Range requests for the same file don't re-fetch from Bee.
// Bee returns the whole body for every Range request anyway, so the
// second Range would otherwise download the file all over again.
private data class MediaBody(val bytes: ByteArray, val mime: String)

private const val MEDIA_CACHE_MAX_ENTRIES = 4
private val mediaBodyCache: MutableMap<String, MediaBody> =
    object : java.util.LinkedHashMap<String, MediaBody>(8, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, MediaBody>?,
        ): Boolean = size > MEDIA_CACHE_MAX_ENTRIES
    }

private fun loadMediaBody(
    req: WebResourceRequest,
    targetUrl: String,
): MediaBody? {
    synchronized(mediaBodyCache) {
        mediaBodyCache[targetUrl]?.let { return it }
    }
    val conn = try {
        (URL(targetUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            req.requestHeaders?.forEach { (k, v) ->
                if (!k.equals("Host", ignoreCase = true) &&
                    !k.equals("Content-Length", ignoreCase = true) &&
                    !k.equals("Accept-Encoding", ignoreCase = true) &&
                    !k.equals("Range", ignoreCase = true)
                ) {
                    try { setRequestProperty(k, v) } catch (_: Throwable) {}
                }
            }
            setRequestProperty("Accept-Encoding", "identity")
        }
    } catch (t: Throwable) {
        Log.w(LOG_TAG, "media fetch open failed: $targetUrl", t)
        return null
    }
    return try {
        conn.connect()
        val status = conn.responseCode
        if (status !in 200..299) {
            Log.w(LOG_TAG, "media fetch status $status for $targetUrl")
            return null
        }
        val bytes = conn.inputStream.use { it.readBytes() }
        val rawCt = conn.contentType
        val mime = rawCt
            ?.substringBefore(';')
            ?.trim()
            ?.ifBlank { null }
            ?: mimeTypeFromUrl(targetUrl)
            ?: "application/octet-stream"
        val body = MediaBody(bytes, mime)
        synchronized(mediaBodyCache) { mediaBodyCache[targetUrl] = body }
        Log.i(LOG_TAG, "media cached: $targetUrl bytes=${bytes.size} mime=$mime")
        body
    } catch (t: Throwable) {
        Log.w(LOG_TAG, "media fetch failed: $targetUrl", t)
        null
    }
}

/**
 * Regex matching a single byte-range in an HTTP `Range` request header —
 * `bytes=<first>-<last>`. We only support single-range requests (the
 * common case for HTML5 media); multipart/byteranges is vanishingly rare
 * and Chromium never sends it for `<video>`.
 */
private val RANGE_REGEX = Regex("""^bytes=(\d+)?-(\d+)?$""")

/**
 * Serve a media subresource from Bee with synthetic Range support. Bee
 * itself returns the full body (with empty Content-Type and no
 * Accept-Ranges) for every request regardless of the `Range` header, so
 * Chromium's media pipeline stalls at `HAVE_METADATA` when it tries to
 * seek. We fetch the body once, cache it in-process, and answer each
 * Range request by slicing the buffer and returning a proper 206 with
 * Content-Range / Content-Length — exactly what Chromium expects.
 *
 * Also injects a real MIME type (inferred from the URL extension) so
 * the media element can pick a decoder.
 */
private fun fetchMediaWithRangeSupport(
    req: WebResourceRequest,
    targetUrl: String,
): WebResourceResponse? {
    val body = loadMediaBody(req, targetUrl) ?: return null
    val total = body.bytes.size
    val rangeHeader = req.requestHeaders?.entries
        ?.firstOrNull { it.key.equals("Range", ignoreCase = true) }
        ?.value
    val match = rangeHeader?.let { RANGE_REGEX.matchEntire(it.trim()) }
    val baseHeaders = mutableMapOf(
        "Accept-Ranges" to "bytes",
        "Access-Control-Allow-Origin" to "*",
    )
    return if (match != null) {
        val firstStr = match.groupValues[1]
        val lastStr = match.groupValues[2]
        val (start, end) = when {
            firstStr.isEmpty() && lastStr.isEmpty() -> 0 to (total - 1)
            firstStr.isEmpty() -> {
                val suffixLen = lastStr.toLong().coerceAtMost(total.toLong()).toInt()
                (total - suffixLen) to (total - 1)
            }
            lastStr.isEmpty() -> firstStr.toLong().toInt() to (total - 1)
            else -> firstStr.toLong().toInt() to lastStr.toLong().toInt().coerceAtMost(total - 1)
        }
        if (start < 0 || start >= total || end < start) {
            Log.w(LOG_TAG, "media range unsatisfiable: $rangeHeader total=$total")
            return WebResourceResponse(
                body.mime, null, 416, "Range Not Satisfiable",
                baseHeaders + ("Content-Range" to "bytes */$total"),
                ByteArrayInputStream(ByteArray(0)),
            )
        }
        val length = end - start + 1
        val slice = body.bytes.copyOfRange(start, end + 1)
        val headers = baseHeaders + mapOf(
            "Content-Range" to "bytes $start-$end/$total",
            "Content-Length" to length.toString(),
        )
        Log.v(
            LOG_TAG,
            "media 206: $targetUrl range=$start-$end/$total mime=${body.mime}",
        )
        WebResourceResponse(
            body.mime, null, 206, "Partial Content",
            headers, ByteArrayInputStream(slice),
        )
    } else {
        val headers = baseHeaders + ("Content-Length" to total.toString())
        Log.v(LOG_TAG, "media 200 full: $targetUrl bytes=$total mime=${body.mime}")
        WebResourceResponse(
            body.mime, null, 200, "OK",
            headers, ByteArrayInputStream(body.bytes),
        )
    }
}

private fun fetchWithRetry(
    req: WebResourceRequest,
    targetUrl: String,
    originalUrl: String,
): WebResourceResponse? {
    var lastResponse: WebResourceResponse? = null
    for ((index, delayMs) in ESCAPE_RETRY_DELAYS_MS.withIndex()) {
        if (delayMs > 0) {
            try {
                Thread.sleep(delayMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return lastResponse
            }
        }

        val (response, transient) = fetchOnce(req, targetUrl) ?: (null to true)
        if (response != null && !transient) {
            return response
        }
        lastResponse = response
        if (response != null) {
            Log.i(
                LOG_TAG,
                "gateway-escape transient ${response.statusCode} for $originalUrl → $targetUrl " +
                    "(attempt ${index + 1}/${ESCAPE_RETRY_DELAYS_MS.size})",
            )
        }
    }
    return lastResponse
}

/**
 * Single network attempt against [targetUrl]. Returns the response + a
 * "transient" flag indicating whether the caller should retry. `null`
 * means the request failed with a non-retriable exception (bail out so
 * the WebView can show its own network error).
 */
private fun fetchOnce(
    req: WebResourceRequest,
    targetUrl: String,
): Pair<WebResourceResponse?, Boolean>? {
    return try {
        val conn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 10_000
            instanceFollowRedirects = true
            req.requestHeaders?.forEach { (k, v) ->
                if (!k.equals("Host", ignoreCase = true) &&
                    !k.equals("Content-Length", ignoreCase = true) &&
                    !k.equals("Accept-Encoding", ignoreCase = true)
                ) {
                    try { setRequestProperty(k, v) } catch (_: Throwable) {}
                }
            }
            // Force `identity` so HttpURLConnection doesn't silently
            // decompress the body out from under us and mismatch the
            // upstream Content-Length we forward to the WebView.
            setRequestProperty("Accept-Encoding", "identity")
        }
        conn.connect()
        val status = conn.responseCode
        val reason = conn.responseMessage?.ifBlank { null } ?: "OK"
        val rawCt = conn.contentType
        val mime = rawCt
            ?.substringBefore(';')
            ?.trim()
            ?.ifBlank { null }
            // Bee occasionally serves bzz subresources with an empty or
            // generic Content-Type. Fall back to the OS MIME registry
            // (driven by the URL's file extension) so CSS / fonts / etc.
            // don't get handed `application/octet-stream` and get
            // refused by the renderer.
            ?: mimeTypeFromUrl(targetUrl)
            ?: "application/octet-stream"
        Log.v(LOG_TAG, "fetch: $targetUrl status=$status mime=$mime rawCt=$rawCt")
        val charset = rawCt
            ?.substringAfter("charset=", "")
            ?.trim()
            ?.trim('"')
            ?.ifBlank { null }

        val headers = conn.headerFields
            .asSequence()
            .mapNotNull { (k, v) ->
                if (k == null || v == null) null
                else k to v.joinToString(",")
            }
            .filter { (k, _) ->
                val lk = k.lowercase()
                lk !in HEADERS_TO_STRIP && lk != "content-length"
            }
            .toMap()

        val body = when {
            status in 200..399 -> conn.inputStream
            else -> conn.errorStream ?: ByteArrayInputStream(ByteArray(0))
        }
        val response = WebResourceResponse(mime, charset, status, reason, headers, body)
        val transient = status == 404 || status == 500
        response to transient
    } catch (t: IOException) {
        Log.w(LOG_TAG, "gateway fetch failed: $targetUrl", t)
        null
    } catch (t: Throwable) {
        Log.w(LOG_TAG, "gateway fetch unexpected failure: $targetUrl", t)
        null
    }
}

internal fun isLocalGatewayUrl(url: String): Boolean =
    url.startsWith("${SwarmNode.GATEWAY_URL}/")

/**
 * Guess the response MIME type from a URL's file extension, using the
 * system's [MimeTypeMap]. Used as a fallback when the upstream server
 * returns an empty or missing Content-Type — notably, the bee-lite
 * gateway, which hands back `Content-Type: ` for bzz subresources and
 * lets the browser sniff. HTML5 `<video>` / `<audio>` won't play
 * `application/octet-stream`, so getting a real `video/mp4` out of the
 * extension is what makes background videos on Swarm sites actually
 * render.
 */
private fun mimeTypeFromUrl(url: String): String? {
    val ext = fileExtension(url) ?: return null
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
}

private fun fileExtension(url: String): String? {
    val path = url.substringBefore('?').substringBefore('#')
    return path.substringAfterLast('.', "").lowercase().ifBlank { null }
}

private val MEDIA_EXTENSIONS: Set<String> = setOf(
    "mp4", "webm", "ogv", "ogg", "m4v", "mov", "mkv",
    "mp3", "m4a", "wav", "flac", "aac", "opus",
)

private fun isMediaLikeUrl(url: String): Boolean =
    fileExtension(url) in MEDIA_EXTENSIONS

/**
 * Map a "real" URL (what the WebView actually loaded — `http://127.0.0.1:…`
 * for Swarm content, or an external origin) to the friendly string for the
 * address bar.
 */
internal fun displayFor(actualUrl: String, state: BrowserState): String =
    DisplayUrl.forActualUrl(actualUrl, state.override)

/**
 * Android's [WebView] auto-generates a title from the page URL when the
 * document has no `<title>` element. For gateway-hosted content that's
 * something like `127.0.0.1:1633/bzz/<hash>/…`, which is useless in the
 * tab switcher / history list (and worse, leaks the raw gateway URL
 * after we went to the trouble of folding it back to `bzz://` / `ens://`
 * in [displayFor]).
 *
 * Treat any title that looks like the loaded URL (with or without the
 * scheme) as "no title" by returning an empty string, so the UI can
 * fall back to the friendly display URL.
 */
internal fun sanitizeTitle(rawTitle: String?, actualUrl: String?): String {
    val title = rawTitle.orEmpty()
    if (title.isEmpty()) return ""
    val url = actualUrl.orEmpty()
    if (url.isEmpty()) return title
    val stripped = url.substringAfter("://", url)
    return if (title == url || title == stripped ||
        stripped.startsWith(title) || title.startsWith(stripped)
    ) "" else title
}
