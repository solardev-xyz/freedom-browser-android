package baby.freedom.mobile.browser

import androidx.compose.material3.Typography
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Safari-style minimised pill (#40) and the split bar it minimises
 * from (#60): what the bar is *shaped* like across its width, and what a
 * tap on it does.
 *
 * Stage 2/3's height model (and #47's bottom anchor) is tested in
 * [CapsuleGeometryTest] and is deliberately untouched here — 32 / 44 /
 * 64 dp, the slot that never moves for a scroll, editing winning over
 * compact. What this pins down is everything that happens along the
 * other axis: where the three surfaces sit, the field's width through
 * both morphs, the label's type and position, and the two-step tap.
 */
class CapsuleCompactStateTest {

    /** A phone-width bar slot: 411 dp window less 2 × 14 dp margin. */
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

    // ---- the split bar's three surfaces ------------------------------

    @Test
    fun `the field is whatever the round buttons leave of the bar`() {
        // A 48 dp button slot and a 7 dp gap on each side that has one.
        assertEquals(48.dp, CapsuleControlSize)
        assertEquals(7.dp, CapsuleSplitGap)
        assertEquals(restingWidth - 110.dp, addressFieldRestingWidth(restingWidth, true))
        assertEquals(restingWidth - 55.dp, addressFieldRestingWidth(restingWidth, false))
        // Never negative, whatever window it is handed.
        assertEquals(0.dp, addressFieldRestingWidth(40.dp, true))
    }

    @Test
    fun `a tab with no history widens the field instead of leaving a hole`() {
        // The brief's one explicit "don't": no gap where Back was, and no
        // Home button substituted into its slot. The field takes it.
        val withHistory = addressFieldRestingWidth(restingWidth, true)
        val without = addressFieldRestingWidth(restingWidth, false)
        assertEquals(CapsuleControlSize + CapsuleSplitGap, without - withHistory)
        // …and it moves by exactly half of what it gained, which is what
        // taking the slot rather than growing into the middle means.
        assertEquals(0.dp, addressFieldCenterOffset(true))
        assertEquals(
            -(CapsuleControlSize + CapsuleSplitGap) / 2f,
            addressFieldCenterOffset(false),
        )
    }

    @Test
    fun `the drawn field has three settled widths`() {
        val restingField = addressFieldRestingWidth(restingWidth, canGoBack = true)
        val compact = compactCapsuleWidth(142.dp, restingField)
        assertEquals(
            restingField,
            addressFieldDrawnWidth(0f, 0f, restingWidth, true, compact),
        )
        // Editing hands the field the width the round buttons were using.
        assertEquals(
            restingWidth,
            addressFieldDrawnWidth(0f, 1f, restingWidth, true, compact),
        )
        assertEquals(
            compact,
            addressFieldDrawnWidth(1f, 0f, restingWidth, true, compact),
        )
    }

    @Test
    fun `both morphs pull the field back onto the slot's centre line`() {
        // At rest with no Back button the field is off-centre by half a
        // button; the editor and the compact pill are both centred, and
        // the travel between is affine in the driving fraction.
        assertEquals(addressFieldCenterOffset(false), addressFieldDrawnCenter(0f, 0f, false))
        assertEquals(0.dp, addressFieldDrawnCenter(0f, 1f, false))
        assertEquals(0.dp, addressFieldDrawnCenter(1f, 0f, false))
        val resting = addressFieldCenterOffset(false).value
        for (step in 0..20) {
            val t = step / 20f
            assertEquals(
                "field centre wandered at edit=$t",
                resting + (0f - resting) * t,
                addressFieldDrawnCenter(0f, t, false).value,
                0.001f,
            )
        }
        // Overshooting springs can't push it past either end.
        assertEquals(0.dp, addressFieldDrawnCenter(1.08f, 0f, false))
        assertEquals(addressFieldCenterOffset(false), addressFieldDrawnCenter(-0.08f, 0f, false))
    }

    // ---- tap surface -----------------------------------------------

    @Test
    fun `the trailing control is all the gesture surface ever gives up`() {
        // The surface runs the field's full width in every state; the
        // things that keep their own taps are the overflow menu and
        // Reload / Stop / ×, and they keep them by being composed *over*
        // the surface rather than by the surface standing back from them
        // (#42). The field's own inset is not a strip the surface gives
        // up — it is glass the user is aiming at.
        assertEquals(1f, capsulePillSlotScale(0f), 0f)
        assertEquals(8.dp, capsuleLabelInset(0f))
    }

    @Test
    fun `the trailing slot is gone well before the capsule settles compact`() {
        // Which is what makes "the compact pill is the surface, edge to
        // edge" true without exception: by the time the capsule is
        // anywhere near settled there is no control left to compose over
        // it, so a long-press anywhere on it is the URL menu's.
        val slotsGone = (0..100).map { it / 100f }.first { capsulePillSlotScale(it) <= 0f }
        assertTrue(
            "the slots should outlast half the collapse",
            slotsGone > CAPSULE_TAP_EXPAND_ABOVE,
        )
        assertTrue("the slots should be gone before the capsule settles", slotsGone < 1f)
        assertEquals(0f, capsulePillSlotScale(1f), 0f)
        // And an overshooting spring can't bring it back.
        assertEquals(0f, capsulePillSlotScale(1.08f), 0f)
    }

    @Test
    fun `the field's content inset is symmetric at rest and hugs the label when compact`() {
        // Both ends of the field hold the same 32 dp slot at the same
        // inset, which is what lets the domain simply centre in it.
        assertEquals(8.dp, capsuleLabelInset(0f))
        assertEquals(CapsuleCompactSidePadding, capsuleLabelInset(1f))
        // #47: the compact pill hugs the label with 12 dp of air per
        // side — twice the 6 dp it keeps above and below it, and the
        // most a pill this short can carry and still read as hugging.
        assertEquals(12.dp, CapsuleCompactSidePadding)
        assertEquals(CapsuleCompactVerticalPadding * 2, CapsuleCompactSidePadding)
        for (step in 0..20) {
            val collapse = step / 20f
            val inset = capsuleLabelInset(collapse)
            assertTrue("negative content inset at collapse=$collapse", inset >= 0.dp)
            assertTrue(
                "content inset left its band at collapse=$collapse",
                inset >= 8.dp && inset <= CapsuleCompactSidePadding,
            )
        }
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
        assertEquals(88.dp, CapsuleCompactMinWidth)
        // The floor is the *only* thing an empty label can reach for
        // (the home tab never collapses, but the geometry may not depend
        // on that).
        assertEquals(
            CapsuleCompactMinWidth,
            compactCapsuleWidth(labelWidth = 0.dp, restingWidth = restingWidth),
        )
    }

    @Test
    fun `a real host sizes the capsule, not the floor`() {
        // What the smaller floor is for: past a handful of characters
        // it is the label that decides the width, so the floor only
        // catches stubs rather than quietly becoming a fixed width.
        // `example.com` measures ~78 dp at the compact type size on a
        // 420 dpi phone, `documentation.swarm.eth` ~163 dp.
        for (labelWidth in listOf(78.dp, 163.dp)) {
            val width = compactCapsuleWidth(labelWidth, restingWidth)
            assertTrue(
                "the floor took over at labelWidth=$labelWidth",
                width > CapsuleCompactMinWidth,
            )
            assertEquals(
                labelWidth + CapsuleCompactSidePadding * 2 + 2.dp,
                width,
            )
        }
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
        assertEquals(60.dp, compactCapsuleWidth(labelWidth = 4.dp, restingWidth = 60.dp))
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
        // The resting size is what the label has always rendered at, and
        // the compact one is one step down the M3 scale (bodyLarge →
        // bodyMedium). Pinned, because a trust surface may not quietly
        // shrink further than the brief allows.
        assertEquals(16.sp, AddressLabelRestingFontSize)
        assertEquals(14.sp, AddressLabelCompactFontSize)
        assertEquals(24.sp, AddressLabelRestingLineHeight)
        assertEquals(20.sp, AddressLabelCompactLineHeight)
        // …and the step is taken as a scale, not as a second text layout
        // (#55): one at rest, exactly 14/16 when compact.
        assertEquals(1f, addressLabelScale(0f), 0f)
        assertEquals(AddressLabelCompactScale, addressLabelScale(1f), 0f)
        assertEquals(
            AddressLabelCompactFontSize.value / AddressLabelRestingFontSize.value,
            AddressLabelCompactScale,
            0f,
        )
        assertEquals(0.875f, AddressLabelCompactScale, 1e-6f)
    }

    @Test
    fun `the resting label is the size the theme gives every label`() {
        // The label sets no size of its own before this stage, so it
        // renders at whatever `LocalTextStyle` is — and `MaterialTheme`
        // provides that as `typography.bodyLarge`. Reading the theme's
        // own number here is what keeps the resting end of the
        // interpolation from silently drifting off the shipped size.
        assertEquals(Typography().bodyLarge.fontSize, AddressLabelRestingFontSize)
        assertEquals(Typography().bodyMedium.fontSize, AddressLabelCompactFontSize)
        assertEquals(Typography().bodyLarge.lineHeight, AddressLabelRestingLineHeight)
        assertEquals(Typography().bodyMedium.lineHeight, AddressLabelCompactLineHeight)
    }

    @Test
    fun `the label scale morphs rather than snaps`() {
        assertEquals((1f + AddressLabelCompactScale) / 2f, addressLabelScale(0.5f), 1e-6f)
        var previous = addressLabelScale(0f)
        for (step in 1..20) {
            val scale = addressLabelScale(step / 20f)
            assertTrue("label grew at step $step", scale <= previous)
            assertTrue("label jumped at step $step", previous - scale < 0.05f)
            previous = scale
        }
        assertEquals(AddressLabelCompactScale, previous, 1e-6f)
    }

    @Test
    fun `an overshooting spring cannot shrink the label further`() {
        assertEquals(AddressLabelCompactScale, addressLabelScale(1.08f), 0f)
        assertEquals(1f, addressLabelScale(-0.08f), 0f)
    }

    // ---- where the label sits (#55) --------------------------------

    /** A label that fits: `rfc-editor.org` at 16 sp on a 420 dpi phone. */
    private val labelWidth = 99.dp

    private fun offset(collapse: Float, canGoBack: Boolean = true, hasBadge: Boolean = false) =
        addressLabelCenterOffset(
            collapse = collapse,
            restingCenter = addressLabelRestingCenter(canGoBack, hasBadge),
        )

    @Test
    fun `the resting label is centred in the field it sits in`() {
        // The field's two control slots are the same size at the same
        // inset, so they cancel: the domain's resting centre is the
        // field's centre, which is the bar's own whenever Back is there.
        assertEquals(0.dp, addressLabelRestingCenter(canGoBack = true, hasBadge = false))
        // Without Back the field has moved, and the label with it.
        assertEquals(
            addressFieldCenterOffset(false),
            addressLabelRestingCenter(canGoBack = false, hasBadge = false),
        )
        // The badge sits in front of the domain inside the same box, so
        // the domain gives up half of its 24 dp block to keep the pair
        // centred.
        assertEquals(
            addressLabelRestingCenter(canGoBack = true, hasBadge = false) + 12.dp,
            addressLabelRestingCenter(canGoBack = true, hasBadge = true),
        )
    }

    @Test
    fun `the label is laid out against the resting field and nothing else`() {
        // Everything beside the label inside the field, added up: 8 dp of
        // inset and a 32 dp slot at each end.
        val max = addressLabelMaxWidth(restingWidth, hasBadge = false)
        assertEquals(addressFieldRestingWidth(restingWidth, true) - 80.dp, max)
        assertTrue("a phone-width bar must leave room for a domain", max > 120.dp)
        // The badge takes its 24 dp — mark plus the air after it — out of
        // the same width.
        assertEquals(max - 24.dp, addressLabelMaxWidth(restingWidth, hasBadge = true))
        // …and on a window narrower than its own chrome it bottoms out
        // rather than going negative.
        assertEquals(0.dp, addressLabelMaxWidth(60.dp, hasBadge = false))
    }

    @Test
    fun `history appearing under a compact bar cannot re-ellipsise the label`() {
        // `canGoBack` is the one thing in the resting row that flips
        // while the domain stays put — an in-page `pushState` gives the
        // tab its first history entry without changing the host — and it
        // flips while the Back button it belongs to is not even on
        // screen. So the width the ellipsis is settled against reserves
        // the button's slot either way: it is the *narrower* of the two
        // fields, the one with a Back button beside it.
        val inside = 80.dp
        val withHistory = addressFieldRestingWidth(restingWidth, canGoBack = true) - inside
        val withoutHistory = addressFieldRestingWidth(restingWidth, canGoBack = false) - inside
        assertEquals(withHistory + CapsuleControlSize + CapsuleSplitGap, withoutHistory)
        // A threshold that followed the history state would re-ellipsise
        // a long name mid-session and step the compact pill — which is
        // sized from that one layout — by the button's slot in a single
        // unanimated frame. It takes the narrower field instead.
        assertEquals(withHistory, addressLabelMaxWidth(restingWidth, hasBadge = false))
        // Where the label *starts* does still follow the button — there
        // the button is on screen, taking the room it moved the field out
        // of.
        assertTrue(
            "the Back button still moves the resting label",
            addressLabelRestingCenter(canGoBack = true, hasBadge = false) >
                addressLabelRestingCenter(canGoBack = false, hasBadge = false),
        )
    }

    @Test
    fun `the label's two settled positions are the field's and the slot's`() {
        // At rest: the centre of the field's content box.
        assertEquals(addressLabelRestingCenter(canGoBack = true, hasBadge = false), offset(0f))
        assertEquals(
            addressLabelRestingCenter(canGoBack = false, hasBadge = false),
            offset(0f, canGoBack = false),
        )
        // Compact: dead centre, because `compactCapsuleWidth` sizes the
        // pill *from* this label.
        assertEquals(0.dp, offset(1f))
        assertEquals(0.dp, offset(1f, canGoBack = false))
    }

    @Test
    fun `the label's x is one function of the collapse`() {
        // The whole point of #55: affine in the fraction, so the label
        // cannot run ahead of the pill and fall back. Any three samples
        // must be collinear. Taken on a tab with no history, where the
        // label actually has somewhere to travel (with Back on screen the
        // two ends coincide, which is the split bar's own doing).
        val a = offset(0f, canGoBack = false).value
        val b = offset(1f, canGoBack = false).value
        assertTrue("the label should have somewhere to travel", a != b)
        for (step in 0..20) {
            val c = step / 20f
            assertEquals(
                "label left the straight line at collapse=$c",
                a + (b - a) * c,
                offset(c, canGoBack = false).value,
                0.001f,
            )
        }
        // Monotone with it, too — it never doubles back.
        var previous = offset(0f, canGoBack = false)
        for (step in 1..20) {
            val next = offset(step / 20f, canGoBack = false)
            assertTrue("the label moved backwards at step $step", next >= previous)
            previous = next
        }
    }

    @Test
    fun `an overshooting spring cannot push the label past either end`() {
        assertEquals(offset(1f, canGoBack = false), offset(1.08f, canGoBack = false))
        assertEquals(offset(0f, canGoBack = false), offset(-0.08f, canGoBack = false))
    }

    @Test
    fun `a label the capsule was sized for is centred in it`() {
        // The compact capsule is the scaled label plus 12 dp a side (and
        // the rounding slack), and the label's offset is 0 there — so the
        // air either side of it is the same air, which is what "hugs its
        // label" means.
        val compactLabel = labelWidth * AddressLabelCompactScale
        val capsule = compactCapsuleWidth(compactLabel, restingWidth)
        assertEquals(0.dp, offset(1f))
        assertEquals(
            (capsule - compactLabel) / 2f,
            (capsule - compactLabel) / 2f - offset(1f),
        )
        assertTrue(
            "the capsule must still hold the label it was measured from",
            capsule >= compactLabel + CapsuleCompactSidePadding * 2,
        )
    }
}
