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
    // *box* and a collapsed trailing margin `trail` (a last `<p>`'s),
    // below `floatB`, the end of any uncleared floats (its `clear`);
    // `body::after` after the body's content and floats. `bodyAuto`
    // makes the body box as tall as its in-flow content (plus the spacer
    // and the floats it clears, when on body). `absBottom` is the end of
    // an absolutely positioned element. The in-flow content is one last
    // child; `lastRel` pushes it down by `position: relative` (overflow
    // only — the flow, and ::after, stay put). `quirks` is a document
    // without a doctype: `<body>` is the scrolling element, reports the
    // viewport's extent, and its box is stretched to the viewport.
    private val fakeDom = """
        var htmlOverflowY = 'visible', bodyOverflowY = 'visible';
        var baseHeight = 2000, bodyH = null, contentH = null, absBottom = 0;
        var bodyDisplay = 'block', bodyAuto = false, trail = 0, floatB = 0;
        var lastRel = 0, quirks = false;
        function cH() { return contentH === null ? baseHeight : contentH; }
        function bH() {
          var v;
          if (bodyAuto) v = onBody() ? Math.max(cH(), floatB) + onBody() : cH();
          else v = bodyH === null ? baseHeight : bodyH;
          return quirks ? Math.max(v, window.innerHeight) : v;
        }
        function hH() { // <html>'s content box: auto height
          return onHtml() ? Math.max(bH() + trail, floatB) + onHtml() : Math.max(bH() + trail, floatB);
        }
        function bScroll() {
          return Math.max(bH(), cH() + lastRel, floatB, onBody() ? Math.max(cH(), floatB) + onBody() : 0);
        }
        var cspBlocksSheets = false, sheetsBuilt = 0, sheetWrites = 0;
        var screen = { width: 412 };
        var window = { scrollY: 0, innerHeight: 800 };
        function CSSStyleSheet() {
          sheetsBuilt++;
          var self = this, props = {}, prio = {};
          this.textContent = '';
          this.cssRules = [];
          this.replaceSync = function (text) {
            sheetWrites++;
            self.textContent = text;
            props = {}; prio = {};
            var re = /([a-z-]+):([^;!}]*)(!important)?/g, m;
            while ((m = re.exec(text.replace(/^[^{]*\{/, '')))) {
              props[m[1]] = m[2]; prio[m[1]] = m[3] ? 'important' : '';
            }
            self.cssRules = cspBlocksSheets ? [] : [{
              selectorText: /^[a-z]+::after/.exec(text)[0], style: {
              getPropertyValue: function (k) { return k in props ? props[k] : ''; },
              getPropertyPriority: function (k) { return k in prio ? prio[k] : ''; } } }];
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
          return parseInt(s.cssRules[0].style.getPropertyValue('height'), 10) || 0;
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
            return Math.max(window.innerHeight, hH(), bScroll(), absBottom);
          },
          get clientHeight() { return window.innerHeight; },
          get scrollTop() { return window.scrollY; },
          getBoundingClientRect: function () { return { top: -window.scrollY, bottom: hH() - window.scrollY }; }
        };
        var content = {
          get offsetTop() { return lastRel; },
          get offsetHeight() { return cH(); },
          previousElementSibling: null,
          getBoundingClientRect: function () {
            return { top: lastRel - window.scrollY, bottom: cH() + lastRel - window.scrollY };
          }
        };
        var body = {
          get scrollHeight() { return quirks ? html.scrollHeight : bScroll(); },
          get clientHeight() { return quirks ? window.innerHeight : bH(); },
          lastElementChild: content,
          getBoundingClientRect: function () { return { top: -window.scrollY, bottom: bH() - window.scrollY }; }
        };
        document = {
          documentElement: html, body: body,
          get scrollingElement() { return quirks ? body : html; },
          adoptedStyleSheets: [otherSheet]
        };
        function getComputedStyle(e, pseudo) {
          if (pseudo) return { content: spacerPx() > 0 && placedOn() === (e === html ? 'html' : 'body') ? '""' : 'none' };
          if (e === content) return {
            display: 'block', position: lastRel ? 'relative' : 'static', cssFloat: 'none',
            top: lastRel + 'px', marginBottom: '0px'
          };
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

    // R4-F1: a last `<p>` whose bottom margin collapses through body
    // lands below the body box; the spacer after it still ends the page.
    @Test
    fun `a trailing collapsed margin still reports the spacer`() = doc {
        eval("bodyAuto = true; contentH = 790; trail = 16; window.innerHeight = 863")
        assertEquals(82, run())
        assertEquals("html", eval("placedOn()"))
        assertEquals(790 + 16 + 82, num("html.scrollHeight"))
        assertFalse(documentScrollsPastSpacer(true, 790 + 16 + 82, 82, 863, 1f))
    }

    // R4-F2: floats overflowing an auto-height body. html::after clears
    // them, so it ends the page; body::after is never tried, and nothing
    // flips between passes.
    @Test
    fun `uncleared floats keep the spacer on html, with no rewrite per pass`() = doc {
        eval("bodyAuto = true; contentH = 20; floatB = 1880")
        assertEquals(82, run())
        assertEquals("html", eval("placedOn()"))
        assertEquals(1880 + 82, num("html.scrollHeight"))
        eval("sheetWrites = 0")
        repeat(4) {
            assertEquals(82, run())
            assertEquals("html", eval("placedOn()"))
        }
        assertEquals(0, num("sheetWrites"))
    }

    // R4-F2: an auto-height body that is on body::after (from before its
    // content moved into floats) goes back to html once and stays.
    @Test
    fun `an auto-height body that only grew around the spacer settles on html`() = doc {
        eval("bodyAuto = true; contentH = 20; floatB = 1880")
        eval("document.adoptedStyleSheets = [otherSheet, new CSSStyleSheet()]")
        eval("ours().replaceSync('body::after{clear:both!important;height:82px!important}')")
        assertEquals(82, run())
        assertEquals("html", eval("placedOn()"))
        eval("sheetWrites = 0")
        repeat(3) { assertEquals(82, run()) }
        assertEquals(0, num("sheetWrites"))
    }

    // R5-F1: no doctype. `<body>` is the scrolling element and its
    // scrollHeight/clientHeight are the viewport's; that is no body
    // overflow, and html::after (under the viewport-tall body box) does
    // end the document, so the spacer is reported and discounted.
    @Test
    fun `a quirks-mode short page reports the spacer it gets`() = doc {
        eval("quirks = true; bodyAuto = true; contentH = 100; window.innerHeight = 863")
        assertEquals(82, run())
        assertEquals("html", eval("placedOn()"))
        assertEquals(863 + 82, num("html.scrollHeight"))
        assertFalse(documentScrollsPastSpacer(true, 863 + 82, 82, 863, 1f))
        eval("sheetWrites = 0")
        assertEquals(82, run())
        assertEquals(0, num("sheetWrites"))
    }

    // R5-F2: a last child pushed down by `position: relative` (or a
    // transform) overflows an auto-height body, but ::after follows the
    // flow, not the offset box: neither placement gets past it. Report
    // 0, stay on html, and write nothing on later passes.
    @Test
    fun `a relatively offset footer reports no spacer and does not flip placement`() = doc {
        eval("bodyAuto = true; contentH = 1500; lastRel = 100; window.innerHeight = 863")
        assertEquals(0, run())
        assertEquals("html", eval("placedOn()"))
        eval("sheetWrites = 0")
        repeat(4) {
            assertEquals(0, run())
            assertEquals("html", eval("placedOn()"))
        }
        assertEquals(0, num("sheetWrites"))
    }

    // R5-F3: the sheet is found again by its shape, with no marker.
    @Test
    fun `the rule carries no custom property`() = doc {
        assertEquals(82, run())
        assertFalse(eval("ours().textContent").toString().contains("--"))
        assertEquals(82, run())
        assertEquals(1, styleCount)
    }
}
