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
 * - three settled heights, 44 / 56 / 64 dp;
 * - the layout *slot* only ever moves for editing, so a scroll can't
 *   shift anything outside the capsule;
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

        assertEquals(44.dp, CapsuleCompactHeight)
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
        // directions has to produce a monotone 44 → 64 dp travel with no
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
        assertEquals(6.dp, gutter(collapse = 1f, edit = 0f))
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
