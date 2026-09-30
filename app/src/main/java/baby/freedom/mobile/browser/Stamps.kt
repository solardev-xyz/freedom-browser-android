package baby.freedom.mobile.browser

import android.util.Log
import baby.freedom.mobile.node.INodeService
import baby.freedom.swarm.NodeInfo
import baby.freedom.swarm.SpendPermit
import baby.freedom.swarm.SwarmNode
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.util.Locale
import java.util.UUID
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
    /** The same in PLUR; zero for none. */
    val depositPlur: BigInteger = BigInteger.ZERO,
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
        depositPlur = deposit.max(BigInteger.ZERO),
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
 * ([INodeService.stampCall]), and the one spend (a stamp buy or extend, a
 * chequebook deposit, #117, or connecting a stamp the wallet bought, #115)
 * in flight
 * — held here, not by a screen, so it outlives leaving the page, and so
 * there's only ever one. [MainActivity] keeps [service] and [node] current.
 */
internal object StampClient {
    @Volatile
    var service: INodeService? = null

    /**
     * The Swarm node's last published state, as `:node`'s callback reports
     * it — kept current while the Activity is stopped too (the callback
     * stays registered until it's destroyed), unlike a composable's
     * parameter, which only moves on recomposition (#291 R4-M1).
     */
    val node = MutableStateFlow(NodeInfo())

    /**
     * This process stopped hearing from `:node` (an unbind, the Activity's
     * destroy, or `:node` dying): drop the binder and reset [node] to
     * Stopped, as a fresh Activity's own flow used to start. [node] is
     * process-wide, so without this a recreated Activity (the task swiped
     * away while the process stays cached) would first show the previous
     * binding's last state until its own bind reports (#291 R5-M1).
     */
    fun detach() {
        service = null
        node.value = NodeInfo()
    }

    sealed interface Answer {
        data class Ok(val json: JSONObject) : Answer
        /**
         * [unbound]: this process holds no binder to `:node` right now —
         * not proof `:node` is gone: the Activity that binds it may just
         * be being recreated while `:node` runs on.
         */
        data class Failed(val message: String, val unbound: Boolean = false) : Answer
    }

    /** Runs [method] on the node and waits up to [timeoutMs]. Blocking; never throws. */
    fun call(method: String, args: JSONObject = JSONObject(), timeoutMs: Long = READ_TIMEOUT_MS): Answer {
        val binder = service ?: return Answer.Failed(NOT_BOUND, unbound = true)
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

    /**
     * Registers the stamps this account already owns on Gnosis with the
     * node (#118): bought on another device, or before a reinstall. No
     * transaction. Blocking; the ids it found, or why it couldn't look.
     */
    private fun discoverNow(): Result<List<String>> {
        // Names this search to `:node`, so an outcome it reports later is this one's.
        val id = UUID.randomUUID().toString()
        return when (val a = call("discover", JSONObject().put("id", id), timeoutMs = DISCOVER_TIMEOUT_MS)) {
            is Answer.Ok -> Result.success(registeredIds(a.json))
            is Answer.Failed -> {
                if (a.message == TIMED_OUT) {
                    // The page stopped waiting, but `:node` is most likely
                    // still scanning — and may yet adopt a chequebook and
                    // restart the gateway. The search stays Running (so no
                    // publish starts under it) until `:node` says it ended,
                    // and then shows how it ended.
                    val end = awaitNodeWorkEnd(
                        ask = { call("discovering", JSONObject().put("id", id), timeoutMs = DISCOVERING_TIMEOUT_MS) },
                    ) { Thread.sleep(DISCOVER_POLL_MS) }
                    overranOutcome(end)
                } else {
                    Result.failure(IllegalStateException(a.message))
                }
            }
        }
    }

    private fun registeredIds(json: JSONObject): List<String> =
        json.optJSONArray("registered")?.let { ids -> (0 until ids.length()).mapNotNull { normalizeBatchId(ids.optString(it)) } }
            .orEmpty()

    /**
     * Waits, [pause] between asks, until [ask] (`:node`'s "discovering" or
     * "gatewayWork") says the work it asks after runs no more
     * ([nodeWorkStillRunning]). Its last answer: the one that said so.
     */
    internal fun awaitNodeWorkEnd(ask: () -> Answer, pause: () -> Unit): Answer {
        var a: Answer
        do {
            pause()
            a = ask()
        } while (nodeWorkStillRunning(a))
        return a
    }

    /**
     * Is `:node`'s search (or buy) still running, by its answer to
     * "discovering" (or "gatewayWork")? Yes when it says so; when it didn't
     * answer in time (busy, not gone); and while this process isn't bound
     * to it ([Answer.Failed.unbound]): the Activity that binds it may be
     * being recreated while `:node` works on, and it answers again once
     * it's rebound. No once it says not, or once the call to it fails —
     * the `:node` process went away, and the work with it (a `:node`
     * started again says not running).
     */
    internal fun nodeWorkStillRunning(a: Answer): Boolean = when (a) {
        is Answer.Ok -> a.json.optBoolean("running", false)
        is Answer.Failed -> a.message == TIMED_OUT || a.unbound
    }

    /**
     * How a search that outlived the page's wait ended, from `:node`'s
     * last "discovering" answer ([awaitNodeWorkEnd]): what it found, or
     * why it failed, if `:node` kept its outcome; [DISCOVER_OVERRAN] if
     * not (`:node` went away mid-search).
     */
    internal fun overranOutcome(end: Answer): Result<List<String>> {
        val outcome = (end as? Answer.Ok)?.json?.optJSONObject("outcome")
            ?: return Result.failure(IllegalStateException(DISCOVER_OVERRAN))
        return outcome.optString("error").takeIf { it.isNotEmpty() }
            ?.let { Result.failure(IllegalStateException(it)) }
            ?: Result.success(registeredIds(outcome))
    }

    /**
     * A search for the account's own stamps: none asked, one running, or
     * what the last one found — for [Finished.account], the node's account
     * when it was asked, since the node can restart as another identity
     * (a wallet added, removed or replaced) while this outlives the page.
     */
    sealed interface Discovery {
        data object Idle : Discovery
        data object Running : Discovery
        data class Finished(val account: String, val found: Result<List<String>>) : Discovery

        /** What to show while the node runs as [account]: another account's outcome is not this one's. */
        fun forAccount(account: String): Discovery =
            if (this is Finished && !this.account.equals(account, ignoreCase = true)) Idle else this
    }

    private val _discovery = MutableStateFlow<Discovery>(Discovery.Idle)

    /**
     * The search, held here like [spend] so it outlives the list scrolling
     * or the page closing, and so there's only ever one. It never runs
     * alongside a spend (nor a spend alongside it): ant's discover can
     * deploy the chequebook as it registers a batch, which must not go
     * out under a buy's permit. `:node` enforces the same.
     */
    val discovery: StateFlow<Discovery> = _discovery.asStateFlow()

    /**
     * Starts a search for the stamps of [account], the node's account as
     * the page shows it. False if one, or a spend, is already running, or
     * a publish is uploading.
     */
    fun discover(account: String): Boolean {
        synchronized(this) {
            if (!canRestartGateway(_spend.value, _discovery.value, Publisher.state.value)) return false
            _discovery.value = Discovery.Running
        }
        scope.launch {
            val found = try {
                discoverNow()
            } catch (t: Throwable) {
                Log.w(TAG, "stamp discover failed: ${t.javaClass.simpleName}")
                Result.failure(IllegalStateException("Something went wrong"))
            }
            _discovery.compareAndSet(Discovery.Running, Discovery.Finished(account, found))
        }
        return true
    }

    /** Whether a buy or extend may start now: nothing else of the node's stamp work is running. */
    fun canSpend(spend: Spend, discovery: Discovery): Boolean =
        spend !is Spend.Running && discovery !is Discovery.Running

    /**
     * Whether [spend] or [discovery] may restart the node's gateway: a buy
     * or a connect (the first one sets up the chequebook, #115) or a search
     * (which can adopt one) ends with `:node` reloading it, which cuts
     * every request open on it — a publish's `POST /bzz` too.
     */
    fun mayRestartGateway(spend: Spend, discovery: Discovery): Boolean =
        (spend is Spend.Running && spend.kind.mayRestartGateway) || discovery is Discovery.Running

    /**
     * Blocks while `:node` runs a buy or a search that may end by
     * reloading the gateway ("gatewayWork"), for a publish about to send
     * its upload (#222 R4-F1). [Publisher.claim] already refuses a publish
     * under stamp work this process knows is running, but that isn't all
     * of it: a UI process started again since knows nothing of what
     * `:node` was already doing. Nothing new can start meanwhile — this
     * process is the only one that starts stamp work, and it refuses to
     * while a publish runs ([canRestartGateway]) — so once this returns,
     * the upload can't be cut off by one. [onWaiting] is called once, the
     * first time `:node` says (or, busy, doesn't deny) that such work runs.
     *
     * Unlike a search's own wait ([nodeWorkStillRunning]), being unbound
     * isn't taken as "still running" for long: it's most often the node
     * switched off in Settings, and then there's no work to wait for and
     * the upload should fail at once rather than hang under a false
     * "waiting for the node" card (#222 R5-F1). A binder that's only gone
     * while the Activity is recreated is back within a few asks, so an
     * unbound answer is asked again at most [MAX_UNBOUND_ASKS] times in a
     * row before the publish goes ahead (and fails on its own if `:node`
     * is gone).
     */
    internal fun awaitGatewayQuiet(
        ask: () -> Answer = ::askGatewayWork,
        pause: () -> Unit = { Thread.sleep(DISCOVER_POLL_MS) },
        onWaiting: () -> Unit = {},
    ) {
        var waited = false
        var unboundAsks = 0
        while (true) {
            val a = ask()
            if (a is Answer.Failed && a.unbound) {
                if (++unboundAsks > MAX_UNBOUND_ASKS) return
            } else {
                if (!nodeWorkStillRunning(a)) return
                unboundAsks = 0
                if (!waited) {
                    waited = true
                    onWaiting()
                }
            }
            pause()
        }
    }

    /** How many unbound answers in a row [awaitGatewayQuiet] waits through (~15 s). */
    internal const val MAX_UNBOUND_ASKS = 3

    private fun askGatewayWork(): Answer = call("gatewayWork", timeoutMs = DISCOVERING_TIMEOUT_MS)

    /**
     * Whether a buy or a search for owned stamps may start now: nothing
     * else of the node's stamp work is running, and no publish is
     * uploading through the gateway it may restart. [Publisher] checks
     * the other way round, under this object's lock, so neither starts
     * over the other.
     */
    fun canRestartGateway(spend: Spend, discovery: Discovery, publishing: Publisher.State): Boolean =
        canSpend(spend, discovery) && publishing !is Publisher.State.Running

    enum class Kind {
        Buy, Extend, Deposit, Connect;

        /** A buy or a connect may end by reloading the gateway: the first one sets up the chequebook (#115). */
        val mayRestartGateway: Boolean get() = this == Buy || this == Connect
    }

    sealed interface Spend {
        data object Idle : Spend
        data class Running(val kind: Kind, val batchId: String?) : Spend
        data class Done(val kind: Kind, val batchId: String?) : Spend
        data class Failed(val kind: Kind, val batchId: String?, val message: String) : Spend
    }

    private val _spend = MutableStateFlow<Spend>(Spend.Idle)
    val spend: StateFlow<Spend> = _spend.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Buys the batch [quote] priced, as the user just confirmed. False if a
     * spend or a discover is already running, or a publish is uploading.
     */
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

    /**
     * Connects batch [batchId], which the wallet bought for the node
     * through SwarmNodeFunder (#115, [SwarmFunding]), so the node stamps
     * with it; a first one also sets up the node's chequebook, and so may
     * reload the gateway. False if a spend or a discover is already
     * running, or a publish is uploading.
     */
    fun connect(batchId: String): Boolean = start(Kind.Connect, batchId) { JSONObject().put("batchId", batchId) }

    /** Forget a finished spend's outcome once it's been shown. */
    fun acknowledge() {
        _spend.value.let { if (it is Spend.Done || it is Spend.Failed) _spend.compareAndSet(it, Spend.Idle) }
    }

    private fun start(kind: Kind, batchId: String?, args: () -> JSONObject): Boolean {
        val running = Spend.Running(kind, batchId)
        synchronized(this) {
            if (!canSpend(_spend.value, _discovery.value)) return false
            // A buy or connect may restart the gateway (the chequebook): not under a publish's upload.
            if (kind.mayRestartGateway && !canRestartGateway(_spend.value, _discovery.value, Publisher.state.value)) return false
            _spend.value = running
        }
        scope.launch {
            val method = when (kind) {
                Kind.Buy -> "buy"
                Kind.Extend -> "extend"
                Kind.Deposit -> "deposit"
                Kind.Connect -> "connect"
            }
            val outcome = try {
                spendOutcome(kind, batchId, call(method, args(), SPEND_TIMEOUT_MS)) {
                    awaitNodeWorkEnd(::askGatewayWork) { Thread.sleep(DISCOVER_POLL_MS) }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "stamp $method failed: ${t.javaClass.simpleName}")
                Spend.Failed(kind, batchId, "Something went wrong")
            }
            _spend.compareAndSet(running, outcome)
        }
        return true
    }

    /**
     * What a spend of [kind] ended as, by `:node`'s answer [a]. Past the
     * deadline the node is most likely still on it; and a buy or connect
     * may yet end by reloading the gateway (the chequebook), so for those
     * it first [awaitBuyEnd]s — the spend stays Running meanwhile, so no publish
     * starts under it — until `:node` says it ended (#222 R4-F1).
     */
    internal fun spendOutcome(kind: Kind, batchId: String?, a: Answer, awaitBuyEnd: () -> Unit): Spend = when (a) {
        is Answer.Ok -> Spend.Done(kind, batchId)
        is Answer.Failed -> when {
            a.message != TIMED_OUT -> Spend.Failed(kind, batchId, a.message)
            kind.mayRestartGateway -> {
                awaitBuyEnd()
                Spend.Failed(kind, batchId, if (kind == Kind.Connect) CONNECT_OVERRAN else BUY_OVERRAN)
            }
            else -> Spend.Failed(kind, batchId, stillSendingMessage(kind))
        }
    }

    /**
     * The outcome of an extend or deposit that outlived [SPEND_TIMEOUT_MS]:
     * the node is most likely still on it. (A buy's waits on for `:node`
     * to end it instead: [BUY_OVERRAN].) A deposit's leads with
     * [SwarmNode.DEPOSIT_MAYBE_SENT], so it reads as "didn't report back",
     * not as a failure (#117).
     */
    internal fun stillSendingMessage(kind: Kind): String = when (kind) {
        Kind.Deposit -> "${SwarmNode.DEPOSIT_MAYBE_SENT} (the node is still sending it). " +
            "The chequebook's balance shows it once it confirms"
        else -> "The node is still sending the transactions. The list shows the stamp once they confirm."
    }

    private const val NOT_BOUND = "The Swarm node isn't running"

    /**
     * How a buy that outlived [SPEND_TIMEOUT_MS] ended, once `:node` said
     * it had: `:node` doesn't keep a buy's outcome for the app to read.
     */
    internal const val BUY_OVERRAN =
        "it took longer than expected, and ended without telling the app how it went. " +
            "The list shows the stamp if it was bought."

    /** The same for a connect (#115): `:node` doesn't keep its outcome either. */
    internal const val CONNECT_OVERRAN =
        "it took longer than expected, and ended without telling the app how it went. " +
            "If the stamp doesn't show in the list, try Connect again."
    internal const val TIMED_OUT = "The Swarm node didn't answer in time"
    internal const val DISCOVER_OVERRAN =
        "The search took longer than expected, and ended without telling the app what it found. " +
            "The list shows any stamps it registered."

    /**
     * How often `:node` is asked after a search that outlived
     * [DISCOVER_TIMEOUT_MS], a buy that outlived [SPEND_TIMEOUT_MS], or
     * stamp work a publish waits for; and how long each ask waits.
     */
    private const val DISCOVER_POLL_MS = 5_000L
    private const val DISCOVERING_TIMEOUT_MS = 15_000L
    const val READ_TIMEOUT_MS = 60_000L

    /** A discover scans the account's xBZZ transfers since the token's deploy, then reads each batch found. */
    const val DISCOVER_TIMEOUT_MS = 3 * 60_000L

    /**
     * A spend's: up to five transactions, each waited on for up to a
     * minute by ant, plus its chain reads. Past this the page stops
     * waiting for the answer — the node goes on, and the list shows what
     * it bought. A buy still shows as running until `:node` says it ended.
     */
    const val SPEND_TIMEOUT_MS = 10 * 60_000L
    private const val MAX_ANSWER_BYTES = 256 * 1024
    private const val TAG = "StampClient"
}
