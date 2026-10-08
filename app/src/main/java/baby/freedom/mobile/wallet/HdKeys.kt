package baby.freedom.mobile.wallet

import java.math.BigInteger
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Hierarchical key derivation from a BIP-39 seed, for the node
 * identities (#77): BIP-32 over secp256k1 (the Swarm key) and SLIP-0010
 * over Ed25519 (the IPFS PeerID key), matching desktop's
 * `identity/derivation.js` (ethers `HDNodeWallet`, micro-key-producer
 * `slip10`) and iOS's `SLIP10.swift` byte for byte.
 *
 * Pure Kotlin on [BigInteger]: Android has no secp256k1 or (below
 * API 33) Ed25519 in its providers, and the repo avoids BouncyCastle
 * (see [baby.freedom.mobile.ens.Keccak256]). Unlike
 * [baby.freedom.mobile.ens.Secp256k1], this does touch secret keys, so
 * the scalar multiplications run a fixed-length ladder that does the
 * same group operations whatever the key bits are; [BigInteger] itself
 * isn't constant-time, which is acceptable here because derivation runs
 * on the device only, a handful of times per wallet, with nothing
 * outside the app able to trigger or time it.
 *
 * Every intermediate secret *byte array* (each HMAC output, the `I_L`
 * half, the parent key and chain code) is zeroed as soon as the next
 * level has been computed; callers own (and must zero) what's returned.
 * What can't be zeroed: the [BigInteger]s the secp256k1 child step adds
 * `I_L` and the parent key in (immutable, with their own internal
 * copies), and the copy of each chain code [SecretKeySpec] keeps for
 * the HMAC key. Those stay on the heap until the GC reuses the memory.
 */
internal object HdKeys {
    const val HARDENED = 0x80000000L

    /**
     * Test hook: sees every secret scratch array key derivation and
     * signing allocate (HMAC outputs, `I_L`, the Ed25519 scalar, RFC 6979
     * state and nonces), so a test can check each one was zeroed.
     */
    @Volatile
    internal var scratchSeen: ((ByteArray) -> Unit)? = null

    internal fun scratch(b: ByteArray) = b.also { scratchSeen?.invoke(it) }

    /** A private key plus the chain code for deriving its children. */
    class Node(val key: ByteArray, val chainCode: ByteArray) {
        fun wipe() {
            key.fill(0)
            chainCode.fill(0)
        }
    }

    /**
     * `m/44'/60'/0'/0/1` → `[44+2³¹, 60+2³¹, 2³¹, 0, 1]`. Only `'` marks
     * a hardened index (what desktop and iOS write); indices must fit 31 bits.
     */
    fun parsePath(path: String): LongArray {
        val parts = path.split('/')
        require(parts.firstOrNull() == "m") { "a path starts with m" }
        return LongArray(parts.size - 1) { i ->
            val seg = parts[i + 1]
            val hardened = seg.endsWith("'")
            val digits = if (hardened) seg.dropLast(1) else seg
            require(digits.isNotEmpty() && digits.all { it in '0'..'9' } && digits.length <= 10) { "bad path segment" }
            val n = digits.toLong()
            require(n < HARDENED) { "path index too large" }
            if (hardened) n + HARDENED else n
        }
    }

    // ---- BIP-32, secp256k1 ----

    /** The secp256k1 private key at [path] (BIP-32), 32 bytes. */
    fun secp256k1(seed: ByteArray, path: String): ByteArray {
        var node = hmac("Bitcoin seed".toByteArray(), seed).let { master(it) { k -> Secp256k1Keys.isValidPrivate(k) } }
        for (index in parsePath(path)) {
            val next = secp256k1Child(node, index)
            node.wipe()
            node = next
        }
        node.chainCode.fill(0)
        return node.key
    }

    private fun secp256k1Child(parent: Node, index: Long): Node {
        // The parts go into the HMAC one by one: `0x00 ‖ key ‖ index` built
        // with `+` would leave an unzeroed copy of the parent key behind.
        val i = if (index >= HARDENED) {
            hmac(parent.chainCode, ZERO, parent.key, ser32(index))
        } else {
            hmac(parent.chainCode, Secp256k1Keys.publicKeyCompressed(parent.key), ser32(index))
        }
        val ilBytes = scratch(i.copyOfRange(0, 32))
        val il: BigInteger
        val k: BigInteger
        val chain: ByteArray
        try {
            il = BigInteger(1, ilBytes)
            k = il.add(BigInteger(1, parent.key)).mod(Secp256k1Keys.N)
            chain = i.copyOfRange(32, 64)
        } finally {
            ilBytes.fill(0)
            i.fill(0)
        }
        // Probability ~2⁻¹²⁷; BIP-32 says to move on to the next index,
        // which no desktop path would ever reach — refuse instead.
        check(il < Secp256k1Keys.N && k.signum() != 0) { "invalid BIP-32 child" }
        return Node(to32(k), chain)
    }

    // ---- SLIP-0010, Ed25519 ----

    /** The 32-byte Ed25519 private key (seed) at [path] (SLIP-0010; every segment must be hardened). */
    fun ed25519(seed: ByteArray, path: String): ByteArray {
        var node = master(hmac("ed25519 seed".toByteArray(), seed)) { true }
        for (index in parsePath(path)) {
            require(index >= HARDENED) { "SLIP-0010 Ed25519 needs hardened indices" }
            val i = hmac(node.chainCode, ZERO, node.key, ser32(index))
            node.wipe()
            node = Node(i.copyOfRange(0, 32), i.copyOfRange(32, 64))
            i.fill(0)
        }
        node.chainCode.fill(0)
        return node.key
    }

    private inline fun master(i: ByteArray, valid: (ByteArray) -> Boolean): Node {
        val node = Node(i.copyOfRange(0, 32), i.copyOfRange(32, 64))
        i.fill(0)
        check(valid(node.key)) { "invalid master key" }
        return node
    }

    private fun ser32(i: Long) = byteArrayOf((i ushr 24).toByte(), (i ushr 16).toByte(), (i ushr 8).toByte(), i.toByte())

    private val ZERO = byteArrayOf(0)

    /** HMAC-SHA512 over [parts] in order, without joining them into one (secret-holding) array. */
    private fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(key, "HmacSHA512"))
        for (part in parts) mac.update(part)
        return scratch(mac.doFinal())
    }

    internal fun sha512(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-512").digest(data)

    /** [v] as 32 big-endian bytes. */
    internal fun to32(v: BigInteger): ByteArray {
        val raw = v.toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
            else -> ByteArray(32 - raw.size) + raw
        }.also { if (it !== raw) raw.fill(0) }
    }
}

/**
 * secp256k1 public keys from private ones — the sign side
 * [baby.freedom.mobile.ens.Secp256k1] leaves out. Jacobian coordinates
 * and a Montgomery ladder, so every key takes the same 256 add/double
 * steps (see [HdKeys] on what that does and doesn't promise).
 */
internal object Secp256k1Keys {
    private val P = BigInteger("fffffffffffffffffffffffffffffffffffffffffffffffffffffffefffffc2f", 16)
    val N = BigInteger("fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141", 16)
    private val GX = BigInteger("79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798", 16)
    private val GY = BigInteger("483ada7726a3c4655da4fbfc0e1108a8fd17b448a68554199c47d08ffb10d4b8", 16)

    /** Jacobian (X, Y, Z); Z = 0 is the point at infinity. */
    private class J(val x: BigInteger, val y: BigInteger, val z: BigInteger)

    private val INF = J(BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)

    fun isValidPrivate(key: ByteArray): Boolean {
        val k = BigInteger(1, key)
        return key.size == 32 && k.signum() != 0 && k < N
    }

    /** `x ‖ y`, 64 bytes — what an Ethereum address hashes. */
    fun publicKeyUncompressed(privateKey: ByteArray): ByteArray {
        val (x, y) = publicPoint(privateKey)
        return HdKeys.to32(x) + HdKeys.to32(y)
    }

    /** SEC1 compressed: `02|03 ‖ x`, 33 bytes — what BIP-32 hashes. */
    fun publicKeyCompressed(privateKey: ByteArray): ByteArray {
        val (x, y) = publicPoint(privateKey)
        return byteArrayOf(if (y.testBit(0)) 3 else 2) + HdKeys.to32(x)
    }

    /** `privateKey·G` in affine coordinates, by the fixed-length ladder (also the nonce point of a signature, [EthSigning]). */
    internal fun publicPoint(privateKey: ByteArray): Pair<BigInteger, BigInteger> {
        require(isValidPrivate(privateKey)) { "not a secp256k1 private key" }
        val k = BigInteger(1, privateKey)
        // Montgomery ladder: r0 = k'·G, r1 = r0 + G, same work per bit.
        var r0 = INF
        var r1 = J(GX, GY, BigInteger.ONE)
        for (bit in 255 downTo 0) {
            if (k.testBit(bit)) {
                r0 = add(r0, r1)
                r1 = double(r1)
            } else {
                r1 = add(r0, r1)
                r0 = double(r0)
            }
        }
        val zInv = r0.z.modInverse(P)
        val zInv2 = zInv.multiply(zInv).mod(P)
        return r0.x.multiply(zInv2).mod(P) to r0.y.multiply(zInv2).multiply(zInv).mod(P)
    }

    private fun double(p: J): J {
        if (p.z.signum() == 0 || p.y.signum() == 0) return INF
        val y2 = p.y.multiply(p.y).mod(P)
        val s = p.x.multiply(y2).shiftLeft(2).mod(P)
        val m = p.x.multiply(p.x).multiply(BigInteger.valueOf(3)).mod(P)
        val x3 = m.multiply(m).subtract(s.shiftLeft(1)).mod(P)
        val y3 = m.multiply(s.subtract(x3)).subtract(y2.multiply(y2).shiftLeft(3)).mod(P)
        val z3 = p.y.multiply(p.z).shiftLeft(1).mod(P)
        return J(x3, y3, z3)
    }

    private fun add(a: J, b: J): J {
        if (a.z.signum() == 0) return b
        if (b.z.signum() == 0) return a
        val z1z1 = a.z.multiply(a.z).mod(P)
        val z2z2 = b.z.multiply(b.z).mod(P)
        val u1 = a.x.multiply(z2z2).mod(P)
        val u2 = b.x.multiply(z1z1).mod(P)
        val s1 = a.y.multiply(b.z).multiply(z2z2).mod(P)
        val s2 = b.y.multiply(a.z).multiply(z1z1).mod(P)
        if (u1 == u2) return if (s1 == s2) double(a) else INF
        val h = u2.subtract(u1).mod(P)
        val r = s2.subtract(s1).mod(P)
        val h2 = h.multiply(h).mod(P)
        val h3 = h2.multiply(h).mod(P)
        val u1h2 = u1.multiply(h2).mod(P)
        val x3 = r.multiply(r).subtract(h3).subtract(u1h2.shiftLeft(1)).mod(P)
        val y3 = r.multiply(u1h2.subtract(x3)).subtract(s1.multiply(h3)).mod(P)
        val z3 = h.multiply(a.z).multiply(b.z).mod(P)
        return J(x3, y3, z3)
    }
}

/**
 * The Ed25519 public key for a 32-byte private key (RFC 8032 §5.1.5):
 * SHA-512, clamp, multiply the base point, encode. Extended twisted
 * Edwards coordinates with the unified addition law, and a fixed-length
 * ladder as in [Secp256k1Keys].
 */
internal object Ed25519 {
    private val P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val D = BigInteger.valueOf(-121665).multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P)
    private val D2 = D.shiftLeft(1).mod(P)
    private val BX = BigInteger("15112221349535400772501151409588531511454012693041857206046113283949847762202")
    private val BY = BigInteger("46316835694926478169428394003475163141307993866256225615783033603165251855960")

    /** Extended (X, Y, Z, T) with x = X/Z, y = Y/Z, xy = T/Z. */
    private class E(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger)

    private val IDENTITY = E(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)

    fun publicKey(privateKey: ByteArray): ByteArray {
        require(privateKey.size == 32) { "an Ed25519 private key is 32 bytes" }
        val h = HdKeys.scratch(HdKeys.sha512(privateKey))
        val scalarBytes = HdKeys.scratch(h.copyOfRange(0, 32))
        h.fill(0)
        scalarBytes[0] = (scalarBytes[0].toInt() and 248).toByte()
        scalarBytes[31] = ((scalarBytes[31].toInt() and 127) or 64).toByte()
        // Little-endian: reverse in place, since `reversedArray()` would be
        // an unzeroed copy of the scalar (#477).
        scalarBytes.reverse()
        val a = BigInteger(1, scalarBytes)
        scalarBytes.fill(0)
        var r0 = IDENTITY
        var r1 = E(BX, BY, BigInteger.ONE, BX.multiply(BY).mod(P))
        for (bit in 254 downTo 0) {
            if (a.testBit(bit)) {
                r0 = add(r0, r1)
                r1 = add(r1, r1)
            } else {
                r1 = add(r0, r1)
                r0 = add(r0, r0)
            }
        }
        val zInv = r0.z.modInverse(P)
        val x = r0.x.multiply(zInv).mod(P)
        val y = r0.y.multiply(zInv).mod(P)
        val out = HdKeys.to32(y).reversedArray()
        if (x.testBit(0)) out[31] = (out[31].toInt() or 0x80).toByte()
        return out
    }

    private fun add(p: E, q: E): E {
        val a = p.y.subtract(p.x).multiply(q.y.subtract(q.x)).mod(P)
        val b = p.y.add(p.x).multiply(q.y.add(q.x)).mod(P)
        val c = p.t.multiply(D2).multiply(q.t).mod(P)
        val d = p.z.shiftLeft(1).multiply(q.z).mod(P)
        val e = b.subtract(a)
        val f = d.subtract(c)
        val g = d.add(c)
        val hh = b.add(a)
        return E(e.multiply(f).mod(P), g.multiply(hh).mod(P), f.multiply(g).mod(P), e.multiply(hh).mod(P))
    }
}
