package baby.freedom.mobile.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import baby.freedom.mobile.data.BookmarkEntry
import baby.freedom.mobile.data.BrowsingRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Full-screen list of saved bookmarks, in the user's order (#264).
 * Tapping a row calls [onOpen] with the bookmark's URL; the host closes
 * the screen and submits the URL into the active tab.
 *
 * Each row's ⋮ menu edits it ([BookmarkEditDialog]), moves it, or
 * removes it. To reorder by hand, drag a row by its handle (at once), or
 * long-press anywhere on it and drag, as in the tab switcher; TalkBack
 * gets the same moves as actions on the row. The order is saved
 * ([BrowsingRepository.moveBookmark]) and is the Home page tiles' order
 * too.
 *
 * [private] is whether it was opened from a private tab: the edit
 * dialog's fields then don't let the keyboard learn what's typed.
 */
@Composable
fun BookmarksScreen(
    repo: BrowsingRepository,
    private: Boolean,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
) {
    BackHandler(onBack = onDismiss)

    val entries by remember { repo.bookmarks }.collectAsState(initial = emptyList())
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val edgePx = with(density) { REORDER_EDGE.toPx() }
    val handleZonePx = with(density) { HANDLE_ZONE.toPx() }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val reorder = remember(listState, repo) {
        BookmarkReorder(listState) { id, afterId -> repo.moveBookmark(id, afterId) }
    }
    val dropped = reorder.pendingDrop
    // A drop that didn't change the saved order (it failed, or the list
    // had changed under it) gets no new list from Room, so stop showing
    // the dragged copy once the save says so (#296 R1-M2).
    LaunchedEffect(dropped) {
        if (dropped != null && !dropped.await()) reorder.dropFailed(dropped)
    }
    // A dropped row keeps the order it was dropped in until the saved
    // order arrives, so it doesn't jump back for a frame.
    LaunchedEffect(entries) { reorder.stored() }
    val shown = reorder.order ?: entries
    val currentShown by rememberUpdatedState(shown)

    FullScreenScaffold(
        title = "Bookmarks",
        onDismiss = onDismiss,
    ) {
        if (shown.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.BookmarkBorder,
                title = "No bookmarks yet",
                hint = "Tap the star in the menu while on a page to save it.",
            )
        } else {
            val ids = shown.map { it.id }
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(horizontal = LIST_PADDING_H, vertical = 8.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(reorder, handleZonePx, rtl) {
                        bookmarkDragGestures(
                            reorder = reorder,
                            onHandle = { at ->
                                if (rtl) at.x >= size.width - handleZonePx else at.x <= handleZonePx
                            },
                            entries = { currentShown },
                            onStart = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                            onDrag = { dy -> reorder.drag(dy, scope, edgePx, size.height.toFloat()) },
                        )
                    },
            ) {
                itemsIndexed(items = shown, key = { _, entry -> entry.id }) { index, entry ->
                    val dragged = entry.id == reorder.draggedId
                    BookmarkRow(
                        entry = entry,
                        moves = bookmarkMoves(ids, index),
                        modifier = if (dragged) {
                            Modifier
                                .zIndex(1f)
                                .graphicsLayer {
                                    translationY = reorder.offset
                                    scaleX = 1.02f
                                    scaleY = 1.02f
                                    alpha = 0.92f
                                }
                        } else {
                            Modifier.animateItem()
                        },
                        onClick = { onOpen(entry.url) },
                        onEdit = { editingId = entry.id },
                        onMove = { afterId -> repo.moveBookmark(entry.id, afterId) },
                        onRemove = { repo.deleteBookmark(entry.id) },
                    )
                }
            }
        }
    }

    editingId?.let { id ->
        BookmarkEditDialog(
            repo = repo,
            id = id,
            private = private,
            onDismiss = { editingId = null },
        )
    }
}

@Composable
private fun BookmarkRow(
    entry: BookmarkEntry,
    moves: List<Pair<String, Long?>>,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onMove: (afterId: Long?) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    PageRow(
        title = entry.title.ifBlank { entry.url },
        subtitle = entry.url,
        onClick = onClick,
        // The drag has no TalkBack equivalent, so its moves are offered
        // as accessibility actions (the ⋮ menu has them too).
        modifier = modifier.semantics {
            customActions = moves.map { (label, afterId) ->
                CustomAccessibilityAction(label) {
                    onMove(afterId)
                    true
                }
            }
        },
        // The drag handle: pressing here and dragging moves the row
        // ([bookmarkDragGestures]); it's decoration for TalkBack.
        leadingIcon = Icons.Filled.DragIndicator,
        trailing = {
            Box {
                IconButton(
                    onClick = { menuOpen = true },
                    shapes = IconButtonDefaults.shapes(),
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "Bookmark options",
                        modifier = Modifier.size(20.dp),
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        onClick = {
                            menuOpen = false
                            onEdit()
                        },
                    )
                    moves.forEach { (label, afterId) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                menuOpen = false
                                onMove(afterId)
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Remove") },
                        onClick = {
                            menuOpen = false
                            onRemove()
                        },
                    )
                }
            }
        },
    )
}

/** The list's side padding. */
private val LIST_PADDING_H = 16.dp

/**
 * How far in from the list's start edge a press is on a row's drag
 * handle: the side padding, the row's own padding, the handle icon and
 * the gap after it.
 */
private val HANDLE_ZONE = LIST_PADDING_H + 12.dp + 24.dp + 12.dp

/** How close to the list's top or bottom a dragged row scrolls it. */
private val REORDER_EDGE = 56.dp

/** Most the list scrolls per frame while a row is held at its edge. */
private const val REORDER_SCROLL_STEP = 24f

/**
 * Drag-to-reorder for the Bookmarks list — the tab switcher's
 * `TabReorder` for one column.
 *
 * While a row is held the list shows [order], a local copy the drag
 * rearranges: whenever the row's centre crosses into another row's slot
 * it moves there and [offset] is rebased onto the new slot, so it stays
 * under the finger while the others animate round it. Only the drop is
 * saved — once, as "after this bookmark" ([onDrop], see
 * `movedAfter`) — and [order] stays up until the saved order comes back
 * ([stored]).
 */
private class BookmarkReorder(
    private val list: LazyListState,
    private val onDrop: (id: Long, afterId: Long?) -> Deferred<Boolean>,
) {
    var order: List<BookmarkEntry>? by mutableStateOf(null)
        private set

    /** The last drop's save, until it's done: whether it changed the stored order. */
    var pendingDrop: Deferred<Boolean>? by mutableStateOf(null)
        private set
    var draggedId: Long? by mutableStateOf(null)
        private set
    var offset: Float by mutableFloatStateOf(0f)
        private set

    private var startIds: List<Long> = emptyList()
    private var autoScroll: Job? = null
    private var scrollStep = 0f

    /** Where the held row is drawn (its top, in list pointer space): see TabReorder. */
    private var rowTop = 0f
    private var rowHeight = 0f

    /** The saved list changed: once no row is held, show it again. */
    fun stored() {
        if (draggedId == null) order = null
    }

    /**
     * [drop] saved nothing, so no new list is coming: show the stored
     * order again — unless another drag has started since.
     */
    fun dropFailed(drop: Deferred<Boolean>) {
        if (pendingDrop !== drop) return
        pendingDrop = null
        if (draggedId == null) order = null
    }

    private fun top(item: LazyListItemInfo): Float =
        (item.offset - list.layoutInfo.viewportStartOffset).toFloat()

    private fun contains(item: LazyListItemInfo, y: Float): Boolean {
        val top = top(item)
        return y >= top && y < top + item.size
    }

    fun start(at: Offset, entries: List<BookmarkEntry>): Boolean {
        val item = list.layoutInfo.visibleItemsInfo.firstOrNull { contains(it, at.y) } ?: return false
        val id = item.key as? Long ?: return false
        if (entries.none { it.id == id }) return false
        order = entries
        startIds = entries.map { it.id }
        draggedId = id
        offset = 0f
        rowTop = top(item)
        rowHeight = item.size.toFloat()
        return true
    }

    fun drag(dy: Float, scope: CoroutineScope, edgePx: Float, heightPx: Float) {
        if (draggedId == null) return
        offset += dy
        rowTop += dy
        moveIfOverAnother()
        val bottom = rowTop + rowHeight
        scrollStep = when {
            rowTop < edgePx -> -REORDER_SCROLL_STEP * ((edgePx - rowTop) / edgePx).coerceIn(0f, 1f)
            bottom > heightPx - edgePx ->
                REORDER_SCROLL_STEP * ((bottom - heightPx + edgePx) / edgePx).coerceIn(0f, 1f)
            else -> 0f
        }
        if (scrollStep != 0f && autoScroll?.isActive != true) {
            autoScroll = scope.launch {
                while (isActive && draggedId != null && scrollStep != 0f) {
                    val scrolled = try {
                        list.scrollBy(scrollStep)
                    } catch (e: CancellationException) {
                        if (!isActive) throw e
                        0f
                    }
                    val canGoOn = if (scrollStep > 0f) list.canScrollForward else list.canScrollBackward
                    if (scrolled == 0f && !canGoOn) break
                    offset += scrolled
                    moveIfOverAnother()
                    delay(16)
                }
            }
        }
    }

    private fun moveIfOverAnother() {
        val current = order ?: return
        val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == draggedId } ?: return
        val from = current.indexOfFirst { it.id == draggedId }
        // The list hasn't laid out the last move yet; ask again on the
        // next move or scroll step.
        if (item.index != from) return
        val centre = rowTop + rowHeight / 2f
        val target = list.layoutInfo.visibleItemsInfo
            .firstOrNull { it.key != draggedId && contains(it, centre) }
            ?: return
        val to = current.indexOfFirst { it.id == target.key }
        if (from < 0 || to < 0) return
        // The list keeps its first visible item pinned across a
        // reorder; re-pin to the current position if that item is one
        // of the two moving.
        val first = list.firstVisibleItemIndex
        val firstOffset = list.firstVisibleItemScrollOffset
        offset += top(item) - top(target)
        order = current.toMutableList().apply { add(to, removeAt(from)) }
        if (from == first || to == first) list.requestScrollToItem(first, firstOffset)
    }

    fun end() {
        autoScroll?.cancel()
        autoScroll = null
        scrollStep = 0f
        val id = draggedId
        draggedId = null
        offset = 0f
        val ids = order?.map { it.id }
        if (id == null || ids == null || ids == startIds) {
            order = null
            return
        }
        pendingDrop = onDrop(id, ids.getOrNull(ids.indexOf(id) - 1))
    }
}

/**
 * Drag on the Bookmarks list: from a row's handle ([onHandle]) as soon
 * as the finger moves past touch slop, from anywhere else on a row after
 * a long press.
 *
 * Like the tab switcher's gestures, the held pointer is read in the
 * initial pass and consumed there, so the list doesn't scroll under the
 * drag and lifting the finger doesn't also count as a tap that opens
 * the bookmark. On the handle the move that crosses the slop is consumed
 * the same way, before the list's own scroll sees it. Before that
 * nothing is consumed: a tap still opens, a swipe elsewhere still
 * scrolls.
 */
private suspend fun PointerInputScope.bookmarkDragGestures(
    reorder: BookmarkReorder,
    onHandle: (Offset) -> Boolean,
    entries: () -> List<BookmarkEntry>,
    onStart: () -> Unit,
    onDrag: (Float) -> Unit,
) = awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false)
    var pending = 0f
    val pointer = if (onHandle(down.position)) {
        var moved = Offset.Zero
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            if (!change.pressed || change.isConsumed) return@awaitEachGesture
            moved += change.positionChange()
            if (moved.getDistance() > viewConfiguration.touchSlop) {
                change.consume()
                break
            }
        }
        if (!reorder.start(down.position, entries())) return@awaitEachGesture
        pending = moved.y
        down.id
    } else {
        val press = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
        if (!reorder.start(press.position, entries())) return@awaitEachGesture
        press.id
    }
    onStart()
    try {
        if (pending != 0f) onDrag(pending)
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == pointer } ?: break
            val delta = change.positionChange()
            change.consume()
            if (!change.pressed) break
            if (delta.y != 0f) onDrag(delta.y)
        }
    } finally {
        reorder.end()
    }
}
