package baby.freedom.mobile.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cookie
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import baby.freedom.mobile.R
import baby.freedom.mobile.data.BrowsingRepository

/**
 * Full-screen settings page. Two sections, top to bottom:
 *
 *  1. **Browsing data** — wipe history, bookmarks, and WebView cookies /
 *     site storage / per-tab caches. Each action is guarded by a
 *     confirmation dialog.
 *  2. **About** — app name, version, package, and a short blurb.
 *
 * The Swarm node has its own dedicated page ([NodeScreen]) reachable
 * from the top-bar menu; it isn't duplicated here.
 */
@Composable
fun SettingsScreen(
    repo: BrowsingRepository,
    onClearWebViewData: () -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)

    val history by remember { repo.history }.collectAsState(initial = emptyList())
    val bookmarks by remember { repo.bookmarks }.collectAsState(initial = emptyList())

    var confirmClearHistory by remember { mutableStateOf(false) }
    var confirmClearBookmarks by remember { mutableStateOf(false) }
    var confirmClearSiteData by remember { mutableStateOf(false) }

    FullScreenScaffold(
        title = "Settings",
        onDismiss = onDismiss,
    ) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item("browsing") {
                BrowsingDataSection(
                    historyCount = history.size,
                    bookmarkCount = bookmarks.size,
                    onClearHistoryRequested = { confirmClearHistory = true },
                    onClearBookmarksRequested = { confirmClearBookmarks = true },
                    onClearSiteDataRequested = { confirmClearSiteData = true },
                )
            }
            item("about") {
                AboutSection()
            }
        }
    }

    if (confirmClearHistory) {
        ConfirmDialog(
            title = "Clear history?",
            message = "Removes every entry from the browsing history on this device.",
            confirmLabel = "Clear history",
            onConfirm = {
                repo.clearHistory()
                confirmClearHistory = false
            },
            onDismiss = { confirmClearHistory = false },
        )
    }
    if (confirmClearBookmarks) {
        ConfirmDialog(
            title = "Clear bookmarks?",
            message = "Removes every saved bookmark on this device.",
            confirmLabel = "Clear bookmarks",
            onConfirm = {
                repo.clearBookmarks()
                confirmClearBookmarks = false
            },
            onDismiss = { confirmClearBookmarks = false },
        )
    }
    if (confirmClearSiteData) {
        ConfirmDialog(
            title = "Clear cookies and site data?",
            message = "Signs you out of most sites and wipes cached page data, cookies, and form autofill from every open tab.",
            confirmLabel = "Clear site data",
            onConfirm = {
                onClearWebViewData()
                confirmClearSiteData = false
            },
            onDismiss = { confirmClearSiteData = false },
        )
    }
}

@Composable
private fun BrowsingDataSection(
    historyCount: Int,
    bookmarkCount: Int,
    onClearHistoryRequested: () -> Unit,
    onClearBookmarksRequested: () -> Unit,
    onClearSiteDataRequested: () -> Unit,
) {
    SectionCard(title = "Browsing data") {
        ActionRow(
            icon = Icons.Filled.History,
            title = "Clear history",
            subtitle = if (historyCount == 0) "Nothing to clear"
            else "$historyCount visit${if (historyCount == 1) "" else "s"}",
            enabled = historyCount > 0,
            onClick = onClearHistoryRequested,
        )
        ActionRow(
            icon = Icons.Filled.Star,
            title = "Clear bookmarks",
            subtitle = if (bookmarkCount == 0) "Nothing to clear"
            else "$bookmarkCount bookmark${if (bookmarkCount == 1) "" else "s"}",
            enabled = bookmarkCount > 0,
            onClick = onClearBookmarksRequested,
        )
        ActionRow(
            icon = Icons.Filled.Cookie,
            title = "Clear cookies & site data",
            subtitle = "Cookies, DOM storage, cache, and form data",
            enabled = true,
            onClick = onClearSiteDataRequested,
        )
    }
}

@Composable
private fun AboutSection() {
    val context = LocalContext.current
    val info = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
    }
    val versionName = info?.versionName ?: "unknown"
    @Suppress("DEPRECATION")
    val versionCode = info?.let {
        if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode
        else it.versionCode.toLong()
    } ?: 0L

    SectionCard(title = "About") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_swarm),
                contentDescription = null,
                tint = Color(0xFFF7931A),
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Freedom", fontWeight = FontWeight.SemiBold)
                Text(
                    "Swarm-native browser for Android",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        DetailRow("Version", "$versionName (build $versionCode)")
        DetailRow("Package", context.packageName, mono = true)
        Spacer(Modifier.height(8.dp))
        Text(
            "Loads regular https:// sites plus decentralised content via bzz:// hashes and ens:// names, served through an embedded bee-lite node.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    PageRow(
        title = title,
        subtitle = subtitle,
        style = PageRowStyle.Inset,
        leadingIcon = icon,
        enabled = enabled,
        onClick = onClick,
        trailing = if (enabled) {
            {
                Icon(
                    Icons.Filled.DeleteForever,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else null,
    )
}

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    confirmLabel,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
