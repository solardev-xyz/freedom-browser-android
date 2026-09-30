package baby.freedom.mobile.browser

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Every third-party component the APK ships, with its licence text
 * (#325): Settings → About → Open-source licences.
 *
 * Read from the asset the build generates ([ASSET], `generateLicences<Variant>`
 * in app/build.gradle.kts, from AboutLibraries' scan of the Gradle
 * dependencies and app/licences/), so it's there offline and matches the
 * build it's in. A component names its texts by index into [texts]: most
 * of the ~900 components share a few dozen texts.
 */
internal class OpenSourceLicences(
    val components: List<Component>,
    val texts: List<LicenceText>,
) {
    /** Where a component lives in the APK; the page groups by it, in this order. */
    enum class Section(val key: String) {
        /** A Gradle dependency: Kotlin/Java code in the app's dex, or an AAR's native helper. */
        Android("android"),

        /** A Rust crate in libfreedom_mobile_ffi.so, or what Colibri links into libc4.so. */
        Native("native"),

        /** A script or data file under assets/: the OpenLV bundle, the filter lists. */
        Data("data"),
    }

    class Component(
        val section: Section,
        val name: String,
        /** Maven coordinates for a Gradle dependency, the crate name for a crate. */
        val id: String,
        /** Empty when the component has no version of its own (the filter lists). */
        val version: String,
        /** As published: an SPDX expression, or the licence names a POM gives. */
        val licence: String,
        val url: String,
        /** Attribution or a note on how it's shipped, shown above the texts. */
        val notice: String?,
        val texts: List<Int>,
    )

    class LicenceText(val title: String, val text: String)

    fun textsOf(component: Component): List<LicenceText> = component.texts.map { texts[it] }

    companion object {
        const val ASSET = "licences/licences.json"

        @Volatile private var loaded: OpenSourceLicences? = null

        /** The asset, parsed once per process (about 1 MB of JSON). */
        suspend fun load(context: Context): OpenSourceLicences = loaded ?: withContext(Dispatchers.IO) {
            val json = context.applicationContext.assets.open(ASSET).bufferedReader().use { it.readText() }
            parse(json).also { loaded = it }
        }

        fun parse(json: String): OpenSourceLicences {
            val root = JSONObject(json)
            val texts = root.getJSONArray("texts").objects().map {
                LicenceText(it.getString("title"), it.getString("text"))
            }
            val sections = Section.entries.associateBy { it.key }
            val components = root.getJSONArray("components").objects().map { c ->
                val ids = c.getJSONArray("texts").let { a -> List(a.length()) { a.getInt(it) } }
                require(ids.isNotEmpty() && ids.all { it in texts.indices }) { "bad texts for ${c.optString("name")}" }
                Component(
                    section = sections[c.getString("section")] ?: error("unknown section ${c.getString("section")}"),
                    name = c.getString("name"),
                    id = c.getString("id"),
                    version = c.optString("version"),
                    licence = c.getString("licence"),
                    url = c.optString("url"),
                    notice = c.optString("notice").takeIf { it.isNotBlank() },
                    texts = ids,
                )
            }
            // Grouped by section, then by name, as the page lists them.
            val sorted = components.sortedWith(
                compareBy<Component> { it.section.ordinal }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                    .thenBy { it.version },
            )
            return OpenSourceLicences(sorted, texts)
        }

        /**
         * The components matching [query] (by name, id, version or
         * licence, ignoring case), in list order; all of them for a blank
         * query.
         */
        fun search(components: List<Component>, query: String): List<Component> {
            val q = query.trim()
            if (q.isEmpty()) return components
            return components.filter { c ->
                listOf(c.name, c.id, c.version, c.licence).any { it.contains(q, ignoreCase = true) }
            }
        }

        /**
         * [text] as paragraphs for a narrow screen: split at blank lines,
         * and each paragraph's hard-wrapped lines joined into one, so a
         * licence set at 72 columns doesn't break every line in two at a
         * large font scale. A line that starts a list item ("(a)", "1.",
         * "- ") stays on a line of its own.
         */
        fun paragraphs(text: String): List<String> =
            text.split(PARAGRAPH_BREAK).mapNotNull { paragraph ->
                val out = StringBuilder()
                for (raw in paragraph.lines()) {
                    val line = raw.trim()
                    if (line.isEmpty()) continue
                    if (out.isNotEmpty()) out.append(if (LIST_ITEM.containsMatchIn(line)) '\n' else ' ')
                    out.append(line)
                }
                out.toString().takeIf { it.isNotEmpty() }
            }

        private val PARAGRAPH_BREAK = Regex("\\n[ \\t]*\\n")
        private val LIST_ITEM = Regex("^([-*•]|\\(?([0-9]{1,3}|[A-Za-z]|[ivxIVX]{1,4})[.)])\\s")

        private fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }
    }
}
