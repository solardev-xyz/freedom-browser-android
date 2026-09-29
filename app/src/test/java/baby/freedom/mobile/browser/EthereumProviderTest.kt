package baby.freedom.mobile.browser

import baby.freedom.mobile.chains.BuiltInChains
import baby.freedom.mobile.chains.Chain
import baby.freedom.mobile.chains.rpc.ChainRpcException
import baby.freedom.mobile.chains.rpc.ChainSource
import baby.freedom.mobile.chains.rpc.ChainTrust
import baby.freedom.mobile.ens.Keccak256
import baby.freedom.mobile.wallet.EthTransaction
import baby.freedom.mobile.wallet.MessageSigning
import baby.freedom.mobile.wallet.SendException
import baby.freedom.mobile.wallet.SendQuote
import baby.freedom.mobile.wallet.SendRequest
import baby.freedom.mobile.wallet.Eip712
import baby.freedom.mobile.wallet.WalletAccount
import baby.freedom.mobile.wallet.ledger.LedgerException
import java.math.BigInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The `window.ethereum` provider's rules (#110), with the wallet, the stores and the chain behind fakes. */
class EthereumProviderTest {
    private val site = "https://app.example"
    private val cow = Keccak256.digest("cow".toByteArray())
    private val main = WalletAccount(0, "Account 1", "0xCD2a3d9F938E13CD947Ec05AbC7FE734Df8DD826")
    private val second = WalletAccount(1, "Account 2", "0xbBbBBBBbbBBBbbbBbbBbbbbBBbBbbbbBbBbbBBbB")

    private class FakeGrants : EthereumProvider.Grants {
        val grants = HashMap<String, EthereumProvider.Grant>()
        val added = mutableListOf<Chain>()
        /** The store can't be read right now. */
        var unreadable = false
        override suspend fun grantFor(origin: String): EthereumProvider.Grant? {
            if (unreadable) throw EthereumProvider.GrantsUnreadable()
            return grants[origin]
        }
        override suspend fun grant(origin: String, account: String, chainId: Long): Boolean {
            grants[origin] = EthereumProvider.Grant(account, chainId)
            return true
        }
        override suspend fun setChain(origin: String, chainId: Long): Boolean {
            val g = grants[origin] ?: return false
            grants[origin] = g.copy(chainId = chainId)
            return true
        }
        /** When set, a revoke waits here before it commits: a write still in flight. */
        var revokeGate: CompletableDeferred<Unit>? = null
        val revokeStarted = CompletableDeferred<Unit>()
        override suspend fun revoke(origin: String): Boolean {
            revokeStarted.complete(Unit)
            revokeGate?.await()
            return grants.remove(origin).let { true }
        }
        override suspend fun all(): Map<String, EthereumProvider.Grant> {
            if (unreadable) throw EthereumProvider.GrantsUnreadable()
            return HashMap(grants)
        }
        override suspend fun clear() = grants.clear().let { true }
        override suspend fun addChain(chain: Chain): Boolean {
            added += chain
            return true
        }
    }

    private inner class FakeWallet : EthereumProvider.Wallet {
        var list: List<WalletAccount>? = listOf(main, second)
        var activity = 0
        var open = true
        override fun unlocked() = open
        override suspend fun accounts() = list
        override fun noteActivity() {
            activity++
        }
        /** What a Ledger answers instead of signing (#142), when set. */
        var ledgerFailure: LedgerException? = null
        override suspend fun signMessage(account: WalletAccount, message: ByteArray) =
            sign(account, MessageSigning.personalDigest(message))
        override suspend fun signTypedData(account: WalletAccount, data: Eip712.TypedData, digest: ByteArray) = sign(account, digest)
        private fun sign(account: WalletAccount, digest: ByteArray): String {
            ledgerFailure?.let { throw it }
            check(account == main) { "only the cow key here" }
            return MessageSigning.sign(cow, account.address, digest)
        }
    }

    private class FakeSends : EthereumProvider.Sends {
        val prepared = mutableListOf<SendRequest>()
        val outcomes = ArrayDeque<EthereumProvider.Submitted>()
        var prepareError: String? = null
        var busy = false
        /** What the send is priced at. */
        var fees: EthTransaction.Fees = EthTransaction.Fees.Eip1559(BigInteger.valueOf(2_000_000_000), BigInteger.ONE)
        override fun busy() = busy
        override suspend fun prepare(request: SendRequest): SendQuote {
            prepareError?.let { throw SendException(it) }
            prepared += request
            val (to, value, data) = request.call()
            val tx = EthTransaction(
                chainId = request.chain.id,
                nonce = BigInteger.valueOf(7),
                gasLimit = BigInteger.valueOf(50_000),
                to = to,
                value = value,
                data = data,
                fees = fees,
            )
            val trust = ChainTrust(ChainTrust.Level.VERIFIED, ChainSource.QUORUM, emptyList(), emptyList(), emptyList(), 3, 2, null)
            return SendQuote(request, tx, BigInteger.TEN.pow(18), null, 0, trust)
        }
        override suspend fun submit(quote: SendQuote) = outcomes.removeFirst()
    }

    private class FakeAutoApprove : EthereumProvider.AutoApprove {
        val rules = LinkedHashSet<String>()
        var failWrites = false
        /** The store can't be read: nothing matches. */
        var unreadable = false
        override suspend fun matches(rule: AutoApproveRule) = !unreadable && rule.key in rules
        override suspend fun grant(rule: AutoApproveRule) = !failWrites && rules.add(rule.key).let { true }
        override suspend fun revokeOrigin(origin: String) = !failWrites && rules.removeAll { it.startsWith("$origin|") }.let { true }
        override suspend fun clear() = !failWrites && rules.clear().let { true }
    }

    private val sepolia = Chain(id = 11155111, name = "Sepolia", symbol = "ETH", rpcUrls = listOf("https://rpc.sepolia.org"), isTestnet = true)
    /** What Settings → Chains has; null when it can't be read. */
    private var chainList: List<Chain>? = BuiltInChains.ALL + sepolia
    /** Runs once, right after the provider's next read of [chainList] took its snapshot. */
    private var afterChainsRead: (() -> Unit)? = null
    private val grants = FakeGrants()
    private val wallet = FakeWallet()
    private val sends = FakeSends()
    private val rules = FakeAutoApprove()
    private val readsSeen = mutableListOf<String>()
    private var readAnswer: (String) -> Any? = { "0x1" }
    private val events = mutableListOf<Triple<String, String, String>>()
    private val asks = mutableListOf<EthAsk>()
    private var answer: (EthAsk) -> EthAnswer = { EthAnswer.Rejected }

    private val provider = EthereumProvider(
        grants = grants,
        wallet = wallet,
        chains = {
            val list = chainList
            afterChainsRead?.let { afterChainsRead = null; it() }
            list ?: throw java.io.IOException("unreadable")
        },
        reads = { chainId, method, params, origin ->
            readsSeen += "$chainId $method $params $origin"
            readAnswer(method)
        },
        sends = sends,
        autoApprove = rules,
    ).also { p -> p.events = EthereumProvider.Events { o, e, d -> events += Triple(o, e, d.toString()) } }

    private fun call(method: String, params: JSONArray = JSONArray(), origin: String = site) = runBlocking {
        provider.request(origin, method, params) { ask ->
            asks += ask
            answer(ask)
        }
    }

    private fun ok(r: EthereumProvider.Reply): Any = (r as? EthereumProvider.Reply.Ok)?.value ?: error("not ok: $r")
    private fun code(r: EthereumProvider.Reply): Int = (r as? EthereumProvider.Reply.Err)?.code ?: error("not an error: $r")
    private fun message(r: EthereumProvider.Reply): String = (r as? EthereumProvider.Reply.Err)?.message ?: error("not an error: $r")

    private fun connect(account: WalletAccount = main) {
        answer = { EthAnswer.Approved(account) }
        ok(call("eth_requestAccounts"))
        asks.clear()
        events.clear()
    }

    @Test
    fun `a site starts on Gnosis with no accounts, and nothing is asked`() {
        assertEquals("0x64", ok(call("eth_chainId")))
        assertEquals("100", ok(call("net_version")))
        assertEquals("[]", ok(call("eth_accounts")).toString())
        assertEquals(JSONObject.NULL, ok(call("eth_coinbase")))
        assertTrue(asks.isEmpty())
    }

    @Test
    fun `connecting asks once, shares the account picked, and says so to the page`() {
        answer = { EthAnswer.Approved(second) }
        assertEquals("[\"${second.address}\"]", ok(call("eth_requestAccounts")).toString())
        assertEquals(listOf<EthAsk>(EthAsk.Connect(site, BuiltInChains.GNOSIS)), asks)
        assertEquals(EthereumProvider.Grant(second.address, 100), grants.grants[site])
        assertEquals(
            listOf(Triple(site, "accountsChanged", "[\"${second.address}\"]"), Triple(site, "connect", "{\"chainId\":\"0x64\"}")),
            events,
        )
        asks.clear()
        assertEquals("[\"${second.address}\"]", ok(call("eth_requestAccounts")).toString())
        assertEquals("[\"${second.address}\"]", ok(call("eth_accounts")).toString())
        assertTrue(asks.isEmpty())
        assertTrue(wallet.activity > 0)
    }

    @Test
    fun `a rejected connect, or one naming an account the wallet doesn't have, is 4001 and grants nothing`() {
        answer = { EthAnswer.Rejected }
        assertEquals(4001, code(call("eth_requestAccounts")))
        answer = { EthAnswer.Approved(WalletAccount(9, "Stranger", "0x1111111111111111111111111111111111111111")) }
        assertEquals(4001, code(call("eth_requestAccounts")))
        assertTrue(grants.grants.isEmpty())
    }

    @Test
    fun `a grant for an account the wallet no longer has shows no accounts`() {
        grants.grants[site] = EthereumProvider.Grant("0x1111111111111111111111111111111111111111", 100)
        assertEquals("[]", ok(call("eth_accounts")).toString())
        assertEquals(4100, code(call("personal_sign", JSONArray().put("0x68656c6c6f").put("0x1111111111111111111111111111111111111111"))))
        wallet.list = null
        grants.grants[site] = EthereumProvider.Grant(main.address, 100)
        assertEquals("[]", ok(call("eth_accounts")).toString())
    }

    @Test
    fun `personal_sign needs a connection, shows the message, and signs it as ethers would`() {
        assertEquals(4100, code(call("personal_sign", JSONArray().put("hello").put(main.address))))
        connect()
        answer = { EthAnswer.Approved() }
        val sig = ok(call("personal_sign", JSONArray().put("0x68656c6c6f").put(main.address.lowercase())))
        assertEquals(
            "0x2452a50a1b27db559e685e82ef59445ff08ca6843b5089aa1c32a70db206d47d693e5ae94daffccbbf590c5d2a72ad5706994748d2c8d3a8b39355589e16e8751c",
            sig,
        )
        assertEquals(EthAsk.SignMessage(site, main, "hello", "0x68656c6c6f"), asks.single())
        // [address, message] works too; plain text is signed as its UTF-8 bytes.
        assertEquals(sig, ok(call("personal_sign", JSONArray().put(main.address).put("hello"))))
    }

    @Test
    fun `a Ledger that refuses or fails is a clear error for the page, never a hang`() {
        connect()
        answer = { EthAnswer.Approved() }
        val typed = JSONObject()
            .put("types", JSONObject().put("EIP712Domain", JSONArray()).put("M", JSONArray().put(JSONObject().put("name", "a").put("type", "uint8"))))
            .put("primaryType", "M").put("domain", JSONObject()).put("message", JSONObject().put("a", 1))
        for ((failure, code) in listOf(
            LedgerException(LedgerException.Kind.REJECTED) to 4001,
            LedgerException(LedgerException.Kind.CANCELLED) to 4001,
            LedgerException(LedgerException.Kind.DISCONNECTED) to -32603,
            LedgerException(LedgerException.Kind.TIMEOUT) to -32603,
        )) {
            wallet.ledgerFailure = failure
            val personal = call("personal_sign", JSONArray().put("0x68656c6c6f").put(main.address))
            assertEquals(failure.kind.name, code, code(personal))
            val data = call("eth_signTypedData_v4", JSONArray().put(main.address).put(typed.toString()))
            assertEquals(failure.kind.name, code, code(data))
            if (code != 4001) assertTrue(message(personal).contains(failure.kind.message))
        }
    }

    @Test
    fun `personal_sign of bytes that aren't text shows hex`() {
        connect()
        answer = { EthAnswer.Approved() }
        ok(call("personal_sign", JSONArray().put("0xdeadbeef").put(main.address)))
        assertEquals(EthAsk.SignMessage(site, main, null, "0xdeadbeef"), asks.single())
    }

    @Test
    fun `personal_sign text with a bidi override or an invisible character shows hex`() {
        connect()
        answer = { EthAnswer.Approved() }
        for (text in listOf("pay \u202Eevil\u202C", "a\u2066b\u2069", "zero\u200Bwidth", "tag\uDB40\uDC01", "\uFEFFbom")) {
            asks.clear()
            ok(call("personal_sign", JSONArray().put(text).put(main.address)))
            val shown = asks.single() as EthAsk.SignMessage
            assertNull(text, shown.text)
            assertEquals("0x" + text.toByteArray().joinToString("") { "%02x".format(it) }, shown.hex)
            // The same bytes sent as hex are shown the same way.
            asks.clear()
            ok(call("personal_sign", JSONArray().put(shown.hex).put(main.address)))
            assertNull((asks.single() as EthAsk.SignMessage).text)
        }
        assertEquals("Sign in\nNonce: 1 ✓ 🐄", MessageSigning.readableText("Sign in\nNonce: 1 ✓ 🐄".toByteArray()))
    }

    @Test
    fun `personal_sign text that hides or overprints what's around it shows hex, as desktop signing does`() {
        // Each passed the page-signing check before #229; desktop signing already refused them all.
        connect()
        answer = { EthAnswer.Approved() }
        for (text in listOf(
            "line\u2028separator",
            "smuggled a\uFE00\uFE01\uFE02\uFE03",
            "blank \u2800 braille",
            "hangul\u3164filler",
            "Zalgo a" + "\u0301".repeat(8) + " spender",
            "   ",
        )) {
            asks.clear()
            ok(call("personal_sign", JSONArray().put(text).put(main.address)))
            assertNull(text, (asks.single() as EthAsk.SignMessage).text)
        }
        // Real text with a few stacked marks (Vietnamese) still reads as text.
        asks.clear()
        ok(call("personal_sign", JSONArray().put("Xin chào, Việt Nam").put(main.address)))
        assertEquals("Xin chào, Việt Nam", (asks.single() as EthAsk.SignMessage).text)
    }

    @Test
    fun `signing as another account than the connected one is refused before any sheet`() {
        connect()
        answer = { EthAnswer.Approved() }
        assertEquals(-32602, code(call("personal_sign", JSONArray().put("hello").put(second.address))))
        assertEquals(-32602, code(call("eth_signTypedData_v4", JSONArray().put(second.address).put("{}"))))
        assertTrue(asks.isEmpty())
    }

    @Test
    fun `a tab paused after a rejection gets 4001 saying why no sheet came up`() {
        connect()
        answer = { EthAnswer.Paused }
        val err = call("personal_sign", JSONArray().put("hello").put(main.address)) as EthereumProvider.Reply.Err
        assertEquals(4001, err.code)
        assertTrue(err.message, err.message.contains("reload"))
        assertEquals(4001, code(call("wallet_switchEthereumChain", JSONArray().put(JSONObject().put("chainId", "0x1")))))
    }

    @Test
    fun `a rejected signature is 4001`() {
        connect()
        answer = { EthAnswer.Rejected }
        assertEquals(4001, code(call("personal_sign", JSONArray().put("hello").put(main.address))))
    }

    private val mail = """{"types":{"EIP712Domain":[{"name":"name","type":"string"},{"name":"version","type":"string"},{"name":"chainId","type":"uint256"},{"name":"verifyingContract","type":"address"}],"Person":[{"name":"name","type":"string"},{"name":"wallet","type":"address"}],"Mail":[{"name":"from","type":"Person"},{"name":"to","type":"Person"},{"name":"contents","type":"string"}]},"primaryType":"Mail","domain":{"name":"Ether Mail","version":"1","chainId":1,"verifyingContract":"0xCcCCccccCCCCcCCCCCCcCcCccCcCCCcCcccccccC"},"message":{"from":{"name":"Cow","wallet":"0xCD2a3d9F938E13CD947Ec05AbC7FE734Df8DD826"},"to":{"name":"Bob","wallet":"0xbBbBBBBbbBBBbbbBbbBbbbbBBbBbbbbBbBbbBBbB"},"contents":"Hello, Bob!"}}"""

    @Test
    fun `typed data for another chain than the site's is refused, on its chain it's shown and signed`() {
        connect()
        answer = { EthAnswer.Approved() }
        assertEquals(-32602, code(call("eth_signTypedData_v4", JSONArray().put(main.address).put(mail))))
        assertTrue(asks.isEmpty())
        ok(call("wallet_switchEthereumChain", JSONArray().put(JSONObject().put("chainId", "0x1"))))
        asks.clear()
        assertEquals(
            "0x4355c47d63924e8a72e509b65029052eb6c299d53a04e167c5775fd466751c9d07299936d304c153f6443dfa05f40ff007d72911b6f72307f996231605b915621c",
            ok(call("eth_signTypedData_v4", JSONArray().put(main.address).put(JSONObject(mail)))),
        )
        val shown = asks.single() as EthAsk.SignTypedData
        assertEquals("Ether Mail", shown.domainName)
        assertEquals("Mail", shown.primaryType)
        assertEquals(BuiltInChains.ETHEREUM, shown.chain)
        assertTrue(shown.messageJson.contains("Hello, Bob!"))
        assertEquals(-32602, code(call("eth_signTypedData_v4", JSONArray().put(main.address).put("{\"types\":{}}"))))
    }

    @Test
    fun `the typed-data sheet shows only the fields the signature covers`() {
        connect()
        answer = { EthAnswer.Approved() }
        ok(call("wallet_switchEthereumChain", JSONArray().put(JSONObject().put("chainId", "0x1"))))
        asks.clear()
        val data = JSONObject(mail)
        data.getJSONObject("message").put("note", "Just a harmless login").getJSONObject("to").put("extra", "not signed")
        val sig = ok(call("eth_signTypedData_v4", JSONArray().put(main.address).put(data)))
        // Undeclared keys don't change what's signed...
        assertEquals(ok(call("eth_signTypedData_v4", JSONArray().put(main.address).put(mail))), sig)
        // ...and aren't shown.
        val shown = JSONObject((asks.first() as EthAsk.SignTypedData).messageJson)
        assertEquals(setOf("from", "to", "contents"), shown.keys().asSequence().toSet())
        assertEquals(setOf("name", "wallet"), shown.getJSONObject("to").keys().asSequence().toSet())
        assertEquals("Bob", shown.getJSONObject("to").getString("name"))
    }

    @Test
    fun `the typed-data sheet names an application or contract only if the domain type declares it`() {
        connect()
        answer = { EthAnswer.Approved() }
        ok(call("wallet_switchEthereumChain", JSONArray().put(JSONObject().put("chainId", "0x1"))))
        asks.clear()
        ok(call("eth_signTypedData_v4", JSONArray().put(main.address).put(mail)))
        (asks.single() as EthAsk.SignTypedData).let {
            assertEquals("Ether Mail", it.domainName)
            assertEquals("0xCcCCccccCCCCcCCCCCCcCcCccCcCCCcCcccccccC", it.verifyingContract)
        }
        asks.clear()
        // Only chainId is in the domain separator: the name and contract the domain names aren't signed.
        val data = JSONObject(mail)
        data.getJSONObject("types").put("EIP712Domain", JSONArray().put(JSONObject().put("name", "chainId").put("type", "uint256")))
        data.getJSONObject("domain").put("name", "Uniswap")
        ok(call("eth_signTypedData_v4", JSONArray().put(main.address).put(data)))
        (asks.single() as EthAsk.SignTypedData).let {
            assertNull(it.domainName)
            assertNull(it.verifyingContract)
        }
        asks.clear()
        // No EIP712Domain type: it's built from the domain's own keys, so they're all signed and shown.
        data.getJSONObject("types").remove("EIP712Domain")
        ok(call("eth_signTypedData_v4", JSONArray().put(main.address).put(data)))
        assertEquals("Uniswap", (asks.single() as EthAsk.SignTypedData).domainName)
    }

    @Test
    fun `the typed-data sheet writes out characters that hide or rearrange what's around them (#229)`() {
        connect()
        answer = { EthAnswer.Approved() }
        ok(call("wallet_switchEthereumChain", JSONArray().put(JSONObject().put("chainId", "0x1"))))
        asks.clear()
        val zalgo = "Bob" + "\u0301".repeat(12)
        val data = JSONObject(mail)
        data.getJSONObject("domain").put("name", "Uni\u202Eswap")
        data.getJSONObject("message").getJSONObject("to").put("name", zalgo)
        data.getJSONObject("message").put("contents", "tag\uDB40\uDC41\uDB40\uDC42 a\uFE00\uFE01")
        ok(call("eth_signTypedData_v4", JSONArray().put(main.address).put(data)))
        val shown = asks.single() as EthAsk.SignTypedData
        assertEquals("Uni\\u202Eswap", shown.domainName)
        assertFalse(shown.messageJson, MessageSigning.anyHides(shown.messageJson.replace("\n", " ")))
        assertTrue(shown.messageJson, shown.messageJson.contains("\\u{E0041}"))
        // Android's org.json leaves U+2028 and bidi controls as they are when it prints: sheetJson
        // must catch them in the printed text itself, keys included.
        val printed = "{\n  \"to\u202E\": \"a\u2028b\",\n  \"x\": \"\u2066y\u2069\"\n}"
        val sheet = EthereumProvider.sheetJson(printed)
        assertEquals("{\n  \"to\\u202E\": \"a\\u2028b\",\n  \"x\": \"\\u2066y\\u2069\"\n}", sheet)
    }

    @Test
    fun `typed data too long for the sheet to show whole is refused before any sheet (#229)`() {
        connect()
        answer = { EthAnswer.Approved() }
        ok(call("wallet_switchEthereumChain", JSONArray().put(JSONObject().put("chainId", "0x1"))))
        asks.clear()
        // Padding before the fields that matter: the sheet would show only its start.
        val data = JSONObject(mail)
        data.getJSONObject("message").getJSONObject("from").put("name", "x".repeat(SHEET_MAX_CHARS))
        val r = call("eth_signTypedData_v4", JSONArray().put(main.address).put(data))
        assertEquals(-32602, code(r))
        assertTrue(message(r), message(r).contains("too long"))
        assertTrue(asks.isEmpty())
    }

    @Test
    fun `an approval sheet lays out at most the first part of a huge text, never splitting a character`() {
        assertEquals("short" to 0, sheetText("short"))
        val (shown, cut) = sheetText("a".repeat(SHEET_MAX_CHARS + 500))
        assertEquals(SHEET_MAX_CHARS, shown.length)
        assertEquals(500, cut)
        // A surrogate pair straddling the limit is left out whole.
        val (s2, c2) = sheetText("ab\uD83D\uDE00cd", max = 3)
        assertEquals("ab", s2)
        assertEquals(4, c2)
        assertEquals("00ff10", hexOf(byteArrayOf(0, -1, 16)))
        assertEquals("00ff", hexOf(byteArrayOf(0, -1, 16), limit = 2))
    }

    @Test
    fun `typed data that would take minutes to hash is hashed once per type, or refused quickly`() {
        connect()
        answer = { EthAnswer.Approved() }
        // ~400 types hung off Root, and message.items = N × {} of Root[]: each item
        // used to re-walk all 400 types for Root's type hash.
        fun payload(n: Int): JSONObject {
            val types = JSONObject()
            types.put("EIP712Domain", JSONArray().put(JSONObject().put("name", "name").put("type", "string")))
            val root = JSONArray()
            for (i in 0 until 400) {
                types.put("T$i", JSONArray().put(JSONObject().put("name", "v").put("type", "uint256")))
                root.put(JSONObject().put("name", "f$i").put("type", "T$i"))
            }
            types.put("Root", root)
            types.put("Main", JSONArray().put(JSONObject().put("name", "items").put("type", "Root[]")))
            val items = JSONArray().apply { repeat(n) { put(JSONObject()) } }
            return JSONObject().put("types", types).put("primaryType", "Main")
                .put("domain", JSONObject().put("name", "x")).put("message", JSONObject().put("items", items))
        }
        // The bound only has to catch the old minutes-long hashing; shared CI runners are several
        // times slower than a dev machine (1.4 s locally for the whole test), so keep it generous.
        var start = System.nanoTime()
        // Hashed in full, then refused as too long to show whole (#229): 2000 items print past the sheet's limit.
        val long = call("eth_signTypedData_v4", JSONArray().put(main.address).put(payload(2000))) as EthereumProvider.Reply.Err
        assertTrue("took ${(System.nanoTime() - start) / 1_000_000} ms", System.nanoTime() - start < 15_000_000_000L)
        assertTrue(long.message, long.message.contains("too long to show"))
        start = System.nanoTime()
        ok(call("eth_signTypedData_v4", JSONArray().put(main.address).put(payload(500))))
        assertTrue("took ${(System.nanoTime() - start) / 1_000_000} ms", System.nanoTime() - start < 15_000_000_000L)
        asks.clear()
        start = System.nanoTime()
        val err = call("eth_signTypedData_v4", JSONArray().put(main.address).put(payload(20_000))) as EthereumProvider.Reply.Err
        assertTrue("took ${(System.nanoTime() - start) / 1_000_000} ms", System.nanoTime() - start < 15_000_000_000L)
        assertEquals(-32602, err.code)
        assertTrue(err.message, err.message.contains("too large"))
        assertTrue(asks.isEmpty())
    }

    @Test
    fun `typed data is hashed on the compute context, not the caller's`() {
        var dispatched = 0
        val compute = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                dispatched++
                kotlinx.coroutines.Dispatchers.Default.dispatch(context, block)
            }
        }
        val p = EthereumProvider(grants, wallet, { BuiltInChains.ALL }, { _, _, _, _ -> null }, sends, rules, compute)
        connect()
        grants.grants[site] = EthereumProvider.Grant(main.address, 1)
        val sig = runBlocking { p.request(site, "eth_signTypedData_v4", JSONArray().put(main.address).put(mail)) { EthAnswer.Approved() } }
        assertTrue(sig is EthereumProvider.Reply.Ok)
        assertTrue(dispatched > 0)
    }

    @Test
    fun `switching chains asks, moves only that site, and tells its pages`() {
        answer = { EthAnswer.Approved() }
        assertEquals(JSONObject.NULL, ok(call("wallet_switchEthereumChain", JSONArray().put(JSONObject().put("chainId", "0x64")))))
        assertTrue(asks.isEmpty())
        ok(call("wallet_switchEthereumChain", JSONArray().put(JSONObject().put("chainId", "0x2105"))))
        assertEquals(EthAsk.SwitchChain(site, BuiltInChains.GNOSIS, BuiltInChains.BASE), asks.single())
        assertEquals(Triple(site, "chainChanged", "0x2105"), events.single())
        assertEquals("0x2105", ok(call("eth_chainId")))
        assertEquals("0x64", ok(call("eth_chainId", origin = "https://other.example")))
        // Not connected: remembered for the session only, and taken along when it connects.
        assertTrue(grants.grants.isEmpty())
        answer = { EthAnswer.Approved(main) }
        ok(call("eth_requestAccounts"))
        assertEquals(8453L, grants.grants[site]?.chainId)
        answer = { EthAnswer.Approved() }
        ok(call("wallet_switchEthereumChain", JSONArray().put(JSONObject().put("chainId", "0x1"))))
        assertEquals(1L, grants.grants[site]?.chainId)
    }

    @Test
    fun `an unknown chain is 4902, a rejected switch 4001, a malformed one -32602`() {
        answer = { EthAnswer.Rejected }
        assertEquals(4902, code(call("wallet_switchEthereumChain", JSONArray().put(JSONObject().put("chainId", "0x539")))))
        assertEquals(4001, code(call("wallet_switchEthereumChain", JSONArray().put(JSONObject().put("chainId", "0x1")))))
        assertEquals("0x64", ok(call("eth_chainId")))
        assertEquals(-32602, code(call("wallet_switchEthereumChain", JSONArray().put(JSONObject().put("chainId", 1)))))
        assertEquals(-32602, code(call("wallet_switchEthereumChain")))
    }

    @Test
    fun `a site whose chain was removed is moved to Gnosis, written down and told, once`() {
        answer = { EthAnswer.Approved() }
        connect()
        ok(call("wallet_switchEthereumChain", JSONArray().put(JSONObject().put("chainId", "0xaa36a7"))))
        val session = "https://session.example"
        ok(call("wallet_switchEthereumChain", JSONArray().put(JSONObject().put("chainId", "0xaa36a7")), origin = session))
        events.clear()
        // Removed in Settings → Chains: the sweep moves both and tells their pages.
        chainList = BuiltInChains.ALL
        runBlocking { provider.chainsChanged(chainList!!) }
        assertEquals(setOf(Triple(site, "chainChanged", "0x64"), Triple(session, "chainChanged", "0x64")), events.toSet())
        assertEquals(2, events.size)
        assertEquals(100L, grants.grants[site]?.chainId)
        assertEquals("0x64", ok(call("eth_chainId")))
        assertEquals("0x64", ok(call("eth_chainId", origin = session)))
        // Added back later: the sites stay where they were moved, silently switching nobody.
        chainList = BuiltInChains.ALL + sepolia
        runBlocking { provider.chainsChanged(chainList!!) }
        assertEquals("0x64", ok(call("eth_chainId")))
        assertEquals(2, events.size)
    }

    @Test
    fun `a removed chain the sweep hasn't seen yet moves the site on its next request, with chainChanged first`() {
        answer = { EthAnswer.Approved() }
        connect()
        ok(call("wallet_switchEthereumChain", JSONArray().put(JSONObject().put("chainId", "0xaa36a7"))))
        events.clear()
        chainList = BuiltInChains.ALL
        assertEquals("0x64", ok(call("eth_chainId")))
        assertEquals(listOf(Triple(site, "chainChanged", "0x64")), events)
        assertEquals(100L, grants.grants[site]?.chainId)
        ok(call("eth_blockNumber"))
        assertEquals("100 eth_blockNumber [] $site", readsSeen.last())
        assertEquals(1, events.size)
    }

    @Test
    fun `an onchain app pinned to a removed chain is refused, never answered for Gnosis`() {
        chainList = listOf(BuiltInChains.ETHEREUM, BuiltInChains.GNOSIS)
        val app = "https://0x1234567890123456789012345678901234567890-8453.web3.freedom.baby"
        assertEquals(4901, code(call("eth_chainId", origin = app)))
        assertEquals(4901, code(call("eth_blockNumber", origin = app)))
        assertTrue(readsSeen.isEmpty())
        assertTrue(events.isEmpty())
    }

    @Test
    fun `a site on a custom chain while the chain list can't be read is refused, not routed to Gnosis`() {
        answer = { EthAnswer.Approved() }
        connect()
        ok(call("wallet_switchEthereumChain", JSONArray().put(JSONObject().put("chainId", "0xaa36a7"))))
        events.clear()
        chainList = null
        assertEquals(4901, code(call("eth_chainId")))
        assertEquals(11155111L, grants.grants[site]?.chainId)
        assertTrue(events.isEmpty())
        // A built-in chain is still known.
        grants.grants[site] = grants.grants[site]!!.copy(chainId = 1)
        assertEquals("0x1", ok(call("eth_chainId")))
    }

    @Test
    fun `while the chain list can't be read, built-in chains still switch and nothing unknowable is offered to add`() {
        answer = { EthAnswer.Approved() }
        connect()
        chainList = null
        // A built-in chain: switched to as normal, not 4902.
        ok(call("wallet_switchEthereumChain", JSONArray().put(JSONObject().put("chainId", "0x1"))))
        assertEquals(1L, grants.grants[site]?.chainId)
        assertEquals(EthAsk.SwitchChain(site, BuiltInChains.GNOSIS, BuiltInChains.ETHEREUM), asks.single())
        asks.clear()
        // Adding a built-in chain under the site's own name and RPCs only switches, as with a readable list.
        val evil = JSONObject().put("chainId", "0x64").put("chainName", "Evil Gnosis")
            .put("nativeCurrency", JSONObject().put("name", "x").put("symbol", "X").put("decimals", 18))
            .put("rpcUrls", JSONArray().put("https://rpc.evil.example"))
        ok(call("wallet_addEthereumChain", JSONArray().put(evil)))
        assertEquals(EthAsk.SwitchChain(site, BuiltInChains.ETHEREUM, BuiltInChains.GNOSIS), asks.single())
        asks.clear()
        // A chain that may or may not be stored: neither a 4902 nor an Add sheet whose values might not be used.
        assertEquals(-32603, code(call("wallet_switchEthereumChain", JSONArray().put(JSONObject().put("chainId", "0xaa36a7")))))
        val custom = JSONObject(evil.toString()).put("chainId", "0xaa36a7").put("chainName", "Evil Sepolia")
        assertEquals(-32603, code(call("wallet_addEthereumChain", JSONArray().put(custom))))
        assertTrue(asks.isEmpty())
        assertTrue(grants.added.isEmpty())
        assertEquals(100L, grants.grants[site]?.chainId)
    }

    @Test
    fun `a site on a custom chain can still switch to a built-in one while the chain list can't be read`() {
        answer = { EthAnswer.Approved() }
        connect()
        ok(call("wallet_switchEthereumChain", JSONArray().put(JSONObject().put("chainId", "0xaa36a7"))))
        asks.clear()
        events.clear()
        chainList = null
        ok(call("wallet_switchEthereumChain", JSONArray().put(JSONObject().put("chainId", "0x1"))))
        val ask = asks.single() as EthAsk.SwitchChain
        assertEquals(11155111L, ask.from.id)
        assertEquals(BuiltInChains.ETHEREUM, ask.to)
        assertEquals(1L, grants.grants[site]?.chainId)
        assertEquals(listOf(Triple(site, "chainChanged", "0x1")), events)
    }

    @Test
    fun `while connected sites can't be read, a connected site is refused rather than shown as unconnected on Gnosis`() {
        answer = { EthAnswer.Approved() }
        connect()
        ok(call("wallet_switchEthereumChain", JSONArray().put(JSONObject().put("chainId", "0xaa36a7"))))
        events.clear()
        grants.unreadable = true
        assertEquals(-32603, code(call("eth_accounts")))
        assertEquals(-32603, code(call("eth_chainId")))
        assertEquals(-32603, code(call("eth_blockNumber")))
        // The sweep moves nobody, and a disconnect isn't claimed done.
        runBlocking { provider.chainsChanged(BuiltInChains.ALL) }
        assertFalse(runBlocking { provider.disconnect(site) })
        assertTrue(events.isEmpty())
        assertTrue(readsSeen.isEmpty())
        grants.unreadable = false
        assertEquals(JSONArray().put(main.address).toString(), ok(call("eth_accounts")).toString())
        assertEquals(11155111L, grants.grants[site]?.chainId)
    }

    @Test
    fun `a request that read the chain list before a chain was added doesn't move the site back off it`() {
        val custom = Chain(id = 1337, name = "Local", symbol = "ETH", rpcUrls = listOf("https://rpc.local.example"), isTestnet = true)
        connect()
        // The read's snapshot lacks 1337; the same site's add then finishes (stored, switched) before it looks at the site.
        afterChainsRead = {
            chainList = chainList!! + custom
            grants.grants[site] = grants.grants[site]!!.copy(chainId = 1337)
        }
        assertEquals("0x539", ok(call("eth_chainId")))
        assertEquals(1337L, grants.grants[site]?.chainId)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `typed data whose domain type doesn't declare chainId isn't checked against the site's chain, and the sheet says so`() {
        connect()
        answer = { EthAnswer.Approved() }
        val data = JSONObject(mail)
        // chainId 1 in the domain, but not in EIP712Domain: not signed, so neither checked nor shown.
        data.getJSONObject("types").put(
            "EIP712Domain",
            JSONArray().put(JSONObject().put("name", "name").put("type", "string"))
                .put(JSONObject().put("name", "verifyingContract").put("type", "address")),
        )
        ok(call("eth_signTypedData_v4", JSONArray().put(main.address).put(data)))
        assertFalse((asks.single() as EthAsk.SignTypedData).chainBound)
        asks.clear()
        // Declared: checked (the site is on Gnosis, the data says 1) and refused before any sheet.
        assertEquals(-32602, code(call("eth_signTypedData_v4", JSONArray().put(main.address).put(mail))))
        assertTrue(asks.isEmpty())
        ok(call("wallet_switchEthereumChain", JSONArray().put(JSONObject().put("chainId", "0x1"))))
        asks.clear()
        ok(call("eth_signTypedData_v4", JSONArray().put(main.address).put(mail)))
        assertTrue((asks.single() as EthAsk.SignTypedData).chainBound)
        asks.clear()
        // R1-M2: a bound chain ID past Long's range is compared whole, so 2^64 + 1 (1 in its low 64 bits) isn't chain 1.
        val wrapped = JSONObject(mail)
        wrapped.getJSONObject("domain").put("chainId", java.math.BigInteger.ONE.shiftLeft(64).inc().toString())
        assertEquals(-32602, code(call("eth_signTypedData_v4", JSONArray().put(main.address).put(wrapped))))
        assertTrue(asks.isEmpty())
    }

    @Test
    fun `a page-sized array type string is refused at once, not stripped one suffix at a time`() {
        connect()
        answer = { EthAnswer.Approved() }
        fun payload(type: String) = JSONObject()
            .put("types", JSONObject().put("Mail", JSONArray().put(JSONObject().put("name", "a").put("type", type))))
            .put("primaryType", "Mail").put("domain", JSONObject().put("name", "x"))
            .put("message", JSONObject().put("a", JSONArray()))
        val start = System.nanoTime()
        val err = call("eth_signTypedData_v4", JSONArray().put(main.address).put(payload("uint256" + "[]".repeat(450_000)).toString()))
        assertTrue("took ${(System.nanoTime() - start) / 1_000_000} ms", System.nanoTime() - start < 2_000_000_000L)
        assertEquals(-32602, code(err))
        assertTrue(asks.isEmpty())
        // Up to the cap, suffixes are still fine.
        ok(call("eth_signTypedData_v4", JSONArray().put(main.address).put(payload("uint256" + "[]".repeat(100)))))
    }

    @Test
    fun `an onchain app is on its own chain and can't switch`() {
        val app = "https://0x1234567890123456789012345678901234567890-8453.web3.freedom.baby"
        assertEquals("0x2105", ok(call("eth_chainId", origin = app)))
        answer = { EthAnswer.Approved() }
        assertEquals(4200, code(call("wallet_switchEthereumChain", JSONArray().put(JSONObject().put("chainId", "0x64")), origin = app)))
        assertTrue(asks.isEmpty())
    }

    @Test
    fun `adding a chain the wallet has only switches to it, a new one is checked, asked, added and switched to`() {
        answer = { EthAnswer.Approved() }
        ok(call("wallet_addEthereumChain", JSONArray().put(JSONObject().put("chainId", "0x1").put("chainName", "Whatever"))))
        assertEquals(EthAsk.SwitchChain(site, BuiltInChains.GNOSIS, BuiltInChains.ETHEREUM), asks.single())
        assertTrue(grants.added.isEmpty())
        asks.clear()
        val params = JSONObject()
            .put("chainId", "0x539")
            .put("chainName", "Test Net")
            .put("nativeCurrency", JSONObject().put("name", "Test Ether").put("symbol", "TST").put("decimals", 18))
            .put("rpcUrls", JSONArray().put("http://rpc.example").put("https://rpc.test.example"))
            .put("blockExplorerUrls", JSONArray().put("https://scan.test.example/"))
        assertEquals(JSONObject.NULL, ok(call("wallet_addEthereumChain", JSONArray().put(params))))
        val added = (asks.single() as EthAsk.AddChain).chain
        assertEquals(1337L, added.id)
        assertEquals(listOf("https://rpc.test.example"), added.rpcUrls)
        assertEquals("https://scan.test.example", added.explorerUrl)
        assertEquals("Test Ether", added.currencyName)
        assertEquals(listOf(added), grants.added)
        assertEquals(Triple(site, "chainChanged", "0x539"), events.last())
    }

    @Test
    fun `a remote site can't add a chain whose RPC is on the device, a page on loopback can`() {
        answer = { EthAnswer.Approved() }
        val params = JSONObject()
            .put("chainId", "0x539")
            .put("chainName", "Local")
            .put("nativeCurrency", JSONObject().put("name", "Test Ether").put("symbol", "TST").put("decimals", 18))
            .put("rpcUrls", JSONArray().put("http://localhost:8545").put("http://127.0.0.1:8545"))
        assertEquals(-32602, code(call("wallet_addEthereumChain", JSONArray().put(params))))
        params.getJSONArray("rpcUrls").put("https://rpc.local.example")
        ok(call("wallet_addEthereumChain", JSONArray().put(JSONObject(params.toString()))))
        assertEquals(listOf("https://rpc.local.example"), (asks.last() as EthAsk.AddChain).chain.rpcUrls)
        asks.clear()
        val local = "http://localhost:3000"
        ok(call("wallet_addEthereumChain", JSONArray().put(JSONObject(params.toString()).put("chainId", "0x53a")), origin = local))
        assertEquals(listOf("http://localhost:8545", "http://127.0.0.1:8545", "https://rpc.local.example"), (asks.single() as EthAsk.AddChain).chain.rpcUrls)
    }

    @Test
    fun `a chain to add with no usable RPC, or rejected, is not added`() {
        answer = { EthAnswer.Rejected }
        val base = JSONObject().put("chainId", "0x539").put("chainName", "Test")
            .put("nativeCurrency", JSONObject().put("name", "T").put("symbol", "T").put("decimals", 18))
        assertEquals(-32602, code(call("wallet_addEthereumChain", JSONArray().put(JSONObject(base.toString()).put("rpcUrls", JSONArray().put("http://10.0.0.1"))))))
        assertEquals(4001, code(call("wallet_addEthereumChain", JSONArray().put(JSONObject(base.toString()).put("rpcUrls", JSONArray().put("https://rpc.test.example"))))))
        assertTrue(grants.added.isEmpty())
    }

    private fun tx(vararg pairs: Pair<String, Any>) = JSONArray().put(JSONObject().apply { pairs.forEach { (k, v) -> put(k, v) } })

    @Test
    fun `a transaction needs a connection, its own account, a to address and the site's chain`() {
        answer = { EthAnswer.Approved() }
        assertEquals(4100, code(call("eth_sendTransaction", tx("from" to main.address, "to" to second.address))))
        connect()
        answer = { EthAnswer.Approved() }
        assertEquals(-32602, code(call("eth_sendTransaction", tx("from" to second.address, "to" to main.address))))
        assertEquals(-32602, code(call("eth_sendTransaction", tx("from" to main.address, "data" to "0x6080"))))
        assertEquals(-32602, code(call("eth_sendTransaction", tx("from" to main.address, "to" to second.address, "chainId" to "0x1"))))
        assertEquals(-32602, code(call("eth_sendTransaction", tx("to" to "0xbBbBBBBbbBBBbbbBbbBbbbbBBbBbbbbBbBbbBBbb"))))
        assertEquals(-32602, code(call("eth_sendTransaction", tx("to" to second.address, "data" to "0xzz"))))
        assertTrue(asks.isEmpty())
        assertTrue(sends.prepared.isEmpty())
    }

    @Test
    fun `an approved transaction goes through the wallet's send flow and its hash comes back`() {
        connect()
        answer = { EthAnswer.Approved() }
        val hash = "0x" + "ab".repeat(32)
        sends.outcomes += EthereumProvider.Submitted.Sent(hash)
        val r = call(
            "eth_sendTransaction",
            tx("from" to main.address.lowercase(), "to" to second.address.lowercase(), "value" to "0x2386f26fc10000", "data" to "0xa9059cbb", "gas" to "0x7530"),
        )
        assertEquals(hash, ok(r))
        val request = sends.prepared.single()
        assertEquals(second.address, request.to)
        assertEquals(BigInteger("10000000000000000"), request.amount)
        assertEquals("a9059cbb", request.dapp!!.data.joinToString("") { "%02x".format(it) })
        assertEquals(BigInteger.valueOf(30_000), request.dapp!!.gasLimit)
        assertEquals(site, request.dapp!!.origin)
        assertEquals(BuiltInChains.GNOSIS, request.chain)
        assertFalse((asks.single() as EthAsk.SendTransaction).repriced)
    }

    @Test
    fun `a quote that went stale while the sheet was up is priced again and asked again`() {
        connect()
        answer = { EthAnswer.Approved() }
        sends.outcomes += EthereumProvider.Submitted.Stale
        sends.outcomes += EthereumProvider.Submitted.Sent("0x" + "cd".repeat(32))
        ok(call("eth_sendTransaction", tx("to" to second.address)))
        assertEquals(2, sends.prepared.size)
        assertEquals(listOf(false, true), asks.map { (it as EthAsk.SendTransaction).repriced })
    }

    @Test
    fun `a rejected, busy, failed or unpriceable transaction says why`() {
        connect()
        answer = { EthAnswer.Rejected }
        assertEquals(4001, code(call("eth_sendTransaction", tx("to" to second.address))))
        answer = { EthAnswer.Approved() }
        sends.outcomes += EthereumProvider.Submitted.Busy
        assertEquals(-32603, code(call("eth_sendTransaction", tx("to" to second.address))))
        // A send already going out: said before any sheet, not after the user confirmed.
        asks.clear()
        sends.busy = true
        assertEquals(-32603, code(call("eth_sendTransaction", tx("to" to second.address))))
        assertTrue(asks.isEmpty())
        sends.busy = false
        sends.outcomes += EthereumProvider.Submitted.Failed("Not sent: nope", "0x" + "ef".repeat(32))
        val failed = call("eth_sendTransaction", tx("to" to second.address)) as EthereumProvider.Reply.Err
        assertEquals("Not sent: nope", failed.message)
        assertEquals("0x" + "ef".repeat(32), (failed.data as JSONObject).getString("hash"))
        asks.clear()
        sends.prepareError = "Not enough xDAI for the network fee"
        val unpriced = call("eth_sendTransaction", tx("to" to second.address)) as EthereumProvider.Reply.Err
        assertEquals("Not enough xDAI for the network fee", unpriced.message)
        assertTrue(asks.isEmpty())
    }

    @Test
    fun `a transaction refused on the Ledger is a user rejection for the page, not priced again`() {
        connect()
        answer = { EthAnswer.Approved() }
        sends.outcomes += EthereumProvider.Submitted.Rejected
        assertEquals(4001, code(call("eth_sendTransaction", tx("to" to second.address))))
        assertEquals(1, sends.prepared.size)
        assertEquals(1, asks.size)
    }

    @Test
    fun `removing a Ledger account disconnects the sites connected with it, and only those`() {
        val other = "https://other.example"
        connect(second)
        answer = { EthAnswer.Approved(main) }
        ok(call("eth_requestAccounts", origin = other))
        events.clear()
        assertTrue(runBlocking { provider.accountRemoved(second.address.lowercase()) })
        assertNull(grants.grants[site])
        assertEquals(main.address, grants.grants[other]?.account)
        assertEquals(listOf(Triple(site, "accountsChanged", "[]")), events)
        // The same account added back: the site asks again before it sees it.
        assertEquals("[]", ok(call("eth_accounts")).toString())
    }

    @Test
    fun `reads go to the site's chain as the page's own reads, errors as the node gave them`() {
        assertEquals("0x1", ok(call("eth_blockNumber")))
        assertEquals("100 eth_blockNumber [] $site", readsSeen.single())
        readAnswer = { throw ChainRpcException.Rpc(3, "execution reverted", "0x08c379a0") }
        val err = call("eth_call", JSONArray().put(JSONObject().put("to", second.address))) as EthereumProvider.Reply.Err
        assertEquals(3, err.code)
        assertEquals("0x08c379a0", err.data)
        readAnswer = { throw ChainRpcException.AllSourcesFailed(listOf("direct: down"), null) }
        assertEquals(-32002, code(call("eth_getBalance", JSONArray().put(main.address).put("latest"))))
        readAnswer = { null }
        assertEquals(JSONObject.NULL, ok(call("eth_getTransactionReceipt", JSONArray().put("0x" + "00".repeat(32)))))
    }

    @Test
    fun `dangerous and unknown methods are 4200`() {
        connect()
        for (m in listOf("eth_sign", "eth_signTransaction", "eth_sendRawTransaction", "eth_signTypedData_v3", "wallet_nope", "debug_traceTransaction")) {
            assertEquals(m, 4200, code(call(m)))
        }
        assertTrue(asks.isEmpty())
    }

    @Test
    fun `EIP-2255 permissions follow the connection, and revoking one disconnects the site`() {
        assertEquals("[]", ok(call("wallet_getPermissions")).toString())
        answer = { EthAnswer.Approved(main) }
        val perms = ok(call("wallet_requestPermissions", JSONArray().put(JSONObject().put("eth_accounts", JSONObject())))) as JSONArray
        assertEquals("eth_accounts", perms.getJSONObject(0).getString("parentCapability"))
        assertEquals(1, ok(call("wallet_getPermissions")).let { (it as JSONArray).length() })
        events.clear()
        assertEquals(JSONObject.NULL, ok(call("wallet_revokePermissions", JSONArray().put(JSONObject().put("eth_accounts", JSONObject())))))
        assertNull(grants.grants[site])
        assertEquals(Triple(site, "accountsChanged", "[]"), events.single())
        assertEquals("[]", ok(call("eth_accounts")).toString())
    }

    @Test
    fun `a disconnected site stays on its chain, with no chainChanged`() {
        answer = { EthAnswer.Approved() }
        ok(call("wallet_addEthereumChain", JSONArray().put(JSONObject().put("chainId", "0x1").put("chainName", "x"))))
        connect()
        assertEquals(1L, grants.grants[site]?.chainId)
        ok(call("wallet_revokePermissions", JSONArray().put(JSONObject().put("eth_accounts", JSONObject()))))
        assertEquals("0x1", ok(call("eth_chainId")))
        assertEquals("1 eth_blockNumber [] $site", ok(call("eth_blockNumber")).let { readsSeen.last() })
        // The wallet page's Disconnect does the same.
        connect()
        ok(call("wallet_switchEthereumChain", JSONArray().put(JSONObject().put("chainId", "0x2105"))))
        events.clear()
        assertTrue(runBlocking { provider.disconnect(site) })
        assertNull(grants.grants[site])
        assertEquals(listOf(Triple(site, "accountsChanged", "[]")), events)
        assertEquals("0x2105", ok(call("eth_chainId")))
    }

    @Test
    fun `a disconnect whose caller goes away mid-write still tells the site's pages`() {
        connect()
        events.clear()
        val gate = CompletableDeferred<Unit>()
        grants.revokeGate = gate
        runBlocking {
            // The connected site's page: Disconnect tapped, then Back while the write is in flight.
            val page = launch(start = CoroutineStart.UNDISPATCHED) { provider.disconnect(site) }
            grants.revokeStarted.await()
            val left = launch { page.cancelAndJoin() }
            gate.complete(Unit)
            left.join()
            assertTrue(page.isCancelled)
        }
        assertNull(grants.grants[site])
        assertEquals(listOf(Triple(site, "accountsChanged", "[]")), events)
        assertEquals("[]", ok(call("eth_accounts")).toString())
    }

    @Test
    fun `removing the wallet disconnects every site, so the same phrase imported again doesn't reconnect them`() {
        val other = "https://other.example"
        connect()
        answer = { EthAnswer.Approved(main) }
        ok(call("eth_requestAccounts", origin = other))
        ok(call("wallet_switchEthereumChain", JSONArray().put(JSONObject().put("chainId", "0x1")), origin = other))
        events.clear()
        assertTrue(runBlocking { provider.disconnectAll() })
        assertTrue(grants.grants.isEmpty())
        assertEquals(setOf(Triple(site, "accountsChanged", "[]"), Triple(other, "accountsChanged", "[]")), events.toSet())
        // Same accounts back (the phrase imported again): nothing is shared until asked.
        assertEquals("[]", ok(call("eth_accounts")).toString())
        assertEquals(4100, code(call("personal_sign", JSONArray().put("hi").put(main.address))))
        assertEquals("0x1", ok(call("eth_chainId", origin = other)))
    }

    @Test
    fun `both review screens say when a send replaces one the user stopped tracking`() {
        connect()
        answer = { EthAnswer.Rejected }
        call("eth_sendTransaction", tx("to" to second.address))
        // The sheet's quote, as the Send page would get it from the same prepare.
        val quote = (asks.single() as EthAsk.SendTransaction).quote
        val trust = trustLabel(quote.nonceTrust)
        assertEquals(trust, nonceDetail(quote))
        val replacing = quote.copy(replaces = "0x" + "aa".repeat(32))
        assertEquals(
            "$trust · replaces the send you stopped tracking (0x${"aa".repeat(32)}), at a higher fee: " +
                "only one of the two can go through",
            nonceDetail(replacing),
        )
    }

    // ---- Auto-approve rules (#112) ----

    private val token = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"
    private val otherToken = "0x6B175474E89094C44Da98b954EedeAC495271d0F"
    private val transferData = "0xa9059cbb" + "00".repeat(64)
    private val approveData = "0x095ea7b3" + "00".repeat(64)
    private fun sent(n: Int) = EthereumProvider.Submitted.Sent("0x" + "%064x".format(n))

    /** Connect, then send [transferData] to [token] once with the sheet's switch turned on. */
    private fun grantTransferRule() {
        connect()
        answer = { EthAnswer.Approved(alwaysApprove = true) }
        sends.outcomes += sent(1)
        ok(call("eth_sendTransaction", tx("to" to token, "data" to transferData)))
        asks.clear()
        answer = { error("no sheet expected") }
    }

    @Test
    fun `a send's sheet offers the rule for its site, contract, function and chain, and turning it on grants it`() {
        connect()
        answer = { EthAnswer.Approved() }
        sends.outcomes += sent(1)
        ok(call("eth_sendTransaction", tx("to" to token, "data" to transferData)))
        val offered = (asks.single() as EthAsk.SendTransaction)
        assertEquals(AutoApproveRule(site, token.lowercase(), "0xa9059cbb", 100), offered.autoApprove)
        assertFalse(offered.ruled)
        // Approved without the switch: nothing granted, the next one asks too.
        assertTrue(rules.rules.isEmpty())
        grantTransferRule()
        assertEquals(setOf("$site|${token.lowercase()}|0xa9059cbb|100"), rules.rules)
    }

    @Test
    fun `a rule turned on with a send that didn't go out is not written`() {
        connect()
        answer = { EthAnswer.Approved(alwaysApprove = true) }
        sends.outcomes += EthereumProvider.Submitted.Busy
        assertEquals(-32603, code(call("eth_sendTransaction", tx("to" to token, "data" to transferData))))
        sends.outcomes += EthereumProvider.Submitted.Failed("The wallet locked before the transaction was signed.", null)
        assertEquals(-32603, code(call("eth_sendTransaction", tx("to" to token, "data" to transferData))))
        repeat(3) { sends.outcomes += EthereumProvider.Submitted.Stale }
        assertEquals(-32603, code(call("eth_sendTransaction", tx("to" to token, "data" to transferData))))
        assertTrue(rules.rules.isEmpty())
        // So the next one asks again.
        asks.clear()
        answer = { EthAnswer.Rejected }
        assertEquals(4001, code(call("eth_sendTransaction", tx("to" to token, "data" to transferData))))
        assertFalse((asks.single() as EthAsk.SendTransaction).ruled)
    }

    @Test
    fun `after a reprice, the switch of the sheet confirmed last decides`() {
        connect()
        var n = 0
        answer = { EthAnswer.Approved(alwaysApprove = n++ == 0) }
        sends.outcomes += EthereumProvider.Submitted.Stale
        sends.outcomes += sent(1)
        ok(call("eth_sendTransaction", tx("to" to token, "data" to transferData)))
        assertTrue(rules.rules.isEmpty())
        n = 0
        answer = { EthAnswer.Approved(alwaysApprove = n++ == 1) }
        sends.outcomes += EthereumProvider.Submitted.Stale
        sends.outcomes += sent(2)
        ok(call("eth_sendTransaction", tx("to" to token, "data" to transferData)))
        assertEquals(1, rules.rules.size)
    }

    @Test
    fun `a call a rule covers goes out without a sheet while the wallet is unlocked`() {
        grantTransferRule()
        sends.outcomes += sent(2)
        assertEquals(sent(2).hash, ok(call("eth_sendTransaction", tx("from" to main.address, "to" to token.lowercase(), "data" to transferData))))
        assertTrue(asks.isEmpty())
        // Still the wallet's own send flow, priced as any other.
        assertEquals(site, sends.prepared.last().dapp!!.origin)
    }

    @Test
    fun `a rule covers nothing wider - another contract, function, chain, site, or a call that sends funds asks`() {
        grantTransferRule()
        answer = { EthAnswer.Rejected }
        assertEquals(4001, code(call("eth_sendTransaction", tx("to" to otherToken, "data" to transferData))))
        assertEquals(4001, code(call("eth_sendTransaction", tx("to" to token, "data" to approveData))))
        assertEquals(4001, code(call("eth_sendTransaction", tx("to" to token, "data" to transferData, "value" to "0x1"))))
        // A plain send to the same address is never covered, and offers no rule.
        assertEquals(4001, code(call("eth_sendTransaction", tx("to" to token))))
        assertNull((asks.last() as EthAsk.SendTransaction).autoApprove)
        // A value-bearing call offers none either.
        assertNull((asks[2] as EthAsk.SendTransaction).autoApprove)
        // Another site, connected to the same account, sending the same call.
        val other = "https://evil.example"
        answer = { EthAnswer.Approved(main) }
        ok(call("eth_requestAccounts", origin = other))
        answer = { EthAnswer.Rejected }
        assertEquals(4001, code(call("eth_sendTransaction", tx("to" to token, "data" to transferData), origin = other)))
        // The same site after switching chains.
        answer = { EthAnswer.Approved() }
        ok(call("wallet_switchEthereumChain", JSONArray().put(JSONObject().put("chainId", "0x1"))))
        answer = { EthAnswer.Rejected }
        assertEquals(4001, code(call("eth_sendTransaction", tx("to" to token, "data" to transferData))))
        assertEquals(1L, (asks.last() as EthAsk.SendTransaction).autoApprove!!.chainId)
        assertEquals(6, asks.count { it is EthAsk.SendTransaction })
        assertTrue(sends.outcomes.isEmpty())
    }

    @Test
    fun `a covered call at a fee above what one RPC's word may set asks, and says why (#233)`() {
        grantTransferRule()
        val gwei = BigInteger.valueOf(1_000_000_000)
        // A tip past the cap (only a trusted base fee lets one through), or a high legacy price.
        for (high in listOf(
            EthTransaction.Fees.Eip1559(BigInteger.valueOf(200) * gwei, BigInteger.valueOf(60) * gwei),
            EthTransaction.Fees.Legacy(BigInteger.valueOf(5_000) * gwei),
        )) {
            sends.fees = high
            asks.clear()
            answer = { EthAnswer.Rejected }
            assertEquals(4001, code(call("eth_sendTransaction", tx("to" to token, "data" to transferData))))
            val sheet = asks.single() as EthAsk.SendTransaction
            assertTrue(sheet.ruled)
        }
        val high = autoApproveRuledNote(replaces = false, highFee = true, locked = false)
        assertTrue(high.contains("network fee is higher"))
        assertFalse(high.contains("locked"))
        // Locked as well: both reasons, so the unlock prompt on confirm isn't unexplained (R1-M1).
        val both = autoApproveRuledNote(replaces = false, highFee = true, locked = true)
        assertTrue(both, both.contains("network fee is higher") && both.contains("the wallet is locked"))
        // At the cap it still goes out silently.
        sends.fees = EthTransaction.Fees.Eip1559(BigInteger.valueOf(10) * gwei, BigInteger.valueOf(5) * gwei)
        asks.clear()
        answer = { error("no sheet expected") }
        sends.outcomes += sent(3)
        assertEquals(sent(3).hash, ok(call("eth_sendTransaction", tx("to" to token, "data" to transferData))))
        assertTrue(asks.isEmpty())
    }

    @Test
    fun `a covered call still asks while the wallet is locked`() {
        grantTransferRule()
        wallet.open = false
        answer = { EthAnswer.Approved() }
        sends.outcomes += sent(2)
        ok(call("eth_sendTransaction", tx("to" to token, "data" to transferData)))
        val locked = asks.single() as EthAsk.SendTransaction
        assertTrue(locked.ruled)
        assertNull(locked.quote.replaces)
        // Confirming there doesn't grant it a second time.
        assertEquals(1, rules.rules.size)
        assertTrue(autoApproveRuledNote(replaces = false).contains("the wallet is locked"))
        assertTrue(autoApproveRuledNote(replaces = true).contains("a send you stopped tracking"))
    }

    @Test
    fun `a send replacing one the user stopped tracking is always asked`() {
        grantTransferRule()
        val replacing = object : EthereumProvider.Sends by sends {
            override suspend fun prepare(request: SendRequest) = sends.prepare(request).copy(replaces = "0x" + "aa".repeat(32))
        }
        val p = EthereumProvider(grants, wallet, { chainList!! }, { _, _, _, _ -> null }, replacing, rules)
        sends.outcomes += sent(2)
        runBlocking {
            p.request(site, "eth_sendTransaction", tx("to" to token, "data" to transferData)) { ask ->
                asks += ask
                EthAnswer.Approved()
            }
        }
        assertTrue((asks.single() as EthAsk.SendTransaction).ruled)
    }

    @Test
    fun `while the rules can't be read a covered call asks`() {
        grantTransferRule()
        rules.unreadable = true
        answer = { EthAnswer.Rejected }
        assertEquals(4001, code(call("eth_sendTransaction", tx("to" to token, "data" to transferData))))
        assertFalse((asks.single() as EthAsk.SendTransaction).ruled)
    }

    @Test
    fun `a covered call is refused while another send is going out, and priced again if its quote went stale`() {
        grantTransferRule()
        sends.busy = true
        assertEquals(-32603, code(call("eth_sendTransaction", tx("to" to token, "data" to transferData))))
        sends.busy = false
        val before = sends.prepared.size
        sends.outcomes += EthereumProvider.Submitted.Stale
        sends.outcomes += sent(3)
        assertEquals(sent(3).hash, ok(call("eth_sendTransaction", tx("to" to token, "data" to transferData))))
        assertEquals(before + 2, sends.prepared.size)
        assertTrue(asks.isEmpty())
    }

    @Test
    fun `disconnecting a site drops its rules, so connecting again asks`() {
        grantTransferRule()
        answer = { EthAnswer.Approved(main) }
        ok(call("eth_requestAccounts", origin = "https://other.example"))
        rules.rules += "https://other.example|${token.lowercase()}|0xa9059cbb|100"
        assertTrue(runBlocking { provider.disconnect(site) })
        assertEquals(setOf("https://other.example|${token.lowercase()}|0xa9059cbb|100"), rules.rules)
        connect()
        answer = { EthAnswer.Rejected }
        assertEquals(4001, code(call("eth_sendTransaction", tx("to" to token, "data" to transferData))))
    }

    @Test
    fun `a Ledger account's send never offers or uses a rule - the Ledger asks for each one`() {
        val ledger = WalletAccount(-1, "Ledger", "0xcccccccccccccccccccccccccccccccccccccccc", baby.freedom.mobile.wallet.ledger.LedgerKey("44'/60'/0'/0/0", "AA:BB:CC:DD:EE:FF", "Nano X"))
        wallet.list = listOf(main, second, ledger)
        connect(ledger)
        // Even with a rule for this very call left in the store, the sheet is shown, with no switch.
        rules.rules += "$site|${token.lowercase()}|0xa9059cbb|100"
        answer = { EthAnswer.Approved(alwaysApprove = true) }
        sends.outcomes += sent(1)
        ok(call("eth_sendTransaction", tx("to" to token, "data" to transferData)))
        val sheet = asks.single() as EthAsk.SendTransaction
        assertNull(sheet.autoApprove)
        assertFalse(sheet.ruled)
        assertEquals(setOf("$site|${token.lowercase()}|0xa9059cbb|100"), rules.rules)
    }

    @Test
    fun `removing a Ledger account drops the rules of the sites connected with it`() {
        val ledger = WalletAccount(-1, "Ledger", "0xcccccccccccccccccccccccccccccccccccccccc", baby.freedom.mobile.wallet.ledger.LedgerKey("44'/60'/0'/0/0", "AA:BB:CC:DD:EE:FF", "Nano X"))
        wallet.list = listOf(main, second, ledger)
        connect(ledger)
        answer = { EthAnswer.Approved(main) }
        ok(call("eth_requestAccounts", origin = "https://other.example"))
        rules.rules += "$site|${token.lowercase()}|0xa9059cbb|100"
        rules.rules += "https://other.example|${token.lowercase()}|0xa9059cbb|100"
        // A rule store that can't be written: the site stays connected, to try again.
        rules.failWrites = true
        assertFalse(runBlocking { provider.accountRemoved(ledger.address) })
        assertEquals(ledger.address, grants.grants[site]?.account)
        rules.failWrites = false
        events.clear()
        assertTrue(runBlocking { provider.accountRemoved(ledger.address) })
        assertNull(grants.grants[site])
        assertEquals(setOf("https://other.example|${token.lowercase()}|0xa9059cbb|100"), rules.rules)
        assertEquals(listOf(Triple(site, "accountsChanged", "[]")), events)
    }

    @Test
    fun `a disconnect whose rules can't be removed leaves the site connected, to try again`() {
        grantTransferRule()
        rules.failWrites = true
        events.clear()
        assertFalse(runBlocking { provider.disconnect(site) })
        assertEquals(main.address, grants.grants[site]?.account)
        assertTrue(events.isEmpty())
        rules.failWrites = false
        assertTrue(runBlocking { provider.disconnect(site) })
        assertTrue(rules.rules.isEmpty())
        assertNull(grants.grants[site])
    }

    @Test
    fun `removing the wallet drops every rule`() {
        grantTransferRule()
        assertTrue(runBlocking { provider.disconnectAll() })
        assertTrue(rules.rules.isEmpty())
    }

    @Test
    fun `a rule turned on in a sheet the site disconnected under is not written, so reconnecting asks`() {
        connect()
        sends.outcomes += sent(1)
        runBlocking {
            provider.request(site, "eth_sendTransaction", tx("to" to token, "data" to transferData)) { ask ->
                asks += ask
                // The page's own wallet_revokePermissions, while the sheet is up.
                assertTrue(provider.disconnect(site))
                EthAnswer.Approved(alwaysApprove = true)
            }
        }
        assertTrue(rules.rules.isEmpty())
        connect()
        answer = { EthAnswer.Rejected }
        assertEquals(4001, code(call("eth_sendTransaction", tx("to" to token, "data" to transferData))))
        assertEquals(1, asks.size)
    }

    @Test
    fun `a rule turned on in a sheet the site reconnected under with another account is not written`() {
        connect()
        sends.outcomes += sent(1)
        runBlocking {
            provider.request(site, "eth_sendTransaction", tx("to" to token, "data" to transferData)) { ask ->
                asks += ask
                assertTrue(provider.disconnect(site))
                assertEquals(
                    second.address,
                    (provider.request(site, "eth_requestAccounts", JSONArray()) { EthAnswer.Approved(second) } as EthereumProvider.Reply.Ok)
                        .value.let { (it as JSONArray).getString(0) },
                )
                EthAnswer.Approved(alwaysApprove = true)
            }
        }
        assertTrue(rules.rules.isEmpty())
    }

    @Test
    fun `connecting drops rules an earlier connection left, so they never cover another account`() {
        grantTransferRule()
        // Account 1 was the site's; the wallet's list no longer has it, so the site shows as unconnected.
        wallet.list = listOf(second)
        answer = { EthAnswer.Approved(second) }
        assertEquals(second.address, (ok(call("eth_requestAccounts")) as JSONArray).getString(0))
        assertTrue(rules.rules.isEmpty())
        answer = { EthAnswer.Rejected }
        assertEquals(4001, code(call("eth_sendTransaction", tx("to" to token, "data" to transferData))))
    }

    @Test
    fun `a connect whose old rules can't be dropped is not saved`() {
        grantTransferRule()
        wallet.list = listOf(second)
        rules.failWrites = true
        answer = { EthAnswer.Approved(second) }
        assertEquals(-32603, code(call("eth_requestAccounts")))
        assertEquals(main.address, grants.grants[site]?.account)
    }

    @Test
    fun `removing the wallet disconnects every site even if the rules can't be cleared`() {
        grantTransferRule()
        rules.failWrites = true
        assertFalse(runBlocking { provider.disconnectAll() })
        assertTrue(grants.grants.isEmpty())
        assertEquals(listOf(Triple(site, "accountsChanged", "[]")), events)
    }

    @Test
    fun `the approval copy names each action`() {
        assertEquals("Connect", ethApprovalCopy(EthAsk.Connect(site, BuiltInChains.GNOSIS)).approve)
        assertEquals("Switch", ethApprovalCopy(EthAsk.SwitchChain(site, BuiltInChains.GNOSIS, BuiltInChains.BASE)).approve)
    }

    @Test
    fun `the ethereum sheet takes turns with the other prompts`() {
        assertEquals(PromptTurn.Ethereum, modalPromptTurn(false, false, false, false, ethereumWaiting = true))
        assertEquals(PromptTurn.SitePermission, modalPromptTurn(true, false, false, false, ethereumWaiting = true))
        assertEquals(PromptTurn.Radicle, modalPromptTurn(false, false, false, false, radicleWaiting = true, ethereumWaiting = true))
        assertEquals(PromptTurn.Ethereum, modalPromptTurn(true, true, false, false, ethereumWaiting = true, ethereumHasTurn = true))
        assertEquals(PromptTurn.Ethereum, modalPromptTurn(false, true, false, false, ethereumWaiting = true))
        assertEquals(PromptTurn.None, modalPromptTurn(false, false, false, true, ethereumWaiting = true, ethereumHasTurn = true))
    }

    @Test
    fun `requests off the channel are parsed strictly`() {
        assertEquals(EthereumRequest(1, "eth_chainId", JSONArray()).toString(), parseEthereumRequest("""{"id":1,"method":"eth_chainId"}""").toString())
        assertEquals(1, parseEthereumRequest("""{"id":2,"method":"x","params":{"a":1}}""")!!.params.length())
        assertNull(parseEthereumRequest("""{"id":"1","method":"eth_chainId"}"""))
        assertNull(parseEthereumRequest("""{"id":1,"method":""}"""))
        assertNull(parseEthereumRequest("""{"id":1,"method":"x","params":"nope"}"""))
        assertNull(parseEthereumRequest("[".repeat(100_000)))
    }
}
