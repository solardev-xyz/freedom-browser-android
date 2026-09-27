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
        assertEquals("DuckDuckGo", SearchEngines.labelFor("altavista", null))
        assertEquals("Brave Search", SearchEngines.labelFor("brave", null))
    }

    @Test
    fun `row, picker and search agree on the engine in use`() {
        // One resolver behind the Settings row, the picker's checked radio
        // and the address bar: a `custom` id with an unusable template is
        // DuckDuckGo everywhere, never "Custom" in one place only.
        val stale = "http://evil.example/{searchTerms}"
        assertEquals("duckduckgo", SearchEngines.effectiveId("custom", stale))
        assertEquals("duckduckgo", SearchEngines.effectiveId("custom", ""))
        assertEquals("DuckDuckGo", SearchEngines.labelFor("custom", stale))
        assertEquals(SearchEngines.DEFAULT.template, SearchEngines.templateFor("custom", stale))

        val good = "https://s.example/?q={searchTerms}"
        assertEquals("custom", SearchEngines.effectiveId("custom", good))
        assertEquals("Custom", SearchEngines.labelFor("custom", good))
        assertEquals(good, SearchEngines.templateFor("custom", good))

        // A saved custom template doesn't matter once a built-in is chosen.
        assertEquals("bing", SearchEngines.effectiveId("bing", good))
        assertEquals("duckduckgo", SearchEngines.effectiveId("altavista", good))
        assertEquals("duckduckgo", SearchEngines.effectiveId(null, null))
    }

    @Test
    fun `each rejection names its reason`() {
        fun reason(t: String) = SearchEngines.validateTemplate(t).rejection
        assertEquals(SearchEngines.Rejection.EMPTY, reason("  "))
        assertEquals(
            SearchEngines.Rejection.TOO_LONG,
            reason("https://s.example/?q={searchTerms}" + "a".repeat(2048)),
        )
        assertEquals(SearchEngines.Rejection.NO_PLACEHOLDER, reason("https://s.example/"))
        assertEquals(SearchEngines.Rejection.MULTIPLE_PLACEHOLDERS, reason("https://s.example/?q=%s&r=%s"))
        assertEquals(SearchEngines.Rejection.NOT_A_URL, reason("s.example/?q=%s"))
        assertEquals(SearchEngines.Rejection.NOT_A_URL, reason("https:///?q=%s"))
        assertEquals(SearchEngines.Rejection.SCHEME, reason("http://s.example/?q=%s"))
        assertEquals(SearchEngines.Rejection.SCHEME, reason("javascript:alert(1)//%s"))
        assertEquals(SearchEngines.Rejection.USER_INFO, reason("https://user:pw@s.example/?q=%s"))
        assertNull(reason("https://s.example/?q=%s"))
    }

    @Test
    fun `custom template accepts exactly what desktop's new URL accepts`() {
        // Expected values are desktop's normalizeSearchUrlTemplate
        // (src/renderer/lib/search-utils.js) run on Node's WHATWG `URL`.
        listOf(
            "https://my_host.example/?q=%s" to "https://my_host.example/?q={searchTerms}",
            "https://suche.bücher.de/?q=%s" to "https://suche.bücher.de/?q={searchTerms}",
            "https://x.example/?q={searchTerms}&fmt={json}" to "https://x.example/?q={searchTerms}&fmt={json}",
            "https:search.example/?q=%s" to "https:search.example/?q={searchTerms}",
            "https:\\\\search.example\\\\?q=%s" to "https:\\\\search.example\\\\?q={searchTerms}",
            "https://@s.example/?q=%s" to "https://@s.example/?q={searchTerms}",
            "https://:@s.example/?q=%s" to "https://:@s.example/?q={searchTerms}",
            "https://u@s.example/?q=%s" to null,
            "https://:p@s.example/?q=%s" to null,
            "https://foo@bar@s.example/?q=%s" to null,
            "http://LOCALHOST/?q=%s" to "http://LOCALHOST/?q={searchTerms}",
            "http://127.1/?q=%s" to "http://127.1/?q={searchTerms}",
            "http://0x7f.0.0.1/?q=%s" to "http://0x7f.0.0.1/?q={searchTerms}",
            "http://0177.0.0.1/?q=%s" to "http://0177.0.0.1/?q={searchTerms}",
            "http://2130706433/?q=%s" to "http://2130706433/?q={searchTerms}",
            "http://127.0.0.1.:80/?q=%s" to "http://127.0.0.1.:80/?q={searchTerms}",
            "http://[0:0:0:0:0:0:0:1]/?q=%s" to "http://[0:0:0:0:0:0:0:1]/?q={searchTerms}",
            "http://[::1]:8080/?q=%s" to "http://[::1]:8080/?q={searchTerms}",
            "http://[::ffff:127.0.0.1]/?q=%s" to null,
            "http://localhost./?q=%s" to null,
            "http://[::1/?q=%s" to null,
            "https://[zz]/?q=%s" to null,
            "https://1.2.3.999/?q=%s" to null,
            "https://-1.2/?q=%s" to null,
            "https://0x/?q=%s" to "https://0x/?q={searchTerms}",
            "https://s.example:99999/?q=%s" to null,
            "https://s.example:0080/?q=%s" to "https://s.example:0080/?q={searchTerms}",
            "https://s.example:/?q=%s" to "https://s.example:/?q={searchTerms}",
            "https://s.example:8a/?q=%s" to null,
            "https://s ex.example/?q=%s" to null,
            "https://s%20ex.example/?q=%s" to null,
            "https://s%2eexample/?q=%s" to "https://s%2eexample/?q={searchTerms}",
            "https://s.example^/?q=%s" to null,
            "https://s.example/p q?q=%s" to "https://s.example/p q?q={searchTerms}",
            "https://s.example/?q=%s#frag|x" to "https://s.example/?q={searchTerms}#frag|x",
            "https://s.example/?q=%s&x=%zz" to "https://s.example/?q={searchTerms}&x=%zz",
            "HtTpS://S.EXAMPLE/%s" to "HtTpS://S.EXAMPLE/{searchTerms}",
            "https://%s.example/" to "https://{searchTerms}.example/",
            "https://xn--bcher-kva.de/?q=%s" to "https://xn--bcher-kva.de/?q={searchTerms}",
            "https://ＥＸＡＭＰＬＥ.com/?q=%s" to "https://ＥＸＡＭＰＬＥ.com/?q={searchTerms}",
            "https://a..b/?q=%s" to "https://a..b/?q={searchTerms}",
            "localhost:8080/?q=%s" to null,
            "https://s.exa\tmple/?q=%s" to "https://s.exa\tmple/?q={searchTerms}",
            "http://evil.example/?q=%s" to null,
            "javascript:alert(1)//%s" to null,
        ).forEach { (input, desktop) ->
            assertEquals(input, desktop, SearchEngines.normalizeTemplate(input))
        }
    }
}
