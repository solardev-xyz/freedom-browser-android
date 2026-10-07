package baby.freedom.mobile.browser

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import baby.freedom.mobile.R
import baby.freedom.mobile.chains.Chain
import baby.freedom.mobile.chains.rpc.ChainDataRouter
import baby.freedom.mobile.data.BrowsingRepository
import baby.freedom.mobile.l10n.Strings
import baby.freedom.mobile.l10n.pluralText
import baby.freedom.mobile.wallet.BiometricVaultAuthenticator
import baby.freedom.mobile.wallet.GasOracle
import baby.freedom.mobile.wallet.SendAmounts
import baby.freedom.mobile.wallet.SendQuote
import baby.freedom.mobile.wallet.Vault
import baby.freedom.mobile.wallet.VaultAuthFailedException
import baby.freedom.mobile.wallet.VaultKeyLostException
import baby.freedom.mobile.wallet.VaultLockedException
import baby.freedom.mobile.wallet.VaultUnreadableException
import baby.freedom.mobile.wallet.WalletAccount
import baby.freedom.mobile.wallet.WalletAccounts
import baby.freedom.mobile.wallet.ledger.LedgerTypedDataHashes
import java.net.URI
import java.text.NumberFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What an approval sheet says: its title, what the site wants, and the approve button's label. */
internal data class EthApprovalCopy(val title: String, val request: String, val approve: String)

internal fun ethApprovalCopy(ask: EthAsk): EthApprovalCopy {
    val (title, request, approve) = when (ask) {
        is EthAsk.Connect -> Triple(R.string.send_eth_connect_title, R.string.send_eth_connect_request, R.string.send_eth_connect_action)
        is EthAsk.SignMessage ->
            Triple(R.string.send_eth_sign_message_title, R.string.send_eth_sign_message_request, R.string.send_eth_sign_action)
        is EthAsk.SignTypedData ->
            Triple(R.string.send_eth_sign_data_title, R.string.send_eth_sign_data_request, R.string.send_eth_sign_action)
        is EthAsk.SendTransaction ->
            Triple(R.string.send_eth_send_title, R.string.send_eth_send_request, R.string.send_confirm_and_send)
        is EthAsk.CantSend -> Triple(R.string.send_eth_cant_send_title, R.string.send_eth_send_request, R.string.common_close)
        is EthAsk.SwitchChain -> Triple(R.string.send_eth_switch_title, R.string.send_eth_switch_request, R.string.send_eth_switch_action)
        is EthAsk.AddChain ->
            Triple(R.string.send_eth_add_chain_title, R.string.send_eth_add_chain_request, R.string.send_eth_add_chain_action)
        is EthAsk.Payment -> Triple(R.string.send_eth_pay_title, R.string.send_eth_pay_request, R.string.send_eth_pay_action)
        // Never a sheet: BrowserScreen opens the Send page for it instead.
        is EthAsk.SendLink -> Triple(R.string.send_title, R.string.send_eth_pay_request, R.string.send_eth_pay_action)
    }
    return EthApprovalCopy(Strings.get(title), Strings.get(request), Strings.get(approve))
}

/**
 * The notes a sheet shows for [ask], each at its level (#423, audit W24):
 * Danger for what can take funds later or for good (an unlimited or
 * long-lived permit or approval), Caution for what deserves a second look
 * (a repriced fee, a send that replaces another, data the wallet can't
 * read), Info for the rest — the add-network and switch notes included.
 * The add-network note follows the RPCs the user picked ([siteRpcs]).
 * [nowSeconds] is the wall clock, for a permit's deadline.
 */
internal fun sheetWarnings(ask: EthAsk, nowSeconds: Long, siteRpcs: Boolean = false): List<SheetWarning> = when (ask) {
    is EthAsk.Connect -> emptyList()
    is EthAsk.SignMessage -> listOfNotNull(
        if (ask.text == null) SheetWarning(WarningLevel.Caution, Strings.get(R.string.send_eth_message_hex), "message-hex") else null,
        SheetWarning(WarningLevel.Info, Strings.get(R.string.send_eth_message_note)),
    )
    is EthAsk.SignTypedData -> listOfNotNull(
        ask.permit?.let { permitWarning(it, nowSeconds) }
            ?: SheetWarning(WarningLevel.Caution, Strings.get(R.string.send_eth_typed_note), "typed-unknown"),
        ask.ledgerHashes?.let { SheetWarning(WarningLevel.Caution, Strings.get(R.string.send_eth_ledger_hashes_only), "ledger-hashes") },
    )
    is EthAsk.SendTransaction -> sendTxWarnings(ask.quote, ask.repriced)
    is EthAsk.CantSend -> listOf(SheetWarning(WarningLevel.Info, Strings.get(R.string.send_eth_cant_send_note)))
    is EthAsk.SwitchChain -> listOf(SheetWarning(WarningLevel.Info, Strings.get(R.string.send_eth_switch_note)))
    is EthAsk.AddChain -> listOf(
        SheetWarning(
            WarningLevel.Info,
            Strings.get(if (ask.checked != null && !siteRpcs) R.string.send_eth_add_chain_note_checked else R.string.send_eth_add_chain_note),
            "add-chain",
        ),
    )
    is EthAsk.Payment, is EthAsk.SendLink -> emptyList()
}

/**
 * The notes a transaction sheet shows for [quote], at their level: a
 * repriced fee ([repriced]), a send it replaces, a high fee, and what a
 * decoded call grants (an unlimited approval is Danger). The site's sheet
 * and desktop's over OpenLV (#434 R3-M1) show the same list, so a call
 * reads the same whoever asked for it.
 */
internal fun sendTxWarnings(quote: SendQuote, repriced: Boolean = false): List<SheetWarning> {
    val request = quote.request
    val chain = request.chain
    return listOfNotNull(
        if (repriced) SheetWarning(WarningLevel.Caution, Strings.get(R.string.send_eth_repriced), "repriced") else null,
        // A transaction can take the nonce of a send the user stopped tracking that's still
        // waiting in a pool — say so here as the Send page does, or confirming silently drops
        // that earlier send (#215 R6-F1).
        replacesWarning(quote),
        if (!GasOracle.quiet(quote.tx.fees, chain.id)) SheetWarning(WarningLevel.Caution, Strings.get(R.string.send_eth_high_fee), "high-fee") else null,
        TxDecode.call(request.to, quote.tx.data, chain.id)?.let(::callWarning),
    )
}

/**
 * The surface Caution for a [quote] that takes the nonce of a send the user
 * stopped tracking ([SendQuote.replaces]). Every sheet that can confirm such
 * a quote — the site's, a remote signer's (OpenLV), a Safe activation or
 * execution — shows it above the fold, never only inside Details: confirming
 * silently drops that earlier send (#215 R6-F1, #434 R2-F1).
 */
internal fun replacesWarning(quote: SendQuote): SheetWarning? =
    quote.replaces?.let { SheetWarning(WarningLevel.Caution, Strings.get(R.string.send_eth_replaces, it), "replaces") }

/**
 * The hero line of a site's transaction (W22): what a decoded call does
 * ("Send 20 USDC to 0xd8dA…6045"), a plain send of the native currency,
 * or which contract it calls — never "0 ETH" for a token transfer.
 */
internal fun sendTxHeadline(ask: EthAsk.SendTransaction): String = sendTxHeadline(ask.quote)

/** [sendTxHeadline] for any priced transaction: the site's sheet and desktop's over OpenLV (W40) say it alike. */
internal fun sendTxHeadline(quote: SendQuote): String {
    val request = quote.request
    val chain = request.chain
    TxDecode.call(request.to, quote.tx.data, chain.id)?.let { return callHeadline(it) }
    if (quote.tx.data.isEmpty()) {
        return sendHeadline(SendAmounts.exact(request.amount, chain.decimals), chain.symbol, request.to, null, chain.name)
    }
    val function = selectorLabel("0x" + hexOf(quote.tx.data, 4))
    return function?.let { Strings.get(R.string.send_eth_call_named_headline, it, shortAddress(request.to)) }
        ?: Strings.get(R.string.send_eth_call_headline, shortAddress(request.to), chain.name)
}

/**
 * "Also sends 1.5 ETH": the native value a contract call carries on top of
 * what its headline says, so it's named on the sheet's surface, not only
 * under Details; null for a plain transfer (the headline already names it)
 * or a call that sends no value. The site's sheet and desktop's over OpenLV
 * both show it.
 */
internal fun alsoSendsLine(quote: SendQuote): String? {
    val request = quote.request
    if (quote.tx.data.isEmpty() || request.amount.signum() <= 0) return null
    return Strings.get(R.string.send_eth_also_sends, SendAmounts.exact(request.amount, request.chain.decimals), request.chain.symbol)
}

/** The unlock error under a sheet (W20): what to do first, and the raw detail for "Show details"; null when there's nothing to say. */
internal fun unlockError(e: Throwable): Pair<String, String?>? = when (e) {
    is CancellationException -> null
    // These already say what happened and what to do about it.
    is VaultKeyLostException, is VaultUnreadableException, is VaultLockedException ->
        walletErrorMessage(e, Strings.get(R.string.wallet_action_unlock), phraseBackedUp = true)?.let { it to null }
    is VaultAuthFailedException -> Strings.get(R.string.send_eth_unlock_failed) to e.message
    else -> walletErrorMessage(e, Strings.get(R.string.wallet_action_unlock), phraseBackedUp = true)?.let {
        Strings.get(R.string.send_eth_unlock_failed) to (e.message?.let { m -> "${e.javaClass.simpleName}: $m" } ?: e.javaClass.simpleName)
    }
}

/**
 * A `window.ethereum` approval (#110): a bottom sheet that always starts
 * with the site asking — its favicon and host as the headline, in full,
 * wrapped rather than ellipsised, since the tail of a host is what a
 * spoof hides — then one plain sentence of what it asks (#423), the
 * notes that matter at their level, and everything raw (JSON, hex,
 * nonce, gas, contracts) under Details; then Reject / the action.
 *
 * Signing and sending need the wallet open: the action button asks for
 * the screen lock first when it isn't. A connect with no wallet on the
 * device offers to set one up (the wallet page, over the sheet).
 *
 * Like the site-permission prompt, the buttons, a swipe down, a tap
 * outside and Back all ignore input for the first
 * [PromptTapGuard.PROTECTION_MS] the sheet is on screen — for Sign, Send
 * and Pay [PromptTapGuard.SPEND_PROTECTION_MS] (#240) — counted from its
 * first drawn frame and started over by every touch before then, so a
 * page can't time its request to catch a tap meant for the page, nor keep
 * a "tap fast here" game going until the sheet arms. The action button
 * fills up while it arms (W30). The action also drops a press begun
 * before that, or one another app's window covered ([protectedPress]);
 * other apps' overlays are hidden while the sheet is up (Android 12+).
 * Everything but the action rejects.
 *
 * A send that can't go ([EthAsk.CantSend]) shows the same sheet with
 * the reason, Receive (when it's short of funds) and Close; closing it
 * answers the page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EthereumApprovalSheet(request: EthereumPromptRequest) {
    val ask = request.ask
    val copy = ethApprovalCopy(ask)
    val context = LocalContext.current
    val vault = remember(context) { Vault.get(context) }
    val walletAccounts = remember(context) { WalletAccounts.get(context) }
    val vaultState by vault.state.collectAsState()
    val accountList by walletAccounts.accounts.collectAsState()
    // Sign and Send can't be taken back: they arm later (#240).
    val spends = ask is EthAsk.SignMessage || ask is EthAsk.SignTypedData || ask is EthAsk.SendTransaction ||
        ask is EthAsk.Payment
    val protectionMs = if (spends) PromptTapGuard.SPEND_PROTECTION_MS else PromptTapGuard.PROTECTION_MS
    val tap = rememberArmedTapGuard(request, protectionMs)
    val guard = tap.guard
    val armed = tap.armed
    var busy by remember(request) { mutableStateOf(false) }
    var error by remember(request) { mutableStateOf<Pair<String, String?>?>(null) }
    var picked by remember(request) { mutableStateOf<String?>(null) }
    val payment = remember(request) { (ask as? EthAsk.Payment)?.let { X402SheetState(it.payment) } }
    // The auto-approve row (#112): off every time the sheet comes up.
    var always by remember(request) { mutableStateOf(false) }
    // Add network (W27): the RPCs from Freedom's own list unless the user picks the site's.
    var siteRpcs by remember(request) { mutableStateOf(false) }
    var receiving by remember(request) { mutableStateOf(false) }
    val nowSeconds = remember(request) { System.currentTimeMillis() / 1000 }
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || (guard.accepts() && !busy) },
    )

    val accounts = accountList?.accounts
    val connectAccount = accounts?.let { list ->
        list.firstOrNull { it.address == picked } ?: accountList?.active
    }
    // A Ledger account signs on the Ledger (#142): nothing on the phone to unlock.
    val ledger = ledgerOf(ask)
    val needsUnlock = ledger == null && (
        ask is EthAsk.SignMessage || ask is EthAsk.SignTypedData || ask is EthAsk.SendTransaction ||
            ask is EthAsk.Payment
        )
    val canApprove = when (ask) {
        is EthAsk.Connect -> connectAccount != null
        is EthAsk.Payment -> ask.payment.paysFrom(accountList?.active) && payment?.choice() != null
        else -> true
    }

    fun approve() {
        if (!guard.accepts() || busy) return
        // Fixed now: what the user saw when they tapped is what's paid.
        val choice = payment?.choice()
        if (ask is EthAsk.Payment && choice == null) return
        if (!needsUnlock) {
            // A Ledger account's payment too: it's signed on the Ledger, after the sheet.
            request.respond(
                EthAnswer.Approved(
                    if (ask is EthAsk.Connect) connectAccount else null,
                    payment = choice,
                    siteRpcs = ask is EthAsk.AddChain && ask.checked != null && siteRpcs,
                ),
            )
            return
        }
        busy = true
        error = null
        scope.launch {
            try {
                if (!vault.unlockedNow()) vault.unlock(BiometricVaultAuthenticator(context))
                request.respond(
                    EthAnswer.Approved(payment = choice, alwaysApprove = ask is EthAsk.SendTransaction && always),
                )
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
        onDismissRequest = {
            if (guard.accepts() && !busy) request.respond(if (ask is EthAsk.CantSend) EthAnswer.Closed else EthAnswer.Rejected) else guard.noteInput()
        },
        sheetState = sheetState,
        modifier = Modifier.testTag("ethereum-approval"),
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
            SiteHeader(ask.origin, copy.request)
            Spacer(Modifier.height(16.dp))
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
            ) {
                val warnings = sheetWarnings(ask, nowSeconds, siteRpcs)
                when (ask) {
                    is EthAsk.Connect -> ConnectBody(
                        ask = ask,
                        accounts = accounts,
                        selected = connectAccount,
                        noWallet = vaultState == Vault.State.Empty || accounts == null,
                        tap = tap,
                        enabled = !busy,
                        onPick = { picked = it.address },
                        onSetUp = request.setUpWallet,
                    )
                    is EthAsk.SignMessage -> SignMessageBody(ask, warnings)
                    is EthAsk.SignTypedData -> SignTypedDataBody(ask, warnings)
                    is EthAsk.SendTransaction -> SendBody(
                        ask,
                        warnings,
                        always,
                        tap,
                        enabled = !busy,
                        locked = vaultState is Vault.State.Locked,
                        onAlways = { always = it },
                    )
                    is EthAsk.CantSend -> CantSendBody(ask, warnings)
                    is EthAsk.SwitchChain -> SwitchBody(ask, warnings)
                    is EthAsk.AddChain -> AddChainBody(ask, warnings, siteRpcs, tap, enabled = !busy) { siteRpcs = it }
                    is EthAsk.Payment -> X402PaymentBody(
                        ask = ask.payment,
                        state = payment!!,
                        account = ask.payment.account,
                        noWallet = vaultState == Vault.State.Empty || accounts == null,
                        locked = vaultState is Vault.State.Locked,
                        onSetUp = request.setUpWallet,
                    )
                    is EthAsk.SendLink -> Unit
                }
            }
            ledger?.let {
                Spacer(Modifier.height(8.dp))
                Warning(
                    SheetWarning(WarningLevel.Info, ledgerNote(it.deviceName, hashesOnly = (ask as? EthAsk.SignTypedData)?.ledgerHashes != null)),
                    Modifier.testTag("ethereum-ledger-note"),
                )
            }
            error?.let { (message, detail) ->
                Spacer(Modifier.height(8.dp))
                ErrorWithDetails(message, detail, Modifier.testTag("ethereum-error"))
            }
            Spacer(Modifier.height(16.dp))
            ObscuredTapNotice(tap)
            if (ask is EthAsk.CantSend) {
                SheetButtonRow {
                    OutlinedButton(
                        onClick = { if (guard.accepts()) request.respond(EthAnswer.Closed) },
                        enabled = armed,
                        modifier = Modifier.testTag("ethereum-close"),
                    ) { Text(stringResource(R.string.common_close)) }
                    ask.shortOf?.let { symbol ->
                        Button(
                            onClick = { if (guard.accepts()) receiving = true },
                            enabled = armed,
                            modifier = Modifier.testTag("ethereum-receive"),
                        ) { Text(stringResource(R.string.send_eth_cant_send_receive, symbol)) }
                    }
                }
            } else {
                SheetButtonRow {
                    OutlinedButton(
                        onClick = { if (guard.accepts() && !busy) request.respond(EthAnswer.Rejected) },
                        enabled = armed && !busy,
                        modifier = Modifier.testTag("ethereum-reject"),
                    ) { Text(stringResource(R.string.common_reject)) }
                    ArmingButton(
                        tap = tap,
                        protectionMs = protectionMs,
                        onClick = ::approve,
                        enabled = !busy && canApprove,
                        busy = busy,
                        modifier = Modifier.protectedPress(tap).testTag("ethereum-approve"),
                    ) { Text(copy.approve) }
                }
            }
        }
    }
    if (receiving && ask is EthAsk.CantSend) {
        // The wallet's own Receive page, over the sheet: the address to fund, then back here to Close.
        Dialog(
            onDismissRequest = { receiving = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            ReceivePage(account = ask.account, onBack = { receiving = false })
        }
    }
}

/** The Ledger that signs what [ask] asks for, if its account is a Ledger's (#142). */
internal fun ledgerOf(ask: EthAsk): baby.freedom.mobile.wallet.ledger.LedgerKey? = when (ask) {
    is EthAsk.SignMessage -> ask.account.ledger
    is EthAsk.SignTypedData -> ask.account.ledger
    is EthAsk.SendTransaction -> ask.quote.request.from.ledger
    is EthAsk.Payment -> ask.payment.account?.ledger
    else -> null
}

/**
 * The site asking, as the sheet's headline (W29): its favicon and its host
 * in full — selectable, wrapped, never cut — then what it asks.
 */
@Composable
internal fun SiteHeader(origin: String, request: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().testTag("ethereum-origin")) {
        SiteFavicon(origin)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            SelectionContainer {
                Text(
                    permissionOriginDisplay(origin),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.semantics { heading() },
                )
            }
            Text(request, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (origin.startsWith("http://")) {
                Text(
                    stringResource(R.string.send_eth_loopback),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The favicon the browser cached for [origin], on a pale tile so a dark
 * mark stays legible; the host's first letter when there's none. A
 * picture of the name beside it, so TalkBack skips it.
 */
@Composable
private fun SiteFavicon(origin: String) {
    val context = LocalContext.current
    val page = VirtualOrigin.displayUrlFor(origin) ?: origin
    val bytes by remember(page) {
        runCatching { BrowsingRepository.get(context).favicon(page) }.getOrNull() ?: kotlinx.coroutines.flow.flowOf(null)
    }.collectAsState(initial = null)
    val bitmap = remember(bytes) {
        bytes?.let { b -> runCatching { BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap() }.getOrNull() }
    }
    Box(
        modifier = Modifier
            .clearAndSetSemantics {}
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (bitmap != null) Color.White else MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap = bitmap, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(28.dp))
        } else {
            val host = permissionOriginDisplay(origin).removePrefix("http://").removePrefix("https://")
            Text(
                host.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

/**
 * A sheet's note at its level (W24): Info as plain muted text with an
 * info icon; Caution as an amber card; Danger as an error card whose
 * first line is bold. Every word of it is in the card — nothing in a
 * tooltip, nothing cut.
 */
@Composable
internal fun Warning(warning: SheetWarning, modifier: Modifier = Modifier) {
    val tagged = modifier.then(warning.tag?.let { Modifier.testTag("warning-$it") } ?: Modifier)
    when (warning.level) {
        WarningLevel.Info -> Row(verticalAlignment = Alignment.Top, modifier = tagged.fillMaxWidth().padding(vertical = 4.dp)) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 1.dp).size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(warning.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        WarningLevel.Caution, WarningLevel.Danger -> {
            val danger = warning.level == WarningLevel.Danger
            val container = if (danger) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
            val content = if (danger) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
            val label = stringResource(if (danger) R.string.send_eth_warning_danger else R.string.send_eth_warning_caution)
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = container,
                contentColor = content,
                modifier = tagged.fillMaxWidth().padding(vertical = 4.dp),
            ) {
                Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(12.dp)) {
                    Icon(
                        if (danger) Icons.Filled.Error else Icons.Filled.Warning,
                        contentDescription = label,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        val first = warning.text.substringBefore('\n')
                        val rest = warning.text.substringAfter('\n', "")
                        Text(first, style = MaterialTheme.typography.bodyMedium, fontWeight = if (danger) FontWeight.Bold else FontWeight.Medium)
                        if (rest.isNotEmpty()) Text(rest, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/**
 * The sheet's primary button. While the tap guard arms it's disabled and
 * fills from the start edge, so the wait reads as "getting ready", not as
 * broken (W30); a touch that starts the guard over empties it again.
 * [busy] shows a spinner TalkBack reads as busy (W50).
 */
@Composable
internal fun ArmingButton(
    tap: ArmedTapGuard,
    protectionMs: Long,
    onClick: () -> Unit,
    enabled: Boolean,
    busy: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var progress by remember(tap) { mutableFloatStateOf(if (tap.armed) 1f else 0f) }
    LaunchedEffect(tap) {
        // A coarse tick, not every frame: the fill only has to read as filling, and a
        // per-frame redraw would keep the UI thread busy for the whole arming period.
        while (!tap.armed) {
            progress = (1f - tap.guard.remainingMs().toFloat() / protectionMs).coerceIn(0f, 1f)
            delay(ARMING_TICK_MS)
        }
        progress = 1f
    }
    val fill = MaterialTheme.colorScheme.primary.copy(alpha = 0.32f)
    val arming = stringResource(R.string.send_eth_arming)
    val working = stringResource(R.string.send_eth_working)
    Button(
        onClick = onClick,
        enabled = tap.armed && enabled,
        modifier = modifier
            .drawWithContent {
                drawContent()
                if (!tap.armed && enabled) {
                    // The pill as drawn: the node is taller (its 48 dp touch target) than the button.
                    val h = ButtonDefaults.MinHeight.toPx().coerceAtMost(size.height)
                    val top = (size.height - h) / 2
                    val pill = Path().apply { addRoundRect(RoundRect(0f, top, size.width, top + h, CornerRadius(h / 2))) }
                    clipPath(pill) { drawRect(fill, topLeft = Offset(0f, top), size = Size(size.width * progress, h)) }
                }
            }
            .semantics { if (!tap.armed) stateDescription = arming },
    ) {
        if (busy) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp).semantics { contentDescription = working },
            )
        } else {
            content()
        }
    }
}

/** How often the arming fill moves on. */
private const val ARMING_TICK_MS = 50L

/**
 * An error that says what to do first; the raw detail, if any, behind
 * "Show details" (W20). Announced when it appears (W50).
 */
@Composable
internal fun ErrorWithDetails(message: String, detail: String?, modifier: Modifier = Modifier) {
    var open by remember(message, detail) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        Text(
            message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (detail != null) {
            TextButton(onClick = { open = !open }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(if (open) R.string.send_eth_hide_details else R.string.send_eth_show_details))
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
}

/**
 * What a Connect shares, with the account picker. The rows are guarded
 * like the Connect button ([protectedSelectable]): a press before the
 * sheet armed, or one through another app's window, doesn't change the
 * account the site will see (#287 R4-M1). What the site can then do is
 * two bullets (W29), not a paragraph.
 */
@Composable
internal fun ConnectBody(
    ask: EthAsk.Connect,
    accounts: List<WalletAccount>?,
    selected: WalletAccount?,
    noWallet: Boolean,
    tap: ArmedTapGuard,
    enabled: Boolean,
    onPick: (WalletAccount) -> Unit,
    onSetUp: () -> Unit,
) {
    if (noWallet || accounts == null) {
        Text(stringResource(R.string.send_eth_no_wallet))
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onSetUp, modifier = Modifier.fillMaxWidth().testTag("ethereum-setup")) { Text(stringResource(R.string.send_eth_set_up_wallet)) }
        return
    }
    Text(
        stringResource(if (accounts.size > 1) R.string.send_eth_account_to_share else R.string.send_label_account),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp),
    )
    accounts.forEach { account ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .protectedSelectable(tap, selected = account == selected, enabled = enabled) { onPick(account) }
                .heightIn(min = 56.dp)
                .padding(vertical = 4.dp)
                .testTag("ethereum-connect-account"),
        ) {
            if (accounts.size > 1) RadioButton(selected = account == selected, onClick = null, enabled = tap.armed && enabled)
            Column(Modifier.padding(start = if (accounts.size > 1) 8.dp else 0.dp)) {
                Text(accountLabel(account), style = MaterialTheme.typography.bodyLarge)
                AddressText(account.address, MaterialTheme.typography.bodySmall, MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    Text(
        stringResource(R.string.send_eth_connect_lead, ask.chain.name),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.testTag("ethereum-connect-lead"),
    )
    Bullet(stringResource(R.string.send_eth_connect_bullet_see))
    Bullet(stringResource(R.string.send_eth_connect_bullet_ask))
}

@Composable
private fun Bullet(text: String) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text("•", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.clearAndSetSemantics {}.padding(end = 8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** A line under a summary's headline ("From Account 1 · on Gnosis"). */
@Composable
private fun SummarySub(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Warnings(warnings: List<SheetWarning>) {
    if (warnings.isEmpty()) return
    Spacer(Modifier.height(8.dp))
    warnings.forEach { Warning(it) }
}

@Composable
private fun SignMessageBody(ask: EthAsk.SignMessage, warnings: List<SheetWarning>) {
    TxReviewSummary(headline = stringResource(R.string.send_eth_sign_message_headline, accountLabel(ask.account)), fee = null)
    Spacer(Modifier.height(8.dp))
    if (ask.text != null) {
        Label(stringResource(R.string.send_eth_message))
        Block(ask.text, mono = false, whole = stringResource(R.string.send_eth_message_whole))
    }
    Warnings(warnings)
    DetailsExpander(Modifier.testTag("ethereum-details")) {
        AccountRow(ask.account)
        if (ask.text == null) {
            Label(stringResource(R.string.send_eth_message))
            Block(ask.hex, mono = true, whole = stringResource(R.string.send_eth_message_whole))
        }
    }
}

@Composable
private fun SignTypedDataBody(ask: EthAsk.SignTypedData, warnings: List<SheetWarning>) {
    val permit = ask.permit
    val network = if (ask.chainBound) ask.chain.name else stringResource(R.string.send_eth_any_network)
    TxReviewSummary(
        headline = permit?.let { permitHeadline(it) }
            ?: ask.domainName?.let { stringResource(R.string.send_eth_sign_data_headline_app, it) }
            ?: stringResource(R.string.send_eth_sign_data_headline),
        fee = null,
    ) {
        permit?.let { p -> permitLines(p).forEach { SummarySub(it) } }
        SummarySub(stringResource(R.string.send_eth_from_on, accountLabel(ask.account), network))
    }
    Warnings(warnings)
    DetailsExpander(Modifier.testTag("ethereum-details")) {
        if (ask.chainBound) {
            ChainRow(stringResource(R.string.send_label_network), ask.chain)
        } else {
            Row0(stringResource(R.string.send_label_network), stringResource(R.string.send_eth_any_chain))
        }
        AccountRow(ask.account)
        permit?.let { CopyableAddressRow(stringResource(R.string.send_eth_spender), it.spender) }
        permit?.grants?.forEach { CopyableAddressRow(stringResource(R.string.send_eth_token), it.token.address, name = it.token.symbol) }
        ask.domainName?.let { Row0(stringResource(R.string.send_eth_application), it) }
        ask.verifyingContract?.let { CopyableAddressRow(stringResource(R.string.send_label_contract), it) }
        Row0(stringResource(R.string.send_eth_type), ask.primaryType, mono = true)
        Label(stringResource(R.string.send_label_data))
        Block(ask.messageJson, mono = true, whole = stringResource(R.string.send_eth_data_whole))
    }
    ask.ledgerHashes?.let { LedgerHashRows(it) }
}

@Composable
private fun SendBody(
    ask: EthAsk.SendTransaction,
    warnings: List<SheetWarning>,
    always: Boolean,
    tap: ArmedTapGuard,
    enabled: Boolean,
    locked: Boolean,
    onAlways: (Boolean) -> Unit,
) {
    val quote = ask.quote
    val request = quote.request
    val chain = request.chain
    val data = quote.tx.data
    TxReviewSummary(
        headline = sendTxHeadline(ask),
        fee = stringResource(R.string.send_up_to, feeText(quote.maxFee, chain)),
        total = quote.nativeTotal?.takeIf { request.amount.signum() > 0 }?.let { stringResource(R.string.send_up_to, feeText(it, chain)) },
    ) {
        SummarySub(stringResource(R.string.send_eth_from_on, accountLabel(request.from), chain.name))
        alsoSendsLine(quote)?.let { SummarySub(it) }
    }
    Warnings(warnings)
    ask.autoApprove?.let { rule ->
        Spacer(Modifier.height(8.dp))
        if (ask.ruled) {
            Warning(SheetWarning(WarningLevel.Info, autoApproveRuledNote(quote.replaces != null, highFee = !GasOracle.quiet(quote.tx.fees, chain.id), locked = locked)))
        } else if (rule.offerable) {
            AutoApproveSwitch(rule, chain.name, always, tap, enabled, onAlways)
        }
    }
    DetailsExpander(Modifier.testTag("ethereum-details")) {
        Row0(stringResource(R.string.send_label_network), chain.name)
        AccountRow(request.from, stringResource(R.string.send_label_from))
        CopyableAddressRow(stringResource(if (data.isEmpty()) R.string.send_label_to else R.string.send_label_contract), request.to)
        Row0(stringResource(R.string.send_label_amount), "${SendAmounts.exact(request.amount, chain.decimals)} ${chain.symbol}", mono = true)
        if (data.isNotEmpty()) {
            Label(pluralText(R.plurals.send_eth_data_bytes, data.size, data.size))
            // Only the bytes the sheet shows are turned into hex, not megabytes of them.
            val head = remember(data) { "0x" + hexOf(data, SHEET_MAX_CHARS / 2) }
            Block(head, mono = true, maxHeight = 120, omitted = maxOf(0, data.size - SHEET_MAX_CHARS / 2) * 2, whole = stringResource(R.string.send_eth_tx_whole))
        }
        Row0(
            stringResource(R.string.send_label_network_fee),
            stringResource(R.string.send_up_to, feeText(quote.maxFee, chain)),
            mono = true,
            detail = feeDetail(quote),
        )
        Row0(stringResource(R.string.send_label_nonce), quote.tx.nonce.toString(), detail = nonceDetail(quote))
        Spacer(Modifier.height(8.dp))
        Warning(SheetWarning(WarningLevel.Info, stringResource(R.string.send_eth_send_footnote, feeFootnote(quote.tx))))
    }
}

/**
 * "Don't ask again for token transfers on this contract…" (#112, W25):
 * one row, off until the user turns it on through a separate confirm
 * that spells out exactly what it covers; on, one tap turns it off. It
 * only takes effect with the sheet's own Confirm. Offered only for a
 * function the wallet can name ([AutoApproveRule.offerable]). Guarded
 * like the Confirm itself ([protectedToggle]) — the row and the
 * confirm's Turn on alike: a press before the surface armed, or one
 * through another app's window, doesn't turn it on (#287 R3-M1).
 */
@Composable
internal fun AutoApproveSwitch(
    rule: AutoApproveRule,
    chain: String,
    checked: Boolean,
    tap: ArmedTapGuard,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    var confirming by remember(rule) { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .protectedToggle(tap, value = checked, role = Role.Switch, enabled = enabled) { on ->
                if (on) confirming = true else onChange(false)
            }
            .heightIn(min = 56.dp)
            .padding(vertical = 4.dp)
            .testTag("ethereum-always-approve"),
    ) {
        Column(Modifier.weight(1f)) {
            Text(autoApproveSwitchLabel(rule), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(if (checked) R.string.send_eth_always_on else R.string.send_eth_always_off),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = tap.armed && enabled)
    }
    if (confirming) {
        val confirmTap = rememberArmedTapGuard(rule)
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.send_eth_always_confirm_title)) },
            text = {
                Column {
                    Text(autoApproveScope(rule, chain))
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.send_eth_always_confirm_note), style = MaterialTheme.typography.bodySmall)
                    ObscuredTapNotice(confirmTap)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (confirmTap.guard.accepts()) {
                            confirming = false
                            onChange(true)
                        }
                    },
                    enabled = confirmTap.armed,
                    modifier = Modifier.protectedPress(confirmTap).testTag("ethereum-always-confirm"),
                ) { Text(stringResource(R.string.send_eth_always_turn_on)) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

/**
 * Why a send a rule covers still has a sheet. A high fee and a locked
 * wallet are both named when both hold, so the unlock prompt on confirm
 * isn't a surprise (R1-M1). Every reason is the caller's to state, so
 * none is ever claimed by default (R2-M2).
 */
internal fun autoApproveRuledNote(replaces: Boolean, highFee: Boolean, locked: Boolean): String =
    Strings.get(
        when {
            replaces -> R.string.send_eth_ruled_replaces
            highFee && locked -> R.string.send_eth_ruled_high_fee_locked
            highFee -> R.string.send_eth_ruled_high_fee
            locked -> R.string.send_eth_ruled_locked
            else -> R.string.send_eth_ruled_check
        },
    )

/** A send that can't go (W23): why, in a sentence; the account and network under Details. */
@Composable
private fun CantSendBody(ask: EthAsk.CantSend, warnings: List<SheetWarning>) {
    TxReviewSummary(
        headline = ask.shortOf?.let { stringResource(R.string.send_eth_cant_send_short, it) } ?: stringResource(R.string.send_eth_cant_send_headline),
        fee = null,
        modifier = Modifier.testTag("ethereum-cant-send"),
    ) {
        Text(ask.reason, style = MaterialTheme.typography.bodyMedium)
    }
    Warnings(warnings)
    DetailsExpander(Modifier.testTag("ethereum-details")) {
        Row0(stringResource(R.string.send_label_network), ask.chain.name)
        AccountRow(ask.account, stringResource(R.string.send_label_from))
    }
}

@Composable
private fun SwitchBody(ask: EthAsk.SwitchChain, warnings: List<SheetWarning>) {
    ChainHeadline(stringResource(R.string.send_eth_switch_headline_label), ask.to)
    SummarySub(stringResource(R.string.send_eth_switch_from, ask.from.name))
    Warnings(warnings)
    DetailsExpander(Modifier.testTag("ethereum-details")) {
        ChainRow(stringResource(R.string.send_label_from), ask.from)
        ChainRow(stringResource(R.string.send_label_to), ask.to)
    }
}

/**
 * The summary of a sheet about a chain: what happens ([label]), then the
 * chain's name as the headline with its chain ID on a line of its own
 * right under it — a site's name for a chain is the site's word, and the
 * ID is what it really is (#370 R3-F1), so the two are never apart.
 */
@Composable
private fun ChainHeadline(label: String, chain: Chain) {
    Text(label, style = MaterialTheme.typography.titleMedium)
    Text(
        chain.name,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.semantics { heading() },
    )
    Text(
        stringResource(R.string.send_eth_chain_id_line, chain.id.toString()),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * A chain's site-chosen name, with its chain ID on a line of its own below
 * it. The ID is never part of the name's text: a name like
 * "Ethereum (chain 1) Neeee…t" would otherwise wrap so its first line reads
 * as the real chain and the actual ID falls lines further down.
 */
@Composable
private fun ChainRow(label: String, chain: Chain) {
    Row0(label, chain.name, detail = stringResource(R.string.send_eth_chain_id_line, chain.id.toString()))
}

/**
 * Add network: what's added and switched to. For a chain Freedom knows
 * ([EthAsk.AddChain.checked]) its own RPCs come first and are picked (W27);
 * the site's are the other choice. The choice is guarded like the account
 * picker: it decides who sees the user's addresses on that network.
 */
@Composable
private fun AddChainBody(
    ask: EthAsk.AddChain,
    warnings: List<SheetWarning>,
    siteRpcs: Boolean,
    tap: ArmedTapGuard,
    enabled: Boolean,
    onSiteRpcs: (Boolean) -> Unit,
) {
    val checked = ask.checked
    val chain = if (checked != null && !siteRpcs) checked else ask.chain
    ChainHeadline(stringResource(R.string.send_eth_add_chain_headline_label), chain)
    if (checked != null) {
        Spacer(Modifier.height(8.dp))
        RpcChoice(
            title = stringResource(R.string.send_eth_add_checked),
            detail = pluralText(R.plurals.send_eth_add_checked_detail, providers(checked), providers(checked)),
            selected = !siteRpcs,
            tap = tap,
            enabled = enabled,
            tag = "ethereum-rpc-checked",
        ) { onSiteRpcs(false) }
        RpcChoice(
            title = stringResource(R.string.send_eth_add_site_rpcs),
            detail = ask.chain.rpcUrls.joinToString(", ") { hostOf(it) },
            selected = siteRpcs,
            tap = tap,
            enabled = enabled,
            tag = "ethereum-rpc-site",
        ) { onSiteRpcs(true) }
    }
    Warnings(warnings)
    DetailsExpander(Modifier.testTag("ethereum-details")) {
        Row0(stringResource(R.string.send_label_network), chain.name)
        Row0(stringResource(R.string.send_eth_chain_id), chain.id.toString(), mono = true)
        Row0(
            stringResource(R.string.send_eth_currency_label),
            // The site's decimals in plain digits, like the chain ID (#313 R1-M4).
            pluralText(R.plurals.send_eth_currency, chain.decimals, chain.currencyName, chain.symbol, chain.decimals.toString()),
        )
        Row0(stringResource(R.string.send_eth_rpc), chain.rpcUrls.joinToString("\n") { hostOf(it) }, mono = true)
        chain.explorerUrl?.let { Row0(stringResource(R.string.send_eth_explorer), hostOf(it), mono = true) }
    }
}

/** How many different providers run [chain]'s RPCs. */
private fun providers(chain: Chain): Int = ChainDataRouter.quorumMembers(chain.rpcUrls).size

@Composable
private fun RpcChoice(
    title: String,
    detail: String,
    selected: Boolean,
    tap: ArmedTapGuard,
    enabled: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .protectedSelectable(tap, selected = selected, enabled = enabled, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(vertical = 4.dp)
            .testTag(tag),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = tap.armed && enabled)
        Column(Modifier.padding(start = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** `host[:port]` of [url] (an accepted RPC has no user info). */
private fun hostOf(url: String): String = runCatching { URI(url).rawAuthority }.getOrNull() ?: url

/** The line under a sheet whose signature a Ledger ([deviceName]) makes; [hashesOnly]: it shows [LedgerHashesOnly]'s hashes. */
internal fun ledgerNote(deviceName: String, hashesOnly: Boolean): String =
    Strings.get(if (hashesOnly) R.string.send_eth_ledger_note_hashes else R.string.send_eth_ledger_note, deviceName)

/**
 * Typed data a Ledger can't show field by field (an array over 255, a
 * name over 255 bytes, a missing nested struct): it signs [hashes] of it
 * instead, so its screen no longer backs up what this sheet shows — said
 * before the user approves, with the hashes to compare (#239).
 */
@Composable
internal fun LedgerHashesOnly(hashes: LedgerTypedDataHashes) {
    Spacer(Modifier.height(8.dp))
    Column(Modifier.testTag("ledger-hashes-only")) {
        Warning(SheetWarning(WarningLevel.Caution, stringResource(R.string.send_eth_ledger_hashes_only)))
        Row0(stringResource(R.string.send_eth_domain_hash), hashes.domainHex, mono = true)
        Row0(stringResource(R.string.send_eth_message_hash), hashes.messageHex, mono = true)
    }
}

/** [LedgerHashesOnly]'s hashes, for a sheet whose [sheetWarnings] already carry its note. */
@Composable
private fun LedgerHashRows(hashes: LedgerTypedDataHashes) {
    Column(Modifier.testTag("ledger-hashes-only")) {
        Row0(stringResource(R.string.send_eth_domain_hash), hashes.domainHex, mono = true)
        Row0(stringResource(R.string.send_eth_message_hash), hashes.messageHex, mono = true)
    }
}

@Composable
internal fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

@Composable
internal fun Row0(label: String, value: String, mono: Boolean = false, detail: String? = null) {
    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 4.dp), verticalArrangement = Arrangement.Center) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer {
            Text(value, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default)
        }
        detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    HorizontalDivider()
}

@Composable
internal fun AccountRow(account: WalletAccount, label: String = stringResource(R.string.send_label_account)) {
    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 4.dp), verticalArrangement = Arrangement.Center) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(accountLabel(account))
        AddressText(account.address, MaterialTheme.typography.bodySmall, MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider()
}

@Composable
internal fun AddressRow(label: String, address: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer {
            AddressText(address, MaterialTheme.typography.bodyMedium, MaterialTheme.colorScheme.onSurface)
        }
    }
    HorizontalDivider()
}

/**
 * A message or payload in its own scrolling box so the buttons stay in
 * reach. The text is the site's, and so is its size: past
 * [SHEET_MAX_CHARS] only the start is laid out (a megabyte of text froze
 * the UI for seconds before the sheet appeared, #215 R2-M2), with a note
 * saying how much more there is and [whole] — that it's covered all the same.
 * [omitted] counts characters the caller already left out.
 */
@Composable
private fun Block(text: String, mono: Boolean, maxHeight: Int = 240, omitted: Int = 0, whole: String? = null) {
    val (shown, cut) = remember(text) { sheetText(text) }
    val more = cut + omitted
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(8.dp))
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
    ) {
        SelectionContainer {
            Text(
                shown,
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
                style = if (mono) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
            )
        }
    }
    if (more > 0) {
        Spacer(Modifier.height(4.dp))
        Warning(
            SheetWarning(
                WarningLevel.Caution,
                pluralText(R.plurals.send_eth_too_long, more, NumberFormat.getIntegerInstance().format(more), whole ?: ""),
            ),
        )
    }
}

/** The most of a site-supplied text an approval sheet lays out. */
internal const val SHEET_MAX_CHARS = 10_000

/** [text]'s first [max] characters (never splitting a surrogate pair), and how many were left out. */
internal fun sheetText(text: String, max: Int = SHEET_MAX_CHARS): Pair<String, Int> {
    if (text.length <= max) return text to 0
    var end = max
    if (Character.isHighSurrogate(text[end - 1])) end--
    return text.substring(0, end) to text.length - end
}

private val HEX_DIGITS = "0123456789abcdef".toCharArray()

/** [bytes] (the first [limit] of them) as lowercase hex, no prefix. */
internal fun hexOf(bytes: ByteArray, limit: Int = bytes.size): String {
    val n = minOf(limit, bytes.size)
    val out = CharArray(n * 2)
    for (i in 0 until n) {
        val b = bytes[i].toInt()
        out[2 * i] = HEX_DIGITS[(b shr 4) and 0xf]
        out[2 * i + 1] = HEX_DIGITS[b and 0xf]
    }
    return String(out)
}

@Composable
internal fun Note(text: String, warn: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
