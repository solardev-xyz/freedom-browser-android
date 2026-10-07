package baby.freedom.mobile.browser

import android.webkit.WebViewClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class WebLoadErrorTest {

    @Test
    fun `chromium error names pick the failure`() {
        fun f(name: String) = netFailureFor("net::$name", WebViewClient.ERROR_UNKNOWN, online = true)
        assertEquals(NetFailure.NOT_FOUND, f("ERR_NAME_NOT_RESOLVED"))
        assertEquals(NetFailure.REFUSED, f("ERR_CONNECTION_REFUSED"))
        assertEquals(NetFailure.RESET, f("ERR_CONNECTION_RESET"))
        assertEquals(NetFailure.RESET, f("ERR_EMPTY_RESPONSE"))
        assertEquals(NetFailure.TIMED_OUT, f("ERR_CONNECTION_TIMED_OUT"))
        assertEquals(NetFailure.TIMED_OUT, f("ERR_TIMED_OUT"))
        assertEquals(NetFailure.OFFLINE, f("ERR_INTERNET_DISCONNECTED"))
        assertEquals(NetFailure.INSECURE, f("ERR_SSL_PROTOCOL_ERROR"))
        assertEquals(NetFailure.INSECURE, f("ERR_CERT_AUTHORITY_INVALID"))
        assertEquals(NetFailure.OTHER, f("ERR_TOO_MANY_REDIRECTS"))
    }

    @Test
    fun `no network is offline whatever chromium called it`() {
        assertEquals(
            NetFailure.OFFLINE,
            netFailureFor("net::ERR_NAME_NOT_RESOLVED", WebViewClient.ERROR_HOST_LOOKUP, online = false),
        )
    }

    @Test
    fun `without a chromium name the webview error code decides`() {
        assertEquals(NetFailure.NOT_FOUND, netFailureFor(null, WebViewClient.ERROR_HOST_LOOKUP, true))
        assertEquals(NetFailure.REFUSED, netFailureFor("", WebViewClient.ERROR_CONNECT, true))
        assertEquals(NetFailure.TIMED_OUT, netFailureFor(null, WebViewClient.ERROR_TIMEOUT, true))
        assertEquals(NetFailure.OTHER, netFailureFor(null, WebViewClient.ERROR_UNKNOWN, true))
    }

    @Test
    fun `page escapes the address and retries it`() {
        val url = "https://x.invalid/?a=<b>&c=\"d\""
        val html = netErrorPageHtml(url, "x<y>.invalid", NetFailure.NOT_FOUND, "net::ERR_NAME_NOT_RESOLVED")
        assertFalse(html.contains("<b>&c"))
        assertFalse(html.contains("x<y>"))
        assertTrue(html.contains("x&lt;y&gt;.invalid"))
        assertTrue(html.contains("href=\"https://x.invalid/?a=&lt;b&gt;&amp;c=&quot;d&quot;\""))
        assertTrue(html.contains("<title>Site can't be found</title>"))
        assertTrue(html.contains("net::ERR_NAME_NOT_RESOLVED"))
    }

    private val ddg = SearchEngines.DEFAULT.template

    @Test
    fun `a typed bare address is remembered with its search`() {
        val typed = typedAddressFor(" example.cmo ", "https://example.cmo", ddg)
        assertEquals(TypedAddress("https://example.cmo", "example.cmo", "https://duckduckgo.com/?q=example.cmo"), typed)
        assertEquals(
            "https://duckduckgo.com/?q=localhost%3A8080",
            typedAddressFor("localhost:8080", "http://localhost:8080", ddg)?.searchUrl,
        )
    }

    @Test
    fun `a search, a full URL or a non-web address is not a typed address`() {
        // Already a search: there's nothing to offer a search for.
        assertEquals(null, typedAddressFor("cats and dogs", UrlParser.searchUrl("cats and dogs", ddg), ddg))
        // Typed with its scheme (or a Reload, a bookmark): meant as written.
        assertEquals(null, typedAddressFor("https://example.cmo", "https://example.cmo", ddg))
        assertEquals(null, typedAddressFor("bzz://abc", "bzz://abc", ddg))
        assertEquals(null, typedAddressFor("", "about:blank", ddg))
    }

    @Test
    fun `search instead is offered only when the typed address isn't found`() {
        val typed = TypedAddress("https://example.cmo", "example.cmo", "https://duckduckgo.com/?q=example.cmo")
        assertEquals(typed, searchInsteadFor(typed, "https://example.cmo", NetFailure.NOT_FOUND))
        assertEquals(typed, searchInsteadFor(typed, "https://example.cmo#top", NetFailure.NOT_FOUND))
        // Chromium reports the typed host back with its empty path as `/`.
        assertEquals(typed, searchInsteadFor(typed, "https://example.cmo/", NetFailure.NOT_FOUND))
        assertEquals(null, searchInsteadFor(typed, "https://example.cmo/x", NetFailure.NOT_FOUND))
        // Found but unreachable: searching for it wouldn't help.
        assertEquals(null, searchInsteadFor(typed, "https://example.cmo", NetFailure.REFUSED))
        assertEquals(null, searchInsteadFor(typed, "https://example.cmo", NetFailure.OFFLINE))
        // A different address failed (a redirect, a link): not what was typed.
        assertEquals(null, searchInsteadFor(typed, "https://other.example", NetFailure.NOT_FOUND))
        assertEquals(null, searchInsteadFor(null, "https://example.cmo", NetFailure.NOT_FOUND))
    }

    @Test
    fun `the typed address is dropped once another page commits in the tab`() {
        val typed = TypedAddress("https://example.cmo", "example.cmo", "https://duckduckgo.com/?q=example.cmo")
        // Its own load, its error page, a Reload or Try again: kept.
        assertEquals(typed, typedAddressAfterCommit(typed, "https://example.cmo/"))
        assertEquals(typed, typedAddressAfterCommit(typed, "https://example.cmo#top"))
        // Browsing on by links (or the search link itself): dropped, so a
        // later link back to the same dead host gets no offer.
        var kept = typedAddressAfterCommit(typed, "https://other.example/")
        assertEquals(null, kept)
        kept = typedAddressAfterCommit(kept, "https://example.cmo/")
        assertEquals(null, searchInsteadFor(kept, "https://example.cmo/", NetFailure.NOT_FOUND))
        assertEquals(null, typedAddressAfterCommit(typed, null))
        assertEquals(null, typedAddressAfterCommit(null, "https://example.cmo/"))
    }

    @Test
    fun `page offers search instead, escaped, after Try again`() {
        val typed = TypedAddress("https://a.cmo", "a\"<b>.cmo", "https://s.example/?q=a%22&x=<b>")
        val html = netErrorPageHtml("https://a.cmo", "a.cmo", NetFailure.NOT_FOUND, null, searchInstead = typed)
        val tryAgain = html.indexOf("<a class=\"btn p\" href=\"https://a.cmo\">Try again</a>")
        val search = html.indexOf(
            "<a class=\"btn\" href=\"https://s.example/?q=a%22&amp;x=&lt;b&gt;\">" +
                "Search for “a&quot;&lt;b&gt;.cmo” instead</a>",
        )
        assertTrue(tryAgain > 0)
        assertTrue(search > tryAgain)
        assertFalse(netErrorPageHtml("https://a.cmo", "a.cmo", NetFailure.NOT_FOUND, null).contains("Search for"))
    }

    @Test
    fun `page is neutral and themed, with the raw error collapsed under Details`() {
        val html = netErrorPageHtml("https://a.example", "a.example", NetFailure.REFUSED, "net::ERR_CONNECTION_REFUSED")
        assertTrue(html.contains("<style>" + errorPageThemeCss() + ERROR_PAGE_CSS + "</style>"))
        // Collapsed (no `open`), with the address and Chromium's name inside.
        assertTrue(
            html.contains(
                "<details><summary>Details</summary><div class=\"d\">https://a.example\n\nnet::ERR_CONNECTION_REFUSED</div></details>",
            ),
        )
        // The old all-red palette is gone; the heading takes the text colour.
        for (red in listOf("#ff5e5e", "#cf222e", "#ff8a8a")) assertFalse(red, html.contains(red))
        assertTrue(ERROR_PAGE_CSS.contains("h1{font-size:22px;font-weight:600;line-height:1.3;margin:0 0 12px;color:var(--fg)}"))
    }

    @Test
    fun `theme colours are the app's own schemes`() {
        val css = errorPageThemeCss()
        // Light teal ink and dark wordmark teal as the primary (Try again).
        assertTrue(css.startsWith(":root{color-scheme:light dark;--bg:#fef7ff;--fg:#1d1b20;"))
        assertTrue(css.contains("--pri:#00695b;--onpri:#ffffff;"))
        assertTrue(css.contains("@media (prefers-color-scheme:dark){:root{--bg:#141218;--fg:#e6e0e9;"))
        assertTrue(css.contains("--pri:#00e9c4;--onpri:#00382f;"))
    }

    @Test
    fun `error_html carries the same theme and layout`() {
        val page = listOf(java.io.File("src/main/assets/error/error.html"), java.io.File("app/src/main/assets/error/error.html"))
            .first { it.isFile }
            .readText()
        val block = Regex("""/\* THEME \*/(.*?)/\* /THEME \*/""", RegexOption.DOT_MATCHES_ALL).find(page)?.groupValues?.get(1)
        assertEquals("error.html's THEME block must equal errorPageThemeCss() + ERROR_PAGE_CSS", errorPageThemeCss() + ERROR_PAGE_CSS, block)
        for (red in listOf("#ff5e5e", "#cf222e", "#ff8a8a")) assertFalse(red, page.contains(red))
        assertTrue(page.contains("<button id=\"retry-btn\" class=\"btn p\"></button>"))
        assertTrue(page.contains("<summary id=\"details-label\"></summary>"))
        assertFalse(page.contains("<details open"))
        // Except on a not-cross-checked warning, which asks the user to judge the address in it.
        assertTrue(page.contains("if (document.body.classList.contains('warn')) detailsEl.parentElement.open = true;"))
    }

    @Test
    fun `script only replaces chromium's own error document`() {
        val script = netErrorPageScript("<html><head><title>t</title></head><body>'x'</body></html>")
        assertTrue(script.startsWith("(function(){if(location.protocol!=='chrome-error:')return false;"))
        // The page goes in as one JS string literal, marked so a second run is a no-op.
        assertTrue(script.contains("document.write(\"<html><head><meta name=\\\"error-page\\\"><title>t<\\/title>"))
    }

    private val day = 24 * 3600 * 1000L
    private val now = 1_790_000_000_000L

    private fun facts(vararg e: Int, before: Long? = now - 400 * day, after: Long? = now + 400 * day) =
        CertFacts(e.toSet(), before, after, "*.badssl.com", "Some CA")

    @Test
    fun `an expired certificate reported as bad dates reads as expired`() {
        val f = facts(SSL_DATE_INVALID, after = 1_428_796_800_000L) // 12 Apr 2015
        assertEquals("cert_expired", certErrorCode(f, now))
        // The date is in the user's locale's style (#280).
        val locale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.UK)
            assertEquals(
                "Expired on 12 Apr 2015\n\nIssued to: *.badssl.com\nIssued by: Some CA",
                certErrorDetail(f, "expired.badssl.com", now),
            )
            Locale.setDefault(Locale.US)
            assertTrue(certErrorDetail(f, "expired.badssl.com", now).startsWith("Expired on Apr 12, 2015\n"))
        } finally {
            Locale.setDefault(locale)
        }
    }

    @Test
    fun `a certificate from the future reads as not yet valid`() {
        val f = facts(SSL_DATE_INVALID, before = now + day)
        assertEquals("cert_not_yet_valid", certErrorCode(f, now))
        assertTrue(certErrorDetail(f, "h", now).startsWith("Not valid until "))
        assertEquals("cert_not_yet_valid", certErrorCode(facts(SSL_NOTYETVALID), now))
        assertEquals("cert_expired", certErrorCode(facts(SSL_EXPIRED), now))
        assertEquals("cert_date_invalid", certErrorCode(facts(SSL_DATE_INVALID), now))
    }

    @Test
    fun `wrong host leads, and every problem is listed`() {
        val f = facts(SSL_IDMISMATCH, SSL_UNTRUSTED)
        assertEquals("cert_wrong_host", certErrorCode(f, now))
        assertEquals(
            "Not issued for wrong.host.badssl.com\nIssuer not trusted by this device\n\n" +
                "Issued to: *.badssl.com\nIssued by: Some CA",
            certErrorDetail(f, "wrong.host.badssl.com", now),
        )
        assertEquals("cert_untrusted", certErrorCode(facts(SSL_UNTRUSTED), now))
        assertEquals("cert_invalid", certErrorCode(facts(5), now))
    }

    @Test
    fun `a refused load's finish gets the certificate page whatever the WebView reports`() {
        val bad = "https://expired.badssl.com/"
        // A popup the page opened: `getUrl()` is still null (R1-F1).
        assertTrue(finishedLoadIsCurrent(bad, null))
        assertTrue(certErrorEndsLoad(bad, bad))
        // A Reload of the page on screen: `getUrl()` is the same URL (R1-F2).
        assertTrue(finishedLoadIsCurrent(bad, bad))
        assertTrue(certErrorEndsLoad(bad, bad))
        // Any other page's finish is not the refused load.
        assertFalse(certErrorEndsLoad("https://example.com/", bad))
        assertFalse(certErrorEndsLoad(null, bad))
    }

    @Test
    fun `a subresource refusal landing before the main frame's finish doesn't hide the page`() {
        val facts = CertFacts(setOf(SSL_UNTRUSTED), null, null, null, null)
        val target = "https://10.0.2.2:8721/target"
        val pending = PendingCertErrors(capacity = 4)
        pending.record(PendingCertError(target, facts, listOf(target)), inFlight = true)
        // The page still on screen keeps loading refused images (R4-F1),
        // more of them than the map holds.
        repeat(10) {
            val img = "https://10.0.2.2:8722/img?$it"
            pending.record(PendingCertError(img, facts, listOf(img)), inFlight = false)
        }
        assertEquals(4, pending.size)
        // An unrelated finish takes nothing.
        assertEquals(null, pending.takeFor("https://example.com/"))
        assertEquals(target, pending.takeFor(target)?.url)
        // Once only.
        assertEquals(null, pending.takeFor(target))
        // A later refusal of the same URL replaces the earlier one.
        pending.record(PendingCertError(target, facts(SSL_EXPIRED), listOf(target)), inFlight = true)
        assertEquals(setOf(SSL_EXPIRED), pending.takeFor(target)?.facts?.errors)
        // A commit drops the rest.
        pending.clear()
        assertEquals(0, pending.size)
        assertEquals(null, pending.takeFor("https://10.0.2.2:8722/img?9"))
    }

    @Test
    fun `the main-frame chain tells whether a refused URL is on the navigation in flight`() {
        val chain = MainFrameChain()
        chain.started("http://a.example/b")
        chain.redirected("https://bad.example/")
        assertTrue(chain.reaches("https://bad.example/"))
        assertTrue(chain.reaches("http://a.example/b#x"))
        assertFalse(chain.reaches("https://tracker.example/pixel"))
        chain.committed()
        assertFalse(chain.reaches("https://bad.example/"))
    }

    @Test
    fun `a refused history step's page goes in the entry it was for`() {
        val a = "https://a.example/"
        val b = "https://b.example/"
        val c = "https://c.example/"
        // A → B → C, on C: Back onto B refused → the same Back.
        assertEquals(-1, certPageStep(listOf(a, b, c), 2, b))
        // On A: Forward onto B refused → the same Forward, C kept ahead.
        assertEquals(1, certPageStep(listOf(a, b, c), 0, b))
        // Reload, or Try again, of the entry on screen.
        assertEquals(0, certPageStep(listOf(a, b), 1, b))
        // A page the list doesn't hold: a new entry of its own.
        assertEquals(null, certPageStep(listOf(a, c), 1, b))
        assertEquals(null, certPageStep(emptyList(), -1, b))
        // The entry's fragment isn't in the request.
        assertEquals(-1, certPageStep(listOf(a, "$b#top", c), 2, b))
        // The nearer entry wins; Back on a tie.
        assertEquals(-1, certPageStep(listOf(b, a, b, c), 3, b))
        assertEquals(-1, certPageStep(listOf(b, a, b), 1, b))
    }

    @Test
    fun `a refused step onto an entry that redirects to a bad certificate goes in that entry`() {
        val a = "http://a.example/"
        val b = "http://b.example/"
        val bad = "https://self-signed.example/"
        val c = "http://c.example/"
        val chain = listOf(b, bad)
        // A → B (302 → bad) → C, on C: Back names only the redirect
        // target, but the entry holds B — the same Back, served on B.
        assertEquals(CertReissue(-1, b), certPageReissue(listOf(a, b, c), 2, chain))
        // On A: Forward, C kept ahead.
        assertEquals(CertReissue(1, b), certPageReissue(listOf(a, b, c), 0, chain))
        // Reload or Try again on B's page: B again, in place.
        assertEquals(CertReissue(0, b), certPageReissue(listOf(a, b), 1, chain))
        // A link that redirected there: a new entry, for the link's URL.
        assertEquals(CertReissue(null, b), certPageReissue(listOf(a, c), 1, chain))
        // A refusal with no redirect is issued on its own URL.
        assertEquals(CertReissue(-1, bad), certPageReissue(listOf(a, bad, c), 2, listOf(bad)))
    }

    @Test
    fun `a refused step onto a URL held on both sides goes where the step was headed`() {
        val x = "http://x.example/"
        val n = "http://n.example/"
        val o = "http://o.example/"
        val d = "http://d.example/"
        val bad = "https://self-signed.example/"
        val chain = listOf(n, bad)
        val list = listOf(x, n, o, n, d)
        // X → N → O → N → D, on O: the chrome's Forward was refused —
        // the same Forward again, into the entry ahead, D kept past it.
        assertEquals(CertReissue(1, n), certPageReissue(list, 2, chain, steppedTo = 3))
        // Its Back: the entry behind.
        assertEquals(CertReissue(-1, n), certPageReissue(list, 2, chain, steppedTo = 1))
        // A step the page took itself (nothing recorded): Back on a tie.
        assertEquals(CertReissue(-1, n), certPageReissue(list, 2, chain))
        // A recorded step whose entry holds none of the chain (a link
        // from the page after it) is no evidence: the nearer entry.
        assertEquals(CertReissue(-1, n), certPageReissue(list, 2, chain, steppedTo = 4))
        // One outside the list likewise.
        assertEquals(CertReissue(-1, n), certPageReissue(list, 2, chain, steppedTo = 7))
    }

    @Test
    fun `the main-frame chain follows a navigation from its start through its redirects`() {
        val chain = MainFrameChain()
        val a = "http://a.example/"
        val b = "http://b.example/"
        val bad = "https://self-signed.example/"
        // A Back: the interceptor sees B, the WebView follows its 302.
        chain.requested(b)
        chain.requested(b) // a retried first request
        chain.redirected(bad)
        chain.requested(bad) // the hop, if the interceptor sees it at all
        assertEquals(listOf(b, bad), chain.endingAt(bad))
        // A hop reported to neither callback: the refused URL alone.
        assertEquals(listOf("https://elsewhere.example/"), chain.endingAt("https://elsewhere.example/"))
        // A new navigation starts a new chain; so does a request for
        // anything but the latest URL.
        chain.started(a)
        assertEquals(listOf(a), chain.endingAt(a))
        chain.redirected(b)
        chain.requested(a)
        assertEquals(listOf(bad), chain.endingAt(bad))
        assertEquals(listOf(a), chain.endingAt(a))
        // A commit ends it.
        chain.committed()
        assertEquals(listOf(a), chain.endingAt(a))
        chain.redirected(bad)
        assertEquals(listOf(bad), chain.endingAt(bad))
    }

    @Test
    fun `the certificate page answers only its own re-issue, once`() {
        val bad = "https://self-signed.badssl.com/"
        val slot = CertRefusalSlot()
        assertTrue(slot.arm(bad, "<p>page</p>"))
        assertEquals(null, slot.take("https://other.example/"))
        // Disarmed by the other request.
        assertEquals(null, slot.take(bad))
        assertFalse(slot.isServed(bad))

        slot.committed("https://other.example/")
        assertTrue(slot.arm(bad, "<p>page</p>"))
        assertEquals("<p>page</p>", slot.take(bad))
        // Only once it commits.
        assertFalse(slot.isServed(bad))
        slot.committed(bad)
        assertTrue(slot.isServed(bad))
        assertTrue(slot.isServed("$bad#x"))
        // Try again: the retry's request isn't answered, and it's refused
        // — no commit — so the page is still what's on screen.
        assertEquals(null, slot.take(bad))
        assertTrue(slot.isServed(bad))
        // The site's own page, once its certificate is fixed.
        slot.committed(bad)
        assertFalse(slot.isServed(bad))
    }

    @Test
    fun `a re-issue refused before any commit isn't issued again`() {
        val bad = "https://self-signed.badssl.com/"
        val slot = CertRefusalSlot()
        assertTrue(slot.arm(bad, "p"))
        // The re-issue never reached the interceptor and was refused too.
        assertFalse(slot.arm(bad, "p"))
        // The user's next try arms again.
        assertTrue(slot.arm(bad, "p"))
        // A committed page in between: a new refusal.
        slot.committed(bad)
        assertTrue(slot.arm(bad, "p"))
    }

    @Test
    fun `the certificate page escapes the host and address`() {
        val facts = CertFacts(setOf(SSL_UNTRUSTED), null, null, "<x>", "Evil & Co")
        val html = certErrorPageHtml("https://h.example/?q=\"<b>", "h<i>.example", facts, 0L)
        assertTrue(html.contains("<b>h&lt;i&gt;.example</b>"))
        assertTrue(html.contains("href=\"https://h.example/?q=&quot;&lt;b&gt;\""))
        assertTrue(html.contains("Issued to: &lt;x&gt;"))
        assertTrue(html.contains("Issued by: Evil &amp; Co"))
        assertTrue(html.contains("wasn't issued by an authority this device trusts"))
        assertFalse(html.contains("<script"))
    }

    @Test
    fun `the certificate page for a redirect retries the entry's own url`() {
        val facts = CertFacts(setOf(SSL_UNTRUSTED), null, null, null, null)
        val html = certErrorPageHtml("https://bad.example/", "bad.example", facts, 0L, retryUrl = "http://b.example/")
        assertTrue(html.contains("href=\"http://b.example/\""))
        assertTrue(html.contains("https://bad.example/"))
    }
}
