package baby.freedom.mobile.node

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.RemoteCallbackList
import android.util.Log
import baby.freedom.swarm.MyotisInfo
import baby.freedom.swarm.MyotisNode
import baby.freedom.swarm.MyotisStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.system.exitProcess

/**
 * Holds the embedded Myotis light client ([MyotisNode]: Ethereum mainnet
 * and Gnosis) for the lifetime of the `:myotis` process (#72).
 *
 * A bound-only service: `MainActivity` binds it while the user has the
 * light client switched on (off by default — Settings is
 * [baby.freedom.mobile.data.NodeSettings.myotisEnabled]) and unbinds when
 * they switch it off or the UI goes away. Creating the service starts the
 * engines; destroying it stops them and exits the process, the same way
 * [NodeService] releases ant's and freedom-ipfs's native state.
 *
 * Its own process rather than `:node`: the light client is independent of
 * the Swarm toggle (switching Swarm off kills `:node`), and a native fault
 * in it can't take the browser — or the Swarm node — down with it.
 *
 * Not a foreground service: the engines idle-sleep while the app is in
 * the background ([IMyotisService.onAppBackground]), as on iOS, so there is
 * nothing to keep running.
 */
class MyotisService : Service() {

    private var node: MyotisNode? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val callbacks = RemoteCallbackList<IMyotisCallback>()

    private val binder = object : IMyotisService.Stub() {
        override fun getState(): MyotisInfo = currentState()

        override fun registerCallback(cb: IMyotisCallback?) {
            cb ?: return
            callbacks.register(cb)
            runCatching { cb.onMyotisStateChanged(currentState()) }
        }

        override fun unregisterCallback(cb: IMyotisCallback?) {
            cb ?: return
            callbacks.unregister(cb)
        }

        override fun onAppForeground() {
            node?.enterForeground()
        }

        override fun onAppBackground() {
            node?.enterBackground()
        }
    }

    /**
     * The engines are stopping for a process exit (see [onDestroy]) — a
     * service instance created in that window (the user switched the light
     * client straight back on) must not open the same data directories
     * the old engines still hold. It reports Starting; the process exit
     * disconnects the UI, whose binding brings up a fresh process.
     */
    private fun currentState(): MyotisInfo =
        node?.state?.value ?: MyotisInfo(status = MyotisStatus.Starting)

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        if (exiting) {
            Log.i(TAG, "created while the previous engines are still stopping; waiting for the process exit")
            return
        }
        val node = MyotisNode(filesDir.resolve("myotis"))
        this.node = node
        node.state
            .onEach { info ->
                broadcast(info)
                Log.i(
                    TAG,
                    "myotis → ${info.status} " + info.chains.joinToString(" ") {
                        "[${it.chainId} ${it.beaconState} peers=${it.peerCount}/${it.snapPeers}/${it.snapServingPeers} " +
                            "head=${it.headBlock} fin=${it.finalizedBlock}${it.error?.let { e -> " error=$e" } ?: ""}]"
                    },
                )
            }
            .launchIn(scope)
        node.start()
    }

    override fun onDestroy() {
        callbacks.kill()
        scope.cancel()
        val node = node
        this.node = null
        super.onDestroy()
        if (node == null && exiting) return
        exiting = true
        // myotis_stop has no wall-clock bound: stop off the main thread
        // (a service callback stuck here would ANR), give the engines a
        // bounded chance to drain and persist, then exit so nothing
        // native — tokio runtimes, the data-dir locks — outlives the
        // switch.
        Thread({
            val stopped = runBlocking { withTimeoutOrNull(STOP_TIMEOUT_MS) { node?.shutdown() } != null }
            Log.i(TAG, "exiting :myotis process (engines stopped cleanly: $stopped)")
            exitProcess(0)
        }, "myotis-shutdown").start()
    }

    private fun broadcast(info: MyotisInfo) {
        val n = callbacks.beginBroadcast()
        for (i in 0 until n) {
            runCatching { callbacks.getBroadcastItem(i).onMyotisStateChanged(info) }
                .onFailure { Log.w(TAG, "myotis callback threw", it) }
        }
        callbacks.finishBroadcast()
    }

    companion object {
        private const val TAG = "MyotisService"
        private const val STOP_TIMEOUT_MS = 10_000L

        /** Set once this process has begun its shutdown-and-exit. */
        @Volatile
        private var exiting = false
    }
}
