package baby.freedom.mobile.browser

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import baby.freedom.mobile.R
import baby.freedom.mobile.chains.RpcUrls
import baby.freedom.mobile.data.NodeSettings
import baby.freedom.mobile.ens.EnsQuorum
import baby.freedom.mobile.ens.EnsRpcConfig
import baby.freedom.mobile.ens.KeyedRpcProvider
import baby.freedom.mobile.ens.RpcEndpointCheck
import baby.freedom.mobile.l10n.Strings
import baby.freedom.mobile.ui.isLight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Settings → Name resolution and → RPC providers (#102): the desktop
 * browser's pages of the same names, cut to what the Android resolver
 * does: a Colibri proof first (#100), then the RPC quorum (#96) over the
 * endpoints below, in their order. Every
 * edit goes to [NodeSettings.ensRpcConfig], which the resolver reads
 * for each lookup, so it applies to the next name without a restart.
 */

internal val SECTION_ENS: String get() = Strings.get(R.string.names_section_ens)
internal val SECTION_RPC: String get() = Strings.get(R.string.names_section_rpc)

private val ENS_ABOUT: String get() = Strings.get(R.string.names_ens_about)
private val ROW_COLIBRI: String get() = Strings.get(R.string.names_colibri_title)
private val COLIBRI_HELP: String get() = Strings.get(R.string.names_colibri_help)
private val ROW_ORDER: String get() = Strings.get(R.string.names_order_title)
private val ORDER_HELP: String get() = Strings.get(R.string.names_order_help)
private val ROW_CCIP: String get() = Strings.get(R.string.names_ccip_title)
private val CCIP_HELP: String get() = Strings.get(R.string.names_ccip_help)

private val SUB_CUSTOM: String get() = Strings.get(R.string.names_custom_title)
private val CUSTOM_HELP: String get() = Strings.get(R.string.names_custom_help)
private val ROW_ADD_ENDPOINT: String get() = Strings.get(R.string.names_add_endpoint)
private val SUB_KEYED: String get() = Strings.get(R.string.names_keyed_title)
private val KEYED_HELP: String get() = Strings.get(R.string.names_keyed_help)
private val SUB_PUBLIC: String get() = Strings.get(R.string.names_public_title)
private val PUBLIC_HELP: String get() = Strings.get(R.string.names_public_help)
private val LAST_ENDPOINT_HELP: String get() = Strings.get(R.string.names_last_endpoint_help)

/**
 * The warning under the lists when the quorum (#96) can't run: fewer
 * than [EnsQuorum.MIN_PROVIDERS] different providers ([enabled],
 * [EnsRpcConfig.providerCount]) among the [endpoints] that are on leaves
 * every answer one server's word, which the browser then asks about each
 * time — every answer the Colibri verifier can't prove, when Colibri
 * proofs are on ([colibri], #100). `null` when there are enough.
 */
internal fun tooFewEndpointsHint(enabled: Int, endpoints: Int = enabled, colibri: Boolean = false): String? {
    if (enabled >= EnsQuorum.MIN_PROVIDERS) return null
    // A proven answer (#100) needs no cross-check and loads without
    // asking; only one Colibri can't prove falls to a single endpoint.
    val min = EnsQuorum.MIN_PROVIDERS
    return when {
        endpoints > enabled -> Strings.plural(
            if (colibri) R.plurals.names_too_few_providers_colibri else R.plurals.names_too_few_providers,
            enabled,
            endpoints,
            enabled,
            min,
        )
        enabled == 1 -> Strings.get(
            if (colibri) R.string.names_one_endpoint_colibri else R.string.names_one_endpoint,
            min,
        )
        else -> Strings.plural(
            if (colibri) R.plurals.names_too_few_endpoints_colibri else R.plurals.names_too_few_endpoints,
            enabled,
            enabled,
            min,
        )
    }
}

/** "Ordered" line for [EnsRpcConfig.Source]: never shows an API key. */
private fun sourceLine(source: EnsRpcConfig.Source): String = when (source.kind) {
    EnsRpcConfig.Kind.KEYED -> "${source.label} · ${EnsRpcConfig.redact(source.url)}"
    else -> "${source.label} · ${source.url}"
}

internal fun ensSectionRows(config: EnsRpcConfig) = listOf(
    settingsRow("about", ENS_ABOUT),
    settingsRow(
        "order",
        ROW_ORDER,
        ORDER_HELP,
        tooFewEndpointsHint(config.providerCount, config.sources.size, config.colibri),
        *config.sources.map(::sourceLine).toTypedArray(),
    ),
    settingsRow(
        "colibri",
        ROW_COLIBRI,
        onOff(config.colibri),
        COLIBRI_HELP,
        Strings.get(R.string.names_search_proof),
        Strings.get(R.string.names_search_verified),
    ),
    settingsRow("ccip", ROW_CCIP, onOff(config.ccipRead), CCIP_HELP, "EIP-3668"),
)

private fun onOff(on: Boolean): String = Strings.get(if (on) R.string.names_on else R.string.names_off)

private fun keyedSubtitle(config: EnsRpcConfig, provider: KeyedRpcProvider): String =
    config.apiKeys[provider.id]?.let { Strings.get(R.string.names_key_masked, EnsRpcConfig.maskKey(it)) }
        ?: Strings.get(R.string.names_no_key)

internal fun rpcSectionRows(config: EnsRpcConfig) = listOf(
    settingsRow(
        "custom",
        SUB_CUSTOM,
        CUSTOM_HELP,
        ROW_ADD_ENDPOINT,
        *config.customEndpoints.toTypedArray(),
    ),
) + EnsRpcConfig.KEYED_PROVIDERS.map { provider ->
    settingsRow(
        "keyed:${provider.id}",
        SUB_KEYED,
        provider.name,
        keyedSubtitle(config, provider),
        Strings.get(R.string.names_api_key),
    )
} + settingsRow(
    "public",
    SUB_PUBLIC,
    PUBLIC_HELP,
    *EnsRpcConfig.PUBLIC_ENDPOINTS.toTypedArray(),
)

@Composable
internal fun NameResolutionSection(
    visible: Set<Any>,
    config: EnsRpcConfig,
    settings: NodeSettings,
) {
    val scope = rememberCoroutineScope()
    SectionCard(title = SECTION_ENS) {
        if ("about" in visible) HelpText(ENS_ABOUT)
        if ("order" in visible) {
            if ("about" in visible) Spacer(Modifier.height(12.dp))
            Text(ROW_ORDER, fontWeight = FontWeight.Medium)
            HelpText(ORDER_HELP)
            Spacer(Modifier.height(6.dp))
            config.sources.forEachIndexed { i, source ->
                Row(modifier = Modifier.padding(vertical = 2.dp)) {
                    Text(
                        "${i + 1}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.widthIn(min = 22.dp),
                    )
                    // In full, wrapping: the end of a URL is what tells
                    // two endpoints of one provider apart.
                    Text(
                        sourceLine(source),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            tooFewEndpointsHint(config.providerCount, config.sources.size, config.colibri)?.let {
                Spacer(Modifier.height(6.dp))
                WarningText(it)
            }
        }
        if ("colibri" in visible) {
            if ("about" in visible || "order" in visible) Spacer(Modifier.height(12.dp))
            SwitchRow(
                title = ROW_COLIBRI,
                help = COLIBRI_HELP,
                checked = config.colibri,
                enabled = true,
                onCheckedChange = { on -> scope.launch { settings.setEnsColibri(on) } },
            )
        }
        if ("ccip" in visible) {
            if ("about" in visible || "order" in visible || "colibri" in visible) Spacer(Modifier.height(12.dp))
            SwitchRow(
                title = ROW_CCIP,
                help = CCIP_HELP,
                checked = config.ccipRead,
                enabled = true,
                onCheckedChange = { on -> scope.launch { settings.setEnsCcipRead(on) } },
            )
        }
    }
}

@Composable
internal fun RpcProvidersSection(
    visible: Set<Any>,
    config: EnsRpcConfig,
    settings: NodeSettings,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var addingEndpoint by remember { mutableStateOf(false) }
    var addError by remember { mutableStateOf<String?>(null) }
    var editingProvider by remember { mutableStateOf<KeyedRpcProvider?>(null) }
    var keyError by remember { mutableStateOf<String?>(null) }

    // `NodeSettings` refuses a write that would leave no endpoint (the
    // greyed-out controls can still race it, e.g. a double tap before
    // recomposition), and a write can fail; say so rather than look as
    // if it was saved.
    fun report(edit: NodeSettings.EnsEdit) {
        val why = ensEditError(edit) ?: return
        Toast.makeText(context, Strings.get(R.string.names_not_changed, why), Toast.LENGTH_SHORT).show()
    }

    SectionCard(title = SECTION_RPC) {
        var first = true
        fun gap(): Boolean = (!first).also { first = false }

        if ("custom" in visible) {
            gap()
            SubHeader(SUB_CUSTOM)
            HelpText(CUSTOM_HELP)
            val custom = config.customEndpoints
            custom.forEachIndexed { i, url ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        url,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    if (custom.size > 1) {
                        IconButton(
                            onClick = { scope.launch { report(settings.moveEnsRpcEndpoint(url, -1)) } },
                            enabled = i > 0,
                        ) { Icon(Icons.Filled.ArrowUpward, contentDescription = stringResource(R.string.names_move_up, url)) }
                        IconButton(
                            onClick = { scope.launch { report(settings.moveEnsRpcEndpoint(url, +1)) } },
                            enabled = i < custom.lastIndex,
                        ) { Icon(Icons.Filled.ArrowDownward, contentDescription = stringResource(R.string.names_move_down, url)) }
                    }
                    IconButton(
                        onClick = {
                            scope.launch { report(settings.removeEnsRpcEndpoint(url)) }
                        },
                        enabled = config.canRemoveCustom(url),
                    ) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.names_remove_item, url)) }
                }
            }
            if (custom.size < EnsRpcConfig.MAX_CUSTOM_ENDPOINTS) {
                PageRow(
                    title = ROW_ADD_ENDPOINT,
                    subtitle = stringResource(R.string.names_add_endpoint_subtitle),
                    style = PageRowStyle.Inset,
                    leadingIcon = Icons.Filled.Add,
                    onClick = {
                        addError = null
                        addingEndpoint = true
                    },
                )
            }
        }

        val keyedVisible = EnsRpcConfig.KEYED_PROVIDERS.filter { "keyed:${it.id}" in visible }
        if (keyedVisible.isNotEmpty()) {
            if (gap()) Spacer(Modifier.height(12.dp))
            SubHeader(SUB_KEYED)
            HelpText(KEYED_HELP)
            for (provider in keyedVisible) {
                PageRow(
                    title = provider.name,
                    subtitle = keyedSubtitle(config, provider),
                    style = PageRowStyle.Inset,
                    leadingIcon = Icons.Filled.Key,
                    onClick = {
                        keyError = null
                        editingProvider = provider
                    },
                )
            }
        }

        if ("public" in visible) {
            if (gap()) Spacer(Modifier.height(12.dp))
            SubHeader(SUB_PUBLIC)
            HelpText(PUBLIC_HELP)
            for (url in EnsRpcConfig.PUBLIC_ENDPOINTS) {
                val on = url !in config.disabledPublicEndpoints
                // Switching off the last endpoint would leave nothing
                // to resolve with.
                val locked = on && !config.canDisablePublic(url)
                val onToggle: (Boolean) -> Unit = { enable ->
                    scope.launch {
                        report(settings.setPublicEnsRpcEnabled(url, enable))
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .switchRow(checked = on, onCheckedChange = onToggle, enabled = !locked),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            url,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        config.publicTwinOf(url)?.takeIf { on }?.let { twin ->
                            Text(
                                publicTwinHelp(twin, asked = url in config.endpoints),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (locked) {
                            Text(
                                LAST_ENDPOINT_HELP,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = on,
                        enabled = !locked,
                        onCheckedChange = null,
                    )
                }
            }
            tooFewEndpointsHint(config.providerCount, config.sources.size, config.colibri)?.let {
                Spacer(Modifier.height(6.dp))
                WarningText(it)
            }
        }
    }

    if (addingEndpoint) {
        AddEndpointDialog(
            isDuplicate = config::hasCustomEndpoint,
            isPublic = config::isPublicEndpoint,
            saveError = addError,
            onAdd = { url ->
                addError = null
                scope.launch {
                    // The dialog closes only once the endpoint is saved.
                    addError = when (settings.addEnsRpcEndpoint(url)) {
                        NodeSettings.AddEndpointResult.ADDED -> {
                            addingEndpoint = false
                            null
                        }
                        NodeSettings.AddEndpointResult.DUPLICATE -> Strings.get(R.string.names_not_added_duplicate)
                        NodeSettings.AddEndpointResult.PUBLIC ->
                            Strings.get(R.string.names_not_added_reason, publicEndpointHint(url))
                        NodeSettings.AddEndpointResult.FULL -> Strings.plural(
                            R.plurals.names_not_added_full,
                            EnsRpcConfig.MAX_CUSTOM_ENDPOINTS,
                            EnsRpcConfig.MAX_CUSTOM_ENDPOINTS,
                        )
                        NodeSettings.AddEndpointResult.INVALID -> Strings.get(R.string.names_not_added_invalid)
                        NodeSettings.AddEndpointResult.FAILED -> Strings.get(R.string.names_not_added_failed)
                    }
                }
            },
            onDismiss = { addingEndpoint = false },
        )
    }
    editingProvider?.let { provider ->
        val saved = config.apiKeys[provider.id].orEmpty()
        ApiKeyDialog(
            provider = provider,
            savedKey = saved,
            // Removing the key takes that provider's endpoint away.
            canRemove = saved.isEmpty() || config.canRemoveKey(provider.id),
            saveError = keyError,
            onSave = { key ->
                keyError = null
                scope.launch {
                    // The dialog closes only once the change is saved.
                    val edit = settings.setRpcApiKey(provider.id, key)
                    keyError = ensEditError(edit)?.let { Strings.get(R.string.names_not_saved, it) }
                    if (keyError == null) editingProvider = null
                }
            },
            onDismiss = { editingProvider = null },
        )
    }
}

/** A dialog's "that wasn't saved" line; the dialog stays open under it. */
@Composable
private fun SaveErrorText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun WarningText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun SubHeader(text: String) {
    Text(text, fontWeight = FontWeight.Medium, modifier = Modifier.semantics { heading() })
}

@Composable
private fun HelpText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SwitchRow(
    title: String,
    help: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .switchRow(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            HelpText(help)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, enabled = enabled, onCheckedChange = null)
    }
}

/** Why a name-resolution write didn't happen, for the user; `null` once it did. */
internal fun ensEditError(edit: NodeSettings.EnsEdit): String? = when (edit) {
    NodeSettings.EnsEdit.DONE -> null
    NodeSettings.EnsEdit.LAST_ENDPOINT -> LAST_ENDPOINT_HELP
    NodeSettings.EnsEdit.FAILED -> Strings.get(R.string.names_edit_failed)
}

/** The same hints as the chain page's RPC field: it's the same list ([rpcUrlHint]). */
private fun endpointHint(rejection: RpcUrls.Rejection): String =
    if (rejection == RpcUrls.Rejection.EMPTY) Strings.get(R.string.names_endpoint_hint) else rpcUrlHint(rejection)

/** The "Test" button's verdict, as the dialogs show it. */
private fun checkLabel(outcome: RpcEndpointCheck.Outcome): String = when (outcome) {
    is RpcEndpointCheck.Outcome.Ok -> Strings.get(R.string.names_check_works, outcome.latencyMs)
    is RpcEndpointCheck.Outcome.WrongChain -> Strings.get(R.string.names_check_wrong_chain, outcome.chainId)
    is RpcEndpointCheck.Outcome.Failed -> Strings.get(R.string.names_check_failed, outcome.message)
}

/**
 * "Test" + its verdict under a dialog's field. [url] is `null` while
 * the field doesn't hold something testable; editing the field clears
 * the verdict (the caller keys this on the text).
 */
@Composable
private fun EndpointTestRow(url: String?) {
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<RpcEndpointCheck.Outcome?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            enabled = url != null && !testing,
            onClick = {
                val target = url ?: return@TextButton
                testing = true
                outcome = null
                scope.launch {
                    outcome = withContext(Dispatchers.IO) { RpcEndpointCheck.check(target) }
                    testing = false
                }
            },
        ) { Text(stringResource(if (testing) R.string.names_testing else R.string.names_test)) }
        outcome?.let {
            Text(
                checkLabel(it),
                style = MaterialTheme.typography.bodySmall,
                color = if (it is RpcEndpointCheck.Outcome.Ok) {
                    // A darker green on the light scheme, where the bright
                    // one doesn't read on the pale card (as UnverifiedWarning).
                    if (MaterialTheme.colorScheme.isLight) Color(0xFF15803D) else Color(0xFF22C55E)
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
    }
}

/**
 * Why [url] isn't taken as one of yours: it's on the host of a built-in
 * public endpoint ([EnsRpcConfig.publicEndpointHost]), whatever its path
 * or query — the same server, already asked under its own switch.
 */
internal fun publicEndpointHint(url: String): String {
    val host = (EnsRpcConfig.normalizeEndpoint(url) ?: url).let(EnsRpcConfig::publicEndpointHost)
    return if (host != null) {
        Strings.get(R.string.names_public_endpoint_hint_host, host, SUB_PUBLIC)
    } else {
        Strings.get(R.string.names_public_endpoint_hint, SUB_PUBLIC)
    }
}

/**
 * Under a public endpoint's switch, when [twin] — one of yours or a
 * keyed provider, run by the same provider — is asked ahead of it
 * ([EnsRpcConfig.publicTwinOf]): one provider is one vote, cast by
 * the first of the two in that order that answers — the head probe
 * and, if [twin]'s record read fails, the read too. [asked] false: the
 * public one isn't asked at all, [twin] being on its very host.
 */
internal fun publicTwinHelp(twin: EnsRpcConfig.Source, asked: Boolean = true): String {
    val keyed = twin.kind == EnsRpcConfig.Kind.KEYED
    val what = if (keyed) twin.label else EnsRpcConfig.redact(twin.url)
    // Not [asked]: [twin] is on this very host, the same server.
    if (!asked) {
        return Strings.get(if (keyed) R.string.names_twin_not_asked_key else R.string.names_twin_not_asked_endpoint, what)
    }
    return Strings.get(if (keyed) R.string.names_twin_same_provider_key else R.string.names_twin_same_provider_endpoint, what)
}

@Composable
private fun AddEndpointDialog(
    isDuplicate: (String) -> Boolean,
    isPublic: (String) -> Boolean,
    saveError: String?,
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    val validation = EnsRpcConfig.validateEndpoint(draft)
    val duplicate = validation.url != null && isDuplicate(validation.url)
    val public = validation.url != null && !duplicate && isPublic(validation.url)
    val url = validation.url?.takeIf { !duplicate && !public }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ROW_ADD_ENDPOINT) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text(stringResource(R.string.names_rpc_url)) },
                    placeholder = { Text(stringResource(R.string.names_endpoint_placeholder)) },
                    isError = draft.isNotBlank() && url == null,
                    supportingText = {
                        Text(
                            when {
                                duplicate -> stringResource(R.string.names_endpoint_duplicate)
                                public -> publicEndpointHint(validation.url).replaceFirstChar { it.uppercase() }
                                draft.isNotBlank() && validation.rejection != null ->
                                    endpointHint(validation.rejection)
                                url != null && url.startsWith("http://", ignoreCase = true) ->
                                    stringResource(R.string.names_endpoint_unencrypted)
                                else -> stringResource(R.string.names_endpoint_hint)
                            },
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        autoCorrectEnabled = false,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                androidx.compose.runtime.key(url) { EndpointTestRow(url) }
                saveError?.let { SaveErrorText(it) }
            }
        },
        confirmButton = {
            TextButton(onClick = { url?.let(onAdd) }, enabled = url != null) {
                Text(stringResource(R.string.common_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

@Composable
private fun ApiKeyDialog(
    provider: KeyedRpcProvider,
    savedKey: String,
    canRemove: Boolean,
    saveError: String?,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(savedKey) }
    // Masked like the settings row (•••• + last four); the eye shows it.
    var reveal by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val key = draft.trim()
    // A key is a path segment: nothing that would change the URL's shape.
    val usable = key.isNotEmpty() && key.none { it.isWhitespace() || it in "/?#%@" }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(provider.name) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text(stringResource(R.string.names_api_key)) },
                    isError = key.isNotEmpty() && !usable,
                    supportingText = {
                        Text(
                            if (key.isNotEmpty() && !usable) {
                                stringResource(R.string.names_api_key_not_url)
                            } else {
                                stringResource(R.string.names_api_key_stored)
                            },
                        )
                    },
                    singleLine = true,
                    visualTransformation = if (reveal) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { reveal = !reveal }) {
                            Icon(
                                if (reveal) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = stringResource(
                                    if (reveal) R.string.names_hide_key else R.string.names_show_key,
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
                androidx.compose.runtime.key(key) {
                    EndpointTestRow(if (usable) provider.urlFor(key) else null)
                }
                TextButton(
                    onClick = {
                        try {
                            uriHandler.openUri(provider.website)
                        } catch (e: Exception) {
                            // No app to open it (IllegalStateException /
                            // ActivityNotFoundException): say where to go.
                            Toast.makeText(context, provider.website, Toast.LENGTH_LONG).show()
                        }
                    },
                ) {
                    Icon(Icons.Filled.Link, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.names_get_a_key, provider.website),
                        textDecoration = TextDecoration.Underline,
                    )
                }
                if (savedKey.isNotEmpty()) {
                    TextButton(
                        onClick = { onSave("") },
                        enabled = canRemove,
                    ) {
                        Text(
                            if (canRemove) {
                                stringResource(R.string.names_remove_key)
                            } else {
                                stringResource(R.string.names_remove_key_locked, LAST_ENDPOINT_HELP)
                            },
                            color = if (canRemove) MaterialTheme.colorScheme.error else Color.Unspecified,
                        )
                    }
                }
                saveError?.let { SaveErrorText(it) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(key) },
                enabled = usable && key != savedKey,
            ) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}
