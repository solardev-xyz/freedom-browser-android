package baby.freedom.mobile.browser

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import baby.freedom.swarm.NodeInfo
import baby.freedom.swarm.NodeStatus
import kotlin.math.roundToInt

/**
 * Height of the floating capsule. The brief allows 52–58 dp; 56 dp is
 * the one value in that band that fits a stock 48 dp [IconButton] with
 * a symmetric 4 dp of breathing room above and below, so every control
 * keeps its full Material touch target without the capsule growing a
 * visible gutter.
 */
internal val CapsuleHeight = 56.dp

/** Side margin between the capsule and the screen edge (brief: 12–16 dp). */
internal val CapsuleSideMargin = 14.dp

/** Gap between the capsule and the navigation/gesture inset (brief: 8–12 dp). */
internal val CapsuleBottomMargin = 10.dp

/**
 * Capsule background opacity. Low enough that the page reads through as
 * a faint wash (so the chrome sits *over* the page rather than
 * replacing a strip of it), high enough that `onSurface` text stays
 * legible over both a white article and a dark hero image.
 */
private const val CAPSULE_ALPHA = 0.90f

/**
 * The browser chrome: a floating, semi-transparent capsule layered over
 * an edge-to-edge page, holding (left to right) the Back control (only
 * while there's history to pop), the address field with its protocol
 * badge, the tab counter and the overflow menu. Home moved into that
 * menu — on a page you can reach it in one extra tap, and the resting
 * bar stays low-density the way the brief asks.
 *
 * The capsule is a `surfaceContainer` pill at [CAPSULE_ALPHA] with a
 * low shadow, not an opaque full-width bar; the address field is a
 * second, darker pill inside it (`surfaceContainerHighest`) that grows
 * a primary-coloured outline while focused. Layout is fixed-height so
 * nothing shifts when focus, the clear (×) button, the Back control or
 * the protocol badge come and go.
 *
 * [collapseFraction] drives the compact-on-scroll state (0 = resting,
 * 1 = compact): the capsule interpolates down to
 * [CapsuleCompactHeight] and the secondary controls collapse out of
 * the row, leaving the domain. It is pure geometry — sizes and
 * positions interpolate, nothing cross-fades — and the layout *slot*
 * stays [CapsuleHeight] tall throughout, so the capsule shrinks within
 * a frame that never moves and nothing else on screen shifts.
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
    onNewTab: () -> Unit,
    collapseFraction: Float = 0f,
    modifier: Modifier = Modifier,
) {
    // Clamp rather than trust the caller: the collapse is driven by a
    // spring, and the expressive spatial springs overshoot slightly at
    // both ends.
    val collapse = collapseFraction.coerceIn(0f, 1f)
    val capsuleHeight = lerp(CapsuleHeight, CapsuleCompactHeight, collapse)
    // Secondary controls finish collapsing before the capsule finishes
    // shrinking (they're gone by ~⅔ of the way), so the pill always has
    // somewhere to grow into and the compact bar never looks crowded
    // mid-transition.
    val controlScale = (1f - collapse * CONTROL_COLLAPSE_RATE).coerceIn(0f, 1f)

    Box(
        // The slot keeps its full height in both states. The capsule
        // shrinks *inside* it, which is what keeps the page, the
        // progress strip, the snackbar and the IME reserve from moving
        // when the bar collapses — and what lets the controls keep 48 dp
        // touch targets while the capsule around them is 44 dp tall.
        modifier = modifier
            .fillMaxWidth()
            .height(CapsuleHeight),
        contentAlignment = Alignment.Center,
    ) {
        // The capsule itself is background only: drawn at the
        // interpolated height, centred in the slot, with the controls
        // laid out over it rather than inside it.
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(capsuleHeight),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = CAPSULE_ALPHA),
            // Just enough shadow to lift the capsule off the page without
            // the "heavy shadow" the brief rules out.
            shadowElevation = 3.dp,
            content = {},
        )

        // [Surface] used to set this for its content; the content now
        // sits beside it, so state it.
        CompositionLocalProvider(
            LocalContentColor provides MaterialTheme.colorScheme.onSurface,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 4.dp),
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
                // the page or the domain.
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
                    addressFocused = addressFocused,
                    addressBarEdited = addressBarEdited,
                    collapse = collapse,
                    onAddressFocusChanged = onAddressFocusChanged,
                    onAddressEditedChanged = onAddressEditedChanged,
                    onAddressQueryChanged = onAddressQueryChanged,
                    onSubmit = onSubmit,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp),
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
    }
}

/**
 * How much faster the secondary controls collapse than the capsule
 * shrinks. 1.6 puts them at zero width when the capsule is ~⅔ of the
 * way to compact.
 */
private const val CONTROL_COLLAPSE_RATE = 1.6f

/**
 * Collapse a control by interpolating its geometry: the slot it
 * occupies narrows towards zero while the control scales down about
 * the edge it collapses into. No cross-fade — the brief asks for
 * position/size interpolation over opacity, and a control that shrinks
 * into the capsule's edge keeps saying where it went.
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
 * The address pill. Hand-built around [BasicTextField] rather than M3's
 * `TextField` / `SearchBar`: their content padding shifts by a couple
 * of dp between focused and unfocused, which makes the pill appear to
 * grow when tapped, and the search bar wants to own the whole screen
 * on expansion. A fixed-height Box gives us a rock-steady 40 dp bubble
 * that lives comfortably inside the 56 dp capsule.
 *
 * [collapse] (0 at rest, 1 compact) shrinks the *drawn* bubble to
 * [AddressPillCompactHeight] so it keeps its margins inside the
 * compact capsule. The Box itself stays [AddressFieldTouchHeight] tall
 * in both states — the pill is painted at the interpolated height
 * rather than laid out at it — so the compact bar's one remaining
 * control keeps a full-width, 48 dp touch target. The domain label is
 * deliberately *not* interpolated: it is a trust surface and has to
 * read the same either way.
 */
@Composable
private fun AddressField(
    state: BrowserState,
    addressFocused: Boolean,
    addressBarEdited: Boolean,
    collapse: Float,
    onAddressFocusChanged: (Boolean) -> Unit,
    onAddressEditedChanged: (Boolean) -> Unit,
    onAddressQueryChanged: (String) -> Unit,
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
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

    // Drawn (not laid out) pill geometry: the bubble shrinks with the
    // capsule while the Box that owns the touches keeps its height.
    val pillHeight = lerp(AddressPillHeight, AddressPillCompactHeight, collapse)
    val pillFill = colors.surfaceContainerHighest

    Box(
        modifier = modifier
            .height(AddressFieldTouchHeight)
            .drawBehind {
                val h = pillHeight.toPx()
                // Centred in the touch box, which is itself centred in
                // the capsule — so the bubble, the capsule and the text
                // share one centre line in every state.
                val top = (size.height - h) / 2f
                drawRoundRect(
                    color = pillFill,
                    topLeft = Offset(0f, top),
                    size = Size(size.width, h),
                    cornerRadius = CornerRadius(h / 2f),
                )
                if (outline.alpha > 0f) {
                    val stroke = 1.5.dp.toPx()
                    drawRoundRect(
                        color = outline,
                        topLeft = Offset(stroke / 2f, top + stroke / 2f),
                        size = Size(size.width - stroke, h - stroke),
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
                        .padding(
                            start = if (badge != null) 10.dp else 16.dp,
                            end = 4.dp,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (badge != null) {
                        Image(
                            painter = painterResource(badge.drawableRes),
                            contentDescription = badge.contentDescription,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(8.dp))
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
                    val restingLabel =
                        if (addressFocused) "" else AddressLabel.resting(state.addressBarText)
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
                            )
                        } else if (restingLabel.isNotEmpty()) {
                            Text(
                                text = restingLabel,
                                color = colors.onSurface,
                                fontWeight = FontWeight.Medium,
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
                    // Trailing Clear (×) — sized to the pill, never pushes it
                    // taller. Shown only while the user is actively editing
                    // (focused + typed something); the slot is not
                    // reserved otherwise, because the pill is narrower
                    // now that it shares the toolbar with three buttons
                    // and every dp of URL matters while reading. Loading
                    // state is communicated by the wavy progress bar
                    // above the toolbar, so the pill doesn't need its
                    // own spinner.
                    //
                    // The `addressBarEdited` guard matters once the user
                    // submits: submit() resets that flag (to dismiss the
                    // suggestions panel) but intentionally leaves focus
                    // alone, so without this check the × would stay
                    // visible while the page is already loading.
                    if (addressFocused && addressBarEdited && fieldValue.text.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                fieldValue = TextFieldValue("")
                                onAddressQueryChanged("")
                                // × is a "start over" gesture — drop the
                                // suggestions panel and wait for the next
                                // keystroke before showing it again.
                                onAddressEditedChanged(false)
                            },
                            shapes = IconButtonDefaults.shapes(),
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                Icons.Filled.Clear,
                                contentDescription = "Clear",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    } else {
                        Spacer(Modifier.width(8.dp))
                    }
                }
            },
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
                popupPositionProvider = AnchoredAboveEndProvider(anchorBounds!!, popupGapPx),
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
private fun MenuItemLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(end = 32.dp),
    )
}

/**
 * Places a [Popup] [gapPx] above an anchor's *top* edge (it opens
 * upwards, since the anchor lives in the bottom toolbar) and against its right
 * edge (LTR) / left edge (RTL), clamping to the window so the popup
 * never runs off-screen. The anchor bounds are captured by the caller
 * via [Modifier.onGloballyPositioned]; we deliberately ignore the
 * [anchorBounds] argument the framework hands in, since that's the
 * very value that mis-fires on the first open for Material3's default
 * [androidx.compose.material3.DropdownMenu].
 */
private class AnchoredAboveEndProvider(
    private val anchor: IntRect,
    private val gapPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = when (layoutDirection) {
            LayoutDirection.Ltr -> anchor.right - popupContentSize.width
            LayoutDirection.Rtl -> anchor.left
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
