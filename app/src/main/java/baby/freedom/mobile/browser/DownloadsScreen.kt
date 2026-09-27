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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import baby.freedom.mobile.data.DownloadEntry
import baby.freedom.mobile.data.DownloadStatus
import java.text.DateFormat
import java.util.Date

/**
 * Download history (#79): every download, newest first, with live
 * progress and Cancel while running, tap-to-open once complete, and
 * Retry for ones that failed or were cancelled. × on a finished row
 * only forgets the entry — the file stays in Downloads.
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
                        onCancel = { downloads.cancel(entry.id) },
                        onRetry = { downloads.retry(entry) },
                        onRemove = { downloads.remove(entry.id) },
                    )
                }
            }
        }
    }
}

/** The status line under a download's name. */
internal fun downloadStatusLine(entry: DownloadEntry, live: DownloadProgress?, timestamp: String): String =
    when (entry.status) {
        DownloadStatus.RUNNING -> {
            val received = live?.received ?: 0
            val total = live?.total?.takeIf { it > 0 } ?: entry.totalBytes.takeIf { it > 0 }
            when {
                live == null -> "Starting…"
                total != null -> "${formatBytes(received)} of ${formatBytes(total)}"
                else -> "${formatBytes(received)} downloaded"
            }
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
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
) {
    val running = entry.status == DownloadStatus.RUNNING
    val completed = entry.status == DownloadStatus.COMPLETED
    val failed = entry.status == DownloadStatus.FAILED
    val canRetry = !running && !completed && entry.sourceUrl.isNotBlank()
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
        if (running) {
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
