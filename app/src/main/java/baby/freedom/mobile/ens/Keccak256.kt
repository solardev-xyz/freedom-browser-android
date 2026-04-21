package baby.freedom.mobile.ens

/**
 * Pure-Kotlin Keccak-256 (legacy / Ethereum variant).
 *
 * Important: this is **not** NIST SHA3-256 — they share the same permutation
 * but use different padding bytes (0x01 here vs 0x06 for NIST). Android's JCE
 * `MessageDigest.getInstance("SHA3-256")` uses the NIST variant and will
 * give different outputs; Ethereum, ENS namehash, and the Universal
 * Resolver's ABI selectors all depend on the legacy Keccak, so we have
 * to implement it ourselves (or pull in BouncyCastle, which is ~7 MB).
 *
 * Reference: https://keccak.team/keccak_specs_summary.html
 */
internal object Keccak256 {
    private const val RATE_BYTES = 136
    private const val OUTPUT_BYTES = 32

    // 24 round constants for Keccak-f[1600].
    private val RC = longArrayOf(
        0x0000000000000001uL.toLong(),
        0x0000000000008082uL.toLong(),
        0x800000000000808auL.toLong(),
        0x8000000080008000uL.toLong(),
        0x000000000000808buL.toLong(),
        0x0000000080000001uL.toLong(),
        0x8000000080008081uL.toLong(),
        0x8000000000008009uL.toLong(),
        0x000000000000008auL.toLong(),
        0x0000000000000088uL.toLong(),
        0x0000000080008009uL.toLong(),
        0x000000008000000auL.toLong(),
        0x000000008000808buL.toLong(),
        0x800000000000008buL.toLong(),
        0x8000000000008089uL.toLong(),
        0x8000000000008003uL.toLong(),
        0x8000000000008002uL.toLong(),
        0x8000000000000080uL.toLong(),
        0x000000000000800auL.toLong(),
        0x800000008000000auL.toLong(),
        0x8000000080008081uL.toLong(),
        0x8000000000008080uL.toLong(),
        0x0000000080000001uL.toLong(),
        0x8000000080008008uL.toLong(),
    )

    // Rotation offsets r[x + 5*y] for the ρ step, standard Keccak layout.
    private val R = intArrayOf(
        0, 1, 62, 28, 27,
        36, 44, 6, 55, 20,
        3, 10, 43, 25, 39,
        41, 45, 15, 21, 8,
        18, 2, 61, 56, 14,
    )

    fun digest(input: ByteArray): ByteArray {
        val state = LongArray(25)
        val padded = pad(input)
        val blocks = padded.size / RATE_BYTES
        for (b in 0 until blocks) {
            val off = b * RATE_BYTES
            for (i in 0 until RATE_BYTES / 8) {
                state[i] = state[i] xor readLE(padded, off + i * 8)
            }
            keccakF(state)
        }
        val out = ByteArray(OUTPUT_BYTES)
        for (i in 0 until OUTPUT_BYTES / 8) {
            writeLE(state[i], out, i * 8)
        }
        return out
    }

    fun digest(input: String): ByteArray = digest(input.toByteArray(Charsets.UTF_8))

    // Keccak (legacy) padding: append 0x01, zero-pad to rate, set high bit of
    // final byte. NIST SHA3 uses 0x06 here — the one-byte difference is the
    // only thing distinguishing our output from SHA3-256 for the same input.
    private fun pad(input: ByteArray): ByteArray {
        val padLen = RATE_BYTES - (input.size % RATE_BYTES)
        val out = ByteArray(input.size + padLen)
        input.copyInto(out)
        out[input.size] = 0x01
        out[out.size - 1] = (out[out.size - 1].toInt() or 0x80).toByte()
        return out
    }

    private fun keccakF(s: LongArray) {
        val c = LongArray(5)
        val d = LongArray(5)
        val b = LongArray(25)
        for (round in 0 until 24) {
            for (x in 0..4) {
                c[x] = s[x] xor s[x + 5] xor s[x + 10] xor s[x + 15] xor s[x + 20]
            }
            for (x in 0..4) {
                d[x] = c[(x + 4) % 5] xor java.lang.Long.rotateLeft(c[(x + 1) % 5], 1)
            }
            for (i in 0..24) {
                s[i] = s[i] xor d[i % 5]
            }
            for (x in 0..4) {
                for (y in 0..4) {
                    val idx = x + 5 * y
                    val newIdx = y + 5 * ((2 * x + 3 * y) % 5)
                    b[newIdx] = java.lang.Long.rotateLeft(s[idx], R[idx])
                }
            }
            for (y in 0..4) {
                for (x in 0..4) {
                    s[x + 5 * y] =
                        b[x + 5 * y] xor (b[((x + 1) % 5) + 5 * y].inv() and b[((x + 2) % 5) + 5 * y])
                }
            }
            s[0] = s[0] xor RC[round]
        }
    }

    private fun readLE(buf: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0..7) {
            v = v or ((buf[off + i].toLong() and 0xff) shl (8 * i))
        }
        return v
    }

    private fun writeLE(v: Long, buf: ByteArray, off: Int) {
        for (i in 0..7) {
            buf[off + i] = (v ushr (8 * i)).toByte()
        }
    }
}
