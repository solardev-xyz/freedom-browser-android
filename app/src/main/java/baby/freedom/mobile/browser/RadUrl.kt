package baby.freedom.mobile.browser

import java.net.URLEncoder

/**
 * `rad://` addresses (#124): Radicle repositories, browsed out of the
 * embedded node's storage (#73). The Android port of desktop's
 * `rad:` handling (`rad-protocol.js` + the `rad-browser` page) and iOS's
 * `RadSchemeHandler`.
 *
 * ```
 * address bar shows:   rad://z3gqcJUoA1n9HaHKufZs5FCSGazv5/tree/<sha>/src
 * WebView loads:       https://rad.freedom.baby/z3gqcJUoA1n9HaHKufZs5FCSGazv5/tree/<sha>/src
 * ```
 *
 * WebView has no way to register a scheme of its own, so — like the dweb
 * [VirtualOrigin]s and the onchain apps — the page is loaded from a
 * synthetic https host that `shouldInterceptRequest` answers before any
 * DNS or TLS ([RadApi]). Unlike those, it is one host for every
 * repository: what it serves is the browser's own viewer, not the
 * repository's code, and a repository ID is case-sensitive base58, which
 * a host name (case-folded) can't carry — so the RID goes in the path.
 *
 * `rad:<rid>/…`, the URN form desktop and canopy use, is read too.
 */
object RadUrl {
    const val SCHEME = "rad"
    const val HOST = "rad.${VirtualOrigin.BASE_DOMAIN}"
    const val ORIGIN = "https://$HOST"

    /** Paths under here are the viewer's own files and its read API, never a repository. */
    const val INTERNAL_PREFIX = "/_/"

    /** The read API: `/_/api/<rid>/<endpoint>`, the same endpoints desktop's `rad:` URLs reach. */
    const val API_PREFIX = "${INTERNAL_PREFIX}api/"

    /** Where an address whose repository ID doesn't parse is shown (the viewer says so). */
    const val INVALID_PATH = "${INTERNAL_PREFIX}invalid"

    /** `z` + base58btc (desktop's and iOS's RID check). */
    val RID = Regex("^z[1-9A-HJ-NP-Za-km-z]{20,60}$")

    /** Does [input] use the `rad:` scheme at all (parseable or not)? */
    fun isRadScheme(input: String): Boolean = input.trim().startsWith("$SCHEME:", ignoreCase = true)

    /**
     * The bare RID (`z…`) and the `/path?query#fragment` tail (`""` for
     * the repository's root) of a `rad://<rid>/…` or `rad:<rid>/…` URL, or
     * null if it isn't one or the RID isn't valid. Nothing is case-folded:
     * base58 is case-sensitive.
     */
    fun parse(url: String): Pair<String, String>? {
        val trimmed = url.trim()
        if (!isRadScheme(trimmed)) return null
        var rest = trimmed.substring(SCHEME.length + 1)
        if (rest.startsWith("//")) rest = rest.substring(2)
        val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val rid = if (end < 0) rest else rest.substring(0, end)
        if (!RID.matches(rid)) return null
        val tail = if (end < 0) "" else rest.substring(end)
        return rid to normalizedTail(tail)
    }

    /**
     * What the WebView loads for a `rad:` [url]: the repository's page on
     * [HOST], or — for a `rad:` address whose RID doesn't parse — the
     * viewer's "not a repository ID" page naming it. Null if [url] isn't
     * a `rad:` URL at all.
     */
    fun toVirtualUrl(url: String): String? {
        if (!isRadScheme(url)) return null
        val parsed = parse(url)
        if (parsed != null) return "$ORIGIN/${parsed.first}${parsed.second}"
        val typed = url.trim().substring(SCHEME.length + 1).removePrefix("//")
        return "$ORIGIN$INVALID_PATH?id=${URLEncoder.encode(typed.take(MAX_INVALID_ECHO), "UTF-8")}"
    }

    /**
     * The `rad://` form of a [HOST] URL — for the address bar, history and
     * bookmarks — or null for any other URL and for [HOST]'s own files
     * and API (shown as they are).
     */
    fun displayUrlFor(url: String): String? {
        val path = pathOf(url) ?: return null
        val rid = path.removePrefix("/").substringBefore('/').substringBefore('?').substringBefore('#')
        if (!RID.matches(rid)) return null
        return "$SCHEME://$rid${path.substring(1 + rid.length)}"
    }

    /** Is [url] on [HOST] (any path)? */
    fun isVirtualUrl(url: String?): Boolean = url != null && pathOf(url) != null

    /**
     * The `/path?query#fragment` of a URL on exactly `https://`[HOST] (no
     * userinfo, port or trailing dot, all of which make another origin),
     * or null.
     */
    fun pathOf(url: String): String? {
        val prefix = "$ORIGIN"
        if (!url.startsWith(prefix, ignoreCase = true)) return null
        val rest = url.substring(prefix.length)
        if (rest.isEmpty()) return "/"
        return when (rest[0]) {
            '/' -> rest
            '?', '#' -> "/$rest"
            else -> null
        }
    }

    /** `""` for the root, else the tail with a leading `/` (a query-only tail gets one too). */
    private fun normalizedTail(tail: String): String = when {
        tail.isEmpty() || tail == "/" -> ""
        tail.startsWith("/") -> tail
        else -> "/$tail"
    }

    private const val MAX_INVALID_ECHO = 200
}
