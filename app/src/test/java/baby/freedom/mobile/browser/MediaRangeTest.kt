package baby.freedom.mobile.browser

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [byteRangeFor]: a page-chosen Range header never throws (#355);
 * [mediaReplyFor]/[SlicedInputStream]: a media answer is streamed, and a
 * Range the gateway ignored is sliced from the stream, never buffered;
 * [SeekAbsorbingInputStream]: WebView's own seek doesn't cut it again.
 */
class MediaRangeTest {
    private fun partial(start: Long, end: Long) = ByteRangeAnswer.Partial(start, end)

    @Test
    fun `no header or an unparsable one serves the whole body`() {
        assertEquals(ByteRangeAnswer.Full, byteRangeFor(null, 100))
        assertEquals(ByteRangeAnswer.Full, byteRangeFor("bytes=-", 100))
        assertEquals(ByteRangeAnswer.Full, byteRangeFor("bytes=0-10,20-30", 100))
        assertEquals(ByteRangeAnswer.Full, byteRangeFor("items=0-10", 100))
        assertEquals(ByteRangeAnswer.Full, byteRangeFor("bytes=-1-2", 100))
    }

    @Test
    fun `ordinary ranges`() {
        assertEquals(partial(0, 99), byteRangeFor("bytes=0-", 100))
        assertEquals(partial(10, 19), byteRangeFor(" bytes=10-19 ", 100))
        assertEquals(partial(10, 99), byteRangeFor("bytes=10-1000", 100))
        assertEquals(partial(90, 99), byteRangeFor("bytes=-10", 100))
        assertEquals(partial(0, 99), byteRangeFor("bytes=-1000", 100))
        assertEquals(10L, partial(10, 19).length)
    }

    @Test
    fun `positions too large for a Long don't throw`() {
        assertEquals(ByteRangeAnswer.Unsatisfiable, byteRangeFor("bytes=99999999999999999999-", 100))
        assertEquals(partial(0, 99), byteRangeFor("bytes=-99999999999999999999", 100))
        assertEquals(partial(5, 99), byteRangeFor("bytes=5-99999999999999999999", 100))
        assertEquals(
            ByteRangeAnswer.Unsatisfiable,
            byteRangeFor("bytes=99999999999999999999-99999999999999999999", 100),
        )
    }

    @Test
    fun `offsets past 2^31 aren't truncated to another slice`() {
        assertEquals(ByteRangeAnswer.Unsatisfiable, byteRangeFor("bytes=4294967296-", 100))
        assertEquals(ByteRangeAnswer.Unsatisfiable, byteRangeFor("bytes=2147483648-", 100))
        assertEquals(ByteRangeAnswer.Unsatisfiable, byteRangeFor("bytes=4294967296-4294967300", 100))
    }

    @Test
    fun `unsatisfiable ranges`() {
        assertEquals(ByteRangeAnswer.Unsatisfiable, byteRangeFor("bytes=100-", 100))
        assertEquals(ByteRangeAnswer.Unsatisfiable, byteRangeFor("bytes=20-10", 100))
        assertEquals(ByteRangeAnswer.Unsatisfiable, byteRangeFor("bytes=-0", 100))
        assertEquals(ByteRangeAnswer.Unsatisfiable, byteRangeFor("bytes=0-", 0))
        assertEquals(ByteRangeAnswer.Unsatisfiable, byteRangeFor("bytes=-5", 0))
    }

    private val gateway = mapOf(
        "content-type" to "audio/x-wav",
        "accept-ranges" to "bytes",
        "Access-Control-Allow-Origin" to "*",
    )

    /** [headers]' value for [name], whatever its spelling, asserting there's at most one. */
    private fun MediaReply.header(name: String): String? {
        val values = headers.filterKeys { it.equals(name, ignoreCase = true) }.values
        assertTrue("one $name in $headers", values.size <= 1)
        return values.firstOrNull()
    }

    @Test
    fun `a gateway 206 is streamed as it is, with its Content-Length back`() {
        val reply = mediaReplyFor(
            "bytes=0-", 206, "Partial Content",
            gateway + ("content-range" to "bytes 0-54958119/54958120"), 54_958_120,
        )
        assertEquals(206, reply.status)
        assertEquals("bytes 0-54958119/54958120", reply.header("Content-Range"))
        assertEquals("54958120", reply.header("Content-Length"))
        assertEquals("bytes", reply.header("Accept-Ranges"))
        assertEquals("*", reply.header("Access-Control-Allow-Origin"))
        assertEquals(0L, reply.skip)
        assertNull(reply.length)
        assertFalse(reply.empty)
    }

    @Test
    fun `a whole 200 to an unranged request keeps its length`() {
        val reply = mediaReplyFor(null, 200, "OK", gateway, 1000)
        assertEquals(200, reply.status)
        assertEquals("1000", reply.header("Content-Length"))
        assertEquals("bytes", reply.header("Accept-Ranges"))
        assertEquals(0L, reply.skip)
        assertNull(reply.length)
    }

    @Test
    fun `a Range the gateway ignored is sliced from the stream`() {
        val reply = mediaReplyFor("bytes=100-199", 200, "OK", gateway - "accept-ranges", 1000)
        assertEquals(206, reply.status)
        assertEquals("bytes 100-199/1000", reply.header("Content-Range"))
        assertEquals("100", reply.header("Content-Length"))
        assertEquals("bytes", reply.header("Accept-Ranges"))
        assertEquals(100L, reply.skip)
        assertEquals(100L, reply.length)

        val suffix = mediaReplyFor("bytes=-10", 200, "OK", gateway, 1000)
        assertEquals("bytes 990-999/1000", suffix.header("Content-Range"))
        assertEquals(990L, suffix.skip)
        assertEquals(10L, suffix.length)

        // Offsets past 2^31 stay Longs end to end.
        val far = mediaReplyFor("bytes=3000000000-", 200, "OK", gateway, 4_000_000_000)
        assertEquals(3_000_000_000L, far.skip)
        assertEquals(1_000_000_000L, far.length)
        assertEquals("bytes 3000000000-3999999999/4000000000", far.header("Content-Range"))
    }

    @Test
    fun `an ignored Range past the body is a 416 with no body`() {
        val reply = mediaReplyFor("bytes=5000-", 200, "OK", gateway, 1000)
        assertEquals(416, reply.status)
        assertEquals("bytes */1000", reply.header("Content-Range"))
        assertEquals("0", reply.header("Content-Length"))
        assertTrue(reply.empty)
    }

    @Test
    fun `an unparsable Range the gateway ignored serves the whole body`() {
        val reply = mediaReplyFor("bytes=0-10,20-30", 200, "OK", gateway, 1000)
        assertEquals(200, reply.status)
        assertEquals("1000", reply.header("Content-Length"))
        assertEquals(0L, reply.skip)
        assertNull(reply.length)
    }

    @Test
    fun `a 200 of unknown length is passed on whole`() {
        val reply = mediaReplyFor("bytes=100-", 200, "OK", gateway - "accept-ranges", -1)
        assertEquals(200, reply.status)
        assertNull(reply.header("Content-Length"))
        assertNull(reply.header("Accept-Ranges"))
        assertEquals(0L, reply.skip)
        assertNull(reply.length)
    }

    @Test
    fun `other statuses are passed on as they are`() {
        val notFound = mapOf("content-type" to "application/json")
        for (status in listOf(404, 416, 500, 502)) {
            val reply = mediaReplyFor("bytes=0-", status, "x", notFound, 47)
            assertEquals(status, reply.status)
            assertEquals(notFound, reply.headers)
            assertEquals(0L, reply.skip)
            assertNull(reply.length)
            assertFalse(reply.empty)
        }
    }

    private fun body(n: Int) = ByteArray(n) { (it % 251).toByte() }

    /** An input that hands out at most [step] bytes per read and never skips, like a slow socket. */
    private class Trickle(private val data: ByteArray, private val step: Int) : InputStream() {
        var at = 0
        var closed = false
        override fun read(): Int = if (at < data.size) data[at++].toInt() and 0xff else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (at >= data.size) return -1
            val n = minOf(len, step, data.size - at)
            System.arraycopy(data, at, b, off, n)
            at += n
            return n
        }
        override fun skip(n: Long): Long = 0
        override fun close() { closed = true }
    }

    @Test
    fun `a sliced stream skips and limits without holding the body`() {
        val data = body(300_000)
        val input = Trickle(data, 1000)
        val slice = SlicedInputStream(input, 150_000, 100_000)
        // Nothing is read until the slice is.
        assertEquals(0, input.at)
        assertArrayEquals(data.copyOfRange(150_000, 250_000), slice.readBytes())
        assertEquals(-1, slice.read())
        // Stops at the end of the slice: the rest is never pulled.
        assertEquals(250_000, input.at)
        slice.close()
        assertTrue(input.closed)
    }

    @Test
    fun `a sliced stream with no length runs to the end`() {
        val data = body(10_000)
        assertArrayEquals(
            data.copyOfRange(9_000, 10_000),
            SlicedInputStream(Trickle(data, 333), 9_000, null).readBytes(),
        )
        val single = SlicedInputStream(ByteArrayInputStream(data), 5, 2)
        assertEquals(data[5].toInt() and 0xff, single.read())
        assertEquals(data[6].toInt() and 0xff, single.read())
        assertEquals(-1, single.read())
    }

    @Test
    fun `a body shorter than the skip ends the slice`() {
        val slice = SlicedInputStream(Trickle(body(100), 7), 500, 10)
        assertEquals(-1, slice.read(ByteArray(10), 0, 10))
        assertEquals(-1, slice.read())
    }

    @Test
    fun `the bytes WebView skips itself`() {
        assertEquals(0L, webViewSkipFor(null))
        assertEquals(0L, webViewSkipFor("bytes=0-"))
        assertEquals(46_071_808L, webViewSkipFor("bytes=46071808-"))
        assertEquals(100L, webViewSkipFor(" Bytes = 100 - 199 "))
        assertEquals(3_000_000_000L, webViewSkipFor("bytes=3000000000-"))
        // Suffix, several ranges, nonsense, past a Long: no seek.
        assertEquals(0L, webViewSkipFor("bytes=-500"))
        assertEquals(0L, webViewSkipFor("bytes=0-10,20-30"))
        assertEquals(0L, webViewSkipFor("items=5-"))
        assertEquals(0L, webViewSkipFor("bytes=99999999999999999999-"))
    }

    /**
     * What Chromium's `InputStreamReader::Seek` does to an intercepted
     * body for a range starting at [first]: check the range against
     * `available()`, then `skip()` in a loop, failing on a skip of 0.
     */
    private fun webViewSeek(stream: InputStream, first: Long): Boolean {
        val size = stream.available()
        if (size > 0 && first >= size) return false
        var left = first
        while (left > 0) {
            val skipped = stream.skip(left)
            if (skipped <= 0) return false
            left -= skipped
        }
        return true
    }

    @Test
    fun `a body that starts at the range survives WebView's own seek`() {
        val file = body(50_000)
        // The gateway's 206 for bytes=40000-: the slice, from its first byte.
        val slice = { ByteArrayInputStream(file, 40_000, 10_000) }
        // Unprotected, WebView's seek cuts it again (or fails outright).
        assertFalse(webViewSeek(slice(), 40_000))

        val absorbing = SeekAbsorbingInputStream(slice(), webViewSkipFor("bytes=40000-"))
        assertTrue(webViewSeek(absorbing, 40_000))
        assertArrayEquals(file.copyOfRange(40_000, 50_000), absorbing.readBytes())
    }

    @Test
    fun `once read, a seek-absorbing stream skips for real`() {
        val data = body(100)
        val stream = SeekAbsorbingInputStream(ByteArrayInputStream(data), 50)
        assertEquals(data[0].toInt() and 0xff, stream.read())
        assertEquals(10L, stream.skip(10))
        assertEquals(data[11].toInt() and 0xff, stream.read())
        assertEquals(0, stream.available())
    }

    @Test
    fun `a sliced fallback survives WebView's seek too`() {
        // The gateway ignored bytes=1000-: whole body, sliced here.
        val file = body(5_000)
        val reply = mediaReplyFor("bytes=1000-", 200, "OK", gateway, 5_000)
        val stream = SeekAbsorbingInputStream(
            SlicedInputStream(Trickle(file, 300), reply.skip, reply.length),
            webViewSkipFor("bytes=1000-"),
        )
        assertTrue(webViewSeek(stream, 1_000))
        assertArrayEquals(file.copyOfRange(1_000, 5_000), stream.readBytes())
    }

    @Test
    fun `a whole 200 to a ranged request reaches the page whole`() {
        // A non-media fetch from a gateway that ignored bytes=1000-1999.
        val file = body(300_000)
        val stream = webViewSeekProof(ByteArrayInputStream(file), "bytes=1000-1999")
        assertTrue(webViewSeek(stream, 1_000))
        assertArrayEquals(file, stream.readBytes())
    }

    @Test
    fun `an error body to a ranged request survives WebView's seek`() {
        // A gateway 416 for bytes=5000000-: a short body, far shorter than the skip.
        val error = "Requested Range Not Satisfiable".toByteArray()
        assertFalse(webViewSeek(ByteArrayInputStream(error), 5_000_000))
        val stream = webViewSeekProof(ByteArrayInputStream(error), "bytes=5000000-")
        assertTrue(webViewSeek(stream, 5_000_000))
        assertArrayEquals(error, stream.readBytes())
    }

    @Test
    fun `an unranged or suffix request is left alone`() {
        val plain = ByteArrayInputStream(body(10))
        assertSame(plain, webViewSeekProof(plain, null))
        assertSame(plain, webViewSeekProof(plain, "bytes=-5"))
        assertSame(plain, webViewSeekProof(plain, "bytes=0-"))
    }
}
