package baby.freedom.mobile.browser

import baby.freedom.mobile.R
import baby.freedom.mobile.l10n.Strings
import baby.freedom.mobile.wallet.NodeIdentitySync

/**
 * The notice for a node identity switch (#77, maintainer decision 10).
 * [restarting]: the Swarm node is running, so it restarts now; otherwise
 * it picks the identity up whenever it next starts.
 */
internal fun nodeIdentityNotice(change: NodeIdentitySync.Change, restarting: Boolean): String = when (change) {
    is NodeIdentitySync.Change.Adopted -> {
        val address = shortAddress(change.swarmAddress)
        if (restarting) {
            Strings.get(R.string.node_identity_adopted_restarting, address)
        } else {
            Strings.get(R.string.node_identity_adopted_next_start, address)
        }
    }
    NodeIdentitySync.Change.Dropped ->
        if (restarting) {
            Strings.get(R.string.node_identity_dropped_restarting)
        } else {
            Strings.get(R.string.node_identity_dropped_next_start)
        }
}

/** `0x6Fac4D18…4Ab9C0` → `0x6Fac…b9C0`. */
internal fun shortAddress(address: String): String =
    if (address.length > 12) "${address.take(6)}…${address.takeLast(4)}" else address
