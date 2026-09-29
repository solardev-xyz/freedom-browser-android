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
) {
    constructor(config: Config) : this(config, NodeOps.Native)

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
        fun storageQuote(handle: Long, gnosisRpc: String, depth: Int, days: Long): String
        fun storageTopupQuote(handle: Long, gnosisRpc: String, days: Long): String
        fun storageValidity(handle: Long, gnosisRpc: String): String
        fun storageBuyXdai(handle: Long, gnosisRpc: String, depth: Int, amountPerChunk: String, immutable: Boolean): String
        fun storageTopupXdai(handle: Long, gnosisRpc: String, amountPerChunk: String): String
        fun storageDiscover(handle: Long, gnosisRpc: String): String

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
            override fun storageQuote(handle: Long, gnosisRpc: String, depth: Int, days: Long) =
                AntNative.storageQuote(handle, gnosisRpc, depth, days)
            override fun storageTopupQuote(handle: Long, gnosisRpc: String, days: Long) =
                AntNative.storageTopupQuote(handle, gnosisRpc, days)
            override fun storageValidity(handle: Long, gnosisRpc: String) = AntNative.storageValidity(handle, gnosisRpc)
            override fun storageBuyXdai(handle: Long, gnosisRpc: String, depth: Int, amountPerChunk: String, immutable: Boolean) =
                AntNative.storageBuyXdai(handle, gnosisRpc, depth, amountPerChunk, immutable)
            override fun storageTopupXdai(handle: Long, gnosisRpc: String, amountPerChunk: String) =
                AntNative.storageTopupXdai(handle, gnosisRpc, amountPerChunk)
            override fun storageDiscover(handle: Long, gnosisRpc: String) = AntNative.storageDiscover(handle, gnosisRpc)
        }
    }

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
                    ops.stopGateway(h)
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
                        ops.stopGateway(h)
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
     */
    fun discoverStamps(): String = withLightNode { h, rpc -> ops.storageDiscover(h, rpc) }

    /**
     * Buys a batch as the user confirmed it: [depth], [amountPerChunk] from
     * the quote they saw, swapping at most [maxSwapWei] of xDAI for the
     * xBZZ it needs. SPENDS: only these transactions get out ([SpendGuard]).
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
                throw IllegalStateException("this stamp isn't the node's active one")
            }
            val depth = connected.optInt("batch_depth", -1).takeIf { it in 17..64 }
                ?: throw IllegalStateException("the node reported no depth for this stamp")
            val plan = SpendPlan.ExtendStamp(owner(), want, depth, amountPerChunk, maxSwapWei)
            SpendGuard.during(plan) { ops.storageTopupXdai(h, rpc, amountPerChunk.toString()) }
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
         * Listen address handed to `ant_start_gateway`. ant defaults to
         * the same bee-conventional `127.0.0.1:1633`, but we pass it
         * explicitly so [GATEWAY_URL] can't silently drift from what
         * the node actually binds.
         */
        private const val GATEWAY_ADDR = "127.0.0.1:1633"

        /** Canonical URL of the embedded bee-shaped HTTP gateway. */
        const val GATEWAY_URL: String = "http://$GATEWAY_ADDR"

        private const val TAG = "SwarmNode"

        /** [id] as 64 lowercase hex without `0x`, or null if it isn't a batch id. */
        fun normalizeBatchId(id: String): String? = id.trim().removePrefix("0x").removePrefix("0X").lowercase()
            .takeIf { s -> s.length == 64 && s.all { it in '0'..'9' || it in 'a'..'f' } }

        /** [message] with [rpc] (an endpoint that can carry an API key) taken out. */
        internal fun scrubRpc(message: String, rpc: String): String =
            if (rpc.isBlank()) message else message.replace(rpc, "the Gnosis RPC").replace(rpc.trimEnd('/'), "the Gnosis RPC")
    }
}
