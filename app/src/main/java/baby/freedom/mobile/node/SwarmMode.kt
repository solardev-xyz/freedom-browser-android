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
 * The Gnosis JSON-RPC endpoint handed to ant as its configured RPC. Its
 * reads don't use it — they go through the chain-data router
 * ([gnosisChainFor], #273) — but a broadcast SpendGuard admits goes out on
 * it. ant takes exactly one, so it's the first the router would ask too:
 * the user's own Gnosis RPC (Settings → Chains → Gnosis → Your RPCs, #108)
 * when there is one, else the chain's first public one. The shipped Gnosis
 * list stands in when the chain list has none at all.
 */
internal fun gnosisRpcFor(chains: List<Chain>): String {
    val gnosis = chains.firstOrNull { it.id == BuiltInChains.GNOSIS.id }
    return (gnosis?.userRpcUrls.orEmpty() + gnosis?.rpcUrls.orEmpty()).firstOrNull { it.isNotBlank() }
        ?: BuiltInChains.GNOSIS.rpcUrls.first()
}

/**
 * The Gnosis chain as the Swarm node's reads see it (#273): the chain's
 * own entry in [chains] — its RPCs and the user's — with the shipped list
 * standing in when it has no RPC at all, as for [gnosisRpcFor].
 */
internal fun gnosisChainFor(chains: List<Chain>): Chain {
    val gnosis = chains.firstOrNull { it.id == BuiltInChains.GNOSIS.id } ?: return BuiltInChains.GNOSIS
    return if (gnosis.rpcUrls.none { it.isNotBlank() } && gnosis.userRpcUrls.none { it.isNotBlank() }) {
        gnosis.copy(rpcUrls = BuiltInChains.GNOSIS.rpcUrls)
    } else {
        gnosis
    }
}

/**
 * What [SwarmBootIdentity] tracks a launch as: the Swarm account it boots
 * as (`""` for ant's own) and its mode, so a change to either restarts the
 * node once. Kept in memory only — the RPC in it can carry an API key.
 */
internal fun swarmBootKey(swarmAddress: String, mode: SwarmNode.Mode): String =
    "${swarmAddress.lowercase()}|${if (mode.light) "light" else "ultra-light"}|${mode.gnosisRpc}"
