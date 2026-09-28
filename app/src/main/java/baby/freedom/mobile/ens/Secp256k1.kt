package baby.freedom.mobile.ens

import java.math.BigInteger

/**
 * Ethereum signature recovery (secp256k1 `ecrecover`), verify side only.
 *
 * Used to authenticate the ad-block list manifest (#127), which the
 * publisher signs with EIP-191 `personal_sign` (ethers
 * `Wallet.signMessage`). Only public data goes through here — a
 * signature and the message it claims to sign — so plain [BigInteger]
 * arithmetic is fine: there is no secret for a timing side channel to
 * leak, and it saves pulling in BouncyCastle for one function.
 */
internal object Secp256k1 {
    private val P = BigInteger("fffffffffffffffffffffffffffffffffffffffffffffffffffffffefffffc2f", 16)
    private val N = BigInteger("fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141", 16)
    private val GX = BigInteger("79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798", 16)
    private val GY = BigInteger("483ada7726a3c4655da4fbfc0e1108a8fd17b448a68554199c47d08ffb10d4b8", 16)
    private val SEVEN = BigInteger.valueOf(7)
    private val SQRT_EXP = P.add(BigInteger.ONE).shiftRight(2)

    /** An affine point; `null` stands for the point at infinity. */
    private class Point(val x: BigInteger, val y: BigInteger)

    private val G = Point(GX, GY)

    /**
     * The `0x…` lower-case address that signed [message] with EIP-191
     * `personal_sign` — keccak256 of `"\u0019Ethereum Signed Message:\n"
     * + len + message` — or `null` when [signature] (65 bytes, `r‖s‖v`,
     * hex with or without `0x`) isn't a valid signature at all.
     */
    fun recoverPersonalSign(message: ByteArray, signature: String): String? {
        val prefix = "\u0019Ethereum Signed Message:\n${message.size}".toByteArray(Charsets.UTF_8)
        return recover(Keccak256.digest(prefix + message), signature)
    }

    /** The address whose key made [signature] over the 32-byte [digest], or `null`. */
    fun recover(digest: ByteArray, signature: String): String? {
        if (digest.size != 32) return null
        val sig = hexToBytes(signature.removePrefix("0x").removePrefix("0X")) ?: return null
        if (sig.size != 65) return null
        val r = BigInteger(1, sig.copyOfRange(0, 32))
        val s = BigInteger(1, sig.copyOfRange(32, 64))
        val v = sig[64].toInt() and 0xff
        val recId = when (v) {
            27, 28 -> v - 27
            0, 1 -> v
            else -> return null
        }
        if (r.signum() == 0 || r >= N || s.signum() == 0 || s >= N) return null
        // x = r: the recovery ids that would need x = r + n (recId 2/3)
        // are never produced by ethers and are refused above.
        if (r >= P) return null
        val rhs = r.modPow(BigInteger.valueOf(3), P).add(SEVEN).mod(P)
        var y = rhs.modPow(SQRT_EXP, P)
        if (y.modPow(BigInteger.valueOf(2), P) != rhs) return null
        if (y.testBit(0) != (recId and 1 == 1)) y = P.subtract(y)
        val big = Point(r, y)
        val e = BigInteger(1, digest).mod(N)
        val rInv = r.modInverse(N)
        val u1 = N.subtract(e).multiply(rInv).mod(N)
        val u2 = s.multiply(rInv).mod(N)
        val q = add(multiply(G, u1), multiply(big, u2)) ?: return null
        val pub = toBytes32(q.x) + toBytes32(q.y)
        val hash = Keccak256.digest(pub)
        return "0x" + hash.copyOfRange(12, 32).joinToString("") { "%02x".format(it) }
    }

    private fun add(a: Point?, b: Point?): Point? {
        if (a == null) return b
        if (b == null) return a
        val lambda = if (a.x == b.x) {
            if (a.y != b.y || a.y.signum() == 0) return null
            a.x.pow(2).multiply(BigInteger.valueOf(3)).multiply(a.y.shiftLeft(1).modInverse(P)).mod(P)
        } else {
            b.y.subtract(a.y).multiply(b.x.subtract(a.x).modInverse(P)).mod(P)
        }
        val x = lambda.pow(2).subtract(a.x).subtract(b.x).mod(P)
        val y = lambda.multiply(a.x.subtract(x)).subtract(a.y).mod(P)
        return Point(x, y)
    }

    private fun multiply(p: Point, k: BigInteger): Point? {
        var result: Point? = null
        var addend: Point? = p
        for (i in 0 until k.bitLength()) {
            if (k.testBit(i)) result = add(result, addend)
            addend = add(addend, addend)
        }
        return result
    }

    private fun toBytes32(v: BigInteger): ByteArray {
        val raw = v.toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
            else -> ByteArray(32 - raw.size) + raw
        }
    }

    private fun hexToBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = hexDigit(hex[2 * i])
            val lo = hexDigit(hex[2 * i + 1])
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    /** ASCII hex only — [Character.digit] would also take other scripts' digits. */
    private fun hexDigit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }
}
