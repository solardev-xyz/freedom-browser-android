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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import baby.freedom.mobile.R
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
import baby.freedom.mobile.ens.EnsRpcConfig
import baby.freedom.mobile.l10n.Strings
import baby.freedom.mobile.l10n.pluralText
import java.io.IOException
import java.text.NumberFormat
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

internal val SECTION_CHAINS: String get() = Strings.get(R.string.names_section_chains)
private val ROW_ADD_CHAIN: String get() = Strings.get(R.string.names_add_chain)
private const val ADD_CHAIN_KEY = "add-chain"

/** Which Chains sub-page Settings shows in place of its list, if any. */
internal sealed interface ChainPage {
    data object Search : ChainPage

    /** The form, pre-filled from [prefill] (a chainlist pick) or empty. */
    data class Form(val prefill: Chain?) : ChainPage

    /** One chain's page ([ChainDetailPage]). */
    data class Detail(val chainId: Long) : ChainPage
}

/** A chain's currency as its list row says it: "Ether (ETH)", or just the symbol when that's its name too. */
internal fun chainCurrency(chain: Chain): String =
    if (chain.currencyName == chain.symbol) chain.symbol
    else Strings.get(R.string.names_currency_short, chain.currencyName, chain.symbol)

internal fun rpcCountLabel(count: Int) = Strings.plural(R.plurals.names_rpc_endpoints, count, count)

private fun builtInLabel(chain: Chain) =
    Strings.get(if (chain.builtIn) R.string.names_chain_built_in else R.string.names_chain_custom)

/**
 * How [ChainDataRouter] can check a read on a chain, from the first tier
 * of its read order that can answer: the one fact both the list's status
 * and the chain page's reassurance line are worded from ([readAssurance]).
 */
internal sealed interface ReadAssurance {
    /** A proof checked on this device ([source]: the light client or the prover). */
    data class Proof(val source: ChainSource) : ReadAssurance

    /**
     * The RPC quorum: [providers] RPCs, each from a different provider, are
     * asked and [needed] must agree; [yours] of those seats are the user's own.
     */
    data class CrossChecked(val providers: Int, val needed: Int, val yours: Int) : ReadAssurance

    /** No quorum can form: the first RPC that answers, [yours] if the user has one of their own. */
    data class Single(val yours: Boolean) : ReadAssurance
}

/**
 * [ReadAssurance] for [chain] under [policy], counting only the tiers
 * [wired] in this build — the same seat assignment the router uses
 * ([ChainDataRouter.quorumMembers]), so what the page claims is what a
 * read does.
 */
internal fun readAssurance(chain: Chain, policy: ChainAccessPolicy, wired: (ChainSource) -> Boolean): ReadAssurance {
    val pool = (chain.userRpcUrls + chain.rpcUrls).distinct()
    for (source in policy.readOrder.filter(wired)) {
        when (source) {
            ChainSource.MYOTIS, ChainSource.COLIBRI -> return ReadAssurance.Proof(source)
            ChainSource.QUORUM -> if (ChainDataRouter.quorumMembers(pool).size >= policy.quorumM) {
                val members = ChainDataRouter.quorumMembers(pool, policy.quorumK)
                return ReadAssurance.CrossChecked(
                    providers = members.size,
                    needed = policy.quorumM,
                    yours = members.count { it in chain.userRpcUrls },
                )
            }
            ChainSource.DIRECT -> break
        }
    }
    return ReadAssurance.Single(yours = chain.userRpcUrls.isNotEmpty())
}

/** [readAssurance] as [router] reads [chain] right now. */
internal fun readAssurance(chain: Chain, router: ChainDataRouter): ReadAssurance =
    readAssurance(chain, router.policy(chain)) { router.isWired(it, chain.id) }

/** The list row's status: "Verified reads", "Custom · Not cross-checked", "Verified reads · Testnet", … */
internal fun chainStatus(chain: Chain, assurance: ReadAssurance): String = listOfNotNull(
    Strings.get(R.string.names_chain_custom).takeIf { !chain.builtIn },
    Strings.get(
        when (assurance) {
            is ReadAssurance.Proof, is ReadAssurance.CrossChecked -> R.string.names_chain_status_verified
            is ReadAssurance.Single ->
                if (assurance.yours) R.string.names_chain_status_yours else R.string.names_chain_status_unchecked
        },
    ),
    Strings.get(R.string.names_chain_testnet).takeIf { chain.isTestnet },
).joinToString(" · ")

/** The chain page's opening line: how much a read on this chain can be trusted, in plain words. */
internal fun readAssuranceLine(assurance: ReadAssurance): String = when (assurance) {
    is ReadAssurance.Proof -> Strings.get(R.string.names_assurance_proof, assurance.source.label)
    is ReadAssurance.CrossChecked -> if (assurance.yours == assurance.providers) {
        Strings.plural(R.plurals.names_assurance_cross_checked_yours, assurance.providers, assurance.providers)
    } else {
        Strings.plural(R.plurals.names_assurance_cross_checked, assurance.providers, assurance.providers)
    }
    is ReadAssurance.Single -> Strings.get(
        if (assurance.yours) R.string.names_assurance_single_yours else R.string.names_assurance_single,
    )
}

/**
 * Settings search index: each chain by name, currency and status, and by
 * its ID (decimal and hex) though the row no longer shows it; and the Add row.
 */
internal fun chainSettingsRows(chains: List<Chain>, assurance: (Chain) -> ReadAssurance) = chains.map { chain ->
    settingsRow(chain.id, chain.name, chainCurrency(chain), chainStatus(chain, assurance(chain)), chain.id.toString(), chain.hexId)
} + settingsRow(
    ADD_CHAIN_KEY,
    ROW_ADD_CHAIN,
    "chainlist.org",
    Strings.get(R.string.names_search_custom_chain),
    Strings.get(R.string.names_search_network),
)

@Composable
internal fun ChainsSection(
    visible: Set<Any>,
    chains: List<Chain>,
    onOpen: (Chain) -> Unit,
    onRemove: (Chain) -> Unit,
    onAdd: () -> Unit,
) {
    val context = LocalContext.current
    val router = remember(context) { ChainDataRouter.get(context) }
    SectionCard(title = SECTION_CHAINS) {
        for (chain in chains) {
            if (chain.id !in visible) continue
            PageRow(
                title = chain.name,
                subtitle = chainCurrency(chain),
                style = PageRowStyle.Inset,
                leadingIcon = Icons.Filled.Hub,
                thirdLine = chainStatus(chain, readAssurance(chain, router)),
                onClick = { onOpen(chain) },
                trailing = if (chain.builtIn) null else {
                    {
                        IconButton(onClick = { onRemove(chain) }) {
                            Icon(
                                Icons.Filled.DeleteOutline,
                                contentDescription = stringResource(R.string.names_remove_item, chain.name),
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
                subtitle = stringResource(R.string.names_add_chain_subtitle),
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
    data class Failed(val error: ChainError) : ReadCheck
}

/** Why [ChainStore.addUserRpc] didn't add a URL, as the chain page says it; `null` once added. */
internal fun userRpcAddError(result: ChainStore.RpcAddResult, url: String = ""): String? = when (result) {
    ChainStore.RpcAddResult.ADDED -> null
    ChainStore.RpcAddResult.INVALID -> Strings.get(R.string.names_rpc_add_invalid)
    ChainStore.RpcAddResult.DUPLICATE -> Strings.get(R.string.names_rpc_add_duplicate)
    ChainStore.RpcAddResult.PUBLIC -> Strings.get(R.string.names_rpc_add_public)
    ChainStore.RpcAddResult.NAME_RESOLUTION_PUBLIC ->
        (RpcUrls.normalize(url) ?: url).let(EnsRpcConfig::publicEndpointHost)
            .let { host ->
                if (host != null) {
                    Strings.get(R.string.names_rpc_add_name_resolution_public_host, host)
                } else {
                    Strings.get(R.string.names_rpc_add_name_resolution_public)
                }
            }
    ChainStore.RpcAddResult.FULL -> Strings.get(R.string.names_rpc_add_full, Chain.MAX_USER_RPC_URLS)
    ChainStore.RpcAddResult.NO_CHAIN -> Strings.get(R.string.names_rpc_add_no_chain)
    ChainStore.RpcAddResult.FAILED -> Strings.get(R.string.names_rpc_add_failed)
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
    val quorumStep = when {
        mine == 0 -> R.plurals.names_read_step_quorum
        mine == members.size -> R.plurals.names_read_step_quorum_all_yours
        else -> R.plurals.names_read_step_quorum_yours_among
    }
    return policy.readOrder.filter(wired).map { source ->
        when (source) {
            ChainSource.MYOTIS, ChainSource.COLIBRI -> Strings.get(R.string.names_read_step_proof, source.label)
            ChainSource.QUORUM -> if (providers >= policy.quorumM) {
                Strings.plural(quorumStep, members.size, source.label, policy.quorumM, members.size)
            } else {
                Strings.plural(
                    R.plurals.names_read_step_quorum_skipped,
                    policy.quorumM,
                    source.label,
                    policy.quorumM,
                    providers,
                )
            }
            ChainSource.DIRECT -> Strings.get(R.string.names_read_step_direct, source.label)
        }
    }
}

/** How a read was checked, in a line: who agreed, who didn't. Hosts only. */
internal fun trustSummary(trust: ChainTrust): String {
    val dissent = if (trust.dissented.isEmpty()) "" else
        " · " + Strings.get(R.string.names_trust_dissent, trust.dissented.joinToString(", "))
    return when (trust.level) {
        ChainTrust.Level.VERIFIED -> if (trust.source == ChainSource.QUORUM) {
            Strings.plural(
                R.plurals.names_trust_verified_quorum,
                trust.k,
                trust.agreed.size,
                trust.k,
                trust.agreed.joinToString(", "),
            ) + dissent
        } else {
            Strings.get(R.string.names_trust_verified_by, trust.source.label)
        }
        ChainTrust.Level.USER_CONFIGURED ->
            Strings.get(R.string.names_trust_your_rpc, trust.agreed.firstOrNull().orEmpty()) + dissent
        ChainTrust.Level.UNVERIFIED ->
            Strings.get(R.string.names_trust_unverified, trust.agreed.joinToString(", ")) + dissent
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
internal fun userRpcsNote(chain: Chain, policy: ChainAccessPolicy, withLead: Boolean = true): String {
    val lead = Strings.get(R.string.names_user_rpcs_note_lead).takeIf { withLead }
    val tail = Strings.get(R.string.names_user_rpcs_note_tail)
    val k = policy.quorumK
    val pool = (chain.userRpcUrls + chain.rpcUrls).distinct()
    val providers = ChainDataRouter.quorumMembers(pool).size
    if (ChainSource.QUORUM !in policy.readOrder || providers < policy.quorumM) {
        val order = Strings.get(
            when (chain.userRpcUrls.size) {
                0 -> R.string.names_user_rpcs_note_order_none
                1 -> R.string.names_user_rpcs_note_order_one
                else -> R.string.names_user_rpcs_note_order_many
            },
        )
        return listOfNotNull(lead, order, tail).joinToString(" ")
    }
    val members = ChainDataRouter.quorumMembers(pool, k)
    val seats = members.count { it in chain.userRpcUrls }
    val publicSeats = members.size - seats
    val howReadsGo = Strings.plural(R.plurals.names_user_rpcs_note_reads, k, k)
    val who = when {
        seats == 0 -> Strings.get(R.string.names_user_rpcs_note_no_seats)
        publicSeats == 0 -> Strings.get(R.string.names_user_rpcs_note_all_seats)
        seats == 1 -> Strings.plural(R.plurals.names_user_rpcs_note_one_seat, publicSeats, publicSeats)
        else -> Strings.plural(R.plurals.names_user_rpcs_note_seats, publicSeats, publicSeats)
    }
    val spare = chain.userRpcUrls.size - seats
    val spareNote = if (spare <= 0) null else Strings.plural(R.plurals.names_user_rpcs_note_spare, spare, spare, k)
    return listOfNotNull(lead, howReadsGo, who, spareNote, tail).joinToString(" ")
}

/**
 * A chain's page (#107, #108; laid out by #426): it opens with how far
 * its reads can be trusted ([readAssuranceLine]) and "Use my own RPC",
 * then the currency and explorer. The user's own RPCs ("Your RPCs",
 * added and removed here, given quorum seats ahead of public ones) show
 * once there are any or the button was tapped. Everything technical —
 * the chain ID, the public RPCs, how a read is checked and a Check
 * button that reads the latest block through [ChainDataRouter] and says
 * how that answer was verified — waits in a collapsed Advanced section.
 * Every URL is shown in full (wrapped, never cut). A custom chain also
 * offers Remove.
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
    // "Use my own RPC" tapped: the Your RPCs card shows (and takes focus)
    // before there's an RPC in it.
    var addingRpc by rememberSaveable(chain.id) { mutableStateOf(false) }
    val rpcField = remember { FocusRequester() }
    // Keyed to the RPC lists too: a result naming an RPC the user has
    // since removed (or read before they added one) no longer describes
    // this chain, and a check still running writes only to the old state.
    var check by remember(chain.id, chain.userRpcUrls, chain.rpcUrls) { mutableStateOf<ReadCheck>(ReadCheck.Idle) }
    val newRpcCheck = RpcUrls.validate(newRpc)
    val policy = router.policy(chain)
    val assurance = readAssurance(chain, policy) { router.isWired(it, chain.id) }
    val showYourRpcs = chain.userRpcUrls.isNotEmpty() || addingRpc || rpcError != null || newRpc.isNotBlank()

    fun addRpc() {
        if (saving || newRpcCheck.url == null) return
        saving = true
        scope.launch {
            val error = userRpcAddError(onAddRpc(newRpc), newRpc)
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
                    Text(stringResource(R.string.common_remove), color = MaterialTheme.colorScheme.error)
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
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
            ) {
                val verified = assurance !is ReadAssurance.Single || assurance.yours
                Icon(
                    if (verified) Icons.Filled.VerifiedUser else Icons.Outlined.Info,
                    contentDescription = null,
                    tint = if (verified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp).size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(readAssuranceLine(assurance), style = MaterialTheme.typography.bodyLarge)
            }
            if (!showYourRpcs) {
                FilledTonalButton(onClick = { addingRpc = true }) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.names_use_my_own_rpc))
                }
            }
            if (showYourRpcs) {
                // Tapping "Use my own RPC" puts the cursor in the field.
                LaunchedEffect(addingRpc) {
                    if (addingRpc && chain.userRpcUrls.isEmpty()) {
                        runCatching { rpcField.requestFocus() }
                    }
                }
                SectionCard(title = stringResource(R.string.names_your_rpcs)) {
                    Text(
                        stringResource(R.string.names_user_rpcs_note_lead),
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
                                    contentDescription = stringResource(R.string.names_remove_item, url),
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
                                    label = stringResource(R.string.names_rpc_url),
                                    placeholder = "https://rpc.example.org",
                                    hint = rpcError ?: newRpcCheck.rejection
                                        ?.takeIf { newRpc.isNotBlank() }
                                        ?.let(::rpcUrlHint),
                                    url = true,
                                    imeAction = ImeAction.Done,
                                    onImeAction = { addRpc() },
                                    modifier = Modifier.focusRequester(rpcField),
                                )
                            }
                            IconButton(onClick = { addRpc() }, enabled = newRpcCheck.url != null && !saving) {
                                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.names_add_your_rpc))
                            }
                        }
                    } else rpcError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            SectionCard(title = stringResource(R.string.names_chain_section)) {
                DetailLine(
                    stringResource(R.string.names_currency),
                    pluralText(
                        R.plurals.names_currency_detail,
                        chain.decimals,
                        chain.currencyName,
                        chain.symbol,
                        chain.decimals,
                    ),
                )
                DetailLine(
                    stringResource(R.string.names_block_explorer),
                    chain.explorerUrl ?: stringResource(R.string.names_block_explorer_none),
                )
                DetailLine(stringResource(R.string.names_chain_type), listOfNotNull(
                    builtInLabel(chain),
                    stringResource(R.string.names_chain_testnet).takeIf { chain.isTestnet },
                ).joinToString(", "))
            }
            DetailsExpander(title = stringResource(R.string.names_advanced)) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionCard(title = stringResource(R.string.names_chain_id)) {
                        SelectionContainer {
                            Text("${chain.id} (${chain.hexId})", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    SectionCard(title = stringResource(R.string.names_public_rpcs)) {
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
                    SectionCard(title = stringResource(R.string.names_how_reads_are_checked)) {
                        val steps = readSteps(chain, policy) { router.isWired(it, chain.id) }
                        steps.forEachIndexed { i, step ->
                            Text(
                                stringResource(R.string.names_read_step_numbered, i + 1, step),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        if (chain.id in ChainAccessPolicy.LIGHT_CLIENT_CHAIN_IDS &&
                            !router.isWired(ChainSource.MYOTIS, chain.id)
                        ) {
                            Text(
                                stringResource(R.string.names_proof_tiers_missing, chain.name),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            userRpcsNote(chain, policy, withLead = false),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
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
                                            ReadCheck.Failed(readCheckFailure(e))
                                        }
                                    }
                                },
                            ) { Text(stringResource(R.string.names_check_latest_block)) }
                            if (check == ReadCheck.Running) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            }
                        }
                        when (val c = check) {
                            is ReadCheck.Done -> {
                                Text(
                                    stringResource(
                                        R.string.names_check_block,
                                        NumberFormat.getIntegerInstance().format(c.block),
                                    ),
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
                            is ReadCheck.Failed -> ChainErrorText(c.error)
                            else -> Unit
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * An error as the chain pages show it (#426): [message] leads with what
 * to do, in plain words; [detail] is the raw diagnosis (an RPC's own error
 * code and text, which source failed how), behind "Show details".
 */
internal data class ChainError(val message: String, val detail: String? = null)

/**
 * What Check says when the read failed: what to try first, and the
 * router's own diagnosis as the detail — its frame in the user's
 * language, the per-source details as the router wrote them.
 */
internal fun readCheckFailure(e: ChainRpcException): ChainError = when (e) {
    is ChainRpcException.UnknownChain -> ChainError(
        Strings.get(R.string.names_check_failed_unknown_chain),
        readCheckDetail(e),
    )
    is ChainRpcException.Rpc -> ChainError(Strings.get(R.string.names_check_failed_refused), readCheckDetail(e))
    is ChainRpcException.AllSourcesFailed -> ChainError(
        Strings.get(
            if (e.nodeError != null) R.string.names_check_failed_refused else R.string.names_check_failed_no_answer,
        ),
        readCheckDetail(e),
    )
    is ChainRpcException.InvalidResponse -> ChainError(Strings.get(R.string.names_check_failed_odd_answer), readCheckDetail(e))
    else -> ChainError(Strings.get(R.string.names_check_failed_other), readCheckDetail(e))
}

/** The raw side of [readCheckFailure]: `null` when the exception has nothing to say. */
private fun readCheckDetail(e: ChainRpcException): String? = when (e) {
    is ChainRpcException.UnknownChain -> Strings.get(R.string.names_check_unknown_chain, e.chainId.toString())
    is ChainRpcException.Rpc -> Strings.get(R.string.names_check_rpc_error, e.code, e.rpcMessage)
    is ChainRpcException.AllSourcesFailed -> e.nodeError?.let { node ->
        Strings.get(
            R.string.names_check_no_source_node_error,
            e.failures.joinToString("; "),
            readCheckDetail(node) ?: node.message.orEmpty(),
        )
    } ?: Strings.get(R.string.names_check_no_source, e.failures.joinToString("; "))
    else -> e.message?.takeIf { it.isNotBlank() }
}

/**
 * [error]'s message in the error colour, then — when it has a raw
 * detail — a "Show details" toggle that reveals it. Every value stays in
 * the text (wrapped, never cut).
 */
@Composable
internal fun ChainErrorText(error: ChainError, modifier: Modifier = Modifier) {
    var open by rememberSaveable(error) { mutableStateOf(false) }
    Column(modifier) {
        Text(error.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        val detail = error.detail ?: return@Column
        TextButton(
            onClick = { open = !open },
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp),
        ) {
            Text(stringResource(if (open) R.string.names_hide_details else R.string.names_show_details))
        }
        if (open) {
            SelectionContainer {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
    /** [detail]: what the download hit, behind "Show details". */
    data class Failed(val detail: String?) : CatalogState
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
            CatalogState.Failed(e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName)
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
                placeholder = { Text(stringResource(R.string.names_chainlist_search_placeholder)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.names_clear_search))
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
                        title = stringResource(R.string.names_enter_manually),
                        subtitle = stringResource(R.string.names_enter_manually_subtitle),
                        leadingIcon = Icons.Filled.Edit,
                        onClick = onManual,
                    )
                }
                item("note") {
                    Text(
                        stringResource(R.string.names_chainlist_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    )
                }
                when (val st = state) {
                    CatalogState.Loading -> item("loading") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 16.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(12.dp))
                            Text(stringResource(R.string.names_chainlist_loading), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    is CatalogState.Failed -> item("failed") {
                        Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 16.dp)) {
                            ChainErrorText(ChainError(stringResource(R.string.names_chainlist_failed), st.detail))
                            TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.common_retry)) }
                        }
                    }
                    is CatalogState.Loaded -> {
                        if (results.isNullOrEmpty()) item("none") {
                            Text(
                                stringResource(R.string.names_chainlist_no_match, query.trim()),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 16.dp),
                            )
                        } else items(results, key = { it.id }) { entry ->
                            val added = entry.id in existingIds
                            PageRow(
                                title = entry.name,
                                subtitle = stringResource(R.string.names_chain_subtitle, entry.id.toString(), entry.symbol),
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
    if (added) Strings.get(R.string.names_catalog_already_added) else null,
    when (val n = entry.rpcUrls.size) {
        0 -> Strings.get(R.string.names_catalog_no_key_free_rpc)
        else -> Strings.plural(R.plurals.names_catalog_key_free_rpcs, n, n)
    },
    Strings.get(R.string.names_chain_testnet).takeIf { entry.isTestnet },
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
    RpcUrls.Rejection.EMPTY, RpcUrls.Rejection.NOT_A_URL -> Strings.get(R.string.names_rpc_hint_not_a_url)
    RpcUrls.Rejection.TOO_LONG -> Strings.get(R.string.names_rpc_hint_too_long)
    RpcUrls.Rejection.NON_ASCII -> Strings.get(R.string.names_rpc_hint_non_ascii)
    RpcUrls.Rejection.SCHEME -> Strings.get(R.string.names_rpc_hint_scheme)
    RpcUrls.Rejection.CREDENTIALS -> Strings.get(R.string.names_rpc_hint_credentials)
    RpcUrls.Rejection.PLACEHOLDER -> Strings.get(R.string.names_rpc_hint_placeholder)
    RpcUrls.Rejection.INTERNAL_HOST -> Strings.get(R.string.names_rpc_hint_internal_host)
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
                ChainStore.AddResult.BUILT_IN ->
                    error = Strings.get(R.string.names_add_chain_built_in, c.id.toString())
                ChainStore.AddResult.DUPLICATE ->
                    error = Strings.get(R.string.names_add_chain_duplicate, c.id.toString())
                ChainStore.AddResult.FAILED -> error = Strings.get(R.string.names_add_chain_failed)
            }
        }
    }

    val rpcs = currentRpcs()
    val chain = currentChain()
    val newRpcCheck = RpcUrls.validate(newRpc)

    FullScreenScaffold(
        title = ROW_ADD_CHAIN,
        onDismiss = onBack,
        // A full-screen dialog: Add commits it from the header, and the way
        // out drops what was typed, so it closes with × rather than going back.
        exit = PageExit.Close,
        trailing = {
            TextButton(onClick = { submit() }, enabled = chain != null && !saving) {
                Text(stringResource(R.string.common_add))
            }
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
                SectionCard(title = stringResource(R.string.names_chain_section)) {
                    FormField(
                        value = id, onValueChange = { id = it; error = null },
                        label = stringResource(R.string.names_chain_id),
                        placeholder = "137",
                        hint = if (id.isNotBlank() && ChainInput.parseId(id) == null) {
                            stringResource(R.string.names_chain_id_hint, Chain.MAX_ID)
                        } else null,
                        keyboardType = KeyboardType.Ascii,
                        literal = true,
                    )
                    FormField(
                        value = name, onValueChange = { name = it }, label = stringResource(R.string.names_chain_name),
                        placeholder = "Polygon",
                        hint = if (name.isNotEmpty() && ChainInput.parseName(name) == null) {
                            stringResource(R.string.names_chain_name_hint, Chain.MAX_NAME_LENGTH)
                        } else null,
                    )
                }
                SectionCard(title = stringResource(R.string.names_currency)) {
                    FormField(
                        value = symbol, onValueChange = { symbol = it },
                        label = stringResource(R.string.names_currency_symbol),
                        placeholder = "POL",
                        hint = if (symbol.isNotEmpty() && ChainInput.parseSymbol(symbol) == null) {
                            stringResource(R.string.names_currency_symbol_hint, Chain.MAX_SYMBOL_LENGTH)
                        } else null,
                        literal = true,
                    )
                    FormField(
                        value = decimals, onValueChange = { decimals = it },
                        label = stringResource(R.string.names_currency_decimals),
                        placeholder = "18",
                        hint = if (ChainInput.parseDecimals(decimals) == null) {
                            stringResource(R.string.names_currency_decimals_hint)
                        } else null,
                        keyboardType = KeyboardType.Number,
                        literal = true,
                    )
                }
                SectionCard(title = stringResource(R.string.names_block_explorer)) {
                    FormField(
                        value = explorer, onValueChange = { explorer = it },
                        label = stringResource(R.string.names_block_explorer_url),
                        placeholder = "https://polygonscan.com",
                        hint = if (explorer.isNotBlank() && ChainInput.normalizeExplorer(explorer) == null) {
                            stringResource(R.string.names_block_explorer_hint)
                        } else null,
                        url = true,
                    )
                }
                SectionCard(title = stringResource(R.string.names_rpc_endpoints_section)) {
                    if (rpcs.isEmpty()) {
                        Text(
                            stringResource(R.string.names_rpc_endpoints_empty),
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
                                    contentDescription = stringResource(R.string.names_remove_item, url),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (rpcs.size < Chain.MAX_RPC_URLS) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.weight(1f)) {
                                FormField(
                                    value = newRpc, onValueChange = { newRpc = it },
                                    label = stringResource(R.string.names_rpc_url),
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
                                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.names_add_rpc_endpoint))
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
    modifier: Modifier = Modifier,
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
            modifier = modifier.fillMaxWidth(),
        )
    }
    if (literal) NoSuggestionsTextInput(field) else field()
}
