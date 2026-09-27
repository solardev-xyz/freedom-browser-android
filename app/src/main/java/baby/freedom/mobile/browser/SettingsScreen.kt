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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Cookie
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import baby.freedom.mobile.data.NodeSettings
import baby.freedom.swarm.IpfsInfo
import baby.freedom.swarm.IpfsStatus
import kotlinx.coroutines.launch

/**
 * Full-screen settings page. Top to bottom:
 *
 *  0. **Search** — the address bar's search engine: the desktop set
 *     ([SearchEngines.BUILT_IN]) or a custom template (#87).
 *  1. **Browsing data** — wipe history, bookmarks, and WebView cookies /
 *     site storage / per-tab caches. Each action is guarded by a
 *     confirmation dialog.
 *  2. **Site permissions** — every camera / microphone / location
 *     decision (remembered, or this run's), each revocable (#81).
 *  3. **About** — app name, version, package, and a short blurb.
 *  4. **Other** — a single "Show advanced options" row. Tapping it
 *     flips [NodeSettings.showIpfsUi] on, which reveals an "IPFS node
 *     (experimental)" card below (status, peers, gateway URL, and
 *     routing preferences). This gate exists so IPFS support stays a
 *     demo surprise — `ipfs://` and `ens→ipfs` already work silently,
 *     but nothing in the UI hints at it until the user explicitly
 *     opts in.
 *
 * The Swarm node has its own dedicated page ([NodeScreen]) reachable
 * from the top-bar menu; it isn't duplicated here.
 */
@Composable
fun SettingsScreen(
    repo: BrowsingRepository,
    ipfsInfo: IpfsInfo,
    onIpfsToggle: (Boolean) -> Unit,
    onClearHistory: () -> Unit,
    onClearWebViewData: () -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)

    val history by remember { repo.history }.collectAsState(initial = emptyList())
    val bookmarks by remember { repo.bookmarks }.collectAsState(initial = emptyList())

    val context = LocalContext.current
    val settings = remember(context) { NodeSettings.get(context) }
    val showIpfsUi by settings.showIpfsUi.collectAsState(initial = false)
    val searchEngine by settings.searchEngine
        .collectAsState(initial = SearchEngines.DEFAULT_ID)
    val customSearchTemplate by settings.customSearchTemplate.collectAsState(initial = "")
    var pickSearchEngine by remember { mutableStateOf(false) }

    var confirmClearHistory by remember { mutableStateOf(false) }
    var confirmClearBookmarks by remember { mutableStateOf(false) }
    var confirmClearSiteData by remember { mutableStateOf(false) }

    val sitePermissions = remember(context) { SitePermissionBroker.get(context) }
    val permissionEntries by remember(sitePermissions) { sitePermissions.entries }
        .collectAsState(initial = emptyList())

    val scope = rememberCoroutineScope()

    FullScreenScaffold(
        title = "Settings",
        onDismiss = onDismiss,
    ) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item("search") {
                SearchSection(
                    engineId = searchEngine,
                    customTemplate = customSearchTemplate,
                    onClick = { pickSearchEngine = true },
                )
            }
            item("browsing") {
                BrowsingDataSection(
                    historyCount = history.size,
                    bookmarkCount = bookmarks.size,
                    onClearHistoryRequested = { confirmClearHistory = true },
                    onClearBookmarksRequested = { confirmClearBookmarks = true },
                    onClearSiteDataRequested = { confirmClearSiteData = true },
                )
            }
            item("permissions") {
                SitePermissionsSection(
                    entries = permissionEntries,
                    onRevoke = sitePermissions::revoke,
                )
            }
            item("about") {
                AboutSection()
            }
            item("other") {
                OtherSection(
                    showIpfsUi = showIpfsUi,
                    onToggleShowIpfsUi = { enabled ->
                        scope.launch { settings.setShowIpfsUi(enabled) }
                    },
                )
            }
            if (showIpfsUi) {
                item("ipfs") {
                    IpfsSection(
                        settings = settings,
                        ipfsInfo = ipfsInfo,
                        onIpfsToggle = onIpfsToggle,
                    )
                }
            }
        }
    }

    if (pickSearchEngine) {
        SearchEngineDialog(
            selectedId = searchEngine,
            savedCustomTemplate = customSearchTemplate,
            onSelectBuiltIn = { id ->
                scope.launch { settings.setSearchEngine(id) }
                pickSearchEngine = false
            },
            onSaveCustom = { template ->
                scope.launch { settings.setCustomSearchTemplate(template) }
                pickSearchEngine = false
            },
            onDismiss = { pickSearchEngine = false },
        )
    }
    if (confirmClearHistory) {
        ConfirmDialog(
            title = "Clear history?",
            message = "Removes every entry from the browsing history on this device.",
            confirmLabel = "Clear history",
            onConfirm = {
                repo.clearHistory()
                onClearHistory()
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
private fun SearchSection(
    engineId: String,
    customTemplate: String,
    onClick: () -> Unit,
) {
    // A `custom` id without a usable template searches with the default
    // ([SearchEngines.effectiveId]) — say so rather than claim "Custom".
    val isCustom = SearchEngines.effectiveId(engineId, customTemplate) == SearchEngines.CUSTOM_ID
    SectionCard(title = "Search") {
        PageRow(
            title = "Search engine",
            subtitle = SearchEngines.labelFor(engineId, customTemplate),
            style = PageRowStyle.Inset,
            leadingIcon = Icons.Filled.Search,
            // The whole template, wrapped — never cut, so it's readable
            // on the narrowest screen.
            thirdLine = if (isCustom) customTemplate else null,
            onClick = onClick,
        )
    }
}

/**
 * Radio list of the built-in engines plus "Custom". Tapping a built-in
 * applies it straight away; "Custom" reveals a template field and a
 * Save button that stays disabled until [SearchEngines.normalizeTemplate]
 * accepts the text, with the reason shown under the field.
 */
@Composable
private fun SearchEngineDialog(
    selectedId: String,
    savedCustomTemplate: String,
    onSelectBuiltIn: (String) -> Unit,
    onSaveCustom: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // Check the radio of the engine search actually uses — the same
    // resolver as the Settings row, so a stale `custom` shows DuckDuckGo
    // here too rather than a checked "Custom" the row doesn't name.
    val effectiveId = SearchEngines.effectiveId(selectedId, savedCustomTemplate)
    var customSelected by remember {
        mutableStateOf(effectiveId == SearchEngines.CUSTOM_ID)
    }
    var draft by remember { mutableStateOf(savedCustomTemplate) }
    val validation = SearchEngines.validateTemplate(draft)
    val normalized = validation.template

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Search engine") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                SearchEngines.BUILT_IN.forEach { engine ->
                    EngineRadioRow(
                        label = engine.label,
                        selected = !customSelected && effectiveId == engine.id,
                        onClick = { onSelectBuiltIn(engine.id) },
                    )
                }
                EngineRadioRow(
                    label = "Custom",
                    selected = customSelected,
                    onClick = { customSelected = true },
                )
                if (customSelected) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        label = { Text("Search URL") },
                        placeholder = { Text("https://example.com/search?q={searchTerms}") },
                        isError = draft.isNotBlank() && normalized == null,
                        supportingText = {
                            Text(
                                validation.rejection
                                    ?.takeIf { draft.isNotBlank() }
                                    ?.let(::templateHint)
                                    ?: "Your search replaces {searchTerms} (or %s)",
                            )
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            autoCorrectEnabled = false,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            if (customSelected) {
                TextButton(
                    onClick = { normalized?.let(onSaveCustom) },
                    enabled = normalized != null,
                ) { Text("Save") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/** What to fix, for each reason [SearchEngines.validateTemplate] refuses a template. */
private fun templateHint(rejection: SearchEngines.Rejection): String = when (rejection) {
    SearchEngines.Rejection.EMPTY,
    SearchEngines.Rejection.NO_PLACEHOLDER -> "Put {searchTerms} (or %s) where your search goes"
    SearchEngines.Rejection.MULTIPLE_PLACEHOLDERS -> "Use {searchTerms} (or %s) only once"
    SearchEngines.Rejection.TOO_LONG -> "Too long: at most 2048 characters"
    SearchEngines.Rejection.NOT_A_URL -> "Not a full URL: start with https:// and a host name"
    SearchEngines.Rejection.SCHEME ->
        "Needs https:// (http:// only to localhost, 127.0.0.1 or [::1])"
    SearchEngines.Rejection.USER_INFO -> "Remove the user name or password before the host"
}

@Composable
private fun EngineRadioRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(label)
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

/**
 * Site permissions (#81): one row per decision — the site, in full and
 * wrapping (never ellipsised: the end of a host is the part that
 * matters), the permission and its state, and a Remove button that
 * makes the site ask again next time. Session-only decisions are listed
 * too, so a Block or a dismissal embargo made this run can be lifted
 * without restarting the app.
 */
@Composable
private fun SitePermissionsSection(
    entries: List<SitePermissionEntry>,
    onRevoke: (SitePermissionEntry) -> Unit,
) {
    SectionCard(title = "Site permissions") {
        if (entries.isEmpty()) {
            Text(
                "Sites you allow or block from using your camera, microphone or location appear here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        for (entry in entries) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    when (entry.permission) {
                        SitePermission.CAMERA -> Icons.Filled.Videocam
                        SitePermission.MICROPHONE -> Icons.Filled.Mic
                        SitePermission.LOCATION -> Icons.Filled.LocationOn
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        permissionOriginDisplay(entry.origin),
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        "${entry.permission.label} · ${sitePermissionStateLabel(entry)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { onRevoke(entry) }) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Remove ${entry.permission.label} permission for ${permissionOriginDisplay(entry.origin)}",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** "Allowed", "Blocked (this session)", "Blocked after 3 dismissals (this session)". */
internal fun sitePermissionStateLabel(entry: SitePermissionEntry): String {
    val scope = if (entry.remembered) "" else " (this session)"
    return when {
        entry.embargoed ->
            "Blocked after ${PermissionSession.DISMISS_EMBARGO_THRESHOLD} dismissals$scope"
        entry.decision == PermissionDecision.ALLOW -> "Allowed$scope"
        else -> "Blocked$scope"
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
            "Loads regular https:// sites plus decentralised content via bzz:// hashes and ENS names (vitalik.eth), served through embedded nodes.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun OtherSection(
    showIpfsUi: Boolean,
    onToggleShowIpfsUi: (Boolean) -> Unit,
) {
    SectionCard(title = "Other") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Tune,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Show advanced options", fontWeight = FontWeight.Medium)
                Text(
                    "Experimental protocol settings",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = showIpfsUi,
                onCheckedChange = onToggleShowIpfsUi,
            )
        }
    }
}

// Intentionally not part of the default UI surface. Controls here are
// revealed by [OtherSection]'s toggle so that IPFS support stays a
// demo surprise until the user flips it on explicitly.
//
// The "IPFS" master toggle is purely derived from the live
// [IpfsInfo.status]: we never persist a "run IPFS" preference, so the
// toggle is always off at cold launch. Flipping it on calls
// [onIpfsToggle] which routes through `MainActivity` → `NodeService`
// to start/stop the IPFS node live.
@Composable
private fun IpfsSection(
    settings: NodeSettings,
    ipfsInfo: IpfsInfo,
    onIpfsToggle: (Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val routingMode by settings.ipfsRoutingMode
        .collectAsState(initial = NodeSettings.DEFAULT_IPFS_ROUTING_MODE)

    val triple = ipfsStatusTriple(ipfsInfo)
    val isOn = ipfsInfo.status != IpfsStatus.Stopped

    SectionCard(title = "IPFS") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(triple.icon, contentDescription = null, tint = triple.color)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("IPFS", fontWeight = FontWeight.Medium)
                Text(
                    triple.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = isOn,
                onCheckedChange = onIpfsToggle,
            )
        }

        if (ipfsInfo.gatewayUrl.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            // connectedPeers carries verified blocks fetched — the
            // reader has no peer set (see swarmnode IpfsNode).
            DetailRow("Blocks fetched", ipfsInfo.connectedPeers.toString())
            DetailRow("Gateway", ipfsInfo.gatewayUrl, mono = true)
            if (ipfsInfo.clientVersion.isNotBlank()) {
                DetailRow("Client", "freedom-ipfs/${ipfsInfo.clientVersion}", mono = true)
            }
        }
        val err = ipfsInfo.errorMessage
        if (!err.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            DetailRow("Error", err, singleLine = false)
        }

        Spacer(Modifier.height(12.dp))
        RoutingModePicker(
            selected = routingMode,
            onSelect = { mode ->
                scope.launch { settings.setIpfsRoutingMode(mode) }
            },
        )

        Spacer(Modifier.height(8.dp))
        Text(
            "Routing mode applies the next time IPFS " +
                "starts — toggle IPFS off and on to re-init.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun RoutingModePicker(
    selected: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Routing mode", fontWeight = FontWeight.Medium)
            Text(
                "Content discovery strategy",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = { expanded = true }) {
            Text(selected)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            NodeSettings.IPFS_ROUTING_MODES.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode) },
                    onClick = {
                        expanded = false
                        onSelect(mode)
                    },
                )
            }
        }
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

private data class IpfsStatusTriple(
    val color: Color,
    val icon: ImageVector,
    val label: String,
)

/**
 * Translate the live IPFS [IpfsInfo] into an icon + color + user-
 * facing label for the master IPFS toggle. Visible to the user:
 *
 *  - `Disconnected` — node not running (toggle off)
 *  - `Connecting…`  — node starting, or running but still peer-less
 *  - `Connected`    — node running with at least one peer
 *  - `Error`        — last start attempt threw
 */
private fun ipfsStatusTriple(info: IpfsInfo): IpfsStatusTriple = when (info.status) {
    // freedom-ipfs is an on-demand reader: the gateway being up means
    // the node is usable — there is no peer set to wait for.
    IpfsStatus.Running -> IpfsStatusTriple(
        Color(0xFF22C55E), Icons.Filled.CheckCircle, "Connected",
    )
    IpfsStatus.Starting -> IpfsStatusTriple(
        Color(0xFFF59E0B), Icons.Filled.HourglassTop, "Connecting…",
    )
    IpfsStatus.Stopped -> IpfsStatusTriple(
        Color(0xFF94A3B8), Icons.Filled.PowerSettingsNew, "Disconnected",
    )
    IpfsStatus.Error -> IpfsStatusTriple(
        Color(0xFFEF4444), Icons.Filled.ErrorOutline, "Error",
    )
}
