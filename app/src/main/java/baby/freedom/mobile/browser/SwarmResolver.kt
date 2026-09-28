package baby.freedom.mobile.browser

import baby.freedom.swarm.SwarmNode

/**
 * Maps between the user-facing `bzz://` URL scheme and the local ant
 * gateway that actually serves the content.
 *
 * User-facing (what we show in the address bar):  `bzz://<hash>[/path]`
 * Loadable (what we hand to the WebView):         `http://127.0.0.1:1633/bzz/<hash>[/path]`
 *
 * Both directions are pure string rewrites — no hex validation here, because
 * the gateway itself is the source of truth about what's resolvable.
 *
 * The embedded node's gateway URL lives on [SwarmNode.GATEWAY_URL]; an
 * external Swarm endpoint (#125) replaces it, so every direction takes
 * the gateway `base` to use ([Gateways.swarmBase] in production). Keep
 * this file as the only place that knows how `/bzz/...` nests under it.
 */
object SwarmResolver {
    private const val BZZ_PREFIX: String = "bzz://"

    /** `bzz://xyz[/path]` → `<base>/bzz/xyz[/path]`, otherwise unchanged. */
    fun toLoadable(url: String, base: String = SwarmNode.GATEWAY_URL): String {
        if (!url.startsWith(BZZ_PREFIX)) return url
        val rest = url.removePrefix(BZZ_PREFIX)
        return "$base/bzz/" + rootSlash(rest)
    }

    /**
     * `<hash>` → `<hash>/`: a bare root must reach the WebView with a
     * trailing slash so the page's *relative* URLs resolve under the
     * manifest instead of under `/bzz/` (where ant 400s them as
     * non-hex). bee enforced this shape with a 301 on bare roots; ant
     * serves the bare form directly, so the browser normalizes — the
     * exact twin of [IpfsGateway]'s rootSlash. Sites with absolute
     * asset paths (swarm.eth) never hit this; sites with relative ones
     * (freemap.eth's leaflet.js/map.js) render blank without it.
     */
    private fun rootSlash(rest: String): String =
        if (rest.isNotEmpty() && rest.none { it == '/' || it == '?' || it == '#' }) "$rest/" else rest

    /** `<base>/bzz/xyz[/path]` → `bzz://xyz[/path]`, otherwise unchanged. */
    fun toDisplay(url: String, base: String = SwarmNode.GATEWAY_URL): String {
        val prefix = "$base/bzz/"
        if (!url.startsWith(prefix)) return url
        val rest = url.removePrefix(prefix)
        return BZZ_PREFIX + rest
    }

    fun isSwarm(url: String, base: String = SwarmNode.GATEWAY_URL): Boolean =
        url.startsWith(BZZ_PREFIX) || url.startsWith("$base/bzz/")
}
