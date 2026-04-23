package baby.freedom.mobile.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Single choke-point for reads/writes to [AppDatabase]. Owns a private
 * coroutine scope so write operations ("record this visit", "toggle
 * bookmark") are fire-and-forget from the UI — they never block Compose
 * recompositions and they survive the activity that triggered them.
 *
 * Lifetime: process-scoped; there is one [BrowsingRepository] for the app
 * (held by the [AppDatabase] companion via [get]).
 */
class BrowsingRepository private constructor(
    private val db: AppDatabase,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val history: Flow<List<HistoryEntry>> = db.history().recent()
    val bookmarks: Flow<List<BookmarkEntry>> = db.bookmarks().all()

    /**
     * Record a page visit. No-ops for empty URLs, `about:*`, `data:*`, and
     * `javascript:*` — we don't want internal bookkeeping noise or script
     * evaluations to show up in the user's history.
     */
    fun recordVisit(url: String, title: String) {
        if (!shouldRecord(url)) return
        scope.launch {
            db.history().insert(
                HistoryEntry(
                    url = url,
                    title = title,
                    visitedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    fun isBookmarked(url: String): Flow<Boolean> = db.bookmarks().isBookmarked(url)

    /**
     * Address-bar auto-complete suggestions.
     *
     * Bookmarks come first (since the user asked the browser to remember
     * them), followed by recent history with bookmarked URLs filtered
     * out. History is deduped by URL in-memory: we oversample from Room
     * (`limit * 3`) so that a site visited 20 times in a row doesn't
     * crowd out other matches from the final list.
     *
     * [query] may be empty — in that case we fall back to the most-recent
     * bookmarks and history, which gives the address bar a useful
     * "top sites" list the moment it's focused.
     */
    fun suggestions(query: String, limit: Int = 8): Flow<List<UrlSuggestion>> {
        val pattern = "%" + query.trim().escapeForLike() + "%"
        return combine(
            db.bookmarks().search(pattern, limit),
            db.history().search(pattern, limit * 3),
        ) { bookmarks, history ->
            val bookmarkUrls = bookmarks.mapTo(HashSet()) { it.url }
            val bookmarkItems = bookmarks.map {
                UrlSuggestion(it.url, it.title, UrlSuggestion.Source.BOOKMARK)
            }
            val historyItems = history.asSequence()
                .filter { it.url !in bookmarkUrls }
                .distinctBy { it.url }
                .map { UrlSuggestion(it.url, it.title, UrlSuggestion.Source.HISTORY) }
                .toList()
            (bookmarkItems + historyItems).take(limit)
        }
    }

    /** Add (or replace) the bookmark for [url]. */
    fun bookmark(url: String, title: String) {
        if (!shouldRecord(url)) return
        scope.launch {
            db.bookmarks().upsert(
                BookmarkEntry(
                    url = url,
                    title = title,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    fun unbookmark(url: String) {
        scope.launch { db.bookmarks().deleteByUrl(url) }
    }

    fun clearHistory() {
        scope.launch { db.history().clear() }
    }

    fun clearBookmarks() {
        scope.launch { db.bookmarks().clear() }
    }

    fun deleteHistory(id: Long) {
        scope.launch { db.history().delete(id) }
    }

    private fun shouldRecord(url: String): Boolean {
        if (url.isBlank()) return false
        val lower = url.lowercase()
        return !lower.startsWith("about:") &&
            !lower.startsWith("data:") &&
            !lower.startsWith("javascript:") &&
            !lower.startsWith("blob:")
    }

    companion object {
        @Volatile private var instance: BrowsingRepository? = null

        fun get(context: Context): BrowsingRepository =
            instance ?: synchronized(this) {
                instance ?: BrowsingRepository(AppDatabase.get(context)).also {
                    instance = it
                }
            }
    }
}
