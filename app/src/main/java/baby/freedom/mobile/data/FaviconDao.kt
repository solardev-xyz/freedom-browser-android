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

    /** The icons stored or refreshed at or after [since] (epoch ms), for Delete browsing data (#480). */
    @Query("SELECT origin, updatedAt FROM favicons WHERE updatedAt >= :since")
    suspend fun stampsSince(since: Long): List<FaviconStamp>

    @Query("DELETE FROM favicons WHERE origin = :origin")
    suspend fun delete(origin: String)

    /** Keep [origin]'s icon but drop when it was last seen (#480). */
    @Query("UPDATE favicons SET updatedAt = 0 WHERE origin = :origin")
    suspend fun undate(origin: String)
}

/** A favicon row without its bytes: which origin, stored when. */
data class FaviconStamp(val origin: String, val updatedAt: Long)

/**
 * What deleting history at or after [since] (epoch ms; 0 for all time)
 * does to the favicon cache (#480), given its rows' [stamps]: every
 * origin whose icon was stored or refreshed in the range goes, since the
 * table otherwise lists each site visited and when — except an origin in
 * [kept] (a bookmarked site's), which keeps its icon so the bookmark
 * still shows it, but loses the time.
 */
internal data class FaviconForget(val delete: List<String>, val undate: List<String>)

internal fun faviconsToForget(stamps: List<FaviconStamp>, since: Long, kept: Set<String>): FaviconForget {
    val inRange = stamps.filter { since <= 0L || it.updatedAt >= since }
    return FaviconForget(
        delete = inRange.filter { it.origin !in kept }.map { it.origin },
        // Already 0: nothing left to forget (and a ranged delete never reaches it again).
        undate = inRange.filter { it.origin in kept && it.updatedAt != 0L }.map { it.origin },
    )
}
