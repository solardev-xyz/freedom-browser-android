package baby.freedom.mobile

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * What another app on the device can reach: pinned so a later change
 * that widens it has to change this test too.
 *
 * - Only the two activities are exported: [MainActivity] for the launcher,
 *   and [IncomingLinkActivity], the one gate every link, share and search
 *   from another app comes through (#268). Every service, receiver and
 *   provider is private to the app.
 * - No backup of the app's data, and a release build is never debuggable.
 * - Every PendingIntent handed to the system is immutable, so the app
 *   holding it (the notification shade, AlarmManager) can't fill in its
 *   own action, data or component.
 * - WebView debugging (chrome://inspect over adb) is on only in a
 *   debuggable build.
 */
class PlatformSurfaceTest {
    private val manifest = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(File("src/main/AndroidManifest.xml"))
        .documentElement

    private val sources = File("src/main/java")

    private fun Element.android(name: String): String = getAttributeNS(ANDROID, name)

    private fun components(tag: String): List<Element> {
        val nodes = manifest.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    @Test
    fun `only the launcher activity and the incoming-link gate are exported`() {
        val exported = (components("activity") + components("activity-alias") + components("service") +
            components("receiver") + components("provider"))
            .filter { it.android("exported") != "false" }
            .map { it.android("name") }
        assertEquals(listOf(".MainActivity", ".IncomingLinkActivity"), exported)
        // Every component states it, rather than leaving it to the intent-filter default.
        for (c in components("service") + components("receiver") + components("provider")) {
            assertEquals(c.android("name"), "false", c.android("exported"))
        }
    }

    @Test
    fun `MainActivity takes nothing from other apps but the launcher`() {
        val main = components("activity").single { it.android("name") == ".MainActivity" }
        val filters = main.getElementsByTagName("intent-filter")
        assertEquals(1, filters.length)
        val actions = (filters.item(0) as Element).getElementsByTagName("action")
        assertEquals(1, actions.length)
        assertEquals("android.intent.action.MAIN", (actions.item(0) as Element).android("name"))
    }

    @Test
    fun `the incoming-link gate runs in no task of its own and leaves no trace in Recents`() {
        val gate = components("activity").single { it.android("name") == ".IncomingLinkActivity" }
        assertEquals("true", gate.android("noHistory"))
        assertEquals("true", gate.android("excludeFromRecents"))
        assertEquals("", gate.android("taskAffinity"))
        assertTrue(gate.hasAttributeNS(ANDROID, "taskAffinity"))
    }

    @Test
    fun `no backup and never debuggable`() {
        val app = components("application").single()
        assertEquals("false", app.android("allowBackup"))
        assertFalse("android:debuggable is set by the build type, never in the manifest", app.hasAttributeNS(ANDROID, "debuggable"))
        val gradle = File("build.gradle.kts").readText()
        assertFalse(Regex("""isDebuggable\s*=\s*true""").containsMatchIn(gradle))
    }

    @Test
    fun `the file provider is private and serves only its two cache directories`() {
        val provider = components("provider").single()
        assertEquals("false", provider.android("exported"))
        val paths = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/xml/file_paths.xml")).documentElement
        val entries = (0 until paths.childNodes.length).map { paths.childNodes.item(it) }
            .filterIsInstance<Element>()
            .map { it.tagName to it.getAttribute("path") }
        assertEquals(listOf("cache-path" to "uploads/", "cache-path" to "shared/"), entries)
    }

    @Test
    fun `every PendingIntent is immutable`() {
        val create = Regex("""PendingIntent\.get(Activity|Activities|Broadcast|Service|ForegroundService)\(""")
        var found = 0
        for (file in sources.walkTopDown().filter { it.extension == "kt" }) {
            val text = file.readText()
            for (m in create.findAll(text)) {
                found++
                val call = text.substring(m.range.first, closingParen(text, m.range.last))
                assertTrue("${file.name}: $call", "FLAG_IMMUTABLE" in call)
                assertFalse("${file.name}: $call", "FLAG_MUTABLE" in call)
            }
        }
        assertTrue("no PendingIntent found: the scan is broken", found > 0)
    }

    @Test
    fun `WebView debugging is turned on only in a debuggable build`() {
        val call = "WebView.setWebContentsDebuggingEnabled("
        val uses = sources.walkTopDown().filter { it.extension == "kt" }
            .flatMap { f -> f.readLines().mapIndexedNotNull { i, line -> if (call in line) Triple(f, i, line) else null } }
            .toList()
        assertEquals(1, uses.size)
        val (file, index, _) = uses.single()
        val before = file.readLines().subList(maxOf(0, index - 4), index).joinToString("\n")
        assertTrue(before, "FLAG_DEBUGGABLE" in before)
    }

    /** The index just past the `)` closing the call whose `(` is at [open]. */
    private fun closingParen(text: String, open: Int): Int {
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return i + 1
            }
        }
        return text.length
    }

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"
    }
}
