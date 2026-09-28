package baby.freedom.mobile.browser

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

class FirstBuildGateTest {

    private val now = AtomicLong(1_000L)
    private fun gate(waitMs: Long = 5_000L) = FirstBuildGate(waitMs) { now.get() }

    @Test
    fun `nothing waits before the build starts`() {
        val g = gate()
        assertFalse(g.pending)
        val t0 = System.nanoTime()
        assertFalse(g.await())
        assertTrue(System.nanoTime() - t0 < 500_000_000L)
    }

    @Test
    fun `a request during the build waits for it and sees it land`() {
        val g = gate()
        g.open()
        assertTrue(g.pending)
        thread { Thread.sleep(100); g.ready() }
        assertTrue(g.await())
        assertFalse(g.pending)
        assertTrue(g.await())
    }

    @Test
    fun `the deadline is shared and fixed at open, not per request`() {
        val g = gate(waitMs = 300L)
        val real = FirstBuildGate(300L) { System.nanoTime() / 1_000_000 }
        real.open()
        val t0 = System.nanoTime()
        assertFalse(real.await())
        val waited = (System.nanoTime() - t0) / 1_000_000
        assertTrue("waited $waited ms", waited in 250..2_000)
        // A second request after the deadline doesn't wait again.
        val t1 = System.nanoTime()
        assertFalse(real.await())
        assertTrue((System.nanoTime() - t1) / 1_000_000 < 100)
        assertFalse(real.pending)

        g.open()
        now.addAndGet(300L)
        assertFalse(g.pending)
        assertFalse(g.await())
    }

    @Test
    fun `open is idempotent and doesn't push the deadline back`() {
        val g = gate(waitMs = 1_000L)
        g.open()
        now.addAndGet(900L)
        g.open()
        assertTrue(g.pending)
        now.addAndGet(100L)
        assertFalse(g.pending)
    }

    @Test
    fun `ready before open means no wait`() {
        val g = gate()
        g.ready()
        g.open()
        assertFalse(g.pending)
        assertTrue(g.await())
    }

    @Test
    fun `the suspending wait sees the build land`() = runBlocking {
        val g = gate()
        g.open()
        thread { Thread.sleep(100); g.ready() }
        assertTrue(g.awaitSuspending())
    }
}
