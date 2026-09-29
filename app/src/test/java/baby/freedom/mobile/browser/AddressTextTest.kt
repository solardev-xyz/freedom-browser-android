package baby.freedom.mobile.browser

import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** An address fits on one line (shrunk a little at most) or splits in even halves (#104, #208 R1-M1). */
class AddressTextTest {
    // A monospace line: 42 characters, each 0.6 em wide, at 1 px per sp.
    private fun width(size: TextUnit) = (42 * 0.6f * size.value).toInt()

    @Test
    fun `an address that fits keeps its size`() {
        assertEquals(14.sp, fittedAddressSize(14.sp, 400, ::width))
        assertEquals(14.sp, fittedAddressSize(14.sp, Constraints.Infinity, ::width))
    }

    @Test
    fun `a little too wide shrinks just enough to fit on one line`() {
        val size = fittedAddressSize(14.sp, 340, ::width)!! // 14 sp needs 352 px
        assertTrue(width(size) <= 340)
        assertTrue(size.value >= 14f * MIN_ADDRESS_SCALE)
        assertTrue(size.value > 13f)
    }

    @Test
    fun `far too wide isn't shrunk past the floor`() {
        assertNull(fittedAddressSize(14.sp, 250, ::width))
    }

    @Test
    fun `a too-long amount may break only after a separator, never between digits`() {
        val z = "\u200B"
        assertEquals("1,${z}234,${z}567.${z}891", amountBreaks("1,234,567.891"))
        assertEquals("42", amountBreaks("42"))
        assertEquals("<0.${z}000001", amountBreaks("<0.000001"))
    }
}
