package baby.freedom.mobile.node

/**
 * What the Swarm node's current (re)start boots as (#77) — and in which
 * mode (#114) — so [INodeService.reloadIdentity] and
 * [INodeService.setSwarmMode] restart only a node that really booted as
 * something other than it would now.
 *
 * The value is a [swarmBootKey]: the wallet's Swarm address (`""` for
 * ant's own) with the mode; or unknown — "a read is still coming": before the first start, and again
 * from the moment a restart is ordered until the new launch reads the
 * store. An unknown node is never restarted, since the coming read sees
 * the change anyway; so of two reload requests racing one change, only
 * the first restarts it (#207 R1-F2).
 *
 * A launch whose read threw (#357: the wallet's keys are there but
 * couldn't be read) fails rather than boot as ant's own key, and is
 * tracked as [UNREADABLE] — a key no read gives — so the next reload
 * that reads the store again restarts it — a bind, an unlock, a Remove
 * wallet, or the app coming back to the foreground ([failedRead]). A reload whose own read
 * fails can't tell what the node should be, and keeps it as it is.
 *
 * Thread-safe: both calls run under one lock, the second holding it
 * across the caller's restart so the two can't interleave.
 */
internal class SwarmBootIdentity {
    private val lock = Any()
    private var bootedAs: String? = null

    /**
     * A launch reads the store: [read] gives the key it boots as
     * ([swarmBootKey]) and its identity. If [read] throws, the launch
     * fails with it and is tracked as [UNREADABLE].
     */
    fun <T> boot(read: () -> Pair<String, T>): T = synchronized(lock) {
        val (address, identity) = try {
            read()
        } catch (t: Throwable) {
            bootedAs = UNREADABLE
            throw t
        }
        bootedAs = address
        identity
    }

    /**
     * Restarts the node via [restart] if it booted as something other
     * than [want] and is [running]; the next launch's read is then
     * awaited before any further restart. True if it restarted. [want]
     * gives null when it can't read what the node should boot as: the
     * node is then left as it is, never taken for stale.
     */
    fun restartIfStale(want: () -> String?, running: () -> Boolean, restart: () -> Unit): Boolean =
        synchronized(lock) {
            val booted = bootedAs ?: return false
            val wanted = want() ?: return false
            if (booted.equals(wanted, ignoreCase = true) || !running()) return false
            bootedAs = null
            restart()
            true
        }

    /**
     * True while the current launch is one whose read failed ([UNREADABLE]):
     * the node is in Error. A bind and every settled wallet change (unlock,
     * removal) already have it reload; but the app coming back to the
     * foreground with no bind (same activity) and no wallet change is the
     * only other moment a cleared read shows, so it retries too.
     */
    fun failedRead(): Boolean = synchronized(lock) { bootedAs == UNREADABLE }

    private companion object {
        /** A launch whose read failed: never a [swarmBootKey], which always holds a `|`. */
        const val UNREADABLE = "unreadable"
    }
}
