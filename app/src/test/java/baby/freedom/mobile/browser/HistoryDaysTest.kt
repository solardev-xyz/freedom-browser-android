package baby.freedom.mobile.browser

import baby.freedom.mobile.data.HistoryEntry
import baby.freedom.mobile.data.likeContains
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

class HistoryDaysTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val today = LocalDate.of(2026, 9, 30)

    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int = 0, id: Long) = HistoryEntry(
        id = id,
        url = "https://example.com/$id",
        title = "p$id",
        visitedAt = LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli(),
    )

    @Test
    fun `groups newest-first visits into Today, Yesterday, then dates`() {
        val entries = listOf(
            at(2026, 9, 30, 9, id = 1),
            at(2026, 9, 30, 0, 0, id = 2),
            at(2026, 9, 29, 23, 59, id = 3),
            at(2026, 9, 28, 12, id = 4),
            at(2025, 9, 28, 12, id = 5),
        )
        val days = historyDays(entries, today, zone, Locale.US)
        assertEquals(
            listOf("Today", "Yesterday", "Monday, September 28, 2026", "Sunday, September 28, 2025"),
            days.map { it.label },
        )
        assertEquals(listOf(listOf(1L, 2L), listOf(3L), listOf(4L), listOf(5L)), days.map { d -> d.entries.map { it.id } })
    }

    @Test
    fun `day boundary follows the zone, not UTC`() {
        // 23:30 in Berlin on the 29th is 21:30 UTC — Yesterday either way;
        // 00:30 on the 30th is still the 29th in UTC but Today here.
        val days = historyDays(listOf(at(2026, 9, 30, 0, 30, id = 1), at(2026, 9, 29, 23, 30, id = 2)), today, zone, Locale.US)
        assertEquals(listOf("Today", "Yesterday"), days.map { it.label })
    }

    @Test
    fun `a visit stamped in the future gets its own dated header`() {
        val days = historyDays(listOf(at(2026, 10, 2, 8, id = 1), at(2026, 9, 30, 8, id = 2)), today, zone, Locale.US)
        assertEquals(listOf("Friday, October 2, 2026", "Today"), days.map { it.label })
    }

    @Test
    fun `no entries, no days`() {
        assertEquals(emptyList<HistoryDay>(), historyDays(emptyList(), today, zone))
    }

    @Test
    fun `like pattern escapes wildcards instead of dropping them`() {
        assertEquals("%name.eth%", likeContains("name.eth"))
        assertEquals("%my\\_page%", likeContains("my_page"))
        assertEquals("%100\\%%", likeContains("100%"))
        assertEquals("%a\\\\b%", likeContains("a\\b"))
    }

    @Test
    fun `No matches shows only while the field still holds a search`() {
        val none = "foo" to emptyList<HistoryEntry>()
        assertEquals(true, showsNoMatches(none, "foo"))
        // A new search in flight keeps the previous No matches up.
        assertEquals(true, showsNoMatches(none, "foob"))
        // Cleared with ×/Back: the stale empty answer for "foo" must not
        // show over the blank field before the unfiltered list arrives.
        assertEquals(false, showsNoMatches(none, ""))
        assertEquals(false, showsNoMatches(none, "   "))
        assertEquals(false, showsNoMatches("" to emptyList(), ""))
        assertEquals(false, showsNoMatches(null, "foo"))
        assertEquals(false, showsNoMatches("foo" to listOf(at(2026, 9, 30, 9, id = 1)), "foo"))
    }
}
