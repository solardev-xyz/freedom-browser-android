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
import baby.freedom.mobile.R
import baby.freedom.mobile.data.AppDatabase
import baby.freedom.mobile.data.DownloadDao
import baby.freedom.mobile.data.DownloadEntry
import baby.freedom.mobile.data.DownloadStatus
import baby.freedom.mobile.ens.EnsNormalize
import baby.freedom.mobile.l10n.Strings
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

private const val LOG_TAG = "Downloads"

/** Folder under the shared Downloads collection that Freedom writes to. */
private const val DOWNLOAD_SUBDIR = "Freedom"

/** Where partial files live, under `noBackupFilesDir` (#265). */
private const val PARTIAL_DIR = "downloads"
private const val PARTIAL_SUFFIX = ".part"

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
 * Free space a download never takes: it stops with "Not enough storage"
 * rather than write the device full (a server can stream forever) —
 * failing, or paused if its partial file can be gone on from (#265).
 */
internal const val STORAGE_FLOOR_BYTES = 256L * 1024 * 1024

/** How often, in bytes written, a running download re-checks free space. */
private const val STORAGE_CHECK_EVERY_BYTES = 16L * 1024 * 1024

/**
 * Live byte counts of a running download. [total] is -1 when unknown.
 * [saving]: every byte is in, and the file is being copied into
 * Downloads (#265).
 */
data class DownloadProgress(val received: Long, val total: Long, val saving: Boolean = false)

/** One-shot notices for the browser chrome's snackbar. */
sealed class DownloadEvent {
    /** The download's [DownloadEntry.id]. */
    abstract val id: Long
    abstract val fileName: String
    data class Started(override val id: Long, override val fileName: String) : DownloadEvent()
    data class Completed(override val id: Long, override val fileName: String) : DownloadEvent()
    data class Failed(override val id: Long, override val fileName: String, val reason: String) : DownloadEvent()
}

/** A `data:` download up to this long has its size told on the UI thread ([DownloadManager.start]); about a millisecond's scan. */
private const val DATA_URI_INLINE_SCAN_CHARS = 1_000_000

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
 * - **`data:`**: decoded in-process, as it's written ([openDataUri]).
 * - **`blob:`**: read inside the frame that made it, a chunk at a time,
 *   through the tab's [BlobDownloads] channel. Such a row keeps no source
 *   URL — nothing can read the blob once its page is gone — so it can't
 *   be paused or retried.
 *
 * Bytes stream into a partial file in the app's own storage
 * (`no_backup/downloads/<id>.part`, #265), and only a complete file is
 * copied into a `MediaStore.Downloads` entry (`Download/Freedom/…`) and
 * published — no storage permission is needed on API 29+, and a
 * cancelled or failed download leaves nothing behind. With *Ask where
 * to save each file* on (#322), the complete file is written through the
 * document the user picked instead ([DownloadEntry.saveTo], see
 * [DownloadSaveTo]); everything before that step is the same. Every download
 * gets a row in the `downloads` table (the history screen); running
 * byte counts live in [progress] only.
 *
 * Pause and resume (#265): [pause] stops a download and keeps its
 * partial file; [resume] asks for the rest with `Range`, guarded by
 * `If-Range` against the validator the first response gave
 * ([downloadValidator]). A server that ignores the range (or a file that
 * changed) answers with the whole file, which is written from the start
 * with a note. A lost connection pauses a download instead of failing
 * it when its server serves ranges ([DownloadEntry.resumable]). Web
 * downloads can always be paused; dweb ones only if the gateway serves
 * ranges ([canPause]).
 *
 * Lifetime: process-scoped, like [baby.freedom.mobile.data.BrowsingRepository].
 * Downloads survive the Activity; a process death mid-download is
 * swept up on the next start: a resumable row becomes paused with its
 * partial file, any other is marked interrupted and its partial deleted.
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

    /**
     * Held while a row changes hands between states from outside its
     * job (#265): a row going RUNNING together with its job's
     * registration ([enqueue], [resume]), and [pause] / [cancel] /
     * [remove] reading a row to decide what to do. So a pause never
     * sees a RUNNING row whose job isn't registered yet, and a resume
     * never races a cancel of the same paused row.
     */
    private val transitions = Mutex()

    /** Partial files (#265): app storage, never backed up, never visible to other apps. */
    private val partialDir = File(appContext.noBackupFilesDir, PARTIAL_DIR)

    private fun partialFile(id: Long) = File(partialDir, "$id$PARTIAL_SUFFIX")

    private fun deletePartial(id: Long) {
        val file = partialFile(id)
        if (file.exists() && !file.delete()) Log.w(LOG_TAG, "couldn't delete partial download $id")
    }

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
     * died mid-download: nothing is fetching them any more. One whose
     * server serves ranges and that left a partial file becomes paused
     * (#265); any other is marked interrupted and its partial deleted.
     * A pending Downloads item (a death while saving) goes either way.
     * Partial files no paused row owns go too — every private one
     * among them, the private list having died with the process.
     * [start] and [resume] wait for this so the sweep can't catch a
     * download of this process; [pause], [cancel] and [remove] so they
     * read what the sweep made of a dead process's row (a Pause or
     * Cancel tapped in the notification it left behind cold-starts the
     * process), never the RUNNING it was.
     */
    private val staleSweep: Job = scope.launch {
        sweepGateForTest?.await()
        for (stale in dao.withStatus(DownloadStatus.RUNNING)) {
            val saveTo = stale.saveTo
            if (saveTo == null) {
                stale.contentUri?.let { deleteQuietly(it) }
            } else if (stale.contentUri != null) {
                // Died copying into the picked document (#322): what it
                // holds is part of the file. The document is the user's
                // pick, so it's emptied, not deleted — a resume writes it
                // again, and a failure's discard deletes it if the picker
                // created it (never an existing file picked to replace).
                DownloadSaveTo.truncate(resolver, Uri.parse(saveTo))
            }
            val partial = partialFile(stale.id)
            val kept = if (partial.exists()) partial.length() else 0L
            if (stale.resumable && kept > 0) {
                dao.update(
                    stale.copy(
                        status = DownloadStatus.PAUSED,
                        contentUri = null,
                        receivedBytes = kept,
                        note = DOWNLOAD_INTERRUPTED_NOTE,
                    ),
                )
            } else {
                deletePartial(stale.id)
                saveTo?.let { DownloadSaveTo.discard(appContext, Uri.parse(it), stale.saveToCreated) }
                dao.update(
                    stale.copy(
                        status = DownloadStatus.FAILED,
                        contentUri = null,
                        error = DOWNLOAD_INTERRUPTED_NOTE,
                        note = null,
                        finishedAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
        // A row from before names were cleaned of bidi/format characters
        // keeps the name it was listed under — a finished one in the
        // downloads list, a paused one also the name it is saved under on
        // resume — so every such row is cleaned here, before either is
        // read. A Save-as row (saveTo set) is left alone: its name is the
        // picked document's own (queryDisplayName), the one the user gave
        // it, and the list should keep matching what the document is
        // really called.
        for (row in dao.all().first()) {
            if (row.saveTo != null) continue
            val clean = cleanStoredFileName(row.fileName)
            if (clean != row.fileName) dao.update(row.copy(fileName = clean))
        }
        val pausedRows = dao.withStatus(DownloadStatus.PAUSED)
        val paused = pausedRows.mapTo(HashSet()) { it.id }
        for (file in partialDir.listFiles().orEmpty()) {
            val id = file.name.removeSuffix(PARTIAL_SUFFIX).toLongOrNull()
            if (id == null || id !in paused) file.delete()
        }
        // Grants on picked documents (#322) only paused downloads still
        // need: any other was left by a download that ended while the
        // process died before it could give its grant back.
        DownloadSaveTo.sweep(appContext, keep = pausedRows.mapNotNullTo(HashSet()) { it.saveTo })
    }

    init {
        DownloadNotifications(appContext, scope, downloads, progress, ::canPause).start()
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
    internal fun start(
        tabId: Long,
        private: Boolean = false,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long,
        pageUrl: String?,
        /**
         * A `blob:` download's file, as the page that holds it described
         * it ([BlobDownloads.prepare]) — its name, type and size stand in
         * for what `DownloadListener` said (which for a blob is nothing
         * but its type). Released if the offer is declined or dropped.
         */
        blob: BlobSource? = null,
    ) {
        val target = classifyDownloadUrl(url, Gateways::isLocalGateway, Gateways::toDisplay)
        @Suppress("NAME_SHADOWING")
        val contentDisposition = blob?.let { blobContentDisposition(it.name) } ?: contentDisposition
        @Suppress("NAME_SHADOWING")
        val mimeType = if (blob != null) blobMimeType(blob.mimeType, blob.name, ::mimeForExtension) else mimeType
        val name = downloadFileName(contentDisposition, url, normalizeMime(mimeType), ::extensionForMime)
        val refererOrigin = downloadRefererOrigin(pageUrl)
        // Who asked, as the prompt names them. A page with no usable
        // origin (`data:`, `about:blank`) still asked: it's named by
        // its scheme rather than passed off as the user's own request.
        val requestedBy = downloadRequester(pageUrl, Gateways::toDisplay)
        // A private offer belongs to the session live when it was made:
        // accepted after that session ended, it's dropped ([enqueue]).
        val session = if (private) privateSession() else null
        fun offer(contentLength: Long) {
            val queued = offerQueue.offer(
                tabId, requestedBy, name, target.displayUrl, contentLength.coerceAtLeast(-1), private,
                mimeType = saveAsMimeType(normalizeMime(mimeType)),
                discard = { blob?.release() },
            ) { saveTo ->
                enqueue(url, userAgent, contentDisposition, mimeType, contentLength, refererOrigin, session, saveTo, blob)
            }
            if (!queued) blob?.release()
            if (!queued) Log.i(LOG_TAG, "download offer from tab $tabId dropped (tab blocked or closed, or $MAX_PENDING_OFFERS waiting)")
        }
        when {
            blob != null -> offer(if (blob.failure == null) blob.size else contentLength)
            // WebView says 0 for every data: URI; its payload tells. A
            // short one is told at once; a long one can be tens of
            // millions of characters to scan, so not on the UI thread.
            target is DownloadTarget.Data && contentLength <= 0 && url.length <= DATA_URI_INLINE_SCAN_CHARS ->
                offer(openDataUri(url)?.length ?: -1)
            target is DownloadTarget.Data && contentLength <= 0 ->
                scope.launch(Dispatchers.Default) {
                    // Unwatched: a failure to tell only costs the size.
                    val length = try { openDataUri(url)?.length } catch (_: Throwable) { null }
                    offer(length ?: -1)
                }
            else -> offer(contentLength)
        }
    }

    /**
     * The user wants [DownloadOffer.key]'s file: start it — saved as
     * [saveTo], the document they picked in the *Save as* picker (#322),
     * or into Download/Freedom. The picked document's grant is kept from
     * here until the download ends; if the offer went meanwhile (its tab
     * closed while the picker was up), the document the picker created
     * goes again.
     *
     * When no lasting grant can be had on [saveTo], nothing starts: the
     * picker's document goes again (while the activity's grant still
     * lets it), the offer stays up, and [onNoLastingAccess] is called on
     * the main thread — a download there couldn't be written once the
     * activity is gone.
     */
    fun accept(key: Long, saveTo: Uri? = null, onNoLastingAccess: () -> Unit = {}) {
        if (saveTo == null) {
            offerQueue.accept(key)
            return
        }
        scope.launch {
            val picked = holdOrRefuse(saveTo, onNoLastingAccess) ?: return@launch
            if (!offerQueue.accept(key, picked)) DownloadSaveTo.discard(appContext, saveTo, picked.created)
        }
    }

    /**
     * [DownloadSaveTo.hold] on [saveTo]; null — with the document given
     * up and [onNoLastingAccess] told — when Freedom couldn't keep
     * access to it past the activity.
     */
    private suspend fun holdOrRefuse(saveTo: Uri, onNoLastingAccess: () -> Unit): PickedDocument? {
        val picked = DownloadSaveTo.hold(appContext, saveTo)
        if (picked.lasting) return picked
        DownloadSaveTo.discard(appContext, saveTo, picked.created)
        withContext(Dispatchers.Main) { onNoLastingAccess() }
        return null
    }

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
        /** The document picked to save it as (#322); never for a private download. */
        saveTo: PickedDocument? = null,
        /** A `blob:` download's file, held by its page ([start]). */
        blob: BlobSource? = null,
    ) {
        val target = if (blob != null) {
            DownloadTarget.Blob(url, blob)
        } else {
            classifyDownloadUrl(url, Gateways::isLocalGateway, Gateways::toDisplay)
        }
        val guessedMime = normalizeMime(mimeType)
        scope.launch {
            staleSweep.join()
            val picked = saveTo?.uri?.let(Uri::parse)
            if (picked != null && saveTo != null && session != null) {
                // Private downloads always go to Download/Freedom (#322).
                DownloadSaveTo.discard(appContext, picked, saveTo.created)
            }
            val savesTo = picked?.takeIf { session == null }
            // Listed under the name the user gave it in the picker (or
            // the one the provider settled on).
            val initialName = savesTo?.let(::queryDisplayName)
                ?: downloadFileName(contentDisposition, url, guessedMime, ::extensionForMime)
            val row = DownloadEntry(
                    fileName = initialName,
                    // Spelled the same whenever it is saved (#490 R1-M2).
                    displayUrl = DisplayUrl.settledName(target.displayUrl),
                    // A data: URI *is* the file — possibly megabytes — and
                    // doesn't belong in a history row (Room's cursor window
                    // is 2 MB). A blob: URL means nothing once its page is
                    // gone (and nothing can read it but that page). Blank
                    // means "can't be retried" (and can't be paused).
                    sourceUrl = if (target is DownloadTarget.Data || target is DownloadTarget.Blob) "" else url,
                    mimeType = guessedMime ?: "application/octet-stream",
                    contentUri = null,
                    status = DownloadStatus.RUNNING,
                    totalBytes = contentLength.coerceAtLeast(-1),
                    receivedBytes = 0,
                    error = null,
                    startedAt = System.currentTimeMillis(),
                    finishedAt = null,
                    refererOrigin = refererOrigin.takeIf { target is DownloadTarget.Web },
                    userAgent = userAgent?.takeIf { it.isNotBlank() },
                    saveTo = savesTo?.toString(),
                    saveToCreated = savesTo != null && saveTo?.created == true,
                )
            val cookies = if (session != null) {
                session.cookies
            } else {
                runCatching { CookieManager.getInstance() }.getOrNull()
            }
            transitions.withLock {
                val id = if (session != null) {
                    privateRows.withLock {
                        // Its session ended meanwhile: nothing to list it in.
                        val id = privateSessions.allocate(session.generation) ?: run {
                            blob?.release()
                            return@launch
                        }
                        memoryDao.insert(row.copy(id = id))
                    }
                } else {
                    dao.insert(row)
                }
                _events.tryEmit(DownloadEvent.Started(id, initialName))
                launchJob(id, resuming = false, abandoned = { blob?.release() }) {
                    run(id, target, userAgent, contentDisposition, refererOrigin, cookies, resuming = false)
                }
            }
        }
    }

    /**
     * Start [id]'s (RUNNING) row's job [block]. Called under
     * [transitions]. A stop that got there first — possible only before
     * a new row's insert is seen, as the Cancel button appears with it —
     * leaves the row the way run() would have — and, as run()'s own
     * `finally` would, lets go of what it was to read ([abandoned]: a
     * `blob:` download's file, which its page would otherwise go on
     * holding until its own timer runs out).
     */
    private suspend fun launchJob(
        id: Long,
        resuming: Boolean,
        abandoned: () -> Unit = {},
        block: suspend () -> Unit,
    ) {
        val job = scope.launch(start = CoroutineStart.LAZY) { block() }
        if (cancellation.register(id, job)) {
            job.start()
            return
        }
        val stop = cancellation.stopOf(id)
        job.cancel()
        abandoned()
        val row = daoFor(id).get(id) ?: return
        if (stop != DownloadStop.PAUSE) endSaveTo(row, discard = true)
        daoFor(id).update(
            if (stop == DownloadStop.PAUSE) {
                row.copy(status = DownloadStatus.PAUSED)
            } else {
                if (resuming) deletePartial(id)
                row.copy(status = DownloadStatus.CANCELLED, finishedAt = System.currentTimeMillis())
            },
        )
    }

    /**
     * Can [entry] be paused now? Running, and either a web download (a
     * server that can't resume restarts it, with a note) or one whose
     * server serves ranges — see [downloadCanPause].
     */
    fun canPause(entry: DownloadEntry): Boolean {
        if (entry.status != DownloadStatus.RUNNING || entry.sourceUrl.isBlank()) return false
        val target = classifyDownloadUrl(entry.sourceUrl, Gateways::isLocalGateway, Gateways::toDisplay)
        return downloadCanPause(
            isWeb = target is DownloadTarget.Web,
            isData = target is DownloadTarget.Data,
            resumable = entry.resumable,
        )
    }

    /** Pause a running download (#265), keeping its partial file for [resume]. */
    fun pause(id: Long) {
        scope.launch {
            staleSweep.join()
            transitions.withLock {
                val row = daoFor(id).get(id) ?: return@withLock
                if (canPause(row)) {
                    DownloadNotifications.forgetDismissals(appContext, id)
                    cancellation.cancel(id, DownloadStop.PAUSE)
                }
            }
        }
    }

    /**
     * Go on with a paused download (#265), from where its partial file
     * ends. A private one only while its session is live.
     */
    fun resume(id: Long) {
        scope.launch {
            staleSweep.join()
            transitions.withLock {
                val row = daoFor(id).get(id)?.takeIf { it.status == DownloadStatus.PAUSED } ?: return@withLock
                val cookies = if (id < 0) {
                    if (!privateSessions.isLive(id)) return@withLock
                    PrivateProfile.cookieManager()
                } else {
                    runCatching { CookieManager.getInstance() }.getOrNull()
                }
                val target = classifyDownloadUrl(row.sourceUrl, Gateways::isLocalGateway, Gateways::toDisplay)
                DownloadNotifications.forgetDismissals(appContext, id)
                daoFor(id).update(row.copy(status = DownloadStatus.RUNNING))
                // A pause aimed at its last run (a second Pause tap that
                // landed after it had paused) must not stop this one; a
                // Cancel tapped just before (its mark set, its locked
                // follow-up still waiting) must.
                cancellation.forgetPause(id)
                launchJob(id, resuming = true) {
                    run(
                        id = id,
                        target = target,
                        userAgent = userAgentOf(row),
                        contentDisposition = null,
                        refererOrigin = row.refererOrigin,
                        cookies = cookies,
                        resuming = true,
                    )
                }
            }
        }
    }

    /**
     * The User-Agent [entry]'s first request sent — a desktop-site tab's
     * (#180) is not the default — or, on a row from before it was kept,
     * the WebView's default one.
     */
    private fun userAgentOf(entry: DownloadEntry): String? =
        entry.userAgent ?: runCatching { WebSettings.getDefaultUserAgent(appContext) }.getOrNull()

    /**
     * Fetch a finished-unsuccessfully download again, as a new entry —
     * saved as [saveTo] when the user picked where (#322).
     */
    fun retry(entry: DownloadEntry, saveTo: Uri? = null, onNoLastingAccess: () -> Unit = {}) {
        if (saveTo == null) {
            retryInto(entry, null)
            return
        }
        // As [accept]: nothing starts into a document Freedom can't keep.
        scope.launch {
            val picked = holdOrRefuse(saveTo, onNoLastingAccess) ?: return@launch
            if (!retryInto(entry, picked)) DownloadSaveTo.discard(appContext, saveTo, picked.created)
        }
    }

    /** [retry], with the document already held; false when nothing was started. */
    private fun retryInto(entry: DownloadEntry, saveTo: PickedDocument?): Boolean {
        // A private row from a session that has ended is already being
        // cleared; retrying it would carry it into the next one.
        if (entry.sourceUrl.isBlank() || entry.id < 0 && !privateSessions.isLive(entry.id)) return false
        scope.launch { daoFor(entry.id).delete(entry.id) }
        enqueue(
            url = entry.sourceUrl,
            userAgent = userAgentOf(entry),
            contentDisposition = null,
            mimeType = entry.mimeType,
            contentLength = -1,
            refererOrigin = entry.refererOrigin,
            session = if (entry.id < 0) privateSession() else null,
            saveTo = saveTo,
        )
        return true
    }

    /**
     * A document picked in the *Save as* picker (#322) that nothing will
     * save into after all (the row it was for is gone): delete it again.
     */
    fun abandonSaveTo(uri: Uri) {
        scope.launch {
            // Looked at first, as a held one is: an existing file the
            // user picked to replace stays.
            DownloadSaveTo.discard(appContext, uri, DownloadSaveTo.hold(appContext, uri).created)
        }
    }

    /** The history row for [id], if it still exists. */
    suspend fun entry(id: Long): DownloadEntry? = daoFor(id).get(id)

    /** Stop a running or paused download; its partial file is deleted. */
    fun cancel(id: Long) {
        // At once, without the lock: a blocked read must end now. (A
        // dead process's row has no job: this leaves a mark that the
        // locked block below, after the sweep, cancels or forgets.)
        cancellation.cancel(id)
        scope.launch {
            staleSweep.join()
            transitions.withLock {
                val row = daoFor(id).get(id)
                if (row?.status == DownloadStatus.PAUSED) {
                    deletePartial(id)
                    endSaveTo(row, discard = true)
                    daoFor(id).update(
                        row.copy(
                            status = DownloadStatus.CANCELLED,
                            note = null,
                            finishedAt = System.currentTimeMillis(),
                        ),
                    )
                }
                // Not running: no job of this run is left to stop, and a
                // mark must not stop a later resume of the row.
                if (row?.status != DownloadStatus.RUNNING) cancellation.forget(id)
            }
        }
    }

    /**
     * Forget a download. Only the history row goes — a completed file
     * stays in Downloads, where the user put it; a running one is
     * cancelled first, and a paused one's partial file is deleted.
     */
    fun remove(id: Long) {
        cancellation.cancel(id)
        scope.launch {
            // As [cancel]: after the sweep has settled a dead process's row.
            staleSweep.join()
            transitions.withLock {
                val row = daoFor(id).get(id)
                daoFor(id).delete(id)
                // A running job's own cancel deletes its partial; this
                // gets a paused one's — and gives up its picked document.
                deletePartial(id)
                if (row?.status == DownloadStatus.PAUSED) endSaveTo(row, discard = true)
                // A job registered after this finds no row and ends at once;
                // an early-cancel mark has nothing left to guard.
                cancellation.forget(id)
            }
        }
    }

    /**
     * Part of *Delete browsing data*'s *Cookies and site data* (#265): no unfinished download
     * keeps a partial file. Running and paused downloads are cancelled
     * (running ones delete theirs as they stop), and any partial file
     * no running download is writing goes.
     */
    fun discardUnfinished() {
        scope.launch {
            staleSweep.join()
            transitions.withLock {
                val running = HashSet<Long>()
                for (d in listOf(dao, memoryDao)) {
                    for (row in d.withStatus(DownloadStatus.RUNNING)) {
                        running += row.id
                        cancellation.cancel(row.id)
                    }
                    for (row in d.withStatus(DownloadStatus.PAUSED)) {
                        endSaveTo(row, discard = true)
                        d.update(
                            row.copy(
                                status = DownloadStatus.CANCELLED,
                                note = null,
                                finishedAt = System.currentTimeMillis(),
                            ),
                        )
                        cancellation.forget(row.id)
                    }
                }
                for (file in partialDir.listFiles().orEmpty()) {
                    val id = file.name.removeSuffix(PARTIAL_SUFFIX).toLongOrNull()
                    if (id == null || id !in running) file.delete()
                }
            }
        }
    }

    /**
     * Hand a completed download to whichever app opens its type.
     * Returns a user-facing reason when that isn't possible.
     */
    suspend fun open(context: Context, entry: DownloadEntry): String? {
        val uri = entry.contentUri?.let(Uri::parse) ?: return Strings.get(R.string.library_download_file_not_available)
        // A file saved where the user picked (#322) isn't a MediaStore
        // item to check: whether it's still there, and still ours to
        // open, only the provider knows — see the catch below.
        val state = if (entry.saveTo != null) {
            DownloadFileState.UNKNOWN
        } else {
            withContext(Dispatchers.IO) { queryDownloadFileState(resolver, uri) }
        }
        when (state) {
            DownloadFileState.PRESENT, DownloadFileState.UNKNOWN -> Unit
            // In the system trash (restorable for 30 days): keep the row
            // and its URI, so it opens again once the user restores it.
            DownloadFileState.TRASHED -> return Strings.get(R.string.library_download_file_in_trash)
            DownloadFileState.GONE -> {
                // Deleted outside the app: the row is no longer "completed" —
                // mark it failed so it stops offering an open that can't work
                // and offers Retry instead.
                markFileDeleted(entry.id)
                return Strings.get(R.string.library_download_file_was_deleted)
            }
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, entry.mimeType.ifBlank { "*/*" })
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            null
        } catch (_: ActivityNotFoundException) {
            Strings.get(R.string.library_download_no_app_to_open)
        } catch (_: SecurityException) {
            // A picked location's grant (#322) is given back once the
            // download ends, and the picker's own one goes with the
            // activity that got it: the file is the user's, in the
            // folder they chose, but no longer Freedom's to hand out.
            Strings.get(R.string.library_download_open_from_files)
        }
    }

    /**
     * [row]'s download, saving into a picked document (#322), has ended:
     * give its grant back — and, when it ended unfinished ([discard]),
     * the document the picker created with it (never an existing file
     * picked to replace, [DownloadEntry.saveToCreated]). Unless another
     * unfinished download is saving into the same document (the same
     * existing file picked twice): that one still needs it.
     */
    private suspend fun endSaveTo(row: DownloadEntry, discard: Boolean) {
        val saveTo = row.saveTo ?: return
        val shared = listOf(DownloadStatus.RUNNING, DownloadStatus.PAUSED).any { status ->
            dao.withStatus(status).any { it.id != row.id && it.saveTo == saveTo }
        }
        if (shared) return
        val uri = Uri.parse(saveTo)
        if (discard) DownloadSaveTo.discard(appContext, uri, row.saveToCreated) else DownloadSaveTo.release(appContext, uri)
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
            transitions.withLock {
                privateRows.withLock {
                    for (running in memoryDao.withStatus(DownloadStatus.RUNNING)) {
                        // Its own cancel deletes its partial file as it stops.
                        if (running.id in ended) cancellation.cancel(running.id)
                    }
                    // Paused ones (#265) have no job left to do it.
                    for (paused in memoryDao.withStatus(DownloadStatus.PAUSED)) {
                        if (paused.id in ended) deletePartial(paused.id)
                    }
                    for (id in ended) cancellation.forget(id)
                    memoryDao.deleteRange(ended.first, ended.last)
                }
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
                error = DownloadNote.of(R.string.library_download_file_deleted),
            ),
        )
    }

    // ---------------------------------------------------------------

    /**
     * Fetch [id]'s file into its partial file, then publish it. On a
     * [resuming] run the row's name and type stand, and a partial file
     * already there is continued: with `Range` / `If-Range` when the row
     * has a validator, or else started over.
     */
    private suspend fun run(
        id: Long,
        target: DownloadTarget,
        userAgent: String?,
        contentDisposition: String?,
        refererOrigin: String?,
        cookies: CookieManager?,
        resuming: Boolean,
    ) {
        val dao = daoFor(id)
        var entry = dao.get(id) ?: return
        if (resuming) {
            // The startup sweep cleaned every paused row it found but a
            // Save-as one (whose name is the picked document's); this one
            // too, should one have slipped past it.
            if (entry.saveTo == null) {
                val clean = cleanStoredFileName(entry.fileName)
                if (clean != entry.fileName) entry = entry.copy(fileName = clean).also { dao.update(it) }
            }
        }
        val partial = partialFile(id)
        var pending: Uri? = null
        // Set once the file is public: from then on it's the user's
        // file, and nothing here — a late cancel included — deletes it.
        var published = false
        // Still fetching (false once every byte is in the partial file).
        var fetching = true
        // The catch has written the row and let go of what this run held.
        var settled = false
        // Everything that can block on the network is tracked as soon as
        // it exists — each connection before connect(), then the body —
        // so [cancel] can close it (see [DownloadCancellation]).
        val job = currentCoroutineContext()[Job]
        val track: (AutoCloseable) -> Unit = { cancellation.track(id, job, it) }
        try {
            var kept = if (resuming && partial.exists()) partial.length() else 0L
            if (!resuming) partial.delete()
            if (entry.totalBytes in 0 until kept) {
                // Longer than the file it's part of: not to be trusted.
                partial.delete()
                kept = 0
            }
            // Paused (or killed) after every byte was in, while saving:
            // nothing left to fetch.
            val received = if (kept > 0 && kept == entry.totalBytes) {
                kept
            } else {
                // The row is saved (and [entry] follows it) as soon as the
                // headers are in, so a failure while reading the body
                // knows whether the server can resume it.
                fetchInto(entry, partial, kept, target, userAgent, contentDisposition, refererOrigin, cookies, track, resuming) {
                    entry = it
                    afterHeadersForTest?.invoke(id)
                    dao.update(it)
                }
            }
            fetching = false
            afterFetchForTest?.invoke(id)
            // Before anything can throw: a pause landing now writes the
            // row from [entry], and must record the length a body with no
            // Content-Length turned out to have — or the resume can't
            // tell the partial file is whole, and fetches it again.
            entry = entry.copy(totalBytes = received, receivedBytes = received)
            currentCoroutineContext().ensureActive()
            dao.update(entry)
            _progress.update { it + (id to DownloadProgress(received, received, saving = true)) }
            // The copy into Downloads needs room for a second copy of
            // the file until the partial one is deleted. A picked
            // document (#322) needs it on the volume it's on — an SD card
            // or another provider's, not necessarily the primary one —
            // and is asked before `wt` empties an existing file there.
            val picked = entry.saveTo?.let(Uri::parse)
            val room = if (picked != null) {
                pickedRoomForTest?.let { it(picked) } ?: DownloadSaveTo.roomFor(resolver, picked)
            } else {
                allocatableBytes(sharedStorageDir())
            }
            if (!downloadFitsStorage(room, partial.length(), STORAGE_FLOOR_BYTES)) {
                throw notEnoughStorage()
            }
            val uri = if (picked != null) {
                // Where the user picked (#322). Recorded before the first
                // byte goes in, as a pending item is: a process that dies
                // copying leaves the startup sweep a document to empty.
                entry = entry.copy(contentUri = picked.toString())
                dao.update(entry)
                copyToDownloads(partial, picked, intoPicked = true)
                picked
            } else {
                val uri = insertPendingDownload(resolver, "Download/$DOWNLOAD_SUBDIR", "dl$id", entry.fileName, entry.mimeType)
                    ?: throw DownloadFailure(DownloadNote.of(R.string.library_download_create_file_failed))
                pending = uri
                entry = entry.copy(contentUri = uri.toString())
                dao.update(entry)
                copyToDownloads(partial, uri)
                if (!publishPendingDownload(resolver, uri, entry.fileName)) {
                    throw DownloadFailure(DownloadNote.of(R.string.library_download_save_file_failed))
                }
                pending = null
                uri
            }
            published = true
            afterPublishForTest?.invoke(id)
            withContext(NonCancellable) {
                partial.delete()
                // MediaStore settles a name collision ("x (1).pdf") when
                // the item stops being pending; show the name it chose.
                val finalName = queryDisplayName(uri) ?: entry.fileName
                entry = entry.copy(
                    fileName = finalName,
                    status = DownloadStatus.COMPLETED,
                    totalBytes = received,
                    receivedBytes = received,
                    note = null,
                    finishedAt = System.currentTimeMillis(),
                )
                // Done with the picked document (#322): its grant goes back.
                endSaveTo(entry, discard = false)
                dao.update(entry)
                _events.tryEmit(DownloadEvent.Completed(id, finalName))
            }
        } catch (t: Throwable) {
            // Finished: a cancel that lands now (withContext rethrows it
            // on the way out) is too late to undo anything.
            if (published) {
                if (t is CancellationException) throw t
                Log.w(LOG_TAG, "download $id: after publishing", t)
                return
            }
            // Settled under [transitions]: a Cancel reads the row there,
            // so it either finds it still RUNNING — and its mark, set
            // before, is seen here — or finds the state written here. And
            // a Resume, which needs a PAUSED row, can't start the next run
            // before this one has let go of its connection and progress.
            val reason = withContext(NonCancellable) {
                transitions.withLock { settleFailure(id, entry, t, job, resuming, fetching, partial, pending) }
            }
            settled = true
            if (reason != null) _events.tryEmit(DownloadEvent.Failed(id, entry.fileName, reason))
            if (t is CancellationException) throw t
        } finally {
            // Read or not, the page can let go of a blob: download's file.
            (target as? DownloadTarget.Blob)?.source?.release()
            if (!settled) {
                cancellation.release(id, job)
                _progress.update { it - id }
            }
        }
    }

    /**
     * Write the row of [id]'s run that ended in [t] before publishing:
     * CANCELLED, PAUSED or FAILED. Called under [transitions]. Returns
     * the failure reason to announce, if it failed.
     */
    private suspend fun settleFailure(
        id: Long,
        entry: DownloadEntry,
        t: Throwable,
        job: Job?,
        resuming: Boolean,
        fetching: Boolean,
        partial: File,
        pending: Uri?,
    ): String? {
        val dao = daoFor(id)
        // [cancel] closes the socket under a blocked read, so a stopped
        // download usually surfaces as an IOException, not a
        // CancellationException — the job's state (and a stop mark set
        // since the failure) is the truth.
        val mark = cancellation.stopOf(id)
        val stopped = t is CancellationException || job?.isActive == false || mark != null
        val stop = if (stopped) mark ?: DownloadStop.CANCEL else null
        // A network failure part-way through a download its server can
        // resume pauses it rather than failing it (#265) — and so does
        // any failure to reach the file (a stopped node, a 503) of a run
        // the user resumed: its partial file is theirs to go on with.
        val unreachable = t is DownloadFailure && t.retriable || t is IOException && t !is DownloadFailure
        // Running out of room pauses it too, while there's a partial file
        // to go on from: one a range can continue, or — past the fetch,
        // copying into Downloads — the whole file.
        val noSpace = t is DownloadFailure && t.noSpace
        val canGoOn = partial.length() > 0 &&
            (!fetching || (entry.resumable || resuming) && entry.validator != null)
        val lost = !stopped && canGoOn && (fetching && unreachable || noSpace)
        val pause = stop == DownloadStop.PAUSE || lost
        if (!stopped) Log.w(LOG_TAG, "download $id ${if (pause) "paused" else "failed"}", t)
        // Stored as a DownloadNote, so the row follows a language change;
        // the event gets it resolved.
        val storedReason = when {
            stopped || pause -> null
            t is DownloadFailure -> t.stored
            t is IOException -> t.message?.takeIf { it.isNotBlank() }?.let { DownloadNote.of(R.string.library_download_network_error_detail, it) }
                ?: DownloadNote.of(R.string.library_download_network_error)
            else -> DownloadNote.of(R.string.library_download_failed)
        }
        val reason = DownloadNote.shown(storedReason)
        pending?.let { deleteQuietly(it.toString()) }
        // A copy into the picked document (#322) that didn't finish left
        // part of the file in it: empty it — a resume writes it afresh,
        // and an end deletes it if the picker created it
        // ([DownloadSaveTo.discard]). An existing file picked to replace
        // stays, emptied: its old content went when the copy started.
        val saveTo = entry.saveTo
        if (saveTo != null && entry.contentUri == saveTo) DownloadSaveTo.truncate(resolver, Uri.parse(saveTo))
        val received = _progress.value[id]?.received ?: entry.receivedBytes
        cancellation.release(id, job)
        _progress.update { it - id }
        if (pause) {
            // Without a validator a resume starts over anyway: don't hold
            // on to bytes it can't use. (A file whose bytes are all in is
            // only saved on resume, so it stays.)
            if (entry.validator == null && fetching) partial.delete()
            dao.update(
                entry.copy(
                    status = DownloadStatus.PAUSED,
                    contentUri = null,
                    // What the partial file holds: nothing, once deleted.
                    receivedBytes = if (partial.exists()) partial.length() else 0L,
                    note = when {
                        !lost -> null
                        t is DownloadFailure -> t.stored
                        else -> DOWNLOAD_CONNECTION_LOST_NOTE
                    },
                    error = null,
                ),
            )
        } else {
            partial.delete()
            // Gone before the row says it ended, as its partial file is.
            endSaveTo(entry, discard = true)
            dao.update(
                entry.copy(
                    status = if (stopped) DownloadStatus.CANCELLED else DownloadStatus.FAILED,
                    contentUri = null,
                    receivedBytes = received,
                    error = storedReason,
                    note = null,
                    finishedAt = System.currentTimeMillis(),
                ),
            )
        }
        return reason
    }

    /**
     * Fetch the rest of [row]'s file into [partial], which holds [kept]
     * bytes of it already. [save] is handed the row once the response's
     * headers have been read (its name, size, validator, note); returns
     * the file's length. On a [resuming] run the row keeps its name and
     * type.
     */
    private suspend fun fetchInto(
        row: DownloadEntry,
        partial: File,
        kept: Long,
        target: DownloadTarget,
        userAgent: String?,
        contentDisposition: String?,
        refererOrigin: String?,
        cookies: CookieManager?,
        track: (AutoCloseable) -> Unit,
        resuming: Boolean,
        save: suspend (DownloadEntry) -> Unit,
    ): Long {
        val id = row.id
        var entry = row
        // Only a validator makes a range safe to ask for.
        var offset = if (entry.validator != null) kept else 0L
        var src = openBody(target, userAgent, contentDisposition, refererOrigin, cookies, track,
            downloadResumeHeaders(offset, entry.validator))
        var answer = resumeAnswer(src.status, offset, src.contentRange, src.length)
        if (answer is ResumeAnswer.AskWhole) {
            // A range it can't use (416, or some other range): the whole file, then.
            src.close()
            offset = 0
            src = openBody(target, userAgent, contentDisposition, refererOrigin, cookies, track, emptyMap())
            answer = resumeAnswer(src.status, 0, src.contentRange, src.length)
            if (answer !is ResumeAnswer.FromStart) {
                src.close()
                throw DownloadFailure(DownloadNote.of(R.string.library_download_partial_file))
            }
        }
        src.use {
            track(src)
            currentCoroutineContext().ensureActive()
            val fromStart = answer is ResumeAnswer.FromStart
            val startAt = if (fromStart) 0L else offset
            val total = if (answer is ResumeAnswer.Continue) answer.total else src.length
            if (fromStart) {
                // A whole response carries the validator a later resume
                // checks against, and — on a first run — names the file.
                // A resumed row keeps the name it has been listed under,
                // and so does one saved where the user picked (#322): the
                // name is the one they gave it.
                val validator = downloadValidator(src.etag, src.lastModified, src.date)
                if (!resuming && entry.saveTo != null) {
                    entry = entry.copy(mimeType = src.mimeType ?: entry.mimeType)
                } else if (!resuming) {
                    val mime = src.mimeType ?: entry.mimeType
                    entry = entry.copy(
                        mimeType = mime,
                        fileName = downloadFileName(
                            contentDisposition = src.contentDisposition ?: contentDisposition,
                            url = src.nameUrl,
                            mimeType = mime,
                            extensionForMime = ::extensionForMime,
                        ),
                    )
                }
                entry = entry.copy(
                    validator = validator,
                    resumable = downloadResumable(src.acceptRanges, validator),
                    // A resume that got the whole file had to start over.
                    note = if (kept > 0) DOWNLOAD_RESTARTED_NOTE else null,
                )
            } else {
                entry = entry.copy(note = null)
            }
            entry = entry.copy(totalBytes = total, receivedBytes = startAt)
            if (!downloadFitsStorage(
                    allocatableBytes(partialDir),
                    if (total >= 0) (total - startAt).coerceAtLeast(0) else 0,
                    STORAGE_FLOOR_BYTES,
                )
            ) {
                throw notEnoughStorage()
            }
            // A whole answer to a resume is a new file (or the same one,
            // started over): the bytes kept belong to the old one. Gone
            // before the row takes its validator — a pause (or process
            // death) landing after that must not keep old bytes under the
            // new file's validator, which a later resume would continue,
            // splicing the two.
            if (fromStart && partial.exists() && !partial.delete()) {
                throw DownloadFailure(DownloadNote.of(R.string.library_download_write_failed))
            }
            save(entry)
            val received = copyWithProgress(id, src.stream, partial, startAt, total)
            // With a Content-Length a short body is caught here, and a
            // chunked body cut off mid-stream fails in read() (the
            // chunk framing is incomplete). A close-delimited body —
            // no length, not chunked, the HTTP/1.0 way — can't be
            // checked: a connection that drops cleanly looks exactly
            // like its end, so such a body is taken as complete. A
            // reset still fails; the storage floor bounds the rest.
            if (total >= 0 && received < total) {
                throw IOException(Strings.get(R.string.library_download_connection_closed_early))
            }
            return received
        }
    }

    /**
     * [src], with an [IOException] it throws while being read turned into
     * the one [map] makes of it — a malformed `data:` body or a page that
     * stopped handing over its `blob:` file fails with its own reason, not
     * as a lost connection.
     */
    private class FailureMappingStream(
        src: InputStream,
        private val map: (IOException) -> IOException,
    ) : java.io.FilterInputStream(src) {
        override fun read(): Int = try { super.read() } catch (e: IOException) { throw remap(e) }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            try { super.read(b, off, len) } catch (e: IOException) { throw remap(e) }
        private fun remap(e: IOException) = if (e is DownloadFailure) e else map(e)
    }

    /** A response body plus what it says about itself. */
    private class Body(
        val stream: InputStream,
        val length: Long,
        val mimeType: String?,
        val contentDisposition: String?,
        /** The URL whose last path segment names the file (final URL after redirects). */
        val nameUrl: String,
        /** HTTP status: 200 or 206 — or 416, whose (empty) body stands for "no such range". */
        val status: Int = 200,
        val contentRange: String? = null,
        val acceptRanges: String? = null,
        val etag: String? = null,
        val lastModified: String? = null,
        /** The response's `Date`, which tells whether [lastModified] is a strong validator. */
        val date: String? = null,
        private val onClose: () -> Unit = {},
    ) : AutoCloseable {
        override fun close() {
            runCatching { stream.close() }
            onClose()
        }
    }

    /**
     * A failure with a message fit for the downloads list as-is.
     * [retriable]: the file couldn't be reached right now (a stopped
     * node, an error status) — a resumed download it hits stays paused,
     * its partial file kept, rather than failing.
     */
    private class DownloadFailure(
        /** What the row keeps ([DownloadNote]); the message is it resolved. */
        val stored: String,
        val retriable: Boolean = false,
        /**
         * The device ran out of room. A download whose partial file a
         * resume can go on from pauses instead (#265): deleting what's
         * in would only make the user fetch it all again once they've
         * freed some space.
         */
        val noSpace: Boolean = false,
    ) : IOException(DownloadNote.shown(stored))

    private fun notEnoughStorage() = DownloadFailure(DownloadNote.of(R.string.library_download_not_enough_storage), noSpace = true)

    private suspend fun openBody(
        target: DownloadTarget,
        userAgent: String?,
        contentDisposition: String?,
        refererOrigin: String?,
        cookies: CookieManager?,
        track: (AutoCloseable) -> Unit,
        /** A resume's `Range` / `If-Range` ([downloadResumeHeaders]); empty for the whole file. */
        rangeHeaders: Map<String, String>,
    ): Body = when (target) {
        is DownloadTarget.Data -> {
            // Decoded as it's written: a page's data: URI can be tens of MB.
            val body = openDataUri(target.uri) ?: throw DownloadFailure(DownloadNote.of(R.string.library_download_malformed_data_uri))
            Body(
                stream = FailureMappingStream(body.stream) { DownloadFailure(DownloadNote.of(R.string.library_download_malformed_data_uri)) },
                length = body.length,
                mimeType = normalizeMime(body.mimeType),
                contentDisposition = contentDisposition,
                nameUrl = target.uri,
            )
        }
        is DownloadTarget.Blob -> {
            // Read inside the page that made it ([BlobDownloads]); with no
            // page's answer (the row of a dead process) there's nothing to read.
            val source = target.source ?: throw DownloadFailure(DownloadNote.of(R.string.library_download_blob_page_closed))
            source.failure?.let { throw DownloadFailure(it) }
            val stream = try {
                source.open()
            } catch (e: BlobReadException) {
                throw DownloadFailure(e.note)
            }
            Body(
                stream = FailureMappingStream(stream) { e ->
                    if (e is BlobReadException) DownloadFailure(e.note) else e
                },
                length = source.size,
                mimeType = blobMimeType(source.mimeType, source.name, ::mimeForExtension),
                contentDisposition = blobContentDisposition(source.name) ?: contentDisposition,
                nameUrl = target.url,
            )
        }
        is DownloadTarget.Dweb -> {
            val gatewayUrl = Gateways.gatewayUrlFor(target.root, target.pathAndQuery)
                ?: throw DownloadFailure(
                    if (target.root is ContentRoot.Ens) DownloadNote.of(
                        R.string.library_download_ens_unresolved,
                        // As the row's address shows it (#490 R2-M1).
                        EnsNormalize.tezosDisplay(target.root.name),
                    )
                    else DownloadNote.of(R.string.library_download_node_not_running),
                    retriable = true,
                )
            // Name the file after the dweb path, not the gateway URL
            // (whose last segment for a bare root would be the hash).
            fetchDweb(gatewayUrl, nameUrl = target.displayUrl.let { d ->
                if (d.contains("://")) d else "ens://$d"
            }, track = track, rangeHeaders = rangeHeaders)
        }
        is DownloadTarget.LocalGateway ->
            fetchDweb(target.url, nameUrl = target.displayUrl, track = track, rangeHeaders = rangeHeaders)
        is DownloadTarget.Web -> fetchWeb(target.url, userAgent, refererOrigin, cookies, track, rangeHeaders)
        is DownloadTarget.Unsupported ->
            throw DownloadFailure(DownloadNote.of(R.string.library_download_scheme_unsupported, target.scheme))
    }

    private suspend fun fetchDweb(
        gatewayUrl: String,
        nameUrl: String,
        track: (AutoCloseable) -> Unit,
        rangeHeaders: Map<String, String>,
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
                    rangeHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
                }
            } catch (_: TorRouting.RedirectRefusedException) {
                // The gateway answered, with a redirect onto this device:
                // not a stopped node, and refused again on every retry.
                throw DownloadFailure(DownloadNote.of(R.string.library_download_redirect_refused))
            } catch (_: java.net.ConnectException) {
                throw DownloadFailure(DownloadNote.of(R.string.library_download_node_not_running), retriable = true)
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                Log.i(LOG_TAG, "dweb download attempt failed: $gatewayUrl", e)
                if (!dwebHeaderFailureRetries(e)) break
                continue
            }
            val status = conn.responseCode
            if (status in 200..299 || (status == 416 && rangeHeaders.isNotEmpty())) return bodyOf(conn, nameUrl)
            lastStatus = status
            conn.disconnect()
            if (status !in DWEB_TRANSIENT_STATUSES) break
            Log.i(LOG_TAG, "dweb download: transient $status for $gatewayUrl")
        }
        throw DownloadFailure(
            when (lastStatus) {
                0 -> DownloadNote.of(R.string.library_download_gateway_no_answer)
                404 -> DownloadNote.of(R.string.library_download_content_not_found)
                else -> DownloadNote.of(R.string.library_download_gateway_error, lastStatus)
            },
            retriable = true,
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
        rangeHeaders: Map<String, String>,
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
                // On every hop: the range is of the file, wherever it's served from.
                rangeHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
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
            if (status == 416 && rangeHeaders.isNotEmpty()) return bodyOf(conn, current)
            if (status !in 200..299) {
                conn.disconnect()
                throw DownloadFailure(DownloadNote.of(R.string.library_download_server_error, status), retriable = true)
            }
            return bodyOf(conn, current)
        }
        throw DownloadFailure(DownloadNote.of(R.string.library_download_too_many_redirects))
    }

    private fun bodyOf(conn: HttpURLConnection, nameUrl: String): Body {
        val status = conn.responseCode
        return Body(
            // A 416 has no body worth reading (and inputStream throws for it).
            stream = if (status >= 400) java.io.ByteArrayInputStream(ByteArray(0)) else conn.inputStream,
            length = conn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L,
            mimeType = normalizeMime(conn.contentType),
            contentDisposition = conn.getHeaderField("Content-Disposition"),
            nameUrl = nameUrl,
            status = status,
            contentRange = conn.getHeaderField("Content-Range"),
            acceptRanges = conn.getHeaderField("Accept-Ranges"),
            etag = conn.getHeaderField("ETag"),
            lastModified = conn.getHeaderField("Last-Modified"),
            date = conn.getHeaderField("Date"),
            onClose = { conn.disconnect() },
        )
    }

    /**
     * Bytes the shared-storage volume can still take (cache the system
     * may evict counted in); null when it can't be told, which doesn't
     * block the download.
     */
    private fun allocatableBytes(dir: File): Long? {
        allocatableForTest?.let { return it(dir) }
        return runCatching {
            val storage = appContext.getSystemService(StorageManager::class.java)
            storage.getAllocatableBytes(storage.getUuidForPath(dir))
        }.getOrNull()
    }

    /** The shared-storage volume Downloads lives on. */
    private fun sharedStorageDir(): File = Environment.getExternalStorageDirectory()

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    /**
     * Append [input] to [file], which holds [startAt] bytes of the file
     * already (0: write it afresh), publishing progress and re-checking
     * free space as it goes. Returns the file's length.
     */
    private suspend fun copyWithProgress(id: Long, input: InputStream, file: File, startAt: Long, total: Long): Long {
        partialDir.mkdirs()
        val out = try {
            FileOutputStream(file, startAt > 0)
        } catch (_: IOException) {
            throw DownloadFailure(DownloadNote.of(R.string.library_download_write_failed))
        }
        var received = startAt
        var lastPublish = 0L
        var nextStorageCheck = startAt + STORAGE_CHECK_EVERY_BYTES
        _progress.update { it + (id to DownloadProgress(received, total)) }
        out.use { sink ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buffer)
                if (n < 0) break
                // A failed write is the device's, not the connection's:
                // it must not pass for a lost connection (and pause).
                try {
                    sink.write(buffer, 0, n)
                } catch (e: IOException) {
                    throw if (isNoSpace(e)) notEnoughStorage() else DownloadFailure(DownloadNote.of(R.string.library_download_write_failed))
                }
                received += n
                if (received >= nextStorageCheck) {
                    nextStorageCheck = received + STORAGE_CHECK_EVERY_BYTES
                    val stillToWrite = if (total >= 0) (total - received).coerceAtLeast(0) else 0
                    if (!downloadFitsStorage(allocatableBytes(partialDir), stillToWrite, STORAGE_FLOOR_BYTES)) {
                        throw notEnoughStorage()
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

    /**
     * Copy the finished partial [file] into the pending Downloads item
     * [uri] — or, [intoPicked], the document the user picked (#322),
     * written from its start.
     */
    private suspend fun copyToDownloads(file: File, uri: Uri, intoPicked: Boolean = false) {
        val out = if (intoPicked) {
            // Gone, or no longer Freedom's to write (a grant the provider
            // wouldn't let outlive the activity that picked it).
            try {
                DownloadSaveTo.openForWriting(resolver, uri)
            } catch (_: SecurityException) {
                null
            } catch (_: java.io.FileNotFoundException) {
                null
            } ?: throw DownloadFailure(DownloadNote.of(R.string.library_download_write_to_picked_failed))
        } else {
            resolver.openOutputStream(uri) ?: throw DownloadFailure(DownloadNote.of(R.string.library_download_write_to_downloads_failed))
        }
        out.use { sink ->
            file.inputStream().use { src ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = src.read(buffer)
                    if (n < 0) break
                    try {
                        sink.write(buffer, 0, n)
                    } catch (e: IOException) {
                        if (isNoSpace(e)) throw notEnoughStorage()
                        throw e
                    }
                }
            }
        }
    }

    /** Did [e] come from a full disk (ENOSPC / EDQUOT)? */
    private fun isNoSpace(e: IOException): Boolean {
        val errno = (e.cause as? android.system.ErrnoException)?.errno
        return errno == android.system.OsConstants.ENOSPC || errno == android.system.OsConstants.EDQUOT ||
            e.message?.contains("ENOSPC") == true || e.message?.contains("No space left") == true
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

    /**
     * Test hook: runs as soon as every byte of a download is in its
     * partial file, before its length is written — where a pause can
     * land on a body that came with no length.
     */
    @Volatile internal var afterFetchForTest: ((Long) -> Unit)? = null

    /**
     * Test hook: runs once a download's response headers are in and its
     * row is about to be saved with them — where a pause can land after
     * a resume got the whole (changed) file back.
     */
    @Volatile internal var afterHeadersForTest: ((Long) -> Unit)? = null

    /** Test hook: free space on [dir]'s volume, in place of the real answer. */
    @Volatile internal var allocatableForTest: ((dir: File) -> Long?)? = null

    /** Test hook: the room a picked document's volume has (#322), instead of asking it. */
    @Volatile internal var pickedRoomForTest: ((Uri) -> Long?)? = null

    /** Test hook: the shared-storage volume's directory, for [allocatableForTest]. */
    internal fun sharedStorageDirForTest(): File = sharedStorageDir()

    companion object {
        @Volatile private var instance: DownloadManager? = null

        /**
         * Test hook: a manager built while this is set holds its startup
         * sweep until it completes — the window a process started by a
         * notification tap is in.
         */
        @Volatile internal var sweepGateForTest: kotlinx.coroutines.Deferred<Unit>? = null

        /**
         * Test hook: a second manager on the same database, as a new
         * process would build it (its sweep sees the rows left RUNNING).
         */
        internal fun newProcessForTest(context: Context): DownloadManager = DownloadManager(context)

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

private fun mimeForExtension(ext: String): String? =
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)

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
