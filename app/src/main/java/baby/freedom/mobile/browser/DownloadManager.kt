package baby.freedom.mobile.browser

import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.WebSettings
import baby.freedom.mobile.data.AppDatabase
import baby.freedom.mobile.data.DownloadEntry
import baby.freedom.mobile.data.DownloadStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

private const val LOG_TAG = "Downloads"

/** Folder under the shared Downloads collection that Freedom writes to. */
private const val DOWNLOAD_SUBDIR = "Freedom"

/** Minimum gap between two in-memory progress publications per download. */
private const val PROGRESS_INTERVAL_MS = 200L

/**
 * Transient-status retries before the first byte of a dweb download,
 * the same idea as the interceptor's subresource retries: a cold Swarm
 * node regularly answers 404 for content it's still pulling from the
 * DHT. Longer in total than the interceptor's (~17 s) because nothing
 * here races Chromium's request-hang detector.
 */
private val DWEB_RETRY_DELAYS_MS = longArrayOf(0, 500, 1_000, 2_000, 3_000, 5_000, 8_000, 10_000, 15_000)
private val DWEB_TRANSIENT_STATUSES = setOf(404, 500, 502, 503, 504)

private const val MAX_REDIRECTS = 8

/** Live byte counts of a running download. [total] is -1 when unknown. */
data class DownloadProgress(val received: Long, val total: Long)

/** One-shot notices for the browser chrome's snackbar. */
sealed class DownloadEvent {
    /** The download's [DownloadEntry.id]. */
    abstract val id: Long
    abstract val fileName: String
    data class Started(override val id: Long, override val fileName: String) : DownloadEvent()
    data class Completed(override val id: Long, override val fileName: String) : DownloadEvent()
    data class Failed(override val id: Long, override val fileName: String, val reason: String) : DownloadEvent()
}

/**
 * The browser's download manager (#79).
 *
 * `WebView` has no download support of its own — a `DownloadListener`
 * only reports "the page wants to save this URL" — and the platform
 * `DownloadManager` can't help with most of what Freedom downloads:
 * virtual dweb origins (`<label>.bzz.freedom.baby`) never resolve in
 * DNS, and cold Swarm content needs the same retrieval hints and
 * transient-404 retries the request interceptor gives page loads. So
 * the fetch happens here, in-process:
 *
 * - **dweb** (virtual origins and `bzz://` / `ipfs://` / `ipns://` /
 *   `ens://`): mapped onto the local gateway through
 *   [Gateways.gatewayUrlFor], the interceptor's own mapping, with the
 *   Swarm retrieval headers and a retry budget for cold content.
 * - **http(s)**: fetched with the WebView's cookies and User-Agent, so
 *   logged-in / session-gated downloads work. The only Referer is the
 *   page's bare origin, and only to that same origin
 *   ([downloadReferer]).
 * - **`data:`**: decoded in-process.
 *
 * Bytes stream straight into a pending `MediaStore.Downloads` entry
 * (`Download/Freedom/…`), which becomes visible to other apps only when
 * complete — no storage permission is needed on API 29+, and a
 * cancelled or failed download leaves nothing behind. Every download
 * gets a row in the `downloads` table (the history screen); running
 * byte counts live in [progress] only.
 *
 * Lifetime: process-scoped, like [baby.freedom.mobile.data.BrowsingRepository].
 * Downloads survive the Activity; a process death mid-download is
 * swept up on the next start (the row is marked interrupted and the
 * half-written file deleted).
 */
class DownloadManager private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver
    private val dao = AppDatabase.get(appContext).downloads()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    /** Running jobs and what they block on; see [DownloadCancellation]. */
    private val cancellation = DownloadCancellation()

    private val _progress = MutableStateFlow<Map<Long, DownloadProgress>>(emptyMap())
    /** Running downloads' byte counts, keyed by [DownloadEntry.id]. */
    val progress: StateFlow<Map<Long, DownloadProgress>> = _progress.asStateFlow()

    private val _events = MutableSharedFlow<DownloadEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<DownloadEvent> = _events.asSharedFlow()

    /** Download history, newest first. */
    val downloads: Flow<List<DownloadEntry>> = dao.all()

    /**
     * Rows still "running" at startup belong to a previous process that
     * died mid-download: nothing is fetching them any more. [start]
     * waits for this so the sweep can't catch a download of this process.
     */
    private val staleSweep: Job = scope.launch {
        for (stale in dao.withStatus(DownloadStatus.RUNNING)) {
            stale.contentUri?.let { deleteQuietly(it) }
            dao.update(
                stale.copy(
                    status = DownloadStatus.FAILED,
                    contentUri = null,
                    error = "Interrupted",
                    finishedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    /**
     * Start downloading [url] — the arguments of
     * `DownloadListener.onDownloadStart`, plus the page the download
     * came from ([pageUrl]; null for a navigation the user started,
     * which has no referrer). Only [pageUrl]'s origin is kept, for a
     * same-origin Referer ([downloadReferer]).
     */
    fun start(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long,
        pageUrl: String?,
    ) = enqueue(url, userAgent, contentDisposition, mimeType, contentLength, downloadRefererOrigin(pageUrl))

    private fun enqueue(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long,
        refererOrigin: String?,
    ) {
        val target = classifyDownloadUrl(url, Gateways::isLocalGateway, Gateways::toDisplay)
        val guessedMime = normalizeMime(mimeType)
        val initialName = downloadFileName(contentDisposition, url, guessedMime, ::extensionForMime)
        scope.launch {
            staleSweep.join()
            val id = dao.insert(
                DownloadEntry(
                    fileName = initialName,
                    displayUrl = target.displayUrl,
                    // A data: URI *is* the file — possibly megabytes — and
                    // doesn't belong in a history row (Room's cursor window
                    // is 2 MB). Blank means "can't be retried".
                    sourceUrl = if (target is DownloadTarget.Data) "" else url,
                    mimeType = guessedMime ?: "application/octet-stream",
                    contentUri = null,
                    status = DownloadStatus.RUNNING,
                    totalBytes = contentLength.coerceAtLeast(-1),
                    receivedBytes = 0,
                    error = null,
                    startedAt = System.currentTimeMillis(),
                    finishedAt = null,
                    refererOrigin = refererOrigin.takeIf { target is DownloadTarget.Web },
                ),
            )
            _events.tryEmit(DownloadEvent.Started(id, initialName))
            val job = scope.launch(start = CoroutineStart.LAZY) {
                run(id, target, userAgent, contentDisposition, refererOrigin)
            }
            val cancelled = !cancellation.register(id, job)
            // Cancelled before it began: run() never starts, so mark the
            // row the way run() would have.
            if (cancelled) {
                job.cancel()
                dao.get(id)?.let {
                    dao.update(
                        it.copy(
                            status = DownloadStatus.CANCELLED,
                            finishedAt = System.currentTimeMillis(),
                        ),
                    )
                }
            } else {
                job.start()
            }
        }
    }

    /** Fetch a finished-unsuccessfully download again, as a new entry. */
    fun retry(entry: DownloadEntry) {
        if (entry.sourceUrl.isBlank()) return
        scope.launch { dao.delete(entry.id) }
        enqueue(
            url = entry.sourceUrl,
            // The app never overrides the WebView's User-Agent, so the
            // default one is what the first attempt sent.
            userAgent = runCatching { WebSettings.getDefaultUserAgent(appContext) }.getOrNull(),
            contentDisposition = null,
            mimeType = entry.mimeType,
            contentLength = -1,
            refererOrigin = entry.refererOrigin,
        )
    }

    /** The history row for [id], if it still exists. */
    suspend fun entry(id: Long): DownloadEntry? = dao.get(id)

    /** Stop a running download; its partial file is deleted. */
    fun cancel(id: Long) = cancellation.cancel(id)

    /**
     * Forget a download. Only the history row goes — a completed file
     * stays in Downloads, where the user put it; a running one is
     * cancelled first.
     */
    fun remove(id: Long) {
        cancel(id)
        scope.launch {
            dao.delete(id)
            // A job registered after this finds no row and ends at once;
            // an early-cancel mark has nothing left to guard.
            cancellation.forget(id)
        }
    }

    /**
     * Hand a completed download to whichever app opens its type.
     * Returns a user-facing reason when that isn't possible.
     */
    suspend fun open(context: Context, entry: DownloadEntry): String? {
        val uri = entry.contentUri?.let(Uri::parse) ?: return "File not available"
        when (withContext(Dispatchers.IO) { queryDownloadFileState(resolver, uri) }) {
            DownloadFileState.PRESENT, DownloadFileState.UNKNOWN -> Unit
            // In the system trash (restorable for 30 days): keep the row
            // and its URI, so it opens again once the user restores it.
            DownloadFileState.TRASHED -> return "The file is in the trash"
            DownloadFileState.GONE -> {
                // Deleted outside the app: the row is no longer "completed" —
                // mark it failed so it stops offering an open that can't work
                // and offers Retry instead.
                markFileDeleted(entry.id)
                return "The file was deleted"
            }
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, entry.mimeType.ifBlank { "*/*" })
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            null
        } catch (_: ActivityNotFoundException) {
            "No app can open this file"
        }
    }

    private suspend fun markFileDeleted(id: Long) = withContext(Dispatchers.IO) {
        // Re-read: the caller's copy may be stale (removed, or retried).
        val current = dao.get(id)?.takeIf { it.status == DownloadStatus.COMPLETED } ?: return@withContext
        dao.update(
            current.copy(
                status = DownloadStatus.FAILED,
                contentUri = null,
                error = "File deleted",
            ),
        )
    }

    // ---------------------------------------------------------------

    private suspend fun run(
        id: Long,
        target: DownloadTarget,
        userAgent: String?,
        contentDisposition: String?,
        refererOrigin: String?,
    ) {
        var entry = dao.get(id) ?: return
        var pending: Uri? = null
        // Everything that can block on the network is tracked as soon as
        // it exists — each connection before connect(), then the body —
        // so [cancel] can close it (see [DownloadCancellation]).
        val job = currentCoroutineContext()[Job]
        val track: (AutoCloseable) -> Unit = { cancellation.track(id, job, it) }
        try {
            val body = openBody(target, userAgent, contentDisposition, refererOrigin, track)
            body.use { src ->
                track(src)
                currentCoroutineContext().ensureActive()
                val mime = src.mimeType ?: entry.mimeType
                val name = downloadFileName(
                    contentDisposition = src.contentDisposition ?: contentDisposition,
                    url = src.nameUrl,
                    mimeType = mime,
                    extensionForMime = ::extensionForMime,
                )
                val uri = createPending(name, mime)
                pending = uri
                entry = entry.copy(
                    fileName = name,
                    mimeType = mime,
                    contentUri = uri.toString(),
                    totalBytes = src.length,
                )
                dao.update(entry)
                val received = copyWithProgress(id, src.stream, uri, src.length)
                if (src.length >= 0 && received < src.length) {
                    throw IOException("Connection closed early")
                }
                publish(uri)
                // MediaStore settles a name collision ("x (1).pdf") when
                // the item stops being pending; show the name it chose.
                val finalName = queryDisplayName(uri) ?: name
                entry = entry.copy(
                    fileName = finalName,
                    status = DownloadStatus.COMPLETED,
                    totalBytes = received,
                    receivedBytes = received,
                    finishedAt = System.currentTimeMillis(),
                )
                withContext(NonCancellable) { dao.update(entry) }
                _events.tryEmit(DownloadEvent.Completed(id, finalName))
            }
        } catch (t: Throwable) {
            // [cancel] closes the socket under a blocked read, so a
            // cancelled download usually surfaces as an IOException, not
            // a CancellationException — the job's state is the truth.
            val cancelled = t is CancellationException || !currentCoroutineContext().isActive
            if (!cancelled) Log.w(LOG_TAG, "download $id failed", t)
            val reason = when {
                cancelled -> null
                t is DownloadFailure -> t.message ?: "Download failed"
                t is IOException -> t.message?.takeIf { it.isNotBlank() }?.let { "Network error: $it" }
                    ?: "Network error"
                else -> "Download failed"
            }
            withContext(NonCancellable) {
                pending?.let { deleteQuietly(it.toString()) }
                dao.update(
                    entry.copy(
                        status = if (cancelled) DownloadStatus.CANCELLED else DownloadStatus.FAILED,
                        contentUri = null,
                        receivedBytes = _progress.value[id]?.received ?: 0,
                        error = reason,
                        finishedAt = System.currentTimeMillis(),
                    ),
                )
            }
            if (reason != null) _events.tryEmit(DownloadEvent.Failed(id, entry.fileName, reason))
            if (t is CancellationException) throw t
        } finally {
            cancellation.release(id)
            _progress.update { it - id }
        }
    }

    /** A response body plus what it says about itself. */
    private class Body(
        val stream: InputStream,
        val length: Long,
        val mimeType: String?,
        val contentDisposition: String?,
        /** The URL whose last path segment names the file (final URL after redirects). */
        val nameUrl: String,
        private val onClose: () -> Unit = {},
    ) : AutoCloseable {
        override fun close() {
            runCatching { stream.close() }
            onClose()
        }
    }

    /** A failure with a message fit for the downloads list as-is. */
    private class DownloadFailure(message: String) : IOException(message)

    private suspend fun openBody(
        target: DownloadTarget,
        userAgent: String?,
        contentDisposition: String?,
        refererOrigin: String?,
        track: (AutoCloseable) -> Unit,
    ): Body = when (target) {
        is DownloadTarget.Data -> {
            val payload = parseDataUri(target.uri) ?: throw DownloadFailure("Malformed data: URI")
            Body(
                stream = payload.bytes.inputStream(),
                length = payload.bytes.size.toLong(),
                mimeType = normalizeMime(payload.mimeType),
                contentDisposition = contentDisposition,
                nameUrl = target.uri,
            )
        }
        is DownloadTarget.Dweb -> {
            val gatewayUrl = Gateways.gatewayUrlFor(target.root, target.pathAndQuery)
                ?: throw DownloadFailure(
                    if (target.root is ContentRoot.Ens) "Couldn't resolve ${target.root.name}"
                    else "Node not running",
                )
            // Name the file after the dweb path, not the gateway URL
            // (whose last segment for a bare root would be the hash).
            fetchDweb(gatewayUrl, nameUrl = target.displayUrl.let { d ->
                if (d.contains("://")) d else "ens://$d"
            }, track = track)
        }
        is DownloadTarget.LocalGateway -> fetchDweb(target.url, nameUrl = target.displayUrl, track = track)
        is DownloadTarget.Web -> fetchWeb(target.url, userAgent, refererOrigin, track)
        is DownloadTarget.Unsupported ->
            throw DownloadFailure("${target.scheme}: downloads aren't supported")
    }

    private suspend fun fetchDweb(
        gatewayUrl: String,
        nameUrl: String,
        track: (AutoCloseable) -> Unit,
    ): Body {
        var lastStatus = 0
        for (delayMs in DWEB_RETRY_DELAYS_MS) {
            if (delayMs > 0) delay(delayMs)
            currentCoroutineContext().ensureActive()
            val conn = try {
                (URL(gatewayUrl).openConnection() as HttpURLConnection).apply {
                    track(AutoCloseable { disconnect() })
                    connectTimeout = 5_000
                    readTimeout = 60_000
                    instanceFollowRedirects = true
                    setRequestProperty("Accept-Encoding", "identity")
                    setRequestProperty("Swarm-Chunk-Retrieval-Timeout", "30s")
                    setRequestProperty("Swarm-Redundancy-Strategy", "3")
                    setRequestProperty("Swarm-Redundancy-Fallback-Mode", "true")
                    connect()
                }
            } catch (_: java.net.ConnectException) {
                throw DownloadFailure("Node not running")
            } catch (e: IOException) {
                Log.i(LOG_TAG, "dweb download attempt failed: $gatewayUrl", e)
                continue
            }
            val status = conn.responseCode
            if (status in 200..299) return bodyOf(conn, nameUrl)
            lastStatus = status
            conn.disconnect()
            if (status !in DWEB_TRANSIENT_STATUSES) break
            Log.i(LOG_TAG, "dweb download: transient $status for $gatewayUrl")
        }
        throw DownloadFailure(
            when (lastStatus) {
                0 -> "Gateway didn't answer"
                404 -> "Content not found"
                else -> "Gateway error $lastStatus"
            },
        )
    }

    /**
     * Plain web download. Redirects are followed by hand so an
     * `http` → `https` hop (which `HttpURLConnection` refuses to follow)
     * works, with the cookie jar consulted for every hop.
     *
     * Known gaps against Chromium's own fetch (it's a re-fetch, not the
     * page's request): `CookieManager` hands out every cookie for the
     * URL with no SameSite attribute, so a cross-site download also
     * carries `SameSite=Strict` cookies Chromium would have withheld;
     * and `DownloadListener` reports neither the method nor the body,
     * so the download of a form POST response (or a single-use URL the
     * WebView already consumed) is re-requested as a plain GET and may
     * fail or save different content.
     */
    private suspend fun fetchWeb(
        url: String,
        userAgent: String?,
        refererOrigin: String?,
        track: (AutoCloseable) -> Unit,
    ): Body {
        var current = url
        repeat(MAX_REDIRECTS + 1) {
            currentCoroutineContext().ensureActive()
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                track(AutoCloseable { disconnect() })
                connectTimeout = 15_000
                readTimeout = 60_000
                instanceFollowRedirects = false
                setRequestProperty("Accept-Encoding", "identity")
                userAgent?.takeIf { it.isNotBlank() }?.let { setRequestProperty("User-Agent", it) }
                runCatching { CookieManager.getInstance().getCookie(current) }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { setRequestProperty("Cookie", it) }
                // Re-decided per hop: a redirect off the page's origin
                // (or down to http) drops it.
                downloadReferer(refererOrigin, current)?.let { setRequestProperty("Referer", it) }
            }
            val status = conn.responseCode
            if (status in 300..399) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                current = when (val hop = downloadRedirect(current, location)) {
                    is DownloadRedirect.Follow -> hop.url
                    is DownloadRedirect.Refuse -> throw DownloadFailure(hop.reason)
                }
                return@repeat
            }
            if (status !in 200..299) {
                conn.disconnect()
                throw DownloadFailure("Server error $status")
            }
            return bodyOf(conn, current)
        }
        throw DownloadFailure("Too many redirects")
    }

    private fun bodyOf(conn: HttpURLConnection, nameUrl: String): Body = Body(
        stream = conn.inputStream,
        length = conn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L,
        mimeType = normalizeMime(conn.contentType),
        contentDisposition = conn.getHeaderField("Content-Disposition"),
        nameUrl = nameUrl,
        onClose = { conn.disconnect() },
    )

    private fun createPending(name: String, mime: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/$DOWNLOAD_SUBDIR")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        return resolver.insert(
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            values,
        ) ?: throw DownloadFailure("Couldn't create the file in Downloads")
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    private suspend fun copyWithProgress(id: Long, input: InputStream, uri: Uri, total: Long): Long {
        val out = resolver.openOutputStream(uri) ?: throw DownloadFailure("Couldn't write to Downloads")
        var received = 0L
        var lastPublish = 0L
        _progress.update { it + (id to DownloadProgress(0, total)) }
        out.use { sink ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buffer)
                if (n < 0) break
                sink.write(buffer, 0, n)
                received += n
                val now = System.currentTimeMillis()
                if (now - lastPublish >= PROGRESS_INTERVAL_MS) {
                    lastPublish = now
                    _progress.update { it + (id to DownloadProgress(received, total)) }
                }
            }
        }
        _progress.update { it + (id to DownloadProgress(received, total)) }
        return received
    }

    private fun publish(uri: Uri) {
        val values = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
        resolver.update(uri, values, null, null)
    }

    private fun deleteQuietly(uri: String) {
        runCatching { resolver.delete(Uri.parse(uri), null, null) }
            .onFailure { Log.w(LOG_TAG, "couldn't delete partial download $uri", it) }
    }

    companion object {
        @Volatile private var instance: DownloadManager? = null

        fun get(context: Context): DownloadManager =
            instance ?: synchronized(this) {
                instance ?: DownloadManager(context).also { instance = it }
            }
    }
}

/** `text/html; charset=utf-8` → `text/html`; blank → null. */
private fun normalizeMime(raw: String?): String? =
    raw?.substringBefore(';')?.trim()?.lowercase()?.ifBlank { null }

private fun extensionForMime(mime: String): String? =
    MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)

/** What [DownloadManager.open] found behind a completed row's URI. */
internal enum class DownloadFileState {
    /** The item exists and isn't trashed. */
    PRESENT,

    /** The item is in the system trash — restorable, so not deleted. */
    TRASHED,

    /** No such item any more: deleted for good. */
    GONE,

    /** The query failed; nothing is known, so nothing is dropped. */
    UNKNOWN,
}

/** Classify an item the query found, by its IS_TRASHED flag. */
internal fun downloadFileState(isTrashed: Boolean): DownloadFileState =
    if (isTrashed) DownloadFileState.TRASHED else DownloadFileState.PRESENT

/**
 * Where [uri]'s MediaStore item stands. Blocking. The query opts in to
 * trashed items — the default excludes them, which would make a file the
 * user can still restore look deleted.
 */
internal fun queryDownloadFileState(resolver: ContentResolver, uri: Uri): DownloadFileState =
    runCatching {
        val args = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
        }
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_TRASHED), args, null).use { c ->
            when {
                c == null -> DownloadFileState.UNKNOWN
                !c.moveToFirst() -> DownloadFileState.GONE
                else -> downloadFileState(c.getInt(0) != 0)
            }
        }
    }.getOrDefault(DownloadFileState.UNKNOWN)
