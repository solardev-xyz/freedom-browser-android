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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Link
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
    val needsUnlock = ask is EthAsk.SignMessage || ask is EthAsk.SignTypedData || ask is EthAsk.SendTransaction
    val canApprove = when (ask) {
        is EthAsk.Connect -> connectAccount != null
        else -> true
    }

    fun approve() {
        if (!guard.accepts() || busy) return
        if (!needsUnlock) {
            request.respond(EthAnswer.Approved(if (ask is EthAsk.Connect) connectAccount else null))
            return
        }
        busy = true
        error = null
        scope.launch {
            try {
                if (!vault.unlockedNow()) vault.unlock(BiometricVaultAuthenticator(context))
                request.respond(EthAnswer.Approved())
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
                    is EthAsk.SendTransaction -> SendBody(ask)
                    is EthAsk.SwitchChain -> SwitchBody(ask)
                    is EthAsk.AddChain -> AddChainBody(ask)
                }
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

private fun iconFor(ask: EthAsk) = when (ask) {
    is EthAsk.Connect -> Icons.Filled.AccountBalanceWallet
    is EthAsk.SignMessage, is EthAsk.SignTypedData -> Icons.Filled.Draw
    is EthAsk.SendTransaction -> Icons.AutoMirrored.Filled.Send
    is EthAsk.SwitchChain -> Icons.Filled.Link
    is EthAsk.AddChain -> Icons.Filled.Hub
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
                Text(account.name, style = MaterialTheme.typography.bodyLarge)
                AddressText(account.address, MaterialTheme.typography.bodySmall, MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Note(
        "The site will see this account's address and can read its balances. It can ask you to sign " +
            "messages and send transactions, and each one asks you here first. Disconnect it any time on the wallet page.",
    )
}

@Composable
private fun SignMessageBody(ask: EthAsk.SignMessage) {
    AccountRow(ask.account)
    Label("Message")
    Block(ask.text ?: ask.hex, mono = ask.text == null)
    if (ask.text == null) Note("This message isn't readable text; it's shown as hex bytes.")
    Spacer(Modifier.height(8.dp))
    Note("A signature can authorise actions off-chain, such as a login or an order. Only sign if you trust the site.")
}

@Composable
private fun SignTypedDataBody(ask: EthAsk.SignTypedData) {
    Row0("Network", "${ask.chain.name} (chain ${ask.chain.id})")
    AccountRow(ask.account)
    ask.domainName?.let { Row0("Application", it) }
    ask.verifyingContract?.let { AddressRow("Contract", it) }
    Row0("Type", ask.primaryType, mono = true)
    Label("Data")
    Block(ask.messageJson, mono = true)
    Spacer(Modifier.height(8.dp))
    Note(
        "Typed data can authorise a transfer or a trade (a permit, an order) without a transaction. " +
            "Only sign if you trust the site and expect it.",
    )
}

@Composable
private fun SendBody(ask: EthAsk.SendTransaction) {
    val quote = ask.quote
    val request = quote.request
    val chain = request.chain
    val data = quote.tx.data
    if (ask.repriced) {
        Note("It took more than a minute, so the network fee was priced again. Check it before you confirm.", warn = true)
        Spacer(Modifier.height(4.dp))
    }
    Row0("Network", chain.name)
    AccountRow(request.from, "From")
    AddressRow(if (data.isEmpty()) "To" else "Contract", request.to)
    Row0("Amount", "${SendAmounts.exact(request.amount, chain.decimals)} ${chain.symbol}", mono = true)
    if (data.isNotEmpty()) {
        Label("Data (${data.size} bytes)")
        Block("0x" + data.joinToString("") { "%02x".format(it) }, mono = true, maxHeight = 120)
    }
    Row0("Network fee", "up to ${feeText(quote.tx.maxFee, chain)}", mono = true, detail = feeDetail(quote.tx))
    quote.nativeTotal?.takeIf { request.amount.signum() > 0 }?.let { Row0("Total", "up to ${feeText(it, chain)}", mono = true) }
    Row0("Nonce", quote.tx.nonce.toString(), detail = trustLabel(quote.nonceTrust))
    Spacer(Modifier.height(8.dp))
    Note(
        "Only the fee the network actually charges is paid. A transaction can't be undone once it's sent: " +
            "only confirm if you trust the site and expect it.",
    )
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
private fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun Row0(label: String, value: String, mono: Boolean = false, detail: String? = null) {
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
private fun AccountRow(account: WalletAccount, label: String = "Account") {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(account.name)
        AddressText(account.address, MaterialTheme.typography.bodySmall, MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider()
}

@Composable
private fun AddressRow(label: String, address: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer {
            AddressText(address, MaterialTheme.typography.bodyMedium, MaterialTheme.colorScheme.onSurface)
        }
    }
    HorizontalDivider()
}

/** A message or payload, whole, in its own scrolling box so the buttons stay in reach. */
@Composable
private fun Block(text: String, mono: Boolean, maxHeight: Int = 240) {
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
                text,
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
                style = if (mono) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun Note(text: String, warn: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
