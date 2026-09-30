package baby.freedom.mobile.browser

import java.io.File
import java.util.Locale
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The `rad://` viewer's text table, `/_/strings.js` (#280). */
class RadViewerStringsTest {
    private val script: String by lazy {
        val file = listOf(File("src/main/assets/rad/viewer.js"), File("app/src/main/assets/rad/viewer.js"))
            .first { it.isFile }
        file.readText()
    }

    /** Every `<table>.<key>` viewer.js reads (not `foo.S.x`, not `RAD_STRINGS.x`). */
    private fun keysUsed(table: String): Set<String> =
        Regex("(?<![\\w$.])$table\\.([A-Za-z_][A-Za-z0-9_]*)").findAll(script).map { it.groupValues[1] }.toSet()

    @Test
    fun `every string and plural viewer js reads is in the table, and nothing more`() {
        val strings = keysUsed("S")
        val plurals = keysUsed("P")
        assertTrue(strings.isNotEmpty() && plurals.isNotEmpty())
        assertEquals(emptySet<String>(), strings - RadApi.VIEWER_STRINGS.keys)
        assertEquals(emptySet<String>(), plurals - RadApi.VIEWER_PLURALS.keys)
        assertEquals(emptySet<String>(), RadApi.VIEWER_STRINGS.keys - strings)
        assertEquals(emptySet<String>(), RadApi.VIEWER_PLURALS.keys - plurals)
    }

    @Test
    fun `the viewer gets its text as a script, every key resolved`() {
        val reply = RadApi.serve(
            "GET",
            "https://rad.freedom.baby/_/strings.js",
            { _, _ -> error("the API isn't asked") },
            fromViewer = true,
        )
        assertEquals(200, reply.status)
        assertTrue(reply.mime.startsWith("text/javascript"))
        assertEquals(RadApi.PAGE_HEADERS + ("Cross-Origin-Resource-Policy" to "same-origin"), reply.headers)
        val text = String(reply.body)
        val prefix = "window.RAD_STRINGS = "
        assertTrue(text.startsWith(prefix))
        val table = JSONObject(text.removePrefix(prefix).trim().removeSuffix(";"))
        val strings = table.getJSONObject("strings")
        for (key in RadApi.VIEWER_STRINGS.keys) assertTrue(key, strings.getString(key).isNotEmpty())
        assertEquals("Loading…", strings.getString("loading"))
        assertEquals("%1\$s isn’t in your node’s storage.", strings.getString("repo_not_found_body"))
        val plurals = table.getJSONObject("plurals")
        for (key in RadApi.VIEWER_PLURALS.keys) {
            assertTrue(key, plurals.getJSONObject(key).getString("other").isNotEmpty())
        }
        assertEquals("%1\$d comment", plurals.getJSONObject("comments").getString("one"))
        assertEquals("%1\$d comments", plurals.getJSONObject("comments").getString("other"))
    }

    @Test
    fun `another site can't include the table, which names the app language`() {
        val reply = RadApi.serve(
            "GET",
            "https://rad.freedom.baby/_/strings.js",
            { _, _ -> error("the API isn't asked") },
            fromViewer = false,
        )
        assertEquals(403, reply.status)
        assertFalse(String(reply.body).contains("RAD_STRINGS"))
    }

    @Test
    fun `plural forms are keyed by category`() {
        val table = RadApi.viewerStrings(Locale.GERMAN, mapOf("one" to 1, "other" to 0))
        assertEquals("de", table.getString("lang"))
        assertEquals("%1\$d minute ago", table.getJSONObject("plurals").getJSONObject("minutes_ago").getString("one"))
        assertEquals("%1\$d minutes ago", table.getJSONObject("plurals").getJSONObject("minutes_ago").getString("other"))
        // No ICU off-device: English's categories.
        assertEquals(mapOf("one" to 1, "other" to 0), RadApi.pluralSamples(Locale.ENGLISH))
    }
}
