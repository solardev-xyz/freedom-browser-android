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
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.util.Log
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.PixelCopy
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import android.view.animation.DecelerateInterpolator
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.JsPromptResult
import android.webkit.JsResult
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
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
import baby.freedom.mobile.ens.EnsNormalize
import baby.freedom.mobile.ens.EnsResult
import baby.freedom.mobile.ens.NameSystem
import baby.freedom.mobile.ens.TezosDomainsResolver
import kotlinx.coroutines.flow.collectLatest
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.IOException
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
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

/**
 * The trust shield for a document that just committed at [url] (#97):
 * the recorded check of the name its address shows ([displayUrl]), or
 * `null` for an [ErrorPage] or a document the interceptor refused
 * ([NameRefusalSlot]) — neither was served from the name's answer.
 */
internal fun committedNameTrust(
    url: String?,
    displayUrl: String,
    refusal: NameRefusalSlot,
    pins: EnsDocumentPins? = null,
): NameTrust? {
    if (url == null || ErrorPage.isErrorPage(url) || refusal.isRefused(url)) return null
    // A document the tab's re-check served is described by the answer
    // it was served from — which, when the lookup failed, is the tab's
    // own earlier answer, not the session's newer one (R3-F1).
    val name = nameIn(displayUrl) ?: return null
    pins?.answerFor(name)?.let { (answer, trust) ->
        // …unless the session has since recorded the name pointing at a
        // different root (R4-F1): the fallback is content the name has
        // already been seen leaving, the same as a raw load of a stale
        // hash below, so it gets no shield either.
        if (!KnownEnsNames.isCurrentRoot(name, answer)) return null
        return trust?.let { NameTrust(name, it, answer) }
    }
    val trust = nameTrustFor(displayUrl) ?: return null
    // A raw `bzz://<hash>` load shown as the name (name preservation)
    // is the name's page only while the name still resolves to that
    // hash (R1-F2): the name's trust says nothing about content it
    // pointed at before. A document on the name's own origin with no
    // pin (restored from the back/forward cache) gets the session's
    // current answer, as before.
    val loaded = Gateways.toDisplay(url)
    val raw = CONTENT_ROOT_SCHEMES.any { loaded.startsWith(it) }
    if (raw && !KnownEnsNames.isCurrentRoot(trust.name, loaded)) return null
    return trust
}

private val CONTENT_ROOT_SCHEMES = listOf("bzz://", "ipfs://", "ipns://")

/** Status for the interceptor's refusal of an ENS document. */
internal fun statusForNameResolutionError(code: String): Int =
    if (code == "ens_lookup_failed" || code == "ens_ccip_disabled") 502 else 404

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

/** A request's `Referer` header, if it sent one (see [AdblockPage]). */
internal fun refererOf(headers: Map<String, String>?): String? =
    headers?.entries?.firstOrNull { it.key.equals("Referer", ignoreCase = true) }?.value?.trim()?.ifEmpty { null }

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
internal fun nameResolutionRefusal(
    name: String,
    code: String,
    assertedProtocol: String? = null,
): WebResourceResponse =
    WebResourceResponse(
        "text/html", "utf-8", statusForNameResolutionError(code), "Name Resolution Failed",
        mapOf(NAME_RESOLUTION_ERROR_HEADER to code, "Cache-Control" to "no-store"),
        ByteArrayInputStream(
            nameResolutionRefusalHtml(name, code, assertedProtocol).toByteArray(Charsets.UTF_8),
        ),
    )

/**
 * Title and description (HTML) of [nameResolutionRefusal]'s page for
 * [code] — the same wording `error.html` uses for that code.
 * [assertedProtocol] is the typed scheme an `ens_wrong_protocol`
 * refusal held the name to (#97).
 */
internal fun nameResolutionRefusalCopy(
    name: String,
    code: String,
    assertedProtocol: String? = null,
): Pair<String, String> {
    val system = NameSystem.forName(name)
    val label = system.label
    val tezos = system == NameSystem.TEZOS
    val chain = if (tezos) "Tezos" else "Ethereum"
    return when (code) {
        // A Reload / Back under a typed `bzz://name.eth` whose name now
        // points elsewhere (#97): the assertion holds, as it did when
        // typed — see [Gateways.reverifyEnsDocument].
        "ens_wrong_protocol" -> {
            val network = when (assertedProtocol) {
                "bzz" -> "Swarm"
                "ipfs" -> "IPFS"
                "ipns" -> "IPNS"
                else -> "the network"
            }
            val scheme = assertedProtocol?.let { "<code>$it://</code>" } ?: "scheme"
            "Name lives on a different network" to
                "This $label name no longer resolves to $network content, which the $scheme " +
                "address asks for, so nothing was loaded: Freedom doesn't switch networks " +
                "behind the address bar. Enter the name on its own to follow wherever it " +
                "points now."
        }
        "ens_not_found" -> "No content for this $label name" to
            if (tezos) {
                "This $label name doesn't point at a website any more. The owner may " +
                    "have removed its <code>web:content_url</code> record, or the name has expired."
            } else {
                "This $label name doesn't point at any content any more. The owner may " +
                    "have removed its <code>contenthash</code> record, or the name has no resolver."
            }
        "ens_unsupported_codec" -> "Unsupported content format" to
            "This $label name now resolves to a content format Freedom Browser " +
            "cannot load yet on mobile."
        "ens_ccip_disabled" -> "$label lookup failed" to
            "This name is resolved through an off-chain gateway (CCIP-Read), which is " +
            "turned off in Settings &rarr; Name resolution."
        // ENSIP-15 refused the name ([EnsNormalize]): no lookup ran.
        "ens_invalid_name" -> "Not a valid $label name" to
            "This name breaks the ENSIP-15 naming rules " +
            "(a disallowed character, mixed scripts, a lookalike, &hellip;), so Freedom Browser " +
            "won't look it up &mdash; other $label apps refuse it too, and it could be " +
            "mistaken for a different name. Check the spelling."
        "ens_name_too_long" -> "$label name too long" to
            "A label of this name is longer than the 255 bytes an $label lookup can " +
            "carry, so Freedom Browser can't ask a resolver about it. Check the address."
        // [Gateways.reverifyEnsDocument] lands here for one server's
        // record that isn't what this tab or session had — including when
        // they had nothing yet (an iframe of a name never resolved) — and
        // for one server's "no content" for a name with an earlier answer.
        // A typed navigation shows the record with Continue and "no
        // content" as "No content" with a trust note, so the copy neither
        // promises an answer to review nor claims an earlier one.
        "ens_unverified" -> "Not cross-checked" to
            "Only one $chain RPC server answered for this name, so Freedom " +
            "couldn't check its answer against another server. An answer only " +
            "one server gave is loaded here only if it matches one already " +
            "loaded in this session, so nothing was loaded. Try again, or " +
            "enter the name in the address bar to see what that server answered."
        "ens_conflict" -> "RPC servers disagreed" to
            "The $chain RPC servers Freedom asked gave different answers for " +
            "this name. At least one of them is wrong, so nothing was loaded."
        else -> "$label lookup failed" to
            if (tezos) {
                "Couldn't reach a Tezos RPC endpoint to resolve this name. " +
                    "Check your connection and try again."
            } else {
                "Couldn't reach an Ethereum RPC endpoint to resolve this name. Check your " +
                    "connection, or the endpoints in Settings &rarr; RPC providers, and try again."
            }
    }
}

/** [nameResolutionRefusal]'s page. */
internal fun nameResolutionRefusalHtml(
    name: String,
    code: String,
    assertedProtocol: String? = null,
): String {
    val (title, description) = nameResolutionRefusalCopy(name, code, assertedProtocol)
    fun esc(t: String) = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    val safeName = esc(name)
    // The spec's reason, as the address-bar path shows it in the details box.
    val reason = if (code == "ens_invalid_name") {
        (runCatching { EnsNormalize.normalize(name) }.exceptionOrNull() as? EnsNormalize.InvalidNameException)
            ?.message?.let { "\n" + esc(it) }.orEmpty()
    } else {
        ""
    }
    return inPlaceErrorPageHtml(title, description, "ens://$safeName\n\n$code$reason")
}

/**
 * A self-contained error page served *as* a refused document's own
 * response (no script, nothing fetched): [title], [descriptionHtml], and
 * [detailsHtml] in the details box (both already escaped), with a
 * Try again link that reloads the entry. [nameResolutionRefusal]'s and
 * [TorRouting]'s refusals.
 */
internal fun inPlaceErrorPageHtml(title: String, descriptionHtml: String, detailsHtml: String): String =
    """<!doctype html><html lang="en"><head><meta charset="utf-8">
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
</style></head><body><div class="c"><h1>$title</h1><p>$descriptionHtml</p>
<div class="d">$detailsHtml</div><a href="">Try again</a></div></body></html>"""

/** Where [nameWebRecordNavigation] sends a request for [pathAndQuery] on the name's origin. */
internal fun webRecordTarget(result: EnsResult.Ok, pathAndQuery: String): String =
    if (result.redirect) {
        result.uri
    } else {
        // The origin's bare `/` is no path: keep the record's own.
        TezosDomainsResolver.appendWebsiteSuffix(result.uri, pathAndQuery.takeUnless { it == "/" }.orEmpty())
    }

/**
 * The interceptor's answer to a `.tez` document whose website record is
 * now on the ordinary web (`http(s)`): a page that sends the frame
 * there, as a typed `.tez` navigation does. A content URL keeps the
 * requested [pathAndQuery]; a redirect record is the whole destination.
 * A zero-delay meta refresh replaces the name's history entry, so Back
 * doesn't land on it again; no script, as for [nameResolutionRefusal].
 * It carries [NAME_RESOLUTION_ERROR_HEADER] only to stay out of history.
 */
internal fun nameWebRecordNavigation(result: EnsResult.Ok, pathAndQuery: String): WebResourceResponse {
    val target = webRecordTarget(result, pathAndQuery)
    val safe = target.replace("&", "&amp;").replace("\"", "&quot;")
        .replace("<", "&lt;").replace(">", "&gt;")
    val html = """<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="referrer" content="no-referrer">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'">
<meta http-equiv="refresh" content="0;url=$safe">
<title>${result.name.replace("<", "&lt;")}</title></head>
<body><a href="$safe">$safe</a></body></html>"""
    return WebResourceResponse(
        "text/html", "utf-8", 200, "OK",
        mapOf(NAME_RESOLUTION_ERROR_HEADER to Gateways.ENS_WEB_RECORD, "Cache-Control" to "no-store"),
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
    crossOrigin: Boolean = false,
) {
    req.requestHeaders?.forEach { (k, v) ->
        val lk = k.lowercase()
        if (lk in REQUEST_HEADERS_TO_STRIP) return@forEach
        if (stripRange && lk == "range") return@forEach
        // A redirect hop to another origin doesn't get the credentials
        // (HttpURLConnection's own following dropped them too).
        if (crossOrigin && lk == "authorization") return@forEach
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
    covered: Boolean = false,
) {
    val context = LocalContext.current
    val repo = remember(context) { BrowsingRepository.get(context) }
    val sitePermissions = remember(context) { SitePermissionBroker.get(context) }
    val pageZoom = remember(context) { PageZoom.get(context) }
    val desktopSites = remember(context) { DesktopSites.get(context) }
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
        // A private session a dead process left behind (#86) goes
        // before any page — private or not — can load.
        PrivateProfile.discardLeftovers()
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
    // The ids in [webViews] of private tabs (#86).
    val privateIds = remember { mutableSetOf<Long>() }

    // Periodic cookie sweep (defense in depth against cookie tossing
    // across virtual origins until the PSL entry propagates — and kept
    // afterwards; see [CookieHygiene]). The on-navigation sweep in
    // onPageStarted handles the common case; this catches long-lived
    // pages that write document.cookie while sitting idle, including
    // one in a background tab re-planting a cookie after another app's
    // document loaded (R3-F2). It reads at every open tab's current
    // path, since a cookie tossed with a non-root `Path` is only
    // visible there (R3-F1). Runs on the main thread: `WebView.url`.
    LaunchedEffect(Unit) {
        while (true) {
            CookieHygiene.sweepAsync(webViews.values.mapNotNull { it.url })
            kotlinx.coroutines.delay(CookieHygiene.SWEEP_INTERVAL_MS)
        }
    }
    // A tab brought to the front: sweep at its document's path before
    // the user interacts with it — it may have sat in the background
    // while another tab planted cookies (R3-F2).
    LaunchedEffect(tabs.active.id) {
        val url = webViews[tabs.active.id]?.url
        if (CookieHygiene.coversNavigation(url)) CookieHygiene.sweepAsync(url)
    }

    /**
     * No private tab is left (#86): wipe the private profile's cookies
     * and site storage (its HTTP cache was cleared through the last
     * private WebView) and retire it for deletion, and drop
     * what the app itself kept for the session in memory: its
     * site-permission answers, zoom levels, downloads list (a private
     * download still running is cancelled, as in Chrome) and the
     * onchain apps let through despite a warning (#123).
     */
    fun endPrivateSession() {
        PrivateProfile.discard()
        sitePermissions.onPrivateSessionEnded()
        Adblock.onPrivateSessionEnded()
        pageZoom.clearPrivate()
        desktopSites.clearPrivate()
        DownloadManager.get(context).endPrivateSession()
        OnchainApps.onPrivateSessionEnded()
    }

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
            sitePermissions = sitePermissions,
            pageZoom = pageZoom,
            desktopSites = desktopSites,
            onSubmitUrl = { target, url ->
                tabs.requestSubmit?.invoke(target, url)
            },
            onEnterFullscreen = { view, callback ->
                tabs.enterFullscreen(tab, view, callback)
            },
            onExitFullscreen = { tabs.onFullscreenHidden(tab) },
            onRecoverNodes = { tabs.requestNodeRecovery?.invoke() },
            // The popup's first navigation is Chromium's own, already
            // under way in its WebView: a load of its own for the IPFS
            // phase line (#94), like a link the WebView follows.
            onCreateWindow = {
                attach(tabs.adoptPopup(opener = tab).also { it.beginLoad(inWebView = true) })
            },
            onCloseWindow = { tabs.closePopup(tab) },
            // Handed to Chromium by `onCreateWindow`, which needs it
            // never to have navigated. Not a popup rebuilt after a
            // relaunch (#183): its first navigation is the restore, not
            // one its opener asked for.
            isPopup = tab.openerId != null && tab.pendingRestore == null,
            popupOpener = {
                val openerId = tab.openerId
                val opener = tabs.tabs.firstOrNull { it.id == openerId }
                val view = openerId?.let { webViews[it] }
                if (opener != null && view != null) opener to view else null
            },
            // A reopened tab: its WebView's first navigation must be
            // the host's `restoreState` (see the creation loop below).
            restoring = tab.pendingRestore != null,
            fileChooser = fileChooser,
            // Only the tab on screen raises a menu, and only over a
            // real page (the home overlay covers `about:blank`).
            // Pinned at the press to the document pressed on; see
            // [PageContextMenuPin].
            onContextMenuPress = {
                if (tab === tabs.active && tab.url.isNotEmpty()) {
                    PageContextMenuPin(tab.id, tab.url, tab.navCounter)
                } else {
                    null
                }
            },
            onContextMenu = { pin, target -> tabs.pageContextMenu = pin.request(target) },
            onSearchSelection = { query -> tabs.requestSearchInNewTab?.invoke(query, tab.private) },
        )
        webViews[tab.id] = wv
        if (tab.private) privateIds += tab.id
        refreshLayouts[tab.id] = layout
        frame.addView(layout)
        return wv
    }
    run {
        val idsNow = currentIds.toSet()
        for (tab in tabs.tabs) {
            if (webViews[tab.id] != null) continue
            // A reopened tab (see [TabsState.reopenClosedTab]): put the
            // closed WebView's back/forward list back and load its
            // current entry. If the saved state is missing or WebView
            // won't take it, give the WebView the initial blank paint it
            // skipped and submit the page's address instead — the page
            // comes back, its history doesn't.
            val restore = tab.pendingRestore
            val wv = attach(tab)
            if (restore == null) continue
            tab.pendingRestore = null
            // The restored entry is fetched again: with its site's user
            // agent (#180), in place before the fetch starts.
            (wv as? PageWebView)?.matchUserAgentTo(tab.url)
            // A tab that outlived its WebView (#183) keeps its mute.
            if (tab.audioMuted) {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.MUTE_AUDIO)) {
                    WebViewCompat.setAudioMuted(wv, true)
                } else {
                    tab.audioMuted = false
                }
            }
            val restored = restore.webViewState?.let { wv.restoreState(it) } != null
            if (restored) {
                tab.canGoBack = wv.canGoBack()
                tab.canGoForward = wv.canGoForward()
                // The WebView loads the restored entry itself: a load of
                // its own for the IPFS phase line (#94).
                tab.beginLoad(inWebView = true)
            } else {
                wv.loadUrl(ABOUT_BLANK)
            }
            // Closed before its page committed, the restored list ends
            // on the blank entry; without a restore the WebView is on it
            // too. Either way the address goes back (and is submitted,
            // unless the user had stopped that load) once that entry
            // has finished — any earlier and the blank entry's
            // `onPageFinished` wipes the tab's address after the submit,
            // and the tab loads behind the home overlay. Only armed for
            // the entry the WebView really is on: a restored list that
            // ends on a real page never finishes a blank load, so there
            // it waits for that page instead — for a load the tab had in
            // flight over it (#183 R1-F2).
            tab.armAfterRestore(restore.afterBlank(
                restored = restored,
                currentEntryUrl = if (restored) {
                    wv.copyBackForwardList().currentItem?.url
                } else {
                    ABOUT_BLANK
                },
            ))
        }
        val toRemove = webViews.keys.filter { it !in idsNow }
        var closedPrivate = false
        for (id in toRemove) {
            val wv = webViews.remove(id) ?: continue
            if (privateIds.remove(id)) {
                closedPrivate = true
                // The last private WebView is the only handle on the
                // private profile's HTTP cache (#86): clear it through
                // this one before it goes.
                if (privateIds.isEmpty()) runCatching { wv.clearCache(true) }
            }
            val layout = refreshLayouts.remove(id)
            if (layout != null) frame.removeView(layout)
            // Take down any permission prompt the tab still had up;
            // its request is denied along with the page.
            sitePermissions.onTabClosed(id)
            RadicleProviders.onTabClosed(id)
            EthereumProviders.onTabClosed(id)
            X402Payments.onTabClosed(id)
            UnverifiedOrigins.release(wv)
            (wv as? PageWebView)?.sweptReload?.committed()
            wv.stopLoading()
            wv.destroy()
        }
        // The last private tab is gone (its WebView destroyed above):
        // the private session ends, and everything it kept goes with it.
        if (closedPrivate && privateIds.isEmpty()) endPrivateSession()
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
                        // Not one this tab's WebView was already handed:
                        // a tab that outlived its WebView (#183) comes
                        // back from its saved state instead.
                        if (counter > tab.handedNavCounter && pending.isNotEmpty()) {
                            val wv = webViews[tab.id] ?: return@collectLatest
                            tab.handedNavCounter = counter
                            // Abort any in-flight load first. Without this,
                            // hitting Home (or otherwise navigating) mid-
                            // load lets Chromium keep firing late
                            // onProgressChanged callbacks for the aborted
                            // page, which flips the top progress bar back
                            // on after navigateHome() has already cleared
                            // it to -1.
                            // Not under the load a restore put back over
                            // its page, which is still coming in: that
                            // load was in flight over a complete page,
                            // and Chromium keeps the page loading until
                            // the new one commits (#185 R4-F1).
                            val keepsPage = tab.takePutBackKeepsPage()
                            if (!keepsPage) wv.stopLoading()
                            // The user's submit scheduled this very load?
                            // Then its redirects may end in an app link
                            // (#173) — this load's, no other's.
                            val namedByUser = tab.takeUserNamedLoad(pending) && wv is PageWebView
                            val load = {
                                // From here the WebView is on this load, not
                                // the one it was showing (#94).
                                tab.handLoadToWebView()
                                if (namedByUser) {
                                    (wv as PageWebView).loadUrlNamedByUser(pending)
                                } else {
                                    wv.loadUrl(pending)
                                }
                            }
                            // One that needs the other user agent (#180)
                            // would stop that page after all: it waits
                            // for the page's finish ([PutBackHold]).
                            if (keepsPage && wv is PageWebView && wv.needsOtherUserAgentFor(pending)) {
                                wv.holdPutBack(load)
                                // The tab is busy with it: not the page's
                                // Reload under the typed address (R2-F1).
                                if (tab.progress == -1 && !tab.loadAborted) tab.progress = PUT_BACK_HOLD_PROGRESS
                            } else {
                                load()
                            }
                        }
                    }
            }
        }
    }

    AndroidView(
        factory = { frame },
        modifier = modifier.fillMaxSize(),
    )

    // A full-screen panel covers the pages ([covered]): none of them may
    // hold Android focus underneath it. Page script can take it back at
    // any time (`element.focus()` asks for view focus), and a focused
    // WebView gets the hardware keyboard's keys — typing meant for the
    // panel (the Wallet page's recovery phrase, above all) would go to a
    // page nobody can see. Blocking descendants makes those requests
    // fail for as long as the panel is up, not just when it opens.
    SideEffect {
        val blocked = frame.descendantFocusability == ViewGroup.FOCUS_BLOCK_DESCENDANTS
        if (covered == blocked) return@SideEffect
        if (covered) {
            frame.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            frame.findFocus()?.clearFocus()
        } else {
            frame.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS // ViewGroup default
        }
    }

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
        tabs.saveWebViewState = { tab -> webViews[tab.id]?.let(::saveWebViewState) }
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
        // An external IPFS gateway was switched away from (#125): a tab
        // still showing what it served would keep running it, unwarned,
        // and could write to the origin again after its one-shot cleanup.
        // Reload those tabs — falling back to a navigation that can't be
        // refused if the reload doesn't commit, a POST result's say
        // ([SweptReload]) — and hold the origins for cleanup until each
        // has committed its next document.
        UnverifiedOrigins.onSweep = { swept ->
            // Frame documents a service worker fetched belong to no known
            // tab: every tab whose document predates the fetch counts as
            // having them.
            val anyTab = UnverifiedOrigins.takeWorkerDocuments(swept)
            for (wv in webViews.values) {
                val stale = sweptDocuments(wv, swept, anyTab)
                if (stale.isEmpty()) continue
                UnverifiedOrigins.hold(wv, stale)
                if (wv is PageWebView) wv.sweptReload.swept(wv.url) else wv.reload()
            }
        }
        // Per-tab mute (#91). Only where the WebView can: without the
        // hook the switcher shows the indicator but no toggle.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MUTE_AUDIO)) {
            tabs.setAudioMuted = { tab, muted ->
                webViews[tab.id]?.let { wv ->
                    WebViewCompat.setAudioMuted(wv, muted)
                    tab.audioMuted = WebViewCompat.isAudioMuted(wv)
                }
            }
        }
        tabs.printPage = { tab ->
            webViews[tab.id]?.let { wv ->
                printWebView(wv, printJobName(tab.title, tab.addressBarText, tab.url))
            }
        }
        tabs.dropMemoryCache = { tab ->
            webViews[tab.id]?.let { wv -> runCatching { wv.clearCache(false) } }
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
            // …and a private session's own (#86), which lives in its
            // profile's stores.
            PrivateProfile.clearData()
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
            // Remembered zoom levels are keyed by the sites visited (#88).
            pageZoom.clearAll()
            // …and so are the sites asked for as desktop sites (#180).
            desktopSites.clearAll()
        }
        onDispose {
            tabs.captureActiveThumbnail = null
            tabs.clearWebViewData = null
            tabs.stopLoading = null
            tabs.saveWebViewState = null
            tabs.find = null
            tabs.printPage = null
            tabs.dropMemoryCache = null
            UnverifiedOrigins.onSweep = null
            tabs.setAudioMuted = null
        }
    }

    // Page zoom (#88): every tab's WebView at its site's level. A tab
    // picks its level up at commit (see `onPageStarted`); this follows
    // everything after that — a press in the menu, the same site changed
    // from another tab, the remembered levels landing from disk after
    // the first page of a cold start already committed.
    // Scaled by the system font scale (a config change we handle
    // ourselves, so it arrives here live), which is WebView's default.
    val fontScale = rememberUpdatedState(LocalConfiguration.current.fontScale)
    LaunchedEffect(tabs) {
        snapshotFlow {
            val scale = fontScale.value
            tabs.tabs.map {
                it.id to PageZoomLevels.textZoom(pageZoom.levelFor(it.zoomSite, it.private), scale)
            }
        }
            .collect { zooms ->
                for ((id, zoom) in zooms) {
                    val settings = webViews[id]?.settings ?: continue
                    if (settings.textZoom != zoom) settings.textZoom = zoom
                }
            }
    }

    DisposableEffect(Unit) {
        onDispose {
            // The Activity is being relaunched (#183) and the tabs live
            // on in [TabsSession] — the ViewModel store is kept on
            // exactly this condition. Each tab keeps its WebView's state
            // for the next host to restore it from, and anything its
            // page had asked for is withdrawn with the page.
            val relaunch = context.findActivity()?.isChangingConfigurations == true
            if (relaunch) {
                tabs.parkForRelaunch { tab -> webViews[tab.id]?.let(::saveWebViewState) }
                for (tab in tabs.tabs) {
                    sitePermissions.onDocumentStarted(tab)
                    RadicleProviders.onDocumentStarted(tab, url = null)
                    EthereumProviders.onDocumentStarted(tab, url = null)
                    X402Payments.onDocumentStarted(tab, view = null, url = null)
                }
            } else {
                // As when the last private tab closes (#86): the private
                // cache goes through a private WebView, before they all do.
                privateIds.firstNotNullOfOrNull { webViews[it] }?.let { runCatching { it.clearCache(true) } }
            }
            for (wv in webViews.values) {
                UnverifiedOrigins.release(wv)
                (wv as? PageWebView)?.sweptReload?.committed()
                wv.stopLoading()
                wv.destroy()
            }
            webViews.clear()
            refreshLayouts.clear()
            // Otherwise the tabs don't outlive this host, so neither does
            // a private session. Across a relaunch it goes on with its
            // tabs, whose WebViews the next host puts back on its profile.
            if (privateIds.isNotEmpty()) {
                privateIds.clear()
                if (!relaunch) endPrivateSession()
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
private fun buildRefreshableWebView(
    context: Context,
    state: BrowserState,
    repo: BrowsingRepository,
    sitePermissions: SitePermissionBroker,
    pageZoom: PageZoom,
    desktopSites: DesktopSites,
    onSubmitUrl: (BrowserState, String) -> Unit,
    onEnterFullscreen: (View, WebChromeClient.CustomViewCallback?) -> Unit,
    onExitFullscreen: () -> Unit,
    onRecoverNodes: () -> Unit = {},
    restoring: Boolean = false,
    fileChooser: FileChooser? = null,
    onCreateWindow: () -> WebView,
    onCloseWindow: () -> Unit,
    isPopup: Boolean = false,
    popupOpener: () -> Pair<BrowserState, WebView>? = { null },
    onContextMenuPress: () -> PageContextMenuPin? = { null },
    onContextMenu: (PageContextMenuPin, PageContextTarget) -> Unit = { _, _ -> },
    onSearchSelection: (String) -> Unit = {},
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

    // Audio indicator (#91): the frames whose media is audible, as their
    // detectors report it on the same channel (see [TabAudioFrames]).
    val audioFrames = TabAudioFrames<JavaScriptReplyProxy>()
    fun forgetTabAudio() {
        audioFrames.clear()
        state.playingAudio = false
    }

    // Page context menu (#84): the long-press waiting on the page's
    // `contextmenu` verdict, which the detector's document-start script
    // reports on the same hidden channel as the bottom-UI reports (see
    // [bottomUiDetectorJs]). Without that channel the page's say can't
    // be heard, so there is no menu and every long-press stays
    // Chromium's.
    val contextMenuSupported = bottomUiSupported
    var contextMenuPress: PageContextMenuPress? = null

    /** Send the current document's token to [targets]: the detector's start, or a fresh probe. */
    fun postBottomUiProbe(targets: List<JavaScriptReplyProxy> = bottomUiChannels.targets) {
        val token = bottomChrome.token ?: return
        val request = bottomUiProbeRequest(token)
        for (reply in targets) runCatching { reply.postMessage(request) }
    }

    /**
     * Could the document at [url] answer theme-colour asks itself? Only
     * where its detector can run: http(s) (the only origins the listener
     * takes), past the detector's start. Whether it actually does is
     * only known once it is heard ([ThemeColorSlot.heard]); a document
     * whose detector never runs (a CSP `sandbox` one: opaque origin, no
     * channel) never answers, so [readThemeColor] falls back for it.
     */
    fun themeColorFromDetector(url: String?): Boolean {
        if (!bottomUiSupported || !bottomChrome.installed || !bottomUiApplies(url)) return false
        val u = url!!.lowercase()
        return u.startsWith("https://") || u.startsWith("http://")
    }

    /** Ask the current document's detector for its theme colour (#92). */
    fun postThemeColorRequest(targets: List<JavaScriptReplyProxy> = bottomUiChannels.targets) {
        val token = bottomChrome.token ?: return
        val request = themeColorRequest(token)
        for (reply in targets) runCatching { reply.postMessage(request) }
    }

    // The page's theme colour behind the status bar (#92). Read at first
    // paint, when the load finishes (a tag a script adds late) and on a
    // same-document history change (an SPA route with its own colour),
    // and whenever the detector sees a `<meta>` change (a route that sets
    // its colour only after its data arrives, [THEME_COLOR_PREFIX]);
    // each read is stamped with its document and gated on that document
    // having painted, so the outgoing page can't answer for the incoming
    // one (see [ThemeColorSlot]).
    val themeColor = ThemeColorSlot()

    /** [THEME_COLOR_JS] in the page, for the read stamped [token]. */
    fun readThemeColorPageVisible(view: WebView, token: Int, onAnswer: ((Int?) -> Unit)?) {
        if (view.isDestroyed) return
        view.evaluateJavascript(THEME_COLOR_JS) { result ->
            if (themeColor.accept(token)) {
                val argb = themeColorArgb(result)
                state.themeColorArgb = argb
                onAnswer?.invoke(argb)
            }
        }
    }

    /**
     * Read the theme colour of the document on screen. A document whose
     * detector can run is asked through its channel: the detector reads
     * with functions it saved at document start, so the page can't see
     * the read, and its answer is tagged with the document's token.
     *
     * The ask goes out even before the detector has been heard (its
     * first report can land after `onPageFinished` on a fast load), and
     * isn't given up on then: if the detector still hasn't spoken
     * [DETECTOR_THEME_WAIT_MS] later, the document has none that runs
     * and is read with [THEME_COLOR_JS] instead; if it has, the ask is
     * repeated on its proved channel, since one sent before its start
     * was dropped. Anything else is read with [THEME_COLOR_JS] straight
     * away, which the page can see; [onAnswer] hears only such an answer.
     */
    fun readThemeColor(view: WebView?, onScreen: Boolean = false, onAnswer: ((Int?) -> Unit)? = null) {
        // A read posted before the host destroyed the WebView (an
        // Activity relaunch, #183) has no page to read any more.
        if (view == null || view.isDestroyed) return
        val token = themeColor.beginRead(onScreen) ?: return
        if (themeColorFromDetector(view.url)) {
            // The detector's answer lands through the painted gate;
            // `onScreen` vouches for this document the same way.
            if (onScreen) themeColor.painted()
            postThemeColorRequest()
            if (!themeColor.heard) {
                view.postDelayed({
                    if (themeColor.fallbackDue(token)) readThemeColorPageVisible(view, token, onAnswer)
                    else if (themeColor.accept(token)) postThemeColorRequest()
                }, DETECTOR_THEME_WAIT_MS)
            }
            return
        }
        readThemeColorPageVisible(view, token, onAnswer)
    }

    // A popup's blank document is the page's own while [BrowserState.blankIsPage]
    // holds, and its opener can write a whole page into it
    // (`window.open('')` + `document.write`) — which gets no navigation
    // callback at all, not even `onPageCommitVisible` (verified on the
    // AVD), and no detector either (#92). So while that document is the
    // one on screen, the frames it draws ask for a read, at most one per
    // [BLANK_PAGE_READ_MS], backing off while the answer stays the same
    // so an animating page isn't re-read for as long as it is open
    // ([BlankPageReads]).
    val blankPageReads = BlankPageReads()

    fun onBlankPageDrawn(view: WebView) {
        if (!state.blankIsPage || state.url != ABOUT_BLANK) return
        val delayMs = blankPageReads.drawn() ?: return
        view.postDelayed({
            blankPageReads.fired()
            if (state.blankIsPage && state.url == ABOUT_BLANK) {
                readThemeColor(view, onScreen = true, onAnswer = blankPageReads::answered)
            }
        }, delayMs)
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

    // A private tab's pickers and `<select>` lists open in windows of
    // their own, built on this context: [PrivateWindowContext] makes
    // them FLAG_SECURE like the Activity window (#86).
    val webView = PageWebView(
        if (state.private) PrivateWindowContext.of(context) else context,
    ).apply {
        // A private tab's WebView goes on the private session's profile
        // (#86) before anything else touches it: Chromium only takes a
        // profile change on a WebView that has never been used.
        if (state.private) PrivateProfile.attach(this)
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

        // "Desktop site" (#180): this tab's pages are requested with a
        // desktop user agent on the sites the user asked for, and as
        // the tab sees it — a private tab's choices are its session's.
        wantsDesktop = { url -> desktopSites.isDesktop(desktopSiteKey(url), state.private) }
        // A page's own tapped navigation across that line is re-issued
        // by the page, through its detector (R5-F3): only once that
        // detector has proved it is the document on screen's, with a
        // report tagged with its token.
        if (bottomUiSupported) {
            pageReissue = object : PageReissueChannel {
                private fun channel(): Pair<JavaScriptReplyProxy, String>? {
                    val token = bottomChrome.token ?: return null
                    if (!bottomChrome.installed) return null
                    return (bottomUiChannels.proved ?: return null) to token
                }

                override fun ready(url: String): Boolean {
                    val (_, token) = channel() ?: return false
                    return pageReissueRequest(token, url) != null
                }

                override fun send(url: String): Boolean {
                    val (reply, token) = channel() ?: return false
                    val ask = pageReissueRequest(token, url) ?: return false
                    return runCatching { reply.postMessage(ask) }.isSuccess
                }
            }
        }

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
            // The Geolocation API reaches onGeolocationPermissionsShowPrompt
            // (and so the site-permission prompt, #81) only while this is
            // on. It is WebView's default; spelled out because the prompt
            // depends on it.
            setGeolocationEnabled(true)
            // The selection toolbar's search is ours ("Search", added in
            // [PageWebView.startActionMode]): it uses the engine chosen
            // in Settings, in a new tab. Chromium's "Web search" would hand the
            // text to whichever app answers ACTION_WEB_SEARCH instead.
            disabledActionModeMenuItems = android.webkit.WebSettings.MENU_ITEM_WEB_SEARCH
            // `target=_blank` links and `window.open()` get a real
            // window — a new tab, see `onCreateWindow` below — instead
            // of silently replacing the page that asked (#82).
            // `javaScriptCanOpenWindowsAutomatically` stays at its
            // default `false`, which is Chromium's popup blocker: a
            // window only opens from a user gesture (a tap on the link
            // or button), never from a script on its own.
            setSupportMultipleWindows(true)
        }

        this.onSearchSelection = onSearchSelection

        // Long-press on a link or an image raises the page context menu
        // (#84) — but only once the page has had its DOM `contextmenu`
        // event and let it through ([PageContextMenuPress]). The press
        // itself is never taken: Chromium calls this listener *before*
        // it hands the long-press to the page, so returning `true` here
        // would keep the event from the page altogether (no
        // preventDefault, no site long-press UI).
        // Anything that isn't a link or image stays wholly Chromium's: a
        // long-press on text starts a selection, whose toolbar carries
        // the selection actions.
        //
        // The hit test is answered synchronously; the link's own address
        // and text (for an image inside a link, the hit test only
        // reports the image) come from `requestFocusNodeHref`, which
        // answers through a Message, and the page's verdict comes
        // through the bottom-UI channel, from the `contextmenu` listener
        // the detector's document-start script adds in every frame
        // ([bottomUiDetectorJs], registered below). The menu opens when
        // both have landed.
        setOnLongClickListener {
            if (!contextMenuSupported) return@setOnLongClickListener false
            val hit = hitTestResult
            val type = hit.type
            val extra = hit.extra
            // Only a press certain to have a target once the href lands.
            // An image inside a link is certain when the image itself is
            // fetchable; a `blob:` image inside a link hangs on the link,
            // which may yet resolve to nothing (`javascript:`), so that
            // case gets no menu.
            if (!pageContextMenuIsCertain(type, extra)) return@setOnLongClickListener false
            // Which document this press is on is read now, not when the
            // answers land: a navigation committing in between must
            // leave the menu stale, not re-pin it to the new page.
            val pin = onContextMenuPress() ?: return@setOnLongClickListener false
            val press = PageContextMenuPress(pin, type, extra, SystemClock.uptimeMillis())
            contextMenuPress = press
            val reply = android.os.Handler(android.os.Looper.getMainLooper()) { msg ->
                press.onHref(msg.data.getString("url"), msg.data.getString("title"))
                    ?.let { onContextMenu(press.pin, it) }
                true
            }
            requestFocusNodeHref(reply.obtainMessage())
            false
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
        // A popup's written blank page tells us of itself only by drawing (#92).
        onDrawn = { onBlankPageDrawn(this) }
        // A `theme-color`'s `media` can ask about anything the page is
        // rendered under: the colour scheme (a light/dark pair), but just
        // as well the orientation, the width or the resolution (#92). The
        // Activity handles those configuration changes itself, so no
        // navigation follows a live light/dark switch, a rotation, a
        // split-screen resize or a fold: read again once a frame drawn
        // under the new environment is on screen — asked any earlier,
        // `matchMedia` can still answer for the old one. Every tab's
        // WebView stays attached to the one frame (a background tab is
        // only hidden), so a background tab hears the change and
        // re-reads too. A burst (a rotation is a configuration change
        // and a resize) reads once, for its last change: each change asks
        // for a frame, and only the latest ask's frame reads. (Not a
        // "pending" flag — a hidden tab's frame may never come, and a
        // stuck flag would silence it for good.)
        var mediaChange = 0L
        onMediaEnvironmentChanged = {
            postVisualStateCallback(++mediaChange, object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) {
                    if (requestId == mediaChange) readThemeColor(this@apply)
                }
            })
        }
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
                // A frame's media became audible or fell silent (#91):
                // any frame, keyed by its reply proxy, folded into the
                // tab's indicator.
                parseAudioReport(message.data)?.let { audible ->
                    state.playingAudio = audioFrames.onReport(replyProxy, audible)
                    return@WebMessageListener
                }
                // Input the top document itself received (#85): only the
                // main frame's word counts — an iframe's would let it
                // vouch for a tap on itself ([UserGestureLatch]).
                parseTopDocumentInput(message.data)?.let { input ->
                    if (isMainFrame) userGestures.onTopDocumentInput(input.ageMs, input.isClick)
                    return@WebMessageListener
                }
                // The page's say on a long-press (#84): any frame, since
                // the press may land in an iframe. It can only ever open
                // a menu for a press the user actually made.
                parseContextMenuVerdict(message.data)?.let { allowed ->
                    contextMenuPress?.onPageVerdict(allowed, SystemClock.uptimeMillis())
                        ?.let { target -> contextMenuPress?.let { onContextMenu(it.pin, target) } }
                    return@WebMessageListener
                }
                if (message.data == BOTTOM_UI_READY) {
                    // A main-frame detector at document start, from the
                    // document on screen or one still on its way in. It
                    // is started now only if it can be the painted
                    // document's own late ready (see [BottomUiChannels]);
                    // otherwise it waits for its document's first paint.
                    if (!isMainFrame) return@WebMessageListener
                    // A new main-frame document has replaced the last one,
                    // and every frame of that one is gone (#91): nothing
                    // they said about audio holds any more, whether or not
                    // their `pagehide` silence made it here. Frames report
                    // on their own pipes, so a new subframe's first
                    // report can overtake this ready and be wiped too; an
                    // audible frame re-sends it every [AUDIO_RECHECK_MS],
                    // so the indicator comes back within one period.
                    forgetTabAudio()
                    val starts = bottomUiChannels.onReady(replyProxy, bottomChrome.installed)
                    postBottomUiProbe(starts)
                    // The theme-colour ask sent at first paint had no
                    // channel to go to yet: ask again with the start (#92).
                    if (starts.isNotEmpty() && themeColor.beginRead() != null) postThemeColorRequest(starts)
                    return@WebMessageListener
                }
                // The current document's theme colour (#92): the answer
                // to [readThemeColor]'s ask, or sent unasked when a
                // `<meta>` changed (an SPA route that sets its colour
                // only after its data arrives, well after
                // `doUpdateVisitedHistory`'s read). Only for the current
                // token, and only once that document has painted.
                val theme = parseThemeColorReport(message.data, isMainFrame, bottomChrome.token)
                if (theme != null) {
                    themeColor.detectorHeard()
                    if (themeColor.beginRead() != null) state.themeColorArgb = theme.argb
                    return@WebMessageListener
                }
                val report = parseBottomUiMessage(message.data, isMainFrame, bottomChrome.token)
                    ?: return@WebMessageListener
                bottomUiChannels.onReport(replyProxy)
                themeColor.detectorHeard()
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

        // Ad blocking (#126): the tab's top-level document — the page
        // the network filters' `third-party` / `domain=` options and the
        // allowlist are judged against. The committed one, or one whose
        // answer the browser itself just handed over and is about to
        // commit, or — for a request whose Referer names it — the one a
        // network navigation is fetching; never one that didn't commit
        // (see [AdblockPage]).
        // The cosmetic channel reads it on the main thread.
        val adblockPage = AdblockPage()
        AdblockCosmetic.install(this, state.private) { adblockPage.current() }

        // `window.radicle` (#124): the provider's page object and channel.
        RadicleProviders.install(this, state)

        // `window.ethereum` (#110): the provider's page object, EIP-6963 announce and channel.
        EthereumProviders.install(this, state)

        // Force an initial paint so the WebView's compositor surface
        // is valid even before the user submits a URL. Not for a popup:
        // Chromium rejects (crashes on) a popup WebView that has already
        // navigated, and loads the popup's own URL into it anyway. Nor
        // for a tab being restored: `restoreState` has to be the
        // WebView's first navigation, and the blank load still pending
        // here would win over the restored entry (seen on the AVD — the
        // reopened tab came back on the home overlay).
        if (!isPopup && !restoring) loadUrl(ABOUT_BLANK)

        // Downloads (#79): anything Chromium decides not to render — a
        // `Content-Disposition: attachment`, a non-renderable type, a
        // `<a download>` — lands here, dweb origins and `data:` URIs
        // included. [DownloadManager] does the fetching; WebView itself
        // saves nothing.
        // Main-frame request URLs since the last commit (the pending
        // navigation's own URL and every redirect hop). Filled from
        // shouldInterceptRequest (a WebView IO thread) and
        // shouldOverrideUrlLoading, emptied by onPageStarted.
        val pendingNavigationUrls = java.util.Collections.synchronizedSet(LinkedHashSet<String>())

        // Whether the main-frame navigation in flight started with a user
        // gesture. WebView reports `hasGesture()` false on every redirect
        // hop, so a tapped link whose server redirects to another app's
        // link (a meeting invite's tracking URL → `zoomus:`) would be
        // refused without it (#85). Reset when a document starts, and
        // when the navigation ends without one (handed to an app,
        // detoured to the submit flow, or turned into a download), and
        // when the browser starts a load of its own over it.
        var navigationHadGesture = false
        // The redirect chain of the main-frame navigation in flight when
        // it is a load the user named ([BrowserState.takeUserNamedLoad]):
        // it may end in an app link with no tap on a page, asked for the
        // hop that redirected there (#173 — a Meet link redirects to the
        // Meet app's `intent:`). Ended wherever [navigationHadGesture] is
        // dropped, on Stop, when loading stops without a commit (a 204,
        // R2-F1), and by a main-frame request of the page's own
        // ([UserNamedChain]).
        val userNamedChain = UserNamedChain()
        // The URL of the document on screen, as last committed (or moved
        // by history.pushState): tells the page's own `load` event from
        // the end of a navigation that never commits
        // ([UserNamedChain.loadFinished]).
        var committedPageUrl: String? = null
        // A load the browser starts itself (typed URL, reload, back /
        // forward) replaces whatever navigation was in flight without a
        // first hop through shouldOverrideUrlLoading: the replaced
        // navigation's gesture mustn't carry over to its redirects (#85).
        this.onBrowserInitiatedLoad = { url, userNamed ->
            navigationHadGesture = false
            // …nor does an address a restore had waiting for its own
            // load (#185 R2-F1).
            state.restoreLoadSuperseded()
            if (url != null && userNamed) userNamedChain.started(url) else userNamedChain.ended()
            // …nor is what answers it a paid request's answer, or a 402
            // noted before it (#218 R2-M2, R2-M3).
            X402Payments.onNavigationSuperseded(state)
        }
        // Stop (or a new load's stop first) ends the navigation in flight
        // without a commit: neither its gesture nor the user's naming of
        // it carries over to a navigation the page starts next (R1-F1).
        this.onStopLoading = {
            navigationHadGesture = false
            // The stop finishes the restored page: the address waiting
            // for that finish mustn't go in over what comes next — the
            // user's own navigation, or nothing if they hit Stop
            // (#185 R2-F1).
            state.restoreLoadSuperseded()
            userNamedChain.ended()
            // A 402 whose page never commits isn't paid for later (#218 R2-M2).
            X402Payments.onNavigationSuperseded(state)
        }

        // Whether this WebView has started a document yet. A popup
        // (`target=_blank`, `window.open()`) whose very first navigation
        // is a link to another app was opened for that link alone: the
        // link is its opener's (origin, tap, prompt), and the empty tab
        // closes again, as in Chrome (#85).
        var documentStartedOnce = false

        // A link to another app (#85) that passed the scheme and gesture
        // checks: the site-permission broker asks (or applies a
        // remembered answer) for the page that asked, then the app is
        // started. An `intent:` no app can take goes to its http(s)
        // fallback instead, as a page navigation would.
        fun offerExternalLink(view: WebView, pageUrl: String?, tab: BrowserState, url: String) {
            val origin = permissionOriginKey(pageUrl)
            val launch = externalAppLaunch(url, view.context.packageName)
            if (origin == null || launch == null) {
                Log.i(LOG_TAG, "external link refused: ${externalUrlForLog(url)}")
                return
            }
            sitePermissions.onExternalLink(tab, origin, launch.scheme) {
                if (startExternalApp(view.context, launch)) return@onExternalLink
                val fallback = launch.fallbackUrl
                if (fallback != null) {
                    onSubmitUrl(tab, fallback)
                } else {
                    sitePermissions.onNoAppForLink?.invoke(launch.scheme)
                }
            }
        }

        setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            // A download that is the response of a main-frame navigation
            // since the last commit (the typed URL or a redirect hop).
            // That navigation is over either way — it *became* the
            // download — so its URLs go, and can't make a later download
            // of the same URL look like a navigation's (a link that
            // turned into a download leaves the tab committed, so
            // nothing else would clear them).
            val wasPending = pendingNavigationUrls.remove(url)
            if (wasPending) {
                pendingNavigationUrls.clear()
                // Nor does its gesture carry over to the next load.
                navigationHadGesture = false
                userNamedChain.ended()
                (this as? PageWebView)?.usersNavigation?.ended()
                // The page on screen stays: its open requests are this
                // load's, whatever the answer's headers suggested.
                state.mainFrameKeptPage()
                adblockPage.kept()
                // A 402 that became a file never commits (#218 R2-M2).
                X402Payments.onNavigationSuperseded(state)
            }
            // A main-frame navigation that turned out to be a file never
            // commits: no onPageStarted, no final progress callback. Left
            // alone, the capsule keeps the typed address, the progress
            // trace and Stop over the previous page for good. That load
            // is over, so clear the busy chrome the way Stop does and
            // put the label back on the page that is actually on
            // screen. Unlike Stop, a blank committed address wins too
            // (the download started from home): the request was served,
            // so there's nothing left to keep the typed address for, and
            // home comes back instead of a blank page under a label for
            // a file.
            //
            // Only a download *of* that navigation, though: one the
            // committed page starts meanwhile (a "your download begins in
            // 5 s" timer) has a URL the pending navigation never
            // requested, and leaves it loading.
            val endsTypedNavigation = downloadEndsPendingNavigation(
                committedUrl = state.url,
                addressBarText = state.addressBarText,
                resolving = state.resolving,
                // A typed `data:` URL is never seen by the request
                // hooks; it is the address itself.
                downloadIsNavigationResponse = wasPending || url == state.addressBarText,
            )
            DownloadManager.get(context).start(
                tabId = state.id,
                private = state.private,
                url = url,
                userAgent = userAgent,
                contentDisposition = contentDisposition,
                mimeType = mimeType,
                contentLength = contentLength,
                // An address the user submitted has no referrer — the
                // page on screen had nothing to do with it. Anything else
                // a page asked for, even with no URL to show ("" — the
                // prompt then says "a page"): null would pass it off as
                // the user's own request, which a declined tab still
                // lets through.
                pageUrl = if (endsTypedNavigation) null else (this.url ?: ""),
            )
            if (endsTypedNavigation) {
                state.stopProgress()
                state.addressBarText = state.url
            }
        }

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

            // Reloading a page reached by POST asks here; the answer is
            // always "don't resend" (WebView's default: resending would
            // repeat the form's side effect). But a reload a sweep asked
            // for must still get rid of the stale document, so that one
            // moves straight on to a GET of the same address (#125,
            // R6-F1, [SweptReload]) — posted, not run inside WebView's
            // own callback. A prompt for a load started after the sweep's
            // reload (the user's Back to a POST entry) is that load's, and
            // [SweptReload.refused] leaves it alone (R1-F1).
            override fun onFormResubmission(view: WebView?, dontResend: Message?, resend: Message?) {
                dontResend?.sendToTarget()
                if (view is PageWebView) view.post { view.sweptReload.refused() }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                // Ad blocking judges requests against it from here on
                // (a page back from the back/forward cache made none).
                url?.let(adblockPage::committed)
                // The pending navigation committed; it's no download.
                pendingNavigationUrls.clear()
                navigationHadGesture = false
                userNamedChain.ended()
                documentStartedOnce = true
                committedPageUrl = url
                // The previous document, and its frames, are gone: what a
                // sweep held for them is cleared once more, now that they
                // can't write to it again, and the tab no longer counts
                // as being on their origins — only on those requested
                // since this document's answer, which may already include
                // its frames (#125, [TabDocuments.committed]).
                if (view is PageWebView) {
                    view.sweptReload.committed()
                    UnverifiedOrigins.release(view)
                    view.documents.committed(
                        url,
                        url?.let(VirtualOrigin::parseHostOfUrl)?.let(VirtualOrigin::originFor),
                    )
                }
                state.documentCommitted()
                // A load the tab had in flight over the restored page
                // before its WebView was rebuilt (#183 R1-F2) goes back
                // in flight over it now, at its reload's commit —
                // whatever URL that ended on (#185 R2-F2) — before the
                // page can take input, so a navigation the user starts
                // on it replaces that load as it would have before the
                // relaunch, POST form included (#185 R3-F1). Posted, so
                // this commit has updated the tab first, and only if
                // nothing has superseded it by then (#185 R2-F1). Handed
                // over without stopping the page, whose HTML and
                // subresources are still coming in (#185 R4-F1).
                state.afterPageCommitted()?.let { after ->
                    view?.post {
                        if (!state.claimAfterPage(after)) return@post
                        state.addressBarText = after.address
                        onSubmitUrl(state, after.address)
                    }
                }
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
                // …and with its own theme colour once it has painted; the
                // outgoing page keeps its tint until then, as it keeps
                // the screen (#92).
                themeColor.startDocument()
                blankPageReads.reset()
                // …and at full height again: a reveal belongs to the
                // document it was pushed on (#65).
                cancelReveal()
                // …and floating over the page until this document shows
                // a bottom nav of its own (#66). A report still in flight
                // from the outgoing document carries its old token and
                // is dropped.
                bottomChrome.startDocument()
                bottomUiChannels.startDocument()
                // A main-frame document no detector runs in (not
                // http(s)) sends no ready to reset the tab's audio frames
                // on (#91), so they are forgotten here. An http(s) one's
                // ready does it instead: its subframes can report before
                // this callback arrives.
                if (!isHttpUrl(url)) forgetTabAudio()
                state.bottomChromeMode = BottomChromeMode.Overlay
                // …and with no permission prompt from the outgoing
                // document left standing: its requests are denied and
                // a late answer can't land on this one (#81).
                sitePermissions.onDocumentStarted(state)
                RadicleProviders.onDocumentStarted(state, url)
                EthereumProviders.onDocumentStarted(state, url)
                // After it: an x402 payment the page asks for is put to
                // the user against this document's number (#140).
                X402Payments.onDocumentStarted(state, view, url)
                // …and with the progress latch open again: whatever the
                // last Stop aborted, this document is a load of its own
                // and its percentages are worth drawing (#41).
                state.loadAborted = false
                // …and at its own site's zoom level (#88), set before it
                // paints so a remembered level never shows as a jump.
                // Home and error pages aren't sites: they get the default.
                val zoomSite = zoomSiteKey(url)
                state.zoomSite = zoomSite
                state.providerOrigin = providerOriginKey(url)
                // …with the user agent it was fetched with (#180). One
                // that crossed the desktop/mobile line was corrected
                // before its request went out, where it could be (see
                // [PageWebView.redirectCrossesUserAgent] and
                // [PageWebView.pageHopRequested]); one that couldn't be
                // (a page's redirect or script navigation) keeps the one
                // it started with, and isn't fetched again: the server
                // has answered it, and a GET may be single-use (R5-F1).
                (view as? PageWebView)?.documentStarted(url)
                // Relative to the system font scale, which is what
                // WebView's own default text zoom is.
                view?.let {
                    it.settings.textZoom = PageZoomLevels.textZoom(
                        pageZoom.levelFor(zoomSite, state.private),
                        it.resources.configuration.fontScale,
                    )
                }
                // …and above the home branch below, because the blank
                // entry ends a page's probe exactly like any other
                // document does (see [cancelProbeSupersededBy]).
                cancelProbeSupersededBy(url)
                // …and IPFS or not by what actually committed — a link,
                // back/forward, or a redirect can land somewhere the
                // submit that started this load didn't name (#94).
                if (url != null) state.ipfsLoad = ipfsLoadFor(url, state.ipfsLoad, ensPins)
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
                    // No name behind Home, so no shield (#97).
                    state.nameTrust = null
                    // No page colour behind Home (#92). A popup's own
                    // blank page keeps the old one until it draws.
                    if (!state.blankIsPage) state.themeColorArgb = null
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
                // Entering a virtual origin or an onchain app (#123):
                // expire anything page JS managed to plant via
                // document.cookie before this page gets a chance to
                // read it.
                if (CookieHygiene.coversNavigation(url)) CookieHygiene.sweepAsync(url)
                val display = url?.let { displayFor(it, state, ensPins) }
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
                // The trust shield (#97) describes this document's
                // answer, taken now — the interceptor recorded it before
                // handing the document over. An error page or a name
                // refusal was served from no answer, so it has none.
                state.nameTrust = committedNameTrust(url, state.url, nameRefusal, ensPins)
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
                // …and its `<head>` is in: the theme colour is readable
                // (#92) — a popup's blank page's too, which is a page.
                if (url != ABOUT_BLANK || state.blankIsPage) {
                    themeColor.painted()
                    readThemeColor(view)
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
                // A private tab (#86) claims the slot and writes nothing.
                if (flushed != null && visitGate.recordOnce(flushed.display) && !state.private) {
                    repo.recordVisit(flushed.display, flushed.title)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                // Chromium's synthetic finish for a navigation that never
                // committed (a 204, Stop, superseded): the page on screen
                // stays, and ad blocking goes on judging against it.
                if (!finishedLoadIsCurrent(url, view?.url)) adblockPage.kept(url)
                // Loading stopped: the user's named load, if this is its
                // end (a 204, a cancelled hop), has no more hops (R2-F1).
                userNamedChain.loadFinished(url, committedPageUrl)
                (view as? PageWebView)?.usersNavigation?.loadFinished(url, committedPageUrl)
                // A 402 whose load ended without committing (#218 R2-M2).
                X402Payments.onLoadFinished(state, url)
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
                    // …and no site to zoom as (#88), for the same reason.
                    state.zoomSite = null
                    state.providerOrigin = null
                    // …and no page colour behind the status bar (#92) —
                    // unless the blank document is a popup's page, whose
                    // colour is its own.
                    if (state.blankIsPage) {
                        readThemeColor(view, onScreen = true)
                    } else {
                        state.themeColorArgb = null
                    }
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
                    // A reopened tab waiting on this blank entry to
                    // put its address back (see [BrowserState.afterBlank]).
                    // The hook submits as the renderer, which leaves the
                    // address bar as it is until the page commits — so
                    // put the address the user had there back first, or
                    // the tab loads behind the home overlay. A load the
                    // user had stopped only gets its address back, and
                    // the Stop latch so the bar offers Reload.
                    state.takeAfterBlankEntry()?.let {
                        state.addressBarText = it.address
                        if (it.submit) onSubmitUrl(state, it.address) else state.stopProgress()
                    }
                    return
                }
                // The first page to finish after a restore consumes the
                // pending blank-entry address too, even though it isn't
                // the blank entry: it can't apply to a later Home. (One
                // armed over the restored page went in at its commit.)
                state.afterPageFinished()
                // Dismiss the pull-to-refresh spinner once the page has
                // finished loading (or errored out). Happens regardless
                // of whether the load was user-initiated reload or not.
                refreshLayout.isRefreshing = false
                val display = displayFor(url.orEmpty(), state, ensPins)
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
                // A load put back over this restored page that needs the
                // other user agent goes in now that the page is complete,
                // unless the user started one of their own on the page
                // meanwhile, which dropped it (#180, [PutBackHold]).
                // Posted, after this finish's bookkeeping.
                val putBackGoesIn = isCurrent && view is PageWebView && view.putBackHold.pageFinished()
                if (putBackGoesIn) {
                    view.post { (view as PageWebView).putBackHold.release() }
                }
                if (isCurrent) {
                    lastLoadedDisplayUrl = display
                    state.title = sanitizeTitle(view?.title, url)
                    // The bar keeps the address of the load going in now,
                    // as for the deadline's release or any typed load,
                    // not the page it's about to replace (R3-F1).
                    if (finishShowsPageAddress(putBackGoesIn)) {
                        state.url = uiDisplay
                        state.addressBarText = uiDisplay
                    }
                    // A theme colour a script set after first paint (#92).
                    // A current finish is the document on screen even if
                    // it never reported a paint. Through the detector even
                    // if it hasn't reported yet (a fast load finishes
                    // first); one that never does gets the fallback read.
                    readThemeColor(view, onScreen = true)
                }
                state.canGoBack = view?.canGoBack() == true
                state.canGoForward = view?.canGoForward() == true
                // Still busy with the put-back load going in (R2-F1).
                state.progress = if (putBackGoesIn) PUT_BACK_HOLD_PROGRESS else -1
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
                        if (visitGate.recordOnce(display) && !state.private) {
                            repo.recordVisit(display, state.title)
                        }
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
                committedPageUrl = url
                // Posted after `onPageStarted` for a new document, alone
                // for a same-document step: either way the user's
                // navigation is over, and a same-document one (the
                // chrome's Back to a `pushState` entry) must not be left
                // for a later navigation of the page's own (#180, R2-F1).
                // Only a step to the address it was awaited at: the page
                // on screen's own `replaceState` while the user's
                // navigation is in flight isn't its end (R3-F2).
                (view as? PageWebView)?.usersNavigation?.sameDocumentStep(url)
                // A same-document step keeps the page on screen as the
                // load's document for the IPFS phase line (#94, R3-F2).
                state.historyUpdated(isHome = url == ABOUT_BLANK)
                // A same-document move (`pushState`) to a new path on a
                // virtual origin or onchain app can bring cookies tossed
                // at that path into view (R3-F1): sweep there too.
                if (CookieHygiene.coversNavigation(url)) CookieHygiene.sweepAsync(url)
                if (view == null || !bottomUiApplies(url)) return
                // An SPA route can bring its own theme colour (#92). Only
                // once the document has painted: before that, this is the
                // cross-document commit, and first paint reads it anyway.
                // A colour the route sets later (after a fetch) comes in
                // through the detector's `<meta>` ping.
                readThemeColor(view)
                requestBottomUiProbe()
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                val target = request?.url?.toString() ?: return false
                // "Continue once" on the not-cross-checked ENS warning
                // (#96): never a load, only a message for the submit
                // flow, which checks it is this tab's ([EnsGate]).
                if (EnsGate.continueToken(target) != null) {
                    if (request.isForMainFrame) {
                        // A stale warning (Back, tab restore) re-runs its
                        // navigation rather than doing nothing.
                        EnsGate.continueDestination(target, state.ensGate, view?.url)
                            ?.let { onSubmitUrl(state, it) }
                    }
                    return true
                }
                // A redirect of a load of the app's to a site across the
                // desktop/mobile line (#180): cancelled before its target
                // is requested, and the target loaded with its own user
                // agent — never fetched twice (R5-F1). The page on screen
                // stays until that load, as for a cancelled app-link hop
                // (#94).
                if (request.isForMainFrame && request.isRedirect && view is PageWebView &&
                    !(isPopup && !documentStartedOnce) &&
                    view.redirectCrossesUserAgent(target, named = userNamedChain.asker() != null)
                ) {
                    pendingNavigationUrls.clear()
                    state.mainFrameKeptPage()
                    return true
                }
                // A link to another app (#85): never a page load. Main
                // frame + user gesture only, then per-site consent.
                if (request.isForMainFrame && !request.isRedirect) {
                    navigationHadGesture = request.hasGesture()
                    // The page's own navigation, not the user's load.
                    userNamedChain.ended()
                    // A link the user tapped on a restored page is where
                    // they're going now, not the load a restore had
                    // waiting (#185 R2-F1). A script's redirect without a
                    // tap is still the restored page's own load.
                    if (request.hasGesture()) {
                        state.restoreLoadSuperseded()
                        (view as? PageWebView)?.putBackHold?.dropped()
                    }
                }
                // A popup's very first navigation: an app link there is
                // its opener's, and the popup was opened for it alone.
                val popupFirstNavigation = isPopup && !documentStartedOnce && request.isForMainFrame
                val opener = if (popupFirstNavigation) popupOpener() else null
                val askingView = opener?.second as? PageWebView ?: view as? PageWebView
                var gesture: Int? = null
                // The hop whose answer this redirect is, when it is one of
                // a load the user named (#173).
                val userNamedAsker = if (request.isRedirect && request.isForMainFrame) {
                    userNamedChain.asker()
                } else {
                    null
                }
                val verdict = externalLinkVerdict(
                    url = target,
                    isForMainFrame = request.isForMainFrame,
                    hasGesture = request.hasGesture() ||
                        (request.isRedirect && request.isForMainFrame && navigationHadGesture),
                    userNamedRedirect = userNamedAsker != null,
                    consumeGesture = {
                        gesture = askingView?.userGestures?.consume()
                        gesture != null
                    },
                )
                if (verdict != ExternalLinkVerdict.NotExternal) {
                    // Cancelled here, so it never reaches onPageStarted:
                    // its gesture mustn't carry over to the next load.
                    if (request.isForMainFrame) {
                        navigationHadGesture = false
                        userNamedChain.ended()
                        (view as? PageWebView)?.usersNavigation?.ended()
                    }
                    // A redirect hop cancelled here ends a navigation whose
                    // first hop was already answered as a new document
                    // (#94): none commits, so the page on screen stays and
                    // its open requests are this load's — as for a
                    // navigation that became a download. Its URLs go too.
                    // A first hop was never answered: whatever navigation
                    // is pending keeps its own bookkeeping.
                    if (externalLinkKeepsPage(request.isForMainFrame, request.isRedirect, popupFirstNavigation)) {
                        pendingNavigationUrls.clear()
                        state.mainFrameKeptPage()
                        adblockPage.kept()
                    }
                    val input = gesture
                    val latch = askingView?.userGestures
                    // The tap has to have been the top document's, not an
                    // iframe's that navigates the top frame (target=_top):
                    // the offer waits for the top document to say so
                    // ([UserGestureLatch]). The page it is asked for is
                    // the one on screen now, whatever commits meanwhile.
                    val pageUrl = askingView?.url
                    val offerTab = opener?.first ?: state
                    val offer = askingView?.let { page -> { offerExternalLink(page, pageUrl, offerTab, target) } }
                    val waiting = verdict == ExternalLinkVerdict.Ask && input != null && latch != null &&
                        offer != null && latch.whenInTopDocument(input, offer)
                    if (verdict == ExternalLinkVerdict.AskUserNamed && askingView != null) {
                        // The load ends here, in another app or nowhere:
                        // the tab goes back to what it shows — the page
                        // before, or Home when there was none — instead
                        // of a blank page under the named address (#173).
                        // Not by loading Home: a new document would
                        // withdraw the prompt below.
                        state.stopProgress()
                        state.addressBarText = state.url
                        state.canGoBack = askingView.canGoBack()
                        // The user's own submit was the gesture. The site
                        // asking is the hop that answered with the app
                        // link — not the page on screen, which didn't ask,
                        // nor the address typed, which may have been an
                        // open redirect to it (#173, R1-F2).
                        offerExternalLink(askingView, userNamedAsker, state, target)
                    } else if (waiting) {
                        askingView.postDelayed({
                            if (latch.giveUp(input, offer)) {
                                Log.i(LOG_TAG, "external link refused: ${externalUrlForLog(target)}")
                            }
                        }, UserGestureLatch.CONFIRM_MS)
                    } else {
                        Log.i(LOG_TAG, "external link refused: ${externalUrlForLog(target)}")
                    }
                    // Posted: the tab (and this WebView) mustn't be torn
                    // down from inside its own callback. Closed whether
                    // or not the opener is still there to ask for it (a
                    // closed or evicted opener refuses the link): either
                    // way the popup would stay an empty tab.
                    if (popupFirstNavigation) view?.post { onCloseWindow() }
                    return true
                }
                // Redirect hops of a main-frame navigation come through
                // here — the download a navigation turns into has the
                // final hop's URL.
                if (request.isForMainFrame) pendingNavigationUrls.add(target)
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
                    // A link the WebView follows itself is a new load
                    // (a detoured one gets this from the submit, #94).
                    state.beginLoad(inWebView = true)
                }
                if (detoured) {
                    // The WebView's own navigation stops here; the submit
                    // starts another, with an answer of its own.
                    if (request.isForMainFrame) adblockPage.kept()
                    navigationHadGesture = false
                    userNamedChain.ended()
                    onSubmitUrl(state, target)
                    return true
                }
                // A page's own navigation — a link, a script's
                // `location` change, or a redirect of either — goes on
                // with the user agent in place, even across "Desktop
                // site" (#180): WebView can't swap it under a navigation
                // it has started, and cancelling it to start it again as
                // a load of ours would lose its initiator (Chromium would
                // send `Sec-Fetch-Site: none`, a forged `Sec-Fetch-User`,
                // no `Referer` and SameSite=Strict cookies — R1-F1) and
                // turn a `location.replace()` into a new history entry
                // (R1-F2). A tapped one's first request may be held back
                // and re-issued by the page instead (see
                // `shouldInterceptRequest`). A redirect hop is the
                // navigation it continues: it keeps its start's say.
                if (request.isForMainFrame && view is PageWebView) {
                    if (request.isRedirect) {
                        view.usersNavigation.redirected(target)
                    } else {
                        view.navigationIsUsers(request.hasGesture(), target)
                    }
                }
                // The WebView follows it: a page a service worker answers
                // commits with no answer the interceptor saw, and prunes
                // the tab's origins from here instead (#125, R5-F1).
                if (request.isForMainFrame && view is PageWebView) {
                    view.documents.navigationStarted(target)
                }
                // A new top-level navigation supersedes whatever was
                // pending, which may never report back (an ERR_ABORTED
                // fetch sends nothing): drop it, or a service worker's
                // page couldn't adopt its frames (#178 R6-F1).
                if (request.isForMainFrame && !request.isRedirect) adblockPage.kept()
                // A server redirect of a navigation already answered as a
                // new document: that document is the redirect target's.
                if (request.isForMainFrame && request.isRedirect) adblockPage.redirected(target)
                // A hop of the user's named load the WebView now follows:
                // its answer is the next one that may be an app link.
                if (request.isForMainFrame && request.isRedirect) userNamedChain.redirected(target)
                // x402 (#140): a paid request's redirect hop may be where
                // its answer comes from; the page's own navigation is not
                // its answer, nor a 402's commit (#218 R2).
                if (request.isForMainFrame) {
                    if (request.isRedirect) X402Payments.onRedirect(state, target)
                    else X402Payments.onNavigationSuperseded(state)
                }
                return false
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
            ): WebResourceResponse? {
                val mainFrame = request?.isForMainFrame == true
                // A page's tapped navigation across the desktop/mobile
                // line (#180): its first request is answered here with a
                // `204`, so it never reaches the server with the other
                // site's user agent, and the page re-issues it with the
                // right one (see [PageWebView.pageHopRequested]).
                var heldBack = false
                if (mainFrame) {
                    request!!.url?.toString()?.let {
                        pendingNavigationUrls.add(it)
                        // Not a hop of the user's named load: the page's
                        // own navigation (R1-F1). Nor of the user's
                        // navigation, for the user agent (#180, R2-F1).
                        userNamedChain.mainFrameRequested(it)
                        (view as? PageWebView)?.usersNavigation?.mainFrameRequested(it)
                        // A form the user submitted (a POST never reaches
                        // shouldOverrideUrlLoading) supersedes a held
                        // put-back as a tapped link does (R2-F2).
                        if (request.hasGesture() && request.method.equals("POST", ignoreCase = true)) {
                            (view as? PageWebView)?.let { v -> v.post { v.putBackHold.dropped() } }
                        }
                        // x402 (#140): a form POST — gesture or not —
                        // replaces a paid request in flight; its answer
                        // is not the paid request's (#218 R3-M1). Posted
                        // now, so it lands before the POST's own
                        // redirect or commit callbacks.
                        if (!request.method.equals("GET", ignoreCase = true)) {
                            val method = request.method
                            view?.post { X402Payments.onMainFrameRequested(state, method) }
                        }
                        heldBack = (view as? PageWebView)?.pageHopRequested(it, request.requestHeaders) == true
                    }
                }
                // Tagged with the load it belongs to, and open until
                // Chromium closes the body, so the IPFS phase line can
                // tell while a superseded load is still fetching (#94,
                // see [GatewayWork]). A main-frame request is the
                // navigation the WebView was handed; a subresource is
                // the document's whose main-frame answer went out last
                // (see [BrowserState.documentGeneration]).
                val generation = if (mainFrame) {
                    state.mainFrameRequested()
                } else {
                    state.documentGeneration
                }
                if (view is PageWebView && request != null &&
                    isDocumentRequest(request.isForMainFrame, request.requestHeaders)
                ) {
                    request.url?.toString()?.let(VirtualOrigin::parseHostOfUrl)
                        ?.let(VirtualOrigin::originFor)?.let(view.documents::requested)
                }
                // Ad and tracker blocking (#126): a subresource the
                // enabled filter lists name, unless the page's site is
                // allowlisted. Never a navigation, a local gateway or a
                // virtual origin (see [Adblock.shouldBlock]).
                if (!mainFrame && request != null) {
                    val url = request.url?.toString()
                    // A frame of the page on screen: its requests aren't
                    // a pending destination's (see [AdblockPage]).
                    if (url != null && isDocumentRequest(false, request.requestHeaders)) {
                        adblockPage.frameRequested(url, refererOf(request.requestHeaders))
                    }
                    if (url != null &&
                        Adblock.shouldBlock(
                            url,
                            request.requestHeaders,
                            adblockPage.current(refererOf(request.requestHeaders)),
                            state.private,
                        )
                    ) {
                        return Adblock.blockedResponse()
                    }
                }
                val work = state.gatewayWork.start(generation)
                val response = if (heldBack) heldBackResponse() else try {
                    interceptVirtualRequest(
                        request, ensPins, view, state::assertedProtocolFor, state.onchain,
                    ) { served ->
                        noteMainFrameContentLoad(view, state, generation, served)
                    }
                } catch (t: Throwable) {
                    state.gatewayWork.finish(work)
                    throw t
                }
                // Before Chromium has the answer, so none of the new
                // document's own requests can be filed under the load
                // before it (R3-F1).
                if (mainFrame) {
                    nameRefusal.onMainFrameResponse(
                        request!!.url.toString(),
                        response?.let { nameResolutionErrorIn(it.responseHeaders) },
                    )
                    val replaces = mainFrameAnswerReplacesDocument(response)
                    state.mainFrameAnswered(generation, replaces)
                    adblockPage.answered(
                        request!!.url.toString(),
                        replaces,
                        fetchedByWebView = response == null,
                    )
                    if (replaces && view is PageWebView) {
                        view.documents.mainFrameAnswered(request!!.url.toString())
                    }
                }
                state.gatewayWork.answered(work)
                return trackedUntilClosed(
                    response,
                    onReading = { started -> state.gatewayWork.reading(work, started) },
                ) { state.gatewayWork.finish(work) }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                val req = request ?: return
                if (!req.isForMainFrame) return
                val failed = req.url?.toString() ?: return
                // A paid request (#140) that got no answer.
                X402Payments.onMainFrameFailed(state, failed)
                // Already on the error page? Don't loop.
                if (ErrorPage.isErrorPage(failed)) return
                // An onion page whose Tor went away mid-load (#143): the
                // proxy refused it. Reloading lands on the interceptor's
                // "Tor isn't running" page in place, which can't fail
                // again — nor loop, as the reload only happens while no
                // Tor port is routed. A form POST's reload would only
                // ask to resend (answered "don't"), so that one is
                // loaded again as a GET (R2-F2).
                if (isOnionHost(req.url?.host) && TorRouting.port == 0 && view != null) {
                    Log.i(LOG_TAG, "main-frame ${error?.errorCode} for $failed with Tor down → refusal page")
                    if (onionRefusalByReload(req.method)) {
                        view.post { view.reload() }
                    } else {
                        view.post { view.loadUrl(failed) }
                    }
                    return
                }
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
                // A 402 with x402 terms, or the answer to a paid request (#140).
                X402Payments.onHttpError(state, req, errorResponse)
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
                val progress = progressForCallback(
                    newProgress = newProgress,
                    isHomeSentinel = view?.url == ABOUT_BLANK,
                    aborted = state.loadAborted,
                )
                // The restored page finishing under a held put-back
                // leaves the tab busy with that load, not idle (R2-F1).
                val holding = progress == -1 && !state.loadAborted && view?.url != ABOUT_BLANK &&
                    (view as? PageWebView)?.putBackHold?.held == true
                state.progress = if (holding) PUT_BACK_HOLD_PROGRESS else progress
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                state.title = sanitizeTitle(title, view?.url)
            }

            // JavaScript dialogs from a private tab (#86) go in a secure
            // window of our own ([showPrivateJsDialog]); a normal tab's
            // keep WebView's default dialog (false).
            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean =
                state.private && result != null &&
                    showPrivateJsDialog(context, JsDialogKind.ALERT, url, message, null, result)

            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean =
                state.private && result != null &&
                    showPrivateJsDialog(context, JsDialogKind.CONFIRM, url, message, null, result)

            override fun onJsPrompt(
                view: WebView?,
                url: String?,
                message: String?,
                defaultValue: String?,
                result: JsPromptResult?,
            ): Boolean =
                state.private && result != null &&
                    showPrivateJsDialog(context, JsDialogKind.PROMPT, url, message, defaultValue, result)

            // Ours in every tab, not only a private one: Stay ends the
            // navigation that asked without a commit, and nothing else
            // tells (#180, R2-F2 — see [PageWebView.navigationDidNotLeave]).
            override fun onJsBeforeUnload(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean =
                result != null && if ((view as? PageWebView)?.takeReissueBeforeUnload() == true) {
                    // The page's re-issue of a navigation the user
                    // already left for (#180, R4-F3): not asked twice.
                    result.confirm()
                    true
                } else {
                    showPrivateJsDialog(
                        context, JsDialogKind.BEFORE_UNLOAD, url, message, null, result,
                        secure = state.private,
                        onAnswered = { leave ->
                            if (!leave) {
                                // Over, as after Stop: nothing will call
                                // back for it, so the chrome's Back (or
                                // a submit) would stay busy for good.
                                (view as? PageWebView)?.let {
                                    it.onStopLoading?.invoke()
                                    it.navigationDidNotLeave()
                                }
                                state.stopProgress()
                                state.addressBarText = addressBarTextAfterStop(
                                    committedUrl = state.url,
                                    pending = state.addressBarText,
                                )
                            }
                        },
                    )
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

            // Site permissions (#81): camera / microphone through
            // `onPermissionRequest`, location through the geolocation
            // prompt. Both go to [SitePermissionBroker], which asks the
            // user per site and only then asks Android for the app's
            // runtime permission. Without these overrides WebView
            // denies every request outright.
            override fun onPermissionRequest(request: PermissionRequest?) {
                request ?: return
                sitePermissions.onMediaRequest(state, request)
            }

            override fun onPermissionRequestCanceled(request: PermissionRequest?) {
                request ?: return
                sitePermissions.onMediaRequestCanceled(state, request)
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?,
            ) {
                sitePermissions.onGeolocationRequest(state, origin, callback)
            }

            override fun onGeolocationPermissionsHidePrompt() {
                sitePermissions.onGeolocationHidden(state)
            }

            // `<input type=file>` (#80) — see [FileChooser].
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?,
            ): Boolean {
                if (filePathCallback == null || fileChooserParams == null) return false
                return fileChooser?.show(state.id, filePathCallback, fileChooserParams) ?: false
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
                // Nor anything from a private tab (#86): the favicon
                // cache is a list of sites visited.
                if (state.private) return
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
        // …and is a new load of its own for the IPFS phase line (#94).
        state.beginLoad(inWebView = true)
        // It's the user's own reload, like the menu's: a sheet they
        // rejected on the page (a wallet or x402 payment sheet, a
        // `window.radicle` prompt) may ask again (#218 R1-M3).
        EthereumProviders.allowPrompts(state.id)
        RadicleProviders.allowPrompts(state.id)
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
 * Chromium's accessibility node provider for the page, passed through
 * untouched except that an `ACTION_CLICK` on any node (a TalkBack
 * double-tap, a Switch Access select) arms [latch] first, as a tap on
 * the screen would (#85).
 */
private class GestureArmingNodeProvider(
    private val inner: AccessibilityNodeProvider,
    private val latch: UserGestureLatch,
) : AccessibilityNodeProvider() {
    override fun performAction(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
        if (accessibilityActionArmsGestureLatch(action)) {
            latch.onInputStart(untilConfirmed = true)
            latch.onInput()
        }
        return inner.performAction(virtualViewId, action, arguments)
    }

    override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfo? =
        inner.createAccessibilityNodeInfo(virtualViewId)

    override fun addExtraDataToAccessibilityNodeInfo(
        virtualViewId: Int, info: AccessibilityNodeInfo, extraDataKey: String, arguments: Bundle?,
    ) = inner.addExtraDataToAccessibilityNodeInfo(virtualViewId, info, extraDataKey, arguments)

    override fun findAccessibilityNodeInfosByText(
        text: String, virtualViewId: Int,
    ): MutableList<AccessibilityNodeInfo>? = inner.findAccessibilityNodeInfosByText(text, virtualViewId)

    override fun findFocus(focus: Int): AccessibilityNodeInfo? = inner.findFocus(focus)
}

/**
 * The tab's WebView. A subclass only for what `WebView` keeps
 * protected: Chromium's unconsumed overscroll, and the scroll range.
 */
internal class PageWebView(context: Context) : WebView(context) {
    /** [destroy] has been called: nothing may be asked of this WebView any more. */
    var destroyed = false
        private set

    override fun destroy() {
        destroyed = true
        super.destroy()
    }

    /**
     * Virtual origins this tab may have a live document on — the main
     * frame's and its frames' (see [TabDocuments]). What
     * [sweptDocuments] checks after an external IPFS gateway is
     * switched away from (#125).
     */
    val documents = TabDocuments()

    /**
     * Whether a page at this URL is to be requested as a desktop site
     * (#180, [DesktopSites]); set by the tab that owns this WebView.
     */
    var wantsDesktop: (url: String?) -> Boolean = { false }

    // Built on first use, before anything has changed the user agent,
    // so it captures the WebView's own. Lazy: the settings aren't
    // there before WebView's constructor has run.
    private val userAgentSwitch by lazy { UserAgentSwitch(this) }

    /**
     * Puts the user agent a navigation to [url] should be requested
     * with in place (#180) — before it starts: WebView reads the user
     * agent when the navigation's request goes out. True if it changed.
     */
    fun matchUserAgentTo(url: String?, historyStep: Boolean = false): Boolean {
        // A `javascript:` URL runs in the page on screen: no request.
        // The chrome's Back / Forward steps are ones, to the entry
        // they step to.
        when (url) {
            HISTORY_BACK_JS -> return matchUserAgentTo(historyEntryUrl(-1) ?: return false, historyStep = true)
            HISTORY_FORWARD_JS -> return matchUserAgentTo(historyEntryUrl(1) ?: return false, historyStep = true)
        }
        if (url != null && url.startsWith("javascript:", ignoreCase = true)) return false
        // Every load of the app's comes through here. A history step to
        // an entry at the address on screen steps an iframe (or reloads
        // the same site, whose user agent is in place): no main-frame
        // commit may end it, so it isn't followed at all — or a later
        // `location.reload()` of the page's would take it (R3-F3).
        if (url != null && !(historyStep && this.url?.let { sameRequestUrl(it, url) } == true)) {
            usersNavigation.started(url)
            usersNavigationIsApps = true
        } else {
            usersNavigation.ended()
        }
        // Only [loadUrl] / [postUrl] say it's a load that makes an entry.
        usersNavigationIsLoad = false
        redirectCorrection.navigationStarted(url)
        pageNavigationStart.ended()
        reissueSkipsBeforeUnload = false
        val desktop = wantsDesktop(url)
        if (desktop == userAgentSwitch.desktop) return false
        // Chromium reloads the page on screen, with the new user agent,
        // if the user agent changes while anything is loading — which
        // would replace the navigation about to start with a reload of
        // the page it leaves. Whatever is loading is superseded by that
        // navigation anyway. Not [stopLoading]: this is no user's Stop.
        // (The one load that mustn't stop the page on screen, one a
        // restore put back over its still-loading page, doesn't get here
        // until that page has finished, or its hold's deadline passed:
        // see [PutBackHold].)
        super.stopLoading()
        return userAgentSwitch.set(desktop)
    }

    /**
     * The navigation last started, while it is the user's — a load of
     * the app's, or a page's own navigation started by a user gesture —
     * followed hop by hop to the address it is awaited at (see
     * [UserNamedChain]; the client feeds it the redirects, main-frame
     * requests and load stops). A navigation nothing of ours sees start
     * (the page's own `history.back()` or `location.reload()`) must never
     * inherit it: so it also ends with anything that ends the navigation
     * without a commit — Stop, a download, a `204`, a same-document step,
     * a Stay on a `beforeunload` prompt, a request for any other address
     * (R2-F1).
     */
    val usersNavigation = UserNamedChain()

    // Whether [usersNavigation] was started by a load of the app's
    // (true) or a page's own tapped navigation (false).
    private var usersNavigationIsApps = false

    // Whether it is a [loadUrl] / [postUrl] of the app's — a new entry,
    // so a redirect of it across the line can become a load of the
    // target ([redirectCrossesUserAgent]). A reload or a history step
    // can't: a load would add an entry, and cut off Forward (#149).
    private var usersNavigationIsLoad = false

    /**
     * The document on screen's channel for re-issuing its own navigation
     * ([PageReissueChannel]); set by the tab. Null: never re-issued.
     */
    var pageReissue: PageReissueChannel? = null

    private val redirectCorrection = RedirectCorrection()

    /**
     * A load a restore put back over its page, waiting for that page to
     * finish before the user agent changes under it (#180, #185).
     */
    val putBackHold = PutBackHold()

    /** Holds [load] in [putBackHold], until its page's finish or [PUT_BACK_HOLD_MS] (R2-F1). */
    fun holdPutBack(load: () -> Unit) {
        val generation = putBackHold.hold(load)
        postDelayed({ putBackHold.deadline(generation) }, PUT_BACK_HOLD_MS)
    }

    /**
     * A page's own tapped navigation, while it's the user's: where it
     * started, and with what `Referer` (R4-F1/F2). The interceptor feeds
     * it the main-frame requests.
     */
    val pageNavigationStart = PageNavigationStart()

    // The page's re-issue of its own navigation is asked for: the
    // `beforeunload` prompt it raises is one the user already answered
    // Leave (or never got) for the navigation it repeats, so it isn't
    // asked again (R4-F3). Until that navigation starts, or its deadline.
    private var reissueSkipsBeforeUnload = false

    /**
     * Whether a `beforeunload` prompt is the page's re-issue of the
     * navigation the user already left for ([pageHopRequested]):
     * answered Leave without asking again. Once.
     */
    fun takeReissueBeforeUnload(): Boolean = reissueSkipsBeforeUnload.also { reissueSkipsBeforeUnload = false }

    // The document on screen's address, as it committed: the origin a
    // page's re-issue is checked against.
    private var documentUrl: String? = null

    // The user agent the document on screen was fetched with: what its
    // later requests should keep going out with, if the navigation that
    // switched it away never leaves it (R2-F2). Null before any commit.
    private var documentDesktop: Boolean? = null

    /** A page's own main-frame navigation to [url] started, with a user gesture or not. */
    fun navigationIsUsers(gesture: Boolean, url: String) {
        reissueSkipsBeforeUnload = false
        val reissue = redirectCorrection.isReissue(url)
        redirectCorrection.navigationStarted(url)
        usersNavigationIsLoad = false
        if (gesture) {
            usersNavigation.started(url)
            usersNavigationIsApps = false
        } else {
            usersNavigation.ended()
        }
        // The re-issue itself has the right user agent: nothing to hold.
        if (gesture && !reissue) {
            val crosses = webOrigin(url) != null && needsOtherUserAgentFor(url) &&
                pageReissue?.ready(url) == true
            pageNavigationStart.started(url, documentUrl, crosses)
        } else {
            pageNavigationStart.ended()
        }
    }

    /**
     * A main-frame redirect to [target] is about to be followed (#180).
     * True if it is a hop of a load of the app's ([loadUrl], [postUrl])
     * whose site wants the other user agent: the caller cancels it before
     * [target] is requested, and [target] is loaded instead, with its own
     * user agent — as a load the user named (#173) if [named]. Once per
     * navigation ([RedirectCorrection]). What the redirect would have
     * sent is what the load sends: `Sec-Fetch-Site: none` and no
     * `Referer`, as for the load's own first hop.
     *
     * Before the request, never after (R5-F1): the hop that answered with
     * the redirect was fetched once, with the right user agent, and
     * [target] is fetched once, with its own. A page's own navigation
     * isn't loaded again by us (R1-F1), nor are a reload's or a history
     * step's redirects (a load would add an entry): they go on with the
     * user agent in place.
     */
    fun redirectCrossesUserAgent(target: String, named: Boolean): Boolean {
        if (!usersNavigationIsApps || !usersNavigationIsLoad || usersNavigation.asker() == null) return false
        if (webOrigin(target) == null) return false
        if (!redirectCorrection.crossing(target, ::needsOtherUserAgentFor)) return false
        usersNavigation.ended()
        // Posted: not from inside the WebView's own callback, and after
        // the redirect's cancellation has ended the navigation — the
        // user agent must not change while anything is loading.
        mainHandler.post {
            val url = redirectCorrection.issue() ?: return@post
            if (named) loadUrlNamedByUser(url) else loadUrl(url)
        }
        return true
    }

    /**
     * The WebView is about to request [url] for the main frame, with
     * [headers] (the interceptor's thread). True if the request is to be
     * held back — the caller answers it with a `204`, so it never leaves
     * the device — and the page on screen asked to re-issue it with its
     * own site's user agent: the first hop of the page's tapped
     * navigation across the desktop/mobile line ([PageNavigationStart]).
     * The server sees one request, with the right user agent (R5-F1).
     */
    fun pageHopRequested(url: String, headers: Map<String, String>?): Boolean {
        if (!pageNavigationStart.requested(url, headers)) return false
        if (!redirectCorrection.crossing(url) { true }) return false
        mainHandler.post(::reissueFromPage)
        return true
    }

    // The held-back hop, asked for again by the page on screen, through
    // its detector ([PageReissueChannel]). If the page never starts it —
    // it cancelled it, or never got the ask — the user agent goes back
    // to the one the page was fetched with, and nothing stays armed for
    // it (R5-F2).
    private fun reissueFromPage() {
        val url = redirectCorrection.issue() ?: return
        val generation = redirectCorrection.generation
        // The held-back navigation may still be winding down: nothing may
        // load while the user agent changes (see [matchUserAgentTo]).
        super.stopLoading()
        usersNavigation.ended()
        pageNavigationStart.ended()
        userAgentSwitch.set(wantsDesktop(url))
        // The page's `beforeunload` runs again for it; its prompt was
        // answered for the tap.
        reissueSkipsBeforeUnload = true
        if (pageReissue?.send(url) != true) {
            navigationDidNotLeave()
            return
        }
        mainHandler.postDelayed({
            if (redirectCorrection.neverStarted(generation)) navigationDidNotLeave()
        }, PAGE_REISSUE_START_MS)
    }

    /**
     * A document at [url] committed, with the user agent in place. It
     * isn't fetched again if that was the other site's (R5-F1): the
     * server already answered it, and a second GET could find its
     * one-time token spent. The next load puts the right one back.
     */
    fun documentStarted(url: String?) {
        // Another document took the tab: no held put-back goes in over it.
        putBackHold.dropped()
        documentDesktop = userAgentSwitch.desktop
        documentUrl = url
        redirectCorrection.ended()
        pageNavigationStart.ended()
        reissueSkipsBeforeUnload = false
        usersNavigation.ended()
        usersNavigationIsLoad = false
    }

    /**
     * The navigation in flight ended without leaving the document on
     * screen — the user's Stop, or Stay on its `beforeunload` prompt. A
     * user agent switched for it goes back to the one the document was
     * fetched with (R2-F2), with nothing loading: Chromium would reload
     * the page on screen otherwise (see [matchUserAgentTo]).
     */
    fun navigationDidNotLeave() {
        usersNavigation.ended()
        redirectCorrection.ended()
        pageNavigationStart.ended()
        reissueSkipsBeforeUnload = false
        val desktop = documentDesktop ?: return
        if (desktop == userAgentSwitch.desktop) return
        super.stopLoading()
        userAgentSwitch.set(desktop)
    }

    /**
     * Whether a page at [url] needs a different user agent than the one
     * in place (#180). WebView can't change it under a navigation
     * already started (see [matchUserAgentTo]).
     */
    fun needsOtherUserAgentFor(url: String): Boolean = wantsDesktop(url) != userAgentSwitch.desktop

    /**
     * Gets the tab off a document a sweep left stale — a reload, then a
     * GET of the same address, then `about:blank`, until one commits
     * (#125, R6-F1; see [SweptReload]).
     */
    val sweptReload: SweptReload = SweptReload(
        navigate = { step ->
            when (step) {
                SweptReload.Step.RELOAD -> reload()
                SweptReload.Step.GET -> loadUrl(sweptReload.address ?: ABOUT_BLANK)
                SweptReload.Step.BLANK -> loadUrl(ABOUT_BLANK)
            }
        },
        schedule = { delayMs, action -> mainHandler.postDelayed(action, delayMs) },
    )

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * The user's taps, key presses and accessibility clicks on this
     * page, each good for one
     * link to another app (#85, see [UserGestureLatch]). Recorded before
     * Chromium sees the event, so the click it turns into — and the
     * navigation that starts — find it already there.
     */
    val userGestures = UserGestureLatch(SystemClock::uptimeMillis)

    /** Only a tap counts: not the lift at the end of a scroll or fling. */
    private val taps = TapTracker(ViewConfiguration.get(context).scaledTouchSlop.toFloat())

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                userGestures.onInputStart(event.eventTime)
                taps.onDown(event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> taps.onMove(event.x, event.y)
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> taps.onCancel()
            MotionEvent.ACTION_UP -> {
                userGestures.onInputContinues(event.eventTime)
                if (taps.onUp(event.x, event.y)) userGestures.onInput()
            }
        }
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (keyArmsGestureLatch(
                action = event.action,
                repeatCount = event.repeatCount,
                isSystem = event.isSystem,
                isModifier = KeyEvent.isModifierKey(event.keyCode),
            )
        ) {
            userGestures.onInputStart(event.eventTime)
            userGestures.onInputContinues(SystemClock.uptimeMillis())
            userGestures.onInput()
        }
        return super.dispatchKeyEvent(event)
    }

    // TalkBack / Switch Access clicks: on the WebView itself when it has
    // no virtual tree, else on one of Chromium's virtual nodes, through
    // its node provider. Either way, armed before Chromium clicks.
    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (accessibilityActionArmsGestureLatch(action)) {
            userGestures.onInputStart(untilConfirmed = true)
            userGestures.onInput()
        }
        return super.performAccessibilityAction(action, arguments)
    }

    private var a11yProvider: Pair<AccessibilityNodeProvider, AccessibilityNodeProvider>? = null

    override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider? {
        val inner = super.getAccessibilityNodeProvider() ?: return null
        a11yProvider?.let { (wrapped, wrapper) -> if (wrapped === inner) return wrapper }
        return GestureArmingNodeProvider(inner, userGestures).also { a11yProvider = inner to it }
    }

    /**
     * A load this app starts on the WebView (not the page): a typed URL,
     * a reload, back / forward, a retry. Its first hop never reaches
     * `shouldOverrideUrlLoading`, so the client hears of it here (#85).
     * `url`: the URL loaded (`loadUrl`, `postUrl`), or `null` for a
     * reload, a history step or inline data. `userNamed`: the load is
     * the one a user's submit scheduled ([loadUrlNamedByUser], #173).
     */
    var onBrowserInitiatedLoad: (url: String?, userNamed: Boolean) -> Unit = { _, _ -> }

    /** `stopLoading()`: the navigation in flight ends without a commit. */
    // Nullable: read by an override WebView could call before this
    // class's initializers have run.
    var onStopLoading: (() -> Unit)? = null

    // Set only for the duration of [loadUrlNamedByUser]'s own load.
    private var loadingNamedByUser = false

    private fun browserInitiatedLoad(url: String? = null) {
        // This load, not a held put-back, is what the tab is on now.
        // Null only while WebView's own constructor runs.
        @Suppress("SENSELESS_COMPARISON")
        if (putBackHold != null) putBackHold.dropped()
        // Any load but a sweep's own step supersedes its reload: a later
        // resubmission prompt is that load's, not the sweep's (R1-F1).
        sweptReload.navigationStarted()
        onBrowserInitiatedLoad(url, url != null && loadingNamedByUser)
    }

    /**
     * Loads [url] as the address the user named (#173): the one load a
     * user's submit scheduled, never any other `loadUrl` (a blank first
     * paint, a retry, an error page) that happens to come first.
     */
    fun loadUrlNamedByUser(url: String) {
        loadingNamedByUser = true
        try {
            loadUrl(url)
        } finally {
            loadingNamedByUser = false
        }
    }

    override fun stopLoading() {
        onStopLoading?.invoke()
        super.stopLoading()
        // Null only while WebView's own constructor runs.
        @Suppress("SENSELESS_COMPARISON")
        if (putBackHold != null) putBackHold.dropped()
        @Suppress("SENSELESS_COMPARISON")
        if (usersNavigation != null) navigationDidNotLeave()
    }

    // Navigations the app starts, noted before Chromium has them (see
    // [TabDocuments.navigationStarted]); the page's own go through
    // `shouldOverrideUrlLoading`.
    override fun loadUrl(url: String) {
        matchUserAgentTo(url)
        // A `javascript:` URL runs in the page: no load, no entry.
        if (!url.startsWith("javascript:", ignoreCase = true)) usersNavigationIsLoad = true
        documents.navigationStarted(url)
        browserInitiatedLoad(url)
        super.loadUrl(url)
    }

    override fun loadUrl(url: String, additionalHttpHeaders: MutableMap<String, String>) {
        matchUserAgentTo(url)
        // A `javascript:` URL runs in the page: no load, no entry.
        if (!url.startsWith("javascript:", ignoreCase = true)) usersNavigationIsLoad = true
        documents.navigationStarted(url)
        browserInitiatedLoad(url)
        super.loadUrl(url, additionalHttpHeaders)
    }

    override fun postUrl(url: String, postData: ByteArray) {
        matchUserAgentTo(url)
        // A `javascript:` URL runs in the page: no load, no entry.
        if (!url.startsWith("javascript:", ignoreCase = true)) usersNavigationIsLoad = true
        documents.navigationStarted(url)
        browserInitiatedLoad(url)
        super.postUrl(url, postData)
    }

    override fun loadData(data: String, mimeType: String?, encoding: String?) {
        browserInitiatedLoad()
        super.loadData(data, mimeType, encoding)
    }

    override fun loadDataWithBaseURL(
        baseUrl: String?, data: String, mimeType: String?, encoding: String?, historyUrl: String?,
    ) {
        browserInitiatedLoad()
        super.loadDataWithBaseURL(baseUrl, data, mimeType, encoding, historyUrl)
    }

    override fun reload() {
        matchUserAgentTo(url)
        url?.let(documents::navigationStarted)
        browserInitiatedLoad()
        super.reload()
    }

    override fun goBack() {
        historyStepStarting(-1)
        browserInitiatedLoad()
        super.goBack()
    }

    override fun goForward() {
        historyStepStarting(1)
        browserInitiatedLoad()
        super.goForward()
    }

    override fun goBackOrForward(steps: Int) {
        historyStepStarting(steps)
        browserInitiatedLoad()
        super.goBackOrForward(steps)
    }

    private fun historyStepStarting(steps: Int) {
        val url = historyEntryUrl(steps) ?: return
        // The entry is fetched again with whatever user agent is in
        // place: the one its site asks for now (#180).
        matchUserAgentTo(url, historyStep = true)
        documents.navigationStarted(url)
    }

    /** The URL of the history entry [steps] away from the current one, if there is one. */
    private fun historyEntryUrl(steps: Int): String? {
        val history = copyBackForwardList()
        val index = history.currentIndex + steps
        if (index !in 0 until history.size) return null
        return history.getItemAtIndex(index)?.url
    }

    /** "Search" on the text-selection toolbar, with the selected text (#84). */
    var onSearchSelection: ((String) -> Unit)? = null

    /**
     * Chromium raises its text-selection toolbar through here; the
     * callback is wrapped to add the "Search" item (see
     * [SearchSelectionCallback]). Every other action mode — and every
     * other item on this one — is Chromium's, untouched.
     */
    override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? {
        val search = onSearchSelection
        if (callback == null || search == null) return super.startActionMode(callback, type)
        return super.startActionMode(SearchSelectionCallback(callback, this, search), type)
    }

    /**
     * Chromium overscrolled past the bottom edge (the page didn't take
     * a drag towards the end) — never the top edge, even on a page with
     * no scroll range, where both clamp alike (see [overscrollPastEnd]).
     */
    var onBottomOverscroll: () -> Unit = {}

    /** Chromium overscrolled past the top edge (see [overscrollPastTop]). */
    var onTopOverscroll: () -> Unit = {}

    /**
     * Something a media query can ask about changed while this WebView
     * was attached: the configuration (light/dark, orientation, screen
     * size, density, …) or the view's own size. The manifest keeps all
     * of those in `configChanges`, so no Activity restart (and no
     * reload) follows one, and this is the only word of it the page's
     * owner gets. May fire more than once for one change.
     */
    var onMediaEnvironmentChanged: () -> Unit = {}
    private var lastConfiguration = android.content.res.Configuration(context.resources.configuration)

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration?) {
        super.onConfigurationChanged(newConfig)
        newConfig ?: return
        val changed = lastConfiguration.diff(newConfig)
        lastConfiguration = android.content.res.Configuration(newConfig)
        if (changed != 0) onMediaEnvironmentChanged()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // The first layout is no change: the first paint reads anyway.
        if (oldw != 0 && oldh != 0) onMediaEnvironmentChanged()
    }

    // The vertical delta of the overScrollBy call in progress (0 outside
    // one): onOverScrolled only says a clamp happened, not which edge.
    private var overScrollDeltaY = 0

    val verticalRange: Int get() = computeVerticalScrollRange() - computeVerticalScrollExtent()

    /** px between the current scroll offset and the document's end. */
    val distanceFromEnd: Int get() = (verticalRange - scrollY).coerceAtLeast(0)

    /** Called at the start of each draw of this view, before Chromium's frame is recorded. */
    var onBeforeDraw: (() -> Unit)? = null

    /** Called after each draw of this view: Chromium has a new frame for it. */
    var onDrawn: () -> Unit = {}

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        onDrawn()
    }

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

/**
 * Chromium's selection-toolbar callback plus one item, "Search", which
 * searches the selection with the browser's engine in a new tab.
 *
 * The item is only on the toolbar while there is something to search:
 * the same toolbar also comes up for a bare caret in a text field
 * (Paste only) and for a selection inside a password field, and
 * Chromium's own items can't tell us which — their ids are WebView-
 * package resources, not a stable API. So the page is asked instead
 * ([SELECTION_TEXT_SCRIPT], which reads a password field as empty),
 * and a probe whose answer flips "is there a selection" invalidates
 * the toolbar so prepare adds or drops the item.
 *
 * That script runs in the page's own JS world, where a page that wraps
 * `getSelection` / `activeElement` can count the calls — so the page
 * is asked as rarely as the answer can change: on create, and on a
 * prepare only when Chromium's own items differ from the last probe's
 * ([SelectionProbeGate]). A caret becoming a selection, or a selection
 * moving into a password field, swaps Chromium's items (Paste only ↔
 * Copy / Share / Select all; Copy dropped for a password), so those
 * still re-probe; dragging the handles or our own invalidate doesn't.
 *
 * Syncing happens on prepare as well as create because Chromium rebuilds
 * its menu on prepare, clearing whatever else was on it. On click the
 * selection is read afresh (the user may have dragged the handles since
 * the last probe) and *before* the toolbar is finished, since finishing
 * it clears the selection.
 *
 * It is a [ActionMode.Callback2] because Chromium's is: the floating
 * toolbar asks it where the selection is ([onGetContentRect]), and a
 * plain Callback would park the toolbar at the top of the view.
 */
private class SearchSelectionCallback(
    private val delegate: ActionMode.Callback,
    private val view: WebView,
    private val onSearch: (String) -> Unit,
) : ActionMode.Callback2() {
    /** The last probe's answer: is there a selection worth searching? */
    private var searchable = false
    private var destroyed = false
    private val gate = SelectionProbeGate()

    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        val created = delegate.onCreateActionMode(mode, menu)
        if (created && gate.shouldProbe(chromiumItems(menu))) probe(mode)
        return created
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
        val changed = delegate.onPrepareActionMode(mode, menu)
        // Read Chromium's items before ours is synced in: the
        // signature must not change just because "Search" came or went.
        val items = chromiumItems(menu)
        val synced = syncSearchItem(menu)
        if (gate.shouldProbe(items)) probe(mode)
        return changed || synced
    }

    /** Chromium's own items on [menu], by id, in order; "Search" left out. */
    private fun chromiumItems(menu: Menu): List<Int> =
        (0 until menu.size()).map { menu.getItem(it).itemId }.filter { it != SEARCH_SELECTION_ITEM_ID }

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        if (item.itemId != SEARCH_SELECTION_ITEM_ID) return delegate.onActionItemClicked(mode, item)
        view.evaluateJavascript(SELECTION_TEXT_SCRIPT) { json ->
            mode.finish()
            searchSelectionQuery(decodeJsString(json))?.let(onSearch)
        }
        return true
    }

    override fun onDestroyActionMode(mode: ActionMode) {
        destroyed = true
        delegate.onDestroyActionMode(mode)
    }

    override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
        if (delegate is ActionMode.Callback2) {
            delegate.onGetContentRect(mode, view, outRect)
        } else {
            super.onGetContentRect(mode, view, outRect)
        }
    }

    private fun probe(mode: ActionMode) {
        view.evaluateJavascript(SELECTION_TEXT_SCRIPT) { json ->
            if (destroyed) return@evaluateJavascript
            val now = searchSelectionQuery(decodeJsString(json)) != null
            if (now != searchable) {
                searchable = now
                mode.invalidate()
            }
        }
    }

    /** Adds or removes "Search" to match [searchable]; `true` if the menu changed. */
    private fun syncSearchItem(menu: Menu): Boolean {
        val present = menu.findItem(SEARCH_SELECTION_ITEM_ID) != null
        if (searchable == present) return false
        if (searchable) {
            menu.add(Menu.NONE, SEARCH_SELECTION_ITEM_ID, SEARCH_SELECTION_ITEM_ORDER, "Search")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        } else {
            menu.removeItem(SEARCH_SELECTION_ITEM_ID)
        }
        return true
    }
}

/**
 * The host has destroyed this WebView ([PageWebView.destroy]); a
 * callback it posted earlier can still run after that.
 */
internal val WebView.isDestroyed: Boolean
    get() = (this as? PageWebView)?.destroyed == true

/** [WebView.saveState] into a fresh bundle, or null if the WebView won't give one. */
private fun saveWebViewState(wv: WebView): Bundle? =
    Bundle().takeIf { runCatching { wv.saveState(it) }.getOrNull() != null }

/**
 * When [SearchSelectionCallback] asks the page for its selection: the
 * first time, and afterwards only when Chromium's items (their ids, in
 * order) differ from the last time it asked — the page's answer can
 * only flip with a change Chromium's menu reflects too.
 */
internal class SelectionProbeGate {
    private var last: List<Int>? = null

    fun shouldProbe(items: List<Int>): Boolean {
        if (items == last) return false
        last = items
        return true
    }
}

/** An `evaluateJavascript` result (a JSON value) as a string, or `null`. */
private fun decodeJsString(json: String?): String? =
    runCatching { org.json.JSONTokener(json ?: return null).nextValue() as? String }.getOrNull()

/** Outside the resource-id range Chromium's own items use. */
private const val SEARCH_SELECTION_ITEM_ID = 0x5EA4C4

/**
 * Where "Search" sits among Chromium's items. Chromium's default group
 * numbers its items from 1 (Copy 1, Share 2, Select all 3 on a page
 * selection; Cut / Copy / Paste / Select all in a field) and "Read
 * aloud" and the text-processing apps come after, in another group. At
 * 2 the item lands just after Select all and ahead of those, which
 * keeps it on the visible bar rather than in the overflow in both
 * cases (checked on the API 36 AVD).
 */
private const val SEARCH_SELECTION_ITEM_ORDER = 2

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
    // A Radicle repository (#124), in either form (`rad:z…` has no `//`).
    if (RadUrl.isRadScheme(url)) return true
    val schemeEnd = url.indexOf("://")
    if (schemeEnd <= 0) return false
    val scheme = url.substring(0, schemeEnd).lowercase()
    // A `web3://` app (#123) is read, and gated, by the submit flow too.
    return scheme in CONTENT_SCHEMES || scheme == OnchainAppRef.SCHEME
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
 *
 * [tab] is the requesting tab's WebView (null for a service worker): a
 * cleanup page another tab's hold asks for is served to it only once
 * ([UnverifiedOrigins.takeClearFor]).
 *
 * [assertedProtocol] is the requesting tab's typed-scheme assertion for
 * a name, if its address makes one (#97,
 * [BrowserState.assertedProtocolFor]); a document re-check holds the
 * name to it.
 *
 * [onchain] is the requesting tab's onchain-app documents (#123, see
 * [interceptOnchainAppRequest]); null for a service worker or a native
 * re-fetch, which are refused on an app's origin anyway.
 *
 * [onMainFrameRoot] hears, for a main-frame request on a virtual origin,
 * the content root its document is about to be fetched from — after the
 * name's re-check, before the fetch — or `null` when the re-check
 * refused it (an error page is served instead). It is what the tab's
 * IPFS phase line follows while the fetch runs (#94, #179 R5-F1).
 */
internal fun interceptVirtualRequest(
    request: WebResourceRequest?,
    ensPins: EnsDocumentPins? = null,
    tab: Any? = null,
    assertedProtocol: (name: String) -> String? = { null },
    onchain: OnchainAppTab? = null,
    onMainFrameRoot: (ContentRoot?) -> Unit = {},
): WebResourceResponse? {
    val req = request ?: return null
    val url = req.url?.toString() ?: return null
    // A `.onion` request with no Tor port routed is refused before
    // anything else looks at it (#143, fail closed).
    TorRouting.refusalFor(req)?.let { return it }
    // A page's on-chain write to the Swarm node — buying stamps, funding
    // the chequebook — is refused outright (#114, fail closed).
    NodeChainWrites.refusalFor(req)?.let { return it }
    val incoming = if (req.isForMainFrame) ensPins?.beginNavigation(url) else null
    // A contract-hosted app's origin (#123) is answered by its own rules.
    // Then an origin an unverified external IPFS gateway served before
    // the user switched away from it (#125): its next document first
    // clears what that gateway's pages left there, before anything else
    // runs.
    // The Radicle repository browser and its read API (#124).
    val response = RadApi.intercept(req, url)
        ?: interceptOnchainAppRequest(req, url, onchain)
        ?: siteDataCleanupFor(req, url, tab)
        ?: interceptVirtualRequestFor(req, ensPins, incoming, assertedProtocol, onMainFrameRoot)
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

/**
 * The document that clears a swept origin's site data
 * ([UnverifiedOrigins.takeClearFor]) — once, in place of the first
 * document requested there after the switch — or `null`. It runs on
 * the origin itself, which is the only way to reach its localStorage,
 * sessionStorage, Cache Storage and service workers: neither
 * `WebStorage.deleteOrigin` nor a `Clear-Site-Data` header on an
 * intercepted response touches those. Then it reloads the URL in
 * place, which is served normally.
 */
private fun siteDataCleanupFor(req: WebResourceRequest, url: String, tab: Any?): WebResourceResponse? {
    if (!isDocumentRequest(req.isForMainFrame, req.requestHeaders)) return null
    val origin = VirtualOrigin.parseHostOfUrl(url)?.let(VirtualOrigin::originFor) ?: return null
    // At a cold start the origins left to clear are loaded, and swept,
    // just before the endpoint settings land: a restored tab's first
    // document must not get ahead of that.
    Gateways.awaitExternalEndpointsBlocking()
    if (!UnverifiedOrigins.takeClearFor(origin, tab)) return null
    return WebResourceResponse(
        "text/html", "utf-8", 200, "OK",
        // `Vary: *`: a service worker's Cache Storage won't keep it.
        mapOf("Cache-Control" to "no-store", "Vary" to "*"),
        ByteArrayInputStream(SITE_DATA_CLEANUP_HTML.toByteArray(Charsets.UTF_8)),
    )
}

internal const val SITE_DATA_CLEANUP_HTML = """<!doctype html><meta charset="utf-8"><script>
(async () => {
  const quietly = async (f) => { try { await f(); } catch (e) {} };
  await quietly(() => localStorage.clear());
  await quietly(() => sessionStorage.clear());
  await quietly(async () => {
    for (const db of await indexedDB.databases()) {
      await new Promise((done) => {
        const r = indexedDB.deleteDatabase(db.name);
        r.onsuccess = r.onerror = r.onblocked = done;
      });
    }
  });
  await quietly(async () => { for (const k of await caches.keys()) await caches.delete(k); });
  await quietly(async () => {
    for (const r of await navigator.serviceWorker.getRegistrations()) await r.unregister();
  });
  await quietly(() => {
    for (const c of document.cookie.split(';')) {
      const name = c.split('=')[0].trim();
      if (name) document.cookie = name + '=; Max-Age=0; path=/';
    }
  });
  location.replace(location.href);
})();
</script>"""

private fun interceptVirtualRequestFor(
    req: WebResourceRequest,
    ensPins: EnsDocumentPins?,
    incoming: EnsDocumentPins.Page?,
    assertedProtocol: (name: String) -> String?,
    onMainFrameRoot: (ContentRoot?) -> Unit,
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
    // config status). Only the embedded nodes: an external endpoint
    // (#125) keeps its own CORS policy, so its preflights go through.
    if (req.method == "OPTIONS" && Gateways.isEmbeddedGateway(url)) {
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
                "Send writes to the node API at ${Gateways.swarmBase}.",
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
        val asserted = assertedProtocol(root.name)
        var web: EnsResult.Ok? = null
        Gateways.reverifyEnsDocument(
            root.name, ensPins, page, asserted, onWebRecord = { web = it },
        )?.let { code ->
            if (req.isForMainFrame) onMainFrameRoot(null)
            web?.let { return nameWebRecordNavigation(it, pathAndQuery) }
            return nameResolutionRefusal(root.name, code, asserted)
        }
    }
    // The root the document is fetched from, by the same precedence the
    // fetch uses: a name's is the answer just pinned for this navigation
    // — which a failed re-check can hold on an older answer than the
    // session registry's (R5-F1).
    if (req.isForMainFrame) onMainFrameRoot(Gateways.servedRootFor(root, page = page))

    // At a cold start the external endpoint settings (#125) are still
    // being read: a restored tab must not reach the embedded gateway
    // meanwhile. Immediate once they're known.
    Gateways.awaitExternalEndpointsBlocking()
    // Resolved again if the IPFS gateway is switched while this request
    // is under way (see [UnverifiedOrigins.record]); a switch is a
    // deliberate settings change, so more than one retry is a bound,
    // not a path.
    repeat(GATEWAY_SWITCH_RETRIES) {
        val target = Gateways.gatewayUrlFor(root, pathAndQuery, page = page)
            ?: return syntheticResponse(
                502, "Bad Gateway",
                "No local gateway can serve this content root " +
                    "(node not running, or name resolution failed).",
            )

        // An unverified external IPFS gateway (#125) shares the root's
        // virtual origin with the verified embedded node: note the origin
        // so its storage is wiped once this gateway is no longer in use,
        // and don't let the gateway install a service worker there — one
        // would keep answering the origin from its own code after the
        // switch.
        val external = Gateways.externalIpfsGatewayOf(target)
        var token: Long? = null
        if (external != null) {
            val origin = VirtualOrigin.originFor(root)
            if (origin != null) {
                // Swept away from since `target` was resolved: resolve again.
                token = UnverifiedOrigins.record(external, origin) ?: return@repeat
            }
            if (isServiceWorkerScript(req.requestHeaders)) {
                return syntheticResponse(
                    403, "Forbidden",
                    "Service workers aren't installed from an external IPFS gateway: " +
                        "its content isn't verified against the CID.",
                )
            }
        }

        val response = if (isMediaLikeUrl(target)) {
            fetchMediaWithRangeSupport(req, target)
        } else {
            fetchWithRetry(req, target, url)
        }
        // Fetched from a gateway a sweep switched away from meanwhile:
        // the origin was already wiped, so this must not land there.
        if (token != null && !UnverifiedOrigins.isCurrent(token)) {
            runCatching { response?.data?.close() }
            return@repeat
        }
        if (external != null && response != null) withoutCacheStorage(response)
        // A null here means the gateway socket itself is gone (connection
        // refused / node stopped). Synthesize instead of returning null —
        // null would send Chromium to DNS for a hostname that doesn't
        // exist, which surfaces as a slow, confusing resolver error.
        return response ?: syntheticResponse(
            502, "Bad Gateway",
            "The local gateway did not answer (is the node running?).",
        )
    }
    return syntheticResponse(
        503, "Service Unavailable",
        "The IPFS gateway was switched while this request was under way.",
    )
}

private const val GATEWAY_SWITCH_RETRIES = 3

/**
 * Mark an external IPFS gateway's response (#125) `Vary: *`, which
 * Cache Storage refuses to store (`cache.put`/`add` reject it). A
 * service worker registered while on the embedded node could otherwise
 * keep the unverified gateway's responses and serve them on the origin
 * after the switch, from a cache the interceptor never sees. WebView
 * doesn't HTTP-cache intercepted responses, so nothing else changes.
 */
private fun withoutCacheStorage(response: WebResourceResponse) {
    response.responseHeaders = varyAll(response.responseHeaders)
}

/** [headers] with any `Vary` replaced by `Vary: *` (see [withoutCacheStorage]). */
internal fun varyAll(headers: Map<String, String>?): Map<String, String> =
    headers.orEmpty().filterKeys { !it.equals("Vary", ignoreCase = true) } + ("Vary" to "*")

/**
 * The [swept] origins [webView] may have a document on: its main
 * frame's (a page a service worker answered never reaches the
 * interceptor, so the committed URL is checked too), its frames', and
 * those of [anyTab] — frame documents a service worker fetched, by the
 * tick of the fetch, which can't be traced to a tab
 * ([UnverifiedOrigins.noteWorkerDocument]) — fetched since the tab's
 * document on screen was answered.
 */
internal fun sweptDocuments(
    webView: WebView,
    swept: Set<String>,
    anyTab: Map<String, Long> = emptyMap(),
): Set<String> {
    val documents = (webView as? PageWebView)?.documents
    return sweptOrigins(
        swept = swept,
        documentOrigins = documents?.origins().orEmpty(),
        committedUrl = webView.url,
        anyTab = anyTab.filterValues { documents?.mayHoldWorkerFetchAt(it) ?: true }.keys,
    )
}

/** [sweptDocuments] without the WebView. */
internal fun sweptOrigins(
    swept: Set<String>,
    documentOrigins: Set<String>,
    committedUrl: String?,
    anyTab: Set<String> = emptySet(),
): Set<String> {
    val onScreen = documentOrigins + anyTab + listOfNotNull(
        committedUrl?.let(VirtualOrigin::parseHostOfUrl)?.let(VirtualOrigin::originFor),
    )
    return onScreen.intersect(swept)
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
    val target = try {
        URL(targetUrl)
    } catch (t: Throwable) {
        Log.w(LOG_TAG, "media fetch open failed: $targetUrl", t)
        return MediaLoadResult.Fatal
    }
    return try {
        // Redirects are followed hop by hop through TorRouting.
        val conn = TorRouting.openFollowingRedirects(target) { hop ->
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 60_000
            forwardProxiedHeaders(req, stripRange = true, crossOrigin = !TorRouting.sameOrigin(hop, target))
        }
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
    } catch (t: TorRouting.RefusedException) {
        Log.w(LOG_TAG, "media fetch open failed: $targetUrl", t)
        MediaLoadResult.Fatal
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
        val target = URL(targetUrl)
        // Redirects are followed hop by hop through TorRouting.
        val conn = TorRouting.openFollowingRedirects(target) { hop ->
            requestMethod = if (req.method == "HEAD") "HEAD" else "GET"
            connectTimeout = 5_000
            readTimeout = 10_000
            forwardProxiedHeaders(req, crossOrigin = !TorRouting.sameOrigin(hop, target))
        }
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

/**
 * Does a download that just started end the tab's pending navigation?
 *
 * Yes when the tab is showing an address that never committed
 * ([addressBarText] ahead of [committedUrl] — after a commit
 * `onPageStarted` writes the same string into both) and the load has
 * been handed to the WebView ([resolving] is the ENS / gateway phase in
 * front of it, which can't have produced a download yet). Chromium runs
 * one main-frame navigation at a time, so a download arriving then is
 * that navigation's response. A download from a committed page — an
 * `<a download>`, an attachment link — leaves the tab alone.
 *
 * And only when the download *is* that navigation's response
 * ([downloadIsNavigationResponse]: its URL is one the main frame
 * requested since the last commit — the typed URL or a redirect hop).
 * The committed page can start a download of its own while the typed
 * address is still loading; that one mustn't take the progress trace
 * and Stop away from a navigation that's still in flight.
 */
internal fun downloadEndsPendingNavigation(
    committedUrl: String,
    addressBarText: String,
    resolving: Boolean,
    downloadIsNavigationResponse: Boolean,
): Boolean = downloadIsNavigationResponse && !resolving &&
    addressBarText.isNotBlank() && addressBarText != committedUrl

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
    if (failedUrl.startsWith("${Gateways.swarmBase}/")) return "swarm"
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
internal fun displayFor(
    actualUrl: String,
    state: BrowserState,
    pins: EnsDocumentPins? = null,
): String = DisplayUrl.forActualUrl(actualUrl, state.override, committedProtocolFor(pins))

/**
 * The transport a name is shown under on the page on screen: the one
 * its document was served from ([pins], the tab's committed page), which
 * a failed re-check can hold on an older answer than the session's
 * (R3-F1) — else the session's current answer.
 */
internal fun committedProtocolFor(pins: EnsDocumentPins?): (String) -> String? = { name ->
    pins?.uriFor(name)?.let(KnownEnsNames::protocolOf) ?: KnownEnsNames.protocolFor(name)
}

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

/**
 * Re-derive [BrowserState.ipfsLoad] for a main-frame request to a
 * virtual dweb origin, before the (possibly slow) gateway fetch starts
 * (#94). Runs on the interceptor's thread, from
 * [interceptVirtualRequest]'s `onMainFrameRoot`.
 *
 * [served] is the content root the interceptor is about to fetch the
 * document from (`null`: the name was refused). For an ENS name that is
 * the answer its re-check just pinned for this navigation — not the
 * session registry's, which another tab can have moved to a different
 * transport while this tab's failed re-check serves its own earlier
 * answer (R5-F1). A reload of a restored tab (the session-only
 * [KnownEnsNames] is empty after a process restart), back/forward, or
 * an in-page link to another name reach the WebView unresolved; the
 * re-check resolves them first, so the phase line shows for the fetch
 * itself, not only after commit.
 *
 * The request belongs to the navigation [generation] the WebView was last handed
 * ([BrowserState.webViewGeneration]), which is not necessarily the tab's
 * current one: a submit still in its probe phase has already started a
 * new load while the WebView is still fetching the old. The write is
 * posted to the main thread and applied only if that navigation is still
 * the tab's current load (see [mainFrameNoteApplies]).
 */
private fun noteMainFrameContentLoad(
    view: WebView?,
    state: BrowserState,
    generation: Int,
    served: ContentRoot?,
) {
    val ipfs = servedFromIpfs(served)
    view?.post {
        if (mainFrameNoteApplies(generation, state.loadGeneration)) state.ipfsLoad = ipfs
    }
}

/** Whether a document fetched from [served] comes from the IPFS node. */
internal fun servedFromIpfs(served: ContentRoot?): Boolean = when (served) {
    is ContentRoot.Ipfs, is ContentRoot.IpnsKey, is ContentRoot.IpnsName -> true
    is ContentRoot.Bzz, is ContentRoot.Ens, null -> false
}

/**
 * Whether a main-frame request made for the WebView's navigation
 * [requestGeneration] may set the flag of the tab's load
 * [currentGeneration]: only when they are the same load.
 */
internal fun mainFrameNoteApplies(requestGeneration: Int, currentGeneration: Int): Boolean =
    requestGeneration == currentGeneration

/**
 * The answer to a main-frame request held back for the page to re-issue
 * with the other user agent (#180, [PageWebView.pageHopRequested]): a
 * `204`, which ends the navigation and leaves the page on screen.
 */
internal fun heldBackResponse(): WebResourceResponse =
    WebResourceResponse(
        "text/plain", "utf-8", 204, "No Content", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)),
    )

/**
 * Whether Chromium turns the main-frame answer [response] into a new
 * document. A 204 / 205, an attachment, or an opaque binary body ends
 * the navigation instead, leaving the page on screen: a 204 / 205 is
 * simply dropped, and an attachment or binary body is handed to the
 * download listener (#79), which saves it through DownloadManager
 * rather than rendering it. `null` — Chromium fetches it itself — is
 * assumed to (#94, R3-F2).
 *
 * A best guess from the headers only: Chromium also downloads any other
 * type it can't render (an inline `application/zip`, say), which this
 * counts as replacing. The download listener corrects that once the
 * answer reaches it ([BrowserState.mainFrameKeptPage]).
 */
internal fun mainFrameAnswerReplacesDocument(response: WebResourceResponse?): Boolean =
    response == null || mainFrameAnswerReplacesDocument(
        response.statusCode,
        response.responseHeaders,
        response.mimeType,
    )

/** [mainFrameAnswerReplacesDocument] on the answer's parts. */
internal fun mainFrameAnswerReplacesDocument(
    status: Int,
    headers: Map<String, String>?,
    mimeType: String?,
): Boolean {
    if (status == 204 || status == 205) return false
    val disposition = headers
        ?.entries
        ?.firstOrNull { it.key.equals("Content-Disposition", ignoreCase = true) }
        ?.value
    if (disposition != null && disposition.trim().lowercase().startsWith("attachment")) return false
    return mimeType?.lowercase() != "application/octet-stream"
}

/**
 * [response], with [onDone] run once its body is closed — or at once
 * when there is no body to wait for — and [onReading] told when each
 * read of the body starts (`true`) and returns (`false`).
 */
internal fun trackedUntilClosed(
    response: WebResourceResponse?,
    onReading: (Boolean) -> Unit = {},
    onDone: () -> Unit,
): WebResourceResponse? {
    val body = response?.data
    if (body == null) {
        onDone()
        return response
    }
    response.data = CloseNotifyingInputStream(body, onReading, onDone)
    return response
}

/**
 * Runs [onClose] once, the first time the stream is closed, and
 * [onReading] around every read / skip (see [GatewayWork.activeBefore]).
 */
internal class CloseNotifyingInputStream(
    inner: InputStream,
    private val onReading: (Boolean) -> Unit = {},
    private val onClose: () -> Unit,
) : FilterInputStream(inner) {
    private val closed = AtomicBoolean(false)

    private inline fun <T> tracked(block: () -> T): T {
        onReading(true)
        try {
            return block()
        } finally {
            onReading(false)
        }
    }

    override fun read(): Int = tracked { super.read() }

    override fun read(b: ByteArray, off: Int, len: Int): Int = tracked { super.read(b, off, len) }

    override fun skip(n: Long): Long = tracked { super.skip(n) }

    override fun close() {
        try {
            super.close()
        } finally {
            if (closed.compareAndSet(false, true)) onClose()
        }
    }
}
