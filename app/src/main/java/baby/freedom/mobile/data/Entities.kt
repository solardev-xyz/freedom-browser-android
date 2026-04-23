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

/**
 * A cached favicon. [origin] is a canonical scheme+authority string
 * (e.g. `https://spiegel.de`, `ens://meinhard.eth`, `bzz://<hash>`) —
 * every page under that origin shares the same icon, which matches
 * both what users expect and how the underlying sites serve their
 * `/favicon.ico`. [data] is the PNG-encoded bitmap as captured from
 * the WebView's `onReceivedIcon` callback.
 *
 * We don't care about equality semantics on this type (it's never
 * held in a Set / used as a Map key), so the `ByteArray` field is
 * harmless; Room only needs reflection-based copy behaviour.
 */
@Entity(tableName = "favicons")
data class FaviconEntry(
    @PrimaryKey val origin: String,
    val data: ByteArray,
    val updatedAt: Long,
)
