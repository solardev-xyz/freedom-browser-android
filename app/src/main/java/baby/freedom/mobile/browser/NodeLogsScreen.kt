package baby.freedom.mobile.browser

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import baby.freedom.mobile.node.NodeLogSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The embedded nodes' recent log lines (#276): what each node's own
 * process keeps in memory ([baby.freedom.mobile.node.NodeLogs]), re-read
 * every couple of seconds while the page is open, one node at a time, and
 * Share after a warning. Opened from a node's card at that node.
 *
 * [read] asks the node's process for its lines; null when the node itself
 * is off — its process may be gone, or (Swarm, IPFS and Radicle share
 * `:node`) still running another node, in which case earlier lines of this
 * one aren't shown. [externalTor]: Settings → Tor uses an external
 * proxy (#275), so no Tor client runs here and Tor's logs are that app's.
 */
@Composable
fun NodeLogsScreen(
    initial: NodeLogSource,
    read: (NodeLogSource) -> String?,
    onDismiss: () -> Unit,
    externalTor: Boolean = false,
) {
    BackHandler(onBack = onDismiss)
    val context = LocalContext.current
    var source by rememberSaveable { mutableStateOf(initial) }
    var confirmShare by rememberSaveable { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Null until first read; then the lines, or null lines while the node's process isn't running.
    // Only while the app is on screen: in the background the page would go on
    // asking the node's process for up to ~190 KB every couple of seconds (R1-M2).
    val logs by produceState<NodeLogRead?>(null, source, lifecycle) {
        value = null
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val text = withContext(Dispatchers.IO) { read(source) }
                value = NodeLogRead(text?.let(::nodeLogLines))
                delay(REFRESH_MS)
            }
        }
    }
    // Settings → Tor uses an external client: whatever the embedded one
    // logged before the switch isn't what's routing now; nothing to share.
    val external = source == NodeLogSource.Tor && externalTor
    val lines = if (external) emptyList() else logs?.lines.orEmpty()

    FullScreenScaffold(
        title = "Node logs",
        onDismiss = onDismiss,
        trailing = {
            IconButton(onClick = { confirmShare = true }, enabled = lines.isNotEmpty()) {
                Icon(Icons.Filled.Share, contentDescription = "Share ${source.title} logs")
            }
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                for (s in NodeLogSource.entries) {
                    FilterChip(
                        selected = s == source,
                        onClick = { source = s },
                        label = { Text(s.title) },
                    )
                }
            }
            val current = logs
            when {
                external -> LogNote(EXTERNAL_TOR_NOTE)
                current == null -> LogNote("Reading…")
                current.lines == null -> LogNote(notRunningNote(source))
                current.lines.isEmpty() -> LogNote("No log lines yet.")
                else -> LogLines(source, current.lines)
            }
        }
    }

    if (confirmShare) {
        AlertDialog(
            onDismissRequest = { confirmShare = false },
            title = { Text("Share ${source.title} logs?") },
            text = { Text(SHARE_WARNING) },
            confirmButton = {
                TextButton(onClick = {
                    confirmShare = false
                    shareNodeLogs(context, source, lines)
                }) { Text("Share") }
            },
            dismissButton = {
                TextButton(onClick = { confirmShare = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun LogNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp),
    )
}

@Composable
private fun LogLines(source: NodeLogSource, lines: List<String>) {
    val listState = rememberLazyListState()
    // Open at the newest line, and keep following new ones while the
    // reader is at the end; leave them be once they've scrolled back.
    var followed by remember(source) { mutableStateOf(false) }
    LaunchedEffect(source, lines.size, lines.lastOrNull()) {
        val info = listState.layoutInfo
        val atEnd = info.visibleItemsInfo.lastOrNull()?.index?.let { it >= info.totalItemsCount - 2 } ?: true
        if (!followed || atEnd) {
            listState.scrollToItem(lines.lastIndex)
            followed = true
        }
    }
    SelectionContainer {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(lines) { _, line ->
                Text(
                    line,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** A node card's way into its logs. */
@Composable
internal fun LogsButton(onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text("Logs") }
}

/** The lines of a node's log text, oldest first. */
internal fun nodeLogLines(text: String): List<String> =
    if (text.isEmpty()) emptyList() else text.split('\n')

internal fun notRunningNote(source: NodeLogSource): String =
    "${source.title} isn't running, so there are no logs to show. They're shown only while it runs, " +
        "and kept in memory only, never written to storage."

internal const val EXTERNAL_TOR_NOTE =
    "Tor runs in an external app (Orbot, say), set in Settings → Tor. Its logs are in that app."

internal const val SHARE_WARNING =
    "Logs can contain this device's node addresses and peer IDs, the addresses of the peers and " +
        "servers the node talked to, and the IDs of Radicle repositories. The addresses and names of " +
        "the pages you visited are taken out before a line is kept. Share them only with someone you trust."

/** The shared text: which app, node and time, then the lines. */
internal fun nodeLogShareText(version: String, source: NodeLogSource, at: Date, lines: List<String>): String {
    val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(at)
    return "Freedom $version · ${source.title} logs · $stamp\n\n" + lines.joinToString("\n")
}

private fun shareNodeLogs(context: Context, source: NodeLogSource, lines: List<String>) {
    val version = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "unknown"
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Freedom ${source.title} logs")
        putExtra(Intent.EXTRA_TEXT, nodeLogShareText(version, source, Date(), lines))
    }
    // No share target at all would throw; losing the share beats a crash.
    runCatching { context.startActivity(Intent.createChooser(send, null)) }
}

/** One read of a node's log: its lines, or null when its process isn't running. */
private class NodeLogRead(val lines: List<String>?)

private const val REFRESH_MS = 2_000L
