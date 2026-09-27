package baby.freedom.mobile.browser

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The download snackbars (#79) the browser has showing or queued, so
 * they can be withdrawn without touching anyone else's.
 *
 * Each notice is one coroutine around `SnackbarHostState.showSnackbar`,
 * and cancelling that coroutine dismisses the snackbar if it's up or
 * drops it from the host's queue if it's still waiting. So:
 *
 * - a download's end [supersedeStart]s its own "Downloading…" notice,
 *   and never another download's;
 * - opening the Downloads list [cancelAll]s every download notice —
 *   the one on screen *and* the ones queued behind it — while a
 *   snackbar that isn't a download notice stays where it is;
 * - a background tab dropping download offers is [announceDrops]ed
 *   once per episode, and that notice is a download notice too — but
 *   one withdrawn by [cancelAll] before it ran its course counts as
 *   not yet announced, since the Downloads list doesn't show drops:
 *   the next [announceDrops] (once the list closes) says it again;
 * - a private download's notice (#86; negative [id]) names a file from
 *   the private session, so it is only for a screen already showing
 *   private content: leaving it [cancelPrivate]s them, and
 *   [privateShowing] tells the screen guard one is still up or queued.
 *
 * Main thread only (it's driven from Compose effects), so no locking.
 */
internal class DownloadNotices {
    private val live = HashSet<Job>()
    private val starts = HashMap<Long, Job>()
    private val announcedDrops = HashSet<Long>()
    private val dropNotices = HashMap<Long, Job>()
    private val privates = HashSet<Job>()
    private var privateCount by mutableIntStateOf(0)

    /** Is a private download's notice showing or queued? Snapshot state. */
    val privateShowing: Boolean get() = privateCount > 0

    /**
     * Launch notice [block] for download [id] in [scope]. A [start]
     * notice is the one a later [supersedeStart] for [id] withdraws.
     */
    fun show(
        scope: CoroutineScope,
        id: Long,
        start: Boolean = false,
        block: suspend CoroutineScope.() -> Unit,
    ): Job {
        val job = scope.launch(block = block)
        if (job.isCompleted) return job
        live += job
        if (start) starts.put(id, job)?.cancel()
        if (id < 0) {
            privates += job
            privateCount = privates.size
        }
        job.invokeOnCompletion {
            live.remove(job)
            starts.remove(id, job)
            if (privates.remove(job)) privateCount = privates.size
        }
        return job
    }

    /**
     * Announce, once per episode, each tab in [dropping] (tabs whose
     * download offers are being dropped) other than [activeTab], by
     * launching [block] for it in [scope]. A tab that stops dropping
     * ends its episode: its notice is withdrawn and a later episode is
     * announced afresh. Pass a [scope] that outlives the caller's
     * effect — a tab starting to drop must not cancel another tab's
     * notice — and don't call this while the Downloads list is up: an
     * unannounced tab is picked up by the next call.
     */
    fun announceDrops(
        scope: CoroutineScope,
        dropping: Set<Long>,
        activeTab: Long,
        block: suspend CoroutineScope.(tabId: Long) -> Unit,
    ) {
        announcedDrops.retainAll(dropping)
        dropNotices.keys.filter { it !in dropping }.forEach { dropNotices.remove(it)?.cancel() }
        for (tabId in dropping) {
            if (tabId == activeTab || !announcedDrops.add(tabId)) continue
            val job = scope.launch { block(tabId) }
            if (job.isCompleted) continue
            live += job
            dropNotices[tabId] = job
            job.invokeOnCompletion {
                live.remove(job)
                dropNotices.remove(tabId, job)
            }
        }
    }

    /** Withdraw download [id]'s "Downloading…" notice, if still up or queued. */
    fun supersedeStart(id: Long) {
        starts.remove(id)?.cancel()
    }

    /**
     * Withdraw every private download's notice, showing or queued.
     * [privateShowing] turns false once they've actually finished (off
     * the host), not here.
     */
    fun cancelPrivate() {
        // Copy: each cancel's completion handler edits the set.
        privates.toList().forEach { it.cancel() }
    }

    /**
     * Withdraw every download notice, showing or queued. A tab whose
     * drop notice is withdrawn here is announced again by the next
     * [announceDrops] if it's still dropping: the notice was cut short
     * (or never shown), and the Downloads list that replaced it doesn't
     * carry that news.
     */
    fun cancelAll() {
        announcedDrops.removeAll(dropNotices.keys)
        // Copy: each cancel's completion handler edits the sets.
        live.toList().forEach { it.cancel() }
        live.clear()
        starts.clear()
        // [privates] empties from the completion handlers, once each
        // cancelled notice has really left the host: until then
        // [privateShowing] stays true.
        dropNotices.clear()
    }
}
