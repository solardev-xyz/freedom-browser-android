package baby.freedom.mobile.browser

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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

    private class Budget(val limit: Long) {
        var used = 0L
        var peak = 0L
        fun reserve(n: Long): Boolean {
            if (used + n > limit) return false
            used += n
            peak = maxOf(peak, used)
            return true
        }
        fun release(n: Long) { used -= n }
    }

    private fun read(body: ByteArray, limit: Int, expected: Long, budget: Budget) =
        readBounded(ByteArrayInputStream(body), limit, expected, budget::reserve, budget::release)

    @Test
    fun `readBounded holds exactly the body it returns`() {
        val body = ByteArray(200_000) { it.toByte() }
        for (expected in listOf(200_000L, -1L)) {
            val budget = Budget(Long.MAX_VALUE)
            val got = read(body, 200_000, expected, budget) as BoundedRead.Bytes
            assertArrayEquals(body, got.bytes)
            assertEquals(200_000L, budget.used)
        }
        val empty = Budget(Long.MAX_VALUE)
        assertArrayEquals(ByteArray(0), (read(ByteArray(0), 0, -1, empty) as BoundedRead.Bytes).bytes)
        assertEquals(0L, empty.used)
    }

    @Test
    fun `a known length is read into one array, with no growth or copy`() {
        val budget = Budget(Long.MAX_VALUE)
        read(ByteArray(1_000_000), 1_000_000, 1_000_000, budget)
        assertEquals(1_000_000L, budget.peak)
    }

    @Test
    fun `readBounded gives up past the limit or the budget, holding nothing`() {
        val body = ByteArray(200_000)
        val budget = Budget(Long.MAX_VALUE)
        assertEquals(BoundedRead.TooLarge, read(body, 199_999, -1, budget))
        assertEquals(BoundedRead.TooLarge, read(body, 199_999, 200_000, budget))
        assertEquals(0L, budget.used)
        // Never more than one chunk past the limit before giving up.
        assertTrue(budget.peak <= 199_999 + 64 * 1024)

        val small = Budget(150_000)
        assertEquals(BoundedRead.NoRoom, read(body, 200_000, 200_000, small))
        assertEquals(BoundedRead.NoRoom, read(body, 200_000, -1, small))
        assertEquals(0L, small.used)
        assertTrue(small.peak <= 150_000)
        // Unknown length: the chunks plus the joined copy must both fit.
        val tight = Budget(300_000)
        assertEquals(BoundedRead.NoRoom, read(body, 200_000, -1, tight))
        assertEquals(0L, tight.used)
    }

    @Test
    fun `a body that isn't the announced length`() {
        val budget = Budget(Long.MAX_VALUE)
        // Longer: not buffered this time.
        assertEquals(BoundedRead.NoRoom, read(ByteArray(1_001), 10_000, 1_000, budget))
        assertEquals(0L, budget.used)
        // Shorter: what came, holding just that.
        val got = read(ByteArray(500) { 7 }, 10_000, 1_000, budget) as BoundedRead.Bytes
        assertEquals(500, got.bytes.size)
        assertEquals(500L, budget.used)
    }

    @Test
    fun `a failed read gives its reservation back`() {
        val failing = object : java.io.InputStream() {
            var n = 0
            override fun read(): Int = throw java.io.IOException("reset")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (n++ > 2) throw java.io.IOException("reset")
                return len.coerceAtMost(1_000)
            }
        }
        for (expected in listOf(100_000L, -1L)) {
            failing.n = 0
            val budget = Budget(Long.MAX_VALUE)
            try {
                readBounded(failing, 200_000, expected, budget::reserve, budget::release)
                fail("expected IOException")
            } catch (_: java.io.IOException) {
            }
            assertEquals(0L, budget.used)
        }
    }
}
