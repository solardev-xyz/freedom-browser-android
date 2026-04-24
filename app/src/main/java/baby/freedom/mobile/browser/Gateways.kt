package baby.freedom.mobile.browser

import baby.freedom.swarm.SwarmNode

/**
 * Process-wide holder + router for the local content gateways.
 *
 * The Bee gateway lives on a fixed port so [SwarmNode.GATEWAY_URL] is a
 * compile-time constant; the Kubo gateway binds to an ephemeral port at
 * startup, so the UI process mirrors it into [ipfsBase] whenever the
 * `:node` process broadcasts a new [baby.freedom.swarm.IpfsInfo].
 *
 * This object exists so the rest of the browser layer — [BrowserState],
 * [BrowserWebView], [BrowserScreen] — doesn't care about which protocol
 * a URL belongs to: they hand a `bzz://` / `ipfs://` / `ipns://` URL in
 * and get a loadable `http://127.0.0.1:…/…` back, and vice-versa.
 */
object Gateways {
    const val SWARM_BASE: String = SwarmNode.GATEWAY_URL

    /**
     * Base URL of the embedded Kubo gateway (e.g.
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
     * Rewrite a user-facing URL (`bzz://` / `ipfs://` / `ipns://`) to
     * the gateway-backed HTTP URL the WebView can actually fetch.
     * `http(s)://` and other external URLs are returned unchanged, as
     * are IPFS URLs when the Kubo node hasn't published a gateway yet.
     */
    fun toLoadable(url: String): String {
        if (url.startsWith("bzz://")) return SwarmResolver.toLoadable(url)
        if (IpfsGateway.isIpfsScheme(url)) return IpfsGateway.toLoadable(url, ipfsBase)
        return url
    }

    /**
     * Inverse of [toLoadable]: map a loaded gateway URL back to the
     * user-facing scheme. Returns the input unchanged if it isn't one
     * of the local gateway origins.
     */
    fun toDisplay(url: String): String {
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
}
