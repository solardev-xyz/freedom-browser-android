package baby.freedom.mobile.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import baby.freedom.mobile.R
import baby.freedom.mobile.l10n.Strings
import baby.freedom.mobile.wallet.BiometricVaultAuthenticator
import baby.freedom.mobile.wallet.OpenLvSession
import baby.freedom.mobile.wallet.SendAmounts
import baby.freedom.mobile.wallet.Vault
import baby.freedom.mobile.wallet.WalletAccount
import baby.freedom.mobile.ens.toHex
import baby.freedom.mobile.ui.isLight
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * [hex] (`0x`-prefixed) as the review lays it out: one 32-byte word a line —
 * ABI's own grain — after the 4-byte function selector when [selector].
 * Every byte is in a line; nothing is cut.
 */
internal fun hexLines(hex: String, selector: Boolean): List<String> {
    val body = hex.removePrefix("0x")
    if (body.isEmpty()) return emptyList()
    val out = ArrayList<String>(body.length / HEX_WORD + 2)
    val first = minOf(body.length, if (selector) 8 else HEX_WORD)
    out += "0x" + body.substring(0, first)
    var i = first
    while (i < body.length) {
        out += body.substring(i, minOf(body.length, i + HEX_WORD))
        i += HEX_WORD
    }
    return out
}

private const val HEX_WORD = 64

/** Up to this many lines are laid out inline; more go in a bounded, lazily laid out box. */
internal const val HEX_INLINE_LINES = 16

/**
 * A review row for bytes shown in hex (call data, a binary message). A
 * short value is laid out inline; a long one (up to 64 KB of it:
 * [OpenLvSession.MAX_CALL_DATA], [OpenLvSession.MAX_MESSAGE]) goes in a
 * box of its own that scrolls and only lays out the lines on screen — one
 * Text holding all of it took seconds of main-thread layout.
 */
@Composable
internal fun HexRow(label: String, hex: String, selector: Boolean, detail: String? = null) {
    val lines = remember(hex, selector) { hexLines(hex, selector) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // A 32-byte word as two 16-byte halves: fits a phone's width instead of wrapping at a random column.
        val line: @Composable (String) -> Unit = {
            Text(
                it.chunked(HEX_WORD / 2).joinToString("\n"),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 3.dp),
            )
        }
        when {
            lines.isEmpty() -> Text(stringResource(R.string.signing_hex_none))
            lines.size <= HEX_INLINE_LINES -> SelectionContainer { Column { lines.forEach { line(it) } } }
            else -> Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) {
                SelectionContainer {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp).padding(horizontal = 8.dp, vertical = 4.dp)) {
                        items(lines.size) { line(lines[it]) }
                    }
                }
            }
        }
        val more = if (lines.size > HEX_INLINE_LINES) pluralStringResource(R.plurals.signing_hex_lines_scroll, lines.size, lines.size) else null
        listOfNotNull(detail, more).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    HorizontalDivider()
}

/** What the connection over a scanned code is doing, in a line: the code doesn't prove who's on the other end. */
internal fun remoteStatusText(status: OpenLvSession.Status): String = when (status) {
    OpenLvSession.Status.Idle -> Strings.get(R.string.signing_remote_status_idle)
    OpenLvSession.Status.Connecting -> Strings.get(R.string.signing_remote_status_connecting)
    OpenLvSession.Status.Connected -> Strings.get(R.string.signing_remote_status_connected)
    OpenLvSession.Status.Disconnected -> Strings.get(R.string.signing_remote_status_disconnected)
    is OpenLvSession.Status.Failed -> Strings.get(R.string.signing_remote_status_failed, status.message)
}

/**
 * The scan page's answer to a pairing code (#113), which it has already
 * connected with ([OpenLvSession.start]) — the user scanned it on
 * purpose, and nothing is signed or shared without a sheet of its own:
 * how the connection is going, and Disconnect.
 */
@Composable
internal fun PairingSection(uri: String) {
    val context = LocalContext.current
    val session = remember(context) { OpenLvSession.get(context) }
    val status by session.status.collectAsState()
    SectionCard(title = stringResource(R.string.signing_remote_connection_title)) {
        Row(verticalAlignment = Alignment.Top) {
            when (status) {
                OpenLvSession.Status.Connecting -> CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                OpenLvSession.Status.Connected -> Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = successGreen(), modifier = Modifier.size(20.dp))
                is OpenLvSession.Status.Failed -> Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                else -> Spacer(Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text(remoteStatusText(status), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.signing_remote_pairing_explained),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            when {
                session.active -> TextButton(onClick = { session.stop() }) { Text(stringResource(R.string.common_disconnect)) }
                else -> TextButton(onClick = { session.start(uri, again = true) }) { Text(stringResource(R.string.signing_remote_connect_again)) }
            }
        }
    }
}

@Composable
private fun successGreen() = if (MaterialTheme.colorScheme.isLight) Color(0xFF15803D) else Color(0xFF22C55E)

/**
 * Desktop Freedom's requests over OpenLV (#113), one sheet at a time,
 * over whatever the app is showing. Signing asks for the wallet to be
 * unlocked (biometric or screen lock) as part of the approval — a
 * Ledger's account (#142) signs on the Ledger instead; Back
 * rejects. Like the send review, the approve button ignores taps until
 * the sheet has been on screen and untouched for
 * [PromptTapGuard.SPEND_PROTECTION_MS] (a connect
 * [PromptTapGuard.PROTECTION_MS]), so a tap meant for what was there
 * before can't approve it; it also drops a press begun before then or
 * one another app's window covered, and other apps' overlays are hidden
 * while it's up (#240, [protectedPress]).
 */
@Composable
internal fun RemoteSigningHost() {
    val context = LocalContext.current
    val session = remember(context) { OpenLvSession.get(context) }
    val approval by session.approval.collectAsState()
    val current = approval ?: return
    RemoteSigningSheet(current)
}

@Composable
private fun RemoteSigningSheet(approval: OpenLvSession.Approval) {
    val context = LocalContext.current
    val vault = remember(context) { Vault.get(context) }
    val auth = remember(context) { BiometricVaultAuthenticator(context) }
    val vaultState by vault.state.collectAsState()
    val scope = rememberCoroutineScope()
    val request = approval.request
    var picked by remember(approval) { mutableStateOf((request as? OpenLvSession.Request.Connect)?.suggested) }
    var busy by remember(approval) { mutableStateOf(false) }
    var error by remember(approval) { mutableStateOf<String?>(null) }
    // Sign and Send can't be taken back: they arm later (#240).
    val tap = rememberArmedTapGuard(
        approval,
        if (request is OpenLvSession.Request.Connect) PromptTapGuard.PROTECTION_MS else PromptTapGuard.SPEND_PROTECTION_MS,
    )
    val guard = tap.guard
    val armed = tap.armed
    val backedUp = when (val s = vaultState) {
        is Vault.State.Locked -> s.info.backedUp
        is Vault.State.Unlocked -> s.info.backedUp
        else -> true
    }
    val unlockAction = stringResource(R.string.signing_remote_action_unlock_wallet)
    val reject = { if (!busy) approval.decide(OpenLvSession.Decision.Reject) }
    val approve = approve@{
        if (busy || !guard.accepts()) return@approve
        // Sharing an address needs no key; signing does, so the wallet opens first —
        // unless a Ledger signs it (#142): nothing on the phone to unlock.
        val needsKey = request !is OpenLvSession.Request.Connect && ledgerOf(request) == null
        busy = true
        error = null
        scope.launch {
            try {
                if (needsKey && !vault.unlockedNow()) vault.unlock(auth)
                approval.decide(OpenLvSession.Decision.Approve(picked))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = walletErrorMessage(e, unlockAction, backedUp)
            } finally {
                busy = false
            }
        }
    }
    val (title, action) = when (request) {
        is OpenLvSession.Request.Connect ->
            stringResource(R.string.signing_remote_title_connect) to stringResource(R.string.signing_remote_action_connect)
        is OpenLvSession.Request.PersonalSign ->
            stringResource(R.string.signing_remote_title_sign_message) to stringResource(R.string.signing_remote_action_sign)
        is OpenLvSession.Request.TypedData ->
            stringResource(R.string.signing_remote_title_sign_typed_data) to stringResource(R.string.signing_remote_action_sign)
        is OpenLvSession.Request.SendTransaction ->
            stringResource(R.string.signing_remote_title_send_transaction) to stringResource(R.string.signing_remote_action_confirm_send)
    }
    Dialog(
        onDismissRequest = reject,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        RestartsTapGuardInWindow(guard)
        val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.9f).dp
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.padding(16.dp).widthIn(max = 560.dp).fillMaxWidth().heightIn(max = maxHeight).imePadding(),
        ) {
            Column(Modifier.restartsTapGuard(guard).padding(20.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(
                    // The phone can't tell who made the code: a web page can show one too.
                    stringResource(R.string.signing_remote_asked_over_code),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    when (request) {
                        is OpenLvSession.Request.Connect -> ConnectBody(request, picked, tap, enabled = !busy) { picked = it }
                        is OpenLvSession.Request.PersonalSign -> PersonalSignBody(request)
                        is OpenLvSession.Request.TypedData -> TypedDataBody(request)
                        is OpenLvSession.Request.SendTransaction -> SendTransactionBody(request)
                    }
                    ledgerOf(request)?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            ledgerNote(it.deviceName, hashesOnly = (request as? OpenLvSession.Request.TypedData)?.ledgerHashes != null),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    error?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.height(16.dp))
                ObscuredTapNotice(tap)
                SheetButtonRow {
                    OutlinedButton(onClick = reject, enabled = !busy) { Text(stringResource(R.string.common_reject)) }
                    Button(onClick = approve, enabled = armed && !busy, modifier = Modifier.protectedPress(tap)) {
                        if (busy) {
                            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        } else {
                            Text(action)
                        }
                    }
                }
            }
        }
    }
}

/** The Ledger that signs what [request] asks for, if its account is a Ledger's (#142). */
internal fun ledgerOf(request: OpenLvSession.Request): baby.freedom.mobile.wallet.ledger.LedgerKey? = when (request) {
    is OpenLvSession.Request.PersonalSign -> request.account.ledger
    is OpenLvSession.Request.TypedData -> request.account.ledger
    is OpenLvSession.Request.SendTransaction -> request.quote.request.from.ledger
    is OpenLvSession.Request.Connect -> null
}

/**
 * The account to add, guarded like the Connect button
 * ([protectedSelectable]): a press before the dialog armed, or one
 * through another app's window, doesn't change which address the
 * computer learns (#287 R4-M1).
 */
@Composable
internal fun ConnectBody(
    request: OpenLvSession.Request.Connect,
    picked: WalletAccount?,
    tap: ArmedTapGuard,
    enabled: Boolean,
    onPick: (WalletAccount) -> Unit,
) {
    Text(
        stringResource(R.string.signing_remote_connect_explained),
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(8.dp))
    Column(Modifier.selectableGroup()) {
        for (account in request.accounts) {
            val selected = account.address == picked?.address
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
                    .protectedSelectable(tap, selected = selected, enabled = enabled) { onPick(account) }
                    .padding(vertical = 4.dp),
            ) {
                RadioButton(selected = selected, onClick = null, enabled = tap.armed && enabled)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(account.name, fontWeight = FontWeight.Medium)
                    AddressText(account.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun AccountRow(account: WalletAccount) {
    ReviewRow(stringResource(R.string.signing_review_account), account.name, address = account.address)
}

@Composable
private fun PersonalSignBody(request: OpenLvSession.Request.PersonalSign) {
    AccountRow(request.account)
    val text = request.text
    if (text != null) {
        ReviewRow(stringResource(R.string.signing_review_message), text)
    } else {
        HexRow(
            stringResource(R.string.signing_review_message),
            "0x" + request.message.toHex(),
            selector = false,
            detail = pluralStringResource(R.plurals.signing_review_binary_bytes, request.message.size, request.message.size),
        )
    }
    Spacer(Modifier.height(4.dp))
    Text(
        stringResource(R.string.signing_remote_personal_sign_warning),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun TypedDataBody(request: OpenLvSession.Request.TypedData) {
    AccountRow(request.account)
    // Only a chainId the domain type declares (as a uint) is signed; otherwise the signature isn't tied to any chain (R6-F1).
    val id = request.chainId
    if (id == null) {
        ReviewRow(
            stringResource(R.string.signing_review_network),
            stringResource(R.string.signing_review_network_any),
            detail = stringResource(R.string.signing_review_network_any_detail),
        )
    } else {
        ReviewRow(
            stringResource(R.string.signing_review_network),
            request.chain?.name ?: stringResource(R.string.signing_review_chain_id, id.toString()),
            detail = if (request.chain == null) stringResource(R.string.signing_review_chain_not_set_up) else null,
        )
    }
    ReviewRow(stringResource(R.string.signing_review_type), request.primaryType)
    TypedLines(stringResource(R.string.signing_review_domain), request.domain)
    TypedLines(stringResource(R.string.signing_review_message), request.message)
    request.ledgerHashes?.let { LedgerHashesOnly(it) }
    Spacer(Modifier.height(4.dp))
    Text(
        stringResource(R.string.signing_remote_typed_data_warning),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Indent steps a typed-data line gets on the sheet, [TYPED_INDENT_DP] each. */
internal const val TYPED_MAX_INDENT = 4
internal const val TYPED_INDENT_DP = 12

/**
 * How far in a typed-data line at [depth] is drawn: one step a level, but
 * never more than [TYPED_MAX_INDENT]. Nesting is unbounded, and an
 * uncapped indent pushes a deep field past the sheet's width, where it
 * would be signed without ever being drawn.
 */
internal fun typedIndent(depth: Int): Int = depth.coerceIn(0, TYPED_MAX_INDENT)

/** A typed-data line's label, naming its level once the indent stops showing it. */
internal fun typedLabel(line: baby.freedom.mobile.wallet.Eip712.Line): String =
    if (line.depth > TYPED_MAX_INDENT) Strings.get(R.string.signing_typed_level_label, line.depth, line.label) else line.label

@Composable
internal fun TypedLines(title: String, lines: List<baby.freedom.mobile.wallet.Eip712.Line>) {
    if (lines.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer {
            Column {
                for (line in lines) {
                    // Label and value on their own lines: a long address or number wraps whole, never cut.
                    Column(Modifier.padding(start = (typedIndent(line.depth) * TYPED_INDENT_DP).dp, top = 2.dp)) {
                        Text(typedLabel(line), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(line.value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

@Composable
private fun SendTransactionBody(request: OpenLvSession.Request.SendTransaction) {
    val quote = request.quote
    val r = quote.request
    val chain = r.chain
    request.notice?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(4.dp))
    }
    ReviewRow(stringResource(R.string.signing_review_network), chain.name)
    ReviewRow(stringResource(R.string.signing_review_from), r.from.name, address = r.from.address)
    ReviewRow(stringResource(R.string.signing_review_to), null, address = r.to)
    if (r.to.equals(r.from.address, ignoreCase = true)) {
        Text(stringResource(R.string.signing_review_to_self), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    ReviewRow(stringResource(R.string.signing_review_value), "${SendAmounts.exact(r.amount, chain.decimals)} ${chain.symbol}", mono = true)
    val data = "0x" + (r.dapp?.data?.toHex() ?: "")
    HexRow(
        stringResource(R.string.signing_review_data),
        data,
        selector = true,
        detail = if (data.length > 2) {
            pluralStringResource(R.plurals.signing_review_call_bytes, (data.length - 2) / 2, (data.length - 2) / 2)
        } else {
            stringResource(R.string.signing_review_plain_transfer)
        },
    )
    ReviewRow(stringResource(R.string.signing_review_network_fee), stringResource(R.string.signing_review_up_to, feeText(quote.tx.maxFee, chain)), mono = true, detail = feeDetail(quote.tx))
    quote.nativeTotal?.let { ReviewRow(stringResource(R.string.signing_review_total), stringResource(R.string.signing_review_up_to, feeText(it, chain)), mono = true) }
    ReviewRow(
        stringResource(R.string.signing_review_nonce),
        quote.tx.nonce.toString(),
        detail = nonceDetail(quote),
    )
    Spacer(Modifier.height(4.dp))
    Text(
        stringResource(R.string.signing_remote_send_footnote, feeFootnote(quote.tx)),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
