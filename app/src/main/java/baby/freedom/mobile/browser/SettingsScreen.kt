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
import androidx.annotation.StringRes
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Cookie
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Folder
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
import androidx.compose.ui.res.stringResource
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
import baby.freedom.mobile.l10n.Strings
import baby.freedom.mobile.ui.Appearance
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
 *  0. **Appearance** — the theme: System default, Light or Dark
 *     ([baby.freedom.mobile.ui.Appearance], #269).
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
 *  5. **About** — app name, version, package, and a short blurb; and
 *     **Check for updates** / **Check now**, with a newer release's
 *     page one tap away (#272, [AppUpdates]).
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
    /** Open the node logs page (#276) at the IPFS node's. */
    onOpenIpfsLogs: () -> Unit = {},
    /** A newer release's page (#272), in a new tab in front of Settings. */
    onOpenUrl: (String) -> Unit = {},
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
    val appearance by settings.appearance.collectAsState(initial = Appearance.System)
    var pickAppearance by remember { mutableStateOf(false) }
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
    val torExternalProxy by settings.torExternalProxy.collectAsState(initial = "")
    var editTorClient by remember { mutableStateOf(false) }

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
    // Open-source licences (#325), a page standing in for the list like the others.
    var licencesOpen by rememberSaveable { mutableStateOf(false) }
    var chainQuery by rememberSaveable { mutableStateOf("") }
    var confirmRemoveChain by remember { mutableStateOf<Chain?>(null) }
    var removeChainFailed by remember { mutableStateOf<Chain?>(null) }

    val vault = remember(context) { Vault.get(context) }
    val walletState by vault.state.collectAsState()

    val scope = rememberCoroutineScope()
    val appVersion = remember(context) { appVersionLabel(context) }
    val rpcNotRemovedLast = stringResource(R.string.settings_rpc_not_removed_last)
    val rpcRemoveFailed = stringResource(R.string.settings_rpc_remove_failed)
    val appUpdate by AppUpdates.state.collectAsState()
    val checkForUpdates by settings.checkForUpdates.collectAsState(initial = true)
    val askWhereToSave by settings.askWhereToSave.collectAsState(initial = false)

    // Each section's rows for the current query; an empty set hides the
    // section. The index is what the page shows right now (see
    // [SettingsRow]) — IPFS only while advanced options reveal it.
    val walletRows = visibleSettingsRows(query, SECTION_WALLET, walletSettingsRows(walletState))
    val searchRows = visibleSettingsRows(
        query, SECTION_SEARCH, searchSectionRows(searchEngine, customSearchTemplate),
    )
    // Language (#280): only on Android 13+ and once there is more than one.
    val appLanguage = rememberAppLanguage()
    val appearanceRows = visibleSettingsRows(
        query, SECTION_APPEARANCE, appearanceSectionRows(appearance, appLanguage),
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
    val downloadRows = visibleSettingsRows(query, SECTION_DOWNLOADS, downloadSettingsRows(askWhereToSave))
    val permissionRows = visibleSettingsRows(
        query, SECTION_PERMISSIONS, sitePermissionRows(permissionEntries, dappGrants, walletAccounts, chains),
    )
    val nodeRows = visibleSettingsRows(
        query, SECTION_NODES,
        nodeRows(externalSwarm, externalIpfs, showIpfsUi) + radicleSettingsRow(radicle),
    )
    val torRows = visibleSettingsRows(query, SECTION_TOR, torRows(torEnabled, torStartOnLaunch, torExternalProxy))
    val chainRows = visibleSettingsRows(query, SECTION_CHAINS, chainSettingsRows(chains))
    val aboutRows = visibleSettingsRows(
        query, SECTION_ABOUT, aboutRows(appVersion, context.packageName, appUpdate, checkForUpdates),
    )
    val otherRows = visibleSettingsRows(query, SECTION_OTHER, otherRows())
    val ipfsRows = if (showIpfsUi) {
        visibleSettingsRows(query, SECTION_IPFS, ipfsRows(ipfsInfo))
    } else emptySet()
    val nothingMatches = listOf(
        walletRows, searchRows, appearanceRows, defaultBrowserRows, adblockRows, ensRows, rpcRows, browsingRows, downloadRows,
        permissionRows, nodeRows,
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
                                NodeSettings.EnsEdit.LAST_ENDPOINT -> rpcNotRemovedLast
                                NodeSettings.EnsEdit.FAILED -> rpcRemoveFailed
                            }
                        } else if (chainStore.removeUserRpc(chain.id, url)) {
                            null
                        } else {
                            rpcRemoveFailed
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
    if (licencesOpen && chainPage == null && site == null) {
        OpenSourceLicencesPage(onBack = { licencesOpen = false })
    }
    if (chainPage == null && site == null && !licencesOpen) FullScreenScaffold(
        title = stringResource(R.string.settings_title),
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
                if (appearanceRows.isNotEmpty()) item("appearance") {
                    AppearanceSection(
                        visible = appearanceRows,
                        appearance = appearance,
                        onClick = { pickAppearance = true },
                        language = appLanguage,
                        onLanguageClick = {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                AppLanguage.openSettings(context)
                            }
                        },
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
                if (downloadRows.isNotEmpty()) item("downloads") {
                    DownloadSettingsSection(
                        askWhereToSave = askWhereToSave,
                        onAskWhereToSave = { on -> scope.launch { settings.setAskWhereToSave(on) } },
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
                        externalProxy = torExternalProxy,
                        onEditClient = { editTorClient = true },
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
                    AboutSection(
                        visible = aboutRows,
                        version = appVersion,
                        update = appUpdate,
                        checkForUpdates = checkForUpdates,
                        onCheckForUpdates = { on -> scope.launch { settings.setCheckForUpdates(on) } },
                        onCheckNow = { AppUpdates.checkForUpdates() },
                        onOpenRelease = { onOpenUrl(it.url) },
                        onOpenLicences = { licencesOpen = true },
                    )
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
                        onOpenLogs = onOpenIpfsLogs,
                    )
                }
                if (nothingMatches) item("no-match") {
                    Text(
                        stringResource(R.string.settings_no_match, query.trim()),
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
    if (pickAppearance) {
        AppearanceDialog(
            selected = appearance,
            onSelect = { choice ->
                scope.launch { settings.setAppearance(choice) }
                pickAppearance = false
            },
            onDismiss = { pickAppearance = false },
        )
    }
    if (editTorClient) {
        TorClientDialog(
            saved = torExternalProxy,
            onSave = { value ->
                scope.launch { settings.setTorExternalProxy(value) }
                editTorClient = false
            },
            onDismiss = { editTorClient = false },
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
            title = stringResource(R.string.settings_remove_chain_title, chain.name),
            message = stringResource(R.string.settings_remove_chain_message, chain.id.toString()),
            confirmLabel = stringResource(R.string.common_remove),
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
            title = { Text(stringResource(R.string.settings_remove_chain_failed_title, chain.name)) },
            text = { Text(stringResource(R.string.settings_remove_chain_failed_message, chain.id.toString())) },
            confirmButton = {
                TextButton(onClick = { removeChainFailed = null }) { Text(stringResource(R.string.common_ok)) }
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
            title = stringResource(R.string.settings_clear_history_title),
            message = stringResource(R.string.settings_clear_history_message),
            confirmLabel = stringResource(R.string.settings_clear_history),
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
            title = stringResource(R.string.settings_clear_bookmarks_title),
            message = stringResource(R.string.settings_clear_bookmarks_message),
            confirmLabel = stringResource(R.string.settings_clear_bookmarks),
            onConfirm = {
                repo.clearBookmarks()
                confirmClearBookmarks = false
            },
            onDismiss = { confirmClearBookmarks = false },
        )
    }
    if (confirmClearSiteData) {
        ConfirmDialog(
            title = stringResource(R.string.settings_clear_site_data_title),
            message = stringResource(R.string.settings_clear_site_data_message),
            confirmLabel = stringResource(R.string.settings_clear_site_data_confirm),
            onConfirm = {
                onClearWebViewData()
                confirmClearSiteData = false
            },
            onDismiss = { confirmClearSiteData = false },
        )
    }
}

// Section titles: each card's heading, and searched too (a query naming
// a section shows all of it, see [visibleSettingsRows]).
private val SECTION_WALLET: String get() = Strings.get(R.string.settings_section_wallet)
private val SECTION_SEARCH: String get() = Strings.get(R.string.settings_section_search)
private val SECTION_APPEARANCE: String get() = Strings.get(R.string.settings_section_appearance)
private val SECTION_ADBLOCK: String get() = Strings.get(R.string.settings_section_adblock)
private val SECTION_BROWSING: String get() = Strings.get(R.string.settings_section_browsing)
private val SECTION_PERMISSIONS: String get() = Strings.get(R.string.settings_section_permissions)
private val SECTION_DOWNLOADS: String get() = Strings.get(R.string.settings_section_downloads)
private val SECTION_NODES: String get() = Strings.get(R.string.settings_section_nodes)
private val SECTION_TOR: String get() = Strings.get(R.string.settings_section_tor)
private val SECTION_ABOUT: String get() = Strings.get(R.string.settings_section_about)
private val SECTION_OTHER: String get() = Strings.get(R.string.settings_section_other)
private val SECTION_IPFS: String get() = Strings.get(R.string.settings_section_ipfs)

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
        placeholder = { Text(stringResource(R.string.settings_search_placeholder)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.settings_search_clear))
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

private val ROW_SEARCH_ENGINE: String get() = Strings.get(R.string.settings_search_engine)

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

private val ROW_THEME: String get() = Strings.get(R.string.settings_theme)

private val ROW_LANGUAGE: String get() = Strings.get(R.string.settings_language)

/**
 * The theme row (#269), findable by the choice in use and by every
 * choice it can be switched to, plus the words people search for them
 * with ("dark mode" → the Theme row); and, while it's shown, the
 * Language row (#280) with the app's current [language].
 */
internal fun appearanceSectionRows(appearance: Appearance, language: String? = null) = listOfNotNull(
    settingsRow(
        "theme",
        ROW_THEME,
        appearance.label,
        APPEARANCE_DETAIL,
        *Appearance.entries.map { it.label }.toTypedArray(),
        *searchKeywords(R.string.settings_theme_keywords),
    ),
    language?.let {
        settingsRow("language", ROW_LANGUAGE, it, *searchKeywords(R.string.settings_language_keywords))
    },
)

/**
 * What else the choice reaches: pages, through `prefers-color-scheme`,
 * only where [Appearance.apply] can set the app's night mode. Nothing
 * is claimed on Android 11, where only the chrome follows.
 */
private val APPEARANCE_DETAIL: String?
    get() = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
        Strings.get(R.string.settings_appearance_detail)
    } else null

/**
 * Settings → Appearance: the theme row, which opens [AppearanceDialog];
 * and the Language row while there is a [language] to show, which opens
 * Android's per-app language page.
 */
@Composable
private fun AppearanceSection(
    visible: Set<Any>,
    appearance: Appearance,
    onClick: () -> Unit,
    language: String?,
    onLanguageClick: () -> Unit,
) {
    SectionCard(title = stringResource(R.string.settings_section_appearance)) {
        if ("theme" in visible) PageRow(
            title = stringResource(R.string.settings_theme),
            subtitle = stringResource(appearance.labelRes),
            style = PageRowStyle.Inset,
            leadingIcon = Icons.Filled.Contrast,
            thirdLine = APPEARANCE_DETAIL,
            onClick = onClick,
        )
        if (language != null && "language" in visible) PageRow(
            title = stringResource(R.string.settings_language),
            subtitle = language,
            style = PageRowStyle.Inset,
            leadingIcon = Icons.Filled.Language,
            onClick = onLanguageClick,
        )
    }
}

/** Radio list of the [Appearance] choices; a tap applies one straight away. */
@Composable
private fun AppearanceDialog(
    selected: Appearance,
    onSelect: (Appearance) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_theme)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Appearance.entries.forEach { choice ->
                    EngineRadioRow(
                        label = stringResource(choice.labelRes),
                        selected = choice == selected,
                        onClick = { onSelect(choice) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/**
 * Settings → Wallet: one row with the wallet's state, and the backup
 * reminder (or no-screen-lock warning) as a line that stays under it —
 * wrapped, never cut.
 */
@Composable
private fun WalletSection(state: Vault.State, onOpen: () -> Unit) {
    SectionCard(title = stringResource(R.string.settings_section_wallet)) {
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
    SectionCard(title = stringResource(R.string.settings_section_search)) {
        PageRow(
            title = stringResource(R.string.settings_search_engine),
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
        title = { Text(stringResource(R.string.settings_search_engine)) },
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
                    label = stringResource(R.string.settings_search_engine_custom),
                    selected = customSelected,
                    onClick = { customSelected = true },
                )
                if (customSelected) {
                    NoSuggestionsTextInput {
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            label = { Text(stringResource(R.string.settings_search_url)) },
                            placeholder = { Text(stringResource(R.string.settings_search_url_placeholder)) },
                            isError = draft.isNotBlank() && normalized == null,
                            supportingText = {
                                Text(
                                    validation.rejection
                                        ?.takeIf { draft.isNotBlank() }
                                        ?.let(::templateHint)
                                        ?: stringResource(R.string.settings_search_url_helper),
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
                ) { Text(stringResource(R.string.common_save)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/** What to fix, for each reason [SearchEngines.validateTemplate] refuses a template. */
private fun templateHint(rejection: SearchEngines.Rejection): String = when (rejection) {
    SearchEngines.Rejection.EMPTY,
    SearchEngines.Rejection.NO_PLACEHOLDER -> Strings.get(R.string.settings_search_hint_no_placeholder)
    SearchEngines.Rejection.MULTIPLE_PLACEHOLDERS -> Strings.get(R.string.settings_search_hint_multiple_placeholders)
    SearchEngines.Rejection.TOO_LONG -> Strings.get(R.string.settings_hint_too_long)
    SearchEngines.Rejection.NOT_A_URL -> Strings.get(R.string.settings_search_hint_not_a_url)
    SearchEngines.Rejection.SCHEME -> Strings.get(R.string.settings_search_hint_scheme)
    SearchEngines.Rejection.USER_INFO -> Strings.get(R.string.settings_hint_remove_credentials)
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
    @StringRes private val titleRes: Int,
    @StringRes private val embeddedLabelRes: Int,
    val placeholder: String,
    @StringRes private val helperRes: Int,
    /** Extra words Settings search finds the row by. */
    @StringRes val keywordsRes: Int,
) {
    Swarm(
        key = "swarm",
        titleRes = R.string.settings_swarm_endpoint,
        embeddedLabelRes = R.string.settings_swarm_embedded,
        placeholder = "http://192.168.1.10:1633",
        helperRes = R.string.settings_swarm_endpoint_helper,
        keywordsRes = R.string.settings_swarm_endpoint_keywords,
    ),
    Ipfs(
        key = "ipfs",
        titleRes = R.string.settings_ipfs_gateway,
        embeddedLabelRes = R.string.settings_ipfs_embedded,
        placeholder = "http://192.168.1.10:8080",
        helperRes = R.string.settings_ipfs_gateway_helper,
        keywordsRes = R.string.settings_ipfs_gateway_keywords,
    ),
    ;

    val title: String get() = Strings.get(titleRes)
    val embeddedLabel: String get() = Strings.get(embeddedLabelRes)
    val helper: String get() = Strings.get(helperRes)

    /** Shown while an external endpoint of this kind is in use. */
    val warning: String?
        get() = if (this == Ipfs) ExternalEndpoints.IPFS_UNVERIFIED_WARNING else null
}

private val EXTERNAL_LABEL: String get() = Strings.get(R.string.settings_external)

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
            *searchKeywords(NodeEndpoint.Swarm.keywordsRes),
        ),
        if (showIpfsUi || externalIpfs.isNotEmpty()) settingsRow(
            NodeEndpoint.Ipfs.key,
            NodeEndpoint.Ipfs.title,
            endpointSubtitle(NodeEndpoint.Ipfs, externalIpfs),
            externalIpfs,
            externalIpfs.takeIf { it.isNotEmpty() }?.let { NodeEndpoint.Ipfs.warning },
            *searchKeywords(NodeEndpoint.Ipfs.keywordsRes),
        ) else null,
    )

private val TOR_ENABLED: String get() = Strings.get(R.string.settings_tor_enabled)
private val TOR_ENABLED_DETAIL: String get() = Strings.get(R.string.settings_tor_enabled_detail)
private val TOR_ON_LAUNCH: String get() = Strings.get(R.string.settings_tor_on_launch)
private val TOR_ON_LAUNCH_SUBTITLE: String get() = Strings.get(R.string.settings_tor_on_launch_subtitle)
private val TOR_CLIENT: String get() = Strings.get(R.string.settings_tor_client)
private val TOR_CLIENT_EMBEDDED: String get() = Strings.get(R.string.settings_tor_client_embedded)
private val TOR_CLIENT_EXTERNAL: String get() = Strings.get(R.string.settings_tor_client_external)
private val TOR_CLIENT_HELPER: String get() = Strings.get(R.string.settings_tor_client_helper)

/** "On" / "Off": a switch row's state, for settings search. */
private fun onOff(on: Boolean): String = Strings.get(if (on) R.string.settings_on else R.string.settings_off)

private fun torClientSubtitle(externalProxy: String) =
    if (externalProxy.isEmpty()) TOR_CLIENT_EMBEDDED else TOR_CLIENT_EXTERNAL

private val ASK_WHERE_TO_SAVE: String get() = Strings.get(R.string.settings_downloads_ask_where)
private val ASK_WHERE_TO_SAVE_PRIVATE: String get() = Strings.get(R.string.settings_downloads_ask_where_private)

private fun askWhereToSaveSubtitle(on: Boolean): String = Strings.get(
    if (on) R.string.settings_downloads_ask_where_subtitle_on else R.string.settings_downloads_ask_where_subtitle_off,
)

/** Settings → Downloads (#322), for settings search. */
internal fun downloadSettingsRows(askWhereToSave: Boolean) = listOf(
    settingsRow(
        "ask-where", ASK_WHERE_TO_SAVE, askWhereToSaveSubtitle(askWhereToSave), ASK_WHERE_TO_SAVE_PRIVATE,
        onOff(askWhereToSave), *searchKeywords(R.string.settings_downloads_ask_where_keywords),
    ),
)

/**
 * Settings → Downloads (#322): *Ask where to save each file*, off by
 * default. On, confirming a download opens the system's *Save as*
 * picker; private tabs' downloads keep saving to Download/Freedom.
 */
@Composable
private fun DownloadSettingsSection(
    askWhereToSave: Boolean,
    onAskWhereToSave: (Boolean) -> Unit,
) {
    SectionCard(title = SECTION_DOWNLOADS) {
        PageRow(
            title = ASK_WHERE_TO_SAVE,
            subtitle = askWhereToSaveSubtitle(askWhereToSave),
            thirdLine = ASK_WHERE_TO_SAVE_PRIVATE,
            style = PageRowStyle.Inset,
            leadingIcon = Icons.Filled.Folder,
            onClick = { onAskWhereToSave(!askWhereToSave) },
            checked = askWhereToSave,
            trailing = { Switch(checked = askWhereToSave, onCheckedChange = null) },
        )
    }
}

/** Settings → Tor (#143, #275), for settings search. */
internal fun torRows(enabled: Boolean, startOnLaunch: Boolean, externalProxy: String = "") = listOf(
    settingsRow("tor-enabled", TOR_ENABLED, onOff(enabled), TOR_ENABLED_DETAIL, "Arti"),
    settingsRow(
        "tor-client", TOR_CLIENT, torClientSubtitle(externalProxy), externalProxy,
        TOR_CLIENT_EMBEDDED, TOR_CLIENT_EXTERNAL, "Orbot", "SOCKS",
    ),
    settingsRow("tor-launch", TOR_ON_LAUNCH, TOR_ON_LAUNCH_SUBTITLE, onOff(startOnLaunch), "onion"),
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
    externalProxy: String,
    onEditClient: () -> Unit,
    onEnabled: (Boolean) -> Unit,
    onStartOnLaunch: (Boolean) -> Unit,
) {
    SectionCard(title = stringResource(R.string.settings_section_tor)) {
        if ("tor-enabled" in visible) PageRow(
            title = stringResource(R.string.settings_tor_enabled),
            subtitle = stringResource(if (enabled) R.string.settings_on else R.string.settings_off),
            // Wraps: the whole explanation is readable on a phone.
            thirdLine = TOR_ENABLED_DETAIL,
            style = PageRowStyle.Inset,
            leadingIcon = Icons.Filled.VpnLock,
            onClick = { onEnabled(!enabled) },
            checked = enabled,
            trailing = { Switch(checked = enabled, onCheckedChange = null) },
        )
        if ("tor-client" in visible) PageRow(
            title = TOR_CLIENT,
            subtitle = torClientSubtitle(externalProxy),
            style = PageRowStyle.Inset,
            leadingIcon = Icons.Filled.Tune,
            // The whole host:port, never cut.
            thirdLine = externalProxy.ifEmpty { null },
            onClick = onEditClient,
        )
        if ("tor-launch" in visible) PageRow(
            title = TOR_ON_LAUNCH,
            subtitle = TOR_ON_LAUNCH_SUBTITLE,
            style = PageRowStyle.Inset,
            leadingIcon = Icons.Filled.PowerSettingsNew,
            enabled = enabled,
            onClick = { onStartOnLaunch(!startOnLaunch) },
            checked = startOnLaunch,
            trailing = {
                Switch(checked = startOnLaunch, onCheckedChange = null, enabled = enabled)
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
    SectionCard(title = stringResource(R.string.settings_section_nodes)) {
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
                            label = { Text(stringResource(R.string.settings_url)) },
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
                ) { Text(stringResource(R.string.common_save)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/**
 * Settings → Tor → Tor client (#275): "Embedded (Arti)" applies at once;
 * "External SOCKS proxy" takes a loopback `host:port` ([TorProxy.parse])
 * and applies on Save. Test runs [TorProxy.probe] — a CONNECT to a
 * `.onion` service through it — and, with Orbot installed and nothing
 * answering, offers to start it. Saving doesn't need a passing test (Orbot
 * may simply not be running yet): Freedom routes `.onion` to the proxy
 * only once the same probe passes.
 */
@Composable
private fun TorClientDialog(
    saved: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var externalSelected by remember { mutableStateOf(saved.isNotEmpty()) }
    var draft by remember { mutableStateOf(saved.ifEmpty { TorProxy.DEFAULT.authority }) }
    val parsed = TorProxy.parse(draft)
    val endpoint = parsed.endpoint
    // The test's endpoint and verdict (null while running); a new draft drops it.
    var test by remember { mutableStateOf<Pair<SocksEndpoint, TorProxy.Probe?>?>(null) }
    val shown = test?.takeIf { it.first == endpoint }
    val orbotInstalled = remember { TorProxy.orbotInstalled(context) }
    fun runTest(target: SocksEndpoint, delayMs: Long = 0) {
        test = target to null
        scope.launch {
            if (delayMs > 0) kotlinx.coroutines.delay(delayMs)
            val result = TorProxy.probe(target)
            if (test?.first == target) test = target to result
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(TOR_CLIENT) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                EngineRadioRow(
                    label = TOR_CLIENT_EMBEDDED,
                    selected = !externalSelected,
                    onClick = { onSave("") },
                )
                EngineRadioRow(
                    label = TOR_CLIENT_EXTERNAL,
                    selected = externalSelected,
                    onClick = { externalSelected = true },
                )
                if (externalSelected) {
                    NoSuggestionsTextInput {
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { draft = it.take(TOR_PROXY_MAX_LENGTH) },
                            label = { Text(stringResource(R.string.settings_tor_proxy_label)) },
                            placeholder = { Text(TorProxy.DEFAULT.authority) },
                            isError = endpoint == null,
                            supportingText = {
                                Text(parsed.rejection?.let(::torProxyHint) ?: TOR_CLIENT_HELPER)
                            },
                            singleLine = true,
                            keyboardOptions = urlKeyboardOptions(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = { endpoint?.let { runTest(it) } },
                            enabled = endpoint != null && !(shown != null && shown.second == null),
                        ) { Text(stringResource(R.string.settings_tor_test)) }
                        if (endpoint != null && orbotInstalled &&
                            (shown?.second == TorProxy.Probe.NotListening)
                        ) {
                            TextButton(onClick = {
                                TorProxy.requestOrbotStart(context)
                                runTest(endpoint, delayMs = 3_000)
                            }) { Text(stringResource(R.string.settings_tor_start_orbot)) }
                        }
                    }
                    if (shown != null && endpoint != null) {
                        val result = shown.second
                        Text(
                            if (result == null) {
                                stringResource(R.string.settings_tor_testing, endpoint.toString())
                            } else {
                                TorProxy.describe(result, endpoint)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = when (result) {
                                null -> MaterialTheme.colorScheme.onSurfaceVariant
                                TorProxy.Probe.Tor -> Color(0xFF22C55E)
                                else -> MaterialTheme.colorScheme.error
                            },
                        )
                        if (result == TorProxy.Probe.NotListening && orbotInstalled) {
                            Text(
                                ORBOT_START_NOTE,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (externalSelected) {
                TextButton(
                    onClick = { endpoint?.let { onSave(it.authority) } },
                    enabled = endpoint != null,
                ) { Text(stringResource(R.string.common_save)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/** A `host:port` is short; the field takes no more. */
private const val TOR_PROXY_MAX_LENGTH = 64

/** What to fix, for each reason [TorProxy.parse] refuses a proxy. */
private fun torProxyHint(rejection: TorProxy.Rejection): String = when (rejection) {
    TorProxy.Rejection.EMPTY,
    TorProxy.Rejection.FORMAT -> Strings.get(R.string.settings_tor_hint_format)
    TorProxy.Rejection.SCHEME -> Strings.get(R.string.settings_tor_hint_scheme)
    TorProxy.Rejection.NOT_LOOPBACK -> Strings.get(R.string.settings_tor_hint_not_loopback)
    TorProxy.Rejection.PORT -> Strings.get(R.string.settings_tor_hint_port)
}

/** What to fix, for each reason [ExternalEndpoints.validate] refuses a URL. */
private fun endpointHint(rejection: ExternalEndpoints.Rejection): String = when (rejection) {
    ExternalEndpoints.Rejection.EMPTY,
    ExternalEndpoints.Rejection.NOT_A_URL -> Strings.get(R.string.settings_endpoint_hint_not_a_url)
    ExternalEndpoints.Rejection.TOO_LONG -> Strings.get(R.string.settings_hint_too_long)
    ExternalEndpoints.Rejection.SCHEME -> Strings.get(R.string.settings_endpoint_hint_scheme)
    ExternalEndpoints.Rejection.QUERY_OR_FRAGMENT -> Strings.get(R.string.settings_endpoint_hint_query)
    ExternalEndpoints.Rejection.CREDENTIALS -> Strings.get(R.string.settings_hint_remove_credentials)
}

private val ADBLOCK_ALLOWLIST_EMPTY: String get() = Strings.get(R.string.settings_adblock_allowlist_empty)
private val ADBLOCK_ADD_SITE: String get() = Strings.get(R.string.settings_adblock_add_site)
private val ADBLOCK_ADD_SITE_SUBTITLE: String get() = Strings.get(R.string.settings_adblock_add_site_subtitle)
private val ADBLOCK_CREDITS: String get() = Strings.get(R.string.settings_adblock_credits)

private val ADBLOCK_AUTO_UPDATE: String get() = Strings.get(R.string.settings_adblock_auto_update)
private val ADBLOCK_AUTO_UPDATE_SUBTITLE: String get() = Strings.get(R.string.settings_adblock_auto_update_subtitle)
private val ADBLOCK_CHECK_UPDATES: String get() = Strings.get(R.string.settings_adblock_check_updates)
private val ADBLOCK_CHECK_UPDATES_SUBTITLE: String get() = Strings.get(R.string.settings_adblock_check_updates_subtitle)

/** List names in a status line: "EasyList, EasyPrivacy". */
private fun List<String>.joinNames(): String = joinToString(Strings.get(R.string.settings_list_separator))

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
    val builtIn = Strings.get(R.string.settings_adblock_lists_builtin)
    val version = status.listsVersion ?: return builtIn
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
                newer.size == all.size -> Strings.get(R.string.settings_adblock_reason_all_newer, version)
                else -> Strings.get(R.string.settings_adblock_reason_some_newer, newer.joinNames(), version)
            },
            when {
                damaged.isEmpty() -> null
                damaged.size == all.size -> Strings.get(R.string.settings_adblock_reason_all_damaged, version)
                else -> Strings.get(R.string.settings_adblock_reason_some_damaged, version, damaged.joinNames())
            },
            when {
                uncovered.isEmpty() -> null
                uncovered.size == all.size -> Strings.get(R.string.settings_adblock_reason_none_included, version)
                else -> Strings.get(R.string.settings_adblock_reason_some_not_included, version, uncovered.joinNames())
            },
        )
        if (reasons.isEmpty()) return builtIn
        return Strings.get(
            R.string.settings_adblock_lists_builtin_because,
            reasons.joinToString(Strings.get(R.string.settings_clause_separator)),
        )
    }
    val day = status.listsGeneratedAt?.take(10)?.takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) }
    val update = if (day != null) {
        Strings.get(R.string.settings_adblock_using_update_of, version, day)
    } else {
        Strings.get(R.string.settings_adblock_using_update, version)
    }
    if (status.builtInLists.isEmpty()) return update
    val damaged = status.damagedLists.filter { it in status.builtInLists }
    return if (damaged.isEmpty()) {
        Strings.get(
            R.string.settings_adblock_using_update_and_builtin,
            update, status.updatedLists.joinNames(), status.builtInLists.joinNames(),
        )
    } else {
        Strings.get(
            R.string.settings_adblock_using_update_and_builtin_damaged,
            update, status.updatedLists.joinNames(), status.builtInLists.joinNames(), version, damaged.joinNames(),
        )
    }
}

/** The Settings name of the category [key] ("ads" → "EasyList"). */
private fun adblockListName(key: String): String =
    AdblockCategory.entries.firstOrNull { it.key == key }?.listName ?: key

/**
 * The wrapping line under "Check for list updates": a check under way,
 * or how the last one ended; `null` before the first check.
 */
internal fun adblockUpdateLine(update: AdblockUpdateState): String? {
    if (update.checking) return Strings.get(R.string.settings_checking)
    return when (val last = update.last) {
        null -> null
        is AdblockUpdateOutcome.Applied ->
            if (last.olderThanBuiltIn.isEmpty()) {
                Strings.get(R.string.settings_adblock_updated, last.version)
            } else {
                Strings.plural(
                    R.plurals.settings_adblock_updated_builtin_newer, last.olderThanBuiltIn.size,
                    last.version, last.olderThanBuiltIn.map { adblockListName(it) }.joinNames(),
                )
            }
        is AdblockUpdateOutcome.BuiltInNewer ->
            Strings.get(R.string.settings_adblock_builtin_newer, last.version)
        is AdblockUpdateOutcome.UpToDate ->
            if (last.version > 0) {
                Strings.get(R.string.settings_adblock_up_to_date_version, last.version)
            } else {
                Strings.get(R.string.settings_adblock_up_to_date)
            }
        AdblockUpdateOutcome.FeedUnavailable -> Strings.get(R.string.settings_adblock_feed_unavailable)
        is AdblockUpdateOutcome.Rejected -> Strings.get(R.string.settings_adblock_rejected, last.reason)
        is AdblockUpdateOutcome.DownloadFailed ->
            Strings.get(R.string.settings_adblock_download_failed, adblockListName(last.category))
        is AdblockUpdateOutcome.HashMismatch ->
            Strings.get(R.string.settings_adblock_hash_mismatch, adblockListName(last.category))
        AdblockUpdateOutcome.NothingEnabled -> Strings.get(R.string.settings_adblock_nothing_enabled)
        is AdblockUpdateOutcome.Failed -> Strings.get(R.string.settings_adblock_failed, last.message)
    }
}

/** The line under a category: its list, and while it's on, how the engine is doing. */
internal fun adblockCategorySubtitle(category: AdblockCategory, on: Boolean, status: AdblockStatus): String =
    if (on && status.loading) Strings.get(R.string.settings_adblock_category_loading, category.listName) else category.listName

private fun adblockSectionRows(
    enabled: Set<AdblockCategory>,
    allowlist: List<String>,
    status: AdblockStatus,
    update: AdblockUpdateState,
) = buildList {
    for (category in AdblockCategory.entries) {
        add(settingsRow(category, category.title, category.listName, onOff(category in enabled)))
    }
    val updateWords = searchKeywords(R.string.settings_adblock_updates_keywords)
    add(settingsRow("auto-update", ADBLOCK_AUTO_UPDATE, ADBLOCK_AUTO_UPDATE_SUBTITLE, adblockListsLine(status), *updateWords))
    add(settingsRow("update-check", ADBLOCK_CHECK_UPDATES, ADBLOCK_CHECK_UPDATES_SUBTITLE, adblockUpdateLine(update), *updateWords))
    add(
        settingsRow(
            "allowlist-add", ADBLOCK_ADD_SITE, ADBLOCK_ADD_SITE_SUBTITLE,
            *searchKeywords(R.string.settings_adblock_add_site_keywords),
        ),
    )
    if (allowlist.isEmpty()) {
        add(settingsRow("allowlist-empty", ADBLOCK_ALLOWLIST_EMPTY))
    } else {
        for (site in allowlist) {
            add(
                settingsRow(
                    "site:$site", allowlistHostForDisplay(site), allowlistSiteSubtitle(site),
                    *searchKeywords(R.string.settings_adblock_site_keywords), site,
                ),
            )
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
    return if (shown == site) {
        Strings.get(R.string.settings_adblock_site_allowed)
    } else {
        Strings.get(R.string.settings_adblock_site_allowed_punycode, site)
    }
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
    SectionCard(title = stringResource(R.string.settings_section_adblock)) {
        for (category in AdblockCategory.entries) {
            if (category !in visible) continue
            val on = category in enabled
            PageRow(
                title = category.title,
                subtitle = adblockCategorySubtitle(category, on, status),
                style = PageRowStyle.Inset,
                leadingIcon = Icons.Filled.Shield,
                onClick = { onToggle(category, !on) },
                checked = on,
                trailing = {
                    Switch(checked = on, onCheckedChange = null)
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
            checked = autoUpdate,
            trailing = {
                Switch(checked = autoUpdate, onCheckedChange = null)
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
                        contentDescription = stringResource(R.string.settings_adblock_site_remove, shown),
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
        title = { Text(stringResource(R.string.settings_adblock_allow_site_title)) },
        text = {
            NoSuggestionsTextInput {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text(stringResource(R.string.settings_adblock_allow_site_label)) },
                    placeholder = { Text(stringResource(R.string.settings_adblock_allow_site_placeholder)) },
                    isError = draft.isNotBlank() && host == null,
                    supportingText = {
                        Text(
                            if (draft.isNotBlank() && host == null) {
                                stringResource(R.string.settings_adblock_allow_site_invalid)
                            } else {
                                stringResource(R.string.settings_adblock_allow_site_helper)
                            },
                        )
                    },
                    singleLine = true,
                    keyboardOptions = urlKeyboardOptions(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { host?.let(onAdd) }, enabled = host != null) { Text(stringResource(R.string.common_add)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

private val ROW_CLEAR_HISTORY: String get() = Strings.get(R.string.settings_clear_history)
private val ROW_CLEAR_BOOKMARKS: String get() = Strings.get(R.string.settings_clear_bookmarks)
private val ROW_CLEAR_SITE_DATA: String get() = Strings.get(R.string.settings_clear_site_data)
private val ROW_CLEAR_SITE_DATA_SUBTITLE: String get() = Strings.get(R.string.settings_clear_site_data_subtitle)

private fun historySubtitle(count: Int) =
    if (count == 0) {
        Strings.get(R.string.settings_nothing_to_clear)
    } else {
        Strings.plural(R.plurals.settings_history_visits, count, count)
    }

private fun bookmarksSubtitle(count: Int) =
    if (count == 0) {
        Strings.get(R.string.settings_nothing_to_clear)
    } else {
        Strings.plural(R.plurals.settings_bookmarks_count, count, count)
    }

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
    SectionCard(title = stringResource(R.string.settings_section_browsing)) {
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
private val PERMISSIONS_EMPTY: String get() = Strings.get(R.string.settings_permissions_empty)

/** A wallet connection's row key in Site permissions: its own type, so it never equals a [SitePermissionEntry]. */
internal data class DappConnectionRow(val origin: String)

/** "Wallet · Account 1 · 0x9858…da94 · Gnosis Chain": a connected site's line in Site permissions. */
internal fun dappConnectionDetail(grant: DappGrantStore.Grant, accounts: List<WalletAccount>, chains: List<Chain>) =
    Strings.get(R.string.settings_dapp_connection_detail, connectedSiteSummary(grant, accounts, chains))

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
    SectionCard(title = stringResource(R.string.settings_section_permissions)) {
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
                    .clickable(onClickLabel = stringResource(R.string.common_open)) { onOpenSite(grant.origin) }
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
                        contentDescription = stringResource(R.string.settings_dapp_disconnect, site),
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
                    sitePermissionIcon(entry.permission),
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
                        contentDescription = stringResource(
                            R.string.settings_permission_remove,
                            entry.permission.label,
                            permissionOriginDisplay(entry.origin),
                        ),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** "1.2.3 (build 45)", as the About card shows it. */
private fun appVersionLabel(context: Context): String {
    val info = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0)
    }.getOrNull()
    val versionName = info?.versionName ?: Strings.get(R.string.settings_app_version_unknown)
    @Suppress("DEPRECATION")
    val versionCode = info?.let {
        if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode
        else it.versionCode.toLong()
    } ?: 0L
    return Strings.get(R.string.settings_app_version, versionName, versionCode)
}

private val ABOUT_NAME: String get() = Strings.get(R.string.app_name)
private val ABOUT_TAGLINE: String get() = Strings.get(R.string.settings_about_tagline)
private val ABOUT_BLURB: String get() = Strings.get(R.string.settings_about_blurb)

private val UPDATES_CHECK: String get() = Strings.get(R.string.settings_updates_check)
private val LICENCES: String get() = Strings.get(R.string.settings_licences)
private val LICENCES_SUBTITLE: String get() = Strings.get(R.string.settings_licences_subtitle)
private val UPDATES_CHECK_SUBTITLE: String get() = Strings.get(R.string.settings_updates_check_subtitle)
private val UPDATES_CHECK_NOW: String get() = Strings.get(R.string.settings_updates_check_now)
private val UPDATES_CHECK_NOW_SUBTITLE: String get() = Strings.get(R.string.settings_updates_check_now_subtitle)
private val UPDATES_OPEN_SUBTITLE: String get() = Strings.get(R.string.settings_updates_open_subtitle)

/**
 * The line under **Check for updates** for a build from an app store:
 * it doesn't check, the store updates it. `null` otherwise.
 */
internal fun appUpdateStoreLine(update: AppUpdateState): String? =
    update.store?.let { Strings.get(R.string.settings_updates_from_store, it.text) }

/**
 * The line under **Check now**: a check under way, what the last one
 * found (the newer release, or that this is the latest), or why it
 * failed — with when it last ran, by [formatTime].
 */
internal fun appUpdateLine(update: AppUpdateState, formatTime: (Long) -> String): String {
    if (update.store != null) return Strings.get(R.string.settings_updates_come_from, update.store.text)
    if (update.checking) return Strings.get(R.string.settings_checking)
    val available = update.available
    val found = when {
        available != null ->
            Strings.get(R.string.settings_updates_available, available.version.toString(), update.installedName)
        update.latest != null -> Strings.get(R.string.settings_updates_up_to_date, update.latest.version.toString())
        else -> null
    }
    val failed = (update.last as? UpdateCheckOutcome.Failed)
        ?.let { Strings.get(R.string.settings_updates_last_failed, it.reason) }
    val lead = failed ?: found
        ?: return update.lastCheckedAt?.let { Strings.get(R.string.settings_updates_last_checked_alone, formatTime(it)) }
            ?: Strings.get(R.string.settings_updates_not_checked)
    val checked = update.lastCheckedAt?.let { Strings.get(R.string.settings_updates_last_checked, formatTime(it)) }
    return listOfNotNull(lead, checked).joinToString(" · ")
}

/** The title of the row that opens a newer release's page: the home notice's. */
internal fun appUpdateAvailableTitle(release: LatestRelease): String = updateNoticeTitle(release)

private fun aboutRows(version: String, packageName: String, update: AppUpdateState, checkOn: Boolean) = buildList {
    add(settingsRow("app", ABOUT_NAME, ABOUT_TAGLINE))
    add(settingsRow("version", Strings.get(R.string.settings_version), version))
    add(settingsRow("package", Strings.get(R.string.settings_package), packageName))
    add(
        settingsRow(
            "update-check", UPDATES_CHECK, UPDATES_CHECK_SUBTITLE, appUpdateStoreLine(update),
            onOff(checkOn), *searchKeywords(R.string.settings_updates_check_keywords),
        ),
    )
    add(
        settingsRow(
            "update-now", UPDATES_CHECK_NOW, UPDATES_CHECK_NOW_SUBTITLE, appUpdateLine(update) { txDateFormat().format(java.util.Date(it)) },
            *searchKeywords(R.string.settings_updates_check_now_keywords),
        ),
    )
    update.available?.let {
        add(
            settingsRow(
                "update-open", appUpdateAvailableTitle(it), UPDATES_OPEN_SUBTITLE,
                *searchKeywords(R.string.settings_updates_open_keywords), it.url,
            ),
        )
    }
    add(settingsRow("licences", LICENCES, LICENCES_SUBTITLE, *searchKeywords(R.string.settings_licences_keywords)))
    add(settingsRow("blurb", ABOUT_BLURB))
}

@Composable
private fun AboutSection(
    visible: Set<Any>,
    version: String,
    update: AppUpdateState,
    checkForUpdates: Boolean,
    onCheckForUpdates: (Boolean) -> Unit,
    onCheckNow: () -> Unit,
    onOpenRelease: (LatestRelease) -> Unit,
    onOpenLicences: () -> Unit,
) {
    val context = LocalContext.current
    SectionCard(title = stringResource(R.string.settings_section_about)) {
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
        if ("version" in visible) DetailRow(stringResource(R.string.settings_version), version)
        if ("package" in visible) DetailRow(stringResource(R.string.settings_package), context.packageName, mono = true)
        val available = update.available
        if ("update-open" in visible && available != null) PageRow(
            title = appUpdateAvailableTitle(available),
            subtitle = UPDATES_OPEN_SUBTITLE,
            style = PageRowStyle.Inset,
            leadingIcon = Icons.Filled.NewReleases,
            onClick = { onOpenRelease(available) },
        )
        // A store install doesn't check: the switch shows why, greyed out.
        val fromStore = update.store != null
        if ("update-check" in visible) PageRow(
            title = UPDATES_CHECK,
            subtitle = UPDATES_CHECK_SUBTITLE,
            thirdLine = appUpdateStoreLine(update),
            style = PageRowStyle.Inset,
            leadingIcon = Icons.Filled.Update,
            enabled = !fromStore,
            onClick = { if (!fromStore) onCheckForUpdates(!checkForUpdates) },
            checked = checkForUpdates && !fromStore,
            trailing = {
                Switch(
                    checked = checkForUpdates && !fromStore,
                    onCheckedChange = null,
                    enabled = !fromStore,
                )
            },
        )
        if ("update-now" in visible) PageRow(
            title = UPDATES_CHECK_NOW,
            subtitle = UPDATES_CHECK_NOW_SUBTITLE,
            thirdLine = appUpdateLine(update) { txDateFormat().format(java.util.Date(it)) },
            style = PageRowStyle.Inset,
            leadingIcon = Icons.Filled.Sync,
            enabled = !fromStore && !update.checking,
            onClick = onCheckNow,
        )
        if ("licences" in visible) PageRow(
            title = LICENCES,
            subtitle = LICENCES_SUBTITLE,
            style = PageRowStyle.Inset,
            leadingIcon = Icons.Filled.Description,
            onClick = onOpenLicences,
        )
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

private val ROW_ADVANCED: String get() = Strings.get(R.string.settings_advanced)
private val ROW_ADVANCED_SUBTITLE: String get() = Strings.get(R.string.settings_advanced_subtitle)

private fun otherRows() = listOf(settingsRow("advanced", ROW_ADVANCED, ROW_ADVANCED_SUBTITLE))

@Composable
private fun OtherSection(
    showIpfsUi: Boolean,
    onToggleShowIpfsUi: (Boolean) -> Unit,
) {
    SectionCard(title = stringResource(R.string.settings_section_other)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .switchRow(checked = showIpfsUi, onCheckedChange = onToggleShowIpfsUi)
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
                onCheckedChange = null,
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
private val ROW_ROUTING_MODE: String get() = Strings.get(R.string.settings_routing_mode)
private val ROW_ROUTING_MODE_SUBTITLE: String get() = Strings.get(R.string.settings_routing_mode_subtitle)
private val ROUTING_MODE_HELPER: String get() = Strings.get(R.string.settings_routing_mode_helper)

/**
 * The IPFS switch with the node's details under it (one row: they
 * describe the switch), and the routing-mode picker with every mode it
 * offers and its helper line.
 */
internal fun ipfsRows(info: IpfsInfo) = listOf(
    settingsRow(
        "status",
        SECTION_IPFS,
        ipfsStatusTriple(info).label,
        // The details [IpfsSection] lists under the switch.
        *(if (info.gatewayUrl.isNotBlank()) arrayOf(
            Strings.get(R.string.settings_ipfs_blocks_fetched), info.connectedPeers.toString(),
            Strings.get(R.string.settings_ipfs_gateway_label), info.gatewayUrl,
            Strings.get(R.string.settings_ipfs_client),
            info.clientVersion.takeIf { it.isNotBlank() }?.let { "freedom-ipfs/$it" },
        ) else emptyArray()),
        *(if (!info.errorMessage.isNullOrBlank()) {
            arrayOf(Strings.get(R.string.settings_ipfs_error), info.errorMessage)
        } else emptyArray()),
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
    onOpenLogs: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val routingMode by settings.ipfsRoutingMode
        .collectAsState(initial = NodeSettings.DEFAULT_IPFS_ROUTING_MODE)

    val triple = ipfsStatusTriple(ipfsInfo)
    val isOn = ipfsInfo.status != IpfsStatus.Stopped

    SectionCard(title = stringResource(R.string.settings_section_ipfs)) {
        if ("status" in visible) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .switchRow(checked = isOn, onCheckedChange = onIpfsToggle)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(triple.icon, contentDescription = null, tint = triple.color)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_section_ipfs), fontWeight = FontWeight.Medium)
                    Text(
                        triple.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = isOn,
                    onCheckedChange = null,
                )
            }

            if (ipfsInfo.gatewayUrl.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                // connectedPeers carries verified blocks fetched — the
                // reader has no peer set (see swarmnode IpfsNode).
                DetailRow(stringResource(R.string.settings_ipfs_blocks_fetched), ipfsInfo.connectedPeers.toString())
                DetailRow(stringResource(R.string.settings_ipfs_gateway_label), ipfsInfo.gatewayUrl, mono = true)
                if (ipfsInfo.clientVersion.isNotBlank()) {
                    DetailRow(
                        stringResource(R.string.settings_ipfs_client),
                        "freedom-ipfs/${ipfsInfo.clientVersion}",
                        mono = true,
                    )
                }
            }
            val err = ipfsInfo.errorMessage
            if (!err.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                DetailRow(stringResource(R.string.settings_ipfs_error), err, singleLine = false)
            }
            LogsButton(onOpenLogs)
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
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
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
        Color(0xFF22C55E), Icons.Filled.CheckCircle, Strings.get(R.string.settings_ipfs_connected),
    )
    IpfsStatus.Starting -> IpfsStatusTriple(
        Color(0xFFF59E0B), Icons.Filled.HourglassTop, Strings.get(R.string.settings_ipfs_connecting),
    )
    IpfsStatus.Stopped -> IpfsStatusTriple(
        Color(0xFF94A3B8), Icons.Filled.PowerSettingsNew, Strings.get(R.string.settings_ipfs_disconnected),
    )
    IpfsStatus.Error -> IpfsStatusTriple(
        Color(0xFFEF4444), Icons.Filled.ErrorOutline, Strings.get(R.string.settings_ipfs_error),
    )
}
