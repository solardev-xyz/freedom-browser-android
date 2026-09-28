package baby.freedom.mobile.browser

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import android.webkit.ValueCallback
import android.webkit.WebChromeClient.FileChooserParams
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * `<input type=file>` (#80).
 *
 * Without a `WebChromeClient.onShowFileChooser` override WebView ignores
 * the tap on a file input entirely — the picker never opens and the
 * form can't be completed. [FileChooser] answers it with one of two
 * system activities:
 *
 *  - the document picker (`ACTION_GET_CONTENT`), filtered by the
 *    input's `accept` and allowing several files for `multiple`;
 *  - the camera (`ACTION_IMAGE_CAPTURE` / `ACTION_VIDEO_CAPTURE`) when
 *    the input carries `capture` and accepts images or video — the
 *    photo lands in a private cache file served to the page through
 *    our [FileProvider].
 *
 * Backing out of either delivers `null`, which is how WebView tells
 * the page "nothing selected": it fires the input's `cancel` event and
 * frees the input for the next tap. (Chromium also clears an earlier
 * selection on that input, as Chrome for Android does.)
 *
 * Capture is delegated to the camera app, which holds its own camera
 * permission. But the browser declares `CAMERA` too (site permissions,
 * #81), and Android refuses `ACTION_IMAGE_CAPTURE` /
 * `ACTION_VIDEO_CAPTURE` with a `SecurityException` from an app that
 * declares `CAMERA` without holding it. So, as Chrome does, a capture
 * input asks for the permission first ([cameraGateFor]); if the user
 * refuses, the input gets the document picker instead.
 *
 * That request goes through the site-permission broker's Android-dialog
 * path ([SitePermissionBroker.requestUploadCamera]), not a launcher of
 * its own, so it follows the same rules as a site's camera request: one
 * system dialog at a time, only over the page that asked, and a refusal
 * is recorded so a later permanent one is recognised. When Android has
 * refused the camera for good, the picker opens and, once it closes,
 * the user is told where to turn the camera back on.
 *
 * Nothing opens unless the requesting tab's page is on screen
 * ([SitePermissionBroker.isOnScreen]): a page's delayed `click()` on
 * its input must not pop a picker, camera or system dialog over
 * Settings, the tab switcher, or another app.
 */
internal class FileChooser(
    private val context: Context,
    private val permissions: SitePermissionBroker,
) {

    /** Set from composition; see [rememberFileChooser]. */
    var launcher: ActivityResultLauncher<Intent>? = null

    /** A capture input waiting on the `CAMERA` permission dialog. */
    private class AwaitingCamera(
        val callback: ValueCallback<Array<Uri>>,
        val kind: CaptureKind,
        val pickerTypes: List<String>,
        val multiple: Boolean,
    )

    /** The capture waiting on [SitePermissionBroker.requestUploadCamera], if any. */
    private var awaitingCamera: AwaitingCamera? = null

    private class Pending(
        val callback: ValueCallback<Array<Uri>>,
        val multiple: Boolean,
        /** Camera output target, or null for the document picker. */
        val captureFile: File?,
        val captureUri: Uri?,
        /**
         * The picker stands in for a capture Android refused `CAMERA`
         * for; say so once the picker closes.
         */
        val cameraRefused: Boolean = false,
    )

    private var pending: Pending? = null

    /** Entry point for `WebChromeClient.onShowFileChooser`. */
    fun show(tabId: Long, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
        val launcher = launcher ?: return false
        // Only over the page that asked (returning false frees the input).
        if (!permissions.isOnScreen(tabId)) return false
        // WebView won't open a second chooser before the first one is
        // answered, but a WebView from another tab could; don't leave
        // the earlier page's input wedged.
        cancelPending()

        val accept = parseAccept(params.acceptTypes) { ext ->
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        }
        val multiple = params.mode == FileChooserParams.MODE_OPEN_MULTIPLE

        val capture = captureKindFor(params.isCaptureEnabled, accept)
        if (capture != null) {
            val gate = cameraGateFor(
                declared = declaresCamera,
                granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED,
            )
            when (gate) {
                CameraGate.LAUNCH -> if (tryCapture(launcher, callback, capture)) return true
                CameraGate.ASK -> {
                    val ask = AwaitingCamera(callback, capture, accept.pickerTypes, multiple)
                    awaitingCamera = ask
                    permissions.requestUploadCamera(tabId) { onCameraPermission(ask, it) }
                    return true
                }
            }
        }
        return launchPicker(launcher, callback, accept.pickerTypes, multiple)
    }

    /**
     * The `CAMERA` request [show] made for [ask] has ended: on a grant,
     * the capture it was for; on a refusal (or if the camera can't
     * start after all) the document picker, as for an input without
     * `capture`; and nothing at all if the page is no longer on screen.
     * An [ask] already cancelled (and answered) is ignored.
     */
    private fun onCameraPermission(ask: AwaitingCamera, outcome: AndroidPermissionAsk) {
        if (awaitingCamera !== ask) return
        awaitingCamera = null
        val launcher = launcher
        val refused = outcome == AndroidPermissionAsk.REFUSED
        val started = launcher != null && outcome != AndroidPermissionAsk.OFF_SCREEN && (
            (outcome == AndroidPermissionAsk.GRANTED && tryCapture(launcher, ask.callback, ask.kind)) ||
                launchPicker(launcher, ask.callback, ask.pickerTypes, ask.multiple, cameraRefused = refused)
            )
        // show() already told WebView the callback is ours, so it must be answered.
        if (!started) {
            runCatching { ask.callback.onReceiveValue(null) }
            if (refused) permissions.noteUploadCameraRefused()
        }
    }

    /** Whether the manifest declares `CAMERA` (see [cameraGateFor]). */
    private val declaresCamera: Boolean by lazy {
        runCatching {
            context.packageManager
                .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions
                ?.contains(Manifest.permission.CAMERA) == true
        }.getOrDefault(true)
    }

    /** Start the camera for [kind]; false (having logged why) if it can't start. */
    private fun tryCapture(
        launcher: ActivityResultLauncher<Intent>,
        callback: ValueCallback<Array<Uri>>,
        kind: CaptureKind,
    ): Boolean = runCatching { launchCapture(launcher, callback, kind) }
        .onFailure { Log.w(LOG_TAG, "camera capture unavailable, using picker", it) }
        .getOrDefault(false)

    private fun launchPicker(
        launcher: ActivityResultLauncher<Intent>,
        callback: ValueCallback<Array<Uri>>,
        pickerTypes: List<String>,
        multiple: Boolean,
        cameraRefused: Boolean = false,
    ): Boolean {
        val picker = pickerIntent(pickerTypes, multiple)
        pending = Pending(callback, multiple, captureFile = null, captureUri = null, cameraRefused)
        return try {
            launcher.launch(picker)
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(LOG_TAG, "no document picker", e)
            pending = null
            // Returning false tells WebView we didn't take the callback;
            // it resets the input itself.
            false
        }
    }

    private fun launchCapture(
        launcher: ActivityResultLauncher<Intent>,
        callback: ValueCallback<Array<Uri>>,
        kind: CaptureKind,
    ): Boolean {
        val file = newCaptureFile(kind) ?: return false
        try {
            return launchCaptureInto(launcher, callback, kind, file)
        } catch (e: Exception) {
            // Whatever the failure (no camera app, a camera that throws
            // SecurityException, a FileProvider mismatch), don't leave
            // the empty placeholder, a grant or a stale request behind;
            // show() falls back to the picker.
            val p = pending?.takeIf { it.captureFile == file }
            if (p != null) {
                pending = null
                p.captureUri?.let(::revokeCaptureGrant)
            }
            file.delete()
            if (e is ActivityNotFoundException) return false
            throw e
        }
    }

    private fun launchCaptureInto(
        launcher: ActivityResultLauncher<Intent>,
        callback: ValueCallback<Array<Uri>>,
        kind: CaptureKind,
        file: File,
    ): Boolean {
        val uri = FileProvider.getUriForFile(context, authority(context), file)
        val action = when (kind) {
            CaptureKind.IMAGE -> MediaStore.ACTION_IMAGE_CAPTURE
            CaptureKind.VIDEO -> MediaStore.ACTION_VIDEO_CAPTURE
        }
        val intent = Intent(action)
            .putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        // The grant flags only reach the camera app through ClipData.
        intent.clipData = ClipData.newRawUri(null, uri)
        pending = Pending(callback, multiple = false, captureFile = file, captureUri = uri)
        launcher.launch(intent)
        return true
    }

    /** Result of whichever activity [show] started. */
    fun onResult(result: ActivityResult) {
        val p = pending ?: return
        pending = null
        val uris: Array<Uri>? = if (p.captureFile != null) {
            captureResult(p, result)
        } else if (result.resultCode == android.app.Activity.RESULT_OK) {
            val data = result.data
            val clip = data?.clipData
            val clipUris = if (clip == null) emptyList()
            else (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
            pickedUris(clipUris, data?.data, p.multiple, ::isUploadable)
                .takeIf { it.isNotEmpty() }
                ?.toTypedArray()
        } else {
            null
        }
        // The requesting tab may have been closed (its WebView destroyed)
        // while the activity was open.
        runCatching { p.callback.onReceiveValue(uris) }
            .onFailure { Log.w(LOG_TAG, "file chooser callback failed", it) }
        // Back on the page now, where the notice can be seen.
        if (p.cameraRefused) permissions.noteUploadCameraRefused()
    }

    /**
     * Whether a URI handed back by another app may be read and uploaded
     * with the browser's identity: see [isUploadableUri].
     */
    private fun isUploadable(uri: Uri): Boolean =
        isUploadableUri(uri.scheme, uri.authority, ::isOwnAuthority)

    /**
     * True for an authority served by one of the browser's own content
     * providers — our [FileProvider] (earlier captures in cache/uploads)
     * or any provider a library merged into the manifest. WebView reads
     * those as the browser, so accepting one would let a picker or
     * camera app make us upload our own private data.
     */
    private fun isOwnAuthority(authority: String): Boolean {
        if (authority.equals(authority(context), ignoreCase = true)) return true
        val info = runCatching {
            context.packageManager.resolveContentProvider(authority, 0)
        }.getOrNull()
        return info?.packageName == context.packageName
    }

    private fun captureResult(p: Pending, result: ActivityResult): Array<Uri>? =
        finishCapture(
            file = p.captureFile!!,
            captureUri = p.captureUri!!,
            ok = result.resultCode == android.app.Activity.RESULT_OK,
            returned = result.data?.data,
            uploadable = ::isUploadable,
            revoke = ::revokeCaptureGrant,
        )?.let { arrayOf(it) }

    /**
     * Take back the read/write grant the camera app got on [uri]. The
     * camera runs in the browser's task, so the grant would otherwise
     * outlive the capture for as long as that task does — long enough
     * for the camera app to rewrite the file the page reads lazily
     * (up to [CAPTURE_MAX_AGE_MS] later). Our own reads (WebView reads
     * as the provider's owner) don't depend on any grant.
     */
    private fun revokeCaptureGrant(uri: Uri) {
        runCatching {
            context.revokeUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.onFailure { Log.w(LOG_TAG, "can't revoke capture grant", it) }
    }

    /** Answer any open request with "nothing selected". */
    fun cancelPending() {
        awaitingCamera?.let { ask ->
            awaitingCamera = null
            runCatching { ask.callback.onReceiveValue(null) }
        }
        val p = pending ?: return
        pending = null
        p.captureUri?.let(::revokeCaptureGrant)
        p.captureFile?.delete()
        runCatching { p.callback.onReceiveValue(null) }
    }

    /**
     * Delete every camera capture in cache/uploads — part of "clear
     * browsing data": they're served by our [FileProvider] and would
     * otherwise linger until the next capture's age-based prune (or
     * forever, if none follows). WebView's `clearCache` only covers
     * Chromium's own cache directory. The target of a capture still in
     * progress is kept so the camera app has somewhere to write.
     */
    fun clearCaptures() {
        deleteCaptures(File(context.cacheDir, CAPTURE_DIR), keep = pending?.captureFile)
    }

    private fun newCaptureFile(kind: CaptureKind): File? {
        val dir = File(context.cacheDir, CAPTURE_DIR)
        if (!dir.isDirectory && !dir.mkdirs()) return null
        // A page reads an uploaded file lazily (on submit, or whenever
        // its script gets to it), so a capture can't be deleted as soon
        // as it's been handed over. Anything from a previous day is
        // long done with; cacheDir is also reclaimable by the system.
        val cutoff = System.currentTimeMillis() - CAPTURE_MAX_AGE_MS
        dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
        // The name is what the page (and the site's server) sees, so
        // make it the familiar camera-style one, not temp-file digits.
        val (prefix, ext) = if (kind == CaptureKind.IMAGE) "IMG" to "jpg" else "VID" to "mp4"
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return try {
            generateSequence(0) { it + 1 }
                .map { n -> File(dir, if (n == 0) "${prefix}_$stamp.$ext" else "${prefix}_${stamp}_$n.$ext") }
                .first { it.createNewFile() }
        } catch (e: IOException) {
            Log.w(LOG_TAG, "can't create capture file", e)
            null
        }
    }

    companion object {
        private const val LOG_TAG = "FileChooser"

        /** Subdirectory of `cacheDir`; must match `res/xml/file_paths.xml`. */
        const val CAPTURE_DIR = "uploads"
        private const val CAPTURE_MAX_AGE_MS = 24L * 60 * 60 * 1000

        fun authority(context: Context) = "${context.packageName}.files"
    }
}

/**
 * The [FileChooser] for this composition, wired to an activity-result
 * launcher. Any request still open when the host leaves composition is
 * cancelled so the page's input doesn't stay locked.
 */
@Composable
internal fun rememberFileChooser(): FileChooser {
    val context = LocalContext.current
    val chooser = remember {
        FileChooser(context.applicationContext, SitePermissionBroker.get(context))
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { chooser.onResult(it) }
    SideEffect { chooser.launcher = launcher }
    DisposableEffect(chooser) {
        onDispose {
            chooser.cancelPending()
            chooser.launcher = null
        }
    }
    return chooser
}

internal enum class CaptureKind { IMAGE, VIDEO }

/** What a capture input does before the camera can start; see [cameraGateFor]. */
internal enum class CameraGate {
    /** Start the camera app now. */
    LAUNCH,

    /** Ask for `CAMERA` first; the camera on a grant, else the picker. */
    ASK,
}

/**
 * Android refuses `ACTION_IMAGE_CAPTURE` / `ACTION_VIDEO_CAPTURE` from
 * an app that [declared] `CAMERA` in its manifest but hasn't been
 * [granted] it (`SecurityException`, "revoked permission"). An app that
 * doesn't declare it can start the camera app freely. So with the
 * permission declared and not held, a capture asks for it first rather
 * than attempting a launch that can only fail. There is no "can't ask"
 * case here: when no screen is composed to ask with, the ask itself
 * ends [AndroidPermissionAsk.REFUSED] ([askAndroidPermissionOnScreen])
 * and the input gets the picker like any other refusal.
 */
internal fun cameraGateFor(declared: Boolean, granted: Boolean): CameraGate =
    if (!declared || granted) CameraGate.LAUNCH else CameraGate.ASK

/**
 * The upload for a finished camera capture into [file] (served to the
 * camera as [captureUri]), or null for none. Whatever the outcome, the
 * camera app's grant on [captureUri] is [revoke]d first: from here on
 * only the browser may touch the file, so what the page uploads is
 * what the user reviewed. An empty [file] is deleted.
 *
 * On [ok] a written [file] is the capture; some camera apps ignore
 * EXTRA_OUTPUT (more often for video) and hand back their own content
 * URI as [returned] instead, taken if [uploadable].
 */
internal fun <U : Any> finishCapture(
    file: File,
    captureUri: U,
    ok: Boolean,
    returned: U?,
    uploadable: (U) -> Boolean,
    revoke: (U) -> Unit,
): U? {
    revoke(captureUri)
    if (ok && file.length() > 0) return captureUri
    file.delete()
    if (ok && returned != null && uploadable(returned)) return returned
    return null
}

/**
 * Delete the files in the capture directory [dir], except [keep]. A
 * missing directory is fine (nothing captured yet). Returns how many
 * files could not be deleted.
 */
internal fun deleteCaptures(dir: File, keep: File?): Int {
    val keepPath = keep?.absoluteFile
    return dir.listFiles().orEmpty()
        .filter { it.absoluteFile != keepPath }
        .count { !it.deleteRecursively() }
}

/**
 * An `accept` attribute, as WebView hands it over in
 * [FileChooserParams.getAcceptTypes] (the attribute split on commas —
 * entries may carry whitespace, be empty, or be file extensions like
 * `.pdf`), parsed with extensions mapped through `extensionToMime`.
 *
 * [named] is every MIME type the attribute names that we could map;
 * [unmapped] is whether some extension (e.g. `.gpx`) couldn't be.
 */
internal class AcceptTypes(val named: List<String>, val unmapped: Boolean) {
    /** No restriction at all: no `accept`, or a wildcard in it. */
    val anything: Boolean get() = (named.isEmpty() && !unmapped) || "*/*" in named

    /**
     * The picker filter (empty = anything). An unmappable extension
     * widens it to anything — filtering to only the mappable types
     * would hide files the page explicitly accepts.
     */
    val pickerTypes: List<String> get() = if (anything || unmapped) emptyList() else named
}

internal fun parseAccept(
    acceptTypes: Array<String>?,
    extensionToMime: (String) -> String?,
): AcceptTypes {
    if (acceptTypes == null) return AcceptTypes(emptyList(), unmapped = false)
    val out = LinkedHashSet<String>()
    var unmapped = false
    for (raw in acceptTypes.flatMap { it.split(',') }) {
        val entry = raw.trim().lowercase()
        when {
            entry.isEmpty() -> Unit
            entry.startsWith('.') ->
                extensionToMime(entry.substring(1))?.let { out += it } ?: run { unmapped = true }
            entry.contains('/') -> out += entry
        }
    }
    return AcceptTypes(out.toList(), unmapped)
}

/** The picker's MIME filter for an `accept` attribute; see [AcceptTypes.pickerTypes]. */
internal fun mimeTypesForAccept(
    acceptTypes: Array<String>?,
    extensionToMime: (String) -> String?,
): List<String> = parseAccept(acceptTypes, extensionToMime).pickerTypes

/**
 * What the camera should record for an input with `capture`, or null
 * to use the document picker instead (no `capture`, or an `accept`
 * the camera can't produce, e.g. `capture accept="application/pdf"`).
 * Decided on the types the page actually names, not on the widened
 * picker filter: `accept="application/pdf,.xyz"` widens the picker to
 * anything but still names nothing a camera makes. Images win when
 * the input accepts both, matching Chrome.
 */
internal fun captureKindFor(captureEnabled: Boolean, accept: AcceptTypes): CaptureKind? {
    if (!captureEnabled) return null
    if (accept.anything) return CaptureKind.IMAGE
    if (accept.named.any { it.startsWith("image/") }) return CaptureKind.IMAGE
    if (accept.named.any { it.startsWith("video/") }) return CaptureKind.VIDEO
    return null
}

/**
 * `ACTION_GET_CONTENT` for [mimeTypes] (empty = anything). Only
 * [CATEGORY_OPENABLE][Intent.CATEGORY_OPENABLE] results — something
 * the page can actually read the bytes of.
 */
internal fun pickerIntent(mimeTypes: List<String>, multiple: Boolean): Intent =
    Intent(Intent.ACTION_GET_CONTENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = mimeTypes.singleOrNull() ?: "*/*"
        if (mimeTypes.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())
        if (multiple) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
    }

/**
 * The files a picker result selects: every [clip] item when several
 * came back (multi-select), else [data]. Only URIs passing
 * [uploadable] (see [isUploadableUri]) are passed on. A single-file
 * input takes the first pick only.
 */
internal fun <T : Any> pickedUris(
    clip: List<T>,
    data: T?,
    multiple: Boolean,
    uploadable: (T) -> Boolean,
): List<T> {
    val all = clip.ifEmpty { listOfNotNull(data) }
        .filter(uploadable)
        .distinct()
    return if (multiple) all else all.take(1)
}

/**
 * Whether a URI another app (picker or camera) handed back may be
 * uploaded. WebView reads it with the browser's identity, so it must
 * be a `content:` URI ([scheme]) served by *someone else's* provider:
 * `file:///data/…` or `content://<our own provider>/…` (e.g. an earlier
 * capture still in cache/uploads, uploaded to a different site) would
 * have the browser upload its own private files. [authority]
 * may carry a `userId@` prefix, which ContentResolver strips before
 * resolving the provider, so it is stripped here too.
 */
internal fun isUploadableUri(
    scheme: String?,
    authority: String?,
    isOwnAuthority: (String) -> Boolean,
): Boolean {
    if (!scheme.equals("content", ignoreCase = true)) return false
    val bare = authority?.substringAfterLast('@')
    if (bare.isNullOrEmpty()) return false
    return !isOwnAuthority(bare)
}
