package baby.freedom.mobile.ens

import java.util.zip.GZIPInputStream
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The official ENSIP-15 validation vectors, every one of them.
 *
 * `resources/ensip15/tests.json.gz` is `validate/tests.json` from
 * [adraffy/ens-normalize.js](https://github.com/adraffy/ens-normalize.js)
 * at commit `4fb708ae` (ens-normalize 1.11.1, Unicode 17.0.0 — the
 * version desktop pins), gzipped unmodified; the uncompressed file's
 * sha256 is `7b20fb69…43d089d`. Each entry is `{name, norm?, error?}`:
 * `error` means ENSIP-15 must reject the name, otherwise it must
 * normalize to `norm` (or to itself when `norm` is absent).
 */
class EnsNormalizeVectorsTest {

    private fun vectors(): JSONArray {
        val stream = javaClass.getResourceAsStream("/ensip15/tests.json.gz")
            ?: error("missing ensip15/tests.json.gz")
        val text = GZIPInputStream(stream).bufferedReader(Charsets.UTF_8).use { it.readText() }
        return JSONArray(text)
    }

    @Test
    fun `every ENSIP-15 vector normalizes or fails as the spec says`() {
        val all = vectors()
        val header = all.getJSONObject(0)
        assertEquals("version", header.getString("name"))
        assertEquals("1.11.1", header.getString("version"))

        val failures = mutableListOf<String>()
        var checked = 0
        for (i in 1 until all.length()) {
            val v = all.getJSONObject(i)
            val name = v.getString("name")
            val shouldFail = v.optBoolean("error", false)
            val expected = if (v.has("norm")) v.getString("norm") else name
            val actual = EnsNormalize.normalizeOrNull(name)
            val ok = if (shouldFail) actual == null else actual == expected
            if (!ok) {
                failures += "#$i ${escape(name)}: expected " +
                    (if (shouldFail) "error" else escape(expected)) +
                    ", got " + (actual?.let(::escape) ?: "error")
            }
            checked++
        }
        // Guards against a silently truncated or wrong resource.
        assertTrue("only $checked vectors", checked > 30_000)
        assertTrue(
            "${failures.size}/$checked vectors failed:\n" + failures.take(20).joinToString("\n"),
            failures.isEmpty(),
        )
    }

    private fun escape(s: String): String = buildString {
        append('"')
        s.codePoints().forEach { cp ->
            if (cp in 0x20..0x7e) appendCodePoint(cp) else append("{%X}".format(cp))
        }
        append('"')
    }
}
