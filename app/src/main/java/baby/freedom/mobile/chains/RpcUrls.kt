package baby.freedom.mobile.chains

import baby.freedom.mobile.browser.WhatwgHost
import java.net.URI

/**
 * Which JSON-RPC endpoint URLs a chain may carry (#107) — the desktop
 * browser's `validateRpcUrl` (`network-registry.js`): `https://` to a
 * public host, no user name or password, no `{API_KEY}`-style
 * placeholder. Plain `http://` is allowed only to a loopback host, for a
 * node running on the device itself; LAN and other internal addresses
 * are refused either way — as literals, and as DNS names that spell one
 * out (`10.0.0.1.nip.io`, see [isInternal]) — so a chain definition,
 * including one pulled from chainlist.org, can't name the local network.
 * A public-looking name whose DNS resolves to an internal address can
 * only be caught when connecting, by whatever sends the requests (#108).
 *
 * The same rules gate a URL the user types and every RPC imported from
 * the chainlist.org catalog ([Chainlist]).
 */
object RpcUrls {
    const val MAX_LENGTH = 2048

    enum class Rejection {
        EMPTY, TOO_LONG, NOT_A_URL, NON_ASCII, SCHEME, CREDENTIALS, PLACEHOLDER, INTERNAL_HOST,
    }

    /** [validate]'s answer: the URL to store, or why not. */
    data class Validation(val url: String?, val rejection: Rejection?)

    fun normalize(raw: String): String? = validate(raw).url

    /**
     * Validate [raw] as an RPC endpoint. Accepted URLs are stored as
     * typed, minus surrounding whitespace.
     *
     * The host is read by two parsers — [WhatwgHost] (what a browser
     * would make of the URL) and `java.net.URI` (what the app's own
     * `HttpURLConnection` will connect to) — and a URL they disagree on
     * (`https:\\host`, `https://127.1`, a backslash or odd character in
     * the authority) is refused rather than guessed at: the host that
     * passed the checks below must be the host that gets the request.
     * Non-ASCII is refused outright for the same reason (a domain has
     * a punycode `xn--` form that means the same thing to both).
     */
    fun validate(raw: String): Validation {
        fun no(r: Rejection) = Validation(null, r)
        val text = raw.trim()
        if (text.isEmpty()) return no(Rejection.EMPTY)
        if (text.length > MAX_LENGTH) return no(Rejection.TOO_LONG)
        if ('{' in text || '}' in text) return no(Rejection.PLACEHOLDER)
        if (text.any { it.code > 0x7e }) return no(Rejection.NON_ASCII)
        if (text.any { it.code <= 0x20 }) return no(Rejection.NOT_A_URL)

        val whatwg = WhatwgHost.parse(text) ?: return no(Rejection.NOT_A_URL)
        if (whatwg.scheme != "http" && whatwg.scheme != "https") return no(Rejection.SCHEME)
        val uri = try {
            URI(text)
        } catch (_: Exception) {
            return no(Rejection.NOT_A_URL)
        }
        val uriHost = uri.host?.lowercase() ?: return no(Rejection.NOT_A_URL)
        if (uri.scheme?.lowercase() != whatwg.scheme) return no(Rejection.NOT_A_URL)
        if (!uri.rawSchemeSpecificPart.startsWith("//")) return no(Rejection.NOT_A_URL)
        val host = whatwg.hostname ?: return no(Rejection.NOT_A_URL)
        if (uriHost != host) return no(Rejection.NOT_A_URL)
        if (whatwg.hasCredentials || uri.rawUserInfo != null) return no(Rejection.CREDENTIALS)

        val loopback = isLoopback(host)
        if (!loopback && whatwg.scheme != "https") return no(Rejection.SCHEME)
        if (!loopback && isInternal(host)) return no(Rejection.INTERNAL_HOST)
        return Validation(text, null)
    }

    /** Whether [url] (one [validate] accepted) goes to the device itself. */
    fun isLoopbackUrl(url: String): Boolean =
        WhatwgHost.parse(url)?.hostname?.let(::isLoopback) == true

    /**
     * `localhost` / `*.localhost`, a four-octet `127.0.0.0/8` literal, or
     * `[::1]` — what Chromium counts as loopback. [host] is WHATWG's
     * serialisation, so IPv4 is already dotted-decimal and IPv6 already
     * compressed.
     */
    internal fun isLoopback(host: String): Boolean {
        val h = host.trimEnd('.')
        if (h == "localhost" || h.endsWith(".localhost")) return true
        if (h == "[::1]") return true
        return ipv4(h)?.get(0) == 127
    }

    /**
     * Desktop's `isInternalHostname`: a private, link-local, CGNAT,
     * benchmarking, multicast or reserved IPv4 (also when carried inside
     * an IPv4-mapped, NAT64 or 6to4 IPv6 address); a unique-local,
     * link-local, site-local, Teredo or multicast IPv6; `.local`; or a
     * single-label name (only resolvable on the local network).
     *
     * Beyond desktop, a DNS name that spells out such an address for a
     * wildcard-DNS service to resolve (see [spellsInternalAddress]:
     * `127.0.0.1.nip.io`, `10-0-0-1.sslip.io`, `0a000001.nip.io`,
     * `fe80--1.sslip.io`) or a well-known name for the device itself
     * (`lvh.me`, `*.localtest.me`) counts as internal too. That's what a
     * URL can say about itself; a name whose DNS points at the device or
     * the LAN without spelling it out is only caught at connect time.
     */
    internal fun isInternal(host: String): Boolean {
        val h = host.trimEnd('.')
        if (h.isEmpty()) return true
        if (h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local")) return true
        ipv4(h)?.let { return isInternalIpv4(it) }
        if (h.startsWith("[")) {
            val s = ipv6(h.removePrefix("[").removeSuffix("]")) ?: return true
            return isInternalIpv6(s)
        }
        if ('.' !in h) return true
        if (LOOPBACK_NAMES.any { h == it || h.endsWith(".$it") }) return true
        return spellsInternalAddress(h)
    }

    /** Public domains whose every name resolves to 127.0.0.1. */
    private val LOOPBACK_NAMES = listOf("localtest.me", "lvh.me", "vcap.me", "lacolhost.com", "fuf.me")

    /**
     * Whether DNS name [h] carries an internal address the way wildcard
     * DNS services (nip.io, sslip.io, xip.io, traefik.me, …) read one:
     * four decimal labels in a row (`10.0.0.1.nip.io`), four decimal
     * `-`-pieces in a row inside a label (`app-10-0-0-1.sslip.io`), an
     * 8-hex-digit piece (`0a000001.nip.io`), or a label that is an IPv6
     * address with `-` for `:` (`fe80--1.sslip.io`). Any service, not a
     * list of them — a new one works the same way.
     */
    internal fun spellsInternalAddress(h: String): Boolean {
        val labels = h.split('.')
        fun internalRun(parts: List<String>) = parts.windowed(4).any { run ->
            ipv4(run.joinToString("."))?.let(::isInternalIpv4) == true
        }
        if (internalRun(labels)) return true
        for (label in labels) {
            val pieces = label.split('-')
            if (internalRun(pieces)) return true
            for (piece in pieces) {
                if (piece.length == 8 && piece.all { it in '0'..'9' || it in 'a'..'f' }) {
                    val v = piece.toLong(16)
                    val quad = IntArray(4) { ((v shr (24 - 8 * it)) and 0xff).toInt() }
                    // Multicast/reserved (≥224) and 0/8 are left out here: they
                    // cover 1 in 8 random hex strings, and a public ID like
                    // conduit's `…-e4a1b2c3` isn't an address anyone meant.
                    if (quad[0] in 1..223 && isInternalIpv4(quad)) return true
                }
            }
            if ("--" in label || label.count { it == '-' } >= 7) {
                ipv6(label.replace('-', ':'))?.let { if (isInternalIpv6(it)) return true }
            }
        }
        return false
    }

    private fun isInternalIpv4(p: IntArray): Boolean {
        val (a, b) = p
        return a == 0 || a == 10 || a == 127 ||
            (a == 169 && b == 254) ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 100 && b in 64..127) ||
            (a == 198 && (b == 18 || b == 19)) ||
            a >= 224
    }

    private fun isInternalIpv6(s: IntArray): Boolean {
        fun low32() = intArrayOf(s[6] shr 8, s[6] and 0xff, s[7] shr 8, s[7] and 0xff)
        // ::ffff:a.b.c.d (mapped), 64:ff9b::/96 (NAT64), ::/96 (compatible; covers :: and ::1).
        if ((0..4).all { s[it] == 0 } && s[5] == 0xffff) return isInternalIpv4(low32())
        if (s[0] == 0x64 && s[1] == 0xff9b && (2..5).all { s[it] == 0 }) return isInternalIpv4(low32())
        if ((0..5).all { s[it] == 0 }) return isInternalIpv4(low32())
        // 2002::/16 6to4: the IPv4 sits in the second and third pieces.
        if (s[0] == 0x2002) {
            return isInternalIpv4(intArrayOf(s[1] shr 8, s[1] and 0xff, s[2] shr 8, s[2] and 0xff))
        }
        return (s[0] == 0x64 && s[1] == 0xff9b && s[2] == 1) || // 64:ff9b:1::/48 local-use NAT64
            (s[0] == 0x2001 && s[1] == 0) || // Teredo
            (s[0] and 0xfe00) == 0xfc00 || // fc00::/7 unique local
            (s[0] and 0xffc0) == 0xfe80 || // fe80::/10 link-local
            (s[0] and 0xffc0) == 0xfec0 || // fec0::/10 site-local
            (s[0] and 0xff00) == 0xff00 // multicast
    }

    /** Strict dotted-decimal IPv4 (what WHATWG serialises every IPv4 host to), else `null`. */
    private fun ipv4(h: String): IntArray? {
        val parts = h.split('.')
        if (parts.size != 4) return null
        if (parts.any { it.isEmpty() || it.length > 3 || !it.all { c -> c in '0'..'9' } }) return null
        val n = parts.map { it.toInt() }
        if (n.any { it > 255 }) return null
        return n.toIntArray()
    }

    /** Eight 16-bit pieces of a hex IPv6 literal (`::` compression allowed), else `null`. */
    private fun ipv6(h: String): IntArray? {
        fun pieces(part: String): List<Int>? {
            if (part.isEmpty()) return emptyList()
            return part.split(':').map { p ->
                if (p.isEmpty() || p.length > 4) return null
                p.toIntOrNull(16) ?: return null
            }
        }
        val dbl = h.indexOf("::")
        if (dbl != h.lastIndexOf("::")) return null
        if (dbl < 0) return pieces(h)?.takeIf { it.size == 8 }?.toIntArray()
        val left = pieces(h.substring(0, dbl)) ?: return null
        val right = pieces(h.substring(dbl + 2)) ?: return null
        val fill = 8 - left.size - right.size
        if (fill < 1) return null
        return (left + List(fill) { 0 } + right).toIntArray()
    }
}
