package baby.freedom.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchEnginesTest {

    @Test
    fun `built-in set matches desktop, DuckDuckGo default`() {
        assertEquals(
            listOf("duckduckgo", "google", "bing", "brave", "ecosia", "startpage"),
            SearchEngines.BUILT_IN.map { it.id },
        )
        assertEquals("duckduckgo", SearchEngines.DEFAULT.id)
        assertEquals(
            "https://www.startpage.com/sp/search?query={searchTerms}",
            SearchEngines.templateFor("startpage", null),
        )
    }

    @Test
    fun `typed text searches on the chosen template`() {
        assertEquals(
            "https://duckduckgo.com/?q=hello%20world",
            UrlParser.toUrl(" hello world ", SearchEngines.DEFAULT.template),
        )
        assertEquals(
            "https://www.google.com/search?q=swarm",
            UrlParser.toUrl("swarm", SearchEngines.templateFor("google", null)),
        )
        // URLs and hosts are never searched, whatever the engine.
        assertEquals(
            "https://example.com",
            UrlParser.toUrl("example.com", SearchEngines.templateFor("bing", null)),
        )
    }

    @Test
    fun `query is encodeURIComponent-encoded`() {
        assertEquals(
            "a%26b%3Dc%2Fd%3F%23%2B%25-_.!~*'()%C3%A9%F0%9F%90%9D",
            SearchEngines.encodeQueryComponent("a&b=c/d?#+%-_.!~*'()é🐝"),
        )
    }

    @Test
    fun `custom template accepts https with one placeholder`() {
        assertEquals(
            "https://search.example/find?q={searchTerms}&lang=en",
            SearchEngines.normalizeTemplate("  https://search.example/find?q={searchTerms}&lang=en "),
        )
        // %s is canonicalised to {searchTerms}.
        assertEquals(
            "https://search.example/{searchTerms}",
            SearchEngines.normalizeTemplate("https://search.example/%s"),
        )
        // Loopback http, for an engine self-hosted on the device.
        assertEquals(
            "http://127.0.0.1:8888/search?q={searchTerms}",
            SearchEngines.normalizeTemplate("http://127.0.0.1:8888/search?q=%s"),
        )
        assertEquals(
            "http://localhost/?q={searchTerms}",
            SearchEngines.normalizeTemplate("http://localhost/?q={searchTerms}"),
        )
        assertEquals(
            "http://[::1]:8080/?q={searchTerms}",
            SearchEngines.normalizeTemplate("http://[::1]:8080/?q={searchTerms}"),
        )
        assertEquals(
            "HTTPS://Search.Example/?q={searchTerms}",
            SearchEngines.normalizeTemplate("HTTPS://Search.Example/?q={searchTerms}"),
        )
    }

    @Test
    fun `custom template rejects anything else`() {
        listOf(
            "",
            "   ",
            "https://search.example/",                          // no placeholder
            "https://search.example/?q={searchTerms}&r={searchTerms}", // two
            "https://search.example/?q=%s&r={searchTerms}",     // both spellings
            "https://search.example/?q=%s%s",
            "http://search.example/?q={searchTerms}",            // plain http
            "http://192.168.1.2/?q={searchTerms}",               // http, not loopback
            "ftp://search.example/?q={searchTerms}",
            "javascript:alert(1)//{searchTerms}",
            "file:///sdcard/{searchTerms}",
            "bzz://search.eth/?q={searchTerms}",
            "https://user:pw@search.example/?q={searchTerms}",   // user-info
            "https:///?q={searchTerms}",                          // no host
            "search.example/?q={searchTerms}",                    // not absolute
            "https://search.example/?q={searchTerms}" + "a".repeat(2048),
        ).forEach { assertNull(it, SearchEngines.normalizeTemplate(it)) }
    }

    @Test
    fun `stale or invalid settings fall back to the default`() {
        val ddg = SearchEngines.DEFAULT.template
        assertEquals(ddg, SearchEngines.templateFor(null, null))
        assertEquals(ddg, SearchEngines.templateFor("altavista", null))
        assertEquals(ddg, SearchEngines.templateFor("custom", null))
        assertEquals(ddg, SearchEngines.templateFor("custom", "http://evil.example/{searchTerms}"))
        assertEquals(
            "https://s.example/?q={searchTerms}",
            SearchEngines.templateFor("custom", "https://s.example/?q=%s"),
        )
        assertEquals("DuckDuckGo", SearchEngines.labelFor("altavista"))
        assertEquals("Brave Search", SearchEngines.labelFor("brave"))
    }
}
