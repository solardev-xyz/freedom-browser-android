package baby.freedom.mobile.browser

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Safari-style minimised capsule (#40): what the compact state is
 * *shaped* like, and what a tap on it does.
 *
 * Stage 2/3's height model is tested in [CapsuleGeometryTest] and is
 * deliberately untouched here — 44 / 56 / 64 dp, the slot that never
 * moves for a scroll, editing winning over compact. What this pins down
 * is the other two things that now interpolate along the collapse (the
 * capsule's *width* and the label's type size) plus the two-step tap.
 */
class CapsuleCompactStateTest {

    /** A phone-width resting capsule: 411 dp window less 2 × 14 dp margin. */
    private val restingWidth = 383.dp

    // ---- two-step tap ----------------------------------------------

    @Test
    fun `a tap on the compact capsule expands it and nothing more`() {
        assertEquals(
            CapsuleTapAction.Expand,
            capsuleTapAction(collapse = 1f, addressFocused = false),
        )
    }

    @Test
    fun `a tap on the resting capsule edits`() {
        assertEquals(
            CapsuleTapAction.Edit,
            capsuleTapAction(collapse = 0f, addressFocused = false),
        )
    }

    @Test
    fun `the tap follows the capsule the user can see`() {
        // Mid-morph the fraction is on a spring, and the user is aiming
        // at whatever is currently drawn: mostly compact expands, mostly
        // resting edits.
        assertEquals(
            CapsuleTapAction.Expand,
            capsuleTapAction(collapse = 0.9f, addressFocused = false),
        )
        assertEquals(
            CapsuleTapAction.Edit,
            capsuleTapAction(collapse = 0.1f, addressFocused = false),
        )
        // Exactly half-way is still the editor: expanding is the
        // *narrower* claim, so it needs the capsule to be past half.
        assertEquals(
            CapsuleTapAction.Edit,
            capsuleTapAction(
                collapse = CAPSULE_TAP_EXPAND_ABOVE,
                addressFocused = false,
            ),
        )
    }

    @Test
    fun `editing wins over compact for the tap as well as the geometry`() {
        // [BrowserScreen] holds `collapse` at 0 under focus, so this is
        // belt and braces — but a tap inside an open editor must never
        // be read as a request to expand the bar.
        for (collapse in listOf(0f, 0.5f, 1f)) {
            assertEquals(
                "focused capsule at collapse=$collapse",
                CapsuleTapAction.Edit,
                capsuleTapAction(collapse, addressFocused = true),
            )
        }
    }

    @Test
    fun `expanding is a one-tap step, not a shortcut into the editor`() {
        // The whole point of the two steps: the action that fires on a
        // compact bar is not the action that opens the keyboard.
        assertTrue(
            capsuleTapAction(1f, addressFocused = false) !=
                capsuleTapAction(0f, addressFocused = false),
        )
    }

    // ---- width -----------------------------------------------------

    @Test
    fun `the compact capsule wraps its label plus padding`() {
        // A long ENS name: wide enough to clear the floor, narrow enough
        // to stay under the resting width, so the capsule is the label
        // plus its two side paddings (and the rounding slack, which is
        // what keeps a label the capsule was sized for from
        // middle-ellipsising inside it).
        val labelWidth = 142.dp
        val width = compactCapsuleWidth(labelWidth, restingWidth)
        assertTrue(
            "capsule must leave room for the label it was measured from",
            width >= labelWidth + CapsuleCompactSidePadding * 2,
        )
        assertTrue(
            "capsule must not pad beyond the label plus a glyph of slack",
            width <= labelWidth + CapsuleCompactSidePadding * 2 + 12.dp,
        )
    }

    @Test
    fun `a short label still gets a capsule, not a stub`() {
        assertEquals(
            CapsuleCompactMinWidth,
            compactCapsuleWidth(labelWidth = 20.dp, restingWidth = restingWidth),
        )
        assertEquals(120.dp, CapsuleCompactMinWidth)
        // The floor is the *only* thing an empty label can reach for
        // (the home tab never collapses, but the geometry may not depend
        // on that).
        assertEquals(
            CapsuleCompactMinWidth,
            compactCapsuleWidth(labelWidth = 0.dp, restingWidth = restingWidth),
        )
    }

    @Test
    fun `the compact capsule never outgrows the bar it came from`() {
        // Past the resting width the label middle-ellipsises exactly as
        // it does at rest; the capsule does not keep growing.
        assertEquals(
            restingWidth,
            compactCapsuleWidth(labelWidth = 900.dp, restingWidth = restingWidth),
        )
        // …and on a window too narrow for the floor, the ceiling wins:
        // a "compact" bar wider than the resting one is not a collapse.
        assertEquals(90.dp, compactCapsuleWidth(labelWidth = 4.dp, restingWidth = 90.dp))
    }

    @Test
    fun `the width interpolation has the two settled ends`() {
        val compact = compactCapsuleWidth(labelWidth = 142.dp, restingWidth = restingWidth)
        assertEquals(restingWidth, capsuleDrawnWidth(0f, restingWidth, compact))
        assertEquals(compact, capsuleDrawnWidth(1f, restingWidth, compact))
        // Half-way is half-way: the capsule narrows continuously rather
        // than swapping widths at some threshold.
        assertEquals(
            (restingWidth + compact) / 2f,
            capsuleDrawnWidth(0.5f, restingWidth, compact),
        )
    }

    @Test
    fun `the width never leaves its band, whatever the spring does`() {
        val compact = compactCapsuleWidth(labelWidth = 142.dp, restingWidth = restingWidth)
        // Expressive spatial springs overshoot slightly at both ends.
        assertEquals(compact, capsuleDrawnWidth(1.08f, restingWidth, compact))
        assertEquals(restingWidth, capsuleDrawnWidth(-0.08f, restingWidth, compact))
        var previous = restingWidth
        for (step in 0..20) {
            val width = capsuleDrawnWidth(step / 20f, restingWidth, compact)
            assertTrue("width grew at step $step", width <= previous)
            assertTrue("width left the band at step $step", width >= compact)
            previous = width
        }
    }

    @Test
    fun `a compact capsule is always narrower than the slot it sits in`() {
        // The stage 3 rule, applied to the new axis: the capsule shrinks
        // *inside* a slot that still spans the resting width, so nothing
        // outside it moves on a scroll.
        val compact = compactCapsuleWidth(labelWidth = 142.dp, restingWidth = restingWidth)
        for (step in 0..10) {
            assertTrue(capsuleDrawnWidth(step / 10f, restingWidth, compact) <= restingWidth)
        }
    }

    // ---- label type size -------------------------------------------

    @Test
    fun `the label steps down exactly one type size`() {
        assertEquals(AddressLabelRestingFontSize, addressLabelFontSize(0f))
        assertEquals(AddressLabelCompactFontSize, addressLabelFontSize(1f))
        // The resting size is what the label has always rendered at, and
        // the compact one is one step down the M3 scale (bodyMedium →
        // bodySmall). Pinned, because a trust surface may not quietly
        // shrink further than the brief allows.
        assertEquals(14.sp, AddressLabelRestingFontSize)
        assertEquals(12.sp, AddressLabelCompactFontSize)
    }

    @Test
    fun `the label size morphs rather than snaps`() {
        assertEquals(13.sp, addressLabelFontSize(0.5f))
        var previous = addressLabelFontSize(0f)
        for (step in 1..20) {
            val size = addressLabelFontSize(step / 20f)
            assertTrue("label grew at step $step", size.value <= previous.value)
            assertTrue("label jumped at step $step", previous.value - size.value < 0.5f)
            previous = size
        }
        assertEquals(AddressLabelCompactFontSize, previous)
    }

    @Test
    fun `an overshooting spring cannot shrink the label further`() {
        assertEquals(AddressLabelCompactFontSize, addressLabelFontSize(1.08f))
        assertEquals(AddressLabelRestingFontSize, addressLabelFontSize(-0.08f))
    }
}
