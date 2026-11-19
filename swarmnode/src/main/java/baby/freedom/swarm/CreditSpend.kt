package baby.freedom.swarm

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.math.BigInteger
import org.json.JSONArray
import org.json.JSONObject

/*
 * Browsing credit's recent spend (#543), after desktop's
 * browsing-credit-service.js (#488): what the node's chequebook paid peers
 * in the last 24 hours and 7 days.
 *
 * ant's `GET /settlements` reports the peers the node is connected to now
 * and has a record with (sent or received something), each with its
 * cumulative `sent`, not a ledger of payments, so the spend is built here:
 * each reading is folded into a per-chequebook high-water mark per peer,
 * and what a peer's figure grew by is recorded as spend in an hourly
 * bucket, kept in the node's data dir. Growth is dated only when the same
 * peer was listed in the previous reading, and that reading is recent:
 * - a peer seen for the first time is a baseline (its lifetime total has
 *   no date), so its first cheque, which is what makes ant list it, isn't
 *   counted;
 * - a peer that drops out (disconnects) and comes back is a baseline
 *   again: what it was paid before it dropped out, or while it was away,
 *   has no hour to go in;
 * - so is every figure after a gap in the sampling (the node off, `:node`
 *   not running).
 * What's left out is left out, never put in the wrong hour.
 */

/** One peer's cumulative `sent` ([sent], PLUR) and when it was last listed ([seen], wall-clock ms). */
internal data class SpendPeer(val sent: BigInteger, val seen: Long)

/** Spend dated to the hour starting at [start] (wall-clock ms), [plur]. */
internal data class SpendBucket(val start: Long, val plur: BigInteger)

/**
 * A chequebook's spend history: first read at [since], last at [sampled],
 * the peers that reading listed ([listed]), the peers' marks, and the
 * hourly buckets, oldest first. On disk it's saved only with some other
 * change, so it can lag; [sampled] and [listed] are always saved
 * together, so they still describe one reading.
 */
internal data class SpendLedger(
    val since: Long,
    val sampled: Long,
    val peers: Map<String, SpendPeer>,
    val buckets: List<SpendBucket>,
    val listed: Set<String> = emptySet(),
)

/** One peer's row from a `/settlements` body: [peer] (lowercase) and its cumulative [sent]. */
internal data class SettlementRow(val peer: String, val sent: BigInteger)

internal object CreditSpend {
    const val HOUR_MS = 60 * 60_000L
    const val DAY_MS = 24 * HOUR_MS
    const val WEEK_MS = 7 * DAY_MS

    /** Spend is kept in hourly buckets: "last 24 hours" is accurate to the hour. */
    const val BUCKET_MS = HOUR_MS

    /** A week and a day: what the 7-day figure needs, and a little more. */
    const val KEEP_MS = WEEK_MS + DAY_MS

    /** How often `:node` reads `/settlements` while the node runs in light mode. */
    const val SAMPLE_MS = 5 * 60_000L

    /** Growth is only dated when the previous reading is at most this old. */
    const val MAX_SAMPLE_GAP_MS = 3 * SAMPLE_MS

    /** A peer's mark is refreshed at most this often while its figure is unchanged. */
    const val PEER_SEEN_REFRESH_MS = DAY_MS

    /** A `/settlements` body's rows; null when it isn't one. */
    fun rowsFrom(body: String): List<SettlementRow>? {
        val list = runCatching { JSONObject(body).getJSONArray("settlements") }.getOrNull() ?: return null
        return (0 until list.length()).mapNotNull { i ->
            val o = list.optJSONObject(i) ?: return@mapNotNull null
            val peer = o.optString("peer").lowercase().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val sent = plur(o.opt("sent")) ?: return@mapNotNull null
            SettlementRow(peer, sent)
        }
    }

    /**
     * Fold one `/settlements` reading ([rows], at [at]) into [ledger] (null:
     * none yet), whose [SpendLedger.sampled] and [SpendLedger.listed] are
     * the previous reading. Only a peer's growth between two readings at
     * most [MAX_SAMPLE_GAP_MS] apart, in order, that both listed it is
     * spend. A figure seen for the first time, a peer the previous reading
     * didn't list, or any figure after a longer gap, is a baseline.
     * Peers unlisted for longer than [KEEP_MS] are dropped; one that comes
     * back is a fresh baseline, so dropping it never invents spend. Pure:
     * the new ledger, and whether it differs enough to be saved.
     */
    fun record(ledger: SpendLedger?, rows: List<SettlementRow>, at: Long): Pair<SpendLedger, Boolean> {
        val current = LinkedHashMap<String, BigInteger>()
        for (row in rows) {
            val previous = current[row.peer]
            if (previous == null || row.sent > previous) current[row.peer] = row.sent
        }
        if (ledger == null) {
            return SpendLedger(at, at, current.mapValues { SpendPeer(it.value, at) }, emptyList(), current.keys) to true
        }

        val cutoff = at - KEEP_MS
        // A previous reading later than now (the clock was ahead, then
        // corrected) says nothing about how long the node went unwatched.
        val dated = at - ledger.sampled in 0..MAX_SAMPLE_GAP_MS
        // A saved `sampled` in the future is rewritten to now, like a peer's.
        var changed = ledger.sampled > at
        val peers = LinkedHashMap<String, SpendPeer>()
        for ((peer, known) in ledger.peers) {
            // A last-seen in the future (the clock stepped back) is read as now.
            val seen = minOf(known.seen, at)
            if (seen <= cutoff && peer !in current) {
                changed = true
                continue
            }
            if (known.seen > at) changed = true
            peers[peer] = SpendPeer(known.sent, seen)
        }
        var spent = BigInteger.ZERO
        for ((peer, sent) in current) {
            val known = peers[peer]
            when {
                known == null -> {
                    peers[peer] = SpendPeer(sent, at)
                    changed = true
                }
                sent > known.sent -> {
                    // Listed last time too: it grew since then. Otherwise
                    // it was away (disconnected), and when it was paid has
                    // no reading to place it.
                    if (dated && peer in ledger.listed) spent += sent - known.sent
                    peers[peer] = SpendPeer(sent, at)
                    changed = true
                }
                at - known.seen >= PEER_SEEN_REFRESH_MS -> {
                    peers[peer] = known.copy(seen = at)
                    changed = true
                }
            }
        }

        var buckets = ledger.buckets.filter { it.start + BUCKET_MS > cutoff }
        if (buckets.size != ledger.buckets.size) changed = true
        if (spent.signum() > 0) {
            val start = Math.floorDiv(at, BUCKET_MS) * BUCKET_MS
            val last = buckets.lastOrNull()
            buckets = if (last != null && last.start == start) {
                buckets.dropLast(1) + SpendBucket(start, last.plur + spent)
            } else {
                buckets + SpendBucket(start, spent)
            }
        }
        return SpendLedger(ledger.since, at, peers, buckets, current.keys) to changed
    }

    /**
     * Spend in the buckets that overlap the last [windowMs] before [at]: a
     * rolling window, up to one bucket (an hour) longer than [windowMs],
     * since the oldest bucket counts whole.
     */
    fun spendWithin(ledger: SpendLedger?, windowMs: Long, at: Long): BigInteger {
        val from = at - windowMs
        return ledger?.buckets.orEmpty().filter { it.start + BUCKET_MS > from }
            .fold(BigInteger.ZERO) { sum, b -> sum + b.plur }
    }

    fun toJson(ledger: SpendLedger): JSONObject = JSONObject()
        .put("since", ledger.since)
        .put("sampled", ledger.sampled)
        .put("listed", JSONArray().apply { ledger.listed.forEach { put(it) } })
        .put("peers", JSONObject().apply { ledger.peers.forEach { (p, v) -> put(p, JSONArray().put(v.sent.toString()).put(v.seen)) } })
        .put("buckets", JSONArray().apply { ledger.buckets.forEach { put(JSONArray().put(it.start).put(it.plur.toString())) } })

    /**
     * A stored ledger; null when it isn't one. Unreadable peers and buckets
     * are dropped, not the whole ledger; with no readable `listed`, no peer
     * counts as listed, so the next reading is a baseline.
     */
    fun fromJson(o: JSONObject): SpendLedger? {
        val since = o.optLong("since", -1L).takeIf { it >= 0 } ?: return null
        val sampled = o.optLong("sampled", -1L).takeIf { it >= 0 } ?: return null
        val peers = LinkedHashMap<String, SpendPeer>()
        o.optJSONObject("peers")?.let { p ->
            for (peer in p.keys()) {
                val entry = p.optJSONArray(peer) ?: continue
                val sent = plur(entry.opt(0)) ?: continue
                val seen = entry.optLong(1, -1L).takeIf { it >= 0 } ?: continue
                peers[peer] = SpendPeer(sent, seen)
            }
        }
        val buckets = ArrayList<SpendBucket>()
        o.optJSONArray("buckets")?.let { b ->
            for (i in 0 until b.length()) {
                val entry = b.optJSONArray(i) ?: continue
                val start = entry.optLong(0, -1L).takeIf { it >= 0 } ?: continue
                val plur = plur(entry.opt(1)) ?: continue
                buckets += SpendBucket(start, plur)
            }
        }
        val listed = LinkedHashSet<String>()
        o.optJSONArray("listed")?.let { l ->
            for (i in 0 until l.length()) l.optString(i).takeIf { it.isNotEmpty() }?.let { listed += it }
        }
        return SpendLedger(since, sampled, peers, buckets.sortedBy { it.start }, listed)
    }

    private fun plur(v: Any?): BigInteger? = when (v) {
        is String, is Number -> runCatching { BigInteger(v.toString()) }.getOrNull()?.takeIf { it.signum() >= 0 }
        else -> null
    }
}

/**
 * The spend ledgers, one per chequebook (lowercase `0x` address), in
 * [file]. Read once; written whole, through a temporary file renamed over
 * it. A file that can't be read or written costs only the history, never
 * the node: every failure is logged and swallowed.
 */
internal class CreditSpendStore(private val file: File) {
    private var ledgers: MutableMap<String, SpendLedger>? = null

    @Synchronized
    fun get(chequebook: String): SpendLedger? = load()[chequebook]

    /**
     * Keeps [ledger] as [chequebook]'s, and writes the file when [save]
     * (otherwise only this process sees it: the next reading's dating
     * needs the last reading, saved or not).
     */
    @Synchronized
    fun set(chequebook: String, ledger: SpendLedger, save: Boolean = true) {
        val all = load()
        all[chequebook] = ledger
        if (!save) return
        val tmp = File(file.parentFile, "${file.name}.tmp")
        try {
            val json = JSONObject().put("version", VERSION)
                .put("chequebooks", JSONObject().apply { all.forEach { (k, v) -> put(k, CreditSpend.toJson(v)) } })
            FileOutputStream(tmp).use { out ->
                out.write(json.toString().toByteArray())
                out.fd.sync()
            }
            check(tmp.renameTo(file))
        } catch (t: Throwable) {
            tmp.delete()
            Log.w(TAG, "couldn't save the spend history: ${t.javaClass.simpleName}")
        }
    }

    private fun load(): MutableMap<String, SpendLedger> {
        ledgers?.let { return it }
        val all = LinkedHashMap<String, SpendLedger>()
        try {
            if (file.exists()) {
                val o = JSONObject(file.readText())
                if (o.optInt("version") == VERSION) {
                    o.optJSONObject("chequebooks")?.let { c ->
                        for (k in c.keys()) c.optJSONObject(k)?.let(CreditSpend::fromJson)?.let { all[k] = it }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "the spend history is unreadable: ${t.javaClass.simpleName}")
        }
        ledgers = all
        return all
    }

    private companion object {
        const val VERSION = 1
        const val TAG = "CreditSpend"
    }
}
