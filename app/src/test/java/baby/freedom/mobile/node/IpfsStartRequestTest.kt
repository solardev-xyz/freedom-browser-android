package baby.freedom.mobile.node

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IpfsStartRequestTest {
    private var clock = 1_000L
    private val request = IpfsStartRequest(windowMs = 90_000L, now = { clock })

    // Stands in for MainActivity's binder: null until the bind connects,
    // and `binder?.ensureIpfsStarted()` is then a no-op, as on a cold start.
    private var connected = false
    private var starts = 0
    private val send: () -> Unit = { if (connected) starts++ }

    private fun connect() {
        connected = true
        request.onConnected(send)
    }

    @Test
    fun `an ask made before the binder connects is sent when it connects`() {
        request.ask(bound = true, send)
        assertEquals("nothing to send to yet", 0, starts)
        clock += 2_000
        connect()
        assertEquals(1, starts)
    }

    @Test
    fun `an ask made while connected is sent at once`() {
        connect()
        request.ask(bound = true, send)
        assertEquals(1, starts)
    }

    @Test
    fun `a reconnect while the tab still waits sends it again`() {
        request.ask(bound = true, send)
        connect()
        connected = false // :node died
        clock += 10_000
        connect()
        assertEquals(2, starts)
    }

    @Test
    fun `no ask, no start on connect`() {
        connect()
        assertEquals(0, starts)
    }

    @Test
    fun `an ask the tab already gave up on isn't sent`() {
        request.ask(bound = true, send)
        clock += 90_000
        assertFalse(request.pending)
        connect()
        assertEquals(0, starts)
    }

    @Test
    fun `an ask made with the node off isn't kept for a later bind`() {
        request.settled(bound = false)
        request.ask(bound = false, send)
        assertFalse(request.pending)
        connect()
        assertEquals(0, starts)
    }

    @Test
    fun `switching IPFS or the node off forgets the ask`() {
        request.ask(bound = true, send)
        assertTrue(request.pending)
        request.forget()
        connect()
        assertEquals(0, starts)
    }

    @Test
    fun `a clock reading before the ask doesn't keep it live`() {
        request.ask(bound = true, send)
        clock -= 1
        assertFalse(request.pending)
        connect()
        assertEquals(0, starts)
    }

    @Test
    fun `an ask made before the cold start read whether the node runs is kept for its bind`() {
        // BrowserScreen asks before onCreate's runNodeEnabled read resumes:
        // nothing is bound yet, though a bind is about to start.
        request.ask(bound = false, send)
        assertTrue(request.pending)
        request.settled(bound = true)
        clock += 2_000
        connect()
        assertEquals(1, starts)
    }

    @Test
    fun `an ask made before the cold start read is dropped once it finds the node off`() {
        request.ask(bound = false, send)
        request.settled(bound = false)
        assertFalse(request.pending)
        connect()
        assertEquals(0, starts)
    }

    @Test
    fun `once settled, an unbound ask isn't kept`() {
        request.settled(bound = true)
        request.ask(bound = false, send) // the node was switched off since
        assertFalse(request.pending)
    }
}
