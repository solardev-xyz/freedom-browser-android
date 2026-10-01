package baby.freedom.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * [MediaBodyBuffer]'s byte budget with [readBounded] behind it (#355):
 * however many media bodies a page loads at once, buffered and in flight
 * together stay inside the budget, and a too-large marker never pushes a
 * real body out.
 */
class MediaBufferBudgetTest {
    private val url = "http://127.0.0.1:1633/bzz/abc/clip"
    private val noRoom = "no room"
    private val tooLarge = "too large"

    private fun buffer(maxBytes: Long, maxEntries: Int = 64) = MediaBodyBuffer<Any>(
        maxEntries = maxEntries,
        maxBytes = maxBytes,
        sizeOf = { (it as? ByteArray)?.size?.toLong() ?: (it as? MediaBytes)?.held ?: 0L },
        keep = { it !== noRoom },
    )

    private fun body(n: Int) = ByteArray(n)

    @Test
    fun `parallel loads together stay inside the budget`() {
        val budget = 1_000_000L
        val size = 480_000
        val buffer = buffer(budget)
        val peak = AtomicLong()
        val decided = AtomicInteger()
        val go = CountDownLatch(1)
        val pool = Executors.newCachedThreadPool()
        // Eight distinct files, each well under the per-body limit, every
        // read held open until all eight have reserved or been refused.
        val jobs = (0 until 8).map { i ->
            pool.submit<Any?> {
                buffer.load("$url$i.mp4", fresh = false) {
                    val stream = object : InputStream() {
                        var left = size
                        override fun read(): Int = -1
                        override fun read(b: ByteArray, off: Int, len: Int): Int {
                            go.await(5, TimeUnit.SECONDS)
                            if (left == 0) return -1
                            val n = minOf(len, left)
                            left -= n
                            return n
                        }
                    }
                    val reserve = { n: Long ->
                        buffer.reserve(n).also {
                            peak.accumulateAndGet(buffer.usedBytes) { a, b -> maxOf(a, b) }
                            decided.incrementAndGet()
                        }
                    }
                    when (val r = readBounded(stream, 32 * 1024 * 1024, size.toLong(), reserve, buffer::release, buffer::reserveFree)) {
                        is BoundedRead.Bytes -> r.body
                        BoundedRead.NoRoom -> noRoom
                        BoundedRead.TooLarge -> tooLarge
                    }
                }
            }
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (decided.get() < 8 && System.nanoTime() < deadline) Thread.sleep(5)
        go.countDown()
        val results = jobs.map { it.get(5, TimeUnit.SECONDS) }
        pool.shutdown()
        assertEquals(2, results.count { it is MediaBytes })
        assertEquals(6, results.count { it === noRoom })
        assertTrue("peak ${peak.get()}", peak.get() <= budget)
        assertEquals(2L * size, buffer.usedBytes)
    }

    @Test
    fun `a too-large marker doesn't push a buffered body out`() {
        val buffer = buffer(Long.MAX_VALUE)
        val bodies = (0 until 4).map { i -> buffer.load("$url$i", fresh = false) { assertTrue(buffer.reserve(10)); body(10) } }
        repeat(20) { i -> buffer.load("${url}big$i", fresh = false) { tooLarge } }
        bodies.forEachIndexed { i, b ->
            assertSame(b, buffer.load("$url$i", fresh = false) { error("refetched $i") })
        }
        assertEquals(40L, buffer.usedBytes)
    }

    @Test
    fun `room for a new read evicts the least recently used bodies`() {
        val buffer = buffer(100)
        assertTrue(buffer.reserve(40))
        val a = buffer.load("${url}a", fresh = false) { body(40) }
        assertTrue(buffer.reserve(40))
        buffer.load("${url}b", fresh = false) { body(40) }
        buffer.load("${url}a", fresh = false) { error("a is buffered") } // a is now newest
        assertEquals(80L, buffer.usedBytes)
        // 50 more: b (least recently used) goes, a stays.
        assertTrue(buffer.reserve(50))
        assertEquals(90L, buffer.usedBytes)
        buffer.release(50)
        assertSame(a, buffer.load("${url}a", fresh = false) { error("a was evicted") })
        assertTrue(buffer.load("${url}b", fresh = false) { noRoom } === noRoom)
        // 70 more: a goes too.
        assertTrue(buffer.reserve(70))
        assertEquals(70L, buffer.usedBytes)
        // Nothing left to evict: refused, nothing taken.
        assertTrue(!buffer.reserve(40))
        assertEquals(70L, buffer.usedBytes)
    }

    @Test
    fun `a body that isn't kept, or is replaced, gives its bytes back`() {
        val buffer = buffer(1_000)
        assertTrue(buffer.reserve(100))
        buffer.load(url, fresh = false) { body(100) }
        // A Hard reload drops the old body and buffers the new one.
        assertTrue(buffer.reserve(60))
        buffer.load(url, fresh = true) { body(60) }
        assertEquals(60L, buffer.usedBytes)
        // Entries over maxEntries give their bytes back as they go.
        val small = buffer(1_000, maxEntries = 1)
        assertTrue(small.reserve(10))
        small.load("${url}x", fresh = false) { body(10) }
        assertTrue(small.reserve(20))
        small.load("${url}y", fresh = false) { body(20) }
        assertEquals(20L, small.usedBytes)
    }
}
