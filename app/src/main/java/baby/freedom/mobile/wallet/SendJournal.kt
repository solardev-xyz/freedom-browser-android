package baby.freedom.mobile.wallet

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import baby.freedom.mobile.chains.Chain
import baby.freedom.mobile.chains.rpc.ChainSource
import baby.freedom.mobile.chains.rpc.ChainTrust
import baby.freedom.mobile.ens.Keccak256
import baby.freedom.mobile.ens.hexToBytes
import baby.freedom.mobile.ens.toHex
import java.io.File
import java.io.FileOutputStream
import java.math.BigInteger
import org.json.JSONArray
import org.json.JSONObject

/**
 * What must outlive the app's process for a send that may still land
 * (#105): the [unresolved][SendStatus.unresolved] send — its quote, stage
 * and signed bytes, which Try again resends — and the sends the user
 * stopped tracking ([NonceTracker.Abandoned]), whose nonce the next send
 * must reuse. Android kills a backgrounded app at will; without this the
 * next launch would show no send in progress and sign a fresh payment
 * one nonce past a copy still in a mempool, so both could be paid.
 *
 * Nothing here is secret: signed bytes are public once broadcast, and no
 * key is ever written. Everything is public on chain once it has landed.
 */
interface SendJournal {
    /** Saves [state]; false if it couldn't be written. */
    fun save(state: State): Boolean

    /** What was saved last, or null (none, or unreadable). */
    fun load(): State?

    class Send(val status: SendStatus, val signed: EthTransaction.Signed)

    /** [abandoned] is keyed by `chainId:address` in lower case, as [NonceTracker] keys it. */
    class State(val send: Send?, val abandoned: Map<String, NonceTracker.Abandoned>)

    /** Keeps nothing (tests that aren't about restarts). */
    object None : SendJournal {
        override fun save(state: State) = true
        override fun load(): State? = null
    }
}

/**
 * [SendJournal] in one JSON file, written whole to a temporary file and
 * renamed over it. The bytes are fsynced before the rename, and the
 * directory after it, and a save returns true only once both fsyncs
 * succeeded, so a save that returned true is on flash — the
 * signed bytes must be, before they're broadcast, or a power loss right
 * after a send could come back to no journal and a fresh send one nonce
 * past it. Blocks on storage: never call it on the main thread.
 */
class FileSendJournal internal constructor(
    private val file: File,
    private val dirSync: (File?) -> Boolean,
) : SendJournal {
    constructor(file: File) : this(file, { FileSendJournal.syncDirectory(it) })

    override fun save(state: SendJournal.State): Boolean = try {
        if (state.send == null && state.abandoned.isEmpty()) {
            // Nothing there: nothing to delete, nor to sync.
            if (!file.exists()) {
                true
            } else {
                file.delete()
                !file.exists() && dirSync(file.parentFile)
            }
        } else {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            FileOutputStream(tmp).use { out ->
                out.write(SendJournalCodec.encode(state).toString().toByteArray())
                out.fd.sync()
            }
            if (tmp.renameTo(file)) {
                // Renamed but maybe not on flash: not saved as far as a broadcast is concerned.
                dirSync(file.parentFile)
            } else {
                tmp.delete()
                false
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "couldn't save the send journal: ${e.javaClass.simpleName}")
        false
    }

    override fun load(): SendJournal.State? = try {
        if (file.exists()) SendJournalCodec.decode(JSONObject(file.readText())) else null
    } catch (e: Exception) {
        Log.w(TAG, "unreadable send journal: ${e.javaClass.simpleName}")
        null
    } catch (e: StackOverflowError) {
        null
    }

    internal companion object {
        private const val TAG = "WalletSend"

        /**
         * fsyncs [dir] itself, so a rename (or delete) in it survives a
         * power loss; false if it couldn't be. `java.nio` can't open a
         * directory on Android (`IoBridge` refuses one), so this goes
         * through [Os].
         */
        internal fun syncDirectory(dir: File?): Boolean {
            if (dir == null) return false
            return try {
                val fd = Os.open(dir.path, OsConstants.O_RDONLY, 0)
                try {
                    Os.fsync(fd)
                } finally {
                    Os.close(fd)
                }
                true
            } catch (e: ErrnoException) {
                Log.w(TAG, "couldn't sync the send journal's directory: ${e.errno}")
                false
            }
        }
    }
}

/** The journal's JSON. Only unresolved stages are written; anything else reads as no send. */
internal object SendJournalCodec {
    fun encode(state: SendJournal.State): JSONObject = JSONObject().apply {
        put("version", 1)
        state.send?.let { put("send", send(it)) }
        put(
            "abandoned",
            JSONObject().apply {
                state.abandoned.forEach { (k, a) ->
                    put(k, JSONObject().put("nonce", a.nonce.toString()).put("fees", fees(a.fees)).put("hash", a.hash))
                }
            },
        )
    }

    fun decode(o: JSONObject): SendJournal.State? {
        if (o.optInt("version") != 1) return null
        val abandoned = HashMap<String, NonceTracker.Abandoned>()
        o.optJSONObject("abandoned")?.let { a ->
            for (k in a.keys()) {
                // One entry that can't be read back is dropped; the others, and the send, still hold.
                runCatching {
                    val e = a.getJSONObject(k)
                    NonceTracker.Abandoned(BigInteger(e.getString("nonce")), fees(e.getJSONObject("fees")), e.getString("hash"))
                }.getOrNull()?.let { abandoned[k] = it }
            }
        }
        // A send that can't be read back is dropped, but the abandoned nonces still hold.
        val send = o.optJSONObject("send")?.let { runCatching { send(it) }.getOrNull() }
        return SendJournal.State(send, abandoned)
    }

    private fun send(s: SendJournal.Send): JSONObject {
        val q = s.status.quote
        val r = q.request
        val c = r.chain
        return JSONObject()
            .put(
                "chain",
                JSONObject().put("id", c.id).put("name", c.name).put("symbol", c.symbol)
                    .put("currencyName", c.currencyName).put("decimals", c.decimals)
                    .put("explorerUrl", c.explorerUrl ?: JSONObject.NULL).put("isTestnet", c.isTestnet)
                    .put("builtIn", c.builtIn).put("rpcUrls", JSONArray(c.rpcUrls)),
            )
            .put(
                "token",
                JSONObject().put("address", r.token.address ?: JSONObject.NULL).put("symbol", r.token.symbol)
                    .put("name", r.token.name).put("decimals", r.token.decimals),
            )
            .put("from", JSONObject().put("index", r.from.index).put("name", r.from.name).put("address", r.from.address))
            .put("to", r.to)
            .put("amount", r.amount.toString())
            .put(
                "tx",
                JSONObject().put("nonce", q.tx.nonce.toString()).put("gasLimit", q.tx.gasLimit.toString())
                    .put("to", q.tx.to).put("value", q.tx.value.toString()).put("data", q.tx.data.toHex())
                    .put("fees", fees(q.tx.fees)),
            )
            .put("nativeBalance", q.nativeBalance.toString())
            .put("tokenBalance", q.tokenBalance?.toString() ?: JSONObject.NULL)
            .put("preparedAt", q.preparedAt)
            .put(
                "trust",
                JSONObject().put("level", q.nonceTrust.level.name).put("source", q.nonceTrust.source.name)
                    .put("agreed", JSONArray(q.nonceTrust.agreed)).put("dissented", JSONArray(q.nonceTrust.dissented))
                    .put("queried", JSONArray(q.nonceTrust.queried)).put("k", q.nonceTrust.k).put("m", q.nonceTrust.m)
                    .put("block", q.nonceTrust.block ?: JSONObject.NULL),
            )
            .put("replaces", q.replaces ?: JSONObject.NULL)
            .put("stage", stage(s.status.stage))
            .put("raw", s.signed.raw)
            .put("hash", s.signed.hash)
    }

    private fun send(o: JSONObject): SendJournal.Send? {
        val c = o.getJSONObject("chain")
        val chain = Chain(
            id = c.getLong("id"),
            name = c.getString("name"),
            symbol = c.getString("symbol"),
            currencyName = c.getString("currencyName"),
            decimals = c.getInt("decimals"),
            explorerUrl = c.optStringOrNull("explorerUrl"),
            rpcUrls = c.getJSONArray("rpcUrls").strings(),
            isTestnet = c.getBoolean("isTestnet"),
            builtIn = c.getBoolean("builtIn"),
        )
        val t = o.getJSONObject("token")
        val token = Token(chain.id, t.optStringOrNull("address"), t.getString("symbol"), t.getString("name"), t.getInt("decimals"))
        val f = o.getJSONObject("from")
        val from = WalletAccount(f.getInt("index"), f.getString("name"), f.getString("address"))
        val request = SendRequest(chain, token, from, o.getString("to"), BigInteger(o.getString("amount")))
        val x = o.getJSONObject("tx")
        val tx = EthTransaction(
            chainId = chain.id,
            nonce = BigInteger(x.getString("nonce")),
            gasLimit = BigInteger(x.getString("gasLimit")),
            to = x.getString("to"),
            value = BigInteger(x.getString("value")),
            data = x.getString("data").hexToBytes(),
            fees = fees(x.getJSONObject("fees")),
        )
        val tr = o.getJSONObject("trust")
        val trust = ChainTrust(
            level = ChainTrust.Level.valueOf(tr.getString("level")),
            source = ChainSource.valueOf(tr.getString("source")),
            agreed = tr.getJSONArray("agreed").strings(),
            dissented = tr.getJSONArray("dissented").strings(),
            queried = tr.getJSONArray("queried").strings(),
            k = tr.getInt("k"),
            m = tr.getInt("m"),
            block = if (tr.isNull("block")) null else tr.getLong("block"),
        )
        val quote = SendQuote(
            request = request,
            tx = tx,
            nativeBalance = BigInteger(o.getString("nativeBalance")),
            tokenBalance = o.optStringOrNull("tokenBalance")?.let(::BigInteger),
            preparedAt = o.getLong("preparedAt"),
            nonceTrust = trust,
            replaces = o.optStringOrNull("replaces"),
        )
        val raw = o.getString("raw")
        val hash = o.getString("hash")
        // The bytes must be the ones the hash names, or Try again would resend something else.
        if (!hash.equals("0x" + Keccak256.digest(raw.hexToBytes()).toHex(), ignoreCase = true)) return null
        val stage = stage(o.getJSONObject("stage")) ?: return null
        return SendJournal.Send(SendStatus(quote, stage, hash), EthTransaction.Signed(tx, raw, hash))
    }

    private fun stage(s: SendStatus.Stage): JSONObject = when (s) {
        SendStatus.Stage.Broadcasting -> JSONObject().put("kind", "broadcasting")
        SendStatus.Stage.Pending -> JSONObject().put("kind", "pending")
        SendStatus.Stage.Unconfirmed -> JSONObject().put("kind", "unconfirmed")
        is SendStatus.Stage.Failed -> JSONObject().put("kind", "failed").put("message", s.message).put("mayHaveGone", s.mayHaveGone)
        else -> error("only an unresolved send is journalled")
    }

    private fun stage(o: JSONObject): SendStatus.Stage? = when (o.getString("kind")) {
        "broadcasting" -> SendStatus.Stage.Broadcasting
        "pending" -> SendStatus.Stage.Pending
        "unconfirmed" -> SendStatus.Stage.Unconfirmed
        "failed" -> SendStatus.Stage.Failed(o.getString("message"), o.getBoolean("mayHaveGone")).takeIf { it.mayHaveGone }
        else -> null
    }

    private fun fees(f: EthTransaction.Fees): JSONObject = when (f) {
        is EthTransaction.Fees.Eip1559 -> JSONObject().put("maxFeePerGas", f.maxFeePerGas.toString())
            .put("maxPriorityFeePerGas", f.maxPriorityFeePerGas.toString())
        is EthTransaction.Fees.Legacy -> JSONObject().put("gasPrice", f.gasPrice.toString())
    }

    private fun fees(o: JSONObject): EthTransaction.Fees = if (o.has("gasPrice")) {
        EthTransaction.Fees.Legacy(BigInteger(o.getString("gasPrice")))
    } else {
        EthTransaction.Fees.Eip1559(BigInteger(o.getString("maxFeePerGas")), BigInteger(o.getString("maxPriorityFeePerGas")))
    }

    private fun JSONObject.optStringOrNull(name: String): String? = if (isNull(name)) null else getString(name)

    private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
}
