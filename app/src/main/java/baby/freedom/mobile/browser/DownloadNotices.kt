package baby.freedom.mobile.browser

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
 *   snackbar that isn't a download notice stays where it is.
 *
 * Main thread only (it's driven from Compose effects), so no locking.
 */
internal class DownloadNotices {
    private val live = HashSet<Job>()
    private val starts = HashMap<Long, Job>()

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
        job.invokeOnCompletion {
            live.remove(job)
            starts.remove(id, job)
        }
        return job
    }

    /** Withdraw download [id]'s "Downloading…" notice, if still up or queued. */
    fun supersedeStart(id: Long) {
        starts.remove(id)?.cancel()
    }

    /** Withdraw every download notice, showing or queued. */
    fun cancelAll() {
        // Copy: each cancel's completion handler edits the sets.
        live.toList().forEach { it.cancel() }
        live.clear()
        starts.clear()
    }
}
