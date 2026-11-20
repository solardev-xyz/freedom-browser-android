package baby.freedom.mobile.agent

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import baby.freedom.mobile.R
import baby.freedom.mobile.browser.ArmingButton
import baby.freedom.mobile.browser.CopyableAddressRow
import baby.freedom.mobile.browser.DetailsExpander
import baby.freedom.mobile.browser.ErrorWithDetails
import baby.freedom.mobile.browser.NameTrust
import baby.freedom.mobile.browser.NameTrustLine
import baby.freedom.mobile.browser.ObscuredTapNotice
import baby.freedom.mobile.browser.PromptTapGuard
import baby.freedom.mobile.browser.RestartsTapGuardInWindow
import baby.freedom.mobile.browser.ReviewRow
import baby.freedom.mobile.browser.SheetButtonRow
import baby.freedom.mobile.browser.SheetWarning
import baby.freedom.mobile.browser.TxReviewSummary
import baby.freedom.mobile.browser.Warning
import baby.freedom.mobile.browser.WarningLevel
import baby.freedom.mobile.browser.feeDetail
import baby.freedom.mobile.browser.feeFootnote
import baby.freedom.mobile.browser.feeText
import baby.freedom.mobile.browser.fiatNative
import baby.freedom.mobile.browser.fiatTokenTotal
import baby.freedom.mobile.browser.nonceDetail
import baby.freedom.mobile.browser.protectedPress
import baby.freedom.mobile.browser.rememberArmedTapGuard
import baby.freedom.mobile.browser.rememberFiatQuotes
import baby.freedom.mobile.browser.restartsTapGuard
import baby.freedom.mobile.browser.sendHeadline
import baby.freedom.mobile.browser.sendTxWarnings
import baby.freedom.mobile.browser.unlockError
import baby.freedom.mobile.wallet.BiometricVaultAuthenticator
import baby.freedom.mobile.wallet.SendAmounts
import baby.freedom.mobile.wallet.Vault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Where `wallet_transfer`'s sheets (#596) come up: on the browser
 * screen, over everything else (the agent's chat included), next to
 * [AgentApprovalHost]. While it's composed the tool can reach the wallet
 * ([AgentTransfers.wallet], from the application context only); once
 * it's gone every waiting transfer is withdrawn. Of a transfer and an
 * approval card both waiting, the one asked first shows, never both.
 */
@Composable
internal fun AgentTransferHost() {
    val context = LocalContext.current
    DisposableEffect(context) {
        val wallet = AppTransferWallet(context)
        AgentTransfers.wallet = wallet
        onDispose {
            if (AgentTransfers.wallet === wallet) AgentTransfers.wallet = null
            AgentTransfers.withdrawAll()
        }
    }
    val ask by AgentTransfers.shown.collectAsState()
    val approval by AgentApprovals.shown.collectAsState()
    val shown = ask ?: return
    if (approval.let { it != null && it.id < shown.id }) return
    key(shown.id) { TransferSheet(shown) }
}

/**
 * The review of one proposed transfer, built only from the priced
 * transaction ([TransferAsk.quote]) — never from the model's words: the
 * Send page's hero line, fee and total, the recipient in full (with how
 * its name was vouched for, and the one-server name's checkbox gating
 * Confirm), the account it's sent from, the shared transaction notes
 * (a send it replaces, a high fee, a repriced fee), and the network,
 * contract, nonce and gas under Details.
 *
 * Confirm arms after [PromptTapGuard.SPEND_PROTECTION_MS] untouched on
 * screen and drops a covered or early press, like every sheet that
 * spends; on a locked wallet it asks for the screen lock first. Don't
 * send, a swipe down, a tap outside and Back are the user's no.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransferSheet(ask: TransferAsk) {
    val context = LocalContext.current
    val vault = remember(context) { Vault.get(context) }
    val vaultState by vault.state.collectAsState()
    val tap = rememberArmedTapGuard(ask.id, PromptTapGuard.SPEND_PROTECTION_MS)
    val guard = tap.guard
    var busy by remember { mutableStateOf(false) }
    var accepted by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Pair<String, String?>?>(null) }
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || (guard.accepts() && !busy) },
    )
    val request = ask.quote.request
    val ledger = request.from.isLedger
    val canConfirm = !ask.needsAcceptance || accepted

    fun decline() {
        if (guard.accepts() && !busy) AgentTransfers.answer(ask.id, TransferAnswer.Declined)
    }

    fun confirm() {
        if (!guard.accepts() || busy || !canConfirm) return
        val acceptedNow = ask.needsAcceptance && accepted
        if (ledger) {
            // Signed on the Ledger, which shows it again there: nothing to open here.
            AgentTransfers.answer(ask.id, TransferAnswer.Approved(acceptedNow))
            return
        }
        busy = true
        error = null
        scope.launch {
            try {
                if (!vault.unlockedNow()) vault.unlock(BiometricVaultAuthenticator(context))
                AgentTransfers.answer(ask.id, TransferAnswer.Approved(acceptedNow))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = unlockError(e)
            } finally {
                busy = false
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (guard.accepts() && !busy) decline() else guard.noteInput() },
        sheetState = sheetState,
        modifier = Modifier.statusBarsPadding().testTag("agent-transfer"),
    ) {
        RestartsTapGuardInWindow(guard)
        Column(
            modifier = Modifier
                .restartsTapGuard(guard)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.SmartToy, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.agent_transfer_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
            }
            Spacer(Modifier.height(12.dp))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                TransferBody(ask, accepted, enabled = !busy, onAccept = { accepted = it })
                if (vaultState is Vault.State.Locked && !ledger) {
                    Warning(SheetWarning(WarningLevel.Info, stringResource(R.string.agent_transfer_locked)))
                }
            }
            error?.let { (message, detail) ->
                Spacer(Modifier.height(8.dp))
                ErrorWithDetails(message, detail, Modifier.testTag("agent-transfer-error"))
            }
            Spacer(Modifier.height(16.dp))
            ObscuredTapNotice(tap)
            val amount = SendAmounts.exact(request.amount, request.token.decimals)
            SheetButtonRow {
                OutlinedButton(
                    onClick = ::decline,
                    enabled = tap.armed && !busy,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("agent-transfer-decline"),
                ) { Text(stringResource(R.string.agent_transfer_decline)) }
                ArmingButton(
                    tap = tap,
                    protectionMs = PromptTapGuard.SPEND_PROTECTION_MS,
                    onClick = ::confirm,
                    enabled = !busy && canConfirm,
                    busy = busy,
                    modifier = Modifier.heightIn(min = 48.dp).protectedPress(tap).testTag("agent-transfer-confirm"),
                ) {
                    Text(
                        stringResource(
                            if (ledger) R.string.send_confirm_amount_ledger else R.string.send_confirm_amount,
                            amount,
                            request.token.symbol,
                        ),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun TransferBody(ask: TransferAsk, accepted: Boolean, enabled: Boolean, onAccept: (Boolean) -> Unit) {
    val quote = ask.quote
    val request = quote.request
    val chain = request.chain
    val token = request.token
    val amount = SendAmounts.exact(request.amount, token.decimals)
    val fee = feeText(quote.maxFee, chain)
    val fiat = rememberFiatQuotes()
    TxReviewSummary(
        headline = sendHeadline(amount, token.symbol, request.to, request.toName, chain.name),
        fee = stringResource(R.string.send_up_to, fee),
        total = quote.nativeTotal?.let { stringResource(R.string.send_up_to, feeText(it, chain)) }
            ?: stringResource(R.string.send_total_token, amount, token.symbol, fee),
        feeFiat = fiatNative(fiat, chain, quote.maxFee),
        totalFiat = quote.nativeTotal?.let { fiatNative(fiat, chain, it) }
            ?: fiatTokenTotal(fiat, token, request.amount, chain, quote.maxFee),
    ) {
        if (request.to.equals(request.from.address, ignoreCase = true)) {
            Warning(SheetWarning(WarningLevel.Info, stringResource(R.string.send_self_send_note)))
        }
    }
    sendTxWarnings(quote, ask.repriced).forEach { Warning(it) }
    Warning(SheetWarning(WarningLevel.Info, stringResource(R.string.agent_transfer_note)))
    Spacer(Modifier.height(8.dp))
    HorizontalDivider()
    CopyableAddressRow(
        label = stringResource(R.string.send_label_to),
        address = request.to,
        name = request.toName,
        below = {
            val name = request.toName
            val trust = ask.nameTrust
            if (name != null && trust != null) NameTrustLine(NameTrust(name, trust, request.to))
            if (ask.needsAcceptance) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .toggleable(value = accepted, enabled = enabled, role = Role.Checkbox, onValueChange = onAccept)
                        .testTag("agent-transfer-accept"),
                ) {
                    Checkbox(checked = accepted, onCheckedChange = null, enabled = enabled)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.send_accept_unverified), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
    )
    CopyableAddressRow(
        label = stringResource(R.string.send_label_from),
        address = request.from.address,
        name = request.from.name,
    )
    DetailsExpander(Modifier.testTag("agent-transfer-details")) {
        ReviewRow(stringResource(R.string.send_label_network), "${chain.name} (${chain.id})")
        token.address?.let {
            CopyableAddressRow(label = stringResource(R.string.send_label_token_contract, token.symbol), address = it)
        }
        ReviewRow(stringResource(R.string.send_label_nonce), quote.tx.nonce.toString(), mono = true, detail = nonceDetail(quote))
        ReviewRow(stringResource(R.string.send_label_gas), feeDetail(quote))
        request.toName?.let {
            Text(
                stringResource(R.string.send_name_recheck_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Text(
            feeFootnote(quote.tx),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
