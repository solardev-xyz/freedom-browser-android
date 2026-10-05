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
    fun `only a subresource gets a header deadline separate from the body`() {
        // fetchOnce arms its outside header deadline only when the two differ.
        assertTrue(subresource().headerTimeoutMs < subresource().bodyStallTimeoutMs)
        assertTrue(subresource(media = true).headerTimeoutMs < subresource(media = true).bodyStallTimeoutMs)
        assertEquals(navigation().headerTimeoutMs, navigation().bodyStallTimeoutMs)
    }
}
