package baby.freedom.mobile.node

import baby.freedom.swarm.NodeInfo
import baby.freedom.swarm.NodeStatus

/**
 * Keeps turning the node off from cutting a postage spend in half (#116).
 *
 * A buy or extend blocks inside ant for minutes while its transactions
 * confirm. Stopping the service in the middle ([NodeService.onDestroy]
 * exits the `:node` process) could leave a batch paid for on-chain but
 * never registered, or xDAI swapped with the approval still open. So
 * [NodeService] counts the spends running ([begin]/[end]), and when the
 * user turns the node off ([requestStop]) while one runs, the service
 * stays up and stops itself once the last one ends. From the moment a
 * stop is asked for until the user turns the node back on ([cancelStop])
 * no new spend may start, so the stop can't be overtaken by one.
 *
 * The wait is bounded: a stop never waits more than a fixed budget
 * ([budgetLeftMs]), counted from the moment it was first asked for, so a
 * spend hung inside ant can't keep a node the user turned off peering
 * indefinitely. Each stop carries a [stopGeneration], so a deadline timer
 * scheduled for one stop can't act on a later one ([overdue]).
 *
 * It also keeps a search for the account's own stamps (#118) apart from
 * the spends ([beginDiscover]): ant's discover can deploy the chequebook
 * and pay its deposit as it registers a batch, and while a spend's
 * permit is open that would go out under it, taking the spend's slots.
 * With neither overlapping, a discover only ever runs with no permit
 * open, so it sends nothing.
 */
internal class SpendStopGate(private val nanoTime: () -> Long = System::nanoTime) {
    private val monitor = Object()
    private var running = 0
    private var stopping = false
    private var stopWhenIdle = false
    private var stopAskedAt = 0L
    private var generation = 0L
    private var discovering = false

    /**
     * A spend is about to start; false (don't start it) while the node is
     * being turned off, or while a discover runs.
     */
    fun begin(): Boolean = synchronized(monitor) {
        if (stopping || discovering) return false
        running++
        true
    }

    /** A discover is about to start; false (don't start it) while a spend or another discover runs. */
    fun beginDiscover(): Boolean = synchronized(monitor) {
        if (discovering || running > 0) return false
        discovering = true
        true
    }

    /** Whether a discover is running now (for saying why a spend didn't start). */
    val discoverRunning: Boolean get() = synchronized(monitor) { discovering }

    /** The discover [beginDiscover] let start has ended, however. */
    fun endDiscover() = synchronized(monitor) {
        check(discovering) { "endDiscover() without beginDiscover()" }
        discovering = false
    }

    /** A spend [begin] let start has ended, however. True if the service should now stop itself. */
    fun end(): Boolean = synchronized(monitor) {
        check(running > 0) { "end() without begin()" }
        running--
        if (running == 0) monitor.notifyAll()
        stopWhenIdle && running == 0
    }

    /**
     * The user turned the node off. True: a spend is running, and the
     * service stops itself once it ends ([shouldStopNow]). False: nothing
     * is running, stop now. Either way no spend starts from here on.
     */
    fun requestStop(): Boolean = synchronized(monitor) {
        if (!stopping) {
            // A repeated ask keeps the first one's clock: toggling off
            // again doesn't extend the wait.
            stopping = true
            stopAskedAt = nanoTime()
            generation++
        }
        stopWhenIdle = running > 0
        stopWhenIdle
    }

    /** The user turned the node back on: forget any stop asked for. */
    fun cancelStop() = synchronized(monitor) {
        if (stopping) generation++
        stopping = false
        stopWhenIdle = false
    }

    /** The stop asked for now, for a deadline timer to hand back to [overdue]. */
    val stopGeneration: Long get() = synchronized(monitor) { generation }

    /**
     * How much of [budgetMs] is left for the stop asked for: all of it
     * with none asked for, never below 0.
     */
    fun budgetLeftMs(budgetMs: Long): Long = synchronized(monitor) {
        if (!stopping) return budgetMs
        val elapsedMs = (nanoTime() - stopAskedAt) / 1_000_000
        (budgetMs - elapsedMs).coerceIn(0, budgetMs)
    }

    /**
     * Has stop [stopGeneration] waited out [budgetMs] with a spend still
     * running? The service then stops anyway. False once the stop was
     * cancelled or superseded, or once the spend ended (the ordinary
     * deferred stop then carries it out).
     */
    fun overdue(stopGeneration: Long, budgetMs: Long): Boolean = synchronized(monitor) {
        stopping && stopWhenIdle && running > 0 && stopGeneration == generation &&
            (nanoTime() - stopAskedAt) / 1_000_000 >= budgetMs
    }

    /** Is a deferred stop due: one was asked for, not cancelled, and no spend runs any more? */
    fun shouldStopNow(): Boolean = synchronized(monitor) { stopWhenIdle && running == 0 }

    /**
     * Blocks until no spend runs, or [timeoutMs] has passed; true if none
     * runs. For the service being destroyed regardless (a stop that didn't
     * go through [requestStop]): it holds the process exit back until the
     * spend is done. Call [requestStop] first so no new one starts.
     */
    fun awaitIdle(timeoutMs: Long): Boolean = synchronized(monitor) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (running > 0) {
            val left = (deadline - System.nanoTime()) / 1_000_000
            if (left <= 0) return false
            monitor.wait(left)
        }
        true
    }

    /** Spends running now. */
    val spendsRunning: Int get() = synchronized(monitor) { running }
}

/**
 * Process-wide: has a destroyed [NodeService] left the `:node` process
 * doomed — it exits once a postage spend still running inside ant ends
 * ([NodeService.onDestroy]'s fallback, #116)?
 *
 * Turning the node back on in that window creates a new [NodeService] in
 * this same process, where the old ant handle (port 1633, its state-store
 * lock) may still be alive and the pending exit will kill whatever the
 * new one starts. So the new one starts nothing — no node, no spend — and
 * the exit takes it down; Android restarts the (sticky, bound) service in
 * a fresh process, which boots normally.
 */
internal class ProcessExitLatch {
    @Volatile
    var pending: Boolean = false
        private set

    /** The process will exit once the running spend ends: nothing may start in it any more. */
    fun schedule() {
        pending = true
    }

    companion object {
        /** The `:node` process's one latch. */
        val node = ProcessExitLatch()
    }
}

/**
 * What a [NodeService] created in a doomed `:node` process ([ProcessExitLatch])
 * reports instead of its idle node's Stopped: the node is on its way up —
 * it starts once the process restarts — and why it isn't yet. Without it
 * the node page shows the switch on beside "Stopped" with no reason.
 */
internal fun reportedNodeInfo(info: NodeInfo, doomed: Boolean): NodeInfo =
    if (doomed && info.status == NodeStatus.Stopped) {
        NodeInfo(status = NodeStatus.Starting, errorMessage = WAITING_FOR_SPEND_NOTE)
    } else {
        info
    }

internal const val WAITING_FOR_SPEND_NOTE =
    "Waiting for a payment (a postage stamp or a chequebook deposit) to finish; the node starts once it's done"

