package baby.freedom.mobile.browser

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [byteRangeFor] and [readAtMost]: a page-chosen Range header never throws (#355). */
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

    @Test
    fun `readAtMost stops one chunk past the limit`() {
        val body = ByteArray(200_000) { it.toByte() }
        assertArrayEquals(body, readAtMost(ByteArrayInputStream(body), 200_000))
        assertNull(readAtMost(ByteArrayInputStream(body), 199_999))
        assertArrayEquals(ByteArray(0), readAtMost(ByteArrayInputStream(ByteArray(0)), 0))
    }
}
