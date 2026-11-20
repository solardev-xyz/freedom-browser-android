package baby.freedom.mobile.agent

import android.content.Context
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import baby.freedom.mobile.R
import baby.freedom.mobile.browser.EmptyState
import baby.freedom.mobile.browser.FullScreenScaffold
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Whether AI is on (#573), for the browser to offer the chat (#574). */
internal fun agentAiOn(context: Context): Flow<Boolean> =
    AgentProviders.get(context).state.map { it.enabled }.distinctUntilChanged()

/** A model the picker offers: [label] is its name, [connectionName] its provider's. */
internal data class ModelChoice(val ref: ModelRef, val label: String, val connectionName: String)

/**
 * The models the chat's picker offers (#574): each connection's
 * favourites and the default model; a connection with no favourite
 * offers its own default, else the first of its models, so a server just
 * added is usable at once.
 */
internal fun modelChoices(state: AgentState, discovered: Map<String, List<AgentModel>>, providers: AgentProviders): List<ModelChoice> =
    state.connections.filter { !state.keyLost(it) }.flatMap { c ->
        val models = providers.modelsOf(c, discovered)
        val names = models.associate { it.id to it.name }
        val definition = ProviderCatalog.definition(c.providerId)
        val default = state.defaultModel?.takeIf { it.connectionId == c.id }?.modelId
        val ids = (listOfNotNull(default) + c.favorites).distinct().ifEmpty {
            listOfNotNull(definition?.defaultModelId ?: models.firstOrNull()?.id)
        }
        ids.map { ModelChoice(ModelRef(c.id, it), names[it] ?: it, c.name) }
    }

private val ComposerSaver = Saver<MutableState<String>, String>(
    // A long draft isn't kept across a process restart: the saved state
    // goes through a Binder transaction with a ~1 MB limit.
    save = { it.value.takeIf { v -> v.length <= 8_000 } },
    restore = { mutableStateOf(it) },
)

private val ModelRefSaver = Saver<MutableState<ModelRef?>, String>(
    save = { s -> s.value?.let { it.connectionId + "\n" + it.modelId } ?: "" },
    restore = { v -> mutableStateOf(v.split('\n').takeIf { it.size == 2 }?.let { ModelRef(it[0], it[1]) }) },
)

/**
 * Freedom Agent's chat (#574), from the main menu while AI is on: the
 * current chat, a model picker, a composer with Send (Stop while a reply
 * comes in), and the list of past chats. Closes itself if AI is turned
 * off while it's up.
 */
@Composable
internal fun AgentChatPage(aiOn: Boolean?, onDismiss: () -> Unit, onOpenSettings: () -> Unit) {
    // [aiOn] null: the switch isn't read yet (just after a rotation); only
    // a definite off closes the page.
    LaunchedEffect(aiOn) { if (aiOn == false) onDismiss() }
    if (aiOn != true) return
    val context = LocalContext.current
    val chats = remember(context) { AgentChats.get(context) }
    val providers = remember(context) { AgentProviders.get(context) }
    val state by providers.state.collectAsState(initial = null)
    val discovered by providers.models.collectAsState(initial = emptyMap())
    var sessionId by rememberSaveable { mutableStateOf<String?>(null) }
    var showList by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    BackHandler(onBack = onDismiss)
    BackHandler(enabled = showList) { showList = false }

    FullScreenScaffold(
        title = stringResource(if (showList) R.string.agent_chats_title else R.string.agent_menu),
        onDismiss = { if (showList) showList = false else onDismiss() },
        trailing = {
            if (!showList) {
                IconButton(onClick = { showList = true }) {
                    Icon(Icons.Outlined.History, contentDescription = stringResource(R.string.agent_chats_title))
                }
                IconButton(onClick = { sessionId = null }, enabled = sessionId != null) {
                    Icon(Icons.Outlined.EditNote, contentDescription = stringResource(R.string.agent_new_chat))
                }
            }
        },
    ) {
        val s = state ?: return@FullScreenScaffold
        if (showList) {
            ChatList(
                chats = chats,
                onOpen = {
                    sessionId = it
                    showList = false
                },
                onDeleted = { if (it == null || it == sessionId) sessionId = null },
            )
        } else {
            ChatView(
                chats = chats,
                sessionId = sessionId,
                choices = modelChoices(s, discovered, providers),
                defaultModel = s.defaultModel,
                onStarted = { sessionId = it },
                onOpenSettings = onOpenSettings,
                scope = scope,
            )
        }
    }
}

@Composable
private fun ChatView(
    chats: AgentChats,
    sessionId: String?,
    choices: List<ModelChoice>,
    defaultModel: ModelRef?,
    onStarted: (String) -> Unit,
    onOpenSettings: () -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val session by remember(sessionId) { sessionId?.let { chats.session(it) } ?: flowOf(null) }.collectAsState(initial = null)
    val turns by remember(sessionId) { sessionId?.let { chats.turns(it) } ?: flowOf(emptyList()) }.collectAsState(initial = emptyList())
    val liveAll by chats.live.collectAsState()
    val live = sessionId?.let { liveAll[it] }
    var draft by rememberSaveable(saver = ComposerSaver) { mutableStateOf("") }
    var picked by rememberSaveable(sessionId, saver = ModelRefSaver) { mutableStateOf(null) }
    var sendFailed by remember { mutableStateOf(false) }
    // The chat's own model, else the one picked, else the default; only one still offered.
    val sessionModel = session?.let { s -> s.connectionId?.let { c -> s.modelId?.let { ModelRef(c, it) } } }
    val model = listOfNotNull(picked, sessionModel, defaultModel).firstOrNull { ref -> choices.any { it.ref == ref } }
        ?: choices.firstOrNull()?.ref

    if (choices.isEmpty() && turns.isEmpty()) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                EmptyState(
                    icon = Icons.Outlined.AutoAwesome,
                    title = stringResource(R.string.agent_no_provider_title),
                    hint = stringResource(R.string.agent_no_provider_hint),
                )
            }
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.padding(bottom = 32.dp).heightIn(min = 48.dp)) {
                Text(stringResource(R.string.agent_open_settings))
            }
        }
        return
    }

    val listState = rememberLazyListState()
    // Follows the reply as it grows while the list is at its end; a user
    // who scrolls up to read stays where they are until they scroll back
    // down, or send.
    var follow by remember(sessionId) { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) follow = !listState.canScrollForward
        }
    }
    LaunchedEffect(turns.size) {
        follow = true
        if (turns.isNotEmpty()) listState.scrollToItem(turns.size - 1, Int.MAX_VALUE / 2)
    }
    LaunchedEffect(live?.text?.length) {
        if (follow && turns.isNotEmpty()) listState.scrollToItem(turns.size - 1, Int.MAX_VALUE / 2)
    }
    Column(Modifier.fillMaxSize()) {
        if (turns.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                EmptyState(
                    icon = Icons.Outlined.AutoAwesome,
                    title = stringResource(R.string.agent_empty_title),
                    hint = stringResource(R.string.agent_empty_hint),
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            ) {
                items(turns, key = { it.id }) { turn ->
                    TurnView(turn, live?.takeIf { it.turnId == turn.id })
                }
            }
        }
        HorizontalDivider()
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            ModelPicker(choices, model, onPick = { picked = it })
            if (sendFailed) {
                Text(
                    stringResource(R.string.agent_send_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            Row(verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = {
                        draft = it
                        sendFailed = false
                    },
                    placeholder = { Text(stringResource(R.string.agent_composer_hint)) },
                    modifier = Modifier.weight(1f),
                    maxLines = 6,
                )
                val running = live != null
                if (running) {
                    FilledIconButton(
                        onClick = { scope.launch { sessionId?.let { chats.stop(it) } } },
                        modifier = Modifier.padding(start = 8.dp),
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.agent_stop))
                    }
                } else {
                    FilledIconButton(
                        onClick = {
                            val m = model ?: return@FilledIconButton
                            val text = draft
                            scope.launch {
                                val id = chats.send(sessionId, text, m)
                                if (id == null) {
                                    sendFailed = true
                                } else {
                                    draft = ""
                                    onStarted(id)
                                }
                            }
                        },
                        enabled = model != null && draft.isNotBlank() && draft.length <= AgentChats.MAX_MESSAGE_CHARS,
                        modifier = Modifier.padding(start = 8.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.agent_send))
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelPicker(choices: List<ModelChoice>, model: ModelRef?, onPick: (ModelRef) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = choices.firstOrNull { it.ref == model }
    val label = current?.let { stringResource(R.string.agent_model_label, it.label, it.connectionName) }
        ?: stringResource(R.string.agent_model_none)
    Box {
        TextButton(
            onClick = { open = true },
            enabled = choices.isNotEmpty(),
            modifier = Modifier.heightIn(min = 48.dp).semantics {
                contentDescription = label
            },
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f, fill = false))
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (choice in choices) {
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(choice.label)
                            Text(
                                choice.connectionName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = {
                        open = false
                        onPick(choice.ref)
                    },
                )
            }
        }
    }
}

@Composable
private fun TurnView(turn: ChatTurn, live: AgentChats.Live?) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // The user's message, on the end side.
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            Text(
                stringResource(R.string.agent_you),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.semantics { heading() },
            )
            SelectionContainer {
                Text(
                    turn.userText,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .widthIn(max = 560.dp)
                        .background(colors.secondaryContainer, RoundedCornerShape(16.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    color = colors.onSecondaryContainer,
                )
            }
        }
        Text(
            stringResource(R.string.agent_name),
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceVariant,
            modifier = Modifier.semantics { heading() },
        )
        val running = live != null || turn.status == ChatStatus.RUNNING
        val text = live?.text ?: turn.assistantText
        if (text.isNotEmpty()) {
            SelectionContainer { MarkdownText(text, Modifier.fillMaxWidth()) }
        } else if (running) {
            Text(
                stringResource(R.string.agent_thinking),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (!running) {
            turnNote(turn)?.let { (note, isError) ->
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isError) colors.error else colors.onSurfaceVariant,
                )
            }
        }
    }
}

/** What's said under a turn that didn't end in a whole answer, and whether it's an error. */
@Composable
private fun turnNote(turn: ChatTurn): Pair<String, Boolean>? {
    val error = turn.error?.let { name -> ChatError.entries.firstOrNull { it.name == name } }
    return when {
        turn.status == ChatStatus.INTERRUPTED -> stringResource(R.string.agent_note_interrupted) to false
        error == ChatError.CutShort -> stringResource(R.string.agent_note_cut_short) to false
        error != null -> stringResource(errorText(error)) to (turn.status == ChatStatus.FAILED)
        turn.status == ChatStatus.CANCELLED -> stringResource(R.string.agent_note_stopped) to false
        else -> null
    }
}

/** A [ChatError] in plain words. */
internal fun errorText(error: ChatError): Int = when (error) {
    ChatError.NoModel -> R.string.agent_error_no_model
    ChatError.NoKey -> R.string.agent_error_no_key
    ChatError.BadUrl -> R.string.agent_error_bad_url
    ChatError.Unauthorized -> R.string.agent_error_unauthorized
    ChatError.ModelNotFound -> R.string.agent_error_model_not_found
    ChatError.RateLimited -> R.string.agent_error_rate_limited
    ChatError.ServerError -> R.string.agent_error_server
    ChatError.Refused -> R.string.agent_error_refused
    ChatError.Unreachable -> R.string.agent_error_unreachable
    ChatError.TimedOut -> R.string.agent_error_timed_out
    ChatError.BadReply -> R.string.agent_error_bad_reply
    ChatError.ContextTooLong -> R.string.agent_error_too_long
    ChatError.TooManySteps -> R.string.agent_error_too_many_steps
    ChatError.AiOff -> R.string.agent_error_ai_off
    ChatError.KeyChanged -> R.string.agent_error_key_changed
    ChatError.CutShort -> R.string.agent_note_cut_short
}

@Composable
private fun ChatList(chats: AgentChats, onOpen: (String) -> Unit, onDeleted: (String?) -> Unit) {
    val sessions by chats.sessions.collectAsState(initial = null)
    val live by chats.live.collectAsState()
    val scope = rememberCoroutineScope()
    var confirmAll by remember { mutableStateOf(false) }
    val list = sessions ?: return
    if (list.isEmpty()) {
        EmptyState(
            icon = Icons.Outlined.History,
            title = stringResource(R.string.agent_chats_empty_title),
            hint = stringResource(R.string.agent_chats_empty_hint),
        )
        return
    }
    val context = LocalContext.current
    LazyColumn(Modifier.fillMaxSize()) {
        items(list, key = { it.id }) { session ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clickable { onOpen(session.id) }
                    .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(session.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    val sub = buildList {
                        add(DateUtils.getRelativeTimeSpanString(session.updatedAt).toString())
                        session.modelId?.let { add(it) }
                        if (session.id in live) add(context.getString(R.string.agent_chat_running))
                    }.joinToString(" · ")
                    Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val deleteLabel = stringResource(R.string.agent_delete_chat, session.title)
                IconButton(onClick = {
                    scope.launch { if (chats.delete(session.id)) onDeleted(session.id) }
                }) {
                    Icon(Icons.Outlined.Delete, contentDescription = deleteLabel)
                }
            }
        }
        item {
            TextButton(
                onClick = { confirmAll = true },
                modifier = Modifier.padding(16.dp).heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.agent_delete_all), color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text(stringResource(R.string.agent_delete_all_title)) },
            text = { Text(stringResource(R.string.agent_delete_all_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmAll = false
                    scope.launch { if (chats.deleteAll()) onDeleted(null) }
                }) { Text(stringResource(R.string.agent_delete_all_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmAll = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}
