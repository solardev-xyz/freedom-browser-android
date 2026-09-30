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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import baby.freedom.mobile.R
import baby.freedom.mobile.l10n.Strings
import baby.freedom.mobile.l10n.pluralText
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
internal val RADICLE_ROW_TITLE: String get() = Strings.get(R.string.radicle_row_title)

/** The Nodes section's Radicle row, for settings search. */
internal fun radicleSettingsRow(radicle: RadicleControls) = settingsRow(
    RADICLE_ROW_KEY,
    RADICLE_ROW_TITLE,
    radicleSummary(radicle.info, radicle.enabled),
    Strings.get(R.string.radicle_row_search_hint),
)

/** One line on where the Radicle node stands, for its Settings row. */
internal fun radicleSummary(info: RadicleInfo, enabled: Boolean): String = when {
    !enabled -> Strings.get(R.string.radicle_status_off)
    info.status == RadicleStatus.Running ->
        Strings.plural(R.plurals.radicle_summary_running, info.connectedPeers, info.connectedPeers)
    info.status == RadicleStatus.Starting -> Strings.get(R.string.radicle_status_starting)
    info.status == RadicleStatus.Stopping -> Strings.get(R.string.radicle_status_stopping)
    info.status == RadicleStatus.Error -> Strings.get(R.string.radicle_status_error)
    // On, but `:node` isn't up (the node service is off) or hasn't
    // started it yet.
    else -> Strings.get(R.string.radicle_status_waiting_service)
}

/** The seed line under the RID field: the phase and its detail. */
internal fun seedLine(seed: RadicleSeed): String {
    val phase = when (seed.phase) {
        "resolving" -> Strings.get(R.string.radicle_seed_phase_resolving)
        "connecting" -> Strings.get(R.string.radicle_seed_phase_connecting)
        "fetching" -> Strings.get(R.string.radicle_seed_phase_fetching)
        "peer-failed" -> Strings.get(R.string.radicle_seed_phase_peer_failed)
        RadicleNode.PHASE_DONE -> Strings.get(R.string.radicle_seed_phase_done)
        RadicleNode.PHASE_CANCELLED -> Strings.get(R.string.radicle_seed_phase_cancelled)
        RadicleNode.PHASE_FAILED -> Strings.get(R.string.radicle_seed_phase_failed)
        else -> seed.phase
    }
    val detail = seed.shown
    return if (detail.isEmpty()) phase else Strings.get(R.string.radicle_seed_line_detail, phase, detail)
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
    /** Open the node logs page (#276) at Radicle's. */
    onOpenLogs: () -> Unit = {},
) {
    BackHandler(onBack = onDismiss)
    val info = radicle.info
    FullScreenScaffold(
        title = stringResource(R.string.radicle_row_title),
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
                    onOpenLogs = onOpenLogs,
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
    onOpenLogs: () -> Unit,
) {
    val (color, icon, labelRes) = when {
        !enabled -> Triple(Color(0xFF94A3B8), Icons.Filled.PowerSettingsNew, R.string.radicle_status_off)
        info.status == RadicleStatus.Running -> Triple(Color(0xFF22C55E), Icons.Filled.CheckCircle, R.string.radicle_status_running)
        info.status == RadicleStatus.Error -> Triple(Color(0xFFEF4444), Icons.Filled.ErrorOutline, R.string.radicle_status_error)
        info.status == RadicleStatus.Stopping -> Triple(Color(0xFFF59E0B), Icons.Filled.HourglassTop, R.string.radicle_status_stopping)
        info.status == RadicleStatus.Starting -> Triple(Color(0xFFF59E0B), Icons.Filled.HourglassTop, R.string.radicle_status_starting)
        else -> Triple(Color(0xFF94A3B8), Icons.Filled.HourglassTop, R.string.radicle_status_waiting)
    }
    val label = stringResource(labelRes)
    SectionCard(title = stringResource(R.string.radicle_section_status)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .switchRow(checked = enabled, onCheckedChange = onToggle, label = stringResource(R.string.radicle_row_title))
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = color)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(label, fontWeight = FontWeight.Medium)
                Text(
                    when {
                        !enabled -> stringResource(R.string.radicle_status_off_detail)
                        !runNodeEnabled -> stringResource(R.string.radicle_status_needs_swarm)
                        info.status == RadicleStatus.Running ->
                            pluralText(R.plurals.radicle_status_connected, info.connectedPeers, info.connectedPeers)
                        info.status == RadicleStatus.Starting -> stringResource(R.string.radicle_status_starting_detail)
                        info.status == RadicleStatus.Stopping -> stringResource(R.string.radicle_status_stopping_detail)
                        info.status == RadicleStatus.Error -> stringResource(R.string.radicle_status_error_detail)
                        else -> stringResource(R.string.radicle_status_waiting_service)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = null)
        }
        val err = info.errorMessage
        if (enabled && !err.isNullOrBlank()) {
            DetailRow(stringResource(R.string.radicle_detail_error), err, singleLine = false)
        }
        // A failed start isn't retried behind the user's back; this asks
        // `:node` to boot again (as the next bind would).
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            LogsButton(onOpenLogs)
            if (enabled && runNodeEnabled && info.status == RadicleStatus.Error) {
                TextButton(onClick = { onToggle(true) }) { Text(stringResource(R.string.common_retry)) }
            }
        }
    }
}

@Composable
private fun RadicleIdentitySection(info: RadicleInfo) {
    SectionCard(title = stringResource(R.string.radicle_section_identity)) {
        DetailRow(stringResource(R.string.radicle_identity_alias), info.alias)
        DetailRow(stringResource(R.string.radicle_identity_peers), info.connectedPeers.toString())
        StackedValue(stringResource(R.string.radicle_identity_did), info.did)
        StackedValue(stringResource(R.string.radicle_identity_node_id), info.nid)
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
    SectionCard(title = stringResource(R.string.radicle_section_seeded)) {
        if (info.seededRepos.isEmpty()) {
            Text(
                stringResource(R.string.radicle_nothing_seeded),
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
                                stringResource(R.string.radicle_awaiting_first_fetch),
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
                        TextButton(onClick = { onOpen(repo.rid) }) { Text(stringResource(R.string.common_open)) }
                    }
                    // Also the way out for a RID that never fetched.
                    TextButton(onClick = { onUnseed(repo.rid) }) { Text(stringResource(R.string.radicle_stop_seeding_button)) }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        NoSuggestionsTextInput {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text(stringResource(R.string.radicle_rid_label)) },
                placeholder = { Text(stringResource(R.string.radicle_rid_placeholder)) },
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
            ) { Text(stringResource(if (busy) R.string.radicle_seeding_button else R.string.radicle_seed_button)) }
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
    SectionCard(title = stringResource(R.string.radicle_section_sites)) {
        grants.forEach { grant ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f).padding(vertical = 4.dp)) {
                    Text(permissionOriginDisplay(grant.origin), fontWeight = FontWeight.Medium)
                    Text(
                        stringResource(if (grant.signing) R.string.radicle_site_can_sign else R.string.radicle_site_can_connect),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { onRevoke(grant.origin) }) { Text(stringResource(R.string.common_disconnect)) }
            }
        }
    }
}
