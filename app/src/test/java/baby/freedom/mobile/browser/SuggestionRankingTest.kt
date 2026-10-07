package baby.freedom.mobile.browser

import baby.freedom.mobile.data.UrlSuggestion
import baby.freedom.mobile.data.UrlSuggestion.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The address bar's local suggestions: ranking and de-duplication (#443). */
class SuggestionRankingTest {

    private fun tab(id: Long, url: String, title: String = "", private: Boolean = false) =
        TabCandidate(id, url, title, private)

    private fun bookmark(url: String, title: String = "") = UrlSuggestion(url, title, Source.BOOKMARK)

    private fun visit(url: String, title: String = "", visits: Int = 1, last: Long = 0) =
        HistoryCandidate(url, title, visits, last)

    private fun rank(
        query: String,
        tabs: List<TabCandidate> = emptyList(),
        bookmarks: List<UrlSuggestion> = emptyList(),
        history: List<HistoryCandidate> = emptyList(),
        currentTabId: Long? = 0,
        private: Boolean = false,
        limit: Int = DEFAULT_SUGGESTION_LIMIT,
    ) = rankSuggestions(query, tabs, currentTabId, private, bookmarks, history, limit)

    @Test
    fun `match strength orders address prefix, host label, title word, contains`() {
        assertEquals(MatchStrength.ADDRESS_PREFIX, matchStrength("exa", "https://www.example.com/", ""))
        assertEquals(MatchStrength.ADDRESS_PREFIX, matchStrength("www.exa", "https://example.com/", ""))
        assertEquals(MatchStrength.ADDRESS_PREFIX, matchStrength("https://exa", "https://example.com/", ""))
        assertEquals(MatchStrength.ADDRESS_PREFIX, matchStrength("EXA", "https://Example.com/", ""))
        assertEquals(MatchStrength.HOST_LABEL, matchStrength("wiki", "https://en.wikipedia.org/wiki/Bee", ""))
        assertEquals(MatchStrength.TITLE_WORD, matchStrength("rust", "https://doc.example/book", "The Rust Book"))
        assertEquals(MatchStrength.CONTAINS, matchStrength("ust", "https://doc.example/book", "The Rust Book"))
        assertEquals(MatchStrength.CONTAINS, matchStrength("book", "https://doc.example/abook", ""))
        assertEquals(MatchStrength.NONE, matchStrength("zzz", "https://doc.example/book", "The Rust Book"))
        assertEquals(MatchStrength.NONE, matchStrength("  ", "https://doc.example/book", "x"))
    }

    @Test
    fun `best match first, whatever the source`() {
        val out = rank(
            "exa",
            bookmarks = listOf(bookmark("https://news.example.org/", "Examples daily")),
            history = listOf(visit("https://example.com/", "Example")),
        )
        assertEquals(listOf("https://example.com/", "https://news.example.org/"), out.map { it.url })
    }

    @Test
    fun `equal matches go tab, bookmark, history, then most visited, then most recent`() {
        val out = rank(
            "site",
            tabs = listOf(tab(1, "https://site-t.example/")),
            bookmarks = listOf(bookmark("https://site-b.example/")),
            history = listOf(
                visit("https://site-once.example/", visits = 1, last = 900),
                visit("https://site-often.example/", visits = 7, last = 100),
                visit("https://site-recent.example/", visits = 1, last = 1000),
            ),
        )
        assertEquals(
            listOf(
                "https://site-t.example/", "https://site-b.example/", "https://site-often.example/",
                "https://site-recent.example/", "https://site-once.example/",
            ),
            out.map { it.url },
        )
        assertEquals(listOf(Source.TAB, Source.BOOKMARK, Source.HISTORY), out.map { it.source }.distinct())
    }

    @Test
    fun `one page is one row - open tab beats bookmark beats history`() {
        val out = rank(
            "example",
            tabs = listOf(tab(5, "https://example.com", "")),
            bookmarks = listOf(bookmark("http://www.example.com/", "Example (bookmark)")),
            history = listOf(visit("https://example.com/#", "Example", visits = 3)),
        )
        assertEquals(1, out.size)
        assertEquals(Source.TAB, out[0].source)
        assertEquals(5L, out[0].tabId)
        // The tab had no title yet: the row borrows the bookmark's.
        assertEquals("Example (bookmark)", out[0].title)
    }

    @Test
    fun `a bookmarked page in history shows once, as the bookmark`() {
        val out = rank(
            "example",
            bookmarks = listOf(bookmark("https://example.com/a", "A")),
            history = listOf(visit("https://example.com/a", "A"), visit("https://example.com/b", "B")),
        )
        assertEquals(listOf(Source.BOOKMARK to "https://example.com/a", Source.HISTORY to "https://example.com/b"),
            out.map { it.source to it.url })
    }

    @Test
    fun `a merged page keeps its strongest match and best source`() {
        // A page matched weakly as a bookmark title, strongly as history:
        // the merged row keeps the strong match.
        val out = rank(
            "doc",
            bookmarks = listOf(bookmark("https://a.example/x", "my docs")),
            history = listOf(
                visit("https://b.example/doc", "", visits = 1),
                visit("https://a.example/x/", "x", visits = 2),
            ),
        )
        assertEquals("https://a.example/x", out.first().url)
        assertEquals(Source.BOOKMARK, out.first().source)
    }

    @Test
    fun `the tab being edited, home tabs and non-matching tabs are left out`() {
        val out = rank(
            "example",
            tabs = listOf(
                tab(1, "https://example.com/current"),
                tab(2, ""),
                tab(3, "https://other.org/", "Other"),
                tab(4, "https://example.com/other"),
            ),
            currentTabId = 1,
        )
        assertEquals(listOf(4L), out.map { it.tabId })
    }

    @Test
    fun `private and regular tabs never show in each other's list`() {
        val tabs = listOf(
            tab(1, "https://example.com/regular"),
            tab(2, "https://example.com/private", private = true),
        )
        assertEquals(listOf(1L), rank("example", tabs = tabs, currentTabId = 99).map { it.tabId })
        assertEquals(listOf(2L), rank("example", tabs = tabs, currentTabId = 99, private = true).map { it.tabId })
    }

    @Test
    fun `database rows count as a match even where the ranking doesn't see one`() {
        // SQL's LIKE matched it (case folding outside ASCII, say): it stays.
        val out = rank("ÄPFEL", history = listOf(visit("https://x.example/", "äpfel")))
        assertEquals(1, out.size)
    }

    @Test
    fun `limit, blank query`() {
        val many = (1..20).map { visit("https://site$it.example/") }
        assertEquals(8, rank("site", history = many).size)
        assertEquals(3, rank("site", history = many, limit = 3).size)
        assertTrue(rank("  ", history = many).isEmpty())
    }

    @Test
    fun `a popup's blank document is not offered as a tab`() {
        val tabs = listOf(
            tab(1, "about:blank"),
            tab(2, "About:Blank", title = "about:blank"),
            tab(3, "https://blank.example/", title = "Blank page"),
        )
        assertEquals(listOf("https://blank.example/"), rank("blank", tabs = tabs).map { it.url })
        assertTrue(rank("about", tabs = tabs).isEmpty())
        assertTrue(!isSuggestibleTab("about:blank"))
        assertTrue(!isSuggestibleTab(""))
        assertTrue(isSuggestibleTab("https://example.com/"))
    }

    @Test
    fun `page key and address shown`() {
        assertEquals(pageKey("https://www.Example.com/"), pageKey("http://example.com"))
        assertEquals(pageKey("https://example.com/a#"), pageKey("https://example.com/a"))
        assertTrue(pageKey("https://example.com/a") != pageKey("https://example.com/b"))
        // Host case folds; path, query and fragment case don't.
        assertEquals("example.com/Docs?Q=1#Top", pageKey("HTTPS://WWW.EXAMPLE.COM/Docs?Q=1#Top"))
        assertTrue(pageKey("https://example.com/Docs") != pageKey("https://example.com/docs"))
        assertEquals("example.com/a", suggestionAddress("https://example.com/a"))
        assertEquals("example.com", suggestionAddress("http://example.com"))
        assertEquals("bzz://name.eth/", suggestionAddress("bzz://name.eth/"))
        assertEquals("en.wikipedia.org", suggestionHost("https://user@en.wikipedia.org:443/wiki"))
    }

    @Test
    fun `pages differing only in path case stay two rows`() {
        val out = rank(
            "example",
            history = listOf(visit("https://example.com/Docs", "Docs"), visit("https://example.com/docs", "docs")),
        )
        assertEquals(listOf("https://example.com/Docs", "https://example.com/docs"), out.map { it.url }.sorted())
    }

    @Test
    fun `an open tab claims its page even when only an older title matched`() {
        // The tab's current title doesn't match; an old visit's title does.
        val out = rank(
            "release",
            tabs = listOf(tab(7, "https://news.example/today", "Today's headlines")),
            history = listOf(visit("https://news.example/today", "Release notes", visits = 3)),
        )
        assertEquals(1, out.size)
        assertEquals(Source.TAB, out.single().source)
        assertEquals(7L, out.single().tabId)
        // Likewise for a bookmark.
        val viaBookmark = rank(
            "release",
            tabs = listOf(tab(7, "https://news.example/today", "Today's headlines")),
            bookmarks = listOf(bookmark("https://news.example/today", "Release notes")),
        )
        assertEquals(listOf(7L), viaBookmark.map { it.tabId })
        // A tab nothing matches is still left out.
        assertTrue(rank("release", tabs = listOf(tab(7, "https://news.example/today", "Today"))).isEmpty())
    }
}
