package baby.freedom.mobile.browser

import baby.freedom.mobile.ui.Appearance
import baby.freedom.swarm.IpfsInfo
import baby.freedom.swarm.IpfsStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsSearchTest {
    private val rows = listOf(
        settingsRow("history", "Clear history", "3 visits"),
        settingsRow("bookmarks", "Clear bookmarks", "Nothing to clear"),
        settingsRow("site-data", "Clear cookies & site data", "Cookies, DOM storage, cache, and form data"),
    )

    private fun visible(query: String, title: String = "Browsing data") =
        visibleSettingsRows(query, title, rows)

    @Test
    fun `blank query shows every row`() {
        assertEquals(setOf("history", "bookmarks", "site-data"), visible(""))
        assertEquals(setOf("history", "bookmarks", "site-data"), visible("   "))
    }

    @Test
    fun `matches label case-insensitively`() {
        assertEquals(setOf("bookmarks"), visible("BOOKMARK"))
    }

    @Test
    fun `matches description`() {
        assertEquals(setOf("site-data"), visible("dom storage"))
        assertEquals(setOf("history"), visible("visits"))
    }

    @Test
    fun `substring shared by several rows keeps all of them`() {
        assertEquals(setOf("history", "bookmarks", "site-data"), visible("clear"))
    }

    @Test
    fun `query is trimmed`() {
        assertEquals(setOf("bookmarks"), visible("  bookmarks "))
    }

    @Test
    fun `section title match shows the whole section`() {
        assertEquals(setOf("history", "bookmarks", "site-data"), visible("browsing"))
    }

    @Test
    fun `no match hides the section`() {
        assertEquals(emptySet<Any>(), visible("ipfs"))
    }

    @Test
    fun `no fuzzy matching`() {
        assertEquals(emptySet<Any>(), visible("hstory"))
    }

    @Test
    fun `null and blank texts are dropped from the index`() {
        val row = settingsRow("engine", "Search engine", null, "")
        assertEquals(listOf("Search engine"), row.texts)
    }

    @Test
    fun `keeps row order`() {
        assertEquals(listOf("history", "bookmarks", "site-data"), visible("c").toList())
    }

    @Test
    fun `ipfs blocks fetched value is searchable`() {
        val info = IpfsInfo(
            status = IpfsStatus.Running,
            connectedPeers = 4217L,
            gatewayUrl = "http://127.0.0.1:58312",
        )
        assertEquals(setOf("status"), visibleSettingsRows("4217", "IPFS", ipfsRows(info)))
    }

    @Test
    fun `desktop site choices are found under clear site data`() {
        // #180: "Clear cookies & site data" also forgets desktop-site choices.
        for (q in listOf("desktop site", "Desktop")) {
            assertEquals(q, setOf("site-data"), visibleSettingsRows(q, "Browsing data", browsingDataRows(3, 2)))
        }
    }

    @Test
    fun `theme row is found by every choice and by dark mode`() {
        // #269: whichever choice is in use, each one it can switch to finds the row.
        for (current in Appearance.entries) {
            for (q in listOf("theme", "Light", "dark", "system default", "dark mode", "night")) {
                assertEquals("$current/$q", setOf("theme"), visibleSettingsRows(q, "Appearance", appearanceSectionRows(current)))
            }
        }
        assertEquals(setOf("theme"), visibleSettingsRows("appearance", "Appearance", appearanceSectionRows(Appearance.System)))
        assertEquals(emptySet<Any>(), visibleSettingsRows("bookmarks", "Appearance", appearanceSectionRows(Appearance.Dark)))
    }
}
