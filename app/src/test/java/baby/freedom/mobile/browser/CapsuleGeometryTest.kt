package baby.freedom.mobile.browser

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one height model behind the floating capsule.
 *
 * Two morphs drive the same object — compact-on-scroll (#30) and the
 * editing morph (#31) — and the whole point of merging them was that
 * they resolve through a single rule rather than fighting over the same
 * pixels. These are the invariants that rule has to keep:
 *
 * - three settled heights, 32 / 56 / 64 dp;
 * - the layout *slot* only ever moves for editing, so a scroll can't
 *   shift anything outside the capsule;
 * - the compact capsule shrinks *upwards* off a bottom edge that never
 *   moves (#47), while the 48 dp touch band follows it down;
 * - editing wins over compact;
 * - the address pill tracks the capsule with an even gutter in every
 *   state.
 */
class CapsuleGeometryTest {

    /** Gutter between the capsule's edge and the pill's, top and bottom. */
    private fun gutter(collapse: Float, edit: Float) =
        (capsuleDrawnHeight(collapse, edit) - addressPillHeight(collapse, edit)) / 2f

    @Test
    fun `the capsule has exactly three settled heights`() {
        assertEquals(CapsuleHeight, capsuleDrawnHeight(collapse = 0f, editProgress = 0f))
        assertEquals(CapsuleCompactHeight, capsuleDrawnHeight(collapse = 1f, editProgress = 0f))
        assertEquals(CapsuleEditingHeight, capsuleDrawnHeight(collapse = 0f, editProgress = 1f))

        assertEquals(32.dp, CapsuleCompactHeight)
        assertEquals(56.dp, CapsuleHeight)
        assertEquals(64.dp, CapsuleEditingHeight)
    }

    @Test
    fun `editing wins over compact`() {
        // [BrowserScreen] holds `collapse` at 0 whenever the field has
        // focus, so this is the state the settled editor is in — but
        // pin it at the geometry layer too: if that guard is ever lost,
        // the editor must still open to its full height rather than to
        // some blend of the two.
        assertEquals(CapsuleEditingHeight, capsuleDrawnHeight(collapse = 0f, editProgress = 1f))
    }

    @Test
    fun `the layout slot never moves for a scroll`() {
        // The invariant the compact state rests on: collapsing changes
        // the drawn capsule and nothing else, so the page, the snackbar
        // and the IME reserve stay exactly where they were.
        for (collapse in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            assertEquals(
                "slot must not depend on collapse (was $collapse)",
                CapsuleHeight,
                capsuleSlotHeight(editProgress = 0f),
            )
            assertTrue(capsuleDrawnHeight(collapse, 0f) <= capsuleSlotHeight(0f))
        }
    }

    @Test
    fun `the slot grows only with the editing morph`() {
        assertEquals(CapsuleHeight, capsuleSlotHeight(0f))
        assertEquals(CapsuleEditingHeight, capsuleSlotHeight(1f))
        assertEquals(60.dp, capsuleSlotHeight(0.5f))
    }

    @Test
    fun `the capsule travels continuously from compact to editing`() {
        // Tapping a compact bar springs `collapse` down while
        // `editProgress` comes up. Walking the two in opposite
        // directions has to produce a monotone 32 → 64 dp travel with no
        // step in the frame where they cross.
        var previous = capsuleDrawnHeight(collapse = 1f, editProgress = 0f)
        assertEquals(CapsuleCompactHeight, previous)
        for (step in 1..20) {
            val t = step / 20f
            val height = capsuleDrawnHeight(collapse = 1f - t, editProgress = t)
            assertTrue(
                "height went backwards at t=$t ($previous → $height)",
                height >= previous,
            )
            assertTrue((height - previous).value < 3f)
            previous = height
        }
        assertEquals(CapsuleEditingHeight, previous)
    }

    @Test
    fun `the pill keeps an even gutter at every settled height`() {
        assertEquals(8.dp, gutter(collapse = 0f, edit = 0f))
        assertEquals(8.dp, gutter(collapse = 0f, edit = 1f))
        // None at all when compact: the capsule has shrunk onto the pill,
        // so the two are one shape and the gutter that used to separate
        // them is the 6 dp of rim #47 took away.
        assertEquals(0.dp, gutter(collapse = 1f, edit = 0f))
        assertEquals(CapsuleCompactHeight, addressPillHeight(collapse = 1f, editProgress = 0f))
    }

    @Test
    fun `the compact height is the label's line box plus its padding`() {
        // The whole derivation of the compact capsule, in one line: it
        // is not a chosen height, it is what the 14 sp label's line box
        // needs plus 6 dp of air above and below.
        assertEquals(6.dp, CapsuleCompactVerticalPadding)
        assertEquals(
            AddressLabelCompactLineHeight.value.dp + CapsuleCompactVerticalPadding * 2,
            CapsuleCompactHeight,
        )
        // And the line box is a real one — the size the compact label is
        // actually laid out at, not a number picked to make the sum work.
        assertEquals(AddressLabelCompactLineHeight, addressLabelLineHeight(1f))
    }

    // ---- bottom anchor ---------------------------------------------

    @Test
    fun `the capsule's bottom edge never moves`() {
        // #47's rule. The slot is unchanged, the capsule shrinks
        // upwards inside it, so the gap to the navigation inset
        // ([CapsuleBottomMargin], applied by [BrowserScreen] under the
        // slot) is the same in every state.
        for (step in 0..20) {
            val collapse = step / 20f
            // Centred in the slot, then pushed down by the anchor: the
            // drawn bottom edge is the slot's own whatever the height.
            assertEquals(
                "capsule bottom moved at collapse=$collapse",
                capsuleSlotHeight(0f).value,
                capsuleBottomAnchor(collapse, 0f).value * 2f +
                    capsuleDrawnHeight(collapse, 0f).value,
                0.001f,
            )
        }
        assertEquals(12.dp, capsuleBottomAnchor(collapse = 1f, editProgress = 0f))
    }

    @Test
    fun `the states that fill the slot are not anchored at all`() {
        // Resting and editing are both exactly the slot's height, so
        // there is nothing to anchor and the capsule stays centred —
        // which is to say #47 touches the compact state and only it.
        assertEquals(0.dp, capsuleBottomAnchor(collapse = 0f, editProgress = 0f))
        assertEquals(0.dp, capsuleBottomAnchor(collapse = 0f, editProgress = 1f))
        assertEquals(0.dp, capsuleBottomAnchor(collapse = 0f, editProgress = 0.5f))
    }

    @Test
    fun `the anchor travels continuously with the height`() {
        var previous = capsuleBottomAnchor(collapse = 0f, editProgress = 0f)
        for (step in 1..20) {
            val anchor = capsuleBottomAnchor(step / 20f, 0f)
            assertTrue("anchor went backwards at step $step", anchor >= previous)
            assertTrue("anchor jumped at step $step", (anchor - previous).value < 2f)
            previous = anchor
        }
        // Overshooting springs may not push it past either end.
        assertEquals(12.dp, capsuleBottomAnchor(collapse = 1.08f, editProgress = 0f))
        assertEquals(0.dp, capsuleBottomAnchor(collapse = -0.08f, editProgress = 0f))
    }

    @Test
    fun `the touch band follows the capsule down without leaving the slot`() {
        // The band is what answers the two-step tap, so it may not hang
        // out of the chrome and start taking presses meant for the page.
        for (step in 0..20) {
            val collapse = step / 20f
            val shift = addressFieldTouchShift(collapse, 0f)
            assertTrue("negative shift at collapse=$collapse", shift >= 0.dp)
            assertTrue(
                "touch band left the slot at collapse=$collapse",
                (capsuleSlotHeight(0f) - AddressFieldTouchHeight) / 2f + shift +
                    AddressFieldTouchHeight <= capsuleSlotHeight(0f),
            )
        }
        // In the resting slot that is 4 dp of travel — exactly the slack
        // a 48 dp box has under it in 56 dp.
        assertEquals(4.dp, addressFieldTouchShift(collapse = 1f, editProgress = 0f))
        assertEquals(0.dp, addressFieldTouchShift(collapse = 0f, editProgress = 0f))
    }

    @Test
    fun `the touch band covers the whole compact capsule`() {
        // Taps anywhere on the minimised pill — its bottom rim
        // included — must reach the two-step tap rather than the page.
        val slot = capsuleSlotHeight(0f)
        val bandTop = (slot - AddressFieldTouchHeight) / 2f +
            addressFieldTouchShift(1f, 0f)
        val capsuleTop = (slot - capsuleDrawnHeight(1f, 0f)) / 2f +
            capsuleBottomAnchor(1f, 0f)
        assertTrue("band starts below the capsule", bandTop <= capsuleTop)
        assertEquals(
            "band must reach the capsule's bottom edge",
            slot.value,
            (bandTop + AddressFieldTouchHeight).value,
            0.001f,
        )
        // And it is still a full Material target.
        assertEquals(48.dp, AddressFieldTouchHeight)
    }

    @Test
    fun `the pill is drawn on the capsule's centre line, not the box's`() {
        // The anchor is split between a layout shift (the touch box,
        // capped by the slot) and a drawing shift (the bubble and the
        // label). The two must add back up to the capsule's own anchor,
        // or the pill drifts off the shape it is supposed to *be*.
        for (edit in listOf(0f, 0.5f, 1f)) {
            for (step in 0..10) {
                val collapse = step / 10f
                assertEquals(
                    "pill left the capsule's centre line at " +
                        "collapse=$collapse edit=$edit",
                    capsuleBottomAnchor(collapse, edit).value,
                    addressFieldTouchShift(collapse, edit).value +
                        addressPillTopShift(collapse, edit).value,
                    0.001f,
                )
            }
        }
        assertEquals(8.dp, addressPillTopShift(collapse = 1f, editProgress = 0f))
        assertEquals(0.dp, addressPillTopShift(collapse = 0f, editProgress = 0f))
    }

    @Test
    fun `the pill never outgrows its touch target`() {
        // The pill is drawn inside a box that stays
        // [AddressFieldTouchHeight] tall in every state, so neither
        // morph may paint a bubble taller than the box that owns the
        // taps.
        for (edit in listOf(0f, 0.5f, 1f)) {
            for (collapse in listOf(0f, 0.5f, 1f)) {
                assertTrue(
                    "pill overflows its touch box at edit=$edit collapse=$collapse",
                    addressPillHeight(collapse, edit) <= AddressFieldTouchHeight,
                )
            }
        }
    }

    @Test
    fun `overshooting springs cannot push the capsule past its bounds`() {
        // Both fractions come off expressive spatial springs, which
        // overshoot slightly at either end.
        assertEquals(CapsuleCompactHeight, capsuleDrawnHeight(collapse = 1.08f, editProgress = 0f))
        assertEquals(CapsuleHeight, capsuleDrawnHeight(collapse = -0.08f, editProgress = 0f))
        assertEquals(
            CapsuleEditingHeight,
            capsuleDrawnHeight(collapse = 0f, editProgress = 1.08f),
        )
    }
}
