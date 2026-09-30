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

    /**
     * A dweb address's path, query and fragment: none for the root, a
     * leading `/` before a bare `?`/`#`, and otherwise the way Chromium
     * serialises them ([pathQueryFragment]) — a dweb page is loaded as a
     * virtual `https://` URL and the address bar shows that URL's own
     * path and query back ([VirtualOrigin.displayUrlFor]).
     */
    private fun tail(t: String): String {
        if (t.isEmpty()) return ""
        val c = pathQueryFragment(if (t[0] == '/' || t[0] == '\\') t else "/$t")
        return if (c == "/") "" else c
    }

    /**
     * An `http`/`https` URL the way Chromium serialises it: lowercase
     * scheme, the WHATWG host ([WhatwgHost.host]: lowercase, punycode,
     * IPv4 and IPv6 in their canonical forms), no default port, and a
     * path, query and fragment as [pathQueryFragment] has them. Null for
     * anything else, or a host that doesn't parse.
     */
    private fun web(url: String): String? {
        val colon = url.indexOf("://")
        if (colon < 0) return null
        val scheme = url.substring(0, colon).lowercase()
        if (scheme != "http" && scheme != "https") return null
        val rest = url.substring(colon + 3)
        val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' || it == '\\' }
        val authority = if (end < 0) rest else rest.substring(0, end)
        val path = if (end < 0) "" else rest.substring(end)
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
        val tail = pathQueryFragment(if (path.isEmpty() || path[0] == '?' || path[0] == '#') "/$path" else path)
        return "$scheme://$userInfo$host$portPart$tail"
    }

    /**
     * An `http(s)` URL's path (starting `/` or `\`), query and fragment
     * the way Chromium's URL parser serialises them (WHATWG, special
     * scheme; #296 R3-F1), so a typed `/x/../Straße/` is saved as the
     * `/Stra%C3%9Fe/` the page reports:
     *
     *  - tabs and newlines are dropped, `\` in the path is `/`;
     *  - `.` and `..` segments (also as `%2e`, any case) are resolved;
     *  - each part is UTF-8 percent-encoded with its own set — C0
     *    controls, DEL and non-ASCII always, plus space `"` `<` `>` and
     *    `` ` `` `{` `}` `|` `^` in the path, space `"` `'` `<` `>` in
     *    the query, and space `"` `<` `>` `` ` `` in the fragment. A lone
     *    surrogate is U+FFFD. An escape already there is kept as typed
     *    (Chromium neither decodes nor re-cases it).
     *
     * Checked against Chromium 153's `new URL()`, and on the WebView
     * itself by `BookmarkUrlsWebViewTest`.
     */
    internal fun pathQueryFragment(tail: String): String {
        val t = tail.filter { it != '\t' && it != '\n' && it != '\r' }
        val hash = t.indexOf('#')
        val beforeHash = if (hash < 0) t else t.substring(0, hash)
        val q = beforeHash.indexOf('?')
        val path = (if (q < 0) beforeHash else beforeHash.substring(0, q)).replace('\\', '/')
        val out = StringBuilder(t.length + 8)
        val segments = path.removePrefix("/").split('/')
        val kept = ArrayList<String>(segments.size)
        for ((i, seg) in segments.withIndex()) {
            val last = i == segments.lastIndex
            when (seg.lowercase()) {
                "..", ".%2e", "%2e.", "%2e%2e" -> {
                    kept.removeLastOrNull()
                    if (last) kept.add("")
                }
                ".", "%2e" -> if (last) kept.add("")
                else -> kept.add(seg)
            }
        }
        out.append('/')
        kept.forEachIndexed { i, seg ->
            if (i > 0) out.append('/')
            encode(seg, PATH_SET, out)
        }
        if (q >= 0) {
            out.append('?')
            encode(beforeHash.substring(q + 1), QUERY_SET, out)
        }
        if (hash >= 0) {
            out.append('#')
            encode(t.substring(hash + 1), FRAGMENT_SET, out)
        }
        return out.toString()
    }

    private const val PATH_SET = " \"<>`{}|^"
    private const val QUERY_SET = " \"'<>"
    private const val FRAGMENT_SET = " \"<>`"

    private fun encode(s: String, set: String, out: StringBuilder) {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val cp: Int
            if (Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1])) {
                cp = Character.toCodePoint(c, s[i + 1])
                i += 2
            } else {
                cp = if (Character.isSurrogate(c)) 0xFFFD else c.code
                i += 1
            }
            if (cp in 0x21..0x7E && set.indexOf(cp.toChar()) < 0) {
                out.append(cp.toChar())
            } else {
                for (b in String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)) {
                    val v = b.toInt() and 0xFF
                    out.append('%').append(HEX[v shr 4]).append(HEX[v and 0xF])
                }
            }
        }
    }

    private const val HEX = "0123456789ABCDEF"
}
