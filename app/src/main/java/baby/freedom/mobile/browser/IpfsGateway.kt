package baby.freedom.mobile.browser

/**
 * Maps between the user-facing `ipfs://` / `ipns://` URL schemes and
 * the embedded Kubo HTTP gateway that actually serves the content.
 *
 *   User-facing:  `ipfs://<cid>[/path]`   `ipns://<name>[/path]`
 *   Loadable:     `http://127.0.0.1:<port>/ipfs/<cid>[/path]`
 *                 `http://127.0.0.1:<port>/ipns/<name>[/path]`
 *
 * Parallel to [SwarmResolver]. The Kubo gateway port is not fixed —
 * [baby.freedom.swarm.IpfsNode] starts the listener on `127.0.0.1:0`
 * and the kernel picks a free port at startup — so both directions
 * take the current `base` (`"http://127.0.0.1:<port>"`) as a parameter.
 * Empty `base` means "IPFS isn't running"; rewrites pass the URL
 * through unchanged so the caller gets a consistent shape back.
 */
object IpfsGateway {
    private const val IPFS_PREFIX: String = "ipfs://"
    private const val IPNS_PREFIX: String = "ipns://"

    fun toLoadable(url: String, base: String): String {
        if (base.isEmpty()) return url
        return when {
            url.startsWith(IPFS_PREFIX) -> "$base/ipfs/" + url.removePrefix(IPFS_PREFIX)
            url.startsWith(IPNS_PREFIX) -> "$base/ipns/" + url.removePrefix(IPNS_PREFIX)
            else -> url
        }
    }

    fun toDisplay(url: String, base: String): String {
        if (base.isEmpty()) return url
        val ipfsPrefix = "$base/ipfs/"
        val ipnsPrefix = "$base/ipns/"
        return when {
            url.startsWith(ipfsPrefix) -> IPFS_PREFIX + url.removePrefix(ipfsPrefix)
            url.startsWith(ipnsPrefix) -> IPNS_PREFIX + url.removePrefix(ipnsPrefix)
            else -> url
        }
    }

    fun isIpfsScheme(url: String): Boolean =
        url.startsWith(IPFS_PREFIX) || url.startsWith(IPNS_PREFIX)
}
