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

/** What [readBounded] made of a body. */
internal sealed class BoundedRead {
    /** The whole body; exactly `bytes.size` bytes of the budget are held for it. */
    class Bytes(val bytes: ByteArray) : BoundedRead()

    /** Past the limit: never worth buffering. Nothing held. */
    object TooLarge : BoundedRead()

    /** Within the limit, but the budget had no room for it right now. Nothing held. */
    object NoRoom : BoundedRead()
}

private const val READ_CHUNK = 64 * 1024

/**
 * All of [input] if it fits in [limit] bytes and in the memory budget
 * behind [reserve]/[release] (#355). Every byte array is reserved before
 * it is allocated, so parallel reads together can't outgrow the budget:
 *
 * - [expected] (the announced length, or negative if unknown) within the
 *   limit is reserved up front and read straight into one array of that
 *   size, so there's no growth or copy;
 * - an unknown length is read in [READ_CHUNK]s, each reserved first, and
 *   joined once the end is reached (its copy reserved too); it gives up
 *   one chunk past [limit].
 *
 * On [BoundedRead.Bytes] the reservation left is exactly its size; on
 * any other answer, or a thrown [IOException], nothing is left reserved.
 */
@Throws(IOException::class)
internal fun readBounded(
    input: InputStream,
    limit: Int,
    expected: Long,
    reserve: (Long) -> Boolean,
    release: (Long) -> Unit,
): BoundedRead {
    if (expected > limit) return BoundedRead.TooLarge
    var held = 0L
    try {
        if (expected >= 0) {
            if (!reserve(expected)) return BoundedRead.NoRoom
            held = expected
            val out = ByteArray(expected.toInt())
            var n = 0
            while (n < out.size) {
                val r = input.read(out, n, out.size - n)
                if (r < 0) break
                n += r
            }
            if (n == out.size) {
                // Longer than announced: not buffered this time.
                if (input.read() >= 0) {
                    release(held)
                    held = 0
                    return BoundedRead.NoRoom
                }
                held = 0
                return BoundedRead.Bytes(out)
            }
            // Shorter than announced: keep just what came.
            if (!reserve(n.toLong())) {
                release(held)
                held = 0
                return BoundedRead.NoRoom
            }
            held += n
            val bytes = out.copyOf(n)
            release(expected)
            held = 0
            return BoundedRead.Bytes(bytes)
        }
        val chunks = ArrayList<ByteArray>()
        var total = 0L
        var lastFill = READ_CHUNK
        while (true) {
            if (total > limit) {
                release(held)
                held = 0
                return BoundedRead.TooLarge
            }
            if (lastFill == READ_CHUNK) {
                if (!reserve(READ_CHUNK.toLong())) {
                    release(held)
                    held = 0
                    return BoundedRead.NoRoom
                }
                held += READ_CHUNK
                chunks += ByteArray(READ_CHUNK)
                lastFill = 0
            }
            val chunk = chunks.last()
            val r = input.read(chunk, lastFill, READ_CHUNK - lastFill)
            if (r < 0) break
            lastFill += r
            total += r
        }
        if (!reserve(total)) {
            release(held)
            held = 0
            return BoundedRead.NoRoom
        }
        val out = ByteArray(total.toInt())
        var at = 0
        for (chunk in chunks) {
            val n = minOf(chunk.size.toLong(), total - at).toInt()
            System.arraycopy(chunk, 0, out, at, n)
            at += n
        }
        release(held)
        held = 0
        return BoundedRead.Bytes(out)
    } catch (t: Throwable) {
        release(held)
        throw t
    }
}
