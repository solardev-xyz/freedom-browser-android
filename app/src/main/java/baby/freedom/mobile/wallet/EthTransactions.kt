package baby.freedom.mobile.wallet

import baby.freedom.mobile.ens.Keccak256
import baby.freedom.mobile.ens.Secp256k1
import baby.freedom.mobile.ens.toHex
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Recursive Length Prefix, Ethereum's serialization (Yellow Paper, appendix B): encode only. */
internal object Rlp {
    /** A byte string. */
    fun bytes(b: ByteArray): ByteArray = when {
        b.size == 1 && (b[0].toInt() and 0xff) < 0x80 -> b.copyOf()
        else -> header(0x80, b.size) + b
    }

    /** A scalar: big-endian with no leading zeros, so zero is the empty string. */
    fun quantity(v: BigInteger): ByteArray {
        require(v.signum() >= 0) { "RLP quantities are unsigned" }
        if (v.signum() == 0) return bytes(ByteArray(0))
        val raw = v.toByteArray()
        return bytes(if (raw[0].toInt() == 0) raw.copyOfRange(1, raw.size) else raw)
    }

    fun quantity(v: Long): ByteArray = quantity(BigInteger.valueOf(v))

    /** A list of items that are already encoded. */
    fun list(items: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        items.forEach { out.write(it) }
        val payload = out.toByteArray()
        return header(0xc0, payload.size) + payload
    }

    private fun header(offset: Int, length: Int): ByteArray {
        if (length < 56) return byteArrayOf((offset + length).toByte())
        val len = BigInteger.valueOf(length.toLong()).toByteArray().let { if (it[0].toInt() == 0) it.copyOfRange(1, it.size) else it }
        return byteArrayOf((offset + 55 + len.size).toByte()) + len
    }
}

/**
 * Ethereum transaction signing (#105): secp256k1 ECDSA with RFC 6979
 * deterministic nonces and low-s signatures — what ethers, desktop's
 * signer, produces byte for byte for the same key and digest.
 *
 * The nonce point comes from [Secp256k1Keys.publicPoint]'s fixed-length
 * ladder; the rest is [BigInteger] arithmetic, which isn't constant
 * time. As with key derivation ([HdKeys]) that is accepted: signing
 * runs on the device, once per transaction the user confirmed, and
 * nothing outside the app can trigger it or time it.
 */
internal object EthSigning {
    /** `r`, `s` (low-s) and the recovery id (`0` or `1`: the parity of the nonce point's y). */
    class Signature(val r: BigInteger, val s: BigInteger, val recoveryId: Int) {
        /** 65 bytes `r ‖ s ‖ v` with v = 27 + [recoveryId], the `ecrecover` form. */
        fun rsv(): ByteArray = HdKeys.to32(r) + HdKeys.to32(s) + byteArrayOf((27 + recoveryId).toByte())
    }

    private val N = Secp256k1Keys.N
    private val HALF_N = N.shiftRight(1)

    /** Signs the 32-byte [digest] with [privateKey] (which the caller zeroes). */
    fun sign(privateKey: ByteArray, digest: ByteArray): Signature {
        require(digest.size == 32) { "a digest is 32 bytes" }
        require(Secp256k1Keys.isValidPrivate(privateKey)) { "not a secp256k1 private key" }
        val d = BigInteger(1, privateKey)
        val z = BigInteger(1, digest).mod(N)
        val nonces = Rfc6979(privateKey, HdKeys.to32(z))
        try {
            while (true) {
                val kBytes = nonces.next()
                try {
                    val k = BigInteger(1, kBytes)
                    val (rx, ry) = Secp256k1Keys.publicPoint(kBytes)
                    // x ≥ n happens with probability ~2⁻¹²⁷; its recovery id (2 or 3)
                    // is one ecrecover can't use, so take the next nonce instead.
                    if (rx >= N) continue
                    val r = rx
                    if (r.signum() == 0) continue
                    var s = k.modInverse(N).multiply(z.add(r.multiply(d))).mod(N)
                    if (s.signum() == 0) continue
                    var recId = if (ry.testBit(0)) 1 else 0
                    if (s > HALF_N) {
                        s = N.subtract(s)
                        recId = recId xor 1
                    }
                    return Signature(r, s, recId)
                } finally {
                    kBytes.fill(0)
                }
            }
        } finally {
            nonces.wipe()
        }
    }

    /** RFC 6979 §3.2 with HMAC-SHA256: the candidate nonces for one (key, digest), in order. */
    private class Rfc6979(key: ByteArray, h1: ByteArray) {
        private var k = ByteArray(32)
        private var v = ByteArray(32) { 1 }
        private var first = true

        init {
            // The parts go into the HMAC one by one: `v + 0x00 + key + h1`
            // built with `+` leaves unzeroed intermediates holding the key (#477).
            rekey(v, ZERO, key, h1)
            step()
            rekey(v, ONE, key, h1)
            step()
        }

        /** The next candidate, in [1, n). */
        fun next(): ByteArray {
            while (true) {
                if (!first) {
                    rekey(v, ZERO)
                    step()
                }
                first = false
                step()
                val candidate = BigInteger(1, v)
                if (candidate.signum() > 0 && candidate < N) return HdKeys.scratch(v.copyOf())
            }
        }

        fun wipe() {
            k.fill(0)
            v.fill(0)
        }

        /** K = HMAC_K(parts in order); the old K is zeroed. */
        private fun rekey(vararg parts: ByteArray) {
            val next = hmac(k, *parts)
            k.fill(0)
            k = next
        }

        /** V = HMAC_K(V); the old V is zeroed. */
        private fun step() {
            val next = hmac(k, v)
            v.fill(0)
            v = next
        }

        private fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            for (part in parts) mac.update(part)
            return HdKeys.scratch(mac.doFinal())
        }

        private companion object {
            val ZERO = byteArrayOf(0)
            val ONE = byteArrayOf(1)
        }
    }
}

/**
 * An Ethereum transaction the wallet builds and signs (#105): EIP-1559
 * (type 2) where the chain has a base fee, else legacy with EIP-155
 * replay protection. [to] is always set — the wallet only sends, it
 * never deploys a contract.
 */
data class EthTransaction(
    val chainId: Long,
    val nonce: BigInteger,
    val gasLimit: BigInteger,
    /** The recipient, or the token contract for an ERC-20 transfer. */
    val to: String,
    val value: BigInteger,
    val data: ByteArray,
    val fees: Fees,
) {
    sealed interface Fees {
        /** The most one gas unit can cost. */
        val maxPerGas: BigInteger

        data class Eip1559(val maxFeePerGas: BigInteger, val maxPriorityFeePerGas: BigInteger) : Fees {
            init {
                require(maxPriorityFeePerGas <= maxFeePerGas) { "the tip can't exceed the fee cap" }
            }
            override val maxPerGas: BigInteger get() = maxFeePerGas
        }

        data class Legacy(val gasPrice: BigInteger) : Fees {
            override val maxPerGas: BigInteger get() = gasPrice
        }
    }

    init {
        require(chainId > 0) { "chain id" }
        require(ADDRESS.matches(to)) { "not an address" }
        require(nonce.signum() >= 0 && gasLimit.signum() > 0 && value.signum() >= 0) { "negative field" }
    }

    /** The most the transaction can cost in fees, in the native currency's base units. */
    val maxFee: BigInteger get() = gasLimit.multiply(fees.maxPerGas)

    /** The bytes the signature covers (their keccak256 is what's signed). */
    fun signingPayload(): ByteArray = when (val f = fees) {
        is Fees.Eip1559 -> byteArrayOf(2) + Rlp.list(eip1559Fields(f))
        is Fees.Legacy -> Rlp.list(legacyFields(f) + listOf(Rlp.quantity(chainId), Rlp.quantity(0), Rlp.quantity(0)))
    }

    /** The signed transaction as `eth_sendRawTransaction` takes it. */
    internal fun encodeSigned(sig: EthSigning.Signature): ByteArray = when (val f = fees) {
        is Fees.Eip1559 -> byteArrayOf(2) + Rlp.list(
            eip1559Fields(f) + listOf(Rlp.quantity(sig.recoveryId.toLong()), Rlp.quantity(sig.r), Rlp.quantity(sig.s)),
        )
        is Fees.Legacy -> Rlp.list(
            legacyFields(f) + listOf(
                Rlp.quantity(BigInteger.valueOf(chainId).shiftLeft(1).add(BigInteger.valueOf(35L + sig.recoveryId))),
                Rlp.quantity(sig.r),
                Rlp.quantity(sig.s),
            ),
        )
    }

    private fun common() = listOf(
        Rlp.quantity(gasLimit),
        Rlp.bytes(to.substring(2).hexBytes()),
        Rlp.quantity(value),
        Rlp.bytes(data),
    )

    private fun eip1559Fields(f: Fees.Eip1559) = listOf(
        Rlp.quantity(chainId),
        Rlp.quantity(nonce),
        Rlp.quantity(f.maxPriorityFeePerGas),
        Rlp.quantity(f.maxFeePerGas),
    ) + common() + listOf(Rlp.list(emptyList()))

    private fun legacyFields(f: Fees.Legacy) = listOf(Rlp.quantity(nonce), Rlp.quantity(f.gasPrice)) + common()

    // ByteArray has identity equality; compare its contents.
    override fun equals(other: Any?): Boolean = other is EthTransaction && chainId == other.chainId &&
        nonce == other.nonce && gasLimit == other.gasLimit && to.equals(other.to, ignoreCase = true) &&
        value == other.value && data.contentEquals(other.data) && fees == other.fees

    override fun hashCode(): Int = listOf(chainId, nonce, gasLimit, to.lowercase(), value, data.contentHashCode(), fees).hashCode()

    /** A transaction signed and ready to broadcast: its raw bytes and hash. */
    class Signed(val tx: EthTransaction, val raw: String, val hash: String)

    /**
     * Signs with [privateKey] (the caller zeroes it), then recovers the
     * signer from the signature and refuses unless it is [from]: a
     * transaction that would come from any other account never leaves
     * the device.
     */
    fun sign(privateKey: ByteArray, from: String): Signed = signedWith(EthSigning.sign(privateKey, Keccak256.digest(signingPayload())), from)

    /**
     * With [sig] made elsewhere (a Ledger, #142), under the same check as
     * [sign]: it must recover to [from] over this transaction's digest.
     */
    internal fun signedWith(sig: EthSigning.Signature, from: String): Signed {
        val digest = Keccak256.digest(signingPayload())
        val recovered = Secp256k1.recover(digest, sig.rsv().toHex())
        check(recovered != null && recovered.equals(from, ignoreCase = true)) { "the signature doesn't match the account" }
        val raw = encodeSigned(sig)
        return Signed(this, "0x" + raw.toHex(), "0x" + Keccak256.digest(raw).toHex())
    }

    companion object {
        internal val ADDRESS = Regex("^0x[0-9a-fA-F]{40}$")

        private fun String.hexBytes() = ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}
