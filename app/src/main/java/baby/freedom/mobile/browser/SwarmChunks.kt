package baby.freedom.mobile.browser

import baby.freedom.mobile.ens.Keccak256
import baby.freedom.mobile.ens.Secp256k1
import baby.freedom.mobile.wallet.EthSigning
import baby.freedom.mobile.wallet.MessageSigning
import baby.freedom.mobile.wallet.NodeIdentity
import baby.freedom.mobile.wallet.PublisherKeys

/**
 * Swarm's chunk formats for the `window.swarm` provider (#120), byte for
 * byte as bee-js builds them (`chunk/cac.js`, `chunk/bmt.js`,
 * `chunk/soc.js`, `feed/identifier.js`) and as iOS's `SwarmSOC` /
 * `SwarmChunkCodec` do: content-addressed chunks (CACs), single-owner
 * chunks (SOCs) and the sequence-feed identifiers they're written at.
 * No I/O; [SwarmProvider] composes these with the node's HTTP API.
 *
 * A signing key passed in here is the caller's: used for one signature
 * and zeroed by the caller, never kept, logged or returned.
 */
internal object SwarmChunks {
    /** Bytes in a BMT leaf segment. */
    const val SEGMENT_SIZE = 32

    /** A chunk carries at most this much payload. */
    const val MAX_PAYLOAD = 4096

    /** `identifier(32) ‖ signature(65) ‖ span(8)`: what a SOC holds ahead of its payload. */
    const val SOC_HEADER = 32 + 65 + 8

    /** A content-addressed chunk: its 8-byte little-endian span, payload and address. */
    class Cac(val span: ByteArray, val payload: ByteArray, val address: ByteArray) {
        /** What `POST /chunks` and `POST /soc` take as the body: `span ‖ payload`. */
        fun data(): ByteArray = span + payload
        val spanValue: ULong get() = spanOf(span)
    }

    /**
     * The CAC holding [payload], its span the payload's length unless
     * [span] is given (a chunk that is the root of a larger tree carries
     * the whole tree's length). Payloads are 1..[MAX_PAYLOAD] bytes.
     */
    fun cac(payload: ByteArray, span: ULong? = null): Cac {
        require(payload.isNotEmpty()) { "payload must not be empty" }
        require(payload.size <= MAX_PAYLOAD) { "payload exceeds $MAX_PAYLOAD bytes" }
        val spanBytes = spanBytes(span ?: payload.size.toULong())
        return Cac(spanBytes, payload, Keccak256.digest(spanBytes + bmtRoot(payload)))
    }

    /**
     * A CAC read back from the node (`span ‖ payload`), or null if the
     * bytes don't hash to [reference] — the node's `/chunks` endpoint
     * doesn't promise a CAC, only a chunk at that address.
     */
    fun parseCac(reference: ByteArray, raw: ByteArray): Cac? {
        if (raw.size < 8 + 1 || raw.size > 8 + MAX_PAYLOAD) return null
        val cac = cac(raw.copyOfRange(8, raw.size), spanOf(raw.copyOfRange(0, 8)))
        return cac.takeIf { it.address.contentEquals(reference) }
    }

    /** A signed single-owner chunk. [owner] is the signer's 20-byte address. */
    class Soc(
        val identifier: ByteArray,
        val signature: ByteArray,
        val owner: ByteArray,
        val cac: Cac,
    ) {
        val address: ByteArray get() = socAddress(identifier, owner)
    }

    /** A SOC's address: `keccak256(identifier ‖ owner)`. */
    fun socAddress(identifier: ByteArray, owner: ByteArray): ByteArray {
        require(identifier.size == 32) { "identifier must be 32 bytes" }
        require(owner.size == 20) { "owner must be 20 bytes" }
        return Keccak256.digest(identifier + owner)
    }

    /**
     * Wraps [cac] in a SOC at [identifier], signed with [privateKey]:
     * EIP-191 `personal_sign` over `keccak256(identifier ‖ cac.address)`,
     * as bee-js's `PrivateKey.sign`. The signature is checked to recover
     * to the key's own address before it's returned.
     */
    fun sign(identifier: ByteArray, cac: Cac, privateKey: ByteArray): Soc {
        require(identifier.size == 32) { "identifier must be 32 bytes" }
        val digest = MessageSigning.personalDigest(Keccak256.digest(identifier + cac.address))
        val address = PublisherKeys.address(privateKey)
        val signature = EthSigning.sign(privateKey, digest).rsv()
        val recovered = Secp256k1.recover(digest, "0x" + signature.swarmHex())
        check(recovered != null && recovered.equals(address, ignoreCase = true)) { "the signature doesn't match the key" }
        return Soc(identifier, signature, address.removePrefix("0x").hexToBytesOrNull()!!, cac)
    }

    /**
     * A SOC read back from the node (`identifier ‖ signature ‖ span ‖
     * payload`), or null unless its signer and identifier hash to
     * [address] — the check that makes it that owner's chunk.
     */
    fun parseSoc(address: ByteArray, raw: ByteArray): Soc? {
        if (raw.size < SOC_HEADER + 1 || raw.size > SOC_HEADER + MAX_PAYLOAD) return null
        val identifier = raw.copyOfRange(0, 32)
        val signature = raw.copyOfRange(32, 97)
        val cac = cac(raw.copyOfRange(SOC_HEADER, raw.size), spanOf(raw.copyOfRange(97, SOC_HEADER)))
        val digest = MessageSigning.personalDigest(Keccak256.digest(identifier + cac.address))
        val owner = Secp256k1.recover(digest, "0x" + signature.swarmHex())?.removePrefix("0x")?.hexToBytesOrNull()
            ?: return null
        if (!socAddress(identifier, owner).contentEquals(address)) return null
        return Soc(identifier, signature, owner, cac)
    }

    /** A sequence feed's identifier for [index]: `keccak256(topic ‖ index as 8 bytes big-endian)`. */
    fun feedIdentifier(topic: ByteArray, index: Long): ByteArray {
        require(topic.size == 32) { "topic must be 32 bytes" }
        require(index >= 0) { "negative feed index" }
        val be = ByteArray(8) { (index ushr (8 * (7 - it))).toByte() }
        return Keccak256.digest(topic + be)
    }

    /** bee-js `Topic.fromString`: `keccak256` of the string's UTF-8. */
    fun topic(string: String): ByteArray = Keccak256.digest(string.toByteArray(Charsets.UTF_8))

    /** What an `updateFeed` entry holds: seconds since the epoch (8 bytes big-endian) ‖ the 32-byte reference. */
    fun referenceUpdate(reference: ByteArray, epochSeconds: Long): ByteArray {
        require(reference.size == 32) { "reference must be 32 bytes" }
        return ByteArray(8) { (epochSeconds ushr (8 * (7 - it))).toByte() } + reference
    }

    /** EIP-55 form of a 20-byte address. */
    fun checksum(address: ByteArray): String = NodeIdentity.checksum(address)

    fun spanBytes(value: ULong): ByteArray = ByteArray(8) { (value shr (8 * it)).toByte() }

    fun spanOf(bytes: ByteArray): ULong {
        require(bytes.size == 8) { "a span is 8 bytes" }
        var v = 0UL
        for (i in 7 downTo 0) v = (v shl 8) or (bytes[i].toULong() and 0xffUL)
        return v
    }

    /** The Binary Merkle Tree root over the payload zero-padded to [MAX_PAYLOAD]. */
    private fun bmtRoot(payload: ByteArray): ByteArray {
        var level = ByteArray(MAX_PAYLOAD).also { payload.copyInto(it) }
        while (level.size > SEGMENT_SIZE) {
            val next = ByteArray(level.size / 2)
            var i = 0
            while (i < level.size) {
                Keccak256.digest(level.copyOfRange(i, i + 2 * SEGMENT_SIZE)).copyInto(next, i / 2)
                i += 2 * SEGMENT_SIZE
            }
            level = next
        }
        return level
    }
}

internal fun ByteArray.swarmHex(): String {
    val out = CharArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt() and 0xff
        out[2 * i] = SWARM_HEX[v ushr 4]
        out[2 * i + 1] = SWARM_HEX[v and 0xf]
    }
    return String(out)
}

/** Hex (no `0x`, either case) as bytes, or null if it isn't hex. */
internal fun String.hexToBytesOrNull(): ByteArray? {
    if (length % 2 != 0) return null
    val out = ByteArray(length / 2)
    for (i in out.indices) {
        val hi = Character.digit(this[2 * i], 16)
        val lo = Character.digit(this[2 * i + 1], 16)
        if (hi < 0 || lo < 0) return null
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}

private val SWARM_HEX = "0123456789abcdef".toCharArray()
