package baby.freedom.mobile.browser

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import baby.freedom.swarm.SwarmNode
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/*
 * Publishing a file, a folder or some text to Swarm (#118), and the
 * history of what was published — after desktop's publish-service.js /
 * publish-history.js and iOS's SwarmPublishHistoryView. Uploads go to
 * the embedded light node's gateway (`POST /bzz`), stamped with one of
 * its usable postage batches; a folder goes as a tar collection, with
 * its top-level index.html as the page `bzz://<reference>` opens. The
 * page lives in PublishScreen.kt.
 */

internal enum class PublishKind { File, Folder, Text }

internal enum class PublishStatus { Uploading, Completed, Failed }

/** One publish, as the history lists it. Nothing here is secret: it's what was made public. */
internal data class PublishRecord(
    val id: String,
    val kind: PublishKind,
    /** The file's or folder's name; "Text" for text. */
    val name: String,
    val status: PublishStatus,
    val startedAt: Long,
    /** 64 lowercase hex, once it completed. */
    val reference: String? = null,
    val batchId: String? = null,
    /** What was uploaded: the file, the folder's files, or the text. */
    val bytes: Long? = null,
    val completedAt: Long? = null,
    val error: String? = null,
) {
    val bzzUrl: String? get() = reference?.let { "bzz://$it" }
}

/** What a history record interrupted by the app's end says (desktop's sweep of orphaned rows). */
internal const val PUBLISH_INTERRUPTED = "Interrupted: Freedom was closed before the upload finished"

/** The history file's JSON. A record that can't be read back is dropped; the others still hold. */
internal object PublishHistoryCodec {
    fun encode(records: List<PublishRecord>): JSONObject = JSONObject()
        .put("version", 1)
        .put("records", JSONArray().apply { records.forEach { put(record(it)) } })

    /**
     * The records in [o], newest first. One still [PublishStatus.Uploading]
     * belonged to a run that ended mid-upload (no upload outlives the
     * process), so it reads back as failed.
     */
    fun decode(o: JSONObject, now: Long): List<PublishRecord> {
        if (o.optInt("version") != 1) return emptyList()
        val a = o.optJSONArray("records") ?: return emptyList()
        return (0 until a.length())
            .mapNotNull { i -> runCatching { record(a.getJSONObject(i)) }.getOrNull() }
            .distinctBy { it.id }
            .map {
                if (it.status == PublishStatus.Uploading) {
                    it.copy(status = PublishStatus.Failed, completedAt = now, error = PUBLISH_INTERRUPTED)
                } else {
                    it
                }
            }
            .sortedByDescending { it.startedAt }
    }

    private fun record(r: PublishRecord): JSONObject = JSONObject()
        .put("id", r.id)
        .put("kind", r.kind.name)
        .put("name", r.name)
        .put("status", r.status.name)
        .put("startedAt", r.startedAt)
        .put("reference", r.reference ?: JSONObject.NULL)
        .put("batchId", r.batchId ?: JSONObject.NULL)
        .put("bytes", r.bytes ?: JSONObject.NULL)
        .put("completedAt", r.completedAt ?: JSONObject.NULL)
        .put("error", r.error ?: JSONObject.NULL)

    private fun record(o: JSONObject): PublishRecord {
        val reference = o.stringOrNull("reference")
        require(reference == null || isSwarmReference(reference))
        return PublishRecord(
            id = o.getString("id").also { require(it.isNotEmpty()) },
            kind = PublishKind.valueOf(o.getString("kind")),
            name = o.getString("name"),
            status = PublishStatus.valueOf(o.getString("status")),
            startedAt = o.getLong("startedAt"),
            reference = reference,
            batchId = o.stringOrNull("batchId")?.let(::normalizeBatchId),
            bytes = if (o.isNull("bytes")) null else o.getLong("bytes"),
            completedAt = if (o.isNull("completedAt")) null else o.getLong("completedAt"),
            error = o.stringOrNull("error"),
        )
    }

    private fun JSONObject.stringOrNull(name: String): String? = if (!has(name) || isNull(name)) null else getString(name)
}

internal fun isSwarmReference(s: String): Boolean = s.length == 64 && s.all { it in '0'..'9' || it in 'a'..'f' }

/** Where the history is kept between runs. */
internal interface PublishHistoryStore {
    fun save(records: List<PublishRecord>): Boolean
    fun load(now: Long): List<PublishRecord>

    object None : PublishHistoryStore {
        override fun save(records: List<PublishRecord>) = true
        override fun load(now: Long): List<PublishRecord> = emptyList()
    }
}

/** [PublishHistoryStore] in one JSON file, written whole to a temporary file and renamed over it. Blocks. */
internal class FilePublishHistoryStore(private val file: File) : PublishHistoryStore {
    override fun save(records: List<PublishRecord>): Boolean = try {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        if (records.isEmpty()) {
            (!tmp.exists() or tmp.delete()) and (!file.exists() || file.delete())
        } else {
            file.parentFile?.mkdirs()
            FileOutputStream(tmp).use { it.write(PublishHistoryCodec.encode(records).toString().toByteArray()) }
            tmp.renameTo(file) || run {
                tmp.delete()
                false
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "couldn't save the publish history: ${e.javaClass.simpleName}")
        false
    }

    override fun load(now: Long): List<PublishRecord> = try {
        if (file.exists()) PublishHistoryCodec.decode(JSONObject(file.readText()), now) else emptyList()
    } catch (e: Exception) {
        Log.w(TAG, "unreadable publish history: ${e.javaClass.simpleName}")
        emptyList()
    } catch (e: StackOverflowError) {
        emptyList()
    }

    private companion object {
        const val TAG = "PublishHistory"
    }
}

/**
 * The publish history (#118): every publish started from the Publish
 * page, newest first, with where it stands. Changes are made in memory
 * at once and written to [store] in order, the latest state last. A
 * change made before the file has been read is kept on top of what the
 * file holds; [clear] before then drops the file's records too.
 */
internal class PublishHistory(
    private val store: PublishHistoryStore,
    scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _records = MutableStateFlow<List<PublishRecord>>(emptyList())
    val records: StateFlow<List<PublishRecord>> = _records.asStateFlow()

    private var loaded = false
    private var clearedBeforeLoad = false
    private val removedBeforeLoad = mutableSetOf<String>()

    /** Held while the file is written, so writes land in the order their states were made. */
    private val writing = Any()

    /** One write at a time, off the caller's thread. */
    private val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    init {
        scope.launch(Dispatchers.IO) {
            val saved = runCatching { store.load(clock()) }.getOrDefault(emptyList())
            synchronized(this@PublishHistory) {
                loaded = true
                if (!clearedBeforeLoad) {
                    val mine = _records.value.map { it.id }.toSet()
                    _records.value = (_records.value + saved.filter { it.id !in mine && it.id !in removedBeforeLoad })
                        .sortedByDescending { it.startedAt }
                        .take(MAX_RECORDS)
                }
                removedBeforeLoad.clear()
            }
            persist()
        }
    }

    fun start(kind: PublishKind, name: String, bytes: Long?): PublishRecord {
        val record = PublishRecord(
            id = UUID.randomUUID().toString(),
            kind = kind,
            name = name,
            status = PublishStatus.Uploading,
            startedAt = clock(),
            bytes = bytes,
        )
        change { (listOf(record) + it).take(MAX_RECORDS) }
        return record
    }

    fun completed(id: String, reference: String, batchId: String, bytes: Long) = change { list ->
        list.map {
            if (it.id == id) {
                it.copy(status = PublishStatus.Completed, reference = reference, batchId = batchId, bytes = bytes, completedAt = clock())
            } else {
                it
            }
        }
    }

    fun failed(id: String, message: String) = change { list ->
        list.map { if (it.id == id) it.copy(status = PublishStatus.Failed, error = message, completedAt = clock()) else it }
    }

    /** Forgets a finished record. One still uploading stays: its outcome and link are still to come. */
    fun remove(id: String) = change { list ->
        if (list.any { it.id == id && it.status == PublishStatus.Uploading }) return@change list
        if (!loaded) removedBeforeLoad += id
        list.filterNot { it.id == id }
    }

    /**
     * Forgets every finished record. An upload in flight stays, or its
     * reference would have nowhere to land when ant answers.
     */
    fun clear() = change { list ->
        if (!loaded) clearedBeforeLoad = true
        list.filter { it.status == PublishStatus.Uploading }
    }

    private fun change(f: (List<PublishRecord>) -> List<PublishRecord>) {
        val write = synchronized(this) {
            _records.value = f(_records.value)
            loaded
        }
        if (write) persist()
    }

    /** Writes the current state (whatever it is by the time the lock is had), off the caller's thread if it's the main one. */
    private fun persist() {
        writer.launch {
            synchronized(writing) { store.save(synchronized(this@PublishHistory) { _records.value }) }
        }
    }

    companion object {
        /** Oldest records beyond this are dropped. */
        const val MAX_RECORDS = 500

        @Volatile private var instance: PublishHistory? = null

        fun get(context: Context): PublishHistory = instance ?: synchronized(this) {
            instance ?: PublishHistory(
                FilePublishHistoryStore(File(context.applicationContext.filesDir, "publish/history.json")),
                CoroutineScope(SupervisorJob() + Dispatchers.IO),
            ).also { instance = it }
        }
    }
}

/** How much room desktop leaves on a batch past the estimate (`SIZE_SAFETY_MARGIN`). */
internal const val PUBLISH_SIZE_MARGIN = 1.5

/** A batch's room left: its effective capacity less its fullest bucket's share (bee-js's `remainingSize`). */
internal fun batchRemainingBytes(batch: PostageBatch): Long =
    (batch.capacityBytes * (1.0 - batch.usedFraction)).toLong()

/**
 * What an upload takes out of a batch, roughly: every file fills whole
 * 4 KiB chunks, and each file adds at least a chunk of manifest. Small
 * files are where raw bytes would badly understate it — a 300-byte page
 * stamps two chunks.
 */
internal fun publishStampEstimate(fileSizes: List<Long>): Long =
    fileSizes.sumOf { size -> ((size + CHUNK - 1) / CHUNK).coerceAtLeast(1) * CHUNK + CHUNK }

private const val CHUNK = 4096L

/**
 * The batch to publish [stampBytes] with, desktop's `selectBestBatch`:
 * of the usable batches with room for it (with [PUBLISH_SIZE_MARGIN]),
 * the one that lasts longest. A batch whose time left the node couldn't
 * read still counts, after every one it could.
 */
internal fun selectPublishBatch(batches: List<PostageBatch>, stampBytes: Long): PostageBatch? =
    batches
        .filter { it.usable && (it.ttlSeconds ?: 1) > 0 && batchHasRoom(it, stampBytes) }
        .maxByOrNull { it.ttlSeconds ?: 0 }

/** Whether [batch] has room for [stampBytes], with [PUBLISH_SIZE_MARGIN]. */
internal fun batchHasRoom(batch: PostageBatch, stampBytes: Long): Boolean =
    batchRemainingBytes(batch) >= stampBytes * PUBLISH_SIZE_MARGIN

/**
 * How many bytes [input] holds, read to its end; throws
 * [PublishException] past [max]. For a document whose provider doesn't
 * give its size, so the stamp is chosen for what will really go out.
 */
internal fun measureCapped(input: InputStream, max: Long = MAX_PUBLISH_BYTES, tooBig: String): Long {
    val buf = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val n = input.read(buf)
        if (n < 0) return total
        total += n
        if (total > max) throw PublishException(tooBig)
    }
}

/**
 * The persistable read grants on picked files and folders, held only
 * while a publish needs them (#118). A count per URI, so one pick
 * releasing can't take away another's; and what no one here holds —
 * a grant left by a run that ended mid-upload or with the confirmation
 * up — is given back by [sweep] at the next launch.
 */
internal object PublishGrants {
    private val held = mutableMapOf<String, Int>()

    fun hold(context: Context, uri: Uri) = synchronized(this) {
        held[uri.toString()] = (held[uri.toString()] ?: 0) + 1
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        Unit
    }

    fun release(context: Context, uri: Uri) = synchronized(this) {
        val left = (held[uri.toString()] ?: 1) - 1
        if (left > 0) {
            held[uri.toString()] = left
        } else {
            held -= uri.toString()
            runCatching { context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        Unit
    }

    /** Gives back every persisted grant no publish in this process holds. Freedom keeps none otherwise. */
    fun sweep(context: Context) = synchronized(this) {
        val persisted = runCatching { context.contentResolver.persistedUriPermissions }.getOrDefault(emptyList())
        val stale = staleGrants(persisted.map { it.uri.toString() }, held.keys)
        persisted.filter { it.uri.toString() in stale }.forEach { p ->
            val flags = (if (p.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                (if (p.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
            runCatching { context.contentResolver.releasePersistableUriPermission(p.uri, flags) }
        }
    }
}

/** Of the [persisted] grants, the ones not [held] by a publish in this process. */
internal fun staleGrants(persisted: List<String>, held: Set<String>): Set<String> =
    persisted.filterNot { it in held }.toSet()

/**
 * The most a publish can upload: ant's gateway refuses a body over
 * 64 MiB (`GATEWAY_MAX_UPLOAD_BYTES`), and a folder's tar headers count.
 */
internal const val MAX_PUBLISH_BYTES = 64L * 1024 * 1024

/** A folder with more files than this is refused before anything is read. */
internal const val MAX_PUBLISH_FILES = 5_000

private const val MAX_FOLDER_DEPTH = 32

/** Why a publish can't go ahead, in words for the page. */
internal class PublishException(message: String) : Exception(message)

/** One file of a folder, found by [listFolder]: its path in the folder and its document in the picked tree. */
internal data class FolderFile(val path: String, val documentId: String, val size: Long)

/**
 * A name as a path segment, or null for one that can't be (empty, `.`,
 * `..`, or holding a slash or a NUL): such an entry is left out rather
 * than let it climb out of the folder in the manifest.
 */
internal fun safePathSegment(name: String?): String? =
    name?.takeIf { it.isNotEmpty() && it != "." && it != ".." && !it.contains('/') && !it.contains('\u0000') }

/**
 * Every file under the folder [treeUri] the picker granted, with paths
 * relative to it, sorted. Throws [PublishException] when it's empty or
 * too big for one upload.
 */
internal fun listFolder(resolver: ContentResolver, treeUri: Uri): List<FolderFile> {
    val out = mutableListOf<FolderFile>()
    var total = 0L
    fun walk(documentId: String, prefix: String, depth: Int) {
        if (depth > MAX_FOLDER_DEPTH) throw PublishException("The folder is nested too deep to publish")
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val cursor = resolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
            ),
            null, null, null,
        ) ?: throw PublishException("The folder couldn't be read")
        val dirs = mutableListOf<Pair<String, String>>()
        cursor.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = safePathSegment(c.getString(1)) ?: continue
                if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    dirs += id to "$prefix$name/"
                } else {
                    val size = if (c.isNull(3)) {
                        // The provider doesn't say: read it, so the stamp is picked for its real size.
                        val doc = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                        resolver.openInputStream(doc)?.use {
                            measureCapped(it, MAX_PUBLISH_BYTES - total, folderTooBig())
                        } ?: throw PublishException("$prefix$name couldn't be read")
                    } else {
                        c.getLong(3)
                    }
                    total += size
                    if (out.size >= MAX_PUBLISH_FILES) throw PublishException("The folder has more than $MAX_PUBLISH_FILES files")
                    if (total > MAX_PUBLISH_BYTES) throw PublishException(folderTooBig())
                    out += FolderFile("$prefix$name", id, size)
                }
            }
        }
        dirs.forEach { (id, path) -> walk(id, path, depth + 1) }
    }
    walk(DocumentsContract.getTreeDocumentId(treeUri), "", 0)
    if (out.isEmpty()) throw PublishException("The folder has no files")
    return out.sortedBy { it.path }
}

private fun folderTooBig() =
    "The folder is bigger than ${formatStampBytes(MAX_PUBLISH_BYTES)}, the most one publish can upload"

private fun fileTooBig() =
    "The file is bigger than ${formatStampBytes(MAX_PUBLISH_BYTES)}, the most one publish can upload"

/** The folder's own name, for the history. */
internal fun folderName(resolver: ContentResolver, treeUri: Uri): String {
    val doc = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
    return runCatching {
        resolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Folder"
}

/** A picked file's name and size (null when the provider doesn't say). */
internal fun fileInfo(resolver: ContentResolver, uri: Uri): Pair<String, Long?> {
    var name: String? = null
    var size: Long? = null
    runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0)
                size = if (c.isNull(1)) null else c.getLong(1)
            }
        }
    }
    return (safePathSegment(name?.trim()) ?: "file") to size
}

/**
 * A ustar archive written to a file, the shape ant's collection upload
 * reads (only regular files count). A path longer than the header's 100
 * bytes goes in a GNU long-name entry before it. Each file's size is
 * taken from what was actually read, patched into its header after, so
 * a file that changed since it was listed can't break the archive.
 */
internal class TarWriter(private val out: RandomAccessFile, private val maxBytes: Long) {
    /** Appends [path] with [input]'s bytes; returns how many there were. */
    fun add(path: String, input: InputStream): Long {
        val name = path.toByteArray(Charsets.UTF_8)
        if (name.size > 100) {
            ensureRoom(BLOCK + padded(name.size + 1L))
            out.write(header(LONG_LINK, name.size + 1L, TYPE_GNU_LONGNAME))
            out.write(name)
            out.write(0)
            pad(name.size + 1L)
        }
        val at = out.filePointer
        ensureRoom(BLOCK)
        out.write(ByteArray(BLOCK.toInt()))
        var size = 0L
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            size += n
            if (at + BLOCK + size > maxBytes) throw PublishException(tooBig())
            out.write(buf, 0, n)
        }
        pad(size)
        val end = out.filePointer
        out.seek(at)
        // Past 100 bytes the long-name entry above holds the path; this one is a stand-in.
        out.write(header(name.copyOf(minOf(name.size, 100)), size, TYPE_FILE))
        out.seek(end)
        return size
    }

    /** The two zero blocks that end an archive. */
    fun finish() {
        ensureRoom(2 * BLOCK)
        out.write(ByteArray(2 * BLOCK.toInt()))
    }

    private fun ensureRoom(extra: Long) {
        if (out.filePointer + extra > maxBytes) throw PublishException(tooBig())
    }

    private fun tooBig() = "It's bigger than ${formatStampBytes(MAX_PUBLISH_BYTES)}, the most one publish can upload"

    private fun pad(size: Long) {
        val rest = padded(size) - size
        if (rest > 0) out.write(ByteArray(rest.toInt()))
    }

    companion object {
        const val BLOCK = 512L
        const val TYPE_FILE = '0'
        const val TYPE_GNU_LONGNAME = 'L'

        fun padded(size: Long) = (size + BLOCK - 1) / BLOCK * BLOCK

        private val LONG_LINK = "././@LongLink".toByteArray()

        /** A 512-byte header; [nameField] is at most 100 bytes. */
        fun header(nameField: ByteArray, size: Long, type: Char): ByteArray {
            val h = ByteArray(BLOCK.toInt())
            nameField.copyInto(h, 0, 0, minOf(100, nameField.size))
            octal(h, 100, 8, 0b110_100_100) // mode 0644
            octal(h, 108, 8, 0) // uid
            octal(h, 116, 8, 0) // gid
            octal(h, 124, 12, size)
            octal(h, 136, 12, 0) // mtime: fixed, so the same files give the same archive
            h[156] = type.code.toByte()
            if (type == TYPE_GNU_LONGNAME) {
                "ustar  ".toByteArray().copyInto(h, 257) // GNU magic + version, with its NUL after
            } else {
                "ustar".toByteArray().copyInto(h, 257)
                h[263] = '0'.code.toByte()
                h[264] = '0'.code.toByte()
            }
            // The checksum is taken with its own field as spaces.
            for (i in 148 until 156) h[i] = ' '.code.toByte()
            val sum = h.sumOf { it.toInt() and 0xff }
            val digits = String.format("%06o", sum).toByteArray()
            digits.copyInto(h, 148)
            h[154] = 0
            h[155] = ' '.code.toByte()
            return h
        }

        private fun octal(h: ByteArray, at: Int, len: Int, value: Long) {
            val s = java.lang.Long.toOctalString(value).padStart(len - 1, '0')
            require(s.length <= len - 1) { "value too large for the tar header" }
            s.toByteArray().copyInto(h, at)
            h[at + len - 1] = 0
        }
    }
}

/** The request ant's gateway gets for one publish. */
internal data class PublishRequest(
    val kind: PublishKind,
    val batchId: String,
    /** The single file's name (a file, or text); null for a folder. */
    val fileName: String?,
    val contentType: String?,
    /** A folder's index document, or null. */
    val indexDocument: String?,
) {
    fun path(): String = if (fileName != null) "/bzz?name=" + URLEncoder.encode(fileName, "UTF-8").replace("+", "%20") else "/bzz"

    fun headers(): Map<String, String> = buildMap {
        put("Swarm-Postage-Batch-Id", batchId)
        // Desktop's publishes pin too: the node keeps its own copy.
        put("Swarm-Pin", "true")
        if (kind == PublishKind.Folder) {
            put("Swarm-Collection", "true")
            put("Content-Type", "application/x-tar")
            indexDocument?.let { put("Swarm-Index-Document", it) }
        } else {
            put("Content-Type", contentType ?: "application/octet-stream")
        }
    }
}

/** The folder's page, desktop's rule: a top-level index.html. */
internal fun indexDocumentFor(paths: List<String>): String? = "index.html".takeIf { it in paths }

/** A file's type, from the provider or else its name. */
internal fun contentTypeFor(name: String, provided: String?): String? =
    provided?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
        ?: name.substringAfterLast('.', "").lowercase().takeIf { it.isNotEmpty() }
            ?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }

/**
 * The reference in ant's answer to a `POST /bzz`, or a message saying
 * why there's none: ant's own for a refused request, with the causes a
 * user can do something about put in their words.
 */
internal fun publishAnswer(code: Int, body: String?): Result<String> {
    val o = runCatching { JSONObject(body ?: "") }.getOrNull()
    if (code in 200..299) {
        val ref = o?.optString("reference")?.lowercase()
        return if (ref != null && isSwarmReference(ref)) Result.success(ref) else Result.failure(PublishException("The node's answer had no reference"))
    }
    val message = o?.optString("message")?.takeIf { it.isNotBlank() } ?: body?.take(200)?.takeIf { it.isNotBlank() }
    val text = when {
        code == 413 -> "It's too big for one upload" + (message?.let { " ($it)" } ?: "")
        code == 422 || message?.contains("not usable") == true ->
            "The postage stamp can't be used for this: ${message ?: "HTTP $code"}"
        code == 503 -> "The node can't upload right now" + (message?.let { ": $it" } ?: "")
        else -> message?.let { "The node refused the upload: $it" } ?: "The node refused the upload (HTTP $code)"
    }
    return Result.failure(PublishException(text))
}

/**
 * Sends [body] ([length] bytes) to the gateway at [gatewayUrl] as
 * [request] and returns the reference. Blocks until ant has pushed every
 * chunk to the network — its answer comes only then — or gives up once
 * the node has been silent for [readTimeoutMs].
 */
internal fun uploadToGateway(
    gatewayUrl: String,
    request: PublishRequest,
    body: () -> InputStream,
    length: Long,
    readTimeoutMs: Int = PUBLISH_READ_TIMEOUT_MS,
): String {
    val conn = URL(gatewayUrl + request.path()).openConnection() as HttpURLConnection
    try {
        conn.requestMethod = "POST"
        conn.connectTimeout = 10_000
        conn.readTimeout = readTimeoutMs
        conn.useCaches = false
        conn.doOutput = true
        conn.setFixedLengthStreamingMode(length)
        request.headers().forEach { (k, v) -> conn.setRequestProperty(k, v) }
        try {
            conn.outputStream.use { out -> body().use { it.copyTo(out, 64 * 1024) } }
        } catch (e: IOException) {
            throw PublishException("Couldn't hand the upload to the Swarm node")
        }
        val code = try {
            conn.responseCode
        } catch (e: java.net.SocketTimeoutException) {
            throw PublishException("The Swarm node didn't finish the upload in time")
        }
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.use { it.readBytesCapped(64 * 1024) }?.toString(Charsets.UTF_8)
        return publishAnswer(code, text).getOrThrow()
    } finally {
        conn.disconnect()
    }
}

private fun InputStream.readBytesCapped(max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (out.size() < max) {
        val n = read(buf, 0, minOf(buf.size, max - out.size()))
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

/** How long the node may stay silent on an upload: it answers once every chunk is pushed. */
internal const val PUBLISH_READ_TIMEOUT_MS = 30 * 60_000

/** What the user picked to publish. */
internal sealed interface PublishSource {
    data class OneFile(val uri: Uri) : PublishSource
    data class Folder(val treeUri: Uri) : PublishSource
    data class Text(val text: String) : PublishSource
}

/**
 * What a picked source is, read before asking the user to confirm: its
 * name, the bytes it holds and what it would take out of a stamp.
 */
internal data class PublishPlan(
    val source: PublishSource,
    val kind: PublishKind,
    val name: String,
    /** The bytes (null when a file's provider doesn't say). */
    val bytes: Long?,
    val stampBytes: Long,
    val files: List<FolderFile> = emptyList(),
)

/** Reads what [source] is. Blocking; throws [PublishException]. */
internal fun planPublish(resolver: ContentResolver, source: PublishSource): PublishPlan = when (source) {
    is PublishSource.Text -> {
        val size = source.text.toByteArray(Charsets.UTF_8).size.toLong()
        if (size == 0L) throw PublishException("There's no text to publish")
        if (size > MAX_PUBLISH_BYTES) throw PublishException("The text is bigger than ${formatStampBytes(MAX_PUBLISH_BYTES)}")
        PublishPlan(source, PublishKind.Text, "Text", size, publishStampEstimate(listOf(size)))
    }
    is PublishSource.OneFile -> {
        val (name, provided) = fileInfo(resolver, source.uri)
        if (provided != null && provided > MAX_PUBLISH_BYTES) throw PublishException(fileTooBig())
        // A provider that doesn't say how big it is: read it through, so
        // the stamp is picked (and confirmed) for its real size.
        val size = provided ?: (
            resolver.openInputStream(source.uri)?.use { measureCapped(it, tooBig = fileTooBig()) }
                ?: throw PublishException("The file couldn't be read")
            )
        PublishPlan(source, PublishKind.File, name, size, publishStampEstimate(listOf(size)))
    }
    is PublishSource.Folder -> {
        val files = listFolder(resolver, source.treeUri)
        PublishPlan(
            source, PublishKind.Folder, folderName(resolver, source.treeUri),
            files.sumOf { it.size }, publishStampEstimate(files.map { it.size }), files,
        )
    }
}

/**
 * The publish in flight — one at a time, held here rather than by the
 * page, so it carries on when the page is left — and the last one's
 * outcome until the page has shown it.
 */
internal object Publisher {
    sealed interface State {
        data object Idle : State
        data class Running(val recordId: String, val name: String, val kind: PublishKind) : State
        data class Finished(val recordId: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Publishes [plan] with [batch], as the user confirmed; false if a
     * publish is already running. The history gets the record at once.
     */
    fun start(context: Context, plan: PublishPlan, batch: PostageBatch): Boolean {
        val batchId = batch.id
        val app = context.applicationContext
        val history = PublishHistory.get(app)
        val record: PublishRecord
        synchronized(this) {
            if (_state.value is State.Running) return false
            record = history.start(plan.kind, plan.name, plan.bytes)
            _state.value = State.Running(record.id, plan.name, plan.kind)
        }
        scope.launch {
            try {
                val (reference, bytes) = run(app, plan, batch)
                history.completed(record.id, reference, batchId, bytes)
            } catch (e: PublishException) {
                history.failed(record.id, e.message ?: "The upload failed")
            } catch (t: Throwable) {
                Log.w(TAG, "publish failed: ${t.javaClass.simpleName}")
                history.failed(record.id, "The upload failed")
            } finally {
                releaseGrant(app, plan.source)
                // Its record removed from the history meanwhile (Remove or
                // Clear all): there's no outcome left to show.
                synchronized(this@Publisher) { _state.value = finishedState(record.id, history.records.value) }
            }
        }
        return true
    }

    /** Forget a finished publish once its outcome has been shown. */
    fun acknowledge() {
        _state.value.let { if (it is State.Finished) _state.compareAndSet(it, State.Idle) }
    }

    /**
     * After records were removed from the history: a finished publish
     * whose record went with them has no outcome to show, so it's
     * forgotten as if acknowledged.
     */
    fun forgetRemoved(records: List<PublishRecord>) = synchronized(this) {
        _state.value.let { if (it is State.Finished) _state.value = finishedState(it.recordId, records) }
    }

    /** What a finished publish of [recordId] leaves: its outcome to show, or nothing once its record is gone. */
    internal fun finishedState(recordId: String, records: List<PublishRecord>): State =
        if (records.any { it.id == recordId }) State.Finished(recordId) else State.Idle

    /**
     * Stages [plan] in a file (a tar for a folder), so its exact length is
     * known up front and capped, and uploads it. Returns the reference and
     * the bytes published.
     */
    private fun run(app: Context, plan: PublishPlan, batch: PostageBatch): Pair<String, Long> {
        val batchId = batch.id
        val resolver = app.contentResolver
        val dir = stagingDir(app)
        dir.mkdirs()
        val staged = File(dir, UUID.randomUUID().toString())
        try {
            var published = 0L
            val sizes = mutableListOf<Long>()
            val request = when (val s = plan.source) {
                is PublishSource.Text -> {
                    staged.writeBytes(s.text.toByteArray(Charsets.UTF_8))
                    published = staged.length()
                    sizes += published
                    PublishRequest(PublishKind.Text, batchId, TEXT_FILE_NAME, "text/plain; charset=utf-8", null)
                }
                is PublishSource.OneFile -> {
                    val input = resolver.openInputStream(s.uri) ?: throw PublishException("The file couldn't be read")
                    input.use { copyCapped(it, staged) }
                    published = staged.length()
                    sizes += published
                    PublishRequest(PublishKind.File, batchId, plan.name, contentTypeFor(plan.name, resolver.getType(s.uri)), null)
                }
                is PublishSource.Folder -> {
                    RandomAccessFile(staged, "rw").use { raf ->
                        val tar = TarWriter(raf, MAX_PUBLISH_BYTES)
                        plan.files.forEach { f ->
                            val uri = DocumentsContract.buildDocumentUriUsingTree(s.treeUri, f.documentId)
                            val input = resolver.openInputStream(uri) ?: throw PublishException("${f.path} couldn't be read")
                            val n = input.use { tar.add(f.path, it) }
                            sizes += n
                            published += n
                        }
                        tar.finish()
                    }
                    PublishRequest(PublishKind.Folder, batchId, null, null, indexDocumentFor(plan.files.map { it.path }))
                }
            }
            // What was read may differ from what was listed (a provider
            // that gave no or a wrong size, a file changed since): check
            // the exact bytes against the stamp before anything goes out.
            publishStampEstimate(sizes).let { need ->
                if (!batchHasRoom(batch, need)) {
                    throw PublishException(
                        "It turned out bigger than the stamp ${shortBatchId(batchId)} has room for " +
                            "(${formatStampBytes(need)}, with a margin). Buy a bigger one under Postage stamps.",
                    )
                }
            }
            val reference = uploadToGateway(SwarmNode.GATEWAY_URL, request, { staged.inputStream() }, staged.length())
            return reference to published
        } catch (e: SecurityException) {
            throw PublishException("Freedom may no longer read what you picked; pick it again")
        } catch (e: java.io.FileNotFoundException) {
            throw PublishException("What you picked couldn't be read")
        } catch (e: IOException) {
            throw PublishException(if (e is java.net.ConnectException) "The Swarm node isn't running" else "The upload failed: couldn't reach the Swarm node")
        } finally {
            staged.delete()
        }
    }

    private fun copyCapped(input: InputStream, to: File) {
        FileOutputStream(to).use { out ->
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_PUBLISH_BYTES) throw PublishException(fileTooBig())
                out.write(buf, 0, n)
            }
        }
    }

    /**
     * Staged uploads live here only while they're sent. One left by a run
     * that ended mid-upload goes at the next start.
     */
    fun stagingDir(context: Context) = File(context.applicationContext.cacheDir, "publish")

    /** Clears what an earlier run left in [stagingDir]; nothing is staged while no publish runs. */
    fun sweepStaging(context: Context) {
        PublishGrants.sweep(context)
        if (_state.value is State.Running) return
        stagingDir(context).listFiles()?.forEach { it.delete() }
    }

    const val TEXT_FILE_NAME = "text.txt"
    private const val TAG = "Publisher"
}
