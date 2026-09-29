package baby.freedom.mobile.browser

import android.os.SystemClock
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** The browser's own name for a manifest row, and what granting it means — never the app's words. */
internal fun manifestRowLabel(capability: ManifestCapability): Pair<String, String> = when (capability) {
    ManifestCapability.Publish -> "Publish content" to "Upload to Swarm with your postage stamps and bandwidth, without asking each time."
    ManifestCapability.Feeds -> "Manage feeds" to "Create and update this app's feeds without asking each time."
    ManifestCapability.Signing -> "Sign Swarm content" to "Sign with this app's publisher identity without asking each time."
    ManifestCapability.Messaging -> "Send and receive messages" to "Not available on this device yet: messaging requests are refused."
}

/** The notes under the rows: what happens to the site's publisher identity, and what still asks. */
internal fun manifestNotes(consent: SwarmManifests.Consent): List<String> = listOfNotNull(
    "Feeds and signing use a new app-scoped publisher identity for this site.".takeIf { consent.createsIdentity },
    "The site's existing publisher identity is kept.".takeIf { consent.preservedIdentity },
    "You don't have a wallet yet: the first signature asks you to set one up.".takeIf { consent.needsWallet },
    consent.removed.takeIf { it.isNotEmpty() }?.let { removed ->
        "No longer asked for: " + removed.joinToString(", ") { manifestRowLabel(it).first } +
            ". What the manifest allowed for them has been taken back."
    },
    "Uploads still need a usable postage stamp, and signing an unlocked wallet. " +
        "Use individual approvals to be asked before each upload and signature instead.",
)

/**
 * The permission-manifest sheet (#122): the site asking — in full, as on
 * every approval sheet — then the name and description the app gives
 * itself (its own words, labelled as such), each row it asks for with
 * the browser's label and meaning and the app's reason, what happens to
 * its publisher identity, and three answers: Allow all, Use individual
 * approvals, Don't allow. Like the other approval sheets, the buttons, a
 * swipe down, a tap outside and Back ignore input for the first
 * [PromptTapGuard.PROTECTION_MS] it is on screen; dismissing it is Don't allow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwarmManifestSheet(request: SwarmPromptRequest, ask: SwarmAsk.Manifest) {
    val consent = ask.consent
    val guard = remember(request) { PromptTapGuard(SystemClock::uptimeMillis) }
    var armed by remember(request) { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || guard.accepts() },
    )
    LaunchedEffect(request) {
        withFrameNanos { }
        guard.onShown()
        delay(guard.remainingMs())
        armed = true
    }

    fun answer(answer: SwarmProvider.Answer) {
        if (guard.accepts()) request.respond(answer)
    }

    ModalBottomSheet(
        onDismissRequest = { answer(SwarmProvider.Answer.REJECTED) },
        sheetState = sheetState,
        modifier = Modifier.testTag("swarm-manifest"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.VerifiedUser, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(if (consent.isUpdate) "App asks for more" else "App permissions", style = MaterialTheme.typography.titleLarge)
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
                        if (consent.isUpdate) "now asks for these Swarm permissions too" else "asks for these Swarm permissions",
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
                    "The app calls itself",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("“${consent.name}”", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("swarm-manifest-name"))
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
                        Text("App's reason: “$why”", style = MaterialTheme.typography.bodyMedium)
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
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { answer(SwarmProvider.Answer(allowed = true, always = true)) },
                    enabled = armed,
                    modifier = Modifier.fillMaxWidth().testTag("swarm-manifest-allow"),
                ) { Text("Allow all") }
                OutlinedButton(
                    onClick = { answer(SwarmProvider.Answer(allowed = true, always = false)) },
                    enabled = armed,
                    modifier = Modifier.fillMaxWidth().testTag("swarm-manifest-individual"),
                ) { Text("Use individual approvals") }
                TextButton(
                    onClick = { answer(SwarmProvider.Answer.REJECTED) },
                    enabled = armed,
                    modifier = Modifier.fillMaxWidth().testTag("swarm-manifest-deny"),
                ) { Text("Don't allow") }
            }
        }
    }
}
