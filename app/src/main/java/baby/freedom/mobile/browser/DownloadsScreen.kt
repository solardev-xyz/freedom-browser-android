package baby.freedom.mobile.browser

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.material.icons.filled.SearchOff
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import baby.freedom.mobile.R
import baby.freedom.mobile.data.DownloadEntry
import baby.freedom.mobile.data.DownloadStatus
import baby.freedom.mobile.data.NodeSettings
import baby.freedom.mobile.l10n.Strings
import baby.freedom.mobile.l10n.pluralText
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/**
 * Whether [entry] matches the Downloads search [query] (#322): its file
 * name or where it came from (the address the list shows) contains it,
 * ignoring case — desktop's `freedom://downloads` filter. A blank query
 * matches everything.
 */
internal fun downloadMatches(entry: DownloadEntry, query: String): Boolean {
    val q = query.trim()
    return q.isEmpty() || entry.fileName.contains(q, ignoreCase = true) ||
        entry.displayUrl.contains(q, ignoreCase = true)
}

/**
 * Download history (#79): every download, newest first, with live
 * progress and Cancel while running, tap-to-open once complete, and
 * Retry for ones that failed or were cancelled. × on a finished row
 * only forgets the entry — the file stays in Downloads. A running
 * download that can be paused has Pause, and a paused one Resume
 * (#265); × on either cancels it and deletes its partial file.
 *
 * A search field under the title filters the list by file name and
 * source address (#322), with History's no-match state; Back clears it
 * first. With *Ask where to save each file* on, Retry opens the Save as
 * picker the way the download prompt does.
 */
@Composable
fun DownloadsScreen(
    downloads: DownloadManager,
    onDismiss: () -> Unit,
    onOpen: (DownloadEntry) -> Unit,
) {
    BackHandler(onBack = onDismiss)
    // Registered after the dismiss handler so it wins while there's a
    // query: Back clears the search first, as on History.
    var query by rememberSaveable(saver = LibraryQuerySaver) { mutableStateOf("") }
    BackHandler(enabled = query.isNotEmpty()) { query = "" }

    val context = LocalContext.current
    val askWhereToSave by remember(context) { NodeSettings.get(context).askWhereToSave }
        .collectAsState(initial = false)
    // The row a Retry is picking a location for, by id: in saved state,
    // so an activity rebuilt while the picker is up still retries it.
    var retrying by rememberSaveable { mutableStateOf<Long?>(null) }
    val entries by remember { downloads.downloads }.collectAsState(initial = emptyList())
    val saveAsPicker = rememberLauncherForActivityResult(SaveAsContract()) { uri ->
        val id = retrying ?: return@rememberLauncherForActivityResult
        retrying = null
        if (uri == null) return@rememberLauncherForActivityResult
        val entry = entries.firstOrNull { it.id == id }
        if (entry != null) downloads.retry(entry, uri) else downloads.abandonSaveTo(uri)
    }
    val retry = { entry: DownloadEntry ->
        val picking = asksWhereToSave(askWhereToSave, private = entry.id < 0) && try {
            saveAsPicker.launch(SaveAsRequest(entry.fileName, entry.mimeType))
            retrying = entry.id
            true
        } catch (_: android.content.ActivityNotFoundException) {
            false
        }
        if (!picking) downloads.retry(entry)
    }
    val shown = remember(entries, query) { entries.filter { downloadMatches(it, query) } }
    val progress by downloads.progress.collectAsState()
    val dateFormat = remember {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    }

    FullScreenScaffold(
        title = stringResource(R.string.library_downloads_title),
        onDismiss = onDismiss,
    ) {
        if (entries.isEmpty() && query.isBlank()) {
            EmptyState(
                icon = Icons.Outlined.Download,
                title = stringResource(R.string.library_downloads_empty_title),
                hint = stringResource(R.string.library_downloads_empty_hint),
            )
        } else Column(modifier = Modifier.fillMaxSize()) {
            LibrarySearchField(
                query = query,
                onQueryChange = { query = it },
                placeholder = stringResource(R.string.library_downloads_search_placeholder),
            )
            if (shown.isEmpty()) {
                EmptyState(
                    icon = Icons.Filled.SearchOff,
                    title = stringResource(R.string.library_downloads_no_matches_title),
                    hint = stringResource(R.string.library_downloads_no_matches_hint),
                )
            } else LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(items = shown, key = { it.id }) { entry ->
                    DownloadRow(
                        entry = entry,
                        live = progress[entry.id],
                        timestamp = dateFormat.format(Date(entry.finishedAt ?: entry.startedAt)),
                        onOpen = { onOpen(entry) },
                        canPause = downloads.canPause(entry),
                        onPause = { downloads.pause(entry.id) },
                        onResume = { downloads.resume(entry.id) },
                        onCancel = { downloads.cancel(entry.id) },
                        onRetry = { retry(entry) },
                        onRemove = { downloads.remove(entry.id) },
                    )
                }
            }
        }
    }
}

/** "12.0 MB of 40.0 MB", or "12.0 MB downloaded" with no known total. */
private fun downloadBytesLine(received: Long, total: Long?): String =
    if (total != null) {
        Strings.get(R.string.library_download_bytes_of_total, formatBytes(received), formatBytes(total))
    } else {
        Strings.get(R.string.library_download_bytes_downloaded, formatBytes(received))
    }

/** The status line under a download's name. */
internal fun downloadStatusLine(entry: DownloadEntry, live: DownloadProgress?, timestamp: String): String =
    when (entry.status) {
        DownloadStatus.RUNNING -> {
            val received = live?.received ?: 0
            val total = live?.total?.takeIf { it > 0 } ?: entry.totalBytes.takeIf { it > 0 }
            val line = when {
                live == null -> Strings.get(R.string.library_download_status_starting)
                live.saving -> Strings.get(
                    if (entry.saveTo != null) R.string.library_download_status_saving_picked
                    else R.string.library_download_status_saving,
                )
                else -> downloadBytesLine(received, total)
            }
            // "Restarted from the beginning: …" after a resume that
            // couldn't pick up where it left off.
            DownloadNote.shown(entry.note)?.let { Strings.get(R.string.library_download_status_with_note, line, it) } ?: line
        }
        DownloadStatus.PAUSED -> {
            // "Paused: connection lost": lowering the note's first letter
            // is English's rule, not every language's (#313 R1-M5).
            val why = DownloadNote.shown(entry.note)?.let {
                val note = if (Strings.language().language == "en") it.replaceFirstChar(Char::lowercaseChar) else it
                Strings.get(R.string.library_download_status_paused_reason, note)
            } ?: Strings.get(R.string.library_download_status_paused)
            val bytes = downloadBytesLine(entry.receivedBytes, entry.totalBytes.takeIf { it > 0 })
            // No validator to check a range against: Resume starts over.
            val whole = entry.totalBytes > 0 && entry.receivedBytes == entry.totalBytes
            if (entry.validator == null && !whole) {
                Strings.get(R.string.library_download_status_paused_line_restarts, why, bytes)
            } else {
                Strings.get(R.string.library_download_status_paused_line, why, bytes)
            }
        }
        DownloadStatus.COMPLETED ->
            Strings.get(R.string.library_download_status_completed, formatBytes(entry.receivedBytes), timestamp)
        DownloadStatus.CANCELLED -> Strings.get(R.string.library_download_status_cancelled, timestamp)
        else -> Strings.get(
            R.string.library_download_status_failed,
            DownloadNote.shown(entry.error) ?: Strings.get(R.string.library_download_unknown_error),
            timestamp,
        )
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
            RowAction(Icons.Filled.Refresh, stringResource(R.string.common_retry), onRetry)
        }
        if (running && canPause) {
            RowAction(Icons.Filled.Pause, stringResource(R.string.library_download_pause), onPause)
        }
        if (paused) {
            RowAction(Icons.Filled.PlayArrow, stringResource(R.string.library_download_resume), onResume)
        }
        if (running || paused) {
            RowAction(Icons.Filled.Close, stringResource(R.string.library_download_cancel), onCancel)
        } else {
            RowAction(Icons.Filled.Close, stringResource(R.string.library_download_remove_from_list), onRemove)
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
    if (totalBytes > 0) formatBytes(totalBytes) else Strings.get(R.string.library_download_size_unknown)

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
    Strings.plural(R.plurals.library_download_offer_dropped, dropped, dropped, MAX_PENDING_OFFERS)

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
        title = { Text(stringResource(R.string.library_download_offer_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(offer.fileName, fontWeight = FontWeight.SemiBold)
                Text(downloadOfferSizeLine(offer.totalBytes))
                Text(
                    stringResource(R.string.library_download_offer_from, offer.source),
                    color = secondary,
                    style = MaterialTheme.typography.bodySmall,
                )
                offer.requestedBy?.let { page ->
                    Text(
                        stringResource(R.string.library_download_offer_requested_by, page),
                        color = secondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        stringResource(R.string.library_download_offer_cancel_blocks),
                        color = secondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (othersWaiting > 0) {
                    Text(
                        pluralText(R.plurals.library_download_offer_more_waiting, othersWaiting, othersWaiting),
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
            TextButton(onClick = onAccept, enabled = armed) { Text(stringResource(R.string.library_download_offer_download)) }
        },
        dismissButton = {
            Row {
                if (othersWaiting > 0) {
                    TextButton(onClick = onDeclineAll) { Text(stringResource(R.string.library_download_offer_cancel_all)) }
                }
                TextButton(onClick = onDecline) { Text(stringResource(R.string.common_cancel)) }
            }
        },
    )
}
