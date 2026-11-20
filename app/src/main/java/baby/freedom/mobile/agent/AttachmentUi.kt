package baby.freedom.mobile.agent

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import baby.freedom.mobile.R
import baby.freedom.mobile.browser.isOwnProviderAuthority
import baby.freedom.mobile.browser.isUploadableUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * The chat's attachments on screen (#601): the composer's attach button,
 * the picked files under it, and the files a sent message carried.
 */

/**
 * The composer's attach button. Android's own picker (any type: what the
 * agent reads is decided by the file's name and bytes, as on desktop);
 * each pick is copied into the app at once ([AgentAttachments.stage]),
 * so no grant on it is kept. [onPicked] gets the picks, in order, as
 * Android handed them over; [onStaged] each one's outcome.
 */
@Composable
internal fun AttachButton(
    enabled: Boolean,
    onPicked: (List<Uri>) -> Unit,
) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) onPicked(uris)
    }
    IconButton(onClick = { launcher.launch(arrayOf("*/*")) }, enabled = enabled) {
        Icon(Icons.Outlined.AttachFile, contentDescription = stringResource(R.string.agent_attach))
    }
}

/**
 * Copies the picked [uris] into staging, one after another, off the main
 * thread. A URI served by the browser's own providers is refused, as for
 * a page's upload (#148): a picker app could otherwise hand back the
 * browser's own private files.
 */
internal suspend fun stagePicks(context: Context, store: AgentAttachments, uris: List<Uri>): List<StageResult> {
    val app = context.applicationContext
    val untitled = app.getString(R.string.agent_attachment_untitled)
    // A provider's query can be slow (a cloud file): never on the main thread.
    return withContext(Dispatchers.IO) {
        uris.map { uri ->
            val name = describe(app, uri)?.takeIf { it.isNotBlank() } ?: untitled
            if (!isUploadableUri(uri.scheme, uri.authority) { isOwnProviderAuthority(app, it) }) {
                return@map StageResult.Refused(StageRefusal.Unreadable, AttachmentRules.cleanName(name))
            }
            val type = runCatching { app.contentResolver.getType(uri) }.getOrNull()
            store.stage(name, type) { app.contentResolver.openInputStream(uri) }
        }
    }
}

/** The picker's name for [uri], trusted no further than showing and naming it (the size is counted while copying). */
private fun describe(context: Context, uri: Uri): String? = try {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (!c.moveToFirst()) return@use null
        c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { c.getString(it) }
    }
} catch (e: Exception) {
    null
}

private fun iconOf(kind: AttachmentKind): ImageVector = when (kind) {
    AttachmentKind.Text -> Icons.Outlined.Description
    AttachmentKind.Image -> Icons.Outlined.Image
    AttachmentKind.Pdf -> Icons.Outlined.PictureAsPdf
}

@Composable
private fun kindLabel(kind: AttachmentKind): String = stringResource(
    when (kind) {
        AttachmentKind.Text -> R.string.agent_attachment_kind_text
        AttachmentKind.Image -> R.string.agent_attachment_kind_image
        AttachmentKind.Pdf -> R.string.agent_attachment_kind_pdf
    },
)

/** One file: its kind's icon, its whole name, and its kind and size on a line under it. */
@Composable
private fun FileLine(name: String, kind: AttachmentKind, bytes: Long, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(iconOf(kind), contentDescription = null, modifier = Modifier.padding(end = 8.dp).size(20.dp))
        Column {
            // The whole name: a long one wraps rather than losing its end.
            Text(name, style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.agent_attachment_size, kindLabel(kind), Formatter.formatShortFileSize(context, bytes)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The files picked for the message being written, each with its ×. */
@Composable
internal fun StagedAttachments(staged: List<StagedAttachment>, onRemove: (String) -> Unit) {
    if (staged.isEmpty()) return
    FlowRow(
        Modifier.fillMaxWidth().padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (s in staged) {
            Row(
                Modifier
                    .widthIn(max = 320.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                    .padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FileLine(s.name, s.kind, s.bytes, Modifier.weight(1f, fill = false).padding(vertical = 6.dp))
                // 48 dp, the whole × target.
                IconButton(onClick = { onRemove(s.id) }) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.agent_attachment_remove, s.name))
                }
            }
        }
    }
}

/** Why the last picks weren't all attached, one line each. */
@Composable
internal fun StageRefusals(refused: List<StageResult.Refused>) {
    if (refused.isEmpty()) return
    Column(Modifier.padding(vertical = 4.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
        for (r in refused.distinct()) {
            Text(
                refusalText(r),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun refusalText(r: StageResult.Refused): String = when (r.reason) {
    StageRefusal.TooMany -> stringResource(R.string.agent_attachment_too_many)
    StageRefusal.TooLarge -> stringResource(R.string.agent_attachment_too_large, r.name)
    StageRefusal.TotalTooLarge -> stringResource(R.string.agent_attachment_total_too_large, r.name)
    StageRefusal.Unsupported -> stringResource(R.string.agent_attachment_unsupported, r.name)
    StageRefusal.NotAnImage -> stringResource(R.string.agent_attachment_not_image, r.name)
    StageRefusal.ImageTooLarge -> stringResource(R.string.agent_attachment_image_too_large, r.name)
    StageRefusal.ImageTooWide -> stringResource(R.string.agent_attachment_image_too_wide, r.name)
    StageRefusal.NotPutBack -> stringResource(R.string.agent_attachment_not_put_back, r.name)
    StageRefusal.NotMovedBack -> stringResource(R.string.agent_attachment_not_moved_back, r.name)
    StageRefusal.Unreadable -> stringResource(R.string.agent_attachment_unreadable, r.name)
}

/**
 * The note under staged files: where they go, and, when the picked model
 * can't see images and an image is among them, that it gets only names.
 */
@Composable
internal fun StagedNote(staged: List<StagedAttachment>, modelTakesImages: Boolean) {
    if (staged.isEmpty()) return
    if (!modelTakesImages && staged.any { it.kind == AttachmentKind.Image }) {
        Text(
            stringResource(R.string.agent_attachment_no_vision),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    Text(
        stringResource(R.string.agent_attachment_where),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

/** The files a sent message carried, under it, on the end side with it. */
@Composable
internal fun TurnAttachments(json: String?) {
    val list = Attachment.parseList(json)
    if (list.isEmpty()) return
    Column(Modifier.padding(top = 4.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (a in list) {
            FileLine(
                a.name, a.kind, a.bytes,
                Modifier
                    .widthIn(max = 560.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}
