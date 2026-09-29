package baby.freedom.mobile.wallet

import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the wallet makes of a scanned or pasted QR code (#106). */
class ScannedCodeTest {
    // EIP-55's own test vector, and one more address with it.
    private val checksummed = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
    private val other = "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359"
    private val xbzz = "0xdBF3Ea6F5beE45c02255B2c26a16F300502F68da"

    private fun unrecognized(raw: String): String {
        val code = ScannedCode.parse(raw)
        assertTrue("$raw → $code", code is ScannedCode.Unrecognized)
        return (code as ScannedCode.Unrecognized).reason
    }

    @Test
    fun `a bare address comes out checksummed, whatever case it was written in`() {
        assertEquals(ScannedCode.Address(checksummed), ScannedCode.parse(checksummed))
        assertEquals(ScannedCode.Address(checksummed), ScannedCode.parse(checksummed.lowercase()))
        assertEquals(ScannedCode.Address(checksummed), ScannedCode.parse("0x" + checksummed.substring(2).uppercase()))
        assertEquals(ScannedCode.Address(checksummed), ScannedCode.parse("  $checksummed\n"))
    }

    @Test
    fun `a mixed-case address whose checksum doesn't hold is refused, not corrected`() {
        // One letter's case flipped: the kind of change the checksum exists to catch.
        val flipped = checksummed.replace("aAeb", "aaeb")
        assertTrue(unrecognized(flipped).contains("checksum"))
        assertTrue(unrecognized("ethereum:$flipped").contains("checksum"))
        assertTrue(unrecognized("ethereum:$xbzz/transfer?address=$flipped&uint256=1").contains("checksum"))
    }

    @Test
    fun `wrong-length and non-hex addresses aren't addresses`() {
        unrecognized(checksummed.dropLast(1))
        unrecognized(checksummed + "0")
        unrecognized("0x" + "g".repeat(40))
        unrecognized(checksummed.substring(2))
        unrecognized("")
        unrecognized("https://example.com/")
    }

    @Test
    fun `an EIP-681 native payment reads recipient, chain and amount`() {
        assertEquals(
            ScannedCode.Payment(checksummed, chainId = 100, token = null, amount = BigInteger("2014000000000000000")),
            ScannedCode.parse("ethereum:${checksummed.lowercase()}@100?value=2.014e18"),
        )
        assertEquals(
            ScannedCode.Payment(checksummed, chainId = null, token = null, amount = null),
            ScannedCode.parse("ethereum:$checksummed"),
        )
        assertEquals(
            ScannedCode.Payment(checksummed, chainId = 1, token = null, amount = BigInteger.TEN),
            ScannedCode.parse("ETHEREUM:pay-$checksummed@1?value=10&gasLimit=21000"),
        )
    }

    @Test
    fun `an EIP-681 token transfer pays the address parameter, not the contract`() {
        assertEquals(
            ScannedCode.Payment(other, chainId = 100, token = xbzz, amount = BigInteger("15000000000000000")),
            ScannedCode.parse("ethereum:$xbzz@100/transfer?address=$other&uint256=1.5e16"),
        )
        assertEquals(
            ScannedCode.Payment(other, chainId = null, token = xbzz, amount = null),
            ScannedCode.parse("ethereum:$xbzz/transfer?address=${other.lowercase()}"),
        )
        assertTrue(unrecognized("ethereum:$xbzz/transfer?uint256=1").contains("who to pay"))
        assertTrue(unrecognized("ethereum:$xbzz/transfer?address=bob.eth").contains("valid address"))
        assertTrue(unrecognized("ethereum:$xbzz/transfer?address=$other&value=1").contains("own currency"))
        // An explicit zero `value` is what some wallets add: harmless.
        assertEquals(
            ScannedCode.Payment(other, chainId = null, token = xbzz, amount = BigInteger.ONE),
            ScannedCode.parse("ethereum:$xbzz/transfer?address=$other&uint256=1&value=0"),
        )
    }

    @Test
    fun `anything but a payment is refused`() {
        assertTrue(unrecognized("ethereum:$xbzz/approve?address=$other&uint256=1e30").contains("approve"))
        assertTrue(unrecognized("ethereum:vitalik.eth@1?value=1").contains("vitalik.eth"))
        assertTrue(unrecognized("ethereum:hello").contains("no valid address"))
        assertTrue(unrecognized("ethereum:$checksummed@0").contains("chain"))
        assertTrue(unrecognized("ethereum:$checksummed@x1").contains("chain"))
        assertTrue(unrecognized("ethereum:$checksummed@").contains("chain"))
        assertTrue(unrecognized("ethereum:$checksummed@99999999999999999999").contains("chain"))
    }

    @Test
    fun `an amount given twice is ambiguous and refused`() {
        assertTrue(unrecognized("ethereum:$checksummed?value=1&value=1000").contains("twice"))
        assertTrue(
            unrecognized("ethereum:$xbzz/transfer?address=$other&address=$checksummed&uint256=1").contains("twice"),
        )
    }

    @Test
    fun `amounts must be a whole number of base units within a uint256`() {
        assertEquals(BigInteger("2014000000000000000"), ScannedCode.eip681Number("2.014e18"))
        assertEquals(BigInteger("2014000000000000000"), ScannedCode.eip681Number("2.014E+18"))
        assertEquals(BigInteger.ZERO, ScannedCode.eip681Number("0"))
        assertEquals(BigInteger("1500"), ScannedCode.eip681Number("15e2"))
        assertEquals(BigInteger("15"), ScannedCode.eip681Number("1.50e1"))
        assertNull(ScannedCode.eip681Number("1.5")) // half a wei
        assertNull(ScannedCode.eip681Number("1.2345e3"))
        assertNull(ScannedCode.eip681Number("-1"))
        assertNull(ScannedCode.eip681Number("1e-3"))
        assertNull(ScannedCode.eip681Number("0x10"))
        assertNull(ScannedCode.eip681Number(""))
        assertNull(ScannedCode.eip681Number("1e"))
        val max = BigInteger.ONE.shiftLeft(256) - BigInteger.ONE
        assertEquals(max, ScannedCode.eip681Number(max.toString()))
        assertNull(ScannedCode.eip681Number((max + BigInteger.ONE).toString()))
        // Never expands a huge exponent into a huge number before refusing it.
        assertNull(ScannedCode.eip681Number("1e999"))
        assertNull(ScannedCode.eip681Number("1e79"))
        assertTrue(unrecognized("ethereum:$checksummed?value=0.5").contains("amount"))
        assertTrue(unrecognized("ethereum:$xbzz/transfer?address=$other&uint256=abc").contains("amount"))
    }

    @Test
    fun `an OpenLV pairing code is found bare or inside its bridge link`() {
        assertEquals(ScannedCode.Pairing("openlv://abc?k=1"), ScannedCode.parse("openlv://abc?k=1"))
        assertEquals(ScannedCode.Pairing("OPENLV://abc"), ScannedCode.parse(" OPENLV://abc "))
        // The bridge page's fragment is percent-decoded once, and a '+' stays a '+'.
        assertEquals(
            ScannedCode.Pairing("openlv://abc?k=a+b/c"),
            ScannedCode.parse("https://openlv.example/#openlv%3A%2F%2Fabc%3Fk%3Da+b%2Fc"),
        )
        assertEquals(ScannedCode.Pairing("openlv://abc"), ScannedCode.parse("https://openlv.example/#openlv://abc"))
        // A malformed escape is taken as written rather than thrown.
        assertEquals(ScannedCode.Pairing("openlv://a%zz"), ScannedCode.parse("https://x.example/#openlv://a%zz"))
        unrecognized("https://openlv.example/#other")
    }

    @Test
    fun `a pairing code never reaches a log line through toString`() {
        assertEquals("Pairing(…)", ScannedCode.Pairing("openlv://secret").toString())
    }
}
