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
 * Thread-safe: both calls run under one lock, the second holding it
 * across the caller's restart so the two can't interleave.
 */
internal class SwarmBootIdentity {
    private val lock = Any()
    private var bootedAs: String? = null

    /** A launch reads the store: [read] gives the key it boots as ([swarmBootKey]) and its identity. */
    fun <T> boot(read: () -> Pair<String, T>): T = synchronized(lock) {
        val (address, identity) = read()
        bootedAs = address
        identity
    }

    /**
     * Restarts the node via [restart] if it booted as something other
     * than [want] and is [running]; the next launch's read is then
     * awaited before any further restart. True if it restarted.
     */
    fun restartIfStale(want: () -> String, running: () -> Boolean, restart: () -> Unit): Boolean =
        synchronized(lock) {
            val booted = bootedAs ?: return false
            if (booted.equals(want(), ignoreCase = true) || !running()) return false
            bootedAs = null
            restart()
            true
        }
}
