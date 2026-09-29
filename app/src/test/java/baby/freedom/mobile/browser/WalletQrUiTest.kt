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
    fun `the code shown doesn't read again while it stays in view, and does once it has left`() {
        var now = 0L
        val dedup = ScanDedup(clock = { now }, goneAfterMs = 2_000)
        assertTrue(dedup.isNew("A"))
        // Decoded frame after frame: the window keeps renewing.
        repeat(10) { now += 500; assertFalse(dedup.isNew("A")) }
        // Out of view for longer than the window: pointing at it again reads it.
        now += 2_000
        assertTrue(dedup.isNew("A"))
        // A different code reads at once, and then A again once A itself has
        // been out of view for the window (scan A, scan B, back to A).
        now += 100
        assertTrue(dedup.isNew("B"))
        now += 2_000
        assertTrue(dedup.isNew("A"))
        // A clock that went backwards doesn't hold a code back forever.
        now -= 10_000
        assertTrue(dedup.isNew("A"))
    }

    @Test
    fun `a paste is not replaced by the code the camera last read, however long it was gone`() {
        var now = 1_000L
        val dedup = ScanDedup(clock = { now }, goneAfterMs = 2_000)
        assertTrue(dedup.isNew("A"))
        dedup.holdRecent() // the user pastes something else
        // Camera stopped (Home, scrolled away) or blurred for far longer than the window.
        now += 60_000
        assertFalse(dedup.isNew("A"))
        now += 5_000
        assertFalse(dedup.isNew("A"))
        // A different code does replace the paste, and afterwards A reads again
        // as usual, once it has been out of view for the window.
        now += 100
        assertTrue(dedup.isNew("B"))
        now += 100
        assertFalse(dedup.isNew("A"))
        now += 2_000
        assertTrue(dedup.isNew("A"))
    }

    @Test
    fun `clearing the paste lets the held code read again`() {
        var now = 1_000L
        val dedup = ScanDedup(clock = { now }, goneAfterMs = 2_000)
        assertTrue(dedup.isNew("A"))
        dedup.holdRecent()
        now += 10_000
        assertFalse(dedup.isNew("A"))
        dedup.release()
        // A still in front of the camera: its very next frame reads it again,
        // without first having to leave view for the window.
        now += 100
        assertTrue(dedup.isNew("A"))
        // ...and then reads once while it stays in view, as usual.
        now += 100
        assertFalse(dedup.isNew("A"))
        dedup.holdRecent()
        dedup.release()
        now += 10_000
        assertTrue(dedup.isNew("A"))
        // A paste before the camera read anything holds nothing back.
        val fresh = ScanDedup(clock = { now }, goneAfterMs = 2_000)
        fresh.holdRecent()
        assertTrue(fresh.isNew("A"))
    }

    @Test
    fun `pasting again after clearing a paste still holds back the camera's codes`() {
        var now = 1_000L
        val dedup = ScanDedup(clock = { now }, goneAfterMs = 2_000)
        assertTrue(dedup.isNew("A"))
        now += 200
        assertTrue(dedup.isNew("B"))
        dedup.holdRecent() // paste P
        now += 5_000
        dedup.release() // clear it
        now += 5_000
        dedup.holdRecent() // paste Q, with no camera frame in between
        // Back in front of the camera much later: neither replaces Q.
        now += 5_000
        assertFalse(dedup.isNew("A"))
        now += 100
        assertFalse(dedup.isNew("B"))
        // Clearing Q lets both read again on their next frame, as before,
        // and then no more while the decoder alternates between them.
        dedup.release()
        now += 100
        assertTrue(dedup.isNew("A"))
        now += 100
        assertTrue(dedup.isNew("B"))
        repeat(5) {
            now += 100
            assertFalse(dedup.isNew("A"))
            now += 100
            assertFalse(dedup.isNew("B"))
        }
        // A released code is let go once only: a code outside the released
        // set ends it, and the rest go back to the normal rules.
        val one = ScanDedup(clock = { now }, goneAfterMs = 2_000)
        assertTrue(one.isNew("A"))
        now += 100
        assertTrue(one.isNew("B"))
        one.holdRecent()
        one.release()
        now += 100
        assertTrue(one.isNew("C"))
        now += 100
        assertFalse(one.isNew("A")) // seen moments ago, not shown, one frame
    }

    @Test
    fun `a paste holds back every code the camera was reading, not just the last one`() {
        var now = 1_000L
        val dedup = ScanDedup(clock = { now }, goneAfterMs = 2_000)
        // Two codes in frame: the decoder reads one, then the other.
        assertTrue(dedup.isNew("A"))
        now += 200
        assertTrue(dedup.isNew("B"))
        dedup.holdRecent()
        // Neither replaces the paste, in any order, however long the camera was away.
        now += 200
        assertFalse(dedup.isNew("A"))
        now += 200
        assertFalse(dedup.isNew("B"))
        now += 60_000
        assertFalse(dedup.isNew("A"))
        // A code that wasn't in view does replace it, and ends the hold.
        now += 100
        assertTrue(dedup.isNew("C"))
        now += 2_100
        assertTrue(dedup.isNew("A"))

        // A code last read well before the camera's latest one isn't held.
        val old = ScanDedup(clock = { now }, goneAfterMs = 2_000)
        assertTrue(old.isNew("X"))
        now += 5_000
        assertTrue(old.isNew("Y"))
        old.holdRecent()
        now += 100
        assertFalse(old.isNew("Y"))
        now += 100
        assertTrue(old.isNew("X"))

        // Clearing the paste lets both held codes read again at once.
        val both = ScanDedup(clock = { now }, goneAfterMs = 2_000)
        assertTrue(both.isNew("A"))
        now += 100
        assertTrue(both.isNew("B"))
        both.holdRecent()
        both.release()
        now += 100
        assertTrue(both.isNew("B"))
        now += 100
        assertTrue(both.isNew("A"))
    }

    @Test
    fun `two codes decoded alternately don't flip the result every frame`() {
        var now = 1_000L
        val dedup = ScanDedup(clock = { now }, goneAfterMs = 2_000)
        // Two codes in frame, the decoder alternating between them: each
        // reads once, then neither reads again while both stay in view.
        assertTrue(dedup.isNew("A"))
        now += 150
        assertTrue(dedup.isNew("B"))
        repeat(20) {
            now += 150
            assertFalse(dedup.isNew("A"))
            now += 150
            assertFalse(dedup.isNew("B"))
        }
        // The same right after a paste is cleared: each held code reads once
        // on its next frame, and then no more while the decoder alternates.
        dedup.holdRecent()
        dedup.release()
        now += 150
        assertTrue(dedup.isNew("A"))
        now += 150
        assertTrue(dedup.isNew("B"))
        repeat(20) {
            now += 150
            assertFalse(dedup.isNew("A"))
            now += 150
            assertFalse(dedup.isNew("B"))
        }
        // B leaves view and the camera settles on A alone: A reads once its
        // frames run unbroken, and then not again while it stays in view.
        repeat(2) { now += 150; assertFalse(dedup.isNew("A")) }
        now += 150
        assertTrue(dedup.isNew("A"))
        repeat(20) { now += 150; assertFalse(dedup.isNew("A")) }
    }

    @Test
    fun `a code the camera returns to within the window reads once it holds steady`() {
        var now = 1_000L
        val dedup = ScanDedup(clock = { now }, goneAfterMs = 2_000, steadyReads = 3)
        assertTrue(dedup.isNew("A"))
        // Pan to the neighbouring B: it reads at once.
        now += 300
        assertTrue(dedup.isNew("B"))
        repeat(3) { now += 150; assertFalse(dedup.isNew("B")) }
        // Back to A well within A's window, and held there: the result
        // moves to A after a few unbroken frames, not never.
        now += 150
        assertFalse(dedup.isNew("A"))
        now += 150
        assertFalse(dedup.isNew("A"))
        now += 150
        assertTrue(dedup.isNew("A"))
        // A stray frame of B, still within B's window, doesn't flip it back...
        now += 150
        assertFalse(dedup.isNew("B"))
        // ...and A, now shown, doesn't read again while it stays.
        repeat(20) { now += 150; assertFalse(dedup.isNew("A")) }

        // While a paste holds A back, even a steady A doesn't replace it.
        dedup.holdRecent()
        repeat(20) { now += 150; assertFalse(dedup.isNew("A")) }
    }
}
