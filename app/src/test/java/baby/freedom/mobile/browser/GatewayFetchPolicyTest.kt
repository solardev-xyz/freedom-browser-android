package baby.freedom.mobile.browser

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
}
