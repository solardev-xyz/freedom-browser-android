package baby.freedom.mobile.wallet

import android.util.Log
import baby.freedom.mobile.chains.rpc.ChainDataRouter
import baby.freedom.mobile.chains.rpc.ChainRpcException
import baby.freedom.mobile.chains.rpc.WalletRpc
import java.io.File
import java.io.FileOutputStream
import java.math.BigInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * One transaction the wallet sent (#109), as its history lists it. The
 * chain's and token's names are kept as they were when it was sent, so
 * the entry still reads right if the chain is later removed from
 * Settings → Chains. Nothing here is secret: it's all public on chain
 * once the transaction lands.
 */
data class TxRecord(
    val hash: String,
    val chainId: Long,
    val chainName: String,
    val chainSymbol: String,
    val chainDecimals: Int,
    val explorerUrl: String?,
    val from: String,
    /** The recipient: for a token transfer the person paid, not the token's contract. */
    val to: String,
    /** The name [to] was sent to by, if the user typed one (#277): a label only. */
    val toName: String? = null,
    val tokenAddress: String?,
    val tokenSymbol: String,
    val tokenDecimals: Int,
    val amount: BigInteger,
    val nonce: BigInteger,
    val sentAt: Long,
    val status: Status,
    val block: Long? = null,
    val feePaid: BigInteger? = null,
    /** When [status] stopped being [Status.PENDING]. */
    val settledAt: Long? = null,
    /**
     * [to] is someone the user paid from Send (#422): not the contract a
     * composed transaction — a dApp's, a Safe's, a stamp purchase's —
     * calls. Only these are suggested as recipients again. False for a
     * record kept before this was noted: which kind it was isn't known.
     */
    val payee: Boolean = false,
) {
    enum class Status(internal val rank: Int) {
        /** Sent (or may have been), no receipt yet. */
        PENDING(0),

        /** No receipt, and the account's nonce went past it: another transaction took its place. */
        REPLACED(1),

        /**
         * No receipt, and the account's nonce went past it, but it was sent
         * too long ago to tell: a node may have pruned the receipt of one
         * mined back then, so it was either mined or replaced.
         */
        UNKNOWN(1),

        /** Mined, and it did what it was sent to do. */
        CONFIRMED(2),

        /** Mined, but the transfer itself failed; the fee was still paid. */
        FAILED(2),
    }

    val pending: Boolean get() = status == Status.PENDING
}

/** Where the history is kept between runs. */
interface TxHistoryStore {
    /** Saves [records]; false if they couldn't be written. */
    fun save(records: List<TxRecord>): Boolean

    /** What was saved last; empty if nothing (or nothing readable) was. */
    fun load(): List<TxRecord>

    /** Keeps nothing (tests that aren't about restarts). */
    object None : TxHistoryStore {
        override fun save(records: List<TxRecord>) = true
        override fun load(): List<TxRecord> = emptyList()
    }
}

/**
 * [TxHistoryStore] in one JSON file, written whole to a temporary file
 * and renamed over it. Unlike [FileSendJournal] it isn't fsynced: the
 * history only reports what the chain already knows, and a record lost
 * to a power cut costs nothing but a line in the list. Blocks on
 * storage: never call it on the main thread.
 */
class FileTxHistoryStore(private val file: File) : TxHistoryStore {
    override fun save(records: List<TxRecord>): Boolean = try {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        if (records.isEmpty()) {
            // A save cut short (the process died before the rename) leaves
            // the old history in the temporary file; an empty history (a
            // removed wallet) has to take that with it too.
            (!tmp.exists() or tmp.delete()) and (!file.exists() || file.delete())
        } else {
            file.parentFile?.mkdirs()
            FileOutputStream(tmp).use { it.write(TxHistoryCodec.encode(records).toString().toByteArray()) }
            tmp.renameTo(file) || run {
                tmp.delete()
                false
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "couldn't save the transaction history: ${e.javaClass.simpleName}")
        false
    }

    override fun load(): List<TxRecord> = try {
        if (file.exists()) TxHistoryCodec.decode(JSONObject(file.readText())) else emptyList()
    } catch (e: Exception) {
        Log.w(TAG, "unreadable transaction history: ${e.javaClass.simpleName}")
        emptyList()
    } catch (e: StackOverflowError) {
        emptyList()
    }

    private companion object {
        const val TAG = "WalletHistory"
    }
}

/** The history file's JSON. A record that can't be read back is dropped; the others still hold. */
internal object TxHistoryCodec {
    fun encode(records: List<TxRecord>): JSONObject = JSONObject()
        .put("version", 1)
        .put("records", JSONArray().apply { records.forEach { put(record(it)) } })

    fun decode(o: JSONObject): List<TxRecord> {
        if (o.optInt("version") != 1) return emptyList()
        val a = o.optJSONArray("records") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i -> runCatching { record(a.getJSONObject(i)) }.getOrNull() }
            .distinctBy { it.hash.lowercase() }
    }

    private fun record(r: TxRecord): JSONObject = JSONObject()
        .put("hash", r.hash)
        .put("chainId", r.chainId)
        .put("chainName", r.chainName)
        .put("chainSymbol", r.chainSymbol)
        .put("chainDecimals", r.chainDecimals)
        .put("explorerUrl", r.explorerUrl ?: JSONObject.NULL)
        .put("from", r.from)
        .put("to", r.to)
        .put("toName", r.toName ?: JSONObject.NULL)
        .put("tokenAddress", r.tokenAddress ?: JSONObject.NULL)
        .put("tokenSymbol", r.tokenSymbol)
        .put("tokenDecimals", r.tokenDecimals)
        .put("amount", r.amount.toString())
        .put("nonce", r.nonce.toString())
        .put("sentAt", r.sentAt)
        .put("status", r.status.name)
        .put("block", r.block ?: JSONObject.NULL)
        .put("feePaid", r.feePaid?.toString() ?: JSONObject.NULL)
        .put("settledAt", r.settledAt ?: JSONObject.NULL)
        .put("payee", r.payee)

    private fun record(o: JSONObject): TxRecord {
        val hash = o.getString("hash")
        require(TX_HASH.matches(hash))
        return TxRecord(
            hash = hash,
            chainId = o.getLong("chainId"),
            chainName = o.getString("chainName"),
            chainSymbol = o.getString("chainSymbol"),
            chainDecimals = o.getInt("chainDecimals"),
            explorerUrl = o.stringOrNull("explorerUrl"),
            from = o.getString("from"),
            to = o.getString("to"),
            toName = o.stringOrNull("toName"),
            tokenAddress = o.stringOrNull("tokenAddress"),
            tokenSymbol = o.getString("tokenSymbol"),
            tokenDecimals = o.getInt("tokenDecimals"),
            amount = BigInteger(o.getString("amount")),
            nonce = BigInteger(o.getString("nonce")),
            sentAt = o.getLong("sentAt"),
            status = TxRecord.Status.valueOf(o.getString("status")),
            block = if (o.isNull("block")) null else o.getLong("block"),
            feePaid = o.stringOrNull("feePaid")?.let(::BigInteger),
            settledAt = if (o.isNull("settledAt")) null else o.getLong("settledAt"),
            payee = o.optBoolean("payee", false),
        )
    }

    private fun JSONObject.stringOrNull(name: String): String? = if (isNull(name)) null else getString(name)

    private val TX_HASH = Regex("^0x[0-9a-fA-F]{64}$")
}

/**
 * The wallet's transaction history (#109), after desktop's
 * `tx-recorder.js` / `payment-history.js`: every send that went out, or
 * may have, with where it stands — pending, confirmed, failed on chain,
 * or replaced (another transaction used its nonce).
 *
 * [WalletSender] reports each send as it moves ([note]); a send it's no
 * longer following (Stop tracking, or one that got no receipt in time)
 * stays pending here, and [refresh] settles it from the chain later,
 * the way desktop's `repollPending` does at boot.
 *
 * A status only ever moves forward (pending → replaced → confirmed or
 * failed): a late report can't undo what a receipt already showed.
 */
class TxHistory internal constructor(
    private val rpc: WalletRpc,
    private val scope: CoroutineScope,
    private val store: TxHistoryStore = TxHistoryStore.None,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _records = MutableStateFlow<List<TxRecord>>(emptyList())

    /** Every record, newest first. */
    val records: StateFlow<List<TxRecord>> = _records.asStateFlow()

    /** Held while the file is written; taken before this object's lock, never under it. */
    private val writing = Any()

    /** Bumped by [wipe]: a [refresh] that began before it doesn't write back into the emptied history. */
    private var generation = 0

    private var loaded = false

    /** [wipe] came before the file was read: what it holds is dropped. */
    private var wipedBeforeLoad = false

    /** A write was asked for before the file was read: the load writes once it has merged. */
    private var writeAfterLoad = false

    private val refreshing = Mutex()

    /**
     * Done once the file has been read and merged (or the read will never
     * run, its scope gone). [refresh] waits for it: a refresh begun first
     * would see an empty list, answer "nothing due", and a caller polling
     * on that answer would never come back for the records the file brings.
     */
    private val fileRead = CompletableDeferred<Unit>()

    init {
        scope.launch(Dispatchers.IO) {
            val saved = store.load()
            val write = synchronized(this@TxHistory) {
                loaded = true
                if (!wipedBeforeLoad) {
                    // Noted meanwhile (a send restored at launch): the further-on status
                    // of the two wins, and it was sent when the file first had it.
                    val noted = _records.value.associateBy { it.hash.lowercase() }
                    val fromFile = saved.map { old ->
                        val now = noted[old.hash.lowercase()] ?: return@map old
                        (if (old.status.rank > now.status.rank) old else now).copy(sentAt = minOf(old.sentAt, now.sentAt))
                    }
                    val have = saved.map { it.hash.lowercase() }.toSet()
                    _records.value = sorted(fromFile + _records.value.filter { it.hash.lowercase() !in have })
                }
                writeAfterLoad
            }
            fileRead.complete(Unit)
            if (write) persistNow()
        }.invokeOnCompletion { fileRead.complete(Unit) }
    }

    /** Until the file has been read and merged (off the main thread, from [init]). */
    internal suspend fun awaitLoaded() = fileRead.await()

    /**
     * [status] as [WalletSender] now has it. A send is recorded once it
     * went out or may have (pending, not mined in time, or a broadcast
     * whose outcome is unknown); signing, broadcasting and a send that
     * certainly wasn't sent leave the history as it is. Cheap and never
     * blocks on storage: callable under the sender's lock, from any thread.
     */
    fun note(status: SendStatus) {
        val hash = status.hash ?: return
        val (next, block, fee) = when (val s = status.stage) {
            SendStatus.Stage.Pending, SendStatus.Stage.Unconfirmed -> Triple(TxRecord.Status.PENDING, null, null)
            is SendStatus.Stage.Failed -> if (s.mayHaveGone) Triple(TxRecord.Status.PENDING, null, null) else return
            is SendStatus.Stage.Confirmed -> Triple(TxRecord.Status.CONFIRMED, s.block, s.feePaid)
            is SendStatus.Stage.Reverted -> Triple(TxRecord.Status.FAILED, s.block, s.feePaid)
            SendStatus.Stage.Signing, SendStatus.Stage.Broadcasting -> return
        }
        val changed = synchronized(this) {
            val existing = find(hash)
            if (existing == null) {
                val base = recordOf(status, hash)
                _records.value = sorted(_records.value + (advance(base, next, block, fee) ?: base))
                true
            } else {
                val updated = advance(existing, next, block, fee) ?: return@synchronized false
                replace(existing, updated)
                true
            }
        }
        if (changed) persistLater()
    }

    /**
     * Reads the chain for every pending record (and a replaced one
     * recently judged, in case the node that judged it was behind): a
     * receipt settles it; with none, an account nonce already past its
     * own marks it replaced. A record that can't be read (no RPC
     * answering, the chain gone from Settings) stays as it was.
     *
     * True while a later refresh could still tell something new: a record
     * is still due (pending, or replaced within [RECHECK_REPLACED_MS]) on
     * a chain that's still set up. A pending record whose chain was
     * removed from Settings doesn't count — nothing can be read for it
     * until the chain comes back and the page is refreshed.
     *
     * Waits for the file to be read first, so the answer covers what it holds.
     */
    suspend fun refresh(): Boolean {
        fileRead.await()
        return refreshLoaded()
    }

    private suspend fun refreshLoaded(): Boolean = refreshing.withLock {
        val (mine, due) = synchronized(this) { generation to _records.value.filter { due(it, clock()) } }
        val unknownChains = mutableSetOf<Long>()
        for (r in due) {
            val found = try {
                settle(r)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ChainRpcException.UnknownChain) {
                unknownChains += r.chainId
                null
            } catch (e: Exception) {
                null
            } ?: continue
            val changed = synchronized(this) {
                if (generation != mine) return@withLock false
                val existing = find(r.hash) ?: return@synchronized false
                val updated = advance(existing, found.status, found.block, found.feePaid) ?: return@synchronized false
                replace(existing, updated)
                true
            }
            if (changed) persistLater()
        }
        synchronized(this) {
            val now = clock()
            generation == mine && _records.value.any { due(it, now) && it.chainId !in unknownChains }
        }
    }

    /** Whether a refresh at [now] reads [r] from the chain. */
    private fun due(r: TxRecord, now: Long): Boolean =
        r.pending || (r.status == TxRecord.Status.REPLACED && now - (r.settledAt ?: 0) in 0 until RECHECK_REPLACED_MS)

    /** What the chain says about [r] now, or null if it says nothing new. */
    private suspend fun settle(r: TxRecord): TxRecord? {
        rpc.receipt(r.chainId, r.hash).value?.let { receipt ->
            return outcome(r, receipt)
        }
        if (r.status != TxRecord.Status.PENDING) return null
        // Mined count past its nonce: that nonce is used. Ask for the receipt once
        // more after that answer, so a receipt that just landed isn't read as replaced.
        if (rpc.transactionCount(r.chainId, r.from, "latest").value <= r.nonce) return null
        rpc.receipt(r.chainId, r.hash).value?.let { receipt -> return outcome(r, receipt) }
        // A node that prunes its transaction index (geth's txlookuplimit and the like)
        // answers "no receipt" for a transaction mined long ago, too: past that age a
        // missing receipt doesn't tell this one from its replacement.
        val age = clock() - r.sentAt
        return r.copy(status = if (age in 0 until REPLACED_JUDGE_MAX_AGE_MS) TxRecord.Status.REPLACED else TxRecord.Status.UNKNOWN)
    }

    private fun outcome(r: TxRecord, receipt: JSONObject): TxRecord? = when (val s = WalletSender.outcomeOf(receipt)) {
        is SendStatus.Stage.Confirmed -> r.copy(status = TxRecord.Status.CONFIRMED, block = s.block, feePaid = s.feePaid)
        is SendStatus.Stage.Reverted -> r.copy(status = TxRecord.Status.FAILED, block = s.block, feePaid = s.feePaid)
        else -> null
    }

    /**
     * Forgets every record, on disk too: the wallet they came from was
     * removed. Doesn't block: the file is rewritten by a write launched
     * off the calling thread. [wipeNow] is the one that has the file gone
     * before it returns.
     */
    fun wipe() {
        forget()
        persistLater()
    }

    /**
     * [wipe], with the file written (emptied: deleted) before this
     * returns, so a process death right after can't leave the removed
     * wallet's records on disk to come back with its phrase. Blocks on
     * storage: never call it on the main thread. False if the file
     * couldn't be written.
     */
    fun wipeNow(): Boolean = synchronized(writing) {
        forget()
        // Written even before the file has been read back: what it had is dropped
        // anyway (wipedBeforeLoad), and whatever was noted since goes with it.
        store.save(synchronized(this) { _records.value })
    }

    private fun forget() = synchronized(this) {
        generation++
        if (!loaded) wipedBeforeLoad = true
        _records.value = emptyList()
    }

    /**
     * [r] moved on to [status] (with its receipt's [block] and [fee]), or
     * null when that's no move forward. Under this object's lock.
     */
    private fun advance(r: TxRecord, status: TxRecord.Status, block: Long?, fee: BigInteger?): TxRecord? = when {
        status.rank > r.status.rank ->
            r.copy(status = status, block = block ?: r.block, feePaid = fee ?: r.feePaid, settledAt = clock())
        // The same outcome again: only a receipt's details that weren't there yet are news.
        status == r.status && r.block == null && block != null -> r.copy(block = block, feePaid = fee ?: r.feePaid)
        else -> null
    }

    private fun find(hash: String): TxRecord? = _records.value.firstOrNull { it.hash.equals(hash, ignoreCase = true) }

    private fun replace(old: TxRecord, new: TxRecord) {
        _records.value = _records.value.map { if (it === old) new else it }
    }

    private fun recordOf(status: SendStatus, hash: String): TxRecord {
        val q = status.quote
        val r = q.request
        return TxRecord(
            hash = hash,
            chainId = r.chain.id,
            chainName = r.chain.name,
            chainSymbol = r.chain.symbol,
            chainDecimals = r.chain.decimals,
            explorerUrl = r.chain.explorerUrl,
            from = r.from.address,
            to = r.to,
            toName = r.toName,
            tokenAddress = r.token.address,
            tokenSymbol = r.token.symbol,
            tokenDecimals = r.token.decimals,
            amount = r.amount,
            nonce = q.tx.nonce,
            sentAt = clock(),
            status = TxRecord.Status.PENDING,
            payee = r.dapp == null,
        )
    }

    /** Newest first, at most [MAX_RECORDS]: the oldest settled ones go first, a pending one never. */
    private fun sorted(records: List<TxRecord>): List<TxRecord> {
        val newest = records.sortedByDescending { it.sentAt }
        if (newest.size <= MAX_RECORDS) return newest
        val drop = newest.filterNot { it.pending }.takeLast(newest.size - MAX_RECORDS).toSet()
        return newest.filterNot { it in drop }
    }

    /** Writes the history as it is now, on the calling thread (never the main one); the last write is the latest state. */
    internal fun persistNow(): Boolean = synchronized(writing) {
        val snapshot = synchronized(this) {
            // Not read back yet: the load merges and writes once it has.
            if (!loaded) writeAfterLoad = true
            _records.value.takeIf { loaded }
        } ?: return true
        store.save(snapshot)
    }

    private fun persistLater() {
        scope.launch(Dispatchers.IO) { persistNow() }
    }

    companion object {
        /** More than a page ever shows; enough to cover months of use. */
        const val MAX_RECORDS = 200

        /** How long after it's judged replaced a record's receipt is still looked for. */
        const val RECHECK_REPLACED_MS = 10 * 60_000L

        /**
         * How long after it was sent a missing receipt (with its nonce used)
         * still reads as [TxRecord.Status.REPLACED]. Well inside any node's
         * transaction index; the history is refreshed each time the wallet
         * page opens, so a send is normally settled long before this. Past
         * it, [TxRecord.Status.UNKNOWN].
         */
        const val REPLACED_JUDGE_MAX_AGE_MS = 7 * 24 * 60 * 60_000L

        @Volatile
        private var instance: TxHistory? = null

        fun get(context: android.content.Context): TxHistory = instance ?: synchronized(this) {
            instance ?: TxHistory(
                rpc = WalletRpc(ChainDataRouter.get(context.applicationContext)),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                store = FileTxHistoryStore(File(context.applicationContext.noBackupFilesDir, "wallet/history.json")),
            ).also { instance = it }
        }
    }
}
