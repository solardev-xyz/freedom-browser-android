package baby.freedom.mobile.node

import baby.freedom.swarm.NodeInfo
import baby.freedom.swarm.NodeStatus
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
    fun `a discover and a spend never overlap`() {
        assertTrue(gate.beginDiscover())
        assertTrue(gate.discoverRunning)
        assertFalse("a spend's permit must not open under a running discover", gate.begin())
        assertFalse("one discover at a time", gate.beginDiscover())
        gate.endDiscover()
        assertFalse(gate.discoverRunning)
        assertTrue(gate.begin())
        assertFalse("a discover must not run under a spend's open permit", gate.beginDiscover())
        gate.end()
        assertTrue(gate.beginDiscover())
        gate.endDiscover()
    }

    @Test
    fun `a discover's outcome is kept for the search that asked, and only once it ended`() {
        assertEquals(SpendStopGate.DiscoverStatus(running = false, outcome = null), gate.discoverStatus("a"))
        assertTrue(gate.beginDiscover())
        assertEquals(SpendStopGate.DiscoverStatus(running = true, outcome = null), gate.discoverStatus("a"))
        gate.endDiscover("a", """{"error":"rpc down"}""")
        assertEquals(SpendStopGate.DiscoverStatus(running = false, outcome = """{"error":"rpc down"}"""), gate.discoverStatus("a"))
        // Another search's outcome is not this one's.
        assertEquals(null, gate.discoverStatus("b").outcome)
        assertEquals(null, gate.discoverStatus(null).outcome)
        // A later search replaces it, and hides it while running.
        assertTrue(gate.beginDiscover())
        assertEquals(null, gate.discoverStatus("a").outcome)
        gate.endDiscover("b", """{"registered":[]}""")
        assertEquals(null, gate.discoverStatus("a").outcome)
        assertEquals("""{"registered":[]}""", gate.discoverStatus("b").outcome)
        // One that ended without an outcome (or an id) leaves none.
        assertTrue(gate.beginDiscover())
        gate.endDiscover("c", null)
        assertEquals(null, gate.discoverStatus("c").outcome)
        assertEquals(null, gate.discoverStatus("b").outcome)
    }

    @Test
    fun `a discover doesn't count as a spend for a stop`() {
        assertTrue(gate.beginDiscover())
        assertFalse(gate.requestStop())
        gate.endDiscover()
    }

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

    @Test
    fun `a process doomed to exit after a spend stays doomed`() {
        val latch = ProcessExitLatch()
        assertFalse(latch.pending)
        latch.schedule()
        // Seen from another thread (a later NodeService's main-thread onCreate,
        // or a binder thread starting a spend), and never cleared: nothing
        // started in the process could survive the exit.
        var seen = false
        thread { seen = latch.pending }.join()
        assertTrue(seen)
        latch.schedule()
        assertTrue(latch.pending)
    }

    private var nowMs = 1_000L
    private val clocked = SpendStopGate { nowMs * 1_000_000 }

    @Test
    fun `a deferred stop is overdue once its budget passes with the spend still running`() {
        assertTrue(clocked.begin())
        assertTrue(clocked.requestStop())
        val gen = clocked.stopGeneration
        assertEquals(15_000L, clocked.budgetLeftMs(15_000))
        nowMs += 14_999
        assertFalse(clocked.overdue(gen, 15_000))
        assertEquals(1L, clocked.budgetLeftMs(15_000))
        nowMs += 1
        assertTrue(clocked.overdue(gen, 15_000))
        // A destroy then waits no further.
        assertEquals(0L, clocked.budgetLeftMs(15_000))
    }

    @Test
    fun `toggling off again doesn't extend the wait`() {
        assertTrue(clocked.begin())
        assertTrue(clocked.requestStop())
        val gen = clocked.stopGeneration
        nowMs += 10_000
        assertTrue(clocked.requestStop())
        assertEquals(gen, clocked.stopGeneration)
        assertEquals(5_000L, clocked.budgetLeftMs(15_000))
        nowMs += 5_000
        assertTrue(clocked.overdue(gen, 15_000))
    }

    @Test
    fun `turning the node back on disarms the old stop's deadline, and a new stop gets its own`() {
        assertTrue(clocked.begin())
        assertTrue(clocked.requestStop())
        val first = clocked.stopGeneration
        nowMs += 10_000
        clocked.cancelStop()
        assertEquals(15_000L, clocked.budgetLeftMs(15_000))
        assertTrue(clocked.requestStop())
        nowMs += 5_000
        // The first stop's timer fires now: it must not act on the second.
        assertFalse(clocked.overdue(first, 15_000))
        assertFalse(clocked.overdue(clocked.stopGeneration, 15_000))
        nowMs += 10_000
        assertTrue(clocked.overdue(clocked.stopGeneration, 15_000))
    }

    @Test
    fun `a spend that ends before the deadline leaves nothing overdue`() {
        assertTrue(clocked.begin())
        assertTrue(clocked.requestStop())
        val gen = clocked.stopGeneration
        assertTrue(clocked.end())
        nowMs += 60_000
        assertFalse(clocked.overdue(gen, 15_000))
    }

    @Test
    fun `a service in a doomed process reports why its node isn't up`() {
        val shown = reportedNodeInfo(NodeInfo(), doomed = true)
        assertEquals(NodeStatus.Starting, shown.status)
        assertEquals(WAITING_FOR_SPEND_NOTE, shown.errorMessage)
        // Otherwise, or once the node really moves, the node's own state.
        assertEquals(NodeInfo(), reportedNodeInfo(NodeInfo(), doomed = false))
        val err = NodeInfo(status = NodeStatus.Error, errorMessage = "x")
        assertEquals(err, reportedNodeInfo(err, doomed = true))
    }
}
