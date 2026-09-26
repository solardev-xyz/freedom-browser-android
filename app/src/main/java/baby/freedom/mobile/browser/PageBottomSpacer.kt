package baby.freedom.mobile.browser

import kotlin.math.ceil

// Interim mitigation for the bottom bar covering the end of every page
// (#65 — this does not fix it; #66 is the real fix).
//
// The floating capsule sits over the last band of the content area, and
// a WebView's scroll extent ends where the document ends (it ignores
// `View` padding, see [BrowserScreen]). So each ordinary document gets
// one block of extra height at its end — `html::after`, in a constructed
// stylesheet (CSP-safe: CSSOM-built sheets are not subject to
// `style-src`, and nothing is reported) — and the footer can scroll
// clear of the bar.
//
// Nothing here predicts layout. A *decision pass* inserts the rule and
// measures the document's scroll extent before and after: if it grew by
// the spacer's height the rule stays, otherwise it is removed on the
// spot and the page is exactly as without it. Flex/grid or full-height
// bodies, floats, quirks mode, inner scrollers, positioned footers:
// either the measurement shows the spacer worked, or the page is left
// alone. `position: fixed` bottom elements, layouts where the spacer
// adds no scroll range and scroll locks that never lift behave as
// before (#66's territory).
//
// A kept spacer is left alone — the page is never restyled again for
// it, so nothing jumps and touch-downs run no script (see
// [BottomSpacerSlot]). It is re-made only on a width change (rotation:
// the navigation inset and a desktop-width page's zoom both move), or
// when an SPA route change has dropped our sheet. A rejected document
// is decided again if it grows (a client-rendered page whose first
// paint was a one-screen shell).

/**
 * How tall the spacer is, in dp: the capsule's resting slot, the margin
 * under it, and the navigation inset the content draws behind — the
 * resting value of `capsuleOverlap` in [BrowserScreen]. Rounded up so a
 * fractional inset never leaves a sliver of the footer under the bar.
 *
 * @param navInsetPx the window's bottom system-bars inset, in px.
 * @param density px per dp.
 */
internal fun bottomSpacerDp(navInsetPx: Int, density: Float): Int {
    val navDp = if (density > 0f) navInsetPx.coerceAtLeast(0) / density else 0f
    return ceil(CapsuleHeight.value + CapsuleBottomMargin.value + navDp).toInt()
}

/**
 * Does this document get the spacer? Everything the WebView shows except
 * our own home sentinel: `about:blank` sits under the native Home
 * overlay, which pads itself (`bottomContentPadding`).
 */
internal fun bottomSpacerApplies(url: String?): Boolean =
    !url.isNullOrEmpty() && url != ABOUT_BLANK

/**
 * How our sheet is recognised in `document.adoptedStyleSheets`: a
 * non-enumerable `true` under this name on the sheet object. Deliberately
 * generic — nothing a page can read back names the browser.
 */
internal const val BOTTOM_SPACER_MARK = "endSpacer"

/**
 * One decision pass (see the file comment):
 *
 *  - `-1` — pending: `<html>`/`<body>` hides vertical overflow (a scroll
 *    lock: consent banner, lightbox, app shell) or there is no `<body>`
 *    yet. Nothing is inserted *or removed*: a sheet of ours from an
 *    earlier decision stays (a rotation under a modal keeps the spacer
 *    at its old height rather than dropping it; see [BottomSpacerSlot]).
 *
 * Otherwise a sheet of ours from an earlier decision is removed first
 * (rotation, a retry), and:
 *
 *  - `0` — rejected: the page styles `html::after` itself (a CSS-to-JS
 *    breakpoint channel, say — ours would override it), so nothing is
 *    inserted; or the rule did not grow the scroll extent by (nearly)
 *    its height, so it was removed again in the same task, before any
 *    paint.
 *  - `n > 0` — kept: the rule stays, and `n` is the *measured* growth of
 *    `scrollingElement.scrollHeight`, in CSS px.
 *
 * The height is `spacerDp` scaled by a desktop-width page's zoom factor
 * (a 980 px layout on a 412 px screen needs proportionally more CSS px).
 */
internal fun bottomSpacerDecisionJs(spacerDp: Int): String = """
(function () {
  var d = document, sheet = null;
  var mine = function (s) { return !!s && s.$BOTTOM_SPACER_MARK === true; };
  var drop = function (f) { d.adoptedStyleSheets = d.adoptedStyleSheets.filter(function (s) { return !f(s); }); };
  try {
    var h = d.documentElement, b = d.body;
    if (!h || !b) return -1;
    if (!('adoptedStyleSheets' in d) || typeof CSSStyleSheet !== 'function') return 0;
    var hidden = /hidden|clip/;
    if (hidden.test(getComputedStyle(h).overflowY) || hidden.test(getComputedStyle(b).overflowY)) return -1;
    if (d.adoptedStyleSheets.some(mine)) drop(mine);
    var own = getComputedStyle(h, '::after').content;
    if (own && own !== 'none' && own !== 'normal') return 0;
    var se = d.scrollingElement || h;
    var px = Math.ceil($spacerDp * Math.max(1, h.clientWidth / (screen.width || h.clientWidth)));
    var before = se.scrollHeight;
    sheet = new CSSStyleSheet();
    sheet.replaceSync('html::after{all:initial!important;content:""!important;' +
      'display:block!important;clear:both!important;height:' + px + 'px!important}');
    Object.defineProperty(sheet, '$BOTTOM_SPACER_MARK', { value: true });
    d.adoptedStyleSheets = d.adoptedStyleSheets.concat([sheet]);
    var grew = se.scrollHeight - before;
    if (grew >= px - 1) return grew;
    drop(function (s) { return s === sheet; });
    return 0;
  } catch (e) {
    try { if (sheet) drop(function (s) { return s === sheet; }); } catch (e2) {}
    return 0;
  }
})();
"""

/**
 * Is our sheet still adopted? `1` yes, `0` no — asked after an SPA
 * history change, which may have reset `adoptedStyleSheets`. Read-only.
 */
internal val BOTTOM_SPACER_PRESENT_JS = """
(function () {
  try {
    var l = document.adoptedStyleSheets || [];
    for (var i = 0; i < l.length; i++) if (l[i] && l[i].$BOTTOM_SPACER_MARK === true) return 1;
    return 0;
  } catch (e) {
    return 1;
  }
})();
"""

/** [bottomSpacerDecisionJs]'s answer: `null` pending, else CSS px (0 = rejected). */
internal fun parseBottomSpacerResult(jsResult: String?): Int? =
    jsResult?.trim()?.toIntOrNull()?.takeIf { it >= 0 }

/** Touch-downs per document that may run a decision pass (see [BottomSpacerSlot]). */
internal const val SPACER_MAX_TOUCH_ATTEMPTS = 8

/** Where a document stands with the spacer. */
internal sealed interface SpacerState {
    /** Not decided yet: locked, or no body. */
    data object Pending : SpacerState

    /**
     * On the page, grown by a measured [cssPx]. [stale]: the width
     * changed while a scroll lock was up, so the sheet stayed at its old
     * height and is re-decided once the lock lifts.
     */
    data class Kept(val cssPx: Int, val stale: Boolean = false) : SpacerState

    /**
     * Tried and removed: the page is as without it. [atContentHeightCss]
     * is the document's height then; a document that has grown since
     * (a client-rendered page that painted a one-screen shell first) is
     * decided again.
     */
    data class Rejected(val atContentHeightCss: Int) : SpacerState
}

/**
 * The spacer's per-document state, and the one place that decides when a
 * script runs at all. Answers are stamped with the document and the
 * request they belong to, so a pass that outlives its document (or is
 * superseded by a rotation) cannot speak for what replaced it.
 * Single-threaded: WebView callbacks and `evaluateJavascript` results all
 * arrive on the UI thread.
 *
 * A kept spacer is final: touch-downs run no script for it. Pending and
 * rejected documents are retried on load finish and on touch-down — a
 * rejected one only once `WebView.getContentHeight()` (read natively, no
 * script) shows it has grown — with touch-downs capped at
 * [SPACER_MAX_TOUCH_ATTEMPTS] per document.
 */
internal class BottomSpacerSlot {
    /** Identifies one decision request; see [accept]. */
    data class Token(val document: Int, val request: Int)

    private var document = 0
    private var request = 0
    private var inFlight: Token? = null

    var state: SpacerState = SpacerState.Pending
        private set

    /** Touch-downs this document has spent on decision passes. */
    var touchAttempts = 0
        private set

    /** What the pull-to-refresh gate discounts: the measured growth, or 0. */
    val discountCssPx: Int get() = (state as? SpacerState.Kept)?.cssPx ?: 0

    fun startDocument() {
        document++
        inFlight = null
        touchAttempts = 0
        state = SpacerState.Pending
    }

    /** Is a pass worth running for a document now [contentHeightCss] tall? */
    private fun undecided(contentHeightCss: Int): Boolean = when (val s = state) {
        SpacerState.Pending -> true
        is SpacerState.Kept -> s.stale
        is SpacerState.Rejected -> contentHeightCss > s.atContentHeightCss
    }

    /** First paint / load finished: decide if this document is still undecided. */
    fun decideOnLoad(contentHeightCss: Int): Token? =
        if (inFlight == null && undecided(contentHeightCss)) begin() else null

    /** Touch-down: retry an undecided document, at most [SPACER_MAX_TOUCH_ATTEMPTS] times. */
    fun decideOnTouch(contentHeightCss: Int): Token? {
        if (inFlight != null || touchAttempts >= SPACER_MAX_TOUCH_ATTEMPTS) return null
        if (!undecided(contentHeightCss)) return null
        touchAttempts++
        return begin()
    }

    /**
     * Width change (rotation): always a fresh decision, superseding any in
     * flight. A kept spacer counts as kept until the pass answers — under
     * a scroll lock the pass leaves it in place, marked [SpacerState.Kept.stale].
     */
    fun decideOnWidthChange(): Token {
        state = when (val s = state) {
            is SpacerState.Kept -> s.copy(stale = true)
            else -> SpacerState.Pending
        }
        touchAttempts = 0
        return begin()
    }

    /** SPA history change: only a kept spacer is worth checking for. */
    fun checkOnHistoryChange(): Token? =
        if (state is SpacerState.Kept && inFlight == null) Token(document, request) else null

    /**
     * The history check's answer. Returns a new decision token when the
     * sheet has gone (the SPA reset `adoptedStyleSheets`), else null.
     */
    fun acceptPresence(token: Token, present: Boolean): Token? {
        if (token.document != document || present || state !is SpacerState.Kept || inFlight != null) return null
        state = SpacerState.Pending
        return begin()
    }

    /**
     * A decision's answer, per [parseBottomSpacerResult]; [contentHeightCss]
     * is `WebView.getContentHeight()` when it arrived.
     */
    fun accept(token: Token, cssPx: Int?, contentHeightCss: Int) {
        if (token != inFlight) return
        inFlight = null
        state = when {
            cssPx == null -> state // pending: a stale kept sheet is still on the page
            cssPx > 0 -> SpacerState.Kept(cssPx)
            else -> SpacerState.Rejected(contentHeightCss)
        }
    }

    private fun begin(): Token = Token(document, ++request).also { inFlight = it }
}

/**
 * The `documentScrollsDown` input to [pullToRefreshArmed], with the
 * spacer discounted.
 *
 * Pull-to-refresh arms only on documents that scroll (#56): a page one
 * viewport tall — a map, a canvas — keeps its downward drags. A kept
 * spacer can make such a page scroll by its own height, so the question
 * becomes "would the document scroll *without* the spacer?": its content
 * height less the measured growth, at the current scale, against the
 * WebView's height, with [SPACER_DISCOUNT_SLACK_CSS_PX] of slack.
 *
 * @param canScrollDown `webView.canScrollVertically(1)`.
 * @param contentHeightCss `webView.contentHeight`, CSS px.
 * @param spacerCss [BottomSpacerSlot.discountCssPx].
 * @param viewHeightPx `webView.height`.
 * @param scale `webView.scale` — px per CSS px at the current zoom.
 */
internal fun documentScrollsPastSpacer(
    canScrollDown: Boolean,
    contentHeightCss: Int,
    spacerCss: Int,
    viewHeightPx: Int,
    scale: Float,
): Boolean {
    if (!canScrollDown) return false
    if (spacerCss <= 0 || scale <= 0f) return true
    return (contentHeightCss - spacerCss - SPACER_DISCOUNT_SLACK_CSS_PX) * scale > viewHeightPx
}

/**
 * `getContentHeight()` is an integer rounded up from a fractional layout
 * height, so a few CSS px of slack keep a page one viewport tall reading
 * as "does not scroll", as it did before the spacer; a document whose
 * real overflow is smaller than this is not one anybody scrolls.
 */
internal const val SPACER_DISCOUNT_SLACK_CSS_PX = 4
