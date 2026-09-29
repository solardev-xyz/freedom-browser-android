package baby.freedom.mobile.browser

import android.os.SystemClock
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material3.Button
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
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import baby.freedom.mobile.wallet.BiometricVaultAuthenticator
import baby.freedom.mobile.wallet.GasOracle
import baby.freedom.mobile.wallet.SendAmounts
import baby.freedom.mobile.wallet.Vault
import baby.freedom.mobile.wallet.WalletAccount
import baby.freedom.mobile.wallet.WalletAccounts
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What an approval sheet says: its title, what the site wants, and the approve button's label. */
internal data class EthApprovalCopy(val title: String, val request: String, val approve: String)

internal fun ethApprovalCopy(ask: EthAsk): EthApprovalCopy = when (ask) {
    is EthAsk.Connect -> EthApprovalCopy("Connect wallet", "wants to connect to your wallet", "Connect")
    is EthAsk.SignMessage -> EthApprovalCopy("Sign message", "wants you to sign a message", "Sign")
    is EthAsk.SignTypedData -> EthApprovalCopy("Sign data", "wants you to sign typed data", "Sign")
    is EthAsk.SendTransaction -> EthApprovalCopy("Send transaction", "wants to send a transaction", "Confirm and send")
    is EthAsk.SwitchChain -> EthApprovalCopy("Switch network", "wants to switch networks", "Switch")
    is EthAsk.AddChain -> EthApprovalCopy("Add network", "wants to add a network and switch to it", "Add and switch")
    is EthAsk.Payment -> EthApprovalCopy("Pay for this page", "asks to be paid to show this page", "Pay")
}

/**
 * A `window.ethereum` approval (#110): a bottom sheet that always starts
 * with the site asking — in full, wrapped rather than ellipsised, since
 * the tail of a host is what a spoof hides — then what exactly it asks
 * (the account to share, the message or typed data, the transaction with
 * its most expensive fee, the networks), and Reject / the action.
 *
 * Signing and sending need the wallet open: the action button asks for
 * the screen lock first when it isn't. A connect with no wallet on the
 * device offers to set one up (the wallet page, over the sheet).
 *
 * Like the site-permission prompt, the buttons, a swipe down, a tap
 * outside and Back all ignore input for the first
 * [PromptTapGuard.PROTECTION_MS] the sheet is on screen, counted from its
 * first drawn frame, so a page can't time its request to catch a tap
 * meant for the page. Everything but the action rejects.
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
    val guard = remember(request) { PromptTapGuard(SystemClock::uptimeMillis) }
    var armed by remember(request) { mutableStateOf(false) }
    var busy by remember(request) { mutableStateOf(false) }
    var error by remember(request) { mutableStateOf<String?>(null) }
    var picked by remember(request) { mutableStateOf<String?>(null) }
    val payment = remember(request) { (ask as? EthAsk.Payment)?.let { X402SheetState(it.payment) } }
    // The auto-approve switch (#112): off every time the sheet comes up.
    var always by remember(request) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || (guard.accepts() && !busy) },
    )
    LaunchedEffect(request) {
        withFrameNanos { }
        guard.onShown()
        delay(guard.remainingMs())
        armed = true
    }

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
            request.respond(EthAnswer.Approved(if (ask is EthAsk.Connect) connectAccount else null, payment = choice))
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
                error = walletErrorMessage(e, "unlock the wallet", phraseBackedUp = true)
            } finally {
                busy = false
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (guard.accepts() && !busy) request.respond(EthAnswer.Rejected) },
        sheetState = sheetState,
        modifier = Modifier.testTag("ethereum-approval"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(iconFor(ask), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(copy.title, style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(16.dp))
            OriginStrip(ask.origin, copy.request)
            Spacer(Modifier.height(12.dp))
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
            ) {
                when (ask) {
                    is EthAsk.Connect -> ConnectBody(
                        ask = ask,
                        accounts = accounts,
                        selected = connectAccount,
                        noWallet = vaultState == Vault.State.Empty || accounts == null,
                        onPick = { picked = it.address },
                        onSetUp = request.setUpWallet,
                    )
                    is EthAsk.SignMessage -> SignMessageBody(ask)
                    is EthAsk.SignTypedData -> SignTypedDataBody(ask)
                    is EthAsk.SendTransaction -> SendBody(
                        ask,
                        always,
                        enabled = armed && !busy,
                        locked = vaultState is Vault.State.Locked,
                        onAlways = { always = it },
                    )
                    is EthAsk.SwitchChain -> SwitchBody(ask)
                    is EthAsk.AddChain -> AddChainBody(ask)
                    is EthAsk.Payment -> X402PaymentBody(
                        ask = ask.payment,
                        state = payment!!,
                        account = ask.payment.account,
                        noWallet = vaultState == Vault.State.Empty || accounts == null,
                        locked = vaultState is Vault.State.Locked,
                        onSetUp = request.setUpWallet,
                    )
                }
            }
            ledger?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    "You’ll check and confirm this on your Ledger (${it.deviceName}) next.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("ethereum-ledger-note"),
                )
            }
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { if (guard.accepts() && !busy) request.respond(EthAnswer.Rejected) },
                    enabled = armed && !busy,
                    modifier = Modifier.weight(1f).testTag("ethereum-reject"),
                ) { Text("Reject") }
                Button(
                    onClick = ::approve,
                    enabled = armed && !busy && canApprove,
                    modifier = Modifier.weight(1f).testTag("ethereum-approve"),
                ) {
                    if (busy) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    } else {
                        Text(copy.approve)
                    }
                }
            }
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

private fun iconFor(ask: EthAsk) = when (ask) {
    is EthAsk.Connect -> Icons.Filled.AccountBalanceWallet
    is EthAsk.SignMessage, is EthAsk.SignTypedData -> Icons.Filled.Draw
    is EthAsk.SendTransaction -> Icons.AutoMirrored.Filled.Send
    is EthAsk.SwitchChain -> Icons.Filled.Link
    is EthAsk.AddChain -> Icons.Filled.Hub
    is EthAsk.Payment -> Icons.Filled.Payments
}

/** The site asking, in full, and what it asks — the first thing on every sheet. */
@Composable
private fun OriginStrip(origin: String, request: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().testTag("ethereum-origin"),
    ) {
        Column(Modifier.padding(12.dp)) {
            SelectionContainer {
                Text(
                    permissionOriginDisplay(origin),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(request, style = MaterialTheme.typography.bodyMedium)
            if (origin.startsWith("http://")) {
                Text(
                    "Not encrypted: a loopback page on this device",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ConnectBody(
    ask: EthAsk.Connect,
    accounts: List<WalletAccount>?,
    selected: WalletAccount?,
    noWallet: Boolean,
    onPick: (WalletAccount) -> Unit,
    onSetUp: () -> Unit,
) {
    if (noWallet || accounts == null) {
        Text("There's no wallet on this device yet. Set one up to connect — or reject, and keep browsing without one.")
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onSetUp, modifier = Modifier.fillMaxWidth().testTag("ethereum-setup")) { Text("Set up a wallet") }
        return
    }
    Row0("Network", ask.chain.name)
    Text(
        if (accounts.size > 1) "Account to share" else "Account",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
    accounts.forEach { account ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .selectable(selected = account == selected, role = Role.RadioButton, onClick = { onPick(account) })
                .padding(vertical = 4.dp),
        ) {
            if (accounts.size > 1) RadioButton(selected = account == selected, onClick = null)
            Column(Modifier.padding(start = if (accounts.size > 1) 8.dp else 0.dp)) {
                Text(accountLabel(account), style = MaterialTheme.typography.bodyLarge)
                AddressText(account.address, MaterialTheme.typography.bodySmall, MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Note(
        "The site will see this account's address and can read its balances. It can ask you to sign " +
            "messages and send transactions, and each one asks you here first (unless you later turn on auto-approve for a call). Disconnect it any time on the wallet page or in Settings → Site permissions.",
    )
}

@Composable
private fun SignMessageBody(ask: EthAsk.SignMessage) {
    AccountRow(ask.account)
    Label("Message")
    Block(ask.text ?: ask.hex, mono = ask.text == null, whole = "The signature covers the whole message.")
    if (ask.text == null) Note("This message isn't readable text; it's shown as hex bytes.")
    Spacer(Modifier.height(8.dp))
    Note("A signature can authorise actions off-chain, such as a login or an order. Only sign if you trust the site.")
}

@Composable
private fun SignTypedDataBody(ask: EthAsk.SignTypedData) {
    Row0("Network", if (ask.chainBound) "${ask.chain.name} (chain ${ask.chain.id})" else "Any — the signature names no chain")
    AccountRow(ask.account)
    ask.domainName?.let { Row0("Application", it) }
    ask.verifyingContract?.let { AddressRow("Contract", it) }
    Row0("Type", ask.primaryType, mono = true)
    Label("Data")
    Block(ask.messageJson, mono = true, whole = "The signature covers all of the data.")
    Spacer(Modifier.height(8.dp))
    Note(
        "Typed data can authorise a transfer or a trade (a permit, an order) without a transaction. " +
            "Only sign if you trust the site and expect it.",
    )
}

@Composable
private fun SendBody(ask: EthAsk.SendTransaction, always: Boolean, enabled: Boolean, locked: Boolean, onAlways: (Boolean) -> Unit) {
    val quote = ask.quote
    val request = quote.request
    val chain = request.chain
    val data = quote.tx.data
    if (ask.repriced) {
        Note("It took more than a minute, so the network fee was priced again. Check it before you confirm.", warn = true)
        Spacer(Modifier.height(4.dp))
    }
    quote.replaces?.let {
        // A site's transaction can take the nonce of a send the user stopped tracking that's
        // still waiting in a pool — say so here as the Send page does, or confirming silently
        // drops that earlier send (#215 R6-F1).
        Note(
            "This transaction takes the place of the send you stopped tracking ($it): only one of the two can " +
                "go through, and this one pays the higher fee. If that send still matters, reject this and " +
                "settle it on the wallet page first.",
            warn = true,
        )
        Spacer(Modifier.height(4.dp))
    }
    Row0("Network", chain.name)
    AccountRow(request.from, "From")
    AddressRow(if (data.isEmpty()) "To" else "Contract", request.to)
    Row0("Amount", "${SendAmounts.exact(request.amount, chain.decimals)} ${chain.symbol}", mono = true)
    if (data.isNotEmpty()) {
        Label("Data (${data.size} bytes)")
        // Only the bytes the sheet shows are turned into hex, not megabytes of them.
        val head = remember(data) { "0x" + hexOf(data, SHEET_MAX_CHARS / 2) }
        Block(head, mono = true, maxHeight = 120, omitted = maxOf(0, data.size - SHEET_MAX_CHARS / 2) * 2, whole = "The transaction sends all of it.")
    }
    Row0("Network fee", "up to ${feeText(quote.tx.maxFee, chain)}", mono = true, detail = feeDetail(quote.tx))
    quote.nativeTotal?.takeIf { request.amount.signum() > 0 }?.let { Row0("Total", "up to ${feeText(it, chain)}", mono = true) }
    Row0("Nonce", quote.tx.nonce.toString(), detail = nonceDetail(quote))
    ask.autoApprove?.let { rule ->
        Spacer(Modifier.height(8.dp))
        if (ask.ruled) {
            Note(autoApproveRuledNote(quote.replaces != null, highFee = !GasOracle.quiet(quote.tx.fees, chain.id), locked = locked))
        } else {
            AutoApproveSwitch(rule, chain.name, always, enabled, onAlways)
        }
    }
    Spacer(Modifier.height(8.dp))
    Note(
        feeFootnote(quote.tx) + " A transaction can't be undone once it's sent: " +
            "only confirm if you trust the site and expect it.",
    )
}

/**
 * "Always approve … on this contract" (#112), with exactly what it covers
 * written out in full under it. Off until the user turns it on; it only
 * takes effect with the sheet's own Confirm.
 */
@Composable
private fun AutoApproveSwitch(rule: AutoApproveRule, chain: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
                    .testTag("ethereum-always-approve"),
            ) {
                Text(autoApproveSwitchLabel(rule), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Switch(checked = checked, onCheckedChange = null, enabled = enabled)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                autoApproveScope(rule, chain),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Why a send a rule covers still has a sheet. A high fee and a locked
 * wallet are both named when both hold, so the unlock prompt on confirm
 * isn't a surprise (R1-M1). Every reason is the caller's to state, so
 * none is ever claimed by default (R2-M2).
 */
internal fun autoApproveRuledNote(replaces: Boolean, highFee: Boolean, locked: Boolean): String =
    "An auto-approve rule you turned on covers this call. " + when {
        replaces -> "It's asked here because it takes the place of a send you stopped tracking."
        highFee && locked -> "It's asked here because its network fee is higher than a rule sends without asking, " +
            "and because the wallet is locked; it goes out once you confirm."
        highFee -> "It's asked here because its network fee is higher than a rule sends without asking."
        locked -> "It's asked here because the wallet is locked; it goes out once you confirm."
        else -> "It's asked here for you to check before it goes out."
    }

@Composable
private fun SwitchBody(ask: EthAsk.SwitchChain) {
    Row0("From", "${ask.from.name} (chain ${ask.from.id})")
    Row0("To", "${ask.to.name} (chain ${ask.to.id})")
    Spacer(Modifier.height(8.dp))
    Note("Only this site switches; other sites stay on their own network.")
}

@Composable
private fun AddChainBody(ask: EthAsk.AddChain) {
    val chain = ask.chain
    Row0("Network", chain.name)
    Row0("Chain ID", chain.id.toString(), mono = true)
    Row0("Currency", "${chain.currencyName} (${chain.symbol}, ${chain.decimals} decimals)")
    Row0("RPC", chain.rpcUrls.joinToString("\n") { hostOf(it) }, mono = true)
    chain.explorerUrl?.let { Row0("Explorer", hostOf(it), mono = true) }
    Spacer(Modifier.height(8.dp))
    Note(
        "The site chose these RPCs. They'll see your addresses and what you read and send on this network, " +
            "and their answers aren't checked against other RPCs. You can change or remove the network in " +
            "Settings → Chains.",
        warn = true,
    )
}

/** `host[:port]` of [url] (an accepted RPC has no user info). */
private fun hostOf(url: String): String = runCatching { URI(url).rawAuthority }.getOrNull() ?: url

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
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer {
            Text(value, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default)
        }
        detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    HorizontalDivider()
}

@Composable
internal fun AccountRow(account: WalletAccount, label: String = "Account") {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
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
        Note(
            "Too long to show whole: ${"%,d".format(more)} more characters aren't shown. " + (whole ?: ""),
            warn = true,
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
