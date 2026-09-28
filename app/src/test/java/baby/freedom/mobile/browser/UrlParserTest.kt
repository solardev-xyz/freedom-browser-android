package baby.freedom.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlParserTest {
    private val ddg = SearchEngines.DEFAULT.template

    private fun url(input: String) = UrlParser.toUrl(input, ddg)

    @Test
    fun `host with a port becomes the http address the WebView requests`() {
        // #180 R6-F2: these used to pass through verbatim, the scheme
        // check taking `localhost:` for a scheme.
        assertEquals("http://localhost:8700/x", url("localhost:8700/x"))
        assertEquals("http://localhost:8700", url("localhost:8700"))
        assertEquals("http://LocalHost:8700/redir?s1", url(" LocalHost:8700/redir?s1 "))
        assertEquals("http://example.com:8080/x", url("example.com:8080/x"))
        assertEquals("http://app.localhost:3000?q=1", url("app.localhost:3000?q=1"))
        assertEquals("http://example.com:8080#top", url("example.com:8080#top"))
        assertEquals("http://[::1]:8700/x", url("[::1]:8700/x"))
        assertFalse(UrlParser.isSearch("localhost:8700/x"))
        assertFalse(UrlParser.isSearch("example.com:8080"))
    }

    @Test
    fun `an ipv4 literal with a port keeps https`() {
        assertEquals("https://1.1.1.1:8080", url("1.1.1.1:8080"))
        assertEquals("https://127.0.0.1:8700/x", url("127.0.0.1:8700/x"))
    }

    @Test
    fun `real schemes are kept as typed`() {
        for (s in listOf(
            "http://localhost:8700/x",
            "https://example.com:8080/x",
            "ens:vitalik.eth",
            "ens://vitalik.eth",
            "bzz://abc/index.html",
            "ipfs://bafy/x",
            "mailto:alice@example.com",
            "tel:5551234",
            "tel:+15551234",
            "sms:5551234",
            "about:blank",
            "data:text/plain,hi",
            "javascript:void(0)",
            "intent:#Intent;scheme=x;end",
            "file:///sdcard/a.html",
        )) {
            assertEquals(s, s, url(s))
            assertFalse(s, UrlParser.isSearch(s))
        }
    }

    @Test
    fun `bare hosts and searches are unchanged`() {
        assertEquals("https://example.com", url("example.com"))
        assertEquals("https://example.com/a:1", url("example.com/a:1"))
        assertTrue(UrlParser.isSearch("localhost"))
        assertTrue(UrlParser.isSearch("what is 2:30"))
        // A port that isn't digits isn't a port.
        assertEquals("example.com:abc", url("example.com:abc"))
    }
}
