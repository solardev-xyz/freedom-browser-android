package baby.freedom.mobile.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import baby.freedom.mobile.data.NodeSettings
import baby.freedom.mobile.node.NodeLogSource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import baby.freedom.swarm.MyotisChainStatus
import baby.freedom.swarm.MyotisInfo
import baby.freedom.swarm.MyotisNetwork
import baby.freedom.swarm.MyotisRecovery
import baby.freedom.swarm.MyotisStatus
import baby.freedom.swarm.NodeInfo
import baby.freedom.swarm.NodeStatus
import baby.freedom.swarm.SwarmNode
import baby.freedom.swarm.TorInfo
import baby.freedom.swarm.TorStatus
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Full-screen node-details page: the Swarm node's live status, peer
 * count, gateway URL and run-node on/off toggle, its mode and the ways into
 * publish setup (#114, [PublishSetupScreen]), the chequebook (#117,
 * [ChequebookScreen]), the postage stamps (#116, [StampsScreen]) and the
 * Publish page (#118, [PublishScreen]), the Tor client (#143)
 * with its start/stop switch, status and version, then the Myotis
 * Ethereum / Gnosis light client (#72) with a switch and a start-at-launch
 * choice per chain (#274) and each chain's sync state. Shares the same [FullScreenScaffold] chrome as Settings /
 * History / Bookmarks.
 */
@Composable
fun NodeScreen(
    nodeInfo: NodeInfo,
    runNodeEnabled: Boolean,
    onToggleRunNode: (Boolean) -> Unit,
    myotisInfo: MyotisInfo,
    /** The light client's chains switched on (#274); null until the launch choice is read. */
    myotisRunning: Set<MyotisNetwork>?,
    onRunMyotisChain: (MyotisNetwork, Boolean) -> Unit,
    onDismiss: () -> Unit,
    tor: TorControls = TorControls(),
    /** A chain's Retry (`repair = false`) or Repair sync data (`true`) on a blocked or waiting recovery. */
    onMyotisRecovery: (chainId: Long, repair: Boolean) -> Unit = { _, _ -> },
    /** Open the wallet page, for publish setup's identity step (#114). */
    onOpenWallet: () -> Unit = {},
    /**
     * Open a URL in a new tab: a published page's bzz:// link (#118), or
     * a transaction's explorer page, from the fund-and-buy page (#115).
     */
    onOpenUrl: (String) -> Unit = {},
    /** Open the node logs page (#276) at this node's. */
    onOpenLogs: (NodeLogSource) -> Unit = {},
) {
    val triple = nodeStatusTriple(nodeInfo.status)
    val context = LocalContext.current
    val settings = remember(context) { NodeSettings.get(context) }
    val externalSwarm by remember(settings) { settings.externalSwarmEndpoint }
        .collectAsState(initial = "")
    // The mode setting (#114); MainActivity relays it to the node, which
    // restarts into it. Null until read, so the switch doesn't flicker.
    val lightModeWanted by remember(settings) { settings.swarmLightMode }
        .collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val setLightMode: (Boolean) -> Unit = { light -> scope.launch { settings.setSwarmLightMode(light) } }
    // The light client's chains that start at launch (#274); null until read.
    val myotisAtLaunch by remember(settings) { settings.myotisStartOnLaunch }
        .collectAsState(initial = null)
    var showPublishSetup by rememberSaveable { mutableStateOf(false) }
    // The stamp pages (#116): "list", "buy" (from publish setup), or null.
    var showStamps by rememberSaveable { mutableStateOf<String?>(null) }
    // The Publish page (#118); the stamp and setup pages it links to open over it.
    var showPublish by rememberSaveable { mutableStateOf(false) }
    // The chequebook page (#117).
    var showChequebook by rememberSaveable { mutableStateOf(false) }
    // Fund the node and buy a stamp in one wallet transaction (#115).
    var showFund by rememberSaveable { mutableStateOf(false) }

    if (showFund) {
        // Back lands on publish setup, which opened it.
        FundNodeScreen(nodeInfo = nodeInfo, onOpenUrl = onOpenUrl, onDismiss = { showFund = false })
        return
    }
    if (showChequebook) {
        // Back lands on whichever page opened it.
        ChequebookScreen(nodeInfo = nodeInfo, onDismiss = { showChequebook = false })
        return
    }
    showStamps?.let { start ->
        // Back from the stamps lands on whichever page opened them.
        StampsScreen(nodeInfo = nodeInfo, startWithBuy = start == "buy", onDismiss = { showStamps = null })
        return
    }
    if (showPublishSetup) {
        PublishSetupScreen(
            nodeInfo = nodeInfo,
            lightModeWanted = lightModeWanted == true,
            onSwitchToLightMode = { setLightMode(true) },
            onOpenWallet = onOpenWallet,
            onBuyStamp = { showStamps = "buy" },
            onOpenChequebook = { showChequebook = true },
            onDismiss = { showPublishSetup = false },
            onFundAndBuy = { showFund = true },
        )
        return
    }
    if (showPublish) {
        PublishScreen(
            nodeInfo = nodeInfo,
            onOpenStamps = { showStamps = "list" },
            onOpenSetup = { showPublishSetup = true },
            onOpenUrl = onOpenUrl,
            onDismiss = { showPublish = false },
        )
        return
    }
    BackHandler(onBack = onDismiss)

    FullScreenScaffold(
        title = "Nodes",
        onDismiss = onDismiss,
    ) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item("status") {
                StatusSection(
                    triple = triple,
                    runNodeEnabled = runNodeEnabled,
                    external = externalSwarm.isNotEmpty(),
                    onToggleRunNode = onToggleRunNode,
                    onOpenLogs = { onOpenLogs(NodeLogSource.Swarm) },
                )
            }
            item("details") {
                DetailsSection(nodeInfo = nodeInfo)
            }
            item("publishing") {
                PublishingSection(
                    nodeInfo = nodeInfo,
                    lightModeWanted = lightModeWanted,
                    onSetLightMode = setLightMode,
                    onOpenSetup = { showPublishSetup = true },
                    onOpenStamps = { showStamps = "list" },
                    onOpenPublish = { showPublish = true },
                    onOpenChequebook = { showChequebook = true },
                )
            }
            item("gateway") {
                GatewaySection(externalSwarm = externalSwarm)
            }
            item("tor") {
                TorSection(tor, onOpenLogs = { onOpenLogs(NodeLogSource.Tor) })
            }
            item("myotis") {
                LightClientSection(
                    info = myotisInfo,
                    running = myotisRunning,
                    onRun = onRunMyotisChain,
                    atLaunch = myotisAtLaunch,
                    onAtLaunch = { network, on -> scope.launch { settings.setMyotisStartOnLaunch(network, on) } },
                    onRecovery = onMyotisRecovery,
                    onOpenLogs = { onOpenLogs(NodeLogSource.LightClient) },
                )
            }
        }
    }
}

@Composable
private fun StatusSection(
    triple: NodeStatusTriple,
    runNodeEnabled: Boolean,
    external: Boolean,
    onToggleRunNode: (Boolean) -> Unit,
    onOpenLogs: () -> Unit,
) {
    SectionCard(title = "Swarm node") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .switchRow(checked = runNodeEnabled, onCheckedChange = onToggleRunNode, label = "Swarm node")
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(triple.icon, contentDescription = null, tint = triple.color)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(triple.label, fontWeight = FontWeight.Medium)
                Text(
                    when {
                        // Settings → Nodes (#125): bzz:// goes to the
                        // user's own node, whatever this one is doing.
                        external -> "bzz:// served by an external endpoint"
                        runNodeEnabled -> "Serving bzz:// via local gateway"
                        else -> "Gateway disabled"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = runNodeEnabled,
                onCheckedChange = null,
            )
        }
        LogsButton(onOpenLogs)
    }
}

@Composable
private fun DetailsSection(nodeInfo: NodeInfo) {
    SectionCard(title = "Details") {
        // What the node runs as, from the node itself: the setting can be
        // ahead of it while it restarts.
        if (nodeInfo.status == NodeStatus.Running) {
            DetailRow("Mode", swarmModeLabel(nodeInfo.lightMode))
        }
        DetailRow("Peers", nodeInfo.connectedPeers.toString())
        if (nodeInfo.clientVersion.isNotBlank()) {
            DetailRow("Client", nodeInfo.clientVersion, mono = true)
        }
        // Which account the node runs as (#77): the wallet's, derived from
        // the recovery phrase — the same as on desktop and iOS — or its own.
        if (nodeInfo.accountAddress.isNotBlank()) {
            DetailRow("Identity", if (nodeInfo.walletIdentity) "From your wallet" else "This device's own")
            DetailRow("Address", nodeInfo.accountAddress, mono = true, singleLine = false)
        }
        if (nodeInfo.overlay.isNotBlank()) {
            DetailRow("Overlay", nodeInfo.overlay, mono = true, singleLine = false)
        }
        val err = nodeInfo.errorMessage
        if (!err.isNullOrBlank()) {
            // Not always an error: a node waiting to restart after a
            // postage spend (#116) says why here, as Starting.
            DetailRow(if (nodeInfo.status == NodeStatus.Error) "Error" else "Status", err, singleLine = false)
        }
    }
}

/** The Swarm node's mode as desktop and iOS name it. */
internal fun swarmModeLabel(light: Boolean): String = if (light) "Light" else "Ultra-light"

/**
 * The Swarm node's mode (#114) and the way into publish setup. The switch
 * is the setting; the line under it says what the node runs as, which lags
 * the switch while the node restarts.
 */
@Composable
private fun PublishingSection(
    nodeInfo: NodeInfo,
    lightModeWanted: Boolean?,
    onSetLightMode: (Boolean) -> Unit,
    onOpenSetup: () -> Unit,
    onOpenStamps: () -> Unit,
    onOpenPublish: () -> Unit,
    onOpenChequebook: () -> Unit,
) {
    val spend by StampClient.spend.collectAsState()
    val publishing by Publisher.state.collectAsState()
    val light = nodeInfo.status == NodeStatus.Running && nodeInfo.lightMode
    // What the chequebook holds (#117): part of the node's status in light
    // mode, and where a deposit shows up. Re-read at once when a spend ends.
    val chequebook = rememberChequebookState(light, refresh = spend is StampClient.Spend.Running)
    SectionCard(title = "Publishing") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .switchRow(
                    checked = lightModeWanted == true,
                    onCheckedChange = onSetLightMode,
                    enabled = lightModeWanted != null,
                )
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Light mode", fontWeight = FontWeight.Medium)
                Text(
                    swarmModeSubtitle(nodeInfo, lightModeWanted),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = lightModeWanted == true,
                onCheckedChange = null,
                enabled = lightModeWanted != null,
            )
        }
        if (light) {
            DetailRow("Chequebook", chequebookSummary(chequebook), singleLine = false)
        }
        if (publishEntryShown(nodeInfo, publishing)) {
            Spacer(Modifier.height(8.dp))
            Button(onClick = onOpenPublish) { Text("Publish") }
        }
        Spacer(Modifier.height(4.dp))
        FlowRow {
            TextButton(onClick = onOpenSetup) { Text("Set up publishing") }
            if (stampsEntryShown(nodeInfo, spend)) {
                TextButton(onClick = onOpenStamps) { Text("Postage stamps") }
                TextButton(onClick = onOpenChequebook) { Text("Chequebook") }
            }
        }
    }
}

/**
 * Is the Postage stamps entry offered? The pages read a light node's
 * gateway, so while one runs; and also while a spend (#116) has something
 * to show, even with the node off — turning it off mid-spend lets the
 * spend finish first, and its progress and outcome stay reachable.
 */
internal fun stampsEntryShown(nodeInfo: NodeInfo, spend: StampClient.Spend): Boolean =
    (nodeInfo.status == NodeStatus.Running && nodeInfo.lightMode) || spend !is StampClient.Spend.Idle

/**
 * Is the Publish entry offered (#118)? Uploads go to a light node, so
 * while one runs; and while a publish is running or its outcome waits to
 * be seen, so it stays reachable if the node is turned off meanwhile.
 */
internal fun publishEntryShown(nodeInfo: NodeInfo, publishing: Publisher.State): Boolean =
    (nodeInfo.status == NodeStatus.Running && nodeInfo.lightMode) || publishing !is Publisher.State.Idle

/** The chequebook line of the node's status (#117): what it holds, or that there's none yet. */
internal fun chequebookSummary(state: ChequebookState): String = when {
    state.address == null -> "Checking…"
    state.address.isEmpty() -> "None yet (comes with the first postage stamp)"
    state.balancePlur == null -> "Checking…"
    else -> formatBzz(state.balancePlur)
}

/** The line under the light-mode switch: what the mode does, or that the node is on its way into it. */
internal fun swarmModeSubtitle(nodeInfo: NodeInfo, lightModeWanted: Boolean?): String {
    val running = nodeInfo.status == NodeStatus.Running
    return when {
        lightModeWanted == null -> ""
        running && nodeInfo.lightMode == lightModeWanted -> if (lightModeWanted) {
            "Connected to Gnosis Chain, so the node can publish"
        } else {
            "Browsing only. Light mode connects the node to Gnosis Chain so it can publish"
        }
        running || nodeInfo.status == NodeStatus.Starting ->
            "Restarting the node in ${swarmModeLabel(lightModeWanted).lowercase()} mode…"
        else -> if (lightModeWanted) {
            "Runs in light mode when the node is on"
        } else {
            "Runs in ultra-light mode (browsing only) when the node is on"
        }
    }
}

@Composable
private fun GatewaySection(externalSwarm: String) {
    SectionCard(title = "Gateway") {
        DetailRow("URL", SwarmNode.GATEWAY_URL, mono = true)
        if (externalSwarm.isNotEmpty()) {
            DetailRow("In use", externalSwarm, mono = true, singleLine = false)
        }
    }
}

internal data class NodeStatusTriple(
    val color: Color,
    val icon: ImageVector,
    val label: String,
)

internal fun nodeStatusTriple(status: NodeStatus): NodeStatusTriple = when (status) {
    NodeStatus.Running -> NodeStatusTriple(
        Color(0xFF22C55E), Icons.Filled.CheckCircle, "Running",
    )
    NodeStatus.Starting -> NodeStatusTriple(
        Color(0xFFF59E0B), Icons.Filled.HourglassTop, "Starting…",
    )
    NodeStatus.Stopped -> NodeStatusTriple(
        Color(0xFF94A3B8), Icons.Filled.PowerSettingsNew, "Stopped",
    )
    NodeStatus.Error -> NodeStatusTriple(
        Color(0xFFEF4444), Icons.Filled.ErrorOutline, "Error",
    )
}

/**
 * The Myotis light client (#72): its overall line, then per chain (#274)
 * a switch that starts or stops that chain alone, whether it starts at
 * launch, and — while it runs — its sync state.
 */
@Composable
private fun LightClientSection(
    info: MyotisInfo,
    running: Set<MyotisNetwork>?,
    onRun: (MyotisNetwork, Boolean) -> Unit,
    atLaunch: Set<MyotisNetwork>?,
    onAtLaunch: (MyotisNetwork, Boolean) -> Unit,
    onRecovery: (chainId: Long, repair: Boolean) -> Unit,
    onOpenLogs: () -> Unit,
) {
    val on = running.orEmpty()
    val shown = lightClientInfoFor(info, on)
    val triple = lightClientStatusTriple(shown)
    SectionCard(title = "Ethereum light client") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(triple.icon, contentDescription = null, tint = triple.color)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(triple.label, fontWeight = FontWeight.Medium)
                Text(
                    "Verifies Ethereum and Gnosis peer-to-peer on this device (Myotis). " +
                        "Each chain runs on its own. With Ethereum off, names are checked through Colibri " +
                        "or your RPCs instead.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val err = shown.errorMessage
        if (!err.isNullOrBlank()) DetailRow("Error", err, singleLine = false)
        for (network in MyotisNetwork.entries) {
            ChainRows(
                network = network,
                nodeStatus = shown.status,
                chain = shown.chain(network),
                on = network in on,
                onRun = running?.let { { run: Boolean -> onRun(network, run) } },
                atLaunch = atLaunch?.let { network in it },
                onAtLaunch = { onAtLaunch(network, it) },
                onRecovery = onRecovery,
            )
        }
        LogsButton(onOpenLogs)
    }
}

/**
 * What the light client's rows show (#274): nothing while every chain is
 * off, else the state from `:myotis` without a chain just switched off
 * that it hasn't dropped yet.
 */
internal fun lightClientInfoFor(info: MyotisInfo, running: Set<MyotisNetwork>): MyotisInfo =
    if (running.isEmpty()) {
        MyotisInfo()
    } else {
        info.copy(chains = info.chains.filter { chain -> running.any { it.chainId == chain.chainId } })
    }

@Composable
private fun ChainRows(
    network: MyotisNetwork,
    nodeStatus: MyotisStatus,
    chain: MyotisChainStatus?,
    on: Boolean,
    /** Null (switch disabled) until the chains switched on are known. */
    onRun: ((Boolean) -> Unit)?,
    /** Null until read. */
    atLaunch: Boolean?,
    onAtLaunch: (Boolean) -> Unit,
    onRecovery: (chainId: Long, repair: Boolean) -> Unit,
) {
    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .switchRow(checked = on, onCheckedChange = onRun, label = "Run ${network.displayName}")
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(network.displayName, fontWeight = FontWeight.Medium)
            Text(
                myotisChainLabel(nodeStatus, chain, on),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = on,
            onCheckedChange = null,
            enabled = onRun != null,
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = atLaunch == true,
                enabled = atLaunch != null,
                role = Role.Checkbox,
                onValueChange = onAtLaunch,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = atLaunch == true, onCheckedChange = null, enabled = atLaunch != null)
        Spacer(Modifier.width(8.dp))
        Text("Start ${network.displayName} at launch", style = MaterialTheme.typography.bodyMedium)
    }
    if (!on || chain == null || nodeStatus != MyotisStatus.Running) return
    chain.error?.let {
        DetailRow("Error", it, singleLine = false)
        return
    }
    val recovery = chain.recovery
    if (recovery != null) {
        RecoveryRows(chain, recovery, onRecovery)
        return
    }
    if (chain.staleAnchor) {
        DetailRow("Not syncing", staleAnchorExplanation(chain), singleLine = false)
        return
    }
    DetailRow("Beacon peers", chain.peerCount.toString())
    DetailRow("State peers", statePeersLabel(chain))
    if (chain.headBlock > 0) DetailRow("Head block", formatBlock(chain.headBlock), mono = true)
    if (chain.finalizedBlock > 0) {
        DetailRow("Finalized block", formatBlock(chain.finalizedBlock), mono = true)
    }
    val reason = chain.notServingReason
    if (reason.isNotEmpty()) DetailRow("Not serving", reason, singleLine = false)
}

/**
 * A chain in stale-anchor checkpoint recovery (#195): what it's doing or
 * why it stopped, how old the refused checkpoint was, and — blocked or
 * waiting for an automatic retry — Retry / Repair sync data.
 */
@Composable
private fun RecoveryRows(
    chain: MyotisChainStatus,
    recovery: MyotisRecovery,
    onRecovery: (chainId: Long, repair: Boolean) -> Unit,
) {
    // The retry countdown runs on elapsedRealtime, the clock the :myotis
    // process stamped it with; tick it while it's counting.
    val now by produceState(SystemClock.elapsedRealtime(), recovery) {
        while (recovery.phase == MyotisRecovery.Phase.Waiting) {
            value = SystemClock.elapsedRealtime()
            delay(1_000)
        }
    }
    DetailRow(
        if (recovery.phase == MyotisRecovery.Phase.Blocked) "Not syncing" else "Recovery",
        recovery.message(now),
        singleLine = false,
    )
    anchorAge(chain)?.let { DetailRow("Trust checkpoint", it, singleLine = false) }
    if (recovery.canRetry) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onRecovery(chain.chainId, false) }) { Text("Retry") }
            if (recovery.canRepair) {
                OutlinedButton(onClick = { onRecovery(chain.chainId, true) }) { Text("Repair sync data") }
            }
        }
    }
}

/**
 * One-line state for a chain row: what the light client is doing on that
 * chain — "Off" while its switch is ([on] false, #274).
 */
internal fun myotisChainLabel(nodeStatus: MyotisStatus, chain: MyotisChainStatus?, on: Boolean): String =
    if (!on) "Off" else when (nodeStatus) {
        MyotisStatus.Stopped -> "Off"
        MyotisStatus.Starting -> "Starting…"
        MyotisStatus.Error -> if (chain?.error != null) "Failed" else "Off"
        MyotisStatus.Running -> when {
            // Just switched on beside a running chain: `:myotis` is booting it.
            chain == null -> "Starting…"
            chain.error != null -> "Failed"
            chain.recovery != null -> chain.recovery?.label.orEmpty()
            chain.paused -> "Paused"
            chain.staleAnchor -> "Checkpoint too old"
            chain.ready -> "Synced"
            chain.synced -> "Synced, not serving yet"
            chain.beaconState == "CATCHING_UP" -> "Catching up"
            chain.beaconState == "SYNCING" -> "Syncing"
            else -> "Starting…"
        }
    }

/**
 * Why a chain parked on a stale trust anchor isn't syncing: the engine
 * refuses to sync forward from a checkpoint older than the weak-subjectivity
 * bound. Shown only until its checkpoint recovery (#195) is on the row,
 * which is almost at once — the node starts one the moment it parks.
 */
internal fun staleAnchorExplanation(chain: MyotisChainStatus): String {
    val detail = anchorAge(chain)?.let { " ($it)" } ?: ""
    // The refused anchor is the embedded one or a previously verified
    // generation's checkpoint; the status doesn't say which.
    return "This chain's checkpoint is too old to sync from safely$detail."
}

/** How old a refused anchor is against the engine's limit, or null when it isn't a stale anchor. */
internal fun anchorAge(chain: MyotisChainStatus): String? {
    if (!chain.staleAnchor || chain.wsBoundPeriods <= 0) return null
    val age = (chain.targetPeriod - chain.currentPeriod).coerceAtLeast(0)
    return "${plural(age, "sync period")} old, the safe limit is ${chain.wsBoundPeriods}"
}

private fun plural(n: Long, noun: String) = if (n == 1L) "1 $noun" else "$n ${noun}s"

/** Pooled execution-layer peers, and how many of them can serve at the head. */
internal fun statePeersLabel(chain: MyotisChainStatus): String =
    if (chain.snapPeers > 0) "${chain.snapPeers} · ${chain.snapServingPeers} at head" else "0"

internal fun formatBlock(number: Long): String = String.format(Locale.US, "%,d", number)

/**
 * The light client's overall line: green once every chain that came up
 * serves verified reads, amber (with how many do) until then.
 */
internal fun lightClientStatusTriple(info: MyotisInfo): NodeStatusTriple = when (info.status) {
    MyotisStatus.Stopped -> NodeStatusTriple(
        Color(0xFF94A3B8), Icons.Filled.PowerSettingsNew, "Off",
    )
    MyotisStatus.Starting -> NodeStatusTriple(
        Color(0xFFF59E0B), Icons.Filled.HourglassTop, "Starting…",
    )
    MyotisStatus.Error -> NodeStatusTriple(
        Color(0xFFEF4444), Icons.Filled.ErrorOutline, "Error",
    )
    MyotisStatus.Running -> {
        val live = info.chains.filter { it.error == null }
        val ready = live.count { it.ready }
        // A chain on a stale checkpoint is recovering (or stuck), not
        // syncing along: don't let it keep the whole line on "Syncing…" —
        // neither when it's the only chain left nor beside one still waiting.
        val parked = live.count { it.staleAnchor || it.recovery != null }
        when {
            live.isNotEmpty() && ready == live.size ->
                NodeStatusTriple(Color(0xFF22C55E), Icons.Filled.CheckCircle, "Synced")
            live.isNotEmpty() && parked == live.size -> NodeStatusTriple(
                Color(0xFFF59E0B),
                Icons.Filled.ErrorOutline,
                when {
                    live.any { it.recovery?.phase == MyotisRecovery.Phase.Blocked } -> "Sync paused"
                    live.any { it.recovery != null } -> "Updating checkpoint"
                    else -> "Checkpoint too old"
                },
            )
            ready > 0 || parked > 0 -> NodeStatusTriple(
                Color(0xFFF59E0B), Icons.Filled.HourglassTop, "$ready of ${live.size} chains synced",
            )
            // A chain switched on that `:myotis` hasn't booted yet (its
            // previous engine still stopping): no row to sync.
            live.isEmpty() -> NodeStatusTriple(Color(0xFFF59E0B), Icons.Filled.HourglassTop, "Starting…")
            else -> NodeStatusTriple(Color(0xFFF59E0B), Icons.Filled.HourglassTop, "Syncing…")
        }
    }
}

/**
 * What the UI knows about the Tor client (#143) and what it can ask of
 * it: its state as broadcast from `:tor` (or, for an external proxy, as
 * [TorProxy.probe] found it), Settings → Tor, whether the user has it
 * running (the node page's switch), and whether this WebView can route
 * only `.onion` through it. [proxy] is Settings → Tor's external SOCKS
 * proxy (#275), `null` for the embedded client; with Orbot installed
 * and not answering, the card offers to start it.
 */
data class TorControls(
    val info: TorInfo = TorInfo(),
    val enabled: Boolean = false,
    val running: Boolean = false,
    val supported: Boolean = true,
    val onRun: (Boolean) -> Unit = {},
    val proxy: SocksEndpoint? = null,
    val orbotInstalled: Boolean = false,
    val onStartOrbot: () -> Unit = {},
    val onOpenOrbot: () -> Unit = {},
)

@Composable
private fun TorSection(tor: TorControls, onOpenLogs: () -> Unit) {
    val info = if (tor.enabled && (tor.running || tor.info.status == TorStatus.Error)) {
        tor.info
    } else {
        TorInfo(version = tor.info.version)
    }
    val triple = torStatusTriple(info)
    SectionCard(title = "Tor") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .switchRow(
                    checked = tor.enabled && tor.running,
                    onCheckedChange = tor.onRun,
                    enabled = tor.enabled && tor.supported,
                    label = "Tor",
                )
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(triple.icon, contentDescription = null, tint = triple.color)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(triple.label, fontWeight = FontWeight.Medium)
                Text(
                    torSubtitle(tor),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = tor.enabled && tor.running,
                onCheckedChange = null,
                enabled = tor.enabled && tor.supported,
            )
        }
        val proxy = tor.proxy
        if (proxy == null && info.version.isNotBlank()) DetailRow("Version", "Arti ${info.version}", mono = true)
        if (info.status == TorStatus.Starting && info.summary.isNotBlank()) {
            DetailRow(if (proxy == null) "Bootstrap" else "Check", info.summary, singleLine = false)
        }
        if (proxy != null) {
            DetailRow("External proxy", proxy.authority, mono = true)
        } else if (info.socksPort > 0) {
            DetailRow("SOCKS proxy", "127.0.0.1:${info.socksPort}", mono = true)
        }
        val err = info.errorMessage
        if (!err.isNullOrBlank()) DetailRow("Error", err, singleLine = false)
        if (showStartOrbot(tor, info)) {
            Text(
                ORBOT_START_NOTE,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                OutlinedButton(onClick = tor.onStartOrbot) { Text("Start Orbot") }
                TextButton(onClick = tor.onOpenOrbot) { Text("Open Orbot") }
            }
        }
        // An external client's logs are in that app, not here.
        if (proxy == null) LogsButton(onOpenLogs)
    }
}

internal const val ORBOT_START_NOTE =
    "Orbot starts in the background only with its Allow background starts option on; " +
        "otherwise open it and tap Connect."

/**
 * Whether the Tor card offers Start Orbot (#275): an external proxy in
 * use, Tor switched on, Orbot installed, and no Tor client answering.
 */
internal fun showStartOrbot(tor: TorControls, info: TorInfo): Boolean =
    tor.proxy != null && tor.enabled && tor.running && tor.orbotInstalled && info.status == TorStatus.Error

/** The line under the Tor status: what it does, or why it can't be switched on. */
internal fun torSubtitle(tor: TorControls): String = when {
    !tor.supported -> "This WebView can't route only .onion sites through Tor; update Android System WebView"
    !tor.enabled -> "Turn on Tor in Settings to open .onion sites"
    tor.proxy != null ->
        "Opens .onion sites through the external Tor proxy (e.g. Orbot). Every other site connects directly."
    else -> "Opens .onion sites over Tor (Arti). Every other site connects directly."
}

/** The Tor client's status line: grey off, amber bootstrapping (with its progress), green connected. */
internal fun torStatusTriple(info: TorInfo): NodeStatusTriple = when (info.status) {
    TorStatus.Stopped -> NodeStatusTriple(Color(0xFF94A3B8), Icons.Filled.PowerSettingsNew, "Off")
    TorStatus.Starting -> NodeStatusTriple(
        Color(0xFFF59E0B), Icons.Filled.HourglassTop,
        if (info.progress > 0) "Connecting… ${info.progress}%" else "Starting…",
    )
    TorStatus.Running -> NodeStatusTriple(Color(0xFF22C55E), Icons.Filled.CheckCircle, "Connected")
    TorStatus.Error -> NodeStatusTriple(Color(0xFFEF4444), Icons.Filled.ErrorOutline, "Error")
}
