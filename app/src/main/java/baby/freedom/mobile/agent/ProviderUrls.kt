package baby.freedom.mobile.agent

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Which server URLs a LAN provider (Ollama, OpenAI-compatible) may use
 * (#573), desktop's `normalizeCompatibleUrl` and audit finding L7: HTTPS
 * anywhere, plain HTTP only to this phone or the local network.
 *
 * "Local" is judged on the host exactly as OkHttp, which dials it, parses
 * it ([HttpUrl]), and the [HttpUrl] judged is the one requests are built
 * from, never a string parsed again. Plain HTTP is allowed only to:
 *  - `localhost` (which [ProviderClient] pins to loopback itself rather
 *    than asking DNS);
 *  - a four-part decimal IPv4 literal in 127.0.0.0/8, 10.0.0.0/8,
 *    172.16.0.0/12 or 192.168.0.0/16. Short, hex and octal forms
 *    (`127.1`, `0x7f.0.0.1`, `2130706433`) are names to OkHttp, and a name
 *    is never local over HTTP;
 *  - an IPv6 literal `[::1]` or in fc00::/7.
 *
 * A LAN DNS name needs HTTPS or its private IP address, as on desktop.
 * Unlike desktop, Ollama isn't limited to loopback: on a phone it runs on
 * another machine, so it gets the same local-network rule.
 *
 * Also refused, whatever the scheme: user info (a password in a URL),
 * a query or fragment, a URL over 2048 characters, and `.onion` names,
 * which would otherwise be looked up through system DNS outside Tor.
 */
internal object ProviderUrls {
    const val MAX_LENGTH = 2048

    enum class Rejection { Invalid, Scheme, Credentials, QueryOrFragment, PublicHttp, Onion }

    data class Result(val url: HttpUrl?, val rejection: Rejection?)

    /**
     * [input] as a LAN server's base URL, trailing slashes dropped, or why
     * not. Leading and trailing whitespace is ignored.
     */
    fun validate(input: String): Result {
        val text = input.trim()
        if (text.isEmpty() || text.length > MAX_LENGTH) return Result(null, Rejection.Invalid)
        val scheme = text.substringBefore("://", "").lowercase()
        if (scheme != "http" && scheme != "https") return Result(null, Rejection.Scheme)
        val url = text.toHttpUrlOrNull() ?: return Result(null, Rejection.Invalid)
        return check(url)
    }

    /**
     * [url] judged by the same rules; used again before every request, so
     * a stored URL saved under older rules can't be dialled under them.
     */
    fun check(url: HttpUrl): Result {
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return Result(null, Rejection.Credentials)
        if (url.query != null || url.fragment != null) return Result(null, Rejection.QueryOrFragment)
        val host = url.host
        val name = host.trimEnd('.')
        if (name == "onion" || name.endsWith(".onion")) return Result(null, Rejection.Onion)
        if (url.scheme == "http" && !isLocalHost(host)) return Result(null, Rejection.PublicHttp)
        val trimmed = url.newBuilder().apply {
            // "http://host:11434/v1/" and "…/v1" are the same server.
            val segments = url.pathSegments.toMutableList()
            while (segments.isNotEmpty() && segments.last().isEmpty()) segments.removeAt(segments.lastIndex)
            encodedPath("/")
            for (s in segments) addPathSegment(s)
        }.build()
        if (trimmed.toString().length > MAX_LENGTH) return Result(null, Rejection.Invalid)
        return Result(trimmed, null)
    }

    /** [url] as text without the trailing slash OkHttp keeps on an empty path. */
    fun display(url: HttpUrl): String = url.toString().removeSuffix("/")

    /** Whether [host] (as [HttpUrl.host] gives it) is this phone or a private network address. */
    fun isLocalHost(host: String): Boolean {
        if (host == "localhost") return true
        if (':' in host) return isLocalIpv6(host)
        val octets = strictIpv4(host) ?: return false
        val (a, b) = octets
        return a == 127 || a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
    }

    /** A four-part decimal dotted quad, each part 0–255 with no leading zero; else null. */
    internal fun strictIpv4(host: String): List<Int>? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        return parts.map { p ->
            if (p.isEmpty() || p.length > 3 || p.any { it !in '0'..'9' } || (p.length > 1 && p[0] == '0')) return null
            p.toInt().takeIf { it <= 255 } ?: return null
        }
    }

    /** OkHttp gives an IPv6 host canonical and unbracketed: `::1`, `fd00::1`. */
    private fun isLocalIpv6(host: String): Boolean {
        if (host == "::1") return true
        val first = host.substringBefore(':')
        if (first.isEmpty() || first.length > 4) return false
        val word = first.toIntOrNull(16) ?: return false
        // fc00::/7: the first 7 bits are 1111110. A 1–3 digit group is the low bits of a 16-bit word.
        return first.length == 4 && (word and 0xFE00) == 0xFC00
    }
}
