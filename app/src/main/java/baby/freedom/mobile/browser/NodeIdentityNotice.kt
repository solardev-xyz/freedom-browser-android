package baby.freedom.mobile.browser

import baby.freedom.mobile.R
import baby.freedom.mobile.l10n.Strings
import baby.freedom.mobile.wallet.NodeIdentitySync

/**
 * The notice for a node identity switch (#77, #328; maintainer decision
 * 10), or null when there's nothing to tell. [restarting]: the Swarm node
 * is running, so it restarts now; otherwise it picks the identity up
 * whenever it next starts. [radicleOn]: the user has the Radicle node on,
 * so it's mentioned too — [radicleRestarting] if it's running now. A
 * Radicle node that's off isn't mentioned; it starts as the wallet's
 * identity whenever it's turned on.
 *
 * A wallet that has just been set up (W8) is told as only that: "Wallet
 * created" — [justCreated], a new phrase not shown yet — or "Wallet
 * ready" (imported, restored). That the nodes now use its identity is
 * jargon at that moment; the node page says it (Identity: From your
 * wallet). Only a Radicle-only change of a wallet that was already here
 * still names the node.
 */
internal fun nodeIdentityNotice(
    change: NodeIdentitySync.Change,
    restarting: Boolean,
    radicleOn: Boolean = false,
    radicleRestarting: Boolean = false,
    justCreated: Boolean = false,
): String? {
    val parts = when (change) {
        // The same identities, sealed again: nothing to tell.
        NodeIdentitySync.Change.Resealed, NodeIdentitySync.Change.Unchanged -> emptyList()
        is NodeIdentitySync.Change.Adopted -> if (change.swarmChanged) {
            listOf(Strings.get(if (justCreated) R.string.wallet_created_notice else R.string.wallet_ready_notice))
        } else listOfNotNull(
            if (!radicleOn || change.radicleDid.isEmpty()) {
                null
            } else if (radicleRestarting) {
                Strings.get(R.string.node_identity_radicle_adopted_restarting, shortDid(change.radicleDid))
            } else {
                Strings.get(R.string.node_identity_radicle_adopted_next_start, shortDid(change.radicleDid))
            },
        )
        NodeIdentitySync.Change.Dropped -> listOfNotNull(
            if (restarting) {
                Strings.get(R.string.node_identity_dropped_restarting)
            } else {
                Strings.get(R.string.node_identity_dropped_next_start)
            },
            if (!radicleOn) {
                null
            } else if (radicleRestarting) {
                Strings.get(R.string.node_identity_radicle_dropped_restarting)
            } else {
                Strings.get(R.string.node_identity_radicle_dropped_next_start)
            },
        )
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
}

/** `0x6Fac4D18…4Ab9C0` → `0x6Fac…b9C0`. */
internal fun shortAddress(address: String): String =
    if (address.length > 12) "${address.take(6)}…${address.takeLast(4)}" else address

/** `did:key:z6Mkgb93Mj…QX8gAn` → `z6Mkgb…8gAn`. */
internal fun shortDid(did: String): String = did.removePrefix("did:key:").let {
    if (it.length > 12) "${it.take(6)}…${it.takeLast(4)}" else it
}
