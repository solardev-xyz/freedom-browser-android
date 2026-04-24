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
import mobile.IpfsNode as MobileIpfsNode
import mobile.IpfsNodeOptions
import mobile.Mobile
import org.json.JSONObject
import java.io.File

/**
 * Kotlin wrapper around the bee-lite `mobile.aar`'s embedded Kubo node.
 *
 * Shape mirrors [SwarmNode] on purpose: the UI observes [state] via a
 * [StateFlow] so the Running edge can't be missed by late collectors,
 * and peer counts are polled on the same cadence.
 *
 * Passing `gatewayAddr = "127.0.0.1:0"` has the Go listener allocate an
 * ephemeral port; we read the real bound address back via
 * [MobileIpfsNode.gatewayAddr] after start and publish it as
 * [IpfsInfo.gatewayUrl] so the browser knows where to fetch `/ipfs/…`
 * and `/ipns/…`.
 */
class IpfsNode(
    private val config: Config,
) {
    data class Config(
        /**
         * Root directory where the Kubo repo (keys, blockstore,
         * datastore) lives. A dedicated subdirectory is recommended so
         * it doesn't collide with the Bee state store.
         */
        val dataDir: String,
        /**
         * Whether to apply Kubo's `lowpower` profile on first init.
         * Mobile default is `true` — smaller connection / stream limits
         * and a DHT client-only router, which are what the battery
         * budget wants.
         */
        val lowPower: Boolean = true,
        /**
         * Content routing strategy. Empty falls back to Kubo's `"dht"`
         * default. `"autoclient"` is recommended for mobile — delegated
         * routing for lookups, client-only DHT participation. See
         * `freedom-node-mobile/mobile/ipfs-wrapper.go::routingOption`.
         */
        val routingMode: String = "autoclient",
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var mobileNode: MobileIpfsNode? = null
    private var peerPoller: Job? = null

    private val _state = MutableStateFlow(IpfsInfo())
    val state: StateFlow<IpfsInfo> = _state.asStateFlow()

    fun start() {
        if (_state.value.status == IpfsStatus.Starting ||
            _state.value.status == IpfsStatus.Running
        ) return

        _state.update { it.copy(status = IpfsStatus.Starting, errorMessage = null) }

        scope.launch {
            try {
                withContext(Dispatchers.IO) { ensureDnsConfigPatched() }
                val node = withContext(Dispatchers.IO) {
                    Mobile.startIpfsNode(buildOptions(), LOG_LEVEL)
                        ?: error("Mobile.startIpfsNode returned null")
                }
                mobileNode = node
                val peerId = runCatching { node.peerID() }.getOrDefault("")
                val gatewayAddr = runCatching { node.gatewayAddr() }.getOrDefault("")
                _state.update {
                    it.copy(
                        status = IpfsStatus.Running,
                        peerId = peerId,
                        gatewayUrl = gatewayAddr.toGatewayBaseUrl(),
                        errorMessage = null,
                    )
                }
                startPeerPolling(node)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to start IPFS node", t)
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
        peerPoller?.cancel()
        peerPoller = null

        val node = mobileNode
        mobileNode = null

        _state.update {
            it.copy(
                status = IpfsStatus.Stopped,
                connectedPeers = 0,
                gatewayUrl = "",
            )
        }

        if (node != null) {
            scope.launch {
                runCatching { node.shutdown() }
                    .onFailure { Log.w(TAG, "shutdown threw", it) }
            }
        }
    }

    fun dispose() {
        stop()
        scope.cancel()
    }

    private fun startPeerPolling(node: MobileIpfsNode) {
        peerPoller?.cancel()
        peerPoller = scope.launch {
            while (isActive) {
                val peers = runCatching { node.connectedPeerCount() }.getOrDefault(0L)
                _state.update { it.copy(connectedPeers = peers) }
                delay(if (peers > 100) 5_000L else 1_000L)
            }
        }
    }

    private fun buildOptions(): IpfsNodeOptions = IpfsNodeOptions().apply {
        dataDir = config.dataDir
        // Ephemeral port — Go's net.Listen("tcp", "host:0") picks one
        // free; we read it back via gatewayAddr() after start(). Keeps
        // us out of port conflicts with anything else on the device.
        gatewayAddr = "127.0.0.1:0"
        offline = false
        lowPower = config.lowPower
        routingMode = config.routingMode
    }

    /**
     * Turn the `host:port` returned by [MobileIpfsNode.gatewayAddr] into a
     * full `http://host:port` base URL. Defensive if Go ever decides to
     * hand back an already-prefixed value.
     */
    private fun String.toGatewayBaseUrl(): String {
        if (isEmpty()) return ""
        if (startsWith("http://") || startsWith("https://")) return this
        return "http://$this"
    }

    /**
     * Kubo's default DNS resolver (`".": "auto"`) falls through to the Go
     * runtime's `net.DefaultResolver`, which on Android has no
     * `/etc/resolv.conf` and ends up trying `[::1]:53` — connection
     * refused. That breaks DNSLink resolution, which is how every
     * `ipns://<dns-name>` (e.g. `en.wikipedia-on-ipfs.org`) URL works,
     * and also some bootstrap/provider `/dnsaddr/…` multiaddrs.
     *
     * Mirrors what freedom-browser desktop does in
     * `src/main/ipfs-manager.js::enforceConfig` — point the default
     * resolver at Cloudflare's DoH endpoint and give `.eth` names a
     * dedicated resolver so ENS → DNSLink paths also work. DoH is
     * HTTPS-based, so we don't need a working system UDP resolver.
     *
     * First run of the node has no config file yet (Kubo creates it on
     * first `startIpfsNode`). In that case we boot a short-lived
     * `offline=true` node first — init still writes the repo, and
     * offline mode means DNS isn't touched — then shut it down,
     * patch, and let the caller start for real. Subsequent starts
     * just patch-then-start.
     */
    private suspend fun ensureDnsConfigPatched() {
        val configFile = File(config.dataDir, "config")
        if (!configFile.exists()) {
            Log.i(TAG, "IPFS repo uninitialized — running offline init so DNS can be patched")
            val initOpts = IpfsNodeOptions().apply {
                dataDir = config.dataDir
                gatewayAddr = "127.0.0.1:0"
                offline = true
                lowPower = config.lowPower
                routingMode = config.routingMode
            }
            val node = runCatching { Mobile.startIpfsNode(initOpts, LOG_LEVEL) }
                .onFailure { Log.w(TAG, "offline init failed", it) }
                .getOrNull()
            runCatching { node?.shutdown() }
                .onFailure { Log.w(TAG, "offline init shutdown failed", it) }
        }
        if (!configFile.exists()) {
            Log.w(TAG, "Kubo config still missing after init; skipping DNS patch")
            return
        }
        try {
            val json = JSONObject(configFile.readText())
            val dns = json.optJSONObject("DNS") ?: JSONObject().also { json.put("DNS", it) }
            val resolvers = dns.optJSONObject("Resolvers")
                ?: JSONObject().also { dns.put("Resolvers", it) }
            val needsUpdate = resolvers.optString(".") != DEFAULT_DOH ||
                resolvers.optString("eth.") != ETH_DOH
            if (!needsUpdate) return
            resolvers.put(".", DEFAULT_DOH)
            resolvers.put("eth.", ETH_DOH)
            configFile.writeText(json.toString(2))
            Log.i(TAG, "patched Kubo DNS resolvers for DoH (.=$DEFAULT_DOH, eth.=$ETH_DOH)")
        } catch (t: Throwable) {
            Log.w(TAG, "failed to patch Kubo DNS config", t)
        }
    }

    companion object {
        private const val TAG = "IpfsNode"
        // Kubo reads log-level from the environment, not from options;
        // this string is just a cosmetic verbosity hint the Go wrapper
        // carries through so both nodes share a vocabulary.
        private const val LOG_LEVEL = "3"

        /** DoH endpoint used as the default DNS resolver for Kubo. */
        private const val DEFAULT_DOH = "https://cloudflare-dns.com/dns-query"

        /** Dedicated resolver for `.eth` names so ENS → DNSLink works. */
        private const val ETH_DOH = "https://dns.eth.limo/dns-query"
    }
}
