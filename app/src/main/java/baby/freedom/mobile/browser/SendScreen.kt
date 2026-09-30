package baby.freedom.mobile.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import baby.freedom.mobile.R
import baby.freedom.mobile.chains.BuiltInChains
import baby.freedom.mobile.chains.Chain
import baby.freedom.mobile.l10n.pluralText
import baby.freedom.mobile.ui.isLight
import baby.freedom.mobile.ens.EnsAddressResult
import baby.freedom.mobile.ens.toHex
import baby.freedom.mobile.l10n.Strings
import baby.freedom.mobile.wallet.DappCall
import baby.freedom.mobile.wallet.EthTransaction
import baby.freedom.mobile.wallet.GasOracle
import baby.freedom.mobile.wallet.Recipients
import baby.freedom.mobile.wallet.SendAmounts
import baby.freedom.mobile.wallet.SendException
import baby.freedom.mobile.wallet.SendQuote
import baby.freedom.mobile.wallet.SendRequest
import baby.freedom.mobile.wallet.SendStatus
import baby.freedom.mobile.wallet.Token
import baby.freedom.mobile.wallet.TokenAmounts
import baby.freedom.mobile.wallet.TokenBalance
import baby.freedom.mobile.wallet.TokenRegistry
import baby.freedom.mobile.wallet.Vault
import baby.freedom.mobile.wallet.VaultAuthenticator
import baby.freedom.mobile.wallet.WalletAccount
import baby.freedom.mobile.wallet.WalletSender
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/** A fee in the native currency, rounded *up* to [maxFraction] digits — a fee must never read lower than it can be. */
internal fun feeText(wei: BigInteger, chain: Chain, maxFraction: Int = 8): String {
    if (wei.signum() == 0) return "0 ${chain.symbol}"
    val v = BigDecimal(wei, chain.decimals).setScale(maxFraction, RoundingMode.UP).stripTrailingZeros()
    return "${v.toPlainString()} ${chain.symbol}"
}

/** Wei per gas in gwei, up to 9 decimals, e.g. `1.000000028 gwei`. */
internal fun gweiText(wei: BigInteger): String =
    BigDecimal(wei, 9).stripTrailingZeros().toPlainString() + " gwei"

/** The review's fee sub-line: the gas limit and what one unit of gas can cost. */
internal fun feeDetail(tx: EthTransaction): String {
    val limit = "%,d".format(java.util.Locale.ROOT, tx.gasLimit)
    return when (val f = tx.fees) {
        is EthTransaction.Fees.Eip1559 ->
            Strings.get(R.string.send_fee_detail_eip1559, limit, gweiText(f.maxFeePerGas), gweiText(f.maxPriorityFeePerGas))
        is EthTransaction.Fees.Legacy -> Strings.get(R.string.send_fee_detail_legacy, limit, gweiText(f.gasPrice))
    }
}

/**
 * What of the review's "up to" fee is actually paid (#233): gas the
 * transaction doesn't use and, with a base fee, headroom the base fee
 * doesn't rise into stay in the account. The tip is paid on each unit
 * of gas used, but only as far as the cap leaves room above the base
 * fee (R1-M2); a legacy gas price is paid in full. A fee above what an
 * auto-approve rule sends without asking ([GasOracle.quiet]) — a legacy
 * price no cap could clamp, say — is called out as unusually high.
 */
internal fun feeFootnote(tx: EthTransaction): String {
    val paid = when (tx.fees) {
        is EthTransaction.Fees.Eip1559 -> Strings.get(R.string.send_fee_footnote_eip1559)
        is EthTransaction.Fees.Legacy -> Strings.get(R.string.send_fee_footnote_legacy)
    }
    return if (GasOracle.quiet(tx.fees, tx.chainId)) {
        paid
    } else {
        Strings.get(R.string.send_fee_footnote_high, paid)
    }
}

/** The line a [SendStatus] shows under its heading. */
internal fun sendStatusText(status: SendStatus): Pair<String, String> {
    val chain = status.quote.request.chain
    return when (val s = status.stage) {
        SendStatus.Stage.Signing -> status.quote.request.from.ledger?.let {
            Strings.get(R.string.send_status_ledger_title) to Strings.get(R.string.send_status_ledger_text, it.deviceName)
        } ?: (Strings.get(R.string.send_status_signing_title) to Strings.get(R.string.send_status_signing_text))
        SendStatus.Stage.Broadcasting ->
            Strings.get(R.string.send_status_broadcasting_title) to Strings.get(R.string.send_status_broadcasting_text, chain.name)
        SendStatus.Stage.Pending -> Strings.get(R.string.send_status_pending_title) to Strings.get(R.string.send_status_pending_text)
        is SendStatus.Stage.Confirmed -> {
            val block = "%,d".format(java.util.Locale.ROOT, s.block)
            Strings.get(R.string.send_status_confirmed_title) to (
                s.feePaid?.let { Strings.get(R.string.send_status_confirmed_text_fee, block, feeText(it, chain)) }
                    ?: Strings.get(R.string.send_status_confirmed_text, block)
                )
        }
        is SendStatus.Stage.Reverted -> {
            val block = "%,d".format(java.util.Locale.ROOT, s.block)
            val contract = status.quote.request.dapp != null
            Strings.get(R.string.send_status_reverted_title) to (
                s.feePaid?.let {
                    Strings.get(
                        if (contract) R.string.send_status_reverted_contract_fee else R.string.send_status_reverted_transfer_fee,
                        block,
                        feeText(it, chain),
                    )
                } ?: Strings.get(if (contract) R.string.send_status_reverted_contract else R.string.send_status_reverted_transfer, block)
                )
        }
        SendStatus.Stage.Unconfirmed -> {
            val minutes = (WalletSender.CONFIRM_TIMEOUT_MS / 60_000).toInt()
            Strings.get(R.string.send_status_unconfirmed_title) to Strings.plural(R.plurals.send_status_unconfirmed_text, minutes, minutes)
        }
        is SendStatus.Stage.Failed -> Strings.get(
            if (s.mayHaveGone) R.string.send_status_not_confirmed_title else R.string.send_status_not_sent_title,
        ) to s.message
    }
}

/**
 * A name typed as the recipient (#277) and its answer for chain
 * [chainId]; `null` [result] while it's being looked up.
 */
private data class NameLookup(val name: String, val chainId: Long, val result: EnsAddressResult?)

/** How long the recipient field must stay still before a name in it is looked up. */
private const val NAME_LOOKUP_DEBOUNCE_MS = 400L

/**
 * Where the form's values came from, when a payment link filled it in
 * (#317): the site whose link it was, in full (never shortened — its tail
 * is what a spoof hides), and a network the link didn't name.
 */
@Composable
private fun SendLinkNote(prefill: SendPrefill) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("send-link-note")) {
        Text(
            prefill.origin?.let { stringResource(R.string.send_link_filled_from_site, permissionOriginDisplay(it)) }
                ?: stringResource(R.string.send_link_filled),
            style = MaterialTheme.typography.bodyMedium,
        )
        prefill.chainGuess?.let { guess ->
            Text(
                when (guess) {
                    ChainGuess.ETHEREUM_DEFAULT -> stringResource(R.string.send_link_chain_assumed)
                    ChainGuess.ONLY_CHAIN_WITH_TOKEN -> stringResource(
                        R.string.send_link_chain_from_token,
                        prefillChainName(prefill),
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The network [prefill]'s asset is on, by name (its chain ID when Freedom has no name for it). */
internal fun prefillChainName(prefill: SendPrefill): String {
    val id = prefill.tokenKey.substringBefore(':')
    return BuiltInChains.ALL.firstOrNull { it.id.toString() == id }?.name ?: id
}

/** The block explorer's page for [hash], or null when the chain has no explorer. */
internal fun explorerTxUrl(chain: Chain, hash: String): String? =
    chain.explorerUrl?.trimEnd('/')?.let { "$it/tx/$hash" }

/**
 * Send (#105): the active account sends a native currency or a known
 * ERC-20 on one of the wallet's chains. Pick the asset, type the
 * address and amount, review what will be signed — the fee is priced
 * and the nonce read at that moment — and confirm. The confirmed send
 * runs in [WalletSender], so leaving this page doesn't stop it; coming
 * back shows where it got to.
 */
@Composable
internal fun SendPage(
    account: WalletAccount,
    chains: List<Chain>,
    balances: Map<String, TokenBalance>,
    vault: Vault,
    auth: VaultAuthenticator,
    phraseBackedUp: Boolean,
    onOpenUrl: (String) -> Unit,
    onBack: () -> Unit,
    // A payment link's asset, recipient and amount (#317): only what the
    // form starts with, every field still the user's to change.
    prefill: SendPrefill? = null,
    // A send from this page has started (#317: a link's Send page was used).
    onStarted: () -> Unit = {},
) {
    val context = LocalContext.current
    val sender = remember(context) { WalletSender.get(context) }
    val status by sender.status.collectAsState()
    val scope = rememberCoroutineScope()
    val assets = remember(chains) {
        TokenRegistry.WALLET_CHAIN_IDS.mapNotNull { id -> chains.firstOrNull { it.id == id } }
            .flatMap { chain -> TokenRegistry.tokens(chain).map { chain to it } }
    }
    val prefilledAsset = prefill?.let { p -> assets.firstOrNull { it.second.key == p.tokenKey }?.second }
    var assetKey by remember { mutableStateOf(prefilledAsset?.key ?: assets.firstOrNull()?.second?.key) }
    val asset = assets.firstOrNull { it.second.key == assetKey } ?: assets.firstOrNull()
    var recipient by remember { mutableStateOf(prefill?.recipient.orEmpty()) }
    // In the link's asset's own decimals, every digit kept: a request's amount isn't rounded.
    var amount by remember {
        mutableStateOf(
            prefilledAsset?.let { token -> prefill.amount?.let { SendAmounts.exact(it, token.decimals) } }.orEmpty(),
        )
    }
    var all by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var quote by remember { mutableStateOf<SendQuote?>(null) }
    var lookup by remember { mutableStateOf<NameLookup?>(null) }
    var lookupAttempt by remember { mutableStateOf(0) }
    // Set by Try again, taken by the lookup it restarts (#277): a retry
    // asks the servers again past the cache, so a disagreement cached a
    // moment ago isn't simply shown again. Any other restart (an edit, a
    // chain switch) finds it already taken.
    var retryFresh by remember { mutableStateOf(false) }
    // The user's explicit OK for an answer only one server gave (#277);
    // any change to the name, the chain or the answer takes it back.
    var unverifiedAccepted by remember { mutableStateOf(false) }
    // The Confirm job while it looks the name up again before signing
    // (#277): leaving the review cancels it, so an abandoned quote is
    // never signed once the answer lands.
    var recheck by remember { mutableStateOf<Job?>(null) }
    // A failed re-check's own fresh answer for the name it puts back in
    // the field (#277): the lookup that refill restarts shows it rather
    // than asking again, past the cache, behind the review's error.
    var seededLookup by remember { mutableStateOf<NameLookup?>(null) }

    // A name in the field is looked up for the selected asset's chain
    // (#277). Here, at the page's top, not inside the list's item: an
    // item scrolled away would drop the lookup and start it over. Every
    // edit restarts it, so an answer is never shown for an older name.
    val fieldToken = asset?.second
    val fieldChain = asset?.first
    val parsedRecipient = fieldToken?.let { Recipients.parse(recipient, it, names = true) }
    val typedName = (parsedRecipient as? Recipients.Parsed.Name)?.name
    LaunchedEffect(typedName, fieldChain?.id, lookupAttempt) {
        unverifiedAccepted = false
        val fresh = retryFresh
        retryFresh = false
        val seeded = seededLookup
        seededLookup = null
        if (typedName == null || fieldChain == null) {
            lookup = null
            return@LaunchedEffect
        }
        if (seeded != null && seeded.name == typedName && seeded.chainId == fieldChain.id) {
            lookup = seeded
            return@LaunchedEffect
        }
        lookup = NameLookup(typedName, fieldChain.id, null)
        delay(NAME_LOOKUP_DEBOUNCE_MS)
        val result = try {
            Gateways.ensResolver.resolveAddress(typedName, fieldChain.id, fresh = fresh)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            EnsAddressResult.Error(typedName, "RESOLUTION_ERROR", e.message ?: e.javaClass.simpleName, retryable = true)
        }
        lookup = NameLookup(typedName, fieldChain.id, result)
    }
    // Only the answer for what's in the field now: never a frame of the last name's.
    val nameLookup = lookup?.takeIf { it.name == typedName && it.chainId == fieldChain?.id }
    val nameAnswer = nameLookup?.result as? EnsAddressResult.Ok
    val nameRecipient = if (nameAnswer != null && fieldToken != null) Recipients.resolved(nameAnswer.address, fieldToken) else null
    // What the send goes to: a typed address, or a name's address — one
    // only one server vouched for once the user has said so.
    val recipientAddress = when (parsedRecipient) {
        is Recipients.Parsed.Ok -> parsedRecipient.address
        is Recipients.Parsed.Name -> (nameRecipient as? Recipients.Parsed.Ok)?.address
            ?.takeIf { nameAnswer!!.trust.verified || unverifiedAccepted }
        else -> null
    }

    fun prepare(request: SendRequest, sendAll: Boolean, then: (SendQuote) -> Unit = { quote = it }) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                then(sender.prepare(request, sendAll))
            } catch (e: SendException) {
                error = e.message
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = Strings.get(R.string.send_prepare_failed, e.javaClass.simpleName)
            } finally {
                busy = false
            }
        }
    }

    // Back to the form from the review: whatever the Confirm was still
    // checking is dropped with the quote it was checking.
    fun leaveReview() {
        recheck?.cancel()
        recheck = null
        quote = null
        notice = null
        error = null
    }

    val back = {
        val current = status
        when {
            // A send still on its way, or one that may have gone out or got no
            // receipt (Try again / Keep waiting settles it, Stop tracking gives
            // it up), stays for the next visit; a settled one is done with.
            current != null -> {
                if (!current.unresolved) sender.acknowledge()
                onBack()
            }
            quote != null -> leaveReview()
            else -> onBack()
        }
    }
    BackHandler(onBack = back)

    FullScreenScaffold(title = stringResource(R.string.send_title), onDismiss = back) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            val current = status
            val q = quote
            when {
                current != null -> item("status") {
                    SendStatusSection(
                        status = current,
                        onOpenUrl = onOpenUrl,
                        onRetry = sender::retry,
                        onCheckAgain = sender::checkAgain,
                        onStopTracking = {
                            sender.discard()
                            onBack()
                        },
                        onReviewAgain = {
                            val request = current.quote.request
                            sender.acknowledge()
                            prepare(request, sendAll = false)
                        },
                        onDone = back,
                    )
                }
                q != null -> item("review") {
                    SendReviewSection(
                        quote = q,
                        recipientTrust = q.request.toName?.let { name ->
                            nameAnswer?.takeIf { it.name == name && it.address.equals(q.request.to, ignoreCase = true) }
                                ?.let { NameTrust(name, it.trust, q.request.to) }
                        },
                        busy = busy,
                        notice = notice,
                        error = error,
                        onEdit = ::leaveReview,
                        onConfirm = {
                            // Priced too long ago to trust its fee: price it again and let the user look.
                            val reprice = {
                                prepare(q.request, sendAll = false) { fresh ->
                                    quote = fresh
                                    notice = Strings.get(R.string.send_repriced_notice)
                                }
                            }
                            if (sender.isStale(q)) {
                                reprice()
                            } else {
                                busy = true
                                error = null
                                scope.launch {
                                    var stale = false
                                    try {
                                        // A Ledger account's key is on the Ledger: nothing to unlock here (#142).
                                        if (!q.request.from.isLedger && !vault.unlockedNow()) vault.unlock(auth)
                                        // A name is asked again, past every cache, right before
                                        // signing (#277): the address reviewed must still be its
                                        // answer, as well vouched for as when it was accepted.
                                        q.request.toName?.let { name ->
                                            val chainId = q.request.chain.id
                                            notice = Strings.get(R.string.send_rechecking_name, name)
                                            // Back/✕/Edit during the lookup cancels this job (leaveReview).
                                            recheck = coroutineContext.job
                                            val after = try {
                                                Gateways.ensResolver.resolveAddress(name, chainId, fresh = true)
                                            } catch (e: CancellationException) {
                                                throw e
                                            } catch (e: Exception) {
                                                EnsAddressResult.Error(name, "RESOLUTION_ERROR", e.message ?: e.javaClass.simpleName, retryable = true)
                                            } finally {
                                                if (recheck === coroutineContext.job) recheck = null
                                            }
                                            // Main thread throughout: nothing can cancel it past here, and
                                            // a review left without cancelling (none should be) isn't sent.
                                            coroutineContext.ensureActive()
                                            if (quote !== q) return@launch
                                            // The acceptance travels with the request, so a Review
                                            // again from a reopened page still has it.
                                            Recipients.recheck(name, q.request.to, after, q.request.toNameAccepted)?.let { problem ->
                                                // Back to the form, showing what the name says now —
                                                // with the name in it, even on a reopened page.
                                                val shown = NameLookup(name, chainId, after)
                                                if (typedName != name || fieldChain?.id != chainId) {
                                                    assets.firstOrNull { it.second.key == q.request.token.key }?.let { assetKey = it.second.key }
                                                    recipient = name
                                                    if (amount.isBlank()) amount = SendAmounts.exact(q.request.amount, q.request.token.decimals)
                                                    all = false
                                                    // The refill restarts the lookup: it takes this answer
                                                    // instead of replacing it with a new, cached one.
                                                    seededLookup = shown
                                                }
                                                lookup = shown
                                                unverifiedAccepted = false
                                                quote = null
                                                notice = null
                                                error = problem
                                                return@launch
                                            }
                                        }
                                        notice = null
                                        // submit checks the age again: the unlock prompt can have stood for minutes.
                                        when (sender.submit(q, WalletSender.signerFor(context, vault, q.request.from) { !sender.isStale(q) })) {
                                            WalletSender.Submit.STARTED -> {
                                                quote = null
                                                notice = null
                                                onStarted()
                                            }
                                            WalletSender.Submit.BUSY -> error = Strings.get(R.string.send_busy)
                                            WalletSender.Submit.STALE -> stale = true
                                        }
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        error = walletErrorMessage(e, Strings.get(R.string.wallet_action_unlock), phraseBackedUp)
                                    } finally {
                                        busy = false
                                    }
                                    if (stale) reprice()
                                }
                            }
                        },
                    )
                }
                else -> {
                    if (prefill != null) {
                        item("link") { SendLinkNote(prefill) }
                    }
                    item("from") {
                        SectionCard(title = stringResource(R.string.send_label_from)) {
                            Text(account.name, fontWeight = FontWeight.Medium)
                            AddressText(
                                account.address,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    item("asset") {
                        AssetPicker(assets, asset?.second, balances, enabled = !busy) {
                            assetKey = it.key
                            all = false
                            error = null
                        }
                    }
                    val token = fieldToken
                    val chain = fieldChain
                    item("to") {
                        val parsed = parsedRecipient
                        SectionCard(title = stringResource(R.string.send_label_to)) {
                            OutlinedTextField(
                                value = recipient,
                                onValueChange = {
                                    recipient = it.trim()
                                    error = null
                                },
                                enabled = !busy,
                                singleLine = true,
                                placeholder = { Text(stringResource(R.string.send_recipient_placeholder)) },
                                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                keyboardOptions = KeyboardOptions(
                                    capitalization = KeyboardCapitalization.None,
                                    autoCorrectEnabled = false,
                                    keyboardType = KeyboardType.Ascii,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            when {
                                recipient.isEmpty() -> Unit
                                parsed is Recipients.Parsed.Invalid -> FieldNote(parsed.reason, error = true)
                                parsed is Recipients.Parsed.Name && chain != null -> NameRecipientNote(
                                    name = parsed.name,
                                    chainName = chain.name,
                                    result = nameLookup?.result,
                                    recipient = nameRecipient,
                                    ownAddress = account.address,
                                    unverifiedAccepted = unverifiedAccepted,
                                    enabled = !busy,
                                    onAcceptUnverified = { unverifiedAccepted = it },
                                    onRetry = {
                                        retryFresh = true
                                        lookupAttempt++
                                    },
                                )
                                parsed is Recipients.Parsed.Ok && parsed.address.equals(account.address, ignoreCase = true) ->
                                    FieldNote(stringResource(R.string.send_own_address_note), error = false)
                            }
                        }
                    }
                    item("amount") {
                        val held = token?.let { (balances[it.key] as? TokenBalance.Known)?.raw }
                        val parsedAmount = token?.let { SendAmounts.parse(amount, it.decimals) }
                        SectionCard(title = stringResource(R.string.send_label_amount)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = amount,
                                    onValueChange = {
                                        amount = it.trim()
                                        all = false
                                        error = null
                                    },
                                    enabled = !busy,
                                    singleLine = true,
                                    placeholder = { Text(stringResource(R.string.send_amount_placeholder)) },
                                    suffix = { Text(token?.symbol.orEmpty()) },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(Modifier.width(8.dp))
                                TextButton(
                                    enabled = !busy && held != null && held.signum() > 0,
                                    onClick = {
                                        if (token != null && held != null) {
                                            amount = SendAmounts.exact(held, token.decimals)
                                            all = true
                                            error = null
                                        }
                                    },
                                ) { Text(stringResource(R.string.send_max)) }
                            }
                            if (token != null) {
                                when {
                                    amount.isNotEmpty() && parsedAmount == null -> FieldNote(
                                        pluralText(R.plurals.send_amount_invalid, token.decimals, token.decimals),
                                        error = true,
                                    )
                                    all && token.isNative -> FieldNote(
                                        stringResource(R.string.send_all_note),
                                        error = false,
                                    )
                                    held != null -> FieldNote(
                                        stringResource(R.string.send_balance_note, TokenAmounts.format(held, token.decimals), token.symbol),
                                        error = false,
                                    )
                                }
                            }
                        }
                    }
                    item("review") {
                        val to = recipientAddress
                        val raw = token?.let { SendAmounts.parse(amount, it.decimals) }
                        Column {
                            error?.let {
                                FieldNote(it, error = true)
                                Spacer(Modifier.height(8.dp))
                            }
                            Button(
                                enabled = !busy && chain != null && token != null && to != null && raw != null,
                                onClick = {
                                    if (chain != null && token != null && to != null && raw != null) {
                                        prepare(
                                            SendRequest(
                                                chain, token, account, to, raw,
                                                toName = typedName,
                                                toNameAccepted = typedName != null && nameAnswer?.trust?.verified == false && unverifiedAccepted,
                                            ),
                                            all,
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                if (busy) {
                                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.send_pricing))
                                } else {
                                    Text(stringResource(R.string.send_review_button))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Every asset the wallet knows, grouped by chain, each with its balance: pick one. */
@Composable
private fun AssetPicker(
    assets: List<Pair<Chain, Token>>,
    selected: Token?,
    balances: Map<String, TokenBalance>,
    enabled: Boolean,
    onPick: (Token) -> Unit,
) {
    SectionCard(title = stringResource(R.string.send_label_asset)) {
        var lastChain: Long? = null
        assets.forEach { (chain, token) ->
            if (chain.id != lastChain) {
                if (lastChain != null) Spacer(Modifier.height(6.dp))
                Text(chain.name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                lastChain = chain.id
            }
            val text = balanceText(balances[token.key], token.decimals, refreshing = false)
            val isSelected = token.key == selected?.key
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = isSelected, enabled = enabled, role = Role.RadioButton, onClick = { onPick(token) })
                    .padding(vertical = 2.dp),
            ) {
                RadioButton(selected = isSelected, onClick = null, enabled = enabled)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(token.symbol, fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal)
                    Text(
                        text.amount?.let { "$it ${token.symbol}" } ?: text.detail,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * What will be signed, before anything is: network, from, to, amount,
 * the most the fee can be and the nonce. Confirm ignores taps for the
 * first [PromptTapGuard.SPEND_PROTECTION_MS] the review is on screen, so
 * the tap that opened it can't also confirm it, and drops a press begun
 * before then or one another app's window covered; other apps' overlays
 * are hidden while it's up (#240, [protectedPress]).
 */
@Composable
private fun SendReviewSection(
    quote: SendQuote,
    /** How the name the send goes to was checked (#277), when the form's answer is the one reviewed. */
    recipientTrust: NameTrust?,
    busy: Boolean,
    notice: String?,
    error: String?,
    onEdit: () -> Unit,
    onConfirm: () -> Unit,
) {
    val request = quote.request
    val chain = request.chain
    val token = request.token
    val tap = rememberArmedTapGuard(quote, PromptTapGuard.SPEND_PROTECTION_MS)
    val guard = tap.guard
    val armed = tap.armed
    SectionCard(title = stringResource(R.string.send_review_title)) {
        ReviewRow(stringResource(R.string.send_label_network), chain.name)
        ReviewRow(stringResource(R.string.send_label_asset), token.symbol, address = token.address)
        ReviewRow(stringResource(R.string.send_label_from), request.from.name, address = request.from.address)
        val recheckNote = stringResource(R.string.send_name_recheck_note)
        ReviewRow(
            stringResource(R.string.send_label_to),
            request.toName,
            address = request.to,
            detail = recipientTrust?.let { stringResource(R.string.send_name_trust_detail, it.tier.title, it.recipientSummary) }
                ?: request.toName?.let { recheckNote },
        )
        if (request.to.equals(request.from.address, ignoreCase = true)) {
            FieldNote(stringResource(R.string.send_self_send_note), error = false)
        }
        ReviewRow(stringResource(R.string.send_label_amount), "${SendAmounts.exact(request.amount, token.decimals)} ${token.symbol}", mono = true)
        ReviewRow(
            stringResource(R.string.send_label_network_fee),
            stringResource(R.string.send_up_to, feeText(quote.tx.maxFee, chain)),
            mono = true,
            detail = feeDetail(quote.tx),
        )
        quote.nativeTotal?.let {
            ReviewRow(stringResource(R.string.send_label_total), stringResource(R.string.send_up_to, feeText(it, chain)), mono = true)
        }
        ReviewRow(
            stringResource(R.string.send_label_nonce),
            quote.tx.nonce.toString(),
            detail = nonceDetail(quote),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            feeFootnote(quote.tx),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(12.dp))
    notice?.let {
        FieldNote(it, error = false)
        Spacer(Modifier.height(8.dp))
    }
    error?.let {
        FieldNote(it, error = true)
        Spacer(Modifier.height(8.dp))
    }
    ObscuredTapNotice(tap)
    SheetButtonRow {
        OutlinedButton(onClick = onEdit, enabled = !busy) { Text(stringResource(R.string.common_edit)) }
        Button(
            onClick = { if (guard.accepts()) onConfirm() },
            enabled = armed && !busy,
            modifier = Modifier.protectedPress(tap),
        ) {
            if (busy) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            } else {
                Text(stringResource(if (request.from.isLedger) R.string.send_confirm_on_ledger else R.string.send_confirm_and_send))
            }
        }
    }
}

/** A labelled value on its own line (it's never cut), with an optional address and a muted sub-line. */
@Composable
internal fun ReviewRow(label: String, value: String?, mono: Boolean = false, address: String? = null, detail: String? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer {
            Column {
                value?.let { Text(it, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default) }
                address?.let { AddressText(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) }
            }
        }
        detail?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    HorizontalDivider()
}

/** Where a confirmed send is, with what can be done next. */
@Composable
internal fun SendStatusSection(
    status: SendStatus,
    onOpenUrl: (String) -> Unit,
    onRetry: () -> Unit,
    onCheckAgain: () -> Unit,
    onStopTracking: () -> Unit,
    onReviewAgain: () -> Unit,
    onDone: () -> Unit,
) {
    var confirmStop by remember { mutableStateOf(false) }
    val (title, text) = sendStatusText(status)
    val request = status.quote.request
    val green = if (MaterialTheme.colorScheme.isLight) Color(0xFF15803D) else Color(0xFF22C55E)
    val amber = if (MaterialTheme.colorScheme.isLight) Color(0xFFB45309) else Color(0xFFF59E0B)
    SectionCard(title = stringResource(R.string.send_title)) {
        Row(verticalAlignment = Alignment.Top) {
            when (val stage = status.stage) {
                SendStatus.Stage.Signing, SendStatus.Stage.Broadcasting, SendStatus.Stage.Pending ->
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                is SendStatus.Stage.Confirmed ->
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = green, modifier = Modifier.size(22.dp))
                SendStatus.Stage.Unconfirmed ->
                    Icon(Icons.Filled.Schedule, contentDescription = null, tint = amber, modifier = Modifier.size(22.dp))
                is SendStatus.Stage.Failed, is SendStatus.Stage.Reverted -> Icon(
                    Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = if ((stage as? SendStatus.Stage.Failed)?.mayHaveGone == true) amber else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                SelectionContainer { Text(text, style = MaterialTheme.typography.bodyMedium) }
            }
        }
        Spacer(Modifier.height(8.dp))
        // A site's transaction (#110): who asked for it, whatever it carries.
        request.dapp?.let { ReviewRow(stringResource(R.string.send_label_requested_by), dappRequester(it)) }
        ReviewRow(
            stringResource(R.string.send_label_amount),
            stringResource(
                R.string.send_amount_on_chain,
                SendAmounts.exact(request.amount, request.token.decimals),
                request.token.symbol,
                request.chain.name,
            ),
            mono = true,
        )
        ReviewRow(
            stringResource(if (request.dapp != null) R.string.send_label_contract else R.string.send_label_to),
            request.toName,
            address = request.to,
        )
        // One desktop Freedom composed (#113): what it calls is part of what was sent.
        request.dapp?.takeIf { it.origin == null && it.safe == null && it.swarm == null }?.let {
            HexRow(
                stringResource(R.string.send_label_data),
                "0x" + it.data.toHex(),
                selector = true,
                detail = stringResource(R.string.send_data_scanned_detail),
            )
        }
        status.hash?.let { hash ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(stringResource(R.string.send_label_transaction), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SelectionContainer {
                    Text(hash, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    val stage = status.stage
    val explorer = status.hash?.let { explorerTxUrl(request.chain, it) }
        ?.takeIf { stage != SendStatus.Stage.Signing && (stage !is SendStatus.Stage.Failed || stage.mayHaveGone) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            stage is SendStatus.Stage.Failed && stage.mayHaveGone -> Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.common_try_again))
            }
            // A transaction a site or desktop Freedom composed is theirs to ask for again, not this page's.
            stage is SendStatus.Stage.Failed && request.dapp == null -> Button(onClick = onReviewAgain, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.send_review_again))
            }
            stage == SendStatus.Stage.Unconfirmed -> Button(onClick = onCheckAgain, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.send_keep_waiting))
            }
        }
        explorer?.let { url ->
            OutlinedButton(onClick = { onOpenUrl(url) }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    android.net.Uri.parse(url).host?.let { stringResource(R.string.send_view_on, it) }
                        ?: stringResource(R.string.send_view_on_explorer),
                )
            }
        }
        if (stage != SendStatus.Stage.Signing && stage != SendStatus.Stage.Broadcasting) {
            TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(
                        when {
                            stage == SendStatus.Stage.Pending -> R.string.send_close_keeps_going
                            status.mayHaveGone -> R.string.send_close_try_again_stays
                            stage == SendStatus.Stage.Unconfirmed -> R.string.send_close_stays
                            else -> R.string.common_done
                        },
                    ),
                )
            }
        }
        // Giving up on one that may still land is its own, confirmed step.
        if (status.mayHaveGone || stage == SendStatus.Stage.Unconfirmed) {
            TextButton(onClick = { confirmStop = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.send_stop_tracking_ellipsis), color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text(stringResource(R.string.send_stop_tracking_title)) },
            text = { Text(stopTrackingText(status)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmStop = false
                    onStopTracking()
                }) { Text(stringResource(R.string.send_stop_tracking), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text(stringResource(R.string.send_keep_it)) } },
        )
    }
}

/** What giving up on an unresolved send means, for the confirmation. */
internal fun stopTrackingText(status: SendStatus): String {
    val r = status.quote.request
    return Strings.get(
        R.string.send_stop_tracking_text,
        SendAmounts.exact(r.amount, r.token.decimals),
        r.token.symbol,
        r.from.name,
        r.chain.name,
        status.quote.tx.nonce.toString(),
    )
}

/**
 * What a name typed as the recipient (#277) comes to: looking it up;
 * the address, with the trust shield's tier and what it means; why
 * there's nothing to send to; or a failure to retry. An address only one
 * server vouched for needs the user's explicit OK ([onAcceptUnverified])
 * before it can be reviewed; servers that disagree block the send.
 */
@Composable
private fun NameRecipientNote(
    name: String,
    chainName: String,
    result: EnsAddressResult?,
    recipient: Recipients.Parsed?,
    ownAddress: String,
    unverifiedAccepted: Boolean,
    enabled: Boolean,
    onAcceptUnverified: (Boolean) -> Unit,
    onRetry: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when {
            result == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.send_looking_up_name, name, chainName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            result is EnsAddressResult.Ok -> {
                val trust = NameTrust(name, result.trust, result.address)
                SelectionContainer {
                    AddressText(
                        (recipient as? Recipients.Parsed.Ok)?.address ?: result.address,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(trust.tier.icon, contentDescription = null, tint = trust.tier.color, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        trust.tier.title,
                        style = MaterialTheme.typography.labelLarge,
                        color = trust.tier.color,
                    )
                }
                Text(
                    trust.recipientSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when {
                    recipient is Recipients.Parsed.Invalid -> FieldNote(recipient.reason, error = true)
                    !result.trust.verified -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().selectable(
                            selected = unverifiedAccepted,
                            enabled = enabled,
                            role = Role.Checkbox,
                            onClick = { onAcceptUnverified(!unverifiedAccepted) },
                        ),
                    ) {
                        Checkbox(checked = unverifiedAccepted, onCheckedChange = null, enabled = enabled)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.send_accept_unverified),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    result.address.equals(ownAddress, ignoreCase = true) ->
                        FieldNote(stringResource(R.string.send_own_address_note), error = false)
                }
            }
            else -> {
                FieldNote(Recipients.lookupProblem(result, chainName).orEmpty(), error = true)
                if (Recipients.retryable(result)) {
                    TextButton(onClick = onRetry, enabled = enabled) { Text(stringResource(R.string.common_try_again)) }
                }
            }
        }
    }
}

@Composable
private fun FieldNote(text: String, error: Boolean) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/** The wallet page's way into Send, saying where the current send is if there is one. */
@Composable
internal fun SendEntrySection(status: SendStatus?, enabled: Boolean, onOpen: () -> Unit) {
    SectionCard(title = stringResource(R.string.send_title)) {
        PageRow(
            title = if (status == null) stringResource(R.string.send_title) else sendStatusText(status).first,
            subtitle = status?.let {
                val r = it.quote.request
                val what = stringResource(R.string.send_amount_on_chain, SendAmounts.exact(r.amount, r.token.decimals), r.token.symbol, r.chain.name)
                r.dapp?.let { d -> stringResource(R.string.send_entry_for, what, dappRequester(d)) } ?: what
            } ?: stringResource(R.string.send_entry_subtitle),
            style = PageRowStyle.Inset,
            leadingIcon = Icons.AutoMirrored.Filled.Send,
            enabled = enabled,
            onClick = onOpen,
        )
    }
}

/**
 * Who asked for a composed transaction: the site, the wallet's own Safe
 * account (#141) it activates or executes for, the Swarm node it funds
 * (#115), or — for desktop Freedom's (#113) — the code that was scanned.
 */
internal fun dappRequester(d: DappCall): String = d.origin?.let(::permissionOriginDisplay)
    ?: d.safe?.let {
        Strings.get(if (it.activates) R.string.send_requester_safe_activation else R.string.send_requester_safe_owners, it.name)
    }
    ?: d.swarm?.let { Strings.get(R.string.send_requester_swarm_node) }
    ?: Strings.get(R.string.send_requester_pairing_code)
