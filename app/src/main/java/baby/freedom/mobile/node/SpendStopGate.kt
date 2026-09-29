package baby.freedom.mobile.node

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
 */
internal class SpendStopGate {
    private val monitor = Object()
    private var running = 0
    private var stopping = false
    private var stopWhenIdle = false

    /** A spend is about to start; false (don't start it) while the node is being turned off. */
    fun begin(): Boolean = synchronized(monitor) {
        if (stopping) return false
        running++
        true
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
        stopping = true
        stopWhenIdle = running > 0
        stopWhenIdle
    }

    /** The user turned the node back on: forget any stop asked for. */
    fun cancelStop() = synchronized(monitor) {
        stopping = false
        stopWhenIdle = false
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
