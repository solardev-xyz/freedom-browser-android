package baby.freedom.mobile.browser

import baby.freedom.mobile.ens.Keccak256
import java.math.BigInteger

/**
 * A test stand-in for the list publisher: EIP-191 signs a manifest's
 * canonical bytes with [key], as freedom-adblock-service's `signManifest`
 * does with ethers. Test-only and not constant-time.
 */
internal class TestManifestSigner(private val key: BigInteger) {
    val address: String

    init {
        val pub = mul(G, key)!!
        address = "0x" + Keccak256.digest(b32(pub.first) + b32(pub.second)).copyOfRange(12, 32).hex()
    }

    /** [manifest] (sans `sig`) with a `sig` over its canonical bytes. */
    fun sign(manifest: Map<String, Any?>): Map<String, Any?> {
        val body = manifest - "sig"
        return body + ("sig" to signMessage(canonicalManifestBytes(body)))
    }

    fun signMessage(message: ByteArray): String {
        val prefix = "\u0019Ethereum Signed Message:\n${message.size}".toByteArray()
        val e = BigInteger(1, Keccak256.digest(prefix + message))
        var k = BigInteger(1, Keccak256.digest(b32(key) + b32(e))).mod(N)
        while (true) {
            if (k.signum() != 0) {
                val r = mul(G, k)!!
                val rx = r.first.mod(N)
                val s = k.modInverse(N).multiply(e.add(rx.multiply(key))).mod(N)
                if (rx.signum() != 0 && s.signum() != 0 && r.first < N) {
                    val v = 27 + (if (r.second.testBit(0)) 1 else 0)
                    return "0x" + (b32(rx) + b32(s) + byteArrayOf(v.toByte())).hex()
                }
            }
            k = k.add(BigInteger.ONE).mod(N)
        }
    }

    private companion object {
        val P = BigInteger("fffffffffffffffffffffffffffffffffffffffffffffffffffffffefffffc2f", 16)
        val N = BigInteger("fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141", 16)
        val G = BigInteger("79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798", 16) to
            BigInteger("483ada7726a3c4655da4fbfc0e1108a8fd17b448a68554199c47d08ffb10d4b8", 16)

        fun add(a: Pair<BigInteger, BigInteger>?, b: Pair<BigInteger, BigInteger>?): Pair<BigInteger, BigInteger>? {
            if (a == null) return b
            if (b == null) return a
            val l = if (a.first == b.first) {
                if (a.second != b.second) return null
                a.first.pow(2).multiply(BigInteger.valueOf(3)).multiply(a.second.shiftLeft(1).modInverse(P)).mod(P)
            } else {
                b.second.subtract(a.second).multiply(b.first.subtract(a.first).modInverse(P)).mod(P)
            }
            val x = l.pow(2).subtract(a.first).subtract(b.first).mod(P)
            return x to l.multiply(a.first.subtract(x)).subtract(a.second).mod(P)
        }

        fun mul(p: Pair<BigInteger, BigInteger>, k: BigInteger): Pair<BigInteger, BigInteger>? {
            var result: Pair<BigInteger, BigInteger>? = null
            var addend: Pair<BigInteger, BigInteger>? = p
            for (i in 0 until k.bitLength()) {
                if (k.testBit(i)) result = add(result, addend)
                addend = add(addend, addend)
            }
            return result
        }

        fun b32(v: BigInteger): ByteArray {
            val raw = v.toByteArray()
            return if (raw.size >= 32) raw.copyOfRange(raw.size - 32, raw.size) else ByteArray(32 - raw.size) + raw
        }

        fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    }
}
