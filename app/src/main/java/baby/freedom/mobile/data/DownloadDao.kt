package baby.freedom.mobile.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Insert
    suspend fun insert(entry: DownloadEntry): Long

    @Update
    suspend fun update(entry: DownloadEntry)

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun get(id: Long): DownloadEntry?

    @Query("SELECT * FROM downloads ORDER BY startedAt DESC, id DESC")
    fun all(): Flow<List<DownloadEntry>>

    @Query("SELECT * FROM downloads WHERE status = :status")
    suspend fun withStatus(status: String): List<DownloadEntry>

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM downloads")
    suspend fun clear()
}
