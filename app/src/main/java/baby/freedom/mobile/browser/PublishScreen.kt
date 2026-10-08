package baby.freedom.mobile.browser

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import baby.freedom.mobile.R
import baby.freedom.mobile.l10n.Strings
import baby.freedom.swarm.NodeInfo
import baby.freedom.swarm.NodeStatus
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Why nothing can be published now, or null when it can: uploads go to the embedded light node. */
internal fun publishPageBlockedReason(node: NodeInfo): String? = when {
    node.status == NodeStatus.Starting -> Strings.get(R.string.publish_blocked_node_starting)
    node.status != NodeStatus.Running -> Strings.get(R.string.publish_blocked_node_off)
    !node.lightMode -> Strings.get(R.string.publish_blocked_needs_light_mode)
    else -> null
}

/**
 * What the page says when no usable stamp has room for [stampBytes], or
 * null when one has: none yet, or none big enough. Nothing publishes in
 * less than [MIN_PUBLISH_STAMP_BYTES], so a [stampBytes] of 0 (the page,
 * before anything is picked) asks for at least that: a stamp that's full
 * is warned about up front, not after a file was picked and read.
 */
internal fun noStampText(batches: List<PostageBatch>, stampBytes: Long): String? = when {
    selectPublishBatch(batches, maxOf(stampBytes, MIN_PUBLISH_STAMP_BYTES)) != null -> null
    batches.none { it.usable } -> Strings.get(R.string.publish_no_stamp_none)
    stampBytes <= 0 -> Strings.get(R.string.publish_no_stamp_full)
    else -> Strings.get(R.string.publish_no_stamp_room, formatStampBytes(stampBytes))
}

/** The least any publish stamps: one chunk of content and one of manifest. */
internal val MIN_PUBLISH_STAMP_BYTES: Long = publishStampEstimate(listOf(0L))

/**
 * What of the text being written rides in saved instance state: all of
 * it up to [MAX_SAVED_PUBLISH_TEXT_CHARS], else none (it's lost if the
 * process dies, rather than crashing the app with a bundle past the
 * ~1 MB binder limit, or restoring cut short and publishing half).
 */
internal fun savedPublishText(text: String): String? = text.takeIf { it.length <= MAX_SAVED_PUBLISH_TEXT_CHARS }

/** 64k UTF-16 chars: 128 KiB of the bundle at most. */
internal const val MAX_SAVED_PUBLISH_TEXT_CHARS = 64 * 1024

private val PublishTextSaver = Saver<MutableState<String>, String>(
    save = { savedPublishText(it.value) },
    restore = { mutableStateOf(it) },
)

/** One history row's status line. */
internal fun publishStatusText(r: PublishRecord): String = when (r.status) {
    PublishStatus.Uploading -> Strings.get(R.string.publish_status_uploading)
    PublishStatus.Completed -> Strings.get(R.string.publish_status_published)
    PublishStatus.Failed -> r.error?.let { Strings.get(R.string.publish_status_failed, it) }
        ?: Strings.get(R.string.publish_status_failed_unknown)
}

/**
 * The Publish page (#118), over the node page, like desktop's
 * freedom://publish: publish a file, a folder (a website: its
 * index.html is what the link opens) or some text on Swarm, with the
 * node's own postage stamps, and the history of what was published.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PublishScreen(
    nodeInfo: NodeInfo,
    onOpenStamps: () -> Unit,
    onOpenSetup: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val history = remember(context) { PublishHistory.get(context) }
    val records by history.records.collectAsState()
    val publishing by Publisher.state.collectAsState()
    val spend by StampClient.spend.collectAsState()
    val discovery by StampClient.discovery.collectAsState()
    // A stamp buy or search may restart the gateway as it ends, which would cut an upload off.
    val stampWork = StampClient.mayRestartGateway(spend, discovery)
    val blocked = publishPageBlockedReason(nodeInfo)

    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(publishing) { if (publishing is Publisher.State.Finished) refresh++ }
    val lifecycle = currentLifecycle()
    val batches by produceState<List<PostageBatch>?>(null, blocked == null, refresh, lifecycle) {
        if (blocked != null) return@produceState
        lifecycle.pollWhileStarted(STAMPS_POLL_MS) {
            gatewayGet("/stamps", STAMPS_READ_TIMEOUT_MS)?.let(::stampsFrom)?.let { value = it }
        }
    }

    var writingText by rememberSaveable { mutableStateOf(false) }
    var text by rememberSaveable(saver = PublishTextSaver) { mutableStateOf("") }
    // What was picked, read and waiting for the user's go-ahead.
    var plan by remember { mutableStateOf<PublishPlan?>(null) }
    var reading by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var clearing by remember { mutableStateOf(false) }
    val unreadable = stringResource(R.string.publish_error_source_unreadable)

    fun prepare(source: PublishSource) {
        problem = null
        reading = true
        scope.launch {
            try {
                plan = withContext(Dispatchers.IO) { planPublish(context.contentResolver, source) }
            } catch (e: PublishException) {
                problem = e.message
                releaseGrant(context, source)
            } catch (e: Exception) {
                problem = unreadable
                releaseGrant(context, source)
            } finally {
                reading = false
            }
        }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) prepare(PublishSource.OneFile(uri).also { holdGrant(context, uri) })
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) prepare(PublishSource.Folder(uri).also { holdGrant(context, uri) })
    }

    // A picked source waiting for the go-ahead when the page goes (the
    // activity recreated, the page closed): nothing will publish it.
    DisposableEffect(Unit) {
        onDispose { plan?.let { releaseGrant(context, it.source) } }
    }

    val back: () -> Unit = { if (writingText) writingText = false else onDismiss() }
    BackHandler(onBack = back)

    val running = publishing is Publisher.State.Running
    val canStart = blocked == null && !running && !reading

    FullScreenScaffold(title = stringResource(R.string.publish_title), onDismiss = onDismiss) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            // First, and whatever the node is doing: a publish carries on
            // when the page is left, and its outcome waits here.
            when (val p = publishing) {
                is Publisher.State.Running -> item("running") { RunningCard(p) }
                is Publisher.State.Finished -> records.firstOrNull { it.id == p.recordId }?.let { r ->
                    item("outcome") {
                        OutcomeCard(r, onOpenUrl = onOpenUrl, onDone = Publisher::acknowledge)
                    }
                }
                Publisher.State.Idle -> Unit
            }
            item("publish") {
                SectionCard(title = stringResource(R.string.publish_on_swarm_title)) {
                    Muted(stringResource(R.string.publish_intro))
                    when {
                        blocked != null -> {
                            Spacer(Modifier.height(8.dp))
                            Muted(blocked)
                            TextButton(onClick = onOpenSetup) { Text(stringResource(R.string.publish_set_up)) }
                        }
                        batches == null -> {
                            Spacer(Modifier.height(8.dp))
                            Muted(stringResource(R.string.publish_reading_stamps))
                        }
                        else -> noStampText(batches.orEmpty(), 0L)?.let {
                            Spacer(Modifier.height(8.dp))
                            Muted(it)
                            TextButton(onClick = onOpenStamps) { Text(stringResource(R.string.publish_postage_stamps)) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { pickFile.launch(arrayOf("*/*")) }, enabled = canStart) {
                            IconLabel(Icons.AutoMirrored.Filled.InsertDriveFile, stringResource(R.string.publish_pick_file))
                        }
                        FilledTonalButton(onClick = { pickFolder.launch(null) }, enabled = canStart) {
                            IconLabel(Icons.Filled.Folder, stringResource(R.string.publish_pick_folder))
                        }
                        FilledTonalButton(onClick = { writingText = true }, enabled = canStart && !writingText) {
                            IconLabel(Icons.AutoMirrored.Filled.Notes, stringResource(R.string.publish_pick_text))
                        }
                    }
                    if (reading) {
                        Spacer(Modifier.height(4.dp))
                        Muted(stringResource(R.string.publish_reading_source))
                    }
                    problem?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    if (writingText) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            placeholder = { Text(stringResource(R.string.publish_text_placeholder)) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                        )
                        Row {
                            Button(
                                onClick = { prepare(PublishSource.Text(text)) },
                                enabled = canStart && text.isNotEmpty(),
                            ) { Text(stringResource(R.string.publish_text_button)) }
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = { writingText = false }) { Text(stringResource(R.string.common_cancel)) }
                        }
                    }
                }
            }
            item("history-title") {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.publish_history_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (records.any { it.status != PublishStatus.Uploading }) {
                        TextButton(onClick = { clearing = true }) { Text(stringResource(R.string.publish_clear_all)) }
                    }
                }
            }
            if (records.isEmpty()) {
                item("history-empty") { Muted(stringResource(R.string.publish_history_empty)) }
            } else {
                items(records, key = { it.id }) { r ->
                    HistoryCard(r, onOpenUrl = onOpenUrl, onRemove = {
                        history.remove(r.id)
                        Publisher.forgetRemoved(history.records.value)
                    })
                }
            }
        }
    }

    plan?.let { p ->
        val batch = selectPublishBatch(batches.orEmpty(), p.stampBytes)
        AlertDialog(
            onDismissRequest = {
                plan = null
                releaseGrant(context, p.source)
            },
            title = { Text(stringResource(R.string.publish_confirm_title, p.name)) },
            text = {
                Text(confirmText(p, batch, batches.orEmpty()) + (publishWaitNote(running, stampWork)?.let { "\n\n$it" } ?: ""))
            },
            confirmButton = {
                TextButton(
                    enabled = batch != null && !running && !stampWork,
                    onClick = {
                        // Refused (a publish, or a stamp buy or search, started
                        // since this was drawn): the dialog stays, now saying
                        // why ([publishWaitNote]), rather than closing on nothing.
                        if (batch != null && Publisher.start(context, p, batch)) {
                            plan = null
                            if (p.kind == PublishKind.Text) {
                                writingText = false
                                text = ""
                            }
                        }
                    },
                ) { Text(stringResource(R.string.publish_confirm_button)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    plan = null
                    releaseGrant(context, p.source)
                }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
    if (clearing) {
        AlertDialog(
            onDismissRequest = { clearing = false },
            title = { Text(stringResource(R.string.publish_clear_title)) },
            text = {
                Text(stringResource(R.string.publish_clear_body))
            },
            confirmButton = {
                TextButton(onClick = {
                    clearing = false
                    history.clear()
                    Publisher.forgetRemoved(history.records.value)
                }) { Text(stringResource(R.string.publish_clear_button)) }
            },
            dismissButton = { TextButton(onClick = { clearing = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

/** Why Publish waits: a stamp buy or search may restart the gateway the upload goes through. */
internal val STAMP_WORK_RUNNING_NOTE: String get() = Strings.get(R.string.publish_stamp_work_note)

/** What a running publish is doing: uploading, or held back for the node's stamp work. */
internal fun runningText(p: Publisher.State.Running): String =
    if (p.waitingForNode) {
        Strings.get(R.string.publish_running_waiting)
    } else {
        Strings.get(R.string.publish_running_uploading)
    }

/** Why Publish waits: there's one upload at a time. */
internal val ANOTHER_PUBLISH_NOTE: String get() = Strings.get(R.string.publish_another_running_note)

/**
 * Why the confirmation's Publish is off, or null when it isn't for either
 * reason: a publish uploading ([running]), or stamp work that may restart
 * the gateway ([stampWork]).
 */
internal fun publishWaitNote(running: Boolean, stampWork: Boolean): String? = when {
    running -> ANOTHER_PUBLISH_NOTE
    stampWork -> STAMP_WORK_RUNNING_NOTE
    else -> null
}

/** The confirmation's body: what goes out, with which stamp, and that it's public. */
internal fun confirmText(p: PublishPlan, batch: PostageBatch?, batches: List<PostageBatch>): String {
    val what = when (p.kind) {
        PublishKind.Folder -> Strings.plural(
            if (indexDocumentFor(p.files.map { it.path }) != null) R.plurals.publish_confirm_folder_index else R.plurals.publish_confirm_folder_no_index,
            p.files.size,
            p.files.size,
            p.bytes?.let(::formatStampBytes) ?: Strings.get(R.string.publish_confirm_folder_size_unknown),
        )
        else -> p.bytes?.let(::formatStampBytes) ?: Strings.get(R.string.publish_confirm_size_unknown)
    }
    val stamp = batch?.let {
        Strings.get(
            R.string.publish_confirm_stamp,
            shortBatchId(it.id),
            Math.round(it.usedFraction * 100),
            it.ttlSeconds?.let(::formatStampTtl)?.lowercase() ?: Strings.get(R.string.publish_confirm_ttl_unknown),
        )
    } ?: (noStampText(batches, p.stampBytes) ?: "")
    return Strings.get(R.string.publish_confirm_body, what, stamp)
}

@Composable
private fun RunningCard(p: Publisher.State.Running) {
    SectionCard(title = stringResource(R.string.publish_running_title)) {
        Row(verticalAlignment = Alignment.Top) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(p.name, fontWeight = FontWeight.Medium)
                Muted(runningText(p))
            }
        }
    }
}

@Composable
private fun OutcomeCard(r: PublishRecord, onOpenUrl: (String) -> Unit, onDone: () -> Unit) {
    SectionCard(
        title = stringResource(if (r.status == PublishStatus.Completed) R.string.publish_outcome_published else R.string.publish_outcome_failed),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            if (r.status == PublishStatus.Completed) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Color(0xFF22C55E), modifier = Modifier.size(20.dp))
            } else {
                Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(r.name, fontWeight = FontWeight.Medium)
                r.bzzUrl?.let { LinkText(it) } ?: Muted(publishStatusText(r))
            }
        }
        LinkActions(r, onOpenUrl, extra = { TextButton(onClick = onDone) { Text(stringResource(R.string.common_done)) } })
    }
}

@Composable
private fun HistoryCard(r: PublishRecord, onOpenUrl: (String) -> Unit, onRemove: () -> Unit) {
    SectionCard(title = r.name) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                when (r.kind) {
                    PublishKind.File -> Icons.AutoMirrored.Filled.InsertDriveFile
                    PublishKind.Folder -> Icons.Filled.Folder
                    PublishKind.Text -> Icons.AutoMirrored.Filled.Notes
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                publishStatusText(r),
                style = MaterialTheme.typography.bodySmall,
                color = when (r.status) {
                    PublishStatus.Completed -> Color(0xFF22C55E)
                    PublishStatus.Failed -> MaterialTheme.colorScheme.error
                    PublishStatus.Uploading -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Muted(
            listOfNotNull(
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(r.startedAt)),
                r.bytes?.let(::formatStampBytes),
                r.batchId?.let { stringResource(R.string.publish_history_stamp, shortBatchId(it)) },
            ).joinToString(" · "),
        )
        r.bzzUrl?.let { LinkText(it) }
        LinkActions(
            r, onOpenUrl,
            extra = {
                if (r.status != PublishStatus.Uploading) TextButton(onClick = onRemove) { Text(stringResource(R.string.common_remove)) }
            },
        )
    }
}

/** The whole link, wrapped rather than cut, and selectable. */
@Composable
private fun LinkText(url: String) {
    SelectionContainer {
        Text(url, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LinkActions(r: PublishRecord, onOpenUrl: (String) -> Unit, extra: @Composable () -> Unit) {
    val context = LocalContext.current
    FlowRow {
        r.bzzUrl?.let { url ->
            TextButton(onClick = { onOpenUrl(url) }) { Text(stringResource(R.string.common_open)) }
            TextButton(onClick = { copyUrlToClipboard(context, url) }) { Text(stringResource(R.string.publish_copy_link)) }
            TextButton(onClick = { shareUrl(context, url, r.name) }) { Text(stringResource(R.string.common_share)) }
        }
        extra()
    }
}

@Composable
private fun IconLabel(icon: ImageVector, label: String) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
    Spacer(Modifier.width(6.dp))
    Text(label)
}

@Composable
private fun Muted(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * Keeps read access to what was picked until the publish is done with it
 * (a picker's grant otherwise ends with the activity that asked).
 */
private fun holdGrant(context: Context, uri: Uri) = PublishGrants.hold(context, uri)

/** Gives back what [holdGrant] kept: nothing stays readable once a publish no longer needs it. */
internal fun releaseGrant(context: Context, source: PublishSource) {
    val uri = when (source) {
        is PublishSource.OneFile -> source.uri
        is PublishSource.Folder -> source.treeUri
        is PublishSource.Text -> return
    }
    PublishGrants.release(context, uri)
}

private const val STAMPS_POLL_MS = 15_000L
private const val STAMPS_READ_TIMEOUT_MS = 15_000
