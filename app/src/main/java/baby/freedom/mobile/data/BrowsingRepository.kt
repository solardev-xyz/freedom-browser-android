package baby.freedom.mobile.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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

    init {
        // One-time cleanup for installs that predate [shouldRecord]
        // rejecting `about:*` — earlier builds wrote `about:blank`
        // rows when the home sentinel flipped, and those still linger
        // in Room. Drop them so the home page's Recent list stays
        // clean instead of permanently showing an "about:blank" tile
        // at the top.
        scope.launch { db.history().deleteByUrl("about:blank") }
        // Same idea for rows whose *title* is `about:blank`: those come
        // from aborted loads recorded before `currentLoadCommitted`
        // gated history writes. The title only shows up as literal
        // "about:blank" when the page never painted, so this is a
        // safe heuristic for "never finished loading".
        scope.launch { db.history().deleteByTitle("about:blank") }
    }

    val history: Flow<List<HistoryEntry>> = db.history().recent()
    val bookmarks: Flow<List<BookmarkEntry>> = db.bookmarks().all()

    /**
     * Most recently visited pages, deduplicated by URL so a site visited
     * 20 times in a row doesn't crowd out other entries. Backs the home
     * page's "Recent pages" list.
     *
     * We dedupe in-memory off [history] (which already orders by
     * `visitedAt DESC`) so the first occurrence we see is the most
     * recent visit — exactly what [distinctBy] keeps.
     */
    fun recentDistinct(limit: Int = 10): Flow<List<HistoryEntry>> =
        history.map { list ->
            list.asSequence()
                .distinctBy { it.url }
                .take(limit)
                .toList()
        }

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

    /**
     * Cache the PNG-encoded favicon [data] for the [pageUrl]'s origin.
     * No-op for URLs that don't have a meaningful origin
     * (`about:blank`, `data:`, `javascript:`, etc.) so we don't
     * overwrite a real site's icon with an internal page's blank one.
     */
    fun storeFavicon(pageUrl: String, data: ByteArray) {
        val origin = FaviconOrigin.from(pageUrl) ?: return
        if (data.isEmpty()) return
        scope.launch {
            db.favicons().upsert(
                FaviconEntry(
                    origin = origin,
                    data = data,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    /**
     * Stream the favicon bytes for the given URL's origin. Emits
     * `null` until something is cached (and again if the row is ever
     * evicted). Caller is responsible for decoding — see
     * `HomeScreen.kt` for a Compose-side remember/decode pattern.
     */
    fun favicon(pageUrl: String): Flow<ByteArray?> {
        val origin = FaviconOrigin.from(pageUrl) ?: return flowOf(null)
        return db.favicons().get(origin)
    }

    fun clearFavicons() {
        scope.launch { db.favicons().clear() }
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
