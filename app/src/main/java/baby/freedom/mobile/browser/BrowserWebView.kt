package baby.freedom.mobile.browser

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Message
import android.os.SystemClock
import android.util.Log
import android.view.PixelCopy
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.ValueCallback
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.createBitmap
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import baby.freedom.mobile.data.BrowsingRepository
import kotlinx.coroutines.flow.collectLatest
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlin.math.roundToInt

internal const val ABOUT_BLANK = "about:blank"
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
    // Ours alone to set: a gateway response carrying it would pass for
    // our own in-place refusal and keep the real error page away (see
    // [nameResolutionErrorIn]).
    NAME_RESOLUTION_ERROR_HEADER.lowercase(),
)

/**
 * Header on the interceptor's refusal of a `<name>.ens.…` document whose
 * name no longer resolves to loadable content (#99). Carries the
 * [ErrorPage] code, and tells `onReceivedHttpError` that the response
 * already *is* the error page (see [nameResolutionRefusal]).
 */
internal const val NAME_RESOLUTION_ERROR_HEADER = "X-Name-Resolution-Error"

/**
 * The [ErrorPage] code in a main-frame HTTP error's headers if the
 * interceptor refused the document itself, else `null`.
 */
internal fun nameResolutionErrorIn(headers: Map<String, String>?): String? =
    headers?.entries
        ?.firstOrNull { it.key.equals(NAME_RESOLUTION_ERROR_HEADER, ignoreCase = true) }
        ?.value

/** Status for the interceptor's refusal of an ENS document. */
internal fun statusForNameResolutionError(code: String): Int =
    if (code == "ens_lookup_failed") 502 else 404

/**
 * "The main-frame document the interceptor last served for this tab was
 * a name refusal" (#99), keyed by its URL. The refusal is served on the
 * name's real URL, not on an [ErrorPage] URL, so the
 * `ErrorPage.isErrorPage` guards that keep error pages out of history
 * can't see it; the tab's client asks this instead. Written from
 * `shouldInterceptRequest` (IO thread) for every main-frame request —
 * which WebView makes before the document commits — and read on the
 * main thread from `onPageFinished`.
 */
internal class NameRefusalSlot {
    @Volatile
    private var refusedUrl: String? = null

    fun onMainFrameResponse(url: String, refusalCode: String?) {
        refusedUrl = if (refusalCode != null) url.substringBefore('#') else null
    }

    fun isRefused(url: String?): Boolean =
        url != null && url.substringBefore('#') == refusedUrl
}

/**
 * Is [req] a document load — the top-level page, or an iframe — as
 * opposed to a subresource of one? The name re-check (#99) keys on this.
 * `isForMainFrame` alone misses iframes, and navigations a service
 * worker forwards to the network (which WebView hands over as
 * not-main-frame). A navigation the SW answers from its own cache never
 * reaches us, and so isn't re-checked. Chromium
 * marks a navigation's own request with `Sec-Fetch-Dest` and an
 * `Accept` that leads with `text/html`; subresources (`fetch`, XHR,
 * scripts, styles, images) never lead with it by default.
 */
internal fun isDocumentRequest(
    isForMainFrame: Boolean,
    headers: Map<String, String>?,
): Boolean {
    if (isForMainFrame) return true
    fun header(name: String) =
        headers?.entries?.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    header("Sec-Fetch-Dest")?.trim()?.lowercase()?.let {
        return it == "document" || it == "iframe" || it == "frame"
    }
    return header("Accept")?.trim()?.lowercase()?.startsWith("text/html") == true
}

/**
 * The interceptor's answer to an ENS document it refuses: the error
 * page itself, served *as* the history entry's document rather than
 * via a `loadUrl(ErrorPage.url(…))` afterwards (#99). That navigation
 * would push a new entry and so truncate forward history — a refused
 * Back would delete the page the user just came from, and Back from
 * the error page would only land on the refused entry again. Served in
 * place, Back and Forward move past it as usual and Reload re-checks
 * the name. No script: the document is on the name's origin.
 */
internal fun nameResolutionRefusal(name: String, code: String): WebResourceResponse {
    val (title, description) = when (code) {
        "ens_not_found" -> "No content for this ENS name" to
            "This ENS name doesn't point at any content any more. The owner may " +
            "have removed its <code>contenthash</code> record, or the name has no resolver."
        "ens_unsupported_codec" -> "Unsupported content format" to
            "This ENS name now resolves to a content format Freedom Browser " +
            "cannot load yet on mobile."
        else -> "ENS lookup failed" to
            "Couldn't reach an Ethereum RPC endpoint to resolve this name. " +
            "Check your connection and try again."
    }
    val safeName = name.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    val html = """<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'">
<title>$title</title><style>
html,body{margin:0;min-height:100%}
body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;
background:#141414;color:#f5f5f5;padding:32px 20px;box-sizing:border-box;text-align:center}
.c{max-width:560px;margin:0 auto}
h1{font-size:22px;margin:24px 0 12px;color:#ff5e5e}
p{line-height:1.55;margin:0 0 20px;color:#ccc;font-size:15px}
.d{background:#1a1a1a;padding:14px 16px;border-radius:8px;font-family:ui-monospace,Menlo,monospace;
font-size:13px;color:#ff8a8a;margin:0 0 24px;word-break:break-all;white-space:pre-wrap;text-align:left}
a{display:inline-block;padding:12px 22px;background:#2c2c2c;color:#fff;border:1px solid #444;
border-radius:8px;font-size:15px;text-decoration:none}
@media (prefers-color-scheme:light){body{background:#fff;color:#24292f}h1{color:#cf222e}
p{color:#57606a}.d{background:#f6f8fa;color:#cf222e}a{background:#f6f8fa;border-color:#d0d7de;color:#24292f}}
</style></head><body><div class="c"><h1>$title</h1><p>$description</p>
<div class="d">ens://$safeName

$code</div><a href="">Try again</a></div></body></html>"""
    return WebResourceResponse(
        "text/html", "utf-8", statusForNameResolutionError(code), "Name Resolution Failed",
        mapOf(NAME_RESOLUTION_ERROR_HEADER to code, "Cache-Control" to "no-store"),
        ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)),
    )
}

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

/**
 * Does an `onPageFinished` for [finishedUrl] describe the document the
 * WebView is actually showing ([currentUrl], i.e. `WebView.getUrl()`)?
 *
 * Chromium fires a synthetic `onPageFinished` for a navigation that was
 * aborted before it committed — the user hit Stop, or a second
 * navigation superseded the first — carrying the URL of the page that
 * never loaded, while `getUrl()` goes on naming the page still on
 * screen. Only a finish that passes this test may write the tab's
 * committed address: adopting an aborted one renames the tab after a
 * site it never visited, and the bold domain label is the capsule
 * asserting "this is the site you are on" (#39).
 *
 * A `null` on either side means the WebView isn't telling us anything
 * to the contrary, so the finish is taken at face value — the same
 * behaviour as before this guard existed.
 */
internal fun finishedLoadIsCurrent(finishedUrl: String?, currentUrl: String?): Boolean =
    finishedUrl == null || currentUrl == null || finishedUrl == currentUrl

/**
 * Does a main-frame commit of [committedUrl] end the tab's pending
 * probe — the one asked for by [probeSource], aimed at [probeTarget]?
 *
 * A probe outlives the submit that started it by design: it waits for
 * the node to be ready (up to `NODE_READY_TIMEOUT_MS`, longer on a cold
 * node) and only then navigates the tab. Nothing about that is safe to
 * carry across a *different* navigation: a page that asked for
 * `ens://…` in the gap before the user's own plain http(s) Go unloaded
 * it — the http(s) path registers no probe of its own, so there is no
 * submit to supersede it — would otherwise get to move the tab a minute
 * and a half after the page that asked is gone (#54).
 *
 * Whose probe dies on a commit is the same question
 * [submitSupersedesPendingProbe] answers for submits, and the answer is
 * the same shape: a page's probe belongs to the document that asked for
 * it and goes when that document does, while a probe the *user* asked
 * for is theirs to end — by their next submit, or by Stop. The user
 * typing `swarm.eth` and tapping a link on the old page while it
 * resolves must still land on `swarm.eth`.
 *
 * The probe's own navigation is not somebody else's: [probeTarget] is
 * the URL it will hand the WebView, so its commit leaves it alone.
 * Today that never actually fires for the probe's own commit — a probe
 * navigates through [BrowserState.loadUrl], which deregisters it before
 * the WebView is even asked to load, so by the time the commit arrives
 * there is no probe left to spare. It is kept as the belt to those
 * braces: a probe that ever learns to navigate by some other door must
 * still not cancel itself.
 *
 * What the clause does do today is spare a page's probe when some
 * *other* navigation commits exactly the address the probe is headed
 * for — deliberate, and narrow: the tab is already at the probe's
 * destination, so the probe can only re-land it there or, if the
 * content never resolves, swap it for the error page naming that same
 * address.
 */
internal fun commitCancelsPendingProbe(
    probeSource: SubmitSource?,
    probeTarget: String?,
    committedUrl: String?,
): Boolean = probeSource == SubmitSource.Renderer &&
    (committedUrl == null || probeTarget == null || committedUrl != probeTarget)

/**
 * A finished load that is only waiting on first paint before it counts
 * as a visit: the raw URL the finish was for, plus the history row it
 * would write.
 */
internal data class PendingVisit(val rawUrl: String, val display: String, val title: String)

/**
 * The visit [pending] flushes when [committedUrl] paints, or `null` when
 * that paint belongs to some other document.
 *
 * `onPageFinished` and `onPageCommitVisible` come in no guaranteed
 * order. On a small, fast page — a local gateway page, a 2 kB document —
 * the load event can beat the compositor's first frame by a millisecond,
 * and the [CommittedVisitGate] that exists to keep *aborted*
 * loads out of history then silently drops a page the user really did
 * visit. So a finish that is history-worthy in every other respect is
 * parked instead of discarded, and recorded here once the paint it was
 * waiting for arrives. A load that never paints — the aborted case the
 * gate is for — never flushes.
 *
 * The match is on the raw URL, so a paint belonging to the *next*
 * navigation can't adopt the previous one's row.
 */
internal fun visitToFlush(pending: PendingVisit?, committedUrl: String?): PendingVisit? =
    pending?.takeIf { it.rawUrl == committedUrl }

/**
 * The parked visit's whole life — parked by a finish that beat its own
 * paint, flushed by that paint, and dropped by anything else.
 *
 * Single-shot, because a park that outlives its own navigation records a
 * page twice (#43). [flush] empties the slot whether or not the paint
 * matched, and [clear] empties it whenever another document takes the
 * screen: a page that never painted before the next one started is the
 * aborted case the whole gate exists for, and holding on to it means a
 * later visit to the same URL flushes the stale park on its paint *and*
 * records its own finish — two rows for one visit, the first of which
 * never displayed.
 *
 * The narrow sequence that got there: page A finishes before it paints
 * (parked), the user taps Home before A's paint arrives, and the
 * `about:blank` branches — which return early rather than flush — left
 * the park standing. It is a lifecycle rather than a `var` so that
 * "recorded exactly once per committed document" is a property of the
 * type and not of remembering to null a field in four callbacks.
 */
internal class PendingVisitSlot {
    private var parked: PendingVisit? = null

    /** What is currently parked, if anything. For tests and assertions. */
    val pending: PendingVisit?
        get() = parked

    /** Park [visit] until the paint it is waiting for arrives. */
    fun park(visit: PendingVisit) {
        parked = visit
    }

    /**
     * A new document is starting: whatever is parked belongs to the
     * previous one and never painted, so it goes no further.
     */
    fun clear() {
        parked = null
    }

    /**
     * The paint for [committedUrl] landed: the row to record, if the park
     * was waiting for exactly this document ([visitToFlush]). Empties the
     * slot either way — the park gets one paint to be redeemed by.
     */
    fun flush(committedUrl: String?): PendingVisit? {
        val flushed = visitToFlush(parked, committedUrl)
        parked = null
        return flushed
    }
}

/**
 * Has the document now on screen painted, and has its visit been
 * written yet?
 *
 * The first half is the gate that keeps *aborted* navigations out of
 * history: a load the user stopped, or one a second navigation
 * superseded, still gets a synthetic `onPageFinished`, but it never
 * reaches first paint — so only a finish for a document that
 * [commit]ted may be recorded (see [PendingVisit] for the other side of
 * that rule, the finish that merely beat its own paint).
 *
 * The second half is `once` (#53). `onPageFinished` is not once per
 * document: a *streaming* load stopped mid-body that then completes
 * server-side fires it a second time, with the same URL, the same
 * `getUrl()`, and the document still committed — and the committed
 * branch, having no way to tell the second finish from the first, wrote
 * the user's one visit into history twice. [recordOnce] is that way.
 *
 * The token it keys on is the row itself — the display URL that would
 * be written. Not a bare "already recorded" flag: a same-document
 * navigation (`history.pushState`, a hash link) gets its own
 * `onPageFinished` without an intervening `onPageStarted`, and it is a
 * different address, so it is a visit and goes on being recorded as one
 * (verified on the freedom AVD). Only a finish that would write the row
 * this document already wrote is the duplicate #53 is about.
 *
 * Stop is untouched by this. The abort rules from #41 live on
 * [BrowserState.loadAborted] and on the commit half above: a stopped
 * load that never painted is still not recorded, and a stopped load
 * that *had* painted is still the visit it was — recorded once, by
 * whichever of its finishes arrives first.
 */
internal class CommittedVisitGate {
    private var committed = false
    private var recordedDisplay: String? = null

    /** Has the document on screen painted? */
    val isCommitted: Boolean
        get() = committed

    /** A new document is starting: it has neither painted nor recorded. */
    fun startNavigation() {
        committed = false
        recordedDisplay = null
    }

    /** First paint (`onPageCommitVisible`) of the document on screen. */
    fun commit() {
        committed = true
    }

    /**
     * Claim the history row [display] for the document on screen:
     * `true` once per row, `false` for a finish that would write the
     * same row again (and for any finish before the paint — that one is
     * [PendingVisit]'s).
     */
    fun recordOnce(display: String): Boolean {
        if (!committed || recordedDisplay == display) return false
        recordedDisplay = display
        return true
    }
}

/**
 * What [BrowserState.progress] should become for a chrome-client
 * `onProgressChanged([newProgress])`.
 *
 * `-1` is the idle sentinel the capsule reads as "not loading", so both
 * ends of Chromium's 0..100 counter fold into it, and so do the two
 * callbacks that describe a load nobody is waiting on any more:
 *
 *  - [isHomeSentinel]: the `about:blank` home page is an overlay, not a
 *    loading page — and a real load aborted by a Home tap goes on
 *    reporting progress against the blank document that replaced it.
 *  - [aborted]: the user hit Stop. On an uncommitted navigation
 *    Chromium answers `stopLoading()` with a final progress callback
 *    carrying the percentage the fetch died at — never 100, and never
 *    followed by anything, because that document neither commits nor
 *    finishes. Adopting it re-lights the capsule's trace and swaps
 *    Reload back out for Stop indefinitely (#41).
 */
internal fun progressForCallback(
    newProgress: Int,
    isHomeSentinel: Boolean,
    aborted: Boolean,
): Int = when {
    isHomeSentinel || aborted -> -1
    else -> if (newProgress in 1..99) newProgress else -1
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
    val fileChooser = rememberFileChooser()

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
    //
    // A page's own new windows (`target=_blank`, `window.open()`) come
    // through here too, but synchronously from inside `onCreateWindow`:
    // Chromium wants the popup's WebView back before that callback
    // returns, so [attach] builds it right away instead of waiting for
    // the next composition to notice the new tab.
    fun attach(tab: BrowserState): WebView {
        webViews[tab.id]?.let { return it }
        val (layout, wv) = buildRefreshableWebView(
            context = context,
            state = tab,
            repo = repo,
            onSubmitUrl = { target, url ->
                tabs.requestSubmit?.invoke(target, url)
            },
            onEnterFullscreen = { view, callback ->
                tabs.enterFullscreen(tab, view, callback)
            },
            onExitFullscreen = { tabs.onFullscreenHidden(tab) },
            onRecoverNodes = { tabs.requestNodeRecovery?.invoke() },
            onCreateWindow = { attach(tabs.adoptPopup(opener = tab)) },
            onCloseWindow = { tabs.closePopup(tab) },
            // Handed to Chromium by `onCreateWindow`, which needs it
            // never to have navigated.
            isPopup = tab.openerId != null,
            fileChooser = fileChooser,
        )
        webViews[tab.id] = wv
        refreshLayouts[tab.id] = layout
        frame.addView(layout)
        return wv
    }
    run {
        val idsNow = currentIds.toSet()
        for (tab in tabs.tabs) attach(tab)
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
        // Abort whatever the given tab is loading. Chromium answers a
        // stopLoading() with a final onProgressChanged(100), which the
        // chrome client below folds into `progress = -1`; [BrowserScreen]
        // also clears the counter itself so the capsule's edge trace
        // goes out on the same frame as the tap.
        tabs.stopLoading = { tab -> webViews[tab.id]?.stopLoading() }
        // Find in page (#83). Results come back through the WebView's
        // FindListener into the tab's [FindInPageState] (see
        // [buildRefreshableWebView]).
        tabs.find = { tab, action ->
            val wv = webViews[tab.id]
            when (action) {
                is FindAction.Search -> {
                    tab.find.startSearch(action.text)
                    if (action.text.isEmpty()) wv?.clearMatches()
                    else wv?.findAllAsync(action.text)
                }
                is FindAction.Step -> wv?.findNext(action.forward)
                FindAction.Clear -> {
                    tab.find.close()
                    wv?.clearMatches()
                }
            }
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
            // Camera captures handed to pages live in our own cache/uploads
            // (served by our FileProvider), outside Chromium's cache dir.
            runCatching { fileChooser.clearCaptures() }
        }
        onDispose {
            tabs.captureActiveThumbnail = null
            tabs.clearWebViewData = null
            tabs.stopLoading = null
            tabs.find = null
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

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
private fun buildRefreshableWebView(
    context: Context,
    state: BrowserState,
    repo: BrowsingRepository,
    onSubmitUrl: (BrowserState, String) -> Unit,
    onEnterFullscreen: (View, WebChromeClient.CustomViewCallback?) -> Unit,
    onExitFullscreen: () -> Unit,
    onRecoverNodes: () -> Unit = {},
    fileChooser: FileChooser? = null,
    onCreateWindow: () -> WebView,
    onCloseWindow: () -> Unit,
    isPopup: Boolean = false,
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

    // The ENS roots this tab's documents were served from, so their
    // subresources don't follow another tab's newer answer (#99).
    val ensPins = EnsDocumentPins()

    // Is the document on screen the interceptor's in-place refusal of an
    // ENS name? Kept out of history like any other error page (#99).
    val nameRefusal = NameRefusalSlot()

    // "The document on screen has painted, and has not been written to
    // history yet." Commits in `onPageCommitVisible`, resets in
    // `onPageStarted`, and is read by `onPageFinished`: it suppresses
    // history for aborted loads — the user tapped Home while
    // `spiegel.de` was still fetching, so Chromium fires a synthetic
    // `onPageFinished` for a page that never committed and we don't
    // want a stub entry with no real title — and it holds the one
    // record slot a committed document gets, so a second finish for
    // the same document can't record it twice (#53, see
    // [CommittedVisitGate]).
    val visitGate = CommittedVisitGate()

    // "The document on screen has claimed vertical drags for itself"
    // (`touch-action` / `overscroll-behavior-y` on `<html>` / `<body>`)
    // — one of the two inputs to the pull-to-refresh arming decision
    // below, see [pullToRefreshArmed]. Probed once per document
    // because `SwipeRefreshLayout` asks its question synchronously on
    // ACTION_DOWN and JS answers arrive too late to be asked then.
    // Reset on every navigation: unknown reads as "no claim", which is
    // the pre-#56 behaviour, and the scroll-range half of the decision
    // is what carries a page that hasn't been probed yet. The answer is
    // tokened per document so a probe that outlives the page that asked
    // for it cannot speak for the page that replaced it (see
    // [RootPanProbeSlot]).
    val rootPanProbe = RootPanProbeSlot()

    /**
     * Re-read the document's root pan styles. Cheap (two
     * `getComputedStyle` reads on an already-laid-out document) and run
     * at the two moments a document's root styles become knowable and
     * then final: first paint, and load finished.
     *
     * The result is stamped with the document it was asked about, and
     * lands only if that document is still on screen when it arrives.
     */
    fun probeRootPanStyles(view: WebView?) {
        val token = rootPanProbe.beginProbe()
        view?.evaluateJavascript(ROOT_PAN_STYLES_JS) { result ->
            rootPanProbe.accept(token, rootBlocksVerticalPan(result))
        }
    }

    // Reserved mode (#66): does the document on screen have its own
    // bottom navigation the capsule would cover? [BottomChromeSlot] holds
    // the per-document token and the hysteresis; the page side is the
    // detector from [bottomUiDetectorJs], a document-start script that
    // starts once per document at first paint and is otherwise woken only
    // by events. Both platform features or neither: without the
    // document-start script the channel object would sit on every page's
    // `window` for any script to find (#69).
    val bottomChrome = BottomChromeSlot()
    val bottomUiSupported = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
        WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    // The channels back into main-frame detectors: candidates from their
    // [BOTTOM_UI_READY] (posted at document start), and the current
    // document's own once a valid report proves it. A ready can't say
    // which document sent it, so it only counts once a report does
    // ([BottomUiChannels]).
    val bottomUiChannels = BottomUiChannels<JavaScriptReplyProxy>()

    /** Send the current document's token to [targets]: the detector's start, or a fresh probe. */
    fun postBottomUiProbe(targets: List<JavaScriptReplyProxy> = bottomUiChannels.targets) {
        val token = bottomChrome.token ?: return
        val request = bottomUiProbeRequest(token)
        for (reply in targets) runCatching { reply.postMessage(request) }
    }

    /**
     * Start the detector in the document on screen, once. If its ready
     * hasn't arrived yet, the ready starts it instead (see the listener).
     */
    fun installBottomUiDetector() {
        if (!bottomUiSupported) return
        bottomChrome.install() ?: return
        postBottomUiProbe()
    }

    /**
     * Ask the current document's detector for a fresh, reported probe
     * (load finished, a same-document history change, the hysteresis
     * confirmation).
     *
     * Before first paint there is nothing to ask: the detector goes in
     * at `onPageCommitVisible` ([installBottomUiDetector]), and
     * [installIfUnpainted] lets load-finished install it for a document
     * that never reports a first paint. A cross-document commit also
     * fires `doUpdateVisitedHistory`, *before* first paint; that one
     * must not install: the detector is already in the document (a
     * document-start script) and must not start before first paint.
     *
     * A detector asked while it has no `<body>` to probe owes its
     * report and sends it on its next probe (see [bottomUiDetectorJs]).
     */
    fun requestBottomUiProbe(installIfUnpainted: Boolean = false) {
        if (!bottomUiSupported) return
        if (!bottomChrome.installed) {
            if (installIfUnpainted) installBottomUiDetector()
            return
        }
        postBottomUiProbe()
    }

    // Scroll-to-reveal (#65): a push past the end of an overlay page
    // shortens the page area by the bar's footprint, until the user
    // scrolls back up. [ScrollRevealSlot] is the gesture's state; the
    // WebView, its touch listener and the layout-change listener below
    // drive it. Assigned once the WebView exists.
    val reveal = ScrollRevealSlot()
    var cancelReveal: () -> Unit = {}
    // The reveal's resize is under way: the page is held at H by
    // translation until it can be handed over to a real scroll offset.
    var revealHandover = false
    // What the page area actually shrank by for the reveal on screen.
    var revealLandedPx = 0
    var revealLanded: (Int) -> Unit = {}

    /**
     * Publish the tab's bottom-chrome mode: reserved when the detector
     * says so (#66), else revealed while a reveal holds (#65), else
     * overlay. Reserved takes over from a reveal (same band, so the page
     * doesn't move), and the reveal is forgotten: back in overlay it
     * needs a fresh push.
     */
    fun applyBottomChrome() {
        val reserved = bottomChrome.mode == BottomChromeMode.Reserved
        if (reserved) {
            if (reveal.phase != ScrollRevealSlot.Phase.Idle) cancelReveal()
            state.bottomStripRgb = bottomChrome.color
        }
        state.bottomChromeMode = when {
            reserved -> BottomChromeMode.Reserved
            reveal.revealed -> BottomChromeMode.Revealed
            else -> BottomChromeMode.Overlay
        }
    }

    // A finish that arrived before its first paint, parked until
    // `onPageCommitVisible` says the page really is on screen (see
    // [visitToFlush]). Without it the fastest pages — the ones that
    // finish loading in the millisecond before the compositor's first
    // frame — were dropped from history by the gate above. Single-shot:
    // one paint to be redeemed by, and every navigation that starts
    // empties it (see [PendingVisitSlot]).
    val pendingVisit = PendingVisitSlot()

    // The dweb URL we already prompted a node recovery + retry for. A
    // main-frame load through the interceptor that dies mid-body
    // (Chromium's generic -1) is the signature of a node sitting on
    // dead peer sockets while still reporting Running; one redial and
    // reload fixes it, a second identical failure goes to the error
    // page. Cleared when a real page finishes so a later visit can
    // recover again.
    var autoRecoveredUrl: String? = null

    val webView = PageWebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        // Find-in-page counts (#83), interim ones included so a long
        // page's count converges visibly. Reports for a session that has
        // since ended are dropped by [FindInPageState.onResult].
        setFindListener { activeMatchOrdinal, numberOfMatches, isDoneCounting ->
            state.find.onResult(findResultFrom(activeMatchOrdinal, numberOfMatches, isDoneCounting))
        }

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
            // `target=_blank` links and `window.open()` get a real
            // window — a new tab, see `onCreateWindow` below — instead
            // of silently replacing the page that asked (#82).
            // `javaScriptCanOpenWindowsAutomatically` stays at its
            // default `false`, which is Chromium's popup blocker: a
            // window only opens from a user gesture (a tap on the link
            // or button), never from a script on its own.
            setSupportMultipleWindows(true)
        }

        // Scroll-to-reveal (#65), the View half; the decisions are in
        // [ScrollRevealSlot].
        //
        // The drag and the settle move only this view's `translationY`
        // (a RenderNode property: no layout, nothing for Chromium to
        // redo). What the rising page uncovers is its container's
        // background, set to the page's own bottom colour for the
        // gesture.
        //
        // The commit is the one real resize, and handing over from the
        // translation to it without a visible jump takes three things,
        // each found frame by frame in screen recordings on the AVD:
        //
        //  1. Scroll by the shrink *when the new size lands*, and keep the
        //     translation. Chromium draws its first frames at the new
        //     size still at the old offset (it only accepts the new one
        //     once the renderer has laid out at that size); dropping the
        //     translation right away showed the page 215 px low for 2
        //     frames.
        //  2. Drop the translation in the draw pass in which the WebView
        //     first reports the grown scroll range ([PageWebView]'s
        //     `onBeforeDraw`): that is the frame Chromium draws at the new
        //     offset. Waiting for a `VisualStateCallback` instead was 3
        //     frames late.
        //  3. Bridge the frames in between. At the new size and the old
        //     offset Chromium renders nothing for the page's last band —
        //     under the held translation that band is a hole (it showed
        //     the container's background for 2 frames). Those rows are on
        //     screen, unchanging, just before the resize, so they are
        //     copied then ([captureRevealBand]) and drawn as the
        //     container's background exactly where the hole opens, until
        //     step 2.
        var revealPx = 0f
        var revealTint: Int? = null
        // The finger the drag follows (the one that pushed), and whether
        // the rest of this gesture is the reveal's, down to the last
        // finger lifting: a second finger doesn't take the drag over,
        // and Chromium (cancelled at takeover) sees none of it.
        var revealPointerId = MotionEvent.INVALID_POINTER_ID
        var revealOwnsGesture = false
        var revealAnim: ValueAnimator? = null
        var revealBridge: Bitmap? = null
        var revealScrollFrom = 0
        var revealRangeFrom = 0
        // Tags the handover timeout with the commit it belongs to, so a
        // stale one can't end a later reveal's handover.
        val revealTimeout = RevealGeneration()

        // H in whole px, as Compose's `padding` rounds it: a held
        // translation of 215.25 over a 215 px shrink left the page a
        // quarter pixel off, i.e. a 1 px step at release.
        fun revealHeightPx(): Float {
            val density = resources.displayMetrics.density
            val navInsetPx = ViewCompat.getRootWindowInsets(this)
                ?.getInsets(WindowInsetsCompat.Type.systemBars())?.bottom ?: 0
            return (reservedFootprint((navInsetPx / density).dp).value * density).roundToInt().toFloat()
        }

        // What the rising page uncovers: the page's own bottom colour,
        // or the theme surface (what the strip falls back to as well)
        // if the sample failed or hasn't answered yet.
        fun revealBackgroundArgb(): Int =
            revealTint?.let { 0xFF000000.toInt() or it } ?: state.surfaceArgb

        fun stopRevealAnim() {
            val a = revealAnim ?: return
            revealAnim = null
            a.cancel()
        }

        fun animateReveal(to: Float, then: () -> Unit) {
            stopRevealAnim()
            val anim = ValueAnimator.ofFloat(-translationY, to)
            anim.duration = REVEAL_SETTLE_MS
            anim.interpolator = DecelerateInterpolator()
            anim.addUpdateListener { translationY = -(it.animatedValue as Float) }
            anim.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (revealAnim !== animation) return
                    revealAnim = null
                    then()
                }
            })
            revealAnim = anim
            anim.start()
        }

        /** The handover is over (or abandoned): the page stands on its own. */
        fun finishHandover() {
            onBeforeDraw = null
            revealHandover = false
            revealBridge = null
            translationY = 0f
            refreshLayout.background = null
        }

        cancelReveal = {
            stopRevealAnim()
            finishHandover()
            reveal.reset()
        }

        /**
         * Chromium draws at the new offset: drop the held translation.
         * If the page gained less range than the shrink (a page that
         * could barely scroll, vh-sized content), its offset was clamped
         * [revealShortfall] px short of where the translation holds it;
         * keep that much of the translation (no jump in this frame) and
         * settle it away, over the page's own colour rather than the
         * bridge (which shows the rows at the unclamped offset).
         */
        fun handOverReveal() {
            onBeforeDraw = null
            val shortBy = revealShortfall(revealScrollFrom, revealLandedPx, verticalRange)
            if (shortBy <= 0) {
                finishHandover()
                return
            }
            // WebView still reports the requested offset; line it up
            // with Chromium's clamped one (the handover's own scroll).
            scrollBy(0, -shortBy)
            translationY = -shortBy.toFloat()
            revealBridge = null
            refreshLayout.setBackgroundColor(revealBackgroundArgb())
            animateReveal(0f) { finishHandover() }
        }

        fun onRevealTouchDown(event: MotionEvent) {
            val allowed = revealAllowed(
                mode = state.bottomChromeMode,
                chromeEditing = state.chromeEditing,
                keyboardVisible = ViewCompat.getRootWindowInsets(this)
                    ?.isVisible(WindowInsetsCompat.Type.ime()) == true,
                isHome = !bottomUiApplies(url),
            )
            if (reveal.onDown(event.rawX, event.rawY, atEnd = !canScrollVertically(1), allowed = allowed)) {
                // The strip's colour, from the page's own bottom row
                // while it is still there to be read.
                revealTint = null
                sampleBottomRow(this) { rgb -> revealTint = rgb }
            }
        }

        fun onRevealDragStart(event: MotionEvent) {
            stopRevealAnim()
            revealPointerId = event.getPointerId(0)
            revealOwnsGesture = true
            revealPx = revealHeightPx()
            // Chromium had the gesture until now; end it there (its
            // overscroll effect relaxes, the page gets a touchcancel).
            val cancel = MotionEvent.obtain(event)
            cancel.action = MotionEvent.ACTION_CANCEL
            onTouchEvent(cancel)
            cancel.recycle()
            parent?.requestDisallowInterceptTouchEvent(true)
            refreshLayout.setBackgroundColor(revealBackgroundArgb())
        }

        fun captureAndCommitReveal() {
            if (reveal.phase != ScrollRevealSlot.Phase.Committing) return
            captureRevealBand(this, revealPx.roundToInt()) { band ->
                // A new document, reserved mode or a rotation may have
                // called the reveal off while the copy was in flight.
                if (reveal.phase != ScrollRevealSlot.Phase.Committing) return@captureRevealBand
                revealBridge = band
                // What the page area is meant to shrink by, until
                // [revealLanded] measures what it did: the restore rule
                // has this reveal's H even if the resize never lands.
                revealLandedPx = revealPx.roundToInt()
                reveal.onCommitted(unscrollable = scrollY <= 0)
                // No sample: null, i.e. the theme surface — never an
                // earlier reserved or revealed page's colour.
                state.bottomStripRgb = revealTint?.let(::rgbString)
                revealHandover = true
                // Compose shortens the page area on its next layout
                // ([contentBottomReserve]); [revealLanded] takes it from
                // there. Should the resize never come, the translation
                // doesn't stay behind.
                applyBottomChrome()
                val generation = revealTimeout.next()
                postDelayed({
                    if (!revealTimeout.isCurrent(generation) || !revealHandover || revealAnim != null) return@postDelayed
                    // The range never changed (or the resize never
                    // landed): settle rather than snap.
                    if (onBeforeDraw != null) {
                        handOverReveal()
                    } else {
                        revealBridge = null
                        refreshLayout.setBackgroundColor(revealBackgroundArgb())
                        animateReveal(0f) { finishHandover() }
                    }
                }, REVEAL_HANDOVER_TIMEOUT_MS)
            }
        }

        fun commitReveal() {
            // `PixelCopy` reads the last frame *presented*: requested in
            // the settle's final frame it caught the one before (the page
            // 13 px short of H, seen as a 13 px step in the bridge). Two
            // frames later the page has been standing still at H.
            postOnAnimation { postOnAnimation { captureAndCommitReveal() } }
        }

        fun onRevealDragEvent(event: MotionEvent) {
            val action = event.actionMasked
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                revealOwnsGesture = false
            }
            if (reveal.phase != ScrollRevealSlot.Phase.Dragging) return
            val index = event.findPointerIndex(revealPointerId)
            val released = revealDragReleased(action, event.actionIndex, index)
            when {
                action == MotionEvent.ACTION_MOVE && index >= 0 ->
                    translationY = -reveal.dragOffset(event.getRawY(index), revealPx)

                released -> {
                    val cancelled = action == MotionEvent.ACTION_CANCEL
                    when (reveal.onRelease(-translationY, revealPx, cancelled)) {
                        ScrollRevealSlot.Phase.Committing -> animateReveal(revealPx) { commitReveal() }
                        else -> animateReveal(0f) {
                            reveal.onSprungBack()
                            refreshLayout.background = null
                        }
                    }
                }
            }
        }

        revealLanded = fun(shrunkBy: Int) {
            if (!revealHandover || onBeforeDraw != null) return
            revealLandedPx = shrunkBy
            revealBridge?.let { refreshLayout.background = BottomBandDrawable(it) }
            revealScrollFrom = scrollY
            revealRangeFrom = verticalRange
            scrollBy(0, shrunkBy)
            // Chromium's frame at the new offset is the first one that
            // reports a changed range. Not "grown by the shrink": a page
            // that gains less (a short page, vh-sized content) never
            // gets there, and waited out the timeout (#70 review).
            onBeforeDraw = {
                if (verticalRange != revealRangeFrom) handOverReveal()
            }
            postInvalidateOnAnimation()
        }

        // Keep a focused form field visible when the keyboard opens.
        //
        // Chromium does scroll the focused editable into view itself,
        // but it runs that scroll against the pre-keyboard viewport:
        // the app is edge-to-edge, so the window never resizes for the
        // IME (`adjustResize` notwithstanding) and this WebView only
        // shrinks a frame later, when Compose re-pads the chrome column
        // for the IME inset — see [BrowserScreen]. A field near the
        // bottom of a page therefore lands back under the chrome and
        // stays there until the first keystroke triggers a second
        // scroll, so that character is typed blind. Re-running the
        // scroll once the shrink has actually landed puts the caret on
        // screen at focus time instead.
        //
        // Only shrinks matter (the keyboard closing re-grows us, and
        // the page is free to stay where it is), and only while the
        // page — not the address bar — owns the focus.
        addOnLayoutChangeListener { v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val shrank = (bottom - top) < (oldBottom - oldTop)
            // A height change can reach the page one input late. When
            // several land in quick succession — the keyboard sliding
            // away while the page area's reserve changes (#66), or just
            // the keyboard on its own — Chromium was seen on the freedom
            // AVD (WebView 133) keeping the page at an intermediate
            // height (`innerHeight` 805 in a 781 CSS px view) until the
            // next touch: in reserved mode that hides the page's own nav
            // behind the strip. `main` shows the same stale height after
            // the address bar's keyboard closes (3 of 4 runs). One
            // invalidate per size change makes WebView pick the final
            // size up; it is not a loop — drawing doesn't change the size.
            if ((bottom - top) != (oldBottom - oldTop)) {
                v.postInvalidate()
            }
            if (shrank && v.hasFocus()) {
                (v as WebView).evaluateJavascript(SCROLL_FOCUSED_FIELD_JS, null)
            }
            // The reveal's resize has landed (#65): hand over from the
            // drag's translation to the real, shortened page area.
            if (shrank) revealLanded(oldBottom - oldTop - (bottom - top))
            // A width change is a rotation (or a window resize): the
            // reveal height and the page's layout both move with it, so
            // a reveal is dropped rather than carried over (#65) and the
            // next one needs a fresh push. Height-only changes (the
            // keyboard, the reveal itself) leave it alone.
            if (oldRight - oldLeft > 0 && (right - left) != (oldRight - oldLeft) &&
                reveal.phase != ScrollRevealSlot.Phase.Idle
            ) {
                cancelReveal()
                applyBottomChrome()
            }
        }

        // Compact-on-scroll for the floating capsule (#30).
        //
        // A WebView scrolls itself: it consumes the touch stream in its
        // own native compositor and reports nothing up Compose's
        // nested-scroll chain, so the chrome can't observe the gesture
        // the way a `LazyColumn` would drive a
        // `TopAppBarScrollBehavior`. Its own scroll callback is the
        // signal that *is* available, so the collapse is derived from
        // that — deltas in, one Boolean out (see [CapsuleCollapseState]).
        //
        // `setOnScrollChangeListener` rather than a WebView subclass
        // overriding `onScrollChanged`: same callback, no new type, and
        // nothing else in the app listens to this view's scroll.
        val screenDensity = context.resources.displayMetrics.density
        setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            // The reveal's own scroll (the handover) is not the user's.
            if (revealHandover) return@setOnScrollChangeListener
            state.capsuleCollapse.onScroll(scrollY, oldScrollY, screenDensity)
            // Scrolled back up past the reveal height, or to the top:
            // restore (#65). The WebView grows back by H below the fold;
            // the offset is still in range, so nothing on screen moves.
            if (reveal.onScroll(distanceFromEnd, scrollY, revealLandedPx)) applyBottomChrome()
        }

        // …and the touch stream that says whether a given scroll is the
        // user's. The scroll callback alone can't: an animated
        // programmatic scroll (Chromium pulling a tapped form field into
        // view, #25's re-scroll when the WebView shrinks for the IME)
        // arrives as a run of small deltas that looks exactly like a
        // flick. A drag past the touch slop arms the state machine; the
        // next touch down disarms it, so a tap's after-effects can't
        // move the chrome. For that it only observes: the one gesture
        // it takes from the WebView is a reveal push (#65), from the
        // move that starts it to the finger lifting.
        val touchSlopPx = ViewConfiguration.get(context).scaledTouchSlop
        var touchDownY = 0f
        onBottomOverscroll = {
            // A drag that went down mid-page just reached the end with
            // the finger still down (#138): armed, and the page's bottom
            // row is on screen now to take the strip's colour from.
            if (reveal.onBottomOverscroll()) {
                revealTint = null
                sampleBottomRow(this) { rgb -> revealTint = rgb }
            }
        }
        // A drag pulling the page down past its top edge (a page with no
        // scroll range, or one already at the top) isn't going to reach
        // the end: the gesture is the page's, as before #138.
        onTopOverscroll = { reveal.onTopOverscroll() }
        setOnTouchListener { _, event ->
            // The reveal owns this gesture (#65): the page follows the
            // finger by translation only, and Chromium sees none of it.
            // A fresh gesture is nobody's yet, even if the last one's
            // UP never reached us (the view was detached mid-drag).
            if (event.actionMasked == MotionEvent.ACTION_DOWN) revealOwnsGesture = false
            if (revealOwnsGesture) {
                onRevealDragEvent(event)
                return@setOnTouchListener true
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchDownY = event.y
                    state.capsuleCollapse.onTouchDown()
                    onRevealTouchDown(event)
                }

                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.y - touchDownY) > touchSlopPx) {
                        state.capsuleCollapse.onDragPastSlop()
                    }
                    if (reveal.onMove(event.rawX, event.rawY, touchSlopPx.toFloat())) {
                        onRevealDragStart(event)
                        return@setOnTouchListener true
                    }
                }

                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    reveal.onRelease(0f, 0f, cancelled = true)
            }
            false
        }

        // The detector's messages (#66). A message is taken only from an
        // http(s) origin (see [BOTTOM_UI_ORIGIN_RULES] for why the rule
        // itself can't say that), only from the main frame, and is either
        // the detector's ready or a report in the exact shape
        // [parseBottomUiMessage] allows with the current document's token.
        if (bottomUiSupported) {
            val listener = WebViewCompat.WebMessageListener { view, message, sourceOrigin, isMainFrame, replyProxy ->
                if (sourceOrigin.scheme != "https" && sourceOrigin.scheme != "http") return@WebMessageListener
                if (message.type != WebMessageCompat.TYPE_STRING) return@WebMessageListener
                if (message.data == BOTTOM_UI_READY) {
                    // A main-frame detector at document start, from the
                    // document on screen or one still on its way in. It
                    // is started now only if it can be the painted
                    // document's own late ready (see [BottomUiChannels]);
                    // otherwise it waits for its document's first paint.
                    if (!isMainFrame) return@WebMessageListener
                    postBottomUiProbe(bottomUiChannels.onReady(replyProxy, bottomChrome.installed))
                    return@WebMessageListener
                }
                val report = parseBottomUiMessage(message.data, isMainFrame, bottomChrome.token)
                    ?: return@WebMessageListener
                bottomUiChannels.onReport(replyProxy)
                val verdict = bottomChrome.accept(report, SystemClock.uptimeMillis())
                if (verdict.changed) applyBottomChrome()
                val confirmIn = verdict.confirmInMs
                if (confirmIn != null) {
                    // One re-probe after the hysteresis gap — a single
                    // delayed message, not a timer: it asks once, and a
                    // new document in the meantime cancels it.
                    val token = bottomChrome.token
                    view.postDelayed({
                        if (bottomChrome.token == token) requestBottomUiProbe()
                    }, confirmIn)
                }
            }
            // A fresh channel name per WebView, and the script that takes
            // the channel object back off every frame's `window` before
            // the page runs (#69). Registered before the first load.
            val channel = newBottomUiChannelName()
            WebViewCompat.addWebMessageListener(this, channel, BOTTOM_UI_ORIGIN_RULES, listener)
            WebViewCompat.addDocumentStartJavaScript(this, bottomUiDetectorJs(channel), BOTTOM_UI_ORIGIN_RULES)
        }

        // Force an initial paint so the WebView's compositor surface
        // is valid even before the user submits a URL. Not for a popup:
        // Chromium rejects (crashes on) a popup WebView that has already
        // navigated, and loads the popup's own URL into it anyway.
        if (!isPopup) loadUrl(ABOUT_BLANK)

        webViewClient = object : WebViewClient() {
            // A probe the *page* asked for belongs to the page that
            // asked: any document that replaces it takes the probe with
            // it rather than letting it navigate the tab up to 90 s
            // later (#54). A probe the user asked for survives — only
            // their own next submit or Stop ends that one.
            //
            // Every main-frame commit runs this, `about:blank` very much
            // included: home is a document like any other, and the blank
            // entry is reachable both by a Home tap (which starts) and
            // by a back gesture onto it (which only finishes). A page
            // that asked for `bzz://…` and then called `history.back()`
            // onto home would otherwise sit on Home with the probe still
            // resolving, and be navigated off it minutes later.
            fun cancelProbeSupersededBy(committedUrl: String?) {
                if (commitCancelsPendingProbe(
                        probeSource = state.pendingProbeSource,
                        probeTarget = state.pendingProbeTarget,
                        committedUrl = committedUrl,
                    )
                ) {
                    state.cancelPendingProbe()
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                // The main-frame document committed: its ENS pins are now
                // the page on screen's, and subresources held waiting on
                // the commit go ahead (#99, [EnsDocumentPins]).
                ensPins.documentStarted(url)
                // A new document ends the tab's find session (#83): the
                // bar closes and the highlights go, even for a page
                // restored from the back/forward cache with the ones it
                // was cached with.
                view?.clearMatches()
                state.find.onDocumentCommitted()
                // A new document arrives with the chrome whole, however
                // far the previous one was scrolled…
                state.capsuleCollapse.expand()
                // …and with no claim on the user's vertical drags until
                // it makes one: the previous document's `touch-action`
                // is none of this one's business, not even by way of a
                // probe of its that is still in flight (#56).
                rootPanProbe.startDocument()
                // …and at full height again: a reveal belongs to the
                // document it was pushed on (#65).
                cancelReveal()
                // …and floating over the page until this document shows
                // a bottom nav of its own (#66). A report still in flight
                // from the outgoing document carries its old token and
                // is dropped.
                bottomChrome.startDocument()
                bottomUiChannels.startDocument()
                state.bottomChromeMode = BottomChromeMode.Overlay
                // …and with the progress latch open again: whatever the
                // last Stop aborted, this document is a load of its own
                // and its percentages are worth drawing (#41).
                state.loadAborted = false
                // …and above the home branch below, because the blank
                // entry ends a page's probe exactly like any other
                // document does (see [cancelProbeSupersededBy]).
                cancelProbeSupersededBy(url)
                if (url == ABOUT_BLANK) {
                    // `about:blank` is our home sentinel — either the
                    // WebView's forced initial paint, a user-initiated
                    // Home tap, or a back/forward gesture that lands
                    // on the blank entry in the back stack. All three
                    // want the same end state: a home-looking tab
                    // (empty url/title/address bar) so the Compose
                    // HomeScreen overlay takes over.
                    //
                    // Except in a popup whose opener hasn't navigated
                    // it yet: there the blank document is the page's
                    // own (`window.open('')` + `document.write`), and
                    // it stays a page (see [BrowserState.blankIsPage]).
                    if (state.blankIsPage) {
                        state.showBlankPage()
                    } else {
                        state.url = ""
                        state.addressBarText = ""
                    }
                    state.title = ""
                    state.progress = -1
                    lastLoadedDisplayUrl = null
                    visitGate.startNavigation()
                    // Home is a navigation like any other: a page that
                    // finished but had not painted when the user tapped it
                    // never displayed, and the `about:blank` branch of
                    // `onPageCommitVisible` returns early rather than
                    // flushing — so without this the park would sit there
                    // until some later visit to the same URL painted, and
                    // be recorded a second time (#43).
                    pendingVisit.clear()
                    return
                }
                visitGate.startNavigation()
                // A real document: a popup's blank start is over, and
                // `about:blank` in this tab is the home sentinel again.
                state.blankIsPage = false
                // Whatever is parked belongs to the document this one is
                // replacing, and it never painted (a paint is what would
                // have flushed it). Dropping it here is what keeps the
                // park single-shot: it cannot survive its own navigation.
                pendingVisit.clear()
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
                    val uiDisplay = ErrorPage.displayUrlFor(url) ?: display
                    state.url = uiDisplay
                    lastLoadedDisplayUrl = display
                    // Commit the address *here*, at navigation commit —
                    // not in `onPageFinished`. The new document starts
                    // painting long before its load event fires, and a
                    // single hanging subresource can hold that event off
                    // for as long as the destination site likes. Waiting
                    // for it would leave the destination's content on
                    // screen under the *previous* site's bold domain
                    // label — the capsule vouching for a site the user
                    // is no longer on. WebView posts `onPageStarted`
                    // once the main-frame navigation has committed, so
                    // this is the first moment the new document can
                    // paint, and the label flips no later than the
                    // content it describes.
                    state.addressBarText = uiDisplay
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
                // The document has laid out and painted, so its root
                // styles are real: this is the earliest the #56 probe
                // can answer, and pages are touchable from here on.
                probeRootPanStyles(view)
                if (view != null && bottomUiApplies(url)) {
                    // The bottom-nav detector starts here, once (#66).
                    installBottomUiDetector()
                }
                if (url == ABOUT_BLANK) return
                visitGate.commit()
                // A finish that beat this paint left its visit parked;
                // this is the moment it becomes real. Anything parked
                // that *isn't* this document never painted, so it goes
                // no further either way.
                //
                // The row it writes is this document's one row, so it
                // claims the gate's record slot as well — otherwise a
                // second finish for the same document (the streaming
                // case in #53) would find the slot untouched and record
                // the same visit again.
                val flushed = pendingVisit.flush(url)
                if (flushed != null && visitGate.recordOnce(flushed.display)) {
                    repo.recordVisit(flushed.display, flushed.title)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                // Re-probe: a page's own stylesheet (or its first
                // script) can be what sets `touch-action: none`, and
                // that is not necessarily in place at first paint (#56).
                probeRootPanStyles(view)
                if (view != null && bottomUiApplies(url)) {
                    // A late-mounting nav: probe again now the load is
                    // done (or install, if first paint didn't) (#66).
                    if (finishedLoadIsCurrent(url, view.url)) {
                        requestBottomUiProbe(installIfUnpainted = true)
                    }
                }
                if (url != null && !ErrorPage.isErrorPage(url) && url != ABOUT_BLANK) {
                    autoRecoveredUrl = null
                }
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
                    // …with the same popup exception (see above).
                    if (state.blankIsPage) {
                        state.showBlankPage()
                    } else {
                        state.url = ""
                        state.addressBarText = ""
                    }
                    state.title = ""
                    state.canGoBack = view?.canGoBack() == true
                    state.canGoForward = view?.canGoForward() == true
                    state.progress = -1
                    // …and drop the park for the same reason as the
                    // `onPageStarted` branch: home has the screen now, so
                    // a page that finished but had not painted by the
                    // time the user left it never will (#43). This is the
                    // branch that catches the *back* gesture onto the
                    // blank entry, which gets no `onPageStarted` at all.
                    pendingVisit.clear()
                    // …and for the same reason a page's probe ends here
                    // too: `location.href='bzz://…'` followed by
                    // `history.back()` onto home reaches the blank entry
                    // through this branch only, and a probe that outlived
                    // it would navigate the tab off Home minutes later —
                    // the hijack #54 is about (see
                    // [cancelProbeSupersededBy]).
                    cancelProbeSupersededBy(url)
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
                // …but only if this finish belongs to the document that
                // is actually on screen. An aborted navigation — the
                // user hitting Stop, or a second navigation superseding
                // the first — still gets its own `onPageFinished`, with
                // a URL that never committed and never painted a pixel
                // (verified on the freedom AVD: no `onPageStarted`, no
                // `onPageCommitVisible`, then `onPageFinished` for the
                // abandoned URL while `getUrl()` still names the old
                // page). Adopting it would rename the tab after a site
                // it never loaded — the bold label, the reload target
                // and the content on screen all disagreeing until the
                // next navigation happened to fix them (#39).
                val isCurrent = finishedLoadIsCurrent(url, view?.url)
                if (isCurrent) {
                    state.url = uiDisplay
                    lastLoadedDisplayUrl = display
                    state.title = sanitizeTitle(view?.title, url)
                    state.addressBarText = uiDisplay
                }
                state.canGoBack = view?.canGoBack() == true
                state.canGoForward = view?.canGoForward() == true
                state.progress = -1
                // Record the *displayed* URL (bzz://, ens://, https://) — not
                // the gateway-rewritten one — so history reflects what the
                // user actually visited. The local home page is hidden from
                // the address bar (displayFor returns "") and shouldn't
                // clutter the history either. The error page is also
                // deliberately kept out of history — it's a transient
                // state, not a destination the user meant to visit —
                // and so is the in-place refusal of an ENS name, which
                // sits on the name's own URL (#99, [NameRefusalSlot]).
                //
                // The gate's commit half is only reset by `onPageStarted`,
                // which an aborted load never gets — so on its synthetic
                // finish the document on screen is still the *previous*
                // page's committed one, and without [finishedLoadIsCurrent]
                // the abandoned URL went into history under the old page's
                // title.
                //
                // A load that finishes *before* its first paint is not
                // aborted, it is merely quick: its visit is parked and
                // recorded by `onPageCommitVisible` instead of being
                // dropped here (see [visitToFlush]).
                //
                // And the committed branch writes each row once, not once
                // per finish: `onPageFinished` fires twice for a streaming
                // load that was stopped mid-body and then completed
                // server-side — same URL, same `getUrl()`, still committed
                // — which recorded the visit twice (#53). The row is
                // claimed by whichever of those arrives first; a
                // *different* row (a pushState / hash navigation inside the
                // same document) is still a visit of its own (see
                // [CommittedVisitGate]).
                if (display.isNotBlank() &&
                    !ErrorPage.isErrorPage(url) &&
                    !nameRefusal.isRefused(url) &&
                    isCurrent
                ) {
                    if (visitGate.isCommitted) {
                        if (visitGate.recordOnce(display)) repo.recordVisit(display, state.title)
                    } else if (url != null) {
                        pendingVisit.park(PendingVisit(url, display, state.title))
                    }
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

            // A same-document history change (`pushState`, hash) gets
            // no commit or finish callback. This one is how the
            // bottom-nav detector hears about `pushState` / `replaceState` / `popstate` /
            // `hashchange` without the page's history methods being
            // patched (#66). It also fires for a cross-document commit,
            // before first paint; the detector isn't installed yet then,
            // and this doesn't install it (see [requestBottomUiProbe]).
            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                if (view == null || !bottomUiApplies(url)) return
                requestBottomUiProbe()
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
                //
                // Main frame only: the submit flow navigates the whole
                // tab, which is never what a subframe asked for (#36 —
                // see [submitDetourForNavigation]).
                val detoured = submitDetourForNavigation(target, request.isForMainFrame)
                // Decided *after* the detour, so a submit the #35 gate
                // ignores leaves the latch as it found it (see
                // [navigationOpensStopLatch]).
                if (navigationOpensStopLatch(request.isForMainFrame, detoured)) {
                    state.loadAborted = false
                }
                if (detoured) {
                    onSubmitUrl(state, target)
                    return true
                }
                return false
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
            ): WebResourceResponse? {
                val response = interceptVirtualRequest(request, ensPins)
                if (request?.isForMainFrame == true) {
                    nameRefusal.onMainFrameResponse(
                        request.url.toString(),
                        response?.let { nameResolutionErrorIn(it.responseHeaders) },
                    )
                }
                return response
            }

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

                if (autoRecoveredUrl != failed && view != null) {
                    autoRecoveredUrl = failed
                    Log.i(LOG_TAG, "main-frame ${error?.errorCode} for $failed → recover nodes + retry")
                    onRecoverNodes()
                    view.postDelayed({ view.loadUrl(failed) }, AUTO_RECOVER_RETRY_DELAY_MS)
                    return
                }

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
                // The interceptor's refusal of an ENS document (#99) is
                // already the error page, served in place — loading
                // ErrorPage on top would truncate forward history.
                if (nameResolutionErrorIn(errorResponse?.responseHeaders) != null) {
                    Log.i(LOG_TAG, "main-frame HTTP $status for $failed → name refused in place")
                    return
                }
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
                // collection). Stop takes the same guard via
                // [BrowserState.loadAborted] — a stopped navigation
                // that never committed gets one last progress callback
                // carrying the percentage it died at, and no callback
                // ever after it, so adopting it would leave the capsule
                // lit and stuck on Stop for good (#41).
                state.progress = progressForCallback(
                    newProgress = newProgress,
                    isHomeSentinel = view?.url == ABOUT_BLANK,
                    aborted = state.loadAborted,
                )
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                state.title = sanitizeTitle(title, view?.url)
            }

            // HTML5 fullscreen (`element.requestFullscreen()`, and the
            // native `<video>` fullscreen button). Without these two
            // overrides the WebView rejects every request and the
            // page's promise falls into its `.catch` — games, video
            // players and map apps silently stay windowed, and
            // `screen.orientation.lock()` (which Chromium only grants
            // while fullscreen) is unavailable too. The chrome
            // renders [view] in [FullscreenCustomView].
            override fun onShowCustomView(
                view: View?,
                callback: WebChromeClient.CustomViewCallback?,
            ) {
                if (view == null) {
                    callback?.onCustomViewHidden()
                    return
                }
                onEnterFullscreen(view, callback)
            }

            override fun onHideCustomView() {
                onExitFullscreen()
            }

            // `<input type=file>` (#80) — see [FileChooser].
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?,
            ): Boolean {
                if (filePathCallback == null || fileChooserParams == null) return false
                return fileChooser?.show(filePathCallback, fileChooserParams) ?: false
            }

            // A new window the page asked for (`target=_blank`,
            // `window.open()`; #82) becomes a tab of its own. The new
            // tab's WebView goes back to Chromium through the transport,
            // and Chromium loads the popup's URL into it itself — as a
            // real popup, so `window.opener` works and an OAuth-style
            // flow can post its result back to this page. Only gesture-
            // initiated requests get here at all: see
            // `setSupportMultipleWindows` above.
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?,
            ): Boolean {
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                transport.webView = onCreateWindow()
                resultMsg.sendToTarget()
                return true
            }

            // `window.close()` from script. Chromium doesn't only allow
            // this for windows a page opened — it also honours it in a
            // tab with a single history entry — so the real gate is
            // `TabsState.closePopup`, which ignores tabs with no opener.
            override fun onCloseWindow(window: WebView?) {
                onCloseWindow()
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
    refreshLayout.setOnRefreshListener {
        // Pull-to-refresh reloads the WebView directly rather than
        // going through [BrowserState.loadUrl], so it has to open the
        // Stop latch itself (#41).
        state.loadAborted = false
        webView.reload()
    }
    // Who owns a downward drag — the refresh spinner or the page.
    //
    // `SwipeRefreshLayout` asks this on the gesture's ACTION_DOWN and
    // lives with the answer for the whole gesture, so the decision is
    // made from what is knowable synchronously: the WebView's own
    // scroll range plus the last per-document probe of the root's
    // `touch-action` / `overscroll-behavior-y` (see
    // [pullToRefreshArmed]). A page that is exactly one viewport tall —
    // a full-screen map, a canvas, a game — has no overscroll to pull
    // on, so the drag stays with the page (#56).
    //
    // The callback reports "the child can scroll up", i.e. `true`
    // vetoes the gesture; we override it rather than let
    // `SwipeRefreshLayout` ask the WebView directly because WebView's
    // own canScrollUp reporting is flaky for nested scrollers and would
    // arm the spinner mid-page.
    refreshLayout.setOnChildScrollUpCallback { _, _ ->
        // `getScale()` is deprecated only in favour of `onScaleChanged`
        // tracking; it still reports the live px-per-CSS-px we need.
        !pullToRefreshArmed(
            scrollY = webView.scrollY,
            // A page revealed from no scroll range at all (#65) doesn't
            // gain pull-to-refresh from the range the reveal gave it.
            documentScrollsDown = revealAdjustedScrollsDown(
                canScrollDown = webView.canScrollVertically(1),
                revealedFromUnscrollable = reveal.revealed && reveal.revealedFromUnscrollable,
            ),
            rootBlocksVerticalPan = rootPanProbe.blocksVerticalPan,
        )
    }
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
// Grace period between prompting the nodes to redial and reloading the
// failed page: ant_resume opens the bootnode sockets in parallel, so a
// couple of seconds is enough for retrieval to have working routes.
private const val AUTO_RECOVER_RETRY_DELAY_MS = 2_500L

/** How long the reveal settles onto H, or springs back (#65). */
private const val REVEAL_SETTLE_MS = 160L

/** A reveal whose resize never lands lets go of its translation after this long. */
private const val REVEAL_HANDOVER_TIMEOUT_MS = 1_000L

/**
 * The tab's WebView. A subclass only for what `WebView` keeps
 * protected: Chromium's unconsumed overscroll, and the scroll range.
 */
internal class PageWebView(context: Context) : WebView(context) {
    /**
     * Chromium overscrolled past the bottom edge (the page didn't take
     * a drag towards the end) — never the top edge, even on a page with
     * no scroll range, where both clamp alike (see [overscrollPastEnd]).
     */
    var onBottomOverscroll: () -> Unit = {}

    /** Chromium overscrolled past the top edge (see [overscrollPastTop]). */
    var onTopOverscroll: () -> Unit = {}

    // The vertical delta of the overScrollBy call in progress (0 outside
    // one): onOverScrolled only says a clamp happened, not which edge.
    private var overScrollDeltaY = 0

    val verticalRange: Int get() = computeVerticalScrollRange() - computeVerticalScrollExtent()

    /** px between the current scroll offset and the document's end. */
    val distanceFromEnd: Int get() = (verticalRange - scrollY).coerceAtLeast(0)

    /** Called at the start of each draw of this view, before Chromium's frame is recorded. */
    var onBeforeDraw: (() -> Unit)? = null

    override fun computeScroll() {
        super.computeScroll()
        onBeforeDraw?.invoke()
    }

    // Chromium's unconsumed overscroll arrives here (WebView's
    // PrivateAccess.overScrollBy calls this view's overScrollBy), which
    // calls onOverScrolled synchronously with the clamped result.
    override fun overScrollBy(
        deltaX: Int, deltaY: Int, scrollX: Int, scrollY: Int,
        scrollRangeX: Int, scrollRangeY: Int, maxOverScrollX: Int, maxOverScrollY: Int,
        isTouchEvent: Boolean,
    ): Boolean {
        overScrollDeltaY = deltaY
        try {
            return super.overScrollBy(
                deltaX, deltaY, scrollX, scrollY, scrollRangeX, scrollRangeY,
                maxOverScrollX, maxOverScrollY, isTouchEvent,
            )
        } finally {
            overScrollDeltaY = 0
        }
    }

    override fun onOverScrolled(scrollX: Int, scrollY: Int, clampedX: Boolean, clampedY: Boolean) {
        super.onOverScrolled(scrollX, scrollY, clampedX, clampedY)
        when {
            overscrollPastEnd(overScrollDeltaY, clampedY, canScrollVertically(1)) -> onBottomOverscroll()
            overscrollPastTop(overScrollDeltaY, clampedY, canScrollVertically(-1)) -> onTopOverscroll()
        }
    }
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/**
 * Read the window's pixels along [view]'s bottom edge, just above the
 * navigation bar and its scrim ([revealSampleRowY]), and hand the
 * [dominantRgb] of its edge columns ([revealTintPixels]: half the
 * capsule's side margin, clear of its shadow) to [onRgb] (#65). `PixelCopy` of a 1 px row: a small
 * GPU read-back, answered within a frame or two, once per touch that
 * arms a reveal.
 */
private fun sampleBottomRow(view: View, onRgb: (Int) -> Unit) {
    val window = view.context.findActivity()?.window ?: return
    val handler = view.handler ?: return
    if (view.width <= 0 || view.height <= 0) return
    val loc = IntArray(2)
    view.getLocationInWindow(loc)
    val navInsetPx = ViewCompat.getRootWindowInsets(view)
        ?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
    val y = revealSampleRowY(loc[1], view.height, window.decorView.height, navInsetPx)
    val rect = Rect(loc[0], y, loc[0] + view.width, y + 1)
    val bitmap = createBitmap(rect.width(), 1)
    try {
        PixelCopy.request(window, rect, bitmap, { result ->
            if (result == PixelCopy.SUCCESS) {
                val px = IntArray(bitmap.width)
                bitmap.getPixels(px, 0, bitmap.width, 0, 0, bitmap.width, 1)
                val edgePx = (CapsuleSideMargin.value * view.resources.displayMetrics.density / 2).roundToInt()
                dominantRgb(revealTintPixels(px, edgePx))?.let(onRgb)
            }
            bitmap.recycle()
        }, handler)
    } catch (e: IllegalArgumentException) {
        bitmap.recycle()
    }
}

/**
 * Copy the window's pixels of the band a reveal's resize is about to
 * leave Chromium-less (#65): [bandPx] tall, directly above the bottom
 * [bandPx] of [view]'s (untranslated) bounds — the page's last band,
 * lifted there by the settled translation. Answers null if the copy
 * fails; the handover then runs without a bridge.
 */
private fun captureRevealBand(view: View, bandPx: Int, onBand: (Bitmap?) -> Unit) {
    val window = view.context.findActivity()?.window
    val handler = view.handler
    if (window == null || handler == null || bandPx <= 0 || view.width <= 0 || view.height <= 2 * bandPx) {
        onBand(null)
        return
    }
    val loc = IntArray(2)
    view.getLocationInWindow(loc)
    val bottom = loc[1] - view.translationY.roundToInt() + view.height - bandPx
    val rect = Rect(loc[0], bottom - bandPx, loc[0] + view.width, bottom)
    val bitmap = createBitmap(rect.width(), rect.height())
    try {
        PixelCopy.request(window, rect, bitmap, { result ->
            onBand(if (result == PixelCopy.SUCCESS) bitmap else null)
        }, handler)
    } catch (_: IllegalArgumentException) {
        onBand(null)
    }
}

/** Draws [band] across the bottom of its bounds; see [captureRevealBand]. */
private class BottomBandDrawable(private val band: Bitmap) : Drawable() {
    private val dst = Rect()

    override fun draw(canvas: Canvas) {
        val b = bounds
        dst.set(b.left, b.bottom - band.height, b.right, b.bottom)
        canvas.drawBitmap(band, null, dst, null)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

// Scroll the page's focused form field back into view — see the
// layout-change listener in [buildRefreshableWebView]. A no-op unless
// the page really has an editable focused, and `scrollIntoViewIfNeeded`
// (Chromium) only moves the page when the field isn't already fully
// visible, so the common case costs nothing visible.
//
// Seen from the top document, a field focused inside an `<iframe>` is
// reported as the IFRAME element itself, so the snippet descends
// through nested frames to the innermost active element. That only
// works for same-origin frames (`contentDocument` is null, or throws,
// across origins); a cross-origin frame keeps Chromium's own
// first-keystroke scroll. Scrolling an element inside a same-origin
// frame scrolls the ancestor documents too, so the outer page moves
// as needed.
private const val SCROLL_FOCUSED_FIELD_JS = """
(function () {
  var e = document.activeElement;
  for (var depth = 0; e && e.tagName === 'IFRAME' && depth < 8; depth++) {
    var inner = null;
    try { inner = e.contentDocument && e.contentDocument.activeElement; } catch (_) {}
    if (!inner) break;
    e = inner;
  }
  if (!e) return;
  var t = e.tagName;
  if (t !== 'INPUT' && t !== 'TEXTAREA' && t !== 'SELECT' && !e.isContentEditable) return;
  if (e.scrollIntoViewIfNeeded) e.scrollIntoViewIfNeeded(false);
  else e.scrollIntoView({ block: 'nearest' });
})();
"""

private val ESCAPE_RETRY_DELAYS_MS: LongArray = longArrayOf(
    0L, 250L, 500L, 1000L, 2000L, 3000L, 5000L, 5000L,
)

// Schemes whose subresource requests we answer with a redirect to the
// virtual-origin equivalent (`<img src="bzz://…">` inside a page).
private val CONTENT_SCHEMES = setOf("bzz", "ipfs", "ipns", "ens")

/**
 * Does a navigation request for [url] belong in the screen's submit
 * flow — the probe gate the address bar uses — rather than in
 * Chromium's own hands?
 *
 * True only for a *main-frame* content-scheme navigation. The detour
 * ends in `submit()`, which moves the whole tab: that is the right
 * answer for a link the user tapped or an error page's "Try Again", and
 * the wrong one for anything else in the document. An
 * `<iframe src="bzz://…">` is a subframe asking for a subframe's worth
 * of content; routing it through the submit flow navigated the entire
 * tab to the iframe's URL, so any page — including a plain https one —
 * could move the tab by embedding one frame, and since renderer submits
 * stopped naming their destination early (#34) the pill would keep
 * reading like the old page for the whole resolve + probe window (#36).
 *
 * Returning `false` doesn't drop the subframe load: the request falls
 * through to [interceptVirtualRequest], which serves content-scheme
 * URLs off the local gateway — the same path that already renders
 * `<img src="bzz://…">` (see its "scheme-URL subresources" case). The
 * frame gets its content, the tab stays where the user left it.
 */
internal fun submitDetourForNavigation(url: String, isForMainFrame: Boolean): Boolean {
    if (!isForMainFrame) return false
    val schemeEnd = url.indexOf("://")
    if (schemeEnd <= 0) return false
    return url.substring(0, schemeEnd).lowercase() in CONTENT_SCHEMES
}

/**
 * Whether a navigation arriving at `shouldOverrideUrlLoading` should
 * open the Stop latch ([BrowserState.loadAborted]) there and then.
 *
 * Only one that Chromium is about to perform itself. Such a load starts
 * ticking progress well before `onPageStarted` commits it, so waiting
 * for commit would leave the first seconds of the page the user tapped
 * into drawing no trace at all (#41).
 *
 * A [detoured] navigation performs nothing here: it is handed to
 * `submit()`, which the #35 gate may ignore outright — a page looping
 * `location.href='ens://…'` on top of the user's own pending probe
 * navigates nowhere, and opening the latch for it would un-latch the
 * load the user stopped, letting Chromium's one late
 * `onProgressChanged` re-light the capsule trace. When the submit *is*
 * accepted it reaches [BrowserState.loadUrl], which opens the latch as
 * part of actually scheduling the load — so the accepted case loses
 * nothing by waiting.
 */
internal fun navigationOpensStopLatch(isForMainFrame: Boolean, detoured: Boolean): Boolean =
    isForMainFrame && !detoured

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
 *
 * [ensPins] is the requesting tab's (null for service-worker fetches,
 * which belong to no tab): the ENS roots its documents were served from.
 * A main-frame request — any URL, not only a virtual one — starts the
 * incoming page's pins; they replace the page on screen's only when the
 * new document commits, from `onPageStarted` (#99, see [EnsDocumentPins]).
 * Handing WebView an answer that renders in place only marks the page
 * delivered, so the subresources that race `onPageStarted` wait for it
 * rather than guess which page they belong to.
 */
internal fun interceptVirtualRequest(
    request: WebResourceRequest?,
    ensPins: EnsDocumentPins? = null,
): WebResourceResponse? {
    val req = request ?: return null
    val url = req.url?.toString() ?: return null
    val incoming = if (req.isForMainFrame) ensPins?.beginNavigation(url) else null
    val response = interceptVirtualRequestFor(req, ensPins, incoming)
    if (incoming != null && response != null &&
        rendersInPlace(response.statusCode, response.mimeType, response.responseHeaders)
    ) {
        ensPins?.delivered(incoming)
    }
    return response
}

/**
 * Will WebView commit a main-frame response as the tab's new document,
 * rather than hand it to a download (or drop it: 204/205, a redirect)?
 * Every type WebView renders itself: markup (HTML, XHTML, SVG, XML — any
 * `+xml`), text (any `text/` type: plain, CSS, JS shown as source…), JSON,
 * images, audio and video (the media document). Anything else goes to
 * the download listener. A type missing here would leave the
 * subresources that race `onPageStarted` on the previous page's pins; a
 * type listed that doesn't render costs the page on screen's
 * subresources [EnsDocumentPins.pageFor]'s bounded wait.
 */
internal fun rendersInPlace(
    status: Int,
    mimeType: String?,
    headers: Map<String, String>?,
): Boolean {
    if (status == 204 || status == 205 || status in 300..399) return false
    val disposition = headers?.entries
        ?.firstOrNull { it.key.equals("Content-Disposition", ignoreCase = true) }?.value
    if (disposition?.trim()?.lowercase()?.startsWith("attachment") == true) return false
    val mime = mimeType?.substringBefore(';')?.trim()?.lowercase() ?: return false
    if (mime in DOWNLOADED_TEXT_TYPES) return false
    return mime.startsWith("text/") ||
        mime.startsWith("image/") ||
        mime.startsWith("audio/") ||
        mime.startsWith("video/") ||
        mime == "application/xml" ||
        mime.endsWith("+xml") ||
        mime == "application/json" ||
        mime.endsWith("+json") ||
        mime == "application/javascript"
}

/**
 * `text/` types Chromium hands to a download instead of rendering
 * (its `IsUnsupportedTextMimeType` list, the common ones).
 */
private val DOWNLOADED_TEXT_TYPES = setOf(
    "text/csv", "text/x-csv", "text/comma-separated-values",
    "text/tab-separated-values", "text/tsv",
    "text/calendar", "text/x-calendar", "text/vcalendar", "text/x-vcalendar",
    "text/vcard", "text/x-vcard", "text/x-vcf", "text/directory",
    "text/rtf", "text/ldif", "text/qif", "text/x-qif", "text/ofx",
    "text/vnd.sun.j2me.app-descriptor",
)

private fun interceptVirtualRequestFor(
    req: WebResourceRequest,
    ensPins: EnsDocumentPins?,
    incoming: EnsDocumentPins.Page?,
): WebResourceResponse? {
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

    // A document on a name-derived origin re-checks the name first —
    // Back / Forward included, which restore the history entry without
    // going through submit (#99, see [Gateways.reverifyEnsDocument]).
    // Except a non-main-frame document on a name the page on screen is
    // already pinned to — a same-name iframe, a pjax `fetch` asking for
    // `text/html`: that is part of the page, and re-pinning the name
    // would move the rest of the page's subresources to a newer root
    // under its old HTML.
    //
    // Anything but the main frame belongs to the page on screen — once
    // it is known which page that is ([EnsDocumentPins.pageFor]).
    val page = incoming ?: (root as? ContentRoot.Ens)?.let { ensPins?.pageFor(it.name) }
    if (root is ContentRoot.Ens &&
        isDocumentRequest(req.isForMainFrame, req.requestHeaders) &&
        (req.isForMainFrame || page?.uriFor(root.name) == null)
    ) {
        Gateways.reverifyEnsDocument(root.name, ensPins, page)?.let { code ->
            return nameResolutionRefusal(root.name, code)
        }
    }

    val target = Gateways.gatewayUrlFor(root, pathAndQuery, page = page)
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
