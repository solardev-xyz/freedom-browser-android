package baby.freedom.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable

/**
 * [bottomSpacerDecisionJs] and [BOTTOM_SPACER_PRESENT_JS] run for real
 * (Rhino) against a minimal fake DOM. The fake does no layout: the
 * document's scroll extent is `baseHeight`, plus `growth(px)` while an
 * `html::after` rule of `px` is adopted — the knob that stands in for
 * whatever the real layout does with it. `sheetWrites` counts every
 * style write (`replaceSync`, `adoptedStyleSheets` assignment).
 */
class BottomSpacerScriptTest {

    private val fakeDom = """
        var htmlOverflowY = 'visible', bodyOverflowY = 'visible', pageHtmlAfter = 'none';
        var baseHeight = 2000, sheetWrites = 0, throwOnRead = false;
        var growth = function (px) { return px; };
        var screen = { width: 412 };
        function CSSStyleSheet() {
          var self = this;
          this.cssRules = [];
          this.replaceSync = function (text) {
            sheetWrites++;
            var m = /^([a-z]+)::after\{.*height:(\d+)px/.exec(text);
            self.cssRules = [{ selectorText: m[1] + '::after', height: +m[2] }];
          };
        }
        var otherSheet = { cssRules: [] };
        var adopted = [otherSheet];
        var document = {
          get adoptedStyleSheets() { return adopted; },
          set adoptedStyleSheets(v) { sheetWrites++; adopted = v.slice(); },
        };
        function spacerPx() {
          for (var i = 0; i < adopted.length; i++) {
            var r = adopted[i].cssRules[0];
            if (r && r.selectorText === 'html::after') return r.height;
          }
          return 0;
        }
        var html = {
          clientWidth: 412,
          get scrollHeight() {
            if (throwOnRead && spacerPx()) throw new Error('boom');
            var s = spacerPx();
            return baseHeight + (s ? growth(s) : 0);
          }
        };
        var body = {};
        document.documentElement = html;
        document.body = body;
        document.scrollingElement = html;
        function getComputedStyle(e, pseudo) {
          if (pseudo === '::after') {
            if (e !== html) throw new Error('only html::after is read');
            return { content: spacerPx() ? '""' : pageHtmlAfter };
          }
          return { overflowY: e === html ? htmlOverflowY : bodyOverflowY };
        }
    """

    private inner class Doc {
        private val cx: Context = Context.enter().apply { optimizationLevel = -1 }
        private val scope: Scriptable = cx.initStandardObjects()

        init {
            eval(fakeDom)
        }

        fun eval(js: String): Any? = cx.evaluateString(scope, js, "t", 1, null)
        fun num(js: String): Int = (Context.toNumber(eval(js))).toInt()
        fun decide(dp: Int = 82): Int = num(bottomSpacerDecisionJs(dp))
        fun present(): Int = num(BOTTOM_SPACER_PRESENT_JS)
        val spacer get() = num("spacerPx()")
        val scrollHeight get() = num("html.scrollHeight")
        val writes get() = num("sheetWrites")
        val sheets get() = num("adopted.length")
        fun close() = Context.exit()
    }

    private fun doc(block: Doc.() -> Unit) {
        val d = Doc()
        try {
            d.block()
        } finally {
            d.close()
        }
    }

    @Test
    fun `an ordinary document keeps the spacer and reports the measured growth`() = doc {
        assertEquals(82, decide())
        assertEquals(2082, scrollHeight)
        assertEquals(82, spacer)
        assertEquals(2, sheets) // the page's own sheet is untouched
        assertTrue(eval("adopted[0] === otherSheet") as Boolean)
    }

    @Test
    fun `a spacer that adds no scroll range is removed and reports zero`() = doc {
        // `html, body { height: 100% }` with overflowing content: html::after
        // lands inside the overflow.
        eval("growth = function () { return 0; }")
        assertEquals(0, decide())
        assertEquals(2000, scrollHeight) // exactly as without it
        assertEquals(0, spacer)
        assertEquals(1, sheets)
        assertTrue(eval("adopted[0] === otherSheet") as Boolean)
    }

    @Test
    fun `partial growth is rejected, not kept at a wrong height`() = doc {
        eval("growth = function (px) { return px - 40; }")
        assertEquals(0, decide())
        assertEquals(0, spacer)
    }

    @Test
    fun `growth one px short of the height still counts, and is what it reports`() = doc {
        eval("growth = function (px) { return px - 1; }")
        assertEquals(81, decide())
        assertEquals(81, scrollHeight - 2000)
    }

    @Test
    fun `growth beyond the height is reported as measured`() = doc {
        eval("growth = function (px) { return px + 16; }") // e.g. a collapsed margin
        assertEquals(98, decide())
    }

    @Test
    fun `a scroll lock is pending and inserts nothing`() = doc {
        eval("htmlOverflowY = 'hidden'")
        assertEquals(-1, decide())
        eval("htmlOverflowY = 'visible'; bodyOverflowY = 'clip'")
        assertEquals(-1, decide())
        assertEquals(0, writes)
        assertEquals(1, sheets)
        eval("bodyOverflowY = 'visible'") // consent accepted
        assertEquals(82, decide())
    }

    @Test
    fun `a width change under a scroll lock leaves a kept spacer in place`() = doc {
        // Kept, then a lightbox sets body{overflow:hidden} and the user rotates.
        assertEquals(82, decide())
        eval("bodyOverflowY = 'hidden'; sheetWrites = 0")
        assertEquals(-1, decide(58))
        assertEquals(0, writes)
        assertEquals(82, spacer) // not dropped
        assertEquals(2082, scrollHeight)
        eval("bodyOverflowY = 'visible'") // lightbox closed: redone at the new height
        assertEquals(58, decide(58))
        assertEquals(2, sheets)
    }

    @Test
    fun `a page that styles html__after itself is left alone`() = doc {
        // html::after{content:'mobile';display:none}, read back from JS as a
        // breakpoint channel: ours would override it.
        eval("pageHtmlAfter = '\"mobile\"'")
        assertEquals(0, decide())
        assertEquals(0, writes)
        assertEquals(1, sheets)
        eval("pageHtmlAfter = 'normal'")
        assertEquals(82, decide())
    }

    @Test
    fun `our own kept rule is not mistaken for the page's`() = doc {
        assertEquals(82, decide())
        assertEquals(106, decide(106)) // re-decision drops ours before looking
    }

    @Test
    fun `no body yet is pending`() = doc {
        eval("document.body = null")
        assertEquals(-1, decide())
        assertEquals(0, writes)
    }

    @Test
    fun `no constructed stylesheets is rejected`() = doc {
        eval("CSSStyleSheet = undefined")
        assertEquals(0, decide())
        assertEquals(0, writes)
    }

    @Test
    fun `a kept decision costs two writes, a rejected one four, and leaves one sheet`() = doc {
        assertEquals(82, decide())
        assertEquals(2, writes) // replaceSync + adopt
        eval("sheetWrites = 0; growth = function () { return 0; }")
        assertEquals(0, decide()) // drop old, build, adopt, drop
        assertEquals(4, writes)
        assertEquals(1, sheets)
    }

    @Test
    fun `a re-decision replaces the earlier sheet rather than stacking`() = doc {
        // Rotation under 3-button navigation: 58 dp in landscape, 106 in portrait.
        eval("html.clientWidth = 915; screen.width = 915")
        assertEquals(58, decide(58))
        eval("html.clientWidth = 412; screen.width = 412")
        assertEquals(106, decide(106))
        assertEquals(2, sheets)
        assertEquals(106, spacer)
        assertEquals(2106, scrollHeight)
    }

    @Test
    fun `a desktop-width page gets its zoom factor`() = doc {
        eval("html.clientWidth = 980")
        assertEquals(196, decide(82)) // ceil(82 * 980 / 412)
    }

    @Test
    fun `a failure after inserting removes the rule`() = doc {
        eval("throwOnRead = true")
        assertEquals(0, decide())
        assertEquals(0, spacer)
        assertEquals(1, sheets)
    }

    @Test
    fun `our sheet is found by a neutral non-enumerable mark`() = doc {
        assertEquals(0, present())
        decide()
        assertEquals(1, present())
        val ours = "adopted[adopted.length - 1]"
        assertTrue(eval("$ours.$BOTTOM_SPACER_MARK === true") as Boolean)
        assertFalse(eval("Object.keys($ours).indexOf('$BOTTOM_SPACER_MARK') >= 0") as Boolean)
        eval("sheetWrites = 0")
        present()
        assertEquals(0, writes) // the history check is read-only
    }

    @Test
    fun `an SPA that resets adoptedStyleSheets is seen as having lost it`() = doc {
        decide()
        eval("document.adoptedStyleSheets = [otherSheet]")
        assertEquals(0, present())
        assertEquals(82, decide())
        assertEquals(1, present())
    }

    @Test
    fun `nothing in the scripts names the browser`() {
        for (js in listOf(bottomSpacerDecisionJs(82), BOTTOM_SPACER_PRESENT_JS)) {
            assertFalse(js.contains("freedom", ignoreCase = true))
            assertFalse(js.contains("createElement"))
            assertFalse(js.contains("body::after"))
        }
        assertTrue(bottomSpacerDecisionJs(82).contains("new CSSStyleSheet()"))
    }
}
