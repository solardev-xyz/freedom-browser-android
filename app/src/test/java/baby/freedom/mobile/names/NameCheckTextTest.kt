package baby.freedom.mobile.names

import baby.freedom.mobile.browser.fiatNative
import baby.freedom.mobile.chains.BuiltInChains
import baby.freedom.mobile.wallet.FiatCurrency
import baby.freedom.mobile.wallet.FiatQuotes
import java.math.BigDecimal
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The check card's price and the name it prices for what was typed (#617). */
class NameCheckTextTest {
    // The price the v0.6.32 smoke test showed as `0.00291213255683822 ETH`.
    private val smokePrice = BigInteger("2912132556838220")

    @Test
    fun `the price is rounded up to six decimals, never down`() {
        assertEquals("0.002913 ETH", ethPriceText(smokePrice))
        assertEquals("0.003125 ETH", ethPriceText(BigInteger("3125000000000000")))
        assertEquals("1 ETH", ethPriceText(BigInteger.TEN.pow(18)))
        assertEquals("0.000001 ETH", ethPriceText(BigInteger.ONE))
        assertEquals("0 ETH", ethPriceText(BigInteger.ZERO))
        // The review still keeps every digit.
        assertEquals("0.00291213255683822 ETH", ethText(smokePrice))
    }

    @Test
    fun `with Show prices on, the price has its value in euros`() {
        val quotes = FiatQuotes(FiatCurrency.EUR, mapOf("1:native" to BigDecimal("2000")))
        assertEquals("≈ €5.82", fiatNative(quotes, BuiltInChains.ETHEREUM, smokePrice))
        assertEquals(null, fiatNative(null, BuiltInChains.ETHEREUM, smokePrice))
    }

    @Test
    fun `a bare label is checked as its eth name`() {
        assertEquals("alice.eth", completedName("alice"))
        assertEquals("Alice.eth", completedName(" Alice "))
        val parsed = NameContracts.parse(completedName("alice"))
        assertEquals(NameSpace.ETH, parsed.space)
        assertEquals("alice.eth", parsed.name)
    }

    @Test
    fun `a name with a dot, or nothing, is checked as typed`() {
        assertEquals("", completedName(""))
        assertEquals("", completedName("   "))
        assertEquals("alice.eth", completedName("alice.eth"))
        assertEquals("alice.wei", completedName("alice.wei"))
        assertEquals("alice.", completedName("alice."))
        assertEquals("a.b", completedName("a.b"))
    }

    @Test
    fun `a bare label is offered eth first, then wei and gwei`() {
        assertEquals(listOf(NameSpace.ETH, NameSpace.WEI, NameSpace.GWEI), endingChoices("alice"))
        assertEquals(listOf(NameSpace.ETH, NameSpace.WEI, NameSpace.GWEI), endingChoices(" alice "))
        assertEquals(emptyList<NameSpace>(), endingChoices(""))
        assertEquals(emptyList<NameSpace>(), endingChoices("alice.eth"))
        assertEquals(emptyList<NameSpace>(), endingChoices("alice."))
    }

    @Test
    fun `the price rounds the way the wallet's fees do`() {
        for (wei in listOf(smokePrice, BigInteger.ONE, BigInteger.ZERO, BigInteger.TEN.pow(18), BigInteger("123456789012345678901"))) {
            assertEquals(baby.freedom.mobile.browser.feeText(wei, BuiltInChains.ETHEREUM, 6), ethPriceText(wei))
        }
    }

    @Test
    fun `no ending is offered past the name length limit`() {
        val max = NameContracts.MAX_NAME_LENGTH
        // 251 + ".eth"/".wei" is 255; ".gwei" would make 256.
        assertEquals(listOf(NameSpace.ETH, NameSpace.WEI), endingChoices("a".repeat(max - 4)))
        assertEquals(emptyList<NameSpace>(), endingChoices("a".repeat(max - 3)))
        assertEquals(emptyList<NameSpace>(), endingChoices("a".repeat(max)))
        assertEquals(NameContracts.MAX_NAME_LENGTH, completedName("a".repeat(max - 4)).length)
    }

    @Test
    fun `a bare label too long for its eth name is refused as too long`() {
        val label = "a".repeat(NameContracts.MAX_NAME_LENGTH - 2)
        try {
            NameContracts.parse(completedName(label))
            fail("accepted a ${label.length + 4}-character name")
        } catch (e: NameException) {
            assertEquals(NameException.Code.INVALID, e.code)
            assertTrue(e.message!!, e.message!!.contains("too long"))
            assertTrue(e.message!!, e.message!!.contains("255"))
        }
        // Exactly at the limit still parses.
        assertEquals(NameContracts.MAX_NAME_LENGTH, NameContracts.parse(completedName("a".repeat(NameContracts.MAX_NAME_LENGTH - 4))).name.length)
    }
}
