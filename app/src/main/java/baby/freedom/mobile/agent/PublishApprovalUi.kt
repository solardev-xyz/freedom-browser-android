package baby.freedom.mobile.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import baby.freedom.mobile.R
import baby.freedom.mobile.browser.ArmedTapGuard
import baby.freedom.mobile.browser.ObscuredTapNotice
import baby.freedom.mobile.browser.formatStampBytes
import baby.freedom.mobile.browser.formatStampTtl
import baby.freedom.mobile.browser.protectedPress
import baby.freedom.mobile.browser.shortBatchId
import java.text.NumberFormat

/** How much of the text the publish card shows; the rest is said to be there. */
internal const val PUBLISH_PREVIEW_CHARS = 4_000

/** Whether the card's Publish publicly may be pressed: a usable stamp, and every secret ticked. */
internal fun publishAllowed(action: AgentAction.Publish, acknowledged: Collection<String>): Boolean =
    action.postage is PostagePreview.Ready && action.findings.filter { it.blocking }.all { it.id in acknowledged }

/**
 * The card for `swarm_publish` (#597), inside [AgentApprovalHost]'s
 * sheet: the fixed public-and-permanent notice, what the pre-publish
 * check found, each secret with its own "Publish this anyway" box, the
 * stamp it would use, and the text that goes out (all of it up to
 * [PUBLISH_PREVIEW_CHARS], and how much more there is). [settle] is the sheet's
 * guarded answer. What was found and the stamp come first, so the reason
 * the button is off shows without scrolling past a long text.
 */
@Composable
internal fun PublishApprovalContent(approval: AgentApproval, action: AgentAction.Publish, tap: ArmedTapGuard, settle: (ApprovalOutcome) -> Unit) {
    val acknowledged = remember(approval.id) { mutableStateListOf<String>() }
    Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
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
        Text(stringResource(R.string.agent_publish_headline), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.agent_publish_permanent), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.agent_publish_findings_title), style = MaterialTheme.typography.titleSmall)
        if (action.findings.isEmpty()) {
            Text(stringResource(R.string.agent_publish_findings_none), style = MaterialTheme.typography.bodyMedium)
        } else {
            if (action.findings.any { it.blocking }) {
                Text(stringResource(R.string.agent_publish_findings_blocking), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }
            for (f in action.findings) FindingRow(f, f.id in acknowledged) { on -> if (on) acknowledged.add(f.id) else acknowledged.remove(f.id) }
        }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.agent_publish_postage_title), style = MaterialTheme.typography.titleSmall)
        PostageRows(action.postage)

        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        val total = action.text.codePointCount(0, action.text.length)
        Text(
            stringResource(
                R.string.agent_publish_text_label,
                formatStampBytes(action.bytes.toLong()),
                ApprovalText.flatten(action.contentType, 255),
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            when (val shown = ApprovalText.typed(action.text, PUBLISH_PREVIEW_CHARS)) {
                is ApprovalText.Typed.Shown -> shown.text
                is ApprovalText.Typed.Blank -> pluralBlank(shown.count)
                ApprovalText.Typed.Empty -> ""
            },
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.testTag("agent-publish-text"),
        )
        if (total > PUBLISH_PREVIEW_CHARS) {
            val n = NumberFormat.getIntegerInstance()
            Text(
                stringResource(R.string.agent_publish_text_cut, n.format(PUBLISH_PREVIEW_CHARS), n.format(total)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(Modifier.height(16.dp))
    ObscuredTapNotice(tap)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = { settle(ApprovalOutcome.Publish(acknowledged.toSet())) },
            enabled = tap.armed && publishAllowed(action, acknowledged),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).protectedPress(tap).testTag("agent-approval-allow"),
        ) { Text(stringResource(R.string.agent_publish_button)) }
        if (action.findings.isNotEmpty()) {
            OutlinedButton(
                onClick = { settle(ApprovalOutcome.AskToFix) },
                enabled = tap.armed,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("agent-publish-fix"),
            ) { Text(stringResource(R.string.agent_publish_fix)) }
        }
        TextButton(
            onClick = { settle(ApprovalOutcome.Declined) },
            enabled = tap.armed,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("agent-approval-deny"),
        ) { Text(stringResource(R.string.agent_approve_deny)) }
    }
}

@Composable
private fun pluralBlank(count: Int): String =
    baby.freedom.mobile.l10n.pluralText(R.plurals.agent_approve_text_blank, count, count)

@Composable
private fun FindingRow(f: PublicationScan.Finding, ticked: Boolean, onTick: (Boolean) -> Unit) {
    val kind = stringResource(
        when (f.kind) {
            PublicationScan.Kind.Secret -> R.string.agent_publish_kind_secret
            PublicationScan.Kind.Personal -> R.string.agent_publish_kind_personal
            PublicationScan.Kind.ThirdParty -> R.string.agent_publish_kind_third_party
            PublicationScan.Kind.Metadata -> R.string.agent_publish_kind_metadata
        },
    )
    val title = "$kind: ${f.label}"
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(
            f.line?.let { stringResource(R.string.agent_publish_finding_line, title, it) } ?: title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (f.blocking) FontWeight.SemiBold else null,
            color = if (f.blocking) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        Text(ApprovalText.flatten(f.excerpt, 200), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        if (f.blocking) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .toggleable(value = ticked, role = Role.Checkbox, onValueChange = onTick)
                    .testTag("agent-publish-anyway-${f.id}"),
            ) {
                Checkbox(checked = ticked, onCheckedChange = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.agent_publish_anyway), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun PostageRows(postage: PostagePreview) {
    val body = MaterialTheme.typography.bodyMedium
    when (postage) {
        is PostagePreview.Ready -> {
            Text(
                stringResource(
                    R.string.agent_publish_postage_ready,
                    shortBatchId(postage.batch.id),
                    formatStampBytes(postage.stampBytes),
                    formatStampBytes(postage.remainingBytes),
                ),
                style = body,
            )
            val ttl = postage.batch.ttlSeconds
            Text(
                if (ttl == null) stringResource(R.string.agent_publish_postage_ttl_unknown)
                else stringResource(R.string.agent_publish_postage_ttl, formatStampTtl(ttl)),
                style = body,
            )
            when {
                ttl != null && ttl < ONE_DAY -> Text(stringResource(R.string.agent_publish_postage_critical), style = body, color = MaterialTheme.colorScheme.error)
                ttl != null && ttl < 7 * ONE_DAY -> Text(stringResource(R.string.agent_publish_postage_soon), style = body, fontWeight = FontWeight.Medium)
            }
        }
        is PostagePreview.NoStamp -> {
            Text(postage.message, style = body)
            Text(stringResource(R.string.agent_publish_postage_blocked), style = body, fontWeight = FontWeight.Medium)
        }
        is PostagePreview.NodeUnavailable -> {
            Text(postage.message, style = body)
            Text(stringResource(R.string.agent_publish_postage_blocked), style = body, fontWeight = FontWeight.Medium)
        }
        PostagePreview.Unknown -> Text(stringResource(R.string.agent_publish_postage_unknown), style = body, fontWeight = FontWeight.Medium)
    }
    Text(
        stringResource(R.string.agent_publish_no_buy),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private const val ONE_DAY = 86_400L
