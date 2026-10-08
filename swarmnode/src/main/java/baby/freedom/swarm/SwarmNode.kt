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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A chequebook deposit whose outcome the node couldn't tell: its transfer
 * may already be out (#117). A type of its own, so the app tells it from
 * a failure without reading [message], which is in the app language (#280).
 */
class DepositMaybeSentException(message: String) : RuntimeException(message)

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
        /** Boots as the data dir's own identity, the disk cache capped at [cacheCapacityBytes] (≤ 0: ant's default). */
        fun init(dataDir: String, cacheCapacityBytes: Long): Long
        /** Boots as the account in [identity] (#77), likewise capped. */
        fun initWithIdentity(dataDir: String, identity: ByteArray, cacheCapacityBytes: Long): Long
        fun accountInfo(handle: Long): String?
        /** Starts the gateway allowing CORS reads from [corsOrigins] only. */
        fun startGateway(handle: Long, apiAddr: String, lightMode: Boolean, gnosisRpc: String, corsOrigins: List<String>)
        fun agentString(handle: Long): String?
        fun peerCount(handle: Long): Int
        fun resume(handle: Long)
        fun wake(handle: Long)
        fun suspend(handle: Long)
        fun stopGateway(handle: Long)
        fun shutdown(handle: Long)
        fun storageStatus(handle: Long): String
        fun storageQuote(handle: Long, gnosisRpc: String, depth: Int, days: Long): String
        fun storageTopupQuote(handle: Long, gnosisRpc: String, days: Long): String
        fun storageValidity(handle: Long, gnosisRpc: String): String
        fun storageBuyXdai(handle: Long, gnosisRpc: String, depth: Int, amountPerChunk: String, immutable: Boolean): String
        fun storageTopupXdai(handle: Long, gnosisRpc: String, amountPerChunk: String): String
        fun storageConnectBatch(handle: Long, gnosisRpc: String, batchId: String): String
        fun storageDiscover(handle: Long, gnosisRpc: String): String
        fun setSwapEnabled(handle: Long, enabled: Boolean)
        fun swapStatus(handle: Long): String
        fun confirmChequeLiability(handle: Long, chequebook: String): Int
        fun cacheStatus(handle: Long): String
        fun cacheClear(handle: Long): String
        fun cacheSetCapacity(handle: Long, bytes: Long)

        /**
         * One request to the node's own gateway ([GATEWAY_URL] + [path]),
         * from this process: its status code and body, or null when it
         * couldn't be reached. Never logs the path's query.
         */
        fun gateway(method: String, path: String, timeoutMs: Int): GatewayAnswer?

        object Native : NodeOps {
            override fun seed(antDir: File) = BootnodeSeeder.seedIfEmpty(antDir)
            override fun init(dataDir: String, cacheCapacityBytes: Long) =
                AntNative.initWithConfig(dataDir, null, cacheCapacityBytes)
            override fun initWithIdentity(dataDir: String, identity: ByteArray, cacheCapacityBytes: Long) =
                AntNative.initWithConfig(dataDir, identity, cacheCapacityBytes)
            override fun accountInfo(handle: Long) = AntNative.accountInfo(handle)
            override fun startGateway(
                handle: Long,
                apiAddr: String,
                lightMode: Boolean,
                gnosisRpc: String,
                corsOrigins: List<String>,
            ) = AntNative.startGateway(handle, apiAddr, lightMode, gnosisRpc, corsOrigins.toTypedArray())
            override fun agentString(handle: Long) = AntNative.agentString(handle)
            override fun peerCount(handle: Long) = AntNative.peerCount(handle)
            override fun resume(handle: Long) { AntNative.resume(handle) }
            override fun wake(handle: Long) { AntNative.wake(handle) }
            override fun suspend(handle: Long) { AntNative.suspend(handle) }
            override fun stopGateway(handle: Long) = AntNative.stopGateway(handle)
            override fun shutdown(handle: Long) = AntNative.shutdown(handle)
            override fun storageStatus(handle: Long) = AntNative.storageStatus(handle)
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
            override fun setSwapEnabled(handle: Long, enabled: Boolean) = AntNative.setSwapEnabled(handle, enabled)
            override fun swapStatus(handle: Long) = AntNative.swapStatus(handle)
            override fun confirmChequeLiability(handle: Long, chequebook: String) =
                AntNative.confirmChequeLiability(handle, chequebook)
            override fun cacheStatus(handle: Long) = AntNative.cacheStatus(handle)
            override fun cacheClear(handle: Long) = AntNative.cacheClear(handle)
            override fun cacheSetCapacity(handle: Long, bytes: Long) = AntNative.cacheSetCapacity(handle, bytes)
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
        /**
         * Whether the node pays peers from its chequebook (bee's
         * swap-enable, for downloads and uploads), read at every start
         * until [setSwapEnabled] has said otherwise. ant doesn't persist
         * it, so the node applies it after every init. On by default, as
         * in bee and ant.
         */
        val swapEnabled: () -> Boolean = { true },
        /**
         * The disk chunk cache's cap in bytes, read at every start until
         * [setCacheCapacity] has said otherwise, and handed to ant's init
         * so it applies from start-up (ant doesn't persist it). 0 or less:
         * ant's default, 512 MiB.
         */
        val cacheCapacityBytes: () -> Long = { 0L },
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
     * `*mut AntHandle` from [AntNative.initWithConfig]; `0` when not running.
     * `@Volatile` so the peer poller observes the reset in [stop]
     * without locking.
     */
    @Volatile
    private var handle: Long = 0L

    /** The mode [handle] booted in; its RPC is the one the storage calls use. Guarded by [lock]. */
    private var handleMode: Mode = Mode.ULTRA_LIGHT

    /** [clock] when [handle] was published: how long ant has had to count its chunk cache. */
    @Volatile
    private var handleBootedAt: Long = 0L

    /**
     * A fresh token each time a [handle] is published, and the one a
     * [clearCache] last succeeded on: after a clear on this boot, an
     * all-zero cache reading is the clear's own answer, not ant's post-init
     * count (an older build's database can keep its size through a clear).
     */
    @Volatile
    private var handleBoot: Any = Any()
    @Volatile
    private var cacheClearedBoot: Any? = null

    /**
     * Held (read) by every storage call for as long as it uses [handle],
     * and (write) by the shutdown of a handle [stop] took down — so ant
     * is never shut down under a call still inside it. A buy blocks until
     * its transactions confirm, so a restart waits for it.
     */
    private val handleUse = ReentrantReadWriteLock()
    private var peerPoller: Job? = null

    /**
     * True between [suspend] and [resume]: the app is in the background,
     * where nobody reads the peer count, so [startPeerPolling] asks ant
     * for it only every [BACKGROUND_PEER_POLL_MS] (#471). Kept across a
     * restart, which doesn't bring the app back.
     */
    private val backgrounded = MutableStateFlow(false)

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
            val cache = cacheValue()
            val h = try {
                if (identity != null) ops.initWithIdentity(antDir, identity, cache) else ops.init(antDir, cache)
            } finally {
                identity?.fill(0)
            }
            // ant's switch resets at every init (it isn't persisted): set
            // it before the gateway's chain init, which is when payments
            // can start, so a node the user switched off never pays.
            applySwap(h)
            try {
                ops.startGateway(
                    handle = h,
                    apiAddr = GATEWAY_ADDR,
                    // Ultra-light: read path only, no chain. Light: the
                    // chain surfaces publishing needs (#114).
                    lightMode = mode.light,
                    gnosisRpc = mode.gnosisRpc,
                    corsOrigins = GATEWAY_CORS_ORIGINS,
                )
            } catch (t: Throwable) {
                // As in [stop]: a chain read ant began before the gateway
                // failed would otherwise hold this shutdown for the
                // reader's whole deadline. Nothing else uses this handle
                // yet (it isn't published), so no spend's read is failed
                // (#300 R3-M2).
                runCatching { AntChainTransport.whileStopping { ops.shutdown(h) } }
                throw t
            }
            val agent = runCatching { ops.agentString(h) }.getOrNull().orEmpty()
            val account = runCatching { ops.accountInfo(h)?.let(::JSONObject) }.getOrNull()
            val published = synchronized(lock) {
                if (generation != gen) return@synchronized false
                handle = h
                handleMode = mode
                handleBootedAt = clock()
                handleBoot = Any()
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
            if (published) {
                // Again on the published handle: a [setSwapEnabled] that
                // landed since the first apply found no handle to set.
                applySwap()
                // Likewise a [setCacheCapacity] since the init read its cap.
                applyCache(bootedWith = h to cache)
            } else {
                Log.i(TAG, "stopped while starting; shutting the new node down")
                runCatching {
                    AntChainTransport.whileStopping {
                        ops.stopGateway(h)
                        ops.shutdown(h)
                    }
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
                        // Its chain reads end now, not at their deadline,
                        // and none starts meanwhile: both calls below wait
                        // for them (#273, #300 R2-M1).
                        AntChainTransport.whileStopping {
                            ops.stopGateway(h)
                            ops.shutdown(h)
                        }
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
    fun resume() {
        backgrounded.value = false
        lifecycle("resume") { h ->
            ops.resume(h)
            ops.wake(h)
        }
    }

    /**
     * App went to the background: let uploads checkpoint and quiesce, and
     * count peers only every [BACKGROUND_PEER_POLL_MS] until [resume].
     */
    fun suspend() {
        backgrounded.value = true
        lifecycle("suspend") { h -> ops.suspend(h) }
    }

    /** Network changed (Wi-Fi ↔ cellular, airplane mode off): redial. */
    fun onNetworkChanged() = lifecycle("network-change") { h -> ops.resume(h) }

    /**
     * [block] on the running node, off the caller's thread. The handle is
     * read, and used, under [handleUse] (read) like a storage call's, so
     * [stop]'s shutdown waits for a call still inside ant rather than
     * freeing the node under it.
     */
    private fun lifecycle(what: String, block: (Long) -> Unit) {
        if (handle == 0L || _state.value.status != NodeStatus.Running) return
        scope.launch {
            withContext(Dispatchers.IO) {
                handleUse.read {
                    val h = synchronized(lock) { handle }
                    if (h == 0L || _state.value.status != NodeStatus.Running) return@read
                    runCatching { block(h) }
                        .onSuccess { Log.i(TAG, "$what ok  peers=${_state.value.connectedPeers}") }
                        .onFailure { Log.w(TAG, "$what failed", it) }
                }
            }
        }
    }

    /**
     * What [setSwapEnabled] last asked for; null until it has, when the
     * node applies [Config.swapEnabled]. Guarded by [swapLock].
     */
    private var swapWanted: Boolean? = null

    /** Orders every apply of the switch, so the last one to run sets the latest wish. */
    private val swapLock = Any()

    /**
     * Pay peers from the chequebook, or not (bee's swap-enable): for
     * downloads and uploads alike, live on a running node and at every
     * later start (ant resets it at init). Returns at once; the native
     * call runs off the caller's thread.
     */
    fun setSwapEnabled(enabled: Boolean) {
        synchronized(swapLock) { swapWanted = enabled }
        scope.launch { applySwap() }
    }

    /** The switch's value for the next apply. Under [swapLock]. */
    private fun swapValue(): Boolean = swapWanted ?: runCatching { config.swapEnabled() }.getOrElse {
        Log.w(TAG, "reading the pay-peers setting failed (${it.javaClass.simpleName}); on, ant's default")
        true
    }

    /**
     * Sets the switch on [h], a handle not published yet; or, with no
     * argument, on the running node's, under [handleUse] like a storage
     * call. A failure is logged: the node then runs ant's default, which
     * [swapStatus] reports.
     */
    private fun applySwap(h: Long? = null) {
        if (h != null) {
            synchronized(swapLock) {
                val on = swapValue()
                runCatching { ops.setSwapEnabled(h, on) }
                    .onSuccess { Log.i(TAG, "pay peers from the chequebook: $on") }
                    .onFailure { Log.w(TAG, "setting pay-peers failed", it) }
            }
            return
        }
        handleUse.read {
            synchronized(swapLock) {
                val running = synchronized(lock) { handle }
                if (running == 0L || _state.value.status != NodeStatus.Running) return@read
                val on = swapValue()
                runCatching { ops.setSwapEnabled(running, on) }
                    .onSuccess { Log.i(TAG, "pay peers from the chequebook: $on") }
                    .onFailure { Log.w(TAG, "setting pay-peers failed", it) }
            }
        }
    }

    /**
     * The node's SWAP state, ant's `ant_swap_status` JSON: whether it can
     * pay peers, whether the switch is on, and whether it pays now. In
     * either mode (an ultra-light node reports it doesn't pay). Throws
     * [IllegalStateException] while the node isn't running.
     */
    fun swapStatus(): String = handleUse.read {
        val h = synchronized(lock) { handle }
        check(h != 0L && _state.value.status == NodeStatus.Running) { SwarmStrings.get(R.string.swarmnode_not_running) }
        ops.swapStatus(h)
    }

    /**
     * What [setCacheCapacity] last asked for; null until it has, when the
     * node boots with [Config.cacheCapacityBytes]. Guarded by [cacheLock].
     */
    private var cacheWanted: Long? = null

    /** Guards [cacheWanted]; never held across a native call. */
    private val cacheLock = Any()

    /**
     * Orders every live apply of the cache cap, so the last one to run
     * sets the latest wish. Held across the native call (a shrink evicts
     * before it returns), so it isn't [cacheLock], which a binder thread
     * takes to record a new wish.
     */
    private val cacheApplyLock = Any()

    /** The cache cap for the next init or apply. */
    private fun cacheValue(): Long = synchronized(cacheLock) { cacheWanted } ?: runCatching { config.cacheCapacityBytes() }
        .getOrElse {
            Log.w(TAG, "reading the cache size setting failed (${it.javaClass.simpleName}); ant's default")
            0L
        }

    /**
     * The disk chunk cache's cap: live on a running node (ant evicts down
     * to it at once when it shrinks) and at every later start. Returns at
     * once; the native call runs off the caller's thread. A failed
     * eviction is logged and not undone: ant keeps the new cap applied and
     * evicts down to it on its next write.
     */
    fun setCacheCapacity(bytes: Long) {
        synchronized(cacheLock) { cacheWanted = bytes }
        scope.launch { applyCache() }
    }

    /**
     * The handle and cap last set on it (by its init, or a live apply).
     * Guarded by [cacheApplyLock].
     */
    private var cacheApplied: Pair<Long, Long>? = null

    /**
     * Sets the latest [setCacheCapacity] on the running node, under
     * [handleUse] like a storage call; nothing when none was asked for, or
     * the running node already has it. [bootedWith]: the handle just
     * published and the cap its init was given.
     */
    private fun applyCache(bootedWith: Pair<Long, Long>? = null) {
        handleUse.read {
            synchronized(cacheApplyLock) {
                if (bootedWith != null) cacheApplied = bootedWith
                val running = synchronized(lock) { handle }
                if (running == 0L || _state.value.status != NodeStatus.Running) return@read
                val want = synchronized(cacheLock) { cacheWanted } ?: return@read
                if (want <= 0L || cacheApplied == (running to want)) return@read
                // Recorded either way: on a failed eviction ant keeps the cap
                // applied, and evicts down to it on its next write.
                cacheApplied = running to want
                runCatching { ops.cacheSetCapacity(running, want) }
                    .onSuccess { Log.i(TAG, "chunk cache capped at $want bytes") }
                    .onFailure { Log.w(TAG, "setting the chunk cache's cap to $want failed", it) }
            }
        }
    }

    /**
     * The chunk cache's figures, ant's `ant_cache_status` JSON, with
     * `"counting":true` added while ant is probably still counting a
     * large cache after init (see [markCacheCounting]). Cheap.
     * Throws [IllegalStateException] while the node isn't running.
     */
    fun cacheStatus(): String = withRunningNode { h ->
        val boot = handleBoot
        markCacheCounting(ops.cacheStatus(h), clock() - handleBootedAt, clearedSinceBoot = cacheClearedBoot === boot)
    }

    /**
     * Drops every unpinned chunk from the cache (pinned ones stay), ant's
     * `ant_cache_clear` JSON. Blocks for as long as the clear takes: off
     * the main thread. Throws [IllegalStateException] while the node
     * isn't running, and [RuntimeException] with ant's message on failure.
     */
    fun clearCache(): String = withRunningNode { h ->
        val boot = handleBoot
        ops.cacheClear(h).also {
            cacheClearedBoot = boot
            Log.i(TAG, "chunk cache cleared")
        }
    }

    /** [block] on the running node's handle, under [handleUse] like a storage call. */
    private fun <T> withRunningNode(block: (Long) -> T): T = handleUse.read {
        val h = synchronized(lock) { handle }
        check(h != 0L && _state.value.status == NodeStatus.Running) { SwarmStrings.get(R.string.swarmnode_not_running) }
        block(h)
    }

    /**
     * Accepts the outstanding cheques of [chequebook] after the node's
     * cheque ledger was lost, as the user confirmed: the node then pays
     * peers from it again. Only for the chequebook the gateway runs.
     * Returns true when a loss was confirmed, false when there was none.
     */
    fun confirmChequeLiability(chequebook: String): Boolean {
        val want = normalizeAddress(chequebook) ?: throw IllegalArgumentException("not a chequebook address")
        return withLightNode { h, _ ->
            when (gatewayChequebook()) {
                null -> throw IllegalStateException(SwarmStrings.get(R.string.swarmnode_chequebook_unknown))
                "" -> throw IllegalStateException(SwarmStrings.get(R.string.swarmnode_chequebook_none))
                want -> Unit
                else -> throw IllegalStateException(SwarmStrings.get(R.string.swarmnode_chequebook_other))
            }
            ops.confirmChequeLiability(h, "0x$want") == 0
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
     * this account already owns (a read, no transaction) and points the
     * running gateway at it (ant 0.5.52+), so the chequebook page (#117)
     * sees it with no gateway restart. Settlement may also try to deploy
     * a chequebook or (ant 0.5.51+) top up an adopted one's deposit: with
     * no permit open, [SpendGuard] refuses that broadcast, which is the
     * "refused a broadcast" log line a discover can leave behind.
     */
    fun discoverStamps(): String = withLightNode { h, rpc -> ops.storageDiscover(h, rpc) }

    /**
     * Buys a batch as the user confirmed it: [depth], [amountPerChunk] from
     * the quote they saw, swapping at most [maxSwapWei] of xDAI for the
     * xBZZ it needs. SPENDS: only these transactions get out ([SpendGuard]).
     * Once the batch is bought and registered, the first buy also sets up
     * the chequebook (deploys one, or adopts the one this account already
     * owns), and since ant 0.5.52 points the running gateway at it itself —
     * settlement and the chequebook page (#117) see it with no gateway
     * restart. A buy that fails on the batch never gets that far (ant
     * v0.5.56's activate_bought_batch runs ensure_settlement only after
     * createBatch and register_batch succeed), so it sets up no chequebook.
     */
    fun buyStamp(depth: Int, amountPerChunk: BigInteger, immutable: Boolean, maxSwapWei: BigInteger): String =
        withLightNode { h, rpc ->
            val plan = SpendPlan.BuyStamp(owner(), depth, amountPerChunk, immutable, maxSwapWei)
            SpendGuard.during(plan) { ops.storageBuyXdai(h, rpc, depth, amountPerChunk.toString(), immutable) }
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
                throw IllegalStateException(SwarmStrings.get(R.string.swarmnode_stamp_not_active))
            }
            val depth = connected.optInt("batch_depth", -1).takeIf { it in 17..64 }
                ?: throw IllegalStateException(SwarmStrings.get(R.string.swarmnode_stamp_no_depth))
            val plan = SpendPlan.ExtendStamp(owner(), want, depth, amountPerChunk, maxSwapWei)
            SpendGuard.during(plan) { ops.storageTopupXdai(h, rpc, amountPerChunk.toString()) }
        }

    /**
     * Connects batch [batchId] (hex, `0x` optional), which the wallet
     * bought for this node through SwarmNodeFunder (#115): ant checks on
     * chain that the node's account owns it and registers it, so the node
     * stamps with it. A first connect also sets up the chequebook, as a
     * first buy does — the only transactions the permit lets out — which
     * (ant 0.5.52+) the running gateway picks up at once, with no
     * restart. Returns ant's storage status.
     */
    fun connectBatch(batchId: String): String {
        val id = normalizeBatchId(batchId) ?: throw IllegalArgumentException("not a batch id")
        return withLightNode { h, rpc ->
            SpendGuard.during(SpendPlan.ConnectBatch(owner())) { ops.storageConnectBatch(h, rpc, "0x$id") }
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
                null -> throw IllegalStateException(SwarmStrings.get(R.string.swarmnode_chequebook_unknown))
                "" -> throw IllegalStateException(SwarmStrings.get(R.string.swarmnode_chequebook_none))
                want -> Unit
                else -> throw IllegalStateException(SwarmStrings.get(R.string.swarmnode_chequebook_other))
            }
            val wallet = ops.gateway("GET", "/wallet", GATEWAY_READ_TIMEOUT_MS)
                ?.takeIf { it.code == 200 }
                ?.let { runCatching { BigInteger(JSONObject(it.body).getString("bzzBalance")) }.getOrNull() }
                ?: throw IllegalStateException(SwarmStrings.get(R.string.swarmnode_balance_unreadable))
            check(wallet >= amountPlur) { SwarmStrings.get(R.string.swarmnode_balance_short, formatBzz(wallet)) }
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
                        throw IllegalStateException(SwarmStrings.get(R.string.swarmnode_deposit_earlier_pending))
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
                throw DepositMaybeSentException(
                    SwarmStrings.get(
                        R.string.swarmnode_deposit_maybe_sent_detail,
                        DEPOSIT_MAYBE_SENT,
                        message ?: SwarmStrings.get(R.string.swarmnode_gateway_no_answer),
                    ),
                )
            }
            if (answer!!.code !in 200..299) {
                throw RuntimeException(message ?: SwarmStrings.get(R.string.swarmnode_deposit_failed_http, answer.code))
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
    private val unconfirmedDepositTmp get() = File(config.dataDir, "$UNCONFIRMED_DEPOSIT_FILE.tmp")

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
                val tmp = unconfirmedDepositTmp
                tmp.writeText(json)
                check(tmp.renameTo(unconfirmedDepositFile))
            }
        }.onFailure {
            // Don't leave the half-written step lying in files/.
            unconfirmedDepositTmp.delete()
            Log.w(TAG, "couldn't persist the deposit hold: ${it.javaClass.simpleName}")
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

    /** The node's account, as [SpendPlan.owner]. */
    private fun owner(): String = _state.value.accountAddress.removePrefix("0x").lowercase()
        .takeIf { it.length == 40 } ?: throw IllegalStateException(SwarmStrings.get(R.string.swarmnode_no_account))

    private fun <T> withLightNode(block: (Long, String) -> T): T = handleUse.read {
        val (h, mode) = synchronized(lock) { handle to handleMode }
        check(h != 0L && _state.value.status == NodeStatus.Running) { SwarmStrings.get(R.string.swarmnode_not_running) }
        check(mode.light) { SwarmStrings.get(R.string.swarmnode_not_light) }
        try {
            block(h, mode.gnosisRpc)
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: DepositMaybeSentException) {
            // Scrubbed too, and still told apart by its type (#313 R1-M1).
            throw DepositMaybeSentException(scrubRpc(e.message ?: e.javaClass.simpleName, mode.gnosisRpc))
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
                // Under [handleUse], like [lifecycle]: [stop]'s shutdown
                // can't free the node during the count.
                val peers = handleUse.read {
                    val h = synchronized(lock) { handle }
                    if (h == 0L) null else runCatching { ops.peerCount(h) }.getOrDefault(-1)
                } ?: break
                _state.update { it.copy(connectedPeers = peers.coerceAtLeast(0).toLong()) }
                val wasBackgrounded = backgrounded.value
                // Coming back to the foreground cuts a background wait
                // short, so the count is fresh as soon as the app shows it.
                withTimeoutOrNull(peerPollDelayMs(peers, wasBackgrounded)) {
                    backgrounded.first { it != wasBackgrounded }
                }
            }
        }
    }

    companion object {
        /** How often a backgrounded node's peers are counted (#471). */
        internal const val BACKGROUND_PEER_POLL_MS = 30_000L

        /**
         * The wait before the next peer count: every second while the app
         * is in front and the node still has few peers, every 5 s once it
         * has plenty, and every [BACKGROUND_PEER_POLL_MS] in the background.
         */
        internal fun peerPollDelayMs(peers: Int, backgrounded: Boolean): Long = when {
            backgrounded -> BACKGROUND_PEER_POLL_MS
            peers > 100 -> 5_000L
            else -> 1_000L
        }

        /**
         * Listen address handed to `ant_start_gateway`. ant defaults to
         * the same bee-conventional `127.0.0.1:1633`, but we pass it
         * explicitly so [GATEWAY_URL] can't silently drift from what
         * the node actually binds.
         */
        private const val GATEWAY_ADDR = "127.0.0.1:1633"

        /** Canonical URL of the embedded bee-shaped HTTP gateway. */
        const val GATEWAY_URL: String = "http://$GATEWAY_ADDR"

        /**
         * The origins the gateway lets read its answers across origins
         * (`ant_set_gateway_cors`, #284): none. The gateway then sends no
         * CORS headers at all, so a page on any other origin can send it
         * requests but never read what comes back.
         *
         * Up to ant 0.5.48 the list was pinned to `null`, which a CORS
         * fetch carries after any cross-origin redirect: any page could
         * read `/wallet` and `/addresses` through a redirector, past the
         * app's `NodeApiGuard` (which WebView never asks about such a hop)
         * and from other browsers, which never pass through it.
         *
         * Nothing the app serves needs an entry here:
         * - bzz://, ens:// and the other dweb pages are served on virtual
         *   origins by the app's interceptor, which fetches from the gateway
         *   natively and stamps its own CORS headers; `window.swarm` and
         *   the app's own screens talk to the gateway natively too.
         * - A page on a virtual origin (`https://<label>.bzz.freedom.baby`)
         *   that fetches `127.0.0.1:1633` itself never matched `null`
         *   either. ant matches exact origins only, and there's one per
         *   root, so the only entry that would cover them is `*`, which
         *   would let every page read the node's private API.
         * - The error page (a `file://` document, origin `null`) asks
         *   `/health` with a `no-cors` probe, which needs no CORS answer.
         */
        val GATEWAY_CORS_ORIGINS: List<String> = emptyList()

        private const val TAG = "SwarmNode"

        /**
         * How long after init an all-zero cache reading over a large file
         * is taken for ant's background count rather than an empty cache.
         * ant documents "a few seconds"; this leaves a slow phone room.
         */
        internal const val CACHE_COUNT_WINDOW_MS = 60_000L

        /**
         * A `chunks.sqlite` (+ -wal/-shm) at least this big isn't an empty
         * cache: a fresh database is a few KB, and a clear gives the
         * space back (incremental vacuum).
         */
        internal const val CACHE_COUNT_MIN_FILE_BYTES = 1L shl 20

        /**
         * [json] (ant's `ant_cache_status`) with `"counting":true` when it
         * is most likely ant's post-init count still running: right after
         * init on a large cache, ant reads 0 for every disk counter until a
         * background count finishes, which would read as "0 B of 2 GB".
         * So: within [CACHE_COUNT_WINDOW_MS] of init ([sinceBootMs]), disk
         * cache open, nothing counted (used, chunks, pinned all 0), yet
         * the file holds at least [CACHE_COUNT_MIN_FILE_BYTES]. Otherwise
         * (and for anything unreadable) [json] unchanged. Never after a
         * clear on this boot ([clearedSinceBoot]): ant rebuilds a database
         * from an older build on a clear only when its pinned chunks fit
         * in 64 MiB and the disk has room, so a cleared cache can keep a
         * large file with nothing in it, and that is the true reading.
         */
        internal fun markCacheCounting(json: String, sinceBootMs: Long, clearedSinceBoot: Boolean = false): String {
            if (clearedSinceBoot) return json
            if (sinceBootMs !in 0 until CACHE_COUNT_WINDOW_MS) return json
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return json
            if (!o.optBoolean("disk_enabled", false)) return json
            if (listOf("used_bytes", "chunks", "pinned_bytes", "pinned_chunks").any { o.optLong(it, 0L) != 0L }) return json
            if (o.optLong("file_bytes", 0L) < CACHE_COUNT_MIN_FILE_BYTES) return json
            return o.put("counting", true).toString()
        }

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
        val DEPOSIT_MAYBE_SENT: String get() = SwarmStrings.get(R.string.swarmnode_deposit_maybe_sent)

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
            if (rpc.isBlank()) {
                message
            } else {
                val name = SwarmStrings.get(R.string.swarmnode_gnosis_rpc)
                message.replace(rpc, name).replace(rpc.trimEnd('/'), name)
            }
    }
}
