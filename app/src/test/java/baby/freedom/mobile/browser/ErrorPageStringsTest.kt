package baby.freedom.mobile.browser

import baby.freedom.mobile.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** `error.html`'s text comes from [ErrorPage.stringsJson] (#280). */
class ErrorPageStringsTest {
    private val page: String =
        listOf(File("src/main/assets/error/error.html"), File("app/src/main/assets/error/error.html"))
            .first { it.isFile }
            .readText()

    /** Every key the page's script looks up: `fmt('key'` and `fmtNodes('key'`. */
    private val keysUsed: Set<String> =
        Regex("""\bfmt(?:Nodes)?\(\s*'([^']*)'""").findAll(page).map { it.groupValues[1] }.toSet()

    @Test
    fun `every string the page uses is in the table, and nothing else`() {
        assertTrue(keysUsed.isNotEmpty())
        assertEquals(keysUsed.sorted(), ErrorPage.PAGE_STRINGS.keys.sorted())
    }

    @Test
    fun `the page reads its text only through fmt`() {
        // A bare `S.key` / `S['key']` would bypass the check above;
        // only fmt / fmtNodes read `S[key]`.
        assertEquals(2, Regex("""\bS\s*[.\[]""").findAll(page).count())
        assertEquals(2, Regex("""\bS\[key]""").findAll(page).count())
    }

    @Test
    fun `the page takes its table from the app's script`() {
        // ErrorPage.stringsScript calls this, or parks the table here.
        assertTrue(page.contains("window.__errorPageStrings = (table) => start(table, false);"))
        assertTrue(page.contains("if (window.__errorPageTable) start(window.__errorPageTable, false);"))
    }

    @Test
    fun `the page falls back to the English table if the app's never comes`() {
        // Never blank, never without Retry (#313 R1-M2): after a short wait
        // it shows in English, and the app's table still replaces that.
        val fallback = Regex("""/\* EN-FALLBACK \*/(.*?)/\* /EN-FALLBACK \*/""", RegexOption.DOT_MATCHES_ALL)
            .find(page)?.groupValues?.get(1)
        assertEquals("error.html's FALLBACK must equal the English strings", ErrorPage.stringsJson(), fallback)
        assertTrue(page.contains("setTimeout(() => start(FALLBACK, true), FALLBACK_AFTER_MS);"))
        assertTrue(page.contains("if (started === true || !table || typeof table !== 'object') return;"))
        assertTrue(page.contains("if (started && fallback) return;"))
    }

    @Test
    fun `the table carries the English text`() {
        val json = ErrorPage.stringsJson()
        assertTrue(json.startsWith("{\"page_default_title\":\"Content Unavailable\","))
        assertTrue(json.contains("\"page_try_again\":\"Try Again\""))
        assertTrue(json.contains("\"cert_title\":\"Connection isn't secure\""))
        assertTrue(json.contains("\"ens_not_found_title\":\"No content for this %1\$s name\""))
        // HTML fragments stay HTML, escaped for the script element.
        assertTrue(json.contains("\\u003ccode\\u003econtenthash\\u003c/code\\u003e"))
        assertTrue(json.contains("&mdash;".replace("&", "\\u0026")))
        assertEquals(
            "(window.__errorPageStrings||function(t){window.__errorPageTable=t;})($json);",
            ErrorPage.stringsScript(json),
        )
    }

    @Test
    fun `nothing in a string can end the script or break the literal`() {
        val text = mapOf(R.string.errorpage_page_default_title to "</script><b>\"a\\b\"\n\u2028&")
        val json = ErrorPage.stringsJson { text[it] ?: "" }
        assertFalse(json.contains("</"))
        assertFalse(json.contains("<"))
        assertFalse(json.contains("\u2028"))
        assertTrue(
            json.startsWith(
                "{\"page_default_title\":\"\\u003c/script\\u003e\\u003cb\\u003e\\\"a\\\\b\\\"\\n\\u2028\\u0026\",",
            ),
        )
    }

    @Test
    fun `the page prints the address with its bidi controls marked`() {
        // A refused ENS name reaches the page as given; one RLO in it
        // printed `ens://\u202Emoc.lapyap.eth` as `hte.paypal.com`.
        assertTrue(page.contains("""replace(/\p{Bidi_Control}/gu, '\uFFFD')"""))
        assertTrue(page.contains("parts.push(shown(protocolUrl))"))
        assertTrue(page.contains("else if (url) parts.push(shown(url))"))
        assertFalse(Regex("""parts\.push\((?:url|protocolUrl)\)""").containsMatchIn(page))
    }
}
