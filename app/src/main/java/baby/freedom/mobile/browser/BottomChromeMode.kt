package baby.freedom.mobile.browser

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.json.JSONException
import org.json.JSONObject
import java.security.SecureRandom

// Reserved mode (#66): stop covering a site's own bottom navigation.
//
// The floating capsule sits over the bottom ~58 dp of the page. On an
// app-like page (a tab bar, a flex-column shell whose nav is its last
// child, a bottom cookie banner) that band is the page's own primary
// controls, and the capsule makes them untappable. Such a tab switches to
// [BottomChromeMode.Reserved]: the page area stops above the bar, which
// genuinely shrinks the WebView (Compose padding, the same mechanism the
// keyboard reserve uses — `android.webkit.WebView` ignores its own View
// padding, see [BrowserScreen]). The strip under the bar is filled with
// the nav's own colour so the page reads as continuing under the chrome.
//
// Detection is a hit test, a document-start script that stays dormant
// until the document first paints ([bottomUiDetectorJs]), the heuristic
// of Freedom iOS but event-driven: it runs once at first paint,
// then only when something could have changed the answer — load
// finished, a same-document history change, a viewport resize, DOM
// mutations (one debounced observer) and a size/visibility change of the
// nav it found. An idle page runs nothing. Results come back through
// `WebViewCompat.addWebMessageListener`, tagged with a per-document token,
// and are validated and debounced here ([BottomChromeSlot]).

/** How the bottom chrome sits against a tab's page. */
enum class BottomChromeMode {
    /** The floating capsule over a full-bleed page (the default). */
    Overlay,

    /** The page area's bottom edge stops above the capsule. */
    Reserved,

    /**
     * An overlay page the user pushed past its end (#65, see
     * [ScrollRevealSlot]): the same shortened page area and strip as
     * [Reserved], until they scroll back up.
     */
    Revealed,
}

/** Does [this] mode stop the page area above the capsule (reserved band + strip)? */
internal val BottomChromeMode.shortensPage: Boolean
    get() = this != BottomChromeMode.Overlay

/**
 * The page area's bottom padding (on top of the IME inset it already
 * gets from its window insets).
 *
 * - **Overlay, no keyboard:** nothing — the page runs under the bar.
 * - **Overlay, keyboard up:** the capsule's footprint, as before #66 (so a
 *   focused field at the end of a page can scroll clear of the capsule).
 * - **Reserved:** the capsule's *resting* footprint plus the navigation
 *   inset. Deliberately constant: compacting on scroll and the editing
 *   morph happen inside this band, and never resize the WebView (#63).
 * - **Revealed** (#65): exactly as reserved — the same band, so a page
 *   that goes from revealed to reserved doesn't move.
 * - **Reserved, keyboard up:** the keyboard reserve, but never less than
 *   the reserved band. The IME inset is already part of the page area's
 *   padding, so the navigation inset is only topped up while the rising
 *   keyboard is still shorter than it — the page does not jump taller for
 *   the first frames of the IME animation.
 *
 * @param capsuleFootprint the capsule's height plus its bottom margin as
 *   [BrowserScreen] computes it (editing height while the address bar
 *   has focus).
 */
internal fun contentBottomReserve(
    mode: BottomChromeMode,
    keyboardVisible: Boolean,
    capsuleFootprint: Dp,
    navInset: Dp,
    imeInset: Dp,
): Dp = when {
    mode.shortensPage && keyboardVisible ->
        capsuleFootprint + (navInset - imeInset).coerceAtLeast(0.dp)
    mode.shortensPage -> reservedFootprint(navInset)
    keyboardVisible -> capsuleFootprint
    else -> 0.dp
}

/**
 * How much of the page area the capsule still covers once
 * [contentBottomReserve] is applied. Native surfaces ([HomeScreen],
 * [SuggestionsPanel]) pad their content by it so the last row stays
 * clear of the chrome.
 *
 * - **Keyboard up:** nothing — the reserve already clears the capsule.
 * - **Overlay:** the whole footprint plus the navigation inset the page
 *   area draws behind.
 * - **Reserved / revealed:** only what the capsule grows *past* the reserved band —
 *   zero at rest, the editing morph's extra height while the address bar
 *   has focus without an IME (hardware keyboard, ChromeOS), which would
 *   otherwise cover the nearest suggestion row.
 */
internal fun capsuleOverlap(
    mode: BottomChromeMode,
    keyboardVisible: Boolean,
    capsuleFootprint: Dp,
    navInset: Dp,
): Dp {
    if (keyboardVisible) return 0.dp
    val covered = capsuleFootprint + navInset.coerceAtLeast(0.dp)
    return if (mode.shortensPage) {
        (covered - reservedFootprint(navInset)).coerceAtLeast(0.dp)
    } else {
        covered
    }
}

/** The reserved band: the resting capsule, its margin and the navigation inset. */
internal fun reservedFootprint(navInset: Dp): Dp =
    CapsuleHeight + CapsuleBottomMargin + navInset.coerceAtLeast(0.dp)

/**
 * Does the bottom-nav detector run in this document? Everything the
 * WebView shows except our own home sentinel: `about:blank` sits under
 * the native Home overlay, which pads itself.
 */
internal fun bottomUiApplies(url: String?): Boolean =
    !url.isNullOrEmpty() && url != ABOUT_BLANK

/**
 * The mode a tab's chrome actually uses: the home surface is a native
 * screen that pads itself, never reserved.
 */
internal fun effectiveBottomChromeMode(mode: BottomChromeMode, isHomeTab: Boolean): BottomChromeMode =
    if (isHomeTab) BottomChromeMode.Overlay else mode

/**
 * The strip's colour under the bar in reserved mode, as ARGB. The page's
 * report already carries the first of the detected nav's background, the
 * page's `theme-color` and the page background that it found
 * ([bottomUiDetectorJs]); [surfaceArgb] (the theme surface) when it found
 * none or the value doesn't validate.
 */
internal fun bottomStripArgb(reportedRgb: String?, surfaceArgb: Int): Int =
    parseRgb(reportedRgb) ?: surfaceArgb

private val RGB = Regex("""rgb\((\d{1,3}), (\d{1,3}), (\d{1,3})\)""")

/** `rgb(r, g, b)` (0..255 each, the one form the detector sends) → opaque ARGB, else null. */
internal fun parseRgb(value: String?): Int? {
    val m = RGB.matchEntire(value ?: return null) ?: return null
    val (r, g, b) = m.destructured.toList().map { it.toInt() }
    if (r > 255 || g > 255 || b > 255) return null
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

/** One validated detector report. */
internal data class BottomUiReport(val hasBottomUI: Boolean, val color: String?)

/**
 * Validate a detector message for the document [expectedToken]. Accepts
 * exactly `{"token": <expectedToken>, "hasBottomUI": <boolean>,
 * "color": <rgb string> | null}` from the main frame; anything else —
 * another frame, another document's token, extra or missing keys, a
 * non-boolean flag, a colour that isn't `rgb(r, g, b)` — is dropped. A
 * negative report carries no colour.
 */
internal fun parseBottomUiMessage(
    data: String?,
    isMainFrame: Boolean,
    expectedToken: String?,
): BottomUiReport? {
    if (!isMainFrame || data == null || expectedToken == null || data.length > 512) return null
    val o = try {
        JSONObject(data)
    } catch (_: JSONException) {
        return null
    }
    if (o.length() != 3 || !o.has("token") || !o.has("hasBottomUI") || !o.has("color")) return null
    if (o.opt("token") != expectedToken) return null
    val has = o.opt("hasBottomUI") as? Boolean ?: return null
    val rawColor = o.opt("color")
    val color = when {
        rawColor == null || rawColor == JSONObject.NULL -> null
        rawColor is String && parseRgb(rawColor) != null -> rawColor
        else -> return null
    }
    return BottomUiReport(has, if (has) color else null)
}

/** Two negative probes at least this far apart switch a reserved tab back to overlay. */
internal const val RESERVED_EXIT_GAP_MS = 1_000L

/**
 * A reserved spell that ends within this long of starting counts as a
 * quick exit; see [RESERVED_MAX_QUICK_EXITS].
 */
internal const val RESERVED_QUICK_EXIT_MS = 3_000L

/**
 * Quick exits per document after which the tab stays in overlay. A
 * backstop against a page whose nav exists only at the full viewport
 * height (a nav placed at an absolute pixel offset, say): reserving would
 * hide it, overlay would show it again, and without this the tab would
 * cycle about once a second. The detector itself is built not to do
 * this for bottom-anchored navs (see [bottomUiDetectorJs]).
 */
internal const val RESERVED_MAX_QUICK_EXITS = 3

/**
 * A tab's bottom-chrome mode for the document on screen: the per-document
 * token, validation and hysteresis. Single-threaded (the message listener
 * and WebView callbacks run on the UI thread).
 *
 * - Every document starts in [BottomChromeMode.Overlay] with a fresh
 *   token; reports tagged with an older one are dropped.
 * - The first positive report switches to [BottomChromeMode.Reserved].
 * - Back to overlay only after two negative reports at least
 *   [RESERVED_EXIT_GAP_MS] apart. The first negative asks the caller to
 *   re-probe after the gap ([Verdict.confirmInMs]); a positive in between
 *   cancels the exit.
 */
internal class BottomChromeSlot(
    private val newToken: () -> String = ::randomToken,
) {
    /** The answer to one report. */
    data class Verdict(val changed: Boolean, val confirmInMs: Long? = null)

    /** The current document's token; null before the first document. */
    var token: String? = null
        private set

    var mode: BottomChromeMode = BottomChromeMode.Overlay
        private set

    /** The last validated strip colour while reserved; null in overlay. */
    var color: String? = null
        private set

    /** Has the detector been installed in this document? */
    var installed: Boolean = false
        private set

    private var firstNegativeAt = -1L
    private var reservedAt = -1L
    private var quickExits = 0

    /** Is the tab pinned to overlay for this document? See [RESERVED_MAX_QUICK_EXITS]. */
    val latched: Boolean get() = quickExits >= RESERVED_MAX_QUICK_EXITS

    /** A new document: overlay, fresh token. Returns the token. */
    fun startDocument(): String {
        val t = newToken()
        token = t
        mode = BottomChromeMode.Overlay
        color = null
        installed = false
        firstNegativeAt = -1L
        reservedAt = -1L
        quickExits = 0
        return t
    }

    /**
     * The document's detector is being started (its first probe request);
     * returns the token to send, or null if already installed.
     */
    fun install(): String? {
        val t = token ?: return null
        if (installed) return null
        installed = true
        return t
    }

    /** A raw message from the page; see [parseBottomUiMessage]. */
    fun accept(data: String?, isMainFrame: Boolean, nowMs: Long): Verdict {
        val report = parseBottomUiMessage(data, isMainFrame, token) ?: return Verdict(false)
        return accept(report, nowMs)
    }

    fun accept(report: BottomUiReport, nowMs: Long): Verdict {
        if (report.hasBottomUI) {
            firstNegativeAt = -1L
            if (latched) return Verdict(false)
            val changed = mode != BottomChromeMode.Reserved || color != report.color
            if (mode != BottomChromeMode.Reserved) reservedAt = nowMs
            mode = BottomChromeMode.Reserved
            color = report.color
            return Verdict(changed)
        }
        if (mode == BottomChromeMode.Overlay) return Verdict(false)
        if (firstNegativeAt < 0) {
            firstNegativeAt = nowMs
            return Verdict(false, confirmInMs = RESERVED_EXIT_GAP_MS)
        }
        val waited = nowMs - firstNegativeAt
        if (waited < RESERVED_EXIT_GAP_MS) {
            return Verdict(false, confirmInMs = RESERVED_EXIT_GAP_MS - waited)
        }
        if (nowMs - reservedAt < RESERVED_QUICK_EXIT_MS) quickExits++
        mode = BottomChromeMode.Overlay
        color = null
        firstNegativeAt = -1L
        return Verdict(true)
    }
}

/**
 * Which reply channels a detector request goes to (#69). Every main-frame
 * document posts [BOTTOM_UI_READY] at document start, but a ready carries
 * nothing that says *which* document sent it, and it can arrive on either
 * side of that document's `onPageStarted` and, over different renderer
 * pipes, out of order with other documents' readies. So a ready's channel
 * is only a candidate until a report tagged with the current document's
 * token comes back through it: that proves the channel belongs to the
 * document on screen. Until then a request goes to every candidate, since
 * the channels of documents that are gone drop it and the new document's
 * channel is among them.
 *
 * - A ready is started right away only when the document on screen is
 *   installed and nothing has proved its channel yet. That is its own late
 *   ready. Once its channel is proved, a ready belongs to a document still
 *   on its way in (its ready beat its `onPageStarted`). It waits as a
 *   candidate, dormant until that document's first paint.
 * - A proved channel is the only target until the next document starts.
 *
 * One ordering is still ambiguous. If an incoming document's ready
 * arrives after the painted document was started but before its first
 * report, it can't be told from the painted document's own late ready,
 * so it is started with the painted document's token. That document then
 * starts before its first paint. Its reports are still right, because
 * its own start at first paint re-tags them.
 *
 * Generic over the channel type so the bookkeeping can be unit-tested; the
 * WebView uses [androidx.webkit.JavaScriptReplyProxy]. Single-threaded,
 * like [BottomChromeSlot].
 */
internal class BottomUiChannels<P : Any>(private val maxCandidates: Int = 4) {
    private val candidates = ArrayDeque<P>()

    /** The current document's channel, proved by one of its reports; null until then. */
    var proved: P? = null
        private set

    /** Where a request for the current document goes: its proved channel, else every candidate. */
    val targets: List<P> get() = proved?.let(::listOf) ?: candidates.toList()

    /**
     * A main-frame ready through [channel]. Returns the channels to send the
     * current document's start to now, if [installed]: this one when the
     * current document's channel is still unproved, nothing otherwise.
     */
    fun onReady(channel: P, installed: Boolean): List<P> {
        candidates.remove(channel)
        candidates.addLast(channel)
        while (candidates.size > maxCandidates) candidates.removeFirst()
        return if (installed && proved == null) listOf(channel) else emptyList()
    }

    /** A valid report for the current document's token came through [channel]. */
    fun onReport(channel: P) {
        proved = channel
        candidates.remove(channel)
    }

    /** A new main-frame document: the proved channel was the old one's. Candidates stay. */
    fun startDocument() {
        proved = null
    }
}

private val tokenRandom = SecureRandom()

private fun randomToken(): String {
    val bytes = ByteArray(12).also(tokenRandom::nextBytes)
    return bytes.joinToString("") { "%02x".format(it) }
}

/**
 * A fresh name for the object `addWebMessageListener` injects into pages
 * (#69), one per WebView. The platform puts that object on `window` in
 * every frame of every origin, before any script runs, and there is no
 * rule that scopes it to http(s) main frames only (see
 * [BOTTOM_UI_ORIGIN_RULES]). [bottomUiDetectorJs], registered as a
 * document-start script, takes it off `window` again before the page's
 * first script can look, so a page never sees it under any name; the name
 * is random anyway, so there is no fixed global to probe for, like
 * `typeof bottomUiChannel`, should that ever fail to happen. Lower-case
 * letters only: a plain identifier, safe to splice into the script.
 */
internal fun newBottomUiChannelName(): String {
    val letters = "abcdefghijklmnopqrstuvwxyz"
    return String(CharArray(16) { letters[tokenRandom.nextInt(letters.length)] })
}

/** Is [name] safe to splice into the detector's source? (Lower-case letters only.) */
private val CHANNEL_SAFE = Regex("[a-z]{8,64}")

/** What the detector posts once, at document start, so Kotlin has a way to reach it. */
internal const val BOTTOM_UI_READY = "ready"

/** The detector's report that the page let a long-press's `contextmenu` through (#84). */
internal const val CONTEXT_MENU_ALLOWED = "contextmenu 1"

/** The detector's report that the page kept a long-press (`preventDefault()` on its `contextmenu`). */
internal const val CONTEXT_MENU_KEPT = "contextmenu 0"

/**
 * What the detector posts, followed by the current document's token,
 * when a mutation touched a `<meta>` (added, removed, or its `content` /
 * `media` / `name` changed) or swapped a `<head>`: the page's theme
 * colour may have changed, so Kotlin reads it again (#92). A ping only —
 * the colour itself is read by [THEME_COLOR_JS].
 */
internal const val THEME_COLOR_PING_PREFIX = "theme "

/**
 * Is [data] the current document's [THEME_COLOR_PING_PREFIX] ping? Main
 * frame only, and only with [expectedToken] (another document's ping, or
 * one sent before Kotlin's first probe request, is dropped).
 */
internal fun isThemeColorPing(data: String?, isMainFrame: Boolean, expectedToken: String?): Boolean =
    isMainFrame && expectedToken != null && data == THEME_COLOR_PING_PREFIX + expectedToken

/** What Kotlin sends back through the channel to ask for a fresh, reported probe. */
internal fun bottomUiProbeRequest(token: String): String = "probe $token"

/**
 * The detector: a document-start script ([WebViewCompat.addDocumentStartJavaScript]),
 * registered once per WebView with that WebView's [channel] name, which
 * runs in every frame before any of the page's own scripts.
 *
 * **The channel is gone before the page runs** (#69). The first thing
 * it does, in every frame, is take the listener's object off `window`
 * (`delete`: the platform defines it as an ordinary configurable
 * property) and keep it in its closure. No script of the page's, the top
 * document's or any iframe's, can then find it — not by name, not by
 * walking `window`'s properties.
 *
 * **The page's say on a long-press** (#84), in every frame: a capture
 * listener for `contextmenu` on `window` — the first one there, since
 * this runs before the page — reports each trusted event's outcome
 * through the same channel, [CONTEXT_MENU_ALLOWED] or
 * [CONTEXT_MENU_KEPT], a task after dispatch (with the `setTimeout`
 * saved at document start), when every page handler, including a
 * bubbling one on `window` registered after ours, has had its say. The
 * browser's link / image menu opens only for a press the page didn't
 * `preventDefault()` ([PageContextMenuPress]). (`-webkit-touch-callout`
 * needs no check: Android's Blink doesn't parse it — `CSS.supports` is
 * false on the API 36 AVD.) In a subframe that is all it does.
 *
 * **Dormant until first paint.** In the main frame it posts
 * [BOTTOM_UI_READY] (so Kotlin holds a reply channel for the document)
 * and then waits. It starts when Kotlin's first [bottomUiProbeRequest]
 * arrives, which Kotlin sends at `onPageCommitVisible` ([BottomChromeSlot.install])
 * — the same install point as when this script was injected there.
 * Until then it touches nothing: no probe, no observer, no listener on
 * the page but the `contextmenu` one above. The request's token tags every report after it; a request
 * with a different token (a new install for the same document) re-tags
 * them and is answered like the first.
 *
 * **The probe** is Freedom iOS's hit test, thresholds unchanged: take the
 * element at `(vw/2, vh-30)` and walk up for an ancestor that is
 *  - anchored to the bottom: `vh-60 ≤ rect.bottom ≤ vh+20`;
 *  - nav-sized: `40 ≤ height ≤ vh*0.25`, `width ≥ vw*0.5`;
 *  - interactive: contains `a, button, [role=button|tab|link]`.
 * Deliberately not a `position: fixed` check: a flex-column shell whose
 * nav is simply the last child of a viewport-tall container is the same
 * thing to the user.
 *
 * One exclusion on top of iOS's rules: while the *document* scrolls, a
 * candidate with no `fixed`/`sticky` element among itself and its
 * ancestors scrolls with it — an ordinary footer that is at the viewport
 * bottom only because the page is scrolled to its end (seen on the AVD
 * with the article fixture and the keyboard up). Reserving for it would
 * also stick: scrolling away is not an event the detector listens to.
 * The same holds for a page scrolled to its end and revealed (#65): its
 * footer sits right above the bar, but it scrolls. A flex shell's
 * document doesn't scroll (its inner container does), so it is
 * unaffected.
 *
 * **Colour**, first found: a non-transparent background on the nav or an
 * ancestor below `<body>`; the page's `theme-color` (a `media` query, if
 * any, must match); `<body>`'s, then `<html>`'s background. `null` when
 * there is none (the theme surface is used, [bottomStripArgb]).
 *
 * **Reserving cannot make the nav disappear.** Reserved mode shortens the
 * viewport; the probe point and the anchoring band are measured from the
 * *current* viewport's bottom, which a bottom-anchored nav (fixed,
 * sticky, or the last child of a `100vh`/`100%` shell) follows. The one
 * threshold that would otherwise move, `height ≤ vh*0.25`, is taken
 * against the tallest viewport seen at the current width, so a nav that
 * passed at full height still passes in the shortened one.
 *
 * **When it runs.** Once at install (Kotlin's first request). Then only after an event, debounced
 * to one probe per [debounceMs]: a `resize` of the window (the reserve
 * itself, the keyboard, rotation), a DOM mutation (one `MutationObserver`
 * on the document, whose callback only arms the debounce timer), and a
 * `ResizeObserver`/`IntersectionObserver` on the nav it found — a nav
 * that collapses, is removed or slides off-screen is noticed even if the
 * change is outside the mutation filter. The Kotlin side adds load
 * finished and same-document history changes (`pushState`,
 * `replaceState`, `popstate`, `hashchange` all arrive as
 * `doUpdateVisitedHistory`), and the hysteresis confirmation, as
 * [bottomUiProbeRequest] messages. Nothing polls: with no events there
 * is no work.
 *
 * **A report is owed until it is made.** A forced probe (install, or
 * Kotlin asking) that finds no `<body>` yet can't answer; the next
 * probe, whatever woke it, reports even if its answer matches the last
 * one, so an ask that came too early is still honoured. If `<html>` itself
 * wasn't there at install, the `MutationObserver` is attached on the
 * document's next `readystatechange` (which also probes).
 *
 * **Theme colour** (#92). The same `MutationObserver` also watches
 * `content`, `media` and `name`; a batch that touched a `<meta>` (or a
 * `<head>`) marks the theme colour dirty, and the next debounced run
 * posts a [THEME_COLOR_PING_PREFIX] ping with the token, so a route that
 * sets its `theme-color` after a data fetch (react-helmet, Next.js, Vue's
 * `useHead`) is still read. The ping carries no colour; Kotlin reads it.
 *
 * **Reporting.** Only when the answer (flag, colour) changes, or when
 * Kotlin asked. Nothing is written to the page: no DOM node, attribute,
 * style or global of ours — the platform's channel object included,
 * see above — and history methods are not patched.
 *
 * **Not invisible once started.** The probe and the start still call
 * DOM methods the page can replace: `addEventListener`,
 * `MutationObserver.prototype.observe`, `elementFromPoint`,
 * `querySelectorAll`, `getComputedStyle`. The detector only saves the
 * constructors and `getComputedStyle` at document start. A page that
 * wraps these methods before first paint can see the detector's calls,
 * and the listener it registers (whose source it can read). What stays
 * hidden is the channel object, and with it any way to talk to Kotlin.
 */
internal fun bottomUiDetectorJs(channel: String, debounceMs: Int = BOTTOM_UI_DEBOUNCE_MS): String {
    require(CHANNEL_SAFE.matches(channel)) { "channel must be lower-case letters" }
    return """
(function () {
  var w = window, d = document, N = '$channel', port = w[N];
  if (port === undefined) return;
  try { delete w[N]; } catch (e) {}
  if (!port || typeof port.postMessage !== 'function') return;
  var setT = w.setTimeout;
  w.addEventListener('contextmenu', function (e) {
    if (!e.isTrusted) return;
    setT(function () { port.postMessage(e.defaultPrevented ? '$CONTEXT_MENU_KEPT' : '$CONTEXT_MENU_ALLOWED'); }, 0);
  }, true);
  if (w.top !== w) return;
  var T = null, started = false, ASK = /^probe ([0-9a-f]{1,64})$/, SEL = 'a, button, [role="button"], [role="tab"], [role="link"]';
  var gcs = w.getComputedStyle, MO = w.MutationObserver,
      RO = w.ResizeObserver, IO = w.IntersectionObserver, str = JSON.stringify;
  var timer = 0, last = null, owed = false, mo = null, watched = null, ro = null, io = null, fullW = -1, fullH = 0, ctx = null,
      metaDirty = false;
  var RGBA = /^rgba?\(\s*([\d.]+)[\s,]+([\d.]+)[\s,]+([\d.]+)\s*(?:[,\/]\s*([\d.]+)(%?)\s*)?\)$/;
  function paint(c) {
    var m = RGBA.exec(c || '');
    if (!m) return null;
    if (m[4] !== undefined && parseFloat(m[4]) === 0) return null;
    return 'rgb(' + Math.round(+m[1]) + ', ' + Math.round(+m[2]) + ', ' + Math.round(+m[3]) + ')';
  }
  function norm(c) {
    if (!c) return null;
    var p = paint(c);
    if (p) return p;
    try {
      if (!ctx) ctx = d.createElement('canvas').getContext('2d');
      ctx.fillStyle = '#000'; ctx.fillStyle = c; var a = ctx.fillStyle;
      ctx.fillStyle = '#fff'; ctx.fillStyle = c; if (ctx.fillStyle !== a) return null;
      var h = /^#([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})$/i.exec(a);
      return h ? 'rgb(' + parseInt(h[1], 16) + ', ' + parseInt(h[2], 16) + ', ' + parseInt(h[3], 16) + ')' : paint(a);
    } catch (e) { return null; }
  }
  function themeColor() {
    var ms = d.querySelectorAll('meta[name="theme-color" i]');
    for (var i = 0; i < ms.length; i++) {
      var q = ms[i].getAttribute('media');
      if (q && !(w.matchMedia && w.matchMedia(q).matches)) continue;
      var c = norm(ms[i].getAttribute('content'));
      if (c) return c;
    }
    return null;
  }
  function pinned(n) {
    for (; n && n !== d.documentElement; n = n.parentElement) {
      var p = gcs(n).position;
      if (p === 'fixed' || p === 'sticky') return true;
    }
    return false;
  }
  function probe() {
    var de = d.documentElement, b = d.body;
    if (!de || !b) return null;
    var std = d.compatMode === 'CSS1Compat';
    var vw = (std && de.clientWidth) || w.innerWidth, vh = (std && de.clientHeight) || w.innerHeight;
    if (!vw || !vh) return null;
    if (vw !== fullW) { fullW = vw; fullH = vh; } else if (vh > fullH) fullH = vh;
    var nav = null, se = d.scrollingElement || de, scrolls = se.scrollHeight > vh + 1;
    for (var n = d.elementFromPoint(vw / 2, vh - 30); n && n !== b && n !== de; n = n.parentElement) {
      var r = n.getBoundingClientRect();
      if (r.bottom >= vh - 60 && r.bottom <= vh + 20 && r.height >= 40 && r.height <= fullH * 0.25 &&
          r.width >= vw * 0.5 && n.querySelector(SEL) && !(scrolls && !pinned(n))) { nav = n; break; }
    }
    if (!nav) return { nav: null, color: null };
    var c = null;
    for (var m = nav; m && m !== b && m !== de && !c; m = m.parentElement) c = paint(gcs(m).backgroundColor);
    return { nav: nav, color: c || themeColor() || paint(gcs(b).backgroundColor) || paint(gcs(de).backgroundColor) };
  }
  function watch(el) {
    if (el === watched) return;
    if (ro) ro.disconnect();
    if (io) io.disconnect();
    ro = io = null; watched = el;
    if (!el) return;
    if (RO) { ro = new RO(soon); ro.observe(el); }
    if (IO) { io = new IO(soon); io.observe(el); }
  }
  function run(force) {
    timer = 0;
    if (metaDirty) { metaDirty = false; port.postMessage('$THEME_COLOR_PING_PREFIX' + T); }
    var p = null;
    try { p = probe(); } catch (e) {}
    if (!p) { if (force) owed = true; return; }
    watch(p.nav);
    var key = !!p.nav + ' ' + p.color;
    if (!force && !owed && key === last) return;
    last = key; owed = false;
    port.postMessage(str({ token: T, hasBottomUI: !!p.nav, color: p.color }));
  }
  function soon() { if (!timer) timer = setT(function () { run(false); }, $debounceMs); }
  function isMeta(n) { return !!n && (n.nodeName === 'META' || n.nodeName === 'HEAD'); }
  function metaTouched(recs) {
    for (var i = 0; recs && i < recs.length; i++) {
      var r = recs[i];
      if (r.type === 'attributes') { if (isMeta(r.target)) return true; continue; }
      var lists = [r.addedNodes, r.removedNodes];
      for (var j = 0; j < 2; j++) for (var k = 0; lists[j] && k < lists[j].length; k++) if (isMeta(lists[j][k])) return true;
    }
    return false;
  }
  function attach() {
    if (mo || !MO || !d.documentElement) return;
    mo = new MO(function (recs) { if (metaTouched(recs)) metaDirty = true; soon(); });
    mo.observe(d.documentElement, {
      childList: true, subtree: true, attributes: true,
      attributeFilter: ['class', 'style', 'hidden', 'open', 'content', 'media', 'name']
    });
  }
  function start() {
    started = true;
    w.addEventListener('resize', soon);
    if (d.addEventListener) d.addEventListener('readystatechange', function () { attach(); soon(); });
    attach();
  }
  port.addEventListener('message', function (e) {
    var m = e && typeof e.data === 'string' ? ASK.exec(e.data) : null;
    if (!m) return;
    if (m[1] !== T) { T = m[1]; last = null; }
    if (!started) start();
    run(true);
  });
  port.postMessage('$BOTTOM_UI_READY');
})();
"""
}

/** Debounce for event-triggered probes; the task floor is 250 ms. */
internal const val BOTTOM_UI_DEBOUNCE_MS = 300

/**
 * Origins the channel is injected into. Any site may have a bottom nav,
 * so this has to cover every web origin — and the rule grammar has no
 * scheme-wide wildcard: WebView 133 rejects `https` plus a bare `*` host with
 * `IllegalArgumentException` (a host wildcard must be `*.` plus a
 * domain). `*` is the one rule that covers them all; the listener then
 * accepts only `http`/`https` source origins, main frame only. The
 * detector's document-start script uses the same rule, so it runs in
 * every frame the channel object lands in and removes it there (#69).
 */
internal val BOTTOM_UI_ORIGIN_RULES: Set<String> = setOf("*")
