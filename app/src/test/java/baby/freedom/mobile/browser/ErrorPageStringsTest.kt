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
    fun `the page has exactly one spot for the table`() {
        assertEquals(1, page.split(ErrorPage.STRINGS_PLACEHOLDER).size - 1)
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
        val html = ErrorPage.html(page, json)
        assertFalse(html.contains(ErrorPage.STRINGS_PLACEHOLDER))
        assertTrue(html.contains("const S = $json;"))
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
    fun `only the page's own URL is answered`() {
        assertTrue(ErrorPage.isPageRequest(ErrorPage.URL))
        assertTrue(ErrorPage.isPageRequest(ErrorPage.url("ens_not_found", "x.eth")))
        assertTrue(ErrorPage.isPageRequest("${ErrorPage.URL}#top"))
        assertFalse(ErrorPage.isPageRequest("${ErrorPage.URL}.js"))
        assertFalse(ErrorPage.isPageRequest("file:///android_asset/error/other.html"))
        assertFalse(ErrorPage.isPageRequest("https://example.com/error/error.html"))
    }
}
