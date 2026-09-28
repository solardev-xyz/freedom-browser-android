package baby.freedom.mobile.chains

import baby.freedom.mobile.browser.Icu4jUts46
import baby.freedom.mobile.browser.WhatwgHost
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
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

        now += 2000
        service.entries()
        assertEquals(2, net.calls)
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
        assertEquals(2, net.calls)

        // An error page doesn't replace a good cache either.
        net.body = { "<html>oops</html>" }
        val errorPage = ChainlistService(file, net::fetch) { now }
        assertEquals(6, errorPage.entries().size)
        assertTrue(file.readText().startsWith("["))
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
