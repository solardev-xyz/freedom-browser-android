package baby.freedom.swarm

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections

/** [MyotisNode]'s stale-anchor checkpoint recovery (#195), over a fake engine and a scripted quorum. */
class MyotisRecoveryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeEngine : MyotisNode.Engine {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val status = mutableMapOf<Long, String>()
        val dirs = mutableMapOf<Long, String>()
        var nextHandle = mutableMapOf("mainnet" to 1L, "gnosis" to 2L)
        var createAnswer: Long? = null

        override fun init(): Int = 32
        override val expectedAbi: Int = 32
        private fun handle(network: String, dataDir: String): Long {
            createAnswer?.let { return it }
            val h = nextHandle.getValue(network)
            nextHandle[network] = h + 10
            dirs[h] = dataDir
            return h
        }
        override fun create(network: String, dataDir: String): Long {
            calls += "create $network"
            return handle(network, dataDir)
        }
        override fun createWithCheckpoint(network: String, dataDir: String, root: String, slot: Long): Long {
            calls += "createWithCheckpoint $network $root $slot"
            return handle(network, dataDir)
        }
        override fun start(handle: Long): Boolean = true.also { calls += "start $handle" }
        override fun stop(handle: Long) {
            calls += "stop $handle"
        }
        override fun pause(handle: Long): Boolean = true.also { calls += "pause $handle" }
        override fun resume(handle: Long): Boolean = true.also { calls += "resume $handle" }
        override fun setServedBlockWindow(handle: Long, blocks: Int) = true
        override fun statusJson(handle: Long): String = status[handle] ?: "{}"
        override fun drainLogs(max: Int): String = ""
    }

    /** Each acquisition waits for the next scripted answer. */
    private class Checkpoints : MyotisNode.CheckpointSource {
        val asked: MutableList<MyotisNetwork> = Collections.synchronizedList(mutableListOf())
        val answers = Channel<Result<MyotisCheckpointRecord>>(Channel.UNLIMITED)
        override suspend fun acquire(network: MyotisNetwork, onDiagnostic: (String) -> Unit): MyotisCheckpointRecord {
            asked += network
            return answers.receive().getOrThrow()
        }
        fun succeed(record: MyotisCheckpointRecord) = answers.trySend(Result.success(record))
        fun fail(error: MyotisCheckpointError) = answers.trySend(Result.failure(MyotisCheckpointException(error)))
    }

    private val slot = 1_895_077L * 16
    private val root = "0x3f437998c4c0f8a4aa2d7175ab08fd49faf4e6814c76e5b36cfe9a7756122c3d"
    private val rootHex = root.removePrefix("0x")
    private val record = MyotisCheckpointRecord(
        100L, "gnosis", root, slot, MyotisCheckpointNetwork.Gnosis.slotTimeMs(slot) + 60_000L,
        listOf("https://checkpoint.gnosischain.com", "https://checkpoint-sync-gnosis.dappnode.net"), 1_895_077L,
    )

    private class Clocks(var wall: Long, var up: Long = 1_000L) {
        fun advance(ms: Long) {
            wall += ms
            up += ms
        }
    }

    private val clocks = Clocks(wall = record.verifiedAt)
    private val engine = FakeEngine()
    private val checkpoints = Checkpoints()
    private val node by lazy {
        MyotisNode(
            tmp.root, engine, pollIntervalMs = 60_000L,
            wallClock = { clocks.wall }, upClock = { clocks.up }, checkpoints = checkpoints,
        )
    }

    private val stale = """{"running":true,"beaconState":"STALE_ANCHOR","currentPeriod":3692,"targetPeriod":3701,"wsBoundPeriods":3}"""
    private val parkedJson = """{"running":false,"paused":true,"beaconState":"STALE_ANCHOR"}"""
    private fun synced(slot: Long, rootHex: String) =
        """{"running":true,"beaconState":"SYNCED","snapServingPeers":2,"elReaderAvailable":true,"finalizedSlot":$slot,"finalizedRootHex":"$rootHex"}"""

    private fun idle() = runBlocking { withTimeout(5_000) { node.awaitIdle() } }

    /** Wait for [condition], letting the node's queue run between checks. */
    private fun eventually(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            Thread.sleep(10)
            idle()
        }
        idle()
    }

    private fun poll() {
        node.pollNow()
        idle()
    }

    private val gnosisRow get() = node.state.value.chain(MyotisNetwork.Gnosis)!!
    private val recovery get() = gnosisRow.recovery

    /** Mainnet synced, Gnosis parked on a stale anchor with its recovery asking the quorum. */
    private fun startStale() {
        engine.status[1L] = synced(15_000_000L, "11".repeat(32))
        engine.status[2L] = stale
        node.start()
        idle()
        engine.status[2L] = parkedJson
        eventually { checkpoints.asked.size == 1 }
    }

    /** [startStale], then the quorum agrees on [record] and Gnosis relaunches on it (handle 12). */
    private fun recoverOnce() {
        startStale()
        engine.status[12L] = """{"running":true,"beaconState":"SYNCING"}"""
        checkpoints.succeed(record)
        eventually { "start 12" in engine.calls }
    }

    @Test
    fun `a stale chain asks the quorum, bootstraps a verified generation and is ready only once synced from it`() {
        startStale()
        assertEquals(listOf(MyotisNetwork.Gnosis), checkpoints.asked)
        assertTrue("pause 2" in engine.calls)
        assertEquals(MyotisRecovery(MyotisRecovery.Phase.Checking, attempt = 1), recovery)
        assertTrue(gnosisRow.staleAnchor)
        assertFalse(gnosisRow.ready)
        assertNull(node.state.value.chain(MyotisNetwork.Mainnet)!!.recovery)

        engine.status[12L] = """{"running":true,"beaconState":"SYNCING"}"""
        checkpoints.succeed(record)
        eventually { "start 12" in engine.calls }
        val order = engine.calls.dropWhile { it != "stop 2" }
        assertEquals(listOf("stop 2", "createWithCheckpoint gnosis $root $slot", "start 12"), order.take(3))
        val dir = File(engine.dirs.getValue(12L))
        assertEquals(File(tmp.root, "gnosis/verified-sync"), dir.parentFile)
        assertEquals(MyotisRecovery.Phase.Restarting, recovery?.phase)
        assertEquals("SYNCING", gnosisRow.beaconState)

        // Synced, but not yet past the anchor with a finalized root: still recovering.
        engine.status[12L] = synced(slot - 16, rootHex)
        clocks.advance(3_000)
        poll()
        assertEquals(MyotisRecovery.Phase.Restarting, recovery?.phase)
        assertFalse(gnosisRow.ready)

        engine.status[12L] = synced(slot, rootHex)
        clocks.advance(3_000)
        poll()
        assertNull(recovery)
        assertTrue(gnosisRow.ready)
        assertEquals(1, checkpoints.asked.size)
    }

    @Test
    fun `a verified generation resumes from its checkpoint on the next start`() {
        recoverOnce()
        node.stop()
        idle()
        engine.calls.clear()
        node.start()
        idle()
        assertTrue("createWithCheckpoint gnosis $root $slot" in engine.calls)
        assertFalse("create gnosis" in engine.calls)
        assertTrue("create mainnet" in engine.calls)
    }

    @Test
    fun `a transient failure waits on the retry ladder and retries once the wait is over`() {
        startStale()
        checkpoints.fail(MyotisCheckpointError.QuorumUnavailable)
        eventually { recovery?.phase == MyotisRecovery.Phase.Waiting }
        assertEquals(MyotisRecoveryReason.QuorumUnavailable, recovery?.reason)
        assertEquals(clocks.up + 15_000L, recovery?.nextRetryAt)
        assertTrue(recovery!!.canRetry)
        assertEquals("Updating checkpoint", recovery!!.label)
        assertTrue(recovery!!.message(clocks.up).endsWith("Trying again in 15s."))

        clocks.advance(14_000)
        poll()
        assertEquals(1, checkpoints.asked.size)
        clocks.advance(1_000)
        poll()
        eventually { checkpoints.asked.size == 2 }
        assertEquals(MyotisRecovery(MyotisRecovery.Phase.Checking, attempt = 2), recovery)

        // The second failure waits a minute, later ones five.
        checkpoints.fail(MyotisCheckpointError.Unavailable)
        eventually { recovery?.phase == MyotisRecovery.Phase.Waiting }
        assertEquals(clocks.up + 60_000L, recovery?.nextRetryAt)
    }

    @Test
    fun `no retry runs in the background, an overdue one runs on return`() {
        startStale()
        checkpoints.fail(MyotisCheckpointError.Unavailable)
        eventually { recovery?.phase == MyotisRecovery.Phase.Waiting }
        node.enterBackground()
        idle()
        clocks.advance(60_000)
        poll()
        assertEquals(1, checkpoints.asked.size)
        node.enterForeground()
        eventually { checkpoints.asked.size == 2 }
    }

    @Test
    fun `checkpoint evidence failing verification is not told as the chain disagreeing`() {
        startStale()
        checkpoints.fail(MyotisCheckpointError.Mismatch)
        eventually { recovery?.phase == MyotisRecovery.Phase.Blocked }
        assertEquals(MyotisRecoveryReason.Mismatch, recovery?.reason)
        assertEquals("The checkpoint evidence didn't pass verification.", recovery!!.message(0))
        node.retryRecovery(MyotisNetwork.Gnosis)
        eventually { checkpoints.asked.size == 2 }
        // Still a stale-anchor recovery: the checking line doesn't claim a chain contradiction.
        assertEquals(MyotisRecovery(MyotisRecovery.Phase.Checking, attempt = 1), recovery)
        assertTrue(recovery!!.message(0).startsWith("This chain's checkpoint is too old"))
    }

    @Test
    fun `a terminal failure blocks until the user retries`() {
        startStale()
        checkpoints.fail(MyotisCheckpointError.QuorumConflict)
        eventually { recovery?.phase == MyotisRecovery.Phase.Blocked }
        assertEquals(MyotisRecoveryReason.QuorumConflict, recovery?.reason)
        assertEquals("Sync paused", myotisLabel())
        assertTrue(recovery!!.canRetry)
        assertFalse(recovery!!.canRepair)
        clocks.advance(3_600_000)
        poll()
        assertEquals(1, checkpoints.asked.size)

        node.retryRecovery(MyotisNetwork.Gnosis)
        eventually { checkpoints.asked.size == 2 }
        assertEquals(MyotisRecovery(MyotisRecovery.Phase.Checking, attempt = 1), recovery)
    }

    private fun myotisLabel() = recovery!!.label

    @Test
    fun `retry while waiting runs at once and restarts the ladder`() {
        startStale()
        checkpoints.fail(MyotisCheckpointError.Unavailable)
        eventually { recovery?.phase == MyotisRecovery.Phase.Waiting }
        node.retryRecovery(MyotisNetwork.Gnosis)
        eventually { checkpoints.asked.size == 2 }
        assertEquals(1, recovery?.attempt)
        // A second Retry while one is in flight doesn't start another.
        node.retryRecovery(MyotisNetwork.Gnosis)
        idle()
        Thread.sleep(50)
        assertEquals(2, checkpoints.asked.size)
    }

    @Test
    fun `the engine contradicting the agreed root blocks recovery as a mismatch`() {
        recoverOnce()
        engine.status[12L] = synced(slot, "ab".repeat(32))
        clocks.advance(3_000)
        poll()
        assertEquals(MyotisRecovery.Phase.Blocked, recovery?.phase)
        assertEquals(MyotisRecoveryReason.AnchorMismatch, recovery?.reason)
        assertEquals("The synced chain didn't match the agreed checkpoint.", recovery!!.message(0))
        assertFalse(gnosisRow.ready)
        // Retry fetches a fresh checkpoint rather than trusting this one.
        node.retryRecovery(MyotisNetwork.Gnosis)
        eventually { checkpoints.asked.size == 2 }
        clocks.advance(3_000)
        poll()
        assertEquals(MyotisRecovery.Phase.Checking, recovery?.phase)
    }

    @Test
    fun `a restart that never finishes stalls after five minutes in front, and retry restarts it`() {
        recoverOnce()
        clocks.advance(MyotisRecoveryPolicy.STALL_MS - 1_000)
        poll()
        assertEquals(MyotisRecovery.Phase.Restarting, recovery?.phase)
        clocks.advance(1_000)
        poll()
        assertEquals(MyotisRecoveryReason.Stalled, recovery?.reason)
        assertEquals("Syncing slowly", recovery?.label)

        engine.calls.clear()
        node.retryRecovery(MyotisNetwork.Gnosis)
        idle()
        assertEquals(listOf("stop 12", "createWithCheckpoint gnosis $root $slot", "start 22"), engine.calls.take(3))
        assertEquals(MyotisRecovery(MyotisRecovery.Phase.Restarting, MyotisRecovery.Mode.Restart), recovery)
        engine.status[22L] = synced(slot + 16, "22".repeat(32))
        poll()
        assertNull(recovery)
    }

    @Test
    fun `a stalled restart that then syncs on a contradicting root blocks as a mismatch, not ready`() {
        recoverOnce()
        clocks.advance(MyotisRecoveryPolicy.STALL_MS)
        poll()
        assertEquals(MyotisRecoveryReason.Stalled, recovery?.reason)

        // The engine keeps running and syncs at the checkpoint slot on a different root.
        engine.status[12L] = synced(slot, "ab".repeat(32))
        poll()
        assertEquals(MyotisRecovery.Phase.Blocked, recovery?.phase)
        assertEquals(MyotisRecoveryReason.AnchorMismatch, recovery?.reason)
        assertFalse(gnosisRow.ready)

        // Once finality moves past the slot the check can't see it any more: still blocked, and recorded.
        engine.status[12L] = synced(slot + 16, "cd".repeat(32))
        poll()
        assertEquals(MyotisRecoveryReason.AnchorMismatch, recovery?.reason)
        assertFalse(gnosisRow.ready)
        node.stop()
        idle()
        engine.calls.clear()
        node.start()
        idle()
        assertFalse(engine.calls.any { it.startsWith("createWithCheckpoint gnosis") })
        assertEquals(MyotisRecoveryReason.AnchorMismatch, recovery?.reason)
    }

    @Test
    fun `a checkpoint acquired while backgrounded relaunches paused`() {
        startStale()
        node.enterBackground()
        idle()
        engine.status[12L] = """{"running":false,"paused":true,"beaconState":"STARTING"}"""
        checkpoints.succeed(record)
        eventually { "start 12" in engine.calls }
        assertTrue(engine.calls.indexOf("pause 12") > engine.calls.indexOf("start 12"))
    }

    @Test
    fun `stopping drops a recovery in flight`() {
        startStale()
        node.stop()
        idle()
        checkpoints.succeed(record)
        Thread.sleep(100)
        idle()
        assertFalse(engine.calls.any { it.startsWith("createWithCheckpoint") })
        assertEquals(MyotisInfo(), node.state.value)
    }

    @Test
    fun `sync data that doesn't match its record blocks at start, and Repair starts a fresh generation`() {
        val pointer = File(tmp.root, "gnosis/verified-sync.json")
        pointer.parentFile!!.mkdirs()
        pointer.writeText("{garbage")
        engine.status[1L] = synced(15_000_000L, "11".repeat(32))
        node.start()
        idle()
        assertFalse("create gnosis" in engine.calls)
        assertEquals(MyotisStatus.Running, node.state.value.status)
        assertEquals(MyotisRecovery(MyotisRecovery.Phase.Blocked, reason = MyotisRecoveryReason.Storage), recovery)
        assertTrue(recovery!!.canRepair)
        assertTrue(recovery!!.message(0).contains("Repair"))

        engine.status[2L] = """{"running":true,"beaconState":"SYNCING"}"""
        node.repairSyncData(MyotisNetwork.Gnosis)
        idle()
        assertTrue("create gnosis" in engine.calls)
        assertEquals(File(tmp.root, "gnosis/verified-sync"), File(engine.dirs.getValue(2L)).parentFile)
        assertEquals(MyotisRecovery(MyotisRecovery.Phase.Restarting, MyotisRecovery.Mode.Restart), recovery)
        assertTrue(File(tmp.root, "gnosis").listFiles()!!.any { it.name.startsWith("verified-sync-backup-") })

        engine.status[2L] = synced(slot, rootHex)
        poll()
        assertNull(recovery)
    }

    @Test
    fun `an engine refusing the generation's anchor at start blocks as storage`() {
        engine.createAnswer = MyotisNative.ANCHOR_MISMATCH
        node.start()
        idle()
        assertEquals(MyotisStatus.Running, node.state.value.status)
        assertEquals(MyotisRecoveryReason.Storage, recovery?.reason)
        assertEquals(MyotisRecoveryReason.Storage, node.state.value.chain(MyotisNetwork.Mainnet)?.recovery?.reason)
    }

    @Test
    fun `a failed relaunch keeps a row and blocks with a startup retry`() {
        startStale()
        engine.createAnswer = MyotisNative.CREATE_FAILED
        checkpoints.succeed(record)
        eventually { recovery?.phase == MyotisRecovery.Phase.Blocked }
        assertEquals(MyotisRecoveryReason.Startup, recovery?.reason)
        assertFalse(gnosisRow.running)

        engine.createAnswer = null
        engine.status[12L] = """{"running":true,"beaconState":"SYNCING"}"""
        node.retryRecovery(MyotisNetwork.Gnosis)
        idle()
        // Restarts the generation it had just minted, from its checkpoint.
        assertTrue(engine.calls.last { it.startsWith("create") } == "createWithCheckpoint gnosis $root $slot")
        assertEquals(MyotisRecovery.Phase.Restarting, recovery?.phase)
    }

    @Test
    fun `an agreed checkpoint the engine then judges stale goes back on the ladder`() {
        recoverOnce()
        engine.status[12L] = stale
        clocks.advance(3_000)
        poll()
        assertEquals(MyotisRecovery.Phase.Waiting, recovery?.phase)
        assertEquals(MyotisRecoveryReason.Stale, recovery?.reason)
        clocks.advance(15_000)
        poll()
        eventually { checkpoints.asked.size == 2 }
    }

    @Test
    fun `an engine that accepts its own anchor after a clock fix drops the recovery in flight`() {
        startStale()
        // The wall clock is corrected: the park is released to re-judge.
        clocks.wall -= 40L * 86_400_000L
        engine.status[2L] = """{"running":true,"beaconState":"SYNCING"}"""
        poll()
        assertTrue("resume 2" in engine.calls)
        assertEquals(MyotisRecovery.Phase.Checking, recovery?.phase)
        engine.status[2L] = """{"running":true,"beaconState":"CATCHING_UP"}"""
        clocks.advance(3_000)
        poll()
        assertNull(recovery)
        // A late answer changes nothing.
        checkpoints.succeed(record)
        Thread.sleep(100)
        idle()
        assertFalse(engine.calls.any { it.startsWith("createWithCheckpoint") })
    }

    @Test
    fun `a mismatch survives a restart instead of booting the contradicted generation again`() {
        recoverOnce()
        engine.status[12L] = synced(slot, "ab".repeat(32))
        clocks.advance(3_000)
        poll()
        assertEquals(MyotisRecoveryReason.AnchorMismatch, recovery?.reason)

        // Force-stop and relaunch: by now the engine would be past the checkpoint slot.
        node.stop()
        idle()
        engine.calls.clear()
        node.start()
        idle()
        assertFalse(engine.calls.any { it.startsWith("createWithCheckpoint gnosis") })
        assertTrue("create mainnet" in engine.calls)
        assertEquals(MyotisRecovery(MyotisRecovery.Phase.Blocked, reason = MyotisRecoveryReason.AnchorMismatch), recovery)
        assertFalse(gnosisRow.ready)
        // The same story blocked as while checking again: the chain disagreed, not the evidence.
        assertEquals("The synced chain didn't match the agreed checkpoint.", recovery!!.message(0))

        // Retry asks for a fresh checkpoint (with the mismatch still named) and boots a new generation.
        node.retryRecovery(MyotisNetwork.Gnosis)
        eventually { checkpoints.asked.size == 2 }
        assertEquals(MyotisRecovery.Phase.Checking, recovery?.phase)
        assertTrue(recovery!!.message(0).startsWith("The synced chain didn't match"))
        val next = record.copy(verifiedAt = record.verifiedAt + 1)
        clocks.wall = next.verifiedAt
        checkpoints.succeed(next)
        eventually { engine.calls.count { it.startsWith("createWithCheckpoint gnosis") } == 1 }
        assertEquals(MyotisRecovery.Phase.Restarting, recovery?.phase)
        engine.status[22L] = synced(slot, rootHex)
        poll()
        assertNull(recovery)
        assertTrue(gnosisRow.ready)
    }

    @Test
    fun `a clock-blocked recovery asks again when the chain re-parks after a clock change`() {
        startStale()
        checkpoints.fail(MyotisCheckpointError.Clock)
        eventually { recovery?.phase == MyotisRecovery.Phase.Blocked }
        assertEquals(MyotisRecoveryReason.Clock, recovery?.reason)
        // The user fixes the clock; the park is released and the engine re-judges...
        clocks.wall -= 2L * 3_600_000L
        engine.status[2L] = stale
        poll()
        assertTrue("resume 2" in engine.calls)
        // ...still stale by the corrected clock: it parks again after the grace, and asks.
        clocks.advance(MyotisNode.REJUDGE_GRACE_MS)
        poll()
        eventually { checkpoints.asked.size == 2 }
        assertEquals(MyotisRecovery.Phase.Checking, recovery?.phase)
    }
}
