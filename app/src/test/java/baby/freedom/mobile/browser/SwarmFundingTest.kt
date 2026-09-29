package baby.freedom.mobile.browser

import baby.freedom.mobile.chains.BuiltInChains
import baby.freedom.mobile.chains.rpc.ChainSource
import baby.freedom.mobile.chains.rpc.ChainTrust
import baby.freedom.mobile.wallet.ChainTrustsForTest
import baby.freedom.mobile.wallet.DappCall
import baby.freedom.mobile.wallet.EthTransaction
import baby.freedom.mobile.wallet.SendQuote
import baby.freedom.mobile.wallet.SendRequest
import baby.freedom.mobile.wallet.SendStatus
import baby.freedom.mobile.wallet.SwarmFundLabel
import baby.freedom.mobile.wallet.SwarmFunder
import baby.freedom.mobile.wallet.TokenRegistry
import baby.freedom.mobile.wallet.WalletAccount
import baby.freedom.swarm.NodeInfo
import baby.freedom.swarm.NodeStatus
import java.io.File
import java.math.BigInteger
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The stamp the wallet buys for the node (#115), from its call going out to the node connecting it. */
class SwarmFundingTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val node = "0xD5572300E441b77b72bdd318BBFA7b97A1F03096"
    private val batch = SwarmFunder.batchId(ByteArray(32) { 7 })
    private val label = SwarmFundLabel(node, batch, 17, 2)
    private val hash = "0x" + "aa".repeat(32)
    // Added to from the funding's own threads as well as the test's.
    private val connects: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
    private val payer = "0x9858EfFD232B4033E47d90003D41EC34EcaEda94"

    private fun funding(file: File = File(tmp.root, "funding.json")) =
        SwarmFunding(file, connect = { connects += it; true }, spends = emptyFlow())

    private fun status(stage: SendStatus.Stage, l: SwarmFundLabel? = label): SendStatus {
        val gnosis = BuiltInChains.GNOSIS
        val from = WalletAccount(0, "Account 1", payer)
        val data = byteArrayOf(0x83.toByte(), 0x4a, 0xeb.toByte(), 0x80.toByte())
        return SendStatus(
            SendQuote(
                SendRequest(gnosis, TokenRegistry.native(gnosis), from, SwarmFunder.ADDRESS, BigInteger.TEN, DappCall(null, data, null, swarm = l)),
                EthTransaction(100, BigInteger.ONE, BigInteger.valueOf(400_000), SwarmFunder.ADDRESS, BigInteger.TEN, data, EthTransaction.Fees.Legacy(BigInteger.ONE)),
                BigInteger.TEN.pow(18), null, 0L, ChainTrustsForTest.unverified,
            ),
            stage,
            hash.takeIf { stage != SendStatus.Stage.Signing },
        )
    }

    @Test
    fun `going out it's recorded, mined it's connected once, and the record outlives the process`() {
        val f = funding()
        f.noteSend(status(SendStatus.Stage.Signing))
        assertEquals(SwarmFunding.Pending(node, batch, 17, 2, null, mined = false, from = payer, nonce = BigInteger.ONE), f.pending.value)
        f.noteSend(status(SendStatus.Stage.Pending))
        assertEquals(hash, f.pending.value!!.hash)
        assertTrue(connects.isEmpty())
        // Not mined yet: nothing to connect.
        assertFalse(f.connectNow())

        f.noteSend(status(SendStatus.Stage.Confirmed(48_500_000, null)))
        assertTrue(f.pending.value!!.mined)
        assertEquals(listOf(batch), connects)
        // The sender replays its last status: not a second connect.
        f.noteSend(status(SendStatus.Stage.Confirmed(48_500_000, null)))
        assertEquals(1, connects.size)

        // A connect that failed (the node was off) is offered again after a restart.
        val again = funding()
        assertEquals(f.pending.value, again.pending.value)
        assertTrue(again.connectNow())
        assertEquals(listOf(batch, batch), connects)
        again.forget()
        assertNull(funding().pending.value)
    }

    @Test
    fun `one that reverted or certainly never went out bought nothing`() {
        val f = funding()
        f.noteSend(status(SendStatus.Stage.Pending))
        f.noteSend(status(SendStatus.Stage.Reverted(1, null)))
        assertNull(f.pending.value)

        f.noteSend(status(SendStatus.Stage.Signing))
        f.noteSend(status(SendStatus.Stage.Failed("no", mayHaveGone = true)))
        // It may have gone out: kept.
        assertEquals(batch, f.pending.value!!.batchId)
        f.noteSend(status(SendStatus.Stage.Failed("no", mayHaveGone = false)))
        assertNull(f.pending.value)
        assertTrue(connects.isEmpty())
    }

    @Test
    fun `a finished send with no record, or anyone else's, starts nothing`() {
        val f = funding()
        // Already connected (and so cleared) before the process restarted.
        f.noteSend(status(SendStatus.Stage.Confirmed(1, null)))
        f.noteSend(status(SendStatus.Stage.Pending, l = null))
        assertNull(f.pending.value)
        assertTrue(connects.isEmpty())
    }

    @Test
    fun `an unreadable record reads as none`() {
        val file = File(tmp.root, "funding.json").apply { writeText("{not json") }
        assertNull(funding(file).pending.value)
        file.writeText("""{"node":"$node","batchId":"xyz","depth":17,"days":2,"hash":"","mined":true}""")
        assertNull(funding(file).pending.value)
    }

    @Test
    fun `funding waits for light mode, the wallet's identity, and a stamp already bought to be connected`() {
        val light = NodeInfo(status = NodeStatus.Running, accountAddress = node, walletIdentity = true, lightMode = true)
        assertNull(fundNodeBlockedReason(light, null))
        assertTrue(fundNodeBlockedReason(light.copy(lightMode = false), null)!!.contains("light mode"))
        assertTrue(fundNodeBlockedReason(light.copy(walletIdentity = false), null) != null)
        val pending = SwarmFunding.Pending(node, batch, 17, 2, hash, mined = false)
        assertEquals("A stamp your wallet is buying for the node is still going out.", fundNodeBlockedReason(light, pending))
        assertEquals("Connect the stamp your wallet already bought for the node first.", fundNodeBlockedReason(light, pending.copy(mined = true)))
        assertEquals(
            "Connect or dismiss the stamp your wallet stopped following first.",
            fundNodeBlockedReason(light, pending.copy(tracked = false)),
        )
    }

    /** Waits (bounded) for [f]'s background collectors to settle on [want]. */
    private fun awaitPending(f: SwarmFunding, want: (SwarmFunding.Pending?) -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!want(f.pending.value) && System.currentTimeMillis() < until) Thread.sleep(10)
        assertTrue("pending was ${f.pending.value}", want(f.pending.value))
    }

    private fun awaitConnects(n: Int) {
        val until = System.currentTimeMillis() + 5_000
        while (synchronized(connects) { connects.size } < n && System.currentTimeMillis() < until) Thread.sleep(10)
    }

    @Test
    fun `a send the wallet stopped following leaves a record offered for Connect and Dismiss, across restarts`() {
        val f = funding()
        // Stop tracking (or Remove wallet): the sender publishes null.
        f.start(flowOf(status(SendStatus.Stage.Pending), status(SendStatus.Stage.Unconfirmed), null))
        awaitPending(f) { it?.tracked == false }
        assertEquals(
            SwarmFunding.Pending(node, batch, 17, 2, hash, mined = false, tracked = false, from = payer, nonce = BigInteger.ONE),
            f.pending.value,
        )
        // Not blocking funding for good, and connectable in case it was mined after all.
        val light = NodeInfo(status = NodeStatus.Running, accountAddress = node, walletIdentity = true, lightMode = true)
        assertFalse(fundNodeBlockedReason(light, f.pending.value)!!.contains("still going out"))
        assertTrue(f.connectNow())
        assertEquals(listOf(batch), connects)

        val again = funding()
        assertEquals(f.pending.value, again.pending.value)
        // If the sender picks the same send up again and sees it mined, it's tracked and connected.
        again.noteSend(status(SendStatus.Stage.Confirmed(1, null)))
        assertEquals(true, again.pending.value?.let { it.mined && it.tracked })
        assertEquals(listOf(batch, batch), connects)

        // A mined one isn't touched by the sender moving on.
        again.untrack()
        assertTrue(again.pending.value!!.tracked)
        again.forget()
        assertNull(funding().pending.value)
    }

    @Test
    fun `a record left going out with no send after a restart is untracked, one still being followed isn't`() {
        funding().noteSend(status(SendStatus.Stage.Pending))
        // The sender's journal brought nothing back.
        val orphan = funding()
        orphan.start(emptyFlow()) { null }
        awaitPending(orphan) { it?.tracked == false }

        funding().noteSend(status(SendStatus.Stage.Pending))
        val followed = funding()
        assertTrue(followed.pending.value!!.tracked)
        followed.start(emptyFlow()) { status(SendStatus.Stage.Pending) }
        Thread.sleep(200)
        assertTrue(followed.pending.value!!.tracked)
    }

    /** A chain whose answers the test sets; [reads] counts receipt lookups. */
    private class FakeChain : SwarmFunding.ChainReader {
        @Volatile var receipt: JSONObject? = null
        @Volatile var trusted = true
        @Volatile var minedCount: BigInteger = BigInteger.ONE
        @Volatile var fail = false
        @Volatile var reads = 0

        override suspend fun receipt(hash: String): SwarmFunding.Receipt? {
            reads++
            if (fail) throw java.io.IOException("offline")
            return receipt?.let { SwarmFunding.Receipt(it, trusted) }
        }

        override suspend fun minedCount(address: String): BigInteger {
            if (fail) throw java.io.IOException("offline")
            return minedCount
        }
    }

    private fun receipt(status: String) = JSONObject().put("blockNumber", "0x10").put("status", status)

    /** A record the wallet stopped following before its call ([hash], nonce 1) was mined. */
    private fun untracked(chain: FakeChain, now: () -> Long = { 0L }): SwarmFunding {
        funding().apply {
            noteSend(status(SendStatus.Stage.Unconfirmed))
            untrack()
        }
        return SwarmFunding(
            File(tmp.root, "funding.json"), connect = { connects += it; true }, spends = emptyFlow(), chain = chain,
            confirmAfterMs = 30_000, now = now,
        )
    }

    @Test
    fun `an untracked call that lands later is found by its hash and connected, across a restart`() {
        val chain = FakeChain()
        val f = untracked(chain)
        assertEquals(payer, f.pending.value!!.from)
        assertEquals(BigInteger.ONE, f.pending.value!!.nonce)
        // Not mined yet, and the nonce not used: still waiting, and not certain to have bought nothing.
        runBlocking { f.checkChain() }
        assertFalse(f.pending.value!!.mined)
        assertNull(f.superseded.value)
        assertTrue(connects.isEmpty())

        chain.receipt = receipt("0x1")
        chain.minedCount = BigInteger.TWO
        runBlocking { f.checkChain() }
        assertTrue(f.pending.value!!.mined)
        assertNull(f.superseded.value)
        assertEquals(listOf(batch), connects)
        // Mined: not looked up, nor connected, again.
        val reads = chain.reads
        runBlocking { f.checkChain() }
        assertEquals(reads, chain.reads)
        assertEquals(1, connects.size)
    }

    @Test
    fun `an untracked call that reverted is dropped, one whose nonce went elsewhere is marked, and a failed read changes nothing`() {
        val chain = FakeChain()
        var clock = 0L
        val f = untracked(chain) { clock }
        chain.fail = true
        runBlocking { f.checkChain() }
        assertEquals(false, f.pending.value?.mined)
        assertNull(f.superseded.value)

        chain.fail = false
        chain.minedCount = BigInteger.TWO
        runBlocking { f.checkChain() }
        // One read isn't enough (its two halves may come from nodes at different heights), nor an agreeing one too soon.
        assertNull(f.superseded.value)
        clock = 29_999
        runBlocking { f.checkChain() }
        assertNull(f.superseded.value)
        clock = 30_000
        runBlocking { f.checkChain() }
        // Can never be mined: kept for the user to dismiss, now safely.
        assertEquals(batch, f.superseded.value)
        assertEquals(false, f.pending.value?.mined)

        chain.receipt = receipt("0x0")
        runBlocking { f.checkChain() }
        assertNull(f.pending.value)
        assertNull(funding().pending.value)
        assertTrue(connects.isEmpty())
    }

    @Test
    fun `one unverified read of a reverted receipt doesn't drop the record, two 30 s apart do`() {
        val chain = FakeChain()
        var clock = 0L
        val f = untracked(chain) { clock }
        // A lone public RPC (buggy, or serving a block later reorged away) says it reverted.
        chain.trusted = false
        chain.receipt = receipt("0x0")
        runBlocking { f.checkChain() }
        assertEquals(false, f.pending.value?.mined)
        clock = 29_999
        runBlocking { f.checkChain() }
        assertEquals(false, f.pending.value?.mined)
        // The next read disagrees (not there after all): the verdict starts over.
        chain.receipt = null
        clock = 30_000
        runBlocking { f.checkChain() }
        chain.receipt = receipt("0x0")
        clock = 40_000
        runBlocking { f.checkChain() }
        clock = 69_999
        runBlocking { f.checkChain() }
        assertEquals(false, f.pending.value?.mined)
        // It really mined, successfully: kept, and connected once a second read agrees.
        chain.receipt = receipt("0x1")
        clock = 70_000
        runBlocking { f.checkChain() }
        assertEquals(false, f.pending.value?.mined)
        clock = 100_000
        runBlocking { f.checkChain() }
        assertTrue(f.pending.value!!.mined)
        assertEquals(listOf(batch), connects)
    }

    @Test
    fun `one unverified read of a successful receipt doesn't mark the call mined, two 30 s apart do`() {
        val chain = FakeChain()
        var clock = 0L
        val f = untracked(chain) { clock }
        // A lone public RPC (buggy, or serving a block later reorged away) says it was mined.
        chain.trusted = false
        chain.receipt = receipt("0x1")
        runBlocking { f.checkChain() }
        assertEquals(false, f.pending.value?.mined)
        assertTrue(connects.isEmpty())
        clock = 29_999
        runBlocking { f.checkChain() }
        assertEquals(false, f.pending.value?.mined)
        // The next read disagrees (not there after all): still looked up, the verdict starts over.
        chain.receipt = null
        clock = 30_000
        runBlocking { f.checkChain() }
        assertEquals(false, f.pending.value?.mined)
        // Nor does a revert read confirm an earlier success read, or the other way round.
        chain.receipt = receipt("0x1")
        clock = 40_000
        runBlocking { f.checkChain() }
        chain.receipt = receipt("0x0")
        clock = 70_000
        runBlocking { f.checkChain() }
        assertEquals(false, f.pending.value?.mined)
        assertTrue(connects.isEmpty())
        // Two agreeing reads 30 s apart: mined, and connected.
        chain.receipt = receipt("0x1")
        clock = 80_000
        runBlocking { f.checkChain() }
        assertEquals(false, f.pending.value?.mined)
        clock = 110_000
        runBlocking { f.checkChain() }
        assertTrue(f.pending.value!!.mined)
        assertEquals(listOf(batch), connects)
    }

    @Test
    fun `two unverified reads 30 s apart agreeing it reverted drop the record`() {
        val chain = FakeChain()
        var clock = 0L
        val f = untracked(chain) { clock }
        chain.trusted = false
        chain.receipt = receipt("0x0")
        runBlocking { f.checkChain() }
        assertEquals(false, f.pending.value?.mined)
        clock = 30_000
        runBlocking { f.checkChain() }
        assertNull(f.pending.value)
        assertNull(funding().pending.value)
        assertTrue(connects.isEmpty())
    }

    @Test
    fun `a count read ahead of the receipt read doesn't call a just-mined call superseded`() {
        val chain = FakeChain()
        var clock = 0L
        val f = untracked(chain) { clock }
        // The count's nodes have the block with the call; the receipt's are a block behind.
        chain.minedCount = BigInteger.TWO
        runBlocking { f.checkChain() }
        assertNull(f.superseded.value)
        // By the next read the lagging nodes caught up: it's mined, never labelled superseded.
        clock = 30_000
        chain.receipt = receipt("0x1")
        runBlocking { f.checkChain() }
        assertNull(f.superseded.value)
        assertTrue(f.pending.value!!.mined)
        assertEquals(listOf(batch), connects)
    }

    @Test
    fun `a superseded verdict is withdrawn, and has to be confirmed afresh, when a read disagrees`() {
        val chain = FakeChain()
        var clock = 0L
        val f = untracked(chain) { clock }
        chain.minedCount = BigInteger.TWO
        runBlocking { f.checkChain() }
        clock = 30_000
        runBlocking { f.checkChain() }
        assertEquals(batch, f.superseded.value)
        // A read at a lower height (the count not past the nonce): no longer certain.
        chain.minedCount = BigInteger.ONE
        clock = 60_000
        runBlocking { f.checkChain() }
        assertNull(f.superseded.value)
        chain.minedCount = BigInteger.TWO
        clock = 60_001
        runBlocking { f.checkChain() }
        assertNull(f.superseded.value)
        clock = 90_001
        runBlocking { f.checkChain() }
        assertEquals(batch, f.superseded.value)
    }

    @Test
    fun `an untracked call is looked up less and less often, and not at all once it can never be mined`() {
        val chain = FakeChain()
        val f = SwarmFunding(
            File(tmp.root, "funding.json"), connect = { connects += it; true }, spends = emptyFlow(), chain = chain,
            checkEveryMs = 10, checkAtMostEveryMs = 60_000, confirmAfterMs = 10,
        )
        val started = System.nanoTime()
        f.start(flowOf(status(SendStatus.Stage.Pending), status(SendStatus.Stage.Unconfirmed), null))
        awaitPending(f) { it?.tracked == false }
        // Backing off from 10 ms (10, 20, 40, 80, 160, 320…): a handful of reads in 600 ms, not 60. Bounded by the
        // time that really went by, which a loaded machine stretches past the 600 ms slept (#225 R5-F3).
        Thread.sleep(600)
        val backedOff = chain.reads
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        val atMost = 2 + (64 - java.lang.Long.numberOfLeadingZeros(elapsedMs / 10 + 1))
        assertTrue("$backedOff reads in $elapsedMs ms (at most $atMost)", backedOff in 2..atMost)

        // The nonce went elsewhere: confirmed at the next read, then nothing more is read.
        chain.minedCount = BigInteger.TWO
        val until = System.currentTimeMillis() + 5_000
        while (f.superseded.value == null && System.currentTimeMillis() < until) Thread.sleep(10)
        assertEquals(batch, f.superseded.value)
        val reads = chain.reads
        Thread.sleep(300)
        assertEquals(reads, chain.reads)
        // A Connect still looks it up once.
        f.connectNow()
        val until2 = System.currentTimeMillis() + 5_000
        while (chain.reads == reads && System.currentTimeMillis() < until2) Thread.sleep(10)
        assertEquals(reads + 1, chain.reads)
        assertFalse(f.pending.value!!.mined)
    }

    @Test
    fun `lookups stopped by a superseded verdict start again when a Connect's read withdraws it`() {
        val chain = FakeChain()
        val f = SwarmFunding(
            File(tmp.root, "funding.json"), connect = { connects += it; false }, spends = emptyFlow(), chain = chain,
            checkEveryMs = 10, checkAtMostEveryMs = 40, confirmAfterMs = 10,
        )
        chain.minedCount = BigInteger.TWO
        f.start(flowOf(status(SendStatus.Stage.Pending), status(SendStatus.Stage.Unconfirmed), null))
        awaitPending(f) { it?.tracked == false }
        val until = System.currentTimeMillis() + 5_000
        while (f.superseded.value == null && System.currentTimeMillis() < until) Thread.sleep(10)
        assertEquals(batch, f.superseded.value)
        val stopped = chain.reads
        Thread.sleep(200)
        assertEquals(stopped, chain.reads)

        // A Connect (which fails: nothing changes in the record) reads at a height where the nonce isn't used yet.
        chain.minedCount = BigInteger.ONE
        f.connectNow()
        val until2 = System.currentTimeMillis() + 5_000
        while (f.superseded.value != null && System.currentTimeMillis() < until2) Thread.sleep(10)
        assertNull(f.superseded.value)
        // No longer certain it can never land: the app keeps checking, as the card says, and connects it once mined.
        val withdrawn = chain.reads
        val until3 = System.currentTimeMillis() + 5_000
        while (chain.reads < withdrawn + 2 && System.currentTimeMillis() < until3) Thread.sleep(10)
        assertTrue("${chain.reads - withdrawn} reads", chain.reads >= withdrawn + 2)
        chain.receipt = receipt("0x1")
        chain.minedCount = BigInteger.TWO
        awaitPending(f) { it?.mined == true }
        awaitConnects(2)
        assertTrue(connects.size >= 2)
        assertEquals(batch, connects.last())
    }

    @Test
    fun `once the wallet stops following the call, it's looked up on chain until it's mined`() {
        val chain = FakeChain()
        val f = SwarmFunding(File(tmp.root, "funding.json"), connect = { connects += it; true }, spends = emptyFlow(), chain = chain, checkEveryMs = 20)
        f.start(flowOf(status(SendStatus.Stage.Pending), status(SendStatus.Stage.Unconfirmed), null))
        awaitPending(f) { it?.tracked == false }
        val until = System.currentTimeMillis() + 5_000
        while (chain.reads < 2 && System.currentTimeMillis() < until) Thread.sleep(10)
        assertTrue(chain.reads >= 2)
        chain.receipt = receipt("0x1")
        awaitPending(f) { it?.mined == true }
        // The connect follows the record's update, on the loop's own thread.
        awaitConnects(1)
        assertEquals(listOf(batch), connects)
        val reads = chain.reads
        Thread.sleep(200)
        assertEquals(reads, chain.reads)
    }

    /** A funding whose connects are refused while [free] is false, as [StampClient.start] refuses them under other work. */
    private fun busyFunding(free: kotlinx.coroutines.flow.MutableStateFlow<Boolean>, chain: FakeChain? = null) =
        SwarmFunding(
            File(tmp.root, "funding.json"), connect = { if (free.value) { connects += it; true } else false },
            spends = emptyFlow(), chain = chain, confirmAfterMs = 30_000, now = { 0L }, connectFree = free,
        )

    @Test
    fun `a connect refused while the node is busy is started once it's free, and the card says so meanwhile`() {
        val free = kotlinx.coroutines.flow.MutableStateFlow(false)
        val f = busyFunding(free)
        f.start(emptyFlow())
        f.noteSend(status(SendStatus.Stage.Pending))
        // Mined during a publish's upload: the connect is refused, and owed.
        f.noteSend(status(SendStatus.Stage.Confirmed(1, null)))
        assertTrue(f.pending.value!!.mined)
        assertTrue(connects.isEmpty())
        assertEquals(batch, f.connectOwed.value)
        val light = NodeInfo(status = NodeStatus.Running, accountAddress = node, walletIdentity = true, lightMode = true)
        val owedText = pendingStampText(f.pending.value!!, light, StampClient.Spend.Idle, superseded = false, owed = true)
        assertTrue(owedText, owedText.contains("as soon as"))

        // The upload ends: connected, once.
        free.value = true
        awaitConnects(1)
        assertEquals(listOf(batch), connects)
        assertNull(f.connectOwed.value)
        free.value = false
        free.value = true
        Thread.sleep(100)
        assertEquals(1, connects.size)
        // Not owed any more (it ran; had it failed, the card offers Connect): no promise of connecting it.
        val waiting = pendingStampText(f.pending.value!!, light, StampClient.Spend.Idle, superseded = false, owed = false)
        assertTrue(waiting, waiting.startsWith("Mined. Connect adds it"))
    }

    @Test
    fun `an owed connect is dropped with its record`() {
        val free = kotlinx.coroutines.flow.MutableStateFlow(false)
        val f = busyFunding(free)
        f.start(emptyFlow())
        f.noteSend(status(SendStatus.Stage.Pending))
        f.noteSend(status(SendStatus.Stage.Confirmed(1, null)))
        assertEquals(batch, f.connectOwed.value)
        f.forget()
        assertNull(f.connectOwed.value)
        free.value = true
        Thread.sleep(100)
        assertTrue(connects.isEmpty())
    }

    @Test
    fun `an untracked call found mined while the node is busy is connected once it's free`() {
        val chain = FakeChain()
        funding().apply {
            noteSend(status(SendStatus.Stage.Unconfirmed))
            untrack()
        }
        val free = kotlinx.coroutines.flow.MutableStateFlow(false)
        val f = busyFunding(free, chain)
        f.start(emptyFlow())
        chain.receipt = receipt("0x1")
        chain.minedCount = BigInteger.TWO
        runBlocking { f.checkChain() }
        assertTrue(f.pending.value!!.mined)
        assertTrue(connects.isEmpty())
        assertEquals(batch, f.connectOwed.value)
        free.value = true
        awaitConnects(1)
        assertEquals(listOf(batch), connects)
    }

    @Test
    fun `the pool's price sizes the swap only when a quorum, a proof, or the user's own undisputed RPC gave it`() {
        val u = ChainTrustsForTest.unverified
        assertFalse(chainReadTrusted(u))
        assertTrue(chainReadTrusted(u.copy(level = ChainTrust.Level.VERIFIED, source = ChainSource.QUORUM, k = 3, m = 2)))
        assertTrue(chainReadTrusted(u.copy(level = ChainTrust.Level.VERIFIED, dissented = listOf("b.example"), k = 3, m = 2)))
        assertTrue(chainReadTrusted(u.copy(level = ChainTrust.Level.USER_CONFIGURED)))
        assertFalse(chainReadTrusted(u.copy(level = ChainTrust.Level.USER_CONFIGURED, dissented = listOf("b.example"))))
    }

    @Test
    fun `the card only tells the user to dismiss an unmined stamp when the chain shows it can never land`() {
        val light = NodeInfo(status = NodeStatus.Running, accountAddress = node, walletIdentity = true, lightMode = true)
        val p = SwarmFunding.Pending(node, batch, 17, 2, hash, mined = false, tracked = false)
        val failed = StampClient.Spend.Failed(StampClient.Kind.Connect, batch, "batch not found")
        val waiting = pendingStampText(p, light, failed, superseded = false)
        assertTrue(waiting, waiting.startsWith("Connecting it failed: batch not found"))
        assertTrue(waiting, waiting.contains("keeps checking"))
        assertFalse(waiting, waiting.contains("dismiss it"))
        assertTrue(pendingStampText(p, light, failed, superseded = true).contains("There's no stamp, so dismiss it."))
        // Another batch's connect is neither "connecting" nor a failure of this one.
        val other = SwarmFunder.batchId(ByteArray(32) { 9 })
        assertFalse(pendingStampText(p, light, StampClient.Spend.Running(StampClient.Kind.Connect, other), false).contains("connecting it"))
        assertEquals("The node is connecting it…", pendingStampText(p, light, StampClient.Spend.Running(StampClient.Kind.Connect, batch), false))
        assertFalse(pendingStampText(p, light, failed.copy(batchId = other), false).contains("failed"))
    }
}
