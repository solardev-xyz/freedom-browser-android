package baby.freedom.mobile.browser

import baby.freedom.mobile.chains.BuiltInChains
import baby.freedom.mobile.wallet.ScannedCode
import baby.freedom.mobile.wallet.WalletAccount
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The receive QR and what the scan page shows for a code (#106). */
class WalletQrUiTest {
    private val chains = listOf(BuiltInChains.ETHEREUM, BuiltInChains.GNOSIS)
    private val mine = WalletAccount(0, "Account 1", "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed")
    private val other = "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359"
    private val xbzz = "0xdBF3Ea6F5beE45c02255B2c26a16F300502F68da"

    /** [text] as a QR code, rendered to a grey frame the way a camera's Y plane holds it. */
    private fun frame(text: String, scale: Int = 6, stride: Int? = null, inverted: Boolean = false): Triple<ByteArray, Int, Int> {
        val matrix = qrMatrix(text)
        val side = (matrix.width + 8) * scale
        val rowStride = stride ?: side
        val light = if (inverted) 20 else 235
        val dark = if (inverted) 235 else 20
        val bytes = ByteArray(rowStride * side) { light.toByte() }
        for (y in 0 until side) for (x in 0 until side) {
            val mx = x / scale - 4
            val my = y / scale - 4
            if (mx in 0 until matrix.width && my in 0 until matrix.height && matrix[mx, my]) {
                bytes[y * rowStride + x] = dark.toByte()
            }
        }
        return Triple(bytes, rowStride, side)
    }

    @Test
    fun `the receive code decodes back to exactly the address`() {
        val (bytes, stride, side) = frame(mine.address)
        assertEquals(mine.address, QrFrameDecoder().decode(bytes, stride, side, side))
    }

    @Test
    fun `the frame decoder reads padded rows and light-on-dark codes, and nothing from a blank frame`() {
        val uri = "ethereum:$xbzz@100/transfer?address=$other&uint256=1.5e16"
        val side0 = (qrMatrix(uri).width + 8) * 6
        val (padded, stride, side) = frame(uri, stride = side0 + 64)
        assertEquals(uri, QrFrameDecoder().decode(padded, stride, side, side))
        val (inverted, istride, iside) = frame(uri, inverted = true)
        assertEquals(uri, QrFrameDecoder().decode(inverted, istride, iside, iside))
        assertNull(QrFrameDecoder().decode(ByteArray(200 * 200) { 128.toByte() }, 200, 200, 200))
    }

    @Test
    fun `an address says when it's one of the user's own accounts`() {
        assertEquals(
            listOf(ScannedLine("Address", mine.address, "This is your Account 1")),
            scannedLines(ScannedCode.Address(mine.address), chains, listOf(mine)),
        )
        assertEquals(listOf(ScannedLine("Address", other)), scannedLines(ScannedCode.Address(other), chains, listOf(mine)))
    }

    @Test
    fun `a native payment shows the exact amount in the chain's currency`() {
        val code = ScannedCode.Payment(other, 100, null, BigInteger("1234567890123456789"))
        assertEquals(
            listOf(
                ScannedLine("Pay to", other),
                ScannedLine("Network", "Gnosis Chain"),
                ScannedLine("Amount", "1.234567890123456789 xDAI"),
            ),
            scannedLines(code, chains, emptyList()),
        )
    }

    @Test
    fun `a known token payment names the token and its amount`() {
        val code = ScannedCode.Payment(other, 100, xbzz, BigInteger("15000000000000000"))
        assertEquals(
            listOf(
                ScannedLine("Pay to", other),
                ScannedLine("Network", "Gnosis Chain"),
                ScannedLine("Token", "xBZZ (Swarm Token)", xbzz),
                ScannedLine("Amount", "1.5 xBZZ"),
            ),
            scannedLines(code, chains, emptyList()),
        )
    }

    @Test
    fun `without a known chain or token, nothing is guessed`() {
        // xBZZ's address, but no chain: it can't be told to be xBZZ.
        val noChain = scannedLines(ScannedCode.Payment(other, null, xbzz, BigInteger.TEN), chains, emptyList())
        assertEquals(ScannedLine("Network", "Not given", "Ask the sender which network the payment is for."), noChain[1])
        assertEquals("A token Freedom doesn’t know. Check it with the sender.", noChain[2].note)
        assertEquals(ScannedLine("Amount", "10 base units", "The token’s decimals aren’t known, so this is its smallest unit."), noChain[3])

        val unknownChain = scannedLines(ScannedCode.Payment(other, 137, null, BigInteger.TEN), chains, emptyList())
        assertEquals(ScannedLine("Network", "Chain ID 137", "Not one of your networks."), unknownChain[1])
        assertEquals(
            ScannedLine(
                "Amount",
                "10 base units",
                "The network isn’t known, so this is in its currency’s smallest unit (like wei for ETH).",
            ),
            unknownChain[2],
        )
        // Native currency with no chain: native wording, and one unit is singular.
        val oneNoChain = scannedLines(ScannedCode.Payment(other, null, null, BigInteger.ONE), chains, emptyList())
        assertEquals(
            ScannedLine(
                "Amount",
                "1 base unit",
                "The network isn’t known, so this is in its currency’s smallest unit (like wei for ETH).",
            ),
            oneNoChain[2],
        )
        val oneToken = scannedLines(ScannedCode.Payment(other, null, xbzz, BigInteger.ONE), chains, emptyList())
        assertEquals("1 base unit", oneToken[3].value)

        val noAmount = scannedLines(ScannedCode.Payment(mine.address, 1, null, null), chains, listOf(mine))
        assertEquals(ScannedLine("Pay to", mine.address, "This is your Account 1"), noAmount[0])
        assertEquals(ScannedLine("Amount", "Not given"), noAmount[2])
    }

    @Test
    fun `amounts are never rounded`() {
        assertEquals("0.000000000000000001", exactAmount(BigInteger.ONE, 18))
        assertEquals("1,000", exactAmount(BigInteger("1000"), 0))
    }

    @Test
    fun `a code reads once while it stays in view, and again once it has left`() {
        var now = 0L
        val dedup = ScanDedup(clock = { now }, goneAfterMs = 2_000)
        assertTrue(dedup.isNew("A"))
        // Decoded frame after frame: the window keeps renewing.
        repeat(10) { now += 500; assertFalse(dedup.isNew("A")) }
        // Out of view for longer than the window: pointing at it again reads it.
        now += 2_000
        assertTrue(dedup.isNew("A"))
        // A different code reads at once, and then A again (scan A, scan B, back to A).
        now += 100
        assertTrue(dedup.isNew("B"))
        now += 100
        assertTrue(dedup.isNew("A"))
        // A clock that went backwards doesn't hold a code back forever.
        now -= 10_000
        assertTrue(dedup.isNew("A"))
    }
}
