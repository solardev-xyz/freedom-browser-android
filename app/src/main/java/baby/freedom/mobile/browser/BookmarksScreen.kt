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
import androidx.compose.material.icons.filled.DeleteForever
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import baby.freedom.mobile.R
import baby.freedom.mobile.data.BookmarkEntry
import baby.freedom.mobile.data.BrowsingRepository
import baby.freedom.mobile.l10n.pluralText
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
 * Each row's ⋮ menu opens it in a new or a private tab behind this one
 * ([onOpenInNewTab], #321), edits it ([BookmarkEditDialog]), moves it,
 * or removes it; a long-press on the row that isn't followed by a drag
 * opens the same menu. To reorder by hand, drag a row by its handle (at
 * once), or long-press anywhere on it and drag, as in the tab switcher;
 * TalkBack gets the whole menu as actions on the row. The order is saved
 * ([BrowsingRepository.moveBookmark]) and is the Home page tiles' order
 * too. The page's own ⋮ has *Delete all bookmarks*, behind a
 * confirmation (#400: it used to sit in Settings next to Clear history).
 *
 * [private] is whether it was opened from a private tab: the edit
 * dialog's fields then don't let the keyboard learn what's typed, and
 * a new tab opened from here is a private one ([entryOpenTargets]).
 */
@Composable
fun BookmarksScreen(
    repo: BrowsingRepository,
    private: Boolean,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
    onOpenInNewTab: (url: String, private: Boolean) -> Unit,
) {
    BackHandler(onBack = onDismiss)

    // Null until Room's first answer, so nothing reads "no bookmarks"
    // before the list has loaded (the Delete all confirmation below).
    val loaded by remember { repo.bookmarks }.collectAsState(initial = null)
    val entries = loaded.orEmpty()
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    // The row whose menu is open, from its ⋮ or a long-press.
    var menuFor by remember { mutableStateOf<Long?>(null) }
    // The page's own ⋮ (#400), and its Delete all bookmarks confirmation.
    var pageMenuOpen by remember { mutableStateOf(false) }
    var confirmDeleteAll by rememberSaveable { mutableStateOf(false) }

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
        title = stringResource(R.string.library_bookmarks_title),
        onDismiss = onDismiss,
        trailing = {
            // Only with something to delete: an empty page has no ⋮.
            if (entries.isNotEmpty()) Box {
                // Material's own size: a full 48 dp target (#279).
                IconButton(onClick = { pageMenuOpen = true }, shapes = IconButtonDefaults.shapes()) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.library_bookmarks_more))
                }
                DropdownMenu(expanded = pageMenuOpen, onDismissRequest = { pageMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.library_bookmarks_delete_all)) },
                        leadingIcon = { Icon(Icons.Filled.DeleteForever, contentDescription = null) },
                        onClick = {
                            pageMenuOpen = false
                            confirmDeleteAll = true
                        },
                    )
                }
            }
        },
    ) {
        if (shown.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.BookmarkBorder,
                title = stringResource(R.string.library_bookmarks_empty_title),
                hint = stringResource(R.string.library_bookmarks_empty_hint),
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
                            onHeld = { id -> menuFor = id },
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
                        menuOpen = menuFor == entry.id,
                        onMenuOpenChange = { open -> menuFor = if (open) entry.id else null },
                        openActions = entryOpenActions(private) { inPrivate -> onOpenInNewTab(entry.url, inPrivate) },
                        onEdit = { editingId = entry.id },
                        onMove = { afterId -> repo.moveBookmark(entry.id, afterId) },
                        onRemove = { repo.deleteBookmark(entry.id) },
                    )
                }
            }
        }
    }

    // The confirmation survives recreation, but waits for the list to load
    // before it names a count, and closes if the list empties some other way.
    val bookmarkCount = loaded?.size
    LaunchedEffect(bookmarkCount) { if (bookmarkCount == 0) confirmDeleteAll = false }
    if (confirmDeleteAll && bookmarkCount != null && bookmarkCount > 0) {
        ConfirmDialog(
            title = stringResource(R.string.library_bookmarks_delete_all_title),
            message = pluralText(
                R.plurals.library_bookmarks_delete_all_message, bookmarkCount, bookmarkCount,
            ),
            confirmLabel = stringResource(R.string.library_bookmarks_delete_all_confirm),
            onConfirm = {
                repo.clearBookmarks()
                confirmDeleteAll = false
            },
            onDismiss = { confirmDeleteAll = false },
        )
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
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    openActions: List<Pair<String, () -> Unit>>,
    onEdit: () -> Unit,
    onMove: (afterId: Long?) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val editLabel = stringResource(R.string.common_edit)
    val removeLabel = stringResource(R.string.common_remove)
    PageRow(
        title = entry.title.ifBlank { entry.url },
        subtitle = entry.url,
        onClick = onClick,
        // The drag has no TalkBack equivalent, so its moves are offered
        // as accessibility actions (the ⋮ menu has them too) — with the
        // opens (#321), Edit and Remove, so the whole menu is on the row
        // itself (#279).
        modifier = modifier.semantics {
            customActions = openActions.asAccessibilityActions() + listOf(
                CustomAccessibilityAction(editLabel) {
                    onEdit()
                    true
                },
            ) + moves.map { (label, afterId) ->
                CustomAccessibilityAction(label) {
                    onMove(afterId)
                    true
                }
            } + CustomAccessibilityAction(removeLabel) {
                onRemove()
                true
            }
        },
        // The drag handle: pressing here and dragging moves the row
        // ([bookmarkDragGestures]); it's decoration for TalkBack.
        leadingIcon = Icons.Filled.DragIndicator,
        trailing = {
            Box {
                // Material's own size: a full 48 dp target (#279).
                IconButton(
                    onClick = { onMenuOpenChange(true) },
                    shapes = IconButtonDefaults.shapes(),
                ) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.library_bookmark_options),
                        modifier = Modifier.size(20.dp),
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenuOpenChange(false) }) {
                    EntryOpenMenuItems(openActions, onClose = { onMenuOpenChange(false) })
                    DropdownMenuItem(
                        text = { Text(editLabel) },
                        onClick = {
                            onMenuOpenChange(false)
                            onEdit()
                        },
                    )
                    moves.forEach { (label, afterId) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                onMenuOpenChange(false)
                                onMove(afterId)
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(removeLabel) },
                        onClick = {
                            onMenuOpenChange(false)
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
 *
 * A long press that ends without the finger having moved past touch
 * slop was no drag: the row never moves (the finger's sub-slop jitter
 * isn't fed to the drag, so it can't reorder or edge-scroll either),
 * and lifting calls [onHeld] with the row's id, which opens that row's
 * menu (#321). Only a long press does — a press on the handle is a drag
 * from its first move.
 */
private suspend fun PointerInputScope.bookmarkDragGestures(
    reorder: BookmarkReorder,
    onHandle: (Offset) -> Boolean,
    entries: () -> List<BookmarkEntry>,
    onStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onHeld: (id: Long) -> Unit,
) = awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false)
    var pending = 0f
    val longPress = !onHandle(down.position)
    val pointer = if (!longPress) {
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
    val held = reorder.draggedId
    onStart()
    var travelled = Offset.Zero
    // Once past slop it was a drag, even if the row ends up back where it
    // started. From the handle it already is; after a long press the row
    // doesn't move at all until the finger has gone past slop, so a still
    // finger's jitter can't move it — nor, on a row at the list's edge,
    // start the edge auto-scroll that would carry it through the list.
    var dragged = !longPress
    var lifted = false
    try {
        if (pending != 0f) onDrag(pending)
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == pointer } ?: break
            val delta = change.positionChange()
            change.consume()
            if (!change.pressed) {
                lifted = true
                break
            }
            travelled += delta
            if (!dragged) {
                if (travelled.getDistance() <= viewConfiguration.touchSlop) continue
                dragged = true
                // Catch the row up with the finger's travel so far.
                if (travelled.y != 0f) onDrag(travelled.y)
                continue
            }
            if (delta.y != 0f) onDrag(delta.y)
        }
    } finally {
        reorder.end()
    }
    if (longPress && lifted && !dragged && held != null) {
        onHeld(held)
    }
}
