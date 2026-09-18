package baby.freedom.mobile.browser

import androidx.compose.material3.Typography
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Safari-style minimised capsule (#40): what the compact state is
 * *shaped* like, and what a tap on it does.
 *
 * Stage 2/3's height model (and #47's bottom anchor) is tested in
 * [CapsuleGeometryTest] and is deliberately untouched here — 32 / 56 /
 * 64 dp, the slot that never moves for a scroll, editing winning over
 * compact. What this pins down is the other two things that interpolate
 * along the collapse (the capsule's *width* and the label's type) plus
 * the two-step tap.
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

    // ---- tap surface -----------------------------------------------

    @Test
    fun `the capsule's gutters stay the controls' while a control is on screen`() {
        // Nothing may move under a half-collapsed bar: for as long as
        // there is a flanking control, the air around it is its own.
        val controlsGone = 1f / 1.6f
        for (step in 0..10) {
            val collapse = controlsGone * step / 10f
            assertEquals(
                "handover at collapse=$collapse",
                0f,
                capsuleGutterHandover(collapse),
                0f,
            )
        }
        assertEquals(4.dp, capsuleControlGutter(0f))
        assertEquals(4.dp, capsuleFieldGutter(0f))
        assertEquals(8.dp, CapsuleFieldSideGutter)
        assertEquals(0.dp, addressPillSideInset(0f))
    }

    @Test
    fun `the compact pill is tap surface from edge to edge`() {
        // Once the controls are gone their gutters are nobody's, and the
        // field's touch box takes them — which is what stops a tap near
        // the compact capsule's rim from falling through to the text
        // field (opening the editor on what should be the expand-only
        // first tap) or doing nothing at all.
        assertEquals(1f, capsuleGutterHandover(1f), 0f)
        assertEquals(0.dp, capsuleControlGutter(1f))
        assertEquals(0.dp, capsuleFieldGutter(1f))
        assertEquals(CapsuleFieldSideGutter, addressPillSideInset(1f))
    }

    @Test
    fun `the trailing control is all the gesture surface ever gives up`() {
        // The surface runs the pill's full width in every state; the one
        // thing that keeps its own taps is Reload / Stop / ×, and it
        // keeps them by being composed *over* the surface rather than by
        // the surface standing back from it (#42). So the only strip of
        // pill that is not the surface's is the slot itself: 32 dp at
        // rest, and the 4 dp of air outboard of it — where a long-press
        // used to reach the text field and raise the platform's selection
        // UI — is the surface's like the rest of the pill.
        assertEquals(1f, capsulePillSlotScale(0f), 0f)
        assertEquals(4.dp, addressLabelPadding(0f, 4.dp))
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
    fun `the handover gives back in drawing exactly what it takes in layout`() {
        // The pill is painted where it has always been painted: whatever
        // the touch box swallows, the drawn inset returns.
        for (step in 0..20) {
            val collapse = step / 20f
            assertEquals(
                "pill edge moved at collapse=$collapse",
                CapsuleFieldSideGutter.value,
                capsuleControlGutter(collapse).value +
                    capsuleFieldGutter(collapse).value +
                    addressPillSideInset(collapse).value,
                0.001f,
            )
        }
        // And an overshooting spring can neither widen the box past the
        // gutters nor push the pill out of it.
        assertEquals(CapsuleFieldSideGutter, addressPillSideInset(1.08f))
        assertEquals(0.dp, addressPillSideInset(-0.08f))
    }

    @Test
    fun `the label does not move when the box under it widens`() {
        // The label's inset is measured from the *capsule's* edge, so it
        // is unaffected by the handover: 24 dp at rest (16 dp of pill
        // padding over the 8 dp of gutter), the symmetric compact side
        // padding when collapsed.
        assertEquals(16.dp + CapsuleFieldSideGutter, capsuleLabelInset(0f, 16.dp))
        assertEquals(CapsuleCompactSidePadding, capsuleLabelInset(1f, 16.dp))
        assertEquals(CapsuleCompactSidePadding, capsuleLabelInset(1f, 4.dp))
        // #47: the compact capsule hugs the label with 12 dp of air per
        // side — twice the 6 dp it keeps above and below it, and the
        // most a pill this short can carry and still read as hugging.
        assertEquals(12.dp, CapsuleCompactSidePadding)
        assertEquals(CapsuleCompactVerticalPadding * 2, CapsuleCompactSidePadding)
        for (step in 0..20) {
            val collapse = step / 20f
            val padding = addressLabelPadding(collapse, 16.dp)
            assertTrue("negative label padding at collapse=$collapse", padding >= 0.dp)
            assertEquals(
                "label moved at collapse=$collapse",
                capsuleLabelInset(collapse, 16.dp).value,
                padding.value +
                    capsuleControlGutter(collapse).value +
                    capsuleFieldGutter(collapse).value,
                0.001f,
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

    private fun offset(collapse: Float, canGoBack: Boolean = true) =
        addressLabelCenterOffset(
            collapse = collapse,
            restingInset = addressLabelRestingInset(canGoBack, hasBadge = false),
            restingWidth = restingWidth,
            labelWidth = labelWidth,
        )

    @Test
    fun `the resting inset is the whole row in front of the label`() {
        // 4 dp of capsule gutter, the Back button's 48 dp slot, 4 dp of
        // field gutter and the pill's own 16 dp label inset.
        assertEquals(72.dp, addressLabelRestingInset(canGoBack = true, hasBadge = false))
        // No history to pop: the label starts where the Back button would
        // have.
        assertEquals(24.dp, addressLabelRestingInset(canGoBack = false, hasBadge = false))
        // The badge trades 6 dp of the inset for its 16 dp mark and the
        // 8 dp of air after it, so it costs the label 18 dp net.
        assertEquals(
            addressLabelRestingInset(canGoBack = true, hasBadge = false) + 18.dp,
            addressLabelRestingInset(canGoBack = true, hasBadge = true),
        )
    }

    @Test
    fun `the label is laid out against the resting width and nothing else`() {
        // Everything beside the label at rest, added up: the resting
        // inset in front, and behind it 4 dp of pill inset, the 32 dp
        // trailing slot, 4 dp of field gutter, tabs and overflow, 4 dp
        // of capsule gutter.
        val max = addressLabelMaxWidth(restingWidth, hasBadge = false)
        assertEquals(restingWidth - 212.dp, max)
        assertTrue("a phone-width bar must leave room for a domain", max > 120.dp)
        // The badge takes its 18 dp net out of the same width.
        assertEquals(max - 18.dp, addressLabelMaxWidth(restingWidth, hasBadge = true))
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
        // rows, the one with a Back button in it.
        //
        // Everything behind the label — 4 dp of pill inset, the 32 dp
        // trailing slot, 4 dp of field gutter, tabs and overflow, 4 dp
        // of capsule gutter.
        val behind = 140.dp
        val withHistory =
            restingWidth - addressLabelRestingInset(canGoBack = true, hasBadge = false) - behind
        val withoutHistory =
            restingWidth - addressLabelRestingInset(canGoBack = false, hasBadge = false) - behind
        assertEquals(withHistory + CapsuleControlSize, withoutHistory)
        // A threshold that followed the history state would re-ellipsise
        // a long name mid-session and step the compact capsule — which
        // is sized from that one layout — by the button's 48 dp in a
        // single unanimated frame. It takes the narrower row instead.
        assertEquals(withHistory, addressLabelMaxWidth(restingWidth, hasBadge = false))
        // The resting *inset* does still follow the button — there the
        // button is on screen, taking the room it moved the label out of.
        assertTrue(
            "the Back button still moves the resting label",
            addressLabelRestingInset(canGoBack = true, hasBadge = false) >
                addressLabelRestingInset(canGoBack = false, hasBadge = false),
        )
    }

    @Test
    fun `the label's two settled positions are the ones it has always had`() {
        // At rest: its leading edge is the resting inset in from the
        // capsule's, i.e. its centre is half a label further in still.
        assertEquals(
            addressLabelRestingInset(canGoBack = true, hasBadge = false) +
                labelWidth / 2f - restingWidth / 2f,
            offset(0f),
        )
        // Compact: dead centre, because `compactCapsuleWidth` sizes the
        // capsule *from* this label.
        assertEquals(0.dp, offset(1f))
    }

    @Test
    fun `the label's x is one function of the collapse`() {
        // The whole point of #55: affine in the fraction, so the label
        // cannot run ahead of the capsule and fall back. Any three
        // samples must be collinear.
        val a = offset(0f).value
        val b = offset(1f).value
        for (step in 0..20) {
            val c = step / 20f
            assertEquals(
                "label left the straight line at collapse=$c",
                a + (b - a) * c,
                offset(c).value,
                0.001f,
            )
        }
        // Monotone with it, too — it never doubles back.
        var previous = offset(0f)
        for (step in 1..20) {
            val next = offset(step / 20f)
            assertTrue("the label moved backwards at step $step", next >= previous)
            previous = next
        }
    }

    @Test
    fun `an overshooting spring cannot push the label past either end`() {
        assertEquals(offset(1f), offset(1.08f))
        assertEquals(offset(0f), offset(-0.08f))
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
