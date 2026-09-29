package baby.freedom.mobile.wallet

import baby.freedom.mobile.browser.explorerTxUrl
import baby.freedom.mobile.browser.feeDetail
import baby.freedom.mobile.browser.feeText
import baby.freedom.mobile.browser.sendStatusText
import baby.freedom.mobile.chains.BuiltInChains
import baby.freedom.mobile.chains.Chain
import baby.freedom.mobile.chains.rpc.ChainDataRouter
import baby.freedom.mobile.chains.rpc.ChainRpcException
import baby.freedom.mobile.chains.rpc.RpcTransport
import baby.freedom.mobile.chains.rpc.WalletRpc
import baby.freedom.mobile.ens.hexToBytes
import java.io.IOException
import java.math.BigInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    @After
    fun tearDown() = scope.cancel()

    /** A fake chain every RPC agrees on; [on] can override any method's answer (a JSON-RPC member). */
    private inner class FakeChain {
        var balance = BigInteger.TEN.pow(18)
        var tokenBalance = BigInteger.valueOf(5_000)
        var nonce = 7L
        var baseFee: BigInteger? = BigInteger.valueOf(14)
        var tip = BigInteger.ONE
        var estimate = 21_000L
        var receipt: String = "null"
        var down = false
        val sent = mutableListOf<String>()
        val methods = mutableListOf<String>()
        val on = HashMap<String, (JSONObject) -> String>()

        fun answer(req: JSONObject): String {
            val method = req.getString("method")
            synchronized(methods) { methods += method }
            on[method]?.let { return it(req) }
            fun q(v: BigInteger) = "\"result\":\"0x${v.toString(16)}\""
            return when (method) {
                "eth_getBalance" -> q(balance)
                "eth_call" -> "\"result\":\"0x" + tokenBalance.toString(16).padStart(64, '0') + "\""
                "eth_getTransactionCount" -> q(BigInteger.valueOf(nonce))
                "eth_getBlockByNumber" -> "\"result\":" + JSONObject().apply {
                    put("number", "0x10")
                    baseFee?.let { put("baseFeePerGas", "0x" + it.toString(16)) }
                }
                "eth_maxPriorityFeePerGas" -> q(tip)
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
                transport = RpcTransport { _, body, _ ->
                    if (down) throw IOException("down")
                    """{"jsonrpc":"2.0","id":1,${answer(JSONObject(body))}}"""
                },
            ),
        )
    }

    private fun sender(chain: FakeChain, clock: () -> Long = System::currentTimeMillis) =
        WalletSender(chain.rpc(), scope, clock, pollMs = 10, confirmTimeoutMs = 300)

    private fun signer(): (EthTransaction) -> EthTransaction.Signed = { tx -> tx.sign(key.copyOf(), from.address) }

    private fun request(token: Token = xdai, amount: Long = 1) = SendRequest(gnosis, token, from, to, BigInteger.valueOf(amount))

    private suspend fun WalletSender.awaitStage(predicate: (SendStatus.Stage) -> Boolean): SendStatus =
        withTimeout(5_000) { status.first { it != null && predicate(it.stage) }!! }

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

    // ---- fees and gas ----

    @Test
    fun `EIP-1559 fees are twice the base fee plus a tip of at least 1 gwei`() {
        val low = GasOracle.eip1559(BigInteger.valueOf(14), BigInteger.ONE)
        assertEquals(gwei, low.maxPriorityFeePerGas)
        assertEquals(gwei + BigInteger.valueOf(28), low.maxFeePerGas)
        val high = GasOracle.eip1559(BigInteger.valueOf(20) * gwei, BigInteger.valueOf(2) * gwei)
        assertEquals(BigInteger.valueOf(2) * gwei, high.maxPriorityFeePerGas)
        assertEquals(BigInteger.valueOf(42) * gwei, high.maxFeePerGas)
        assertEquals(gwei, GasOracle.eip1559(BigInteger.TEN, null).maxPriorityFeePerGas)
        try {
            GasOracle.legacy(BigInteger.ZERO)
            fail("a zero gas price must be refused")
        } catch (_: SendException) {
        }
    }

    @Test
    fun `a plain transfer keeps 21000 gas, anything with code gets 20 percent headroom`() {
        assertEquals(BigInteger.valueOf(21_000), WalletSender.gasLimit(BigInteger.valueOf(21_000), hasData = false))
        assertEquals(BigInteger.valueOf(62_400), WalletSender.gasLimit(BigInteger.valueOf(52_000), hasData = true))
        assertEquals(BigInteger.valueOf(28_800), WalletSender.gasLimit(BigInteger.valueOf(24_000), hasData = false))
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
        var now = 1_000_000L
        val s = sender(FakeChain()) { now }
        val quote = s.prepare(request())
        assertFalse(s.isStale(quote))
        now += WalletSender.QUOTE_TTL_MS
        assertTrue(s.isStale(quote))
        // A clock set back since: not trusted either.
        now = 0
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
        var now = 1_000L
        val s = sender(chain) { now }
        val quote = s.prepare(request())
        now += WalletSender.QUOTE_TTL_MS + 1
        var signed = false
        assertEquals(WalletSender.Submit.STALE, s.submit(quote) { tx -> signed = true; tx.sign(key.copyOf(), from.address) })
        assertFalse(signed)
        assertNull(s.status.value)
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
