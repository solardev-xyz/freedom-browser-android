package baby.freedom.mobile.browser

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import baby.freedom.mobile.chains.Chain
import baby.freedom.mobile.chains.ChainInput
import baby.freedom.mobile.chains.Chainlist
import baby.freedom.mobile.chains.ChainlistService
import baby.freedom.mobile.chains.RpcUrls
import baby.freedom.mobile.chains.rpc.ChainAccessPolicy
import baby.freedom.mobile.chains.rpc.ChainDataRouter
import baby.freedom.mobile.chains.rpc.ChainRpcException
import baby.freedom.mobile.chains.rpc.ChainSource
import baby.freedom.mobile.chains.rpc.ChainTrust
import baby.freedom.mobile.chains.rpc.WalletRpc
import baby.freedom.mobile.data.ChainStore
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/*
 * Settings → Chains (#107): the chains the browser knows — Ethereum,
 * Gnosis and Base built in, plus any the user adds — each openable for
 * its details, custom ones removable. "Add chain" opens a chainlist.org
 * search ([ChainlistPage]); a pick, or "Enter manually", opens the
 * [AddChainPage] form, pre-filled from the catalog in the first case,
 * which the user reviews before adding.
 */

internal const val SECTION_CHAINS = "Chains"
private const val ROW_ADD_CHAIN = "Add chain"
private const val ADD_CHAIN_KEY = "add-chain"

/** Which Chains sub-page Settings shows in place of its list, if any. */
internal sealed interface ChainPage {
    data object Search : ChainPage

    /** The form, pre-filled from [prefill] (a chainlist pick) or empty. */
    data class Form(val prefill: Chain?) : ChainPage

    /** One chain's page ([ChainDetailPage]). */
    data class Detail(val chainId: Long) : ChainPage
}

internal fun chainSubtitle(chain: Chain) = "Chain ID ${chain.id} · ${chain.symbol}"

internal fun rpcCountLabel(count: Int) =
    if (count == 1) "1 RPC endpoint" else "$count RPC endpoints"

private fun chainThirdLine(chain: Chain) = listOfNotNull(
    rpcCountLabel(chain.rpcUrls.size),
    "Testnet".takeIf { chain.isTestnet },
    if (chain.builtIn) "Built in" else "Custom",
).joinToString(" · ")

/** Settings search index: each chain by name, ID and currency, and the Add row. */
internal fun chainSettingsRows(chains: List<Chain>) = chains.map { chain ->
    settingsRow(chain.id, chain.name, chainSubtitle(chain), chainThirdLine(chain), chain.hexId)
} + settingsRow(ADD_CHAIN_KEY, ROW_ADD_CHAIN, "chainlist.org", "Custom chain", "Network")

@Composable
internal fun ChainsSection(
    visible: Set<Any>,
    chains: List<Chain>,
    onOpen: (Chain) -> Unit,
    onRemove: (Chain) -> Unit,
    onAdd: () -> Unit,
) {
    SectionCard(title = SECTION_CHAINS) {
        for (chain in chains) {
            if (chain.id !in visible) continue
            PageRow(
                title = chain.name,
                subtitle = chainSubtitle(chain),
                style = PageRowStyle.Inset,
                leadingIcon = Icons.Filled.Hub,
                thirdLine = chainThirdLine(chain),
                onClick = { onOpen(chain) },
                trailing = if (chain.builtIn) null else {
                    {
                        IconButton(onClick = { onRemove(chain) }) {
                            Icon(
                                Icons.Filled.DeleteOutline,
                                contentDescription = "Remove ${chain.name}",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
            )
        }
        if (ADD_CHAIN_KEY in visible) {
            PageRow(
                title = ROW_ADD_CHAIN,
                subtitle = "Search chainlist.org or enter one yourself",
                style = PageRowStyle.Inset,
                leadingIcon = Icons.Filled.Add,
                onClick = onAdd,
            )
        }
    }
}

/** What "Check" found out, for the chain page's read section. */
private sealed interface ReadCheck {
    data object Idle : ReadCheck
    data object Running : ReadCheck
    data class Done(val block: Long, val trust: ChainTrust) : ReadCheck
    data class Failed(val message: String) : ReadCheck
}

/** Why [ChainStore.addUserRpc] didn't add a URL, as the chain page says it; `null` once added. */
internal fun userRpcAddError(result: ChainStore.RpcAddResult): String? = when (result) {
    ChainStore.RpcAddResult.ADDED -> null
    ChainStore.RpcAddResult.INVALID -> "Not a usable RPC URL."
    ChainStore.RpcAddResult.DUPLICATE -> "That RPC is already in your list."
    ChainStore.RpcAddResult.PUBLIC -> "That's already one of this chain's public RPCs."
    ChainStore.RpcAddResult.FULL -> "At most ${Chain.MAX_USER_RPC_URLS} of your own RPCs per chain."
    ChainStore.RpcAddResult.NO_CHAIN -> "This chain was removed."
    ChainStore.RpcAddResult.FAILED -> "Couldn't save the RPC. Try again."
}

/**
 * The steps a read on [chain] walks under [policy], in order, as the
 * chain page lists them — only the tiers [wired] in this build.
 */
internal fun readSteps(chain: Chain, policy: ChainAccessPolicy, wired: (ChainSource) -> Boolean): List<String> {
    val pool = (chain.userRpcUrls + chain.rpcUrls).distinct()
    val providers = ChainDataRouter.quorumMembers(pool).size
    val members = ChainDataRouter.quorumMembers(pool, policy.quorumK)
    val mine = members.count { it in chain.userRpcUrls }
    val whose = when {
        mine == 0 -> ""
        mine == members.size -> ", all of them yours"
        else -> ", yours among them"
    }
    return policy.readOrder.filter(wired).map { source ->
        when (source) {
            ChainSource.MYOTIS, ChainSource.COLIBRI -> "${source.label}: a proof checked on this device"
            ChainSource.QUORUM -> if (providers >= policy.quorumM) {
                "${source.label}: ${policy.quorumM} of the first ${members.size} RPCs " +
                    "(each from a different provider$whose) must give the same answer — verified"
            } else {
                "${source.label}: skipped, needs RPCs from at least ${policy.quorumM} providers " +
                    "(this chain has $providers)"
            }
            ChainSource.DIRECT -> "${source.label}: otherwise the first RPC that answers — unverified, " +
                "or marked as yours if it's one of your RPCs"
        }
    }
}

/** How a read was checked, in a line: who agreed, who didn't. Hosts only. */
internal fun trustSummary(trust: ChainTrust): String {
    val dissent = if (trust.dissented.isEmpty()) "" else
        " · ${trust.dissented.joinToString(", ")} answered differently"
    return when (trust.level) {
        ChainTrust.Level.VERIFIED -> if (trust.source == ChainSource.QUORUM) {
            "Verified: ${trust.agreed.size} of ${trust.k} RPCs agreed (${trust.agreed.joinToString(", ")})$dissent"
        } else {
            "Verified by the ${trust.source.label}"
        }
        ChainTrust.Level.USER_CONFIGURED -> "From your RPC ${trust.agreed.firstOrNull().orEmpty()}$dissent"
        ChainTrust.Level.UNVERIFIED -> "Unverified: only ${trust.agreed.joinToString(", ")}'s word$dissent"
    }
}

/**
 * What the "Your RPCs" section says about how [chain]'s own RPCs are
 * used — true of [ChainDataRouter] for the RPCs the user has now: they
 * lead the pool, so the quorum's K seats (one per provider) are handed to
 * them ahead of public RPCs, which fill the rest; every seat is asked at
 * the same time, so "ahead" is about who gets a seat, never timing. With
 * no RPCs of the user's own the note claims no seat for one. Once the
 * user's providers fill every seat, no public RPC is in the quorum; an
 * RPC sharing a provider with an earlier one (every loopback spelling is
 * one) or past the first K takes no seat and is only asked if the quorum
 * falls short.
 */
internal fun userRpcsNote(chain: Chain, policy: ChainAccessPolicy): String {
    val lead = "Your own node or provider for this chain. "
    val tail = "An answer only your RPC gave is marked as yours rather than unverified."
    val k = policy.quorumK
    val pool = (chain.userRpcUrls + chain.rpcUrls).distinct()
    val providers = ChainDataRouter.quorumMembers(pool).size
    if (ChainSource.QUORUM !in policy.readOrder || providers < policy.quorumM) {
        val order = when (chain.userRpcUrls.size) {
            0 -> "One you add is asked before the public RPCs. "
            1 -> "It's asked before the public RPCs. "
            else -> "They're asked before the public RPCs. "
        }
        return lead + order + tail
    }
    val members = ChainDataRouter.quorumMembers(pool, k)
    val seats = members.count { it in chain.userRpcUrls }
    val publicSeats = members.size - seats
    val howReadsGo = "Each read goes to up to $k RPCs from different providers at the same time. "
    val who = when {
        seats == 0 -> "Until you add one, those are all the chain's public RPCs; " +
            "one you add takes a seat ahead of them. "
        publicSeats == 0 -> "Your RPCs fill every seat, so the quorum is only yours: " +
            "a public RPC is asked only if it falls short. "
        else -> "Yours ${if (seats == 1) "takes a seat" else "take seats"} ahead of public RPCs, so ${if (seats == 1) "it's" else "they're"} in the quorum, but not alone: " +
            "public RPCs fill ${if (publicSeats == 1) "the other seat" else "the other $publicSeats seats"} and see the same read. "
    }
    val spare = chain.userRpcUrls.size - seats
    val spareNote = if (spare <= 0) "" else
        "${plural(spare, "RPC")} of yours ${if (spare == 1) "isn't" else "aren't"} in the quorum — " +
            "it takes one per provider (this device counts as one) and $k at most — " +
            "so ${if (spare == 1) "it's" else "they're"} asked only if it falls short. "
    return lead + howReadsGo + who + spareNote + tail
}

private fun plural(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"

/**
 * A chain's page (#107, #108): what it is, the user's own RPCs ("Your
 * RPCs", added and removed here, given quorum seats ahead of public
 * ones), its public RPCs, and how a
 * read is checked — with a Check button that reads the latest block
 * through [ChainDataRouter] and says how that answer was verified. Every
 * URL is shown in full (wrapped, never cut). A custom chain also offers
 * Remove.
 */
@Composable
internal fun ChainDetailPage(
    chain: Chain,
    onAddRpc: suspend (String) -> ChainStore.RpcAddResult,
    /** Removes an RPC of yours; the reason it didn't, or `null` once it did. */
    onRemoveRpc: suspend (String) -> String?,
    onRemove: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val router = remember(context) { ChainDataRouter.get(context) }
    val scope = rememberCoroutineScope()
    var newRpc by rememberSaveable(chain.id) { mutableStateOf("") }
    var rpcError by remember(chain.id) { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    // Keyed to the RPC lists too: a result naming an RPC the user has
    // since removed (or read before they added one) no longer describes
    // this chain, and a check still running writes only to the old state.
    var check by remember(chain.id, chain.userRpcUrls, chain.rpcUrls) { mutableStateOf<ReadCheck>(ReadCheck.Idle) }
    val newRpcCheck = RpcUrls.validate(newRpc)

    fun addRpc() {
        if (saving || newRpcCheck.url == null) return
        saving = true
        scope.launch {
            val error = userRpcAddError(onAddRpc(newRpc))
            saving = false
            rpcError = error
            if (error == null) newRpc = ""
        }
    }

    FullScreenScaffold(
        title = chain.name,
        onDismiss = onBack,
        trailing = {
            if (!chain.builtIn) {
                TextButton(onClick = onRemove) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            }
        },
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            SectionCard(title = "Chain") {
                DetailLine("Chain ID", "${chain.id} (${chain.hexId})")
                DetailLine("Currency", "${chain.currencyName} (${chain.symbol}), ${chain.decimals} decimals")
                DetailLine("Block explorer", chain.explorerUrl ?: "None")
                DetailLine("Type", listOfNotNull(
                    if (chain.builtIn) "Built in" else "Custom",
                    "Testnet".takeIf { chain.isTestnet },
                ).joinToString(", "))
            }
            SectionCard(title = "Your RPCs") {
                Text(
                    userRpcsNote(chain, router.policy(chain)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                for (url in chain.userRpcUrls) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            url,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = {
                            scope.launch {
                                rpcError = onRemoveRpc(url)
                            }
                        }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Remove $url",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (chain.userRpcUrls.size < Chain.MAX_USER_RPC_URLS) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.weight(1f)) {
                            FormField(
                                value = newRpc,
                                onValueChange = { newRpc = it; rpcError = null },
                                label = "RPC URL",
                                placeholder = "https://rpc.example.org",
                                hint = rpcError ?: newRpcCheck.rejection
                                    ?.takeIf { newRpc.isNotBlank() }
                                    ?.let(::rpcUrlHint),
                                url = true,
                                imeAction = ImeAction.Done,
                                onImeAction = { addRpc() },
                            )
                        }
                        IconButton(onClick = { addRpc() }, enabled = newRpcCheck.url != null && !saving) {
                            Icon(Icons.Filled.Add, contentDescription = "Add your RPC")
                        }
                    }
                } else rpcError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
            SectionCard(title = "Public RPCs") {
                Text(
                    rpcCountLabel(chain.publicRpcUrls.size),
                    style = MaterialTheme.typography.labelLarge,
                )
                for (url in chain.publicRpcUrls) {
                    Text(
                        url,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            SectionCard(title = "How reads are checked") {
                val steps = readSteps(chain, router.policy(chain)) { router.isWired(it, chain.id) }
                steps.forEachIndexed { i, step ->
                    Text("${i + 1}. $step", style = MaterialTheme.typography.bodyMedium)
                }
                if (chain.id in ChainAccessPolicy.LIGHT_CLIENT_CHAIN_IDS &&
                    !router.isWired(ChainSource.MYOTIS, chain.id)
                ) {
                    Text(
                        "The P2P light client and the Colibri prover, which verify ${chain.name} " +
                            "reads with a proof, aren't in this version yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        enabled = check != ReadCheck.Running,
                        onClick = {
                            check = ReadCheck.Running
                            scope.launch {
                                check = try {
                                    val r = WalletRpc(router).blockNumber(chain.id)
                                    ReadCheck.Done(r.value, r.trust)
                                } catch (e: ChainRpcException) {
                                    ReadCheck.Failed(e.message ?: "No answer")
                                }
                            }
                        },
                    ) { Text("Check latest block") }
                    if (check == ReadCheck.Running) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                }
                when (val c = check) {
                    is ReadCheck.Done -> {
                        Text(
                            "Block %,d".format(c.block),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            trustSummary(c.trust),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (c.trust.level == ChainTrust.Level.UNVERIFIED) {
                                MaterialTheme.colorScheme.error
                            } else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is ReadCheck.Failed -> Text(
                        c.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    else -> Unit
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private sealed interface CatalogState {
    data object Loading : CatalogState
    data class Loaded(val entries: List<Chainlist.Entry>) : CatalogState
    data object Failed : CatalogState
}

/**
 * Search the chainlist.org catalog. Opens on the highest-TVL chains;
 * the field filters by name, short name or chain ID. A chain the
 * browser already has is listed but can't be picked again. "Enter
 * manually" opens an empty form for chains the catalog lacks.
 */
@Composable
internal fun ChainlistPage(
    query: String,
    onQueryChange: (String) -> Unit,
    existingIds: Set<Long>,
    onPick: (Chainlist.Entry) -> Unit,
    onManual: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val service = remember(context) { ChainlistService.get(context) }
    var attempt by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<CatalogState>(CatalogState.Loading) }
    LaunchedEffect(attempt) {
        state = CatalogState.Loading
        state = try {
            CatalogState.Loaded(service.entries())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Not only IOException: the download re-raises whatever its
            // worker thread hit, and that must show Retry, not crash.
            if (e !is IOException) Log.w("ChainsSettings", "chainlist load failed", e)
            CatalogState.Failed
        }
    }
    val results = (state as? CatalogState.Loaded)?.let { loaded ->
        remember(loaded.entries, query) { Chainlist.search(loaded.entries, query) }
    }
    val focusManager = LocalFocusManager.current

    FullScreenScaffold(title = ROW_ADD_CHAIN, onDismiss = onBack) {
        Column(modifier = Modifier.fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text("Name or chain ID") },
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
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item("manual") {
                    PageRow(
                        title = "Enter manually",
                        subtitle = "Chain ID, currency and RPC endpoints",
                        leadingIcon = Icons.Filled.Edit,
                        onClick = onManual,
                    )
                }
                item("note") {
                    Text(
                        "From chainlist.org, refreshed daily. Only RPC endpoints that need " +
                            "no API key and aren't marked as tracking are kept.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    )
                }
                when (state) {
                    CatalogState.Loading -> item("loading") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 16.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(12.dp))
                            Text("Loading chainlist.org…", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    CatalogState.Failed -> item("failed") {
                        Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 16.dp)) {
                            Text(
                                "Couldn't load chainlist.org. Check your connection and try again, " +
                                    "or enter the chain manually.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            TextButton(onClick = { attempt++ }) { Text("Retry") }
                        }
                    }
                    is CatalogState.Loaded -> {
                        if (results.isNullOrEmpty()) item("none") {
                            Text(
                                "No chains match “${query.trim()}”",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 16.dp),
                            )
                        } else items(results, key = { it.id }) { entry ->
                            val added = entry.id in existingIds
                            PageRow(
                                title = entry.name,
                                subtitle = "Chain ID ${entry.id} · ${entry.symbol}",
                                thirdLine = catalogThirdLine(entry, added),
                                enabled = !added,
                                onClick = { onPick(entry) },
                            )
                        }
                    }
                }
            }
        }
    }
}

internal fun catalogThirdLine(entry: Chainlist.Entry, added: Boolean) = listOfNotNull(
    if (added) "Already added" else null,
    when (val n = entry.rpcUrls.size) {
        0 -> "No key-free RPC endpoint — you'd add your own"
        1 -> "1 key-free RPC endpoint"
        else -> "$n key-free RPC endpoints"
    },
    "Testnet".takeIf { entry.isTestnet },
).joinToString(" · ")

/**
 * The form's RPC list with the URL still typed in the RPC field
 * ([pending]) appended, as Add saves it: unchanged if the field is blank,
 * the URL is already listed or the list is full (the field is hidden
 * then); `null` if the field holds something that isn't a valid RPC URL,
 * so Add can't quietly drop it.
 */
internal fun rpcsWithPending(committed: List<String>, pending: String): List<String>? {
    if (pending.isBlank() || committed.size >= Chain.MAX_RPC_URLS) return committed
    val url = RpcUrls.normalize(pending) ?: return null
    return if (url in committed) committed else committed + url
}

/** What to fix, for each reason [RpcUrls.validate] refuses a URL. */
internal fun rpcUrlHint(rejection: RpcUrls.Rejection): String = when (rejection) {
    RpcUrls.Rejection.EMPTY, RpcUrls.Rejection.NOT_A_URL -> "Not a URL: e.g. https://rpc.example.org"
    RpcUrls.Rejection.TOO_LONG -> "Too long: at most 2048 characters"
    RpcUrls.Rejection.NON_ASCII -> "Use the xn-- form of an international domain name"
    RpcUrls.Rejection.SCHEME -> "Needs https:// (http:// only to localhost, 127.0.0.1 or [::1])"
    RpcUrls.Rejection.CREDENTIALS -> "Remove the user name or password before the host"
    RpcUrls.Rejection.PLACEHOLDER -> "Replace the {API_KEY} placeholder: only key-free RPCs are supported"
    RpcUrls.Rejection.INTERNAL_HOST -> "Needs a public host name, not a local network address"
}

/**
 * The Add chain form: chain ID, name, currency, explorer and RPC
 * endpoints, pre-filled from a chainlist pick or empty. Add stays
 * disabled until [ChainInput.build] accepts every field; each field
 * says what's wrong with it. [onAdd] answers with the store's result
 * so a chain ID that's built in or already added is reported here.
 */
@Composable
internal fun AddChainPage(
    prefill: Chain?,
    onAdd: suspend (Chain) -> ChainStore.AddResult,
    onAdded: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var id by rememberSaveable(prefill) { mutableStateOf(prefill?.id?.toString().orEmpty()) }
    var name by rememberSaveable(prefill) { mutableStateOf(prefill?.name.orEmpty()) }
    var symbol by rememberSaveable(prefill) { mutableStateOf(prefill?.symbol.orEmpty()) }
    var decimals by rememberSaveable(prefill) { mutableStateOf((prefill?.decimals ?: 18).toString()) }
    var explorer by rememberSaveable(prefill) { mutableStateOf(prefill?.explorerUrl.orEmpty()) }
    // One URL per line: a saveable String, where a List might not be.
    var rpcLines by rememberSaveable(prefill) {
        mutableStateOf(prefill?.rpcUrls.orEmpty().joinToString("\n"))
    }
    var newRpc by rememberSaveable(prefill) { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // The Add / Done / + handlers read the fields through these, at the
    // moment they run — never a value computed during composition:
    // Compose keeps a callback whose captures compare equal (a local
    // function reference does), so a computed value captured in it can
    // be several keystrokes old by the time it's tapped.
    fun currentRpcs() = rpcLines.split('\n').filter { it.isNotEmpty() }
    fun setRpcs(list: List<String>) {
        rpcLines = list.joinToString("\n")
    }
    // A URL still sitting in the RPC field counts: tapping Add without
    // first tapping + / Done must not silently drop it (and one that
    // isn't valid keeps Add disabled, its hint showing why).
    fun currentChain(): Chain? = ChainInput.build(
        id = id, name = name, symbol = symbol, decimals = decimals, explorer = explorer,
        rpcUrls = rpcsWithPending(currentRpcs(), newRpc) ?: return null,
        // The catalog's own currency name, while the symbol is still the catalog's.
        currencyName = prefill?.currencyName?.takeIf { symbol.trim() == prefill.symbol },
        isTestnet = prefill?.isTestnet ?: false,
    )
    fun addRpc() {
        val url = RpcUrls.normalize(newRpc) ?: return
        val list = currentRpcs()
        if (list.size < Chain.MAX_RPC_URLS && url !in list) setRpcs(list + url)
        newRpc = ""
    }
    fun submit() {
        val c = currentChain() ?: return
        if (saving) return
        saving = true
        scope.launch {
            val result = onAdd(c)
            saving = false
            when (result) {
                ChainStore.AddResult.ADDED -> onAdded()
                ChainStore.AddResult.BUILT_IN -> error = "Chain ID ${c.id} is built in already."
                ChainStore.AddResult.DUPLICATE ->
                    error = "A chain with ID ${c.id} is already added. Remove it first to replace it."
                ChainStore.AddResult.FAILED -> error = "Couldn't save the chain. Try again."
            }
        }
    }

    val rpcs = currentRpcs()
    val chain = currentChain()
    val newRpcCheck = RpcUrls.validate(newRpc)

    FullScreenScaffold(
        title = ROW_ADD_CHAIN,
        onDismiss = onBack,
        trailing = {
            TextButton(onClick = { submit() }, enabled = chain != null && !saving) { Text("Add") }
        },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Pinned under the title rather than at the end of the form, so
            // it's seen with the keyboard up and the form scrolled anywhere.
            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                SectionCard(title = "Chain") {
                    FormField(
                        value = id, onValueChange = { id = it; error = null }, label = "Chain ID",
                        placeholder = "137",
                        hint = if (id.isNotBlank() && ChainInput.parseId(id) == null) {
                            "A whole number from 1 to ${Chain.MAX_ID} (or 0x hex)"
                        } else null,
                        keyboardType = KeyboardType.Ascii,
                        literal = true,
                    )
                    FormField(
                        value = name, onValueChange = { name = it }, label = "Name",
                        placeholder = "Polygon",
                        hint = if (name.isNotEmpty() && ChainInput.parseName(name) == null) {
                            "1 to ${Chain.MAX_NAME_LENGTH} characters"
                        } else null,
                    )
                }
                SectionCard(title = "Currency") {
                    FormField(
                        value = symbol, onValueChange = { symbol = it }, label = "Symbol",
                        placeholder = "POL",
                        hint = if (symbol.isNotEmpty() && ChainInput.parseSymbol(symbol) == null) {
                            "1 to ${Chain.MAX_SYMBOL_LENGTH} characters, no spaces"
                        } else null,
                        literal = true,
                    )
                    FormField(
                        value = decimals, onValueChange = { decimals = it }, label = "Decimals",
                        placeholder = "18",
                        hint = if (ChainInput.parseDecimals(decimals) == null) "0 to 36" else null,
                        keyboardType = KeyboardType.Number,
                        literal = true,
                    )
                }
                SectionCard(title = "Block explorer") {
                    FormField(
                        value = explorer, onValueChange = { explorer = it }, label = "URL (optional)",
                        placeholder = "https://polygonscan.com",
                        hint = if (explorer.isNotBlank() && ChainInput.normalizeExplorer(explorer) == null) {
                            "Needs https:// and a host name"
                        } else null,
                        url = true,
                    )
                }
                SectionCard(title = "RPC endpoints") {
                    if (rpcs.isEmpty()) {
                        Text(
                            "Add at least one.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    for (url in rpcs) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                url,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { setRpcs(rpcs - url) }) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "Remove $url",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (rpcs.size < Chain.MAX_RPC_URLS) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.weight(1f)) {
                                FormField(
                                    value = newRpc, onValueChange = { newRpc = it }, label = "RPC URL",
                                    placeholder = "https://rpc.example.org",
                                    hint = newRpcCheck.rejection
                                        ?.takeIf { newRpc.isNotBlank() }
                                        ?.let(::rpcUrlHint),
                                    url = true,
                                    imeAction = ImeAction.Done,
                                    onImeAction = { addRpc() },
                                )
                            }
                            IconButton(onClick = { addRpc() }, enabled = newRpcCheck.url != null) {
                                Icon(Icons.Filled.Add, contentDescription = "Add RPC endpoint")
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun FormField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    hint: String?,
    keyboardType: KeyboardType = KeyboardType.Text,
    url: Boolean = false,
    /** Codes rather than words (IDs, tickers): no suggestions or auto-correct. */
    literal: Boolean = url,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: (() -> Unit)? = null,
) {
    val field = @Composable {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            placeholder = { Text(placeholder) },
            isError = hint != null,
            supportingText = hint?.let { { Text(it) } },
            singleLine = true,
            keyboardOptions = if (url) {
                urlKeyboardOptions(imeAction)
            } else {
                KeyboardOptions(
                    capitalization = if (literal) KeyboardCapitalization.None else KeyboardCapitalization.Words,
                    autoCorrectEnabled = !literal,
                    keyboardType = keyboardType,
                    imeAction = imeAction,
                )
            },
            keyboardActions = if (onImeAction != null) {
                KeyboardActions(onDone = { onImeAction() })
            } else KeyboardActions.Default,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (literal) NoSuggestionsTextInput(field) else field()
}
