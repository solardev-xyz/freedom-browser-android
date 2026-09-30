package baby.freedom.mobile.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The History page's search query (#263) against real SQLite: title and
 * display-URL matches, wildcard characters matching literally, newest
 * first.
 */
@RunWith(AndroidJUnit4::class)
class HistorySearchDeviceTest {
    private lateinit var db: AppDatabase
    private val dao get() = db.history()

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
        ).build()
        listOf(
            HistoryEntry(url = "bzz://ab12cd/index.html", title = "Swarm site", visitedAt = 1),
            HistoryEntry(url = "meinhard.eth/blog", title = "Blog", visitedAt = 2),
            HistoryEntry(url = "https://example.com/my_page", title = "Underscore", visitedAt = 3),
            HistoryEntry(url = "https://example.com/mypage", title = "Plain", visitedAt = 4),
            HistoryEntry(url = "https://shop.example/", title = "Save 100% now", visitedAt = 5),
            HistoryEntry(url = "https://news.example/", title = "Daily News", visitedAt = 6),
        ).forEach { dao.insert(it) }
    }

    @After
    fun tearDown() = db.close()

    private fun search(q: String) = runBlocking { dao.matching(likeContains(q)).first().map { it.title } }

    @Test
    fun matchesDwebDisplayForms() {
        assertEquals(listOf("Swarm site"), search("bzz://"))
        assertEquals(listOf("Blog"), search("meinhard.eth"))
    }

    @Test
    fun matchesTitleCaseInsensitively() {
        assertEquals(listOf("Daily News"), search("daily news"))
    }

    @Test
    fun wildcardsMatchLiterally() {
        assertEquals(listOf("Underscore"), search("my_page"))
        assertEquals(listOf("Save 100% now"), search("100%"))
        assertEquals(listOf("Save 100% now"), search("%"))
        assertEquals(emptyList<String>(), search("_x_"))
    }

    @Test
    fun newestFirstAndEmptyWhenNothingMatches() {
        assertEquals(listOf("Plain", "Underscore"), search("example.com/my"))
        assertEquals(emptyList<String>(), search("nothing-like-this"))
    }

    @Test
    fun anyTracksWhetherHistoryExists() = runBlocking {
        assertTrue(dao.any().first())
        dao.clear()
        assertFalse(dao.any().first())
    }
}
