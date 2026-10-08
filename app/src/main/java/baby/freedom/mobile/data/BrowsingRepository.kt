package baby.freedom.mobile.data

import android.content.Context
import android.database.sqlite.SQLiteException
import android.util.Log
import androidx.room.withTransaction
import baby.freedom.mobile.browser.BookmarkUrls
import baby.freedom.mobile.browser.DisplayUrl
import baby.freedom.mobile.browser.typedForm
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private val scope = writeScope()

    /** Keeps a visit or icon asked for before a history delete from landing after it (#480 R1-M1). */
    private val historyGate = HistoryWriteGate()

    /**
     * A ticket for [storeFavicon]/[recordVisit]: a write carrying one
     * taken before a later history delete is dropped. The browser takes
     * one when a page's load starts, so an icon that page reports late
     * can't bring the site back after Delete browsing data.
     */
    fun historyTicket(): Long = historyGate.ticket()

    /** Keeps an icon from landing over one reported after it (#516 R1-F2). */
    private val faviconOrder = FaviconWriteOrder()

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
     * evaluations to show up in the user's history. Keeps only the newest
     * [MAX_HISTORY_VISITS] visits (#473). A visit asked for before a
     * history delete ([ticket], see [historyTicket]) that hasn't landed
     * by then is dropped.
     */
    fun recordVisit(url: String, title: String, ticket: Long = historyTicket()) {
        if (!isRecordable(url)) return
        scope.launch {
            val settled = DisplayUrl.settledName(url)
            // Settling can respell it longer (`%XX`); keep the table's cap.
            if (!isRecordable(settled)) return@launch
            historyGate.write(ticket) {
                db.history().insertKeeping(
                    HistoryEntry(
                        // Not the startup-only `%XX` spelling of a `.tez` name (#490 R1-M2).
                        url = settled,
                        title = storedTitle(title),
                        visitedAt = System.currentTimeMillis(),
                    ),
                    keep = MAX_HISTORY_VISITS,
                )
            }
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
                query = text,
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
        if (!isBookmarkable(address)) return@async null
        // Spelled the same whenever it is saved, also right after startup (#490 R1-M2).
        val url = DisplayUrl.settledName(PageVisits.withoutEmptyFragment(address))
        // Settling can respell it longer (`%XX`); keep the table's cap (#461).
        if (!isRecordable(url)) return@async null
        try {
            db.withTransaction {
                bookmarkFor(url)?.let { Bookmarked(it.id, added = false) }
                    ?: Bookmarked(
                        db.bookmarks().upsert(
                            BookmarkEntry(
                                url = url,
                                title = storedTitle(title),
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
        // `bookmarkAddress` already refuses an address this long; this
        // keeps the table safe whoever calls (#461).
        if (url.length > MAX_URL_CHARS) return@async BookmarkEditResult.Failed
        try {
            db.withTransaction {
                val current = db.bookmarks().byId(id) ?: return@withTransaction BookmarkEditResult.Gone
                val samePage = current.url == url || bookmarkKey(current.url) == bookmarkKey(url)
                val other = if (samePage) null else bookmarkFor(url, except = id)
                when {
                    other != null ->
                        BookmarkEditResult.Duplicate(other.title, other.url)
                    db.bookmarks().update(id, url, storedTitle(title)) == 0 -> BookmarkEditResult.Gone
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
        historyGate.revoke()
        scope.launch { historyGate.forget { db.history().clear() } }
    }

    /**
     * Delete browsing data (#400): how many visits were recorded at or
     * after [since] (epoch ms; 0 counts them all). Re-emits as history
     * changes.
     */
    fun historyCount(since: Long): Flow<Int> = db.history().countSince(since)

    /**
     * Delete the visits recorded at or after [since] (epoch ms); 0 (or
     * less) deletes every one, as [clearHistory] does. The favicon cache
     * goes with them for the same range ([faviconsToForget], #480): it
     * lists every site visited and when, so it is history too. A
     * bookmarked site keeps its icon, without the time. A visit or icon
     * asked for before this call and not yet written is dropped, not
     * written after it ([HistoryWriteGate], #480 R1-M1).
     */
    fun deleteHistorySince(since: Long) {
        historyGate.revoke()
        scope.launch { historyGate.forget { forgetHistorySince(since) } }
    }

    private suspend fun forgetHistorySince(since: Long) {
        try {
            if (since <= 0L) db.history().clear() else db.history().deleteSince(since)
        } catch (e: SQLiteException) {
            Log.w(TAG, "deleteHistorySince: ${e.message}")
        }
        try {
            db.withTransaction {
                // Keyed as [favicon] reads a bookmark's icon.
                val kept = db.bookmarks().allOnce()
                    .mapNotNullTo(HashSet()) { FaviconOrigin.from(DisplayUrl.settledName(it.url)) }
                // The sites a ranged delete leaves older visits of, keyed as
                // [favicon] reads a history row's icon (#480 R3-M1).
                val remaining = HashMap<String, Long>()
                if (since > 0L) {
                    db.history().latestVisits().forEach { v ->
                        val origin = FaviconOrigin.from(DisplayUrl.settledName(v.url)) ?: return@forEach
                        remaining.merge(origin, v.visitedAt, ::maxOf)
                    }
                }
                val forget = faviconsToForget(
                    db.favicons().stampsSince(since.coerceAtLeast(0L)), since, kept, remaining,
                )
                forget.delete.forEach { db.favicons().delete(it) }
                forget.undate.forEach { db.favicons().undate(it) }
                forget.restamp.forEach { (origin, at) -> db.favicons().restamp(origin, at) }
            }
        } catch (e: SQLiteException) {
            Log.w(TAG, "deleteHistorySince: ${e.message}")
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
    fun storeFavicon(pageUrl: String, data: ByteArray, ticket: Long = historyTicket()) {
        if (data.isEmpty()) return
        storeFavicon(pageUrl, ticket) { data }
    }

    /**
     * As above, with the PNG made by [encode] on [Dispatchers.Default]
     * rather than on the caller's thread (#482): the WebView hands its
     * icon over on the main thread, and a full-quality PNG encode there
     * costs a frame on every page load. [encode] returning null or an
     * empty array stores nothing.
     *
     * Encodes for one origin can finish in any order (a big icon takes
     * longer than the small one a page swaps in right after it), so the
     * call is numbered here, in the order the WebView reported the icons,
     * and a write older than one already stored for its origin is dropped
     * (#516 R1-F2).
     */
    fun storeFavicon(pageUrl: String, ticket: Long, encode: () -> ByteArray?) {
        val seq = faviconOrder.next()
        scope.launch {
            // Keyed on the settled spelling, as history rows are
            // (#490 R3-M3): a `café.tez` page shown `caf%C3%A9.tez`
            // before warm-up must still file its icon under the name its
            // history row carries. settledName may decode the ENSIP-15
            // tables, so off the main thread.
            val origin = FaviconOrigin.from(DisplayUrl.settledName(pageUrl)) ?: return@launch
            val data = withContext(Dispatchers.Default) { encode() }
            if (data == null || data.isEmpty()) return@launch
            // Not for a page loaded before a history delete (#480 R1-M1).
            historyGate.write(ticket) {
                if (!faviconOrder.admit(origin, seq)) return@write
                db.withTransaction {
                    storeFaviconRow(
                        db.favicons(),
                        FaviconEntry(
                            origin = origin,
                            data = data,
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                }
            }
        }
    }

    /**
     * Stream the favicon bytes for the given URL's origin. Emits
     * `null` until something is cached (and again if the row is ever
     * evicted), and again only when the stored bytes actually change
     * (#482). Caller is responsible for decoding, off the main thread —
     * see `rememberFavicon` in `HomeScreen.kt`.
     */
    fun favicon(pageUrl: String): Flow<ByteArray?> {
        // Settled like [storeFavicon]'s key (#490 R3-M3), so a page read
        // by its shown spelling finds its icon either side of warm-up.
        if (FaviconOrigin.from(pageUrl) == null) return flowOf(null)
        return flow {
            val origin = FaviconOrigin.from(DisplayUrl.settledName(pageUrl))
            if (origin == null) emit(null) else emitAll(db.favicons().get(origin))
        }.distinctIcons().flowOn(Dispatchers.IO)
    }

    fun clearFavicons() {
        historyGate.revoke()
        scope.launch { historyGate.forget { db.favicons().clear() } }
    }

    companion object {
        private const val TAG = "BrowsingRepository"

        /**
         * The scope the repository's fire-and-forget writes run in (#462).
         * Nobody waits on them, so whatever one throws — a
         * `SQLiteFullException` on a phone whose storage is full, from
         * every finished page load ([recordVisit]) or favicon — is logged
         * and dropped here instead of reaching the thread's uncaught
         * handler and killing the app with every open tab. The write is
         * lost, as it would be anyway; the browser keeps going.
         */
        internal fun writeScope(dispatcher: CoroutineDispatcher = Dispatchers.IO): CoroutineScope =
            CoroutineScope(
                SupervisorJob() + dispatcher +
                    CoroutineExceptionHandler { _, e -> Log.w(TAG, "write failed: ${e.message}", e) },
            )

        /**
         * The longest address history and bookmarks keep (#461), the same
         * 8 KiB the saved tabs allow (`TabsState.MAX_SAVED_ADDRESS`).
         * Chromium loads addresses up to 2 MiB, and a row that big no
         * longer fits the 2 MB window Android reads query results
         * through: every read of the table that reaches it — the Home
         * page's Recent list, History, the bookmark star — throws
         * `SQLiteBlobTooBigException`. A shortened address would be a
         * different page, so a longer one isn't kept at all.
         */
        const val MAX_URL_CHARS = 8 * 1024

        /**
         * How many visits history keeps (#473); recording one more drops
         * the oldest ([HistoryDao.insertKeeping]). Without a cap the table
         * only grew, and the address bar's suggestions scan all of it on
         * every keystroke ([HistoryDao.suggest]). About half a year of
         * heavy browsing; a page visited often within it still counts all
         * those visits, and a bookmark is never touched.
         */
        const val MAX_HISTORY_VISITS = 10_000

        /**
         * The longest title history and bookmarks keep (#461); a longer
         * one is cut ([storedTitle]). Chromium already caps a page's
         * title well below the read limit; this is for whatever else
         * reaches the table (a bookmark's typed name).
         */
        const val MAX_TITLE_CHARS = 1024

        /**
         * Whether [url] is a page history and bookmarks keep: not blank,
         * `about:*`, `data:*`, `javascript:*` or `blob:*` — internal
         * bookkeeping, script, or bytes that only live in one page — and
         * no longer than [MAX_URL_CHARS].
         */
        fun isRecordable(url: String): Boolean {
            if (url.isBlank() || url.length > MAX_URL_CHARS) return false
            val lower = url.trim().lowercase()
            return !lower.startsWith("about:") &&
                !lower.startsWith("data:") &&
                !lower.startsWith("javascript:") &&
                !lower.startsWith("blob:")
        }

        /**
         * Whether [bookmark] saves [address] (null from it otherwise): the
         * address it stores ([PageVisits.withoutEmptyFragment]) is
         * [isRecordable]. The star asks this to say why nothing was added.
         */
        fun isBookmarkable(address: String): Boolean =
            isRecordable(PageVisits.withoutEmptyFragment(address))

        /**
         * [title] as history and bookmarks store it: at most
         * [MAX_TITLE_CHARS], cut between characters, never inside a
         * surrogate pair.
         */
        fun storedTitle(title: String): String {
            if (title.length <= MAX_TITLE_CHARS) return title
            val end = if (Character.isHighSurrogate(title[MAX_TITLE_CHARS - 1])) {
                MAX_TITLE_CHARS - 1
            } else {
                MAX_TITLE_CHARS
            }
            return title.substring(0, end)
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
