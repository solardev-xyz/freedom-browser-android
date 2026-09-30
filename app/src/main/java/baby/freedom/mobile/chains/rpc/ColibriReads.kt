package baby.freedom.mobile.chains.rpc

/**
 * The *Colibri proofs* switch (Settings → Name resolution) as this
 * process's chain-data router reads it ([ColibriChainSource]): the one
 * switch covers name lookups and the router's wallet and Swarm-node
 * reads, so turning it off sends nothing to the prover.
 *
 * The setting lives in the UI process's DataStore, which another process
 * can't watch, so it's pushed here: in the app process by `MainActivity`
 * as it changes, and in `:node` by the same relay over
 * [baby.freedom.mobile.node.INodeService.setColibriReads] on every bind
 * and change, with the stored value read once at `:node`'s start
 * ([seed]) until a relay lands. Until either has, it reads as off: a
 * read then starts at the quorum rather than tell the prover anything
 * the user may have turned off.
 */
object ColibriReads {
    /** `null` until known. */
    @Volatile
    private var on: Boolean? = null

    /** Whether the router may ask the Colibri prover. */
    val enabled: Boolean get() = on == true

    /** The switch as it changed (the relay). Wins over [seed]. */
    fun set(enabled: Boolean) {
        synchronized(this) { on = enabled }
    }

    /** The stored value read at start: only when no [set] has landed yet. */
    fun seed(enabled: Boolean) {
        synchronized(this) { if (on == null) on = enabled }
    }

    /** For tests. */
    internal fun reset() {
        synchronized(this) { on = null }
    }
}
