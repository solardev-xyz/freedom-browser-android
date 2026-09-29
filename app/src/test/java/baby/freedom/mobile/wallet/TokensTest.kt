package baby.freedom.mobile.wallet

import baby.freedom.mobile.chains.BuiltInChains
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The token list, the ERC-20 balance call, and how amounts read (#104). */
class TokensTest {
    @Test
    fun `each wallet chain lists its native currency first, then the iOS registry's tokens`() {
        val eth = TokenRegistry.tokens(BuiltInChains.ETHEREUM)
        assertEquals(listOf("ETH", "USDC", "USDT", "DAI", "EURC", "BZZ"), eth.map { it.symbol })
        assertEquals("1:native", eth[0].key)
        assertEquals("1:0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48", eth[1].key)
        val gnosis = TokenRegistry.tokens(BuiltInChains.GNOSIS)
        assertEquals(listOf("xDAI", "xBZZ", "EURe"), gnosis.map { it.symbol })
        assertEquals(listOf(18, 16, 18), gnosis.map { it.decimals })
        assertEquals(listOf(1L, 100L), TokenRegistry.WALLET_CHAIN_IDS)
    }

    @Test
    fun `balanceOf call data is the selector and the padded holder`() {
        assertEquals(
            "0x70a08231000000000000000000000000" + "9858effd232b4033e47d90003d41ec34ecaeda94",
            Erc20.balanceOfData("0x9858EfFD232B4033E47d90003D41EC34EcaEda94"),
        )
    }

    @Test
    fun `only a single 32-byte word decodes as a balance`() {
        assertEquals(BigInteger.valueOf(255), Erc20.decodeUint256("0x" + "0".repeat(62) + "ff"))
        assertEquals(BigInteger.ZERO, Erc20.decodeUint256("0x" + "0".repeat(64)))
        assertNull(Erc20.decodeUint256("0x")) // no contract: not a zero
        assertNull(Erc20.decodeUint256("0x" + "0".repeat(128)))
        assertNull(Erc20.decodeUint256("0x" + "g".repeat(64)))
    }

    @Test
    fun `amounts are cut, never rounded up, and a dust balance never reads zero`() {
        val e18 = BigInteger.TEN.pow(18)
        assertEquals("0", TokenAmounts.format(BigInteger.ZERO, 18))
        assertEquals("1", TokenAmounts.format(e18, 18))
        assertEquals("1,234.5", TokenAmounts.format(BigInteger.valueOf(12345).multiply(e18).divide(BigInteger.TEN), 18))
        assertEquals("0.999999", TokenAmounts.format(e18 - BigInteger.ONE, 18))
        assertEquals("<0.000001", TokenAmounts.format(BigInteger.ONE, 18))
        assertEquals("2", TokenAmounts.format(e18 * BigInteger.TWO + BigInteger.ONE, 18))
        assertEquals("12.345678", TokenAmounts.format(BigInteger.valueOf(12_345_678), 6))
        assertEquals("0.25", TokenAmounts.format(BigInteger.valueOf(25).multiply(BigInteger.TEN.pow(14)), 16))
        assertEquals("7", TokenAmounts.format(BigInteger.valueOf(7), 0))
    }
}
