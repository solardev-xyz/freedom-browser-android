package baby.freedom.mobile.node

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.RemoteCallbackList
import android.util.Log
import baby.freedom.swarm.TorInfo
import baby.freedom.swarm.TorNode
import baby.freedom.swarm.TorStatus
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
 * Holds the embedded Arti (Tor) client ([TorNode]) for the lifetime of the
 * `:tor` process (#143).
 *
 * A bound-only service, like [MyotisService]: `MainActivity` binds it
 * while Tor should run (switched on from the node page, or at launch with
 * Settings → Tor → Start Tor at launch) and unbinds when it's
 * switched off, Tor is turned off in Settings, or the UI goes away.
 * Creating the service starts the client; destroying it stops the client
 * and exits the process.
 *
 * Its own process: independent of the Swarm toggle (which kills `:node`),
 * and a native fault in Arti can't take the browser down — it only closes
 * the SOCKS port, and the UI, seeing the binding die, stops routing
 * `.onion` to it (fail closed, see `TorRouting`).
 *
 * Not a foreground service: nothing needs Tor while no page is on screen,
 * and Android freezing the process in the background only pauses it.
 */
class TorService : Service() {

    private var node: TorNode? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val callbacks = RemoteCallbackList<ITorCallback>()

    private val binder = object : ITorService.Stub() {
        override fun getState(): TorInfo = currentState()

        override fun registerCallback(cb: ITorCallback?) {
            cb ?: return
            callbacks.register(cb)
            runCatching { cb.onTorStateChanged(currentState()) }
        }

        override fun unregisterCallback(cb: ITorCallback?) {
            cb ?: return
            callbacks.unregister(cb)
        }
    }

    /**
     * A service instance created while the previous client is still
     * stopping for a process exit (a quick off → on) must not open the
     * same state directory; it reports Starting, and the process exit
     * brings the binding back up in a fresh process. No port: nothing is
     * routed to it.
     */
    private fun currentState(): TorInfo =
        node?.state?.value ?: TorInfo(status = TorStatus.Starting)

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        if (exiting) {
            Log.i(TAG, "created while the previous client is still stopping; waiting for the process exit")
            return
        }
        val node = TorNode(filesDir.resolve("tor"))
        this.node = node
        node.state
            .onEach { info ->
                broadcast(info)
                Log.i(TAG, "tor → ${info.status} ${info.progress}% port=${info.socksPort}${info.errorMessage?.let { " error=$it" } ?: ""}")
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
        // Off the main thread, bounded, then exit so nothing native — the
        // tokio runtime, the state-dir lock — outlives the switch.
        Thread({
            val stopped = runBlocking { withTimeoutOrNull(STOP_TIMEOUT_MS) { node?.shutdown() } != null }
            Log.i(TAG, "exiting :tor process (client stopped cleanly: $stopped)")
            exitProcess(0)
        }, "tor-shutdown").start()
    }

    private fun broadcast(info: TorInfo) {
        val n = callbacks.beginBroadcast()
        for (i in 0 until n) {
            runCatching { callbacks.getBroadcastItem(i).onTorStateChanged(info) }
                .onFailure { Log.w(TAG, "tor callback threw", it) }
        }
        callbacks.finishBroadcast()
    }

    companion object {
        private const val TAG = "TorService"
        private const val STOP_TIMEOUT_MS = 5_000L

        /** Set once this process has begun its shutdown-and-exit. */
        @Volatile
        private var exiting = false
    }
}
