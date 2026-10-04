package baby.freedom.mobile.browser

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/** `$redirect=` / `$redirect-rule=` filters and their stand-ins (#405). */
class AdblockRedirectTest {

    private val catalog: ScriptletCatalog = checkNotNull(
        ScriptletCatalog.parse(File("src/main/assets/adblock/$SCRIPTLET_RESOURCES_FILE").readText()),
    )

    private fun engine(vararg lines: String) =
        AdblockEngine.build(listOf(FilterListText(lines.joinToString("\n"))), catalog)

    private val page = "https://www.spiegel.de/"

    private fun AdblockEngine.blocks(url: String, types: Int = RequestType.SCRIPT) =
        shouldBlock(url, hostOfUrl(url)!!, types, page, hostOfUrl(page))

    private fun AdblockEngine.redirect(url: String, types: Int = RequestType.SCRIPT) =
        redirectFor(url, hostOfUrl(url)!!, types, hostOfUrl(page))

    @Test
    fun `the catalog serves uBlock's stand-ins by name and alias`() {
        assertEquals("noop.js", catalog.redirect("noopjs")?.canonical)
        assertEquals("noop.js", catalog.redirect("noop.js")?.canonical)
        assertEquals("noop.js", catalog.redirect("abp-resource:blank-js")?.canonical)
        assertEquals("application/javascript", catalog.redirect("noopjs")?.mimeType)
        // A base64 body is decoded: a real GIF.
        val gif = checkNotNull(catalog.redirect("1x1.gif"))
        assertEquals("image/gif", gif.mimeType)
        assertArrayEquals("GIF89a".toByteArray(), gif.body.copyOf(6))
        assertEquals("noop-0.1s.mp3", catalog.redirect("noopmp3-0.1s")?.canonical)
        assertEquals(0, catalog.redirect("empty")?.body?.size)
        // An extension page a site can't reach here isn't served.
        assertNull(catalog.redirect("click2load.html"))
        assertNull(catalog.redirect("no-such-thing.js"))
    }

    @Test
    fun `a redirect filter blocks and names its stand-in`() {
        val e = engine("||ads.example/gpt.js\$script,redirect=googletagservices_gpt.js")
        assertTrue(e.blocks("https://ads.example/gpt.js"))
        assertEquals("googletagservices_gpt.js", e.redirect("https://ads.example/gpt.js"))
        assertFalse(e.blocks("https://ads.example/other.js"))
    }

    @Test
    fun `a redirect-rule only names a stand-in for what something else blocks`() {
        val e = engine(
            "*\$script,redirect-rule=noopjs,domain=spiegel.de",
            "||adserver.example^",
        )
        // Not blocked by anything: the redirect-rule alone doesn't block.
        assertFalse(e.blocks("https://cdn.example/app.js"))
        // Blocked by the host rule: served noop.js instead of a 403.
        assertTrue(e.blocks("https://adserver.example/tag.js"))
        assertEquals("noop.js", e.redirect("https://adserver.example/tag.js"))
        // Not a script: no stand-in.
        assertNull(e.redirect("https://adserver.example/pixel.gif", RequestType.IMAGE))
        // On another site the rule's domain doesn't match.
        val other = "https://news.example/"
        assertNull(e.redirectFor("https://adserver.example/tag.js", "adserver.example", RequestType.SCRIPT, hostOfUrl(other)))
    }

    @Test
    fun `the highest priority wins, the first listed on a tie`() {
        val e = engine(
            "||adserver.example^",
            "||adserver.example^\$script,redirect-rule=noop.txt",
            "||adserver.example^\$script,redirect-rule=noopjs:10",
            "||adserver.example^\$script,redirect-rule=nooptext:10",
        )
        assertEquals("noop.js", e.redirect("https://adserver.example/x.js"))
    }

    @Test
    fun `exceptions lift the stand-in, not the block`() {
        val e = engine(
            "||adserver.example^\$redirect=noopjs",
            "||cdn.example^\$redirect=noopjs",
            "||cdn.example^\$redirect=1x1.gif:5,image",
            "@@||adserver.example^\$redirect-rule",
            "@@||cdn.example^\$redirect=1x1.gif",
        )
        assertTrue(e.blocks("https://adserver.example/x.js"))
        assertNull(e.redirect("https://adserver.example/x.js"))
        assertTrue(e.blocks("https://cdn.example/x.gif", RequestType.IMAGE))
        // The named exception lifts only its own stand-in.
        assertNull(e.redirect("https://cdn.example/x.gif", RequestType.IMAGE))
        assertEquals("noop.js", e.redirect("https://cdn.example/x.js"))
    }

    @Test
    fun `an unknown stand-in still blocks, a redirect-rule to one is dropped`() {
        val e = engine(
            "||a.example^\$redirect=no-such-thing.js",
            "||b.example^\$redirect-rule=no-such-thing.js",
            "||c.example^\$redirect=none",
        )
        assertTrue(e.blocks("https://a.example/x.js"))
        assertNull(e.redirect("https://a.example/x.js"))
        assertFalse(e.blocks("https://b.example/x.js"))
        assertFalse(e.blocks("https://c.example/x.js"))
        assertEquals(listOf(FilterListCounts(3, 1)), e.listCounts)
    }

    /** spiegel.de's own uBlock rules, as shipped: a blocked ad script there gets noop.js. */
    @Test
    fun `bundled lists give spiegel's blocked scripts a stand-in`() {
        val dir = File("src/main/assets/adblock")
        val ads = AdblockCategory.ADS
        val e = AdblockEngine.build(
            listOf(FilterListText(File(dir, ads.file).readText())) +
                ads.bundledExtras.map { FilterListText(File(dir, it.file).readText(), it.trustedScriptlets) },
            catalog,
        )
        val url = "https://securepubads.g.doubleclick.net/tag/js/gpt.js"
        assertTrue(e.blocks(url))
        assertNotNull(e.redirect(url))
    }

    @Test
    fun `EasyList Germany is on by default for German speakers and German-speaking regions`() {
        assertTrue(germanListByDefault(listOf(Locale.GERMANY)))
        assertTrue(germanListByDefault(listOf(Locale.forLanguageTag("de-CH"))))
        assertTrue(germanListByDefault(listOf(Locale.US, Locale.forLanguageTag("de"))))
        assertTrue(germanListByDefault(listOf(Locale.forLanguageTag("en-AT"))))
        assertFalse(germanListByDefault(listOf(Locale.US)))
        assertFalse(germanListByDefault(listOf(Locale.FRANCE, Locale.UK)))
        assertFalse(germanListByDefault(emptyList()))
    }
}
