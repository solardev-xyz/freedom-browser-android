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

/** History keeps only its newest visits (#473), against real SQLite. */
@RunWith(AndroidJUnit4::class)
class HistoryCapDeviceTest {
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

    private fun ids() = runBlocking { db.history().recent(limit = 1000).first().map { it.id }.sorted() }

    @Test
    fun recordingPastTheCapDropsTheOldestVisits() = runBlocking {
        val h = db.history()
        repeat(5) { h.insertKeeping(HistoryEntry(url = "https://p$it.example/", title = "P", visitedAt = 10L + it), keep = 3) }
        assertEquals(listOf(3L, 4L, 5L), ids())
    }

    @Test
    fun atOrUnderTheCapNothingIsDropped() = runBlocking {
        val h = db.history()
        repeat(3) { h.insertKeeping(HistoryEntry(url = "https://p$it.example/", title = "P", visitedAt = 10L + it), keep = 3) }
        assertEquals(listOf(1L, 2L, 3L), ids())
    }

    @Test
    fun theTrimGoesByRecordingOrderNotTheClock() = runBlocking {
        val h = db.history()
        // A visit recorded while the clock was far ahead, then normal ones.
        h.insertKeeping(HistoryEntry(url = "https://future.example/", title = "F", visitedAt = 4_102_444_800_000), keep = 2)
        h.insertKeeping(HistoryEntry(url = "https://a.example/", title = "A", visitedAt = 100), keep = 2)
        h.insertKeeping(HistoryEntry(url = "https://b.example/", title = "B", visitedAt = 200), keep = 2)
        val urls = h.recent().first().map { it.url }.toSet()
        assertEquals(setOf("https://a.example/", "https://b.example/"), urls)
    }

    @Test
    fun recordVisitKeepsTheRepositoryCap() = runBlocking {
        val sql = db.openHelper.writableDatabase
        val cap = BrowsingRepository.MAX_HISTORY_VISITS
        sql.execSQL(
            "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < $cap) " +
                "INSERT INTO history (id, url, title, visitedAt) SELECT i, 'https://p' || i || '.example/', 'P', i FROM n",
        )
        val repo = BrowsingRepository(db)
        repo.recordVisit("https://new.example/", "New")
        // The write is fire-and-forget; wait for it to land.
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (repo.history.first().firstOrNull()?.url == "https://new.example/") break
            Thread.sleep(20)
        }
        sql.query("SELECT COUNT(*), MIN(id) FROM history").use { c ->
            c.moveToFirst()
            assertEquals(cap, c.getInt(0))
            assertEquals(2L, c.getLong(1))
        }
    }

    @Test
    fun suggestionsGroupByThePageIndex() {
        val plan = db.openHelper.readableDatabase.query(
            "EXPLAIN QUERY PLAN SELECT url, title, COUNT(*) AS visits, MAX(visitedAt) AS lastVisit FROM history " +
                "WHERE url LIKE ? OR title LIKE ? GROUP BY url",
            arrayOf("%g%", "%g%"),
        ).use { c -> buildString { while (c.moveToNext()) appendLine(c.getString(3)) } }
        assertTrue(plan, "index_history_url" in plan)
        assertTrue(plan, "TEMP B-TREE" !in plan)
    }
}
