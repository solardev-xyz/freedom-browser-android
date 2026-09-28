package baby.freedom.mobile.chains

import baby.freedom.mobile.browser.Icu4jUts46
import baby.freedom.mobile.browser.WhatwgHost
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ChainlistTest {
    @Before
    fun icu() {
        WhatwgHost.uts46 = Icu4jUts46
    }

    /** A slice of the real rpcs.json shape, with its awkward cases. */
    private val catalog = """
        [
          {"name": "Ethereum Mainnet", "shortName": "eth", "chainId": 1, "isTestnet": false, "tvl": 1000,
           "nativeCurrency": {"name": "Ether", "symbol": "ETH", "decimals": 18},
           "rpc": [
             {"url": "https://rpc.hostdefi.com/api/rpc/ethereum", "tracking": "limited"},
             {"url": "https://tracker.example/eth", "tracking": "yes"},
             {"url": "https://eth.drpc.org", "tracking": "none"},
             {"url": "https://mainnet.infura.io/v3/${'$'}{INFURA_API_KEY}"},
             {"url": "wss://eth.drpc.org", "tracking": "none"},
             {"url": "https://ethereum.publicnode.com"},
             {"url": "https://eth-mainnet.rpcfast.com?api_key=xbhWBI1Wkguk8SNM", "tracking": "none"},
             {"url": "https://eth.example.net/rpc?Token=abc&x=1"},
             {"url": "https://eth.example.net/rpc?chain=1"},
             "https://legacy-string.example",
             {"url": "https://eth.drpc.org", "tracking": "none"},
             {"url": "http://cleartext.example"},
             {"url": "https://user:pw@creds.example"}
           ],
           "explorers": [{"name": "bad", "url": "http://insecure.example"}, {"name": "etherscan", "url": "https://etherscan.io/"}]},
          {"name": "Base", "shortName": "base", "chainId": 8453, "tvl": 900,
           "nativeCurrency": {"name": "Ether", "symbol": "ETH", "decimals": 18},
           "rpc": [{"url": "https://mainnet.base.org"}]},
          {"name": "Hardhat", "shortName": "hh", "chainId": 31337, "isTestnet": true,
           "nativeCurrency": {"name": "Go", "symbol": "GO", "decimals": 18},
           "rpc": [{"url": "http://localhost:8545"}, {"url": "http://127.0.0.1:8545"}]},
          {"name": "Polygon Mainnet", "shortName": "pol", "chainId": 137, "tvl": 800,
           "nativeCurrency": {"name": "POL", "symbol": "POL", "decimals": 18},
           "rpc": [{"url": "https://polygon.drpc.org", "tracking": "none"}]},
          {"name": "Polygon Amoy", "shortName": "polygonamoy", "chainId": 80002, "isTestnet": true,
           "nativeCurrency": {"name": "POL", "symbol": "POL", "decimals": 18},
           "rpc": [{"url": "https://rpc-amoy.polygon.technology", "tracking": null}]},
          {"name": "", "chainId": 5, "nativeCurrency": {"name": "x", "symbol": "X", "decimals": 18}, "rpc": []},
          {"name": "No currency", "chainId": 6, "rpc": []},
          {"name": "Bad decimals", "chainId": 7, "nativeCurrency": {"name": "x", "symbol": "X", "decimals": 99}, "rpc": []},
          {"name": "Bad id", "chainId": "not-a-number", "nativeCurrency": {"name": "x", "symbol": "X", "decimals": 18}},
          {"name": "Zero id", "chainId": 0, "nativeCurrency": {"name": "x", "symbol": "X", "decimals": 18}},
          "garbage",
          {"name": "Pulse 137 fork", "shortName": "p137", "chainId": 1370, "tvl": 5,
           "nativeCurrency": {"name": "", "symbol": "PLS", "decimals": 18},
           "rpc": [{"url": "https://rpc.p137.example", "tracking": "None"}]}
        ]
    """.trimIndent()

    private val entries by lazy { Chainlist.parse(catalog)!! }

    private fun entry(id: Long) = entries.single { it.id == id }

    @Test
    fun keepsOnlyKeyFreeTrackingFreePublicRpcs() {
        assertEquals(
            listOf(
                "https://eth.drpc.org",
                "https://ethereum.publicnode.com",
                "https://eth.example.net/rpc?chain=1",
                "https://legacy-string.example",
            ),
            entry(1).rpcUrls,
        )
        assertEquals("https://etherscan.io", entry(1).explorerUrl)
        assertEquals("Ether", entry(1).currencyName)
        // A null tracking claim is no claim; "None" is "none".
        assertEquals(listOf("https://rpc-amoy.polygon.technology"), entry(80002).rpcUrls)
        assertEquals(listOf("https://rpc.p137.example"), entry(1370).rpcUrls)
    }

    @Test
    fun catalogNeverPointsAtTheDevice() {
        assertEquals(emptyList<String>(), entry(31337).rpcUrls)
        assertTrue(entry(31337).isTestnet)
    }

    @Test
    fun skipsMalformedEntriesNotTheWholeList() {
        assertEquals(listOf(1L, 8453L, 31337L, 137L, 80002L, 1370L), entries.map { it.id })
        assertEquals("PLS", entry(1370).currencyName)
    }

    @Test
    fun notAListIsNull() {
        assertNull(Chainlist.parse("<html>502 Bad Gateway</html>"))
        assertNull(Chainlist.parse("{\"chains\": []}"))
        assertEquals(emptyList<Chainlist.Entry>(), Chainlist.parse("[]"))
    }

    @Test
    fun usableRpcShapes() {
        assertEquals("https://a.example", Chainlist.usableRpc("https://a.example"))
        assertEquals("https://a.example", Chainlist.usableRpc(JSONObject("""{"url":"https://a.example","tracking":"none"}""")))
        assertNull(Chainlist.usableRpc(JSONObject("""{"url":"https://a.example","tracking":"limited"}""")))
        assertNull(Chainlist.usableRpc(JSONObject("""{"tracking":"none"}""")))
        assertNull(Chainlist.usableRpc(JSONObject("""{"url":"https://a.example/${'$'}KEY"}""")))
        assertNull(Chainlist.usableRpc(JSONObject("""{"url":"https://192.168.0.2"}""")))
        assertNull(Chainlist.usableRpc(42))
    }

    @Test
    fun keysInThePathAreDropped() {
        // Real keyed entries from the live rpcs.json, all marked tracking "none" or unmarked.
        for (url in listOf(
            "https://eth-sepolia.g.alchemy.com/v2/WddzdzI2o9S3COdT73d5w6AIogbKq4X-",
            "https://ropsten.infura.io/v3/9aa3d95b3bc440fa88ea12eaa4456161",
            "https://rinkeby.infura.io/3/9aa3d95b3bc440fa88ea12eaa4456161",
            "https://go.getblock.io/d7094dbd80ab474ba7042603fe912332",
            "https://opbnb-mainnet.nodereal.io/v1/64a9df0874fb4a93b9d0a3849de012d3",
            "https://rpc.ankr.com/somnia_testnet/6e3fd81558cf77b928b06b38e9409b4677b637118114e83364486294d5ff4811",
            "https://node.histori.xyz/kava-mainnet/8ry9f6t9dct1se2hlagxnd9n2a",
            "https://api.blockeden.xyz/metis/67nCBdZQSH9z3YqDDjdm",
            "https://shido-mainnet-archive-lb-nw5es9.zeeve.net/USjg7xqUmCZ4wCsqEOOE/rpc",
            "https://okc-mainnet.gateway.pokt.network/v1/lb/6275309bea1b320039c893ff",
            "https://api-base-mainnet-archive.n.dwellir.com/2ccf18bf-2916-4198-8856-42172854353c",
            "https://eth.nodebridge.xyz/assetchain/exec/b2da3d33-5708-4f61-8d1e-2c677124c35a",
            "https://xcap-mainnet.relay.xcap.network/znzvh2ueyvm2yts5fv5gnul395jbkfb2/rpc1",
            "https://sparkling-autumn-dinghy.worldchain-mainnet.quiknode.pro",
            // Alchemy-shaped keys whose separators split them into short pieces, or with no digits.
            "https://eth-sepolia.g.alchemy.com/v2/Ab3dEfGh1jKlMn_oPqRsTuVwXy-Z0123456",
            "https://eth-sepolia.g.alchemy.com/v2/kQxRtYpLmNbVcZsWdFgHjKaPoIuYtReW",
            // A key doesn't pass for an Avalanche blockchain ID by sitting where one would.
            "https://rpc.example.org/ext/bc/9aa3d95b3bc440fa88ea12eaa4456161/rpc",
            "https://rpc.example.org/ext/bc/WddzdzI2o9S3COdT73d5w6AIogbKq4X-/rpc",
            // …nor by being base58 of the right length with a wrong checksum.
            "https://rpc.gunzchain.io/ext/bc/2M47TxWHGnhNtq6pM5zPXdATBtuqubxn5EPFgFmEawCQr9WFMM/rpc",
        )) {
            assertNull(url, Chainlist.usableRpc(JSONObject(mapOf("url" to url, "tracking" to "none"))))
        }
        // Public endpoints whose paths only name a chain, a version or a public ID.
        for (url in listOf(
            "https://shape-mainnet.g.alchemy.com/public",
            "https://rpc.ankr.com/flare",
            "https://polygon-zkevm.drpc.org",
            "https://node.histori.xyz/polygon-zkevm-mainnet",
            "https://services.tanssi-mainnet.network/tanssi-2002",
            "https://filecoin-mainnet.chainstacklabs.com/rpc/v1",
            "https://public.1rpc.io/zksync2-era",
            "https://bombchain-testnet.ankr.com/bas_full_rpc_1",
            "https://ethereum.public.blockpi.network/v1/rpc/public",
            // An Avalanche blockchain ID is public, not a key.
            "https://rpc.gunzchain.io/ext/bc/2M47TxWHGnhNtq6pM5zPXdATBtuqubxn5EPFgFmEawCQr9WFML/rpc",
            "https://rpc.apertum.io/ext/bc/YDJ1r9RMkewATmA7B35q1bdV18aywzmdiXwd9zGBq3uQjsCnn/rpc",
            "https://subnets.avax.network/defi-kingdoms/mainnet/rpc",
            "https://mainnet.skalenodes.com/v1/honorable-steel-rasalhague",
            "https://lb.routeme.sh/rpc/2716446429837000",
            "https://rpc.ankr.com/blast_testnet_sepolia",
            // Host labels aren't paths: a long name with digits is just a name.
            "https://node1.cratd2csmartchain.io",
        )) {
            assertEquals(url, url, Chainlist.usableRpc(url))
        }
    }

    @Test
    fun keysInTheQueryOrHostAreDropped() {
        for (url in listOf(
            // Real keyed entries from the live rpcs.json.
            "https://api-gateway.skymavis.com/rpc?apikey=9aqYLBbxSC6LROynQJBvKkEIsioqwHmr",
            "https://rpc.chain49.com/ethereum?api_key=14d1a8b86d8a4b4797938332394203dc",
            // Keys under names the old fixed list didn't know.
            "https://lb.drpc.org/ogrpc?network=sepolia&dkey=Ak80gSCleU8Hkq2Jp0fX4bRFsJvGnqQR8KqPrhesZmuN",
            "https://rpc.example.org/?x-api-key=abc",
            "https://rpc.example.org/?projectId=abc",
            // …or under any name at all, judged by the value alone.
            "https://rpc.example.org/?n=sepolia&whatever=9aa3d95b3bc440fa88ea12eaa4456161",
            "https://rpc.example.org/?q=WddzdzI2o9S3COdT73d5w6AIogbKq4X-",
            // A bare key with no `=` is a parameter name, judged as a key too.
            "https://rpc.example.org/?9aa3d95b3bc440fa88ea12eaa4456161",
            "https://rpc.example.org/?chain=1&WddzdzI2o9S3COdT73d5w6AIogbKq4X-",
            // A credential name hidden behind percent-encoding.
            "https://rpc.example.org/?api%5Fkey=abc",
            "https://rpc.example.org/?%74oken=abc",
            // Subdomain-keyed providers.
            "https://9aa3d95b3bc440fa88ea12eaa4456161.eth.rpc.rivet.cloud/",
            "https://2ccf18bf-2916-4198-8856-42172854353c.rpc.example.org/",
        )) {
            assertNull(url, Chainlist.usableRpc(JSONObject(mapOf("url" to url, "tracking" to "none"))))
        }
        // Public query parameters and generated-looking public host labels from the live catalog.
        for (url in listOf(
            "https://andromeda.metis.io/?owner=1088",
            "https://rpc.example.org/?mainnet",
            "https://rpc.example.org/?chain%5Fid=1",
            "https://api.uniblock.dev/uni/v1/json-rpc?chainId=151",
            "https://rpc-astra-9on2f72wzn.t.conduit.xyz",
            "https://fraa-flashbox-2800-rpc.a.stagenet.tanssi.network",
            "https://dchain-2716446429837000-1.jsonrpc.sagarpc.io",
            "https://studiochain-cf4a1621.calderachain.xyz",
            "https://tsub360890-eth-rpc.thetatoken.org/rpc",
        )) {
            assertEquals(url, url, Chainlist.usableRpc(url))
        }
    }

    /**
     * A deeply nested body is "not a list". (This org.json caps nesting
     * itself; Android's recurses until the stack overflows — see
     * ChainlistParseTest on the device.)
     */
    @Test
    fun deeplyNestedBodyIsNull() {
        assertNull(Chainlist.parse("[".repeat(200_000)))
        assertNull(Chainlist.parse("[".repeat(200_000) + "]".repeat(200_000)))
    }

    /** Random keys of the shapes providers hand out, well past any length a name could reach. */
    @Test
    fun randomKeysAreCaught() {
        val random = java.util.Random(187)
        fun key(alphabet: String, length: Int) = String(CharArray(length) { alphabet[random.nextInt(alphabet.length)] })
        val base64url = ('A'..'Z').joinToString("") + ('a'..'z').joinToString("") + ('0'..'9').joinToString("") + "-_"
        val base36 = ('a'..'z').joinToString("") + ('0'..'9').joinToString("")
        for ((alphabet, length) in listOf(base64url to 32, "0123456789abcdef" to 32, base36 to 26, base36 to 32)) {
            val missed = (1..20_000).count { !Chainlist.looksLikeKey(key(alphabet, length)) }
            // Only keys with (almost) no digits in one case can pass for a name.
            assertTrue("$alphabet × $length: $missed missed", missed <= 5)
        }
    }

    @Test
    fun cb58() {
        assertTrue(Chainlist.isCb58Id("2M47TxWHGnhNtq6pM5zPXdATBtuqubxn5EPFgFmEawCQr9WFML"))
        assertTrue(Chainlist.isCb58Id("ZG7cT4B1u3y7piZ9CzfejnTKnNAoehcifbJWUwBqgyD3RuEqK"))
        assertFalse(Chainlist.isCb58Id("2M47TxWHGnhNtq6pM5zPXdATBtuqubxn5EPFgFmEawCQr9WFMM"))
        assertFalse(Chainlist.isCb58Id("0M47TxWHGnhNtq6pM5zPXdATBtuqubxn5EPFgFmEawCQr9WFML"))
        assertFalse(Chainlist.isCb58Id("zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz"))
    }

    @Test
    fun duplicateChainIdsKeepTheFirst() {
        val list = Chainlist.parse(
            """[
              {"name": "First", "chainId": 42, "nativeCurrency": {"name": "A", "symbol": "A", "decimals": 18}},
              {"name": "Second", "chainId": 42, "nativeCurrency": {"name": "B", "symbol": "B", "decimals": 18}},
              {"name": "Other", "chainId": 43, "nativeCurrency": {"name": "C", "symbol": "C", "decimals": 18}}
            ]""",
        )!!
        assertEquals(listOf("First", "Other"), list.map { it.name })
    }

    /**
     * The caller is released at once, not after the stalled read's 30 s
     * timeout. (That the connection itself is torn down too is checked on
     * the device, in `ChainlistDownloadTest`: the desktop JDK's
     * `HttpURLConnection.disconnect()` waits for a blocked read, Android's
     * aborts it.)
     */
    @Test
    fun cancellingTheCallerReturnsAtOnce() = runBlocking {
        StallingServer().use { server ->
            val job = launch(Dispatchers.Default) { ChainlistService.download(server.url) }
            assertTrue(server.responded.await(5, TimeUnit.SECONDS))
            delay(200)
            job.cancel()
            withTimeout(1_000) { job.join() }
        }
    }

    @Test
    fun aCancelledDownloadDoesNotHoldUpTheNextCaller() = runBlocking {
        StallingServer().use { server ->
            var calls = 0
            val service = ChainlistService(File(dir, "rpcs.json"), {
                if (++calls == 1) ChainlistService.download(server.url) else catalog
            })
            val first = launch(Dispatchers.Default) { service.entries() }
            assertTrue(server.responded.await(5, TimeUnit.SECONDS))
            delay(200)
            first.cancel()
            // The next Add chain gets the catalog straight away, not after
            // the first download's 30 s read timeout.
            val list = withTimeout(2_000) { service.entries() }
            assertEquals(6, list.size)
            assertEquals(2, calls)
        }
    }

    @Test
    fun search() {
        // Empty: by TVL.
        assertEquals(listOf(1L, 8453L, 137L, 1370L), Chainlist.search(entries, "", limit = 4).map { it.id })
        // Name / short name, case-insensitive.
        assertEquals(listOf(137L, 80002L), Chainlist.search(entries, "POLYGON").map { it.id })
        assertEquals(listOf(80002L), Chainlist.search(entries, "amoy").map { it.id })
        // Exact chain ID first — decimal or hex — ahead of name hits.
        assertEquals(listOf(137L, 1370L), Chainlist.search(entries, "137").map { it.id })
        assertEquals(listOf(8453L), Chainlist.search(entries, "0x2105").map { it.id })
        assertEquals(emptyList<Long>(), Chainlist.search(entries, "nothing like it").map { it.id })
    }

    private lateinit var dir: File

    @Before
    fun tmp() {
        dir = Files.createTempDirectory("chainlist").toFile()
    }

    @After
    fun cleanup() {
        dir.deleteRecursively()
    }

    private class Net(var body: () -> String) {
        var calls = 0
        suspend fun fetch(): String {
            calls++
            return body()
        }
    }

    @Test
    fun cachesForADay() = runBlocking {
        val net = Net { catalog }
        var now = 1_700_000_000_000L
        val file = File(dir, "chainlist/rpcs.json")
        val service = ChainlistService(file, net::fetch) { now }
        assertEquals(6, service.entries().size)
        assertEquals(1, net.calls)
        assertTrue(file.isFile)

        now += Chainlist.CACHE_TTL_MS - 1000
        service.entries()
        assertEquals(1, net.calls)

        // A new process reads the fresh cache from disk.
        val again = ChainlistService(file, net::fetch) { now }
        assertEquals(6, again.entries().size)
        assertEquals(1, net.calls)

        // Stale: the old list comes back at once and a refresh runs behind it.
        now += 2000
        assertEquals(6, service.entries().size)
        service.refreshing!!.join()
        assertEquals(2, net.calls)
        service.entries()
        assertEquals(2, net.calls)
    }

    @Test
    fun aCacheFromTheFutureIsStale() = runBlocking {
        val net = Net { catalog }
        var now = 1_700_000_000_000L + 30 * Chainlist.CACHE_TTL_MS
        val file = File(dir, "rpcs.json")
        val service = ChainlistService(file, net::fetch) { now }
        service.entries()
        assertEquals(1, net.calls)

        // The clock was a month ahead when that was cached, and is now corrected.
        now -= 30 * Chainlist.CACHE_TTL_MS
        service.entries()
        service.refreshing!!.join()
        assertEquals(2, net.calls)
        // Nor does a new process trust the future-dated file on disk.
        file.setLastModified(now + 30 * Chainlist.CACHE_TTL_MS)
        val fresh = ChainlistService(file, net::fetch) { now }
        fresh.entries()
        fresh.refreshing!!.join()
        assertEquals(3, net.calls)
    }

    @Test
    fun staleCacheBeatsNoneWhenOffline() = runBlocking {
        val net = Net { catalog }
        var now = 1_700_000_000_000L
        val file = File(dir, "rpcs.json")
        ChainlistService(file, net::fetch) { now }.entries()

        now += 10 * Chainlist.CACHE_TTL_MS
        net.body = { throw IOException("offline") }
        val offline = ChainlistService(file, net::fetch) { now }
        assertEquals(6, offline.entries().size)
        offline.refreshing!!.join()
        assertEquals(2, net.calls)
        assertEquals(6, offline.entries().size)

        // An error page doesn't replace a good cache either.
        net.body = { "<html>oops</html>" }
        val errorPage = ChainlistService(file, net::fetch) { now }
        assertEquals(6, errorPage.entries().size)
        errorPage.refreshing!!.join()
        assertEquals(6, errorPage.entries().size)
        assertTrue(file.readText().startsWith("["))
    }

    /**
     * R5-F1: a stale cache is shown at once even when the refresh hangs
     * (a blackholed route can take minutes), later visits don't queue
     * another download behind it, and its result is picked up once it lands.
     */
    @Test
    fun staleCacheIsShownWithoutWaitingForTheRefresh() = runBlocking {
        var now = 1_700_000_000_000L
        val file = File(dir, "rpcs.json")
        ChainlistService(file, { catalog }) { now }.entries()
        file.writeText(catalog.replace("\"Ethereum Mainnet\"", "\"Old Name\""))
        file.setLastModified(now)

        now += 2 * Chainlist.CACHE_TTL_MS
        val network = CompletableDeferred<String>()
        var calls = 0
        val service = ChainlistService(file, { calls++; network.await() }) { now }
        val stale = withTimeout(1_000) { service.entries() }
        assertTrue(stale.any { it.name == "Old Name" })
        assertEquals(stale, withTimeout(1_000) { service.entries() })
        withTimeout(1_000) { while (calls == 0) delay(10) }
        assertEquals(1, calls)

        network.complete(catalog)
        service.refreshing!!.join()
        val fresh = service.entries()
        assertFalse(fresh.any { it.name == "Old Name" })
        assertEquals(1, calls)
    }

    /** A failed background refresh isn't retried on every visit. */
    @Test
    fun aFailedRefreshBacksOff() = runBlocking {
        var now = 1_700_000_000_000L
        val file = File(dir, "rpcs.json")
        val net = Net { catalog }
        ChainlistService(file, net::fetch) { now }.entries()

        now += 2 * Chainlist.CACHE_TTL_MS
        net.body = { throw IOException("offline") }
        val service = ChainlistService(file, net::fetch) { now }
        service.entries()
        service.refreshing!!.join()
        assertEquals(2, net.calls)

        now += ChainlistService.RETRY_AFTER_MS - 1000
        assertEquals(6, service.entries().size)
        assertEquals(2, net.calls)

        now += 2000
        net.body = { catalog }
        service.entries()
        service.refreshing!!.join()
        assertEquals(3, net.calls)
        service.entries()
        assertEquals(3, net.calls)
    }

    /**
     * R6-F1: a background refresh that dies with something other than an
     * IOException (a platform RuntimeException, an OOM re-raised from the
     * download thread) records the failure and backs off; nothing escapes
     * the job to the thread's uncaught handler, where it would kill the app.
     */
    @Test
    fun aRefreshThrowingANonIoErrorIsContained() = runBlocking {
        var now = 1_700_000_000_000L
        val file = File(dir, "rpcs.json")
        val net = Net { catalog }
        ChainlistService(file, net::fetch) { now }.entries()

        now += 2 * Chainlist.CACHE_TTL_MS
        val escaped = mutableListOf<Throwable>()
        // No backstop handler of the service's own here: an exception that
        // left the job would land in this one instead.
        val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> escaped += e },
        )
        for (boom in listOf<Throwable>(RuntimeException("platform bug"), OutOfMemoryError("body"))) {
            net.body = { throw boom }
            val service = ChainlistService(file, net::fetch, scope) { now }
            val callsBefore = net.calls
            assertEquals(6, service.entries().size)
            val job = service.refreshing!!
            job.join()
            assertFalse(job.isCancelled)
            assertEquals(callsBefore + 1, net.calls)
            assertTrue(escaped.toString(), escaped.isEmpty())

            // The failure was recorded: the next visit keeps the stale copy
            // and doesn't retry within the back-off.
            assertEquals(6, service.entries().size)
            assertEquals(callsBefore + 1, net.calls)
        }
    }

    @Test
    fun noCacheAndNoNetworkFails() = runBlocking {
        val service = ChainlistService(File(dir, "rpcs.json"), { throw IOException("offline") })
        try {
            service.entries()
            fail("expected IOException")
        } catch (_: IOException) {
        }
        val errorPage = ChainlistService(File(dir, "rpcs.json"), { "not json" })
        try {
            errorPage.entries()
            fail("expected IOException")
        } catch (_: IOException) {
        }
    }
}
