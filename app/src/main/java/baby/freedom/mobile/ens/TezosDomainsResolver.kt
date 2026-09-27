package baby.freedom.mobile.ens

import android.util.Log
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Resolve a Tezos Domains `.tez` name to its published website, straight
 * from the Tezos Domains mainnet registry over public Tezos RPC.
 *
 * Ported from `freedom-browser/src/main/tezos-domains-resolver.js` —
 * same algorithm, same TTLs:
 *
 *  1. **Anchor.** Ask up to three RPC providers for chain id (must be
 *     mainnet) and head level. A provider whose head block is more than
 *     [STALE_HEAD_AGE_MS] old is *stuck*, not disagreeing: it sits out
 *     the round entirely (see [resolveUncached]). Of the rest,
 *     drop heads more than [MAX_HEAD_LAG_BLOCKS] from the median, then
 *     have each remaining provider name the hash
 *     of one shared block [ANCHOR_DEPTH] below the lowest head. Every
 *     read below happens *at that block*, so honest providers answer
 *     from the same chain state and can be compared byte for byte.
 *  2. **Discover.** Follow the upgradeable proxy
 *     ([PROXY_CONTRACT]'s `%contract`) to the registry, and find its
 *     annotated `%records` / `%expiry_map` big maps. Cached per provider
 *     for [DISCOVERY_TTL_MS] — one lying provider only poisons its own leg.
 *  3. **Look up.** The record is keyed by the Tezos `ScriptExpr` hash of
 *     the name's bytes; its `%expiry_key` points into the expiry map (an
 *     expired name is not found). `web:redirect_url` wins over
 *     `web:content_url`; `td:ttl` bounds the cache.
 *  4. **Quorum.** An answer two providers agree on is *verified*; a
 *     lone provider's is *unverified* and only short-cached. Providers
 *     that disagree without a strict majority (about the head, the
 *     anchor hash, or the record) are a [Outcome.Conflict] — refused,
 *     never settled by picking a side. A stuck provider never counts
 *     towards verification: it can only attest to the chain as it was
 *     when it stalled, and a live provider serving a record rolled back
 *     to that point would read the same — so a live provider answering
 *     next to a stuck one is still a lone, unverified answer.
 *
 * Website records may be `ipfs://` / `ipns://` (served natively, the
 * `.tez` name stays the origin; a published base path is kept) or
 * `http(s)://` (navigated to directly). See [toEnsResult] for how this
 * maps onto the [EnsResult] the browser's name pipeline speaks.
 */
class TezosDomainsResolver internal constructor(
    private val rpcEndpoints: List<String>,
    private val http: EnsHttp,
    private val now: () -> Long = System::currentTimeMillis,
) {
    constructor(rpcEndpoints: List<String> = DEFAULT_RPC_ENDPOINTS) :
        this(rpcEndpoints, EnsHttp.Default)

    /** One provider's answer about the record, compared field by field across legs. */
    internal data class Leg(
        val type: Type,
        val reason: String? = null,
        val protocol: String? = null,
        /** `ipfs://<id><basePath>`, `ipns://…`, or the http(s) URL. */
        val uri: String? = null,
        val redirect: Boolean = false,
        val expiry: String? = null,
        /** `td:ttl` as published — part of the comparison, since it steers the cache. */
        val ttl: String? = null,
    ) {
        enum class Type { OK, NOT_FOUND, UNSUPPORTED }
    }

    internal sealed class Outcome {
        data class Answer(val leg: Leg, val verified: Boolean, val agreed: Int, val asked: Int) : Outcome()
        data class Conflict(val reason: String, val detail: String) : Outcome()
        data class Failed(val reason: String) : Outcome()
    }

    private data class Discovery(val recordsId: String, val expiryMapId: String, val recordType: Any?)
    private data class Timed<T>(val value: T, val expiresAt: Long)
    /** [timestamp]: the head block's time (epoch ms), `null` if it didn't say. */
    private data class Head(val endpoint: String, val level: Long, val timestamp: Long? = null)
    private data class Anchor(val endpoint: String, val level: Long, val hash: String)

    private val resultCache = object : LinkedHashMap<String, Timed<Outcome.Answer>>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Timed<Outcome.Answer>>?) =
            size > MAX_CACHE_ENTRIES
    }
    private val discoveryCache = ConcurrentHashMap<String, Timed<Discovery>>()

    /**
     * name → resolution in flight, so the submit flow and the request
     * interceptor (and every iframe of a page) asking for the same
     * uncached name share one quorum round. The round runs in its own
     * scope: a caller that gives up doesn't cancel it for the others.
     */
    private val inflight = ConcurrentHashMap<String, Deferred<Outcome>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Resolve [rawName] (e.g. `alice.tez`). Throws [CancellationException]
     * if the caller was cancelled, like [EnsResolver.resolveContenthash].
     */
    suspend fun resolve(rawName: String): EnsResult {
        val name = rawName.trim().lowercase()
        if (!isTezosDomainName(name)) {
            return EnsResult.NotFound(name, "INVALID_NAME")
        }
        val outcome = resolveOutcome(name)
        return toEnsResult(name, outcome).also { Log.i(TAG, "[$name] → $it") }
    }

    internal suspend fun resolveOutcome(name: String): Outcome {
        synchronized(resultCache) {
            val cached = resultCache[name]
            if (cached != null && cached.expiresAt > now()) return cached.value
            resultCache.remove(name)
        }
        var mine: Deferred<Outcome>? = null
        val job = inflight.computeIfAbsent(name) {
            scope.async { resolveUncached(name) }.also { mine = it }
        }
        mine?.invokeOnCompletion { inflight.remove(name, mine) }
        return job.await()
    }

    /** Forget cached answers — one name's, or (with `null`) everything. */
    fun invalidate(name: String? = null) {
        if (name != null) {
            synchronized(resultCache) { resultCache.remove(name.trim().lowercase()) }
            return
        }
        synchronized(resultCache) { resultCache.clear() }
        discoveryCache.clear()
    }

    private suspend fun resolveUncached(name: String): Outcome {
        val endpoints = rpcEndpoints.take(3)
        val reachable = settle(endpoints) { fetchHead(it) }
        if (reachable.isEmpty()) return Outcome.Failed("all Tezos RPC providers failed")

        // A provider whose head is hours old is a stuck node, not a
        // provider that disagrees about the chain: comparing its head with
        // a live one leaves no majority, and the median of two is the
        // stale one — so it would push the *healthy* provider out and the
        // name would be refused. Stuck is judged by the block's own
        // timestamp against the device clock, which a lying provider
        // can't make an honest, live one fail. Unless the clock is
        // plainly wrong, and then age is measured from the newest head
        // instead, so a clearly-behind provider still sits out:
        //  - every head looks stale: the clock is fast, or every reachable
        //    provider is stuck (the newest is then only *less* stuck —
        //    no telling which, but it's still the best answer there is);
        //  - a head is further in the future than a live chain can be
        //    (Tezos nodes don't take blocks from the future): the clock
        //    is slow — or that provider is lying. A liar can claim any
        //    timestamp, so the newest head may only set aside strictly
        //    fewer heads than it keeps: two honest heads outvote one from
        //    the future, and one against one stays judged by the clock —
        //    a slow clock and a stuck provider look exactly like a live
        //    provider and a liar, so the round refuses rather than
        //    picking a side.
        // A stuck provider takes no further part: it can't vouch for the
        // chain after it stalled (see the class doc, step 4).
        val clock = now()
        fun stuckBy(reference: Long) =
            reachable.partition { it.timestamp != null && it.timestamp < reference - STALE_HEAD_AGE_MS }
        val newest = reachable.mapNotNull { it.timestamp }.maxOrNull()
        var ageFrom = clock
        var (stale, allHeads) = stuckBy(clock)
        if (newest != null && (allHeads.isEmpty() || newest > clock + STALE_HEAD_AGE_MS)) {
            val (s, c) = stuckBy(newest)
            if (allHeads.isEmpty() || s.size < c.size) {
                ageFrom = newest
                stale = s
                allHeads = c
            }
        }
        for (h in stale) {
            Log.w(TAG, "setting aside ${h.endpoint}: head ${h.level} is ${(ageFrom - h.timestamp!!) / 60_000} min old")
        }

        // Median-referenced outlier rejection: tolerates one provider far
        // behind (which would drag the anchor to before the registry) or
        // far ahead (lying) without letting it move the shared anchor.
        val sorted = allHeads.map { it.level }.sorted()
        val reference = sorted[(sorted.size - 1) / 2]
        val heads = allHeads.filter { kotlin.math.abs(it.level - reference) <= MAX_HEAD_LAG_BLOCKS }
        for (h in allHeads - heads.toSet()) {
            Log.w(TAG, "excluding ${h.endpoint}: head ${h.level} deviates from median $reference")
        }
        // A median only survives a minority of liars. Two reachable
        // providers that disagree leave no majority — the median of two is
        // just the lower one — so neither side can be trusted.
        if (heads.size * 2 <= allHeads.size) {
            val groups = allHeads.groupBy { it.level }.entries.joinToString("; ") { (level, hs) ->
                "chain head #$level: ${hs.joinToString(", ") { hostOf(it.endpoint) }}"
            }
            return Outcome.Conflict("Tezos RPC providers disagree about the chain head", groups)
        }

        val anchorLevel = heads.minOf { it.level } - ANCHOR_DEPTH
        val anchors = settle(heads) { fetchAnchor(it.endpoint, anchorLevel) }
        if (anchors.isEmpty()) return Outcome.Failed("Tezos RPC providers could not anchor a block")
        val anchorGroups = anchors.groupBy { it.hash }.values.sortedByDescending { it.size }
        val best = anchorGroups.first()
        // The hash at a settled depth isn't negotiable: without a strict
        // majority on it, a tie would be broken by iteration order.
        if (best.size * 2 <= anchors.size) {
            val groups = anchorGroups.joinToString("; ") { g ->
                "block #${g[0].level} ${g[0].hash.take(10)}…: ${g.joinToString(", ") { hostOf(it.endpoint) }}"
            }
            return Outcome.Conflict("Tezos RPC providers returned conflicting anchor blocks", groups)
        }

        val legs = settle(best) { anchor -> anchor.endpoint to resolveAtBlock(anchor.endpoint, anchor.hash, name) }
        if (legs.isEmpty()) return Outcome.Failed("Tezos Domains registry lookup failed")

        val groups = legs.groupBy { it.second }.values.sortedByDescending { it.size }
        val winner = groups.first()
        if (groups.size > 1 && winner.size * 2 <= legs.size) {
            val detail = groups.joinToString("; ") { g ->
                val leg = g[0].second
                val value = if (leg.type == Leg.Type.OK) leg.uri.orEmpty().take(300) else leg.reason
                "$value: ${g.joinToString(", ") { hostOf(it.first) }}"
            }
            return Outcome.Conflict("Tezos RPC providers returned conflicting results", detail)
        }
        if (winner.size < legs.size) {
            val dissenting = legs.filter { it !in winner }.joinToString(", ") { it.first }
            Log.w(TAG, "$dissenting disagreed with the majority for a .tez name")
        }
        val agreed = winner.size
        val answer = Outcome.Answer(
            leg = winner[0].second,
            verified = agreed >= 2,
            agreed = agreed,
            asked = legs.size + stale.size,
        )
        synchronized(resultCache) {
            resultCache[name] = Timed(answer, now() + cacheDuration(answer))
        }
        return answer
    }

    /** `Promise.allSettled` + keep the fulfilled: one bad provider never fails the round. */
    private suspend fun <T, R> settle(items: List<T>, block: (T) -> R): List<R> = coroutineScope {
        items.map { item ->
            async(Dispatchers.IO) {
                try {
                    block(item)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Tezos RPC leg failed: ${e.message}")
                    null
                }
            }
        }.awaitAll().filterNotNull()
    }

    internal fun cacheDuration(answer: Outcome.Answer): Long {
        val leg = answer.leg
        if (leg.type != Leg.Type.OK) return NEGATIVE_TTL_MS
        // One provider's word is short-cached so trust recovers as soon
        // as the others are back.
        if (!answer.verified) return UNVERIFIED_TTL_MS
        val recordTtl = leg.ttl?.toDoubleOrNull()
        var duration = if (recordTtl != null && recordTtl > 0) (recordTtl * 1_000).toLong() else DEFAULT_TTL_MS
        duration = minOf(duration, MAX_TTL_MS)
        parseTimestamp(leg.expiry)?.let { duration = minOf(duration, maxOf(0L, it - now())) }
        return maxOf(1_000L, duration)
    }

    // ---- RPC ----

    /** GET/POST [path] on [endpoint]; `null` for 404, throws for any other failure. */
    private fun rpc(endpoint: String, path: String, body: JSONObject? = null): Any? {
        val reply = http.request(
            method = if (body != null) "POST" else "GET",
            url = endpoint + path,
            headers = if (body != null) mapOf("content-type" to "application/json") else emptyMap(),
            body = body?.toString(),
            timeoutMs = REQUEST_TIMEOUT_MS,
            maxBytes = MAX_RPC_RESPONSE_BYTES,
            followRedirects = false,
        )
        if (reply.code == 404) return null
        if (reply.code !in 200..299) throw IllegalStateException("RPC returned HTTP ${reply.code}")
        return JSONTokener(reply.body).nextValue()
    }

    private fun fetchHead(endpoint: String): Head {
        val chainId = rpc(endpoint, "/chains/main/chain_id")
        if (chainId != MAINNET_CHAIN_ID) throw IllegalStateException("unexpected Tezos chain: $chainId")
        val header = rpc(endpoint, "/chains/main/blocks/head/header") as? JSONObject
        val level = header?.opt("level").toString().toLongOrNull()
        if (level == null || level <= ANCHOR_DEPTH) throw IllegalStateException("invalid Tezos head level")
        return Head(endpoint, level, parseTimestamp(header?.optString("timestamp")))
    }

    private fun fetchAnchor(endpoint: String, level: Long): Anchor {
        val hash = rpc(endpoint, "/chains/main/blocks/$level/hash") as? String
        if (hash == null || !hash.startsWith("B") || !base58Regex.matches(hash)) {
            throw IllegalStateException("invalid Tezos block hash")
        }
        return Anchor(endpoint, level, hash)
    }

    private fun fetchNormalizedScript(endpoint: String, block: String, contract: String): JSONObject? =
        rpc(
            endpoint,
            "/chains/main/blocks/$block/context/contracts/$contract/script/normalized",
            JSONObject().put("unparsing_mode", "Readable"),
        ) as? JSONObject

    private fun discoverRegistry(endpoint: String, block: String): Discovery {
        val proxy = fetchNormalizedScript(endpoint, block, PROXY_CONTRACT)
        val registry = (findAnnotatedValue(storageType(proxy), proxy?.opt("storage"), "%contract")
            ?.second as? JSONObject)?.optString("string").orEmpty()
        if (!contractRegex.matches(registry)) {
            throw IllegalStateException("Tezos Domains proxy returned an invalid registry contract")
        }
        val script = fetchNormalizedScript(endpoint, block, registry)
        val type = storageType(script)
        val records = findAnnotatedValue(type, script?.opt("storage"), "%records")
        val expiryMap = findAnnotatedValue(type, script?.opt("storage"), "%expiry_map")
        val recordsId = (records?.second as? JSONObject)?.optString("int").orEmpty()
        val expiryId = (expiryMap?.second as? JSONObject)?.optString("int").orEmpty()
        if (!digitsRegex.matches(recordsId) || !digitsRegex.matches(expiryId)) {
            throw IllegalStateException("Tezos Domains registry storage is missing annotated big maps")
        }
        val recordType = (records!!.first as? JSONObject)?.optJSONArray("args")?.opt(1)
        return Discovery(recordsId, expiryId, recordType)
    }

    private fun resolveAtBlock(endpoint: String, block: String, name: String): Leg {
        discoveryCache[endpoint]?.let { cached ->
            if (cached.expiresAt > now()) {
                try {
                    return lookupRecord(endpoint, block, name, cached.value)
                } catch (e: Exception) {
                    // Stale ids (registry migration) or a transient
                    // failure: rediscover once before failing the leg.
                    discoveryCache.remove(endpoint)
                }
            }
        }
        val discovery = discoverRegistry(endpoint, block)
        discoveryCache[endpoint] = Timed(discovery, now() + DISCOVERY_TTL_MS)
        return lookupRecord(endpoint, block, name, discovery)
    }

    private fun lookupRecord(endpoint: String, block: String, name: String, d: Discovery): Leg {
        val record = rpc(
            endpoint,
            "/chains/main/blocks/$block/context/big_maps/${d.recordsId}/${scriptExprHash(name.toByteArray(Charsets.UTF_8))}",
        ) ?: return Leg(Leg.Type.NOT_FOUND, reason = "NOT_REGISTERED")

        val data = findAnnotatedValue(d.recordType, record, "%data")?.second
        val expiryKey = findAnnotatedValue(d.recordType, record, "%expiry_key")?.second as? JSONObject
        val entries = mapEntries(data)

        var expiry: String? = null
        val expiryKeyHex = if (expiryKey?.optString("prim") == "Some") {
            expiryKey.optJSONArray("args")?.optJSONObject(0)?.optString("bytes")?.takeIf { it.isNotEmpty() }
        } else {
            null
        }
        if (expiryKeyHex != null) {
            val expiryRecord = rpc(
                endpoint,
                "/chains/main/blocks/$block/context/big_maps/${d.expiryMapId}/${scriptExprHash(bytesFromHex(expiryKeyHex))}",
            ) as? JSONObject
            expiry = expiryRecord?.opt("string") as? String
                ?: throw IllegalStateException("Tezos Domains expiry record is missing")
            val expiresAt = parseTimestamp(expiry)
            if (expiresAt != null && expiresAt <= now()) {
                return Leg(Leg.Type.NOT_FOUND, reason = "EXPIRED", expiry = expiry)
            }
        }

        val redirectUrl: Any?
        val contentUrl: Any?
        val ttl: String?
        try {
            redirectUrl = entries["web:redirect_url"]?.let(::decodeJsonBytes)
            contentUrl = entries["web:content_url"]?.let(::decodeJsonBytes)
            ttl = entries["td:ttl"]?.let(::decodeJsonBytes)?.toString()
        } catch (e: Exception) {
            return Leg(Leg.Type.UNSUPPORTED, reason = "invalid Tezos Domains metadata: ${e.message}")
        }
        // Presence, not value, decides precedence: a malformed redirect
        // record is unsupported, not a fallback to the content record.
        if ("web:redirect_url" in entries) {
            return parsePublishedUri(redirectUrl, redirect = true).copy(expiry = expiry, ttl = ttl)
        }
        if ("web:content_url" in entries) {
            return parsePublishedUri(contentUrl, redirect = false).copy(expiry = expiry, ttl = ttl)
        }
        return Leg(Leg.Type.NOT_FOUND, reason = "NO_WEBSITE_RECORD", expiry = expiry, ttl = ttl)
    }

    companion object {
        private const val TAG = "TezosDomains"

        const val MAINNET_CHAIN_ID = "NetXdQprcVkpaWU"
        const val PROXY_CONTRACT = "KT1F7JKNqwaoLzRsMio1MQC7zv3jG9dHcDdJ"

        /**
         * Three independent operators: the quorum reads the first three.
         * Same list as desktop.
         */
        val DEFAULT_RPC_ENDPOINTS: List<String> = listOf(
            "https://tezos-mainnet.octez.io",
            "https://rpc.tzkt.io/mainnet",
            "https://rpc.tzbeta.net",
        )

        private const val REQUEST_TIMEOUT_MS = 8_000
        private const val ANCHOR_DEPTH = 8L
        internal const val MAX_HEAD_LAG_BLOCKS = 60L

        /**
         * A head block older than this is a stuck node (mainnet makes a
         * block every few seconds). Generous, so a device clock a few
         * minutes off doesn't turn live providers into stuck ones.
         */
        internal const val STALE_HEAD_AGE_MS = 15L * 60_000
        internal const val DEFAULT_TTL_MS = 5L * 60_000
        internal const val MAX_TTL_MS = 60L * 60_000
        internal const val NEGATIVE_TTL_MS = 30_000L
        internal const val UNVERIFIED_TTL_MS = 30_000L
        private const val DISCOVERY_TTL_MS = 10L * 60_000
        private const val MAX_RPC_RESPONSE_BYTES = 5L * 1024 * 1024
        private const val MAX_CACHE_ENTRIES = 256

        private val SCRIPT_EXPR_PREFIX = byteArrayOf(13, 44, 64, 27)
        private val contractRegex = Regex("^KT1[1-9A-HJ-NP-Za-km-z]{33}$")
        private val base58Regex = Regex("^[1-9A-HJ-NP-Za-km-z]+$")
        private val digitsRegex = Regex("^\\d+$")
        private val schemeRegex = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):")

        /** `alice.tez`, `docs.alice.tez`: no empty labels, no URL syntax. */
        fun isTezosDomainName(value: String): Boolean {
            val lower = value.lowercase()
            if (!lower.endsWith(".tez") || lower.length > 255) return false
            if (lower.any { it.isWhitespace() || it == '/' || it == '?' || it == '#' || it.code <= 0x1f || it.code == 0x7f }) {
                return false
            }
            return lower.split('.').all { it.isNotEmpty() }
        }

        /** Tezos `expr…` hash of a packed-bytes big-map key. */
        fun scriptExprHash(keyBytes: ByteArray): String {
            val n = keyBytes.size
            val packed = byteArrayOf(0x05, 0x0a, (n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()) +
                keyBytes
            return base58Check(SCRIPT_EXPR_PREFIX + Blake2b.hash(packed, 32))
        }

        private fun base58Check(payload: ByteArray): String {
            val sha = MessageDigest.getInstance("SHA-256")
            val checksum = sha.digest(sha.digest(payload)).copyOfRange(0, 4)
            return Base58.encode(payload + checksum)
        }

        /**
         * A `web:*` record value → [Leg]. `http(s)` for either record;
         * `ipfs` / `ipns` for content only, and only pointing at content —
         * `ipns://self.tez` would resolve a name to itself, forever.
         */
        internal fun parsePublishedUri(raw: Any?, redirect: Boolean): Leg {
            fun unsupported(reason: String) = Leg(Leg.Type.UNSUPPORTED, reason = reason)
            if (raw !is String || raw.length > 8_192) return unsupported("invalid website URI")
            val uri = raw.trim()
            val protocol = schemeRegex.find(uri)?.groupValues?.get(1)?.lowercase()
                ?: return unsupported("invalid website URI")
            if (redirect && protocol != "http" && protocol != "https") {
                return unsupported("redirect URL must use HTTP(S)")
            }
            when (protocol) {
                "http", "https" -> {
                    val parsed = runCatching { URI(uri) }.getOrNull()
                    if (parsed?.host.isNullOrEmpty() || parsed?.rawUserInfo != null) {
                        return unsupported("invalid HTTP(S) website URI")
                    }
                    // `https://kukai.app` → `https://kukai.app/`, as a WHATWG parser would.
                    val normalized = if (parsed!!.rawPath.isNullOrEmpty()) {
                        "$protocol://${parsed.rawAuthority}/" +
                            (parsed.rawQuery?.let { "?$it" } ?: "") +
                            (parsed.rawFragment?.let { "#$it" } ?: "")
                    } else {
                        uri
                    }
                    return Leg(Leg.Type.OK, protocol = protocol, uri = normalized, redirect = redirect)
                }
                "ipfs", "ipns" -> {
                    val label = protocol.uppercase()
                    if (!uri.substring(protocol.length + 1).startsWith("//")) {
                        return unsupported("invalid $label website URI")
                    }
                    val rest = uri.substring(protocol.length + 3)
                    val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
                    val host = if (end >= 0) rest.substring(0, end) else rest
                    if (host.isEmpty() || host.contains('@') || host.contains(':')) {
                        return unsupported("invalid $label website URI")
                    }
                    // A trailing dot or a percent-encoded dot is the same
                    // name to a resolver — check the normalized form.
                    val forNameCheck = host.trimEnd('.').let {
                        runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it)
                    }.lowercase()
                    if (NameSystem.navigableSuffixes.any { forNameCheck.endsWith(it) }) {
                        return unsupported("$label website URI must reference content, not a name")
                    }
                    val path = if (end >= 0) rest.substring(end).substringBefore('?').substringBefore('#') else ""
                    val basePath = path.trimEnd('/')
                    return Leg(Leg.Type.OK, protocol = protocol, uri = "$protocol://$host$basePath")
                }
            }
            return unsupported("unsupported website protocol: $protocol")
        }

        /**
         * Append an address-bar [suffix] (`/path?q#f`) to a published
         * http(s) content URL, keeping its base path — and its own query /
         * fragment unless the suffix brings one. Mirrors desktop's
         * `appendPublishedWebsiteSuffix`.
         */
        fun appendWebsiteSuffix(target: String, suffix: String): String {
            if (suffix.isEmpty()) return target
            fun split(s: String): Triple<String, String?, String?> {
                val hash = s.indexOf('#')
                val frag = if (hash >= 0) s.substring(hash) else null
                val beforeHash = if (hash >= 0) s.substring(0, hash) else s
                val q = beforeHash.indexOf('?')
                val query = if (q >= 0) beforeHash.substring(q) else null
                return Triple(if (q >= 0) beforeHash.substring(0, q) else beforeHash, query, frag)
            }
            val (base, baseQuery, baseFrag) = split(target)
            val (path, query, frag) = split(suffix)
            val newBase = if (path.startsWith("/")) base.trimEnd('/') + path else base
            return newBase + (query ?: baseQuery.orEmpty()) + (frag ?: baseFrag.orEmpty())
        }

        /**
         * The browser pipeline's view of an [Outcome]. `http(s)` answers
         * are [EnsResult.Ok] with that protocol (the caller navigates to
         * them); a conflict is a non-retryable [EnsResult.Error] — a hard
         * refusal on a fresh navigation.
         */
        internal fun toEnsResult(name: String, outcome: Outcome): EnsResult = when (outcome) {
            is Outcome.Failed -> EnsResult.Error(name, "PROVIDER_ERROR", outcome.reason, retryable = true)
            is Outcome.Conflict -> EnsResult.Error(
                name,
                "PROVIDER_CONFLICT",
                "${outcome.reason} (${outcome.detail})",
                retryable = false,
            )
            is Outcome.Answer -> {
                val leg = outcome.leg
                when (leg.type) {
                    Leg.Type.NOT_FOUND -> EnsResult.NotFound(name, leg.reason ?: "NOT_FOUND")
                    Leg.Type.UNSUPPORTED -> EnsResult.Unsupported(name, codec = leg.reason.orEmpty(), rawContentHash = "")
                    Leg.Type.OK -> EnsResult.Ok(
                        name = name,
                        protocol = leg.protocol!!,
                        uri = leg.uri!!,
                        decoded = if (leg.protocol == "ipfs" || leg.protocol == "ipns") {
                            leg.uri.substringAfter("://").substringBefore('/')
                        } else {
                            leg.uri
                        },
                        redirect = leg.redirect,
                        verified = outcome.verified,
                    )
                }
            }
        }

        // ---- Micheline helpers (org.json values) ----

        private fun storageType(script: JSONObject?): Any? {
            val code = script?.optJSONArray("code") ?: return null
            for (i in 0 until code.length()) {
                val entry = code.optJSONObject(i) ?: continue
                if (entry.optString("prim") == "storage") return entry.optJSONArray("args")?.opt(0)
            }
            return null
        }

        private fun flattenPairLeaves(node: Any?): List<Any?> {
            if (node is JSONObject && node.optString("prim").equals("pair", ignoreCase = true)) {
                val args = node.optJSONArray("args") ?: return emptyList()
                return (0 until args.length()).flatMap { flattenPairLeaves(args.opt(it)) }
            }
            return listOf(node)
        }

        private fun hasAnnotation(node: Any?, annotation: String): Boolean {
            val annots = (node as? JSONObject)?.optJSONArray("annots") ?: return false
            return (0 until annots.length()).any { annots.optString(it) == annotation }
        }

        /** The (type, value) leaf of a pair tree whose type carries [annotation]. */
        internal fun findAnnotatedValue(type: Any?, value: Any?, annotation: String): Pair<Any?, Any?>? {
            if (type == null || value == null || value == JSONObject.NULL) return null
            val types = flattenPairLeaves(type)
            val values = flattenPairLeaves(value)
            val index = types.indexOfFirst { hasAnnotation(it, annotation) }
            if (index < 0 || index >= values.size) return null
            return types[index] to values[index]
        }

        private fun mapEntries(value: Any?): Map<String, String> {
            val array = value as? JSONArray ?: return emptyMap()
            val out = LinkedHashMap<String, String>()
            for (i in 0 until array.length()) {
                val elt = array.optJSONObject(i) ?: continue
                if (elt.optString("prim") != "Elt") continue
                val args = elt.optJSONArray("args") ?: continue
                val key = args.optJSONObject(0)?.opt("string") as? String ?: continue
                val bytes = args.optJSONObject(1)?.opt("bytes") as? String ?: continue
                out[key] = bytes
            }
            return out
        }

        private fun bytesFromHex(value: String): ByteArray {
            if (value.length % 2 != 0 || !value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                throw IllegalArgumentException("Invalid Micheline bytes value")
            }
            return value.hexToBytes()
        }

        private fun decodeJsonBytes(hex: String): Any? {
            val text = String(bytesFromHex(hex), Charsets.UTF_8)
            val tokener = JSONTokener(text)
            val v = tokener.nextValue()
            if (tokener.nextClean() != 0.toChar()) throw IllegalArgumentException("trailing data in JSON record")
            return if (v == JSONObject.NULL) null else v
        }

        private fun parseTimestamp(value: String?): Long? {
            if (value.isNullOrEmpty()) return null
            return runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
                ?: runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()
        }

        private fun hostOf(url: String): String = runCatching { URI(url).host }.getOrNull() ?: url
    }
}
