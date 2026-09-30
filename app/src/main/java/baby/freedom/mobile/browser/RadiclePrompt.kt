package baby.freedom.mobile.browser

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** What a [RadicleAsk] prompt says: the request, what it means, and the Allow button's label. */
internal data class RadiclePromptCopy(val request: String, val detail: String, val allow: String)

internal fun radiclePromptCopy(ask: RadicleAsk): RadiclePromptCopy = when (ask) {
    is RadicleAsk.Connect -> RadiclePromptCopy(
        "wants to connect to your Radicle node",
        "It will see whether your node is running and which repositories you seed, " +
            "and can ask to seed or stop seeding repositories. It can't write anything as you " +
            "unless you allow that separately.",
        "Connect",
    )
    is RadicleAsk.Seed -> RadiclePromptCopy(
        "wants to seed a repository",
        "Your node will fetch it now, keep a copy and share it with other peers, " +
            "using storage and data for as long as you seed it.",
        "Seed",
    )
    is RadicleAsk.Unseed -> RadiclePromptCopy(
        "wants to stop seeding a repository",
        "Your node will stop sharing it and drop it from your seeded list.",
        "Stop seeding",
    )
    is RadicleAsk.Signing -> RadiclePromptCopy(
        "wants to act as you on Radicle",
        "It will see your Radicle identity (DID and node ID) and can open issues, comment and " +
            "change issue states signed with your key, without asking again. What it writes is " +
            "published to the network under your name and can't be taken back.",
        "Allow",
    )
}

/**
 * The `window.radicle` consent prompt (#124): the site in full (it wraps
 * rather than ellipsising — the tail of a host is what a spoof hides),
 * what it asks, the repository ID when there is one, Cancel / Allow.
 * Back or a tap outside is a Cancel. Like the site-permission prompt,
 * the buttons and the dismissal ignore taps for the first
 * [PromptTapGuard.PROTECTION_MS] it is on screen, counted from its first
 * drawn frame, so a page can't time its request to catch a tap meant for
 * the page.
 */
@Composable
fun RadiclePromptDialog(request: RadiclePromptRequest) {
    val ask = request.ask
    val copy = radiclePromptCopy(ask)
    val tap = rememberArmedTapGuard(request)
    val guard = tap.guard
    val armed = tap.armed
    val icon = when (ask) {
        is RadicleAsk.Connect -> Icons.Filled.Hub
        is RadicleAsk.Seed -> Icons.Filled.CloudDownload
        is RadicleAsk.Unseed -> Icons.Filled.CloudOff
        is RadicleAsk.Signing -> Icons.Filled.Edit
    }
    val rid = when (ask) {
        is RadicleAsk.Seed -> ask.rid
        is RadicleAsk.Unseed -> ask.rid
        else -> null
    }
    AlertDialog(
        onDismissRequest = { if (guard.accepts()) request.respond(false) },
        icon = { Icon(icon, contentDescription = null) },
        title = {
            Text(
                permissionOriginDisplay(ask.origin),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column {
                Text(copy.request)
                if (rid != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(rid, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    copy.detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ObscuredTapNotice(tap)
            }
        },
        confirmButton = {
            TextButton(
                enabled = armed,
                onClick = { if (guard.accepts()) request.respond(true) },
                modifier = Modifier.protectedPress(tap),
            ) {
                Text(copy.allow)
            }
        },
        dismissButton = {
            TextButton(enabled = armed, onClick = { if (guard.accepts()) request.respond(false) }) {
                Text("Cancel")
            }
        },
    )
}
