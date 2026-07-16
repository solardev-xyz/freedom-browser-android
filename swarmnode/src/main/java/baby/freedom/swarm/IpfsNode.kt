package baby.freedom.swarm

import android.util.Log
import kotlinx.coroutines.CoroutineScope
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
import kotlinx.coroutines.withContext

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
class IpfsNode(
    private val config: Config,
) {
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
    private var handle: Long = 0L
    private var statsPoller: Job? = null

    private val _state = MutableStateFlow(IpfsInfo())
    val state: StateFlow<IpfsInfo> = _state.asStateFlow()

    fun start() {
        if (_state.value.status == IpfsStatus.Starting ||
            _state.value.status == IpfsStatus.Running
        ) return

        _state.update { it.copy(status = IpfsStatus.Starting, errorMessage = null) }

        scope.launch {
            try {
                val gatewayUrl = withContext(Dispatchers.IO) {
                    val node = FreedomIpfsNative.nodeNew(config.dataDir, 0L)
                    if (node == 0L) error("freedom_ipfs_node_new_with_data_dir failed")
                    handle = node
                    if (!FreedomIpfsNative.startGatewayOnline(
                            node, "127.0.0.1:0", routingModeConstant())
                    ) {
                        error("freedom_ipfs start_gateway_online failed")
                    }
                    FreedomIpfsNative.gatewayUrl(node)
                        ?: error("gateway started but reported no URL")
                }
                Log.i(TAG, "freedom-ipfs ${FreedomIpfsNative.version()} gateway at $gatewayUrl")
                _state.update {
                    it.copy(
                        status = IpfsStatus.Running,
                        gatewayUrl = gatewayUrl,
                        errorMessage = null,
                    )
                }
                startStatsPolling()
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to start IPFS node", t)
                releaseHandle()
                _state.update {
                    it.copy(
                        status = IpfsStatus.Error,
                        errorMessage = t.message ?: t.javaClass.simpleName,
                    )
                }
            }
        }
    }

    fun stop() {
        statsPoller?.cancel()
        statsPoller = null

        _state.update {
            it.copy(
                status = IpfsStatus.Stopped,
                connectedPeers = 0,
                gatewayUrl = "",
            )
        }

        scope.launch { withContext(Dispatchers.IO) { releaseHandle() } }
    }

    fun dispose() {
        stop()
        scope.cancel()
    }

    /** Forward connectivity changes so stale provider state is dropped. */
    fun onNetworkChanged() {
        val node = handle
        if (node != 0L && _state.value.status == IpfsStatus.Running) {
            scope.launch { FreedomIpfsNative.handleNetworkChange(node) }
        }
    }

    private fun releaseHandle() {
        val node = handle
        handle = 0L
        if (node != 0L) {
            runCatching { FreedomIpfsNative.stopGateway(node) }
                .onFailure { Log.w(TAG, "stopGateway threw", it) }
            runCatching { FreedomIpfsNative.nodeFree(node) }
                .onFailure { Log.w(TAG, "nodeFree threw", it) }
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
                val node = handle
                if (node == 0L) break
                val blocks = runCatching { FreedomIpfsNative.diagnostics(node) }
                    .map { it[2] + it[3] + it[4] }
                    .getOrDefault(0L)
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
    }
}
