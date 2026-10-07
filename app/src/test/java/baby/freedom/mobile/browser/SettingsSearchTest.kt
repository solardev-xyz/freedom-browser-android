package baby.freedom.mobile.browser

import baby.freedom.mobile.ui.Appearance
import baby.freedom.swarm.IpfsInfo
import baby.freedom.swarm.IpfsStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchTest {
    private val rows = listOf(
        // Made-up rows for the matcher, not the real Browsing data card.
        settingsRow("history", "Delete history", "3 visits"),
        settingsRow("bookmarks", "Delete bookmarks", "Nothing to delete"),
        settingsRow("site-data", "Delete cookies & site data", "Cookies, DOM storage, cache, and form data"),
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
        assertEquals(setOf("history", "bookmarks", "site-data"), visible("delete"))
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
        assertEquals(listOf("history", "bookmarks", "site-data"), visible("de").toList())
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
    fun `delete browsing data row is found by what it deletes`() {
        // #400: the one row replacing the old history, bookmarks and cookies rows.
        for (q in listOf(
            "clear", "Delete", "history", "cookies", "cache", "cached", "site data",
            "desktop site", "zoom", "form data", "DOM storage", "browsing data",
        )) {
            assertTrue(q, "delete-data" in visibleSettingsRows(q, "Browsing data", browsingDataRows()))
        }
        for (q in listOf("history", "cookies", "cache", "site data", "zoom", "form data")) {
            assertEquals(q, setOf("delete-data"), visibleSettingsRows(q, "Browsing data", browsingDataRows()))
        }
    }

    @Test
    fun `a search for bookmarks finds only the pointer to the Bookmarks page`() {
        // R3-M3: Delete all bookmarks moved to the Bookmarks page's ⋮; Settings
        // doesn't wipe them any more, but a search for them still says where.
        for (q in listOf("bookmarks", "Bookmark", "favorites", "saved pages")) {
            assertEquals(q, setOf(BOOKMARKS_MOVED), visibleSettingsRows(q, "Browsing data", browsingDataRows()))
        }
        // "delete" finds both: the row, and the bookmarks one's new home.
        assertEquals(
            setOf("delete-data", BOOKMARKS_MOVED),
            visibleSettingsRows("delete", "Browsing data", browsingDataRows()),
        )
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

    @Test
    fun `language row is searchable while shown`() {
        // #280: shown only with a language to name; found by its title, the language and "locale".
        val rows = appearanceSectionRows(Appearance.System, language = "Deutsch")
        for (q in listOf("language", "deutsch", "locale")) {
            assertEquals(q, setOf("language"), visibleSettingsRows(q, "Appearance", rows))
        }
        assertEquals(setOf("theme", "language"), visibleSettingsRows("appearance", "Appearance", rows))
        assertEquals(emptySet<Any>(), visibleSettingsRows("language", "Appearance", appearanceSectionRows(Appearance.System)))
    }

    @Test
    fun `search keywords split the resource on commas`() {
        assertEquals(
            listOf("dark mode", "light mode", "night mode"),
            searchKeywords(baby.freedom.mobile.R.string.settings_theme_keywords).toList(),
        )
    }

    @Test
    fun `ask where to save is found by its words and by save as`() {
        // #322: its title, what it does either way, the private-tab note, and "save as".
        for (on in listOf(false, true)) {
            val rows = downloadSettingsRows(on)
            for (q in listOf("ask where", "save as", "folder", "private tabs", "Download/Freedom")) {
                assertEquals("$on/$q", setOf("ask-where"), visibleSettingsRows(q, "Downloads", rows))
            }
            assertEquals(setOf("ask-where"), visibleSettingsRows("downloads", "Downloads", rows))
            assertEquals(emptySet<Any>(), visibleSettingsRows("bookmarks", "Downloads", rows))
        }
    }
}
