package baby.freedom.mobile.browser

import baby.freedom.mobile.ens.EnsResolver
import baby.freedom.mobile.ens.EnsResult
import baby.freedom.swarm.SwarmNode
import kotlinx.coroutines.runBlocking

/**
 * Process-wide holder + router for the local content gateways.
 *
 * The ant gateway lives on a fixed port so [SwarmNode.GATEWAY_URL] is a
 * compile-time constant; the IPFS gateway binds to an ephemeral port at
 * startup, so the UI process mirrors it into [ipfsBase] whenever the
 * `:node` process broadcasts a new [baby.freedom.swarm.IpfsInfo].
 *
 * Since the virtual-origin switch the WebView never loads gateway URLs
 * directly: [toLoadable] hands it a per-root `https://….freedom.baby`
 * URL (see [VirtualOrigin]) and the interceptor translates back to the
 * gateway via [gatewayUrlFor]. [toGatewayUrl] keeps the direct mapping
 * for the pieces that talk to the node itself ([GatewayProbe], the
 * interceptor).
 */
object Gateways {
    const val SWARM_BASE: String = SwarmNode.GATEWAY_URL

    /**
     * Base URL of the embedded IPFS gateway (e.g.
     * `http://127.0.0.1:58312`), or `""` when IPFS isn't running.
     *
     * `@Volatile` because the AIDL callback that writes this value runs
     * on the Binder thread while readers (webview interceptors,
     * suspend navigation gates) live on both the UI and IO threads.
     */
    @Volatile
    var ipfsBase: String = ""
        private set

    fun setIpfsBase(base: String) {
        ipfsBase = base
    }

    /**
     * Shared ENS resolver. One instance so the submit flow and the
     * request interceptor (which resolves `<name>.ens.…` hosts) share
     * a cache and never disagree mid-session.
     */
    val ensResolver: EnsResolver by lazy { EnsResolver() }

    /**
     * Blocking ENS lookup used by the request interceptor. A seam so the
     * instrumented WebView suite can answer for a fixture name without
     * reaching a real RPC; production always goes through [ensResolver].
     */
    @Volatile
    internal var ensLookup: (String) -> EnsResult =
        { name -> runBlocking { ensResolver.resolveContenthash(name) } }

    /**
     * Rewrite a user-facing URL (`bzz://` / `ipfs://` / `ipns://` /
     * `ens://`) to what the WebView should load: the per-root virtual
     * https origin. Ids that can't be label-encoded (malformed refs)
     * fall back to the direct gateway URL so the gateway's own error
     * surfaces instead of `ERR_UNKNOWN_URL_SCHEME`. `http(s)://` and
     * other external URLs are returned unchanged.
     */
    fun toLoadable(url: String): String {
        VirtualOrigin.toVirtualUrl(url)?.let { return it }
        return toGatewayUrl(url)
    }

    /**
     * Rewrite a user-facing URL to the direct
     * `http://127.0.0.1:<port>/…` gateway URL — the pre-virtual-origin
     * [toLoadable]. Used by [GatewayProbe] (which polls the node, not
     * the WebView) and as the malformed-id fallback above. IPFS URLs
     * pass through unchanged while the IPFS node hasn't published a
     * gateway yet.
     */
    fun toGatewayUrl(url: String): String {
        if (url.startsWith("bzz://")) return SwarmResolver.toLoadable(url)
        if (IpfsGateway.isIpfsScheme(url)) return IpfsGateway.toLoadable(url, ipfsBase)
        return url
    }

    /**
     * Inverse of [toLoadable]: map a loaded URL (virtual-origin https
     * or raw gateway http) back to the user-facing display form.
     * Returns the input unchanged if it's neither.
     */
    fun toDisplay(url: String): String {
        VirtualOrigin.displayUrlFor(url)?.let { return it }
        val swarm = SwarmResolver.toDisplay(url)
        if (swarm != url) return swarm
        if (ipfsBase.isNotEmpty()) {
            val ipfs = IpfsGateway.toDisplay(url, ipfsBase)
            if (ipfs != url) return ipfs
        }
        return url
    }

    /** Does [url] belong to any currently-active local gateway origin? */
    fun isLocalGateway(url: String): Boolean {
        if (url.startsWith("$SWARM_BASE/")) return true
        val ipfs = ipfsBase
        return ipfs.isNotEmpty() && url.startsWith("$ipfs/")
    }

    /**
     * Translate a [ContentRoot] + path/query into the gateway URL that
     * actually serves it — the interceptor's core mapping. Returns
     * `null` when the backing node isn't available (no IPFS gateway
     * yet) or an ENS name doesn't resolve; the interceptor turns that
     * into a clean synthesized error instead of a hanging load.
     */
    fun gatewayUrlFor(root: ContentRoot, pathAndQuery: String): String? = when (root) {
        is ContentRoot.Bzz -> "$SWARM_BASE/bzz/${root.ref}$pathAndQuery"
        is ContentRoot.Ipfs -> ipfsBase.ifEmpty { null }?.let { "$it/ipfs/${root.cid}$pathAndQuery" }
        is ContentRoot.IpnsKey -> ipfsBase.ifEmpty { null }?.let { "$it/ipns/${root.key}$pathAndQuery" }
        is ContentRoot.IpnsName -> ipfsBase.ifEmpty { null }?.let { "$it/ipns/${root.name}$pathAndQuery" }
        is ContentRoot.Ens -> resolveEnsRoot(root.name)?.let { gatewayUrlFor(it, pathAndQuery) }
    }

    /**
     * `<name>.ens.…` hosts serve whatever the name's contenthash points
     * at *right now* — the origin stays name-derived (storage survives
     * content updates), resolution happens here per request.
     *
     * The submit flow resolves + records every name before navigating,
     * so the registry hit is the hot path; the blocking fallback covers
     * subresource fetches after a process restart (restored tab) and
     * uses the resolver's own TTL cache after the first call. Blocking
     * is fine — the interceptor never runs on the UI thread.
     */
    private fun resolveEnsRoot(name: String): ContentRoot? {
        KnownEnsNames.uriFor(name)?.let { uri ->
            VirtualOrigin.parseContentUrl(uri)?.let { return it.first }
        }
        val result = ensLookup(name)
        if (result is EnsResult.Ok) {
            KnownEnsNames.record(result.uri, name)
            return VirtualOrigin.parseContentUrl(result.uri)?.first
        }
        return null
    }

    /**
     * Resolve [name] again for a main-frame document on its
     * `<name>.ens.…` host, and make the session registry follow the
     * answer (#99).
     *
     * The registry is what [gatewayUrlFor] serves a name from, and only
     * the submit flow ever wrote it — so Back / Forward (the bar, the
     * system gesture, a page's own `history.back()`), which restore a
     * history entry without going through submit, used to serve the
     * page from whatever the name resolved to when it was first
     * visited, however long ago. Every document load now takes the same
     * lookup a typed navigation does: the resolver's answer, subject to
     * the resolver's own freshness rules and nothing older. Subresources
     * keep the registry hot path; they belong to a document that was
     * just checked.
     *
     * Returns `null` when the name resolved to loadable content (the
     * registry now holds it), or the [ErrorPage] code for the page the
     * user should see instead. A failure leaves the registry untouched:
     * other tabs on the name keep their subresources, and the failed
     * history entry is what Reload retries.
     */
    fun reverifyEnsDocument(name: String): String? =
        when (val result = ensLookup(name)) {
            is EnsResult.Ok -> {
                if (VirtualOrigin.parseContentUrl(result.uri) == null) {
                    "ens_unsupported_codec"
                } else {
                    KnownEnsNames.record(result.uri, name)
                    null
                }
            }
            is EnsResult.NotFound -> "ens_not_found"
            is EnsResult.Unsupported -> "ens_unsupported_codec"
            is EnsResult.Error -> "ens_lookup_failed"
        }
}
