package baby.freedom.mobile.wallet

import baby.freedom.mobile.browser.explorerTxUrl
import baby.freedom.mobile.browser.feeDetail
import baby.freedom.mobile.browser.feeFootnote
import baby.freedom.mobile.browser.feeText
import baby.freedom.mobile.browser.sendStatusText
import baby.freedom.mobile.browser.stopTrackingText
import baby.freedom.mobile.chains.BuiltInChains
import baby.freedom.mobile.chains.Chain
import baby.freedom.mobile.chains.rpc.ChainDataRouter
import baby.freedom.mobile.chains.rpc.ChainRpcException
import baby.freedom.mobile.chains.rpc.ChainSource
import baby.freedom.mobile.chains.rpc.ChainTrust
import baby.freedom.mobile.chains.rpc.JsonRpc
import baby.freedom.mobile.chains.rpc.RpcTransport
import baby.freedom.mobile.chains.rpc.WalletRpc
import baby.freedom.mobile.ens.EnsAddressResult
import baby.freedom.mobile.ens.EnsResult
import baby.freedom.mobile.ens.EnsTrust
import baby.freedom.mobile.ens.hexToBytes
import baby.freedom.mobile.ens.toHex
import baby.freedom.mobile.wallet.ledger.LedgerException
import java.io.IOException
import java.math.BigInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.junit.After
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The send flow (#105): amounts, recipients, fees, nonces, prepare, sign, broadcast and receipt. */
class SendTest {
    // Hardhat account 0: a published test key, never a funded one.
    private val key = "ac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80".hexToBytes()
    private val from = WalletAccount(0, "Account 1", "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266")
    private val to = "0x70997970C51812dc3A010C7d01b50e0d17dc79C8"
    private val gnosis = BuiltInChains.GNOSIS.copy(rpcUrls = listOf("https://a.example", "https://b.example", "https://c.example"))
    private val xdai = TokenRegistry.native(gnosis)
    private val xbzz = TokenRegistry.builtins.first { it.symbol == "xBZZ" }
    private val gwei = BigInteger.valueOf(1_000_000_000L)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @get:Rule
    val tmp = TemporaryFolder()

    @After
    fun tearDown() = scope.cancel()

    private companion object {
        const val CONFIRM_TIMEOUT_MS = 300L
    }

    /** A fake chain every RPC agrees on; [on] can override any method's answer (a JSON-RPC member). */
    private inner class FakeChain {
        var balance = BigInteger.TEN.pow(18)
        var tokenBalance = BigInteger.valueOf(5_000)
        var nonce = 7L

        /** The count of mined transactions (`latest`); null: the same as [nonce] (nothing pending). */
        var mined: Long? = null
        var baseFee: BigInteger? = BigInteger.valueOf(14)

        /** The chain's head: blocks past it don't exist yet. */
        var head = 0x10L
        var tip = BigInteger.ONE
        var estimate = 21_000L
        var receipt: String = "null"
        var down = false
        val sent = mutableListOf<String>()
        val methods = mutableListOf<String>()
        val requests = mutableListOf<JSONObject>()
        val on = HashMap<String, (JSONObject) -> String>()

        /** One RPC's own answer to a method (by URL), over [on]: an RPC the others disagree with. */
        val onUrl = HashMap<String, (String) -> String?>()

        /** Like [onUrl], seeing the request too; null falls through to [onUrl] and the defaults. */
        val onUrlReq = HashMap<String, (String, JSONObject) -> String?>()

        fun answer(req: JSONObject, url: String = ""): String {
            val method = req.getString("method")
            synchronized(methods) { methods += method; requests += req }
            onUrlReq[method]?.invoke(url, req)?.let { return it }
            onUrl[method]?.invoke(url)?.let { return it }
            on[method]?.let { return it(req) }
            fun q(v: BigInteger) = "\"result\":\"0x${v.toString(16)}\""
            return when (method) {
                "eth_getBalance" -> q(balance)
                "eth_call" -> "\"result\":\"0x" + tokenBalance.toString(16).padStart(64, '0') + "\""
                "eth_getTransactionCount" -> q(
                    BigInteger.valueOf(if (req.getJSONArray("params").optString(1) == "latest") mined ?: nonce else nonce),
                )
                "eth_getBlockByNumber" -> req.getJSONArray("params").getString(0).let { tag ->
                    if (tag.startsWith("0x") && tag.substring(2).toLong(16) > head) "\"result\":null" else null
                } ?: "\"result\":" + JSONObject().apply {
                    put("number", "0x" + head.toString(16))
                    baseFee?.let { put("baseFeePerGas", "0x" + it.toString(16)) }
                }
                "eth_maxPriorityFeePerGas" -> q(tip)
                "eth_blockNumber" -> q(BigInteger.valueOf(head))
                "eth_feeHistory" -> "\"result\":" + JSONObject()
                    .put("oldestBlock", req.getJSONArray("params").getString(1))
                    .put("baseFeePerGas", org.json.JSONArray().put("0x" + (baseFee ?: BigInteger.ZERO).toString(16)).put("0x1"))
                    .put("gasUsedRatio", org.json.JSONArray().put(0.5))
                "eth_gasPrice" -> q(BigInteger.valueOf(3) * gwei)
                "eth_estimateGas" -> q(BigInteger.valueOf(estimate))
                "eth_sendRawTransaction" -> {
                    val raw = req.getJSONArray("params").getString(0)
                    synchronized(sent) { sent += raw }
                    "\"result\":\"0x" + "ab".repeat(32) + "\""
                }
                "eth_getTransactionReceipt" -> "\"result\":$receipt"
                else -> "\"error\":{\"code\":-32601,\"message\":\"no such method\"}"
            }
        }

        fun rpc() = WalletRpc(
            ChainDataRouter(
                chains = { listOf<Chain>(gnosis) },
                transport = RpcTransport { url, body, _ ->
                    if (down) throw IOException("down")
                    withReceiptHash(JSONObject(body), """{"jsonrpc":"2.0","id":1,${answer(JSONObject(body), url)}}""")
                },
            ),
        )
    }

    /**
     * The senders' clock: still unless a test moves it, so no receipt wait runs
     * out on its own however slow the runner — a send the test expects confirmed
     * can't end Unconfirmed first. [awaitStage] moves it for a test waiting for
     * Unconfirmed.
     */
    private val clock = AtomicLong(1_700_000_000_000L)

    private fun sender(
        chain: FakeChain,
        journal: SendJournal = SendJournal.None,
        clock: () -> Long = { this.clock.get() },
    ) = WalletSender(chain.rpc(), scope, clock, pollMs = 10, confirmTimeoutMs = CONFIRM_TIMEOUT_MS, journal = journal)
        .also { runBlocking { it.awaitRestored() } }

    /**
     * Runs [block] on a thread of its own and checks it returned while [held]
     * (the storage it must not wait on) was still closed. Its return is the
     * proof, not how fast it came; the join's bound only turns a hang into a
     * failure.
     */
    private fun returnsWhileHeld(held: java.util.concurrent.CountDownLatch, block: () -> Unit) {
        var failure: Throwable? = null
        val t = kotlin.concurrent.thread { try { block() } catch (e: Throwable) { failure = e } }
        t.join(30_000)
        assertFalse("still waiting on storage it was meant to leave to another thread", t.isAlive)
        failure?.let { throw it }
        assertEquals(1L, held.count)
    }

    /** A journal file that outlives one [WalletSender], as the app's outlives its process. */
    private fun journalFile() = java.io.File(tmp.root, "wallet/send.json")

    private fun signer(): (EthTransaction) -> EthTransaction.Signed = { tx -> tx.sign(key.copyOf(), from.address) }

    private fun request(token: Token = xdai, amount: Long = 1) = SendRequest(gnosis, token, from, to, BigInteger.valueOf(amount))

    /**
     * Until [status] reaches a stage [predicate] takes. If it would take
     * Unconfirmed, the test is waiting for a receipt wait to run out: [clock]
     * is moved past it, and again, until the send gets there.
     */
    private suspend fun WalletSender.awaitStage(predicate: (SendStatus.Stage) -> Boolean): SendStatus =
        withTimeout(5_000) {
            suspend fun reached() = status.first { it != null && predicate(it.stage) }!!
            if (!predicate(SendStatus.Stage.Unconfirmed)) return@withTimeout reached()
            var seen: SendStatus? = null
            while (seen == null) {
                seen = withTimeoutOrNull(10) { reached() }
                if (seen == null) clock.addAndGet(CONFIRM_TIMEOUT_MS)
            }
            seen
        }

    // ---- input ----

    @Test
    fun `amounts parse exactly to base units, with either decimal point`() {
        assertEquals(BigInteger.ONE, SendAmounts.parse("0.000000000000000001", 18))
        assertEquals(BigInteger("1500000000000000000"), SendAmounts.parse("1.5", 18))
        assertEquals(BigInteger("1500000000000000000"), SendAmounts.parse("1,5", 18))
        assertEquals(BigInteger("500000000000000000"), SendAmounts.parse(".5", 18))
        assertEquals(BigInteger("2000000"), SendAmounts.parse("2.", 6))
        assertEquals(BigInteger.valueOf(12), SendAmounts.parse(" 12 ", 0))
        for (bad in listOf("", "0", "0.0", "-1", "1e3", "1.2.3", "1,000.5", "abc", "0x10", "1.0000001")) {
            assertNull(bad, SendAmounts.parse(bad, 6))
        }
        assertEquals("1.5", SendAmounts.exact(BigInteger("1500000000000000000"), 18))
        assertEquals("0.000000000000000001", SendAmounts.exact(BigInteger.ONE, 18))
        assertEquals("3", SendAmounts.exact(BigInteger.valueOf(3_000_000), 6))
    }

    @Test
    fun `recipients - checksums enforced when mixed case, dead ends refused`() {
        assertEquals(Recipients.Parsed.Ok(to), Recipients.parse(to, xdai))
        assertEquals(Recipients.Parsed.Ok(to), Recipients.parse(to.lowercase(), xdai))
        assertEquals(Recipients.Parsed.Ok(to), Recipients.parse("0x" + to.substring(2).uppercase(), xdai))
        // One letter's case flipped: a typo the checksum catches.
        val typo = to.replaceFirst("C5", "c5")
        assertTrue(Recipients.parse(typo, xdai) is Recipients.Parsed.Invalid)
        assertTrue(Recipients.parse("vitalik.eth", xdai) is Recipients.Parsed.Invalid)
        assertTrue(Recipients.parse("0x1234", xdai) is Recipients.Parsed.Invalid)
        assertTrue(Recipients.parse("0x" + "0".repeat(40), xdai) is Recipients.Parsed.Invalid)
        // The token's own contract, for that token — fine as a native recipient.
        assertTrue(Recipients.parse(xbzz.address!!, xbzz) is Recipients.Parsed.Invalid)
        assertTrue(Recipients.parse(xbzz.address!!, xdai) is Recipients.Parsed.Ok)
    }

    @Test
    fun `recipients - names are looked up on Send's field, in their ENSIP-15 form (#277)`() {
        assertEquals(Recipients.Parsed.Name("alice.eth"), Recipients.parse("Alice.ETH", xdai, names = true))
        assertEquals(Recipients.Parsed.Name("alice.box"), Recipients.parse("alice.box", xdai, names = true))
        assertEquals(Recipients.Parsed.Name("alice.wei"), Recipients.parse("alice.wei", xdai, names = true))
        assertEquals(Recipients.Parsed.Name("alice.gwei"), Recipients.parse("alice.gwei", xdai, names = true))
        // A DNS name imported into ENS.
        assertEquals(Recipients.Parsed.Name("gregskril.com"), Recipients.parse(" gregskril.com ", xdai, names = true))
        assertEquals(Recipients.Parsed.Ok(to), Recipients.parse(to, xdai, names = true))
        assertEquals(Recipients.Parsed.Name("0x1234.eth"), Recipients.parse("0x1234.eth", xdai, names = true))
        for (bad in listOf("alice", "alice..eth", ".eth", "alice.eth/x", "https://alice.eth", "a b.eth", "alice.tez")) {
            assertTrue(bad, Recipients.parse(bad, xdai, names = true) is Recipients.Parsed.Invalid)
        }
        // Elsewhere (a Safe's send) an address only, as before.
        assertTrue(Recipients.parse("alice.eth", xdai) is Recipients.Parsed.Invalid)
    }

    @Test
    fun `recipients - a name's address is checksummed and held to the same refusals (#277)`() {
        assertEquals(Recipients.Parsed.Ok(to), Recipients.resolved(to.lowercase(), xdai))
        assertTrue(Recipients.resolved(xbzz.address!!.lowercase(), xbzz) is Recipients.Parsed.Invalid)
        assertTrue(Recipients.resolved("0x" + "0".repeat(40), xdai) is Recipients.Parsed.Invalid)
    }

    @Test
    fun `recipients - the re-check before signing holds the name to the address reviewed (#277)`() {
        val verified = EnsTrust(verified = true, agreed = listOf("a", "b"))
        val single = EnsTrust(verified = false, agreed = listOf("a"))
        val other = "0x" + "b0".repeat(20)
        fun ok(address: String, trust: EnsTrust) = EnsAddressResult.Ok("alice.eth", address, trust)
        assertNull(Recipients.recheck("alice.eth", to, ok(to.lowercase(), verified), unverifiedAccepted = false))
        assertNull(Recipients.recheck("alice.eth", to, ok(to.lowercase(), single), unverifiedAccepted = true))
        assertNotNull(Recipients.recheck("alice.eth", to, ok(to.lowercase(), single), unverifiedAccepted = false))
        val moved = Recipients.recheck("alice.eth", to, ok(other, verified), unverifiedAccepted = true)!!
        assertTrue(moved, moved.contains("different address (0x"))
        assertNotNull(Recipients.recheck("alice.eth", to, EnsAddressResult.NoAddress("alice.eth", "NO_ADDRESS", verified), true))
        assertNotNull(
            Recipients.recheck(
                "alice.eth", to,
                EnsAddressResult.Conflict("alice.eth", EnsResult.Conflict.Subject.RECORD, emptyList(), 1L), true,
            ),
        )
        assertNotNull(Recipients.recheck("alice.eth", to, EnsAddressResult.Error("alice.eth", "PROVIDER_ERROR", "down", true), true))
    }

    @Test
    fun `recipients - a name with nothing to send to says why (#277)`() {
        assertNull(Recipients.lookupProblem(EnsAddressResult.Ok("alice.eth", to.lowercase(), EnsTrust.ASSUMED), "Gnosis"))
        assertEquals(
            "alice.eth has no address for Gnosis. Only an address its owner set for this network is safe to send to.",
            Recipients.lookupProblem(EnsAddressResult.NoAddress("alice.eth", "NO_ADDRESS", EnsTrust.ASSUMED), "Gnosis"),
        )
        assertTrue(
            Recipients.lookupProblem(EnsAddressResult.NoAddress("alice.wei", "CHAIN_UNSUPPORTED", null), "Gnosis")!!
                .startsWith("WNS names hold an Ethereum address only"),
        )
        val conflict = EnsAddressResult.Conflict(
            "alice.eth", EnsResult.Conflict.Subject.RECORD,
            listOf(EnsResult.Conflict.Group("0xa1", listOf("rpc1.test")), EnsResult.Conflict.Group("0xb0", listOf("rpc2.test"))),
            1L,
        )
        assertEquals(
            "Servers disagree on alice.eth’s address, so nothing can be sent to it: 0xa1 (rpc1.test); 0xb0 (rpc2.test).",
            Recipients.lookupProblem(conflict, "Ethereum"),
        )
    }

    @Test
    fun `a send to a name keeps the name as a label across a restart, the address is what's signed (#277)`() = runBlocking<Unit> {
        val chain = FakeChain()
        val s = sender(chain, journal = FileSendJournal(journalFile()))
        chain.on["eth_getTransactionReceipt"] = { throw IOException("timed out") }
        val quote = s.prepare(request().copy(toName = "alice.eth"))
        assertEquals(to, quote.tx.to)
        s.submit(quote, signer())
        s.awaitStage { it == SendStatus.Stage.Pending }
        assertTrue(journalFile().readText().contains("\"toName\":\"alice.eth\""))
        val restored = sender(chain, journal = FileSendJournal(journalFile())).status.value!!.quote.request
        assertEquals("alice.eth", restored.toName)
        assertEquals(to, restored.to)
    }

    // ---- fees and gas ----

    @Test
    fun `EIP-1559 fees are twice the base fee plus a tip of at least 1 gwei`() {
        val low = GasOracle.eip1559(BigInteger.valueOf(14), BigInteger.ONE, 100)
        assertEquals(gwei, low.maxPriorityFeePerGas)
        assertEquals(gwei + BigInteger.valueOf(28), low.maxFeePerGas)
        val high = GasOracle.eip1559(BigInteger.valueOf(20) * gwei, BigInteger.valueOf(2) * gwei, 100)
        assertEquals(BigInteger.valueOf(2) * gwei, high.maxPriorityFeePerGas)
        assertEquals(BigInteger.valueOf(42) * gwei, high.maxFeePerGas)
        assertEquals(gwei, GasOracle.eip1559(BigInteger.TEN, null, 100).maxPriorityFeePerGas)
        try {
            GasOracle.legacy(BigInteger.ZERO)
            fail("a zero gas price must be refused")
        } catch (_: SendException) {
        }
    }

    private fun q(v: BigInteger) = "\"result\":\"0x${v.toString(16)}\""

    @Test
    fun `an RPC's inflated tip is capped, not paid in full (#233)`() = runBlocking {
        // Every RPC agrees on 5000 gwei: agreement is no reason to burn 0.1 xDAI on a transfer.
        val chain = FakeChain().apply { tip = BigInteger.valueOf(5_000) * gwei }
        val quote = sender(chain).prepare(request())
        val fees = quote.tx.fees as EthTransaction.Fees.Eip1559
        assertEquals(BigInteger.valueOf(5) * gwei, fees.maxPriorityFeePerGas)
        assertEquals(BigInteger.valueOf(28) + BigInteger.valueOf(5) * gwei, fees.maxFeePerGas)
        assertTrue(GasOracle.quiet(fees, gnosis.id))
        // On a chain a site added, the cap is higher (Polygon asks for 25–30 gwei), but still a cap.
        val added = GasOracle.eip1559(BigInteger.valueOf(14), BigInteger.valueOf(5_000) * gwei, 137)
        assertEquals(BigInteger.valueOf(50) * gwei, added.maxPriorityFeePerGas)
        assertEquals(BigInteger.valueOf(30) * gwei, GasOracle.eip1559(BigInteger.ONE, BigInteger.valueOf(30) * gwei, 137).maxPriorityFeePerGas)
    }

    @Test
    fun `a tip above the cap goes only as high as a trusted base fee, never one RPC's word for it (#233)`() = runBlocking {
        val base = BigInteger.valueOf(80) * gwei
        val chain = FakeChain().apply { baseFee = base; tip = BigInteger.valueOf(60) * gwei }
        // Every RPC reports the 80 gwei base fee for the pinned block: a congested chain, where a 60 gwei tip is real.
        val verified = sender(chain).prepare(request()).tx.fees as EthTransaction.Fees.Eip1559
        assertEquals(BigInteger.valueOf(60) * gwei, verified.maxPriorityFeePerGas)
        // Above what one RPC's word may set: an auto-approve rule doesn't send it silently.
        assertFalse(GasOracle.quiet(verified, gnosis.id))
        // The base fee is agreed on for a block pinned two behind the head, from eth_feeHistory (R1-M3):
        // RPCs a block apart still give the same answer there, where `latest` blocks rarely match.
        val pinned = chain.requests.last { it.getString("method") == "eth_feeHistory" }.getJSONArray("params")
        assertEquals("0xe", pinned.getString(1))
        // The latest block can disagree across RPCs without costing the tip its trusted ceiling.
        chain.onUrlReq["eth_getBlockByNumber"] = { url, req ->
            // The latest block only: whether a later block exists yet is still answered the same by all.
            if (req.getJSONArray("params").getString(0) != "latest") null else {
                val fee = listOf(base, base + BigInteger.ONE, base + BigInteger.TWO)[gnosis.rpcUrls.indexOf(url)]
                "\"result\":" + JSONObject().put("number", "0x10").put("baseFeePerGas", "0x" + fee.toString(16))
            }
        }
        assertEquals(BigInteger.valueOf(60) * gwei, (sender(chain).prepare(request()).tx.fees as EthTransaction.Fees.Eip1559).maxPriorityFeePerGas)
        // Only the first RPC reports the high base fee (the others: 14 and 15 wei, so no two agree), and
        // as the first it's the answer used: the cap holds.
        chain.onUrl["eth_feeHistory"] = { url ->
            val fee = listOf(base, BigInteger.valueOf(14), BigInteger.valueOf(15))[gnosis.rpcUrls.indexOf(url)]
            "\"result\":" + JSONObject().put("oldestBlock", "0xe")
                .put("baseFeePerGas", org.json.JSONArray().put("0x" + fee.toString(16)).put("0x1"))
                .put("gasUsedRatio", org.json.JSONArray().put(0.5))
        }
        val unverified = sender(chain).prepare(request()).tx.fees as EthTransaction.Fees.Eip1559
        assertEquals(BigInteger.valueOf(5) * gwei, unverified.maxPriorityFeePerGas)
        assertEquals(BigInteger.valueOf(165) * gwei, unverified.maxFeePerGas)
        // An RPC without eth_feeHistory: no trusted base fee, so the cap holds too.
        chain.onUrl.remove("eth_feeHistory")
        chain.on["eth_feeHistory"] = { "\"error\":{\"code\":-32601,\"message\":\"no such method\"}" }
        assertEquals(BigInteger.valueOf(5) * gwei, (sender(chain).prepare(request()).tx.fees as EthTransaction.Fees.Eip1559).maxPriorityFeePerGas)
        // The user's own RPC's word is trusted, as everywhere else in the app.
        assertEquals(BigInteger.valueOf(60) * gwei, GasOracle.eip1559(base, BigInteger.valueOf(60) * gwei, gnosis.id, trustedBaseFee = base).maxPriorityFeePerGas)
        assertTrue(GasOracle.trusted(ChainTrust(ChainTrust.Level.USER_CONFIGURED, ChainSource.DIRECT, listOf("a"), emptyList(), listOf("a"), 1, 1, null)))
        assertFalse(GasOracle.trusted(ChainTrust(ChainTrust.Level.UNVERIFIED, ChainSource.DIRECT, listOf("a"), emptyList(), listOf("a"), 1, 1, null)))
    }

    @Test
    fun `a head an RPC picks can't pin the tip's ceiling to an old congested block (#233 R2-F1)`() = runBlocking {
        val base = BigInteger.valueOf(80) * gwei
        // Every RPC agrees on the 80 gwei base fee of whatever block is asked for: a block the chain paid
        // that for long ago, as eth_feeHistory for a 2021 block is today. Honest RPCs are a block apart on
        // the head, so no quorum forms and the first RPC's head is the one used.
        val chain = FakeChain().apply { head = 0x1000L; baseFee = base; tip = BigInteger.valueOf(5_000) * gwei }
        chain.onUrl["eth_blockNumber"] = { url ->
            q(BigInteger.valueOf(listOf(0x5L, 0x1000L, 0x1001L)[gnosis.rpcUrls.indexOf(url)]))
        }
        val fees = sender(chain).prepare(request()).tx.fees as EthTransaction.Fees.Eip1559
        // Block 0x3 + 16 exists on every honest RPC: the pin isn't recent, so its base fee isn't trusted.
        assertEquals(BigInteger.valueOf(5) * gwei, fees.maxPriorityFeePerGas)
        // An honest first RPC a block behind still pins a recent block, and a real high tip is kept.
        chain.tip = BigInteger.valueOf(60) * gwei
        chain.onUrl["eth_blockNumber"] = { url ->
            q(BigInteger.valueOf(listOf(0xFFFL, 0x1000L, 0x1001L)[gnosis.rpcUrls.indexOf(url)]))
        }
        val recent = sender(chain).prepare(request()).tx.fees as EthTransaction.Fees.Eip1559
        assertEquals(BigInteger.valueOf(60) * gwei, recent.maxPriorityFeePerGas)
        val asked = chain.requests.filter { it.getString("method") == "eth_getBlockByNumber" }
            .map { it.getJSONArray("params").getString(0) }
        assertTrue(asked.contains("0x" + (0xFFDL + 16).toString(16)))
        // A head in the future pins a block no honest RPC has: nothing agrees, and the cap holds.
        chain.onUrl["eth_blockNumber"] = { url ->
            q(BigInteger.valueOf(listOf(0x9000L, 0x1000L, 0x1001L)[gnosis.rpcUrls.indexOf(url)]))
        }
        chain.onUrl["eth_feeHistory"] = { url ->
            if (gnosis.rpcUrls.indexOf(url) == 0) null
            else "\"error\":{\"code\":-32602,\"message\":\"request beyond head block\"}"
        }
        assertEquals(BigInteger.valueOf(5) * gwei, (sender(chain).prepare(request()).tx.fees as EthTransaction.Fees.Eip1559).maxPriorityFeePerGas)
    }

    @Test
    fun `eth_feeHistory answers shaped differently by each provider still agree on the base fee (#233 R2-M1)`() = runBlocking {
        val base = BigInteger.valueOf(80) * gwei
        val chain = FakeChain().apply { baseFee = base; tip = BigInteger.valueOf(60) * gwei }
        chain.onUrl["eth_feeHistory"] = { url ->
            val fee = org.json.JSONArray().put("0x" + base.toString(16)).put("0x1")
            val h = JSONObject().put("oldestBlock", "0xe").put("baseFeePerGas", fee)
            when (gnosis.rpcUrls.indexOf(url)) {
                0 -> h.put("gasUsedRatio", org.json.JSONArray().put(0.5)).put("reward", org.json.JSONArray().put(org.json.JSONArray()))
                1 -> h.put("gasUsedRatio", org.json.JSONArray().put(0.5))
                else -> h.put("gasUsedRatio", org.json.JSONArray().put(0.50001))
                    .put("baseFeePerBlobGas", org.json.JSONArray().put("0x1").put("0x1"))
                    .put("blobGasUsedRatio", org.json.JSONArray().put(0))
            }.let { "\"result\":$it" }
        }
        val fees = sender(chain).prepare(request()).tx.fees as EthTransaction.Fees.Eip1559
        assertEquals(BigInteger.valueOf(60) * gwei, fees.maxPriorityFeePerGas)
        // Only the block and its base fees are compared, each quantity in one spelling.
        assertEquals(
            JsonRpc.stable(WalletRpc.feeHistoryBaseFees(JSONObject().put("oldestBlock", "0x0e").put("baseFeePerGas", org.json.JSONArray().put("0x00ff")))),
            JsonRpc.stable(WalletRpc.feeHistoryBaseFees(JSONObject().put("oldestBlock", "0xe").put("baseFeePerGas", org.json.JSONArray().put("0xff")).put("reward", org.json.JSONArray()))),
        )
    }

    @Test
    fun `the usual tip needs no extra read (#233)`() = runBlocking {
        val chain = FakeChain().apply { tip = BigInteger.valueOf(2) * gwei }
        sender(chain).prepare(request())
        assertFalse(chain.methods.contains("eth_feeHistory"))
    }

    @Test
    fun `a high legacy gas price is priced, never refused, and never sent silently (#233 R1-F1)`() = runBlocking {
        val chain = FakeChain().apply { baseFee = null }
        // The usual price goes through.
        assertEquals(EthTransaction.Fees.Legacy(BigInteger.valueOf(3) * gwei), sender(chain).prepare(request()).tx.fees)
        // A chain priced high on its own RPC's word (IoTeX's fixed 1000 gwei, Theta's 4000): a manual
        // send isn't a dead end — its review shows the price and says it's unusually high.
        chain.onUrl["eth_gasPrice"] = { url ->
            q(BigInteger.valueOf(listOf(4_000L, 1_000L, 3L)[gnosis.rpcUrls.indexOf(url)]) * gwei)
        }
        val quote = sender(chain).prepare(request())
        assertEquals(EthTransaction.Fees.Legacy(BigInteger.valueOf(4_000) * gwei), quote.tx.fees)
        // …but never out under an auto-approve rule without a sheet.
        assertFalse(GasOracle.quiet(quote.tx.fees, gnosis.id))
        assertTrue(feeFootnote(quote.tx).startsWith("This network fee is unusually high"))
        assertTrue(GasOracle.quiet(EthTransaction.Fees.Legacy(BigInteger.valueOf(3) * gwei), gnosis.id))
        assertTrue(GasOracle.quiet(EthTransaction.Fees.Legacy(BigInteger.valueOf(500) * gwei), 4689))
        assertFalse(GasOracle.quiet(EthTransaction.Fees.Legacy(BigInteger.valueOf(1_000) * gwei), 4689))
    }

    @Test
    fun `the review's footnote says what of the fee is paid (#233)`() {
        fun tx(fees: EthTransaction.Fees) = EthTransaction(100, BigInteger.ZERO, BigInteger.valueOf(21_000), to, BigInteger.ONE, ByteArray(0), fees)
        // The tip is paid less when the base fee rises into the headroom (R1-M2): not "in full".
        val eip1559 = feeFootnote(tx(GasOracle.eip1559(BigInteger.TEN, gwei, 100)))
        assertTrue(eip1559, eip1559.contains("less only if the base fee rises into that room"))
        assertFalse(eip1559.contains("in full"))
        assertFalse(eip1559.contains("unusually high"))
        assertTrue(feeFootnote(tx(EthTransaction.Fees.Legacy(gwei))).contains("paid at the price above in full"))
        // A tip past the cap (a trusted high base fee let it through) is called out too.
        val high = feeFootnote(tx(EthTransaction.Fees.Eip1559(BigInteger.valueOf(200) * gwei, BigInteger.valueOf(60) * gwei)))
        assertTrue(high.startsWith("This network fee is unusually high"))
    }

    @Test
    fun `a plain transfer keeps 21000 gas, anything with code gets 20 percent headroom`() {
        assertEquals(BigInteger.valueOf(21_000), WalletSender.gasLimit(BigInteger.valueOf(21_000), hasData = false))
        assertEquals(BigInteger.valueOf(62_400), WalletSender.gasLimit(BigInteger.valueOf(52_000), hasData = true))
        assertEquals(BigInteger.valueOf(28_800), WalletSender.gasLimit(BigInteger.valueOf(24_000), hasData = false))
        // A site's own gas: taken if it covers the estimate, capped at three times it, ignored below it.
        val estimate = BigInteger.valueOf(50_000)
        assertEquals(BigInteger.valueOf(90_000), WalletSender.gasLimit(estimate, hasData = true, site = BigInteger.valueOf(90_000)))
        assertEquals(BigInteger.valueOf(150_000), WalletSender.gasLimit(estimate, hasData = true, site = BigInteger("ffffffffffff", 16)))
        assertEquals(BigInteger.valueOf(60_000), WalletSender.gasLimit(estimate, hasData = true, site = BigInteger.valueOf(40_000)))
        assertEquals(BigInteger.valueOf(60_000), WalletSender.gasLimit(estimate, hasData = true, site = null))
    }

    @Test
    fun `nonces are the chain's pending count, unless a send it hasn't seen yet is ahead`() = runBlocking<Unit> {
        val chain = FakeChain()
        val tracker = NonceTracker(chain.rpc())
        assertEquals(BigInteger.valueOf(7), tracker.next(from.address, 100).value)
        tracker.markSent(from.address, 100, BigInteger.valueOf(7))
        assertEquals(BigInteger.valueOf(8), tracker.next(from.address.lowercase(), 100).value)
        chain.nonce = 9
        assertEquals(BigInteger.valueOf(9), tracker.next(from.address, 100).value)
        chain.nonce = 7
        tracker.forget(from.address, 100)
        assertEquals(BigInteger.valueOf(7), tracker.next(from.address, 100).value)
    }

    @Test
    fun `a send abandoned between its broadcast and its markSent stays abandoned (#229)`() = runBlocking<Unit> {
        val chain = FakeChain()
        val tracker = NonceTracker(chain.rpc())
        val fees = EthTransaction.Fees.Legacy(BigInteger.TEN)
        val hash = "0x" + "ab".repeat(32)
        // Stop tracking lands after the node took it, before the sender marks it sent.
        tracker.abandon(from.address, 100, BigInteger.valueOf(7), fees, hash)
        tracker.markSent(from.address, 100, BigInteger.valueOf(7), hash.uppercase().replace("0X", "0x"))
        assertEquals(hash, tracker.replacing(from.address, 100, BigInteger.valueOf(7))?.hash)
        // Still waiting in a pool: the next send takes its place rather than going out beside it.
        chain.nonce = 8
        chain.mined = 7
        assertEquals(BigInteger.valueOf(7), tracker.next(from.address, 100).value)
        // Its replacement going out (another hash) is what drops it.
        tracker.markSent(from.address, 100, BigInteger.valueOf(7), "0x" + "cd".repeat(32))
        assertNull(tracker.replacing(from.address, 100, BigInteger.valueOf(7)))
    }

    /** Only the first RPC says the abandoned nonce 7 is mined (8); the others say 7 and 6, so no two agree. */
    private fun FakeChain.lieMined() {
        onUrlReq["eth_getTransactionCount"] = { url, req ->
            if (req.getJSONArray("params").optString(1) != "latest") null
            else "\"result\":\"0x" + listOf(8L, 7L, 6L)[gnosis.rpcUrls.indexOf(url)].toString(16) + "\""
        }
    }

    @Test
    fun `one unverified read of the mined count doesn't drop an abandoned send's guard (#238)`() = runBlocking<Unit> {
        val chain = FakeChain()
        val now = AtomicLong(5_000_000L)
        val tracker = NonceTracker(chain.rpc(), now = { now.get() })
        val hash = "0x" + "ab".repeat(32)
        tracker.abandon(from.address, 100, BigInteger.valueOf(7), EthTransaction.Fees.Legacy(BigInteger.TEN), hash)
        // Still in a pool (pending 8), and one lying RPC, the quorum defeated, says it's mined.
        chain.nonce = 8
        chain.lieMined()
        // The next send still takes its place, so only one of the two can go through…
        assertEquals(BigInteger.valueOf(7), tracker.next(from.address, 100).value)
        assertEquals(hash, tracker.replacing(from.address, 100, BigInteger.valueOf(7))?.hash)
        // …and so for any read less than CONFIRM_AFTER_MS after the first that found it mined.
        now.addAndGet(NonceTracker.CONFIRM_AFTER_MS - 1)
        assertEquals(BigInteger.valueOf(7), tracker.next(from.address, 100).value)
        assertEquals(hash, tracker.replacing(from.address, 100, BigInteger.valueOf(7))?.hash)
        // A read that finds it not mined starts over: the next sighting is a first one again.
        chain.onUrlReq.clear()
        chain.mined = 7
        assertEquals(BigInteger.valueOf(7), tracker.next(from.address, 100).value)
        chain.lieMined()
        now.addAndGet(1)
        assertEquals(BigInteger.valueOf(7), tracker.next(from.address, 100).value)
        assertEquals(hash, tracker.replacing(from.address, 100, BigInteger.valueOf(7))?.hash)
        // A second untrusted read agreeing, CONFIRM_AFTER_MS on: mined, and no chain with only
        // unverified reads reuses a mined nonce for longer than that.
        now.addAndGet(NonceTracker.CONFIRM_AFTER_MS)
        assertEquals(BigInteger.valueOf(8), tracker.next(from.address, 100).value)
        assertNull(tracker.replacing(from.address, 100, BigInteger.valueOf(7)))
    }

    @Test
    fun `a trusted read of the mined count drops an abandoned send at once (#238)`() = runBlocking<Unit> {
        val chain = FakeChain()
        val tracker = NonceTracker(chain.rpc(), now = { 0L })
        tracker.abandon(from.address, 100, BigInteger.valueOf(7), EthTransaction.Fees.Legacy(BigInteger.TEN), "0x" + "ab".repeat(32))
        chain.nonce = 8
        // Every RPC agrees nonce 7 is mined.
        assertEquals(BigInteger.valueOf(8), tracker.next(from.address, 100).value)
        assertNull(tracker.replacing(from.address, 100, BigInteger.valueOf(7)))
    }

    @Test
    fun `the launch sweep drops an abandoned send on one unverified read only once a later one agrees (#238)`() = runBlocking<Unit> {
        val chain = FakeChain()
        var changes = 0
        val tracker = NonceTracker(chain.rpc(), onAbandonedChange = { changes++ }, confirmAfterMs = 50)
        val hash = "0x" + "ab".repeat(32)
        fun abandon() = tracker.abandon(from.address, 100, BigInteger.valueOf(7), EthTransaction.Fees.Legacy(BigInteger.TEN), hash)
        chain.nonce = 8
        // The lying RPC is caught out by the read CONFIRM_AFTER_MS later: kept.
        abandon()
        val reads = java.util.concurrent.atomic.AtomicInteger()
        chain.onUrlReq["eth_getTransactionCount"] = { url, req ->
            if (req.getJSONArray("params").optString(1) != "latest") null
            else {
                // The first read: 8 from the first RPC, 7 and 6 from the others. After: 7 from all.
                val n = if (reads.getAndIncrement() < gnosis.rpcUrls.size) listOf(8L, 7L, 6L)[gnosis.rpcUrls.indexOf(url)] else 7L
                "\"result\":\"0x" + n.toString(16) + "\""
            }
        }
        changes = 0
        tracker.sweepMined()
        assertEquals(0, changes)
        assertEquals(hash, tracker.replacing(from.address, 100, BigInteger.valueOf(7))?.hash)
        // Every read says so, if only on one RPC's word: gone, within the same sweep — a first
        // sighting doesn't outlive the process, so a later launch would never settle it.
        chain.lieMined()
        tracker.sweepMined()
        assertEquals(1, changes)
        assertNull(tracker.replacing(from.address, 100, BigInteger.valueOf(7)))
    }

    @Test
    fun `a local nonce is honoured only while the chain may not have seen the send yet`() = runBlocking<Unit> {
        val chain = FakeChain()
        val now = AtomicLong(1_000_000L)
        val tracker = NonceTracker(chain.rpc(), { now.get() }, ttlMs = 60_000)
        tracker.markSent(from.address, 100, BigInteger.valueOf(7))
        now.addAndGet(59_999)
        assertEquals(BigInteger.valueOf(8), tracker.next(from.address, 100).value)
        // Long enough that every RPC would count it if it were still around: the chain's count again.
        now.addAndGet(1)
        assertEquals(BigInteger.valueOf(7), tracker.next(from.address, 100).value)
        // A clock set back since: not trusted either.
        tracker.markSent(from.address, 100, BigInteger.valueOf(7))
        now.addAndGet(-10)
        assertEquals(BigInteger.valueOf(7), tracker.next(from.address, 100).value)
        // Ending unconfirmed forgets only that send's own mark, never a later one's.
        now.addAndGet(10)
        tracker.markSent(from.address, 100, BigInteger.valueOf(8))
        tracker.forgetSent(from.address, 100, BigInteger.valueOf(7))
        assertEquals(BigInteger.valueOf(9), tracker.next(from.address, 100).value)
        tracker.forgetSent(from.address, 100, BigInteger.valueOf(8))
        assertEquals(BigInteger.valueOf(7), tracker.next(from.address, 100).value)
    }

    // ---- prepare ----

    @Test
    fun `a native send is priced with fresh reads`() = runBlocking<Unit> {
        val chain = FakeChain()
        val quote = sender(chain).prepare(request())
        assertEquals(to, quote.tx.to)
        assertEquals(BigInteger.ONE, quote.tx.value)
        assertEquals(0, quote.tx.data.size)
        assertEquals(BigInteger.valueOf(7), quote.tx.nonce)
        assertEquals(BigInteger.valueOf(21_000), quote.tx.gasLimit)
        assertEquals(EthTransaction.Fees.Eip1559(gwei + BigInteger.valueOf(28), gwei), quote.tx.fees)
        assertEquals((gwei + BigInteger.valueOf(28)) * BigInteger.valueOf(21_000) + BigInteger.ONE, quote.nativeTotal)
        assertNull(quote.tokenBalance)
    }

    @Test
    fun `an ERC-20 send calls the token contract and checks both balances`() = runBlocking<Unit> {
        val chain = FakeChain().apply { estimate = 52_000 }
        val quote = sender(chain).prepare(request(xbzz, 1_000))
        assertEquals(xbzz.address, quote.tx.to)
        assertEquals(BigInteger.ZERO, quote.tx.value)
        assertEquals(Erc20.transferData(to, BigInteger.valueOf(1_000)).toList(), quote.tx.data.toList())
        assertEquals(BigInteger.valueOf(62_400), quote.tx.gasLimit)
        assertEquals(BigInteger.valueOf(5_000), quote.tokenBalance)
        assertNull(quote.nativeTotal)
    }

    @Test
    fun `not enough of the token, or of the native currency for the fee, is said plainly`() = runBlocking<Unit> {
        val chain = FakeChain()
        val s = sender(chain)
        assertMessage("Not enough xBZZ: this account has 0.0000000000005 xBZZ") { s.prepare(request(xbzz, 5_001)) }
        chain.balance = BigInteger.valueOf(1_000)
        assertMessage("Not enough xDAI for the network fee") { s.prepare(request(xbzz, 10)) }
        assertMessage("Not enough xDAI for the amount and the network fee") { s.prepare(request(xdai, 10)) }
        chain.balance = BigInteger.ZERO
        assertMessage("This account has no xDAI") { s.prepare(request(xdai, 1)) }
    }

    @Test
    fun `Max sends all of a token, and all of the native currency less the fee`() = runBlocking<Unit> {
        val chain = FakeChain()
        val s = sender(chain)
        val token = s.prepare(request(xbzz, 1), all = true)
        assertEquals(BigInteger.valueOf(5_000), token.request.amount)
        val native = s.prepare(request(xdai, 1), all = true)
        assertEquals(chain.balance - native.tx.maxFee, native.request.amount)
        assertEquals(native.request.amount, native.tx.value)
        assertEquals(chain.balance, native.nativeTotal)
    }

    @Test
    fun `a transfer the token would refuse, and no RPC answering, say so`() = runBlocking<Unit> {
        val chain = FakeChain()
        // Error(string) "ERC20: transfer amount exceeds balance"
        val data = "0x08c379a0" + "20".padStart(64, '0') + "26".padStart(64, '0') +
            "45524332303a207472616e7366657220616d6f756e7420657863656564732062616c616e6365".padEnd(128, '0')
        chain.on["eth_estimateGas"] = { "\"error\":{\"code\":3,\"message\":\"execution reverted\",\"data\":\"$data\"}" }
        assertMessage("The xBZZ contract would refuse this transfer: ERC20: transfer amount exceeds balance") {
            sender(chain).prepare(request(xbzz, 10))
        }
        chain.on.clear()
        chain.down = true
        assertMessage("No RPC answered") { sender(chain).prepare(request()) }
    }

    @Test
    fun `a chain without a base fee gets a legacy transaction`() = runBlocking<Unit> {
        val chain = FakeChain().apply { baseFee = null }
        val quote = sender(chain).prepare(request())
        assertEquals(EthTransaction.Fees.Legacy(BigInteger.valueOf(3) * gwei), quote.tx.fees)
    }

    @Test
    fun `a quote goes stale after a minute`() = runBlocking<Unit> {
        val now = AtomicLong(1_000_000L)
        val s = sender(FakeChain()) { now.get() }
        val quote = s.prepare(request())
        assertFalse(s.isStale(quote))
        now.addAndGet(WalletSender.QUOTE_TTL_MS)
        assertTrue(s.isStale(quote))
        // A clock set back since: not trusted either.
        now.set(0)
        assertTrue(s.isStale(quote))
    }

    // ---- submit ----

    @Test
    fun `a confirmed send is signed, broadcast, and followed to its receipt`() = runBlocking<Unit> {
        val chain = FakeChain()
        val s = sender(chain)
        val quote = s.prepare(request())
        assertEquals(WalletSender.Submit.STARTED, s.submit(quote, signer()))
        val pending = s.awaitStage { it == SendStatus.Stage.Pending }
        val signed = quote.tx.sign(key.copyOf(), from.address)
        assertEquals(signed.hash, pending.hash)
        assertEquals(listOf(signed.raw), chain.sent)
        chain.receipt = """{"status":"0x1","blockNumber":"0x2e3f070","gasUsed":"0x5208","effectiveGasPrice":"0x3b9aca0e"}"""
        val done = s.awaitStage { it is SendStatus.Stage.Confirmed }
        val stage = done.stage as SendStatus.Stage.Confirmed
        assertEquals(0x2e3f070L, stage.block)
        assertEquals(BigInteger.valueOf(21_000) * BigInteger.valueOf(0x3b9aca0e), stage.feePaid)
        assertTrue(done.done)
        // The next send takes the next nonce even though the chain still says 7.
        assertEquals(BigInteger.valueOf(8), s.prepare(request()).tx.nonce)
    }

    @Test
    fun `a reverted transfer and a missing receipt are told apart from success`() = runBlocking<Unit> {
        val chain = FakeChain()
        val s = sender(chain)
        s.submit(s.prepare(request(xbzz, 1)), signer())
        s.awaitStage { it == SendStatus.Stage.Unconfirmed }
        s.checkAgain()
        chain.receipt = """{"status":"0x0","blockNumber":"0x10","gasUsed":"0x100","effectiveGasPrice":"0x2"}"""
        val reverted = s.awaitStage { it is SendStatus.Stage.Reverted }
        assertEquals(SendStatus.Stage.Reverted(16, BigInteger.valueOf(512)), reverted.stage)
    }

    @Test
    fun `a node's refusal means not sent and the nonce is read again`() = runBlocking<Unit> {
        val chain = FakeChain()
        val s = sender(chain)
        chain.on["eth_sendRawTransaction"] = { "\"error\":{\"code\":-32000,\"message\":\"nonce too low\"}" }
        s.submit(s.prepare(request()), signer())
        val failed = s.awaitStage { it is SendStatus.Stage.Failed }.stage as SendStatus.Stage.Failed
        assertFalse(failed.mayHaveGone)
        assertTrue(failed.message, failed.message.startsWith("Not sent: nonce 7 was already used"))
    }

    @Test
    fun `a nonce reported used by the very transaction that went out counts as sent`() = runBlocking<Unit> {
        val chain = FakeChain()
        val s = sender(chain)
        chain.on["eth_sendRawTransaction"] = { "\"error\":{\"code\":-32000,\"message\":\"nonce too low\"}" }
        chain.receipt = """{"status":"0x1","blockNumber":"0x10","gasUsed":"0x5208","effectiveGasPrice":"0x1"}"""
        s.submit(s.prepare(request()), signer())
        s.awaitStage { it is SendStatus.Stage.Confirmed }
    }

    @Test
    fun `no answer at all may have gone out, and Try again resends the very same bytes`() = runBlocking<Unit> {
        val chain = FakeChain()
        val s = sender(chain)
        val quote = s.prepare(request())
        chain.on["eth_sendRawTransaction"] = { req ->
            synchronized(chain.sent) { chain.sent += req.getJSONArray("params").getString(0) }
            throw IOException("timed out")
        }
        chain.on["eth_getTransactionReceipt"] = { throw IOException("timed out") }
        s.submit(quote, signer())
        val failed = s.awaitStage { it is SendStatus.Stage.Failed }.stage as SendStatus.Stage.Failed
        assertTrue(failed.mayHaveGone)
        chain.on.clear()
        s.retry()
        s.awaitStage { it == SendStatus.Stage.Pending }
        assertEquals(1, chain.sent.toSet().size)
        assertTrue(chain.sent.size >= 2)
    }

    @Test
    fun `changes hands a slow follower every status, where status would skip a Confirmed acknowledged meanwhile`() = runBlocking<Unit> {
        val chain = FakeChain()
        val s = sender(chain)
        // Two followers, each stuck on its first send status (as SafeAccounts is, writing it to disk).
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        fun follow(flow: kotlinx.coroutines.flow.Flow<SendStatus?>): Pair<MutableList<SendStatus?>, kotlinx.coroutines.Job> {
            val seen = java.util.Collections.synchronizedList(mutableListOf<SendStatus?>())
            // Undispatched: subscribed before launch returns, so before the send starts.
            val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                flow.collect {
                    seen += it
                    if (it != null) gate.await()
                }
            }
            return seen to job
        }
        val (viaStatus, statusJob) = follow(s.status)
        val (viaChanges, changesJob) = follow(s.changes)
        assertEquals(listOf(null), viaStatus.toList())
        chain.receipt = """{"status":"0x1","blockNumber":"0x10","gasUsed":"0x5208","effectiveGasPrice":"0x1"}"""
        s.submit(s.prepare(request()), signer())
        s.awaitStage { it is SendStatus.Stage.Confirmed }
        // Done, while both followers are still busy with the send's first status.
        s.acknowledge()
        gate.complete(Unit)
        withTimeout(5_000) {
            while (viaChanges.lastOrNull() != null || viaChanges.size < 2) delay(10)
            while (viaStatus.lastOrNull() != null || viaStatus.size < 2) delay(10)
        }
        statusJob.cancel()
        changesJob.cancel()
        // status conflates (its follower resumes on Done's null, how R4-M2 happens); changes has every step, in order.
        assertEquals(null, viaStatus.last())
        val stages = viaChanges.map { it?.stage }
        val pending = stages.indexOf(SendStatus.Stage.Pending)
        val confirmed = stages.indexOfFirst { it is SendStatus.Stage.Confirmed }
        assertTrue("$stages", pending >= 0 && confirmed > pending && stages.last() == null)
    }

    @Test
    fun `a send that may have gone out outlives Done and blocks a fresh signature until Try again settles it`() = runBlocking<Unit> {
        val chain = FakeChain()
        val s = sender(chain)
        val quote = s.prepare(request())
        // Every RPC times out, but one of them took it: it's in the mempool.
        chain.on["eth_sendRawTransaction"] = { req ->
            synchronized(chain.sent) { chain.sent += req.getJSONArray("params").getString(0) }
            chain.nonce = 8
            throw IOException("timed out")
        }
        chain.on["eth_getTransactionReceipt"] = { throw IOException("timed out") }
        s.submit(quote, signer())
        val failed = s.awaitStage { it is SendStatus.Stage.Failed }
        assertTrue(failed.mayHaveGone)
        assertTrue(failed.unresolved)
        // Done / Back: the status and its signed bytes stay.
        s.acknowledge()
        assertEquals(failed, s.status.value)
        // The same payment entered again is priced (the chain's pending count says 8) but not signed.
        chain.on.remove("eth_getTransactionReceipt")
        val second = s.prepare(request())
        assertEquals(WalletSender.Submit.BUSY, s.submit(second, signer()))
        assertEquals(1, chain.sent.toSet().size)
        // Try again settles it with the very same bytes.
        chain.on.clear()
        s.retry()
        s.awaitStage { it == SendStatus.Stage.Pending }
        assertEquals(1, chain.sent.toSet().size)
        chain.receipt = """{"status":"0x1","blockNumber":"0x10","gasUsed":"0x5208","effectiveGasPrice":"0x1"}"""
        s.awaitStage { it is SendStatus.Stage.Confirmed }
        s.acknowledge()
        assertNull(s.status.value)
    }

    @Test
    fun `a send that ends unconfirmed stays until kept waiting on or given up, and leaves the next nonce to the chain`() = runBlocking<Unit> {
        val chain = FakeChain()
        val s = sender(chain)
        s.submit(s.prepare(request()), signer())
        val unconfirmed = s.awaitStage { it == SendStatus.Stage.Unconfirmed }
        assertTrue(unconfirmed.unresolved)
        // Done / Back don't drop a send that may yet land, and no second one is signed beside it.
        s.acknowledge()
        assertEquals(unconfirmed, s.status.value)
        assertEquals(WalletSender.Submit.BUSY, s.submit(s.prepare(request()), signer()))
        assertEquals(1, chain.sent.size)
        s.discard()
        assertNull(s.status.value)
        // Dropped from every pool meanwhile: nonce 7 again, not 8 past a gap that would never fill.
        assertEquals(BigInteger.valueOf(7), s.prepare(request()).tx.nonce)
    }

    @Test
    fun `given up on while still in a pool, the next send takes its nonce at a higher fee, and so replaces it`() = runBlocking<Unit> {
        val chain = FakeChain()
        val s = sender(chain)
        s.submit(s.prepare(request()), signer())
        val first = s.awaitStage { it == SendStatus.Stage.Unconfirmed }
        s.discard()
        // The RPC still holds it: pending 8, mined 7. Not 8 beside it — 7, outbidding it.
        chain.nonce = 8
        chain.mined = 7
        val next = s.prepare(request(amount = 2))
        assertEquals(BigInteger.valueOf(7), next.tx.nonce)
        assertEquals(first.hash, next.replaces)
        val old = first.quote.tx.fees as EthTransaction.Fees.Eip1559
        val bid = next.tx.fees as EthTransaction.Fees.Eip1559
        assertTrue(bid.maxFeePerGas * BigInteger.TEN > old.maxFeePerGas * BigInteger.valueOf(11))
        assertTrue(bid.maxPriorityFeePerGas * BigInteger.TEN > old.maxPriorityFeePerGas * BigInteger.valueOf(11))
        // Once the replacement is out, the one after goes past it.
        assertEquals(WalletSender.Submit.STARTED, s.submit(next, signer()))
        s.awaitStage { it == SendStatus.Stage.Unconfirmed }
        s.discard()
        chain.mined = 8
        val after = s.prepare(request())
        assertEquals(BigInteger.valueOf(8), after.tx.nonce)
        assertNull(after.replaces)
    }

    @Test
    fun `a send that may have gone out and every node keeps refusing oddly can be given up, and nothing else is blocked after`() = runBlocking<Unit> {
        val chain = FakeChain()
        val s = sender(chain)
        chain.on["eth_sendRawTransaction"] = { req ->
            synchronized(chain.sent) { chain.sent += req.getJSONArray("params").getString(0) }
            "\"error\":{\"code\":-32010,\"message\":\"FeeTooLowToCompete\"}"
        }
        s.submit(s.prepare(request()), signer())
        var failed = s.awaitStage { it is SendStatus.Stage.Failed }
        assertTrue(failed.mayHaveGone)
        repeat(2) {
            s.retry()
            failed = s.awaitStage { it is SendStatus.Stage.Failed }
            assertTrue(failed.mayHaveGone)
        }
        s.acknowledge()
        assertEquals(failed, s.status.value)
        // Stop tracking it: gone, and a fresh send is signed — in its place, not beside it.
        s.discard()
        assertNull(s.status.value)
        chain.on.clear()
        val next = s.prepare(request())
        assertEquals(BigInteger.valueOf(7), next.tx.nonce)
        assertEquals(failed.hash, next.replaces)
        assertEquals(WalletSender.Submit.STARTED, s.submit(next, signer()))
        s.awaitStage { it == SendStatus.Stage.Pending || it == SendStatus.Stage.Unconfirmed }
    }

    @Test
    fun `a send certainly not sent, or never signed, leaves no nonce to replace`() = runBlocking<Unit> {
        val chain = FakeChain()
        val s = sender(chain)
        chain.on["eth_sendRawTransaction"] = { "\"error\":{\"code\":-32000,\"message\":\"nonce too high\"}" }
        s.submit(s.prepare(request()), signer())
        s.awaitStage { it is SendStatus.Stage.Failed }
        s.discard()
        chain.on.clear()
        chain.nonce = 8
        chain.mined = 7
        assertNull(s.prepare(request()).replaces)

        // Discarded (the wallet deleted) while the key was at work: nothing goes out.
        val gate = java.util.concurrent.CountDownLatch(1)
        val signing = java.util.concurrent.CountDownLatch(1)
        val sentBefore = chain.sent.size
        s.submit(s.prepare(request())) { tx -> signing.countDown(); gate.await(); tx.sign(key.copyOf(), from.address) }
        assertTrue(signing.await(5, java.util.concurrent.TimeUnit.SECONDS))
        s.discard()
        gate.countDown()
        Thread.sleep(200)
        assertNull(s.status.value)
        assertEquals(sentBefore, chain.sent.size)
    }

    @Test
    fun `a replacement bids at least an eighth over what it replaces`() {
        val old = EthTransaction.Fees.Eip1559(BigInteger.valueOf(800), BigInteger.valueOf(80))
        assertEquals(
            EthTransaction.Fees.Eip1559(BigInteger.valueOf(901), BigInteger.valueOf(91)),
            GasOracle.replacing(EthTransaction.Fees.Eip1559(BigInteger.valueOf(500), BigInteger.valueOf(10)), old),
        )
        assertEquals(
            EthTransaction.Fees.Eip1559(BigInteger.valueOf(2_000), BigInteger.valueOf(200)),
            GasOracle.replacing(EthTransaction.Fees.Eip1559(BigInteger.valueOf(2_000), BigInteger.valueOf(200)), old),
        )
        assertEquals(
            EthTransaction.Fees.Legacy(BigInteger.valueOf(113)),
            GasOracle.replacing(EthTransaction.Fees.Legacy(BigInteger.valueOf(50)), EthTransaction.Fees.Legacy(BigInteger.valueOf(100))),
        )
    }

    @Test
    fun `a replacement outbids across fee types too`() {
        // A legacy send replaced by an EIP-1559 one: the old gas price is both cap and tip to beat.
        assertEquals(
            EthTransaction.Fees.Eip1559(BigInteger.valueOf(113), BigInteger.valueOf(113)),
            GasOracle.replacing(EthTransaction.Fees.Eip1559(BigInteger.valueOf(30), BigInteger.valueOf(2)), EthTransaction.Fees.Legacy(BigInteger.valueOf(100))),
        )
        // An EIP-1559 send replaced by a legacy one: outbid its cap (and so its tip).
        assertEquals(
            EthTransaction.Fees.Legacy(BigInteger.valueOf(901)),
            GasOracle.replacing(EthTransaction.Fees.Legacy(BigInteger.valueOf(50)), EthTransaction.Fees.Eip1559(BigInteger.valueOf(800), BigInteger.valueOf(80))),
        )
        assertEquals(
            EthTransaction.Fees.Legacy(BigInteger.valueOf(5_000)),
            GasOracle.replacing(EthTransaction.Fees.Legacy(BigInteger.valueOf(5_000)), EthTransaction.Fees.Eip1559(BigInteger.valueOf(800), BigInteger.valueOf(80))),
        )
    }

    // ---- a transaction desktop Freedom composed (#113) ----

    private fun call(data: String = "0xa9059cbb", value: Long = 0) =
        SendRequest(gnosis, xdai, from, xbzz.address!!, BigInteger.valueOf(value), DappCall(null, data.hexToBytes(), null))

    @Test
    fun `a composed call goes out as asked, value zero allowed, and only in the native currency`() = runBlocking<Unit> {
        val chain = FakeChain()
        chain.estimate = 50_000
        val s = sender(chain)
        val quote = s.prepare(call())
        assertEquals(xbzz.address, quote.tx.to)
        assertEquals(BigInteger.ZERO, quote.tx.value)
        assertEquals("a9059cbb", quote.tx.data.joinToString("") { "%02x".format(it) })
        assertEquals(BigInteger.valueOf(60_000), quote.tx.gasLimit)
        // A plain value transfer with no data is a composed request too.
        assertEquals(0, s.prepare(call(data = "0x", value = 5)).tx.data.size)
        assertTrue(runCatching { SendRequest(gnosis, xbzz, from, to, BigInteger.ONE, DappCall(null, ByteArray(0), null)) }.isFailure)
        // The wallet's own sends still need something to send.
        assertTrue(runCatching { request(amount = 0) }.isFailure)
    }

    @Test
    fun `a composed call a contract would refuse says so without calling it a transfer`() {
        val e = WalletSender.estimateFailure(ChainRpcException.Rpc(3, "execution reverted: nope", null), call())
        assertEquals("The contract would refuse this transaction: nope", e.message)
    }

    @Test
    fun `submitAndAwaitBroadcast answers with the hash once a node took it, or why not`() = runBlocking<Unit> {
        val chain = FakeChain()
        val s = sender(chain)
        val quote = s.prepare(call())
        val sent = s.submitAndAwaitBroadcast(quote, signer()) as WalletSender.Broadcast.Sent
        assertEquals(quote.tx.sign(key.copyOf(), from.address).hash, sent.hash)
        // Still unresolved (no receipt yet): the next is refused, nothing signed.
        assertEquals(WalletSender.Broadcast.Busy, s.submitAndAwaitBroadcast(s.prepare(call()), signer()))
        s.discard()

        chain.on["eth_sendRawTransaction"] = { """"error":{"code":-32000,"message":"insufficient funds for gas * price + value"}""" }
        val refused = s.submitAndAwaitBroadcast(s.prepare(call()), signer()) as WalletSender.Broadcast.Failed
        assertFalse(refused.mayHaveGone)
        s.acknowledge()

        val old = WalletSender(chain.rpc(), scope, clock = { System.currentTimeMillis() + WalletSender.QUOTE_TTL_MS }, pollMs = 10, confirmTimeoutMs = 300)
        old.awaitRestored()
        assertEquals(WalletSender.Broadcast.Stale(droppedSigned = false), old.submitAndAwaitBroadcast(quote, signer()))
    }

    @Test
    fun `submitAndAwaitBroadcast answers a Ledger refusal as a rejection and a quote that aged on it as stale`() = runBlocking<Unit> {
        val chain = FakeChain()
        val now = AtomicLong(1_000L)
        val s = sender(chain) { now.get() }
        for (kind in listOf(LedgerException.Kind.REJECTED, LedgerException.Kind.CANCELLED)) {
            assertEquals(WalletSender.Broadcast.Rejected, s.submitAndAwaitBroadcast(s.prepare(call())) { throw LedgerException(kind) })
            s.acknowledge()
        }
        val timedOut = s.submitAndAwaitBroadcast(s.prepare(call())) { throw LedgerException(LedgerException.Kind.TIMEOUT) }
        assertTrue(timedOut is WalletSender.Broadcast.Failed)
        s.acknowledge()
        // Reviewed on the device past the allowance: dropped unsent, to be priced again.
        val aged = s.submitAndAwaitBroadcast(s.prepare(call())) { tx ->
            now.addAndGet(WalletSender.SIGNED_TTL_MS)
            tx.sign(key.copyOf(), from.address)
        }
        assertEquals(WalletSender.Broadcast.Stale(droppedSigned = true), aged)
        assertTrue(chain.sent.isEmpty())
    }

    @Test
    fun `a composed call that may have gone out survives the process with its data`() = runBlocking<Unit> {
        val chain = FakeChain()
        val s = sender(chain, journal = FileSendJournal(journalFile()))
        chain.on["eth_sendRawTransaction"] = { throw IOException("timed out") }
        chain.on["eth_getTransactionReceipt"] = { throw IOException("timed out") }
        val failed = s.submitAndAwaitBroadcast(s.prepare(call(data = "0xa9059cbb00")), signer()) as WalletSender.Broadcast.Failed
        assertTrue(failed.mayHaveGone)
        val again = sender(chain, journal = FileSendJournal(journalFile()))
        val restored = again.status.value?.quote?.request?.dapp!!
        assertEquals("a9059cbb00", restored.data.toHex())
        assertEquals(null, restored.origin)
        assertEquals(s.status.value, again.status.value)
    }

    @Test
    fun `a send that may have gone out survives the process, and Try again after it resends the very same bytes`() = runBlocking<Unit> {
        val chain = FakeChain()
        val s = sender(chain, journal = FileSendJournal(journalFile()))
        chain.on["eth_sendRawTransaction"] = { req ->
            synchronized(chain.sent) { chain.sent += req.getJSONArray("params").getString(0) }
            chain.nonce = 8
            throw IOException("timed out")
        }
        chain.on["eth_getTransactionReceipt"] = { throw IOException("timed out") }
        s.submit(s.prepare(request(token = xbzz, amount = 42)), signer())
        val failed = s.awaitStage { it is SendStatus.Stage.Failed }
        assertTrue(failed.mayHaveGone)

        // The process dies; the next one finds it as it was, and signs nothing beside it.
        val again = sender(chain, journal = FileSendJournal(journalFile()))
        assertEquals(failed, again.status.value)
        chain.on.remove("eth_getTransactionReceipt")
        assertEquals(WalletSender.Submit.BUSY, again.submit(again.prepare(request()), signer()))
        chain.on.clear()
        again.retry()
        again.awaitStage { it == SendStatus.Stage.Pending }
        assertEquals(1, chain.sent.toSet().size)
        chain.receipt = """{"status":"0x1","blockNumber":"0x10","gasUsed":"0x5208","effectiveGasPrice":"0x1"}"""
        again.awaitStage { it is SendStatus.Stage.Confirmed }
        // Settled: nothing left on disk.
        assertFalse(journalFile().exists())
        again.acknowledge()
        assertNull(sender(chain, journal = FileSendJournal(journalFile())).status.value)
    }

    @Test
    fun `a site's call goes out with its own data and no value, gas as it named, and survives a restart as the site's`() = runBlocking<Unit> {
        val chain = FakeChain()
        chain.estimate = 40_000
        val s = sender(chain, journal = FileSendJournal(journalFile()))
        val data = byteArrayOf(0xa9.toByte(), 0x05, 0x9c.toByte(), 0xbb.toByte())
        val call = SendRequest(gnosis, xdai, from, to, BigInteger.ZERO, DappCall("https://app.example", data, BigInteger.valueOf(90_000)))
        val quote = s.prepare(call)
        assertEquals(BigInteger.ZERO, quote.tx.value)
        assertTrue(quote.tx.data.contentEquals(data))
        assertEquals(BigInteger.valueOf(90_000), quote.tx.gasLimit)
        // A named gas limit below the estimate would only fail on chain: the estimate's headroom wins.
        val low = s.prepare(call.copy(dapp = DappCall("https://app.example", data, BigInteger.valueOf(21_000))))
        assertEquals(BigInteger.valueOf(48_000), low.tx.gasLimit)
        chain.on["eth_getTransactionReceipt"] = { throw IOException("timed out") }
        s.submit(quote, signer())
        val pending = s.awaitStage { it == SendStatus.Stage.Pending }
        assertEquals(1, chain.sent.size)

        val again = sender(chain, journal = FileSendJournal(journalFile()))
        val restored = again.status.value!!.quote.request
        assertEquals("https://app.example", restored.dapp?.origin)
        assertTrue(restored.dapp!!.data.contentEquals(data))
        assertEquals(BigInteger.valueOf(90_000), restored.dapp!!.gasLimit)
        assertEquals(pending.hash, again.status.value!!.hash)
    }

    @Test
    fun `a Safe's own call keeps its label across a restart, and names the Safe as who asked`() = runBlocking<Unit> {
        val chain = FakeChain()
        chain.estimate = 250_000
        val s = sender(chain, journal = FileSendJournal(journalFile()))
        val data = byteArrayOf(0x16, 0x88.toByte(), 0xf0.toByte(), 0xb9.toByte())
        val label = SafeCallLabel("0x6d21181D5e0F3a4a438F0CC65FACFd418443b096", "Team", activates = true)
        val quote = s.prepare(SendRequest(gnosis, xdai, from, SafeProtocol.FACTORY, BigInteger.ZERO, DappCall(null, data, null, label)))
        // Code is involved: the estimate plus desktop's 20%.
        assertEquals(BigInteger.valueOf(300_000), quote.tx.gasLimit)
        chain.on["eth_getTransactionReceipt"] = { throw IOException("timed out") }
        s.submit(quote, signer())
        s.awaitStage { it == SendStatus.Stage.Pending }
        val restored = sender(chain, journal = FileSendJournal(journalFile())).status.value!!.quote.request.dapp!!
        assertEquals(label, restored.safe)
        assertNull(restored.origin)
        assertEquals("Safe “Team” (activation)", baby.freedom.mobile.browser.dappRequester(restored))
        assertThrows(IllegalArgumentException::class.java) { DappCall("https://app.example", data, null, label) }
    }

    @Test
    fun `the node's funding keeps its label across a restart, and names the node as what it's for`() = runBlocking<Unit> {
        // #115: the batch it buys is connected once it's mined, even by a later process.
        val chain = FakeChain()
        chain.estimate = 250_000
        val s = sender(chain, journal = FileSendJournal(journalFile()))
        val node = "0xD5572300E441b77b72bdd318BBFA7b97A1F03096"
        val nonce = ByteArray(32) { 7 }
        val data = SwarmFunder.calldata(node, SwarmFunder.XDAI_FOR_NODE_WEI, BigInteger.TEN, BigInteger.TEN, 17, nonce, true)
        val label = SwarmFundLabel(node, SwarmFunder.batchId(nonce), 17, 2)
        val value = BigInteger("53000000000000000")
        val quote = s.prepare(SendRequest(gnosis, xdai, from, SwarmFunder.ADDRESS, value, DappCall(null, data, null, swarm = label)))
        chain.on["eth_getTransactionReceipt"] = { throw IOException("timed out") }
        s.submit(quote, signer())
        s.awaitStage { it == SendStatus.Stage.Pending }
        val restored = sender(chain, journal = FileSendJournal(journalFile())).status.value!!.quote.request
        assertEquals(label, restored.dapp!!.swarm)
        assertEquals(value, restored.amount)
        assertEquals("your Swarm node (funding and a postage stamp)", baby.freedom.mobile.browser.dappRequester(restored.dapp!!))
        assertThrows(IllegalArgumentException::class.java) { DappCall("https://app.example", data, null, swarm = label) }
    }

    @Test
    fun `a site's call with no value still needs the fee, and a revert names the contract`() = runBlocking<Unit> {
        val chain = FakeChain()
        chain.balance = BigInteger.ZERO
        val s = sender(chain)
        val call = SendRequest(gnosis, xdai, from, to, BigInteger.ZERO, DappCall("https://app.example", byteArrayOf(1, 2, 3, 4), null))
        val poor = runCatching { s.prepare(call) }.exceptionOrNull()
        assertTrue(poor?.message, poor?.message?.startsWith("Not enough xDAI for the network fee") == true)
        chain.balance = BigInteger.TEN.pow(18)
        chain.on["eth_estimateGas"] = { """"error":{"code":3,"message":"execution reverted: nope","data":"0x"}""" }
        val reverted = runCatching { s.prepare(call) }.exceptionOrNull()
        assertTrue(reverted?.message, reverted?.message?.startsWith("The contract would refuse this transaction") == true)
    }

    @Test
    fun `a send the process died sending comes back as may-have-gone, and one waiting for its receipt goes on waiting`() = runBlocking<Unit> {
        val chain = FakeChain()
        // The first process's journal: it writes nothing once the process is "killed".
        var dead = false
        val file = FileSendJournal(journalFile())
        val first = object : SendJournal {
            override fun save(state: SendJournal.State) = dead || file.save(state)
            override fun load() = file.load()
        }
        val s = sender(chain, journal = first)
        val broadcasting = java.util.concurrent.CountDownLatch(1)
        val hold = java.util.concurrent.CountDownLatch(1)
        chain.on["eth_sendRawTransaction"] = { req ->
            synchronized(chain.sent) { chain.sent += req.getJSONArray("params").getString(0) }
            broadcasting.countDown()
            hold.await()
            throw IOException("killed")
        }
        s.submit(s.prepare(request()), signer())
        assertTrue(broadcasting.await(5, java.util.concurrent.TimeUnit.SECONDS))
        // Killed right here, mid-broadcast: the signed bytes were saved before they went out.
        dead = true
        val again = sender(chain, journal = FileSendJournal(journalFile()))
        val restored = again.status.value!!
        assertEquals(SendStatus.Stage.Failed(WalletSender.INTERRUPTED, true), restored.stage)
        assertEquals(s.status.value!!.hash, restored.hash)
        hold.countDown()
        chain.on.clear()
        again.retry()
        again.awaitStage { it == SendStatus.Stage.Pending }
        assertEquals(1, chain.sent.toSet().size)

        // Killed again while waiting for the receipt: the next process follows it to the end.
        val third = sender(chain, journal = FileSendJournal(journalFile()))
        assertEquals(SendStatus.Stage.Pending, third.status.value!!.stage)
        chain.receipt = """{"status":"0x1","blockNumber":"0x10","gasUsed":"0x5208","effectiveGasPrice":"0x1"}"""
        third.awaitStage { it is SendStatus.Stage.Confirmed }
        assertEquals(1, chain.sent.toSet().size)
    }

    @Test
    fun `a send given up on is still replaced after a restart, and deleting the wallet leaves no payment on disk`() = runBlocking<Unit> {
        val chain = FakeChain()
        val s = sender(chain, journal = FileSendJournal(journalFile()))
        s.submit(s.prepare(request()), signer())
        val unconfirmed = s.awaitStage { it == SendStatus.Stage.Unconfirmed }
        assertEquals(unconfirmed, sender(chain, journal = FileSendJournal(journalFile())).status.value)
        // Stop tracking (or the wallet deleted): the payment goes, only its nonce, fee and hash stay.
        s.discard()
        s.persistNow()
        val onDisk = journalFile().readText()
        assertFalse(onDisk.contains(to.substring(2), ignoreCase = true))
        assertFalse(onDisk.contains("\"send\""))

        chain.nonce = 8
        chain.mined = 7
        val again = sender(chain, journal = FileSendJournal(journalFile()))
        assertNull(again.status.value)
        val next = again.prepare(request(amount = 2))
        assertEquals(BigInteger.valueOf(7), next.tx.nonce)
        assertEquals(unconfirmed.hash, next.replaces)
        // Once the chain has mined that nonce there's nothing to replace, and the record goes.
        chain.mined = 8
        assertNull(again.prepare(request()).replaces)
        again.persistNow()
        assertFalse(journalFile().exists())
    }

    @Test
    fun `a send whose bytes can't be saved first is not broadcast`() = runBlocking<Unit> {
        val chain = FakeChain()
        val broken = object : SendJournal {
            override fun save(state: SendJournal.State) = state.send == null
            override fun load(): SendJournal.State? = null
        }
        val s = sender(chain, journal = broken)
        s.submit(s.prepare(request()), signer())
        val failed = s.awaitStage { it is SendStatus.Stage.Failed }
        assertFalse(failed.mayHaveGone)
        assertTrue(chain.sent.isEmpty())
    }

    @Test
    fun `an abandoned send the chain has mined is dropped at launch, whichever account it came from`() = runBlocking<Unit> {
        val chain = FakeChain()
        val s = sender(chain, journal = FileSendJournal(journalFile()))
        s.submit(s.prepare(request()), signer())
        s.awaitStage { it == SendStatus.Stage.Unconfirmed }
        // The wallet deleted: no send is ever prepared from this account again.
        s.discard()
        s.persistNow()
        assertTrue(journalFile().readText().contains(from.address.substring(2), ignoreCase = true))

        // Not mined yet: the next launch keeps it.
        chain.mined = 7
        sender(chain, journal = FileSendJournal(journalFile()))
        Thread.sleep(300)
        assertTrue(journalFile().exists())

        // Mined: the next launch drops it, and with it the account's address and the hash.
        chain.mined = 8
        sender(chain, journal = FileSendJournal(journalFile()))
        val deadline = System.currentTimeMillis() + 5_000
        while (journalFile().exists() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertFalse(journalFile().exists())
    }

    @Test
    fun `one malformed abandoned entry loses neither the others nor the send`() = runBlocking<Unit> {
        val chain = FakeChain()
        val s = sender(chain, journal = FileSendJournal(journalFile()))
        s.submit(s.prepare(request()), signer())
        val unconfirmed = s.awaitStage { it == SendStatus.Stage.Unconfirmed }
        val o = JSONObject(journalFile().readText())
        val fees = JSONObject().put("gasPrice", "5")
        o.put(
            "abandoned",
            JSONObject()
                .put("100:0xaa", JSONObject().put("nonce", "not a number").put("fees", fees).put("hash", "0x01"))
                .put("100:0xbb", JSONObject().put("fees", fees).put("hash", "0x02"))
                .put("100:0xcc", "not an object")
                .put("100:0xdd", JSONObject().put("nonce", "3").put("fees", fees).put("hash", "0x04")),
        )
        journalFile().writeText(o.toString())
        val state = FileSendJournal(journalFile()).load()!!
        assertEquals(unconfirmed, state.send!!.status)
        assertEquals(setOf("100:0xdd"), state.abandoned.keys)
        assertEquals(BigInteger.valueOf(3), state.abandoned.getValue("100:0xdd").nonce)
    }

    @Test
    fun `the journal is read and written off the calling thread, which never waits on storage`() = runBlocking<Unit> {
        val chain = FakeChain()
        val file = FileSendJournal(journalFile())
        // A first process leaves a send that may have gone out.
        val first = sender(chain, journal = file)
        chain.on["eth_sendRawTransaction"] = { throw IOException("timed out") }
        chain.on["eth_getTransactionReceipt"] = { throw IOException("timed out") }
        first.submit(first.prepare(request()), signer())
        val failed = first.awaitStage { it is SendStatus.Stage.Failed }
        val saved = file.load()!!.send!!
        chain.on.clear()
        // Still in a pool, not mined: the next send replaces it.
        chain.nonce = 8
        chain.mined = 7

        // Storage as slow as it gets: nothing is read or written until it's let go.
        val gate = java.util.concurrent.CountDownLatch(1)
        val slow = object : SendJournal {
            override fun save(state: SendJournal.State): Boolean {
                gate.await()
                return file.save(state)
            }

            override fun load(): SendJournal.State? {
                gate.await()
                return file.load()
            }
        }
        lateinit var s: WalletSender
        returnsWhileHeld(gate) {
            s = WalletSender(chain.rpc(), scope, clock = { clock.get() }, pollMs = 10, confirmTimeoutMs = CONFIRM_TIMEOUT_MS, journal = slow)
            // Not read back yet: nothing is signed meanwhile, and a discard (the wallet deleted) waits for it.
            assertNull(s.status.value)
            assertEquals(WalletSender.Submit.BUSY, s.submit(first.status.value!!.quote, signer()))
            s.discard()
        }
        gate.countDown()
        s.awaitRestored()
        // The discard applied to what was read back: the send is given up on and replaced.
        assertNull(s.status.value)
        assertEquals(failed.hash, s.prepare(request()).replaces)

        // Written off the calling thread: Try again, Keep waiting and Stop tracking return at once.
        val stuck = java.util.concurrent.CountDownLatch(1)
        val blocking = object : SendJournal {
            override fun save(state: SendJournal.State): Boolean {
                stuck.await()
                return true
            }

            override fun load(): SendJournal.State? = file.load()
        }
        FileSendJournal(journalFile()).save(SendJournal.State(saved, emptyMap()))
        val third = WalletSender(chain.rpc(), scope, clock = { clock.get() }, pollMs = 10, confirmTimeoutMs = CONFIRM_TIMEOUT_MS, journal = blocking)
        third.awaitRestored()
        assertEquals(failed, third.status.value)
        returnsWhileHeld(stuck) {
            third.retry()
            third.discard()
            third.acknowledge()
        }
        assertNull(third.status.value)
        stuck.countDown()
        // The Stop tracking landed while Try again's write was held up: those bytes never go out.
        delay(300)
        assertTrue(chain.sent.isEmpty())
        assertNull(third.status.value)
    }

    @Test
    fun `a save whose directory fsync fails is not a save, and nothing is broadcast`() = runBlocking<Unit> {
        val chain = FakeChain()
        val unsynced = FileSendJournal(journalFile()) { false }
        // The file itself is written; its rename just isn't known to be on flash.
        assertFalse(unsynced.save(SendJournal.State(null, mapOf("100:0xabc" to NonceTracker.Abandoned(BigInteger.ONE, EthTransaction.Fees.Legacy(BigInteger.TEN), "0x01")))))
        // Deleting a file that isn't there needs no sync.
        journalFile().delete()
        assertTrue(unsynced.save(SendJournal.State(null, emptyMap())))
        val s = sender(chain, journal = unsynced)
        s.submit(s.prepare(request()), signer())
        val failed = s.awaitStage { it is SendStatus.Stage.Failed }
        assertFalse(failed.mayHaveGone)
        assertTrue(chain.sent.isEmpty())
        // The unsynced save did rename the Broadcasting send into place; the
        // failure is written over it, so a restart (the process killed before
        // anything else is written) doesn't offer to send what the page said
        // was never sent.
        assertNull(FileSendJournal(journalFile()).load()?.send)
        val restarted = sender(chain, journal = FileSendJournal(journalFile()))
        assertNull(restarted.status.value)
        assertTrue(chain.sent.isEmpty())
    }

    @Test
    fun `an unreadable or tampered journal reads as no send`() {
        journalFile().parentFile!!.mkdirs()
        journalFile().writeText("{not json")
        assertNull(FileSendJournal(journalFile()).load())
        journalFile().writeText("[".repeat(100_000))
        assertNull(FileSendJournal(journalFile()).load())
    }

    @Test
    fun `on Try again a used nonce may be this very transaction, so it's followed, never reviewed again`() = runBlocking<Unit> {
        val chain = FakeChain()
        val s = sender(chain)
        val quote = s.prepare(request())
        chain.on["eth_sendRawTransaction"] = { req ->
            synchronized(chain.sent) { chain.sent += req.getJSONArray("params").getString(0) }
            throw IOException("timed out")
        }
        chain.on["eth_getTransactionReceipt"] = { throw IOException("timed out") }
        s.submit(quote, signer())
        assertTrue((s.awaitStage { it is SendStatus.Stage.Failed }.stage as SendStatus.Stage.Failed).mayHaveGone)
        // It mined meanwhile; every node now says the nonce is used, and the
        // receipt reads right after are rate limited.
        chain.on["eth_sendRawTransaction"] = { "\"error\":{\"code\":-32000,\"message\":\"nonce too low\"}" }
        var reads = 0
        chain.on["eth_getTransactionReceipt"] = {
            if (synchronized(chain) { reads++ } < 8) throw IOException("rate limited")
            "\"result\":{\"status\":\"0x1\",\"blockNumber\":\"0x10\",\"gasUsed\":\"0x5208\",\"effectiveGasPrice\":\"0x1\"}"
        }
        s.retry()
        s.awaitStage { it is SendStatus.Stage.Confirmed }
        assertEquals(1, chain.sent.toSet().size)
        // And the next send doesn't reuse nonce 7.
        chain.on.clear()
        assertEquals(BigInteger.valueOf(8), s.prepare(request()).tx.nonce)
    }

    @Test
    fun `on Try again of a replacement, a used-nonce refusal keeps the abandoned send's guard (#238)`() = runBlocking<Unit> {
        val chain = FakeChain()
        val s = sender(chain)
        s.submit(s.prepare(request()), signer())
        val first = s.awaitStage { it == SendStatus.Stage.Unconfirmed }
        s.discard()
        // Still in a pool: pending 8, mined 7. The replacement takes nonce 7.
        chain.nonce = 8
        chain.mined = 7
        val next = s.prepare(request(amount = 2))
        assertEquals(first.hash, next.replaces)
        chain.on["eth_sendRawTransaction"] = { throw IOException("timed out") }
        chain.on["eth_getTransactionReceipt"] = { throw IOException("timed out") }
        s.submit(next, signer())
        assertTrue(s.awaitStage { it is SendStatus.Stage.Failed }.mayHaveGone)
        // Try again: every leg (one lying RPC, the quorum defeated) says the nonce is used, and
        // the receipt, unreadable at first, then says the replacement mined.
        chain.on["eth_sendRawTransaction"] = { "\"error\":{\"code\":-32000,\"message\":\"nonce too low\"}" }
        var reads = 0
        chain.on["eth_getTransactionReceipt"] = {
            if (synchronized(chain) { reads++ } < 8) throw IOException("rate limited")
            "\"result\":{\"status\":\"0x1\",\"blockNumber\":\"0x10\",\"gasUsed\":\"0x5208\",\"effectiveGasPrice\":\"0x1\"}"
        }
        s.retry()
        val replacement = s.awaitStage { it is SendStatus.Stage.Confirmed }.hash!!
        s.acknowledge()
        // The mined count still says 7 is open: the guard stays, and the next send still takes
        // the abandoned one's place rather than going out beside it at 8.
        chain.on.clear()
        val after = s.prepare(request())
        assertEquals(BigInteger.valueOf(7), after.tx.nonce)
        assertEquals(first.hash, after.replaces)
        // Refused for that nonce, it names the replacement that went through, not the abandoned
        // send (#257 R2-F1).
        chain.on["eth_sendRawTransaction"] = { "\"error\":{\"code\":-32000,\"message\":\"nonce too low\"}" }
        s.submit(after, signer())
        val refused = s.awaitStage { it is SendStatus.Stage.Failed }.stage as SendStatus.Stage.Failed
        assertFalse(refused.mayHaveGone)
        assertTrue(refused.message, replacement in refused.message)
        assertFalse(refused.message, first.hash!! in refused.message)
        s.acknowledge()
        chain.on.clear()
        // Once the chain's count agrees it's mined, it goes.
        chain.mined = 8
        assertNull(s.prepare(request()).replaces)
    }

    @Test
    fun `markUsed moves the next nonce past a used one but keeps an abandoned send's guard (#238)`() = runBlocking<Unit> {
        val chain = FakeChain()
        val tracker = NonceTracker(chain.rpc())
        val hash = "0x" + "ab".repeat(32)
        tracker.abandon(from.address, 100, BigInteger.valueOf(7), EthTransaction.Fees.Legacy(BigInteger.TEN), hash)
        tracker.markUsed(from.address, 100, BigInteger.valueOf(7))
        assertEquals(hash, tracker.replacing(from.address, 100, BigInteger.valueOf(7))?.hash)
        // No abandoned send: one past the used nonce, as markSent.
        val other = NonceTracker(chain.rpc())
        other.markUsed(from.address, 100, BigInteger.valueOf(7))
        assertEquals(BigInteger.valueOf(8), other.next(from.address, 100).value)
    }

    @Test
    fun `noteMined names the replacement holding an abandoned send's nonce but keeps the guard (#257 R2-F1)`() {
        val chain = FakeChain()
        val tracker = NonceTracker(chain.rpc())
        val abandoned = "0x" + "ab".repeat(32)
        val replacement = "0x" + "cd".repeat(32)
        tracker.abandon(from.address, 100, BigInteger.valueOf(7), EthTransaction.Fees.Legacy(BigInteger.TEN), abandoned)
        // Another nonce, or the abandoned send's own hash: not a replacement holding it.
        tracker.noteMined(from.address, 100, BigInteger.valueOf(8), replacement)
        tracker.noteMined(from.address, 100, BigInteger.valueOf(7), abandoned.uppercase().replace("0X", "0x"))
        assertNull(tracker.replacing(from.address, 100, BigInteger.valueOf(7))?.heldBy)
        tracker.noteMined(from.address, 100, BigInteger.valueOf(7), replacement)
        val record = tracker.replacing(from.address, 100, BigInteger.valueOf(7))
        assertEquals(abandoned, record?.hash)
        assertEquals(replacement, record?.heldBy)
        // Abandoned afresh: a new record, holding nothing yet.
        tracker.abandon(from.address, 100, BigInteger.valueOf(7), EthTransaction.Fees.Legacy(BigInteger.TEN), abandoned)
        assertNull(tracker.replacing(from.address, 100, BigInteger.valueOf(7))?.heldBy)
    }

    @Test
    fun `on Try again a used nonce with no receipt ends waiting, not as not sent`() = runBlocking<Unit> {
        val chain = FakeChain()
        val s = sender(chain)
        chain.on["eth_sendRawTransaction"] = { throw IOException("timed out") }
        chain.on["eth_getTransactionReceipt"] = { throw IOException("timed out") }
        s.submit(s.prepare(request()), signer())
        s.awaitStage { it is SendStatus.Stage.Failed }
        chain.on["eth_sendRawTransaction"] = { "\"error\":{\"code\":-32000,\"message\":\"nonce too low\"}" }
        chain.on.remove("eth_getTransactionReceipt")
        s.retry()
        val end = s.awaitStage { it == SendStatus.Stage.Unconfirmed || it is SendStatus.Stage.Failed }
        assertEquals(SendStatus.Stage.Unconfirmed, end.stage)
    }

    @Test
    fun `on Try again insufficient funds may be this very transaction having spent the balance, so it's followed`() = runBlocking<Unit> {
        val chain = FakeChain()
        val s = sender(chain)
        chain.on["eth_sendRawTransaction"] = { req ->
            synchronized(chain.sent) { chain.sent += req.getJSONArray("params").getString(0) }
            throw IOException("timed out")
        }
        chain.on["eth_getTransactionReceipt"] = { throw IOException("timed out") }
        s.submit(s.prepare(request()), signer())
        s.awaitStage { it is SendStatus.Stage.Failed }
        // It mined, spending the balance; Nethermind checks the balance
        // before the nonce, and the first receipt reads are rate limited.
        chain.on["eth_sendRawTransaction"] = {
            "\"error\":{\"code\":-32010,\"message\":\"insufficient funds for gas * price + value, balance 1, cost 2\"}"
        }
        var reads = 0
        chain.on["eth_getTransactionReceipt"] = {
            if (synchronized(chain) { reads++ } < 8) throw IOException("rate limited")
            "\"result\":{\"status\":\"0x1\",\"blockNumber\":\"0x10\",\"gasUsed\":\"0x5208\",\"effectiveGasPrice\":\"0x1\"}"
        }
        s.retry()
        s.awaitStage { it is SendStatus.Stage.Confirmed }
        assertEquals(1, chain.sent.toSet().size)
    }

    @Test
    fun `on Try again no refusal ends as not sent`() = runBlocking<Unit> {
        for (refusal in listOf("insufficient funds for gas * price + value", "max fee per gas less than block base fee", "nonce too high")) {
            val chain = FakeChain()
            val s = sender(chain)
            chain.on["eth_sendRawTransaction"] = { throw IOException("timed out") }
            chain.on["eth_getTransactionReceipt"] = { throw IOException("timed out") }
            s.submit(s.prepare(request()), signer())
            s.awaitStage { it is SendStatus.Stage.Failed }
            chain.on["eth_sendRawTransaction"] = { "\"error\":{\"code\":-32000,\"message\":\"$refusal\"}" }
            chain.on.remove("eth_getTransactionReceipt")
            s.retry()
            val end = s.awaitStage { it == SendStatus.Stage.Unconfirmed || it is SendStatus.Stage.Failed }
            assertEquals(refusal, SendStatus.Stage.Unconfirmed, end.stage)
        }
    }

    @Test
    fun `a locked wallet fails before anything is sent, and one send runs at a time`() = runBlocking<Unit> {
        val chain = FakeChain()
        val s = sender(chain)
        val quote = s.prepare(request())
        s.submit(quote) { throw VaultLockedException() }
        val failed = s.awaitStage { it is SendStatus.Stage.Failed }.stage as SendStatus.Stage.Failed
        assertFalse(failed.mayHaveGone)
        assertTrue(chain.sent.isEmpty())
        // Try again is only for a send that may have gone out.
        s.retry()
        assertTrue(s.status.value!!.stage is SendStatus.Stage.Failed)

        val gate = java.util.concurrent.CountDownLatch(1)
        assertEquals(WalletSender.Submit.STARTED, s.submit(quote) { tx -> gate.await(); tx.sign(key.copyOf(), from.address) })
        assertEquals(WalletSender.Submit.BUSY, s.submit(quote, signer()))
        // Acknowledging a send that's still being signed leaves it.
        s.acknowledge()
        assertNotNull(s.status.value)
        gate.countDown()
        s.awaitStage { it == SendStatus.Stage.Pending }
        // Nor one still waiting for its receipt; a mined one is done with.
        s.acknowledge()
        assertNotNull(s.status.value)
        chain.receipt = """{"status":"0x1","blockNumber":"0x10","gasUsed":"0x5208","effectiveGasPrice":"0x1"}"""
        s.awaitStage { it is SendStatus.Stage.Confirmed }
        s.acknowledge()
        assertNull(s.status.value)
    }

    @Test
    fun `broadcast failures read as what happened`() {
        val quote = SendQuote(
            request(), EthTransaction(100, BigInteger.valueOf(4), BigInteger.valueOf(21_000), to, BigInteger.ONE, ByteArray(0), EthTransaction.Fees.Legacy(BigInteger.ONE)),
            BigInteger.TEN, null, 0, ChainTrustsForTest.unverified,
        )
        fun rpc(message: String) = ChainRpcException.Rpc(-32000, message, null)
        fun check(e: ChainRpcException, start: String, uncertain: Boolean) {
            val (m, u) = WalletSender.broadcastFailure(e, quote)
            assertTrue(m, m.startsWith(start))
            assertEquals(m, uncertain, u)
        }
        check(ChainRpcException.AllSourcesFailed(listOf("direct a.example: timed out"), null), "No RPC confirmed", true)
        check(rpc("insufficient funds for gas * price + value"), "Not sent: not enough xDAI", false)
        check(ChainRpcException.AllSourcesFailed(emptyList(), rpc("replacement transaction underpriced")), "Not sent: another transaction with nonce 4", false)
        check(rpc("transaction underpriced"), "Not sent: its fee is below", false)
        check(rpc("max fee per gas less than block base fee"), "Not sent: its fee is below", false)
        check(rpc("nonce too high"), "Not sent: nonce 4 is ahead of what the network expects", false)
        check(rpc("nonce too low"), "Not sent: nonce 4 was already used", false)
        check(rpc("OldNonce, Current: 5, tx: 4"), "Not sent: nonce 4 was already used", false)
        // Replacing a send given up on: a used nonce is most likely that one having gone through.
        val (m, u) = WalletSender.broadcastFailure(rpc("nonce too low"), quote.copy(replaces = "0xab"))
        assertTrue(m, m.contains("the send you stopped tracking went through (0xab)"))
        assertFalse(u)
        // Its own replacement found mined on that nonce: that's what's named (#257 R2-F1).
        val (held, heldUncertain) = WalletSender.broadcastFailure(rpc("nonce too low"), quote.copy(replaces = "0xab"), heldBy = "0xcd")
        assertTrue(held, held.contains("which went through (0xcd)"))
        assertFalse(held, "0xab" in held)
        assertFalse(heldUncertain)
        // An error this can't read proves nothing: a rate limit, a client's own "already have it" wording.
        check(rpc("something odd‮"), "The RPC answered with an error (something odd), so it may or may not", true)
        check(ChainRpcException.AllSourcesFailed(emptyList(), ChainRpcException.Rpc(-32005, "rate limited", null)), "The RPC answered with an error (rate limited)", true)
        // One source never answered: whatever the rest said, it may have it.
        for (refusal in listOf(rpc("rate limited"), rpc("nonce too low"), rpc("insufficient funds for gas * price + value"))) {
            check(ChainRpcException.AllSourcesFailed(listOf("direct a.example: timed out"), refusal, unanswered = true), "One RPC didn’t answer and may have taken it", true)
        }
    }

    @Test
    fun `a node that timed out then others refusing is may-have-gone, and Try again resends the same bytes`() = runBlocking<Unit> {
        val chain = FakeChain()
        val s = sender(chain)
        val quote = s.prepare(request())
        // a.example takes it but its answer never comes; b and c are rate limited.
        var calls = 0
        chain.on["eth_sendRawTransaction"] = { req ->
            synchronized(chain.sent) { chain.sent += req.getJSONArray("params").getString(0) }
            if (synchronized(chain) { calls++ } == 0) throw IOException("timed out")
            "\"error\":{\"code\":-32005,\"message\":\"rate limit exceeded\"}"
        }
        s.submit(quote, signer())
        val failed = s.awaitStage { it is SendStatus.Stage.Failed }.stage as SendStatus.Stage.Failed
        assertTrue(failed.message, failed.mayHaveGone)
        chain.on.remove("eth_sendRawTransaction")
        s.retry()
        s.awaitStage { it == SendStatus.Stage.Pending }
        assertEquals(1, chain.sent.toSet().size)
    }

    @Test
    fun `a quote that went stale while the unlock prompt stood is not signed`() = runBlocking<Unit> {
        val chain = FakeChain()
        val now = AtomicLong(1_000L)
        val s = sender(chain) { now.get() }
        val quote = s.prepare(request())
        now.addAndGet(WalletSender.QUOTE_TTL_MS + 1)
        var signed = false
        assertEquals(WalletSender.Submit.STALE, s.submit(quote) { tx -> signed = true; tx.sign(key.copyOf(), from.address) })
        assertFalse(signed)
        assertNull(s.status.value)
        assertTrue(chain.sent.isEmpty())
    }

    @Test
    fun `a quote that went stale while a Ledger signed it is dropped, not broadcast`() = runBlocking<Unit> {
        val chain = FakeChain()
        val now = AtomicLong(1_000L)
        val s = sender(chain) { now.get() }
        val quote = s.prepare(request())
        now.addAndGet(WalletSender.QUOTE_TTL_MS - 1)
        // Fresh when confirmed; the unlock and the review on the device took minutes.
        assertEquals(
            WalletSender.Submit.STARTED,
            s.submit(quote) { tx ->
                now.addAndGet(150_000)
                tx.sign(key.copyOf(), from.address)
            },
        )
        val failed = s.awaitStage { it is SendStatus.Stage.Failed }.stage as SendStatus.Stage.Failed
        assertTrue(failed.stale)
        assertFalse(failed.mayHaveGone)
        assertEquals(WalletSender.STALE_WHILE_SIGNING, failed.message)
        assertTrue(failed.droppedSigned)
        assertTrue(chain.sent.isEmpty())
        // Nothing holds the next send back: priced again, it goes.
        now.addAndGet(1)
        assertEquals(WalletSender.Submit.STARTED, s.submit(s.prepare(request()), signer()))
        s.awaitStage { it == SendStatus.Stage.Pending }
        assertEquals(1, chain.sent.size)
    }

    @Test
    fun `a send that ends without going out is over by the time its failure shows`() = runBlocking<Unit> {
        // The page may price the next send the moment it sees the failure. A quote priced
        // then must not read the failed send as one still taking its nonce (STALE forever
        // after): the send is settled before its failure is shown, never just after.
        val chain = FakeChain()
        val now = AtomicLong(1_000L)
        val s = sender(chain) { now.get() }
        /** Submits [quote] with [sign], which fails it; the quote priced the moment that failure shows. */
        suspend fun pricedAsItFails(quote: SendQuote, sign: suspend (EthTransaction) -> EthTransaction.Signed): SendQuote {
            val atFailure = kotlinx.coroutines.CompletableDeferred<SendQuote>()
            // Unconfined: runs inside the sender's show(), as the failure appears, before anything after it.
            val watcher = launch(Dispatchers.Unconfined) {
                s.changes.first { it?.stage is SendStatus.Stage.Failed }
                atFailure.complete(runBlocking { s.prepare(request()) })
            }
            assertEquals(WalletSender.Submit.STARTED, s.submit(quote, sign))
            return withTimeout(5_000) { atFailure.await() }.also { watcher.cancel() }
        }
        // Refused on the Ledger; the quote priced as that shows is taken, and approved
        // on the Ledger too late to send; the one priced as that shows goes out.
        val second = pricedAsItFails(s.prepare(request())) { throw LedgerException(LedgerException.Kind.REJECTED) }
        s.acknowledge()
        val third = pricedAsItFails(second) { tx ->
            now.addAndGet(WalletSender.SIGNED_TTL_MS)
            tx.sign(key.copyOf(), from.address)
        }
        s.acknowledge()
        assertEquals(WalletSender.Submit.STARTED, s.submit(third, signer()))
        s.awaitStage { it == SendStatus.Stage.Pending }
        assertEquals(1, chain.sent.size)
    }

    @Test
    fun `a quote priced beside a send that went out since is priced again, not signed on the same nonce`() = runBlocking<Unit> {
        val chain = FakeChain()
        val s = sender(chain)
        // A site's sheet is up with one quote while another (a covered call) is priced beside it.
        val waiting = s.prepare(request())
        val covered = s.prepare(request(amount = 2))
        assertEquals(waiting.tx.nonce, covered.tx.nonce)
        assertEquals(WalletSender.Submit.STARTED, s.submit(covered, signer()))
        s.awaitStage { it == SendStatus.Stage.Pending }
        chain.receipt = """{"status":"0x1","blockNumber":"0x10","gasUsed":"0x5208","effectiveGasPrice":"0x1"}"""
        s.awaitStage { it is SendStatus.Stage.Confirmed }
        // Still within the quote's minute, and nothing is busy: but its nonce is taken.
        var signed = false
        assertEquals(WalletSender.Submit.STALE, s.submit(waiting) { tx -> signed = true; tx.sign(key.copyOf(), from.address) })
        assertFalse(signed)
        assertEquals(1, chain.sent.size)
        s.acknowledge()
        chain.receipt = "null"
        chain.nonce = 8
        val again = s.prepare(request())
        assertEquals(BigInteger.valueOf(8), again.tx.nonce)
        assertEquals(WalletSender.Submit.STARTED, s.submit(again, signer()))
        s.awaitStage { it == SendStatus.Stage.Pending }
    }

    @Test
    fun `a quote priced while another send was still being signed is priced again, not signed on its nonce`() = runBlocking<Unit> {
        val chain = FakeChain()
        val s = sender(chain)
        // A covered call starts first; the sheet's quote is priced while it
        // is still signing (counted as started, its nonce not yet marked sent).
        val covered = s.prepare(request(amount = 2))
        val gate = java.util.concurrent.CountDownLatch(1)
        val signing = java.util.concurrent.CountDownLatch(1)
        assertEquals(
            WalletSender.Submit.STARTED,
            s.submit(covered) { tx -> signing.countDown(); gate.await(); tx.sign(key.copyOf(), from.address) },
        )
        signing.await()
        val waiting = s.prepare(request())
        assertEquals(covered.tx.nonce, waiting.tx.nonce)
        // It goes out and is mined (nothing busy any more); the sheet is confirmed within its minute.
        chain.receipt = """{"status":"0x1","blockNumber":"0x10","gasUsed":"0x5208","effectiveGasPrice":"0x1"}"""
        gate.countDown()
        s.awaitStage { it is SendStatus.Stage.Confirmed }
        var signed = false
        assertEquals(WalletSender.Submit.STALE, s.submit(waiting) { tx -> signed = true; tx.sign(key.copyOf(), from.address) })
        assertFalse(signed)
        assertEquals(1, chain.sent.size)
        // Priced again, it takes the next nonce and goes out.
        s.acknowledge()
        chain.receipt = "null"
        val again = s.prepare(request())
        assertEquals(covered.tx.nonce + BigInteger.ONE, again.tx.nonce)
        assertEquals(WalletSender.Submit.STARTED, s.submit(again, signer()))
        s.awaitStage { it == SendStatus.Stage.Pending }
        assertEquals(2, chain.sent.size)
    }

    @Test
    fun `a quote whose own send failed before going out can be confirmed again`() = runBlocking<Unit> {
        val chain = FakeChain()
        val s = sender(chain)
        val quote = s.prepare(request())
        assertEquals(WalletSender.Submit.STARTED, s.submit(quote) { throw VaultLockedException() })
        s.awaitStage { it is SendStatus.Stage.Failed }
        assertEquals(WalletSender.Submit.STARTED, s.submit(quote, signer()))
        s.awaitStage { it == SendStatus.Stage.Pending }
        assertEquals(1, chain.sent.size)
    }

    @Test
    fun `a transaction approved on the Ledger within the review allowance is sent, not dropped`() = runBlocking<Unit> {
        val chain = FakeChain()
        val now = AtomicLong(1_000L)
        val s = sender(chain) { now.get() }
        val quote = s.prepare(request())
        // Confirmed after 35 s (the pre-sign check passes); approved on the device 30 s later.
        now.addAndGet(35_000)
        assertEquals(
            WalletSender.Submit.STARTED,
            s.submit(quote) { tx ->
                now.addAndGet(30_000)
                tx.sign(key.copyOf(), from.address)
            },
        )
        s.awaitStage { it == SendStatus.Stage.Pending }
        assertEquals(1, chain.sent.size)
    }

    @Test
    fun `a transaction refused or cancelled on the Ledger fails as a rejection`() = runBlocking<Unit> {
        for (kind in LedgerException.Kind.entries) {
            val chain = FakeChain()
            val s = sender(chain) { 1_000L }
            assertEquals(WalletSender.Submit.STARTED, s.submit(s.prepare(request())) { throw LedgerException(kind) })
            val failed = s.awaitStage { it is SendStatus.Stage.Failed }.stage as SendStatus.Stage.Failed
            val rejection = kind == LedgerException.Kind.REJECTED || kind == LedgerException.Kind.CANCELLED
            assertEquals(kind.name, rejection, failed.rejected)
            assertFalse(failed.mayHaveGone)
            assertFalse(failed.stale)
            assertTrue(chain.sent.isEmpty())
        }
    }

    @Test
    fun `a signer that finds its quote stale before signing ends the send the same way`() = runBlocking<Unit> {
        val chain = FakeChain()
        val s = sender(chain) { 1_000L }
        assertEquals(WalletSender.Submit.STARTED, s.submit(s.prepare(request())) { throw QuoteStaleException() })
        val failed = s.awaitStage { it is SendStatus.Stage.Failed }.stage as SendStatus.Stage.Failed
        assertTrue(failed.stale)
        // Found stale before the Ledger showed it: nothing was signed, and the message says a minute, not three.
        assertFalse(failed.droppedSigned)
        assertEquals(WalletSender.STALE_BEFORE_SIGNING, failed.message)
        assertTrue(chain.sent.isEmpty())
    }

    @Test
    fun `a signer held for the page's own reason ends the send with that reason, nothing signed`() = runBlocking<Unit> {
        val chain = FakeChain()
        val s = sender(chain) { 1_000L }
        assertEquals(WalletSender.Submit.STARTED, s.submit(s.prepare(request())) { throw SigningHeldException("The node changed.") })
        val failed = s.awaitStage { it is SendStatus.Stage.Failed }.stage as SendStatus.Stage.Failed
        assertEquals("The node changed. Nothing was signed or sent.", failed.message)
        assertFalse(failed.mayHaveGone)
        assertFalse(failed.stale)
        assertTrue(chain.sent.isEmpty())
    }

    @Test
    fun `what the pages show`() {
        assertEquals("0.00002101 xDAI", feeText(BigInteger.valueOf(21_000) * (gwei + BigInteger.valueOf(28)), gnosis))
        assertEquals("0 xDAI", feeText(BigInteger.ZERO, gnosis))
        val tx = EthTransaction(100, BigInteger.ONE, BigInteger.valueOf(21_000), to, BigInteger.ONE, ByteArray(0), EthTransaction.Fees.Eip1559(gwei + BigInteger.valueOf(28), gwei))
        assertEquals("Gas limit 21,000 · up to 1.000000028 gwei per gas, tip 1 gwei", feeDetail(tx))
        assertEquals("https://gnosisscan.io/tx/0xab", explorerTxUrl(gnosis, "0xab"))
        val quote = SendQuote(request(), tx, BigInteger.TEN, null, 0, ChainTrustsForTest.unverified)
        assertEquals("Sent", sendStatusText(SendStatus(quote, SendStatus.Stage.Confirmed(48_496_752, null), "0xab")).first)
        assertTrue(sendStatusText(SendStatus(quote, SendStatus.Stage.Reverted(1, null), "0xab")).second.contains("nothing arrived"))
        assertEquals("Not confirmed", sendStatusText(SendStatus(quote, SendStatus.Stage.Failed("x", true))).first)
        assertEquals("Not sent", sendStatusText(SendStatus(quote, SendStatus.Stage.Failed("x", false))).first)
        assertTrue(stopTrackingText(SendStatus(quote, SendStatus.Stage.Unconfirmed, "0xab")).contains("reuses its nonce (1)"))
    }

    @Test
    fun `revert reasons decode Error(string) and nothing else`() {
        assertNull(WalletSender.revertReason("0x"))
        assertNull(WalletSender.revertReason("0x4e487b71" + "11".padStart(64, '0')))
        val data = "0x08c379a0" + "20".padStart(64, '0') + "2".padStart(64, '0') + "6869".padEnd(64, '0')
        assertEquals("hi", WalletSender.revertReason(data))
        // A length or offset past the data is refused before anything is allocated for it.
        assertNull(WalletSender.revertReason("0x08c379a0" + "20".padStart(64, '0') + "7fffffff".padStart(64, '0') + "6869".padEnd(64, '0')))
        assertNull(WalletSender.revertReason("0x08c379a0" + "f".repeat(64) + "2".padStart(64, '0') + "6869".padEnd(64, '0')))
        assertNull(WalletSender.revertReason("0x08c379a0" + "20".padStart(64, '0') + "21".padStart(64, '0') + "6869".padEnd(64, '0')))
    }

    private suspend fun assertMessage(start: String, block: suspend () -> Unit) {
        try {
            block()
            fail("expected: $start")
        } catch (e: SendException) {
            assertTrue(e.message, e.message!!.startsWith(start))
        }
    }
}

internal object ChainTrustsForTest {
    val unverified = baby.freedom.mobile.chains.rpc.ChainTrust(
        baby.freedom.mobile.chains.rpc.ChainTrust.Level.UNVERIFIED,
        baby.freedom.mobile.chains.rpc.ChainSource.DIRECT,
        listOf("a.example"), emptyList(), listOf("a.example"), 1, 1, null,
    )
}

/** A receipt answer names its own transaction, as a node's does (#229): the fakes' receipts leave it out. */
internal fun withReceiptHash(req: JSONObject, reply: String): String {
    if (req.getString("method") != "eth_getTransactionReceipt") return reply
    val o = JSONObject(reply)
    val r = o.opt("result") as? JSONObject ?: return reply
    if (!r.has("transactionHash")) r.put("transactionHash", req.getJSONArray("params").getString(0))
    return o.toString()
}
