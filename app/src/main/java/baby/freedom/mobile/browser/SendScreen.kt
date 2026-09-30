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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import baby.freedom.mobile.chains.Chain
import baby.freedom.mobile.ui.isLight
import baby.freedom.mobile.ens.EnsAddressResult
import baby.freedom.mobile.ens.toHex
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

internal const val SEND_TITLE = "Send"

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
        is EthTransaction.Fees.Eip1559 -> "Gas limit $limit · up to ${gweiText(f.maxFeePerGas)} per gas, tip ${gweiText(f.maxPriorityFeePerGas)}"
        is EthTransaction.Fees.Legacy -> "Gas limit $limit · ${gweiText(f.gasPrice)} per gas"
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
        is EthTransaction.Fees.Eip1559 ->
            "The “up to” leaves room for unused gas and for the base fee to rise; what isn't used stays in the " +
                "account. The tip is paid on the gas used, less only if the base fee rises into that room."
        is EthTransaction.Fees.Legacy ->
            "The “up to” leaves room for unused gas, which stays in the account; the gas used is paid at the price above in full."
    }
    return if (GasOracle.quiet(tx.fees, tx.chainId)) {
        paid
    } else {
        "This network fee is unusually high per gas; check it before you confirm. $paid"
    }
}

/** The line a [SendStatus] shows under its heading. */
internal fun sendStatusText(status: SendStatus): Pair<String, String> {
    val chain = status.quote.request.chain
    return when (val s = status.stage) {
        SendStatus.Stage.Signing -> status.quote.request.from.ledger?.let {
            "Confirm on your Ledger…" to "Check the transaction on ${it.deviceName}’s screen and approve it there."
        } ?: ("Signing…" to "With this account’s key, on this phone.")
        SendStatus.Stage.Broadcasting -> "Sending…" to "Handing the signed transaction to ${chain.name}’s RPCs."
        SendStatus.Stage.Pending -> "Waiting to be mined" to "Sent. It usually takes a block or two."
        is SendStatus.Stage.Confirmed -> "Sent" to "Mined in block ${"%,d".format(java.util.Locale.ROOT, s.block)}" +
            (s.feePaid?.let { " · fee ${feeText(it, chain)}" } ?: "")
        is SendStatus.Stage.Reverted -> "Failed on chain" to "Mined in block ${"%,d".format(java.util.Locale.ROOT, s.block)}, " +
            (if (status.quote.request.dapp != null) "but the contract refused the transaction, so it changed nothing. The network fee"
            else "but the transfer itself failed, so nothing arrived. The network fee") +
            (s.feePaid?.let { " (${feeText(it, chain)})" } ?: "") + " was still paid."
        SendStatus.Stage.Unconfirmed -> "Not mined yet" to "No receipt after ${WalletSender.CONFIRM_TIMEOUT_MS / 60_000} minutes. " +
            "It may still go through; the explorer shows where it stands."
        is SendStatus.Stage.Failed -> (if (s.mayHaveGone) "Not confirmed" else "Not sent") to s.message
    }
}

/**
 * A name typed as the recipient (#277) and its answer for chain
 * [chainId]; `null` [result] while it's being looked up.
 */
private data class NameLookup(val name: String, val chainId: Long, val result: EnsAddressResult?)

/** How long the recipient field must stay still before a name in it is looked up. */
private const val NAME_LOOKUP_DEBOUNCE_MS = 400L

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
) {
    val context = LocalContext.current
    val sender = remember(context) { WalletSender.get(context) }
    val status by sender.status.collectAsState()
    val scope = rememberCoroutineScope()
    val assets = remember(chains) {
        TokenRegistry.WALLET_CHAIN_IDS.mapNotNull { id -> chains.firstOrNull { it.id == id } }
            .flatMap { chain -> TokenRegistry.tokens(chain).map { chain to it } }
    }
    var assetKey by remember { mutableStateOf(assets.firstOrNull()?.second?.key) }
    val asset = assets.firstOrNull { it.second.key == assetKey } ?: assets.firstOrNull()
    var recipient by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var all by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var quote by remember { mutableStateOf<SendQuote?>(null) }
    var lookup by remember { mutableStateOf<NameLookup?>(null) }
    var lookupAttempt by remember { mutableStateOf(0) }
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
            Gateways.ensResolver.resolveAddress(typedName, fieldChain.id)
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
                error = "Couldn’t prepare the send: ${e.javaClass.simpleName}"
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

    FullScreenScaffold(title = SEND_TITLE, onDismiss = back) {
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
                                    notice = "The fee estimate was over a minute old, so it’s been priced again. " +
                                        "Check it and confirm."
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
                                            notice = "Looking up $name again before signing…"
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
                                            }
                                            WalletSender.Submit.BUSY -> error = "Another send is still going out, or may have. Settle it (or stop tracking it) first."
                                            WalletSender.Submit.STALE -> stale = true
                                        }
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        error = walletErrorMessage(e, "unlock the wallet", phraseBackedUp)
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
                    item("from") {
                        SectionCard(title = "From") {
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
                        SectionCard(title = "To") {
                            OutlinedTextField(
                                value = recipient,
                                onValueChange = {
                                    recipient = it.trim()
                                    error = null
                                },
                                enabled = !busy,
                                singleLine = true,
                                placeholder = { Text("0x… or name.eth") },
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
                                    onRetry = { lookupAttempt++ },
                                )
                                parsed is Recipients.Parsed.Ok && parsed.address.equals(account.address, ignoreCase = true) ->
                                    FieldNote("That’s this account’s own address: only the fee leaves it.", error = false)
                            }
                        }
                    }
                    item("amount") {
                        val held = token?.let { (balances[it.key] as? TokenBalance.Known)?.raw }
                        val parsedAmount = token?.let { SendAmounts.parse(amount, it.decimals) }
                        SectionCard(title = "Amount") {
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
                                    placeholder = { Text("0.0") },
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
                                ) { Text("Max") }
                            }
                            if (token != null) {
                                when {
                                    amount.isNotEmpty() && parsedAmount == null -> FieldNote(
                                        "Enter a positive amount with at most ${token.decimals} decimals",
                                        error = true,
                                    )
                                    all && token.isNative -> FieldNote(
                                        "All of it, less the network fee: the review shows the exact amount",
                                        error = false,
                                    )
                                    held != null -> FieldNote(
                                        "Balance ${TokenAmounts.format(held, token.decimals)} ${token.symbol}",
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
                                    Text("Pricing…")
                                } else {
                                    Text("Review")
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
    SectionCard(title = "Asset") {
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
    SectionCard(title = "Review") {
        ReviewRow("Network", chain.name)
        ReviewRow("Asset", token.symbol, address = token.address)
        ReviewRow("From", request.from.name, address = request.from.address)
        ReviewRow(
            "To",
            request.toName,
            address = request.to,
            detail = recipientTrust?.let { "${it.tier.title}. ${it.recipientSummary}" }
                ?: request.toName?.let { "The name is looked up again just before signing; the address, not the name, is signed." },
        )
        if (request.to.equals(request.from.address, ignoreCase = true)) {
            FieldNote("This is the sending account itself.", error = false)
        }
        ReviewRow("Amount", "${SendAmounts.exact(request.amount, token.decimals)} ${token.symbol}", mono = true)
        ReviewRow("Network fee", "up to ${feeText(quote.tx.maxFee, chain)}", mono = true, detail = feeDetail(quote.tx))
        quote.nativeTotal?.let { ReviewRow("Total", "up to ${feeText(it, chain)}", mono = true) }
        ReviewRow(
            "Nonce",
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
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = onEdit, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Edit") }
        Button(
            onClick = { if (guard.accepts()) onConfirm() },
            enabled = armed && !busy,
            modifier = Modifier.weight(1f).protectedPress(tap),
        ) {
            if (busy) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            } else {
                Text(if (request.from.isLedger) "Confirm on Ledger" else "Confirm and send")
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
    SectionCard(title = SEND_TITLE) {
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
        request.dapp?.let { ReviewRow("Requested by", dappRequester(it)) }
        ReviewRow("Amount", "${SendAmounts.exact(request.amount, request.token.decimals)} ${request.token.symbol} on ${request.chain.name}", mono = true)
        ReviewRow(if (request.dapp != null) "Contract" else "To", request.toName, address = request.to)
        // One desktop Freedom composed (#113): what it calls is part of what was sent.
        request.dapp?.takeIf { it.origin == null && it.safe == null && it.swarm == null }?.let { HexRow("Data", "0x" + it.data.toHex(), selector = true, detail = "Asked for over a scanned pairing code") }
        status.hash?.let { hash ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text("Transaction", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                Text("Try again")
            }
            // A transaction a site or desktop Freedom composed is theirs to ask for again, not this page's.
            stage is SendStatus.Stage.Failed && request.dapp == null -> Button(onClick = onReviewAgain, modifier = Modifier.fillMaxWidth()) {
                Text("Review again")
            }
            stage == SendStatus.Stage.Unconfirmed -> Button(onClick = onCheckAgain, modifier = Modifier.fillMaxWidth()) {
                Text("Keep waiting")
            }
        }
        explorer?.let { url ->
            OutlinedButton(onClick = { onOpenUrl(url) }, modifier = Modifier.fillMaxWidth()) {
                Text("View on ${android.net.Uri.parse(url).host ?: "the explorer"}")
            }
        }
        if (stage != SendStatus.Stage.Signing && stage != SendStatus.Stage.Broadcasting) {
            TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                Text(
                    when {
                        stage == SendStatus.Stage.Pending -> "Close (it keeps going)"
                        status.mayHaveGone -> "Close (Try again stays here)"
                        stage == SendStatus.Stage.Unconfirmed -> "Close (it stays here)"
                        else -> "Done"
                    },
                )
            }
        }
        // Giving up on one that may still land is its own, confirmed step.
        if (status.mayHaveGone || stage == SendStatus.Stage.Unconfirmed) {
            TextButton(onClick = { confirmStop = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Stop tracking it…", color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("Stop tracking this send?") },
            text = { Text(stopTrackingText(status)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmStop = false
                    onStopTracking()
                }) { Text("Stop tracking", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("Keep it") } },
        )
    }
}

/** What giving up on an unresolved send means, for the confirmation. */
internal fun stopTrackingText(status: SendStatus): String {
    val r = status.quote.request
    return "It may still go through: if it does, ${SendAmounts.exact(r.amount, r.token.decimals)} ${r.token.symbol} is paid. " +
        "Until it’s mined, the next send from ${r.from.name} on ${r.chain.name} reuses its nonce " +
        "(${status.quote.tx.nonce}) at a higher fee, so it takes this one’s place: only one of the two can go through."
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
                    "Looking up $name on $chainName…",
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
                            "I’ve checked this address with the recipient. Send to it anyway.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    result.address.equals(ownAddress, ignoreCase = true) ->
                        FieldNote("That’s this account’s own address: only the fee leaves it.", error = false)
                }
            }
            else -> {
                FieldNote(Recipients.lookupProblem(result, chainName).orEmpty(), error = true)
                if (Recipients.retryable(result)) {
                    TextButton(onClick = onRetry, enabled = enabled) { Text("Try again") }
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
    SectionCard(title = SEND_TITLE) {
        PageRow(
            title = if (status == null) "Send" else sendStatusText(status).first,
            subtitle = status?.let {
                val r = it.quote.request
                val what = "${SendAmounts.exact(r.amount, r.token.decimals)} ${r.token.symbol} on ${r.chain.name}"
                r.dapp?.let { d -> "$what, for ${dappRequester(d)}" } ?: what
            } ?: "Native currency or tokens, from this account",
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
    ?: d.safe?.let { if (it.activates) "Safe “${it.name}” (activation)" else "Safe “${it.name}” (its owners’ transaction)" }
    ?: d.swarm?.let { "your Swarm node (funding and a postage stamp)" }
    ?: "a scanned pairing code"
