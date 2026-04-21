package baby.freedom.mobile.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
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
