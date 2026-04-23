package baby.freedom.mobile.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

    FullScreenScaffold(
        title = "History",
        onDismiss = onDismiss,
    ) {
        if (entries.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.History,
                title = "No history yet",
                hint = "Pages you visit will show up here.",
            )
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

    FullScreenScaffold(
        title = "Bookmarks",
        onDismiss = onDismiss,
    ) {
        if (entries.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.BookmarkBorder,
                title = "No bookmarks yet",
                hint = "Tap the star in the menu while on a page to save it.",
            )
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

@Composable
private fun EntryRow(
    title: String,
    subtitle: String,
    timestamp: String?,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    PageRow(
        title = title,
        subtitle = subtitle,
        thirdLine = timestamp,
        onClick = onClick,
        trailing = {
            IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Remove",
                    modifier = Modifier.size(18.dp),
                )
            }
        },
    )
}

@Composable
private fun EmptyState(
    icon: ImageVector,
    title: String,
    hint: String,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        contentAlignment = BiasAlignment(0f, -0.25f),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(112.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(52.dp),
                )
            }
            Spacer(Modifier.height(20.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                hint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
