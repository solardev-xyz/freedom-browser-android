package baby.freedom.mobile.node

import android.app.Application
import android.app.Service
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.IBinder
import android.os.RemoteCallbackList
import android.util.Log
import baby.freedom.swarm.MyotisInfo
import baby.freedom.swarm.MyotisNetwork
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
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger
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

    /** Read from binder threads too ([IMyotisService.ethCall]). */
    @Volatile
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

        override fun getLogs(): String = NodeLogs.text(NodeLogSource.LightClient)

        override fun clearLogs() = NodeLogs.clear()

        override fun onAppForeground() {
            node?.enterForeground()
        }

        override fun onAppBackground() {
            node?.enterBackground()
        }

        override fun retryRecovery(chainId: Long) {
            MyotisNetwork.forChain(chainId)?.let { node?.retryRecovery(it) }
        }

        override fun repairSyncData(chainId: Long) {
            MyotisNetwork.forChain(chainId)?.let { node?.repairSyncData(it) }
        }

        override fun ethCall(chainId: Long, to: String?, data: String?, probe: Boolean, result: IMyotisCallResult?) {
            result ?: return
            val node = node
            val network = MyotisNetwork.forChain(chainId)
            if (node == null || network == null || to == null || data == null) {
                reply(result, MyotisNode.NOT_READY_JSON)
                return
            }
            // The engine runs at most eight EVM executions at once and
            // fails the rest as busy: don't queue more than that here. A
            // started call can't be cancelled, so one its caller gave up
            // on keeps its slot until the engine returns; one slot is kept
            // for the resolver's health probe, so lookups filling the rest
            // (a page's slow names) can't make a healthy engine look broken.
            val slots = if (probe) probesInFlight else lookupsInFlight
            val limit = if (probe) PROBE_SLOTS else MAX_CALLS_IN_FLIGHT - PROBE_SLOTS
            if (slots.incrementAndGet() > limit) {
                slots.decrementAndGet()
                reply(result, BUSY_JSON)
                return
            }
            reads.launch {
                val json = try {
                    node.ethCall(network, to, data)
                } catch (t: Throwable) {
                    """{"error":${JSONObject.quote(t.message ?: t.javaClass.simpleName)}}"""
                } finally {
                    slots.decrementAndGet()
                }
                // A call result is a few hundred bytes; the cap keeps a
                // pathological one inside the binder buffer it shares.
                reply(result, if (json.length <= MAX_RESULT_CHARS) json else TOO_LARGE_JSON)
            }
        }
    }

    /** Where [IMyotisService.ethCall]s run: each blocks for as long as the engine takes. */
    private val reads = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lookupsInFlight = AtomicInteger()
    private val probesInFlight = AtomicInteger()

    private fun reply(result: IMyotisCallResult, json: String) {
        // The caller may be gone (its process died); nothing to tell it.
        runCatching { result.onResult(json) }
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
        NodeLogs.start(Application.getProcessName()) { _, _ -> NodeLogSource.LightClient }
        if (exiting) {
            Log.i(TAG, "created while the previous engines are still stopping; waiting for the process exit")
            return
        }
        val node = MyotisNode(filesDir.resolve("myotis"))
        this.node = node
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        node.state
            .onEach { info ->
                broadcast(info)
                // Every poll (3 s in front) can publish a new state: debug
                // builds only, so a release build doesn't fill logcat.
                if (debuggable) Log.d(
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
        reads.cancel()
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
        private const val MAX_CALLS_IN_FLIGHT = 8
        private const val PROBE_SLOTS = 1
        private const val MAX_RESULT_CHARS = 64 * 1024
        private const val BUSY_JSON = """{"status":"unavailable","reason":"busy","busy":true}"""
        private const val TOO_LARGE_JSON = """{"error":"result too large"}"""

        /** Set once this process has begun its shutdown-and-exit. */
        @Volatile
        private var exiting = false
    }
}
