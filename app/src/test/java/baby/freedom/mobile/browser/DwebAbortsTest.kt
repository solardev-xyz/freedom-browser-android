package baby.freedom.mobile.browser

import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DwebAborts]: the page's word that it gave up on a dweb request stops
 * the interceptor's fetch for it — before WebView even asks for it, while
 * it waits on the gateway, or between its retries — and only that fetch.
 */
class DwebAbortsTest {
    private val seg = "https://abc.bzz.freedom.baby/seg/1"

    @Test
    fun `a request the page gave up on before its call is answered at once`() {
        val aborts = DwebAborts()
        aborts.pageDone(seg)
        assertTrue(aborts.begin(seg).abandoned)
        // Only the one: the next request for the same URL is fetched.
        assertFalse(aborts.begin(seg).abandoned)
    }

    @Test
    fun `a call in flight is told when the page gives up on it`() {
        val aborts = DwebAborts()
        val ticket = aborts.begin(seg)
        val cut = CountDownLatch(1)
        ticket.onAbandon { cut.countDown() }
        assertFalse(ticket.abandoned)
        aborts.pageDone(seg)
        assertTrue(ticket.abandoned)
        assertEquals(0L, cut.count)
        assertEquals(0, aborts.working(seg))
    }

    @Test
    fun `a call that is abandoned already runs its cut at once`() {
        val aborts = DwebAborts()
        aborts.pageDone(seg)
        val ticket = aborts.begin(seg)
        var ran = false
        ticket.onAbandon { ran = true }
        assertTrue(ran)
    }

    @Test
    fun `an answered request's entry is matched to its answer, not to a later call`() {
        val aborts = DwebAborts()
        val first = aborts.begin(seg)
        aborts.finished(first) // answered; its entry comes once the page has read it
        val second = aborts.begin(seg)
        aborts.pageDone(seg) // the first request's entry
        assertFalse(second.abandoned)
        aborts.pageDone(seg) // now the second's: the page gave up on it
        assertTrue(second.abandoned)
    }

    @Test
    fun `an entry for an answered request leaves nothing behind for the next call`() {
        val aborts = DwebAborts()
        aborts.finished(aborts.begin(seg))
        aborts.pageDone(seg)
        assertFalse(aborts.begin(seg).abandoned)
    }

    @Test
    fun `of two calls in flight for one URL the first is taken as the one given up`() {
        val aborts = DwebAborts()
        val first = aborts.begin(seg)
        val second = aborts.begin(seg)
        aborts.pageDone(seg)
        assertTrue(first.abandoned)
        assertFalse(second.abandoned)
        aborts.finished(first)
        aborts.finished(second)
        aborts.pageDone(seg) // the second, read by the page
        assertFalse(aborts.begin(seg).abandoned)
    }

    @Test
    fun `only the URL the page gave up on is stopped`() {
        val aborts = DwebAborts()
        val other = aborts.begin("https://abc.bzz.freedom.baby/seg/2")
        aborts.pageDone(seg)
        assertFalse(other.abandoned)
        assertTrue(aborts.begin(seg).abandoned)
    }

    @Test
    fun `an entry whose call never comes is forgotten`() {
        var now = 0L
        val aborts = DwebAborts(now = { now }, ttlMs = 1_000)
        aborts.pageDone(seg)
        now = 1_000
        assertFalse(aborts.begin(seg).abandoned)
    }

    @Test
    fun `an entry from a clock that went back is forgotten too`() {
        var now = 5_000L
        val aborts = DwebAborts(now = { now }, ttlMs = 1_000)
        aborts.pageDone(seg)
        now = 4_000
        assertFalse(aborts.begin(seg).abandoned)
    }

    @Test
    fun `a request and its entry match without the fragment`() {
        val aborts = DwebAborts()
        aborts.pageDone("$seg#t=10")
        assertTrue(aborts.begin(seg).abandoned)
    }

    @Test
    fun `sleep ends early when the page gives up`() {
        val aborts = DwebAborts()
        val ticket = aborts.begin(seg)
        Thread { Thread.sleep(100); aborts.pageDone(seg) }.start()
        val started = System.nanoTime()
        assertTrue(ticket.sleep(5_000))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000)
        assertFalse(aborts.begin(seg).sleep(50))
    }

    @Test
    fun `only subresources on the virtual origins are tracked`() {
        assertTrue(DwebAborts.tracks(seg, mainFrame = false))
        assertTrue(DwebAborts.tracks("https://abc.ipfs.freedom.baby/a.js", mainFrame = false))
        assertFalse(DwebAborts.tracks(seg, mainFrame = true))
        assertFalse(DwebAborts.tracks("https://example.com/seg/1", mainFrame = false))
        assertFalse(DwebAborts.tracks("http://abc.bzz.freedom.baby/seg/1", mainFrame = false))
        assertFalse(DwebAborts.tracks("bzz://abc/seg/1", mainFrame = false))
    }

    @Test
    fun `a report keeps only dweb subresource URLs`() {
        val report = listOf(seg, "https://example.com/x", "", "x".repeat(10), "https://abc.ens.freedom.baby/a").joinToString("\n")
        assertEquals(listOf(seg, "https://abc.ens.freedom.baby/a"), parseDoneReport(report))
    }

    @Test
    fun `the page script is built for the channel and the dweb suffixes`() {
        val js = dwebAbortsJs("abcdefghij")
        assertTrue(js.contains("'abcdefghij'"))
        for (suffix in VirtualOrigin.SUFFIXES) assertTrue(suffix, js.contains("'.$suffix/'"))
        assertTrue(runCatching { dwebAbortsJs("Bad-Name") }.isFailure)
    }

    @Test
    fun `abandoning a header wait cuts its connection and refuses the next hop`() {
        val cuts = AtomicInteger(0)
        val deadline = HeaderDeadline(5_000, patience = PatientWaits(0))
        deadline.connected(FakeConnection { cuts.incrementAndGet() })
        deadline.abandon()
        val deadlineMs = System.nanoTime() / 1_000_000 + 2_000
        while (cuts.get() == 0 && System.nanoTime() / 1_000_000 < deadlineMs) Thread.sleep(10)
        assertEquals(1, cuts.get())
        assertTrue(deadline.expired)
        assertTrue(runCatching { deadline.connected(FakeConnection {}) }.isFailure)
    }

    @Test
    fun `a fetch waiting on the gateway's headers stops when the page gives up`() {
        ServerSocket(0, 50, LOOPBACK).use { server ->
            val accepted = Collections.synchronizedList(mutableListOf<Socket>())
            val hungUp = CountDownLatch(1)
            Thread {
                runCatching {
                    val s = server.accept()
                    accepted += s
                    // Never answers; waits for the client to go.
                    if (s.getInputStream().let { i -> while (i.read() >= 0) Unit; true }) hungUp.countDown()
                }.onFailure { hungUp.countDown() }
            }.apply { isDaemon = true; start() }
            val url = "http://127.0.0.1:${server.localPort}/bzz/ab/seg/1"
            val aborts = DwebAborts()
            val ticket = aborts.begin(seg)
            Thread { Thread.sleep(300); aborts.pageDone(seg) }.start()
            val started = System.nanoTime()
            fetchWithRetry(request(), url, seg, false, gatewayFetchPolicy(mainFrame = false, media = false), abandon = ticket)
            val ms = (System.nanoTime() - started) / 1_000_000
            assertTrue("the gateway was never asked", accepted.isNotEmpty())
            assertTrue("took $ms ms: the 10 s header wait ran on", ms < 3_000)
            assertTrue("returned after $ms ms, before the page gave up", ms >= 250)
            assertTrue("the gateway connection stayed open", hungUp.await(3, TimeUnit.SECONDS))
            accepted.forEach { runCatching { it.close() } }
        }
    }

    @Test
    fun `a fetch retrying a transient answer stops when the page gives up`() {
        ServerSocket(0, 50, LOOPBACK).use { server ->
            val asked = AtomicInteger(0)
            Thread {
                while (!server.isClosed) {
                    runCatching {
                        server.accept().use { s ->
                            asked.incrementAndGet()
                            s.getOutputStream().write("HTTP/1.1 503 Busy\r\nContent-Type: text/plain\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        }
                    }
                }
            }.apply { isDaemon = true; start() }
            val url = "http://127.0.0.1:${server.localPort}/bzz/ab/seg/1"
            val aborts = DwebAborts()
            val ticket = aborts.begin(seg)
            // Past the first answers, inside the back-off that follows them.
            Thread { Thread.sleep(1_000); aborts.pageDone(seg) }.start()
            val started = System.nanoTime()
            fetchWithRetry(request(), url, seg, false, gatewayFetchPolicy(mainFrame = false, media = false), abandon = ticket)
            val ms = (System.nanoTime() - started) / 1_000_000
            assertTrue("took $ms ms: the ~17 s back-off ran on", ms < 3_000)
            assertTrue("returned after $ms ms, before the page gave up", ms >= 900)
            val after = asked.get()
            // 0, 250 and 500 ms back-off: three or four answers by 1 s.
            assertTrue("the gateway was asked $after times", after in 3..4)
            Thread.sleep(600)
            assertEquals("asked again after the page gave up", after, asked.get())
        }
    }

    /** Task 3: a body WebView closes early cuts the gateway, through every wrapper the media path puts round it. */
    @Test
    fun `closing a sliced, seek-proofed media body drops the gateway connection`() {
        for (range in listOf<String?>(null, "bytes=100-", "bytes=-10")) {
            val disconnected = CountDownLatch(1)
            val body = DisconnectOnCloseInputStream(
                ByteArrayInputStream(ByteArray(1000)),
                FakeConnection { disconnected.countDown() },
                length = 1000,
            )
            val stream = webViewSeekProof(SlicedInputStream(body, 100, 500), range)
            stream.read()
            stream.close()
            assertTrue("range $range: not disconnected", disconnected.await(2, TimeUnit.SECONDS))
        }
    }

    private fun request() = object : android.webkit.WebResourceRequest {
        override fun getUrl(): android.net.Uri? = null
        override fun isForMainFrame() = false
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = "GET"
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }

    private class FakeConnection(private val onDisconnect: () -> Unit) :
        HttpURLConnection(URL("http://127.0.0.1/")) {
        override fun disconnect() = onDisconnect()
        override fun usingProxy() = false
        override fun connect() {}
    }

    private companion object {
        val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")
    }
}
