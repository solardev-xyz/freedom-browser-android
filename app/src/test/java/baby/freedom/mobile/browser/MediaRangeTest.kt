package baby.freedom.mobile.browser

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [byteRangeFor]: a page-chosen Range header never throws; [readBounded]:
 * a buffered body stays inside the shared byte budget (#355).
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
        var freeReserves = 0
        // Like MediaBodyBuffer.reserveFree: never evicts (counted, so a
        // test can tell which reserve the trim went through).
        fun reserveFree(n: Long): Boolean {
            freeReserves++
            return reserve(n)
        }
    }

    private fun read(body: ByteArray, limit: Int, expected: Long, budget: Budget) =
        readBounded(ByteArrayInputStream(body), limit, expected, budget::reserve, budget::release, budget::reserveFree)

    @Test
    fun `readBounded holds exactly the body it returns`() {
        val body = ByteArray(200_000) { it.toByte() }
        for (expected in listOf(200_000L, -1L)) {
            val budget = Budget(Long.MAX_VALUE)
            val got = (read(body, 200_000, expected, budget) as BoundedRead.Bytes).body
            assertArrayEquals(body, got.toByteArray())
            assertEquals(200_000L, got.size)
            // The last chunk is trimmed when there's room: nothing spare.
            assertEquals(200_000L, got.held)
            assertEquals(got.held, budget.used)
        }
        // A chunked body ending on a chunk boundary leaves no empty chunk.
        val even = Budget(Long.MAX_VALUE)
        val got = (read(ByteArray(128 * 1024), 200_000, -1, even) as BoundedRead.Bytes).body
        assertEquals(128L * 1024, got.held)
        assertEquals(got.held, even.used)
        val empty = Budget(Long.MAX_VALUE)
        assertArrayEquals(ByteArray(0), (read(ByteArray(0), 0, -1, empty) as BoundedRead.Bytes).body.toByteArray())
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
    }

    @Test
    fun `a chunked body read to its end is kept without room for a joined copy (R2-M1)`() {
        val body = ByteArray(200_000) { (it * 7).toByte() }
        // Room for the four chunks (262 144) but not for a 200 000-byte
        // copy beside them, nor for trimming the last chunk.
        val tight = Budget(4L * 64 * 1024 + 1_000)
        val got = (read(body, 200_000, -1, tight) as BoundedRead.Bytes).body
        assertArrayEquals(body, got.toByteArray())
        assertEquals(4L * 64 * 1024, got.held)
        assertEquals(got.held, tight.used)
        assertTrue(tight.peak <= tight.limit)
    }

    @Test
    fun `a chunked body ending on a chunk boundary is kept with no room for one more (R3-M1)`() {
        for (k in 0..4) {
            val body = ByteArray(k * 64 * 1024) { (it * 3).toByte() }
            val full = Budget(4L * 64 * 1024)
            val got = (read(body, 1_000_000, -1, full) as BoundedRead.Bytes).body
            assertArrayEquals(body, got.toByteArray())
            assertEquals(k * 64L * 1024, got.held)
            assertEquals(got.held, full.used)
        }
        // One byte more than fits is still refused, holding nothing...
        val over = Budget(4L * 64 * 1024)
        assertEquals(BoundedRead.NoRoom, read(ByteArray(4 * 64 * 1024 + 1), 1_000_000, -1, over))
        assertEquals(0L, over.used)
        // ...and past the limit it is TooLarge, not NoRoom.
        val atLimit = Budget(2L * 64 * 1024)
        assertEquals(BoundedRead.TooLarge, read(ByteArray(2 * 64 * 1024 + 1), 2 * 64 * 1024, -1, atLimit))
        assertEquals(0L, atLimit.used)
    }

    @Test
    fun `the last-chunk trim never evicts a buffered body (R3-M2)`() {
        // A buffered two-chunk body fills the budget but for one chunk.
        val buffer = MediaBodyBuffer<MediaBytes>(maxBytes = 3L * 64 * 1024, sizeOf = { it.held })
        val a = buffer.load("a", fresh = false) {
            (readBounded(ByteArrayInputStream(ByteArray(2 * 64 * 1024)), 1_000_000, -1, buffer::reserve, buffer::release, buffer::reserveFree) as BoundedRead.Bytes).body
        }!!
        assertEquals(2L * 64 * 1024, buffer.usedBytes)
        // A 100-byte chunked read takes the free chunk; trimming it would
        // need 100 more, which only evicting `a` could make room for.
        val small = buffer.load("b", fresh = false) {
            (readBounded(ByteArrayInputStream(ByteArray(100)), 1_000_000, -1, buffer::reserve, buffer::release, buffer::reserveFree) as BoundedRead.Bytes).body
        }!!
        assertEquals(64L * 1024, small.held) // kept untrimmed
        assertEquals(100L, small.size)
        assertTrue(a === buffer.load("a", fresh = false) { error("a was evicted") })
        assertEquals(3L * 64 * 1024, buffer.usedBytes)
        // With free room the trim still happens, through reserveFree.
        val roomy = Budget(Long.MAX_VALUE)
        assertEquals(100L, (read(ByteArray(100), 1_000_000, -1, roomy) as BoundedRead.Bytes).body.held)
        assertEquals(1, roomy.freeReserves)
    }

    @Test
    fun `a body that isn't the announced length`() {
        val budget = Budget(Long.MAX_VALUE)
        // Longer: never worth buffering, so remembered and streamed (R2-M1).
        assertEquals(BoundedRead.TooLarge, read(ByteArray(1_001), 10_000, 1_000, budget))
        assertEquals(0L, budget.used)
        // Shorter: what came, in the array already reserved, with no copy
        // (so no room needed for one).
        val tight = Budget(1_000)
        val got = (read(ByteArray(500) { 7 }, 10_000, 1_000, tight) as BoundedRead.Bytes).body
        assertArrayEquals(ByteArray(500) { 7 }, got.toByteArray())
        assertEquals(500L, got.size)
        assertEquals(1_000L, got.held)
        assertEquals(1_000L, tight.used)
    }

    @Test
    fun `slices of a chunked body cross chunk boundaries`() {
        val body = ByteArray(200_000) { (it % 251).toByte() }
        val got = (read(body, 200_000, -1, Budget(Long.MAX_VALUE)) as BoundedRead.Bytes).body
        for ((start, length) in listOf(0L to 0L, 0L to 1L, 65_535L to 2L, 60_000L to 140_000L, 199_999L to 1L, 131_072L to 65_536L)) {
            assertArrayEquals(
                "$start+$length",
                body.copyOfRange(start.toInt(), (start + length).toInt()),
                got.stream(start, length).readBytes(),
            )
        }
        // A shorter-than-announced body never streams its unused tail.
        val short = (read(ByteArray(500) { 7 }, 10_000, 1_000, Budget(Long.MAX_VALUE)) as BoundedRead.Bytes).body
        assertEquals(500, short.stream().readBytes().size)
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
                readBounded(failing, 200_000, expected, budget::reserve, budget::release, budget::reserveFree)
                fail("expected IOException")
            } catch (_: java.io.IOException) {
            }
            assertEquals(0L, budget.used)
        }
    }

    @Test
    fun `no room for the first chunk streams without waiting on a byte (R4-M3)`() {
        val untouched = object : java.io.InputStream() {
            override fun read(): Int = throw AssertionError("read the body")
            override fun read(b: ByteArray, off: Int, len: Int): Int = throw AssertionError("read the body")
        }
        val full = Budget(1_000)
        assertEquals(BoundedRead.NoRoom, readBounded(untouched, 1_000_000, -1, full::reserve, full::release, full::reserveFree))
        assertEquals(0L, full.used)
    }

    @Test
    fun `a failure to close after the read keeps the body and its reservation (R4-M4)`() {
        var closed = 0
        val stream = object : java.io.ByteArrayInputStream(ByteArray(1_000) { 5 }) {
            override fun close() {
                closed++
                throw java.io.IOException("close")
            }
        }
        val budget = Budget(Long.MAX_VALUE)
        val got = readThenClose(stream) {
            readBounded(it, 10_000, 1_000, budget::reserve, budget::release, budget::reserveFree)
        }
        assertEquals(1, closed)
        assertEquals(1_000L, (got as BoundedRead.Bytes).body.size)
        assertEquals(1_000L, budget.used)
        // A failing read is still thrown, and closes the stream.
        try {
            readThenClose(stream) { throw java.io.IOException("read") }
            fail("expected IOException")
        } catch (e: java.io.IOException) {
            assertEquals("read", e.message)
        }
        assertEquals(2, closed)
    }
}
