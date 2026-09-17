package baby.freedom.mobile.browser

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
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
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import baby.freedom.swarm.NodeInfo
import baby.freedom.swarm.NodeStatus

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
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(CapsuleHeight),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = CAPSULE_ALPHA),
        // Just enough shadow to lift the capsule off the page without
        // the "heavy shadow" the brief rules out.
        shadowElevation = 3.dp,
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
            if (state.canGoBack) {
                // Expressive shape variants: the icon buttons morph from
                // round to a squarer pressed shape on touch. Purely
                // visual — the 48 dp hit target and click handlers are
                // unchanged.
                IconButton(onClick = onBack, shapes = IconButtonDefaults.shapes()) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }

            AddressField(
                state = state,
                addressFocused = addressFocused,
                addressBarEdited = addressBarEdited,
                onAddressFocusChanged = onAddressFocusChanged,
                onAddressEditedChanged = onAddressEditedChanged,
                onSubmit = onSubmit,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp),
            )

            TabsCountButton(count = tabCount, onClick = onOpenTabs)

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

/**
 * The address pill. Hand-built around [BasicTextField] rather than M3's
 * `TextField` / `SearchBar`: their content padding shifts by a couple
 * of dp between focused and unfocused, which makes the pill appear to
 * grow when tapped, and the search bar wants to own the whole screen
 * on expansion. A fixed-height Box gives us a rock-steady 40 dp bubble
 * that lives comfortably inside the 56 dp capsule.
 */
@Composable
private fun AddressField(
    state: BrowserState,
    addressFocused: Boolean,
    addressBarEdited: Boolean,
    onAddressFocusChanged: (Boolean) -> Unit,
    onAddressEditedChanged: (Boolean) -> Unit,
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    // Local [TextFieldValue] so we can steer the selection (e.g. select
    // all on focus). We keep it in sync with [state.addressBarText],
    // which is the source of truth for submit / external updates
    // (navigation events, ENS resolution).
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
        if (fieldValue.text != state.addressBarText) {
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

    // Select-all on focus, park cursor at 0 on focus loss.
    //
    // Running the select-all in a LaunchedEffect (rather than from
    // `onFocusChanged`) makes sure we apply *after* any tap-to-place-
    // cursor selection the framework might set during the focus-
    // granting gesture — otherwise the cursor can land wherever the
    // user happened to tap inside the pill.
    LaunchedEffect(addressFocused) {
        fieldValue = if (addressFocused && fieldValue.text.isNotEmpty()) {
            fieldValue.copy(selection = TextRange(0, fieldValue.text.length))
        } else {
            fieldValue.copy(selection = TextRange.Zero)
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

    Box(
        modifier = modifier
            .height(40.dp)
            .clip(CircleShape)
            .background(colors.surfaceContainerHighest)
            .border(width = 1.5.dp, color = outline, shape = CircleShape),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = fieldValue,
            onValueChange = { newValue ->
                val textChanged = newValue.text != fieldValue.text
                fieldValue = newValue
                if (textChanged) {
                    state.addressBarText = newValue.text
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
                    val restingLabel =
                        if (addressFocused) "" else AddressLabel.resting(fieldValue.text)
                    Box(modifier = Modifier.weight(1f)) {
                        Box(
                            modifier = Modifier.graphicsLayer {
                                alpha = if (restingLabel.isEmpty()) 1f else 0f
                            },
                        ) {
                            innerTextField()
                        }
                        if (fieldValue.text.isEmpty()) {
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
                                overflow = TextOverflow.Ellipsis,
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
                                state.addressBarText = ""
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
