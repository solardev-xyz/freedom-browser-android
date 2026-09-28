package baby.freedom.mobile.browser

/**
 * RFC 3492 Punycode, the `xn--` label encoding hostnames carry non-ASCII
 * labels in. `java.net.IDN` isn't usable for this: it is IDNA2003, which
 * refuses emoji (unassigned in Unicode 3.2) and strips ZWJ in nameprep,
 * so it can't round-trip ENS names. This is the bare codec — no mapping
 * or validation — operating on code points.
 */
internal object Punycode {
    private const val BASE = 36
    private const val TMIN = 1
    private const val TMAX = 26
    private const val SKEW = 38
    private const val DAMP = 700
    private const val INITIAL_BIAS = 72
    private const val INITIAL_N = 128

    private fun adapt(deltaIn: Int, numPoints: Int, firstTime: Boolean): Int {
        var delta = if (firstTime) deltaIn / DAMP else deltaIn / 2
        delta += delta / numPoints
        var k = 0
        while (delta > ((BASE - TMIN) * TMAX) / 2) {
            delta /= BASE - TMIN
            k += BASE
        }
        return k + (BASE - TMIN + 1) * delta / (delta + SKEW)
    }

    private fun digit(d: Int): Char = if (d < 26) 'a' + d else '0' + (d - 26)

    private fun value(c: Char): Int = when (c) {
        in 'a'..'z' -> c - 'a'
        in 'A'..'Z' -> c - 'A'
        in '0'..'9' -> c - '0' + 26
        else -> -1
    }

    /** Encode [input] (without the `xn--` prefix). */
    fun encode(input: String): String {
        val cps = input.codePoints().toArray()
        val out = StringBuilder()
        for (cp in cps) if (cp < 0x80) out.append(cp.toChar())
        val basic = out.length
        var h = basic
        if (basic > 0) out.append('-')
        var n = INITIAL_N
        var delta = 0L
        var bias = INITIAL_BIAS
        while (h < cps.size) {
            val m = cps.filter { it >= n }.min()
            delta += (m - n).toLong() * (h + 1)
            n = m
            for (cp in cps) {
                if (cp < n) delta++
                if (cp == n) {
                    var q = delta
                    var k = BASE
                    while (true) {
                        val t = when {
                            k <= bias -> TMIN
                            k >= bias + TMAX -> TMAX
                            else -> k - bias
                        }
                        if (q < t) break
                        out.append(digit((t + (q - t) % (BASE - t)).toInt()))
                        q = (q - t) / (BASE - t)
                        k += BASE
                    }
                    out.append(digit(q.toInt()))
                    bias = adapt(delta.toInt(), h + 1, h == basic)
                    delta = 0
                    h++
                }
            }
            delta++
            n++
        }
        return out.toString()
    }

    /**
     * Decode [input] (without the `xn--` prefix), or `null` if malformed.
     * Follows RFC 3492 §6.2 / the §C reference decoder, including its
     * overflow checks before every multiply and add, so hostile input
     * (any page can link to an arbitrary `xn--` host) can only ever
     * yield `null`, never a wrapped-negative or out-of-range code point.
     * Also rejects code points past U+10FFFF and surrogates.
     */
    fun decode(input: String): String? {
        val maxInt = Int.MAX_VALUE
        val lastDash = input.lastIndexOf('-')
        val out = ArrayList<Int>()
        if (lastDash > 0) {
            for (idx in 0 until lastDash) {
                val c = input[idx]
                if (c.code >= 0x80) return null
                out.add(c.code)
            }
        }
        var n = INITIAL_N
        var i = 0
        var bias = INITIAL_BIAS
        var pos = if (lastDash > 0) lastDash + 1 else 0
        while (pos < input.length) {
            val oldi = i
            var w = 1
            var k = BASE
            while (true) {
                if (pos >= input.length) return null
                val d = value(input[pos++])
                if (d < 0) return null
                if (d > (maxInt - i) / w) return null
                i += d * w
                val t = when {
                    k <= bias -> TMIN
                    k >= bias + TMAX -> TMAX
                    else -> k - bias
                }
                if (d < t) break
                if (w > maxInt / (BASE - t)) return null
                w *= (BASE - t)
                k += BASE
            }
            val len = out.size + 1
            bias = adapt(i - oldi, len, oldi == 0)
            if (i / len > maxInt - n) return null
            n += i / len
            if (n > 0x10FFFF || n in 0xD800..0xDFFF) return null
            i %= len
            out.add(i, n)
            i++
        }
        val sb = StringBuilder()
        for (cp in out) sb.appendCodePoint(cp)
        return sb.toString()
    }
}
