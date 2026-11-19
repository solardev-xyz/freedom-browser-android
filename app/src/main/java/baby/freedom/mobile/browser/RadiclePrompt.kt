package baby.freedom.mobile.browser

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import baby.freedom.mobile.R
import baby.freedom.mobile.l10n.Strings
import baby.freedom.mobile.wallet.Eip712

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
            if (ask.previousDid.isEmpty()) {
                Strings.get(R.string.radicle_prompt_signing_changed_detail_device)
            } else {
                Strings.get(R.string.radicle_prompt_signing_changed_detail, shortDid(ask.previousDid))
            },
            Strings.get(R.string.common_allow),
        )
    }
    is RadicleAsk.Write -> {
        val (request, allow) = when (ask.kind) {
            "radicle_createIssue" -> R.string.radicle_prompt_write_issue_request to R.string.radicle_prompt_write_issue_allow
            "radicle_editIssueState" -> R.string.radicle_prompt_write_state_request to R.string.radicle_prompt_write_state_allow
            "radicle_commentPatch" -> R.string.radicle_prompt_write_patch_comment_request to R.string.radicle_prompt_write_comment_allow
            else -> R.string.radicle_prompt_write_comment_request to R.string.radicle_prompt_write_comment_allow
        }
        val detail = Strings.get(R.string.radicle_prompt_write_detail)
        RadiclePromptCopy(
            Strings.get(request),
            if (ask.limited) Strings.get(R.string.radicle_prompt_write_limited) + " " + detail else detail,
            Strings.get(allow),
        )
    }
}

/** The tallest a write prompt's content box gets before it scrolls on its own. */
private val WRITE_CONTENT_MAX_HEIGHT = 280.dp

/** One labelled value a write prompt shows; [code] for an ID, set in monospace. */
internal data class RadicleWriteLine(val label: String, val value: String, val code: Boolean = false)

/**
 * Everything a [RadicleAsk.Write] publishes, in full: the page picks every
 * word of it, so a character that could hide or rearrange text around it
 * (a bidi override, a zero-width mark) is shown as an escape, the way the
 * wallet's signing sheets show it ([Eip712.visible]). Line breaks in a
 * description or comment are kept as line breaks.
 */
internal fun radicleWriteLines(ask: RadicleAsk.Write): List<RadicleWriteLine> = buildList {
    fun shown(s: String) = s.split('\n').joinToString("\n") { Eip712.visible(it) }
    add(RadicleWriteLine(Strings.get(R.string.radicle_rid_label), ask.rid, code = true))
    if (ask.target.isNotEmpty()) {
        val label = if (ask.kind == "radicle_commentPatch") R.string.radicle_prompt_write_revision else R.string.radicle_prompt_write_issue
        add(RadicleWriteLine(Strings.get(label), ask.target, code = true))
    }
    if (ask.claimedPatch.isNotEmpty()) {
        add(RadicleWriteLine(Strings.get(R.string.radicle_prompt_write_claimed_patch), shown(ask.claimedPatch), code = true))
    }
    if (ask.replyTo.isNotEmpty()) add(RadicleWriteLine(Strings.get(R.string.radicle_prompt_write_reply_to), ask.replyTo, code = true))
    if (ask.title.isNotEmpty()) add(RadicleWriteLine(Strings.get(R.string.radicle_prompt_write_title), shown(ask.title)))
    if (ask.labels.isNotEmpty()) {
        add(RadicleWriteLine(Strings.get(R.string.radicle_prompt_write_labels), ask.labels.joinToString(", ") { shown(it) }))
    }
    if (ask.state.isNotEmpty()) add(RadicleWriteLine(Strings.get(R.string.radicle_prompt_write_state), ask.state))
    if (ask.body.isNotEmpty()) {
        val label = if (ask.kind == "radicle_createIssue") R.string.radicle_prompt_write_description else R.string.radicle_prompt_write_comment
        add(RadicleWriteLine(Strings.get(label), shown(ask.body)))
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
        is RadicleAsk.Write -> Icons.Filled.Edit
    }
    val rid = when (ask) {
        is RadicleAsk.Seed -> ask.rid
        is RadicleAsk.Unseed -> ask.rid
        else -> null
    }
    val identity = radiclePromptIdentity(ask)
    val write = ask as? RadicleAsk.Write
    // A write's "always allow" switch starts where the site's rule is.
    var always by remember(request) { mutableStateOf(write?.limited == true) }
    // Cancel, Back and a tap outside are one answer, the switch included.
    fun answer(allowed: Boolean) = request.respond(allowed, if (write != null) always else null)
    AlertDialog(
        onDismissRequest = { if (guard.accepts()) answer(false) },
        icon = { Icon(icon, contentDescription = null) },
        title = {
            Text(
                permissionOriginDisplay(ask.origin),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            // Scrolls as a whole, so at a large font size nothing (what's
            // published least of all) is squeezed out of view.
            Column(Modifier.verticalScroll(rememberScrollState())) {
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
                if (write != null) {
                    // What's published also scrolls in a box of its own (a
                    // comment can be 64 KB), so the warning and the switch
                    // stay close by.
                    Column(
                        Modifier
                            .heightIn(max = WRITE_CONTENT_MAX_HEIGHT)
                            .verticalScroll(rememberScrollState())
                            .testTag("radicle-write-content"),
                    ) {
                        radicleWriteLines(write).forEach { line ->
                            Spacer(Modifier.height(8.dp))
                            Text(line.label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                line.value,
                                fontFamily = if (line.code) FontFamily.Monospace else null,
                                style = if (line.code) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    copy.detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (write != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .padding(top = 8.dp)
                            .protectedToggle(tap, value = always, role = Role.Switch) { always = it }
                            .testTag("radicle-always-allow"),
                    ) {
                        Text(
                            stringResource(R.string.radicle_prompt_write_always),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Switch(checked = always, onCheckedChange = null, enabled = armed)
                    }
                }
                ObscuredTapNotice(tap)
            }
        },
        confirmButton = {
            TextButton(
                enabled = armed,
                onClick = { if (guard.accepts()) answer(true) },
                modifier = Modifier.protectedPress(tap),
            ) {
                Text(copy.allow)
            }
        },
        dismissButton = {
            TextButton(enabled = armed, onClick = { if (guard.accepts()) answer(false) }) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}
