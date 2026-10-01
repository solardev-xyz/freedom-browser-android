package baby.freedom.mobile.browser

import java.io.IOException
import java.io.InputStream

/**
 * How the interceptor answers a media request's `Range` header against a
 * buffered body of [total] bytes (#355). The header is page-controlled —
 * any `fetch()` from a dweb page can set it — so nothing here may throw.
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
 * All of [input], or null once it runs past [limit] bytes — read no
 * further than one byte beyond it, so a body too large to buffer costs at
 * most [limit] bytes of memory before the caller falls back to streaming.
 */
@Throws(IOException::class)
internal fun readAtMost(input: InputStream, limit: Int): ByteArray? {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    while (true) {
        val n = input.read(buf)
        if (n < 0) return out.toByteArray()
        if (out.size().toLong() + n > limit) return null
        out.write(buf, 0, n)
    }
}
