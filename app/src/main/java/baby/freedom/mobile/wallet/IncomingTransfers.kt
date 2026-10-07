package baby.freedom.mobile.wallet

import android.util.Log
import baby.freedom.mobile.chains.Chain
import baby.freedom.mobile.chains.rpc.ChainDataRouter
import baby.freedom.mobile.chains.rpc.ChainSource
import baby.freedom.mobile.chains.rpc.WalletRpc
import baby.freedom.mobile.chains.rpc.undisputed
import baby.freedom.mobile.ens.hexToBytes
import java.io.File
import java.io.FileOutputStream
import java.math.BigInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * A built-in token that arrived at one of the wallet's accounts (#441),
 * read from the token's ERC-20 `Transfer` log and checked against its
 * transaction's receipt. Chain and token names are kept as they were when
 * it was found, like [TxRecord]'s. Nothing here is secret: it's all on
 * chain.
 */
data class IncomingTransfer(
    /** The account it arrived at, as the wallet names it. */
    val account: String,
    val chainId: Long,
    val chainName: String,
    val explorerUrl: String?,
    val tokenAddress: String,
    val tokenSymbol: String,
    val tokenDecimals: Int,
    /** Who sent it (EIP-55); the zero address for a token minted to the account. */
    val from: String,
    val amount: BigInteger,
    val hash: String,
    val logIndex: Long,
    val block: Long,
    /** The block's own time, in ms. */
    val at: Long,
) {
    /** One log of one transaction: a transaction can carry several transfers. */
    val key: String get() = "$chainId:${hash.lowercase()}:$logIndex"
}

/** A `Transfer` log the scan found, not yet checked against its receipt. */
internal data class LogCandidate(
    val tokenAddress: String,
    val from: String,
    val amount: BigInteger,
    val hash: String,
    val logIndex: Long,
    val block: Long,
    /** The log as found, lower case, to match against the receipt's. */
    val topics: List<String>,
    val data: String,
) {
    val key: String get() = "${hash.lowercase()}:$logIndex"
}

/**
 * How far one account's scan on one chain has got: the blocks
 * [from]..[to] (inclusive, one unbroken range, null before the first
 * chunk) have been read, back to [floor] at most. [span] is the chunk
 * size the chain's RPCs last took.
 */
internal data class ScanState(
    val account: String,
    val chainId: Long,
    val from: Long? = null,
    val to: Long? = null,
    val floor: Long,
    val span: Long = IncomingScan.INITIAL_SPAN,
    /** Chunks read in a row since [span] last shrank. */
    val streak: Int = 0,
    val candidates: List<LogCandidate> = emptyList(),
    val transfers: List<IncomingTransfer> = emptyList(),
) {
    /** Nothing older than what's read is left to read. */
    val caughtUp: Boolean get() = from != null && from <= floor
}

/** The ERC-20 `Transfer` log: the filter that finds them and what one says. */
internal object IncomingLogs {
    /** `keccak256("Transfer(address,address,uint256)")`. */
    const val TRANSFER_TOPIC = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef"

    private val ADDRESS = Regex("^0x[0-9a-fA-F]{40}$")
    private val WORD = Regex("^0x[0-9a-fA-F]{64}$")
    private val QUANTITY = Regex("^0x[0-9a-fA-F]{1,16}$")

    /** [address] as an indexed topic: 32 bytes, left-padded. */
    fun topicOf(address: String): String {
        require(ADDRESS.matches(address)) { "not an address" }
        return "0x" + "0".repeat(24) + address.substring(2).lowercase()
    }

    /** `eth_getLogs` filter: [tokens]' transfers to [account] in [range]. */
    fun filter(account: String, tokens: List<Token>, range: LongRange): JSONObject = JSONObject()
        .put("fromBlock", "0x" + range.first.toString(16))
        .put("toBlock", "0x" + range.last.toString(16))
        .put("address", JSONArray().apply { tokens.forEach { t -> put(requireNotNull(t.address)) } })
        .put("topics", JSONArray().put(TRANSFER_TOPIC).put(JSONObject.NULL).put(topicOf(account)))

    /**
     * What [log] says, if it's a transfer of one of [tokens] to [account]
     * in [range] that moved something: anything else — another contract's
     * log (a node can answer with any), a zero-value transfer (the stuff
     * of address-poisoning spam), one dropped by a reorg (`removed`), a
     * malformed field — is null.
     */
    fun decode(log: Any?, account: String, tokens: List<Token>, range: LongRange): LogCandidate? {
        val o = log as? JSONObject ?: return null
        if (o.optBoolean("removed", false)) return null
        val address = o.opt("address") as? String ?: return null
        val token = tokens.firstOrNull { it.address.equals(address, ignoreCase = true) } ?: return null
        val topics = o.optJSONArray("topics") ?: return null
        if (topics.length() != 3) return null
        val t = (0 until 3).map { (topics.opt(it) as? String)?.lowercase() ?: return null }
        if (t[0] != TRANSFER_TOPIC || t[2] != topicOf(account)) return null
        if (!WORD.matches(t[1]) || !t[1].startsWith("0x" + "0".repeat(24))) return null
        val data = (o.opt("data") as? String)?.lowercase()?.takeIf { WORD.matches(it) } ?: return null
        val amount = BigInteger(data.substring(2), 16).takeIf { it.signum() > 0 } ?: return null
        val block = quantity(o.opt("blockNumber"))?.takeIf { it in range } ?: return null
        val logIndex = quantity(o.opt("logIndex")) ?: return null
        val hash = (o.opt("transactionHash") as? String)?.takeIf { WORD.matches(it) } ?: return null
        return LogCandidate(
            tokenAddress = requireNotNull(token.address),
            from = checksum(t[1].substring(26)),
            amount = amount,
            hash = hash.lowercase(),
            logIndex = logIndex,
            block = block,
            topics = t,
            data = data,
        )
    }

    /**
     * Whether [receipt] — the candidate's transaction's own, read through
     * the verified path — shows it: succeeded, in the same block, with the
     * same log (contract, topics, data) at the same index.
     */
    fun confirms(c: LogCandidate, receipt: JSONObject): Boolean {
        if (!(receipt.opt("transactionHash") as? String).equals(c.hash, ignoreCase = true)) return false
        if (quantity(receipt.opt("status")) != 1L) return false
        if (quantity(receipt.opt("blockNumber")) != c.block) return false
        val logs = receipt.optJSONArray("logs") ?: return false
        return (0 until logs.length()).any { i ->
            val l = logs.opt(i) as? JSONObject ?: return@any false
            val topics = l.optJSONArray("topics") ?: return@any false
            quantity(l.opt("logIndex")) == c.logIndex &&
                (l.opt("address") as? String).equals(c.tokenAddress, ignoreCase = true) &&
                (l.opt("data") as? String)?.lowercase() == c.data &&
                topics.length() == c.topics.size &&
                c.topics.indices.all { j -> (topics.opt(j) as? String)?.lowercase() == c.topics[j] }
        }
    }

    private fun quantity(v: Any?): Long? = (v as? String)?.takeIf { QUANTITY.matches(it) }
        ?.let { java.lang.Long.parseUnsignedLong(it.substring(2), 16) }?.takeIf { it >= 0 }

    private fun checksum(lowerHex: String): String = NodeIdentity.checksum(lowerHex.hexToBytes())
}

/**
 * Which blocks to read next. The read range stays one unbroken run:
 * new blocks first (on from what's read up to the head), then back in
 * time, a chunk at a time, down to the floor — so what arrived lately
 * shows first, and a scan cut short (the page closed, no RPC answering)
 * carries on where it left off.
 */
internal object IncomingScan {
    /** Blocks per `eth_getLogs` at first: what the shipped Ethereum and Gnosis RPCs take. */
    const val INITIAL_SPAN = 10_000L

    /** The smallest chunk a refused or failed read shrinks to. */
    const val MIN_SPAN = 250L

    /** Chunks read in a row before the chunk grows back (doubling, up to [INITIAL_SPAN]). */
    const val GROW_AFTER = 8

    /** How far back a first scan reads: about 30 days. */
    const val WINDOW_MS = 30L * 24 * 60 * 60_000

    private val SLOT_MS = mapOf(TokenRegistry.ETHEREUM to 12_000L, TokenRegistry.GNOSIS to 5_000L)

    /** Blocks left unread under the head: what a reorg could still change. */
    private val MARGIN = mapOf(TokenRegistry.ETHEREUM to 12L, TokenRegistry.GNOSIS to 20L)

    fun windowBlocks(chainId: Long): Long = WINDOW_MS / (SLOT_MS[chainId] ?: 12_000L)

    fun margin(chainId: Long): Long = MARGIN[chainId] ?: 12L

    /** How long under the head a block must be before an undisputed "no receipt" for it counts as absence. */
    const val ABSENCE_MS = 10L * 60_000

    /**
     * Blocks a candidate must lie under the proven head before RPCs agreeing
     * its transaction has no receipt is taken as proof it doesn't exist:
     * nearer the head, a quorum of RPCs a few blocks behind would say the
     * same of a real transfer, whose block then counts as read for good.
     */
    fun absenceDepth(chainId: Long): Long = ABSENCE_MS / (SLOT_MS[chainId] ?: 12_000L)

    /** A first scan's state for [account] on [chainId], the head at [head]. */
    fun start(account: String, chainId: Long, head: Long): ScanState =
        ScanState(account, chainId, floor = maxOf(0L, head - windowBlocks(chainId) + 1))

    /**
     * [s], with a head more than a window past what was read treated as
     * a new start: only a first scan's window under the head is read, not
     * the whole time the app went unopened (the blocks before that window
     * stay unread; the explorer has them). What was found stays. A state
     * with nothing read yet (a first scan whose every chunk failed) keeps
     * its floor only while that's still within a window of the head.
     */
    fun rebase(s: ScanState, head: Long): ScanState {
        val window = windowBlocks(s.chainId)
        val windowFloor = maxOf(0L, head - window + 1)
        val to = s.to ?: return if (s.floor < windowFloor) s.copy(floor = windowFloor) else s
        if (head - to <= window) return s
        return s.copy(from = null, to = null, floor = windowFloor)
    }

    /** The next chunk to read with the head at [head], or null when there's none. */
    fun nextRange(s: ScanState, head: Long): LongRange? {
        val from = s.from
        val to = s.to
        val range = when {
            from == null || to == null -> maxOf(s.floor, head - s.span + 1)..head
            head > to -> (to + 1)..minOf(to + s.span, head)
            from > s.floor -> maxOf(s.floor, from - s.span)..(from - 1)
            else -> return null
        }
        return range.takeIf { it.first <= it.last && it.first >= 0 }
    }

    /** [s] with [range] (from [nextRange]) read. */
    fun read(s: ScanState, range: LongRange): ScanState {
        val streak = s.streak + 1
        val grow = streak >= GROW_AFTER && s.span < INITIAL_SPAN
        return s.copy(
            from = minOf(s.from ?: range.first, range.first),
            to = maxOf(s.to ?: range.last, range.last),
            span = if (grow) minOf(INITIAL_SPAN, s.span * 2) else s.span,
            streak = if (grow) 0 else streak,
        )
    }

    /**
     * The part of [range] (from [nextRange]) whose [found] candidates fit
     * in [room], cut at a block boundary on the side that keeps what's read
     * one unbroken range: the oldest blocks of a chunk past [ScanState.to],
     * the newest of any other. Null when not even the first block fits.
     * When it doesn't but [room] is all of [cap] (the list is empty), that
     * one block is read with only its first [cap] logs kept: a block holding
     * more transfers to one account than that is not worth stalling over.
     */
    fun fitting(s: ScanState, range: LongRange, found: List<LogCandidate>, room: Int, cap: Int): Pair<LongRange, List<LogCandidate>>? {
        if (found.size <= room) return range to found
        val forward = s.to != null && range.first > s.to
        val blocks = found.groupBy { it.block }.toSortedMap(if (forward) naturalOrder() else reverseOrder())
        val kept = mutableListOf<LogCandidate>()
        var cut: Long? = null
        for ((block, logs) in blocks) {
            if (kept.size + logs.size > room) {
                cut = block
                break
            }
            kept += logs
        }
        val stop = requireNotNull(cut)
        if (kept.isEmpty()) {
            if (room < cap) return null
            val first = blocks.getValue(stop).sortedBy { it.logIndex }.take(cap)
            val next = blocks.keys.firstOrNull { it != stop }
            val part = if (forward) range.first..((next ?: range.last + 1) - 1) else ((next ?: range.first - 1) + 1)..range.last
            return part to first
        }
        return (if (forward) range.first..(stop - 1) else (stop + 1)..range.last) to kept
    }

    /** [s] after a chunk no RPC would read: smaller chunks from now on. */
    fun failed(s: ScanState): ScanState = s.copy(span = maxOf(MIN_SPAN, s.span / 2), streak = 0)
}

/** The reads a scan makes; [WalletRpc] in the app. */
internal interface IncomingReads {
    suspend fun blockNumber(chainId: Long): WalletRpc.Reading<Long>
    suspend fun logs(chainId: Long, filter: JSONObject): WalletRpc.Reading<JSONArray>
    suspend fun receipt(chainId: Long, hash: String): WalletRpc.Reading<JSONObject?>
    suspend fun blockTimestamp(chainId: Long, block: Long): WalletRpc.Reading<Long>

    class Rpc(private val rpc: WalletRpc) : IncomingReads {
        override suspend fun blockNumber(chainId: Long) = rpc.blockNumber(chainId)

        // Logs only find candidates (each is proven by its receipt before it's shown),
        // so one RPC's answer is enough: the first that takes the range, not a quorum of
        // three per chunk. Neither proof tier serves eth_getLogs anyway.
        override suspend fun logs(chainId: Long, filter: JSONObject) =
            rpc.logs(chainId, filter, skip = setOf(ChainSource.MYOTIS, ChainSource.COLIBRI, ChainSource.QUORUM))

        override suspend fun receipt(chainId: Long, hash: String) = rpc.receipt(chainId, hash)
        override suspend fun blockTimestamp(chainId: Long, block: Long) = rpc.blockTimestamp(chainId, block)
    }
}

/** Where the scans are kept between runs. */
internal interface IncomingStore {
    fun save(scans: List<ScanState>): Boolean
    fun load(): List<ScanState>

    object None : IncomingStore {
        override fun save(scans: List<ScanState>) = true
        override fun load(): List<ScanState> = emptyList()
    }
}

/** [IncomingStore] in one JSON file, written whole and renamed over, like [FileTxHistoryStore]. Never on the main thread. */
internal class FileIncomingStore(private val file: File) : IncomingStore {
    override fun save(scans: List<ScanState>): Boolean = try {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        if (scans.isEmpty()) {
            (!tmp.exists() or tmp.delete()) and (!file.exists() || file.delete())
        } else {
            file.parentFile?.mkdirs()
            FileOutputStream(tmp).use { it.write(IncomingCodec.encode(scans).toString().toByteArray()) }
            tmp.renameTo(file) || run {
                tmp.delete()
                false
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "couldn't save received transfers: ${e.javaClass.simpleName}")
        false
    }

    override fun load(): List<ScanState> = try {
        if (file.exists()) IncomingCodec.decode(JSONObject(file.readText())) else emptyList()
    } catch (e: Exception) {
        Log.w(TAG, "unreadable received transfers: ${e.javaClass.simpleName}")
        emptyList()
    } catch (e: StackOverflowError) {
        emptyList()
    }

    private companion object {
        const val TAG = "WalletIncoming"
    }
}

/** The scans file's JSON. An entry that can't be read back is dropped; the others still hold. */
internal object IncomingCodec {
    fun encode(scans: List<ScanState>): JSONObject = JSONObject()
        .put("version", 1)
        .put("scans", JSONArray().apply { scans.forEach { put(scan(it)) } })

    fun decode(o: JSONObject): List<ScanState> {
        if (o.optInt("version") != 1) return emptyList()
        val a = o.optJSONArray("scans") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i -> runCatching { scan(a.getJSONObject(i)) }.getOrNull() }
            .distinctBy { it.account.lowercase() to it.chainId }
    }

    private fun scan(s: ScanState): JSONObject = JSONObject()
        .put("account", s.account)
        .put("chainId", s.chainId)
        .put("from", s.from ?: JSONObject.NULL)
        .put("to", s.to ?: JSONObject.NULL)
        .put("floor", s.floor)
        .put("span", s.span)
        .put("streak", s.streak)
        .put("candidates", JSONArray().apply { s.candidates.forEach { put(candidate(it)) } })
        .put("transfers", JSONArray().apply { s.transfers.forEach { put(transfer(it)) } })

    private fun scan(o: JSONObject): ScanState {
        val candidates = o.getJSONArray("candidates")
        val transfers = o.getJSONArray("transfers")
        val from = if (o.isNull("from")) null else o.getLong("from")
        val to = if (o.isNull("to")) null else o.getLong("to")
        require((from == null) == (to == null) && (from == null || from <= to!!))
        return ScanState(
            account = o.getString("account"),
            chainId = o.getLong("chainId"),
            from = from,
            to = to,
            floor = o.getLong("floor"),
            span = o.getLong("span").coerceIn(IncomingScan.MIN_SPAN, IncomingScan.INITIAL_SPAN),
            streak = o.optInt("streak", 0),
            candidates = (0 until candidates.length()).mapNotNull { runCatching { candidate(candidates.getJSONObject(it)) }.getOrNull() },
            transfers = (0 until transfers.length()).mapNotNull { runCatching { transfer(transfers.getJSONObject(it)) }.getOrNull() },
        )
    }

    private fun candidate(c: LogCandidate): JSONObject = JSONObject()
        .put("token", c.tokenAddress)
        .put("from", c.from)
        .put("amount", c.amount.toString())
        .put("hash", c.hash)
        .put("logIndex", c.logIndex)
        .put("block", c.block)
        .put("topics", JSONArray(c.topics))
        .put("data", c.data)

    private fun candidate(o: JSONObject): LogCandidate {
        val topics = o.getJSONArray("topics")
        return LogCandidate(
            tokenAddress = o.getString("token"),
            from = o.getString("from"),
            amount = BigInteger(o.getString("amount")),
            hash = o.getString("hash").also { require(TX_HASH.matches(it)) },
            logIndex = o.getLong("logIndex"),
            block = o.getLong("block"),
            topics = (0 until topics.length()).map { topics.getString(it) },
            data = o.getString("data"),
        )
    }

    private fun transfer(t: IncomingTransfer): JSONObject = JSONObject()
        .put("account", t.account)
        .put("chainId", t.chainId)
        .put("chainName", t.chainName)
        .put("explorerUrl", t.explorerUrl ?: JSONObject.NULL)
        .put("token", t.tokenAddress)
        .put("tokenSymbol", t.tokenSymbol)
        .put("tokenDecimals", t.tokenDecimals)
        .put("from", t.from)
        .put("amount", t.amount.toString())
        .put("hash", t.hash)
        .put("logIndex", t.logIndex)
        .put("block", t.block)
        .put("at", t.at)

    private fun transfer(o: JSONObject) = IncomingTransfer(
        account = o.getString("account"),
        chainId = o.getLong("chainId"),
        chainName = o.getString("chainName"),
        explorerUrl = if (o.isNull("explorerUrl")) null else o.getString("explorerUrl"),
        tokenAddress = o.getString("token"),
        tokenSymbol = o.getString("tokenSymbol"),
        tokenDecimals = o.getInt("tokenDecimals"),
        from = o.getString("from"),
        amount = BigInteger(o.getString("amount")),
        hash = o.getString("hash").also { require(TX_HASH.matches(it)) },
        logIndex = o.getLong("logIndex"),
        block = o.getLong("block"),
        at = o.getLong("at"),
    )

    private val TX_HASH = Regex("^0x[0-9a-fA-F]{64}$")
}

/**
 * Built-in tokens received by the wallet's accounts (#441), found by
 * scanning each token's `Transfer` logs to the account through the
 * wallet's own chain reads — no explorer, no new third party.
 *
 * [scan] reads, per chain, the new blocks since last time and then back
 * to about [IncomingScan.WINDOW_MS] before the account's first scan, in
 * chunks the RPCs take ([IncomingScan]); one chunk at a time with a pause
 * between, cancelled with the page that started it, and resumed where it
 * stopped. What's read and found is kept per account and chain.
 *
 * A log only nominates a transfer: it's listed once its transaction's
 * receipt, read through the verification ladder and undisputed, shows the
 * same log in a successful transaction. A lone RPC can leave a transfer
 * out (the explorer stays the full record); it can't make one up.
 *
 * The chain's native currency (ETH, xDAI) leaves no log, so it can't be
 * found this way.
 */
class IncomingTransfers internal constructor(
    private val reads: IncomingReads,
    private val scope: CoroutineScope,
    private val store: IncomingStore = IncomingStore.None,
    /** Monotonic ms, for the pause between scans. */
    private val uptime: () -> Long = { System.nanoTime() / 1_000_000 },
    private val pauseMs: Long = CHUNK_PAUSE_MS,
) {
    private val scans = MutableStateFlow<Map<Pair<String, Long>, ScanState>>(emptyMap())
    private val _transfers = MutableStateFlow<List<IncomingTransfer>>(emptyList())
    private val _catchingUp = MutableStateFlow<Set<String>>(emptySet())

    /** Every transfer found, for every account, newest first. */
    val transfers: StateFlow<List<IncomingTransfer>> = _transfers.asStateFlow()

    /** Accounts (lower case) whose first scan is still reading back in time, while it runs. */
    val catchingUp: StateFlow<Set<String>> = _catchingUp.asStateFlow()

    private val scanning = Mutex()
    private val writing = Any()
    private var generation = 0
    private var loaded = false
    private var wipedBeforeLoad = false

    /** A write asked for before the file was read: done once it has been (a wipe's included). */
    private var writeAfterLoad = false

    /** Per (account, chain), when each candidate's receipt was last asked for, by [askSeq]. Under this object's lock. */
    private val lastAsked = HashMap<Pair<String, Long>, HashMap<String, Long>>()
    private var askSeq = 0L

    /** When each (account, chain) last read up to the head, by [uptime]. Under this object's lock. */
    private val lastScan = HashMap<Pair<String, Long>, Long>()
    private val fileRead = CompletableDeferred<Unit>()

    init {
        scope.launch(Dispatchers.IO) {
            val saved = store.load()
            val write = synchronized(this@IncomingTransfers) {
                loaded = true
                if (!wipedBeforeLoad) publish(saved.associateBy { keyOf(it.account, it.chainId) })
                writeAfterLoad
            }
            fileRead.complete(Unit)
            if (write) persistNow()
        }.invokeOnCompletion { fileRead.complete(Unit) }
    }

    internal suspend fun awaitLoaded() = fileRead.await()

    /**
     * Reads [account]'s new transfers on each of [chains] that has
     * built-in tokens. Suspends while it reads; cancel it to stop. A
     * chain caught up and read to its head under [MIN_SCAN_INTERVAL_MS]
     * ago isn't read again.
     */
    suspend fun scan(account: String, chains: List<Chain>) {
        fileRead.await()
        scanning.withLock {
            val who = account.lowercase()
            // One generation for the whole scan: a wipe during one chain stops the rest too,
            // or the next chain would save the removed account's reads again.
            val mine = synchronized(this) { generation }
            try {
                for (chain in chains) {
                    if (synchronized(this) { generation != mine }) return
                    val tokens = TokenRegistry.builtins.filter { it.chainId == chain.id }
                    if (tokens.isEmpty()) continue
                    try {
                        scanChain(account, chain, tokens, mine)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.i(TAG, "scan chain=${chain.id} stopped: ${e.javaClass.simpleName}")
                    }
                }
            } finally {
                _catchingUp.value = _catchingUp.value - who
            }
        }
    }

    private suspend fun scanChain(account: String, chain: Chain, tokens: List<Token>, mine: Int) {
        val key = keyOf(account, chain.id)
        val known = scans.value[key]
        val since = synchronized(this) { lastScan[key] }?.let { uptime() - it }
        if (known != null && known.caughtUp && known.candidates.isEmpty() && since != null && since in 0 until MIN_SCAN_INTERVAL_MS) return
        // The head bounds what's marked read: one RPC's word for it could skip blocks never read.
        val head = reads.blockNumber(chain.id).takeIf { it.trust.undisputed }?.value ?: return
        val safeHead = head - IncomingScan.margin(chain.id)
        if (safeHead <= 0) return
        var s = IncomingScan.rebase(known ?: IncomingScan.start(account, chain.id, safeHead), safeHead)
        synchronized(this) { if (generation != mine) return }
        if (!s.caughtUp) _catchingUp.value = _catchingUp.value + account.lowercase()
        var failures = 0
        var chunks = 0
        while (chunks < MAX_CHUNKS_PER_RUN) {
            // A candidate is never dropped once its block counts as read (bar one block holding more
            // than the whole list), or it would be gone for good: with the list full, reading waits
            // until receipts have drained it.
            val room = MAX_CANDIDATES - s.candidates.size
            if (room <= 0) break
            val range = IncomingScan.nextRange(s, safeHead) ?: break
            val logs = try {
                reads.logs(chain.id, IncomingLogs.filter(account, tokens, range)).value
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                s = IncomingScan.failed(s)
                if (!commit(key, s, mine)) return
                if (++failures >= MAX_FAILURES_PER_RUN) break
                delay(pauseMs)
                continue
            }
            failures = 0
            val found = (0 until logs.length()).mapNotNull { IncomingLogs.decode(logs.opt(it), account, tokens, range) }
            val have = s.transfers.map { "${it.hash.lowercase()}:${it.logIndex}" }.toSet()
            val known = s.candidates.map { it.key }.toSet()
            val fresh = found.filter { it.key !in have && it.key !in known }.distinctBy { it.key }
            if (fresh.size > room && s.span > IncomingScan.MIN_SPAN) {
                // More than fits: the range stays unread and is asked again in smaller chunks.
                s = IncomingScan.failed(s)
                if (!commit(key, s, mine)) return
                chunks++
                continue
            }
            // At the smallest chunk only the blocks whose candidates fit count as read; the rest
            // are asked again once receipts have made room. The list never passes the cap, so a
            // lying RPC's flood of fake logs can't pause reading for long: past the absence
            // depth, each scan drains its receipts' worth.
            val (part, kept) = IncomingScan.fitting(s, range, fresh, room, MAX_CANDIDATES) ?: break
            s = IncomingScan.read(s, part).copy(candidates = s.candidates + kept)
            if (!commit(key, s, mine)) return
            chunks++
            // A part read means the rest's next block didn't fit, and the room has only shrunk
            // since: asking again before receipts drain the list would find the same and stop.
            if (part != range) break
            if (IncomingScan.nextRange(s, safeHead) != null) delay(pauseMs)
        }
        val readToHead = (s.to ?: -1) >= safeHead
        synchronized(this) { if (readToHead && generation == mine) lastScan[key] = uptime() }
        verify(chain, s, key, mine, head)
    }

    /**
     * Checks [s]'s candidates against their receipts, the longest-unasked
     * first, so ones that stay unproven can't hold the rest back for good;
     * null when a wipe came meanwhile. A receipt that doesn't show the log
     * drops it; no receipt at all drops it only once its block is
     * [IncomingScan.absenceDepth] under [head] — before that, RPCs a little
     * behind would answer the same for a real transfer.
     */
    private suspend fun verify(chain: Chain, start: ScanState, key: Pair<String, Long>, mine: Int, head: Long): ScanState? {
        var s = start
        val batch = synchronized(this) {
            if (generation != mine) return null
            val asked = lastAsked.getOrPut(key) { HashMap() }
            val live = start.candidates.map { it.key }.toSet()
            asked.keys.retainAll(live)
            // Never asked first, then by when last asked; a stable sort keeps list order among equals.
            start.candidates.sortedBy { asked[it.key] ?: -1L }.take(MAX_VERIFY_PER_RUN).onEach { asked[it.key] = ++askSeq }
        }
        for (c in batch) {
            val keep: Boolean
            var found: IncomingTransfer? = null
            try {
                val r = reads.receipt(chain.id, c.hash)
                if (!r.trust.undisputed) {
                    keep = true
                } else {
                    val receipt = r.value
                    if (receipt == null) {
                        keep = head - c.block < IncomingScan.absenceDepth(chain.id)
                    } else if (!IncomingLogs.confirms(c, receipt)) {
                        keep = false
                    } else {
                        // The date orders it against sends: one RPC's word for it isn't enough either.
                        val time = reads.blockTimestamp(chain.id, c.block)
                        if (!time.trust.undisputed) continue
                        val seconds = time.value
                        val token = TokenRegistry.builtins.first { it.chainId == chain.id && it.address.equals(c.tokenAddress, ignoreCase = true) }
                        found = IncomingTransfer(
                            account = s.account,
                            chainId = chain.id,
                            chainName = chain.name,
                            explorerUrl = chain.explorerUrl,
                            tokenAddress = requireNotNull(token.address),
                            tokenSymbol = token.symbol,
                            tokenDecimals = token.decimals,
                            from = c.from,
                            amount = c.amount,
                            hash = c.hash,
                            logIndex = c.logIndex,
                            block = c.block,
                            at = seconds * 1000,
                        )
                        keep = false
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // No answer worth acting on: asked again next time.
                continue
            }
            if (keep) continue
            s = s.copy(
                candidates = s.candidates.filterNot { it.key == c.key },
                transfers = found?.let { t ->
                    (s.transfers.filterNot { it.key == t.key } + t).sortedByDescending { it.block }.take(MAX_TRANSFERS)
                } ?: s.transfers,
            )
            if (!commit(key, s, mine)) return null
        }
        return s
    }

    /** Stores [s] unless a wipe came after the scan began; false then. */
    private fun commit(key: Pair<String, Long>, s: ScanState, mine: Int): Boolean {
        synchronized(this) {
            if (generation != mine) return false
            publish(scans.value + (key to s))
        }
        persistLater()
        return true
    }

    /** Under this object's lock. */
    private fun publish(next: Map<Pair<String, Long>, ScanState>) {
        scans.value = next
        _transfers.value = next.values.flatMap { it.transfers }.sortedByDescending { it.at }
    }

    /** Forgets everything, on disk too (written off the calling thread): the wallet was removed. */
    fun wipe() {
        forget()
        persistLater()
    }

    /** [wipe], with the file gone before this returns. Blocks on storage: never on the main thread. */
    fun wipeNow(): Boolean = synchronized(writing) {
        forget()
        store.save(emptyList())
    }

    private fun forget() = synchronized(this) {
        generation++
        if (!loaded) wipedBeforeLoad = true
        lastScan.clear()
        lastAsked.clear()
        publish(emptyMap())
        _catchingUp.value = emptySet()
    }

    internal fun persistNow(): Boolean = synchronized(writing) {
        val snapshot = synchronized(this) {
            // Before the file is read, writing would overwrite what it holds; the
            // load writes once it's done instead, so a wipe's empty state still lands.
            if (!loaded) writeAfterLoad = true
            scans.value.values.toList().takeIf { loaded }
        } ?: return true
        store.save(snapshot)
    }

    private fun persistLater() {
        scope.launch(Dispatchers.IO) { persistNow() }
    }

    private fun keyOf(account: String, chainId: Long) = account.lowercase() to chainId

    companion object {
        private const val TAG = "WalletIncoming"

        /** Between two chunks: one request at a time, spaced out. */
        const val CHUNK_PAUSE_MS = 400L

        /** Chunks one chain reads per scan; a first scan of a busy chain carries on next time. */
        const val MAX_CHUNKS_PER_RUN = 64

        /**
         * Failed chunks in a row before a scan gives up on a chain until
         * next time: enough for a refused 10,000-block chunk to shrink to
         * 1,250 within one scan, few enough not to keep asking RPCs that
         * don't answer at all.
         */
        const val MAX_FAILURES_PER_RUN = 4

        /** Receipts read per chain per scan, the longest-unasked candidates first. */
        const val MAX_VERIFY_PER_RUN = 20

        /**
         * Unproven candidates kept per account and chain, never more. A full
         * list pauses reading until receipts drain it ([MAX_VERIFY_PER_RUN] a
         * scan; one with no receipt drains once its block is
         * [IncomingScan.absenceDepth] under the head, ~10 minutes); a chunk at [IncomingScan.MIN_SPAN] that
         * finds more than fits counts only its blocks that fit as read. A
         * found transfer is dropped for space only when one block alone holds
         * more than this many for the account.
         */
        const val MAX_CANDIDATES = 50

        /** Per account and chain; the oldest go first. */
        const val MAX_TRANSFERS = 200

        /** A chain caught up and read to its head this recently isn't read again. */
        const val MIN_SCAN_INTERVAL_MS = 30_000L

        @Volatile
        private var instance: IncomingTransfers? = null

        fun get(context: android.content.Context): IncomingTransfers = instance ?: synchronized(this) {
            val app = context.applicationContext
            instance ?: IncomingTransfers(
                reads = IncomingReads.Rpc(WalletRpc(ChainDataRouter.get(app))),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                store = FileIncomingStore(File(app.noBackupFilesDir, "wallet/incoming.json")),
            ).also { instance = it }
        }
    }
}
