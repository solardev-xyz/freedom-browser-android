package baby.freedom.mobile.wallet

import baby.freedom.mobile.browser.HistoryItem
import baby.freedom.mobile.browser.explorerTxUrl
import baby.freedom.mobile.browser.historyItems
import baby.freedom.mobile.browser.receivedSubtitle
import baby.freedom.mobile.browser.receivedTitle
import baby.freedom.mobile.browser.transfersTo
import baby.freedom.mobile.chains.BuiltInChains
import baby.freedom.mobile.chains.rpc.ChainRpcException
import baby.freedom.mobile.chains.rpc.ChainSource
import baby.freedom.mobile.chains.rpc.ChainTrust
import baby.freedom.mobile.chains.rpc.WalletRpc
import java.math.BigInteger
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Received tokens (#441): log decoding, range chunking, the scan itself, persistence and the merged history. */
class IncomingTransfersTest {
    private val account = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266"
    private val sender = "0x70997970C51812dc3A010C7d01b50e0d17dc79C8"
    private val gnosis = BuiltInChains.GNOSIS
    private val xbzz = TokenRegistry.builtins.first { it.symbol == "xBZZ" }
    private val eure = TokenRegistry.builtins.first { it.symbol == "EURe" }
    private val gnosisTokens = TokenRegistry.builtins.filter { it.chainId == gnosis.id }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @get:Rule
    val tmp = TemporaryFolder()

    @After
    fun tearDown() = scope.cancel()

    private fun hash(n: Int) = "0x" + n.toString(16).padStart(64, '0')
    private fun word(v: BigInteger) = "0x" + v.toString(16).padStart(64, '0')
    private fun hex(n: Long) = "0x" + n.toString(16)

    private fun log(
        token: String = requireNotNull(xbzz.address),
        to: String = account,
        from: String = sender,
        amount: BigInteger = BigInteger.TEN.pow(16),
        block: Long = 1_000,
        tx: String = hash(1),
        logIndex: Long = 3,
        extraTopic: Boolean = false,
    ): JSONObject = JSONObject()
        .put("address", token.lowercase())
        .put(
            "topics",
            JSONArray().put(IncomingLogs.TRANSFER_TOPIC).put(IncomingLogs.topicOf(from)).put(IncomingLogs.topicOf(to))
                .apply { if (extraTopic) put(word(BigInteger.ONE)) },
        )
        .put("data", word(amount))
        .put("blockNumber", hex(block))
        .put("transactionHash", tx)
        .put("logIndex", hex(logIndex))
        .put("removed", false)

    private fun receiptFor(vararg logs: JSONObject, tx: String = hash(1), block: Long = 1_000, status: String = "0x1") = JSONObject()
        .put("transactionHash", tx)
        .put("status", status)
        .put("blockNumber", hex(block))
        .put("logs", JSONArray().apply { logs.forEach { put(it) } })

    // ---- decoding ----

    @Test
    fun `a transfer of a built-in token to the account decodes, sender checksummed`() {
        val c = IncomingLogs.decode(log(), account, gnosisTokens, 0L..2_000L)
        assertNotNull(c)
        c!!
        assertEquals(xbzz.address, c.tokenAddress)
        assertEquals(sender, c.from)
        assertEquals(BigInteger.TEN.pow(16), c.amount)
        assertEquals(1_000L, c.block)
        assertEquals(3L, c.logIndex)
        assertEquals(hash(1), c.hash)
    }

    @Test
    fun `logs that aren't a real transfer to the account are dropped`() {
        val range = 0L..2_000L
        // Another contract (a look-alike token), another recipient, zero value (poisoning spam).
        assertNull(IncomingLogs.decode(log(token = "0x" + "11".repeat(20)), account, gnosisTokens, range))
        assertNull(IncomingLogs.decode(log(to = sender), account, gnosisTokens, range))
        assertNull(IncomingLogs.decode(log(amount = BigInteger.ZERO), account, gnosisTokens, range))
        // An ERC-721 Transfer (tokenId indexed: four topics), a block outside the range asked for,
        // a log a reorg removed, and malformed fields.
        assertNull(IncomingLogs.decode(log(extraTopic = true), account, gnosisTokens, range))
        assertNull(IncomingLogs.decode(log(block = 5_000), account, gnosisTokens, range))
        assertNull(IncomingLogs.decode(log().put("removed", true), account, gnosisTokens, range))
        assertNull(IncomingLogs.decode(log().put("data", "0x1234"), account, gnosisTokens, range))
        assertNull(IncomingLogs.decode(log().put("transactionHash", "0xabc"), account, gnosisTokens, range))
        assertNull(IncomingLogs.decode(log().put("blockNumber", 1000), account, gnosisTokens, range))
        assertNull(IncomingLogs.decode("not a log", account, gnosisTokens, range))
        // A topic that isn't a padded address.
        val bad = log().apply { getJSONArray("topics").put(1, "0x" + "ff".repeat(32)) }
        assertNull(IncomingLogs.decode(bad, account, gnosisTokens, range))
    }

    @Test
    fun `the filter asks for the chain's tokens' transfers to the account over the range`() {
        val f = IncomingLogs.filter(account, gnosisTokens, 16L..255L)
        assertEquals("0x10", f.getString("fromBlock"))
        assertEquals("0xff", f.getString("toBlock"))
        assertEquals(gnosisTokens.map { it.address }, (0 until f.getJSONArray("address").length()).map { f.getJSONArray("address").getString(it) })
        val topics = f.getJSONArray("topics")
        assertEquals(IncomingLogs.TRANSFER_TOPIC, topics.getString(0))
        assertTrue(topics.isNull(1))
        assertEquals("0x000000000000000000000000f39fd6e51aad88f6f4ce6ab8827279cfffb92266", topics.getString(2))
    }

    @Test
    fun `a receipt confirms a candidate only with the same log in a successful transaction`() {
        val c = IncomingLogs.decode(log(), account, gnosisTokens, 0L..2_000L)!!
        assertTrue(IncomingLogs.confirms(c, receiptFor(log(logIndex = 1, token = requireNotNull(eure.address)), log())))
        assertFalse(IncomingLogs.confirms(c, receiptFor(log(), status = "0x0")))
        assertFalse(IncomingLogs.confirms(c, receiptFor(log(), block = 1_001)))
        assertFalse(IncomingLogs.confirms(c, receiptFor(log(amount = BigInteger.ONE))))
        assertFalse(IncomingLogs.confirms(c, receiptFor(log(logIndex = 4))))
        assertFalse(IncomingLogs.confirms(c, receiptFor(log(token = requireNotNull(eure.address)))))
        assertFalse(IncomingLogs.confirms(c, receiptFor(log(), tx = hash(2))))
        assertFalse(IncomingLogs.confirms(c, receiptFor()))
    }

    // ---- range chunking ----

    @Test
    fun `about 30 days of blocks per chain`() {
        assertEquals(518_400L, IncomingScan.windowBlocks(TokenRegistry.GNOSIS))
        assertEquals(216_000L, IncomingScan.windowBlocks(TokenRegistry.ETHEREUM))
    }

    @Test
    fun `a first scan reads the newest chunk, then new blocks, then back to the floor, one unbroken range`() {
        var s = IncomingScan.start(account, gnosis.id, head = 1_000_000).copy(floor = 975_000)
        assertEquals(975_000L, s.floor)
        val ranges = mutableListOf<LongRange>()
        var head = 1_000_000L
        while (true) {
            val r = IncomingScan.nextRange(s, head) ?: break
            ranges += r
            s = IncomingScan.read(s, r)
            if (ranges.size == 2) head = 1_000_100 // the head moves on mid-scan
        }
        assertEquals(
            listOf(
                990_001L..1_000_000L, // newest first
                980_001L..990_000L,
                1_000_001L..1_000_100L, // the new blocks before going further back
                975_000L..980_000L, // stops at the floor
            ),
            ranges,
        )
        assertEquals(975_000L, s.from)
        assertEquals(1_000_100L, s.to)
        assertTrue(s.caughtUp)
        // Every block from the floor to the head exactly once.
        assertEquals((975_000L..1_000_100L).count(), ranges.sumOf { it.count() })
        assertNull(IncomingScan.nextRange(s, 1_000_100))
        // Later on, only what's new.
        assertEquals(1_000_101L..1_000_150L, IncomingScan.nextRange(s, 1_000_150))
        assertEquals(1_000_101L..1_010_100L, IncomingScan.nextRange(s, 2_000_000))
    }

    @Test
    fun `the first scan of a young chain stops at block 0`() {
        val s = IncomingScan.start(account, gnosis.id, head = 300)
        assertEquals(0L, s.floor)
        assertEquals(0L..300L, IncomingScan.nextRange(s, 300))
        assertNull(IncomingScan.nextRange(IncomingScan.read(s, 0L..300L), 300))
    }

    @Test
    fun `a failed chunk halves the span down to the minimum, and it grows back after a run of reads`() {
        var s = IncomingScan.start(account, gnosis.id, head = 10_000_000)
        repeat(10) { s = IncomingScan.failed(s) }
        assertEquals(IncomingScan.MIN_SPAN, s.span)
        assertEquals((10_000_000L - IncomingScan.MIN_SPAN + 1)..10_000_000L, IncomingScan.nextRange(s, 10_000_000))
        repeat(IncomingScan.GROW_AFTER) { s = IncomingScan.read(s, IncomingScan.nextRange(s, 10_000_000)!!) }
        assertEquals(IncomingScan.MIN_SPAN * 2, s.span)
        repeat(IncomingScan.GROW_AFTER * 10) { s = IncomingScan.read(s, IncomingScan.nextRange(s, 10_000_000)!!) }
        assertEquals(IncomingScan.INITIAL_SPAN, s.span)
    }

    @Test
    fun `a head more than a window past what was read starts over with a first scan's window`() {
        val s = ScanState(account, gnosis.id, from = 100_000, to = 200_000, floor = 100_000)
        assertEquals(s, IncomingScan.rebase(s, 200_000 + IncomingScan.windowBlocks(gnosis.id)))
        val far = 5_000_000L
        val r = IncomingScan.rebase(s, far)
        assertNull(r.from)
        assertEquals(far - IncomingScan.windowBlocks(gnosis.id) + 1, r.floor)
        // Just over a window: only a window's worth before the head is read, after what was read before.
        val near = IncomingScan.rebase(s, 200_000 + IncomingScan.windowBlocks(gnosis.id) + 5)
        assertEquals(200_006L, near.floor)
    }

    // ---- merge ----

    private fun transfer(at: Long, tx: Int = 1, to: String = account) = IncomingTransfer(
        account = to, chainId = gnosis.id, chainName = gnosis.name, explorerUrl = gnosis.explorerUrl,
        tokenAddress = requireNotNull(xbzz.address), tokenSymbol = "xBZZ", tokenDecimals = 16, from = sender,
        amount = BigInteger("125000000000000000"), hash = hash(tx), logIndex = 0, block = 1_000, at = at,
    )

    private fun sent(at: Long, tx: Int) = TxRecord(
        hash = hash(tx), chainId = gnosis.id, chainName = gnosis.name, chainSymbol = "xDAI", chainDecimals = 18,
        explorerUrl = gnosis.explorerUrl, from = account, to = sender, tokenAddress = null, tokenSymbol = "xDAI",
        tokenDecimals = 18, amount = BigInteger.ONE, nonce = BigInteger.ZERO, sentAt = at, status = TxRecord.Status.CONFIRMED,
    )

    @Test
    fun `sends and received transfers merge newest first, a send first at the same moment`() {
        val items = historyItems(
            sent = listOf(sent(500, 10), sent(300, 11), sent(100, 12)),
            received = listOf(transfer(400, 20), transfer(300, 21), transfer(50, 22)),
        )
        assertEquals(
            listOf("sent:10", "received:20", "sent:11", "received:21", "sent:12", "received:22"),
            items.map {
                when (it) {
                    is HistoryItem.Sent -> "sent:" + it.record.hash.trimStart('0', 'x').toInt(16)
                    is HistoryItem.Received -> "received:" + it.transfer.hash.trimStart('0', 'x').toInt(16)
                }
            },
        )
        assertEquals(items.size, items.map { it.key }.toSet().size)
    }

    @Test
    fun `only the account's own received transfers are listed`() {
        val mine = transfer(1, 1)
        val other = transfer(2, 2, to = sender)
        assertEquals(listOf(mine), transfersTo(listOf(mine, other), account.lowercase()))
        assertEquals(emptyList<IncomingTransfer>(), transfersTo(listOf(mine), null))
    }

    @Test
    fun `a received transfer reads as received, with its chain, time and explorer page`() {
        val t = transfer(at = 1_700_000_000_000)
        assertEquals("Received 12.5 xBZZ", receivedTitle(t))
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
        assertEquals("Received · Gnosis Chain · 2023-11-14 22:13", receivedSubtitle(t, format))
        assertEquals("https://gnosisscan.io/tx/${hash(1)}", explorerTxUrl(t))
    }

    // ---- the scan ----

    private fun trust(level: ChainTrust.Level) = ChainTrust(level, ChainSource.QUORUM, emptyList(), emptyList(), emptyList(), 3, 2, null)
    private val verified = trust(ChainTrust.Level.VERIFIED)
    private val lone = trust(ChainTrust.Level.UNVERIFIED)

    /** A Gnosis whose logs and receipts are [logs]; records every range asked for. */
    private class FakeReads(var head: Long, val logs: MutableList<JSONObject> = mutableListOf()) : IncomingReads {
        var headTrust: ChainTrust? = null
        var receiptTrust: ChainTrust? = null
        var timeTrust: ChainTrust? = null
        val receipts = mutableMapOf<String, JSONObject?>()
        val asked = java.util.Collections.synchronizedList(mutableListOf<LongRange>())

        /** Ranges wider than this are refused, like an RPC's cap. */
        var maxRange = Long.MAX_VALUE
        var down = false
        var gate: CompletableDeferred<Unit>? = null
        val entered = CompletableDeferred<Unit>()
        lateinit var ok: ChainTrust

        override suspend fun blockNumber(chainId: Long) = WalletRpc.Reading(head, headTrust ?: ok)

        override suspend fun logs(chainId: Long, filter: JSONObject): WalletRpc.Reading<JSONArray> {
            entered.complete(Unit)
            gate?.await()
            val from = filter.getString("fromBlock").substring(2).toLong(16)
            val to = filter.getString("toBlock").substring(2).toLong(16)
            asked += from..to
            if (down) throw ChainRpcException.AllSourcesFailed(listOf("direct: down"), null)
            if (to - from + 1 > maxRange) throw ChainRpcException.AllSourcesFailed(listOf("direct: range too large"), null)
            val out = JSONArray()
            logs.filter { it.getString("blockNumber").substring(2).toLong(16) in from..to }.forEach { out.put(it) }
            return WalletRpc.Reading(out, ok)
        }

        override suspend fun receipt(chainId: Long, hash: String): WalletRpc.Reading<JSONObject?> {
            val r = if (receipts.containsKey(hash)) {
                receipts[hash]
            } else {
                logs.filter { it.getString("transactionHash") == hash }.takeIf { it.isNotEmpty() }?.let { l ->
                    JSONObject().put("transactionHash", hash).put("status", "0x1").put("blockNumber", l[0].getString("blockNumber"))
                        .put("logs", JSONArray(l))
                }
            }
            return WalletRpc.Reading(r, receiptTrust ?: ok)
        }

        override suspend fun blockTimestamp(chainId: Long, block: Long) = WalletRpc.Reading(1_700_000_000L + block * 5, timeTrust ?: ok)
    }

    private fun fake(head: Long, vararg logs: JSONObject) = FakeReads(head, logs.toMutableList()).also { it.ok = verified }

    private fun incoming(reads: IncomingReads, store: IncomingStore = IncomingStore.None, uptime: () -> Long = { 0L }) =
        IncomingTransfers(reads, scope, store, uptime = uptime, pauseMs = 0)

    @Test
    fun `a scan finds a transfer, proves it by its receipt and lists it with its block's time`() = runBlocking {
        val head = 2_000_000L
        val reads = fake(head, log(block = head - 50_000))
        val inc = incoming(reads)
        inc.scan(account, listOf(gnosis))
        val t = inc.transfers.value.single()
        assertEquals(account, t.account)
        assertEquals("xBZZ", t.tokenSymbol)
        assertEquals(sender, t.from)
        assertEquals(head - 50_000, t.block)
        assertEquals((1_700_000_000L + (head - 50_000) * 5) * 1000, t.at)
        assertEquals(gnosis.name, t.chainName)
        // The whole window, newest chunk first, ending 20 blocks under the head (reorg margin).
        val safe = head - IncomingScan.margin(gnosis.id)
        assertEquals((safe - IncomingScan.INITIAL_SPAN + 1)..safe, reads.asked.first())
        assertEquals(IncomingScan.windowBlocks(gnosis.id), reads.asked.sumOf { r -> r.count().toLong() })
        assertTrue(inc.catchingUp.value.isEmpty())
    }

    @Test
    fun `a second scan reads only the new blocks, and not at all right after the last`() = runBlocking {
        val reads = fake(2_000_000L)
        var now = 0L
        val inc = incoming(reads, uptime = { now })
        inc.scan(account, listOf(gnosis))
        val first = reads.asked.size
        // Straight after: caught up, nothing to prove, read moments ago.
        inc.scan(account, listOf(gnosis))
        assertEquals(first, reads.asked.size)
        now += IncomingTransfers.MIN_SCAN_INTERVAL_MS
        reads.head += 100
        reads.logs += log(block = reads.head - 30, tx = hash(9))
        inc.scan(account, listOf(gnosis))
        assertEquals(listOf((2_000_000L - 20 + 1)..(reads.head - 20)), reads.asked.drop(first))
        assertEquals(hash(9), inc.transfers.value.single().hash)
    }

    @Test
    fun `a transfer whose receipt isn't undisputed waits, and one its receipt doesn't show is dropped`() = runBlocking {
        val head = 1_000_000L
        val reads = fake(head, log(block = head - 100, tx = hash(1)), log(block = head - 200, tx = hash(2)))
        reads.receiptTrust = lone
        val inc = incoming(reads)
        inc.scan(account, listOf(gnosis))
        assertTrue(inc.transfers.value.isEmpty())
        // Proven later on: listed then. The other's receipt shows no such log (a node made it up).
        reads.receiptTrust = verified
        reads.receipts[hash(2)] = receiptFor(tx = hash(2), block = head - 200)
        inc.scan(account, listOf(gnosis))
        assertEquals(listOf(hash(1)), inc.transfers.value.map { t -> t.hash })
        // And the made-up one isn't asked about again.
        reads.receipts[hash(2)] = null
        inc.scan(account, listOf(gnosis))
        assertEquals(listOf(hash(1)), inc.transfers.value.map { t -> t.hash })
    }

    @Test
    fun `a proven transfer whose block time only one RPC vouches for waits for a better answer`() = runBlocking {
        val head = 1_000_000L
        val reads = fake(head, log(block = head - 100))
        reads.timeTrust = lone
        val inc = incoming(reads)
        inc.scan(account, listOf(gnosis))
        assertTrue(inc.transfers.value.isEmpty())
        reads.timeTrust = verified
        inc.scan(account, listOf(gnosis))
        assertEquals((1_700_000_000L + (head - 100) * 5) * 1000, inc.transfers.value.single().at)
    }

    @Test
    fun `a head only one public RPC vouches for isn't scanned to`() = runBlocking {
        val reads = fake(1_000_000L, log(block = 999_000))
        reads.headTrust = lone
        val inc = incoming(reads)
        inc.scan(account, listOf(gnosis))
        assertTrue(reads.asked.isEmpty())
        assertTrue(inc.transfers.value.isEmpty())
    }

    @Test
    fun `a refused range shrinks the chunks until the RPCs take them`() = runBlocking {
        val head = 3_000_000L
        val reads = fake(head, log(block = head - 1_000))
        reads.maxRange = 2_000
        val inc = incoming(reads)
        inc.scan(account, listOf(gnosis))
        // 10000 → 5000 → 2500 refused, 1250 taken.
        assertEquals(listOf(10_000L, 5_000L, 2_500L, 1_250L), reads.asked.take(4).map { r -> r.count().toLong() })
        assertEquals(hash(1), inc.transfers.value.single().hash)
    }

    @Test
    fun `no RPC answering stops the scan after a few tries and it resumes where it was`() = runBlocking {
        val head = 3_000_000L
        val reads = fake(head)
        reads.down = true
        val inc = incoming(reads)
        inc.scan(account, listOf(gnosis))
        assertEquals(IncomingTransfers.MAX_FAILURES_PER_RUN, reads.asked.size)
        reads.down = false
        reads.asked.clear()
        inc.scan(account, listOf(gnosis))
        assertTrue(reads.asked.isNotEmpty())
        assertEquals(head - 20, reads.asked.first().last)
    }

    @Test
    fun `a wipe during a scan keeps what it was reading out of the emptied list`() = runBlocking {
        val head = 1_000_000L
        val reads = fake(head, log(block = head - 100))
        val gate = CompletableDeferred<Unit>()
        reads.gate = gate
        val store = FileIncomingStore(tmp.root.resolve("incoming.json"))
        val inc = incoming(reads, store)
        inc.awaitLoaded()
        val job = scope.launch { inc.scan(account, listOf(gnosis)) }
        withTimeout(5_000) { reads.entered.await() }
        inc.wipeNow()
        gate.complete(Unit)
        job.join()
        assertTrue(inc.transfers.value.isEmpty())
        inc.persistNow()
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun `a wipe during one chain's scan stops the scan's later chains from saving the removed account`() = runBlocking {
        val head = 1_000_000L
        val reads = fake(head, log(block = head - 100))
        val gate = CompletableDeferred<Unit>()
        reads.gate = gate
        val store = FileIncomingStore(tmp.root.resolve("incoming.json"))
        val inc = incoming(reads, store)
        inc.awaitLoaded()
        val job = scope.launch { inc.scan(account, listOf(BuiltInChains.ETHEREUM, gnosis)) }
        withTimeout(5_000) { reads.entered.await() }
        inc.wipeNow()
        reads.gate = null
        gate.complete(Unit)
        job.join()
        // Only Ethereum's one gated chunk was read; Gnosis wasn't started at all.
        assertEquals(1, reads.asked.size)
        assertTrue(inc.transfers.value.isEmpty())
        inc.persistNow()
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun `candidates that stay unproven don't keep later ones from being checked`() = runBlocking {
        val head = 1_000_000L
        val stuck = IncomingTransfers.MAX_VERIFY_PER_RUN
        // The stuck ones are older, so they sit at the head of the candidate list.
        val logs = (1..stuck).map { log(block = head - 10_000 + it, tx = hash(it)) } + log(block = head - 100, tx = hash(500))
        val reads = fake(head, *logs.toTypedArray())
        val disputed = object : IncomingReads by reads {
            override suspend fun receipt(chainId: Long, tx: String): WalletRpc.Reading<JSONObject?> {
                val r = reads.receipt(chainId, tx)
                return if (tx == hash(500)) r else WalletRpc.Reading(r.value, lone)
            }
        }
        var now = 0L
        val inc = incoming(disputed, uptime = { now })
        inc.scan(account, listOf(gnosis))
        assertTrue(inc.transfers.value.isEmpty())
        // The next scan asks about the one not asked yet before the stuck ones again.
        now += IncomingTransfers.MIN_SCAN_INTERVAL_MS
        inc.scan(account, listOf(gnosis))
        assertEquals(listOf(hash(500)), inc.transfers.value.map { t -> t.hash })
    }

    @Test
    fun `more transfers than the candidate list holds are all listed, the newest included`() = runBlocking {
        val head = 2_000_000L
        val count = IncomingTransfers.MAX_CANDIDATES + 10
        val logs = (0 until count).map { log(block = head - 5_100 - 5_000L * it, tx = hash(it + 1)) }
        val reads = fake(head, *logs.toTypedArray())
        var now = 0L
        val inc = incoming(reads, uptime = { now })
        repeat(10) {
            inc.scan(account, listOf(gnosis))
            now += IncomingTransfers.MIN_SCAN_INTERVAL_MS
        }
        assertEquals(logs.map { it.getString("transactionHash") }.toSet(), inc.transfers.value.map { t -> t.hash }.toSet())
        assertEquals(head - 5_100, inc.transfers.value.maxOf { t -> t.block })
    }

    @Test
    fun `a chunk finding more than the candidate list has room for is read again in smaller chunks`() = runBlocking {
        val head = 1_000_000L
        val count = IncomingTransfers.MAX_CANDIDATES + 5
        // All in the first 10,000-block chunk: it doesn't fit, so it's re-asked at 5,000 blocks.
        val logs = (0 until count).map { log(block = head - 100 - 150L * it, tx = hash(it + 1)) }
        val reads = fake(head, *logs.toTypedArray())
        var now = 0L
        val inc = incoming(reads, uptime = { now })
        inc.scan(account, listOf(gnosis))
        assertEquals(listOf(10_000L, 5_000L), reads.asked.take(2).map { r -> r.count().toLong() })
        repeat(10) {
            now += IncomingTransfers.MIN_SCAN_INTERVAL_MS
            inc.scan(account, listOf(gnosis))
        }
        assertEquals(count, inc.transfers.value.size)
    }

    @Test
    fun `a first scan that read nothing starts over a window under a much later head`() = runBlocking {
        val head = 3_000_000L
        val reads = fake(head)
        reads.down = true
        val inc = incoming(reads)
        inc.scan(account, listOf(gnosis))
        reads.down = false
        reads.asked.clear()
        reads.head = head + 5_000_000L
        // The failures shrank the chunks, so reading back takes a few runs; each stops at the window's floor.
        repeat(20) { inc.scan(account, listOf(gnosis)) }
        val safe = reads.head - IncomingScan.margin(gnosis.id)
        val windowFloor = safe - IncomingScan.windowBlocks(gnosis.id) + 1
        assertEquals(windowFloor, reads.asked.minOf { r -> r.first })
        assertTrue(inc.catchingUp.value.isEmpty())
        // And the rebase on its own: a never-read state's floor is lifted, a recent one's kept.
        val never = ScanState(account, gnosis.id, floor = 100)
        assertEquals(windowFloor, IncomingScan.rebase(never, safe).floor)
        val recent = ScanState(account, gnosis.id, floor = safe - 10)
        assertEquals(recent, IncomingScan.rebase(recent, safe))
    }

    @Test
    fun `a wipe before the file has been read still deletes it`() = runBlocking {
        val file = tmp.root.resolve("wallet/incoming.json")
        val reads = fake(1_000_000L)
        val saved = ScanState(account, gnosis.id, from = 10, to = 20, floor = 5, transfers = listOf(transfer(1_000)))
        assertTrue(FileIncomingStore(file).save(listOf(saved)))
        assertTrue(file.exists())
        val gate = java.util.concurrent.CountDownLatch(1)
        val slow = object : IncomingStore {
            val inner = FileIncomingStore(file)
            override fun save(scans: List<ScanState>) = inner.save(scans)
            override fun load(): List<ScanState> {
                gate.await()
                return inner.load()
            }
        }
        val again = incoming(reads, slow)
        again.wipe()
        gate.countDown()
        again.awaitLoaded()
        withTimeout(5_000) { while (file.exists()) kotlinx.coroutines.delay(10) }
        assertTrue(again.transfers.value.isEmpty())
        assertTrue(FileIncomingStore(file).load().isEmpty())
    }

    @Test
    fun `what was read and found survives a restart`() = runBlocking {
        val head = 1_000_000L
        val file = tmp.root.resolve("wallet/incoming.json")
        val reads = fake(head, log(block = head - 100))
        val first = incoming(reads, FileIncomingStore(file))
        first.scan(account, listOf(gnosis))
        first.persistNow()
        val again = incoming(reads, FileIncomingStore(file))
        again.awaitLoaded()
        assertEquals(first.transfers.value, again.transfers.value)
        reads.asked.clear()
        reads.head += 10
        again.scan(account, listOf(gnosis))
        // Only the ten new blocks: the window was read before the restart.
        assertEquals(listOf((head - 19)..(head - 10)), reads.asked)
    }

    @Test
    fun `the codec drops what it can't read and keeps the rest`() {
        val s = ScanState(
            account, gnosis.id, from = 10, to = 20, floor = 5, span = 1_000, streak = 2,
            candidates = listOf(IncomingLogs.decode(log(block = 15), account, gnosisTokens, 0L..100L)!!),
            transfers = listOf(transfer(1_000)),
        )
        val json = IncomingCodec.encode(listOf(s))
        assertEquals(listOf(s), IncomingCodec.decode(json))
        json.getJSONArray("scans").put(JSONObject().put("account", account))
        json.getJSONArray("scans").getJSONObject(0).getJSONArray("transfers").put(JSONObject().put("hash", "0x1"))
        assertEquals(listOf(s), IncomingCodec.decode(json))
        assertEquals(emptyList<ScanState>(), IncomingCodec.decode(JSONObject().put("version", 2)))
    }
}
