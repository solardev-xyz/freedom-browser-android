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
 *    a user gesture, *and* the tab's WebView must have seen a tap, key
 *    press or accessibility click within [UserGestureLatch.WINDOW_MS] that no earlier launch
 *    used up, and that landed in the top document itself, not in an
 *    iframe ([UserGestureLatch]). A page can't open an app — or a
 *    prompt — on load, from a timer, from an embedded frame (not even
 *    by navigating the top frame, `target=_top`), or turn one tap into
 *    a burst of launches.
 * 3. The site-permission prompt (#81), keyed by origin + scheme
 *    ([ExternalScheme], stored as `external:<scheme>` like desktop), so
 *    allowing `magnet:` for a site never allows `sms:` too. Remembered
 *    decisions, the dismissal embargo and revoking from Settings all
 *    come from [SitePermissionBroker] unchanged.
 *
 *    The origin is the page the navigation *started from* — the
 *    committed document — even when the app link is the end of a
 *    server-redirect chain through other sites. As in Chrome, which
 *    attributes the navigation to its initiator: a remembered Allow of
 *    `intent:` for site A also covers a link on A to
 *    `https://tracker.example` that redirects to an `intent:` URL. The
 *    redirecting server isn't asked about separately; it can only reach
 *    an app through a tap on a page the user already trusted with that
 *    scheme, and `intent:` URLs are still stripped of components, flags
 *    and grants ([externalAppLaunch]).
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
 * And the input has to have landed in the *top* document. A
 * cross-origin iframe can navigate the top frame (`target=_top`), and
 * that navigation is for the main frame and carries the tap's gesture —
 * but consent would be asked for, and remembered against, the top
 * page's origin, which never asked. Nothing native says which frame a
 * tap went to, so the top document says so itself: the page detector
 * ([bottomUiDetectorJs]) posts [TOP_DOCUMENT_INPUT] from its capture
 * listeners for trusted `pointerdown` / `keydown` / `click`, which only
 * fire in the top document when the input targets it (a tap on an
 * iframe is dispatched inside the iframe's document alone), and Kotlin
 * takes it only when WebView reports the message as the main frame's.
 * That message is not ordered with the navigation: on the API 36 AVD it
 * lands ~20 ms *after* `shouldOverrideUrlLoading` for the tap's link,
 * and far later when a script (an iframe's, even: it shares the
 * renderer thread) keeps the page busy. So each input gets an id and
 * its start…end time ([onInputStart], [onInputContinues]); the message
 * carries the DOM event's age, which puts it on the same timeline, and
 * confirms only the one input it falls inside — a late word about a
 * tap on the top page can't vouch for a later tap in an iframe, nor the
 * other way round ([onTopDocumentInput]). The navigation — cancelled
 * anyway — takes the id with the tap ([consume]), and the offer waits
 * for the top document to confirm that same input
 * ([whenInTopDocument]), for at most [CONFIRM_MS] ([giveUp]); later
 * input (a scroll during a slow redirect chain) doesn't drop it. Without the detector
 * (a WebView lacking the document-start script) no input is ever
 * confirmed, and app links are refused: fail closed.
 *
 * [clock] is a monotonic millisecond clock (`SystemClock.uptimeMillis`).
 * Main thread only.
 */
internal class UserGestureLatch(private val clock: () -> Long) {
    /** One input: when it began and (so far) ended, on [clock]'s timeline. */
    private class Input(val id: Int, val start: Long, var end: Long, var untilConfirmed: Boolean) {
        var inTopDocument = false

        /** Whether the top document's input at [at] can be this input's. */
        fun covers(at: Long): Boolean =
            at >= start - EARLY_MS && at <= (if (untilConfirmed) start + CONFIRM_MS else end + LATE_MS)
    }

    private var inputId = 0
    private val recent = ArrayDeque<Input>()
    private var armedId: Int? = null
    private var armedAt = 0L
    private val waiting = HashMap<Int, () -> Unit>()

    /**
     * A touch, key press or accessibility click begins — before the page
     * sees it — at [at] (the event's own time, e.g. `MotionEvent.eventTime`).
     * A new input id. An offer still waiting on an earlier input keeps
     * waiting: its confirmation names its own input, not "the latest".
     *
     * [untilConfirmed] is for an accessibility click, which has no
     * platform event time: Blink stamps the click it simulates when it
     * runs it in the renderer, as late as a long task on the page makes
     * it. So such an input covers any confirmation from its start until
     * [CONFIRM_MS] later (the most an offer waits anyway), and closes on
     * the first one — the one click it makes. A later input whose word
     * arrives while it is still open matches both and is refused: fail
     * closed.
     */
    fun onInputStart(at: Long = clock(), untilConfirmed: Boolean = false) {
        inputId++
        recent.addLast(Input(inputId, at, at, untilConfirmed))
        val stale = clock() - WINDOW_MS - CONFIRM_MS
        while (recent.isNotEmpty() && (recent.size > MAX_RECENT || recent.first().end < stale)) recent.removeFirst()
    }

    /** The current input goes on until [at] (a touch's `ACTION_UP`). */
    fun onInputContinues(at: Long) {
        val input = recent.lastOrNull()?.takeIf { it.id == inputId } ?: return
        if (at > input.end) input.end = at
    }

    /**
     * The top document received trusted input ([TOP_DOCUMENT_INPUT]) that
     * happened [ageMs] before now, by the page's own clock (the DOM
     * event's `timeStamp`, which Chromium takes from the platform event).
     * Credited to the one recent input it falls inside — never simply to
     * the current one: a renderer busy with an iframe's script can deliver
     * an earlier tap's word after a later tap has begun. Nothing, when it
     * matches no input or more than one. Runs an offer waiting on it.
     */
    fun onTopDocumentInput(ageMs: Long) {
        // Event time plus the message's own (small, positive) transit.
        val at = clock() - ageMs
        val input = recent.singleOrNull { it.covers(at) } ?: return
        if (input.untilConfirmed) {
            input.untilConfirmed = false
            if (at > input.end) input.end = at
        }
        input.inTopDocument = true
        waiting.remove(input.id)?.invoke()
    }

    /** The user tapped or pressed a key on the page: one launch's worth. */
    fun onInput() {
        armedId = inputId
        armedAt = clock()
    }

    /**
     * The id of the input that buys this launch — once, when there was
     * input within [WINDOW_MS] — else `null`. Which frame it went to is
     * [whenInTopDocument]'s question.
     */
    fun consume(): Int? {
        val id = armedId ?: return null
        armedId = null
        return id.takeIf { clock() - armedAt <= WINDOW_MS }
    }

    /**
     * Runs [offer] once the top document confirms input [id] was its own:
     * now, if it already has, or when its message arrives, even after
     * later input. False when it never can (the input is no longer on
     * record), so the link is refused now. A caller that gets true calls
     * [giveUp] after [CONFIRM_MS].
     */
    fun whenInTopDocument(id: Int, offer: () -> Unit): Boolean {
        val input = recent.firstOrNull { it.id == id } ?: return false
        if (input.inTopDocument) {
            offer()
            return true
        }
        waiting[id] = offer
        return true
    }

    /**
     * The top document never confirmed input [id]: the offer waiting on
     * it is refused. True when there was one still waiting.
     */
    fun giveUp(id: Int): Boolean = waiting.remove(id) != null

    companion object {
        const val WINDOW_MS = 5_000L

        /** How long an offer waits for the top document's [TOP_DOCUMENT_INPUT]. */
        const val CONFIRM_MS = 1_000L

        /**
         * How far a confirmation's time may fall outside its input's
         * start…end: before it, clock rounding only; after it, the
         * message's trip from the renderer to the main thread.
         */
        const val EARLY_MS = 5L
        const val LATE_MS = 50L

        private const val MAX_RECENT = 32
    }
}

/**
 * Whether a key event arms the [UserGestureLatch]: one fresh press of a
 * key that reaches the page. Not an auto-repeat (holding a key would
 * re-arm it on every repeat, one launch each), not a system key (volume,
 * media, back, call — pressed at the device, not at the page), and not
 * a modifier on its own (Shift or Ctrl isn't a key the page acts on).
 * Chromium grants activation on the same terms.
 */
internal fun keyArmsGestureLatch(
    action: Int,
    repeatCount: Int,
    isSystem: Boolean,
    isModifier: Boolean,
): Boolean = action == android.view.KeyEvent.ACTION_DOWN && repeatCount == 0 && !isSystem && !isModifier

/**
 * Whether an accessibility action arms the [UserGestureLatch]. TalkBack's
 * double-tap and Switch Access's select reach the page as `ACTION_CLICK`
 * on a node, not as a touch, so without this a screen-reader user's
 * activation of a `tel:` link would be refused with no feedback. A click
 * is the only action that activates an element; focus, scroll and
 * selection actions don't.
 */
internal fun accessibilityActionArmsGestureLatch(action: Int): Boolean =
    action == android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK

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
