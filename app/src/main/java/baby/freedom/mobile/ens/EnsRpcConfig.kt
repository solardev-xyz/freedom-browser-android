package baby.freedom.mobile.ens

import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

/**
 * A keyed RPC provider: an Ethereum mainnet endpoint that needs the
 * user's own API key in its URL. The catalog ([KEYED_PROVIDERS]) is the
 * desktop browser's (`src/shared/endpoint-sources.json`, the `keyed`
 * entries' chain-1 URLs).
 */
data class KeyedRpcProvider(
    val id: String,
    val name: String,
    /** Where to get a key, shown with the key field. */
    val website: String,
    /** Mainnet URL with `{API_KEY}` where the key goes. */
    val urlTemplate: String,
) {
    fun urlFor(apiKey: String): String = urlTemplate.replace("{API_KEY}", apiKey.trim())
}

/**
 * The user's name-resolution settings (#102): which RPC endpoints the
 * resolver asks, in which order, and whether it follows CCIP-Read.
 *
 * The resolver tries [endpoints] top to bottom, the desktop browser's
 * tiers: the user's own endpoints first, then keyed providers with a
 * key set, then the built-in public endpoints that aren't switched off.
 * Public endpoints are stored as the set the user *disabled*, so one
 * added to [PUBLIC_ENDPOINTS] by a later release is on by default.
 */
data class EnsRpcConfig(
    val customEndpoints: List<String> = emptyList(),
    val disabledPublicEndpoints: Set<String> = emptySet(),
    /** Provider id ([KEYED_PROVIDERS]) → API key. */
    val apiKeys: Map<String, String> = emptyMap(),
    val ccipRead: Boolean = true,
) {
    /** One entry of the resolution order, for the settings page. */
    data class Source(val kind: Kind, val label: String, val url: String)

    enum class Kind { CUSTOM, KEYED, PUBLIC }

    /** Why [validateEndpoint] refuses an endpoint URL. */
    enum class Rejection { EMPTY, TOO_LONG, NOT_A_URL, SCHEME, USER_INFO }

    class Validation(val url: String?, val rejection: Rejection?)

    val enabledPublicEndpoints: List<String>
        get() = PUBLIC_ENDPOINTS.filter { it !in disabledPublicEndpoints }

    /** Every endpoint the resolver will try, in order, each once. */
    val sources: List<Source>
        get() {
            val seen = HashSet<String>()
            val out = ArrayList<Source>()
            for (url in customEndpoints) {
                if (seen.add(url)) out += Source(Kind.CUSTOM, "Your endpoint", url)
            }
            for (provider in KEYED_PROVIDERS) {
                val key = apiKeys[provider.id]?.trim().orEmpty()
                if (key.isEmpty()) continue
                val url = provider.urlFor(key)
                if (seen.add(url)) out += Source(Kind.KEYED, provider.name, url)
            }
            for (url in enabledPublicEndpoints) {
                if (seen.add(url)) out += Source(Kind.PUBLIC, "Public", url)
            }
            return out
        }

    val endpoints: List<String> get() = sources.map { it.url }

    /** What [EnsResolver] needs from this, compared to spot a change. */
    val resolverSettings: EnsResolver.Settings
        get() = EnsResolver.Settings(endpoints = endpoints, ccipRead = ccipRead)

    /**
     * Whether removing [url] from the order still leaves the resolver
     * something to ask. The settings page greys out the switch / remove
     * button that would take the last endpoint away.
     */
    fun canRemove(url: String): Boolean = endpoints.any { it != url }

    companion object {
        /** The built-in public mainnet endpoints, tried in this order. */
        val PUBLIC_ENDPOINTS: List<String> = listOf(
            "https://ethereum.publicnode.com",
            "https://1rpc.io/eth",
            "https://eth.drpc.org",
            "https://eth-mainnet.public.blastapi.io",
            "https://eth.merkle.io",
        )

        val KEYED_PROVIDERS: List<KeyedRpcProvider> = listOf(
            KeyedRpcProvider(
                id = "alchemy",
                name = "Alchemy",
                website = "https://www.alchemy.com/rpc",
                urlTemplate = "https://eth-mainnet.g.alchemy.com/v2/{API_KEY}",
            ),
            KeyedRpcProvider(
                id = "infura",
                name = "Infura",
                website = "https://docs.metamask.io/services/get-started/endpoints/",
                urlTemplate = "https://mainnet.infura.io/v3/{API_KEY}",
            ),
            KeyedRpcProvider(
                id = "drpc",
                name = "DRPC",
                website = "https://drpc.org/docs",
                urlTemplate = "https://lb.drpc.live/ethereum/{API_KEY}",
            ),
        )

        const val MAX_CUSTOM_ENDPOINTS = 10
        private const val MAX_URL_LENGTH = 2048

        /**
         * Check a user-typed RPC endpoint: an absolute `https://` (or
         * `http://`, for a node of your own on the local network) URL
         * with a host and no user name or password. Returns the trimmed
         * URL, or why it was refused.
         */
        fun validateEndpoint(raw: String): Validation {
            val text = raw.trim()
            fun no(r: Rejection) = Validation(null, r)
            if (text.isEmpty()) return no(Rejection.EMPTY)
            if (text.length > MAX_URL_LENGTH) return no(Rejection.TOO_LONG)
            if (text.any { it.isWhitespace() }) return no(Rejection.NOT_A_URL)
            val uri = runCatching { URI(text) }.getOrNull() ?: return no(Rejection.NOT_A_URL)
            val scheme = uri.scheme?.lowercase() ?: return no(Rejection.NOT_A_URL)
            if (scheme != "https" && scheme != "http") return no(Rejection.SCHEME)
            if (uri.rawUserInfo != null) return no(Rejection.USER_INFO)
            if (uri.host.isNullOrEmpty()) return no(Rejection.NOT_A_URL)
            return Validation(text, null)
        }

        /** `null` if [validateEndpoint] refuses [raw]. */
        fun normalizeEndpoint(raw: String): String? = validateEndpoint(raw).url

        /**
         * [url] with everything after the host dropped — keyed endpoints
         * carry the API key in their path, which must stay out of logcat
         * and error pages.
         */
        fun redact(url: String): String {
            val uri = runCatching { URI(url) }.getOrNull() ?: return "<endpoint>"
            val host = uri.host ?: return "<endpoint>"
            val port = if (uri.port >= 0) ":${uri.port}" else ""
            val rest = if (!uri.rawPath.isNullOrEmpty() && uri.rawPath != "/") "/…" else ""
            return "${uri.scheme}://$host$port$rest"
        }

        /** "••••1a2b": enough of a key to tell which one is saved. */
        fun maskKey(key: String): String {
            val k = key.trim()
            return if (k.length <= 4) "••••" else "••••" + k.takeLast(4)
        }

        // ---- storage encoding (see NodeSettings) ----

        internal fun encodeList(list: List<String>): String = JSONArray(list).toString()

        internal fun decodeList(json: String?): List<String> {
            if (json.isNullOrBlank()) return emptyList()
            val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
            return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
        }

        internal fun encodeKeys(keys: Map<String, String>): String =
            JSONObject().apply { keys.forEach { (id, k) -> put(id, k) } }.toString()

        internal fun decodeKeys(json: String?): Map<String, String> {
            if (json.isNullOrBlank()) return emptyMap()
            val obj = runCatching { JSONObject(json) }.getOrNull() ?: return emptyMap()
            val ids = KEYED_PROVIDERS.map { it.id }.toSet()
            return obj.keys().asSequence()
                .filter { it in ids }
                .mapNotNull { id -> obj.optString(id).trim().takeIf { it.isNotEmpty() }?.let { id to it } }
                .toMap()
        }
    }
}

/**
 * "Test" on the settings page: ask [url] for `eth_chainId` and check
 * it's Ethereum mainnet. Blocking; call off the main thread.
 */
object RpcEndpointCheck {
    sealed class Outcome {
        data class Ok(val latencyMs: Long) : Outcome()
        data class WrongChain(val chainId: String) : Outcome()
        data class Failed(val message: String) : Outcome()
    }

    fun check(url: String): Outcome = check(url, EnsHttp.Default)

    internal fun check(url: String, http: EnsHttp): Outcome {
        val started = System.nanoTime()
        val reply = try {
            http.request(
                method = "POST",
                url = url,
                headers = mapOf("content-type" to "application/json", "accept" to "application/json"),
                body = """{"jsonrpc":"2.0","id":1,"method":"eth_chainId","params":[]}""",
                timeoutMs = 10_000,
                maxBytes = 64 * 1024,
                followRedirects = true,
            )
        } catch (e: Exception) {
            return Outcome.Failed(e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName)
        }
        val latency = (System.nanoTime() - started) / 1_000_000
        if (reply.code !in 200..299) return Outcome.Failed("HTTP ${reply.code}")
        val json = runCatching { JSONObject(reply.body) }.getOrNull()
            ?: return Outcome.Failed("not a JSON-RPC endpoint")
        json.optJSONObject("error")?.let {
            return Outcome.Failed(it.optString("message").ifBlank { "RPC error" })
        }
        val chain = json.optString("result", "")
        if (chain.isEmpty()) return Outcome.Failed("not a JSON-RPC endpoint")
        val id = chain.removePrefix("0x").removePrefix("0X").toLongOrNull(16)
            ?: return Outcome.Failed("not a JSON-RPC endpoint")
        return if (id == 1L) Outcome.Ok(latency) else Outcome.WrongChain(id.toString())
    }
}
