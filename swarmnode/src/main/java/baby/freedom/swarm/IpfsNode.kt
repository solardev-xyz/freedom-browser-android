package baby.freedom.swarm

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Kotlin wrapper around the embedded freedom-ipfs reader
 * ([FreedomIpfsNative]), replacing the previous Kubo node from
 * `mobile.aar`.
 *
 * Shape mirrors [SwarmNode] on purpose: the UI observes [state] via a
 * [StateFlow] so the Running edge can't be missed by late collectors.
 *
 * freedom-ipfs is a read-only reader with on-demand retrieval — there
 * is no persistent peer set or peer ID. [IpfsInfo.connectedPeers]
 * carries the count of verified blocks fetched so far instead (the
 * closest "is it actually working" signal), and the gateway being up
 * means the node is usable immediately.
 *
 * Passing `127.0.0.1:0` has the listener allocate an ephemeral port;
 * the real bound address is read back via [FreedomIpfsNative.gatewayUrl]
 * and published as [IpfsInfo.gatewayUrl] so the browser knows where to
 * fetch `/ipfs/…` and `/ipns/…`.
 *
 * DNSLink/IPNS DNS goes through Cloudflare DoH inside the library —
 * no Android resolv.conf dependency, so the Kubo-era config-JSON DNS
 * patching is gone.
 */
class IpfsNode internal constructor(
    private val config: Config,
    private val ops: Ops,
) {
    constructor(config: Config) : this(config, Ops.Native)

    /** The `freedom_ipfs_*` calls [IpfsNode] makes; swapped for a fake in tests. */
    internal interface Ops {
        fun nodeNew(dataDir: String, maxCacheBytes: Long): Long
        fun nodeFree(handle: Long)
        fun startGatewayOnline(handle: Long, addr: String, routingMode: Int): Boolean
        fun stopGateway(handle: Long): Boolean
        fun gatewayUrl(handle: Long): String?
        fun version(): String?
        fun diagnostics(handle: Long): LongArray
        fun progressSnapshotJson(handle: Long): ByteArray?
        fun handleNetworkChange(handle: Long): Boolean
        fun enterBackground(handle: Long): Boolean
        fun enterForeground(handle: Long): Boolean

        object Native : Ops {
            override fun nodeNew(dataDir: String, maxCacheBytes: Long) = FreedomIpfsNative.nodeNew(dataDir, maxCacheBytes)
            override fun nodeFree(handle: Long) = FreedomIpfsNative.nodeFree(handle)
            override fun startGatewayOnline(handle: Long, addr: String, routingMode: Int) =
                FreedomIpfsNative.startGatewayOnline(handle, addr, routingMode)
            override fun stopGateway(handle: Long) = FreedomIpfsNative.stopGateway(handle)
            override fun gatewayUrl(handle: Long) = FreedomIpfsNative.gatewayUrl(handle)
            override fun version() = FreedomIpfsNative.version()
            override fun diagnostics(handle: Long) = FreedomIpfsNative.diagnostics(handle)
            override fun progressSnapshotJson(handle: Long) = FreedomIpfsNative.progressSnapshotJson(handle)
            override fun handleNetworkChange(handle: Long) = FreedomIpfsNative.handleNetworkChange(handle)
            override fun enterBackground(handle: Long) = FreedomIpfsNative.enterBackground(handle)
            override fun enterForeground(handle: Long) = FreedomIpfsNative.enterForeground(handle)
        }
    }

    data class Config(
        /**
         * Root directory for the bounded block cache. A dedicated
         * subdirectory is recommended so it doesn't collide with the
         * Swarm state store or the old Kubo repo.
         */
        val dataDir: String,
        /**
         * Retained from the Kubo integration for settings
         * compatibility; freedom-ipfs has no lowpower profile — its
         * defaults are already mobile-budgeted. Unused.
         */
        val lowPower: Boolean = true,
        /**
         * Routing strategy. freedom-ipfs modes: "auto" (delegated
         * routing with light-DHT fallback), "delegated", "light_dht",
         * "offline". Legacy Kubo values ("autoclient", "dht", "") map
         * to "auto".
         */
        val routingMode: String = "auto",
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var handle: Long = 0L

    /** The [generation] whose launch published [handle]. Guarded by [handleLock] (write to change). */
    private var handleGen = 0L

    /**
     * Guards [handle]'s lifetime. Every native call on a handle holds it
     * (read) for the whole call — the binder-thread polls
     * ([progressSnapshotJson], [diagnostics]), the stats poller, the
     * lifecycle and network calls — and [releaseHandle] and a launch's
     * publish hold it (write) only to swap the handle, so a release
     * waits out any call still inside the node before freeing it, while
     * the (slow) gateway shutdown and free run outside it and never block
     * a poll, which just finds 0.
     */
    private val handleLock = ReentrantReadWriteLock()

    /**
     * Held while a node is built (a launch's nodeNew → gateway start →
     * publish) and while one is freed ([releaseHandle]), so this
     * instance's launches and releases run one at a time: a launch first
     * frees any node an older generation of *this instance* published.
     * Never taken by a poll. Order: [nodeLock], then [dataDirLease], then
     * [handleLock], then [stateLock].
     *
     * It says nothing about other instances — NodeService builds a fresh
     * [IpfsNode] on every off → on, while the old one's release (or a
     * launch still finishing its gateway start) runs on — so exclusivity
     * across instances comes from [dataDirLease].
     */
    private val nodeLock = Any()

    /**
     * The process-wide permit for a live node on [Config.dataDir]: taken
     * before nodeNew and given back only after that node's nodeFree, so
     * at most one node (and one gateway) is ever alive per data dir in
     * this process, whichever [IpfsNode] instance owns it — a new
     * instance's launch waits out the old instance's release or in-flight
     * launch instead of opening the same store beside it.
     */
    private val dataDirLease: Semaphore = leaseFor(config.dataDir)

    /** Orders [start]/[stop] with each other: guards [generation] and the Starting/Running/Stopped transitions. */
    private val stateLock = Any()

    /** Bumped by every [start] and [stop]; a launch publishes its node and Running only while its own is current. */
    private var generation = 0L
    private var statsPoller: Job? = null

    private val _state = MutableStateFlow(IpfsInfo())
    val state: StateFlow<IpfsInfo> = _state.asStateFlow()

    fun start() {
        val gen = synchronized(stateLock) {
            if (_state.value.status == IpfsStatus.Starting ||
                _state.value.status == IpfsStatus.Running
            ) return
            _state.update { it.copy(status = IpfsStatus.Starting, errorMessage = null) }
            ++generation
        }

        scope.launch {
            // One node at a time: a node an earlier launch or stop is still
            // freeing holds the data dir's store lock, and one still
            // published from before a [stop] whose release hasn't run yet
            // must not be overwritten (and so leaked, gateway serving) by
            // this launch's — so free whatever is older first, and build
            // this launch's node with [nodeLock] held.
            synchronized(nodeLock) {
                releaseHandle(before = gen)
                // Another instance's node on this data dir (an earlier
                // off → on's) may still be freeing: wait for it to be gone.
                dataDirLease.acquireUninterruptibly()
                var node = 0L
                try {
                    // A stop while this waited: never make the node.
                    if (synchronized(stateLock) { generation != gen }) {
                        dataDirLease.release()
                        return@launch
                    }
                    node = ops.nodeNew(config.dataDir, 0L)
                    if (node == 0L) error("freedom_ipfs_node_new_with_data_dir failed")
                    // The gateway starts before the node is published, so
                    // nothing else can reach it yet and no poll waits on it:
                    // [handleLock] isn't held, and a [stop] meanwhile just
                    // bumps [generation] (its release waits on [nodeLock]).
                    if (!ops.startGatewayOnline(node, "127.0.0.1:0", routingModeConstant())) {
                        error("freedom_ipfs start_gateway_online failed")
                    }
                    val gatewayUrl = ops.gatewayUrl(node) ?: error("gateway started but reported no URL")
                    val version = ops.version().orEmpty()
                    // A [stop] since this launch began wins: the node is
                    // never published, so this launch frees it.
                    val running = handleLock.write {
                        synchronized(stateLock) {
                            if (generation != gen) return@synchronized false
                            handle = node
                            handleGen = gen
                            _state.update {
                                it.copy(
                                    status = IpfsStatus.Running,
                                    gatewayUrl = gatewayUrl,
                                    clientVersion = version,
                                    errorMessage = null,
                                )
                            }
                            startStatsPolling()
                            true
                        }
                    }
                    if (running) {
                        Log.i(TAG, "freedom-ipfs $version gateway at $gatewayUrl")
                    } else {
                        Log.i(TAG, "stopped while starting")
                        freeNode(node)
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to start IPFS node", t)
                    // Never published: only this launch has it. No node
                    // made (nodeNew failed or threw): just give the lease back.
                    if (node != 0L) freeNode(node) else dataDirLease.release()
                    synchronized(stateLock) {
                        if (generation != gen) return@launch
                        _state.update {
                            it.copy(
                                status = IpfsStatus.Error,
                                errorMessage = t.message ?: t.javaClass.simpleName,
                            )
                        }
                    }
                }
            }
        }
    }

    fun stop() {
        val stopGen = synchronized(stateLock) {
            generation++
            statsPoller?.cancel()
            statsPoller = null

            _state.update {
                it.copy(
                    status = IpfsStatus.Stopped,
                    connectedPeers = 0,
                    gatewayUrl = "",
                    clientVersion = "",
                )
            }
            generation
        }

        // Not a child of [scope]: [dispose] cancels that right after, and
        // a launch cancelled before it ran would never free the node —
        // its gateway would go on serving, and fetching from the network,
        // with IPFS switched off.
        // Only a node published before this stop: a [start] right after it
        // may have published its own by the time this runs.
        scope.launch(NonCancellable) { releaseHandle(before = stopGen) }
    }

    fun dispose() {
        stop()
        scope.cancel()
    }

    fun enterBackground() = whileRunning { ops.enterBackground(it) }

    fun enterForeground() = whileRunning { ops.enterForeground(it) }

    /** Forward connectivity changes so stale provider state is dropped. */
    fun onNetworkChanged() = whileRunning { ops.handleNetworkChange(it) }

    /** [block] on the running node, off the caller's thread, holding the node for the whole call. */
    private fun whileRunning(block: (Long) -> Unit) {
        if (handle == 0L || _state.value.status != IpfsStatus.Running) return
        scope.launch {
            withHandle { node ->
                runCatching { block(node) }.onFailure { Log.w(TAG, "lifecycle call failed", it) }
            }
        }
    }

    /** [block] on the running node with [handleLock] held, or null while there's none. */
    private inline fun <T> withHandle(block: (Long) -> T): T? = handleLock.read {
        val node = handle
        if (node == 0L || _state.value.status != IpfsStatus.Running) null else block(node)
    }

    /**
     * The node's live retrieval-progress snapshot (JSON, see
     * [FreedomIpfsNative.progressSnapshotJson]), or null while the node
     * isn't running. Blocking but cheap — a mutex-guarded copy of an
     * in-memory ring, and [releaseHandle] never holds [handleLock] across
     * the node's shutdown — so the browser can poll it a few times a
     * second while an `ipfs://` / `ipns://` page loads.
     */
    fun progressSnapshotJson(): String? = withHandle { node ->
        runCatching { ops.progressSnapshotJson(node) }
            .onFailure { Log.w(TAG, "progressSnapshotJson threw", it) }
            .getOrNull()
            ?.toString(Charsets.UTF_8)
    }

    /**
     * The node's cumulative retrieval / routing counters
     * ([FreedomIpfsNative.diagnostics], `long[11]`), or null while the
     * node isn't running. Unlike [progressSnapshotJson] these are plain
     * atomics inside the node, so they tick regardless of which tracing
     * subscriber the process ended up with.
     */
    fun diagnostics(): LongArray? = withHandle { node ->
        runCatching { ops.diagnostics(node) }
            .onFailure { Log.w(TAG, "diagnostics threw", it) }
            .getOrNull()
    }

    /**
     * Take the handle — only if a launch older than generation [before]
     * published it — and free its node. The write lock waits for every
     * call still using the handle; once the swap is done no call can pick
     * it up, so the slow stopGateway + nodeFree needn't block anyone but
     * a launch, which waits on [nodeLock] for the node to be gone.
     */
    private fun releaseHandle(before: Long) = synchronized(nodeLock) {
        val node = handleLock.write {
            val old = handle
            if (old == 0L || handleGen >= before) return@synchronized
            handle = 0L
            old
        }
        freeNode(node)
    }

    /** Stop [node]'s gateway and free it, then give back the [dataDirLease] its launch took. */
    private fun freeNode(node: Long) {
        try {
            runCatching { ops.stopGateway(node) }
                .onFailure { Log.w(TAG, "stopGateway threw", it) }
            runCatching { ops.nodeFree(node) }
                .onFailure { Log.w(TAG, "nodeFree threw", it) }
        } finally {
            dataDirLease.release()
        }
    }

    /**
     * Surface retrieval activity on the same cadence the Kubo wrapper
     * polled peers: verified blocks fetched (cache + HTTP providers +
     * Bitswap) stand in for `connectedPeers`.
     */
    private fun startStatsPolling() {
        statsPoller?.cancel()
        statsPoller = scope.launch {
            while (isActive) {
                val blocks = handleLock.read {
                    val node = handle
                    if (node == 0L) null else runCatching { ops.diagnostics(node) }.map { it[2] + it[3] + it[4] }.getOrDefault(0L)
                } ?: break
                _state.update { it.copy(connectedPeers = blocks) }
                delay(if (blocks > 100) 5_000L else 1_000L)
            }
        }
    }

    private fun routingModeConstant(): Int = when (config.routingMode) {
        "delegated" -> FreedomIpfsNative.ROUTING_DELEGATED
        "light_dht" -> FreedomIpfsNative.ROUTING_LIGHT_DHT
        "offline" -> FreedomIpfsNative.ROUTING_OFFLINE
        // "auto" plus legacy Kubo values ("autoclient", "dht", "").
        else -> FreedomIpfsNative.ROUTING_AUTO
    }

    companion object {
        private const val TAG = "IpfsNode"

        private val leases = ConcurrentHashMap<String, Semaphore>()

        /** One permit per data dir, shared by every [IpfsNode] in the process. */
        private fun leaseFor(dataDir: String): Semaphore =
            leases.computeIfAbsent(File(dataDir).absoluteFile.normalize().path) { Semaphore(1) }
    }
}
