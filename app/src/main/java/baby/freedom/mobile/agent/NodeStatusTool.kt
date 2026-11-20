package baby.freedom.mobile.agent

import baby.freedom.swarm.IpfsInfo
import baby.freedom.swarm.IpfsStatus
import baby.freedom.swarm.MyotisInfo
import baby.freedom.swarm.MyotisNetwork
import baby.freedom.swarm.MyotisStatus
import baby.freedom.swarm.NodeInfo
import baby.freedom.swarm.NodeStatus
import baby.freedom.swarm.RadicleInfo
import baby.freedom.swarm.RadicleStatus
import baby.freedom.swarm.TorInfo
import baby.freedom.swarm.TorStatus
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * What `node_status` (#577) reads: the live node state the browser
 * screen already has, as plain values. Never a lambda or anything else
 * that holds the Activity ([AgentNodes] outlives a screen), and never
 * read whole into the tool's answer: [NodeStatusReport] takes only
 * states and counts from it.
 */
internal data class NodeSnapshot(
    /** The Swarm node (its peer count isn't reported, as on desktop). */
    val swarm: NodeInfo = NodeInfo(),
    /** Settings' Run node switch: the Swarm and IPFS nodes' own. */
    val runNode: Boolean = false,
    /** bzz:// goes to the user's own Swarm endpoint (Settings → Nodes & networks), not this phone's. */
    val externalSwarm: Boolean = false,
    val ipfs: IpfsInfo = IpfsInfo(),
    val externalIpfs: Boolean = false,
    val radicle: RadicleInfo = RadicleInfo(),
    val radicleEnabled: Boolean = false,
    /** What the Tor page shows ([baby.freedom.mobile.browser.torShownInfo]). */
    val tor: TorInfo = TorInfo(),
    val torEnabled: Boolean = false,
    /** Tor through Orbot rather than the built-in Arti. */
    val torProxy: Boolean = false,
    val myotis: MyotisInfo = MyotisInfo(),
    /** The light client's chains switched on. */
    val myotisRunning: Set<MyotisNetwork> = emptySet(),
)

/**
 * Where `node_status` finds the browser's node state: [source], installed
 * by the browser screen while it's up, null otherwise.
 */
internal object AgentNodes {
    @Volatile
    var source: (() -> NodeSnapshot)? = null
}

/**
 * Desktop's `node_status` (`node-status-controller.js`): a read-only,
 * point-in-time view of the integrated services, with desktop's ids,
 * fields and states. Only lifecycle states and counts leave the phone:
 * no address, peer or node ID, endpoint, port or error text, which are
 * the user's and the model doesn't need.
 */
internal object NodeStatusReport {
    private data class Definition(val id: String, val name: String, val implementation: String, val protocols: List<String>)

    private val ANT = Definition("ant", "Swarm", "Ant", listOf("bzz"))
    private val IPFS = Definition("ipfs", "IPFS", "Freedom IPFS", listOf("ipfs", "ipns"))
    private val RADICLE = Definition("radicle", "Radicle", "Radicle Node", listOf("rad"))
    private val TOR = Definition("tor", "Tor", "Arti", listOf("onion"))
    private val ORBOT = TOR.copy(implementation = "Orbot")
    private val MYOTIS = mapOf(
        MyotisNetwork.Mainnet to Definition("myotis-ethereum", "Ethereum light client", "Myotis", listOf("ethereum")),
        MyotisNetwork.Gnosis to Definition("myotis-gnosis", "Gnosis light client", "Myotis", listOf("gnosis")),
    )

    fun build(s: NodeSnapshot): JSONObject {
        val nodes = listOf(
            if (s.externalSwarm) external(ANT) else manager(ANT, s.runNode, swarmState(s.swarm.status)),
            if (s.externalIpfs) external(IPFS) else manager(IPFS, s.runNode, ipfsState(s.ipfs.status)),
            manager(RADICLE, s.radicleEnabled, radicleState(s.radicle.status)),
            manager(if (s.torProxy) ORBOT else TOR, s.torEnabled, torState(s.tor.status), mode = if (s.torProxy) "external" else "embedded"),
        ) + MyotisNetwork.entries.mapNotNull { network -> MYOTIS[network]?.let { myotis(it, network, s) } }
        return JSONObject().put("nodes", JSONArray(nodes)).put("summary", summary(nodes))
    }

    private fun swarmState(status: NodeStatus) = when (status) {
        NodeStatus.Stopped -> "stopped"
        NodeStatus.Starting -> "starting"
        NodeStatus.Running -> "running"
        NodeStatus.Error -> "error"
    }

    private fun ipfsState(status: IpfsStatus) = when (status) {
        IpfsStatus.Stopped -> "stopped"
        IpfsStatus.Starting -> "starting"
        IpfsStatus.Running -> "running"
        IpfsStatus.Error -> "error"
    }

    private fun radicleState(status: RadicleStatus) = when (status) {
        RadicleStatus.Stopped -> "stopped"
        RadicleStatus.Starting -> "starting"
        RadicleStatus.Running -> "running"
        RadicleStatus.Stopping -> "stopping"
        RadicleStatus.Error -> "error"
    }

    private fun torState(status: TorStatus) = when (status) {
        TorStatus.Stopped -> "stopped"
        TorStatus.Starting -> "starting"
        TorStatus.Running -> "running"
        TorStatus.Error -> "error"
    }

    private fun base(d: Definition) = JSONObject()
        .put("id", d.id)
        .put("name", d.name)
        .put("implementation", d.implementation)
        .put("protocols", JSONArray(d.protocols))

    /** Desktop's `normalizeManagerNode`: a service switched off is `disabled` whatever its process does. */
    private fun manager(d: Definition, enabled: Boolean, rawState: String, mode: String = "embedded"): JSONObject {
        val state = if (enabled) rawState else "disabled"
        val ready = state == "running"
        return base(d)
            .put("state", state)
            .put("mode", if (state == "disabled") "disabled" else mode)
            .put("running", ready)
            .put("ready", ready)
            .apply { recovery(state)?.let { put("recovery", it) } }
    }

    /**
     * The user's own endpoint (Settings → Nodes & networks) serves the
     * scheme: Freedom has no live reading of it, so it's reported as the
     * Nodes & networks page shows it, in service, with mode `external`.
     */
    private fun external(d: Definition): JSONObject = base(d)
        .put("state", "running")
        .put("mode", "external")
        .put("running", true)
        .put("ready", true)

    /** Desktop's `normalizeMyotisNode`, from the light client page's own reading of the chain. */
    private fun myotis(d: Definition, network: MyotisNetwork, s: NodeSnapshot): JSONObject {
        val on = network in s.myotisRunning
        val chain = s.myotis.chain(network)
        val state = when {
            !on -> "disabled"
            else -> when (s.myotis.status) {
                MyotisStatus.Stopped -> "stopped"
                MyotisStatus.Error -> "error"
                MyotisStatus.Starting -> "syncing"
                MyotisStatus.Running -> when {
                    chain == null -> "syncing"
                    chain.error != null -> "error"
                    chain.paused || !chain.running -> "stopped"
                    chain.ready -> "ready"
                    else -> "syncing"
                }
            }
        }
        val ready = state == "ready"
        return base(d)
            .put("chainId", network.chainId)
            .put("state", state)
            .put("mode", if (state == "disabled") "disabled" else "embedded")
            .put("running", ready || state == "syncing")
            .put("ready", ready)
            .apply {
                if (on && chain != null) put("peerCount", chain.peerCount.coerceIn(0, 1_000_000))
                recovery(state)?.let { put("recovery", it) }
            }
    }

    private fun recovery(state: String): String? = when (state) {
        "disabled" -> "Enable this service in Freedom settings if the task needs it."
        "error" -> "Open Freedom's Nodes & networks page for recovery details."
        else -> null
    }

    private fun summary(nodes: List<JSONObject>): JSONObject {
        val active = setOf("starting", "running", "syncing", "ready", "stopping")
        val attention = setOf("error", "unavailable")
        return JSONObject()
            .put("total", nodes.size)
            .put("ready", nodes.count { it.getBoolean("ready") })
            .put("active", nodes.count { it.getString("state") in active })
            .put("disabled", nodes.count { it.getString("state") == "disabled" })
            .put("attention", nodes.count { it.getString("state") in attention })
    }
}

/** `node_status` (#577): no arguments, no approval — it only reads. */
internal class NodeStatusTool(
    private val source: () -> (() -> NodeSnapshot)? = { AgentNodes.source },
    /** Where the snapshot is read: the main thread, where the screen's state is written. */
    private val reading: CoroutineContext = Dispatchers.Main,
) : AgentTool {
    override val spec = ToolSpec(
        name = NAME,
        description = "Inspect the current safe lifecycle and readiness state of Freedom's integrated Swarm, IPFS, " +
            "Radicle, Tor, and Myotis services. This read-only tool cannot start, stop, configure, fund, or reset a node.",
        parameters = JSONObject().put("type", "object").put("properties", JSONObject()).put("additionalProperties", false),
    )

    override suspend fun run(arguments: JSONObject): ToolOutcome {
        val snapshot = withContext(reading) { source()?.invoke() }
            ?: return ToolError(ToolError.CAPABILITY_UNAVAILABLE, "Freedom's node status isn't available right now.").outcome(NAME)
        return ToolOutcome(JSONObject().put("ok", true).put("result", NodeStatusReport.build(snapshot)).toString())
    }

    companion object {
        const val NAME = "node_status"
    }
}
