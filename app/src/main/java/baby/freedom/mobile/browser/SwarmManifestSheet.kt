package baby.freedom.mobile.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import baby.freedom.mobile.R
import baby.freedom.mobile.l10n.Strings

/** The browser's own name for a manifest row, and what granting it means — never the app's words. */
internal fun manifestRowLabel(capability: ManifestCapability): Pair<String, String> = when (capability) {
    ManifestCapability.Publish ->
        Strings.get(R.string.swarm_manifest_publish_label) to Strings.get(R.string.swarm_manifest_publish_meaning)
    ManifestCapability.Feeds ->
        Strings.get(R.string.swarm_manifest_feeds_label) to Strings.get(R.string.swarm_manifest_feeds_meaning)
    ManifestCapability.Signing ->
        Strings.get(R.string.swarm_manifest_signing_label) to Strings.get(R.string.swarm_manifest_signing_meaning)
    ManifestCapability.Messaging ->
        Strings.get(R.string.swarm_manifest_messaging_label) to Strings.get(R.string.swarm_manifest_messaging_meaning)
}

/** The notes under the rows: what happens to the site's publisher identity, and what still asks. */
internal fun manifestNotes(consent: SwarmManifests.Consent): List<String> = listOfNotNull(
    Strings.get(R.string.swarm_manifest_note_new_identity).takeIf { consent.createsIdentity },
    Strings.get(R.string.swarm_manifest_note_identity_kept).takeIf { consent.preservedIdentity },
    Strings.get(R.string.swarm_manifest_note_needs_wallet).takeIf { consent.needsWallet },
    consent.removed.takeIf { it.isNotEmpty() }?.let { removed ->
        Strings.get(R.string.swarm_manifest_note_removed, removed.joinToString(", ") { manifestRowLabel(it).first })
    },
    Strings.get(R.string.swarm_manifest_note_still_asks),
)

/**
 * The permission-manifest sheet (#122): the site asking — in full, as on
 * every approval sheet — then the name and description the app gives
 * itself (its own words, labelled as such), each row it asks for with
 * the browser's label and meaning and the app's reason, what happens to
 * its publisher identity, and three answers: Allow all, Use individual
 * approvals, Don't allow. Like the other approval sheets, the buttons, a
 * swipe down, a tap outside and Back ignore input until it has been on
 * screen, untouched, for [PromptTapGuard.SPEND_PROTECTION_MS] — every
 * touch anywhere in its window restarts that until it arms (#240);
 * dismissing it is Don't allow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwarmManifestSheet(request: SwarmPromptRequest, ask: SwarmAsk.Manifest) {
    val consent = ask.consent
    // Allow all hands the site standing grants at once: it arms as late as Sign (#240).
    val tap = rememberArmedTapGuard(request, PromptTapGuard.SPEND_PROTECTION_MS)
    val guard = tap.guard
    val armed = tap.armed
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || guard.accepts() },
    )

    fun answer(answer: SwarmProvider.Answer) {
        if (guard.accepts()) request.respond(answer)
    }

    ModalBottomSheet(
        onDismissRequest = { answer(SwarmProvider.Answer.REJECTED) },
        sheetState = sheetState,
        modifier = Modifier.testTag("swarm-manifest"),
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.VerifiedUser, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(if (consent.isUpdate) R.string.swarm_manifest_update_title else R.string.swarm_manifest_title),
                    style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(16.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().testTag("swarm-origin"),
            ) {
                Column(Modifier.padding(12.dp)) {
                    SelectionContainer {
                        Text(
                            permissionOriginDisplay(ask.origin),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(
                        stringResource(
                            if (consent.isUpdate) R.string.swarm_manifest_update_request else R.string.swarm_manifest_request_rows,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    stringResource(R.string.swarm_manifest_name_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.swarm_manifest_name, consent.name),
                    style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("swarm-manifest-name"))
                if (consent.description.isNotEmpty()) {
                    Text(consent.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                consent.rows.forEach { (capability, why) ->
                    val (label, meaning) = manifestRowLabel(capability)
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .testTag("swarm-manifest-row-${capability.wire}"),
                    ) {
                        Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(meaning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.swarm_manifest_reason, why), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                manifestNotes(consent).forEach {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            ObscuredTapNotice(tap)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { answer(SwarmProvider.Answer(allowed = true, always = true)) },
                    enabled = armed,
                    modifier = Modifier.fillMaxWidth().protectedPress(tap).testTag("swarm-manifest-allow"),
                ) { Text(stringResource(R.string.swarm_allow_all)) }
                OutlinedButton(
                    onClick = { answer(SwarmProvider.Answer(allowed = true, always = false)) },
                    enabled = armed,
                    modifier = Modifier.fillMaxWidth().protectedPress(tap).testTag("swarm-manifest-individual"),
                ) { Text(stringResource(R.string.swarm_manifest_individual)) }
                TextButton(
                    onClick = { answer(SwarmProvider.Answer.REJECTED) },
                    enabled = armed,
                    modifier = Modifier.fillMaxWidth().testTag("swarm-manifest-deny"),
                ) { Text(stringResource(R.string.swarm_manifest_deny)) }
            }
        }
    }
}
