package baby.freedom.mobile.data

/**
 * Visits that differ only in their `#fragment` are one page (#418): a
 * jump to a section (`page#history`, `page#`) is recorded as a visit of
 * its own, but History and the home page's Recent list show the page
 * once. Recording is unchanged; this is only how they're displayed.
 */
internal object PageVisits {

    /**
     * [url] without an empty trailing `#` (`https://x.org/page#` →
     * `https://x.org/page`): what a bookmark is saved under, as the `#`
     * names no place on the page. Anything else is left as it is.
     */
    fun withoutEmptyFragment(url: String): String {
        val hash = url.indexOf('#')
        return if (hash >= 0 && hash == url.lastIndex) url.dropLast(1) else url
    }

    /**
     * The page [url] is on: [url] without its fragment — except for an
     * app that routes by fragment (`#/inbox`, `#!/inbox`), where each
     * route is a page of its own and stays apart.
     */
    fun pageKey(url: String): String {
        val hash = url.indexOf('#')
        if (hash < 0) return url
        val fragment = url.substring(hash + 1)
        if (fragment.startsWith("/") || fragment.startsWith("!")) return url
        return url.substring(0, hash)
    }

    /** [url] names a place in its page: it has a non-empty, non-route fragment. */
    private fun hasPlace(url: String): Boolean = pageKey(url) != withoutEmptyFragment(url)

    /**
     * One row for [visits] of the same page (newest first): the newest
     * visit to the page itself (an empty `#` dropped) if there is one,
     * else the oldest — where the user landed — with the newest visit's
     * time, and a title from any of them if its own is blank.
     */
    fun representative(visits: List<HistoryEntry>): HistoryEntry {
        val newest = visits.first()
        val rep = visits.firstOrNull { !hasPlace(it.url) } ?: visits.last()
        val title = rep.title.ifBlank { visits.firstOrNull { it.title.isNotBlank() }?.title ?: "" }
        return rep.copy(url = withoutEmptyFragment(rep.url), visitedAt = newest.visitedAt, title = title)
    }

    /**
     * History's rows (#418): [entries] (newest first) with each run of
     * back-to-back visits of the same page ([pageKey]) as one row
     * ([representative]). A visit of the page later on, after another
     * page in between, is a row of its own, as every visit is.
     */
    fun mergeRuns(entries: List<HistoryEntry>): Merged {
        val rows = ArrayList<HistoryEntry>()
        val ids = HashMap<Long, List<Long>>()
        var run = ArrayList<HistoryEntry>()
        fun flush() {
            if (run.isEmpty()) return
            val row = representative(run)
            rows += row
            ids[row.id] = run.map { it.id }
        }
        for (entry in entries) {
            if (run.isNotEmpty() && pageKey(run.last().url) != pageKey(entry.url)) {
                flush()
                run = ArrayList()
            }
            run += entry
        }
        flush()
        return Merged(rows, ids)
    }

    /**
     * The home page's Recent list: the most recent distinct pages in
     * [entries] (newest first), one row each ([representative]),
     * ordered by each page's newest visit.
     */
    fun distinctPages(entries: List<HistoryEntry>, limit: Int): List<HistoryEntry> {
        val byPage = LinkedHashMap<String, MutableList<HistoryEntry>>()
        for (entry in entries) {
            val key = pageKey(entry.url)
            val visits = byPage[key]
            if (visits == null) {
                if (byPage.size == limit) continue
                byPage[key] = mutableListOf(entry)
            } else {
                visits += entry
            }
        }
        return byPage.values.map { representative(it) }
    }

    /**
     * History's rows and, for each row's id, the ids of every visit it
     * stands for — removing the row removes all of them.
     */
    class Merged(val rows: List<HistoryEntry>, val ids: Map<Long, List<Long>>) {
        fun idsOf(row: Long): List<Long> = ids[row] ?: listOf(row)
    }
}
