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

    /** In the user's order (#264): [BookmarkEntry.position], ties newest first. */
    @Query("SELECT * FROM bookmarks ORDER BY position ASC, id DESC")
    fun all(): Flow<List<BookmarkEntry>>

    /** The ids of [all], in the same order. */
    @Query("SELECT id FROM bookmarks ORDER BY position ASC, id DESC")
    suspend fun orderedIds(): List<Long>

    @Query("SELECT * FROM bookmarks WHERE id = :id")
    suspend fun byId(id: Long): BookmarkEntry?

    @Query("SELECT * FROM bookmarks WHERE url = :url")
    suspend fun byUrl(url: String): BookmarkEntry?

    /** The topmost position in use, or 0 with no bookmarks. */
    @Query("SELECT COALESCE(MIN(position), 0) FROM bookmarks")
    suspend fun minPosition(): Long

    @Query("UPDATE bookmarks SET position = :position WHERE id = :id")
    suspend fun setPosition(id: Long, position: Long)

    /** Rename / re-address (#264). The number of rows changed: 0 if [id] is gone. */
    @Query("UPDATE bookmarks SET url = :url, title = :title WHERE id = :id")
    suspend fun update(id: Long, url: String, title: String): Int

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
