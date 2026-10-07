package baby.freedom.mobile.wallet

import baby.freedom.mobile.chains.BuiltInChains
import baby.freedom.mobile.chains.Chain
import baby.freedom.mobile.chains.rpc.ChainDataRouter
import baby.freedom.mobile.chains.rpc.RpcTransport
import baby.freedom.mobile.chains.rpc.WalletRpc
import java.io.IOException
import java.math.BigDecimal
import java.math.BigInteger
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Show prices (#439): off means no request; on, prices from the chain through the verified read path. */
class FiatPricesTest {
    private val ethUrls = listOf("https://e1.example", "https://e2.example", "https://e3.example")
    private val gnoUrls = listOf("https://g1.example", "https://g2.example", "https://g3.example")
    /** The user's own Ethereum RPCs (seated first); none unless a test adds one. */
    private var userEthUrls = emptyList<String>()
    private val chains get() = listOf<Chain>(
        BuiltInChains.ETHEREUM.copy(rpcUrls = userEthUrls + ethUrls, userRpcUrls = userEthUrls),
        BuiltInChains.GNOSIS.copy(rpcUrls = gnoUrls),
    )
    private val now = 1_800_000_000L

    /** The phone's clock (seconds); right unless a test sets it wrong. */
    private var wallClock = now
    private val sent = mutableListOf<Pair<String, JSONObject>>()
    private var clock = 10_000_000L

    /** What a feed answers: price in USD (8 decimals), and when it was updated. */
    private val feeds = mutableMapOf(
        PriceFeeds.ETH_USD.address to (BigDecimal("2500") to now - 600),
        PriceFeeds.EUR_USD.address to (BigDecimal("1.25") to now - 600),
        PriceFeeds.USDC_USD.address to (BigDecimal("1") to now - 600),
        PriceFeeds.USDT_USD.address to (BigDecimal("1") to now - 600),
        PriceFeeds.DAI_USD.address to (BigDecimal("1") to now - 600),
        PriceFeeds.XDAI_USD.address to (BigDecimal("0.999") to now - 600),
    )

    /** Each pool's average tick over the window. */
    private val ticks = mutableMapOf(PriceFeeds.BZZ_WETH.pool to -63235L, PriceFeeds.EURC_USDC.pool to 1126L)

    /**
     * How far back each pool's oldest kept observation reaches, in seconds
     * before the pinned block (absent: far enough for the full window).
     * Asked further back, the pool reverts `OLD` as Uniswap v3 does.
     */
    private val reach = mutableMapOf<String, Long>()

    /** The pinned block's time. */
    private val blockTime = now - 30

    /** Every RPC fails (an outage) while set. */
    private var down = false

    /** While set, every request waits for it. */
    private var gate: CompletableDeferred<Unit>? = null

    /** The RPC hosts whose answers differ from the rest (so nothing is agreed). */
    private var liars = emptySet<String>()

    private fun word(n: BigInteger): String =
        (if (n.signum() < 0) n + BigInteger.ONE.shiftLeft(256) else n).toString(16).padStart(64, '0')

    private fun word(n: Long) = word(BigInteger.valueOf(n))

    private fun answer(url: String, req: JSONObject): String {
        val method = req.getString("method")
        if (method == "eth_blockNumber") return "\"result\":\"0x100\""
        if (method == "eth_getBlockByNumber") {
            val tag = req.getJSONArray("params").getString(0)
            return "\"result\":{\"number\":\"$tag\",\"timestamp\":\"0x${blockTime.toString(16)}\",\"hash\":\"0x$tag\"}"
        }
        if (method != "eth_call") return "\"error\":{\"code\":-32601,\"message\":\"no\"}"
        val call = req.getJSONArray("params").getJSONObject(0)
        val to = call.getString("to")
        // Each liar a different lie, so no two of them agree either.
        val lie = liars.indexOfFirst { url.contains(it) }.let { if (it < 0) 0L else it + 1L }
        feeds.entries.firstOrNull { it.key.equals(to, true) }?.let { (_, v) ->
            val raw = v.first.movePointRight(8).toBigInteger() + BigInteger.valueOf(lie)
            return "\"result\":\"0x" + word(7) + word(raw) + word(v.second) + word(v.second) + word(7) + "\""
        }
        val data = call.getString("data")
        if (to.equals(MULTICALL3, true) && data == GET_CURRENT_BLOCK_TIMESTAMP) {
            // As Colibri's proven EVM answers `block.timestamp`: 0. The block's header is the one to ask.
            return "\"result\":\"0x" + word(0) + "\""
        }
        ticks.entries.firstOrNull { it.key.equals(to, true) }?.let { (pool, tick) ->
            val back = reach[pool] ?: 86_400L
            when {
                data == PriceFeeds.SLOT0 ->
                    // sqrtPrice, tick, observationIndex 0, cardinality 2, next 2, fee protocol, unlocked
                    return "\"result\":\"0x" + word(1) + word(tick) + word(0) + word(2) + word(2) + word(0) + word(1) + "\""
                data == PriceFeeds.observations(1) ->
                    return "\"result\":\"0x" + word(blockTime - back) + word(5) + word(0) + word(1) + "\""
                data == PriceFeeds.observations(0) ->
                    return "\"result\":\"0x" + word(blockTime - 60) + word(5) + word(0) + word(1) + "\""
            }
            val seconds = BigInteger(data.substring(data.length - 128, data.length - 64), 16).toLong()
            if (seconds > back) {
                // Error("OLD")
                return "\"error\":{\"code\":3,\"message\":\"execution reverted: OLD\",\"data\":\"0x08c379a0" +
                    word(32) + word(3) + "4f4c44".padEnd(64, '0') + "\"}"
            }
            val c0 = 1_000_000L
            val c1 = c0 + tick * seconds + lie
            return "\"result\":\"0x" + word(64) + word(192) + word(2) + word(c0) + word(c1) + word(2) + word(1) + word(2) + "\""
        }
        return "\"result\":\"0x\""
    }

    private companion object {
        const val MULTICALL3 = "0xcA11bde05977b3631167028862bE2a173976CA11"
        const val GET_CURRENT_BLOCK_TIMESTAMP = "0x0f28c97d"
    }

    private var setting = FiatCurrency.OFF

    private fun prices() = FiatPrices(
        loadSetting = { setting },
        saveSetting = { setting = it },
        rpc = {
            WalletRpc(
                ChainDataRouter(
                    chains = { chains },
                    transport = RpcTransport { url, body, _ ->
                        val req = JSONObject(body)
                        synchronized(sent) { sent += url to req }
                        gate?.await()
                        if (down) throw IOException("down")
                        """{"jsonrpc":"2.0","id":1,${answer(url, req)}}"""
                    },
                ),
            )
        },
        elapsed = { clock },
        wallClockSeconds = { wallClock },
    )

    private val eth = TokenRegistry.native(BuiltInChains.ETHEREUM)
    private val xdai = TokenRegistry.native(BuiltInChains.GNOSIS)
    private fun token(symbol: String) = TokenRegistry.builtins.first { it.symbol == symbol }

    @Test
    fun `off by default, and off sends no request at all`() = runBlocking {
        val p = prices()
        p.refresh()
        p.refresh()
        assertEquals(FiatCurrency.OFF, p.currency.value)
        assertNull(p.quotes.value)
        assertTrue("no price request leaves the device", sent.isEmpty())
    }

    @Test
    fun `turning it off forgets the prices and stops reading`() = runBlocking {
        val p = prices()
        p.set(FiatCurrency.USD)
        p.refresh()
        assertTrue(p.quotes.value != null)
        p.set(FiatCurrency.OFF)
        assertEquals(FiatCurrency.OFF, setting)
        assertNull(p.quotes.value)
        sent.clear()
        clock += FiatPrices.TTL_MS * 10
        p.refresh()
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `an unknown stored value reads as off`() {
        assertEquals(FiatCurrency.OFF, FiatCurrency.parse(null))
        assertEquals(FiatCurrency.OFF, FiatCurrency.parse("GBP"))
        assertEquals(FiatCurrency.EUR, FiatCurrency.parse("EUR"))
    }

    @Test
    fun `usd prices from chainlink and the pools, every read pinned to one block`() = runBlocking {
        setting = FiatCurrency.USD
        val p = prices()
        p.refresh()
        val q = p.quotes.value!!
        assertEquals(FiatCurrency.USD, q.currency)
        assertEquals(0, BigDecimal("2500").compareTo(q.perToken[eth.key]))
        assertEquals(0, BigDecimal("0.999").compareTo(q.perToken[xdai.key]))
        assertEquals(0, BigDecimal.ONE.compareTo(q.perToken[token("USDC").key]))
        // BZZ: 1.0001^-63235 ETH per 10^16 / 10^18 … times ETH's price; xBZZ takes BZZ's.
        val bzz = q.perToken[token("BZZ").key]!!
        assertEquals(0.04484, bzz.toDouble(), 0.0001)
        assertEquals(bzz, q.perToken[token("xBZZ").key])
        assertEquals(1.1192, q.perToken[token("EURC").key]!!.toDouble(), 0.0001)
        // No source for EURe: no value, rather than a guess.
        assertNull(q.perToken[token("EURe").key])
        // USD needs no EUR/USD read.
        assertTrue(sent.none { it.second.optJSONArray("params")?.optJSONObject(0)?.optString("to").equals(PriceFeeds.EUR_USD.address, true) })
        val calls = sent.filter { it.second.getString("method") == "eth_call" }
        assertTrue(calls.isNotEmpty())
        assertTrue(calls.all { it.second.getJSONArray("params").getString(1) == "0xfe" })
    }

    @Test
    fun `euro prices divide by eur-usd`() = runBlocking {
        setting = FiatCurrency.EUR
        val p = prices()
        p.refresh()
        assertEquals(0, BigDecimal("2000").compareTo(p.quotes.value!!.perToken[eth.key]))
    }

    @Test
    fun `kept for five minutes, then read again`() = runBlocking {
        setting = FiatCurrency.USD
        val p = prices()
        p.refresh()
        val first = sent.size
        clock += FiatPrices.TTL_MS - 1
        p.refresh()
        assertEquals(first, sent.size)
        clock += 2
        p.refresh()
        assertTrue(sent.size > first)
    }

    @Test
    fun `an answer the rpcs don't agree on prices nothing`() = runBlocking {
        setting = FiatCurrency.USD
        liars = setOf("e2", "e3")
        val p = prices()
        p.refresh()
        val q = p.quotes.value!!
        assertNull(q.perToken[eth.key])
        assertNull(q.perToken[token("BZZ").key])
        // Gnosis' own feed was agreed on: xDAI still has its value.
        assertEquals(0, BigDecimal("0.999").compareTo(q.perToken[xdai.key]))
        // An unverified observe([1800, 0]) is no price, not a revert: no fallback reads follow it.
        val poolReads = sent.mapNotNull { it.second.optJSONArray("params")?.optJSONObject(0)?.optString("data") }
        assertTrue(poolReads.none { it == PriceFeeds.SLOT0 || it.startsWith("0x252c09d7") })
        assertTrue(poolReads.none { it.startsWith("0x883bdbfd") && it != PriceFeeds.OBSERVE })
    }

    @Test
    fun `the user's own rpc prices alone, but not against public rpcs that answered differently`() = runBlocking {
        setting = FiatCurrency.USD
        userEthUrls = listOf("https://mine.example")
        // Everyone agrees with it: priced.
        val alone = prices()
        alone.refresh()
        assertEquals(0, BigDecimal("2500").compareTo(alone.quotes.value!!.perToken[eth.key]))
        // Every public RPC answers something else: no quorum, and the user RPC's
        // answer comes back USER_CONFIGURED with dissenters. That prices nothing.
        liars = setOf("mine", "e1", "e2", "e3")
        val disputed = prices()
        disputed.refresh()
        val q = disputed.quotes.value!!
        assertNull(q.perToken[eth.key])
        assertNull(q.perToken[token("BZZ").key])
        assertEquals(0, BigDecimal("0.999").compareTo(q.perToken[xdai.key]))
    }

    @Test
    fun `a feed's age is judged by its block's time, not the phone's clock`() = runBlocking {
        setting = FiatCurrency.EUR
        // Updated a minute before the pinned block; the phone's clock is a quarter of an hour slow.
        feeds.keys.toList().forEach { feeds[it] = feeds[it]!!.first to blockTime - 60 }
        wallClock = now - 900
        val p = prices()
        p.refresh()
        assertEquals(0, BigDecimal("2000").compareTo(p.quotes.value!!.perToken[eth.key]))
        // Three days fast: still priced.
        wallClock = now + 3 * 86_400
        clock += FiatPrices.TTL_MS
        p.refresh()
        assertEquals(0, BigDecimal("2000").compareTo(p.quotes.value!!.perToken[eth.key]))
        // Every block-time read pinned to the same block as the rest.
        val stamps = sent.filter { it.second.getString("method") == "eth_getBlockByNumber" }
        assertTrue(stamps.isNotEmpty())
        assertTrue(stamps.all { it.second.getJSONArray("params").getString(0) == "0xfe" })
    }

    @Test
    fun `a stale feed prices nothing, and a failed read is tried again after a minute`() = runBlocking {
        setting = FiatCurrency.USD
        feeds.keys.toList().forEach { feeds[it] = feeds[it]!!.first to blockTime - FiatMath.MAX_FEED_AGE_SECONDS - 1 }
        val p = prices()
        p.refresh()
        assertTrue(p.quotes.value!!.perToken.isEmpty())
        val first = sent.size
        clock += FiatPrices.RETRY_MS - 1
        p.refresh()
        assertEquals(first, sent.size)
        clock += 2
        p.refresh()
        assertTrue(sent.size > first)
    }

    @Test
    fun `chainlink answers are checked for shape, sign and age`() {
        fun round(answer: BigInteger, updatedAt: Long) =
            "0x" + word(1) + word(answer) + word(updatedAt) + word(updatedAt) + word(1)
        val ok = round(BigInteger.valueOf(258115000000L), now - 60)
        assertEquals(0, BigDecimal("2581.15").compareTo(FiatMath.chainlinkPrice(ok, 8, now)))
        assertNull(FiatMath.chainlinkPrice(round(BigInteger.ZERO, now), 8, now))
        assertNull(FiatMath.chainlinkPrice(round(BigInteger.valueOf(-5), now), 8, now))
        assertNull(FiatMath.chainlinkPrice(round(BigInteger.ONE, now - FiatMath.MAX_FEED_AGE_SECONDS - 1), 8, now))
        // From the future (beyond clock slack): a wrong clock is no price, not a fresh one forever.
        assertNull(FiatMath.chainlinkPrice(round(BigInteger.ONE, now + 3600), 8, now))
        assertNull(FiatMath.chainlinkPrice("0x", 8, now))
        assertNull(FiatMath.chainlinkPrice(ok.dropLast(64), 8, now))
    }

    @Test
    fun `twap ticks round toward negative infinity`() {
        fun obs(c0: Long, c1: Long) = "0x" + word(64) + word(192) + word(2) + word(c0) + word(c1) + word(2) + word(1) + word(2)
        assertEquals(-2L, FiatMath.twapTick(obs(0, -1801), 1800))
        assertEquals(-1L, FiatMath.twapTick(obs(0, -1800), 1800))
        assertEquals(1L, FiatMath.twapTick(obs(0, 1801), 1800))
        assertNull(FiatMath.twapTick("0x" + word(1), 1800))
        // Out of Uniswap's tick range: not a price.
        assertNull(FiatMath.twapTick(obs(0, 900_000L * 1800), 1800))
        assertEquals(1.0, FiatMath.tickPrice(0, 6, 6).toDouble(), 1e-12)
        assertEquals(0.01, FiatMath.tickPrice(0, 16, 18).toDouble(), 1e-12)
    }

    @Test
    fun `values are rounded to the cent and marked approximate`() {
        val en = Locale.US
        assertEquals("≈ €12.41", FiatMath.format(BigDecimal("12.405"), FiatCurrency.EUR, en))
        assertEquals("≈ $1,234.50", FiatMath.format(BigDecimal("1234.5"), FiatCurrency.USD, en))
        assertEquals("< €0.01", FiatMath.format(BigDecimal("0.004"), FiatCurrency.EUR, en))
        assertEquals("≈ €0.01", FiatMath.format(BigDecimal("0.005"), FiatCurrency.EUR, en))
        assertEquals("≈ €0.00", FiatMath.format(BigDecimal.ZERO, FiatCurrency.EUR, en))
        assertNull(FiatMath.format(BigDecimal.ONE, FiatCurrency.OFF, en))
        // In the app's language's own way of writing money.
        assertEquals("≈ 12,40 €", FiatMath.format(BigDecimal("12.4"), FiatCurrency.EUR, Locale.GERMANY)?.replace(' ', ' '))
    }

    @Test
    fun `a token amount's value uses its decimals`() {
        val q = FiatQuotes(FiatCurrency.EUR, mapOf(eth.key to BigDecimal("2000")))
        assertEquals(0, BigDecimal("3").compareTo(q.value(eth.key, BigInteger("1500000000000000"), 18)))
        assertNull(q.value(token("EURe").key, BigInteger.ONE, 18))
    }

    private fun observeWindows(pool: String) = sent.mapNotNull { (_, req) ->
        val call = req.optJSONArray("params")?.optJSONObject(0) ?: return@mapNotNull null
        val data = call.getString("data")
        if (!call.getString("to").equals(pool, true) || !data.startsWith("0x883bdbfd")) return@mapNotNull null
        BigInteger(data.substring(data.length - 128, data.length - 64), 16).toLong()
    }.toSet()

    @Test
    fun `a pool that can't reach back half an hour averages over as far as it does`() = runBlocking {
        // BZZ / WETH keeps two observations: its last two trades 1092 s apart, observe([1800, 0]) reverts OLD.
        setting = FiatCurrency.USD
        reach[PriceFeeds.BZZ_WETH.pool] = 1092
        val p = prices()
        p.refresh()
        val q = p.quotes.value!!
        val bzz = q.perToken[token("BZZ").key]
        assertNotNull("BZZ still has a price", bzz)
        assertEquals(0.04484, bzz!!.toDouble(), 0.0001)
        assertEquals(bzz, q.perToken[token("xBZZ").key])
        assertEquals(setOf(1800L, 1092L), observeWindows(PriceFeeds.BZZ_WETH.pool))
        // The other pool answered the full window and wasn't asked again.
        assertEquals(setOf(1800L), observeWindows(PriceFeeds.EURC_USDC.pool))
        val all = sent.filter { it.second.getString("method") == "eth_call" }
        assertTrue(all.all { it.second.getJSONArray("params").getString(1) == "0xfe" })
    }

    @Test
    fun `a pool reaching back less than ten minutes gives no price`() = runBlocking {
        setting = FiatCurrency.USD
        reach[PriceFeeds.BZZ_WETH.pool] = PriceFeeds.MIN_TWAP_SECONDS - 1L
        val p = prices()
        p.refresh()
        val q = p.quotes.value!!
        assertNull(q.perToken[token("BZZ").key])
        assertNull(q.perToken[token("xBZZ").key])
        assertTrue(observeWindows(PriceFeeds.BZZ_WETH.pool) == setOf(1800L))
        assertEquals(0, BigDecimal("2500").compareTo(q.perToken[eth.key]))
    }

    @Test
    fun `fallback window arithmetic`() {
        assertEquals(1092, FiatMath.fallbackWindow(2000, 908, 1800, 600))
        assertEquals(1800, FiatMath.fallbackWindow(10_000, 1, 1800, 600))
        assertNull(FiatMath.fallbackWindow(1000, 401, 1800, 600))
        assertNull(FiatMath.fallbackWindow(1000, 2000, 1800, 600))
        fun slot0(index: Long, cardinality: Long) = "0x" + word(1) + word(-5) + word(index) + word(cardinality) + word(cardinality) + word(0) + word(1)
        assertEquals(1, FiatMath.oldestObservationIndex(slot0(0, 2)))
        assertEquals(0, FiatMath.oldestObservationIndex(slot0(1, 2)))
        assertEquals(0, FiatMath.oldestObservationIndex(slot0(0, 1)))
        assertNull(FiatMath.oldestObservationIndex(slot0(2, 2)))
        assertNull(FiatMath.oldestObservationIndex(slot0(0, 0)))
        assertEquals(77L, FiatMath.observationTimestamp("0x" + word(77) + word(1) + word(0) + word(1)))
        assertNull(FiatMath.observationTimestamp("0x" + word(77) + word(1) + word(0) + word(0)))
    }

    @Test
    fun `a short outage keeps the prices on screen, for half an hour at most`() = runBlocking {
        setting = FiatCurrency.USD
        val p = prices()
        p.refresh()
        val before = p.quotes.value!!.perToken
        assertTrue(before.isNotEmpty())
        down = true
        clock += FiatPrices.TTL_MS
        p.refresh()
        assertEquals(before, p.quotes.value!!.perToken)
        // Short of what it had: tried again after a minute, not five.
        val asked = sent.size
        clock += FiatPrices.RETRY_MS
        p.refresh()
        assertTrue(sent.size > asked)
        assertEquals(before, p.quotes.value!!.perToken)
        // A source that stays dead doesn't leave its old price up.
        clock += FiatPrices.HOLD_MS
        p.refresh()
        assertTrue(p.quotes.value!!.perToken.isEmpty())
        // Back: everything priced again.
        down = false
        clock += FiatPrices.RETRY_MS
        p.refresh()
        assertEquals(before, p.quotes.value!!.perToken)
    }

    @Test
    fun `one read at a time, however slow`() = runBlocking {
        setting = FiatCurrency.USD
        fun ethFeedCalls() = synchronized(sent) {
            sent.count { it.second.optJSONArray("params")?.optJSONObject(0)?.optString("to").equals(PriceFeeds.ETH_USD.address, true) }
        }
        val p = prices()
        val g = CompletableDeferred<Unit>()
        gate = g
        val first = launch(Dispatchers.IO) { p.refresh() }
        withTimeout(5_000) { while (synchronized(sent) { sent.isEmpty() }) delay(5) }
        // Past the retry wait with no quotes yet: a second refresh returns at once and starts nothing.
        clock += FiatPrices.RETRY_MS * 2
        withTimeout(5_000) { p.refresh() }
        g.complete(Unit)
        first.join()
        gate = null
        // One read asks ETH / USD of each of the three RPCs at most; a second would ask again.
        assertTrue(ethFeedCalls() in 1..ethUrls.size)
        assertNotNull(p.quotes.value)
    }
}
