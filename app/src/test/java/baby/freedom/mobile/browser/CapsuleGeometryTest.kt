package baby.freedom.mobile.browser

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one height model behind the floating bar.
 *
 * Two morphs drive the same object — compact-on-scroll (#30) and the
 * editing morph (#31) — and the whole point of merging them was that
 * they resolve through a single rule rather than fighting over the same
 * pixels. The split bar (#60) adds a third consumer of that rule: the
 * two round buttons are drawn at the resting height the field starts
 * from. These are the invariants the rule has to keep:
 *
 * - three settled heights, 32 / 44 / 64 dp of ink;
 * - all of it drawn inside a 48 dp slot, so every control keeps
 *   Material's touch target;
 * - the layout *slot* only ever moves for editing, so a scroll can't
 *   shift anything outside the bar;
 * - the compact pill shrinks *upwards* off a bottom edge that never
 *   moves (#47), while the 48 dp touch band stays over it;
 * - editing wins over compact.
 */
class CapsuleGeometryTest {

    /**
     * The label's compact line box at a system font scale, read exactly
     * the way [BottomToolbar] reads it: the 20 sp line height through the
     * density.
     *
     * That curve is non-linear here too: the ui-unit [Density] factory
     * puts a `FontScaleConverter` behind `toDp` on the JVM exactly as the
     * platform does on API 34+, so the 20 sp box is 23.6 dp at scale 1.3
     * and 34 dp at 2.0 — not the 26 and 40 dp a linear scale would give.
     * Every expected value below is read off that curve, which is
     * precisely why the app asks the density rather than multiplying the
     * scale itself; do not recompute one by hand.
     */
    private fun lineBox(fontScale: Float): Dp =
        with(Density(density = 2.625f, fontScale = fontScale)) {
            AddressLabelCompactLineHeight.toDp()
        }

    /** The compact capsule's height at that scale. */
    private fun compact(fontScale: Float): Dp = capsuleCompactHeight(lineBox(fontScale))

    /**
     * Every scale the system offers, from the smallest setting to the
     * accessibility maximum (`settings put system font_scale 2.0`).
     */
    private val fontScales = listOf(0.85f, 1f, 1.15f, 1.3f, 1.5f, 1.8f, 2f)

    @Test
    fun `the bar has exactly three settled heights`() {
        assertEquals(CapsuleRestingHeight, capsuleDrawnHeight(collapse = 0f, editProgress = 0f))
        assertEquals(CapsuleCompactHeight, capsuleDrawnHeight(collapse = 1f, editProgress = 0f))
        assertEquals(CapsuleEditingHeight, capsuleDrawnHeight(collapse = 0f, editProgress = 1f))

        assertEquals(32.dp, CapsuleCompactHeight)
        assertEquals(44.dp, CapsuleRestingHeight)
        assertEquals(64.dp, CapsuleEditingHeight)
        // …and a slot that gives every control its full touch target
        // around whichever of them is drawn.
        assertEquals(48.dp, CapsuleHeight)
        assertEquals(AddressFieldTouchHeight, CapsuleHeight)
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
        assertEquals(56.dp, capsuleSlotHeight(0.5f))
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
    fun `the bar's ink stands inside its slot, never outside it`() {
        // The split bar draws 44 dp of surface in a 48 dp slot, so there
        // are 2 dp of slack per side at rest — the slack that also turns
        // the caller's 14 dp margin into the mockup's 16 dp of drawn one.
        // Editing is the one state that fills the slot exactly.
        assertEquals(2.dp, (CapsuleHeight - CapsuleRestingHeight) / 2f)
        for (edit in listOf(0f, 0.5f, 1f)) {
            for (step in 0..10) {
                val collapse = step / 10f
                assertTrue(
                    "ink left the slot at collapse=$collapse edit=$edit",
                    capsuleDrawnHeight(collapse, edit) <= capsuleSlotHeight(edit),
                )
            }
        }
        assertEquals(
            capsuleSlotHeight(1f),
            capsuleDrawnHeight(collapse = 0f, editProgress = 1f),
        )
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
        // And the line box is a real one — the line box of the type size
        // the compact label settles at (`bodyMedium`'s, pinned against
        // the theme in [CapsuleCompactStateTest]), not a number picked
        // to make the sum work.
        assertEquals(20.sp, AddressLabelCompactLineHeight)
        // The constant is the *unscaled* endpoint: at `fontScale == 1`
        // the derivation and it are the same number.
        assertEquals(CapsuleCompactHeight, compact(1f))
        assertEquals(20.dp, lineBox(1f))
    }

    // ---- font scale (#49) ------------------------------------------

    @Test
    fun `the six dp of air survives every font scale`() {
        // #49: the line box is stated in sp, so it grows with the system
        // font scale. A fixed 32 dp capsule would swallow the air to feed
        // it — at scale 2.0 the 20 sp box is 34 dp, i.e. 2 dp taller than
        // the whole capsule. The capsule grows with the box instead.
        for (scale in fontScales) {
            val box = lineBox(scale)
            val height = compact(scale)
            assertEquals(
                "air above/below the label changed at fontScale=$scale",
                CapsuleCompactVerticalPadding.value,
                (height - box).value / 2f,
                0.001f,
            )
        }
        // The values that pin the ends of that, on the platform's
        // non-linear curve: 32 dp at the default scale, 35.6 dp at 1.3,
        // 46 dp at the 2.0 maximum. The boxes they are the air around are
        // that same curve's, not a multiplication — pinned here so the
        // sums above can only be read off it.
        assertEquals(20.dp, lineBox(1f))
        assertEquals(23.6f, lineBox(1.3f).value, 0.01f)
        assertEquals(34.dp, lineBox(2f))
        assertEquals(32.dp, compact(1f))
        assertEquals(35.6f, compact(1.3f).value, 0.01f)
        assertEquals(46.dp, compact(2f))
    }

    @Test
    fun `the compact capsule never outgrows the slot it shrinks inside`() {
        // A "compact" capsule taller than the resting one is not a
        // collapse — and it would push the bottom anchor negative, i.e.
        // the bar out of its slot. Past ~2.2 the line box stops growing
        // rather than the capsule leaving the slot.
        for (scale in fontScales + listOf(2.5f, 3f, 10f)) {
            val height = compact(scale)
            assertTrue(
                "compact capsule left the slot at fontScale=$scale ($height)",
                height <= CapsuleHeight,
            )
            assertTrue(
                "compact capsule stopped hugging at fontScale=$scale",
                height >= CapsuleCompactVerticalPadding * 2,
            )
        }
        assertEquals(CapsuleHeight, compact(10f))
        // And the label is told the clamped box too, so the type can't go
        // on growing out of the shape built to hold it.
        assertEquals(
            CapsuleHeight - CapsuleCompactVerticalPadding * 2,
            compactLabelLineBox(200.dp),
        )
    }

    @Test
    fun `the resting and editing heights ignore the font scale`() {
        // Only the compact end is the label's; the other two are sized by
        // the controls they hold.
        for (scale in fontScales) {
            val compactHeight = compact(scale)
            assertEquals(
                CapsuleRestingHeight,
                capsuleDrawnHeight(collapse = 0f, editProgress = 0f, compactHeight = compactHeight),
            )
            assertEquals(
                CapsuleEditingHeight,
                capsuleDrawnHeight(collapse = 0f, editProgress = 1f, compactHeight = compactHeight),
            )
            assertEquals(CapsuleHeight, capsuleSlotHeight(0f))
            assertEquals(CapsuleEditingHeight, capsuleSlotHeight(1f))
        }
    }

    @Test
    fun `the capsule's bottom edge never moves at any font scale`() {
        // #47/#48's rule, re-checked at every scale: whatever the compact
        // height is, the anchor gives back exactly half the slack, so the
        // drawn bottom edge — and [CapsuleBottomMargin]'s gap to the
        // navigation inset under it — is the slot's own.
        for (scale in fontScales) {
            val compactHeight = compact(scale)
            for (step in 0..20) {
                val collapse = step / 20f
                assertEquals(
                    "capsule bottom moved at collapse=$collapse fontScale=$scale",
                    capsuleSlotHeight(0f).value,
                    capsuleBottomAnchor(collapse, 0f, compactHeight).value * 2f +
                        capsuleDrawnHeight(collapse, 0f, compactHeight).value,
                    0.001f,
                )
            }
            assertTrue(
                "negative anchor at fontScale=$scale",
                capsuleBottomAnchor(1f, 0f, compactHeight) >= 0.dp,
            )
        }
        // A taller capsule is a smaller anchor by exactly as much.
        assertEquals(8.dp, capsuleBottomAnchor(1f, 0f, compact(1f)))
        assertEquals(6.2f, capsuleBottomAnchor(1f, 0f, compact(1.3f)).value, 0.01f)
        assertEquals(1.dp, capsuleBottomAnchor(1f, 0f, compact(2f)))
    }

    @Test
    fun `the touch band still covers the whole compact capsule at every scale`() {
        // The band answers the two-step tap, so every pixel of the pill
        // the user can see has to be inside it — including at a scale
        // where the capsule is taller than Material's 48 dp and the band
        // has to grow with it ([addressFieldTouchHeight]).
        for (scale in fontScales + listOf(2.5f, 3f)) {
            val compactHeight = compact(scale)
            val band = addressFieldTouchHeight(compactHeight)
            val slot = capsuleSlotHeight(0f)
            assertTrue(
                "touch band shrank below 48 dp at fontScale=$scale",
                band >= AddressFieldTouchHeight,
            )
            assertTrue("touch band left the slot at fontScale=$scale", band <= slot)
            val bandTop = (slot - band) / 2f + addressFieldTouchShift(1f, 0f, compactHeight)
            val capsuleTop = (slot - capsuleDrawnHeight(1f, 0f, compactHeight)) / 2f +
                capsuleBottomAnchor(1f, 0f, compactHeight)
            assertTrue("band starts below the capsule at fontScale=$scale", bandTop <= capsuleTop)
            assertEquals(
                "band must reach the capsule's bottom edge at fontScale=$scale",
                slot.value,
                (bandTop + band).value,
                0.001f,
            )
        }
        // It is exactly the 48 dp it always was at every scale the
        // settings offer — the compact capsule is 46 dp even at the
        // maximum — and only grows past that, where a linear font scale
        // would otherwise leave the top of the pill untappable.
        assertEquals(AddressFieldTouchHeight, addressFieldTouchHeight(compact(1f)))
        assertEquals(AddressFieldTouchHeight, addressFieldTouchHeight(compact(1.3f)))
        assertEquals(AddressFieldTouchHeight, addressFieldTouchHeight(compact(2f)))
        assertEquals(CapsuleHeight, addressFieldTouchHeight(compact(3f)))
    }

    @Test
    fun `the field is the compact pill at every font scale`() {
        for (scale in fontScales) {
            val compactHeight = compact(scale)
            assertEquals(
                "field and compact pill drifted apart at fontScale=$scale",
                compactHeight,
                capsuleDrawnHeight(collapse = 1f, editProgress = 0f, compactHeight = compactHeight),
            )
            // And while it is not editing — the one state that grows the
            // slot — it is never painted outside the box that owns the
            // taps.
            for (collapse in listOf(0f, 0.5f, 1f)) {
                assertTrue(
                    "field overflows its touch box at fontScale=$scale collapse=$collapse",
                    capsuleDrawnHeight(collapse, 0f, compactHeight) <=
                        addressFieldTouchHeight(compactHeight),
                )
            }
        }
    }

    @Test
    fun `the pill keeps the capsule's centre line at every font scale`() {
        // The anchor stays split between the touch box's layout shift and
        // the bubble's drawing shift whatever the scale has done to the
        // heights, or the label drifts off the shape it is supposed to be.
        for (scale in fontScales) {
            val compactHeight = compact(scale)
            for (edit in listOf(0f, 0.5f, 1f)) {
                for (step in 0..10) {
                    val collapse = step / 10f
                    assertEquals(
                        "pill left the capsule's centre line at fontScale=$scale " +
                            "collapse=$collapse edit=$edit",
                        capsuleBottomAnchor(collapse, edit, compactHeight).value,
                        addressFieldTouchShift(collapse, edit, compactHeight).value +
                            addressPillTopShift(collapse, edit, compactHeight).value,
                        0.001f,
                    )
                    assertEquals(
                        "control left the capsule's centre line at fontScale=$scale " +
                            "collapse=$collapse edit=$edit",
                        capsuleBottomAnchor(collapse, edit, compactHeight).value,
                        capsuleControlTopShift(collapse, edit, compactHeight).value,
                        0.001f,
                    )
                }
            }
        }
    }

    @Test
    fun `the label's line box and the capsule are the same number`() {
        // What went wrong in #48 was the two being stated separately: the
        // capsule said 32 dp, the label said 20 sp, and they only agreed
        // at one font scale. They are now one derivation.
        for (scale in fontScales + listOf(3f)) {
            val density = Density(density = 2.625f, fontScale = scale)
            val box = with(density) { compactLabelLineBox(AddressLabelCompactLineHeight.toDp()) }
            assertEquals(
                "label and capsule disagree at fontScale=$scale",
                capsuleCompactHeight(with(density) { AddressLabelCompactLineHeight.toDp() }),
                box + CapsuleCompactVerticalPadding * 2,
            )
            // The label's own type is the same derivation seen from the
            // other side: it is laid out once at the resting size and
            // scaled (#55), so the size it settles at is the compact one
            // at every font scale, and the box the capsule is built from
            // is the box that type asks for.
            assertEquals(
                "the compact label is not one type step down at fontScale=$scale",
                with(density) { AddressLabelCompactFontSize.toPx() },
                with(density) { AddressLabelRestingFontSize.toPx() } *
                    addressLabelScale(1f, with(density) { addressLabelCompactScale() }),
                0.001f,
            )
        }
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
        assertEquals(8.dp, capsuleBottomAnchor(collapse = 1f, editProgress = 0f))
    }

    @Test
    fun `the state that fills the slot is not anchored at all`() {
        // The editor is exactly the slot's height, so there is nothing
        // to anchor and it stays centred. Resting is 44 dp of ink in a
        // 48 dp slot, so it carries the 2 dp that puts its bottom edge
        // on the slot's — the same rule, not an exception to it.
        assertEquals(0.dp, capsuleBottomAnchor(collapse = 0f, editProgress = 1f))
        assertEquals(2.dp, capsuleBottomAnchor(collapse = 0f, editProgress = 0f))
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
        assertEquals(8.dp, capsuleBottomAnchor(collapse = 1.08f, editProgress = 0f))
        assertEquals(2.dp, capsuleBottomAnchor(collapse = -0.08f, editProgress = 0f))
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
        // In the resting slot there is no travel at all — a 48 dp box in
        // a 48 dp slot already spans it, so the band covers the compact
        // pill without moving and the whole anchor is spent in drawing.
        assertEquals(0.dp, addressFieldTouchShift(collapse = 1f, editProgress = 0f))
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
    fun `the field is drawn on the bar's centre line, not the box's`() {
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
        // At rest the whole anchor is the drawing shift: the touch band
        // already spans the slot, so the 2 dp that put the ink on the
        // slot's bottom edge are spent here and nowhere else.
        assertEquals(2.dp, addressPillTopShift(collapse = 0f, editProgress = 0f))
        assertEquals(0.dp, addressPillTopShift(collapse = 0f, editProgress = 1f))
    }

    @Test
    fun `the flanking controls are drawn on that same centre line`() {
        // The controls' boxes fill the slot, so left alone they stay on
        // the slot's centre line while the capsule under them rides
        // down: mid-collapse the still-visible Back / tabs / menu icons
        // would float above the centre line of the capsule and label
        // they sit on. They take the capsule's own anchor, whole.
        for (edit in listOf(0f, 0.5f, 1f)) {
            for (step in 0..10) {
                val collapse = step / 10f
                assertEquals(
                    "control left the capsule's centre line at " +
                        "collapse=$collapse edit=$edit",
                    capsuleBottomAnchor(collapse, edit).value,
                    capsuleControlTopShift(collapse, edit).value,
                    0.001f,
                )
            }
        }
        // Nothing to follow in the editor, which fills the slot.
        assertEquals(2.dp, capsuleControlTopShift(collapse = 0f, editProgress = 0f))
        assertEquals(0.dp, capsuleControlTopShift(collapse = 0f, editProgress = 1f))
        assertEquals(8.dp, capsuleControlTopShift(collapse = 1f, editProgress = 0f))
    }

    @Test
    fun `a control still on screen is never drawn outside its touch box`() {
        // The control's shift is a drawing offset — its touch box does
        // not move — so it may only be spent on room the shrinking
        // control has already given back. The round buttons leave faster
        // than the field drops ([capsulePillSlotScale] against the
        // anchor), so a 44 dp circle scaled about the centre of a 48 dp
        // slot stays inside that slot for every fraction it is still
        // visible.
        val slot = CapsuleHeight.value
        val ink = CapsuleRestingHeight.value
        for (step in 0..20) {
            val collapse = step / 20f
            val scale = capsulePillSlotScale(collapse)
            if (scale <= 0f) continue
            assertTrue(
                "control drawn outside its slot at collapse=$collapse",
                capsuleControlTopShift(collapse, 0f).value + ink * scale / 2f <=
                    slot / 2f + 0.001f,
            )
        }
    }

    @Test
    fun `overshooting springs cannot push the capsule past its bounds`() {
        // Both fractions come off expressive spatial springs, which
        // overshoot slightly at either end.
        assertEquals(CapsuleCompactHeight, capsuleDrawnHeight(collapse = 1.08f, editProgress = 0f))
        assertEquals(CapsuleRestingHeight, capsuleDrawnHeight(collapse = -0.08f, editProgress = 0f))
        assertEquals(
            CapsuleEditingHeight,
            capsuleDrawnHeight(collapse = 0f, editProgress = 1.08f),
        )
    }
}
