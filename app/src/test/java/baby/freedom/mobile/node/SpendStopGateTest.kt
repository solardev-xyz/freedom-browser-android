package baby.freedom.mobile.node

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class SpendStopGateTest {
    private val gate = SpendStopGate()

    @Test
    fun `with no spend running a stop happens now`() {
        assertFalse(gate.requestStop())
        assertFalse(gate.shouldStopNow())
    }

    @Test
    fun `a stop during a spend waits for it and is due once it ends`() {
        assertTrue(gate.begin())
        assertTrue(gate.requestStop())
        assertFalse(gate.shouldStopNow())
        assertTrue(gate.end())
        assertTrue(gate.shouldStopNow())
    }

    @Test
    fun `no spend starts once a stop is asked for, until the node is turned back on`() {
        gate.requestStop()
        assertFalse(gate.begin())
        assertEquals(0, gate.spendsRunning)
        gate.cancelStop()
        assertTrue(gate.begin())
        assertEquals(1, gate.spendsRunning)
    }

    @Test
    fun `turning the node back on during a spend cancels the deferred stop`() {
        assertTrue(gate.begin())
        assertTrue(gate.requestStop())
        gate.cancelStop()
        assertFalse(gate.end())
        assertFalse(gate.shouldStopNow())
    }

    @Test
    fun `a spend ending with no stop asked for stops nothing`() {
        assertTrue(gate.begin())
        assertFalse(gate.end())
        assertFalse(gate.shouldStopNow())
    }

    @Test
    fun `awaitIdle holds a destroy's exit until the spend ends`() {
        assertTrue(gate.begin())
        gate.requestStop()
        val started = CountDownLatch(1)
        val done = CountDownLatch(1)
        var idle = false
        thread {
            started.countDown()
            idle = gate.awaitIdle(10_000)
            done.countDown()
        }
        started.await()
        assertFalse("exited while the spend still ran", done.await(300, TimeUnit.MILLISECONDS))
        gate.end()
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertTrue(idle)
    }

    @Test
    fun `awaitIdle gives up after its timeout`() {
        assertTrue(gate.begin())
        assertFalse(gate.awaitIdle(50))
        assertTrue(SpendStopGate().awaitIdle(0))
    }
}
