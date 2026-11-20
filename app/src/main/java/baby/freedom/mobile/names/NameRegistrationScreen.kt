package baby.freedom.mobile.names

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import baby.freedom.mobile.l10n.pluralText
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import baby.freedom.mobile.R
import baby.freedom.mobile.browser.DetailsExpander
import baby.freedom.mobile.browser.FullScreenScaffold
import baby.freedom.mobile.browser.MutedText
import baby.freedom.mobile.browser.PageRow
import baby.freedom.mobile.browser.PageRowStyle
import baby.freedom.mobile.browser.ObscuredTapNotice
import baby.freedom.mobile.browser.PromptTapGuard
import baby.freedom.mobile.browser.ReviewRow
import baby.freedom.mobile.browser.SectionCard
import baby.freedom.mobile.browser.SendStatusSection
import baby.freedom.mobile.browser.SheetButtonRow
import baby.freedom.mobile.browser.TotalFigure
import baby.freedom.mobile.browser.feeDetail
import baby.freedom.mobile.browser.feeFootnote
import baby.freedom.mobile.browser.feeText
import baby.freedom.mobile.browser.nonceDetail
import baby.freedom.mobile.browser.protectedPress
import baby.freedom.mobile.browser.rememberArmedTapGuard
import baby.freedom.mobile.browser.safeErrorMessage
import baby.freedom.mobile.chains.BuiltInChains
import baby.freedom.mobile.chains.Chain
import baby.freedom.mobile.data.ChainStore
import baby.freedom.mobile.l10n.Strings
import baby.freedom.mobile.names.NameOperation.Intent
import baby.freedom.mobile.names.NameOperation.State
import baby.freedom.mobile.names.NameOperation.Step
import baby.freedom.mobile.wallet.BiometricVaultAuthenticator
import baby.freedom.mobile.wallet.DappCall
import baby.freedom.mobile.wallet.NameCallLabel
import baby.freedom.mobile.wallet.SendQuote
import baby.freedom.mobile.wallet.SendRequest
import baby.freedom.mobile.wallet.TokenRegistry
import baby.freedom.mobile.wallet.Vault
import baby.freedom.mobile.wallet.WalletAccount
import baby.freedom.mobile.wallet.WalletAccounts
import baby.freedom.mobile.wallet.WalletSender
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A step under review: the [plan] the registrar built and the wallet's [quote] of it, kept together. */
private class Reviewing(val op: NameOperation, val plan: NameRegistrar.Plan, val quote: SendQuote)

private sealed interface Checked {
    data object None : Checked
    data object Loading : Checked
    data class Ready(val check: NameRegistrar.Check) : Checked
    data class Failed(val message: String) : Checked
}

/**
 * Register and renew `.eth`, `.wei` and `.gwei` names (#579): check a
 * name's availability and price, then each transaction — the commit, the
 * registration, a renewal — through the wallet's review and unlock. Opens
 * on [start] when given (a name the browser found unregistered).
 */
@Composable
fun NameRegistrationScreen(
    onOpenUrl: (String) -> Unit,
    onDismiss: () -> Unit,
    start: String? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val registrar = remember(context) { NameRegistrar.get(context) }
    val vault = remember(context) { Vault.get(context) }
    val auth = remember(context) { BiometricVaultAuthenticator(context) }
    val sender = remember(context) { WalletSender.get(context) }
    val chains by remember(context) { ChainStore.get(context).chains }.collectAsState(initial = null)
    val chain: Chain = chains?.firstOrNull { it.id == NameContracts.CHAIN_ID } ?: BuiltInChains.ETHEREUM
    val accountList by remember(context) { WalletAccounts.get(context).accounts }.collectAsState()
    val payer: WalletAccount? = accountList?.active
    val vaultState by vault.state.collectAsState()
    val phraseBackedUp = when (val s = vaultState) {
        is Vault.State.Locked -> s.info.phraseKnown
        is Vault.State.Unlocked -> s.info.phraseKnown
        else -> true
    }
    val operations by registrar.operations.collectAsState()
    val sendStatus by sender.status.collectAsState()

    var input by rememberSaveable { mutableStateOf(start.orEmpty()) }
    var years by rememberSaveable { mutableIntStateOf(1) }
    var checked by remember { mutableStateOf<Checked>(Checked.None) }
    var reviewing by remember { mutableStateOf<Reviewing?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var dismissing by remember { mutableStateOf<NameOperation?>(null) }

    val space = input.trim().substringAfterLast('.', "").lowercase().let(NameSpace::of)
    val duration = if (space == null || space.nft) NameContracts.YEAR else years * NameContracts.YEAR

    fun runCheck() {
        val name = input.trim()
        if (name.isEmpty()) return
        checked = Checked.Loading
        error = null
        notice = null
        scope.launch {
            checked = try {
                Checked.Ready(registrar.check(name, duration))
            } catch (e: CancellationException) {
                throw e
            } catch (e: NameException) {
                Checked.Failed(e.message.orEmpty())
            } catch (e: Exception) {
                Checked.Failed(Strings.get(R.string.namereg_error_chain_unreachable))
            }
        }
    }

    // Opened on a name: check it at once.
    LaunchedEffect(Unit) { if (!start.isNullOrBlank()) runCheck() }

    /** [fresh]: [op] was started just now, so if its first step can't be built it's dropped again — nothing was sent. */
    fun review(op: NameOperation, fresh: Boolean = false) {
        val from = accountList?.accounts?.firstOrNull { it.address.equals(op.account, ignoreCase = true) } ?: run {
            error = Strings.get(R.string.namereg_op_account_missing)
            return
        }
        busy = true
        error = null
        notice = null
        scope.launch {
            try {
                val plan = registrar.plan(op.id)
                val current = registrar.get(op.id) ?: op
                val q = sender.prepare(
                    SendRequest(
                        chain, TokenRegistry.native(chain), from, plan.to, plan.value,
                        DappCall(null, plan.data, null, name = NameCallLabel(op.name, stepLabel(plan.step))),
                    ),
                )
                reviewing = Reviewing(current, plan, q)
            } catch (e: CancellationException) {
                throw e
            } catch (e: NameException) {
                error = e.message
                if (fresh) runCatching { registrar.dismiss(op.id) }
            } catch (e: Exception) {
                error = safeErrorMessage(e, Strings.get(R.string.namereg_action_price), phraseBackedUp)
            } finally {
                busy = false
            }
        }
    }

    fun begin(check: NameRegistrar.Check, intent: Intent) {
        val from = payer ?: return
        busy = true
        error = null
        scope.launch {
            val op = try {
                registrar.start(check, intent, from.address, from.index)
            } catch (e: CancellationException) {
                throw e
            } catch (e: NameException) {
                error = e.message
                null
            } finally {
                busy = false
            }
            if (op != null) {
                checked = Checked.None
                // A brand-new one (nothing sent yet) that can't be built is dropped again.
                if (op.state == State.READY) review(op, fresh = op.transactionHash == null && op.step != Step.REVEAL && op.revision == 1)
            }
        }
    }

    // Anything on its way is looked at every few seconds while the page is open: never resent, only read.
    val watching = operations.orEmpty().filter { it.state.waiting || (it.state == State.READY && it.step == Step.REVEAL) }.map { it.id }
    LaunchedEffect(watching) {
        while (watching.isNotEmpty()) {
            for (id in watching) {
                runCatching { registrar.refresh(id) }
            }
            delay(5_000)
        }
    }
    // The phone's clock, only for "about 45 s" while a commitment ages.
    var nowSeconds by remember { mutableStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            nowSeconds = System.currentTimeMillis() / 1000
        }
    }

    val back: () -> Unit = {
        if (reviewing != null) {
            reviewing = null
        } else {
            onDismiss()
        }
    }
    BackHandler(onBack = back)
    FullScreenScaffold(title = stringResource(R.string.namereg_title), onDismiss = back) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            val status = sendStatus?.takeIf { it.quote.request.dapp?.name != null }
            val r = reviewing
            val shown = r?.let { nameReview(it.op, it.plan, it.quote) }
            when {
                r != null -> item("review") {
                    if (shown == null) {
                        MutedText(stringResource(R.string.namereg_review_mismatch))
                    } else {
                        NameReviewCard(
                            review = shown,
                            op = r.op,
                            quote = r.quote,
                            busy = busy,
                            error = error,
                            onCancel = {
                                reviewing = null
                                error = null
                            },
                            onConfirm = confirm@{
                                if (sender.isStale(r.quote)) {
                                    reviewing = null
                                    notice = Strings.get(R.string.namereg_send_stale)
                                    return@confirm
                                }
                                busy = true
                                error = null
                                scope.launch {
                                    try {
                                        val q = r.quote
                                        if (!q.request.from.isLedger && !vault.unlockedNow()) vault.unlock(auth)
                                        val signer = WalletSender.signerFor(context, vault, q.request.from, fresh = { !sender.isStale(q) })
                                        when (val d = registrar.dispatch(r.plan) { sender.submitAndAwaitBroadcast(q, signer) }) {
                                            is NameRegistrar.Dispatched.Sent -> notice = Strings.get(
                                                if (r.plan.step == Step.COMMIT) R.string.namereg_sent_commit else R.string.namereg_sent,
                                            )
                                            is NameRegistrar.Dispatched.NotSent -> notice = d.message
                                            NameRegistrar.Dispatched.Unknown -> notice = Strings.get(R.string.namereg_state_dispatch_unknown)
                                        }
                                        reviewing = null
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: NameException) {
                                        reviewing = null
                                        notice = e.message
                                    } catch (e: Exception) {
                                        error = safeErrorMessage(e, Strings.get(R.string.wallet_action_unlock), phraseBackedUp)
                                    } finally {
                                        busy = false
                                    }
                                }
                            },
                        )
                    }
                }
                else -> {
                    // The wallet's own status of a name transaction on top (Try again, Keep waiting,
                    // the explorer), with the operations and the check still below it.
                    if (status != null) {
                        item("send") {
                            SendStatusSection(
                                status = status,
                                onOpenUrl = onOpenUrl,
                                onRetry = sender::retry,
                                onCheckAgain = sender::checkAgain,
                                onStopTracking = sender::discard,
                                onReviewAgain = {},
                                onDone = { if (!status.unresolved) sender.acknowledge() },
                            )
                        }
                    }
                    item("intro") {
                        SectionCard(title = stringResource(R.string.namereg_title)) {
                            MutedText(stringResource(R.string.namereg_intro))
                        }
                    }
                    item("check") {
                        CheckCard(
                            input = input,
                            onInput = {
                                input = it
                                checked = Checked.None
                            },
                            space = space,
                            years = years,
                            onYears = {
                                years = it
                                checked = Checked.None
                            },
                            checked = checked,
                            payer = payer,
                            busy = busy,
                            onCheck = ::runCheck,
                            onRegister = { c -> begin(c, Intent.REGISTER) },
                            onRenew = { c -> begin(c, Intent.RENEW) },
                        )
                    }
                    if (notice != null || error != null) {
                        item("notice") {
                            Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                                notice?.let { MutedText(it) }
                                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                    val ops = operations.orEmpty()
                    if (ops.isNotEmpty()) {
                        item("ops-title") {
                            Text(
                                stringResource(R.string.namereg_your_names),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.semantics { heading() },
                            )
                        }
                        items(ops, key = { it.id }) { op ->
                            OperationCard(
                                op = op,
                                account = accountList?.accounts?.firstOrNull { it.address.equals(op.account, ignoreCase = true) },
                                nowSeconds = nowSeconds,
                                busy = busy || sendStatus?.inFlight == true,
                                onReview = { review(op) },
                                onCheckAgain = {
                                    scope.launch {
                                        try {
                                            registrar.refresh(op.id)
                                        } catch (e: NameException) {
                                            error = e.message
                                        }
                                    }
                                },
                                onDismiss = { dismissing = op },
                            )
                        }
                    }
                }
            }
        }
    }

    dismissing?.let { op ->
        AlertDialog(
            onDismissRequest = { dismissing = null },
            title = { Text(stringResource(R.string.namereg_dismiss_title, op.name)) },
            text = {
                Text(
                    stringResource(
                        when {
                            op.step == Step.REVEAL && !op.state.terminal -> R.string.namereg_dismiss_paid_commit
                            op.state == State.PENDING || op.state == State.DISPATCH_UNKNOWN || op.state == State.VERIFICATION_NEEDED -> R.string.namereg_dismiss_unknown
                            else -> R.string.namereg_dismiss_plain
                        },
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    dismissing = null
                    scope.launch {
                        try {
                            registrar.dismiss(op.id)
                        } catch (e: NameException) {
                            error = e.message
                        }
                    }
                }) { Text(stringResource(R.string.namereg_dismiss)) }
            },
            dismissButton = { TextButton(onClick = { dismissing = null }) { Text(stringResource(R.string.namereg_cancel)) } },
        )
    }
}

@Composable
private fun CheckCard(
    input: String,
    onInput: (String) -> Unit,
    space: NameSpace?,
    years: Int,
    onYears: (Int) -> Unit,
    checked: Checked,
    payer: WalletAccount?,
    busy: Boolean,
    onCheck: () -> Unit,
    onRegister: (NameRegistrar.Check) -> Unit,
    onRenew: (NameRegistrar.Check) -> Unit,
) {
    SectionCard(title = stringResource(R.string.namereg_name_label)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = input,
                onValueChange = { onInput(it.take(255)) },
                label = { Text(stringResource(R.string.namereg_name_label)) },
                placeholder = { Text(stringResource(R.string.namereg_name_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Search, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onSearch = { onCheck() }),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.namereg_term_label), modifier = Modifier.weight(1f))
                if (space == null || space == NameSpace.ETH) {
                    IconButton(onClick = { onYears(years - 1) }, enabled = years > 1) {
                        Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.namereg_fewer_years))
                    }
                    Text(pluralText(R.plurals.namereg_years, years, years))
                    IconButton(onClick = { onYears(years + 1) }, enabled = years < NameContracts.MAX_YEARS) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.namereg_more_years))
                    }
                } else {
                    Text(pluralText(R.plurals.namereg_years, 1, 1))
                }
            }
            if (space != null && space.nft) MutedText(stringResource(R.string.namereg_term_one_year_only))
            Button(
                onClick = onCheck,
                enabled = input.isNotBlank() && checked != Checked.Loading,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.namereg_check)) }
            Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                when (checked) {
                    Checked.None -> Unit
                    Checked.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        MutedText(stringResource(R.string.namereg_checking))
                    }
                    is Checked.Failed -> Text(checked.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    is Checked.Ready -> CheckResult(checked.check, payer, busy, onRegister, onRenew)
                }
            }
        }
    }
}

@Composable
private fun CheckResult(
    check: NameRegistrar.Check,
    payer: WalletAccount?,
    busy: Boolean,
    onRegister: (NameRegistrar.Check) -> Unit,
    onRenew: (NameRegistrar.Check) -> Unit,
) {
    val name = check.parsed.name
    val s = check.state
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(
                when {
                    check.canRegister -> R.string.namereg_available
                    s.renewable -> R.string.namereg_taken
                    else -> R.string.namereg_unavailable
                },
                name,
            ),
            style = MaterialTheme.typography.titleMedium,
        )
        check.priceWei?.let { price ->
            if (check.canRegister) {
                TotalFigure(ethText(price))
                MutedText(stringResource(R.string.namereg_price_for, ethText(price), yearsText(check.duration)))
            }
        }
        if (!check.canRegister) {
            s.expiry?.takeIf { it.signum() > 0 }?.let { e ->
                val expired = e.toLong() <= s.snapshot.timestamp
                MutedText(stringResource(if (expired) R.string.namereg_expired_on else R.string.namereg_expires, dateText(e)))
            }
            if (s.owner != NameContracts.ZERO_ADDRESS) {
                if (payer != null && s.owner.equals(payer.address, ignoreCase = true)) {
                    MutedText(stringResource(R.string.namereg_owner_you))
                } else {
                    ReviewRow(stringResource(R.string.namereg_owner), null, address = s.owner)
                }
            }
            if (check.canRenew) check.priceWei?.let { MutedText(stringResource(R.string.namereg_renew_price, ethText(it), yearsText(check.duration))) }
        }
        if (payer == null && (check.canRegister || check.canRenew)) MutedText(stringResource(R.string.namereg_set_up_wallet))
        if (check.canRegister) {
            MutedText(stringResource(R.string.namereg_steps_note))
            Button(onClick = { onRegister(check) }, enabled = payer != null && !busy, modifier = Modifier.fillMaxWidth()) {
                if (busy) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp)) else Text(stringResource(R.string.namereg_register))
            }
        } else if (check.canRenew) {
            Button(onClick = { onRenew(check) }, enabled = payer != null && !busy, modifier = Modifier.fillMaxWidth()) {
                if (busy) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp)) else Text(stringResource(R.string.namereg_renew))
            }
        }
    }
}

@Composable
private fun OperationCard(
    op: NameOperation,
    account: WalletAccount?,
    nowSeconds: Long,
    busy: Boolean,
    onReview: () -> Unit,
    onCheckAgain: () -> Unit,
    onDismiss: () -> Unit,
) {
    val intent = stringResource(if (op.intent == Intent.REGISTER) R.string.namereg_intent_register else R.string.namereg_intent_renew)
    SectionCard(title = stringResource(R.string.namereg_operation_title, op.name, intent)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(operationStatus(op, nowSeconds), style = MaterialTheme.typography.bodyMedium)
            revealDeadline(op)?.let { MutedText(it) }
            MutedText(account?.let { stringResource(R.string.namereg_op_account, it.name, it.address) } ?: stringResource(R.string.namereg_op_account_missing))
            // Wraps at a large font rather than squeezing a label mid-word.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (op.state == State.READY) {
                    Button(onClick = onReview, enabled = !busy && account != null) { Text(stringResource(R.string.namereg_review_step)) }
                }
                if (op.state.waiting) {
                    OutlinedButton(onClick = onCheckAgain) { Text(stringResource(R.string.namereg_check_again)) }
                }
                if (op.state != State.DISPATCHING) {
                    TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.namereg_dismiss)) }
                }
            }
        }
    }
}

@Composable
private fun NameReviewCard(
    review: NameReview,
    op: NameOperation,
    quote: SendQuote,
    busy: Boolean,
    error: String?,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val request = quote.request
    val chain = request.chain
    val tap = rememberArmedTapGuard(quote, PromptTapGuard.SPEND_PROTECTION_MS)
    val guard = tap.guard
    val armed = tap.armed
    Column {
        SectionCard(title = review.headline) {
            for (row in review.rows) ReviewRow(row.label, row.value)
            if (op.intent == Intent.REGISTER) {
                ReviewRow(stringResource(R.string.namereg_review_owner_register), request.from.name, address = op.account)
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
            Spacer(Modifier.height(4.dp))
            MutedText(feeFootnote(quote.tx))
            DetailsExpander {
                ReviewRow(stringResource(R.string.namereg_review_contract), review.contract.displayName, address = request.to)
                ReviewRow(stringResource(R.string.namereg_review_nonce), quote.tx.nonce.toString(), detail = nonceDetail(quote))
            }
        }
        Spacer(Modifier.height(12.dp))
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        ObscuredTapNotice(tap)
        SheetButtonRow {
            OutlinedButton(onClick = onCancel, enabled = !busy) { Text(stringResource(R.string.namereg_cancel)) }
            Button(onClick = { if (guard.accepts()) onConfirm() }, enabled = armed && !busy, modifier = Modifier.protectedPress(tap)) {
                if (busy) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp)) else Text(stringResource(R.string.namereg_confirm))
            }
        }
    }
}

/** The wallet page's way in: one row opening [NameRegistrationScreen]. */
@Composable
fun NamesEntrySection(enabled: Boolean, onOpen: () -> Unit) {
    SectionCard(title = stringResource(R.string.namereg_section)) {
        PageRow(
            title = stringResource(R.string.namereg_entry),
            subtitle = stringResource(R.string.namereg_entry_subtitle),
            style = PageRowStyle.Inset,
            leadingIcon = Icons.Filled.Language,
            enabled = enabled,
            onClick = onOpen,
        )
    }
}
