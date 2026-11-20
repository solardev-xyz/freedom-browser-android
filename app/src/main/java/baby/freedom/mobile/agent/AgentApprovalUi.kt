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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import baby.freedom.mobile.l10n.pluralText
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import baby.freedom.mobile.R
import baby.freedom.mobile.browser.BrowserState
import baby.freedom.mobile.browser.ObscuredTapNotice
import baby.freedom.mobile.browser.PromptTapGuard
import baby.freedom.mobile.browser.RestartsTapGuardInWindow
import baby.freedom.mobile.browser.protectedPress
import baby.freedom.mobile.browser.rememberArmedTapGuard
import baby.freedom.mobile.browser.restartsTapGuard

/**
 * Freedom Agent's approval card (#575), over everything else on screen
 * (the agent's chat included): the oldest action waiting
 * ([AgentApprovals.shown]), the site it's on as the app knows it, the
 * exact action, and the agent's own words for it below, labelled as
 * such. Its buttons, a swipe down, a tap outside and Back ignore input
 * until it has been on screen untouched for
 * [PromptTapGuard.PROTECTION_MS], like the app's other approval sheets;
 * dismissing it is Don't allow. Each answer settles the one request the
 * card was shown for.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentApprovalHost() {
    val approval by AgentApprovals.shown.collectAsState()
    val transfer by AgentTransfers.shown.collectAsState()
    val node by AgentNodeAsks.shown.collectAsState()
    val shown = approval ?: return
    // A wallet transfer's (#596) or node action's (#598) sheet asked earlier comes first: never two at once.
    if (!AgentSheets.shows(shown.id, transfer?.id, node?.id)) return
    androidx.compose.runtime.key(shown.id) {
        val tap = rememberArmedTapGuard(shown.id)
        val guard = tap.guard
        val sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { it != SheetValue.Hidden || guard.accepts() },
        )

        fun answer(approved: Boolean) {
            if (guard.accepts()) AgentApprovals.answer(shown.id, approved)
        }

        ModalBottomSheet(
            onDismissRequest = { answer(false) },
            sheetState = sheetState,
            modifier = Modifier.statusBarsPadding().testTag("agent-approval"),
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
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    ApprovalBody(shown)
                }
                Spacer(Modifier.height(16.dp))
                ObscuredTapNotice(tap)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { answer(true) },
                        enabled = tap.armed,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).protectedPress(tap).testTag("agent-approval-allow"),
                    ) { Text(stringResource(R.string.agent_approve_allow)) }
                    TextButton(
                        onClick = { answer(false) },
                        enabled = tap.armed,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("agent-approval-deny"),
                    ) { Text(stringResource(R.string.agent_approve_deny)) }
                }
            }
        }
    }
}

@Composable
private fun ApprovalBody(approval: AgentApproval) {
    val action = approval.action
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
    Text(headline(action), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    // A dialog names the frame that opened it; anything else, the tab's committed page.
    val site = ApprovalText.flatten((action as? AgentAction.Dialog)?.from?.ifEmpty { null } ?: approval.site, 200)
    if (!(action is AgentAction.Navigate && action.newTab)) {
        Text(
            if (site.isEmpty()) stringResource(R.string.agent_approve_site_none) else stringResource(R.string.agent_approve_site, site),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(12.dp))
    when (action) {
        is AgentAction.Navigate -> Field(stringResource(R.string.agent_approve_address), ApprovalText.flatten(action.url, BrowserArgs.MAX_URL_CHARS), mono = true)
        is AgentAction.Click -> TargetRows(action.target)
        is AgentAction.Type -> {
            TargetRows(action.target)
            Field(
                stringResource(R.string.agent_approve_text),
                when (val typed = ApprovalText.typed(action.text, BrowserArgs.MAX_TYPED_CHARS)) {
                    ApprovalText.Typed.Empty -> stringResource(
                        if (action.replace) R.string.agent_approve_text_empty else R.string.agent_approve_text_nothing,
                    )
                    is ApprovalText.Typed.Blank ->
                        pluralText(R.plurals.agent_approve_text_blank, typed.count, typed.count)
                    is ApprovalText.Typed.Shown -> typed.text
                },
                mono = true,
            )
        }
        is AgentAction.Select -> {
            TargetRows(action.target)
            Field(
                stringResource(R.string.agent_approve_options),
                action.values.takeIf { it.isNotEmpty() }?.joinToString("\n") { ApprovalText.flatten(it, 2_000) }
                    ?: stringResource(R.string.agent_approve_options_none),
                mono = true,
            )
        }
        is AgentAction.Press -> TargetRows(action.target)
        is AgentAction.Scroll -> action.target?.takeIf { it.role != "viewport" }?.let { TargetRows(it) }
        is AgentAction.Dialog -> {
            Field(stringResource(R.string.agent_approve_message), action.message.ifEmpty { "—" })
            if (action.accept && action.promptText != null) {
                Field(stringResource(R.string.agent_approve_prompt_answer), ApprovalText.flatten(action.promptText, 200), mono = true)
            }
        }
    }
    approval.intent?.takeIf { it.isNotBlank() }?.let {
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.agent_approve_intent, it),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    approval.check?.let { check ->
        Spacer(Modifier.height(8.dp))
        Text(
            when (check) {
                is ApprovalCheck.Judged -> stringResource(R.string.agent_approve_check, check.summary)
                ApprovalCheck.SendsForm -> stringResource(R.string.agent_approve_check_form)
                ApprovalCheck.Credential -> stringResource(R.string.agent_approve_check_credential)
                ApprovalCheck.NoAnswer -> stringResource(R.string.agent_approve_check_none)
                ApprovalCheck.NeedsScreen -> stringResource(R.string.agent_approve_check_screen)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(8.dp))
    if (action !is AgentAction.Navigate) {
        Text(
            stringResource(R.string.agent_approve_untrusted),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (action !is AgentAction.Navigate && action !is AgentAction.Dialog) {
        Text(
            stringResource(R.string.agent_approve_shows_tab),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun headline(action: AgentAction): String = when (action) {
    is AgentAction.Navigate -> stringResource(if (action.newTab) R.string.agent_approve_open_tab else R.string.agent_approve_navigate)
    is AgentAction.Click -> stringResource(R.string.agent_approve_click)
    is AgentAction.Type -> stringResource(if (action.replace) R.string.agent_approve_type_replace else R.string.agent_approve_type_append)
    is AgentAction.Select -> stringResource(R.string.agent_approve_select)
    is AgentAction.Press -> stringResource(R.string.agent_approve_press, action.key)
    is AgentAction.Scroll -> stringResource(
        when (action.direction) {
            "up" -> R.string.agent_approve_scroll_up
            "left" -> R.string.agent_approve_scroll_left
            "right" -> R.string.agent_approve_scroll_right
            else -> R.string.agent_approve_scroll_down
        },
    )
    is AgentAction.Dialog -> stringResource(if (action.accept) R.string.agent_approve_dialog_ok else R.string.agent_approve_dialog_cancel)
}

@Composable
private fun TargetRows(target: PageTarget) {
    val kind = target.role.ifBlank { target.tag }
    Field(
        stringResource(R.string.agent_approve_control),
        if (target.name.isBlank()) stringResource(R.string.agent_approve_control_unnamed, kind)
        else stringResource(R.string.agent_approve_control_value, kind, target.name),
    )
    target.target?.let { Field(stringResource(R.string.agent_approve_goes_to), it, mono = true) }
    val note = when (target.effect) {
        "form_submission" -> R.string.agent_approve_submits
        "file_download" -> R.string.agent_approve_downloads
        "file_upload" -> R.string.agent_approve_uploads
        else -> null
    }
    note?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium) }
    if (target.sensitive) {
        Text(stringResource(R.string.agent_approve_sensitive), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Field(label: String, value: String, mono: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = if (mono) FontFamily.Monospace else null)
    }
}

/**
 * The strip over a tab Freedom Agent holds (#575): says so, and gives
 * the tab back. Taking it back stops nothing else: the agent's turn goes
 * on without it, and any approval waiting on this tab is withdrawn.
 */
@Composable
internal fun AgentTabStrip(tab: BrowserState, modifier: Modifier = Modifier) {
    if (!AgentTabs.isHeld(tab.id)) return
    val description = stringResource(R.string.agent_tab_take_back_description)
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier.fillMaxWidth().testTag("agent-tab-strip"),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 4.dp),
        ) {
            Icon(Icons.Filled.SmartToy, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.agent_tab_held),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { AgentTabs.release(tab.id) },
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = description },
            ) { Text(stringResource(R.string.agent_tab_take_back)) }
        }
    }
}
