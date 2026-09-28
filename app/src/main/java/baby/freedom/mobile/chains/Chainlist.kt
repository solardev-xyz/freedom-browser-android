package baby.freedom.mobile.chains

import android.content.Context
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The chainlist.org chain catalog (#107) — the ethereum-lists/chains
 * data as one flat JSON array (`https://chainlist.org/rpcs.json`, a few
 * MB), which Settings → Chains → Add chain searches. Mirrors the desktop
 * browser's `chain-catalog.js` and iOS's `ChainlistService`.
 *
 * Only RPCs Freedom can use as they are, without handing anyone an API
 * key or a usage trail, survive [parse]:
 *  - key-free: no `${API_KEY}`-style placeholder, no key baked into the
 *    query (`?api_key=…`, `?token=…`) or the path (Alchemy `/v2/<key>`,
 *    Infura `/v3/<key>`, NodeReal, GetBlock, Ankr, dwellir, … — see
 *    [hasPathKey]) — someone's key, shared with every user and tied to
 *    their account — and every other
 *    [RpcUrls.validate] rule (https, public host, no credentials);
 *  - tracking-free: an entry chainlist marks `tracking: "yes"` or
 *    `"limited"` is dropped — only `"none"` or no claim either way stays.
 * Loopback RPCs (`http://localhost:8545` on dev chains) are dropped too,
 * as are names that spell out a loopback or LAN address
 * (`127.0.0.1.nip.io`, `10-0-0-1.sslip.io`, `lvh.me` — see
 * [RpcUrls.isInternal]). A public-looking name whose DNS *happens* to
 * point at the device or the LAN can only be caught at connect time,
 * by whatever sends the requests (#108), not by reading the URL.
 */
object Chainlist {
    const val URL = "https://chainlist.org/rpcs.json"

    /** A day: chainlist changes often enough that longer would miss new chains. */
    const val CACHE_TTL_MS = 24L * 60 * 60 * 1000

    const val MAX_RESULTS = 50

    /** One catalog entry, slimmed to what Add chain needs. */
    data class Entry(
        val id: Long,
        val name: String,
        val shortName: String,
        val symbol: String,
        val currencyName: String,
        val decimals: Int,
        val explorerUrl: String?,
        /** Key-free, tracking-free RPCs only; may be empty. */
        val rpcUrls: List<String>,
        val isTestnet: Boolean,
        val tvl: Double,
    ) {
        fun toChain() = Chain(
            id = id,
            name = name,
            symbol = symbol,
            currencyName = currencyName,
            decimals = decimals,
            explorerUrl = explorerUrl,
            rpcUrls = rpcUrls,
            isTestnet = isTestnet,
        )
    }

    /**
     * The catalog's entries, or `null` if [json] isn't a JSON array at
     * all (an HTML error page, a truncated body). A single malformed
     * entry — the data is community-maintained — is skipped rather than
     * failing the whole list. Chain IDs are unique in the result (the
     * first entry for an ID wins): the picker keys its rows by ID.
     */
    fun parse(json: String): List<Entry>? {
        val array = try {
            JSONArray(json)
        } catch (_: Exception) {
            return null
        } catch (_: StackOverflowError) {
            // JSONTokener recurses once per nesting level: a body nested
            // tens of thousands deep ('[[[[…') would otherwise crash the
            // app instead of falling back to the cache.
            return null
        }
        val out = ArrayList<Entry>(array.length())
        val seen = HashSet<Long>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            entry(obj)?.takeIf { seen.add(it.id) }?.let(out::add)
        }
        return out
    }

    private fun entry(obj: JSONObject): Entry? {
        val id = obj.optLong("chainId", -1)
        if (id <= 0 || id > Chain.MAX_ID) return null
        val name = obj.optString("name").trim().take(Chain.MAX_NAME_LENGTH)
        if (name.isEmpty()) return null
        val currency = obj.optJSONObject("nativeCurrency") ?: return null
        val symbol = currency.optString("symbol").trim()
        if (symbol.isEmpty() || symbol.length > Chain.MAX_SYMBOL_LENGTH) return null
        val decimals = currency.optInt("decimals", -1)
        if (decimals !in Chain.DECIMALS_RANGE) return null
        val currencyName = currency.optString("name").trim().take(Chain.MAX_NAME_LENGTH).ifEmpty { symbol }

        val rpcs = LinkedHashSet<String>()
        val rpcArray = obj.optJSONArray("rpc")
        if (rpcArray != null) {
            for (j in 0 until rpcArray.length()) {
                usableRpc(rpcArray.opt(j))?.let(rpcs::add)
                if (rpcs.size >= Chain.MAX_RPC_URLS) break
            }
        }
        var explorer: String? = null
        val explorers = obj.optJSONArray("explorers")
        if (explorers != null) {
            for (j in 0 until explorers.length()) {
                explorer = ChainInput.normalizeExplorer(
                    explorers.optJSONObject(j)?.optString("url").orEmpty(),
                )
                if (explorer != null) break
            }
        }
        return Entry(
            id = id,
            name = name,
            shortName = obj.optString("shortName").trim(),
            symbol = symbol,
            currencyName = currencyName,
            decimals = decimals,
            explorerUrl = explorer,
            rpcUrls = rpcs.toList(),
            isTestnet = obj.optBoolean("isTestnet", false),
            tvl = obj.optDouble("tvl", 0.0).takeUnless { it.isNaN() } ?: 0.0,
        )
    }

    /**
     * An RPC entry — `{url, tracking, …}`, or a bare URL string in older
     * data — as a URL to keep, or `null` if it's keyed, tracking,
     * loopback or otherwise refused by [RpcUrls.validate].
     */
    internal fun usableRpc(entry: Any?): String? {
        val (url, tracking) = when (entry) {
            is String -> entry to null
            is JSONObject -> entry.optString("url") to
                (if (entry.has("tracking") && !entry.isNull("tracking")) entry.optString("tracking") else null)
            else -> return null
        }
        if (tracking != null && !tracking.trim().equals("none", ignoreCase = true)) return null
        if ('$' in url) return null
        val ok = RpcUrls.normalize(url) ?: return null
        if (hasKeyParameter(ok) || hasPathKey(ok)) return null
        return ok.takeUnless(RpcUrls::isLoopbackUrl)
    }

    /**
     * Fragments of a query parameter's name that mark it as a credential
     * (`key`, `apikey`, dRPC's `dkey`, `x-api-key`, `token`, `projectId`, …).
     */
    private val KEY_NAME_PARTS = listOf("key", "token", "secret", "auth", "pass", "cred", "project", "session")

    /**
     * Whether [url]'s query carries a credential: a parameter whose name
     * says so ([KEY_NAME_PARTS]), or whose value has the shape of a
     * generated token ([looksLikeKey]) whatever it's called — a provider
     * can name its key parameter anything.
     */
    internal fun hasKeyParameter(url: String): Boolean {
        val query = url.substringAfter('?', "").substringBefore('#')
        if (query.isEmpty()) return false
        return query.split('&').any { param ->
            val rawName = param.substringBefore('=')
            val value = param.substringAfter('=', "")
            val name = percentDecoded(rawName).lowercase()
            KEY_NAME_PARTS.any { it in name } ||
                // A bare `?<key>` has no `=`: the whole parameter is its name.
                (rawName.isNotEmpty() && looksLikeKey(rawName)) ||
                (value.isNotEmpty() && looksLikeKey(value))
        }
    }

    /**
     * [s] with `%XX` escapes decoded (`+` left alone), so `api%5Fkey`
     * reads as `api_key`; a malformed escape leaves the rest as is.
     */
    private fun percentDecoded(s: String): String {
        if ('%' !in s) return s
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val hex = if (c == '%' && i + 2 < s.length && HEX.matches(s.substring(i + 1, i + 3))) {
                s.substring(i + 1, i + 3).toInt(16)
            } else {
                null
            }
            if (hex != null) {
                out.write(hex)
                i += 3
            } else {
                out.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
        }
        return out.toString("UTF-8")
    }

    /**
     * Hosts where every endpoint is one account's private endpoint, key or
     * not in the path (QuickNode names each endpoint's subdomain after it).
     */
    private val KEYED_HOST_SUFFIXES = listOf(".quiknode.pro")

    /**
     * Whether one host label carries a key (Rivet's
     * `<32 hex>.eth.rpc.rivet.cloud`). Hosts are one case and full of
     * short generated-looking public IDs (conduit's `rpc-astra-9on2f72wzn`,
     * tanssi's `fraa-flashbox-2800-rpc`, zeeve's `…-6h42j7`), so this is
     * stricter than [looksLikeKey]: a UUID label, or a `-`-separated piece
     * mixing letters and digits that is 16+ characters of hex or 24+ of
     * anything. None of the ~7600 RPCs in today's catalog trips it.
     */
    internal fun looksLikeHostKey(label: String): Boolean {
        if (UUID.matches(label)) return true
        return label.split('-').any { piece ->
            piece.any(Char::isDigit) && piece.any(Char::isLetter) &&
                (piece.length >= 24 || piece.length >= 16 && HEX.matches(piece))
        }
    }

    private val UUID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val TOKEN_CHARS = Regex("[A-Za-z0-9_.~-]+")
    private val SEPARATORS = charArrayOf('-', '_', '.', '~')
    private val HEX = Regex("[0-9a-fA-F]+")

    /**
     * Whether [url] carries an account key in its path — Alchemy
     * `/v2/WddzdzI2o9S3…`, Infura `/v3/9aa3d95b…`, NodeReal/4EVERLAND
     * `/v1/<hex>`, GetBlock `/<hex>`, Ankr `/<chain>/<hex>`, Histori,
     * dwellir/Tenderly `/<uuid>` — or points at a host whose endpoints
     * are all per-account ([KEYED_HOST_SUFFIXES]) or has a key as a
     * subdomain ([looksLikeHostKey]). See [looksLikeKey]
     * for what counts as a key; it is judged the same wherever the
     * segment sits in the path.
     */
    internal fun hasPathKey(url: String): Boolean {
        val uri = try {
            java.net.URI(url)
        } catch (_: Exception) {
            return true
        }
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return true
        if (KEYED_HOST_SUFFIXES.any { host.endsWith(it) || host == it.removePrefix(".") }) return true
        if (host.split('.').any(::looksLikeHostKey)) return true
        return (uri.rawPath ?: "").split('/').any { it.isNotEmpty() && looksLikeKey(it) }
    }

    /**
     * Whether one path segment has the shape of a generated token rather
     * than a chain or version name (`polygon-zkevm-mainnet`, `v2`,
     * `tanssi-2002`, `bas_full_rpc_1`, `2716446429837000`). Judged on
     * the segment with its `-`/`_`/`.`/`~` separators removed — a
     * base64url key contains those too, so splitting on them would cut
     * a key into short, innocent-looking pieces — once it is at least
     * 16 characters long:
     *  - upper *and* lower case letters: a 32-character base64url key
     *    (Alchemy) has both all but ~1 in 10⁷ times; names are written
     *    in one case;
     *  - letters and 3+ digits, a 16+ character run of letters with a
     *    digit or of hex, or any 24+ character run without a separator
     *    (the longest name in the catalog, `assetchaintestnet`, is 17):
     *    hex and base-36 keys (Infura, NodeReal, GetBlock, Histori).
     * A UUID is always a key. The one public identifier of that shape is
     * an Avalanche blockchain ID (`/ext/bc/<id>/rpc`), and it is exempt
     * only if it *is* one — a cb58 string whose 4-byte SHA-256 checksum
     * matches ([isCb58Id]) — so a key doesn't pass for one by being put
     * behind `/ext/bc/`. Losing one public RPC to a false positive is
     * cheap; handing out someone's key is not.
     */
    internal fun looksLikeKey(segment: String): Boolean {
        // A percent-encoded segment isn't a name anyone types; treat it as opaque.
        if (!TOKEN_CHARS.matches(segment)) return segment.length >= 16
        if (UUID.matches(segment)) return true
        if (isCb58Id(segment)) return false
        val chars = segment.filterNot { it in SEPARATORS }
        if (chars.length < 16) return false
        val upper = chars.any(Char::isUpperCase)
        val lower = chars.any(Char::isLowerCase)
        if (upper && lower) return true
        if (!upper && !lower) return false // all digits: a chain ID, not a key
        if (chars.count(Char::isDigit) >= 3) return true
        return segment.split(*SEPARATORS).any { piece ->
            piece.length >= 24 || piece.length >= 16 && (piece.any(Char::isDigit) || HEX.matches(piece))
        }
    }

    private const val BASE58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    /**
     * Whether [s] is an Avalanche ID: cb58, i.e. base58 of 32 bytes plus
     * the last 4 bytes of their SHA-256. A random string passes the
     * checksum 1 time in 2³².
     */
    internal fun isCb58Id(s: String): Boolean {
        if (s.length !in 48..52) return false
        var n = java.math.BigInteger.ZERO
        val base = java.math.BigInteger.valueOf(58)
        for (c in s) {
            val digit = BASE58.indexOf(c)
            if (digit < 0) return false
            n = n.multiply(base).add(java.math.BigInteger.valueOf(digit.toLong()))
        }
        if (n.bitLength() > 36 * 8) return false
        val raw = n.toByteArray().let { if (it.size > 36) it.copyOfRange(it.size - 36, it.size) else it }
        val bytes = ByteArray(36 - raw.size) + raw
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes.copyOfRange(0, 32))
        return digest.copyOfRange(28, 32).contentEquals(bytes.copyOfRange(32, 36))
    }

    /**
     * Search by name, short name or chain ID (exact), best first: an
     * exact ID match, then by TVL. An empty query lists the highest-TVL
     * chains, so the picker opens on the chains a user most likely wants.
     */
    fun search(entries: List<Entry>, query: String, limit: Int = MAX_RESULTS): List<Entry> {
        val q = query.trim().lowercase()
        val byTvl = compareByDescending<Entry> { it.tvl }
        if (q.isEmpty()) return entries.sortedWith(byTvl).take(limit)
        val id = q.removePrefix("0x").takeIf { q.startsWith("0x") }?.toLongOrNull(16) ?: q.toLongOrNull()
        return entries
            .filter { e ->
                e.id == id ||
                    e.name.lowercase().contains(q) ||
                    e.shortName.lowercase().contains(q)
            }
            .sortedWith(compareByDescending<Entry> { it.id == id }.then(byTvl))
            .take(limit)
    }
}

/**
 * Loads [Chainlist] with a 24-hour disk cache ([Chainlist.CACHE_TTL_MS]),
 * like desktop: a fresh cache is used as is; a stale or missing one is
 * refreshed from the network; a failed refresh falls back to the stale
 * cache, whatever its age — a day-old chain list beats none — and only
 * fails when there's no cache at all. Concurrent callers share one
 * download.
 */
class ChainlistService internal constructor(
    private val cacheFile: File,
    private val fetch: suspend () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private var memo: Pair<Long, List<Chainlist.Entry>>? = null

    /** The catalog; throws [IOException] only when there's neither network nor cache. */
    suspend fun entries(): List<Chainlist.Entry> = mutex.withLock {
        val now = clock()
        memo?.let { (at, list) -> if (isFresh(at, now)) return@withLock list }

        val cached = withContext(Dispatchers.IO) { readCache() }
        if (cached != null && isFresh(cached.first, now)) {
            memo = cached
            return@withLock cached.second
        }
        val error: Exception = try {
            val body = fetch()
            val parsed = withContext(Dispatchers.Default) { Chainlist.parse(body) }
            if (parsed != null && parsed.isNotEmpty()) {
                withContext(Dispatchers.IO) { writeCache(body) }
                memo = now to parsed
                return@withLock parsed
            }
            IOException("chainlist response is not a chain list")
        } catch (e: IOException) {
            e
        }
        Log.w(TAG, "chainlist refresh failed: ${error.message}")
        if (cached != null) {
            memo = cached
            return@withLock cached.second
        }
        throw error
    }

    /**
     * Fetched less than [Chainlist.CACHE_TTL_MS] ago. A timestamp in the
     * future — the clock was ahead when it was taken and has since been
     * corrected — is stale, not fresh: otherwise the cache would go
     * unrefreshed until the clock caught up, days or months later.
     */
    private fun isFresh(at: Long, now: Long) = now - at in 0 until Chainlist.CACHE_TTL_MS

    /** `(fetchedAt, entries)` from disk, or `null` if missing or unusable. */
    private fun readCache(): Pair<Long, List<Chainlist.Entry>>? = try {
        if (!cacheFile.isFile) {
            null
        } else {
            val at = cacheFile.lastModified()
            Chainlist.parse(cacheFile.readText())?.takeIf { it.isNotEmpty() }?.let { at to it }
        }
    } catch (e: Exception) {
        Log.w(TAG, "reading chainlist cache failed", e)
        null
    }

    private fun writeCache(body: String) {
        try {
            cacheFile.parentFile?.mkdirs()
            val tmp = File(cacheFile.path + ".tmp")
            tmp.writeText(body)
            if (!tmp.renameTo(cacheFile)) {
                tmp.delete()
                return
            }
            cacheFile.setLastModified(clock())
        } catch (e: Exception) {
            Log.w(TAG, "writing chainlist cache failed", e)
        }
    }

    companion object {
        private const val TAG = "Chainlist"
        private const val MAX_BYTES = 32L * 1024 * 1024
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000

        @Volatile
        private var instance: ChainlistService? = null

        fun get(context: Context): ChainlistService =
            instance ?: synchronized(this) {
                instance ?: ChainlistService(
                    File(context.applicationContext.cacheDir, "chainlist/rpcs.json"),
                    { download() },
                ).also { instance = it }
            }

        /**
         * GET [url]: no cookies, no referrer, nothing but the request
         * itself. The blocking connect/read runs on its own thread, and
         * the caller is resumed with the cancellation the moment it is
         * cancelled — releasing [entries]'s mutex straight away — while
         * the connection is disconnected from yet another thread (a
         * blocked read may not notice a disconnect issued from its own
         * thread until it times out). So leaving the search page neither
         * leaves a multi-MB download running nor makes the next Add chain
         * wait for it.
         */
        internal suspend fun download(url: String = Chainlist.URL): String {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                useCaches = false
                setRequestProperty("Accept", "application/json")
            }
            return suspendCancellableCoroutine { cont ->
                thread(name = "chainlist-download", isDaemon = true) {
                    val result = runCatching {
                        try {
                            read(conn)
                        } finally {
                            conn.disconnect()
                        }
                    }
                    // Ignored if the caller was already cancelled.
                    cont.resumeWith(result)
                }
                cont.invokeOnCancellation {
                    thread(name = "chainlist-abort", isDaemon = true) { conn.disconnect() }
                }
            }
        }

        private fun read(conn: HttpURLConnection): String {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            if (conn.contentLengthLong > MAX_BYTES) throw IOException("catalog too large")
            return conn.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_BYTES) throw IOException("catalog too large")
                    out.write(buf, 0, n)
                }
                out.toString(Charsets.UTF_8.name())
            }
        }
    }
}
