package baby.freedom.mobile.browser

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable

/**
 * [THEME_COLOR_JS] runs for real (Rhino) against a fake `<head>`: a list
 * of `<meta>` elements, a `matchMedia` answering from a table, and a 2D
 * canvas whose `fillStyle` serialises the way Chromium's does (opaque
 * colours as `#rrggbb`, translucent ones as `rgba(…)`, anything it can't
 * parse left as it was). The script's answer goes through
 * [themeColorArgb] exactly as the WebView's does, JSON-encoded.
 */
class ThemeColorTest {

    private val fakeDom = """
        var window = this, metas = [], mediaMatches = {}, selectors = [], writes = 0;
        function meta(content, media) {
          return { getAttribute: function (a) { return a === 'content' ? content : a === 'media' ? (media === undefined ? null : media) : null; } };
        }
        // What the canvas paints for colours outside sRGB's syntax: the engine's sRGB for them.
        var painted = { 'oklch(60% 0.2 30)': [211, 74, 64, 255], 'color(display-p3 1 0 0)': [255, 0, 0, 255],
                        'oklch(60% 0.2 30 / 0)': [0, 0, 0, 0] };
        var named = { red: '#ff0000', white: '#ffffff', navy: '#000080', rebeccapurple: '#663399' };
        function serialise(c) {
          var s = String(c).trim().toLowerCase(), m;
          if (named[s]) return named[s];
          if (s === 'transparent') return 'rgba(0, 0, 0, 0)';
          if (s === 'currentcolor') return '#000000';
          if ((m = /^#([0-9a-f])([0-9a-f])([0-9a-f])${'$'}/.exec(s))) return '#' + m[1] + m[1] + m[2] + m[2] + m[3] + m[3];
          if (/^#[0-9a-f]{6}${'$'}/.test(s)) return s;
          // Chromium keeps a non-sRGB CSS Color 4 value in its own syntax.
          if (/^(oklch|oklab|lab|lch|color)\(/.test(s)) return s;
          if ((m = /^rgba?\((\d+),\s*(\d+),\s*(\d+)(?:,\s*([\d.]+))?\)${'$'}/.exec(s))) {
            if (m[4] !== undefined && +m[4] < 1) return 'rgba(' + m[1] + ', ' + m[2] + ', ' + m[3] + ', ' + m[4] + ')';
            var h = function (n) { return ('0' + (+n).toString(16)).slice(-2); };
            return '#' + h(m[1]) + h(m[2]) + h(m[3]);
          }
          return null;
        }
        var document = {
          querySelectorAll: function (sel) { selectors.push(sel); return metas; },
          createElement: function (t) {
            return { getContext: function () {
              var v = '#000000', px = [0, 0, 0, 0];
              return {
                get fillStyle() { return v; },
                set fillStyle(c) { var s = serialise(c); if (s) v = s; },
                clearRect: function () { px = [0, 0, 0, 0]; },
                fillRect: function () { px = painted[v] || [0, 0, 0, 0]; },
                getImageData: function () { return { data: px }; }
              };
            } };
          },
          appendChild: function () { writes++; }
        };
        function matchMedia(q) { return { matches: !!mediaMatches[q] }; }
    """.trimIndent()

    private val cx: Context = Context.enter().apply { optimizationLevel = -1 }
    private val scope: Scriptable = cx.initStandardObjects().also {
        cx.evaluateString(it, fakeDom, "dom", 1, null)
    }

    @After fun close() = Context.exit()

    private fun eval(js: String): Any? = cx.evaluateString(scope, js, "t", 1, null)

    /** Run the script and decode its answer the way [BrowserWebView] does. */
    private fun read(): Int? {
        val script = THEME_COLOR_JS.trim().removeSuffix(";")
        val json = Context.toString(eval("JSON.stringify($script) || 'null'"))
        return themeColorArgb(json)
    }

    @Test
    fun `no theme-color falls back to none`() {
        assertNull(read())
        assertEquals("meta[name=\"theme-color\" i]", Context.toString(eval("selectors[0]")))
    }

    @Test
    fun `hex, short hex, rgb and named colours are read`() {
        eval("metas = [meta('#1A73E8')]")
        assertEquals(0xFF1A73E8.toInt(), read())
        eval("metas = [meta('#fff')]")
        assertEquals(0xFFFFFFFF.toInt(), read())
        eval("metas = [meta('rgb(12, 34, 56)')]")
        assertEquals(0xFF0C2238.toInt(), read())
        eval("metas = [meta('  rebeccapurple ')]")
        assertEquals(0xFF663399.toInt(), read())
    }

    @Test
    fun `light and dark tags - the one whose media matches wins`() {
        eval(
            "metas = [meta('#ffffff', '(prefers-color-scheme: light)'), " +
                "meta('#0d1117', '(prefers-color-scheme: dark)')]",
        )
        eval("mediaMatches = { '(prefers-color-scheme: light)': true }")
        assertEquals(0xFFFFFFFF.toInt(), read())
        eval("mediaMatches = { '(prefers-color-scheme: dark)': true }")
        assertEquals(0xFF0D1117.toInt(), read())
    }

    @Test
    fun `a media-less tag after a non-matching one is the fallback`() {
        eval("metas = [meta('#0d1117', '(prefers-color-scheme: dark)'), meta('#ff0000')]")
        eval("mediaMatches = {}")
        assertEquals(0xFFFF0000.toInt(), read())
        // …and tree order decides between two that both apply.
        eval("mediaMatches = { '(prefers-color-scheme: dark)': true }")
        assertEquals(0xFF0D1117.toInt(), read())
    }

    @Test
    fun `unparsable, empty, currentcolor and transparent values are skipped`() {
        eval("metas = [meta('not-a-colour'), meta(''), meta(null), meta('currentColor'), meta('transparent')]")
        assertNull(read())
        // …in favour of the next one that does parse.
        eval("metas.push(meta('navy'))")
        assertEquals(0xFF000080.toInt(), read())
    }

    @Test
    fun `colours outside sRGB's syntax are read from a painted pixel`() {
        eval("metas = [meta('oklch(60% 0.2 30)')]")
        assertEquals(0xFFD34A40.toInt(), read())
        eval("metas = [meta('color(display-p3 1 0 0)')]")
        assertEquals(0xFFFF0000.toInt(), read())
        // Fully transparent still doesn't count, however it's written.
        eval("metas = [meta('oklch(60% 0.2 30 / 0)'), meta('navy')]")
        assertEquals(0xFF000080.toInt(), read())
        assertEquals(0, Context.toNumber(eval("writes")).toInt())
    }

    @Test
    fun `a translucent colour is taken opaque`() {
        eval("metas = [meta('rgba(255, 0, 0, 0.5)')]")
        assertEquals(0xFFFF0000.toInt(), read())
    }

    @Test
    fun `a throwing page reads as none and nothing is written`() {
        eval("document.querySelectorAll = function () { throw new Error('nope'); }")
        assertNull(read())
        assertEquals(0, Context.toNumber(eval("writes")).toInt())
    }

    @Test
    fun `only the script's own rgb form is accepted from the bridge`() {
        assertEquals(0xFF010203.toInt(), themeColorArgb("\"rgb(1, 2, 3)\""))
        assertNull(themeColorArgb("null"))
        assertNull(themeColorArgb(null))
        assertNull(themeColorArgb("\"#010203\""))
        assertNull(themeColorArgb("\"rgb(1, 2, 300)\""))
    }

    @Test
    fun `home never takes a tint`() {
        assertNull(statusBarTint(0xFF123456.toInt(), isHomeTab = true))
        assertEquals(0xFF123456.toInt(), statusBarTint(0xFF123456.toInt(), isHomeTab = false))
        assertNull(statusBarTint(null, isHomeTab = false))
    }

    @Test
    fun `status bar icons - scheme without a tint, contrast with one`() {
        // No tint: exactly the pre-#92 rule.
        assertTrue(statusBarIconsDark(null, lightScheme = true))
        assertFalse(statusBarIconsDark(null, lightScheme = false))
        // Light tints get dark icons, dark ones light, whatever the scheme.
        assertTrue(statusBarIconsDark(0xFFFFFFFF.toInt(), lightScheme = false))
        assertTrue(statusBarIconsDark(0xFFFFEB3B.toInt(), lightScheme = false))
        assertFalse(statusBarIconsDark(0xFF0D1117.toInt(), lightScheme = true))
        assertFalse(statusBarIconsDark(0xFF000000.toInt(), lightScheme = true))
        // Mid-tone brand colours keep white icons (Chrome's 3:1 rule).
        assertFalse(statusBarIconsDark(0xFF1A73E8.toInt(), lightScheme = true))
        assertFalse(statusBarIconsDark(0xFFD32F2F.toInt(), lightScheme = true))
    }

    @Test
    fun `luminance endpoints`() {
        assertEquals(0.0, relativeLuminance(0xFF000000.toInt()), 1e-9)
        assertEquals(1.0, relativeLuminance(0xFFFFFFFF.toInt()), 1e-9)
    }

    @Test
    fun `a read lands only for the document it was sent for, once painted`() {
        val slot = ThemeColorSlot()
        slot.startDocument()
        // Before first paint the outgoing page is still on screen.
        assertNull(slot.beginRead())
        // …unless the caller knows the document is the one on screen.
        val finished = slot.beginRead(onScreen = true)!!
        assertTrue(slot.accept(finished))
        slot.painted()
        val token = slot.beginRead()!!
        assertTrue(slot.accept(token))
        // A read in flight when the next document starts is dropped.
        val stale = slot.beginRead()!!
        slot.startDocument()
        assertFalse(slot.accept(stale))
        assertNull(slot.beginRead())
    }

    @Test
    fun `a written blank page reads once per burst of frames, and frames after a read read again`() {
        val reads = BlankPageReads()
        assertTrue(reads.drawn())      // first frame: schedule a read
        assertFalse(reads.drawn())     // frames before it runs are covered by it
        assertFalse(reads.drawn())
        reads.fired()                  // it runs (whether or not it ever answers)…
        assertTrue(reads.drawn())      // …and a later frame schedules the next
        assertFalse(reads.drawn())
    }
}
