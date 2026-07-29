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
import kotlinx.coroutines.flow.collectLatest
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

private const val ABOUT_BLANK = "about:blank"
private const val LOG_TAG = "BrowserWebView"

// Response headers we strip when proxying — they're either managed by the
// WebView's own transport or would confuse it if passed through verbatim.
// `content-length` is stripped because `HttpURLConnection` auto-decodes
// gzip responses for us and the incoming length is for the compressed
// body, which would be wrong for what we hand back to the WebView.
// `Set-Cookie` never crosses from the gateway to a virtual origin —
// cookies are stripped in both directions (dweb sites get localStorage
// isolation per root; cookie state would leak through the shared
// registrable domain until the PSL entry propagates).
private val HEADERS_TO_STRIP = setOf(
    "transfer-encoding", "content-encoding", "connection", "keep-alive",
    "set-cookie", "set-cookie2",
)

// Request headers we never forward upstream — either managed by
// `HttpURLConnection` itself or carrying state tied to the WebView's
// virtual origin (`Host`, `Origin`, `Referer`) that would only confuse
// the gateway. `Cookie` is stripped for the same both-directions rule
// as `Set-Cookie` above.
private val REQUEST_HEADERS_TO_STRIP = setOf(
    "host", "origin", "referer", "content-length", "accept-encoding",
    "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
    "te", "trailer", "transfer-encoding", "upgrade", "cookie",
)

// HTTP status codes we treat as transient — a cold Swarm node regularly
// answers 404 for a chunk that's still being fetched, and brief 5xx
// from the node itself resolve on retry too. Matches the retry set
// used by freedom-browser's `bzz-protocol.js` on desktop.
private val TRANSIENT_STATUSES = setOf(404, 500, 502, 503, 504)

/**
 * Apply the Swarm-specific request headers that give the node extra
 * server-side runway on transient chunk-retrieval failures. Measured on
 * the desktop port (against bee) to turn a 40 % single-chunk failure
 * rate into 100 % success on cold content; ant parses the same headers
 * for bee parity. They're ignored for non-redundant content, so they're
 * always safe to set.
 */
private fun HttpURLConnection.applySwarmRequestHeaders() {
    setRequestProperty("Swarm-Chunk-Retrieval-Timeout", "30s")
    setRequestProperty("Swarm-Redundancy-Strategy", "3")
    setRequestProperty("Swarm-Redundancy-Fallback-Mode", "true")
}

/**
 * Copy request headers from [req] onto [this] connection, stripping
 * hop-by-hop / origin-tied headers, optionally dropping `Range`
 * (when the caller wants to fetch the full body), forcing
 * `Accept-Encoding: identity`, and stamping the Swarm-* retrieval
 * hints the gateway honors.
 */
private fun HttpURLConnection.forwardProxiedHeaders(
    req: WebResourceRequest,
    stripRange: Boolean = false,
) {
    req.requestHeaders?.forEach { (k, v) ->
        val lk = k.lowercase()
        if (lk in REQUEST_HEADERS_TO_STRIP) return@forEach
        if (stripRange && lk == "range") return@forEach
        try { setRequestProperty(k, v) } catch (_: Throwable) {}
    }
    // Force `identity` so HttpURLConnection doesn't silently
    // decompress the body out from under us and mismatch the
    // upstream Content-Length we forward to the WebView.
    setRequestProperty("Accept-Encoding", "identity")
    applySwarmRequestHeaders()
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
/**
 * Encode a [Bitmap] to a PNG byte array suitable for persisting into
 * Room. Returns `null` if the bitmap is empty or compression fails —
 * callers should silently skip storing in that case.
 */
internal fun encodePngBytes(bitmap: Bitmap): ByteArray? {
    if (bitmap.width <= 0 || bitmap.height <= 0) return null
    val out = java.io.ByteArrayOutputStream()
    return try {
        val ok = bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        if (ok) out.toByteArray() else null
    } catch (t: Throwable) {
        Log.w(LOG_TAG, "favicon encode failed", t)
        null
    }
}

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
        // Route service-worker fetches through the same virtual-origin
        // interceptor as everything else (feature-gated no-op where the
        // WebView doesn't support SW interception).
        ServiceWorkerInterception.install()
        Unit
    }

    // Periodic cookie sweep (defense in depth against cookie tossing
    // across virtual origins until the PSL entry propagates — and kept
    // afterwards; see [CookieHygiene]). The on-navigation sweep in
    // onPageStarted handles the common case; this catches long-lived
    // pages that write document.cookie while sitting idle.
    LaunchedEffect(Unit) {
        while (true) {
            CookieHygiene.sweepAsync()
            kotlinx.coroutines.delay(CookieHygiene.SWEEP_INTERVAL_MS)
        }
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
                            val wv = webViews[tab.id] ?: return@collectLatest
                            // Abort any in-flight load first. Without this,
                            // hitting Home (or otherwise navigating) mid-
                            // load lets Chromium keep firing late
                            // onProgressChanged callbacks for the aborted
                            // page, which flips the top progress bar back
                            // on after navigateHome() has already cleared
                            // it to -1.
                            wv.stopLoading()
                            wv.loadUrl(pending)
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
            // wiping them once is enough. This covers the per-root
            // virtual origins too — removeAllCookies / deleteAllData
            // are origin-agnostic, so "clear browsing data" clears
            // every `*.bzz.freedom.baby`-style origin's storage along
            // with everything else.
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

    // Display URL of the most recently loaded (non-home) page. We key
    // cached favicons off this rather than [BrowserState.url] because
    // `onReceivedIcon` can fire *after* the user has hit back to home
    // (at which point `state.url` has already been reset to `""`), and
    // we still want to attribute the icon to the page it actually
    // belongs to.
    var lastLoadedDisplayUrl: String? = null

    // Flips to `true` once the current navigation has actually started
    // painting (see `onPageCommitVisible`) and flips back to `false` in
    // `onPageStarted`. Used by `onPageFinished` to suppress history
    // recording for aborted loads — e.g. the user tapped Home while
    // `spiegel.de` was still fetching; Chromium fires a synthetic
    // `onPageFinished` for the cancelled page, but since it never
    // committed (never reached first paint) we don't want a stub
    // history entry with no real title.
    var currentLoadCommitted: Boolean = false

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
                if (url == ABOUT_BLANK) {
                    // `about:blank` is our home sentinel — either the
                    // WebView's forced initial paint, a user-initiated
                    // Home tap, or a back/forward gesture that lands
                    // on the blank entry in the back stack. All three
                    // want the same end state: a home-looking tab
                    // (empty url/title/address bar) so the Compose
                    // HomeScreen overlay takes over.
                    state.url = ""
                    state.title = ""
                    state.addressBarText = ""
                    state.progress = -1
                    lastLoadedDisplayUrl = null
                    currentLoadCommitted = false
                    return
                }
                currentLoadCommitted = false
                // Entering a virtual origin: expire anything page JS
                // managed to plant via document.cookie before this
                // page gets a chance to read it.
                if (VirtualOrigin.isVirtualUrl(url)) CookieHygiene.sweepAsync(url)
                val display = url?.let { displayFor(it, state) }
                if (display != null) {
                    // For error pages, surface the URL the user was
                    // actually trying to visit (`ens://…`, `bzz://…`)
                    // instead of our internal `file:///android_asset/…`
                    // path. `lastLoadedDisplayUrl` stays on the raw
                    // file URL so the [ErrorPage.isErrorPage] guards in
                    // [onReceivedIcon] etc. still match.
                    state.url = ErrorPage.displayUrlFor(url) ?: display
                    lastLoadedDisplayUrl = display
                }
                // Refresh navigation flags here (as well as in
                // onPageFinished) so the system-back hardware button
                // works the instant a new page starts loading. If we
                // waited for onPageFinished, the user pressing back
                // mid-load would slip through the disabled BackHandler
                // and minimize the app instead of returning home.
                state.canGoBack = view?.canGoBack() == true
                state.canGoForward = view?.canGoForward() == true
                state.progress = 0
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                if (url == ABOUT_BLANK) return
                currentLoadCommitted = true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (url == ABOUT_BLANK) {
                    // See companion branch in onPageStarted. Back/
                    // forward onto about:blank never fires
                    // onPageStarted, so we must also zero out the
                    // tab state here — otherwise returning to home
                    // from a deeper page would leave state.url set
                    // to the old display URL and the HomeScreen
                    // overlay would stay hidden, showing a blank
                    // WebView instead.
                    refreshLayout.isRefreshing = false
                    state.url = ""
                    state.title = ""
                    state.addressBarText = ""
                    state.canGoBack = view?.canGoBack() == true
                    state.canGoForward = view?.canGoForward() == true
                    state.progress = -1
                    return
                }
                // Dismiss the pull-to-refresh spinner once the page has
                // finished loading (or errored out). Happens regardless
                // of whether the load was user-initiated reload or not.
                refreshLayout.isRefreshing = false
                val display = displayFor(url.orEmpty(), state)
                // See the companion comment in `onPageStarted` — for
                // error pages the address bar / `state.url` show the
                // URL the user was trying to visit, while the raw
                // `file:///android_asset/…` path is kept only on
                // `lastLoadedDisplayUrl` so the error-page guards
                // elsewhere still fire.
                val uiDisplay = ErrorPage.displayUrlFor(url) ?: display
                state.url = uiDisplay
                lastLoadedDisplayUrl = display
                state.title = sanitizeTitle(view?.title, url)
                state.canGoBack = view?.canGoBack() == true
                state.canGoForward = view?.canGoForward() == true
                state.progress = -1
                state.addressBarText = uiDisplay
                // Record the *displayed* URL (bzz://, ens://, https://) — not
                // the gateway-rewritten one — so history reflects what the
                // user actually visited. The local home page is hidden from
                // the address bar (displayFor returns "") and shouldn't
                // clutter the history either. The error page is also
                // deliberately kept out of history — it's a transient
                // state, not a destination the user meant to visit.
                if (display.isNotBlank() &&
                    !ErrorPage.isErrorPage(url) &&
                    currentLoadCommitted
                ) {
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
                if (target.startsWith("bzz://") ||
                    target.startsWith("ipfs://") ||
                    target.startsWith("ipns://") ||
                    target.startsWith("ens://")
                ) {
                    onSubmitUrl(state, target)
                    return true
                }
                return false
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
            ): WebResourceResponse? = interceptVirtualRequest(request)

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
                if (!isDwebPageUrl(failed)) return

                val display = displayFor(failed, state).ifBlank { failed }
                val code = error?.errorCode?.let { "ERR_$it" } ?: "ERR_FAILED"
                val page = ErrorPage.url(
                    errorCode = code,
                    displayUrl = display,
                    protocol = protocolForErrorPage(failed),
                    retryUrl = retryUrlFor(failed),
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
                if (!isDwebPageUrl(failed)) return

                val status = errorResponse?.statusCode ?: 0
                // The probe already waited out transient 404/500s — if we
                // got one here, the gateway answered but the content
                // genuinely isn't available (misspelled hash, etc). A
                // synthesized 502 is the interceptor telling us the
                // gateway socket itself is gone (node not running).
                val display = displayFor(failed, state).ifBlank { failed }
                val errorCode =
                    if (status == 502) "ERR_CONNECTION_REFUSED"
                    else "swarm_content_not_found"
                val page = ErrorPage.url(
                    errorCode = errorCode,
                    displayUrl = display,
                    protocol = protocolForErrorPage(failed),
                    retryUrl = retryUrlFor(failed),
                )
                Log.i(LOG_TAG, "main-frame HTTP $status for $failed → error page")
                state.clearEnsOverride()
                view?.loadUrl(page)
            }
        }

        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                // Home-sentinel loads never show a progress bar — the
                // overlay is the UI, not a loading page. Also guards
                // against late callbacks from an aborted real-page
                // load arriving after the user has already tapped
                // Home (see the stopLoading() above navCounter
                // collection).
                if (view?.url == ABOUT_BLANK) {
                    state.progress = -1
                    return
                }
                state.progress = if (newProgress in 1..99) newProgress else -1
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                state.title = sanitizeTitle(title, view?.url)
            }

            override fun onReceivedIcon(view: WebView?, icon: Bitmap?) {
                // Key the cache off the *displayed* URL (`bzz://…`,
                // `ens://…`, `https://…`), not the gateway-rewritten
                // one — otherwise bookmarks to `ens://example.eth`
                // would never find the icon we captured under
                // `http://127.0.0.1:1633/bzz/<hash>`.
                //
                // We read `lastLoadedDisplayUrl` rather than
                // [BrowserState.url] because onReceivedIcon is async:
                // for pages whose `<link rel="icon">` gets fetched
                // slowly (e.g. cold Swarm nodes that still need to
                // resolve a chunk), the callback frequently arrives
                // *after* the user has navigated back to home, at
                // which point `state.url` is already `""` and the
                // icon would otherwise be dropped.
                val display = lastLoadedDisplayUrl ?: return
                if (icon == null || display.isBlank()) return
                // Skip our own transient error page — we don't want
                // a "page load failed" icon persisted against the
                // origin the user was actually trying to visit.
                if (ErrorPage.isErrorPage(display)) return
                val bytes = encodePngBytes(icon) ?: return
                repo.storeFavicon(display, bytes)
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

// Back-off schedule for the subresource retry loop. Sized to recover
// from transient 404s on cold Swarm nodes — which frequently take several
// seconds to find a chunk via the DHT — while staying inside the
// WebView's internal ~30 s request-hang detector. ~17 s of sleeping
// across 7 attempts, plus ≤ ~1 s per attempt for a fast 404 response
// from the gateway, lands the worst-case budget near 25 s. (The desktop port
// uses ~3 min across 13 attempts, but runs via a custom Electron
// protocol handler that isn't bound by the WebView hang detector.)
private val ESCAPE_RETRY_DELAYS_MS: LongArray = longArrayOf(
    0L, 250L, 500L, 1000L, 2000L, 3000L, 5000L, 5000L,
)

// Schemes whose subresource requests we answer with a redirect to the
// virtual-origin equivalent (`<img src="bzz://…">` inside a page).
private val CONTENT_SCHEMES = setOf("bzz", "ipfs", "ipns", "ens")

/**
 * Answer a CORS preflight locally. Permissive by policy: content on
 * virtual origins is public and credential-less, and the node API on
 * localhost is only reachable from this device anyway.
 */
private fun corsPreflightResponse(req: WebResourceRequest): WebResourceResponse {
    val requestedHeaders = req.requestHeaders?.entries
        ?.firstOrNull { it.key.equals("Access-Control-Request-Headers", ignoreCase = true) }
        ?.value
    val headers = mutableMapOf(
        "Access-Control-Allow-Origin" to "*",
        "Access-Control-Allow-Methods" to "GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS",
        "Access-Control-Max-Age" to "600",
    )
    if (!requestedHeaders.isNullOrBlank()) {
        headers["Access-Control-Allow-Headers"] = requestedHeaders
    }
    return WebResourceResponse(
        "text/plain", "utf-8", 204, "No Content",
        headers, ByteArrayInputStream(ByteArray(0)),
    )
}

/** Minimal synthesized response — used for errors the interceptor must
 *  answer itself (nothing loads on a virtual host unless we answer). */
private fun syntheticResponse(
    status: Int,
    reason: String,
    body: String = "",
    extraHeaders: Map<String, String> = emptyMap(),
): WebResourceResponse = WebResourceResponse(
    "text/plain", "utf-8", status, reason,
    extraHeaders, ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)),
)

/**
 * Serve the per-root virtual https origins (see [VirtualOrigin]) — the
 * only network path for dweb content.
 *
 * 1. **Virtual hosts** (`<label>.bzz.freedom.baby` etc.): translate the
 *    host back to its content root, map path+query onto the local
 *    gateway, and proxy — main frames *included*: these hostnames never
 *    resolve in DNS, so nothing loads unless we answer here. Media gets
 *    range-aware buffering ([fetchMediaWithRangeSupport]); everything
 *    else retries transient 404/500s ([fetchWithRetry]) because a cold
 *    Swarm node regularly answers the manifest before every chunk is
 *    retrievable. `<name>.ens.…` hosts resolve the *name* per request
 *    (the origin is name-derived so storage survives content updates).
 *
 * 2. **Scheme-URL subresources** (`bzz://…` / `ipfs://…` / `ipns://…`
 *    inside a page): translated and served directly through the same
 *    gateway mapping. (A redirect to the virtual-origin form would be
 *    cleaner, but [WebResourceResponse] rejects 3xx status codes —
 *    `[300, 399]` throws — so serving the bytes is the only option.
 *    Top-level clicks still go through `shouldOverrideUrlLoading` →
 *    the submit flow.)
 *
 * Everything else — external https, and direct `http://127.0.0.1`
 * gateway calls (the sanctioned write path for dapps) — passes through
 * to Chromium's own network stack untouched.
 *
 * Error contract: the interceptor always answers for virtual hosts. A
 * gateway that's unreachable (node not running) or an ENS name that
 * doesn't resolve synthesizes a clean 502 so the main frame fails fast
 * into [ErrorPage] instead of hanging; non-GET/HEAD methods get a 405
 * (WebView interception can't carry request bodies — writes go to the
 * node API origin directly).
 */
internal fun interceptVirtualRequest(
    request: WebResourceRequest?,
): WebResourceResponse? {
    val req = request ?: return null
    val uri = req.url ?: return null
    val url = uri.toString()

    // Sanctioned write path: pages on virtual origins POST/upload to
    // the node API origin (`http://127.0.0.1:…`) directly. Those
    // requests pass through to Chromium's network stack (bodies never
    // reach the interceptor), but their CORS *preflights* are bodyless
    // — answer them here so the write path works regardless of the
    // node's own CORS configuration. The node must still stamp
    // `Access-Control-Allow-Origin` on the actual response (see
    // docs/virtual-origins-hardening.md for the ant/freedom-ipfs
    // config status).
    if (req.method == "OPTIONS" && isLocalGatewayUrl(url)) {
        return corsPreflightResponse(req)
    }

    val scheme = uri.scheme?.lowercase()
    val root: ContentRoot
    val pathAndQuery: String
    if (scheme in CONTENT_SCHEMES) {
        val parsed = VirtualOrigin.parseContentUrl(url) ?: return null
        root = parsed.first
        pathAndQuery = parsed.second.ifEmpty { "/" }
        Log.v(LOG_TAG, "scheme subresource: $url served via gateway mapping")
    } else {
        root = VirtualOrigin.parseHostOfUrl(url) ?: return null
        pathAndQuery = VirtualOrigin.pathAndQueryOf(url)
    }

    // Cross-root CORS policy: answer preflights locally (the
    // interceptor sees them — nothing else can) and stamp
    // `Access-Control-Allow-Origin: *` on content responses below.
    // `*` rather than reflect-origin: dweb content is public,
    // credentials never ride along (cookies are stripped in both
    // directions on this path), so reflecting the origin would grant
    // nothing `*` doesn't while adding a per-response branch.
    if (req.method == "OPTIONS") return corsPreflightResponse(req)

    if (req.method != "GET" && req.method != "HEAD") {
        return syntheticResponse(
            405, "Method Not Allowed",
            "Virtual dweb origins are read-only (GET/HEAD). " +
                "Send writes to the node API at ${Gateways.SWARM_BASE}.",
        )
    }

    val target = Gateways.gatewayUrlFor(root, pathAndQuery)
        ?: return syntheticResponse(
            502, "Bad Gateway",
            "No local gateway can serve this content root " +
                "(node not running, or name resolution failed).",
        )

    val response = if (isMediaLikeUrl(target)) {
        fetchMediaWithRangeSupport(req, target)
    } else {
        fetchWithRetry(req, target, url)
    }
    // A null here means the gateway socket itself is gone (connection
    // refused / node stopped). Synthesize instead of returning null —
    // null would send Chromium to DNS for a hostname that doesn't
    // exist, which surfaces as a slow, confusing resolver error.
    return response ?: syntheticResponse(
        502, "Bad Gateway",
        "The local gateway did not answer (is the node running?).",
    )
}

// In-process LRU of fully-buffered media bodies keyed by bzz URL, so
// successive Range requests for the same file don't re-fetch from the
// gateway.
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
    // Retry transient chunk-retrieval failures the same way non-media
    // subresources do. Range is stripped on outgoing fetches because
    // we always want the full body to feed the in-memory cache.
    for ((index, delayMs) in ESCAPE_RETRY_DELAYS_MS.withIndex()) {
        if (delayMs > 0) {
            try {
                Thread.sleep(delayMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
        val attempt = tryLoadMediaBody(req, targetUrl)
        when (attempt) {
            is MediaLoadResult.Ok -> return attempt.body
            MediaLoadResult.Fatal -> return null
            MediaLoadResult.Transient -> {
                Log.i(
                    LOG_TAG,
                    "media transient for $targetUrl " +
                        "(attempt ${index + 1}/${ESCAPE_RETRY_DELAYS_MS.size})",
                )
            }
        }
    }
    return null
}

private sealed class MediaLoadResult {
    data class Ok(val body: MediaBody) : MediaLoadResult()
    object Transient : MediaLoadResult()
    object Fatal : MediaLoadResult()
}

private fun tryLoadMediaBody(
    req: WebResourceRequest,
    targetUrl: String,
): MediaLoadResult {
    val conn = try {
        (URL(targetUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            forwardProxiedHeaders(req, stripRange = true)
        }
    } catch (t: Throwable) {
        Log.w(LOG_TAG, "media fetch open failed: $targetUrl", t)
        return MediaLoadResult.Fatal
    }
    return try {
        conn.connect()
        val status = conn.responseCode
        if (status in TRANSIENT_STATUSES) {
            Log.w(LOG_TAG, "media fetch transient $status for $targetUrl")
            return MediaLoadResult.Transient
        }
        if (status !in 200..299) {
            Log.w(LOG_TAG, "media fetch status $status for $targetUrl")
            return MediaLoadResult.Fatal
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
        MediaLoadResult.Ok(body)
    } catch (t: java.net.ConnectException) {
        // The gateway socket refused — the node is down; retrying the
        // whole backoff schedule would just stall the media element.
        Log.w(LOG_TAG, "media fetch unreachable: $targetUrl", t)
        MediaLoadResult.Fatal
    } catch (t: IOException) {
        Log.w(LOG_TAG, "media fetch failed: $targetUrl", t)
        MediaLoadResult.Transient
    } catch (t: Throwable) {
        Log.w(LOG_TAG, "media fetch unexpected failure: $targetUrl", t)
        MediaLoadResult.Fatal
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
 * Serve a media subresource with synthetic Range support. We fetch the
 * body once, cache it in-process, and answer each Range request by
 * slicing the buffer and returning a proper 206 with Content-Range /
 * Content-Length — exactly what Chromium expects. (Load-bearing under
 * bee, which answered every Range with the full body; kept under ant
 * so seeks are served from the buffer instead of re-hitting the node.)
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

private sealed class FetchAttempt {
    data class Response(
        val response: WebResourceResponse,
        val transient: Boolean,
    ) : FetchAttempt()

    /** Recoverable I/O failure — worth another attempt. */
    object Retry : FetchAttempt()

    /** The gateway socket refused outright — retrying is pointless;
     *  the caller should synthesize a clean error immediately. */
    object Unreachable : FetchAttempt()
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

        when (val attempt = fetchOnce(req, targetUrl)) {
            is FetchAttempt.Response -> {
                if (!attempt.transient) return attempt.response
                lastResponse = attempt.response
                Log.i(
                    LOG_TAG,
                    "transient ${attempt.response.statusCode} for $originalUrl → $targetUrl " +
                        "(attempt ${index + 1}/${ESCAPE_RETRY_DELAYS_MS.size})",
                )
            }
            FetchAttempt.Unreachable -> return lastResponse
            FetchAttempt.Retry -> {}
        }
    }
    return lastResponse
}

/** Single network attempt against [targetUrl]. */
private fun fetchOnce(
    req: WebResourceRequest,
    targetUrl: String,
): FetchAttempt {
    return try {
        val conn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = if (req.method == "HEAD") "HEAD" else "GET"
            connectTimeout = 5_000
            readTimeout = 10_000
            instanceFollowRedirects = true
            forwardProxiedHeaders(req)
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
            // Error responses with no Content-Type must never fall back
            // to octet-stream: on a main-frame load Chromium treats
            // that as a download and the navigation never finishes —
            // the tab just hangs. Plain text renders the error inline.
            ?: if (status >= 400) "text/plain" else "application/octet-stream"
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
                lk !in HEADERS_TO_STRIP && lk != "content-length" &&
                    lk != "access-control-allow-origin"
            }
            .toMap() + ("Access-Control-Allow-Origin" to "*")

        val body = when {
            status in 200..399 -> conn.inputStream
            else -> conn.errorStream ?: ByteArrayInputStream(ByteArray(0))
        }
        val response = WebResourceResponse(mime, charset, status, reason, headers, body)
        FetchAttempt.Response(response, transient = status in TRANSIENT_STATUSES)
    } catch (t: java.net.ConnectException) {
        Log.w(LOG_TAG, "gateway unreachable: $targetUrl", t)
        FetchAttempt.Unreachable
    } catch (t: IOException) {
        Log.w(LOG_TAG, "gateway fetch failed: $targetUrl", t)
        FetchAttempt.Retry
    } catch (t: Throwable) {
        Log.w(LOG_TAG, "gateway fetch unexpected failure: $targetUrl", t)
        FetchAttempt.Unreachable
    }
}

internal fun isLocalGatewayUrl(url: String): Boolean = Gateways.isLocalGateway(url)

/**
 * Should a failed main-frame load of [url] surface our in-app dweb
 * error page (as opposed to Chromium's default error UI, which is the
 * right thing for external https sites)? True for virtual-origin URLs
 * and for direct local-gateway URLs.
 */
internal fun isDwebPageUrl(url: String): Boolean =
    VirtualOrigin.isVirtualUrl(url) || isLocalGatewayUrl(url)

/**
 * "Try Again" target for the error page — a scheme the submit flow can
 * route (`bzz://…`, `ens://…`), never a raw virtual/gateway URL. The
 * bare `name.eth` display form is unroutable *inside* the `file://`
 * error page (it would resolve as a relative path), hence `ens://`.
 */
internal fun retryUrlFor(failedUrl: String): String {
    val root = VirtualOrigin.parseHostOfUrl(failedUrl)
    if (root is ContentRoot.Ens) {
        val tail = VirtualOrigin.pathAndQueryOf(failedUrl).let { if (it == "/") "" else it }
        return "ens://${root.name}$tail"
    }
    return Gateways.toDisplay(failedUrl)
}

/**
 * Pick the [ErrorPage] `protocol` hint based on which origin a failed
 * URL belongs to — virtual-origin hosts by namespace, raw gateway URLs
 * by path prefix.
 */
private fun protocolForErrorPage(failedUrl: String): String {
    when (VirtualOrigin.parseHostOfUrl(failedUrl)) {
        is ContentRoot.Bzz -> return "swarm"
        is ContentRoot.Ipfs -> return "ipfs"
        is ContentRoot.IpnsKey, is ContentRoot.IpnsName -> return "ipns"
        is ContentRoot.Ens -> return "ens"
        null -> {}
    }
    if (failedUrl.startsWith("${Gateways.SWARM_BASE}/")) return "swarm"
    val ipfsBase = Gateways.ipfsBase
    if (ipfsBase.isNotEmpty() && failedUrl.startsWith("$ipfsBase/")) {
        return if (failedUrl.startsWith("$ipfsBase/ipns/")) "ipns" else "ipfs"
    }
    return "swarm"
}

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
