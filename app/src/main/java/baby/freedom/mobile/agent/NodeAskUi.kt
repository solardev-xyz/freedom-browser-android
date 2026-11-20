package baby.freedom.mobile.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import baby.freedom.mobile.R
import baby.freedom.mobile.browser.ObscuredTapNotice
import baby.freedom.mobile.browser.PromptTapGuard
import baby.freedom.mobile.browser.RestartsTapGuardInWindow
import baby.freedom.mobile.browser.protectedPress
import baby.freedom.mobile.browser.rememberArmedTapGuard
import baby.freedom.mobile.browser.restartsTapGuard

/**
 * Where the node tools' sheets (#598) come up: on the browser screen,
 * over everything (the chat included), next to [AgentApprovalHost] and
 * [AgentTransferHost]; of the three, the one asked first shows
 * ([AgentSheets]). While it's composed the tools may ask
 * ([AgentNodeAsks.hostUp]); once it's gone every waiting ask is withdrawn.
 *
 * Built only from what the app checked: the service, the action or the
 * canonical request exactly as it would be sent, the floor-raised effect,
 * and the classifier's summary labelled as the model's words. Allow arms
 * after the usual untouched time ([PromptTapGuard]); Don't allow, a swipe
 * down, a tap outside and Back are the user's no.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NodeAskHost() {
    DisposableEffect(Unit) {
        AgentNodeAsks.hostShown()
        onDispose {
            if (AgentNodeAsks.hostGone()) AgentNodeAsks.withdrawAll()
        }
    }
    val ask by AgentNodeAsks.shown.collectAsState()
    val approval by AgentApprovals.shown.collectAsState()
    val transfer by AgentTransfers.shown.collectAsState()
    val shown = ask ?: return
    if (!AgentSheets.shows(shown.id, approval?.id, transfer?.id)) return
    key(shown.id) {
        val tap = rememberArmedTapGuard(shown.id, PromptTapGuard.PROTECTION_MS)
        val guard = tap.guard
        val sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { it != SheetValue.Hidden || guard.accepts() },
        )

        fun answer(approved: Boolean) {
            if (guard.accepts()) AgentNodeAsks.answer(shown.id, approved)
        }

        ModalBottomSheet(
            onDismissRequest = { answer(false) },
            sheetState = sheetState,
            modifier = Modifier.statusBarsPadding().testTag("agent-node-ask"),
        ) {
            RestartsTapGuardInWindow(guard)
            Column(
                modifier = Modifier
                    .restartsTapGuard(guard)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 16.dp),
            ) {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                    NodeAskBody(shown)
                }
                Spacer(Modifier.height(16.dp))
                ObscuredTapNotice(tap)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { answer(true) },
                        enabled = tap.armed,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).protectedPress(tap).testTag("agent-node-allow"),
                    ) { Text(stringResource(allowLabel(shown.kind))) }
                    TextButton(
                        onClick = { answer(false) },
                        enabled = tap.armed,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("agent-node-deny"),
                    ) { Text(stringResource(R.string.agent_approve_deny)) }
                }
            }
        }
    }
}

private fun allowLabel(kind: NodeAskKind): Int = when (kind) {
    is NodeAskKind.Request -> R.string.agent_node_allow_request
    is NodeAskKind.Lifecycle -> when (kind.action) {
        "start" -> R.string.agent_node_allow_start
        "stop" -> R.string.agent_node_allow_stop
        else -> R.string.agent_node_allow_restart
    }
    is NodeAskKind.Diagnostics -> R.string.agent_node_allow_share
}

/** A service's name as the Nodes & networks pages call it. */
@Composable
internal fun nodeServiceName(service: String): String = stringResource(
    when (service) {
        "ant" -> R.string.agent_node_service_ant
        "ipfs" -> R.string.agent_node_service_ipfs
        "radicle" -> R.string.agent_node_service_radicle
        "tor" -> R.string.agent_node_service_tor
        "myotis-ethereum" -> R.string.agent_node_service_myotis_ethereum
        else -> R.string.agent_node_service_myotis_gnosis
    },
)

@Composable
private fun NodeAskBody(ask: NodeAsk) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.SmartToy, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Text(
            stringResource(R.string.agent_approve_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
    }
    Spacer(Modifier.height(8.dp))
    when (val kind = ask.kind) {
        is NodeAskKind.Lifecycle -> {
            val name = nodeServiceName(kind.service)
            Headline(
                stringResource(
                    when (kind.action) {
                        "start" -> R.string.agent_node_start
                        "stop" -> R.string.agent_node_stop
                        else -> R.string.agent_node_restart
                    },
                    name,
                ),
            )
            Field(stringResource(R.string.agent_node_now), stateText(kind.beforeState))
            if (kind.service == "ant") {
                Note(
                    stringResource(
                        when (kind.action) {
                            "start" -> R.string.agent_node_ant_start_note
                            "stop" -> R.string.agent_node_ant_stop_note
                            else -> R.string.agent_node_ant_restart_note
                        },
                    ),
                )
                if (kind.also.isNotEmpty()) {
                    Note(
                        stringResource(
                            R.string.agent_node_also_stops,
                            kind.also.map { nodeServiceName(it) }.joinToString(", "),
                        ),
                        strong = true,
                    )
                }
            }
        }
        is NodeAskKind.Request -> {
            val args = kind.args
            Headline(
                stringResource(
                    if (args.write) R.string.agent_node_request_write else R.string.agent_node_request_read,
                    nodeServiceName(args.service),
                ),
            )
            Field(stringResource(R.string.agent_node_request_label), "${args.method} ${args.path}", mono = true)
            if (args.headers.isNotEmpty()) {
                Field(
                    stringResource(R.string.agent_node_request_headers),
                    args.headers.entries.joinToString("\n") { (k, v) -> "$k: $v" },
                    mono = true,
                )
            }
            args.body?.let { body ->
                Field(
                    stringResource(R.string.agent_node_request_body),
                    when (val typed = ApprovalText.typed(body, NodeRequestArgs.MAX_BODY_BYTES)) {
                        ApprovalText.Typed.Empty -> stringResource(R.string.agent_node_request_body_empty)
                        is ApprovalText.Typed.Blank -> stringResource(R.string.agent_node_request_body_blank, typed.count)
                        is ApprovalText.Typed.Shown -> typed.text
                    },
                    mono = true,
                )
            }
            if (args.write) Note(stringResource(R.string.agent_node_request_write_note))
        }
        is NodeAskKind.Diagnostics -> {
            // The light clients write one log: sharing either's shares both chains' lines.
            val shared = NodeDiagnosticsTool.logCovers(kind.service).size > 1
            Headline(
                if (shared) {
                    stringResource(R.string.agent_node_diagnostics_light_clients)
                } else {
                    stringResource(R.string.agent_node_diagnostics, nodeServiceName(kind.service))
                },
            )
            Field(
                stringResource(R.string.agent_node_diagnostics_to),
                kind.provider?.let { ApprovalText.flatten(it, 80) } ?: stringResource(R.string.agent_node_diagnostics_provider_unknown),
            )
            Field(
                stringResource(R.string.agent_node_diagnostics_amount),
                stringResource(R.string.agent_node_diagnostics_amount_value, kind.maxLines, kind.maxBytes / 1024),
            )
            Note(stringResource(R.string.agent_node_diagnostics_note))
        }
    }
    ask.effect?.let { Field(stringResource(R.string.agent_node_effect), effectText(it)) }
    // The model's own judgment, labelled as such; none when it gave none.
    ask.classification?.takeIf { it.fallbackReason == null }?.let { ApprovalText.flatten(it.summary, 240) }?.ifEmpty { null }?.let {
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.agent_approve_check, it),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun effectText(effect: Effect): String = stringResource(
    when (effect) {
        Effect.READ -> R.string.agent_node_effect_read
        Effect.REVERSIBLE_ADMIN -> R.string.agent_node_effect_reversible
        Effect.PERSISTENT_CHANGE -> R.string.agent_node_effect_persistent
        Effect.FINANCIAL -> R.string.agent_node_effect_financial
        Effect.DESTRUCTIVE -> R.string.agent_node_effect_destructive
        Effect.UNKNOWN -> R.string.agent_node_effect_unknown
    },
)

@Composable
private fun stateText(state: String): String = when (state) {
    "running", "ready" -> stringResource(R.string.agent_node_state_running)
    "starting" -> stringResource(R.string.agent_node_state_starting)
    "syncing" -> stringResource(R.string.agent_node_state_syncing)
    "stopping" -> stringResource(R.string.agent_node_state_stopping)
    "stopped" -> stringResource(R.string.agent_node_state_stopped)
    "disabled" -> stringResource(R.string.agent_node_state_disabled)
    "error" -> stringResource(R.string.agent_node_state_error)
    else -> state
}

@Composable
private fun Headline(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun Note(text: String, strong: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = if (strong) FontWeight.Medium else null,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun Field(label: String, value: String, mono: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = if (mono) FontFamily.Monospace else null)
    }
}
