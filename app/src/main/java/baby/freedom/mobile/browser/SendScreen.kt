package baby.freedom.mobile.browser

import android.os.SystemClock
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
import androidx.compose.runtime.withFrameNanos
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
import baby.freedom.mobile.wallet.EthTransaction
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
import kotlinx.coroutines.delay
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

/** The line a [SendStatus] shows under its heading. */
internal fun sendStatusText(status: SendStatus): Pair<String, String> {
    val chain = status.quote.request.chain
    return when (val s = status.stage) {
        SendStatus.Stage.Signing -> "Signing…" to "With this account’s key, on this phone."
        SendStatus.Stage.Broadcasting -> "Sending…" to "Handing the signed transaction to ${chain.name}’s RPCs."
        SendStatus.Stage.Pending -> "Waiting to be mined" to "Sent. It usually takes a block or two."
        is SendStatus.Stage.Confirmed -> "Sent" to "Mined in block ${"%,d".format(java.util.Locale.ROOT, s.block)}" +
            (s.feePaid?.let { " · fee ${feeText(it, chain)}" } ?: "")
        is SendStatus.Stage.Reverted -> "Failed on chain" to "Mined in block ${"%,d".format(java.util.Locale.ROOT, s.block)}, " +
            "but the transfer itself failed, so nothing arrived. The network fee" +
            (s.feePaid?.let { " (${feeText(it, chain)})" } ?: "") + " was still paid."
        SendStatus.Stage.Unconfirmed -> "Not mined yet" to "No receipt after ${WalletSender.CONFIRM_TIMEOUT_MS / 60_000} minutes. " +
            "It may still go through; the explorer shows where it stands."
        is SendStatus.Stage.Failed -> (if (s.mayHaveGone) "Not confirmed" else "Not sent") to s.message
    }
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
            quote != null -> {
                quote = null
                notice = null
                error = null
            }
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
                        busy = busy,
                        notice = notice,
                        error = error,
                        onEdit = {
                            quote = null
                            notice = null
                            error = null
                        },
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
                                        if (!vault.unlockedNow()) vault.unlock(auth)
                                        // submit checks the age again: the unlock prompt can have stood for minutes.
                                        when (sender.submit(q, WalletSender.vaultSigner(vault, q.request.from))) {
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
                    val token = asset?.second
                    val chain = asset?.first
                    item("to") {
                        val parsed = token?.let { Recipients.parse(recipient, it) }
                        SectionCard(title = "To") {
                            OutlinedTextField(
                                value = recipient,
                                onValueChange = {
                                    recipient = it.trim()
                                    error = null
                                },
                                enabled = !busy,
                                singleLine = true,
                                placeholder = { Text("0x…") },
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
                        val to = (token?.let { Recipients.parse(recipient, it) } as? Recipients.Parsed.Ok)?.address
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
                                        prepare(SendRequest(chain, token, account, to, raw), all)
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
 * first [PromptTapGuard.PROTECTION_MS] the review is on screen, so the
 * tap that opened it can't also confirm it.
 */
@Composable
private fun SendReviewSection(
    quote: SendQuote,
    busy: Boolean,
    notice: String?,
    error: String?,
    onEdit: () -> Unit,
    onConfirm: () -> Unit,
) {
    val request = quote.request
    val chain = request.chain
    val token = request.token
    val guard = remember(quote) { PromptTapGuard(SystemClock::uptimeMillis) }
    var armed by remember(quote) { mutableStateOf(false) }
    LaunchedEffect(quote) {
        withFrameNanos { }
        guard.onShown()
        delay(guard.remainingMs())
        armed = true
    }
    SectionCard(title = "Review") {
        ReviewRow("Network", chain.name)
        ReviewRow("Asset", token.symbol, address = token.address)
        ReviewRow("From", request.from.name, address = request.from.address)
        ReviewRow("To", null, address = request.to)
        if (request.to.equals(request.from.address, ignoreCase = true)) {
            FieldNote("This is the sending account itself.", error = false)
        }
        ReviewRow("Amount", "${SendAmounts.exact(request.amount, token.decimals)} ${token.symbol}", mono = true)
        ReviewRow("Network fee", "up to ${feeText(quote.tx.maxFee, chain)}", mono = true, detail = feeDetail(quote.tx))
        quote.nativeTotal?.let { ReviewRow("Total", "up to ${feeText(it, chain)}", mono = true) }
        ReviewRow(
            "Nonce",
            quote.tx.nonce.toString(),
            detail = quote.replaces?.let {
                "${trustLabel(quote.nonceTrust)} · replaces the send you stopped tracking ($it), at a higher fee: " +
                    "only one of the two can go through"
            } ?: trustLabel(quote.nonceTrust),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Only the fee the network actually charges is paid; the rest of the “up to” stays in the account.",
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
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = onEdit, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Edit") }
        Button(
            onClick = { if (guard.accepts()) onConfirm() },
            enabled = armed && !busy,
            modifier = Modifier.weight(1f),
        ) {
            if (busy) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            } else {
                Text("Confirm and send")
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
private fun SendStatusSection(
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
        ReviewRow("Amount", "${SendAmounts.exact(request.amount, request.token.decimals)} ${request.token.symbol} on ${request.chain.name}", mono = true)
        ReviewRow("To", null, address = request.to)
        // One desktop Freedom composed (#113): what it calls is part of what was sent.
        request.callData?.let { ReviewRow("Data", callDataText(it), mono = true, detail = "Asked for over a scanned pairing code") }
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
            // Desktop Freedom's own transaction was answered as failed there; only it can ask again.
            stage is SendStatus.Stage.Failed && request.callData == null -> Button(onClick = onReviewAgain, modifier = Modifier.fillMaxWidth()) {
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
                "${SendAmounts.exact(r.amount, r.token.decimals)} ${r.token.symbol} on ${r.chain.name}"
            } ?: "Native currency or tokens, from this account",
            style = PageRowStyle.Inset,
            leadingIcon = Icons.AutoMirrored.Filled.Send,
            enabled = enabled,
            onClick = onOpen,
        )
    }
}
