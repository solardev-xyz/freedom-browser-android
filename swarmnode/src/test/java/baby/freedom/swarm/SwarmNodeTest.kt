package baby.freedom.swarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        /** [stopGateway] blocks until this opens; open by default. */
        @Volatile var releaseStop = CountDownLatch(0)
        val stopEntered = CountDownLatch(1)

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
        /** What [initWithIdentity] was handed, copied before the node zeroes it; and the array itself. */
        val identities: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val identityArrays: MutableList<ByteArray> = Collections.synchronizedList(mutableListOf())
        override fun initWithIdentity(dataDir: String, identity: ByteArray): Long {
            identities += String(identity)
            identityArrays += identity
            val h = nextHandle++
            calls += "initWithIdentity:$h"
            initEntered.countDown()
            releaseInit.await(5, TimeUnit.SECONDS)
            return h
        }
        override fun accountInfo(handle: Long) =
            """{"eth_address":"0xabc$handle","overlay":"ff$handle","peer_id":"16Uiu2","agent":"ant-test"}"""
        override fun startGateway(handle: Long, apiAddr: String, lightMode: Boolean, gnosisRpc: String) {
            calls += "gateway:$handle"
        }
        override fun agentString(handle: Long) = "ant-test"
        override fun peerCount(handle: Long) = 0
        override fun stopGateway(handle: Long) {
            calls += "stopGateway:$handle"
            stopEntered.countDown()
            releaseStop.await(5, TimeUnit.SECONDS)
        }
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

    @Test
    fun restartAfterStopWaitsForTheOldNodeToShutDown() {
        val ops = FakeOps().apply { releaseSeed.countDown(); releaseInit.countDown() }
        val node = SwarmNode(config, ops)
        node.start()
        awaitStatus(node, NodeStatus.Running)
        ops.releaseStop = CountDownLatch(1)
        node.stop()
        node.start()
        assertTrue(ops.stopEntered.await(5, TimeUnit.SECONDS))
        Thread.sleep(300)
        // The old node still holds the gateway port: the new launch waits.
        assertTrue(ops.calls.none { it == "init:2" })
        ops.releaseStop.countDown()
        awaitStatus(node, NodeStatus.Running)
        val calls = ops.calls.toList()
        assertTrue(calls.indexOf("shutdown:1") in 0 until calls.indexOf("init:2"))
        node.dispose()
    }

    @Test
    fun withoutAWalletIdentityAntUsesItsOwn() {
        val ops = FakeOps().apply { releaseSeed.countDown(); releaseInit.countDown() }
        val node = SwarmNode(config, ops)
        node.start()
        awaitStatus(node, NodeStatus.Running)
        assertEquals(listOf("seed", "init:1", "gateway:1"), ops.calls.toList())
        assertEquals("0xabc1", node.state.value.accountAddress)
        assertEquals("ff1", node.state.value.overlay)
        assertFalse(node.state.value.walletIdentity)
        node.dispose()
    }

    @Test
    fun aWalletIdentityIsHandedToAntAndZeroedAfter() {
        val ops = FakeOps().apply { releaseSeed.countDown(); releaseInit.countDown() }
        val node = SwarmNode(config.copy(identity = { """{"signing_key":"aa"}""".toByteArray() }), ops)
        node.start()
        awaitStatus(node, NodeStatus.Running)
        assertEquals(listOf("seed", "initWithIdentity:1", "gateway:1"), ops.calls.toList())
        assertEquals(listOf("""{"signing_key":"aa"}"""), ops.identities.toList())
        assertTrue(ops.identityArrays.single().all { it.toInt() == 0 })
        assertTrue(node.state.value.walletIdentity)
        assertEquals("0xabc1", node.state.value.accountAddress)
        node.dispose()
    }

    @Test
    fun restartReadsTheIdentityAgain() {
        val ops = FakeOps().apply { releaseSeed.countDown(); releaseInit.countDown() }
        val current = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val node = SwarmNode(config.copy(identity = { current.get()?.toByteArray() }), ops)
        node.start()
        awaitStatus(node, NodeStatus.Running)
        assertFalse(node.state.value.walletIdentity)
        current.set("wallet")
        node.restart()
        val until = System.currentTimeMillis() + 5_000
        while (!node.state.value.walletIdentity && System.currentTimeMillis() < until) Thread.sleep(10)
        assertEquals(NodeStatus.Running, node.state.value.status)
        assertTrue(node.state.value.walletIdentity)
        val calls = ops.calls.toList()
        assertTrue(calls.indexOf("shutdown:1") in 0 until calls.indexOf("initWithIdentity:2"))
        assertEquals("0xabc2", node.state.value.accountAddress)
        current.set(null)
        node.restart()
        val until2 = System.currentTimeMillis() + 5_000
        while (node.state.value.walletIdentity && System.currentTimeMillis() < until2) Thread.sleep(10)
        awaitStatus(node, NodeStatus.Running)
        assertFalse(node.state.value.walletIdentity)
        assertEquals("init:3", ops.calls.last { it.startsWith("init") })
        node.dispose()
    }
}
