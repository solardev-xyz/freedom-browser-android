package baby.freedom.mobile.agent

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import baby.freedom.mobile.R
import baby.freedom.mobile.browser.ConfirmDialog
import baby.freedom.mobile.browser.FullScreenScaffold
import baby.freedom.mobile.browser.PageRow
import baby.freedom.mobile.browser.PageRowStyle
import baby.freedom.mobile.browser.SectionCard
import baby.freedom.mobile.browser.SecureWindow
import baby.freedom.mobile.browser.SettingsRow
import baby.freedom.mobile.browser.TabTextInput
import baby.freedom.mobile.browser.searchKeywords
import baby.freedom.mobile.browser.settingsRow
import baby.freedom.mobile.l10n.Strings
import baby.freedom.mobile.l10n.pluralText
import kotlinx.coroutines.launch

/*
 * Settings → AI (#573): the "Enable AI features" switch, and under it
 * Models & providers — connect a hosted provider with an API key or a
 * server on the network by URL, pick favourite models and the default
 * one, and Test connection. Voice dictation's page (#578) is in
 * DictationSettingsUi.kt.
 */

private val AI_SECTION: String get() = Strings.get(R.string.agent_section_ai)

/** The one-line state under the top-level AI row. */
internal fun aiPageSummary(state: AgentState): String = when {
    !state.enabled -> Strings.get(R.string.agent_page_summary_off)
    state.connections.isEmpty() -> Strings.get(R.string.agent_page_summary_none)
    else -> Strings.plural(R.plurals.agent_page_summary_providers, state.connections.size, state.connections.size)
}

private fun enableSubtitle(on: Boolean) =
    Strings.get(if (on) R.string.agent_enable_on else R.string.agent_enable_off)

/** The AI card's rows, for settings search. Models & providers and Voice dictation are rows only while AI is on. */
internal fun aiSectionRows(state: AgentState): List<SettingsRow> = listOfNotNull(
    settingsRow(
        "ai-enabled", Strings.get(R.string.agent_enable), enableSubtitle(state.enabled),
        *searchKeywords(R.string.agent_enable_keywords),
    ),
    if (state.enabled) {
        settingsRow(
            "ai-providers", Strings.get(R.string.agent_providers),
            *searchKeywords(R.string.agent_providers_keywords),
        )
    } else {
        null
    },
    if (state.enabled) {
        settingsRow(
            "ai-dictation", Strings.get(R.string.agent_dictation),
            *searchKeywords(R.string.agent_dictation_keywords),
        )
    } else {
        null
    },
)

private fun modelName(state: AgentState, discovered: Map<String, List<AgentModel>>, ref: ModelRef): String {
    val connection = state.connection(ref.connectionId) ?: return ref.modelId
    val list = discovered[connection.id] ?: ProviderCatalog.definition(connection.providerId)?.bundled.orEmpty()
    return list.firstOrNull { it.id == ref.modelId }?.name ?: ref.modelId
}

/** "OpenAI · GPT-6.1 Sol", or null without a default. */
private fun defaultLine(state: AgentState, discovered: Map<String, List<AgentModel>>): String? {
    val ref = state.defaultModel ?: return null
    val connection = state.connection(ref.connectionId) ?: return null
    return Strings.get(R.string.agent_default_model_line, connection.name, modelName(state, discovered, ref))
}

/** The AI card on Settings → AI. */
@Composable
internal fun AiSection(
    visible: Set<Any>,
    onOpenProviders: () -> Unit,
    onOpenDictation: () -> Unit,
) {
    val context = LocalContext.current
    val providers = remember(context) { AgentProviders.get(context) }
    val state by providers.state.collectAsState(initial = AgentState())
    val discovered by providers.models.collectAsState(initial = emptyMap())
    val scope = rememberCoroutineScope()
    var failed by remember { mutableStateOf(false) }
    SectionCard(title = AI_SECTION) {
        if ("ai-enabled" in visible) PageRow(
            title = stringResource(R.string.agent_enable),
            subtitle = enableSubtitle(state.enabled),
            thirdLine = if (failed) stringResource(R.string.agent_enable_failed) else null,
            style = PageRowStyle.Inset,
            leadingIcon = Icons.Filled.AutoAwesome,
            checked = state.enabled,
            onClick = {
                val on = !state.enabled
                scope.launch { failed = !providers.setEnabled(on) }
            },
            trailing = { Switch(checked = state.enabled, onCheckedChange = null) },
        )
        if (state.enabled && "ai-providers" in visible) PageRow(
            title = stringResource(R.string.agent_providers),
            subtitle = if (state.connections.isEmpty()) {
                stringResource(R.string.agent_providers_none)
            } else {
                stringResource(
                    R.string.agent_providers_summary,
                    pluralText(R.plurals.agent_providers_count, state.connections.size, state.connections.size),
                    defaultLine(state, discovered) ?: stringResource(R.string.agent_default_none),
                )
            },
            style = PageRowStyle.Inset,
            leadingIcon = Icons.Filled.Tune,
            onClick = onOpenProviders,
        )
        if (state.enabled && "ai-dictation" in visible) {
            val pick by remember(context) { DictationSettings.get(context).language }.collectAsState(initial = null)
            val recognizer = rememberDictationRecognizer()
            PageRow(
                title = stringResource(R.string.agent_dictation),
                // Not before the user's recognizer pick is read: never the wrong destination.
                subtitle = if (recognizer.ready) dictationSummary(recognizer.kind, pick) else "",
                style = PageRowStyle.Inset,
                leadingIcon = Icons.Filled.Mic,
                onClick = onOpenDictation,
            )
        }
    }
}

/** Which provider page is open: a provider, and for an OpenAI-compatible server which one (null: a new one). */
private data class ProviderTarget(val providerId: String, val connectionId: String?) : java.io.Serializable

/**
 * Settings → AI → Models & providers: the default model, then every
 * provider by group with whether it's connected. Closes by itself if AI
 * is turned off meanwhile.
 */
@Composable
internal fun AiProvidersPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val providers = remember(context) { AgentProviders.get(context) }
    val state by providers.state.collectAsState(initial = null)
    val discovered by providers.models.collectAsState(initial = emptyMap())
    val signedOut by providers.signedOut.collectAsState()
    var target by rememberSaveable { mutableStateOf<ProviderTarget?>(null) }
    val current = state
    if (current != null && !current.enabled) LaunchedEffect(Unit) { onBack() }
    if (current == null) return

    val open = target
    if (open != null) {
        ProviderPage(
            providers = providers,
            state = current,
            discovered = discovered,
            target = open,
            onSaved = { id -> target = open.copy(connectionId = id) },
            onBack = { target = null },
        )
        return
    }
    BackHandler(onBack = onBack)
    FullScreenScaffold(title = stringResource(R.string.agent_providers), onDismiss = onBack) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item("default") {
                SectionCard(title = stringResource(R.string.agent_default_model)) {
                    Text(
                        defaultLine(current, discovered) ?: stringResource(R.string.agent_default_model_none),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            for (group in ProviderGroup.entries) item(group.name) {
                SectionCard(title = stringResource(group.titleRes)) {
                    for (definition in ProviderCatalog.ALL.filter { it.group == group }) {
                        if (definition.id == ProviderCatalog.COMPATIBLE) {
                            for (c in current.connections.filter { it.providerId == ProviderCatalog.COMPATIBLE }) {
                                PageRow(
                                    title = c.name,
                                    subtitle = connectionStatus(current, discovered, c, signedOut),
                                    thirdLine = c.baseUrl,
                                    style = PageRowStyle.Inset,
                                    leadingIcon = Icons.Filled.Dns,
                                    onClick = { target = ProviderTarget(definition.id, c.id) },
                                )
                            }
                            PageRow(
                                title = stringResource(R.string.agent_add_server),
                                subtitle = stringResource(R.string.agent_add_server_subtitle),
                                style = PageRowStyle.Inset,
                                leadingIcon = Icons.Filled.Add,
                                onClick = { target = ProviderTarget(definition.id, null) },
                            )
                        } else {
                            val c = current.connection(definition.id)
                            PageRow(
                                title = definition.name,
                                subtitle = if (c == null) {
                                    stringResource(definition.descriptionRes)
                                } else {
                                    connectionStatus(current, discovered, c, signedOut)
                                },
                                thirdLine = if (c == null) stringResource(R.string.agent_status_not_connected) else c.baseUrl,
                                style = PageRowStyle.Inset,
                                leadingIcon = when (definition.kind) {
                                    ProviderKind.Lan -> Icons.Filled.Dns
                                    ProviderKind.Subscription -> Icons.Filled.AccountCircle
                                    ProviderKind.ApiKey -> Icons.Filled.Cloud
                                },
                                onClick = { target = ProviderTarget(definition.id, c?.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** "Connected · 12 models", or that a saved key must be entered again (a subscription: signed in again). */
private fun connectionStatus(
    state: AgentState,
    discovered: Map<String, List<AgentModel>>,
    c: AgentConnection,
    signedOut: Set<String>,
): String {
    val definition = ProviderCatalog.definition(c.providerId)
    val subscription = definition?.kind == ProviderKind.Subscription
    if (subscription && state.keyLost(c)) return Strings.get(R.string.agent_status_sign_in_lost)
    if (subscription && c.id in signedOut) return Strings.get(R.string.agent_status_sign_in_again)
    if (state.keyLost(c)) return Strings.get(R.string.agent_status_key_lost)
    val count = discovered[c.id]?.size ?: definition?.bundled?.size ?: 0
    if (subscription) {
        return if (count == 0) {
            Strings.get(R.string.agent_status_signed_in)
        } else {
            Strings.plural(R.plurals.agent_status_signed_in_models, count, count)
        }
    }
    return if (count == 0) {
        Strings.get(R.string.agent_status_connected)
    } else {
        Strings.plural(R.plurals.agent_status_connected_models, count, count)
    }
}

private fun urlHint(rejection: ProviderUrls.Rejection): String = Strings.get(
    when (rejection) {
        ProviderUrls.Rejection.Invalid -> R.string.agent_url_invalid
        ProviderUrls.Rejection.Scheme -> R.string.agent_url_scheme
        ProviderUrls.Rejection.Credentials -> R.string.agent_url_credentials
        ProviderUrls.Rejection.QueryOrFragment -> R.string.agent_url_query
        ProviderUrls.Rejection.PublicHttp -> R.string.agent_url_public_http
        ProviderUrls.Rejection.Onion -> R.string.agent_url_onion
    },
)

/** [keyed]: a key went with the request, so a missing model may be the key's own limit. */
private fun testLine(result: TestResult, model: String, keyed: Boolean, subscription: Boolean): String = when (result) {
    TestResult.Ok -> Strings.get(R.string.agent_test_ok, model)
    TestResult.NoKey -> Strings.get(if (subscription) R.string.agent_test_sign_in_first else R.string.agent_test_no_key)
    TestResult.SignInAgain -> Strings.get(R.string.agent_test_sign_in_again)
    TestResult.Unauthorized ->
        Strings.get(if (subscription) R.string.agent_test_account_refused else R.string.agent_test_unauthorized)
    TestResult.ModelNotFound ->
        Strings.get(
            when {
                subscription -> R.string.agent_test_model_not_found_account
                keyed -> R.string.agent_test_model_not_found
                else -> R.string.agent_test_model_not_found_server
            },
            model,
        )
    TestResult.RateLimited -> Strings.get(R.string.agent_test_rate_limited)
    TestResult.ServerError -> Strings.get(R.string.agent_test_server_error)
    TestResult.Refused -> Strings.get(R.string.agent_test_refused)
    TestResult.Unreachable -> Strings.get(R.string.agent_test_unreachable)
    TestResult.TimedOut -> Strings.get(R.string.agent_test_timed_out)
    TestResult.BadReply -> Strings.get(R.string.agent_test_bad_reply)
    TestResult.BadUrl -> Strings.get(R.string.agent_test_bad_url)
    TestResult.Cancelled -> Strings.get(R.string.agent_test_cancelled)
}

/** What a model list that isn't there says: only a URL the user typed is theirs to check. */
internal fun refreshNotFoundText(definition: ProviderDefinition): Int = when {
    definition.id == ProviderCatalog.COMPATIBLE -> R.string.agent_refresh_not_found_compatible
    // A hosted provider's address is fixed and not shown: there's no URL to check.
    definition.kind != ProviderKind.Lan -> R.string.agent_refresh_not_found_hosted
    else -> R.string.agent_refresh_not_found
}

private fun refreshLine(result: RefreshResult, definition: ProviderDefinition): String = when (result) {
    RefreshResult.Done -> Strings.get(R.string.agent_refresh_done)
    RefreshResult.NoKey -> Strings.get(
        if (definition.kind == ProviderKind.Subscription) R.string.agent_test_sign_in_first else R.string.agent_test_no_key,
    )
    RefreshResult.SignInAgain -> Strings.get(R.string.agent_test_sign_in_again)
    RefreshResult.Unauthorized -> Strings.get(
        if (definition.kind == ProviderKind.Subscription) R.string.agent_test_account_refused else R.string.agent_test_unauthorized,
    )
    RefreshResult.NotFound -> Strings.get(refreshNotFoundText(definition))
    RefreshResult.RateLimited -> Strings.get(R.string.agent_test_rate_limited)
    RefreshResult.ServerError -> Strings.get(R.string.agent_test_server_error)
    RefreshResult.Refused -> Strings.get(R.string.agent_test_refused)
    RefreshResult.Unreachable -> Strings.get(R.string.agent_test_unreachable)
    RefreshResult.TimedOut -> Strings.get(R.string.agent_refresh_timed_out)
    RefreshResult.TooLarge -> Strings.get(R.string.agent_refresh_too_large)
    RefreshResult.Unusable -> Strings.get(R.string.agent_refresh_unusable)
    RefreshResult.BadUrl -> Strings.get(R.string.agent_test_bad_url)
    RefreshResult.Cancelled -> Strings.get(R.string.agent_test_cancelled)
    RefreshResult.Failed -> Strings.get(R.string.agent_refresh_failed)
}

/** The model Test connection asks: the default if it's this connection's, else a favourite, else the first. */
internal fun testModel(state: AgentState, connection: AgentConnection, models: List<AgentModel>): AgentModel? {
    state.defaultModel?.takeIf { it.connectionId == connection.id }?.let { ref ->
        return models.firstOrNull { it.id == ref.modelId } ?: AgentModel(ref.modelId)
    }
    return models.firstOrNull { it.id in connection.favorites } ?: models.firstOrNull()
}

/** Favourites first (in list order), then the rest; [filter] narrows by name or id. */
internal fun orderedModels(models: List<AgentModel>, favorites: List<String>, filter: String): List<AgentModel> {
    val q = filter.trim()
    val shown = if (q.isEmpty()) models else models.filter { it.name.contains(q, true) || it.id.contains(q, true) }
    return shown.sortedBy { if (it.id in favorites) 0 else 1 }
}

@Composable
private fun ProviderPage(
    providers: AgentProviders,
    state: AgentState,
    discovered: Map<String, List<AgentModel>>,
    target: ProviderTarget,
    onSaved: (String) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val definition = ProviderCatalog.definition(target.providerId) ?: return
    val connection = target.connectionId?.let { state.connection(it) }
    // A server removed meanwhile (here or on another page): back to the list.
    if (target.connectionId != null && connection == null && definition.id == ProviderCatalog.COMPATIBLE) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val isLan = definition.kind == ProviderKind.Lan
    val isSubscription = definition.kind == ProviderKind.Subscription
    val hasKeyField = (!isLan && !isSubscription) || definition.id == ProviderCatalog.COMPATIBLE
    // Keyed on the provider, not the target: saving a new server gives the
    // same page its connection id, and its status line must survive that.
    // The key is held in plain `remember`: never in saved instance state.
    var key by remember(target.providerId) { mutableStateOf("") }
    var reveal by remember(target.providerId) { mutableStateOf(false) }
    var name by rememberSaveable(target.providerId) { mutableStateOf(connection?.name ?: "") }
    var url by rememberSaveable(target.providerId) { mutableStateOf(connection?.baseUrl ?: "") }
    // Which action is running, so only its own button says so.
    var running by remember(target.providerId) { mutableStateOf<PageAction?>(null) }
    val busy = running != null
    var line by remember(target.providerId) { mutableStateOf<String?>(null) }
    var filter by rememberSaveable(target.providerId) { mutableStateOf("") }
    var confirmRemove by remember { mutableStateOf(false) }
    // A pasted Sign in with ChatGPT callback (#602): held here, not in the
    // list item (scrolling it off would drop it), and never in saved state.
    var pasted by remember(target.providerId) { mutableStateOf("") }
    if (reveal) SecureWindow()
    // A finished or failed sign-in isn't shown again on the next visit.
    // Here, not in the list item, which scrolling off disposes.
    if (isSubscription) DisposableEffect(definition.id) {
        onDispose { providers.dismissLogin(definition.id) }
    }

    val keyText = key.trim()
    val keyOk = keyText.isEmpty() || AgentProviders.isUsableKey(keyText)
    val urlCheck = if (isLan && url.isNotBlank()) ProviderUrls.validate(url) else null
    val canSave = !busy && keyOk && if (isLan) urlCheck?.url != null else keyText.isNotEmpty()
    val models = connection?.let { providers.modelsOf(it, discovered) }.orEmpty()
    // A saved key stays only with the address it was saved for.
    val urlMoved = isLan && connection != null && connection.hasKey &&
        urlCheck?.url?.let(ProviderUrls::display) != connection.baseUrl

    fun run(action: PageAction, block: suspend () -> String?) {
        if (running != null) return
        running = action
        line = null
        scope.launch {
            try {
                line = block()
            } finally {
                running = null
            }
        }
    }

    fun save() = run(PageAction.Saving) {
        focusManager.clearFocus()
        val id = if (isLan) {
            providers.saveLan(
                definition.id, connection?.id, name, urlCheck?.url ?: return@run null,
                key = if (hasKeyField && keyText.isNotEmpty()) keyText else null,
            )
        } else {
            target.providerId.takeIf { providers.saveApiKey(definition.id, keyText) }
        }
        if (id == null) return@run Strings.get(R.string.agent_save_failed)
        key = ""
        onSaved(id)
        // Fetch the list the new key or server offers, where there is one to fetch.
        if (definition.catalogPath == null) return@run null
        running = PageAction.Refreshing
        refreshLine(providers.refreshModels(id), definition)
    }

    FullScreenScaffold(
        title = connection?.name ?: definition.name,
        onDismiss = onBack,
        trailing = {
            if (connection != null) TextButton(onClick = { confirmRemove = true }) {
                Text(
                    stringResource(if (isSubscription) R.string.agent_sign_out else R.string.agent_remove),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
    ) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item("connect") {
                SectionCard(title = definition.name) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(definition.descriptionRes), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(definition.privacyRes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (definition.id == ProviderCatalog.COMPATIBLE) OutlinedTextField(
                            value = name,
                            onValueChange = { name = it.take(80) },
                            label = { Text(stringResource(R.string.agent_server_name)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (isLan) OutlinedTextField(
                            value = url,
                            onValueChange = { url = it.take(ProviderUrls.MAX_LENGTH) },
                            label = { Text(stringResource(R.string.agent_server_url)) },
                            placeholder = {
                                Text(
                                    stringResource(
                                        if (definition.id == ProviderCatalog.OLLAMA) R.string.agent_server_url_hint_ollama
                                        else R.string.agent_server_url_hint_compatible,
                                    ),
                                )
                            },
                            isError = urlCheck?.rejection != null,
                            supportingText = {
                                Text(urlCheck?.rejection?.let(::urlHint) ?: stringResource(R.string.agent_url_rule))
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (hasKeyField) TabTextInput(private = true) {
                            OutlinedTextField(
                                value = key,
                                onValueChange = { key = it.take(AgentProviders.MAX_KEY_LENGTH + 1) },
                                label = {
                                    Text(stringResource(if (isLan) R.string.agent_api_key_optional else R.string.agent_api_key))
                                },
                                isError = !keyOk,
                                supportingText = {
                                    Text(
                                        when {
                                            !keyOk -> stringResource(R.string.agent_api_key_invalid)
                                            urlMoved && keyText.isEmpty() -> stringResource(R.string.agent_api_key_new_url)
                                            connection != null && state.keyLost(connection) ->
                                                stringResource(R.string.agent_status_key_lost)
                                            connection != null && connection.id in state.keys ->
                                                stringResource(R.string.agent_api_key_saved)
                                            else -> stringResource(R.string.agent_api_key_stored)
                                        },
                                    )
                                },
                                singleLine = true,
                                visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { reveal = !reveal }) {
                                        Icon(
                                            if (reveal) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                            contentDescription = stringResource(
                                                if (reveal) R.string.agent_hide_key else R.string.agent_show_key,
                                            ),
                                        )
                                    }
                                },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Password,
                                    autoCorrectEnabled = false,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        if (isSubscription) SubscriptionSignIn(
                            providers = providers,
                            definition = definition,
                            connected = connection != null,
                            enabled = !busy,
                            pasted = pasted,
                            onPasted = { pasted = it },
                        )
                        definition.keyUrl?.let { link ->
                            Text(
                                stringResource(R.string.agent_get_key, link),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (!isSubscription) {
                                Button(onClick = { save() }, enabled = canSave, modifier = Modifier.heightIn(min = 48.dp)) {
                                    BusyLabel(
                                        running == PageAction.Saving,
                                        if (isLan) R.string.agent_save else R.string.agent_save_key,
                                        R.string.agent_saving,
                                    )
                                }
                            }
                            if (connection != null) {
                                val testWith = testModel(state, connection, models)
                                OutlinedButton(
                                    onClick = {
                                        val model = testWith ?: return@OutlinedButton
                                        run(PageAction.Testing) {
                                            val keyed = connection.id in state.keys
                                            testLine(providers.testConnection(connection.id, model.id), model.name, keyed, isSubscription)
                                        }
                                    },
                                    enabled = !busy && testWith != null,
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) { BusyLabel(running == PageAction.Testing, R.string.agent_test, R.string.agent_testing) }
                                val savedUrl = connection.baseUrl?.let { ProviderUrls.validate(it).url }
                                if (isLan && hasKeyField && connection.hasKey && savedUrl != null) TextButton(
                                    onClick = {
                                        run(PageAction.RemovingKey) {
                                            val id = providers.saveLan(definition.id, connection.id, connection.name, savedUrl, key = "")
                                            Strings.get(if (id == null) R.string.agent_save_failed else R.string.agent_key_removed)
                                        }
                                    },
                                    enabled = !busy,
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) {
                                    BusyLabel(
                                        running == PageAction.RemovingKey, R.string.agent_remove_key, R.string.agent_removing_key,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                                if (definition.catalogPath != null) OutlinedButton(
                                    onClick = { run(PageAction.Refreshing) { refreshLine(providers.refreshModels(connection.id), definition) } },
                                    enabled = !busy,
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) {
                                    BusyLabel(running == PageAction.Refreshing, R.string.agent_refresh_models, R.string.agent_refreshing)
                                }
                            }
                        }
                        line?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            )
                        }
                    }
                }
            }
            if (connection != null) {
                item("models-head") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            stringResource(R.string.agent_models),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                        Text(
                            stringResource(
                                when {
                                    models.isEmpty() -> R.string.agent_models_empty
                                    connection.id !in discovered -> R.string.agent_models_bundled
                                    else -> R.string.agent_models_discovered
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                        if (models.size > FILTER_FROM) OutlinedTextField(
                            value = filter,
                            onValueChange = { filter = it.take(200) },
                            label = { Text(stringResource(R.string.agent_models_filter)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                items(orderedModels(models, connection.favorites, filter), key = { "m:" + it.id }) { model ->
                    ModelRow(
                        model = model,
                        isDefault = state.defaultModel == ModelRef(connection.id, model.id),
                        favorite = model.id in connection.favorites,
                        onDefault = { scope.launch { providers.setDefault(connection.id, model.id) } },
                        onFavorite = { on -> scope.launch { providers.setFavorite(connection.id, model.id, on) } },
                    )
                }
            }
        }
    }

    if (confirmRemove && connection != null) {
        ConfirmDialog(
            title = stringResource(
                if (isSubscription) R.string.agent_sign_out_title else R.string.agent_remove_title,
                connection.name,
            ),
            message = stringResource(if (isSubscription) R.string.agent_sign_out_message else R.string.agent_remove_message),
            confirmLabel = stringResource(if (isSubscription) R.string.agent_sign_out else R.string.agent_remove),
            onConfirm = {
                confirmRemove = false
                scope.launch {
                    if (providers.remove(connection.id)) {
                        onBack()
                    } else {
                        line = Strings.get(if (isSubscription) R.string.agent_sign_out_failed else R.string.agent_remove_failed)
                    }
                }
            },
            onDismiss = { confirmRemove = false },
        )
    }
}

private fun loginLine(providerId: String, failure: LoginFailure): String = Strings.get(
    // Sign in with ChatGPT has opened a page by then: say only that nothing was saved.
    if (failure == LoginFailure.BadReply && providerId == ChatGptLogin.PROVIDER) R.string.agent_sign_in_bad_grant else when (failure) {
        LoginFailure.Denied -> R.string.agent_sign_in_denied
        LoginFailure.Expired -> R.string.agent_sign_in_expired
        LoginFailure.NotEnabled -> R.string.agent_sign_in_not_enabled
        LoginFailure.NoAccess -> R.string.agent_sign_in_no_access
        LoginFailure.RateLimited -> R.string.agent_sign_in_rate_limited
        LoginFailure.ServerError -> R.string.agent_sign_in_server_error
        LoginFailure.Unreachable -> R.string.agent_sign_in_unreachable
        LoginFailure.TimedOut -> R.string.agent_sign_in_timed_out
        LoginFailure.BadReply -> R.string.agent_sign_in_bad_reply
        LoginFailure.SaveFailed -> R.string.agent_sign_in_save_failed
        LoginFailure.PortBusy -> R.string.agent_sign_in_port_busy
        LoginFailure.NoCallback -> R.string.agent_sign_in_no_callback
        LoginFailure.NotCompleted -> R.string.agent_sign_in_not_completed
        LoginFailure.NotAccepted -> R.string.agent_sign_in_not_accepted
        LoginFailure.NoDeviceId -> R.string.agent_sign_in_no_device_id
    },
)

/**
 * A subscription's sign-in. By device code (#576): Sign in, then the code
 * with the page to enter it on (opened only from the user's tap, in a
 * Custom Tab), Copy and Cancel, until the provider says yes; the page
 * shown, opened and copied is exactly the one
 * [SubscriptionAuth.verificationUrl] passed. Sign in with ChatGPT (#602):
 * Sign in opens OpenAI's page by itself, once per sign-in
 * ([AgentProviders.claimOpen]), with Open again, Cancel and a field for
 * the address the browser ended on, until the browser comes back; the
 * page opened is the one [ChatGptLogin.isAuthorizeUrl] passed. The
 * sign-in runs in [AgentProviders], so it carries on while the user is in
 * the browser, and this shows it again on return.
 */
@Composable
private fun SubscriptionSignIn(
    providers: AgentProviders,
    definition: ProviderDefinition,
    connected: Boolean,
    enabled: Boolean,
    pasted: String,
    onPasted: (String) -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    // Start and Cancel wait for a save under way (see cancelLogin); they only change state in memory.
    val scope = rememberCoroutineScope()
    val login by providers.login.collectAsState()
    val mine = login?.takeIf { it.providerId == definition.id }
    var note by remember(definition.id) { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (mine) {
            is LoginState.Waiting -> {
                val code = mine.code
                Text(
                    stringResource(R.string.agent_sign_in_code_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SelectionContainer {
                    Text(
                        code.userCode,
                        style = MaterialTheme.typography.headlineMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Text(
                    stringResource(R.string.agent_sign_in_page, code.verificationUrl),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            note = if (SignInPage.open(context, code.verificationUrl)) {
                                null
                            } else {
                                Strings.get(R.string.agent_sign_in_open_failed, code.verificationUrl)
                            }
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.agent_sign_in_open)) }
                    OutlinedButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(code.userCode))
                            note = Strings.get(R.string.agent_sign_in_copied)
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.agent_sign_in_copy)) }
                    TextButton(
                        onClick = {
                            note = null
                            scope.launch { providers.cancelLogin(definition.id) }
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.agent_sign_in_cancel)) }
                }
                Text(
                    note ?: stringResource(R.string.agent_sign_in_waiting),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            is LoginState.Browser -> {
                // Opened by itself once, right after Sign in (as on desktop);
                // the button opens it again.
                LaunchedEffect(mine) {
                    if (providers.claimOpen(mine)) {
                        note = if (SignInPage.open(context, mine.url)) null else Strings.get(R.string.agent_sign_in_open_failed, mine.url)
                    }
                }
                Text(
                    stringResource(R.string.agent_chatgpt_waiting),
                    style = MaterialTheme.typography.bodyMedium,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            note = if (SignInPage.open(context, mine.url)) {
                                null
                            } else {
                                Strings.get(R.string.agent_sign_in_open_failed, mine.url)
                            }
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.agent_sign_in_open)) }
                    TextButton(
                        onClick = {
                            note = null
                            onPasted("")
                            scope.launch { providers.cancelLogin(definition.id) }
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.agent_sign_in_cancel)) }
                }
                OutlinedTextField(
                    value = pasted,
                    onValueChange = { if (it.length <= MAX_PASTED) onPasted(it) },
                    label = { Text(stringResource(R.string.agent_chatgpt_paste_label)) },
                    supportingText = {
                        Text(stringResource(R.string.agent_chatgpt_paste_help, "http://127.0.0.1:${ChatGptLogin.PORT}/"))
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = {
                        when (providers.submitCallback(pasted)) {
                            ChatGptLogin.Submitted.Accepted, ChatGptLogin.Submitted.Late, null -> {
                                note = null
                                onPasted("")
                            }
                            ChatGptLogin.Submitted.Invalid -> note = Strings.get(R.string.agent_chatgpt_paste_invalid)
                        }
                    },
                    enabled = pasted.isNotBlank(),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.agent_chatgpt_paste_continue)) }
                note?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
            is LoginState.Finishing -> Text(
                stringResource(R.string.agent_chatgpt_finishing),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            else -> {
                if (!connected) {
                    Text(
                        stringResource(
                            if (definition.id == ChatGptLogin.PROVIDER) R.string.agent_chatgpt_how else R.string.agent_sign_in_how,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val starting = mine is LoginState.Starting
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            note = null
                            scope.launch { providers.startLogin(definition.id) }
                        },
                        enabled = enabled && !starting,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        BusyLabel(
                            starting,
                            if (connected) R.string.agent_sign_in_again else R.string.agent_sign_in,
                            R.string.agent_signing_in,
                        )
                    }
                    if (starting) TextButton(
                        onClick = { scope.launch { providers.cancelLogin(definition.id) } },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.agent_sign_in_cancel)) }
                }
                val status = when (mine) {
                    is LoginState.Failed -> loginLine(definition.id, mine.failure)
                    is LoginState.Done -> Strings.get(R.string.agent_sign_in_done, definition.name)
                    else -> null
                }
                status?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        }
    }
}

/** What a provider's page is doing; one at a time. */
private enum class PageAction { Saving, Testing, Refreshing, RemovingKey }

/**
 * A button label that reads [busyRes] while its own action runs. It is
 * as wide as the wider of the two, so the row of buttons doesn't reflow
 * when it changes.
 */
@Composable
private fun BusyLabel(busy: Boolean, idleRes: Int, busyRes: Int, color: Color = Color.Unspecified) {
    val idle = stringResource(idleRes)
    val working = stringResource(busyRes)
    Box(contentAlignment = Alignment.Center) {
        Text(if (busy) idle else working, modifier = Modifier.alpha(0f).clearAndSetSemantics {})
        Text(if (busy) working else idle, color = color)
    }
}

/** A pasted callback address is a few hundred characters; anything longer isn't one. */
private const val MAX_PASTED = 8 * 1024

/** Past this many models the page offers a filter. */
private const val FILTER_FROM = 12

/**
 * One model: tapping the row makes it the default (a radio button to
 * TalkBack); the star adds or removes it from favourites. The id is
 * shown under a name that differs from it, so both are on screen.
 */
@Composable
private fun ModelRow(
    model: AgentModel,
    isDefault: Boolean,
    favorite: Boolean,
    onDefault: () -> Unit,
    onFavorite: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            // A radio button, with TalkBack's action naming the model.
            .semantics { selected = isDefault }
            .clickable(
                onClickLabel = stringResource(R.string.agent_model_use_default, model.name),
                role = Role.RadioButton,
                onClick = onDefault,
            )
            .padding(start = 4.dp),
    ) {
        RadioButton(selected = isDefault, onClick = null)
        Column(Modifier.weight(1f).padding(start = 12.dp, top = 6.dp, bottom = 6.dp)) {
            Text(model.name, style = MaterialTheme.typography.bodyLarge)
            if (model.name != model.id || isDefault) {
                Text(
                    if (isDefault) {
                        listOfNotNull(stringResource(R.string.agent_model_default), model.id.takeIf { it != model.name })
                            .joinToString(" · ")
                    } else {
                        model.id
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = { onFavorite(!favorite) }) {
            Icon(
                if (favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = stringResource(
                    if (favorite) R.string.agent_model_unfavorite else R.string.agent_model_favorite,
                    model.name,
                ),
                tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
