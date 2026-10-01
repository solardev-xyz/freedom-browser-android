package baby.freedom.mobile.browser

import baby.freedom.mobile.R
import baby.freedom.mobile.l10n.Strings
import java.math.RoundingMode
import java.net.URLDecoder
import java.text.NumberFormat
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
 *
 * So do the invisible characters that change how the rest of the name
 * reads ([fileNameCharIsHidden]): a server naming its file
 * `invoice<U+202E>fdp.apk` would otherwise be shown — in the prompt,
 * the downloads list, the notification, and the Files app — as
 * `invoicekpa.pdf`, an APK passed off as a PDF. Chromium's own
 * download names (desktop's) replace them the same way.
 */
internal fun sanitizeFileName(raw: String): String {
    val base = raw.substringAfterLast('/').substringAfterLast('\\')
    return cleanStoredFileName(base)
        .trim()
        .trimStart('.')
        .trim()
}

/**
 * Is [cp] a character a file name mustn't carry: C0/C1 controls, the
 * FAT-reserved `"*:<>?|`, a lone surrogate, a line/paragraph separator,
 * or a format character (Cf: the bidi embeddings, overrides, isolates
 * and marks, zero-width spaces, the BOM, soft hyphen …). By code point,
 * so a supplementary-plane format character (U+E0001) is caught too.
 * ZWJ / ZWNJ and the emoji tag characters (U+E0020–E007F) stay: emoji
 * sequences, flags and Persian / Indic words need them, and they
 * reorder nothing.
 */
private fun fileNameCharIsHidden(cp: Int): Boolean {
    if (cp < 0x80) return cp < 0x20 || cp == 0x7f || cp.toChar() in "\"*:<>?|"
    if (cp == 0x200C || cp == 0x200D || cp in 0xE0020..0xE007F) return false
    return when (Character.getType(cp).toByte()) {
        Character.CONTROL, Character.FORMAT, Character.SURROGATE,
        Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR,
        -> true
        else -> false
    }
}

private fun clampFileName(name: String): String {
    if (name.length <= MAX_FILE_NAME_CHARS) return name
    val dot = name.lastIndexOf('.')
    val ext = if (dot > 0 && name.length - dot <= 16) name.substring(dot) else ""
    return name.take(clusterSafeCut(name, MAX_FILE_NAME_CHARS - ext.length)) + ext
}

/**
 * [at], moved back so cutting [s] there splits no character a reader
 * sees as one: not the two halves of a surrogate pair (a lone one can't
 * be encoded, and the name would get a `?` where an emoji was), and not
 * a cluster — a ZWJ sequence (👨‍👩‍👧 keeping only 👨‍), a base and its
 * combining marks, variation selector, skin tone or emoji tags (🏴 with
 * its subdivision tags cut off is a plain black flag), or a flag's two
 * regional indicators. By hand rather than with `BreakIterator`, whose
 * JDK version knows nothing of emoji sequences. If the whole kept part
 * is one cluster, only the surrogate rule holds: a name is never cut to
 * nothing (or to a bare `.ext`, a hidden file).
 */
internal fun clusterSafeCut(s: String, at: Int): Int {
    if (at <= 0 || at >= s.length) return at.coerceIn(0, s.length)
    val pairSafe = if (s[at - 1].isHighSurrogate() && s[at].isLowSurrogate()) at - 1 else at
    var k = pairSafe
    while (k > 0) {
        val next = s.codePointAt(k)
        val prev = s.codePointBefore(k)
        val inside = continuesCluster(next) || prev == ZWJ ||
            isRegionalIndicator(next) && regionalIndicatorsBefore(s, k) % 2 == 1
        if (!inside) break
        k -= Character.charCount(prev)
    }
    return if (k > 0) k else pairSafe
}

private const val ZWJ = 0x200D

/** Does [cp] attach to the character before it (grapheme `Extend`-like)? */
private fun continuesCluster(cp: Int): Boolean =
    cp == ZWJ || cp == 0x200C ||
        cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF || // variation selectors
        cp in 0x1F3FB..0x1F3FF || // skin tones
        cp in 0xE0020..0xE007F || // emoji tags
        when (Character.getType(cp).toByte()) {
            Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK -> true
            else -> false
        }

private fun isRegionalIndicator(cp: Int) = cp in 0x1F1E6..0x1F1FF

/** How many regional indicators run back from just before [end]. */
private fun regionalIndicatorsBefore(s: String, end: Int): Int {
    var n = 0
    var i = end
    while (i > 0) {
        val cp = s.codePointBefore(i)
        if (!isRegionalIndicator(cp)) break
        n++
        i -= Character.charCount(cp)
    }
    return n
}

/**
 * [name] with every [fileNameCharIsHidden] character replaced by `_`
 * and nothing else changed — [sanitizeFileName]'s core, and how a name
 * stored before it replaced format characters (a paused row from an
 * older version) is cleaned when that row is loaded again, without
 * touching a name the user picked any further.
 */
internal fun cleanStoredFileName(name: String): String {
    val out = StringBuilder(name.length)
    var i = 0
    while (i < name.length) {
        val cp = name.codePointAt(i)
        i += Character.charCount(cp)
        if (fileNameCharIsHidden(cp)) out.append('_') else out.appendCodePoint(cp)
    }
    return out.toString()
}

/** "1.4 MB" — the unit ladder the downloads list shows sizes in. */
internal fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return Strings.get(R.string.library_size_bytes, sizeNumber(bytes.toDouble(), 0))
    val units = listOf(
        R.string.library_size_kb,
        R.string.library_size_mb,
        R.string.library_size_gb,
        R.string.library_size_tb,
    )
    var value = bytes / 1024.0
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return Strings.get(units[unit], if (value >= 100) sizeNumber(value, 0) else sizeNumber(value, 1))
}

/**
 * [value] in the user's locale ("1.5", "1,5") with [digits] decimals,
 * no grouping: whole numbers are cut off, one decimal is rounded half up.
 */
private fun sizeNumber(value: Double, digits: Int): String =
    NumberFormat.getNumberInstance().apply {
        minimumFractionDigits = digits
        maximumFractionDigits = digits
        isGroupingUsed = false
        roundingMode = if (digits == 0) RoundingMode.DOWN else RoundingMode.HALF_UP
    }.format(value)

/**
 * The origin (`https://host[:port]/`) of the page a web download came
 * from, if it's one worth naming at all: an ordinary http(s) page, not
 * a virtual dweb origin, an onchain app's (#123), home or `about:`.
 * This — never the page's path or query — is all a download remembers
 * about its page.
 */
internal fun downloadRefererOrigin(pageUrl: String?): String? {
    if (pageUrl.isNullOrBlank() || VirtualOrigin.isVirtualUrl(pageUrl) ||
        OnchainAppRef.isVirtualUrl(pageUrl)
    ) {
        return null
    }
    return webOrigin(pageUrl)?.let { "$it/" }
}

/**
 * The `Referer` a native re-fetch of [requestUrl] sends, given the
 * page's [refererOrigin] (from [downloadRefererOrigin]): that origin,
 * and only when [requestUrl] is on the same origin.
 *
 * Chromium's own request would have obeyed the page's Referrer-Policy,
 * which `DownloadListener` doesn't report. Sending just the page's
 * origin to that same origin reveals nothing the server doesn't already
 * know from `Host`, so it is within what every policy allows in
 * substance, while still satisfying the "came from our own site" check
 * that gates some downloads. Cross-origin requests, https→http
 * downgrades (a different origin by scheme) and every redirect hop that
 * leaves the origin get none. Evaluated per hop.
 */
internal fun downloadReferer(refererOrigin: String?, requestUrl: String): String? {
    val origin = refererOrigin?.removeSuffix("/") ?: return null
    return if (webOrigin(requestUrl) == origin) "$origin/" else null
}

/**
 * Who asked for a download, as the prompt names them: the origin of
 * the page ([pageUrl]) — its dweb root (`bzz://<hash>`, `name.eth`) for
 * a page on a virtual origin or the gateway ([gatewayDisplay] is
 * `Gateways.toDisplay`), `scheme://host[:port]` for a web page, and
 * just the scheme (`data:`) for a page with no host. Null when there's
 * no page: an address the user submitted.
 */
internal fun downloadRequester(pageUrl: String?, gatewayDisplay: (String) -> String = { it }): String? {
    if (pageUrl == null) return null
    val display = gatewayDisplay(pageUrl)
    if (display != pageUrl) {
        val rest = display.substringAfter("://", display)
        val prefix = display.removeSuffix(rest)
        return prefix + rest.substringBefore('/').substringBefore('?').substringBefore('#')
    }
    webOrigin(pageUrl)?.let { return it }
    val scheme = pageUrl.substringBefore(':', "").lowercase()
    return if (scheme.isNotEmpty() && scheme.all { it.isLetterOrDigit() || it in "+-." }) "$scheme:" else Strings.get(R.string.library_download_requester_unknown)
}


/** Where a web download's redirect hop leads, or why it can't be followed. */
internal sealed class DownloadRedirect {
    data class Follow(val url: String) : DownloadRedirect()
    data class Refuse(val reason: String) : DownloadRedirect()
}

/**
 * Resolve a redirect's `Location` against the URL that answered it.
 * Only http(s) hops are followed: the native fetch can't speak any
 * other scheme (`ftp:`, `intent:`, `data:` …), so those end the
 * download with a reason that names the scheme instead of failing
 * somewhere deeper with a generic error.
 */
internal fun downloadRedirect(current: String, location: String?): DownloadRedirect {
    if (location.isNullOrBlank()) return DownloadRedirect.Refuse(Strings.get(R.string.library_download_redirect_no_location))
    val resolved = runCatching { java.net.URL(java.net.URL(current), location.trim()) }.getOrNull()
    val scheme = resolved?.protocol?.lowercase()
        ?: Regex("^([A-Za-z][A-Za-z0-9+.-]*):").find(location.trim())?.groupValues?.get(1)?.lowercase()
    return when {
        scheme != null && scheme != "http" && scheme != "https" ->
            DownloadRedirect.Refuse(Strings.get(R.string.library_download_redirect_unsupported, scheme))
        resolved == null || resolved.host.isNullOrEmpty() -> DownloadRedirect.Refuse(Strings.get(R.string.library_download_redirect_malformed))
        else -> DownloadRedirect.Follow(resolved.toString())
    }
}
