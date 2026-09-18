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
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import baby.freedom.mobile.ui.isLight
import baby.freedom.swarm.NodeInfo
import baby.freedom.swarm.NodeStatus
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Height of the floating capsule at rest. The brief allows 52–58 dp;
 * 56 dp is the one value in that band that fits a stock 48 dp
 * [IconButton] with a symmetric 4 dp of breathing room above and below,
 * so every control keeps its full Material touch target without the
 * capsule growing a visible gutter.
 */
internal val CapsuleHeight = 56.dp

/**
 * Height of the capsule in its compact (scrolled) state. The brief
 * allows 40–44 dp; 44 dp is the top of that band, which leaves the
 * compact label room to step down exactly one type size (see
 * [addressLabelFontSize]) rather than the two a 40 dp bar would invite —
 * the label is a trust surface ([AddressLabel]) and a shrunken domain is
 * a harder one to read.
 */
internal val CapsuleCompactHeight = 44.dp

/**
 * Height of the capsule once it has morphed into the address editor.
 * The bar grows by the same 8 dp it gains on each side (see
 * [CapsuleEditingSideMargin]) so the expansion reads as one object
 * inflating rather than a control that got taller.
 */
internal val CapsuleEditingHeight = 64.dp

/** Side margin between the capsule and the screen edge (brief: 12–16 dp). */
internal val CapsuleSideMargin = 14.dp

/**
 * Side margin while editing. The capsule reaches towards the screen
 * edges to make room for the full URL — still a floating pill with page
 * visible around it, never a full-bleed bar and never a new screen.
 */
internal val CapsuleEditingSideMargin = 8.dp

/** Gap between the capsule and the navigation/gesture inset (brief: 8–12 dp). */
internal val CapsuleBottomMargin = 10.dp

/** Address pill height inside the resting (56 dp) capsule. */
internal val AddressPillHeight = 40.dp

/** Address pill height inside the compact (44 dp) capsule. */
internal val AddressPillCompactHeight = 32.dp

/** Address pill height inside the editing (64 dp) capsule. */
internal val AddressPillEditingHeight = 48.dp

/**
 * Touch height of the address pill. Constant across all three states
 * and independent of the pill's *drawn* height, so neither morph ever
 * shrinks a tap target: the field is laid out 48 dp tall inside the
 * toolbar's (equally constant) 48 dp control row and its pill is
 * painted at the interpolated height inside that box.
 */
internal val AddressFieldTouchHeight = 48.dp

/**
 * Height of the *slot* the capsule is laid out in.
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
 * Height the capsule is actually drawn at — the single height function
 * both morphs go through, so there is one geometry model rather than a
 * scroll driver and an editing driver arguing over the same pixels.
 *
 * It reads outwards from the slot: [editProgress] grows 56 → 64 dp,
 * then [collapse] shrinks whatever that gives towards 44 dp. The two
 * can't fight over the result, because [BrowserScreen] holds `collapse`
 * at 0 whenever the address bar has focus — **editing always wins over
 * compact** — so the settled values are exactly 44 ← 56 → 64. Composing
 * them rather than picking one keeps the handover continuous: tapping a
 * compact bar springs `collapse` back down while `editProgress` comes
 * up, and the capsule travels 44 → 64 without a discontinuity in the
 * frame where they cross.
 */
internal fun capsuleDrawnHeight(collapse: Float, editProgress: Float): Dp =
    lerp(capsuleSlotHeight(editProgress), CapsuleCompactHeight, collapse.coerceIn(0f, 1f))

/**
 * The address pill's drawn height, derived from the same two morphs by
 * the same rule as [capsuleDrawnHeight]. It tracks the capsule so the
 * gutter above and below the pill stays even in every state (8 dp at
 * rest and while editing, 6 dp compact), and it is only ever *drawn* at
 * this height — see [AddressFieldTouchHeight].
 */
internal fun addressPillHeight(collapse: Float, editProgress: Float): Dp =
    lerp(
        lerp(AddressPillHeight, AddressPillEditingHeight, editProgress.coerceIn(0f, 1f)),
        AddressPillCompactHeight,
        collapse.coerceIn(0f, 1f),
    )

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
 */
internal val CapsuleCompactSidePadding = 20.dp

/** Gutter between the capsule's edge and the controls laid out over it. */
private val CapsuleRowPadding = 4.dp

/** Gutter between those controls and the address pill. */
private val CapsuleFieldPadding = 4.dp

/**
 * The pill's own trailing slot — Clear / Stop / Reload, or empty. Always
 * reserved at this width whatever it currently holds, so nothing in the
 * pill re-wraps when a load starts or finishes.
 */
private val CapsuleTrailingSlotSize = 32.dp

/** Pill-edge to label inset at rest. */
private val AddressPillLabelInset = 16.dp

/** The same, with the protocol badge filling part of it. */
private val AddressPillLabelInsetWithBadge = 10.dp

/** Pill-edge to trailing-slot inset at rest. */
private val AddressPillTrailingInset = 4.dp

/**
 * The two gutters between the capsule's edge and the address field's
 * touch box, added up — what the flanking controls' air costs the field
 * on either side.
 */
internal val CapsuleFieldSideGutter = CapsuleRowPadding + CapsuleFieldPadding

/**
 * How much of [CapsuleFieldSideGutter] the address field's touch box has
 * taken over.
 *
 * The gutters are the flanking controls' air, and while any control is
 * still on screen they stay theirs — nothing moves under a
 * half-collapsed bar. Once the controls have retreated into the
 * capsule's edges (which they finish doing at `1 / CONTROL_COLLAPSE_RATE`
 * of the collapse, see [Modifier.collapsingControl]) that air belongs to
 * nobody, and the compact capsule is *all* address bar: the field's box
 * grows into it, so every pixel of the pill the user can see answers the
 * two-step tap instead of falling through to the text field underneath.
 *
 * Nothing about the capsule moves when it does — [addressPillSideInset]
 * gives back in drawing exactly what this takes in layout. It is the
 * width counterpart of what the height has always done: a touch box that
 * stays [AddressFieldTouchHeight] tall with a 32 / 40 / 48 dp pill
 * painted inside it.
 */
internal fun capsuleGutterHandover(collapse: Float): Float {
    val controlsGone = 1f / CONTROL_COLLAPSE_RATE
    return ((collapse.coerceIn(0f, 1f) - controlsGone) / (1f - controlsGone))
        .coerceIn(0f, 1f)
}

/** How much of [CapsuleRowPadding] is still the flanking controls'. */
internal fun capsuleControlGutter(collapse: Float): Dp =
    lerp(CapsuleRowPadding, 0.dp, capsuleGutterHandover(collapse))

/** How much of [CapsuleFieldPadding] is still the flanking controls'. */
internal fun capsuleFieldGutter(collapse: Float): Dp =
    lerp(CapsuleFieldPadding, 0.dp, capsuleGutterHandover(collapse))

/**
 * How far inside its touch box the address pill is *drawn* — exactly the
 * gutter the box has swallowed ([capsuleGutterHandover]), so the painted
 * pill stays where it was whatever the handover is doing.
 */
internal fun addressPillSideInset(collapse: Float): Dp =
    CapsuleFieldSideGutter - capsuleControlGutter(collapse) - capsuleFieldGutter(collapse)

/**
 * Capsule-edge to label inset, per side.
 *
 * Asymmetric at rest — [restingInset] is 16 dp of start padding against
 * the trailing slot's own 32 dp, 10 dp when the protocol badge is there
 * to fill it, 4 dp on the trailing side — and symmetric
 * [CapsuleCompactSidePadding] when compact, because the compact label is
 * centred.
 *
 * Measured from the *capsule's* edge rather than from the field's box,
 * which is what lets [capsuleGutterHandover] grow the box underneath the
 * label without the label moving: the padding actually applied is this
 * less whatever gutter is left outside the box.
 */
internal fun capsuleLabelInset(collapse: Float, restingInset: Dp): Dp =
    lerp(
        restingInset + CapsuleFieldSideGutter,
        CapsuleCompactSidePadding,
        collapse.coerceIn(0f, 1f),
    )

/**
 * [capsuleLabelInset] expressed as a padding inside the address field's
 * own box — the label's distance from the capsule edge, less the part of
 * that distance the box already spans.
 */
internal fun addressLabelPadding(collapse: Float, restingInset: Dp): Dp =
    capsuleLabelInset(collapse, restingInset) -
        (CapsuleFieldSideGutter - addressPillSideInset(collapse))

/**
 * Narrowest the compact capsule ever gets. Safari's minimised bar keeps
 * a recognisable pill even for a three-letter host, and a 120 dp capsule
 * still leaves the label's tap surface ~104 × 48 dp — a full Material
 * touch target for the tap that expands it.
 */
internal val CapsuleCompactMinWidth = 120.dp

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
 * Size the domain label is drawn at, interpolated along the collapse so
 * the label morphs with the capsule rather than snapping a size at some
 * threshold.
 */
internal fun addressLabelFontSize(collapse: Float): TextUnit =
    lerp(
        AddressLabelRestingFontSize,
        AddressLabelCompactFontSize,
        collapse.coerceIn(0f, 1f),
    )

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
 * The floor yields to the ceiling: on a window too narrow for 120 dp of
 * capsule, the resting width wins, because a "compact" bar wider than
 * the bar it came from is not a collapse.
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
 * How far the capsule's tap surface stands back from the pill's trailing
 * edge, so the trailing control keeps answering for its own slot.
 *
 * The surface covers the pill edge to edge except for this: the trailing
 * slot ([capsulePillSlotScale] of it, since the slot narrows as it
 * retreats) plus the air between that slot and the pill's edge, which is
 * *outboard* of the button and so cannot be reclaimed without covering
 * the button itself.
 *
 * Which is why the standback goes to zero the moment the tap is an
 * [CapsuleTapAction.Expand]. Held back, that outboard strip is nobody's:
 * it is past the shrinking button, so a press there falls through to the
 * transparent text field underneath and opens the editor and the keyboard
 * on what the two-step tap had decided was an expand-only first tap.
 * Between half-collapsed and `1 / CONTROL_COLLAPSE_RATE` — the frames
 * where the tap already means Expand but the slot has not finished
 * retreating — that would be a ~8 dp hole in the rule that *every* pixel
 * of a mostly-compact pill expands the bar. The button it covers there is
 * a ≤ 6 dp sliver mid-spring, far too small to aim at and never at rest
 * (the fraction only settles at 0 or 1), so the whole pill answering the
 * expand is the better trade.
 */
internal fun capsuleTapSurfaceStandback(collapse: Float, addressFocused: Boolean): Dp {
    val slotScale = capsulePillSlotScale(collapse)
    val expands = capsuleTapAction(collapse, addressFocused) == CapsuleTapAction.Expand
    if (slotScale <= 0f || expands) return 0.dp
    return CapsuleTrailingSlotSize * slotScale +
        addressLabelPadding(collapse, AddressPillTrailingInset)
}

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
 * Capsule background opacity on the **dark** scheme. Low enough that the
 * page reads through as a faint wash (so the chrome sits *over* the page
 * rather than replacing a strip of it), high enough that `onSurface`
 * text stays legible over both a white article and a dark hero image.
 */
private const val CAPSULE_ALPHA_DARK = 0.90f

/**
 * Capsule background opacity on the **light** scheme.
 *
 * A dark capsule is unmistakable over any page; a near-white one is not,
 * and the page it has the least to say against is a white one — the
 * common case. Every point of transparency there lerps the fill
 * *towards* the page it is sitting on, so the same 0.90 that reads as a
 * faint wash on dark reads as the pill dissolving on light. 0.94 keeps
 * the page visible through the chrome (that is still the brief's point)
 * while leaving the capsule's own tone, and therefore its edge, legible
 * against white.
 */
private const val CAPSULE_ALPHA_LIGHT = 0.94f

/**
 * Shadow under the capsule, per scheme. Just enough to lift it off the
 * page without the "heavy shadow" the brief rules out — but on the light
 * scheme the shadow is doing most of the lifting on its own (a pale
 * capsule on a pale page has little tonal separation to offer), whereas
 * on the dark one a black shadow over dark content is nearly invisible
 * and the fill does the work.
 */
private val CapsuleShadowDark = 3.dp
private val CapsuleShadowLight = 6.dp

/**
 * Fill of the outer capsule at a given collapse fraction.
 *
 * At rest it is what it has always been: `surfaceContainer` at the
 * scheme's alpha ([CAPSULE_ALPHA_LIGHT] / [CAPSULE_ALPHA_DARK]), a
 * translucent tray with the darker address pill
 * (`surfaceContainerHighest`, opaque) sitting inside it. Two surfaces,
 * which is right while the capsule is a *bar*: the pill has to be
 * distinguishable from the controls flanking it.
 *
 * Compact, there is nothing left to distinguish it from — the controls
 * are gone and the capsule has wrapped itself to the domain — so the
 * outer surface stops being a tray and starts reading as a translucent
 * rim around a small pill: two nested shapes where Safari shows one. So
 * the compact capsule is the *address field's* colour, and only that
 * colour.
 *
 * Interpolated rather than switched, in both channels at once: the fill
 * walks `surfaceContainer` → `surfaceContainerHighest` while the alpha
 * walks 0.94 / 0.90 → 1, so the rim doesn't blink out at a threshold, it
 * dissolves into the pill. And because the two fills converge rather
 * than one being removed, the inset between them stops being visible
 * exactly when they meet — the compact capsule is one colour edge to
 * edge, with no geometry needing to move for it (the pill keeps the
 * 32 dp height and the tap surface, the shadow keeps lifting the whole
 * thing off a same-tone page, and the load trace still runs along the
 * one outline that is left).
 *
 * Editing never reaches this: the caller holds `collapse` at 0 whenever
 * the field has focus, so the editor keeps the resting two-surface look.
 */
internal fun capsuleFill(collapse: Float, colors: ColorScheme): Color = lerp(
    colors.surfaceContainer.copy(
        alpha = if (colors.isLight) CAPSULE_ALPHA_LIGHT else CAPSULE_ALPHA_DARK,
    ),
    colors.surfaceContainerHighest,
    collapse.coerceIn(0f, 1f),
)

/**
 * The browser chrome: a floating, semi-transparent capsule layered over
 * an edge-to-edge page, holding (left to right) the Back control (only
 * while there's history to pop), the address field with its protocol
 * badge, the tab counter and the overflow menu. Home moved into that
 * menu — on a page you can reach it in one extra tap, and the resting
 * bar stays low-density the way the brief asks.
 *
 * The capsule is a `surfaceContainer` pill at [capsuleFill]'s alpha with
 * a low shadow, not an opaque full-width bar; the address field is a
 * second, darker pill inside it (`surfaceContainerHighest`) that grows
 * a primary-coloured outline while focused. Layout is fixed-height so
 * nothing shifts when focus, the trailing control or the protocol badge
 * come and go.
 *
 * Those two surfaces are a *resting* (and editing) look: as the bar
 * compacts, the outer fill morphs into the pill's own, so the minimised
 * capsule is a single opaque `surfaceContainerHighest` shape with no
 * translucent rim around it (see [capsuleFill]).
 *
 * The capsule has exactly three heights and one model that produces
 * them (see [capsuleDrawnHeight]): 44 dp **compact**, 56 dp **resting**,
 * 64 dp **editing**. Two callers-supplied fractions drive it and
 * nothing else does:
 *
 * **[collapseFraction]** (0 resting, 1 compact) is the compact-on-scroll
 * state. The capsule shrinks *inside* a layout slot that keeps its
 * resting height, so a flick moves the bar and nothing else on screen.
 *
 * **[editProgress]** (0 at rest, 1 in the editor) is the editing morph —
 * the same object inflating, not a second screen. It grows the slot as
 * well as the capsule, interpolates the address pill's height here and
 * the side margins in the caller.
 *
 * They are one axis, not two: the caller holds [collapseFraction] at 0
 * whenever the field has focus, so editing always wins over compact, and
 * both transitions take the flanking controls away through the *same*
 * mechanism ([Modifier.collapsingControl]) — each control shrinks whole
 * into the edge it retreats to, width and scale together, never a
 * cross-fade and never a clipped fragment.
 *
 * **Loading** is drawn *on* the capsule: a thin trace runs along its
 * outline from the bottom centre out to both sides (see
 * [CapsuleEdgeTrace]). It is measured from whatever outline the capsule
 * currently has, so it traces the compact and editing shapes just as
 * readily as the resting one. The pill's trailing slot turns into a Stop
 * control. Both are overlays on geometry that is already settled, so a
 * load starting or ending moves nothing.
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
    val slotHeight = capsuleSlotHeight(edit)
    val drawnHeight = capsuleDrawnHeight(collapse, edit)

    // How much of their resting size the flanking controls still have —
    // one number for both transitions, because they leave the same way
    // in both. Taking the max rather than tracking the two separately is
    // what makes tapping a compact bar read as one continuous move:
    // `collapse` springs back to 0 while `edit` comes up, and the
    // controls stay gone across the handover instead of flashing in
    // between the two states.
    val controlScale =
        (1f - max(collapse, edit) * CONTROL_COLLAPSE_RATE).coerceIn(0f, 1f)

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
    // Which scheme is under us — read from the colours themselves rather
    // than the system flag, so the capsule's alpha and shadow follow what
    // it is actually painting with (see [ColorScheme.isLight]).
    val lightScheme = MaterialTheme.colorScheme.isLight
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
    val labelStyle = LocalTextStyle.current
    // What the label looks like at full collapse. Measuring at the
    // *compact* size is the point: the settled compact capsule wraps the
    // settled compact label.
    val compactLabelStyle = remember(labelStyle) {
        labelStyle.copy(
            fontSize = AddressLabelCompactFontSize,
            fontWeight = FontWeight.Medium,
        )
    }
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val compactLabelWidth = remember(restingLabel, compactLabelStyle, density) {
        if (restingLabel.isEmpty()) 0.dp
        else with(density) {
            val px = textMeasurer.measure(
                text = restingLabel,
                style = compactLabelStyle,
                maxLines = 1,
                softWrap = false,
            ).size.width
            // Ceiled to whole dp: the width this feeds is turned back
            // into px at layout time, and losing a fraction there would
            // middle-ellipsise a label the capsule was sized to fit.
            ceil(px.toDp().value).dp
        }
    }

    BoxWithConstraints(
        // The slot the capsule lives in. Compacting shrinks the capsule
        // *inside* it — in width now as well as height — which is what
        // keeps the page, the snackbar and the IME reserve from moving
        // when the bar collapses, and what lets the controls keep 48 dp
        // touch targets while the capsule around them is only 44 dp
        // tall. Editing is the one transition that grows it (see
        // [capsuleSlotHeight]).
        modifier = modifier
            .fillMaxWidth()
            .height(slotHeight),
        contentAlignment = Alignment.Center,
    ) {
        // The resting width is whatever the caller's slot offers; the
        // compact one is the label plus its padding. Centred in the slot
        // by the Box, so the capsule stays bottom-centred on screen as
        // it narrows.
        val restingWidth = maxWidth
        val drawnWidth = capsuleDrawnWidth(
            collapse = collapse,
            restingWidth = restingWidth,
            compactWidth = compactCapsuleWidth(compactLabelWidth, restingWidth),
        )

        // The capsule itself is background only: drawn at the
        // interpolated height and width, centred in the slot, with the
        // controls laid out over it rather than inside it.
        Surface(
            modifier = Modifier
                .width(drawnWidth)
                .height(drawnHeight),
            shape = CircleShape,
            // Translucent `surfaceContainer` tray at rest, morphing into
            // the address field's own opaque fill as the bar compacts —
            // one colour, one shape, no rim (see [capsuleFill]).
            color = capsuleFill(collapse, MaterialTheme.colorScheme),
            shadowElevation = if (lightScheme) CapsuleShadowLight else CapsuleShadowDark,
            content = {},
        )

        // [Surface] used to set this for its content; the content now
        // sits beside it, so state it.
        CompositionLocalProvider(
            LocalContentColor provides MaterialTheme.colorScheme.onSurface,
        ) {
            Row(
                // The width tracks the capsule; the height stays the
                // slot's, so the 48 dp touch boxes inside keep their
                // full height even while the capsule around them is
                // 44 dp tall.
                modifier = Modifier
                    .fillMaxHeight()
                    .width(drawnWidth)
                    // The gutter is the controls' own air, and it is
                    // theirs for as long as there is a control to give it
                    // to; once they are gone it becomes address-bar touch
                    // surface (see [capsuleGutterHandover]).
                    .padding(horizontal = capsuleControlGutter(collapse)),
                // Mixed-height children (a 40 dp pill next to 48 dp icon
                // buttons) only sit on the capsule's centre line if we say
                // so explicitly — a Row defaults to Top.
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Back replaces Home in the resting bar: history is the
                // control a reader actually reaches for, and it costs
                // nothing when there is no history to pop.
                //
                // Fully-collapsed controls are not composed at all, so a
                // zero-width Back button can never take a tap meant for
                // the page or the domain, and the overflow menu's popup
                // anchor goes away with the button it belongs to.
                if (state.canGoBack && controlScale > 0f) {
                    Box(modifier = Modifier.collapsingControl(controlScale, towardsStart = true)) {
                        // Expressive shape variants: the icon buttons morph
                        // from round to a squarer pressed shape on touch.
                        // Purely visual — the 48 dp hit target and click
                        // handlers are unchanged.
                        IconButton(onClick = onBack, shapes = IconButtonDefaults.shapes()) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                    loading = loading,
                    onAddressFocusChanged = onAddressFocusChanged,
                    onAddressEditedChanged = onAddressEditedChanged,
                    onAddressQueryChanged = onAddressQueryChanged,
                    onSubmit = onSubmit,
                    onReload = onReload,
                    onStop = onStop,
                    onExpandCapsule = onExpandCapsule,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = capsuleFieldGutter(collapse)),
                )

                if (controlScale > 0f) {
                    Box(
                        modifier = Modifier.collapsingControl(controlScale, towardsStart = false),
                    ) {
                        TabsCountButton(count = tabCount, onClick = onOpenTabs)
                    }

                    Box(
                        modifier = Modifier.collapsingControl(controlScale, towardsStart = false),
                    ) {
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
                    }
                }
            }
        }

        // Load progress, stroked along the capsule's *current* outline.
        //
        // Its own sibling, sized to the drawn capsule rather than to the
        // slot, and last in the Box so it lands over the finished
        // capsule and its controls — the trace belongs on the edge, not
        // half-swallowed by the Surface's shape clip. It carries no
        // pointer input, so it is not a hit target and the controls
        // underneath it still take every tap.
        //
        // Because it is measured from `size`, the trace re-traces
        // whatever shape the capsule currently is: 44 dp compact, 56 dp
        // at rest, 64 dp editing, and every frame in between.
        //
        // `state.progress` is read inside the draw lambda (and only as a
        // boundary, through `loading` above), so a ticking load
        // invalidates drawing only — never layout or composition.
        if (loading) {
            Box(
                modifier = Modifier
                    .width(drawnWidth)
                    .height(drawnHeight)
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
 * controls collapse left, trailing controls collapse right).
 *
 * Order matters: the scale has to be applied *inside* the narrowing
 * slot, so `layout` (outer) wraps `graphicsLayer` (inner). Written the
 * other way round the layer would scale the already-narrowed slot a
 * second time, and the control would paint at `visible²` of its slot —
 * a cropped edge fragment beside an empty gap — instead of the whole
 * control shrinking to fill the slot.
 */
private fun Modifier.collapsingControl(visible: Float, towardsStart: Boolean): Modifier {
    if (visible >= 1f) return this
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
            transformOrigin = TransformOrigin(if (towardsStart) 0f else 1f, 0.5f)
            clip = true
        }
}

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
 * The address pill. Hand-built around [BasicTextField] rather than M3's
 * `TextField` / `SearchBar`: their content padding shifts by a couple
 * of dp between focused and unfocused, which makes the pill appear to
 * grow when tapped, and the search bar wants to own the whole screen
 * on expansion. A bubble we paint ourselves gives a rock-steady 40 dp
 * pill inside the 56 dp resting capsule, 32 dp inside the 44 dp compact
 * one and 48 dp inside the 64 dp editing one — see [addressPillHeight],
 * which is the capsule's own height rule applied one level down, so the
 * gutter above and below the pill stays even in every state and the
 * pill is exactly centred for every frame of either morph.
 *
 * The bubble is *drawn*, not laid out: the Box stays
 * [AddressFieldTouchHeight] tall throughout, so neither shrinking the
 * capsule nor inflating it ever changes the size of the tap target, and
 * the compact bar's one remaining control keeps a full-width 48 dp one.
 *
 * Collapsing also takes the pill down to the domain and nothing else:
 * the protocol badge's leading slot and the trailing Reload/Stop slot
 * both retreat into the pill's edges through the same
 * [Modifier.collapsingControl] the flanking controls use, the label
 * centres itself in what is left, and its type steps down one size (see
 * [addressLabelFontSize]). The *string* is untouched —
 * [AddressLabel.resting] with the same middle ellipsis — because that is
 * the part that is a trust surface.
 */
@Composable
private fun AddressField(
    state: BrowserState,
    restingLabel: String,
    addressFocused: Boolean,
    addressBarEdited: Boolean,
    collapse: Float,
    editProgress: Float,
    loading: Boolean,
    onAddressFocusChanged: (Boolean) -> Unit,
    onAddressEditedChanged: (Boolean) -> Unit,
    onAddressQueryChanged: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onReload: () -> Unit,
    onStop: () -> Unit,
    onExpandCapsule: () -> Unit,
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
    // container colour change: the toolbar already stacks two surface
    // tones (toolbar on background, pill on toolbar) and a third would
    // read as mud on the dark scheme. The colour animates with the
    // theme's default effects spring so it fades in rather than pops.
    val outline by animateColorAsState(
        targetValue = if (addressFocused) colors.primary else Color.Transparent,
        label = "addressOutline",
    )

    // Drawn (not laid out) pill geometry: the bubble follows the capsule
    // through both morphs while the Box that owns the touches keeps its
    // height — and, once the flanking controls are gone, widens into the
    // gutters they left behind ([capsuleGutterHandover]) while the bubble
    // is drawn inset by exactly what the box gained.
    val pillHeight = addressPillHeight(collapse, editProgress)
    val pillSideInset = addressPillSideInset(collapse)
    val pillSideInsetPx = with(LocalDensity.current) { pillSideInset.roundToPx() }
    val pillFill = colors.surfaceContainerHighest

    // How much of themselves the pill's own two slots still have — see
    // [capsulePillSlotScale] for why this one is the collapse alone.
    val slotScale = capsulePillSlotScale(collapse)

    Box(
        modifier = modifier
            .height(AddressFieldTouchHeight)
            .onGloballyPositioned { coords ->
                val r = coords.boundsInWindow()
                // The *drawn* pill's bounds, so the long-press menu keeps
                // anchoring on the bubble rather than on the touch box
                // around it.
                pillBounds = IntRect(
                    r.left.toInt() + pillSideInsetPx, r.top.toInt(),
                    r.right.toInt() - pillSideInsetPx, r.bottom.toInt(),
                )
            }
            .drawBehind {
                val h = pillHeight.toPx()
                val inset = pillSideInset.toPx()
                // Centred in the touch box, which is itself centred in
                // the capsule — so the bubble, the capsule and the text
                // share one centre line in every state.
                val top = (size.height - h) / 2f
                drawRoundRect(
                    color = pillFill,
                    topLeft = Offset(inset, top),
                    size = Size(size.width - inset * 2f, h),
                    cornerRadius = CornerRadius(h / 2f),
                )
                if (outline.alpha > 0f) {
                    val stroke = 1.5.dp.toPx()
                    drawRoundRect(
                        color = outline,
                        topLeft = Offset(inset + stroke / 2f, top + stroke / 2f),
                        size = Size(size.width - inset * 2f - stroke, h - stroke),
                        cornerRadius = CornerRadius((h - stroke) / 2f),
                        style = Stroke(width = stroke),
                    )
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
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
                        // Asymmetric at rest (16 dp of start padding
                        // against the trailing slot's own 32 dp),
                        // symmetric when compact — which is what lets
                        // the label centre in the capsule rather than in
                        // a box that is off-centre to begin with. Both
                        // ends are measured from the *capsule's* edge
                        // (see [capsuleLabelInset]), so the label sits
                        // where it always did while the box underneath it
                        // widens into the gutters.
                        .padding(
                            start = addressLabelPadding(
                                collapse,
                                if (badge != null) AddressPillLabelInsetWithBadge
                                else AddressPillLabelInset,
                            ),
                            end = addressLabelPadding(collapse, AddressPillTrailingInset),
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The badge (and the gap after it) is one slot that
                    // retreats into the pill's leading edge as the bar
                    // collapses — the compact capsule shows the domain
                    // and nothing else. Zero-width means not composed,
                    // so it can't take a tap meant for the label.
                    if (badge != null && slotScale > 0f) {
                        Row(
                            modifier = Modifier
                                .collapsingControl(slotScale, towardsStart = true),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Image(
                                painter = painterResource(badge.drawableRes),
                                contentDescription = badge.contentDescription,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(8.dp))
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
                    // The label is derived from the tab's committed
                    // address, never from the edit buffer: a bold bare
                    // domain is the capsule asserting "this is the site
                    // you are on", and only a loaded (or just-submitted)
                    // URL earns that.
                    val label = if (addressFocused) "" else restingLabel
                    // Blank label at rest means a blank address (the
                    // home tab) — [AddressLabel.resting] passes
                    // everything else through — so the placeholder is
                    // the right thing to draw underneath.
                    val showPlaceholder =
                        if (addressFocused) fieldValue.text.isEmpty() else label.isEmpty()
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
                            )
                        } else if (label.isNotEmpty()) {
                            Text(
                                text = label,
                                color = colors.onSurface,
                                fontWeight = FontWeight.Medium,
                                // One type size down at full collapse,
                                // interpolated on the way there so the
                                // label morphs with the capsule.
                                fontSize = addressLabelFontSize(collapse),
                                // Start-aligned at rest, centred in the
                                // compact capsule (whose label padding
                                // is symmetric by then), and *slid*
                                // between the two rather than switched:
                                // a bias of -1 is the start edge and 0
                                // the centre, so the label travels with
                                // the narrowing capsule instead of
                                // jumping the moment the collapse
                                // begins. The Text still wraps its own
                                // width inside the same box, so the
                                // middle ellipsis measures against
                                // exactly what it did before.
                                modifier = Modifier.align(BiasAlignment(-1f + collapse, 0f)),
                                maxLines = 1,
                                // Middle, not tail: a name too long for
                                // the pill is almost always an ENS
                                // subname chain, and its *tail* is the
                                // part that says who is being trusted.
                                // `long.prefix.attacker.eth` tail-
                                // ellipsised reads `long.prefix…`, which
                                // is exactly the half an attacker gets to
                                // choose; eliding the middle keeps the
                                // parent name and TLD on screen.
                                overflow = TextOverflow.MiddleEllipsis,
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
                    // The one thing that moves it is the collapse: the
                    // slot retreats into the pill's trailing edge along
                    // with the badge, because a minimised bar that is
                    // the domain *and a button* is neither minimised nor
                    // centred. Nothing is lost — the tap that brings the
                    // bar back brings Reload/Stop back with it, the same
                    // way it restores Back, the tab counter and the
                    // menu — and the load's own progress keeps being
                    // drawn on the compact capsule's edge throughout.
                    //
                    // Which control: see [capsuleTrailingControl]. The
                    // `addressBarEdited` guard inside it matters once the
                    // user submits — submit() resets that flag (to
                    // dismiss the suggestions panel) but intentionally
                    // leaves focus alone, so without the check the ×
                    // would stay visible while the page is already
                    // loading; now that slot correctly becomes Stop.
                    val trailing = capsuleTrailingControl(
                        addressFocused = addressFocused,
                        addressBarEdited = addressBarEdited,
                        editBufferEmpty = fieldValue.text.isEmpty(),
                        loading = loading,
                        // Reload needs something to reload: either a
                        // loaded page or a committed address. Mirrors
                        // the guard on [BrowserScreen]'s onReload.
                        canReload = state.url.isNotBlank() ||
                            state.addressBarText.isNotBlank(),
                    )
                    if (slotScale > 0f) {
                        Box(
                            modifier = Modifier
                                .collapsingControl(slotScale, towardsStart = false)
                                .size(CapsuleTrailingSlotSize),
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
                                        // × is a "start over" gesture — drop
                                        // the suggestions panel and wait for
                                        // the next keystroke before showing
                                        // it again.
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
        // label's own layout box. The pill's paddings are part of the
        // pill the user is aiming at, and a press that landed in one used
        // to fall through to the text field underneath: on a compact
        // capsule that opened the editor and the keyboard on what should
        // have been the first, expand-only tap, and it raised raw text
        // selection on what should have been the URL-actions long-press.
        // The one thing the surface stands back from is the trailing
        // control slot, which has its own button to answer for Reload /
        // Stop / Clear; once that slot has retreated into the pill's edge
        // (`slotScale == 0`, i.e. the compact bar) — or as soon as the tap
        // means Expand, whichever comes first, see
        // [capsuleTapSurfaceStandback] — there is nothing to stand back
        // from and the surface runs the full width — which,
        // together with [capsuleGutterHandover] widening the box into the
        // capsule's gutters, makes every pixel of the compact pill answer
        // the tap.
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
                    // What the trailing control still owns: its own slot
                    // plus the air between it and the pill's edge — and
                    // nothing at all once the tap means Expand, so that
                    // strip can't fall through to the field underneath.
                    // See [capsuleTapSurfaceStandback].
                    .padding(end = capsuleTapSurfaceStandback(collapse, addressFocused))
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
 * One icon in the address pill's trailing slot. Every occupant is built
 * the same way — 32 dp button, 18 dp glyph — so swapping between Clear,
 * Stop and Reload changes only which vector is drawn, never a metric.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CapsuleTrailingButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    tint: Color = LocalContentColor.current,
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
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * Hamburger button plus its popup menu. The node-status dot sits on
 * the button's corner: with the chrome at the bottom there is no spare
 * top-right corner to park it in, and the menu's "N peers" row is
 * where the user goes to act on it anyway.
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
        ) {
            Icon(Icons.Filled.Menu, contentDescription = "Menu")
        }
        NodeStatusDot(
            nodeInfo = nodeInfo,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = (-8).dp, y = 8.dp),
        )
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

@Composable
private fun NodeStatusDot(
    nodeInfo: NodeInfo,
    modifier: Modifier = Modifier,
) {
    val color = when (nodeInfo.status) {
        NodeStatus.Running -> Color(0xFF22C55E)
        NodeStatus.Starting -> Color(0xFFF59E0B)
        NodeStatus.Stopped -> Color(0xFF94A3B8)
        NodeStatus.Error -> Color(0xFFEF4444)
    }
    Box(
        modifier = modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color),
    )
}
