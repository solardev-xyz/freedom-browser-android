package baby.freedom.swarm

import android.util.Log
import java.io.File
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
        fun startGateway(handle: Long, apiAddr: String, lightMode: Boolean, gnosisRpc: String)
        fun agentString(handle: Long): String?
        fun peerCount(handle: Long): Int
        fun stopGateway(handle: Long)
        fun shutdown(handle: Long)

        object Native : NodeOps {
            override fun seed(antDir: File) = BootnodeSeeder.seedIfEmpty(antDir)
            override fun init(dataDir: String) = AntNative.init(dataDir)
            override fun startGateway(handle: Long, apiAddr: String, lightMode: Boolean, gnosisRpc: String) =
                AntNative.startGateway(handle, apiAddr, lightMode, gnosisRpc)
            override fun agentString(handle: Long) = AntNative.agentString(handle)
            override fun peerCount(handle: Long) = AntNative.peerCount(handle)
            override fun stopGateway(handle: Long) = AntNative.stopGateway(handle)
            override fun shutdown(handle: Long) = AntNative.shutdown(handle)
        }
    }

    data class Config(
        val dataDir: String,
        /**
         * Gnosis JSON-RPC endpoint backing the gateway's on-chain
         * `/wallet` / `/stamps` / `/chequebook` surfaces. `""` keeps
         * them disabled — right for ultra-light (read-only) mode.
         */
        val rpcEndpoint: String = "",
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * `*mut AntHandle` from [AntNative.init]; `0` when not running.
     * `@Volatile` so the peer poller observes the reset in [stop]
     * without locking.
     */
    @Volatile
    private var handle: Long = 0L
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
            val h = ops.init(antDir)
            try {
                ops.startGateway(
                    handle = h,
                    apiAddr = GATEWAY_ADDR,
                    // Ultra-light: read path only, no publishing.
                    lightMode = false,
                    gnosisRpc = config.rpcEndpoint,
                )
            } catch (t: Throwable) {
                runCatching { ops.shutdown(h) }
                throw t
            }
            val agent = runCatching { ops.agentString(h) }.getOrNull().orEmpty()
            val published = synchronized(lock) {
                if (generation != gen) return@synchronized false
                handle = h
                _state.update {
                    it.copy(
                        status = NodeStatus.Running,
                        clientVersion = agent,
                        errorMessage = null,
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
                it.copy(status = NodeStatus.Stopped, connectedPeers = 0, clientVersion = "")
            }
            val h = handle
            handle = 0L
            if (h == 0L) return
            // Created LAZY and published under [lock], so a [start]
            // that follows this [stop] always sees it and waits for it.
            scope.launch(start = CoroutineStart.LAZY) {
                runCatching {
                    ops.stopGateway(h)
                    ops.shutdown(h)
                }.onFailure { Log.w(TAG, "shutdown threw", it) }
            }.also { pendingShutdown = it }
        }
        shutdown.start()
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
    }
}
