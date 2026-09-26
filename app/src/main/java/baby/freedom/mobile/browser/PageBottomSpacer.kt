package baby.freedom.mobile.browser

import kotlin.math.ceil

// Interim mitigation for the bottom bar covering the end of every page
// (#65 — this does not fix it, see below).
//
// The floating capsule sits over the last band of the content area: its
// slot, [CapsuleBottomMargin] under it and the navigation inset (~58 dp
// at rest on gesture navigation). A WebView's scroll extent ends where
// the document ends and it ignores `View` padding (see the note in
// [BrowserScreen]), so that band can never be scrolled out from under
// the bar. What *does* move the extent is the document itself: give it
// real extra height at its end and the footer scrolls clear of the bar.
//
// So every ordinary document gets one generated block after all of its
// own content — `html::after`, not `body` padding:
//
//  - Additive. `padding-bottom` on `html`/`body` would *replace* the
//    site's own value; a pseudo-element after `<body>` sits inside
//    `<html>`'s content box, so the site's body margin/padding and its
//    html padding all stay and the spacer lands after them.
//  - Outside the site's layout. `body` is routinely a flex or grid
//    container, and a `body::after` becomes a flex/grid item there —
//    shrinkable, or auto-placed into an empty cell beside the footer
//    where it adds no height at all. `<html>` is essentially never laid
//    out that way, and `all: initial` below strips anything a site's own
//    `html::after` rule might put on it.
//
// Static per document. The height is the bar's *resting* footprint plus
// the navigation inset — `capsuleOverlap` in [BrowserScreen] without the
// keyboard term — computed once when the style is (re)applied and never
// tracked through the compact/edit morphs: restyling the page on every
// frame of those is exactly the per-frame WebView work #63 forbids.
// While the keyboard is up the WebView is already shrunk clear of the
// bar (`contentBottomReserve`), so the spacer is not needed then — but
// it is left in place rather than removed: taking ~58 px off the end of
// the document while the user is typing into a field near it would
// clamp the scroll offset and jump the field, and the extra room at the
// end costs nothing (it only lets a bottom field scroll a little higher
// above the keyboard).
//
// Where it does nothing — #66's territory (reserved mode): elements that
// are `position: fixed; bottom: 0`, app shells with `overflow: hidden`
// on `html`/`body` (skipped outright, see [bottomSpacerJs]), pages that
// scroll an inner container, and `100vh` layouts.

/** `id` of the injected `<style>`; one per document. */
internal const val BOTTOM_SPACER_STYLE_ID = "freedom-bottom-spacer"

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
 * The script that (re)applies the spacer to the document on screen.
 * Idempotent: the `<style>` is looked up by [BOTTOM_SPACER_STYLE_ID]
 * and created only if it is missing, so running it at first paint, at
 * load finish and on every history update (SPA `pushState`) leaves
 * exactly one. Nothing is left running afterwards — no timer, no
 * observer.
 *
 * `spacerDp` is converted to CSS px here, in the page: at default zoom
 * one CSS px is one dp, but a page without a mobile viewport is laid
 * out at its desktop width (980 px) and shown zoomed out, so the same
 * dp takes proportionally more CSS px. The factor is the layout width
 * over the device width, never below 1 — fixed when the style is
 * written, not tracked through pinch-zoom (zooming in only makes the
 * spacer generously tall).
 *
 * Skipped — and removed if an earlier pass added it — while `<html>` or
 * `<body>` hides vertical overflow. That is an app shell whose document
 * doesn't scroll by design; a spacer there would give it a hidden,
 * programmatic-only scroll range that a focus-scroll could push the
 * whole app up by.
 *
 * Returns the spacer's height in CSS px, `0` when the document has none,
 * or `-1` when it could not tell (no `<body>` yet — a later pass will
 * decide). Deliberately total, like [ROOT_PAN_STYLES_JS].
 */
internal fun bottomSpacerJs(spacerDp: Int): String = """
(function () {
  try {
    var h = document.documentElement;
    var b = document.body;
    if (!h || !b) return -1;
    var el = document.getElementById('$BOTTOM_SPACER_STYLE_ID');
    var hidden = /hidden|clip/;
    if (hidden.test(getComputedStyle(h).overflowY) ||
        hidden.test(getComputedStyle(b).overflowY)) {
      if (el) el.remove();
      return 0;
    }
    if (el) return parseInt(el.getAttribute('data-px'), 10) || 0;
    var k = Math.max(1, h.clientWidth / (screen.width || h.clientWidth));
    var px = Math.ceil($spacerDp * k);
    el = document.createElement('style');
    el.id = '$BOTTOM_SPACER_STYLE_ID';
    el.setAttribute('data-px', String(px));
    el.textContent = 'html::after{all:initial!important;content:""!important;' +
      'display:block!important;clear:both!important;height:' + px + 'px!important}';
    (document.head || h).appendChild(el);
    return px;
  } catch (e) {
    return -1;
  }
})();
"""

/**
 * Parse [bottomSpacerJs]'s result: the spacer height in CSS px, or
 * `null` when the answer says nothing (no body yet, a frame that
 * couldn't run it) — the caller keeps what it knew.
 */
internal fun parseBottomSpacerResult(jsResult: String?): Int? =
    jsResult?.trim()?.toIntOrNull()?.takeIf { it >= 0 }

/**
 * The current document's spacer height, kept the way [RootPanProbeSlot]
 * keeps its probe: every new document mints a token and starts at 0, and
 * an answer is accepted only while the document that gave it is still
 * the one on screen. Single-threaded — WebViewClient callbacks and
 * `evaluateJavascript` results all arrive on the UI thread.
 */
internal class BottomSpacerSlot {
    private var generation = 0

    /** Spacer height in CSS px on the document on screen; 0 if none. */
    var spacerCssPx: Int = 0
        private set

    fun startDocument() {
        generation++
        spacerCssPx = 0
    }

    fun beginApply(): Int = generation

    fun accept(token: Int, cssPx: Int?) {
        if (token != generation || cssPx == null) return
        spacerCssPx = cssPx
    }
}

/**
 * The `documentScrollsDown` input to [pullToRefreshArmed], with the
 * spacer discounted.
 *
 * Pull-to-refresh arms only on documents that scroll (#56): a page one
 * viewport tall — a map, a canvas — keeps its downward drags. The spacer
 * makes every such short page scroll by its own height, which would
 * flip that answer and hand the drags back to the spinner. So the
 * question becomes "would the document scroll *without* the spacer?":
 * its content height less the spacer, at the current scale, against the
 * WebView's height, with [SPACER_DISCOUNT_SLACK_CSS_PX] of slack.
 *
 * @param canScrollDown `webView.canScrollVertically(1)`.
 * @param contentHeightCss `webView.contentHeight`, CSS px.
 * @param spacerCss [BottomSpacerSlot.spacerCssPx].
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
 * height, and a `100vh` body is itself fractional (862.5 CSS px on the
 * freedom AVD): a one-viewport flex page with the spacer read 946 where
 * the arithmetic says 944.5. A few CSS px of slack keeps such a page
 * reading as "does not scroll", as it did before the spacer; a document
 * whose real overflow is smaller than this is not one anybody scrolls.
 */
internal const val SPACER_DISCOUNT_SLACK_CSS_PX = 4
