package baby.freedom.mobile.browser

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * A navigation's Swarm 404 body read ahead for its `path address not
 * found` tag ([fetchOnce], #536) is bounded as a whole, not only per read
 * (PR #557 R3-M2): a gateway that answers its headers and then trickles
 * the body can't hold the interceptor thread past the limit, and a page
 * giving up cuts it at once. Against the device's own connection:
 * `GatewayFetchPolicyDeviceTest.aTrickledNavigation404IsCutAtThePeekLimit`.
 */
class ReadAheadWithinTest {
    @Test
    fun `a short body is read whole and nothing is cut`() {
        val body = """{"message":"path address not found","code":404}""".toByteArray()
        val conn = FakeConnection()
        assertArrayEquals(body, readAheadWithin(body.inputStream(), conn, 1_024, 2_000))
        Thread.sleep(100)
        assertEquals(0, conn.disconnects.get())
    }

    @Test
    fun `a trickled body is cut at the limit, not after max reads`() {
        val conn = FakeConnection()
        val started = System.nanoTime()
        try {
            readAheadWithin(Trickle(conn.cut), conn, 1_024, 600)
            fail("read ahead past its limit")
        } catch (_: SocketTimeoutException) {
        }
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $ms ms", ms in 550..1_500)
        assertEquals(1, conn.disconnects.get())
    }

    @Test
    fun `the cut comes off the reading thread`() {
        val readers = mutableSetOf<Thread>()
        val conn = FakeConnection(onDisconnect = { synchronized(readers) { readers += Thread.currentThread() } })
        runCatching { readAheadWithin(Trickle(conn.cut), conn, 1_024, 300) }
        assertTrue(conn.cut.await(1, TimeUnit.SECONDS))
        synchronized(readers) { assertTrue(Thread.currentThread() !in readers) }
    }

    @Test
    fun `the page giving up cuts the read ahead at once`() {
        val conn = FakeConnection()
        val signal = TestSignal()
        Thread { Thread.sleep(300); signal.fire() }.apply { isDaemon = true; start() }
        val started = System.nanoTime()
        try {
            readAheadWithin(Trickle(conn.cut), conn, 1_024, 30_000, signal)
            fail("read ahead past the page giving up")
        } catch (_: IOException) {
        }
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $ms ms", ms < 1_500)
        assertEquals(1, conn.disconnects.get())
        assertEquals("the abandon hook was left registered", 0, signal.registered.get())
    }

    /** A byte every 100 ms, forever, until [cut]: then the blocked read fails, as a disconnected socket's. */
    private class Trickle(private val cut: CountDownLatch) : InputStream() {
        override fun read(): Int {
            if (cut.await(100, TimeUnit.MILLISECONDS)) throw IOException("socket closed")
            return 'x'.code
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            b[off] = read().toByte()
            return 1
        }
    }

    private class FakeConnection(private val onDisconnect: () -> Unit = {}) : HttpURLConnection(URL("http://127.0.0.1/")) {
        val cut = CountDownLatch(1)
        val disconnects = AtomicInteger(0)

        override fun disconnect() {
            disconnects.incrementAndGet()
            onDisconnect()
            cut.countDown()
        }

        override fun usingProxy() = false
        override fun connect() {}
    }

    private class TestSignal : AbandonSignal {
        private val actions = ArrayList<() -> Unit>()
        val registered = AtomicInteger(0)

        @Volatile
        override var abandoned = false

        override fun onAbandon(action: () -> Unit): () -> Unit {
            synchronized(actions) { actions.add(action) }
            registered.incrementAndGet()
            return { registered.decrementAndGet(); synchronized(actions) { actions.remove(action) } }
        }

        override fun sleep(ms: Long) = abandoned

        fun fire() {
            abandoned = true
            synchronized(actions) { actions.toList() }.forEach { it() }
        }
    }
}
