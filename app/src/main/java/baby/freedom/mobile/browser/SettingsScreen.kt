package baby.freedom.mobile.browser

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
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
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cookie
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import baby.freedom.mobile.R
import baby.freedom.mobile.chains.Chain
import baby.freedom.mobile.chains.BuiltInChains
import baby.freedom.mobile.data.BrowsingRepository
import baby.freedom.mobile.data.ChainStore
import baby.freedom.mobile.data.DappGrantStore
import baby.freedom.mobile.data.NodeSettings
import baby.freedom.mobile.ens.EnsRpcConfig
import baby.freedom.mobile.ui.isLight
import baby.freedom.mobile.wallet.Vault
import baby.freedom.mobile.wallet.WalletAccount
import baby.freedom.mobile.wallet.WalletAccounts
import baby.freedom.swarm.IpfsInfo
import baby.freedom.swarm.IpfsStatus
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Full-screen settings page. A search field pinned under the title
 * filters every section below by label and description (#93, see
 * [visibleSettingsRows]); Back clears a query before it closes the
 * page. Top to bottom:
 *
 *  −. **Wallet** — the one wallet on this device (#75, #76): its state
 *     and, until the phrase has been seen, the backup reminder. Opens
 *     [WalletScreen].
 *  0. **Search** — the address bar's search engine: the desktop set
 *     ([SearchEngines.BUILT_IN]) or a custom template (#87).
 *  0a. **Ad blocking** — the filter-list categories and the sites ad
 *     blocking is off for (#126, [Adblock]).
 *  0b. **Name resolution** and **RPC providers** — the resolution
 *     order, CCIP-Read, and the endpoints names resolve through: your
 *     own, keyed providers, the public ones (#102; see
 *     [NameResolutionSection]).
 *  1. **Browsing data** — wipe history, bookmarks, and WebView cookies /
 *     site storage / per-tab caches. Each action is guarded by a
 *     confirmation dialog.
 *  2. **Site permissions** — every camera / microphone / location
 *     decision (remembered, or this run's), each revocable (#81), and
 *     every "open <scheme>: links in another app" one (#85).
 *  3. **Nodes** — where `bzz://` and `ipfs://` content comes from: the
 *     embedded nodes, or an external Swarm endpoint / IPFS gateway the
 *     user runs (#125, [ExternalEndpoints]); and the embedded Radicle
 *     node's row, which opens its own page ([RadicleScreen], #73). The IPFS row shows only
 *     while advanced options are on, or once an external gateway is
 *     set — its unverified warning must stay in view while it's in use.
 *  4. **Chains** — Ethereum, Gnosis and Base, plus the user's custom
 *     chains, added from a chainlist.org search or by hand (#107, see
 *     [ChainsSection]). Its Add chain pages and each chain's page (its own
 *     RPCs and how reads are checked, #108) replace the list while open.
 *  5. **About** — app name, version, package, and a short blurb.
 *  6. **Other** — a single "Show advanced options" row. Tapping it
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
    radicle: RadicleControls = RadicleControls(),
    onOpenRadicle: () -> Unit = {},
    onOpenWallet: () -> Unit = {},
) {
    BackHandler(onBack = onDismiss)
    // Settings search (#93). Registered after the dismiss handler so it
    // wins while there's a query: Back clears the filter first and puts
    // the whole page back, like Esc in the desktop browser's field.
    var query by rememberSaveable { mutableStateOf("") }
    BackHandler(enabled = query.isNotEmpty()) { query = "" }

    val history by remember { repo.history }.collectAsState(initial = emptyList())
    val bookmarks by remember { repo.bookmarks }.collectAsState(initial = emptyList())

    val context = LocalContext.current
    val settings = remember(context) { NodeSettings.get(context) }
    val showIpfsUi by settings.showIpfsUi.collectAsState(initial = false)
    val searchEngine by settings.searchEngine
        .collectAsState(initial = SearchEngines.DEFAULT_ID)
    val customSearchTemplate by settings.customSearchTemplate.collectAsState(initial = "")
    var pickSearchEngine by remember { mutableStateOf(false) }
    val ensRpcConfig by settings.ensRpcConfig.collectAsState(initial = EnsRpcConfig())
    val externalSwarm by settings.externalSwarmEndpoint.collectAsState(initial = "")
    val externalIpfs by settings.externalIpfsGateway.collectAsState(initial = "")
    var editEndpoint by remember { mutableStateOf<NodeEndpoint?>(null) }
    val adblockCategories by settings.adblockCategories
        .collectAsState(initial = AdblockCategory.entries.filterTo(LinkedHashSet()) { it.enabledByDefault })
    val adblockAllowlist by settings.adblockAllowlist.collectAsState(initial = emptyList())
    val adblockStatus by Adblock.status.collectAsState()
    val adblockUpdate by Adblock.updateState.collectAsState()
    val adblockAutoUpdate by settings.adblockAutoUpdate.collectAsState(initial = true)
    var addAllowlistSite by remember { mutableStateOf(false) }
    val torEnabled by settings.torEnabled.collectAsState(initial = false)
    val torStartOnLaunch by settings.torStartOnLaunch.collectAsState(initial = false)

    var confirmClearHistory by remember { mutableStateOf(false) }
    var confirmClearBookmarks by remember { mutableStateOf(false) }
    var confirmClearSiteData by remember { mutableStateOf(false) }

    val sitePermissions = remember(context) { SitePermissionBroker.get(context) }
    val permissionEntries by remember(sitePermissions) { sitePermissions.entries }
        .collectAsState(initial = emptyList())
    // Sites connected to the wallet (#110), listed with the other site permissions (#111).
    val dappGrants by remember(context) { DappGrantStore.get(context).all }
        .collectAsState(initial = emptyList())
    val walletAccountList by remember(context) { WalletAccounts.get(context).accounts }.collectAsState()
    val walletAccounts = walletAccountList?.accounts.orEmpty()
    // The connected site whose page is open, by origin, so it follows the stored grant.
    var openSite by remember { mutableStateOf<String?>(null) }
    // The connected site whose × couldn't be saved: its row says so, as the site's page does.
    var disconnectFailed by remember { mutableStateOf<String?>(null) }

    val chainStore = remember(context) { ChainStore.get(context) }
    val chains by remember(chainStore) { chainStore.chains }
        .collectAsState(initial = BuiltInChains.ALL)
    var chainPage by remember { mutableStateOf<ChainPage?>(null) }
    var chainQuery by rememberSaveable { mutableStateOf("") }
    var confirmRemoveChain by remember { mutableStateOf<Chain?>(null) }
    var removeChainFailed by remember { mutableStateOf<Chain?>(null) }

    val vault = remember(context) { Vault.get(context) }
    val walletState by vault.state.collectAsState()

    val scope = rememberCoroutineScope()
    val appVersion = remember(context) { appVersionLabel(context) }

    // Each section's rows for the current query; an empty set hides the
    // section. The index is what the page shows right now (see
    // [SettingsRow]) — IPFS only while advanced options reveal it.
    val walletRows = visibleSettingsRows(query, SECTION_WALLET, walletSettingsRows(walletState))
    val searchRows = visibleSettingsRows(
        query, SECTION_SEARCH, searchSectionRows(searchEngine, customSearchTemplate),
    )
    val defaultBrowser = rememberDefaultBrowserState()
    val defaultBrowserRows = visibleSettingsRows(
        query, DefaultBrowser.SECTION, defaultBrowserRows(defaultBrowser.isDefault),
    )
    val adblockRows = visibleSettingsRows(
        query, SECTION_ADBLOCK,
        adblockSectionRows(adblockCategories, adblockAllowlist, adblockStatus, adblockUpdate),
    )
    val ensRows = visibleSettingsRows(query, SECTION_ENS, ensSectionRows(ensRpcConfig))
    val rpcRows = visibleSettingsRows(query, SECTION_RPC, rpcSectionRows(ensRpcConfig))
    val browsingRows = visibleSettingsRows(
        query, SECTION_BROWSING, browsingDataRows(history.size, bookmarks.size),
    )
    val permissionRows = visibleSettingsRows(
        query, SECTION_PERMISSIONS, sitePermissionRows(permissionEntries, dappGrants, walletAccounts, chains),
    )
    val nodeRows = visibleSettingsRows(
        query, SECTION_NODES,
        nodeRows(externalSwarm, externalIpfs, showIpfsUi) + radicleSettingsRow(radicle),
    )
    val torRows = visibleSettingsRows(query, SECTION_TOR, torRows(torEnabled, torStartOnLaunch))
    val chainRows = visibleSettingsRows(query, SECTION_CHAINS, chainSettingsRows(chains))
    val aboutRows = visibleSettingsRows(
        query, SECTION_ABOUT, aboutRows(appVersion, context.packageName),
    )
    val otherRows = visibleSettingsRows(query, SECTION_OTHER, otherRows())
    val ipfsRows = if (showIpfsUi) {
        visibleSettingsRows(query, SECTION_IPFS, ipfsRows(ipfsInfo))
    } else emptySet()
    val nothingMatches = listOf(
        walletRows, searchRows, defaultBrowserRows, adblockRows, ensRows, rpcRows, browsingRows, permissionRows, nodeRows,
        torRows,
        chainRows, aboutRows, otherRows, ipfsRows,
    ).all { it.isEmpty() }

    // A new query starts the results from the top, so the first match
    // isn't left scrolled off above the viewport.
    val listState = rememberLazyListState()
    LaunchedEffect(listState) {
        snapshotFlow { query }.drop(1).collect { listState.scrollToItem(0) }
    }

    // The Chains sub-pages stand in for the list while open; everything
    // above stays composed, so Back lands on the list as it was left.
    when (val page = chainPage) {
        ChainPage.Search -> ChainlistPage(
            query = chainQuery,
            onQueryChange = { chainQuery = it },
            existingIds = chains.mapTo(HashSet()) { it.id },
            onPick = { chainPage = ChainPage.Form(it.toChain()) },
            onManual = { chainPage = ChainPage.Form(null) },
            onBack = { chainPage = null },
        )
        is ChainPage.Form -> AddChainPage(
            prefill = page.prefill,
            onAdd = chainStore::add,
            onAdded = {
                chainPage = null
                chainQuery = ""
            },
            onBack = { chainPage = if (page.prefill != null) ChainPage.Search else null },
        )
        is ChainPage.Detail -> {
            val chain = chains.firstOrNull { it.id == page.chainId }
            if (chain != null) {
                ChainDetailPage(
                    chain = chain,
                    onAddRpc = { chainStore.addUserRpc(chain.id, it) },
                    onRemoveRpc = { url ->
                        if (chain.id == BuiltInChains.ETHEREUM.id) {
                            // Mainnet's own RPCs are name resolution's
                            // "Your endpoints" too (#102): the same
                            // last-endpoint check applies.
                            when (settings.removeEnsRpcEndpoint(url)) {
                                NodeSettings.EnsEdit.DONE -> null
                                NodeSettings.EnsEdit.LAST_ENDPOINT ->
                                    "Not removed: it's the last RPC names resolve through (Settings → RPC providers)"
                                NodeSettings.EnsEdit.FAILED -> "Couldn't remove the RPC. Try again."
                            }
                        } else if (chainStore.removeUserRpc(chain.id, url)) {
                            null
                        } else {
                            "Couldn't remove the RPC. Try again."
                        }
                    },
                    onRemove = { confirmRemoveChain = chain },
                    onBack = { chainPage = null },
                )
            } else {
                // Removed (from this page's Remove): back to the list.
                LaunchedEffect(page) { chainPage = null }
            }
        }
        null -> Unit
    }
    // A connected site's page (#111) stands in for the list the same way; it
    // closes by itself once the site is disconnected, from here or elsewhere.
    val site = openSite?.let { origin -> dappGrants.firstOrNull { it.origin == origin } }
    if (openSite != null && site == null) LaunchedEffect(openSite) { openSite = null }
    if (chainPage == null && site != null) {
        ConnectedSitePage(
            grant = site,
            accounts = walletAccounts,
            chains = chains,
            onDisconnect = { EthereumProviders.disconnect(context, it) },
            onBack = { openSite = null },
        )
    }
    if (chainPage == null && site == null) FullScreenScaffold(
        title = "Settings",
        onDismiss = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            SettingsSearchField(
                query = query,
                onQueryChange = { query = it },
            )
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (walletRows.isNotEmpty()) item("wallet") {
                    WalletSection(state = walletState, onOpen = onOpenWallet)
                }
                if (searchRows.isNotEmpty()) item("search") {
                    SearchSection(
                        engineId = searchEngine,
                        customTemplate = customSearchTemplate,
                        onClick = { pickSearchEngine = true },
                    )
                }
                if (defaultBrowserRows.isNotEmpty()) item("default-browser") {
                    DefaultBrowserSection(defaultBrowser)
                }
                if (adblockRows.isNotEmpty()) item("adblock") {
                    AdblockSection(
                        visible = adblockRows,
                        enabled = adblockCategories,
                        allowlist = adblockAllowlist,
                        status = adblockStatus,
                        update = adblockUpdate,
                        autoUpdate = adblockAutoUpdate,
                        onToggle = { category, on ->
                            scope.launch { settings.setAdblockCategory(category, on) }
                        },
                        onAutoUpdate = { on -> scope.launch { settings.setAdblockAutoUpdate(on) } },
                        onCheckUpdates = { Adblock.checkForUpdates() },
                        onRemoveSite = { site ->
                            Adblock.removeAllowlisted(site)
                        },
                        onAddSite = { addAllowlistSite = true },
                    )
                }
                if (ensRows.isNotEmpty()) item("ens") {
                    NameResolutionSection(
                        visible = ensRows,
                        config = ensRpcConfig,
                        settings = settings,
                    )
                }
                if (rpcRows.isNotEmpty()) item("rpc") {
                    RpcProvidersSection(
                        visible = rpcRows,
                        config = ensRpcConfig,
                        settings = settings,
                    )
                }
                if (browsingRows.isNotEmpty()) item("browsing") {
                    BrowsingDataSection(
                        visible = browsingRows,
                        historyCount = history.size,
                        bookmarkCount = bookmarks.size,
                        onClearHistoryRequested = { confirmClearHistory = true },
                        onClearBookmarksRequested = { confirmClearBookmarks = true },
                        onClearSiteDataRequested = { confirmClearSiteData = true },
                    )
                }
                if (permissionRows.isNotEmpty()) item("permissions") {
                    SitePermissionsSection(
                        visible = permissionRows,
                        entries = permissionEntries,
                        onRevoke = sitePermissions::revoke,
                        grants = dappGrants,
                        accounts = walletAccounts,
                        chains = chains,
                        onOpenSite = { openSite = it },
                        disconnectFailed = disconnectFailed,
                        onDisconnect = { origin ->
                            disconnectFailed = null
                            scope.launch {
                                if (!EthereumProviders.disconnect(context, origin)) disconnectFailed = origin
                            }
                        },
                    )
                }
                if (nodeRows.isNotEmpty()) item("nodes") {
                    NodesSection(
                        visible = nodeRows,
                        externalSwarm = externalSwarm,
                        externalIpfs = externalIpfs,
                        onEdit = { editEndpoint = it },
                        radicle = radicle,
                        onOpenRadicle = onOpenRadicle,
                    )
                }
                if (torRows.isNotEmpty()) item("tor") {
                    TorSettingsSection(
                        visible = torRows,
                        enabled = torEnabled,
                        startOnLaunch = torStartOnLaunch,
                        onEnabled = { on -> scope.launch { settings.setTorEnabled(on) } },
                        onStartOnLaunch = { on -> scope.launch { settings.setTorStartOnLaunch(on) } },
                    )
                }
                if (chainRows.isNotEmpty()) item("chains") {
                    ChainsSection(
                        visible = chainRows,
                        chains = chains,
                        onOpen = { chainPage = ChainPage.Detail(it.id) },
                        onRemove = { confirmRemoveChain = it },
                        onAdd = { chainPage = ChainPage.Search },
                    )
                }
                if (aboutRows.isNotEmpty()) item("about") {
                    AboutSection(visible = aboutRows, version = appVersion)
                }
                if (otherRows.isNotEmpty()) item("other") {
                    OtherSection(
                        showIpfsUi = showIpfsUi,
                        onToggleShowIpfsUi = { enabled ->
                            scope.launch { settings.setShowIpfsUi(enabled) }
                        },
                    )
                }
                if (ipfsRows.isNotEmpty()) item("ipfs") {
                    IpfsSection(
                        visible = ipfsRows,
                        settings = settings,
                        ipfsInfo = ipfsInfo,
                        onIpfsToggle = onIpfsToggle,
                    )
                }
                if (nothingMatches) item("no-match") {
                    Text(
                        "No settings match \u201c${query.trim()}\u201d",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 24.dp),
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
    editEndpoint?.let { endpoint ->
        EndpointDialog(
            endpoint = endpoint,
            saved = if (endpoint == NodeEndpoint.Swarm) externalSwarm else externalIpfs,
            onSave = { value ->
                scope.launch {
                    if (endpoint == NodeEndpoint.Swarm) settings.setExternalSwarmEndpoint(value)
                    else settings.setExternalIpfsGateway(value)
                }
                editEndpoint = null
            },
            onDismiss = { editEndpoint = null },
        )
    }
    confirmRemoveChain?.let { chain ->
        ConfirmDialog(
            title = "Remove ${chain.name}?",
            message = "Removes chain ${chain.id} and its RPC endpoints from this device. " +
                "You can add it again later.",
            confirmLabel = "Remove",
            onConfirm = {
                scope.launch {
                    if (chainStore.remove(chain.id) == ChainStore.RemoveResult.FAILED) {
                        removeChainFailed = chain
                    }
                }
                confirmRemoveChain = null
            },
            onDismiss = { confirmRemoveChain = null },
        )
    }
    removeChainFailed?.let { chain ->
        AlertDialog(
            onDismissRequest = { removeChainFailed = null },
            title = { Text("Couldn't remove ${chain.name}") },
            text = { Text("Chain ${chain.id} is still on this device. Try again.") },
            confirmButton = {
                TextButton(onClick = { removeChainFailed = null }) { Text("OK") }
            },
        )
    }
    if (addAllowlistSite) {
        AllowlistSiteDialog(
            onAdd = { site ->
                Adblock.setAllowlisted(site, allowed = true, private = false)
                addAllowlistSite = false
            },
            onDismiss = { addAllowlistSite = false },
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
            message = "Signs you out of most sites and wipes cached page data, cookies, form autofill, remembered page zoom levels and desktop-site choices from every open tab.",
            confirmLabel = "Clear site data",
            onConfirm = {
                onClearWebViewData()
                confirmClearSiteData = false
            },
            onDismiss = { confirmClearSiteData = false },
        )
    }
}

private const val SECTION_WALLET = "Wallet"
private const val SECTION_SEARCH = "Search"
private const val SECTION_ADBLOCK = "Ad blocking"
private const val SECTION_BROWSING = "Browsing data"
private const val SECTION_PERMISSIONS = "Site permissions"
private const val SECTION_NODES = "Nodes"
private const val SECTION_TOR = "Tor"
private const val SECTION_ABOUT = "About"
private const val SECTION_OTHER = "Other"
private const val SECTION_IPFS = "IPFS"

/**
 * The filter field above the sections (#93). Pinned under the title
 * bar rather than scrolled with the list, so the query stays in view
 * while reading the results; × clears it.
 */
@Composable
private fun SettingsSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text("Search settings") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = "Clear search")
                }
            }
        } else null,
        singleLine = true,
        shape = MaterialTheme.shapes.extraLarge,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

private const val ROW_SEARCH_ENGINE = "Search engine"

/**
 * The engine row, findable by the engine in use and by the name of
 * any engine it can be switched to ("google" → the Search engine row).
 */
private fun searchSectionRows(engineId: String, customTemplate: String) = listOf(
    settingsRow(
        "engine",
        ROW_SEARCH_ENGINE,
        SearchEngines.labelFor(engineId, customTemplate),
        customSearchTemplateLine(engineId, customTemplate),
        *SearchEngines.BUILT_IN.map { it.label }.toTypedArray(),
    ),
)

/** The custom template, shown under the engine row while it's in use. */
private fun customSearchTemplateLine(engineId: String, customTemplate: String): String? =
    // A `custom` id without a usable template searches with the default
    // ([SearchEngines.effectiveId]) — say so rather than claim "Custom".
    customTemplate.takeIf {
        SearchEngines.effectiveId(engineId, customTemplate) == SearchEngines.CUSTOM_ID
    }

/**
 * Settings → Wallet: one row with the wallet's state, and the backup
 * reminder (or no-screen-lock warning) as a line that stays under it —
 * wrapped, never cut.
 */
@Composable
private fun WalletSection(state: Vault.State, onOpen: () -> Unit) {
    SectionCard(title = SECTION_WALLET) {
        PageRow(
            title = WALLET_TITLE,
            subtitle = walletSummary(state),
            style = PageRowStyle.Inset,
            leadingIcon = Icons.Filled.AccountBalanceWallet,
            thirdLine = walletAttentionLine(state),
            onClick = onOpen,
        )
    }
}

@Composable
private fun SearchSection(
    engineId: String,
    customTemplate: String,
    onClick: () -> Unit,
) {
    SectionCard(title = SECTION_SEARCH) {
        PageRow(
            title = ROW_SEARCH_ENGINE,
            subtitle = SearchEngines.labelFor(engineId, customTemplate),
            style = PageRowStyle.Inset,
            leadingIcon = Icons.Filled.Search,
            // The whole template, wrapped — never cut, so it's readable
            // on the narrowest screen.
            thirdLine = customSearchTemplateLine(engineId, customTemplate),
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
                    NoSuggestionsTextInput {
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
                            keyboardOptions = urlKeyboardOptions(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
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

/** The two content sources an external endpoint can replace (#125). */
internal enum class NodeEndpoint(
    val key: String,
    val title: String,
    val embeddedLabel: String,
    val placeholder: String,
    val helper: String,
    /** Shown while an external endpoint of this kind is in use. */
    val warning: String?,
) {
    Swarm(
        key = "swarm",
        title = "Swarm endpoint",
        embeddedLabel = "Embedded Swarm node",
        placeholder = "http://192.168.1.10:1633",
        helper = "A Bee or Ant node API that serves /bzz/",
        warning = null,
    ),
    Ipfs(
        key = "ipfs",
        title = "IPFS gateway",
        embeddedLabel = "Embedded IPFS node (verified)",
        placeholder = "http://192.168.1.10:8080",
        helper = "A path gateway serving /ipfs/ and /ipns/",
        warning = ExternalEndpoints.IPFS_UNVERIFIED_WARNING,
    ),
}

private const val EXTERNAL_LABEL = "External"

private fun endpointSubtitle(endpoint: NodeEndpoint, external: String) =
    if (external.isEmpty()) endpoint.embeddedLabel else EXTERNAL_LABEL

/**
 * The Swarm row always; the IPFS row while advanced options reveal
 * IPFS, or whenever an external gateway is in use — so its unverified
 * warning can't be hidden away with the rest of the IPFS settings.
 */
internal fun nodeRows(externalSwarm: String, externalIpfs: String, showIpfsUi: Boolean) =
    listOfNotNull(
        settingsRow(
            NodeEndpoint.Swarm.key,
            NodeEndpoint.Swarm.title,
            endpointSubtitle(NodeEndpoint.Swarm, externalSwarm),
            externalSwarm,
            "External node",
        ),
        if (showIpfsUi || externalIpfs.isNotEmpty()) settingsRow(
            NodeEndpoint.Ipfs.key,
            NodeEndpoint.Ipfs.title,
            endpointSubtitle(NodeEndpoint.Ipfs, externalIpfs),
            externalIpfs,
            externalIpfs.takeIf { it.isNotEmpty() }?.let { NodeEndpoint.Ipfs.warning },
            "External gateway",
        ) else null,
    )

private const val TOR_ENABLED = "Tor for .onion sites"
private const val TOR_ENABLED_DETAIL =
    "Only .onion sites use Tor; every other site connects directly. While off, onion sites are refused."
private const val TOR_ON_LAUNCH = "Start Tor at launch"
private const val TOR_ON_LAUNCH_SUBTITLE = "Otherwise start it on the Nodes page"

/** Settings → Tor (#143), for settings search. */
internal fun torRows(enabled: Boolean, startOnLaunch: Boolean) = listOf(
    settingsRow("tor-enabled", TOR_ENABLED, if (enabled) "On" else "Off", TOR_ENABLED_DETAIL, "Arti"),
    settingsRow("tor-launch", TOR_ON_LAUNCH, TOR_ON_LAUNCH_SUBTITLE, if (startOnLaunch) "On" else "Off", "onion"),
)

/**
 * Settings → Tor (#143): the integration switch (off by default) and
 * start-at-launch. Starting and stopping Tor itself, and its status, are
 * on the node page. Start-at-launch is greyed out while Tor is off.
 */
@Composable
private fun TorSettingsSection(
    visible: Set<Any>,
    enabled: Boolean,
    startOnLaunch: Boolean,
    onEnabled: (Boolean) -> Unit,
    onStartOnLaunch: (Boolean) -> Unit,
) {
    SectionCard(title = SECTION_TOR) {
        if ("tor-enabled" in visible) PageRow(
            title = TOR_ENABLED,
            subtitle = if (enabled) "On" else "Off",
            // Wraps: the whole explanation is readable on a phone.
            thirdLine = TOR_ENABLED_DETAIL,
            style = PageRowStyle.Inset,
            leadingIcon = Icons.Filled.VpnLock,
            onClick = { onEnabled(!enabled) },
            trailing = { Switch(checked = enabled, onCheckedChange = onEnabled) },
        )
        if ("tor-launch" in visible) PageRow(
            title = TOR_ON_LAUNCH,
            subtitle = TOR_ON_LAUNCH_SUBTITLE,
            style = PageRowStyle.Inset,
            leadingIcon = Icons.Filled.PowerSettingsNew,
            enabled = enabled,
            onClick = { onStartOnLaunch(!startOnLaunch) },
            trailing = {
                Switch(checked = startOnLaunch, onCheckedChange = onStartOnLaunch, enabled = enabled)
            },
        )
    }
}

@Composable
private fun NodesSection(
    visible: Set<Any>,
    externalSwarm: String,
    externalIpfs: String,
    onEdit: (NodeEndpoint) -> Unit,
    radicle: RadicleControls,
    onOpenRadicle: () -> Unit,
) {
    SectionCard(title = SECTION_NODES) {
        if (NodeEndpoint.Swarm.key in visible) {
            EndpointRow(
                endpoint = NodeEndpoint.Swarm,
                external = externalSwarm,
                icon = ImageVector.vectorResource(R.drawable.ic_swarm),
                onClick = { onEdit(NodeEndpoint.Swarm) },
            )
        }
        if (NodeEndpoint.Ipfs.key in visible) {
            EndpointRow(
                endpoint = NodeEndpoint.Ipfs,
                external = externalIpfs,
                icon = ImageVector.vectorResource(R.drawable.ic_ipfs),
                onClick = { onEdit(NodeEndpoint.Ipfs) },
            )
        }
        if (RADICLE_ROW_KEY in visible) {
            PageRow(
                title = RADICLE_ROW_TITLE,
                subtitle = radicleSummary(radicle.info, radicle.enabled),
                style = PageRowStyle.Inset,
                leadingIcon = ImageVector.vectorResource(R.drawable.ic_radicle),
                onClick = onOpenRadicle,
            )
        }
    }
}

@Composable
private fun EndpointRow(
    endpoint: NodeEndpoint,
    external: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    PageRow(
        title = endpoint.title,
        subtitle = endpointSubtitle(endpoint, external),
        style = PageRowStyle.Inset,
        leadingIcon = icon,
        // The whole URL, wrapped — never cut, so it's readable on the
        // narrowest screen.
        thirdLine = external.ifEmpty { null },
        onClick = onClick,
    )
    val warning = endpoint.warning
    if (external.isNotEmpty() && warning != null) {
        UnverifiedWarning(warning, Modifier.padding(start = 40.dp, end = 12.dp, bottom = 8.dp))
    }
}

@Composable
private fun UnverifiedWarning(text: String, modifier: Modifier = Modifier) {
    // Amber, as the node-status "Starting…" state; a darker shade on the
    // light scheme, where the bright one doesn't read on the pale card.
    val color = if (MaterialTheme.colorScheme.isLight) Color(0xFFB45309) else Color(0xFFF59E0B)
    Row(modifier = modifier, verticalAlignment = Alignment.Top) {
        Icon(
            Icons.Filled.Warning,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = color,
        )
    }
}

/**
 * "Embedded node" or "External" + a base-URL field, like the search
 * engine dialog: picking the embedded node applies at once; an
 * external URL applies on Save, which stays disabled until
 * [ExternalEndpoints.validate] accepts it. The IPFS dialog carries the
 * unverified warning while External is picked.
 */
@Composable
private fun EndpointDialog(
    endpoint: NodeEndpoint,
    saved: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var externalSelected by remember { mutableStateOf(saved.isNotEmpty()) }
    var draft by remember { mutableStateOf(saved) }
    val validation = ExternalEndpoints.validate(draft)
    val normalized = validation.endpoint

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(endpoint.title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                EngineRadioRow(
                    label = endpoint.embeddedLabel,
                    selected = !externalSelected,
                    onClick = { onSave("") },
                )
                EngineRadioRow(
                    label = EXTERNAL_LABEL,
                    selected = externalSelected,
                    onClick = { externalSelected = true },
                )
                if (externalSelected) {
                    NoSuggestionsTextInput {
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            label = { Text("URL") },
                            placeholder = { Text(endpoint.placeholder) },
                            isError = draft.isNotBlank() && normalized == null,
                            supportingText = {
                                Text(
                                    validation.rejection
                                        ?.takeIf { draft.isNotBlank() }
                                        ?.let(::endpointHint)
                                        ?: endpoint.helper,
                                )
                            },
                            singleLine = true,
                            keyboardOptions = urlKeyboardOptions(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    endpoint.warning?.let {
                        Spacer(Modifier.height(8.dp))
                        UnverifiedWarning(it)
                    }
                }
            }
        },
        confirmButton = {
            if (externalSelected) {
                TextButton(
                    onClick = { normalized?.let(onSave) },
                    enabled = normalized != null,
                ) { Text("Save") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/** What to fix, for each reason [ExternalEndpoints.validate] refuses a URL. */
private fun endpointHint(rejection: ExternalEndpoints.Rejection): String = when (rejection) {
    ExternalEndpoints.Rejection.EMPTY,
    ExternalEndpoints.Rejection.NOT_A_URL -> "Not a URL: e.g. http://192.168.1.10:1633"
    ExternalEndpoints.Rejection.TOO_LONG -> "Too long: at most 2048 characters"
    ExternalEndpoints.Rejection.SCHEME -> "Needs http:// or https://"
    ExternalEndpoints.Rejection.QUERY_OR_FRAGMENT -> "Remove the ? or # part"
    ExternalEndpoints.Rejection.CREDENTIALS -> "Remove the user name or password before the host"
}

private const val ADBLOCK_ALLOWLIST_EMPTY =
    "Sites you allow ads on — from the page menu, or with Add site — appear here. Blocking is off for them and their subdomains."
private const val ADBLOCK_ADD_SITE = "Add site"
private const val ADBLOCK_ADD_SITE_SUBTITLE = "Turn ad blocking off for a site"
private const val ADBLOCK_CREDITS =
    "Filter lists: EasyList, EasyPrivacy and Fanboy's lists (easylist.to), © their authors, used under CC BY-SA 3.0. Changes apply to pages as they next load."

private const val ADBLOCK_AUTO_UPDATE = "Keep filter lists up to date"
private const val ADBLOCK_AUTO_UPDATE_SUBTITLE = "Signed updates over Swarm"
private const val ADBLOCK_CHECK_UPDATES = "Check for list updates"
private const val ADBLOCK_CHECK_UPDATES_SUBTITLE = "Reads the update feed on Swarm now"

/**
 * The wrapping line under "Keep filter lists up to date": which lists
 * the engine uses now (#127) — the applied update's version and the day
 * it was built, and, where the bundled lists serve some categories
 * instead (the update doesn't carry them, its copy failed its hash
 * check, or theirs is newer), which — giving each list its own reason.
 * (The subtitle is one ellipsised line, too short to be sure of showing
 * the version on a phone.)
 */
internal fun adblockListsLine(status: AdblockStatus): String {
    val version = status.listsVersion ?: return "Using the built-in lists"
    if (status.updatedLists.isEmpty()) {
        // Give each built-in list its real reason: newer than the
        // update's copy, the update's copy failed its hash check (it's
        // fetched again on the next check), or the update doesn't carry
        // it (e.g. a category switched on since it applied).
        val all = status.builtInLists
        val newer = status.newerBuiltInLists
        val damaged = status.damagedLists - newer.toSet()
        val uncovered = all - newer.toSet() - damaged.toSet()
        val reasons = listOfNotNull(
            when {
                newer.isEmpty() -> null
                newer.size == all.size -> "newer than update $version"
                else -> "${newer.joinToString(", ")} newer than update $version's"
            },
            when {
                damaged.isEmpty() -> null
                damaged.size == all.size -> "update $version's copies failed their hash check"
                else -> "update $version's ${damaged.joinToString(", ")} failed its hash check"
            },
            when {
                uncovered.isEmpty() -> null
                uncovered.size == all.size -> "update $version doesn't include them"
                else -> "update $version doesn't include ${uncovered.joinToString(", ")}"
            },
        )
        if (reasons.isEmpty()) return "Using the built-in lists"
        return "Using the built-in lists (${reasons.joinToString("; ")})"
    }
    val day = status.listsGeneratedAt?.take(10)?.takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) }
    val update = "Using update $version" + (day?.let { " of $it" } ?: "")
    if (status.builtInLists.isEmpty()) return update
    val damaged = status.damagedLists.filter { it in status.builtInLists }
    return "$update for ${status.updatedLists.joinToString(", ")}; " +
        "the built-in ${status.builtInLists.joinToString(", ")}" +
        (if (damaged.isEmpty()) "" else " (update $version's ${damaged.joinToString(", ")} failed its hash check)")
}

/** The Settings name of the category [key] ("ads" → "EasyList"). */
private fun adblockListName(key: String): String =
    AdblockCategory.entries.firstOrNull { it.key == key }?.listName ?: key

/**
 * The wrapping line under "Check for list updates": a check under way,
 * or how the last one ended; `null` before the first check.
 */
internal fun adblockUpdateLine(update: AdblockUpdateState): String? {
    if (update.checking) return "Checking…"
    return when (val last = update.last) {
        null -> null
        is AdblockUpdateOutcome.Applied ->
            if (last.olderThanBuiltIn.isEmpty()) {
                "Updated to version ${last.version}"
            } else {
                "Updated to version ${last.version}; the built-in " +
                    last.olderThanBuiltIn.joinToString(", ") { adblockListName(it) } +
                    if (last.olderThanBuiltIn.size == 1) " stays, it's newer" else " stay, they're newer"
            }
        is AdblockUpdateOutcome.BuiltInNewer ->
            "Version ${last.version} on the feed is older than the built-in lists; they stay in use"
        is AdblockUpdateOutcome.UpToDate ->
            if (last.version > 0) "Up to date (version ${last.version})" else "Up to date"
        AdblockUpdateOutcome.FeedUnavailable ->
            "Couldn't reach the update feed on Swarm — is the Swarm node running? Tap to try again"
        is AdblockUpdateOutcome.Rejected ->
            "Refused an update that failed verification (${last.reason}); the current lists stay"
        is AdblockUpdateOutcome.DownloadFailed ->
            "Couldn't download ${adblockListName(last.category)}; the current lists stay"
        is AdblockUpdateOutcome.HashMismatch ->
            "${adblockListName(last.category)} didn't match its signed hash; the current lists stay"
        AdblockUpdateOutcome.NothingEnabled -> "Every filter list is off"
        is AdblockUpdateOutcome.Failed -> "The update failed (${last.message}); the current lists stay"
    }
}

/** The line under a category: its list, and while it's on, how the engine is doing. */
internal fun adblockCategorySubtitle(category: AdblockCategory, on: Boolean, status: AdblockStatus): String =
    if (on && status.loading) "${category.listName} · loading…" else category.listName

private fun adblockSectionRows(
    enabled: Set<AdblockCategory>,
    allowlist: List<String>,
    status: AdblockStatus,
    update: AdblockUpdateState,
) = buildList {
    for (category in AdblockCategory.entries) {
        add(settingsRow(category, category.title, category.listName, if (category in enabled) "On" else "Off"))
    }
    add(settingsRow("auto-update", ADBLOCK_AUTO_UPDATE, ADBLOCK_AUTO_UPDATE_SUBTITLE, adblockListsLine(status), "filter list updates"))
    add(settingsRow("update-check", ADBLOCK_CHECK_UPDATES, ADBLOCK_CHECK_UPDATES_SUBTITLE, adblockUpdateLine(update), "filter list updates"))
    add(settingsRow("allowlist-add", ADBLOCK_ADD_SITE, ADBLOCK_ADD_SITE_SUBTITLE, "allowlist", "allowed sites"))
    if (allowlist.isEmpty()) {
        add(settingsRow("allowlist-empty", ADBLOCK_ALLOWLIST_EMPTY))
    } else {
        for (site in allowlist) {
            add(settingsRow("site:$site", allowlistHostForDisplay(site), allowlistSiteSubtitle(site), "allowlist", site))
        }
    }
    add(settingsRow("credits", ADBLOCK_CREDITS))
}

/**
 * The line under an allowed site: "Ads allowed", led by the stored
 * punycode when the title shows the Unicode name
 * ([allowlistHostForDisplay]), so both forms are on screen.
 */
internal fun allowlistSiteSubtitle(site: String): String {
    val shown = allowlistHostForDisplay(site)
    return if (shown == site) "Ads allowed" else "$site · Ads allowed"
}

/**
 * Ad blocking (#126): a switch per filter-list category, the sites
 * blocking is off for (each removable, and "Add site" for one typed in),
 * and the lists' attribution. Every string is shown whole and wraps —
 * a site's name is never cut.
 */
@Composable
private fun AdblockSection(
    visible: Set<Any>,
    enabled: Set<AdblockCategory>,
    allowlist: List<String>,
    status: AdblockStatus,
    update: AdblockUpdateState,
    autoUpdate: Boolean,
    onToggle: (AdblockCategory, Boolean) -> Unit,
    onAutoUpdate: (Boolean) -> Unit,
    onCheckUpdates: () -> Unit,
    onRemoveSite: (String) -> Unit,
    onAddSite: () -> Unit,
) {
    SectionCard(title = SECTION_ADBLOCK) {
        for (category in AdblockCategory.entries) {
            if (category !in visible) continue
            val on = category in enabled
            PageRow(
                title = category.title,
                subtitle = adblockCategorySubtitle(category, on, status),
                style = PageRowStyle.Inset,
                leadingIcon = Icons.Filled.Shield,
                onClick = { onToggle(category, !on) },
                trailing = {
                    Switch(checked = on, onCheckedChange = { onToggle(category, it) })
                },
            )
        }
        if ("auto-update" in visible) PageRow(
            title = ADBLOCK_AUTO_UPDATE,
            subtitle = ADBLOCK_AUTO_UPDATE_SUBTITLE,
            thirdLine = adblockListsLine(status),
            style = PageRowStyle.Inset,
            leadingIcon = Icons.Filled.Update,
            onClick = { onAutoUpdate(!autoUpdate) },
            trailing = {
                Switch(checked = autoUpdate, onCheckedChange = onAutoUpdate)
            },
        )
        if ("update-check" in visible) PageRow(
            title = ADBLOCK_CHECK_UPDATES,
            subtitle = ADBLOCK_CHECK_UPDATES_SUBTITLE,
            thirdLine = adblockUpdateLine(update),
            style = PageRowStyle.Inset,
            leadingIcon = Icons.Filled.Sync,
            enabled = !update.checking,
            onClick = onCheckUpdates,
        )
        if ("allowlist-add" in visible) PageRow(
            title = ADBLOCK_ADD_SITE,
            subtitle = ADBLOCK_ADD_SITE_SUBTITLE,
            style = PageRowStyle.Inset,
            leadingIcon = Icons.Filled.Add,
            onClick = onAddSite,
        )
        if (allowlist.isEmpty() && "allowlist-empty" in visible) {
            Text(
                ADBLOCK_ALLOWLIST_EMPTY,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        for (site in allowlist) {
            if ("site:$site" !in visible) continue
            val shown = allowlistHostForDisplay(site)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Public,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(shown, fontWeight = FontWeight.Medium)
                    Text(
                        allowlistSiteSubtitle(site),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { onRemoveSite(site) }) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Block ads on $shown again",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if ("credits" in visible) {
            Text(
                ADBLOCK_CREDITS,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/** A host or URL to allow ads on; Add stays disabled until it is one ([normalizeAllowlistHost]). */
@Composable
private fun AllowlistSiteDialog(onAdd: (String) -> Unit, onDismiss: () -> Unit) {
    var draft by remember { mutableStateOf("") }
    val host = normalizeAllowlistHost(draft)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Allow ads on a site") },
        text = {
            NoSuggestionsTextInput {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text("Site") },
                    placeholder = { Text("example.com") },
                    isError = draft.isNotBlank() && host == null,
                    supportingText = {
                        Text(
                            if (draft.isNotBlank() && host == null) "Not a site: e.g. example.com"
                            else "Its subdomains are included.",
                        )
                    },
                    singleLine = true,
                    keyboardOptions = urlKeyboardOptions(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { host?.let(onAdd) }, enabled = host != null) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private const val ROW_CLEAR_HISTORY = "Clear history"
private const val ROW_CLEAR_BOOKMARKS = "Clear bookmarks"
private const val ROW_CLEAR_SITE_DATA = "Clear cookies & site data"
private const val ROW_CLEAR_SITE_DATA_SUBTITLE = "Cookies, DOM storage, cache, form data, zoom levels, and desktop sites"

private fun historySubtitle(count: Int) =
    if (count == 0) "Nothing to clear" else "$count visit${if (count == 1) "" else "s"}"

private fun bookmarksSubtitle(count: Int) =
    if (count == 0) "Nothing to clear" else "$count bookmark${if (count == 1) "" else "s"}"

internal fun browsingDataRows(historyCount: Int, bookmarkCount: Int) = listOf(
    settingsRow("history", ROW_CLEAR_HISTORY, historySubtitle(historyCount)),
    settingsRow("bookmarks", ROW_CLEAR_BOOKMARKS, bookmarksSubtitle(bookmarkCount)),
    settingsRow("site-data", ROW_CLEAR_SITE_DATA, ROW_CLEAR_SITE_DATA_SUBTITLE),
)

@Composable
private fun BrowsingDataSection(
    visible: Set<Any>,
    historyCount: Int,
    bookmarkCount: Int,
    onClearHistoryRequested: () -> Unit,
    onClearBookmarksRequested: () -> Unit,
    onClearSiteDataRequested: () -> Unit,
) {
    SectionCard(title = SECTION_BROWSING) {
        if ("history" in visible) ActionRow(
            icon = Icons.Filled.History,
            title = ROW_CLEAR_HISTORY,
            subtitle = historySubtitle(historyCount),
            enabled = historyCount > 0,
            onClick = onClearHistoryRequested,
        )
        if ("bookmarks" in visible) ActionRow(
            icon = Icons.Filled.Star,
            title = ROW_CLEAR_BOOKMARKS,
            subtitle = bookmarksSubtitle(bookmarkCount),
            enabled = bookmarkCount > 0,
            onClick = onClearBookmarksRequested,
        )
        if ("site-data" in visible) ActionRow(
            icon = Icons.Filled.Cookie,
            title = ROW_CLEAR_SITE_DATA,
            subtitle = ROW_CLEAR_SITE_DATA_SUBTITLE,
            enabled = true,
            onClick = onClearSiteDataRequested,
        )
    }
}

/**
 * Site permissions (#81), links to other apps included (#85): one row
 * per decision — the site, in full and
 * wrapping (never ellipsised: the end of a host is the part that
 * matters), the permission and its state, and a Remove button that
 * makes the site ask again next time. Session-only decisions are listed
 * too, so a Block or a dismissal embargo made this run can be lifted
 * without restarting the app.
 */
private const val PERMISSIONS_EMPTY =
    "Sites you allow or block from using your camera, microphone or location, or from opening links in other apps, " +
        "and sites you connect your wallet to, appear here."

/** A wallet connection's row key in Site permissions: its own type, so it never equals a [SitePermissionEntry]. */
internal data class DappConnectionRow(val origin: String)

/** "Wallet · Account 1 · 0x9858…da94 · Gnosis Chain": a connected site's line in Site permissions. */
internal fun dappConnectionDetail(grant: DappGrantStore.Grant, accounts: List<WalletAccount>, chains: List<Chain>) =
    "Wallet · ${connectedSiteSummary(grant, accounts, chains)}"

/**
 * One row per wallet connection (#111), then one per decision, each keyed by
 * its own item; the explainer while there are none.
 */
internal fun sitePermissionRows(
    entries: List<SitePermissionEntry>,
    grants: List<DappGrantStore.Grant>,
    accounts: List<WalletAccount>,
    chains: List<Chain>,
) =
    if (entries.isEmpty() && grants.isEmpty()) {
        listOf(settingsRow("empty", PERMISSIONS_EMPTY))
    } else {
        grants.map { grant ->
            settingsRow(
                DappConnectionRow(grant.origin),
                permissionOriginDisplay(grant.origin),
                dappConnectionDetail(grant, accounts, chains),
            )
        } + entries.map { entry ->
            settingsRow(
                entry,
                permissionOriginDisplay(entry.origin),
                sitePermissionDetail(entry),
            )
        }
    }

private fun sitePermissionDetail(entry: SitePermissionEntry) =
    "${entry.permission.label} · ${sitePermissionStateLabel(entry)}"

@Composable
private fun SitePermissionsSection(
    visible: Set<Any>,
    entries: List<SitePermissionEntry>,
    onRevoke: (SitePermissionEntry) -> Unit,
    grants: List<DappGrantStore.Grant>,
    accounts: List<WalletAccount>,
    chains: List<Chain>,
    onOpenSite: (String) -> Unit,
    disconnectFailed: String?,
    onDisconnect: (String) -> Unit,
) {
    SectionCard(title = SECTION_PERMISSIONS) {
        if (entries.isEmpty() && grants.isEmpty() && "empty" in visible) {
            Text(
                PERMISSIONS_EMPTY,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        // Wallet connections first: they're what a site can do the most with.
        for (grant in grants) {
            if (DappConnectionRow(grant.origin) !in visible) continue
            val site = permissionOriginDisplay(grant.origin)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .clickable(onClickLabel = "Open") { onOpenSite(grant.origin) }
                    .padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.AccountBalanceWallet,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(site, fontWeight = FontWeight.Medium)
                    Text(
                        dappConnectionDetail(grant, accounts, chains),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (disconnectFailed == grant.origin) {
                        Text(
                            DISCONNECT_FAILED,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                IconButton(onClick = { onDisconnect(grant.origin) }) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Disconnect $site from the wallet",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        for (entry in entries) {
            if (entry !in visible) continue
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
                        is ExternalScheme -> Icons.AutoMirrored.Filled.OpenInNew
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
                        sitePermissionDetail(entry),
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

/** "1.2.3 (build 45)", as the About card shows it. */
private fun appVersionLabel(context: Context): String {
    val info = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0)
    }.getOrNull()
    val versionName = info?.versionName ?: "unknown"
    @Suppress("DEPRECATION")
    val versionCode = info?.let {
        if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode
        else it.versionCode.toLong()
    } ?: 0L
    return "$versionName (build $versionCode)"
}

private const val ABOUT_NAME = "Freedom"
private const val ABOUT_TAGLINE = "Swarm-native browser for Android"
private const val ABOUT_BLURB =
    "Loads regular https:// sites plus decentralised content via bzz:// hashes and ENS names (vitalik.eth), served through embedded nodes."

private fun aboutRows(version: String, packageName: String) = listOf(
    settingsRow("app", ABOUT_NAME, ABOUT_TAGLINE),
    settingsRow("version", "Version", version),
    settingsRow("package", "Package", packageName),
    settingsRow("blurb", ABOUT_BLURB),
)

@Composable
private fun AboutSection(visible: Set<Any>, version: String) {
    val context = LocalContext.current
    SectionCard(title = SECTION_ABOUT) {
        if ("app" in visible) Row(
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
                Text(ABOUT_NAME, fontWeight = FontWeight.SemiBold)
                Text(
                    ABOUT_TAGLINE,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // Spaced off the detail rows only; the blurb adds its own spacer.
        if ("app" in visible && ("version" in visible || "package" in visible)) {
            Spacer(Modifier.height(8.dp))
        }
        if ("version" in visible) DetailRow("Version", version)
        if ("package" in visible) DetailRow("Package", context.packageName, mono = true)
        if ("blurb" in visible) {
            // Spaced off only when something sits above it.
            if (visible.size > 1) Spacer(Modifier.height(8.dp))
            Text(
                ABOUT_BLURB,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private const val ROW_ADVANCED = "Show advanced options"
private const val ROW_ADVANCED_SUBTITLE = "Experimental protocol settings"

private fun otherRows() = listOf(settingsRow("advanced", ROW_ADVANCED, ROW_ADVANCED_SUBTITLE))

@Composable
private fun OtherSection(
    showIpfsUi: Boolean,
    onToggleShowIpfsUi: (Boolean) -> Unit,
) {
    SectionCard(title = SECTION_OTHER) {
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
                Text(ROW_ADVANCED, fontWeight = FontWeight.Medium)
                Text(
                    ROW_ADVANCED_SUBTITLE,
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
private const val ROW_ROUTING_MODE = "Routing mode"
private const val ROW_ROUTING_MODE_SUBTITLE = "Content discovery strategy"
private const val ROUTING_MODE_HELPER =
    "Routing mode applies the next time IPFS starts — toggle IPFS off and on to re-init."

/**
 * The IPFS switch with the node's details under it (one row: they
 * describe the switch), and the routing-mode picker with every mode it
 * offers and its helper line.
 */
internal fun ipfsRows(info: IpfsInfo) = listOf(
    settingsRow(
        "status",
        "IPFS",
        ipfsStatusTriple(info).label,
        // The details [IpfsSection] lists under the switch.
        *(if (info.gatewayUrl.isNotBlank()) arrayOf(
            "Blocks fetched", info.connectedPeers.toString(),
            "Gateway", info.gatewayUrl,
            "Client", info.clientVersion.takeIf { it.isNotBlank() }?.let { "freedom-ipfs/$it" },
        ) else emptyArray()),
        *(if (!info.errorMessage.isNullOrBlank()) arrayOf("Error", info.errorMessage) else emptyArray()),
    ),
    settingsRow(
        "routing",
        ROW_ROUTING_MODE,
        ROW_ROUTING_MODE_SUBTITLE,
        ROUTING_MODE_HELPER,
        *NodeSettings.IPFS_ROUTING_MODES.toTypedArray(),
    ),
)

@Composable
private fun IpfsSection(
    visible: Set<Any>,
    settings: NodeSettings,
    ipfsInfo: IpfsInfo,
    onIpfsToggle: (Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val routingMode by settings.ipfsRoutingMode
        .collectAsState(initial = NodeSettings.DEFAULT_IPFS_ROUTING_MODE)

    val triple = ipfsStatusTriple(ipfsInfo)
    val isOn = ipfsInfo.status != IpfsStatus.Stopped

    SectionCard(title = SECTION_IPFS) {
        if ("status" in visible) {
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
        }

        if ("routing" in visible) {
            if ("status" in visible) Spacer(Modifier.height(12.dp))
            RoutingModePicker(
                selected = routingMode,
                onSelect = { mode ->
                    scope.launch { settings.setIpfsRoutingMode(mode) }
                },
            )

            Spacer(Modifier.height(8.dp))
            Text(
                ROUTING_MODE_HELPER,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
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
            Text(ROW_ROUTING_MODE, fontWeight = FontWeight.Medium)
            Text(
                ROW_ROUTING_MODE_SUBTITLE,
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
