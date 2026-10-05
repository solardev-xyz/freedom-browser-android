package baby.freedom.mobile.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import baby.freedom.mobile.R
import baby.freedom.mobile.data.NodeSettings
import baby.freedom.mobile.l10n.Strings
import baby.freedom.swarm.NodeInfo
import baby.freedom.swarm.NodeStatus
import java.math.BigInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The chequebook as the light node's gateway reports it (#117): its
 * address and what it holds, and with [withWallet] the account's own
 * xBZZ, read every [pollMs] while [light], and again at once whenever
 * [refresh] changes (a spend just ended). `/wallet` costs the gateway two
 * chain reads, so only the page that offers a deposit asks for it.
 */
@Composable
internal fun rememberChequebookState(
    light: Boolean,
    refresh: Any = Unit,
    withWallet: Boolean = false,
    pollMs: Long = CHEQUEBOOK_POLL_MS,
): ChequebookState {
    val state by produceState(ChequebookState(), light, refresh, withWallet, pollMs) {
        if (!light) value = ChequebookState()
        while (light) {
            val address = gatewayGet("/chequebook/address")?.let(::chequebookFrom)
            val funds = if (address.isNullOrEmpty()) null else gatewayGet("/chequebook/balance")?.let(::chequebookFundsFrom)
            val wallet = if (withWallet) gatewayGet("/wallet", WALLET_TIMEOUT_MS)?.let(::walletBzzFrom) else null
            // A failed read keeps what was last known.
            val none = address == ""
            val known = address ?: value.address
            value = ChequebookState(
                address = known,
                balancePlur = if (none) null else funds?.totalPlur ?: value.balancePlur,
                walletPlur = wallet ?: value.walletPlur,
                availablePlur = if (none) null else funds?.availablePlur ?: value.availablePlur,
                availableUpperBound = if (none) false else funds?.availableUpperBound ?: value.availableUpperBound,
                ledgerLost = if (none) false else funds?.ledgerLost ?: value.ledgerLost,
            )
            delay(pollMs)
        }
    }
    return state
}

/**
 * ant's `ant_swap_status` while the node runs, read every [SWAP_POLL_MS]
 * through `:node` (no network: ant answers from memory), and again at once
 * whenever [refresh] changes; null until the first answer. A failed read
 * keeps the last answer.
 */
@Composable
internal fun rememberSwapStatus(running: Boolean, refresh: Any = Unit): SwapStatus? {
    val status by produceState<SwapStatus?>(null, running, refresh) {
        if (!running) value = null
        while (running) {
            val answer = withContext(Dispatchers.IO) { StampClient.call("swapStatus") }
            (answer as? StampClient.Answer.Ok)?.json?.let(::swapStatusFrom)?.let { value = it }
            delay(SWAP_POLL_MS)
        }
    }
    return status
}

/**
 * The chequebook page (#117), over the node page: the node's chequebook,
 * what it holds and what it can still spend, whether downloads pay peers
 * from it (the "Pay peers from the chequebook" switch, and a lost cheque
 * ledger to confirm), and a deposit into it from the node's own xBZZ.
 */
@Composable
internal fun ChequebookScreen(nodeInfo: NodeInfo, onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)
    val spend by StampClient.spend.collectAsState()
    // A search for owned stamps (#118) excludes a deposit as it does a buy.
    val discovery by StampClient.discovery.collectAsState()
    val blocked = chequebookBlockedReason(nodeInfo)
    // Bumped after a spend ends, so the balances show it at once.
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(spend) {
        if (spend is StampClient.Spend.Done || spend is StampClient.Spend.Failed) refresh++
    }
    val state = rememberChequebookState(light = blocked == null, refresh = refresh, withWallet = true)
    val context = LocalContext.current
    val settings = remember(context) { NodeSettings.get(context) }
    // The switch's setting; null until read, so it doesn't flicker.
    // MainActivity relays it to the node, live and after every init.
    val swapWanted by remember(settings) { settings.swarmSwapEnabled }.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    // A lost ledger confirmed: read again at once.
    var swapRefresh by remember { mutableIntStateOf(0) }
    val swap = rememberSwapStatus(nodeInfo.status == NodeStatus.Running, refresh = swapWanted to swapRefresh)
    // The lost-ledger confirmation: open, and how the last one went.
    var confirmingLiability by remember { mutableStateOf<String?>(null) }
    var liabilityOutcome by remember { mutableStateOf<String?>(null) }
    var amount by rememberSaveable { mutableStateOf<String?>(null) }
    val amountPlur = amount?.let(::BigInteger)
    // What the confirmation shows, captured when it opens: that's what's sent.
    var confirming by remember { mutableStateOf<Pair<String, BigInteger>?>(null) }

    FullScreenScaffold(title = stringResource(R.string.stamps_chequebook_title), onDismiss = onDismiss) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            // Even with the node off: a deposit the node finishes after the
            // user turned it off still shows how it went.
            spendStatusText(spend)?.let { text ->
                item("spend") { SpendBanner(spend, text) }
            }
            if (blocked != null) {
                item("blocked") { MutedText(blocked) }
                return@LazyColumn
            }
            item("credit") {
                ChequebookCreditCards(
                    state = state,
                    swap = swap,
                    swapWanted = swapWanted,
                    liabilityOutcome = liabilityOutcome,
                    onSwapChange = { on -> scope.launch { settings.setSwarmSwapEnabled(on) } },
                    onConfirmLost = { liabilityOutcome = null; confirmingLiability = it },
                )
            }
            item("deposit") {
                SectionCard(title = stringResource(R.string.stamps_deposit)) {
                    DEPOSIT_PRESETS_PLUR.forEach { preset ->
                        ChoiceRow(selected = preset == amountPlur, label = formatBzzExact(preset)) {
                            amount = preset.toString()
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    val reason = depositBlockedReason(nodeInfo, state, amountPlur)
                    val running = !StampClient.canSpend(spend, discovery)
                    when {
                        spend is StampClient.Spend.Running ->
                            MutedText(stringResource(R.string.stamps_chequebook_other_payment))
                        running -> MutedText(stringResource(R.string.stamps_chequebook_searching))
                        reason != null -> MutedText(reason)
                    }
                    // Short of xBZZ: fund the node first, as publish setup
                    // does — its address to send xBZZ to on Gnosis Chain.
                    val fundTo = fundingAddress(nodeInfo)
                    if (!running && fundTo != null && depositNeedsFunding(state, amountPlur)) {
                        Spacer(Modifier.height(6.dp))
                        DetailRow(stringResource(R.string.publish_setup_node_address), fundTo, mono = true, singleLine = false)
                        SubLine(stringResource(R.string.stamps_credit_fund_node_note))
                        OutlinedButton(onClick = { copyNodeAddress(context, fundTo) }) {
                            Text(stringResource(R.string.common_copy_address))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            val address = state.address
                            if (!address.isNullOrEmpty() && amountPlur != null) confirming = address to amountPlur
                        },
                        enabled = reason == null && !running,
                    ) { Text(stringResource(R.string.stamps_deposit)) }
                }
            }
        }
    }
    confirming?.let { (address, plur) ->
        SpendConfirmDialog(
            title = stringResource(R.string.stamps_chequebook_deposit_title, formatBzzExact(plur)),
            body = depositConfirmText(plur, address),
            confirmLabel = stringResource(R.string.stamps_deposit),
            onConfirm = {
                confirming = null
                if (StampClient.deposit(address, plur)) amount = null
            },
            onDismiss = { confirming = null },
        )
    }
    confirmingLiability?.let { chequebook ->
        SpendConfirmDialog(
            title = stringResource(R.string.stamps_credit_lost_dialog_title),
            body = stringResource(R.string.stamps_credit_lost_dialog_body, chequebook),
            confirmLabel = stringResource(R.string.stamps_credit_lost_dialog_confirm),
            onConfirm = {
                confirmingLiability = null
                scope.launch {
                    val answer = withContext(Dispatchers.IO) {
                        StampClient.call("confirmLiability", JSONObject().put("chequebook", chequebook))
                    }
                    liabilityOutcome = liabilityOutcomeText(answer)
                    swapRefresh++
                    refresh++
                }
            },
            onDismiss = { confirmingLiability = null },
        )
    }
}

/**
 * The chequebook page's top: the chequebook with its spendable credit next
 * to the on-chain balance, whether downloads pay peers from it and why not
 * (with the "Pay peers from the chequebook" switch), and a lost cheque
 * ledger to confirm. Stateless: [ChequebookScreen] reads the node.
 * [swapWanted] is the saved switch (null until read); [swap] is ant's
 * `ant_swap_status` (null until read); [liabilityOutcome] how the last
 * lost-ledger confirmation went.
 */
@Composable
internal fun ChequebookCreditCards(
    state: ChequebookState,
    swap: SwapStatus?,
    swapWanted: Boolean?,
    liabilityOutcome: String?,
    onSwapChange: (Boolean) -> Unit,
    onConfirmLost: (chequebook: String) -> Unit,
) {
    val credit = creditStatus(swap, state)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard(title = stringResource(R.string.stamps_chequebook_title)) {
            MutedText(stringResource(R.string.stamps_chequebook_intro))
            Spacer(Modifier.height(6.dp))
            DetailRow(
                stringResource(R.string.stamps_chequebook_address),
                when (state.address) {
                    null -> stringResource(R.string.stamps_checking)
                    "" -> stringResource(R.string.stamps_chequebook_none_yet)
                    else -> state.address
                },
                mono = !state.address.isNullOrEmpty(),
                singleLine = false,
            )
            if (state.address != "") {
                // Spendable first: uncashed cheques make the on-chain
                // figure read as more than is left.
                DetailRow(
                    stringResource(R.string.stamps_credit_spendable),
                    state.availablePlur?.let { plur ->
                        if (state.availableUpperBound) stringResource(R.string.stamps_credit_at_most, formatBzz(plur)) else formatBzz(plur)
                    } ?: stringResource(R.string.stamps_checking),
                    singleLine = false,
                )
                SubLine(
                    stringResource(
                        if (state.availableUpperBound) R.string.stamps_credit_spendable_upper_note else R.string.stamps_credit_spendable_note,
                    ),
                )
                DetailRow(
                    stringResource(R.string.stamps_credit_on_chain),
                    state.balancePlur?.let(::formatBzz) ?: stringResource(R.string.stamps_checking),
                    singleLine = false,
                )
                SubLine(stringResource(R.string.stamps_credit_on_chain_note))
            }
            DetailRow(
                stringResource(R.string.stamps_node_xbzz),
                state.walletPlur?.let(::formatBzz) ?: stringResource(R.string.stamps_checking),
                singleLine = false,
            )
            SubLine(stringResource(R.string.stamps_chequebook_wallet_note))
        }
        SectionCard(title = stringResource(R.string.stamps_credit_downloads_title)) {
            DetailRow(stringResource(R.string.stamps_credit_downloads), creditStatusText(credit), singleLine = false)
            if (credit is CreditStatus.Paying && credit.low) {
                SubLine(stringResource(R.string.stamps_credit_low))
            }
            Spacer(Modifier.height(6.dp))
            PayPeersSwitch(wanted = swapWanted, running = swap?.swapEnabled, onChange = onSwapChange)
            Spacer(Modifier.height(6.dp))
            SubLine(stringResource(R.string.stamps_credit_cost_note))
        }
        val lostChequebook = state.address
        if (state.ledgerLost && !lostChequebook.isNullOrEmpty()) {
            SectionCard(title = stringResource(R.string.stamps_credit_lost_title)) {
                Text(
                    stringResource(R.string.stamps_credit_lost_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { onConfirmLost(lostChequebook) }) {
                    Text(stringResource(R.string.stamps_credit_lost_confirm))
                }
            }
        }
        liabilityOutcome?.let { MutedText(it) }
    }
}

/**
 * "Pay peers from the chequebook": the saved setting ([wanted], null until
 * read), with a note while the node still runs the other way ([running]).
 */
@Composable
private fun PayPeersSwitch(wanted: Boolean?, running: Boolean?, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .switchRow(checked = wanted == true, onCheckedChange = onChange, enabled = wanted != null)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.stamps_credit_switch), fontWeight = FontWeight.Medium)
            Text(
                stringResource(R.string.stamps_credit_switch_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (wanted != null && running != null && wanted != running) {
                Text(
                    stringResource(R.string.stamps_credit_switch_applying),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = wanted == true, onCheckedChange = null, enabled = wanted != null)
    }
}

/** How a lost-ledger confirmation went, as the page says it. */
internal fun liabilityOutcomeText(answer: StampClient.Answer): String = when (answer) {
    is StampClient.Answer.Ok ->
        Strings.get(if (answer.json.optBoolean("confirmed")) R.string.stamps_credit_lost_confirmed else R.string.stamps_credit_lost_nothing)
    is StampClient.Answer.Failed -> Strings.get(R.string.stamps_credit_lost_failed, answer.message)
}

private const val CHEQUEBOOK_POLL_MS = 10_000L

/** ant answers `ant_swap_status` from memory: cheap to ask often. */
private const val SWAP_POLL_MS = 5_000L

/** ant reads both balances from the chain for `/wallet`, each under 10 s. */
private const val WALLET_TIMEOUT_MS = 25_000
