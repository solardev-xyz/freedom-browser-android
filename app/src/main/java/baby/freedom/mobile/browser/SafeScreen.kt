package baby.freedom.mobile.browser

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import baby.freedom.mobile.chains.Chain
import baby.freedom.mobile.chains.rpc.ChainRpcException
import baby.freedom.mobile.ens.Keccak256
import baby.freedom.mobile.ens.toHex
import baby.freedom.mobile.ui.isLight
import baby.freedom.mobile.wallet.DappCall
import baby.freedom.mobile.wallet.Eip712
import baby.freedom.mobile.wallet.Recipients
import baby.freedom.mobile.wallet.SafeAccount
import baby.freedom.mobile.wallet.SafeAccounts
import baby.freedom.mobile.wallet.SafeCallLabel
import baby.freedom.mobile.wallet.SafeChain
import baby.freedom.mobile.wallet.SafeException
import baby.freedom.mobile.wallet.SafePending
import baby.freedom.mobile.wallet.SafeProtocol
import baby.freedom.mobile.wallet.SafeState
import baby.freedom.mobile.wallet.ScannedCode
import baby.freedom.mobile.wallet.SendAmounts
import baby.freedom.mobile.wallet.SendException
import baby.freedom.mobile.wallet.SendQuote
import baby.freedom.mobile.wallet.SendRequest
import baby.freedom.mobile.wallet.SendStatus
import baby.freedom.mobile.wallet.Token
import baby.freedom.mobile.wallet.TokenRegistry
import baby.freedom.mobile.wallet.Vault
import baby.freedom.mobile.wallet.VaultAuthenticator
import baby.freedom.mobile.wallet.WalletAccount
import baby.freedom.mobile.wallet.WalletSender
import baby.freedom.mobile.wallet.ledger.LedgerException
import java.math.BigInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

internal const val SAFE_ACCOUNTS_TITLE = "Safe accounts"

/**
 * A shared request longer than this many bytes ([qrBytes], UTF-8) makes a
 * QR code too dense for a phone camera: Copy only. Bytes, not characters,
 * so a long message in CJK or emoji can't overflow the code's capacity.
 */
internal const val SAFE_QR_MAX = 1_800

/** Who [address] is, among a Safe's owners: one of this wallet's accounts by name, or another device's. */
internal fun safeOwnerName(address: String, accounts: List<WalletAccount>): String =
    accounts.firstOrNull { it.address.equals(address, ignoreCase = true) }?.name ?: "Another device"

/** "2 of 3 owners" — how many must sign, of how many. */
internal fun safePolicy(safe: SafeAccount): String = "${safe.threshold} of ${safe.owners.size} owners must sign"

/** What a pending item is, for its row and heading: the payment, or the message's words. */
internal fun safePendingTitle(p: SafePending): String = when (p.kind) {
    SafePending.Kind.TX -> p.payment?.let { "Send ${SendAmounts.exact(it.amount, it.decimals)} ${it.symbol}" } ?: "Transaction"
    SafePending.Kind.MESSAGE -> "Message: “${p.text.orEmpty()}”"
}

/** Where a pending item stands, with its signature count. */
internal fun safePendingState(p: SafePending): String {
    val count = "${p.collected} of ${p.threshold} signatures"
    return when {
        p.kind == SafePending.Kind.TX && p.superseded -> "$count · can no longer execute"
        p.kind == SafePending.Kind.TX && p.execHash != null -> "$count · executing"
        p.kind == SafePending.Kind.TX && p.ready -> "$count · ready to execute"
        p.ready -> "$count · signed"
        else -> count
    }
}

/** The Safe's row on the wallet page: policy, and whether it's active or what waits. */
internal fun safeRowSubtitle(safe: SafeAccount, pending: List<SafePending>): String {
    val parts = mutableListOf(safePolicy(safe))
    if (!safe.deployed) parts += "not active yet"
    val tx = pending.count { it.kind == SafePending.Kind.TX }
    val messages = pending.count { it.kind == SafePending.Kind.MESSAGE }
    if (tx > 0) parts += "$tx transaction waiting"
    if (messages > 0) parts += if (messages == 1) "1 message waiting" else "$messages messages waiting"
    return parts.joinToString(" · ")
}

/** A Safe step's failure, for the user; null when there's nothing to say (the unlock prompt was cancelled). */
internal fun safeErrorMessage(e: Throwable, action: String, phraseBackedUp: Boolean): String? = when (e) {
    is SafeException -> e.message
    is SendException -> e.message
    is Eip712.Invalid -> e.message
    is ChainRpcException -> WalletSender.readFailure(e)
    // A Ledger owner's or payer's signature (#142): cancelled on the phone says nothing.
    is LedgerException -> e.message.takeIf { e.kind != LedgerException.Kind.CANCELLED }
    else -> walletErrorMessage(e, action, phraseBackedUp)
}

/** The wallet page's list of Safe accounts (#141), and the way to create one. */
@Composable
internal fun SafeAccountsSection(state: SafeState?, enabled: Boolean, onOpen: (String) -> Unit, onCreate: () -> Unit) {
    SectionCard(title = SAFE_ACCOUNTS_TITLE) {
        state?.safes?.forEach { safe ->
            PageRow(
                title = safe.name,
                subtitle = safeRowSubtitle(safe, state.pendingFor(safe.address)),
                thirdLine = safe.address,
                style = PageRowStyle.Inset,
                leadingIcon = Icons.Filled.Groups,
                enabled = enabled,
                onClick = { onOpen(safe.address) },
            )
        }
        PageRow(
            title = "Create a Safe account",
            subtitle = "Held by 2 or 3 owners on Gnosis: this phone’s accounts, and accounts on other devices",
            style = PageRowStyle.Inset,
            leadingIcon = Icons.Filled.Add,
            enabled = enabled && state != null,
            onClick = onCreate,
        )
    }
}

/**
 * Create a Safe (desktop's `create-safe.js`): pick a preset — 1 of 2 or
 * 2 of 3 — and that many owners, from this wallet's accounts and
 * addresses of accounts on other devices, and a name. Creating is free
 * and instant: the address is predicted and stored with its params; it
 * can take funds at once and is activated on Gnosis later.
 */
@Composable
internal fun SafeCreatePage(accounts: List<WalletAccount>, onCreated: (SafeAccount) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val safes = remember(context) { SafeAccounts.get(context) }
    val scope = rememberCoroutineScope()
    var threshold by remember { mutableStateOf(1) }
    val needed = if (threshold == 1) 2 else 3
    var local by remember { mutableStateOf(accounts.take(1).map { it.address }) }
    var others by remember { mutableStateOf(listOf<String>()) }
    var otherInput by remember { mutableStateOf("") }
    var scanningOther by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val owners = local + others
    val cameraPermission = rememberCameraPermissionState()
    val otherParsed = otherInput.takeIf { it.isNotBlank() }?.let { ScannedCode.parse(it) }

    fun addOther(text: String) {
        when (val code = ScannedCode.parse(text)) {
            is ScannedCode.Address -> when {
                owners.any { it.equals(code.address, ignoreCase = true) } -> error = "That account is already an owner."
                accounts.any { it.address.equals(code.address, ignoreCase = true) } -> {
                    local = local + accounts.first { it.address.equals(code.address, ignoreCase = true) }.address
                    otherInput = ""
                }
                else -> {
                    others = others + code.address
                    otherInput = ""
                    error = null
                }
            }
            is ScannedCode.Unrecognized -> error = code.reason
            else -> error = "That isn’t an account’s address."
        }
    }

    BackHandler(onBack = onBack)
    FullScreenScaffold(title = "Create a Safe account", onDismiss = onBack) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item("preset") {
                SectionCard(title = "Who must sign") {
                    for ((t, title, detail) in listOf(
                        Triple(1, "1 of 2 owners", "Either owner can move funds alone; the other is a backup if one key is lost."),
                        Triple(2, "2 of 3 owners", "Any two must sign. One lost key, or one stolen, can’t lose the funds."),
                    )) {
                        Row(
                            verticalAlignment = Alignment.Top,
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(selected = threshold == t, enabled = !busy, role = Role.RadioButton) {
                                    threshold = t
                                    val max = if (t == 1) 2 else 3
                                    if (owners.size > max) {
                                        others = others.take((max - local.size).coerceAtLeast(0))
                                        local = local.take(max - others.size)
                                    }
                                }
                                .padding(vertical = 4.dp),
                        ) {
                            RadioButton(selected = threshold == t, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(title, fontWeight = FontWeight.Medium)
                                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Text(
                        "2 of 2 isn’t offered: losing either key would lock the funds for good.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item("local") {
                SectionCard(title = "Owners from this wallet") {
                    accounts.forEach { account ->
                        val checked = local.any { it.equals(account.address, ignoreCase = true) }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .toggleable(value = checked, enabled = !busy && (checked || owners.size < needed), role = Role.Checkbox) {
                                    local = if (checked) local.filterNot { a -> a.equals(account.address, ignoreCase = true) } else local + account.address
                                }
                                .padding(vertical = 4.dp),
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null, enabled = !busy && (checked || owners.size < needed))
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(account.name, fontWeight = FontWeight.Medium)
                                AddressText(account.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Text(
                        "At least one is needed: it signs here, and pays the network fee to activate the Safe and to execute its transactions.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item("others") {
                SectionCard(title = "Owners on other devices") {
                    others.forEach { address ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            AddressText(
                                address,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { others = others - address }, enabled = !busy) { Text("Remove") }
                        }
                    }
                    if (owners.size < needed) {
                        OutlinedTextField(
                            value = otherInput,
                            onValueChange = {
                                otherInput = it.trim()
                                error = null
                            },
                            enabled = !busy,
                            singleLine = true,
                            placeholder = { Text("0x… address of the other device’s account") },
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (otherParsed is ScannedCode.Unrecognized) FieldText(otherParsed.reason, error = true)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { scanningOther = !scanningOther }, enabled = !busy) { Text(if (scanningOther) "Stop scanning" else "Scan") }
                            TextButton(onClick = { addOther(otherInput) }, enabled = !busy && otherParsed is ScannedCode.Address) { Text("Add") }
                        }
                        if (scanningOther) {
                            QrScanner(
                                permission = cameraPermission,
                                onCode = { text ->
                                    if (ScannedCode.parse(text) is ScannedCode.Address) {
                                        scanningOther = false
                                        addOther(text)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)),
                            )
                        }
                    }
                    Text(
                        "An account on desktop Freedom or another phone: they co-sign the Safe’s transactions by signing the request this phone shares.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item("name") {
                SectionCard(title = "Name") {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(SafeAccounts.MAX_NAME) },
                        enabled = !busy,
                        singleLine = true,
                        placeholder = { Text("Safe ${((SafeAccounts.get(context).state.value?.safes?.size) ?: 0) + 1}") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item("create") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Creating it is free and needs no network: its address is worked out now from these owners, and can take funds at once. " +
                            "It’s activated on Gnosis when you’re ready, for a small network fee.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    error?.let { FieldText(it, error = true) }
                    Button(
                        onClick = {
                            busy = true
                            error = null
                            scope.launch {
                                try {
                                    onCreated(safes.create(name, owners, threshold, accounts.map { it.address }))
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    error = safeErrorMessage(e, "create the Safe", phraseBackedUp = true)
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        enabled = !busy && owners.size == needed && local.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (owners.size == needed) "Create" else "Choose ${needed - owners.size} more owner${if (needed - owners.size == 1) "" else "s"}") }
                }
            }
        }
    }
}

/** A Safe's page: where it stands, what it holds, what waits for signatures, and what can be started. */
@Composable
internal fun SafePage(
    address: String,
    accounts: List<WalletAccount>,
    chains: List<Chain>,
    vault: Vault,
    auth: VaultAuthenticator,
    phraseBackedUp: Boolean,
    onOpenUrl: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val safes = remember(context) { SafeAccounts.get(context) }
    val chainReads = remember(context) { SafeChain.get(context) }
    val sender = remember(context) { WalletSender.get(context) }
    val state by safes.state.collectAsState()
    val sendStatus by sender.status.collectAsState()
    val scope = rememberCoroutineScope()
    val safe = state?.safe(address)
    // Its page goes with it (removed here, or with the wallet).
    LaunchedEffect(safe == null) { if (safe == null) onBack() }
    if (safe == null) return
    val pending = state?.pendingFor(address).orEmpty()
    val chain = chains.firstOrNull { it.id == safe.chainId }
    val executor = SafeChain.executor(safe, accounts)

    var openId by remember { mutableStateOf<String?>(null) }
    var proposing by remember { mutableStateOf<SafePending.Kind?>(null) }
    var showQr by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var quote by remember { mutableStateOf<SendQuote?>(null) }
    var refreshTick by remember { mutableStateOf(0) }
    var activation by remember { mutableStateOf<SafeChain.Activation?>(null) }
    var checkError by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var holdings by remember { mutableStateOf<List<Pair<Token, BigInteger>>?>(null) }

    // Where it stands on chain: deployed (healing the record, as desktop does), and if not, the activation's price.
    LaunchedEffect(safe.deployed, executor?.address, chain, refreshTick) {
        val c = chain ?: return@LaunchedEffect
        checking = true
        checkError = null
        try {
            if (!safe.deployed) {
                if (chainReads.deployed(c.id, safe.address)) {
                    safes.markDeployed(safe.address)
                } else {
                    activation = executor?.let { chainReads.activation(safe, it.address) }
                }
            }
            holdings = TokenRegistry.tokens(c).mapNotNull { token ->
                val held = if (token.address == null) chainReads.balance(c.id, safe.address) else chainReads.tokenBalance(c.id, token.address, safe.address)
                (token to held).takeIf { token.isNative || held.signum() > 0 }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            checkError = safeErrorMessage(e, "read the Safe", phraseBackedUp)
        } finally {
            checking = false
        }
    }
    // An activation or execution that got mined changed the balances.
    LaunchedEffect(sendStatus?.done) { if (sendStatus?.done == true) refreshTick++ }

    val openEntry = openId?.let { id -> pending.firstOrNull { it.id == id } }
    if (openEntry != null) {
        SafeRequestPage(openEntry, safe, accounts, chain, vault, auth, phraseBackedUp, onOpenUrl, onBack = { openId = null })
        return
    }
    proposing?.let { kind ->
        SafeProposePage(kind, safe, accounts, chain, vault, auth, phraseBackedUp, onProposed = {
            proposing = null
            openId = it.id
        }, onBack = { proposing = null })
        return
    }

    fun prepareActivation() {
        val c = chain ?: return
        val from = executor ?: return
        busy = true
        error = null
        scope.launch {
            try {
                val data = SafeProtocol.deploymentData(safe.owners, safe.threshold, safe.saltNonce)
                quote = sender.prepare(
                    SendRequest(c, TokenRegistry.native(c), from, SafeProtocol.FACTORY, BigInteger.ZERO, DappCall(null, data, null, SafeCallLabel(safe.address, safe.name, activates = true))),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = safeErrorMessage(e, "price the activation", phraseBackedUp)
            } finally {
                busy = false
            }
        }
    }

    val back = {
        if (quote != null) {
            quote = null
            notice = null
        } else {
            onBack()
        }
    }
    BackHandler(onBack = back)
    FullScreenScaffold(title = safe.name, onDismiss = back) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item("account") {
                SectionCard(title = "Safe account") {
                    SelectionContainer {
                        AddressText(safe.address, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    }
                    Text(
                        "${safePolicy(safe)} · Safe v${SafeProtocol.VERSION} on ${chain?.name ?: "chain ${safe.chainId}"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (showQr) {
                        Spacer(Modifier.height(8.dp))
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            QrCodeImage(safe.address, "QR code of the Safe’s address", Modifier.widthIn(max = 280.dp).fillMaxWidth())
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { showQr = !showQr }) { Text(if (showQr) "Hide QR code" else "Show QR code") }
                        TextButton(onClick = { copyToClipboard(context, safe.address) }) { Text("Copy address") }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("Owners", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    safe.owners.forEach { owner ->
                        Column(Modifier.padding(vertical = 2.dp)) {
                            Text(safeOwnerName(owner, accounts), style = MaterialTheme.typography.bodyMedium)
                            AddressText(owner, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            val status = sendStatus?.takeIf { it.quote.request.dapp?.safe?.address.equals(safe.address, ignoreCase = true) }
            val q = quote
            when {
                status != null -> item("send") {
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
                q != null -> item("review") {
                    SafeCallReview(
                        quote = q,
                        what = "Activates this Safe: deploys it through the canonical Safe factory with its owners and threshold.",
                        busy = busy,
                        notice = notice,
                        error = error,
                        onCancel = {
                            quote = null
                            notice = null
                            error = null
                        },
                        onConfirm = {
                            confirmSafeCall(context, q, sender, vault, auth, scope,
                                setBusy = { busy = it },
                                onStarted = { quote = null; notice = null },
                                onError = { error = safeErrorMessage(it, "unlock the wallet", phraseBackedUp) },
                                onStale = { prepareActivation(); notice = "The fee estimate was over a minute old, so it’s been priced again. Check it and confirm." },
                            )
                        },
                    )
                }
                !safe.deployed -> item("activate") {
                    SafeActivationSection(
                        safe = safe,
                        chain = chain,
                        executor = executor,
                        activation = activation,
                        checking = checking,
                        checkError = checkError,
                        error = error,
                        busy = busy,
                        onRefresh = { refreshTick++ },
                        onCopy = { copyToClipboard(context, it) },
                        onActivate = ::prepareActivation,
                    )
                }
            }
            if (safe.deployed) item("holdings") {
                SectionCard(title = "Holds") {
                    val c = chain
                    when {
                        c == null -> Text("Gnosis isn’t set up in Settings → Chains.", style = MaterialTheme.typography.bodyMedium)
                        holdings == null && checkError != null -> FieldText(checkError!!, error = true)
                        holdings == null -> Text("Reading…", style = MaterialTheme.typography.bodyMedium)
                        else -> holdings!!.forEach { (token, raw) ->
                            // Every digit: a Safe funded with a few wei must not read as "<0.00000001".
                            ReviewRow(token.symbol, "${exactAmount(raw, token.decimals)} ${token.symbol}", mono = true)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { refreshTick++ }, enabled = !checking) { Text(if (checking) "Reading…" else "Refresh") }
                    }
                }
            }
            item("pending") {
                SectionCard(title = "Waiting for signatures") {
                    if (pending.isEmpty()) {
                        Text("Nothing is waiting.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    pending.sortedBy { it.createdAt }.forEach { p ->
                        PageRow(
                            title = safePendingTitle(p),
                            subtitle = safePendingState(p),
                            thirdLine = p.payment?.let { "to ${it.recipient}" },
                            style = PageRowStyle.Inset,
                            leadingIcon = Icons.Filled.Draw,
                            onClick = { openId = p.id },
                        )
                    }
                }
            }
            item("propose") {
                SectionCard(title = "Start") {
                    val hasTx = pending.any { it.kind == SafePending.Kind.TX }
                    PageRow(
                        title = "Propose a transaction",
                        subtitle = when {
                            !safe.deployed -> "Once the Safe is active"
                            hasTx -> "One at a time: execute or discard the one waiting first"
                            else -> "Send xDAI or a token from the Safe, once enough owners sign"
                        },
                        style = PageRowStyle.Inset,
                        enabled = safe.deployed && !hasTx && chain != null,
                        onClick = { proposing = SafePending.Kind.TX },
                    )
                    PageRow(
                        title = "Propose a message",
                        subtitle = if (safe.deployed) "A text the Safe signs (EIP-1271), once enough owners sign" else "Once the Safe is active",
                        style = PageRowStyle.Inset,
                        enabled = safe.deployed,
                        onClick = { proposing = SafePending.Kind.MESSAGE },
                    )
                }
            }
            item("remove") {
                TextButton(onClick = { confirmRemove = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Remove from this phone…", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove ${safe.name} from this phone?") },
            text = {
                Text(
                    "The Safe and its funds stay on chain, and its owners can still use it elsewhere. This phone forgets it, " +
                        "and anything waiting for signatures here. It can’t be added back from its address alone: its owners and " +
                        "salt are only kept here.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    scope.launch { runCatching { safes.remove(safe.address) } }
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Keep it") } },
        )
    }
}

/** Not active yet: what activation costs, who pays, and — the blocking state — how much that account still needs. */
@Composable
private fun SafeActivationSection(
    safe: SafeAccount,
    chain: Chain?,
    executor: WalletAccount?,
    activation: SafeChain.Activation?,
    checking: Boolean,
    checkError: String?,
    error: String?,
    busy: Boolean,
    onRefresh: () -> Unit,
    onCopy: (String) -> Unit,
    onActivate: () -> Unit,
) {
    val amber = if (MaterialTheme.colorScheme.isLight) Color(0xFFB45309) else Color(0xFFF59E0B)
    SectionCard(title = "Not active yet") {
        when {
            chain == null -> Text("Gnosis isn’t set up in Settings → Chains, so this Safe can’t be activated.", style = MaterialTheme.typography.bodyMedium)
            executor == null -> Text("None of its owners is an account in this wallet, so nothing here can pay to activate it.", style = MaterialTheme.typography.bodyMedium)
            activation == null && checkError != null -> FieldText(checkError, error = true)
            activation == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Pricing the activation…", style = MaterialTheme.typography.bodyMedium)
            }
            activation.needsFunds -> {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = amber, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Needs funds", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                        Text(
                            "Activating costs up to ${feeText(activation.maxFee, chain)}, paid by ${executor.name}, which holds " +
                                "${feeText(activation.balance, chain)}. Send it at least ${feeText(activation.shortfall, chain)}:",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                SelectionContainer { AddressText(executor.address, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { onCopy(executor.address) }) { Text("Copy ${executor.name}’s address") }
                    TextButton(onClick = onRefresh, enabled = !checking) { Text(if (checking) "Checking…" else "Check again") }
                }
            }
            else -> {
                Text(
                    "Activating deploys it on ${chain.name}: up to ${feeText(activation.maxFee, chain)}, paid by ${executor.name} " +
                        "(holds ${feeText(activation.balance, chain)}).",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                error?.let { FieldText(it, error = true) }
                Button(onClick = onActivate, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    if (busy) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp)) else Text("Activate on ${chain.name}")
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Its address can take funds already, before it’s active: they’re there once it is. " +
                "Transactions and messages can be proposed once it’s active.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The review for a call the wallet composed for a Safe — its activation or
 * an `execTransaction` — before anything is signed: who pays, the contract,
 * what it does, the most the fee can be, and the nonce. Confirm ignores
 * taps for the first moment it's on screen ([PromptTapGuard]).
 */
@Composable
private fun SafeCallReview(
    quote: SendQuote,
    what: String,
    busy: Boolean,
    notice: String?,
    error: String?,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val request = quote.request
    val chain = request.chain
    val guard = remember(quote) { PromptTapGuard(SystemClock::uptimeMillis) }
    var armed by remember(quote) { mutableStateOf(false) }
    LaunchedEffect(quote) {
        withFrameNanos { }
        guard.onShown()
        delay(guard.remainingMs())
        armed = true
    }
    SectionCard(title = "Review") {
        ReviewRow("What", what)
        ReviewRow("Network", chain.name)
        ReviewRow("Paid by", request.from.name, address = request.from.address)
        ReviewRow("Contract", null, address = request.to)
        ReviewRow("Network fee", "up to ${feeText(quote.tx.maxFee, chain)}", mono = true, detail = feeDetail(quote.tx))
        ReviewRow("Nonce", quote.tx.nonce.toString(), detail = nonceDetail(quote))
        Spacer(Modifier.height(4.dp))
        Text(
            feeFootnote(quote.tx),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(12.dp))
    notice?.let { FieldText(it, error = false) }
    error?.let { FieldText(it, error = true) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = onCancel, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Cancel") }
        Button(onClick = { if (guard.accepts()) onConfirm() }, enabled = armed && !busy, modifier = Modifier.weight(1f)) {
            if (busy) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp)) else Text("Confirm and send")
        }
    }
}

/** Unlocks the wallet if it has to and hands [q] to the sender, signed by its paying account (as Send's Confirm does). */
private fun confirmSafeCall(
    context: android.content.Context,
    q: SendQuote,
    sender: WalletSender,
    vault: Vault,
    auth: VaultAuthenticator,
    scope: kotlinx.coroutines.CoroutineScope,
    setBusy: (Boolean) -> Unit,
    onStarted: () -> Unit,
    onError: (Throwable) -> Unit,
    onStale: () -> Unit,
) {
    if (sender.isStale(q)) {
        onStale()
        return
    }
    setBusy(true)
    scope.launch {
        var stale = false
        try {
            // A Ledger payer's key is on the Ledger: nothing to unlock here, it's confirmed there (#142).
            if (!q.request.from.isLedger && !vault.unlockedNow()) vault.unlock(auth)
            when (sender.submit(q, WalletSender.signerFor(context, vault, q.request.from) { !sender.isStale(q) })) {
                WalletSender.Submit.STARTED -> onStarted()
                WalletSender.Submit.BUSY -> onError(SafeException("Another send is still going out, or may have. Settle it (or stop tracking it) on the Send page first."))
                WalletSender.Submit.STALE -> stale = true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError(e)
        } finally {
            setBusy(false)
        }
        if (stale) onStale()
    }
}

/** Propose a transaction (an amount of xDAI or a Gnosis token, to an address) or a message, then sign it with this wallet's owners. */
@Composable
private fun SafeProposePage(
    kind: SafePending.Kind,
    safe: SafeAccount,
    accounts: List<WalletAccount>,
    chain: Chain?,
    vault: Vault,
    auth: VaultAuthenticator,
    phraseBackedUp: Boolean,
    onProposed: (SafePending) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val safes = remember(context) { SafeAccounts.get(context) }
    val chainReads = remember(context) { SafeChain.get(context) }
    val scope = rememberCoroutineScope()
    val tokens = remember(chain) { chain?.let { TokenRegistry.tokens(it) }.orEmpty() }
    var tokenKey by remember { mutableStateOf(tokens.firstOrNull()?.key) }
    val token = tokens.firstOrNull { it.key == tokenKey }
    var recipient by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val parsedRecipient = token?.let { Recipients.parse(recipient, it) }
    val parsedAmount = token?.let { SendAmounts.parse(amount, it.decimals) }

    fun propose() {
        busy = true
        error = null
        scope.launch {
            var proposed: SafePending? = null
            try {
                proposed = if (kind == SafePending.Kind.TX) {
                    val c = chain ?: throw SafeException("Gnosis isn’t set up in Settings → Chains.")
                    val t = token ?: return@launch
                    val to = (parsedRecipient as? Recipients.Parsed.Ok)?.address ?: return@launch
                    val value = parsedAmount ?: return@launch
                    val held = if (t.address == null) chainReads.balance(c.id, safe.address) else chainReads.tokenBalance(c.id, t.address, safe.address)
                    if (held < value) {
                        throw SafeException(
                            "The Safe holds ${SendAmounts.exact(held, t.decimals)} ${t.symbol}. Fund it first: send ${t.symbol} to ${safe.address}.",
                        )
                    }
                    val nonce = chainReads.nonce(c.id, safe.address)
                    val tx = if (t.address == null) {
                        SafeProtocol.SafeTx(to, value, ByteArray(0), nonce)
                    } else {
                        SafeProtocol.SafeTx(t.address, BigInteger.ZERO, baby.freedom.mobile.wallet.Erc20.transferData(to, value), nonce)
                    }
                    safes.proposeTx(safe, tx, SafePending.Payment(to, value, t.symbol, t.decimals, t.address))
                } else {
                    safes.proposeMessage(safe, text)
                }
                signWithLocalOwners(proposed, safe, accounts, safes, vault, auth)
                onProposed(proposed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Proposed but not signed here (the unlock was cancelled): it waits on the board all the same.
                if (proposed != null) onProposed(proposed) else error = safeErrorMessage(e, "propose it", phraseBackedUp)
            } finally {
                busy = false
            }
        }
    }

    BackHandler(onBack = onBack)
    FullScreenScaffold(title = if (kind == SafePending.Kind.TX) "Propose a transaction" else "Propose a message", onDismiss = onBack) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item("from") {
                SectionCard(title = "From") {
                    Text(safe.name, fontWeight = FontWeight.Medium)
                    AddressText(safe.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (kind == SafePending.Kind.TX) {
                item("asset") {
                    SectionCard(title = "Asset") {
                        tokens.forEach { t ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                                    .selectable(selected = t.key == tokenKey, enabled = !busy, role = Role.RadioButton) { tokenKey = t.key }
                                    .padding(vertical = 2.dp),
                            ) {
                                RadioButton(selected = t.key == tokenKey, onClick = null)
                                Spacer(Modifier.width(8.dp))
                                Text("${t.symbol} · ${t.name}")
                            }
                        }
                    }
                }
                item("to") {
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
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (recipient.isNotEmpty() && parsedRecipient is Recipients.Parsed.Invalid) FieldText(parsedRecipient.reason, error = true)
                        if (parsedRecipient is Recipients.Parsed.Ok && parsedRecipient.address.equals(safe.address, ignoreCase = true)) {
                            FieldText("That’s the Safe itself.", error = false)
                        }
                    }
                }
                item("amount") {
                    SectionCard(title = "Amount") {
                        OutlinedTextField(
                            value = amount,
                            onValueChange = {
                                amount = it.trim()
                                error = null
                            },
                            enabled = !busy,
                            singleLine = true,
                            suffix = { Text(token?.symbol.orEmpty()) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (amount.isNotEmpty() && parsedAmount == null) {
                            FieldText("Not an amount of ${token?.symbol}: at most ${token?.decimals} digits after the point.", error = true)
                        }
                    }
                }
            } else {
                item("message") {
                    SectionCard(title = "Message") {
                        OutlinedTextField(
                            value = text,
                            onValueChange = {
                                text = it.take(SafeAccounts.MAX_MESSAGE)
                                error = null
                            },
                            enabled = !busy,
                            minLines = 3,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        FieldText(
                            "Signed as personal_sign text by the Safe (EIP-1271): an app checks it with the Safe’s isValidSignature.",
                            error = false,
                        )
                    }
                }
            }
            item("go") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val local = safe.owners.mapNotNull { o -> accounts.firstOrNull { it.address.equals(o, ignoreCase = true) } }
                    FieldText(
                        "Proposing it signs it here with ${local.joinToString(" and ") { it.name }}" +
                            (if (safe.threshold > local.size) ", then it waits for other owners’ signatures." else "."),
                        error = false,
                    )
                    error?.let { FieldText(it, error = true) }
                    val ready = if (kind == SafePending.Kind.TX) parsedRecipient is Recipients.Parsed.Ok && parsedAmount != null else text.isNotEmpty()
                    Button(onClick = ::propose, enabled = !busy && ready, modifier = Modifier.fillMaxWidth()) {
                        if (busy) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp)) else Text("Propose and sign")
                    }
                }
            }
        }
    }
}

/**
 * Signs [p] with each of this wallet's owners of [safe] that hasn't yet, until the threshold is met
 * (desktop's free signatures). Only the seed's: a Ledger owner signs when its Sign is tapped, on the
 * device (#142), not in a run of prompts nobody asked for.
 */
private suspend fun signWithLocalOwners(
    p: SafePending,
    safe: SafeAccount,
    accounts: List<WalletAccount>,
    safes: SafeAccounts,
    vault: Vault,
    auth: VaultAuthenticator,
) {
    val local = safe.owners.mapNotNull { o -> accounts.firstOrNull { !it.isLedger && it.address.equals(o, ignoreCase = true) } }.filterNot { p.hasSigned(it.address) }
    if (local.isEmpty() || p.ready) return
    if (!vault.unlockedNow()) vault.unlock(auth)
    var current = p
    for (account in local) {
        if (current.ready) break
        current = safes.signWith(current.id, account)
    }
}

/**
 * One pending SafeTx or SafeMessage: what it is, who has signed, the
 * request to share with owners on other devices, a field for the
 * signatures they send back — and, once enough owners have signed,
 * Execute (a transaction) or the Safe's signature (a message).
 */
@Composable
private fun SafeRequestPage(
    p: SafePending,
    safe: SafeAccount,
    accounts: List<WalletAccount>,
    chain: Chain?,
    vault: Vault,
    auth: VaultAuthenticator,
    phraseBackedUp: Boolean,
    onOpenUrl: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val safes = remember(context) { SafeAccounts.get(context) }
    val chainReads = remember(context) { SafeChain.get(context) }
    val sender = remember(context) { WalletSender.get(context) }
    val sendStatus by sender.status.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var quote by remember { mutableStateOf<SendQuote?>(null) }
    var pasted by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    // The abandoned executions Discard warns about: those whose account nonce another send hasn't used yet.
    var liveAbandoned by remember(p.abandonedExecs) { mutableStateOf(p.abandonedExecs) }
    val cameraPermission = rememberCameraPermissionState()
    val share = remember(p.id) { p.shareText() }

    fun act(action: String, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = safeErrorMessage(e, action, phraseBackedUp)
            } finally {
                busy = false
            }
        }
    }

    /** The Safe's nonce guard (desktop's): false, and the entry marked, if this transaction can no longer execute. */
    suspend fun stillExecutable(): Boolean {
        val c = chain ?: throw SafeException("Gnosis isn’t set up in Settings → Chains.")
        val onChain = chainReads.nonce(c.id, safe.address)
        val mine = p.safeTx().nonce
        if (onChain > mine) {
            when (safeMovedOn(p) { chainReads.succeeded(c.id, it) }) {
                SafeMovedOn.EXECUTED -> safes.discard(p.id)
                SafeMovedOn.SUPERSEDED -> safes.markSuperseded(p.id)
                SafeMovedOn.UNKNOWN ->
                    throw SafeException("The Safe has moved on; this transaction’s execution isn’t confirmed yet. Try again in a moment.")
            }
            return false
        }
        if (onChain < mine) throw SafeException("An earlier transaction of this Safe (nonce $onChain) hasn’t executed yet.")
        return true
    }

    // A transaction executed elsewhere (or replaced) since it was proposed is told apart on opening.
    LaunchedEffect(p.id, p.execHash) {
        if (p.kind == SafePending.Kind.TX) {
            runCatching {
                if (!p.superseded) {
                    stillExecutable()
                } else if (p.abandonedExecs.isNotEmpty()) {
                    // Marked superseded while an abandoned execution's receipt wasn't known yet (read
                    // from a node a block behind): looked up again, one that did land settles it as executed.
                    val c = chain ?: return@runCatching
                    if (safeMovedOn(p) { chainReads.succeeded(c.id, it) } == SafeMovedOn.EXECUTED) safes.discard(p.id)
                }
            }
        }
        // An execution the sender isn't following any more (Stop tracking in an earlier process,
        // before its end reached this record) isn't going out: Discard and Execute open up again.
        val exec = p.execHash ?: return@LaunchedEffect
        sender.awaitRestored()
        if (sender.status.value?.hash != exec) runCatching { safes.clearExecution(p.id, exec, abandoned = true) }
    }

    fun prepareExecution() = act("price the execution") {
        val c = chain ?: throw SafeException("Gnosis isn’t set up in Settings → Chains.")
        val from = SafeChain.executor(safe, accounts) ?: throw SafeException("None of the Safe’s owners is an account in this wallet to pay for the execution.")
        if (!stillExecutable()) return@act
        val data = SafeProtocol.execTransactionData(p.safeTx(), p.signatures)
        quote = sender.prepare(
            SendRequest(c, TokenRegistry.native(c), from, safe.address, BigInteger.ZERO, DappCall(null, data, null, SafeCallLabel(safe.address, safe.name, activates = false))),
        )
    }

    val back = {
        if (quote != null) quote = null else onBack()
    }
    BackHandler(onBack = back)
    FullScreenScaffold(title = if (p.kind == SafePending.Kind.TX) "Safe transaction" else "Safe message", onDismiss = back) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item("what") {
                SectionCard(title = safePendingTitle(p)) {
                    ReviewRow("From", safe.name, address = safe.address)
                    if (p.kind == SafePending.Kind.TX) {
                        val tx = remember(p.id) { p.safeTx() }
                        p.payment?.let { pay ->
                            ReviewRow("To", null, address = pay.recipient)
                            ReviewRow("Amount", "${SendAmounts.exact(pay.amount, pay.decimals)} ${pay.symbol}", mono = true, address = pay.token)
                        }
                        ReviewRow("Safe nonce", tx.nonce.toString())
                        ReviewRow("SafeTx hash", p.id, mono = true)
                    } else {
                        ReviewRow("Text", p.text.orEmpty())
                        ReviewRow("SafeMessage hash", p.id, mono = true)
                    }
                    if (p.superseded) {
                        FieldText(
                            "The Safe’s nonce has moved past this transaction — it was executed elsewhere, or another took its place — so it can never execute. Discard it.",
                            error = true,
                        )
                    }
                }
            }
            item("owners") {
                SectionCard(title = "${p.collected} of ${p.threshold} signatures") {
                    safe.owners.forEach { owner ->
                        val signed = p.hasSigned(owner)
                        val mine = accounts.firstOrNull { it.address.equals(owner, ignoreCase = true) }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Icon(
                                if (signed) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                                contentDescription = if (signed) "Signed" else "Not signed",
                                tint = if (signed) (if (MaterialTheme.colorScheme.isLight) Color(0xFF15803D) else Color(0xFF22C55E)) else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(safeOwnerName(owner, accounts), style = MaterialTheme.typography.bodyMedium)
                                AddressText(owner, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    when {
                                        signed -> "Signed"
                                        p.ready -> "Not needed: enough owners have signed"
                                        mine != null -> if (mine.isLedger) "Signs on its Ledger" else "Signs on this phone"
                                        else -> "Waiting: share the request below"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            // Not once it's ready: Execute sends exactly the signatures it has, and
                            // an extra one would change the calldata the mined execution is matched by.
                            if (!signed && mine != null && !p.ready && !p.superseded) {
                                TextButton(
                                    onClick = {
                                        act("sign") {
                                            if (!mine.isLedger && !vault.unlockedNow()) vault.unlock(auth)
                                            safes.signWith(p.id, mine)
                                        }
                                    },
                                    enabled = !busy,
                                ) { Text("Sign") }
                            }
                        }
                    }
                }
            }
            val status = sendStatus?.takeIf { it.quote.request.dapp?.safe?.let { l -> !l.activates && l.address.equals(safe.address, ignoreCase = true) } == true }
            val q = quote
            when {
                q != null -> item("review") {
                    SafeCallReview(
                        quote = q,
                        what = "Executes the Safe’s transaction with its owners’ ${p.collected} signatures: ${safePendingTitle(p)}.",
                        busy = busy,
                        notice = notice,
                        error = error,
                        onCancel = { quote = null },
                        onConfirm = {
                            confirmSafeCall(context, q, sender, vault, auth, scope,
                                setBusy = { busy = it },
                                onStarted = { quote = null; notice = null },
                                onError = { error = safeErrorMessage(it, "unlock the wallet", phraseBackedUp) },
                                onStale = { prepareExecution(); notice = "The fee estimate was over a minute old, so it’s been priced again. Check it and confirm." },
                            )
                        },
                    )
                }
                status != null -> item("send") {
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
                p.kind == SafePending.Kind.TX && p.ready && !p.superseded -> item("execute") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        error?.let { FieldText(it, error = true) }
                        Button(onClick = { prepareExecution() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                            if (busy) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp)) else Text("Execute")
                        }
                        FieldText(
                            "Paid by ${SafeChain.executor(safe, accounts)?.name ?: "an owner account in this wallet"}: the network fee comes from that account, the payment from the Safe.",
                            error = false,
                        )
                    }
                }
            }
            if (p.kind == SafePending.Kind.MESSAGE && p.ready) item("signature") {
                SectionCard(title = "The Safe’s signature") {
                    SelectionContainer { Text(p.combinedSignature(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                    FieldText(
                        "The owners’ signatures, sorted and joined (EIP-1271). An app verifies it by calling isValidSignature on the Safe with the message’s hash.",
                        error = false,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { copyToClipboard(context, p.combinedSignature()) }) { Text("Copy signature") }
                    }
                }
            }
            if (!p.ready && !p.superseded) {
                item("share") {
                    SectionCard(title = "Ask another owner to sign") {
                        if (qrBytes(share) <= SAFE_QR_MAX) {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                QrCodeImage(share, "QR code of the signing request", Modifier.widthIn(max = 320.dp).fillMaxWidth())
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                        Text(
                            "On another phone with Freedom: Wallet → Scan QR code, then sign with its owner account. " +
                                "It’s the typed data desktop Freedom signs for its Safes (eth_signTypedData_v4), so any wallet that signs typed data can sign it." +
                                (if (qrBytes(share) > SAFE_QR_MAX) " It’s too long for a QR code: copy it instead." else ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { copyToClipboard(context, share) }) { Text("Copy request") }
                        }
                    }
                }
                item("add") {
                    SectionCard(title = "Add a signature") {
                        OutlinedTextField(
                            value = pasted,
                            onValueChange = {
                                pasted = it.trim()
                                error = null
                            },
                            enabled = !busy,
                            singleLine = true,
                            placeholder = { Text("0x… signature from another owner") },
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (quote == null) error?.let { FieldText(it, error = true) }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { scanning = !scanning }, enabled = !busy) { Text(if (scanning) "Stop scanning" else "Scan") }
                            TextButton(
                                onClick = {
                                    val sig = pasted
                                    act("add the signature") {
                                        safes.addSignature(p.id, sig)
                                        pasted = ""
                                    }
                                },
                                enabled = !busy && pasted.isNotBlank(),
                            ) { Text("Add") }
                        }
                        if (scanning) {
                            QrScanner(
                                permission = cameraPermission,
                                onCode = { text ->
                                    if (SafeProtocol.normalized(text) != null) {
                                        scanning = false
                                        pasted = text.trim()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)),
                            )
                        }
                        FieldText("It’s checked before it counts: it must be an owner’s signature of exactly this request.", error = false)
                    }
                }
            }
            item("discard") {
                TextButton(onClick = { confirmDiscard = true }, enabled = !busy && p.execHash == null, modifier = Modifier.fillMaxWidth()) {
                    Text("Discard…", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    LaunchedEffect(confirmDiscard, p.abandonedExecs) {
        if (!confirmDiscard || p.kind != SafePending.Kind.TX) return@LaunchedEffect
        val c = chain ?: return@LaunchedEffect
        val mined = HashMap<String, BigInteger?>()
        liveAbandoned = p.abandonedExecs.filter { a ->
            val from = a.from ?: return@filter true
            val nonce = a.nonce ?: return@filter true
            val count = mined.getOrPut(from.lowercase()) { runCatching { chainReads.minedCount(c.id, from) }.getOrNull() }
            // Unknown (the read failed): keep warning.
            !safeAbandonedSpent(nonce, count)
        }
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard this ${if (p.kind == SafePending.Kind.TX) "transaction" else "message"}?") },
            text = {
                Text(safeDiscardText(p, liveAbandoned))
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    act("discard it") {
                        safes.discard(p.id)
                        onBack()
                    }
                }) { Text("Discard", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep it") } },
        )
    }
}

internal enum class SafeMovedOn { EXECUTED, SUPERSEDED, UNKNOWN }

/**
 * The Safe's nonce is past pending [p]'s: did one of this wallet's own
 * executions of it do that ([succeeded]: an execution's receipt status,
 * null while unknown)?
 */
internal suspend fun safeMovedOn(p: SafePending, succeeded: suspend (String) -> Boolean?): SafeMovedOn {
    val exec = p.execHash
    // An execution given up on (Stop tracking) that landed anyway — any of them, not just the
    // latest: a later one reusing the account nonce can lose to an earlier one. Done, not superseded.
    if (exec == null && p.abandonedExecs.any { succeeded(it.hash) == true }) return SafeMovedOn.EXECUTED
    return when (exec?.let { succeeded(it) }) {
        true -> SafeMovedOn.EXECUTED
        // Our execution's receipt isn't known yet (a node a block behind the one that gave
        // the nonce): not "executed elsewhere" until it's known to have failed, or it's
        // no longer going out (the sender's outcome, or Stop tracking, settles that).
        null -> if (exec != null) SafeMovedOn.UNKNOWN else SafeMovedOn.SUPERSEDED
        false -> SafeMovedOn.SUPERSEDED
    }
}

/**
 * An abandoned execution sent with account nonce [nonce] can no longer be
 * mined once [minedCount] of its account's transactions are (another send
 * used that nonce — or it did, and the nonce guard settles the entry).
 * Null [minedCount] (not known): it still can.
 */
internal fun safeAbandonedSpent(nonce: BigInteger, minedCount: BigInteger?): Boolean = minedCount != null && minedCount > nonce

/**
 * What Discard throws away, and what it can't stop. [live]: the abandoned
 * executions that can still be mined (all of them unless told otherwise).
 */
internal fun safeDiscardText(p: SafePending, live: List<SafePending.AbandonedExec> = p.abandonedExecs): String = buildString {
    append("The signatures collected here are thrown away. A signature already given to another device stays valid there ")
    append(if (p.kind == SafePending.Kind.TX) "until the Safe executes another transaction with this nonce." else "for this exact message.")
    if (p.kind == SafePending.Kind.TX && live.isNotEmpty()) {
        val hashes = live.joinToString(", ") { "${it.hash.take(10)}…" }
        append(
            if (live.size == 1) {
                "\n\nThe execution you stopped tracking ($hashes) can still be mined and make this payment: " +
                    "discarding doesn’t stop it. The next send from the account that paid for it reuses its nonce and replaces it."
            } else {
                "\n\nThe executions you stopped tracking ($hashes) can still be mined, and any one of them makes this payment: " +
                    "discarding doesn’t stop them. Sends from the account that paid for them reuse their nonces and replace them."
            },
        )
    }
}

/**
 * Co-sign a Safe request another owner's device shared (scanned or
 * pasted): what it does, checked against the Safe on chain — its owners,
 * and for a transaction its nonce — and a signature from this wallet's
 * owner account to hand back, as a QR code or text.
 */
@Composable
internal fun SafeCoSignPage(
    raw: String,
    accounts: List<WalletAccount>,
    chains: List<Chain>,
    vault: Vault,
    auth: VaultAuthenticator,
    phraseBackedUp: Boolean,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val chainReads = remember(context) { SafeChain.get(context) }
    val safes = remember(context) { SafeAccounts.get(context) }
    val scope = rememberCoroutineScope()
    val parsed = remember(raw) { runCatching { SafeProtocol.parseRequest(raw) } }
    val request = parsed.getOrNull()
    val chain = request?.let { r -> chains.firstOrNull { it.id == r.chainId } }
    var owners by remember { mutableStateOf<List<String>?>(null) }
    var nonce by remember { mutableStateOf<BigInteger?>(null) }
    var readError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var signature by remember { mutableStateOf<Pair<WalletAccount, String>?>(null) }
    // A call from the Safe to itself changes the Safe (owners, threshold, modules, guard,
    // fallback handler): decoded and warned about, and signed only once acknowledged.
    val selfCall = request?.let(::safeSelfCall)
    var selfCallAcknowledged by remember(raw) { mutableStateOf(false) }
    val guard = remember(raw) { PromptTapGuard(SystemClock::uptimeMillis) }
    var armed by remember(raw) { mutableStateOf(false) }
    LaunchedEffect(raw) {
        withFrameNanos { }
        guard.onShown()
        delay(guard.remainingMs())
        armed = true
    }
    LaunchedEffect(request, chain) {
        val r = request ?: return@LaunchedEffect
        val c = chain ?: return@LaunchedEffect
        try {
            if (!chainReads.deployed(c.id, r.safe)) {
                readError = "This Safe isn’t active on ${c.name}, so who owns it can’t be checked. Freedom only co-signs for active Safes."
                return@LaunchedEffect
            }
            owners = chainReads.owners(c.id, r.safe)
            if (r is SafeProtocol.Request.Tx) nonce = chainReads.nonce(c.id, r.safe)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            readError = safeErrorMessage(e, "read the Safe", phraseBackedUp)
        }
    }

    BackHandler(onBack = onBack)
    FullScreenScaffold(title = "Co-sign for a Safe", onDismiss = onBack) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (request == null) {
                item("bad") {
                    SectionCard(title = "Can’t use this request") {
                        Text(parsed.exceptionOrNull()?.message ?: "This isn’t a Safe signing request.", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                return@LazyColumn
            }
            item("what") {
                SectionCard(title = if (request is SafeProtocol.Request.Tx) "Transaction" else "Message") {
                    if (selfCall != null && !selfCall.harmless) SafeSelfCallWarning(selfCall)
                    ReviewRow("Safe", null, address = request.safe)
                    ReviewRow("Network", chain?.name ?: "Chain ID ${request.chainId}", detail = if (chain == null) "Not one of your networks." else null)
                    when (request) {
                        is SafeProtocol.Request.Tx -> {
                            val tx = request.tx
                            val token = TokenRegistry.builtins.firstOrNull { it.chainId == request.chainId && it.address.equals(tx.to, ignoreCase = true) }
                            val transfer = token?.let { erc20Transfer(tx.data) }
                            if (selfCall != null) {
                                SafeSelfCallRows(selfCall, tx, chain, owners)
                            } else if (transfer != null) {
                                ReviewRow("Sends", "${SendAmounts.exact(transfer.second, token.decimals)} ${token.symbol}", mono = true, address = token.address)
                                ReviewRow("To", null, address = transfer.first)
                                // Everything signed is shown: a token transfer that also carries native currency says so.
                                if (tx.value.signum() != 0) {
                                    ReviewRow(
                                        "Also sends",
                                        chain?.let { "${SendAmounts.exact(tx.value, it.decimals)} ${it.symbol}" } ?: "${tx.value} base units",
                                        mono = true,
                                        detail = "Native currency to the token contract, on top of the transfer.",
                                    )
                                }
                            } else {
                                ReviewRow("To", null, address = tx.to)
                                ReviewRow(
                                    "Amount",
                                    chain?.let { "${SendAmounts.exact(tx.value, it.decimals)} ${it.symbol}" } ?: "${tx.value} base units",
                                    mono = true,
                                )
                                if (tx.data.isNotEmpty()) HexRow("Data", "0x" + tx.data.toHex(), selector = true, detail = "A contract call: check what it does before signing.")
                            }
                            ReviewRow(
                                "Safe nonce",
                                tx.nonce.toString(),
                                detail = nonce?.let { n ->
                                    when {
                                        n > tx.nonce -> "The Safe is already past this nonce: this transaction can never execute."
                                        n < tx.nonce -> "The Safe’s next nonce is $n: earlier transactions must execute first."
                                        else -> "The Safe’s next transaction."
                                    }
                                },
                            )
                        }
                        is SafeProtocol.Request.Message -> ReviewRow("Text", request.text)
                    }
                    ReviewRow(if (request is SafeProtocol.Request.Tx) "SafeTx hash" else "SafeMessage hash", "0x" + request.hash.toHex(), mono = true)
                }
            }
            item("sign") {
                SectionCard(title = "Sign") {
                    val ownersNow = owners
                    val mine = ownersNow?.let { list -> accounts.filter { a -> list.any { it.equals(a.address, ignoreCase = true) } } }.orEmpty()
                    val outdated = request is SafeProtocol.Request.Tx && nonce != null && nonce!! > request.tx.nonce
                    val s = signature
                    when {
                        s != null -> {
                            Text("Signed with ${s.first.name}. Show this to the owner collecting signatures, or copy it to them:", style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(8.dp))
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                QrCodeImage(s.second, "QR code of the signature", Modifier.widthIn(max = 280.dp).fillMaxWidth())
                            }
                            SelectionContainer { Text(s.second, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { copyToClipboard(context, s.second) }) { Text("Copy signature") }
                            }
                        }
                        chain == null -> Text("Freedom co-signs Safes on your networks only.", style = MaterialTheme.typography.bodyMedium)
                        readError != null -> FieldText(readError!!, error = true)
                        ownersNow == null -> Text("Checking the Safe’s owners…", style = MaterialTheme.typography.bodyMedium)
                        mine.isEmpty() -> Text("None of this wallet’s accounts owns this Safe, so it can’t sign for it.", style = MaterialTheme.typography.bodyMedium)
                        outdated -> Text("This transaction can no longer execute, so there’s nothing to sign.", style = MaterialTheme.typography.bodyMedium)
                        else -> {
                            error?.let { FieldText(it, error = true) }
                            if (selfCall != null && !selfCall.harmless) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .toggleable(
                                            value = selfCallAcknowledged,
                                            enabled = !busy,
                                            role = Role.Checkbox,
                                            onValueChange = { selfCallAcknowledged = it },
                                        ),
                                ) {
                                    Checkbox(checked = selfCallAcknowledged, onCheckedChange = null, enabled = !busy)
                                    Spacer(Modifier.width(8.dp))
                                    Text("I understand this changes the Safe itself, and I trust it", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            mine.forEach { account ->
                                Button(
                                    onClick = {
                                        if (!guard.accepts() || busy || (selfCall != null && !selfCall.harmless && !selfCallAcknowledged)) return@Button
                                        busy = true
                                        error = null
                                        scope.launch {
                                            try {
                                                if (!account.isLedger && !vault.unlockedNow()) vault.unlock(auth)
                                                val sig = safes.ownerSignature(account, request.typedData.toString())
                                                signature = account to sig
                                            } catch (e: CancellationException) {
                                                throw e
                                            } catch (e: Exception) {
                                                error = safeErrorMessage(e, "sign", phraseBackedUp)
                                            } finally {
                                                busy = false
                                            }
                                        }
                                    },
                                    enabled = armed && !busy && (selfCall == null || selfCall.harmless || selfCallAcknowledged),
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("Sign with ${account.name}") }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The red box on top of a co-sign request that changes the Safe itself: what it changes, and what that can cost. */
@Composable
private fun SafeSelfCallWarning(call: SafeSelfCall) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.ErrorOutline, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("This changes the Safe itself", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(4.dp))
            Text(safeSelfCallRisk(call), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Why [call] matters, for the warning. */
internal fun safeSelfCallRisk(call: SafeSelfCall): String = when (call) {
    is SafeSelfCall.AddOwner ->
        "It adds a new owner, who can then sign for this Safe. Together with the threshold it sets, that can give someone else control of everything in it."
    is SafeSelfCall.RemoveOwner -> "It removes an owner, who can then no longer sign for this Safe, and sets how many owners must sign."
    is SafeSelfCall.SwapOwner -> "It replaces an owner with another address, which can then sign for this Safe in its place."
    is SafeSelfCall.ChangeThreshold -> "It changes how many owners must sign a transaction. Too low, and fewer owners — maybe one — can move everything in the Safe."
    is SafeSelfCall.EnableModule -> "It adds a module. A module can move anything in the Safe with no owner signatures at all."
    is SafeSelfCall.DisableModule -> "It removes a module from the Safe."
    is SafeSelfCall.SetGuard -> "It sets the Safe’s transaction guard. A guard checks every transaction the Safe executes, and a bad one can block them all for good."
    is SafeSelfCall.SetFallbackHandler ->
        "It sets the Safe’s fallback handler, which answers calls the Safe itself doesn’t know — including whether a signature is valid for it. A bad one can approve messages no owner signed."
    SafeSelfCall.Unknown -> "It calls one of the Safe’s own functions that Freedom can’t read. It may change who controls the Safe."
    SafeSelfCall.Cancel -> "It does nothing but use up its nonce."
}

/** The decoded rows of a SafeTx from the Safe to itself; [owners] (on chain, if read) to count the threshold against. */
@Composable
private fun SafeSelfCallRows(call: SafeSelfCall, tx: SafeProtocol.SafeTx, chain: Chain?, owners: List<String>?) {
    val amount = chain?.let { "${SendAmounts.exact(tx.value, it.decimals)} ${it.symbol}" } ?: "${tx.value} base units"
    fun threshold(t: BigInteger, ownersAfter: Int?): String? = ownersAfter?.let { n ->
        when {
            t.signum() == 0 || t > BigInteger.valueOf(n.toLong()) -> "Not possible with $n owners: this transaction would fail."
            t == BigInteger.ONE && n > 1 -> "Any one of $n owners alone can then move everything in the Safe."
            else -> "$t of $n owners must then sign."
        }
    }
    fun none(address: String) = if (address.equals(SafeProtocol.ZERO_ADDRESS, ignoreCase = true)) "None" else null
    when (call) {
        SafeSelfCall.Cancel -> {
            ReviewRow("Does", "Nothing: cancels", detail = "A call from the Safe to itself with no data. It only uses up Safe nonce ${tx.nonce}, so no other transaction with that nonce can execute.")
        }
        is SafeSelfCall.AddOwner -> {
            ReviewRow("Changes", "Adds an owner")
            ReviewRow("New owner", null, address = call.owner)
            ReviewRow("Threshold", call.threshold.toString(), mono = true, detail = threshold(call.threshold, owners?.size?.plus(1)))
        }
        is SafeSelfCall.RemoveOwner -> {
            ReviewRow("Changes", "Removes an owner")
            ReviewRow("Removed owner", null, address = call.owner)
            ReviewRow("Threshold", call.threshold.toString(), mono = true, detail = threshold(call.threshold, owners?.size?.minus(1)))
        }
        is SafeSelfCall.SwapOwner -> {
            ReviewRow("Changes", "Replaces an owner")
            ReviewRow("Removed owner", null, address = call.old)
            ReviewRow("New owner", null, address = call.new)
        }
        is SafeSelfCall.ChangeThreshold -> {
            ReviewRow("Changes", "The threshold")
            ReviewRow("Threshold", call.threshold.toString(), mono = true, detail = threshold(call.threshold, owners?.size))
        }
        is SafeSelfCall.EnableModule -> {
            ReviewRow("Changes", "Adds a module")
            ReviewRow("Module", null, address = call.module)
        }
        is SafeSelfCall.DisableModule -> {
            ReviewRow("Changes", "Removes a module")
            ReviewRow("Module", null, address = call.module)
        }
        is SafeSelfCall.SetGuard -> {
            ReviewRow("Changes", "The transaction guard")
            ReviewRow("Guard", none(call.guard), address = call.guard.takeIf { none(it) == null })
        }
        is SafeSelfCall.SetFallbackHandler -> {
            ReviewRow("Changes", "The fallback handler")
            ReviewRow("Fallback handler", none(call.handler), address = call.handler.takeIf { none(it) == null })
        }
        SafeSelfCall.Unknown -> ReviewRow("Changes", "Unknown", detail = "A call to this Safe’s own functions that Freedom can’t read.")
    }
    ReviewRow("To", null, address = tx.to, detail = "This Safe itself.")
    ReviewRow("Amount", amount, mono = true)
    // Everything signed is shown: the raw call data under what was read from it.
    if (tx.data.isNotEmpty()) HexRow("Data", "0x" + tx.data.toHex(), selector = true, detail = if (call == SafeSelfCall.Unknown) "Check what it does before signing." else "The call data decoded above.")
}

/** `transfer(to, amount)` call data → (to, amount), or null for anything else. */
internal fun erc20Transfer(data: ByteArray): Pair<String, BigInteger>? {
    if (data.size != 68) return null
    val hex = data.toHex()
    if (!hex.startsWith("a9059cbb") || hex.substring(8, 32).any { it != '0' }) return null
    return SafeProtocol.eip55("0x" + hex.substring(32, 72)) to BigInteger(hex.substring(72), 16)
}

/**
 * What a SafeTx whose `to` is the Safe itself does: the Safe's own
 * owner/module/guard/handler settings, which only such a call can change —
 * so signing one can hand the Safe to someone else. Decoded exactly
 * (selector, argument count, clean address words) or not at all.
 */
internal sealed interface SafeSelfCall {
    /** No call data: does nothing but use up its nonce (how a pending transaction is cancelled). */
    data object Cancel : SafeSelfCall
    data class AddOwner(val owner: String, val threshold: BigInteger) : SafeSelfCall
    data class RemoveOwner(val owner: String, val threshold: BigInteger) : SafeSelfCall
    data class SwapOwner(val old: String, val new: String) : SafeSelfCall
    data class ChangeThreshold(val threshold: BigInteger) : SafeSelfCall
    data class EnableModule(val module: String) : SafeSelfCall
    data class DisableModule(val module: String) : SafeSelfCall
    data class SetGuard(val guard: String) : SafeSelfCall
    data class SetFallbackHandler(val handler: String) : SafeSelfCall
    /** Anything else: a call into the Safe's own code this wallet can't read. */
    data object Unknown : SafeSelfCall

    /** Changes nothing about who controls the Safe or how. */
    val harmless: Boolean get() = this == Cancel
}

/** [request]'s call from its Safe to that Safe itself, decoded; null for a message or a call elsewhere. */
internal fun safeSelfCall(request: SafeProtocol.Request): SafeSelfCall? =
    (request as? SafeProtocol.Request.Tx)?.takeIf { it.tx.to.equals(it.safe, ignoreCase = true) }?.let { safeSelfCall(it.tx.data) }

/** [data] of a call from a Safe to itself, decoded ([SafeSelfCall]). */
internal fun safeSelfCall(data: ByteArray): SafeSelfCall {
    if (data.isEmpty()) return SafeSelfCall.Cancel
    if (data.size < 4 || (data.size - 4) % 32 != 0) return SafeSelfCall.Unknown
    val hex = data.toHex()
    val words = (0 until (data.size - 4) / 32).map { hex.substring(8 + 64 * it, 72 + 64 * it) }
    fun address(i: Int): String? = words[i].takeIf { w -> w.substring(0, 24).all { it == '0' } }?.let { SafeProtocol.eip55("0x" + it.substring(24)) }
    fun uint(i: Int): BigInteger = BigInteger(words[i], 16)
    fun args(n: Int) = words.size == n
    val call = when (hex.substring(0, 8)) {
        SAFE_SELECTORS["addOwnerWithThreshold"] -> if (args(2)) address(0)?.let { SafeSelfCall.AddOwner(it, uint(1)) } else null
        SAFE_SELECTORS["removeOwner"] -> if (args(3) && address(0) != null) address(1)?.let { SafeSelfCall.RemoveOwner(it, uint(2)) } else null
        SAFE_SELECTORS["swapOwner"] -> if (args(3) && address(0) != null) {
            val old = address(1)
            val new = address(2)
            if (old != null && new != null) SafeSelfCall.SwapOwner(old, new) else null
        } else {
            null
        }
        SAFE_SELECTORS["changeThreshold"] -> if (args(1)) SafeSelfCall.ChangeThreshold(uint(0)) else null
        SAFE_SELECTORS["enableModule"] -> if (args(1)) address(0)?.let { SafeSelfCall.EnableModule(it) } else null
        SAFE_SELECTORS["disableModule"] -> if (args(2) && address(0) != null) address(1)?.let { SafeSelfCall.DisableModule(it) } else null
        SAFE_SELECTORS["setGuard"] -> if (args(1)) address(0)?.let { SafeSelfCall.SetGuard(it) } else null
        SAFE_SELECTORS["setFallbackHandler"] -> if (args(1)) address(0)?.let { SafeSelfCall.SetFallbackHandler(it) } else null
        else -> null
    }
    return call ?: SafeSelfCall.Unknown
}

/** The Safe's own admin functions, by name → 4-byte selector (hex). */
internal val SAFE_SELECTORS: Map<String, String> = listOf(
    "addOwnerWithThreshold(address,uint256)",
    "removeOwner(address,address,uint256)",
    "swapOwner(address,address,address)",
    "changeThreshold(uint256)",
    "enableModule(address)",
    "disableModule(address,address)",
    "setGuard(address)",
    "setFallbackHandler(address)",
).associate { it.substringBefore('(') to Keccak256.digest(it.toByteArray(Charsets.US_ASCII)).copyOfRange(0, 4).toHex() }

@Composable
private fun FieldText(text: String, error: Boolean) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}
