package baby.freedom.mobile.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The address bar's database suggestions (#443) against real SQLite:
 * one row per page with its visit count, and the best candidates kept
 * from the whole table, not only the newest matching rows.
 */
@RunWith(AndroidJUnit4::class)
class SuggestionMatchesDeviceTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() = db.close()

    private fun matches(q: String, bookmarks: Int = 30, pages: Int = 60) = runBlocking {
        BrowsingRepository(db).suggestionMatches(q, bookmarks, pages).first()
    }

    @Test
    fun anOldOftenVisitedPrefixMatchOutranksManyRecentWeakMatches() = runBlocking {
        val h = db.history()
        // The old favourite: ten visits long ago.
        repeat(10) { h.insert(HistoryEntry(url = "https://github.com/", title = "GitHub", visitedAt = 1L + it)) }
        // 300 newer visits that merely contain "g".
        repeat(300) {
            h.insert(HistoryEntry(url = "https://site$it.example/page", title = "Paging $it", visitedAt = 1000L + it))
        }
        val pages = matches("g").pages
        assertEquals("https://github.com/", pages.first().url)
        assertEquals(10, pages.first().visits)
        assertEquals(10L, pages.first().lastVisit)
        assertEquals(60, pages.size)
    }

    @Test
    fun visitsFoldIntoOnePageWithTheNewestTitle() = runBlocking {
        val h = db.history()
        h.insert(HistoryEntry(url = "https://a.example/", title = "A old", visitedAt = 100))
        h.insert(HistoryEntry(url = "https://a.example/", title = "A new", visitedAt = 300))
        h.insert(HistoryEntry(url = "https://b.example/", title = "B", visitedAt = 200))
        val pages = matches("example").pages
        assertEquals(
            listOf(HistoryPage("https://a.example/", "A new", 2, 300), HistoryPage("https://b.example/", "B", 1, 200)),
            pages,
        )
    }

    @Test
    fun anOldBookmarkThatIsAPrefixMatchIsKept() = runBlocking {
        val b = db.bookmarks()
        b.upsert(BookmarkEntry(url = "https://github.com/", title = "GitHub", createdAt = 1, position = 0))
        repeat(50) {
            b.upsert(BookmarkEntry(url = "https://n$it.example/", title = "Paging $it", createdAt = 100L + it, position = it + 1L))
        }
        val found = matches("g", bookmarks = 5).bookmarks.map { it.url }
        assertEquals(5, found.size)
        assertEquals("https://github.com/", found.first())
        assertTrue(found.drop(1).none { it.contains("github") })
    }
}
