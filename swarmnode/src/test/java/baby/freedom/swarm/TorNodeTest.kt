package baby.freedom.swarm

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.concurrent.TimeUnit

class TorNodeTest {
    private class FakeOps : TorNode.Ops {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        @Volatile var startError: String? = null
        @Volatile var status = """{"state":"bootstrapping","port":40123,"progress":0.25,"summary":"25%: loading","blocked":null,"error":null}"""
        @Volatile var libraryMissing = false
        /** The state [TorNode] had published when the native stop ran. */
        @Volatile var stateAtStop: TorInfo? = null
        var node: TorNode? = null

        override fun start(stateDir: String, cacheDir: String): String? {
            calls += "start $stateDir $cacheDir"
            return startError
        }
        override fun stop() {
            stateAtStop = node?.state?.value
            calls += "stop"
        }
        override fun statusJson() = status
        override fun version(): String {
            if (libraryMissing) throw UnsatisfiedLinkError("no freedom_tor_version")
            return "0.46.0"
        }
    }

    private fun node(ops: FakeOps) =
        TorNode(File("/data/tor"), ops, pollBootstrapMs = 10, pollRunningMs = 10).also { ops.node = it }

    private fun awaitState(node: TorNode, what: String, pred: (TorInfo) -> Boolean): TorInfo {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val s = node.state.value
            if (pred(s)) return s
            Thread.sleep(5)
        }
        throw AssertionError("timed out waiting for $what; last ${node.state.value}")
    }

    @Test
    fun `start publishes the bootstrap, then running with its port`() {
        val ops = FakeOps()
        val node = node(ops)
        node.start()
        val starting = awaitState(node, "starting") { it.socksPort != 0 }
        assertEquals(TorStatus.Starting, starting.status)
        assertEquals(40123, starting.socksPort)
        assertEquals(25, starting.progress)
        assertEquals("0.46.0", starting.version)
        assertEquals("start /data/tor/state /data/tor/cache", ops.calls.first())
        ops.status = """{"state":"running","port":40123,"progress":1,"summary":"100%: done","blocked":null,"error":null}"""
        val running = awaitState(node, "running") { it.status == TorStatus.Running }
        assertEquals(100, running.progress)
        assertNull(running.errorMessage)
        runBlocking { node.shutdown() }
    }

    @Test
    fun `stop drops the port before the native stop, and a late poll can't bring it back`() {
        val ops = FakeOps()
        val node = node(ops)
        node.start()
        awaitState(node, "port") { it.socksPort != 0 }
        node.stop()
        awaitState(node, "stopped") { "stop" in ops.calls }
        assertEquals(0, ops.stateAtStop!!.socksPort)
        assertEquals(TorStatus.Stopped, ops.stateAtStop!!.status)
        Thread.sleep(50) // several poll intervals
        assertEquals(TorStatus.Stopped, node.state.value.status)
        assertEquals(0, node.state.value.socksPort)
        // And it starts again.
        node.start()
        awaitState(node, "restarted") { it.socksPort != 0 }
        runBlocking { node.shutdown() }
        assertEquals(0, node.state.value.socksPort)
    }

    @Test
    fun `a failed start reports the error, with no port`() {
        val ops = FakeOps().apply { startError = "bind 127.0.0.1:0: denied" }
        val node = node(ops)
        node.start()
        val s = awaitState(node, "error") { it.status == TorStatus.Error }
        assertEquals("bind 127.0.0.1:0: denied", s.errorMessage)
        assertEquals(0, s.socksPort)
        runBlocking { node.shutdown() }
    }

    @Test
    fun `a library built without Tor is an error, not a crash`() {
        val ops = FakeOps().apply { libraryMissing = true }
        val node = node(ops)
        node.start()
        val s = awaitState(node, "error") { it.status == TorStatus.Error }
        assertEquals(TorNode.UNAVAILABLE.text, s.errorMessage)
        assertTrue(ops.calls.isEmpty())
        runBlocking { node.shutdown() }
    }

    @Test
    fun `status parsing never yields a port it shouldn't`() {
        fun parse(json: String?) = TorNode.parseStatus(json, "0.46.0")
        assertEquals(TorInfo(version = "0.46.0"), parse("""{"state":"stopped"}"""))
        assertEquals(0, parse(null).socksPort)
        assertEquals(TorStatus.Error, parse("garbage").status)
        assertEquals(0, parse("""{"state":"weird","port":9050}""").socksPort)
        // Listening states need a real port.
        assertEquals(TorStatus.Error, parse("""{"state":"running","port":0}""").status)
        assertEquals(0, parse("""{"state":"running","port":70000}""").socksPort)
        // A stuck bootstrap says why; running clears it.
        val blocked = parse("""{"state":"bootstrapping","port":1234,"progress":0.1,"summary":"s","blocked":"can't reach guards","error":null}""")
        assertEquals("can't reach guards", blocked.errorMessage)
        assertEquals(10, blocked.progress)
        val ok = parse("""{"state":"running","port":1234,"progress":1,"summary":"s","blocked":null,"error":"old"}""")
        assertNull(ok.errorMessage)
    }
}
