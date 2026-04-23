package baby.freedom.mobile.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: BookmarkEntry): Long

    @Query("DELETE FROM bookmarks WHERE url = :url")
    suspend fun deleteByUrl(url: String)

    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    fun all(): Flow<List<BookmarkEntry>>

    /** Substring search used by the address-bar auto-complete. */
    @Query(
        "SELECT * FROM bookmarks WHERE url LIKE :q OR title LIKE :q " +
            "ORDER BY createdAt DESC LIMIT :limit",
    )
    fun search(q: String, limit: Int): Flow<List<BookmarkEntry>>

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE url = :url)")
    fun isBookmarked(url: String): Flow<Boolean>

    @Query("DELETE FROM bookmarks")
    suspend fun clear()
}
