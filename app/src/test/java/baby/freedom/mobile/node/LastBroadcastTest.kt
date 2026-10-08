package baby.freedom.mobile.node

import baby.freedom.swarm.NodeInfo
import baby.freedom.swarm.NodeStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@OptIn(ExperimentalCoroutinesApi::class)
class LastBroadcastTest {
    private val running = NodeInfo(status = NodeStatus.Running, clientVersion = "ant-test")

    @Test
    fun answersLiveUntilTheFirstBroadcast() {
        var live = running.copy(connectedPeers = 3)
        val last = LastBroadcast { live }
        assertEquals(3L, last.current().connectedPeers)
        last.publish(running.copy(connectedPeers = 4)) {}
        live = running.copy(connectedPeers = 9)
        assertEquals(4L, last.current().connectedPeers)
    }

    /** R3-M1: a client binding while a count is held back. */
    @Test
    fun aClientJoiningMidWindowStaysInStepWithTheThrottle() = runTest {
        val source = MutableStateFlow(running.copy(connectedPeers = 30))
        val last = LastBroadcast { source.value }
        // What each bound client last received.
        val clients = mutableListOf<MutableList<NodeInfo>>(mutableListOf())
        fun send(info: NodeInfo) = clients.forEach { it += info }
        backgroundScope.launch {
            source.throttlePeerCount(5_000) { testScheduler.currentTime }.collect { last.publish(it, ::send) }
        }
        runCurrent()
        advanceTimeBy(1_000)
        source.value = running.copy(connectedPeers = 31) // held back
        runCurrent()
        // The app binds now.
        val app = mutableListOf<NodeInfo>()
        last.join({ clients += app }) { app += it }
        assertEquals(30L, app.last().connectedPeers)
        assertEquals(30L, last.current().connectedPeers)
        // The count goes back before the window ends: nothing new to send,
        // and the app already shows 30.
        advanceTimeBy(1_000)
        source.value = running.copy(connectedPeers = 30)
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(source.value, app.last())
        assertEquals(source.value, last.current())
    }

    /**
     * R4-M1: the notification while demoted. Its throttle still moves on
     * (nothing is posted), and re-promotion posts what the throttle last
     * sent, so a count going back to that value leaves it right.
     */
    @Test
    fun aRepromotionShowsTheThrottlesLastValueNotTheLiveCount() = runTest {
        val source = MutableStateFlow(running.copy(connectedPeers = 29))
        val notified = LastBroadcast { source.value }
        var demoted = true
        var shown: NodeInfo? = null
        fun update(info: NodeInfo) { if (!demoted) shown = info }
        backgroundScope.launch {
            source.throttlePeerCount(30_000) { testScheduler.currentTime }.collect { notified.publish(it, ::update) }
        }
        runCurrent()
        advanceTimeBy(31_000)
        source.value = running.copy(connectedPeers = 30) // sent, not posted
        runCurrent()
        advanceTimeBy(1_000)
        source.value = running.copy(connectedPeers = 31) // held back
        runCurrent()
        // Re-promotion.
        notified.withCurrent { info -> demoted = false; shown = info }
        assertEquals(30L, shown?.connectedPeers)
        advanceTimeBy(1_000)
        source.value = running.copy(connectedPeers = 30) // dropped as unchanged
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(source.value, shown)
    }

    /** A join racing a broadcast never ends on the older value. */
    @Test
    fun aJoinRacingAPublishEndsOnTheNewerValue() {
        repeat(200) {
            val last = LastBroadcast { 0 }
            last.publish(1) {}
            val clients = mutableListOf<MutableList<Int>>()
            val lock = Any()
            val start = CountDownLatch(1)
            val app = mutableListOf<Int>()
            val publisher = thread {
                start.await()
                last.publish(2) { v -> synchronized(lock) { clients.forEach { it += v } } }
            }
            val joiner = thread {
                start.await()
                last.join({ synchronized(lock) { clients += app } }) { v -> synchronized(lock) { app += v } }
            }
            start.countDown()
            publisher.join(TimeUnit.SECONDS.toMillis(5))
            joiner.join(TimeUnit.SECONDS.toMillis(5))
            assertEquals(2, synchronized(lock) { app.last() })
        }
    }
}
