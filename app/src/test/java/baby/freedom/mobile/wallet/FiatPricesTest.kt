package baby.freedom.mobile.wallet

import baby.freedom.mobile.chains.BuiltInChains
import baby.freedom.mobile.chains.Chain
import baby.freedom.mobile.chains.rpc.ChainDataRouter
import baby.freedom.mobile.chains.rpc.RpcTransport
import baby.freedom.mobile.chains.rpc.WalletRpc
import java.math.BigDecimal
import java.math.BigInteger
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Show prices (#439): off means no request; on, prices from the chain through the verified read path. */
class FiatPricesTest {
    private val ethUrls = listOf("https://e1.example", "https://e2.example", "https://e3.example")
    private val gnoUrls = listOf("https://g1.example", "https://g2.example", "https://g3.example")
    private val chains = listOf<Chain>(
        BuiltInChains.ETHEREUM.copy(rpcUrls = ethUrls),
        BuiltInChains.GNOSIS.copy(rpcUrls = gnoUrls),
    )
    private val now = 1_800_000_000L
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

    /** The RPC hosts whose answers differ from the rest (so nothing is agreed). */
    private var liars = emptySet<String>()

    private fun word(n: BigInteger): String =
        (if (n.signum() < 0) n + BigInteger.ONE.shiftLeft(256) else n).toString(16).padStart(64, '0')

    private fun word(n: Long) = word(BigInteger.valueOf(n))

    private fun answer(url: String, req: JSONObject): String {
        val method = req.getString("method")
        if (method == "eth_blockNumber") return "\"result\":\"0x100\""
        if (method != "eth_call") return "\"error\":{\"code\":-32601,\"message\":\"no\"}"
        val call = req.getJSONArray("params").getJSONObject(0)
        val to = call.getString("to")
        // Each liar a different lie, so no two of them agree either.
        val lie = liars.indexOfFirst { url.contains(it) }.let { if (it < 0) 0L else it + 1L }
        feeds.entries.firstOrNull { it.key.equals(to, true) }?.let { (_, v) ->
            val raw = v.first.movePointRight(8).toBigInteger() + BigInteger.valueOf(lie)
            return "\"result\":\"0x" + word(7) + word(raw) + word(v.second) + word(v.second) + word(7) + "\""
        }
        ticks.entries.firstOrNull { it.key.equals(to, true) }?.let { (_, tick) ->
            val c0 = 1_000_000L
            val c1 = c0 + tick * PriceFeeds.TWAP_SECONDS + lie
            return "\"result\":\"0x" + word(64) + word(192) + word(2) + word(c0) + word(c1) + word(2) + word(1) + word(2) + "\""
        }
        return "\"result\":\"0x\""
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
                        """{"jsonrpc":"2.0","id":1,${answer(url, req)}}"""
                    },
                ),
            )
        },
        elapsed = { clock },
        wallClockSeconds = { now },
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
    }

    @Test
    fun `a stale feed prices nothing, and a failed read is tried again after a minute`() = runBlocking {
        setting = FiatCurrency.USD
        feeds.keys.toList().forEach { feeds[it] = feeds[it]!!.first to now - FiatMath.MAX_FEED_AGE_SECONDS - 1 }
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
}
