package baby.freedom.swarm

import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Makes sure a fresh node always has bootnodes it can dial, even where
 * ant's own `/dnsaddr/mainnet.ethswarm.org` resolution can't work.
 *
 * ant resolves that dnsaddr itself, over plain DNS (UDP/TCP port 53).
 * On Android it can't read the system resolver config (there is no
 * `/etc/resolv.conf`), so every lookup goes to Cloudflare's `1.1.1.1:53`.
 * A network that blocks or hijacks outbound port 53 (common on
 * corporate, hotel and some carrier networks) therefore leaves a fresh
 * install with zero peers. `ant_init` takes no bootnode list, but ant
 * warm-dials every entry in `<dataDir>/peers.json` before its own DNS
 * bootstrap runs. So when that file is empty, we fill it before
 * `ant_init`:
 *
 *  1. Walk the same `/dnsaddr/` TXT tree over DNS-over-HTTPS (the iOS
 *     approach, see freedom-browser-ios `docs/bootnode-resolution.md`).
 *     The endpoint is an IP literal, so it works even if the system
 *     DNS is broken.
 *  2. Add [FALLBACK_BOOTNODES], the list shipped with the app, whether
 *     or not DoH succeeded. The live root currently lists a single
 *     region, so a DoH answer alone can be narrower than the shipped
 *     list; merging means a network that reaches 1.1.1.1:443 but not
 *     that region's nodes still has other regions to dial.
 *
 * When `peers.json` already has entries (every launch after the first
 * successful one) this does nothing: no network request, no startup
 * delay, and ant's own resolution runs unchanged in both cases.
 */
internal object BootnodeSeeder {
    private const val TAG = "BootnodeSeeder"

    const val ROOT = "/dnsaddr/mainnet.ethswarm.org"

    /**
     * Cloudflare's DNS-JSON API at its IP literal. The certificate
     * covers `1.1.1.1` as an IP SAN, so this needs no DNS lookup.
     */
    private const val DOH_ENDPOINT = "https://1.1.1.1/dns-query"

    private const val MAX_DEPTH = 4
    private const val TOTAL_TIMEOUT_MS = 3_000L
    private const val PER_QUERY_TIMEOUT_MS = 2_000L

    /** Must match ant-p2p's `peerstore::SNAPSHOT_VERSION`. */
    private const val PEERSTORE_VERSION = 1

    /**
     * Plain-TCP bootnodes of every regional `_dnsaddr.<region>.mainnet.ethswarm.org`
     * record, captured 2026-09-27 with `dig TXT`, one line per node in
     * the order ams, hel, jhb, sgp, syd, tor (also `sao`'s only TCP
     * leaf), hil, hil. hil's second node, 5.78.94.214, is published
     * only as a `/tls/sni/…/ws` leaf on port 1635; its entry here uses
     * that record's peer id on the standard TCP port 1634, which was
     * checked open. Always merged in after the DoH result (see
     * [seedAddrs]): the root currently lists only the `emea` region,
     * so this list has wider coverage than a live lookup. If an entry goes stale,
     * ant drops it after five failed dials, and any one live bootnode
     * is enough to join the network.
     */
    val FALLBACK_BOOTNODES: List<String> = listOf(
        "/ip4/159.223.6.181/tcp/1634/p2p/QmP9b7MxjyEfrJrch5jUThmuFaGzvUPpWEJewCpx5Ln6i8",
        "/ip4/135.181.84.53/tcp/1634/p2p/QmTxX73q8dDiVbmXU7GqMNwG3gWmjSFECuMoCsTW4xp6CK",
        "/ip4/139.84.229.70/tcp/1634/p2p/QmRa6rSrUWJ7s68MNmV94bo2KAa9pYcp6YbFLMHZ3r7n2M",
        "/ip4/172.104.43.205/tcp/1634/p2p/QmeovveLJmgyfjiA9mJnvFTawHyisuJMCYicJffdWdxNmr",
        "/ip4/170.64.184.25/tcp/1634/p2p/Qmeh2e7U2FWrSooyrjWjnNKGceJWbRxLLx8Ppy5CimzsGH",
        "/ip4/172.105.9.172/tcp/1634/p2p/QmQq7zXgZ2Up5NF1tsCP2odgxzU4N3Evx2trkmFYnHm27w",
        "/ip4/216.238.102.247/tcp/1634/p2p/QmQYFDafiKuWUDknur8VcTUVgJgNxJevLxzYRKDKKvvv1r",
        "/ip4/5.78.94.214/tcp/1634/p2p/QmfEugihe2Pm78YomGupdxSt46Uxgg4DLpjkzgzzeouiKg",
    )

    /**
     * Keep only leaves ant can dial: an IP literal, a TCP port and the
     * peer id. The `/tls/sni/…/ws` variants in the TXT records are
     * WebSocket listeners, and ant's swarm is TCP-only.
     */
    private val DIALABLE = Regex("""^/ip[46]/[^/]+/tcp/\d+/p2p/[^/]+$""")

    /**
     * Seed `<antDataDir>/peers.json` if needed, before `ant_init`.
     * Blocking, and does network I/O only when it seeds. Never throws:
     * a failure here must not prevent the node from starting.
     */
    fun seedIfEmpty(antDataDir: File) {
        try {
            val file = File(antDataDir, "peers.json")
            if (!needsSeed(file)) return
            val resolved = resolveOverDoh()
            val addrs = seedAddrs(resolved)
            antDataDir.mkdirs()
            val tmp = File(antDataDir, "peers.json.seed")
            tmp.writeText(peerstoreJson(addrs))
            if (!tmp.renameTo(file)) {
                tmp.delete()
                Log.w(TAG, "could not write $file")
                return
            }
            Log.i(TAG, "seeded peers.json with ${addrs.size} bootnodes (${resolved.size} from DoH)")
        } catch (t: Throwable) {
            Log.w(TAG, "bootnode seeding failed; ant falls back to its own DNS", t)
        }
    }

    /**
     * The addresses to seed: the live DoH leaves first, then every
     * shipped [FALLBACK_BOOTNODES] entry not already among them. A DoH
     * success never narrows the seed below the shipped list.
     */
    fun seedAddrs(resolved: List<String>): List<String> =
        LinkedHashSet<String>().apply {
            addAll(resolved)
            addAll(FALLBACK_BOOTNODES)
        }.toList()

    /**
     * True when the peerstore file is missing, or is a valid snapshot of
     * our version with no peers. A file we can't parse, or one with a
     * different version, is left alone so we never clobber a snapshot a
     * newer ant wrote in a newer schema. This ant's `PeerStore::load`
     * treats such a file as empty and overwrites it with its own
     * snapshot on the next flush, so a corrupt file costs at most one
     * unseeded launch; the next launch sees a valid (possibly empty)
     * snapshot and seeds as usual.
     */
    fun needsSeed(file: File): Boolean {
        if (!file.exists()) return true
        return try {
            val snap = JSONObject(file.readText())
            snap.optInt("version", -1) == PEERSTORE_VERSION &&
                (snap.optJSONArray("peers")?.length() ?: 0) == 0
        } catch (_: JSONException) {
            false
        }
    }

    /**
     * ant-p2p's peerstore snapshot. The overlay is left empty (unknown
     * until the handshake) and `last_seen_unix` is 0, so ant keeps real
     * peers ahead of these once it has any. Addresses are grouped per
     * peer id.
     */
    fun peerstoreJson(addrs: List<String>): String {
        val byPeer = LinkedHashMap<String, MutableList<String>>()
        for (a in addrs) {
            if (!DIALABLE.matches(a)) continue
            byPeer.getOrPut(a.substringAfterLast("/p2p/")) { mutableListOf() }.add(a)
        }
        val peers = JSONArray()
        for ((id, peerAddrs) in byPeer) {
            peers.put(
                JSONObject()
                    .put("peer_id", id)
                    .put("addrs", JSONArray(peerAddrs))
                    .put("overlay", "")
                    .put("last_seen_unix", 0)
                    .put("fail_count", 0),
            )
        }
        return JSONObject().put("version", PEERSTORE_VERSION).put("peers", peers).toString()
    }

    /**
     * Walk [ROOT]'s TXT tree over DoH and return the dialable leaves,
     * or an empty list on any failure (timeout, HTTP error, bad JSON,
     * DNS error status, nothing dialable).
     *
     * [TOTAL_TIMEOUT_MS] is a hard wall-clock bound on the caller's
     * wait. `HttpURLConnection`'s connect and read timeouts apply to
     * each connect/handshake/read step separately, so a server that
     * accepts the connection and then trickles bytes could otherwise
     * hold every query for several multiples of its per-query timeout.
     * The walk runs on its own thread; when the deadline passes we stop
     * waiting, disconnect the query in flight, and every query the
     * abandoned walk still attempts fails at once.
     */
    fun resolveOverDoh(): List<String> {
        val deadline = System.currentTimeMillis() + TOTAL_TIMEOUT_MS
        val inFlight = InFlight()
        return runWithin(TOTAL_TIMEOUT_MS, onTimeout = { inFlight.cancel() }) {
            walk(ROOT) { name ->
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) null else queryTxt(name, minOf(remaining, PER_QUERY_TIMEOUT_MS), inFlight)
            }
        } ?: emptyList()
    }

    /**
     * Run [block] on a daemon thread and return its result, or null if
     * it hasn't finished within [timeoutMs] (or threw). On timeout the
     * block is left to wind down on its own, its result discarded, and
     * [onTimeout] is started on another daemon thread rather than run
     * here: aborting a connection can itself block (the JDK's
     * `HttpURLConnection.disconnect()` waits for a read in progress on
     * the same connection), and the caller must not wait for that.
     */
    fun <T : Any> runWithin(timeoutMs: Long, onTimeout: () -> Unit = {}, block: () -> T): T? {
        val done = CountDownLatch(1)
        var result: T? = null
        val worker = Thread({
            try {
                result = block()
            } catch (t: Throwable) {
                Log.d(TAG, "bootnode lookup failed: $t")
            } finally {
                done.countDown()
            }
        }, "BootnodeSeeder-doh")
        worker.isDaemon = true
        worker.start()
        if (done.await(timeoutMs, TimeUnit.MILLISECONDS)) return result
        Log.d(TAG, "DoH lookup exceeded ${timeoutMs}ms; giving up")
        Thread(onTimeout, "BootnodeSeeder-cancel").apply { isDaemon = true }.start()
        return null
    }

    /** The connection a walk currently has open, so a timeout can abort it. */
    private class InFlight {
        private var conn: HttpURLConnection? = null
        private var cancelled = false

        /** False if already cancelled, in which case [c] must not be used. */
        @Synchronized fun set(c: HttpURLConnection?): Boolean {
            if (cancelled) return false
            conn = c
            return true
        }

        fun cancel() {
            val c = synchronized(this) {
                cancelled = true
                conn.also { conn = null }
            }
            c?.disconnect()
        }
    }

    /**
     * Expand [root] depth-first. [txt] returns a name's TXT strings,
     * or null on failure. A failed branch is skipped and the rest of the
     * tree still counts, the same as ant's own resolver.
     */
    fun walk(root: String, txt: (String) -> List<String>?): List<String> {
        val out = LinkedHashSet<String>()
        val seen = HashSet<String>()
        fun follow(addr: String, depth: Int) {
            if (!addr.startsWith("/dnsaddr/")) {
                if (DIALABLE.matches(addr)) out += addr
                return
            }
            if (depth >= MAX_DEPTH) return
            val domain = addr.removePrefix("/dnsaddr/")
            if (!seen.add(domain)) return
            val records = txt("_dnsaddr.$domain") ?: return
            for (r in records) {
                val next = r.removePrefix("dnsaddr=")
                if (next != r) follow(next, depth + 1)
            }
        }
        follow(root, 0)
        return out.toList()
    }

    private fun queryTxt(name: String, timeoutMs: Long, inFlight: InFlight): List<String>? {
        val url = URL("$DOH_ENDPOINT?name=${URLEncoder.encode(name, "UTF-8")}&type=TXT")
        val conn = url.openConnection() as HttpURLConnection
        if (!inFlight.set(conn)) return null
        return try {
            conn.connectTimeout = timeoutMs.toInt()
            conn.readTimeout = timeoutMs.toInt()
            conn.setRequestProperty("Accept", "application/dns-json")
            if (conn.responseCode != 200) return null
            parseDohTxt(conn.inputStream.bufferedReader().use { it.readText() })
        } catch (t: Exception) {
            Log.d(TAG, "DoH query for $name failed: $t")
            null
        } finally {
            inFlight.set(null)
            conn.disconnect()
        }
    }

    /**
     * Pull the TXT strings out of a DNS-JSON response, or null if the
     * status isn't NOERROR. Cloudflare returns each TXT value as quoted
     * character-strings (`"\"dnsaddr=…\""`, and a value over 255 bytes
     * comes as several `"…" "…"` parts), while Google returns the bare
     * value. Both forms are handled.
     */
    fun parseDohTxt(body: String): List<String>? {
        val json = try {
            JSONObject(body)
        } catch (_: JSONException) {
            return null
        }
        if (json.optInt("Status", -1) != 0) return null
        val answers = json.optJSONArray("Answer") ?: return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until answers.length()) {
            val a = answers.optJSONObject(i) ?: continue
            if (a.optInt("type") != 16) continue
            val data = a.optString("data")
            out += if (data.startsWith("\"")) {
                QUOTED.findAll(data).joinToString("") { it.groupValues[1].replace("\\\"", "\"") }
            } else {
                data
            }
        }
        return out
    }

    private val QUOTED = Regex(""""((?:[^"\\]|\\.)*)"""")
}
