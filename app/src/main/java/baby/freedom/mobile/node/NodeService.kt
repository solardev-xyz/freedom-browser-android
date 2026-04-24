package baby.freedom.mobile.node

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.RemoteCallbackList
import android.util.Log
import baby.freedom.mobile.R
import baby.freedom.mobile.data.NodeSettings
import baby.freedom.swarm.IpfsInfo
import baby.freedom.swarm.IpfsNode
import baby.freedom.swarm.NodeInfo
import baby.freedom.swarm.NodeStatus
import baby.freedom.swarm.SwarmNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.system.exitProcess

/**
 * Holds the embedded Swarm + IPFS nodes for the lifetime of the `:node`
 * process. The service is started + bound while the user wants the
 * node(s) running; when the user flips the Swarm toggle off the UI
 * calls [stopService] + [unbindService], Android destroys this Service,
 * and [onDestroy] kills the process outright so that bee-lite's
 * LevelDB state-store file lock is actually released — something
 * `MobileNode.shutdown()` alone does not do. Killing the process also
 * tears down the IPFS node, which is the behaviour the user's global
 * "Run node" toggle implies anyway.
 */
class NodeService : Service() {

    private lateinit var swarmNode: SwarmNode
    private var ipfsNode: IpfsNode? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var swarmObserver: Job? = null
    private var ipfsObserver: Job? = null

    /**
     * Bound UI clients that want state updates. [RemoteCallbackList]
     * keeps this Binder-death-safe so we don't leak callbacks when the
     * UI process is restarted.
     */
    private val callbacks = RemoteCallbackList<INodeCallback>()

    private val binder = object : INodeService.Stub() {
        override fun getState(): NodeInfo = swarmNode.state.value

        override fun getIpfsState(): IpfsInfo = ipfsNode?.state?.value ?: IpfsInfo()

        override fun registerCallback(cb: INodeCallback?) {
            cb ?: return
            callbacks.register(cb)
            runCatching { cb.onStateChanged(swarmNode.state.value) }
            runCatching { cb.onIpfsStateChanged(ipfsNode?.state?.value ?: IpfsInfo()) }
        }

        override fun unregisterCallback(cb: INodeCallback?) {
            cb ?: return
            callbacks.unregister(cb)
        }

        override fun ensureIpfsStarted() {
            scope.launch { maybeStartIpfs() }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createChannel()

        swarmNode = SwarmNode(
            SwarmNode.Config(
                dataDir = filesDir.absolutePath,
                password = NODE_KEYSTORE_PASSWORD,
                rpcEndpoint = "",
                cacheEnabled = true,
            ),
        )

        startForeground(
            NOTIFICATION_ID,
            buildNotification(NodeInfo()),
            foregroundTypeCompat(),
        )

        swarmObserver = swarmNode.state
            .onEach { info ->
                updateNotification(info)
                broadcastState(info)
                Log.i(TAG, "swarm → ${info.status}  peers=${info.connectedPeers}")
            }
            .launchIn(scope)

        swarmNode.start()

        // IPFS is NOT started here. Cold boot leaves the Kubo node
        // dormant so users who never visit `ipfs://` / IPFS-resolved
        // ENS content don't pay the bootstrap cost (or the background
        // peer churn) for a network they'll never use. The UI calls
        // [INodeService.ensureIpfsStarted] the first time a navigation
        // actually needs IPFS, which routes into [maybeStartIpfs]
        // below — idempotent, so repeated calls after start are
        // cheap no-ops.
    }

    /**
     * Idempotently bring the IPFS node up. Called lazily from the UI
     * via [INodeService.ensureIpfsStarted] on the first `ipfs://` /
     * `ipns://` / IPFS-resolved `ens://` navigation. Subsequent calls
     * short-circuit — once [ipfsNode] exists we let it run for the
     * lifetime of this `:node` process.
     *
     * Honors the hidden `runIpfsEnabled` opt-out: if the user has
     * flipped IPFS off in Settings → Other, we broadcast a Stopped
     * state so the navigation gate can surface a clear error instead
     * of spinning forever.
     */
    private suspend fun maybeStartIpfs() {
        if (ipfsNode != null) return
        val settings = NodeSettings.get(this)
        val enabled = settings.runIpfsEnabled.first()
        if (!enabled) {
            Log.i(TAG, "ipfs disabled; skipping start")
            broadcastIpfsState(IpfsInfo())
            return
        }
        val lowPower = settings.ipfsLowPower.first()
        val routingMode = settings.ipfsRoutingMode.first()

        val node = IpfsNode(
            IpfsNode.Config(
                dataDir = filesDir.resolve("ipfs").absolutePath,
                lowPower = lowPower,
                routingMode = routingMode,
            ),
        )
        ipfsNode = node

        ipfsObserver = node.state
            .onEach { info ->
                broadcastIpfsState(info)
                Log.i(
                    TAG,
                    "ipfs → ${info.status}  peers=${info.connectedPeers}  gw=${info.gatewayUrl}",
                )
            }
            .launchIn(scope)

        node.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        callbacks.kill()
        swarmObserver?.cancel()
        ipfsObserver?.cancel()
        scope.cancel()
        ipfsNode?.dispose()
        ipfsNode = null
        swarmNode.dispose()
        super.onDestroy()

        // Kill the :node process so the kernel releases bee-lite's
        // LevelDB LOCK file. The next startForegroundService() from
        // the UI will boot a fresh process that can open the store.
        Log.i(TAG, "exiting :node process to release state-store lock")
        exitProcess(0)
    }

    private fun broadcastState(info: NodeInfo) {
        val n = callbacks.beginBroadcast()
        for (i in 0 until n) {
            runCatching { callbacks.getBroadcastItem(i).onStateChanged(info) }
                .onFailure { Log.w(TAG, "callback threw", it) }
        }
        callbacks.finishBroadcast()
    }

    private fun broadcastIpfsState(info: IpfsInfo) {
        val n = callbacks.beginBroadcast()
        for (i in 0 until n) {
            runCatching { callbacks.getBroadcastItem(i).onIpfsStateChanged(info) }
                .onFailure { Log.w(TAG, "ipfs callback threw", it) }
        }
        callbacks.finishBroadcast()
    }

    private fun createChannel() {
        val mgr = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.node_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.node_channel_description)
            setShowBadge(false)
        }
        mgr.createNotificationChannel(channel)
    }

    private fun updateNotification(info: NodeInfo) {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.notify(NOTIFICATION_ID, buildNotification(info))
    }

    // The notification intentionally reflects only the Swarm node.
    // Surfacing IPFS here would spoil the "look, vitalik.eth also
    // works" demo moment; the hidden settings card is the one place
    // IPFS status is visible today.
    private fun buildNotification(info: NodeInfo): Notification {
        val text = when (info.status) {
            NodeStatus.Stopped -> "Stopped"
            NodeStatus.Starting -> "Starting…"
            NodeStatus.Running -> "Running — ${info.connectedPeers} peers"
            NodeStatus.Error -> "Error: ${info.errorMessage ?: "unknown"}"
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.node_notification_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
    }

    private fun foregroundTypeCompat(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }

    companion object {
        private const val TAG = "NodeService"
        private const val CHANNEL_ID = "freedom_node"
        private const val NOTIFICATION_ID = 1

        // Bee-lite's startNode() requires a non-empty password to encrypt the
        // on-device keystore for the ultra-light-mode wallet it generates on
        // first launch. In ultra-light mode the wallet is read-only (no swap,
        // no postage stamps, no funds), so this is a throwaway per-install
        // secret, not a user credential. Safe to be a compile-time constant.
        private const val NODE_KEYSTORE_PASSWORD = "freedom-keystore"

        fun start(ctx: Context) {
            val i = Intent(ctx, NodeService::class.java)
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, NodeService::class.java))
        }
    }
}
