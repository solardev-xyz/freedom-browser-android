package baby.freedom.mobile.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.text.font.FontStyle
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

/**
 * A run of spaces longer than this, inside one line, is shown as a
 * [RadicleWriteRow.Spaces] marker: a long run of no-break or ideographic
 * spaces wraps into blank screen lines of its own, padding what follows
 * out of view. Shorter runs (code indentation) are shown as they are.
 */
internal const val WRITE_MAX_SPACE_RUN = 16

/**
 * One row of a write prompt's content box. [Text] is what the page wrote,
 * with hidden characters escaped; [Blank] and [Spaces] stand in for runs
 * of blank lines and of spaces, which are published too but would only
 * push the rest of the text out of view, so they're counted rather than drawn.
 */
internal sealed interface RadicleWriteRow {
    data class Text(val text: String) : RadicleWriteRow
    data class Blank(val lines: Int) : RadicleWriteRow
    data class Spaces(val count: Int) : RadicleWriteRow
}

/**
 * One labelled value a write prompt shows; [code] for an ID, set in
 * monospace; [boxed] for a list whose items are told apart by a box each
 * (labels: one can contain ", ").
 */
internal data class RadicleWriteLine(
    val label: String,
    val rows: List<RadicleWriteRow>,
    val code: Boolean = false,
    val boxed: Boolean = false,
)

/** A space a line can be padded with: any Unicode space separator (NBSP, U+3000 and the like). */
private fun isPadding(c: Int) = Character.getType(c) == Character.SPACE_SEPARATOR.toInt()

/**
 * [line] (no line breaks in it) as rows: the text with hidden characters
 * escaped ([Eip712.visible]), a run of more than [WRITE_MAX_SPACE_RUN]
 * spaces split out as a [RadicleWriteRow.Spaces].
 */
private fun lineRows(line: String, out: MutableList<RadicleWriteRow>) {
    val start = out.size
    var from = 0
    var i = 0
    while (i < line.length) {
        val c = line.codePointAt(i)
        if (!isPadding(c)) {
            i += Character.charCount(c)
            continue
        }
        var end = i
        var n = 0
        while (end < line.length && isPadding(line.codePointAt(end))) {
            end += Character.charCount(line.codePointAt(end))
            n++
        }
        if (n > WRITE_MAX_SPACE_RUN) {
            if (i > from) out += RadicleWriteRow.Text(Eip712.visible(line.substring(from, i)))
            out += RadicleWriteRow.Spaces(n)
            from = end
        }
        i = end
    }
    // An empty line is an empty row: one line of space, as published.
    if (from < line.length || out.size == start) out += RadicleWriteRow.Text(Eip712.visible(line.substring(from)))
}

/**
 * A one-line field (a title, a label, an ID the page names) as rows: a
 * line break in it is escaped (`\n`) like any other hidden character, so
 * it can't open blank space under the field.
 */
internal fun radicleOneLineRows(s: String): List<RadicleWriteRow> = buildList { lineRows(s, this) }

/**
 * A description or comment as rows, a line each, line breaks kept. Two or
 * more blank lines (nothing but spaces) in a row are one
 * [RadicleWriteRow.Blank] with their count, so a page can't pad what it
 * really publishes below a screenful of nothing (#544).
 */
internal fun radicleTextRows(s: String): List<RadicleWriteRow> = buildList {
    val lines = s.split('\n')
    var i = 0
    while (i < lines.size) {
        var blank = 0
        while (i + blank < lines.size && lines[i + blank].codePoints().allMatch(::isPadding)) blank++
        when {
            blank >= 2 -> { add(RadicleWriteRow.Blank(blank)); i += blank }
            else -> { lineRows(lines[i], this); i++ }
        }
    }
}

/**
 * Everything a [RadicleAsk.Write] publishes, in full: the page picks every
 * word of it, so a character that could hide or rearrange text around it
 * (a bidi override, a zero-width mark) is shown as an escape, the way the
 * wallet's signing sheets show it ([Eip712.visible]), and so is a line
 * break in a one-line field. Line breaks in a description or comment are
 * kept, padding is counted ([radicleTextRows]).
 */
internal fun radicleWriteLines(ask: RadicleAsk.Write): List<RadicleWriteLine> = buildList {
    fun id(s: String) = listOf(RadicleWriteRow.Text(s))
    add(RadicleWriteLine(Strings.get(R.string.radicle_rid_label), id(ask.rid), code = true))
    if (ask.target.isNotEmpty()) {
        val label = if (ask.kind == "radicle_commentPatch") R.string.radicle_prompt_write_revision else R.string.radicle_prompt_write_issue
        add(RadicleWriteLine(Strings.get(label), id(ask.target), code = true))
    }
    if (ask.claimedPatch.isNotEmpty()) {
        add(RadicleWriteLine(Strings.get(R.string.radicle_prompt_write_claimed_patch), radicleOneLineRows(ask.claimedPatch), code = true))
    }
    if (ask.replyTo.isNotEmpty()) add(RadicleWriteLine(Strings.get(R.string.radicle_prompt_write_reply_to), id(ask.replyTo), code = true))
    if (ask.title.isNotEmpty()) add(RadicleWriteLine(Strings.get(R.string.radicle_prompt_write_title), radicleOneLineRows(ask.title)))
    if (ask.labels.isNotEmpty()) {
        // A box and a row each, and their number: "bug, wontfix" is one label, not two.
        add(
            RadicleWriteLine(
                Strings.plural(R.plurals.radicle_prompt_write_labels_count, ask.labels.size, ask.labels.size),
                // At most 100 bytes each: too short for padding to push anything out of view.
                ask.labels.map { RadicleWriteRow.Text(Eip712.visible(it)) },
                boxed = true,
            ),
        )
    }
    if (ask.state.isNotEmpty()) add(RadicleWriteLine(Strings.get(R.string.radicle_prompt_write_state), radicleOneLineRows(ask.state)))
    if (ask.body.isNotEmpty()) {
        val label = if (ask.kind == "radicle_createIssue") R.string.radicle_prompt_write_description else R.string.radicle_prompt_write_comment
        add(RadicleWriteLine(Strings.get(label), radicleTextRows(ask.body)))
    }
}

/** What [row] reads as on screen; a marker in the words the prompt shows it with. */
internal fun rowText(row: RadicleWriteRow): String = when (row) {
    is RadicleWriteRow.Text -> row.text
    is RadicleWriteRow.Blank -> Strings.plural(R.plurals.radicle_prompt_write_blank_lines, row.lines, row.lines)
    is RadicleWriteRow.Spaces -> Strings.plural(R.plurals.radicle_prompt_write_spaces, row.count, row.count)
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
 * what it asks, the repository ID when there is one, Cancel / Allow. A
 * write's prompt (#544) shows everything it publishes ([radicleWriteLines])
 * and the "always allow" switch, answered with the button it goes with.
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
                    // What's published scrolls in a box of its own (a comment
                    // can be 64 KB), laid out lazily (it can be 65,000 lines),
                    // so the warning and the switch stay close by. Whether
                    // there's more than the box shows is said above it, where
                    // the first view always has it, not below it.
                    val content = remember(write) { radicleWriteLines(write) }
                    val list = remember(request) { LazyListState() }
                    val more by remember(list) { derivedStateOf { list.canScrollForward || list.canScrollBackward } }
                    if (more) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.radicle_prompt_write_scroll),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.testTag("radicle-write-more"),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(
                        state = list,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = WRITE_CONTENT_MAX_HEIGHT)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .testTag("radicle-write-content"),
                    ) {
                        content.forEach { line ->
                            item {
                                Text(
                                    line.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                            items(line.rows) { row -> WriteRow(row, line) }
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

/** One [RadicleWriteRow] of a write prompt: a marker (not text a page can write) set apart in italics and colour. */
@Composable
private fun WriteRow(row: RadicleWriteRow, line: RadicleWriteLine) {
    val text = rowText(row)
    if (row !is RadicleWriteRow.Text) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier
                .padding(vertical = 2.dp)
                .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp),
        )
        return
    }
    Text(
        text,
        fontFamily = if (line.code) FontFamily.Monospace else null,
        style = if (line.code) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
        modifier = if (line.boxed) {
            Modifier
                .padding(vertical = 2.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        } else {
            Modifier
        },
    )
}
