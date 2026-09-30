package baby.freedom.mobile.browser

import baby.freedom.mobile.data.HistoryEntry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** One day's visits on the History page (#263), newest first. */
internal data class HistoryDay(
    val date: LocalDate,
    val label: String,
    val entries: List<HistoryEntry>,
)

/**
 * Split [entries] (already newest first, as [HistoryDao] returns them)
 * into runs by the local calendar day of each visit in [zone]. Order
 * is kept, so days come newest first and each day's visits stay newest
 * first. A visit stamped on a later day than [today] (a clock that was
 * ahead when it was recorded) gets its own dated header rather than
 * being folded into Today.
 */
internal fun historyDays(
    entries: List<HistoryEntry>,
    today: LocalDate,
    zone: ZoneId,
    locale: Locale = Locale.getDefault(),
): List<HistoryDay> {
    val days = mutableListOf<HistoryDay>()
    var date: LocalDate? = null
    var run = mutableListOf<HistoryEntry>()
    fun flush() {
        val d = date ?: return
        days += HistoryDay(d, historyDayLabel(d, today, locale), run)
    }
    for (entry in entries) {
        val day = Instant.ofEpochMilli(entry.visitedAt).atZone(zone).toLocalDate()
        if (day != date) {
            flush()
            date = day
            run = mutableListOf()
        }
        run += entry
    }
    flush()
    return days
}

/**
 * "Today", "Yesterday", or the full localized date ("Monday, September
 * 28, 2026") — the year is always shown so an old visit can't be read
 * as this year's.
 */
internal fun historyDayLabel(date: LocalDate, today: LocalDate, locale: Locale): String =
    when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale).format(date)
    }
