package baby.freedom.mobile.browser

import android.webkit.WebViewClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
        assertEquals(
            "Expired on 12 Apr 2015\n\nIssued to: *.badssl.com\nIssued by: Some CA",
            certErrorDetail(f, "expired.badssl.com", now),
        )
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
}
