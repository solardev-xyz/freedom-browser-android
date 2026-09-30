package baby.freedom.mobile.ens

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Keccak-256 at the lengths where its padding changes shape: the empty
 * input, one byte short of the 136-byte rate (the 0x01 and 0x80 pad bits
 * share one byte), exactly one block (a whole extra padding block), one
 * past it, and two blocks. Every transaction hash, signing digest and
 * address goes through here. Vectors from ethers v6 `keccak256` over
 * bytes `i % 251`.
 */
class Keccak256Test {
    private fun input(n: Int) = ByteArray(n) { (it % 251).toByte() }

    @Test
    fun `matches ethers on each side of the rate boundary`() {
        val vectors = mapOf(
            0 to "c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470",
            1 to "bc36789e7a1e281436464229828f817d6612f7b477d66591ff96a9e064bcc98a",
            135 to "cbdfd9dee5faad3818d6b06f95a219fd290b0e1706f6a82e5a595b9ce9faca62",
            136 to "7ce759f1ab7f9ce437719970c26b0a66ff11fe3e38e17df89cf5d29c7d7f807e",
            137 to "ac73d4fae68b8453f764007c1a20ce95994187861f0c3227a3a8e99a73a3b1db",
            271 to "27eceb59ebc3dc8a04a5b135be641591a7278540e4556a2ba9f408194e666ec3",
            272 to "8e2476e65823b24d96ebe239f2c1534cdf763e689e2410c3b1cb0c74e6177bfc",
            1000 to "af692982e84a5a9688359025660a7857cd28ee7c8d867cfa1677baf2e6d1f63b",
        )
        for ((n, hex) in vectors) assertEquals("length $n", hex, Keccak256.digest(input(n)).toHex())
    }

    @Test
    fun `is the legacy Keccak, not NIST SHA3-256`() {
        // SHA3-256("") is a7ffc6f8…; Ethereum's keccak256("") is c5d24601….
        assertEquals("c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470", Keccak256.digest("").toHex())
        // The `transfer(address,uint256)` selector every ERC-20 send carries.
        assertEquals("a9059cbb", Keccak256.digest("transfer(address,uint256)").toHex().take(8))
    }
}
