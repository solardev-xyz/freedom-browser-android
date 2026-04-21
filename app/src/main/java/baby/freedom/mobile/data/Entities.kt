package baby.freedom.mobile.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One entry in the user's browsing history.
 *
 * [url] is the canonical form we show in the address bar (`bzz://…`,
 * `ens://…`, or plain `https://…`), *not* the gateway-rewritten URL —
 * otherwise history would show Freedom's implementation detail rather
 * than what the user actually visited. [visitedAt] is epoch millis.
 */
@Entity(
    tableName = "history",
    indices = [Index(value = ["visitedAt"])],
)
data class HistoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String,
    val visitedAt: Long,
)

/**
 * A bookmarked page. We key by [url] (unique) rather than an auto-id so
 * the bookmark toggle in the chrome can be a simple upsert/delete on the
 * currently-visible URL without having to look up "is this the same as
 * an existing row?" first.
 */
@Entity(
    tableName = "bookmarks",
    indices = [Index(value = ["url"], unique = true)],
)
data class BookmarkEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String,
    val createdAt: Long,
)
