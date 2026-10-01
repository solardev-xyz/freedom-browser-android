package baby.freedom.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mozilla.javascript.Context
import java.io.File

/**
 * Scriptlets (#318): `+js(…)` rules in the engine, the catalog of
 * uBlock Origin's scriptlets the app ships, and the frame script's
 * host and allowlist checks (run for real in Rhino, with a stub
 * scriptlet — Rhino can't run uBlock's modern JavaScript itself).
 */
class AdblockScriptletsTest {

    private val catalog: ScriptletCatalog = checkNotNull(
        ScriptletCatalog.parse(File("src/main/assets/adblock/$SCRIPTLET_RESOURCES_FILE").readText()),
    )

    private fun engine(vararg lists: FilterListText) = AdblockEngine.build(lists.toList(), catalog)

    private fun untrusted(text: String) = FilterListText(text.trimIndent())
    private fun trusted(text: String) = FilterListText(text.trimIndent(), trustedScriptlets = true)

    private fun AdblockEngine.calls(host: String) = scriptletsFor(host).map { it.toString() }

    @Test
    fun `arguments split the way uBlock splits them`() {
        assertEquals(listOf("set", "a.b", "true"), parseScriptletArgs("set, a.b, true"))
        assertEquals(listOf("json-prune", "a b c"), parseScriptletArgs("json-prune, a b c"))
        // An escaped comma is part of the argument; an empty one is kept.
        assertEquals(listOf("rmnt", "script", "window,\"fetch\""), parseScriptletArgs("rmnt, script, window\\,\"fetch\""))
        assertEquals(listOf("x", "a", "", "propsToMatch", "/player?"), parseScriptletArgs("x, a, , propsToMatch, /player?"))
        assertEquals(listOf("x", "a", ""), parseScriptletArgs("x, a,"))
        // A quoted argument is taken literally, commas included.
        assertEquals(
            listOf("trusted-replace-fetch-response", "\"adPlacements\"", "\"no_ads\"", "player?"),
            parseScriptletArgs("trusted-replace-fetch-response, '\"adPlacements\"', '\"no_ads\"', player?"),
        )
        assertEquals(listOf("x", "a, b", "it's"), parseScriptletArgs("x, 'a, b', 'it\\'s'"))
        // A quote that doesn't close the argument is just a character.
        assertEquals(listOf("x", "'a", "b' c"), parseScriptletArgs("x, 'a, b' c"))
        assertEquals(listOf("nowoif"), parseScriptletArgs("nowoif"))
        assertNull(parseScriptletArgs(""))
        assertNull(parseScriptletArgs(", a"))
    }

    @Test
    fun `names and aliases resolve to the canonical scriptlet`() {
        assertEquals("set-constant", catalog.canonical("set"))
        assertEquals("set-constant", catalog.canonical("set.js"))
        assertEquals("set-constant", catalog.canonical("set-constant"))
        assertEquals("abort-on-property-read", catalog.canonical("aopr"))
        assertEquals("trusted-replace-node-text", catalog.canonical("rpnt"))
        assertEquals("adjust-setTimeout", catalog.canonical("nano-stb"))
        assertNull(catalog.canonical("no-such-scriptlet"))
        // A dependency is never a scriptlet a list can call.
        assertNull(catalog.canonical("safe-self.fn"))
        assertTrue(catalog.requiresTrust("trusted-replace-fetch-response"))
        assertFalse(catalog.requiresTrust("json-prune"))
    }

    @Test
    fun `every vetted scriptlet is in the bundled resources and breaks none of the injection rules`() {
        val forbidden = listOf(
            Regex("createElement\\(\\s*['\"](script|style)"), // CSP-checked elements
            Regex("\\beval\\s*\\("), Regex("new\\s+Function\\b"), // CSP-checked eval
            Regex("\\bchrome\\.|\\bbrowser\\."), // extension APIs
            Regex("trustedCreateHTML"), // a page global of its own
        )
        for (name in ScriptletCatalog.VETTED) {
            assertTrue("$name vetted but not bundled", catalog.isVetted(name))
            val code = catalog.code(listOf(ScriptletCall(name, emptyList())))
            for (f in forbidden) assertFalse("$name: $f", f.containsMatchIn(code))
            // The debug logger stays off: nothing here sets its secret.
            assertFalse(name, Regex("bcSecret\\s*=[^=]").containsMatchIn(code))
        }
        assertFalse(catalog.isVetted("trusted-click-element"))
        assertFalse(catalog.isVetted("trusted-create-html"))
    }

    @Test
    fun `code declares each dependency once and calls each scriptlet in its own try`() {
        val code = catalog.code(
            listOf(
                ScriptletCall("json-prune", listOf("playerAds adSlots")),
                ScriptletCall("set-constant", listOf("a.b", "undefined")),
            ),
        )
        assertEquals(1, Regex("function safeSelf\\(").findAll(code).count())
        assertTrue(code.contains("try { jsonPrune(\"playerAds adSlots\"); } catch (e) {}"))
        assertTrue(code.contains("try { setConstant(\"a.b\", \"undefined\"); } catch (e) {}"))
    }

    @Test
    fun `rules apply to their hosts and subdomains, entities under any suffix`() {
        val e = engine(
            untrusted(
                """
                example.com##+js(set, a, 1)
                news.example.org,other.example##+js(aopr, b)
                google.*##+js(nowoif)
                """,
            ),
        )
        assertEquals(listOf("+js(set-constant, a, 1)"), e.calls("example.com"))
        assertEquals(listOf("+js(set-constant, a, 1)"), e.calls("www.example.com"))
        assertEquals(emptyList<String>(), e.calls("badexample.com"))
        assertEquals(listOf("+js(abort-on-property-read, b)"), e.calls("news.example.org"))
        assertEquals(emptyList<String>(), e.calls("example.org"))
        assertEquals(listOf("+js(prevent-window-open)"), e.calls("www.google.co.uk"))
        assertEquals(listOf("+js(prevent-window-open)"), e.calls("google.de"))
        assertEquals(emptyList<String>(), e.calls("google.example.org"))
        assertEquals(listOf("example.com", "com"), scriptletKeys("example.com").take(2))
        assertTrue("www.google.*" in scriptletKeys("www.google.co.uk"))
        assertTrue("google.*" in scriptletKeys("www.google.co.uk"))
    }

    @Test
    fun `negated domains and exceptions turn calls off`() {
        val e = engine(
            untrusted(
                """
                example.com,~shop.example.com##+js(set, a, 1)
                example.com##+js(set, b, 2)
                example.com##+js(aopr, c)
                example.com#@#+js(set, b, 2)
                quiet.example.com#@#+js()
                google.*,~google.de##+js(nowoif)
                """,
            ),
        )
        assertEquals(listOf("+js(set-constant, a, 1)", "+js(abort-on-property-read, c)"), e.calls("example.com"))
        assertEquals(listOf("+js(abort-on-property-read, c)"), e.calls("shop.example.com"))
        assertEquals(emptyList<String>(), e.calls("quiet.example.com"))
        assertEquals(emptyList<String>(), e.calls("google.de"))
        assertEquals(listOf("+js(prevent-window-open)"), e.calls("google.fr"))
    }

    @Test
    fun `a document or elemhide exception for the frame's host turns its scriptlets off`() {
        val e = engine(
            untrusted(
                """
                example.com##+js(set, a, 1)
                other.example##+js(set, a, 1)
                @@||example.com^${'$'}document
                @@||other.example^${'$'}elemhide
                """,
            ),
        )
        assertEquals(emptyList<String>(), e.calls("example.com"))
        assertEquals(emptyList<String>(), e.calls("other.example"))
    }

    @Test
    fun `trusted scriptlets only from a trusted list, unvetted and generic ones never, and all counted`() {
        val rules = """
            ! a comment
            example.com##+js(trusted-replace-fetch-response, '"adSlots"', '"no_ads"', player?)
            example.com##+js(trusted-click-element, button)
            example.com##+js(no-such-scriptlet)
            ##+js(set, everywhere, 1)
            *,~edu##+js(set, everywhere, 2)
            ~example.com##+js(set, everywhere, 3)
            example.com##+js(set, x, 1)
            regex.example,kissasian.*>>##+js(set, y, 1)
            example.com##div:has-text(Ad)
            ||ads.example^
        """
        val plain = engine(untrusted(rules))
        assertEquals(listOf("+js(set-constant, x, 1)", "+js(set-constant, y, 1)"), plain.calls("example.com") + plain.calls("regex.example"))
        val counts = plain.listCounts.single()
        assertEquals(10, counts.rules)
        assertEquals(8, counts.scriptlets)
        assertEquals(2, counts.scriptletsUsed)
        // The two scriptlets and the network rule.
        assertEquals(3, counts.used)
        assertEquals(7, counts.skipped)

        val own = engine(trusted(rules))
        assertEquals(
            listOf("+js(trusted-replace-fetch-response, \"adSlots\", \"no_ads\", player?)", "+js(set-constant, x, 1)"),
            own.calls("example.com"),
        )
        assertEquals(3, own.listCounts.single().scriptletsUsed)
        // Counts are per list, in the order given.
        val both = engine(untrusted(rules), trusted(rules))
        assertEquals(listOf(2, 3), both.listCounts.map { it.scriptletsUsed })
        // No catalog, no scriptlets.
        val none = AdblockEngine.build(listOf(FilterListText(rules.trimIndent(), true)), null)
        assertEquals(emptyList<String>(), none.calls("example.com"))
    }

    @Test
    fun `a call listed twice runs once, in list order`() {
        val e = engine(
            untrusted(
                """
                example.com##+js(aopr, z)
                www.example.com,example.com##+js(set, a, 1)
                example.com##+js(aopr, z)
                """,
            ),
        )
        assertEquals(listOf("+js(abort-on-property-read, z)", "+js(set-constant, a, 1)"), e.calls("www.example.com"))
    }

    @Test
    fun `hosts the injector can scope a script to`() {
        assertEquals("www.youtube.com", scriptletHostOf("https://www.youtube.com/watch?v=x"))
        assertEquals("example.com", scriptletHostOf("HTTP://Example.COM:8080/"))
        assertNull(scriptletHostOf("https://[::1]/"))
        assertNull(scriptletHostOf("bzz://abc/"))
        assertNull(scriptletHostOf("about:blank"))
        assertTrue(isScriptletDomain("youtube.*"))
        assertFalse(isScriptletDomain("*"))
        assertFalse(isScriptletDomain("/re/"))
        assertFalse(isScriptletDomain("a..b"))
    }

    @Test
    fun `string literals escape everything outside printable ASCII`() {
        assertEquals("\"a\\\"b\\\\c\"", jsString("a\"b\\c"))
        assertEquals("\"\\u2028\\u00e9\\u000a\"", jsString(" é\n"))
    }

    /** Run [scriptletFrameJs] for a frame at [href] with [ancestors]; true if the stub scriptlet ran. */
    private fun runsIn(
        host: String,
        href: String,
        ancestors: List<String>,
        allowlist: List<String> = emptyList(),
    ): Boolean {
        val cx = Context.enter().apply { optimizationLevel = -1; languageVersion = Context.VERSION_ES6 }
        try {
            val scope = cx.initStandardObjects()
            val protocol = href.substringBefore(':') + ":"
            val hostname = if (protocol == "about:") "" else hostOfUrl(href).orEmpty()
            val list = ancestors.joinToString(",") { jsString(it) }
            cx.evaluateString(
                scope,
                """
                var ran = false, seenGlobals = null;
                var location = { hostname: ${jsString(hostname)}, protocol: ${jsString(protocol)},
                                 ancestorOrigins: [$list] };
                """,
                "dom", 1, null,
            )
            val code = "function stub() { ran = true; seenGlobals = typeof scriptletGlobals; }\ntry { stub(); } catch (e) {}\n"
            cx.evaluateString(scope, scriptletFrameJs(host, code, allowlist), "frame", 1, null)
            // Nothing the frame script declares leaks onto the page.
            assertEquals("undefined,undefined", Context.toString(cx.evaluateString(scope, "typeof stub + ',' + typeof scriptletGlobals", "t", 1, null)))
            val ran = Context.toBoolean(cx.evaluateString(scope, "ran", "t", 1, null))
            if (ran) assertEquals("object", Context.toString(cx.evaluateString(scope, "seenGlobals", "t", 1, null)))
            return ran
        } finally {
            Context.exit()
        }
    }

    @Test
    fun `the frame script runs on its host, in about frames, and not on allowlisted pages`() {
        // Top frame, and a cross-origin frame on another site's page.
        assertTrue(runsIn("www.youtube.com", "https://www.youtube.com/watch", emptyList()))
        assertTrue(runsIn("www.youtube.com", "https://www.youtube.com/embed/x", listOf("https://news.example")))
        // Another host (the origin rule would already keep it out).
        assertFalse(runsIn("www.youtube.com", "https://evil.example/", emptyList()))
        // An about:blank frame the host's page made shares its origin.
        assertTrue(runsIn("www.youtube.com", "about:blank", listOf("https://www.youtube.com")))
        // …but a top-level about: document has no page to judge by.
        assertFalse(runsIn("www.youtube.com", "about:blank", emptyList()))
        // Allowlisted: the page itself, a subdomain of an entry, a www. page.
        assertFalse(runsIn("www.youtube.com", "https://www.youtube.com/", emptyList(), listOf("youtube.com")))
        assertFalse(runsIn("m.youtube.com", "https://m.youtube.com/", emptyList(), listOf("youtube.com")))
        // A frame on an allowlisted page, by the page's (last ancestor's) origin.
        assertFalse(
            runsIn(
                "www.youtube.com", "https://www.youtube.com/embed/x",
                listOf("https://ads.example:8443", "https://www.news.example"), listOf("news.example"),
            ),
        )
        assertTrue(
            runsIn(
                "www.youtube.com", "https://www.youtube.com/embed/x",
                listOf("https://news.example", "https://other.example"), listOf("news.example"),
            ),
        )
        assertTrue(runsIn("www.youtube.com", "https://www.youtube.com/", emptyList(), listOf("notyoutube.com")))
    }

    /**
     * The lists the app ships: uBlock's YouTube rules come through, in
     * the calls the ad-pruning needs, and none are left to an untrusted
     * list. Prints the counts and how long the build takes here.
     */
    @Test
    fun `bundled uBlock filters carry the YouTube scriptlets`() {
        val dir = File("src/main/assets/adblock")
        val lists = AdblockCategory.entries.flatMap { c ->
            listOf(FilterListText(File(dir, c.file).readText())) +
                c.bundledExtras.map { FilterListText(File(dir, it.file).readText(), it.trustedScriptlets) }
        }
        val t0 = System.nanoTime()
        val e = AdblockEngine.build(lists, catalog)
        println("bundled lists with uBlock: ${e.filterCount} filters in ${(System.nanoTime() - t0) / 1_000_000} ms")
        e.listCounts.forEachIndexed { i, c -> println("  list $i: $c, skipped ${c.skipped}") }
        val ublock = e.listCounts[1]
        assertTrue(ublock.scriptlets > 5_000)
        assertTrue(ublock.scriptletsUsed > ublock.scriptlets / 2)
        val yt = e.scriptletsFor("www.youtube.com").map { it.name }.toSet()
        println("www.youtube.com: $yt")
        assertTrue("json-prune" in yt || "json-prune-fetch-response" in yt)
        assertTrue("trusted-replace-fetch-response" in yt)
        assertTrue("set-constant" in yt)
        val m = e.scriptletsFor("m.youtube.com").map { it.name }.toSet()
        assertTrue("json-prune" in m)
        val code = assertNotNull(catalog.code(e.scriptletsFor("www.youtube.com")))
        println("www.youtube.com script: ${code.length} chars")
        assertTrue(code.length < TabScriptlets.MAX_CHARS / 2)
        // A page with no rules costs nothing.
        assertEquals(emptyList<ScriptletCall>(), e.scriptletsFor("upload.wikimedia.org"))
    }

    private fun <T> assertNotNull(v: T?): T {
        org.junit.Assert.assertNotNull(v)
        return v!!
    }
}
