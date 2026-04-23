package baby.freedom.mobile.browser

/**
 * Parses gateway-origin URLs (what the WebView actually loads —
 * `http://127.0.0.1:1633/bzz/<hash>/…`, and in the future
 * `http://127.0.0.1:<ipfs>/ipfs/<cid>/…` etc.) into their content-addressed
 * components, so address-bar rewrites and subresource-escape rewrites can
 * treat bzz/ipfs/ipns uniformly.
 *
 * Hostnames are intentionally not restricted — `BrowserState`'s loadable
 * URLs all come from `SwarmResolver.toLoadable` / the future
 * `IpfsGateway.toLoadable`, and keeping this helper host-agnostic means it
 * doesn't need to know which gateway ports are in use this session.
 */
object GatewayUrls {
    data class Base(val prefix: String, val protocol: String, val hashOrCid: String)

    // Longest-first so that, if new gateway namespaces are added, more
    // specific prefixes win the match.
    private val baseRegex = Regex(
        "^(https?://[^/]+/(bzz)/([a-fA-F0-9]+)|" +
            "https?://[^/]+/(ipfs)/([A-Za-z0-9]+)|" +
            "https?://[^/]+/(ipns)/([A-Za-z0-9.-]+))",
    )

    private val rootRegex = Regex(
        "^(https?://[^/]+/(?:bzz|ipfs|ipns)/[A-Za-z0-9.-]+/)",
    )

    /**
     * `http://host/bzz/<hash>[/…]` → `Base("http://host/bzz/<hash>", "bzz", "<hash>")`.
     * Returns `null` for URLs that aren't a recognized gateway base.
     */
    fun extractBase(url: String): Base? {
        val m = baseRegex.find(url) ?: return null
        val prefix = m.value
        val protocol: String
        val hashOrCid: String
        when {
            m.groupValues[2].isNotEmpty() -> { protocol = "bzz"; hashOrCid = m.groupValues[3] }
            m.groupValues[4].isNotEmpty() -> { protocol = "ipfs"; hashOrCid = m.groupValues[5] }
            else -> { protocol = "ipns"; hashOrCid = m.groupValues[7] }
        }
        return Base(prefix = prefix, protocol = protocol, hashOrCid = hashOrCid)
    }

    /**
     * Like [extractBase] but returns the prefix *with* the trailing slash.
     * Used by the subresource-escape interceptor, which needs to know the
     * current content root (not just the hash) so it can rewrite
     * absolute-root subresource requests back under it.
     */
    fun extractRoot(url: String?): String? {
        if (url == null) return null
        return rootRegex.find(url)?.groupValues?.get(1)
    }
}
