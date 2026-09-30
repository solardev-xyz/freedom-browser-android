package baby.freedom.mobile.wallet

import baby.freedom.mobile.ens.Keccak256
import java.math.BigInteger
import java.util.Base64

/**
 * The node identities derived from the wallet's recovery phrase (#77) —
 * a cross-platform contract: the same phrase gives the same Swarm
 * account and overlay and the same IPFS PeerID here as on desktop
 * (`identity/derivation.js`, `formats.js`) and iOS.
 *
 *  - Swarm: the secp256k1 key at BIP-44 [SWARM_PATH] (desktop's
 *    `BEE_WALLET`). ant gets it as `signing_key` with a 32-zero
 *    `overlay_nonce`, as desktop's keystore injection and iOS do, so the
 *    overlay (`keccak256(address ‖ networkId_le ‖ nonce)`) matches too;
 *    the libp2p key is left out, and ant derives it from the signing key.
 *  - IPFS: the Ed25519 key at SLIP-0010 [IPFS_PATH]; [peerId] and
 *    [libp2pPrivateKey] are the kubo `Identity.PeerID` / `PrivKey`
 *    desktop writes.
 *
 * Holds secrets: [wipe] it when done, and never log it ([toString]
 * prints the public parts only).
 */
class NodeIdentity internal constructor(
    /** 32-byte secp256k1 key — the Swarm account itself. */
    internal val swarmKey: ByteArray,
    /** 32-byte Ed25519 private key for the IPFS PeerID. */
    internal val ipfsKey: ByteArray,
) {
    init {
        require(swarmKey.size == 32 && ipfsKey.size == 32) { "node keys are 32 bytes" }
    }

    /** The Swarm account's address, EIP-55 checksummed (`0x…`). */
    val swarmAddress: String by lazy {
        val pub = Secp256k1Keys.publicKeyUncompressed(swarmKey)
        checksum(Keccak256.digest(pub).copyOfRange(12, 32))
    }

    /** The Swarm overlay address ant will report, lower-case hex without `0x`. */
    val swarmOverlay: String by lazy {
        val address = hex(swarmAddress.substring(2))
        val networkIdLe = ByteArray(8).also { it[0] = SWARM_NETWORK_ID.toByte() }
        Keccak256.digest(address + networkIdLe + ByteArray(32)).toHex()
    }

    /** Raw 32-byte Ed25519 public key. */
    val ipfsPublicKey: ByteArray by lazy { Ed25519.publicKey(ipfsKey) }

    /** `12D3KooW…`: base58btc of the identity multihash of the libp2p PublicKey protobuf. */
    val peerId: String by lazy {
        val pubProto = byteArrayOf(0x08, 0x01, 0x12, 0x20) + ipfsPublicKey
        Base58.encode(byteArrayOf(0x00, pubProto.size.toByte()) + pubProto)
    }

    /** kubo `Identity.PrivKey`: base64 of the libp2p PrivateKey protobuf (`priv ‖ pub`). Secret. */
    internal fun libp2pPrivateKey(): String {
        val proto = byteArrayOf(0x08, 0x01, 0x12, 0x40) + ipfsKey + ipfsPublicKey
        return try {
            Base64.getEncoder().encodeToString(proto)
        } finally {
            proto.fill(0)
        }
    }

    /**
     * The identity document for `ant_init_with_identity`, as UTF-8 bytes
     * the caller zeroes after use (a String couldn't be).
     */
    fun antIdentityJson(): ByteArray {
        val keyHex = swarmKey.toHexChars()
        val prefix = "{\"signing_key\":\"".toByteArray()
        val suffix = "\",\"overlay_nonce\":\"${"0".repeat(64)}\"}".toByteArray()
        val out = ByteArray(prefix.size + keyHex.size + suffix.size)
        prefix.copyInto(out, 0)
        for (i in keyHex.indices) out[prefix.size + i] = keyHex[i].code.toByte()
        suffix.copyInto(out, prefix.size + keyHex.size)
        keyHex.fill('0')
        return out
    }

    fun wipe() {
        swarmKey.fill(0)
        ipfsKey.fill(0)
    }

    override fun toString(): String = "NodeIdentity(swarm=$swarmAddress, peerId=$peerId)"

    companion object {
        /**
         * Desktop's Swarm key. It shares the non-hardened parent
         * `m/44'/60'/0'/0` with Account 1 (`…/0/0`), whose private key
         * can be exported (#323): that key plus the parent's extended
         * public key (its chain code) gives this one. So the parent's
         * xpub must never be shown or shared — no watch-only export of
         * account 0 — or the "doesn't open your node identity" promise
         * on the private-key page stops holding.
         */
        const val SWARM_PATH = "m/44'/60'/0'/0/1"
        const val IPFS_PATH = "m/44'/73405'/0'/0'/0'"
        private const val SWARM_NETWORK_ID = 1

        /** Derives both identities from a 64-byte BIP-39 seed (see [Vault.withSeed]). */
        fun derive(seed: ByteArray): NodeIdentity =
            NodeIdentity(HdKeys.secp256k1(seed, SWARM_PATH), HdKeys.ed25519(seed, IPFS_PATH))

        /** EIP-55 mixed-case checksum of a 20-byte address. */
        internal fun checksum(address: ByteArray): String {
            val lower = address.toHex()
            val hash = Keccak256.digest(lower.toByteArray())
            val sb = StringBuilder("0x")
            for (i in lower.indices) {
                val c = lower[i]
                val nibble = (hash[i / 2].toInt() shr (if (i % 2 == 0) 4 else 0)) and 0xf
                sb.append(if (c in 'a'..'f' && nibble >= 8) c.uppercaseChar() else c)
            }
            return sb.toString()
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

        private fun ByteArray.toHexChars(): CharArray {
            val digits = "0123456789abcdef"
            val out = CharArray(size * 2)
            for (i in indices) {
                val v = this[i].toInt() and 0xff
                out[2 * i] = digits[v shr 4]
                out[2 * i + 1] = digits[v and 0xf]
            }
            return out
        }

        private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }
}

/** Base58btc (Bitcoin alphabet) encoding. */
internal object Base58 {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val BASE = BigInteger.valueOf(58)

    fun encode(bytes: ByteArray): String {
        var n = BigInteger(1, bytes)
        val sb = StringBuilder()
        while (n.signum() > 0) {
            val (q, r) = n.divideAndRemainder(BASE)
            sb.append(ALPHABET[r.toInt()])
            n = q
        }
        for (b in bytes) {
            if (b.toInt() != 0) break
            sb.append('1')
        }
        return sb.reverse().toString()
    }
}
