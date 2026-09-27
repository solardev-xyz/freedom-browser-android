package baby.freedom.swarm

import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

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
 *  2. If DoH fails, use [FALLBACK_BOOTNODES], the list shipped with the
 *     app.
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
     * Plain-TCP leaves of every regional `_dnsaddr.<region>.mainnet.ethswarm.org`
     * record (ams, hel, jhb, sgp, syd, tor, sao), captured 2026-09-27
     * with `dig TXT`. Used only when the DoH lookup returns nothing.
     * The root currently lists only the `emea` region, so this list
     * has wider coverage than a live lookup. If an entry goes stale,
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
            val (source, addrs) =
                if (resolved.isNotEmpty()) "DoH" to resolved else "fallback" to FALLBACK_BOOTNODES
            antDataDir.mkdirs()
            val tmp = File(antDataDir, "peers.json.seed")
            tmp.writeText(peerstoreJson(addrs))
            if (!tmp.renameTo(file)) {
                tmp.delete()
                Log.w(TAG, "could not write $file")
                return
            }
            Log.i(TAG, "seeded peers.json with ${addrs.size} bootnodes from $source")
        } catch (t: Throwable) {
            Log.w(TAG, "bootnode seeding failed; ant falls back to its own DNS", t)
        }
    }

    /**
     * True when the peerstore file is missing, or is a valid snapshot of
     * our version with no peers. A file we can't read is left alone, so
     * we never overwrite something that a newer ant wrote in a newer
     * schema. (This ant ignores such a file anyway.)
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
     */
    fun resolveOverDoh(): List<String> {
        val deadline = System.currentTimeMillis() + TOTAL_TIMEOUT_MS
        return walk(ROOT) { name ->
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) null else queryTxt(name, minOf(remaining, PER_QUERY_TIMEOUT_MS))
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

    private fun queryTxt(name: String, timeoutMs: Long): List<String>? {
        val url = URL("$DOH_ENDPOINT?name=${URLEncoder.encode(name, "UTF-8")}&type=TXT")
        val conn = url.openConnection() as HttpURLConnection
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
