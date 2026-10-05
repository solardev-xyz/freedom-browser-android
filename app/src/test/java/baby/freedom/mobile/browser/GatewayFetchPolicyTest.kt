package baby.freedom.mobile.browser

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayFetchPolicyTest {
    private fun subresource(media: Boolean = false) =
        gatewayFetchPolicy(mainFrame = false, media = media)

    private fun navigation(media: Boolean = false) =
        gatewayFetchPolicy(mainFrame = true, media = media)

    @Test
    fun `a subresource waits 30 s for its headers`() {
        assertEquals(30_000, subresource().headerTimeoutMs)
    }

    @Test
    fun `a subresource body may pause far past the old 10 s`() {
        assertTrue(subresource().bodyStallTimeoutMs >= 60_000)
        assertTrue(subresource(media = true).bodyStallTimeoutMs >= 60_000)
    }

    @Test
    fun `a subresource 404 is passed through, not retried`() {
        assertFalse(404 in subresource().retryStatuses)
        assertFalse(404 in subresource(media = true).retryStatuses)
    }

    /**
     * PR #409 R3-M2: whatever the page's method, the gateway is sent a GET
     * or HEAD, so a subresource's transient 5xx is always retried.
     */
    @Test
    fun `a subresource retries the transient 5xx`() {
        assertEquals(setOf(500, 502, 503, 504), subresource().retryStatuses)
        assertEquals(setOf(500, 502, 503, 504), subresource(media = true).retryStatuses)
    }

    @Test
    fun `other statuses are never retried for a subresource`() {
        for (status in listOf(200, 206, 301, 304, 400, 401, 403, 405, 410, 416, 429, 501, 505)) {
            assertFalse("$status", status in subresource().retryStatuses)
        }
    }

    @Test
    fun `a media subresource keeps its 60 s header wait`() {
        assertEquals(60_000, subresource(media = true).headerTimeoutMs)
    }

    @Test
    fun `a navigation keeps its 10 s timeouts and 404 retries`() {
        val p = navigation()
        assertEquals(10_000, p.headerTimeoutMs)
        assertEquals(10_000, p.bodyStallTimeoutMs)
        assertEquals(setOf(404, 500, 502, 503, 504), p.retryStatuses)
    }

    @Test
    fun `a media navigation keeps its 60 s timeouts and 404 retries`() {
        val p = navigation(media = true)
        assertEquals(60_000, p.headerTimeoutMs)
        assertEquals(60_000, p.bodyStallTimeoutMs)
        assertEquals(setOf(404, 500, 502, 503, 504), p.retryStatuses)
    }

    @Test
    fun `past main's own limits a subresource waits only on a patient slot`() {
        // What main always gave; beyond it, PatientWaits decides.
        assertEquals(10_000, subresource().baseHeaderTimeoutMs)
        assertEquals(10_000, subresource().baseBodyStallMs)
        assertEquals(60_000, subresource(media = true).baseHeaderTimeoutMs)
        assertEquals(60_000, subresource(media = true).baseBodyStallMs)
        assertTrue(subresource().baseHeaderTimeoutMs < subresource().headerTimeoutMs)
        assertTrue(subresource().baseBodyStallMs < subresource().bodyStallTimeoutMs)
    }

    @Test
    fun `a navigation never waits on a patient slot`() {
        for (p in listOf(navigation(), navigation(media = true))) {
            assertEquals(p.headerTimeoutMs, p.baseHeaderTimeoutMs)
            assertEquals(p.bodyStallTimeoutMs, p.baseBodyStallMs)
        }
    }

    @Test
    fun `patient slots are taken and given back`() {
        val slots = PatientWaits(2)
        assertTrue(slots.tryTake())
        assertTrue(slots.tryTake())
        assertFalse(slots.tryTake())
        slots.give()
        assertTrue(slots.tryTake())
    }

    @Test
    fun `the shared pool leaves most of Chromium's workers free`() {
        val slots = PatientWaits.shared
        var n = 0
        while (slots.tryTake()) n++
        repeat(n) { slots.give() }
        val workers = maxOf(3, Runtime.getRuntime().availableProcessors() - 1)
        assertTrue("$n of $workers", n in 1..maxOf(1, workers / 3))
    }

    @Test
    fun `only a subresource gets a header deadline separate from the body`() {
        // fetchOnce arms its outside header deadline only when the two differ.
        assertTrue(subresource().headerTimeoutMs < subresource().bodyStallTimeoutMs)
        assertTrue(subresource(media = true).headerTimeoutMs < subresource(media = true).bodyStallTimeoutMs)
        assertEquals(navigation().headerTimeoutMs, navigation().bodyStallTimeoutMs)
    }

    /**
     * PR #409 R2-M2: the watchdog hands each cut to a thread of its own,
     * so one disconnect that blocks doesn't hold up another deadline.
     */
    @Test
    fun `a blocking disconnect doesn't hold up other deadlines`() {
        val release = CountDownLatch(1)
        val stuckEntered = CountDownLatch(1)
        val stuck = FakeConnection { stuckEntered.countDown(); release.await(10, TimeUnit.SECONDS) }
        val otherCut = CountDownLatch(1)
        val other = FakeConnection { otherCut.countDown() }
        try {
            HeaderDeadline(100, patience = PatientWaits(0)).connected(stuck)
            assertTrue("the first cut never started", stuckEntered.await(2, TimeUnit.SECONDS))
            val second = HeaderDeadline(200, patience = PatientWaits(0))
            second.connected(other)
            assertTrue("the second cut waited on the first", otherCut.await(2, TimeUnit.SECONDS))
            assertTrue(second.expired)
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `headers in time stop a hop's clock, and the next hop starts its own`() {
        val cuts = AtomicInteger(0)
        val deadline = HeaderDeadline(300, patience = PatientWaits(0))
        deadline.connected(FakeConnection { cuts.incrementAndGet() })
        Thread.sleep(200)
        deadline.answered(FakeConnection {})
        deadline.connected(FakeConnection { cuts.incrementAndGet() })
        Thread.sleep(200) // 400 ms since the first hop began, 200 ms into the second
        deadline.headersReceived()
        Thread.sleep(300)
        assertEquals(0, cuts.get())
        assertFalse(deadline.expired)
    }

    /**
     * PR #409 R3-F1: a connect that hangs (a TLS handshake or SOCKS reply
     * under the long read timeout) is cut at the connect limit, with no
     * slot, and cut again until it returns — a first cut can land before
     * the connection object exists and do nothing.
     */
    @Test
    fun `a connect that hangs is cut at its limit, and again until it returns`() {
        val cuts = AtomicInteger(0)
        val slot = PatientWaits(1)
        val deadline = HeaderDeadline(100, 5_000, slot, connectMs = 100, graceMs = 0) // one route: 100 + 100
        deadline.connecting(FakeConnection { cuts.incrementAndGet() })
        Thread.sleep(120)
        assertEquals("cut before the connect limit", 0, cuts.get())
        Thread.sleep(500) // the limit and a recut or two
        assertTrue(deadline.expired)
        assertFalse("a slot extended the connect", deadline.extended)
        assertTrue("cut ${cuts.get()} times", cuts.get() >= 2)
        assertTrue("took the slot", slot.tryTake())
        slot.give()
        deadline.headersReceived() // the attempt is over: no more cuts
        Thread.sleep(100)
        val after = cuts.get()
        Thread.sleep(600)
        assertEquals(after, cuts.get())
        try {
            deadline.connected(FakeConnection {})
            org.junit.Assert.fail("a hop went on after its connect expired")
        } catch (e: java.net.SocketTimeoutException) {
            // expected
        }
    }

    /**
     * PR #409 R5-M1, R6-M1: the connection tries each route in turn, each
     * under its own connect timeout and its own handshake timeout, as on
     * main — so the connect clock is both per route, plus the grace.
     */
    @Test
    fun `the connect clock allows a connect and a handshake timeout per route`() {
        val cuts = AtomicInteger(0)
        val deadline = HeaderDeadline(100, patience = PatientWaits(0), connectMs = 200, graceMs = 100)
        assertEquals(100, deadline.handshakeTimeoutMs)
        deadline.connecting(FakeConnection { cuts.incrementAndGet() }, routes = 3) // 3 x (200 + 100) + 100
        Thread.sleep(900)
        assertEquals("cut before three routes' worth", 0, cuts.get())
        assertFalse(deadline.expired)
        Thread.sleep(400)
        assertTrue(deadline.expired)
        assertTrue(cuts.get() >= 1)
        deadline.headersReceived()
    }

    /**
     * PR #409 R1-M1: a connect with another route after it (one of the
     * system proxy's routes, a pinned address) is cut at its own limit
     * without expiring the attempt, so the next route's connect still runs
     * on a clock of its own; only a cut of the last route expires it.
     */
    @Test
    fun `a cut route with a fallback after it doesn't end the attempt`() {
        val firstCuts = AtomicInteger(0)
        val deadline = HeaderDeadline(100, patience = PatientWaits(0), connectMs = 100, graceMs = 0) // 200 per route
        deadline.connecting(FakeConnection { firstCuts.incrementAndGet() }, fallback = true)
        Thread.sleep(400)
        assertTrue("the stalled route wasn't cut", firstCuts.get() >= 1)
        assertFalse("a fallback route's cut expired the attempt", deadline.expired)
        val lastCuts = AtomicInteger(0)
        deadline.connecting(FakeConnection { lastCuts.incrementAndGet() }) // the direct fallback
        val before = firstCuts.get()
        Thread.sleep(120)
        assertEquals("the first route was cut on after the next began", before, firstCuts.get())
        assertEquals("the next route didn't get a clock of its own", 0, lastCuts.get())
        deadline.connected(FakeConnection {})
        deadline.headersReceived()
        assertFalse(deadline.expired)
        // The last route cut: the attempt is over.
        val last = HeaderDeadline(100, patience = PatientWaits(0), connectMs = 100, graceMs = 0)
        last.connecting(FakeConnection {}, fallback = true)
        Thread.sleep(300)
        last.connecting(FakeConnection {})
        Thread.sleep(300)
        assertTrue(last.expired)
        last.headersReceived()
    }

    @Test
    fun `a connect in time hands over to the header clock`() {
        val cuts = AtomicInteger(0)
        val deadline = HeaderDeadline(300, patience = PatientWaits(0), connectMs = 300, graceMs = 0)
        deadline.connecting(FakeConnection { cuts.incrementAndGet() })
        Thread.sleep(200)
        deadline.connected(FakeConnection { cuts.incrementAndGet() })
        Thread.sleep(200) // 400 ms since connecting, 200 ms since connected
        deadline.headersReceived()
        Thread.sleep(400)
        assertEquals(0, cuts.get())
        assertFalse(deadline.expired)
    }

    /**
     * PR #409 R6-M1: the connect limit is main's per-route bound — a
     * connect timeout and a handshake timeout for every route, not one
     * handshake for them all — plus the grace that lets each route's own
     * socket timeouts fire first.
     */
    @Test
    fun `the connect limit is a connect and a handshake timeout per route, plus the grace`() {
        assertEquals(16_000L, connectStretchMs(5_000, 10_000, 1))
        assertEquals(31_000L, connectStretchMs(5_000, 10_000, 2))
        assertEquals(46_000L, connectStretchMs(5_000, 10_000, 3))
        // Media: main's 60 s read timeout bounds each route's handshake.
        assertEquals(131_000L, connectStretchMs(5_000, 60_000, 2))
        // No routes known still means one.
        assertEquals(16_000L, connectStretchMs(5_000, 10_000, 0))
        assertEquals(30_000L, connectStretchMs(5_000, 10_000, 2, graceMs = 0))
        assertEquals(1_000, CONNECT_GRACE_MS)
        assertEquals(5_000, GATEWAY_CONNECT_TIMEOUT_MS)
    }

    /** The per-route handshake limit is the policy's base header wait: main's read timeout. */
    @Test
    fun `each route's handshake gets main's read timeout`() {
        for (media in listOf(false, true)) {
            val policy = subresource(media)
            assertEquals(if (media) 60_000 else 10_000, HeaderDeadline(policy.baseHeaderTimeoutMs, policy.headerTimeoutMs).handshakeTimeoutMs)
        }
    }

    /**
     * PR #409 R3-M1: closing a body before its end drops the connection
     * off the closing thread (a Chromium pool worker), so a disconnect
     * that blocks doesn't hold it.
     */
    @Test
    fun `closing a body early doesn't wait on a blocking disconnect`() {
        val release = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val conn = FakeConnection { release.await(10, TimeUnit.SECONDS); disconnected.countDown() }
        val raw = object : java.io.ByteArrayInputStream(ByteArray(10)) {
            override fun close() = closed.countDown()
        }
        val body = DisconnectOnCloseInputStream(raw, conn, length = 10)
        val started = System.nanoTime()
        body.close()
        assertTrue("close blocked", (System.nanoTime() - started) / 1_000_000 < 500)
        assertEquals("the stream closed before the disconnect", 1L, closed.count)
        release.countDown()
        assertTrue(disconnected.await(2, TimeUnit.SECONDS))
        assertTrue("the stream was never closed", closed.await(2, TimeUnit.SECONDS))
    }

    private class FakeConnection(private val onDisconnect: () -> Unit) :
        HttpURLConnection(URL("http://127.0.0.1/")) {
        override fun disconnect() = onDisconnect()
        override fun usingProxy() = false
        override fun connect() {}
    }
}
