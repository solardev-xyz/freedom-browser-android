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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import baby.freedom.mobile.R
import baby.freedom.mobile.l10n.Strings

/** What a [RadicleAsk] prompt says: the request, what it means, and the Allow button's label. */
internal data class RadiclePromptCopy(val request: String, val detail: String, val allow: String)

internal fun radiclePromptCopy(ask: RadicleAsk): RadiclePromptCopy = when (ask) {
    is RadicleAsk.Connect -> RadiclePromptCopy(
        Strings.get(R.string.radicle_prompt_connect_request),
        Strings.get(R.string.radicle_prompt_connect_detail),
        Strings.get(R.string.radicle_prompt_connect_allow),
    )
    is RadicleAsk.Seed -> RadiclePromptCopy(
        Strings.get(R.string.radicle_prompt_seed_request),
        Strings.get(R.string.radicle_prompt_seed_detail),
        Strings.get(R.string.radicle_prompt_seed_allow),
    )
    is RadicleAsk.Unseed -> RadiclePromptCopy(
        Strings.get(R.string.radicle_prompt_unseed_request),
        Strings.get(R.string.radicle_prompt_unseed_detail),
        Strings.get(R.string.radicle_prompt_unseed_allow),
    )
    is RadicleAsk.Signing -> if (ask.previousDid == null) {
        RadiclePromptCopy(
            Strings.get(R.string.radicle_prompt_signing_request),
            Strings.get(R.string.radicle_prompt_signing_detail),
            Strings.get(R.string.common_allow),
        )
    } else {
        RadiclePromptCopy(
            Strings.get(R.string.radicle_prompt_signing_changed_request),
            Strings.get(R.string.radicle_prompt_signing_changed_detail, shortDid(ask.previousDid)),
            Strings.get(R.string.common_allow),
        )
    }
}

/**
 * The identity a signing prompt asks about, as a label and the DID in
 * full (a spoofed or unexpected one hides in its tail); null for a
 * prompt that names none.
 */
internal fun radiclePromptIdentity(ask: RadicleAsk): Pair<String, String>? {
    if (ask !is RadicleAsk.Signing || ask.did.isEmpty()) return null
    val label = Strings.get(if (ask.wallet) R.string.radicle_prompt_identity_wallet else R.string.radicle_prompt_identity_own)
    return label to ask.did
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
    val identity = radiclePromptIdentity(ask)
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
                if (identity != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(identity.first, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    Text(identity.second, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
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
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}
