package baby.freedom.mobile.browser

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The menu popup's placement and the address bar's text fitting (#417). */
class AddressBarPolishTest {

    private val window = IntSize(1080, 2400)
    private val margin = 21 // 8 dp at 2.625x

    private fun place(
        anchor: IntRect,
        popupWidth: Int,
        alignToEnd: Boolean = false,
        direction: LayoutDirection = LayoutDirection.Ltr,
        marginPx: Int = margin,
    ): IntOffset = AnchoredAboveProvider(anchor, gapPx = 32, alignToEnd = alignToEnd, marginPx = marginPx)
        .calculatePosition(IntRect.Zero, window, direction, IntSize(popupWidth, 1000))

    @Test
    fun `the menu hangs off the leading edge of the ≡`() {
        // The ≡ at the capsule's leading end (#415), 200 px in.
        val menu = IntRect(200, 2150, 326, 2276)
        assertEquals(200, place(menu, popupWidth = 700).x)
        // Above the anchor, gap included.
        assertEquals(2150 - 32 - 1000, place(menu, popupWidth = 700).y)
    }

    @Test
    fun `a popup near the left edge keeps the margin instead of touching it`() {
        val nearEdge = IntRect(5, 2150, 131, 2276)
        assertEquals(margin, place(nearEdge, popupWidth = 700).x)
        // What the audit saw (U1): aligned to the end, the popup ran off
        // the left and was clamped to 0 — now held at the margin.
        val menu = IntRect(200, 2150, 326, 2276)
        assertEquals(margin, place(menu, popupWidth = 700, alignToEnd = true).x)
    }

    @Test
    fun `a popup near the right edge keeps the margin too`() {
        val anchor = IntRect(900, 2150, 1026, 2276)
        assertEquals(1080 - 700 - margin, place(anchor, popupWidth = 700).x)
    }

    @Test
    fun `in RTL the leading edge is the right one`() {
        val anchor = IntRect(754, 2150, 880, 2276)
        assertEquals(880 - 500, place(anchor, popupWidth = 500, direction = LayoutDirection.Rtl).x)
        val nearRight = IntRect(950, 2150, 1076, 2276)
        assertEquals(1080 - 500 - margin, place(nearRight, popupWidth = 500, direction = LayoutDirection.Rtl).x)
    }

    @Test
    fun `a popup too wide for both margins is centred, and one wider than the window starts at 0`() {
        val anchor = IntRect(200, 2150, 326, 2276)
        assertEquals((1080 - 1060) / 2, place(anchor, popupWidth = 1060).x)
        assertEquals(0, place(anchor, popupWidth = 1200).x)
    }

    @Test
    fun `no margin is the old clamp to the window`() {
        val nearEdge = IntRect(5, 2150, 131, 2276)
        assertEquals(5, place(nearEdge, popupWidth = 700, marginPx = 0).x)
    }

    @Test
    fun `the label's sizes run from the resting 16 sp down to 13 sp`() {
        assertEquals(AddressLabelRestingFontSize, AddressLabelFitSizes.first())
        assertEquals(13.sp, AddressLabelMinFitFontSize)
        assertEquals(AddressLabelFitSizes.sortedByDescending { it.value }, AddressLabelFitSizes)
    }

    /** A label [chars] long, at roughly 0.55 em a glyph, against [maxDp]. */
    private fun fitsIn(chars: Int, maxDp: Float): (TextUnit) -> Boolean =
        { size -> chars * size.value * 0.55f <= maxDp }

    @Test
    fun `a name that fits keeps the resting size`() {
        assertEquals(16.sp, fitAddressLabelFontSize(fitsIn(chars = 10, maxDp = 121f)))
    }

    @Test
    fun `a name a little too long shrinks rather than eliding`() {
        // "app.swarmit.eth", 15 characters, in the ~121 dp the audit
        // measured on a 411 dp phone (U4): too wide at 16 sp, whole
        // at 14.5 sp.
        val size = fitAddressLabelFontSize(fitsIn(chars = 15, maxDp = 121f))
        assertEquals(14.5f, size.value, 0f)
        assertTrue(15 * size.value * 0.55f <= 121f)
    }

    @Test
    fun `a name that doesn't fit even at 13 sp takes 13 sp and the middle ellipsis`() {
        assertEquals(13.sp, fitAddressLabelFontSize(fitsIn(chars = 40, maxDp = 121f)))
        assertEquals(13.sp, fitAddressLabelFontSize { false })
    }

    @Test
    fun `the compact label is never drawn larger than the resting one`() {
        for (fontScale in listOf(1f, 1.3f, 2f)) {
            val d = Density(2.625f, fontScale)
            with(d) {
                assertTrue(addressLabelCompactScale() < 1f)
                // A name fitted below the compact size keeps its size.
                assertEquals(1f, addressLabelCompactScale(13.sp), 0f)
                assertEquals(1f, addressLabelCompactScale(14.sp), 0.0001f)
            }
        }
    }

    @Test
    fun `placeholder sizes step down by half an sp to the floor`() {
        assertEquals(listOf(16f, 15.5f, 15f, 14.5f, 14f), placeholderFitSizes(16f, 14f))
        val odd = placeholderFitSizes(20.8f, 19.4f)
        assertEquals(4, odd.size)
        assertEquals(19.4f, odd.last(), 0f)
        assertEquals(listOf(12f), placeholderFitSizes(12f, 12f))
        assertEquals(listOf(12f), placeholderFitSizes(12f, 14f))
    }

    private val wordings = listOf("Search or type URL", "Search or URL", "Search")

    /** Roughly 0.55 em a glyph against [maxPx]. */
    private fun placeholderFits(maxPx: Float): (String, Float) -> Boolean =
        { text, size -> text.length * size * 0.55f <= maxPx }

    @Test
    fun `the placeholder keeps its longest wording at full size where it fits`() {
        assertEquals(PlaceholderFit("Search or type URL", 16f), fitAddressPlaceholder(wordings, placeholderFitSizes(16f, 12f), placeholderFits(200f)))
    }

    @Test
    fun `a longer wording a little smaller beats a shorter one at full size`() {
        // 18 chars × 16 × .55 = 158 > 150, but at 15 sp it's 148.5.
        val fit = fitAddressPlaceholder(wordings, placeholderFitSizes(16f, 12f), placeholderFits(150f))
        assertEquals("Search or type URL", fit.text)
        assertEquals(15f, fit.size, 0f)
    }

    @Test
    fun `at 308 dp and font 1_3 beside the Forward pill the placeholder is a whole word, never Sea…`() {
        // About 60 dp of room, a 20.8 sp resting size and a 12 dp floor
        // (≈ 9.2 sp at 1.3): neither longer wording fits at the floor,
        // so "Search", whole, a little smaller than full size.
        val fit = fitAddressPlaceholder(wordings, placeholderFitSizes(20.8f, 9.2f), placeholderFits(60f))
        assertEquals("Search", fit.text)
        assertTrue(fit.size > 17f)
        assertTrue(fit.text.length * fit.size * 0.55f <= 60f)
    }

    @Test
    fun `where nothing fits the shortest wording at the floor is what's drawn`() {
        assertEquals(PlaceholderFit("Search", 12f), fitAddressPlaceholder(wordings, placeholderFitSizes(16f, 12f)) { _, _ -> false })
    }
}
