package baby.freedom.mobile.browser

import baby.freedom.mobile.wallet.NodeIdentitySync

/**
 * The notice for a node identity switch (#77, maintainer decision 10).
 * [restarting]: the Swarm node is running, so it restarts now; otherwise
 * it picks the identity up whenever it next starts.
 */
internal fun nodeIdentityNotice(change: NodeIdentitySync.Change, restarting: Boolean): String = when (change) {
    is NodeIdentitySync.Change.Adopted -> {
        val who = "your wallet's identity (${shortAddress(change.swarmAddress)})"
        if (restarting) {
            "Your Swarm node now uses $who. Restarting it…"
        } else {
            "Your Swarm node will use $who when it next starts."
        }
    }
    NodeIdentitySync.Change.Dropped ->
        if (restarting) {
            "Wallet removed. Your Swarm node is restarting with this device's own identity."
        } else {
            "Wallet removed. Your Swarm node will use this device's own identity."
        }
}

/** `0x6Fac4D18…4Ab9C0` → `0x6Fac…b9C0`. */
internal fun shortAddress(address: String): String =
    if (address.length > 12) "${address.take(6)}…${address.takeLast(4)}" else address
