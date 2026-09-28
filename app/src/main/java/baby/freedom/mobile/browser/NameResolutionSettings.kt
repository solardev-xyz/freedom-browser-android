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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import baby.freedom.mobile.chains.RpcUrls
import baby.freedom.mobile.data.NodeSettings
import baby.freedom.mobile.ens.EnsQuorum
import baby.freedom.mobile.ens.EnsRpcConfig
import baby.freedom.mobile.ens.KeyedRpcProvider
import baby.freedom.mobile.ens.RpcEndpointCheck
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

internal const val SECTION_ENS = "Name resolution"
internal const val SECTION_RPC = "RPC providers"

private const val ENS_ABOUT =
    "How ENS (.eth), WNS (.wei) and GNS (.gwei) names are resolved. With Colibri proofs on, an answer is proven first: checked on this device against Ethereum's own consensus. An answer that can't be proven is cross-checked: it's only trusted when at least two RPC endpoints return exactly the same one. An answer only one endpoint gave is shown to you before anything loads."
private const val ROW_COLIBRI = "Colibri proofs"
private const val COLIBRI_HELP =
    "Each name's record is proven by corpus.core's Colibri prover (colibri-proof.tech) and the proof checked on this device against Ethereum's sync committee, so no server can make up the answer. The prover learns which contract storage a lookup reads; the reads themselves go to the endpoints in the resolution order. If no proof comes back within a few seconds, the endpoints are cross-checked instead. Off: every name is cross-checked."
private const val ROW_ORDER = "Resolution order"
private const val ORDER_HELP =
    "The first three providers in this order that are reachable read each name, so the order decides who answers; the rest are asked, in order, when those can't agree or don't answer. Two endpoints of one provider count once: the first that answers. Change it under RPC providers."
private const val ROW_CCIP = "Off-chain lookups (CCIP-Read)"
private const val CCIP_HELP =
    "Some names (base.eth and cb.id subnames, NameStone names) are answered by a gateway their resolver names. The gateway sees the name you look up. Off: those names don't resolve."

private const val SUB_CUSTOM = "Your endpoints"
private const val CUSTOM_HELP = "First in the resolution order, as listed here. These are Ethereum's own RPCs (Settings → Chains → Ethereum), so every mainnet read uses them too."
private const val ROW_ADD_ENDPOINT = "Add endpoint"
private const val SUB_KEYED = "Keyed providers"
private const val KEYED_HELP = "Next in the resolution order, with your own API key."
private const val SUB_PUBLIC = "Public endpoints"
private const val PUBLIC_HELP = "Last in the resolution order. Switch off any you'd rather not send lookups to."
private const val LAST_ENDPOINT_HELP = "At least one endpoint has to stay on."

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
    val which = if (colibri) "an answer Colibri can't prove" else "answers"
    val asked = if (colibri) "you'll be asked before that name loads" else "you'll be asked before each name loads"
    val isnt = if (colibri) "isn't" else "aren't"
    return when {
        endpoints > enabled ->
            "The $endpoints endpoints that are on come from only $enabled ${if (enabled == 1) "provider" else "different providers"}, and a provider's answer counts once. Cross-checking needs ${EnsQuorum.MIN_PROVIDERS} different providers (to agree on a block), so $which $isnt cross-checked: $asked."
        enabled == 1 ->
            "Only one endpoint is on, so $which can't be cross-checked: $asked. Turn on ${EnsQuorum.MIN_PROVIDERS} or more to cross-check."
        else ->
            "Only $enabled endpoints are on. Cross-checking needs ${EnsQuorum.MIN_PROVIDERS} (to agree on a block), so $which $isnt cross-checked: $asked."
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
    settingsRow("colibri", ROW_COLIBRI, if (config.colibri) "On" else "Off", COLIBRI_HELP, "proof", "verified"),
    settingsRow("ccip", ROW_CCIP, if (config.ccipRead) "On" else "Off", CCIP_HELP, "EIP-3668"),
)

private fun keyedSubtitle(config: EnsRpcConfig, provider: KeyedRpcProvider): String =
    config.apiKeys[provider.id]?.let { "Key ${EnsRpcConfig.maskKey(it)}" } ?: "No key"

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
        "API key",
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
        Toast.makeText(context, "Not changed: $why", Toast.LENGTH_SHORT).show()
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
                        ) { Icon(Icons.Filled.ArrowUpward, contentDescription = "Move $url up") }
                        IconButton(
                            onClick = { scope.launch { report(settings.moveEnsRpcEndpoint(url, +1)) } },
                            enabled = i < custom.lastIndex,
                        ) { Icon(Icons.Filled.ArrowDownward, contentDescription = "Move $url down") }
                    }
                    IconButton(
                        onClick = {
                            scope.launch { report(settings.removeEnsRpcEndpoint(url)) }
                        },
                        enabled = config.canRemoveCustom(url),
                    ) { Icon(Icons.Filled.Close, contentDescription = "Remove $url") }
                }
            }
            if (custom.size < EnsRpcConfig.MAX_CUSTOM_ENDPOINTS) {
                PageRow(
                    title = ROW_ADD_ENDPOINT,
                    subtitle = "Your own node or a provider URL",
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
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
                        onCheckedChange = { enable ->
                            scope.launch {
                                report(settings.setPublicEnsRpcEnabled(url, enable))
                            }
                        },
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
                        NodeSettings.AddEndpointResult.DUPLICATE -> "Not added: already in your endpoints"
                        NodeSettings.AddEndpointResult.PUBLIC -> "Not added: ${publicEndpointHint(url)}"
                        NodeSettings.AddEndpointResult.FULL ->
                            "Not added: at most ${EnsRpcConfig.MAX_CUSTOM_ENDPOINTS} endpoints"
                        NodeSettings.AddEndpointResult.INVALID -> "Not added: not a valid endpoint URL"
                        NodeSettings.AddEndpointResult.FAILED -> "Not added: couldn't save it. Try again."
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
                    keyError = ensEditError(edit)?.let { "Not saved: $it" }
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
    Text(text, fontWeight = FontWeight.Medium)
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
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            HelpText(help)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}

/** Why a name-resolution write didn't happen, for the user; `null` once it did. */
internal fun ensEditError(edit: NodeSettings.EnsEdit): String? = when (edit) {
    NodeSettings.EnsEdit.DONE -> null
    NodeSettings.EnsEdit.LAST_ENDPOINT -> LAST_ENDPOINT_HELP
    NodeSettings.EnsEdit.FAILED -> "couldn't save it. Try again."
}

/** The same hints as the chain page's RPC field: it's the same list ([rpcUrlHint]). */
private fun endpointHint(rejection: RpcUrls.Rejection): String =
    if (rejection == RpcUrls.Rejection.EMPTY) "An Ethereum mainnet JSON-RPC URL" else rpcUrlHint(rejection)

/** The "Test" button's verdict, as the dialogs show it. */
private fun checkLabel(outcome: RpcEndpointCheck.Outcome): String = when (outcome) {
    is RpcEndpointCheck.Outcome.Ok -> "Works: Ethereum mainnet, ${outcome.latencyMs} ms"
    is RpcEndpointCheck.Outcome.WrongChain ->
        "Not Ethereum mainnet (chain ${outcome.chainId}) — names won't resolve"
    is RpcEndpointCheck.Outcome.Failed -> "Didn't answer: ${outcome.message}"
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
        ) { Text(if (testing) "Testing…" else "Test") }
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
    val by = host?.let { "already provided by $it" } ?: "already provided"
    return "$by, one of the built-in public endpoints; turn it on or off under $SUB_PUBLIC"
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
    val what = if (twin.kind == EnsRpcConfig.Kind.KEYED) {
        "your ${twin.label} key"
    } else {
        "your endpoint ${EnsRpcConfig.redact(twin.url)}"
    }
    // Not [asked]: [twin] is on this very host, the same server.
    if (!asked) return "Not asked: same server as $what, which is asked in its place"
    return "Same provider as $what: one vote between them — this one casts it when that one fails to answer"
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
                    label = { Text("RPC URL") },
                    placeholder = { Text("https://your-node.example") },
                    isError = draft.isNotBlank() && url == null,
                    supportingText = {
                        Text(
                            when {
                                duplicate -> "Already in your endpoints"
                                public -> publicEndpointHint(validation.url).replaceFirstChar { it.uppercase() }
                                draft.isNotBlank() && validation.rejection != null ->
                                    endpointHint(validation.rejection)
                                url != null && url.startsWith("http://", ignoreCase = true) ->
                                    "Unencrypted http://, allowed only to a node on this device"
                                else -> "An Ethereum mainnet JSON-RPC URL"
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
            TextButton(onClick = { url?.let(onAdd) }, enabled = url != null) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
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
                    label = { Text("API key") },
                    isError = key.isNotEmpty() && !usable,
                    supportingText = {
                        Text(
                            if (key.isNotEmpty() && !usable) {
                                "Just the key, not the whole URL"
                            } else {
                                "Stored on this device only"
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
                                contentDescription = if (reveal) "Hide key" else "Show key",
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
                        "Get a key: ${provider.website}",
                        textDecoration = TextDecoration.Underline,
                    )
                }
                if (savedKey.isNotEmpty()) {
                    TextButton(
                        onClick = { onSave("") },
                        enabled = canRemove,
                    ) {
                        Text(
                            if (canRemove) "Remove key" else "Remove key — $LAST_ENDPOINT_HELP",
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
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
