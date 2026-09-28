package baby.freedom.swarm

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Collections

/**
 * The checkpoint record rules, the quorum's vote / seat rules and the
 * acquirer's slot proposals (#195), against scripted checkpoint
 * authorities — no network.
 */
class MyotisCheckpointTest {

    private val gnosis = MyotisCheckpointNetwork.Gnosis
    private val mainnet = MyotisCheckpointNetwork.Mainnet
    private val gc = "https://checkpoint.gnosischain.com"
    private val dn = "https://checkpoint-sync-gnosis.dappnode.net"
    private val pn = "https://gnosis-beacon-api.publicnode.com"

    private val epoch = 1_895_077L
    private val slot = epoch * 16 // 30_321_232, a boundary block like the live one
    private val root = "0x3f437998c4c0f8a4aa2d7175ab08fd49faf4e6814c76e5b36cfe9a7756122c3d"
    private val other = "0x" + "ab".repeat(32)

    /** A minute after the checkpoint slot. */
    private val now = gnosis.slotTimeMs(slot) + 60_000L

    private fun expect(error: MyotisCheckpointError, block: suspend () -> Unit) {
        try {
            runBlocking { block() }
            fail("expected $error")
        } catch (e: MyotisCheckpointException) {
            assertEquals(error, e.error)
        }
    }

    // ---- Scripted authorities

    /** URL → body (a String) or a failure (a Throwable). Unscripted URLs are an HTTP 404. */
    private class Fetcher : MyotisCheckpointFetcher {
        val answers = mutableMapOf<String, Any>()
        val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())

        /** Host → the `Date` its responses carry; none by default. */
        val dates = mutableMapOf<String, Long>()
        override suspend fun fetch(url: String, limit: Int): MyotisCheckpointResponse =
            MyotisCheckpointResponse(get(url, limit), dates.entries.firstOrNull { url.startsWith(it.key) }?.value)

        override suspend fun get(url: String, limit: Int): ByteArray {
            asked += url
            return when (val a = answers[url]) {
                is String -> a.toByteArray()
                is Throwable -> throw a
                else -> throw MyotisCheckpointException.transport(MyotisTransportFailure.Http, 404)
            }
        }
    }

    private fun down() = MyotisCheckpointException.transport(MyotisTransportFailure.Transport)

    /** A Checkpointz authority finalized at [finalEpoch] / [finalRoot], serving [blockRoot] at [slot]. */
    private fun Fetcher.checkpointz(
        source: String,
        blockRoot: String = root,
        finalEpoch: Long = epoch,
        finalRoot: String = root,
        history: List<Pair<Long, String>> = listOf(slot to root),
    ) {
        answers["$source/eth/v1/beacon/blocks/$slot/root"] = """{"data":{"root":"${blockRoot.removePrefix("0x")}"}}"""
        answers["$source/eth/v1/beacon/states/head/finality_checkpoints"] =
            """{"data":{"finalized":{"epoch":"$finalEpoch","root":"$finalRoot"}}}"""
        answers["$source/checkpointz/v1/beacon/slots"] = """{"data":{"slots":[${
            history.joinToString(",") { (s, r) -> """{"slot":"$s","block_root":"$r"}""" }
        }]}}"""
    }

    /** A plain Beacon API authority: block flags instead of a history endpoint. */
    private fun Fetcher.beacon(
        source: String,
        blockRoot: String = root,
        finalized: String? = "true",
        optimistic: String? = "false",
        finalRoot: String = root,
    ) {
        val flags = listOfNotNull(
            optimistic?.let { "\"execution_optimistic\":$it" },
            finalized?.let { "\"finalized\":$it" },
        ).joinToString(",")
        val prefix = if (flags.isEmpty()) "" else "$flags,"
        answers["$source/eth/v1/beacon/blocks/$slot/root"] = """{$prefix"data":{"root":"$blockRoot"}}"""
        answers["$source/eth/v1/beacon/states/head/finality_checkpoints"] =
            """{"execution_optimistic":false,"finalized":false,"data":{"finalized":{"epoch":"$epoch","root":"$finalRoot"}}}"""
        answers["$source/eth/v1/beacon/headers/$finalRoot"] =
            """{"data":{"root":"$finalRoot","header":{"message":{"slot":"$slot"}}}}"""
    }

    private fun quorum(fetcher: Fetcher, network: MyotisCheckpointNetwork = gnosis, clock: Long = now) =
        MyotisCheckpointQuorum(network, fetcher, nowMs = { clock })

    // ---- Record rules

    private fun record(
        slot: Long = this.slot,
        root: String = this.root,
        sources: List<String> = listOf(gc, dn),
        verifiedAt: Long = now,
        finalizedEpoch: Long = epoch,
    ) = MyotisCheckpointRecord(100L, "gnosis", root, slot, verifiedAt, sources, finalizedEpoch)

    @Test
    fun `a fresh quorum record validates and round-trips through JSON`() {
        val r = record()
        assertEquals(r, r.validated(100L, now))
        assertEquals(r, MyotisCheckpointRecord.fromJson(r.toJson())!!.validated(100L, now))
    }

    @Test
    fun `a record needs a real quorum of distinct policy sources on its own chain`() {
        expect(MyotisCheckpointError.Mismatch) { record(sources = listOf(gc)).validated(100L, now) }
        expect(MyotisCheckpointError.Mismatch) { record(sources = listOf(gc, gc)).validated(100L, now) }
        expect(MyotisCheckpointError.Mismatch) {
            record(sources = listOf(gc, "https://evil.example")).validated(100L, now)
        }
        expect(MyotisCheckpointError.Mismatch) { record().validated(1L, now) }
        expect(MyotisCheckpointError.Mismatch) { record(root = "0x" + "0".repeat(64)).validated(100L, now) }
        expect(MyotisCheckpointError.Mismatch) { record(root = root.uppercase()).validated(100L, now) }
        expect(MyotisCheckpointError.Mismatch) { record(finalizedEpoch = epoch - 1).validated(100L, now) }
        expect(MyotisCheckpointError.Mismatch) { record(verifiedAt = gnosis.slotTimeMs(slot) - 1).validated(100L, now) }
    }

    @Test
    fun `a fresh record must be under an hour old and not ahead of the clock`() {
        val slotTime = gnosis.slotTimeMs(slot)
        expect(MyotisCheckpointError.Stale) {
            record(verifiedAt = slotTime + 3_600_001L).validated(100L, slotTime + 3_600_001L)
        }
        expect(MyotisCheckpointError.Clock) { record().validated(100L, slotTime - 1_000L) }
        // A reloaded generation isn't judged on age: the engine re-judges it.
        assertEquals(record(), record().validated(100L, now + 86_400_000L, fresh = false))
    }

    @Test
    fun `hex and number parsing are strict`() {
        assertEquals(root, MyotisHex.root(root.removePrefix("0x").uppercase()))
        assertNull(MyotisHex.root("0x" + "0".repeat(64)))
        assertNull(MyotisHex.root("0x1234"))
        assertEquals("0x" + "0".repeat(64), MyotisHex.bytes32("0".repeat(64)))
        assertEquals(1_895_077L, MyotisHex.uint("1895077"))
        assertEquals(255L, MyotisHex.uint("0xff"))
        assertEquals(7L, MyotisHex.uint(7))
        assertNull(MyotisHex.uint("-1"))
        assertNull(MyotisHex.uint("1.5"))
        assertNull(MyotisHex.uint(1.5))
        assertNull(MyotisHex.uint("99999999999999999999"))
    }

    // ---- Votes

    @Test
    fun `a Checkpointz authority votes for the root its finality covers`() {
        val f = Fetcher().apply { checkpointz(gc) }
        val vote = runBlocking { quorum(f).vote(gc, slot) }
        assertEquals(MyotisCheckpointQuorum.Vote(gc, slot, root, epoch), vote)
    }

    @Test
    fun `an older slot needs the authority's finalized history to list exactly that root`() {
        val later = "0x" + "cd".repeat(32)
        val clock = now + 300_000L // finality has moved two epochs on
        val f = Fetcher().apply { checkpointz(gc, finalEpoch = epoch + 2, finalRoot = later) }
        assertEquals(root, runBlocking { quorum(f, clock = clock).vote(gc, slot) }.root)

        f.checkpointz(gc, finalEpoch = epoch + 2, finalRoot = later, history = listOf(slot to other))
        expect(MyotisCheckpointError.QuorumConflict) { quorum(f, clock = clock).vote(gc, slot) }
        f.checkpointz(gc, finalEpoch = epoch + 2, finalRoot = later, history = emptyList())
        expect(MyotisCheckpointError.Race) { quorum(f, clock = clock).vote(gc, slot) }
        f.checkpointz(gc, finalEpoch = epoch + 2, finalRoot = later, history = listOf(slot to root, slot to root))
        expect(MyotisCheckpointError.QuorumConflict) { quorum(f, clock = clock).vote(gc, slot) }
    }

    @Test
    fun `a different root at the finalized epoch itself is a conflict`() {
        val f = Fetcher().apply { checkpointz(gc, blockRoot = other) }
        expect(MyotisCheckpointError.QuorumConflict) { quorum(f).vote(gc, slot) }
    }

    @Test
    fun `finality that doesn't reach the slot yet is a race, and finality in the future is the clock`() {
        val f = Fetcher().apply { checkpointz(gc, finalEpoch = epoch - 1) }
        expect(MyotisCheckpointError.Race) { quorum(f).vote(gc, slot) }
        f.checkpointz(gc, finalEpoch = epoch + 1_000)
        expect(MyotisCheckpointError.Clock) { quorum(f).vote(gc, slot) }
    }

    @Test
    fun `a Beacon API authority votes only on the block's own finalized flag`() {
        val f = Fetcher().apply { beacon(pn) }
        assertEquals(root, runBlocking { quorum(f).vote(pn, slot) }.root)
        f.beacon(pn, finalized = null)
        expect(MyotisCheckpointError.Unavailable) { quorum(f).vote(pn, slot) }
        f.beacon(pn, finalized = "false")
        expect(MyotisCheckpointError.Race) { quorum(f).vote(pn, slot) }
        f.beacon(pn, finalized = "\"yes\"")
        expect(MyotisCheckpointError.Unavailable) { quorum(f).vote(pn, slot) }
        f.beacon(pn, optimistic = "true")
        expect(MyotisCheckpointError.QuorumConflict) { quorum(f).vote(pn, slot) }
        f.beacon(pn, optimistic = null)
        expect(MyotisCheckpointError.Unavailable) { quorum(f).vote(pn, slot) }
    }

    @Test
    fun `a malformed or failing answer is unavailable, never a verdict`() {
        val f = Fetcher().apply { checkpointz(gc) }
        f.answers["$gc/eth/v1/beacon/blocks/$slot/root"] = "not json"
        expect(MyotisCheckpointError.Unavailable) { quorum(f).vote(gc, slot) }
        f.answers["$gc/eth/v1/beacon/blocks/$slot/root"] = "[".repeat(50_000)
        expect(MyotisCheckpointError.Unavailable) { quorum(f).vote(gc, slot) }
        f.answers["$gc/eth/v1/beacon/blocks/$slot/root"] = down()
        expect(MyotisCheckpointError.Unavailable) { quorum(f).vote(gc, slot) }
    }

    // ---- Quorum seats

    @Test
    fun `two agreeing authorities out of three make a quorum`() {
        val f = Fetcher().apply {
            checkpointz(gc)
            checkpointz(dn)
            beacon(pn)
        }
        val obs = runBlocking { quorum(f).quorum(slot) }
        assertEquals(slot, obs.slot)
        assertEquals(root, obs.root)
        assertEquals(epoch, obs.finalizedEpoch)
        assertEquals(listOf(gc, dn, pn), obs.sources)
    }

    @Test
    fun `one Gnosis authority down still leaves a quorum, two down is unavailable`() {
        val f = Fetcher().apply {
            checkpointz(gc)
            beacon(pn)
        }
        assertEquals(listOf(gc, pn), runBlocking { quorum(f).quorum(slot) }.sources)
        val lone = Fetcher().apply { checkpointz(gc) }
        expect(MyotisCheckpointError.QuorumUnavailable) { quorum(lone).quorum(slot) }
    }

    @Test
    fun `a dissenting authority keeps its seat and splits the vote into a conflict`() {
        val f = Fetcher().apply {
            checkpointz(gc)
            checkpointz(dn, blockRoot = other, finalRoot = other, history = listOf(slot to other))
            answers["$pn/eth/v1/beacon/blocks/$slot/root"] = down()
        }
        expect(MyotisCheckpointError.QuorumConflict) { quorum(f).quorum(slot) }
    }

    @Test
    fun `an unavailable mainnet seat goes to the next reserve, the threshold never drops`() {
        val s = mainnet.sources
        val mslot = 15_314_560L
        val mroot = "0xff79b38b4c094a4790b17305e05424fc443aa931b3d4e9f9a5ca4d36c6026d02"
        val mnow = mainnet.slotTimeMs(mslot) + 60_000L
        val f = Fetcher()
        fun ok(source: String) {
            f.answers["$source/eth/v1/beacon/blocks/$mslot/root"] = """{"data":{"root":"$mroot"}}"""
            f.answers["$source/eth/v1/beacon/states/head/finality_checkpoints"] =
                """{"data":{"finalized":{"epoch":"${mslot / 32}","root":"$mroot"}}}"""
        }
        // Seats 1 and 2 down, seat 3 votes; reserves 4 (down) and 5 fill in.
        ok(s[2])
        ok(s[4])
        val obs = runBlocking { quorum(f, mainnet, mnow).quorum(mslot) }
        assertEquals(listOf(s[2], s[4]), obs.sources)
        // Nothing past the first two votes was asked for.
        assertTrue(f.asked.none { it.startsWith(s[5]) || it.startsWith(s[6]) })
    }

    // ---- Acquisition: proposals + quorum + record

    private fun acquirer(f: Fetcher, clock: () -> Long = { now }, deadline: Long = MyotisCheckpointAcquirer.DEADLINE_MS) =
        MyotisCheckpointAcquirer(f, clock, deadline)

    @Test
    fun `acquisition proposes the first authority's finalized checkpoint and records the quorum on it`() {
        val f = Fetcher().apply {
            checkpointz(gc)
            checkpointz(dn)
            beacon(pn)
        }
        val lines = mutableListOf<String>()
        val record = runBlocking { acquirer(f).acquire(MyotisNetwork.Gnosis) { lines += it } }
        assertEquals(MyotisCheckpointRecord(100L, "gnosis", root, slot, now, listOf(gc, dn, pn), epoch), record)
        assertEquals(3, lines.size)
        assertTrue(lines.all { it.contains("slot=$slot") && it.contains("outcome=vote") })
        assertTrue(lines.any { it.startsWith("source=checkpoint.gnosischain.com ") })
    }

    @Test
    fun `a Beacon API authority proposes from its block header`() {
        val f = Fetcher().apply {
            checkpointz(gc)
            checkpointz(dn)
            beacon(pn)
            // The Checkpointz authorities vote but can't propose (no history today).
            answers["$gc/checkpointz/v1/beacon/slots"] = down()
            answers["$dn/checkpointz/v1/beacon/slots"] = down()
        }
        val record = runBlocking { acquirer(f).acquire(MyotisNetwork.Gnosis) }
        assertEquals(slot, record.slot)
        assertTrue(f.asked.contains("$pn/eth/v1/beacon/headers/$root"))
    }

    @Test
    fun `a skipped boundary slot is found in the Checkpointz history by its root`() {
        val skipped = slot - 3
        val f = Fetcher().apply {
            for (s in listOf(gc, dn)) {
                answers["$s/eth/v1/beacon/states/head/finality_checkpoints"] =
                    """{"data":{"finalized":{"epoch":"$epoch","root":"$root"}}}"""
                answers["$s/checkpointz/v1/beacon/slots"] = """{"data":{"slots":[{"slot":"$skipped","block_root":"$root"}]}}"""
                answers["$s/eth/v1/beacon/blocks/$skipped/root"] = """{"data":{"root":"$root"}}"""
            }
        }
        val record = runBlocking { acquirer(f).acquire(MyotisNetwork.Gnosis) }
        assertEquals(skipped, record.slot)
        assertEquals(epoch, record.finalizedEpoch)
    }

    @Test
    fun `a stale or unusable proposal falls through to the next authority's`() {
        val f = Fetcher().apply {
            checkpointz(dn)
            beacon(pn)
            // gnosischain proposes a checkpoint two hours old.
            val oldEpoch = epoch - (2 * 3600 / 5 / 16)
            answers["$gc/eth/v1/beacon/states/head/finality_checkpoints"] =
                """{"data":{"finalized":{"epoch":"$oldEpoch","root":"$other"}}}"""
            answers["$gc/checkpointz/v1/beacon/slots"] = """{"data":{"slots":[{"slot":"${oldEpoch * 16}","block_root":"$other"}]}}"""
        }
        val record = runBlocking { acquirer(f).acquire(MyotisNetwork.Gnosis) }
        assertEquals(slot, record.slot)
        assertEquals(listOf(dn, pn), record.sources)
    }

    @Test
    fun `a quorum conflict ends the attempt at once`() {
        val f = Fetcher().apply {
            checkpointz(gc)
            checkpointz(dn, blockRoot = other, finalRoot = other, history = listOf(slot to other))
            beacon(pn, blockRoot = "0x" + "ee".repeat(32), finalRoot = "0x" + "ee".repeat(32))
        }
        expect(MyotisCheckpointError.QuorumConflict) { acquirer(f).acquire(MyotisNetwork.Gnosis) }
    }

    @Test
    fun `every authority down is quorum-unavailable, a clock far behind them is clock`() {
        expect(MyotisCheckpointError.QuorumUnavailable) { acquirer(Fetcher()).acquire(MyotisNetwork.Gnosis) }
        val f = Fetcher().apply {
            checkpointz(gc)
            checkpointz(dn)
            beacon(pn)
        }
        // The device clock is a day behind the authorities' finality.
        expect(MyotisCheckpointError.Clock) {
            acquirer(f, clock = { now - 86_400_000L }).acquire(MyotisNetwork.Gnosis)
        }
    }

    @Test
    fun `a device clock set ahead is clock by the authorities' Date, not a stale checkpoint`() {
        val ahead = now + 2 * 3_600_000L
        val f = Fetcher().apply {
            checkpointz(gc)
            checkpointz(dn)
            beacon(pn)
            // The authorities' own clocks: a minute after their finalized checkpoint.
            for (s in listOf(gc, dn, pn)) dates[s] = now
        }
        expect(MyotisCheckpointError.Clock) { acquirer(f, clock = { ahead }).acquire(MyotisNetwork.Gnosis) }
        // One authority down doesn't hide it: two still place the clock wrong.
        f.answers["$pn/eth/v1/beacon/states/head/finality_checkpoints"] = down()
        expect(MyotisCheckpointError.Clock) { acquirer(f, clock = { ahead }).acquire(MyotisNetwork.Gnosis) }
        // A cached finality response two hours old: Date + Age (what the fetcher reports) agrees
        // with this device, so the checkpoint really is stale and the ladder asks again.
        for (s in listOf(gc, dn, pn)) f.dates[s] = ahead
        expect(MyotisCheckpointError.Stale) { acquirer(f, clock = { ahead }).acquire(MyotisNetwork.Gnosis) }
        // Without a Date there's no telling: the ladder asks again.
        f.dates.clear()
        expect(MyotisCheckpointError.Stale) { acquirer(f, clock = { ahead }).acquire(MyotisNetwork.Gnosis) }
    }

    @Test
    fun `finality really stalled for hours is stale, whatever the Date says`() {
        val later = now + 2 * 3_600_000L
        val f = Fetcher().apply {
            checkpointz(gc)
            checkpointz(dn)
            beacon(pn)
            // The authorities agree with this device: it's the checkpoint that's old.
            for (s in listOf(gc, dn, pn)) dates[s] = later
        }
        expect(MyotisCheckpointError.Stale) { acquirer(f, clock = { later }).acquire(MyotisNetwork.Gnosis) }
        // A few minutes' drift on top of a stall isn't a clock error either.
        for (s in listOf(gc, dn, pn)) f.dates[s] = later - 5 * 60_000L
        expect(MyotisCheckpointError.Stale) { acquirer(f, clock = { later }).acquire(MyotisNetwork.Gnosis) }
        // One authority alone saying the clock is wrong isn't enough.
        f.dates.clear()
        f.dates[gc] = now
        expect(MyotisCheckpointError.Stale) { acquirer(f, clock = { later }).acquire(MyotisNetwork.Gnosis) }
    }

    @Test
    fun `the whole attempt is bounded by its deadline`() {
        val stalled = MyotisCheckpointFetcher { _, _ ->
            delay(60_000)
            ByteArray(0)
        }
        val started = System.nanoTime()
        expect(MyotisCheckpointError.Unavailable) {
            MyotisCheckpointAcquirer(stalled, { now }, deadlineMs = 300).acquire(MyotisNetwork.Gnosis)
        }
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000)
    }

    @Test
    fun `retryable failures are the transient ones and reasons map like desktop's`() {
        val retryable = MyotisCheckpointError.entries.filter { it.retryable }.toSet()
        assertEquals(
            setOf(
                MyotisCheckpointError.Unavailable, MyotisCheckpointError.QuorumUnavailable,
                MyotisCheckpointError.Race, MyotisCheckpointError.Stale,
            ),
            retryable,
        )
        assertEquals(MyotisRecoveryReason.Unavailable, MyotisRecoveryReason.of(MyotisCheckpointError.Race))
        assertEquals("quorum-conflict", MyotisRecoveryReason.of(MyotisCheckpointError.QuorumConflict).code)
        assertEquals(listOf(15_000L, 60_000L, 300_000L, 300_000L), (1..4).map { MyotisRecoveryPolicy.retryDelay(it) })
        assertNull(MyotisRecoveryPolicy.retryDelay(0))
    }

    @Test
    fun `recovery finishes only past the anchor, or at it with the agreed root`() {
        val r = record()
        val synced = MyotisChainStatus(100L, beaconState = "SYNCED", finalizedSlot = slot, finalizedRootHex = root.removePrefix("0x"))
        assertTrue(MyotisRecoveryPolicy.canFinish(synced, r))
        assertTrue(MyotisRecoveryPolicy.canFinish(synced.copy(finalizedSlot = slot + 16, finalizedRootHex = "11".repeat(32)), r))
        assertEquals(false, MyotisRecoveryPolicy.canFinish(synced.copy(finalizedSlot = slot - 16), r))
        assertEquals(false, MyotisRecoveryPolicy.canFinish(synced.copy(beaconState = "SYNCING"), r))
        assertEquals(false, MyotisRecoveryPolicy.canFinish(synced.copy(finalizedRootHex = ""), r))
        val wrong = synced.copy(finalizedRootHex = other.removePrefix("0x"))
        assertEquals(false, MyotisRecoveryPolicy.canFinish(wrong, r))
        assertTrue(MyotisRecoveryPolicy.isAnchorMismatch(wrong, r))
        assertEquals(false, MyotisRecoveryPolicy.isAnchorMismatch(synced, r))
        assertEquals(false, MyotisRecoveryPolicy.isAnchorMismatch(wrong, null))
        // A bundled generation has no anchor to check against.
        assertTrue(MyotisRecoveryPolicy.canFinish(synced, null))
    }
}
