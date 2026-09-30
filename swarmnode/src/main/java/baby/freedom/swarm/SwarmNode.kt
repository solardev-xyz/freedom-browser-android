package baby.freedom.swarm

import android.util.Log
import java.io.File
import java.math.BigInteger
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Kotlin wrapper around the embedded ant light-node (`libant_ffi.so`,
 * bridged through [AntNative]).
 *
 * ant runs in-process and serves the same bee-shaped HTTP gateway the
 * previous bee-lite integration exposed, on the same fixed address
 * ([GATEWAY_URL]), so the browser layer is agnostic to the swap.
 *
 * The UI observes [state]. Because it's a [StateFlow], any new collector
 * immediately receives the current value — there is no edge to miss.
 */
class SwarmNode internal constructor(
    private val config: Config,
    private val ops: NodeOps,
    /**
     * A monotonic clock in ms, for how long an unconfirmed deposit blocks
     * another. The hold is persisted, so the app's node (the public
     * constructor) uses one every process reads alike and that keeps
     * counting through deep sleep: `SystemClock.elapsedRealtime()`, time
     * since boot, which a reboot restarts at 0 (see [holdElapsedMs]). The
     * `System.nanoTime()` default, which stops in deep sleep, is for JVM
     * tests only, where `SystemClock` isn't available.
     */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    /**
     * This boot's id (null: unknown), kept with a hold so a later boot
     * isn't mistaken for the one the hold's [clock] reading came from.
     */
    private val bootId: () -> String? = { null },
) {
    constructor(config: Config) : this(
        config,
        NodeOps.Native,
        { android.os.SystemClock.elapsedRealtime() },
        { kernelBootId() },
    )

    /** The native calls [SwarmNode] makes; swapped for a fake in tests. */
    internal interface NodeOps {
        fun seed(antDir: File)
        fun init(dataDir: String): Long
        fun initWithIdentity(dataDir: String, identity: ByteArray): Long
        fun accountInfo(handle: Long): String?
        fun startGateway(handle: Long, apiAddr: String, lightMode: Boolean, gnosisRpc: String)
        fun agentString(handle: Long): String?
        fun peerCount(handle: Long): Int
        fun stopGateway(handle: Long)
        fun shutdown(handle: Long)
        fun storageStatus(handle: Long): String
        fun settlementStatus(handle: Long): String
        fun storageQuote(handle: Long, gnosisRpc: String, depth: Int, days: Long): String
        fun storageTopupQuote(handle: Long, gnosisRpc: String, days: Long): String
        fun storageValidity(handle: Long, gnosisRpc: String): String
        fun storageBuyXdai(handle: Long, gnosisRpc: String, depth: Int, amountPerChunk: String, immutable: Boolean): String
        fun storageTopupXdai(handle: Long, gnosisRpc: String, amountPerChunk: String): String
        fun storageConnectBatch(handle: Long, gnosisRpc: String, batchId: String): String
        fun storageDiscover(handle: Long, gnosisRpc: String): String

        /**
         * One request to the node's own gateway ([GATEWAY_URL] + [path]),
         * from this process: its status code and body, or null when it
         * couldn't be reached. Never logs the path's query.
         */
        fun gateway(method: String, path: String, timeoutMs: Int): GatewayAnswer?

        object Native : NodeOps {
            override fun seed(antDir: File) = BootnodeSeeder.seedIfEmpty(antDir)
            override fun init(dataDir: String) = AntNative.init(dataDir)
            override fun initWithIdentity(dataDir: String, identity: ByteArray) =
                AntNative.initWithIdentity(dataDir, identity)
            override fun accountInfo(handle: Long) = AntNative.accountInfo(handle)
            override fun startGateway(handle: Long, apiAddr: String, lightMode: Boolean, gnosisRpc: String) =
                AntNative.startGateway(handle, apiAddr, lightMode, gnosisRpc)
            override fun agentString(handle: Long) = AntNative.agentString(handle)
            override fun peerCount(handle: Long) = AntNative.peerCount(handle)
            override fun stopGateway(handle: Long) = AntNative.stopGateway(handle)
            override fun shutdown(handle: Long) = AntNative.shutdown(handle)
            override fun storageStatus(handle: Long) = AntNative.storageStatus(handle)
            override fun settlementStatus(handle: Long) = AntNative.settlementStatus(handle)
            override fun storageQuote(handle: Long, gnosisRpc: String, depth: Int, days: Long) =
                AntNative.storageQuote(handle, gnosisRpc, depth, days)
            override fun storageTopupQuote(handle: Long, gnosisRpc: String, days: Long) =
                AntNative.storageTopupQuote(handle, gnosisRpc, days)
            override fun storageValidity(handle: Long, gnosisRpc: String) = AntNative.storageValidity(handle, gnosisRpc)
            override fun storageBuyXdai(handle: Long, gnosisRpc: String, depth: Int, amountPerChunk: String, immutable: Boolean) =
                AntNative.storageBuyXdai(handle, gnosisRpc, depth, amountPerChunk, immutable)
            override fun storageTopupXdai(handle: Long, gnosisRpc: String, amountPerChunk: String) =
                AntNative.storageTopupXdai(handle, gnosisRpc, amountPerChunk)
            override fun storageConnectBatch(handle: Long, gnosisRpc: String, batchId: String) =
                AntNative.storageConnectBatch(handle, gnosisRpc, batchId)
            override fun storageDiscover(handle: Long, gnosisRpc: String) = AntNative.storageDiscover(handle, gnosisRpc)
            override fun gateway(method: String, path: String, timeoutMs: Int): GatewayAnswer? = try {
                val conn = java.net.URL(GATEWAY_URL + path).openConnection() as java.net.HttpURLConnection
                try {
                    conn.requestMethod = method
                    conn.connectTimeout = GATEWAY_CONNECT_TIMEOUT_MS
                    conn.readTimeout = timeoutMs
                    conn.useCaches = false
                    if (method == "POST") {
                        conn.doOutput = true
                        conn.setFixedLengthStreamingMode(0)
                        conn.outputStream.close()
                    }
                    val code = conn.responseCode
                    val body = (if (code >= 400) conn.errorStream else conn.inputStream)
                        ?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                    GatewayAnswer(code, body)
                } finally {
                    conn.disconnect()
                }
            } catch (e: java.io.IOException) {
                null
            }
        }
    }

    /** A gateway response: [code] and [body]. */
    internal data class GatewayAnswer(val code: Int, val body: String)

    data class Config(
        val dataDir: String,
        /**
         * The identity document to boot ant as (#77) — the account derived
         * from the wallet — read afresh at every start, or null to run as
         * the node's own `identity.json`. The node zeroes the bytes once
         * ant has them.
         */
        val identity: () -> ByteArray? = { null },
        /**
         * The mode to boot in (#114), read afresh at every start — right
         * after [identity], in the same launch, so a host can hand over
         * the pair it read together.
         */
        val mode: () -> Mode = { Mode.ULTRA_LIGHT },
    )

    /**
     * How the node takes part in Swarm (#114). Ultra-light browses with no
     * chain at all. Light hands ant a Gnosis JSON-RPC endpoint, [gnosisRpc]:
     * the gateway then reports `beeMode: light` and serves real `/wallet`,
     * `/stamps`, `/chequebook` and `/chainstate` — the publishing side.
     * The endpoint can carry an API key in its path or query: never log it.
     */
    class Mode private constructor(val light: Boolean, val gnosisRpc: String) {
        override fun equals(other: Any?) =
            other is Mode && other.light == light && other.gnosisRpc == gnosisRpc
        override fun hashCode() = 31 * light.hashCode() + gnosisRpc.hashCode()
        override fun toString() = if (light) "light" else "ultra-light"

        companion object {
            val ULTRA_LIGHT = Mode(false, "")

            /** Light mode against [gnosisRpc]; ultra-light when it's blank, since light needs a chain. */
            fun light(gnosisRpc: String): Mode =
                gnosisRpc.trim().takeIf { it.isNotEmpty() }?.let { Mode(true, it) } ?: ULTRA_LIGHT
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * `*mut AntHandle` from [AntNative.init]; `0` when not running.
     * `@Volatile` so the peer poller observes the reset in [stop]
     * without locking.
     */
    @Volatile
    private var handle: Long = 0L

    /** The mode [handle] booted in; its RPC is the one the storage calls use. Guarded by [lock]. */
    private var handleMode: Mode = Mode.ULTRA_LIGHT

    /**
     * Held (read) by every storage call for as long as it uses [handle],
     * and (write) by the shutdown of a handle [stop] took down — so ant
     * is never shut down under a call still inside it. A buy blocks until
     * its transactions confirm, so a restart waits for it.
     */
    private val handleUse = ReentrantReadWriteLock()
    private var peerPoller: Job? = null

    /**
     * Guards [generation], and orders it with the Starting/Stopped/
     * Running transitions and the [handle] hand-off.
     */
    private val lock = Any()

    /** Bumped by every [start] and [stop]; a launch acts only while its own is current. */
    private var generation = 0L

    /**
     * Shutdown of the handle the latest [stop] took from a Running
     * node; the next launch joins it before binding the gateway port.
     * Guarded by [lock].
     */
    private var pendingShutdown: Job? = null
    private val startMutex = Mutex()

    private val _state = MutableStateFlow(NodeInfo())
    val state: StateFlow<NodeInfo> = _state.asStateFlow()

    fun start() {
        val (gen, priorShutdown) = synchronized(lock) {
            if (_state.value.status == NodeStatus.Starting ||
                _state.value.status == NodeStatus.Running
            ) return
            _state.update { it.copy(status = NodeStatus.Starting, errorMessage = null) }
            ++generation to pendingShutdown
        }

        scope.launch {
            // The previous node must be gone before this one binds the
            // same gateway port. A launch that [stop] superseded
            // mid-init shuts its node down inside [bringUp], so the
            // mutex covers it; a Running node that [stop] took down is
            // shut down in [pendingShutdown], which is joined here.
            startMutex.withLock {
                priorShutdown?.join()
                bringUp(gen)
            }
        }
    }

    /**
     * Bring the node up for start generation [gen]. [stop] (or a later
     * [start]) bumps [generation], and seeding alone can block for
     * seconds, so the generation is re-checked before the native init
     * and again, under [lock], before the handle and Running status are
     * published. A superseded launch never touches [state] and tears
     * down any node it already created.
     */
    private fun bringUp(gen: Long) {
        if (!isCurrent(gen)) return
        try {
            val antDir = config.dataDir + "/ant"
            // Give a fresh install dialable bootnodes even if
            // ant's own port-53 dnsaddr lookup is blocked.
            ops.seed(File(antDir))
            if (!isCurrent(gen)) return
            val identity = config.identity()
            val mode = try {
                config.mode()
            } catch (t: Throwable) {
                identity?.fill(0)
                throw t
            }
            val h = try {
                if (identity != null) ops.initWithIdentity(antDir, identity) else ops.init(antDir)
            } finally {
                identity?.fill(0)
            }
            try {
                ops.startGateway(
                    handle = h,
                    apiAddr = GATEWAY_ADDR,
                    // Ultra-light: read path only, no chain. Light: the
                    // chain surfaces publishing needs (#114).
                    lightMode = mode.light,
                    gnosisRpc = mode.gnosisRpc,
                )
            } catch (t: Throwable) {
                runCatching { ops.shutdown(h) }
                throw t
            }
            val agent = runCatching { ops.agentString(h) }.getOrNull().orEmpty()
            val account = runCatching { ops.accountInfo(h)?.let(::JSONObject) }.getOrNull()
            val published = synchronized(lock) {
                if (generation != gen) return@synchronized false
                handle = h
                handleMode = mode
                _state.update {
                    it.copy(
                        status = NodeStatus.Running,
                        clientVersion = agent,
                        errorMessage = null,
                        accountAddress = account?.optString("eth_address").orEmpty(),
                        overlay = account?.optString("overlay").orEmpty(),
                        walletIdentity = identity != null,
                        lightMode = mode.light,
                    )
                }
                startPeerPolling()
                true
            }
            if (!published) {
                Log.i(TAG, "stopped while starting; shutting the new node down")
                runCatching {
                    AntChainTransport.cancelInFlight()
                    ops.stopGateway(h)
                    AntChainTransport.cancelInFlight()
                    ops.shutdown(h)
                }.onFailure { Log.w(TAG, "shutdown threw", it) }
            }
        } catch (t: Throwable) {
            synchronized(lock) {
                if (generation != gen) return
                Log.e(TAG, "Failed to start Swarm node", t)
                _state.update {
                    it.copy(
                        status = NodeStatus.Error,
                        errorMessage = t.message ?: t.javaClass.simpleName,
                    )
                }
            }
        }
    }

    private fun isCurrent(gen: Long) = synchronized(lock) { generation == gen }

    fun stop() {
        val shutdown = synchronized(lock) {
            // Supersede any launch still in flight.
            generation++
            peerPoller?.cancel()
            peerPoller = null
            _state.update {
                it.copy(
                    status = NodeStatus.Stopped,
                    connectedPeers = 0,
                    clientVersion = "",
                    accountAddress = "",
                    overlay = "",
                    walletIdentity = false,
                    lightMode = false,
                )
            }
            val h = handle
            handle = 0L
            if (h == 0L) return
            // Created LAZY and published under [lock], so a [start]
            // that follows this [stop] always sees it and waits for it.
            scope.launch(start = CoroutineStart.LAZY) {
                runCatching {
                    // After any storage call still using it (#116).
                    handleUse.write {
                        // Its gateway's chain reads end now, not at their
                        // deadline: both calls below wait for them (#273).
                        AntChainTransport.cancelInFlight()
                        ops.stopGateway(h)
                        AntChainTransport.cancelInFlight()
                        ops.shutdown(h)
                    }
                }.onFailure { Log.w(TAG, "shutdown threw", it) }
            }.also { pendingShutdown = it }
        }
        shutdown.start()
    }

    /**
     * Stop and start again, so the node picks up a changed identity
     * (#77) or mode (#114). The stop supersedes a launch still in flight, and the new
     * launch waits for the old node's shutdown before it binds the port.
     */
    fun restart() {
        stop()
        start()
    }

    /**
     * App came to the foreground, or connectivity came back: re-warm
     * the peer connections and restart paused background work. This is
     * the fix for the "node says Running, every bzz:// page fails until
     * the node is toggled off and on" wedge — see freedom-hq/ant#12.
     */
    fun resume() = lifecycle("resume") { h ->
        AntNative.resume(h)
        AntNative.wake(h)
    }

    /** App went to the background: let uploads checkpoint and quiesce. */
    fun suspend() = lifecycle("suspend") { h -> AntNative.suspend(h) }

    /** Network changed (Wi-Fi ↔ cellular, airplane mode off): redial. */
    fun onNetworkChanged() = lifecycle("network-change") { h -> AntNative.resume(h) }

    private fun lifecycle(what: String, block: (Long) -> Unit) {
        val h = handle
        if (h == 0L || _state.value.status != NodeStatus.Running) return
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { block(h) }
                    .onSuccess { Log.i(TAG, "$what ok  peers=${_state.value.connectedPeers}") }
                    .onFailure { Log.w(TAG, "$what failed", it) }
            }
        }
    }

    /**
     * Postage stamps (#116). ant's storage calls against the running light
     * node, each returning ant's JSON. They block — the two that spend
     * until their transactions confirm — so call them off the main thread.
     * Each throws [IllegalStateException] while the node isn't running in
     * light mode, and [RuntimeException] with ant's message (the RPC URL,
     * which can carry an API key, replaced) when ant fails.
     */
    fun storageStatus(): String = withLightNode { h, _ -> ops.storageStatus(h) }

    /** What a batch [depth] deep lasting [days] would cost, and whether the account covers it. */
    fun storageQuote(depth: Int, days: Long): String =
        withLightNode { h, rpc -> ops.storageQuote(h, rpc, depth, days) }

    /** What extending the node's connected batch by [days] would cost. */
    fun storageTopupQuote(days: Long): String = withLightNode { h, rpc -> ops.storageTopupQuote(h, rpc, days) }

    /**
     * Finds the batches this account already owns on Gnosis and registers
     * the ones still funded (#118), so stamps bought earlier (on another
     * device, or before a reinstall) can be published with again. Runs
     * outside any [SpendGuard] permit, so nothing ant might try to send
     * meanwhile gets out. Returns `{"registered":[ids],"status":{…}}`.
     * ant's discover also sets up settlement, which adopts a chequebook
     * this account already owns (no transaction); the gateway is reloaded
     * then, as after a buy, so the chequebook page (#117) sees it now.
     */
    fun discoverStamps(): String = withLightNode { h, rpc ->
        try {
            ops.storageDiscover(h, rpc)
        } finally {
            reloadGatewayForNewChequebook(h)
        }
    }

    /**
     * Buys a batch as the user confirmed it: [depth], [amountPerChunk] from
     * the quote they saw, swapping at most [maxSwapWei] of xDAI for the
     * xBZZ it needs. SPENDS: only these transactions get out ([SpendGuard]).
     */
    fun buyStamp(depth: Int, amountPerChunk: BigInteger, immutable: Boolean, maxSwapWei: BigInteger): String =
        withLightNode { h, rpc ->
            val plan = SpendPlan.BuyStamp(owner(), depth, amountPerChunk, immutable, maxSwapWei)
            try {
                SpendGuard.during(plan) { ops.storageBuyXdai(h, rpc, depth, amountPerChunk.toString(), immutable) }
            } finally {
                // The first buy sets up the chequebook (deploys one, or adopts
                // the one this account already owns), but the gateway only
                // loads it when it starts — ant's contract is to restart the
                // gateway then, or the chequebook (and a deposit into it,
                // #117) waits for the next node restart. Also when the buy
                // fails: it can set up the chequebook and then fail on the
                // batch itself. Only then, though: a buy that failed before
                // (no xDAI, another payment running) leaves ant with no
                // chequebook, and restarting the gateway for it would only
                // interrupt browsing.
                reloadGatewayForNewChequebook(h)
            }
        }

    /**
     * Extends batch [batchId] (hex, `0x` optional) by [amountPerChunk] per
     * chunk, swapping at most [maxSwapWei]. ant tops up its *connected*
     * batch, so this refuses unless that's [batchId]; and the permit names
     * [batchId], so even a batch switched in meanwhile can't be paid for.
     * SPENDS. Returns ant's validity JSON for the batch.
     */
    fun extendStamp(batchId: String, amountPerChunk: BigInteger, maxSwapWei: BigInteger): String =
        withLightNode { h, rpc ->
            val want = normalizeBatchId(batchId) ?: throw IllegalArgumentException("not a batch id")
            val connected = JSONObject(ops.storageStatus(h))
            if (!connected.optBoolean("enabled") || normalizeBatchId(connected.optString("batch_id")) != want) {
                throw IllegalStateException("this stamp isn't the node's active one")
            }
            val depth = connected.optInt("batch_depth", -1).takeIf { it in 17..64 }
                ?: throw IllegalStateException("the node reported no depth for this stamp")
            val plan = SpendPlan.ExtendStamp(owner(), want, depth, amountPerChunk, maxSwapWei)
            SpendGuard.during(plan) { ops.storageTopupXdai(h, rpc, amountPerChunk.toString()) }
        }

    /**
     * Connects batch [batchId] (hex, `0x` optional), which the wallet
     * bought for this node through SwarmNodeFunder (#115): ant checks on
     * chain that the node's account owns it and registers it, so the node
     * stamps with it. A first connect also sets up the chequebook, as a
     * first buy does — the only transactions the permit lets out — and
     * the gateway is reloaded to pick it up. Returns ant's storage status.
     */
    fun connectBatch(batchId: String): String {
        val id = normalizeBatchId(batchId) ?: throw IllegalArgumentException("not a batch id")
        return withLightNode { h, rpc ->
            try {
                SpendGuard.during(SpendPlan.ConnectBatch(owner())) { ops.storageConnectBatch(h, rpc, "0x$id") }
            } finally {
                if (antHasChequebook(h) && gatewayChequebook() == "") {
                    reloadGateway(h, mode = synchronized(lock) { handleMode })
                }
            }
        }
    }

    /**
     * Deposits [amountPlur] of the node's own xBZZ into its chequebook
     * [chequebook] (#117), as the user confirmed it — through ant's
     * `POST /chequebook/deposit`, the one transfer the permit admits.
     * Refuses unless the gateway's chequebook is [chequebook] and the
     * account holds the xBZZ (there's no swap). SPENDS. Returns ant's
     * `{"transactionHash": …}`.
     */
    fun depositChequebook(chequebook: String, amountPlur: BigInteger): String {
        require(amountPlur.signum() > 0) { "bad amount" }
        val want = normalizeAddress(chequebook) ?: throw IllegalArgumentException("not a chequebook address")
        return withLightNode { _, _ ->
            when (gatewayChequebook()) {
                null -> throw IllegalStateException("the node couldn't say which chequebook it has")
                "" -> throw IllegalStateException("the node has no chequebook yet")
                want -> Unit
                else -> throw IllegalStateException("the node's chequebook isn't the one you confirmed")
            }
            val wallet = ops.gateway("GET", "/wallet", GATEWAY_READ_TIMEOUT_MS)
                ?.takeIf { it.code == 200 }
                ?.let { runCatching { BigInteger(JSONObject(it.body).getString("bzzBalance")) }.getOrNull() }
                ?: throw IllegalStateException("the node couldn't read its xBZZ balance")
            check(wallet >= amountPlur) { "the node holds only ${formatBzz(wallet)} xBZZ" }
            val before = chequebookBalance()
            synchronized(lock) {
                loadUnconfirmedDeposit()
                unconfirmedDeposit?.let { u ->
                    // A deposit that ended without an answer may still be on
                    // its way, and `/wallet` doesn't count a pending transfer:
                    // another one now could move the xBZZ twice. Wait until
                    // the chequebook shows it (or long enough that it won't).
                    val landed = u.chequebook == want && u.balanceBefore != null && before != null &&
                        before >= u.balanceBefore + u.amountPlur
                    if (landed || u.chequebook != want || holdElapsedMs(clock(), u.atMs, sameBoot(u.bootId)) >= UNCONFIRMED_DEPOSIT_HOLD_MS) {
                        setUnconfirmedDeposit(null)
                    } else {
                        throw IllegalStateException(
                            "an earlier deposit may still be on its way; check the chequebook's balance, " +
                                "and deposit again in a few minutes if it hasn't grown",
                        )
                    }
                }
            }
            val plan = SpendPlan.DepositChequebook(owner(), want, amountPlur)
            val answer = SpendGuard.during(plan) {
                ops.gateway("POST", "/chequebook/deposit?amount=$amountPlur", DEPOSIT_TIMEOUT_MS)
            }
            val message = answer?.let { a ->
                runCatching { JSONObject(a.body).optString("message") }.getOrNull()?.takeIf { it.isNotBlank() }
            }
            if (depositMaybeSent(answer, message)) {
                synchronized(lock) { setUnconfirmedDeposit(UnconfirmedDeposit(want, before, amountPlur, clock(), bootId())) }
                throw RuntimeException(
                    "$DEPOSIT_MAYBE_SENT (${message ?: "the node's gateway didn't answer"}). " +
                        "Check the chequebook's balance before depositing again",
                )
            }
            if (answer!!.code !in 200..299) {
                throw RuntimeException(message ?: "the deposit failed (HTTP ${answer.code})")
            }
            answer.body
        }
    }

    /**
     * A deposit whose outcome the node couldn't tell (#117): into
     * [chequebook], which held [balanceBefore] PLUR (null: unknown), of
     * [amountPlur], at [atMs] on [clock], during the boot [bootId]
     * (null: unknown).
     */
    private data class UnconfirmedDeposit(
        val chequebook: String,
        val balanceBefore: BigInteger?,
        val amountPlur: BigInteger,
        val atMs: Long,
        val bootId: String?,
    )

    /** Whether a hold taken during boot [holdBoot] is from this boot (null: can't tell). */
    private fun sameBoot(holdBoot: String?): Boolean? {
        val current = bootId() ?: return null
        return holdBoot?.let { it == current }
    }

    /**
     * The last deposit that may or may not have gone out. Guarded by
     * [lock]. Kept in [unconfirmedDepositFile] too: the `:node` process
     * dies when the node is turned off, and a toggle off and on mustn't
     * lift the hold.
     */
    private var unconfirmedDeposit: UnconfirmedDeposit? = null
    private var unconfirmedDepositLoaded = false
    private val unconfirmedDepositFile get() = File(config.dataDir, UNCONFIRMED_DEPOSIT_FILE)

    /** Reads the persisted hold once, off the main thread (the first deposit). Under [lock]. */
    private fun loadUnconfirmedDeposit() {
        if (unconfirmedDepositLoaded) return
        unconfirmedDepositLoaded = true
        unconfirmedDeposit = runCatching {
            val o = JSONObject(unconfirmedDepositFile.readText())
            UnconfirmedDeposit(
                chequebook = normalizeAddress(o.getString("chequebook"))!!,
                balanceBefore = o.optString("balanceBefore").takeIf { it.isNotEmpty() }?.let(::BigInteger),
                amountPlur = BigInteger(o.getString("amountPlur")),
                atMs = o.getLong("atMs"),
                bootId = o.optString("bootId").takeIf { it.isNotEmpty() },
            )
        }.getOrNull()
    }

    /** Sets (null: lifts) the hold, on disk too. Under [lock]. */
    private fun setUnconfirmedDeposit(u: UnconfirmedDeposit?) {
        unconfirmedDeposit = u
        unconfirmedDepositLoaded = true
        runCatching {
            if (u == null) {
                unconfirmedDepositFile.delete()
            } else {
                val json = JSONObject()
                    .put("chequebook", u.chequebook)
                    .put("balanceBefore", u.balanceBefore?.toString() ?: "")
                    .put("amountPlur", u.amountPlur.toString())
                    .put("atMs", u.atMs)
                    .put("bootId", u.bootId ?: "")
                    .toString()
                val tmp = File(config.dataDir, "$UNCONFIRMED_DEPOSIT_FILE.tmp")
                tmp.writeText(json)
                check(tmp.renameTo(unconfirmedDepositFile))
            }
        }.onFailure { Log.w(TAG, "couldn't persist the deposit hold: ${it.javaClass.simpleName}") }
    }

    /**
     * Whether ant has a chequebook set up for this account on this device
     * (its persisted association, which the gateway reads only when it
     * starts). If ant can't say, assume it may: a needless reload only
     * interrupts browsing, a missing one strands the chequebook.
     */
    private fun antHasChequebook(h: Long): Boolean =
        runCatching { JSONObject(ops.settlementStatus(h)).getBoolean("enabled") }.getOrDefault(true)

    /**
     * Reloads the gateway of [h] when ant has a chequebook set up that the
     * gateway, which reads it only when it starts, doesn't report yet —
     * after a buy or a discover may have set one up.
     */
    private fun reloadGatewayForNewChequebook(h: Long) {
        if (antHasChequebook(h) && gatewayChequebook() == "") {
            reloadGateway(h, mode = synchronized(lock) { handleMode })
        }
    }

    /** What the gateway's chequebook holds, in PLUR; null when it couldn't say. */
    private fun chequebookBalance(): BigInteger? =
        ops.gateway("GET", "/chequebook/balance", GATEWAY_READ_TIMEOUT_MS)?.takeIf { it.code == 200 }
            ?.let { runCatching { BigInteger(JSONObject(it.body).getString("totalBalance")) }.getOrNull() }

    /**
     * The chequebook the gateway loaded, as 40 lowercase hex; `""` for
     * none; null when it couldn't say (not answering, still reading the
     * chain).
     */
    private fun gatewayChequebook(): String? {
        val answer = ops.gateway("GET", "/chequebook/address", GATEWAY_READ_TIMEOUT_MS)?.takeIf { it.code == 200 }
            ?: return null
        val address = runCatching { JSONObject(answer.body).getString("chequebookAddress") }.getOrNull() ?: return null
        val hex = normalizeAddress(address) ?: return null
        return if (hex.all { it == '0' }) "" else hex
    }

    /**
     * Stops and starts the gateway of [h] in [mode], so it loads what ant
     * persisted meanwhile (a chequebook). Only while [h] is still the
     * node's handle; a failure takes the node down into Error rather than
     * leaving it Running with no gateway.
     */
    private fun reloadGateway(h: Long, mode: Mode) {
        synchronized(lock) { if (handle != h) return }
        try {
            ops.stopGateway(h)
            ops.startGateway(handle = h, apiAddr = GATEWAY_ADDR, lightMode = mode.light, gnosisRpc = mode.gnosisRpc)
            Log.i(TAG, "reloaded the gateway so it reports the node's chequebook")
        } catch (t: Throwable) {
            Log.w(TAG, "reloading the gateway failed: ${t.javaClass.simpleName}")
            synchronized(lock) {
                if (handle == h) {
                    // Take the node down as [stop] does (the peer poller, and
                    // the handle once no call uses it), so a start from Error
                    // doesn't init a second node on the same data dir.
                    stop()
                    _state.update {
                        it.copy(status = NodeStatus.Error, errorMessage = GATEWAY_RELOAD_FAILED)
                    }
                }
            }
        }
    }

    /** The node's account, as [SpendPlan.owner]. */
    private fun owner(): String = _state.value.accountAddress.removePrefix("0x").lowercase()
        .takeIf { it.length == 40 } ?: throw IllegalStateException("the node has no account")

    private fun <T> withLightNode(block: (Long, String) -> T): T = handleUse.read {
        val (h, mode) = synchronized(lock) { handle to handleMode }
        check(h != 0L && _state.value.status == NodeStatus.Running) { "the Swarm node isn't running" }
        check(mode.light) { "the Swarm node isn't in light mode" }
        try {
            block(h, mode.gnosisRpc)
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: RuntimeException) {
            throw RuntimeException(scrubRpc(e.message ?: e.javaClass.simpleName, mode.gnosisRpc))
        }
    }

    /** Cancel the internal scope; call from Service.onDestroy after [stop]. */
    fun dispose() {
        stop()
        scope.cancel()
    }

    private fun startPeerPolling() {
        peerPoller?.cancel()
        peerPoller = scope.launch {
            while (isActive) {
                val h = handle
                if (h == 0L) break
                val peers = runCatching { ops.peerCount(h) }.getOrDefault(-1)
                _state.update { it.copy(connectedPeers = peers.coerceAtLeast(0).toLong()) }
                delay(if (peers > 100) 5_000L else 1_000L)
            }
        }
    }

    companion object {
        /**
         * The node's error when its gateway doesn't come back from a reload
         * for a new chequebook — after a buy or a search for owned stamps
         * alike, so it names neither.
         */
        const val GATEWAY_RELOAD_FAILED = "The gateway didn't come back after the node set up its chequebook"

        /**
         * Listen address handed to `ant_start_gateway`. ant defaults to
         * the same bee-conventional `127.0.0.1:1633`, but we pass it
         * explicitly so [GATEWAY_URL] can't silently drift from what
         * the node actually binds.
         */
        private const val GATEWAY_ADDR = "127.0.0.1:1633"

        /** Canonical URL of the embedded bee-shaped HTTP gateway. */
        const val GATEWAY_URL: String = "http://$GATEWAY_ADDR"

        private const val TAG = "SwarmNode"

        private const val GATEWAY_CONNECT_TIMEOUT_MS = 5_000
        private const val GATEWAY_READ_TIMEOUT_MS = 15_000

        /** ant waits up to 3 min for a deposit's receipt; a little longer here. */
        private const val DEPOSIT_TIMEOUT_MS = 4 * 60_000

        /**
         * How long a deposit that ended without a clear answer blocks
         * another into the same chequebook, unless its balance shows it
         * landed first. Past it, a transfer still unmined isn't coming.
         */
        private const val UNCONFIRMED_DEPOSIT_HOLD_MS = 15 * 60_000L

        /**
         * A lower bound on the time since a hold taken at [atMs], with the
         * [clock] now reading [now]. The clock is time since boot and the
         * hold is persisted, so a reboot restarts it at 0: a hold from an
         * earlier boot ([sameBoot] false) came before this one, so at least
         * [now] has passed. Without boot ids to compare (null), a reading
         * below [atMs] still proves a reboot; one above it is taken as the
         * same boot, which can only run the hold longer (#117). Never more
         * than the real time: the hold is never cut short.
         */
        internal fun holdElapsedMs(now: Long, atMs: Long, sameBoot: Boolean? = null): Long = when {
            sameBoot == false || now < atMs -> now
            else -> now - atMs
        }

        /**
         * The kernel's id for this boot (a fresh UUID each boot, readable
         * by apps), or null if it can't be read.
         */
        internal fun kernelBootId(): String? = runCatching {
            File("/proc/sys/kernel/random/boot_id").readText().trim().takeIf { it.isNotEmpty() }
        }.getOrNull()

        /**
         * Whether a deposit that got [answer] (null: none, e.g. a read
         * timeout) with ant's [message] may have broadcast its transfer.
         * ant answers 504 when the receipt wait runs out and 502 for an RPC
         * error, which can come after the send; only a revert (mined,
         * nothing moved) or a refusal before the chain (4xx, 501, 503) is
         * sure it moved nothing.
         */
        internal fun depositMaybeSent(answer: GatewayAnswer?, message: String?): Boolean = when {
            answer == null -> true
            answer.code in 200..299 -> false
            answer.code == 504 -> true
            answer.code == 502 -> message?.contains("transaction reverted") != true
            else -> false
        }

        private const val UNCONFIRMED_DEPOSIT_FILE = "unconfirmed-deposit.json"

        /** How a deposit that may have gone out after all ([depositMaybeSent]) starts its error. */
        const val DEPOSIT_MAYBE_SENT = "it may already have been sent"

        /** [address] as 40 lowercase hex without `0x`, or null if it isn't an address. */
        fun normalizeAddress(address: String): String? = address.trim().removePrefix("0x").removePrefix("0X").lowercase()
            .takeIf { s -> s.length == 40 && s.all { it in '0'..'9' || it in 'a'..'f' } }

        /** PLUR as xBZZ (16 decimals), trailing zeros dropped. */
        internal fun formatBzz(plur: BigInteger): String =
            java.math.BigDecimal(plur).movePointLeft(16).stripTrailingZeros().toPlainString()

        /** [id] as 64 lowercase hex without `0x`, or null if it isn't a batch id. */
        fun normalizeBatchId(id: String): String? = id.trim().removePrefix("0x").removePrefix("0X").lowercase()
            .takeIf { s -> s.length == 64 && s.all { it in '0'..'9' || it in 'a'..'f' } }

        /** [message] with [rpc] (an endpoint that can carry an API key) taken out. */
        internal fun scrubRpc(message: String, rpc: String): String =
            if (rpc.isBlank()) message else message.replace(rpc, "the Gnosis RPC").replace(rpc.trimEnd('/'), "the Gnosis RPC")
    }
}
