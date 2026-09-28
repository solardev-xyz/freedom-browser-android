package baby.freedom.mobile.browser

import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.util.Log
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.WebSettings
import androidx.room.Room
import baby.freedom.mobile.data.AppDatabase
import baby.freedom.mobile.data.DownloadDao
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

/**
 * Whether a dweb attempt that failed before the response headers (a
 * reset, a read timeout) gets another try. A reset is quick and cold
 * nodes do it, so it retries like a 404. A read timeout already cost a
 * whole `readTimeout` (60 s): retrying those would stretch the ~45 s
 * budget to ~10 min, so the first one ends the download
 * ("Gateway didn't answer").
 */
internal fun dwebHeaderFailureRetries(e: IOException): Boolean = e !is java.net.SocketTimeoutException

private const val MAX_REDIRECTS = 8

/**
 * Free space a download never takes: it fails as "Not enough storage"
 * rather than write the device full (a server can stream forever).
 */
internal const val STORAGE_FLOOR_BYTES = 256L * 1024 * 1024

/** How often, in bytes written, a running download re-checks free space. */
private const val STORAGE_CHECK_EVERY_BYTES = 16L * 1024 * 1024

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

    /**
     * Private tabs' downloads (#86): the same table, in a database that
     * only exists in memory, emptied when the private session ends
     * ([endPrivateSession]). Their ids are negative ([privateSessions]),
     * so one id space covers both lists and [daoFor] tells them apart.
     */
    private val memoryDao = Room.inMemoryDatabaseBuilder(appContext, AppDatabase::class.java)
        .build()
        .downloads()
    private val privateSessions = PrivateDownloadSessions()

    /**
     * Held while a private row is inserted and while an ended session's
     * rows are cleared, so an insert that got its id before the session
     * ended is always cleared with it rather than landing after.
     */
    private val privateRows = Mutex()

    private fun daoFor(id: Long): DownloadDao = if (id < 0) memoryDao else dao
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    /** Running jobs and what they block on; see [DownloadCancellation]. */
    private val cancellation = DownloadCancellation()

    private val _progress = MutableStateFlow<Map<Long, DownloadProgress>>(emptyMap())
    /** Running downloads' byte counts, keyed by [DownloadEntry.id]. */
    val progress: StateFlow<Map<Long, DownloadProgress>> = _progress.asStateFlow()

    private val _events = MutableSharedFlow<DownloadEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<DownloadEvent> = _events.asSharedFlow()

    private val offerQueue = DownloadOffers()
    /**
     * Downloads pages asked for, waiting for the user's yes
     * ([accept] / [decline]); nothing is fetched or written before it.
     */
    val offers: StateFlow<List<DownloadOffer>> = offerQueue.pending

    /**
     * Per tab, page downloads refused because it already had
     * [MAX_PENDING_OFFERS] waiting ([DownloadOffers.dropped]).
     */
    val droppedOffers: StateFlow<Map<Long, Int>> = offerQueue.dropped

    /** Download history, newest first. */
    val downloads: Flow<List<DownloadEntry>> = combine(dao.all(), memoryDao.all()) { saved, private ->
        (private + saved).sortedByDescending { it.startedAt }
    }

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
     * Offer to download [url] — the arguments of
     * `DownloadListener.onDownloadStart`, plus the tab it came from
     * ([tabId], a [BrowserState.id]) and the page ([pageUrl]; null for
     * a navigation the user started, which has no referrer). Only
     * [pageUrl]'s origin is kept, for a same-origin Referer
     * ([downloadReferer]) and to say who asked.
     *
     * Nothing is fetched yet: the listener fires for script-initiated
     * downloads too, with no tap, so the download waits in [offers]
     * until the user accepts it — unless the tab is blocked (see
     * [DownloadOffers]), when it's dropped unasked.
     */
    fun start(
        tabId: Long,
        private: Boolean = false,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long,
        pageUrl: String?,
    ) {
        val target = classifyDownloadUrl(url, Gateways::isLocalGateway, Gateways::toDisplay)
        val name = downloadFileName(contentDisposition, url, normalizeMime(mimeType), ::extensionForMime)
        val refererOrigin = downloadRefererOrigin(pageUrl)
        // Who asked, as the prompt names them. A page with no usable
        // origin (`data:`, `about:blank`) still asked: it's named by
        // its scheme rather than passed off as the user's own request.
        val requestedBy = downloadRequester(pageUrl, Gateways::toDisplay)
        // A private offer belongs to the session live when it was made:
        // accepted after that session ended, it's dropped ([enqueue]).
        val session = if (private) privateSession() else null
        val queued = offerQueue.offer(tabId, requestedBy, name, target.displayUrl, contentLength.coerceAtLeast(-1)) {
            enqueue(url, userAgent, contentDisposition, mimeType, contentLength, refererOrigin, session)
        }
        if (!queued) Log.i(LOG_TAG, "download offer from tab $tabId dropped (tab blocked or $MAX_PENDING_OFFERS waiting)")
    }

    /** The user wants [DownloadOffer.key]'s file: start it. */
    fun accept(key: Long) = offerQueue.accept(key)

    /** The user doesn't want [DownloadOffer.key]'s file. */
    fun decline(key: Long) = offerQueue.decline(key)

    /** Decline every offer [tabId] has waiting. */
    fun declineAll(tabId: Long) = offerQueue.declineAll(tabId)

    /** The user navigated [tabId] themselves: its pages may offer downloads again. */
    fun allowOffers(tabId: Long) = offerQueue.allow(tabId)

    /** Only [tabIds] are open; offers and blocks of other tabs go. */
    fun retainOfferTabs(tabIds: Set<Long>) = offerQueue.retainTabs(tabIds)

    /**
     * A private download's session (#86): its generation, and the
     * cookie jar it sends — that session's, never a later one's.
     */
    private class PrivateSession(val generation: Long, val cookies: CookieManager?)

    private fun privateSession() = PrivateSession(privateSessions.current(), PrivateProfile.cookieManager())

    /** [session]: the private session a private download belongs to; null for a normal one. */
    private fun enqueue(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long,
        refererOrigin: String?,
        session: PrivateSession?,
    ) {
        val target = classifyDownloadUrl(url, Gateways::isLocalGateway, Gateways::toDisplay)
        val guessedMime = normalizeMime(mimeType)
        val initialName = downloadFileName(contentDisposition, url, guessedMime, ::extensionForMime)
        scope.launch {
            staleSweep.join()
            val row = DownloadEntry(
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
                )
            val id = if (session != null) {
                privateRows.withLock {
                    // Its session ended meanwhile: nothing to list it in.
                    val id = privateSessions.allocate(session.generation) ?: return@launch
                    memoryDao.insert(row.copy(id = id))
                }
            } else {
                dao.insert(row)
            }
            _events.tryEmit(DownloadEvent.Started(id, initialName))
            val cookies = if (session != null) {
                session.cookies
            } else {
                runCatching { CookieManager.getInstance() }.getOrNull()
            }
            val job = scope.launch(start = CoroutineStart.LAZY) {
                run(id, target, userAgent, contentDisposition, refererOrigin, cookies)
            }
            val cancelled = !cancellation.register(id, job)
            // Cancelled before it began: run() never starts, so mark the
            // row the way run() would have.
            if (cancelled) {
                job.cancel()
                daoFor(id).get(id)?.let {
                    daoFor(id).update(
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
        // A private row from a session that has ended is already being
        // cleared; retrying it would carry it into the next one.
        if (entry.id < 0 && !privateSessions.isLive(entry.id)) return
        scope.launch { daoFor(entry.id).delete(entry.id) }
        enqueue(
            url = entry.sourceUrl,
            // The app never overrides the WebView's User-Agent, so the
            // default one is what the first attempt sent.
            userAgent = runCatching { WebSettings.getDefaultUserAgent(appContext) }.getOrNull(),
            contentDisposition = null,
            mimeType = entry.mimeType,
            contentLength = -1,
            refererOrigin = entry.refererOrigin,
            session = if (entry.id < 0) privateSession() else null,
        )
    }

    /** The history row for [id], if it still exists. */
    suspend fun entry(id: Long): DownloadEntry? = daoFor(id).get(id)

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
            daoFor(id).delete(id)
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

    /**
     * The last private tab has closed (#86): cancel the private
     * downloads still running (as Chrome does with incognito ones —
     * their partial files are deleted) and forget the private list.
     * Files that finished stay in Downloads: they're the user's.
     *
     * The session ends here and now: offers it made can no longer be
     * started, and only its own rows are cleared — never those of a
     * private session started before the clean-up runs.
     */
    fun endPrivateSession() {
        val ended = privateSessions.end()
        if (ended.isEmpty()) return
        scope.launch {
            privateRows.withLock {
                for (running in memoryDao.withStatus(DownloadStatus.RUNNING)) {
                    if (running.id in ended) cancel(running.id)
                }
                memoryDao.deleteRange(ended.first, ended.last)
            }
        }
    }

    private suspend fun markFileDeleted(id: Long) = withContext(Dispatchers.IO) {
        // Re-read: the caller's copy may be stale (removed, or retried).
        val current = daoFor(id).get(id)?.takeIf { it.status == DownloadStatus.COMPLETED } ?: return@withContext
        daoFor(id).update(
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
        cookies: CookieManager?,
    ) {
        val dao = daoFor(id)
        var entry = dao.get(id) ?: return
        var pending: Uri? = null
        // Set once the file is public: from then on it's the user's
        // file, and nothing here — a late cancel included — deletes it.
        var published = false
        // Everything that can block on the network is tracked as soon as
        // it exists — each connection before connect(), then the body —
        // so [cancel] can close it (see [DownloadCancellation]).
        val job = currentCoroutineContext()[Job]
        val track: (AutoCloseable) -> Unit = { cancellation.track(id, job, it) }
        try {
            val body = openBody(target, userAgent, contentDisposition, refererOrigin, cookies, track)
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
                if (!downloadFitsStorage(allocatableBytes(), src.length.coerceAtLeast(0), STORAGE_FLOOR_BYTES)) {
                    throw DownloadFailure("Not enough storage")
                }
                val uri = insertPendingDownload(resolver, "Download/$DOWNLOAD_SUBDIR", "dl$id", name, mime)
                    ?: throw DownloadFailure("Couldn't create the file in Downloads")
                pending = uri
                entry = entry.copy(
                    fileName = name,
                    mimeType = mime,
                    contentUri = uri.toString(),
                    totalBytes = src.length,
                )
                dao.update(entry)
                val received = copyWithProgress(id, src.stream, uri, src.length)
                // With a Content-Length a short body is caught here, and a
                // chunked body cut off mid-stream fails in read() (the
                // chunk framing is incomplete). A close-delimited body —
                // no length, not chunked, the HTTP/1.0 way — can't be
                // checked: a connection that drops cleanly looks exactly
                // like its end, so such a body is taken as complete. A
                // reset still fails; the storage floor bounds the rest.
                if (src.length >= 0 && received < src.length) {
                    throw IOException("Connection closed early")
                }
                if (!publishPendingDownload(resolver, uri, name)) {
                    throw DownloadFailure("Couldn't save the file in Downloads")
                }
                pending = null
                published = true
                afterPublishForTest?.invoke(id)
                withContext(NonCancellable) {
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
                    dao.update(entry)
                    _events.tryEmit(DownloadEvent.Completed(id, finalName))
                }
            }
        } catch (t: Throwable) {
            // Finished: a cancel that lands now (withContext rethrows it
            // on the way out) is too late to undo anything.
            if (published) {
                if (t is CancellationException) throw t
                Log.w(LOG_TAG, "download $id: after publishing", t)
                return
            }
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
        cookies: CookieManager?,
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
        is DownloadTarget.Web -> fetchWeb(target.url, userAgent, refererOrigin, cookies, track)
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
            // The header wait is part of the attempt: a cold node that
            // accepts the connection and then resets gets the rest of
            // the retry budget, like a 404. A read timeout doesn't: that
            // attempt alone already waited a full [readTimeout], twice
            // the node's own retrieval timeout, and nine of those would
            // hold the row at 0 B for ~10 min (see [dwebHeaderFailureRetries]).
            // Redirects are followed hop by hop through TorRouting (each
            // failed hop is disconnected there).
            val conn = try {
                TorRouting.openFollowingRedirects(URL(gatewayUrl)) {
                    track(AutoCloseable { disconnect() })
                    connectTimeout = 5_000
                    readTimeout = 60_000
                    setRequestProperty("Accept-Encoding", "identity")
                    setRequestProperty("Swarm-Chunk-Retrieval-Timeout", "30s")
                    setRequestProperty("Swarm-Redundancy-Strategy", "3")
                    setRequestProperty("Swarm-Redundancy-Fallback-Mode", "true")
                }
            } catch (_: java.net.ConnectException) {
                throw DownloadFailure("Node not running")
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                Log.i(LOG_TAG, "dweb download attempt failed: $gatewayUrl", e)
                if (!dwebHeaderFailureRetries(e)) break
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
        cookies: CookieManager?,
        track: (AutoCloseable) -> Unit,
    ): Body {
        var current = url
        repeat(MAX_REDIRECTS + 1) {
            currentCoroutineContext().ensureActive()
            val conn = (TorRouting.openConnection(URL(current)) as HttpURLConnection).apply {
                track(AutoCloseable { disconnect() })
                connectTimeout = 15_000
                readTimeout = 60_000
                instanceFollowRedirects = false
                setRequestProperty("Accept-Encoding", "identity")
                userAgent?.takeIf { it.isNotBlank() }?.let { setRequestProperty("User-Agent", it) }
                runCatching { cookies?.getCookie(current) }.getOrNull()
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

    /**
     * Bytes the shared-storage volume can still take (cache the system
     * may evict counted in); null when it can't be told, which doesn't
     * block the download.
     */
    private fun allocatableBytes(): Long? = runCatching {
        val storage = appContext.getSystemService(StorageManager::class.java)
        storage.getAllocatableBytes(storage.getUuidForPath(Environment.getExternalStorageDirectory()))
    }.getOrNull()

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    private suspend fun copyWithProgress(id: Long, input: InputStream, uri: Uri, total: Long): Long {
        val out = resolver.openOutputStream(uri) ?: throw DownloadFailure("Couldn't write to Downloads")
        var received = 0L
        var lastPublish = 0L
        var nextStorageCheck = STORAGE_CHECK_EVERY_BYTES
        _progress.update { it + (id to DownloadProgress(0, total)) }
        out.use { sink ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buffer)
                if (n < 0) break
                sink.write(buffer, 0, n)
                received += n
                if (received >= nextStorageCheck) {
                    nextStorageCheck = received + STORAGE_CHECK_EVERY_BYTES
                    val stillToWrite = if (total >= 0) (total - received).coerceAtLeast(0) else 0
                    if (!downloadFitsStorage(allocatableBytes(), stillToWrite, STORAGE_FLOOR_BYTES)) {
                        throw DownloadFailure("Not enough storage")
                    }
                }
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

    private fun deleteQuietly(uri: String) {
        runCatching { resolver.delete(Uri.parse(uri), null, null) }
            .onFailure { Log.w(LOG_TAG, "couldn't delete partial download $uri", it) }
    }

    /**
     * Test hook: runs right after a download's file is published, with
     * no suspension point before the completion write — the spot a late
     * cancel can land in.
     */
    @Volatile internal var afterPublishForTest: ((Long) -> Unit)? = null

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
 * trashed items and reads IS_TRASHED, so a file the user can still restore
 * is told apart from a present one and never mistaken for deleted. Whether
 * the default query hides trashed rows varies by OS version and query
 * shape: on API 36 a single-item URI query returns them anyway (only a
 * collection query hides them), so opt in explicitly rather than rely on
 * either behaviour.
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

/**
 * Does a download leave at least [floor] bytes free? [allocatable] is
 * what the volume can still take (null: unknown, which lets it through)
 * and [stillToWrite] what the download is yet to write (0 when unknown —
 * then the running re-checks catch it).
 */
internal fun downloadFitsStorage(allocatable: Long?, stillToWrite: Long, floor: Long): Boolean =
    allocatable == null || allocatable - stillToWrite >= floor

/**
 * The name a download's pending item is inserted under: unique per
 * download ([tag]), so it never shares a file with another one.
 * MediaProvider keeps a pending item at `.pending-<expiry seconds>-<name>`
 * and settles name collisions only on publish, so two pending items of
 * the same name inserted in the same second would get the *same* file
 * and interleave their bytes. The wanted name is set on publish, where
 * MediaStore does its "x (1).pdf" collision handling.
 */
internal fun pendingDownloadName(tag: String, name: String): String = "$tag-$name"

/** Insert [name]'s pending item under [relativePath]. Blocking. */
internal fun insertPendingDownload(
    resolver: ContentResolver,
    relativePath: String,
    tag: String,
    name: String,
    mime: String,
): Uri? {
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, pendingDownloadName(tag, name))
        put(MediaStore.Downloads.MIME_TYPE, mime)
        put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
        put(MediaStore.Downloads.IS_PENDING, 1)
    }
    return resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
}

/**
 * Make [uri] public under [name] (or the "name (1)" MediaStore picks if
 * it's taken). Blocking. False when MediaStore didn't take the update.
 */
internal fun publishPendingDownload(resolver: ContentResolver, uri: Uri, name: String): Boolean {
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, name)
        put(MediaStore.Downloads.IS_PENDING, 0)
    }
    return resolver.update(uri, values, null, null) > 0
}
