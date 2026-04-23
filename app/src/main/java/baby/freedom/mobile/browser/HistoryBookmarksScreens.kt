package baby.freedom.mobile.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import baby.freedom.mobile.data.BrowsingRepository
import java.text.DateFormat
import java.util.Date

/**
 * Full-screen list of recent history entries. Tapping a row calls
 * [onOpen] with the canonical URL; the host closes the screen and
 * submits the URL into the active tab.
 */
@Composable
fun HistoryScreen(
    repo: BrowsingRepository,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
) {
    BackHandler(onBack = onDismiss)

    val entries by remember { repo.history }.collectAsState(initial = emptyList())
    val dateFormat = remember {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    }

    FullScreenListScaffold(
        title = "History",
        subtitle = "${entries.size} visit${if (entries.size == 1) "" else "s"}",
        onDismiss = onDismiss,
        trailing = {
            if (entries.isNotEmpty()) {
                IconButton(onClick = { repo.clearHistory() }) {
                    Icon(
                        Icons.Filled.DeleteForever,
                        contentDescription = "Clear history",
                    )
                }
            }
        },
    ) {
        if (entries.isEmpty()) {
            EmptyState("No history yet")
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(items = entries, key = { it.id }) { entry ->
                    EntryRow(
                        title = entry.title.ifBlank { entry.url },
                        subtitle = entry.url,
                        timestamp = dateFormat.format(Date(entry.visitedAt)),
                        onClick = { onOpen(entry.url) },
                        onRemove = { repo.deleteHistory(entry.id) },
                    )
                }
            }
        }
    }
}

/**
 * Full-screen list of saved bookmarks. Tapping a row calls [onOpen]
 * with the canonical URL; the host closes the screen and submits the
 * URL into the active tab.
 */
@Composable
fun BookmarksScreen(
    repo: BrowsingRepository,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
) {
    BackHandler(onBack = onDismiss)

    val entries by remember { repo.bookmarks }.collectAsState(initial = emptyList())

    FullScreenListScaffold(
        title = "Bookmarks",
        subtitle = "${entries.size} bookmark${if (entries.size == 1) "" else "s"}",
        onDismiss = onDismiss,
    ) {
        if (entries.isEmpty()) {
            EmptyState("No bookmarks yet — tap the star icon while on a page to save it")
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(items = entries, key = { it.id }) { entry ->
                    EntryRow(
                        title = entry.title.ifBlank { entry.url },
                        subtitle = entry.url,
                        timestamp = null,
                        onClick = { onOpen(entry.url) },
                        onRemove = { repo.unbookmark(entry.url) },
                    )
                }
            }
        }
    }
}

/**
 * Shared chrome for the History and Bookmarks full-screen pages:
 * a background-coloured root, system-bar insets, a title/subtitle bar
 * with a back button (and optional [trailing] slot), and a content
 * area that fills the remaining space.
 */
@Composable
private fun FullScreenListScaffold(
    title: String,
    subtitle: String,
    onDismiss: () -> Unit,
    trailing: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            trailing()
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = "Close")
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            content()
        }
    }
}

@Composable
private fun EntryRow(
    title: String,
    subtitle: String,
    timestamp: String?,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (timestamp != null) {
                Text(
                    timestamp,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
        Spacer(Modifier.size(8.dp))
        IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Remove",
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun EmptyState(text: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 48.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Text(
            text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
