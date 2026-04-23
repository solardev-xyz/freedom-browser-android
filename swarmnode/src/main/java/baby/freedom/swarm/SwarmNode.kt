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
import mobile.Mobile
import mobile.MobileNode
import mobile.MobileNodeOptions

/**
 * Kotlin wrapper around the bee-lite `mobile.aar`.
 *
 * The UI observes [state]. Because it's a [StateFlow], any new collector
 * immediately receives the current value — there is no edge to miss.
 * This deliberately replaces the upstream listener-replay hack that
 * drops the Running edge if `addListener` runs after `start()` completes.
 */
class SwarmNode(
    private val config: Config,
) {
    data class Config(
        val dataDir: String,
        val password: String,
        /** Blockchain RPC endpoint — ignored in ultra-light mode; pass "". */
        val rpcEndpoint: String = "",
        val cacheEnabled: Boolean = true,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var mobileNode: MobileNode? = null
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
                val node = withContext(Dispatchers.IO) {
                    Mobile.startNode(buildOptions(), config.password, LOG_LEVEL)
                        ?: error("bee-lite startNode returned null")
                }
                mobileNode = node
                val wallet = runCatching { node.blockchainData()?.walletAddress.orEmpty() }
                    .getOrDefault("")
                _state.update {
                    it.copy(
                        status = NodeStatus.Running,
                        walletAddress = wallet,
                        errorMessage = null,
                    )
                }
                startPeerPolling(node)
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

        val node = mobileNode
        mobileNode = null

        _state.update {
            it.copy(status = NodeStatus.Stopped, connectedPeers = 0, walletAddress = "")
        }

        if (node != null) {
            scope.launch {
                runCatching { node.shutdown() }
                    .onFailure { Log.w(TAG, "shutdown threw", it) }
            }
        }
    }

    /** Cancel the internal scope; call from Service.onDestroy after [stop]. */
    fun dispose() {
        stop()
        scope.cancel()
    }

    private fun startPeerPolling(node: MobileNode) {
        peerPoller?.cancel()
        peerPoller = scope.launch {
            while (isActive) {
                val peers = runCatching { node.connectedPeerCount() }.getOrDefault(0L)
                _state.update { it.copy(connectedPeers = peers) }
                delay(if (peers > 100) 5_000L else 1_000L)
            }
        }
    }

    private fun buildOptions(): MobileNodeOptions = MobileNodeOptions().apply {
        fullNodeMode = false
        bootnodeMode = false
        // Bypass /dnsaddr/mainnet.ethswarm.org which breaks on Android emulator DNS.
        // See README.md → "Running on an emulator → DHT bootstrap".
        bootnodes = LEAF_BOOTNODES.joinToString("|")
        dataDir = config.dataDir + "/swarm"
        welcomeMessage = "Freedom"
        blockchainRpcEndpoint = config.rpcEndpoint
        swapInitialDeposit = "0"
        paymentThreshold = "100000000"
        // Ultra-light: no swap / chequebook / postage stamps.
        swapEnable = false
        chequebookEnable = false
        usePostageSnapshot = false
        mainnet = true
        networkID = 1L
        dbOpenFilesLimit = 50L
        dbWriteBufferSize = DB_BUFFER_BYTES
        dbBlockCacheCapacity = DB_BUFFER_BYTES
        dbDisableSeeksCompaction = false
        retrievalCaching = true
        cacheCapacity = if (config.cacheEnabled) DB_BUFFER_BYTES else 0L
    }

    companion object {
        /**
         * Canonical URL of the embedded bee-lite HTTP gateway. The port is
         * hard-coded in the AAR's `pkg/api` server and is not exposed via
         * [MobileNodeOptions], so this is effectively a compile-time fact
         * about the bundled node — safe to expose as a `const val`.
         */
        const val GATEWAY_URL: String = "http://127.0.0.1:1633"

        private const val TAG = "SwarmNode"
        private const val LOG_LEVEL = "3"
        private const val DB_BUFFER_BYTES = 32L * 1024 * 1024

        /**
         * Pre-resolved leaf multiaddrs for `_dnsaddr.mainnet.ethswarm.org`.
         * libp2p's `/dnsaddr/` resolver makes multi-hop TXT queries that the
         * Android emulator's DNS stack fails; passing the already-resolved
         * IP-based multiaddrs directly yields ~80+ peers within 60s on the
         * emulator. A long-term fix would periodically re-resolve these.
         */
        private val LEAF_BOOTNODES = listOf(
            "/ip4/159.223.6.181/tcp/1634/p2p/QmP9b7MxjyEfrJrch5jUThmuFaGzvUPpWEJewCpx5Ln6i8",
            "/ip4/135.181.84.53/tcp/1634/p2p/QmTxX73q8dDiVbmXU7GqMNwG3gWmjSFECuMoCsTW4xp6CK",
            "/ip4/139.84.229.70/tcp/1634/p2p/QmRa6rSrUWJ7s68MNmV94bo2KAa9pYcp6YbFLMHZ3r7n2M",
            "/ip4/172.104.43.205/tcp/1634/p2p/QmeovveLJmgyfjiA9mJnvFTawHyisuJMCYicJffdWdxNmr",
            "/ip4/170.64.184.25/tcp/1634/p2p/Qmeh2e7U2FWrSooyrjWjnNKGceJWbRxLLx8Ppy5CimzsGH",
        )
    }
}
