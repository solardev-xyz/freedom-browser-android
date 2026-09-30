package baby.freedom.mobile.browser

import baby.freedom.mobile.data.RadicleGrantStore
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import baby.freedom.swarm.RadicleInfo
import baby.freedom.swarm.RadicleNode
import baby.freedom.swarm.RadicleSeed
import baby.freedom.swarm.RadicleStatus

/**
 * What the UI knows about the embedded Radicle node (#73) and what it can
 * ask of it: the node's state as broadcast from `:node`, the persisted
 * on/off setting, and the actions the Radicle page offers (seed, unseed).
 */
data class RadicleControls(
    val info: RadicleInfo = RadicleInfo(),
    val enabled: Boolean = false,
    val onToggle: (Boolean) -> Unit = {},
    val onSeed: (String) -> Unit = {},
    val onUnseed: (String) -> Unit = {},
    /** Open a seeded repository (`rad:z…`) in the `rad://` browser (#124); set by [BrowserScreen]. */
    val onOpen: ((String) -> Unit)? = null,
    /** Sites connected to `window.radicle` (#124), and dropping one's grant. */
    val grants: List<RadicleGrantStore.Grant> = emptyList(),
    val onRevoke: (String) -> Unit = {},
)

internal const val RADICLE_ROW_KEY = "radicle"
internal const val RADICLE_ROW_TITLE = "Radicle node"

/** The Nodes section's Radicle row, for settings search. */
internal fun radicleSettingsRow(radicle: RadicleControls) = settingsRow(
    RADICLE_ROW_KEY,
    RADICLE_ROW_TITLE,
    radicleSummary(radicle.info, radicle.enabled),
    "Seed repositories",
)

/** One line on where the Radicle node stands, for its Settings row. */
internal fun radicleSummary(info: RadicleInfo, enabled: Boolean): String = when {
    !enabled -> "Off"
    info.status == RadicleStatus.Running -> "Running · ${peersLabel(info.connectedPeers)}"
    info.status == RadicleStatus.Starting -> "Starting…"
    info.status == RadicleStatus.Stopping -> "Stopping…"
    info.status == RadicleStatus.Error -> "Error"
    // On, but `:node` isn't up (the node service is off) or hasn't
    // started it yet.
    else -> "Waiting for the node service"
}

private fun peersLabel(n: Int) = if (n == 1) "1 peer" else "$n peers"

/** The seed line under the RID field: the phase and its detail. */
internal fun seedLine(seed: RadicleSeed): String {
    val phase = when (seed.phase) {
        "resolving" -> "Looking for seeds"
        "connecting" -> "Connecting"
        "fetching" -> "Fetching"
        "peer-failed" -> "A seed failed, trying the next"
        RadicleNode.PHASE_DONE -> "Seeded"
        RadicleNode.PHASE_CANCELLED -> "Cancelled"
        RadicleNode.PHASE_FAILED -> "Failed"
        else -> seed.phase
    }
    return if (seed.detail.isEmpty()) phase else "$phase: ${seed.detail}"
}

/**
 * The embedded Radicle node's page (#73), the Android counterpart of iOS's
 * `RadicleNodeSheet`: an on/off switch, the node's identity (DID, node ID,
 * alias) and peer count, the repositories it seeds, and a seed-by-RID
 * field so replication can be exercised without a dApp page. A seeded
 * repository opens in the `rad://` browser (#124), and the sites
 * connected to `window.radicle` are listed with a way to disconnect them.
 *
 * Identities and RIDs are shown whole and wrapped, never cut, so they can
 * be read (and selected) on the narrowest screen.
 */
@Composable
fun RadicleScreen(
    radicle: RadicleControls,
    runNodeEnabled: Boolean,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val info = radicle.info
    FullScreenScaffold(
        title = RADICLE_ROW_TITLE,
        onDismiss = onDismiss,
    ) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item("status") {
                RadicleStatusSection(
                    info = info,
                    enabled = radicle.enabled,
                    runNodeEnabled = runNodeEnabled,
                    onToggle = radicle.onToggle,
                )
            }
            if (radicle.enabled && info.status == RadicleStatus.Running) {
                item("identity") { RadicleIdentitySection(info) }
                item("repos") {
                    RadicleReposSection(info, onSeed = radicle.onSeed, onUnseed = radicle.onUnseed, onOpen = radicle.onOpen)
                }
            }
            if (radicle.enabled && radicle.grants.isNotEmpty()) {
                item("sites") { RadicleSitesSection(radicle.grants, radicle.onRevoke) }
            }
        }
    }
}

@Composable
private fun RadicleStatusSection(
    info: RadicleInfo,
    enabled: Boolean,
    runNodeEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val (color, icon, label) = when {
        !enabled -> Triple(Color(0xFF94A3B8), Icons.Filled.PowerSettingsNew, "Off")
        info.status == RadicleStatus.Running -> Triple(Color(0xFF22C55E), Icons.Filled.CheckCircle, "Running")
        info.status == RadicleStatus.Error -> Triple(Color(0xFFEF4444), Icons.Filled.ErrorOutline, "Error")
        info.status == RadicleStatus.Stopping -> Triple(Color(0xFFF59E0B), Icons.Filled.HourglassTop, "Stopping…")
        info.status == RadicleStatus.Starting -> Triple(Color(0xFFF59E0B), Icons.Filled.HourglassTop, "Starting…")
        else -> Triple(Color(0xFF94A3B8), Icons.Filled.HourglassTop, "Waiting")
    }
    SectionCard(title = "Status") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .switchRow(checked = enabled, onCheckedChange = onToggle, label = "Radicle node")
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = color)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(label, fontWeight = FontWeight.Medium)
                Text(
                    when {
                        !enabled -> "Collaborate on Radicle repositories peer-to-peer"
                        !runNodeEnabled -> "Starts once the Swarm node is on"
                        info.status == RadicleStatus.Running ->
                            "Connected to ${peersLabel(info.connectedPeers)}"
                        info.status == RadicleStatus.Starting -> "Loading the identity and dialling seeds"
                        info.status == RadicleStatus.Stopping -> "Shutting the node down"
                        info.status == RadicleStatus.Error -> "The node couldn’t start. Retry, or turn it off and on."
                        else -> "Waiting for the node service"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = null)
        }
        val err = info.errorMessage
        if (enabled && !err.isNullOrBlank()) {
            DetailRow("Error", err, singleLine = false)
        }
        // A failed start isn't retried behind the user's back; this asks
        // `:node` to boot again (as the next bind would).
        if (enabled && runNodeEnabled && info.status == RadicleStatus.Error) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { onToggle(true) }) { Text("Retry") }
            }
        }
    }
}

@Composable
private fun RadicleIdentitySection(info: RadicleInfo) {
    SectionCard(title = "Identity") {
        DetailRow("Alias", info.alias)
        DetailRow("Peers", info.connectedPeers.toString())
        StackedValue("DID", info.did)
        StackedValue("Node ID", info.nid)
    }
}

/** A label over a long, selectable monospace value, wrapped in full. */
@Composable
private fun StackedValue(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 3.dp)) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
        SelectionContainer {
            Text(value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun RadicleReposSection(
    info: RadicleInfo,
    onSeed: (String) -> Unit,
    onUnseed: (String) -> Unit,
    onOpen: ((String) -> Unit)?,
) {
    var input by rememberSaveable { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val seed = info.seed
    val busy = seed?.active == true
    val submit = {
        if (input.isNotBlank() && !busy) {
            focusManager.clearFocus()
            onSeed(input)
        }
    }
    SectionCard(title = "Seeded repositories") {
        if (info.seededRepos.isEmpty()) {
            Text(
                "Nothing seeded yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            info.seededRepos.forEach { repo ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f).padding(vertical = 4.dp)) {
                        if (repo.name.isNotEmpty()) {
                            Text(repo.name, fontWeight = FontWeight.Medium)
                        } else {
                            Text(
                                "Awaiting first fetch",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        SelectionContainer {
                            Text(
                                repo.rid,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    // Only once it has fetched: there's nothing to browse before.
                    if (onOpen != null && repo.name.isNotEmpty()) {
                        TextButton(onClick = { onOpen(repo.rid) }) { Text("Open") }
                    }
                    // Also the way out for a RID that never fetched.
                    TextButton(onClick = { onUnseed(repo.rid) }) { Text("Stop seeding") }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        NoSuggestionsTextInput {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("Repository ID") },
                placeholder = { Text("rad:z…") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = urlKeyboardOptions(ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { submit() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(
                onClick = submit,
                enabled = input.isNotBlank() && !busy,
            ) { Text(if (busy) "Seeding…" else "Seed") }
        }
        if (seed != null) {
            Text(
                seedLine(seed),
                style = MaterialTheme.typography.bodySmall,
                color = if (seed.phase == RadicleNode.PHASE_FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(top = 4.dp),
            )
            SelectionContainer {
                Text(
                    seed.rid,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Sites connected to `window.radicle` (#124), and what they may do. A
 * site can drop its own grant (`radicle_disconnect`); this is the user's
 * way to drop it for them.
 */
@Composable
private fun RadicleSitesSection(grants: List<RadicleGrantStore.Grant>, onRevoke: (String) -> Unit) {
    SectionCard(title = "Connected sites") {
        grants.forEach { grant ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f).padding(vertical = 4.dp)) {
                    Text(permissionOriginDisplay(grant.origin), fontWeight = FontWeight.Medium)
                    Text(
                        if (grant.signing) "Can see your identity and write as you" else "Can see your node and ask to seed",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { onRevoke(grant.origin) }) { Text("Disconnect") }
            }
        }
    }
}
