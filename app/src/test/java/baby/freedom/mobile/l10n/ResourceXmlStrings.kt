package baby.freedom.mobile.l10n

import baby.freedom.mobile.R
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * [Strings] for JVM unit tests (#280): there is no `Context` off-device,
 * so this reads the English `src/main/res/values/strings*.xml` the app
 * ships and resolves ids through the generated `R` class. Registered in
 * `META-INF/services`, so [Strings] finds it on its own and a test
 * asserts the same text a user sees.
 *
 * Handles what the app's string files use: aapt's escapes (`\'`, `\"`,
 * `\n`, `\t`, `\\`, `\@`, `\?`, `\uXXXX`), double-quoted spans, whitespace
 * collapsing, `<xliff:g>` wrappers, `%1$s`-style formatting, and English
 * plural rules (`one` for exactly 1).
 */
class ResourceXmlStrings : StringSource {
    private val strings = HashMap<String, String>()
    private val plurals = HashMap<String, Map<String, String>>()
    private val stringNames: Map<Int, String> = idNames("string")
    private val pluralNames: Map<Int, String> = idNames("plurals")

    init {
        val dir = listOf(File("src/main/res/values"), File("app/src/main/res/values")).first { it.isDirectory }
        dir.listFiles { f -> f.name.endsWith(".xml") }!!.sorted().forEach { load(it) }
    }

    override fun string(id: Int, vararg args: Any?): String {
        val name = stringNames[id] ?: error("no string resource $id")
        val text = strings[name] ?: error("string/$name not in res/values")
        return if (args.isEmpty()) text else String.format(Locale.US, text, *args)
    }

    override fun plural(id: Int, count: Int, vararg args: Any?): String {
        val name = pluralNames[id] ?: error("no plurals resource $id")
        val forms = plurals[name] ?: error("plurals/$name not in res/values")
        val text = (if (count == 1) forms["one"] else null) ?: forms["other"] ?: error("plurals/$name has no other")
        return if (args.isEmpty()) text else String.format(Locale.US, text, *args)
    }

    private fun load(file: File) {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(file)
        val root = doc.documentElement
        for (i in 0 until root.childNodes.length) {
            val e = root.childNodes.item(i) as? Element ?: continue
            val name = e.getAttribute("name")
            when (e.tagName) {
                "string" -> strings[name] = decode(e)
                "plurals" -> {
                    val forms = HashMap<String, String>()
                    for (j in 0 until e.childNodes.length) {
                        val item = e.childNodes.item(j) as? Element ?: continue
                        forms[item.getAttribute("quantity")] = decode(item)
                    }
                    plurals[name] = forms
                }
            }
        }
    }

    private fun decode(e: Element): String = unescape(rawText(e))

    /** The element's text with markup (`<xliff:g>`, `<b>`) dropped but its content kept. */
    private fun rawText(n: Node): String = buildString {
        for (i in 0 until n.childNodes.length) {
            val c = n.childNodes.item(i)
            when (c.nodeType) {
                Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> append(c.nodeValue)
                Node.ELEMENT_NODE -> append(rawText(c))
            }
        }
    }

    private fun unescape(s: String): String {
        val out = StringBuilder()
        var quoted = false
        var i = 0
        var pendingSpace = false
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < s.length -> {
                    if (pendingSpace) { out.append(' '); pendingSpace = false }
                    val n = s[i + 1]
                    when (n) {
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        'u' -> { out.append(s.substring(i + 2, i + 6).toInt(16).toChar()); i += 4 }
                        else -> out.append(n)
                    }
                    i += 2
                    continue
                }
                c == '"' -> quoted = !quoted
                !quoted && c.isWhitespace() -> pendingSpace = out.isNotEmpty()
                else -> {
                    if (pendingSpace) { out.append(' '); pendingSpace = false }
                    out.append(c)
                }
            }
            i++
        }
        return out.toString()
    }

    // By name, so this compiles before the app has any plurals.
    private fun idNames(type: String): Map<Int, String> =
        runCatching { Class.forName("${R::class.java.name}\$$type") }.getOrNull()
            ?.fields?.associate { it.getInt(null) to it.name }
            .orEmpty()
}
