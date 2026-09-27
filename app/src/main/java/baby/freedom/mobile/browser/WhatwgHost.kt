package baby.freedom.mobile.browser

import java.net.IDN
import java.text.Normalizer

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
 * 65535, percent-decoded + UTS-46 domains (see [label]), WHATWG IPv4 (`127.1`, `0x7f.1`)
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

    private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'

    private fun host(raw: String): String? {
        if (raw.startsWith("[")) {
            if (!raw.endsWith("]")) return null
            return ipv6(raw.substring(1, raw.length - 1))?.let { "[${serialiseIpv6(it)}]" }
        }
        val ascii = percentDecode(raw).split('.', '\u3002', '\uFF0E', '\uFF61')
            .map { label(it) ?: return null }
            .joinToString(".")
        if (ascii.isEmpty() || ascii.any { it.code <= 0x20 || it.code == 0x7F || it in "#%/:<>?@[\\]^|" }) {
            return null
        }
        if (endsInNumber(ascii)) return ipv4(ascii)?.let(::serialiseIpv4)
        return ascii
    }

    /**
     * One label of UTS-46 ToASCII as WHATWG runs it (non-transitional,
     * CheckJoiners on, CheckHyphens off), or `null` where it fails.
     *
     * `java.net.IDN` is IDNA2003, which differs from that in two ways that
     * matter here: it maps the "deviation" characters `ß`, `ς`, ZWJ and
     * ZWNJ away (`local\u200Dhost` would become `localhost` — the
     * loopback check must not see that host), and it never looks inside
     * an all-ASCII `xn--` label. So IDN is only used to case-fold and
     * NFKC-normalise the runs *between* deviation characters; those are
     * kept, a joiner must sit in its RFC 5892 CONTEXTJ context, and the
     * punycode is produced (and, for `xn--` input, checked) here.
     */
    private fun label(input: String): String? {
        if (input.all { it.code < 0x80 }) {
            val lower = input.asciiLowercase()
            if (!lower.startsWith("xn--")) return lower
            // `new URL` decodes it and requires a valid, canonical, non-ASCII label.
            val decoded = Punycode.decode(lower.substring(4)) ?: return null
            if (decoded.all { it.code < 0x80 } || !validUnicodeLabel(decoded)) return null
            if (map(decoded) != decoded || Punycode.encode(decoded) != lower.substring(4)) return null
            return lower
        }
        val mapped = map(input) ?: return null
        if ('.' in mapped) return null
        if (mapped.all { it.code < 0x80 }) return label(mapped)
        if (mapped.startsWith("xn--") || !validUnicodeLabel(mapped)) return null
        return "xn--" + (Punycode.encode(mapped) ?: return null)
    }

    private fun String.asciiLowercase() =
        String(CharArray(length) { this[it].let { c -> if (c in 'A'..'Z') c + 32 else c } })

    private const val ZWNJ = 0x200C
    private const val ZWJ = 0x200D

    /** UTS-46 deviation characters: valid as-is, where IDNA2003 maps them. */
    private val DEVIATIONS = setOf(0xDF, 0x3C2, ZWNJ, ZWJ)

    /** Case-fold + NFKC via IDNA2003's nameprep, leaving [DEVIATIONS] alone. */
    private fun map(label: String): String? {
        val out = StringBuilder()
        val run = StringBuilder()
        fun flush(): Boolean {
            if (run.isEmpty()) return true
            val ace = runCatching { IDN.toASCII(run.toString(), IDN.ALLOW_UNASSIGNED) }.getOrNull()
                ?: return false
            out.append(IDN.toUnicode(ace, IDN.ALLOW_UNASSIGNED).asciiLowercase())
            run.clear()
            return true
        }
        for (cp in label.codePoints()) {
            // IDN's own unassigned table is Unicode 3.2; UTS-46 disallows what the current one leaves unassigned.
            if (Character.getType(cp) == Character.UNASSIGNED.toInt()) return null
            if (cp in DEVIATIONS) {
                if (!flush()) return null
                out.appendCodePoint(cp)
            } else {
                run.appendCodePoint(cp)
            }
        }
        return if (flush()) out.toString() else null
    }

    /** UTS-46 validity checks IDNA2003 doesn't make: no leading mark, CONTEXTJ joiners. */
    private fun validUnicodeLabel(label: String): Boolean {
        val cps = label.codePoints().toArray()
        if (cps.isEmpty() || cps[0].isMark()) return false
        return cps.indices.all { i -> (cps[i] != ZWJ && cps[i] != ZWNJ) || joinerAllowed(cps, i) }
    }

    private fun Int.isMark() = Character.getType(this).let {
        it == Character.NON_SPACING_MARK.toInt() || it == Character.COMBINING_SPACING_MARK.toInt() ||
            it == Character.ENCLOSING_MARK.toInt()
    }

    /**
     * RFC 5892 CONTEXTJ: either joiner right after a virama; ZWNJ also
     * between two joining letters (`(L|D) T* ZWNJ T* (R|D)`). Java has no
     * Joining_Type table, so "joining letter" is approximated as a letter
     * of a cursive-joining script — a label that passes this way is never
     * one of the loopback names, which is what the check guards.
     */
    private fun joinerAllowed(cps: IntArray, i: Int): Boolean {
        if (i > 0 && isVirama(cps[i - 1])) return true
        if (cps[i] == ZWJ) return false
        fun transparent(cp: Int) = cp != ZWJ && cp != ZWNJ &&
            Character.getType(cp).let {
                it == Character.NON_SPACING_MARK.toInt() || it == Character.ENCLOSING_MARK.toInt() ||
                    it == Character.FORMAT.toInt()
            }
        var before = i - 1
        while (before >= 0 && transparent(cps[before])) before--
        var after = i + 1
        while (after < cps.size && transparent(cps[after])) after++
        return before >= 0 && after < cps.size && joins(cps[before]) && joins(cps[after])
    }

    private val JOINING_SCRIPTS = setOf(
        Character.UnicodeScript.ARABIC, Character.UnicodeScript.SYRIAC, Character.UnicodeScript.NKO,
        Character.UnicodeScript.MONGOLIAN, Character.UnicodeScript.MANDAIC, Character.UnicodeScript.PHAGS_PA,
    )

    private fun joins(cp: Int) = Character.isLetter(cp) && Character.UnicodeScript.of(cp) in JOINING_SCRIPTS

    /**
     * Canonical_Combining_Class == 9 (Virama), read off the platform's own
     * normaliser: canonical reordering swaps two adjacent marks when the
     * first has the higher class, so [cp] must move behind U+3099 (ccc 8)
     * and ahead of U+05B0 (ccc 10).
     */
    private fun isVirama(cp: Int): Boolean {
        val m = String(Character.toChars(cp))
        if (Normalizer.normalize(m, Normalizer.Form.NFD) != m) return false
        return Normalizer.normalize("a$m\u3099", Normalizer.Form.NFD) == "a\u3099$m" &&
            Normalizer.normalize("a\u05B0$m", Normalizer.Form.NFD) == "a$m\u05B0"
    }

    /** RFC 3492 Punycode for IDNA (`xn--` label bodies). `null` on invalid input or overflow. */
    private object Punycode {
        private const val BASE = 36
        private const val TMIN = 1
        private const val TMAX = 26

        private fun adapt(delta: Long, points: Int, first: Boolean): Int {
            var d = if (first) delta / 700 else delta / 2
            d += d / points
            var k = 0
            while (d > ((BASE - TMIN) * TMAX) / 2) {
                d /= BASE - TMIN
                k += BASE
            }
            return (k + (BASE - TMIN + 1) * d / (d + 38)).toInt()
        }

        private fun threshold(k: Int, bias: Int) = when {
            k <= bias -> TMIN
            k >= bias + TMAX -> TMAX
            else -> k - bias
        }

        private fun digit(d: Long) = if (d < 26) 'a' + d.toInt() else '0' + (d.toInt() - 26)

        fun encode(input: String): String? {
            val cps = input.codePoints().toArray()
            val out = StringBuilder()
            cps.filter { it < 0x80 }.forEach { out.append(it.toChar()) }
            val basic = out.length
            var handled = basic
            if (basic > 0) out.append('-')
            var n = 0x80
            var delta = 0L
            var bias = 72
            while (handled < cps.size) {
                val m = cps.filter { it >= n }.min()
                delta += (m - n).toLong() * (handled + 1)
                if (delta > Int.MAX_VALUE) return null
                n = m
                for (c in cps) {
                    if (c < n) delta++
                    if (c != n) continue
                    var q = delta
                    var k = BASE
                    while (true) {
                        val t = threshold(k, bias)
                        if (q < t) break
                        out.append(digit(t + (q - t) % (BASE - t)))
                        q = (q - t) / (BASE - t)
                        k += BASE
                    }
                    out.append(digit(q))
                    bias = adapt(delta, handled + 1, handled == basic)
                    delta = 0
                    handled++
                }
                delta++
                n++
            }
            return out.toString()
        }

        fun decode(input: String): String? {
            val delimiter = input.lastIndexOf('-')
            val out = ArrayList<Int>()
            if (delimiter >= 0) {
                for (c in input.substring(0, delimiter)) {
                    if (c.code >= 0x80) return null
                    out.add(c.code)
                }
            }
            var pos = delimiter + 1
            var n = 0x80L
            var i = 0L
            var bias = 72
            while (pos < input.length) {
                val oldI = i
                var w = 1L
                var k = BASE
                while (true) {
                    if (pos >= input.length) return null
                    val c = input[pos++]
                    val d = when (c) {
                        in 'a'..'z' -> c - 'a'
                        in 'A'..'Z' -> c - 'A'
                        in '0'..'9' -> c - '0' + 26
                        else -> return null
                    }
                    i += d * w
                    if (i > Int.MAX_VALUE) return null
                    val t = threshold(k, bias)
                    if (d < t) break
                    w *= BASE - t
                    if (w > Int.MAX_VALUE) return null
                    k += BASE
                }
                bias = adapt(i - oldI, out.size + 1, oldI == 0L)
                n += i / (out.size + 1)
                i %= (out.size + 1)
                if (n > 0x10FFFF || n in 0xD800..0xDFFF) return null
                out.add(i.toInt(), n.toInt())
                i++
            }
            return StringBuilder().apply { out.forEach { appendCodePoint(it) } }.toString()
        }
    }

    private fun percentDecode(s: String): String {
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
