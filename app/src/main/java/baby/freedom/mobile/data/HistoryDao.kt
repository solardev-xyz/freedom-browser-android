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
