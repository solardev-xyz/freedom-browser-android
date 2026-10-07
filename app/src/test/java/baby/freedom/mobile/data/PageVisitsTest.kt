package baby.freedom.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** Visits that differ only in their #fragment show as one page (#418). */
class PageVisitsTest {

    private fun visit(id: Long, url: String, at: Long, title: String = "Page") =
        HistoryEntry(id = id, url = url, title = title, visitedAt = at)

    @Test
    fun `an empty trailing hash is dropped and nothing else`() {
        assertEquals("https://x.org/a", PageVisits.withoutEmptyFragment("https://x.org/a#"))
        assertEquals("https://x.org/", PageVisits.withoutEmptyFragment("https://x.org/#"))
        assertEquals("https://x.org/a#b", PageVisits.withoutEmptyFragment("https://x.org/a#b"))
        assertEquals("https://x.org/a#b#", PageVisits.withoutEmptyFragment("https://x.org/a#b#"))
        assertEquals("https://x.org/a", PageVisits.withoutEmptyFragment("https://x.org/a"))
    }

    @Test
    fun `the page key drops a fragment but keeps a route`() {
        assertEquals("https://x.org/a", PageVisits.pageKey("https://x.org/a#Mascot"))
        assertEquals("https://x.org/a", PageVisits.pageKey("https://x.org/a#"))
        assertEquals("https://x.org/a?q=1", PageVisits.pageKey("https://x.org/a?q=1#top"))
        assertEquals("https://mail.x.org/#/inbox", PageVisits.pageKey("https://mail.x.org/#/inbox"))
        assertEquals("https://x.org/#!/inbox", PageVisits.pageKey("https://x.org/#!/inbox"))
        assertEquals("bzz://abc/page", PageVisits.pageKey("bzz://abc/page#s"))
    }

    @Test
    fun `back-to-back jumps within a page are one row standing for every visit`() {
        val rows = listOf(
            visit(5, "https://x.org/a#Mascot", 500),
            visit(4, "https://x.org/a#", 400),
            visit(3, "https://x.org/a", 300),
            visit(2, "https://y.org/", 200),
            visit(1, "https://x.org/a", 100),
        )
        val merged = PageVisits.mergeRuns(rows)
        assertEquals(listOf("https://x.org/a", "https://y.org/", "https://x.org/a"), merged.rows.map { it.url })
        // The page itself is the row (its newest such visit), at the newest visit's time.
        assertEquals(4L, merged.rows[0].id)
        assertEquals(500L, merged.rows[0].visitedAt)
        assertEquals(listOf(5L, 4L, 3L), merged.idsOf(4))
        // A later visit of the page, after another page, is a row of its own.
        assertEquals(listOf(1L), merged.idsOf(1))
        assertEquals(listOf(2L), merged.idsOf(2))
    }

    @Test
    fun `a run of jumps only is shown as where the user landed`() {
        val merged = PageVisits.mergeRuns(
            listOf(
                visit(3, "https://x.org/a#c", 300, title = ""),
                visit(2, "https://x.org/a#b", 200, title = "Article"),
            ),
        )
        assertEquals(1, merged.rows.size)
        assertEquals("https://x.org/a#b", merged.rows[0].url)
        assertEquals(300L, merged.rows[0].visitedAt)
        assertEquals("Article", merged.rows[0].title)
    }

    @Test
    fun `hash routes stay separate rows`() {
        val merged = PageVisits.mergeRuns(
            listOf(
                visit(2, "https://m.org/#/settings", 200),
                visit(1, "https://m.org/#/inbox", 100),
            ),
        )
        assertEquals(2, merged.rows.size)
    }

    @Test
    fun `recent pages are distinct by page in order of their newest visit`() {
        val history = listOf(
            visit(6, "https://x.org/a#Mascot", 600),
            visit(5, "https://y.org/", 500),
            visit(4, "https://x.org/a#", 400),
            visit(3, "https://z.org/", 300),
            visit(2, "https://x.org/a", 200),
            visit(1, "https://w.org/", 100),
        )
        val recent = PageVisits.distinctPages(history, limit = 3)
        assertEquals(listOf("https://x.org/a", "https://y.org/", "https://z.org/"), recent.map { it.url })
        assertEquals(600L, recent[0].visitedAt)
        // One row per page, so ids stay unique for the list's keys.
        assertEquals(recent.size, recent.map { it.id }.toSet().size)
    }

    @Test
    fun `merging nothing gives nothing`() {
        assertEquals(0, PageVisits.mergeRuns(emptyList()).rows.size)
        assertEquals(0, PageVisits.distinctPages(emptyList(), 8).size)
    }

    @Test
    fun `neighbours kept apart stay separate rows`() {
        // A search for "page" over page#a, other, page#b: the other page
        // is filtered out, so the two visits look back to back.
        val results = listOf(
            visit(3, "https://x.org/page#b", 300),
            visit(1, "https://x.org/page#a", 100),
        )
        assertEquals(1, PageVisits.mergeRuns(results).rows.size)
        val searched = PageVisits.mergeRuns(results) { _, _ -> true }
        assertEquals(2, searched.rows.size)
        assertEquals(listOf(3L), searched.idsOf(3))
        assertEquals(listOf(1L), searched.idsOf(1))
    }
}
