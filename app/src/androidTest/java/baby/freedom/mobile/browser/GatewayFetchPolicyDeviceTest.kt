package baby.freedom.mobile.browser

import android.net.Uri
import android.webkit.WebResourceRequest
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [fetchWithRetry] against a scripted gateway on the device's own
 * HttpURLConnection: a subresource's body may pause well past the old
 * 10 s, its 404 is not fetched again, and a body dropped by the page
 * closes the gateway connection there and then.
 */
@RunWith(AndroidJUnit4::class)
class GatewayFetchPolicyDeviceTest {
    private lateinit var server: ServerSocket
    private val connections = AtomicInteger(0)
    private val sockets = mutableListOf<Socket>()

    /** Answers the n-th connection (0-based). */
    @Volatile
    private var script: (Int, OutputStream, InputStream) -> Unit = { _, _, _ -> }

    @Before
    fun setUp() {
        server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        Thread {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                synchronized(sockets) { sockets += s }
                val n = connections.getAndIncrement()
                Thread {
                    runCatching {
                        val input = s.getInputStream()
                        readRequestHead(input)
                        script(n, s.getOutputStream(), input)
                    }
                    runCatching { s.close() }
                }.apply { isDaemon = true; start() }
            }
        }.apply { isDaemon = true; start() }
    }

    @After
    fun tearDown() {
        runCatching { server.close() }
        synchronized(sockets) { sockets.forEach { runCatching { it.close() } } }
    }

    private val base get() = "http://127.0.0.1:${server.localPort}"

    @Test
    fun aSubresourceBodyThatPauses15sIsDeliveredWhole() {
        val body = ByteArray(4096) { (it % 251).toByte() }
        script = { _, out, _ ->
            out.write(head(200, "OK", body.size))
            out.write(body, 0, 1024)
            out.flush()
            Thread.sleep(15_000)
            out.write(body, 1024, 1024)
            out.flush()
            Thread.sleep(15_000)
            out.write(body, 2048, 2048)
            out.flush()
        }
        val started = System.nanoTime()
        val response = fetch(mainFrame = false)
        assertNotNull(response)
        assertEquals(200, response!!.statusCode)
        val got = response.data.use { it.readBytes() }
        assertArrayEquals(body, got)
        assertEquals("fetched once, not again after a pause", 1, connections.get())
        assertTrue("took ${elapsedMs(started)} ms", elapsedMs(started) >= 30_000)
    }

    @Test
    fun aSubresource404IsPassedThroughWithoutARetry() {
        script = { _, out, _ ->
            out.write(head(404, "Not Found", 9))
            out.write("not found".toByteArray())
            out.flush()
        }
        val started = System.nanoTime()
        val response = fetch(mainFrame = false)
        assertEquals(404, response!!.statusCode)
        assertEquals("not found", response.data.use { it.readBytes().decodeToString() })
        Thread.sleep(500) // a retry would have come by now (the first is immediate)
        assertEquals(1, connections.get())
        assertTrue("took ${elapsedMs(started)} ms", elapsedMs(started) < 3_000)
    }

    @Test
    fun aSubresource503IsRetried() {
        script = { n, out, _ ->
            if (n == 0) {
                out.write(head(503, "Service Unavailable", 0))
            } else {
                out.write(head(200, "OK", 2))
                out.write("ok".toByteArray())
            }
            out.flush()
        }
        val response = fetch(mainFrame = false)
        assertEquals(200, response!!.statusCode)
        assertEquals("ok", response.data.use { it.readBytes().decodeToString() })
        assertEquals(2, connections.get())
    }

    @Test
    fun aNavigation404IsStillRetried() {
        script = { n, out, _ ->
            if (n == 0) {
                out.write(head(404, "Not Found", 0))
            } else {
                out.write(head(200, "OK", 2))
                out.write("ok".toByteArray())
            }
            out.flush()
        }
        val response = fetch(mainFrame = true)
        assertEquals(200, response!!.statusCode)
        assertEquals(2, connections.get())
    }

    @Test
    fun aBodyClosedMidPauseDropsTheGatewayConnectionAtOnce() {
        val gone = AtomicLong(0)
        val closedAt = AtomicLong(0)
        val disconnected = CountDownLatch(1)
        script = { _, out, input ->
            out.write(head(200, "OK", 1_000_000))
            out.write(ByteArray(1024))
            out.flush()
            // A node out of credit: nothing more for a long while. Watch
            // for the client hanging up meanwhile.
            val r = runCatching { input.read() }.getOrDefault(-1)
            if (r == -1) {
                gone.set(System.nanoTime())
                disconnected.countDown()
            }
        }
        val response = fetch(mainFrame = false)!!
        val data = response.data
        assertEquals(1024, readFully(data, 1024))
        // WebView closes once its in-flight read is back; the page aborted.
        closedAt.set(System.nanoTime())
        data.close()
        assertTrue("the gateway never saw the client go", disconnected.await(5, TimeUnit.SECONDS))
        val ms = (gone.get() - closedAt.get()) / 1_000_000
        assertTrue("connection dropped $ms ms after close", ms < 1_000)
    }

    @Test
    fun aBodyClosedFromAnotherThreadUnblocksAStalledRead() {
        val disconnected = CountDownLatch(1)
        script = { _, out, input ->
            out.write(head(200, "OK", 1_000_000))
            out.write(ByteArray(16))
            out.flush()
            if (runCatching { input.read() }.getOrDefault(-1) == -1) disconnected.countDown()
        }
        val data = fetch(mainFrame = false)!!.data
        assertEquals(16, readFully(data, 16))
        val readReturned = CountDownLatch(1)
        Thread {
            runCatching { data.read(ByteArray(64)) }
            readReturned.countDown()
        }.apply { isDaemon = true; start() }
        Thread.sleep(1_000) // the read is now blocked on the silent node
        val closedAt = System.nanoTime()
        data.close()
        assertTrue("the blocked read never returned", readReturned.await(3, TimeUnit.SECONDS))
        assertTrue("the gateway never saw the client go", disconnected.await(3, TimeUnit.SECONDS))
        assertTrue("took ${elapsedMs(closedAt)} ms", elapsedMs(closedAt) < 3_000)
    }

    @Test
    fun headersThatNeverComeFailAtTheHeaderDeadline() {
        val accepted = CountDownLatch(1)
        script = { _, _, _ ->
            accepted.countDown()
            Thread.sleep(30_000) // never answer
        }
        val conn = URL("$base/slow").openConnection() as HttpURLConnection
        conn.readTimeout = SUBRESOURCE_BODY_STALL_MS
        val deadline = HeaderDeadline(2_000)
        deadline.track(conn)
        val started = System.nanoTime()
        try {
            conn.responseCode
            fail("headers arrived from a silent server")
        } catch (e: java.io.IOException) {
            // expected: disconnected by the deadline
        }
        val ms = elapsedMs(started)
        assertTrue("the server never accepted", accepted.await(0, TimeUnit.SECONDS))
        assertTrue("the deadline didn't fire", deadline.expired)
        assertTrue("took $ms ms", ms in 1_500..5_000)
    }

    @Test
    fun headersInTimeDisarmTheDeadline() {
        script = { _, out, _ ->
            out.write(head(200, "OK", 2))
            out.flush()
            Thread.sleep(3_000) // a body pause past the header deadline
            out.write("ok".toByteArray())
            out.flush()
        }
        val conn = URL("$base/x").openConnection() as HttpURLConnection
        conn.readTimeout = SUBRESOURCE_BODY_STALL_MS
        val deadline = HeaderDeadline(1_000)
        deadline.track(conn)
        assertEquals(200, conn.responseCode)
        deadline.headersReceived()
        assertEquals("ok", conn.inputStream.use { it.readBytes().decodeToString() })
        assertTrue(!deadline.expired)
    }

    private fun fetch(mainFrame: Boolean) = fetchWithRetry(
        FakeRequest("$base/segment", mainFrame), "$base/segment", "$base/segment",
        fresh = false, policy = gatewayFetchPolicy(mainFrame, "GET", media = false),
    )

    private fun readFully(input: InputStream, n: Int): Int {
        val buf = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = input.read(buf, got, n - got)
            if (r < 0) break
            got += r
        }
        return got
    }

    private fun elapsedMs(since: Long) = (System.nanoTime() - since) / 1_000_000

    private fun head(status: Int, reason: String, length: Int) =
        ("HTTP/1.1 $status $reason\r\nContent-Type: application/octet-stream\r\n" +
            "Content-Length: $length\r\n\r\n").toByteArray()

    private fun readRequestHead(input: InputStream) {
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b < 0) return
            last4 = (last4 shl 8) or b
            if (last4 == 0x0d0a0d0a) return
        }
    }

    private class FakeRequest(private val url: String, private val mainFrame: Boolean) : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame() = mainFrame
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = "GET"
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }
}
