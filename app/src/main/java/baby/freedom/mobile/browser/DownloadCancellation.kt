package baby.freedom.mobile.browser

import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap

/**
 * What [DownloadManager.cancel] needs to stop a download at any point
 * of its life (#79):
 *
 * - **before its job exists.** The history row — and with it the Cancel
 *   button — appears the moment it's inserted, a beat before the job
 *   is registered. A cancel then is remembered, and [register] reports
 *   it so the job is never started.
 * - **while it blocks on the network.** Cancelling a coroutine doesn't
 *   interrupt a blocking connect, header wait or socket read, so each
 *   connection and response body is [track]ed as soon as it exists and
 *   [cancel] closes it: the blocked call fails at once instead of at
 *   its timeout. One tracked after the cancel already ran is closed on
 *   the spot rather than left open until GC.
 */
internal class DownloadCancellation {
    private val lock = Any()
    private val jobs = HashMap<Long, Job>()
    private val cancelledEarly = HashSet<Long>()
    private val open = ConcurrentHashMap<Long, AutoCloseable>()

    /**
     * Register [id]'s (not yet started) job. False when a cancel got
     * there first: the caller should cancel the job instead of starting
     * it. The job unregisters itself when it completes.
     */
    fun register(id: Long, job: Job): Boolean {
        job.invokeOnCompletion { synchronized(lock) { if (jobs[id] === job) jobs.remove(id) } }
        return synchronized(lock) {
            jobs[id] = job
            !cancelledEarly.remove(id)
        }
    }

    /** Stop [id]: cancel its job and close whatever it's blocked on. */
    fun cancel(id: Long) {
        val job = synchronized(lock) {
            // No job: not registered yet — or already over, in which case
            // the mark is inert (ids are AUTOINCREMENT, never reused)
            // until [forget] drops it.
            jobs[id].also { if (it == null) cancelledEarly.add(id) }
        }
        job?.cancel()
        open.remove(id)?.let { runCatching { it.close() } }
    }

    /** [id]'s row is gone; nothing can start for it any more. */
    fun forget(id: Long) {
        synchronized(lock) { cancelledEarly.remove(id) }
    }

    /**
     * [closeable] is what [id]'s [job] may block on now (it replaces the
     * previous one). Closed immediately if the job is already cancelled.
     */
    fun track(id: Long, job: Job?, closeable: AutoCloseable) {
        open[id] = closeable
        // Put first, check second: a cancel racing this either sees the
        // entry and closes it, or cancelled the job before this check.
        if (job?.isActive == false) runCatching { closeable.close() }
    }

    /** [id]'s download is over; drop (without closing) what it tracked. */
    fun release(id: Long) {
        open.remove(id)
    }
}
