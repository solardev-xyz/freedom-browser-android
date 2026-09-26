package baby.freedom.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The end-of-document spacer that lets a page's last band scroll out
 * from under the floating bar (#65, interim mitigation).
 */
class PageBottomSpacerTest {

    // ---- height ------------------------------------------------------

    @Test
    fun `height is the resting bar footprint plus the navigation inset`() {
        // 48 dp slot + 10 dp margin + 24 dp gesture-nav inset at 2.625x.
        assertEquals(82, bottomSpacerDp(navInsetPx = 63, density = 2.625f))
        // 3-button navigation: 48 dp inset.
        assertEquals(106, bottomSpacerDp(navInsetPx = 126, density = 2.625f))
    }

    @Test
    fun `no navigation inset leaves just the bar`() {
        assertEquals(58, bottomSpacerDp(navInsetPx = 0, density = 2.625f))
    }

    @Test
    fun `a fractional inset rounds up rather than leaving a sliver`() {
        // 25 px / 2 = 12.5 dp → 58 + 12.5 → 71.
        assertEquals(71, bottomSpacerDp(navInsetPx = 25, density = 2f))
    }

    @Test
    fun `a nonsense density or inset does not throw or go negative`() {
        assertEquals(58, bottomSpacerDp(navInsetPx = 100, density = 0f))
        assertEquals(58, bottomSpacerDp(navInsetPx = -5, density = 2f))
    }

    // ---- gating ------------------------------------------------------

    @Test
    fun `the home sentinel gets no spacer`() {
        assertFalse(bottomSpacerApplies("about:blank"))
        assertFalse(bottomSpacerApplies(null))
        assertFalse(bottomSpacerApplies(""))
    }

    @Test
    fun `real documents do`() {
        assertTrue(bottomSpacerApplies("https://example.com/"))
        assertTrue(bottomSpacerApplies("http://127.0.0.1:1633/bzz/abc/"))
    }

    @Test
    fun `the script carries the height and the marker`() {
        val js = bottomSpacerJs(82)
        assertTrue(js.contains("Math.ceil(82 * k)"))
        assertTrue(js.contains("'$BOTTOM_SPACER_MARK'"))
        assertTrue(js.contains("new CSSStyleSheet()"))
        assertFalse(js.contains("createElement"))
        assertTrue(js.contains("(onBody ? 'body' : 'html') + '::after{"))
    }

    // ---- result parsing ----------------------------------------------

    @Test
    fun `results parse to css px, unknowns to null`() {
        assertEquals(82, parseBottomSpacerResult("82"))
        assertEquals(0, parseBottomSpacerResult("0"))
        assertNull(parseBottomSpacerResult("-1"))
        assertNull(parseBottomSpacerResult("null"))
        assertNull(parseBottomSpacerResult(null))
        assertNull(parseBottomSpacerResult("\"82\""))
    }

    // ---- slot --------------------------------------------------------

    @Test
    fun `a new document starts without a spacer`() {
        val slot = BottomSpacerSlot()
        slot.accept(slot.beginApply(), 82)
        assertEquals(82, slot.spacerCssPx)
        slot.startDocument()
        assertEquals(0, slot.spacerCssPx)
    }

    @Test
    fun `an answer from a replaced document is dropped`() {
        val slot = BottomSpacerSlot()
        val stale = slot.beginApply()
        slot.startDocument()
        slot.accept(stale, 82)
        assertEquals(0, slot.spacerCssPx)
    }

    @Test
    fun `an unknown answer keeps what was known`() {
        val slot = BottomSpacerSlot()
        slot.accept(slot.beginApply(), 82)
        slot.accept(slot.beginApply(), null)
        assertEquals(82, slot.spacerCssPx)
    }

    @Test
    fun `a shell that drops the spacer reads zero`() {
        val slot = BottomSpacerSlot()
        slot.accept(slot.beginApply(), 82)
        slot.accept(slot.beginApply(), 0)
        assertEquals(0, slot.spacerCssPx)
    }

    // ---- pull-to-refresh discount ------------------------------------

    // A 2.625x device at default zoom: the WebView is 2000 px tall, i.e.
    // ~762 CSS px.
    private val scale = 2.625f
    private val viewPx = 2000
    private val viewportCss = 762

    @Test
    fun `a short page scrolled only by the spacer does not count as scrolling`() {
        // Exactly one viewport of content + 82 px of spacer: before the
        // spacer it did not scroll, so pull-to-refresh must stay unarmed.
        assertFalse(
            documentScrollsPastSpacer(
                canScrollDown = true,
                contentHeightCss = viewportCss + 82, // ceil(2000 / 2.625) + spacer
                spacerCss = 82,
                viewHeightPx = viewPx,
                scale = scale,
            ),
        )
        assertFalse(
            pullToRefreshArmed(
                scrollY = 0,
                documentScrollsDown = documentScrollsPastSpacer(
                    true, viewportCss + 82, 82, viewPx, scale,
                ),
                rootBlocksVerticalPan = false,
            ),
        )
    }

    @Test
    fun `a 100vh flex page reads as not scrolling despite getContentHeight rounding`() {
        // Measured on the freedom AVD: WebView 2264 px at 2.625x, a
        // `min-height: 100vh` flex body (862.5 CSS px) plus the 82 px
        // spacer reported contentHeight = 946.
        assertFalse(documentScrollsPastSpacer(true, 946, 82, 2264, 2.625f))
    }

    @Test
    fun `a long article still scrolls`() {
        assertTrue(documentScrollsPastSpacer(true, 5000, 82, viewPx, scale))
    }

    @Test
    fun `without a spacer the WebView's own answer stands`() {
        assertTrue(documentScrollsPastSpacer(true, 0, 0, viewPx, scale))
        assertFalse(documentScrollsPastSpacer(false, 5000, 0, viewPx, scale))
    }

    @Test
    fun `a page that cannot scroll never does`() {
        assertFalse(documentScrollsPastSpacer(false, 5000, 82, viewPx, scale))
    }

    @Test
    fun `a page a little taller than the viewport keeps scrolling`() {
        // 40 CSS px of real overflow beyond the viewport, plus the spacer.
        assertTrue(documentScrollsPastSpacer(true, viewportCss + 40 + 82, 82, viewPx, scale))
    }
}
