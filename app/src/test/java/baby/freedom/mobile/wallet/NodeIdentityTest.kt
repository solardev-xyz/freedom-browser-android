package baby.freedom.mobile.wallet

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The node identities (#77) against the cross-platform golden vectors:
 * iOS's `docs/ipfs-identity-golden-vectors.md` for IPFS, desktop's
 * `identity/derivation.js` + `formats.js` (ethers / micro-key-producer)
 * for Swarm, and the BIP-32, SLIP-0010 and RFC 8032 spec vectors for the
 * layers underneath.
 */
class NodeIdentityTest {
    private val abandon12 = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    private val abandon24 = (List(23) { "abandon" } + "art").joinToString(" ")
    private val legal12 = "legal winner thank year wave sausage worth useful legal winner thank yellow"

    private fun identity(phrase: String) = NodeIdentity.derive(Mnemonic.parse(phrase).seed())

    // ---- iOS golden vectors (IPFS) ----

    @Test
    fun `vector 1 - 12-word phrase gives the golden IPFS identity`() {
        val seed = Mnemonic.parse(abandon12).seed()
        assertEquals(
            "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc1" +
                "9a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4",
            seed.hex(),
        )
        val id = NodeIdentity.derive(seed)
        assertEquals("6d7c32198dff963096b93296acb383c9b4f2bd85a4e52c123bfee1e5cd00c749", id.ipfsKey.hex())
        assertEquals("91237ad5959a25e025487deebe7bbae444e5ac2dfcd6fd10442d43dd87ef5647", id.ipfsPublicKey.hex())
        assertEquals("CAESQG18MhmN/5Ywlrkylqyzg8m08r2FpOUsEjv+4eXNAMdJkSN61ZWaJeAlSH3uvnu65ETlrC381v0QRC1D3YfvVkc=", id.libp2pPrivateKey())
        assertEquals("12D3KooWKavfSLKnBEoUdrcsZKHE2tCWxrPkND6psrNRyL8DgtYW", id.peerId)
    }

    @Test
    fun `vector 2 - 24-word phrase gives the golden IPFS identity`() {
        val seed = Mnemonic.parse(abandon24).seed()
        assertEquals(
            "408b285c123836004f4b8842c89324c1f01382450c0d439af345ba7fc49acf70" +
                "5489c6fc77dbd4e3dc1dd8cc6bc9f043db8ada1e243c4a0eafb290d399480840",
            seed.hex(),
        )
        val id = NodeIdentity.derive(seed)
        assertEquals("4212f8cf43eaa7070644e7b50a7e0d6ad7d02d8318890cb38d39935f66ba721a", id.ipfsKey.hex())
        assertEquals("f7120786aaf4255b760002c2051de31306dc4f73bca521fa842df307908c0f13", id.ipfsPublicKey.hex())
        assertEquals("CAESQEIS+M9D6qcHBkTntQp+DWrX0C2DGIkMs405k19munIa9xIHhqr0JVt2AALCBR3jEwbcT3O8pSH6hC3zB5CMDxM=", id.libp2pPrivateKey())
        assertEquals("12D3KooWSSppFRXRiW23YYh5zC8ZqSR2C3UgL2oJqhM5PjsAmxPk", id.peerId)
    }

    // ---- Desktop parity (Swarm; one more IPFS) ----

    @Test
    fun `swarm key, address and overlay match desktop`() {
        val a = identity(abandon12)
        assertEquals("9a983cb3d832fbde5ab49d692b7a8bf5b5d232479c99333d0fc8e1d21f1b55b6", a.swarmKey.hex())
        assertEquals("0x6Fac4D18c912343BF86fa7049364Dd4E424Ab9C0", a.swarmAddress)
        assertEquals("ffc594c6c113aedba5445e3b7405ea7845b605d562288a4491c7777ccd2b0279", a.swarmOverlay)

        val b = identity(abandon24)
        assertEquals("0855b75d03a8830e390b5483d81694c9c7121d971e092145cf8b9c6fa3a5b373", b.swarmKey.hex())
        assertEquals("0xf785bD075874b8423D3583728a981399f31e95aA", b.swarmAddress)
        assertEquals("e784416f7b161b879d01ad7e56211be7611869696ad4b71256fc6b893486c49e", b.swarmOverlay)

        val c = identity(legal12)
        assertEquals("5e4f6a62c67d2b8735d3cda66e37854d7a80f59aab639c3a4bed7e2c7432a4e4", c.swarmKey.hex())
        assertEquals("0x0D3eB21b6b21833A4939Cfff4810E9AE0758e12C", c.swarmAddress)
        assertEquals("f9ca601ba3a8b47c332ef1b29596e178b43589d1a8b8581285ae430538dfa676", c.swarmOverlay)
        assertEquals("12D3KooWNXaAWoQzdJrPkf2BtM48fPXV3CXuEaDfcSpd9Wpejiut", c.peerId)
    }

    @Test
    fun `ant identity document carries the key and a zero overlay nonce, no libp2p key`() {
        val id = identity(abandon12)
        val json = org.json.JSONObject(String(id.antIdentityJson(), Charsets.UTF_8))
        assertEquals(setOf("signing_key", "overlay_nonce"), json.keys().asSequence().toSet())
        assertEquals("9a983cb3d832fbde5ab49d692b7a8bf5b5d232479c99333d0fc8e1d21f1b55b6", json.getString("signing_key"))
        assertEquals("0".repeat(64), json.getString("overlay_nonce"))
    }

    @Test
    fun `toString never prints a key`() {
        val id = identity(abandon12)
        val s = id.toString()
        assertFalse(s.contains(id.swarmKey.hex()))
        assertFalse(s.contains(id.ipfsKey.hex()))
        assertTrue(s.contains(id.peerId))
    }

    @Test
    fun `wipe zeroes both keys`() {
        val id = identity(abandon12)
        id.wipe()
        assertTrue(id.swarmKey.all { it.toInt() == 0 })
        assertTrue(id.ipfsKey.all { it.toInt() == 0 })
    }

    // ---- Spec vectors for the layers underneath ----

    private val specSeed = "000102030405060708090a0b0c0d0e0f".unhex()

    @Test
    fun `BIP-32 test vector 1`() {
        val expected = mapOf(
            "m/0'" to "edb2e14f9ee77d26dd93b4ecede8d16ed408ce149b6cd80b0715a2d911a0afea",
            "m/0'/1" to "3c6cb8d0f6a264c91ea8b5030fadaa8e538b020f0a387421a12de9319dc93368",
            "m/0'/1/2'" to "cbce0d719ecf7431d88e6a89fa1483e02e35092af60c042b1df2ff59fa424dca",
            "m/0'/1/2'/2" to "0f479245fb19a38a1954c5c7c0ebab2f9bdfd96a17563ef28a6a4b1a2a764ef4",
            "m/0'/1/2'/2/1000000000" to "471b76e389e528d6de6d816857e012c5455051cad6660850e58372a6c3e6e7c8",
        )
        for ((path, key) in expected) assertEquals(path, key, HdKeys.secp256k1(specSeed, path).hex())
    }

    @Test
    fun `SLIP-0010 ed25519 test vector 1`() {
        val m0 = HdKeys.ed25519(specSeed, "m/0'")
        assertEquals("68e0fe46dfb67e368c75379acec591dad19df3cde26e63b93a8e704f1dade7a3", m0.hex())
        assertEquals("8c8a13df77a28f3445213a0f432fde644acaa215fc72dcdf300d5efaa85d350c", Ed25519.publicKey(m0).hex())
        val deep = HdKeys.ed25519(specSeed, "m/0'/1'/2'/2'/1000000000'")
        assertEquals("8f94d394a8e8fd6b1bc2f3f49f5c47e385281d5c17e65324b0f62483e37e8793", deep.hex())
        assertEquals("3c24da049451555d51a7014a37337aa4e12d41e485abccfa46b47dfb2af54b7a", Ed25519.publicKey(deep).hex())
    }

    @Test
    fun `SLIP-0010 ed25519 refuses a non-hardened segment`() {
        assertThrows(IllegalArgumentException::class.java) { HdKeys.ed25519(specSeed, "m/0'/1") }
    }

    @Test
    fun `RFC 8032 test 1 public key`() {
        val sk = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60".unhex()
        assertArrayEquals("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a".unhex(), Ed25519.publicKey(sk))
    }

    @Test
    fun `secp256k1 public key of 1 is the generator`() {
        val one = ByteArray(32).also { it[31] = 1 }
        assertEquals(
            "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798",
            Secp256k1Keys.publicKeyCompressed(one).hex(),
        )
    }

    @Test
    fun `path parser`() {
        assertArrayEquals(
            longArrayOf(44 + HdKeys.HARDENED, 60 + HdKeys.HARDENED, HdKeys.HARDENED, 0, 1),
            HdKeys.parsePath(NodeIdentity.SWARM_PATH),
        )
        for (bad in listOf("", "44'/0", "m/x", "m/2147483648", "m/-1", "m//1")) {
            assertThrows(bad, IllegalArgumentException::class.java) { HdKeys.parsePath(bad) }
        }
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private fun String.unhex() = ByteArray(length / 2) { substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}
