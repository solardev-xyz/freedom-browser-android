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
import androidx.compose.ui.res.stringResource
import baby.freedom.mobile.R
import baby.freedom.mobile.l10n.Strings
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
import java.text.NumberFormat
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
        title = stringResource(R.string.node_screen_title),
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
    SectionCard(title = stringResource(R.string.node_swarm_node)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .switchRow(
                    checked = runNodeEnabled,
                    onCheckedChange = onToggleRunNode,
                    label = stringResource(R.string.node_swarm_node),
                )
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
                        external -> stringResource(R.string.node_gateway_external)
                        runNodeEnabled -> stringResource(R.string.node_gateway_local)
                        else -> stringResource(R.string.node_gateway_disabled)
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
    SectionCard(title = stringResource(R.string.node_details)) {
        // What the node runs as, from the node itself: the setting can be
        // ahead of it while it restarts.
        if (nodeInfo.status == NodeStatus.Running) {
            DetailRow(stringResource(R.string.node_mode), swarmModeLabel(nodeInfo.lightMode))
        }
        DetailRow(stringResource(R.string.node_peers), formatCount(nodeInfo.connectedPeers))
        if (nodeInfo.clientVersion.isNotBlank()) {
            DetailRow(stringResource(R.string.node_client), nodeInfo.clientVersion, mono = true)
        }
        // Which account the node runs as (#77): the wallet's, derived from
        // the recovery phrase — the same as on desktop and iOS — or its own.
        if (nodeInfo.accountAddress.isNotBlank()) {
            DetailRow(
                stringResource(R.string.node_identity),
                stringResource(
                    if (nodeInfo.walletIdentity) R.string.node_identity_wallet else R.string.node_identity_own,
                ),
            )
            DetailRow(stringResource(R.string.node_address), nodeInfo.accountAddress, mono = true, singleLine = false)
        }
        if (nodeInfo.overlay.isNotBlank()) {
            DetailRow(stringResource(R.string.node_overlay), nodeInfo.overlay, mono = true, singleLine = false)
        }
        val err = nodeInfo.errorMessage
        if (!err.isNullOrBlank()) {
            // Not always an error: a node waiting to restart after a
            // postage spend (#116) says why here, as Starting.
            DetailRow(
                stringResource(if (nodeInfo.status == NodeStatus.Error) R.string.node_error else R.string.node_status),
                err,
                singleLine = false,
            )
        }
    }
}

/** The Swarm node's mode as desktop and iOS name it. */
internal fun swarmModeLabel(light: Boolean): String =
    Strings.get(if (light) R.string.node_mode_light else R.string.node_mode_ultra_light)

/** A count shown on its own (peers), in the user's locale. */
private fun formatCount(n: Number): String = NumberFormat.getIntegerInstance().format(n)

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
    SectionCard(title = stringResource(R.string.node_publishing)) {
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
                Text(stringResource(R.string.node_light_mode), fontWeight = FontWeight.Medium)
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
            DetailRow(stringResource(R.string.node_chequebook), chequebookSummary(chequebook), singleLine = false)
        }
        if (publishEntryShown(nodeInfo, publishing)) {
            Spacer(Modifier.height(8.dp))
            Button(onClick = onOpenPublish) { Text(stringResource(R.string.node_publish)) }
        }
        Spacer(Modifier.height(4.dp))
        FlowRow {
            TextButton(onClick = onOpenSetup) { Text(stringResource(R.string.node_set_up_publishing)) }
            if (stampsEntryShown(nodeInfo, spend)) {
                TextButton(onClick = onOpenStamps) { Text(stringResource(R.string.node_postage_stamps)) }
                TextButton(onClick = onOpenChequebook) { Text(stringResource(R.string.node_chequebook)) }
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
    state.address == null -> Strings.get(R.string.node_checking)
    state.address.isEmpty() -> Strings.get(R.string.node_chequebook_none)
    state.balancePlur == null -> Strings.get(R.string.node_checking)
    else -> formatBzz(state.balancePlur)
}

/** The line under the light-mode switch: what the mode does, or that the node is on its way into it. */
internal fun swarmModeSubtitle(nodeInfo: NodeInfo, lightModeWanted: Boolean?): String {
    val running = nodeInfo.status == NodeStatus.Running
    return when {
        lightModeWanted == null -> ""
        running && nodeInfo.lightMode == lightModeWanted -> if (lightModeWanted) {
            Strings.get(R.string.node_mode_light_running)
        } else {
            Strings.get(R.string.node_mode_ultra_light_running)
        }
        running || nodeInfo.status == NodeStatus.Starting -> if (lightModeWanted) {
            Strings.get(R.string.node_mode_light_restarting)
        } else {
            Strings.get(R.string.node_mode_ultra_light_restarting)
        }
        else -> if (lightModeWanted) {
            Strings.get(R.string.node_mode_light_when_on)
        } else {
            Strings.get(R.string.node_mode_ultra_light_when_on)
        }
    }
}

@Composable
private fun GatewaySection(externalSwarm: String) {
    SectionCard(title = stringResource(R.string.node_gateway)) {
        DetailRow(stringResource(R.string.node_url), SwarmNode.GATEWAY_URL, mono = true)
        if (externalSwarm.isNotEmpty()) {
            DetailRow(stringResource(R.string.node_in_use), externalSwarm, mono = true, singleLine = false)
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
        Color(0xFF22C55E), Icons.Filled.CheckCircle, Strings.get(R.string.node_status_running),
    )
    NodeStatus.Starting -> NodeStatusTriple(
        Color(0xFFF59E0B), Icons.Filled.HourglassTop, Strings.get(R.string.node_status_starting),
    )
    NodeStatus.Stopped -> NodeStatusTriple(
        Color(0xFF94A3B8), Icons.Filled.PowerSettingsNew, Strings.get(R.string.node_status_stopped),
    )
    NodeStatus.Error -> NodeStatusTriple(
        Color(0xFFEF4444), Icons.Filled.ErrorOutline, Strings.get(R.string.node_error),
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
    SectionCard(title = stringResource(R.string.node_light_client)) {
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
                    stringResource(R.string.node_light_client_about),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val err = shown.errorMessage
        if (!err.isNullOrBlank()) DetailRow(stringResource(R.string.node_error), err, singleLine = false)
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
            .switchRow(
                checked = on,
                onCheckedChange = onRun,
                label = stringResource(R.string.node_run_chain, network.displayName),
            )
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
        Text(
            stringResource(R.string.node_start_chain_at_launch, network.displayName),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    if (!on || chain == null || nodeStatus != MyotisStatus.Running) return
    chain.error?.let {
        DetailRow(stringResource(R.string.node_error), it, singleLine = false)
        return
    }
    val recovery = chain.recovery
    if (recovery != null) {
        RecoveryRows(chain, recovery, onRecovery)
        return
    }
    if (chain.staleAnchor) {
        DetailRow(stringResource(R.string.node_not_syncing), staleAnchorExplanation(chain), singleLine = false)
        return
    }
    DetailRow(stringResource(R.string.node_beacon_peers), formatCount(chain.peerCount))
    DetailRow(stringResource(R.string.node_state_peers), statePeersLabel(chain))
    if (chain.headBlock > 0) {
        DetailRow(stringResource(R.string.node_head_block), formatBlock(chain.headBlock), mono = true)
    }
    if (chain.finalizedBlock > 0) {
        DetailRow(stringResource(R.string.node_finalized_block), formatBlock(chain.finalizedBlock), mono = true)
    }
    val reason = chain.notServingReason
    if (reason.isNotEmpty()) DetailRow(stringResource(R.string.node_not_serving), reason, singleLine = false)
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
        stringResource(
            if (recovery.phase == MyotisRecovery.Phase.Blocked) R.string.node_not_syncing else R.string.node_recovery,
        ),
        recovery.message(now),
        singleLine = false,
    )
    anchorAge(chain)?.let { DetailRow(stringResource(R.string.node_trust_checkpoint), it, singleLine = false) }
    if (recovery.canRetry) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onRecovery(chain.chainId, false) }) {
                Text(stringResource(R.string.common_retry))
            }
            if (recovery.canRepair) {
                OutlinedButton(onClick = { onRecovery(chain.chainId, true) }) {
                    Text(stringResource(R.string.node_repair_sync_data))
                }
            }
        }
    }
}

/**
 * One-line state for a chain row: what the light client is doing on that
 * chain — "Off" while its switch is ([on] false, #274).
 */
internal fun myotisChainLabel(nodeStatus: MyotisStatus, chain: MyotisChainStatus?, on: Boolean): String =
    if (!on) Strings.get(R.string.node_off) else when (nodeStatus) {
        MyotisStatus.Stopped -> Strings.get(R.string.node_off)
        MyotisStatus.Starting -> Strings.get(R.string.node_status_starting)
        MyotisStatus.Error -> Strings.get(if (chain?.error != null) R.string.node_chain_failed else R.string.node_off)
        MyotisStatus.Running -> when {
            // Just switched on beside a running chain: `:myotis` is booting it.
            chain == null -> Strings.get(R.string.node_status_starting)
            chain.error != null -> Strings.get(R.string.node_chain_failed)
            chain.recovery != null -> chain.recovery?.label.orEmpty()
            chain.paused -> Strings.get(R.string.node_chain_paused)
            chain.staleAnchor -> Strings.get(R.string.node_checkpoint_too_old)
            chain.ready -> Strings.get(R.string.node_synced)
            chain.synced -> Strings.get(R.string.node_chain_synced_not_serving)
            chain.beaconState == "CATCHING_UP" -> Strings.get(R.string.node_chain_catching_up)
            chain.beaconState == "SYNCING" -> Strings.get(R.string.node_chain_syncing)
            else -> Strings.get(R.string.node_status_starting)
        }
    }

/**
 * Why a chain parked on a stale trust anchor isn't syncing: the engine
 * refuses to sync forward from a checkpoint older than the weak-subjectivity
 * bound. Shown only until its checkpoint recovery (#195) is on the row,
 * which is almost at once — the node starts one the moment it parks.
 */
internal fun staleAnchorExplanation(chain: MyotisChainStatus): String {
    val age = anchorAge(chain)
    // The refused anchor is the embedded one or a previously verified
    // generation's checkpoint; the status doesn't say which.
    return if (age != null) {
        Strings.get(R.string.node_stale_anchor_with_age, age)
    } else {
        Strings.get(R.string.node_stale_anchor)
    }
}

/** How old a refused anchor is against the engine's limit, or null when it isn't a stale anchor. */
internal fun anchorAge(chain: MyotisChainStatus): String? {
    if (!chain.staleAnchor || chain.wsBoundPeriods <= 0) return null
    val age = (chain.targetPeriod - chain.currentPeriod).coerceAtLeast(0)
    return Strings.plural(
        R.plurals.node_anchor_age,
        age.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        age,
        chain.wsBoundPeriods,
    )
}

/** Pooled execution-layer peers, and how many of them can serve at the head. */
internal fun statePeersLabel(chain: MyotisChainStatus): String =
    if (chain.snapPeers > 0) {
        Strings.get(R.string.node_state_peers_at_head, chain.snapPeers, chain.snapServingPeers)
    } else {
        formatCount(0)
    }

internal fun formatBlock(number: Long): String = String.format(Locale.US, "%,d", number)

/**
 * The light client's overall line: green once every chain that came up
 * serves verified reads, amber (with how many do) until then.
 */
internal fun lightClientStatusTriple(info: MyotisInfo): NodeStatusTriple = when (info.status) {
    MyotisStatus.Stopped -> NodeStatusTriple(
        Color(0xFF94A3B8), Icons.Filled.PowerSettingsNew, Strings.get(R.string.node_off),
    )
    MyotisStatus.Starting -> NodeStatusTriple(
        Color(0xFFF59E0B), Icons.Filled.HourglassTop, Strings.get(R.string.node_status_starting),
    )
    MyotisStatus.Error -> NodeStatusTriple(
        Color(0xFFEF4444), Icons.Filled.ErrorOutline, Strings.get(R.string.node_error),
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
                NodeStatusTriple(Color(0xFF22C55E), Icons.Filled.CheckCircle, Strings.get(R.string.node_synced))
            live.isNotEmpty() && parked == live.size -> NodeStatusTriple(
                Color(0xFFF59E0B),
                Icons.Filled.ErrorOutline,
                when {
                    live.any { it.recovery?.phase == MyotisRecovery.Phase.Blocked } ->
                        Strings.get(R.string.node_sync_paused)
                    live.any { it.recovery != null } -> Strings.get(R.string.node_updating_checkpoint)
                    else -> Strings.get(R.string.node_checkpoint_too_old)
                },
            )
            ready > 0 || parked > 0 -> NodeStatusTriple(
                Color(0xFFF59E0B),
                Icons.Filled.HourglassTop,
                Strings.plural(R.plurals.node_chains_synced, live.size, ready, live.size),
            )
            // A chain switched on that `:myotis` hasn't booted yet (its
            // previous engine still stopping): no row to sync.
            live.isEmpty() -> NodeStatusTriple(
                Color(0xFFF59E0B), Icons.Filled.HourglassTop, Strings.get(R.string.node_status_starting),
            )
            else -> NodeStatusTriple(
                Color(0xFFF59E0B), Icons.Filled.HourglassTop, Strings.get(R.string.node_syncing_ellipsis),
            )
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
    SectionCard(title = stringResource(R.string.node_tor)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .switchRow(
                    checked = tor.enabled && tor.running,
                    onCheckedChange = tor.onRun,
                    enabled = tor.enabled && tor.supported,
                    label = stringResource(R.string.node_tor),
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
        if (proxy == null && info.version.isNotBlank()) {
            DetailRow(stringResource(R.string.node_version), "Arti ${info.version}", mono = true)
        }
        if (info.status == TorStatus.Starting && info.summary.isNotBlank()) {
            DetailRow(
                stringResource(if (proxy == null) R.string.node_tor_bootstrap else R.string.node_tor_check),
                info.summary,
                singleLine = false,
            )
        }
        if (proxy != null) {
            DetailRow(stringResource(R.string.node_tor_external_proxy), proxy.authority, mono = true)
        } else if (info.socksPort > 0) {
            DetailRow(stringResource(R.string.node_tor_socks_proxy), "127.0.0.1:${info.socksPort}", mono = true)
        }
        val err = info.errorMessage
        if (!err.isNullOrBlank()) DetailRow(stringResource(R.string.node_error), err, singleLine = false)
        if (showStartOrbot(tor, info)) {
            Text(
                stringResource(R.string.node_orbot_start_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                OutlinedButton(onClick = tor.onStartOrbot) { Text(stringResource(R.string.node_start_orbot)) }
                TextButton(onClick = tor.onOpenOrbot) { Text(stringResource(R.string.node_open_orbot)) }
            }
        }
        // An external client's logs are in that app, not here.
        if (proxy == null) LogsButton(onOpenLogs)
    }
}

internal val ORBOT_START_NOTE: String
    get() = Strings.get(R.string.node_orbot_start_note)

/**
 * Whether the Tor card offers Start Orbot (#275): an external proxy in
 * use, Tor switched on, Orbot installed, and no Tor client answering.
 */
internal fun showStartOrbot(tor: TorControls, info: TorInfo): Boolean =
    tor.proxy != null && tor.enabled && tor.running && tor.orbotInstalled && info.status == TorStatus.Error

/** The line under the Tor status: what it does, or why it can't be switched on. */
internal fun torSubtitle(tor: TorControls): String = when {
    !tor.supported -> Strings.get(R.string.node_tor_unsupported)
    !tor.enabled -> Strings.get(R.string.node_tor_disabled)
    tor.proxy != null -> Strings.get(R.string.node_tor_about_external)
    else -> Strings.get(R.string.node_tor_about)
}

/** The Tor client's status line: grey off, amber bootstrapping (with its progress), green connected. */
internal fun torStatusTriple(info: TorInfo): NodeStatusTriple = when (info.status) {
    TorStatus.Stopped -> NodeStatusTriple(
        Color(0xFF94A3B8), Icons.Filled.PowerSettingsNew, Strings.get(R.string.node_off),
    )
    TorStatus.Starting -> NodeStatusTriple(
        Color(0xFFF59E0B), Icons.Filled.HourglassTop,
        if (info.progress > 0) {
            Strings.get(R.string.node_tor_connecting_progress, info.progress)
        } else {
            Strings.get(R.string.node_status_starting)
        },
    )
    TorStatus.Running -> NodeStatusTriple(
        Color(0xFF22C55E), Icons.Filled.CheckCircle, Strings.get(R.string.node_tor_connected),
    )
    TorStatus.Error -> NodeStatusTriple(Color(0xFFEF4444), Icons.Filled.ErrorOutline, Strings.get(R.string.node_error))
}
