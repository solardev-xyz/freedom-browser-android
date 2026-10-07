package baby.freedom.mobile.wallet

import android.content.Context
import android.os.SystemClock
import android.util.Log
import baby.freedom.mobile.R
import baby.freedom.mobile.chains.rpc.ChainDataRouter
import baby.freedom.mobile.chains.rpc.ChainRpcException
import baby.freedom.mobile.chains.rpc.ChainTrust
import baby.freedom.mobile.chains.rpc.WalletRpc
import baby.freedom.mobile.chains.rpc.undisputed
import baby.freedom.mobile.data.FiatSettingStore
import baby.freedom.mobile.l10n.Strings
import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The currency the wallet shows approximate values in (#439): none by
 * default. Only with one chosen does the wallet read a price at all.
 */
enum class FiatCurrency(val code: String?) {
    OFF(null),
    EUR("EUR"),
    USD("USD"),
    ;

    companion object {
        /** The stored [name], or [OFF] for anything else (nothing stored, an unknown value). */
        fun parse(stored: String?): FiatCurrency = entries.firstOrNull { it.name == stored } ?: OFF
    }
}

/**
 * Prices the wallet read, per token key ([Token.key]): what one whole
 * token is worth in [currency], approximately.
 */
data class FiatQuotes(val currency: FiatCurrency, val perToken: Map<String, BigDecimal>) {
    /** [raw] base units of the token with [tokenKey] in [currency]; null when there's no price for it. */
    fun value(tokenKey: String, raw: BigInteger, decimals: Int): BigDecimal? =
        perToken[tokenKey]?.let { FiatMath.value(raw, decimals, it) }
}

/**
 * Where a built-in token's price comes from (#439). No price service:
 * every price is read from the chain itself, through the wallet's own
 * verified read path ([WalletRpc]) — Chainlink's aggregators, and for
 * the two tokens Chainlink has no feed for, a Uniswap v3 pool's
 * 30-minute average price (a TWAP, not the pool's spot price, which one
 * trade can move).
 */
internal object PriceFeeds {
    /** A Chainlink aggregator answering in USD with [decimals] decimals. */
    data class Chainlink(val chainId: Long, val address: String, val decimals: Int)

    /**
     * A Uniswap v3 pool's time-weighted price of its token0 in its token1
     * (with [baseDecimals] and [quoteDecimals]), turned into USD by
     * [quoteUsd], token1's own feed.
     */
    data class Twap(
        val chainId: Long,
        val pool: String,
        val baseDecimals: Int,
        val quoteDecimals: Int,
        val quoteUsd: Chainlink,
    )

    // Checked on chain (description(), decimals()) on 2026-10-07.
    val ETH_USD = Chainlink(TokenRegistry.ETHEREUM, "0x5f4eC3Df9cbd43714FE2740f5E3616155c5b8419", 8)
    val EUR_USD = Chainlink(TokenRegistry.ETHEREUM, "0xb49f677943BC038e9857d61E7d053CaA2C1734C1", 8)
    val USDC_USD = Chainlink(TokenRegistry.ETHEREUM, "0x8fFfFfd4AfB6115b954Bd326cbe7B4BA576818f6", 8)
    val USDT_USD = Chainlink(TokenRegistry.ETHEREUM, "0x3E7d1eAB13ad0104d2750B8863b489D65364e32D", 8)
    val DAI_USD = Chainlink(TokenRegistry.ETHEREUM, "0xAed0c38402a5d19df6E4c03F4E2DceD6e29c1ee9", 8)

    /** Gnosis' DAI / USD feed: xDAI is DAI bridged to Gnosis. */
    val XDAI_USD = Chainlink(TokenRegistry.GNOSIS, "0x678df3415fc31947dA4324eC63212874be5a82f8", 8)

    /** BZZ / WETH, 1 % (Uniswap v3 on Ethereum): BZZ's deepest pool. BZZ (0x1906…) is token0. */
    val BZZ_WETH = Twap(TokenRegistry.ETHEREUM, "0x5696c2c2fcb7e304a5b9faaec9cd37d369c9d067", 16, 18, ETH_USD)

    /** EURC / USDC, 0.05 % (Uniswap v3 on Ethereum). EURC (0x1aBa…) is token0. */
    val EURC_USDC = Twap(TokenRegistry.ETHEREUM, "0x95dbb3c7546f22bce375900abfdd64a4e5bd73d6", 6, 6, USDC_USD)

    /**
     * Each priced token's source, by [Token.key]. xBZZ is BZZ bridged to
     * Gnosis, so it takes BZZ's price. Monerium's EURe has no on-chain
     * source here: it shows no value.
     */
    val byToken: Map<String, Any> = mapOf(
        "1:native" to ETH_USD,
        "1:0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48" to USDC_USD,
        "1:0xdac17f958d2ee523a2206206994597c13d831ec7" to USDT_USD,
        "1:0x6b175474e89094c44da98b954eedeac495271d0f" to DAI_USD,
        "1:0x1abaea1f7c830bd89acc67ec4af516284b1bc33c" to EURC_USDC,
        "1:0x19062190b1925b5b6689d7073fdfc8c2976ef8cb" to BZZ_WETH,
        "100:native" to XDAI_USD,
        "100:0xdbf3ea6f5bee45c02255b2c26a16f300502f68da" to BZZ_WETH,
    )

    /** The TWAP's window: half an hour. */
    const val TWAP_SECONDS = 1800

    /** `latestRoundData()`. */
    const val LATEST_ROUND_DATA = "0xfeaf968c"

    /**
     * The shortest window a pool's average is still taken over when it
     * can't answer [TWAP_SECONDS]: ten minutes. Shorter, and it's too
     * close to the spot price one trade can move — no price.
     */
    const val MIN_TWAP_SECONDS = 600

    /** `observe(uint32[] [TWAP_SECONDS, 0])`. */
    val OBSERVE: String = observe(TWAP_SECONDS)

    /** `observe(uint32[] [seconds, 0])`. */
    fun observe(seconds: Int): String = "0x883bdbfd" + word(32) + word(2) + word(seconds.toLong()) + word(0)

    /** Multicall3, at the same address on every chain. */
    const val MULTICALL3 = "0xcA11bde05977b3631167028862bE2a173976CA11"

    /** Multicall3's `getCurrentBlockTimestamp()`: the EVM's `block.timestamp`. */
    const val GET_CURRENT_BLOCK_TIMESTAMP = "0x0f28c97d"

    /**
     * Multicall3 `aggregate([(MULTICALL3, getCurrentBlockTimestamp()), ([to], [data])])`:
     * [data] run in the same EVM as a read of the `block.timestamp` it ran
     * with, for [FiatMath.timedResult] to check against the block's header.
     * A revert of [data] reverts the whole call.
     */
    fun timed(to: String, data: String): String {
        fun element(target: String, call: String): String {
            val bytes = call.removePrefix("0x")
            val padded = bytes.padEnd((bytes.length + 63) / 64 * 64, '0')
            return address(target) + word(64) + word(bytes.length / 2L) + padded
        }
        val first = element(MULTICALL3, GET_CURRENT_BLOCK_TIMESTAMP)
        val second = element(to, data)
        return "0x252dba42" + word(32) + word(2) + word(64) + word(64 + first.length / 2L) + first + second
    }

    /** A Uniswap v3 pool's `slot0()`. */
    const val SLOT0 = "0x3850c7bd"

    /** A Uniswap v3 pool's `observations(uint256 [index])`. */
    fun observations(index: Int): String = "0x252c09d7" + word(index.toLong())

    private fun word(n: Long) = n.toString(16).padStart(64, '0')

    private fun address(a: String) = a.removePrefix("0x").lowercase().padStart(64, '0')
}

/** The arithmetic behind [FiatPrices], kept pure for tests. */
internal object FiatMath {
    private val MC = MathContext(20, RoundingMode.HALF_EVEN)

    /**
     * Chainlink `latestRoundData()`'s answer as a number, or null when it
     * isn't one to show: not five words, not above zero, or updated more
     * than [maxAgeSeconds] before [nowSeconds] — or, beyond a few minutes'
     * slack, after it. [nowSeconds] is the pinned block's own time when
     * that could be read (the phone's clock plays no part then), the
     * phone's clock otherwise.
     */
    fun chainlinkPrice(hex: String, decimals: Int, nowSeconds: Long, maxAgeSeconds: Long = MAX_FEED_AGE_SECONDS): BigDecimal? {
        val words = words(hex) ?: return null
        if (words.size != 5) return null
        val answer = signed(words[1])
        if (answer.signum() <= 0) return null
        val updatedAt = words[3]
        if (updatedAt.bitLength() > 62) return null
        val age = nowSeconds - updatedAt.toLong()
        if (age !in -CLOCK_SLACK_SECONDS..maxAgeSeconds) return null
        return BigDecimal(answer, decimals)
    }

    /**
     * The average tick over [seconds] from a Uniswap v3 `observe([seconds, 0])`
     * answer, rounded toward negative infinity as the pool's own oracle
     * library does; null for an answer of the wrong shape.
     */
    fun twapTick(hex: String, seconds: Int): Long? {
        val words = words(hex) ?: return null
        // offset, offset, n=2, c0, c1, n=2, s0, s1
        if (words.size != 8 || words[2] != BIG_TWO || words[5] != BIG_TWO) return null
        val delta = signed(words[4]) - signed(words[3])
        val (q, r) = delta.divideAndRemainder(BigInteger.valueOf(seconds.toLong()))
        val tick = if (r.signum() < 0) q - BigInteger.ONE else q
        if (tick.abs() > BigInteger.valueOf(MAX_TICK)) return null
        return tick.toLong()
    }

    /**
     * From a pool's `slot0()`: the index of the oldest observation it
     * keeps, `(observationIndex + 1) % observationCardinality` — or null
     * for an answer of the wrong shape.
     */
    fun oldestObservationIndex(slot0Hex: String): Int? {
        val words = words(slot0Hex) ?: return null
        if (words.size != 7) return null
        val index = words[2]
        val cardinality = words[3]
        if (cardinality.signum() <= 0 || cardinality.bitLength() > 16 || index >= cardinality) return null
        return ((index.toInt() + 1) % cardinality.toInt())
    }

    /**
     * From `observations(i)`: its timestamp, or null when that slot isn't
     * initialized yet (a pool still filling a ring it grew) or the answer
     * is the wrong shape.
     */
    fun observationTimestamp(hex: String): Long? {
        val words = words(hex) ?: return null
        if (words.size != 4 || words[3] != BigInteger.ONE) return null
        if (words[0].bitLength() > 32) return null
        return words[0].toLong()
    }

    /**
     * The second call's return data from a [PriceFeeds.timed] answer
     * (Multicall3 `aggregate`'s `(uint256, bytes[])`), or null unless the
     * `block.timestamp` its EVM ran with is [blockTimestamp], the block's
     * own time from its header — or the answer is the wrong shape. A
     * Uniswap v3 `observe()` run at another time answers for that time:
     * at 0 (Colibri's proven EVM) its "30 minutes ago" wraps to the far
     * future and both ends come out as the current tick, the spot price.
     */
    fun timedResult(hex: String, blockTimestamp: Long): String? {
        val w = words(hex) ?: return null
        fun index(at: Int, base: Int): Int? {
            val off = w.getOrNull(at) ?: return null
            if (off.bitLength() > 20 || off.toInt() % 32 != 0) return null
            return base + off.toInt() / 32
        }
        val array = index(1, 0) ?: return null
        if (w.getOrNull(array) != BIG_TWO) return null
        val results = (0 until 2).map { i ->
            val at = index(array + 1 + i, array + 1) ?: return null
            val len = w.getOrNull(at) ?: return null
            if (len.bitLength() > 20 || len.toInt() % 32 != 0 || len.signum() == 0) return null
            val end = at + 1 + len.toInt() / 32
            if (end > w.size) return null
            w.subList(at + 1, end)
        }
        val time = results[0].singleOrNull() ?: return null
        if (time != BigInteger.valueOf(blockTimestamp)) return null
        return "0x" + results[1].joinToString("") { it.toString(16).padStart(64, '0') }
    }

    /** A single uint word (a block timestamp), or null. */
    fun uint(hex: String): Long? {
        val words = words(hex) ?: return null
        if (words.size != 1 || words[0].bitLength() > 62) return null
        return words[0].toLong()
    }

    /**
     * The window to average a pool over when it can't answer [full]: as
     * far back as its oldest observation reaches at [blockTimestamp], at
     * most [full] — or null when that's shorter than [min] (or the oldest
     * observation is from the future).
     */
    fun fallbackWindow(blockTimestamp: Long, oldestObservation: Long, full: Int, min: Int): Int? {
        val reach = blockTimestamp - oldestObservation
        if (reach < min) return null
        return minOf(reach, full.toLong()).toInt()
    }

    /** One whole token0 in whole token1 at [tick]: 1.0001^tick, scaled by the two tokens' decimals. */
    fun tickPrice(tick: Long, decimals0: Int, decimals1: Int): BigDecimal {
        val ratio = Math.pow(1.0001, tick.toDouble())
        return BigDecimal(ratio, MC).scaleByPowerOfTen(decimals0 - decimals1)
    }

    /** [raw] base units with [decimals] decimals at [price] per whole token. */
    fun value(raw: BigInteger, decimals: Int, price: BigDecimal): BigDecimal =
        BigDecimal(raw, decimals).multiply(price, MC)

    /** A USD price in EUR, given EUR/USD (dollars per euro). */
    fun usdToEur(usd: BigDecimal, eurUsd: BigDecimal): BigDecimal = usd.divide(eurUsd, MC)

    /**
     * [value] in [currency] for the UI, always marked approximate: "≈ €12.40",
     * cents rounded half-up. An amount above zero that rounds to nothing
     * reads "< €0.01", never "≈ €0.00".
     */
    fun format(value: BigDecimal, currency: FiatCurrency, locale: Locale): String? {
        val code = currency.code ?: return null
        val format = NumberFormat.getCurrencyInstance(locale).apply {
            this.currency = Currency.getInstance(code)
            minimumFractionDigits = 2
            maximumFractionDigits = 2
            roundingMode = RoundingMode.HALF_UP
        }
        val cents = value.setScale(2, RoundingMode.HALF_UP)
        return if (value.signum() > 0 && cents.signum() == 0) {
            Strings.get(R.string.fiat_less_than, format.format(BigDecimal("0.01")))
        } else {
            Strings.get(R.string.fiat_approximately, format.format(cents))
        }
    }

    private fun words(hex: String): List<BigInteger>? {
        if (!hex.startsWith("0x") || (hex.length - 2) % 64 != 0 || hex.length == 2) return null
        val digits = hex.substring(2)
        if (!digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return digits.chunked(64).map { BigInteger(it, 16) }
    }

    private val BIG_TWO = BigInteger.valueOf(2)

    private val TWO_256 = BigInteger.ONE.shiftLeft(256)

    private fun signed(word: BigInteger): BigInteger = if (word.testBit(255)) word - TWO_256 else word

    /** Uniswap v3's tick range. */
    private const val MAX_TICK = 887_272L

    /** Older than this, a Chainlink answer is no price: its heartbeats are an hour to a day. */
    const val MAX_FEED_AGE_SECONDS = 48L * 3600

    private const val CLOCK_SLACK_SECONDS = 600L
}

/**
 * The wallet's approximate values (#439), off by default: [currency] is
 * the user's choice, and [quotes] the prices last read for it — null
 * while off and before the first read, empty when nothing could be read.
 *
 * While [currency] is [FiatCurrency.OFF], [refresh] returns before
 * anything is asked: no price request leaves the device. With a currency
 * on, prices are read from the chain through [WalletRpc] — the same
 * verified path as balances — each chain's reads pinned to one block a
 * little behind its head, so every RPC is asked the same question; an
 * answer that wasn't verified (one RPC's word) is dropped. They are kept
 * for [TTL_MS], in memory only, and only one read runs at a time. A
 * price a read couldn't get keeps the previous one for up to [HOLD_MS];
 * with none, that token has no value. It never holds anything up.
 */
class FiatPrices internal constructor(
    private val loadSetting: suspend () -> FiatCurrency,
    private val saveSetting: suspend (FiatCurrency) -> Unit,
    private val rpc: () -> WalletRpc,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
    private val wallClockSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val _currency = MutableStateFlow(FiatCurrency.OFF)
    val currency: StateFlow<FiatCurrency> = _currency.asStateFlow()

    private val _quotes = MutableStateFlow<FiatQuotes?>(null)
    val quotes: StateFlow<FiatQuotes?> = _quotes.asStateFlow()

    private val mutex = Mutex()
    private var loaded = false
    private var readAt: Long? = null
    private var generation = 0L

    /** The generation a read is running for, if one is: never two at once for the same choice. */
    private var reading: Long? = null

    /** The last read missed a price an earlier one had (or found none): try again after [RETRY_MS]. */
    private var retrySoon = false

    /** Each price on show, and when (on [elapsed]) it was read. */
    private var held: Map<String, Pair<BigDecimal, Long>> = emptyMap()

    /** The stored choice, read once. */
    suspend fun load() {
        mutex.withLock {
            if (loaded) return
            loaded = true
            _currency.value = loadSetting()
        }
    }

    /** Choose [value] (Off forgets every price read). */
    suspend fun set(value: FiatCurrency) {
        load()
        mutex.withLock {
            generation++
            _currency.value = value
            _quotes.value = null
            readAt = null
            reading = null
            retrySoon = false
            held = emptyMap()
        }
        saveSetting(value)
    }

    /**
     * Read prices again if a currency is chosen, no read is already
     * running, and the last read is older than [TTL_MS] (or came back
     * short more than [RETRY_MS] ago). Off: does nothing at all.
     *
     * A price the new read couldn't get (an RPC outage, a pool that can't
     * answer for a moment) keeps the one read before it, for up to
     * [HOLD_MS] after that one was read — so a short outage doesn't wipe
     * every value on screen, and a source that stays dead doesn't leave
     * an old price up for long.
     */
    suspend fun refresh() {
        load()
        val (currency, mine) = mutex.withLock {
            val c = _currency.value
            if (c == FiatCurrency.OFF) return
            if (reading == generation) return
            val at = readAt
            val now = elapsed()
            val wait = if (retrySoon || _quotes.value?.perToken.isNullOrEmpty()) RETRY_MS else TTL_MS
            if (at != null && now - at in 0 until wait) return
            readAt = now
            reading = generation
            c to generation
        }
        val read = try {
            read(currency)
        } catch (e: Throwable) {
            // Cancelled (or anything else): this read is over, the next one may start.
            withContext(NonCancellable) {
                mutex.withLock {
                    if (mine == generation) {
                        readAt = null
                        reading = null
                    }
                }
            }
            throw e
        }
        mutex.withLock {
            // Switched off, or to the other currency, while this read ran: file nothing.
            if (mine != generation) return
            reading = null
            val now = elapsed()
            val kept = held.filter { (key, v) -> key !in read && now - v.second in 0 until HOLD_MS }
            held = read.mapValues { it.value to now } + kept
            retrySoon = read.isEmpty() || kept.isNotEmpty()
            _quotes.value = FiatQuotes(currency, held.mapValues { it.value.first })
        }
    }

    private suspend fun read(currency: FiatCurrency): Map<String, BigDecimal> = coroutineScope {
        val rpc = rpc()
        val chains = PriceFeeds.byToken.values.map { chainOf(it) }.toSet()
        val blocks = chains.map { id -> async { id to pinnedBlock(rpc, id) } }.awaitAll().toMap()
        // The age of a feed is judged against its block's time, not the phone's clock.
        val times = blocks.mapNotNull { (id, b) -> b?.let { async { id to blockTime(rpc, id, it) } } }
            .awaitAll().toMap()
        val feeds = buildSet {
            PriceFeeds.byToken.values.forEach {
                when (it) {
                    is PriceFeeds.Chainlink -> add(it)
                    is PriceFeeds.Twap -> add(it.quoteUsd)
                }
            }
            if (currency == FiatCurrency.EUR) add(PriceFeeds.EUR_USD)
        }
        val twaps = PriceFeeds.byToken.values.filterIsInstance<PriceFeeds.Twap>().toSet()
        val usd = feeds.map { f -> async { f to chainlink(rpc, f, blocks[f.chainId], times[f.chainId]) } }
        val ticks = twaps.map { t -> async { t to twap(rpc, t, blocks[t.chainId], times[t.chainId]) } }
        val feedPrices = usd.awaitAll().toMap()
        val twapPrices = ticks.awaitAll().toMap()
        val eurUsd = feedPrices[PriceFeeds.EUR_USD]
        PriceFeeds.byToken.mapNotNull { (key, source) ->
            val priceUsd = when (source) {
                is PriceFeeds.Chainlink -> feedPrices[source]
                is PriceFeeds.Twap -> twapPrices[source]?.let { p -> feedPrices[source.quoteUsd]?.let { p.multiply(it) } }
                else -> null
            } ?: return@mapNotNull null
            val price = when (currency) {
                FiatCurrency.USD -> priceUsd
                FiatCurrency.EUR -> eurUsd?.let { FiatMath.usdToEur(priceUsd, it) } ?: return@mapNotNull null
                FiatCurrency.OFF -> return@mapNotNull null
            }
            key to price
        }.toMap()
    }

    private fun chainOf(source: Any): Long = when (source) {
        is PriceFeeds.Chainlink -> source.chainId
        is PriceFeeds.Twap -> source.chainId
        else -> error("unknown price source")
    }

    private suspend fun pinnedBlock(rpc: WalletRpc, chainId: Long): String? = guarded {
        val head = rpc.blockNumber(chainId).value
        "0x" + maxOf(0L, head - PINNED_BEHIND).toString(16)
    }

    /**
     * [block]'s own timestamp, from its header, or null unless trusted. Not
     * an `eth_call` to Multicall3's `getCurrentBlockTimestamp()`: Colibri's
     * proven EVM answers `block.timestamp` with 0.
     */
    private suspend fun blockTime(rpc: WalletRpc, chainId: Long, block: String): Long? = guarded {
        val r = rpc.blockTimestamp(chainId, block.removePrefix("0x").toLong(16))
        if (trusted(r.trust)) r.value else null
    }

    /**
     * [feed]'s price at [block], its age judged against [blockTime] — a
     * phone clock minutes slow (or days fast) doesn't drop a fresh feed —
     * or, when the block's time couldn't be read, against the phone's.
     */
    private suspend fun chainlink(rpc: WalletRpc, feed: PriceFeeds.Chainlink, block: String?, blockTime: Long?): BigDecimal? = guarded {
        if (block == null) return@guarded null
        val r = rpc.call(feed.chainId, JSONObject().put("to", feed.address).put("data", PriceFeeds.LATEST_ROUND_DATA), block)
        if (!trusted(r.trust)) return@guarded null
        FiatMath.chainlinkPrice(r.value, feed.decimals, blockTime ?: wallClockSeconds())
    }

    /**
     * The pool's [PriceFeeds.TWAP_SECONDS] average; and when it can't go
     * that far back — a pool keeping only a couple of observations (BZZ /
     * WETH keeps two) reverts `OLD` while its last two trades are less
     * than that apart — the average over as far back as it does reach,
     * down to [PriceFeeds.MIN_TWAP_SECONDS]. Only a revert takes that
     * second path: a full-window answer that came back unverified is no
     * price, not a reason to ask three more questions.
     *
     * Every `observe()` goes through [PriceFeeds.timed], and counts only
     * if the EVM that ran it had [blockTime], the block header's time: a
     * proof covers the pool's storage, not the time an EVM is given, and
     * Colibri's runs at 0 — where `observe()` doesn't revert `OLD` but
     * answers the spot tick as the average. That answer is no price.
     * Without the header's time, nothing is asked.
     */
    private suspend fun twap(rpc: WalletRpc, t: PriceFeeds.Twap, block: String?, blockTime: Long?): BigDecimal? = guarded {
        if (block == null || blockTime == null) return@guarded null
        val full = try {
            rpc.call(t.chainId, JSONObject().put("to", PriceFeeds.MULTICALL3).put("data", PriceFeeds.timed(t.pool, PriceFeeds.OBSERVE)), block)
        } catch (e: ChainRpcException.Rpc) {
            if (!e.deterministic) throw e
            null
        }
        val tick = if (full != null) {
            if (!trusted(full.trust)) return@guarded null
            FiatMath.timedResult(full.value, blockTime)?.let { FiatMath.twapTick(it, PriceFeeds.TWAP_SECONDS) }
        } else {
            val window = fallbackWindow(rpc, t, block, blockTime) ?: return@guarded null
            poolCall(rpc, t, PriceFeeds.MULTICALL3, PriceFeeds.timed(t.pool, PriceFeeds.observe(window)), block)
                ?.let { FiatMath.timedResult(it, blockTime) }
                ?.let { FiatMath.twapTick(it, window) }
        }
        tick?.let { FiatMath.tickPrice(it, t.baseDecimals, t.quoteDecimals) }
    }

    /** How far back [t]'s pool can average at [block], or null for too short a reach. */
    private suspend fun fallbackWindow(rpc: WalletRpc, t: PriceFeeds.Twap, block: String, time: Long): Int? = coroutineScope {
        val slot0 = poolCall(rpc, t, t.pool, PriceFeeds.SLOT0, block) ?: return@coroutineScope null
        val oldest = FiatMath.oldestObservationIndex(slot0) ?: return@coroutineScope null
        // Not filled yet (the ring was grown and hasn't wrapped): slot 0 is the oldest.
        val at = poolCall(rpc, t, t.pool, PriceFeeds.observations(oldest), block)?.let(FiatMath::observationTimestamp)
            ?: if (oldest == 0) null else poolCall(rpc, t, t.pool, PriceFeeds.observations(0), block)?.let(FiatMath::observationTimestamp)
        if (at == null) return@coroutineScope null
        FiatMath.fallbackWindow(time, at, PriceFeeds.TWAP_SECONDS, PriceFeeds.MIN_TWAP_SECONDS)
    }

    /** An `eth_call` to [to] on [t]'s chain at [block]; null unless the answer is trusted. */
    private suspend fun poolCall(rpc: WalletRpc, t: PriceFeeds.Twap, to: String, data: String, block: String): String? {
        val r = rpc.call(t.chainId, JSONObject().put("to", to).put("data", data), block)
        return if (trusted(r.trust)) r.value else null
    }

    private suspend fun <T> guarded(block: suspend () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: ChainRpcException) {
        null
    } catch (e: Exception) {
        // A price is never worth more than a missing value.
        Log.w(TAG, "price read failed", e)
        null
    }

    companion object {
        private const val TAG = "FiatPrices"

        /** How long prices are kept before the next read: five minutes. */
        const val TTL_MS = 5 * 60_000L

        /** After a read that found nothing (or missed a price it had before), how long before trying again. */
        const val RETRY_MS = 60_000L

        /** How long a price a later read couldn't get is still shown: half an hour. */
        const val HOLD_MS = 30 * 60_000L

        /** Blocks behind the head the reads are pinned to, so every RPC has the block. */
        private const val PINNED_BEHIND = 2L

        /**
         * Only a verified answer, or the user's own RPC's when no other RPC
         * answered differently ([undisputed]), prices anything. Every read is
         * pinned to one block, so a dissent is a real contradiction, not lag.
         */
        internal fun trusted(trust: ChainTrust): Boolean = trust.undisputed

        @Volatile
        private var instance: FiatPrices? = null

        fun get(context: Context): FiatPrices {
            val app = context.applicationContext
            return instance ?: synchronized(this) {
                val store = FiatSettingStore.get(app)
                instance ?: FiatPrices(store::load, store::save, { WalletRpc(ChainDataRouter.get(app)) }).also { instance = it }
            }
        }
    }
}
