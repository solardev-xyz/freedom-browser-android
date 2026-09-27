package baby.freedom.mobile.browser

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable

/**
 * [bottomUiDetectorJs] runs for real (Rhino) against a fake DOM. The fake
 * does no layout: each element carries its own rect, background and
 * `position`, and `elementFromPoint` returns whatever the test says is at
 * the probe point. Timers, observers and the channel object are fakes the
 * test drives by hand, so "what runs when" is observable. [Page.install]
 * does what the WebView does: runs the script at "document start", then
 * sends Kotlin's first probe request at "first paint".
 */
class BottomUiDetectorScriptTest {

    private val channel = "qwertyuiopasdfgh"

    private val fakeDom = """
        var window = this;
        var sent = [], channelListeners = [], timers = [], timerSeq = 0, mutationCb = null,
            resizeListeners = [], observed = [], hit = null, readies = 0, windowListeners = 0, metas = [], mediaMatches = {},
            domWrites = 0, reads = 0;
        var innerWidth = 412, innerHeight = 863;
        function el(o) {
          o.parentElement = o.parent || null;
          o.getBoundingClientRect = function () { reads++; return o.rect; };
          o.querySelector = function (sel) { return o.interactive ? {} : null; };
          return o;
        }
        var html = el({ tag: 'html', clientWidth: 412, clientHeight: 863, rect: { top: 0, bottom: 863, height: 863, width: 412 } });
        var body = el({ tag: 'body', parent: html, rect: { top: 0, bottom: 863, height: 863, width: 412 } });
        var scrollHeight = 863;
        var document = {
          compatMode: 'CSS1Compat', documentElement: html, body: body,
          scrollingElement: { get scrollHeight() { return scrollHeight; } },
          elementFromPoint: function (x, y) { this.lastProbe = [x, y]; return hit; },
          querySelectorAll: function (sel) { return sel === 'meta[name="theme-color"]' ? metas : []; },
          createElement: function (t) {
            return { getContext: function () {
              var v = '#000000';
              var named = { red: '#ff0000', white: '#ffffff', navy: '#000080' };
              return {
                get fillStyle() { return v; },
                set fillStyle(c) {
                  if (/^#[0-9a-f]{6}${'$'}/i.test(c)) v = c.toLowerCase();
                  else if (c === '#000') v = '#000000';
                  else if (c === '#fff') v = '#ffffff';
                  else if (named[c]) v = named[c];
                }
              };
            } };
          },
          appendChild: function () { domWrites++; }, setAttribute: function () { domWrites++; },
          addEventListener: function (t, f) { docListeners.push({ t: t, f: f }); }
        };
        var docListeners = [];
        function readyStateChanges() { for (var i = 0; i < docListeners.length; i++) if (docListeners[i].t === 'readystatechange') docListeners[i].f(); }
        function getComputedStyle(e) {
          return { backgroundColor: e.bg || 'rgba(0, 0, 0, 0)', position: e.pos || 'static' };
        }
        function matchMedia(q) { return { matches: !!mediaMatches[q] }; }
        function setTimeout(f, ms) { timers.push({ f: f, ms: ms }); return ++timerSeq; }
        var contextMenuListeners = [], inputListeners = [];
        // The page's clock, and Event's native timeStamp getter (which the page may later shadow).
        var perfNow = 1000;
        var performance = { now: function () { return perfNow; } };
        function Event() {}
        Object.defineProperty(Event.prototype, 'timeStamp', { configurable: true, get: function () { return this._ts; } });
        // A trusted (or synthetic) input event of type [t] reaching this document.
        function input(t, trusted) {
          var e = new Event(); e.isTrusted = trusted; e._ts = perfNow - 7;
          for (var i = 0; i < inputListeners.length; i++) if (inputListeners[i].t === t) inputListeners[i].f(e);
        }
        function addEventListener(t, f, c) {
          if (t === 'contextmenu') { contextMenuListeners.push({ f: f, capture: c === true }); return; }
          if (t === 'pointerdown' || t === 'keydown' || t === 'click') { inputListeners.push({ t: t, f: f, capture: c === true }); return; }
          windowListeners++; if (t === 'resize') resizeListeners.push(f);
        }
        // A trusted long-press's `contextmenu`: our listener, then the page's handlers, then tasks.
        function pressAndHold(e, pageCancels) {
          for (var i = 0; i < contextMenuListeners.length; i++) contextMenuListeners[i].f(e);
          if (pageCancels) e.defaultPrevented = true;
          flushTimers();
        }
        function MutationObserver(cb) { mutationCb = cb; this.observe = function (n, o) { this.target = n; this.opts = o; }; }
        function ResizeObserver(cb) { this.cb = cb; var self = this;
          this.observe = function (e) { observed.push({ kind: 'resize', el: e, cb: cb, obs: self }); };
          this.disconnect = function () { observed = observed.filter(function (o) { return o.obs !== self; }); }; }
        function IntersectionObserver(cb) { this.cb = cb; var self = this;
          this.observe = function (e) { observed.push({ kind: 'intersection', el: e, cb: cb, obs: self }); };
          this.disconnect = function () { observed = observed.filter(function (o) { return o.obs !== self; }); }; }
        // As the platform defines it: an ordinary (deletable) property of window.
        window.$channel = {
          postMessage: function (s) { if (s === '$BOTTOM_UI_READY') readies++; else sent.push(s); },
          addEventListener: function (t, f) { if (t === 'message') channelListeners.push(f); }
        };
        var top = window;
        function flushTimers() { var t = timers; timers = []; for (var i = 0; i < t.length; i++) t[i].f(); return t.length; }
        function kotlinSays(data) { for (var i = 0; i < channelListeners.length; i++) channelListeners[i]({ data: data }); }
        // A fixed tab bar: 56 px, full width, bottom-anchored, with tabs.
        var app = el({ tag: 'div', parent: body, rect: { top: 0, bottom: 2000, height: 2000, width: 412 } });
        var nav = el({ tag: 'nav', parent: app, pos: 'fixed', bg: 'rgb(103, 80, 164)', interactive: true,
                       rect: { top: 807, bottom: 863, height: 56, width: 412 } });
        var tab = el({ tag: 'button', parent: nav, rect: { top: 807, bottom: 863, height: 56, width: 103 } });
    """

    private val token = "0123abcd"

    private inner class Page {
        private val cx: Context = Context.enter().apply { optimizationLevel = -1 }
        private val scope: Scriptable = cx.initStandardObjects()

        init {
            eval(fakeDom)
        }

        fun eval(js: String): Any? = cx.evaluateString(scope, js, "t", 1, null)
        fun num(js: String): Int = Context.toNumber(eval(js)).toInt()
        fun documentStart() = eval(bottomUiDetectorJs(channel))
        fun firstPaint() = eval("kotlinSays('${bottomUiProbeRequest(token)}')")
        fun install() {
            documentStart()
            firstPaint()
        }
        val sent: Int get() = num("sent.length")
        fun last(): JSONObject = JSONObject(Context.toString(eval("sent[sent.length - 1]")))
        val timers: Int get() = num("timers.length")
        fun flush(): Int = num("flushTimers()")
        fun mutate() = eval("mutationCb([])")
        fun resize(h: Int) = eval("html.clientHeight = $h; innerHeight = $h; for (var i = 0; i < resizeListeners.length; i++) resizeListeners[i]();")
        fun close() = Context.exit()
    }

    private fun page(block: Page.() -> Unit) {
        val p = Page()
        try {
            p.block()
        } finally {
            p.close()
        }
    }

    private fun JSONObject.has() = getBoolean("hasBottomUI")
    private fun JSONObject.color(): String? = if (isNull("color")) null else getString("color")

    @Test
    fun `a fixed tab bar is found at install, in its own colour, tagged with the token`() = page {
        eval("hit = tab")
        install()
        assertEquals(1, sent)
        val m = last()
        assertEquals(token, m.getString("token"))
        assertTrue(m.has())
        assertEquals("rgb(103, 80, 164)", m.color())
        assertEquals(3, m.length())
        // Probed at (vw/2, vh-30).
        assertEquals("206,833", Context.toString(eval("document.lastProbe.join(',')")))
        // And its message passes the Kotlin-side validation.
        assertEquals(BottomUiReport(true, "rgb(103, 80, 164)"), parseBottomUiMessage(m.toString(), true, token))
    }

    @Test
    fun `a flex shell's in-flow nav counts when the document doesn't scroll`() = page {
        eval("nav.pos = 'static'; hit = tab")
        install()
        assertTrue(last().has())
    }

    @Test
    fun `an in-flow footer at the end of a scrolling document is not a nav`() = page {
        eval("nav.pos = 'static'; hit = tab; scrollHeight = 3000")
        install()
        assertFalse(last().has())
        // …but a sticky one is.
        eval("nav.pos = 'sticky'")
        assertEquals(1, kotlinProbe())
        assertTrue(last().has())
    }

    private fun Page.kotlinProbe(): Int {
        val before = sent
        eval("kotlinSays('${bottomUiProbeRequest(token)}')")
        return sent - before
    }

    @Test
    fun `iOS thresholds - anchoring, size, width, interactivity`() = page {
        eval("hit = tab")
        install()
        assertTrue(last().has())
        // Bottom too far above the viewport bottom (> 60 px).
        eval("nav.rect = { top: 740, bottom: 796, height: 56, width: 412 }")
        kotlinProbe(); assertFalse(last().has())
        // Bottom just inside the band.
        eval("nav.rect = { top: 747, bottom: 803, height: 56, width: 412 }")
        kotlinProbe(); assertTrue(last().has())
        // Too short.
        eval("nav.rect = { top: 824, bottom: 863, height: 39, width: 412 }")
        kotlinProbe(); assertFalse(last().has())
        // Too tall (> vh * 0.25).
        eval("nav.rect = { top: 600, bottom: 863, height: 263, width: 412 }")
        kotlinProbe(); assertFalse(last().has())
        // Too narrow (< vw * 0.5).
        eval("nav.rect = { top: 807, bottom: 863, height: 56, width: 200 }")
        kotlinProbe(); assertFalse(last().has())
        // Right size, nothing to tap.
        eval("nav.rect = { top: 807, bottom: 863, height: 56, width: 412 }; nav.interactive = false")
        kotlinProbe(); assertFalse(last().has())
    }

    @Test
    fun `a nav that leaves the horizontal centre free is not detected`() = page {
        // The probe point lands on the article, whose ancestors aren't navs.
        eval("hit = app")
        install()
        assertFalse(last().has())
    }

    @Test
    fun `colour falls back - ancestor, theme-color, body, html, none`() = page {
        eval("hit = tab; nav.bg = null; app.bg = 'rgb(1, 2, 3)'")
        install()
        assertEquals("rgb(1, 2, 3)", last().color())

        eval("app.bg = null; metas = [{ getAttribute: function (a) { return a === 'content' ? 'navy' : null; } }]")
        kotlinProbe(); assertEquals("rgb(0, 0, 128)", last().color())

        // A theme-color for another colour scheme is skipped.
        eval(
            """metas = [
              { getAttribute: function (a) { return a === 'media' ? '(prefers-color-scheme: dark)' : (a === 'content' ? '#112233' : null); } },
              { getAttribute: function (a) { return a === 'content' ? '#445566' : null; } }]""",
        )
        kotlinProbe(); assertEquals("rgb(68, 85, 102)", last().color())
        eval("mediaMatches['(prefers-color-scheme: dark)'] = true")
        kotlinProbe(); assertEquals("rgb(17, 34, 51)", last().color())

        eval("metas = []; body.bg = 'rgb(250, 250, 250)'")
        kotlinProbe(); assertEquals("rgb(250, 250, 250)", last().color())

        eval("body.bg = 'rgba(0, 0, 0, 0)'; html.bg = 'rgb(18, 18, 18)'")
        kotlinProbe(); assertEquals("rgb(18, 18, 18)", last().color())

        eval("html.bg = null")
        kotlinProbe(); assertNull(last().color())
        assertTrue(last().has())
    }

    @Test
    fun `translucent colours are used opaque, fully transparent ones are skipped`() = page {
        eval("hit = tab; nav.bg = 'rgba(10, 20, 30, 0.8)'")
        install()
        assertEquals("rgb(10, 20, 30)", last().color())
        eval("nav.bg = 'rgba(10, 20, 30, 0)'; app.bg = 'rgb(4, 5, 6)'")
        kotlinProbe(); assertEquals("rgb(4, 5, 6)", last().color())
    }

    @Test
    fun `reserving cannot make the nav disappear`() = page {
        // A tall-ish nav: 200 px is within 25% of the full 863 px viewport
        // but not of the 781 px one reserved mode leaves.
        eval("hit = tab; nav.rect = { top: 663, bottom: 863, height: 200, width: 412 }")
        install()
        assertTrue(last().has())
        // Reserved: the viewport is 82 px shorter; the fixed nav follows its bottom.
        eval("nav.rect = { top: 581, bottom: 781, height: 200, width: 412 }")
        resize(781)
        assertEquals(1, flush())
        kotlinProbe()
        assertTrue(last().has())
        // The probe point moved with the viewport bottom.
        assertEquals("206,751", Context.toString(eval("document.lastProbe.join(',')")))
    }

    @Test
    fun `unchanged answers are not re-sent, changes are`() = page {
        eval("hit = tab")
        install()
        mutate(); flush()
        resize(863); flush()
        assertEquals(1, sent)
        eval("nav.bg = 'rgb(0, 0, 0)'")
        mutate(); flush()
        assertEquals(2, sent)
        assertEquals("rgb(0, 0, 0)", last().color())
    }

    @Test
    fun `mutations are debounced into one probe, at least 250 ms out`() = page {
        eval("hit = tab")
        install()
        val readsBefore = num("reads")
        repeat(50) { mutate() }
        assertEquals(1, timers)
        assertTrue(num("timers[0].ms") >= 250)
        // The observer callback itself does no DOM work.
        assertEquals(readsBefore, num("reads"))
        assertEquals(1, flush())
        assertEquals(0, timers)
    }

    @Test
    fun `an idle page runs nothing after install`() = page {
        eval("hit = tab")
        install()
        // The first observer callbacks for the nav it just found.
        eval("for (var i = 0; i < observed.length; i++) observed[i].cb([])")
        flush()
        // Now idle: no timer is pending, nothing re-arms one.
        assertEquals(0, timers)
        assertEquals(0, flush())
        assertEquals(1, sent)
    }

    @Test
    fun `the found nav is watched, and its disappearance is noticed without a mutation`() = page {
        eval("hit = tab")
        install()
        assertEquals("resize,intersection", Context.toString(eval("observed.map(function (o) { return o.kind; }).join(',')")))
        assertTrue(eval("observed[0].el === nav") as Boolean)
        // The banner collapses (a style change outside the mutation filter).
        eval("nav.rect = { top: 863, bottom: 863, height: 0, width: 412 }; hit = app; observed[0].cb([])")
        flush()
        assertFalse(last().has())
        // Nothing left to watch.
        assertEquals(0, num("observed.length"))
    }

    @Test
    fun `a request with another token re-tags the reports, anything else is ignored`() = page {
        eval("hit = tab")
        install()
        assertEquals(1, kotlinProbe())
        // A new install for the same document: answered, under its token.
        eval("kotlinSays('probe ffff')")
        assertEquals(3, sent)
        assertEquals("ffff", last().getString("token"))
        // Not a probe request: nothing.
        eval("kotlinSays('probe'); kotlinSays('probe FFFF'); kotlinSays('probe ff ff'); kotlinSays(42); kotlinSays(null)")
        assertEquals(3, sent)
    }

    @Test
    fun `the channel object is off window before the page's first script`() = page {
        documentStart()
        assertTrue(eval("!('$channel' in window)") as Boolean)
        assertTrue(eval("Object.keys(window).indexOf('$channel') < 0") as Boolean)
        // It still works: the detector kept it.
        firstPaint()
        eval("hit = tab")
        kotlinProbe()
        assertTrue(last().has())
    }

    @Test
    fun `in a subframe it only removes the channel object`() = page {
        eval("top = {}")
        documentStart()
        assertTrue(eval("!('$channel' in window)") as Boolean)
        firstPaint()
        assertEquals(0, num("readies"))
        assertEquals(0, sent)
        assertEquals(0, num("channelListeners.length"))
        assertTrue(eval("mutationCb === null") as Boolean)
        // …and reports the page's say on a long-press (#84): the press may land in an iframe.
        assertEquals(1, num("contextMenuListeners.length"))
    }

    // ---- the page's say on a long-press (#84) -------------------------

    private fun Page.verdicts(): String = Context.toString(eval("sent.filter(function (s) { return /^contextmenu /.test(s); }).join('|')"))

    @Test
    fun `a let-through long-press is reported, from document start, as a capture listener`() = page {
        documentStart()
        assertEquals(1, num("contextMenuListeners.length"))
        assertTrue(eval("contextMenuListeners[0].capture") as Boolean)
        eval("pressAndHold({ isTrusted: true, defaultPrevented: false }, false)")
        assertEquals(CONTEXT_MENU_ALLOWED, verdicts())
        assertEquals(true, parseContextMenuVerdict(verdicts()))
    }

    @Test
    fun `the outcome is read after every page handler has run`() = page {
        documentStart()
        eval("pressAndHold({ isTrusted: true, defaultPrevented: false }, true)")
        assertEquals(CONTEXT_MENU_KEPT, verdicts())
        assertEquals(false, parseContextMenuVerdict(verdicts()))
    }

    @Test
    fun `a synthetic contextmenu is ignored`() = page {
        documentStart()
        eval("pressAndHold({ isTrusted: false, defaultPrevented: false }, false)")
        assertEquals("", verdicts())
    }

    @Test
    fun `a page replacing setTimeout later can't see or stop the report`() = page {
        documentStart()
        eval("var pageTimers = 0; setTimeout = function () { pageTimers++; }")
        eval("pressAndHold({ isTrusted: true, defaultPrevented: false }, false)")
        assertEquals(CONTEXT_MENU_ALLOWED, verdicts())
        assertEquals(0, num("pageTimers"))
    }

    @Test
    fun `a verdict is reported in a subframe too, and nothing else`() = page {
        eval("top = {}")
        documentStart()
        eval("pressAndHold({ isTrusted: true, defaultPrevented: false }, false)")
        assertEquals(CONTEXT_MENU_ALLOWED, verdicts())
        assertEquals(1, sent)
    }

    // ---- input in the top document (#85) ------------------------------

    private fun Page.inputs(): Int = num("sent.filter(function (s) { return s.indexOf('$TOP_DOCUMENT_INPUT') === 0; }).length")

    @Test
    fun `trusted input in the top document is reported at once, from document start`() = page {
        documentStart()
        assertEquals(3, num("inputListeners.length"))
        assertTrue(eval("inputListeners.every(function (l) { return l.capture; })") as Boolean)
        eval("input('pointerdown', true)")
        // Synchronously, not a task later: it has to beat the navigation.
        assertEquals(1, inputs())
        assertEquals(0, timers)
        eval("input('keydown', true); input('click', true)")
        assertEquals(3, inputs())
        // Each says how long ago its event happened, on the page's clock.
        assertTrue(eval("sent.every(function (s) { return s === '$TOP_DOCUMENT_INPUT 7'; })") as Boolean)
    }

    @Test
    fun `the page can't skew the input's age after document start`() = page {
        documentStart()
        eval("performance.now = function () { return 1e9; }")
        eval("Object.defineProperty(Event.prototype, 'timeStamp', { get: function () { return 0; } })")
        eval("input('pointerdown', true)")
        assertEquals(1, inputs())
        assertEquals(7L, parseTopDocumentInput(eval("sent[sent.length - 1]").toString()))
    }

    @Test
    fun `synthetic input is not reported`() = page {
        documentStart()
        eval("input('pointerdown', false); input('click', false); input('keydown', false)")
        assertEquals(0, inputs())
    }

    @Test
    fun `a subframe never reports input`() = page {
        eval("top = {}")
        documentStart()
        assertEquals(0, num("inputListeners.length"))
        eval("input('pointerdown', true); input('click', true)")
        assertEquals(0, inputs())
    }

    @Test
    fun `before first paint it only says ready`() = page {
        eval("hit = tab")
        documentStart()
        assertEquals(1, num("readies"))
        assertEquals(0, sent)
        assertEquals(0, timers)
        assertEquals(0, num("reads"))
        assertEquals(0, num("windowListeners"))
        // The one listener it adds at document start: the long-press verdict (#84).
        assertEquals(1, num("contextMenuListeners.length"))
        assertEquals(0, num("docListeners.length"))
        assertEquals(0, num("observed.length"))
        assertTrue(eval("mutationCb === null") as Boolean)
        // First paint: it starts, and reports.
        firstPaint()
        assertEquals(1, sent)
        assertTrue(eval("mutationCb !== null") as Boolean)
        assertEquals(1, num("resizeListeners.length"))
        // A second request doesn't start it twice.
        kotlinProbe()
        assertEquals(1, num("resizeListeners.length"))
        assertEquals(1, num("docListeners.length"))
        assertEquals(1, num("readies"))
    }

    @Test
    fun `nothing is written to the page and no global is added`() = page {
        val globals = "Object.keys(window).sort().join(',')"
        // The one change: the platform's channel object is gone.
        val before = Context.toString(eval("Object.keys(window).filter(function (k) { return k !== '$channel'; }).sort().join(',')"))
        eval("hit = tab")
        install()
        mutate(); flush()
        assertEquals(before, Context.toString(eval(globals)))
        assertEquals(0, num("domWrites"))
        assertFalse(bottomUiDetectorJs(channel).contains("freedom", ignoreCase = true))
    }

    @Test
    fun `channel names are fresh plain identifiers`() {
        val names = List(50) { newBottomUiChannelName() }
        assertTrue(names.all { Regex("[a-z]{16}").matches(it) })
        assertEquals(50, names.toSet().size)
        names.forEach { bottomUiDetectorJs(it) }
    }

    @Test
    fun `a detector installed before body exists reports once body arrives`() = page {
        // Started early (no <body> yet): the forced first probe can't
        // answer and posts nothing.
        eval("document.body = null; hit = tab")
        install()
        assertEquals(0, sent)
        // <body> is parsed in: the MutationObserver wakes the detector and
        // the owed report goes out without Kotlin having to ask.
        eval("document.body = body")
        mutate(); flush()
        assertEquals(1, sent)
        assertTrue(last().has())
    }

    @Test
    fun `an owed report is sent even when it matches the last one`() = page {
        eval("hit = app")
        install()
        assertEquals(1, sent)
        assertFalse(last().has())
        // Kotlin asks while <body> is momentarily gone (document.open()):
        // no answer, so the report is owed…
        eval("document.body = null")
        kotlinProbe()
        assertEquals(1, sent)
        // …and the next event-driven probe sends it, unchanged answer and all.
        eval("document.body = body")
        mutate(); flush()
        assertEquals(2, sent)
        // Settled again: an unchanged answer is not re-sent.
        mutate(); flush()
        assertEquals(2, sent)
    }

    @Test
    fun `with no documentElement at install, the observer attaches on readystatechange`() = page {
        eval("document.documentElement = null; document.body = null; hit = tab")
        install()
        assertEquals(0, sent)
        assertTrue(eval("mutationCb === null") as Boolean)
        eval("document.documentElement = html; document.body = body; readyStateChanges()")
        // Attached now, and the state change itself probes.
        assertTrue(eval("mutationCb !== null") as Boolean)
        assertEquals(1, flush())
        assertEquals(1, sent)
        assertTrue(last().has())
        // A second state change doesn't attach a second observer.
        eval("var firstCb = mutationCb; readyStateChanges()")
        assertTrue(eval("mutationCb === firstCb") as Boolean)
    }

    @Test
    fun `no channel object, no detector`() = page {
        eval("delete window.$channel; hit = tab")
        install()
        assertEquals(0, sent)
        assertEquals(0, num("contextMenuListeners.length"))
        assertEquals(0, timers)
        assertTrue(eval("mutationCb === null") as Boolean)
    }

    @Test
    fun `the channel name must be letters so it can't break out of the script`() {
        try {
            bottomUiDetectorJs("abcdefgh'; alert(1); '")
            throw AssertionError("accepted a non-letter channel name")
        } catch (_: IllegalArgumentException) {
        }
    }
}
