package baby.freedom.mobile.browser

import android.content.Context
import android.util.Log
import baby.freedom.mobile.wallet.SendStatus
import baby.freedom.mobile.wallet.WalletSender
import baby.freedom.mobile.chains.rpc.ChainDataRouter
import baby.freedom.mobile.chains.rpc.WalletRpc
import baby.freedom.mobile.wallet.SwarmFunder
import java.io.File
import java.math.BigInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * The stamp the wallet bought for the Swarm node through SwarmNodeFunder
 * (#115), from the moment its call is signed until the node has connected
 * it. The batch is on chain once the call is mined, but the node only
 * stamps with it once it's registered there ([StampClient.connect]) — so
 * this follows the wallet's sends ([WalletSender.changes]) and connects
 * the batch as soon as its call is confirmed. Kept in a file, so a stamp
 * whose connect failed (the node was off) or was cut short by the process
 * dying is still offered for connecting afterwards (publish setup and the
 * fund page show it, with a Connect button). A call the wallet stopped
 * following before it was mined is still looked up by its hash on chain
 * ([chain], [checkChain]), so one that lands later is connected all the
 * same, and one that reverted, or whose nonce went to another
 * transaction, is known to have bought nothing.
 */
internal class SwarmFunding(
    private val file: File,
    private val connect: (String) -> Boolean = StampClient::connect,
    private val spends: Flow<StampClient.Spend> = StampClient.spend,
    private val chain: ChainReader? = null,
    private val checkEveryMs: Long = CHECK_EVERY_MS,
    private val checkAtMostEveryMs: Long = CHECK_AT_MOST_EVERY_MS,
    private val confirmAfterMs: Long = CONFIRM_AFTER_MS,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    /**
     * Whether a connect may start now: no spend, stamp search or publish
     * upload holds the node ([StampClient.canRestartGateway]). A connect
     * owed while one did ([connectOwed]) is retried once this turns true.
     */
    private val connectFree: Flow<Boolean> =
        combine(StampClient.spend, StampClient.discovery, Publisher.state, StampClient::canRestartGateway),
) {
    /** Gnosis Chain reads for a call the wallet no longer follows. */
    interface ChainReader {
        /** The call's receipt, or null while it isn't mined (or is unknown). */
        suspend fun receipt(hash: String): Receipt?

        /** How many of [address]'s transactions are mined: its next nonce at the latest block. */
        suspend fun minedCount(address: String): BigInteger
    }

    /** A receipt as read, and whether the read was one to act on alone ([chainReadTrusted]). */
    data class Receipt(val json: JSONObject, val trusted: Boolean)

    /**
     * The one stamp being bought or waiting to be connected. [mined]:
     * its call is confirmed, so the batch exists and only needs connecting.
     */
    data class Pending(
        /** EIP-55: the node the batch is for; only that node can connect it. */
        val node: String,
        val batchId: String,
        val depth: Int,
        val days: Long,
        val hash: String?,
        val mined: Boolean,
        /**
         * False once the wallet stopped following its call before it was
         * mined (Stop tracking, the wallet removed, or no send left after a
         * restart): it may still land or never will, so it no longer holds
         * up funding, and Connect (which fails harmlessly for a batch that
         * isn't on chain) and Dismiss are offered.
         */
        val tracked: Boolean = true,
        /** The paying account and the call's nonce, to tell a call that can never be mined any more. */
        val from: String? = null,
        val nonce: BigInteger? = null,
    )

    private val _pending = MutableStateFlow(load())
    val pending: StateFlow<Pending?> = _pending.asStateFlow()

    /**
     * The batch id of a mined record the app itself is to connect: its
     * call was just seen mined, and [connect] refused to start then
     * (another spend, a stamp search, or a publish uploading). Retried as
     * soon as [connectFree] allows, and cleared once a connect for it
     * starts or the record goes. A mined record not owed one (a connect
     * that ran and failed, one refused while a connect of its own batch
     * still ran, or one left by an earlier process) waits for the card's
     * Connect (#225 R1-F1, #312 R2-M1).
     */
    private val _connectOwed = MutableStateFlow<String?>(null)
    val connectOwed: StateFlow<String?> = _connectOwed.asStateFlow()

    /**
     * The batch id of an untracked record whose call can never be mined:
     * its receipt isn't there while the paying account's nonce went to
     * another transaction, seen that way by two reads at least
     * [confirmAfterMs] apart ([supersededSeen]). Only then is Dismiss safe
     * to advise.
     */
    private val _superseded = MutableStateFlow<String?>(null)
    val superseded: StateFlow<String?> = _superseded.asStateFlow()

    /**
     * The batch id and time ([now]) of the first read that found its call
     * superseded, not yet confirmed. The count and the receipt come from
     * separate router reads that may be answered at different heights: a
     * count from nodes that already have the block with the call, and a
     * receipt from nodes one block behind, look exactly like a superseded
     * call. A node that far behind catches up within seconds, so the
     * verdict waits for a second read [confirmAfterMs] later that agrees.
     */
    private var supersededSeen: Pair<String, Long>? = null

    /**
     * The batch id, receipt outcome and time ([now]) of an untrusted read
     * that found its call mined, not yet confirmed. Either way one public
     * RPC's word (a buggy node, or one serving a block later reorged away)
     * isn't enough to act on: dropping a reverted record loses the only
     * copy of the batch id (#225 R5-F1), and marking a call mined stops
     * the lookups for good, so a call that never lands would be shown as a
     * mined stamp forever (#225 R6-F1). A trusted read acts at once, an
     * untrusted one only once a second read at least [confirmAfterMs]
     * later agrees.
     */
    private var receiptSeen: ReceiptSeen? = null

    private data class ReceiptSeen(val batchId: String, val reverted: Boolean, val at: Long)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Follows [sends] (every status, none conflated away; null once the
     * sender stopped following its send) and the node's own spends.
     * [current]: the sender's send once its journal has been read back —
     * a record left going out with no such send was given up on in an
     * earlier process.
     */
    fun start(sends: Flow<SendStatus?>, current: (suspend () -> SendStatus?)? = null) {
        scope.launch { sends.collect { if (it == null) untrack() else noteSend(it) } }
        if (current != null) {
            scope.launch {
                val send = current()
                untrack { it.batchId != send?.quote?.request?.dapp?.swarm?.batchId }
            }
        }
        if (chain != null) {
            // While the wallet doesn't follow an unmined call, look it up on chain now and then:
            // every [checkEveryMs] at first, backing off to [checkAtMostEveryMs], and not while
            // it's known it can never be mined ([superseded]) — only a Connect reads then. A read
            // that withdraws that verdict (a Connect's, answered at another height) starts the
            // lookups over, so the card's "keeps checking" stays true (#225 R4-F1).
            scope.launch {
                _pending.collectLatest { p ->
                    if (p != null && !p.mined && !p.tracked && p.hash != null) {
                        var wait = checkEveryMs
                        while (true) {
                            checkChain()
                            if (_superseded.value == p.batchId) {
                                // The read that withdrew it has just been made: the next one after [checkEveryMs].
                                _superseded.first { it != p.batchId }
                                wait = checkEveryMs
                            }
                            val unconfirmed = synchronized(this@SwarmFunding) { supersededSeen?.first ?: receiptSeen?.batchId }
                            if (unconfirmed == p.batchId) {
                                // Superseded, mined or reverted at one read: confirm it as soon as it can be.
                                delay(confirmAfterMs)
                            } else {
                                delay(wait)
                                wait = minOf(wait * 2, checkAtMostEveryMs)
                            }
                        }
                    }
                }
            }
        }
        scope.launch {
            // A connect refused while the node was held by other work: start it once nothing does.
            combine(connectFree, _connectOwed) { free, owed -> owed.takeIf { free } }.collect { owed ->
                if (owed != null) {
                    if (_pending.value?.takeIf { it.mined }?.batchId != owed) {
                        _connectOwed.compareAndSet(owed, null)
                    } else if (connectNow()) {
                        Log.i(TAG, "connecting the mined stamp now that nothing holds the node up")
                    }
                }
            }
        }
        scope.launch {
            spends.collect { s ->
                // The connect this object started ended: its own Done or Failed, or any other
                // state once its Running was seen — a state read before it started (the
                // collector's first Idle, late) doesn't end it.
                synchronized(this@SwarmFunding) {
                    connectRunning?.let { ours ->
                        connectRunning = when {
                            s.connectBatch() != ours.batchId -> ours.takeUnless { it.seen }
                            s is StampClient.Spend.Running -> ours.copy(seen = true)
                            else -> null
                        }
                    }
                }
                // Connected: nothing more to do for it.
                if (s is StampClient.Spend.Done && s.kind == StampClient.Kind.Connect) {
                    update { p -> p?.takeUnless { it.batchId == s.batchId } }
                }
            }
        }
    }

    /**
     * What the wallet's send [status] means for the stamp: a funding call
     * going out is recorded; mined, its batch is connected; one that
     * certainly didn't go out, or reverted, bought nothing and is dropped.
     */
    internal fun noteSend(status: SendStatus) {
        val label = status.quote.request.dapp?.swarm ?: return
        var mined = false
        update { current ->
            val same = current?.takeIf { it.batchId == label.batchId }
            // Only a send still going out starts a record: a finished one with none
            // was already connected or forgotten (the sender replays its last status).
            val base = (
                same ?: Pending(label.node, label.batchId, label.depth, label.days, null, mined = false)
                    .takeIf { status.inFlight || status.stage == SendStatus.Stage.Unconfirmed }
                )?.copy(from = status.quote.request.from.address, nonce = status.quote.tx.nonce)
            val stage = status.stage
            when {
                base == null -> current
                stage is SendStatus.Stage.Confirmed ->
                    base.copy(hash = status.hash ?: base.hash, mined = true, tracked = true).also { mined = !base.mined }
                // Mined but failed, or certainly never sent: nothing was bought.
                stage is SendStatus.Stage.Reverted || (stage is SendStatus.Stage.Failed && !stage.mayHaveGone) ->
                    if (same != null) null else current
                else -> base.copy(hash = status.hash ?: base.hash, tracked = true)
            }
        }
        if (mined) {
            Log.i(TAG, "the node's funding was mined; connecting its stamp")
            connectMined()
        }
    }

    /**
     * The wallet no longer follows the stamp's call (and [which] says it's
     * this one's): unless it's mined, it's kept as [Pending.tracked] false
     * — it may still land, so it's offered for connecting, and dismissing.
     */
    internal fun untrack(which: (Pending) -> Boolean = { true }) =
        update { p -> if (p != null && !p.mined && p.tracked && which(p)) p.copy(tracked = false) else p }

    /**
     * Asks the node to connect the mined stamp (or one the wallet stopped
     * following, which may have been mined). False if there's none, or
     * another spend is running.
     */
    fun connectNow(): Boolean = connectAttempt() > 0

    /**
     * Each connect asked for, numbered in order: the next one's number
     * ([connectsAsked]), and the highest number that started ([connectStarted]).
     * Both under this object's lock.
     */
    private var connectsAsked = 0L
    private var connectStarted = 0L

    /**
     * The batch of the connect this object last started, until [spends]
     * shows it ended. Under this object's lock. A connect of a batch
     * refused while one of that same batch runs owes nothing: that one
     * connects it, or fails and leaves the card's Connect — whatever
     * order the two were asked in (#312 R2-M1).
     */
    private var connectRunning: Started? = null

    /** A connect of [batchId] this object started; [seen]: [spends] showed it Running. */
    private data class Started(val batchId: String, val seen: Boolean = false)

    /** The batch a connect's state is for; null for any other state. */
    private fun StampClient.Spend.connectBatch(): String? = when (this) {
        is StampClient.Spend.Running -> batchId.takeIf { kind == StampClient.Kind.Connect }
        is StampClient.Spend.Done -> batchId.takeIf { kind == StampClient.Kind.Connect }
        is StampClient.Spend.Failed -> batchId.takeIf { kind == StampClient.Kind.Connect }
        else -> null
    }

    /** [connectNow], answering the attempt's number if it started, else minus it (0: nothing to connect). */
    private fun connectAttempt(): Long {
        val p = _pending.value?.takeIf { it.mined || !it.tracked } ?: return 0
        val ticket = synchronized(this) { ++connectsAsked }
        if (!p.mined) scope.launch { checkChain() }
        if (!connect(p.batchId)) return -ticket
        synchronized(this) {
            connectStarted = maxOf(connectStarted, ticket)
            connectRunning = Started(p.batchId)
            _connectOwed.compareAndSet(p.batchId, null)
        }
        return ticket
    }

    /**
     * The record's call was just seen mined: connect its batch now, or —
     * refused, since other work holds the node — as soon as it's free
     * ([connectOwed]).
     */
    private fun connectMined() {
        val batchId = _pending.value?.takeIf { it.mined }?.batchId ?: return
        // Owed only once refused: owed before asking, the collector that
        // retries an owed connect could see it (the node free) and start a
        // second connect of the batch next to this one's (#310).
        val ticket = connectAttempt()
        if (ticket > 0) return
        // Checked with the write, under the lock [update] and a started connect clear it
        // under: a Dismiss, or a Connect that started, since the refusal owes nothing (#312 R1-M1);
        // nor does one refused because a connect of this batch, asked for earlier, still runs (#312 R2-M1).
        val owed = synchronized(this) {
            (
                ticket < 0 && connectStarted < -ticket && connectRunning?.batchId != batchId &&
                    _pending.value?.takeIf { it.mined }?.batchId == batchId
                )
                .also { if (it) _connectOwed.value = batchId }
        }
        if (owed) Log.i(TAG, "the node is busy; connecting the stamp once it's free")
    }

    /**
     * Looks an untracked, unmined record's call up by its hash: mined, its
     * batch is recorded as bought and connected; reverted, it bought
     * nothing and is dropped — either on a trusted read, or on two
     * untrusted ones [confirmAfterMs] apart ([receiptSeen]); not there,
     * it's still waiting — unless the
     * paying account's nonce was used by another transaction, when it can
     * never be mined ([superseded]). A read that fails changes nothing.
     */
    internal suspend fun checkChain() {
        val c = chain ?: return
        val p = _pending.value?.takeIf { !it.mined && !it.tracked } ?: return
        val hash = p.hash ?: return
        try {
            // The count first: read after the receipt, it could count this very call mined in between.
            val nonceUsed = if (p.from != null && p.nonce != null) c.minedCount(p.from) > p.nonce else false
            val read = c.receipt(hash)
            val outcome = read?.json?.let(WalletSender::outcomeOf)
            val settled = when (outcome) {
                is SendStatus.Stage.Confirmed, is SendStatus.Stage.Reverted ->
                    confirmReceipt(p.batchId, outcome is SendStatus.Stage.Reverted, read?.trusted == true)
                else -> {
                    synchronized(this) { receiptSeen = null }
                    false
                }
            }
            val dropIt = outcome is SendStatus.Stage.Reverted && settled
            var connectIt = false
            when (outcome) {
                is SendStatus.Stage.Confirmed -> if (settled) update { cur ->
                    if (cur?.batchId == p.batchId && !cur.mined) {
                        connectIt = true
                        cur.copy(mined = true)
                    } else {
                        cur
                    }
                }
                is SendStatus.Stage.Reverted ->
                    if (dropIt) update { cur -> cur?.takeUnless { it.batchId == p.batchId && !it.mined } }
                else -> {}
            }
            noteSuperseded(p.batchId, outcome == null && nonceUsed)
            if (connectIt) {
                Log.i(TAG, "the funding the wallet stopped following was mined; connecting its stamp")
                connectMined()
            } else if (dropIt) {
                Log.i(TAG, "the funding the wallet stopped following reverted; nothing was bought")
            } else if (outcome is SendStatus.Stage.Reverted) {
                Log.i(TAG, "an unverified read says the funding reverted; confirming before dropping it")
            } else if (outcome is SendStatus.Stage.Confirmed && !settled) {
                Log.i(TAG, "an unverified read says the funding was mined; confirming before connecting it")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.i(TAG, "couldn't look the funding up on chain (${e.javaClass.simpleName})")
        }
    }

    /**
     * One read's verdict on whether [batchId]'s call is superseded: it's
     * [superseded] only once a read at least [confirmAfterMs] after the
     * first such one agrees ([supersededSeen]); any read that doesn't
     * starts over.
     */
    private fun noteSuperseded(batchId: String, seen: Boolean) = synchronized(this) {
        if (!seen) {
            supersededSeen = null
            _superseded.value = null
            return@synchronized
        }
        val t = now()
        val first = supersededSeen?.takeIf { it.first == batchId }
        when {
            first == null -> {
                supersededSeen = batchId to t
                _superseded.value = null
            }
            t - first.second >= confirmAfterMs -> _superseded.value = batchId
        }
    }

    /**
     * Whether a read finding [batchId]'s call mined, [reverted] or not,
     * [trusted] or not, settles it ([receiptSeen]): a trusted one does at
     * once, an untrusted one once an earlier untrusted one [confirmAfterMs]
     * before found the same. A read with any other outcome starts over.
     */
    private fun confirmReceipt(batchId: String, reverted: Boolean, trusted: Boolean): Boolean = synchronized(this) {
        val t = now()
        val first = receiptSeen?.takeIf { it.batchId == batchId && it.reverted == reverted }
        when {
            trusted || (first != null && t - first.at >= confirmAfterMs) -> {
                receiptSeen = null
                true
            }
            first == null -> {
                receiptSeen = ReceiptSeen(batchId, reverted, t)
                false
            }
            else -> false
        }
    }

    /** Forget the stamp (the user dismissed it); the batch stays on chain, owned by the node. */
    fun forget() = update { null }

    private fun update(f: (Pending?) -> Pending?) {
        synchronized(this) {
            val next = f(_pending.value)
            if (next == _pending.value) return
            // Saved before it's published: whoever sees the new record can count on it being on disk
            // (a test's next instance reading the file raced a late save here, #225 R5-F3).
            save(next)
            _pending.value = next
            _connectOwed.value?.let { if (it != next?.batchId) _connectOwed.compareAndSet(it, null) }
        }
    }

    private fun load(): Pending? = try {
        if (!file.exists()) {
            null
        } else {
            val o = JSONObject(file.readText())
            Pending(
                o.getString("node"), o.getString("batchId"), o.getInt("depth"), o.getLong("days"),
                o.optString("hash").takeIf { it.isNotEmpty() }, o.getBoolean("mined"),
                tracked = !o.optBoolean("untracked", false),
                from = o.optString("from").takeIf { it.isNotEmpty() },
                nonce = o.optString("nonce").takeIf { it.isNotEmpty() }?.let(::BigInteger),
            ).takeIf { normalizeBatchId(it.batchId) == it.batchId }
        }
    } catch (e: Exception) {
        Log.w(TAG, "couldn't read the pending stamp (${e.javaClass.simpleName})")
        null
    }

    private fun save(p: Pending?) {
        try {
            if (p == null) {
                file.delete()
                return
            }
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(
                JSONObject().put("node", p.node).put("batchId", p.batchId).put("depth", p.depth).put("days", p.days)
                    .put("hash", p.hash ?: "").put("mined", p.mined)
                    .put("untracked", !p.tracked).put("from", p.from ?: "").put("nonce", p.nonce?.toString() ?: "")
                    .toString(),
            )
            if (!tmp.renameTo(file)) tmp.delete()
        } catch (e: Exception) {
            Log.w(TAG, "couldn't save the pending stamp (${e.javaClass.simpleName})")
        }
    }

    companion object {
        private const val TAG = "SwarmFunding"
        private const val CHECK_EVERY_MS = 30_000L
        private const val CHECK_AT_MOST_EVERY_MS = 30 * 60_000L
        private const val CONFIRM_AFTER_MS = 30_000L

        @Volatile
        private var instance: SwarmFunding? = null

        private fun file(context: Context) = File(context.applicationContext.noBackupFilesDir, "swarm/funding.json")

        /** Touches storage and starts the wallet's sender: call it off the main thread ([load]). */
        fun get(context: Context): SwarmFunding = instance ?: synchronized(this) {
            instance ?: SwarmFunding(file(context), chain = rpcReader(context)).also {
                instance = it
                val sender = WalletSender.get(context)
                it.start(sender.changes) {
                    sender.awaitRestored()
                    sender.status.value
                }
            }
        }

        /** The one already built, if any: a page's first frame, before [load] answers. */
        fun loaded(): SwarmFunding? = instance

        /** [get], read on the IO dispatcher: for a page, which runs on the main thread. */
        suspend fun load(context: Context): SwarmFunding =
            instance ?: kotlinx.coroutines.withContext(Dispatchers.IO) { get(context) }

        private fun rpcReader(context: Context): ChainReader {
            val rpc = WalletRpc(ChainDataRouter.get(context))
            return object : ChainReader {
                override suspend fun receipt(hash: String) = rpc.receipt(SwarmFunder.CHAIN_ID, hash)
                    .let { r -> r.value?.let { SwarmFunding.Receipt(it, chainReadTrusted(r.trust)) } }
                override suspend fun minedCount(address: String) =
                    rpc.transactionCount(SwarmFunder.CHAIN_ID, address, "latest").value
            }
        }

        /**
         * At each app launch, off the main thread: a stamp left bought or
         * going out by the last process is followed (and connected once
         * mined) now rather than at the next visit to publish setup. With
         * no record there is nothing to follow, and nothing is started — a
         * funding send starts from a page that calls [get] itself.
         */
        fun resumeAtLaunch(context: Context) {
            if (file(context).exists()) get(context)
        }
    }
}
