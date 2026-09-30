package baby.freedom.mobile.l10n

import java.io.File
import java.nio.file.Files
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element

/**
 * What lint's MissingTranslation can't see (#313): a translation of the
 * app's string files must also translate `:swarmnode`'s (its lines merge
 * into the app, but lint judges the library by its own `values-<lang>`
 * folders, so a `values-de` with only the app's files would pass and ship
 * the node pages' text in English, R1-M2), and must name its own language
 * in `l10n_language` (plural forms follow it, R1-F1).
 */
class TranslationCoverageTest {
    @Test
    fun `every translation covers swarmnode and names its language`() {
        assertEquals(emptyList<String>(), problems(res("app"), res("swarmnode")))
    }

    @Test
    fun `a translation of the app alone is caught`() {
        val root = Files.createTempDirectory("l10n").toFile()
        try {
            val app = File(root, "app").apply { mkdirs() }
            val swarm = File(root, "swarmnode").apply { mkdirs() }
            write(app, "values", """<string name="l10n_language">en-US</string><string name="a">A</string>""")
            write(swarm, "values", """<string name="swarmnode_x">X</string><string name="swarmnode_id" translatable="false">id</string><plurals name="swarmnode_p"><item quantity="other">p</item></plurals>""")
            write(app, "values-pt-rBR", """<string name="l10n_language">pt-BR</string><string name="a">Á</string>""")
            write(app, "values-de", """<string name="l10n_language">en-US</string><string name="a">Ä</string>""")
            write(swarm, "values-de", """<string name="swarmnode_x">Ẍ</string>""")
            assertEquals(
                listOf(
                    "values-de: l10n_language is \"en-US\", not de",
                    "values-de: :swarmnode doesn't translate swarmnode_p",
                    "values-pt-rBR: :swarmnode doesn't translate swarmnode_p",
                    "values-pt-rBR: :swarmnode doesn't translate swarmnode_x",
                ),
                problems(app, swarm),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    private fun write(res: File, folder: String, body: String) {
        File(res, folder).mkdirs()
        File(res, "$folder/strings.xml").writeText("<resources>$body</resources>")
    }

    /** [module]'s `src/main/res`, from the app module's folder (Gradle) or the project's. */
    private fun res(module: String): File {
        val candidates = if (module == "app") listOf("src/main/res", "app/src/main/res")
        else listOf("../$module/src/main/res", "$module/src/main/res")
        return candidates.map(::File).first { File(it, "values").isDirectory }
    }

    /** Each translation folder of [app] (a `values-<lang>` with string files) checked against [swarm]. */
    private fun problems(app: File, swarm: File): List<String> {
        val swarmNames = names(File(swarm, "values"))
        val out = mutableListOf<String>()
        val folders = app.listFiles { f -> f.isDirectory && f.name.startsWith("values-") && stringFiles(f).isNotEmpty() }
            .orEmpty().sortedBy { it.name }
        for (folder in folders) {
            val tag = languageTag(folder.name.removePrefix("values-"))
            val declared = values(folder)["l10n_language"]
            if (declared == null || !declared.equals(tag, ignoreCase = true)) {
                out += "${folder.name}: l10n_language is \"$declared\", not $tag"
            }
            val translated = names(File(swarm, folder.name))
            for (name in (swarmNames - translated).sorted()) out += "${folder.name}: :swarmnode doesn't translate $name"
        }
        return out
    }

    /** `de` → de, `pt-rBR` → pt-BR, `b+sr+Latn` → sr-Latn; other qualifiers after the language are dropped. */
    private fun languageTag(qualifiers: String): String =
        if (qualifiers.startsWith("b+")) {
            qualifiers.removePrefix("b+").substringBefore('-').replace('+', '-')
        } else {
            val parts = qualifiers.split('-')
            val region = parts.getOrNull(1)?.takeIf { it.length == 3 && it.startsWith("r") }?.drop(1)
            if (region != null) "${parts[0]}-$region" else parts[0]
        }

    private fun stringFiles(folder: File): List<File> =
        folder.listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }.orEmpty().toList()

    /** Translatable `<string>` and `<plurals>` names in [folder]. */
    private fun names(folder: File): Set<String> = elements(folder)
        .filter { it.getAttribute("translatable") != "false" }
        .mapTo(HashSet()) { it.getAttribute("name") }

    private fun values(folder: File): Map<String, String> = elements(folder)
        .filter { it.tagName == "string" }
        .associate { it.getAttribute("name") to it.textContent.trim() }

    private fun elements(folder: File): List<Element> = stringFiles(folder).flatMap { file ->
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
        (0 until root.childNodes.length).mapNotNull { root.childNodes.item(it) as? Element }
            .filter { it.tagName == "string" || it.tagName == "plurals" }
    }
}
