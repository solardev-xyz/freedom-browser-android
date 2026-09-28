package baby.freedom.swarm

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Kotlin wrapper around the embedded Myotis Ethereum light client
 * ([MyotisNative]): one engine handle per [MyotisNetwork] (Ethereum
 * mainnet and Gnosis), each a fully peer-to-peer light client — beacon
 * light-client sync on the consensus layer, snap-serving peers on the
 * execution layer, every read proven against a sync-committee-anchored
 * state root. There's no gateway or port: reads go through the C ABI
 * in-process, so the interesting state is per-chain sync progress
 * ([MyotisChainStatus]), published on [state].
 *
 * Lifecycle is create → start → (pause / resume with the app's
 * foreground) → stop. The engine requires start/pause/resume/stop to be
 * serialized per handle and every one of them may block (stop has no
 * wall-clock bound), so every engine call runs on one queue ([ops]) in
 * the order it was asked for: a Stop sent right after a Start always
 * lands after it, and a status poll never reads a handle mid-teardown.
 *
 * Same shape as the iOS `MyotisNode` (freedom-browser-ios), minus its
 * stale-anchor checkpoint recovery, seed pins and verified reads, which
 * come with their consumers.
 */
class MyotisNode internal constructor(
    private val dataDir: File,
    private val engine: Engine,
    private val networks: List<MyotisNetwork> = MyotisNetwork.entries,
    private val pollIntervalMs: Long = POLL_INTERVAL_MS,
    /** Wall clock, the one the engine judges an anchor's age by. */
    private val wallClock: () -> Long = System::currentTimeMillis,
    /** Monotonic clock that keeps counting through deep sleep. */
    private val upClock: () -> Long = SystemClock::elapsedRealtime,
) {
    constructor(dataDir: File) : this(dataDir, NativeEngine)

    /** The engine calls [MyotisNode] makes; [NativeEngine] in the app, a fake in unit tests. */
    internal interface Engine {
        /** `myotis_init`: the engine's ABI version. */
        fun init(): Int

        /** The ABI version this wrapper (and the vendored header) was written against. */
        val expectedAbi: Int
        fun create(network: String, dataDir: String): Long
        fun start(handle: Long): Boolean
        fun stop(handle: Long)
        fun pause(handle: Long): Boolean
        fun resume(handle: Long): Boolean
        fun setServedBlockWindow(handle: Long, blocks: Int): Boolean
        fun statusJson(handle: Long): String?
        fun drainLogs(max: Int): String?
    }

    private sealed interface Op {
        data object Start : Op
        class Stop(val done: CompletableDeferred<Unit>? = null) : Op
        data object Background : Op
        data object Foreground : Op
        data object Poll : Op
        class Barrier(val done: CompletableDeferred<Unit>) : Op
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ops = Channel<Op>(Channel.UNLIMITED)

    // Touched only from the [ops] consumer.
    private val handles = LinkedHashMap<MyotisNetwork, Long>()
    private val startErrors = LinkedHashMap<MyotisNetwork, String>()

    /**
     * Chains parked on a stale trust anchor. Android has no checkpoint
     * recovery yet (#195), so the handle is paused — networking down, no
     * peers held — and kept paused through foreground/background; its row
     * keeps showing the status it parked with.
     *
     * The engine judges an anchor's age against the wall clock, so a park
     * taken while the clock was wrong (set ahead, then corrected by NTP or
     * by hand) isn't final: when the wall clock moves against [upClock] by
     * more than [CLOCK_JUMP_MS] since the park, the chain is resumed and
     * judged afresh ([releaseParksOnClockChange]). Otherwise it stays
     * parked until the engines are stopped.
     */
    private val parked = LinkedHashMap<MyotisNetwork, Park>()

    private class Park(val status: MyotisChainStatus, val clockOffset: Long)

    /**
     * Chains just released from a park ([Release]): the engine needs a
     * moment to re-judge its anchor, so a `STALE_ANCHOR` it still reports
     * within [REJUDGE_GRACE_MS] doesn't park it again straight away.
     */
    private val released = LinkedHashMap<MyotisNetwork, Release>()

    /**
     * A park released at [upClock] time [at]. Until the engine has shown it
     * accepted the anchor ([MyotisChainStatus.anchorAccepted]) or the grace
     * runs out, the row keeps showing the [status] it was parked with: a
     * resumed engine passes through `STARTING`/`SYNCING` before it
     * concludes `STALE_ANCHOR` again, and showing that would flip the row
     * (and the overall line) to syncing and back for a poll. The cost, when
     * the corrected clock really did fix it, is the old row for up to
     * [REJUDGE_GRACE_MS] while it syncs — the row it had been showing anyway.
     *
     * The grace counts only time the engine is actually running: in the
     * background it's paused and can't re-judge anything, so a release
     * never runs out there, and the foreground restarts it ([at] reset on
     * resume) — otherwise a chain released just before Home and still
     * stale on return would show its raw `SYNCING` row for a poll before
     * parking again.
     */
    private class Release(var at: Long, val status: MyotisChainStatus)

    /**
     * Chains whose engine last reported itself paused, read from the engine
     * rather than from the published row: a released chain's row is its
     * parked snapshot (never `paused`), so a failed resume after a release
     * would otherwise go unretried until the grace ran out.
     */
    private val enginePaused = HashSet<MyotisNetwork>()
    private var foreground = true
    private var polls = 0

    /** Read by the poll ticker; written by the [ops] consumer. */
    @Volatile
    private var polling = false
    private val pollQueued = AtomicBoolean(false)

    private val _state = MutableStateFlow(MyotisInfo())
    val state: StateFlow<MyotisInfo> = _state.asStateFlow()

    init {
        scope.launch {
            for (op in ops) {
                try {
                    apply(op)
                } catch (t: Throwable) {
                    // Nobody awaits most ops: never let one take the
                    // queue (or the process) down.
                    Log.e(TAG, "myotis $op failed", t)
                    if (op is Op.Start) {
                        publish(MyotisStatus.Error, t.message ?: t.javaClass.simpleName)
                    }
                } finally {
                    when (op) {
                        is Op.Stop -> op.done?.complete(Unit)
                        is Op.Barrier -> op.done.complete(Unit)
                        else -> Unit
                    }
                }
            }
        }
        scope.launch {
            while (isActive) {
                delay(pollIntervalMs)
                // Paused in the background: the engine's status is frozen,
                // so there's nothing to read until the app is back.
                if (polling && pollQueued.compareAndSet(false, true)) ops.trySend(Op.Poll)
            }
        }
    }

    /** Start every chain's engine. No-op while already started. */
    fun start() {
        ops.trySend(Op.Start)
    }

    /** Stop every chain's engine. Returns at once; the engines drain on the queue. */
    fun stop() {
        ops.trySend(Op.Stop())
    }

    /** The app went to the background: idle-sleep the engines (networking down, warm state kept). */
    fun enterBackground() {
        ops.trySend(Op.Background)
    }

    /** The app is back: warm-restart paused engines. */
    fun enterForeground() {
        ops.trySend(Op.Foreground)
    }

    /**
     * Stop the engines and wait for them — up to the caller's own
     * timeout — then release the queue. For the owning service's
     * teardown; the node is unusable afterwards.
     */
    suspend fun shutdown() {
        val done = CompletableDeferred<Unit>()
        try {
            if (ops.trySend(Op.Stop(done)).isSuccess) done.await()
        } finally {
            ops.close()
            scope.cancel()
        }
    }

    /** Wait until every op sent so far has run. Tests only. */
    internal suspend fun awaitIdle() {
        val done = CompletableDeferred<Unit>()
        ops.send(Op.Barrier(done))
        done.await()
    }

    /** Run one status poll now, as the ticker would. Tests only. */
    internal fun pollNow() {
        ops.trySend(Op.Poll)
    }

    private fun apply(op: Op) {
        when (op) {
            Op.Start -> startEngines()
            is Op.Stop -> stopEngines()
            Op.Background -> {
                foreground = false
                polling = false
                for ((network, handle) in handles) {
                    if (network in parked) continue
                    val paused = engine.pause(handle)
                    Log.i(TAG, "${network.engineName}: pause → $paused")
                }
                if (handles.isNotEmpty()) refreshStatus()
            }
            Op.Foreground -> {
                foreground = true
                releaseParksOnClockChange(resume = false)
                for ((network, handle) in handles) {
                    if (network in parked) continue
                    val resumed = engine.resume(handle)
                    Log.i(TAG, "${network.engineName}: resume → $resumed")
                }
                // Paused engines re-judged nothing: a release's grace starts over.
                val now = upClock()
                for (release in released.values) release.at = now
                if (handles.isNotEmpty()) {
                    polling = true
                    refreshStatus()
                }
            }
            Op.Poll -> {
                pollQueued.set(false)
                if (handles.isEmpty()) return
                if (foreground) releaseParksOnClockChange(resume = true)
                val retry = if (foreground) pausedChains() else emptyList()
                for ((network, handle) in retry) {
                    // A failed warm restart leaves the engine PAUSED and
                    // retryable; try again on every poll while in front.
                    val resumed = engine.resume(handle)
                    Log.i(TAG, "${network.engineName}: still paused, resume → $resumed")
                    // A release's grace counts from the engine actually running again.
                    if (resumed) released[network]?.at = upClock()
                }
                refreshStatus()
                if (++polls % LOG_DRAIN_EVERY == 0) drainEngineLogs()
            }
            is Op.Barrier -> Unit
        }
    }

    private fun startEngines() {
        if (handles.isNotEmpty()) return
        startErrors.clear()
        publish(MyotisStatus.Starting)
        val abi = engine.init()
        if (abi != engine.expectedAbi) {
            publish(
                MyotisStatus.Error,
                "Light client engine ABI $abi, this app expects ${engine.expectedAbi}",
            )
            return
        }
        for (network in networks) {
            val dir = File(dataDir, network.engineName)
            if (!dir.isDirectory && !dir.mkdirs()) {
                startErrors[network] = "Can't create ${dir.path}"
                continue
            }
            val handle = engine.create(network.engineName, dir.absolutePath)
            if (handle < 1) {
                startErrors[network] = createError(handle)
                Log.w(TAG, "${network.engineName}: create → $handle")
                continue
            }
            if (!engine.start(handle)) {
                engine.stop(handle)
                startErrors[network] = "Engine didn't start"
                Log.w(TAG, "${network.engineName}: start refused")
                continue
            }
            // The engine default (32 blocks) suits a desktop; a phone
            // serves the protocol minimum and keeps its data budget for
            // its own reads (iOS does the same).
            engine.setServedBlockWindow(handle, SERVED_BLOCK_WINDOW)
            if (!foreground) engine.pause(handle)
            handles[network] = handle
            Log.i(TAG, "${network.engineName}: started (handle $handle)")
        }
        if (handles.isEmpty()) {
            publish(MyotisStatus.Error, startErrors.values.distinct().joinToString("; "))
            return
        }
        polling = foreground
        refreshStatus()
    }

    private fun stopEngines() {
        polling = false
        val stopping = handles.toMap()
        handles.clear()
        startErrors.clear()
        parked.clear()
        released.clear()
        enginePaused.clear()
        for ((network, handle) in stopping) {
            engine.stop(handle)
            Log.i(TAG, "${network.engineName}: stopped")
        }
        _state.value = MyotisInfo()
    }

    private fun pausedChains(): List<Pair<MyotisNetwork, Long>> =
        handles.mapNotNull { (network, handle) ->
            if (network !in parked && network in enginePaused) network to handle else null
        }

    private fun refreshStatus() {
        val chains = networks.mapNotNull { network ->
            val handle = handles[network]
            when {
                handle != null -> parked[network]?.status ?: engineStatus(network, handle)
                startErrors[network] != null ->
                    MyotisChainStatus(network.chainId, error = startErrors[network])
                else -> null
            }
        }
        _state.value = MyotisInfo(status = MyotisStatus.Running, chains = chains)
    }

    /** A running (unparked) chain's status as its row should show it; parks it on a stale anchor. */
    private fun engineStatus(network: MyotisNetwork, handle: Long): MyotisChainStatus {
        val status = MyotisChainStatus.decode(network.chainId, engine.statusJson(handle) ?: "{}")
        if (status.paused) enginePaused += network else enginePaused -= network
        if (foreground && status.staleAnchor && !status.paused) park(network, handle, status)
        parked[network]?.let { return it.status }
        val release = released[network] ?: return status
        // A paused engine (a resume that failed, retried on the next poll)
        // re-judges nothing: its grace starts when it's actually running.
        if (status.paused) release.at = upClock()
        if (status.anchorAccepted || (foreground && upClock() - release.at >= REJUDGE_GRACE_MS)) {
            released.remove(network)
            return status
        }
        return release.status
    }

    /**
     * [network] reported `STALE_ANCHOR`: it can't sync forward, so
     * idle-sleep it instead of holding peers for no possible progress.
     * Nothing but a wall-clock change ([releaseParksOnClockChange]) or a
     * stop of the engines releases it. Only on an actual transition — a
     * handle that wasn't running is tried again on the next poll.
     */
    private fun park(network: MyotisNetwork, handle: Long, status: MyotisChainStatus) {
        val release = released[network]
        if (release != null) {
            if (upClock() - release.at < REJUDGE_GRACE_MS) return
            released.remove(network)
        }
        if (!engine.pause(handle)) return
        parked[network] = Park(status, clockOffset())
        Log.i(TAG, "${network.engineName}: trust anchor too old, parked (paused)")
    }

    /**
     * Resume every parked chain whose park was taken on a wall clock that
     * has since been changed (see [parked]). With [resume] false the caller
     * resumes it itself (the foreground op resumes every unparked chain).
     */
    private fun releaseParksOnClockChange(resume: Boolean) {
        if (parked.isEmpty()) return
        val offset = clockOffset()
        val moved = parked.filterValues { abs(offset - it.clockOffset) > CLOCK_JUMP_MS }.keys
        for (network in moved) {
            val park = parked.remove(network) ?: continue
            released[network] = Release(upClock(), park.status)
            val handle = handles[network] ?: continue
            val resumed = if (resume) " → ${engine.resume(handle)}" else ""
            Log.i(TAG, "${network.engineName}: wall clock changed since parking, resuming to re-judge the anchor$resumed")
        }
    }

    /** Wall clock minus the monotonic clock: constant unless someone sets the wall clock. */
    private fun clockOffset(): Long = wallClock() - upClock()

    private fun publish(status: MyotisStatus, error: String? = null) {
        _state.value = MyotisInfo(
            status = status,
            chains = startErrors.map { (network, message) ->
                MyotisChainStatus(network.chainId, error = message)
            },
            errorMessage = error,
        )
    }

    /** The engine's own tracing lines, into logcat (tag `MyotisEngine`) for field debugging. */
    private fun drainEngineLogs() {
        val lines = engine.drainLogs(LOG_DRAIN_MAX) ?: return
        if (lines.isEmpty()) return
        for (line in lines.lineSequence()) if (line.isNotBlank()) Log.i(ENGINE_TAG, line)
    }

    private fun createError(code: Long): String = when (code) {
        MyotisNative.UNSUPPORTED_NETWORK -> "Network not supported by this engine"
        MyotisNative.ANCHOR_MISMATCH -> "Saved sync data doesn't match the engine's checkpoint"
        else -> "Engine couldn't be created ($code)"
    }

    internal object NativeEngine : Engine {
        override fun init(): Int = MyotisNative.init()
        override val expectedAbi: Int get() = MyotisNative.headerAbiVersion()
        override fun create(network: String, dataDir: String) = MyotisNative.create(network, dataDir)
        override fun start(handle: Long) = MyotisNative.start(handle)
        override fun stop(handle: Long) = MyotisNative.stop(handle)
        override fun pause(handle: Long) = MyotisNative.pause(handle)
        override fun resume(handle: Long) = MyotisNative.resume(handle)
        override fun setServedBlockWindow(handle: Long, blocks: Int) =
            MyotisNative.setServedBlockWindow(handle, blocks)
        override fun statusJson(handle: Long) =
            MyotisNative.statusJson(handle)?.toString(Charsets.UTF_8)
        override fun drainLogs(max: Int) = MyotisNative.drainLogs(max)?.toString(Charsets.UTF_8)
    }

    companion object {
        private const val TAG = "MyotisNode"
        private const val ENGINE_TAG = "MyotisEngine"

        /** Status poll cadence while in the foreground (iOS: 3 s, desktop: 1 s). */
        const val POLL_INTERVAL_MS = 3_000L

        /** eth/69 served-block window: the protocol minimum. */
        const val SERVED_BLOCK_WINDOW = 1

        /** Wall-clock change since a park that makes it worth re-judging the anchor. */
        const val CLOCK_JUMP_MS = 60_000L

        /** How long a chain released from a park gets to re-judge its anchor before it can park again. */
        const val REJUDGE_GRACE_MS = 15_000L

        private const val LOG_DRAIN_EVERY = 5
        private const val LOG_DRAIN_MAX = 50
    }
}
