package baby.freedom.mobile.data

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Orders the repository's fire-and-forget history and favicon writes
 * against Delete browsing data (#480 R1-M1). Those writes run unordered
 * on `Dispatchers.IO`, so without this a visit or icon asked for
 * *before* the delete — still queued, or a page's late `onReceivedIcon`
 * — could commit just after it and put the site straight back.
 *
 * A write carries a [ticket] taken when it was asked for (for an icon:
 * when its page's load started). [revoke], called synchronously when a
 * delete is asked for, makes every older ticket stale; [write] runs its
 * block only for a current ticket, and under the same lock as [forget],
 * so a write either lands before the delete (which then removes it) or
 * is dropped.
 */
internal class HistoryWriteGate {
    private val generation = AtomicLong()
    private val lock = Mutex()

    /** The ticket a write asked for now carries. */
    fun ticket(): Long = generation.get()

    /** Make every ticket taken so far stale. Call before launching the delete. */
    fun revoke() {
        generation.incrementAndGet()
    }

    /** Run [block] unless a delete was asked for since [ticket]; whether it ran. */
    suspend fun write(ticket: Long, block: suspend () -> Unit): Boolean = lock.withLock {
        if (generation.get() != ticket) return@withLock false
        block()
        true
    }

    /** Run the delete [block], with no [write] in progress. */
    suspend fun <T> forget(block: suspend () -> T): T = lock.withLock { block() }
}
