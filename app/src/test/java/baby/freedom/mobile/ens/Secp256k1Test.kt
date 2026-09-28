package baby.freedom.mobile.ens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
}
