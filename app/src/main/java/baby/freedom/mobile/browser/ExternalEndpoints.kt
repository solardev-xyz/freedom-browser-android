package baby.freedom.mobile.browser

import java.net.URI

/**
 * External node endpoints (#125): instead of the embedded nodes, the
 * user can point `bzz://` at their own Swarm node (a bee/ant HTTP API
 * that serves `/bzz/<ref>/…`) and `ipfs://` / `ipns://` at an IPFS
 * path gateway (serves `/ipfs/<cid>/…` and `/ipns/<name>/…`), as in the
 * desktop browser's Settings → Nodes.
 *
 * An endpoint is stored as a normalized base URL — scheme, host, port,
 * optional path prefix, no trailing slash — which [Gateways] uses in
 * place of the embedded gateway's base. `""` means "use the embedded
 * node".
 *
 * An external IPFS gateway is **unverified**: the embedded
 * freedom-ipfs reader checks every block against its CID, but bytes
 * from an external gateway are taken as they come, so that gateway is
 * trusted for everything it serves. The Settings rows say so
 * ([IPFS_UNVERIFIED_WARNING]).
 */
object ExternalEndpoints {
    const val MAX_LENGTH = 2048

    const val IPFS_UNVERIFIED_WARNING =
        "Unverified: content from an external gateway isn't checked against its CID, " +
            "so the gateway is trusted for everything it serves. Prefer one you run yourself."

    enum class Rejection { EMPTY, TOO_LONG, NOT_A_URL, SCHEME, QUERY_OR_FRAGMENT, CREDENTIALS }

    data class Validation(val endpoint: String?, val rejection: Rejection?)

    /**
     * Validate a user-typed base URL. Accepts `http` / `https` with a
     * host (a bare `host:port` gets `http://`, the usual shape of a node
     * on the LAN); drops trailing slashes. Refuses a query or fragment —
     * content paths are appended to the base, so either would end up in
     * the middle of every request URL.
     */
    fun validate(raw: String): Validation {
        var text = raw.trim()
        if (text.isEmpty()) return Validation(null, Rejection.EMPTY)
        if (text.length > MAX_LENGTH) return Validation(null, Rejection.TOO_LONG)
        // `http://` goes in front of a bare `host[:port]` only. Something
        // that already starts with a scheme — `http:/nas:1633`,
        // `ftp:host` — is taken as typed (and refused below) rather than
        // turned into `http://http:/…`, a host named `http`.
        if (!text.contains("://")) {
            if (TYPED_SCHEME.containsMatchIn(text)) {
                val scheme = text.substringBefore(':').lowercase()
                val reason = if (scheme == "http" || scheme == "https") Rejection.NOT_A_URL else Rejection.SCHEME
                return Validation(null, reason)
            }
            text = "http://$text"
        }
        val uri = try {
            URI(text)
        } catch (_: Exception) {
            return Validation(null, Rejection.NOT_A_URL)
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return Validation(null, Rejection.SCHEME)
        val authority = uri.rawAuthority ?: return Validation(null, Rejection.NOT_A_URL)
        if (uri.rawQuery != null || uri.rawFragment != null || text.endsWith("?") || text.endsWith("#")) {
            return Validation(null, Rejection.QUERY_OR_FRAGMENT)
        }
        // Nothing sends URL credentials on to the node (the fetch path is
        // plain HttpURLConnection), so a `user:pass@` would only sit in
        // the settings looking like it did something.
        if (uri.rawUserInfo != null || '@' in authority) return Validation(null, Rejection.CREDENTIALS)
        // `URI` only fills in host/port for an RFC 2396 hostname; a LAN
        // name with an underscore (`my_node:1633`) — which HTTP clients
        // resolve fine — comes back as a bare authority. Take those apart
        // here.
        val (rawHost, port) = if (!uri.host.isNullOrEmpty()) {
            uri.host to uri.port
        } else {
            val m = LENIENT_AUTHORITY.matchEntire(authority) ?: return Validation(null, Rejection.NOT_A_URL)
            val p = m.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull()
            if (p != null && p > 65535) return Validation(null, Rejection.NOT_A_URL)
            m.groupValues[1] to (p ?: -1)
        }
        // `URI.host` keeps an IPv6 literal's brackets.
        val host = rawHost.lowercase()
        // `http//nas:1633` (colon forgotten) became `http://http//nas…`.
        if (host == "http" || host == "https") return Validation(null, Rejection.NOT_A_URL)
        val portPart = if (port >= 0) ":$port" else ""
        val path = (uri.rawPath ?: "").trimEnd('/')
        return Validation("$scheme://$host$portPart$path", null)
    }

    /** A scheme typed without `//` (`http:/…`, `ftp:x`): letters, then `:` and no port digit. */
    private val TYPED_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:(?![0-9])")

    /** `host[:port]` with the characters DNS / mDNS names use in practice, `_` included. */
    private val LENIENT_AUTHORITY = Regex("^([A-Za-z0-9](?:[A-Za-z0-9_.-]*[A-Za-z0-9_])?)(?::([0-9]{1,5}))?$")

    /** [validate]'s endpoint, or `null` if [raw] is refused. */
    fun normalize(raw: String): String? = validate(raw).endpoint
}
