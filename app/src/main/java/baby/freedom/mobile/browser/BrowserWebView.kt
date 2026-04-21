package baby.freedom.mobile.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import baby.freedom.swarm.SwarmNode
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

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserWebView(
    state: BrowserState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val webView = remember {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )

            // Match the dark Compose background. WebView defaults to a
            // white background that flashes before the first page paints;
            // worse, before its first paint it can leave the Compose
            // drawing in an inconsistent state that hides the top chrome.
            // Painting a matching colour immediately keeps the UI stable
            // while we wait for the bee-lite node to come up.
            setBackgroundColor(0xFF121212.toInt())

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
            loadUrl("about:blank")

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    if (url == ABOUT_BLANK) return
                    state.currentBzzRoot = extractBzzRoot(url)
                    url?.let { state.url = displayFor(it, state) }
                    state.progress = 0
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    // Skip the priming `about:blank` load we do at construction
                    // time — otherwise it clobbers the address bar before the
                    // user's initial navigation has even started.
                    if (url == ABOUT_BLANK) return
                    state.currentBzzRoot = extractBzzRoot(url)
                    val display = displayFor(url.orEmpty(), state)
                    state.url = display
                    state.title = view?.title.orEmpty()
                    state.canGoBack = view?.canGoBack() == true
                    state.canGoForward = view?.canGoForward() == true
                    state.progress = -1
                    state.addressBarText = display
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
    }

    // Navigation: when `navCounter` changes, load the pending url.
    DisposableEffect(state.navCounter) {
        if (state.navCounter > 0 && state.pendingUrl.isNotEmpty()) {
            webView.loadUrl(state.pendingUrl)
        }
        onDispose { }
    }

    AndroidView(
        factory = { webView },
        modifier = modifier.fillMaxSize(),
    )

    // Make sure the WebView is destroyed with the composition.
    DisposableEffect(Unit) {
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }
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

/** Call from the host when the user hits the system back button. */
fun BrowserState.handleBackPress(webView: WebView): Boolean {
    return if (webView.canGoBack()) {
        webView.goBack(); true
    } else {
        false
    }
}

/**
 * Map a "real" URL (what the WebView actually loaded — `http://127.0.0.1:…`
 * for Swarm content, or an external origin) to the friendly string for the
 * address bar. Prefers the active ENS override when the URL is under its
 * base prefix, otherwise falls back to the `bzz://` rewrite.
 */
internal fun displayFor(actualUrl: String, state: BrowserState): String {
    val base = state.displayOverrideBaseUrl
    val prefix = state.displayOverridePrefix
    if (base != null && prefix != null && actualUrl.startsWith(base)) {
        return prefix + actualUrl.substring(base.length)
    }
    return SwarmResolver.toDisplay(actualUrl)
}
