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
        if (!text.contains("://")) text = "http://$text"
        val uri = try {
            URI(text)
        } catch (_: Exception) {
            return Validation(null, Rejection.NOT_A_URL)
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return Validation(null, Rejection.SCHEME)
        if (uri.host.isNullOrEmpty()) return Validation(null, Rejection.NOT_A_URL)
        if (uri.rawQuery != null || uri.rawFragment != null || text.endsWith("?") || text.endsWith("#")) {
            return Validation(null, Rejection.QUERY_OR_FRAGMENT)
        }
        // Nothing sends URL credentials on to the node (the fetch path is
        // plain HttpURLConnection), so a `user:pass@` would only sit in
        // the settings looking like it did something.
        if (uri.rawUserInfo != null) return Validation(null, Rejection.CREDENTIALS)
        // `URI.host` keeps an IPv6 literal's brackets.
        val host = uri.host.lowercase()
        val port = if (uri.port >= 0) ":${uri.port}" else ""
        val path = (uri.rawPath ?: "").trimEnd('/')
        return Validation("$scheme://$host$port$path", null)
    }

    /** [validate]'s endpoint, or `null` if [raw] is refused. */
    fun normalize(raw: String): String? = validate(raw).endpoint
}
