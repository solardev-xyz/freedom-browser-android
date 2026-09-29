package baby.freedom.mobile.node

import baby.freedom.mobile.chains.BuiltInChains
import baby.freedom.mobile.chains.Chain
import baby.freedom.swarm.SwarmNode

/**
 * The Swarm node's mode (#114) for the light-mode setting and the chains
 * the user has: ultra-light, or light against [gnosisRpcFor] those chains.
 */
internal fun swarmModeFor(light: Boolean, chains: List<Chain>): SwarmNode.Mode =
    if (light) SwarmNode.Mode.light(gnosisRpcFor(chains)) else SwarmNode.Mode.ULTRA_LIGHT

/**
 * The Gnosis JSON-RPC endpoint a light node reads the chain through. ant
 * takes exactly one, so it's the first the chain-data router would ask
 * too: the user's own Gnosis RPC (Settings → Chains → Gnosis → Your RPCs,
 * #108) when there is one, else the chain's first public one. The shipped
 * Gnosis list stands in when the chain list has none at all.
 */
internal fun gnosisRpcFor(chains: List<Chain>): String {
    val gnosis = chains.firstOrNull { it.id == BuiltInChains.GNOSIS.id }
    return (gnosis?.userRpcUrls.orEmpty() + gnosis?.rpcUrls.orEmpty()).firstOrNull { it.isNotBlank() }
        ?: BuiltInChains.GNOSIS.rpcUrls.first()
}

/**
 * What [SwarmBootIdentity] tracks a launch as: the Swarm account it boots
 * as (`""` for ant's own) and its mode, so a change to either restarts the
 * node once. Kept in memory only — the RPC in it can carry an API key.
 */
internal fun swarmBootKey(swarmAddress: String, mode: SwarmNode.Mode): String =
    "${swarmAddress.lowercase()}|${if (mode.light) "light" else "ultra-light"}|${mode.gnosisRpc}"
