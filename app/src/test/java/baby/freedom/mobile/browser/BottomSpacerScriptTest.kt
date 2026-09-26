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

    private val fakeDom = """
        var styleEl = null;
        var htmlOverflowY = 'visible', bodyOverflowY = 'visible';
        var baseHeight = 2000;
        var screen = { width: 412 };
        var window = { scrollY: 0, innerHeight: 800 };
        function spacerPx() { return styleEl ? (parseInt(styleEl.getAttribute('data-px'), 10) || 0) : 0; }
        function mkStyle() {
          var attrs = {};
          return {
            id: '', textContent: '',
            setAttribute: function (k, v) { attrs[k] = v; },
            getAttribute: function (k) { return k in attrs ? attrs[k] : null; },
            remove: function () { if (styleEl === this) styleEl = null; }
          };
        }
        var html = {
          clientWidth: 412,
          get scrollHeight() { return baseHeight + spacerPx(); },
          get clientHeight() { return window.innerHeight; },
          get scrollTop() { return window.scrollY; },
          appendChild: function (e) { styleEl = e; }
        };
        var body = {};
        var document = {
          documentElement: html, body: body, head: html, scrollingElement: html,
          getElementById: function (id) { return styleEl && styleEl.id === id ? styleEl : null; },
          createElement: function () { return mkStyle(); }
        };
        function getComputedStyle(e) { return { overflowY: e === html ? htmlOverflowY : bodyOverflowY }; }
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
        val styleCount get() = num("styleEl ? 1 : 0")
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
        assertTrue(eval("styleEl.textContent").toString().contains("height:82px"))
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
        assertTrue(eval("styleEl.textContent").toString().contains("height:106px"))
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
        eval("styleEl.textContent = 'sentinel'")
        assertEquals(82, run())
        assertEquals("sentinel", eval("styleEl.textContent").toString())
    }

    @Test
    fun `no body yet is unknown`() = doc {
        eval("document.body = null")
        assertEquals(-1, run())
        assertFalse(num("styleEl ? 1 : 0") == 1)
    }
}
