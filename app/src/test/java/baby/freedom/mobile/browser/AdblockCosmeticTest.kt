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
        var sent = [], timers = [], observer = null;
        var port = { postMessage: function (m) { sent.push(m); } };
        window['$channel'] = port;
        var location = { protocol: 'https:' };
        function setTimeout(f, ms) { timers.push(f); return timers.length; }
        function flushTimers() { var t = timers; timers = []; for (var i = 0; i < t.length; i++) t[i](); }
        function el(id, classes, kids) {
          var e = { nodeType: 1, id: id || '', classList: classes || [], kids: kids || [] };
          e.querySelectorAll = function () {
            var out = [];
            (function walk(n) { for (var i = 0; i < n.kids.length; i++) { out.push(n.kids[i]); walk(n.kids[i]); } })(e);
            return out;
          };
          return e;
        }
        function Document() {}
        var adopted = [];
        Object.defineProperty(Document.prototype, 'adoptedStyleSheets', {
          configurable: true, get: function () { return adopted.slice(); }, set: function (v) { adopted = v; } });
        var document = new Document();
        document.documentElement = el('', [], [el('top-ad', ['x']), el('', ['ad-banner', 'story'])]);
        function CSSStyleSheet() { this.css = null; }
        CSSStyleSheet.prototype.replaceSync = function (c) { this.css = c; };
        function MutationObserver(cb) { observer = this; this.cb = cb; this.observe = function (n, o) { this.opts = o; }; this.disconnect = function () { observer.gone = true; }; }
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
        eval("observer.cb([{ type: 'childList', addedNodes: [el('', ['story', 'sponsored'])] }]); flushTimers()")
        assertEquals(listOf(".sponsored"), parseCosmeticTokens(str("sent[2]")))
        // A class set later on an existing element.
        eval("observer.cb([{ type: 'attributes', target: el('late', []) }]); flushTimers()")
        assertEquals(listOf("#late"), parseCosmeticTokens(str("sent[3]")))

        // Further answers add sheets; a page's own sheets stay.
        eval("adopted = adopted.concat([{ page: true }]); reply('1.x{display:none!important}')")
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
        eval("observer.cb([{ type: 'childList', addedNodes: [el('', ['fresh'])] }])")
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
