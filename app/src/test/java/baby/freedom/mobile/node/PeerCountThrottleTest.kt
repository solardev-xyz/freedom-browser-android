package baby.freedom.mobile.node

import baby.freedom.swarm.NodeInfo
import baby.freedom.swarm.NodeStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PeerCountThrottleTest {
    private val running = NodeInfo(status = NodeStatus.Running, clientVersion = "ant-test")

    /** Collects [source] throttled to [window] ms into the returned list. */
    private fun TestScope.collect(source: MutableStateFlow<NodeInfo>, window: Long): MutableList<NodeInfo> {
        val out = mutableListOf<NodeInfo>()
        backgroundScope.launch {
            source.throttlePeerCount(window) { testScheduler.currentTime }.collect { out += it }
        }
        runCurrent()
        return out
    }

    @Test
    fun aPeerCountChangingEverySecondGoesOutOncePerWindow() = runTest {
        val source = MutableStateFlow(running)
        val out = collect(source, 5_000)
        val times = mutableListOf<Long>()
        backgroundScope.launch {
            source.throttlePeerCount(5_000) { testScheduler.currentTime }.collect { times += testScheduler.currentTime }
        }
        runCurrent()
        // A minute of a count churning every second.
        for (s in 1..60) {
            advanceTimeBy(1_000)
            source.value = running.copy(connectedPeers = 30L + s % 7)
            runCurrent()
        }
        advanceTimeBy(5_000)
        runCurrent()
        // The first value, then at most one per 5 s window: not 61.
        assertTrue("sent ${out.size}", out.size <= 14)
        assertTrue(times.toString(), times.zipWithNext().all { (a, b) -> b - a >= 5_000 })
        // The latest count isn't lost.
        assertEquals(source.value, out.last())
    }

    @Test
    fun theLatestCountGoesOutWhenTheWindowEnds() = runTest {
        val source = MutableStateFlow(running.copy(connectedPeers = 1))
        val out = collect(source, 5_000)
        advanceTimeBy(1_000)
        source.value = running.copy(connectedPeers = 2)
        runCurrent()
        advanceTimeBy(1_000)
        source.value = running.copy(connectedPeers = 3)
        runCurrent()
        assertEquals(listOf(1L), out.map { it.connectedPeers })
        advanceTimeBy(2_999)
        runCurrent()
        assertEquals(listOf(1L), out.map { it.connectedPeers })
        advanceTimeBy(1)
        runCurrent()
        // 2 was replaced before its turn; 3 goes out at the window's end.
        assertEquals(listOf(1L, 3L), out.map { it.connectedPeers })
    }

    @Test
    fun aCountAfterAQuietSpellGoesOutAtOnce() = runTest {
        val source = MutableStateFlow(running.copy(connectedPeers = 1))
        val out = collect(source, 5_000)
        advanceTimeBy(6_000)
        source.value = running.copy(connectedPeers = 2)
        runCurrent()
        assertEquals(listOf(1L, 2L), out.map { it.connectedPeers })
    }

    @Test
    fun anyOtherChangeGoesOutAtOnceWithTheLatestCount() = runTest {
        val source = MutableStateFlow(running.copy(connectedPeers = 1))
        val out = collect(source, 30_000)
        advanceTimeBy(1_000)
        source.value = running.copy(connectedPeers = 9)
        runCurrent()
        assertEquals(1, out.size)
        // The node stops: shown straight away, not 29 s later.
        source.value = NodeInfo(status = NodeStatus.Stopped)
        runCurrent()
        assertEquals(listOf(NodeStatus.Running, NodeStatus.Stopped), out.map { it.status })
        // And starting again with a count is a status change too.
        source.value = running.copy(connectedPeers = 4)
        runCurrent()
        assertEquals(running.copy(connectedPeers = 4), out.last())
        // Nothing held back from before the stop turns up later.
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(3, out.size)
    }

    @Test
    fun anErrorWithTheSameCountGoesOutAtOnce() = runTest {
        val source = MutableStateFlow(running.copy(connectedPeers = 5))
        val out = collect(source, 30_000)
        source.value = running.copy(connectedPeers = 5, errorMessage = "gateway down")
        runCurrent()
        assertEquals(2, out.size)
        assertEquals("gateway down", out.last().errorMessage)
    }
}
