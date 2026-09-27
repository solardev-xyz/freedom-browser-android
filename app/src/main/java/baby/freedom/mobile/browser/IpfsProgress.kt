package baby.freedom.mobile.browser

import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns the embedded freedom-ipfs node's retrieval-progress snapshot
 * into the one-line status the chrome shows while an `ipfs://` /
 * `ipns://` page loads ("IPFS: Finding providers…").
 *
 * A port of the desktop browser's `deriveIpfsProgressMessage`
 * (freedom-browser `src/renderer/lib/ipfs-progress-status.js`), same
 * phase vocabulary, same wording, same pick-the-most-telling-entry
 * scoring, so a slow CID reads the same on both. The snapshot is
 * `{"active":[…],"events":[…]}` from
 * `freedom_ipfs_node_progress_snapshot_json`: prefer the node's live
 * targets, and fall back to the tail of its recent events when none
 * are open.
 *
 * The snapshot is fed by a `tracing` layer freedom-ipfs installs as the
 * process's global subscriber — and in the combined
 * `libfreedom_mobile_ffi.so` ant has already claimed that slot by the
 * time IPFS starts (both use `try_init`, first one wins), so today the
 * snapshot comes back empty on Android. [fromCounters] reads the same
 * phases off the node's retrieval / routing counters instead, which are
 * plain atomics and always tick; [line] prefers the snapshot and falls
 * back to the counters, so a native fix lights up the finer-grained
 * phases with no change here (tracked as issue #156).
 */
object IpfsProgress {
    /** How often the chrome re-reads the snapshot while a load runs. */
    const val POLL_INTERVAL_MS: Long = 300L

    private val PHASE_MESSAGES = mapOf(
        "queued" to "IPFS: Queued request…",
        "started" to "IPFS: Starting request…",
        "resolving_name" to "IPFS: Resolving IPNS name…",
        "name_resolved" to "IPFS: Resolved name…",
        "checking_cache" to "IPFS: Checking local cache…",
        "cache_hit" to "IPFS: Loading from local cache…",
        "cache_miss" to "IPFS: Looking up content…",
        "provider_lookup" to "IPFS: Finding providers…",
        "providers_found" to "IPFS: Connecting to providers…",
        "provider_diversity_low" to "IPFS: Expanding provider search…",
        "dht_fallback_started" to "IPFS: Searching the DHT…",
        "fetching_bitswap" to "IPFS: Fetching from peers…",
        "fetching_http_provider" to "IPFS: Fetching from verified provider…",
        "first_byte" to "IPFS: Receiving content…",
        "streaming" to "IPFS: Receiving content…",
        "retrying" to "IPFS: Retrying slow provider…",
        "completed" to "IPFS: Loaded",
        "failed" to "IPFS: Load failed",
    )

    private val PHASE_SCORE = mapOf(
        "failed" to 100,
        "retrying" to 95,
        "fetching_bitswap" to 90,
        "fetching_http_provider" to 88,
        "first_byte" to 82,
        "streaming" to 80,
        "provider_lookup" to 72,
        "providers_found" to 70,
        "provider_diversity_low" to 68,
        "dht_fallback_started" to 66,
        "resolving_name" to 60,
        "checking_cache" to 45,
        "cache_hit" to 42,
        "cache_miss" to 40,
        "started" to 20,
        "queued" to 10,
        "completed" to 0,
    )

    private val TERMINAL_PHASES = setOf("completed", "failed")
    private val TERMINAL_STATUSES = setOf("complete", "completed", "done", "error", "failed")
    private val RUNNING_STATUSES = setOf("", "active", "started", "running")

    /** How many recent events the no-live-targets fallback considers. */
    private const val EVENT_TAIL = 12

    /**
     * The status line for [snapshotJson], or null when the node has
     * nothing in flight (or the snapshot is empty / unparseable) — the
     * chrome hides the line rather than showing a stale phase.
     */
    fun message(snapshotJson: String?): String? {
        if (snapshotJson.isNullOrBlank()) return null
        val snapshot = runCatching { JSONObject(snapshotJson) }.getOrNull() ?: return null
        val candidates = candidates(snapshot)
        if (candidates.isEmpty()) return null

        var best: JSONObject? = null
        var bestScore = Double.NEGATIVE_INFINITY
        candidates.forEachIndexed { index, item ->
            val score = score(item, index)
            if (score > bestScore) {
                best = item
                bestScore = score
            }
        }
        val item = best ?: return null
        PHASE_MESSAGES[phase(item)]?.let { return it }
        val message = item.optString("message").trim()
        if (message.isNotEmpty()) return "IPFS: $message"
        return "IPFS: Loading content…"
    }

    /**
     * The line to show: the snapshot's phase when it has one, else the
     * phase [fromCounters] reads off the counters' growth since
     * [baseline] (the reading taken when this load started), less
     * whatever [carried] attributes to superseded loads (see [LoadMeter]).
     */
    fun line(
        snapshotJson: String?,
        baseline: Counters?,
        now: Counters?,
        carried: Counters? = null,
    ): String? =
        message(snapshotJson)
            ?: if (baseline != null && now != null) fromCounters(baseline, now, carried) else null

    /**
     * The phase a load is in, from how the node's cumulative counters
     * moved since it started. Blocks arriving is the furthest along
     * (Bitswap peers or verified HTTP providers, whichever delivered
     * more), then cache hits, then providers found, then the DHT
     * fallback, then the delegated-routing lookup that starts every
     * cold fetch. Before any of those moves the node is still parsing
     * the path / queueing the request.
     *
     * The counters are node-wide, so a second IPFS tab loading at the
     * same time blends in — the price of a signal that works today; the
     * snapshot [message] reads is per-request. The one blend we can see
     * coming — this load superseding one on the same tab that is still
     * running in the node — is handled by [carried] (see [LoadMeter]):
     * the growth that happened while the old load was still busy in the
     * node is subtracted, so the line may under-report a phase
     * for that stretch but never shows the old load's.
     */
    fun fromCounters(baseline: Counters, now: Counters, carried: Counters? = null): String {
        val d = (now - baseline).minusClamped(carried)
        return when {
            d.bitswapBlocks > 0 && d.bitswapBlocks >= d.httpProviderBlocks ->
                PHASE_MESSAGES.getValue("fetching_bitswap")
            d.httpProviderBlocks > 0 -> PHASE_MESSAGES.getValue("fetching_http_provider")
            d.cacheHits > 0 -> PHASE_MESSAGES.getValue("cache_hit")
            d.providerResults > 0 -> PHASE_MESSAGES.getValue("providers_found")
            d.dhtLookups > 0 -> PHASE_MESSAGES.getValue("dht_fallback_started")
            d.delegatedLookups > 0 -> PHASE_MESSAGES.getValue("provider_lookup")
            else -> PHASE_MESSAGES.getValue("cache_miss")
        }
    }

    /**
     * The slice of freedom-ipfs's `FreedomIpfsDiagnostics` the phases
     * are read from. [of] takes the `long[11]` the `:node` process
     * hands over (see `FreedomIpfsNative.diagnostics`).
     */
    data class Counters(
        val cacheHits: Long = 0,
        val httpProviderBlocks: Long = 0,
        val bitswapBlocks: Long = 0,
        val delegatedLookups: Long = 0,
        val dhtLookups: Long = 0,
        /** Providers found, delegated routing and DHT together. */
        val providerResults: Long = 0,
    ) {
        /** This less [other] field by field, never below zero. */
        fun minusClamped(other: Counters?): Counters = if (other == null) this else Counters(
            cacheHits = (cacheHits - other.cacheHits).coerceAtLeast(0),
            httpProviderBlocks = (httpProviderBlocks - other.httpProviderBlocks).coerceAtLeast(0),
            bitswapBlocks = (bitswapBlocks - other.bitswapBlocks).coerceAtLeast(0),
            delegatedLookups = (delegatedLookups - other.delegatedLookups).coerceAtLeast(0),
            dhtLookups = (dhtLookups - other.dhtLookups).coerceAtLeast(0),
            providerResults = (providerResults - other.providerResults).coerceAtLeast(0),
        )

        operator fun plus(other: Counters) = Counters(
            cacheHits = cacheHits + other.cacheHits,
            httpProviderBlocks = httpProviderBlocks + other.httpProviderBlocks,
            bitswapBlocks = bitswapBlocks + other.bitswapBlocks,
            delegatedLookups = delegatedLookups + other.delegatedLookups,
            dhtLookups = dhtLookups + other.dhtLookups,
            providerResults = providerResults + other.providerResults,
        )

        operator fun minus(other: Counters) = Counters(
            cacheHits = cacheHits - other.cacheHits,
            httpProviderBlocks = httpProviderBlocks - other.httpProviderBlocks,
            bitswapBlocks = bitswapBlocks - other.bitswapBlocks,
            delegatedLookups = delegatedLookups - other.delegatedLookups,
            dhtLookups = dhtLookups - other.dhtLookups,
            providerResults = providerResults - other.providerResults,
        )

        companion object {
            /** Null for anything shorter than the 11-field layout. */
            fun of(raw: LongArray?): Counters? {
                if (raw == null || raw.size < 11) return null
                return Counters(
                    cacheHits = raw[2],
                    httpProviderBlocks = raw[3],
                    bitswapBlocks = raw[4],
                    delegatedLookups = raw[5],
                    providerResults = raw[6] + raw[9],
                    dhtLookups = raw[8],
                )
            }
        }
    }

    /**
     * One load's reading of the node's progress, poll by poll.
     *
     * The first counter reading is the load's baseline. After that, each
     * poll's growth is the load's own — unless a load it superseded on
     * the same tab was busy in the node at either end of that poll
     * interval ([GatewayWork.activeBefore]: a request still waiting for
     * its answer, or a body Chromium is still reading). The node doesn't
     * stop a fetch because the WebView went elsewhere, so while such a
     * request is busy the old load can move *any* counter, at any time,
     * in bursts; there is no telling its growth from this load's. That
     * interval's growth is set aside as carried, whole, and subtracted
     * from this load's (see [fromCounters]). Once the superseded
     * requests have all closed or gone idle (a paused `<video>`'s range
     * body, R4-F2), growth counts again — so a link tapped while the
     * page is still loading advances as soon as the old page's requests
     * are done, instead of being masked for the whole load.
     *
     * The snapshot is node-wide too, and an old load's request stays in
     * its `active` list for as long as the request is open, idle or not
     * — so it is only read while no superseded request is open at all
     * ([GatewayWork.openBefore]); until then the counters speak for this
     * load (R4-F1). Its entries can't be matched to a load by path: an
     * ENS site's is its resolved CID, and the node's spelling of a CID
     * needn't be ours.
     */
    class LoadMeter {
        private var baseline: Counters? = null
        private var last: Counters? = null
        private var carried = Counters()
        private var overlapping = false

        /**
         * Record a poll ([now] null when the node didn't answer) and
         * return the line to show. [supersededActive]: a superseded load
         * of this tab is busy in the node right now
         * ([GatewayWork.activeBefore]). [supersededOpen]: one has any
         * request open, busy or idle ([GatewayWork.openBefore]).
         */
        fun poll(
            snapshotJson: String?,
            now: Counters?,
            supersededActive: Boolean,
            supersededOpen: Boolean = supersededActive,
        ): String? {
            overlapping = overlapping || supersededActive
            if (now != null) {
                val prev = last
                if (prev == null) {
                    baseline = now
                } else if (overlapping) {
                    carried += (now - prev).minusClamped(Counters())
                }
                last = now
                overlapping = supersededActive
            }
            val snapshot = if (supersededOpen || supersededActive) null else snapshotJson
            return line(snapshot, baseline, now, carried)
        }
    }

    private fun candidates(snapshot: JSONObject): List<JSONObject> {
        val active = snapshot.optJSONArray("active").objects()
        if (active.isNotEmpty()) return active.filter(::isActive)
        return snapshot.optJSONArray("events").objects().filter(::isActive).takeLast(EVENT_TAIL)
    }

    private fun JSONArray?.objects(): List<JSONObject> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optJSONObject(it) }
    }

    private fun isActive(item: JSONObject): Boolean {
        val status = status(item)
        val phase = phase(item)
        if (phase in TERMINAL_PHASES || status in TERMINAL_STATUSES) return false
        return status in RUNNING_STATUSES
    }

    private fun score(item: JSONObject, index: Int): Double =
        (PHASE_SCORE[phase(item)] ?: 0) + kindScore(item) + elapsedScore(item) + index / 1000.0

    private fun kindScore(item: JSONObject): Int = when (token(item, "kind")) {
        "gateway_request" -> 20
        "name_resolution" -> 16
        "provider_lookup" -> 12
        "block_fetch" -> 6
        else -> 0
    }

    /** Up to 10 points for a long-running entry: one per elapsed second. */
    private fun elapsedScore(item: JSONObject): Double {
        val elapsed = item.optDouble("elapsed_ms", 0.0)
        if (elapsed.isNaN() || elapsed.isInfinite() || elapsed <= 0) return 0.0
        return minOf(10.0, elapsed / 1000.0)
    }

    private fun phase(item: JSONObject): String =
        firstToken(item, "phase", "current_phase", "last_phase", "event")

    private fun status(item: JSONObject): String = firstToken(item, "status", "state")

    /** The first non-empty of [keys], like the desktop's `a || b || c`. */
    private fun firstToken(item: JSONObject, vararg keys: String): String {
        for (key in keys) {
            val value = token(item, key)
            if (value.isNotEmpty()) return value
        }
        return ""
    }

    /** Trimmed, lower-cased, runs of `-`/whitespace folded to `_`. */
    private fun token(item: JSONObject, key: String): String {
        val raw = item.opt(key) as? String ?: return ""
        return raw.trim().lowercase().replace(Regex("[-\\s]+"), "_")
    }
}

/**
 * The gateway requests one tab has open — its submit probe's HEADs and
 * every request its WebView routes through [interceptVirtualRequest] —
 * each tagged with the [BrowserState.loadGeneration] it belongs to.
 * Thread-safe: the interceptor starts and finishes work on its own
 * threads, the chrome's poll reads it from the main thread.
 *
 * This is how the IPFS phase line (#94) knows a superseded load is still
 * running in the node ([activeBefore]) and for how long, instead of
 * guessing from which counters it had moved by some earlier poll.
 *
 * A request counts as open until its response body is closed (Chromium
 * closes it on EOF and on cancel) or its probe attempt returns, and as
 * busy while it waits for its answer or its body is being read. Entries
 * older than [STALE_MS] are ignored, so a body some path forgets to
 * close can't hold every later load's line back for good.
 */
internal class GatewayWork(
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private class Entry(var generation: Int, val startedAt: Long) {
        /** The answer is out; from here on only reads mean work. */
        var answered = false
        var readsInFlight = 0
        var lastReadAt = startedAt
    }

    private val lock = Any()
    private var nextToken = 0L
    private val open = HashMap<Long, Entry>()

    /** Record a request of load [generation]; pass the result to [finish]. */
    fun start(generation: Int): Long = synchronized(lock) {
        val token = ++nextToken
        open[token] = Entry(generation, clockMs())
        token
    }

    /**
     * The request [token]'s answer went out to Chromium: it now counts
     * as busy only while its body is being read (see [activeBefore]).
     */
    fun answered(token: Long) {
        synchronized(lock) {
            val entry = open[token] ?: return
            entry.answered = true
            entry.lastReadAt = clockMs()
        }
    }

    /** A read of [token]'s body starts ([started]) or returns. */
    fun reading(token: Long, started: Boolean) {
        synchronized(lock) {
            val entry = open[token] ?: return
            entry.readsInFlight = (entry.readsInFlight + if (started) 1 else -1).coerceAtLeast(0)
            entry.lastReadAt = clockMs()
        }
    }

    /** The request [token] is done. Idempotent. */
    fun finish(token: Long) {
        synchronized(lock) { open.remove(token) }
    }

    /**
     * Move every open request of loads [from] up to (not including) [to]
     * to load [to] — the document they were fetching for turned out to
     * be [to]'s as well (see [BrowserState.mainFrameAnswered]).
     */
    fun retag(from: Int, to: Int) {
        synchronized(lock) {
            for (entry in open.values) {
                if (entry.generation in from until to) entry.generation = to
            }
        }
    }

    /**
     * Some load older than [generation] is busy in the node: a request
     * still waiting for its answer, or one whose body is being read or
     * was read within [IDLE_MS]. A body Chromium stopped pulling (a
     * paused `<video>`'s open range request, say) holds the node to no
     * more than a pipe's worth of work, so it doesn't count (R4-F2).
     */
    fun activeBefore(generation: Int): Boolean = synchronized(lock) {
        val now = dropStale(now = clockMs())
        open.values.any {
            it.generation < generation &&
                (!it.answered || it.readsInFlight > 0 || now - it.lastReadAt < IDLE_MS)
        }
    }

    /** Some load older than [generation] has a request open, busy or idle. */
    fun openBefore(generation: Int): Boolean = synchronized(lock) {
        dropStale(now = clockMs())
        open.values.any { it.generation < generation }
    }

    private fun dropStale(now: Long): Long {
        open.values.removeAll { now - it.startedAt >= STALE_MS }
        return now
    }

    companion object {
        const val STALE_MS: Long = 60_000L

        /** How long after its last read an answered body still counts as busy. */
        const val IDLE_MS: Long = 1_000L
    }
}

/**
 * Whether a tab navigating to [url] is loading content the IPFS node
 * serves, given what it was loading before ([current]).
 *
 * [url] is anything the tab is handed or commits: a canonical
 * `ipfs://` / `ipns://` URL, the per-root virtual origin the WebView
 * actually loads (`https://<cid>.ipfs.freedom.baby/…`), or a raw
 * loopback-gateway URL.
 *
 * An ENS name (`ens://name.eth`, `https://name.eth.ens.…`) is IPFS when
 * this session resolved its contenthash to IPFS ([KnownEnsNames] — the
 * submit flow records every resolution before the WebView is handed
 * the name). One not resolved yet — a tab restored after a process
 * restart, a link to another name — is not IPFS *yet*: it is not
 * allowed to inherit [current], which may be a previous IPFS page's
 * (back from an IPFS page into a Swarm-hosted name must not poll the
 * IPFS node). The WebView's main-frame interceptor resolves the name
 * before fetching it and re-derives the flag then (see
 * `noteMainFrameContentLoad`). A `javascript:` URL leaves [current]
 * alone — it runs in the current page, it isn't a navigation.
 *
 * Everything else — Swarm, the web, the error page, home — is not IPFS.
 */
internal fun ipfsLoadFor(url: String, current: Boolean): Boolean {
    if (url.startsWith("javascript:")) return current
    if (IpfsGateway.isIpfsScheme(url)) return true
    val loadable = Gateways.toLoadable(url)
    when (val root = VirtualOrigin.parseHostOfUrl(loadable)) {
        is ContentRoot.Ipfs, is ContentRoot.IpnsKey, is ContentRoot.IpnsName -> return true
        is ContentRoot.Ens -> return when (KnownEnsNames.protocolFor(root.name)) {
            "ipfs", "ipns" -> true
            else -> false
        }
        is ContentRoot.Bzz -> return false
        null -> Unit
    }
    return IpfsGateway.isIpfsScheme(Gateways.toDisplay(url))
}
