package baby.freedom.mobile.browser

/**
 * Path containment for dweb requests (#534, desktop #814).
 *
 * The interceptor picks the content root, `/<namespace>/<reference>/`,
 * from the URL's host and appends the request's path to it
 * ([Gateways.gatewayUrlFor]). Chromium resolves `.`/`..` and `%2e`
 * segments of an https virtual origin's path, but keeps `%2F`, `%5C` and
 * escapes inside a segment, and a raw `ipfs:`/`ipns:`/`bzz:` subresource
 * URL isn't a special scheme at all. The gateway request then goes out
 * through `java.net.URL`, which normalizes nothing. So
 * `ipfs://<cid>/..%2f..%2fapi/v0/id` reaches the node or gateway as
 * written, and one that decodes before it resolves dot segments serves
 * another root, or its own API, under the CID's origin.
 *
 * The rules are desktop's (`src/main/lib/gateway-path.js`, after
 * freedom-core's fc-dweb `check_path`/`stays_beneath`):
 *  - `%2E`, `%2F` and `%5C` are decoded in one pass, in any case, and
 *    every other escape is left as it is (`%252F` stays `%252F`);
 *  - with `/` and `\` both separating, no segment may then be `.` or `..`;
 *  - an encoded separator that doesn't climb (`a%2Fb`) passes, except
 *    under `/bzz/`, where any `%2F` or `%5C` is refused;
 *  - a literal `\` is refused: an HTTP client may read it as `/`.
 */
internal object DwebPath {
    /**
     * Marks the interceptor's own refusal, so `onReceivedHttpError` leaves
     * a refused navigation showing why, rather than the "not found yet"
     * page and its Try Again. Stripped from every gateway response
     * ([HEADERS_TO_STRIP]), so only the app sets it.
     */
    const val REFUSAL_HEADER = "X-Dweb-Path-Refused"

    /** Whether [headers] mark the interceptor's own refusal ([REFUSAL_HEADER]). */
    fun isRefusal(headers: Map<String, String>?): Boolean =
        headers?.keys?.any { it.equals(REFUSAL_HEADER, ignoreCase = true) } == true

    private val DOTS_AND_SEPARATORS = Regex("%(?:2[eEfF]|5[cC])")
    private val ENCODED_SEPARATOR = Regex("%(?:2[fF]|5[cC])")
    private val SEPARATORS = Regex("[/\\\\]")

    /** `%2E`, `%2F` and `%5C`, in any case, decoded in one pass. */
    fun decodeDotsAndSeparators(path: String): String =
        DOTS_AND_SEPARATORS.replace(path) { m ->
            when (m.value[1].lowercaseChar() to m.value[2].lowercaseChar()) {
                '2' to 'e' -> "."
                '2' to 'f' -> "/"
                else -> "\\"
            }
        }

    /** An encoded `/` or `\` (`%2F`, `%5C`, either case). */
    fun hasEncodedSeparator(path: String): Boolean = ENCODED_SEPARATOR.containsMatchIn(path)

    /** Any segment, decoded as above, that is `.` or `..`. */
    private fun hasDotSegment(path: String): Boolean =
        decodeDotsAndSeparators(path).split(SEPARATORS).any { it == "." || it == ".." }

    /** [pathAndQuery]'s path: up to the first `?` or `#`. */
    fun pathnameOf(pathAndQuery: String): String =
        pathAndQuery.substringBefore('?').substringBefore('#')

    /**
     * Whether a request path (no query) is safe to append to a content
     * root: no literal `\`, no dot segment once decoded, and, when
     * [encodedSeparators] is false (`bzz:`), no encoded separator at all.
     */
    fun isContainedPath(path: String, encodedSeparators: Boolean = true): Boolean {
        if ('\\' in path) return false
        if (!encodedSeparators && hasEncodedSeparator(path)) return false
        return !hasDotSegment(path)
    }

    /**
     * Whether a path the gateway URL is built from — `/<namespace>/<ref>`
     * followed by whatever the request and a name's record appended, query
     * included — stays beneath `/<namespace>/<ref>` as it will be sent.
     * `java.net.URL` sends a path as written, so a literal `..` counts as
     * leaving (desktop's `new URL` resolves it first; stricter here).
     */
    fun isContainedTarget(rootedPath: String, encodedSeparators: Boolean = true): Boolean {
        val path = pathnameOf(rootedPath)
        val parts = path.split('/')
        if (parts.size < 3 || parts[0] != "" || parts[1].isEmpty() || parts[2].isEmpty()) return false
        if (!isContainedPath(path, encodedSeparators)) return false
        val decoded = decodeDotsAndSeparators(path).split(SEPARATORS)
        return decoded[1] == parts[1] && decoded[2] == parts[2]
    }
}

/**
 * What of a gateway URL may reach the log: the gateway, never the path
 * (`http://127.0.0.1:1633/bzz/<ref>/diary.txt` → `http://127.0.0.1:1633/<redacted>`),
 * which names the content and the page asked for (#534).
 */
internal fun gatewayUrlForLog(url: String): String {
    val schemeEnd = url.indexOf("://")
    if (schemeEnd <= 0) return "<redacted>"
    val rest = url.substring(schemeEnd + 3)
    val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
    return url.substring(0, schemeEnd + 3) + (if (end >= 0) rest.substring(0, end) else rest) + "/<redacted>"
}

/**
 * What of a dweb page URL (`ipfs://<cid>/…`, or its virtual
 * `https://<cid>.ipfs.…` form, whose host is the content id) may reach
 * the log: the scheme only (#534).
 */
internal fun dwebUrlForLog(url: String): String =
    url.substringBefore("://", "").takeIf { it.isNotEmpty() }?.let { "$it:<redacted>" } ?: "<redacted>"
