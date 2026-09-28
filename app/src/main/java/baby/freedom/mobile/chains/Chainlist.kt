package baby.freedom.mobile.chains

import android.content.Context
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
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
 *    query (`?api_key=…`, `?token=…` — someone's key, shared with every
 *    user and tied to their account), and every other
 *    [RpcUrls.validate] rule (https, public host, no credentials);
 *  - tracking-free: an entry chainlist marks `tracking: "yes"` or
 *    `"limited"` is dropped — only `"none"` or no claim either way stays.
 * Loopback RPCs (`http://localhost:8545` on dev chains) are dropped too:
 * a catalog pick must never point the app at the device itself.
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
     * failing the whole list.
     */
    fun parse(json: String): List<Entry>? {
        val array = try {
            JSONArray(json)
        } catch (_: Exception) {
            return null
        }
        val out = ArrayList<Entry>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            entry(obj)?.let(out::add)
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
        if (hasKeyParameter(ok)) return null
        return ok.takeUnless(RpcUrls::isLoopbackUrl)
    }

    private val KEY_PARAMETERS = setOf(
        "key", "apikey", "api_key", "api-key", "token", "access_token", "accesstoken", "auth", "secret",
    )

    /** Whether [url]'s query carries a credential-looking parameter. */
    internal fun hasKeyParameter(url: String): Boolean {
        val query = url.substringAfter('?', "").substringBefore('#')
        if (query.isEmpty()) return false
        return query.split('&').any { it.substringBefore('=').lowercase() in KEY_PARAMETERS }
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
        memo?.let { (at, list) -> if (now - at < Chainlist.CACHE_TTL_MS) return@withLock list }

        val cached = withContext(Dispatchers.IO) { readCache() }
        if (cached != null && now - cached.first < Chainlist.CACHE_TTL_MS) {
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
                    ::download,
                ).also { instance = it }
            }

        /**
         * GET [Chainlist.URL]: no cookies, no referrer, nothing but the
         * request itself. Cancelling the caller disconnects, so leaving
         * the search page doesn't leave a multi-MB download running.
         */
        private suspend fun download(): String = withContext(Dispatchers.IO) {
            val conn = (URL(Chainlist.URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                useCaches = false
                setRequestProperty("Accept", "application/json")
            }
            val handle = currentCoroutineContext().job.invokeOnCompletion { conn.disconnect() }
            try {
                val code = conn.responseCode
                if (code !in 200..299) throw IOException("HTTP $code")
                if (conn.contentLengthLong > MAX_BYTES) throw IOException("catalog too large")
                conn.inputStream.use { input ->
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
            } finally {
                handle.dispose()
                conn.disconnect()
            }
        }
    }
}
