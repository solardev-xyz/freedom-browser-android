package baby.freedom.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AdblockEngineTest {

    private fun engine(vararg lines: String) = AdblockEngine.build(listOf(lines.joinToString("\n")))

    private fun AdblockEngine.blocks(
        url: String,
        page: String = "https://news.example/article",
        types: Int = RequestType.UNKNOWN,
    ): Boolean = shouldBlock(url, hostOfUrl(url)!!, types, page, hostOfUrl(page))

    @Test
    fun `host rules block the host and its subdomains, nothing else`() {
        val e = engine("||ads.example.com^")
        assertTrue(e.blocks("https://ads.example.com/banner.js"))
        assertTrue(e.blocks("https://cdn.ads.example.com/x.png"))
        assertTrue(e.blocks("http://ads.example.com:8080/"))
        assertFalse(e.blocks("https://example.com/ads.example.com"))
        assertFalse(e.blocks("https://badads.example.com/x"))
        assertFalse(e.blocks("https://ads.example.community/x"))
    }

    @Test
    fun `path patterns, anchors, wildcards and separators`() {
        val e = engine(
            "/banner/*/img^",
            "|https://track.",
            "swf|",
            "||cdn.example.org/ads/",
            "-ad-300x250.",
        )
        assertTrue(e.blocks("https://site.example/banner/big/img?x=1"))
        assertTrue(e.blocks("https://site.example/banner/big/img"))
        assertFalse(e.blocks("https://site.example/banner/big/imgs"))
        assertTrue(e.blocks("https://track.example/p"))
        assertFalse(e.blocks("https://x.example/?u=https://track.example"))
        assertTrue(e.blocks("https://x.example/movie.swf"))
        assertFalse(e.blocks("https://x.example/movie.swf?x"))
        assertTrue(e.blocks("https://cdn.example.org/ads/1.js"))
        assertFalse(e.blocks("https://cdn.example.org/assets/1.js"))
        assertTrue(e.blocks("https://x.example/img/top-ad-300x250.png"))
    }

    @Test
    fun `regex filters`() {
        val e = engine("/^https?:\\/\\/[a-z]{8}\\.xyz\\/[0-9]+\\//")
        assertTrue(e.blocks("https://abcdefgh.xyz/123/a.js"))
        assertFalse(e.blocks("https://abcdefg.xyz/123/a.js"))
    }

    @Test
    fun `regex filters with options, and regex exceptions`() {
        val e = engine(
            "/pixel[0-9]+\\.gif/\$image,third-party",
            "/end[0-9]\$/\$image",
            "||cdn.example^",
            "@@/cdn\\.example\\/ok[0-9]+\\//\$image",
        )
        assertTrue(parseNetworkFilter("/pixel[0-9]+\\.gif/\$image,third-party")!!.regex != null)
        assertTrue(e.blocks("https://tracker.example/pixel123.gif", types = RequestType.IMAGE))
        assertFalse(e.blocks("https://tracker.example/pixel123.gif", types = RequestType.SCRIPT))
        assertFalse(e.blocks("https://news.example/pixel123.gif", types = RequestType.IMAGE))
        // A `$` in the body is the regex's, the last one the options'.
        assertTrue(e.blocks("https://x.example/end7", types = RequestType.IMAGE))
        assertFalse(e.blocks("https://x.example/end7x", types = RequestType.IMAGE))
        // The exception is honoured, not dropped as a glob that never matches.
        assertTrue(e.blocks("https://cdn.example/ads/1.gif", types = RequestType.IMAGE))
        assertFalse(e.blocks("https://cdn.example/ok12/1.gif", types = RequestType.IMAGE))
        assertTrue(e.blocks("https://cdn.example/ok12/1.js", types = RequestType.SCRIPT))
    }

    @Test
    fun `exceptions win unless the block is important`() {
        val e = engine(
            "||ads.example.com^",
            "@@||ads.example.com/allowed/",
            "||tracker.example^\$important",
            "@@||tracker.example^",
        )
        assertTrue(e.blocks("https://ads.example.com/banner.js"))
        assertFalse(e.blocks("https://ads.example.com/allowed/x.js"))
        assertTrue(e.blocks("https://tracker.example/p"))
    }

    @Test
    fun `third-party and domain options`() {
        val e = engine(
            "||widgets.example^\$third-party",
            "||social.example^\$domain=news.example|~sport.news.example",
            "||cdn.example^\$~third-party",
        )
        assertTrue(e.blocks("https://widgets.example/w.js", page = "https://news.example/"))
        assertFalse(e.blocks("https://widgets.example/w.js", page = "https://www.widgets.example/"))
        assertTrue(e.blocks("https://social.example/s.js", page = "https://www.news.example/"))
        assertFalse(e.blocks("https://social.example/s.js", page = "https://sport.news.example/"))
        assertFalse(e.blocks("https://social.example/s.js", page = "https://other.example/"))
        assertTrue(e.blocks("https://cdn.example/x", page = "https://a.cdn.example/"))
        assertFalse(e.blocks("https://cdn.example/x", page = "https://news.example/"))
    }

    @Test
    fun `type options meet the inferred type set`() {
        val e = engine("||media.example^\$image", "||js.example^\$script", "||nocss.example^\$~stylesheet")
        assertTrue(e.blocks("https://media.example/a.png", types = RequestType.IMAGE))
        assertFalse(e.blocks("https://media.example/a.js", types = RequestType.SCRIPT))
        // A wildcard-Accept load could be a script: a `$script` filter applies.
        assertTrue(e.blocks("https://js.example/loader", types = RequestType.UNKNOWN))
        assertFalse(e.blocks("https://js.example/a.png", types = RequestType.IMAGE))
        assertFalse(e.blocks("https://nocss.example/a.css", types = RequestType.STYLESHEET))
        assertTrue(e.blocks("https://nocss.example/a.js", types = RequestType.SCRIPT))
    }

    @Test
    fun `unsupported options drop the whole filter, popup- and document-only too`() {
        val e = engine(
            "||csp.example^\$csp=script-src 'none'",
            "||redirect.example^\$redirect=noopjs",
            "||popup.example^\$popup",
            "||page.example^\$document",
        )
        assertFalse(e.blocks("https://csp.example/a.js"))
        assertFalse(e.blocks("https://redirect.example/a.js"))
        assertFalse(e.blocks("https://popup.example/a.js"))
        assertFalse(e.blocks("https://page.example/a.js"))
    }

    @Test
    fun `a document exception exempts the whole page`() {
        val e = engine("||ads.example.com^", "@@||trusted.example^\$document")
        assertTrue(e.blocks("https://ads.example.com/a.js", page = "https://news.example/"))
        assertFalse(e.blocks("https://ads.example.com/a.js", page = "https://www.trusted.example/"))
        assertTrue(e.documentExempt("https://trusted.example/", "trusted.example"))
    }

    @Test
    fun `request types from WebView's headers and the URL`() {
        assertEquals(RequestType.STYLESHEET, requestTypes("https://a.example/x", mapOf("Accept" to "text/css,*/*;q=0.1")))
        assertEquals(RequestType.IMAGE, requestTypes("https://a.example/x", mapOf("Accept" to "image/avif,image/webp,*/*")))
        assertEquals(RequestType.SUBDOCUMENT, requestTypes("https://a.example/x", mapOf("Accept" to "text/html,application/xhtml+xml")))
        assertEquals(RequestType.MEDIA, requestTypes("https://a.example/v", mapOf("Accept" to "*/*", "Range" to "bytes=0-")))
        assertEquals(RequestType.SCRIPT, requestTypes("https://a.example/app.js?v=1", mapOf("Accept" to "*/*")))
        assertEquals(RequestType.UNKNOWN, requestTypes("https://a.example/collect", mapOf("Accept" to "*/*")))
    }

    @Test
    fun `specific cosmetic rules, with exceptions`() {
        val e = engine(
            "news.example##.promo",
            "news.example,~live.news.example##.sidebar-ad",
            "sport.news.example#@#.promo",
        )
        assertTrue(css(e, "news.example").contains(".promo{display:none!important}"))
        assertTrue(css(e, "www.news.example").contains(".sidebar-ad"))
        assertFalse(css(e, "live.news.example").contains(".sidebar-ad"))
        assertFalse(css(e, "sport.news.example").contains(".promo"))
        assertEquals("", css(e, "other.example"))
    }

    @Test
    fun `generic cosmetic rules are keyed by their leading class or id`() {
        val e = engine(
            "##.ad-banner",
            "###top-ad > div",
            "##[data-ad-slot]",
            "~safe.example##.ad-box",
            "safe2.example#@#.ad-banner",
        )
        // The unkeyed one comes with the first answer.
        assertTrue(css(e, "news.example").contains("[data-ad-slot]"))
        assertFalse(css(e, "news.example").contains(".ad-banner"))
        val keyed = e.cosmeticsForTokens(listOf(".ad-banner", "#top-ad", ".unrelated"), "https://news.example/", "news.example", null, null)
        assertTrue(keyed.contains(".ad-banner{"))
        assertTrue(keyed.contains("#top-ad > div{"))
        assertEquals("", e.cosmeticsForTokens(listOf(".ad-box"), "https://safe.example/", "safe.example", null, null))
        assertEquals("", e.cosmeticsForTokens(listOf(".ad-banner"), "https://safe2.example/", "safe2.example", null, null))
    }

    @Test
    fun `elemhide and generichide exceptions`() {
        val e = engine(
            "##.ad-banner",
            "##[data-ad]",
            "shop.example##.promo",
            "@@||shop.example^\$generichide",
            "@@||clean.example^\$elemhide",
        )
        val shop = css(e, "shop.example")
        assertTrue(shop.contains(".promo"))
        assertFalse(shop.contains("[data-ad]"))
        assertEquals("", e.cosmeticsForTokens(listOf(".ad-banner"), "https://shop.example/", "shop.example", null, null))
        assertEquals("", css(e, "clean.example"))
    }

    @Test
    fun `procedural, scriptlet and extended cosmetic filters are skipped`() {
        val e = engine(
            "news.example##div:has-text(Sponsored)",
            "news.example##+js(set-constant, x, 1)",
            "news.example#?#div:-abp-has(.ad)",
            "news.example#\$#body { overflow: auto !important; }",
            "news.example##^script:has-text(ad)",
            "news.example##.ok",
        )
        assertEquals(".ok{display:none!important}\n", css(e, "news.example"))
    }

    @Test
    fun `cosmetic keys`() {
        assertEquals(".ad-banner", cosmeticKey(".ad-banner > img"))
        assertEquals("#top_ad", cosmeticKey("#top_ad:not(.x)"))
        assertEquals(null, cosmeticKey("div.ad"))
        assertEquals(null, cosmeticKey(".\\[weird\\]"))
    }

    private fun css(e: AdblockEngine, host: String) =
        e.initialCosmetics("https://$host/", host, "https://$host/", host)

    /**
     * The lists the app actually ships: they compile, a well-known ad
     * host and tracker are blocked, the first party isn't, and a lookup
     * is cheap. Also prints how long the build takes on this machine.
     */
    @Test
    fun `bundled lists`() {
        val dir = File("src/main/assets/adblock")
        val texts = AdblockCategory.entries.map { File(dir, it.file).readText() }
        val t0 = System.nanoTime()
        val e = AdblockEngine.build(texts)
        val buildMs = (System.nanoTime() - t0) / 1_000_000
        println("bundled lists: ${e.filterCount} filters in $buildMs ms")
        assertTrue(e.filterCount > 100_000)

        val page = "https://www.example.com/news/story.html"
        assertTrue(e.blocks("https://securepubads.g.doubleclick.net/tag/js/gpt.js", page, RequestType.SCRIPT))
        assertTrue(e.blocks("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js", page, RequestType.SCRIPT))
        assertTrue(e.blocks("https://www.google-analytics.com/analytics.js", page, RequestType.SCRIPT))
        assertFalse(e.blocks("https://www.example.com/static/app.js", page, RequestType.SCRIPT))
        assertFalse(e.blocks("https://fonts.gstatic.com/s/roboto/v30/font.woff2", page, RequestType.FONT))
        assertFalse(e.blocks("https://upload.wikimedia.org/wikipedia/commons/a/a9/Example.jpg", page, RequestType.IMAGE))

        val urls = listOf(
            "https://www.example.com/static/app.js",
            "https://cdn.jsdelivr.net/npm/lodash@4.17.21/lodash.min.js",
            "https://images.example.org/photos/2026/09/header-large.jpg?w=1200&q=80",
            "https://api.example.com/v2/items?page=3&sort=recent",
        )
        val t1 = System.nanoTime()
        repeat(2_000) { for (u in urls) e.blocks(u, page) }
        val perLookupUs = (System.nanoTime() - t1) / 1_000 / (2_000 * urls.size)
        println("bundled lists: ~$perLookupUs µs per lookup")
    }
}
