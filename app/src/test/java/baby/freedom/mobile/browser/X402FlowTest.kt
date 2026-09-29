package baby.freedom.mobile.browser

import baby.freedom.mobile.data.X402Store.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which navigation an x402 402 or a paid request's answer belongs to (#218 R2). */
class X402FlowTest {
    private val settled = mutableListOf<Triple<String, Status, Int?>>()
    private val flow = X402Flow<String> { id, status, http -> settled += Triple(id, status, http) }
    private val tab = 1L
    private val a = "https://pay.example/a"
    private val b = "https://pay.example/b"

    @Test
    fun `a 402 is paid for at its own commit`() {
        flow.detected(tab, a, "terms")
        assertEquals("terms", flow.committed(tab, a))
        assertNull(flow.committed(tab, a))
    }

    @Test
    fun `a paid request's own commit settles it paid`() {
        flow.paid(tab, a, "r1")
        assertNull(flow.committed(tab, a))
        assertEquals(listOf(Triple("r1", Status.PAID, null)), settled)
    }

    @Test
    fun `R2-M1 a paid request redirected to a 402 is refused, not paid again`() {
        flow.paid(tab, a, "r1")
        flow.redirected(tab, b)
        assertTrue(flow.httpError(tab, b, "GET", 402))
        // Its commit is the refused page's: nothing to pay.
        assertNull(flow.committed(tab, b))
        assertEquals(listOf(Triple("r1", Status.REFUSED, 402)), settled)
    }

    @Test
    fun `R2-M1 a paid request redirected to its page settles paid`() {
        flow.paid(tab, a, "r1")
        flow.redirected(tab, b)
        assertNull(flow.committed(tab, b))
        assertEquals(listOf(Triple("r1", Status.PAID, null)), settled)
    }

    @Test
    fun `R2-M2 a 402 stopped before commit isn't paid at a later commit of its URL`() {
        flow.detected(tab, a, "terms")
        flow.superseded(tab) // Stop, a download, a new load
        assertNull(flow.committed(tab, a))
    }

    @Test
    fun `R2-M2 a 402 whose load finished without committing is dropped`() {
        flow.detected(tab, a, "terms")
        flow.loadFinished(tab, "https://other.example/")
        flow.loadFinished(tab, a)
        assertNull(flow.committed(tab, a))
    }

    @Test
    fun `R2-M2 a redirect after a 402 drops it`() {
        flow.detected(tab, a, "terms")
        flow.redirected(tab, a)
        assertNull(flow.committed(tab, a))
    }

    @Test
    fun `R2-M3 a reload while a paid request is in flight ends it unconfirmed`() {
        flow.paid(tab, a, "r1")
        flow.superseded(tab) // the user's Reload
        assertEquals(listOf(Triple("r1", Status.UNCONFIRMED, null)), settled)
        // The reload's own 402 is not the paid request's answer: it's asked about.
        assertFalse(flow.httpError(tab, a, "GET", 402))
        flow.detected(tab, a, "terms")
        assertEquals("terms", flow.committed(tab, a))
        assertEquals(1, settled.size)
    }

    @Test
    fun `a form POST's error isn't the paid request's answer`() {
        flow.paid(tab, a, "r1")
        assertFalse(flow.httpError(tab, a, "POST", 403))
        assertTrue(settled.isEmpty())
    }

    @Test
    fun `a network error on a hop settles unconfirmed, on another URL not`() {
        flow.paid(tab, a, "r1")
        flow.failed(tab, b)
        assertTrue(settled.isEmpty())
        flow.redirected(tab, b)
        flow.failed(tab, b)
        assertEquals(listOf(Triple("r1", Status.UNCONFIRMED, null)), settled)
    }

    @Test
    fun `tabs are apart`() {
        flow.paid(tab, a, "r1")
        flow.detected(2L, a, "terms")
        flow.superseded(2L)
        assertTrue(settled.isEmpty())
        assertTrue(flow.httpError(tab, a, "GET", 500))
    }

    @Test
    fun `R3-M1 a form POST during a paid request ends it unconfirmed, and its commit isn't Paid`() {
        flow.paid(tab, a, "r1")
        flow.mainFrameRequested(tab, "POST")
        assertNull(flow.committed(tab, a))
        assertEquals(listOf(Triple("r1", Status.UNCONFIRMED, null)), settled)
    }

    @Test
    fun `R3-M1 a POST's redirect to a 402 is a fresh 402, not the paid request refused`() {
        flow.paid(tab, a, "r1")
        flow.mainFrameRequested(tab, "POST")
        flow.redirected(tab, b)
        assertFalse(flow.httpError(tab, b, "GET", 402))
        flow.detected(tab, b, "terms")
        assertEquals("terms", flow.committed(tab, b))
        assertEquals(listOf(Triple("r1", Status.UNCONFIRMED, null)), settled)
    }

    @Test
    fun `R3-M1 the paid request's own GET and its hops don't end it`() {
        flow.paid(tab, a, "r1")
        flow.mainFrameRequested(tab, "GET")
        flow.redirected(tab, b)
        flow.mainFrameRequested(tab, "GET")
        assertNull(flow.committed(tab, b))
        assertEquals(listOf(Triple("r1", Status.PAID, null)), settled)
    }
}
