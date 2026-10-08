package baby.freedom.mobile.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #480 R1-M1, against real SQLite: an icon or visit asked for before
 * Delete browsing data, written after it, doesn't put the site back.
 */
@RunWith(AndroidJUnit4::class)
class HistoryDeleteRaceDeviceTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: BrowsingRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
        ).build()
        repo = BrowsingRepository(db)
    }

    @After
    fun tearDown() = db.close()

    private fun origins() = runBlocking {
        db.openHelper.writableDatabase.query("SELECT origin FROM favicons ORDER BY origin").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
    }

    private fun waitFor(what: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && !what()) Thread.sleep(20)
    }

    @Test
    fun aLateIconFromAPageLoadedBeforeTheDeleteIsDropped() {
        // The page's load started (ticket taken), then All time was deleted.
        val ticket = repo.historyTicket()
        repo.deleteHistorySince(0L)
        repo.storeFavicon("https://slow.example/", byteArrayOf(1, 2, 3), ticket)
        // An icon from a page loaded after the delete still goes in.
        repo.storeFavicon("https://fresh.example/", byteArrayOf(4))
        waitFor { origins().isNotEmpty() }
        Thread.sleep(200)
        assertEquals(listOf("https://fresh.example"), origins())
    }

    @Test
    fun aVisitAskedForBeforeTheDeleteIsDropped() = runBlocking {
        val ticket = repo.historyTicket()
        repo.deleteHistorySince(0L)
        repo.recordVisit("https://slow.example/", "Slow", ticket)
        repo.recordVisit("https://fresh.example/", "Fresh")
        waitFor { runBlocking { repo.history.first().isNotEmpty() } }
        Thread.sleep(200)
        assertEquals(listOf("https://fresh.example/"), repo.history.first().map { it.url })
        assertNull(repo.history.first().firstOrNull { it.url == "https://slow.example/" })
    }

    /** #480 R3-M1: a ranged delete leaves a site's older visit, so its icon stays, dated to that visit. */
    @Test
    fun aRangedDeleteKeepsTheIconOfASiteWithAnOlderVisitLeft() = runBlocking {
        val now = System.currentTimeMillis()
        val older = now - 3 * 60 * 60_000L
        db.history().insert(HistoryEntry(url = "https://kept.example/a", title = "A", visitedAt = older))
        db.history().insert(HistoryEntry(url = "https://kept.example/b", title = "B", visitedAt = now - 10 * 60_000L))
        db.history().insert(HistoryEntry(url = "https://gone.example/", title = "G", visitedAt = now - 5 * 60_000L))
        db.favicons().upsert(FaviconEntry("https://kept.example", byteArrayOf(1), now - 10 * 60_000L))
        db.favicons().upsert(FaviconEntry("https://gone.example", byteArrayOf(2), now - 5 * 60_000L))
        repo.deleteHistorySince(now - 60 * 60_000L)
        waitFor { origins() == listOf("https://kept.example") }
        assertEquals(listOf("https://kept.example"), origins())
        assertEquals(listOf("https://kept.example/a"), repo.history.first().map { it.url })
        assertEquals(1, repo.favicon("https://kept.example/a").first()?.size)
        assertEquals(listOf(FaviconStamp("https://kept.example", older)), db.favicons().stampsSince(0L))
    }
}
