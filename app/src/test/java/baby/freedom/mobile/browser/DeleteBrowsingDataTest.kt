package baby.freedom.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeleteBrowsingDataTest {
    private val now = 1_800_000_000_000L
    private val hour = 60L * 60L * 1000L

    @Test
    fun `ranges are Chrome's, in Chrome's order`() {
        assertEquals(
            listOf("Last hour", "Last 24 hours", "Last 7 days", "Last 4 weeks", "All time"),
            DeleteRange.entries.map { it.label },
        )
    }

    @Test
    fun `each range starts that far back, and all time takes every visit`() {
        assertEquals(now - hour, DeleteRange.LastHour.since(now))
        assertEquals(now - 24 * hour, DeleteRange.Last24Hours.since(now))
        assertEquals(now - 7 * 24 * hour, DeleteRange.Last7Days.since(now))
        assertEquals(now - 28 * 24 * hour, DeleteRange.Last4Weeks.since(now))
        assertEquals(0L, DeleteRange.AllTime.since(now))
    }

    @Test
    fun `a range on a clock near zero never turns into all time`() {
        // since() of 0 means "every visit"; a bounded range must never ask for that.
        for (range in DeleteRange.entries - DeleteRange.AllTime) {
            assertTrue(range.name, range.since(10L) > 0L)
        }
    }

    @Test
    fun `defaults are the last hour with every box checked`() {
        val choice = DeleteChoice()
        assertEquals(DeleteRange.LastHour, choice.range)
        assertTrue(choice.history && choice.siteData && choice.cache)
        assertTrue(choice.canDelete)
    }

    @Test
    fun `delete is off only with no box checked`() {
        assertFalse(DeleteChoice(history = false, siteData = false, cache = false).canDelete)
        assertTrue(DeleteChoice(history = true, siteData = false, cache = false).canDelete)
        assertTrue(DeleteChoice(history = false, siteData = true, cache = false).canDelete)
        assertTrue(DeleteChoice(history = false, siteData = false, cache = true).canDelete)
    }

    @Test
    fun `cookies and site data always ask first, history and cache alone never do`() {
        for (range in DeleteRange.entries) for (h in listOf(true, false)) for (c in listOf(true, false)) {
            assertTrue("$range $h $c", DeleteChoice(range, history = h, siteData = true, cache = c).needsConfirm)
            assertFalse("$range $h $c", DeleteChoice(range, history = h, siteData = false, cache = c).needsConfirm)
        }
        // The defaults (last hour, every box) ask: one tap never signs the user out everywhere.
        assertTrue(DeleteChoice().needsConfirm)
    }

    @Test
    fun `the confirmation says a bounded range doesn't hold site data back`() {
        assertEquals(
            "This signs you out of every site and deletes every site's stored data for all time, " +
                "not only for the last hour. It can't be undone.",
            deleteConfirmMessage(DeleteChoice(range = DeleteRange.LastHour)),
        )
        assertTrue(deleteConfirmMessage(DeleteChoice(range = DeleteRange.Last7Days)).contains("not only for the last 7 days"))
        assertEquals(
            "This signs you out of every site and deletes every site's stored data. It can't be undone.",
            deleteConfirmMessage(DeleteChoice(range = DeleteRange.AllTime)),
        )
    }

    @Test
    fun `closed tabs go with history or with site data, not with the cache alone`() {
        assertTrue(DeleteChoice(history = true, siteData = false, cache = false).forgetsClosedTabs)
        assertTrue(DeleteChoice(history = false, siteData = true, cache = false).forgetsClosedTabs)
        assertFalse(DeleteChoice(history = false, siteData = false, cache = true).forgetsClosedTabs)
    }

    @Test
    fun `all-time note shows for a bounded range with cookies or cache checked`() {
        for (range in DeleteRange.entries) {
            for (siteData in listOf(false, true)) for (cache in listOf(false, true)) for (history in listOf(false, true)) {
                val note = allTimeOnlyNote(DeleteChoice(range, history, siteData, cache))
                val expected = range != DeleteRange.AllTime && (siteData || cache)
                assertEquals("$range h=$history s=$siteData c=$cache", expected, note != null)
            }
        }
    }

    @Test
    fun `all-time note names only what is checked`() {
        val both = allTimeOnlyNote(DeleteChoice(DeleteRange.LastHour, siteData = true, cache = true))
        val cookies = allTimeOnlyNote(DeleteChoice(DeleteRange.LastHour, siteData = true, cache = false))
        val cache = allTimeOnlyNote(DeleteChoice(DeleteRange.LastHour, siteData = false, cache = true))
        assertNotNull(both)
        assertTrue(both!!.startsWith("Cookies, site data and cached files"))
        assertTrue(cookies!!.startsWith("Cookies and site data"))
        assertFalse(cookies.contains("Cached", ignoreCase = true))
        assertTrue(cache!!.startsWith("Cached images and files"))
        assertNull(allTimeOnlyNote(DeleteChoice(DeleteRange.LastHour, siteData = false, cache = false)))
    }

    @Test
    fun `all-time note mentions the history range only with history checked`() {
        for (siteData in listOf(false, true)) for (cache in listOf(false, true)) {
            if (!siteData && !cache) continue
            val with = allTimeOnlyNote(DeleteChoice(DeleteRange.LastHour, history = true, siteData, cache))!!
            val without = allTimeOnlyNote(DeleteChoice(DeleteRange.LastHour, history = false, siteData, cache))!!
            assertTrue(with, with.endsWith("Browsing history keeps to the range."))
            assertFalse(without, without.contains("history", ignoreCase = true))
        }
    }

    @Test
    fun `history line counts visits in the range`() {
        assertEquals("1 visit, plus closed tabs and tabs waiting to be restored", historyCountLine(1))
        assertEquals("42 visits, plus closed tabs and tabs waiting to be restored", historyCountLine(42))
        assertEquals("No visits in this range, plus closed tabs and tabs waiting to be restored", historyCountLine(0))
    }

    @Test
    fun `done message says history alone, otherwise browsing data`() {
        assertEquals("Browsing data deleted", deleteDoneMessage(DeleteChoice()))
        assertEquals(
            "Browsing history deleted",
            deleteDoneMessage(DeleteChoice(DeleteRange.AllTime, history = true, siteData = false, cache = false)),
        )
        assertEquals(
            "Browsing data deleted",
            deleteDoneMessage(DeleteChoice(DeleteRange.Last7Days, history = false, siteData = false, cache = true)),
        )
    }
}

class DeleteSwarmCacheTest {
    private val now = 1_800_000_000_000L

    private class Recorder {
        val history = mutableListOf<Long>()
        var swarmClears = 0
        val handed = mutableListOf<DeleteChoice>()
    }

    private fun run(choice: DeleteChoice): Recorder = Recorder().also { r ->
        deleteBrowsingData(choice, { r.history += it }, { r.swarmClears++ }, now) { r.handed += it }
    }

    @Test
    fun `the Swarm node cache box is off by default`() {
        assertFalse(DeleteChoice().swarmCache)
    }

    @Test
    fun `the Swarm box alone enables Delete data, without asking first`() {
        val only = DeleteChoice(history = false, siteData = false, cache = false, swarmCache = true)
        assertTrue(only.canDelete)
        assertFalse(only.needsConfirm)
        assertEquals("Browsing data deleted", deleteDoneMessage(only))
        assertEquals(
            "Browsing data deleted",
            deleteDoneMessage(DeleteChoice(history = true, siteData = false, cache = false, swarmCache = true)),
        )
    }

    @Test
    fun `a checked box clears the Swarm cache once, whatever the range`() {
        for (range in DeleteRange.entries) {
            val r = run(DeleteChoice(range = range, history = false, siteData = false, cache = false, swarmCache = true))
            assertEquals("$range", 1, r.swarmClears)
            assertTrue("$range", r.history.isEmpty())
            assertEquals(1, r.handed.size)
        }
    }

    @Test
    fun `an unchecked box never touches the Swarm cache`() {
        val r = run(DeleteChoice())
        assertEquals(0, r.swarmClears)
        assertEquals(listOf(now - 60L * 60L * 1000L), r.history)
    }

    @Test
    fun `nothing checked deletes nothing`() {
        val r = run(DeleteChoice(history = false, siteData = false, cache = false, swarmCache = false))
        assertEquals(0, r.swarmClears)
        assertTrue(r.history.isEmpty() && r.handed.isEmpty())
    }
}
