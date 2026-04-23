package baby.freedom.mobile.data

/**
 * Maps a page URL to the canonical "origin" string that favicons are
 * keyed by. All pages under the same origin share a cached icon, so
 * the origin is just scheme + authority (host [+ port]) — the path,
 * query, and fragment never contribute.
 *
 * Keeps the non-standard schemes we care about (`ens://`, `bzz://`)
 * in their display form rather than in their Swarm-gateway-rewritten
 * form: a bookmark to `ens://example.eth` and a navigation to
 * `ens://example.eth/about` should end up sharing the same icon, and
 * the icon captured from the gateway-rewritten `http://127.0.0.1:1633`
 * URL must be filed under the ENS origin the user actually sees.
 */
object FaviconOrigin {
    fun from(url: String): String? {
        if (url.isBlank()) return null
        val lower = url.lowercase()
        // Reject internal / non-addressable origins — these aren't
        // things the user can bookmark, and we don't want a stray
        // `about:blank` icon capture to overwrite a legitimate site's
        // cached favicon.
        if (
            lower.startsWith("about:") ||
            lower.startsWith("data:") ||
            lower.startsWith("javascript:") ||
            lower.startsWith("blob:") ||
            lower.startsWith("file:")
        ) {
            return null
        }
        val schemeEnd = url.indexOf("://")
        if (schemeEnd <= 0) return null
        val authStart = schemeEnd + 3
        // Cut at the first path / query / fragment character — leaves
        // us with `scheme://authority`.
        var authEnd = url.length
        for (i in authStart until url.length) {
            val c = url[i]
            if (c == '/' || c == '?' || c == '#') {
                authEnd = i
                break
            }
        }
        if (authEnd == authStart) return null
        return url.substring(0, authEnd)
    }
}
