package baby.freedom.mobile.node

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import baby.freedom.mobile.R
import baby.freedom.swarm.NodeInfo
import baby.freedom.swarm.NodeStatus
import baby.freedom.swarm.SwarmNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn

/**
 * Holds the embedded Swarm node for the lifetime of the app, surviving
 * UI rotation and backgrounding via a foreground service notification.
 */
class NodeService : Service() {

    private lateinit var swarmNode: SwarmNode
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null

    val state: StateFlow<NodeInfo> get() = swarmNode.state

    inner class LocalBinder : Binder() {
        val state: StateFlow<NodeInfo> get() = this@NodeService.state
    }

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

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

        observer = swarmNode.state
            .onEach { info ->
                updateNotification(info)
                Log.i(TAG, "node → ${info.status}  peers=${info.connectedPeers}")
            }
            .launchIn(scope)

        swarmNode.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        observer?.cancel()
        scope.cancel()
        swarmNode.dispose()
        super.onDestroy()
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
    }
}
