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
          querySelectorAll: function (sel) { return sel === 'meta[name="theme-color" i]' ? metas : []; },
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
          if (t === 'playing' || t === 'pagehide' || t === 'pageshow') { mediaListeners.push({ t: t, f: f, capture: c === true }); return; }
          windowListeners++; if (t === 'resize') resizeListeners.push(f);
        }
        // Media (#91): elements that dispatch to their own listeners, and a
        // `playing` that reaches window only while [connected].
        var mediaListeners = [];
        function EventTarget() {}
        EventTarget.prototype.addEventListener = function (t, f) {
          this.ls = this.ls || [];
          for (var i = 0; i < this.ls.length; i++) if (this.ls[i].t === t && this.ls[i].f === f) return;
          this.ls.push({ t: t, f: f });
        };
        // Chromium's decoded-audio counter; an element has sound unless the test says otherwise.
        function HTMLMediaElement() {}
        Object.defineProperty(HTMLMediaElement.prototype, 'webkitAudioDecodedByteCount', { configurable: true,
          get: function () { return this.decoded === undefined ? 4096 : this.decoded; } });
        function media() { var m = new EventTarget(); m.paused = true; m.ended = false; m.muted = false; m.volume = 1; m.connected = true; m.readyState = 4; return m; }
        function fire(m, t) {
          var e = { type: t, target: m, currentTarget: m, isTrusted: true };
          if (m.connected) for (var i = 0; i < mediaListeners.length; i++) if (mediaListeners[i].t === t) mediaListeners[i].f(e);
          var ls = m.ls || [];
          for (var j = 0; j < ls.length; j++) if (ls[j].t === t) ls[j].f(e);
        }
        function play(m) { m.paused = false; fire(m, 'playing'); }
        function pause(m) { m.paused = true; fire(m, 'pause'); }
        function win(t, e) { for (var i = 0; i < mediaListeners.length; i++) if (mediaListeners[i].t === t) mediaListeners[i].f(e || {}); }
        // A trusted long-press's `contextmenu`: our listener, then the page's handlers, then tasks.
        function pressAndHold(e, pageCancels) {
          for (var i = 0; i < contextMenuListeners.length; i++) contextMenuListeners[i].f(e);
          if (pageCancels) e.defaultPrevented = true;
          flushTimers();
        }
        // The Navigation API (#348 R5-F1): `navigation` fires `navigate` with
        // the destination's URL, read through native getters.
        function NavigateEvent() {}
        Object.defineProperty(NavigateEvent.prototype, 'destination', { configurable: true, get: function () { return this._d; } });
        Object.defineProperty(NavigateEvent.prototype, 'navigationType', { configurable: true, get: function () { return this._ty; } });
        Object.defineProperty(NavigateEvent.prototype, 'hashChange', { configurable: true, get: function () { return this._h; } });
        Object.defineProperty(NavigateEvent.prototype, 'formData', { configurable: true, get: function () { return this._f; } });
        function NavigationDestination() {}
        Object.defineProperty(NavigationDestination.prototype, 'url', { configurable: true, get: function () { return this._u; } });
        Object.defineProperty(NavigationDestination.prototype, 'sameDocument', { configurable: true, get: function () { return this._s; } });
        function Navigation() {}
        Navigation.prototype = new EventTarget();
        Object.defineProperty(Navigation.prototype, 'transition', { configurable: true, get: function () { return this._t; } });
        var navigation = new Navigation(); navigation._t = null;
        function navFire(t, e) { var ls = navigation.ls || []; for (var i = 0; i < ls.length; i++) if (ls[i].t === t) ls[i].f(e); }
        // A navigation of this document: our listener, then the page's handlers, then tasks.
        // o: { type: 'push' (default) | 'replace' | 'reload' | 'traverse', same: a
        // same-document one (pushState etc.), hash: a fragment change, intercept:
        // 'now' (the page intercept()s it, committed at once) | 'held' (its commit waits),
        // post: a POST form's submission (formData set) }.
        function navigate(url, trusted, pageCancels, o) {
          o = o || {};
          var dest = new NavigationDestination(); dest._u = url; dest._s = !!(o.same || o.hash);
          var e = new NavigateEvent(); e._d = dest; e.isTrusted = trusted; e.defaultPrevented = false;
          e._ty = o.type || 'push'; e._h = !!o.hash; e._f = o.post ? {} : null;
          navFire('navigate', e);
          if (pageCancels) e.defaultPrevented = true;
          if (dest._s || o.intercept === 'now') navFire('currententrychange', {});
          if (o.intercept === 'held') navigation._t = {};
          flushTimers();
          navigation._t = null;
        }
        var mutationObs = null;
        function mutationCbOpts() { return mutationObs.opts; }
        function MutationObserver(cb) { mutationCb = cb; mutationObs = this; this.observe = function (n, o) { this.target = n; this.opts = o; }; }
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
    fun `the attribute filter's iterator calls nothing the page can replace after document start`() = page {
        // Rhino has no Symbol: stand one in, so the filter is the iterable Chromium gets.
        eval("Symbol = function () {}; Symbol.iterator = '@@iterator'")
        eval("hit = tab")
        documentStart()
        // The page, after document start: every Object.create call is seen and refused.
        eval("var seen = 0; Object.create = function () { seen++; throw new Error('page saw it'); }")
        firstPaint()
        // observe() reads the sequence by iterating it — twice, as a re-attach would.
        val walk = "(function () { var it = mutationCbOpts().attributeFilter['@@iterator'](), r, out = [];" +
            " while (!(r = it.next()).done) out.push(r.value); return out.join(','); })()"
        repeat(2) {
            assertEquals("class,style,hidden,open,content,media,name", Context.toString(eval(walk)))
        }
        assertEquals(0, num("seen"))
    }

    @Test
    fun `a meta change sends Kotlin the theme colour, debounced, and nothing else does`() = page {
        eval("hit = tab")
        install()
        val sentStr = { Context.toString(eval("sent.join('|')")) }
        // It watches the attributes a theme-color change comes through.
        assertEquals(
            "class,style,hidden,open,content,media,name",
            Context.toString(eval("mutationCbOpts().attributeFilter.join(',')")),
        )
        // A mutation elsewhere: no ping.
        eval("mutationCb([{ type: 'attributes', target: { nodeName: 'DIV' } }, { type: 'childList', addedNodes: [{ nodeName: 'P' }], removedNodes: [] }])")
        flush()
        assertEquals(1, sent)
        // A route setting its colour after a fetch: `content` changes, twice in one debounce.
        eval("mutationCb([{ type: 'attributes', target: { nodeName: 'META' } }])")
        eval("mutationCb([{ type: 'attributes', target: { nodeName: 'META' } }])")
        assertEquals(1, timers)
        flush()
        assertEquals(2, sent)
        val ping = Context.toString(eval("sent[1]"))
        assertEquals("theme $token none", ping)
        assertEquals(ThemeColorReport(null), parseThemeColorReport(ping, isMainFrame = true, expectedToken = token))
        assertNull(parseThemeColorReport(ping, isMainFrame = false, expectedToken = token))
        assertNull(parseThemeColorReport(ping, isMainFrame = true, expectedToken = "ffff"))
        assertNull(parseThemeColorReport(ping, isMainFrame = true, expectedToken = null))
        // A tag added, a tag removed, a `<head>` swapped: one ping each.
        eval("mutationCb([{ type: 'childList', addedNodes: [{ nodeName: 'META' }], removedNodes: [] }])"); flush()
        eval("mutationCb([{ type: 'childList', addedNodes: [], removedNodes: [{ nodeName: 'META' }] }])"); flush()
        eval("mutationCb([{ type: 'childList', addedNodes: [{ nodeName: 'HEAD' }], removedNodes: [] }])"); flush()
        assertEquals(5, sent)
        // …and it's no probe report: the bottom-UI answer is unchanged, so none was sent.
        assertNull(parseBottomUiMessage(ping, true, token))
        assertTrue(sentStr().split('|').drop(1).all { it == ping })
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

    // ---- the renderer sync after an input (#348) ----------------------

    private fun Page.synced(): String = Context.toString(eval("sent.filter(function (s) { return /^synced /.test(s); }).join('|')"))

    @Test
    fun `a sync is echoed a task and the settle time later, before first paint too`() = page {
        documentStart()
        eval("kotlinSays('${inputSyncRequest(7, 400)}')")
        assertEquals("", synced()) // not at once: input queued ahead of it runs first
        assertEquals(1, flush())
        // Then it waits out a tap held back for a double tap (R2-F1).
        assertEquals("", synced())
        assertEquals(1, timers)
        assertEquals(400, num("timers[0].ms"))
        flush()
        assertEquals("synced 7", synced())
        assertEquals(7, parseInputSynced(synced()))
        // Anything else in that shape isn't echoed.
        eval("kotlinSays('sync x 1'); kotlinSays('sync 1234567890 1'); kotlinSays('sync 7'); kotlinSays('sync 7 12345')")
        flush()
        flush()
        assertEquals("synced 7", synced())
        assertEquals(null, parseInputSynced("synced 7 "))
    }

    @Test
    fun `the settle time is the double-tap timeout plus margin`() {
        assertEquals(300 + INPUT_SYNC_DELAY_MS, inputSyncSettleMs(300))
        assertEquals(INPUT_SYNC_DELAY_MS, inputSyncSettleMs(-1))
        assertEquals(5_000 + INPUT_SYNC_DELAY_MS, inputSyncSettleMs(Int.MAX_VALUE))
    }

    @Test
    fun `a subframe's detector echoes no sync`() = page {
        eval("top = {}")
        documentStart()
        assertEquals(0, num("channelListeners.length"))
    }

    // ---- navigations the top document started (#348 R5-F1) ----------

    private fun Page.navigations(): String =
        Context.toString(eval("sent.filter(function (s) { return /^navigate /.test(s); }).join('|')"))

    @Test
    fun `a navigation the top document starts is reported, from document start, once the page let it go`() = page {
        documentStart()
        eval("navigate('http://localhost:8710/priced?a=1#x', true, false)")
        assertEquals("navigate http://localhost:8710/priced?a=1#x", navigations())
        assertEquals("http://localhost:8710/priced?a=1#x", parseTopDocumentNavigate(navigations()))
        // One the page cancelled, or a synthetic event, isn't.
        eval("navigate('http://localhost:8710/b', true, true); navigate('http://localhost:8710/c', false, false)")
        assertEquals("navigate http://localhost:8710/priced?a=1#x", navigations())
    }

    @Test
    fun `a same-document navigation, a reload or a Back-Forward is never reported (R6-F1)`() = page {
        documentStart()
        // pushState / replaceState, a fragment, and one the page intercept()ed
        // (committed at once, or with its commit held back) never reach
        // shouldOverrideUrlLoading to claim their word.
        eval("navigate('http://localhost:8710/priced', true, false, { same: true })")
        eval("navigate('http://localhost:8710/priced', true, false, { type: 'replace', same: true })")
        eval("navigate('http://localhost:8710/#x', true, false, { hash: true })")
        eval("navigate('http://localhost:8710/priced', true, false, { intercept: 'now' })")
        eval("navigate('http://localhost:8710/priced', true, false, { intercept: 'held' })")
        // Nor do a reload or a traversal.
        eval("navigate('http://localhost:8710/priced', true, false, { type: 'reload' })")
        eval("navigate('http://localhost:8710/priced', true, false, { type: 'traverse' })")
        assertEquals("", navigations())
        // A cross-document push or replace still is.
        eval("navigate('http://localhost:8710/a', true, false, { type: 'replace' })")
        eval("navigate('http://localhost:8710/b', true, false)")
        assertEquals("navigate http://localhost:8710/a|navigate http://localhost:8710/b", navigations())
    }

    @Test
    fun `a POST form's submission is never reported, a GET form's is (382 R1-M1)`() = page {
        documentStart()
        // WebView never calls shouldOverrideUrlLoading for a POST navigation,
        // so its word would sit unclaimed.
        eval("navigate('http://localhost:8710/priced', true, false, { post: true })")
        eval("navigate('http://localhost:8710/priced', true, false, { post: true, type: 'replace' })")
        assertEquals("", navigations())
        eval("navigate('http://localhost:8710/priced?q=1', true, false)")
        assertEquals("navigate http://localhost:8710/priced?q=1", navigations())
    }

    @Test
    fun `a page that replaces the formData getter later can't make a POST one reported`() = page {
        documentStart()
        eval("Object.defineProperty(NavigateEvent.prototype, 'formData', { get: function () { return null; } })")
        eval("navigate('http://localhost:8710/priced', true, false, { post: true })")
        assertEquals("", navigations())
    }

    @Test
    fun `a page that replaces the navigation getters later can't make a same-document one reported`() = page {
        documentStart()
        eval(
            "Object.defineProperty(NavigationDestination.prototype, 'sameDocument', { get: function () { return false; } });" +
                "Object.defineProperty(NavigateEvent.prototype, 'navigationType', { get: function () { return 'push'; } });" +
                "Object.defineProperty(Navigation.prototype, 'transition', { get: function () { return null; } })",
        )
        eval("navigate('http://localhost:8710/priced', true, false, { same: true, type: 'replace' })")
        eval("navigate('http://localhost:8710/priced', true, false, { type: 'reload' })")
        eval("navigate('http://localhost:8710/priced', true, false, { intercept: 'held' })")
        assertEquals("", navigations())
    }

    @Test
    fun `a page that replaces the getters later can't change the reported URL`() = page {
        documentStart()
        eval("Object.defineProperty(NavigationDestination.prototype, 'url', { get: function () { return 'http://evil.example/'; } })")
        eval("navigate('http://localhost:8710/priced', true, false)")
        assertEquals("navigate http://localhost:8710/priced", navigations())
    }

    @Test
    fun `a subframe's detector reports no navigation`() = page {
        eval("top = {}")
        documentStart()
        eval("navigate('http://localhost:8710/priced', true, false)")
        assertEquals("", navigations())
    }

    @Test
    fun `only an http(s) URL of bounded length parses as a navigation report`() {
        assertEquals("https://a.example/x", parseTopDocumentNavigate("navigate https://a.example/x"))
        assertEquals(null, parseTopDocumentNavigate("navigate javascript:alert(1)"))
        assertEquals(null, parseTopDocumentNavigate("navigate https://a.example/x y"))
        assertEquals(null, parseTopDocumentNavigate("navigate https://" + "a".repeat(TOP_DOCUMENT_NAVIGATE_MAX)))
        assertEquals(null, parseTopDocumentNavigate("navigated https://a.example/x"))
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

    // ---- audible media (#91) -------------------------------------------

    private fun Page.audio(): String = Context.toString(eval("sent.filter(function (s) { return /^audio /.test(s); }).join('|')"))

    @Test
    fun `media becoming audible and falling silent is reported once each, from document start`() = page {
        documentStart()
        assertTrue(eval("mediaListeners.every(function (l) { return l.capture; })") as Boolean)
        eval("var v = media(); play(v)")
        assertEquals(AUDIO_AUDIBLE, audio())
        assertEquals(true, parseAudioReport(audio()))
        // A second `playing` (after buffering) says nothing new.
        eval("fire(v, 'playing')")
        assertEquals(AUDIO_AUDIBLE, audio())
        eval("pause(v)")
        assertEquals("$AUDIO_AUDIBLE|$AUDIO_SILENT", audio())
        assertEquals(false, parseAudioReport(AUDIO_SILENT))
    }

    @Test
    fun `muted or zero-volume media isn't audible until the page turns it up`() = page {
        documentStart()
        eval("var v = media(); v.muted = true; play(v)")
        assertEquals("", audio())
        eval("v.muted = false; fire(v, 'volumechange')")
        assertEquals(AUDIO_AUDIBLE, audio())
        eval("v.volume = 0; fire(v, 'volumechange')")
        assertEquals("$AUDIO_AUDIBLE|$AUDIO_SILENT", audio())
    }

    @Test
    fun `the frame stays audible while any of its elements is`() = page {
        documentStart()
        eval("var a = media(), b = media(); play(a); play(b); pause(a)")
        assertEquals(AUDIO_AUDIBLE, audio())
        eval("b.ended = true; b.paused = true; fire(b, 'ended')")
        assertEquals("$AUDIO_AUDIBLE|$AUDIO_SILENT", audio())
    }

    @Test
    fun `an element detached mid-play is still heard when it pauses`() = page {
        documentStart()
        eval("var v = media(); play(v); v.connected = false; pause(v)")
        assertEquals("$AUDIO_AUDIBLE|$AUDIO_SILENT", audio())
    }

    @Test
    fun `an element paused by being detached is heard again when it plays detached`() = page {
        documentStart()
        eval("var v = media(); play(v); v.connected = false; pause(v); play(v)")
        assertEquals("$AUDIO_AUDIBLE|$AUDIO_SILENT|$AUDIO_AUDIBLE", audio())
        eval("pause(v)")
        assertEquals("$AUDIO_AUDIBLE|$AUDIO_SILENT|$AUDIO_AUDIBLE|$AUDIO_SILENT", audio())
        // A synthetic `playing` dispatched on the detached element adds nothing.
        eval("v.paused = false; v.ls.forEach(function (l) { if (l.t === 'playing') l.f({ type: 'playing', target: v, currentTarget: v, isTrusted: false }); })")
        assertEquals("$AUDIO_AUDIBLE|$AUDIO_SILENT|$AUDIO_AUDIBLE|$AUDIO_SILENT", audio())
    }

    @Test
    fun `leaving the document reports silence, and a synthetic playing is ignored`() = page {
        documentStart()
        eval("var v = media(); v.paused = false; fire({ connected: true, ls: [] }, 'playing')")
        eval("mediaListeners.forEach(function (l) { if (l.t === 'playing') l.f({ target: v, isTrusted: false }); })")
        assertEquals("", audio())
        eval("play(v); win('pagehide')")
        assertEquals("$AUDIO_AUDIBLE|$AUDIO_SILENT", audio())
        // Back from the back/forward cache, still playing.
        eval("win('pageshow', { persisted: true })")
        assertEquals("$AUDIO_AUDIBLE|$AUDIO_SILENT|$AUDIO_AUDIBLE", audio())
    }

    @Test
    fun `a stream starved of data isn't audible until it plays again`() = page {
        documentStart()
        eval("var v = media(); play(v); v.readyState = 2; fire(v, 'waiting')")
        assertEquals("$AUDIO_AUDIBLE|$AUDIO_SILENT", audio())
        // A stall with data still buffered keeps playing, and stays audible.
        eval("v.readyState = 4; fire(v, 'playing'); fire(v, 'stalled')")
        assertEquals("$AUDIO_AUDIBLE|$AUDIO_SILENT|$AUDIO_AUDIBLE", audio())
        // Detached while starved: its own `playing` still brings it back.
        eval("v.readyState = 1; fire(v, 'waiting'); v.connected = false; v.readyState = 4; fire(v, 'playing')")
        assertEquals("$AUDIO_AUDIBLE|$AUDIO_SILENT|$AUDIO_AUDIBLE|$AUDIO_SILENT|$AUDIO_AUDIBLE", audio())
    }

    @Test
    fun `an audible frame looks again on a timer, so a silence no event reports is still sent`() = page {
        documentStart()
        eval("var v = media(); play(v)")
        assertEquals(1, timers)
        assertEquals(AUDIO_AUDIBLE, audio())
        assertEquals(AUDIO_RECHECK_MS, num("timers[0].ms"))
        // Still playing: the check re-arms itself, and says so again (a
        // Kotlin that forgot the frame on a main-frame ready gets it back).
        assertEquals(1, flush())
        assertEquals(1, timers)
        assertEquals("$AUDIO_AUDIBLE|$AUDIO_AUDIBLE", audio())
        // `document.open()`: every listener is erased and the element,
        // removed from the document, pauses without anyone hearing it.
        eval("mediaListeners = []; v.ls = []; v.paused = true")
        flush()
        assertEquals("$AUDIO_AUDIBLE|$AUDIO_AUDIBLE|$AUDIO_SILENT", audio())
        // Silent now: nothing runs any more.
        assertEquals(0, timers)
    }

    @Test
    fun `a video with no audio track isn't audible, and stops being looked at`() = page {
        documentStart()
        eval("var v = media(); v.decoded = 0; play(v)")
        assertEquals("", audio())
        // Looked at again a few times, in case its sound isn't decoded yet…
        assertEquals(AUDIO_SOUND_MS, num("timers[0].ms"))
        var looks = 0
        while (flush() > 0) looks++
        assertEquals(AUDIO_SOUND_TRIES, looks)
        // …then nothing runs, and nothing was said.
        assertEquals(0, timers)
        assertEquals("", audio())
        // A later event on it looks again (new source, now with sound).
        eval("v.decoded = 100; fire(v, 'playing')")
        assertEquals(AUDIO_AUDIBLE, audio())
    }

    @Test
    fun `sound decoded shortly after playing is picked up on a quick look`() = page {
        documentStart()
        eval("var v = media(); v.decoded = 0; play(v)")
        assertEquals("", audio())
        eval("v.decoded = 512")
        flush()
        assertEquals(AUDIO_AUDIBLE, audio())
        assertEquals(AUDIO_RECHECK_MS, num("timers[0].ms"))
    }

    @Test
    fun `a page shadowing the decoded-byte counter later can't make a silent video audible`() = page {
        documentStart()
        eval("Object.defineProperty(HTMLMediaElement.prototype, 'webkitAudioDecodedByteCount', { get: function () { return 1; } })")
        eval("var v = media(); v.decoded = 0; play(v)")
        assertEquals("", audio())
    }

    @Test
    fun `a subframe reports its audio too`() = page {
        eval("top = {}")
        documentStart()
        eval("var v = media(); play(v)")
        assertEquals(AUDIO_AUDIBLE, audio())
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
        // Each says which event it was and how long ago it happened, on
        // the page's clock.
        assertEquals(
            "$TOP_DOCUMENT_INPUT pointerdown 7,$TOP_DOCUMENT_INPUT keydown 7,$TOP_DOCUMENT_INPUT click 7",
            eval("sent.join(',')").toString(),
        )
    }

    @Test
    fun `the page can't skew the input's age after document start`() = page {
        documentStart()
        eval("performance.now = function () { return 1e9; }")
        eval("Object.defineProperty(Event.prototype, 'timeStamp', { get: function () { return 0; } })")
        eval("input('pointerdown', true)")
        assertEquals(1, inputs())
        assertEquals(TopDocumentInput(7L, isClick = false), parseTopDocumentInput(eval("sent[sent.length - 1]").toString()))
    }

    @Test
    fun `the page can't pass a keydown off as a click`() = page {
        documentStart()
        eval("Object.defineProperty(Event.prototype, 'type', { get: function () { return 'click'; } })")
        eval("input('keydown', true)")
        assertEquals(TopDocumentInput(7L, isClick = false), parseTopDocumentInput(eval("sent[sent.length - 1]").toString()))
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

    @Test
    fun `Kotlin's theme-colour ask is answered with the current token only, once started`() = page {
        eval("hit = tab; metas = [{ getAttribute: function (a) { return a === 'content' ? 'navy' : null; } }]")
        documentStart()
        // Before the start at first paint: no answer.
        eval("kotlinSays('${themeColorRequest(token)}')")
        assertEquals(0, sent)
        firstPaint()
        assertEquals(1, sent)
        eval("kotlinSays('${themeColorRequest(token)}')")
        assertEquals(2, sent)
        val answer = Context.toString(eval("sent[1]"))
        assertEquals("theme $token rgb(0, 0, 128)", answer)
        assertEquals(ThemeColorReport(0xFF000080.toInt()), parseThemeColorReport(answer, true, token))
        // Another document's ask: silence.
        eval("kotlinSays('${themeColorRequest("ffff")}')")
        assertEquals(2, sent)
    }

    @Test
    fun `the theme-colour read goes only through functions saved at document start`() = page {
        // Real-shaped prototypes, as the page would find them.
        eval(
            """
            function Element() {}
            Element.prototype.getAttribute = function (n) { return this.attrs[n] === undefined ? null : this.attrs[n]; };
            function meta(attrs) { var m = Object.create(Element.prototype); m.attrs = attrs; return m; }
            function MediaQueryList(q) { this.q = q; }
            Object.defineProperty(MediaQueryList.prototype, 'matches', { configurable: true, get: function () { return !!mediaMatches[this.q]; } });
            matchMedia = function (q) { return new MediaQueryList(q); };
            metas = [meta({ media: '(prefers-color-scheme: dark)', content: '#112233' }), meta({ content: 'navy' })];
            """,
        )
        documentStart()
        // Then the page wraps everything the read uses, counting calls.
        // (Not `RegExp.prototype.test`: the fake canvas's own setter uses it.)
        eval(
            """
            var seen = [];
            function spy(o, n) { var f = o[n]; o[n] = function () { seen.push(n); return f.apply(this, arguments); }; }
            spy(document, 'querySelectorAll'); spy(document, 'createElement'); spy(window, 'matchMedia');
            spy(Element.prototype, 'getAttribute'); spy(RegExp.prototype, 'exec');
            spy(window, 'parseInt'); spy(window, 'parseFloat'); spy(Math, 'round');
            Object.defineProperty(MediaQueryList.prototype, 'matches', { get: function () { seen.push('matches'); return !!mediaMatches[this.q]; } });
            var realCall = Function.prototype.call;
            Function.prototype.call = function () { seen.push('call'); return realCall.apply(this, arguments); };
            """,
        )
        firstPaint()
        eval("seen = []")
        eval("kotlinSays('${themeColorRequest(token)}')")
        assertEquals("theme $token rgb(0, 0, 128)", Context.toString(eval("sent[sent.length - 1]")))
        eval("mediaMatches['(prefers-color-scheme: dark)'] = true")
        eval("kotlinSays('${themeColorRequest(token)}')")
        assertEquals("theme $token rgb(17, 34, 51)", Context.toString(eval("sent[sent.length - 1]")))
        // Not one of the page's functions saw either read.
        assertEquals("", Context.toString(eval("Function.prototype.call = realCall; seen.join(',')")))
    }

    // A page's re-issue of its own navigation (#180, R5-F3).

    private val anchors = """
        function HTMLElement() {}
        HTMLElement.prototype.click = function () { clicked.push({ href: this._href, policy: this._policy, target: this._target, connected: false }); };
        function HTMLAnchorElement() {}
        HTMLAnchorElement.prototype = Object.create(HTMLElement.prototype);
        ['href', 'referrerPolicy', 'target'].forEach(function (n) {
          Object.defineProperty(HTMLAnchorElement.prototype, n, { configurable: true,
            get: function () { return this['_' + ({ href: 'href', referrerPolicy: 'policy', target: 'target' })[n]]; },
            set: function (v) { this['_' + ({ href: 'href', referrerPolicy: 'policy', target: 'target' })[n]] = v; } });
        });
        var clicked = [];
        var fakeCreate = document.createElement;
        document.createElement = function (t) { return t === 'a' ? new HTMLAnchorElement() : fakeCreate(t); };
    """

    @Test
    fun `Kotlin's re-issue ask clicks a detached link, with the current token only`() = page {
        eval(anchors)
        eval("hit = tab")
        documentStart()
        val ask = pageReissueRequest(token, "http://127.0.0.1:8700/meet?x=1")!!
        // Before the start at first paint: nothing.
        eval("kotlinSays('$ask')")
        assertEquals(0, num("clicked.length"))
        firstPaint()
        eval("kotlinSays('$ask')")
        assertEquals(1, num("clicked.length"))
        assertEquals(
            "http://127.0.0.1:8700/meet?x=1 origin _self",
            Context.toString(eval("[clicked[0].href, clicked[0].policy, clicked[0].target].join(' ')")),
        )
        // Another document's token, or a non-http address: nothing.
        eval("kotlinSays('${pageReissueRequest("ffff", "http://127.0.0.1:8700/meet")}')")
        eval("kotlinSays('go $token javascript:alert(1)')")
        assertEquals(1, num("clicked.length"))
        // Nor is anything posted back for it.
        assertEquals(1, sent)
    }

    @Test
    fun `the re-issue goes only through functions saved at document start`() = page {
        eval(anchors)
        eval("hit = tab")
        documentStart()
        // Then the page wraps everything the re-issue uses.
        eval(
            """
            var seen = [];
            function spy(o, n) { var f = o[n]; o[n] = function () { seen.push(n); return f.apply(this, arguments); }; }
            spy(document, 'createElement'); spy(HTMLElement.prototype, 'click'); spy(RegExp.prototype, 'exec');
            ['href', 'referrerPolicy', 'target'].forEach(function (n) {
              Object.defineProperty(HTMLAnchorElement.prototype, n, { configurable: true,
                get: function () { seen.push('get ' + n); }, set: function () { seen.push('set ' + n); } });
            });
            var realCall = Function.prototype.call;
            Function.prototype.call = function () { seen.push('call'); return realCall.apply(this, arguments); };
            """,
        )
        firstPaint()
        eval("seen = []")
        eval("kotlinSays('${pageReissueRequest(token, "http://127.0.0.1:8700/meet")}')")
        assertEquals("http://127.0.0.1:8700/meet", Context.toString(eval("clicked[clicked.length - 1].href")))
        assertEquals("", Context.toString(eval("Function.prototype.call = realCall; seen.join(',')")))
    }

    @Test
    fun `without the natives it needs, there is no re-issue`() = page {
        // No HTMLAnchorElement: nothing to build the link from untouched.
        eval("hit = tab")
        install()
        eval("kotlinSays('${pageReissueRequest(token, "http://127.0.0.1:8700/meet")}')")
        assertEquals(1, sent)
    }

    // A started detector goes only through natives saved at document start (#146).

    /** Real-shaped prototypes for everything the probe, the start and the observers touch. */
    private val protoDom = """
        function getter(proto, n, f) { Object.defineProperty(proto, n, { configurable: true, get: f }); }
        function Node() {}
        getter(Node.prototype, 'parentElement', function () { return this._parent || null; });
        getter(Node.prototype, 'nodeName', function () { return this._tag; });
        function Element() {}
        Element.prototype = Object.create(Node.prototype);
        getter(Element.prototype, 'clientWidth', function () { return this._cw; });
        getter(Element.prototype, 'clientHeight', function () { return this._ch; });
        getter(Element.prototype, 'scrollHeight', function () { return this._sh; });
        Element.prototype.getBoundingClientRect = function () { reads++; return new DOMRect(this._rect); };
        Element.prototype.querySelector = function (sel) { return this._interactive ? {} : null; };
        function DOMRectReadOnly() {}
        getter(DOMRectReadOnly.prototype, 'bottom', function () { return this._r.bottom; });
        function DOMRect(r) { this._r = r; }
        DOMRect.prototype = Object.create(DOMRectReadOnly.prototype);
        getter(DOMRect.prototype, 'height', function () { return this._r.height; });
        getter(DOMRect.prototype, 'width', function () { return this._r.width; });
        function CSSStyleDeclaration(e) { this._e = e; }
        CSSStyleDeclaration.prototype.getPropertyValue = function (n) {
          return n === 'position' ? (this._e._pos || 'static') : n === 'background-color' ? (this._e._bg || 'rgba(0, 0, 0, 0)') : '';
        };
        getComputedStyle = function (e) { return new CSSStyleDeclaration(e); };
        function pel(tag, parent, rect, o) {
          var e = Object.create(Element.prototype); e._tag = tag; e._parent = parent; e._rect = rect;
          for (var k in o || {}) e['_' + k] = o[k];
          return e;
        }
        var phtml = pel('HTML', null, { bottom: 863, height: 863, width: 412 }, { cw: 412, ch: 863, sh: 863 });
        var pbody = pel('BODY', phtml, { bottom: 863, height: 863, width: 412 });
        var pnav = pel('NAV', pbody, { bottom: 863, height: 56, width: 412 }, { pos: 'fixed', bg: 'rgb(103, 80, 164)', interactive: true });
        var ptab = pel('BUTTON', pnav, { bottom: 863, height: 56, width: 103 });
        var pmeta = pel('META', phtml, {});
        function Document() {}
        getter(Document.prototype, 'documentElement', function () { return phtml; });
        getter(Document.prototype, 'body', function () { return pbody; });
        getter(Document.prototype, 'compatMode', function () { return 'CSS1Compat'; });
        getter(Document.prototype, 'scrollingElement', function () { return phtml; });
        Document.prototype.elementFromPoint = function (x, y) { return hit; };
        Document.prototype.addEventListener = function (t, f) { docListeners.push({ t: t, f: f }); };
        var oldDoc = document;
        document = Object.create(Document.prototype);
        document.querySelectorAll = oldDoc.querySelectorAll; document.createElement = oldDoc.createElement;
        function NodeList(items) { for (var i = 0; i < items.length; i++) this[i] = items[i]; this._n = items.length; }
        getter(NodeList.prototype, 'length', function () { return this._n; });
        function MutationRecord(type, target, added) { this._type = type; this._target = target; this._added = new NodeList(added || []); this._removed = new NodeList([]); }
        getter(MutationRecord.prototype, 'type', function () { return this._type; });
        getter(MutationRecord.prototype, 'target', function () { return this._target; });
        getter(MutationRecord.prototype, 'addedNodes', function () { return this._added; });
        getter(MutationRecord.prototype, 'removedNodes', function () { return this._removed; });
        MutationObserver = function (cb) { mutationCb = cb; mutationObs = this; };
        MutationObserver.prototype.observe = function (n, o) { this.target = n; this.opts = o; };
        function observerClass(kind) {
          var C = function (cb) { this.cb = cb; };
          C.prototype.observe = function (e) { observed.push({ kind: kind, el: e, cb: this.cb, obs: this }); };
          C.prototype.disconnect = function () { var self = this; observed = observed.filter(function (o) { return o.obs !== self; }); };
          return C;
        }
        ResizeObserver = observerClass('resize'); IntersectionObserver = observerClass('intersection');
        // A real MessageEvent's getter throws on anything else, and the
        // platform hands the channel's listeners a plain { data } object:
        // the fake DOM's kotlinSays already does, and the detector must cope.
        function MessageEvent(data) { this._data = data; }
        getter(MessageEvent.prototype, 'data', function () {
          if (!(this instanceof MessageEvent)) throw new TypeError('Illegal invocation');
          return this._data;
        });
        hit = ptab;
    """


    /** The page's wrappers: each records its name in `seen`, then does what the original did. */
    private val spies = """
        var seen = [];
        function saw(n) { seen[seen.length] = n; }
        function spy(o, n) { var f = o[n]; o[n] = function () { saw(n); return f.apply(this, arguments); }; }
        function spyGet(proto, n) {
          var x = Object.getOwnPropertyDescriptor(proto, n);
          Object.defineProperty(proto, n, { configurable: true, get: function () { saw(n); return x.get.apply(this); } });
        }
        // Array methods, as seen on any array but the fake DOM's own bookkeeping.
        function fakes(a) {
          return a === sent || a === timers || a === resizeListeners || a === docListeners || a === observed ||
              a === mediaListeners || a === contextMenuListeners || a === channelListeners || a.fake;
        }
        ['indexOf', 'push', 'splice'].forEach(function (n) {
          var f = Array.prototype[n];
          Array.prototype[n] = function () { if (!fakes(this)) saw(n); return f.apply(this, arguments); };
        });
        spy(RegExp.prototype, 'exec'); spy(Math, 'round'); spy(JSON, 'stringify');
        Object.prototype.toJSON = function () { saw('toJSON'); return {}; };
        var realCall = Function.prototype.call;
        Function.prototype.call = function () { saw('call'); return realCall.apply(this, arguments); };
        function pageSaw() { Function.prototype.call = realCall; delete Object.prototype.toJSON; return seen.join(','); }
    """

    /** The page, after document start, wraps every method and getter a started detector could use. */
    private val pageWrapsEverything = """
        spyGet(Node.prototype, 'parentElement'); spyGet(Node.prototype, 'nodeName');
        ['clientWidth', 'clientHeight', 'scrollHeight'].forEach(function (n) { spyGet(Element.prototype, n); });
        spy(Element.prototype, 'getBoundingClientRect'); spy(Element.prototype, 'querySelector');
        spyGet(DOMRectReadOnly.prototype, 'bottom'); spyGet(DOMRect.prototype, 'height'); spyGet(DOMRect.prototype, 'width');
        spy(CSSStyleDeclaration.prototype, 'getPropertyValue');
        ['documentElement', 'body', 'compatMode', 'scrollingElement'].forEach(function (n) { spyGet(Document.prototype, n); });
        spy(Document.prototype, 'elementFromPoint'); spy(Document.prototype, 'addEventListener'); spy(window, 'addEventListener');
        spyGet(NodeList.prototype, 'length');
        ['type', 'target', 'addedNodes', 'removedNodes'].forEach(function (n) { spyGet(MutationRecord.prototype, n); });
        spy(MutationObserver.prototype, 'observe');
        spy(ResizeObserver.prototype, 'observe'); spy(ResizeObserver.prototype, 'disconnect');
        spy(IntersectionObserver.prototype, 'observe'); spy(IntersectionObserver.prototype, 'disconnect');
        spyGet(MessageEvent.prototype, 'data');
    """

    @Test
    fun `a started detector probes, observes and listens only through functions saved at document start`() = page {
        eval(protoDom)
        documentStart()
        eval(spies)
        eval(pageWrapsEverything)
        // First paint: it starts (listeners, observer) and probes.
        firstPaint()
        // A mutation touching a <meta>, a resize, the nav's own observers,
        // a readystatechange, and Kotlin asking again.
        eval("mutationCb([new MutationRecord('attributes', pmeta), new MutationRecord('childList', pbody, [pmeta])]); flushTimers()")
        eval("phtml._ch = 800; pnav._rect = { bottom: 800, height: 56, width: 412 }; for (var i = 0; i < resizeListeners.length; i++) resizeListeners[i](); flushTimers()")
        eval("observed[0].cb(); flushTimers(); readyStateChanges(); flushTimers()")
        // The nav goes: the probe misses it and its observers are dropped.
        eval("hit = pbody")
        kotlinProbe()
        eval("kotlinSays('${themeColorRequest(token)}')")
        assertEquals("", Context.toString(eval("pageSaw()")))
        // And it all still worked.
        val reports = (0 until sent).map { Context.toString(eval("sent[$it]")) }.filter { it.startsWith("{") }
        val first = JSONObject(reports.first())
        assertEquals(token, first.getString("token"))
        assertTrue(first.has())
        assertEquals("rgb(103, 80, 164)", first.color())
        assertEquals(BottomUiReport(true, "rgb(103, 80, 164)"), parseBottomUiMessage(reports.first(), true, token))
        assertEquals(BottomUiReport(false, null), parseBottomUiMessage(reports.last(), true, token))
        assertTrue(eval("mutationObs.target === phtml") as Boolean)
        assertEquals(1, num("resizeListeners.length"))
        assertEquals(1, num("docListeners.length"))
        assertEquals(0, num("observed.length"))
        // The meta mutation was noticed through the saved getters: a theme report went out unasked.
        assertTrue((0 until sent).any { Context.toString(eval("sent[$it]")).startsWith("theme $token ") })
    }

    @Test
    fun `media is tracked only through functions saved at document start`() = page {
        eval(
            """
            function getter(proto, n, f) { Object.defineProperty(proto, n, { configurable: true, get: f }); }
            HTMLMediaElement.prototype.__proto__ = EventTarget.prototype;
            ['paused', 'ended', 'muted', 'volume', 'readyState'].forEach(function (n) {
              getter(HTMLMediaElement.prototype, n, function () { return this['_' + n]; });
            });
            ['type', 'target', 'currentTarget', 'defaultPrevented'].forEach(function (n) {
              getter(Event.prototype, n, function () { return this['_' + n]; });
            });
            function pmedia() { var m = Object.create(HTMLMediaElement.prototype); m._paused = true; m._ended = false; m._muted = false; m._volume = 1; m._readyState = 4; m.ls = []; m.ls.fake = true; return m; }
            function pfire(m, t) {
              function ev() { var e = new Event(); e._type = t; e._target = m; e._currentTarget = m; e.isTrusted = true; return e; }
              for (var i = 0; i < mediaListeners.length; i++) if (mediaListeners[i].t === t) mediaListeners[i].f(ev());
              var ls = m.ls || [];
              for (var j = 0; j < ls.length; j++) if (ls[j].t === t) ls[j].f(ev());
            }
            """,
        )
        documentStart()
        eval(spies)
        eval(
            """
            ['paused', 'ended', 'muted', 'volume', 'readyState', 'webkitAudioDecodedByteCount'].forEach(function (n) { spyGet(HTMLMediaElement.prototype, n); });
            ['type', 'target', 'currentTarget', 'defaultPrevented'].forEach(function (n) { spyGet(Event.prototype, n); });
            spy(EventTarget.prototype, 'addEventListener');
            var m = pmedia(); m._paused = false; pfire(m, 'playing');
            var m2 = pmedia(); m2._paused = false; pfire(m2, 'playing');
            m._paused = true; pfire(m, 'pause');
            m2._paused = true; pfire(m2, 'pause');
            var ce = new Event(); ce.isTrusted = true; ce._defaultPrevented = true;
            pressAndHold(ce, false);
            """,
        )
        assertEquals("", Context.toString(eval("pageSaw()")))
        assertEquals(
            listOf(AUDIO_AUDIBLE, AUDIO_SILENT, CONTEXT_MENU_KEPT),
            (0 until sent).map { Context.toString(eval("sent[$it]")) },
        )
        // Each element got its listeners once, through the saved addEventListener.
        assertEquals(7, num("m.ls.length"))
    }
}
