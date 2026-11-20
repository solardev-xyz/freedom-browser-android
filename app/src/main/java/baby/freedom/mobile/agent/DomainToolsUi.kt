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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartToy
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import baby.freedom.mobile.R
import baby.freedom.mobile.browser.ArmingButton
import baby.freedom.mobile.browser.DetailsExpander
import baby.freedom.mobile.browser.ErrorWithDetails
import baby.freedom.mobile.browser.ObscuredTapNotice
import baby.freedom.mobile.browser.PromptTapGuard
import baby.freedom.mobile.browser.RestartsTapGuardInWindow
import baby.freedom.mobile.browser.ReviewRow
import baby.freedom.mobile.browser.SheetButtonRow
import baby.freedom.mobile.browser.SheetWarning
import baby.freedom.mobile.browser.Warning
import baby.freedom.mobile.browser.WarningLevel
import baby.freedom.mobile.browser.feeDetail
import baby.freedom.mobile.browser.feeFootnote
import baby.freedom.mobile.browser.feeText
import baby.freedom.mobile.browser.nonceDetail
import baby.freedom.mobile.browser.protectedPress
import baby.freedom.mobile.browser.rememberArmedTapGuard
import baby.freedom.mobile.browser.restartsTapGuard
import baby.freedom.mobile.browser.unlockError
import baby.freedom.mobile.names.NameOperation.Intent
import baby.freedom.mobile.names.NameRegistrar
import baby.freedom.mobile.names.ethText
import baby.freedom.mobile.wallet.BiometricVaultAuthenticator
import baby.freedom.mobile.wallet.Vault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Where the `domain_*` tools' sheets (#599) come up: on the browser
 * screen, next to [AgentTransferHost]. While it's composed the tools can
 * reach the names code and the wallet ([AgentDomains.backend], from the
 * application context only); once it's gone every waiting sheet is
 * withdrawn. Of the agent's sheets, the one asked first shows
 * ([agentSheetFirst]), never two at once.
 */
@Composable
internal fun AgentDomainHost() {
    val context = LocalContext.current
    DisposableEffect(context) {
        val backend = DomainBackend(NameRegistrar.get(context), AppTransferWallet(context))
        AgentDomains.backend = backend
        onDispose {
            if (AgentDomains.backend === backend) AgentDomains.backend = null
            AgentDomains.withdrawAll()
        }
    }
    val ask by AgentDomains.shown.collectAsState()
    val approval by AgentApprovals.shown.collectAsState()
    val transfer by AgentTransfers.shown.collectAsState()
    val shown = ask ?: return
    if (!agentSheetFirst(shown.id, approval?.id, transfer?.id)) return
    key(shown.id) { DomainSheet(shown) }
}

/**
 * The review of one name transaction, built only from the operation and
 * the priced transaction ([DomainAsk.review], the names screen's own
 * review rows — desktop's `presentPlan`): what the step does, then who
 * pays, the network, what it sends and its fee, and the contract and
 * nonce under Details. Confirm arms after
 * [PromptTapGuard.SPEND_PROTECTION_MS] untouched on screen, like every
 * sheet that spends, and opens a locked wallet first; Don't send, a swipe
 * down, a tap outside and Back are the user's no.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DomainSheet(ask: DomainAsk) {
    val context = LocalContext.current
    val vault = remember(context) { Vault.get(context) }
    val vaultState by vault.state.collectAsState()
    val tap = rememberArmedTapGuard(ask.id, PromptTapGuard.SPEND_PROTECTION_MS)
    val guard = tap.guard
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Pair<String, String?>?>(null) }
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || (guard.accepts() && !busy) },
    )
    val quote = ask.quote
    val request = quote.request
    val chain = request.chain
    val ledger = request.from.isLedger

    fun decline() {
        if (guard.accepts() && !busy) AgentDomains.answer(ask.id, TransferAnswer.Declined)
    }

    fun confirm() {
        if (!guard.accepts() || busy) return
        if (ledger) {
            AgentDomains.answer(ask.id, TransferAnswer.Approved(acceptedUnverified = false))
            return
        }
        busy = true
        error = null
        scope.launch {
            try {
                if (!vault.unlockedNow()) vault.unlock(BiometricVaultAuthenticator(context))
                AgentDomains.answer(ask.id, TransferAnswer.Approved(acceptedUnverified = false))
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
        modifier = Modifier.statusBarsPadding().testTag("agent-domain"),
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
                    stringResource(R.string.agent_domain_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
            }
            Spacer(Modifier.height(12.dp))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                Text(ask.review.headline, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(4.dp))
                for (row in ask.review.rows) ReviewRow(row.label, row.value)
                if (ask.op.intent == Intent.REGISTER) {
                    ReviewRow(stringResource(R.string.namereg_review_owner_register), request.from.name, address = ask.op.account)
                }
                ReviewRow(stringResource(R.string.namereg_review_paid_by), request.from.name, address = request.from.address)
                ReviewRow(stringResource(R.string.namereg_review_network), chain.name)
                ReviewRow(stringResource(R.string.namereg_review_amount), ethText(request.amount), mono = true)
                ReviewRow(
                    stringResource(R.string.namereg_review_network_fee),
                    stringResource(R.string.namereg_review_fee_up_to, feeText(quote.maxFee, chain)),
                    mono = true,
                    detail = feeDetail(quote),
                )
                if (ask.repriced) Warning(SheetWarning(WarningLevel.Info, stringResource(R.string.agent_domain_repriced)))
                Warning(SheetWarning(WarningLevel.Info, stringResource(R.string.agent_domain_note)))
                if (vaultState is Vault.State.Locked && !ledger) {
                    Warning(SheetWarning(WarningLevel.Info, stringResource(R.string.agent_transfer_locked)))
                }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                DetailsExpander(Modifier.testTag("agent-domain-details")) {
                    ReviewRow(stringResource(R.string.namereg_review_contract), ask.review.contract.displayName, address = request.to)
                    ReviewRow(stringResource(R.string.namereg_review_nonce), quote.tx.nonce.toString(), mono = true, detail = nonceDetail(quote))
                    Text(
                        feeFootnote(quote.tx),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            error?.let { (message, detail) ->
                Spacer(Modifier.height(8.dp))
                ErrorWithDetails(message, detail, Modifier.testTag("agent-domain-error"))
            }
            Spacer(Modifier.height(16.dp))
            ObscuredTapNotice(tap)
            SheetButtonRow {
                OutlinedButton(
                    onClick = ::decline,
                    enabled = tap.armed && !busy,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("agent-domain-decline"),
                ) { Text(stringResource(R.string.agent_transfer_decline)) }
                ArmingButton(
                    tap = tap,
                    protectionMs = PromptTapGuard.SPEND_PROTECTION_MS,
                    onClick = ::confirm,
                    enabled = !busy,
                    busy = busy,
                    modifier = Modifier.heightIn(min = 48.dp).protectedPress(tap).testTag("agent-domain-confirm"),
                ) {
                    Text(stringResource(R.string.namereg_confirm), textAlign = TextAlign.Center)
                }
            }
        }
    }
}
