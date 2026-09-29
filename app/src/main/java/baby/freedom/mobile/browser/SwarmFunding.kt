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
) {
    /** Gnosis Chain reads for a call the wallet no longer follows. */
    interface ChainReader {
        /** The call's receipt, or null while it isn't mined (or is unknown). */
        suspend fun receipt(hash: String): JSONObject?

        /** How many of [address]'s transactions are mined: its next nonce at the latest block. */
        suspend fun minedCount(address: String): BigInteger
    }

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
     * The batch id of an untracked record whose call can never be mined:
     * its receipt isn't there while the paying account's nonce went to
     * another transaction. Only then is Dismiss safe to advise.
     */
    private val _superseded = MutableStateFlow<String?>(null)
    val superseded: StateFlow<String?> = _superseded.asStateFlow()
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
            // While the wallet doesn't follow an unmined call, look it up on chain now and then.
            scope.launch {
                _pending.collectLatest { p ->
                    if (p != null && !p.mined && !p.tracked && p.hash != null) {
                        while (true) {
                            checkChain()
                            delay(checkEveryMs)
                        }
                    }
                }
            }
        }
        scope.launch {
            spends.collect { s ->
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
            connectNow()
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
    fun connectNow(): Boolean {
        val p = _pending.value?.takeIf { it.mined || !it.tracked } ?: return false
        if (!p.mined) scope.launch { checkChain() }
        return connect(p.batchId)
    }

    /**
     * Looks an untracked, unmined record's call up by its hash: mined, its
     * batch is recorded as bought and connected; reverted, it bought
     * nothing and is dropped; not there, it's still waiting — unless the
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
            val outcome = c.receipt(hash)?.let(WalletSender::outcomeOf)
            var connectIt = false
            when (outcome) {
                is SendStatus.Stage.Confirmed -> update { cur ->
                    if (cur?.batchId == p.batchId && !cur.mined) {
                        connectIt = true
                        cur.copy(mined = true)
                    } else {
                        cur
                    }
                }
                is SendStatus.Stage.Reverted -> update { cur -> cur?.takeUnless { it.batchId == p.batchId && !it.mined } }
                else -> {}
            }
            _superseded.value = p.batchId.takeIf { outcome == null && nonceUsed }
            if (connectIt) {
                Log.i(TAG, "the funding the wallet stopped following was mined; connecting its stamp")
                connectNow()
            } else if (outcome is SendStatus.Stage.Reverted) {
                Log.i(TAG, "the funding the wallet stopped following reverted; nothing was bought")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.i(TAG, "couldn't look the funding up on chain (${e.javaClass.simpleName})")
        }
    }

    /** Forget the stamp (the user dismissed it); the batch stays on chain, owned by the node. */
    fun forget() = update { null }

    private fun update(f: (Pending?) -> Pending?) {
        synchronized(this) {
            val next = f(_pending.value)
            if (next == _pending.value) return
            _pending.value = next
            save(next)
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
                override suspend fun receipt(hash: String) = rpc.receipt(SwarmFunder.CHAIN_ID, hash).value
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
