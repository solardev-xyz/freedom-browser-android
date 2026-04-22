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

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM history")
    suspend fun clear()
}
