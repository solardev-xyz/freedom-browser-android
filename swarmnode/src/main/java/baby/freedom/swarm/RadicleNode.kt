package baby.freedom.swarm

import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Kotlin wrapper around the embedded, publish-capable Radicle node
 * (libradicle-uniffi inside `libfreedom_mobile_ffi.so`, called through the
 * generated UniFFI bindings in `uniffi.libradicle_uniffi`).
 *
 * The Android counterpart of iOS's `RadicleNode` and desktop's
 * `radicle-embedded.js`: one node per process (the Rust layer holds it in
 * a global slot), a profile under [Config.home], and a seed book dialled
 * right after start. The node runs as the wallet's Radicle identity
 * ([Config.identity], #328) when there is one; otherwise as the
 * profile's own key, created on first start and reused after. The
 * `no-spawn` build serves peers' fetches in-process, so repositories this
 * node seeds are served back out without any child process.
 *
 * Every UniFFI export is synchronous and blocking; all of them run on
 * [scope]'s IO threads, never the caller's. Results are the JSON strings
 * the desktop napi addon returns (`{"error": …}` on failure); only what
 * the node page shows is decoded into [state].
 *
 * Start and stop run one at a time, in call order, on [lifecycle]. Each
 * one bumps [generation], and anything a start launched (the seed dial, the peer
 * poller, a seed-by-RID fetch) publishes only while its generation is
 * still current, so a slow call that returns after [stop] can't paint
 * the stopped node's state back.
 */
class RadicleNode internal constructor(
    private val config: Config,
    private val ops: Ops,
    /** How long a stop waits for a cancelled seed to roll back; shortened in tests. */
    private val seedStopWaitMs: Long = SEED_STOP_WAIT_MS,
) {
    constructor(config: Config) : this(config, Ops.Native)

    data class Config(
        /** Profile home (keys, storage, node.db). Created on first start. */
        val home: String,
        /** Alias written into a fresh profile; an existing profile keeps its own. */
        val alias: String = DEFAULT_ALIAS,
        /**
         * Where the control socket goes if `<home>/node/control.sock` is too
         * long for a unix socket path. Nothing dials the socket on Android
         * (there's no `rad` CLI); it only has to bind.
         */
        val shortSocketDir: String,
        /**
         * The identity to run as instead of the profile's own key (#328):
         * the wallet's, read again at every boot — or null for the
         * profile's own. Its [HostIdentity.secret] is zeroed after use.
         * Throws when it can't tell right now (the wallet's keys are there
         * but can't be opened): a boot then fails with that reason instead
         * of quietly running as the profile's own key, and [reloadIdentity]
         * leaves the node as it is.
         */
        val identity: () -> HostIdentity? = { null },
    )

    /**
     * A Radicle identity the host keeps (#328): [secret] is the 32-byte
     * Ed25519 secret seed, [did] its `did:key:z6Mk…`. It goes to
     * libradicle from memory and is never written to [Config.home];
     * [wipe] it when done, and never log it.
     */
    class HostIdentity(internal val secret: ByteArray, val did: String) {
        fun wipe() = secret.fill(0)

        override fun toString(): String = "HostIdentity($did)"
    }

    /** The libradicle-uniffi calls [RadicleNode] makes; swapped for a fake in tests. */
    internal interface Ops {
        fun setSocketPath(path: String)
        fun start(home: String, alias: String): String

        /** [start] as [secretKey] (32 bytes), which the library zeroes its own copy of. */
        fun startWithKey(home: String, alias: String, secretKey: ByteArray): String
        fun connectSeeds(timeoutMs: Int): String
        fun identity(): String
        fun status(): String
        fun listSeededRepos(): String
        fun cloneRepoWithProgress(rid: String, timeoutMs: Int, onProgress: (String) -> Unit): String
        fun cancelClone(rid: String): String
        fun unseedRepo(rid: String): String
        fun shutdown(): String

        /**
         * One of the browser's reads or writes ([BROWSER_CALLS], #124) by
         * name, its arguments taken from [args]. Only [Native] reaches the
         * library; a fake that doesn't override this answers an error.
         */
        fun call(method: String, args: JSONObject): String = errorJson("unsupported call: $method")

        object Native : Ops {
            override fun setSocketPath(path: String) =
                android.system.Os.setenv("RAD_SOCKET", path, true)
            override fun start(home: String, alias: String) =
                uniffi.libradicle_uniffi.start(home, alias)
            override fun startWithKey(home: String, alias: String, secretKey: ByteArray) =
                uniffi.libradicle_uniffi.startWithKey(home, alias, secretKey)
            override fun connectSeeds(timeoutMs: Int) =
                uniffi.libradicle_uniffi.connectSeeds(timeoutMs.toUInt())
            override fun identity() = uniffi.libradicle_uniffi.identity()
            override fun status() = uniffi.libradicle_uniffi.status()
            override fun listSeededRepos() = uniffi.libradicle_uniffi.listSeededRepos()
            override fun cloneRepoWithProgress(
                rid: String,
                timeoutMs: Int,
                onProgress: (String) -> Unit,
            ) = uniffi.libradicle_uniffi.cloneRepoWithProgress(
                rid,
                timeoutMs.toUInt(),
                object : uniffi.libradicle_uniffi.ProgressListener {
                    override fun onProgress(event: String) = onProgress(event)
                },
            )
            override fun cancelClone(rid: String) = uniffi.libradicle_uniffi.cancelClone(rid)
            override fun unseedRepo(rid: String) = uniffi.libradicle_uniffi.unseedRepo(rid)
            override fun shutdown() = uniffi.libradicle_uniffi.shutdown()

            override fun call(method: String, args: JSONObject): String {
                fun s(key: String): String = args.getString(key)
                fun u(key: String): UInt = args.getLong(key).coerceIn(0, UInt.MAX_VALUE.toLong()).toUInt()
                return when (method) {
                    "identity" -> uniffi.libradicle_uniffi.identity()
                    "status" -> uniffi.libradicle_uniffi.status()
                    "listSeededRepos" -> uniffi.libradicle_uniffi.listSeededRepos()
                    "repoInfo" -> uniffi.libradicle_uniffi.repoInfo(s("rid"))
                    "seeders" -> uniffi.libradicle_uniffi.seeders(s("rid"))
                    "treeAt" -> uniffi.libradicle_uniffi.treeAt(s("rid"), s("revision"), s("path"))
                    "blobAt" -> uniffi.libradicle_uniffi.blobAt(s("rid"), s("revision"), s("path"))
                    "commit" -> uniffi.libradicle_uniffi.commit(s("rid"), s("revision"))
                    "commits" -> uniffi.libradicle_uniffi.commits(s("rid"), s("parent"), u("page"), u("perPage"))
                    "repoStats" -> uniffi.libradicle_uniffi.repoStats(s("rid"), s("revision"))
                    "remotes" -> uniffi.libradicle_uniffi.remotes(s("rid"))
                    "issues" -> uniffi.libradicle_uniffi.issues(s("rid"))
                    "issue" -> uniffi.libradicle_uniffi.issue(s("rid"), s("issueId"))
                    "patches" -> uniffi.libradicle_uniffi.patches(s("rid"))
                    "patch" -> uniffi.libradicle_uniffi.patch(s("rid"), s("patchId"))
                    "createIssue" -> uniffi.libradicle_uniffi.createIssue(
                        s("rid"), s("title"), s("description"), s("labelsJson"),
                    )
                    "commentIssue" -> uniffi.libradicle_uniffi.commentIssue(
                        s("rid"), s("issueId"), s("body"), args.optString("replyTo").ifEmpty { null },
                    )
                    "editIssueState" -> uniffi.libradicle_uniffi.editIssueState(s("rid"), s("issueId"), s("state"))
                    "commentPatch" -> uniffi.libradicle_uniffi.commentPatch(s("rid"), s("revisionId"), s("body"))
                    else -> errorJson("unsupported call: $method")
                }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Start and stop run here, one at a time and in the order they were
     * asked for, so a quick on → off → on can't reorder into a shutdown
     * of the node the last start booted.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val lifecycle = Dispatchers.IO.limitedParallelism(1)

    /** Bumped by every [start] / [stop]; see the class comment. */
    private val generation = AtomicLong(0L)

    /**
     * Whether the last lifecycle call was a start. Guarded by `this`, as
     * are [poller] / [dialJob] assignments: a start's follow-up jobs are
     * launched under the lock only while its generation is current, so a
     * [stop] either sees (and cancels) them or supersedes them first.
     */
    private var wanted = false

    /** Whether a node is up in this process. Touched only on [lifecycle]. */
    private var booted = false

    /**
     * The DID of the [Config.identity] the node up now booted as, or `""`
     * for the profile's own key. Touched only on [lifecycle].
     */
    private var bootedAs = ""

    /**
     * The DID the native node up now answers to, as its start returned
     * it, or `""` while none is up (cleared before every shutdown and
     * every boot). What [call] checks a write's [AS_DID] against.
     */
    @Volatile
    private var runningAs = ""

    /**
     * [runningAs] when that is the wallet's identity ([Config.identity]'s
     * DID), else `""`. Set before [runningAs] and cleared with it, so a
     * DID read back as [runningAs] is labelled from the same boot: what
     * [call] answers `identity` with as [WALLET_IDENTITY].
     */
    @Volatile
    private var runningWalletDid = ""

    /**
     * Orders identity-bound [call]s (#328) against a boot: a write holds
     * the read side from its [AS_DID] check through its native call, and
     * [bootNode] takes the write side to clear [runningAs] before the
     * native start. So a write that passed its check as the old identity
     * has finished (against the old node, or a stopped one) before the
     * node can come up as another, and one that checks after sees `""`
     * or the new DID — it can never sign as an identity it wasn't
     * allowed for.
     */
    private val callGate = ReentrantReadWriteLock()

    @Volatile
    private var poller: Job? = null

    @Volatile
    private var dialJob: Job? = null

    /** The generation [dialJob] dials for. Guarded by `this`. */
    private var dialGen = -1L

    /**
     * Bumped on every boot and every shutdown, on [lifecycle]: which node
     * instance a seed-by-RID fetch was started against. A fetch from an
     * earlier instance neither blocks a new seed nor touches the new node.
     */
    private val nodeEpoch = AtomicLong(0L)

    /**
     * One seed-by-RID fetch on node instance [epoch]; [dismissed] once the
     * user unseeds it mid-fetch, [stopping] once a [stop] cancels it.
     */
    private class SeedRun(val rid: String, val epoch: Long) {
        @Volatile var dismissed = false
        @Volatile var stopping = false
        /** This call added the seeding policy (and a [pendingUnseedFile] line). */
        @Volatile var added = false
        /**
         * The native fetch is transferring from a peer. libradicle checks
         * its cancel token only between peers, so a cancel doesn't end it
         * until that peer's fetch does.
         */
        @Volatile var fetching = false
        /** The native fetch has returned, or was never started. */
        @Volatile var fetchOver = false
        /** A [stop] already took the policy back; the job leaves it alone. */
        @Volatile var rolledBack = false
        /** The coroutine running this call, rollback included. */
        lateinit var job: Job

        /** Whether this call is still running; true until its [job] is even set. */
        val running: Boolean get() = !this::job.isInitialized || job.isActive

        /** Suspends until this call, rollback included, is over. */
        suspend fun join() {
            while (!this::job.isInitialized) delay(CANCEL_RETRY_MS)
            job.join()
        }
    }

    /** The seed-by-RID fetch in flight, or null. */
    private val seedRun = AtomicReference<SeedRun?>(null)

    /**
     * The latest seed-by-RID call, kept after its fetch ends so a [stop]
     * can wait for its rollback while the node is still up.
     */
    @Volatile
    private var lastRun: SeedRun? = null

    /**
     * Seed calls that haven't fully settled, including ones left over from
     * a shut-down node instance: registered when the call is accepted and
     * dropped only after its fetch, its rollback and its outcome line are
     * all done. Guarded by `this` for the add and the final drop. A second
     * call for the same RID waits for this: libradicle keys its cancel
     * tokens by RID in one process-wide map, and a fetch removes its RID's
     * entry when it ends, so a second fetch of the same RID while an
     * earlier one is alive would lose its token to the earlier one; and an
     * earlier call's rollback landing after a retry would take back the
     * retry's policy and pending line.
     */
    private val liveFetches: MutableSet<SeedRun> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * RIDs whose seeding policy a seed call added and hasn't yet confirmed
     * or taken back, one per line. Written before the fetch starts and
     * cleared once it succeeds or the rollback lands, so a rollback the
     * node couldn't take (it was shut down first, or the process died
     * mid-fetch) is replayed on the next boot.
     */
    private val pendingUnseedFile = File(config.home, PENDING_UNSEED_FILE)

    private val _state = MutableStateFlow(RadicleInfo())
    val state: StateFlow<RadicleInfo> = _state.asStateFlow()

    /**
     * Idempotently start the node, then dial the seed book. A start that
     * failed (status [RadicleStatus.Error]) is retried: the next call
     * (a re-bind, or the page's Retry) boots again instead of being
     * swallowed as "already wanted".
     */
    fun start() {
        val gen = synchronized(this) {
            if (wanted && _state.value.status != RadicleStatus.Error) return
            wanted = true
            // Published under the lock that bumps the generation; see [publish].
            _state.value = RadicleInfo(status = RadicleStatus.Starting)
            generation.incrementAndGet()
        }
        scope.launch(lifecycle) {
            // Superseded by a later start/stop before it got here.
            if (gen != generation.get() || booted) return@launch
            val failure = runCatching { bootNode() }
                .fold(onSuccess = { it }, onFailure = { it.message ?: it.javaClass.simpleName })
            booted = failure == null
            if (booted) nodeEpoch.incrementAndGet()
            if (gen != generation.get()) return@launch
            if (failure != null) {
                Log.e(TAG, "radicle start failed: $failure")
                publish(gen) { RadicleInfo(status = RadicleStatus.Error, errorMessage = failure) }
                return@launch
            }
            replayPendingUnseeds()
            val wallet = bootedAs.isNotEmpty()
            publish(gen) { _state.value.copy(walletIdentity = wallet) }
            refreshIdentity(gen)
            refreshRepos(gen)
            // Under the lock, like [publish]: [stop] writes Stopping before it
            // bumps the generation, so a lock-free check here could see
            // Stopping with this generation still current and undo it.
            publish(gen) { _state.value.copy(status = RadicleStatus.Running) }
            Log.i(TAG, "radicle running as ${_state.value.did}")
            synchronized(this@RadicleNode) {
                if (gen != generation.get()) return@launch
                poller = scope.launch { pollStatus(gen) }
            }
            dial(gen)
        }
    }

    /** Null on success, or why the node didn't come up. */
    private fun bootNode(): String? {
        val socket = File(File(config.home, "node"), "control.sock")
        if (socket.absolutePath.toByteArray().size > MAX_SOCKET_PATH) {
            ops.setSocketPath(File(config.shortSocketDir, "rad.sock").absolutePath)
        }
        // The wallet's key is there but can't be opened: fail (Retry, or the
        // next bind, boots again) rather than run as the profile's own key.
        val host = try {
            config.identity()
        } catch (e: Exception) {
            Log.w(TAG, "radicle identity unreadable: ${e.message}")
            return SwarmStrings.get(R.string.swarmnode_radicle_identity_unreadable)
        }
        // Wait for identity-bound writes still running against the node
        // before (see [callGate]); one that won't return fails this boot
        // (Retry, the next bind or the next reload boots again) rather than
        // coming up while a write checked against the old identity could
        // still reach the new one.
        if (!callGate.writeLock().tryLock(WRITE_DRAIN_MS, TimeUnit.MILLISECONDS)) {
            host?.wipe()
            Log.w(TAG, "radicle boot: a write as the previous identity is still running")
            return SwarmStrings.get(R.string.swarmnode_radicle_write_still_running)
        }
        try {
            runningAs = ""
            runningWalletDid = ""
        } finally {
            callGate.writeLock().unlock()
        }
        val raw = try {
            if (host == null) ops.start(config.home, config.alias) else ops.startWithKey(config.home, config.alias, host.secret)
        } finally {
            host?.wipe()
        }
        val result = json(raw)
        val error = result?.optString("error").orEmpty()
        val did = result?.optString("did").orEmpty()
        return when {
            result == null -> SwarmStrings.get(R.string.swarmnode_radicle_unreadable_start)
            did.isNotEmpty() -> {
                bootedAs = host?.did.orEmpty()
                runningWalletDid = if (host != null && did == host.did) did else ""
                runningAs = did
                if (host != null && did != host.did) Log.w(TAG, "radicle booted as $did, not the wallet's ${host.did}")
                null
            }
            else -> error.ifEmpty { SwarmStrings.get(R.string.swarmnode_radicle_start_failed) }
        }
    }

    /**
     * The identity [Config.identity] gives changed (#328: a wallet was
     * created, imported or removed): a node that's up as another one
     * restarts as it, and one whose boot failed boots again. One that
     * isn't up yet is left alone — its boot reads the identity anyway. Runs on [lifecycle], after any boot or
     * shutdown already asked for, so it sees what that one booted as.
     */
    fun reloadIdentity() {
        scope.launch(lifecycle) {
            if (!booted) {
                // A boot that failed (the wallet's key couldn't be opened, say)
                // tries again now that the identity changed.
                synchronized(this@RadicleNode) {
                    if (wanted && _state.value.status == RadicleStatus.Error) start()
                }
                return@launch
            }
            val want = runCatching { config.identity()?.let { it.wipe(); it.did }.orEmpty() }.getOrElse {
                // The wallet's keys are there but can't be opened right now:
                // keep the node as it is rather than guess, and try again at
                // the next reload (every bind and every wallet change).
                Log.w(TAG, "radicle identity unreadable; not restarting: ${it.message}")
                return@launch
            }
            if (want == bootedAs) return@launch
            // The checks and both calls under one hold of the lock they take
            // (it's reentrant), which [seed] registers its run under too: a
            // user's [stop] lands either before (this sees `wanted` false and
            // leaves the node off) or after the restart (and stops the node it
            // boots), never in between; and a seed lands either before (and
            // is waited for below) or after the restart is queued (and runs
            // against the new node), never between the check and the stop.
            val fetch = synchronized(this@RadicleNode) {
                if (!wanted) return@launch
                // Moving to the wallet's identity keeps what the node seeds, so
                // a first fetch still running isn't cut short (a stop would
                // cancel it and take its policy back): the restart waits for
                // it, and asks again once it's over. Going back to the
                // device's own key (the wallet was removed) doesn't wait.
                val running = if (want.isEmpty()) null else lastRun?.takeIf { it.epoch == nodeEpoch.get() && it.running }
                if (running == null) {
                    Log.i(TAG, "radicle identity changed → restarting")
                    // Both queue behind this job on [lifecycle], in this order.
                    stop()
                    start()
                }
                running
            } ?: return@launch
            Log.i(TAG, "radicle identity changed; restarting once the fetch of ${fetch.rid} ends")
            scope.launch {
                fetch.join()
                reloadIdentity()
            }
        }
    }

    /**
     * Stop the node and join its thread. Also covers a start still in
     * flight: its generation is superseded here, so it publishes nothing,
     * and the shutdown below (queued behind it on [lifecycle]) takes down
     * whatever it booted. The status goes back to Stopped even if the
     * shutdown call itself reports an error, since the node is gone from
     * the UI's point of view either way; the error is kept.
     */
    fun stop() {
        // Under the same lock [seed] registers its run under: a seed either
        // lands first (and is marked stopping here) or sees `wanted` false.
        val (gen, run) = synchronized(this) {
            if (!wanted) return
            wanted = false
            poller?.cancel()
            poller = null
            dialJob?.cancel()
            dialJob = null
            _state.update { it.copy(status = RadicleStatus.Stopping) }
            generation.incrementAndGet() to seedRun.get()?.also { it.stopping = true }
        }
        run?.let { runCatching { ops.cancelClone(it.rid) } }
        scope.launch(lifecycle) {
            var error = ""
            if (booted) {
                // Let a cancelled seed finish its rollback while the node is
                // still up. Blocking (not suspending) keeps [lifecycle] held,
                // so a later start can't slip in ahead of this shutdown. The
                // cancel above is a no-op if it landed before the native
                // fetch registered, so keep asking until it's confirmed.
                // A fetch already transferring from a peer ignores the
                // cancel until that peer is done, so don't wait for it; nor
                // past [seedStopWaitMs] for one that won't wind down. Either
                // way the policy is taken back here, before the shutdown.
                lastRun?.takeIf { it.epoch == nodeEpoch.get() && it.job.isActive }?.let { last ->
                    runBlocking {
                        withTimeoutOrNull(seedStopWaitMs) {
                            if (last === run) cancelFetch(last)
                            while (last.job.isActive && !(last === run && last.fetching)) delay(CANCEL_RETRY_MS)
                        }
                    }
                    if (last.job.isActive && last.added && unseedNow(last.rid)) {
                        last.rolledBack = true
                        editPendingUnseeds { it - last.rid }
                    }
                }
                runningAs = ""
                runningWalletDid = ""
                error = runCatching { json(ops.shutdown())?.optString("error").orEmpty() }
                    .getOrElse { it.message ?: it.javaClass.simpleName }
                nodeEpoch.incrementAndGet()
                booted = false
                if (error.isNotEmpty()) Log.w(TAG, "radicle shutdown: $error")
            }
            publish(gen) { RadicleInfo(errorMessage = error.ifEmpty { null }) }
        }
    }

    /**
     * Replace the state with [next] only if [gen] is still the current
     * generation. The check and the write happen under the lock [start] /
     * [stop] bump the generation and publish Starting / Stopping under, so
     * a start landing between them can't have its Starting overwritten by
     * a superseded job's Stopped or Error.
     */
    private fun publish(gen: Long, next: () -> RadicleInfo) = synchronized(this) {
        if (gen == generation.get()) _state.value = next()
    }

    /**
     * [stop], for the owning service's teardown. The shutdown runs on
     * [scope] like any other; the `:node` process exits right after, and a
     * node killed mid-shutdown only leaves a stale control socket, which
     * the next start clears.
     */
    fun dispose() = stop()

    /**
     * The network changed (Wi-Fi ↔ cellular, airplane mode off): the old
     * sessions are dead, so dial the seed book again.
     */
    fun onNetworkChanged() {
        if (_state.value.status == RadicleStatus.Running) dial(generation.get())
    }

    /**
     * Seed and fetch the repository [input] names (`rad:z…`, `rad://z…` or
     * a bare `z…`), reporting phase progress through [RadicleInfo.seed].
     * Ignored while another seed is in flight or the node isn't running.
     * Neither a fetch left over from before a stop that wouldn't wind down
     * in time nor one the user already unseeded (a cancelled fetch that is
     * mid-transfer runs on until that peer is done) counts as in flight: the
     * UI has dropped its line and offers Seed again. A new seed of the
     * *same* RID as such a fetch is refused with [STALE_FETCH_DETAIL] until
     * it ends and its rollback and outcome have landed, so that rollback
     * can't take back the new call's policy.
     *
     * A fetch that fails or is cancelled takes back the seeding policy it
     * added, so a mistyped or unreachable RID doesn't sit in the seeded
     * list forever as "Awaiting first fetch". A RID that was already
     * seeded before this call keeps its policy.
     */
    fun seed(input: String) {
        if (_state.value.status != RadicleStatus.Running) return
        val rid = normalizeRid(input)
        if (rid == null) {
            _state.update {
                it.copy(seed = RadicleSeed.ofKey(input.trim(), PHASE_FAILED, RadicleSeed.DETAIL_INVALID_RID, active = false))
            }
            return
        }
        val (gen, run) = synchronized(this) {
            // Checked under the lock [stop] marks the run under; see there.
            if (!wanted || _state.value.status != RadicleStatus.Running) return
            val current = seedRun.get()
            if (current != null && current.epoch == nodeEpoch.get() && !current.dismissed) return
            if (liveFetches.any { it.rid == rid }) {
                _state.update {
                    it.copy(seed = RadicleSeed.ofKey(rid, PHASE_FAILED, RadicleSeed.DETAIL_STALE_FETCH, active = false))
                }
                return
            }
            val run = SeedRun(rid, nodeEpoch.get())
            liveFetches += run
            seedRun.set(run)
            lastRun = run
            _state.update { it.copy(seed = RadicleSeed(rid, PHASE_RESOLVING)) }
            generation.get() to run
        }
        run.job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                // Unknown (the list call failed) counts as seeded: never drop a
                // policy this call can't prove it added.
                val wasSeeded = runCatching { parseRepos(ops.listSeededRepos()) }.getOrNull()
                    ?.any { it.rid == rid } ?: true
                if (!wasSeeded) {
                    editPendingUnseeds { it + rid }
                    run.added = true
                }
                // Stopped, unseeded or superseded before the fetch began: don't
                // start it (a cancel that early never reaches the native fetch).
                val skipped = run.stopping || run.dismissed || run.epoch != nodeEpoch.get()
                val result = if (skipped) JSONObject().put("cancelled", true) else try {
                    json(
                        ops.cloneRepoWithProgress(rid, SEED_TIMEOUT_MS) { event ->
                            val parsed = json(event) ?: return@cloneRepoWithProgress
                            run.fetching = parsed.optString("phase") == PHASE_FETCHING
                            val progress = progressLine(rid, parsed)
                            _state.update { if (gen == generation.get() && !run.dismissed) it.copy(seed = progress) else it }
                        },
                    )
                } catch (t: Throwable) {
                    JSONObject().put("error", t.message ?: t.javaClass.simpleName)
                } finally {
                    run.fetching = false
                }
                run.fetchOver = true
                seedRun.compareAndSet(run, null)
                val settled = when {
                    result == null -> RadicleSeed.ofKey(rid, PHASE_FAILED, RadicleSeed.DETAIL_UNREADABLE_FETCH, active = false)
                    result.optBoolean("ok") -> RadicleSeed(rid, PHASE_DONE, active = false)
                    result.optBoolean("cancelled") -> RadicleSeed(rid, PHASE_CANCELLED, active = false)
                    else -> RadicleSeed(rid, PHASE_FAILED, result.optString("error"), active = false)
                }
                Log.i(TAG, "seed $rid → ${settled.phase} ${settled.detail}")
                // Roll back only on the node instance the fetch ran against; one
                // that has since been shut down is rolled back at its next boot.
                if (!wasSeeded && !run.rolledBack && run.epoch == nodeEpoch.get()) {
                    if (skipped || settled.phase == PHASE_DONE || unseedNow(rid)) editPendingUnseeds { it - rid }
                }
                // Settled under the lock [seed] checks [liveFetches] under: a retry
                // of this RID lands either before (refused) or after this line.
                synchronized(this@RadicleNode) {
                    _state.update {
                        when {
                            gen != generation.get() -> it
                            // Unseeded mid-fetch: the user removed it, so no outcome line.
                            run.dismissed -> if (it.seed?.rid == rid) it.copy(seed = null) else it
                            else -> it.copy(seed = settled)
                        }
                    }
                    liveFetches -= run
                }
                refreshRepos(gen)
            } finally {
                // Never leave a crashed call blocking its RID for good.
                run.fetchOver = true
                seedRun.compareAndSet(run, null)
                synchronized(this@RadicleNode) { liveFetches -= run }
            }
        }
        run.job.start()
    }

    /** Take [rid]'s seeding policy back; true if the node confirmed it. */
    private fun unseedNow(rid: String): Boolean {
        val undo = runCatching { ops.unseedRepo(rid) }.getOrElse { it.message.orEmpty() }
        Log.i(TAG, "seed $rid rolled back: $undo")
        return json(undo)?.has("error") == false
    }

    /**
     * Cancel [run]'s fetch, asking again until the node confirms it or the
     * fetch is over: a cancel that lands before the native fetch registered
     * its cancel token is a silent no-op (`{"cancelled":false}`). Keyed to
     * [run] itself, not to it still being [seedRun]: an unseeded run is
     * replaced there by the next seed, and must still be cancelled.
     */
    private suspend fun cancelFetch(run: SeedRun) {
        while (!run.fetchOver) {
            val confirmed = runCatching { json(ops.cancelClone(run.rid))?.optBoolean("cancelled") == true }
                .getOrDefault(false)
            if (confirmed) return
            delay(CANCEL_RETRY_MS)
        }
    }

    /**
     * Roll back what a seed call couldn't before the last shutdown (see
     * [pendingUnseedFile]). Runs right after boot, before the node is shown
     * as running, so no new seed can race it. A RID whose unseed failed
     * stays pending for the next boot while the node still lists it as
     * seeded (or the list can't be read); one it doesn't list had nothing
     * left to take back.
     */
    private fun replayPendingUnseeds() {
        val rids = readPendingUnseeds()
        if (rids.isEmpty()) return
        val failed = rids.filterNot { unseedNow(it) }.toSet()
        val keep = if (failed.isEmpty()) emptySet() else {
            val listed = runCatching { parseRepos(ops.listSeededRepos()) }.getOrNull()?.map { it.rid }?.toSet()
            if (listed == null) failed else failed intersect listed
        }
        editPendingUnseeds { it - (rids - keep) }
    }

    private fun readPendingUnseeds(): Set<String> =
        runCatching { pendingUnseedFile.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet() }
            .getOrDefault(emptySet())

    private fun editPendingUnseeds(edit: (Set<String>) -> Set<String>) = synchronized(pendingUnseedFile) {
        runCatching {
            val next = edit(readPendingUnseeds())
            if (next.isEmpty()) pendingUnseedFile.delete()
            else pendingUnseedFile.writeText(next.joinToString("\n", postfix = "\n"))
        }.onFailure { Log.w(TAG, "pending unseed list: ${it.message}") }
    }

    /**
     * Stop seeding [rid] (it drops out of the seeded list; the bare
     * repository stays in storage). Cancels its fetch first if it's the
     * one in flight. Ignored unless the node is running.
     */
    fun unseed(rid: String) {
        if (_state.value.status != RadicleStatus.Running) return
        val gen = generation.get()
        scope.launch {
            // Mark the in-flight fetch first so none of its later progress or
            // its "cancelled" outcome repaints a line for a RID the user removed.
            val inFlight = seedRun.get()?.takeIf { it.rid == rid }
            if (inFlight != null) {
                inFlight.dismissed = true
                withTimeoutOrNull(SEED_STOP_WAIT_MS) { cancelFetch(inFlight) }
            }
            val result = runCatching { json(ops.unseedRepo(rid)) }.getOrNull()
            Log.i(TAG, "unseed $rid: $result")
            _state.update {
                val line = it.seed
                if (gen == generation.get() && line?.rid == rid && (!line.active || inFlight != null)) it.copy(seed = null) else it
            }
            refreshRepos(gen)
        }
    }

    /**
     * One read or write for the browser (#124): the `rad://` repository
     * browser's reads and the `window.radicle` provider's identity and
     * COB writes. [method] must be one of [BROWSER_CALLS] — seeding,
     * unseeding and the lifecycle stay on their own entry points, so this
     * path can never start or stop the node or change what it seeds —
     * and [args] its arguments by name. Blocking; the caller runs it off
     * the main thread.
     *
     * Answers the library's JSON, or `{"error": …, "reason": …}` with a
     * machine-readable reason: `node-stopped` / `node-not-ready` while the
     * node isn't Running, `unsupported` for a method not on the list,
     * `native-failed` if the call itself threw (a missing argument, a
     * panic the bindings surfaced).
     */
    fun call(method: String, args: JSONObject): String {
        if (method !in BROWSER_CALLS) return errorJson("unsupported call: $method", "unsupported")
        when (_state.value.status) {
            RadicleStatus.Running -> {}
            RadicleStatus.Starting -> return errorJson("Radicle node is starting", "node-not-ready")
            else -> return errorJson("Radicle node is not running", "node-stopped")
        }
        val asDid = args.optString(AS_DID)
        if (asDid.isEmpty()) return if (method == "identity") identity() else native(method, args)
        // A write a site was allowed to make as one identity (#328): refused
        // if the node now runs as another. The check and the native call
        // under one hold of [callGate]'s read side, so a restart can't boot
        // the node as another identity between them. A boot draining the
        // gate means the node is on its way down or up: not ready.
        val gate = callGate.readLock()
        if (!gate.tryLock()) return errorJson("Radicle node is restarting", "node-not-ready")
        return try {
            if (asDid != runningAs) errorJson("Radicle identity changed", IDENTITY_CHANGED) else native(method, args)
        } finally {
            gate.unlock()
        }
    }

    /**
     * The library's `identity`, with [WALLET_IDENTITY]: whether its DID is
     * the wallet's. Read from the boot that answered, so a label is never
     * another boot's (a UI's pushed state can lag a restart); an answer
     * that doesn't match the boot now up is `node-not-ready`.
     */
    private fun identity(): String {
        val raw = native("identity", JSONObject())
        val o = json(raw) ?: return raw
        if (o.has("error")) return raw
        val did = o.optString("did")
        val wallet = runningWalletDid
        if (did.isEmpty() || did != runningAs) return errorJson("Radicle node is restarting", "node-not-ready")
        return o.put(WALLET_IDENTITY, wallet.isNotEmpty() && wallet == did).toString()
    }

    private fun native(method: String, args: JSONObject): String = runCatching { ops.call(method, args) }
        .getOrElse { errorJson(it.message ?: it.javaClass.simpleName, "native-failed") }

    /**
     * Dial the seed book for generation [gen]. Skipped only while a dial
     * for that same generation is still going; a dial left over from an
     * earlier boot never holds up a newer one.
     */
    private fun dial(gen: Long) = synchronized(this) {
        if (gen != generation.get()) return
        if (dialJob?.isActive == true && dialGen == gen) return
        dialGen = gen
        dialJob = scope.launch {
            val report = runCatching { json(ops.connectSeeds(DIAL_TIMEOUT_MS)) }.getOrNull()
            Log.i(TAG, "radicle seed dial: $report")
            refreshPeers(gen)
        }
    }

    private suspend fun pollStatus(gen: Long) {
        while (scope.isActive && gen == generation.get()) {
            delay(POLL_INTERVAL_MS)
            refreshPeers(gen)
            refreshRepos(gen)
        }
    }

    private fun refreshIdentity(gen: Long) {
        val id = runCatching { json(ops.identity()) }.getOrNull() ?: return
        if (id.has("error")) return
        _state.update {
            if (gen != generation.get()) it
            else it.copy(did = id.optString("did"), nid = id.optString("nid"), alias = id.optString("alias"))
        }
    }

    private fun refreshPeers(gen: Long) {
        val status = runCatching { json(ops.status()) }.getOrNull() ?: return
        if (!status.has("connectedPeers")) return
        val peers = status.optInt("connectedPeers")
        _state.update { if (gen == generation.get()) it.copy(connectedPeers = peers) else it }
    }

    private fun refreshRepos(gen: Long) {
        val raw = runCatching { ops.listSeededRepos() }.getOrNull() ?: return
        val repos = parseRepos(raw) ?: return
        _state.update { if (gen == generation.get()) it.copy(seededRepos = repos) else it }
    }

    companion object {
        private const val TAG = "RadicleNode"
        const val DEFAULT_ALIAS = "freedom-android"

        /** `sun_path` is 108 bytes on Linux, one of them the terminating NUL. */
        internal const val MAX_SOCKET_PATH = 107
        internal const val DIAL_TIMEOUT_MS = 15_000
        internal const val SEED_TIMEOUT_MS = 120_000
        /** How long a stop waits for a cancelled seed to roll back before shutting down anyway. */
        internal const val SEED_STOP_WAIT_MS = 10_000L
        /**
         * How long a boot waits for identity-bound writes still running
         * against the node before it (see [callGate]); past it, the boot fails.
         */
        internal const val WRITE_DRAIN_MS = 70_000L
        /** How often an unconfirmed fetch cancel is asked again. */
        internal const val CANCEL_RETRY_MS = 50L
        internal const val PENDING_UNSEED_FILE = "pending-unseed"
        internal const val POLL_INTERVAL_MS = 5_000L
        /** What a page is told when [seed] refuses a RID an earlier fetch still winds down (English, see [RadicleSeed.detail]). */
        internal val STALE_FETCH_DETAIL: String get() = SwarmStrings.english(R.string.swarmnode_radicle_stale_fetch)

        const val PHASE_RESOLVING = "resolving"
        const val PHASE_FETCHING = "fetching"
        const val PHASE_DONE = "done"
        const val PHASE_FAILED = "failed"
        const val PHASE_CANCELLED = "cancelled"

        /**
         * What [call] lets the browser reach: public repository reads,
         * the node's identity / status / seeding list, and the four COB
         * writes. Nothing that seeds, fetches, imports or stops.
         */
        val BROWSER_CALLS: Set<String> = setOf(
            "identity", "status", "listSeededRepos",
            "repoInfo", "seeders", "treeAt", "blobAt", "commit", "commits", "repoStats", "remotes",
            "issues", "issue", "patches", "patch",
            "createIssue", "commentIssue", "editIssueState", "commentPatch",
        )

        /**
         * An argument [call] takes for any method: the DID the caller
         * expects the node to run as. The call is refused (`identity-changed`)
         * if it runs as another. The library ignores it.
         */
        const val AS_DID = "asDid"

        /**
         * In [call]'s `identity` answer: true when the DID is the wallet's
         * (#328), false for the device's own. Not for pages.
         */
        const val WALLET_IDENTITY = "walletIdentity"

        /** [call]'s reason when the node doesn't run as [AS_DID]. */
        const val IDENTITY_CHANGED = "identity-changed"

        internal fun errorJson(message: String, reason: String? = null): String =
            JSONObject().put("error", message).apply { if (reason != null) put("reason", reason) }.toString()

        private val BARE_RID = Regex("^z[1-9A-HJ-NP-Za-km-z]{20,60}$")

        /**
         * `rad:z…` for a repository ID typed as `rad:z…`, `rad://z…` or a
         * bare `z…`, or null if it isn't one. Base58, so case matters and
         * nothing is case-folded.
         */
        fun normalizeRid(input: String): String? {
            var bare = input.trim()
            bare = when {
                bare.startsWith("rad://") -> bare.removePrefix("rad://")
                bare.startsWith("rad:") -> bare.removePrefix("rad:")
                else -> bare
            }
            return if (BARE_RID.matches(bare)) "rad:$bare" else null
        }

        internal fun parseRepos(raw: String): List<RadicleRepo>? {
            val array = runCatching { JSONArray(raw) }.getOrNull() ?: return null
            return (0 until array.length()).mapNotNull { i ->
                val repo = array.optJSONObject(i) ?: return@mapNotNull null
                val rid = repo.optString("rid")
                if (rid.isEmpty()) null
                else RadicleRepo(rid, if (repo.isNull("name")) "" else repo.optString("name"))
            }
        }

        /**
         * The seed line for a progress event: its phase, and the part worth
         * a line under it — English in [RadicleSeed.detail] (pages get it),
         * localised by [RadicleSeed.shown] for the node page.
         */
        internal fun progressLine(rid: String, event: JSONObject): RadicleSeed {
            val phase = event.optString("phase")
            return when (phase) {
                "resolving" -> RadicleSeed.ofKey(rid, phase, RadicleSeed.DETAIL_CANDIDATES, event.optInt("candidates").toString())
                "connecting" -> RadicleSeed(rid, phase, "${event.optString("addr")} (${event.optInt("index")}/${event.optInt("total")})")
                "fetching" -> RadicleSeed.ofKey(rid, phase, RadicleSeed.DETAIL_FETCHING_FROM, event.optString("nid"))
                "peer-failed", "failed" -> RadicleSeed(rid, phase, event.optString("reason"))
                else -> RadicleSeed(rid, phase)
            }
        }

        private fun json(raw: String): JSONObject? = runCatching { JSONObject(raw) }.getOrNull()
    }
}
