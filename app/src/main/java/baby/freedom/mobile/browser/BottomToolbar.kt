package baby.freedom.mobile.browser

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import baby.freedom.mobile.ui.isLight
import baby.freedom.swarm.NodeInfo
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Height of the *slot* the bar is laid out in at rest — one row shared
 * by all three of its floating surfaces (Back, the address field, the
 * tab counter), and the height every one of their touch targets is laid
 * out at.
 *
 * Forty-eight dp, which is Material's minimum interactive size and
 * therefore the shortest slot that can hold three controls without any
 * of them giving up a pixel of its target. The surfaces themselves are
 * *drawn* shorter — [CapsuleRestingHeight] — so the bar is 44 dp of ink
 * inside a 48 dp band of touch, which is the rule the address pill has
 * always been drawn by (see [AddressFieldTouchHeight]) now applied to
 * the whole bar.
 */
internal val CapsuleHeight = 48.dp

/**
 * Height the three surfaces are *drawn* at, at rest.
 *
 * The bar is Safari's split one, adapted: a round Back button, a
 * floating address field and a round tab counter, three separate
 * surfaces on one horizontal line rather than one capsule with controls
 * laid over it. Forty-four dp is the mockup's own height (132 px at the
 * 3× density it was rendered at) and the diameter of the two circles as
 * well as the height of the field, so the line reads as one object cut
 * into three rather than as three controls that happen to be adjacent.
 *
 * It is ink only. Nothing is laid out at this height: the slot stays
 * [CapsuleHeight], every control keeps its 48 dp target inside it, and
 * the 2 dp of slack that leaves around each circle is also what turns
 * [CapsuleSideMargin] into the mockup's 16 dp of drawn margin and
 * [CapsuleSplitGap] into its 9 dp gap.
 */
internal val CapsuleRestingHeight = 44.dp

/**
 * Air above and below the compact label's line box, per side — the whole
 * vertical padding the minimised capsule has.
 *
 * Six dp is what is left of iOS Safari's minimised bar once the tray and
 * the controls are gone: a pill that hugs the domain with a few points of
 * air rather than a bar that happens to contain one.
 */
internal val CapsuleCompactVerticalPadding = 6.dp

/**
 * Height of the capsule in its compact (scrolled) state **at
 * `fontScale == 1`**: the compact label's line box (20 sp at the compact
 * type size, see [AddressLabelCompactLineHeight]) plus
 * [CapsuleCompactVerticalPadding] top and bottom — 20 + 2 × 6 = **32 dp**
 * — and nothing else. The split bar leaves this untouched: the field was
 * already the only surface the compact state keeps (the two round
 * buttons scale out with the field's own controls, see
 * [Modifier.collapsingControl]), so compacting still means the same
 * thing it did — the pill wrapped tight to the domain and nothing else.
 *
 * It is the *unscaled* endpoint, not the height the capsule is drawn at:
 * the label's line box is stated in `sp`, so it grows with the system
 * font scale and a fixed 32 dp would eat the 6 dp of air to feed it
 * (#49 — at `font_scale 2.0` the 20 sp box is 34 dp, so the air would be
 * down to −1 dp and the descenders of a `wikipedia.org` would be sitting
 * on the capsule's bottom rim). [capsuleCompactHeight] derives the real
 * one from the *scaled* box; this is what that comes out at when the
 * scale is 1, and the default every geometry function here falls back to
 * so the unscaled capsule can still be talked about in one value.
 *
 * This supersedes the 40–44 dp band from #30/#40/#45. That band sized the
 * minimised bar as a smaller *bar*; the point of losing the rim was to
 * stop it being a bar at all. The touch target is unaffected — it comes
 * from the 48 dp slot and [addressFieldTouchHeight], never from the drawn
 * height (see [capsuleBottomAnchor]).
 */
internal val CapsuleCompactHeight = 32.dp

/**
 * The compact label's line box in dp, as the label is actually laid out
 * at the current font scale — [AddressLabelCompactLineHeight] through
 * `Density.toDp`, which is the system scale for a linear one and Android
 * 14's non-linear curve where that applies.
 *
 * Clamped to what the capsule's own slot can hold: past the last dp the
 * resting capsule has to give ([CapsuleHeight] less the two paddings,
 * i.e. a font scale of about 2.2 — beyond the 2.0 the accessibility
 * settings offer) the line box stops growing rather than the air giving
 * way, because a "compact" capsule taller than the resting one it shrank
 * from is not a collapse and would push the bottom anchor negative. The
 * label is told the clamped box too (see [addressLabelLineHeight]), so
 * the type and the shape around it never disagree about how tall the
 * line is.
 */
internal fun compactLabelLineBox(scaledLineHeight: Dp): Dp =
    scaledLineHeight.coerceIn(0.dp, CapsuleHeight - CapsuleCompactVerticalPadding * 2)

/**
 * Height the compact capsule is actually drawn at: the label's *scaled*
 * line box ([compactLabelLineBox]) plus [CapsuleCompactVerticalPadding]
 * above and below.
 *
 * The derivation is [CapsuleCompactHeight]'s, evaluated at the font scale
 * the user is actually on rather than at 1: the 6 dp of air is the
 * design, the 32 dp was only ever its value at the default scale. So the
 * capsule grows with the type it hugs — 32 dp at scale 1, 35.6 dp at 1.3,
 * 46 dp at the 2.0 maximum (the platform's non-linear curve, which is why
 * the line box is asked of the density rather than multiplied out) — and
 * the domain keeps the same air around it at every accessibility size.
 */
internal fun capsuleCompactHeight(scaledLineHeight: Dp): Dp =
    compactLabelLineBox(scaledLineHeight) + CapsuleCompactVerticalPadding * 2

/**
 * Height of the address field once it has morphed into the editor.
 *
 * The field is the only surface left by then — the two round buttons
 * have scaled out into the screen edges — so the editor is that one
 * surface inflating: it takes the whole width the bar had, reaches
 * towards the screen edges by [CapsuleEditingSideMargin], and grows to
 * this height while doing it. Sixty-four dp is what it has always been,
 * and the slot grows with it (see [capsuleSlotHeight]) so the editor is
 * drawn edge to edge in its own row rather than floating inside a band
 * sized for a shorter bar.
 */
internal val CapsuleEditingHeight = 64.dp

/**
 * Side margin between the bar's *layout* slot and the screen edge.
 *
 * The drawn margin is the mockup's 16 dp: the round buttons are 48 dp
 * touch slots with a 44 dp circle centred in them ([CapsuleRestingHeight]),
 * so the first ink is 2 dp inside the slot on either side. Fourteen plus
 * two, and the number the caller pads with is still the one the touch
 * targets are measured from.
 */
internal val CapsuleSideMargin = 14.dp

/**
 * Side margin while editing. The capsule reaches towards the screen
 * edges to make room for the full URL — still a floating pill with page
 * visible around it, never a full-bleed bar and never a new screen.
 */
internal val CapsuleEditingSideMargin = 8.dp

/** Gap between the capsule and the navigation/gesture inset (brief: 8–12 dp). */
internal val CapsuleBottomMargin = 10.dp

/**
 * Touch height of the address pill. Constant across all three states
 * and independent of the pill's *drawn* height, so neither morph ever
 * shrinks a tap target: the field is laid out 48 dp tall inside the
 * toolbar's (equally constant) 48 dp control row and its pill is
 * painted at the interpolated height inside that box.
 *
 * The split bar makes the pill and the field's own surface one thing —
 * there is no tray around it any more — so this is now the whole
 * relationship between the field's ink and its touch: 44 dp drawn
 * ([capsuleDrawnHeight]) inside 48 dp of band.
 */
internal val AddressFieldTouchHeight = 48.dp

/**
 * The height that band is actually laid out at — [AddressFieldTouchHeight],
 * or the compact capsule itself once an accessibility font scale has made
 * it the taller of the two.
 *
 * The band's job is to answer for every pixel of the minimised pill (see
 * [addressFieldTouchShift]), and a 48 dp band bottom-anchored under a
 * taller capsule cannot: the top of a visibly tappable pill would fall
 * through to the page. The band therefore takes the capsule's height as a
 * *floor under its own floor* — it is never less than Material's 48 dp.
 *
 * Which, on every scale the accessibility settings offer, means it is
 * exactly the 48 dp it has always been: the platform's font-scale curve
 * puts the compact capsule at 46 dp even at the 2.0 maximum. The floor is
 * for the scales past that — a `font_scale` set by hand, or a device with
 * no conversion table where scaling stays linear — where the coverage
 * invariant would otherwise be the thing that gave way.
 *
 * It stays inside the slot at every scale for the same reason the capsule
 * does: [compactLabelLineBox] caps the compact height at [CapsuleHeight].
 */
internal fun addressFieldTouchHeight(compactHeight: Dp = CapsuleCompactHeight): Dp =
    AddressFieldTouchHeight.coerceAtLeast(compactHeight)

/**
 * Height of the *slot* the bar is laid out in.
 *
 * Compacting deliberately doesn't touch it: the bar shrinks inside a
 * frame that stays exactly where it was, which is what keeps the page,
 * the snackbar and the IME reserve from shifting under a flick — the
 * rule the separate progress strip used to violate and that removing
 * it made absolute. Editing is the one transition allowed to grow the
 * slot, because the user asked for it and the keyboard is on its way
 * over that strip anyway.
 */
internal fun capsuleSlotHeight(editProgress: Float): Dp =
    lerp(CapsuleHeight, CapsuleEditingHeight, editProgress.coerceIn(0f, 1f))

/**
 * Height the address field — and with it every surface on the bar — is
 * actually drawn at. The single height function both morphs go through,
 * so there is one geometry model rather than a scroll driver and an
 * editing driver arguing over the same pixels.
 *
 * It reads outwards from the resting ink: [editProgress] grows 44 → 64
 * dp, then [collapse] shrinks whatever that gives towards
 * [compactHeight]. The two can't fight over the result, because
 * [BrowserScreen] holds `collapse` at 0 whenever the address bar has
 * focus — **editing always wins over compact** — so the settled values
 * are exactly 32 ← 44 → 64 at the default font scale. Composing them
 * rather than picking one keeps the handover continuous: tapping a
 * compact bar springs `collapse` back down while `editProgress` comes
 * up, and the field travels 32 → 64 without a discontinuity in the frame
 * where they cross.
 *
 * The two round buttons don't interpolate with it at all: they are
 * circles of the resting height and they *leave* rather than stretch
 * (see [Modifier.collapsingControl]), which is what a split bar can do
 * and a single capsule could not.
 *
 * [compactHeight] is the one end that is not a constant: it is the
 * label's line box plus its air at the *current* font scale (see
 * [capsuleCompactHeight]), and it defaults to what that comes out at when
 * the scale is 1. The other two ends stay put at every scale — the
 * resting bar and the editor are sized by the controls they hold, not
 * by the domain label.
 *
 * Where in the slot that height is drawn is [capsuleBottomAnchor]'s job:
 * the bar shrinks *upwards*, off a bottom edge that never moves.
 */
internal fun capsuleDrawnHeight(
    collapse: Float,
    editProgress: Float,
    compactHeight: Dp = CapsuleCompactHeight,
): Dp = lerp(
    lerp(CapsuleRestingHeight, CapsuleEditingHeight, editProgress.coerceIn(0f, 1f)),
    compactHeight.coerceAtMost(capsuleSlotHeight(editProgress)),
    collapse.coerceIn(0f, 1f),
)

/**
 * How far *below the slot's centre line* the drawn capsule sits — the
 * bottom-anchor rule, expressed as the one offset everything drawn
 * inside the capsule shares.
 *
 * The capsule is centred in its slot by default, which is right for the
 * two states that fill it (resting and editing are both exactly
 * [capsuleSlotHeight] tall, so this is 0 for both). Compacting is the
 * one state that leaves room over, and it spends all of it at the top:
 * the minimised capsule keeps its bottom edge exactly where the resting
 * capsule's was — [CapsuleBottomMargin] above the navigation inset — and
 * shrinks *upwards*, the way iOS Safari's minimised bar stays down by
 * the gesture bar instead of floating into the middle of the space the
 * full bar used to occupy.
 *
 * The slot itself is untouched by any of this (stage 2/3's rule): it is
 * still 48 dp, so nothing outside it moves on a scroll. Only where the
 * capsule is painted inside it changes, and continuously — the offset is
 * a function of the same two fractions the heights are, so the collapse
 * interpolates height and anchor together rather than sliding the bar
 * down at some threshold.
 *
 * None of which the font scale touches: a taller compact capsule is a
 * smaller anchor by exactly as much (8 dp at scale 1, none at all past
 * the scale where the compact pill has filled the slot), so the drawn
 * bottom edge — and therefore [CapsuleBottomMargin]'s gap to the
 * navigation inset — is the slot's own at every scale.
 *
 * It is not zero at rest any more, and that is the split bar's one
 * change here: the resting surfaces are 44 dp of ink in a 48 dp slot, so
 * they carry 2 dp of anchor and sit on the slot's bottom edge like every
 * other state rather than filling it.
 */
internal fun capsuleBottomAnchor(
    collapse: Float,
    editProgress: Float,
    compactHeight: Dp = CapsuleCompactHeight,
): Dp = (
    capsuleSlotHeight(editProgress) -
        capsuleDrawnHeight(collapse, editProgress, compactHeight)
    ) / 2f

/**
 * The part of [capsuleBottomAnchor] the address field's *touch box*
 * follows, so the band stays over the shrinking capsule.
 *
 * The box is [addressFieldTouchHeight] tall in every state and must stay
 * inside the slot — a touch band hanging out of the chrome would be
 * taking presses from the page below it — so it can only travel as far
 * as the slot's own bottom edge allows: nothing at all in the resting
 * slot, where a 48 dp box in 48 dp has no slack under it. It doesn't
 * need any — the band already spans the whole slot, so it covers the
 * compact pill (the bottom 32 dp of it) edge to edge whatever the
 * capsule does, and the entire anchor is taken up in drawing by
 * [addressPillTopShift].
 *
 * At a font scale where the compact capsule is taller than 48 dp the band
 * grows with it rather than the coverage being lost ([addressFieldTouchHeight]),
 * so the arithmetic holds unchanged: the band's slack under it shrinks by
 * exactly what the capsule gained, and both edges still land on the
 * slot's bottom.
 */
internal fun addressFieldTouchShift(
    collapse: Float,
    editProgress: Float,
    compactHeight: Dp = CapsuleCompactHeight,
): Dp = capsuleBottomAnchor(collapse, editProgress, compactHeight)
    .coerceAtMost(
        (capsuleSlotHeight(editProgress) - addressFieldTouchHeight(compactHeight)) / 2f,
    )
    .coerceAtLeast(0.dp)

/**
 * The rest of [capsuleBottomAnchor]: how far below its touch box's
 * centre line the pill — and the label inside it — is drawn.
 *
 * Together with [addressFieldTouchShift] this adds up to exactly the
 * bar's own anchor, which is what keeps the field's surface, its label
 * and the two round buttons sharing one centre line in every state. It
 * is a *drawing* offset: the box that owns the taps is unmoved by it.
 */
internal fun addressPillTopShift(
    collapse: Float,
    editProgress: Float,
    compactHeight: Dp = CapsuleCompactHeight,
): Dp = capsuleBottomAnchor(collapse, editProgress, compactHeight) -
    addressFieldTouchShift(collapse, editProgress, compactHeight)

/**
 * How far below their slot's centre line the flanking controls are
 * *drawn* — the control-side counterpart of [addressPillTopShift].
 *
 * The controls' boxes fill the slot, so left alone they stay on the
 * slot's centre line while the field beside them rides down to the
 * slot's bottom edge: mid-collapse the still-visible Back and tabs
 * buttons float above the centre line of the field and label they belong
 * to, by up to the anchor's value at the fraction where the last of them
 * leaves. Giving them the field's own anchor keeps every drawn thing —
 * the three surfaces, the label and the icons — on one centre line at
 * every frame, not just at the settled ends.
 *
 * It is the *whole* anchor, not the split [addressFieldTouchShift] /
 * [addressPillTopShift] the pill takes, because it is a drawing offset
 * throughout ([Modifier.collapsingControl] applies it inside the same
 * layer that scales the control): the 48 dp touch boxes stay put in the
 * slot, and a control drawn at [capsulePillSlotScale] of its size is far
 * inside its own box anyway.
 */
internal fun capsuleControlTopShift(
    collapse: Float,
    editProgress: Float,
    compactHeight: Dp = CapsuleCompactHeight,
): Dp = capsuleBottomAnchor(collapse, editProgress, compactHeight)

/**
 * How much faster the flanking controls leave than the capsule changes
 * height. 1.6 puts them at zero width when the transition — *either*
 * transition — is about ⅔ of the way through, so the address pill
 * always has somewhere to grow into and neither the compact bar nor the
 * editor ever looks crowded mid-morph.
 */
private const val CONTROL_COLLAPSE_RATE = 1.6f

/**
 * Padding the compact capsule wraps its label with, per side — capsule
 * edge to the first glyph of the domain.
 *
 * It is the whole side inset, not a padding applied somewhere inside:
 * [compactCapsuleWidth] adds `2 ×` this to the measured label, and it is
 * what [capsuleLabelInset] settles at, so the settled compact capsule is
 * the label plus this much air on either side and the label needs no
 * ellipsis to fit.
 *
 * Twelve dp, down from the 20 dp the capsule carried while it was still
 * a 44 dp bar: on a pill that is only [CapsuleCompactHeight] tall, 20 dp
 * of side air is wider than the air above and below the label put
 * together, and the capsule reads as a lozenge with a domain rattling
 * around in it. Twice the vertical padding is as much as the shape can
 * take and still hug the label.
 */
internal val CapsuleCompactSidePadding = 12.dp

/**
 * Width of one round button's *layout* slot — Back on the leading edge,
 * the tab counter on the trailing one.
 *
 * Forty-eight dp: Material's minimum interactive size, which is what an
 * `IconButton` measures and therefore what every control on this bar is
 * laid out at. The circle drawn inside it is [CapsuleRestingHeight]
 * across, so the slot carries 2 dp of slack per side — the slack that
 * turns [CapsuleSideMargin] into the mockup's 16 dp of drawn margin and
 * [CapsuleSplitGap] into its 9 dp gap. Stated so that
 * [addressFieldRestingWidth] and [addressLabelMaxWidth] can describe the
 * field without measuring the row.
 */
internal val CapsuleControlSize = 48.dp

/**
 * Gap between a round button's layout slot and the address field's.
 *
 * Seven dp of layout, 9 dp of ink: the circle stands 2 dp inside its own
 * slot while the field's surface fills its box edge to edge, so the gap
 * the user sees is this plus that slack — the mockup's 26 px at 3×.
 */
internal val CapsuleSplitGap = 7.dp

/**
 * The field's own trailing slot — Clear / Stop / Reload, or empty. Always
 * reserved at this width whatever it currently holds, so nothing in the
 * field re-wraps when a load starts or finishes. The overflow menu on the
 * leading edge is laid out in a slot of exactly the same size, so the
 * domain between them is centred in the field rather than in whatever is
 * left over between two different insets.
 */
private val CapsuleTrailingSlotSize = 32.dp

/**
 * The glyph inside either of those slots. One size for both, so the
 * hamburger on the leading edge and the Reload / Stop mark on the
 * trailing one read as a pair either side of the domain — and it is
 * Reload's own size, unchanged, rather than a new one for both.
 */
private val CapsuleFieldIconSize = 18.dp

/**
 * Field-edge to control-slot inset, per side — the overflow menu on the
 * leading edge, Reload / Stop / Clear on the trailing one.
 *
 * Symmetric, because the split bar's field holds one control at each end
 * and the domain in the middle: 8 dp of inset around a 32 dp slot puts
 * each glyph's centre 24 dp in from its edge, which is where the mockup
 * has both of them (44 px of field padding at 3×, plus half a 62 px
 * icon).
 */
private val AddressPillControlInset = 8.dp

/** The protocol badge's own mark. */
internal val AddressPillBadgeSize = 16.dp

/** …and the air between it and the first glyph of the domain. */
internal val AddressPillBadgeGap = 8.dp

/**
 * What the protocol badge costs the domain when the origin has earned
 * one — the mark plus the air after it, and nothing when there is no
 * badge.
 */
private fun addressBadgeBlock(hasBadge: Boolean): Dp =
    if (hasBadge) AddressPillBadgeSize + AddressPillBadgeGap else 0.dp

/**
 * Width of the address field at rest: the slot, less the round buttons
 * beside it and the gaps in front of them.
 *
 * Back is the one thing on the bar that comes and goes, and when it is
 * gone the field takes its slot rather than the bar keeping a hole on the
 * left: a tab with no history gets a wider field, not a gap where a
 * disabled button would be, and no substitute control is invented to fill
 * it (Home lives in the overflow menu, which is now inside the field).
 */
internal fun addressFieldRestingWidth(restingWidth: Dp, canGoBack: Boolean): Dp = (
    restingWidth -
        (if (canGoBack) CapsuleControlSize + CapsuleSplitGap else 0.dp) -
        (CapsuleControlSize + CapsuleSplitGap)
    ).coerceAtLeast(0.dp)

/**
 * …and where the centre of that field sits relative to the slot's own
 * centre line.
 *
 * Zero while both round buttons are there — the field is the middle of a
 * symmetric row — and half a button-and-gap towards the leading edge when
 * Back is not, which is what "the field takes the Back button's slot"
 * means in geometry. Everything the field draws is positioned from this
 * one number: its surface, its two controls and the domain label.
 */
internal fun addressFieldCenterOffset(canGoBack: Boolean): Dp =
    ((if (canGoBack) CapsuleControlSize + CapsuleSplitGap else 0.dp) -
        (CapsuleControlSize + CapsuleSplitGap)) / 2f

/**
 * Width the address field's surface is actually drawn at, through both
 * morphs — the width counterpart of [capsuleDrawnHeight], read outwards
 * the same way.
 *
 * [editProgress] grows it from [addressFieldRestingWidth] to the whole
 * slot: the round buttons have gone by then, so the editor inherits the
 * width they were using (and the caller's narrower side margins on top of
 * that). [collapse] then shrinks whatever that gives towards the compact
 * pill, which is sized to the domain it wraps ([compactCapsuleWidth]).
 */
internal fun addressFieldDrawnWidth(
    collapse: Float,
    editProgress: Float,
    restingWidth: Dp,
    canGoBack: Boolean,
    compactWidth: Dp,
): Dp = capsuleDrawnWidth(
    collapse = collapse,
    restingWidth = lerp(
        addressFieldRestingWidth(restingWidth, canGoBack),
        restingWidth,
        editProgress.coerceIn(0f, 1f),
    ),
    compactWidth = compactWidth,
)

/**
 * …and the centre it is drawn about: its resting offset while it is a
 * field between two buttons, the slot's own centre once either morph has
 * taken the buttons away.
 */
internal fun addressFieldDrawnCenter(
    collapse: Float,
    editProgress: Float,
    canGoBack: Boolean,
): Dp = lerp(
    lerp(addressFieldCenterOffset(canGoBack), 0.dp, editProgress.coerceIn(0f, 1f)),
    0.dp,
    collapse.coerceIn(0f, 1f),
)

/**
 * Field-edge to content inset, per side — the protocol badge, the
 * placeholder and the two control slots.
 *
 * [AddressPillControlInset] at rest and while editing,
 * [CapsuleCompactSidePadding] when compact: the compact pill has no
 * controls left in it, and the only thing its inset still holds off is
 * the domain itself.
 *
 * The **domain label** is not one of the things this positions (#55): it
 * is drawn against the bar itself, from [addressLabelCenterOffset], so
 * that its x is one function of the collapse rather than this
 * interpolation composed with three others.
 */
internal fun capsuleLabelInset(
    collapse: Float,
    restingInset: Dp = AddressPillControlInset,
): Dp = lerp(restingInset, CapsuleCompactSidePadding, collapse.coerceIn(0f, 1f))

/**
 * Widest the domain label is ever laid out — what is left of the resting
 * *field* once everything beside the label has taken its share: the
 * overflow menu's slot and its inset on the leading side, the
 * always-reserved Reload / Stop slot and its inset on the trailing one,
 * and the protocol badge where the origin has earned one.
 *
 * The label is laid out against this **and nothing else**, at every
 * collapse fraction (#55). The middle ellipsis is therefore decided once
 * — off the resting width, exactly where it has always been decided —
 * rather than re-evaluated every frame against a box that is busy
 * narrowing, which is what made a long ENS name flicker between its full
 * and elided forms halfway through a collapse. The compact capsule is
 * then sized to whatever that one layout came out as, so a label the
 * capsule was built to hold cannot elide inside it either.
 *
 * The Back button's slot is reserved here **whether or not there is
 * history to pop**, which is the one place this parts company with
 * [addressFieldRestingWidth]. `canGoBack` is the only thing in the
 * resting row that flips while the label itself stays put — an in-page
 * `pushState` gives a tab its first history entry without changing the
 * domain — and the compact capsule is sized from this one layout, so
 * letting the threshold follow it would re-ellipsise a long name and
 * step the compact capsule by the Back button's 48 dp in a single
 * frame, with no animation and no Back button on screen to explain it.
 * Reserved either way, what the label *says* depends only on the domain
 * and the window; only where it starts from still follows the button,
 * and there the button is on screen taking the room. The cost is a
 * domain between the two thresholds eliding on a tab with no history —
 * a settled ellipsis is worth more than the last 48 dp.
 */
internal fun addressLabelMaxWidth(
    restingWidth: Dp,
    hasBadge: Boolean,
): Dp = (
    addressFieldRestingWidth(restingWidth, canGoBack = true) -
        (AddressPillControlInset + CapsuleTrailingSlotSize) * 2 -
        addressBadgeBlock(hasBadge)
    ).coerceAtLeast(0.dp)

/**
 * Where the domain settles at rest: the centre of what the field has
 * left between its two control slots.
 *
 * The slots are the same size on both sides ([CapsuleTrailingSlotSize]
 * at [AddressPillControlInset]), so they cancel and the label's resting
 * centre is the *field's* centre — which is the slot's own centre line
 * whenever Back is there, and half a button-and-gap to the leading side
 * when it is not. The protocol badge is the one thing that moves it: it
 * sits in front of the domain inside the same box, so the domain gives
 * up half the badge's block to keep the pair of them centred — and the
 * badge is then placed *from the domain* by
 * [addressBadgeCenterOffset], so the two are one group however this
 * moves.
 */
internal fun addressLabelRestingCenter(canGoBack: Boolean, hasBadge: Boolean): Dp =
    addressFieldCenterOffset(canGoBack) + addressBadgeBlock(hasBadge) / 2f

/**
 * Where the protocol badge's own 16 dp box sits, relative to the same
 * centre line the domain is placed against.
 *
 * The badge is a mark **on the domain**, so it is placed from the domain
 * rather than from an edge of the field: [AddressPillBadgeGap] of air in
 * front of the label's first glyph, wherever that glyph currently is.
 * Laid out instead as the first child of the field's content row — which
 * is where it used to be, back when the label was left-aligned in that
 * row too — it stays pinned to the field's leading edge while the label
 * it belongs to is centred in the pill, and the two end up half a bar
 * apart: a badge that reads as a second menu icon, and a domain pushed
 * off centre by [addressLabelRestingCenter] to pair with a mark that is
 * nowhere near it.
 *
 * [labelCenter] and [labelWidth] are the label's *drawn* centre and
 * drawn width ([addressLabelCenterOffset] and the measured layout at
 * [addressLabelScale]), so the badge tracks the domain through the
 * collapse instead of interpolating on a curve of its own — the same
 * rule #55 put the label itself under.
 */
internal fun addressBadgeCenterOffset(labelCenter: Dp, labelWidth: Dp): Dp =
    labelCenter - labelWidth / 2f - AddressPillBadgeGap - AddressPillBadgeSize / 2f

/**
 * **The** domain label's horizontal geometry: how far the centre of the
 * label's layout box sits from the bar's own centre line, at a given
 * collapse.
 *
 * One function of one fraction, which is the point (#55). Before this
 * the label's x was whatever fell out of composing an alignment bias
 * that slid −1 → 0, a start padding that interpolated on its own curve,
 * a Back button and a protocol badge that retreated on a third
 * ([CONTROL_COLLAPSE_RATE]), a gutter handover that only starts at ⅝ of
 * the collapse, and a text box that re-measured every frame — five
 * curves and a re-layout, which on the AVD put the label up to 9.5 dp
 * ahead of where a single shared fraction would have it, and then let it
 * fall back. Reduced to this lerp, the label's x is affine in the
 * collapse, so it cannot wander off whatever the field is doing.
 *
 * The compact pill is centred in the slot, so the slot's centre line is
 * the one both ends are measured against. The two ends:
 *
 *  - **resting** — [restingCenter], the centre of the field's own
 *    content box (see [addressLabelRestingCenter]).
 *  - **compact** — dead centre, because [compactCapsuleWidth] sizes the
 *    compact pill *from* this label: centre of the drawn pill minus half
 *    the measured label is the pill's own centre.
 */
internal fun addressLabelCenterOffset(collapse: Float, restingCenter: Dp): Dp =
    lerp(restingCenter, 0.dp, collapse.coerceIn(0f, 1f))

/**
 * The order an accessibility service reads the bar's children in.
 *
 * Stated rather than inherited, because neither the drawing order nor
 * the layout order is the reading order any more: the three surfaces are
 * siblings positioned by geometry rather than laid out left to right,
 * and the domain is composed last of all so that it paints over the
 * field (#55). A service left to the default order therefore announced
 * it **after** the trailing buttons — the bar's primary trust element
 * arriving at the end of the swipe, behind buttons that say nothing
 * about where the user is. (Confirmed on the AVD: the label was the last
 * node in the bar's `uiautomator` dump.)
 *
 * The order stated here is the split bar read left to right, which is
 * also the order the brief asks for: Back, the overflow menu, the
 * protocol badge, the domain it marks, the field they sit on, Reload /
 * Stop, tabs. The bar's slot is the traversal group that carries it.
 */
private const val CapsuleOrderBack = 0f
private const val CapsuleOrderOverflow = 1f
private const val CapsuleOrderBadge = 2f
private const val CapsuleOrderLabel = 3f
private const val CapsuleOrderField = 4f
private const val CapsuleOrderTrailing = 5f
private const val CapsuleOrderTabs = 6f

/**
 * Narrowest the compact capsule ever gets. Safari's minimised bar keeps
 * a recognisable pill even for a three-letter host, and 88 dp still
 * leaves the whole capsule answering the expand tap over an 88 × 48 dp
 * band — comfortably past Material's 48 dp target.
 *
 * Down from 120 dp with the padding: a floor set for a 20 dp-padded
 * capsule would now be doing the padding's old job — holding a short
 * host in the middle of air it didn't ask for — and a floor that is
 * routinely wider than the label plus its padding is not a floor, it is
 * a fixed width. At 12 dp of padding, 88 dp is reached by any host of
 * about nine 14 sp characters or more, so on real pages it is the label
 * that sizes the capsule and the floor only catches the stubs.
 */
internal val CapsuleCompactMinWidth = 88.dp

/**
 * Slack added to the wrapped compact width, on top of the padding.
 *
 * The label's own box is what is left of the capsule after three dp
 * paddings on each side, and *each* of those rounds to whole pixels at
 * layout time — as does the capsule width itself. Sized to the label
 * exactly, the box can therefore come out a pixel short of the text it
 * was measured from, and the label middle-ellipsises inside a capsule
 * built to fit it whole (seen on the AVD: `docs.s…rm.eth` in a 149 dp
 * capsule). Two dp is more than the rounding can ever lose and less than
 * a glyph.
 */
private val CapsuleCompactLabelSlack = 2.dp

/**
 * Font size of the domain label at rest: what it has always rendered
 * at. The label states no size of its own, so it inherits
 * `LocalTextStyle` — and `MaterialTheme` (via `MaterialExpressiveTheme`,
 * see `FreedomTheme`) provides that as `typography.bodyLarge`, i.e.
 * **16 sp**, not Compose's bare 14 sp default. Stated here rather than
 * inherited now that the other end of the interpolation is explicit —
 * and pinned by a test against `Typography().bodyLarge`, because the
 * resting label is the trust surface and may not shrink by accident.
 */
internal val AddressLabelRestingFontSize = 16.sp

/**
 * Font size of the domain label when fully compact — one step down the
 * M3 type scale (`bodyLarge` → `bodyMedium`). The compact bar says less
 * *and* says it smaller, which is the one thing stage 2 deliberately
 * didn't do; what makes it safe is that it says the same *string*
 * ([AddressLabel.resting], middle-ellipsised), so the trust surface is
 * unchanged — only its type size moves, and only by one step.
 */
internal val AddressLabelCompactFontSize = 14.sp

/**
 * Line height of the domain label at rest — `bodyLarge`'s own, i.e. what
 * the label has always been laid out with, stated here for the same
 * reason [AddressLabelRestingFontSize] is and pinned by the same test.
 */
internal val AddressLabelRestingLineHeight = 24.sp

/**
 * Line height of the domain label when fully compact: `bodyMedium`'s,
 * the other half of the one type step the compact label takes. Until
 * #47 only the font size stepped down and the line box stayed
 * `bodyLarge`'s 24 sp — which left the compact label sitting in a box
 * 4 sp taller than its own type, and made "the capsule is the label's
 * line box plus 6 dp" (see [CapsuleCompactHeight]) a claim about a
 * number nothing on screen actually used.
 */
internal val AddressLabelCompactLineHeight = 20.sp

/**
 * The compact type size as a fraction of the resting one — one M3 step,
 * 14 / 16.
 *
 * The step is taken as a *scale* rather than as a second text layout
 * (#55). Interpolating `fontSize` and `lineHeight` along the collapse
 * re-lays-out the label at a fractional sp on every frame, and text
 * layout snaps to whole-pixel glyph advances and integer font metrics:
 * the ink box therefore steps rather than glides — measured on the AVD
 * as a baseline that lands on four or five discrete rows and a label
 * width that goes *back up* mid-transition (227 → 226 → 227 px) as the
 * rounding flips. The glyphs are the same shapes at both ends either
 * way, so scaling one layout gets there continuously and settles on
 * exactly the same type size.
 */
internal val AddressLabelCompactScale: Float =
    AddressLabelCompactFontSize.value / AddressLabelRestingFontSize.value

/**
 * …and the same ratio read off the density actually in force, which is
 * the one the label is scaled by.
 *
 * `sp` is not linear in the font scale: Android 14 converts it through a
 * per-scale curve, so at `fontScale = 1.15` the theme's 16 sp is 18.1 dp
 * while its 14 sp is 16.4 dp — a ratio of 0.906, not 0.875. Taking the
 * ratio through [Density] (the same conversion [capsuleCompactHeight]
 * goes through, see #49/#50) is what keeps the *settled* compact label
 * exactly `bodyMedium`'s own size at every scale rather than only at
 * the default one.
 */
internal fun Density.addressLabelCompactScale(): Float =
    AddressLabelCompactFontSize.toPx() / AddressLabelRestingFontSize.toPx()

/**
 * How much of its resting size the domain label is drawn at — 1 at rest,
 * [compactScale] when fully compact, about the label's own centre (see
 * the `graphicsLayer` in [FloatingCapsule]).
 *
 * Scaling about the centre is what keeps the *baseline* honest: the
 * distance from the centre of a line box to its baseline is
 * `(ascent − descent) / 2`, which depends only on the type size, so a
 * uniformly scaled 16 sp layout puts its baseline exactly where a 14 sp
 * layout would — whatever line box either of them is sitting in.
 */
internal fun addressLabelScale(
    collapse: Float,
    compactScale: Float = AddressLabelCompactScale,
): Float = lerp(1f, compactScale, collapse.coerceIn(0f, 1f))

/**
 * Width the capsule settles at when fully compact: the label plus
 * [CapsuleCompactSidePadding] on either side, never narrower than
 * [CapsuleCompactMinWidth] and never wider than the resting width it
 * shrank from.
 *
 * [labelWidth] is the *measured* width of the compact label at its
 * compact type size (see [addressLabelFontSize]), so a short host gives
 * a short capsule and a long ENS name gives a longer one — up to the
 * resting width, past which the label middle-ellipsises exactly as it
 * does at rest. Plus [CapsuleCompactLabelSlack], so that a label the
 * capsule *was* sized to fit doesn't ellipsise on a rounded pixel.
 *
 * The floor yields to the ceiling: on a window too narrow for
 * [CapsuleCompactMinWidth] of capsule, the resting width wins, because a
 * "compact" bar wider than the bar it came from is not a collapse.
 */
internal fun compactCapsuleWidth(labelWidth: Dp, restingWidth: Dp): Dp {
    val wrapped = labelWidth.coerceAtLeast(0.dp) +
        CapsuleCompactSidePadding * 2 + CapsuleCompactLabelSlack
    val floor = CapsuleCompactMinWidth.coerceAtMost(restingWidth)
    return wrapped.coerceIn(floor, restingWidth.coerceAtLeast(floor))
}

/**
 * Width the capsule is actually drawn at — the width counterpart of
 * [capsuleDrawnHeight], and the *only* thing about the compact state
 * that is new geometry rather than the stage 2/3 model.
 *
 * It shrinks towards [compactWidth] inside a layout slot that keeps
 * spanning the full resting width, so this is still the same rule as the
 * height: **nothing outside the slot moves on scroll**, the capsule just
 * occupies less of it. The editing morph doesn't appear here at all —
 * the caller holds `collapse` at 0 whenever the field has focus (editing
 * wins over compact), so the editor always opens at the full resting
 * width, and the side margins it reaches into are the caller's.
 */
internal fun capsuleDrawnWidth(collapse: Float, restingWidth: Dp, compactWidth: Dp): Dp =
    lerp(
        restingWidth,
        compactWidth.coerceAtMost(restingWidth),
        collapse.coerceIn(0f, 1f),
    )

/**
 * What a tap on the capsule's domain label does.
 *
 * Safari's minimised bar takes two taps to edit, and so does this one:
 * the compact capsule is too small a target to hand straight to a
 * keyboard, and a user reaching for a bar that has shrunk out from under
 * their thumb is usually reaching for the *bar* (Back, tabs, the menu),
 * not for the address editor.
 */
internal enum class CapsuleTapAction {
    /**
     * Restore the resting capsule and stop there — no focus, no
     * keyboard, no suggestions panel. The controls come back with it, so
     * the second tap has a full-size target to land on.
     */
    Expand,

    /** Enter editing: focus the field, select all, raise the keyboard. */
    Edit,
}

/**
 * Past this much collapse a tap expands rather than edits. Half-way is
 * the honest place to put it: the fraction comes off a spring, and the
 * user is aiming at whatever the capsule looks like *now* — mostly
 * compact, they get the expand; mostly resting, they get the editor.
 */
internal const val CAPSULE_TAP_EXPAND_ABOVE = 0.5f

/**
 * Decide the two-step tap. [collapse] is the same fraction the geometry
 * is drawn from, so the decision matches what the user can see.
 *
 * [addressFocused] keeps the **editing wins over compact** rule true
 * here as well: a focused capsule is the editor, and a tap inside the
 * editor is never a request to expand it (the caller holds `collapse` at
 * 0 under focus, so this only matters if that guard is ever lost).
 */
internal fun capsuleTapAction(collapse: Float, addressFocused: Boolean): CapsuleTapAction = when {
    addressFocused -> CapsuleTapAction.Edit
    collapse > CAPSULE_TAP_EXPAND_ABOVE -> CapsuleTapAction.Expand
    else -> CapsuleTapAction.Edit
}

/**
 * How much of themselves the address pill's own two slots — the protocol
 * badge and the trailing Reload / Stop / Clear — still have.
 *
 * Driven by `collapse` *only*, not by `max(collapse, edit)` like the
 * flanking controls: the editor needs its trailing × and the badge keeps
 * vouching for the origin while the full URL is on screen, so inflating
 * the capsule must leave both alone. The rate is the flanking controls'
 * own ([Modifier.collapsingControl]), so everything the compact bar drops
 * leaves at one speed and they are all gone together at
 * `1 / CONTROL_COLLAPSE_RATE` of the collapse.
 */
internal fun capsulePillSlotScale(collapse: Float): Float =
    (1f - collapse * CONTROL_COLLAPSE_RATE).coerceIn(0f, 1f)

/**
 * Stroke of the load-progress trace that runs along the capsule's own
 * outline. Thin enough to read as a highlight on the edge rather than a
 * second border; drawn *over* the capsule, so it costs no layout height
 * and nothing shifts when a load starts or ends.
 */
private val CapsuleProgressStroke = 2.5.dp

/**
 * Fraction of each half-perimeter covered by the travelling segment
 * while the load is indeterminate (ENS resolve / gateway warm-up).
 */
private const val CAPSULE_SWEEP_WINDOW = 0.4f

/** One full lap of the indeterminate sweep, in milliseconds. */
private const val CAPSULE_SWEEP_PERIOD_MS = 1400

/**
 * Which control the address pill's trailing slot is showing. The slot
 * is a fixed-size square that is *always* reserved, so the pill's text
 * area never changes width — that is what keeps a load starting or
 * ending from shifting anything inside the capsule.
 */
internal enum class CapsuleTrailingControl {
    /** Nothing to offer (home tab with no address). */
    None,

    /** × — abandon what has been typed so far. */
    Clear,

    /**
     * × — abort the load that is currently running. Shares Clear's
     * glyph (as Safari's does) but never its moment: Clear only exists
     * while the user is mid-edit, Stop only while they are not, and
     * Stop wears the primary tint that matches the edge trace it turns
     * off.
     */
    Stop,

    /** ⟳ — reload the committed address. */
    Reload,
}

/**
 * Decide what the address pill's trailing slot shows.
 *
 * Priority is "what is the user doing right now" first: while they are
 * actively editing, the slot belongs to Clear even if the previous page
 * happens to still be loading behind the keyboard. Otherwise a live
 * load owns it (Stop), and a settled page gets Reload — one control,
 * two states, exactly as the brief's loading state asks.
 *
 * "The editor owns the slot" also covers the frame *after* a Clear: an
 * open editor on an empty buffer offers nothing, so the × the user just
 * tapped doesn't turn into a Stop under the same finger. A submitted
 * edit is different — the buffer still holds the URL that was sent — so
 * the post-submit Stop is unaffected.
 */
internal fun capsuleTrailingControl(
    addressFocused: Boolean,
    addressBarEdited: Boolean,
    editBufferEmpty: Boolean,
    loading: Boolean,
    canReload: Boolean,
): CapsuleTrailingControl = when {
    addressFocused && addressBarEdited && !editBufferEmpty -> CapsuleTrailingControl.Clear
    addressFocused && editBufferEmpty -> CapsuleTrailingControl.None
    loading -> CapsuleTrailingControl.Stop
    !addressFocused && canReload -> CapsuleTrailingControl.Reload
    else -> CapsuleTrailingControl.None
}

/**
 * True while this tab has a load worth showing progress for.
 *
 * `-1` is the idle sentinel: [BrowserState.progress] starts there, the
 * chrome client folds both ends of Chromium's 0..100 counter into it
 * (`onProgressChanged` maps 0 and 100 to `-1`), and `stopProgress()`
 * resets to it. `0` is *not* idle — `onPageStarted` writes it at
 * navigation commit, before the first percentage arrives — so the band
 * that means "busy" is `0..99`, the same predicate the wavy strip this
 * trace replaced used.
 */
internal fun isCapsuleLoading(state: BrowserState): Boolean =
    state.resolving || state.progress in 0..99

/**
 * Surface opacity on the **dark** scheme — [CAPSULE_ALPHA_LIGHT]'s own
 * number, stated separately because the two schemes take it against
 * different tones (see [capsuleFill]) and might one day part company
 * again.
 */
private const val CAPSULE_ALPHA_DARK = 0.90f

/**
 * Surface opacity on the **light** scheme, and — unchanged — on the dark
 * one: one number for both schemes and every API level (#63).
 *
 * Nine tenths is the line between the two things the surfaces are asked
 * for at once. A **faint tint** of the page through the chrome is wanted:
 * it is what says the bar floats over the page rather than replacing a
 * strip of it, and it is why this is an alpha at all rather than the
 * scheme's flat `surface`. **Readable page text** through it is not: a
 * bar the user has to look twice at to tell from the article behind it is
 * not a surface. A tenth of the page is a wash of its colour and nothing
 * legible — over a white article, over a dark hero image, and over the
 * high-contrast body text of either.
 */
private const val CAPSULE_ALPHA_LIGHT = 0.90f

/**
 * Contrast of the hairline around each surface, per scheme: a 6 %
 * `onSurface` line on light, 10 % on dark. Enough to separate a
 * translucent surface from a page of the same tone, far too little to
 * read as a border in its own right.
 */
private const val CAPSULE_BORDER_ALPHA_LIGHT = 0.06f
private const val CAPSULE_BORDER_ALPHA_DARK = 0.10f

/**
 * Shadow under each surface, per scheme. Just enough to lift it off the
 * page without the "heavy shadow" the brief rules out — but on the light
 * scheme the shadow is doing most of the lifting on its own (a pale
 * surface on a pale page has little tonal separation to offer), whereas
 * on the dark one a black shadow over dark content is nearly invisible
 * and the fill does the work.
 */
private val CapsuleShadowDark = 3.dp
private val CapsuleShadowLight = 6.dp

/**
 * Fill shared by all three of the bar's surfaces.
 *
 * One style, one colour: the split bar has no tray and no pill inside a
 * tray, so there is nothing left for a second tone to distinguish — the
 * round Back button, the field and the round tab counter are the same
 * translucent surface, and the compact pill is that same field shrunk
 * rather than a fourth thing. (Which is also why this no longer takes a
 * collapse fraction: #46's fill morph existed to dissolve the outer tray
 * into the pill as the bar compacted, and there is no outer tray to
 * dissolve.)
 *
 * `surface` on light is the scheme's white; `surfaceContainer` on dark is
 * the tone the capsule has always been. Both at the same alpha
 * ([CAPSULE_ALPHA_LIGHT] / [CAPSULE_ALPHA_DARK]) on every device — the
 * fill is the whole surface now (#63), so there is nothing left for it to
 * depend on but the scheme it is painting with.
 */
internal fun capsuleFill(colors: ColorScheme): Color =
    if (colors.isLight) {
        colors.surface.copy(alpha = CAPSULE_ALPHA_LIGHT)
    } else {
        colors.surfaceContainer.copy(alpha = CAPSULE_ALPHA_DARK)
    }

/** The hairline around that fill — see [CAPSULE_BORDER_ALPHA_LIGHT]. */
internal fun capsuleBorder(colors: ColorScheme): Color = colors.onSurface.copy(
    alpha = if (colors.isLight) CAPSULE_BORDER_ALPHA_LIGHT else CAPSULE_BORDER_ALPHA_DARK,
)

/**
 * One of the bar's three floating surfaces: a translucent fill at
 * [capsuleFill]'s alpha, a hairline of contrast around it, and the soft
 * elevation shadow the capsule has always carried.
 *
 * The fill is the whole surface: a colour with an alpha, read from the
 * scheme and from nothing else. It does not sample the page, record it or
 * hold a layer of it, so a resting bar costs one fill per surface per draw
 * and invalidates nothing between draws (#63).
 *
 * Background only — the control that lives on it is composed over it, and
 * the shadow and the hairline are drawn outside the shape clip so the fill
 * cannot cover either.
 */
@Composable
private fun CapsuleSurface(
    shape: Shape,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val fill = capsuleFill(colors)
    val border = capsuleBorder(colors)
    val elevation = if (colors.isLight) CapsuleShadowLight else CapsuleShadowDark

    Box(
        modifier = modifier
            .border(Dp.Hairline, border, shape)
            .shadow(elevation, shape),
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(shape)
                .background(fill),
        )
    }
}

/**
 * The browser chrome: Safari's split bottom bar, adapted to Android —
 * three floating surfaces on one line over an edge-to-edge page, rather
 * than the single capsule that used to hold every control.
 *
 * Left to right: a **round Back button** (only while there is history to
 * pop — when there isn't, the field widens into its slot rather than the
 * bar keeping a hole or inventing a Home button to fill it), the
 * **address field** with the overflow menu on its leading edge, the
 * domain in the middle and Reload / Stop on its trailing edge, and a
 * **round tab counter**. Home lives in the overflow menu, as it has since
 * the chrome moved to the bottom.
 *
 * All three wear one style ([CapsuleSurface]): a translucent fill at
 * [capsuleFill]'s alpha, a hairline of [capsuleBorder] around it and the
 * low shadow the capsule has always had. The page tints faintly through
 * the fill — a tenth of it, so the bar reads as floating over the page
 * rather than replacing a strip of it — and nothing on the page is
 * legible through it. There is no tray and no second, darker pill inside
 * one — the field *is* a surface, and the compact bar is that same field
 * shrunk rather than a different shape with a different fill.
 *
 * The bar has exactly three heights and one model that produces them
 * (see [capsuleDrawnHeight]): 32 dp **compact**, 44 dp **resting**,
 * 64 dp **editing**, all of them drawn inside a 48 dp slot that gives
 * every control its full Material touch target. The compact one is the
 * only one the system font scale moves — it is the domain label's line
 * box plus 6 dp of air per side, so it grows with the type it hugs (see
 * [capsuleCompactHeight]); 32 dp is its value at `fontScale == 1`. Two
 * caller-supplied fractions drive all of it and nothing else does:
 *
 * **[collapseFraction]** (0 resting, 1 compact) is the compact-on-scroll
 * state. The field shrinks *inside* a layout slot that keeps its resting
 * height, so a flick moves the bar and nothing else on screen — and it
 * shrinks *upwards* off a fixed bottom edge (see [capsuleBottomAnchor]),
 * so the minimised bar stays down by the gesture bar rather than
 * drifting into the middle of the slot. The two round buttons go with
 * the field's own controls, leaving the domain pill alone on screen.
 *
 * **[editProgress]** (0 at rest, 1 in the editor) is the editing morph —
 * the same field inflating, not a second screen. It grows the slot as
 * well as the field, takes the round buttons away, and reaches towards
 * the screen edges through the caller's side margins.
 *
 * They are one axis, not two: the caller holds [collapseFraction] at 0
 * whenever the field has focus, so editing always wins over compact, and
 * both transitions take the round buttons and the field's own controls
 * away through the *same* mechanism ([Modifier.collapsingControl]) —
 * each shrinks whole into the edge it retreats to, width and scale
 * together, never a cross-fade and never a clipped fragment.
 *
 * **Loading** is drawn *on* the field: a thin trace runs along its
 * outline from the bottom centre out to both sides (see
 * [CapsuleEdgeTrace]). It is measured from whatever outline the field
 * currently has, so it traces the compact and editing shapes just as
 * readily as the resting one. The field's trailing slot turns into a
 * Stop control. Both are overlays on geometry that is already settled,
 * so a load starting or ending moves nothing.
 *
 * The caller owns the layout slot (insets, IME padding, max width); this
 * composable only fills whatever width it is given.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun BottomToolbar(
    state: BrowserState,
    tabCount: Int,
    nodeInfo: NodeInfo,
    isBookmarked: Boolean,
    addressFocused: Boolean,
    addressBarEdited: Boolean,
    editProgress: Float,
    collapseFraction: Float,
    onAddressFocusChanged: (Boolean) -> Unit,
    onAddressEditedChanged: (Boolean) -> Unit,
    onAddressQueryChanged: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onHome: () -> Unit,
    onToggleBookmark: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenNode: () -> Unit,
    onOpenTabs: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onReload: () -> Unit,
    onStop: () -> Unit,
    onNewTab: () -> Unit,
    onExpandCapsule: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Clamp rather than trust the caller: both fractions are driven by
    // springs, and the expressive spatial springs overshoot slightly at
    // both ends.
    val collapse = collapseFraction.coerceIn(0f, 1f)
    val edit = editProgress.coerceIn(0f, 1f)
    val density = LocalDensity.current
    // The compact end of every height below, read off the *scaled* line
    // box the label is laid out in rather than off a 32 dp constant that
    // only holds at `fontScale == 1`: 20 sp of line box is 20 dp at the
    // default scale, 23.6 dp at 1.3 and 34 dp at the 2.0 maximum, and the
    // capsule is that plus 6 dp of air above and below at all of them
    // (#49). Converted through the density — the same conversion the
    // label's own `lineHeight` goes through — so Android 14's non-linear
    // font scaling is followed rather than assumed linear, and the shape
    // and the type can't come out of different arithmetic. See
    // [capsuleCompactHeight].
    val compactLineBox = with(density) {
        compactLabelLineBox(AddressLabelCompactLineHeight.toDp())
    }
    val compactHeight = compactLineBox + CapsuleCompactVerticalPadding * 2
    val slotHeight = capsuleSlotHeight(edit)
    val drawnHeight = capsuleDrawnHeight(collapse, edit, compactHeight)
    // Everything the bar draws is centred in the slot and then pushed
    // down by this — 2 dp at rest (44 dp of ink in a 48 dp slot) and
    // (48 − 32) / 2 when fully compact at the default font scale: the
    // bar's bottom edge never moves, at any scale. See
    // [capsuleBottomAnchor].
    val bottomAnchor = capsuleBottomAnchor(collapse, edit, compactHeight)

    // How much of their resting size the round buttons still have — one
    // number for both transitions, because they leave the same way in
    // both. Taking the max rather than tracking the two separately is
    // what makes tapping a compact bar read as one continuous move:
    // `collapse` springs back to 0 while `edit` comes up, and the
    // buttons stay gone across the handover instead of flashing in
    // between the two states.
    val controlScale =
        (1f - max(collapse, edit) * CONTROL_COLLAPSE_RATE).coerceIn(0f, 1f)
    // And how far down they are drawn while they are still on screen:
    // the field's own anchor, so a button mid-collapse sits on the
    // field's centre line rather than on the slot's. See
    // [capsuleControlTopShift].
    val controlTopShift = capsuleControlTopShift(collapse, edit, compactHeight)
    // …and how much the things *inside* the field still have. The badge
    // is drawn out here beside the label rather than in the field's row
    // (see [addressBadgeCenterOffset]), so it takes this from the same
    // function its neighbours in that row take it from.
    val pillSlotScale = capsulePillSlotScale(collapse)

    // Derived, not read straight: `isCapsuleLoading` looks at
    // `state.progress`, and reading that here would put every single
    // progress tick in this composable's recompose scope. Wrapped in a
    // `derivedStateOf`, composition is only invalidated when the tick
    // crosses the loading/idle boundary; the percentage itself is read
    // in the draw phase below, where it invalidates drawing only.
    val loading by remember(state) { derivedStateOf { isCapsuleLoading(state) } }
    // The travelling segment only exists while we have no percentage to
    // show; composing the infinite transition conditionally keeps an
    // idle capsule off the animation clock entirely.
    val sweep: State<Float>? = if (state.resolving) rememberCapsuleSweep() else null
    val progressColor = MaterialTheme.colorScheme.primary
    val progressStrokePx = with(LocalDensity.current) { CapsuleProgressStroke.toPx() }

    // The label the capsule shows while it isn't being edited — the
    // committed address reduced to a domain, which is also the string
    // the compact capsule sizes itself to. Computed here rather than
    // inside [AddressField] because the width has to be known one level
    // up, where the capsule is laid out.
    val restingLabel = remember(state.addressBarText) {
        AddressLabel.resting(state.addressBarText)
    }
    // The one style the label is ever laid out in — the resting one. The
    // compact step is a scale about the label's centre, not a second
    // layout, so this is the style at every collapse fraction and the
    // measurement below is the width the label keeps throughout (#55).
    val labelStyle = LocalTextStyle.current
    val restingLabelStyle = remember(labelStyle) {
        labelStyle.copy(
            fontSize = AddressLabelRestingFontSize,
            lineHeight = AddressLabelRestingLineHeight,
            fontWeight = FontWeight.Medium,
        )
    }
    val textMeasurer = rememberTextMeasurer()
    // Where the domain settles at rest. Both of the things that move it
    // are known here: the Back button (which decides where the field is)
    // and the protocol badge (which shares the field's content box with
    // the label). See [addressLabelRestingCenter].
    val badge = protocolBadgeFor(state)
    val labelRestingCenter = addressLabelRestingCenter(state.canGoBack, badge != null)
    // How far the settled compact label is scaled down from the settled
    // resting one, read off this density rather than assumed to be 14/16
    // (see [Density.addressLabelCompactScale]).
    val labelCompactScale = with(density) { addressLabelCompactScale() }

    // Each control states its own ink (`onSurface`) rather than
    // inheriting it: the surfaces are drawn by us now, so there is no
    // [Surface] in the tree to provide `LocalContentColor` for them.
    val contentColor = MaterialTheme.colorScheme.onSurface

    BoxWithConstraints(
        // The slot the bar lives in — one row for all three surfaces,
        // each positioned in it by geometry rather than laid out in
        // sequence. Compacting shrinks the field *inside* it — in width
        // and height, and towards its bottom edge — which is what keeps
        // the page, the snackbar and the IME reserve from moving when the
        // bar collapses, and what lets every control keep its 48 dp touch
        // target while the ink beside it is only 32 dp tall. Editing is
        // the one transition that grows it (see [capsuleSlotHeight]).
        modifier = modifier
            .fillMaxWidth()
            .height(slotHeight)
            // One bar, read in the order it is drawn on screen rather
            // than in the order it happens to be composed — see
            // [CapsuleOrderLabel].
            .semantics { isTraversalGroup = true },
        contentAlignment = Alignment.Center,
    ) {
        // The resting width is whatever the caller's slot offers; the
        // compact one is the label plus its padding. Centred in the slot
        // by the Box, so the pill stays bottom-centred on screen as it
        // narrows.
        val restingWidth = maxWidth
        // The one width the label is ever laid out against, and the one
        // layout that comes out of it — the middle ellipsis is settled
        // here, at the resting width, and never re-asked as the capsule
        // narrows (see [addressLabelMaxWidth]).
        val labelMaxWidth = addressLabelMaxWidth(
            restingWidth = restingWidth,
            hasBadge = badge != null,
        )
        val labelWidth = remember(restingLabel, restingLabelStyle, labelMaxWidth, density) {
            if (restingLabel.isEmpty()) 0.dp
            else with(density) {
                textMeasurer.measure(
                    text = restingLabel,
                    style = restingLabelStyle,
                    maxLines = 1,
                    softWrap = false,
                    constraints = Constraints(maxWidth = labelMaxWidth.roundToPx()),
                ).size.width.toDp()
            }
        }
        // …and what that same layout covers once it has been scaled down
        // to the compact type size, which is what the compact capsule is
        // built to wrap. Ceiled to whole dp: the width this feeds is
        // turned back into px at layout time, and losing a fraction
        // there would leave the capsule a pixel short of the label it
        // was measured from.
        val compactLabelWidth = ceil(labelWidth.value * labelCompactScale).dp
        // The field's drawn rectangle — its width through both morphs and
        // the centre it is drawn about. Every part of the field is placed
        // from these two numbers (the surface, the touch band, the
        // contents, the load trace on its edge), so they cannot drift
        // apart the way a drawn shape and a laid-out row can.
        val fieldWidth = addressFieldDrawnWidth(
            collapse = collapse,
            editProgress = edit,
            restingWidth = restingWidth,
            canGoBack = state.canGoBack,
            compactWidth = compactCapsuleWidth(
                labelWidth = compactLabelWidth,
                restingWidth = addressFieldRestingWidth(restingWidth, state.canGoBack),
            ),
        )
        val fieldCenter = addressFieldDrawnCenter(collapse, edit, state.canGoBack)
        // Every x here is measured from the bar's leading edge, and
        // neither an offset nor a layer translation is mirrored for us the
        // way an alignment bias or a `start` padding would be.
        val direction =
            if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f

        // Back — its own round surface on the leading edge, shown only
        // while there is history to pop. When there isn't, nothing takes
        // its place: the field simply widens into the slot (see
        // [addressFieldRestingWidth]).
        //
        // Fully-collapsed controls are not composed at all, so a
        // zero-width Back button can never take a tap meant for the page
        // or the domain.
        if (state.canGoBack && controlScale > 0f) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .collapsingControl(
                        controlScale,
                        towardsStart = true,
                        topShift = controlTopShift,
                        // The circle's shadow belongs on the page, not
                        // squared off at the slot's edge — see
                        // [Modifier.collapsingControl].
                        clip = false,
                    )
                    .semantics { traversalIndex = CapsuleOrderBack },
            ) {
                CapsuleRoundButton {
                    // Expressive shape variants: the icon buttons morph
                    // from round to a squarer pressed shape on touch.
                    // Purely visual — the 48 dp hit target and click
                    // handlers are unchanged.
                    IconButton(onClick = onBack, shapes = IconButtonDefaults.shapes()) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = contentColor,
                        )
                    }
                }
            }
        }

        // The tab counter — the other round surface, on the trailing
        // edge. It leaves with Back in both morphs.
        if (controlScale > 0f) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .collapsingControl(
                        controlScale,
                        towardsStart = false,
                        topShift = controlTopShift,
                        clip = false,
                    )
                    .semantics { traversalIndex = CapsuleOrderTabs },
            ) {
                CapsuleRoundButton {
                    TabsCountButton(count = tabCount, onClick = onOpenTabs)
                }
            }
        }

        AddressField(
            state = state,
            restingLabel = restingLabel,
            addressFocused = addressFocused,
            addressBarEdited = addressBarEdited,
            collapse = collapse,
            editProgress = edit,
            compactHeight = compactHeight,
            loading = loading,
            onAddressFocusChanged = onAddressFocusChanged,
            onAddressEditedChanged = onAddressEditedChanged,
            onAddressQueryChanged = onAddressQueryChanged,
            onSubmit = onSubmit,
            onReload = onReload,
            onStop = onStop,
            onExpandCapsule = onExpandCapsule,
            // The overflow menu moved off the bar's trailing end and into
            // the field's leading edge, where Safari keeps it. Handed in
            // as a slot so the field stays a field rather than growing
            // twelve callbacks it never uses.
            menu = {
                OverflowMenuButton(
                    state = state,
                    nodeInfo = nodeInfo,
                    isBookmarked = isBookmarked,
                    onForward = onForward,
                    onHome = onHome,
                    onToggleBookmark = onToggleBookmark,
                    onOpenSettings = onOpenSettings,
                    onOpenNode = onOpenNode,
                    onOpenHistory = onOpenHistory,
                    onOpenBookmarks = onOpenBookmarks,
                    onReload = onReload,
                    onNewTab = onNewTab,
                )
            },
            modifier = Modifier
                .align(Alignment.Center)
                .width(fieldWidth)
                .offset { IntOffset((fieldCenter.toPx() * direction).roundToInt(), 0) }
                // The touch band follows the field down as far as the
                // slot lets it, so a compact pill sitting on the slot's
                // bottom edge is covered edge to edge by the band that
                // answers the two-step tap — see [addressFieldTouchShift].
                .offset(y = addressFieldTouchShift(collapse, edit, compactHeight))
                .semantics { traversalIndex = CapsuleOrderField },
        )

        // The domain label.
        //
        // A sibling of the field rather than a child of its content row
        // (#55), because that is what lets it have *one* geometry: the
        // compact pill is centred in this slot, so a child centred in the
        // same slot and pushed by [addressLabelCenterOffset] is placed
        // against the bar's own centre line, and nothing the Back button,
        // the badge or the two control slots do on the way can move it.
        // Vertically it takes the field's own bottom anchor, the same
        // number the surfaces and the round buttons take.
        //
        // It is drawn, not laid out: no pointer input, so it is not a
        // hit target and the tap still lands on the text field beneath
        // it — exactly as it did while it was drawn over the field
        // inside the pill.
        //
        // The label is derived from the tab's committed address, never
        // from the edit buffer: a bold bare domain is the bar asserting
        // "this is the site you are on", and only a loaded (or
        // just-submitted) URL earns that. The moment the field takes
        // focus it gives way to the full URL in the editor.
        if (!addressFocused && restingLabel.isNotEmpty()) {
            val labelOffset = addressLabelCenterOffset(collapse, labelRestingCenter)
            // The protocol badge, beside the domain it vouches for — a
            // sibling of the label for exactly the reason the label is a
            // sibling of the field (#55). Both are placed against the
            // bar's own centre line, the badge from the label's drawn
            // edge, so the mark and the name stay one group whatever the
            // Back button, the two control slots or the collapse do; in
            // the field's content row it would sit at the field's leading
            // edge, half a bar from the domain it marks (see
            // [addressBadgeCenterOffset]).
            //
            // Drawn, not laid out, like the label: no pointer input, so
            // the tap still lands on the field beneath it. While the
            // field has focus the badge goes back into the row, where the
            // full URL it marks starts at the leading edge — see
            // [AddressField].
            if (badge != null && pillSlotScale > 0f) {
                val labelDrawnWidth = labelWidth * addressLabelScale(collapse, labelCompactScale)
                val badgeOffset = addressBadgeCenterOffset(
                    labelCenter = labelOffset,
                    labelWidth = labelDrawnWidth,
                )
                Image(
                    painter = painterResource(badge.drawableRes),
                    contentDescription = badge.contentDescription,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(AddressPillBadgeSize)
                        .graphicsLayer {
                            // It leaves with everything else the compact
                            // pill drops ([capsulePillSlotScale]),
                            // retreating into the first glyph of the
                            // domain rather than shrinking away from it:
                            // the pivot is the edge that faces the label,
                            // so the 8 dp of air between them holds all
                            // the way out.
                            scaleX = pillSlotScale
                            scaleY = pillSlotScale
                            transformOrigin =
                                TransformOrigin(if (direction > 0f) 1f else 0f, 0.5f)
                            translationX = badgeOffset.toPx() * direction
                            translationY = bottomAnchor.toPx()
                        }
                        .semantics { traversalIndex = CapsuleOrderBadge },
                )
            }
            Text(
                text = restingLabel,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
                // One layout, at the resting type size, at every
                // fraction of the collapse — the step down to
                // `bodyMedium` is the scale below, not a re-layout. See
                // [AddressLabelCompactScale].
                fontSize = AddressLabelRestingFontSize,
                lineHeight = AddressLabelRestingLineHeight,
                maxLines = 1,
                softWrap = false,
                // Middle, not tail: a name too long for the pill is
                // almost always an ENS subname chain, and its *tail* is
                // the part that says who is being trusted.
                // `long.prefix.attacker.eth` tail-ellipsised reads
                // `long.prefix…`, which is exactly the half an attacker
                // gets to choose; eliding the middle keeps the parent
                // name and TLD on screen.
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier
                    .align(Alignment.Center)
                    .widthIn(max = labelMaxWidth)
                    // Both the travel and the type step live in the
                    // layer, so they are sub-pixel and the label is
                    // never re-laid-out mid-transition. The pivot is the
                    // label's own centre, which is the point
                    // [addressLabelCenterOffset] positions.
                    .graphicsLayer {
                        val scale = addressLabelScale(collapse, labelCompactScale)
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(0.5f, 0.5f)
                        translationX = labelOffset.toPx() * direction
                        translationY = bottomAnchor.toPx()
                    }
                    // Painted last, read second — the domain is the one
                    // thing in the bar a screen-reader user is here for
                    // (see [CapsuleOrderLabel]).
                    //
                    // *Inside* the layer, not outside it: a semantics
                    // node reports the bounds of the coordinator it sits
                    // on, so hung in front of the `graphicsLayer` it
                    // would hand the accessibility focus rectangle the
                    // label's untranslated layout box — dead centre of
                    // the slot — instead of the pixels the label is
                    // actually drawn on (seen in the AVD's `uiautomator`
                    // dump as the label's bounds jumping 89 px right of
                    // its ink).
                    .semantics { traversalIndex = CapsuleOrderLabel },
            )
        }

        // Load progress, stroked along the field's *current* outline.
        //
        // Its own sibling, sized and positioned from the same two numbers
        // the field's surface is ([addressFieldDrawnWidth] /
        // [addressFieldDrawnCenter]), and last in the Box so it lands
        // over the finished bar — the trace belongs on the edge, not
        // half-swallowed by a shape clip. It carries no pointer input, so
        // it is not a hit target and the controls underneath it still
        // take every tap.
        //
        // Because it is measured from `size`, the trace re-traces
        // whatever shape the field currently is: 32 dp compact, 44 dp
        // at rest, 64 dp editing, and every frame in between — and it
        // carries the field's own bottom anchor, so it stays on the
        // outline rather than beside it.
        //
        // `state.progress` is read inside the draw lambda (and only as a
        // boundary, through `loading` above), so a ticking load
        // invalidates drawing only — never layout or composition.
        if (loading) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .width(fieldWidth)
                    .height(drawnHeight)
                    .offset { IntOffset((fieldCenter.toPx() * direction).roundToInt(), 0) }
                    .offset(y = bottomAnchor)
                    .drawWithCache {
                        // The outline only changes when the capsule's
                        // size does, so it is traced and measured here in
                        // the cache block — a frame of the sweep then
                        // costs one `getSegment` per half, not two fresh
                        // paths and a fresh PathMeasure.
                        val trace = CapsuleEdgeTrace(size, progressStrokePx)
                        onDrawBehind {
                            val start: Float
                            val end: Float
                            if (sweep != null) {
                                val head = sweep.value * (1f + CAPSULE_SWEEP_WINDOW)
                                start = (head - CAPSULE_SWEEP_WINDOW).coerceIn(0f, 1f)
                                end = head.coerceIn(0f, 1f)
                            } else {
                                start = 0f
                                end = state.progress.coerceIn(0, 100) / 100f
                            }
                            trace.draw(this, start, end, progressColor)
                        }
                    },
            )
        }
    }
}

/**
 * One of the bar's two round buttons — a 44 dp circle of [CapsuleSurface]
 * with a stock 48 dp [IconButton] centred on it.
 *
 * The slot is the button's, not the circle's ([CapsuleControlSize]): the
 * ink is 2 dp inside it on every side, which is the slack that turns the
 * caller's margins into the mockup's, and the control keeps Material's
 * full target while the surface stays the same 44 dp the field is.
 */
@Composable
private fun CapsuleRoundButton(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier.size(CapsuleControlSize),
        contentAlignment = Alignment.Center,
    ) {
        CapsuleSurface(
            shape = CircleShape,
            modifier = Modifier.size(CapsuleRestingHeight),
        )
        content()
    }
}

/**
 * Take a flanking control away by interpolating its geometry: the slot
 * it occupies narrows towards zero while the control scales down about
 * the edge it collapses into. No cross-fade — the brief asks for
 * position/size interpolation over opacity, and a control that shrinks
 * into the capsule's edge keeps saying where it went.
 *
 * This is the single mechanism behind *both* morphs: compacting on
 * scroll and inflating into the editor take the same controls away the
 * same way, so there is one thing to reason about (and one thing that
 * can be wrong) rather than two.
 *
 * [visible] is 1 at rest and 0 when fully collapsed;
 * [towardsStart] picks the edge the control retreats to (leading
 * controls collapse left, trailing controls collapse right);
 * [topShift] rides the control down onto the capsule's centre line as
 * the capsule drops onto the slot's bottom edge (see
 * [capsuleControlTopShift]) — drawing only, so the control's touch box
 * stays where the slot put it.
 *
 * [clip] is what keeps a control's *overflow* inside the narrowing slot:
 * the field's own slots are 32 dp boxes holding 48 dp icon buttons (see
 * [Modifier.capsuleFieldSlot]), so without it a ripple would paint over
 * the domain beside it. The two round buttons have no overflow to
 * contain — their ink is 44 dp inside a 48 dp box — but they do have an
 * elevation shadow, and a clip here cuts it off square at the slot's
 * edge: a straight line down the outboard side of the button and another
 * under it, where the single capsule's shadow used to fall softly onto
 * the page. They pass `false`, and the shadow leaves the box the way it
 * always did — the [CapsuleBottomMargin] below the bar and the page
 * beside it are the room it needs. Nothing else escapes: the scale below
 * pivots on the edge the slot narrows towards, so the *control* still
 * fills its slot exactly at every fraction of both morphs.
 *
 * Order matters: the scale has to be applied *inside* the narrowing
 * slot, so `layout` (outer) wraps `graphicsLayer` (inner). Written the
 * other way round the layer would scale the already-narrowed slot a
 * second time, and the control would paint at `visible²` of its slot —
 * a cropped edge fragment beside an empty gap — instead of the whole
 * control shrinking to fill the slot.
 */
private fun Modifier.collapsingControl(
    visible: Float,
    towardsStart: Boolean,
    topShift: Dp = 0.dp,
    clip: Boolean = true,
): Modifier {
    if (visible >= 1f && topShift == 0.dp) return this
    return this
        .layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            val width = (placeable.width * visible).roundToInt()
            layout(width, placeable.height) {
                // Anchored to the edge it collapses into, which is the
                // same edge the scale below pivots on — so the drawn
                // control exactly fills the narrowing slot at every
                // fraction, and nothing of it lands outside.
                placeable.place(if (towardsStart) 0 else width - placeable.width, 0)
            }
        }
        .graphicsLayer {
            scaleX = visible
            scaleY = visible
            // Applied outside the scale by the layer's own matrix, so
            // the control travels the anchor's full distance whatever
            // it has shrunk to.
            translationY = topShift.toPx()
            transformOrigin = TransformOrigin(if (towardsStart) 0f else 1f, 0.5f)
            this.clip = clip
        }
}

/**
 * One of the address field's two control slots — the overflow menu on the
 * leading edge, Clear / Stop / Reload on the trailing one — as a box of
 * its own, retreating into the edge it belongs to with everything else
 * the compact pill drops ([capsulePillSlotScale]).
 *
 * Stated once because each slot is laid out twice: the content row
 * reserves it (so the URL beside it never re-wraps when Reload becomes
 * Stop, and so the reservation is exactly as wide as the thing filling
 * it), and the control itself is composed over the field's gesture
 * surface, which has to be *above* the text field and so cannot be
 * inside the row. See [AddressField].
 */
private fun Modifier.capsuleFieldSlot(slotScale: Float, towardsStart: Boolean): Modifier =
    collapsingControl(slotScale, towardsStart).size(CapsuleTrailingSlotSize)

/**
 * Phase of the indeterminate edge sweep, 0..1 per lap. Kept in its own
 * composable so the infinite transition is only created while a tab is
 * actually resolving.
 */
@Composable
private fun rememberCapsuleSweep(): State<Float> {
    val transition = rememberInfiniteTransition(label = "capsuleSweep")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(CAPSULE_SWEEP_PERIOD_MS, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "capsuleSweepPhase",
    )
}

/**
 * The capsule's own outline, pre-split and pre-measured, ready to be
 * traced with the load progress.
 *
 * The perimeter is split into two halves that both start at the bottom
 * centre and end at the top centre, so progress opens outwards from
 * under the domain and closes at the top — symmetric, and unambiguous
 * about where 0 % and 100 % are.
 *
 * The geometry depends on nothing but the capsule's [size] and the
 * stroke width, so one of these is built per size in
 * [Modifier.drawWithCache]'s cache block and reused for every frame of
 * the load. The paths are inset by half the stroke, so the trace's outer
 * edge lands exactly on the capsule's edge, and they are drawn over the
 * finished capsule, so nothing about them participates in layout.
 */
private class CapsuleEdgeTrace(size: Size, strokeWidth: Float) {
    private val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)

    // Held for the lifetime of the trace: a [PathMeasure] measures the
    // path it was given rather than a copy of it.
    private val halves: List<Path> = capsuleHalves(size, strokeWidth)
    private val measures: List<PathMeasure> =
        halves.map { half -> PathMeasure().apply { setPath(half, false) } }
    private val lengths: List<Float> = measures.map { it.length }

    // Rewritten in place each frame; `getSegment` appends, so it is
    // reset first.
    private val segment = Path()

    /**
     * Stroke the [start]..[end] fraction of each half onto [scope]: a
     * determinate load draws `0 → progress`, an indeterminate one a
     * short window travelling from bottom to top.
     */
    fun draw(scope: DrawScope, start: Float, end: Float, color: Color) {
        if (end <= start) return
        for (i in measures.indices) {
            val length = lengths[i]
            segment.reset()
            measures[i].getSegment(start * length, end * length, segment, true)
            scope.drawPath(segment, color, style = stroke)
        }
    }
}

/**
 * The capsule outline as two half-paths, each running bottom centre →
 * along an edge → around the end cap → back to the top centre. Empty
 * when [size] is not the shape we trace: a capsule needs at least one
 * full cap per side.
 */
private fun capsuleHalves(size: Size, strokeWidth: Float): List<Path> {
    val inset = strokeWidth / 2f
    val width = size.width - strokeWidth
    val height = size.height - strokeWidth
    if (height <= 0f || width < height) return emptyList()

    val left = inset
    val top = inset
    val right = inset + width
    val bottom = inset + height
    val radius = height / 2f
    val centerX = inset + width / 2f

    val leftHalf = Path().apply {
        moveTo(centerX, bottom)
        lineTo(left + radius, bottom)
        arcTo(Rect(left, top, left + height, bottom), 90f, 180f, false)
        lineTo(centerX, top)
    }
    val rightHalf = Path().apply {
        moveTo(centerX, bottom)
        lineTo(right - radius, bottom)
        arcTo(Rect(right - height, top, right, bottom), 90f, -180f, false)
        lineTo(centerX, top)
    }
    return listOf(leftHalf, rightHalf)
}

/**
 * The address field — the middle surface of the split bar, and the only
 * one that survives either morph.
 *
 * Hand-built around [BasicTextField] rather than M3's `TextField` /
 * `SearchBar`: their content padding shifts by a couple of dp between
 * focused and unfocused, which makes the field appear to grow when
 * tapped, and the search bar wants to own the whole screen on expansion.
 * A surface we place ourselves gives a rock-steady 44 dp pill at rest,
 * the compact pill's own height when minimised (32 dp at the default
 * font scale, taller where the label's line box is) and 64 dp in the
 * editor — one height function, [capsuleDrawnHeight], shared with the
 * round buttons' own 44 dp so the whole bar sits on one centre line
 * ([addressPillTopShift] included) for every frame of either morph.
 *
 * Its surface is *drawn*, not laid out: the Box stays
 * [addressFieldTouchHeight] tall throughout, so neither shrinking the
 * field nor inflating it ever changes the size of the tap target, and
 * the compact bar's one remaining control keeps a full-width 48 dp one.
 *
 * Inside it, left to right: the overflow menu ([menu], handed in as a
 * slot), the domain — drawn one level up with the protocol badge beside
 * it, see [BottomToolbar] — and the Reload / Stop control. The two
 * control slots are the same size, so the domain between them is centred
 * in the field. The badge is this row's only while the row's own text is
 * the text on screen (the editor's full URL, or the home tab's
 * placeholder); at rest it travels with the domain instead of with the
 * leading edge (see [addressBadgeCenterOffset]).
 *
 * Collapsing takes the field down to the domain and nothing else: both
 * control slots and the badge retreat into the field's edges through the
 * same [Modifier.collapsingControl] the round buttons use, the label
 * centres itself in what is left, and its type steps down one size. The
 * *string* is untouched — [AddressLabel.resting] with the same middle
 * ellipsis — because that is the part that is a trust surface.
 */
@Composable
private fun AddressField(
    state: BrowserState,
    restingLabel: String,
    addressFocused: Boolean,
    addressBarEdited: Boolean,
    collapse: Float,
    editProgress: Float,
    compactHeight: Dp,
    loading: Boolean,
    onAddressFocusChanged: (Boolean) -> Unit,
    onAddressEditedChanged: (Boolean) -> Unit,
    onAddressQueryChanged: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onReload: () -> Unit,
    onStop: () -> Unit,
    onExpandCapsule: () -> Unit,
    menu: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current

    // Long-press URL actions (copy / share / paste-and-go). All three
    // are answered from what the capsule already holds — the committed
    // address, the page title, the clipboard — so the gesture needs no
    // new plumbing out of this composable beyond the submit path
    // paste-and-go shares with the keyboard's Go key.
    var urlActionsOpen by remember(state.id) { mutableStateOf(false) }
    // Sampled at long-press rather than composed from the clipboard:
    // there is no "clipboard changed" signal worth subscribing to here,
    // and the answer only has to be right for the menu about to open.
    var urlActionsCanPaste by remember { mutableStateOf(false) }
    // The pill's window bounds, so the menu can be anchored above the
    // capsule the same hand-rolled way the overflow menu is (see
    // [OverflowMenuButton] for why we don't let the popup work it out).
    var pillBounds by remember { mutableStateOf<IntRect?>(null) }
    val pillInteractionSource = remember { MutableInteractionSource() }
    // What copy / share would act on: the tab's committed address, i.e.
    // exactly what the label the user pressed is derived from. `null` on
    // the home tab, where there is no address to offer.
    val actionUrl = urlActionTarget(state.addressBarText, state.url)

    // Local [TextFieldValue]: the *edit buffer*. It holds whatever the
    // user is typing and lets us steer the selection (e.g. select all
    // on focus). Keystrokes stay here — they never write back into
    // [state.addressBarText], which is the tab's committed address
    // (what the WebView loaded, or what the user submitted) and the
    // only thing we're allowed to present as "the site you are on".
    // The buffer is re-seeded from that address whenever it changes and
    // whenever an edit is abandoned (see the two effects below).
    //
    // Keyed on [state.id] so that switching tabs re-initialises
    // `fieldValue` from the new tab's `addressBarText` *synchronously*,
    // inside composition. Without the key, the remembered value would
    // carry over the previous tab's text and only be corrected on the
    // next composition pass once the `LaunchedEffect` below ran — which
    // briefly rendered the stale URL inside the newly-active tab's
    // pill.
    var fieldValue by remember(state.id) {
        mutableStateOf(
            TextFieldValue(
                text = state.addressBarText,
                selection = TextRange(state.addressBarText.length),
            ),
        )
    }

    // External → internal sync. Fires when the webview updates the
    // displayed URL, when the user hits × (see below), or when submit()
    // rewrites the bar with a canonical / ENS form. Keyed on the tab
    // id too so that when the active tab changes the new tab's own
    // sync state is tracked from scratch (otherwise a key based purely
    // on `state.addressBarText` would miss an update that happens to
    // land on the *same* string the previous tab had).
    LaunchedEffect(state.id, state.addressBarText) {
        // Never clobber an in-progress edit: a page that happens to
        // finish loading while the user is typing updates the committed
        // address, and the buffer picks that up when the edit ends.
        if (!addressFocused && fieldValue.text != state.addressBarText) {
            // Park the cursor at position 0 so long URLs horizontally
            // scroll to their *start* rather than their tail — the
            // domain is what the user cares about, so keeping e.g.
            // `https://example.com/...` visible beats showing the end
            // of a deep query string with the scheme pushed off-screen.
            fieldValue = TextFieldValue(
                text = state.addressBarText,
                selection = TextRange.Zero,
            )
        }
    }

    // Select-all on focus; on focus loss, abandon the edit.
    //
    // Running the select-all in a LaunchedEffect (rather than from
    // `onFocusChanged`) makes sure we apply *after* any tap-to-place-
    // cursor selection the framework might set during the focus-
    // granting gesture — otherwise the cursor can land wherever the
    // user happened to tap inside the pill.
    //
    // Losing focus ends the edit, so the buffer goes back to the tab's
    // committed address (cursor parked at 0, see above): text the user
    // typed but never submitted is not an address this tab is on, and
    // leaving it in the pill would have the capsule vouch for a site
    // that was never loaded. On the submit path this is a no-op —
    // `submit()` clears focus *and* writes the submitted URL into
    // `addressBarText` before we get here, so we re-seed from that.
    LaunchedEffect(addressFocused) {
        fieldValue = if (addressFocused) {
            if (fieldValue.text.isEmpty()) fieldValue
            else fieldValue.copy(selection = TextRange(0, fieldValue.text.length))
        } else {
            TextFieldValue(text = state.addressBarText, selection = TextRange.Zero)
        }
    }

    val colors = MaterialTheme.colorScheme
    val textStyle = LocalTextStyle.current.copy(color = colors.onSurface)
    // Focus is signalled by a primary-coloured ring rather than a
    // container colour change: the field is already a translucent
    // surface over the page, and a second fill on top of that would read
    // as mud on either scheme.
    // The colour animates with the theme's default effects spring so it
    // fades in rather than pops.
    val outline by animateColorAsState(
        targetValue = if (addressFocused) colors.primary else Color.Transparent,
        label = "addressOutline",
    )

    // Drawn (not laid out) pill geometry: the surface follows both morphs
    // while the Box that owns the touches keeps its height. Its width is
    // the box's — the caller sizes this composable to the field's drawn
    // width ([addressFieldDrawnWidth]), so the surface, the touch band and
    // the contents are one rectangle rather than three that have to be
    // kept in step.
    val pillHeight = capsuleDrawnHeight(collapse, editProgress, compactHeight)
    // The touch box has already followed the field as far down the slot
    // as it may ([addressFieldTouchShift]); this is the rest of the
    // bar's bottom anchor, applied in drawing only.
    val pillTopShift = addressPillTopShift(collapse, editProgress, compactHeight)
    // The band the taps land in: 48 dp, or the compact pill where an
    // accessibility font scale has made that taller — see
    // [addressFieldTouchHeight].
    val touchHeight = addressFieldTouchHeight(compactHeight)
    val pillTopPx = with(LocalDensity.current) {
        ((touchHeight - pillHeight) / 2f + pillTopShift).roundToPx()
    }
    val pillHeightPx = with(LocalDensity.current) { pillHeight.roundToPx() }

    // How much of themselves the field's own slots still have — see
    // [capsulePillSlotScale] for why this one is the collapse alone.
    val slotScale = capsulePillSlotScale(collapse)
    // The inset the content keeps from the field's edge, and how far the
    // menu's 48 dp target has to stand back from it to stay centred on
    // its 32 dp slot.
    val contentInset = capsuleLabelInset(collapse)
    val menuTargetInset =
        (contentInset - (CapsuleControlSize - CapsuleTrailingSlotSize) / 2f)
            .coerceAtLeast(0.dp)

    Box(
        modifier = modifier
            .height(touchHeight)
            .onGloballyPositioned { coords ->
                val r = coords.boundsInWindow()
                // The *drawn* pill's bounds, so the long-press menu keeps
                // anchoring on the pill's surface rather than on the touch
                // box around it.
                pillBounds = IntRect(
                    r.left.toInt(), r.top.toInt() + pillTopPx,
                    r.right.toInt(),
                    r.top.toInt() + pillTopPx + pillHeightPx,
                )
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        // The field's own surface — the same fill the round buttons wear,
        // at whatever height and shape the two morphs have made of it.
        // Composed first, so everything below paints over it.
        CapsuleSurface(
            shape = CircleShape,
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(pillHeight)
                .offset(y = pillTopShift),
        )
        if (outline.alpha > 0f) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .height(pillHeight)
                    .offset(y = pillTopShift)
                    .border(1.5.dp, outline, CircleShape),
            )
        }
        BasicTextField(
            value = fieldValue,
            onValueChange = { newValue ->
                val textChanged = newValue.text != fieldValue.text
                fieldValue = newValue
                if (textChanged) {
                    // Typing feeds the suggestions query only. The
                    // tab's committed address stays put until the user
                    // actually submits (or the WebView navigates).
                    onAddressQueryChanged(newValue.text)
                    onAddressEditedChanged(true)
                }
            },
            singleLine = true,
            textStyle = textStyle,
            cursorBrush = SolidColor(colors.primary),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onFocusChanged { onAddressFocusChanged(it.isFocused) },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(
                onGo = { onSubmit(fieldValue.text) },
            ),
            decorationBox = { innerTextField ->
                // Protocol badge: the pill grows a Swarm hex mark or
                // the IPFS cube on the left whenever the *loaded*
                // page origin is one of our embedded gateways. For
                // `ens://` names we look at the active display
                // override — its `baseUrl` is the gateway that
                // actually served the page, which tells us whether
                // the contenthash resolved to Swarm or IPFS.
                // Mirrors `.protocol-icon[data-protocol='swarm'|'ipfs'|'ipns']`
                // in freedom-browser's desktop address bar.
                val badge = protocolBadgeFor(state)
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        // Rides the same drawing shift as the surface it
                        // sits on, so the content stays on the compact
                        // pill's centre line rather than on the touch
                        // box's ([addressPillTopShift]).
                        .offset(y = pillTopShift)
                        // Symmetric, because the field holds one control
                        // at each end: 8 dp at rest and while editing,
                        // [CapsuleCompactSidePadding] when compact, where
                        // there is nothing left in the pill but the
                        // domain (see [capsuleLabelInset]).
                        .padding(horizontal = contentInset),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The overflow menu's reservation. Filled outside the
                    // field for the same reason the trailing slot is (see
                    // below) — what the row lays out is the empty box.
                    if (slotScale > 0f) {
                        Box(
                            modifier = Modifier
                                .capsuleFieldSlot(slotScale, towardsStart = true),
                        )
                    }
                    // The badge, on the runs where the text it marks is
                    // this row's: the editor's full URL, which starts at
                    // the leading edge, and the home tab's placeholder.
                    // The resting domain is drawn one level up, centred
                    // in the field, and the badge is drawn up there
                    // beside it — a mark on the domain has to travel with
                    // the domain (see [addressBadgeCenterOffset]). The
                    // two conditions are complements, so the badge is on
                    // screen exactly once in every state.
                    //
                    // Either way it is one slot with the gap after it,
                    // retreating into the pill's leading edge as the bar
                    // collapses — the compact pill shows the domain and
                    // nothing else. Zero-width means not composed, so it
                    // can't take a tap meant for the label.
                    if (badge != null && (addressFocused || restingLabel.isEmpty()) &&
                        slotScale > 0f
                    ) {
                        Row(
                            modifier = Modifier
                                .collapsingControl(slotScale, towardsStart = true),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Image(
                                painter = painterResource(badge.drawableRes),
                                contentDescription = badge.contentDescription,
                                modifier = Modifier.size(AddressPillBadgeSize),
                            )
                            Spacer(Modifier.width(AddressPillBadgeGap))
                        }
                    }
                    // Resting vs editing text. At rest the domain is the
                    // primary element (see [AddressLabel]); the moment
                    // the field takes focus the full URL is back, still
                    // select-alled, so nothing about editing changes.
                    //
                    // The text field itself stays composed and laid out
                    // in both states — it is simply drawn transparent
                    // under the domain label while resting — so a tap
                    // anywhere on the pill still lands on it and focuses
                    // it, exactly as before.
                    //
                    // The domain label itself is drawn one level up,
                    // against the bar rather than against this row, so
                    // that its geometry can be one function of the
                    // collapse (#55) — see [BottomToolbar]. What is
                    // left here is the box it used to share with the
                    // field: the field, and the placeholder for when
                    // there is no address to show.
                    //
                    // Blank label at rest means a blank address (the
                    // home tab) — [AddressLabel.resting] passes
                    // everything else through — so the placeholder is
                    // the right thing to draw underneath.
                    val showPlaceholder =
                        if (addressFocused) fieldValue.text.isEmpty() else restingLabel.isEmpty()
                    Box(modifier = Modifier.weight(1f)) {
                        Box(
                            modifier = Modifier.graphicsLayer {
                                alpha = if (addressFocused) 1f else 0f
                            },
                        ) {
                            innerTextField()
                        }
                        if (showPlaceholder) {
                            Text(
                                text = "Search or type URL",
                                color = colors.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                // Centred while resting, for the same
                                // reason the domain it stands in for is:
                                // the split bar's field is symmetric, and
                                // a left-aligned placeholder in it reads
                                // as text that failed to centre. Editing
                                // hands the box back to the text field,
                                // which starts at the leading edge.
                                textAlign = if (addressFocused) TextAlign.Start
                                else TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    // Trailing control slot — sized to the pill, never
                    // pushes it taller, and *always* reserved at the same
                    // 32 dp whatever it is currently holding. That fixed
                    // reservation is what makes "no layout shifts" true
                    // for the loading state: the Stop control appears
                    // into a slot that was already there, and the URL
                    // beside it doesn't re-wrap or re-ellipsise when a
                    // load starts or finishes.
                    //
                    // Reserved here, *filled* outside the field: the
                    // control has to sit above the pill's gesture surface
                    // to keep its own taps, and that surface has to sit
                    // above the text field (see below). So what the row
                    // lays out is the empty slot, and the button is
                    // composed over it with the same
                    // [Modifier.capsuleFieldSlot] — one box, laid out
                    // twice, so the two can't drift apart.
                    //
                    // The one thing that moves it is the collapse: the
                    // slot retreats into the pill's trailing edge along
                    // with the badge, because a minimised bar that is
                    // the domain *and a button* is neither minimised nor
                    // centred. Nothing is lost — the tap that brings the
                    // bar back brings Reload/Stop back with it, the same
                    // way it restores Back, the tab counter and the
                    // menu — and the load's own progress keeps being
                    // drawn on the compact pill's edge throughout.
                    if (slotScale > 0f) {
                        Box(
                            modifier = Modifier
                                .capsuleFieldSlot(slotScale, towardsStart = false),
                        )
                    }
                }
            },
        )

        // The capsule's tap surface: the two-step tap and the long-press
        // URL actions, over the whole pill.
        //
        // A sibling laid over the (transparent) text field rather than a
        // modifier on the Box around it: Compose hit-tests children
        // before their parent, so a modifier there would never see a
        // press the field itself takes first. On top, it gets the
        // gesture — and with it the chance to keep the field's *own*
        // long-press (selection handles, magnifier) off a label that
        // isn't even editable text yet.
        //
        // It covers the touch box **edge to edge** rather than just the
        // label's own layout box, in every state — resting, compact and
        // every frame between. The field's paddings are part of the pill
        // the user is aiming at, and a press that landed in one used to
        // fall through to the text field underneath: on a compact pill
        // that opened the editor and the keyboard on what should have
        // been the first, expand-only tap, and anywhere it raised raw
        // text selection — the platform's own Copy/Paste toolbar and two
        // selection handles floating over the page — on what should have
        // been the URL-actions long-press (#42). The box is the field's
        // drawn rectangle exactly, and the two controls inside it are
        // composed *over* this surface (see below), so they keep their
        // own taps while every other pixel of the pill answers here.
        //
        // Composed only while the field is unfocused, so the moment the
        // capsule becomes the editor it is gone and every touch inside
        // the pill is the text field's again — cursor placement, drag
        // selection, the lot. That is also what keeps the gesture off
        // stage 2's scroll wiring: it lives inside the capsule, never
        // over the page, so the WebView's own onTouchDown /
        // onDragPastSlop stream is untouched.
        //
        // `onClick` is the **two-step tap**: on a compact capsule it only
        // expands the bar back to its resting state — no focus, no
        // keyboard, no suggestions panel — and it is the *second* tap, on
        // the full-size bar, that opens the editor (request focus, which
        // select-alls, and raise the keyboard). See [capsuleTapAction];
        // the long-press is deliberately outside that split and offers
        // copy / share / paste-and-go in either state.
        if (!addressFocused) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .combinedClickable(
                        interactionSource = pillInteractionSource,
                        // No ripple: the pill is a painted bubble, and a
                        // rectangular ripple inside it would be the one
                        // square corner in the whole capsule.
                        indication = null,
                        onClickLabel = when (capsuleTapAction(collapse, addressFocused)) {
                            CapsuleTapAction.Expand -> "Expand address bar"
                            CapsuleTapAction.Edit -> "Edit address"
                        },
                        onLongClickLabel = "URL actions",
                        onLongClick = {
                            urlActionsCanPaste = context.clipboardHasText()
                            // Nothing to copy and nothing to paste is an
                            // empty menu; a long-press that opens one is
                            // worse than one that does nothing.
                            if (actionUrl != null || urlActionsCanPaste) {
                                urlActionsOpen = true
                            }
                        },
                        onClick = {
                            when (capsuleTapAction(collapse, addressFocused)) {
                                CapsuleTapAction.Expand -> onExpandCapsule()
                                CapsuleTapAction.Edit -> {
                                    focusRequester.requestFocus()
                                    keyboardController?.show()
                                }
                            }
                        },
                    ),
            )
        }

        // The overflow menu, filling the leading slot the row reserved —
        // composed over the gesture surface for the same reason the
        // trailing control is, and for the same reason on the same edge:
        // the hamburger has to keep its own taps while the pill around
        // it answers the two-step tap.
        //
        // Its box is [CapsuleControlSize] rather than the slot's 32 dp,
        // centred on the same point (hence [menuTargetInset] standing the
        // padding back by the difference): the brief asks every control
        // on this bar for Material's full 48 dp target, and the menu is
        // the one control inside the field that can have it without
        // eating into the domain — the 8 dp it gains on the leading side
        // is the field's own inset, page behind it either way.
        if (slotScale > 0f) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(y = pillTopShift)
                    .padding(start = menuTargetInset)
                    .collapsingControl(slotScale, towardsStart = true)
                    .size(CapsuleControlSize)
                    .semantics { traversalIndex = CapsuleOrderOverflow },
                contentAlignment = Alignment.Center,
            ) {
                menu()
            }
        }

        // The trailing control, filling the slot the label's row reserved
        // for it — composed here, last, so it sits above the gesture
        // surface: the surface covers the pill edge to edge, and the one
        // thing inside the pill that has to keep its own taps is this
        // button. Hit-testing takes the topmost sibling, so Reload / Stop
        // / × answer here while every other pixel of the pill answers the
        // surface below, and the text field — bottom of the stack, and
        // not editable text until it is focused — answers nowhere at all.
        //
        // Positioned from the pill's trailing edge exactly as the row
        // positioned it: the slot's own box ([Modifier.capsuleFieldSlot],
        // the same one the reservation uses), inset by the row's trailing
        // padding, on the label's centre line ([addressPillTopShift], the
        // row's own offset).
        //
        // Clipped to that slot while the surface is there. A Material
        // icon button quietly claims a 48 dp touch target around its
        // 32 dp self, which over the surface would hand it ~8 dp of label
        // on one side and the pill's outboard air on the other — the very
        // strip #42 is about. Focused, there is no surface to take it
        // from and the × keeps the full target.
        //
        // Which control: see [capsuleTrailingControl]. The
        // `addressBarEdited` guard inside it matters once the user
        // submits — submit() resets that flag (to dismiss the suggestions
        // panel) but intentionally leaves focus alone, so without the
        // check the × would stay visible while the page is already
        // loading; now that slot correctly becomes Stop.
        val trailing = capsuleTrailingControl(
            addressFocused = addressFocused,
            addressBarEdited = addressBarEdited,
            editBufferEmpty = fieldValue.text.isEmpty(),
            loading = loading,
            // Reload needs something to reload: either a loaded page or a
            // committed address. Mirrors the guard on [BrowserScreen]'s
            // onReload.
            canReload = state.url.isNotBlank() || state.addressBarText.isNotBlank(),
        )
        if (slotScale > 0f) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .offset(y = pillTopShift)
                    .padding(end = contentInset)
                    .then(if (addressFocused) Modifier else Modifier.clipToBounds())
                    .capsuleFieldSlot(slotScale, towardsStart = false)
                    .semantics { traversalIndex = CapsuleOrderTrailing },
                contentAlignment = Alignment.Center,
            ) {
                when (trailing) {
                    CapsuleTrailingControl.None -> Unit
                    CapsuleTrailingControl.Clear -> CapsuleTrailingButton(
                        icon = Icons.Filled.Clear,
                        contentDescription = "Clear",
                        onClick = {
                            fieldValue = TextFieldValue("")
                            onAddressQueryChanged("")
                            // × is a "start over" gesture — drop the
                            // suggestions panel and wait for the next
                            // keystroke before showing it again.
                            onAddressEditedChanged(false)
                        },
                    )
                    CapsuleTrailingControl.Stop -> CapsuleTrailingButton(
                        icon = Icons.Filled.Close,
                        contentDescription = "Stop loading",
                        tint = colors.primary,
                        onClick = onStop,
                    )
                    CapsuleTrailingControl.Reload -> CapsuleTrailingButton(
                        icon = Icons.Filled.Refresh,
                        contentDescription = "Reload",
                        onClick = onReload,
                    )
                }
            }
        }

        // The long-press menu. Anchored on the pill's own bounds, above
        // the capsule; dismissed by a tap outside or by the system back
        // gesture (the popup is focusable, so back closes the menu
        // instead of navigating the page under it).
        //
        // Guarded on focus as well as on the flag: if anything at all
        // focuses the field while the menu is up, the capsule is the
        // editor now and the menu has nothing left to describe.
        val anchor = pillBounds
        if (urlActionsOpen && anchor != null && !addressFocused) {
            CapsuleUrlActionsMenu(
                anchor = anchor,
                canCopy = actionUrl != null,
                canPaste = urlActionsCanPaste,
                onCopy = { actionUrl?.let { copyUrlToClipboard(context, it) } },
                onShare = { actionUrl?.let { shareUrl(context, it, state.title) } },
                // Paste-and-go goes through the *same* submit path as the
                // keyboard's Go key — so a pasted `bzz://` / ENS address
                // takes the probe-gated route and a pasted phrase
                // searches, with no second opinion about what a URL is.
                onPasteAndGo = {
                    pasteAndGoFromClipboard(context)?.let(onSubmit)
                },
                onDismiss = { urlActionsOpen = false },
            )
        }
    }
}

/**
 * One icon in the address field's trailing slot. Every occupant is built
 * the same way — 32 dp button, [CapsuleFieldIconSize] glyph — so swapping
 * between Clear, Stop and Reload changes only which vector is drawn,
 * never a metric.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CapsuleTrailingButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    IconButton(
        onClick = onClick,
        shapes = IconButtonDefaults.shapes(),
        modifier = Modifier.size(32.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(CapsuleFieldIconSize),
        )
    }
}

/**
 * Hamburger button plus its popup menu, on the address field's leading
 * edge — where Safari keeps it, and where it reads as part of the
 * address rather than as one more thing on the end of a row.
 *
 * The node-status dot that used to ride the button's corner is gone with
 * the move: a coloured dot on a control *inside* the address field is a
 * badge on the trust surface, which is the one place in the chrome it
 * must not be. The menu's own "N peers" row is where the user goes to
 * act on the node anyway, and the Node screen behind it is unchanged.
 *
 * The popup still anchors on the button's own window bounds, so it
 * follows the button to its new position with no arithmetic of its own
 * (see below for why the anchoring is hand-rolled).
 */
@Composable
private fun OverflowMenuButton(
    state: BrowserState,
    nodeInfo: NodeInfo,
    isBookmarked: Boolean,
    onForward: () -> Unit,
    onHome: () -> Unit,
    onToggleBookmark: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenNode: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onReload: () -> Unit,
    onNewTab: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    // We hand-roll the anchor positioning rather than rely on
    // Material3's [DropdownMenu]: its Popup mis-anchors on the very
    // first open and only recovers on subsequent opens. Tracking the
    // IconButton's window bounds ourselves via [onGloballyPositioned]
    // and feeding them to a [Popup] + custom [PopupPositionProvider]
    // produces a stable anchor from the first frame.
    var anchorBounds by remember { mutableStateOf<IntRect?>(null) }
    val peerCount = nodeInfo.connectedPeers
    // Lift the popup clear of the toolbar's own top padding plus a
    // little air, so it floats above the pill instead of touching it.
    val popupGapPx = with(LocalDensity.current) { 12.dp.roundToPx() }

    Box(
        modifier = Modifier.onGloballyPositioned { coords ->
            val r = coords.boundsInWindow()
            anchorBounds = IntRect(
                r.left.toInt(), r.top.toInt(),
                r.right.toInt(), r.bottom.toInt(),
            )
        },
    ) {
        IconButton(
            onClick = { menuExpanded = true },
            shapes = IconButtonDefaults.shapes(),
            modifier = Modifier.size(CapsuleControlSize),
        ) {
            // Sized to the field it sits in rather than to Material's
            // 24 dp default: the glyph is the same weight as the Reload
            // mark across the field from it, so the two ends of the
            // domain read as a pair.
            Icon(
                imageVector = Icons.Filled.Menu,
                contentDescription = "Menu",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(CapsuleFieldIconSize),
            )
        }
        if (menuExpanded && anchorBounds != null) {
            Popup(
                popupPositionProvider = AnchoredAboveProvider(anchorBounds!!, popupGapPx),
                onDismissRequest = { menuExpanded = false },
                properties = PopupProperties(focusable = true),
            ) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 3.dp,
                    shadowElevation = 3.dp,
                ) {
                    // [IntrinsicSize.Max] makes the Column size to
                    // its widest child's natural width. Without
                    // this, [DropdownMenuItem] uses fillMaxWidth
                    // internally and the popup grows to the window.
                    Column(
                        modifier = Modifier
                            .width(IntrinsicSize.Max)
                            .padding(vertical = 8.dp),
                    ) {
                        DropdownMenuItem(
                            text = {
                                MenuItemLabel(
                                    if (isBookmarked) "Remove bookmark" else "Add bookmark",
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (isBookmarked) Icons.Filled.Star
                                    else Icons.Filled.StarBorder,
                                    contentDescription = null,
                                    tint = if (isBookmarked) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                                )
                            },
                            enabled = state.url.isNotBlank(),
                            onClick = {
                                menuExpanded = false
                                onToggleBookmark()
                            },
                        )
                        // Home lives here now that Back owns the
                        // capsule's left control slot.
                        DropdownMenuItem(
                            text = { MenuItemLabel("Home") },
                            leadingIcon = { Icon(Icons.Filled.Home, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onHome()
                            },
                        )
                        DropdownMenuItem(
                            text = { MenuItemLabel("Forward") },
                            leadingIcon = {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = null,
                                )
                            },
                            enabled = state.canGoForward,
                            onClick = {
                                menuExpanded = false
                                onForward()
                            },
                        )
                        DropdownMenuItem(
                            text = { MenuItemLabel("New tab") },
                            leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onNewTab()
                            },
                        )
                        DropdownMenuItem(
                            text = { MenuItemLabel("Reload") },
                            leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onReload()
                            },
                        )
                        DropdownMenuItem(
                            text = { MenuItemLabel("History") },
                            leadingIcon = { Icon(Icons.Filled.History, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onOpenHistory()
                            },
                        )
                        DropdownMenuItem(
                            text = { MenuItemLabel("Bookmarks") },
                            leadingIcon = { Icon(Icons.Filled.Bookmark, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onOpenBookmarks()
                            },
                        )
                        DropdownMenuItem(
                            text = { MenuItemLabel("Settings") },
                            leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onOpenSettings()
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                MenuItemLabel(
                                    if (peerCount == 1L) "1 peer" else "$peerCount peers",
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(baby.freedom.mobile.R.drawable.ic_nodes),
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onOpenNode()
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Text label for a [DropdownMenuItem] that carries its own trailing
 * padding. Every label wears the same end-inset, so [IntrinsicSize.Max]
 * on the parent Column grows the whole popup past the bare-text width
 * and keeps long labels like "Bookmark" from hugging the right edge.
 */
@Composable
internal fun MenuItemLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(end = 32.dp),
    )
}

/**
 * Places a [Popup] [gapPx] above an anchor's *top* edge (it opens
 * upwards, since the anchor lives in the bottom toolbar), aligned to the
 * anchor's trailing edge when [alignToEnd] (the overflow menu, hanging
 * off its button) or its leading edge otherwise (the URL-actions menu,
 * hanging off the label that was pressed), clamping to the window so the
 * popup never runs off-screen. The anchor bounds are captured by the
 * caller via [Modifier.onGloballyPositioned]; we deliberately ignore the
 * [anchorBounds] argument the framework hands in, since that's the
 * very value that mis-fires on the first open for Material3's default
 * [androidx.compose.material3.DropdownMenu].
 */
internal class AnchoredAboveProvider(
    private val anchor: IntRect,
    private val gapPx: Int,
    private val alignToEnd: Boolean = true,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val startEdge = layoutDirection == LayoutDirection.Ltr
        val x = when (alignToEnd) {
            true -> if (startEdge) anchor.right - popupContentSize.width else anchor.left
            false -> if (startEdge) anchor.left else anchor.right - popupContentSize.width
        }.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val y = (anchor.top - gapPx - popupContentSize.height)
            .coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))
        return IntOffset(x, y)
    }
}
