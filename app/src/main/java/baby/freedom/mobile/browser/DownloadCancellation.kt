package baby.freedom.mobile.browser

import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap

/** Why a download is being stopped (#265). */
internal enum class DownloadStop {
    /** Keep the partial file; it can be resumed. */
    PAUSE,

    /** Throw the partial file away. Wins over [PAUSE]. */
    CANCEL,
}

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
 *
 * A stop is a cancel or a pause (#265, [DownloadStop]); the job asks
 * [stopOf] which one it got. A cancel landing on top of a pause turns
 * it into a cancel, never the other way round.
 */
internal class DownloadCancellation {
    private val lock = Any()
    private val jobs = HashMap<Long, Job>()
    private val cancelledEarly = HashMap<Long, DownloadStop>()
    private val stops = HashMap<Long, DownloadStop>()
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
            val early = cancelledEarly.remove(id)
            if (early == null) stops.remove(id) else stops[id] = early
            early == null
        }
    }

    /**
     * Stop [id] — cancel or pause it, per [stop]: cancel its job and
     * close whatever it's blocked on.
     */
    fun cancel(id: Long, stop: DownloadStop = DownloadStop.CANCEL) {
        val job = synchronized(lock) {
            // No job: not registered yet — or already over, in which case
            // the mark is inert until [forget] drops it (ids are
            // AUTOINCREMENT, never reused; a resumed download, which does
            // run its id again, forgets first).
            jobs[id].also {
                val marks = if (it == null) cancelledEarly else stops
                marks[id] = strongerStop(marks[id], stop)
            }
        }
        job?.cancel()
        open.remove(id)?.let { runCatching { it.close() } }
    }

    /**
     * How [id]'s current (or refused) job was stopped, if it was. Read
     * by the job itself once it's been stopped, and by the caller of a
     * [register] that returned false.
     */
    fun stopOf(id: Long): DownloadStop? = synchronized(lock) { stops[id] }

    /**
     * Drop every mark on [id]: its row is gone and nothing can start for
     * it any more — or it's about to run again (a resume), and a stop
     * aimed at its previous run must not stop the new one.
     */
    fun forget(id: Long) {
        synchronized(lock) {
            cancelledEarly.remove(id)
            stops.remove(id)
        }
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

private fun strongerStop(a: DownloadStop?, b: DownloadStop): DownloadStop =
    if (a == DownloadStop.CANCEL || b == DownloadStop.CANCEL) DownloadStop.CANCEL else DownloadStop.PAUSE
