package baby.freedom.mobile.browser

import java.net.URLDecoder
import java.util.Base64

/**
 * Pure (Android-free) half of the download manager (#79): what a URL
 * handed to `DownloadListener.onDownloadStart` actually is, what the
 * saved file should be called, and how a `data:` URI decodes. Kept
 * apart from [DownloadManager] so it can be unit-tested on the JVM.
 */
internal sealed class DownloadTarget {
    /** What the downloads list shows as the file's source. */
    abstract val displayUrl: String

    /**
     * Dweb content: a virtual-origin URL (`https://<label>.bzz.freedom.baby/…`)
     * or a `bzz://` / `ipfs://` / `ipns://` / `ens://` URL. Those hosts
     * never resolve in DNS — the bytes come from the local gateway, the
     * same mapping the request interceptor uses.
     */
    data class Dweb(
        val root: ContentRoot,
        val pathAndQuery: String,
        override val displayUrl: String,
    ) : DownloadTarget()

    /** Ordinary http(s), fetched as-is (with the WebView's cookies). */
    data class Web(val url: String, override val displayUrl: String) : DownloadTarget()

    /** A direct call to the local gateway (`http://127.0.0.1:1633/bzz/…`). */
    data class LocalGateway(val url: String, override val displayUrl: String) : DownloadTarget()

    /** `data:` — the URI is the file. */
    data class Data(val uri: String) : DownloadTarget() {
        override val displayUrl: String get() = shortDataUri(uri)
    }

    /** Anything we can't fetch from native code (`blob:`, `file:`, …). */
    data class Unsupported(val scheme: String, override val displayUrl: String) : DownloadTarget()
}

/** Longest `data:` prefix kept for display — the payload is the file, not a URL. */
private const val DATA_URI_DISPLAY_CHARS = 48

internal fun shortDataUri(uri: String): String =
    if (uri.length <= DATA_URI_DISPLAY_CHARS) uri else uri.take(DATA_URI_DISPLAY_CHARS) + "…"

private val DWEB_SCHEMES = setOf("bzz", "ipfs", "ipns", "ens")

/**
 * Classify a download URL. [isLocalGateway] and [gatewayDisplay] are
 * [Gateways.isLocalGateway] / [Gateways.toDisplay] in production —
 * injected because the IPFS gateway's port is only known at runtime.
 */
internal fun classifyDownloadUrl(
    url: String,
    isLocalGateway: (String) -> Boolean = { false },
    gatewayDisplay: (String) -> String = { it },
): DownloadTarget {
    val scheme = url.substringBefore(':', "").lowercase()
    if (scheme == "data") return DownloadTarget.Data(url)
    if (scheme in DWEB_SCHEMES) {
        val parsed = VirtualOrigin.parseContentUrl(url)
            ?: return DownloadTarget.Unsupported(scheme, url)
        val display = VirtualOrigin.toVirtualUrl(url)?.let { VirtualOrigin.displayUrlFor(it) }
            ?: url.substringBefore('#')
        return DownloadTarget.Dweb(parsed.first, parsed.second.ifEmpty { "/" }, display)
    }
    VirtualOrigin.parseHostOfUrl(url)?.let { root ->
        return DownloadTarget.Dweb(
            root = root,
            pathAndQuery = VirtualOrigin.pathAndQueryOf(url),
            displayUrl = VirtualOrigin.displayUrlFor(url) ?: url,
        )
    }
    if (scheme == "http" || scheme == "https") {
        return if (isLocalGateway(url)) {
            DownloadTarget.LocalGateway(url, gatewayDisplay(url))
        } else {
            DownloadTarget.Web(url, url)
        }
    }
    return DownloadTarget.Unsupported(scheme.ifEmpty { "?" }, url)
}

/** A decoded `data:` URI. */
internal class DataUriPayload(val mimeType: String, val bytes: ByteArray)

/**
 * Decode `data:[<mime>][;param=…][;base64],<payload>` (RFC 2397).
 * Returns null for anything malformed — a bad base64 body included.
 */
internal fun parseDataUri(uri: String): DataUriPayload? {
    if (!uri.regionMatches(0, "data:", 0, 5, ignoreCase = true)) return null
    val comma = uri.indexOf(',')
    if (comma < 0) return null
    val meta = uri.substring(5, comma).split(';').map { it.trim() }
    val isBase64 = meta.drop(1).any { it.equals("base64", ignoreCase = true) }
    val mime = meta.first().lowercase().ifBlank { "text/plain" }
    val payload = uri.substring(comma + 1)
    val bytes = try {
        if (isBase64) {
            // Base64 in a URL is routinely percent-encoded, wrapped, or
            // in the URL-safe alphabet. The strict decoder (not the MIME
            // one, which skips junk) so a corrupt body fails loudly.
            val cleaned = percentDecode(payload)
                .filterNot { it.isWhitespace() }
                .replace('-', '+')
                .replace('_', '/')
            Base64.getDecoder().decode(cleaned)
        } else {
            percentDecodeBytes(payload)
        }
    } catch (_: IllegalArgumentException) {
        return null
    }
    return DataUriPayload(mime, bytes)
}

private fun percentDecode(s: String): String =
    if ('%' !in s) s else String(percentDecodeBytes(s), Charsets.ISO_8859_1)

/** `%XX` → byte; every other char → its UTF-8 bytes. `+` stays `+`. */
private fun percentDecodeBytes(s: String): ByteArray {
    val out = java.io.ByteArrayOutputStream(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '%' && i + 2 < s.length && s[i + 1].isHexDigitChar() && s[i + 2].isHexDigitChar()) {
            out.write(s.substring(i + 1, i + 3).toInt(16))
            i += 3
        } else {
            out.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
        }
    }
    return out.toByteArray()
}

private fun Char.isHexDigitChar(): Boolean =
    this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

/** Used when neither the headers nor the URL give the file a name. */
internal const val DEFAULT_DOWNLOAD_NAME = "download"

/** Longest file name we write (most filesystems cap at 255 bytes). */
private const val MAX_FILE_NAME_CHARS = 120

/**
 * The file name to save a download under, in order of preference: the
 * `Content-Disposition` `filename*` / `filename` parameter, the last
 * path segment of [url], then [DEFAULT_DOWNLOAD_NAME]. A name with no
 * extension gets one from [mimeType] (via [extensionForMime], which is
 * `MimeTypeMap` in production) so the file opens in the right app.
 *
 * A bare content root (`bzz://<hash>` with no path) has no useful last
 * segment — the hash is not a file name — so it falls through to the
 * default unless the gateway sent a `Content-Disposition` name.
 */
internal fun downloadFileName(
    contentDisposition: String?,
    url: String,
    mimeType: String?,
    extensionForMime: (String) -> String? = { null },
): String {
    val fromHeader = contentDisposition?.let { fileNameFromContentDisposition(it) }
    val fromUrl = if (url.startsWith("data:", ignoreCase = true)) null else lastPathSegment(url)
    var name = sanitizeFileName(fromHeader ?: fromUrl ?: "")
        .ifEmpty { DEFAULT_DOWNLOAD_NAME }
    val mime = mimeType?.substringBefore(';')?.trim()?.lowercase()
    if (!name.contains('.') && !mime.isNullOrEmpty() && mime != "application/octet-stream") {
        extensionForMime(mime)?.takeIf { it.isNotBlank() }?.let { name = "$name.$it" }
    }
    return clampFileName(name)
}

/**
 * `filename*=UTF-8''na%C3%AFve.txt` (RFC 6266 / 5987, preferred) or
 * `filename="x.txt"` / `filename=x.txt`.
 */
internal fun fileNameFromContentDisposition(header: String): String? {
    val params = splitHeaderParams(header)
    params.firstOrNull { it.first.equals("filename*", ignoreCase = true) }?.second?.let { v ->
        val parts = v.split('\'', limit = 3)
        if (parts.size == 3) {
            val charset = runCatching { charset(parts[0].ifBlank { "UTF-8" }) }
                .getOrDefault(Charsets.UTF_8)
            val decoded = String(percentDecodeBytes(parts[2]), charset)
            if (decoded.isNotBlank()) return decoded
        }
    }
    return params.firstOrNull { it.first.equals("filename", ignoreCase = true) }
        ?.second?.takeIf { it.isNotBlank() }
}

/** `attachment; filename="a;b.txt"; size=3` → [(filename, a;b.txt), (size, 3)]. */
private fun splitHeaderParams(header: String): List<Pair<String, String>> {
    val out = mutableListOf<Pair<String, String>>()
    var i = header.indexOf(';')
    if (i < 0) return out
    i++
    while (i < header.length) {
        while (i < header.length && (header[i] == ' ' || header[i] == ';')) i++
        val eq = header.indexOf('=', i)
        if (eq < 0) break
        val key = header.substring(i, eq).trim()
        i = eq + 1
        while (i < header.length && header[i] == ' ') i++
        val value: String
        if (i < header.length && header[i] == '"') {
            val sb = StringBuilder()
            i++
            while (i < header.length && header[i] != '"') {
                if (header[i] == '\\' && i + 1 < header.length) i++
                sb.append(header[i])
                i++
            }
            i++ // closing quote
            value = sb.toString()
        } else {
            val end = header.indexOf(';', i).let { if (it < 0) header.length else it }
            value = header.substring(i, end).trim()
            i = end
        }
        out += key to value
    }
    return out
}

/** Decoded last non-empty path segment of [url], or null. */
private fun lastPathSegment(url: String): String? {
    val afterScheme = url.substringAfter("://", "")
    val path = afterScheme.substringAfter('/', "")
        .substringBefore('?')
        .substringBefore('#')
    val segment = path.split('/').lastOrNull { it.isNotEmpty() } ?: return null
    return runCatching { URLDecoder.decode(segment.replace("+", "%2B"), "UTF-8") }
        .getOrDefault(segment)
}

/**
 * Strip what can't be in a single file name: path separators (a header
 * saying `../../x` must not escape Downloads), control characters, and
 * the characters Android's FAT-backed shared storage rejects. Leading
 * dots go too so a download is never a hidden file.
 */
internal fun sanitizeFileName(raw: String): String {
    val base = raw.substringAfterLast('/').substringAfterLast('\\')
    return base
        .map { c -> if (c.code < 0x20 || c == '\u007f' || c in "\"*:<>?|") '_' else c }
        .joinToString("")
        .trim()
        .trimStart('.')
        .trim()
}

private fun clampFileName(name: String): String {
    if (name.length <= MAX_FILE_NAME_CHARS) return name
    val dot = name.lastIndexOf('.')
    val ext = if (dot > 0 && name.length - dot <= 16) name.substring(dot) else ""
    return name.take(MAX_FILE_NAME_CHARS - ext.length) + ext
}

/** "1.4 MB" — the unit ladder the downloads list shows sizes in. */
internal fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes / 1024.0
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (value >= 100) "${value.toLong()} ${units[unit]}"
    else String.format(java.util.Locale.US, "%.1f %s", value, units[unit])
}
