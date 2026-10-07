package baby.freedom.mobile.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: HistoryEntry): Long

    @Query("SELECT * FROM history ORDER BY visitedAt DESC LIMIT :limit")
    fun recent(limit: Int = 500): Flow<List<HistoryEntry>>

    /**
     * Prefix / substring search for address-bar auto-complete. We order
     * by most-recent visit and oversample (via [limit]) so the caller
     * can dedupe by URL in-memory without losing matches.
     */
    @Query(
        "SELECT * FROM history WHERE url LIKE :q OR title LIKE :q " +
            "ORDER BY visitedAt DESC LIMIT :limit",
    )
    fun search(q: String, limit: Int): Flow<List<HistoryEntry>>

    /**
     * The address bar's history suggestions (#443): one row per page
     * (URL) whose title or URL contains [q], with its visit count and
     * latest visit, and the title of that latest visit (SQLite takes a
     * bare column from the `MAX()` row). Ordered the way the browser
     * ranks them (`rankSuggestions`) — match strength first, then visits,
     * then recency — so the [limit] it keeps are the best candidates from
     * the whole history, not just the most recent visits: an old,
     * often-visited page whose address starts with the text still makes
     * it. The strength here is coarse (SQL `LIKE`); the browser
     * re-ranks exactly. [prefix] is the typed text without scheme or
     * `www.` followed by `%`, [word] the text as typed followed by `%`;
     * all three carry no wildcards of their own (`escapeForLike`).
     */
    @Query(
        "SELECT url, title, COUNT(*) AS visits, MAX(visitedAt) AS lastVisit FROM history " +
            "WHERE url LIKE :q OR title LIKE :q " +
            "GROUP BY url " +
            "ORDER BY " + SUGGEST_STRENGTH + " DESC, visits DESC, lastVisit DESC " +
            "LIMIT :limit",
    )
    fun suggest(q: String, prefix: String, word: String, limit: Int): Flow<List<HistoryPage>>

    /**
     * The History page's search (#263): every visit whose title or
     * stored URL contains [pattern], newest first. [pattern] is a full
     * `LIKE` pattern built by [likeContains] — `%`, `_` and `\` in the
     * user's text are escaped with `\`, so they match themselves. The
     * URL column holds the display form (`bzz://…`, `name.eth/…`,
     * `https://…`), so that's what a query matches. SQLite's `LIKE` is
     * case-insensitive for ASCII letters only.
     */
    @Query(
        "SELECT * FROM history WHERE url LIKE :pattern ESCAPE '\\' " +
            "OR title LIKE :pattern ESCAPE '\\' " +
            "ORDER BY visitedAt DESC LIMIT :limit",
    )
    fun matching(pattern: String, limit: Int = 500): Flow<List<HistoryEntry>>

    /** Whether there's any history at all, whatever a search shows. */
    @Query("SELECT EXISTS(SELECT 1 FROM history)")
    fun any(): Flow<Boolean>

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM history WHERE url = :url")
    suspend fun deleteByUrl(url: String)

    @Query("DELETE FROM history WHERE title = :title")
    suspend fun deleteByTitle(title: String)

    @Query("DELETE FROM history")
    suspend fun clear()

    /** How many visits were recorded at or after [since] (epoch ms): Delete browsing data's count. */
    @Query("SELECT COUNT(*) FROM history WHERE visitedAt >= :since")
    fun countSince(since: Long): Flow<Int>

    /** Delete the visits recorded at or after [since] (epoch ms): Delete browsing data's time range. */
    @Query("DELETE FROM history WHERE visitedAt >= :since")
    suspend fun deleteSince(since: Long)
}

/** A page in history, for [HistoryDao.suggest]. */
data class HistoryPage(
    val url: String,
    val title: String,
    val visits: Int,
    val lastVisit: Long,
)

/**
 * SQL for the coarse match strength the address bar's suggestion queries
 * sort by (#443), mirroring `MatchStrength` in the browser package:
 * address prefix (past any scheme and `www.`) 4, a host label 3, a title
 * word 2, anything else 1. Binds `:prefix` and `:word`.
 */
internal const val SUGGEST_STRENGTH =
    "(CASE " +
        "WHEN url LIKE :prefix OR url LIKE 'www.' || :prefix " +
        "OR url LIKE '%://' || :prefix OR url LIKE '%://www.' || :prefix THEN 4 " +
        "WHEN url LIKE '%.' || :word THEN 3 " +
        "WHEN title LIKE :word OR title LIKE '% ' || :word THEN 2 " +
        "ELSE 1 END)"
