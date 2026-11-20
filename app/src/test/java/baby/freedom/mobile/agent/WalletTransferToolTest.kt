package baby.freedom.mobile.agent

import baby.freedom.mobile.chains.BuiltInChains
import baby.freedom.mobile.chains.Chain
import baby.freedom.mobile.ens.EnsAddressResult
import baby.freedom.mobile.ens.EnsTrust
import baby.freedom.mobile.l10n.Said
import baby.freedom.mobile.wallet.ChainTrustsForTest
import baby.freedom.mobile.wallet.EthTransaction
import baby.freedom.mobile.wallet.SendException
import baby.freedom.mobile.wallet.SendQuote
import baby.freedom.mobile.wallet.SendRequest
import baby.freedom.mobile.wallet.Token
import baby.freedom.mobile.wallet.WalletAccount
import baby.freedom.mobile.wallet.WalletAccountList
import baby.freedom.mobile.wallet.WalletSender
import java.math.BigInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletTransferToolTest {
    private val me = WalletAccount(0, "Account 1", "0x9858EfFD232B4033E47d90003D41EC34EcaEda94")
    private val second = WalletAccount(1, "Account 2", "0x6Fac4D18c912343BF86fa7049364Dd4E424Ab9C0")
    private val vitalik = "0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045"
    private val other = "0x1111111111111111111111111111111111111111"

    /** The wallet as the tool sees it; [sends] counts every quote handed to the signer. */
    private inner class FakeWallet : TransferWallet {
        var wallet = true
        var chainList: List<Chain>? = listOf(BuiltInChains.ETHEREUM, BuiltInChains.GNOSIS)
        var busy = false
        var shortOf: String? = null
        var maxPerGas: BigInteger = BigInteger.valueOf(2_000_000_000)
        var names: (String, Boolean) -> EnsAddressResult = { n, _ -> EnsAddressResult.NoAddress(n, "NO_RESOLVER", trust = null) }
        val lookups = mutableListOf<Pair<String, Boolean>>()
        var stale: (SendQuote) -> Boolean = { false }
        var outcome: (SendQuote) -> WalletSender.Broadcast = { WalletSender.Broadcast.Sent("0x" + "ab".repeat(32)) }
        val prepared = mutableListOf<SendRequest>()
        val sends = mutableListOf<SendQuote>()

        override fun hasWallet() = wallet
        override suspend fun accounts() = WalletAccountList(listOf(me, second), 0)
        override suspend fun chains() = chainList
        override suspend fun resolveAddress(name: String, chainId: Long, fresh: Boolean): EnsAddressResult {
            lookups += name to fresh
            return names(name, fresh)
        }
        override fun busy() = busy
        override suspend fun prepare(request: SendRequest): SendQuote {
            prepared += request
            shortOf?.let { throw SendException(Said("Not enough $it", "Not enough $it for this transfer and its fee."), shortOf = it) }
            val (to, value, data) = request.call()
            val tx = EthTransaction(
                request.chain.id, BigInteger.valueOf(prepared.size.toLong()), BigInteger.valueOf(21_000), to, value, data,
                EthTransaction.Fees.Legacy(maxPerGas),
            )
            return SendQuote(request, tx, BigInteger.TEN.pow(20), null, 0, ChainTrustsForTest.unverified)
        }
        override fun isStale(quote: SendQuote) = stale(quote)
        override suspend fun send(quote: SendQuote): WalletSender.Broadcast {
            sends += quote
            return outcome(quote)
        }
    }

    private val wallet = FakeWallet()
    private val asked = mutableListOf<TransferAsk>()
    private var answers: (TransferAsk) -> TransferAnswer = { TransferAnswer.Approved(acceptedUnverified = false) }
    private var ids = 0L

    private fun tool(w: TransferWallet? = wallet) = WalletTransferTool(
        sessionId = "s",
        wallet = { w },
        ask = { a -> asked += a; answers(a) },
        nextId = { ++ids },
    )

    private fun run(args: String, t: WalletTransferTool = tool()) = runBlocking { t.run(JSONObject(args)) }

    private fun code(outcome: ToolOutcome) = Regex("^\\[([A-Z_]+)]").find(outcome.output)?.groupValues?.get(1)

    @After
    fun clear() {
        AgentTransfers.withdrawAll()
    }

    @Test
    fun `desktop's name and schema, and a chat offers it`() {
        val spec = tool().spec
        assertEquals("wallet_transfer", spec.name)
        val params = spec.parameters
        assertEquals(listOf("recipient", "amount", "asset"), (0 until params.getJSONArray("required").length()).map { params.getJSONArray("required").getString(it) })
        assertEquals(setOf("recipient", "amount", "asset", "chainId", "walletIndex"), params.getJSONObject("properties").keys().asSequence().toSet())
        assertFalse(params.getBoolean("additionalProperties"))
        assertTrue(ToolRegistry.forChat("s") { error("not needed to list the tools") }.specs.any { it.name == "wallet_transfer" })
    }

    @Test
    fun `arguments are checked as desktop's operations do, before anything else`() {
        val bad = listOf(
            """{"amount":"1","asset":"ETH"}""",
            """{"recipient":"  ","amount":"1","asset":"ETH"}""",
            """{"recipient":"$vitalik","amount":1,"asset":"ETH"}""",
            """{"recipient":"${"a".repeat(256)}","amount":"1","asset":"ETH"}""",
            """{"recipient":"$vitalik","amount":"${"1".repeat(81)}","asset":"ETH"}""",
            """{"recipient":"$vitalik","amount":"1","asset":"${"E".repeat(81)}"}""",
            """{"recipient":"$vitalik","amount":"1","asset":"ETH","chainId":0}""",
            """{"recipient":"$vitalik","amount":"1","asset":"ETH","chainId":"1"}""",
            """{"recipient":"$vitalik","amount":"1","asset":"ETH","chainId":1.5}""",
            """{"recipient":"$vitalik","amount":"1","asset":"ETH","walletIndex":-1}""",
            """{"recipient":"$vitalik","amount":"1","asset":"ETH","gas":"0x1"}""",
        )
        for (args in bad) {
            val out = run(args)
            assertEquals(args, "INVALID_ARGUMENT", code(out))
            assertTrue(out.isError)
        }
        assertTrue(wallet.prepared.isEmpty())
        assertTrue(asked.isEmpty())
        assertTrue(wallet.sends.isEmpty())
        assertEquals(TransferArgs(vitalik, "1", "ETH", 1, 0), TransferArgs.parse(JSONObject("""{"recipient":" $vitalik ","amount":"1","asset":"ETH","chainId":1,"walletIndex":0}""")))
    }

    @Test
    fun `an asset is a wallet asset by symbol or contract, and one on two networks asks for the chain`() {
        val assets = TransferAssets.of(listOf(BuiltInChains.ETHEREUM, BuiltInChains.GNOSIS))
        assertEquals("BZZ", TransferAssets.pick("bzz", null, assets).second.symbol)
        assertEquals("USDC", TransferAssets.pick("0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48", null, assets).second.symbol)
        assertEquals(100L, TransferAssets.pick("xDAI", 100, assets).first.id)
        // The same symbol on two networks: never guessed.
        val twice = assets + (BuiltInChains.GNOSIS to Token(100, "0x2a22f9c3b484c3629090FeED35F17Ff8F88f76F0", "USDC", "USD Coin", 6))
        val e = runCatching { TransferAssets.pick("USDC", null, twice) }.exceptionOrNull() as ToolError
        assertEquals(ToolError.INVALID_ARGUMENT, e.code)
        assertTrue(e.message!!, e.message!!.contains("chainId 1") && e.message!!.contains("chainId 100"))
        assertEquals(1L, TransferAssets.pick("USDC", 1, twice).first.id)
        // Not on that chain, not a wallet asset, not a wallet chain.
        for ((asset, chain) in listOf("USDC" to 100L, "PEPE" to null, "ETH" to 10L, "0x0000000000000000000000000000000000000001" to null)) {
            assertEquals(ToolError.INVALID_ARGUMENT, (runCatching { TransferAssets.pick(asset, chain, assets) }.exceptionOrNull() as ToolError).code)
        }
        assertEquals("INVALID_ARGUMENT", code(run("""{"recipient":"$vitalik","amount":"1","asset":"PEPE"}""")))
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `amounts are plain decimals with a point, positive, within the token's decimals`() {
        val usdc = Token(1, "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48", "USDC", "USD Coin", 6)
        assertEquals(BigInteger.valueOf(1_500_000), transferAmount("1.5", usdc))
        assertEquals(BigInteger.valueOf(500_000), transferAmount(".5", usdc))
        for (amount in listOf("1,000", "1,5", "0", "0.000", "-1", "1e3", "0x10", "0.0000001", "1 000", "1.2.3")) {
            val e = runCatching { transferAmount(amount, usdc) }.exceptionOrNull()
            assertTrue(amount, e is ToolError && e.code == ToolError.INVALID_ARGUMENT)
        }
        assertEquals("INVALID_ARGUMENT", code(run("""{"recipient":"$vitalik","amount":"1,000","asset":"USDC"}""")))
        assertTrue(wallet.prepared.isEmpty())
    }

    @Test
    fun `a recipient that can't be paid is refused before any sheet`() {
        val refused = listOf(
            "0x0000000000000000000000000000000000000000",
            // A checksum typo (one letter's case flipped).
            "0xd8DA6BF26964aF9D7eEd9e03E53415D37aA96045",
            // USDC's own contract, for USDC.
            "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48",
            "alice.tez",
            "not an address",
        )
        for (to in refused) {
            assertEquals(to, "INVALID_ARGUMENT", code(run("""{"recipient":"$to","amount":"1","asset":"USDC"}""")))
        }
        // A name with nothing to send to; a name whose servers disagree (retryable).
        assertEquals("INVALID_ARGUMENT", code(run("""{"recipient":"nobody.eth","amount":"1","asset":"ETH"}""")))
        wallet.names = { n, _ -> EnsAddressResult.Error(n, "RPC_ERROR", "timeout", retryable = true) }
        assertEquals("CAPABILITY_UNAVAILABLE", code(run("""{"recipient":"slow.eth","amount":"1","asset":"ETH"}""")))
        // A name pointing at the token's own contract.
        wallet.names = { n, _ -> EnsAddressResult.Ok(n, "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48", EnsTrust(verified = true)) }
        assertEquals("INVALID_ARGUMENT", code(run("""{"recipient":"usdc.eth","amount":"1","asset":"USDC"}""")))
        assertTrue(wallet.prepared.isEmpty())
        assertTrue(asked.isEmpty())
        assertTrue(wallet.sends.isEmpty())
    }

    @Test
    fun `no browser, no wallet, a busy wallet, too little balance or no fee are refused before any sheet`() {
        val args = """{"recipient":"$vitalik","amount":"0.1","asset":"ETH"}"""
        assertEquals("CAPABILITY_UNAVAILABLE", code(run(args, tool(w = null))))
        wallet.wallet = false
        assertEquals("CAPABILITY_UNAVAILABLE", code(run(args)))
        wallet.wallet = true
        wallet.chainList = null
        assertEquals("CAPABILITY_UNAVAILABLE", code(run(args)))
        wallet.chainList = listOf(BuiltInChains.ETHEREUM, BuiltInChains.GNOSIS)
        wallet.busy = true
        assertEquals("CAPABILITY_UNAVAILABLE", code(run(args)))
        wallet.busy = false
        wallet.shortOf = "ETH"
        val short = run(args)
        assertEquals("CAPABILITY_UNAVAILABLE", code(short))
        assertTrue(short.output, short.output.contains("Not enough ETH for this transfer and its fee."))
        wallet.shortOf = null
        wallet.maxPerGas = BigInteger.ZERO
        val free = run(args)
        assertEquals("CAPABILITY_UNAVAILABLE", code(free))
        assertTrue(free.output.contains("maximum transfer fee"))
        assertTrue(asked.isEmpty())
        assertTrue(wallet.sends.isEmpty())
    }

    @Test
    fun `the user's no ends it, nothing is signed, and the turn ends`() {
        answers = { TransferAnswer.Declined }
        val out = run("""{"recipient":"$vitalik","amount":"0.1","asset":"ETH"}""")
        assertEquals("USER_DECLINED", code(out))
        assertTrue(out.declined)
        assertEquals(1, asked.size)
        assertTrue(wallet.sends.isEmpty())
    }

    @Test
    fun `a sheet taken down without an answer sends nothing and isn't the user's no`() {
        answers = { TransferAnswer.Withdrawn }
        val out = run("""{"recipient":"$vitalik","amount":"0.1","asset":"ETH"}""")
        assertEquals("APPROVAL_REQUIRED", code(out))
        assertFalse(out.declined)
        assertTrue(wallet.sends.isEmpty())
        // An asker that fails sends nothing either.
        answers = { throw IllegalStateException("host gone") }
        assertTrue(runCatching { run("""{"recipient":"$vitalik","amount":"0.1","asset":"ETH"}""") }.isFailure)
        assertTrue(wallet.sends.isEmpty())
    }

    @Test
    fun `a confirmed native transfer sends exactly the quote shown, and the receipt carries its hash`() {
        val out = run("""{"recipient":"$vitalik","amount":"0.25","asset":"eth","chainId":1}""")
        assertFalse(out.output, out.isError)
        assertEquals(1, asked.size)
        val shown = asked.single()
        assertEquals("s", shown.sessionId)
        assertFalse(shown.repriced)
        assertNull(shown.nameTrust)
        assertEquals(1, wallet.sends.size)
        assertEquals(shown.quote, wallet.sends.single())
        val tx = wallet.sends.single().tx
        assertEquals(vitalik, tx.to)
        assertEquals(BigInteger("250000000000000000"), tx.value)
        assertEquals(0, tx.data.size)
        assertEquals(me, wallet.sends.single().request.from)
        val w = JSONObject(out.output).getJSONObject("result").getJSONObject("wallet")
        assertEquals("broadcast", w.getString("action"))
        assertEquals(1, w.getInt("chainId"))
        assertEquals("0x" + "ab".repeat(32), w.getString("transactionHash"))
        assertEquals(vitalik, w.getString("recipient"))
        assertEquals("0.25", w.getString("amount"))
        assertEquals("ETH", w.getString("asset"))
    }

    @Test
    fun `a token to a verified name is a transfer call, from the account asked for, and the name is checked again before signing`() {
        wallet.names = { n, _ -> EnsAddressResult.Ok(n, vitalik.lowercase(), EnsTrust(verified = true, agreed = listOf("a", "b"))) }
        val out = run("""{"recipient":"vitalik.eth","amount":"12.5","asset":"xbzz","walletIndex":1}""")
        assertFalse(out.output, out.isError)
        val shown = asked.single()
        assertFalse(shown.needsAcceptance)
        assertEquals("vitalik.eth", shown.quote.request.toName)
        val sent = wallet.sends.single()
        assertEquals(second, sent.request.from)
        assertEquals(100L, sent.tx.chainId)
        assertEquals("0xdBF3Ea6F5beE45c02255B2c26a16F300502F68da", sent.tx.to)
        assertEquals(BigInteger.ZERO, sent.tx.value)
        // transfer(vitalik, 12.5 xBZZ): 16 decimals.
        val hex = sent.tx.data.joinToString("") { "%02x".format(it) }
        assertTrue(hex.startsWith("a9059cbb" + "0".repeat(24) + vitalik.substring(2).lowercase()))
        assertEquals(BigInteger("125000000000000000"), BigInteger(hex.substring(72), 16))
        assertEquals(listOf("vitalik.eth" to false, "vitalik.eth" to true), wallet.lookups)
        // An index that names no account.
        assertEquals("CAPABILITY_UNAVAILABLE", code(run("""{"recipient":"$vitalik","amount":"1","asset":"ETH","walletIndex":7}""")))
    }

    @Test
    fun `a name only one server vouched for needs the user's own OK, which travels with the send`() {
        wallet.names = { n, _ -> EnsAddressResult.Ok(n, vitalik.lowercase(), EnsTrust(verified = false, agreed = listOf("a"))) }
        // Confirmed without the box ticked (the sheet keeps Confirm off; the tool checks too).
        val out = run("""{"recipient":"vitalik.eth","amount":"1","asset":"ETH"}""")
        assertEquals("APPROVAL_REQUIRED", code(out))
        assertTrue(asked.single().needsAcceptance)
        assertTrue(wallet.sends.isEmpty())
        answers = { TransferAnswer.Approved(acceptedUnverified = true) }
        assertFalse(run("""{"recipient":"vitalik.eth","amount":"1","asset":"ETH"}""").isError)
        assertTrue(wallet.sends.single().request.toNameAccepted)
    }

    @Test
    fun `a name that moved after the sheet sends nothing`() {
        wallet.names = { n, fresh ->
            EnsAddressResult.Ok(n, if (fresh) other else vitalik.lowercase(), EnsTrust(verified = true, agreed = listOf("a", "b")))
        }
        val out = run("""{"recipient":"vitalik.eth","amount":"1","asset":"ETH"}""")
        assertEquals("CAPABILITY_UNAVAILABLE", code(out))
        assertTrue(out.output.contains("Nothing was sent"))
        assertEquals(1, asked.size)
        assertTrue(wallet.sends.isEmpty())
    }

    @Test
    fun `a quote that aged while the sheet was up is priced again and shown again before anything is signed`() {
        var first = true
        wallet.stale = { q -> first.also { if (q.tx.nonce == BigInteger.ONE) first = false } && q.tx.nonce == BigInteger.ONE }
        val out = run("""{"recipient":"$vitalik","amount":"0.1","asset":"ETH"}""")
        assertFalse(out.output, out.isError)
        assertEquals(2, asked.size)
        assertFalse(asked[0].repriced)
        assertTrue(asked[1].repriced)
        assertEquals(asked[1].quote, wallet.sends.single())
        // Always stale: a few sheets, then it gives up, unsigned.
        asked.clear()
        wallet.sends.clear()
        wallet.stale = { true }
        assertEquals("CAPABILITY_UNAVAILABLE", code(run("""{"recipient":"$vitalik","amount":"0.1","asset":"ETH"}""")))
        assertEquals(WalletTransferTool.MAX_SHEETS, asked.size)
        assertTrue(wallet.sends.isEmpty())
    }

    @Test
    fun `what the signer reports reaches the model as it is`() {
        val args = """{"recipient":"$vitalik","amount":"0.1","asset":"ETH"}"""
        // Dropped as stale while a Ledger signed it: shown again, and the second goes.
        var calls = 0
        wallet.outcome = { if (calls++ == 0) WalletSender.Broadcast.Stale(droppedSigned = true) else WalletSender.Broadcast.Sent("0x01") }
        assertFalse(run(args).isError)
        assertEquals(2, asked.size)
        assertTrue(asked[1].repriced)
        // Refused on the Ledger: the user's no.
        wallet.outcome = { WalletSender.Broadcast.Rejected }
        val rejected = run(args)
        assertEquals("USER_DECLINED", code(rejected))
        assertTrue(rejected.declined)
        // May have gone: said so, with the hash, and never "not sent".
        wallet.outcome = { WalletSender.Broadcast.Failed("x", true, "No node answered.", "0xfeed") }
        val unknown = run(args)
        assertEquals("INTERNAL_ERROR", code(unknown))
        assertTrue(unknown.output, unknown.output.contains("outcome_unknown") && unknown.output.contains("0xfeed") && unknown.output.contains("Do not send it again"))
        // Certainly not sent.
        wallet.outcome = { WalletSender.Broadcast.Failed("x", false, "The wallet is locked.") }
        val locked = run(args)
        assertEquals("CAPABILITY_UNAVAILABLE", code(locked))
        assertTrue(locked.output.contains("not sent: The wallet is locked."))
    }

    @Test
    fun `the queue - an answer settles only its own sheet, and Stop or a deleted chat withdraws it`() = runBlocking {
        val t = WalletTransferTool("chat-a", wallet = { wallet })
        val call = async(Dispatchers.Default) { t.run(JSONObject("""{"recipient":"$vitalik","amount":"0.1","asset":"ETH"}""")) }
        val shown = withTimeout(5_000) { AgentTransfers.shown.first { it != null } }!!
        AgentTransfers.answer(shown.id + 1000, TransferAnswer.Approved(false))
        AgentTransfers.withdrawSession("chat-b")
        yield()
        assertEquals(shown, AgentTransfers.shown.value)
        AgentTransfers.withdrawSession("chat-a")
        assertEquals("APPROVAL_REQUIRED", code(call.await()))
        assertNull(AgentTransfers.shown.value)
        // Cancelling the call (Stop) takes its sheet down too.
        val again = async(Dispatchers.Default) { t.run(JSONObject("""{"recipient":"$vitalik","amount":"0.1","asset":"ETH"}""")) }
        withTimeout(5_000) { AgentTransfers.shown.first { it != null } }
        again.cancel()
        withTimeout(5_000) { AgentTransfers.shown.first { it == null } }
        assertTrue(wallet.sends.isEmpty())
        // And the user's Confirm sends the one shown.
        val confirmed = async(Dispatchers.Default) { t.run(JSONObject("""{"recipient":"$vitalik","amount":"0.1","asset":"ETH"}""")) }
        val third = withTimeout(5_000) { AgentTransfers.shown.first { it != null } }!!
        AgentTransfers.answer(third.id, TransferAnswer.Approved(false))
        assertFalse(confirmed.await().isError)
        assertEquals(third.quote, wallet.sends.single())
    }
}
