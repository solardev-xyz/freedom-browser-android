package baby.freedom.mobile.browser

import baby.freedom.mobile.data.HistoryPage
import baby.freedom.mobile.data.LocalMatches
import baby.freedom.mobile.data.UrlSuggestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Rows from an earlier text don't show under the current one while the lookup waits (#473 R1-F1). */
class StillMatchingTest {
    private val forG = LocalMatches(
        bookmarks = listOf(
            UrlSuggestion("https://google.com/", "Google", UrlSuggestion.Source.BOOKMARK),
            UrlSuggestion("https://example.org/", "My GitHub notes", UrlSuggestion.Source.BOOKMARK),
        ),
        pages = listOf(
            HistoryPage("https://google.com/", "Google", visits = 50, lastVisit = 2),
            HistoryPage("https://github.com/", "GitHub", visits = 1, lastVisit = 1),
        ),
        query = "g",
    )

    @Test
    fun `rows for an earlier text that no longer match are dropped`() {
        val shown = stillMatching(forG, "github")
        assertEquals(listOf("https://example.org/"), shown.bookmarks.map { it.url })
        assertEquals(listOf("https://github.com/"), shown.pages.map { it.url })
    }

    @Test
    fun `a dropped row never reaches the ranking`() {
        val shown = stillMatching(forG, "github")
        val ranked = rankSuggestions(
            query = "github",
            tabs = emptyList(),
            currentTabId = null,
            private = false,
            bookmarks = shown.bookmarks,
            history = shown.pages.map { HistoryCandidate(it.url, it.title, it.visits, it.lastVisit) },
        )
        assertEquals(setOf("https://github.com/", "https://example.org/"), ranked.map { it.url }.toSet())
    }

    @Test
    fun `rows for the current text pass through untouched`() {
        assertSame(forG, stillMatching(forG, " g "))
    }

    @Test
    fun `case is ignored, like the lookup`() {
        assertEquals(listOf("https://github.com/"), stillMatching(forG, "GITHUB.C").pages.map { it.url })
    }
}
