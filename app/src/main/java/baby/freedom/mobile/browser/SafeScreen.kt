package baby.freedom.mobile.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import baby.freedom.mobile.R
import baby.freedom.mobile.chains.Chain
import baby.freedom.mobile.chains.rpc.ChainRpcException
import baby.freedom.mobile.ens.Keccak256
import baby.freedom.mobile.ens.toHex
import baby.freedom.mobile.l10n.Strings
import baby.freedom.mobile.l10n.pluralText
import baby.freedom.mobile.ui.isLight
import baby.freedom.mobile.wallet.DappCall
import baby.freedom.mobile.wallet.Eip712
import baby.freedom.mobile.wallet.Recipients
import baby.freedom.mobile.wallet.SafeAccount
import baby.freedom.mobile.wallet.SafeAccounts
import baby.freedom.mobile.wallet.SafeCallLabel
import baby.freedom.mobile.wallet.SafeChain
import baby.freedom.mobile.wallet.SafePolicyChange
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
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * A shared request longer than this many bytes ([qrBytes], UTF-8) makes a
 * QR code too dense for a phone camera: Copy only. Bytes, not characters,
 * so a long message in CJK or emoji can't overflow the code's capacity.
 */
internal const val SAFE_QR_MAX = 1_800

/** Who [address] is, among a Safe's owners: one of this wallet's accounts by name, or another device's. */
internal fun safeOwnerName(address: String, accounts: List<WalletAccount>): String =
    accounts.firstOrNull { it.address.equals(address, ignoreCase = true) }?.name ?: Strings.get(R.string.safe_owner_another_device)

/** "2 of 3 owners" — how many must sign, of how many (as the Safe has it now, as far as this phone knows). */
internal fun safePolicy(safe: SafeAccount): String =
    Strings.plural(R.plurals.safe_policy, safe.ownersNow.size, safe.thresholdNow, safe.ownersNow.size)

/** What a request page's read of the Safe's owners and threshold found (#341). */
internal sealed interface SafePolicyCheck {
    data object Checking : SafePolicyCheck

    /** Confirmed on chain, and the record brought in line with it. */
    data object Confirmed : SafePolicyCheck

    /** Not read or not confirmed: Execute and a message's signature stay off, and [reason] says why. */
    data class Failed(val reason: String) : SafePolicyCheck
}

/** What to tell the user when the chain's owners or threshold differed from the record (#341), in plain words. */
internal fun safePolicyChangeNotice(change: SafePolicyChange): String = buildList {
    if (change.thresholdChanged) add(Strings.plural(R.plurals.safe_policy_now_needs, change.thresholdNow, change.thresholdNow))
    if (change.ownersChanged) add(Strings.get(R.string.safe_policy_owners_changed))
    if (change.droppedSignatures > 0) add(Strings.plural(R.plurals.safe_policy_signatures_dropped, change.droppedSignatures, change.droppedSignatures))
}.joinToString(" ")

/** What a pending item is, for its row and heading: the payment, or the message's words. */
internal fun safePendingTitle(p: SafePending): String = when (p.kind) {
    SafePending.Kind.TX -> p.payment?.let { Strings.get(R.string.safe_pending_title_send, SendAmounts.exact(it.amount, it.decimals), it.symbol) }
        ?: Strings.get(R.string.safe_pending_title_tx)
    SafePending.Kind.MESSAGE -> Strings.get(R.string.safe_pending_title_message, p.shownText.orEmpty())
}

/**
 * A pending item's hero line (W34): "Send 1 xDAI to 0xd8dA…6045 from
 * Team Safe", a message's words, or a transaction of [safeName].
 */
internal fun safeRequestHeadline(p: SafePending, safeName: String): String = when (p.kind) {
    SafePending.Kind.TX -> p.payment?.let {
        Strings.get(R.string.safe_headline_send, SendAmounts.exact(it.amount, it.decimals), it.symbol, shortAddress(it.recipient), safeName)
    } ?: Strings.get(R.string.safe_headline_tx, safeName)
    SafePending.Kind.MESSAGE -> Strings.get(R.string.safe_headline_message, safeName, p.shownText.orEmpty())
}

/** Where a pending item stands, with its signature count. */
internal fun safePendingState(p: SafePending): String {
    val count = Strings.plural(R.plurals.safe_signature_count, p.threshold, p.collected, p.threshold)
    return when {
        p.kind == SafePending.Kind.TX && p.superseded -> Strings.get(R.string.safe_pending_state_superseded, count)
        p.kind == SafePending.Kind.TX && p.execHash != null -> Strings.get(R.string.safe_pending_state_executing, count)
        p.kind == SafePending.Kind.TX && p.ready -> Strings.get(R.string.safe_pending_state_ready, count)
        p.ready -> Strings.get(R.string.safe_pending_state_signed, count)
        else -> count
    }
}

/** The Safe's row on the wallet page: policy, and whether it's active or what waits. */
internal fun safeRowSubtitle(safe: SafeAccount, pending: List<SafePending>): String {
    val parts = mutableListOf(safePolicy(safe))
    if (!safe.deployed) parts += Strings.get(R.string.safe_row_not_active)
    val tx = pending.count { it.kind == SafePending.Kind.TX }
    val messages = pending.count { it.kind == SafePending.Kind.MESSAGE }
    if (tx > 0) parts += Strings.plural(R.plurals.safe_row_transactions_waiting, tx, tx)
    if (messages > 0) parts += Strings.plural(R.plurals.safe_row_messages_waiting, messages, messages)
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
    SectionCard(title = stringResource(R.string.safe_accounts_title)) {
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
            title = stringResource(R.string.safe_create_title),
            subtitle = stringResource(R.string.safe_create_row_subtitle),
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
                owners.any { it.equals(code.address, ignoreCase = true) } -> error = Strings.get(R.string.safe_create_already_owner)
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
            else -> error = Strings.get(R.string.safe_create_not_an_address)
        }
    }

    BackHandler(onBack = onBack)
    FullScreenScaffold(title = stringResource(R.string.safe_create_title), onDismiss = onBack) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item("preset") {
                SectionCard(title = stringResource(R.string.safe_create_who_must_sign)) {
                    for ((t, title, detail) in listOf(
                        Triple(1, pluralText(R.plurals.safe_create_preset, 2, 1, 2), stringResource(R.string.safe_create_preset_1of2_detail)),
                        Triple(2, pluralText(R.plurals.safe_create_preset, 3, 2, 3), stringResource(R.string.safe_create_preset_2of3_detail)),
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
                                .heightIn(min = 56.dp)
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
                    // Why the option someone may look for is missing, so its absence doesn't read as an oversight.
                    FieldText(stringResource(R.string.safe_create_no_2of2), error = false)
                }
            }
            item("local") {
                SectionCard(title = stringResource(R.string.safe_create_local_owners)) {
                    accounts.forEach { account ->
                        val checked = local.any { it.equals(account.address, ignoreCase = true) }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .toggleable(value = checked, enabled = !busy && (checked || owners.size < needed), role = Role.Checkbox) {
                                    local = if (checked) local.filterNot { a -> a.equals(account.address, ignoreCase = true) } else local + account.address
                                }
                                .heightIn(min = 56.dp)
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
                }
            }
            item("others") {
                SectionCard(title = stringResource(R.string.safe_create_other_owners)) {
                    others.forEach { address ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            AddressText(
                                address,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { others = others - address }, enabled = !busy) { Text(stringResource(R.string.common_remove)) }
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
                            placeholder = { Text(stringResource(R.string.safe_create_other_placeholder)) },
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (otherParsed is ScannedCode.Unrecognized) FieldText(otherParsed.reason, error = true)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { scanningOther = !scanningOther }, enabled = !busy) { Text(stringResource(if (scanningOther) R.string.safe_stop_scanning else R.string.safe_scan)) }
                            TextButton(onClick = { addOther(otherInput) }, enabled = !busy && otherParsed is ScannedCode.Address) { Text(stringResource(R.string.common_add)) }
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
                }
            }
            item("name") {
                SectionCard(title = stringResource(R.string.safe_create_name)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(SafeAccounts.MAX_NAME) },
                        enabled = !busy,
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.safe_default_name, ((SafeAccounts.get(context).state.value?.safes?.size) ?: 0) + 1)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item("create") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.safe_create_footnote),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    error?.let { FieldText(it, error = true) }
                    val blocker = safeCreateBlocker(local.size, owners.size, needed)
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
                                    error = safeErrorMessage(e, Strings.get(R.string.safe_action_create), phraseBackedUp = true)
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        enabled = !busy && blocker == null,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        when {
                            busy -> BusySpinner(stringResource(R.string.safe_creating))
                            else -> Text(safeCreateLabel(blocker), textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
    }
}

/** Why Create can't go yet (W36): no owner from this wallet, or [more] owners still to choose. */
internal sealed interface SafeCreateBlocker {
    data object NoLocalOwner : SafeCreateBlocker
    data class ChooseMore(val more: Int) : SafeCreateBlocker
}

/**
 * Why a Safe with [local] of this wallet's accounts among [owners] owners
 * can't be created yet, needing [needed]; null once it can. An owner from
 * this wallet comes first: it signs here and pays to activate the Safe,
 * so without one even a full set of owners is no use.
 */
internal fun safeCreateBlocker(local: Int, owners: Int, needed: Int): SafeCreateBlocker? = when {
    local == 0 -> SafeCreateBlocker.NoLocalOwner
    owners < needed -> SafeCreateBlocker.ChooseMore(needed - owners)
    else -> null
}

/** Create's label: what's still missing ([safeCreateBlocker]), or Create. */
internal fun safeCreateLabel(blocker: SafeCreateBlocker?): String = when (blocker) {
    SafeCreateBlocker.NoLocalOwner -> Strings.get(R.string.safe_create_need_local)
    is SafeCreateBlocker.ChooseMore -> Strings.plural(R.plurals.safe_create_choose_more, blocker.more, blocker.more)
    null -> Strings.get(R.string.safe_create_button)
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
            checkError = safeErrorMessage(e, Strings.get(R.string.safe_action_read), phraseBackedUp)
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
                error = safeErrorMessage(e, Strings.get(R.string.safe_action_price_activation), phraseBackedUp)
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
    val hasTx = pending.any { it.kind == SafePending.Kind.TX }
    val groups = safePendingGroups(pending, safe, accounts)
    FullScreenScaffold(title = stringResource(R.string.safe_account_section), onDismiss = back) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item("header") {
                SafeHeader(
                    safe = safe,
                    chain = chain,
                    holdings = holdings,
                    checkError = checkError,
                    checking = checking,
                    sendBlocked = safeSendBlocked(safe, chain != null, hasTx),
                    showReceive = showQr,
                    onRefresh = { refreshTick++ },
                    onSend = { proposing = SafePending.Kind.TX },
                    onReceive = { showQr = !showQr },
                )
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
                        headline = stringResource(R.string.safe_activation_headline, safe.name, q.request.chain.name),
                        detail = stringResource(R.string.safe_activation_what),
                        busy = busy,
                        notice = notice,
                        error = error,
                        onOpenUrl = onOpenUrl,
                        onCancel = {
                            quote = null
                            notice = null
                            error = null
                        },
                        onConfirm = {
                            confirmSafeCall(context, q, sender, vault, auth, scope,
                                setBusy = { busy = it },
                                onStarted = { quote = null; notice = null },
                                onError = { error = safeErrorMessage(it, Strings.get(R.string.safe_action_unlock), phraseBackedUp) },
                                onStale = { prepareActivation(); notice = Strings.get(R.string.safe_fee_stale_notice) },
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
            if (groups.needsYou.isNotEmpty()) item("needs-you") {
                SectionCard(title = stringResource(R.string.safe_needs_your_signature)) {
                    groups.needsYou.forEach { p -> SafePendingRow(p) { openId = p.id } }
                }
            }
            item("pending") {
                SectionCard(title = stringResource(if (groups.needsYou.isEmpty()) R.string.safe_waiting_title else R.string.safe_waiting_others_title)) {
                    if (groups.others.isEmpty()) {
                        Text(
                            stringResource(if (groups.needsYou.isEmpty()) R.string.safe_nothing_waiting else R.string.safe_nothing_else_waiting),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    groups.others.forEach { p -> SafePendingRow(p) { openId = p.id } }
                    PageRow(
                        title = stringResource(R.string.safe_propose_message),
                        subtitle = stringResource(if (safe.deployed) R.string.safe_propose_message_subtitle else R.string.safe_propose_once_active),
                        style = PageRowStyle.Inset,
                        leadingIcon = Icons.Filled.Add,
                        enabled = safe.deployed,
                        onClick = { proposing = SafePending.Kind.MESSAGE },
                    )
                }
            }
            item("about") {
                SectionCard(title = stringResource(R.string.safe_about_title)) {
                    Text(safePolicy(safe), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(
                            R.string.safe_account_summary,
                            SafeProtocol.VERSION,
                            chain?.name ?: stringResource(R.string.safe_chain_fallback, safe.chainId.toString()),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    HorizontalDivider()
                    safe.ownersNow.forEach { owner ->
                        CopyableAddressRow(
                            label = stringResource(R.string.safe_owner_label),
                            address = owner,
                            name = safeOwnerName(owner, accounts),
                            explorerUrl = chain?.let { explorerAddressUrl(it, owner) },
                            onOpenUrl = onOpenUrl,
                        )
                    }
                    CopyableAddressRow(
                        label = stringResource(R.string.safe_address_label),
                        address = safe.address,
                        explorerUrl = chain?.let { explorerAddressUrl(it, safe.address) },
                        onOpenUrl = onOpenUrl,
                    )
                }
            }
            item("remove") {
                TextButton(onClick = { confirmRemove = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.safe_remove_button), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.safe_remove_title, safe.name)) },
            text = {
                Text(stringResource(R.string.safe_remove_body))
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    scope.launch { runCatching { safes.remove(safe.address) } }
                }) { Text(stringResource(R.string.common_remove), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.safe_keep_it)) } },
        )
    }
}

/** A Safe page's pending items, split by whether this wallet has something to do on them. */
internal data class SafePendingGroups(val needsYou: List<SafePending>, val others: List<SafePending>)

/**
 * Whether [p] waits on this wallet (W33): an owner among [accounts] still
 * has to sign it, or it's a transaction with enough signatures that one
 * of them can execute. Not one that can never execute, or that's
 * already going out.
 */
internal fun safeNeedsYou(p: SafePending, safe: SafeAccount, accounts: List<WalletAccount>): Boolean {
    if (p.superseded) return false
    val mine = safe.ownersNow.filter { o -> accounts.any { it.address.equals(o, ignoreCase = true) } }
    return when {
        mine.isEmpty() -> false
        p.ready -> p.kind == SafePending.Kind.TX && p.execHash == null
        else -> mine.any { !p.hasSigned(it) }
    }
}

/** [pending], oldest first, those that wait on this wallet ([safeNeedsYou]) apart from the rest. */
internal fun safePendingGroups(pending: List<SafePending>, safe: SafeAccount, accounts: List<WalletAccount>): SafePendingGroups {
    val (needsYou, others) = pending.sortedBy { it.createdAt }.partition { safeNeedsYou(it, safe, accounts) }
    return SafePendingGroups(needsYou, others)
}

/** Why the Safe page's Send can't start a transaction now, or null when it can. */
internal fun safeSendBlocked(safe: SafeAccount, chainKnown: Boolean, hasTx: Boolean): String? = when {
    !chainKnown -> Strings.get(R.string.safe_gnosis_not_set_up)
    !safe.deployed -> Strings.get(R.string.safe_send_once_active)
    hasTx -> Strings.get(R.string.safe_propose_tx_one_at_a_time)
    else -> null
}

/** One pending item's row: what it is, where it stands, who it pays. */
@Composable
private fun SafePendingRow(p: SafePending, onClick: () -> Unit) {
    PageRow(
        title = safePendingTitle(p),
        subtitle = safePendingState(p),
        thirdLine = p.payment?.let { stringResource(R.string.safe_pending_to, it.recipient) },
        style = PageRowStyle.Inset,
        leadingIcon = Icons.Filled.Draw,
        onClick = onClick,
    )
}

/**
 * The top of a Safe's page (W33): its name and policy, what it holds —
 * every digit, a Safe funded with a few wei must not read as
 * "<0.00000001" — and its two main actions, Send (propose a transaction)
 * and Receive (its address as a QR code, with Copy and Share).
 */
@Composable
private fun SafeHeader(
    safe: SafeAccount,
    chain: Chain?,
    holdings: List<Pair<Token, BigInteger>>?,
    checkError: String?,
    checking: Boolean,
    sendBlocked: String?,
    showReceive: Boolean,
    onRefresh: () -> Unit,
    onSend: () -> Unit,
    onReceive: () -> Unit,
) {
    val context = LocalContext.current
    val reading = stringResource(R.string.safe_reading)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp),
    ) {
        Text(safe.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
        Text(safePolicy(safe), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.safe_balance_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                when {
                    chain == null -> Text(stringResource(R.string.safe_gnosis_not_set_up), style = MaterialTheme.typography.bodyMedium)
                    holdings == null && checkError != null -> FieldText(checkError, error = true)
                    holdings == null -> Text(reading, style = MaterialTheme.typography.bodyMedium)
                    else -> {
                        holdings.filter { it.first.isNative }.forEach { (token, raw) ->
                            Text("${exactAmount(raw, token.decimals)} ${token.symbol}", style = MaterialTheme.typography.headlineSmall)
                        }
                        holdings.filterNot { it.first.isNative }.forEach { (token, raw) ->
                            Text("${exactAmount(raw, token.decimals)} ${token.symbol}", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
            IconButton(onClick = onRefresh, enabled = !checking && chain != null) {
                if (checking) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp).semantics { contentDescription = reading })
                } else {
                    Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.safe_refresh))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onSend, enabled = sendBlocked == null, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.safe_send))
            }
            OutlinedButton(onClick = onReceive, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Icon(Icons.Filled.QrCode, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.safe_receive))
            }
        }
        sendBlocked?.let { FieldText(it, error = false) }
        if (showReceive) {
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                QrCodeImage(safe.address, stringResource(R.string.safe_qr_address_description), Modifier.widthIn(max = 280.dp).fillMaxWidth())
            }
            Spacer(Modifier.height(8.dp))
            SelectionContainer {
                AddressText(safe.address, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
            FieldText(
                stringResource(R.string.safe_receive_hint, chain?.name ?: stringResource(R.string.safe_chain_fallback, safe.chainId.toString())),
                error = false,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { copyToClipboard(context, safe.address) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.common_copy_address))
                }
                val chainName = chain?.name ?: stringResource(R.string.safe_chain_fallback, safe.chainId.toString())
                // With the "only on <network>" line: the payer sees only what's shared, and the Safe exists on its own network alone.
                TextButton(onClick = { shareAddress(context, safeShareText(safe.address, chainName)) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.safe_share))
                }
            }
        }
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
    SectionCard(title = stringResource(R.string.safe_not_active_title)) {
        when {
            chain == null -> Text(stringResource(R.string.safe_activation_no_chain), style = MaterialTheme.typography.bodyMedium)
            executor == null -> Text(stringResource(R.string.safe_activation_no_executor), style = MaterialTheme.typography.bodyMedium)
            activation == null && checkError != null -> FieldText(checkError, error = true)
            activation == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.safe_activation_pricing), style = MaterialTheme.typography.bodyMedium)
            }
            activation.needsFunds -> {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = amber, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.safe_activation_needs_funds_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                        Text(
                            stringResource(
                                R.string.safe_activation_needs_funds,
                                feeText(activation.maxFee, chain),
                                executor.name,
                                feeText(activation.balance, chain),
                                feeText(activation.shortfall, chain),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                SelectionContainer { AddressText(executor.address, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { onCopy(executor.address) }) { Text(stringResource(R.string.safe_copy_account_address, executor.name)) }
                    TextButton(onClick = onRefresh, enabled = !checking) { Text(stringResource(if (checking) R.string.safe_checking else R.string.safe_check_again)) }
                }
            }
            else -> {
                Text(
                    stringResource(
                        R.string.safe_activation_ready,
                        chain.name,
                        feeText(activation.maxFee, chain),
                        executor.name,
                        feeText(activation.balance, chain),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                error?.let { FieldText(it, error = true) }
                Button(onClick = onActivate, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    if (busy) BusySpinner(stringResource(R.string.safe_activation_pricing)) else Text(stringResource(R.string.safe_activate_on, chain.name))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.safe_activation_footnote),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The review for a call the wallet composed for a Safe — its activation or
 * an `execTransaction` — before anything is signed (W35): [headline] (what
 * it does, in a sentence), the most the fee can be, and who pays it, with
 * Copy and the explorer; [detail] (what the call does in more words), the
 * network, the contract, the nonce and gas under Details. Confirm ignores
 * taps for the first moment it's on screen ([PromptTapGuard]).
 */
@Composable
internal fun SafeCallReview(
    quote: SendQuote,
    headline: String,
    detail: String,
    busy: Boolean,
    notice: String?,
    error: String?,
    onOpenUrl: (String) -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    sub: String? = null,
) {
    val request = quote.request
    val chain = request.chain
    val tap = rememberArmedTapGuard(quote, PromptTapGuard.SPEND_PROTECTION_MS)
    val guard = tap.guard
    val armed = tap.armed
    SectionCard(title = stringResource(R.string.safe_review)) {
        TxReviewSummary(
            headline = headline,
            fee = stringResource(R.string.safe_review_fee_up_to, feeText(quote.maxFee, chain)),
        ) {
            sub?.let { FieldText(it, error = false) }
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        CopyableAddressRow(
            label = stringResource(R.string.safe_review_paid_by),
            address = request.from.address,
            name = request.from.name,
            explorerUrl = explorerAddressUrl(chain, request.from.address),
            onOpenUrl = onOpenUrl,
        )
        replacesWarning(quote)?.let { Warning(it) }
        DetailsExpander {
            ReviewRow(stringResource(R.string.safe_review_what), detail)
            ReviewRow(stringResource(R.string.safe_label_network), chain.name)
            CopyableAddressRow(
                label = stringResource(R.string.safe_review_contract),
                address = request.to,
                explorerUrl = explorerAddressUrl(chain, request.to),
                onOpenUrl = onOpenUrl,
            )
            ReviewRow(stringResource(R.string.safe_review_nonce), quote.tx.nonce.toString(), mono = true, detail = nonceDetail(quote))
            ReviewRow(stringResource(R.string.safe_review_gas), feeDetail(quote))
            Text(
                feeFootnote(quote.tx),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
    Spacer(Modifier.height(12.dp))
    notice?.let { FieldText(it, error = false) }
    error?.let { FieldText(it, error = true) }
    ObscuredTapNotice(tap)
    val sending = stringResource(R.string.safe_sending)
    SheetButtonRow {
        OutlinedButton(onClick = onCancel, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.common_cancel)) }
        Button(
            onClick = { if (guard.accepts()) onConfirm() },
            enabled = armed && !busy,
            modifier = Modifier.heightIn(min = 48.dp).protectedPress(tap),
        ) {
            if (busy) BusySpinner(sending) else Text(stringResource(R.string.safe_confirm_and_send))
        }
    }
}

/** A busy button's spinner, named so TalkBack says what's happening rather than "Button, disabled" (W50). */
@Composable
private fun BusySpinner(label: String) {
    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp).semantics { contentDescription = label })
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
                WalletSender.Submit.BUSY -> onError(SafeException(Strings.get(R.string.safe_send_busy)))
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
    var recipientNote by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    val cameraPermission = rememberCameraPermissionState()
    // What the Safe holds of each asset, read once the page opens: the amount is checked against it as it's typed.
    var held by remember { mutableStateOf<Map<String, BigInteger>>(emptyMap()) }
    var heldError by remember { mutableStateOf(false) }
    if (kind == SafePending.Kind.TX) {
        LaunchedEffect(chain) {
            val c = chain ?: return@LaunchedEffect
            try {
                held = tokens.associate { t ->
                    t.key to if (t.address == null) chainReads.balance(c.id, safe.address) else chainReads.tokenBalance(c.id, t.address, safe.address)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                heldError = true
            }
        }
    }
    val available = token?.let { held[it.key] }
    val amountProblem = safeAmountProblem(amount, parsedAmount, available)

    fun takeRecipient(text: String) {
        recipientNote = null
        when (val code = ScannedCode.parse(text.trim())) {
            is ScannedCode.Address -> recipient = code.address
            is ScannedCode.Unrecognized -> recipient = text.trim()
            else -> recipientNote = Strings.get(R.string.safe_scan_not_address)
        }
        error = null
    }

    fun propose() {
        busy = true
        error = null
        scope.launch {
            var proposed: SafePending? = null
            try {
                // The owners and threshold the Safe has now (#341), so the item counts the right signatures and
                // isn't signed by an owner that's gone. Best effort: the request page checks again before it counts.
                chain?.let { c ->
                    try {
                        chainReads.policy(c.id, safe.address).takeIf { it.confirmed }?.let { safes.applyOnChain(safe.address, it.owners, it.threshold) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                    }
                }
                val current = safes.state.value?.safe(safe.address) ?: safe
                proposed = if (kind == SafePending.Kind.TX) {
                    val c = chain ?: throw SafeException(Strings.get(R.string.safe_gnosis_not_set_up))
                    val t = token ?: return@launch
                    val to = (parsedRecipient as? Recipients.Parsed.Ok)?.address ?: return@launch
                    val value = parsedAmount ?: return@launch
                    val held = if (t.address == null) chainReads.balance(c.id, safe.address) else chainReads.tokenBalance(c.id, t.address, safe.address)
                    if (held < value) {
                        throw SafeException(
                            Strings.get(R.string.safe_propose_insufficient, SendAmounts.exact(held, t.decimals), t.symbol, safe.address),
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
                signWithLocalOwners(proposed, current, accounts, safes, vault, auth)
                onProposed(proposed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Proposed but not signed here (the unlock was cancelled): it waits on the board all the same.
                if (proposed != null) onProposed(proposed) else error = safeErrorMessage(e, Strings.get(R.string.safe_action_propose), phraseBackedUp)
            } finally {
                busy = false
            }
        }
    }

    BackHandler(onBack = onBack)
    FullScreenScaffold(title = stringResource(if (kind == SafePending.Kind.TX) R.string.safe_propose_tx else R.string.safe_propose_message), onDismiss = onBack) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item("from") {
                SectionCard(title = stringResource(R.string.safe_label_from)) {
                    Text(safe.name, fontWeight = FontWeight.Medium)
                    AddressText(safe.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (kind == SafePending.Kind.TX) {
                item("asset") {
                    SectionCard(title = stringResource(R.string.safe_propose_asset)) {
                        tokens.forEach { t ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                                    .selectable(selected = t.key == tokenKey, enabled = !busy, role = Role.RadioButton) { tokenKey = t.key }
                                    .heightIn(min = 56.dp)
                                    .padding(vertical = 4.dp),
                            ) {
                                RadioButton(selected = t.key == tokenKey, onClick = null)
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("${t.symbol} · ${t.name}")
                                    held[t.key]?.let {
                                        Text(
                                            stringResource(R.string.safe_available, exactAmount(it, t.decimals), t.symbol),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                item("to") {
                    SectionCard(title = stringResource(R.string.safe_label_to)) {
                        OutlinedTextField(
                            value = recipient,
                            onValueChange = {
                                recipient = it.trim()
                                recipientNote = null
                                error = null
                            },
                            enabled = !busy,
                            singleLine = true,
                            placeholder = { Text(stringResource(R.string.safe_address_placeholder)) },
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
                            trailingIcon = {
                                Row {
                                    IconButton(
                                        enabled = !busy,
                                        onClick = {
                                            val clip = recipientClip(context)
                                            val pasted = clip?.let { safePastedRecipient(pastedRecipient(it.label, it.sensitive, it.text), it.text?.trim()?.take(MAX_PASTED_RECIPIENT), safe.chainId) }
                                            when (pasted) {
                                                is SafePaste.Take -> takeRecipient(pasted.text)
                                                is SafePaste.Note -> recipientNote = pasted.reason
                                                null -> Unit
                                            }
                                        },
                                    ) { Icon(Icons.Filled.ContentPaste, contentDescription = stringResource(R.string.send_paste)) }
                                    IconButton(enabled = !busy, onClick = { scanning = !scanning }) {
                                        Icon(
                                            Icons.Filled.QrCodeScanner,
                                            contentDescription = stringResource(if (scanning) R.string.safe_stop_scanning else R.string.send_scan),
                                        )
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        recipientNote?.let { FieldText(it, error = true) }
                        if (scanning) {
                            Spacer(Modifier.height(8.dp))
                            QrScanner(
                                permission = cameraPermission,
                                onCode = { text ->
                                    // Read as a pasted one is: an address (or a bare request for one) goes in; any other request is told why.
                                    when (val read = safeScannedRecipient(text, safe.chainId)) {
                                        is SafePaste.Take -> {
                                            scanning = false
                                            takeRecipient(read.text)
                                        }
                                        is SafePaste.Note -> recipientNote = read.reason
                                        null -> Unit
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)),
                            )
                        }
                        if (recipient.isNotEmpty() && parsedRecipient is Recipients.Parsed.Invalid) FieldText(parsedRecipient.reason, error = true)
                        // The field scrolls a long address out of view: here it is whole.
                        if (parsedRecipient is Recipients.Parsed.Ok) {
                            Spacer(Modifier.height(6.dp))
                            AddressText(parsedRecipient.address, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        }
                        if (parsedRecipient is Recipients.Parsed.Ok && parsedRecipient.address.equals(safe.address, ignoreCase = true)) {
                            FieldText(stringResource(R.string.safe_recipient_is_safe), error = false)
                        }
                    }
                }
                item("amount") {
                    SectionCard(title = stringResource(R.string.safe_label_amount)) {
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
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                when {
                                    token == null -> ""
                                    available != null -> stringResource(R.string.safe_available, exactAmount(available, token.decimals), token.symbol)
                                    heldError -> stringResource(R.string.safe_available_unknown)
                                    else -> stringResource(R.string.safe_reading)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = {
                                    if (token != null && available != null) {
                                        amount = SendAmounts.exact(available, token.decimals)
                                        error = null
                                    }
                                },
                                enabled = !busy && available != null && available.signum() > 0,
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) { Text(stringResource(R.string.safe_max)) }
                        }
                        when (amountProblem) {
                            SafeAmountProblem.INVALID -> FieldText(
                                ambiguousAmountNote(amount)
                                    ?: stringResource(R.string.safe_amount_invalid, token?.symbol.toString(), token?.decimals ?: 0),
                                error = true,
                            )
                            SafeAmountProblem.ZERO -> FieldText(stringResource(R.string.safe_amount_zero), error = true)
                            SafeAmountProblem.TOO_MUCH -> FieldText(
                                stringResource(R.string.safe_amount_too_much, exactAmount(available ?: BigInteger.ZERO, token?.decimals ?: 0), token?.symbol.orEmpty()),
                                error = true,
                            )
                            null -> Unit
                        }
                    }
                }
            } else {
                item("message") {
                    SectionCard(title = stringResource(R.string.safe_label_message)) {
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
                            stringResource(R.string.safe_message_hint),
                            error = false,
                        )
                    }
                }
            }
            item("go") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val local = safe.ownersNow.mapNotNull { o -> accounts.firstOrNull { it.address.equals(o, ignoreCase = true) } }
                    val names = local.map { it.name }.reduceOrNull { a, b -> Strings.get(R.string.safe_names_and, a, b) }.orEmpty()
                    FieldText(
                        stringResource(if (safe.thresholdNow > local.size) R.string.safe_propose_signs_here_then_waits else R.string.safe_propose_signs_here, names),
                        error = false,
                    )
                    error?.let { FieldText(it, error = true) }
                    val ready = if (kind == SafePending.Kind.TX) {
                        parsedRecipient is Recipients.Parsed.Ok && parsedAmount != null && amountProblem == null
                    } else {
                        text.isNotEmpty()
                    }
                    Button(onClick = ::propose, enabled = !busy && ready, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        if (busy) BusySpinner(stringResource(R.string.safe_proposing)) else Text(stringResource(R.string.safe_propose_and_sign))
                    }
                }
            }
        }
    }
}

/** What's wrong with the amount typed on the Propose page, as it's typed (W37). */
internal enum class SafeAmountProblem { INVALID, ZERO, TOO_MUCH }

/**
 * [typed] read as [parsed] (null: not an amount), against what the Safe
 * holds ([available], null until read). Null when it's fine, or empty.
 */
internal fun safeAmountProblem(typed: String, parsed: BigInteger?, available: BigInteger?): SafeAmountProblem? = when {
    typed.isEmpty() -> null
    parsed == null && typed.any { it.isDigit() } && typed.all { it == '0' || it == '.' || it == ',' } && typed.count { it == '.' || it == ',' } <= 1 -> SafeAmountProblem.ZERO
    parsed == null -> SafeAmountProblem.INVALID
    available != null && parsed > available -> SafeAmountProblem.TOO_MUCH
    else -> null
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
    val local = safe.ownersNow.mapNotNull { o -> accounts.firstOrNull { !it.isLedger && it.address.equals(o, ignoreCase = true) } }.filterNot { p.hasSigned(it.address) }
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
    // The Safe's owners and threshold as its contract has them (#341): Execute and a message's
    // signature wait for them, and a change since the record was written is said in plain words.
    var policyCheck by remember(p.id) { mutableStateOf<SafePolicyCheck>(SafePolicyCheck.Checking) }
    var policyNotice by remember(p.id) { mutableStateOf<String?>(null) }
    var policyTick by remember(p.id) { mutableStateOf(0) }
    val cameraPermission = rememberCameraPermissionState()
    val share = remember(p.id) { p.shareText() }
    // The owners' Sign buttons produce a signature (maybe the one that completes the threshold):
    // guarded like co-sign's Sign. Re-armed whenever a signature lands, since the rows' labels
    // and buttons change under the finger then.
    val tap = rememberArmedTapGuard(p.id to p.collected, PromptTapGuard.SPEND_PROTECTION_MS)

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

    /**
     * Reads the Safe's owners, threshold and nonce from its contract (#341) and brings the record in
     * line: pending items take the threshold, signatures from former owners stop counting. Throws
     * [SafeException] saying why Execute (or a message's signature) stays off when the read fails or
     * isn't confirmed — a lone server's word isn't enough to count signatures by, or to rewrite the record.
     */
    suspend fun checkPolicy(): SafeChain.Policy {
        policyCheck = SafePolicyCheck.Checking
        val tx = p.kind == SafePending.Kind.TX
        try {
            val c = chain ?: throw SafeException(Strings.get(R.string.safe_gnosis_not_set_up))
            val policy = try {
                chainReads.policy(c.id, safe.address)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                throw SafeException(Strings.get(if (tx) R.string.safe_policy_read_failed_tx else R.string.safe_policy_read_failed_message))
            }
            if (!policy.confirmed) throw SafeException(Strings.get(if (tx) R.string.safe_policy_unconfirmed_tx else R.string.safe_policy_unconfirmed_message))
            safes.applyOnChain(safe.address, policy.owners, policy.threshold)?.let { policyNotice = safePolicyChangeNotice(it) }
            policyCheck = SafePolicyCheck.Confirmed
            return policy
        } catch (e: SafeException) {
            policyCheck = SafePolicyCheck.Failed(e.message.orEmpty())
            throw e
        }
    }

    /** The Safe's nonce guard (desktop's): false, and the entry marked, if this transaction can no longer execute. */
    suspend fun stillExecutable(known: BigInteger? = null): Boolean {
        val c = chain ?: throw SafeException(Strings.get(R.string.safe_gnosis_not_set_up))
        val onChain = known ?: chainReads.nonce(c.id, safe.address)
        val mine = p.safeTx().nonce
        if (onChain > mine) {
            when (safeMovedOn(p) { chainReads.succeeded(c.id, it) }) {
                SafeMovedOn.EXECUTED -> safes.discard(p.id)
                SafeMovedOn.SUPERSEDED -> safes.markSuperseded(p.id)
                SafeMovedOn.UNKNOWN ->
                    throw SafeException(Strings.get(R.string.safe_moved_on_unconfirmed))
            }
            return false
        }
        if (onChain < mine) throw SafeException(Strings.get(R.string.safe_earlier_pending, onChain.toString()))
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

    // Checked on opening, and again with Check again; Execute reads it afresh as well.
    LaunchedEffect(p.id, policyTick) {
        if (!p.superseded) runCatching { checkPolicy() }
    }

    fun prepareExecution() = act(Strings.get(R.string.safe_action_price_execution)) {
        val c = chain ?: throw SafeException(Strings.get(R.string.safe_gnosis_not_set_up))
        // Signatures are counted against the Safe's owners and threshold on chain, not the record's (#341):
        // a stale threshold would offer an execution that reverts (GS020) at the executor's expense.
        val policy = checkPolicy()
        if (!stillExecutable(policy.nonce)) return@act
        // The entry as the read above left it: it may need more signatures now.
        val entry = safes.state.value?.pending?.firstOrNull { it.id == p.id } ?: throw SafeException(Strings.get(R.string.safe_error_discarded))
        if (entry.countedBy(policy.owners) < policy.threshold) {
            // The page now shows it waiting for signatures, and the notice says why.
            if (policyNotice != null) return@act
            throw SafeException(Strings.plural(R.plurals.safe_policy_now_needs, policy.threshold, policy.threshold))
        }
        val current = safes.state.value?.safe(safe.address) ?: safe
        val from = SafeChain.executor(current, accounts) ?: throw SafeException(Strings.get(R.string.safe_no_executor_for_execution))
        val data = SafeProtocol.execTransactionData(entry.safeTx(), entry.signatures)
        quote = sender.prepare(
            SendRequest(c, TokenRegistry.native(c), from, safe.address, BigInteger.ZERO, DappCall(null, data, null, SafeCallLabel(safe.address, safe.name, activates = false))),
        )
    }

    val back = {
        if (quote != null) quote = null else onBack()
    }
    BackHandler(onBack = back)
    FullScreenScaffold(title = stringResource(if (p.kind == SafePending.Kind.TX) R.string.safe_request_title_tx else R.string.safe_request_title_message), onDismiss = back) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item("what") {
                SectionCard(title = stringResource(if (p.kind == SafePending.Kind.TX) R.string.safe_label_transaction else R.string.safe_label_message)) {
                    TxReviewSummary(headline = safeRequestHeadline(p, safe.name), fee = null) {
                        FieldText(safePendingState(p), error = false)
                    }
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    if (p.kind == SafePending.Kind.TX) {
                        p.payment?.let { pay ->
                            CopyableAddressRow(
                                label = stringResource(R.string.safe_label_to),
                                address = pay.recipient,
                                explorerUrl = chain?.let { explorerAddressUrl(it, pay.recipient) },
                                onOpenUrl = onOpenUrl,
                            )
                        }
                    } else {
                        ReviewRow(stringResource(R.string.safe_label_text), p.shownText.orEmpty())
                    }
                    if (p.superseded) {
                        FieldText(
                            stringResource(R.string.safe_superseded_note),
                            error = true,
                            announce = false,
                        )
                    }
                    policyNotice?.let { FieldText(it, error = false, announce = true) }
                    DetailsExpander {
                        CopyableAddressRow(
                            label = stringResource(R.string.safe_label_from),
                            address = safe.address,
                            name = safe.name,
                            explorerUrl = chain?.let { explorerAddressUrl(it, safe.address) },
                            onOpenUrl = onOpenUrl,
                        )
                        if (p.kind == SafePending.Kind.TX) {
                            val tx = remember(p.id) { p.safeTx() }
                            p.payment?.let { pay ->
                                ReviewRow(stringResource(R.string.safe_label_amount), "${SendAmounts.exact(pay.amount, pay.decimals)} ${pay.symbol}", mono = true)
                                pay.token?.let {
                                    CopyableAddressRow(
                                        label = stringResource(R.string.safe_label_token_contract, pay.symbol),
                                        address = it,
                                        explorerUrl = chain?.let { c -> explorerAddressUrl(c, it) },
                                        onOpenUrl = onOpenUrl,
                                    )
                                }
                            }
                            ReviewRow(stringResource(R.string.safe_label_safe_nonce), tx.nonce.toString(), mono = true)
                            CopyableAddressRow(stringResource(R.string.safe_label_safetx_hash), p.id)
                        } else {
                            CopyableAddressRow(stringResource(R.string.safe_label_safemessage_hash), p.id)
                        }
                        Text(
                            stringResource(R.string.safe_request_format_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
            if (!p.ready && !p.superseded) item("share") {
                SectionCard(title = stringResource(R.string.safe_share_title)) {
                    val fits = qrBytes(share) <= SAFE_QR_MAX
                    if (fits) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            QrCodeImage(share, stringResource(R.string.safe_qr_request_description), Modifier.widthIn(max = 320.dp).fillMaxWidth())
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    Text(
                        stringResource(if (fits) R.string.safe_share_hint else R.string.safe_share_hint_too_long),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { shareAddress(context, share) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.safe_share_request))
                    }
                    TextButton(
                        onClick = { copyToClipboard(context, share) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.safe_copy_request)) }
                }
            }
            item("owners") {
                SectionCard(title = pluralText(R.plurals.safe_signature_count, p.threshold, p.collected, p.threshold)) {
                    ObscuredTapNotice(tap)
                    safe.ownersNow.forEach { owner ->
                        val signed = p.hasSigned(owner)
                        val mine = accounts.firstOrNull { it.address.equals(owner, ignoreCase = true) }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 4.dp)) {
                            Icon(
                                if (signed) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                                contentDescription = stringResource(if (signed) R.string.safe_signed else R.string.safe_not_signed),
                                tint = if (signed) (if (MaterialTheme.colorScheme.isLight) Color(0xFF15803D) else Color(0xFF22C55E)) else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(safeOwnerName(owner, accounts), style = MaterialTheme.typography.bodyMedium)
                                AddressText(owner, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    when {
                                        signed -> stringResource(R.string.safe_signed)
                                        p.ready -> stringResource(R.string.safe_owner_not_needed)
                                        mine != null -> stringResource(if (mine.isLedger) R.string.safe_owner_signs_on_ledger else R.string.safe_owner_signs_here)
                                        else -> stringResource(R.string.safe_owner_waiting)
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
                                        if (!tap.guard.accepts()) return@TextButton
                                        act(Strings.get(R.string.safe_action_sign)) {
                                            if (!mine.isLedger && !vault.unlockedNow()) vault.unlock(auth)
                                            safes.signWith(p.id, mine)
                                        }
                                    },
                                    enabled = tap.armed && !busy,
                                    modifier = Modifier.protectedPress(tap),
                                ) { Text(stringResource(R.string.safe_sign)) }
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
                        headline = safeRequestHeadline(p, safe.name),
                        detail = pluralText(R.plurals.safe_execute_what, p.collected, p.collected, safePendingTitle(p)),
                        sub = stringResource(R.string.safe_execute_sub),
                        busy = busy,
                        notice = notice,
                        error = error,
                        onOpenUrl = onOpenUrl,
                        onCancel = { quote = null },
                        onConfirm = {
                            confirmSafeCall(context, q, sender, vault, auth, scope,
                                setBusy = { busy = it },
                                onStarted = { quote = null; notice = null },
                                onError = { error = safeErrorMessage(it, Strings.get(R.string.safe_action_unlock), phraseBackedUp) },
                                onStale = { prepareExecution(); notice = Strings.get(R.string.safe_fee_stale_notice) },
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
                        val check = policyCheck
                        if (check is SafePolicyCheck.Failed) {
                            // Not offered while the Safe's owners and threshold aren't confirmed (#341).
                            FieldText(check.reason, error = true)
                            OutlinedButton(
                                onClick = { error = null; policyTick++ },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            ) { Text(stringResource(R.string.safe_policy_check_again)) }
                            return@Column
                        }
                        error?.let { FieldText(it, error = true) }
                        Button(onClick = { prepareExecution() }, enabled = !busy && check == SafePolicyCheck.Confirmed, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            when {
                                busy -> BusySpinner(stringResource(R.string.safe_pricing))
                                check == SafePolicyCheck.Checking -> Text(stringResource(R.string.safe_policy_checking))
                                else -> Text(stringResource(R.string.safe_execute))
                            }
                        }
                        FieldText(
                            SafeChain.executor(safe, accounts)?.name?.let { stringResource(R.string.safe_execute_paid_by, it) }
                                ?: stringResource(R.string.safe_execute_paid_by_any),
                            error = false,
                        )
                    }
                }
            }
            if (p.kind == SafePending.Kind.MESSAGE && p.ready) item("signature") {
                SectionCard(title = stringResource(R.string.safe_signature_title)) {
                    // Only once the signatures are counted against the Safe's owners and threshold on
                    // chain (#341): otherwise the Safe could reject the signature it's given as.
                    when (val check = policyCheck) {
                        SafePolicyCheck.Confirmed -> {
                            SelectionContainer { Text(p.combinedSignature(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                            FieldText(
                                stringResource(R.string.safe_signature_hint),
                                error = false,
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { copyToClipboard(context, p.combinedSignature()) }) { Text(stringResource(R.string.safe_copy_signature)) }
                            }
                        }
                        SafePolicyCheck.Checking -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp)) {
                            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(stringResource(R.string.safe_policy_checking), style = MaterialTheme.typography.bodyMedium)
                        }
                        is SafePolicyCheck.Failed -> {
                            FieldText(check.reason, error = true)
                            OutlinedButton(
                                onClick = { policyTick++ },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            ) { Text(stringResource(R.string.safe_policy_check_again)) }
                        }
                    }
                }
            }
            if (!p.ready && !p.superseded) {
                item("add") {
                    SectionCard(title = stringResource(R.string.safe_add_signature_title)) {
                        OutlinedTextField(
                            value = pasted,
                            onValueChange = {
                                pasted = it.trim()
                                error = null
                            },
                            enabled = !busy,
                            singleLine = true,
                            placeholder = { Text(stringResource(R.string.safe_signature_placeholder)) },
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (quote == null) error?.let { FieldText(it, error = true) }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { scanning = !scanning }, enabled = !busy) { Text(stringResource(if (scanning) R.string.safe_stop_scanning else R.string.safe_scan)) }
                            TextButton(
                                onClick = {
                                    val sig = pasted
                                    act(Strings.get(R.string.safe_action_add_signature)) {
                                        safes.addSignature(p.id, sig)
                                        pasted = ""
                                    }
                                },
                                enabled = !busy && pasted.isNotBlank(),
                            ) { Text(stringResource(R.string.common_add)) }
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
                        FieldText(stringResource(R.string.safe_signature_checked_hint), error = false)
                    }
                }
            }
            item("discard") {
                TextButton(onClick = { confirmDiscard = true }, enabled = !busy && p.execHash == null, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.safe_discard_button), color = MaterialTheme.colorScheme.error)
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
            title = { Text(stringResource(if (p.kind == SafePending.Kind.TX) R.string.safe_discard_title_tx else R.string.safe_discard_title_message)) },
            text = {
                Text(safeDiscardText(p, liveAbandoned))
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    act(Strings.get(R.string.safe_action_discard)) {
                        safes.discard(p.id)
                        onBack()
                    }
                }) { Text(stringResource(R.string.safe_discard), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.safe_keep_it)) } },
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
    append(Strings.get(if (p.kind == SafePending.Kind.TX) R.string.safe_discard_body_tx else R.string.safe_discard_body_message))
    if (p.kind == SafePending.Kind.TX && live.isNotEmpty()) {
        val hashes = live.joinToString(", ") { "${it.hash.take(10)}…" }
        append("\n\n")
        append(Strings.plural(R.plurals.safe_discard_abandoned, live.size, hashes))
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
    onOpenUrl: ((String) -> Unit)? = null,
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
    // For a self-call: the Safe's nonce, owners, modules (and, for a cancellation that sends some, its balance)
    // all read at one block, which is what its checks go by (null: not read). For a guard: whether it is one.
    var snapshot by remember { mutableStateOf<SafeChain.Snapshot?>(null) }
    var guardSupported by remember { mutableStateOf<Boolean?>(null) }
    var readError by remember { mutableStateOf<String?>(null) }
    // Every read below has come back (or failed), so the page has stopped changing under Sign.
    var readsDone by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var signature by remember { mutableStateOf<Pair<WalletAccount, String>?>(null) }
    // Which of this wallet's owners signs, when more than one could: the first until another is picked.
    var signerAddress by remember(raw) { mutableStateOf<String?>(null) }
    // A call from the Safe to itself changes the Safe (owners, threshold, modules, guard,
    // fallback handler): decoded and warned about, and signed only once acknowledged.
    val selfCall = request?.let(::safeSelfCall)
    var selfCallAcknowledged by remember(raw) { mutableStateOf(false) }
    // One predicate for both the Sign buttons' enabled state and their click guard.
    val selfCallCleared = safeSelfCallCleared(selfCall, selfCallAcknowledged)
    val mine = owners?.let { list -> accounts.filter { a -> list.any { it.equals(a.address, ignoreCase = true) } } }.orEmpty()
    val outdated = request is SafeProtocol.Request.Tx && safeTxOutdated(selfCall, snapshot, nonce, request.tx.nonce)
    val stage = safeCoSignStage(
        signed = signature != null,
        chainKnown = chain != null,
        readFailed = readError != null,
        readsDone = readsDone,
        ownsIt = mine.isNotEmpty(),
        outdated = outdated,
    )
    // Sign and the acknowledgement tick only appear once the reads are back, so the guard is
    // keyed to their showing, not the page's: it arms from their first frame, and a tap aimed
    // at "Checking the Safe's owners…" can't land on a live Sign that replaced it (#287 R1-M2).
    val tap = rememberArmedTapGuard(raw to (stage == SafeCoSignStage.SIGN), PromptTapGuard.SPEND_PROTECTION_MS)
    val guard = tap.guard
    val armed = tap.armed
    LaunchedEffect(request, chain) {
        val r = request ?: return@LaunchedEffect
        val c = chain ?: return@LaunchedEffect
        readsDone = false
        try {
            if (!chainReads.deployed(c.id, r.safe)) {
                readError = Strings.get(R.string.safe_cosign_not_active, c.name)
                return@LaunchedEffect
            }
            owners = chainReads.owners(c.id, r.safe)
            if (r is SafeProtocol.Request.Tx) nonce = chainReads.nonce(c.id, r.safe)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            readError = safeErrorMessage(e, Strings.get(R.string.safe_action_read), phraseBackedUp)
            readsDone = true
            return@LaunchedEffect
        }
        // None of these is a reason to refuse: an unread value only leaves out the note it would give.
        val call = safeSelfCall(r)
        try {
            when {
                call is SafeSelfCall.SetGuard && !call.guard.equals(SafeProtocol.ZERO_ADDRESS, ignoreCase = true) ->
                    guardSupported = chainReads.guardSupported(c.id, call.guard)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        if (call != null && r is SafeProtocol.Request.Tx) {
            try {
                snapshot = chainReads.snapshot(c.id, r.safe, withBalance = call == SafeSelfCall.Cancel && r.tx.value.signum() != 0)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        readsDone = true
    }

    BackHandler(onBack = onBack)
    FullScreenScaffold(title = stringResource(R.string.safe_cosign_title), onDismiss = onBack) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (request == null) {
                item("bad") {
                    SectionCard(title = stringResource(R.string.safe_cosign_cant_use)) {
                        Text(parsed.exceptionOrNull()?.message ?: stringResource(R.string.safe_cosign_not_a_request), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                return@LazyColumn
            }
            item("what") {
                SectionCard(title = stringResource(if (request is SafeProtocol.Request.Tx) R.string.safe_label_transaction else R.string.safe_label_message)) {
                    if (selfCall.needsAcknowledgement) SafeSelfCallWarning(selfCall!!)
                    val networkName = chain?.name ?: stringResource(R.string.safe_chain_id_fallback, request.chainId.toString())
                    TxReviewSummary(headline = safeCoSignHeadline(request, chain, selfCall), fee = null) {
                        FieldText(stringResource(R.string.safe_cosign_on_network, networkName), error = false)
                        if (chain == null) FieldText(stringResource(R.string.safe_cosign_not_your_network), error = true, announce = false)
                    }
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    val explorer = { address: String -> chain?.let { explorerAddressUrl(it, address) } }
                    when (request) {
                        is SafeProtocol.Request.Tx -> {
                            val tx = request.tx
                            val token = TokenRegistry.builtins.firstOrNull { it.chainId == request.chainId && it.address.equals(tx.to, ignoreCase = true) }
                            val transfer = token?.let { erc20Transfer(tx.data) }
                            if (selfCall != null) {
                                // Owners, modules and balance are the Safe at one block: they only say what this call
                                // does if it is the Safe's next transaction there, and no module can change them first.
                                val s = snapshot?.takeIf { safeStateApplies(selfCall, it, tx.nonce) }
                                SafeSelfCallRows(
                                    selfCall,
                                    tx,
                                    chain,
                                    s?.owners,
                                    accounts,
                                    s?.balance,
                                    s?.modules,
                                    guardSupported,
                                    queuedNote = safeSelfCallQueuedNote(selfCall, snapshot, tx.nonce),
                                )
                            } else if (transfer != null) {
                                CopyableAddressRow(stringResource(R.string.safe_label_to), transfer.first, explorerUrl = explorer(transfer.first), onOpenUrl = onOpenUrl)
                                // Everything signed is shown: a token transfer that also carries native currency says so.
                                if (tx.value.signum() != 0) {
                                    ReviewRow(
                                        stringResource(R.string.safe_label_also_sends),
                                        chain?.let { "${SendAmounts.exact(tx.value, it.decimals)} ${it.symbol}" } ?: stringResource(R.string.safe_base_units, tx.value.toString()),
                                        mono = true,
                                        detail = stringResource(R.string.safe_also_sends_detail),
                                    )
                                }
                            } else {
                                CopyableAddressRow(stringResource(R.string.safe_label_to), tx.to, explorerUrl = explorer(tx.to), onOpenUrl = onOpenUrl)
                                if (tx.data.isNotEmpty()) {
                                    ReviewRow(
                                        stringResource(R.string.safe_label_amount),
                                        chain?.let { "${SendAmounts.exact(tx.value, it.decimals)} ${it.symbol}" } ?: stringResource(R.string.safe_base_units, tx.value.toString()),
                                        mono = true,
                                    )
                                    TxDecode.call(tx.to, tx.data, request.chainId)?.let(::callWarning)?.let { Warning(it, Modifier.padding(top = 4.dp)) }
                                    FieldText(stringResource(R.string.safe_contract_call_detail), error = true, announce = false)
                                }
                            }
                        }
                        is SafeProtocol.Request.Message -> ReviewRow(stringResource(R.string.safe_label_text), request.shownText)
                    }
                    CopyableAddressRow(stringResource(R.string.safe_label_safe), request.safe, explorerUrl = explorer(request.safe), onOpenUrl = onOpenUrl)
                    // On the surface, not under Details: a request queued behind transactions this
                    // signer hasn't seen ("earlier transactions must execute first") is said up front.
                    if (request is SafeProtocol.Request.Tx) {
                        val tx = request.tx
                        val shownNonce = safeNonceShown(selfCall, snapshot, nonce)
                        ReviewRow(
                            stringResource(R.string.safe_label_safe_nonce),
                            tx.nonce.toString(),
                            mono = true,
                            detail = when {
                                shownNonce == null -> null
                                shownNonce > tx.nonce -> stringResource(R.string.safe_nonce_past)
                                shownNonce < tx.nonce -> stringResource(R.string.safe_nonce_ahead, shownNonce.toString())
                                else -> stringResource(R.string.safe_nonce_next)
                            },
                        )
                    }
                    DetailsExpander {
                        if (request is SafeProtocol.Request.Tx) {
                            val tx = request.tx
                            val token = TokenRegistry.builtins.firstOrNull { it.chainId == request.chainId && it.address.equals(tx.to, ignoreCase = true) }
                            if (token != null && erc20Transfer(tx.data) != null) {
                                CopyableAddressRow(
                                    stringResource(R.string.safe_label_token_contract, token.symbol),
                                    tx.to,
                                    explorerUrl = explorer(tx.to),
                                    onOpenUrl = onOpenUrl,
                                )
                            }
                            // Everything signed is shown: the raw call data, under what was read from it.
                            if (tx.data.isNotEmpty()) {
                                HexRow(
                                    stringResource(R.string.safe_label_data),
                                    "0x" + tx.data.toHex(),
                                    selector = true,
                                    detail = when {
                                        selfCall == SafeSelfCall.Unknown -> stringResource(R.string.safe_data_check_detail)
                                        selfCall != null -> stringResource(R.string.safe_data_decoded_detail)
                                        else -> null
                                    },
                                )
                            }
                        }
                        CopyableAddressRow(
                            stringResource(if (request is SafeProtocol.Request.Tx) R.string.safe_label_safetx_hash else R.string.safe_label_safemessage_hash),
                            "0x" + request.hash.toHex(),
                        )
                    }
                }
            }
            item("sign") {
                SectionCard(title = stringResource(R.string.safe_sign_section_title)) {
                    val s = signature
                    when (stage) {
                        SafeCoSignStage.SIGNED -> if (s != null) {
                            Text(stringResource(R.string.safe_cosign_signed, s.first.name), style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(8.dp))
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                QrCodeImage(s.second, stringResource(R.string.safe_qr_signature_description), Modifier.widthIn(max = 280.dp).fillMaxWidth())
                            }
                            SelectionContainer { Text(s.second, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { copyToClipboard(context, s.second) }) { Text(stringResource(R.string.safe_copy_signature)) }
                            }
                        }
                        SafeCoSignStage.NO_CHAIN -> Text(stringResource(R.string.safe_cosign_no_chain), style = MaterialTheme.typography.bodyMedium)
                        SafeCoSignStage.READ_FAILED -> FieldText(readError.orEmpty(), error = true)
                        SafeCoSignStage.CHECKING -> Text(stringResource(R.string.safe_cosign_checking), style = MaterialTheme.typography.bodyMedium)
                        SafeCoSignStage.NOT_OWNER -> Text(stringResource(R.string.safe_cosign_not_owner), style = MaterialTheme.typography.bodyMedium)
                        SafeCoSignStage.OUTDATED -> Text(stringResource(R.string.safe_cosign_outdated), style = MaterialTheme.typography.bodyMedium)
                        SafeCoSignStage.SIGN -> {
                            error?.let { FieldText(it, error = true) }
                            ObscuredTapNotice(tap)
                            if (selfCall.needsAcknowledgement) {
                                // Guarded like Sign: an early or obscured tap can't tick the
                                // acknowledgement that Sign waits for.
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .protectedToggle(tap, value = selfCallAcknowledged, role = Role.Checkbox, enabled = !busy) {
                                            selfCallAcknowledged = it
                                        },
                                ) {
                                    Checkbox(checked = selfCallAcknowledged, onCheckedChange = null, enabled = armed && !busy)
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.safe_self_call_acknowledge), style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            val account = safeCoSigner(mine, signerAddress) ?: return@SectionCard
                            if (mine.size > 1) {
                                Text(stringResource(R.string.safe_sign_as), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Column(Modifier.selectableGroup()) {
                                    mine.forEach { a ->
                                        val selected = a.address.equals(account.address, ignoreCase = true)
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .protectedSelectable(tap, selected = selected, enabled = !busy) { signerAddress = a.address }
                                                .heightIn(min = 56.dp)
                                                .padding(vertical = 4.dp),
                                        ) {
                                            RadioButton(selected = selected, onClick = null, enabled = armed && !busy)
                                            Spacer(Modifier.width(8.dp))
                                            Column(Modifier.weight(1f)) {
                                                Text(accountLabel(a), fontWeight = FontWeight.Medium)
                                                AddressText(a.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                            } else {
                                FieldText(stringResource(R.string.safe_signs_as, accountLabel(account)), error = false)
                                Spacer(Modifier.height(8.dp))
                            }
                            val signing = stringResource(R.string.safe_signing)
                            Button(
                                onClick = {
                                    if (!guard.accepts() || busy || !selfCallCleared) return@Button
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
                                            error = safeErrorMessage(e, Strings.get(R.string.safe_action_sign), phraseBackedUp)
                                        } finally {
                                            busy = false
                                        }
                                    }
                                },
                                enabled = armed && !busy && selfCallCleared,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).protectedPress(tap),
                            ) {
                                if (busy) BusySpinner(signing) else Text(stringResource(if (account.isLedger) R.string.safe_sign_on_ledger else R.string.safe_sign))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Which of [mine] (this wallet's owners of the Safe) signs: the one picked ([picked]), else the first. */
internal fun safeCoSigner(mine: List<WalletAccount>, picked: String?): WalletAccount? =
    mine.firstOrNull { picked != null && it.address.equals(picked, ignoreCase = true) } ?: mine.firstOrNull()

/**
 * A co-sign request's hero line (W35): what signing it lets the Safe do,
 * in a sentence — a payment with its amount and recipient, a change to
 * the Safe itself, a contract call ([TxDecode.call] read as a site's sheet
 * reads it — an approval names its spender and amount — else just the
 * contract), or a message.
 */
internal fun safeCoSignHeadline(request: SafeProtocol.Request, chain: Chain?, selfCall: SafeSelfCall?): String {
    val safe = shortAddress(request.safe)
    return when (request) {
        is SafeProtocol.Request.Message -> Strings.get(R.string.safe_cosign_headline_message, safe)
        is SafeProtocol.Request.Tx -> {
            val tx = request.tx
            val token = TokenRegistry.builtins.firstOrNull { it.chainId == request.chainId && it.address.equals(tx.to, ignoreCase = true) }
            val transfer = token?.let { erc20Transfer(tx.data) }
            when {
                selfCall == SafeSelfCall.Cancel -> Strings.get(R.string.safe_cosign_headline_cancel, safe)
                selfCall != null -> Strings.get(R.string.safe_cosign_headline_self, safe)
                transfer != null ->
                    Strings.get(R.string.safe_headline_send, SendAmounts.exact(transfer.second, token.decimals), token.symbol, shortAddress(transfer.first), safe)
                tx.data.isEmpty() && chain != null ->
                    Strings.get(R.string.safe_headline_send, SendAmounts.exact(tx.value, chain.decimals), chain.symbol, shortAddress(tx.to), safe)
                tx.data.isEmpty() ->
                    Strings.get(R.string.safe_headline_send, tx.value.toString(), Strings.get(R.string.safe_base_units_unit), shortAddress(tx.to), safe)
                else -> TxDecode.call(tx.to, tx.data, request.chainId)
                    ?.let { Strings.get(R.string.safe_cosign_headline_decoded, callHeadline(it), safe) }
                    ?: Strings.get(R.string.safe_cosign_headline_call, shortAddress(tx.to), safe)
            }
        }
    }
}

/** What a co-sign request's Sign card shows, in order of precedence. */
internal enum class SafeCoSignStage { SIGNED, NO_CHAIN, READ_FAILED, CHECKING, NOT_OWNER, OUTDATED, SIGN }

/**
 * The co-sign Sign card's stage. [SafeCoSignStage.SIGN] (the Sign buttons and the self-call
 * tick) only once every read is back ([readsDone]), not just the owners: a later read (the
 * nonce, the self-call snapshot) adds rows above Sign that would move a live button under the
 * finger, and the tap guard is keyed to this stage so it arms from Sign's own first frame.
 */
internal fun safeCoSignStage(
    signed: Boolean,
    chainKnown: Boolean,
    readFailed: Boolean,
    readsDone: Boolean,
    ownsIt: Boolean,
    outdated: Boolean,
): SafeCoSignStage = when {
    signed -> SafeCoSignStage.SIGNED
    !chainKnown -> SafeCoSignStage.NO_CHAIN
    readFailed -> SafeCoSignStage.READ_FAILED
    !readsDone -> SafeCoSignStage.CHECKING
    !ownsIt -> SafeCoSignStage.NOT_OWNER
    outdated -> SafeCoSignStage.OUTDATED
    else -> SafeCoSignStage.SIGN
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
                Text(stringResource(R.string.safe_self_call_warning_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(4.dp))
            Text(safeSelfCallRisk(call), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Why [call] matters, for the warning. */
internal fun safeSelfCallRisk(call: SafeSelfCall): String = Strings.get(
    when (call) {
        is SafeSelfCall.AddOwner -> R.string.safe_risk_add_owner
        is SafeSelfCall.RemoveOwner -> R.string.safe_risk_remove_owner
        is SafeSelfCall.SwapOwner -> R.string.safe_risk_swap_owner
        is SafeSelfCall.ChangeThreshold -> R.string.safe_risk_change_threshold
        is SafeSelfCall.EnableModule -> R.string.safe_risk_enable_module
        is SafeSelfCall.DisableModule -> R.string.safe_risk_disable_module
        is SafeSelfCall.SetGuard -> R.string.safe_risk_set_guard
        is SafeSelfCall.SetFallbackHandler -> R.string.safe_risk_set_fallback_handler
        SafeSelfCall.Unknown -> R.string.safe_risk_unknown
        SafeSelfCall.Cancel -> R.string.safe_risk_cancel
    },
)

/** Whether [this] needs the "I understand" acknowledgement before it can be signed: any self-call but a cancellation. */
internal val SafeSelfCall?.needsAcknowledgement: Boolean get() = this != null && !harmless

/** Whether a request with [call] (null: not a self-call) may be signed now, given [acknowledged]. */
internal fun safeSelfCallCleared(call: SafeSelfCall?, acknowledged: Boolean): Boolean = !call.needsAcknowledgement || acknowledged

/**
 * Why a self-call would revert, or null if it can go through (or that isn't
 * known yet). Every "would fail" here is a check every Safe version the
 * co-sign page accepts makes (v1.1.1 on); the two only v1.4 added — GS300 and
 * GS400 below — are hedged instead, since the page accepts any deployed Safe
 * and doesn't read its version. Owner calls are counted against [owners]
 * (on chain, in the Safe's own linked-list order, as `getOwners()` returns
 * them; null until read): the Safe refuses to add an address that already
 * owns it or is no address (GS203/GS204), to remove or replace one that
 * doesn't own it (GS205), and a `prevOwner` that isn't the owner right before
 * it in that list (GS205): the first owner's is the list's sentinel `0x…01`.
 * Module calls are counted against [modules] (`getModulesPaginated`, the same
 * kind of list; null until read): it refuses to enable no address (GS101) or
 * an enabled module (GS102), and to disable one that isn't enabled or with the
 * wrong `prevModule` (GS103). A Safe from v1.4 on also refuses itself as its
 * fallback handler (GS400); v1.3.0 and older don't check and simply install
 * it, dropping the handler that answers EIP-1271 and token-receive callbacks,
 * so that note warns rather than promising a failure.
 * On the page, [owners] and [modules] come only from a snapshot that
 * [safeStateApplies] to, and that never holds a module (an enabled module can
 * change the list with no nonce), so of these the page only ever shows "isn't
 * enabled"; GS102 and a wrong `prevModule` need a non-empty list and stay
 * here for a caller that has one it can trust.
 *
 * A guard that doesn't currently declare itself one ([guardSupported] false;
 * a v1.4 Safe would refuse it, GS300, while v1.3.0 doesn't ask) gets a note
 * too, but never a "would fail": besides the Safe's version, whether it
 * answers is up to the guard's own code, which its author can
 * deploy or change after the page reads it (a CREATE2 address with nothing
 * there yet, or a guard that answers true only to the Safe), so the note says
 * not to count on the transaction failing.
 */
/**
 * The note for a Safe set as its own fallback handler: a v1.4 Safe refuses it
 * (GS400), but v1.3.0 and older install it, so it can't promise a failure.
 */
internal val SAFE_SELF_HANDLER_NOTE: String get() = Strings.get(R.string.safe_self_handler_note)

internal fun safeSelfCallFailure(
    call: SafeSelfCall,
    owners: List<String>?,
    safe: String,
    modules: List<String>? = null,
    guardSupported: Boolean? = null,
): String? {
    fun has(list: List<String>, address: String) = list.any { it.equals(address, ignoreCase = true) }
    fun prevIn(list: List<String>, address: String): String {
        val i = list.indexOfFirst { it.equals(address, ignoreCase = true) }
        return if (i == 0) SAFE_OWNERS_SENTINEL else list[i - 1]
    }
    fun noAddress(address: String) = address.equals(SafeProtocol.ZERO_ADDRESS, ignoreCase = true) ||
        address.equals(SAFE_OWNERS_SENTINEL, ignoreCase = true)
    fun added(owners: List<String>, address: String) = when {
        address.equals(safe, ignoreCase = true) -> Strings.get(R.string.safe_fail_new_owner_is_safe)
        noAddress(address) -> Strings.get(R.string.safe_fail_new_owner_no_address)
        has(owners, address) -> Strings.get(R.string.safe_fail_new_owner_already_owns)
        else -> null
    }
    // [replaced]: the owner is swapped out rather than removed.
    fun removed(owners: List<String>, prev: String, address: String, replaced: Boolean) = when {
        !has(owners, address) ->
            Strings.get(if (replaced) R.string.safe_fail_replaced_not_owner else R.string.safe_fail_removed_not_owner)
        !prev.equals(prevIn(owners, address), ignoreCase = true) ->
            Strings.get(if (replaced) R.string.safe_fail_replaced_wrong_prev else R.string.safe_fail_removed_wrong_prev)
        else -> null
    }
    return when (call) {
        is SafeSelfCall.AddOwner -> owners?.let { added(it, call.owner) }
        is SafeSelfCall.RemoveOwner -> owners?.let { removed(it, call.prev, call.owner, replaced = false) }
        is SafeSelfCall.SwapOwner -> owners?.let { removed(it, call.prev, call.old, replaced = true) ?: added(it, call.new) }
        is SafeSelfCall.EnableModule -> when {
            noAddress(call.module) -> Strings.get(R.string.safe_fail_module_no_address)
            modules != null && has(modules, call.module) -> Strings.get(R.string.safe_fail_module_already_enabled)
            else -> null
        }
        is SafeSelfCall.DisableModule -> when {
            noAddress(call.module) -> Strings.get(R.string.safe_fail_module_no_address)
            modules == null -> null
            !has(modules, call.module) -> Strings.get(R.string.safe_fail_module_not_enabled)
            !call.prev.equals(prevIn(modules, call.module), ignoreCase = true) ->
                Strings.get(R.string.safe_fail_module_wrong_prev)
            else -> null
        }
        is SafeSelfCall.SetGuard ->
            if (!call.guard.equals(SafeProtocol.ZERO_ADDRESS, ignoreCase = true) && guardSupported == false) {
                Strings.get(R.string.safe_fail_guard_unsupported)
            } else {
                null
            }
        is SafeSelfCall.SetFallbackHandler ->
            if (call.handler.equals(safe, ignoreCase = true)) SAFE_SELF_HANDLER_NOTE else null
        is SafeSelfCall.ChangeThreshold, SafeSelfCall.Cancel, SafeSelfCall.Unknown -> null
    }
}

/**
 * The Threshold row's detail for an owner/threshold self-call, counted against
 * [owners] (on chain, null until read) — including whether the call can
 * execute at all ([safeSelfCallFailure]); null for a call with no threshold.
 */
internal fun safeSelfCallThreshold(call: SafeSelfCall, owners: List<String>?, safe: String): String? {
    if (owners == null) return null
    val (t, n) = when (call) {
        is SafeSelfCall.AddOwner -> call.threshold to owners.size + 1
        is SafeSelfCall.RemoveOwner -> call.threshold to owners.size - 1
        is SafeSelfCall.ChangeThreshold -> call.threshold to owners.size
        else -> return null
    }
    safeSelfCallFailure(call, owners, safe)?.let { return it }
    return when {
        t.signum() == 0 || t > BigInteger.valueOf(n.toLong()) -> Strings.plural(R.plurals.safe_threshold_impossible, n, n)
        t == BigInteger.ONE && n > 1 -> Strings.plural(R.plurals.safe_threshold_any_one, n, n)
        // The threshold as the request has it, in plain digits (#313 R1-M4).
        else -> Strings.plural(R.plurals.safe_threshold_must_sign, n, t.toString(), n)
    }
}

/**
 * The cancellation row's detail for a no-data self-call sending [value]
 * ([amount], formatted) at [nonce], given the Safe's [balance] (null: not
 * read). The Safe can't send more than it holds, and with no `safeTxGas` or
 * `gasPrice` (all Freedom signs) a failed call reverts the whole execution
 * (GS013), so the nonce is only used up if the balance covers [value] at
 * execution. [balance] is one read taken when the page opened, from an RPC
 * whose answer may be untrusted, and the Safe can be drained before
 * execution, so even a covering balance only hedges, never promises. A short
 * one only hedges too: anyone can send the Safe native currency first.
 */
internal fun safeCancelDetail(nonce: BigInteger, value: BigInteger, balance: BigInteger?, amount: String): String = when {
    value.signum() == 0 -> Strings.get(R.string.safe_cancel_detail_no_value, nonce.toString())
    balance != null && balance < value -> Strings.get(R.string.safe_cancel_detail_short, nonce.toString(), amount)
    balance != null -> Strings.get(R.string.safe_cancel_detail_covered, nonce.toString(), amount)
    else -> Strings.get(R.string.safe_cancel_detail_unread, nonce.toString(), amount)
}

/**
 * Whether [snapshot] (the Safe's nonce, owners, modules and balance, all at
 * one block) is what a SafeTx at [txNonce] runs against. Only if it was the
 * Safe's next transaction at that block: one queued behind earlier nonces
 * runs after them, and they can add or remove owners and modules or move
 * funds first. And, for a [call] checked against owners or modules, only if
 * the Safe had no modules then: an enabled module can change owners and
 * modules through `execTransactionFromModule` with no nonce at all. With
 * neither, nothing can change them before this nonce executes except
 * another transaction at the same nonce, which leaves this one unable to.
 * Reads from separate blocks — the router picks a node per read — could
 * pair an old owner list with a current nonce, hence the one block.
 */
internal fun safeStateApplies(call: SafeSelfCall, snapshot: SafeChain.Snapshot?, txNonce: BigInteger): Boolean =
    snapshot != null && snapshot.nonce == txNonce && (!call.readsOwnersOrModules || snapshot.modules?.isEmpty() == true)

/**
 * The Safe's next nonce the *Safe nonce* row goes by. For a self-call it's
 * [snapshot]'s once read, so the row agrees with [safeSelfCallQueuedNote]:
 * the separate [nonce] read may come from a later block or another node.
 * Otherwise (and until the snapshot is read) [nonce].
 */
internal fun safeNonceShown(call: SafeSelfCall?, snapshot: SafeChain.Snapshot?, nonce: BigInteger?): BigInteger? =
    if (call != null && snapshot != null) snapshot.nonce else nonce

/**
 * Whether the Safe is already past [txNonce], so there's nothing to sign.
 * Goes by the same [safeNonceShown] value as the *Safe nonce* row, so the
 * row's "can never execute" and the Sign card's "can no longer execute"
 * always agree.
 */
internal fun safeTxOutdated(call: SafeSelfCall?, snapshot: SafeChain.Snapshot?, nonce: BigInteger?, txNonce: BigInteger): Boolean =
    safeNonceShown(call, snapshot, nonce)?.let { it > txNonce } == true

/** Whether the Safe's checks on this call go by its owners or its modules: owner, threshold and module calls. */
private val SafeSelfCall.readsOwnersOrModules: Boolean
    get() = this is SafeSelfCall.AddOwner || this is SafeSelfCall.RemoveOwner || this is SafeSelfCall.SwapOwner ||
        this is SafeSelfCall.ChangeThreshold || this is SafeSelfCall.EnableModule || this is SafeSelfCall.DisableModule

/**
 * For an owner, threshold or module self-call that [snapshot] doesn't say
 * what it runs against ([safeStateApplies]): why. It's queued behind the
 * Safe's next nonce, or the Safe has modules (or they couldn't all be
 * read). Null otherwise, and until the Safe is read.
 */
internal fun safeSelfCallQueuedNote(call: SafeSelfCall, snapshot: SafeChain.Snapshot?, txNonce: BigInteger): String? {
    if (snapshot == null || snapshot.nonce > txNonce) return null
    // Whether it's checked against the owners (owner and threshold calls) or the modules.
    val owners = when (call) {
        is SafeSelfCall.AddOwner, is SafeSelfCall.RemoveOwner, is SafeSelfCall.SwapOwner, is SafeSelfCall.ChangeThreshold -> true
        is SafeSelfCall.EnableModule, is SafeSelfCall.DisableModule -> false
        else -> return null
    }
    return when {
        snapshot.nonce < txNonce -> Strings.get(
            if (owners) R.string.safe_queued_owners_behind else R.string.safe_queued_modules_behind,
            snapshot.nonce.toString(),
            txNonce.toString(),
        )
        snapshot.modules == null ->
            Strings.get(if (owners) R.string.safe_queued_owners_modules_unread else R.string.safe_queued_modules_modules_unread)
        snapshot.modules.isNotEmpty() -> Strings.plural(
            if (owners) R.plurals.safe_queued_owners_has_modules else R.plurals.safe_queued_modules_has_modules,
            snapshot.modules.size,
            snapshot.modules.size,
        )
        else -> null
    }
}

/** The head of a Safe's owner (and module) linked list: the `prevOwner` of its first owner, the `prevModule` of its first module. */
internal const val SAFE_OWNERS_SENTINEL = "0x0000000000000000000000000000000000000001"

/** [address] named as one of this wallet's own [accounts] ("Account 1 (this phone)"), or null for anyone else's. */
internal fun safeOwnAccountLabel(address: String, accounts: List<WalletAccount>): String? =
    accounts.firstOrNull { it.address.equals(address, ignoreCase = true) }?.let {
        Strings.get(if (it.isLedger) R.string.safe_own_account_ledger else R.string.safe_own_account_phone, it.name)
    }

/** The decoded rows of a SafeTx from the Safe to itself; [owners] (on chain, if read) to count the threshold against. */
@Composable
private fun SafeSelfCallRows(
    call: SafeSelfCall,
    tx: SafeProtocol.SafeTx,
    chain: Chain?,
    owners: List<String>?,
    accounts: List<WalletAccount>,
    safeBalance: BigInteger?,
    modules: List<String>?,
    guardSupported: Boolean?,
    queuedNote: String?,
) {
    // Whether the Safe would refuse a module, guard or handler call: on its Changes row, which has nothing else to say.
    // A call queued behind earlier ones says instead that those can change what it's checked against.
    val fails = listOfNotNull(safeSelfCallFailure(call, owners, tx.to, modules, guardSupported), queuedNote)
        .joinToString("\n\n").ifEmpty { null }
    val amount = chain?.let { "${SendAmounts.exact(tx.value, it.decimals)} ${it.symbol}" } ?: stringResource(R.string.safe_base_units, tx.value.toString())
    val noneText = stringResource(R.string.safe_none)
    fun none(address: String) = if (address.equals(SafeProtocol.ZERO_ADDRESS, ignoreCase = true)) noneText else null
    // A removed owner that is this wallet's own account is named, so signing yourself out can't pass as a bare address.
    @Composable
    fun Removed(address: String) {
        val own = safeOwnAccountLabel(address, accounts)
        val ownDetail = stringResource(R.string.safe_removed_own_account_detail)
        CopyableAddressRow(stringResource(R.string.safe_label_removed_owner), address, name = own, detail = own?.let { ownDetail })
    }
    when (call) {
        SafeSelfCall.Cancel -> {
            ReviewRow(stringResource(R.string.safe_label_does), stringResource(R.string.safe_does_cancel), detail = safeCancelDetail(tx.nonce, tx.value, safeBalance, amount))
        }
        is SafeSelfCall.AddOwner -> {
            ReviewRow(stringResource(R.string.safe_label_changes), stringResource(R.string.safe_changes_add_owner), detail = queuedNote)
            CopyableAddressRow(stringResource(R.string.safe_label_new_owner), call.owner, name = safeOwnAccountLabel(call.owner, accounts))
            ReviewRow(stringResource(R.string.safe_label_threshold), call.threshold.toString(), mono = true, detail = safeSelfCallThreshold(call, owners, tx.to))
        }
        is SafeSelfCall.RemoveOwner -> {
            ReviewRow(stringResource(R.string.safe_label_changes), stringResource(R.string.safe_changes_remove_owner), detail = queuedNote)
            Removed(call.owner)
            ReviewRow(stringResource(R.string.safe_label_threshold), call.threshold.toString(), mono = true, detail = safeSelfCallThreshold(call, owners, tx.to))
        }
        is SafeSelfCall.SwapOwner -> {
            // No threshold row to carry it: whether the Safe would refuse it goes on this one.
            ReviewRow(stringResource(R.string.safe_label_changes), stringResource(R.string.safe_changes_swap_owner), detail = fails)
            Removed(call.old)
            CopyableAddressRow(stringResource(R.string.safe_label_new_owner), call.new, name = safeOwnAccountLabel(call.new, accounts))
        }
        is SafeSelfCall.ChangeThreshold -> {
            ReviewRow(stringResource(R.string.safe_label_changes), stringResource(R.string.safe_changes_threshold), detail = queuedNote)
            ReviewRow(stringResource(R.string.safe_label_threshold), call.threshold.toString(), mono = true, detail = safeSelfCallThreshold(call, owners, tx.to))
        }
        is SafeSelfCall.EnableModule -> {
            ReviewRow(stringResource(R.string.safe_label_changes), stringResource(R.string.safe_changes_enable_module), detail = fails)
            CopyableAddressRow(stringResource(R.string.safe_label_module), call.module)
        }
        is SafeSelfCall.DisableModule -> {
            ReviewRow(stringResource(R.string.safe_label_changes), stringResource(R.string.safe_changes_disable_module), detail = fails)
            CopyableAddressRow(stringResource(R.string.safe_label_module), call.module)
        }
        is SafeSelfCall.SetGuard -> {
            ReviewRow(stringResource(R.string.safe_label_changes), stringResource(R.string.safe_changes_guard), detail = fails)
            if (none(call.guard) != null) ReviewRow(stringResource(R.string.safe_label_guard), noneText) else CopyableAddressRow(stringResource(R.string.safe_label_guard), call.guard)
        }
        is SafeSelfCall.SetFallbackHandler -> {
            ReviewRow(stringResource(R.string.safe_label_changes), stringResource(R.string.safe_changes_fallback_handler), detail = fails)
            if (none(call.handler) != null) ReviewRow(stringResource(R.string.safe_label_fallback_handler), noneText) else CopyableAddressRow(stringResource(R.string.safe_label_fallback_handler), call.handler)
        }
        SafeSelfCall.Unknown -> ReviewRow(
            stringResource(R.string.safe_label_changes),
            stringResource(R.string.safe_changes_unknown),
            detail = stringResource(R.string.safe_unknown_detail),
        )
    }
    CopyableAddressRow(stringResource(R.string.safe_label_to), tx.to, detail = stringResource(R.string.safe_to_self_detail))
    ReviewRow(stringResource(R.string.safe_label_amount), amount, mono = true)
    // The raw call data, under what was read from it, is in the page's Details.
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
    data class RemoveOwner(val prev: String, val owner: String, val threshold: BigInteger) : SafeSelfCall
    data class SwapOwner(val prev: String, val old: String, val new: String) : SafeSelfCall
    data class ChangeThreshold(val threshold: BigInteger) : SafeSelfCall
    data class EnableModule(val module: String) : SafeSelfCall
    data class DisableModule(val prev: String, val module: String) : SafeSelfCall
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
        SAFE_SELECTORS["removeOwner"] -> if (args(3)) {
            val prev = address(0)
            val owner = address(1)
            if (prev != null && owner != null) SafeSelfCall.RemoveOwner(prev, owner, uint(2)) else null
        } else {
            null
        }
        SAFE_SELECTORS["swapOwner"] -> if (args(3)) {
            val prev = address(0)
            val old = address(1)
            val new = address(2)
            if (prev != null && old != null && new != null) SafeSelfCall.SwapOwner(prev, old, new) else null
        } else {
            null
        }
        SAFE_SELECTORS["changeThreshold"] -> if (args(1)) SafeSelfCall.ChangeThreshold(uint(0)) else null
        SAFE_SELECTORS["enableModule"] -> if (args(1)) address(0)?.let { SafeSelfCall.EnableModule(it) } else null
        SAFE_SELECTORS["disableModule"] -> if (args(2)) {
            val prev = address(0)
            val module = address(1)
            if (prev != null && module != null) SafeSelfCall.DisableModule(prev, module) else null
        } else {
            null
        }
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
private fun FieldText(text: String, error: Boolean, announce: Boolean = error) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        // A problem is announced as it appears, not only found by exploring (W50); a note in red that is
        // part of the page from its first frame ([announce] false) is read in turn, not as news.
        modifier = Modifier.padding(top = 4.dp).then(if (announce) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier),
    )
}

/** What Safe Propose's Paste does with the clip ([safePastedRecipient]). */
internal sealed interface SafePaste {
    /** Into the To field as typed. */
    data class Take(val text: String) : SafePaste

    /** Nothing filled in; [reason] shown under the field. */
    data class Note(val reason: String) : SafePaste
}

/**
 * [pasted] — the clip's [text] read by [pastedRecipient] — as Safe
 * Propose's To field takes it, for a Safe on [chainId]. A plain address
 * or a name goes in; so does a bare request naming only an address
 * (`ethereum:0x…`, or `ethereum:0x…@<this Safe's chain>`: a wallet's
 * receive code), the same as a scanned one ([safeScannedRecipient]).
 * Any other payment request (EIP-681) doesn't, even when Send could pay
 * it: it names its own network, token or amount, and taking only its
 * address would let the Safe send something else, on its own network, to
 * an address that may only take the request's (an exchange's
 * Ethereum-only deposit address, say). A secret is never shown.
 */
internal fun safePastedRecipient(pasted: PastedRecipient?, text: String?, chainId: Long): SafePaste? = when (pasted) {
    // Only an address or a request reads as either: [text] is the request itself.
    is PastedRecipient.Fill, is PastedRecipient.Refused ->
        text?.let { safeScannedRecipient(it, chainId) } ?: SafePaste.Note(Strings.get(R.string.safe_paste_payment_request))
    is PastedRecipient.Text -> SafePaste.Take(pasted.text)
    PastedRecipient.Secret -> SafePaste.Note(Strings.get(R.string.send_paste_secret))
    null -> null
}

/**
 * [text], scanned or pasted into Safe Propose's To field, for a Safe on
 * [chainId]: an address goes in, and so does a request naming nothing
 * but an address — no token, no amount, and no network or this Safe's
 * own — since that is all a receive code says. Any other request is
 * refused with why ([R.string.safe_paste_payment_request]); null for
 * anything that's neither (a name, say: the caller decides).
 */
internal fun safeScannedRecipient(text: String, chainId: Long): SafePaste? = when (val code = ScannedCode.parse(text)) {
    is ScannedCode.Address -> SafePaste.Take(code.address)
    is ScannedCode.Payment ->
        if (code.token == null && code.amount == null && (code.chainId == null || code.chainId == chainId)) {
            SafePaste.Take(code.recipient)
        } else {
            SafePaste.Note(Strings.get(R.string.safe_paste_payment_request))
        }
    else -> null
}

/**
 * What Safe Receive's Share sends: the address, then the same "only on
 * [chainName]" line the page shows under it — unlike an account's
 * address, a Safe's takes funds on its own network only, and the person
 * paying sees nothing but what's shared.
 */
internal fun safeShareText(address: String, chainName: String): String =
    "$address\n\n${Strings.get(R.string.safe_receive_hint, chainName)}"
