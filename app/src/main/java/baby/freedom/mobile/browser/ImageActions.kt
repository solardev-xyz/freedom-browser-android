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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
 *  - anything else over http(s) with the WebView's User-Agent, and the
 *    site's cookies where the WebView would have sent them (same-site
 *    hops only, see [fetchHttpImage]), so an image behind a session
 *    still comes back;
 *  - `data:` URLs are decoded in place.
 *
 * `blob:` images never reach here: they only exist inside the page that
 * minted them (see [pageContextTargetFor]).
 */

internal class FetchedImage(val bytes: ByteArray, val mime: String)

/** Images bigger than this are refused rather than buffered into memory. */
private const val MAX_IMAGE_BYTES = 50 * 1024 * 1024

internal suspend fun fetchImage(
    url: String,
    pageUrl: String?,
    userAgent: String?,
    private: Boolean = false,
): FetchedImage? =
    withHardDeadline(IMAGE_FETCH_DEADLINE_MS) { guard ->
        if (url.startsWith("data:", ignoreCase = true)) return@withHardDeadline decodeDataUrl(url)
        interceptVirtualRequest(GetRequest(url))?.let { response ->
            val data = response.data ?: return@withHardDeadline null
            if (!guard.register(data)) return@withHardDeadline null
            data.use {
                if (response.statusCode !in 200..299) return@withHardDeadline null
                val bytes = readCapped(it) ?: return@withHardDeadline null
                return@withHardDeadline imageMime(response.mimeType, bytes, url)?.let { FetchedImage(bytes, it) }
            }
        }
        // A private tab's image goes with the private session's cookies
        // (#86) — none once it has ended, never the normal tabs'.
        val cookies = if (private) {
            PrivateProfile.cookieManager()
        } else {
            runCatching { CookieManager.getInstance() }.getOrNull()
        }
        fetchHttpImage(url, pageUrl, userAgent, cookies, guard)
    }

/**
 * What a [withHardDeadline] block hands its blocking I/O to, so the
 * deadline can cut it off mid-read: a registered connection or stream
 * is closed the moment the deadline passes (or the caller goes away),
 * and one registered after that is closed on the spot and refused.
 */
internal class FetchGuard {
    private var abandoned = false
    private val open = mutableListOf<java.io.Closeable>()

    /** `false` (and [c] already closed) once the fetch is abandoned. */
    @Synchronized
    fun register(c: java.io.Closeable): Boolean {
        if (abandoned) {
            runCatching { c.close() }
            return false
        }
        open += c
        return true
    }

    @Synchronized
    fun abandon() {
        abandoned = true
        open.forEach { runCatching { it.close() } }
        open.clear()
    }
}

/** Blocking fetches outlive their caller only until [FetchGuard] closes them. */
private val imageFetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Run the blocking [block] on the IO pool and return its result, or
 * `null` once [timeoutMs] has passed — a hard stop for the caller, not
 * a per-read timeout: a server trickling a byte just inside every read
 * timeout, or a dweb fetch retrying inside [interceptVirtualRequest]
 * (which no timeout here reaches), still ends at [timeoutMs]. At that
 * point the block is abandoned: everything it registered with the
 * guard is closed (unblocking its socket reads), its thread is
 * interrupted (ending any retry sleep), and whatever it would still
 * return is dropped. Any exception from the block is a `null` too.
 */
internal suspend fun <T : Any> withHardDeadline(
    timeoutMs: Long,
    block: (FetchGuard) -> T?,
): T? {
    val guard = FetchGuard()
    val work = imageFetchScope.async {
        runInterruptible { runCatching { block(guard) }.getOrNull() }
    }
    try {
        return withTimeoutOrNull(timeoutMs) { work.await() }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        return null
    } finally {
        if (!work.isCompleted) {
            guard.abandon()
            work.cancel()
        }
    }
}

private const val MAX_REDIRECTS = 10

/**
 * GET [url] over http(s), following redirects by hand so each hop's
 * `Cookie` header is decided for *that* hop. `HttpURLConnection`'s own
 * redirect handling re-sends a manually set `Cookie` header to wherever
 * the server points, which would hand the page's session cookies to
 * any host an `<img src>` (or an open redirect) names.
 *
 * Cookies go only where the WebView itself would send them for an
 * image on [pageUrl]: to a hop that is same-site with the page
 * ([sendsCookiesTo]; third-party cookies are off in the WebView), and
 * never again once the chain has left the page's site.
 *
 * Nor does a secure page's image come back in cleartext: every hop is
 * passed through [secureHopFor] first, so an `http://` image (or an
 * https → http redirect) on an https or dweb page is fetched over https
 * — as Chromium's mixed-content autoupgrade would have — or not at all.
 *
 * The whole chain shares one [IMAGE_FETCH_DEADLINE_MS] budget, which
 * [withHardDeadline] enforces by disconnecting the live hop (each is
 * registered with [guard]) when it runs out. Each hop's connect / read
 * timeouts are also clamped to what is left of it, so an abandoned
 * fetch's thread doesn't linger past it either.
 */
private fun fetchHttpImage(
    url: String,
    pageUrl: String?,
    userAgent: String?,
    cookies: CookieManager?,
    guard: FetchGuard,
): FetchedImage? {
    val deadline = System.currentTimeMillis() + IMAGE_FETCH_DEADLINE_MS
    var current = url
    var sameSiteChain = true
    repeat(MAX_REDIRECTS + 1) {
        current = secureHopFor(pageUrl, current) ?: return null
        val remaining = (deadline - System.currentTimeMillis()).toInt()
        if (remaining <= 0) return null
        sameSiteChain = sameSiteChain && sendsCookiesTo(pageUrl, current)
        val conn = (URL(current).openConnection() as HttpURLConnection).apply {
            connectTimeout = minOf(10_000, remaining)
            readTimeout = minOf(20_000, remaining)
            instanceFollowRedirects = false
            if (!userAgent.isNullOrEmpty()) setRequestProperty("User-Agent", userAgent)
            if (sameSiteChain) {
                cookies?.getCookie(current)?.let { setRequestProperty("Cookie", it) }
            }
        }
        if (!guard.register { conn.disconnect() }) return null
        try {
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location") ?: return null
                current = URL(URL(current), location).toString()
                return@repeat
            }
            if (code !in 200..299) return null
            val bytes = conn.inputStream.use { readCapped(it, deadline) } ?: return null
            return imageMime(conn.contentType, bytes, current)?.let { FetchedImage(bytes, it) }
        } finally {
            conn.disconnect()
        }
    }
    return null
}

/**
 * The most a Copy / Save / Share image refetch may take, redirects and
 * all, dweb or http — a hard stop, see [withHardDeadline].
 */
internal const val IMAGE_FETCH_DEADLINE_MS = 30_000L

/** How long a refetch may run before the user is told it is under way. */
internal const val IMAGE_FETCH_PROGRESS_DELAY_MS = 600L

/**
 * The URL to fetch for a hop to [url] of an image on [pageUrl], or
 * `null` to refuse it. Only http(s) is fetched. A page that isn't plain
 * `http://` — https, a dweb display URL (served from a virtual https
 * origin), or unknown — is a secure context, and its images never go
 * out in cleartext: an `http://` hop is upgraded to `https://` (port
 * kept), the way Chromium autoupgrades mixed-content images, except to
 * a loopback host, which Chromium treats as potentially trustworthy
 * and loads as-is.
 */
internal fun secureHopFor(pageUrl: String?, url: String): String? {
    val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase() ?: return null
    if (scheme == "https") return url
    if (scheme != "http") return null
    val pageScheme = pageUrl?.let { runCatching { java.net.URI(it).scheme }.getOrNull() }?.lowercase()
    if (pageScheme == "http") return url
    val host = uri.host?.lowercase()?.trimEnd('.')?.removePrefix("[")?.removeSuffix("]") ?: return null
    if (isLoopbackHost(host)) return url
    // Rebuilt by hand so the rest of the URL stays byte-for-byte (URI's
    // component constructor would re-encode its `%` escapes). An explicit
    // :80 is http's default port, so it becomes https's default.
    val authority = uri.rawAuthority ?: return null
    val start = url.indexOf("//")
    if (start < 0 || !url.startsWith(authority, start + 2)) return null
    val rest = url.substring(start + 2 + authority.length)
    return "https://" + (if (uri.port == 80) authority.removeSuffix(":80") else authority) + rest
}

/**
 * Whether [host] (lowercased, brackets and trailing dot stripped) is one
 * Chromium deems potentially trustworthy as loopback: `localhost` and
 * `*.localhost`, `::1`, or an IPv4 *literal* in 127.0.0.0/8. Only a
 * literal: `127.tracker.example` is a DNS name like any other, and is
 * upgraded.
 */
internal fun isLoopbackHost(host: String): Boolean {
    if (host == "localhost" || host.endsWith(".localhost") || host == "::1") return true
    val octets = host.split('.')
    return octets.size == 4 &&
        octets.all { o -> o.length in 1..3 && o.all { it in '0'..'9' } && o.toInt() <= 255 } &&
        octets[0].toInt() == 127
}

/**
 * Whether the page at [pageUrl] would send its cookies with a
 * subresource request to [url]: both http(s), same scheme, same site
 * (registrable domain; an IP literal or a host with no registrable
 * domain is its own site). `null` page → never.
 */
internal fun sendsCookiesTo(pageUrl: String?, url: String): Boolean {
    val page = cookieSite(pageUrl ?: return false) ?: return false
    val target = cookieSite(url) ?: return false
    return page == target
}

private fun cookieSite(url: String): Pair<String, String>? {
    val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase()?.takeIf { it == "http" || it == "https" } ?: return null
    val host = uri.host?.lowercase()?.trimEnd('.')?.removePrefix("[")?.removeSuffix("]")
        ?.takeIf { it.isNotEmpty() } ?: return null
    val isIp = ':' in host || host.all { it.isDigit() || it == '.' }
    val site = if (isIp) host else PublicSuffixList.registrableDomain(host) ?: host
    return scheme to site
}

private fun readCapped(input: java.io.InputStream, deadline: Long = Long.MAX_VALUE): ByteArray? {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    while (true) {
        if (System.currentTimeMillis() > deadline) return null
        val n = input.read(buf)
        if (n < 0) break
        out.write(buf, 0, n)
        if (out.size() > MAX_IMAGE_BYTES) return null
    }
    return out.toByteArray().takeIf { it.isNotEmpty() }
}

/**
 * `data:[<mime>][;base64],<payload>` → bytes. `null` for anything
 * malformed, and for anything that isn't an image: the declared MIME
 * must be `image/…` or the bytes must decode as one (a `data:,hello`
 * `<img>` is not something to save as a picture). A non-base64 payload
 * is percent-decoded (an inline SVG is the usual one).
 */
internal fun decodeDataUrl(url: String): FetchedImage? {
    if (!url.startsWith("data:", ignoreCase = true)) return null
    val comma = url.indexOf(',')
    if (comma < 0) return null
    val meta = url.substring(5, comma)
    val payload = url.substring(comma + 1)
    val params = meta.split(';')
    val base64 = params.drop(1).any { it.equals("base64", ignoreCase = true) }
    val declared = params.first().trim().lowercase()
    val bytes = if (base64) {
        runCatching { Base64.decode(payload, Base64.DEFAULT) }.getOrNull()
    } else {
        Uri.decode(payload).toByteArray()
    } ?: return null
    if (bytes.isEmpty()) return null
    val mime = imageMimeFor(declared, decodedImageMime(bytes), extensionMime = null) ?: return null
    return FetchedImage(bytes, mime)
}

/** What [bytes] decode as, if they decode as an image at all. */
private fun decodedImageMime(bytes: ByteArray): String? {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    return opts.outMimeType?.takeIf { it.startsWith("image/") }
}

/**
 * The image's MIME type: what the server said if it said `image/…`,
 * otherwise what the bytes decode as, otherwise what the URL's image
 * extension implies (gateways do serve images as
 * `application/octet-stream`). `null` when nothing says it is an image.
 */
private fun imageMime(contentType: String?, bytes: ByteArray, url: String): String? =
    imageMimeFor(
        declared = contentType,
        sniffed = decodedImageMime(bytes),
        extensionMime = MimeTypeMap.getFileExtensionFromUrl(url)?.lowercase()
            ?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) },
    )

/** The decision behind [imageMime] / [decodeDataUrl], on already-read inputs. */
internal fun imageMimeFor(declared: String?, sniffed: String?, extensionMime: String?): String? {
    val type = declared?.substringBefore(';')?.trim()?.lowercase()
    if (type != null && type.startsWith("image/")) return type
    return sniffed?.takeIf { it.startsWith("image/") }
        ?: extensionMime?.lowercase()?.takeIf { it.startsWith("image/") }
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
            FileProvider.getUriForFile(context, FileChooser.authority(context), file)
        }.getOrNull()
    }

/** Subdirectory of `cacheDir`; must match `res/xml/file_paths.xml`. */
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
