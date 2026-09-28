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
        override fun createWithCheckpoint(network: String, dataDir: String, root: String, slot: Long): Long {
            calls += "createWithCheckpoint $network $slot"
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
        override fun ethCall(handle: Long, to: String, data: String, block: String): String {
            calls += "ethCall $handle $to $data $block"
            return """{"status":"ok","resultHex":"0x01","blockNumber":7,"verified":false}"""
        }
    }

    /** A checkpoint source that never answers: these tests are about parking, not recovery. */
    private val silent = MyotisNode.CheckpointSource { _, _ -> kotlinx.coroutines.awaitCancellation() }

    private fun node(engine: FakeEngine) =
        MyotisNode(tmp.root, engine, pollIntervalMs = 60_000L, checkpoints = silent)

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
    fun `a chain on a stale anchor is paused and stays paused through foreground and polls`() {
        val engine = FakeEngine()
        engine.status[1L] = """{"running":true,"beaconState":"SYNCED"}"""
        engine.status[2L] = """{"running":true,"beaconState":"STALE_ANCHOR","snapPeers":2,"currentPeriod":3692,"targetPeriod":3701,"wsBoundPeriods":3}"""
        val node = node(engine)
        node.start()
        idle(node)
        assertEquals(1, engine.calls.count { it == "pause 2" })
        assertFalse(engine.calls.contains("pause 1"))

        // Once paused the engine reports paused; the row keeps the parked state.
        engine.status[2L] = """{"running":false,"paused":true,"beaconState":"STALE_ANCHOR"}"""
        engine.calls.clear()
        node.pollNow()
        node.enterBackground()
        node.enterForeground()
        node.pollNow()
        idle(node)
        assertEquals(listOf("pause 1", "resume 1"), engine.calls.filter { it.startsWith("pause") || it.startsWith("resume") })
        val gnosis = node.state.value.chain(MyotisNetwork.Gnosis)!!
        assertTrue(gnosis.staleAnchor)
        assertFalse(gnosis.paused)
        assertEquals(9L, gnosis.targetPeriod - gnosis.currentPeriod)

        // Stop → start releases the park: a fresh engine is judged afresh.
        engine.status[2L] = """{"running":true,"beaconState":"SYNCING"}"""
        node.stop()
        node.start()
        idle(node)
        assertEquals("SYNCING", node.state.value.chain(MyotisNetwork.Gnosis)?.beaconState)
    }

    @Test
    fun `a stale anchor seen in the background is not parked until the app is in front`() {
        val engine = FakeEngine()
        engine.status[2L] = """{"running":false,"paused":true,"beaconState":"STALE_ANCHOR"}"""
        val node = node(engine)
        node.enterBackground()
        node.start()
        idle(node)
        engine.calls.clear()
        engine.status[2L] = """{"running":true,"beaconState":"STALE_ANCHOR"}"""
        node.enterForeground()
        idle(node)
        assertEquals(listOf("resume 1", "resume 2", "pause 2"), engine.calls.filter { it.startsWith("pause") || it.startsWith("resume") })
    }

    private class Clocks(var wall: Long = 1_000_000_000L, var up: Long = 1_000L)

    private fun node(engine: FakeEngine, clocks: Clocks) =
        MyotisNode(
            tmp.root, engine, pollIntervalMs = 60_000L,
            wallClock = { clocks.wall }, upClock = { clocks.up }, checkpoints = silent,
        )

    private fun FakeEngine.pausesAndResumes() = calls.filter { it.startsWith("pause") || it.startsWith("resume") }

    @Test
    fun `a park taken on a wrong wall clock is released once the clock is corrected`() {
        val engine = FakeEngine()
        val clocks = Clocks(wall = 1_000_000_000L + 40L * 86_400_000L) // 40 days ahead
        engine.status[1L] = """{"running":true,"beaconState":"STALE_ANCHOR","currentPeriod":1500,"targetPeriod":1535,"wsBoundPeriods":13}"""
        val node = node(engine, clocks)
        node.start()
        idle(node)
        assertEquals(listOf("pause 1"), engine.pausesAndResumes())

        // Time passes normally: no reason to re-judge, it stays parked.
        engine.status[1L] = """{"running":false,"paused":true,"beaconState":"STALE_ANCHOR"}"""
        clocks.wall += 30_000L
        clocks.up += 30_000L
        node.pollNow()
        idle(node)
        assertEquals(listOf("pause 1"), engine.pausesAndResumes())

        // NTP puts the clock back: the chain is resumed and judged afresh.
        // Straight after the resume the engine may still say STALE_ANCHOR
        // for a moment: that doesn't park it again inside the grace.
        engine.status[1L] = """{"running":true,"beaconState":"STALE_ANCHOR"}"""
        clocks.wall = 1_000_000_000L + 33_000L
        clocks.up += 3_000L
        engine.calls.clear()
        node.pollNow()
        idle(node)
        assertEquals(listOf("resume 1"), engine.pausesAndResumes())
        assertTrue(node.state.value.chain(MyotisNetwork.Mainnet)!!.staleAnchor)

        clocks.up += 3_000L
        clocks.wall += 3_000L
        node.pollNow()
        idle(node)
        assertEquals(listOf("resume 1"), engine.pausesAndResumes())

        engine.status[1L] = """{"running":true,"beaconState":"SYNCED"}"""
        clocks.up += 3_000L
        clocks.wall += 3_000L
        node.pollNow()
        idle(node)
        assertEquals("SYNCED", node.state.value.chain(MyotisNetwork.Mainnet)?.beaconState)
        assertFalse(node.state.value.chain(MyotisNetwork.Mainnet)!!.staleAnchor)
    }

    /**
     * A recovery blocked on an acquisition failure — including evidence that
     * failed verification ([MyotisRecoveryReason.Mismatch]) — is dropped once
     * a corrected clock lets the engine accept its own anchor.
     */
    private fun assertAcquisitionBlockDropped(error: MyotisCheckpointError, reason: MyotisRecoveryReason) {
        val engine = FakeEngine()
        val clocks = Clocks(wall = 1_000_000_000L + 40L * 86_400_000L) // 40 days ahead
        engine.status[1L] = """{"running":true,"beaconState":"STALE_ANCHOR"}"""
        val failing = MyotisNode.CheckpointSource { _, _ -> throw MyotisCheckpointException(error) }
        val node = MyotisNode(
            tmp.root, engine, pollIntervalMs = 60_000L,
            wallClock = { clocks.wall }, upClock = { clocks.up }, checkpoints = failing,
        )
        node.start()
        idle(node)
        node.pollNow()
        idle(node)
        // The acquisition runs off the node's queue: wait for its failure to land.
        val deadline = System.currentTimeMillis() + 5_000
        while (node.state.value.chain(MyotisNetwork.Mainnet)?.recovery?.phase != MyotisRecovery.Phase.Blocked &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(5)
            idle(node)
        }
        val blocked = node.state.value.chain(MyotisNetwork.Mainnet)!!.recovery
        assertEquals(MyotisRecovery.Phase.Blocked, blocked?.phase)
        assertEquals(reason, blocked?.reason)

        // NTP puts the clock back; the resumed engine accepts its own anchor.
        engine.status[1L] = """{"running":true,"beaconState":"SYNCED"}"""
        clocks.wall = 1_000_000_000L + 33_000L
        clocks.up += 3_000L
        node.pollNow()
        idle(node)
        clocks.up += 3_000L
        clocks.wall += 3_000L
        node.pollNow()
        idle(node)
        val chain = node.state.value.chain(MyotisNetwork.Mainnet)!!
        assertEquals("SYNCED", chain.beaconState)
        assertNull(chain.recovery)
    }

    @Test
    fun `a mismatch block from acquisition is dropped once the engine accepts its own anchor`() =
        assertAcquisitionBlockDropped(MyotisCheckpointError.Mismatch, MyotisRecoveryReason.Mismatch)

    @Test
    fun `a quorum-conflict block is dropped once the engine accepts its own anchor`() =
        assertAcquisitionBlockDropped(MyotisCheckpointError.QuorumConflict, MyotisRecoveryReason.QuorumConflict)

    @Test
    fun `a released chain that is still stale after the grace parks again`() {
        val engine = FakeEngine()
        val clocks = Clocks()
        engine.status[2L] = """{"running":true,"beaconState":"STALE_ANCHOR"}"""
        val node = node(engine, clocks)
        node.start()
        idle(node)
        clocks.wall += 5L * 60_000L // clock set by hand, anchor really is old
        engine.calls.clear()
        node.pollNow()
        idle(node)
        assertEquals(listOf("resume 2"), engine.pausesAndResumes())

        clocks.up += MyotisNode.REJUDGE_GRACE_MS
        clocks.wall += MyotisNode.REJUDGE_GRACE_MS
        node.pollNow()
        idle(node)
        assertEquals(listOf("resume 2", "pause 2"), engine.pausesAndResumes())
        assertTrue(node.state.value.chain(MyotisNetwork.Gnosis)!!.staleAnchor)
    }

    @Test
    fun `a clock corrected while in the background releases the park on foreground`() {
        val engine = FakeEngine()
        val clocks = Clocks()
        engine.status[2L] = """{"running":true,"beaconState":"STALE_ANCHOR"}"""
        val node = node(engine, clocks)
        node.start()
        idle(node)
        node.enterBackground()
        idle(node)
        clocks.wall -= 40L * 86_400_000L
        clocks.up += 60_000L
        engine.calls.clear()
        engine.status[2L] = """{"running":true,"beaconState":"SYNCING"}"""
        node.enterForeground()
        idle(node)
        assertEquals(listOf("resume 1", "resume 2"), engine.pausesAndResumes())
        // Still re-judging: the row keeps its parked state until the engine
        // shows it accepted the anchor.
        assertTrue(node.state.value.chain(MyotisNetwork.Gnosis)!!.staleAnchor)

        engine.status[2L] = """{"running":true,"beaconState":"CATCHING_UP"}"""
        clocks.up += 3_000L
        clocks.wall += 3_000L
        node.pollNow()
        idle(node)
        assertEquals("CATCHING_UP", node.state.value.chain(MyotisNetwork.Gnosis)?.beaconState)
    }

    @Test
    fun `a released chain re-judging its anchor keeps its parked row instead of flashing syncing`() {
        val engine = FakeEngine()
        val clocks = Clocks()
        val parkedJson = """{"running":true,"beaconState":"STALE_ANCHOR","currentPeriod":1500,"targetPeriod":1535,"wsBoundPeriods":13}"""
        engine.status[1L] = """{"running":true,"beaconState":"SYNCED"}"""
        engine.status[2L] = parkedJson
        val node = node(engine, clocks)
        node.start()
        idle(node)
        val parkedRow = node.state.value.chain(MyotisNetwork.Gnosis)!!

        // Clock changed: resumed, and the engine passes through SYNCING
        // before it concludes STALE_ANCHOR again.
        clocks.wall += 5L * 60_000L
        engine.status[2L] = """{"running":true,"beaconState":"SYNCING"}"""
        engine.calls.clear()
        node.pollNow()
        idle(node)
        assertEquals(listOf("resume 2"), engine.pausesAndResumes())
        assertEquals(parkedRow, node.state.value.chain(MyotisNetwork.Gnosis))

        engine.status[2L] = parkedJson
        clocks.up += 3_000L
        clocks.wall += 3_000L
        node.pollNow()
        idle(node)
        assertEquals(parkedRow, node.state.value.chain(MyotisNetwork.Gnosis))

        // Grace over, still stale: parked again, the row never changed.
        clocks.up += MyotisNode.REJUDGE_GRACE_MS
        clocks.wall += MyotisNode.REJUDGE_GRACE_MS
        node.pollNow()
        idle(node)
        assertEquals(listOf("resume 2", "pause 2"), engine.pausesAndResumes())
        assertTrue(node.state.value.chain(MyotisNetwork.Gnosis)!!.staleAnchor)
        assertEquals(1535L, node.state.value.chain(MyotisNetwork.Gnosis)!!.targetPeriod)
    }

    @Test
    fun `a release's grace doesn't run out in the background and restarts on foreground`() {
        val engine = FakeEngine()
        val clocks = Clocks()
        val parkedJson = """{"running":true,"beaconState":"STALE_ANCHOR","currentPeriod":1500,"targetPeriod":1535,"wsBoundPeriods":13}"""
        engine.status[1L] = """{"running":true,"beaconState":"SYNCED"}"""
        engine.status[2L] = parkedJson
        val node = node(engine, clocks)
        node.start()
        idle(node)
        val parkedRow = node.state.value.chain(MyotisNetwork.Gnosis)!!

        // Clock corrected: released, then Home 5 s later.
        clocks.wall -= 40L * 86_400_000L
        engine.status[2L] = """{"running":true,"beaconState":"SYNCING"}"""
        node.pollNow()
        idle(node)
        clocks.up += 5_000L
        clocks.wall += 5_000L
        node.enterBackground()
        idle(node)
        assertEquals(parkedRow, node.state.value.chain(MyotisNetwork.Gnosis))

        // Back well after the grace would have run out on the wall: the
        // resumed engine passes through SYNCING again, and the row keeps
        // its parked state instead of flashing it.
        clocks.up += 19_000L
        clocks.wall += 19_000L
        engine.status[2L] = """{"running":true,"beaconState":"SYNCING"}"""
        engine.calls.clear()
        node.enterForeground()
        idle(node)
        assertEquals(listOf("resume 1", "resume 2"), engine.pausesAndResumes())
        assertEquals(parkedRow, node.state.value.chain(MyotisNetwork.Gnosis))

        engine.status[2L] = parkedJson
        clocks.up += 3_000L
        clocks.wall += 3_000L
        node.pollNow()
        idle(node)
        assertEquals(parkedRow, node.state.value.chain(MyotisNetwork.Gnosis))
        assertEquals(listOf("resume 1", "resume 2"), engine.pausesAndResumes())

        // A full grace of foreground time, still stale: parked again.
        clocks.up += MyotisNode.REJUDGE_GRACE_MS
        clocks.wall += MyotisNode.REJUDGE_GRACE_MS
        node.pollNow()
        idle(node)
        assertEquals(listOf("resume 1", "resume 2", "pause 2"), engine.pausesAndResumes())
        assertEquals(parkedRow, node.state.value.chain(MyotisNetwork.Gnosis))
    }

    @Test
    fun `a failed resume after a release is retried on the next poll without flashing the row`() {
        val engine = FakeEngine()
        val clocks = Clocks()
        val parkedJson = """{"running":true,"beaconState":"STALE_ANCHOR","currentPeriod":1500,"targetPeriod":1535,"wsBoundPeriods":13}"""
        engine.status[1L] = """{"running":true,"beaconState":"SYNCED"}"""
        engine.status[2L] = parkedJson
        val node = node(engine, clocks)
        node.start()
        idle(node)
        val parkedRow = node.state.value.chain(MyotisNetwork.Gnosis)!!

        // Clock corrected, but the warm restart fails: the engine stays paused.
        clocks.wall -= 40L * 86_400_000L
        engine.resumeAnswer = false
        engine.status[2L] = """{"running":false,"paused":true,"beaconState":"STALE_ANCHOR"}"""
        engine.calls.clear()
        node.pollNow()
        idle(node)
        assertEquals(listOf("resume 2"), engine.pausesAndResumes())
        assertEquals(parkedRow, node.state.value.chain(MyotisNetwork.Gnosis))

        // The next poll retries it, well inside the grace, even though the
        // row shows the (unpaused) parked snapshot.
        clocks.up += 3_000L
        clocks.wall += 3_000L
        engine.resumeAnswer = true
        engine.status[2L] = """{"running":true,"beaconState":"SYNCING"}"""
        node.pollNow()
        idle(node)
        assertEquals(listOf("resume 2", "resume 2"), engine.pausesAndResumes())
        assertEquals(parkedRow, node.state.value.chain(MyotisNetwork.Gnosis))

        // Running now, passing through SYNCING: the grace counts from the
        // successful resume, not the release, so the row doesn't flash.
        clocks.up += 12_000L
        clocks.wall += 12_000L
        node.pollNow()
        idle(node)
        assertEquals(listOf("resume 2", "resume 2"), engine.pausesAndResumes())
        assertEquals(parkedRow, node.state.value.chain(MyotisNetwork.Gnosis))

        // Still stale after a full grace of running time: parked again.
        engine.status[2L] = parkedJson
        clocks.up += MyotisNode.REJUDGE_GRACE_MS
        clocks.wall += MyotisNode.REJUDGE_GRACE_MS
        node.pollNow()
        idle(node)
        assertEquals(listOf("resume 2", "resume 2", "pause 2"), engine.pausesAndResumes())
        assertEquals(parkedRow, node.state.value.chain(MyotisNetwork.Gnosis))
    }

    @Test
    fun `a released chain still syncing when the grace runs out shows its real state`() {
        val engine = FakeEngine()
        val clocks = Clocks()
        engine.status[2L] = """{"running":true,"beaconState":"STALE_ANCHOR"}"""
        val node = node(engine, clocks)
        node.start()
        idle(node)
        clocks.wall -= 40L * 86_400_000L
        engine.status[2L] = """{"running":true,"beaconState":"SYNCING"}"""
        node.pollNow()
        idle(node)
        assertTrue(node.state.value.chain(MyotisNetwork.Gnosis)!!.staleAnchor)

        clocks.up += MyotisNode.REJUDGE_GRACE_MS
        clocks.wall += MyotisNode.REJUDGE_GRACE_MS
        node.pollNow()
        idle(node)
        assertEquals("SYNCING", node.state.value.chain(MyotisNetwork.Gnosis)?.beaconState)
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

    private val readyJson =
        """{"running":true,"beaconState":"SYNCED","snapServingPeers":1,"elReaderAvailable":true}"""

    @Test
    fun `a verified read reaches the engine only on a chain the last poll found ready`() {
        val engine = FakeEngine()
        engine.status[1L] = """{"running":true,"beaconState":"CATCHING_UP","elReaderAvailable":true}"""
        val node = node(engine)
        // Before start: nothing to read from.
        assertEquals(MyotisNode.NOT_READY_JSON, node.ethCall(MyotisNetwork.Mainnet, "0xaa", "0x01"))
        node.start()
        idle(node)
        // Catching up isn't ready.
        assertEquals(MyotisNode.NOT_READY_JSON, node.ethCall(MyotisNetwork.Mainnet, "0xaa", "0x01"))

        engine.status[1L] = readyJson
        node.pollNow()
        idle(node)
        assertTrue(node.ethCall(MyotisNetwork.Mainnet, "0xaa", "0x01").contains("\"ok\""))
        assertTrue("ethCall 1 0xaa 0x01 latest" in engine.calls)
        // Gnosis (handle 2) never reported ready.
        assertEquals(MyotisNode.NOT_READY_JSON, node.ethCall(MyotisNetwork.Gnosis, "0xaa", "0x01"))
    }

    @Test
    fun `background and stop close the read gate before the engines pause or stop`() {
        val engine = FakeEngine()
        engine.status[1L] = readyJson
        val node = node(engine)
        node.start()
        idle(node)
        assertTrue(node.ethCall(MyotisNetwork.Mainnet, "0xaa", "0x01").contains("\"ok\""))

        node.enterBackground()
        idle(node)
        assertEquals(MyotisNode.NOT_READY_JSON, node.ethCall(MyotisNetwork.Mainnet, "0xaa", "0x01"))

        node.enterForeground()
        idle(node)
        assertTrue(node.ethCall(MyotisNetwork.Mainnet, "0xaa", "0x01").contains("\"ok\""))

        node.stop()
        idle(node)
        assertEquals(MyotisNode.NOT_READY_JSON, node.ethCall(MyotisNetwork.Mainnet, "0xaa", "0x01"))
        assertEquals(2, engine.calls.count { it.startsWith("ethCall") })
    }
}
