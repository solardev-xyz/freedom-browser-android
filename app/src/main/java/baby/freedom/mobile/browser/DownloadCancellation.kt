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
    /** What each id's job may block on, with the job that tracked it. */
    private val open = ConcurrentHashMap<Long, Pair<Job?, AutoCloseable>>()

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
            // run its id again, forgets its pause marks first — see
            // [forgetPause]).
            jobs[id].also {
                val marks = if (it == null) cancelledEarly else stops
                marks[id] = strongerStop(marks[id], stop)
            }
        }
        job?.cancel()
        open.remove(id)?.let { runCatching { it.second.close() } }
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
     * Drop the [DownloadStop.PAUSE] marks on [id], keeping a cancel: it's
     * about to run again (a resume). A pause aimed at its last run (a
     * second Pause tap that landed after it had paused) must not stop the
     * new one — but a Cancel tapped a beat before the resume must: its
     * own locked follow-up hasn't run yet (a paused row it gets to is
     * cancelled there and forgotten), so the mark is never stale.
     */
    fun forgetPause(id: Long) {
        synchronized(lock) {
            if (cancelledEarly[id] == DownloadStop.PAUSE) cancelledEarly.remove(id)
            if (stops[id] == DownloadStop.PAUSE) stops.remove(id)
        }
    }

    /**
     * [closeable] is what [id]'s [job] may block on now (it replaces the
     * previous one). Closed immediately if the job is already cancelled.
     */
    fun track(id: Long, job: Job?, closeable: AutoCloseable) {
        open[id] = job to closeable
        // Put first, check second: a cancel racing this either sees the
        // entry and closes it, or cancelled the job before this check.
        if (job?.isActive == false) runCatching { closeable.close() }
    }

    /**
     * [id]'s run by [job] is over; drop (without closing) what it
     * tracked. Only its own entry: a resumed run of the same id may
     * already have tracked its connection, and that one must stay
     * reachable by a later Pause or Cancel.
     */
    fun release(id: Long, job: Job?) {
        open.computeIfPresent(id) { _, entry -> if (entry.first === job) null else entry }
    }
}

private fun strongerStop(a: DownloadStop?, b: DownloadStop): DownloadStop =
    if (a == DownloadStop.CANCEL || b == DownloadStop.CANCEL) DownloadStop.CANCEL else DownloadStop.PAUSE
