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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
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
 * A top bar with "+ New tab" and an × to dismiss, followed by a 2-column
 * grid of tab cards. Each card shows its page title, a close (×) button,
 * a speaker to mute / unmute a tab that is playing audio (#91), and a
 * preview thumbnail of the page (or a letter placeholder when no
 * snapshot has been captured yet).
 *
 * Long-press a card and drag it to move the tab ([TabsState.moveTab]);
 * the header's "Reopen" brings back the most recently closed tab
 * ([TabsState.reopenClosedTab]) while there is one.
 *
 * "Private" opens a private tab (#86) — offered only where the WebView
 * can run them ([onNewPrivateTab] non-null) — and private tabs' cards
 * wear the private scheme and mark.
 */
@Composable
fun TabSwitcherScreen(
    tabs: TabsState,
    onDismiss: () -> Unit,
    onNewTab: () -> Unit,
    onNewPrivateTab: (() -> Unit)? = null,
) {
    // Snapshot the currently-active tab right before we render so the
    // user sees an up-to-date preview of whatever they were last reading.
    LaunchedEffect(Unit) { tabs.captureActiveThumbnail?.invoke() }

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
            // Top: the × stays on the first line when the actions wrap
            // (every control here is a 48dp touch target, so one line
            // is centred either way).
            verticalAlignment = Alignment.Top,
        ) {
            // The actions wrap onto a second line rather than squeezing
            // each other (or the ×) when they don't fit — a narrow
            // screen or a large font with Private and Reopen both shown.
            // The × sits outside the flow, so it's always there.
            FlowRow(
                modifier = Modifier.weight(1f),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = {
                    onNewTab()
                    onDismiss()
                }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("New tab", fontWeight = FontWeight.Medium, softWrap = false)
                }
                if (onNewPrivateTab != null) {
                    TextButton(onClick = {
                        onNewPrivateTab()
                        onDismiss()
                    }) {
                        Icon(PrivateTabIcon, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Private", fontWeight = FontWeight.Medium, softWrap = false)
                    }
                }
                if (tabs.canReopenClosedTab) {
                    // Pushes Reopen to the end of its line.
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        tabs.reopenClosedTab()
                        onDismiss()
                    }) {
                        Icon(Icons.Filled.Restore, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Reopen", fontWeight = FontWeight.Medium, softWrap = false)
                    }
                }
            }
            IconButton(onClick = onDismiss, shapes = IconButtonDefaults.shapes()) {
                Icon(Icons.Filled.Close, contentDescription = "Close tab switcher")
            }
        }

        val gridState = rememberLazyGridState()
        val scope = rememberCoroutineScope()
        val haptics = LocalHapticFeedback.current
        val edgePx = with(LocalDensity.current) { REORDER_EDGE.toPx() }
        val gridStartPx = with(LocalDensity.current) { GRID_PADDING_H.toPx() }
        val reorder = remember(tabs, gridState, gridStartPx) {
            TabReorder(tabs, gridState, gridStartPx)
        }

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
                    )
                },
            contentPadding = PaddingValues(
                start = GRID_PADDING_H, end = GRID_PADDING_H, top = 4.dp, bottom = 24.dp,
            ),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(
                items = tabs.tabs,
                key = { _, tab -> tab.id },
            ) { index, tab ->
                val dragged = tab.id == reorder.draggedId
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
                    isActive = index == tabs.activeIndex,
                    onClick = {
                        tabs.switchTo(index)
                        onDismiss()
                    },
                    onClose = { tabs.closeTab(index) },
                    onToggleMute = tabs.setAudioMuted?.let { set -> { set(tab, !tab.audioMuted) } },
                    // The drag has no TalkBack equivalent, so the same
                    // moves are offered as accessibility actions.
                    moveActions = tabMoveTargets(index, tabs.tabs.size).map { (label, to) ->
                        CustomAccessibilityAction(label) {
                            tabs.moveTab(index, to)
                            true
                        }
                    },
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
        add("Move tab earlier" to index - 1)
        if (index > 1) add("Move tab to start" to 0)
    }
    if (index < count - 1) {
        add("Move tab later" to index + 1)
        if (index < count - 2) add("Move tab to end" to count - 1)
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
        // right. The next move or scroll step asks again.
        if (item.index != from) return
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
 */
private suspend fun PointerInputScope.reorderGestures(
    reorder: TabReorder,
    onStart: () -> Unit,
    onDrag: (Offset) -> Unit,
) = awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false)
    val press = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
    if (!reorder.start(press.position)) return@awaitEachGesture
    onStart()
    try {
        val pointer = press.id
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == pointer } ?: break
            val delta = change.positionChange()
            change.consume()
            if (!change.pressed) break
            if (delta != Offset.Zero) onDrag(delta)
        }
    } finally {
        reorder.end()
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
    moveActions: List<CustomAccessibilityAction> = emptyList(),
) = PrivateTheme(tab.private) {
    val borderColor = if (isActive) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    val borderWidth = if (isActive) 2.dp else 1.dp

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
            .clickable(onClickLabel = "Switch to tab") { onClick() }
            // Which card is the tab on screen, said rather than only
            // drawn as the thicker border; and Close (and Mute) as
            // actions on the card itself, so TalkBack users needn't hunt
            // for the small buttons in its header (#279).
            .semantics {
                selected = isActive
                if (isActive) stateDescription = "Current tab"
                customActions = listOfNotNull(
                    CustomAccessibilityAction("Close tab") { onClose(); true },
                    onToggleMute?.takeIf { tab.playingAudio || tab.audioMuted }?.let { toggle ->
                        CustomAccessibilityAction(if (tab.audioMuted) "Unmute tab" else "Mute tab") {
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
                tab.url.ifBlank { if (tab.private) "Private tab" else "New tab" }
            }
            if (tab.private) {
                Icon(
                    PrivateTabIcon,
                    contentDescription = "Private",
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
                    contentDescription = "Close tab",
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
            val thumb = tab.thumbnail
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
                contentDescription = if (muted) "Tab muted" else "Tab playing audio",
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
            contentDescription = if (muted) "Unmute tab" else "Mute tab",
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
            if (tab.private && letter == "•") {
                Icon(
                    PrivateTabIcon,
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
    if (count == 1) "Tabs, 1 open" else "Tabs, $count open"
