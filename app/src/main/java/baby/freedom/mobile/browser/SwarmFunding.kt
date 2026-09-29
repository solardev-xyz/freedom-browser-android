package baby.freedom.mobile.browser

import android.content.Context
import android.util.Log
import baby.freedom.mobile.wallet.SendStatus
import baby.freedom.mobile.wallet.WalletSender
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * fund page show it, with a Connect button).
 */
internal class SwarmFunding(
    private val file: File,
    private val connect: (String) -> Boolean = StampClient::connect,
    private val spends: Flow<StampClient.Spend> = StampClient.spend,
) {
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
    )

    private val _pending = MutableStateFlow(load())
    val pending: StateFlow<Pending?> = _pending.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Follows [sends] (every status, none conflated away) and the node's own spends. */
    fun start(sends: Flow<SendStatus?>) {
        scope.launch { sends.collect { it?.let(::noteSend) } }
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
            val base = same ?: Pending(label.node, label.batchId, label.depth, label.days, null, mined = false)
                .takeIf { status.inFlight || status.stage == SendStatus.Stage.Unconfirmed }
            val stage = status.stage
            when {
                base == null -> current
                stage is SendStatus.Stage.Confirmed -> base.copy(hash = status.hash ?: base.hash, mined = true).also { mined = !base.mined }
                // Mined but failed, or certainly never sent: nothing was bought.
                stage is SendStatus.Stage.Reverted || (stage is SendStatus.Stage.Failed && !stage.mayHaveGone) ->
                    if (same != null) null else current
                else -> base.copy(hash = status.hash ?: base.hash)
            }
        }
        if (mined) {
            Log.i(TAG, "the node's funding was mined; connecting its stamp")
            connectNow()
        }
    }

    /** Asks the node to connect the mined stamp. False if there's none, or another spend is running. */
    fun connectNow(): Boolean {
        val p = _pending.value?.takeIf { it.mined } ?: return false
        return connect(p.batchId)
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
                    .put("hash", p.hash ?: "").put("mined", p.mined).toString(),
            )
            if (!tmp.renameTo(file)) tmp.delete()
        } catch (e: Exception) {
            Log.w(TAG, "couldn't save the pending stamp (${e.javaClass.simpleName})")
        }
    }

    companion object {
        private const val TAG = "SwarmFunding"

        @Volatile
        private var instance: SwarmFunding? = null

        fun get(context: Context): SwarmFunding = instance ?: synchronized(this) {
            instance ?: SwarmFunding(File(context.applicationContext.noBackupFilesDir, "swarm/funding.json")).also {
                instance = it
                it.start(WalletSender.get(context).changes)
            }
        }
    }
}
