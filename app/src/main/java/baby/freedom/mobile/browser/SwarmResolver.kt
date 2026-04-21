package baby.freedom.mobile.browser

import baby.freedom.swarm.SwarmNode

/**
 * Maps between the user-facing `bzz://` URL scheme and the local bee-lite
 * gateway that actually serves the content.
 *
 * User-facing (what we show in the address bar):  `bzz://<hash>[/path]`
 * Loadable (what we hand to the WebView):         `http://127.0.0.1:1633/bzz/<hash>[/path]`
 *
 * Both directions are pure string rewrites — no hex validation here, because
 * the gateway itself is the source of truth about what's resolvable.
 *
 * The gateway URL lives on [SwarmNode.GATEWAY_URL] — keep this file as the
 * only place that knows how `/bzz/...` nests under it.
 */
object SwarmResolver {
    private const val BZZ_PREFIX: String = "bzz://"
    private const val GATEWAY_BZZ_PREFIX: String = "${SwarmNode.GATEWAY_URL}/bzz/"

    /** `bzz://xyz[/path]` → `http://127.0.0.1:1633/bzz/xyz[/path]`, otherwise unchanged. */
    fun toLoadable(url: String): String {
        if (!url.startsWith(BZZ_PREFIX)) return url
        val rest = url.removePrefix(BZZ_PREFIX)
        return GATEWAY_BZZ_PREFIX + rest
    }

    /** `http://127.0.0.1:1633/bzz/xyz[/path]` → `bzz://xyz[/path]`, otherwise unchanged. */
    fun toDisplay(url: String): String {
        if (!url.startsWith(GATEWAY_BZZ_PREFIX)) return url
        val rest = url.removePrefix(GATEWAY_BZZ_PREFIX)
        return BZZ_PREFIX + rest
    }

    fun isSwarm(url: String): Boolean =
        url.startsWith(BZZ_PREFIX) || url.startsWith(GATEWAY_BZZ_PREFIX)
}
