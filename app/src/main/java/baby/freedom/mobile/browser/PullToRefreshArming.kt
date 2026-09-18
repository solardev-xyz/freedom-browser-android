package baby.freedom.mobile.browser

// When a downward drag belongs to pull-to-refresh, and when it belongs
// to the page (#56).
//
// Every tab's WebView sits inside a `SwipeRefreshLayout`, and that
// layout decides whether to take a downward drag by asking one
// question — `canChildScrollUp()`. It asks it on the ACTION_DOWN of the
// gesture and answers it for the whole gesture: if the layout does not
// claim the drag at touch-down it never claims it at all, and if it
// does claim it, the page never sees another event of that stream. The
// decision therefore has to be *synchronous* and it has to be right the
// first time — there is no "let the page have a go and take it back",
// and an answer that arrives a few milliseconds later (an
// `evaluateJavascript` round trip, say) arrives for the gesture after
// this one.
//
// The original gate was `webView.scrollY > 0`, i.e. "arm whenever the
// document is at the top". A full-screen map (freemap.eth) is at the
// top permanently — its document is exactly one viewport tall and it
// pans its own tiles in response to pointer events — so every downward
// drag on it was swallowed by the refresh spinner before the page's
// handlers ran.
//
// What this file adds is the two things the gate can know at touch-down
// without asking the renderer anything:
//
//  1. Is the document actually scrollable downwards? `View`'s scroll
//     range comes from the compositor and is free to read. A document
//     no taller than its viewport has no overscroll to pull on, so the
//     drag is the page's — that alone is the freemap.eth fix, and it
//     covers every self-handling full-viewport page (maps, canvases,
//     games, carousels) whatever pointer API it uses.
//  2. Has the document opted out of vertical panning at its root?
//     `touch-action` / `overscroll-behavior-y` on `<html>` / `<body>`
//     is what a page uses to say "the browser's pull gestures are
//     mine"; Chrome honours it for its own pull-to-refresh and so do
//     we. Those are document-level styles, so they can be probed once
//     per page (see `ROOT_PAN_STYLES_JS`) and read synchronously at
//     touch-down — no per-touch JS hit-test, no race.
//
// A long article still pulls to refresh from the top: it is scrollable
// and its root leaves panning alone.

/**
 * `touch-action` tokens that leave a vertical drag to the browser.
 *
 * `touch-action` is a blocklist by omission — the browser keeps the
 * pan directions the value names (plus everything, for `auto` /
 * `manipulation`). So the page has claimed the vertical drag exactly
 * when none of these appear.
 */
private val VERTICAL_PAN_TOKENS = setOf("auto", "manipulation", "pan-y", "pan-up", "pan-down")

/**
 * `overscroll-behavior-y` values that stop the overscroll at the
 * document instead of letting it become a browser gesture. Both mean
 * "no pull-to-refresh" in Chrome; `contain` additionally keeps
 * scroll chaining out, which is not our business here.
 */
private val OVERSCROLL_CONTAINED = setOf("contain", "none")

/**
 * Probe for the root element's pan-related computed styles, evaluated
 * once per document (see `buildRefreshableWebView`).
 *
 * Returns `"<html touch-action>|<html overscroll-behavior-y>|<body …>|<body …>"`.
 * `<body>` is reported separately because that is where pages
 * overwhelmingly set both (`body { touch-action: none }`), and because
 * the viewport propagation rules for `overscroll-behavior` take the
 * value from whichever of the two sets it.
 *
 * Deliberately total: any failure (no body yet, a document that denies
 * us `getComputedStyle`) returns the empty string, which
 * [rootBlocksVerticalPan] reads as "nothing claimed" — the page keeps
 * whatever the scroll-range half of the decision gives it, and
 * pull-to-refresh behaves as it did before this probe existed.
 */
internal const val ROOT_PAN_STYLES_JS = """
(function () {
  try {
    var h = document.documentElement;
    var b = document.body;
    var hs = h ? getComputedStyle(h) : null;
    var bs = b ? getComputedStyle(b) : null;
    return [
      hs ? hs.touchAction : '',
      hs ? hs.overscrollBehaviorY : '',
      bs ? bs.touchAction : '',
      bs ? bs.overscrollBehaviorY : ''
    ].join('|');
  } catch (e) {
    return '';
  }
})();
"""

/**
 * Has the document claimed vertical drags for itself?
 *
 * [jsResult] is the raw `evaluateJavascript` value of
 * [ROOT_PAN_STYLES_JS] — a JSON string, so quoted, and `"null"` for a
 * frame that could not run it at all. Anything we can't read is "no
 * claim": this half of the decision only ever *removes* pull-to-refresh
 * from a page that asked us to, it never adds it.
 */
internal fun rootBlocksVerticalPan(jsResult: String?): Boolean {
    val parts = unquoteJsString(jsResult)?.split('|') ?: return false
    if (parts.size < 4) return false
    return blocksVerticalPan(touchAction = parts[0], overscrollBehaviorY = parts[1]) ||
        blocksVerticalPan(touchAction = parts[2], overscrollBehaviorY = parts[3])
}

/**
 * The per-element half of [rootBlocksVerticalPan]: does this pair of
 * computed values say "the vertical drag is mine"?
 *
 * An empty (missing) `touch-action` is not a claim — an element we
 * could not read tells us nothing.
 */
internal fun blocksVerticalPan(touchAction: String, overscrollBehaviorY: String): Boolean {
    val action = touchAction.trim().lowercase()
    val claimsTouch = action.isNotEmpty() &&
        action.split(' ').none { it in VERTICAL_PAN_TOKENS }
    val claimsOverscroll = overscrollBehaviorY.trim().lowercase() in OVERSCROLL_CONTAINED
    return claimsTouch || claimsOverscroll
}

/**
 * Should the `SwipeRefreshLayout` take the downward drag that is
 * starting now?
 *
 * @param scrollY the WebView's vertical scroll offset in px. Only the
 *   very top of the document pulls to refresh, as before; `<= 0`
 *   rather than `== 0` because an overscrolled WebView can report a
 *   negative offset.
 * @param documentScrollsDown `webView.canScrollVertically(1)` — the
 *   document has content below the viewport, so it is a scrollable
 *   document sitting at its top rather than a one-viewport page that
 *   never scrolls at all.
 * @param rootBlocksVerticalPan the last [rootBlocksVerticalPan] probe
 *   for the document on screen.
 */
internal fun pullToRefreshArmed(
    scrollY: Int,
    documentScrollsDown: Boolean,
    rootBlocksVerticalPan: Boolean,
): Boolean = scrollY <= 0 && documentScrollsDown && !rootBlocksVerticalPan

/**
 * Unwrap the JSON value `evaluateJavascript` hands back: `"…"` for a
 * string, the literal `null` for a frame that produced nothing.
 */
private fun unquoteJsString(raw: String?): String? {
    val value = raw?.trim() ?: return null
    if (value.isEmpty() || value == "null") return null
    if (value.length < 2 || !value.startsWith('"') || !value.endsWith('"')) return null
    return value.substring(1, value.length - 1)
}
