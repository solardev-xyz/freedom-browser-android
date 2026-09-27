package baby.freedom.mobile.browser

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import java.net.URISyntaxException

/**
 * Links to other apps (#85): `mailto:`, `tel:`, `magnet:`, `geo:`,
 * `intent:` and the rest of the long tail a page can link to but a
 * browser doesn't render itself. WebView treats every one of them as a
 * page it fails to load (`ERR_UNKNOWN_URL_SCHEME`); here they are
 * offered to Android instead, decided per site and per scheme.
 *
 * Mirrors the desktop browser (freedom-browser
 * `src/main/external-protocol.js`). Policy, in the order it is applied
 * to a page's navigation (`shouldOverrideUrlLoading`):
 *
 * 1. Scheme blocklist ([BLOCKED_SCHEMES]). Schemes the browser handles
 *    itself, and ones naming local or internal resources, never reach
 *    another app. A blocklist, not an allowlist, because the point is
 *    the long tail (a meeting app's `zoomus:`, a wallet's `wc:`); the
 *    prompt is the real gate for everything not on the list.
 * 2. Only the top-level document may ask, and only right after the user
 *    interacted with it: WebView must report the navigation as carrying
 *    a user gesture, *and* the tab's WebView must have seen a tap or key
 *    press within [UserGestureLatch.WINDOW_MS] that no earlier launch
 *    used up ([UserGestureLatch]). A page can't open an app — or a
 *    prompt — on load, from a timer, from an embedded frame, or turn
 *    one tap into a burst of launches.
 * 3. The site-permission prompt (#81), keyed by origin + scheme
 *    ([ExternalScheme], stored as `external:<scheme>` like desktop), so
 *    allowing `magnet:` for a site never allows `sms:` too. Remembered
 *    decisions, the dismissal embargo and revoking from Settings all
 *    come from [SitePermissionBroker] unchanged.
 *
 * The page's own navigation is always cancelled; an allowed link is
 * started as a separate `ACTION_VIEW` activity ([externalAppLaunch]).
 */

/** One kind of link a site may hand to another app, e.g. `mailto`. */
data class ExternalScheme(val scheme: String) : SiteCapability {
    override val key: String get() = "$KEY_PREFIX$scheme"
    override val label: String get() = "$scheme: links"
    override val phrase: String get() = "open $scheme: links in another app"
    override val androidPermissions: List<String> get() = emptyList()

    companion object {
        /** Storage-key prefix; the same as desktop's `permissions.json`. */
        const val KEY_PREFIX = "external:"

        /**
         * The capability a stored key names, or `null` — including for a
         * blocked scheme, so a store entry can never hand one to an app.
         */
        fun forKey(key: String): ExternalScheme? {
            if (!key.startsWith(KEY_PREFIX)) return null
            val scheme = key.removePrefix(KEY_PREFIX)
            return if (isExternalSchemeAllowed(scheme)) ExternalScheme(scheme) else null
        }
    }
}

// RFC 3986 scheme grammar, lower-cased; capped so a key stays readable.
private val SCHEME_RE = Regex("^[a-z][a-z0-9+.-]{0,63}$")

/**
 * Schemes the browser (Chromium, or Freedom's own dweb schemes) handles
 * itself. A navigation to one of these is not a link to another app and
 * keeps going through the browser's normal paths.
 */
private val INTERNAL_SCHEMES = setOf(
    "http", "https", "ws", "wss",
    "file", "filesystem", "javascript", "data", "blob", "about",
    "chrome", "chrome-extension", "chrome-untrusted", "chrome-error", "chrome-search",
    "devtools", "view-source",
    // Freedom's own (desktop parity): the dweb schemes this app routes
    // through its gateways, and the ones desktop handles in-browser.
    "freedom", "bzz", "ipfs", "ipns", "web3", "ens", "rad", "radapi", "ethereum",
)

/**
 * Everything that is never handed to another app: the internal schemes,
 * plus Android ones that name local data or reach an app by a route that
 * skips the checks an `ACTION_VIEW` + `BROWSABLE` launch applies —
 * `content:` (other apps' — and this app's — content providers),
 * `android-app:` (Chrome's form for launching an activity by package).
 */
internal val BLOCKED_SCHEMES: Set<String> = INTERNAL_SCHEMES + setOf("content", "android-app")

/**
 * Lower-cased scheme of [url], or `null` when it has none or an invalid
 * one. Parsed by hand rather than with a URI parser so an opaque URL
 * (`mailto:a@b`, `magnet:?xt=…`) and a malformed one fail the same way.
 */
internal fun schemeOf(url: String?): String? {
    val s = url ?: return null
    val colon = s.indexOf(':')
    if (colon <= 0) return null
    val scheme = s.substring(0, colon).lowercase()
    return if (SCHEME_RE.matches(scheme)) scheme else null
}

/**
 * The scheme of [url] when it is a link to another app — a valid scheme
 * the browser doesn't handle itself — else `null`. Blocked-but-external
 * schemes (`content:`) count, so the caller cancels them instead of
 * letting WebView try; [isExternalSchemeAllowed] then refuses them.
 */
internal fun externalLinkScheme(url: String?): String? =
    schemeOf(url)?.takeUnless { it in INTERNAL_SCHEMES }

internal fun isExternalSchemeAllowed(scheme: String): Boolean =
    SCHEME_RE.matches(scheme) && scheme !in BLOCKED_SCHEMES

/**
 * What of an external URL may reach the log: the scheme only. The rest
 * is exactly what shouldn't be there — a mail address, a phone number, a
 * torrent's info-hash, a meeting id.
 */
internal fun externalUrlForLog(url: String?): String =
    schemeOf(url)?.let { "$it:<redacted>" } ?: "unknown"

/** What to do with a page's navigation to another app's link. */
internal enum class ExternalLinkVerdict {
    /** Not a link to another app: the browser's normal paths take it. */
    NotExternal,

    /** An app link that may not be launched: cancel it, silently. */
    Refuse,

    /** Ask the site-permission broker (prompt, or a remembered answer). */
    Ask,
}

/**
 * Applies the policy above to a navigation. [consumeGesture] is only
 * called — and so the tap only used up — once everything else passes.
 */
internal fun externalLinkVerdict(
    url: String?,
    isForMainFrame: Boolean,
    hasGesture: Boolean,
    consumeGesture: () -> Boolean,
): ExternalLinkVerdict {
    val scheme = externalLinkScheme(url) ?: return ExternalLinkVerdict.NotExternal
    if (!isExternalSchemeAllowed(scheme)) return ExternalLinkVerdict.Refuse
    if (!isForMainFrame || !hasGesture) return ExternalLinkVerdict.Refuse
    return if (consumeGesture()) ExternalLinkVerdict.Ask else ExternalLinkVerdict.Refuse
}

/**
 * The tab's own record of user input, standing in for the "one tap buys
 * one launch" half of Chromium's user activation. WebView's
 * `WebResourceRequest.hasGesture()` says a navigation was started with
 * activation, but activation isn't consumed by navigations: one tap
 * followed by a loop of `location = 'tel:…'` would report a gesture on
 * every one. So each tap or key press is good for one external launch
 * (or one prompt) within [WINDOW_MS] — Chromium's activation lifespan.
 *
 * [clock] is a monotonic millisecond clock (`SystemClock.uptimeMillis`).
 */
internal class UserGestureLatch(private val clock: () -> Long) {
    private var inputAt: Long? = null

    /** The user tapped or pressed a key on the page. */
    fun onInput() {
        inputAt = clock()
    }

    /** True, once, when there was input within [WINDOW_MS]. */
    fun consume(): Boolean {
        val at = inputAt ?: return false
        inputAt = null
        return clock() - at <= WINDOW_MS
    }

    companion object {
        const val WINDOW_MS = 5_000L
    }
}

/**
 * Tells a tap from the end of a scroll, fling or pinch, so only a tap
 * arms the [UserGestureLatch]: every one of those ends in an `ACTION_UP`
 * too. A tap is one finger that went down and came up within [slopPx]
 * of where it started — the same touch slop Android uses to decide a
 * touch has become a scroll.
 */
internal class TapTracker(private val slopPx: Float) {
    private var downX = 0f
    private var downY = 0f
    private var tracking = false

    /** The first finger went down. */
    fun onDown(x: Float, y: Float) {
        downX = x
        downY = y
        tracking = true
    }

    /** The finger moved; past the slop, this touch is a scroll. */
    fun onMove(x: Float, y: Float) {
        if (tracking && movedPastSlop(x, y)) tracking = false
    }

    /** A second finger, or the system took the touch: no tap. */
    fun onCancel() {
        tracking = false
    }

    /** The finger lifted; true when the whole touch was a tap. */
    fun onUp(x: Float, y: Float): Boolean {
        val tap = tracking && !movedPastSlop(x, y)
        tracking = false
        return tap
    }

    private fun movedPastSlop(x: Float, y: Float): Boolean {
        val dx = x - downX
        val dy = y - downY
        return dx * dx + dy * dy > slopPx * slopPx
    }
}

/** An allowed link, ready to start. */
internal class ExternalAppLaunch(
    val scheme: ExternalScheme,
    val intent: Intent,
    /**
     * An `intent:` URL's `S.browser_fallback_url`, when http(s): where
     * the tab goes instead if no app can take the intent (Chrome's rule).
     */
    val fallbackUrl: String?,
)

/**
 * The activity launch for [url], or `null` if it isn't an app link that
 * may be launched. Plain schemes become `ACTION_VIEW` on the URL.
 * `intent:` URLs (Chrome's Android form, `intent://…#Intent;…;end`) are
 * parsed, then stripped of everything that would let a page reach past
 * a normal link: an explicit component or selector (which could target
 * an activity with no browsable intent filter), launch flags (a
 * `FLAG_GRANT_*_URI_PERMISSION` would hand out this app's own content),
 * clip data, this app as the target, and data in a blocked scheme.
 * Either way only activities that declare themselves `BROWSABLE` —
 * willing to be opened from a web link — can receive it.
 */
internal fun externalAppLaunch(url: String, ownPackage: String): ExternalAppLaunch? {
    val scheme = externalLinkScheme(url)?.takeIf(::isExternalSchemeAllowed) ?: return null
    var fallbackUrl: String? = null
    val intent = if (scheme == "intent") {
        val parsed = parseIntentUrl(url) ?: return null
        parsed.component = null
        parsed.selector = null
        parsed.flags = 0
        parsed.clipData = null
        if (parsed.`package` == ownPackage) return null
        val dataScheme = parsed.scheme?.lowercase()
        if (dataScheme != null && dataScheme != "http" && dataScheme != "https" &&
            dataScheme in BLOCKED_SCHEMES
        ) {
            return null
        }
        fallbackUrl = parsed.getStringExtra("browser_fallback_url")
            ?.takeIf { schemeOf(it) == "http" || schemeOf(it) == "https" }
        parsed
    } else {
        Intent(Intent.ACTION_VIEW, Uri.parse(url))
    }
    intent.addCategory(Intent.CATEGORY_BROWSABLE)
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return ExternalAppLaunch(ExternalScheme(scheme), intent, fallbackUrl)
}

/**
 * [url] parsed as an `intent:` URL, or `null` when it doesn't parse.
 * `Intent.parseUri` reports a malformed URL as `URISyntaxException`
 * only for some errors: a bad typed extra (`i.n=zz`, `b.x=…`) or
 * `launchFlags=` escapes as `NumberFormatException`, others as other
 * runtime exceptions. Any of them, thrown out of a WebView callback,
 * takes the whole app down — so every failure is "not a link".
 */
internal fun parseIntentUrl(
    url: String,
    parse: (String) -> Intent = { Intent.parseUri(it, Intent.URI_INTENT_SCHEME) },
): Intent? = try {
    parse(url)
} catch (_: URISyntaxException) {
    null
} catch (_: RuntimeException) {
    null
}

/** Start [launch]; `false` when no app on the device can take it. */
internal fun startExternalApp(context: Context, launch: ExternalAppLaunch): Boolean = try {
    context.startActivity(launch.intent)
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (e: SecurityException) {
    // An `intent:` asking for something this app may not start (e.g. a
    // protected action): the same as there being no app for it.
    Log.w("ExternalApps", "${launch.scheme.scheme}: launch refused", e)
    false
}
