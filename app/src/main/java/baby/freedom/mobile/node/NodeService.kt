package baby.freedom.mobile.node

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Application
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteCallbackList
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import baby.freedom.mobile.R
import baby.freedom.mobile.l10n.Strings
import baby.freedom.mobile.l10n.TextLocale
import baby.freedom.mobile.chains.BuiltInChains
import baby.freedom.mobile.chains.Chain
import baby.freedom.mobile.chains.rpc.ChainDataRouter
import baby.freedom.mobile.chains.rpc.ColibriReads
import baby.freedom.mobile.chains.rpc.PinnedHttpTransport
import baby.freedom.mobile.data.ChainStore
import baby.freedom.mobile.data.NodeSettings
import baby.freedom.mobile.wallet.KeystoreVaultStore
import baby.freedom.mobile.wallet.NodeIdentityStore
import baby.freedom.swarm.AntChainTransport
import baby.freedom.swarm.DepositMaybeSentException
import baby.freedom.swarm.IpfsInfo
import baby.freedom.swarm.IpfsNode
import baby.freedom.swarm.NodeInfo
import baby.freedom.swarm.NodeStatus
import baby.freedom.swarm.RadicleInfo
import baby.freedom.swarm.RadicleNode
import baby.freedom.swarm.SwarmNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlin.system.exitProcess
import java.math.BigInteger
import org.json.JSONObject

/**
 * Holds the embedded Swarm + IPFS (+ Radicle, while on) nodes for the lifetime of the `:node`
 * process. The service is started + bound while the user wants the
 * node(s) running; when the user flips the Swarm toggle off the UI
 * calls [stopService] + [unbindService], Android destroys this Service,
 * and [onDestroy] kills the process outright so no native state — the
 * ant node's tokio runtime, the freedom-ipfs store lock — can outlive the toggle.
 * Killing the process also tears down the IPFS node, which is the
 * behaviour the user's global "Run node" toggle implies anyway.
 */
class NodeService : Service() {

    private lateinit var swarmNode: SwarmNode
    private lateinit var identityStore: NodeIdentityStore
    private lateinit var vaultStore: KeystoreVaultStore

    /** What the Swarm node's (re)start booted as (#77, #114), for [INodeService.reloadIdentity] / [INodeService.setSwarmMode]. */
    private val bootIdentity = SwarmBootIdentity()

    /**
     * The mode the Swarm node should run in (#114), as the UI last relayed
     * it through [INodeService.setSwarmMode]; null until it has, when the
     * first boot reads the persisted setting itself ([swarmMode]).
     */
    @Volatile
    private var relayedMode: SwarmNode.Mode? = null

    /** Numbers each [INodeService.setSwarmMode], so an older one can't land after a newer one. */
    private val modeRelays = java.util.concurrent.atomic.AtomicLong(0)

    /** The newest [modeRelays] number applied to [relayedMode]. Guarded by [modeRelays]. */
    private var appliedModeRelay = 0L

    /**
     * "Pay peers from the chequebook" as the UI last relayed it through
     * [INodeService.setSwapEnabled]; null until it has, when a boot reads
     * the persisted setting itself ([swapEnabled]).
     */
    @Volatile
    private var relayedSwap: Boolean? = null

    /**
     * The Swarm chunk cache's cap as the UI last relayed it through
     * [INodeService.setSwarmCacheCapacity]; null until it has, when a boot
     * reads the persisted setting itself ([swarmCacheCapacity]).
     */
    @Volatile
    private var relayedCacheCapacity: Long? = null

    /** The mode the launch now booting read, handed to [SwarmNode.Config.mode] right after its identity. */
    @Volatile
    private var launchMode: SwarmNode.Mode = SwarmNode.Mode.ULTRA_LIGHT

    /**
     * The Gnosis chain the Swarm node reads through the chain-data router
     * (#273), as the UI last relayed it with [INodeService.setSwarmMode] —
     * its RPCs and the user's; null until it has, when [storedGnosis] (read
     * here) stands in. Can carry API keys: never logged.
     */
    @Volatile
    private var relayedGnosis: Chain? = null

    @Volatile
    private var storedGnosis: Chain? = null

    /**
     * Answers ant's Gnosis reads (#273) through a router of this process's
     * own over [gnosisForReads], installed as [AntChainTransport]'s reader
     * for the life of the process: it's never taken down, since a postage
     * spend still running after [onDestroy] reads the chain (nonces,
     * receipts) until it ends, and the process exits after either way.
     */
    private lateinit var chainBridge: AntChainBridge
    private val myotisReads = MyotisReadBinding(this)
    private var ipfsNode: IpfsNode? = null

    /**
     * Serializes [maybeStartIpfs] and [maybeStopIpfs]. A start suspends on
     * two DataStore reads between its `ipfsNode == null` check and setting
     * [ipfsNode], so two overlapping starts (two IPFS navigations, or one
     * plus the Settings toggle) would otherwise both build an [IpfsNode]:
     * the first would be orphaned, keep its node (and the data dir's
     * process-wide lease) after IPFS off, and leave every later start
     * blocked in Starting. A stop queued behind a pending start then
     * disposes the instance that start made rather than finding nothing.
     */
    private val ipfsLifecycle = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Holds a stop back while a postage spend runs inside ant (#116); see [INodeService.stopWhenIdle]. */
    private val stopGate = SpendStopGate()

    /**
     * Was this instance created in a `:node` process an earlier instance
     * already doomed to exit after a spend ([ProcessExitLatch])? It then
     * starts nothing; the exit takes it down and Android restarts it fresh.
     */
    private val doomed: Boolean get() = ProcessExitLatch.node.pending

    /** `radicleCall`s running at once: the browser's reads and the provider's calls (#124). */
    private val radicleCalls = Semaphore(MAX_RADICLE_CALLS)

    /** Has the other end of the pipe [write] feeds been closed? (A pipe's write end polls POLLERR then.) */
    private fun readerGone(write: ParcelFileDescriptor): Boolean = try {
        val fd = StructPollfd().apply {
            this.fd = write.fileDescriptor
            events = 0
        }
        Os.poll(arrayOf(fd), 0)
        fd.revents.toInt() and (OsConstants.POLLERR or OsConstants.POLLHUP) != 0
    } catch (e: Exception) {
        false
    }
    private var swarmObserver: Job? = null
    private var ipfsObserver: Job? = null

    /**
     * The embedded Radicle node (#73). Created with the service but only
     * started while the user has Radicle on ([NodeSettings.radicleEnabled]),
     * which the UI relays through [INodeService.startRadicle].
     */
    private lateinit var radicleNode: RadicleNode
    private var radicleObserver: Job? = null

    /**
     * Bound UI clients that want state updates. [RemoteCallbackList]
     * keeps this Binder-death-safe so we don't leak callbacks when the
     * UI process is restarted.
     */
    private val callbacks = RemoteCallbackList<INodeCallback>()

    private val binder = object : INodeService.Stub() {
        override fun getState(): NodeInfo = reportedNodeInfo(swarmNode.state.value, doomed, bootIdentity.owed.value)

        override fun getIpfsState(): IpfsInfo = ipfsNode?.state?.value ?: IpfsInfo()

        // Binder thread, not [scope]: a cheap in-memory copy, answered
        // synchronously so the UI's poll gets this tick's snapshot.
        override fun getIpfsProgress(): String? = ipfsNode?.progressSnapshotJson()

        override fun getIpfsCounters(): LongArray? = ipfsNode?.diagnostics()

        override fun getLogs(source: Int): String =
            NodeLogSource.of(source)?.let { NodeLogs.text(it) }.orEmpty()

        override fun clearLogs() = NodeLogs.clear()

        override fun setColibriReads(on: Boolean) = ColibriReads.set(on)

        override fun registerCallback(cb: INodeCallback?) {
            cb ?: return
            callbacks.register(cb)
            runCatching { cb.onStateChanged(reportedNodeInfo(swarmNode.state.value, doomed, bootIdentity.owed.value)) }
            runCatching { cb.onIpfsStateChanged(ipfsNode?.state?.value ?: IpfsInfo()) }
            runCatching { cb.onRadicleStateChanged(radicleNode.state.value) }
        }

        override fun unregisterCallback(cb: INodeCallback?) {
            cb ?: return
            callbacks.unregister(cb)
        }

        override fun ensureIpfsStarted() {
            if (doomed) return
            scope.launch { maybeStartIpfs() }
        }

        override fun stopIpfs() {
            scope.launch { maybeStopIpfs() }
        }

        override fun onAppForeground() {
            scope.launch {
                Log.i(TAG, "app foreground → resume nodes")
                swarmNode.resume()
                // A launch that couldn't read the wallet's identity (#357)
                // sits in Error, and a reload (a mode change) that couldn't
                // read it wasn't applied; resume() touches neither, so
                // reopening the app reads the store again and restarts the
                // node once it reads.
                if (bootIdentity.retryDue()) {
                    launch(Dispatchers.IO) { restartSwarmIfStale("app foreground after an unreadable identity") }
                }
                ipfsNode?.enterForeground()
                repromoteForegroundIfDemoted()
            }
        }

        override fun onAppBackground() {
            scope.launch {
                Log.i(TAG, "app background → suspend nodes")
                swarmNode.suspend()
                ipfsNode?.enterBackground()
            }
        }

        override fun recoverNetwork() {
            scope.launch { recoverNetworkNow("ui request") }
        }

        override fun reloadIdentity() {
            if (doomed) return
            scope.launch(Dispatchers.IO) { restartSwarmIfStale("node identity changed") }
            // Radicle too (#328): restarts only if it's up as another identity.
            radicleNode.reloadIdentity()
        }

        override fun setSwarmMode(light: Boolean, gnosisRpc: String?, gnosisUserRpcs: List<String>?, gnosisRpcs: List<String>?) {
            // No Gnosis relayed (the UI hasn't read its chain list yet,
            // #300 R3-M1): keep the one this process has, and take the
            // mode's RPC from it below.
            if (gnosisRpc != null) {
                // Read by the next chain request at once: no restart needed.
                relayedGnosis = gnosisChainFor(
                    listOf(
                        BuiltInChains.GNOSIS.copy(
                            rpcUrls = gnosisRpcs.orEmpty().take(Chain.MAX_RPC_URLS),
                            userRpcUrls = gnosisUserRpcs.orEmpty().take(Chain.MAX_USER_RPC_URLS),
                        ),
                    ),
                )
            }
            if (doomed) return
            val seq = modeRelays.incrementAndGet()
            scope.launch(Dispatchers.IO) {
                val mode = when {
                    !light -> SwarmNode.Mode.ULTRA_LIGHT
                    gnosisRpc != null -> SwarmNode.Mode.light(gnosisRpc)
                    else -> SwarmNode.Mode.light(gnosisRpcFor(listOf(gnosisForReads())))
                }
                // A later relay that finished first stands.
                synchronized(modeRelays) {
                    if (seq < appliedModeRelay) return@launch
                    appliedModeRelay = seq
                    relayedMode = mode
                }
                restartSwarmIfStale("swarm mode is now $mode")
            }
        }

        override fun setSwapEnabled(enabled: Boolean) {
            relayedSwap = enabled
            swarmNode.setSwapEnabled(enabled)
        }

        override fun setSwarmCacheCapacity(bytes: Long) {
            relayedCacheCapacity = bytes
            swarmNode.setCacheCapacity(bytes)
        }

        override fun getSwarmCacheStatus(): String? =
            runCatching { swarmNode.cacheStatus() }.getOrNull()

        override fun clearSwarmCache(): String = runCatching { swarmNode.clearCache() }.getOrElse { e ->
            Log.w(TAG, "clearing the Swarm cache failed: ${e.javaClass.simpleName}: ${e.message}")
            JSONObject().put("error", e.message ?: Strings.get(R.string.node_call_failed)).toString()
        }

        override fun getRadicleState(): RadicleInfo = radicleNode.state.value

        override fun startRadicle() {
            if (doomed) return
            scope.launch { radicleNode.start() }
        }

        override fun stopRadicle() {
            scope.launch { radicleNode.stop() }
        }

        override fun seedRadicleRepo(rid: String?) {
            rid ?: return
            scope.launch { radicleNode.seed(rid) }
        }

        override fun unseedRadicleRepo(rid: String?) {
            rid ?: return
            scope.launch { radicleNode.unseed(rid) }
        }

        override fun radicleCall(method: String?, argsJson: String?): ParcelFileDescriptor {
            val (read, write) = ParcelFileDescriptor.createPipe()
            scope.launch(Dispatchers.IO) {
                // A few at a time (#201 R1-F4); a call whose reader gave
                // up (its deadline, a closed page) while it queued isn't
                // run at all.
                val answer = radicleCalls.withPermit {
                    if (readerGone(write)) {
                        null
                    } else {
                        runCatching {
                            radicleNode.call(method.orEmpty(), JSONObject(argsJson ?: "{}"))
                        }.getOrElse { JSONObject().put("error", it.message ?: "bad call").toString() }
                    }
                }
                // The reader may have given up since: the write then
                // fails, which ends this job.
                runCatching {
                    ParcelFileDescriptor.AutoCloseOutputStream(write).use { out ->
                        if (answer != null) out.write(answer.toByteArray())
                    }
                }
            }
            return read
        }

        override fun stampCall(method: String?, argsJson: String?): ParcelFileDescriptor {
            val (read, write) = ParcelFileDescriptor.createPipe()
            scope.launch(Dispatchers.IO) {
                // Nothing is started for a reader that already gave up.
                val answer = if (readerGone(write)) {
                    null
                } else {
                    runCatching { stampCallNow(method.orEmpty(), JSONObject(argsJson ?: "{}")) }
                        .getOrElse { e ->
                            Log.w(TAG, "stamp call $method failed: ${e.javaClass.simpleName}: ${e.message}")
                            JSONObject().put("error", e.message ?: Strings.get(R.string.node_call_failed))
                                // By type, for the app to tell "may be out" from "failed" (#280).
                                .apply { if (e is DepositMaybeSentException) put("maybeSent", true) }
                                .toString()
                        }
                }
                runCatching {
                    ParcelFileDescriptor.AutoCloseOutputStream(write).use { out ->
                        if (answer != null) out.write(answer.toByteArray())
                    }
                }
                stopIfDeferredStopDue()
            }
            return read
        }

        override fun stopWhenIdle(): Boolean {
            val deferred = stopGate.requestStop()
            if (deferred) {
                Log.i(TAG, "node turned off during a postage spend; stopping once it ends")
                stopAnywayWhenOverdue()
            }
            return deferred
        }
    }

    /**
     * Runs a postage spend (#116) under [stopGate]: refused once the node
     * is being turned off, and a stop asked for while it ran happens as
     * soon as it (the last one) ends — never in the middle, where exiting
     * the process could leave a batch paid for but unregistered.
     */
    private fun <T> spending(buy: Boolean = false, block: () -> T): T {
        check(!doomed && stopGate.begin(buy)) {
            Strings.get(
                if (!doomed && stopGate.discoverRunning) R.string.node_stamps_searching else R.string.node_turning_off,
            )
        }
        try {
            return block()
        } finally {
            stopGate.end(buy)
        }
    }

    /**
     * Bounds a deferred stop (#116): if the spend is still running once
     * [SPEND_STOP_WAIT_MS] has passed since the stop was asked for — ant
     * hung — the service stops anyway, and [onDestroy] then exits without
     * waiting further, so a node the user turned off doesn't go on
     * peering indefinitely. Tagged with the stop's generation, so turning
     * the node back on (and off again) meanwhile disarms it.
     */
    private fun stopAnywayWhenOverdue() {
        val generation = stopGate.stopGeneration
        val left = stopGate.budgetLeftMs(SPEND_STOP_WAIT_MS)
        scope.launch {
            delay(left)
            if (stopGate.overdue(generation, SPEND_STOP_WAIT_MS)) {
                Log.w(TAG, "postage spend still running ${SPEND_STOP_WAIT_MS / 60_000} min after the node was turned off; stopping anyway")
                stopSelf()
            }
        }
    }

    /**
     * Carries out a stop [INodeService.stopWhenIdle] deferred, if it's due
     * now. Called once a stamp call's answer is written, so the process
     * doesn't exit before the app has read how the spend went.
     */
    private fun stopIfDeferredStopDue() {
        if (!stopGate.shouldStopNow()) return
        scope.launch {
            // Re-checked on the main thread, where onStartCommand cancels
            // a stop the user took back meanwhile.
            if (stopGate.shouldStopNow()) {
                Log.i(TAG, "postage spend ended; carrying out the deferred stop")
                stopSelf()
            }
        }
    }

    /**
     * One [INodeService.stampCall] (#115, #116, #117), blocking. The spends are for
     * the wallet identity's node only: the device-only key can't be
     * restored anywhere, so nothing bought with it could be kept.
     */
    private fun stampCallNow(method: String, args: JSONObject): String {
        fun days() = args.getLong("days").also { require(it in 1..MAX_STAMP_DAYS) { "bad duration" } }
        fun amount() = BigInteger(args.getString("amountPerChunk")).also { require(it.signum() > 0) { "bad amount" } }
        fun maxSwap() = BigInteger(args.getString("maxSwapWei")).also { require(it.signum() >= 0) { "bad xDAI total" } }
        fun spendable() = check(swarmNode.state.value.walletIdentity) {
            Strings.get(R.string.node_not_wallet_identity)
        }
        return when (method) {
            "status" -> swarmNode.storageStatus()
            "swapStatus" -> swarmNode.swapStatus()
            // Not a spend, but it lets the node pay again: the Chequebook
            // page sends it only from its warning's confirmation.
            "confirmLiability" -> {
                Log.i(TAG, "confirming a lost cheque ledger's outstanding cheques, as the user confirmed")
                val confirmed = swarmNode.confirmChequeLiability(args.getString("chequebook"))
                JSONObject().put("confirmed", confirmed).toString()
            }
            "quote" -> {
                val depth = args.getInt("depth").also { require(it in MIN_STAMP_DEPTH..MAX_STAMP_DEPTH) { "bad depth" } }
                swarmNode.storageQuote(depth, days())
            }
            "extendQuote" -> {
                // ant prices its connected batch only; say so rather than price another.
                val want = SwarmNode.normalizeBatchId(args.getString("batchId")) ?: throw IllegalArgumentException("bad batch id")
                val connected = JSONObject(swarmNode.storageStatus())
                check(SwarmNode.normalizeBatchId(connected.optString("batch_id")) == want) {
                    Strings.get(R.string.node_stamp_not_active)
                }
                swarmNode.storageTopupQuote(days())
            }
            // Registers the stamps this account already owns (#118). Never
            // alongside a spend, so no permit is open and it sends nothing.
            "discover" -> {
                check(stopGate.beginDiscover()) { Strings.get(R.string.node_stamp_work_running) }
                // Kept under the app's id for it, for a search that runs
                // on after the app stopped waiting ("discovering" below).
                val id = args.optString("id").ifEmpty { null }
                var outcome: String? = null
                try {
                    swarmNode.discoverStamps().also { outcome = it }
                } catch (e: Exception) {
                    outcome = JSONObject().put("error", e.message ?: Strings.get(R.string.node_call_failed)).toString()
                    throw e
                } finally {
                    stopGate.endDiscover(id, outcome)
                }
            }
            // Whether a discover still runs, and once it's over, how the
            // app's search [id] ended: the app asks once it has stopped
            // waiting for one, so it holds a publish back until the search
            // is over (the #222 lock, from when a search could end by
            // reloading the gateway — it no longer does, ant 0.5.52+), and
            // then says what it found or why it failed.
            "discovering" -> {
                val status = stopGate.discoverStatus(args.optString("id").ifEmpty { null })
                JSONObject().put("running", status.running).apply {
                    status.outcome?.let { o -> runCatching { JSONObject(o) }.getOrNull()?.let { put("outcome", it) } }
                }.toString()
            }
            // Whether a buy (or connect) or a discover runs: a publish waits
            // for it before sending (#222 R4-F1). Kept from when these could
            // end by reloading the gateway; since ant 0.5.52 they don't.
            "gatewayWork" -> JSONObject().put("running", stopGate.gatewayWorkRunning).toString()
            "buy" -> spending(buy = true) {
                spendable()
                val depth = args.getInt("depth").also { require(it in MIN_STAMP_DEPTH..MAX_STAMP_DEPTH) { "bad depth" } }
                Log.i(TAG, "buying a postage batch (depth $depth), as the user confirmed")
                swarmNode.buyStamp(depth, amount(), immutable = true, maxSwapWei = maxSwap())
            }
            "extend" -> spending {
                spendable()
                Log.i(TAG, "extending a postage batch, as the user confirmed")
                swarmNode.extendStamp(args.getString("batchId"), amount(), maxSwap())
            }
            // Counted as a buy: a first connect sets up the chequebook, as a
            // first buy does (the running gateway picks it up itself, ant
            // 0.5.52+); a publish still waits it out, as for a buy.
            "connect" -> spending(buy = true) {
                // A batch the wallet bought for the node (#115); ant checks the node owns it.
                spendable()
                Log.i(TAG, "connecting a postage batch the wallet bought for the node")
                swarmNode.connectBatch(args.getString("batchId"))
            }
            "deposit" -> spending {
                spendable()
                val plur = BigInteger(args.getString("amountPlur")).also { require(it.signum() > 0) { "bad amount" } }
                Log.i(TAG, "depositing into the chequebook, as the user confirmed")
                swarmNode.depositChequebook(args.getString("chequebook"), plur)
            }
            else -> throw IllegalArgumentException("unknown stamp call")
        }
    }

    /**
     * Restart the Swarm node if it booted as another identity (#77) or in
     * another mode (#114) than it would boot as now — once, however many
     * reloads race the change (see [SwarmBootIdentity]). A store that
     * can't be read now (#357) says nothing about what the node should be:
     * the node is kept, not restarted as ant's own key, and the reload is
     * retried when the app next comes to the foreground.
     */
    private fun restartSwarmIfStale(reason: String) {
        bootIdentity.restartIfStale(
            want = want@{
                val boot = try {
                    identityStore.boot(vaultStore)
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "$reason, but the wallet's Swarm identity can't be read; keeping the node as it is: ${e.message}")
                    return@want null
                }
                val address = boot?.let {
                    it.antIdentity.fill(0)
                    it.swarmAddress
                }.orEmpty()
                swarmBootKey(address, swarmMode())
            },
            running = { swarmNode.state.value.status != NodeStatus.Stopped },
            restart = {
                Log.i(TAG, "$reason → restarting swarm")
                swarmNode.restart()
            },
        )
    }

    /**
     * The mode the Swarm node should boot in (#114): the UI's latest
     * [INodeService.setSwarmMode], or — before this process has heard one,
     * as on its first boot, which starts before the UI binds — the
     * persisted setting and the chains, read here. That read is this
     * process's first of those stores, so it can't be a stale cached copy.
     * Blocking: called from a launch's IO thread.
     */
    private fun swarmMode(): SwarmNode.Mode = relayedMode ?: try {
        runBlocking {
            // Null while the file can't be read: nothing is kept then, so
            // a passing read error doesn't pin the Swarm node's reads to
            // the shipped RPCs ([gnosisForReads] reads again).
            val chains = ChainStore.get(this@NodeService).chainsOrUnreadable.first()
            chains?.let { storedGnosis = gnosisChainFor(it) }
            swarmModeFor(NodeSettings.get(this@NodeService).swarmLightMode.first(), chains ?: BuiltInChains.ALL)
        }
    } catch (e: Exception) {
        Log.w(TAG, "reading the swarm mode failed (${e.javaClass.simpleName}); ultra-light")
        SwarmNode.Mode.ULTRA_LIGHT
    }

    /**
     * "Pay peers from the chequebook": the UI's latest
     * [INodeService.setSwapEnabled], or — before this process has heard
     * one, as on its first boot — the persisted setting, read here as
     * [swarmMode] reads the mode. On (ant's default) when it can't be read.
     * Blocking: called from a launch's IO thread.
     */
    private fun swapEnabled(): Boolean = relayedSwap ?: try {
        runBlocking { NodeSettings.get(this@NodeService).swarmSwapEnabled.first() }
    } catch (e: Exception) {
        Log.w(TAG, "reading the pay-peers setting failed (${e.javaClass.simpleName}); on")
        true
    }

    /**
     * The Swarm chunk cache's cap: the UI's latest
     * [INodeService.setSwarmCacheCapacity], or — before this process has
     * heard one — the persisted setting, read here as [swapEnabled] reads
     * its own. ant's default when it can't be read. Blocking: called from
     * a launch's IO thread.
     */
    private fun swarmCacheCapacity(): Long = relayedCacheCapacity ?: try {
        runBlocking { NodeSettings.get(this@NodeService).swarmCacheCapacityBytes.first() }
    } catch (e: Exception) {
        Log.w(TAG, "reading the Swarm cache size failed (${e.javaClass.simpleName}); ant's default")
        0L
    }

    /**
     * The Gnosis chain for the Swarm node's reads (#273): the UI's latest
     * relay, or — before this process has heard one — the chains store,
     * read here as [swarmMode] does. While that can't be read, the shipped
     * chain answers this read, and nothing is kept: the next read tries
     * the store again, so a passing error doesn't leave the user's own
     * Gnosis RPC out until the UI next relays.
     */
    private suspend fun gnosisForReads(): Chain = relayedGnosis ?: storedGnosis ?: try {
        ChainStore.get(this).chainsOrUnreadable.first()?.let { chains -> gnosisChainFor(chains).also { storedGnosis = it } }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        null
    } ?: BuiltInChains.GNOSIS.also { Log.w(TAG, "reading the Gnosis RPCs failed; the shipped ones for now") }

    /**
     * Drop stale connections and redial on every node. Triggered by a
     * network change, or by the UI after a dweb fetch failed against a
     * node that reports Running (the wedge in freedom-hq/ant#12).
     */
    private fun recoverNetworkNow(reason: String) {
        Log.i(TAG, "recover network ($reason)")
        swarmNode.onNetworkChanged()
        ipfsNode?.onNetworkChanged()
        radicleNode.onNetworkChanged()
    }

    /**
     * Connectivity changes are observed here, in the `:node` process,
     * rather than relayed from the UI: they matter most while the UI
     * is gone. A network becoming available after one was lost is the
     * Wi-Fi ↔ cellular handoff / airplane-mode-off case; the nodes'
     * sockets from the previous network are dead and nothing redials.
     */
    private var lastNetwork: Network? = null
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val previous = lastNetwork
            lastNetwork = network
            if (previous != null && previous != network) {
                scope.launch { recoverNetworkNow("network changed $previous → $network") }
            }
        }

        override fun onLost(network: Network) {
            if (lastNetwork == network) {
                // Keep `lastNetwork` so the next onAvailable counts as a
                // change even if the same Network object comes back.
                Log.i(TAG, "network lost $network")
            }
        }
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { cm.registerNetworkCallback(request, networkCallback) }
            .onFailure { Log.w(TAG, "network callback registration failed", it) }
    }

    private fun unregisterNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        runCatching { cm.unregisterNetworkCallback(networkCallback) }
    }

    /**
     * Android 15+ stops a `dataSync` foreground service after ~6 h of
     * use per day and calls this first; an app that doesn't stop the
     * service promptly is crashed. We drop foreground status but keep
     * the service alive for as long as the UI is bound to it, and try
     * to re-promote it the next time the app comes to the foreground
     * (the budget resets daily; the call is refused until then). With
     * no UI bound it stops instead (#458): see [ForegroundHold].
     *
     * While demoted, the `:node` process is an ordinary background
     * process: Android may freeze it, which is exactly the state
     * [INodeService.onAppForeground] recovers from.
     */
    private val foreground = ForegroundHold()

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "foreground service time limit reached (type=$fgsType); demoting")
        val stop = foreground.timedOut()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        if (stop) {
            Log.w(TAG, "no app bound to a demoted node; stopping rather than be sticky-restarted")
            stopSelf()
        }
    }

    private fun repromoteForegroundIfDemoted() {
        if (!foreground.demoted) return
        val refusal = foreground.promote {
            startForeground(NOTIFICATION_ID, buildNotification(reportedNodeInfo(swarmNode.state.value, doomed, bootIdentity.owed.value)), foregroundTypeCompat())
        }
        Log.i(TAG, if (refusal == null) "re-promoted to foreground service" else "foreground re-promotion refused: ${refusal.message}")
    }

    override fun onBind(intent: Intent?): IBinder {
        foreground.bind()
        return binder
    }

    override fun onRebind(intent: Intent?) {
        foreground.bind()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        val token = foreground.unbind()
        if (foreground.shouldStop()) {
            // A moment's grace, so an Activity recreated in place (it
            // unbinds, then binds again) doesn't stop the node. Only this
            // unbind's own timer acts, and only if nothing (a rebind, the
            // app's start, a later unbind) came since.
            scope.launch {
                delay(UNBOUND_STOP_GRACE_MS)
                if (foreground.graceOver(token)) {
                    Log.w(TAG, "app unbound from a demoted node; stopping")
                    stopSelf()
                }
            }
        }
        // onRebind for the next bind, so it's counted too.
        return true
    }

    override fun onCreate() {
        super.onCreate()
        NodeLogs.start(Application.getProcessName(), ::nodeProcessSource)
        createChannel()

        identityStore = NodeIdentityStore.get(this)
        vaultStore = KeystoreVaultStore(this)
        // Before the node starts: ant's first chain reads come at its
        // gateway's start.
        // Its light-client tier reads through this process's own
        // binding to `:myotis`, which never starts the light client.
        myotisReads.bind()
        // The Colibri proofs switch (#329): the UI relays it on bind; till
        // then the stored value, read here (off until either lands).
        scope.launch(Dispatchers.IO) {
            runCatching { NodeSettings.get(this@NodeService).ensColibri.first() }
                .onSuccess(ColibriReads::seed)
                .onFailure { Log.w(TAG, "reading the Colibri proofs setting failed (${it.javaClass.simpleName})") }
        }
        chainBridge = AntChainBridge(
            ChainDataRouter(
                chains = { listOf(gnosisForReads()) },
                transport = PinnedHttpTransport(),
                verifiedSources = ChainDataRouter.verifiedSources(this),
            ),
        )
        AntChainTransport.install(chainBridge::serve, chainBridge::cancelInFlight)
        swarmNode = SwarmNode(
            SwarmNode.Config(
                dataDir = filesDir.absolutePath,
                // The wallet's Swarm identity (#77) when there is one,
                // re-read at every (re)start; ant's own otherwise.
                // And the mode (#114), read in the same step so the boot
                // key records the pair this launch really boots as.
                // When the wallet's keys are there but can't be read (#357),
                // the launch fails with an error the Nodes page shows rather
                // than boot as ant's own key; the next reload (a bind, an
                // unlock, a Remove wallet, the app coming back to the
                // foreground) tries again.
                identity = {
                    bootIdentity.boot {
                        val boot = try {
                            identityStore.boot(vaultStore)
                        } catch (e: IllegalStateException) {
                            Log.w(TAG, "swarm identity unreadable: ${e.message}")
                            // The wallet file itself unreadable can't be
                            // unlocked: only removing it (or the file
                            // reading again) gets past it, so say that.
                            val walletUnreadable = runCatching { vaultStore.read() }.getOrNull() == null
                            val message = if (walletUnreadable) {
                                R.string.node_swarm_wallet_unreadable
                            } else {
                                R.string.node_swarm_identity_unreadable
                            }
                            throw IllegalStateException(getString(message), e)
                        }
                        val mode = swarmMode()
                        launchMode = mode
                        swarmBootKey(boot?.swarmAddress.orEmpty(), mode) to boot?.antIdentity
                    }
                },
                mode = { launchMode },
                swapEnabled = ::swapEnabled,
                cacheCapacityBytes = ::swarmCacheCapacity,
            ),
        )

        // Refused in the background on Android 12+ (a sticky restart) and
        // once the day's dataSync budget is spent on 15+ (#458): demoted
        // then, not crashed; onStartCommand stops it if no app is bound.
        foreground.promote {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(reportedNodeInfo(NodeInfo(), doomed)),
                foregroundTypeCompat(),
            )
        }?.let { Log.w(TAG, "foreground status refused at create; running demoted: ${it.message}") }

        // In a doomed process, why the node isn't up yet (#116);
        // and a restart waiting on an unreadable identity (#357).
        val swarmReported = combine(swarmNode.state, bootIdentity.owed) { raw, owed ->
            reportedNodeInfo(raw, doomed, owed)
        }
        // A new peer count alone (every second while it churns) reaches
        // the app at most every few seconds and the notification at most
        // twice a minute (#471); anything else goes out at once.
        swarmObserver = scope.launch {
            swarmReported.throttlePeerCount(PEER_BROADCAST_MS)
                .onEach { info ->
                    broadcastState(info)
                    Log.i(TAG, "swarm → ${info.status}  peers=${info.connectedPeers}")
                }
                .launchIn(this)
            swarmReported.throttlePeerCount(PEER_NOTIFICATION_MS)
                .onEach(::updateNotification)
                .launchIn(this)
        }

        if (doomed) {
            // An earlier instance's exit is pending (#116): starting ant
            // here would collide with its still-live handle, and be killed.
            Log.w(TAG, "created while :node waits to exit after a postage spend; starting nothing until the restart")
        } else {
            swarmNode.start()
            registerNetworkCallback()
        }

        // Radicle (#73) runs only while the user has it on: the UI calls
        // [INodeService.startRadicle] on every bind while the setting is
        // on (the setting lives in the UI process's DataStore). Its profile
        // lives under files/radicle; the control socket stays there too
        // unless that path is too long for a unix socket. It runs as the
        // wallet's Radicle identity (#328) when there is one, handed to
        // libradicle from memory at each boot; the profile's own key
        // (kept in files/radicle/keys) otherwise.
        radicleNode = RadicleNode(
            RadicleNode.Config(
                home = filesDir.resolve("radicle").absolutePath,
                shortSocketDir = cacheDir.absolutePath,
                identity = { identityStore.radicle(vaultStore) },
            ),
        )
        radicleObserver = radicleNode.state
            .onEach { info ->
                broadcastRadicleState(info)
                Log.i(TAG, "radicle → ${info.status}  peers=${info.connectedPeers}  seeded=${info.seededRepos.size}")
            }
            .launchIn(scope)

        // IPFS is NOT started here. Cold boot leaves the freedom-ipfs node
        // dormant so users who never visit `ipfs://` / IPFS-resolved
        // ENS content don't pay the bootstrap cost (or the background
        // peer churn) for a network they'll never use. The UI calls
        // [INodeService.ensureIpfsStarted] the first time a navigation
        // actually needs IPFS, which routes into [maybeStartIpfs]
        // below — idempotent, so repeated calls after start are
        // cheap no-ops.
    }

    /**
     * Idempotently bring the IPFS node up. Driven entirely by the UI:
     * either the user flipping the IPFS toggle on in Settings, or the
     * first `ipfs://` / `ipns://` / IPFS-resolved `ens://` navigation
     * hitting [INodeService.ensureIpfsStarted]. No-op if an IPFS node
     * is already live in this `:node` process.
     *
     * `ipfs_low_power` and `ipfs_routing_mode` are snapshotted here
     * and applied on node init; the freedom-ipfs wrapper
     * has no live-reconfig path, so changing them in Settings only
     * takes effect on the next start cycle (off → on).
     */
    private suspend fun maybeStartIpfs(): Unit = ipfsLifecycle.withLock {
        if (ipfsNode != null) return@withLock
        val settings = NodeSettings.get(this)
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

    /**
     * Shut the IPFS node down and clear the slot so a subsequent
     * [maybeStartIpfs] spins up a fresh instance. Swarm is
     * deliberately left alone — stopping IPFS shouldn't close
     * `bzz://` pages the user currently has open.
     */
    private suspend fun maybeStopIpfs(): Unit = ipfsLifecycle.withLock {
        val node = ipfsNode ?: return@withLock
        ipfsObserver?.cancel()
        ipfsObserver = null
        node.dispose()
        ipfsNode = null
        // Emit a final Stopped tick so the UI toggle + status
        // immediately reflect the off state instead of lingering
        // on the last Running frame.
        broadcastIpfsState(IpfsInfo())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The user turned the node (back) on: a stop still waiting for a
        // spend to end (#116) no longer stands.
        stopGate.cancelStop()
        // A sticky restart (null intent) Android couldn't promote, with no
        // app bound to re-promote it: stop rather than be killed and
        // restarted into the same refusal (#458).
        if (foreground.started(stickyRestart = intent == null)) {
            Log.w(TAG, "sticky restart refused foreground status with no app bound; stopping")
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterNetworkCallback()
        myotisReads.unbind()
        callbacks.kill()
        swarmObserver?.cancel()
        ipfsObserver?.cancel()
        radicleObserver?.cancel()
        scope.cancel()
        ipfsNode?.dispose()
        ipfsNode = null
        radicleNode.dispose()
        swarmNode.dispose()
        super.onDestroy()

        // Kill the :node process so nothing native lingers (ant's
        // tokio runtime threads, the freedom-ipfs store lock). The next
        // startForegroundService() from the UI boots a fresh process.
        //
        // Not while a postage spend is still inside ant (#116), though:
        // the UI defers its stop through [INodeService.stopWhenIdle], but
        // a stop that got here some other way (the UI not yet bound when
        // the user turned the node off) must still not exit mid-spend.
        // The spend holds its read lock on ant's handle, so the teardown
        // above never frees ant under it; only the exit waits for it.
        stopGate.requestStop()
        if (doomed) {
            // An earlier instance's spend is still running in this process;
            // its exit thread ends the process once that spend is done.
            Log.i(TAG, "destroyed while :node waits to exit after a postage spend")
            return
        }
        if (stopGate.spendsRunning == 0) {
            Log.i(TAG, "exiting :node process to release state-store lock")
            exitProcess(0)
        }
        Log.w(TAG, "destroyed during a postage spend; exiting once it ends")
        // A service created in this process from now on starts nothing.
        ProcessExitLatch.node.schedule()
        // The wait counts from when the stop was first asked for: a
        // deferred stop that already waited out its budget
        // ([stopAnywayWhenOverdue]) exits right away.
        val budget = stopGate.budgetLeftMs(SPEND_STOP_WAIT_MS)
        Thread({
            stopGate.awaitIdle(budget)
            // A moment for the spend's answer to reach the app.
            Thread.sleep(ANSWER_GRACE_MS)
            Log.i(TAG, "exiting :node process to release state-store lock")
            exitProcess(0)
        }, "node-exit-after-spend").start()
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

    private fun broadcastRadicleState(info: RadicleInfo) {
        val n = callbacks.beginBroadcast()
        for (i in 0 until n) {
            runCatching { callbacks.getBroadcastItem(i).onRadicleStateChanged(info) }
                .onFailure { Log.w(TAG, "radicle callback threw", it) }
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
        if (foreground.demoted) return
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.notify(NOTIFICATION_ID, buildNotification(info))
    }

    // The notification intentionally reflects only the Swarm node.
    // Surfacing IPFS here would spoil the "look, vitalik.eth also
    // works" demo moment; the hidden settings card is the one place
    // IPFS status is visible today.
    private fun buildNotification(info: NodeInfo): Notification {
        val text = when (info.status) {
            NodeStatus.Stopped -> getString(R.string.node_status_stopped)
            NodeStatus.Starting -> info.errorMessage ?: getString(R.string.node_status_starting)
            NodeStatus.Running -> TextLocale.plural(
                this,
                R.plurals.node_notification_running,
                info.connectedPeers.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                info.connectedPeers,
            )
            NodeStatus.Error ->
                getString(R.string.node_notification_error, info.errorMessage ?: getString(R.string.node_error_unknown))
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.node_notification_title))
            .setContentText(text)
            // The peer-network mark from the menu's Nodes row, not the
            // system's download arrow: a running node isn't a download.
            .setSmallIcon(R.drawable.ic_nodes)
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
        /** The stamp sizes the buy screen offers (#116): 2^17 chunks (ant's and bee's smallest) up to 2^24. */
        const val MIN_STAMP_DEPTH = 17
        const val MAX_STAMP_DEPTH = 24

        /** A stamp bought or extended for at most ten years at a time. */
        const val MAX_STAMP_DAYS = 3650L

        private const val MAX_RADICLE_CALLS = 4

        /** How often a change in the Swarm peer count alone reaches the app, at most (#471). */
        private const val PEER_BROADCAST_MS = 5_000L

        /** How often a change in the Swarm peer count alone reposts the notification, at most (#471). */
        private const val PEER_NOTIFICATION_MS = 30_000L

        /**
         * The longest a stop waits for a postage spend still running
         * (#116), counted from when it was asked for: a deferred stop
         * ([INodeService.stopWhenIdle]) and a destroyed service's exit alike.
         */
        private const val SPEND_STOP_WAIT_MS = 15 * 60_000L
        private const val ANSWER_GRACE_MS = 1_000L
        private const val UNBOUND_STOP_GRACE_MS = 5_000L

        private const val TAG = "NodeService"
        private const val CHANNEL_ID = "freedom_node"
        private const val NOTIFICATION_ID = 1

        fun start(ctx: Context) {
            val i = Intent(ctx, NodeService::class.java)
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, NodeService::class.java))
        }
    }
}
