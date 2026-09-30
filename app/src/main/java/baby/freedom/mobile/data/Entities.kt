package baby.freedom.mobile.data

import androidx.room.ColumnInfo
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
 *
 * [title] and [url] start as the page's, and the user can edit both
 * (#264). [position] is the user's order: the list reads ascending
 * [position] (ties newest first), a new bookmark goes in above all the
 * others, and a drag or a Move up/down renumbers them
 * ([BrowsingRepository.moveBookmark]).
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
    @ColumnInfo(defaultValue = "0") val position: Long = 0,
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

/**
 * One file the user downloaded (or tried to). The download manager
 * (`browser/Downloads.kt`) owns the rows: it inserts one as
 * [DownloadStatus.RUNNING] when a download starts and moves it to a
 * terminal status when it ends, or to [DownloadStatus.PAUSED] (#265)
 * while its partial file waits in app storage for a resume. Live byte
 * counts while it runs are in memory only (see
 * `DownloadManager.progress`); [receivedBytes] is written when it
 * pauses and when it ends.
 *
 * [displayUrl] is the user-facing source (`bzz://…`, `ipfs://…`,
 * `name.eth/…`, `https://…`, or `data:` — truncated for data URIs, whose
 * bytes are the file itself); [sourceUrl] is what the WebView handed us
 * and what a retry fetches again. [contentUri] is the MediaStore
 * Downloads entry the bytes were written to — null until one was
 * created, and the thing "open" hands to other apps.
 */
@Entity(
    tableName = "downloads",
    indices = [Index(value = ["startedAt"])],
)
data class DownloadEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fileName: String,
    val displayUrl: String,
    val sourceUrl: String,
    val mimeType: String,
    val contentUri: String?,
    val status: String,
    val totalBytes: Long,
    val receivedBytes: Long,
    val error: String?,
    val startedAt: Long,
    val finishedAt: Long?,
    /**
     * Origin of the web page the download came from (`https://host/`),
     * for the same-origin Referer a Retry sends again — see
     * [baby.freedom.mobile.browser.downloadReferer]. Never a path.
     */
    val refererOrigin: String? = null,
    /**
     * The response's validator (#265) — a strong `ETag`, else its
     * `Last-Modified` — sent back as `If-Range` when a paused download
     * resumes, so a file that changed on the server restarts instead of
     * being spliced onto the old bytes. Null: nothing to check against,
     * so a resume starts over.
     */
    val validator: String? = null,
    /**
     * Whether the server said it serves byte ranges (`Accept-Ranges:
     * bytes`) and gave a [validator]: only then does a lost connection
     * pause the download instead of failing it, and only then is a dweb
     * download offered Pause at all.
     */
    @ColumnInfo(defaultValue = "0")
    val resumable: Boolean = false,
    /**
     * A remark on a running or paused download (#265): why it paused
     * ("Connection lost"), or that a resume had to start over. Failures
     * go in [error].
     */
    val note: String? = null,
    /**
     * The User-Agent the first request sent (#265): the tab's, which is
     * the desktop one for a site asked for as a desktop site (#180). A
     * resume or retry sends it again, so a server that gates on it
     * answers the same way. Null on rows from before v5: the default.
     */
    val userAgent: String? = null,
    /**
     * The document the user picked to save the file as (#322), when
     * *Ask where to save each file* was on: a Storage Access Framework
     * URI the finished file is written through. Null: the file goes to
     * `Download/Freedom` like every download before v6.
     */
    val saveTo: String? = null,
    /**
     * Whether the picker created [saveTo] for this download (an empty
     * new file), so a download that doesn't finish may delete it again.
     * False for an existing file the user picked to replace, which is
     * never deleted (#322).
     */
    @ColumnInfo(defaultValue = "0")
    val saveToCreated: Boolean = false,
)

/** Values of [DownloadEntry.status]. Strings, so the column reads in `sqlite3`. */
object DownloadStatus {
    const val RUNNING = "running"
    const val COMPLETED = "completed"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"

    /** Stopped part-way, its partial file kept for a resume (#265). */
    const val PAUSED = "paused"
}
