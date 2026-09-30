package baby.freedom.mobile.browser

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.result.contract.ActivityResultContract
import baby.freedom.mobile.data.DownloadEntry
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

    /** Keep the picker's grant on [uri] past the activity that got it. */
    fun hold(context: Context, uri: Uri) {
        runCatching { context.contentResolver.takePersistableUriPermission(uri, MODES) }
            // A provider that doesn't offer persistable grants still
            // works while the activity lives; only a download outliving
            // it can't write there.
            .onFailure { Log.w(LOG_TAG, "no persistable grant on the picked document", it) }
    }

    /** Give back the grant on [uri]; the download that held it has ended. */
    fun release(context: Context, uri: Uri) {
        val persisted = runCatching { context.contentResolver.persistedUriPermissions }.getOrDefault(emptyList())
        if (persisted.none { it.uri == uri }) return
        runCatching { context.contentResolver.releasePersistableUriPermission(uri, MODES) }
    }

    /**
     * An unfinished download that won't finish ends here: its document
     * goes if nothing was ever written into it — the empty file the
     * picker created — and its grant is given back. A document with
     * bytes in it is left alone: the user may have picked an existing
     * file to replace, and a download that didn't finish never touched
     * it ([truncate] empties one whose copy failed part-way first).
     */
    fun discard(context: Context, uri: Uri) {
        if (documentSize(context.contentResolver, uri) == 0L) {
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
     * The picked document, opened to be written from its start: `wt`
     * truncates what's there (a copy that failed part-way before), and a
     * provider that doesn't know `wt` gets plain `w`.
     */
    fun openForWriting(resolver: ContentResolver, uri: Uri): OutputStream? =
        try {
            resolver.openOutputStream(uri, "wt")
        } catch (_: IllegalArgumentException) {
            resolver.openOutputStream(uri, "w")
        } catch (_: UnsupportedOperationException) {
            resolver.openOutputStream(uri, "w")
        }

    /** Empty the picked document after a copy into it failed part-way, so [discard] may delete it. */
    fun truncate(resolver: ContentResolver, uri: Uri) {
        runCatching { openForWriting(resolver, uri)?.close() }
    }

    /** [uri]'s size, or null when the provider won't say. */
    private fun documentSize(resolver: ContentResolver, uri: Uri): Long? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    }.getOrNull()
}

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
