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
 * [adblockCosmeticJs] runs for real (Rhino) against a small fake DOM:
 * the channel object, timers, a `MutationObserver` the test fires by hand,
 * constructed stylesheets and `document.adoptedStyleSheets`.
 */
class AdblockCosmeticTest {

    private val channel = "zxcvbnmasdfghjkl"

    private val fakeDom = """
        var window = this;
        // Rhino has no Symbol: stand one in, so sequences are the iterables Chromium gets.
        Symbol = function () {}; Symbol.iterator = '@@iterator';
        var sent = [], timers = [], observer = null;
        var port = { postMessage: function (m) { sent[sent.length] = m; } };
        window['$channel'] = port;
        var location = { protocol: 'https:' };
        function setTimeout(f, ms) { timers[timers.length] = f; return timers.length; }
        function flushTimers() { var t = timers; timers = []; for (var i = 0; i < t.length; i++) t[i](); }
        // The platform's sequence conversion: walk the value's iterator.
        function sequence(v) {
          var out = [], r;
          if (Array.isArray(v)) { for (var i = 0; i < v.length; i++) out[i] = v[i]; return out; }
          var it = v['@@iterator']();
          while (!(r = it.next()).done) out[out.length] = r.value;
          return out;
        }
        function getter(P, n, f) { Object.defineProperty(P, n, { configurable: true, get: f }); }
        function list(C, a) { var l = new C(); for (var i = 0; i < a.length; i++) l[i] = a[i]; l._n = a.length; return l; }
        function Node() {}
        getter(Node.prototype, 'nodeType', function () { return this._type; });
        function Element() {}
        Element.prototype = Object.create(Node.prototype);
        getter(Element.prototype, 'id', function () { return this._id; });
        getter(Element.prototype, 'classList', function () { return list(DOMTokenList, this._cl); });
        Element.prototype.querySelectorAll = function () {
          var out = [];
          (function walk(n) { for (var i = 0; i < n.kids.length; i++) { out[out.length] = n.kids[i]; walk(n.kids[i]); } })(this);
          return list(NodeList, out);
        };
        function DOMTokenList() {}
        getter(DOMTokenList.prototype, 'length', function () { return this._n; });
        function NodeList() {}
        getter(NodeList.prototype, 'length', function () { return this._n; });
        function MutationRecord(type, target, added) { this._type = type; this._target = target; this._added = list(NodeList, added || []); }
        getter(MutationRecord.prototype, 'type', function () { return this._type; });
        getter(MutationRecord.prototype, 'target', function () { return this._target; });
        getter(MutationRecord.prototype, 'addedNodes', function () { return this._added; });
        function el(id, classes, kids) { var e = new Element(); e._type = 1; e._id = id || ''; e._cl = classes || []; e.kids = kids || []; return e; }
        function text() { var t = new Node(); t._type = 3; return t; }
        function Document() {}
        var adopted = [], lastSet = null;
        Object.defineProperty(Document.prototype, 'adoptedStyleSheets', {
          configurable: true,
          get: function () { var c = []; for (var i = 0; i < adopted.length; i++) c[i] = adopted[i]; return c; },
          set: function (v) { lastSet = v; adopted = sequence(v); } });
        getter(Document.prototype, 'documentElement', function () { return this._de; });
        var document = new Document();
        document._de = el('', [], [el('top-ad', ['x']), el('', ['ad-banner', 'story'])]);
        function CSSStyleSheet() { this.css = null; }
        CSSStyleSheet.prototype.replaceSync = function (c) { this.css = c; };
        // Reads every option it knows, as the platform does.
        function MutationObserver(cb) { observer = this; this.cb = cb; }
        MutationObserver.prototype.observe = function (n, o) {
          this.opts = o; this.filter = sequence(o.attributeFilter);
          this.flags = [o.childList, o.subtree, o.attributes, o.characterData, o.attributeOldValue, o.characterDataOldValue];
        };
        MutationObserver.prototype.disconnect = function () { this.gone = true; };
        function reply(m) { port.onmessage({ data: m }); }
    """.trimIndent()

    private val cx: Context = Context.enter().apply { optimizationLevel = -1; languageVersion = Context.VERSION_ES6 }
    private val scope: Scriptable = cx.initStandardObjects()

    @After
    fun exit() = Context.exit()

    private fun eval(js: String): Any? = cx.evaluateString(scope, js, "t", 1, null)
    private fun str(js: String) = Context.toString(eval(js))

    private fun install() {
        eval(fakeDom)
        eval(adblockCosmeticJs(channel))
    }

    @Test
    fun `takes the channel off window and says hello`() {
        install()
        assertEquals("undefined", str("typeof window['$channel']"))
        assertEquals(COSMETIC_HELLO, str("sent.join('|')"))
    }

    @Test
    fun `applies CSS as an adopted sheet, then reports each name once`() {
        install()
        eval("reply('1.promo{display:none!important}')")
        assertEquals("1", str("adopted.length"))
        assertEquals(".promo{display:none!important}", str("adopted[0].css"))
        eval("flushTimers()")
        val report = str("sent[1]")
        assertTrue(report.startsWith(COSMETIC_TOKENS))
        assertEquals(setOf("#top-ad", ".x", ".ad-banner", ".story"), parseCosmeticTokens(report)!!.toSet())

        // New content: only the names not reported before.
        eval("observer.cb([new MutationRecord('childList', null, [el('', ['story', 'sponsored']), text()])]); flushTimers()")
        assertEquals(listOf(".sponsored"), parseCosmeticTokens(str("sent[2]")))
        // A class set later on an existing element.
        eval("observer.cb([new MutationRecord('attributes', el('late', []))]); flushTimers()")
        assertEquals(listOf("#late"), parseCosmeticTokens(str("sent[3]")))

        // Further answers add sheets; a page's own sheets stay.
        eval("adopted[adopted.length] = { page: true }; reply('1.x{display:none!important}')")
        assertEquals("3", str("adopted.length"))
        assertEquals("true", str("adopted[0].page === true || adopted[1].page === true"))
    }

    @Test
    fun `puts its sheets back when the page replaces adoptedStyleSheets`() {
        install()
        eval("reply('1.promo{display:none!important}')")
        eval("var ours = adopted[0]; flushTimers()")
        // The page assigns its own list, with no DOM change: the recheck restores ours.
        eval("document.adoptedStyleSheets = [{ page: true }]")
        assertEquals("1", str("adopted.length"))
        eval("flushTimers()")
        assertEquals("2", str("adopted.length"))
        assertEquals("true", str("adopted[0].page === true && adopted[1] === ours"))
        // …and it keeps rechecking, without adding duplicates.
        eval("flushTimers(); flushTimers()")
        assertEquals("2", str("adopted.length"))
        eval("document.adoptedStyleSheets = []")
        // A DOM change puts them back at once, with its batch.
        eval("observer.cb([new MutationRecord('childList', null, [el('', ['fresh'])])])")
        eval("var t = timers; timers = []; t[t.length - 1]()")
        assertEquals("true", str("adopted.length === 1 && adopted[0] === ours"))
    }

    @Test
    fun `stops rechecking once hiding is off`() {
        install()
        eval("reply('1.promo{display:none!important}'); flushTimers()")
        eval("port.onmessage({ data: '0' }); document.adoptedStyleSheets = []; flushTimers(); flushTimers()")
        assertEquals("0", str("adopted.length"))
        assertEquals("0", str("timers.length"))
    }

    @Test
    fun `stops for good when told there is no hiding here`() {
        install()
        eval("reply('0')")
        eval("flushTimers()")
        assertEquals("1", str("sent.length"))
        assertEquals("0", str("adopted.length"))
        assertEquals("null", str("String(observer)"))
    }

    @Test
    fun `does nothing on a non-web page`() {
        eval(fakeDom)
        eval("location.protocol = 'about:'")
        eval(adblockCosmeticJs(channel))
        assertEquals("0", str("sent.length"))
        assertEquals("undefined", str("typeof window['$channel']"))
    }

    @Test
    fun `observes only id and class changes, with options and a filter built at document start`() {
        install()
        eval("reply('1')")
        assertEquals("id,class", str("observer.filter.join(',')"))
        assertEquals("true,true,true,,,", str("observer.flags.join(',')"))
        assertEquals("true", str("Object.getPrototypeOf(observer.opts) === null && !Array.isArray(observer.opts.attributeFilter)"))
        assertEquals("true", str("Object.getPrototypeOf(observer.opts.attributeFilter) === null"))
    }

    /** The page's wrappers, put in after document start: each records its name in `seen`, then does what the original did. */
    private val pageWrapsEverything = """
        var seen = [];
        function saw(n) { seen[seen.length] = n; }
        function spy(o, n) { var f = o[n]; o[n] = function () { saw(n); return f.apply(this, arguments); }; }
        function spyGet(P, n) {
          var x = Object.getOwnPropertyDescriptor(P, n);
          Object.defineProperty(P, n, { configurable: true, get: function () { saw(n); return x.get.apply(this); },
            set: x.set && function (v) { saw('set ' + n); return x.set.apply(this, [v]); } });
        }
        spy(Element.prototype, 'querySelectorAll');
        spyGet(Element.prototype, 'id'); spyGet(Element.prototype, 'classList'); spyGet(Node.prototype, 'nodeType');
        spyGet(DOMTokenList.prototype, 'length'); spyGet(NodeList.prototype, 'length');
        ['type', 'target', 'addedNodes'].forEach(function (n) { spyGet(MutationRecord.prototype, n); });
        spyGet(Document.prototype, 'documentElement'); spyGet(Document.prototype, 'adoptedStyleSheets');
        spy(MutationObserver.prototype, 'observe'); spy(MutationObserver.prototype, 'disconnect');
        spy(CSSStyleSheet.prototype, 'replaceSync');
        spy(RegExp.prototype, 'exec'); spy(RegExp.prototype, 'test');
        ['charAt', 'substring', 'substr', 'slice', 'split'].forEach(function (n) { spy(String.prototype, n); });
        ['push', 'indexOf', 'splice', 'join', 'slice', 'concat'].forEach(function (n) { spy(Array.prototype, n); });
        if (typeof Set === 'function') { spy(Set.prototype, 'has'); spy(Set.prototype, 'add'); }
        // Options the platform reads but the script doesn't set would be found here.
        ['characterData', 'attributeOldValue', 'characterDataOldValue'].forEach(function (n) {
          Object.defineProperty(Object.prototype, n, { configurable: true, get: function () { saw(n); } });
        });
        var realCall = Function.prototype.call, realApply = Function.prototype.apply;
        Function.prototype.call = function () {
          saw('call');
          var a = []; for (var i = 1; i < arguments.length; i++) a[i - 1] = arguments[i];
          return realApply.apply(this, [arguments[0], a]);
        };
        function pageSaw() {
          Function.prototype.call = realCall;
          ['characterData', 'attributeOldValue', 'characterDataOldValue'].forEach(function (n) { delete Object.prototype[n]; });
          var out = ''; for (var i = 0; i < seen.length; i++) out += (i ? ',' : '') + seen[i];
          return out;
        }
    """.trimIndent()

    @Test
    fun `after document start it runs only natives saved then`() {
        install()
        // The page's scripts run, wrapping everything the script calls later.
        eval(pageWrapsEverything)
        // Kotlin's answer arrives: a sheet, the observer, the first scan.
        eval("reply('1.promo{display:none!important}'); flushTimers()")
        // DOM changes, an attribute change, a node that isn't an element.
        eval("observer.cb([new MutationRecord('childList', null, [el('a b', ['story', 'sponsored'], [el('kid', ['deep'])]), text()])])")
        eval("observer.cb([new MutationRecord('attributes', el('late', ['later']))]); flushTimers()")
        // The page drops our sheets (through the original setter, not its own wrapper); the recheck puts them back.
        eval("adopted = [{ page: true }]; flushTimers()")
        // A second answer, then hiding goes off.
        eval("reply('1.x{display:none!important}'); flushTimers(); reply('0'); flushTimers()")
        assertEquals("", str("pageSaw()"))
        // …and it all still worked.
        assertEquals(setOf("#top-ad", ".x", ".ad-banner", ".story"), parseCosmeticTokens(str("sent[1]"))!!.toSet())
        assertEquals(setOf(".sponsored", "#kid", ".deep", "#late", ".later"), parseCosmeticTokens(str("sent[2]"))!!.toSet())
        assertEquals("3", str("adopted.length"))
        assertEquals("true", str("observer.gone === true"))
        assertEquals("true", str("!Array.isArray(lastSet) && Object.getPrototypeOf(lastSet) === null"))
    }

    @Test
    fun `a big find goes out in batches`() {
        install()
        eval("reply('1'); flushTimers(); var many = []; for (var i = 0; i < ${COSMETIC_MAX_TOKENS + 5}; i++) many[i] = el('', ['c' + i]);")
        eval("observer.cb([new MutationRecord('childList', null, many)]); flushTimers()")
        assertEquals("4", str("sent.length"))
        assertEquals(COSMETIC_MAX_TOKENS, parseCosmeticTokens(str("sent[2]"))!!.size)
        assertEquals(listOf(".c1000", ".c1001", ".c1002", ".c1003", ".c1004"), parseCosmeticTokens(str("sent[3]")))
    }

    @Test
    fun `token reports are validated`() {
        assertEquals(listOf(".a", "#b"), parseCosmeticTokens("t\n.a\n#b\nc\n."))
        assertNull(parseCosmeticTokens("x\n.a"))
        assertNull(parseCosmeticTokens("t\n" + List(COSMETIC_MAX_TOKENS + 1) { ".a$it" }.joinToString("\n")))
    }

    @Test
    fun `allowlist hosts`() {
        assertEquals("news.example", normalizeAllowlistHost(" https://www.News.Example:8443/path?q "))
        assertEquals("news.example", normalizeAllowlistHost("news.example."))
        assertNull(normalizeAllowlistHost("localhost"))
        assertNull(normalizeAllowlistHost("not a host"))
        assertNull(normalizeAllowlistHost(""))
        val list = setOf("news.example")
        assertTrue(isAllowlisted("m.news.example", list))
        assertTrue(isAllowlisted("www.news.example", list))
        assertFalse(isAllowlisted("badnews.example", list))
        assertFalse(isAllowlisted("example", list))
    }

    @Test
    fun `an internationalised allowlist host is stored as the punycode Chromium reports`() {
        val was = WhatwgHost.uts46
        WhatwgHost.uts46 = Icu4jUts46
        try {
            assertEquals("xn--bcher-kva.de", normalizeAllowlistHost("bücher.de"))
            assertEquals("xn--bcher-kva.de", normalizeAllowlistHost("https://www.BÜCHER.de/x"))
            assertEquals("xn--bcher-kva.de", normalizeAllowlistHost("bücher。de"))
            assertEquals("xn--bcher-kva.de", normalizeAllowlistHost("xn--bcher-kva.de"))
            // Not a valid IDN (a joiner outside a joining context): refused, not stored raw.
            assertNull(normalizeAllowlistHost("a\u200db.de"))
            val list = setOfNotNull(normalizeAllowlistHost("bücher.de"))
            assertTrue(isAllowlisted("xn--bcher-kva.de", list))
            assertTrue(isAllowlisted("shop.xn--bcher-kva.de", list))
            assertFalse(isAllowlisted("bücher.de.example", list))
            // Settings shows the Unicode name, with the stored form on the sub-line (R2-F2).
            assertEquals("bücher.de", allowlistHostForDisplay("xn--bcher-kva.de"))
            assertEquals("shop.bücher.de", allowlistHostForDisplay("shop.xn--bcher-kva.de"))
            assertEquals("xn--bcher-kva.de · Ads allowed", allowlistSiteSubtitle("xn--bcher-kva.de"))
            assertEquals("news.example", allowlistHostForDisplay("news.example"))
            assertEquals("Ads allowed", allowlistSiteSubtitle("news.example"))
            // An xn-- label that doesn't decode cleanly is shown as stored.
            assertEquals("xn--zz.de", allowlistHostForDisplay("xn--zz.de"))
        } finally {
            WhatwgHost.uts46 = was
        }
    }

    @Test
    fun `the page menu's site`() {
        assertEquals("news.example", adblockSiteFor("https://www.news.example/story"))
        assertNull(adblockSiteFor(""))
        assertNull(adblockSiteFor("bzz://abcd/"))
        assertNull(adblockSiteFor("http://127.0.0.1:1633/bzz/abcd/"))
    }
}
