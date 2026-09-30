package baby.freedom.mobile.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import baby.freedom.mobile.data.DownloadEntry
import baby.freedom.mobile.data.DownloadStatus
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/**
 * Download history (#79): every download, newest first, with live
 * progress and Cancel while running, tap-to-open once complete, and
 * Retry for ones that failed or were cancelled. × on a finished row
 * only forgets the entry — the file stays in Downloads. A running
 * download that can be paused has Pause, and a paused one Resume
 * (#265); × on either cancels it and deletes its partial file.
 */
@Composable
fun DownloadsScreen(
    downloads: DownloadManager,
    onDismiss: () -> Unit,
    onOpen: (DownloadEntry) -> Unit,
) {
    BackHandler(onBack = onDismiss)

    val entries by remember { downloads.downloads }.collectAsState(initial = emptyList())
    val progress by downloads.progress.collectAsState()
    val dateFormat = remember {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    }

    FullScreenScaffold(
        title = "Downloads",
        onDismiss = onDismiss,
    ) {
        if (entries.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.Download,
                title = "No downloads yet",
                hint = "Files you download from web and dweb pages will show up here.",
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(items = entries, key = { it.id }) { entry ->
                    DownloadRow(
                        entry = entry,
                        live = progress[entry.id],
                        timestamp = dateFormat.format(Date(entry.finishedAt ?: entry.startedAt)),
                        onOpen = { onOpen(entry) },
                        canPause = downloads.canPause(entry),
                        onPause = { downloads.pause(entry.id) },
                        onResume = { downloads.resume(entry.id) },
                        onCancel = { downloads.cancel(entry.id) },
                        onRetry = { downloads.retry(entry) },
                        onRemove = { downloads.remove(entry.id) },
                    )
                }
            }
        }
    }
}

/** "12.0 MB of 40.0 MB", or "12.0 MB downloaded" with no known total. */
private fun downloadBytesLine(received: Long, total: Long?): String =
    if (total != null) "${formatBytes(received)} of ${formatBytes(total)}" else "${formatBytes(received)} downloaded"

/** The status line under a download's name. */
internal fun downloadStatusLine(entry: DownloadEntry, live: DownloadProgress?, timestamp: String): String =
    when (entry.status) {
        DownloadStatus.RUNNING -> {
            val received = live?.received ?: 0
            val total = live?.total?.takeIf { it > 0 } ?: entry.totalBytes.takeIf { it > 0 }
            val line = when {
                live == null -> "Starting…"
                live.saving -> "Saving to Downloads…"
                else -> downloadBytesLine(received, total)
            }
            // "Restarted from the beginning: …" after a resume that
            // couldn't pick up where it left off.
            entry.note?.let { "$line · $it" } ?: line
        }
        DownloadStatus.PAUSED -> {
            val why = entry.note?.let { "Paused: ${it.replaceFirstChar(Char::lowercaseChar)}" } ?: "Paused"
            val bytes = downloadBytesLine(entry.receivedBytes, entry.totalBytes.takeIf { it > 0 })
            // No validator to check a range against: Resume starts over.
            val whole = entry.totalBytes > 0 && entry.receivedBytes == entry.totalBytes
            if (entry.validator == null && !whole) "$why · $bytes · resuming starts over" else "$why · $bytes"
        }
        DownloadStatus.COMPLETED -> "${formatBytes(entry.receivedBytes)} · $timestamp"
        DownloadStatus.CANCELLED -> "Cancelled · $timestamp"
        else -> "Failed: ${entry.error ?: "unknown error"} · $timestamp"
    }

@Composable
private fun DownloadRow(
    entry: DownloadEntry,
    live: DownloadProgress?,
    timestamp: String,
    onOpen: () -> Unit,
    canPause: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
) {
    val running = entry.status == DownloadStatus.RUNNING
    val paused = entry.status == DownloadStatus.PAUSED
    val completed = entry.status == DownloadStatus.COMPLETED
    val failed = entry.status == DownloadStatus.FAILED
    val canRetry = !running && !paused && !completed && entry.sourceUrl.isNotBlank()
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = completed, onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (failed) Icons.Outlined.ErrorOutline else Icons.Outlined.InsertDriveFile,
            contentDescription = null,
            tint = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                entry.fileName,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                entry.displayUrl,
                style = MaterialTheme.typography.bodySmall,
                color = onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (running) {
                val total = live?.total?.takeIf { it > 0 } ?: entry.totalBytes.takeIf { it > 0 }
                val modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                if (live != null && total != null) {
                    LinearProgressIndicator(
                        progress = { (live.received.toFloat() / total).coerceIn(0f, 1f) },
                        modifier = modifier,
                    )
                } else {
                    LinearProgressIndicator(modifier = modifier)
                }
            } else if (paused && entry.totalBytes > 0) {
                LinearProgressIndicator(
                    progress = { (entry.receivedBytes.toFloat() / entry.totalBytes).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                )
            }
            Text(
                downloadStatusLine(entry, live, timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = if (failed) MaterialTheme.colorScheme.error
                else onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
        Spacer(Modifier.width(8.dp))
        if (canRetry) {
            RowAction(Icons.Filled.Refresh, "Retry", onRetry)
        }
        if (running && canPause) {
            RowAction(Icons.Filled.Pause, "Pause download", onPause)
        }
        if (paused) {
            RowAction(Icons.Filled.PlayArrow, "Resume download", onResume)
        }
        if (running || paused) {
            RowAction(Icons.Filled.Close, "Cancel download", onCancel)
        } else {
            RowAction(Icons.Filled.Close, "Remove from list", onRemove)
        }
    }
}

@Composable
private fun RowAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        shapes = IconButtonDefaults.shapes(),
        modifier = Modifier.size(32.dp),
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(18.dp))
    }
}

/** The line under an offered file's name: its size, or that it's unknown. */
internal fun downloadOfferSizeLine(totalBytes: Long): String =
    if (totalBytes > 0) formatBytes(totalBytes) else "Size unknown"

/**
 * How long each offer's Download button stays disabled after it
 * appears — the same input-protection delay Firefox puts on its
 * download and permission dialogs (`security.dialog_enable_delay`,
 * 1 s). A page chooses when the prompt appears, so without it the page
 * could fire a download just before a tap it has coaxed out of the
 * user (a "tap here fast" game with Download under the finger) and have
 * that tap say yes.
 */
internal const val DOWNLOAD_OFFER_ARM_DELAY_MS = 1_000L

/** The prompt's note that a tab asked for more than [MAX_PENDING_OFFERS] at once. */
internal fun downloadOfferDroppedLine(dropped: Int): String =
    (if (dropped == 1) "1 further download from this tab wasn't" else "$dropped further downloads from this tab weren't") +
        " offered: $MAX_PENDING_OFFERS were already waiting."

/**
 * "Download file?" for a download a page asked for (#79): its name,
 * size and source, the page that asked, with Download / Cancel.
 * Dismissing it declines — nothing is saved without an explicit yes —
 * and a no also stops that tab's pages asking again until the user
 * navigates it ([DownloadOffers]). When a page has asked for more, they
 * wait behind this one and can all be declined at once. Download is
 * armed only [DOWNLOAD_OFFER_ARM_DELAY_MS] after each offer appears.
 */
@Composable
internal fun DownloadOfferDialog(
    offer: DownloadOffer,
    othersWaiting: Int,
    /** Further page downloads this tab asked for and couldn't queue ([MAX_PENDING_OFFERS]). */
    dropped: Int,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onDeclineAll: () -> Unit,
) {
    // Keyed on the offer: the next one in line re-arms, so a page
    // can't line a second prompt up under a tap meant for the first.
    var armed by remember(offer.key) { mutableStateOf(false) }
    LaunchedEffect(offer.key) {
        delay(DOWNLOAD_OFFER_ARM_DELAY_MS)
        armed = true
    }
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    AlertDialog(
        onDismissRequest = onDecline,
        title = { Text("Download file?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(offer.fileName, fontWeight = FontWeight.SemiBold)
                Text(downloadOfferSizeLine(offer.totalBytes))
                Text(
                    "From ${offer.source}",
                    color = secondary,
                    style = MaterialTheme.typography.bodySmall,
                )
                offer.requestedBy?.let { page ->
                    Text(
                        "Requested by $page",
                        color = secondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Cancel also blocks further downloads from this tab until you reload it or enter an address.",
                        color = secondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (othersWaiting > 0) {
                    Text(
                        if (othersWaiting == 1) "1 more download waiting" else "$othersWaiting more downloads waiting",
                        color = secondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (dropped > 0) {
                    Text(
                        downloadOfferDroppedLine(dropped),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onAccept, enabled = armed) { Text("Download") }
        },
        dismissButton = {
            Row {
                if (othersWaiting > 0) {
                    TextButton(onClick = onDeclineAll) { Text("Cancel all") }
                }
                TextButton(onClick = onDecline) { Text("Cancel") }
            }
        },
    )
}
