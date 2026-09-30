package baby.freedom.mobile.data

/**
 * [ids] (the bookmarks in their current order) with [id] taken out and
 * put back right after [afterId] — or first, for a null [afterId]
 * (#264). Null when there's nothing to do: [id] or a non-null [afterId]
 * isn't in the list (a bookmark removed meanwhile), [afterId] is [id]
 * itself, or [id] is already there.
 *
 * A move names its new neighbour rather than an index so it lands where
 * the user put it even if the list changed under it — a bookmark added
 * from another tab while this one was being dragged doesn't shift it.
 */
internal fun movedAfter(ids: List<Long>, id: Long, afterId: Long?): List<Long>? {
    if (id !in ids || afterId == id) return null
    val rest = ids.filter { it != id }
    val at = if (afterId == null) 0 else rest.indexOf(afterId).takeIf { it >= 0 }?.plus(1) ?: return null
    val moved = rest.toMutableList().apply { add(at, id) }
    return moved.takeIf { it != ids }
}

/** What saving an edited bookmark came to (#264). */
sealed interface BookmarkEditResult {
    data object Saved : BookmarkEditResult

    /** Another bookmark already has this address; [title] is its name. */
    data class Duplicate(val title: String, val url: String) : BookmarkEditResult

    /** The bookmark was removed while it was being edited. */
    data object Gone : BookmarkEditResult

    /** The database refused the write (e.g. the disk is full). */
    data object Failed : BookmarkEditResult
}

/**
 * The bookmark a star tap came to: [id], and whether it [added] a new
 * one or found the page already bookmarked under some spelling of it.
 */
data class Bookmarked(val id: Long, val added: Boolean)
