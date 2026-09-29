package baby.freedom.mobile.wallet

import baby.freedom.mobile.chains.BuiltInChains
import baby.freedom.mobile.ens.Keccak256
import baby.freedom.mobile.ens.hexToBytes
import baby.freedom.mobile.ens.toHex
import java.math.BigInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Signing and encoding (#105), against vectors from ethers v6 (desktop's
 * signer): `Wallet.signTransaction` / `SigningKey.sign` with the public
 * Hardhat test key, and EIP-155's own example.
 */
class EthTransactionsTest {
    // Hardhat account 0: a published test key, never a funded one.
    private val key = "ac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80".hexToBytes()
    private val from = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266"
    private val to = "0x70997970C51812dc3A010C7d01b50e0d17dc79C8"

    private fun big(v: Long) = BigInteger.valueOf(v)

    @Test
    fun `the key's address is the one ethers derives`() {
        assertEquals(from, PublisherKeys.address(key))
    }

    @Test
    fun `RFC 6979 low-s signatures match ethers`() {
        val cases = listOf(
            Triple("", "f9a45c11d904141226963b371ccbc2128a29d146ce3c0d43326414711e3e153d", "536804917f10c70c5d0cd7b730b559d95eb812d1d783f6dd534e5854f01b3e55" to 0),
            Triple("a", "ee9dad68947d5129295f88d85867b09b7c8d2ec0fe26480131f889c8424597e2", "4894965a0f2860363d7ec7a3b0b105d9df07e68a2d81327844f69b8a9f5042d6" to 1),
            Triple("freedom", "76987fda5374892806eedba8a3a8816276aad5bf0cb02cc1265e9c7e4c6687c5", "41eb0601cc99e199ac2b50120203bf0a0eeb92fb1f9dee63e70f7cde1748d7b3" to 1),
        )
        for ((message, r, sv) in cases) {
            val sig = EthSigning.sign(key, Keccak256.digest(message))
            assertEquals(message, r, HdKeys.to32(sig.r).toHex())
            assertEquals(message, sv.first, HdKeys.to32(sig.s).toHex())
            assertEquals(message, sv.second, sig.recoveryId)
        }
    }

    @Test
    fun `a native EIP-1559 send on Gnosis encodes and hashes as ethers does`() {
        val tx = EthTransaction(
            chainId = 100, nonce = big(11), gasLimit = big(21_000), to = to, value = BigInteger.ONE, data = ByteArray(0),
            fees = EthTransaction.Fees.Eip1559(maxFeePerGas = big(1_000_000_028), maxPriorityFeePerGas = big(1_000_000_000)),
        )
        val signed = tx.sign(key, from)
        assertEquals(
            "0x02f86a640b843b9aca00843b9aca1c8252089470997970c51812dc3a010c7d01b50e0d17dc79c80180c001a0d1f49e8801e78be0f1a3d7b1" +
                "71d2646ff3057c5271443dc4a34f63f744c94003a018542d865b18f7da9d01da99516943e49d216bd88613c4b6614a3702af8869a7",
            signed.raw,
        )
        assertEquals("0x028d5f4213a11e8dbed5f23f1f1516e91cf92e8e2255a6233adf7ae313049d52", signed.hash)
    }

    @Test
    fun `an ERC-20 transfer carries transfer(to, amount) to the token with no value`() {
        val xbzz = TokenRegistry.builtins.first { it.symbol == "xBZZ" }
        val request = SendRequest(BuiltInChains.GNOSIS, xbzz, WalletAccount(0, "Account 1", from), to, BigInteger("1234567890123456789"))
        val (callTo, value, data) = request.call()
        assertEquals(xbzz.address, callTo)
        assertEquals(BigInteger.ZERO, value)
        assertEquals(
            "a9059cbb00000000000000000000000070997970c51812dc3a010c7d01b50e0d17dc79c8000000000000000000000000000000000000000000000000112210f47de98115",
            data.toHex(),
        )
        val tx = EthTransaction(
            chainId = 100, nonce = BigInteger.ZERO, gasLimit = big(65_000), to = callTo, value = value, data = data,
            fees = EthTransaction.Fees.Eip1559(BigInteger.ZERO, BigInteger.ZERO),
        )
        val signed = tx.sign(key, from)
        assertEquals(
            "0x02f8a76480808082fde894dbf3ea6f5bee45c02255b2c26a16f300502f68da80b844a9059cbb00000000000000000000000070997970c5" +
                "1812dc3a010c7d01b50e0d17dc79c8000000000000000000000000000000000000000000000000112210f47de98115c080a08c1ad7e4e6c557" +
                "55f304dbdb2546b6adbf73700c5ebd9847a2abf1a11b900dd7a05a09c504cd20d3e95b525d6f13d0f334d064a25561c75f9b75aadc5b984b9fa0",
            signed.raw,
        )
        assertEquals("0x75da3291d6fd935f4b02a9b9a188cba5162a107b806987d9732f850c30b8161e", signed.hash)
    }

    @Test
    fun `an Ethereum mainnet EIP-1559 send with a multi-byte nonce and 1 ETH`() {
        val tx = EthTransaction(
            chainId = 1, nonce = big(300), gasLimit = big(21_000), to = to, value = BigInteger.TEN.pow(18), data = ByteArray(0),
            fees = EthTransaction.Fees.Eip1559(maxFeePerGas = big(30_000_000_000), maxPriorityFeePerGas = big(1_500_000_000)),
        )
        val signed = tx.sign(key, from)
        assertEquals(
            "0x02f8750182012c8459682f008506fc23ac008252089470997970c51812dc3a010c7d01b50e0d17dc79c8880de0b6b3a764000080c080a09b" +
                "75c7e8e79fb6b517639585c1286c5056af4c1202c403a1f39fb9aa0bc9b375a0793f92e3f935ba82f96613efaf53115c6b0daccbfa3ac26d1d30acd885678d8d",
            signed.raw,
        )
        assertEquals("0xddfceb946dcdd9abffc3effed63561b7e92898d9f5fa3494f4bf5b67a403622b", signed.hash)
    }

    @Test
    fun `legacy EIP-155 transactions, including the EIP's own example`() {
        val legacy = EthTransaction(
            chainId = 100, nonce = big(5), gasLimit = big(21_000), to = to, value = big(123), data = ByteArray(0),
            fees = EthTransaction.Fees.Legacy(big(2_000_000_000)),
        ).sign(key, from)
        assertEquals(
            "0xf8640584773594008252089470997970c51812dc3a010c7d01b50e0d17dc79c87b8081eba087951f3c997a06e6db1505b7e6366bba0a5a35bd" +
                "a94423c10f5ecf4f98d89e5ba079e9dd8e876f86619bf00c3754cc5c9138fc58da5874ada691eae33472d50238",
            legacy.raw,
        )
        assertEquals("0x60014a6ed0f83479ea913b0e288f51041bfb734e0808ee6e1a21123ed7386938", legacy.hash)

        val eip155 = EthTransaction(
            chainId = 1, nonce = big(9), gasLimit = big(21_000), to = "0x3535353535353535353535353535353535353535",
            value = BigInteger.TEN.pow(18), data = ByteArray(0), fees = EthTransaction.Fees.Legacy(big(20_000_000_000)),
        ).sign(ByteArray(32) { 0x46 }, "0x9d8A62f656a8d1615C1294fd71e9CFb3E4855A4F")
        assertEquals(
            "0xf86c098504a817c800825208943535353535353535353535353535353535353535880de0b6b3a76400008025a028ef61340bd939bc2195fe53" +
                "7567866003e1a15d3c71ff63e1590620aa636276a067cbe9d8997f761aecb703304b3800ccf555c9f3dc64214b297fb1966a3b6d83",
            eip155.raw,
        )
    }

    @Test
    fun `a signature that recovers to another account is refused`() {
        val tx = EthTransaction(
            chainId = 100, nonce = BigInteger.ZERO, gasLimit = big(21_000), to = to, value = BigInteger.ONE, data = ByteArray(0),
            fees = EthTransaction.Fees.Legacy(BigInteger.ONE),
        )
        assertThrows(IllegalStateException::class.java) { tx.sign(key, to) }
    }

    @Test
    fun `RLP encodes the edge cases the transaction fields hit`() {
        assertArrayEquals(byteArrayOf(0x80.toByte()), Rlp.quantity(0))
        assertArrayEquals(byteArrayOf(0x7f), Rlp.quantity(0x7f))
        assertArrayEquals(byteArrayOf(0x81.toByte(), 0x80.toByte()), Rlp.quantity(0x80))
        assertArrayEquals(byteArrayOf(0x82.toByte(), 0x01, 0x00), Rlp.quantity(256))
        assertArrayEquals(byteArrayOf(0xc0.toByte()), Rlp.list(emptyList()))
        // 56 bytes: the long-form header.
        val long = Rlp.bytes(ByteArray(56) { 1 })
        assertArrayEquals(byteArrayOf(0xb8.toByte(), 56), long.copyOfRange(0, 2))
        assertEquals(58, long.size)
        // A 256-bit quantity with its top bit set isn't given a sign byte.
        assertEquals(33, Rlp.quantity(BigInteger.ONE.shiftLeft(256) - BigInteger.ONE).size)
    }

    @Test
    fun `the tip can't exceed the fee cap`() {
        assertThrows(IllegalArgumentException::class.java) {
            EthTransaction.Fees.Eip1559(maxFeePerGas = BigInteger.ONE, maxPriorityFeePerGas = BigInteger.TWO)
        }
    }
}
