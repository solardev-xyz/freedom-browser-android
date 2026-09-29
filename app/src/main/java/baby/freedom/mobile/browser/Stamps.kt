package baby.freedom.mobile.browser

import android.util.Log
import baby.freedom.mobile.node.INodeService
import baby.freedom.swarm.SpendPermit
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/*
 * Postage stamps (#116): the node's batches, what a new one or an
 * extension costs, and buying or extending one — after desktop's
 * stamp-manager.js / stamp-service.js and iOS's StampService, on ant's
 * own storage calls. ant pays in xDAI: it swaps what it needs for xBZZ
 * itself, so the funded xDAI from publish setup is all a user needs.
 * The UI lives in StampsScreen.kt.
 */

/** One of the node's postage batches, from the gateway's `GET /stamps`. */
internal data class PostageBatch(
    /** 64 lowercase hex, no `0x`. */
    val id: String,
    val usable: Boolean,
    val depth: Int,
    val bucketDepth: Int,
    /** The fullest bucket's fill (bee's `utilization`). */
    val utilization: Long,
    val immutable: Boolean,
    /** Seconds left, or null when the node couldn't read it from the chain. */
    val ttlSeconds: Long?,
) {
    /** What it holds, bee's effective size for its depth. */
    val capacityBytes: Long get() = effectiveStampBytes(depth)

    /** 4 KiB × 2^depth: what it would hold if every bucket filled evenly. */
    val theoreticalBytes: Long get() = 4096L shl depth

    /** How full it is, 0..1: the fullest bucket against a bucket's capacity. */
    val usedFraction: Double
        get() {
            val bucketCapacity = 1L shl (depth - bucketDepth).coerceIn(0, 62)
            return (utilization.toDouble() / bucketCapacity).coerceIn(0.0, 1.0)
        }
}

/**
 * The batches in a `/stamps` body, or null if it isn't one. ant fills in
 * `batchTTL` from the chain; when that read fails it reports ~10 years,
 * which is shown as unknown rather than believed.
 */
internal fun stampsFrom(body: String): List<PostageBatch>? {
    val stamps = runCatching { JSONObject(body).optJSONArray("stamps") }.getOrNull() ?: return null
    return (0 until stamps.length()).mapNotNull { i ->
        val o = stamps.optJSONObject(i) ?: return@mapNotNull null
        val id = normalizeBatchId(o.optString("batchID")) ?: return@mapNotNull null
        val ttl = o.optLong("batchTTL", -1)
        PostageBatch(
            id = id,
            usable = o.optBoolean("usable"),
            depth = o.optInt("depth"),
            bucketDepth = o.optInt("bucketDepth", 16),
            utilization = o.optLong("utilization"),
            immutable = o.optBoolean("immutableFlag"),
            ttlSeconds = ttl.takeIf { it >= 0 && it != ANT_PLACEHOLDER_TTL },
        )
    }
}

/** ant's `batchTTL` stand-in when it can't read the chain (10 years). */
internal const val ANT_PLACEHOLDER_TTL = 315_360_000L

internal fun normalizeBatchId(id: String): String? = id.trim().removePrefix("0x").removePrefix("0X").lowercase()
    .takeIf { s -> s.length == 64 && s.all { it in '0'..'9' || it in 'a'..'f' } }

/** `abcd1234…9f8e7d6c`, for a list row; the detail shows the whole id. */
internal fun shortBatchId(id: String): String = if (id.length > 16) "${id.take(8)}…${id.takeLast(8)}" else id

/**
 * Effective capacity at [depth], bee-js's table (1 GB = 10^9 bytes): what
 * an immutable batch reliably holds before its fullest bucket is full,
 * well below [PostageBatch.theoreticalBytes] at small depths.
 */
internal fun effectiveStampBytes(depth: Int): Long = when {
    depth < 17 -> 0
    depth > 34 -> (4096.0 * (1L shl depth) * 0.9).toLong()
    else -> (EFFECTIVE_GB[depth - 17] * 1e9).toLong()
}

private val EFFECTIVE_GB = doubleArrayOf(
    0.00004089, 0.00609, 0.10249, 0.62891, 2.38, 7.07, 18.24, 43.04, 96.5,
    208.52, 435.98, 908.81, 1870.0, 3810.0, 7730.0, 15610.0, 31430.0, 63150.0,
)

/** Bytes in 1000-based units, as bee and the other Freedom apps show them. */
internal fun formatStampBytes(bytes: Long): String {
    fun one(v: Double) = if (v >= 100) String.format(Locale.US, "%.0f", v) else String.format(Locale.US, "%.1f", v).removeSuffix(".0")
    return when {
        bytes >= 1_000_000_000_000 -> "${one(bytes / 1e12)} TB"
        bytes >= 1_000_000_000 -> "${one(bytes / 1e9)} GB"
        bytes >= 1_000_000 -> "${one(bytes / 1e6)} MB"
        bytes >= 1_000 -> "${one(bytes / 1e3)} kB"
        else -> "$bytes B"
    }
}

/** Time left in its two largest units ("12 days 4 hours"), or "Expired". */
internal fun formatStampTtl(seconds: Long): String {
    if (seconds <= 0) return "Expired"
    fun unit(n: Long, name: String) = if (n == 1L) "1 $name" else "$n ${name}s"
    val days = seconds / 86_400
    val hours = seconds % 86_400 / 3_600
    val minutes = seconds % 3_600 / 60
    return when {
        days > 0 -> unit(days, "day") + if (hours > 0) " " + unit(hours, "hour") else ""
        hours > 0 -> unit(hours, "hour") + if (minutes > 0) " " + unit(minutes, "minute") else ""
        minutes > 0 -> unit(minutes, "minute")
        else -> "Under a minute"
    }
}

/** The sizes the buy screen offers (depths); the node service takes the same range. */
internal val STAMP_DEPTHS = (17..24).toList()

/** Buy durations, in days: two is the shortest that stays above the contract's one-day minimum if the price moves. */
internal val STAMP_BUY_DAYS = listOf(2L, 7L, 30L, 90L, 365L)
internal const val DEFAULT_STAMP_DEPTH = 20
internal const val DEFAULT_STAMP_DAYS = 30L

/** Extend durations, in days added. */
internal val STAMP_EXTEND_DAYS = listOf(1L, 7L, 30L, 90L)
internal const val DEFAULT_EXTEND_DAYS = 30L

internal fun daysLabel(days: Long) = if (days == 1L) "1 day" else "$days days"

/**
 * ant's price for a new batch or an extension (`ant_storage_quote` /
 * `ant_storage_topup_quote`): no transaction was sent to get it.
 */
internal data class StampQuote(
    val depth: Int,
    val days: Long,
    /** PLUR per chunk: what the buy or extension is then made with, so it charges what was shown. */
    val amountPerChunk: BigInteger,
    val totalCostBzz: String,
    /** The node's chequebook deposit a first buy also makes, or null for none. */
    val depositBzz: String?,
    /** xBZZ the node has, and how much more it swaps xDAI for. */
    val accountBzz: String,
    val neededBzz: String,
    /** The xDAI the account must hold: the swap plus ant's gas reserve. Also the most the swap may take. */
    val xdaiRequired: BigInteger,
    val xdaiRequiredDisplay: String,
    val accountXdai: String,
    val xdaiToSend: String,
    val sufficientFunds: Boolean,
)

internal fun stampQuoteFrom(o: JSONObject): StampQuote? = runCatching {
    val amount = BigInteger(o.getString("amount_per_chunk"))
    val deposit = BigInteger(o.optString("settlement_deposit_plur", "0").ifEmpty { "0" })
    if (amount.signum() <= 0) return null
    StampQuote(
        depth = o.getInt("depth"),
        days = o.getLong("days"),
        amountPerChunk = amount,
        totalCostBzz = o.getString("total_cost_bzz"),
        depositBzz = if (deposit.signum() > 0) o.optString("settlement_deposit_bzz") else null,
        accountBzz = o.optString("account_bzz_display"),
        neededBzz = o.optString("needed_bzz_display"),
        xdaiRequired = BigInteger(o.getString("xdai_required")),
        xdaiRequiredDisplay = o.getString("xdai_required_display"),
        accountXdai = o.optString("account_xdai_display"),
        xdaiToSend = o.optString("xdai_to_send_display"),
        sufficientFunds = o.getBoolean("sufficient_funds"),
    )
}.getOrNull()

/**
 * The confirmation's sentence on what a spend may cost in xDAI (#116):
 * ant's estimate, and the hard bound [baby.freedom.swarm.SpendGuard]
 * enforces — the swap at most [StampQuote.xdaiRequired], plus gas of at
 * most [SpendPermit.MAX_GAS_WEI] for each of the spend's transactions.
 */
internal fun spendCostText(q: StampQuote, buy: Boolean): String {
    val estimate = withUnit(q.xdaiRequiredDisplay, "xDAI")
    // The bound from the wei SpendGuard enforces, not ant's display
    // string, which ant truncates to 4 decimals (0.017899 → "0.0178").
    val bound = formatXdaiCeiling(q.xdaiRequired)
    val txs = SpendPermit.slotsFor(buy).size
    return "The node pays from its xDAI: it swaps what it needs for xBZZ, about $estimate including gas. " +
        "At most, it swaps $bound and pays up to ${formatXdai(SpendPermit.maxGasWei(buy))} of gas " +
        "on top, across up to $txs transactions."
}

/**
 * Wei as xDAI to at most 6 decimals, rounded *up*: for a stated upper
 * bound, which must never read below the amount it bounds.
 */
internal fun formatXdaiCeiling(wei: BigInteger): String {
    val xdai = BigDecimal(wei).movePointLeft(18).setScale(6, RoundingMode.UP).stripTrailingZeros()
    return "${xdai.toPlainString()} xDAI"
}

/** ant's amounts come as "0.0283 xBZZ" or a bare number; show them with their unit once. */
internal fun withUnit(amount: String, unit: String): String =
    amount.trim().let { if (it.endsWith(unit, ignoreCase = true)) it else "$it $unit" }

/**
 * The UI process's way to the node's storage calls, which run in `:node`
 * ([INodeService.stampCall]), and the one spend (a stamp buy or extend, or
 * a chequebook deposit, #117) in flight
 * — held here, not by a screen, so it outlives leaving the page, and so
 * there's only ever one. [MainActivity] keeps [service] current.
 */
internal object StampClient {
    @Volatile
    var service: INodeService? = null

    sealed interface Answer {
        data class Ok(val json: JSONObject) : Answer
        data class Failed(val message: String) : Answer
    }

    /** Runs [method] on the node and waits up to [timeoutMs]. Blocking; never throws. */
    fun call(method: String, args: JSONObject = JSONObject(), timeoutMs: Long = READ_TIMEOUT_MS): Answer {
        val binder = service ?: return Answer.Failed(NOT_BOUND)
        val pipe = try {
            binder.stampCall(method, args.toString())
        } catch (e: Exception) {
            null
        } ?: return Answer.Failed(NOT_BOUND)
        val raw = try {
            RadicleClient.readAll(pipe, timeoutMs, MAX_ANSWER_BYTES)
        } finally {
            runCatching { pipe.close() }
        }
        raw ?: return Answer.Failed(TIMED_OUT)
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return Answer.Failed("The Swarm node's answer couldn't be read")
        return o.optString("error").takeIf { it.isNotEmpty() }?.let { Answer.Failed(it) } ?: Answer.Ok(o)
    }

    enum class Kind { Buy, Extend, Deposit }

    sealed interface Spend {
        data object Idle : Spend
        data class Running(val kind: Kind, val batchId: String?) : Spend
        data class Done(val kind: Kind, val batchId: String?) : Spend
        data class Failed(val kind: Kind, val batchId: String?, val message: String) : Spend
    }

    private val _spend = MutableStateFlow<Spend>(Spend.Idle)
    val spend: StateFlow<Spend> = _spend.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Buys the batch [quote] priced, as the user just confirmed. False if a spend is already running. */
    fun buy(quote: StampQuote): Boolean = start(Kind.Buy, null) {
        JSONObject()
            .put("depth", quote.depth)
            .put("amountPerChunk", quote.amountPerChunk.toString())
            .put("maxSwapWei", quote.xdaiRequired.toString())
    }

    /** Extends [batchId] as [quote] priced it, as the user just confirmed. */
    fun extend(batchId: String, quote: StampQuote): Boolean = start(Kind.Extend, batchId) {
        JSONObject()
            .put("batchId", batchId)
            .put("amountPerChunk", quote.amountPerChunk.toString())
            .put("maxSwapWei", quote.xdaiRequired.toString())
    }

    /**
     * Deposits [amountPlur] into the chequebook [chequebook] (#117), as the
     * user just confirmed. False if a spend is already running.
     */
    fun deposit(chequebook: String, amountPlur: BigInteger): Boolean = start(Kind.Deposit, null) {
        JSONObject().put("chequebook", chequebook).put("amountPlur", amountPlur.toString())
    }

    /** Forget a finished spend's outcome once it's been shown. */
    fun acknowledge() {
        _spend.value.let { if (it is Spend.Done || it is Spend.Failed) _spend.compareAndSet(it, Spend.Idle) }
    }

    private fun start(kind: Kind, batchId: String?, args: () -> JSONObject): Boolean {
        val running = Spend.Running(kind, batchId)
        synchronized(this) {
            if (_spend.value is Spend.Running) return false
            _spend.value = running
        }
        scope.launch {
            val method = when (kind) {
                Kind.Buy -> "buy"
                Kind.Extend -> "extend"
                Kind.Deposit -> "deposit"
            }
            val outcome = try {
                when (val a = call(method, args(), SPEND_TIMEOUT_MS)) {
                    is Answer.Ok -> Spend.Done(kind, batchId)
                    // Past the deadline the node is most likely still on it.
                    is Answer.Failed -> Spend.Failed(
                        kind, batchId,
                        when {
                            a.message != TIMED_OUT -> a.message
                            kind == Kind.Deposit -> "The node is still sending the deposit. The chequebook's balance shows it once it confirms."
                            else -> "The node is still sending the transactions. The list shows the stamp once they confirm."
                        },
                    )
                }
            } catch (t: Throwable) {
                Log.w(TAG, "stamp $method failed: ${t.javaClass.simpleName}")
                Spend.Failed(kind, batchId, "Something went wrong")
            }
            _spend.compareAndSet(running, outcome)
        }
        return true
    }

    private const val NOT_BOUND = "The Swarm node isn't running"
    private const val TIMED_OUT = "The Swarm node didn't answer in time"
    const val READ_TIMEOUT_MS = 60_000L

    /**
     * A spend's: up to five transactions, each waited on for up to a
     * minute by ant, plus its chain reads. Past this the page stops
     * waiting — the node goes on, and the list shows what it bought.
     */
    const val SPEND_TIMEOUT_MS = 10 * 60_000L
    private const val MAX_ANSWER_BYTES = 256 * 1024
    private const val TAG = "StampClient"
}
