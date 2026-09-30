package baby.freedom.mobile.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BIP-32 test vectors 2–4 for [HdKeys.secp256k1] (vector 1 is in
 * [NodeIdentityTest]): a 64-byte seed and the largest hardened index
 * (vector 2), and a master and a hardened child private key that start
 * with a zero byte (vectors 3 and 4), which must be kept as 32 bytes —
 * the leading-zero bug these two vectors were added to BIP-32 for.
 * Every wallet account, the Swarm key and each publisher key goes
 * through this code.
 *
 * The fourth test checks, through the [HdKeys.scratchSeen] hook, that
 * every HMAC output and every `I_L` copy made while deriving a
 * secp256k1 and an ed25519 key is all-zero by the time the key is
 * returned.
 */
class HdKeysTest {
    private fun vectors(seed: String, expected: Map<String, String>) {
        for ((path, key) in expected) assertEquals(path, key, HdKeys.secp256k1(seed.unhex(), path).hex())
    }

    @Test
    fun `BIP-32 test vector 2`() = vectors(
        "fffcf9f6f3f0edeae7e4e1dedbd8d5d2cfccc9c6c3c0bdbab7b4b1aeaba8a5a29f9c999693908d8a8784817e7b7875726f6c696663605d5a5754514e4b484542",
        mapOf(
            "m" to "4b03d6fc340455b363f51020ad3ecca4f0850280cf436c70c727923f6db46c3e",
            "m/0" to "abe74a98f6c7eabee0428f53798f0ab8aa1bd37873999041703c742f15ac7e1e",
            "m/0/2147483647'" to "877c779ad9687164e9c2f4f0f4ff0340814392330693ce95a58fe18fd52e6e93",
            "m/0/2147483647'/1" to "704addf544a06e5ee4bea37098463c23613da32020d604506da8c0518e1da4b7",
            "m/0/2147483647'/1/2147483646'" to "f1c7c871a54a804afe328b4c83a1c33b8e5ff48f5087273f04efa83b247d6a2d",
            "m/0/2147483647'/1/2147483646'/2" to "bb7d39bdb83ecf58f2fd82b6d918341cbef428661ef01ab97c28a4842125ac23",
        ),
    )

    @Test
    fun `BIP-32 test vector 3 - a master key with a leading zero`() = vectors(
        "4b381541583be4423346c643850da4b320e46a87ae3d2a4e6da11eba819cd4acba45d239319ac14f863b8d5ab5a0d0c64d2e8a1e7d1457df2e5a3c51c73235be",
        mapOf(
            "m" to "00ddb80b067e0d4993197fe10f2657a844a384589847602d56f0c629c81aae32",
            "m/0'" to "491f7a2eebc7b57028e0d3faa0acda02e75c33b03c48fb288c41e2ea44e1daef",
        ),
    )

    @Test
    fun `BIP-32 test vector 4 - a hardened child with a leading zero`() = vectors(
        "3ddd5602285899a946114506157c7997e5444528f3003f6134712147db19b678",
        mapOf(
            "m" to "12c0d59c7aa3a10973dbd3f478b65f2516627e3fe61e00c345be9a477ad2e215",
            "m/0'" to "00d948e9261e41362a688b916f297121ba6bfb2274a3575ac0e456551dfd7f7e",
            "m/0'/1'" to "3a2086edd7d9df86c3487a5905a1712a9aa664bce8cc268141e07549eaa8661d",
        ),
    )

    @Test
    fun `every scratch secret - HMAC outputs and I_L - is zeroed once a key is derived`() {
        val seen = mutableListOf<ByteArray>()
        HdKeys.scratchSeen = { seen += it }
        try {
            val seed = "3ddd5602285899a946114506157c7997e5444528f3003f6134712147db19b678".unhex()
            // Hardened and non-hardened secp256k1 levels, then SLIP-0010.
            val key = HdKeys.secp256k1(seed, "m/44'/60'/0'/0/1")
            val ed = HdKeys.ed25519(seed, "m/44'/73405'/0'")
            assertTrue(key.any { it != 0.toByte() } && ed.any { it != 0.toByte() })
            // master + 5 child HMACs + 5 I_L copies, then master + 3 child HMACs.
            assertEquals(15, seen.size)
            seen.forEachIndexed { n, b -> assertTrue("scratch #$n not zeroed", b.all { it == 0.toByte() }) }
        } finally {
            HdKeys.scratchSeen = null
        }
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private fun String.unhex() = ByteArray(length / 2) { substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}
