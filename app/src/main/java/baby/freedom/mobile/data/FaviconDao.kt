package baby.freedom.mobile.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FaviconDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: FaviconEntry)

    /**
     * Streams the raw PNG bytes for an origin (null when we haven't
     * captured one yet). Callers that want to display a favicon should
     * decode the bytes to a [android.graphics.Bitmap] off the main
     * thread and remember the result — this DAO deliberately stays at
     * the byte-level so Room doesn't have to ship an image-decoding
     * dependency into the data module.
     */
    @Query("SELECT data FROM favicons WHERE origin = :origin LIMIT 1")
    fun get(origin: String): Flow<ByteArray?>

    @Query("DELETE FROM favicons")
    suspend fun clear()
}
