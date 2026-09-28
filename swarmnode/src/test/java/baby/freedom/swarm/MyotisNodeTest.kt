package baby.freedom.swarm

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Collections

class MyotisNodeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Records every engine call in order; each network's create answer is scripted. */
    private class FakeEngine(
        var abi: Int = 32,
        val createAnswers: MutableMap<String, Long> = mutableMapOf("mainnet" to 1L, "gnosis" to 2L),
        val startAnswers: MutableMap<Long, Boolean> = mutableMapOf(),
    ) : MyotisNode.Engine {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val status = mutableMapOf<Long, String>()
        var resumeAnswer = true

        override fun init(): Int = abi.also { calls += "init" }
        override val expectedAbi: Int = 32
        override fun create(network: String, dataDir: String): Long {
            calls += "create $network"
            return createAnswers.getValue(network)
        }
        override fun start(handle: Long): Boolean {
            calls += "start $handle"
            return startAnswers[handle] ?: true
        }
        override fun stop(handle: Long) {
            calls += "stop $handle"
        }
        override fun pause(handle: Long): Boolean {
            calls += "pause $handle"
            return true
        }
        override fun resume(handle: Long): Boolean {
            calls += "resume $handle"
            return resumeAnswer
        }
        override fun setServedBlockWindow(handle: Long, blocks: Int): Boolean {
            calls += "window $handle $blocks"
            return true
        }
        override fun statusJson(handle: Long): String = status[handle] ?: "{}"
        override fun drainLogs(max: Int): String = ""
    }

    private fun node(engine: FakeEngine) =
        MyotisNode(tmp.root, engine, pollIntervalMs = 60_000L)

    private fun idle(node: MyotisNode) = runBlocking { withTimeout(5_000) { node.awaitIdle() } }

    @Test
    fun `start creates and starts one engine per chain with the minimum served window`() {
        val engine = FakeEngine()
        engine.status[1L] = """{"running":true,"beaconState":"SYNCING","peerCount":3}"""
        val node = node(engine)
        node.start()
        idle(node)

        assertEquals(
            listOf("init", "create mainnet", "start 1", "window 1 1", "create gnosis", "start 2", "window 2 1"),
            engine.calls,
        )
        assertTrue(tmp.root.resolve("mainnet").isDirectory)
        assertTrue(tmp.root.resolve("gnosis").isDirectory)
        val info = node.state.value
        assertEquals(MyotisStatus.Running, info.status)
        assertEquals(listOf(1L, 100L), info.chains.map { it.chainId })
        assertEquals(3, info.chain(MyotisNetwork.Mainnet)?.peerCount)
        assertEquals("SYNCING", info.chain(MyotisNetwork.Mainnet)?.beaconState)
    }

    @Test
    fun `a second start while running is a no-op`() {
        val engine = FakeEngine()
        val node = node(engine)
        node.start()
        node.start()
        idle(node)
        assertEquals(1, engine.calls.count { it == "create mainnet" })
    }

    @Test
    fun `an ABI mismatch refuses to create any engine`() {
        val engine = FakeEngine(abi = 31)
        val node = node(engine)
        node.start()
        idle(node)
        assertEquals(listOf("init"), engine.calls)
        val info = node.state.value
        assertEquals(MyotisStatus.Error, info.status)
        assertTrue(info.errorMessage!!.contains("31"))
    }

    @Test
    fun `one chain failing to create leaves the other running with the error on the failed chain`() {
        val engine = FakeEngine(createAnswers = mutableMapOf("mainnet" to 1L, "gnosis" to -2L))
        val node = node(engine)
        node.start()
        idle(node)
        val info = node.state.value
        assertEquals(MyotisStatus.Running, info.status)
        assertNull(info.chain(MyotisNetwork.Mainnet)?.error)
        assertEquals("Network not supported by this engine", info.chain(MyotisNetwork.Gnosis)?.error)
    }

    @Test
    fun `a refused start is stopped again and every chain failing is an error`() {
        val engine = FakeEngine(startAnswers = mutableMapOf(1L to false, 2L to false))
        val node = node(engine)
        node.start()
        idle(node)
        assertTrue(engine.calls.containsAll(listOf("stop 1", "stop 2")))
        assertEquals(MyotisStatus.Error, node.state.value.status)
        assertEquals("Engine didn't start", node.state.value.errorMessage)
        assertEquals(2, node.state.value.chains.size)
    }

    @Test
    fun `stop right after start runs after it and stops every engine`() {
        val engine = FakeEngine()
        val node = node(engine)
        node.start()
        node.stop()
        idle(node)
        assertEquals(listOf("stop 1", "stop 2"), engine.calls.filter { it.startsWith("stop") })
        assertTrue(engine.calls.indexOf("stop 1") > engine.calls.indexOf("start 2"))
        assertEquals(MyotisInfo(), node.state.value)
    }

    @Test
    fun `start after stop boots fresh engines`() {
        val engine = FakeEngine()
        val node = node(engine)
        node.start()
        node.stop()
        node.start()
        idle(node)
        assertEquals(2, engine.calls.count { it == "create mainnet" })
        assertEquals(MyotisStatus.Running, node.state.value.status)
    }

    @Test
    fun `background pauses and foreground resumes every engine`() {
        val engine = FakeEngine()
        val node = node(engine)
        node.start()
        node.enterBackground()
        node.enterForeground()
        idle(node)
        val lifecycle = engine.calls.filter { it.startsWith("pause") || it.startsWith("resume") }
        assertEquals(listOf("pause 1", "pause 2", "resume 1", "resume 2"), lifecycle)
    }

    @Test
    fun `a start while in the background comes up paused`() {
        val engine = FakeEngine()
        val node = node(engine)
        node.enterBackground()
        node.start()
        idle(node)
        assertTrue(engine.calls.containsAll(listOf("pause 1", "pause 2")))
        assertTrue(engine.calls.indexOf("pause 1") > engine.calls.indexOf("start 1"))
    }

    @Test
    fun `a chain still paused in the foreground is resumed again on the next poll`() {
        val engine = FakeEngine()
        engine.resumeAnswer = false
        engine.status[1L] = """{"running":false,"paused":true,"beaconState":"SYNCED"}"""
        engine.status[2L] = """{"running":true,"paused":false,"beaconState":"SYNCED"}"""
        val node = node(engine)
        node.start()
        node.enterBackground()
        node.enterForeground()
        idle(node)
        engine.calls.clear()
        node.pollNow()
        idle(node)
        assertEquals(listOf("resume 1"), engine.calls)
    }

    @Test
    fun `polls in the background don't retry a resume`() {
        val engine = FakeEngine()
        engine.status[1L] = """{"running":false,"paused":true}"""
        val node = node(engine)
        node.start()
        node.enterBackground()
        idle(node)
        engine.calls.clear()
        node.pollNow()
        idle(node)
        assertFalse(engine.calls.any { it.startsWith("resume") })
    }

    @Test
    fun `shutdown stops the engines and returns`() {
        val engine = FakeEngine()
        val node = node(engine)
        node.start()
        runBlocking { withTimeout(5_000) { node.shutdown() } }
        assertEquals(listOf("stop 1", "stop 2"), engine.calls.filter { it.startsWith("stop") })
        // A second shutdown (queue already closed) must not hang.
        runBlocking { withTimeout(5_000) { node.shutdown() } }
    }
}
