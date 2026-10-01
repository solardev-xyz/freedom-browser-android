package baby.freedom.swarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The freedom-ipfs node's lifetime: switching IPFS off really frees the
 * node (its loopback gateway stops answering), and no native call ever
 * runs on a node that has been freed.
 */
class IpfsNodeTest {
    private class FakeOps : IpfsNode.Ops {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        @Volatile var nextHandle = 1L
        val freed = CountDownLatch(1)
        val gatewayEntered = CountDownLatch(1)
        /** [startGatewayOnline] blocks until this opens; open by default. */
        @Volatile var releaseGateway = CountDownLatch(0)
        val foregroundEntered = CountDownLatch(1)
        /** [enterForeground] blocks until this opens; open by default. */
        @Volatile var releaseForeground = CountDownLatch(0)
        private val live: MutableSet<Long> = Collections.synchronizedSet(mutableSetOf())

        /** A call on a handle that was never made or was already freed: a use-after-free on the device. */
        val stale: MutableList<String> = Collections.synchronizedList(mutableListOf())
        private fun use(what: String, handle: Long) {
            calls += "$what:$handle"
            if (handle !in live) stale += "$what:$handle"
        }

        /** A node made while another was still alive: on the device the store lock refuses it, or the old one leaks. */
        val overlapping: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override fun nodeNew(dataDir: String, maxCacheBytes: Long): Long {
            val h = nextHandle++
            if (live.isNotEmpty()) overlapping += "new:$h while ${live.toList()}"
            live += h
            calls += "new:$h"
            return h
        }
        override fun nodeFree(handle: Long) {
            use("free", handle)
            live -= handle
            freed.countDown()
        }
        override fun startGatewayOnline(handle: Long, addr: String, routingMode: Int): Boolean {
            use("gateway", handle)
            gatewayEntered.countDown()
            releaseGateway.await(5, TimeUnit.SECONDS)
            calls += "gatewayUp:$handle"
            return true
        }
        override fun stopGateway(handle: Long): Boolean {
            use("stopGateway", handle)
            return true
        }
        override fun gatewayUrl(handle: Long): String {
            use("url", handle)
            return "http://127.0.0.1:4${handle}000"
        }
        override fun version() = "0.4.4"
        override fun diagnostics(handle: Long): LongArray {
            use("diagnostics", handle)
            return LongArray(11)
        }
        override fun progressSnapshotJson(handle: Long): ByteArray {
            use("progress", handle)
            return "{}".toByteArray()
        }
        override fun handleNetworkChange(handle: Long): Boolean {
            use("network", handle)
            return true
        }
        override fun enterBackground(handle: Long): Boolean {
            use("background", handle)
            return true
        }
        override fun enterForeground(handle: Long): Boolean {
            use("foreground", handle)
            foregroundEntered.countDown()
            releaseForeground.await(5, TimeUnit.SECONDS)
            calls += "foregrounded:$handle"
            return true
        }
    }

    /** Each test its own data dir: the live-node lease is process-wide, per dir. */
    private val dataDir = "/data/ipfs-${java.util.UUID.randomUUID()}"

    private fun node(ops: FakeOps) = IpfsNode(IpfsNode.Config(dataDir = dataDir), ops)

    private fun awaitStatus(node: IpfsNode, status: IpfsStatus) {
        val until = System.currentTimeMillis() + 5_000
        while (node.state.value.status != status && System.currentTimeMillis() < until) Thread.sleep(5)
        assertEquals(status, node.state.value.status)
    }

    @Test
    fun `dispose frees the running node and stops its gateway`() {
        // NodeService.maybeStopIpfs (Settings → IPFS off) disposes the node:
        // it must not be left running, gateway still serving on loopback.
        repeat(20) {
            val ops = FakeOps()
            val node = node(ops)
            node.start()
            awaitStatus(node, IpfsStatus.Running)
            node.dispose()
            assertTrue("round $it: ${ops.calls}", ops.freed.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("stopGateway:1", "free:1"), ops.calls.filter { c -> c.startsWith("stopGateway") || c.startsWith("free") })
            assertEquals(IpfsStatus.Stopped, node.state.value.status)
        }
    }

    @Test
    fun `a stop waits for a lifecycle call still inside the node before freeing it`() {
        val ops = FakeOps().apply { releaseForeground = CountDownLatch(1) }
        val node = node(ops)
        node.start()
        awaitStatus(node, IpfsStatus.Running)
        node.enterForeground()
        assertTrue(ops.foregroundEntered.await(5, TimeUnit.SECONDS))
        node.stop()
        Thread.sleep(300)
        assertTrue(ops.calls.toString(), ops.calls.none { it.startsWith("free") || it.startsWith("stopGateway") })
        ops.releaseForeground.countDown()
        assertTrue(ops.freed.await(5, TimeUnit.SECONDS))
        val calls = ops.calls.toList()
        assertTrue(calls.toString(), calls.indexOf("foregrounded:1") < calls.indexOf("stopGateway:1"))
        assertEquals(emptyList<String>(), ops.stale.toList())
        node.dispose()
    }

    @Test
    fun `a stop while the gateway starts frees the node only after the start returns, and stays stopped`() {
        val ops = FakeOps().apply { releaseGateway = CountDownLatch(1) }
        val node = node(ops)
        node.start()
        assertTrue(ops.gatewayEntered.await(5, TimeUnit.SECONDS))
        node.stop()
        Thread.sleep(300)
        assertTrue(ops.calls.toString(), ops.calls.none { it.startsWith("free") })
        ops.releaseGateway.countDown()
        assertTrue(ops.freed.await(5, TimeUnit.SECONDS))
        Thread.sleep(200)
        assertEquals(IpfsStatus.Stopped, node.state.value.status)
        assertEquals("", node.state.value.gatewayUrl)
        assertEquals(1, ops.calls.count { it.startsWith("free") })
        assertEquals(emptyList<String>(), ops.stale.toList())
        node.dispose()
    }

    @Test
    fun `calls after a stop never reach a freed node`() {
        val ops = FakeOps()
        val node = node(ops)
        node.start()
        awaitStatus(node, IpfsStatus.Running)
        node.stop()
        node.enterBackground()
        node.enterForeground()
        node.onNetworkChanged()
        assertEquals(null, node.progressSnapshotJson())
        assertEquals(null, node.diagnostics())
        assertTrue(ops.freed.await(5, TimeUnit.SECONDS))
        Thread.sleep(200)
        assertEquals(emptyList<String>(), ops.stale.toList())
        node.dispose()
    }

    @Test
    fun `stop then start runs exactly one node, and frees the old one`() {
        val ops = FakeOps()
        val node = node(ops)
        node.start()
        awaitStatus(node, IpfsStatus.Running)
        node.stop()
        node.start()
        awaitStatus(node, IpfsStatus.Running)
        assertTrue(ops.freed.await(5, TimeUnit.SECONDS))
        Thread.sleep(200)
        assertEquals(listOf("free:1"), ops.calls.filter { it.startsWith("free") })
        assertEquals("http://127.0.0.1:42000", node.state.value.gatewayUrl)
        assertEquals(emptyList<String>(), ops.stale.toList())
        node.dispose()
    }

    @Test
    fun `off then on while a call holds the node frees the old node before making the new one`() {
        // A lifecycle call holds the node; Settings → IPFS off then on. The
        // stop's release and the new launch both wait on it, and whichever
        // goes first, node 1 must be freed — and before node 2 exists.
        repeat(10) { round ->
            val ops = FakeOps().apply { releaseForeground = CountDownLatch(1) }
            val node = node(ops)
            node.start()
            awaitStatus(node, IpfsStatus.Running)
            node.enterForeground()
            assertTrue(ops.foregroundEntered.await(5, TimeUnit.SECONDS))
            node.stop()
            node.start()
            Thread.sleep(100)
            ops.releaseForeground.countDown()
            awaitStatus(node, IpfsStatus.Running)
            assertTrue(ops.freed.await(5, TimeUnit.SECONDS))
            Thread.sleep(100)
            val calls = ops.calls.toList()
            assertEquals("round $round: $calls", listOf("free:1"), calls.filter { it.startsWith("free") })
            assertEquals("round $round: $calls", listOf("stopGateway:1"), calls.filter { it.startsWith("stopGateway") })
            assertTrue("round $round: $calls", calls.indexOf("free:1") < calls.indexOf("new:2"))
            assertEquals("http://127.0.0.1:42000", node.state.value.gatewayUrl)
            assertEquals(emptyList<String>(), ops.overlapping.toList())
            assertEquals(emptyList<String>(), ops.stale.toList())
            node.dispose()
        }
    }

    @Test
    fun `a stop during a slow gateway start never blocks the binder polls`() {
        val ops = FakeOps().apply { releaseGateway = CountDownLatch(1) }
        val node = node(ops)
        node.start()
        assertTrue(ops.gatewayEntered.await(5, TimeUnit.SECONDS))
        node.stop()
        Thread.sleep(100)
        val t0 = System.nanoTime()
        assertEquals(null, node.progressSnapshotJson())
        assertEquals(null, node.diagnostics())
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("polls took $ms ms", ms < 500)
        ops.releaseGateway.countDown()
        assertTrue(ops.freed.await(5, TimeUnit.SECONDS))
        Thread.sleep(100)
        assertEquals(IpfsStatus.Stopped, node.state.value.status)
        assertEquals(emptyList<String>(), ops.stale.toList())
        node.dispose()
    }

    @Test
    fun `a new instance after off then on waits for the old instance's node to be freed`() {
        // NodeService builds a fresh IpfsNode on every off → on: the old
        // instance's release (held up here by a call still inside its node)
        // must finish before the new instance opens the same data dir.
        repeat(10) { round ->
            val ops = FakeOps().apply { releaseForeground = CountDownLatch(1) }
            val old = node(ops)
            old.start()
            awaitStatus(old, IpfsStatus.Running)
            old.enterForeground()
            assertTrue(ops.foregroundEntered.await(5, TimeUnit.SECONDS))
            old.dispose()
            val fresh = node(ops)
            fresh.start()
            Thread.sleep(150)
            assertTrue("round $round: ${ops.calls}", ops.calls.none { it == "new:2" })
            assertEquals(IpfsStatus.Starting, fresh.state.value.status)
            ops.releaseForeground.countDown()
            awaitStatus(fresh, IpfsStatus.Running)
            val calls = ops.calls.toList()
            assertTrue("round $round: $calls", calls.indexOf("free:1") in 0 until calls.indexOf("new:2"))
            assertEquals("http://127.0.0.1:42000", fresh.state.value.gatewayUrl)
            assertEquals(emptyList<String>(), ops.overlapping.toList())
            assertEquals(emptyList<String>(), ops.stale.toList())
            fresh.dispose()
        }
    }

    @Test
    fun `a new instance waits for the old instance's launch still starting its gateway`() {
        // Off while the old instance's gateway is still starting, then on:
        // its launch frees node 1 only once the start returns, and the new
        // instance's node must not exist before then.
        val ops = FakeOps().apply { releaseGateway = CountDownLatch(1) }
        val old = node(ops)
        old.start()
        assertTrue(ops.gatewayEntered.await(5, TimeUnit.SECONDS))
        old.dispose()
        val fresh = node(ops)
        fresh.start()
        Thread.sleep(200)
        assertTrue(ops.calls.toString(), ops.calls.none { it == "new:2" })
        ops.releaseGateway.countDown()
        awaitStatus(fresh, IpfsStatus.Running)
        val calls = ops.calls.toList()
        assertTrue(calls.toString(), calls.indexOf("free:1") in 0 until calls.indexOf("new:2"))
        assertEquals(IpfsStatus.Stopped, old.state.value.status)
        assertEquals(emptyList<String>(), ops.overlapping.toList())
        assertEquals(emptyList<String>(), ops.stale.toList())
        fresh.dispose()
    }

    @Test
    fun `a failed nodeNew gives the lease back, so a later start still runs`() {
        val delegate = FakeOps()
        val ops = object : IpfsNode.Ops by delegate {
            @Volatile var fail = true
            override fun nodeNew(dataDir: String, maxCacheBytes: Long): Long =
                if (fail) throw IllegalStateException("boom") else delegate.nodeNew(dataDir, maxCacheBytes)
        }
        val node = IpfsNode(IpfsNode.Config(dataDir = dataDir), ops)
        node.start()
        awaitStatus(node, IpfsStatus.Error)
        ops.fail = false
        node.start()
        awaitStatus(node, IpfsStatus.Running)
        assertEquals(emptyList<String>(), delegate.stale.toList())
        node.dispose()
    }
}
