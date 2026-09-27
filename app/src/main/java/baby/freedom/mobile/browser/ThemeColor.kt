package baby.freedom.mobile.browser

import kotlin.math.pow

// The page's theme colour behind the status bar (#92).
//
// A page can name its brand colour with `<meta name="theme-color">`,
// optionally one per colour scheme (`media="(prefers-color-scheme: dark)"`).
// Chrome and Safari paint their chrome with it; here the band behind the
// status bar takes it, as Freedom iOS does (`BrowserTab.extractThemeColor`),
// and the status-bar icons flip to whichever of light / dark reads on it.
// A page without one, or with one we can't read, keeps today's look: the
// app background and the scheme's icons.

/**
 * Reads the document's theme colour: the first `<meta name="theme-color">`
 * whose `media` (if any) matches and whose `content` is a CSS colour, as
 * `rgb(r, g, b)` — the one form [parseRgb] takes — or `null`.
 *
 * The rules are the HTML spec's: tree order, a non-matching `media` is
 * skipped, and so is a `content` that doesn't parse, so a page's
 * dark-scheme tag followed by its default one resolves the way the
 * page's own CSS would (`matchMedia` answers with the colour scheme the
 * page is rendered in). The name matches case-insensitively, like
 * Chromium's own lookup.
 *
 * Any CSS colour is accepted (hex, `rgb()`, `hsl()`, named…): it is
 * normalised by a detached 2D canvas, the same way the bottom-nav
 * detector reads the page's `theme-color` for its strip
 * ([bottomUiDetectorJs]). A value the canvas rejects keeps its
 * `fillStyle` unchanged, so it is set over two different defaults and
 * accepted only if both agree. `currentcolor` (meaningless without an
 * element) and a fully transparent colour don't count; any other alpha
 * is dropped, a status bar can't be see-through.
 *
 * Nothing is written to the page: no node is inserted, the canvas is
 * never attached, and there is no global or listener of ours left
 * behind. Deliberately total — any exception reads as "no theme colour".
 */
internal const val THEME_COLOR_JS = """
(function () {
  try {
    var d = document, w = window, ctx = null;
    var RGBA = /^rgba?\(\s*([\d.]+)[\s,]+([\d.]+)[\s,]+([\d.]+)\s*(?:[,\/]\s*([\d.]+)(%?)\s*)?\)$/;
    var HEX = /^#([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})$/i;
    function norm(c) {
      if (!c || /currentcolor/i.test(c)) return null;
      if (!ctx) ctx = d.createElement('canvas').getContext('2d');
      ctx.fillStyle = '#000'; ctx.fillStyle = c; var a = ctx.fillStyle;
      ctx.fillStyle = '#fff'; ctx.fillStyle = c; if (ctx.fillStyle !== a) return null;
      var h = HEX.exec(a);
      if (h) return 'rgb(' + parseInt(h[1], 16) + ', ' + parseInt(h[2], 16) + ', ' + parseInt(h[3], 16) + ')';
      var m = RGBA.exec(a);
      if (!m || (m[4] !== undefined && parseFloat(m[4]) === 0)) return null;
      return 'rgb(' + Math.round(+m[1]) + ', ' + Math.round(+m[2]) + ', ' + Math.round(+m[3]) + ')';
    }
    var ms = d.querySelectorAll('meta[name="theme-color" i]');
    for (var i = 0; i < ms.length; i++) {
      var q = ms[i].getAttribute('media');
      if (q && !(w.matchMedia && w.matchMedia(q).matches)) continue;
      var c = norm(ms[i].getAttribute('content'));
      if (c) return c;
    }
  } catch (e) {}
  return null;
})();
"""

/**
 * The theme colour as opaque ARGB from the raw `evaluateJavascript`
 * result of [THEME_COLOR_JS] (a JSON string, or `null`); `null` for
 * none, or for anything that isn't exactly the `rgb(r, g, b)` the
 * script sends.
 */
internal fun themeColorArgb(jsResult: String?): Int? = parseRgb(unquoteJsString(jsResult))

/**
 * The colour behind the status bar for the tab on screen: the page's
 * theme colour, except on the home surface (a native screen, whatever
 * the blank document under it says) — `null` is "the app background".
 */
internal fun statusBarTint(themeColorArgb: Int?, isHomeTab: Boolean): Int? =
    if (isHomeTab) null else themeColorArgb

/**
 * `WindowInsetsControllerCompat.isAppearanceLightStatusBars` — "dark
 * icons, please" — for a status bar drawn over [tintArgb], or over the
 * app's own background when that's `null` (then the scheme decides, as
 * before #92).
 *
 * Light icons whenever white reaches 3:1 contrast on the tint, else dark
 * ones: Chrome's rule for its own theme-coloured toolbar
 * (`ColorUtils.shouldUseLightForegroundOnBackground`). It favours white
 * on mid-tone brand colours (a Material blue, a GitHub-ish slate), where
 * a plain "whichever contrasts more" would pick black.
 */
internal fun statusBarIconsDark(tintArgb: Int?, lightScheme: Boolean): Boolean {
    tintArgb ?: return lightScheme
    val whiteContrast = 1.05 / (relativeLuminance(tintArgb) + 0.05)
    return whiteContrast < 3.0
}

/** WCAG relative luminance of an ARGB colour's RGB (alpha ignored), 0..1. */
internal fun relativeLuminance(argb: Int): Double {
    fun channel(shift: Int): Double {
        val c = ((argb shr shift) and 0xFF) / 255.0
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
}

/**
 * Keeps a theme-colour read the *current* document's answer.
 *
 * The read is an asynchronous `evaluateJavascript` while the document it
 * describes can be replaced at any moment, and `onPageStarted` for an
 * incoming document fires while the outgoing one is still on screen —
 * a read sent then would describe the old page and be filed under the
 * new one. So, the same shape as [RootPanProbeSlot] plus a paint gate:
 *
 * - [startDocument] (`onPageStarted`) mints a new generation and marks
 *   the document unpainted;
 * - [painted] (`onPageCommitVisible`) opens the gate;
 * - [beginRead] stamps a read with the current generation, or refuses
 *   (`null`) before first paint unless the caller knows the document is
 *   the one on screen (`onPageFinished` for the current load, which a
 *   document that never reports a first paint still gets);
 * - [accept] lets an answer land only while its generation is current.
 *
 * The colour itself is not cleared at [startDocument]: until the new
 * document paints, the old one is what the user sees, and its tint with
 * it. The first read after the paint replaces it — with `null` if the new
 * page has none.
 *
 * Single-threaded: every caller is a `WebViewClient` callback or an
 * `evaluateJavascript` result, all on the UI thread.
 */
internal class ThemeColorSlot {
    private var generation = 0
    private var hasPainted = false

    /** A new document is starting; no read of the old one may speak for it. */
    fun startDocument() {
        generation++
        hasPainted = false
    }

    /** The document on screen has painted. */
    fun painted() {
        hasPainted = true
    }

    /**
     * The token for a read about to be sent, or `null` if the document
     * hasn't painted yet and [onScreen] doesn't vouch for it.
     */
    fun beginRead(onScreen: Boolean = false): Int? =
        if (hasPainted || onScreen) generation else null

    /** May the answer to the read stamped [token] land? */
    fun accept(token: Int): Boolean = token == generation
}
