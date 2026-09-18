package baby.freedom.mobile.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who gets a downward drag: the pull-to-refresh spinner, or the page
 * (#56).
 *
 * The bug this pins down is freemap.eth — a map that fills the viewport
 * and pans its own tiles. Its document never scrolls, so the old gate
 * ("the WebView is at scrollY 0") was true for every drag the user ever
 * made on it and the spinner took all of them.
 */
class PullToRefreshArmingTest {

    // ---- the arming decision ---------------------------------------

    @Test
    fun `a full-screen map never arms the spinner`() {
        // One viewport tall, permanently at the top: nothing to pull.
        assertFalse(
            pullToRefreshArmed(
                scrollY = 0,
                documentScrollsDown = false,
                rootBlocksVerticalPan = false,
            ),
        )
    }

    @Test
    fun `a long article still pulls to refresh from the top`() {
        assertTrue(
            pullToRefreshArmed(
                scrollY = 0,
                documentScrollsDown = true,
                rootBlocksVerticalPan = false,
            ),
        )
    }

    @Test
    fun `an article scrolled off the top does not`() {
        assertFalse(
            pullToRefreshArmed(
                scrollY = 240,
                documentScrollsDown = true,
                rootBlocksVerticalPan = false,
            ),
        )
    }

    @Test
    fun `an overscrolled WebView counts as the top`() {
        // A WebView bounced past its own top reports a negative offset;
        // that is still the head of the document.
        assertTrue(
            pullToRefreshArmed(
                scrollY = -12,
                documentScrollsDown = true,
                rootBlocksVerticalPan = false,
            ),
        )
    }

    @Test
    fun `a scrollable page that claims vertical drags keeps them`() {
        // The canvas fixture case: tall enough to scroll, but its root
        // says `touch-action: none`, so the page means to handle the
        // gesture itself.
        assertFalse(
            pullToRefreshArmed(
                scrollY = 0,
                documentScrollsDown = true,
                rootBlocksVerticalPan = true,
            ),
        )
    }

    // ---- reading the root's computed styles -------------------------

    @Test
    fun `a default document claims nothing`() {
        assertFalse(rootBlocksVerticalPan("\"auto|auto|auto|auto\""))
    }

    @Test
    fun `touch-action none on the body is a claim`() {
        assertTrue(rootBlocksVerticalPan("\"auto|auto|none|auto\""))
    }

    @Test
    fun `touch-action none on the root element is a claim`() {
        assertTrue(rootBlocksVerticalPan("\"none|auto|auto|auto\""))
    }

    @Test
    fun `overscroll-behavior-y contain or none is a claim`() {
        assertTrue(rootBlocksVerticalPan("\"auto|contain|auto|auto\""))
        assertTrue(rootBlocksVerticalPan("\"auto|auto|auto|none\""))
    }

    @Test
    fun `an unreadable probe result claims nothing`() {
        // No answer at all, an empty answer, a frame that returned
        // `null`, a truncated answer: none of them may take
        // pull-to-refresh away from a page that would otherwise have it.
        assertFalse(rootBlocksVerticalPan(null))
        assertFalse(rootBlocksVerticalPan("null"))
        assertFalse(rootBlocksVerticalPan("\"\""))
        assertFalse(rootBlocksVerticalPan("\"none|auto\""))
        assertFalse(rootBlocksVerticalPan("none|auto|none|auto"))
    }

    // ---- what each `touch-action` value means -----------------------

    @Test
    fun `values that keep vertical panning leave the drag to the browser`() {
        for (value in listOf("auto", "manipulation", "pan-y", "pan-y pinch-zoom", "pan-x pan-y")) {
            assertFalse(value, blocksVerticalPan(value, "auto"))
        }
    }

    @Test
    fun `values that drop vertical panning claim the drag`() {
        for (value in listOf("none", "pan-x", "pan-x pinch-zoom", "pinch-zoom")) {
            assertTrue(value, blocksVerticalPan(value, "auto"))
        }
    }

    @Test
    fun `directional vertical pans still count as vertical`() {
        // `pan-down` is precisely the direction pull-to-refresh starts
        // in, and `pan-up` pages (infinite feeds) are scrollers too.
        assertFalse(blocksVerticalPan("pan-down", "auto"))
        assertFalse(blocksVerticalPan("pan-up", "auto"))
    }

    @Test
    fun `an unreadable element claims nothing`() {
        assertFalse(blocksVerticalPan("", ""))
    }

    @Test
    fun `computed values are read case and whitespace insensitively`() {
        assertTrue(blocksVerticalPan(" NONE ", "auto"))
        assertFalse(blocksVerticalPan(" AUTO ", " AUTO "))
        assertTrue(blocksVerticalPan("auto", " Contain "))
    }
}
