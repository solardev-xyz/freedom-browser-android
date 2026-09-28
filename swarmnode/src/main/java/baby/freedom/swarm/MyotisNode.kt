package baby.freedom.swarm

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
 * Stale-anchor checkpoint recovery (#195): when a chain parks on a trust
 * anchor older than the engine's weak-subjectivity bound, the node asks
 * an external quorum of checkpoint-sync authorities for a fresh finalized
 * checkpoint ([CheckpointSource], [MyotisCheckpointAcquirer]), stops the
 * parked engine, mints a new verified generation for it
 * ([MyotisGenerationStore]) and bootstraps from it
 * (`myotis_create_with_checkpoint`). The chain isn't [MyotisChainStatus.ready]
 * until the engine has synced forward from that anchor
 * ([MyotisRecoveryPolicy.canFinish]). Transient failures retry on a ladder
 * (15 s, 60 s, then every 5 min in the foreground); terminal ones block
 * and wait for the user ([retryRecovery], [repairSyncData]). Nothing calls
 * `myotis_accept_stale_anchor` or raises the bound.
 *
 * Same shape as the iOS `MyotisNode` (freedom-browser-ios), minus its
 * Colibri corroboration of the checkpoint (see [MyotisCheckpointAcquirer]),
 * seed pins and verified reads, which come with their consumers.
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
    /** Where a recovery checkpoint comes from. */
    private val checkpoints: CheckpointSource = CheckpointSource { network, onDiagnostic ->
        MyotisCheckpointAcquirer(nowMs = wallClock).acquire(network, onDiagnostic)
    },
) {
    constructor(dataDir: File) : this(dataDir, NativeEngine)

    /** The engine calls [MyotisNode] makes; [NativeEngine] in the app, a fake in unit tests. */
    internal interface Engine {
        /** `myotis_init`: the engine's ABI version. */
        fun init(): Int

        /** The ABI version this wrapper (and the vendored header) was written against. */
        val expectedAbi: Int
        fun create(network: String, dataDir: String): Long
        fun createWithCheckpoint(network: String, dataDir: String, root: String, slot: Long): Long
        fun start(handle: Long): Boolean
        fun stop(handle: Long)
        fun pause(handle: Long): Boolean
        fun resume(handle: Long): Boolean
        fun setServedBlockWindow(handle: Long, blocks: Int): Boolean
        fun statusJson(handle: Long): String?
        fun drainLogs(max: Int): String?
    }

    /**
     * A fresh, validated checkpoint for a chain, or a
     * [MyotisCheckpointException] saying why there isn't one. The app's is
     * the checkpoint quorum ([MyotisCheckpointAcquirer]); unit tests fake it.
     */
    fun interface CheckpointSource {
        suspend fun acquire(network: MyotisNetwork, onDiagnostic: (String) -> Unit): MyotisCheckpointRecord
    }

    private sealed interface Op {
        data object Start : Op
        class Stop(val done: CompletableDeferred<Unit>? = null) : Op
        data object Background : Op
        data object Foreground : Op
        data object Poll : Op
        class Acquired(val network: MyotisNetwork, val token: Long, val result: Result<MyotisCheckpointRecord>) : Op
        class Retry(val network: MyotisNetwork) : Op
        class Repair(val network: MyotisNetwork) : Op
        class Barrier(val done: CompletableDeferred<Unit>) : Op
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ops = Channel<Op>(Channel.UNLIMITED)

    // Touched only from the [ops] consumer.
    private val handles = LinkedHashMap<MyotisNetwork, Long>()
    private val startErrors = LinkedHashMap<MyotisNetwork, String>()

    private val store = MyotisGenerationStore(dataDir)

    /** Each running chain's generation (its data directory and anchor). */
    private val generations = LinkedHashMap<MyotisNetwork, MyotisGeneration>()

    /** Set once [startEngines] got as far as the chains; cleared by [stopEngines]. */
    private var started = false

    /**
     * Chains parked on a stale trust anchor. The handle is paused —
     * networking down, no peers held — and kept paused through
     * foreground/background while checkpoint recovery ([recovery]) gets a
     * fresh anchor to replace it with; its row keeps showing the status it
     * parked with.
     *
     * The engine judges an anchor's age against the wall clock, so a park
     * taken while the clock was wrong (set ahead, then corrected by NTP or
     * by hand) isn't final: when the wall clock moves against [upClock] by
     * more than [CLOCK_JUMP_MS] since the park, the chain is resumed and
     * judged afresh ([releaseParksOnClockChange]). Otherwise it stays
     * parked until recovery relaunches it or the engines are stopped.
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

    /**
     * Checkpoint recovery per chain; absent while nothing is recovering or
     * blocked. [Recovery.token] ties an acquisition's result to the attempt
     * that asked for it: a result for an attempt that was superseded,
     * cancelled by Home, or stopped is dropped.
     */
    private val recovery = LinkedHashMap<MyotisNetwork, Recovery>()

    private class Recovery(
        val phase: MyotisRecovery.Phase,
        val mode: MyotisRecovery.Mode = MyotisRecovery.Mode.Checkpoint,
        val reason: MyotisRecoveryReason? = null,
        val attempt: Int = 0,
        /** [upClock] time of the next automatic attempt, while waiting. */
        val nextRetryAt: Long? = null,
        val token: Long = 0L,
        /**
         * This recovery answers a stale anchor, so it's moot once the
         * engine accepts its own (a corrected wall clock). False for one
         * retrying a [MyotisRecoveryReason.AnchorMismatch], whose engine is up and
         * synced — on the anchor that contradicted the quorum.
         */
        val forStaleAnchor: Boolean = true,
    ) {
        /** The acquisition in flight (phase checking). */
        var job: Job? = null

        /**
         * [upClock] time the restarted engine came up, or last came back to
         * the foreground: the stall watchdog counts foreground time from it.
         */
        var restartedAt: Long? = null

        fun snapshot() = MyotisRecovery(phase, mode, reason, attempt, nextRetryAt)
    }

    /** Automatic attempts in the current recovery episode, per chain. */
    private val attempts = HashMap<MyotisNetwork, Int>()
    private var tokens = 0L
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

    /**
     * "Retry" on a chain whose recovery is blocked or waiting: a fresh
     * checkpoint recovery, or — for startup, stall and storage reasons — a
     * restart of the chain's current generation.
     */
    fun retryRecovery(network: MyotisNetwork) {
        ops.trySend(Op.Retry(network))
    }

    /** "Repair sync data" on a chain blocked on inconsistent storage: a fresh generation, old data kept. */
    fun repairSyncData(network: MyotisNetwork) {
        ops.trySend(Op.Repair(network))
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
                // Recovery starts and retries in the foreground only; an
                // acquisition already in flight finishes (the process keeps
                // running, and a relaunch in the background comes up
                // paused), a waiting retry keeps its due time.
                for ((network, handle) in handles) {
                    if (network in parked) continue
                    val paused = engine.pause(handle)
                    Log.i(TAG, "${network.engineName}: pause → $paused")
                }
                if (started) refreshStatus()
            }
            Op.Foreground -> {
                foreground = true
                releaseParksOnClockChange(resume = false)
                for ((network, handle) in handles) {
                    if (network in parked) continue
                    val resumed = engine.resume(handle)
                    Log.i(TAG, "${network.engineName}: resume → $resumed")
                }
                // Paused engines re-judged nothing: a release's grace starts
                // over, and so does a restarted engine's stall watchdog.
                val now = upClock()
                for (release in released.values) release.at = now
                for (rec in recovery.values) if (rec.restartedAt != null) rec.restartedAt = now
                if (started) {
                    polling = true
                    reconcileRecovery()
                    refreshStatus()
                }
            }
            Op.Poll -> {
                pollQueued.set(false)
                if (!started) return
                if (foreground) releaseParksOnClockChange(resume = true)
                reconcileRecovery()
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
            is Op.Acquired -> {
                onAcquired(op)
                if (started) refreshStatus()
            }
            is Op.Retry -> {
                retry(op.network)
                if (started) refreshStatus()
            }
            is Op.Repair -> {
                val rec = recovery[op.network]
                if (started && rec != null && rec.snapshot().canRepair && rec.job == null) {
                    restartOwned(op.network, repair = true)
                    refreshStatus()
                }
            }
            is Op.Barrier -> Unit
        }
    }

    private fun startEngines() {
        if (started) return
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
            val generation = try {
                store.load(network).also { store.checkNativeMarker(it, network) }
            } catch (e: MyotisCheckpointException) {
                if (e.error == MyotisCheckpointError.AnchorMismatch) {
                    // The engine once contradicted this generation's checkpoint:
                    // blocked until Retry fetches a fresh one.
                    recovery[network] = Recovery(
                        MyotisRecovery.Phase.Blocked,
                        reason = MyotisRecoveryReason.AnchorMismatch,
                        forStaleAnchor = false,
                    )
                    Log.w(TAG, "${network.engineName}: current generation was rejected, not booting it")
                    continue
                }
                // Offers Repair: a fresh generation, the old data kept.
                recovery[network] = Recovery(MyotisRecovery.Phase.Blocked, reason = MyotisRecoveryReason.of(e.error))
                Log.w(TAG, "${network.engineName}: saved sync data unusable (${e.error.code})")
                continue
            }
            generations[network] = generation
            when (val boot = boot(network, generation)) {
                is Boot.Started -> {
                    handles[network] = boot.handle
                    Log.i(TAG, "${network.engineName}: started (handle ${boot.handle}, ${generation.describe()})")
                }
                is Boot.Failed ->
                    if (boot.reason == MyotisRecoveryReason.Storage) {
                        recovery[network] = Recovery(MyotisRecovery.Phase.Blocked, reason = boot.reason)
                    } else {
                        startErrors[network] = boot.message
                    }
            }
        }
        if (handles.isEmpty() && recovery.isEmpty()) {
            publish(MyotisStatus.Error, startErrors.values.distinct().joinToString("; "))
            return
        }
        started = true
        polling = foreground
        refreshStatus()
    }

    private sealed interface Boot {
        class Started(val handle: Long) : Boot
        class Failed(val reason: MyotisRecoveryReason, val message: String) : Boot
    }

    /**
     * Create and start [network]'s engine on [generation]: from its
     * checkpoint for a verified generation, from the embedded anchor for a
     * bundled one. Shared by the first start and every recovery relaunch.
     */
    private fun boot(network: MyotisNetwork, generation: MyotisGeneration): Boot {
        val dir = generation.directory
        if (!dir.isDirectory && !dir.mkdirs()) {
            return Boot.Failed(MyotisRecoveryReason.StorageIO, "Can't create ${dir.path}")
        }
        val checkpoint = generation.checkpoint
        val handle = if (generation.origin == MyotisGeneration.Origin.Verified && checkpoint != null) {
            engine.createWithCheckpoint(network.engineName, dir.absolutePath, checkpoint.root, checkpoint.slot)
        } else {
            engine.create(network.engineName, dir.absolutePath)
        }
        if (handle < 1) {
            Log.w(TAG, "${network.engineName}: create → $handle")
            val reason = if (handle == MyotisNative.ANCHOR_MISMATCH) MyotisRecoveryReason.Storage else MyotisRecoveryReason.Startup
            return Boot.Failed(reason, createError(handle))
        }
        if (!engine.start(handle)) {
            engine.stop(handle)
            Log.w(TAG, "${network.engineName}: start refused")
            return Boot.Failed(MyotisRecoveryReason.Startup, "Engine didn't start")
        }
        // The engine default (32 blocks) suits a desktop; a phone
        // serves the protocol minimum and keeps its data budget for
        // its own reads (iOS does the same).
        engine.setServedBlockWindow(handle, SERVED_BLOCK_WINDOW)
        if (!foreground) engine.pause(handle)
        return Boot.Started(handle)
    }

    private fun MyotisGeneration.describe(): String =
        if (id == null) "${origin.code} anchor" else "${origin.code} generation ${id.take(8)}"

    private fun stopEngines() {
        polling = false
        started = false
        val stopping = handles.toMap()
        handles.clear()
        startErrors.clear()
        parked.clear()
        released.clear()
        enginePaused.clear()
        for (rec in recovery.values) rec.job?.cancel()
        recovery.clear()
        attempts.clear()
        generations.clear()
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
            val row = when {
                handle != null -> parked[network]?.status ?: engineStatus(network, handle)
                // No engine: recovery stopped it and couldn't start a new one.
                recovery[network] != null -> MyotisChainStatus(network.chainId)
                startErrors[network] != null ->
                    return@mapNotNull MyotisChainStatus(network.chainId, error = startErrors[network])
                else -> return@mapNotNull null
            }
            row.copy(recovery = recovery[network]?.snapshot())
        }
        _state.value = MyotisInfo(status = MyotisStatus.Running, chains = chains)
    }

    /** A running (unparked) chain's status as its row should show it; parks it on a stale anchor. */
    private fun engineStatus(network: MyotisNetwork, handle: Long): MyotisChainStatus {
        val status = MyotisChainStatus.decode(network.chainId, engine.statusJson(handle) ?: "{}")
        if (status.paused) enginePaused += network else enginePaused -= network
        if (foreground && status.staleAnchor && !status.paused) park(network, handle, status)
        parked[network]?.let { return it.status }
        observeRecovery(network, status)
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
     * idle-sleep it instead of holding peers for no possible progress,
     * and start checkpoint recovery ([onStaleAnchor]). A recovery relaunch,
     * a wall-clock change ([releaseParksOnClockChange]) or a stop of the
     * engines releases it. Only on an actual transition — a handle that
     * wasn't running is tried again on the next poll.
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
        onStaleAnchor(network)
    }

    // ---- Checkpoint recovery (#195). Everything below runs on the [ops] consumer.

    /** [network] just parked on a stale anchor: start (or re-route) its recovery. */
    private fun onStaleAnchor(network: MyotisNetwork) {
        val rec = recovery[network]
        when {
            rec == null -> beginRecovery(network, reset = false)
            rec.phase == MyotisRecovery.Phase.Restarting && rec.mode == MyotisRecovery.Mode.Restart -> {
                // Restarting its own generation found the anchor expired: fetch a fresh one.
                recovery.remove(network)
                beginRecovery(network, reset = true)
            }
            // The freshly agreed checkpoint itself was judged too old (a
            // long stall between agreement and bootstrap): the ladder asks again.
            rec.phase == MyotisRecovery.Phase.Restarting ->
                failRecovery(network, MyotisRecoveryReason.Stale, retry = true)
            rec.phase == MyotisRecovery.Phase.Blocked && rec.reason == MyotisRecoveryReason.Stalled ->
                beginRecovery(network, reset = true)
            // Blocked on this device's clock, and parked again after a
            // clock-change release: the clock moved, so ask again.
            rec.phase == MyotisRecovery.Phase.Blocked && rec.reason == MyotisRecoveryReason.Clock ->
                beginRecovery(network, reset = true)
            else -> Unit
        }
    }

    /**
     * On every foreground poll and on return to the foreground: a parked
     * chain with no recovery (it parked while a recovery couldn't start)
     * starts one; a waiting chain whose retry is due runs it. Polls stop in
     * the background, so nothing retries there.
     */
    private fun reconcileRecovery() {
        if (!foreground) return
        for (network in networks) {
            val rec = recovery[network]
            if (rec == null) {
                if (network in parked) beginRecovery(network, reset = false)
            } else if (rec.phase == MyotisRecovery.Phase.Waiting && rec.job == null &&
                upClock() >= (rec.nextRetryAt ?: 0L)
            ) {
                beginRecovery(network, reset = false)
            }
        }
    }

    /** Ask the checkpoint quorum for a fresh anchor; single-flight per chain. */
    private fun beginRecovery(
        network: MyotisNetwork,
        reset: Boolean,
        forStaleAnchor: Boolean = recovery[network]?.forStaleAnchor ?: true,
    ) {
        if (!foreground || recovery[network]?.job != null) return
        if (reset) attempts[network] = 0
        val attempt = (attempts[network] ?: 0) + 1
        attempts[network] = attempt
        val token = ++tokens
        val rec = Recovery(
            MyotisRecovery.Phase.Checking,
            // Replacing an anchor the engine contradicted, not an expired one: say so on the row.
            reason = if (forStaleAnchor) null else MyotisRecoveryReason.AnchorMismatch,
            attempt = attempt,
            token = token,
            forStaleAnchor = forStaleAnchor,
        )
        recovery[network] = rec
        Log.i(TAG, "${network.engineName}: asking the checkpoint quorum for a fresh anchor (attempt $attempt)")
        rec.job = scope.launch {
            val result = try {
                Result.success(
                    checkpoints.acquire(network) { line ->
                        Log.i(TAG, "${network.engineName}: checkpoint source $line (attempt $attempt)")
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // Nobody awaits this job: anything it throws is an answer.
                Result.failure(t)
            }
            ops.trySend(Op.Acquired(network, token, result))
        }
    }

    private fun onAcquired(op: Op.Acquired) {
        val network = op.network
        val rec = recovery[network]
        if (rec == null || rec.token != op.token || rec.phase != MyotisRecovery.Phase.Checking) return
        rec.job = null
        val record = op.result.getOrElse { t ->
            val code = (t as? MyotisCheckpointException)?.error ?: MyotisCheckpointError.Unavailable
            if (t !is MyotisCheckpointException) Log.w(TAG, "${network.engineName}: checkpoint acquisition threw", t)
            failRecovery(network, MyotisRecoveryReason.of(code), retry = code.retryable)
            return
        }
        if (rec.forStaleAnchor && handles[network] != null && network !in parked && network !in released) {
            // The engine accepted its own anchor meanwhile (a corrected
            // wall clock): there's nothing to replace.
            recovery.remove(network)
            attempts.remove(network)
            Log.i(TAG, "${network.engineName}: anchor accepted meanwhile, checkpoint not needed")
            return
        }
        Log.i(
            TAG,
            "${network.engineName}: checkpoint agreed · slot ${record.slot} · " +
                "${record.sources.size} sources — restarting from it",
        )
        val restarting = Recovery(MyotisRecovery.Phase.Restarting, attempt = rec.attempt)
        recovery[network] = restarting
        relaunch(network, restarting) { store.replace(network, record, wallClock()) }
    }

    /**
     * Stop [network]'s engine (synchronous: the directory is released when
     * it returns), take the generation [makeGeneration] picks, check its
     * marker, and boot it. A failure blocks recovery with its reason.
     */
    private fun relaunch(network: MyotisNetwork, rec: Recovery, makeGeneration: () -> MyotisGeneration) {
        handles.remove(network)?.let { handle ->
            engine.stop(handle)
            Log.i(TAG, "${network.engineName}: stopped for relaunch")
        }
        parked.remove(network)
        released.remove(network)
        enginePaused.remove(network)
        startErrors.remove(network)
        val generation = try {
            makeGeneration().also { store.checkNativeMarker(it, network) }
        } catch (e: MyotisCheckpointException) {
            failRecovery(network, MyotisRecoveryReason.of(e.error), retry = false)
            return
        }
        generations[network] = generation
        when (val boot = boot(network, generation)) {
            is Boot.Started -> {
                handles[network] = boot.handle
                rec.restartedAt = upClock()
                Log.i(TAG, "${network.engineName}: relaunched (handle ${boot.handle}, ${generation.describe()})")
            }
            is Boot.Failed -> {
                Log.w(TAG, "${network.engineName}: relaunch failed — ${boot.message}")
                failRecovery(network, boot.reason, retry = false)
            }
        }
    }

    /** Desktop `failRecovery`: wait for the next rung of the retry ladder, or block for the user. */
    private fun failRecovery(network: MyotisNetwork, reason: MyotisRecoveryReason, retry: Boolean) {
        val previous = recovery[network]
        previous?.job?.cancel()
        val attempt = attempts[network] ?: 0
        val delay = if (retry) MyotisRecoveryPolicy.retryDelay(attempt) else null
        recovery[network] = Recovery(
            phase = if (delay == null) MyotisRecovery.Phase.Blocked else MyotisRecovery.Phase.Waiting,
            mode = previous?.mode ?: MyotisRecovery.Mode.Checkpoint,
            reason = reason,
            attempt = attempt,
            nextRetryAt = delay?.let { upClock() + it },
            forStaleAnchor = reason != MyotisRecoveryReason.AnchorMismatch && previous?.forStaleAnchor != false,
        )
        if (delay != null) {
            Log.i(TAG, "${network.engineName}: recovery attempt $attempt failed (${reason.code}), retrying in ${delay / 1000} s")
        } else {
            Log.w(TAG, "${network.engineName}: recovery blocked (${reason.code})")
        }
    }

    /** The user's Retry (see [retryRecovery]). */
    private fun retry(network: MyotisNetwork) {
        val rec = recovery[network] ?: return
        if (!started || !rec.snapshot().canRetry || rec.job != null) return
        if (rec.reason?.restartsOwnedState == true) {
            restartOwned(network, repair = false)
        } else {
            beginRecovery(network, reset = true, forStaleAnchor = rec.forStaleAnchor)
        }
    }

    /**
     * Desktop `restartOwnedState`: no new checkpoint — relaunch the chain's
     * current generation (or, repairing, a fresh bundled one). If the
     * anchor really is stale the engine parks again and a checkpoint
     * recovery follows.
     */
    private fun restartOwned(network: MyotisNetwork, repair: Boolean) {
        attempts[network] = 0
        val rec = Recovery(MyotisRecovery.Phase.Restarting, mode = MyotisRecovery.Mode.Restart)
        recovery[network] = rec
        Log.i(TAG, "${network.engineName}: ${if (repair) "repairing sync data" else "restarting"}")
        relaunch(network, rec) { if (repair) store.repair(network) else store.load(network) }
    }

    /**
     * Desktop `observeSync`, on every poll of a running, unparked chain:
     * finish a restart once the engine has synced from its anchor, block
     * one that contradicts the agreed checkpoint or stalls, and drop a
     * pending recovery the engine no longer needs.
     */
    private fun observeRecovery(network: MyotisNetwork, status: MyotisChainStatus) {
        if (status.paused || status.staleAnchor) return
        val rec = recovery[network]
        val checkpoint = generations[network]?.checkpoint
        if ((rec == null || rec.phase == MyotisRecovery.Phase.Restarting) &&
            MyotisRecoveryPolicy.isAnchorMismatch(status, checkpoint)
        ) {
            // The engine's own BLS-verified finalized root isn't the one the
            // quorum agreed on. Recorded on the generation, so a restart
            // can't boot it again once it has synced past the checkpoint
            // slot, where this check can no longer see the contradiction.
            generations[network]?.let { generation ->
                try {
                    store.reject(generation)
                } catch (e: MyotisCheckpointException) {
                    Log.w(TAG, "${network.engineName}: couldn't record the rejected generation (${e.error.code})")
                }
            }
            failRecovery(network, MyotisRecoveryReason.AnchorMismatch, retry = false)
            return
        }
        rec ?: return
        val finished = MyotisRecoveryPolicy.canFinish(status, checkpoint)
        when {
            (rec.phase == MyotisRecovery.Phase.Restarting ||
                (rec.phase == MyotisRecovery.Phase.Blocked && rec.reason == MyotisRecoveryReason.Stalled)) && finished -> {
                recovery.remove(network)
                attempts.remove(network)
                Log.i(TAG, "${network.engineName}: recovery complete — synced from ${generations[network]?.describe()}")
            }
            rec.phase == MyotisRecovery.Phase.Restarting -> {
                val since = rec.restartedAt
                if (foreground && since != null && upClock() - since >= MyotisRecoveryPolicy.STALL_MS) {
                    failRecovery(network, MyotisRecoveryReason.Stalled, retry = false)
                }
            }
            // A clock-change release let the engine accept its own anchor:
            // a recovery still pending for it isn't needed any more.
            status.anchorAccepted && rec.forStaleAnchor && (
                rec.phase == MyotisRecovery.Phase.Checking ||
                    rec.phase == MyotisRecovery.Phase.Waiting ||
                    (rec.phase == MyotisRecovery.Phase.Blocked && rec.reason in ACQUISITION_REASONS)
                ) -> {
                rec.job?.cancel()
                recovery.remove(network)
                attempts.remove(network)
                Log.i(TAG, "${network.engineName}: anchor accepted, pending recovery dropped")
            }
        }
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
        override fun createWithCheckpoint(network: String, dataDir: String, root: String, slot: Long) =
            MyotisNative.createWithCheckpoint(network, dataDir, root, slot)
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

        /**
         * Blocked reasons from fetching a checkpoint (including evidence that
         * failed verification, [MyotisRecoveryReason.Mismatch]), which an
         * engine that accepted its own anchor makes moot. Not
         * [MyotisRecoveryReason.AnchorMismatch]: that recovery isn't for a
         * stale anchor ([Recovery.forStaleAnchor] is false) and must still
         * replace the checkpoint the engine contradicted.
         */
        private val ACQUISITION_REASONS = setOf(
            MyotisRecoveryReason.Unavailable,
            MyotisRecoveryReason.QuorumUnavailable,
            MyotisRecoveryReason.QuorumConflict,
            MyotisRecoveryReason.Mismatch,
            MyotisRecoveryReason.Clock,
            MyotisRecoveryReason.Stale,
        )

        private const val LOG_DRAIN_EVERY = 5
        private const val LOG_DRAIN_MAX = 50
    }
}
