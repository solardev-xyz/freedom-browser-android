package baby.freedom.mobile.browser

import android.icu.text.IDNA

/**
 * Just enough of the WHATWG URL parser (what desktop's `new URL()` runs)
 * to validate a search template the same way desktop does: the scheme,
 * whether there is a user name/password, and the serialised `hostname`.
 *
 * `java.net.URI` is RFC 2396 and much stricter — it has no host for
 * `my_host.example` or `bücher.de`, and throws on `{`/`}` in a query — so
 * templates desktop accepts would be refused. This follows the spec's
 * special-scheme path instead: tab/newline stripping, any mix of `/` and
 * `\` after `https:`, credentials up to the last `@`, a numeric port up to
 * 65535, percent-decoded + UTS-46 domains (see [Uts46]), WHATWG IPv4 (`127.1`, `0x7f.1`)
 * and IPv6 (`[0::1]` → `[::1]`) serialisation. Only `http`/`https` are
 * parsed in full; any other scheme returns with no host, which the caller
 * refuses anyway.
 */
internal object WhatwgHost {
    data class Parsed(val scheme: String, val hostname: String?, val hasCredentials: Boolean)

    /** `null` where `new URL(input)` (no base) would throw. */
    fun parse(input: String): Parsed? {
        val s = input.trim { it <= ' ' }.filterNot { it == '\t' || it == '\n' || it == '\r' }
        val colon = s.indexOf(':')
        if (colon < 1 || !s[0].isAsciiLetter()) return null
        val scheme = s.substring(0, colon)
        if (!scheme.all { it.isAsciiLetter() || it in '0'..'9' || it in "+-." }) return null
        val lower = scheme.lowercase()
        if (lower != "http" && lower != "https") return Parsed(lower, null, false)

        var rest = s.substring(colon + 1).trimStart('/', '\\')
        val end = rest.indexOfFirst { it in "/\\?#" }
        val authority = if (end < 0) rest else rest.substring(0, end)
        val at = authority.lastIndexOf('@')
        val userInfo = if (at >= 0) authority.substring(0, at) else ""
        rest = if (at >= 0) authority.substring(at + 1) else authority

        // Host and port split at the first ':' outside brackets.
        var inBracket = false
        var split = -1
        for ((i, c) in rest.withIndex()) {
            if (c == '[') inBracket = true
            if (c == ']') inBracket = false
            if (c == ':' && !inBracket) { split = i; break }
        }
        val rawHost = if (split < 0) rest else rest.substring(0, split)
        if (split >= 0) {
            val port = rest.substring(split + 1)
            if (!port.all { it in '0'..'9' }) return null
            if (port.isNotEmpty() && port.trimStart('0').length > 5) return null
            if (port.isNotEmpty() && port.toInt() > 65535) return null
        }
        if (rawHost.isEmpty()) return null
        val hostname = host(rawHost) ?: return null
        // Username is before the first ':', password after; both empty
        // (`https://@h`, `https://:@h`) is what `new URL` treats as none.
        return Parsed(lower, hostname, userInfo.isNotEmpty() && userInfo != ":")
    }

    /**
     * WHATWG "domain to ASCII" (beStrict = false): an ASCII domain with no
     * `xn--` label is only lowercased; anything else goes through UTS-46.
     */
    fun domainToAscii(domain: String): String? {
        if (domain.all { it.code < 0x80 } &&
            domain.split('.').none { it.asciiLowercase().startsWith("xn--") }
        ) {
            return domain.asciiLowercase()
        }
        return uts46.toAscii(domain)?.takeIf { it.isNotEmpty() }
    }

    private fun String.asciiLowercase() =
        String(CharArray(length) { this[it].let { c -> if (c in 'A'..'Z') c + 32 else c } })

    /**
     * UTS-46 ToASCII of a whole domain with WHATWG's options, or `null` on
     * an error WHATWG doesn't ignore.
     *
     * This must be real UTS-46 (ICU), not `java.net.IDN`: IDNA2003 maps
     * the deviation characters (`ß`, ZWJ …) and *deletes* others such as
     * U+1806, so `http://\u1806localhost/` would pass the loopback check
     * as `localhost` while Chromium resolves `xn--localhost-uf3c`.
     */
    fun interface Uts46 {
        fun toAscii(domain: String): String?

        /**
         * UTS-46 ToUnicode of an ASCII (punycode) domain, for display only,
         * or `null` on any error.
         */
        fun toUnicode(domain: String): String? = null

        companion object {
            /** Chromium's `url_idna_icu` options: nontransitional, CheckBidi, (and WHATWG's) CheckJoiners. */
            const val OPTIONS = IDNA.NONTRANSITIONAL_TO_ASCII or IDNA.CHECK_BIDI or IDNA.CHECK_CONTEXTJ

            /** Errors WHATWG ignores: CheckHyphens and VerifyDnsLength are both off. */
            val IGNORED_ERRORS = setOf(
                "EMPTY_LABEL", "LABEL_TOO_LONG", "DOMAIN_NAME_TOO_LONG",
                "LEADING_HYPHEN", "TRAILING_HYPHEN", "HYPHEN_3_4",
            )
        }
    }

    /** The platform's ICU (`android.icu`, API 24+). */
    private object AndroidUts46 : Uts46 {
        private val idna by lazy { IDNA.getUTS46Instance(Uts46.OPTIONS) }

        override fun toAscii(domain: String): String? {
            val out = StringBuilder()
            val info = IDNA.Info()
            idna.nameToASCII(domain, out, info)
            if (info.errors.any { it.name !in Uts46.IGNORED_ERRORS }) return null
            return out.toString()
        }

        override fun toUnicode(domain: String): String? {
            val out = StringBuilder()
            val info = IDNA.Info()
            idna.nameToUnicode(domain, out, info)
            if (info.hasErrors()) return null
            return out.toString()
        }
    }

    /** Swapped for icu4j in JVM unit tests, where `android.icu` is a stub. */
    @Volatile
    internal var uts46: Uts46 = AndroidUts46

    private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'

    private fun host(raw: String): String? {
        if (raw.startsWith("[")) {
            if (!raw.endsWith("]")) return null
            return ipv6(raw.substring(1, raw.length - 1))?.let { "[${serialiseIpv6(it)}]" }
        }
        val ascii = domainToAscii(percentDecode(raw)) ?: return null
        if (ascii.isEmpty() || ascii.any { it.code <= 0x20 || it.code == 0x7F || it in "#%/:<>?@[\\]^|" }) {
            return null
        }
        if (endsInNumber(ascii)) return ipv4(ascii)?.let(::serialiseIpv4)
        return ascii
    }

    internal fun percentDecode(s: String): String {
        if ('%' !in s) return s
        fun Byte.isHexByte() = toInt().toChar().isHex()
        val raw = s.toByteArray(Charsets.UTF_8)
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < raw.size) {
            if (raw[i] == '%'.code.toByte() && i + 2 < raw.size &&
                raw[i + 1].isHexByte() && raw[i + 2].isHexByte()
            ) {
                bytes.write(String(raw, i + 1, 2, Charsets.US_ASCII).toInt(16))
                i += 3
            } else {
                bytes.write(raw[i].toInt())
                i++
            }
        }
        return bytes.toByteArray().toString(Charsets.UTF_8)
    }

    private fun endsInNumber(host: String): Boolean {
        val parts = host.split('.').toMutableList()
        if (parts.last().isEmpty()) {
            if (parts.size == 1) return false
            parts.removeAt(parts.lastIndex)
        }
        val last = parts.last()
        if (last.isNotEmpty() && last.all { it in '0'..'9' }) return true
        return last.startsWith("0x") && last.substring(2).all { it in '0'..'9' || it in 'a'..'f' }
    }

    private fun ipv4Number(part: String): Long? {
        if (part.isEmpty()) return null
        val (digits, radix) = when {
            part.startsWith("0x") -> part.substring(2) to 16
            part.length > 1 && part.startsWith("0") -> part.substring(1) to 8
            else -> part to 10
        }
        if (digits.isEmpty()) return 0
        // Arbitrary length (`0000000001`); anything past 2^32 fails the caller's range check.
        if (!digits.all { Character.digit(it, radix) >= 0 }) return null
        val n = digits.toBigInteger(radix)
        return if (n.bitLength() > 40) Long.MAX_VALUE else n.toLong()
    }

    private fun ipv4(host: String): Long? {
        val parts = host.split('.').toMutableList()
        if (parts.last().isEmpty() && parts.size > 1) parts.removeAt(parts.lastIndex)
        if (parts.size > 4) return null
        val numbers = parts.map { ipv4Number(it) ?: return null }
        if (numbers.dropLast(1).any { it > 255 }) return null
        val lastLimit = 1L shl (8 * (5 - numbers.size))
        if (numbers.last() >= lastLimit) return null
        var ipv4 = numbers.last()
        numbers.dropLast(1).forEachIndexed { i, n -> ipv4 += n shl (8 * (3 - i)) }
        return ipv4
    }

    private fun serialiseIpv4(a: Long) =
        (3 downTo 0).joinToString(".") { ((a shr (8 * it)) and 0xFF).toString() }

    /** WHATWG IPv6 parser; the eight 16-bit pieces or `null`. */
    private fun ipv6(s: String): IntArray? {
        val pieces = IntArray(8)
        var piece = 0
        var compress = -1
        var i = 0
        if (s.startsWith(":")) {
            if (!s.startsWith("::")) return null
            i = 2; piece = 1; compress = 1
        }
        while (i < s.length) {
            if (piece == 8) return null
            if (s[i] == ':') {
                if (compress >= 0) return null
                i++; piece++; compress = piece
                continue
            }
            var value = 0
            var length = 0
            while (length < 4 && i < s.length && s[i].isHex()) {
                value = value * 16 + s[i].digitToInt(16); i++; length++
            }
            if (i < s.length && s[i] == '.') {
                if (length == 0) return null
                i -= length
                if (piece > 6) return null
                var seen = 0
                while (i < s.length) {
                    var v = -1
                    if (seen > 0) {
                        if (s[i] == '.' && seen < 4) i++ else return null
                    }
                    if (i >= s.length || s[i] !in '0'..'9') return null
                    while (i < s.length && s[i] in '0'..'9') {
                        val n = s[i] - '0'
                        v = when (v) { -1 -> n; 0 -> return null; else -> v * 10 + n }
                        if (v > 255) return null
                        i++
                    }
                    pieces[piece] = pieces[piece] * 0x100 + v
                    seen++
                    if (seen == 2 || seen == 4) piece++
                }
                if (seen != 4) return null
                break
            } else if (i < s.length && s[i] == ':') {
                i++
                if (i >= s.length) return null
            } else if (i < s.length) {
                return null
            }
            pieces[piece] = value
            piece++
        }
        if (compress >= 0) {
            var swaps = piece - compress
            piece = 7
            while (piece != 0 && swaps > 0) {
                val t = pieces[piece]; pieces[piece] = pieces[compress + swaps - 1]
                pieces[compress + swaps - 1] = t
                piece--; swaps--
            }
        } else if (piece != 8) {
            return null
        }
        return pieces
    }

    private fun Char.isHex() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private fun serialiseIpv6(p: IntArray): String {
        // Compress the first longest run of two or more zero pieces.
        var best = -1; var bestLen = 1
        var i = 0
        while (i < 8) {
            if (p[i] == 0) {
                var j = i
                while (j < 8 && p[j] == 0) j++
                if (j - i > bestLen) { best = i; bestLen = j - i }
                i = j
            } else i++
        }
        val sb = StringBuilder()
        var ignore0 = false
        for (k in 0 until 8) {
            if (ignore0 && p[k] == 0) continue
            ignore0 = false
            if (k == best) {
                sb.append(if (k == 0) "::" else ":")
                ignore0 = true
                continue
            }
            sb.append(Integer.toHexString(p[k]))
            if (k != 7) sb.append(':')
        }
        return sb.toString()
    }
}
