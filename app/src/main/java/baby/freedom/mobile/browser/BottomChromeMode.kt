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
 * The prefix of the detector's theme-colour messages (#92), both ways:
 * Kotlin asks with [themeColorRequest], and the detector answers
 * `theme <token> <colour>` — `rgb(r, g, b)` or `none` — to that ask and,
 * unasked, whenever a mutation touched a `<meta>` (added, removed, or its
 * `content` / `media` / `name` changed) or swapped a `<head>`. See
 * [parseThemeColorReport].
 */
internal const val THEME_COLOR_PREFIX = "theme "

/** What Kotlin sends through the channel to ask the detector for the theme colour. */
internal fun themeColorRequest(token: String): String = THEME_COLOR_PREFIX + token

/** One validated theme-colour answer: [argb] is the opaque colour, or `null` for none. */
internal data class ThemeColorReport(val argb: Int?)

/**
 * Validate a detector theme-colour message for the document
 * [expectedToken]: exactly `theme <token> none` or `theme <token>
 * rgb(r, g, b)` ([parseRgb]'s form), main frame only. Anything else —
 * another document's token, no token yet, another shape — is `null`.
 */
internal fun parseThemeColorReport(data: String?, isMainFrame: Boolean, expectedToken: String?): ThemeColorReport? {
    if (!isMainFrame || data == null || expectedToken == null || data.length > 128) return null
    val head = THEME_COLOR_PREFIX + expectedToken + " "
    if (!data.startsWith(head)) return null
    val value = data.substring(head.length)
    if (value == "none") return ThemeColorReport(null)
    return parseRgb(value)?.let(::ThemeColorReport)
}

/**
 * The top document's detector saw trusted user input aimed at the top
 * document itself — not at an iframe — so an app link the input leads
 * to is the top page's to ask for (#85, see [UserGestureLatch]).
 */
internal const val TOP_DOCUMENT_INPUT = "input"

private val TOP_DOCUMENT_INPUT_RE = Regex("^$TOP_DOCUMENT_INPUT (pointerdown|keydown|click) (\\d{1,7})$")

/**
 * One [TOP_DOCUMENT_INPUT] report: the DOM event happened [ageMs] before
 * the message was sent, and was a `click` ([isClick]) rather than a
 * `pointerdown` or `keydown`. Only a click can be an accessibility
 * click's own word ([UserGestureLatch.onTopDocumentInput]).
 */
internal data class TopDocumentInput(val ageMs: Long, val isClick: Boolean)

/**
 * The input a [TOP_DOCUMENT_INPUT] message reports (`input <type> <ms>`:
 * which listener heard it, and how long before the message the DOM event
 * happened), or `null` when [data] isn't one.
 */
internal fun parseTopDocumentInput(data: String?): TopDocumentInput? {
    val m = TOP_DOCUMENT_INPUT_RE.matchEntire(data ?: return null) ?: return null
    return TopDocumentInput(m.groupValues[2].toLong(), isClick = m.groupValues[1] == "click")
}

/**
 * Kotlin's ask to the top document's detector, sent a moment after an
 * input ended (`sync <id> <settleMs>`), and the detector's echo
 * (`synced <id>`) (#348). The page's renderer thread handles input
 * ahead of the message and ahead of a task posted after it, so a task
 * later the renderer is past the input's own events. But not
 * necessarily past its tap: with double-tap zoom on (a page without a
 * `width=device-width` viewport) Chromium holds a tap's `GestureTap` —
 * the click, which renews a frame's activation — for up to the
 * double-tap timeout after the touch went down, counted from when the
 * renderer acknowledged it. So the detector waits [settleMs] more
 * (the double-tap timeout plus margin, [inputSyncSettleMs]) before it
 * echoes: a held-back click reaches the renderer by then, and a timer
 * that comes due runs after input already queued. The echo then says
 * the renderer is past input `<id>` — and past any activation it gave
 * an iframe, however long that iframe's own handlers held the thread
 * ([UserGestureLatch.onRendererCaughtUp]).
 */
internal const val INPUT_SYNC = "sync"
internal const val INPUT_SYNCED = "synced"

internal fun inputSyncRequest(id: Int, settleMs: Long): String = "$INPUT_SYNC $id $settleMs"

/**
 * How long after an input ends its [INPUT_SYNC] is sent: margin for the
 * input's last events to reach the renderer thread ahead of it, over
 * their own pipe.
 */
internal const val INPUT_SYNC_DELAY_MS = 100L

/**
 * How long the detector waits, once the renderer is past an input's own
 * events, before it echoes [INPUT_SYNCED]: Chromium's double-tap timeout
 * ([doubleTapTimeoutMs], `ViewConfiguration.getDoubleTapTimeout()`, what
 * its gesture detector holds a tap for) plus [INPUT_SYNC_DELAY_MS] for the
 * held-back click to reach the renderer.
 */
internal fun inputSyncSettleMs(doubleTapTimeoutMs: Int): Long =
    doubleTapTimeoutMs.toLong().coerceIn(0L, 5_000L) + INPUT_SYNC_DELAY_MS

private val INPUT_SYNCED_RE = Regex("^$INPUT_SYNCED (\\d{1,9})$")

/** The input id an [INPUT_SYNCED] echo names, or `null` when [data] isn't one. */
internal fun parseInputSynced(data: String?): Int? =
    INPUT_SYNCED_RE.matchEntire(data ?: return null)?.groupValues?.get(1)?.toIntOrNull()

/**
 * A frame's report that media in its document is now audible (#91):
 * playing, not muted by the page, volume above zero. Sent on a change
 * only; [AUDIO_SILENT] when that stops. See [TabAudioFrames].
 */
internal const val AUDIO_AUDIBLE = "audio 1"

/** A frame's report that nothing in its document is audible any more (#91). */
internal const val AUDIO_SILENT = "audio 0"

/**
 * How often an audible frame's detector looks at its elements again,
 * whatever events it heard (#91): the safety net for a silence no event
 * announces — `document.open()` erases every listener of the document and
 * its window, the detector's included, and pauses the elements it removes.
 * Each look that still finds sound says so again ([AUDIO_AUDIBLE]), so a
 * frame Kotlin forgot ([TabAudioFrames.clear], on a main-frame ready that
 * may overtake a subframe's report) is back within one period. Only while
 * the frame is audible; a silent frame runs nothing after [AUDIO_SOUND_TRIES].
 */
internal const val AUDIO_RECHECK_MS = 2000

/**
 * How soon a frame looks again at an element that plays but has decoded
 * no audio yet (#91) — a video with no audio track, or one whose first
 * audio isn't decoded at `playing` — and how many times before it gives
 * up until the element's next event. A track-less video looping forever
 * costs [AUDIO_SOUND_TRIES] looks, then nothing.
 */
internal const val AUDIO_SOUND_MS = 500
internal const val AUDIO_SOUND_TRIES = 6

/** Is [data] a frame's audio report ([AUDIO_AUDIBLE] → true, [AUDIO_SILENT] → false)? `null` if not one. */
internal fun parseAudioReport(data: String?): Boolean? = when (data) {
    AUDIO_AUDIBLE -> true
    AUDIO_SILENT -> false
    else -> null
}

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
 * false on the API 36 AVD.) In a subframe that and the audio reports
 * below are all it does.
 *
 * **Audible media** (#91), in every frame: a capture listener for
 * trusted `playing` on `window` picks up each `<audio>`/`<video>` of the
 * document as it starts, and from then on listens on the element itself
 * (`pause`, `ended`, `emptied`, `volumechange`, `waiting`, `stalled`,
 * `playing`, through
 * `EventTarget.prototype.addEventListener` saved at document start) — so
 * an element the page detaches mid-play, whose `pause` no longer reaches
 * `window`, is still heard, and one the page plays again after detaching
 * it (its trusted `playing` fires only on itself) is tracked again. The frame posts [AUDIO_AUDIBLE] when one of
 * its elements becomes audible (playing, not `muted`, `volume` above 0,
 * not starved of data: `readyState` past `HAVE_CURRENT_DATA`, so a
 * stream stuck `waiting`/`stalled` with `paused` still false doesn't
 * count until its next `playing` — and with sound: Chromium's
 * `webkitAudioDecodedByteCount`, read through the `HTMLMediaElement`
 * getter saved at document start, above 0, so a video with no audio
 * track isn't audible; one not yet decoded is looked at again every
 * [AUDIO_SOUND_MS], [AUDIO_SOUND_TRIES] times) and [AUDIO_SILENT] when none is any
 * more, or when the document goes (`pagehide`: navigated away, or its
 * iframe removed). While audible it also looks again every
 * [AUDIO_RECHECK_MS], so a silence no event reports (`document.open()`
 * erases the detector's listeners) still reaches Kotlin, and re-sends
 * [AUDIO_AUDIBLE] each time it still hears sound; Kotlin in turn
 * forgets every frame when a new main-frame document starts
 * ([TabAudioFrames.clear]). What it can't
 * see: Web Audio (an `AudioContext` fires nothing on `window`), an
 * element that never joined the document (`new Audio(src).play()`), and
 * one inside a shadow root (`playing` isn't composed). WebView itself has
 * no "this page is audible" signal (see [TabAudioFrames]).
 *
 * **Re-issuing the page's own navigation** (#180), in the main frame: a
 * `go <token> <url>` ask ([pageReissueRequest]) for the started document's
 * own token builds a detached `<a>` with `referrerPolicy=origin` and
 * `target=_self` and clicks it — through `createElement`, the anchor's
 * setters and `HTMLElement.prototype.click` saved at document start, so
 * no function the page wraps later sees it (R5-F3). Without those natives
 * the ask does nothing (and Kotlin's deadline undoes its switch).
 *
 * **Input in the top document** (#85): in the main frame, capture
 * listeners for trusted `pointerdown`, `keydown` and `click` post
 * [TOP_DOCUMENT_INPUT] at once (not a task later: it has to reach Kotlin
 * before the navigation the input starts), with the listener's event
 * type (each listener knows its own; `e.type` isn't read) and the event's age — `now`
 * minus its `timeStamp`, both read through `performance.now` and the
 * `Event.prototype` getter saved at document start, so page script can't
 * skew them — which lets Kotlin tell which input it was. A tap or key press aimed at
 * an iframe is dispatched in the iframe's document only, so this is how
 * Kotlin tells a tap on the top page from one on an embedded frame that
 * navigates the top frame ([UserGestureLatch]).
 *
 * **Renderer sync** (#348): in the main frame, a `sync <id>` ask
 * ([INPUT_SYNC]), sent by Kotlin just after an input ends, is echoed as
 * `synced <id>` a task and then `<settleMs>` later (the double-tap
 * timeout a held-back tap waits out), before first paint too — the earliest the
 * renderer is known to be past that input, and so past any activation
 * it gave an iframe ([UserGestureLatch.onRendererCaughtUp]).
 *
 * **Dormant until first paint.** In the main frame it posts
 * [BOTTOM_UI_READY] (so Kotlin holds a reply channel for the document)
 * and then waits. It starts when Kotlin's first [bottomUiProbeRequest]
 * arrives, which Kotlin sends at `onPageCommitVisible` ([BottomChromeSlot.install])
 * — the same install point as when this script was injected there.
 * Until then it touches nothing: no probe, no observer, no listener on
 * the page but the `contextmenu` and input ones above. The request's token tags every report after it; a request
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
 * **Theme colour** (#92). Read here, not by an injected script, so the
 * page can't watch it being read: the read ([THEME_COLOR_JS]'s rules)
 * goes only through functions saved at document start, before any page
 * script could replace them — `querySelectorAll`, `getAttribute`,
 * `matchMedia` and `MediaQueryList.matches`, `createElement`,
 * `getContext`, the 2D context's `fillStyle` accessor, `clearRect`,
 * `fillRect`, `getImageData` and `ImageData.data`, `NodeList.length`,
 * `RegExp.prototype.exec`, `parseInt`/`parseFloat`/`Math.round` — each
 * called through a `Function.prototype.call` bound at document start, so
 * a page that wraps any of them (or `call` itself) sees nothing. Kotlin
 * asks with [themeColorRequest]; a started detector answers an ask with
 * its own token only. The same `MutationObserver` also watches
 * `content`, `media` and `name`; a batch that touched a `<meta>` (or a
 * `<head>`) marks the theme colour dirty, and the next debounced run
 * sends the colour unasked, so a route that sets its `theme-color` after
 * a data fetch (react-helmet, Next.js, Vue's `useHead`) is still read.
 * The probe's own `theme-color` fallback for the strip uses the same read.
 *
 * **Reporting.** Only when the answer (flag, colour) changes, or when
 * Kotlin asked. Nothing is written to the page: no DOM node, attribute,
 * style or global of ours — the platform's channel object included,
 * see above — and history methods are not patched.
 *
 * **Nothing the page wraps later sees it** (#146). Every function the
 * detector calls after document start — once started, and in its media,
 * `contextmenu` and input listeners — was saved at document start,
 * before any page script, and is called through a `Function.prototype.call`
 * bound then: `addEventListener` (window's, the document's,
 * `EventTarget`'s), `elementFromPoint`, `querySelector`,
 * `getBoundingClientRect` and the `DOMRect` getters, the `Document`/`Node`/
 * `Element` getters it reads (`documentElement`, `body`, `compatMode`,
 * `scrollingElement`, `parentElement`, `nodeName`, `clientWidth`/`Height`,
 * `scrollHeight`, `innerWidth`/`Height`), `getComputedStyle` and
 * `CSSStyleDeclaration.getPropertyValue`, `observe`/`disconnect` of the
 * three observers, the `MutationRecord` and `NodeList.length` getters,
 * the `Event` getters (`target`, `currentTarget`, `type`,
 * `defaultPrevented`, `timeStamp`, `PageTransitionEvent.persisted`,
 * `MessageEvent.data`), the `HTMLMediaElement` getters,
 * and `Math.round`. No `Array` method runs (the media list is a
 * prototype-less object), and the report is built as text rather than by
 * `JSON.stringify`, which would ask `Object.prototype` for a `toJSON`.
 * `MutationObserver.observe`'s options are built at document start with
 * no prototype (the platform reads every option it knows, and a missing
 * one would be looked up on `Object.prototype`), and its attribute filter
 * is its own iterable rather than an array (a sequence is read through
 * `Array.prototype[Symbol.iterator]`, seen called on the AVD). So a page
 * that wraps any of these before first paint catches no call and never
 * gets hold of one of the detector's listeners or callbacks. The channel
 * object's own methods are called as they are (the page can't reach
 * that object), and its listeners get a plain object whose own `data` is
 * read directly. What a page can still notice is indirect: the
 * style and layout work a probe forces.
 */
internal fun bottomUiDetectorJs(channel: String, debounceMs: Int = BOTTOM_UI_DEBOUNCE_MS): String {
    require(CHANNEL_SAFE.matches(channel)) { "channel must be lower-case letters" }
    return """
(function () {
  var w = window, d = document, N = '$channel', port = w[N], AUDIO_EVENTS = ['pause', 'ended', 'emptied', 'volumechange', 'waiting', 'stalled', 'playing'];
  if (port === undefined) return;
  try { delete w[N]; } catch (e) {}
  if (!port || typeof port.postMessage !== 'function') return;
  var setT = w.setTimeout;
  // Natives, saved now, before any page script (#146): un(f)(o, …) is
  // f.call(o, …) through a `call` bound now, so neither a wrapped method
  // or getter nor a wrapped `Function.prototype.call` sees a call made
  // after the page has run. Where the platform has no such native (never
  // in Chromium) the live property is used instead.
  var fcall = Function.prototype.call, fbind = Function.prototype.bind, gopd = Object.getOwnPropertyDescriptor,
      gpo = Object.getPrototypeOf, round = Math.round;
  var un = function (f) { return typeof f === 'function' ? fbind.call(fcall, f) : null; };
  var method = function (proto, n) {
    return (proto && un(proto[n])) || function (o, a, b, c, e) { return o[n](a, b, c, e); };
  };
  var prop = function (proto, n, set) {
    var x = null;
    for (var p = proto; p && !x; p = gpo(p)) x = gopd(p, n);
    var f = x && un(set ? x.set : x.get);
    return f || (set ? function (o, v) { o[n] = v; } : function (o) { return o[n]; });
  };
  var proto = function (C) { return C && C.prototype; };
  var EvP = proto(w.Event), evTarget = prop(EvP, 'target'), evCurrent = prop(EvP, 'currentTarget'),
      evType = prop(EvP, 'type'), evPrevented = prop(EvP, 'defaultPrevented');
  w.addEventListener('contextmenu', function (e) {
    if (!e.isTrusted) return;
    setT(function () { port.postMessage(evPrevented(e) ? '$CONTEXT_MENU_KEPT' : '$CONTEXT_MENU_ALLOWED'); }, 0);
  }, true);
  var ET = w.EventTarget, onEl = un(ET && ET.prototype && ET.prototype.addEventListener), loud = false, recheck = 0, tries = 0;
  // Media being heard: a list with no prototype, so no Array method runs.
  var media = Object.create(null), nm = 0;
  function slot(m) { for (var i = 0; i < nm; i++) if (media[i] === m) return i; return -1; }
  function drop(i) { for (; i < nm - 1; i++) media[i] = media[i + 1]; delete media[--nm]; }
  var MP = proto(w.HTMLMediaElement), adb = MP && gopd(MP, 'webkitAudioDecodedByteCount'), adbOf = un(adb && adb.get),
      paused = prop(MP, 'paused'), ended = prop(MP, 'ended'), muted = prop(MP, 'muted'), volume = prop(MP, 'volume'),
      ready = prop(MP, 'readyState');
  function sound(m) {
    if (!adbOf) return true;
    try { return !(adbOf(m) === 0); } catch (e) { return true; }
  }
  function hear(beat) {
    var now = false, unsure = false;
    for (var i = nm - 1; i >= 0; i--) {
      var m = media[i];
      if (paused(m) || ended(m)) drop(i);
      else if (!muted(m) && volume(m) > 0 && ready(m) > 2) { if (sound(m)) now = true; else unsure = true; }
    }
    if (now !== loud || (beat && now)) { loud = now; port.postMessage(now ? '$AUDIO_AUDIBLE' : '$AUDIO_SILENT'); }
    if (!recheck && (loud || (unsure && tries < $AUDIO_SOUND_TRIES))) {
      recheck = setT(function () { recheck = 0; if (!loud) tries++; hear(true); }, loud ? $AUDIO_RECHECK_MS : $AUDIO_SOUND_MS);
    }
  }
  function heard(e) {
    var m = evCurrent(e);
    tries = 0;
    if (evType(e) === 'playing' && e.isTrusted && slot(m) < 0) media[nm++] = m;
    hear();
  }
  if (onEl) {
    w.addEventListener('playing', function (e) {
      if (!e.isTrusted) return;
      var m = evTarget(e);
      try { if (!m || typeof paused(m) !== 'boolean') return; } catch (x) { return; }
      if (slot(m) < 0) {
        media[nm++] = m;
        for (var i = 0; i < AUDIO_EVENTS.length; i++) onEl(m, AUDIO_EVENTS[i], heard);
      }
      tries = 0;
      hear();
    }, true);
    w.addEventListener('pagehide', function () { if (loud) { loud = false; port.postMessage('$AUDIO_SILENT'); } }, true);
    var persisted = prop(proto(w.PageTransitionEvent), 'persisted');
    w.addEventListener('pageshow', function (e) { if (persisted(e)) hear(); }, true);
  }
  if (w.top !== w) return;
  var P = w.performance, pnow = P && P.now && P.now.bind(P), tsOf = prop(EvP, 'timeStamp');
  if (!(EvP && gopd(EvP, 'timeStamp'))) tsOf = null;
  var said = function (t) {
    w.addEventListener(t, function (e) {
      if (!e.isTrusted || !pnow || !tsOf) return;
      var age = round(pnow() - tsOf(e));
      port.postMessage('$TOP_DOCUMENT_INPUT ' + t + ' ' + (age > 0 ? age : 0));
    }, true);
  };
  said('pointerdown');
  said('keydown');
  said('click');
  var T = null, started = false, SYNC = /^$INPUT_SYNC ([0-9]{1,9}) ([0-9]{1,4})$/, ASK = /^probe ([0-9a-f]{1,64})$/, THEME_ASK = /^theme ([0-9a-f]{1,64})$/,
      GO = /^$PAGE_REISSUE_PREFIX([0-9a-f]{1,64}) (https?:\/\/\S+)$/i, SEL = 'a, button, [role="button"], [role="tab"], [role="link"]';
  var gcs = w.getComputedStyle, MO = w.MutationObserver,
      RO = w.ResizeObserver, IO = w.IntersectionObserver;
  var timer = 0, last = null, owed = false, mo = null, watched = null, ro = null, io = null, fullW = -1, fullH = 0, ctx = null,
      metaDirty = false;
  // What the probe, the start and the observers use once started (#146).
  var DP = proto(w.Document), EP = proto(w.Element), NP = proto(w.Node), RP = proto(w.DOMRect) || proto(w.DOMRectReadOnly),
      CSP = proto(w.CSSStyleDeclaration), MR = proto(w.MutationRecord);
  var dom = {
    add: un(w.addEventListener) || method(null, 'addEventListener'),
    docAdd: d.addEventListener ? un(d.addEventListener) || method(null, 'addEventListener') : null,
    html: prop(DP, 'documentElement'), body: prop(DP, 'body'), mode: prop(DP, 'compatMode'), scroller: prop(DP, 'scrollingElement'),
    at: un(d.elementFromPoint) || method(null, 'elementFromPoint'),
    innerW: prop(w, 'innerWidth'), innerH: prop(w, 'innerHeight'),
    cw: prop(EP, 'clientWidth'), ch: prop(EP, 'clientHeight'), sh: prop(EP, 'scrollHeight'),
    rect: method(EP, 'getBoundingClientRect'), has: method(EP, 'querySelector'),
    up: prop(NP, 'parentElement'), name: prop(NP, 'nodeName'),
    bottom: prop(RP, 'bottom'), height: prop(RP, 'height'), width: prop(RP, 'width'),
    css: CSP && un(CSP.getPropertyValue),
    moObserve: method(proto(MO), 'observe'),
    roObserve: method(proto(RO), 'observe'), roOff: method(proto(RO), 'disconnect'),
    ioObserve: method(proto(IO), 'observe'), ioOff: method(proto(IO), 'disconnect'),
    recType: prop(MR, 'type'), recTarget: prop(MR, 'target'), added: prop(MR, 'addedNodes'), removed: prop(MR, 'removedNodes'),
    count: prop(proto(w.NodeList), 'length'), data: prop(proto(w.MessageEvent), 'data')
  };
  // observe()'s options, built now and with no prototype: the platform
  // reads every option it knows, and one this object lacks would
  // otherwise be looked up on Object.prototype, where a page's getter
  // would see the call.
  // The attribute filter is a sequence, which the platform reads by
  // iterating: an array would be walked through Array.prototype's
  // iterator, which the page can replace. So it is its own iterable,
  // and its iterator is built now too: iterating calls nothing but
  // these closures (a fresh Object.create at observe() time would call
  // whatever the page has put there since).
  var moOpts = Object.create(null), FILTER = ['class', 'style', 'hidden', 'open', 'content', 'media', 'name'],
      ITER = typeof Symbol === 'function' ? Symbol.iterator : null;
  moOpts.childList = true; moOpts.subtree = true; moOpts.attributes = true;
  if (ITER) {
    var fi = 0, fit = Object.create(null);
    fit.next = function () { return fi < FILTER.length ? { value: FILTER[fi++], done: false } : { value: undefined, done: true }; };
    moOpts.attributeFilter = Object.create(null);
    moOpts.attributeFilter[ITER] = function () { fi = 0; return fit; };
  } else moOpts.attributeFilter = FILTER;
  function style(n, name, camel) { var s = gcs(n); return dom.css ? dom.css(s, name) : s[camel]; }
  // The theme-colour read's natives, saved before the page runs (#92).
  var tc = null, go = null;
  try {
    var C2 = proto(w.CanvasRenderingContext2D);
    tc = {
      qsa: un(d.querySelectorAll), mkEl: un(d.createElement),
      attr: method(EP, 'getAttribute'),
      len: dom.count,
      mm: un(w.matchMedia), mqMatches: prop(proto(w.MediaQueryList), 'matches'),
      getCtx: method(proto(w.HTMLCanvasElement), 'getContext'),
      getFS: prop(C2, 'fillStyle'), setFS: prop(C2, 'fillStyle', true),
      clear: method(C2, 'clearRect'), fill: method(C2, 'fillRect'), pixels: method(C2, 'getImageData'),
      data: prop(proto(w.ImageData), 'data'),
      exec: un(RegExp.prototype.exec), pInt: w.parseInt, pFloat: w.parseFloat, round: round
    };
    // The re-issue of the page's own navigation (#180): a detached
    // link, built and clicked through natives saved now, or not at all.
    var AP = proto(w.HTMLAnchorElement), HP = proto(w.HTMLElement);
    var setter = function (proto, n) { var x = proto && gopd(proto, n); return x ? un(x.set) : null; };
    go = { mk: tc.mkEl, href: setter(AP, 'href'), policy: setter(AP, 'referrerPolicy'),
           target: setter(AP, 'target'), click: HP ? un(HP.click) : null };
    if (!go.mk || !go.href || !go.policy || !go.target || !go.click) go = null;
  } catch (e) { tc = null; go = null; }
  // Without them there is no theme-colour read; the probe still runs.
  var live = !tc;
  if (live) tc = { exec: function (r, s) { return r.exec(s); }, pFloat: w.parseFloat, round: round };
  var RGBA = /^rgba?\(\s*([\d.]+)[\s,]+([\d.]+)[\s,]+([\d.]+)\s*(?:[,\/]\s*([\d.]+)(%?)\s*)?\)$/,
      HEX = /^#([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})$/i, CURRENT = /currentcolor/i;
  function rgb(r, g, b) { return 'rgb(' + r + ', ' + g + ', ' + b + ')'; }
  function paint(c) {
    var m = tc.exec(RGBA, c || '');
    if (!m) return null;
    if (m[4] !== undefined && tc.pFloat(m[4]) === 0) return null;
    return rgb(tc.round(+m[1]), tc.round(+m[2]), tc.round(+m[3]));
  }
  function norm(c) {
    if (!c || tc.exec(CURRENT, c)) return null;
    var p = paint(c);
    if (p) return p;
    try {
      if (!ctx) ctx = tc.getCtx(tc.mkEl(d, 'canvas'), '2d');
      tc.setFS(ctx, '#000'); tc.setFS(ctx, c); var a = tc.getFS(ctx);
      tc.setFS(ctx, '#fff'); tc.setFS(ctx, c); if (tc.getFS(ctx) !== a) return null;
      var h = tc.exec(HEX, a);
      if (h) return rgb(tc.pInt(h[1], 16), tc.pInt(h[2], 16), tc.pInt(h[3], 16));
      p = paint(a);
      if (p || tc.exec(RGBA, a)) return p;
      tc.clear(ctx, 0, 0, 1, 1); tc.fill(ctx, 0, 0, 1, 1);
      var px = tc.data(tc.pixels(ctx, 0, 0, 1, 1));
      return px[3] ? rgb(px[0], px[1], px[2]) : null;
    } catch (e) { return null; }
  }
  function themeColor() {
    if (live) return null;
    try {
      var ms = tc.qsa(d, 'meta[name="theme-color" i]'), n = tc.len(ms);
      for (var i = 0; i < n; i++) {
        var q = tc.attr(ms[i], 'media');
        if (q && !(tc.mm && tc.mqMatches(tc.mm(w, q)))) continue;
        var c = norm(tc.attr(ms[i], 'content'));
        if (c) return c;
      }
    } catch (e) {}
    return null;
  }
  function reportTheme() { port.postMessage('$THEME_COLOR_PREFIX' + T + ' ' + (themeColor() || 'none')); }
  function pinned(n, de) {
    for (; n && n !== de; n = dom.up(n)) {
      var p = style(n, 'position', 'position');
      if (p === 'fixed' || p === 'sticky') return true;
    }
    return false;
  }
  function probe() {
    var de = dom.html(d), b = dom.body(d);
    if (!de || !b) return null;
    var std = dom.mode(d) === 'CSS1Compat';
    var vw = (std && dom.cw(de)) || dom.innerW(w), vh = (std && dom.ch(de)) || dom.innerH(w);
    if (!vw || !vh) return null;
    if (vw !== fullW) { fullW = vw; fullH = vh; } else if (vh > fullH) fullH = vh;
    var nav = null, scrolls = dom.sh(dom.scroller(d) || de) > vh + 1;
    for (var n = dom.at(d, vw / 2, vh - 30); n && n !== b && n !== de; n = dom.up(n)) {
      var r = dom.rect(n), bottom = dom.bottom(r), height = dom.height(r);
      if (bottom >= vh - 60 && bottom <= vh + 20 && height >= 40 && height <= fullH * 0.25 &&
          dom.width(r) >= vw * 0.5 && dom.has(n, SEL) && !(scrolls && !pinned(n, de))) { nav = n; break; }
    }
    if (!nav) return { nav: null, color: null };
    var c = null;
    for (var m = nav; m && m !== b && m !== de && !c; m = dom.up(m)) c = paint(style(m, 'background-color', 'backgroundColor'));
    return { nav: nav, color: c || themeColor() || paint(style(b, 'background-color', 'backgroundColor')) ||
        paint(style(de, 'background-color', 'backgroundColor')) };
  }
  function watch(el) {
    if (el === watched) return;
    if (ro) dom.roOff(ro);
    if (io) dom.ioOff(io);
    ro = io = null; watched = el;
    if (!el) return;
    if (RO) { ro = new RO(soon); dom.roObserve(ro, el); }
    if (IO) { io = new IO(soon); dom.ioObserve(io, el); }
  }
  // Not JSON.stringify: it asks the object (and Object.prototype, which
  // the page can extend) for a `toJSON`. Every part is our own text: a
  // hex token and rgb().
  function report(has, color) {
    return '{"token":"' + T + '","hasBottomUI":' + (has ? 'true' : 'false') + ',"color":' + (color ? '"' + color + '"' : 'null') + '}';
  }
  function run(force) {
    timer = 0;
    if (metaDirty) { metaDirty = false; reportTheme(); }
    var p = null;
    try { p = probe(); } catch (e) {}
    if (!p) { if (force) owed = true; return; }
    watch(p.nav);
    var key = !!p.nav + ' ' + p.color;
    if (!force && !owed && key === last) return;
    last = key; owed = false;
    port.postMessage(report(!!p.nav, p.color));
  }
  function soon() { if (!timer) timer = setT(function () { run(false); }, $debounceMs); }
  function isMeta(n) { if (!n) return false; var t = dom.name(n); return t === 'META' || t === 'HEAD'; }
  function anyMeta(list) { for (var k = 0, n = list ? dom.count(list) : 0; k < n; k++) if (isMeta(list[k])) return true; return false; }
  function metaTouched(recs) {
    for (var i = 0; recs && i < recs.length; i++) {
      var r = recs[i];
      if (dom.recType(r) === 'attributes') { if (isMeta(dom.recTarget(r))) return true; continue; }
      if (anyMeta(dom.added(r)) || anyMeta(dom.removed(r))) return true;
    }
    return false;
  }
  function attach() {
    var de = dom.html(d);
    if (mo || !MO || !de) return;
    mo = new MO(function (recs) { if (metaTouched(recs)) metaDirty = true; soon(); });
    dom.moObserve(mo, de, moOpts);
  }
  function start() {
    started = true;
    dom.add(w, 'resize', soon);
    if (dom.docAdd) dom.docAdd(d, 'readystatechange', function () { attach(); soon(); });
    attach();
  }
  port.addEventListener('message', function (e) {
    // The platform hands the channel's listeners a plain object with its
    // own `data`, not a MessageEvent; read that as it is (an own property
    // runs nothing of the page's), and a real event through the getter.
    var data = null;
    try { var own = e ? gopd(e, 'data') : null; data = own ? own.value : e ? dom.data(e) : null; } catch (x) {}
    if (typeof data !== 'string') return;
    var y = tc.exec(SYNC, data);
    if (y) {
      // A task later, input the renderer already had has run (#348);
      // then wait out a tap Chromium holds back for a double tap, so its
      // click (queued input, ahead of a timer) has run too.
      var id = y[1], settle = +y[2];
      setT(function () { setT(function () { port.postMessage('$INPUT_SYNCED ' + id); }, settle); }, 0);
      return;
    }
    var g = go ? tc.exec(GO, data) : null;
    if (g) {
      if (started && g[1] === T) {
        var a = go.mk(d, 'a');
        go.href(a, g[2]); go.policy(a, '$REISSUE_REFERRER_POLICY'); go.target(a, '_self'); go.click(a);
      }
      return;
    }
    var m = tc.exec(ASK, data);
    if (!m) {
      var t = tc.exec(THEME_ASK, data);
      if (t && started && t[1] === T) reportTheme();
      return;
    }
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
