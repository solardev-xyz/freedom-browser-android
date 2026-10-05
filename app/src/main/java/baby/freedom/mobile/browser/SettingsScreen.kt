package baby.freedom.mobile.browser

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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
import java.text.NumberFormat

/**
 * Full-screen settings page, in two levels like Chrome's and Safari's
 * (#400): a short top-level list whose rows each name a page and sum up
 * its current state, grouped General / Privacy / Web3 / About
 * ([settingsTopLevel]); a row opens its sub-page in place of the list,
 * and Back (the system's, or the ← at the top) goes one level up. Search
 * engine opens its dialog straight from the top level, and Default
 * browser is a top-level row only while Freedom isn't the default
 * browser. Which card lives on which page is [SettingsSection.page]:
 *
 *  - **Appearance** — the theme ([Appearance], #269) and language (#280).
 *  - **Downloads** — *Ask where to save each file* (#322).
 *  - **Privacy & security** — Browsing data: one row to Delete browsing
 *    data ([DeleteBrowsingDataPage], #400); Site permissions: every camera / microphone /
 *    location decision (#81), "open in another app" (#85), and the sites
 *    connected to the wallet (#110, #111); Tor (#143, #275).
 *  - **Ad blocking** — filter-list categories, updates and the sites
 *    ad blocking is off for (#126, [Adblock]).
 *  - **Wallet & chains** — the wallet's row (#75, #76), which opens
 *    [WalletScreen]; then Chains (#107, [ChainsSection]), whose Add chain
 *    and per-chain pages replace the screen while open.
 *  - **Name resolution** — the resolution order, Colibri, CCIP-Read and
 *    the RPC providers names resolve through (#102,
 *    [NameResolutionSection]).
 *  - **Nodes & networks** — a row to the Nodes page ([NodeScreen]); where
 *    `bzz://` and `ipfs://` content comes from: the embedded nodes or an
 *    external endpoint (#125, [ExternalEndpoints]); the Radicle node's
 *    row ([RadicleScreen], #73); and the embedded IPFS node's switch,
 *    details and routing mode.
 *  - **About Freedom** — version, update checks (#272, [AppUpdates]),
 *    open-source licences (#325); and, once Freedom is the default
 *    browser, a muted line saying so.
 *
 * Every card keeps its own rows and search index ([visibleSettingsRows],
 * #93). A query in the field above the top level lists the matching
 * rows of every page in one list, each page's cards under its name in
 * top-level order ([settingsResultGroups]); the rows work in place, and
 * the name opens the page at the first matching card. Back clears a
 * query before it closes the screen.
 */
@Composable
fun SettingsScreen(
    repo: BrowsingRepository,
    ipfsInfo: IpfsInfo,
    onIpfsToggle: (Boolean) -> Unit,
    /**
     * Delete browsing data (#400): history in the range is deleted here;
     * the host deletes the rest [DeleteChoice] names — closed tabs,
     * WebView state, node logs.
     */
    onDeleteBrowsingData: (DeleteChoice) -> Unit,
    onDismiss: () -> Unit,
    radicle: RadicleControls = RadicleControls(),
    onOpenRadicle: () -> Unit = {},
    onOpenWallet: () -> Unit = {},
    /** The Nodes page, from Settings → Nodes & networks. */
    onOpenNodes: () -> Unit = {},
    /** Open the node logs page (#276) at the IPFS node's. */
    onOpenIpfsLogs: () -> Unit = {},
    /** A newer release's page (#272), in a new tab in front of Settings. */
    onOpenUrl: (String) -> Unit = {},
) {
    BackHandler(onBack = onDismiss)
    // Settings search (#93). Registered after the dismiss handler so it
    // wins while there's a query: Back clears the filter first and puts
    // the top level back, like Esc in the desktop browser's field.
    var query by rememberSaveable { mutableStateOf("") }
    // The sub-page open in place of the top level (#400), if any; its
    // handler, registered last, wins: Back goes one level up, to the
    // search results when the page was opened from one.
    var page by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    // The card a page opened from a search result starts scrolled to.
    var scrollTo by remember { mutableStateOf<SettingsSection?>(null) }
    BackHandler(enabled = query.isNotEmpty() && page == null) { query = "" }
    BackHandler(enabled = page != null) { page = null }

    val context = LocalContext.current
    val settings = remember(context) { NodeSettings.get(context) }
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

    // Delete browsing data (#400), a page standing in for the list like Licences.
    var deleteDataOpen by rememberSaveable { mutableStateOf(false) }

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
    // Language (#280): only on Android 13+ and once there is more than one.
    val appLanguage = rememberAppLanguage()
    val defaultBrowser = rememberDefaultBrowserState()
    val isDefaultBrowser = defaultBrowser.isDefault

    // Each card's rows, as the page shows them right now (see [SettingsRow]).
    val sectionRows: Map<SettingsSection, List<SettingsRow>> = mapOf(
        SettingsSection.Search to searchSectionRows(searchEngine, customSearchTemplate),
        SettingsSection.DefaultBrowser to defaultBrowserRows(isDefaultBrowser),
        SettingsSection.Appearance to appearanceSectionRows(appearance, appLanguage),
        SettingsSection.Downloads to downloadSettingsRows(askWhereToSave),
        SettingsSection.Browsing to browsingDataRows(),
        SettingsSection.Permissions to sitePermissionRows(permissionEntries, dappGrants, walletAccounts, chains),
        SettingsSection.Tor to torRows(torEnabled, torStartOnLaunch, torExternalProxy),
        SettingsSection.Adblock to
            adblockSectionRows(adblockCategories, adblockAllowlist, adblockStatus, adblockUpdate),
        SettingsSection.Wallet to walletSettingsRows(walletState),
        SettingsSection.Chains to chainSettingsRows(chains),
        SettingsSection.Ens to ensSectionRows(ensRpcConfig),
        SettingsSection.Rpc to rpcSectionRows(ensRpcConfig),
        SettingsSection.Nodes to nodeRows(externalSwarm, externalIpfs) + radicleSettingsRow(radicle),
        SettingsSection.Ipfs to ipfsRows(ipfsInfo),
        SettingsSection.About to aboutRows(appVersion, context.packageName, appUpdate, checkForUpdates),
    )
    // For the query: each card's matching rows; a query naming the card
    // or its page shows the whole card. An empty set hides the card.
    val matches = sectionRows.mapValues { (section, rows) ->
        visibleSettingsRows(query, sectionTitle(section), rows, section.page(isDefaultBrowser).title)
    }
    val searching = query.isNotBlank()
    val resultGroups = settingsResultGroups(matches, isDefaultBrowser)

    // A new query starts the results from the top, so the first match
    // isn't left scrolled off above the viewport.
    val listState = rememberLazyListState()
    LaunchedEffect(listState) {
        snapshotFlow { query }.drop(1).collect { listState.scrollToItem(0) }
    }

    // The Chains sub-pages stand in for the list while open. The scaffold
    // below leaves composition meanwhile, so its scroll positions are
    // hoisted here (listState above, pageState below) and Back lands on
    // the list or sub-page as it was left.
    when (val chainSubPage = chainPage) {
        ChainPage.Search -> ChainlistPage(
            query = chainQuery,
            onQueryChange = { chainQuery = it },
            existingIds = chains.mapTo(HashSet()) { it.id },
            onPick = { chainPage = ChainPage.Form(it.toChain()) },
            onManual = { chainPage = ChainPage.Form(null) },
            onBack = { chainPage = null },
        )
        is ChainPage.Form -> AddChainPage(
            prefill = chainSubPage.prefill,
            onAdd = chainStore::add,
            onAdded = {
                chainPage = null
                chainQuery = ""
            },
            onBack = { chainPage = if (chainSubPage.prefill != null) ChainPage.Search else null },
        )
        is ChainPage.Detail -> {
            val chain = chains.firstOrNull { it.id == chainSubPage.chainId }
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
                LaunchedEffect(chainSubPage) { chainPage = null }
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
    if (deleteDataOpen && chainPage == null && site == null && !licencesOpen) {
        DeleteBrowsingDataPage(
            repo = repo,
            onDelete = { choice ->
                onDeleteBrowsingData(choice)
                deleteDataOpen = false
                Toast.makeText(context, deleteDoneMessage(choice), Toast.LENGTH_LONG).show()
            },
            onBack = { deleteDataOpen = false },
        )
    }

    /** One card, showing the rows in [visible] — the same composables on a sub-page and in search results. */
    @Composable
    fun Section(section: SettingsSection, visible: Set<Any>) = when (section) {
        SettingsSection.Wallet -> WalletSection(state = walletState, onOpen = onOpenWallet)
        SettingsSection.Search -> SearchSection(
            engineId = searchEngine,
            customTemplate = customSearchTemplate,
            onClick = { pickSearchEngine = true },
        )
        SettingsSection.Appearance -> AppearanceSection(
            visible = visible,
            appearance = appearance,
            onClick = { pickAppearance = true },
            language = appLanguage,
            onLanguageClick = {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    AppLanguage.openSettings(context)
                }
            },
        )
        SettingsSection.DefaultBrowser ->
            if (isDefaultBrowser) DefaultBrowserLine(onClick = defaultBrowser.onClick) else DefaultBrowserSection(defaultBrowser)
        SettingsSection.Adblock -> AdblockSection(
            visible = visible,
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
            onRemoveSite = { allowed -> Adblock.removeAllowlisted(allowed) },
            onAddSite = { addAllowlistSite = true },
        )
        SettingsSection.Ens -> NameResolutionSection(
            visible = visible,
            config = ensRpcConfig,
            settings = settings,
        )
        SettingsSection.Rpc -> RpcProvidersSection(
            visible = visible,
            config = ensRpcConfig,
            settings = settings,
        )
        SettingsSection.Browsing -> BrowsingDataSection(onOpen = { deleteDataOpen = true })
        SettingsSection.Downloads -> DownloadSettingsSection(
            askWhereToSave = askWhereToSave,
            onAskWhereToSave = { on -> scope.launch { settings.setAskWhereToSave(on) } },
        )
        SettingsSection.Permissions -> SitePermissionsSection(
            visible = visible,
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
        SettingsSection.Nodes -> NodesSection(
            visible = visible,
            externalSwarm = externalSwarm,
            externalIpfs = externalIpfs,
            onEdit = { editEndpoint = it },
            radicle = radicle,
            onOpenRadicle = onOpenRadicle,
            onOpenNodes = onOpenNodes,
        )
        SettingsSection.Tor -> TorSettingsSection(
            visible = visible,
            enabled = torEnabled,
            startOnLaunch = torStartOnLaunch,
            externalProxy = torExternalProxy,
            onEditClient = { editTorClient = true },
            onEnabled = { on -> scope.launch { settings.setTorEnabled(on) } },
            onStartOnLaunch = { on -> scope.launch { settings.setTorStartOnLaunch(on) } },
        )
        SettingsSection.Chains -> ChainsSection(
            visible = visible,
            chains = chains,
            onOpen = { chainPage = ChainPage.Detail(it.id) },
            onRemove = { confirmRemoveChain = it },
            onAdd = { chainPage = ChainPage.Search },
        )
        SettingsSection.About -> AboutSection(
            visible = visible,
            version = appVersion,
            update = appUpdate,
            checkForUpdates = checkForUpdates,
            onCheckForUpdates = { on -> scope.launch { settings.setCheckForUpdates(on) } },
            onCheckNow = { AppUpdates.checkForUpdates() },
            onOpenRelease = { onOpenUrl(it.url) },
            onOpenLicences = { licencesOpen = true },
        )
        SettingsSection.Ipfs -> IpfsSection(
            visible = visible,
            settings = settings,
            ipfsInfo = ipfsInfo,
            onIpfsToggle = onIpfsToggle,
            onOpenLogs = onOpenIpfsLogs,
        )
    }

    /** The one-line state under a top-level row. */
    fun summary(p: SettingsPage): String = when (p) {
        SettingsPage.SearchEngine -> SearchEngines.labelFor(searchEngine, customSearchTemplate)
        SettingsPage.Appearance -> appearancePageSummary(appearance.label, appLanguage)
        SettingsPage.Downloads -> downloadsPageSummary(askWhereToSave)
        SettingsPage.DefaultBrowser -> DefaultBrowser.ROW_SET_SUBTITLE
        SettingsPage.Privacy -> privacyPageSummary(permissionEntries.size + dappGrants.size, torEnabled)
        SettingsPage.Adblock ->
            adblockPageSummary(adblockCategories.size, AdblockCategory.entries.size, adblockAllowlist.size)
        SettingsPage.Wallet -> walletPageSummary(walletSummary(walletState), chains.size)
        SettingsPage.Names -> namesPageSummary(ensRpcConfig.colibri, ensRpcConfig.sources.size)
        SettingsPage.Nodes ->
            nodesPageSummary(externalSwarm, externalIpfs, radicleSummary(radicle.info, radicle.enabled))
        SettingsPage.About -> appVersion
    }

    // A line that must stay in view from the top level too: the wallet's
    // backup reminder, Default browser's "not changed".
    fun attention(p: SettingsPage): String? = when (p) {
        SettingsPage.Wallet -> walletAttentionLine(walletState)
        SettingsPage.DefaultBrowser -> if (defaultBrowser.declined) DefaultBrowser.DECLINED_LINE else null
        else -> null
    }

    fun open(p: SettingsPage) = when (p) {
        SettingsPage.SearchEngine -> pickSearchEngine = true
        SettingsPage.DefaultBrowser -> defaultBrowser.onClick()
        else -> page = p
    }

    val openPage = page
    // Held out here, not inside the scaffold: a Chains, Connected-site or
    // Licences page replaces the whole scaffold while open, and Back must
    // land on the sub-page scrolled where it was left. A new page (or the
    // same one opened again from the top level) starts at the top.
    val pageState = remember(openPage) { LazyListState() }
    if (chainPage == null && site == null && !licencesOpen && !deleteDataOpen) FullScreenScaffold(
        title = openPage?.title ?: stringResource(R.string.settings_title),
        // On a sub-page the ← goes up to the top level, as Back does.
        onDismiss = { if (page != null) page = null else onDismiss() },
    ) {
        if (openPage == null) Column(modifier = Modifier.fillMaxSize()) {
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
                if (!searching) {
                    for ((group, pages) in settingsTopLevel(isDefaultBrowser)) item(group.name) {
                        SectionCard(title = stringResource(group.titleRes)) {
                            for (p in pages) PageRow(
                                title = p.title,
                                subtitle = summary(p),
                                style = PageRowStyle.Inset,
                                leadingIcon = p.icon,
                                thirdLine = attention(p),
                                onClick = { open(p) },
                            )
                        }
                    }
                } else {
                    for ((p, sections) in resultGroups) {
                        // A page's name over its matches, which opens it
                        // at the first; a row with no page of its own
                        // (Search engine, Default browser) needs none, and
                        // comes first so it never sits under another page's.
                        if (p.hasSubPage) item("page:${p.name}") {
                            SettingsResultHeading(p.title) {
                                scrollTo = sections.first()
                                page = p
                            }
                        }
                        for (section in sections) item(section.name) {
                            Section(section, matches.getValue(section))
                        }
                    }
                    if (resultGroups.isEmpty()) item("no-match") {
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
        } else androidx.compose.runtime.key(openPage) {
            val sections = settingsSections(openPage, isDefaultBrowser)
            // Re-runs after a Chains/site/Licences page closes, by which
            // time scrollTo is null, so it leaves the kept position alone.
            LaunchedEffect(Unit) {
                val target = scrollTo
                scrollTo = null
                val index = sections.indexOf(target)
                if (index > 0) pageState.scrollToItem(index)
            }
            LazyColumn(
                state = pageState,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                for (section in sections) item(section.name) {
                    Section(section, sectionRows.getValue(section).mapTo(LinkedHashSet()) { it.key })
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
private val SECTION_IPFS: String get() = Strings.get(R.string.settings_section_ipfs)

/** Each card's title, as its [SectionCard] heads it and search matches it. */
private fun sectionTitle(section: SettingsSection): String = when (section) {
    SettingsSection.Search -> SECTION_SEARCH
    SettingsSection.DefaultBrowser -> DefaultBrowser.SECTION
    SettingsSection.Appearance -> SECTION_APPEARANCE
    SettingsSection.Downloads -> SECTION_DOWNLOADS
    SettingsSection.Browsing -> SECTION_BROWSING
    SettingsSection.Permissions -> SECTION_PERMISSIONS
    SettingsSection.Tor -> SECTION_TOR
    SettingsSection.Adblock -> SECTION_ADBLOCK
    SettingsSection.Wallet -> SECTION_WALLET
    SettingsSection.Chains -> SECTION_CHAINS
    SettingsSection.Ens -> SECTION_ENS
    SettingsSection.Rpc -> SECTION_RPC
    SettingsSection.Nodes -> SECTION_NODES
    SettingsSection.Ipfs -> SECTION_IPFS
    SettingsSection.About -> SECTION_ABOUT
}

/**
 * A sub-page's name over its cards in the search results (#400): a
 * heading for TalkBack's heading navigation, and a full-width 48 dp
 * target that opens the page at its first matching card.
 */
@Composable
private fun SettingsResultHeading(title: String, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable(onClickLabel = stringResource(R.string.settings_page_open, title), onClick = onOpen)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

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

private val NODES_PAGE: String get() = Strings.get(R.string.settings_nodes_page)
private val NODES_PAGE_SUBTITLE: String get() = Strings.get(R.string.settings_nodes_page_subtitle)
private const val NODES_PAGE_KEY = "nodes-page"

/**
 * The row to the Nodes page, then where `bzz://` and `ipfs://` content
 * comes from (#125) — both always listed, IPFS no longer behind an
 * advanced-options switch (#400 item 8).
 */
internal fun nodeRows(externalSwarm: String, externalIpfs: String) =
    listOf(
        settingsRow(NODES_PAGE_KEY, NODES_PAGE, NODES_PAGE_SUBTITLE, Strings.get(R.string.node_screen_title)),
        settingsRow(
            NodeEndpoint.Swarm.key,
            NodeEndpoint.Swarm.title,
            endpointSubtitle(NodeEndpoint.Swarm, externalSwarm),
            externalSwarm,
            *searchKeywords(NodeEndpoint.Swarm.keywordsRes),
        ),
        settingsRow(
            NodeEndpoint.Ipfs.key,
            NodeEndpoint.Ipfs.title,
            endpointSubtitle(NodeEndpoint.Ipfs, externalIpfs),
            externalIpfs,
            externalIpfs.takeIf { it.isNotEmpty() }?.let { NodeEndpoint.Ipfs.warning },
            *searchKeywords(NodeEndpoint.Ipfs.keywordsRes),
        ),
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
    onOpenNodes: () -> Unit,
) {
    SectionCard(title = stringResource(R.string.settings_section_nodes)) {
        if (NODES_PAGE_KEY in visible) {
            PageRow(
                title = NODES_PAGE,
                subtitle = NODES_PAGE_SUBTITLE,
                style = PageRowStyle.Inset,
                leadingIcon = Icons.Filled.Hub,
                onClick = onOpenNodes,
            )
        }
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

/** The line under a category: its lists, and while it's on, how the engine is doing. */
internal fun adblockCategorySubtitle(category: AdblockCategory, on: Boolean, status: AdblockStatus): String {
    val lists = category.listNames.joinNames()
    return if (on && status.loading) Strings.get(R.string.settings_adblock_category_loading, lists) else lists
}

/**
 * The wrapping line under an enabled category (#318): how many of its
 * lists' rules run, how many of those are scriptlets, and how many
 * can't run here — procedural and HTML filters, options and scriptlets
 * this browser doesn't support, generic scriptlets. `null` while it's
 * off or has no engine yet.
 */
internal fun adblockCategoryCounts(category: AdblockCategory, on: Boolean, status: AdblockStatus): String? {
    if (!on) return null
    val counts = status.counts[category] ?: return null
    val n = NumberFormat.getIntegerInstance()
    return if (counts.scriptlets == 0) {
        Strings.get(R.string.settings_adblock_category_counts_no_scriptlets, n.format(counts.used), n.format(counts.skipped))
    } else {
        Strings.get(
            R.string.settings_adblock_category_counts,
            n.format(counts.used), n.format(counts.scriptletsUsed), n.format(counts.skipped),
        )
    }
}

private fun adblockSectionRows(
    enabled: Set<AdblockCategory>,
    allowlist: List<String>,
    status: AdblockStatus,
    update: AdblockUpdateState,
) = buildList {
    for (category in AdblockCategory.entries) {
        add(
            settingsRow(
                category, category.title, category.listNames.joinNames(), onOff(category in enabled),
                adblockCategoryCounts(category, category in enabled, status),
            ),
        )
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
                thirdLine = adblockCategoryCounts(category, on, status),
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

private val ROW_DELETE_DATA: String get() = Strings.get(R.string.settings_delete_browsing_data)
private val ROW_DELETE_DATA_SUBTITLE: String get() = Strings.get(R.string.settings_delete_browsing_data_subtitle)

/**
 * Privacy & security → Browsing data (#400): the one row that opens
 * [DeleteBrowsingDataPage], found by what it deletes ("history",
 * "cookies", "cache", "desktop site" …) and by "clear" and "delete".
 */
internal fun browsingDataRows() = listOf(
    settingsRow(
        "delete-data",
        ROW_DELETE_DATA,
        ROW_DELETE_DATA_SUBTITLE,
        *searchKeywords(R.string.settings_delete_browsing_data_keywords),
    ),
)

@Composable
private fun BrowsingDataSection(onOpen: () -> Unit) {
    SectionCard(title = stringResource(R.string.settings_section_browsing)) {
        PageRow(
            title = ROW_DELETE_DATA,
            subtitle = ROW_DELETE_DATA_SUBTITLE,
            style = PageRowStyle.Inset,
            leadingIcon = Icons.Filled.DeleteForever,
            onClick = onOpen,
            trailing = {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
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

// Settings → Nodes & networks → IPFS (#400 item 8: no longer behind a
// "Show advanced options" switch).
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

/** A yes/no question whose yes is destructive (shown in the error colour). */
@Composable
internal fun ConfirmDialog(
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
