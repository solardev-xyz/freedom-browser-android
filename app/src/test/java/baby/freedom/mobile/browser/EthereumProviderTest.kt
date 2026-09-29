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
import baby.freedom.mobile.wallet.WalletAccount
import java.math.BigInteger
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
        override suspend fun grantFor(origin: String) = grants[origin]
        override suspend fun grant(origin: String, account: String, chainId: Long): Boolean {
            grants[origin] = EthereumProvider.Grant(account, chainId)
            return true
        }
        override suspend fun setChain(origin: String, chainId: Long): Boolean {
            val g = grants[origin] ?: return false
            grants[origin] = g.copy(chainId = chainId)
            return true
        }
        override suspend fun revoke(origin: String) = grants.remove(origin).let { true }
        override suspend fun all(): Map<String, EthereumProvider.Grant> = HashMap(grants)
        override suspend fun clear() = grants.clear().let { true }
        override suspend fun addChain(chain: Chain): Boolean {
            added += chain
            return true
        }
    }

    private inner class FakeWallet : EthereumProvider.Wallet {
        var list: List<WalletAccount>? = listOf(main, second)
        var activity = 0
        override suspend fun accounts() = list
        override fun noteActivity() {
            activity++
        }
        override fun sign(account: WalletAccount, digest: ByteArray): String {
            check(account == main) { "only the cow key here" }
            return MessageSigning.sign(cow, account.address, digest)
        }
    }

    private class FakeSends : EthereumProvider.Sends {
        val prepared = mutableListOf<SendRequest>()
        val outcomes = ArrayDeque<EthereumProvider.Submitted>()
        var prepareError: String? = null
        var busy = false
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
                fees = EthTransaction.Fees.Eip1559(BigInteger.valueOf(2_000_000_000), BigInteger.ONE),
            )
            val trust = ChainTrust(ChainTrust.Level.VERIFIED, ChainSource.QUORUM, emptyList(), emptyList(), emptyList(), 3, 2, null)
            return SendQuote(request, tx, BigInteger.TEN.pow(18), null, 0, trust)
        }
        override suspend fun submit(quote: SendQuote) = outcomes.removeFirst()
    }

    private val sepolia = Chain(id = 11155111, name = "Sepolia", symbol = "ETH", rpcUrls = listOf("https://rpc.sepolia.org"), isTestnet = true)
    private val grants = FakeGrants()
    private val wallet = FakeWallet()
    private val sends = FakeSends()
    private val readsSeen = mutableListOf<String>()
    private var readAnswer: (String) -> Any? = { "0x1" }
    private val events = mutableListOf<Triple<String, String, String>>()
    private val asks = mutableListOf<EthAsk>()
    private var answer: (EthAsk) -> EthAnswer = { EthAnswer.Rejected }

    private val provider = EthereumProvider(
        grants = grants,
        wallet = wallet,
        chains = { BuiltInChains.ALL + sepolia },
        reads = { chainId, method, params, origin ->
            readsSeen += "$chainId $method $params $origin"
            readAnswer(method)
        },
        sends = sends,
    ).also { p -> p.events = EthereumProvider.Events { o, e, d -> events += Triple(o, e, d.toString()) } }

    private fun call(method: String, params: JSONArray = JSONArray(), origin: String = site) = runBlocking {
        provider.request(origin, method, params) { ask ->
            asks += ask
            answer(ask)
        }
    }

    private fun ok(r: EthereumProvider.Reply): Any = (r as? EthereumProvider.Reply.Ok)?.value ?: error("not ok: $r")
    private fun code(r: EthereumProvider.Reply): Int = (r as? EthereumProvider.Reply.Err)?.code ?: error("not an error: $r")

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
        assertEquals("Sign in\nNonce: 1 ✓ 🐄", EthereumProvider.readableUtf8("Sign in\nNonce: 1 ✓ 🐄".toByteArray()))
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
        var start = System.nanoTime()
        ok(call("eth_signTypedData_v4", JSONArray().put(main.address).put(payload(2000))))
        assertTrue("took ${(System.nanoTime() - start) / 1_000_000} ms", System.nanoTime() - start < 3_000_000_000L)
        asks.clear()
        start = System.nanoTime()
        val err = call("eth_signTypedData_v4", JSONArray().put(main.address).put(payload(20_000))) as EthereumProvider.Reply.Err
        assertTrue("took ${(System.nanoTime() - start) / 1_000_000} ms", System.nanoTime() - start < 3_000_000_000L)
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
        val p = EthereumProvider(grants, wallet, { BuiltInChains.ALL }, { _, _, _, _ -> null }, sends, compute)
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
