package baby.freedom.mobile.browser

import java.io.InputStream

/**
 * How the interceptor answers a media request's `Range` header against a
 * body of [total] bytes when the gateway ignored the header and sent the
 * whole body ([mediaReplyFor]). The header is page-controlled — any
 * `fetch()` from a dweb page can set it — so nothing here may throw.
 */
internal sealed class ByteRangeAnswer {
    /** No usable range: the whole body, `200`. */
    object Full : ByteRangeAnswer()

    /** `206` for bytes [start]..[end], both inclusive and inside the body. */
    data class Partial(val start: Long, val end: Long) : ByteRangeAnswer() {
        val length: Long get() = end - start + 1
    }

    /** `416`, with a `Content-Range` naming only the total. */
    object Unsatisfiable : ByteRangeAnswer()
}

/**
 * A single byte-range in a `Range` header — `bytes=<first>-<last>`. Only
 * single ranges are served (the common case for HTML5 media);
 * multipart/byteranges is vanishingly rare and Chromium never sends it
 * for `<video>`. Any number of digits matches: a position too large for a
 * `Long` is past every body, not a parse failure.
 */
private val RANGE_REGEX = Regex("""^bytes=(\d+)?-(\d+)?$""")

/**
 * The answer to [header] (null: none sent) for a body of [total] bytes.
 * A header that isn't a single byte-range (no digits on either side
 * included) is ignored and the whole body served, as RFC 9110 §14.2
 * allows; a range that starts past the body, an empty suffix, or a last
 * position before the first is [ByteRangeAnswer.Unsatisfiable]. All the
 * math is in `Long`, so an offset past 2³¹ can't wrap to another slice.
 */
internal fun byteRangeFor(header: String?, total: Long): ByteRangeAnswer {
    val match = header?.let { RANGE_REGEX.matchEntire(it.trim()) } ?: return ByteRangeAnswer.Full
    val firstStr = match.groupValues[1]
    val lastStr = match.groupValues[2]
    // Overflowing a Long means "past any body": the max is as good.
    fun pos(s: String): Long = s.toLongOrNull() ?: Long.MAX_VALUE
    return when {
        firstStr.isEmpty() && lastStr.isEmpty() -> ByteRangeAnswer.Full
        firstStr.isEmpty() -> {
            val suffix = pos(lastStr).coerceAtMost(total)
            if (suffix <= 0) ByteRangeAnswer.Unsatisfiable
            else ByteRangeAnswer.Partial(total - suffix, total - 1)
        }
        else -> {
            val first = pos(firstStr)
            val last = if (lastStr.isEmpty()) total - 1 else pos(lastStr)
            if (first >= total || last < first) ByteRangeAnswer.Unsatisfiable
            else ByteRangeAnswer.Partial(first, last.coerceAtMost(total - 1))
        }
    }
}

/**
 * How a media request's gateway answer is handed to the WebView: the
 * status, reason and headers of the reply, and which bytes of the
 * gateway's body make up its body — [skip] bytes dropped first, then at
 * most [length] (null: the rest) passed on; none at all when [empty].
 */
internal data class MediaReply(
    val status: Int,
    val reason: String,
    val headers: Map<String, String>,
    val skip: Long = 0,
    val length: Long? = null,
    val empty: Boolean = false,
)

/**
 * The reply to a media request that sent [rangeHeader] (null: none), for
 * the gateway's answer [status]/[reason] with [headers] (already filtered
 * by `gatewayResponseHeaders`, so without a `Content-Length`) and a body
 * of [contentLength] bytes (negative: unknown).
 *
 * The request's Range header went to the gateway, so its answer is
 * streamed as it is: a `206` keeps its `Content-Range` and gets its
 * `Content-Length` back, which Chromium's media loader sizes the resource
 * by. Only when the gateway ignored the header — a whole-body `200` of a
 * known length to a ranged request — is the slice cut here, from the
 * stream itself ([skip]/[length]) rather than from a buffered copy, so no
 * body is ever held in memory, however large. A `200` of unknown length
 * can't be sliced (there's no total for a `Content-Range`) and is passed
 * on whole, as is every other status.
 *
 * Nor is a `200` sliced when the request carried `If-Range` ([ifRange]):
 * that header went to the gateway too, and a whole `200` is then its
 * answer that the validator no longer matches — the representation
 * changed (an ENS name or feed now pointing at new content), so the
 * client must get the new body whole, not a `206` of new bytes it would
 * splice onto its old copy (RFC 9110 §13.1.5). Passing it on whole is
 * right even from a gateway that ignores both headers: a server may
 * always answer a range with the full `200`.
 */
internal fun mediaReplyFor(
    rangeHeader: String?,
    status: Int,
    reason: String,
    headers: Map<String, String>,
    contentLength: Long,
    ifRange: Boolean = false,
): MediaReply {
    val length = if (contentLength >= 0) contentLength.toString() else null
    return when {
        status == 206 -> MediaReply(
            status, reason,
            headers.with("Accept-Ranges", "bytes").let { h -> length?.let { h.with("Content-Length", it) } ?: h },
        )
        status != 200 || contentLength < 0 -> MediaReply(status, reason, headers)
        else -> {
            val total = contentLength
            val whole = headers.with("Accept-Ranges", "bytes")
            // A Range the gateway answered whole: sliced here — unless
            // the whole answer is If-Range's "changed", meant whole.
            val range = if (rangeHeader == null || ifRange) ByteRangeAnswer.Full else byteRangeFor(rangeHeader, total)
            when (range) {
                ByteRangeAnswer.Full -> MediaReply(200, reason, whole.with("Content-Length", total.toString()))
                is ByteRangeAnswer.Partial -> MediaReply(
                    206, "Partial Content",
                    whole.with("Content-Range", "bytes ${range.start}-${range.end}/$total")
                        .with("Content-Length", range.length.toString()),
                    skip = range.start,
                    length = range.length,
                )
                ByteRangeAnswer.Unsatisfiable -> MediaReply(
                    416, "Range Not Satisfiable",
                    whole.with("Content-Range", "bytes */$total").with("Content-Length", "0"),
                    empty = true,
                )
            }
        }
    }
}

/** These headers with [name] set to [value], replacing any spelling of it. */
private fun Map<String, String>.with(name: String, value: String): Map<String, String> =
    filterKeys { !it.equals(name, ignoreCase = true) } + (name to value)

/**
 * [input] with its first [skip] bytes dropped and at most [length] (null:
 * all the rest) passed on — a slice of a body streamed straight through,
 * never buffered. The skip happens on the first read, on the reader's
 * thread rather than the interceptor's; a body that ends early just ends
 * the slice early. Closing closes [input].
 */
internal class SlicedInputStream(
    private val input: InputStream,
    private var skip: Long,
    length: Long?,
) : InputStream() {
    private var left: Long = length ?: Long.MAX_VALUE

    private fun skipped(): Boolean {
        if (skip > 0) {
            val scratch = ByteArray(SKIP_CHUNK)
            while (skip > 0) {
                // InputStream.skip may skip nothing short of the end: read instead.
                val r = input.read(scratch, 0, minOf(skip, SKIP_CHUNK.toLong()).toInt())
                if (r < 0) {
                    left = 0
                    skip = 0
                    return false
                }
                skip -= r
            }
        }
        return left > 0
    }

    override fun read(): Int {
        if (!skipped()) return -1
        val b = input.read()
        if (b < 0) left = 0 else left--
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (!skipped()) return -1
        val r = input.read(b, off, minOf(len.toLong(), left).toInt())
        if (r < 0) left = 0 else left -= r
        return r
    }

    override fun available(): Int =
        if (skip > 0 || left <= 0) 0 else minOf(input.available().toLong(), left).toInt()

    override fun close() = input.close()
}

private const val SKIP_CHUNK = 64 * 1024

/**
 * The bytes WebView itself skips at the start of an intercepted response's
 * body for a request that sent [rangeHeader]: Chromium's
 * `AndroidStreamReaderURLLoader` parses a single `bytes=<first>-…` range
 * and, whatever status the response has, seeks the stream to `<first>`
 * (`InputStreamReader::Seek`) — a body made for a `200` of the whole
 * file. Several ranges, or none: nothing. A suffix range (`bytes=-n`) is
 * not a fixed skip, so it isn't counted here, but WebView does seek for
 * it too: `ComputeBounds` takes the first byte as `available()` minus
 * `min(available(), n)`, so a body that reports more than `n` bytes
 * already buffered would lose the difference — [webViewSeekProof] covers
 * it by reporting nothing buffered ([isWebViewSuffixRange]).
 */
internal fun webViewSkipFor(rangeHeader: String?): Long {
    val match = rangeHeader?.let { WEBVIEW_RANGE_REGEX.matchEntire(it.trim()) } ?: return 0
    return match.groupValues[1].toLongOrNull() ?: 0
}

private val WEBVIEW_RANGE_REGEX = Regex("""^bytes\s*=\s*(\d+)\s*-\s*\d*$""", RegexOption.IGNORE_CASE)

/**
 * Whether [rangeHeader] is a single suffix range (`bytes=-n`), for which
 * WebView seeks a body to `available() - min(available(), n)`
 * ([webViewSkipFor]): harmless only while the body reports nothing
 * buffered.
 */
internal fun isWebViewSuffixRange(rangeHeader: String?): Boolean =
    rangeHeader?.let { WEBVIEW_SUFFIX_REGEX.matches(it.trim()) } ?: false

private val WEBVIEW_SUFFIX_REGEX = Regex("""^bytes\s*=\s*-\s*\d+$""", RegexOption.IGNORE_CASE)

/**
 * [body], the whole answer to a request that sent [rangeHeader], protected
 * from WebView's own seek ([webViewSkipFor]) whatever the response's
 * status: a `206` body already starts at the range, and a whole `200`
 * (a gateway that ignored Range) or an error page (`416`, …) is meant
 * whole, so no answer the proxy hands back may be cut by WebView again —
 * for a suffix range (`bytes=-n`) too, whose seek WebView derives from
 * the body's `available()`.
 */
internal fun webViewSeekProof(body: InputStream, rangeHeader: String?): InputStream {
    val skip = webViewSkipFor(rangeHeader)
    // A suffix range: no fixed skip, but WebView's seek is computed from
    // available(), which a SeekAbsorbingInputStream reports as 0, so it
    // seeks to the start and skips nothing.
    return if (skip > 0 || isWebViewSuffixRange(rangeHeader)) SeekAbsorbingInputStream(body, skip) else body
}

/**
 * [input], a body that already starts at the requested range ([skip] =
 * [webViewSkipFor]), made proof against WebView's own seek: the first
 * [skip] bytes it skips are skipped without reading anything, and
 * [available] is 0, so WebView doesn't check the range against what's
 * buffered (`VerifyRequestedRange`) either. Without this a gateway's `206`
 * for a seek past the start fails in WebView with no request to the
 * gateway ever failing: the skip runs past the end of the slice, or the
 * range is "unsatisfiable" against the bytes buffered so far, and the
 * media element ends on `MEDIA_ERR_NETWORK`. Once anything is read, every
 * skip is a real one.
 */
internal class SeekAbsorbingInputStream(
    private val input: InputStream,
    private var skip: Long,
) : InputStream() {
    override fun skip(n: Long): Long {
        if (n <= 0) return 0
        if (skip > 0) {
            val absorbed = minOf(n, skip)
            skip -= absorbed
            return absorbed
        }
        return input.skip(n)
    }

    override fun available(): Int = 0

    override fun read(): Int {
        skip = 0
        return input.read()
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        skip = 0
        return input.read(b, off, len)
    }

    override fun close() = input.close()
}
