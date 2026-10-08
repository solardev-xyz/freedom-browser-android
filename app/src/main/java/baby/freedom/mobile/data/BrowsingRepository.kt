package baby.freedom.mobile.data

import android.content.Context
import android.database.sqlite.SQLiteException
import android.util.Log
import androidx.room.withTransaction
import baby.freedom.mobile.browser.BookmarkUrls
import baby.freedom.mobile.browser.DisplayUrl
import baby.freedom.mobile.browser.typedForm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
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
class BrowsingRepository internal constructor(
    private val db: AppDatabase,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        // One-time cleanup for installs that predate [isRecordable]
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
     * The History page's list (#263): the most recent visits when
     * [query] is blank, otherwise those whose title or URL contains it
     * (see [HistoryDao.matching]). Re-emits when history changes.
     */
    fun searchHistory(query: String): Flow<List<HistoryEntry>> {
        val q = query.trim()
        return if (q.isEmpty()) history else db.history().matching(likeContains(q))
    }

    /** Whether any history exists, independent of a search. */
    val hasHistory: Flow<Boolean> = db.history().any()

    /**
     * Most recently visited pages, deduplicated by page so a site visited
     * 20 times in a row doesn't crowd out other entries — and jumps to a
     * section of a page (`page#intro`) count as that page (#418,
     * [PageVisits.distinctPages]). Backs the home page's "Recent pages"
     * list.
     *
     * We dedupe in-memory off [history] (which already orders by
     * `visitedAt DESC`), so pages come in the order of their most recent
     * visit.
     */
    fun recentDistinct(limit: Int = 10): Flow<List<HistoryEntry>> =
        history.map { list -> PageVisits.distinctPages(list, limit) }

    /**
     * Record a page visit. No-ops for empty URLs, `about:*`, `data:*`, and
     * `javascript:*` — we don't want internal bookkeeping noise or script
     * evaluations to show up in the user's history.
     */
    fun recordVisit(url: String, title: String) {
        if (!isRecordable(url)) return
        scope.launch {
            db.history().insert(
                HistoryEntry(
                    // Not the startup-only `%XX` spelling of a `.tez` name (#490 R1-M2).
                    url = DisplayUrl.settledName(url),
                    title = title,
                    visitedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    /**
     * Whether [url] is bookmarked, under any spelling of it
     * ([BookmarkUrls.key]): an edited bookmark saved as `x.eth` still
     * fills the star on the page shown as `ipfs://x.eth/` (#296 R1-F1).
     */
    fun isBookmarked(url: String): Flow<Boolean> {
        // Worked out in the flow, on Dispatchers.Default: [BookmarkUrls.key]
        // runs ENSIP-15 normalisation for a non-ASCII name, and this is
        // called from composition each time the page's address changes
        // (#296 R3-M2).
        val key by lazy { bookmarkKey(url) }
        return bookmarks
            .map { list -> list.any { bookmarkKey(it.url) == key } }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
    }

    /**
     * The page a bookmark [url] is for: [BookmarkUrls.key], with an
     * empty trailing `#` dropped first (#418) — `x#` and `x` are one
     * page, also for a bookmark saved as `x#` before bookmarks dropped it.
     */
    private fun bookmarkKey(url: String): String = BookmarkUrls.key(PageVisits.withoutEmptyFragment(url))

    /** The bookmark that is [url] under any spelling of it ([bookmarkKey]). */
    private suspend fun bookmarkFor(url: String, except: Long? = null): BookmarkEntry? {
        db.bookmarks().byUrl(url)?.takeIf { it.id != except }?.let { return it }
        val key = bookmarkKey(url)
        return db.bookmarks().allOnce().firstOrNull { it.id != except && bookmarkKey(it.url) == key }
    }

    /**
     * What the address bar's text matches (#443): bookmarks and history
     * pages whose title or URL contains [query]. History comes one row
     * per page with its visit count, so the browser can rank a page
     * visited 20 times above one visited once (`rankSuggestions`), which
     * also de-duplicates them against each other and the open tabs. Both
     * queries keep their best [bookmarkLimit]/[pageLimit] candidates by
     * match strength, then visits or age ([HistoryDao.suggest]), so an
     * old but often-visited page the text is a prefix of isn't pushed out
     * by a crowd of recent weaker matches. Re-emits when either table
     * changes.
     */
    fun suggestionMatches(
        query: String,
        bookmarkLimit: Int = 30,
        pageLimit: Int = 60,
    ): Flow<LocalMatches> {
        val text = query.trim()
        val pattern = "%" + text.escapeForLike() + "%"
        val prefix = typedForm(text).escapeForLike() + "%"
        val word = text.escapeForLike() + "%"
        return combine(
            db.bookmarks().suggest(pattern, prefix, word, bookmarkLimit),
            db.history().suggest(pattern, prefix, word, pageLimit),
        ) { bookmarks, pages ->
            LocalMatches(
                bookmarks = bookmarks.map { UrlSuggestion(it.url, it.title, UrlSuggestion.Source.BOOKMARK) },
                pages = pages,
            )
        }
    }

    /**
     * Bookmark [address] under [title], above every other bookmark — with
     * an empty trailing `#` dropped ([PageVisits.withoutEmptyFragment]).
     * Completes with the bookmark — an existing bookmark for it is kept as it is
     * (its name and place) and given with [Bookmarked.added] false — or
     * null for an address that isn't bookmarked ([isRecordable]). The
     * write runs in the repository's scope, so a caller that stops
     * waiting (the screen that asked went away) doesn't stop it.
     */
    fun bookmark(address: String, title: String): Deferred<Bookmarked?> = scope.async {
        // `page#` is saved as `page` (#418): the `#` names no place.
        // Spelled the same whenever it is saved, also right after startup (#490 R1-M2).
        val url = DisplayUrl.settledName(PageVisits.withoutEmptyFragment(address))
        if (!isRecordable(url)) return@async null
        try {
            db.withTransaction {
                bookmarkFor(url)?.let { Bookmarked(it.id, added = false) }
                    ?: Bookmarked(
                        db.bookmarks().upsert(
                            BookmarkEntry(
                                url = url,
                                title = title,
                                createdAt = System.currentTimeMillis(),
                                position = db.bookmarks().minPosition() - 1,
                            ),
                        ),
                        added = true,
                    )
            }
        } catch (e: SQLiteException) {
            Log.w(TAG, "bookmark: ${e.message}")
            null
        }
    }

    /** The bookmark with [id], or null if there's none (any more). */
    suspend fun bookmarkById(id: Long): BookmarkEntry? = db.bookmarks().byId(id)

    /**
     * Rename bookmark [id] and/or change its address (#264). [url] must
     * already be what the address bar would load for the typed text
     * (`bookmarkAddress` in the browser package) and [isRecordable], or
     * the bookmark's own address unchanged. Moving it onto a page another
     * bookmark has is refused rather than merged; an edit that keeps it
     * on its own page ([BookmarkUrls.key] unchanged) isn't, even when an
     * older row already shares that page — rows saved under two spellings
     * before #264 stay separate bookmarks, each can still be renamed
     * (#296 R6-F1). In the repository's scope like [bookmark], so closing
     * the dialog mid-save can't lose it.
     */
    fun editBookmark(id: Long, title: String, url: String): Deferred<BookmarkEditResult> = scope.async {
        // Spelled the same whenever it is saved (#490 R1-M2).
        @Suppress("NAME_SHADOWING")
        val url = DisplayUrl.settledName(url)
        try {
            db.withTransaction {
                val current = db.bookmarks().byId(id) ?: return@withTransaction BookmarkEditResult.Gone
                val samePage = current.url == url || bookmarkKey(current.url) == bookmarkKey(url)
                val other = if (samePage) null else bookmarkFor(url, except = id)
                when {
                    other != null ->
                        BookmarkEditResult.Duplicate(other.title, other.url)
                    db.bookmarks().update(id, url, title) == 0 -> BookmarkEditResult.Gone
                    else -> BookmarkEditResult.Saved
                }
            }
        } catch (e: SQLiteException) {
            Log.w(TAG, "editBookmark: ${e.message}")
            BookmarkEditResult.Failed
        }
    }

    /**
     * Put bookmark [id] right after [afterId] in the user's order, or
     * first for a null [afterId] (#264, see [movedAfter]), and renumber
     * them all 0, 1, 2… Completes with whether the stored order was
     * written: false for a move that goes nowhere (or whose bookmark is
     * gone) and for a failed write, when Room sends no new list — so a
     * list showing the move ahead of the save knows to show the stored
     * order again (#296 R1-M2).
     */
    fun moveBookmark(id: Long, afterId: Long?): Deferred<Boolean> = scope.async {
        try {
            db.withTransaction {
                val order = movedAfter(db.bookmarks().orderedIds(), id, afterId) ?: return@withTransaction false
                order.forEachIndexed { index, bookmark ->
                    db.bookmarks().setPosition(bookmark, index.toLong())
                }
                true
            }
        } catch (e: SQLiteException) {
            Log.w(TAG, "moveBookmark: ${e.message}")
            false
        }
    }

    /**
     * Remove [url]'s bookmark, whichever spelling of it was saved
     * ([BookmarkUrls.key]) — the star's Remove, which acts on the page.
     * The Bookmarks list removes one row by its id ([deleteBookmark]).
     */
    fun unbookmark(url: String) {
        scope.launch {
            try {
                db.withTransaction {
                    val key = bookmarkKey(url)
                    db.bookmarks().allOnce()
                        .filter { it.url == url || bookmarkKey(it.url) == key }
                        .forEach { db.bookmarks().delete(it.id) }
                }
            } catch (e: SQLiteException) {
                Log.w(TAG, "unbookmark: ${e.message}")
            }
        }
    }

    fun clearHistory() {
        scope.launch { db.history().clear() }
    }

    /**
     * Delete browsing data (#400): how many visits were recorded at or
     * after [since] (epoch ms; 0 counts them all). Re-emits as history
     * changes.
     */
    fun historyCount(since: Long): Flow<Int> = db.history().countSince(since)

    /**
     * Delete the visits recorded at or after [since] (epoch ms); 0 (or
     * less) deletes every one, as [clearHistory] does.
     */
    fun deleteHistorySince(since: Long) {
        scope.launch {
            try {
                if (since <= 0L) db.history().clear() else db.history().deleteSince(since)
            } catch (e: SQLiteException) {
                Log.w(TAG, "deleteHistorySince: ${e.message}")
            }
        }
    }

    fun clearBookmarks() {
        scope.launch { db.bookmarks().clear() }
    }

    /**
     * Remove the one bookmark [id], and only it: rows saved in another
     * spelling of the same page before #296 stay, as the list shows them
     * as separate bookmarks (#296 R2-F1).
     */
    fun deleteBookmark(id: Long) {
        scope.launch {
            try {
                db.bookmarks().delete(id)
            } catch (e: SQLiteException) {
                Log.w(TAG, "deleteBookmark: ${e.message}")
            }
        }
    }

    fun deleteHistory(id: Long) {
        scope.launch { db.history().delete(id) }
    }

    /** Remove the visits [ids] — one History row standing for several (#418). */
    fun deleteHistory(ids: Collection<Long>) {
        scope.launch {
            try {
                db.withTransaction { ids.forEach { db.history().delete(it) } }
            } catch (e: SQLiteException) {
                Log.w(TAG, "deleteHistory: ${e.message}")
            }
        }
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

    companion object {
        private const val TAG = "BrowsingRepository"

        /**
         * Whether [url] is a page history and bookmarks keep: not blank,
         * `about:*`, `data:*`, `javascript:*` or `blob:*` — internal
         * bookkeeping, script, or bytes that only live in one page.
         */
        fun isRecordable(url: String): Boolean {
            if (url.isBlank()) return false
            val lower = url.trim().lowercase()
            return !lower.startsWith("about:") &&
                !lower.startsWith("data:") &&
                !lower.startsWith("javascript:") &&
                !lower.startsWith("blob:")
        }


        @Volatile private var instance: BrowsingRepository? = null

        fun get(context: Context): BrowsingRepository =
            instance ?: synchronized(this) {
                instance ?: BrowsingRepository(AppDatabase.get(context)).also {
                    instance = it
                }
            }
    }
}
