package baby.freedom.mobile.ens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.math.BigInteger
import org.junit.Test

class Secp256k1Test {
    // (address, message, signature) made by ethers v6 `Wallet.signMessage`
    // — what the list publisher signs with — for three keys, including
    // the private keys 1 and n−1.
    private val vectors = listOf(
        Triple("0x2c7536e3605d9c16a7a3d7b1898e529396a65c23", "", "0x8a68b4e66cd2b575338e16069d7b65f6f67c7ceae8945dccf8cb7bdb06278d933dd9c888f3444ca4698464079a067ad3cfffe96d493b8ecf56885169d0fdfe7d1b"),
        Triple("0x2c7536e3605d9c16a7a3d7b1898e529396a65c23", "hello", "0xa5d58782075bdf09490159d634d1aae66a8f6777c7247d2f233e9511cfd7c64c34f288cdbcea5370e4863fdbe9f4d86654c2ba1d86589e9ebb64494c649008591b"),
        Triple("0x2c7536e3605d9c16a7a3d7b1898e529396a65c23", "ünïcødé ✓ 🚀", "0x5cf039ced0b05f3f996077e0d24229b623d763ce3973a121f73558ffc2befd9b35bf9582a879d7bfed7feb79c84b8eefaccf2c4d1a2b73e071c2e92406ca4d8b1c"),
        Triple("0x7e5f4552091a69125d5dfcb7b8c2659029395bdf", "hello", "0xe5ddc160e4c8f92de507c7db9b982d4f9b7197bfa421864aeadc586bc96b09ae0ba0c5b131650ae4994cff1839341d00f3735ef5abc62ac8fe2cf50f65208e2a1b"),
        Triple("0x7e5f4552091a69125d5dfcb7b8c2659029395bdf", "ünïcødé ✓ 🚀", "0x6b1aa03f453ab3cad328f2bcad9e399a34fd5d11c20e8f02d538e869beb741b06fca58b72e249fc38a542c68d9900350ae1c01e1afbdf05dc84783b529c893171c"),
        Triple("0x80c0dbf239224071c59dd8970ab9d542e3414ab2", "x".repeat(300), "0xe487ca101fd43f6390a1e988bcb08814a1c465d403c34976a7c578dbf3e7730a660c8c3df63b1e3ca2590a71291b08186fbe009af52876b93a95330e8589117a1b"),
        Triple("0x80c0dbf239224071c59dd8970ab9d542e3414ab2", "ünïcødé ✓ 🚀", "0x53f238cad84fb2ba3ea71ed7c2e553d05c2813c190110749a70954d6c75d95760ef03813beb2f89bb45c527685c77288a1c9e0d0463a365e4d1ec3bc7d41399c1c"),
    )

    @Test
    fun `recovers the signer of ethers personal_sign signatures`() {
        for ((address, message, sig) in vectors) {
            assertEquals(message, address, Secp256k1.recoverPersonalSign(message.toByteArray(), sig))
        }
    }

    @Test
    fun `a signature over a different message recovers someone else`() {
        val (address, _, sig) = vectors[1]
        assertNotEquals(address, Secp256k1.recoverPersonalSign("hellO".toByteArray(), sig))
    }

    @Test
    fun `v may be 0 or 1 as well as 27 or 28`() {
        val (address, message, sig) = vectors[1]
        val v = sig.takeLast(2).toInt(16) - 27
        val raw = sig.dropLast(2) + "%02x".format(v)
        assertEquals(address, Secp256k1.recoverPersonalSign(message.toByteArray(), raw))
    }

    @Test
    fun `malformed signatures recover nobody`() {
        val (_, message, sig) = vectors[1]
        val bytes = message.toByteArray()
        assertNull(Secp256k1.recoverPersonalSign(bytes, ""))
        assertNull(Secp256k1.recoverPersonalSign(bytes, sig.dropLast(2)))
        assertNull(Secp256k1.recoverPersonalSign(bytes, sig + "00"))
        assertNull(Secp256k1.recoverPersonalSign(bytes, sig.dropLast(2) + "1d")) // v = 29
        assertNull(Secp256k1.recoverPersonalSign(bytes, "0x" + "00".repeat(64) + "1b")) // r = s = 0
        assertNull(Secp256k1.recoverPersonalSign(bytes, "0x" + "ff".repeat(64) + "1b")) // r, s ≥ n
        assertNull(Secp256k1.recoverPersonalSign(bytes, sig.replaceRange(10, 11, "g")))
        // Non-ASCII digits Character.digit would take.
        assertNull(Secp256k1.recoverPersonalSign(bytes, sig.replaceRange(10, 11, "٣")))
    }

    private val n = BigInteger("fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141", 16)

    private fun hex32(v: BigInteger) = v.toString(16).padStart(64, '0')

    private fun hexBytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    @Test
    fun `go-ethereum's ecrecover precompile vector`() {
        // core/vm/contracts_test.go, "ValidKey": hash, v = 28, r, s.
        val digest = hexBytes("456e9aea5e197a1f1af7a3e85a3212fa4049a3ba34c2289b4c860fc0b0c64ef3")
        val sig = "9242685bf161793cc25603c231bc2f568eb630ea16aa137d2664ac8038825608" +
            "4f8ae3bd7535248d0bd448298cc2e2071e56992d0774dc340c368ae950852ada1c"
        assertEquals("0x7156526fbd7a3c72969b54f64e42c10fbb768c8a", Secp256k1.recover(digest, sig))
    }

    @Test
    fun `agrees with Wycheproof and an independent implementation on every recoverable vector`() {
        val lines = javaClass.getResource("/secp256k1/wycheproof-ecdsa-secp256k1-sha256-recover.txt")!!
            .readText().lines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(190, lines.size)
        val seen = HashMap<String, Int>()
        for (line in lines) {
            val f = line.split(" ")
            val (tcId, digestHex, r, s, signer) = f
            val rec0 = f[5]
            val rec1 = f[6]
            val expect = f[7]
            val digest = hexBytes(digestHex)
            val got = listOf("1b", "1c").map { v -> Secp256k1.recover(digest, r + s + v) ?: "-" }
            assertEquals("tcId $tcId", listOf(rec0, rec1), got)
            when (expect) {
                "valid" -> assertTrue("tcId $tcId recovers its signer", signer in got)
                "invalid", "largex" -> assertTrue("tcId $tcId must not recover its signer", signer !in got)
                else -> error("tcId $tcId: $expect")
            }
            seen[expect] = (seen[expect] ?: 0) + 1
        }
        assertEquals(mapOf("valid" to 166, "invalid" to 22, "largex" to 2), seen)
    }

    @Test
    fun `a high-s signature recovers the same signer with the other recovery id`() {
        // Malleability doesn't matter here: the signature isn't used as an
        // identifier, and (n - s, flipped v) is still the pinned key's
        // signature over the same bytes — as the EVM's ecrecover and
        // Wycheproof's secp256k1_sha256 (not the _bitcoin variant) take it.
        val (address, message, sig) = vectors[1]
        val r = sig.substring(2, 66)
        val s = BigInteger(sig.substring(66, 130), 16)
        val v = sig.takeLast(2).toInt(16)
        val highS = r + hex32(n - s)
        assertTrue(n - s > n.shiftRight(1))
        assertEquals(address, Secp256k1.recoverPersonalSign(message.toByteArray(), highS + "%02x".format(55 - v)))
        assertNotEquals(address, Secp256k1.recoverPersonalSign(message.toByteArray(), highS + "%02x".format(v)))
    }

    @Test
    fun `out-of-range r and s recover nobody`() {
        val (_, message, sig) = vectors[1]
        val bytes = message.toByteArray()
        val r = sig.substring(2, 66)
        val s = sig.substring(66, 130)
        for (bad in listOf(BigInteger.ZERO, n, n + BigInteger.ONE, BigInteger.ONE.shiftLeft(256) - BigInteger.ONE)) {
            assertNull(bad.toString(16), Secp256k1.recoverPersonalSign(bytes, hex32(bad) + s + "1b"))
            assertNull(bad.toString(16), Secp256k1.recoverPersonalSign(bytes, r + hex32(bad) + "1b"))
        }
        // r = 5 is no point's x coordinate: y² = 132 has no root mod p.
        assertNull(Secp256k1.recoverPersonalSign(bytes, hex32(BigInteger.valueOf(5)) + s + "1b"))
    }

    @Test
    fun `recovery ids other than 0 and 1 recover nobody`() {
        val (_, message, sig) = vectors[1]
        val body = sig.dropLast(2)
        // 2/3 (x = r + n) are never made by ethers; 29/30 their v form;
        // EIP-155 v (chainId·2 + 35) doesn't apply to personal_sign.
        for (v in listOf(2, 3, 4, 26, 29, 30, 35, 36, 0xff)) {
            assertNull("v=$v", Secp256k1.recoverPersonalSign(message.toByteArray(), body + "%02x".format(v)))
        }
    }

    @Test
    fun `non-canonical encodings recover nobody`() {
        val (address, message, sig) = vectors[1]
        val bytes = message.toByteArray()
        val raw = sig.removePrefix("0x")
        assertNull(Secp256k1.recoverPersonalSign(bytes, "0x00$raw")) // 66 bytes, leading zero
        assertNull(Secp256k1.recoverPersonalSign(bytes, "0x${raw}0")) // odd length
        assertNull(Secp256k1.recoverPersonalSign(bytes, "0x0x$raw"))
        assertNull(Secp256k1.recoverPersonalSign(bytes, " $sig"))
        assertNull(Secp256k1.recoverPersonalSign(bytes, "$sig\n"))
        assertNull(Secp256k1.recoverPersonalSign(bytes, raw.substring(0, 64) + "00" + raw.substring(64))) // r padded to 33 bytes
        // The same 65 bytes in upper case or without 0x are the same signature.
        assertEquals(address, Secp256k1.recoverPersonalSign(bytes, raw))
        assertEquals(address, Secp256k1.recoverPersonalSign(bytes, "0x" + raw.uppercase()))
    }

    @Test
    fun `a digest that isn't 32 bytes recovers nobody`() {
        val digest = hexBytes("456e9aea5e197a1f1af7a3e85a3212fa4049a3ba34c2289b4c860fc0b0c64ef3")
        val sig = "9242685bf161793cc25603c231bc2f568eb630ea16aa137d2664ac8038825608" +
            "4f8ae3bd7535248d0bd448298cc2e2071e56992d0774dc340c368ae950852ada1c"
        assertNull(Secp256k1.recover(digest.copyOf(31), sig))
        assertNull(Secp256k1.recover(digest.copyOf(33), sig))
    }
}
