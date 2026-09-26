package baby.freedom.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable

/**
 * [bottomSpacerJs] run for real (Rhino) against a minimal fake DOM:
 * just the handful of APIs the script touches. Each test is one
 * document; [run] is one pass of the script (a load hook, a history
 * update, a touch-down), and the fake's knobs change between passes the
 * way the page would.
 */
class BottomSpacerScriptTest {

    // `ours()` is the spacer's constructed sheet while the document has
    // it adopted, else null. `cspBlocksSheets` makes every constructed
    // rule fail to take, the way a blocked write would look.
    //
    // Layout, as Chromium does it: the body box is `bodyH` tall and its
    // in-flow content `contentH` (both `baseHeight` unless set; content
    // taller than the box is overflow). `html::after` sits under the body
    // *box*; `body::after` after the body's content. `absBottom` is the
    // end of an absolutely positioned element.
    private val fakeDom = """
        var htmlOverflowY = 'visible', bodyOverflowY = 'visible';
        var baseHeight = 2000, bodyH = null, contentH = null, absBottom = 0;
        var bodyDisplay = 'block';
        function bH() { return bodyH === null ? baseHeight : bodyH; }
        function cH() { return contentH === null ? baseHeight : contentH; }
        var cspBlocksSheets = false, sheetsBuilt = 0, sheetWrites = 0;
        var screen = { width: 412 };
        var window = { scrollY: 0, innerHeight: 800 };
        function CSSStyleSheet() {
          sheetsBuilt++;
          var self = this, props = {};
          this.textContent = '';
          this.cssRules = [];
          this.replaceSync = function (text) {
            sheetWrites++;
            self.textContent = text;
            props = {};
            var m = /(--[a-z-]+):(\d+)/.exec(text);
            if (m) props[m[1]] = m[2];
            self.cssRules = cspBlocksSheets ? [] : [{
              selectorText: /^[a-z]+::after/.exec(text)[0], style: {
              getPropertyValue: function (k) { return k in props ? props[k] : ''; } } }];
          };
        }
        var otherSheet = { cssRules: [{ style: { getPropertyValue: function () { return ''; } } }] };
        var document;
        function ours() {
          var l = document.adoptedStyleSheets;
          for (var i = 0; i < l.length; i++) if (l[i] instanceof CSSStyleSheet) return l[i];
          return null;
        }
        function spacerPx() {
          var s = ours();
          if (!s || !s.cssRules.length) return 0;
          return parseInt(s.cssRules[0].style.getPropertyValue('$BOTTOM_SPACER_MARK'), 10) || 0;
        }
        function placedOn() {
          var s = ours();
          return s && s.cssRules.length ? s.cssRules[0].selectorText.split(':')[0] : '';
        }
        function onBody() { return placedOn() === 'body' ? spacerPx() : 0; }
        function onHtml() { return placedOn() === 'html' ? spacerPx() : 0; }
        var html = {
          clientWidth: 412,
          get scrollHeight() {
            return Math.max(window.innerHeight, bH() + onHtml(), cH() + onBody(), absBottom);
          },
          get clientHeight() { return window.innerHeight; },
          get scrollTop() { return window.scrollY; }
        };
        var body = {
          get scrollHeight() { return Math.max(bH(), cH() + onBody()); },
          get clientHeight() { return bH(); },
          getBoundingClientRect: function () { return { bottom: bH() - window.scrollY }; }
        };
        document = {
          documentElement: html, body: body, scrollingElement: html,
          adoptedStyleSheets: [otherSheet]
        };
        function getComputedStyle(e, pseudo) {
          if (pseudo) return { content: spacerPx() > 0 && placedOn() === (e === html ? 'html' : 'body') ? '""' : 'none' };
          return {
            overflowY: e === html ? htmlOverflowY : bodyOverflowY,
            display: e === html ? 'block' : bodyDisplay,
            marginBottom: '0px', paddingBottom: '0px', borderBottomWidth: '0px'
          };
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
        fun run(dp: Int = 82): Int = num(bottomSpacerJs(dp))
        val spacer get() = num("spacerPx()")
        val styleCount get() = num(
            "var n = 0; for (var i = 0; i < document.adoptedStyleSheets.length; i++)" +
                " if (document.adoptedStyleSheets[i] instanceof CSSStyleSheet) n++; n",
        )
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
    fun `an ordinary document gets the spacer once`() = doc {
        assertEquals(82, run())
        assertEquals(82, run())
        assertEquals(1, styleCount)
        assertTrue(eval("ours().textContent").toString().contains("height:82px"))
    }

    // R1-F1: a document scroll-locked at load (consent banner).
    @Test
    fun `a lock at load is not padded, and the spacer arrives once it lifts`() = doc {
        eval("htmlOverflowY = 'hidden'")
        assertEquals(0, run()) // commit
        assertEquals(0, run()) // finish
        assertEquals(0, styleCount)
        eval("htmlOverflowY = 'visible'") // banner accepted
        assertEquals(82, run()) // next touch-down
        assertEquals(82, spacer)
    }

    // R1-F1: a pushState lightbox opened at the end of the page.
    @Test
    fun `a lock while at the end keeps the spacer, so scrollY is not clamped`() = doc {
        assertEquals(82, run())
        eval("window.scrollY = 2000 + 82 - 800") // scrolled to the very end
        eval("bodyOverflowY = 'hidden'")
        assertEquals(82, run()) // doUpdateVisitedHistory
        assertEquals(82, spacer)
        eval("bodyOverflowY = 'visible'")
        assertEquals(82, run())
        assertEquals(1, styleCount)
    }

    @Test
    fun `a lock away from the end still drops the spacer, as an app shell needs`() = doc {
        assertEquals(82, run())
        eval("bodyOverflowY = 'clip'")
        assertEquals(0, run())
        assertEquals(0, styleCount)
    }

    @Test
    fun `an app shell that is locked throughout never gets one`() = doc {
        eval("htmlOverflowY = 'hidden'; baseHeight = 800")
        repeat(5) { assertEquals(0, run()) }
        assertEquals(0, styleCount)
    }

    // R1-F2: rotation under 3-button navigation — 0 dp inset in
    // landscape (58 dp spacer), 48 dp in portrait (106 dp).
    @Test
    fun `a new height after rotation rewrites the rule in place`() = doc {
        eval("html.clientWidth = 915; screen.width = 915")
        assertEquals(58, run(58))
        eval("html.clientWidth = 412; screen.width = 412")
        assertEquals(106, run(106))
        assertEquals(1, styleCount)
        assertTrue(eval("ours().textContent").toString().contains("height:106px"))
    }

    // R1-F2: a desktop-width page (980 px layout) rotated from landscape
    // to portrait needs proportionally more CSS px.
    @Test
    fun `a desktop-width page's zoom factor is recomputed`() = doc {
        eval("html.clientWidth = 980; screen.width = 915")
        assertEquals(63, run(58)) // ceil(58 * 980 / 915)
        eval("screen.width = 412")
        assertEquals(196, run(82)) // ceil(82 * 980 / 412)
        assertEquals(196, spacer)
    }

    @Test
    fun `an unchanged pass writes nothing`() = doc {
        assertEquals(82, run())
        eval("sheetWrites = 0")
        assertEquals(82, run())
        assertEquals(0, num("sheetWrites"))
    }

    @Test
    fun `no body yet is unknown`() = doc {
        eval("document.body = null")
        assertEquals(-1, run())
        assertEquals(0, styleCount)
    }

    // R2-F1: a page whose CSP `style-src` lacks 'unsafe-inline'. The
    // spacer goes in through CSSOM, which `style-src` does not govern —
    // no `<style>` element, so nothing to block and nothing to report.
    @Test
    fun `the spacer is a constructed sheet, not a style element`() = doc {
        assertEquals(82, run())
        assertEquals(1, num("sheetsBuilt"))
        assertEquals(2, num("document.adoptedStyleSheets.length"))
        assertTrue(eval("document.adoptedStyleSheets[0] === otherSheet") as Boolean)
    }

    // R2-F1: whatever keeps the rule from taking, the script reports
    // what is really on the page — never a height the page lacks.
    @Test
    fun `a rule that does not take is withdrawn and reported as none`() = doc {
        eval("cspBlocksSheets = true")
        assertEquals(0, run())
        assertEquals(0, styleCount)
        assertEquals(1, num("document.adoptedStyleSheets.length")) // the site's own stays
    }

    // An SPA that resets `adoptedStyleSheets` drops the spacer; the next
    // pass puts it back and does not count the lost one.
    @Test
    fun `a sheet list reset by the page is re-adopted`() = doc {
        assertEquals(82, run())
        eval("document.adoptedStyleSheets = [otherSheet]")
        assertEquals(82, run())
        assertEquals(1, styleCount)
    }

    // R3-F1: `html, body { height: 100% }` with content running past the
    // body box. `html::after` would land under the box, inside the
    // overflow, and add nothing; `body::after` follows the content.
    @Test
    fun `content overflowing a full-height body gets the spacer after it`() = doc {
        eval("bodyH = 800; contentH = 1540")
        assertEquals(82, run())
        assertEquals("body", eval("placedOn()"))
        assertEquals(1540 + 82, num("html.scrollHeight"))
        assertEquals(82, run())
        assertEquals(1, styleCount)
    }

    // R3-F1: the verifier's 60 px case — html::after would only have
    // grown it 923 -> 945 while reporting 82, flipping the PTR gate.
    @Test
    fun `a small overflow past the body box still gets the whole spacer`() = doc {
        eval("bodyH = 863; contentH = 923; window.innerHeight = 863")
        assertEquals(82, run())
        assertEquals(923 + 82, num("html.scrollHeight"))
        assertTrue(documentScrollsPastSpacer(true, 923 + 82, 82, 863, 1f))
    }

    @Test
    fun `the placement follows the page as it grows and shrinks`() = doc {
        eval("bodyH = 800; contentH = 300")
        assertEquals(82, run())
        assertEquals("html", eval("placedOn()"))
        eval("contentH = 1540") // infinite scroll loads more
        assertEquals(82, run())
        assertEquals("body", eval("placedOn()"))
        eval("contentH = 780") // back inside the box; the spacer alone must not keep it on body
        assertEquals(82, run())
        assertEquals("html", eval("placedOn()"))
        assertEquals(1, styleCount)
    }

    // A flex/grid body is never given `body::after` (it would be a
    // flex/grid item); when its content overflows the box the spacer
    // cannot end the document, and the script says so.
    @Test
    fun `a flex body overflowing its box reports no spacer`() = doc {
        eval("bodyDisplay = 'flex'; bodyH = 800; contentH = 1540")
        assertEquals(0, run())
        assertEquals("html", eval("placedOn()"))
        eval("sheetWrites = 0")
        assertEquals(0, run()) // inert, and not rewritten on every pass
        assertEquals(0, num("sheetWrites"))
    }

    @Test
    fun `positioned content past the spacer reports no spacer`() = doc {
        eval("bodyH = 800; contentH = 100; absBottom = 1500")
        assertEquals(0, run())
        eval("absBottom = 0") // the positioned element goes away
        assertEquals(82, run())
    }

    @Test
    fun `a short page reports the spacer it gets`() = doc {
        eval("bodyH = 300; contentH = 300; window.innerHeight = 800")
        assertEquals(82, run())
        assertEquals("html", eval("placedOn()"))
    }

    @Test
    fun `no constructable stylesheets means no spacer`() = doc {
        eval("CSSStyleSheet = undefined")
        assertEquals(0, run())
    }
}
