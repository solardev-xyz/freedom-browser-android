package baby.freedom.mobile.browser

import baby.freedom.mobile.chains.BuiltInChains
import baby.freedom.mobile.wallet.ScannedCode
import baby.freedom.mobile.wallet.WalletAccount
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        assertEquals("10 base units", unknownChain[2].value)

        val noAmount = scannedLines(ScannedCode.Payment(mine.address, 1, null, null), chains, listOf(mine))
        assertEquals(ScannedLine("Pay to", mine.address, "This is your Account 1"), noAmount[0])
        assertEquals(ScannedLine("Amount", "Not given"), noAmount[2])
    }

    @Test
    fun `amounts are never rounded`() {
        assertEquals("0.000000000000000001", exactAmount(BigInteger.ONE, 18))
        assertEquals("1,000", exactAmount(BigInteger("1000"), 0))
    }
}
