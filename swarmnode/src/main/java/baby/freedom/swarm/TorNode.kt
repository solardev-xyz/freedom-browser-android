package baby.freedom.swarm

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Kotlin wrapper around the embedded Arti (Tor) client ([TorNative]): one
 * client per process, reachable only through its loopback SOCKS5 listener,
 * which connects to `.onion` hosts and nothing else (the Rust side refuses
 * any other target). The browser routes `*.onion` — and only that — to
 * [TorInfo.socksPort] (#143).
 *
 * [start] and [stop] run one at a time, in call order, on [lifecycle];
 * while the client is up a poller reads the bootstrap status (every
 * second while bootstrapping, every [POLL_RUNNING_MS] after) into [state].
 */
class TorNode internal constructor(
    private val dataDir: File,
    private val ops: Ops,
    private val pollBootstrapMs: Long = POLL_BOOTSTRAP_MS,
    private val pollRunningMs: Long = POLL_RUNNING_MS,
) {
    /** [dataDir] holds Arti's state (`state/`, guards) and directory cache (`cache/`). */
    constructor(dataDir: File) : this(dataDir, Ops.Native)

    /** The `freedom_tor_*` calls [TorNode] makes; swapped for a fake in tests. */
    internal interface Ops {
        /** `null` on success, else the failure message. */
        fun start(stateDir: String, cacheDir: String): String?
        fun stop()
        fun statusJson(): String?
        fun version(): String

        object Native : Ops {
            override fun start(stateDir: String, cacheDir: String) =
                TorNative.start(stateDir, cacheDir)?.toString(Charsets.UTF_8)
            override fun stop() = TorNative.stop()
            override fun statusJson() = TorNative.statusJson()?.toString(Charsets.UTF_8)
            override fun version() = TorNative.version()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val lifecycle = Dispatchers.IO.limitedParallelism(1)

    private val _state = MutableStateFlow(TorInfo())
    val state: StateFlow<TorInfo> = _state.asStateFlow()

    /** The status poller of the running client; touched only on [lifecycle]. */
    private var poller: Job? = null

    /**
     * Whether the poller may still publish. Read and cleared under
     * `this`, together with the state write, so a poll that read the
     * status just before a stop can't put the closed port back.
     */
    private var publishing = false

    /**
     * Start the client if it isn't running. A library without the Tor
     * client (a `libfreedom_mobile_ffi.so` built without the `tor`
     * feature) reports [TorStatus.Error] rather than throwing.
     */
    fun start() {
        scope.launch(lifecycle) {
            if (poller != null) return@launch
            val version = runCatching { ops.version() }.getOrElse { t ->
                Log.w(TAG, "Tor client unavailable", t)
                _state.value = TorInfo(status = TorStatus.Error, errorMessage = UNAVAILABLE)
                return@launch
            }
            _state.value = TorInfo(status = TorStatus.Starting, version = version)
            val err = runCatching {
                ops.start(
                    File(dataDir, "state").absolutePath,
                    File(dataDir, "cache").absolutePath,
                )
            }.getOrElse { it.message ?: it.toString() }
            if (err != null) {
                Log.w(TAG, "start failed: $err")
                _state.value = TorInfo(status = TorStatus.Error, version = version, errorMessage = err)
                return@launch
            }
            synchronized(this@TorNode) { publishing = true }
            poller = scope.launch { poll(version) }
        }
    }

    /** Stop the client: its port closes, so `.onion` requests fail from here on. */
    fun stop() {
        scope.launch(lifecycle) { stopNow() }
    }

    /** Stop and wait for it; for the service's teardown. */
    suspend fun shutdown() {
        withContext(lifecycle) { stopNow() }
        scope.cancel()
    }

    private suspend fun stopNow() {
        val p = poller ?: run {
            if (_state.value.status != TorStatus.Stopped) {
                _state.value = TorInfo(version = _state.value.version)
            }
            return
        }
        p.cancel()
        poller = null
        // Published before the native stop: the port is about to close,
        // and nothing may be routed to it from here on.
        synchronized(this) {
            publishing = false
            _state.value = TorInfo(version = _state.value.version)
        }
        runCatching { ops.stop() }.onFailure { Log.w(TAG, "stop failed", it) }
        p.join()
    }

    private suspend fun poll(version: String) {
        while (scope.isActive) {
            val info = parseStatus(runCatching { ops.statusJson() }.getOrNull(), version)
            synchronized(this) {
                // A poll that raced [stopNow] must not publish the old port again.
                if (!publishing) return
                _state.value = info
            }
            delay(if (info.status == TorStatus.Running) pollRunningMs else pollBootstrapMs)
        }
    }

    companion object {
        private const val TAG = "TorNode"
        const val POLL_BOOTSTRAP_MS = 1_000L
        const val POLL_RUNNING_MS = 5_000L
        internal const val UNAVAILABLE = "This build of Freedom has no Tor client"

        /**
         * [TorInfo] from `freedom_tor_status_json` (freedom_tor.h). Anything
         * unparseable, or a state other than bootstrapping/running, reads
         * as not listening (port 0), so it's never routed to.
         */
        internal fun parseStatus(json: String?, version: String): TorInfo {
            val o = runCatching { JSONObject(json ?: "") }.getOrNull()
                ?: return TorInfo(status = TorStatus.Error, version = version, errorMessage = "No status")
            val port = o.optInt("port", 0).takeIf { it in 1..65535 } ?: 0
            val status = when (o.optString("state")) {
                "running" -> TorStatus.Running
                "bootstrapping" -> TorStatus.Starting
                "stopped" -> return TorInfo(version = version)
                else -> return TorInfo(status = TorStatus.Error, version = version, errorMessage = "Unknown state")
            }
            if (port == 0) {
                return TorInfo(status = TorStatus.Error, version = version, errorMessage = "No SOCKS port")
            }
            fun str(key: String) = if (o.isNull(key)) null else o.optString(key).takeIf { it.isNotBlank() }
            val progress = Math.round(o.optDouble("progress", 0.0).coerceIn(0.0, 1.0) * 100).toInt()
            // A bootstrap that's stuck says why (Arti's "blocked" reason);
            // an error from the bootstrap task is kept while it retries.
            val problem = str("blocked") ?: str("error")
            return TorInfo(
                status = status,
                socksPort = port,
                progress = progress,
                summary = str("summary").orEmpty(),
                version = version,
                errorMessage = if (status == TorStatus.Running) null else problem,
            )
        }
    }
}
