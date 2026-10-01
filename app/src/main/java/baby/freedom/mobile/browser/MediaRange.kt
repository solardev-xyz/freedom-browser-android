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
 * A buffered media body (#355): [size] bytes kept as the arrays they were
 * read into, in order, so a body of unknown length never needs a joined
 * copy (which would need its whole size a second time, and could find no
 * room for it after the body was already read). [held] is what the
 * arrays take, at most one partly-filled [READ_CHUNK] more than [size].
 */
internal class MediaBytes(private val chunks: List<ByteArray>, val size: Long) {
    val held: Long = chunks.sumOf { it.size.toLong() }

    init {
        require(size in 0..held)
    }

    /** Bytes [start] until [start] + [length], both inside the body, as a stream over the arrays. */
    fun stream(start: Long = 0, length: Long = size - start): InputStream {
        require(start >= 0 && length >= 0 && start + length <= size)
        val parts = ArrayList<InputStream>()
        var at = 0L // offset of the current array in the body
        val end = start + length
        for (chunk in chunks) {
            val chunkEnd = minOf(at + chunk.size, size)
            val from = maxOf(start, at)
            val to = minOf(end, chunkEnd)
            if (from < to) {
                parts += java.io.ByteArrayInputStream(chunk, (from - at).toInt(), (to - from).toInt())
            }
            at += chunk.size
            if (at >= end) break
        }
        return java.io.SequenceInputStream(java.util.Collections.enumeration(parts))
    }

    /** The whole body as one array (a copy, for tests). */
    fun toByteArray(): ByteArray = stream().readBytes()
}

/** What [readBounded] made of a body. */
internal sealed class BoundedRead {
    /** The whole body; exactly [MediaBytes.held] bytes of the budget are held for it. */
    class Bytes(val body: MediaBytes) : BoundedRead()

    /**
     * Never worth buffering: past the limit, or longer than its announced
     * length (a body that doesn't match its own `Content-Length` will do
     * so again). Nothing held.
     */
    object TooLarge : BoundedRead()

    /**
     * Within the limit, but the budget had no room for it right now. Only
     * answered when a reservation fails *before* the bytes it was for are
     * read, never once the whole body is in hand. Nothing held.
     */
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
 *   size, so there's no growth or copy. A body shorter than announced
 *   keeps that array (holding its unused tail); one longer is
 *   [BoundedRead.TooLarge];
 * - an unknown length is read in [READ_CHUNK]s, each reserved first, and
 *   kept as those chunks ([MediaBytes]), so nothing has to be reserved
 *   once the end is reached; it gives up one chunk past [limit]. When a
 *   full chunk's successor can't be reserved, one byte is read to tell
 *   "more to come" ([BoundedRead.NoRoom]) from "that was the end" (the
 *   body, with no reservation needed — R3-M1: a body that is an exact
 *   multiple of [READ_CHUNK] would otherwise be thrown away whole) —
 *   unless no chunk was read yet, which is [BoundedRead.NoRoom] at once
 *   (R4-M3: the body is streamed rather than waiting on a byte). The
 *   last chunk is trimmed only if [reserveFree] — which must never evict
 *   a buffered body — has room for its copy (R3-M2): saving under one
 *   chunk is never worth a body that would then be downloaded again.
 *
 * So a body read to its end is never thrown away for want of room (R2-M1):
 * that would leave it unbuffered, and every later Range request for it
 * would download it in full again before streaming.
 *
 * On [BoundedRead.Bytes] the reservation left is exactly its
 * [MediaBytes.held]; on any other answer, or a thrown [IOException],
 * nothing is left reserved.
 */
@Throws(IOException::class)
internal fun readBounded(
    input: InputStream,
    limit: Int,
    expected: Long,
    reserve: (Long) -> Boolean,
    release: (Long) -> Unit,
    reserveFree: (Long) -> Boolean,
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
            // Longer than announced: streamed, every time.
            if (n == out.size && input.read() >= 0) {
                release(held)
                held = 0
                return BoundedRead.TooLarge
            }
            // Shorter than announced keeps the array: no copy to reserve.
            held = 0
            return BoundedRead.Bytes(MediaBytes(listOf(out), n.toLong()))
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
                    // No room for even the first chunk: streamed, without
                    // waiting on this full GET's first byte to learn
                    // whether the body is empty (R4-M3).
                    if (total == 0L) return BoundedRead.NoRoom
                    // The chunks so far are full: the body may end right
                    // here, and then it is whole with nothing more to hold.
                    val next = input.read()
                    if (next < 0) break
                    release(held)
                    held = 0
                    return if (total + 1 > limit) BoundedRead.TooLarge else BoundedRead.NoRoom
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
        // The last chunk is full only when the body ended on a boundary
        // with no room for another (nothing to trim). Otherwise drop it if
        // empty, else trim it when there's free room for the copy (keeping
        // it whole otherwise; never evicting for it).
        if (lastFill == 0) {
            chunks.removeAt(chunks.size - 1)
            release(READ_CHUNK.toLong())
            held -= READ_CHUNK
        } else if (lastFill < READ_CHUNK && reserveFree(lastFill.toLong())) {
            chunks[chunks.size - 1] = chunks.last().copyOf(lastFill)
            release(READ_CHUNK.toLong())
            held += lastFill - READ_CHUNK
        }
        held = 0
        return BoundedRead.Bytes(MediaBytes(chunks, total))
    } catch (t: Throwable) {
        release(held)
        throw t
    }
}

/**
 * [block]'s answer on [input], which is then closed. A failure to close
 * is ignored rather than thrown (R4-M4): the body is already in hand, and
 * throwing would lose it together with the budget it holds — a
 * [BoundedRead.Bytes]' reservation would never be given back. A failure
 * in [block] itself is thrown as ever.
 */
internal inline fun <T> readThenClose(input: InputStream, block: (InputStream) -> T): T {
    try {
        return block(input)
    } finally {
        try {
            input.close()
        } catch (_: IOException) {
        } catch (_: RuntimeException) {
        }
    }
}
