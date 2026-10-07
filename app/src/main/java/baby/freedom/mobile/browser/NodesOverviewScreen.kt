package baby.freedom.mobile.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import baby.freedom.mobile.R
import baby.freedom.mobile.data.NodeSettings
import baby.freedom.mobile.ens.EnsRpcConfig
import baby.freedom.mobile.l10n.Strings
import baby.freedom.swarm.IpfsInfo
import baby.freedom.swarm.IpfsStatus
import baby.freedom.swarm.MyotisInfo
import baby.freedom.swarm.MyotisNetwork
import baby.freedom.swarm.NodeInfo
import baby.freedom.swarm.NodeStatus
import baby.freedom.swarm.RadicleInfo
import baby.freedom.swarm.RadicleStatus
import baby.freedom.swarm.TorInfo
import baby.freedom.swarm.TorStatus
import java.text.NumberFormat

/*
 * Nodes & networks (#416): one calm list of everything the browser
 * reaches the decentralized web through — the nodes on this phone, then
 * the remote services it asks — each a status dot, a name, one line of
 * state and a chevron to its details. The menu's last row opens it, and
 * so does Settings → Nodes & networks → Node status, so there is one
 * status page, not two.
 *
 * The rows are a pure function of the live state ([nodeOverviewRows]),
 * built from the same mappings the detail pages draw their own status
 * line with, so a row never says something its page doesn't.
 */

/** Where a row of the overview leads. */
enum class NodeDestination {
    Swarm,
    Ipfs,
    Radicle,
    Tor,
    LightClient,

    /** Settings → Name resolution: the RPC providers, Colibri. */
    Rpc,

    /** Settings → Nodes & networks: your own Swarm / IPFS endpoints. */
    Gateways,
}

/** The two halves of the overview. */
internal enum class NodeOverviewGroup { OnDevice, Remote }

/**
 * A row's dot: the colours the detail pages already use for their
 * status icon ([nodeStatusTriple] and friends). [None] is a remote
 * service there's no live reading of — its line is configuration only.
 */
internal enum class NodeHealth { Ok, Busy, Off, Error, None }

internal data class NodeOverviewRow(
    val destination: NodeDestination,
    val group: NodeOverviewGroup,
    val title: String,
    val status: String,
    val health: NodeHealth,
)

/** Everything the overview reads; all of it is already live in [BrowserScreen]. */
internal data class NodeOverviewInput(
    val nodeInfo: NodeInfo = NodeInfo(),
    val externalSwarm: String = "",
    val ipfsInfo: IpfsInfo = IpfsInfo(),
    val externalIpfs: String = "",
    val radicleInfo: RadicleInfo = RadicleInfo(),
    val radicleEnabled: Boolean = false,
    val tor: TorControls = TorControls(),
    val myotisInfo: MyotisInfo = MyotisInfo(),
    /** The light client's chains switched on; null until read (shown as none). */
    val myotisRunning: Set<MyotisNetwork>? = emptySet(),
    /** Null until read: the remote rows say "Checking…" meanwhile. */
    val rpcConfig: EnsRpcConfig? = null,
)

/** The overview's rows, in order: this device's nodes, then the remote services. */
internal fun nodeOverviewRows(input: NodeOverviewInput): List<NodeOverviewRow> = listOf(
    swarmRow(input),
    ipfsRow(input.ipfsInfo, input.externalIpfs),
    radicleRow(input.radicleInfo, input.radicleEnabled),
    torRow(input.tor),
    lightClientRow(input.myotisInfo, input.myotisRunning.orEmpty()),
    NodeOverviewRow(
        NodeDestination.Rpc,
        NodeOverviewGroup.Remote,
        Strings.get(R.string.nodes_overview_rpc),
        input.rpcConfig?.let { namesPageSummary(it.colibri, it.sources.size) }
            ?: Strings.get(R.string.node_checking),
        NodeHealth.None,
    ),
    NodeOverviewRow(
        NodeDestination.Gateways,
        NodeOverviewGroup.Remote,
        Strings.get(R.string.nodes_overview_gateways),
        Strings.get(
            R.string.nodes_overview_gateways_summary,
            gatewaySource(input.externalSwarm),
            gatewaySource(input.externalIpfs),
        ),
        NodeHealth.None,
    ),
)

private fun gatewaySource(external: String): String = Strings.get(
    if (external.isEmpty()) R.string.nodes_overview_source_device else R.string.nodes_overview_source_own,
)

private fun swarmRow(input: NodeOverviewInput): NodeOverviewRow {
    val info = input.nodeInfo
    val triple = nodeStatusTriple(info.status)
    val (status, health) = when {
        // Settings → Nodes & networks (#125): bzz:// goes to the user's
        // own node whatever this one does, as the Swarm page says.
        input.externalSwarm.isNotEmpty() ->
            Strings.get(R.string.nodes_overview_swarm_external, input.externalSwarm) to NodeHealth.Ok
        info.status == NodeStatus.Running -> listOf(
            triple.label,
            Strings.plural(R.plurals.nodes_overview_peers, info.connectedPeers.toCountInt(), formatCount(info.connectedPeers)),
            swarmModeLabel(info.lightMode),
        ).joinToString(" · ") to triple.health
        else -> triple.label to triple.health
    }
    return NodeOverviewRow(NodeDestination.Swarm, NodeOverviewGroup.OnDevice, swarmTitle(), status, health)
}

private fun ipfsRow(info: IpfsInfo, externalIpfs: String): NodeOverviewRow {
    val (status, health) = when {
        externalIpfs.isNotEmpty() ->
            Strings.get(R.string.nodes_overview_ipfs_external, externalIpfs) to NodeHealth.Ok
        else -> {
            // The IPFS page's own status line: Running / Connecting… /
            // Off (not "Disconnected", #416) / Error. freedom-ipfs is an
            // on-demand reader with no peer set (its "peers" figure is
            // blocks fetched), so Running gives no count.
            val triple = ipfsStatusTriple(info)
            triple.label to when (info.status) {
                IpfsStatus.Running -> NodeHealth.Ok
                IpfsStatus.Starting -> NodeHealth.Busy
                IpfsStatus.Stopped -> NodeHealth.Off
                IpfsStatus.Error -> NodeHealth.Error
            }
        }
    }
    return NodeOverviewRow(NodeDestination.Ipfs, NodeOverviewGroup.OnDevice, ipfsTitle(), status, health)
}

private fun radicleRow(info: RadicleInfo, enabled: Boolean): NodeOverviewRow {
    val health = when {
        !enabled -> NodeHealth.Off
        info.status == RadicleStatus.Running -> NodeHealth.Ok
        info.status == RadicleStatus.Error -> NodeHealth.Error
        else -> NodeHealth.Busy
    }
    return NodeOverviewRow(
        NodeDestination.Radicle,
        NodeOverviewGroup.OnDevice,
        Strings.get(R.string.nodes_overview_radicle),
        radicleSummary(info, enabled),
        health,
    )
}

private fun torRow(tor: TorControls): NodeOverviewRow {
    val triple = torStatusTriple(torShownInfo(tor))
    return NodeOverviewRow(NodeDestination.Tor, NodeOverviewGroup.OnDevice, torTitle(), triple.label, triple.health)
}

private fun lightClientRow(info: MyotisInfo, running: Set<MyotisNetwork>): NodeOverviewRow {
    val shown = lightClientInfoFor(info, running)
    val triple = lightClientStatusTriple(shown)
    // Per chain, as the page's chain rows read: "Ethereum: Synced · Gnosis: Off".
    val status = MyotisNetwork.entries.joinToString(" · ") { network ->
        Strings.get(
            R.string.nodes_overview_chain_state,
            network.displayName,
            myotisChainLabel(shown.status, shown.chain(network), network in running),
        )
    }
    return NodeOverviewRow(
        NodeDestination.LightClient,
        NodeOverviewGroup.OnDevice,
        lightClientTitle(),
        status,
        triple.health,
    )
}

/**
 * The menu row's sub-line (#416): only when a node is in trouble — its
 * name, or how many — and nothing at all otherwise, so the menu stays
 * quiet and doesn't change with every peer that comes and goes.
 */
internal fun nodesMenuNote(rows: List<NodeOverviewRow>): String? {
    val failing = rows.filter { it.health == NodeHealth.Error }
    return when (failing.size) {
        0 -> null
        1 -> Strings.get(R.string.nodes_menu_problem_one, failing.single().title)
        else -> Strings.plural(R.plurals.nodes_menu_problem_many, failing.size, failing.size)
    }
}

/** The status the Tor page shows: off unless switched on and running (or failed). */
internal fun torShownInfo(tor: TorControls): TorInfo =
    if (tor.enabled && (tor.running || tor.info.status == TorStatus.Error)) {
        tor.info
    } else {
        TorInfo(version = tor.info.version)
    }

/** The dot of a detail page's own status icon, read off its colour so the two can't drift apart. */
internal val NodeStatusTriple.health: NodeHealth
    get() = when (color) {
        STATUS_GREEN -> NodeHealth.Ok
        STATUS_AMBER -> NodeHealth.Busy
        STATUS_RED -> NodeHealth.Error
        else -> NodeHealth.Off
    }

internal val STATUS_GREEN = Color(0xFF22C55E)
internal val STATUS_AMBER = Color(0xFFF59E0B)
internal val STATUS_GREY = Color(0xFF94A3B8)
internal val STATUS_RED = Color(0xFFEF4444)

private fun NodeHealth.color(): Color? = when (this) {
    NodeHealth.Ok -> STATUS_GREEN
    NodeHealth.Busy -> STATUS_AMBER
    NodeHealth.Off -> STATUS_GREY
    NodeHealth.Error -> STATUS_RED
    NodeHealth.None -> null
}

private fun Long.toCountInt(): Int = coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

private fun formatCount(n: Long): String = NumberFormat.getIntegerInstance().format(n)

internal fun swarmTitle(): String = Strings.get(R.string.nodes_overview_swarm)
internal fun ipfsTitle(): String = Strings.get(R.string.settings_section_ipfs)
internal fun torTitle(): String = Strings.get(R.string.node_tor)
internal fun lightClientTitle(): String = Strings.get(R.string.node_light_client)

/**
 * The Nodes & networks page (#416). [onOpen] goes to a row's details:
 * a node's own page, or Settings at the page that configures a remote
 * service.
 */
@Composable
internal fun NodesOverviewScreen(
    input: NodeOverviewInput,
    onOpen: (NodeDestination) -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val context = LocalContext.current
    val settings = remember(context) { NodeSettings.get(context) }
    val externalSwarm by remember(settings) { settings.externalSwarmEndpoint }.collectAsState(initial = "")
    val externalIpfs by remember(settings) { settings.externalIpfsGateway }.collectAsState(initial = "")
    val rpcConfig by remember(settings) { settings.ensRpcConfig }.collectAsState(initial = null)
    val rows = nodeOverviewRows(
        input.copy(externalSwarm = externalSwarm, externalIpfs = externalIpfs, rpcConfig = rpcConfig),
    )
    FullScreenScaffold(
        title = stringResource(R.string.settings_page_nodes),
        onDismiss = onDismiss,
    ) {
        LazyColumn(
            contentPadding = PaddingValues(vertical = 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            for (group in NodeOverviewGroup.entries) {
                val groupRows = rows.filter { it.group == group }
                item("group:${group.name}") {
                    Text(
                        stringResource(
                            when (group) {
                                NodeOverviewGroup.OnDevice -> R.string.nodes_overview_on_device
                                NodeOverviewGroup.Remote -> R.string.nodes_overview_remote
                            },
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
                            .semantics { heading() },
                    )
                }
                groupRows.forEachIndexed { index, row ->
                    item(row.destination.name) {
                        NodeOverviewRowItem(row) { onOpen(row.destination) }
                        if (index < groupRows.lastIndex) {
                            HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NodeOverviewRowItem(row: NodeOverviewRow, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val dot = row.health.color()
        if (dot != null) {
            // A status dot, centred in the 24 dp an icon would take, so
            // the text lines up with the remote rows' cloud.
            Box(
                modifier = Modifier.width(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(12.dp)) {
                    drawCircle(dot)
                }
            }
        } else {
            Icon(
                Icons.Filled.Cloud,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(16.dp))
        // Name and state wrap rather than cut, so a large font or a narrow
        // screen still shows the whole line.
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(row.title, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            Text(
                row.status,
                style = MaterialTheme.typography.bodySmall,
                color = if (row.health == NodeHealth.Error) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The IPFS node's details (#416), from the overview: the card Settings ›
 * Nodes & networks shows — its switch, status, gateway, routing mode and
 * Logs — on a page of its own.
 */
@Composable
internal fun IpfsScreen(
    ipfsInfo: IpfsInfo,
    onIpfsToggle: (Boolean) -> Unit,
    onOpenLogs: () -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val context = LocalContext.current
    val settings = remember(context) { NodeSettings.get(context) }
    FullScreenScaffold(title = ipfsTitle(), onDismiss = onDismiss) {
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item("ipfs") {
                IpfsSection(
                    visible = setOf("status", "routing"),
                    settings = settings,
                    ipfsInfo = ipfsInfo,
                    onIpfsToggle = onIpfsToggle,
                    onOpenLogs = onOpenLogs,
                )
            }
        }
    }
}
