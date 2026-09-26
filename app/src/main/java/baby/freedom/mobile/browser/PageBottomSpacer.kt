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
//  - Except when the body's own content overflows it. With
//    `html, body { height: 100% }` (or a `100vh` body) the body box is
//    one viewport tall and the page runs on past it as overflow;
//    `html::after` then lands right under the body *box*, inside that
//    overflow, and adds no scroll range. There — and only for a
//    block-level body, where the pseudo-element is plain flow after the
//    last child — the rule moves to `body::after`, which sits after the
//    overflowing content. Only overflow that runs into `html::after`'s
//    band counts, and only in-flow overflow — where the flow puts the
//    body's last child, not `body.scrollHeight`: uncleared floats, and a
//    last child pushed down by `position: relative` or a transform, also
//    overflow an auto-height body, but `body::after` follows none of
//    them (`html::after`'s `clear` already puts it below the floats),
//    and moving such a page to `body::after` would only flip it back on
//    the next pass. A page that
//    grows past (or shrinks back inside) its body box moves the spacer
//    with it; one that does neither is never rewritten.
//
// Never per frame. The height is the bar's *resting* footprint plus the
// navigation inset — `capsuleOverlap` in [BrowserScreen] without the
// keyboard term — recomputed on each (re)apply pass (load, history
// update, width change, touch-down) but never tracked through the
// compact/edit morphs: restyling the page on every frame of those is
// exactly the per-frame WebView work #63 forbids.
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
// scroll an inner container, flex/grid bodies whose content overflows
// the body box, and pages whose end is set by positioned, relatively
// offset or transformed content. The script reports `0` for all of
// these (see "Does it end the document?" in [bottomSpacerJs]).
//
// One inner scroller does get it, as a side effect of the full-height
// case above: `html, body { height: 100%; overflow-x: hidden }` makes
// `<body>` itself the scroll container (its `overflow-y` computes to
// `auto`), its content overflows its box, and `body::after` lands at the
// end of that overflow — i.e. at the end of the body's own scroll range,
// which is where the footer is. The script reports the spacer there,
// and the report is right whichever scroller the WebView takes as its
// own: if the body is promoted to root scroller the discount removes
// exactly the range the spacer added, and if not the document does not
// scroll at all and pull-to-refresh is not armed anyway.
//
// Quirks mode (no doctype) is handled like standards mode: there
// `<body>` is `document.scrollingElement` and reports the viewport's
// scroll extent, which is compared the same way; it never gets
// `body::after`.

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
 * Idempotent: the rule lives in one constructed stylesheet adopted by
 * the document (`document.adoptedStyleSheets`), found again on each
 * pass by its shape — a one-rule sheet, `html::after` or `body::after`,
 * `clear: both` and an `!important` height, which is also where the
 * current height is read back from. No marker of our own: anything a
 * page can read in `adoptedStyleSheets` that names this browser would
 * be a fingerprint. So running it at first paint, at load
 * finish, on every history update (SPA `pushState`), on a width change
 * and on every touch-down leaves exactly one. Nothing is left running
 * afterwards — no timer, no observer.
 *
 * A constructed sheet, not a `<style>` element: `evaluateJavascript`
 * runs in the page's own world, so an inserted `<style>` is inline
 * style under the page's CSP — a `style-src` without `'unsafe-inline'`
 * blocks it (the footer stays under the bar while the script reported
 * a spacer, skewing the pull-to-refresh gate) and every insert or
 * rewrite sends the site a violation report, a fingerprint of this
 * browser. CSSOM-built sheets are not subject to `style-src`, so the
 * spacer applies on such pages too and reports nothing. The answer is
 * still checked against the computed `::after` after every write, and
 * a rule that did not take is withdrawn and reported as `0`, so the
 * height handed back is always the one actually on the page.
 *
 * Recomputed on every run, not frozen at insertion: `configChanges`
 * keeps the document across a rotation, and both inputs move with it —
 * the navigation inset (3-button nav: 0 in landscape, 48 dp in
 * portrait) and a desktop-width page's zoom factor. A run whose height
 * differs from the one on the page rewrites the rule; one that matches
 * touches nothing, so the touch-down pass is a handful of style and
 * geometry reads — no write, and no layout of its own.
 *
 * Does it end the document? A spacer that is on the page but not at
 * its end is worth nothing: the footer stays under the bar, and
 * discounting its height from the pull-to-refresh gate would subtract
 * range the page never got. So every pass checks that the document's
 * scroll extent ends where the spacer does — for `html::after`, the
 * bottom of `<html>`'s content box, which is the spacer's own bottom
 * and so already past a trailing margin collapsed through `<body>` (a
 * last `<p>`) and past cleared floats; for `body::after`, the body's
 * overflow including it — plus `<html>`'s own bottom padding, border
 * and margin; or the viewport, on a short page.
 * When something else reaches further — a flex/grid body's overflow,
 * an absolutely positioned element — it reports `0`. The rule is left
 * in place (it is inert there, and dropping it only to put it back on
 * the next pass would churn the page's styles on every touch-down).
 *
 * Scroll locks. While `<html>` or `<body>` hides vertical overflow the
 * document does not scroll — either an app shell, by design, or a page
 * scroll-locked for a moment (a consent banner at load, a lightbox):
 *
 *  - Not inserted while locked. A spacer on an app shell is a hidden,
 *    programmatic-only scroll range that a focus-scroll could push the
 *    whole app up by. A lock that lifts later (the consent banner is
 *    accepted) is picked up by the next pass — at the latest the next
 *    touch-down, i.e. before the user can scroll to the footer.
 *  - Removed on a lock only when that cannot move the page: if the
 *    viewport reaches into the spacer's band (the user is at the end
 *    and opened a lightbox), dropping it would clamp `scrollY` behind
 *    the modal and leave the page ~a bar higher when it closes, so it
 *    stays until a later pass finds the page unlocked again.
 *
 * Returns the spacer's height in CSS px, `0` when the document has none
 * (or has one that does not end it),
 * or `-1` when it could not tell (no `<body>` yet — a later pass will
 * decide). Deliberately total, like [ROOT_PAN_STYLES_JS].
 */
internal fun bottomSpacerJs(spacerDp: Int): String = """
(function () {
  try {
    var h = document.documentElement;
    var b = document.body;
    if (!h || !b) return -1;
    if (!('adoptedStyleSheets' in document) || typeof CSSStyleSheet !== 'function') return 0;
    var sheet = null, cur = 0, list = document.adoptedStyleSheets, i;
    for (i = 0; i < list.length; i++) {
      var rs = list[i].cssRules, r = rs && rs.length === 1 && rs[0], st = r && r.style;
      var v = st && /^(html|body)::after$/.test(r.selectorText || '') &&
        st.getPropertyValue('clear') === 'both' && st.getPropertyPriority('height') === 'important'
        ? parseInt(st.getPropertyValue('height'), 10) : NaN;
      if (v > 0) { sheet = list[i]; cur = v; break; }
    }
    var drop = function () {
      var keep = [], all = document.adoptedStyleSheets, j;
      for (j = 0; j < all.length; j++) if (all[j] !== sheet) keep.push(all[j]);
      document.adoptedStyleSheets = keep;
      return 0;
    };
    var se = document.scrollingElement || h;
    var hs = getComputedStyle(h), bs = getComputedStyle(b);
    var hidden = /hidden|clip/;
    if (hidden.test(hs.overflowY) || hidden.test(bs.overflowY)) {
      if (!sheet) return 0;
      var bottom = (window.scrollY || se.scrollTop || 0) +
        Math.max(window.innerHeight || 0, se.clientHeight || 0);
      if (bottom > se.scrollHeight - cur) return cur;
      return drop();
    }
    var onBody = !!sheet && /^body/.test(sheet.cssRules[0].selectorText || '');
    var k = Math.max(1, h.clientWidth / (screen.width || h.clientWidth));
    var px = Math.ceil($spacerDp * k);
    var put = function (toBody) {
      if (!sheet) {
        sheet = new CSSStyleSheet();
        document.adoptedStyleSheets = document.adoptedStyleSheets.concat([sheet]);
      }
      onBody = toBody;
      sheet.replaceSync((onBody ? 'body' : 'html') + '::after{' +
        'all:initial!important;content:""!important;display:block!important;' +
        'clear:both!important;height:' + px + 'px!important}');
      return getComputedStyle(onBody ? b : h, '::after').content !== 'none';
    };
    var n = function (s, p) { return parseFloat(s[p]) || 0; };
    var y = function () { return window.scrollY || se.scrollTop || 0; };
    var tail = function () { return n(hs, 'paddingBottom') + n(hs, 'borderBottomWidth') + n(hs, 'marginBottom'); };
    // Body's bottom margin as collapsed with its last in-flow descendants'
    // (a trailing <p>'s 1em lands below the body box, not inside it).
    var trail = function () {
      var m = n(bs, 'marginBottom'), pos = Math.max(0, m), neg = Math.min(0, m), e = b, s = bs, c, cs;
      while (!n(s, 'paddingBottom') && !n(s, 'borderBottomWidth') &&
          /^(block|list-item)$/.test(s.display) && /^visible$/.test(s.overflowY || 'visible')) {
        for (c = e.lastElementChild; c; c = c.previousElementSibling) {
          cs = getComputedStyle(c);
          if (cs.display !== 'none' && (cs.cssFloat || cs['float'] || 'none') === 'none' &&
              !/absolute|fixed/.test(cs.position)) break;
        }
        if (!c || Math.abs(c.getBoundingClientRect().bottom - e.getBoundingClientRect().bottom) > 1) break;
        m = n(cs, 'marginBottom'); pos = Math.max(pos, m); neg = Math.min(neg, m); e = c; s = cs;
      }
      return pos + neg;
    };
    // The body's last in-flow child, where the flow puts it: its
    // border-box bottom in document coordinates before any relative
    // offset or transform (offsetTop/offsetHeight ignore transforms; a
    // relative offset is taken back off; a sticky one, whose offset
    // moves with the scroll, is stacked on the sibling before it), and
    // its bottom margin. These are what ::after follows.
    // `body.scrollHeight` is not: it also counts relatively offset,
    // transformed and floated content, which overflows even an
    // auto-height body without moving ::after at all.
    var last = function () {
      var c, cs, sticky = [], edge, mb = 0, k;
      for (c = b.lastElementChild; c; c = c.previousElementSibling) {
        cs = getComputedStyle(c);
        if (/^(none|contents)$/.test(cs.display) || (cs.cssFloat || cs['float'] || 'none') !== 'none' ||
            /absolute|fixed/.test(cs.position)) continue;
        if (cs.position !== 'sticky') break;
        sticky.push([c, cs]);
      }
      if (c) {
        edge = c.offsetTop + c.offsetHeight - (cs.position === 'relative' ? n(cs, 'top') : 0);
        mb = n(cs, 'marginBottom');
      } else if (sticky.length) {
        edge = b.getBoundingClientRect().top + y() + n(bs, 'borderTopWidth') + n(bs, 'paddingTop');
      } else return null;
      for (k = sticky.length - 1; k >= 0; k--) {
        edge += Math.max(mb, n(sticky[k][1], 'marginTop')) + sticky[k][0].offsetHeight;
        mb = n(sticky[k][1], 'marginBottom');
      }
      return { edge: edge, mb: mb };
    };
    // How far the body's in-flow content runs past the body's content
    // box: > 0 only for a body of fixed height (`html, body
    // { height: 100% }`, `100vh`) — an auto-height body grows round it.
    var over = function () {
      var l = last();
      return l ? l.edge + Math.min(0, l.mb) -
        (b.getBoundingClientRect().bottom + y() - n(bs, 'paddingBottom') - n(bs, 'borderBottomWidth')) : 0;
    };
    // Where the spacer ends, in document coordinates.
    var spacerEnd = function () {
      var bb = b.getBoundingClientRect().bottom + y(), l;
      if (onBody) return Math.max(bb + n(bs, 'marginBottom'), (l = last()) ? l.edge + Math.max(0, l.mb) + px : 0);
      // html::after is the last thing in <html>'s content box, so where
      // <html> is as tall as its content (nearly always) that box ends
      // exactly at the spacer: past a collapsed trailing margin, past
      // the floats its clear:both clears. Else, estimate from the body.
      var hEnd = h.getBoundingClientRect().bottom + y() - n(hs, 'paddingBottom') - n(hs, 'borderBottomWidth');
      return Math.max(hEnd, bb + trail() + px);
    };
    // Does the body's content, overflowing its box, run on into the
    // band html::after occupies (`html, body { height: 100% }`)? Then
    // html::after adds less than its height, or nothing.
    var intrudes = function (end) {
      var l = over() > 1 && last();
      return !!l && l.edge > end - px + 2;
    };
    // In quirks mode `<body>` is the scrolling element and its
    // scrollHeight/clientHeight are the viewport's, so the same
    // document-level comparison holds there too.
    var ends = function () {
      var end = spacerEnd();
      if (!onBody && intrudes(end)) return false;
      return se.scrollHeight <= Math.max(end + tail(), se.clientHeight || 0) + 2;
    };
    var canBody = se !== b && /^(block|flow-root|list-item)$/.test(bs.display);
    // On body::after, stay only while the in-flow content overflows the
    // body box (a fixed-height body — the spacer, not being an element,
    // does not count); an auto-height body that merely grew around it
    // goes back to html::after.
    var want = onBody && canBody && over() > 1;
    if (!sheet || cur !== px || onBody !== want) {
      if (!put(want)) return drop();
    }
    if (ends()) return px;
    // html::after does not end the document. Move to body::after only if
    // the body's own overflow is what runs into or past it — the one
    // thing body::after follows. Uncleared floats never get here
    // (html::after clears them, so they end above it), and neither does
    // a page whose end is only a positioned element, so neither flips
    // the placement back and forth between passes.
    if (!onBody && canBody && intrudes(spacerEnd())) {
      if (!put(true)) return drop();
      if (ends()) return px;
    }
    return 0;
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
