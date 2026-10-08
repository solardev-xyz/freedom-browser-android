package baby.freedom.mobile.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

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

    /** The stored PNG bytes for [origin], read once (null when none). */
    @Query("SELECT data FROM favicons WHERE origin = :origin LIMIT 1")
    suspend fun dataOnce(origin: String): ByteArray?

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

    /** Say [origin]'s icon was last seen at [at] (epoch ms), its latest visit still in history (#480 R3-M1). */
    @Query("UPDATE favicons SET updatedAt = :at WHERE origin = :origin")
    suspend fun restamp(origin: String, at: Long)
}

/**
 * Store [entry], but leave the bytes alone when they're the icon already
 * on file (#482): a site sends the same favicon on every load, and
 * rewriting the blob each time wakes every [FaviconDao.get] reader for
 * nothing. Only the time moves then, so a later Delete browsing data
 * still sees the visit ([faviconsToForget]). Run it in a transaction.
 */
internal suspend fun storeFaviconRow(dao: FaviconDao, entry: FaviconEntry) {
    val stored = dao.dataOnce(entry.origin)
    if (stored != null && stored.contentEquals(entry.data)) {
        dao.restamp(entry.origin, entry.updatedAt)
    } else {
        dao.upsert(entry)
    }
}

/**
 * Room re-runs [FaviconDao.get] on any write to the table, and hands back
 * a new array each time even when the icon is the same (#482): pass on
 * only an actual change, compared by content, so a row decodes its icon
 * once rather than on every other site's visit.
 */
internal fun Flow<ByteArray?>.distinctIcons(): Flow<ByteArray?> =
    distinctUntilChanged { old, new -> old contentEquals new }

/** A favicon row without its bytes: which origin, stored when. */
data class FaviconStamp(val origin: String, val updatedAt: Long)

/**
 * What deleting history at or after [since] (epoch ms; 0 for all time)
 * does to the favicon cache (#480), given its rows' [stamps]: every
 * origin whose icon was stored or refreshed in the range goes, since the
 * table otherwise lists each site visited and when — except
 *
 * - an origin with a visit still in history after the delete
 *   ([remaining]: origin → its latest remaining visit, epoch ms), which
 *   keeps its icon so that row still shows it, re-dated to that visit so
 *   the table says no more than history does and a later delete
 *   reaching that visit takes the icon too (#480 R3-M1);
 * - otherwise an origin in [kept] (a bookmarked site's), which keeps its
 *   icon so the bookmark still shows it, but loses the time.
 */
internal data class FaviconForget(
    val delete: List<String>,
    val undate: List<String>,
    val restamp: Map<String, Long> = emptyMap(),
)

internal fun faviconsToForget(
    stamps: List<FaviconStamp>,
    since: Long,
    kept: Set<String>,
    remaining: Map<String, Long> = emptyMap(),
): FaviconForget {
    val inRange = stamps.filter { since <= 0L || it.updatedAt >= since }
    val (visited, rest) = inRange.partition { it.origin in remaining }
    return FaviconForget(
        delete = rest.filter { it.origin !in kept }.map { it.origin },
        // Already 0: nothing left to forget (and a ranged delete never reaches it again).
        undate = rest.filter { it.origin in kept && it.updatedAt != 0L }.map { it.origin },
        restamp = visited.associate { it.origin to remaining.getValue(it.origin) },
    )
}
