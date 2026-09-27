package baby.freedom.swarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SwarmNodeTest {
    /** Records native calls; [seed] and [init] block until released. */
    private class FakeOps : SwarmNode.NodeOps {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val seedEntered = CountDownLatch(1)
        val releaseSeed = CountDownLatch(1)
        val initEntered = CountDownLatch(1)
        val releaseInit = CountDownLatch(1)
        val shutDown = CountDownLatch(1)
        @Volatile var nextHandle = 1L

        override fun seed(antDir: File) {
            calls += "seed"
            seedEntered.countDown()
            releaseSeed.await(5, TimeUnit.SECONDS)
        }
        override fun init(dataDir: String): Long {
            val h = nextHandle++
            calls += "init:$h"
            initEntered.countDown()
            releaseInit.await(5, TimeUnit.SECONDS)
            return h
        }
        override fun startGateway(handle: Long, apiAddr: String, lightMode: Boolean, gnosisRpc: String) {
            calls += "gateway:$handle"
        }
        override fun agentString(handle: Long) = "ant-test"
        override fun peerCount(handle: Long) = 0
        override fun stopGateway(handle: Long) { calls += "stopGateway:$handle" }
        override fun shutdown(handle: Long) {
            calls += "shutdown:$handle"
            shutDown.countDown()
        }
    }

    private val config = SwarmNode.Config(dataDir = "/nonexistent")

    private fun awaitStatus(node: SwarmNode, status: NodeStatus) {
        val until = System.currentTimeMillis() + 5_000
        while (node.state.value.status != status && System.currentTimeMillis() < until) Thread.sleep(10)
        assertEquals(status, node.state.value.status)
    }

    @Test
    fun startsNormally() {
        val ops = FakeOps().apply { releaseSeed.countDown(); releaseInit.countDown() }
        val node = SwarmNode(config, ops)
        node.start()
        awaitStatus(node, NodeStatus.Running)
        assertEquals("ant-test", node.state.value.clientVersion)
        node.dispose()
    }

    @Test
    fun stopDuringSeedingNeverInitsAndStaysStopped() {
        val ops = FakeOps().apply { releaseInit.countDown() }
        val node = SwarmNode(config, ops)
        node.start()
        assertTrue(ops.seedEntered.await(5, TimeUnit.SECONDS))
        node.stop()
        ops.releaseSeed.countDown()
        Thread.sleep(300)
        assertEquals(NodeStatus.Stopped, node.state.value.status)
        assertEquals(listOf("seed"), ops.calls.toList())
        node.dispose()
    }

    @Test
    fun stopDuringInitShutsTheNewNodeDownAndStaysStopped() {
        val ops = FakeOps().apply { releaseSeed.countDown() }
        val node = SwarmNode(config, ops)
        node.start()
        assertTrue(ops.initEntered.await(5, TimeUnit.SECONDS))
        node.stop()
        ops.releaseInit.countDown()
        assertTrue(ops.shutDown.await(5, TimeUnit.SECONDS))
        assertEquals(NodeStatus.Stopped, node.state.value.status)
        assertEquals(listOf("seed", "init:1", "gateway:1", "stopGateway:1", "shutdown:1"), ops.calls.toList())
        node.dispose()
    }

    @Test
    fun restartDuringSeedingRunsExactlyOneNode() {
        val ops = FakeOps().apply { releaseInit.countDown() }
        val node = SwarmNode(config, ops)
        node.start()
        assertTrue(ops.seedEntered.await(5, TimeUnit.SECONDS))
        node.stop()
        node.start()
        ops.releaseSeed.countDown()
        awaitStatus(node, NodeStatus.Running)
        Thread.sleep(300)
        assertEquals(1, ops.calls.count { it.startsWith("init:") })
        assertTrue(ops.calls.none { it.startsWith("shutdown:") })
        node.dispose()
    }
}
