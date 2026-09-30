package baby.freedom.mobile.browser

import baby.freedom.mobile.ens.EnsInput

/**
 * One spelling per page for bookmarks (#264, #296 R1-F1).
 *
 * The star saves the page's address as the address bar shows it
 * ([BrowserState.url]): the WebView's own serialisation for a web page
 * (`http://localhost:8730/`, lowercase host, a `/` path), the display form
 * for a dweb page (`vitalik.eth`, or `ipfs://vitalik.eth/` once the name
 * has resolved, #97). An edited address is typed, so the same page can
 * arrive as `localhost:8730`, `HTTPS://Example.com` or `ens://x.eth`.
 *
 *  - [canonical] is what an edited address is saved as: the spelling the
 *    page itself will report, as far as that can be known without loading
 *    it.
 *  - [key] is what "is this the same bookmark" compares — the star's
 *    filled state, Remove, re-adding, and the edit dialog's duplicate
 *    check all go through it, so they can't disagree with each other. It
 *    also forgets the transport a name's page is shown under
 *    (`ipfs://vitalik.eth` and `vitalik.eth` are one bookmark), which
 *    depends on how the name resolves today, not on what was saved.
 *
 * `key(canonical(x)) == key(x)` for every address.
 */
internal object BookmarkUrls {

    private val contentSchemes = listOf("bzz", "ipfs", "ipns")

    fun canonical(url: String): String {
        val trimmed = url.trim()
        EnsInput.parse(trimmed)?.let { return it.name + tail(it.suffix) }
        EnsInput.parseConstrained(trimmed)?.let { return "${it.protocol}://${it.name}${tail(it.suffix)}" }
        RadUrl.parse(trimmed)?.let { (rid, rest) -> return "${RadUrl.SCHEME}://$rid$rest" }
        contentSchemes.firstOrNull { trimmed.startsWith("$it://", ignoreCase = true) }?.let { scheme ->
            val rest = trimmed.substring(scheme.length + 3)
            val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
            val id = if (end < 0) rest else rest.substring(0, end)
            // A Swarm reference is hex (case-free); CIDs and IPNS keys
            // can be case-sensitive, so they stay as typed.
            val root = if (scheme == "bzz") id.lowercase() else id
            return "$scheme://$root${tail(if (end < 0) "" else rest.substring(end))}"
        }
        return web(trimmed) ?: trimmed
    }

    fun key(url: String): String {
        val c = canonical(url)
        EnsInput.parseConstrained(c)?.let { return it.name + tail(it.suffix) }
        return c
    }

    /** A dweb address's path: none for the root, and a leading `/` before a bare `?`/`#`. */
    private fun tail(t: String): String = when {
        t.isEmpty() || t == "/" -> ""
        t.startsWith("/") -> t
        else -> "/$t"
    }

    /**
     * An `http`/`https` URL the way Chromium serialises it: lowercase
     * scheme, the WHATWG host ([WhatwgHost.host]: lowercase, punycode,
     * IPv4 and IPv6 in their canonical forms), no default port, and a `/`
     * path at least. Null for anything else, or a host that doesn't parse.
     */
    private fun web(url: String): String? {
        val colon = url.indexOf("://")
        if (colon < 0) return null
        val scheme = url.substring(0, colon).lowercase()
        if (scheme != "http" && scheme != "https") return null
        val rest = url.substring(colon + 3)
        val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' || it == '\\' }
        val authority = if (end < 0) rest else rest.substring(0, end)
        var path = if (end < 0) "" else rest.substring(end)
        val at = authority.lastIndexOf('@')
        val userInfo = if (at >= 0) authority.substring(0, at + 1) else ""
        val hostPort = authority.substring(at + 1)
        var inBracket = false
        var split = -1
        for ((i, c) in hostPort.withIndex()) {
            if (c == '[') inBracket = true
            if (c == ']') inBracket = false
            if (c == ':' && !inBracket) { split = i; break }
        }
        val rawHost = if (split < 0) hostPort else hostPort.substring(0, split)
        if (rawHost.isEmpty()) return null
        val host = runCatching { WhatwgHost.host(rawHost) }.getOrNull() ?: return null
        var port = if (split < 0) "" else hostPort.substring(split + 1)
        if (!port.all { it in '0'..'9' }) return null
        if (port.isNotEmpty()) {
            port = port.trimStart('0').ifEmpty { "0" }
            if (port.length > 5 || port.toInt() > 65535) return null
        }
        val default = if (scheme == "http") "80" else "443"
        val portPart = if (port.isEmpty() || port == default) "" else ":$port"
        if (path.isEmpty() || path[0] == '?' || path[0] == '#') path = "/$path"
        return "$scheme://$userInfo$host$portPart$path"
    }
}
