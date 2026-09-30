package baby.freedom.mobile.browser

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.activity.result.contract.ActivityResultContract
import baby.freedom.mobile.data.DownloadEntry
import java.io.FileNotFoundException
import java.io.OutputStream

private const val LOG_TAG = "Downloads"

/**
 * *Ask where to save each file* (#322): the user picks a name and folder
 * in the system's *Save as* picker (`ACTION_CREATE_DOCUMENT`) and the
 * finished file is written through the URI it returns
 * ([DownloadEntry.saveTo]) instead of into `Download/Freedom`.
 *
 * Nothing else about a download changes: the bytes still stream into
 * the partial file in app storage (pause/resume, the free-space floor
 * and the notification work on that, #265), and only a complete file is
 * copied into the picked document — the way a normal one is copied into
 * its pending Downloads item.
 *
 * The picker's grant on the document lasts only as long as the activity
 * that got it, while a download outlives it (and a paused one outlives
 * the process). So a persistable grant is taken on the pick and held for
 * as long as the download is unfinished; it's given back the moment the
 * download ends — completed, failed, cancelled or removed — and any left
 * behind by a run that died is swept at the next launch
 * ([DownloadManager]'s startup sweep, which keeps only paused rows').
 *
 * Grants are told apart from the publish flow's by their mode: a publish
 * only ever holds *read* grants ([PublishGrants]), a download always
 * holds *write* ones, and each sweep leaves the other's alone.
 */
internal object DownloadSaveTo {
    /** The modes a download's grant is taken and given back with. */
    private const val MODES = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    /**
     * Keep the picker's grant on [uri] past the activity that got it,
     * and look at what was picked: an empty file the picker just
     * created ([PickedDocument.created]), or an existing one to replace.
     *
     * [PickedDocument.lasting] is false when no lasting grant could be
     * taken (a provider that doesn't offer persistable grants): then the
     * download couldn't write there — nor delete the file again — once
     * the activity is gone, so the caller must not start it. Call off
     * the main thread: it asks the provider.
     */
    fun hold(context: Context, uri: Uri): PickedDocument {
        val resolver = context.contentResolver
        val created = pickedDocumentIsNew(
            size = documentSize(resolver, uri),
            lastModified = documentLastModified(resolver, uri),
            now = System.currentTimeMillis(),
        )
        val lasting = runCatching { resolver.takePersistableUriPermission(uri, MODES) }
            .onFailure { Log.w(LOG_TAG, "no persistable grant on the picked document", it) }
            .isSuccess || ownMediaItem(context, uri)
        return PickedDocument(uri.toString(), created, lasting)
    }

    /**
     * A `MediaStore` item Freedom created itself (the device tests' stand-in
     * for a pick) is Freedom's to write for good, with no grant.
     */
    private fun ownMediaItem(context: Context, uri: Uri): Boolean =
        uri.authority == MediaStore.AUTHORITY && runCatching {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.OWNER_PACKAGE_NAME), null, null, null)
                ?.use { c -> c.moveToFirst() && c.getString(0) == context.packageName }
        }.getOrNull() == true

    /** Give back the grant on [uri]; the download that held it has ended. */
    fun release(context: Context, uri: Uri) {
        val persisted = runCatching { context.contentResolver.persistedUriPermissions }.getOrDefault(emptyList())
        if (persisted.none { it.uri == uri }) return
        runCatching { context.contentResolver.releasePersistableUriPermission(uri, MODES) }
    }

    /**
     * An unfinished download that won't finish ends here: its document
     * goes if the picker [created] it for this download and nothing is
     * in it, and its grant is given back. A file the user picked to
     * replace is never deleted — it was theirs before the download.
     * (Its old content is gone once a copy into it has started, though:
     * `wt` empties it, and a copy that fails part-way leaves it empty,
     * see [truncate].)
     */
    fun discard(context: Context, uri: Uri, created: Boolean) {
        if (created && documentSize(context.contentResolver, uri) == 0L) {
            runCatching {
                if (DocumentsContract.isDocumentUri(context, uri)) {
                    DocumentsContract.deleteDocument(context.contentResolver, uri)
                } else {
                    context.contentResolver.delete(uri, null, null)
                }
            }
                .onFailure { Log.w(LOG_TAG, "couldn't delete the picked document", it) }
        }
        release(context, uri)
    }

    /**
     * Gives back every download grant (a persisted *write* grant) not in
     * [keep] — the documents paused downloads will still save into.
     */
    fun sweep(context: Context, keep: Set<String>) {
        val persisted = runCatching { context.contentResolver.persistedUriPermissions }.getOrDefault(emptyList())
        val stale = staleDownloadGrants(persisted.map { PersistedGrant(it.uri.toString(), it.isWritePermission) }, keep)
        persisted.filter { it.uri.toString() in stale }.forEach {
            runCatching { context.contentResolver.releasePersistableUriPermission(it.uri, MODES) }
        }
    }

    /**
     * The picked document, opened to be written from its start and
     * emptied first: `wt`. A provider that refuses `wt` gets plain `w`,
     * which doesn't truncate on a file-backed provider, so the file is
     * truncated by hand ([truncating]) — and when it can't be (a pipe)
     * while there's something in the document, the open fails rather
     * than leave the old file's tail after the new bytes.
     */
    fun openForWriting(resolver: ContentResolver, uri: Uri): OutputStream? {
        try {
            return resolver.openOutputStream(uri, "wt")
        } catch (_: IllegalArgumentException) {
        } catch (_: UnsupportedOperationException) {
        } catch (_: FileNotFoundException) {
            // Some providers refuse an unknown mode this way; a document
            // that's really gone fails the `w` open below just the same.
        }
        val fd = resolver.openFileDescriptor(uri, "w") ?: return null
        return truncating(fd) { documentSize(resolver, uri) }
    }

    /**
     * [fd], emptied and wrapped for writing from its start. [size] is
     * asked only when [fd] can't be truncated (a pipe, not a file): an
     * empty document (0) is fine to stream into; one with anything in it,
     * or whose size isn't known, isn't — [FileNotFoundException].
     */
    internal fun truncating(fd: ParcelFileDescriptor, size: () -> Long?): OutputStream {
        val truncated = runCatching {
            if (!OsConstants.S_ISREG(Os.fstat(fd.fileDescriptor).st_mode)) error("not a file")
            Os.ftruncate(fd.fileDescriptor, 0)
            Os.lseek(fd.fileDescriptor, 0, OsConstants.SEEK_SET)
        }.isSuccess
        if (!truncated && size() != 0L) {
            runCatching { fd.close() }
            throw FileNotFoundException("the picked document can't be emptied to write it afresh")
        }
        return ParcelFileDescriptor.AutoCloseOutputStream(fd)
    }

    /** Empty the picked document after a copy into it failed part-way, so [discard] may delete it. */
    fun truncate(resolver: ContentResolver, uri: Uri) {
        runCatching { openForWriting(resolver, uri)?.close() }
    }

    /**
     * How many bytes [uri]'s document can grow to: the free space of the
     * volume it's on plus what it holds now (a rewrite frees that). Null
     * when that can't be told — a provider that streams through a pipe
     * rather than handing out the file — which doesn't block the copy
     * (running out of room while copying still pauses the download).
     * Asked of the document itself, not of the primary shared volume: a
     * pick on an SD card has that card's room, not internal storage's.
     */
    fun roomFor(resolver: ContentResolver, uri: Uri): Long? = runCatching {
        resolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val st = Os.fstat(pfd.fileDescriptor)
            if (!OsConstants.S_ISREG(st.st_mode)) return@use null
            val vfs = Os.fstatvfs(pfd.fileDescriptor)
            vfs.f_bavail * vfs.f_frsize + st.st_size
        }
    }.getOrNull()

    /** [uri]'s size, or null when the provider won't say. */
    private fun documentSize(resolver: ContentResolver, uri: Uri): Long? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    }.getOrNull()

    /** [uri]'s last-modified time (epoch ms), or null when the provider won't say. */
    private fun documentLastModified(resolver: ContentResolver, uri: Uri): Long? = runCatching {
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    }.getOrNull()
}

/**
 * A document picked in the *Save as* picker, as [DownloadSaveTo.hold]
 * found it. [created]: the picker made it for this download (an empty
 * new file), so a download that doesn't finish may delete it again;
 * false for an existing file picked to replace. [lasting]: Freedom can
 * still write it once the activity that picked it is gone.
 */
internal data class PickedDocument(val uri: String, val created: Boolean, val lasting: Boolean = true)

/** How long after it was last modified an empty picked file still counts as just created. */
internal const val PICKED_NEW_WINDOW_MS = 5 * 60 * 1000L

/**
 * Whether a picked document is the empty file the picker just created,
 * rather than an existing file the user picked to replace: it's empty
 * ([size] 0; unknown counts as not) and was modified only just now —
 * an existing empty file last touched long ago is the user's, and stays.
 * A provider that doesn't report the time can't tell the two apart; an
 * empty document from it is taken as new, so the picker's own file
 * doesn't outlive a download that failed.
 */
internal fun pickedDocumentIsNew(size: Long?, lastModified: Long?, now: Long): Boolean =
    size == 0L && (lastModified == null || now - lastModified in -60_000L..PICKED_NEW_WINDOW_MS)

/** One of `ContentResolver.persistedUriPermissions`, as far as the sweeps need it. */
internal data class PersistedGrant(val uri: String, val write: Boolean)

/** Of the [persisted] grants, the downloads' (write) ones not in [keep]. */
internal fun staleDownloadGrants(persisted: List<PersistedGrant>, keep: Set<String>): Set<String> =
    persisted.filter { it.write && it.uri !in keep }.mapTo(LinkedHashSet()) { it.uri }

/** What the *Save as* picker is opened with: the suggested name and type. */
internal data class SaveAsRequest(val fileName: String, val mimeType: String)

/**
 * The system's *Save as* picker (`ACTION_CREATE_DOCUMENT`) with the
 * download's suggested name; answers the created document, or null
 * when the user backed out.
 */
internal class SaveAsContract : ActivityResultContract<SaveAsRequest, Uri?>() {
    override fun createIntent(context: Context, input: SaveAsRequest): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(saveAsMimeType(input.mimeType))
            .putExtra(Intent.EXTRA_TITLE, input.fileName)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        intent?.data?.takeIf { resultCode == android.app.Activity.RESULT_OK }
}

/**
 * The type the picker creates the document with. A blank or wildcard
 * type isn't one a provider can create (DocumentsUI refuses `*` types),
 * so those become `application/octet-stream`.
 */
internal fun saveAsMimeType(mime: String?): String =
    mime?.trim()?.takeIf { it.isNotEmpty() && '*' !in it && '/' in it } ?: "application/octet-stream"

/**
 * Whether a download should open the *Save as* picker: the setting is
 * on, and it isn't a private tab's (#86) — those always go to
 * `Download/Freedom`, so the picker (whose recent locations and names
 * outlive the session) keeps no trace of them, and no grant is held
 * for a list that only lives in memory.
 */
internal fun asksWhereToSave(setting: Boolean, private: Boolean): Boolean = setting && !private
