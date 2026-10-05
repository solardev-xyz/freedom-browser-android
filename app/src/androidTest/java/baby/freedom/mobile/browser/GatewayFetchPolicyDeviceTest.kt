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
        conn.connect()
        deadline.connected(conn)
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
        conn.connect()
        deadline.connected(conn)
        assertEquals(200, conn.responseCode)
        deadline.headersReceived()
        assertEquals("ok", conn.inputStream.use { it.readBytes().decodeToString() })
        assertTrue(!deadline.expired)
    }

    @Test
    fun headersPastTheBaseWaitOnlyOnAFreeSlot() {
        script = { _, _, _ -> Thread.sleep(30_000) } // never answer
        // No slot free: cut at the base limit, as main always did.
        val none = PatientWaits(0)
        val conn = URL("$base/a").openConnection() as HttpURLConnection
        conn.readTimeout = SUBRESOURCE_BODY_STALL_MS
        val quick = HeaderDeadline(1_000, 4_000, none)
        conn.connect()
        quick.connected(conn)
        var started = System.nanoTime()
        runCatching { conn.responseCode }
        assertTrue("took ${elapsedMs(started)} ms", elapsedMs(started) in 800..2_500)
        assertTrue(quick.expired)

        // A free slot: waits on to the long limit, then gives the slot back.
        val one = PatientWaits(1)
        val conn2 = URL("$base/b").openConnection() as HttpURLConnection
        conn2.readTimeout = SUBRESOURCE_BODY_STALL_MS
        val patient = HeaderDeadline(1_000, 3_000, one)
        conn2.connect()
        patient.connected(conn2)
        started = System.nanoTime()
        runCatching { conn2.responseCode }
        assertTrue("took ${elapsedMs(started)} ms", elapsedMs(started) in 2_700..4_500)
        assertTrue(patient.expired)
        assertTrue("the slot wasn't given back", one.tryTake())
    }

    @Test
    fun headersInTimeGiveTheSlotBack() {
        script = { _, out, _ ->
            Thread.sleep(2_000)
            out.write(head(200, "OK", 2))
            out.write("ok".toByteArray())
            out.flush()
        }
        val one = PatientWaits(1)
        val conn = URL("$base/x").openConnection() as HttpURLConnection
        conn.readTimeout = SUBRESOURCE_BODY_STALL_MS
        val deadline = HeaderDeadline(1_000, 10_000, one)
        conn.connect()
        deadline.connected(conn)
        assertEquals(200, conn.responseCode)
        assertTrue(deadline.extended)
        deadline.headersReceived()
        assertTrue(!deadline.expired)
        assertTrue("the slot wasn't given back", one.tryTake())
    }

    /**
     * PR #409 R1-F1: every stalled read blocks a thread of Chromium's
     * shared pool, so only a slot holder may stall past the base limit;
     * the others fail there, as on main.
     */
    @Test
    fun ofTwoStalledBodiesOnlyTheSlotHolderWaitsPastTheBase() {
        script = { _, out, _ ->
            out.write(head(200, "OK", 32))
            out.write(ByteArray(16))
            out.flush()
            Thread.sleep(3_000) // longer than the 1 s base, well within the long limit
            out.write(ByteArray(16))
            out.flush()
        }
        val one = PatientWaits(1)
        val results = arrayOfNulls<Any>(2)
        val guards = arrayOfNulls<BodyStallGuard>(2)
        val threads = (0..1).map { i ->
            val conn = URL("$base/s$i").openConnection() as HttpURLConnection
            conn.readTimeout = SUBRESOURCE_BODY_STALL_MS
            assertEquals(200, conn.responseCode)
            val guard = BodyStallGuard(conn, 1_000, one)
            guards[i] = guard
            val body = DisconnectOnCloseInputStream(conn.inputStream, conn, guard)
            Thread {
                val started = System.nanoTime()
                results[i] = runCatching { body.use { it.readBytes().size } }
                    .fold({ it to elapsedMs(started) }, { it to elapsedMs(started) })
            }.apply { start() }
        }
        threads.forEach { it.join(10_000) }
        val outcomes = results.map { it as Pair<*, *> }
        val whole = outcomes.filter { it.first == 32 }
        val failed = outcomes.filter { it.first is Throwable }
        assertEquals("$outcomes", 1, whole.size)
        assertEquals("$outcomes", 1, failed.size)
        assertTrue("$outcomes", (failed[0].second as Long) in 800..2_500)
        assertEquals(1, guards.count { it!!.cutOff })
        assertEquals(1, guards.sumOf { it!!.extensions })
        assertTrue("the slot wasn't given back", one.tryTake())
    }

    /**
     * PR #409 R2-F1: a lookup before the connection exists (pinning an
     * external gateway's hop) is outside the clock, and the connection
     * that comes up after it is still cut at the base limit; before, a
     * deadline that ran out during the lookup "disconnected" a connection
     * not yet connected, a no-op, and it waited out its read timeout.
     */
    @Test
    fun aHopConnectedAfterALongLookupIsStillCutAtTheBase() {
        script = { _, _, _ -> Thread.sleep(30_000) } // never answer
        val deadline = HeaderDeadline(1_000, 4_000, PatientWaits(0))
        Thread.sleep(1_500) // a name lookup longer than the base
        val conn = URL("$base/late").openConnection() as HttpURLConnection
        conn.readTimeout = 8_000
        val started = System.nanoTime()
        conn.connect()
        deadline.connected(conn)
        try {
            conn.responseCode
            fail("headers arrived from a silent server")
        } catch (e: java.io.IOException) {
            // expected: cut by the deadline, not the 8 s read timeout
        }
        val ms = elapsedMs(started)
        assertTrue("the deadline didn't fire", deadline.expired)
        assertTrue("took $ms ms", ms in 800..2_500)
        // Once expired, a later hop isn't started at all.
        val next = URL("$base/next").openConnection() as HttpURLConnection
        next.connect()
        try {
            deadline.connected(next)
            fail("a hop started on an expired attempt")
        } catch (e: java.net.SocketTimeoutException) {
            // expected
        } finally {
            next.disconnect()
        }
    }

    /**
     * PR #409 R2-M1: each redirect hop gets the base limit to its own
     * headers, as main's read timeout gave it; two hops of 0.7 s each on
     * a 1 s base, with no slot free, arrive.
     */
    @Test
    fun eachRedirectHopGetsTheBaseLimitOfItsOwn() {
        script = { n, out, _ ->
            Thread.sleep(700)
            if (n == 0) {
                out.write("HTTP/1.1 302 Found\r\nLocation: /second\r\nContent-Length: 0\r\n\r\n".toByteArray())
            } else {
                out.write(head(200, "OK", 2))
                out.write("ok".toByteArray())
            }
            out.flush()
        }
        val deadline = HeaderDeadline(1_000, 4_000, PatientWaits(0))
        val conn = TorRouting.openFollowingRedirects(URL("$base/first"), hops = deadline) {
            readTimeout = SUBRESOURCE_BODY_STALL_MS
        }
        deadline.headersReceived()
        assertEquals(200, conn.responseCode)
        assertEquals("ok", conn.inputStream.use { it.readBytes().decodeToString() })
        assertTrue(!deadline.expired)
        assertEquals(2, connections.get())
    }

    /**
     * PR #409 R2-M3: a body closed after exactly its Content-Length, or a
     * HEAD answer closed unread, keeps its connection for the next request.
     */
    @Test
    fun aBodyClosedAtItsLengthOrAHeadKeepsTheConnection() {
        script = { _, out, input ->
            out.write(head(200, "OK", 2))
            out.write("ok".toByteArray())
            out.flush()
            readRequestHead(input) // the HEAD
            out.write(head(200, "OK", 2))
            out.flush()
            readRequestHead(input) // the third request
            out.write(head(200, "OK", 2))
            out.write("ok".toByteArray())
            out.flush()
            Thread.sleep(1_000)
        }
        val first = URL("$base/a").openConnection() as HttpURLConnection
        assertEquals(200, first.responseCode)
        val body = DisconnectOnCloseInputStream(first.inputStream, first, length = first.contentLengthLong)
        assertEquals(2, body.read(ByteArray(2), 0, 2)) // exactly the length; no read to -1
        body.close()

        val head = URL("$base/b").openConnection() as HttpURLConnection
        head.requestMethod = "HEAD"
        assertEquals(200, head.responseCode)
        DisconnectOnCloseInputStream(head.inputStream, head, length = head.contentLengthLong, noBody = true).close()

        val third = URL("$base/c").openConnection() as HttpURLConnection
        assertEquals("ok", third.inputStream.use { it.readBytes().decodeToString() })
        assertEquals("all three over one connection", 1, connections.get())
    }

    /**
     * PR #409 R3-F1: an https hop whose TLS handshake never completes
     * (the server accepts and says nothing) is cut at the connect limit,
     * not left to the long read timeout the body stall needs.
     */
    @Test
    fun aStalledTlsHandshakeIsCutAtTheConnectLimit() {
        script = { _, _, _ -> Thread.sleep(30_000) } // never answer the ClientHello
        val deadline = HeaderDeadline(1_000, 4_000, PatientWaits(1), connectMs = 1_500)
        val started = System.nanoTime()
        try {
            TorRouting.openFollowingRedirects(URL("https://127.0.0.1:${server.localPort}/tls"), hops = deadline) {
                connectTimeout = GATEWAY_CONNECT_TIMEOUT_MS
                readTimeout = 8_000
            }
            fail("a silent TLS server completed a handshake")
        } catch (e: java.io.IOException) {
            // expected: cut by the deadline, not the 8 s read timeout
        } finally {
            deadline.headersReceived()
        }
        val ms = elapsedMs(started)
        assertTrue("the deadline didn't fire", deadline.expired)
        assertTrue("took $ms ms", ms in 1_300..3_000)
        assertTrue("the server never saw the connection", connections.get() >= 1)
    }

    private fun fetch(mainFrame: Boolean) = fetchWithRetry(
        FakeRequest("$base/segment", mainFrame), "$base/segment", "$base/segment",
        fresh = false, policy = gatewayFetchPolicy(mainFrame, media = false),
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
