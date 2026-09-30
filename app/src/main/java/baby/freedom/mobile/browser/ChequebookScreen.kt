package baby.freedom.mobile.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import baby.freedom.mobile.R
import baby.freedom.swarm.NodeInfo
import java.math.BigInteger
import kotlinx.coroutines.delay

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
            val balance = if (address.isNullOrEmpty()) null else gatewayGet("/chequebook/balance")?.let(::chequebookBalanceFrom)
            val wallet = if (withWallet) gatewayGet("/wallet", WALLET_TIMEOUT_MS)?.let(::walletBzzFrom) else null
            // A failed read keeps what was last known.
            value = ChequebookState(
                address = address ?: value.address,
                balancePlur = if (address == "") null else balance ?: value.balancePlur,
                walletPlur = wallet ?: value.walletPlur,
            )
            delay(pollMs)
        }
    }
    return state
}

/**
 * The chequebook page (#117), over the node page: the node's chequebook,
 * what it holds, and a deposit into it from the node's own xBZZ.
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
            item("chequebook") {
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
                        DetailRow(stringResource(R.string.stamps_chequebook_balance), state.balancePlur?.let(::formatBzz) ?: stringResource(R.string.stamps_checking))
                    }
                    DetailRow(stringResource(R.string.stamps_node_xbzz), state.walletPlur?.let(::formatBzz) ?: stringResource(R.string.stamps_checking))
                    SubLine(stringResource(R.string.stamps_chequebook_wallet_note))
                }
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
}

private const val CHEQUEBOOK_POLL_MS = 10_000L

/** ant reads both balances from the chain for `/wallet`, each under 10 s. */
private const val WALLET_TIMEOUT_MS = 25_000
