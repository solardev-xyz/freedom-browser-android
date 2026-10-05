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
    private fun subresource(method: String = "GET", media: Boolean = false) =
        gatewayFetchPolicy(mainFrame = false, method = method, media = media)

    private fun navigation(method: String = "GET", media: Boolean = false) =
        gatewayFetchPolicy(mainFrame = true, method = method, media = media)

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
        assertFalse(404 in subresource(method = "HEAD").retryStatuses)
    }

    @Test
    fun `a subresource GET or HEAD retries the transient 5xx`() {
        for (method in listOf("GET", "HEAD", "get")) {
            assertEquals(setOf(500, 502, 503, 504), subresource(method).retryStatuses)
        }
    }

    @Test
    fun `other statuses are never retried for a subresource`() {
        for (status in listOf(200, 206, 301, 304, 400, 401, 403, 405, 410, 416, 429, 501, 505)) {
            assertFalse("$status", status in subresource().retryStatuses)
        }
    }

    @Test
    fun `a non-idempotent subresource is never retried`() {
        for (method in listOf("POST", "PUT", "PATCH", "DELETE")) {
            assertTrue(method, subresource(method).retryStatuses.isEmpty())
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

    private class FakeConnection(private val onDisconnect: () -> Unit) :
        HttpURLConnection(URL("http://127.0.0.1/")) {
        override fun disconnect() = onDisconnect()
        override fun usingProxy() = false
        override fun connect() {}
    }
}
