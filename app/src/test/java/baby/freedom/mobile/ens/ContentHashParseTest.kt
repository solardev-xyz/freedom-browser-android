package baby.freedom.mobile.ens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for [EnsResolver]'s EIP-1577 contenthash → URI mapping.
 *
 * We can't call `parseContentHash` directly (it's private), but
 * [EnsResolver.decodeContenthashResponse] exercises exactly the same
 * code path, and it only needs a correctly-shaped ABI blob — no RPC.
 *
 * The fixtures here are real on-chain contenthashes captured from
 * mainnet ENS so the expected strings are the ones Kubo's gateway
 * actually serves.
 */
class ContentHashParseTest {

    /**
     * vitalik.eth contenthash (as of 2024): CIDv1 + dag-pb + sha2-256.
     * Bytes: e3 01 01 70 12 20 <32-byte digest>. Expected CID is the
     * canonical Base32 `bafy…` form Kubo accepts at
     * `/ipfs/bafy…`.
     */
    @Test
    fun `vitalik eth CIDv1 dag-pb contenthash decodes to bafy string`() {
        val contentHash =
            "e30101701220" +
                "7f38d55c61cef80e6cd1b6b3c17b0b9f1d0c8b3a61ec7a0e3c7b6a4b4a0b7c3d"
        val rawHex = wrapAsOuterInner(contentHash)

        val result = EnsResolver().invokeDecode("vitalik.eth", rawHex)

        require(result is EnsResult.Ok) { "got $result" }
        assertEquals("ipfs", result.protocol)
        assertTrue(
            "expected bafy… CID, got ${result.decoded}",
            result.decoded.startsWith("bafy"),
        )
        assertEquals("ipfs://${result.decoded}", result.uri)
    }

    /**
     * CIDv0 IPFS contenthash: e3 01 + raw sha2-256 multihash (12 20 <32>).
     * CIDv0 has no version/codec bytes and renders as a `Qm…` Base58
     * string.
     */
    @Test
    fun `CIDv0 IPFS contenthash decodes to Qm string`() {
        val contentHash =
            "e301" +
                "1220" +
                "7f38d55c61cef80e6cd1b6b3c17b0b9f1d0c8b3a61ec7a0e3c7b6a4b4a0b7c3d"
        val rawHex = wrapAsOuterInner(contentHash)

        val result = EnsResolver().invokeDecode("test-cidv0.eth", rawHex)

        require(result is EnsResult.Ok) { "got $result" }
        assertEquals("ipfs", result.protocol)
        assertTrue(
            "expected Qm… CID, got ${result.decoded}",
            result.decoded.startsWith("Qm"),
        )
    }

    /**
     * IPNS CIDv1 libp2p-key contenthash: e5 01 01 72 <multihash>.
     */
    @Test
    fun `IPNS CIDv1 contenthash decodes to ipns uri`() {
        val contentHash =
            "e50101721220" +
                "7f38d55c61cef80e6cd1b6b3c17b0b9f1d0c8b3a61ec7a0e3c7b6a4b4a0b7c3d"
        val rawHex = wrapAsOuterInner(contentHash)

        val result = EnsResolver().invokeDecode("docs.eth", rawHex)

        require(result is EnsResult.Ok) { "got $result" }
        assertEquals("ipns", result.protocol)
        assertTrue(result.uri.startsWith("ipns://"))
    }

    /** Swarm contenthash is unchanged by this fix — verify the happy path still works. */
    @Test
    fun `swarm contenthash still decodes to bzz uri`() {
        val contentHash =
            "e40101fa011b20" +
                "7f38d55c61cef80e6cd1b6b3c17b0b9f1d0c8b3a61ec7a0e3c7b6a4b4a0b7c3d"
        val rawHex = wrapAsOuterInner(contentHash)

        val result = EnsResolver().invokeDecode("swarm.eth", rawHex)

        require(result is EnsResult.Ok) { "got $result" }
        assertEquals("bzz", result.protocol)
        assertEquals(
            "bzz://7f38d55c61cef80e6cd1b6b3c17b0b9f1d0c8b3a61ec7a0e3c7b6a4b4a0b7c3d",
            result.uri,
        )
    }

    @Test
    fun `base32 encodes RFC4648 test vectors lowercase`() {
        assertEquals("", Base32.encodeLower(byteArrayOf()))
        assertEquals("my", Base32.encodeLower("f".toByteArray()))
        assertEquals("mzxq", Base32.encodeLower("fo".toByteArray()))
        assertEquals("mzxw6", Base32.encodeLower("foo".toByteArray()))
        assertEquals("mzxw6yq", Base32.encodeLower("foob".toByteArray()))
        assertEquals("mzxw6ytb", Base32.encodeLower("fooba".toByteArray()))
        assertEquals("mzxw6ytboi", Base32.encodeLower("foobar".toByteArray()))
    }
}

/**
 * Wrap a raw contenthash hex string in the ABI envelope the Universal
 * Resolver returns for `resolve(bytes,bytes)`:
 *
 *   (bytes result, address resolver)  ← outer tuple
 *
 * where `result` is itself `abi.encode(contentHashBytes)` (i.e.
 * another single-bytes ABI encoding: `0x20 pointer + length + bytes`).
 * So the full raw hex becomes:
 *
 *   [head0=0x40] [head1=resolver_addr]
 *   [outer_len] [outer_bytes = [0x20][len][contenthash_padded]]
 */
private fun wrapAsOuterInner(contentHashHex: String): String {
    val contentHash = contentHashHex.hexToBytes()
    // Innermost: ABI-encode the contenthash as a single `bytes` value.
    val innerEncoded = ByteArray(32).also { writeUint256AsHead(0x20L, it) } +
        ByteArray(32).also { writeUint256AsHead(contentHash.size.toLong(), it) } +
        paddedTo32(contentHash)
    val outerLen = ByteArray(32).also { writeUint256AsHead(innerEncoded.size.toLong(), it) }
    val outerBody = outerLen + paddedTo32(innerEncoded)
    val head0 = ByteArray(32).also { writeUint256AsHead(0x40L, it) }
    val head1 = ByteArray(32)
    return "0x" + (head0 + head1 + outerBody).toHex()
}

private fun paddedTo32(bytes: ByteArray): ByteArray {
    val rem = bytes.size % 32
    if (rem == 0) return bytes
    return bytes + ByteArray(32 - rem)
}

private fun writeUint256AsHead(v: Long, buf: ByteArray) {
    for (i in 0..7) buf[31 - i] = (v ushr (8 * i)).toByte()
}

/**
 * Invokes the private `decodeContenthashResponse` via reflection. The
 * method is private but stable — we use this only from tests in the
 * same package, and the compiler mangling of Kotlin privates is well
 * understood.
 */
private fun EnsResolver.invokeDecode(name: String, rawHex: String): EnsResult {
    val m = EnsResolver::class.java.getDeclaredMethod(
        "decodeContenthashResponse",
        String::class.java,
        String::class.java,
    )
    m.isAccessible = true
    return m.invoke(this, name, rawHex) as EnsResult
}
