package baby.freedom.mobile.wallet

import baby.freedom.mobile.browser.explorerTxUrl
import baby.freedom.mobile.browser.txRecordsFrom
import baby.freedom.mobile.browser.txStatusText
import baby.freedom.mobile.browser.txSubtitle
import baby.freedom.mobile.browser.txTitle
import baby.freedom.mobile.chains.BuiltInChains
import baby.freedom.mobile.chains.Chain
import baby.freedom.mobile.chains.rpc.ChainDataRouter
import baby.freedom.mobile.chains.rpc.RpcTransport
import baby.freedom.mobile.chains.rpc.WalletRpc
import baby.freedom.mobile.ens.hexToBytes
import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The transaction history (#109): what's recorded, how it settles, what survives a restart, and how it reads. */
class TxHistoryTest {
    // Hardhat account 0: a published test key, never a funded one.
    private val key = "ac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80".hexToBytes()
    private val from = WalletAccount(0, "Account 1", "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266")
    private val to = "0x70997970C51812dc3A010C7d01b50e0d17dc79C8"
    private val gnosis = BuiltInChains.GNOSIS.copy(rpcUrls = listOf("https://a.example", "https://b.example", "https://c.example"))
    private val xdai = TokenRegistry.native(gnosis)
    private val xbzz = TokenRegistry.builtins.first { it.symbol == "xBZZ" }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @get:Rule
    val tmp = TemporaryFolder()

    @After
    fun tearDown() = scope.cancel()

    private companion object {
        const val CONFIRM_TIMEOUT_MS = 300L
    }

    private fun ok(block: Long = 16) = """{"status":"0x1","blockNumber":"0x${block.toString(16)}","gasUsed":"0x5208","effectiveGasPrice":"0x2"}"""

    /** A chain every RPC agrees on, with a receipt per hash. */
    private class FakeChain(private val chain: Chain) {
        @Volatile var nonce = 7L

        /** Mined count (`latest`); null: the same as [nonce]. */
        @Volatile var mined: Long? = null

        @Volatile var down = false
        @Volatile var refuseBroadcast = false
        val receipts = java.util.concurrent.ConcurrentHashMap<String, String>()
        val methods = java.util.Collections.synchronizedList(mutableListOf<String>())

        fun answer(req: JSONObject): String {
            val method = req.getString("method")
            methods += method
            fun q(v: Long) = "\"result\":\"0x${v.toString(16)}\""
            return when (method) {
                "eth_getBalance" -> "\"result\":\"0x" + BigInteger.TEN.pow(18).toString(16) + "\""
                "eth_call" -> "\"result\":\"0x" + BigInteger.valueOf(5_000).toString(16).padStart(64, '0') + "\""
                "eth_getTransactionCount" ->
                    q(if (req.getJSONArray("params").optString(1) == "latest") mined ?: nonce else nonce)
                "eth_getBlockByNumber" -> "\"result\":{\"number\":\"0x10\",\"baseFeePerGas\":\"0xe\"}"
                "eth_maxPriorityFeePerGas" -> q(1)
                "eth_gasPrice" -> q(3)
                "eth_estimateGas" -> q(21_000)
                "eth_sendRawTransaction" -> if (refuseBroadcast) "\"error\":{\"code\":-32000,\"message\":\"nonce too low\"}" else "\"result\":\"0x" + "ab".repeat(32) + "\""
                "eth_getTransactionReceipt" -> "\"result\":" + (receipts[req.getJSONArray("params").getString(0).lowercase()] ?: "null")
                else -> "\"error\":{\"code\":-32601,\"message\":\"no such method\"}"
            }
        }

        fun rpc() = WalletRpc(
            ChainDataRouter(
                chains = { listOf(chain) },
                transport = RpcTransport { _, body, _ ->
                    if (down) throw IOException("down")
                    withReceiptHash(JSONObject(body), """{"jsonrpc":"2.0","id":1,${answer(JSONObject(body))}}""")
                },
            ),
        )
    }

    private val now = AtomicLong(1_790_000_000_000L)

    private fun history(chain: FakeChain, store: TxHistoryStore = TxHistoryStore.None) =
        TxHistory(chain.rpc(), scope, store, clock = { now.get() })

    /** The senders' own clock: still unless a test moves it, so no receipt wait runs out on its own on a slow runner. */
    private val senderClock = AtomicLong(1_700_000_000_000L)

    private fun sender(chain: FakeChain, history: TxHistory) =
        WalletSender(chain.rpc(), scope, { senderClock.get() }, pollMs = 10, confirmTimeoutMs = CONFIRM_TIMEOUT_MS, history = history)
            .also { runBlocking { it.awaitRestored() } }

    private fun signer(): (EthTransaction) -> EthTransaction.Signed = { tx -> tx.sign(key.copyOf(), from.address) }

    private fun request(token: Token = xdai, amount: Long = 1) = SendRequest(gnosis, token, from, to, BigInteger.valueOf(amount))

    /** Until [status] reaches a stage [predicate] takes; waiting for Unconfirmed, [senderClock] is moved past the receipt wait. */
    private suspend fun WalletSender.awaitStage(predicate: (SendStatus.Stage) -> Boolean): SendStatus =
        withTimeout(5_000) {
            suspend fun reached() = status.first { it != null && predicate(it.stage) }!!
            if (!predicate(SendStatus.Stage.Unconfirmed)) return@withTimeout reached()
            var seen: SendStatus? = null
            while (seen == null) {
                seen = withTimeoutOrNull(10) { reached() }
                if (seen == null) senderClock.addAndGet(CONFIRM_TIMEOUT_MS)
            }
            seen
        }

    private suspend fun TxHistory.awaitRecord(hash: String, predicate: (TxRecord) -> Boolean = { true }): TxRecord =
        withTimeout(5_000) { records.first { list -> list.any { it.hash == hash && predicate(it) } }.first { it.hash == hash } }

    private fun record(
        hash: String = "0x" + "11".repeat(32),
        nonce: Long = 7,
        status: TxRecord.Status = TxRecord.Status.PENDING,
        sentAt: Long = now.get(),
        from: String = this.from.address,
    ) = TxRecord(
        hash = hash, chainId = gnosis.id, chainName = gnosis.name, chainSymbol = gnosis.symbol, chainDecimals = 18,
        explorerUrl = gnosis.explorerUrl, from = from, to = to, tokenAddress = null, tokenSymbol = "xDAI", tokenDecimals = 18,
        amount = BigInteger("1500000000000000000"), nonce = BigInteger.valueOf(nonce), sentAt = sentAt, status = status,
    )

    /** A store in memory that a second [TxHistory] can load, as the file outlives a process. */
    private class MemoryStore(@Volatile var saved: List<TxRecord> = emptyList()) : TxHistoryStore {
        override fun save(records: List<TxRecord>): Boolean {
            saved = records
            return true
        }
        override fun load() = saved
    }

    // ---- recorded from the send flow ----

    @Test
    fun `a send is recorded pending once it's out, and confirmed with its block and fee`() = runBlocking<Unit> {
        val chain = FakeChain(gnosis)
        val h = history(chain)
        val s = sender(chain, h)
        val quote = s.prepare(request(amount = 42))
        s.submit(quote, signer())
        val hash = s.awaitStage { it == SendStatus.Stage.Pending }.hash!!
        val pending = h.awaitRecord(hash)
        assertEquals(TxRecord.Status.PENDING, pending.status)
        assertEquals(BigInteger.valueOf(42), pending.amount)
        assertEquals(to, pending.to)
        assertEquals(from.address, pending.from)
        assertEquals(BigInteger.valueOf(7), pending.nonce)
        assertEquals("xDAI", pending.tokenSymbol)
        assertEquals(gnosis.name, pending.chainName)
        chain.receipts[hash.lowercase()] = ok(0x2e3f070)
        val confirmed = h.awaitRecord(hash) { it.status == TxRecord.Status.CONFIRMED }
        assertEquals(0x2e3f070L, confirmed.block)
        assertEquals(BigInteger.valueOf(21_000 * 2), confirmed.feePaid)
        assertEquals(now.get(), confirmed.settledAt)
        assertEquals(1, h.records.value.size)
    }

    @Test
    fun `a token transfer records the person paid, not the contract, and a revert is failed`() = runBlocking<Unit> {
        val chain = FakeChain(gnosis)
        val h = history(chain)
        val s = sender(chain, h)
        s.submit(s.prepare(request(xbzz, 5)), signer())
        val hash = s.awaitStage { it == SendStatus.Stage.Pending }.hash!!
        chain.receipts[hash.lowercase()] = """{"status":"0x0","blockNumber":"0x10","gasUsed":"0x100","effectiveGasPrice":"0x2"}"""
        val failed = h.awaitRecord(hash) { it.status == TxRecord.Status.FAILED }
        assertEquals(to, failed.to)
        assertEquals(xbzz.address, failed.tokenAddress)
        assertEquals("xBZZ", failed.tokenSymbol)
        assertEquals(16L, failed.block)
        assertEquals(BigInteger.valueOf(512), failed.feePaid)
    }

    @Test
    fun `a send that may have gone out is recorded pending, one certainly not sent never is`() = runBlocking<Unit> {
        val chain = FakeChain(gnosis)
        val h = history(chain)
        val s = sender(chain, h)
        val quote = s.prepare(request())
        // No RPC answering the broadcast: one may have taken it.
        chain.down = true
        s.submit(quote, signer())
        val failed = s.awaitStage { it is SendStatus.Stage.Failed }
        assertTrue((failed.stage as SendStatus.Stage.Failed).mayHaveGone)
        assertEquals(TxRecord.Status.PENDING, h.awaitRecord(failed.hash!!).status)
        s.discard()

        // Every node refuses it outright: "Not sent", and nothing is recorded.
        chain.down = false
        chain.refuseBroadcast = true
        val h2 = history(chain)
        val s2 = sender(chain, h2)
        s2.submit(s2.prepare(request()), signer())
        val notSent = s2.awaitStage { it is SendStatus.Stage.Failed }
        assertFalse((notSent.stage as SendStatus.Stage.Failed).mayHaveGone)
        // The history hears each stage before it's shown: nothing can still be on its way.
        assertTrue(h2.records.value.isEmpty())
    }

    @Test
    fun `stop tracking leaves the send pending in the history, and once its nonce is used elsewhere it reads replaced`() = runBlocking<Unit> {
        val chain = FakeChain(gnosis)
        val h = history(chain)
        val s = sender(chain, h)
        s.submit(s.prepare(request()), signer())
        val first = s.awaitStage { it == SendStatus.Stage.Unconfirmed }.hash!!
        s.discard()
        assertEquals(TxRecord.Status.PENDING, h.records.value.single().status)
        // Still in a pool: pending 8, mined 7. The replacement takes nonce 7.
        chain.nonce = 8
        chain.mined = 7
        h.refresh()
        assertEquals(TxRecord.Status.PENDING, h.records.value.single().status)
        val next = s.prepare(request(amount = 2))
        s.submit(next, signer())
        val second = s.awaitStage { it == SendStatus.Stage.Pending }.hash!!
        chain.receipts[second.lowercase()] = ok()
        chain.mined = 8
        h.awaitRecord(second) { it.status == TxRecord.Status.CONFIRMED }
        h.refresh()
        val old = h.records.value.first { it.hash == first }
        assertEquals(TxRecord.Status.REPLACED, old.status)
        assertEquals(TxRecord.Status.CONFIRMED, h.records.value.first { it.hash == second }.status)
    }

    // ---- settled from the chain ----

    @Test
    fun `refresh settles a pending record from its receipt, and leaves it while there's none`() = runBlocking<Unit> {
        val chain = FakeChain(gnosis)
        val store = MemoryStore(listOf(record()))
        val h = history(chain, store)
        withTimeout(5_000) { h.records.first { it.isNotEmpty() } }
        h.refresh()
        assertEquals(TxRecord.Status.PENDING, h.records.value.single().status)
        chain.receipts["0x" + "11".repeat(32)] = ok(99)
        h.refresh()
        val r = h.records.value.single()
        assertEquals(TxRecord.Status.CONFIRMED, r.status)
        assertEquals(99L, r.block)
        withTimeout(5_000) { while (store.saved.single().status != TxRecord.Status.CONFIRMED) delay(10) }
    }

    @Test
    fun `a record that can't be read stays as it was`() = runBlocking<Unit> {
        val chain = FakeChain(gnosis)
        chain.down = true
        val h = history(chain, MemoryStore(listOf(record())))
        withTimeout(5_000) { h.records.first { it.isNotEmpty() } }
        // No RPC answering: worth asking again later.
        assertTrue(h.refresh())
        assertEquals(TxRecord.Status.PENDING, h.records.value.single().status)
        // Its chain removed from Settings: the router doesn't know it, and nothing
        // could be read for it however often the page asked, so it isn't polled.
        val gone = history(FakeChain(BuiltInChains.ETHEREUM.copy(rpcUrls = listOf("https://x.example"))), MemoryStore(listOf(record())))
        withTimeout(5_000) { gone.records.first { it.isNotEmpty() } }
        assertFalse(gone.refresh())
        assertEquals(TxRecord.Status.PENDING, gone.records.value.single().status)
    }

    @Test
    fun `a receipt that turns up just after the nonce moved on is read as mined, not replaced`() = runBlocking<Unit> {
        val chain = FakeChain(gnosis)
        chain.mined = 8
        val hash = "0x" + "11".repeat(32)
        var reads = 0
        val rpc = WalletRpc(
            ChainDataRouter(
                chains = { listOf(gnosis) },
                transport = RpcTransport { _, body, _ ->
                    val req = JSONObject(body)
                    // The first receipt read is from before it landed; the one after the nonce read sees it.
                    if (req.getString("method") == "eth_getTransactionReceipt" && ++reads > 1) chain.receipts[hash] = ok()
                    withReceiptHash(req, """{"jsonrpc":"2.0","id":1,${chain.answer(req)}}""")
                },
            ),
        )
        val h = TxHistory(rpc, scope, MemoryStore(listOf(record(hash))), clock = { now.get() })
        withTimeout(5_000) { h.records.first { it.isNotEmpty() } }
        h.refresh()
        assertEquals(TxRecord.Status.CONFIRMED, h.records.value.single().status)
    }

    @Test
    fun `a replaced record whose receipt turns up soon after becomes confirmed, and long after it is not asked about`() = runBlocking<Unit> {
        val chain = FakeChain(gnosis)
        chain.mined = 8
        val hash = "0x" + "11".repeat(32)
        val h = history(chain, MemoryStore(listOf(record(hash))))
        withTimeout(5_000) { h.records.first { it.isNotEmpty() } }
        // Judged replaced, and nothing pending any more: the refresh still asks to be run
        // again, so the page keeps re-reading the receipt through the recheck window.
        assertTrue(h.refresh())
        assertEquals(TxRecord.Status.REPLACED, h.records.value.single().status)
        now.addAndGet(TxHistory.RECHECK_REPLACED_MS / 2)
        assertTrue(h.refresh())
        assertEquals(TxRecord.Status.REPLACED, h.records.value.single().status)
        // The node that said "no receipt" was behind.
        chain.receipts[hash] = ok(20)
        now.addAndGet(TxHistory.RECHECK_REPLACED_MS / 2 - 1)
        // Settled for good: nothing left to poll for.
        assertFalse(h.refresh())
        assertEquals(TxRecord.Status.CONFIRMED, h.records.value.single().status)

        val later = history(chain, MemoryStore(listOf(record(hash).copy(status = TxRecord.Status.REPLACED, settledAt = now.get()))))
        withTimeout(5_000) { later.records.first { it.isNotEmpty() } }
        now.addAndGet(TxHistory.RECHECK_REPLACED_MS)
        chain.methods.clear()
        assertFalse(later.refresh())
        assertEquals(TxRecord.Status.REPLACED, later.records.value.single().status)
        assertTrue(chain.methods.isEmpty())
    }

    @Test
    fun `a refresh begun before the file is read waits for it, so a replaced record it holds is still rechecked`() = runBlocking<Unit> {
        val chain = FakeChain(gnosis)
        chain.mined = 8
        val hash = "0x" + "11".repeat(32)
        val gate = java.util.concurrent.CountDownLatch(1)
        val store = object : TxHistoryStore {
            override fun save(records: List<TxRecord>) = true
            override fun load(): List<TxRecord> {
                gate.await()
                return listOf(record(hash).copy(status = TxRecord.Status.REPLACED, settledAt = now.get()))
            }
        }
        val h = history(chain, store)
        // The page's first poll, begun while the file is still being read.
        val first = async(Dispatchers.Default) { h.refresh() }
        delay(100)
        assertFalse(first.isCompleted)
        gate.countDown()
        // Nothing pending, but the replaced record is inside its recheck window: poll again.
        assertTrue(withTimeout(5_000) { first.await() })
        chain.receipts[hash] = ok(20)
        assertFalse(h.refresh())
        assertEquals(TxRecord.Status.CONFIRMED, h.records.value.single().status)
    }

    @Test
    fun `a record sent too long ago for its missing receipt to mean anything reads unknown, not replaced`() = runBlocking<Unit> {
        val chain = FakeChain(gnosis)
        chain.mined = 8
        val young = "0x" + "11".repeat(32)
        val old = "0x" + "22".repeat(32)
        val h = history(
            chain,
            MemoryStore(
                listOf(
                    record(young, sentAt = now.get() - TxHistory.REPLACED_JUDGE_MAX_AGE_MS + 1),
                    // A node with a pruned transaction index says "no receipt" for one mined months ago.
                    record(old, sentAt = now.get() - TxHistory.REPLACED_JUDGE_MAX_AGE_MS),
                ),
            ),
        )
        withTimeout(5_000) { h.records.first { it.size == 2 } }
        h.refresh()
        assertEquals(TxRecord.Status.REPLACED, h.records.value.first { it.hash == young }.status)
        val unknown = h.records.value.first { it.hash == old }
        assertEquals(TxRecord.Status.UNKNOWN, unknown.status)
        assertEquals("Outcome unknown", txStatusText(unknown).first)
        // Settled for good: a receipt the node has pruned isn't asked for again.
        val later = history(chain, MemoryStore(listOf(unknown)))
        withTimeout(5_000) { later.records.first { it.isNotEmpty() } }
        chain.methods.clear()
        later.refresh()
        assertTrue(chain.methods.isEmpty())
        assertEquals(TxRecord.Status.UNKNOWN, later.records.value.single().status)
    }

    @Test
    fun `a status only moves forward`() = runBlocking<Unit> {
        val chain = FakeChain(gnosis)
        val h = history(chain)
        val s = sender(chain, h)
        // What the history holds at the very moment each stage shows (Unconfined: inside the
        // sender's show()): it has heard first, so whoever reacts to a stage finds it there.
        val seen = java.util.concurrent.CopyOnWriteArrayList<Pair<SendStatus.Stage, TxRecord.Status?>>()
        val watcher = async(Dispatchers.Unconfined) {
            s.changes.first { st ->
                if (st?.hash != null) seen += st.stage to h.records.value.singleOrNull()?.status
                st?.stage is SendStatus.Stage.Confirmed
            }
        }
        val quote = s.prepare(request())
        s.submit(quote, signer())
        val hash = s.awaitStage { it == SendStatus.Stage.Pending }.hash!!
        chain.receipts[hash.lowercase()] = ok()
        val done = s.awaitStage { it is SendStatus.Stage.Confirmed }
        withTimeout(5_000) { watcher.await() }
        assertEquals(TxRecord.Status.PENDING, seen.first { it.first == SendStatus.Stage.Pending }.second)
        assertEquals(TxRecord.Status.CONFIRMED, seen.first { it.first is SendStatus.Stage.Confirmed }.second)
        // A late report of an earlier stage (a restored journal, say) changes nothing.
        h.note(done.copy(stage = SendStatus.Stage.Pending))
        h.note(done.copy(stage = SendStatus.Stage.Unconfirmed))
        h.note(done.copy(stage = SendStatus.Stage.Reverted(1, BigInteger.ONE)))
        val r = h.records.value.single()
        assertEquals(TxRecord.Status.CONFIRMED, r.status)
        assertEquals(16L, r.block)
        // Signing and broadcasting record nothing.
        val other = done.copy(hash = "0x" + "22".repeat(32))
        h.note(other.copy(stage = SendStatus.Stage.Signing))
        h.note(other.copy(stage = SendStatus.Stage.Broadcasting))
        h.note(other.copy(stage = SendStatus.Stage.Failed("Not sent", false)))
        assertEquals(1, h.records.value.size)
    }

    // ---- kept between runs ----

    @Test
    fun `the history survives a restart in its file, newest first`() = runBlocking<Unit> {
        val chain = FakeChain(gnosis)
        val file = File(tmp.root, "wallet/history.json")
        val h = history(chain, FileTxHistoryStore(file))
        val s = sender(chain, h)
        s.submit(s.prepare(request(amount = 1)), signer())
        val a = s.awaitStage { it == SendStatus.Stage.Pending }.hash!!
        chain.receipts[a.lowercase()] = ok()
        s.awaitStage { it is SendStatus.Stage.Confirmed }
        s.acknowledge()
        now.addAndGet(60_000)
        s.submit(s.prepare(request(xbzz, 3)), signer())
        val b = s.awaitStage { it == SendStatus.Stage.Pending }.hash!!
        withTimeout(5_000) {
            while (FileTxHistoryStore(file).load().map { it.hash to it.status } !=
                listOf(b to TxRecord.Status.PENDING, a to TxRecord.Status.CONFIRMED)
            ) delay(10)
        }
        val back = history(chain, FileTxHistoryStore(file))
        val list = withTimeout(5_000) { back.records.first { it.size == 2 } }
        assertEquals(h.records.value.first { it.hash == b }, list[0])
        assertEquals(h.records.value.first { it.hash == a }, list[1])
    }

    @Test
    fun `a send restored as pending before the file is read doesn't undo the confirmed record the file has`() = runBlocking<Unit> {
        val chain = FakeChain(gnosis)
        val hash = "0x" + "11".repeat(32)
        val confirmed = record(hash, status = TxRecord.Status.CONFIRMED, sentAt = now.get() - 5_000).copy(block = 5, settledAt = now.get())
        val gate = java.util.concurrent.CountDownLatch(1)
        val store = object : TxHistoryStore {
            @Volatile var saved: List<TxRecord> = listOf(confirmed)
            override fun save(records: List<TxRecord>): Boolean {
                saved = records
                return true
            }
            override fun load(): List<TxRecord> {
                gate.await()
                return saved
            }
        }
        val h = history(chain, store)
        val s = sender(chain, TxHistory(chain.rpc(), scope))
        val quote = s.prepare(request())
        h.note(SendStatus(quote, SendStatus.Stage.Pending, hash))
        h.note(SendStatus(quote, SendStatus.Stage.Pending, "0x" + "33".repeat(32)))
        gate.countDown()
        // Both are in the list before the file is read, so wait for the merge itself, not for the size.
        h.awaitLoaded()
        val list = h.records.value
        assertEquals(2, list.size)
        val kept = list.first { it.hash == hash }
        assertEquals(TxRecord.Status.CONFIRMED, kept.status)
        assertEquals(5L, kept.block)
        assertEquals(confirmed.sentAt, kept.sentAt)
        // And what was noted meanwhile is written once the file has been read.
        withTimeout(5_000) { while (store.saved.size != 2) delay(10) }
    }

    @Test
    fun `wipe forgets everything, on disk too, and a refresh begun before it writes nothing back`() = runBlocking<Unit> {
        val file = File(tmp.root, "wallet/history.json")
        FileTxHistoryStore(file).save(listOf(record()))
        assertTrue(file.exists())
        val hash = "0x" + "11".repeat(32)
        val release = java.util.concurrent.CountDownLatch(1)
        val asked = java.util.concurrent.CountDownLatch(1)
        val chain = FakeChain(gnosis)
        chain.receipts[hash] = ok()
        val rpc = WalletRpc(
            ChainDataRouter(
                chains = { listOf(gnosis) },
                transport = RpcTransport { _, body, _ ->
                    asked.countDown()
                    release.await()
                    """{"jsonrpc":"2.0","id":1,${chain.answer(JSONObject(body))}}"""
                },
            ),
        )
        val h = TxHistory(rpc, scope, FileTxHistoryStore(file), clock = { now.get() })
        withTimeout(5_000) { h.records.first { it.isNotEmpty() } }
        val refreshing = scope.async { h.refresh() }
        asked.await()
        h.wipe()
        release.countDown()
        refreshing.await()
        assertTrue(h.records.value.isEmpty())
        withTimeout(5_000) { while (file.exists()) delay(10) }
    }

    @Test
    fun `saving an empty history also deletes a temporary file a cut-short save left behind`() {
        val file = File(tmp.root, "wallet/history.json")
        val store = FileTxHistoryStore(file)
        assertTrue(store.save(listOf(record())))
        val left = File(file.parentFile, "history.json.tmp")
        left.writeText(TxHistoryCodec.encode(listOf(record())).toString())
        assertTrue(store.save(emptyList()))
        assertFalse(file.exists())
        assertFalse(left.exists())
        // Nothing but the stray temporary file on disk: still wiped.
        left.writeText("{}")
        assertTrue(store.save(emptyList()))
        assertFalse(left.exists())
    }

    @Test
    fun `a wipe before the file is read drops what the file had`() = runBlocking<Unit> {
        val gate = java.util.concurrent.CountDownLatch(1)
        val store = object : TxHistoryStore {
            @Volatile var saved: List<TxRecord> = listOf(record())
            override fun save(records: List<TxRecord>): Boolean {
                saved = records
                return true
            }
            override fun load(): List<TxRecord> {
                gate.await()
                return saved
            }
        }
        val h = history(FakeChain(gnosis), store)
        h.wipe()
        gate.countDown()
        withTimeout(5_000) { while (store.saved.isNotEmpty()) delay(10) }
        assertTrue(h.records.value.isEmpty())
    }

    @Test
    fun `wipeNow has the file gone before it returns, even before the file has been read`() = runBlocking<Unit> {
        val file = File(tmp.root, "wallet/history.json")
        FileTxHistoryStore(file).save(listOf(record()))
        val h = TxHistory(FakeChain(gnosis).rpc(), scope, FileTxHistoryStore(file), clock = { now.get() })
        withTimeout(5_000) { h.records.first { it.isNotEmpty() } }
        assertTrue(h.wipeNow())
        // No write left to a launched job that a process death could get ahead of.
        assertFalse(file.exists())
        assertTrue(h.records.value.isEmpty())

        val gate = java.util.concurrent.CountDownLatch(1)
        val store = object : TxHistoryStore {
            @Volatile var saved: List<TxRecord> = listOf(record())
            override fun save(records: List<TxRecord>): Boolean {
                saved = records
                return true
            }
            override fun load(): List<TxRecord> {
                gate.await()
                return saved
            }
        }
        val unread = history(FakeChain(gnosis), store)
        assertTrue(unread.wipeNow())
        assertTrue(store.saved.isEmpty())
        gate.countDown()
        unread.awaitLoaded()
        assertTrue(unread.records.value.isEmpty())
        assertTrue(store.saved.isEmpty())
    }

    @Test
    fun `a send read back from the journal after its wallet was removed is not recorded in the wiped history`() = runBlocking<Unit> {
        val chain = FakeChain(gnosis)
        // A first process leaves a pending send in its journal.
        val kept = java.util.concurrent.atomic.AtomicReference<SendJournal.State?>()
        val journal = object : SendJournal {
            override fun save(state: SendJournal.State): Boolean {
                kept.set(state)
                return true
            }
            override fun load() = kept.get()
        }
        val earlier = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val first = WalletSender(chain.rpc(), earlier, pollMs = 10, confirmTimeoutMs = 60_000, journal = journal)
        first.awaitRestored()
        first.submit(first.prepare(request()), signer())
        val hash = first.awaitStage { it == SendStatus.Stage.Pending }.hash!!
        withTimeout(5_000) { while (kept.get()?.send?.status?.stage != SendStatus.Stage.Pending) delay(10) }
        earlier.cancel()

        // The next launch: Remove wallet lands before the journal has been read back.
        val gate = java.util.concurrent.CountDownLatch(1)
        val slow = object : SendJournal {
            override fun save(state: SendJournal.State) = true
            override fun load(): SendJournal.State? {
                gate.await()
                return kept.get()
            }
        }
        val h = history(chain)
        val s = WalletSender(chain.rpc(), scope, { senderClock.get() }, pollMs = 10, confirmTimeoutMs = CONFIRM_TIMEOUT_MS, journal = slow, history = h)
        s.discard()
        h.wipeNow()
        gate.countDown()
        s.awaitRestored()
        assertNull(s.status.value)
        // Read back and discarded before awaitRestored returned; the history hears synchronously.
        assertTrue(h.records.value.isEmpty())
        // Given up on, not forgotten: the next send from that account replaces it.
        assertEquals(hash, s.prepare(request()).replaces)

        // Without the removal, the same journal is recorded as pending.
        val h2 = history(chain)
        WalletSender(chain.rpc(), scope, { senderClock.get() }, pollMs = 10, confirmTimeoutMs = CONFIRM_TIMEOUT_MS, journal = journal, history = h2).awaitRestored()
        assertEquals(TxRecord.Status.PENDING, h2.awaitRecord(hash).status)
    }

    @Test
    fun `the file keeps what it can read and drops what it can't`() {
        val good = record()
        val json = TxHistoryCodec.encode(listOf(good, record("0x" + "22".repeat(32))))
        json.getJSONArray("records").getJSONObject(1).put("amount", "not a number")
        json.getJSONArray("records").put(JSONObject().put("hash", "0xnothex"))
        assertEquals(listOf(good), TxHistoryCodec.decode(json))
        assertEquals(emptyList<TxRecord>(), TxHistoryCodec.decode(JSONObject().put("version", 2)))
        val file = File(tmp.root, "h.json")
        file.writeText("[[[[")
        assertEquals(emptyList<TxRecord>(), FileTxHistoryStore(file).load())
        file.writeText("[".repeat(100_000))
        assertEquals(emptyList<TxRecord>(), FileTxHistoryStore(file).load())
    }

    @Test
    fun `at most MAX_RECORDS are kept, the oldest settled going first and a pending one never`() = runBlocking<Unit> {
        val chain = FakeChain(gnosis)
        val oldPending = record("0x" + "0".repeat(63) + "1", sentAt = 1)
        val settled = (2..TxHistory.MAX_RECORDS + 1).map {
            record("0x" + it.toString(16).padStart(64, '0'), status = TxRecord.Status.CONFIRMED, sentAt = it.toLong())
        }
        val h = history(chain, MemoryStore(listOf(oldPending) + settled))
        val list = withTimeout(5_000) { h.records.first { it.isNotEmpty() } }
        assertEquals(TxHistory.MAX_RECORDS, list.size)
        assertTrue(list.any { it.hash == oldPending.hash })
        assertFalse(list.any { it.sentAt == 2L })
        assertEquals(list.sortedByDescending { it.sentAt }, list)
    }

    // ---- how it reads ----

    @Test
    fun `each status reads as what happened`() {
        val r = record()
        assertEquals("Sent 1.5 xDAI", txTitle(r))
        assertEquals("Pending", txStatusText(r).first)
        val c = r.copy(status = TxRecord.Status.CONFIRMED, block = 1_234_567, feePaid = BigInteger.valueOf(42_000))
        assertEquals("Confirmed" to "Mined in block 1,234,567 · fee 0.00000001 xDAI", txStatusText(c))
        val f = c.copy(status = TxRecord.Status.FAILED)
        assertEquals("Failed on chain", txStatusText(f).first)
        assertTrue(txStatusText(f).second.contains("nothing arrived"))
        assertTrue(txStatusText(f).second.contains("(0.00000001 xDAI) was still paid"))
        val x = r.copy(status = TxRecord.Status.REPLACED)
        assertEquals("Replaced", txStatusText(x).first)
        assertTrue(txStatusText(x).second.contains("nonce (7)"))
        val utc = SimpleDateFormat("d MMM yyyy HH:mm", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
        assertEquals("Confirmed · ${gnosis.name} · 21 Sep 2026 14:13", txSubtitle(c.copy(sentAt = 1_789_999_980_000L), utc))
        assertEquals("${gnosis.explorerUrl!!.trimEnd('/')}/tx/${r.hash}", explorerTxUrl(r))
        assertNull(explorerTxUrl(r.copy(explorerUrl = null)))
    }

    @Test
    fun `the list shows the active account's sends only`() {
        val mine = record("0x" + "11".repeat(32))
        val other = record("0x" + "22".repeat(32), from = to)
        val list = listOf(mine, other)
        assertEquals(listOf(mine), txRecordsFrom(list, from.address.lowercase()))
        assertEquals(listOf(other), txRecordsFrom(list, to))
        assertEquals(emptyList<TxRecord>(), txRecordsFrom(list, null))
    }
}
