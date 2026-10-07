package baby.freedom.mobile.browser

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Tab
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import baby.freedom.mobile.R
import baby.freedom.mobile.l10n.Strings
import baby.freedom.mobile.ui.PrivateTheme
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Full-screen, Chrome-style tab switcher.
 *
 * A top bar with a *Tabs | Private* toggle (#418) and an × to dismiss;
 * under it "+ New tab" (or "+ New private tab" on the private pane),
 * Reopen and the ⋮ menu; then a 2-column grid of the pane's tab
 * cards. Each card shows its page title, a close (×) button, a speaker
 * to mute / unmute a tab that is playing audio (#91), and a preview
 * thumbnail of the page (or a placeholder when there is no snapshot, or
 * the tab is on its home page).
 *
 * Normal and private tabs (#86) sit in separate panes, as in Chrome and
 * Safari ([privatePane], [onPrivatePaneChange]; the host holds which is
 * shown, since its screenshot guard depends on it). The toggle is only
 * there while private tabs can be opened ([onNewPrivateTab] non-null) or
 * some are open.
 *
 * Long-press a card and drag it to move the tab ([TabsState.moveTab]);
 * long-press and let go opens the tab's menu (Close other tabs, Close
 * tab; #320), whose items are also the card's accessibility actions.
 * Closing a card is handed to [onTabsClosed] with its Undo (#418), as a
 * bulk close is. The header's "Reopen" brings back the most recently
 * closed tab ([TabsState.reopenClosedTab]) while there is one, and its ⋮
 * menu closes every tab of the pane on screen.
 */
@Composable
fun TabSwitcherScreen(
    tabs: TabsState,
    onDismiss: () -> Unit,
    onNewTab: () -> Unit,
    onNewPrivateTab: (() -> Unit)? = null,
    onTabsClosed: (TabsState.BulkClose) -> Unit = {},
    privatePane: Boolean = false,
    onPrivatePaneChange: (Boolean) -> Unit = {},
) {
    // Snapshot the currently-active tab right before we render so the
    // user sees an up-to-date preview of whatever they were last reading.
    LaunchedEffect(Unit) { tabs.captureActiveThumbnail?.invoke() }

    val panes = switcherHasPanes(privateTabsOffered = onNewPrivateTab != null, anyPrivate = tabs.hasPrivateTabs)
    val showPrivate = panes && privatePane
    val paneTabs = paneTabs(tabs.tabs, showPrivate)
    val activeId = tabs.active.id

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (panes) {
                TabPaneToggle(
                    privatePane = showPrivate,
                    onChange = onPrivatePaneChange,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 4.dp, end = 4.dp),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            IconButton(onClick = onDismiss, shapes = IconButtonDefaults.shapes()) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.browser_tabs_close_switcher))
            }
        }
        // The actions wrap onto a second line rather than squeezing each
        // other when they don't fit — a narrow screen or a large font.
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            val newTab: (() -> Unit)? = if (showPrivate) onNewPrivateTab else onNewTab
            if (newTab != null) {
                TextButton(onClick = {
                    newTab()
                    onDismiss()
                }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(
                        stringResource(if (showPrivate) R.string.browser_tabs_new_private_tab else R.string.browser_tabs_new_tab),
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            // Closed private tabs aren't kept (#86): Reopen belongs to
            // the normal pane.
            if (!showPrivate && tabs.canReopenClosedTab) {
                // Pushes Reopen (and ⋮) to the end of its line.
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    tabs.reopenClosedTab()
                    onDismiss()
                }) {
                    Icon(Icons.Filled.Restore, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.browser_tabs_reopen), fontWeight = FontWeight.Medium)
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
            Box {
                var menuOpen by remember { mutableStateOf(false) }
                IconButton(onClick = { menuOpen = true }, shapes = IconButtonDefaults.shapes()) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.browser_tabs_menu))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (showPrivate) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.browser_tabs_close_private)) },
                            enabled = paneTabs.isNotEmpty(),
                            onClick = {
                                menuOpen = false
                                onTabsClosed(tabs.closePrivateTabs())
                            },
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text(stringResource(closeTabsPaneLabel(anyPrivate = tabs.hasPrivateTabs))) },
                            enabled = paneTabs.isNotEmpty(),
                            onClick = {
                                menuOpen = false
                                // The private pane's tabs stay (#418);
                                // with none open this is every tab.
                                onTabsClosed(tabs.closeRegularTabs())
                            },
                        )
                    }
                }
            }
        }

        if (paneTabs.isEmpty()) {
            EmptyState(
                icon = if (showPrivate) PrivateTabIcon else Icons.Filled.Add,
                title = stringResource(if (showPrivate) R.string.browser_tabs_private_empty_title else R.string.browser_tabs_empty_title),
                hint = stringResource(if (showPrivate) R.string.browser_tabs_private_empty_hint else R.string.browser_tabs_empty_hint),
            )
            return@Column
        }

        val gridState = rememberLazyGridState()
        val scope = rememberCoroutineScope()
        val haptics = LocalHapticFeedback.current
        val edgePx = with(LocalDensity.current) { REORDER_EDGE.toPx() }
        val gridStartPx = with(LocalDensity.current) { GRID_PADDING_H.toPx() }
        val reorder = remember(tabs, gridState, gridStartPx, showPrivate) {
            TabReorder(tabs, gridState, gridStartPx, showPrivate)
        }
        // The tab whose menu is open: long-pressed and let go in place.
        var menuTabId by remember { mutableStateOf<Long?>(null) }

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            state = gridState,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(reorder) {
                    reorderGestures(
                        reorder = reorder,
                        onStart = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                        onDrag = { amount ->
                            reorder.drag(amount, scope, edgePx, size.height.toFloat())
                        },
                        onHold = { id -> menuTabId = id },
                    )
                },
            contentPadding = PaddingValues(
                start = GRID_PADDING_H, end = GRID_PADDING_H, top = 4.dp, bottom = 24.dp,
            ),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(
                items = paneTabs,
                key = { _, tab -> tab.id },
            ) { index, tab ->
                val dragged = tab.id == reorder.draggedId
                // Where the tab is in the whole list, which the tab
                // state's own calls take.
                val at = { tabs.tabs.indexOfFirst { it.id == tab.id } }
                TabCard(
                    modifier = if (dragged) {
                        Modifier
                            .zIndex(1f)
                            .graphicsLayer {
                                translationX = reorder.offset.x
                                translationY = reorder.offset.y
                                scaleX = 1.04f
                                scaleY = 1.04f
                                alpha = 0.92f
                            }
                    } else {
                        Modifier.animateItem()
                    },
                    tab = tab,
                    isActive = tab.id == activeId,
                    onClick = {
                        tabs.switchTo(at())
                        onDismiss()
                    },
                    // Its Undo goes on screen (#418).
                    onClose = { onTabsClosed(tabs.closeTab(at(), offerUndo = true)) },
                    onCloseOthers = if (paneTabs.size > 1) {
                        { onTabsClosed(tabs.closeOtherTabsOfItsKind(tab)) }
                    } else {
                        null
                    },
                    menuOpen = menuTabId == tab.id,
                    onMenuDismiss = { menuTabId = null },
                    onToggleMute = tabs.setAudioMuted?.let { set -> { set(tab, !tab.audioMuted) } },
                    // The drag has no TalkBack equivalent, so the same
                    // moves are offered as accessibility actions — within
                    // the pane, as the drag moves it.
                    moveActions = tabMoveTargets(index, paneTabs.size).map { (label, to) ->
                        CustomAccessibilityAction(label) {
                            val target = paneTabs[to].id
                            tabs.moveTab(at(), tabs.tabs.indexOfFirst { it.id == target })
                            true
                        }
                    },
                )
            }
        }
    }
}

/**
 * Whether the switcher splits its tabs into *Tabs* and *Private* panes
 * (#418): while private tabs can be opened, or any is open (so one
 * opened before the WebView lost private support still has a place).
 */
internal fun switcherHasPanes(privateTabsOffered: Boolean, anyPrivate: Boolean): Boolean =
    privateTabsOffered || anyPrivate

/**
 * The *Tabs* pane's close-everything item (#418): *Close all tabs* when
 * it really is all of them, *Close normal tabs* while private tabs are
 * open, since [TabsState.closeRegularTabs] leaves those in their pane.
 */
internal fun closeTabsPaneLabel(anyPrivate: Boolean): Int =
    if (anyPrivate) R.string.browser_tabs_close_normal else R.string.browser_tabs_close_all

/** The tabs of the pane on screen, in their order: the private ones or the normal ones. */
internal fun paneTabs(all: List<BrowserState>, privatePane: Boolean): List<BrowserState> =
    all.filter { it.private == privatePane }

/**
 * The *Tabs | Private* toggle at the top of the switcher (#418): two
 * segments, one selected, each a radio-style choice for TalkBack. The
 * labels may wrap at a large font rather than be cut.
 */
@Composable
private fun TabPaneToggle(
    privatePane: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        val options = listOf(
            false to stringResource(R.string.browser_tabs_pane_tabs),
            true to stringResource(R.string.browser_tabs_pane_private),
        )
        options.forEachIndexed { i, (isPrivate, label) ->
            SegmentedButton(
                selected = privatePane == isPrivate,
                onClick = { onChange(isPrivate) },
                shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size),
                // The app's teal, as the switcher's other actions, rather
                // than the scheme's (amber) secondary container.
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                    activeContentColor = MaterialTheme.colorScheme.primary,
                    activeBorderColor = MaterialTheme.colorScheme.outline,
                ),
                icon = {
                    Icon(
                        if (isPrivate) PrivateTabIcon else Icons.Outlined.Tab,
                        contentDescription = null,
                        modifier = Modifier.size(SegmentedButtonDefaults.IconSize),
                    )
                },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                // One line, shrinking to fit a narrow screen at a large
                // font rather than breaking "Private" mid-word.
                Text(
                    label,
                    maxLines = 1,
                    autoSize = TextAutoSize.StepBased(
                        minFontSize = 12.sp,
                        maxFontSize = LocalTextStyle.current.fontSize,
                    ),
                )
            }
        }
    }
}

/**
 * The accessibility actions that move the tab at [index] in a list of
 * [count] tabs, as (label, target index) — the non-gesture way to do
 * what dragging a card does. Only moves that go somewhere are listed.
 */
internal fun tabMoveTargets(index: Int, count: Int): List<Pair<String, Int>> = buildList {
    if (index !in 0 until count) return@buildList
    if (index > 0) {
        add(Strings.get(R.string.browser_tabs_move_earlier) to index - 1)
        if (index > 1) add(Strings.get(R.string.browser_tabs_move_to_start) to 0)
    }
    if (index < count - 1) {
        add(Strings.get(R.string.browser_tabs_move_later) to index + 1)
        if (index < count - 2) add(Strings.get(R.string.browser_tabs_move_to_end) to count - 1)
    }
}

/** The switcher grid's side padding. */
private val GRID_PADDING_H = 12.dp

/** How close to the grid's top or bottom a dragged card scrolls it. */
private val REORDER_EDGE = 56.dp

/** Most the grid scrolls per frame while a card is held at its edge. */
private const val REORDER_SCROLL_STEP = 24f

/**
 * Long-press drag-to-reorder for the switcher grid.
 *
 * The held card follows the finger ([offset], applied as a translation
 * on top of its slot). Whenever the card's centre crosses into another
 * card's slot the tab moves there ([TabsState.moveTab]) and [offset] is
 * rebased onto the new slot, so the card stays under the finger while
 * the rest of the grid animates around it. Held near the top or bottom
 * edge, the grid scrolls so a tab can be carried past the screenful.
 *
 * Positions come from the grid's own layout info, whose item offsets
 * are measured inside the content padding: along the scroll axis
 * `viewportStartOffset` (minus the top padding) converts them into the
 * grid's pointer space, across it the side padding ([startPaddingPx])
 * has to be added back.
 */
private class TabReorder(
    private val tabs: TabsState,
    private val grid: LazyGridState,
    private val startPaddingPx: Float,
    /** The pane shown (#418): the grid holds only its tabs. */
    private val privatePane: Boolean,
) {
    var draggedId: Long? by mutableStateOf(null)
        private set
    var offset: Offset by mutableStateOf(Offset.Zero)
        private set

    private var autoScroll: Job? = null
    private var scrollStep = 0f

    /**
     * Where the held card is drawn, in grid pointer space: its slot at
     * pick-up plus every finger move since. Moves and auto-scroll change
     * the slot *and* rebase [offset] by the same amount, so this only
     * ever follows the finger — and unlike slot + [offset] it's never
     * read against a layout that hasn't caught up with a move yet.
     */
    private var cardTopLeft = Offset.Zero
    private var cardSize = Offset.Zero

    private fun topLeft(item: LazyGridItemInfo): Offset = Offset(
        item.offset.x + startPaddingPx,
        (item.offset.y - grid.layoutInfo.viewportStartOffset).toFloat(),
    )

    private fun contains(item: LazyGridItemInfo, point: Offset): Boolean {
        val tl = topLeft(item)
        return point.x >= tl.x && point.x < tl.x + item.size.width &&
            point.y >= tl.y && point.y < tl.y + item.size.height
    }

    private fun draggedItem(): LazyGridItemInfo? =
        grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == draggedId }

    fun start(at: Offset): Boolean {
        val item = grid.layoutInfo.visibleItemsInfo.firstOrNull { contains(it, at) }
            ?: return false
        draggedId = item.key as? Long ?: return false
        offset = Offset.Zero
        cardTopLeft = topLeft(item)
        cardSize = Offset(item.size.width.toFloat(), item.size.height.toFloat())
        return true
    }

    fun drag(amount: Offset, scope: CoroutineScope, edgePx: Float, heightPx: Float) {
        offset += amount
        cardTopLeft += amount
        moveIfOverAnother()
        val top = cardTopLeft.y
        val bottom = top + cardSize.y
        scrollStep = when {
            top < edgePx -> -REORDER_SCROLL_STEP * ((edgePx - top) / edgePx).coerceIn(0f, 1f)
            bottom > heightPx - edgePx ->
                REORDER_SCROLL_STEP * ((bottom - heightPx + edgePx) / edgePx).coerceIn(0f, 1f)
            else -> 0f
        }
        if (scrollStep != 0f && autoScroll?.isActive != true) {
            autoScroll = scope.launch {
                while (isActive && draggedId != null && scrollStep != 0f) {
                    // A re-pin from [moveIfOverAnother]
                    // (`requestScrollToItem`) cancels the scroll in
                    // flight; that ends this step, not the auto-scroll
                    // — unless it is this job itself being cancelled.
                    val scrolled = try {
                        grid.scrollBy(scrollStep)
                    } catch (e: CancellationException) {
                        if (!isActive) throw e
                        0f
                    }
                    // So a step can scroll 0 without the grid being at
                    // its end — only the end itself stops the loop.
                    val canGoOn = if (scrollStep > 0f) grid.canScrollForward else grid.canScrollBackward
                    if (scrolled == 0f && !canGoOn) break
                    // The slot scrolled with the content; keep the card
                    // where the finger is.
                    offset += Offset(0f, scrolled)
                    moveIfOverAnother()
                    delay(16)
                }
            }
        }
    }

    private fun moveIfOverAnother() {
        val item = draggedItem() ?: return
        val from = tabs.tabs.indexOfFirst { it.id == draggedId }
        // The grid hasn't laid out the last move yet: its slots are
        // stale, so neither the target nor the rebase below would be
        // right. The next move or scroll step asks again. The grid
        // holds only the pane's tabs, so its index is the pane's.
        if (item.index != paneTabs(tabs.tabs, privatePane).indexOfFirst { it.id == draggedId }) return
        val centre = cardTopLeft + cardSize / 2f
        val target = grid.layoutInfo.visibleItemsInfo
            .firstOrNull { it.key != draggedId && contains(it, centre) }
            ?: return
        // The grid keeps its first visible item pinned across a
        // reorder; if that item is one of the two moving, it would
        // scroll the grid along with it. Re-pin to the current
        // position instead.
        val first = grid.firstVisibleItemIndex
        val firstOffset = grid.firstVisibleItemScrollOffset
        val to = tabs.tabs.indexOfFirst { it.id == target.key }
        if (from < 0 || to < 0) return
        offset += topLeft(item) - topLeft(target)
        tabs.moveTab(from, to)
        if (from == first || to == first) {
            // requestScrollToItem applies on the next measure without
            // suspending, so the pin lands in the same frame as the move.
            grid.requestScrollToItem(first, firstOffset)
        }
    }

    fun end() {
        autoScroll?.cancel()
        autoScroll = null
        scrollStep = 0f
        draggedId = null
        offset = Offset.Zero
    }
}

/**
 * Long-press, then drag, on the switcher grid.
 *
 * Not `detectDragGesturesAfterLongPress`: that listens in the main
 * pass, where the grid's own scroll handling and each card's
 * `clickable` sit *inside* this modifier and see every move first —
 * on the AVD the long press registered and not one move reached the
 * reorder. Once the long press has landed on a card, this reads the
 * held pointer in the initial pass and consumes it there, so the grid
 * doesn't scroll under the drag and lifting the finger doesn't also
 * count as a tap that opens the tab. Before the long press nothing is
 * consumed: a quick tap still opens a tab, a quick swipe still scrolls.
 * A long press let go without moving is a hold: [onHold] opens that
 * tab's menu.
 */
private suspend fun PointerInputScope.reorderGestures(
    reorder: TabReorder,
    onStart: () -> Unit,
    onDrag: (Offset) -> Unit,
    onHold: (tabId: Long) -> Unit,
) = awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false)
    val press = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
    if (!reorder.start(press.position)) return@awaitEachGesture
    val held = reorder.draggedId ?: return@awaitEachGesture
    onStart()
    val hold = HoldCheck(viewConfiguration.touchSlop)
    var lifted = false
    try {
        val pointer = press.id
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == pointer } ?: break
            val delta = change.positionChange()
            change.consume()
            if (!change.pressed) {
                lifted = true
                break
            }
            hold.move(delta)
            if (delta != Offset.Zero) onDrag(delta)
        }
    } finally {
        reorder.end()
    }
    if (lifted && hold.isHold) onHold(held)
}

/**
 * Whether a long press let go was a hold (open the tab's menu) or a drag
 * (#320): a hold if the finger never left the touch [slop] around where
 * the press landed. Farthest reach, not net travel — dragging a card out
 * and back (swapping it, then swapping it back) and lifting near the
 * start is still a drag.
 */
internal class HoldCheck(private val slop: Float) {
    private var travel = Offset.Zero

    var isHold = true
        private set

    fun move(delta: Offset) {
        travel += delta
        if (travel.getDistance() >= slop) isHold = false
    }
}

@Composable
private fun TabCard(
    tab: BrowserState,
    isActive: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit,
    onToggleMute: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onCloseOthers: (() -> Unit)? = null,
    menuOpen: Boolean = false,
    onMenuDismiss: () -> Unit = {},
    moveActions: List<CustomAccessibilityAction> = emptyList(),
) = PrivateTheme(tab.private) {
    val borderColor = if (isActive) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    val borderWidth = if (isActive) 2.dp else 1.dp
    val currentTabState = stringResource(R.string.browser_tabs_current_tab)
    val closeTabLabel = stringResource(R.string.browser_tabs_close_tab)
    val closeOthersLabel = stringResource(R.string.browser_tabs_close_others)
    val muteLabel = stringResource(if (tab.audioMuted) R.string.browser_tabs_unmute_tab else R.string.browser_tabs_mute_tab)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(0.78f)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = borderWidth,
                color = borderColor,
                shape = MaterialTheme.shapes.large,
            )
            .clickable(onClickLabel = stringResource(R.string.browser_tabs_switch_to_tab)) { onClick() }
            // Which card is the tab on screen, said rather than only
            // drawn as the thicker border; and Close (and Mute) as
            // actions on the card itself, so TalkBack users needn't hunt
            // for the small buttons in its header (#279).
            .semantics {
                selected = isActive
                if (isActive) stateDescription = currentTabState
                customActions = listOfNotNull(
                    CustomAccessibilityAction(closeTabLabel) { onClose(); true },
                    onCloseOthers?.let { closeOthers ->
                        CustomAccessibilityAction(closeOthersLabel) { closeOthers(); true }
                    },
                    onToggleMute?.takeIf { tab.playingAudio || tab.audioMuted }?.let { toggle ->
                        CustomAccessibilityAction(muteLabel) {
                            toggle(); true
                        }
                    },
                ) + moveActions
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(start = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val title = tab.title.ifBlank {
                tab.url.ifBlank {
                    if (tab.private) {
                        stringResource(R.string.browser_tabs_private_tab)
                    } else {
                        stringResource(R.string.browser_tabs_new_tab)
                    }
                }
            }
            if (tab.private) {
                Icon(
                    PrivateTabIcon,
                    contentDescription = stringResource(R.string.browser_tabs_private),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.size(6.dp))
            }
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (tab.playingAudio || tab.audioMuted) {
                TabAudioButton(tab.audioMuted, onToggleMute)
            }
            IconButton(
                onClick = onClose,
                shapes = IconButtonDefaults.shapes(),
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = closeTabLabel,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(MaterialTheme.colorScheme.background),
        ) {
            // A tab with no page yet (its home page, or a first load
            // still on its way) has a snapshot of the blank document
            // under the overlay — a white rectangle, not what the user
            // saw (#418): its placeholder stands in.
            val thumb = tab.thumbnail?.takeUnless { tab.url.isBlank() }
            if (thumb != null) {
                Image(
                    bitmap = thumb,
                    contentDescription = null,
                    contentScale = ContentScale.FillWidth,
                    alignment = Alignment.TopCenter,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                ThumbnailPlaceholder(tab)
            }
        }
        // The tab's own menu (#320), from a long press let go in place;
        // anchored to the card.
        DropdownMenu(expanded = menuOpen, onDismissRequest = onMenuDismiss) {
            if (onCloseOthers != null) {
                DropdownMenuItem(
                    text = { Text(closeOthersLabel) },
                    onClick = {
                        onMenuDismiss()
                        onCloseOthers()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(closeTabLabel) },
                onClick = {
                    onMenuDismiss()
                    onClose()
                },
            )
        }
    }
}

/**
 * The card's audio indicator (#91): a speaker while the tab's page is
 * audible, a struck-out one while the tab is muted (shown whether or not
 * the page is playing right now, so a muted tab can always be unmuted).
 * A tap toggles the mute; without [onToggleMute] (a WebView that can't
 * mute) it is only an indicator.
 */
@Composable
private fun TabAudioButton(muted: Boolean, onToggleMute: (() -> Unit)?) {
    val icon = if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp
    val tint = if (muted) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.primary
    }
    if (onToggleMute == null) {
        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = if (muted) stringResource(R.string.browser_tabs_muted) else stringResource(R.string.browser_tabs_playing_audio),
                tint = tint,
                modifier = Modifier.size(16.dp),
            )
        }
        return
    }
    IconButton(
        onClick = onToggleMute,
        shapes = IconButtonDefaults.shapes(),
        modifier = Modifier.size(36.dp),
    ) {
        Icon(
            icon,
            contentDescription = if (muted) stringResource(R.string.browser_tabs_unmute_tab) else stringResource(R.string.browser_tabs_mute_tab),
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun ThumbnailPlaceholder(tab: BrowserState) {
    val letter = firstLetterFor(tab)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            if (letter == "•") {
                // Nothing to take a letter from: the home page (#418).
                Icon(
                    if (tab.private) PrivateTabIcon else Icons.Outlined.Home,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            } else {
                Text(
                    letter,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                )
            }
        }
    }
}

private fun firstLetterFor(tab: BrowserState): String {
    val source = tab.title.ifBlank { tab.url }
    if (source.isBlank()) return "•"
    // Strip scheme and any leading `www.`.
    val stripped = source
        .substringAfter("://")
        .removePrefix("www.")
    val ch = stripped.firstOrNull { it.isLetterOrDigit() } ?: return "•"
    return ch.uppercase()
}

/**
 * The tabs-count badge that lives in the bottom toolbar's trailing round
 * button. Tapping it opens the switcher. Renders as a bordered square
 * with the tab count inside.
 *
 * Nineteen dp across with a 1.2 dp stroke, down from 22 and 1.5: the
 * badge now sits inside a 44 dp circle of its own rather than loose on a
 * bar, and the split-bar mockup draws it at 58 px square with a 3.5 px
 * outline at 3× density (19.3 dp / 1.17 dp). Lighter and smaller, so the
 * circle around it reads as the control and the badge as its content.
 *
 * The count is the one piece of type in the chrome that does *not* take
 * the system font scale: it is an icon's fill, not text the user reads a
 * sentence of, and the square it fills is a fixed 19 dp. Sized in `sp`
 * it overflowed its own outline at `font_scale 2.0` (seen on the AVD);
 * sized off the density it stays the same fraction of the badge at every
 * accessibility setting, while the address label beside it scales as it
 * always has.
 */
@Composable
fun TabsCountButton(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val stroke = MaterialTheme.colorScheme.onSurface
    val density = LocalDensity.current
    IconButton(
        onClick = onClick,
        shapes = IconButtonDefaults.shapes(),
        // The drawn digit alone is all TalkBack had to read ("1"): name
        // the control and say the count in words (#279).
        modifier = modifier.semantics { contentDescription = tabsCountDescription(count) },
    ) {
        Box(
            modifier = Modifier
                .clearAndSetSemantics {}
                .size(19.dp)
                .clip(RoundedCornerShape(5.dp))
                .border(
                    width = 1.2.dp,
                    color = stroke,
                    shape = RoundedCornerShape(5.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (count > 99) "∞" else count.toString(),
                color = stroke,
                style = MaterialTheme.typography.labelSmall,
                fontSize = with(density) { 11.dp.toSp() },
                lineHeight = with(density) { 13.dp.toSp() },
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** Spoken label of [TabsCountButton]: what it opens and how many tabs there are. */
internal fun tabsCountDescription(count: Int): String =
    Strings.plural(R.plurals.browser_tabs_count_description, count, count)
