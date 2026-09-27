package baby.freedom.mobile.browser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The image half of the page context menu (#84): fetch the bytes of an
 * `<img>` the user long-pressed, then save, copy or share them.
 *
 * The WebView keeps no API to hand over an image it already decoded, so
 * the bytes are fetched again here, along the same path the page's own
 * request took:
 *
 *  - dweb content (virtual origins, `bzz://` and friends) goes through
 *    [interceptVirtualRequest] — the very interceptor that served it to
 *    the page, so it maps to the local gateway exactly as it did then;
 *  - anything else over http(s), carrying the site's cookies and the
 *    WebView's User-Agent, so an image behind a session still comes back;
 *  - `data:` URLs are decoded in place.
 *
 * `blob:` images never reach here: they only exist inside the page that
 * minted them (see [pageContextTargetFor]).
 */

internal class FetchedImage(val bytes: ByteArray, val mime: String)

/** Images bigger than this are refused rather than buffered into memory. */
private const val MAX_IMAGE_BYTES = 50 * 1024 * 1024

internal suspend fun fetchImage(url: String, userAgent: String?): FetchedImage? =
    withContext(Dispatchers.IO) {
        runCatching {
            if (url.startsWith("data:", ignoreCase = true)) return@runCatching decodeDataUrl(url)
            interceptVirtualRequest(GetRequest(url))?.let { response ->
                if (response.statusCode !in 200..299) return@runCatching null
                val bytes = response.data?.use { readCapped(it) } ?: return@runCatching null
                return@runCatching FetchedImage(bytes, imageMime(response.mimeType, bytes, url))
            }
            val scheme = Uri.parse(url).scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return@runCatching null
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                if (!userAgent.isNullOrEmpty()) setRequestProperty("User-Agent", userAgent)
                CookieManager.getInstance().getCookie(url)?.let { setRequestProperty("Cookie", it) }
            }
            try {
                if (conn.responseCode !in 200..299) return@runCatching null
                val bytes = conn.inputStream.use { readCapped(it) } ?: return@runCatching null
                FetchedImage(bytes, imageMime(conn.contentType, bytes, url))
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

private fun readCapped(input: java.io.InputStream): ByteArray? {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        out.write(buf, 0, n)
        if (out.size() > MAX_IMAGE_BYTES) return null
    }
    return out.toByteArray().takeIf { it.isNotEmpty() }
}

/**
 * `data:[<mime>][;base64],<payload>` → bytes. `null` for anything
 * malformed. A non-base64 payload is percent-decoded (an inline SVG is
 * the usual one).
 */
internal fun decodeDataUrl(url: String): FetchedImage? {
    if (!url.startsWith("data:", ignoreCase = true)) return null
    val comma = url.indexOf(',')
    if (comma < 0) return null
    val meta = url.substring(5, comma)
    val payload = url.substring(comma + 1)
    val params = meta.split(';')
    val base64 = params.drop(1).any { it.equals("base64", ignoreCase = true) }
    val mime = params.first().trim().lowercase().ifEmpty { "text/plain" }
    val bytes = if (base64) {
        runCatching { Base64.decode(payload, Base64.DEFAULT) }.getOrNull()
    } else {
        Uri.decode(payload).toByteArray()
    } ?: return null
    if (bytes.isEmpty()) return null
    return FetchedImage(bytes, mime)
}

/**
 * The image's MIME type: what the server said if it said `image/…`,
 * otherwise what the bytes decode as, otherwise what the URL's extension
 * implies. Gateways do serve images as `application/octet-stream`.
 */
private fun imageMime(contentType: String?, bytes: ByteArray, url: String): String {
    val declared = contentType?.substringBefore(';')?.trim()?.lowercase()
    if (declared != null && declared.startsWith("image/")) return declared
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    opts.outMimeType?.let { return it }
    val ext = MimeTypeMap.getFileExtensionFromUrl(url)?.lowercase()
    return ext?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) } ?: "image/png"
}

/**
 * A file name for an image saved from [url]: the URL's last path
 * segment when it looks like a file name, `image` otherwise, with an
 * extension that matches [mime] (a gateway's extensionless `/bzz/<hash>`
 * still saves as a `.png` / `.jpg` the gallery can open).
 */
internal fun imageFileName(url: String, mime: String, extensionForMime: (String) -> String?): String {
    val segment = if (url.startsWith("data:", ignoreCase = true)) {
        ""
    } else {
        url.substringBefore('#').substringBefore('?')
            .substringAfter("://", url).substringAfter('/', "") // drop scheme + host
            .trimEnd('/').substringAfterLast('/')
    }
    val decoded = runCatching { java.net.URLDecoder.decode(segment, "UTF-8") }.getOrDefault(segment)
    val cleaned = decoded.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim('.', ' ', '_').take(80)
    // A 64-hex Swarm reference or a CID is a name nobody wants in a gallery.
    val looksLikeHash = Regex("^[A-Za-z0-9]{40,}$").matches(cleaned.substringBeforeLast('.'))
    val base = cleaned.substringBeforeLast('.').ifEmpty { "image" }.let { if (looksLikeHash) "image" else it }
    val ext = extensionForMime(mime)
        ?: cleaned.substringAfterLast('.', "").lowercase().ifEmpty { null }
        ?: "png"
    return "$base.$ext"
}

private fun extensionFor(mime: String): String? = when (mime) {
    "image/jpeg" -> "jpg"
    "image/svg+xml" -> "svg"
    else -> MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
}

/**
 * Save [image] to the shared `Pictures/Freedom` collection. No storage
 * permission is needed for an app's own MediaStore inserts (minSdk 30).
 * Something MediaStore won't file as a picture lands in Downloads instead.
 */
internal suspend fun saveImage(context: Context, image: FetchedImage, url: String): Boolean =
    withContext(Dispatchers.IO) {
        val name = imageFileName(url, image.mime, ::extensionFor)
        val resolver = context.contentResolver
        fun insert(collection: Uri, dir: String): Uri? = runCatching {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, image.mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/Freedom")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            resolver.insert(collection, values)
        }.getOrNull()

        val uri = insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), Environment.DIRECTORY_PICTURES)
            ?: insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), Environment.DIRECTORY_DOWNLOADS)
            ?: return@withContext false
        runCatching {
            resolver.openOutputStream(uri)!!.use { it.write(image.bytes) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            true
        }.getOrElse {
            runCatching { resolver.delete(uri, null, null) }
            false
        }
    }

/**
 * Write [image] where [FileProvider] can hand it out (`cache/shared/`),
 * returning its `content://` URI. Files there older than a day are
 * pruned on the way in: a copy or share needs its file only while the
 * receiving app reads it, and the cache would otherwise grow per image.
 */
private suspend fun shareableUri(context: Context, image: FetchedImage, url: String): Uri? =
    withContext(Dispatchers.IO) {
        runCatching {
            val root = File(context.cacheDir, SHARED_DIR)
            val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
            root.listFiles()?.forEach { dir -> if (dir.lastModified() < cutoff) dir.deleteRecursively() }
            // A directory per image keeps the natural file name (which the
            // receiving app shows) without two images colliding on it.
            val dir = File(root, System.nanoTime().toString()).apply { mkdirs() }
            val file = File(dir, imageFileName(url, image.mime, ::extensionFor))
            file.writeBytes(image.bytes)
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull()
    }

private const val SHARED_DIR = "shared"

/** Put [image] on the clipboard as a `content://` image clip. */
internal suspend fun copyImageToClipboard(context: Context, image: FetchedImage, url: String): Boolean {
    val uri = shareableUri(context, image, url) ?: return false
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
    clipboard.setPrimaryClip(ClipData.newUri(context.contentResolver, "Image", uri))
    // Same rule as [copyUrlToClipboard]: Android 13+ confirms copies itself.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, "Image copied", Toast.LENGTH_SHORT).show()
    }
    return true
}

/** Hand [image] to the system share sheet as a file. */
internal suspend fun shareImage(context: Context, image: FetchedImage, url: String): Boolean {
    val uri = shareableUri(context, image, url) ?: return false
    val send = Intent(Intent.ACTION_SEND).apply {
        type = image.mime
        putExtra(Intent.EXTRA_STREAM, uri)
        // The ClipData is what carries the read grant through the chooser.
        clipData = ClipData.newRawUri(null, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return runCatching { context.startActivity(Intent.createChooser(send, null)) }.isSuccess
}

/** A bare GET for [interceptVirtualRequest]: no headers, not a navigation. */
private class GetRequest(private val url: String) : WebResourceRequest {
    override fun getUrl(): Uri = Uri.parse(url)
    override fun isForMainFrame(): Boolean = false
    override fun isRedirect(): Boolean = false
    override fun hasGesture(): Boolean = false
    override fun getMethod(): String = "GET"
    override fun getRequestHeaders(): Map<String, String> = emptyMap()
}
