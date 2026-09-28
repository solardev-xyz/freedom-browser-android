package baby.freedom.mobile.ens

import baby.freedom.mobile.chains.Chain
import baby.freedom.mobile.chains.RpcUrls
import baby.freedom.mobile.chains.rpc.ChainDataRouter
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
 *
 * How this relates to the per-chain RPCs (#108, #189):
 *
 * - [customEndpoints] *are* Ethereum mainnet's own RPCs
 *   ([baby.freedom.mobile.chains.Chain.userRpcUrls], kept by
 *   `ChainStore`): one list, edited from either page, that every
 *   mainnet read — names here, `web3://` apps and the chain-data router
 *   there — puts first.
 * - [apiKeys] stay name resolution's own for now: the chain-data router
 *   only takes key-free RPCs (a key in a URL is what its chainlist
 *   filter strips out), and giving it keyed providers needs its own
 *   decision about which of its reads may spend them.
 * - [PUBLIC_ENDPOINTS] stay a separate list because the resolver's
 *   quorum is built for, and has been tested against, these endpoints'
 *   ENS/CCIP behaviour; the chain page's public RPCs are chainlist's.
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

    val enabledPublicEndpoints: List<String>
        get() = PUBLIC_ENDPOINTS.filter { it !in disabledPublicEndpoints }

    /**
     * Every endpoint the resolver will try, in order, each once. Yours
     * and keyed ones are de-duplicated by [endpointKey]
     * (`https://eth.drpc.org/` is `https://eth.drpc.org`); a public one
     * is skipped altogether when one of yours or a keyed one is already
     * run by the same provider ([ChainDataRouter.providerOf]) — yours
     * takes that provider's seat ([publicSkippedFor]). The quorum counts
     * one vote per provider anyway ([EnsQuorum.voters]).
     */
    val sources: List<Source>
        get() {
            val seen = HashSet<String>()
            val out = ArrayList<Source>()
            for (url in customEndpoints) {
                if (seen.add(endpointKey(url))) out += Source(Kind.CUSTOM, "Your endpoint", url)
            }
            for (provider in KEYED_PROVIDERS) {
                val key = apiKeys[provider.id]?.trim().orEmpty()
                if (key.isEmpty()) continue
                val url = provider.urlFor(key)
                if (seen.add(endpointKey(url))) out += Source(Kind.KEYED, provider.name, url)
            }
            val yours = out.map { ChainDataRouter.providerOf(it.url) }.toSet()
            for (url in enabledPublicEndpoints) {
                if (ChainDataRouter.providerOf(url) in yours) continue
                if (seen.add(endpointKey(url))) out += Source(Kind.PUBLIC, "Public", url)
            }
            return out
        }

    val endpoints: List<String> get() = sources.map { it.url }

    /**
     * How many different providers [endpoints] come from
     * ([EnsQuorum.voters]) — what the cross-check counts, not the
     * number of URLs.
     */
    val providerCount: Int get() = EnsQuorum.voters(endpoints).size

    /**
     * The one of your endpoints, or keyed providers, that public
     * endpoint [url] is skipped for ([sources]): run by the same
     * provider. `null` when it isn't skipped for one.
     */
    fun publicSkippedFor(url: String): Source? {
        val provider = ChainDataRouter.providerOf(url)
        return sources.firstOrNull { it.kind != Kind.PUBLIC && ChainDataRouter.providerOf(it.url) == provider }
    }

    /** What [EnsResolver] needs from this, compared to spot a change. */
    val resolverSettings: EnsResolver.Settings
        get() = EnsResolver.Settings(endpoints = endpoints, ccipRead = ccipRead)

    /**
     * Whether removing one of the user's own endpoints / switching off a
     * public one / deleting a provider's key still leaves the resolver
     * something to ask. The settings page greys out the control that
     * would take the last endpoint away, and `NodeSettings` refuses the
     * write. Each is judged on the configuration the change would
     * produce, not on [endpoints]: an endpoint listed both as your own
     * and as a public one shows once in the order, yet dropping either
     * copy leaves the other.
     */
    fun canRemoveCustom(url: String): Boolean =
        copy(customEndpoints = customEndpoints - url).endpoints.isNotEmpty()

    fun canDisablePublic(url: String): Boolean =
        copy(disabledPublicEndpoints = disabledPublicEndpoints + url).endpoints.isNotEmpty()

    fun canRemoveKey(providerId: String): Boolean =
        copy(apiKeys = apiKeys - providerId).endpoints.isNotEmpty()

    /** Whether [url] is already one of the user's own endpoints ([endpointKey]). */
    fun hasCustomEndpoint(url: String): Boolean {
        val key = endpointKey(url)
        return customEndpoints.any { endpointKey(it) == key }
    }

    /**
     * Whether [url] is on the host of one of the built-in
     * [PUBLIC_ENDPOINTS] ([publicEndpointHost]): the resolver already
     * asks that server, under its own switch, so it isn't taken as one
     * of yours.
     */
    fun isPublicEndpoint(url: String): Boolean = publicEndpointHost(url) != null

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

        /** Your own endpoints are Ethereum mainnet's own RPCs ([baby.freedom.mobile.data.ChainStore]). */
        const val MAX_CUSTOM_ENDPOINTS = Chain.MAX_USER_RPC_URLS

        /**
         * Check a user-typed RPC endpoint by the rules every chain's RPC
         * is held to ([RpcUrls.validate]): `https://` to a public host,
         * or `http://` to a node on this device; no user name or
         * password. Your endpoints *are* mainnet's own RPCs, so they
         * pass the same check wherever they're added.
         */
        fun validateEndpoint(raw: String): RpcUrls.Validation = RpcUrls.validate(raw)

        /**
         * What two endpoint URLs are compared by to tell whether they
         * are the same endpoint — the request the app would actually
         * send, not the spelling: scheme and host are case-insensitive
         * and a host's trailing `.` is dropped; a scheme's default port
         * (`https://eth.drpc.org:443`) is no port; a percent-escape of
         * an unreserved character (`/%65th`) is that character and other
         * escapes compare case-insensitively; `.`/`..` path segments are
         * then resolved — escaped ones (`/%2e%2e/`) too, since the HTTP
         * client sends those resolved as well; a run of `/` is one `/`
         * (`//eth`); a trailing `/` on the
         * path, an empty `?` query, and any `#fragment` (never sent),
         * make no difference.
         */
        fun endpointKey(url: String): String {
            val uri = runCatching { URI(url.trim()) }.getOrNull()
            val scheme = uri?.scheme?.lowercase()
            val host = uri?.host?.lowercase()?.trimEnd('.')
            if (uri == null || scheme == null || host == null) return url.trim().trimEnd('/')
            val defaultPort = when (scheme) {
                "https" -> 443
                "http" -> 80
                else -> -1
            }
            val port = if (uri.port >= 0 && uri.port != defaultPort) ":${uri.port}" else ""
            // Decode first, then resolve: `%2e%2e` is only a `..` segment
            // once decoded. Runs of `/` are one (`//eth` is `/eth`), as
            // most servers read them.
            val path = removeDotSegments(canonicalEscapes(uri.rawPath.orEmpty()).replace(SLASHES, "/")).trimEnd('/')
            val query = uri.rawQuery?.takeIf { it.isNotEmpty() }?.let { "?" + canonicalEscapes(it) }.orEmpty()
            return "$scheme://$host$port$path$query"
        }

        /**
         * [path] with its `.` and `..` segments resolved (RFC 3986
         * §5.2.4); a `..` above the root stays at the root.
         */
        private fun removeDotSegments(path: String): String {
            if (!path.startsWith('/')) return path
            val out = ArrayList<String>()
            val segments = path.substring(1).split('/')
            for ((i, seg) in segments.withIndex()) {
                val last = i == segments.lastIndex
                when (seg) {
                    "." -> if (last) out.add("")
                    ".." -> {
                        if (out.isNotEmpty()) out.removeAt(out.lastIndex)
                        if (last) out.add("")
                    }
                    else -> out.add(seg)
                }
            }
            return "/" + out.joinToString("/")
        }

        /**
         * [raw] with each `%XX` of an unreserved character (RFC 3986:
         * letters, digits, `-._~`) decoded and every other escape's hex
         * upper-cased — the forms a server can't tell apart.
         */
        private fun canonicalEscapes(raw: String): String {
            if ('%' !in raw) return raw
            val out = StringBuilder(raw.length)
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                val hex = if (c == '%' && i + 2 < raw.length && raw[i + 1].isHex() && raw[i + 2].isHex()) {
                    raw.substring(i + 1, i + 3).toInt(16)
                } else {
                    null
                }
                if (hex == null) {
                    out.append(c)
                    i++
                    continue
                }
                val ch = hex.toChar()
                if (ch.isLetterOrDigit() && ch.code < 0x80 || ch in "-._~") {
                    out.append(ch)
                } else {
                    out.append('%').append(raw.substring(i + 1, i + 3).uppercase())
                }
                i += 3
            }
            return out.toString()
        }

        private val SLASHES = Regex("/{2,}")

        private fun Char.isHex() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

        /**
         * The host [url] shares with one of the built-in
         * [PUBLIC_ENDPOINTS], or `null`. Compared by host, not by the
         * whole URL: what path, query or slashes a server answers the
         * same is up to the server (`https://eth.drpc.org/?x=1`,
         * `https://1rpc.io//eth` are the built-ins), so any URL on a
         * built-in's host is that built-in. Such a URL is refused as one
         * of yours ("already provided by <host>"); another host of the
         * same provider (`lb.drpc.org`) is taken, and then counts as that
         * provider's one vote ([EnsQuorum.voters]) in place of the
         * built-in ([sources]).
         */
        fun publicEndpointHost(url: String): String? {
            val host = hostKey(url) ?: return null
            return host.takeIf { h -> PUBLIC_ENDPOINTS.any { hostKey(it) == h } }
        }

        private fun hostKey(url: String): String? =
            runCatching { URI(url.trim()).host }.getOrNull()?.lowercase()?.trimEnd('.')?.takeIf { it.isNotEmpty() }

        /** `null` if [validateEndpoint] refuses [raw]. */
        fun normalizeEndpoint(raw: String): String? = RpcUrls.normalize(raw)

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
