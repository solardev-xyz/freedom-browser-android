package baby.freedom.mobile.wallet

import baby.freedom.mobile.chains.BuiltInChains
import baby.freedom.mobile.chains.Chain
import baby.freedom.mobile.chains.rpc.ChainDataRouter
import baby.freedom.mobile.chains.rpc.RpcTransport
import baby.freedom.mobile.chains.rpc.WalletRpc
import baby.freedom.mobile.ens.Keccak256
import baby.freedom.mobile.ens.Secp256k1
import baby.freedom.mobile.ens.hexToBytes
import baby.freedom.mobile.ens.toHex
import baby.freedom.mobile.wallet.ledger.LedgerException
import baby.freedom.mobile.wallet.ledger.LedgerKey
import java.math.BigInteger
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone's side of desktop Freedom's remote signing (#113): what each
 * request is answered with, what waits for a sheet, and what a session
 * ending does — with a fake transport and a fake chain, and the real
 * send flow, signing and EIP-712 code.
 */
class OpenLvSessionTest {
    // Hardhat accounts 0 and 1: published test keys, never funded ones.
    private val keys = mapOf(
        "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266" to "ac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80",
        "0x70997970C51812dc3A010C7d01b50e0d17dc79C8" to "59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d",
    )
    private val account0 = WalletAccount(0, "Account 1", "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266")
    private val account1 = WalletAccount(1, "Account 2", "0x70997970C51812dc3A010C7d01b50e0d17dc79C8")
    private val gnosis = BuiltInChains.GNOSIS.copy(rpcUrls = listOf("https://a.example"))
    private val thread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + thread)
    private val senderScope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
        senderScope.cancel()
        thread.close()
    }

    private class FakeEngine : OpenLvEngine {
        override var listener: OpenLvEngine.Listener? = null
        val started = mutableListOf<Pair<Int, String>>()
        var stopped = 0
        val responses = java.util.concurrent.LinkedBlockingQueue<Triple<Int, Int, OpenLvResponse>>()

        override fun start(sid: Int, uri: String) {
            started += sid to uri
        }

        override fun stop() {
            stopped++
        }

        override fun respond(sid: Int, id: Int, response: OpenLvResponse) {
            responses.put(Triple(sid, id, response))
        }

        fun next(): Triple<Int, Int, OpenLvResponse> =
            responses.poll(5, java.util.concurrent.TimeUnit.SECONDS) ?: error("no response")
    }

    private inner class FakeKeys(var list: WalletAccountList? = WalletAccountList(listOf(account0, account1), 0)) : OpenLvSession.Keys {
        var locked = false
        var activity = 0
        override fun accounts() = list
        override fun <T> withKey(account: WalletAccount, block: (ByteArray) -> T): T {
            if (locked) throw VaultLockedException()
            val key = keys.getValue(account.address).hexToBytes()
            return try {
                block(key)
            } finally {
                key.fill(0)
            }
        }

        override fun noteActivity() {
            activity++
        }
    }

    // Hardhat account 2's address, held on a (fake) Ledger (#142).
    private val ledgerAccount = WalletAccount(
        -1, "Ledger", "0x3C44CdDdB6a900fa2b585dd299e03d12FA4293BC",
        LedgerKey("44'/60'/0'/0/0", "AA:BB:CC:DD:EE:FF", "Nano X 1A2B"),
    )
    private val ledgerKey = "5de4111afa1a4b94908f83103eb1f1706367c2e68ca870fc3fb9a804cdab365a"

    /** [FakeKeys] with [ledgerAccount] too, signed on "the Ledger": [ledgerFails] if set, else with its key. */
    private inner class LedgerKeys : OpenLvSession.Keys {
        val soft = FakeKeys(WalletAccountList(listOf(account0, ledgerAccount), 0))
        var ledgerFails: LedgerException? = null
        var ledgerSigned = 0
        var freshAsked: Boolean? = null
        /** Runs on "the Ledger" as it signs: the time the user takes reviewing there. */
        var onReview: (() -> Unit)? = null
        override fun accounts() = soft.accounts()
        override fun <T> withKey(account: WalletAccount, block: (ByteArray) -> T): T {
            check(!account.isLedger) { "a Ledger account's key was asked of the seed" }
            return soft.withKey(account, block)
        }
        override fun noteActivity() = soft.noteActivity()

        private fun <T> onLedger(block: (ByteArray) -> T): T {
            ledgerFails?.let { throw it }
            ledgerSigned++
            return block(ledgerKey.hexToBytes())
        }

        override suspend fun signPersonal(account: WalletAccount, message: ByteArray): String =
            if (!account.isLedger) super.signPersonal(account, message)
            else onLedger { MessageSigning.sign(it, account.address, MessageSigning.personalDigest(message)) }

        override suspend fun signTypedData(account: WalletAccount, data: Eip712.TypedData, digest: ByteArray): String =
            if (!account.isLedger) super.signTypedData(account, data, digest)
            else onLedger { MessageSigning.sign(it, account.address, digest) }

        override fun transactionSigner(account: WalletAccount, fresh: () -> Boolean): suspend (EthTransaction) -> EthTransaction.Signed =
            if (!account.isLedger) super.transactionSigner(account, fresh)
            else { t -> freshAsked = fresh(); onReview?.invoke(); onLedger { t.sign(it, account.address) } }
    }

    private val sent = mutableListOf<String>()

    private fun rpc() = WalletRpc(
        ChainDataRouter(
            chains = { listOf<Chain>(gnosis) },
            transport = RpcTransport { _, body, _ ->
                val req = JSONObject(body)
                fun q(v: Long) = "\"result\":\"0x${v.toString(16)}\""
                val answer = when (req.getString("method")) {
                    "eth_getBalance" -> q(1_000_000_000_000_000_000L)
                    "eth_getTransactionCount" -> q(3)
                    "eth_getBlockByNumber" -> "\"result\":{\"number\":\"0x10\",\"baseFeePerGas\":\"0x7\"}"
                    "eth_maxPriorityFeePerGas" -> q(1)
                    "eth_estimateGas" -> q(50_000)
                    "eth_sendRawTransaction" -> {
                        synchronized(sent) { sent += req.getJSONArray("params").getString(0) }
                        "\"result\":\"0x" + "ab".repeat(32) + "\""
                    }
                    "eth_getTransactionReceipt" -> "\"result\":null"
                    else -> "\"error\":{\"code\":-32601,\"message\":\"no\"}"
                }
                """{"jsonrpc":"2.0","id":1,$answer}"""
            },
        ),
    )

    private fun <K : OpenLvSession.Keys> session(
        engine: FakeEngine,
        keys: K,
        clock: () -> Long = System::currentTimeMillis,
    ): Triple<OpenLvSession, FakeEngine, K> {
        val sender = WalletSender(rpc(), senderScope, clock = clock, pollMs = 10, confirmTimeoutMs = 200)
        runBlocking { sender.awaitRestored() }
        val s = OpenLvSession(engine, keys, { listOf(BuiltInChains.ETHEREUM, gnosis) }, sender, scope)
        return Triple(s, engine, keys)
    }

    private fun session(engine: FakeEngine = FakeEngine()): Triple<OpenLvSession, FakeEngine, FakeKeys> = session(engine, FakeKeys())

    private fun OpenLvSession.startOnScope(uri: String = "openlv://session") = runBlocking { withContext(thread) { start(uri) } }

    private fun OpenLvSession.awaitSheet(): OpenLvSession.Approval = runBlocking { withTimeout(5_000) { approval.first { it != null }!! } }

    private fun result(r: Triple<Int, Int, OpenLvResponse>): Any = (r.third as OpenLvResponse.Result).json

    private fun error(r: Triple<Int, Int, OpenLvResponse>): Int = (r.third as OpenLvResponse.Error).code

    @Test
    fun `plain reads are answered at once and a chain switch needs the chain set up`() {
        val (s, engine, keys) = session()
        s.startOnScope()
        assertEquals(listOf(1 to "openlv://session"), engine.started)
        s.onRequest(1, 1, "eth_chainId", JSONArray())
        assertEquals("0x1", result(engine.next()))
        s.onRequest(1, 2, "eth_accounts", JSONArray())
        assertEquals(0, (result(engine.next()) as JSONArray).length())
        s.onRequest(1, 3, "wallet_switchEthereumChain", JSONArray().put(JSONObject().put("chainId", "0x64")))
        assertEquals(JSONObject.NULL, result(engine.next()))
        s.onRequest(1, 4, "eth_chainId", JSONArray())
        assertEquals("0x64", result(engine.next()))
        s.onRequest(1, 5, "wallet_switchEthereumChain", JSONArray().put(JSONObject().put("chainId", "0x2105")))
        assertEquals(OpenLvSession.UNKNOWN_CHAIN, error(engine.next()))
        s.onRequest(1, 6, "wallet_addEthereumChain", JSONArray().put(JSONObject().put("chainId", "0x2105")))
        assertEquals(OpenLvSession.UNSUPPORTED, error(engine.next()))
        s.onRequest(1, 7, "eth_sign", JSONArray())
        assertEquals(OpenLvSession.UNSUPPORTED, error(engine.next()))
        assertNull(s.approval.value)
        assertEquals(7, keys.activity) // every request keeps an open wallet from idling out
    }

    @Test
    fun `connecting shares only the account picked on the sheet, and a rejection shares none`() {
        val (s, engine) = session()
        s.startOnScope()
        s.onRequest(1, 1, "eth_requestAccounts", JSONArray())
        val sheet = s.awaitSheet()
        val connect = sheet.request as OpenLvSession.Request.Connect
        assertEquals(listOf(account0, account1), connect.accounts)
        assertEquals(account0, connect.suggested)
        sheet.decide(OpenLvSession.Decision.Approve(account1))
        assertEquals(account1.address, (result(engine.next()) as JSONArray).getString(0))
        s.onRequest(1, 2, "eth_accounts", JSONArray())
        assertEquals(account1.address, (result(engine.next()) as JSONArray).getString(0))

        // A new scan: nothing carried over.
        s.startOnScope("openlv://next")
        s.onRequest(2, 3, "eth_accounts", JSONArray())
        assertEquals(0, (result(engine.next()) as JSONArray).length())
        s.onRequest(2, 4, "eth_requestAccounts", JSONArray())
        s.awaitSheet().decide(OpenLvSession.Decision.Reject)
        assertEquals(OpenLvSession.REJECTED_CODE, error(engine.next()))
    }

    @Test
    fun `personal_sign shows the message and signs it with the account asked for`() {
        val (s, engine) = session()
        s.startOnScope()
        val text = "freedom openlv android harness"
        s.onRequest(1, 1, "personal_sign", JSONArray().put("0x" + text.toByteArray().toHex()).put(account1.address.lowercase()))
        val sheet = s.awaitSheet()
        val req = sheet.request as OpenLvSession.Request.PersonalSign
        assertEquals(account1, req.account)
        assertEquals(text, req.text)
        sheet.decide(OpenLvSession.Decision.Approve())
        val sig = result(engine.next()) as String
        assertEquals(account1.address.lowercase(), Secp256k1.recoverPersonalSign(text.toByteArray(), sig)?.lowercase())
    }

    @Test
    fun `an account not in this wallet, or a locked wallet at signing time, signs nothing`() {
        val (s, engine, keys) = session()
        s.startOnScope()
        s.onRequest(1, 1, "personal_sign", JSONArray().put("0x68690a").put("0x0000000000000000000000000000000000000001"))
        assertEquals(OpenLvSession.UNAUTHORIZED, error(engine.next()))
        assertNull(s.approval.value)
        keys.locked = true
        s.onRequest(1, 2, "personal_sign", JSONArray().put("0x6869").put(account0.address))
        s.awaitSheet().decide(OpenLvSession.Decision.Approve())
        assertEquals(OpenLvSession.UNAUTHORIZED, error(engine.next()))
        keys.list = null
        s.onRequest(1, 3, "eth_requestAccounts", JSONArray())
        assertEquals(OpenLvSession.UNAUTHORIZED, error(engine.next()))
    }

    @Test
    fun `typed data is shown field by field and signed as desktop verifies it`() {
        val (s, engine) = session()
        s.startOnScope()
        val payload = JSONObject()
            .put("types", JSONObject().put("Hello", JSONArray().put(JSONObject().put("name", "to").put("type", "string"))))
            .put("domain", JSONObject().put("name", "Freedom").put("chainId", "100"))
            .put("primaryType", "Hello")
            .put("message", JSONObject().put("to", "desktop"))
        s.onRequest(1, 1, "eth_signTypedData_v4", JSONArray().put(account0.address).put(payload.toString()))
        val sheet = s.awaitSheet()
        val req = sheet.request as OpenLvSession.Request.TypedData
        assertEquals("Hello", req.primaryType)
        assertEquals(100L.toBigInteger(), req.chainId)
        assertEquals(gnosis.id, req.chain?.id)
        assertEquals(listOf(Eip712.Line("to", "desktop", 0)), req.message)
        sheet.decide(OpenLvSession.Decision.Approve())
        val sig = result(engine.next()) as String
        val digest = Eip712.digest(Eip712.parseStrict(payload.toString()))
        assertEquals(account0.address.lowercase(), Secp256k1.recover(digest, sig)?.lowercase())

        s.onRequest(1, 2, "eth_signTypedData_v4", JSONArray().put(account0.address).put("{\"types\":{}}"))
        assertEquals(OpenLvSession.INVALID_PARAMS, error(engine.next()))

        // R1-M2: a bound chain ID past Long's range still names that chain (no phone chain has it), never "any network".
        val big = BigInteger.ONE.shiftLeft(64)
        payload.getJSONObject("domain").put("chainId", big.toString())
        s.onRequest(1, 3, "eth_signTypedData_v4", JSONArray().put(account0.address).put(payload.toString()))
        val bigSheet = s.awaitSheet()
        val bigReq = bigSheet.request as OpenLvSession.Request.TypedData
        assertEquals(big, bigReq.chainId)
        assertNull(bigReq.chain)
        bigSheet.decide(OpenLvSession.Decision.Reject)
        assertEquals(OpenLvSession.REJECTED_CODE, error(engine.next()))
    }

    @Test
    fun `one sheet at a time, and a session that ends takes its sheet with it`() {
        val (s, engine) = session()
        s.startOnScope()
        s.onRequest(1, 1, "personal_sign", JSONArray().put("0x6869").put(account0.address))
        s.awaitSheet()
        s.onRequest(1, 2, "personal_sign", JSONArray().put("0x6869").put(account0.address))
        val busy = engine.next()
        assertEquals(2, busy.second)
        assertEquals(OpenLvSession.BUSY, error(busy))
        // Desktop went away: the open sheet is answered as rejected and closes.
        s.onLink(1, OpenLvLink.Disconnected)
        val rejected = engine.next()
        assertEquals(1, rejected.second)
        assertEquals(OpenLvSession.REJECTED_CODE, error(rejected))
        runBlocking { withTimeout(5_000) { s.approval.first { it == null } } }
        assertEquals(OpenLvSession.Status.Disconnected, s.status.value)
        // Anything the old session still says is ignored.
        s.onLink(1, OpenLvLink.Connected)
        s.onRequest(1, 3, "eth_chainId", JSONArray())
        // Both handled (and dropped) by the time a task queued after them on the session's thread runs.
        runBlocking { withContext(thread) {} }
        assertEquals(OpenLvSession.Status.Disconnected, s.status.value)
        assertTrue(engine.responses.isEmpty())
    }

    @Test
    fun `a transaction is priced by the send flow, reviewed, signed, broadcast and answered with its hash`() {
        val (s, engine) = session()
        s.startOnScope()
        s.onRequest(1, 1, "wallet_switchEthereumChain", JSONArray().put(JSONObject().put("chainId", "0x64")))
        engine.next()
        val tx = JSONObject()
            .put("from", account0.address)
            .put("to", "0x9a676e781a523b5d0c0e43731313a708cb607508")
            .put("value", "0x0")
            .put("data", "0xA9059CBB")
            .put("chainId", "0x64")
        s.onRequest(1, 1, "eth_sendTransaction", JSONArray().put(tx))
        val sheet = s.awaitSheet()
        val quote = (sheet.request as OpenLvSession.Request.SendTransaction).quote
        assertEquals(gnosis.id, quote.tx.chainId)
        assertEquals("0x9A676e781A523b5d0C0e43731313A708CB607508", quote.tx.to)
        assertEquals(BigInteger.ZERO, quote.tx.value)
        assertEquals("a9059cbb", quote.tx.data.toHex())
        assertEquals(BigInteger.valueOf(3), quote.tx.nonce)
        assertEquals(BigInteger.valueOf(60_000), quote.tx.gasLimit) // estimate + 20%: it has data
        sheet.decide(OpenLvSession.Decision.Approve())
        val hash = result(engine.next()) as String
        val raw = synchronized(sent) { sent.single() }
        assertEquals("0x" + Keccak256.digest(raw.hexToBytes()).toHex(), hash)
    }

    @Test
    fun `a Ledger account signs on its Ledger, never with a key from the seed, and a refusal there is a rejection`() {
        val (s, engine, keys) = session(FakeEngine(), LedgerKeys())
        s.startOnScope()
        val text = "sign in"
        fun ask(id: Int): OpenLvSession.Approval {
            s.onRequest(1, id, "personal_sign", JSONArray().put("0x" + text.toByteArray().toHex()).put(ledgerAccount.address))
            return s.awaitSheet().also { assertEquals(ledgerAccount, (it.request as OpenLvSession.Request.PersonalSign).account) }
        }
        ask(1).decide(OpenLvSession.Decision.Approve())
        val sig = result(engine.next()) as String
        assertEquals(ledgerAccount.address.lowercase(), Secp256k1.recoverPersonalSign(text.toByteArray(), sig)?.lowercase())
        assertEquals(1, keys.ledgerSigned)

        // Rejected on the device, or its wait cancelled: 4001, as for a site.
        for (kind in listOf(LedgerException.Kind.REJECTED, LedgerException.Kind.CANCELLED)) {
            keys.ledgerFails = LedgerException(kind)
            ask(2).decide(OpenLvSession.Decision.Approve())
            val r = engine.next().third as OpenLvResponse.Error
            assertEquals(OpenLvSession.REJECTED_CODE, r.code)
            assertEquals("Rejected on the Ledger.", r.message)
        }
        // Anything else says what the Ledger said.
        keys.ledgerFails = LedgerException(LedgerException.Kind.LOCKED)
        ask(3).decide(OpenLvSession.Decision.Approve())
        val locked = engine.next().third as OpenLvResponse.Error
        assertEquals(OpenLvSession.INTERNAL, locked.code)
        assertEquals("Ledger: " + LedgerException.Kind.LOCKED.message, locked.message)

        // Typed data too.
        keys.ledgerFails = LedgerException(LedgerException.Kind.REJECTED)
        val payload = JSONObject()
            .put("types", JSONObject().put("Hello", JSONArray().put(JSONObject().put("name", "to").put("type", "string"))))
            .put("domain", JSONObject().put("name", "Freedom"))
            .put("primaryType", "Hello")
            .put("message", JSONObject().put("to", "desktop"))
        s.onRequest(1, 4, "eth_signTypedData_v4", JSONArray().put(ledgerAccount.address).put(payload.toString()))
        s.awaitSheet().decide(OpenLvSession.Decision.Approve())
        assertEquals(OpenLvSession.REJECTED_CODE, error(engine.next()))
    }

    @Test
    fun `a Ledger account's transaction is signed on its Ledger, and a refusal there sends nothing`() {
        val (s, engine, keys) = session(FakeEngine(), LedgerKeys())
        s.startOnScope()
        s.onRequest(1, 1, "wallet_switchEthereumChain", JSONArray().put(JSONObject().put("chainId", "0x64")))
        engine.next()
        val tx = JSONObject().put("from", ledgerAccount.address).put("to", account1.address).put("value", "0x1").put("chainId", "0x64")
        keys.ledgerFails = LedgerException(LedgerException.Kind.REJECTED)
        s.onRequest(1, 2, "eth_sendTransaction", JSONArray().put(tx))
        s.awaitSheet().decide(OpenLvSession.Decision.Approve())
        val refused = engine.next().third as OpenLvResponse.Error
        assertEquals(OpenLvSession.REJECTED_CODE, refused.code)
        assertEquals("Rejected on the Ledger.", refused.message)
        assertTrue(synchronized(sent) { sent.isEmpty() })

        keys.ledgerFails = null
        s.onRequest(1, 3, "eth_sendTransaction", JSONArray().put(tx))
        s.awaitSheet().decide(OpenLvSession.Decision.Approve())
        val hash = result(engine.next()) as String
        val raw = synchronized(sent) { sent.single() }
        assertEquals("0x" + Keccak256.digest(raw.hexToBytes()).toHex(), hash)
        assertEquals(1, keys.ledgerSigned)
        assertEquals(true, keys.freshAsked) // the quote's age is checked once the Ledger is ready
    }

    @Test
    fun `a transaction for another chain than the session's, or from another wallet, isn't priced`() {
        val (s, engine) = session()
        s.startOnScope()
        val tx = JSONObject().put("from", account0.address).put("to", account1.address).put("value", "0x1").put("chainId", "0x64")
        s.onRequest(1, 1, "eth_sendTransaction", JSONArray().put(tx))
        assertEquals(OpenLvSession.INVALID_PARAMS, error(engine.next())) // still on Ethereum: switch first
        s.onRequest(1, 2, "eth_sendTransaction", JSONArray().put(JSONObject(tx.toString()).put("from", "0x0000000000000000000000000000000000000001").put("chainId", "0x1")))
        assertEquals(OpenLvSession.UNAUTHORIZED, error(engine.next()))
        s.onRequest(1, 3, "eth_sendTransaction", JSONArray().put(JSONObject(tx.toString()).put("chainId", "0x1").apply { remove("to") }))
        assertEquals(OpenLvSession.INVALID_PARAMS, error(engine.next()))
        assertNull(s.approval.value)
    }

    @Test
    fun `call data over the cap is refused before it's priced or shown`() {
        val (s, engine) = session()
        s.startOnScope()
        s.onRequest(1, 0, "wallet_switchEthereumChain", JSONArray().put(JSONObject().put("chainId", "0x64")))
        engine.next()
        val tx = JSONObject().put("from", account0.address).put("to", account1.address).put("chainId", "0x64")
        s.onRequest(1, 1, "eth_sendTransaction", JSONArray().put(JSONObject(tx.toString()).put("data", "0x" + "ab".repeat(OpenLvSession.MAX_CALL_DATA + 1))))
        assertEquals(OpenLvSession.INVALID_PARAMS, error(engine.next()))
        assertNull(s.approval.value)
        // At the cap it's a request like any other.
        s.onRequest(1, 2, "eth_sendTransaction", JSONArray().put(JSONObject(tx.toString()).put("data", "0x" + "ab".repeat(OpenLvSession.MAX_CALL_DATA))))
        val quote = (s.awaitSheet().request as OpenLvSession.Request.SendTransaction).quote
        assertEquals(OpenLvSession.MAX_CALL_DATA, quote.tx.data.size)
    }

    @Test
    fun `a transaction approved on the Ledger too late is priced again, and the sheet says why`() {
        val now = java.util.concurrent.atomic.AtomicLong(1_000_000L)
        val (s, engine, keys) = session(FakeEngine(), LedgerKeys()) { now.get() }
        s.startOnScope()
        s.onRequest(1, 1, "wallet_switchEthereumChain", JSONArray().put(JSONObject().put("chainId", "0x64")))
        engine.next()
        val tx = JSONObject().put("from", ledgerAccount.address).put("to", account1.address).put("value", "0x1").put("chainId", "0x64")
        // Fresh as the Ledger showed it; the review there outlasted SIGNED_TTL_MS.
        keys.onReview = { now.addAndGet(WalletSender.SIGNED_TTL_MS) }
        s.onRequest(1, 2, "eth_sendTransaction", JSONArray().put(tx))
        val first = s.awaitSheet()
        assertNull((first.request as OpenLvSession.Request.SendTransaction).notice)
        first.decide(OpenLvSession.Decision.Approve())
        val again = runBlocking { withTimeout(5_000) { s.approval.first { it != null && it !== first }!! } }
        val notice = (again.request as OpenLvSession.Request.SendTransaction).notice!!
        assertTrue(notice, notice.contains("Ledger approval came over three minutes"))
        assertFalse(notice, notice.contains("over a minute old"))
        assertTrue(synchronized(sent) { sent.isEmpty() })
        again.decide(OpenLvSession.Decision.Reject)
        assertEquals(OpenLvSession.REJECTED_CODE, error(engine.next()))
    }

    @Test
    fun `a transaction whose fees aged before signing says they were over a minute old`() {
        val now = java.util.concurrent.atomic.AtomicLong(1_000_000L)
        val (s, engine) = session(FakeEngine(), FakeKeys()) { now.get() }
        s.startOnScope()
        s.onRequest(1, 1, "wallet_switchEthereumChain", JSONArray().put(JSONObject().put("chainId", "0x64")))
        engine.next()
        val tx = JSONObject().put("from", account0.address).put("to", account1.address).put("value", "0x1").put("chainId", "0x64")
        s.onRequest(1, 2, "eth_sendTransaction", JSONArray().put(tx))
        val first = s.awaitSheet()
        now.addAndGet(WalletSender.QUOTE_TTL_MS) // the sheet stood for a minute
        first.decide(OpenLvSession.Decision.Approve())
        val again = runBlocking { withTimeout(5_000) { s.approval.first { it != null && it !== first }!! } }
        val notice = (again.request as OpenLvSession.Request.SendTransaction).notice!!
        assertTrue(notice, notice.contains("over a minute old"))
        again.decide(OpenLvSession.Decision.Reject)
        assertEquals(OpenLvSession.REJECTED_CODE, error(engine.next()))
    }

    @Test
    fun `removing the shared Ledger account takes it back from desktop, and re-adding it needs a new Connect`() {
        val keys = LedgerKeys()
        val (s, engine) = session(FakeEngine(), keys)
        s.startOnScope()
        s.onRequest(1, 1, "eth_requestAccounts", JSONArray())
        s.awaitSheet().decide(OpenLvSession.Decision.Approve(ledgerAccount))
        assertEquals(ledgerAccount.address, (result(engine.next()) as JSONArray).getString(0))

        runBlocking { withContext(thread) { s.accountRemoved(ledgerAccount.address.lowercase()) } }
        s.onRequest(1, 2, "eth_accounts", JSONArray())
        assertEquals(0, (result(engine.next()) as JSONArray).length())
        // Added again: still not desktop's until a Connect sheet says so.
        s.onRequest(1, 3, "eth_requestAccounts", JSONArray())
        assertTrue(s.awaitSheet().request is OpenLvSession.Request.Connect)
    }

    @Test
    fun `an account gone from the wallet's list stops being desktop's even without the hook`() {
        val keys = LedgerKeys()
        val (s, engine) = session(FakeEngine(), keys)
        s.startOnScope()
        s.onRequest(1, 1, "eth_requestAccounts", JSONArray())
        s.awaitSheet().decide(OpenLvSession.Decision.Approve(ledgerAccount))
        engine.next()
        val full = keys.soft.list
        keys.soft.list = WalletAccountList(listOf(account0), 0)
        s.onRequest(1, 2, "eth_accounts", JSONArray())
        assertEquals(0, (result(engine.next()) as JSONArray).length())
        keys.soft.list = full
        s.onRequest(1, 3, "eth_accounts", JSONArray())
        assertEquals(0, (result(engine.next()) as JSONArray).length())
        // A list not read yet says nothing about the shared account.
        s.onRequest(1, 4, "eth_requestAccounts", JSONArray())
        s.awaitSheet().decide(OpenLvSession.Decision.Approve(ledgerAccount))
        engine.next()
        keys.soft.list = null
        s.onRequest(1, 5, "eth_accounts", JSONArray())
        assertEquals(ledgerAccount.address, (result(engine.next()) as JSONArray).getString(0))
        keys.soft.list = full
    }
}
