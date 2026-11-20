package baby.freedom.mobile.agent

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.ValueCallback
import baby.freedom.mobile.browser.DownloadFileState
import baby.freedom.mobile.browser.DownloadManager
import baby.freedom.mobile.browser.queryDownloadFileState
import baby.freedom.mobile.data.DownloadEntry
import baby.freedom.mobile.data.DownloadStatus
import baby.freedom.mobile.data.NodeSettings
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Which download an approved `browser_download` covers (#600): desktop's
 * controlled-download check (`downloads-manager.js` `handleWillDownload`)
 * for the one address Android's `DownloadListener` reports.
 *
 * Desktop accepts a download whose redirect chain contains the approved
 * link and that ends on the link's origin. WebView reports only where
 * the download ended, not the chain, so here a download matches when it
 * is the approved link itself (fragment aside), or ends on the link's
 * origin: a same-origin redirect desktop also accepts. A download from
 * anywhere else is never covered (desktop's rule too), so a page can't
 * swap in another site's file. Within the one origin it covers nothing
 * a site couldn't already do: it decides what bytes the approved address
 * serves anyway.
 *
 * A `blob:` or `data:` link has no origin of its own here and must match
 * exactly.
 */
internal object DownloadIntentMatch {
    private val WEB_SCHEMES = setOf("http", "https", "bzz", "ipfs", "ipns")

    fun matches(expected: String, actual: String): Boolean {
        val e = expected.trim().substringBefore('#')
        val a = actual.trim().substringBefore('#')
        if (e.isEmpty() || a.isEmpty()) return false
        if (e == a) return true
        val origin = originOf(e) ?: return false
        return origin == originOf(a)
    }

    /** `scheme://host[:port]` of a web or dweb address, lower case, without a default port; null otherwise. */
    fun originOf(url: String): String? {
        val uri = try {
            URI(url.trim())
        } catch (e: Exception) {
            return null
        }
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme !in WEB_SCHEMES) return null
        if (uri.rawUserInfo != null) return null
        val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val port = uri.port.takeIf { it >= 0 && !(scheme == "http" && it == 80) && !(scheme == "https" && it == 443) }
        return "$scheme://$host" + (port?.let { ":$it" } ?: "")
    }
}

/**
 * What `DownloadManager.start` holds while it lets an approved download
 * through (#600): the agent's [DownloadIntent] it matched.
 */
internal interface AgentDownloadClaim {
    /**
     * Start it at once: the user's Allow on the agent's card was the yes.
     * False when the user asks where to save each file (#322): then it's
     * offered like any download, and the user's answer there decides.
     */
    val direct: Boolean

    /** Its row in the downloads list, once started. */
    fun started(rowId: Long)

    /** Offered, and the offer went without starting (declined, or its tab closed). */
    fun declined()

    /** The offer prompt didn't take it (its tab's downloads blocked, or too many waiting). */
    fun notQueued()
}

/**
 * One approved `browser_download`, armed on its tab just before the
 * agent's tap on the link. One per tab, used once: the first download
 * the tab's pages start afterwards either matches it ([DownloadIntentMatch])
 * or ends it as [Start.Unapproved], and is then held for the user like
 * any page download.
 */
internal class DownloadIntent(
    val sessionId: String,
    val tabId: Long,
    /** The link's address as the page gave it, in full. */
    val expectedUrl: String,
    val direct: Boolean,
) {
    sealed interface Start {
        /** Saving: row [rowId] of the downloads list, fetched from [url]. */
        data class Started(val rowId: Long, val url: String) : Start

        /** The page's download wasn't the approved one: it waits for the user's own answer. */
        data object Unapproved : Start

        /** Offered (Ask where to save is on) and the user said no, or closed the tab. */
        data object Declined : Start

        /** Offered, and the prompt refused it: the tab's downloads are blocked, or too many wait. */
        data object NotQueued : Start

        /** The user took the tab back before its download came: theirs now. */
        data object TakenBack : Start
    }

    /** Completes when a download from the tab met this intent and matched. */
    val claimed = CompletableDeferred<Unit>()

    /** How it ended up. */
    val start = CompletableDeferred<Start>()
}

/**
 * The agent's approved downloads waiting for their page to start them
 * (#600), and the hook `DownloadManager.start` asks about every download
 * a page starts ([claim]). Any thread: a big `data:` download is offered
 * off the main thread, a `blob:` one once its page has handed the file over.
 */
internal object AgentDownloads {
    private val armed = HashMap<Long, DownloadIntent>()
    private val held = HashSet<Long>()

    /** Who holds a tab; [AgentTabs] outside tests. */
    @Volatile
    internal var holderOf: (Long) -> String? = { AgentTabs.holder(it) }

    /** Arms [intent] on its tab; false if one is already armed there. */
    @Synchronized
    fun arm(intent: DownloadIntent): Boolean {
        if (intent.tabId in armed) return false
        armed[intent.tabId] = intent
        held.remove(intent.tabId)
        return true
    }

    /** [intent] is over, whichever way (answered, timed out, the turn stopped). */
    @Synchronized
    fun disarm(intent: DownloadIntent) {
        if (armed[intent.tabId] === intent) armed.remove(intent.tabId)
    }

    /**
     * A page in tab [tabId] wants to save [url]. Non-null only for the
     * download an armed intent approved, which consumes it. Any other
     * download from a tab the agent holds is left to the usual offer
     * (held for the user) and noted for the agent ([takeHeld]); one that
     * met an armed intent and didn't match also ends that intent.
     */
    @Synchronized
    fun claim(tabId: Long, url: String): AgentDownloadClaim? {
        val intent = armed[tabId]
        if (intent == null) {
            if (holderOf(tabId) != null) held += tabId
            return null
        }
        armed.remove(tabId)
        if (holderOf(tabId) != intent.sessionId) {
            intent.start.complete(DownloadIntent.Start.TakenBack)
            return null
        }
        if (!DownloadIntentMatch.matches(intent.expectedUrl, url)) {
            held += tabId
            intent.start.complete(DownloadIntent.Start.Unapproved)
            return null
        }
        intent.claimed.complete(Unit)
        return object : AgentDownloadClaim {
            override val direct = intent.direct

            override fun started(rowId: Long) {
                intent.start.complete(DownloadIntent.Start.Started(rowId, url))
            }

            override fun declined() {
                intent.start.complete(DownloadIntent.Start.Declined)
            }

            override fun notQueued() {
                intent.start.complete(DownloadIntent.Start.NotQueued)
            }
        }
    }

    /** Whether a page in [tabId] started a download no approval covered since last asked; clears it. */
    @Synchronized
    fun takeHeld(tabId: Long): Boolean = held.remove(tabId)

    /** For tests. */
    @Synchronized
    internal fun clear() {
        armed.clear()
        held.clear()
    }
}

/**
 * One approved `browser_upload`, armed on its tab just before the
 * agent's tap on the file input (#600). The file chooser that tap opens
 * is the user's: Android's own picker, where they pick or cancel. The
 * agent is told only what was attached (name, size, type), never where
 * it came from.
 */
internal class UploadIntent(val sessionId: String, val tabId: Long) {
    sealed interface Result {
        data class Picked(val uris: List<Uri>, val resolver: ContentResolver) : Result

        /** The user closed the picker without choosing. */
        data object Cancelled : Result

        /** The chooser was closed by the app, not the user (another chooser, the screen going away). */
        data object Withdrawn : Result
    }

    /** Whether the page's chooser opened (true) or couldn't (false). */
    val opened = CompletableDeferred<Boolean>()
    val result = CompletableDeferred<Result>()

    /**
     * The page's callback, answered as the picker answers it, with the
     * answer reported to this intent too. [withdraw]: the app is about to
     * answer it with nothing, which isn't the user's cancel.
     */
    inner class Callback(
        private val inner: ValueCallback<Array<Uri>>,
        private val resolver: ContentResolver,
    ) : ValueCallback<Array<Uri>> {
        @Volatile
        private var withdrawn = false

        fun withdraw() {
            withdrawn = true
        }

        override fun onReceiveValue(value: Array<Uri>?) {
            try {
                inner.onReceiveValue(value)
            } finally {
                result.complete(
                    when {
                        withdrawn -> Result.Withdrawn
                        value.isNullOrEmpty() -> Result.Cancelled
                        else -> Result.Picked(value.toList(), resolver)
                    },
                )
            }
        }
    }
}

/**
 * The agent's approved uploads waiting for their page's file chooser
 * (#600); `onShowFileChooser` asks [take] for every chooser a page opens.
 */
internal object AgentUploads {
    private val armed = HashMap<Long, UploadIntent>()

    @Synchronized
    fun arm(intent: UploadIntent): Boolean {
        if (intent.tabId in armed) return false
        armed[intent.tabId] = intent
        return true
    }

    @Synchronized
    fun disarm(intent: UploadIntent) {
        if (armed[intent.tabId] === intent) armed.remove(intent.tabId)
    }

    /**
     * Tab [tabId]'s page opens a file chooser: the armed intent it's for,
     * used up, or null (a chooser of the user's own). The caller wraps the
     * page's callback with [UploadIntent.Callback] and reports whether the
     * chooser opened. Not for a tab the chat no longer holds.
     */
    @Synchronized
    fun take(tabId: Long): UploadIntent? {
        val intent = armed.remove(tabId) ?: return null
        if (AgentTabs.holder(tabId) != intent.sessionId) {
            intent.opened.complete(false)
            return null
        }
        return intent
    }

    /** The app answers [callback] with nothing (not the user's cancel). */
    fun withdrawing(callback: ValueCallback<Array<Uri>>) {
        (callback as? UploadIntent.Callback)?.withdraw()
    }
}

/** A file the user picked for an upload, as its provider describes it. */
internal data class PickedFile(val name: String, val bytes: Long, val mimeType: String?)

/**
 * A download the agent made, as `browser_download` and
 * `browser_list_downloads` report it (desktop's artifact receipt): never
 * a path or a content address.
 */
internal data class DownloadReceipt(
    val artifactId: String,
    val fileName: String,
    val mimeType: String?,
    val bytes: Long,
    /** completed, running, paused, failed, cancelled, or removed (gone from Freedom's list). */
    val state: String,
    val sourceOrigin: String,
    /** downloads (Download/Freedom) or chosen_location (a file the user picked to save as). */
    val location: String,
    val available: Boolean,
) {
    fun json(): JSONObject = JSONObject()
        .put("artifactId", artifactId)
        .put("filename", fileName)
        .apply { mimeType?.let { put("mimeType", it) } }
        .put("bytes", bytes)
        .put("state", state)
        .put("sourceOrigin", sourceOrigin)
        .put("location", location)
        .put("available", available)

    companion object {
        /**
         * The receipt for [record] given its row as it is now ([row]; null
         * once the user removed it from the list). [present]: whether the
         * saved file is still there, asked only of a completed row.
         */
        fun of(record: AgentDownloadRecords.Record, row: DownloadEntry?, present: (DownloadEntry) -> Boolean): DownloadReceipt {
            if (row == null) {
                return DownloadReceipt(
                    record.artifactId, record.fileName, null, 0, "removed", record.sourceOrigin, "downloads", false,
                )
            }
            val completed = row.status == DownloadStatus.COMPLETED
            return DownloadReceipt(
                artifactId = record.artifactId,
                fileName = ApprovalText.flatten(row.fileName, 255).ifEmpty { record.fileName },
                mimeType = row.mimeType.take(200).ifEmpty { null },
                bytes = maxOf(0L, row.receivedBytes),
                state = row.status,
                sourceOrigin = record.sourceOrigin,
                location = if (row.saveTo != null) "chosen_location" else "downloads",
                available = completed && present(row),
            )
        }
    }
}

/**
 * Which downloads each chat made (#600), for `browser_list_downloads`:
 * desktop keeps the conversation on the download's row; here a small
 * file beside the agent's database does, so the downloads list itself is
 * untouched. Kept across restarts; a chat's records go with the chat.
 */
internal class AgentDownloadRecords(private val file: File) {
    data class Record(
        val sessionId: String,
        val artifactId: String,
        val rowId: Long,
        val fileName: String,
        val sourceOrigin: String,
    )

    private val lock = Mutex()
    private var loaded: MutableList<Record>? = null

    private fun read(): MutableList<Record> {
        loaded?.let { return it }
        val out = ArrayList<Record>()
        val text = try {
            if (file.exists()) file.readText() else ""
        } catch (e: Exception) {
            ""
        }
        val array = try {
            if (text.isBlank()) JSONArray() else JSONArray(text)
        } catch (e: Exception) {
            JSONArray()
        }
        for (i in 0 until array.length()) {
            // Each on its own: one bad entry doesn't lose the rest.
            val o = array.optJSONObject(i) ?: continue
            val session = o.optString("session").ifEmpty { null } ?: continue
            val artifact = o.optString("artifact").takeIf { ARTIFACT_ID.matches(it) } ?: continue
            val row = o.optLong("row", -1).takeIf { it > 0 } ?: continue
            out += Record(session, artifact, row, o.optString("name"), o.optString("origin"))
        }
        loaded = out
        return out
    }

    private fun write(records: List<Record>) {
        val array = JSONArray()
        for (r in records) {
            array.put(
                JSONObject().put("session", r.sessionId).put("artifact", r.artifactId).put("row", r.rowId)
                    .put("name", r.fileName).put("origin", r.sourceOrigin),
            )
        }
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use {
            it.write(array.toString().toByteArray(Charsets.UTF_8))
            it.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw java.io.IOException("can't replace ${file.name}")
        }
    }

    /** Records a new download of [sessionId]'s, under a new receipt id. */
    suspend fun add(sessionId: String, rowId: Long, fileName: String, sourceOrigin: String): Record = lock.withLock {
        withContext(Dispatchers.IO) {
            val records = read()
            val record = Record(sessionId, newArtifactId(), rowId, ApprovalText.flatten(fileName, 255), sourceOrigin)
            val next = (records + record).let { all ->
                // Only the newest few hundred per chat.
                val mine = all.filter { it.sessionId == sessionId }
                if (mine.size > MAX_PER_CHAT) all - mine.take(mine.size - MAX_PER_CHAT).toSet() else all
            }
            try {
                write(next)
            } catch (e: Exception) {
                // Still listed for as long as the process lives.
                Log.w(TAG, "saving agent downloads failed (${e.javaClass.simpleName})")
            }
            records.clear()
            records += next
            record
        }
    }

    suspend fun of(sessionId: String): List<Record> = lock.withLock {
        withContext(Dispatchers.IO) { read().filter { it.sessionId == sessionId } }
    }

    /** A chat was deleted: its records go ([sessionId] null: every chat's). */
    suspend fun forget(sessionId: String?) = lock.withLock {
        withContext(Dispatchers.IO) {
            val records = read()
            val next = if (sessionId == null) emptyList() else records.filter { it.sessionId != sessionId }
            if (next.size == records.size && file.exists()) return@withContext
            try {
                if (next.isEmpty()) file.delete() else write(next)
            } catch (e: Exception) {
                Log.w(TAG, "forgetting agent downloads failed (${e.javaClass.simpleName})")
            }
            records.clear()
            records += next
        }
    }

    companion object {
        private const val TAG = "AgentDownloadRecords"
        const val MAX_PER_CHAT = 200
        val ARTIFACT_ID = Regex("^artifact_[a-f0-9]{20}$")

        fun newArtifactId(): String = "artifact_" + UUID.randomUUID().toString().replace("-", "").take(20)
    }
}

/**
 * What the upload and download tools need of the app beyond the tabs
 * (#600): the downloads list, the user's Ask where to save setting, the
 * chat's download records, and what a picked file is.
 */
internal interface AgentFilesPort {
    /** Whether the user asks where to save each download (#322). */
    suspend fun askWhereToSave(): Boolean

    /** Row [rowId] as it is now; null once it's removed from the list. */
    suspend fun row(rowId: Long): DownloadEntry?

    /** Row [rowId] once it stops running (completed, failed, cancelled, paused), or null once it's removed. */
    suspend fun settled(rowId: Long): DownloadEntry?

    /** Whether a completed row's file is still there. Blocking. */
    fun present(row: DownloadEntry): Boolean

    val records: AgentDownloadRecords

    /** What the picker handed over, as its providers describe it. Blocking. */
    fun describe(picked: UploadIntent.Result.Picked): List<PickedFile>
}

/** Where the tools reach [AgentFilesPort]: installed with the chats ([install]). */
internal object AgentFiles {
    @Volatile
    var port: AgentFilesPort? = null

    fun install(context: Context): AgentFilesPort = port ?: synchronized(this) {
        port ?: AppFiles(context.applicationContext).also { port = it }
    }

    private class AppFiles(private val app: Context) : AgentFilesPort {
        override val records = AgentDownloadRecords(File(app.filesDir, "agent/downloads.json"))

        override suspend fun askWhereToSave(): Boolean =
            runCatching { NodeSettings.get(app).askWhereToSave.first() }.getOrDefault(false)

        override suspend fun row(rowId: Long): DownloadEntry? = DownloadManager.get(app).entry(rowId)

        override suspend fun settled(rowId: Long): DownloadEntry? = DownloadManager.get(app).downloads
            .map { rows -> rows.firstOrNull { it.id == rowId } }
            .first { it == null || it.status != DownloadStatus.RUNNING }

        override fun present(row: DownloadEntry): Boolean {
            val uri = (row.contentUri ?: row.saveTo)?.let(Uri::parse) ?: return false
            if (uri.authority == "media") return queryDownloadFileState(app.contentResolver, uri) == DownloadFileState.PRESENT
            return runCatching {
                app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { it.moveToFirst() } == true
            }.getOrDefault(false)
        }

        override fun describe(picked: UploadIntent.Result.Picked): List<PickedFile> = picked.uris.map { uri ->
            var name: String? = null
            var size = -1L
            runCatching {
                picked.resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val s = c.getColumnIndex(OpenableColumns.SIZE)
                        if (n >= 0 && !c.isNull(n)) name = c.getString(n)
                        if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
                    }
                }
            }
            val type = runCatching { picked.resolver.getType(uri) }.getOrNull()
            PickedFile(
                // Only the name: a provider's display name is never a path, but cut any it gives.
                name = ApprovalText.flatten(name.orEmpty().substringAfterLast('/'), 255),
                bytes = size,
                mimeType = type?.let { ApprovalText.flatten(it, 200) }?.ifEmpty { null },
            )
        }
    }
}
