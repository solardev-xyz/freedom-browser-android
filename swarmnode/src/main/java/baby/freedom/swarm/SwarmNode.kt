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
class SwarmNode(
    private val config: Config,
) {
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

    private val _state = MutableStateFlow(NodeInfo())
    val state: StateFlow<NodeInfo> = _state.asStateFlow()

    fun start() {
        if (_state.value.status == NodeStatus.Starting ||
            _state.value.status == NodeStatus.Running
        ) return

        _state.update { it.copy(status = NodeStatus.Starting, errorMessage = null) }

        scope.launch {
            try {
                val h = withContext(Dispatchers.IO) {
                    val h = AntNative.init(config.dataDir + "/ant")
                    try {
                        AntNative.startGateway(
                            handle = h,
                            apiAddr = GATEWAY_ADDR,
                            // Ultra-light: read path only, no publishing.
                            lightMode = false,
                            gnosisRpc = config.rpcEndpoint,
                        )
                    } catch (t: Throwable) {
                        runCatching { AntNative.shutdown(h) }
                        throw t
                    }
                    h
                }
                handle = h
                val agent = runCatching { AntNative.agentString(h) }.getOrNull().orEmpty()
                _state.update {
                    it.copy(
                        status = NodeStatus.Running,
                        clientVersion = agent,
                        errorMessage = null,
                    )
                }
                startPeerPolling()
            } catch (t: Throwable) {
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

    fun stop() {
        peerPoller?.cancel()
        peerPoller = null

        val h = handle
        handle = 0L

        _state.update {
            it.copy(status = NodeStatus.Stopped, connectedPeers = 0, clientVersion = "")
        }

        if (h != 0L) {
            scope.launch {
                runCatching {
                    AntNative.stopGateway(h)
                    AntNative.shutdown(h)
                }.onFailure { Log.w(TAG, "shutdown threw", it) }
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
                val peers = runCatching { AntNative.peerCount(h) }.getOrDefault(-1)
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
