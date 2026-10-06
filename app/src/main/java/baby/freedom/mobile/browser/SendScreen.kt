package baby.freedom.mobile.browser

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import baby.freedom.mobile.R
import baby.freedom.mobile.chains.BuiltInChains
import baby.freedom.mobile.chains.Chain
import baby.freedom.mobile.ens.EnsAddressResult
import baby.freedom.mobile.ens.toHex
import baby.freedom.mobile.l10n.Strings
import baby.freedom.mobile.ui.isLight
import baby.freedom.mobile.wallet.DappCall
import baby.freedom.mobile.wallet.EthTransaction
import baby.freedom.mobile.wallet.GasOracle
import baby.freedom.mobile.wallet.Recipients
import baby.freedom.mobile.wallet.ScannedCode
import baby.freedom.mobile.wallet.SendAmounts
import baby.freedom.mobile.wallet.SendException
import baby.freedom.mobile.wallet.SendQuote
import baby.freedom.mobile.wallet.SendRequest
import baby.freedom.mobile.wallet.SendStatus
import baby.freedom.mobile.wallet.Token
import baby.freedom.mobile.wallet.TokenAmounts
import baby.freedom.mobile.wallet.TokenBalance
import baby.freedom.mobile.wallet.TokenRegistry
import baby.freedom.mobile.wallet.TxHistory
import baby.freedom.mobile.wallet.TxRecord
import baby.freedom.mobile.wallet.Vault
import baby.freedom.mobile.wallet.VaultAuthenticator
import baby.freedom.mobile.wallet.WalletAccount
import baby.freedom.mobile.wallet.WalletAccounts
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
 * Why [input] isn't taken as an amount when it could be read two ways
 * ([SendAmounts.ambiguous]: `1,234` is 1.234 or 1234), quoting it as
 * the parser judged it (trimmed); null when it's not that. Every
 * amount field [SendAmounts.parse] reads shows this over its own
 * generic note, which would wrongly blame the decimals or say nothing
 * was entered.
 */
internal fun ambiguousAmountNote(input: String): String? {
    if (!SendAmounts.ambiguous(input)) return null
    val typed = input.trim()
    return Strings.get(R.string.send_amount_ambiguous, typed, typed.replace(',', '.'), typed.replace(",", ""))
}

/**
 * [feeDetail] of [quote]'s transaction, plus, on an OP Stack rollup,
 * the L1 data fee the review's "up to" also counts ([SendQuote.l1Fee]).
 */
internal fun feeDetail(quote: SendQuote): String {
    val gas = feeDetail(quote.tx)
    if (quote.l1Fee.signum() == 0) return gas
    return Strings.get(R.string.send_fee_detail_l1, gas, feeText(quote.l1Fee, quote.request.chain))
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
/** Where a [SendPrefill] the Send form shows came from (#317, #422): what its note above the form says. */
internal enum class FillSource {
    /** A payment link opened in the browser (#317). */
    LINK,

    /** A code scanned on the wallet's Scan page, or with the To field's camera (#422). */
    SCANNED,

    /** A payment request pasted into the To field (#422). */
    PASTED,
}

/** What the note above the Send form says: [prefill] filled it in, from [source]. */
internal data class FillNote(val prefill: SendPrefill, val source: FillSource)

/**
 * The note above a form [note] filled in. [assetKey]: the asset picked
 * now. The line saying which network was assumed (#317) shows only
 * while that asset is still the one it was assumed for: whoever filled
 * the form in with a request naming no network — a link, a scanned
 * code, a paste — is told so, never left to find out on the review.
 */
@Composable
private fun SendLinkNote(note: FillNote, assetKey: String?) {
    val prefill = note.prefill
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("send-link-note")) {
        Text(
            when (note.source) {
                FillSource.LINK -> prefill.origin?.let { stringResource(R.string.send_link_filled_from_site, permissionOriginDisplay(it)) }
                    ?: stringResource(R.string.send_link_filled)
                FillSource.SCANNED -> stringResource(R.string.send_scan_filled)
                FillSource.PASTED -> stringResource(R.string.send_paste_filled)
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        prefill.chainGuess?.takeIf { assetKey == prefill.tokenKey }?.let { guess ->
            Text(
                chainGuessNote(guess, note.source, prefillChainName(prefill)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("send-chain-guess"),
            )
        }
    }
}

/** Why [guess]'s network was filled in, worded for where the request came from ([source]); [chainName] is that network. */
internal fun chainGuessNote(guess: ChainGuess, source: FillSource, chainName: String): String = when (source) {
    FillSource.LINK -> when (guess) {
        ChainGuess.ETHEREUM_DEFAULT -> Strings.get(R.string.send_link_chain_assumed)
        ChainGuess.ONLY_CHAIN_WITH_TOKEN -> Strings.get(R.string.send_link_chain_from_token, chainName)
    }
    FillSource.SCANNED, FillSource.PASTED -> when (guess) {
        ChainGuess.ETHEREUM_DEFAULT -> Strings.get(R.string.send_request_chain_assumed)
        ChainGuess.ONLY_CHAIN_WITH_TOKEN -> Strings.get(R.string.send_request_chain_from_token, chainName)
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

/** Whether [balance] shows anything held: a current read, or the last one when the latest failed. */
private fun holds(balance: TokenBalance?): Boolean = when (balance) {
    is TokenBalance.Known -> balance.raw.signum() > 0
    is TokenBalance.Failed -> (balance.previous?.raw?.signum() ?: 0) > 0
    null -> false
}

/**
 * [assets] as the asset sheet lists them (#422): those the account holds
 * first, then the rest, each group in the wallet's own order (chain by
 * chain, native currency first).
 */
internal fun assetOrder(assets: List<Pair<Chain, Token>>, balances: Map<String, TokenBalance>): List<Pair<Chain, Token>> {
    val (held, empty) = assets.partition { holds(balances[it.second.key]) }
    return held + empty
}

/** How the Send form's amount stands as it's typed (#422). */
internal sealed interface AmountCheck {
    /** Nothing typed yet: nothing to say. */
    data object Empty : AmountCheck

    /** [raw] base units, which the balance read covers (or nothing was read to say otherwise). */
    data class Ok(val raw: BigInteger) : AmountCheck

    /**
     * Not usable, and [note] says why. [balance]: it's the account's
     * balance that's short — said again under the disabled Review.
     */
    data class Problem(val note: String, val balance: Boolean = false) : AmountCheck
}

/**
 * [input], an amount of [token] on [chain], judged as it's typed (#422):
 * how it reads ([SendAmounts.parse]), then against [held] — the token's
 * balance as last read — and, for a token, whether there's any of the
 * chain's own currency ([nativeHeld]) to pay the fee with. A balance
 * that wasn't read (null) blocks nothing: the review reads it again.
 * The fee itself is only known once priced, so a native send of the
 * whole balance is the review's to refuse (Max takes the fee off).
 */
internal fun amountCheck(input: String, token: Token, chain: Chain, held: BigInteger?, nativeHeld: BigInteger?): AmountCheck {
    if (input.isEmpty()) return AmountCheck.Empty
    ambiguousAmountNote(input)?.let { return AmountCheck.Problem(it) }
    val raw = SendAmounts.parse(input, token.decimals)
        ?: return AmountCheck.Problem(Strings.plural(R.plurals.send_amount_invalid, token.decimals, token.decimals))
    if (held != null && raw > held) {
        return AmountCheck.Problem(
            Strings.get(R.string.send_not_enough, token.symbol, TokenAmounts.format(held, token.decimals)),
            balance = true,
        )
    }
    if (!token.isNative && nativeHeld != null && nativeHeld.signum() == 0) {
        return AmountCheck.Problem(Strings.get(R.string.send_no_fee_currency, chain.symbol, chain.name), balance = true)
    }
    return AmountCheck.Ok(raw)
}

/**
 * Someone to suggest for an empty To field (#422): one of the user's own
 * accounts ([mine]), or an address they sent to before. [label] is the
 * account's or the typed name's, when there is one.
 */
internal data class RecipientSuggestion(val label: String?, val address: String, val mine: Boolean)

/**
 * The To field's suggestions for a send from [from] (#422): the user's
 * other [accounts], then up to [recent] addresses [records] show [from]
 * paid from Send ([TxRecord.payee]), newest first, each once and none of
 * them an own account.
 */
internal fun recipientSuggestions(
    accounts: List<WalletAccount>,
    from: String,
    records: List<TxRecord>,
    recent: Int = 3,
): List<RecipientSuggestion> {
    val mine = accounts.filter { !it.address.equals(from, ignoreCase = true) }
        .map { RecipientSuggestion(it.name, it.address, mine = true) }
    val own = accounts.map { it.address.lowercase() }.toSet() + from.lowercase()
    // Only payees the user chose on Send: a dApp's, a Safe's or a stamp
    // purchase's transaction goes to the contract it calls, never someone to pay.
    val sentTo = records.filter { it.payee && it.from.equals(from, ignoreCase = true) }
        .sortedByDescending { it.sentAt }
        .distinctBy { it.to.lowercase() }
        .filter { it.to.lowercase() !in own }
        .take(recent)
        .map { RecipientSuggestion(it.toName, it.to, mine = false) }
    return mine + sentTo
}

/**
 * A [SendPrefill.tokenKey] naming no asset (a scanned plain address,
 * #422): it matches none, so the page starts on its own default.
 */
internal const val ANY_ASSET = ""

/** What a scanned code (or pasted text) puts in the Send form (#422). */
internal sealed interface ScannedRecipient {
    /**
     * Pay [recipient]. [prefill]: a payment request's, as its link would
     * open Send — asset, maybe an amount, and which network was assumed;
     * null for a plain address.
     */
    data class Fill(val recipient: String, val prefill: SendPrefill? = null) : ScannedRecipient {
        /** What the Send page opens with for this: the request's own, or the address on the page's default asset. */
        fun sendPrefill(): SendPrefill = prefill ?: SendPrefill(origin = null, tokenKey = ANY_ASSET, recipient = recipient, amount = null)
    }

    /** Nothing to send to, and why. */
    data class Refused(val reason: String) : ScannedRecipient
}

/**
 * [text], read by the To field's camera (#422): an address, or an EIP-681
 * request read as a payment link is ([ethereumLinkRoute], so a network or
 * token Send can't pay is refused the same way); anything else isn't
 * someone to pay.
 */
internal fun scannedRecipient(text: String): ScannedRecipient = when (val code = ScannedCode.parse(text)) {
    is ScannedCode.Address -> ScannedRecipient.Fill(code.address)
    is ScannedCode.Payment -> when (val route = ethereumLinkRoute(text.trim(), private = false, walletReady = true, origin = null)) {
        is EthereumLinkRoute.OpenSend -> ScannedRecipient.Fill(route.prefill.recipient, route.prefill)
        is EthereumLinkRoute.Refuse -> ScannedRecipient.Refused(route.reason)
        EthereumLinkRoute.Drop -> ScannedRecipient.Refused(Strings.get(R.string.send_scan_not_address))
    }
    is ScannedCode.Unrecognized -> ScannedRecipient.Refused(code.reason)
    is ScannedCode.Pairing, is ScannedCode.SafeRequest -> ScannedRecipient.Refused(Strings.get(R.string.send_scan_not_address))
}

/** What the To field's Paste puts in the form (#422). */
internal sealed interface PastedRecipient {
    /** An address, or a payment request: filled in as a scanned one is ([ScannedRecipient.Fill]). */
    data class Fill(val fill: ScannedRecipient.Fill) : PastedRecipient

    /** A payment request Send can't pay, and why: nothing is filled in. */
    data class Refused(val reason: String) : PastedRecipient

    /** Anything else — a name, say — goes into the field as typed. */
    data class Text(val text: String) : PastedRecipient

    /**
     * A clip marked secret — this wallet's own recovery phrase or private
     * key ([PhraseClipboard.CLIP_LABEL], [PhraseClipboard.KEY_CLIP_LABEL]),
     * or anything another app flagged sensitive: never put in a field on
     * a page screenshots and Recents can see.
     */
    data object Secret : PastedRecipient
}

/**
 * The clip for the To field's Paste (#422), told apart by its
 * description first — [label] and whether it's flagged [sensitive] —
 * so a secret ([PastedRecipient.Secret]) is never shown; then [text]
 * as [scannedRecipient] reads it: an address or a payment request
 * fills the form in as a scanned one would, a request Send can't pay
 * is refused, and anything else is the text itself, trimmed. Null when
 * there's no text.
 */
internal fun pastedRecipient(label: CharSequence?, sensitive: Boolean, text: String?): PastedRecipient? {
    if (sensitive || PhraseClipboard.isSecretLabel(label)) return PastedRecipient.Secret
    val trimmed = text?.trim()?.take(MAX_PASTED_RECIPIENT)
    if (trimmed.isNullOrEmpty()) return null
    return when (val read = scannedRecipient(trimmed)) {
        is ScannedRecipient.Fill -> PastedRecipient.Fill(read)
        // Only a payment request is refused: a name, say, isn't one, and goes in as typed.
        is ScannedRecipient.Refused -> if (ScannedCode.parse(trimmed) is ScannedCode.Payment) {
            PastedRecipient.Refused(read.reason)
        } else {
            PastedRecipient.Text(trimmed)
        }
    }
}

/**
 * [pastedRecipient] for what's on the clipboard now. The description is
 * looked at before any item; the item is read as its plain text, never
 * coerced (a `content:` item isn't opened).
 */
internal fun pastedRecipient(context: Context): PastedRecipient? {
    val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip ?: return null
    val description = clip.description
    val sensitive = description?.extras?.getBoolean(PhraseClipboard.EXTRA_IS_SENSITIVE, false) == true
    if (sensitive || PhraseClipboard.isSecretLabel(description?.label)) return PastedRecipient.Secret
    return pastedRecipient(description?.label, sensitive = false, text = clip.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString())
}

/** More than any address or name: a longer paste is cut rather than kept whole in the field. */
private const val MAX_PASTED_RECIPIENT = 512

/**
 * Send (#105): the active account sends a native currency or a known
 * ERC-20 on one of the wallet's chains. Pick the asset, type the
 * address and amount, review what will be signed — the fee is priced
 * and the nonce read at that moment — and confirm. The confirmed send
 * runs in [WalletSender], so leaving this page doesn't stop it; coming
 * back shows where it got to.
 */
/**
 * The Send form's own fields — asset, recipient, amount, "all" — held
 * apart from [SendPage] so a caller can keep them while the page is off
 * screen: a link's page hidden behind a feature's request comes back with
 * what the user had typed, not the link's values again (#317 R2-M1).
 */
internal class SendDraft {
    var filled = false
    var assetKey by mutableStateOf<String?>(null)
    var recipient by mutableStateOf("")
    var amount by mutableStateOf("")
    var all by mutableStateOf(false)

    /** The note above the form: what filled it in, if anything did (#317, #422). */
    var note by mutableStateOf<FillNote?>(null)
}

@OptIn(ExperimentalLayoutApi::class)
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
    // Where the form's fields are kept, when they must outlive the page
    // (a link's Send page hidden while a feature's request needs the
    // wallet home, R2-M1); the page's own otherwise.
    draft: SendDraft? = null,
    // [prefill] came from a code scanned on the wallet's Scan page (#422), not a link.
    scanned: Boolean = false,
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
    // The form's fields live in [draft] when the caller keeps one, so the
    // user's edits outlive this page leaving composition (R2-M1); filled in
    // from the link only the first time.
    val form = draft ?: remember { SendDraft() }
    if (!form.filled) {
        form.filled = true
        // With nothing named, the first asset the account holds (#422).
        form.assetKey = prefilledAsset?.key ?: assetOrder(assets, balances).firstOrNull()?.second?.key
        form.recipient = prefill?.recipient.orEmpty()
        // In the link's asset's own decimals, every digit kept: a request's amount isn't rounded.
        form.amount = prefilledAsset?.let { token -> prefill.amount?.let { SendAmounts.exact(it, token.decimals) } }.orEmpty()
        form.note = prefill?.let { FillNote(it, if (scanned) FillSource.SCANNED else FillSource.LINK) }
    }
    var assetKey by form::assetKey
    val asset = assets.firstOrNull { it.second.key == assetKey } ?: assets.firstOrNull()
    var recipient by form::recipient
    var amount by form::amount
    var all by form::all
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
    // The asset sheet and the To field's camera (#422). The camera's permission
    // state is held here, above the list, so scrolling never asks again.
    var assetSheet by remember { mutableStateOf(false) }
    var scanSheet by remember { mutableStateOf(false) }
    // Why a Paste filled nothing in (a secret, a request Send can't pay); any edit clears it.
    var toNote by remember { mutableStateOf<String?>(null) }
    val cameraPermission = rememberCameraPermissionState()
    // Suggestions for an empty To field: the user's other accounts, then recent recipients.
    val accountList by remember(context) { WalletAccounts.get(context) }.accounts.collectAsState()
    val records by remember(context) { TxHistory.get(context) }.records.collectAsState()
    val suggestions = remember(accountList, records, account.address) {
        recipientSuggestions(accountList?.accounts.orEmpty(), account.address, records)
    }

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

    // The amount judged as it's typed (#422): Review waits for a usable one.
    val amountCheckNow = if (fieldToken != null && fieldChain != null) {
        amountCheck(
            amount,
            fieldToken,
            fieldChain,
            held = (balances[fieldToken.key] as? TokenBalance.Known)?.raw,
            nativeHeld = (balances["${fieldChain.id}:native"] as? TokenBalance.Known)?.raw,
        )
    } else {
        AmountCheck.Empty
    }

    fun price(then: (SendQuote) -> Unit, priced: suspend () -> SendQuote) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                then(priced())
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

    /**
     * The To field's Scan or Paste filling the form in from [read] (#422),
     * the one way both do it: the payee; for a payment request, also its
     * asset and amount, the way its link would open Send. A request that
     * names another asset but no amount leaves the amount empty — one
     * typed for the asset before isn't an amount of this one — and the
     * note above the form says where the request came from and, when it
     * named no network, which one was assumed.
     */
    fun fill(read: ScannedRecipient.Fill, source: FillSource) {
        val request = read.prefill
        val requested = request?.let { p -> assets.firstOrNull { it.second.key == p.tokenKey }?.second }
        if (requested != null && requested.key != assetKey) {
            assetKey = requested.key
            amount = ""
        }
        recipient = read.recipient
        if (requested != null) request.amount?.let { amount = SendAmounts.exact(it, requested.decimals) }
        all = false
        error = null
        toNote = null
        // A pasted plain address is just a paste; anything scanned, or a request, is said.
        form.note = when {
            request != null -> FillNote(request, source)
            source == FillSource.SCANNED -> FillNote(read.sendPrefill(), source)
            else -> null
        }
    }

    fun prepare(request: SendRequest, sendAll: Boolean, then: (SendQuote) -> Unit = { quote = it }) =
        price(then) { sender.prepare(request, sendAll) }

    /** [q] priced again as it was asked for: a Max send stays Max, less the new fee. */
    fun reprice(q: SendQuote, then: (SendQuote) -> Unit = { quote = it }) = price(then) { sender.reprice(q) }

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
                            val old = current.quote
                            sender.acknowledge()
                            reprice(old)
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
                        onOpenUrl = onOpenUrl,
                        onEdit = ::leaveReview,
                        onConfirm = {
                            // Priced too long ago to trust its fee: price it again and let the user look.
                            val reprice = {
                                reprice(q) { fresh ->
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
                    form.note?.let { note -> item("link") { SendLinkNote(note, assetKey) } }
                    item("from") {
                        Text(
                            stringResource(R.string.send_from_line, account.name, shortAddress(account.address)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
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
                                    toNote = null
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
                                trailingIcon = {
                                    Row {
                                        IconButton(
                                            enabled = !busy,
                                            onClick = {
                                                when (val pasted = pastedRecipient(context)) {
                                                    is PastedRecipient.Fill -> fill(pasted.fill, FillSource.PASTED)
                                                    is PastedRecipient.Text -> {
                                                        recipient = pasted.text
                                                        error = null
                                                        toNote = null
                                                    }
                                                    is PastedRecipient.Refused -> toNote = pasted.reason
                                                    PastedRecipient.Secret -> toNote = Strings.get(R.string.send_paste_secret)
                                                    null -> Unit
                                                }
                                            },
                                        ) { Icon(Icons.Filled.ContentPaste, contentDescription = stringResource(R.string.send_paste)) }
                                        IconButton(enabled = !busy, onClick = { scanSheet = true }) {
                                            Icon(Icons.Filled.QrCodeScanner, contentDescription = stringResource(R.string.send_scan))
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            toNote?.let { FieldNote(it, error = true) }
                            when {
                                recipient.isEmpty() -> RecipientSuggestions(suggestions, enabled = !busy) {
                                    recipient = it
                                    error = null
                                    toNote = null
                                }
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
                                // The field scrolls a long address out of view: here it is whole.
                                parsed is Recipients.Parsed.Ok -> {
                                    Spacer(Modifier.height(6.dp))
                                    AddressText(
                                        parsed.address,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    if (parsed.address.equals(account.address, ignoreCase = true)) {
                                        FieldNote(stringResource(R.string.send_own_address_note), error = false)
                                    }
                                }
                            }
                        }
                    }
                    item("amount") {
                        val held = token?.let { (balances[it.key] as? TokenBalance.Known)?.raw }
                        SectionCard(title = stringResource(R.string.send_label_amount)) {
                            OutlinedTextField(
                                value = amount,
                                onValueChange = {
                                    amount = it.trim()
                                    all = false
                                    error = null
                                },
                                enabled = !busy,
                                singleLine = true,
                                placeholder = {
                                    Text(stringResource(R.string.send_amount_placeholder), style = MaterialTheme.typography.headlineMedium)
                                },
                                textStyle = MaterialTheme.typography.headlineMedium,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                if (token != null && chain != null) {
                                    AssistChip(
                                        enabled = !busy,
                                        onClick = { assetSheet = true },
                                        label = { Text(stringResource(R.string.send_asset_chip, token.symbol, chain.name)) },
                                        trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
                                    )
                                }
                                FilterChip(
                                    selected = all,
                                    enabled = !busy && held != null && held.signum() > 0,
                                    onClick = {
                                        if (token != null && held != null) {
                                            amount = SendAmounts.exact(held, token.decimals)
                                            all = true
                                            error = null
                                        }
                                    },
                                    label = { Text(stringResource(R.string.send_max)) },
                                )
                            }
                            if (token != null) {
                                when (val check = amountCheckNow) {
                                    is AmountCheck.Problem -> FieldNote(check.note, error = true)
                                    else -> when {
                                        all && token.isNative -> FieldNote(stringResource(R.string.send_all_note), error = false)
                                        held != null -> FieldNote(
                                            stringResource(R.string.send_balance_note, TokenAmounts.format(held, token.decimals), token.symbol),
                                            error = false,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    item("review") {
                        val to = recipientAddress
                        val raw = (amountCheckNow as? AmountCheck.Ok)?.raw
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
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
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
    if (assetSheet) {
        AssetSheet(
            assets = assetOrder(assets, balances),
            selected = asset?.second,
            balances = balances,
            onPick = {
                assetKey = it.key
                all = false
                error = null
                assetSheet = false
            },
            onDismiss = { assetSheet = false },
        )
    }
    if (scanSheet) {
        RecipientScanSheet(
            permission = cameraPermission,
            onRead = { text ->
                when (val read = scannedRecipient(text)) {
                    is ScannedRecipient.Fill -> {
                        fill(read, FillSource.SCANNED)
                        scanSheet = false
                        null
                    }
                    is ScannedRecipient.Refused -> read.reason
                }
            },
            onDismiss = { scanSheet = false },
        )
    }
}

/**
 * The asset sheet (#422): every asset the wallet can send, those with a
 * balance first ([assetOrder]), each with its network under it and what
 * the account holds. One tap picks it and closes the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AssetSheet(
    assets: List<Pair<Chain, Token>>,
    selected: Token?,
    balances: Map<String, TokenBalance>,
    onPick: (Token) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("send-asset-sheet"),
    ) {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            item("title") {
                Text(
                    stringResource(R.string.send_asset_sheet_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp).semantics { heading() },
                )
            }
            items(assets, key = { it.second.key }) { (chain, token) ->
                val text = balanceText(balances[token.key], token.decimals, refreshing = false)
                val isSelected = token.key == selected?.key
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clip(MaterialTheme.shapes.small)
                        .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onPick(token) })
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(token.symbol, fontWeight = FontWeight.Medium)
                        Text(chain.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text.amount?.let { stringResource(R.string.send_balance_note, it, token.symbol) } ?: text.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (isSelected) {
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = stringResource(R.string.send_asset_selected),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The To field's Scan (#422): the camera in a sheet. The first code that
 * names someone to pay fills the form in ([onRead] returns null) and
 * closes it; anything else keeps scanning, with [onRead]'s reason shown.
 * [permission] is the page's ([rememberCameraPermissionState]), so the
 * automatic ask happens once per Send page, not once per sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecipientScanSheet(permission: CameraPermissionState, onRead: (String) -> String?, onDismiss: () -> Unit) {
    var problem by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }
    var last by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("send-scan-sheet"),
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            Text(
                stringResource(R.string.send_scan_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(12.dp))
            QrScanner(
                permission = permission,
                onCode = { text ->
                    // The same code read frame after frame is judged once.
                    if (!done && text != last) {
                        last = text
                        val reason = onRead(text)
                        if (reason == null) done = true else problem = reason
                    }
                },
                modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)),
            )
            problem?.let {
                Spacer(Modifier.height(8.dp))
                FieldNote(it, error = true)
            }
        }
    }
}

/** Suggestions under an empty To field (#422): the user's other accounts, then who they sent to lately. */
@Composable
private fun RecipientSuggestions(suggestions: List<RecipientSuggestion>, enabled: Boolean, onPick: (String) -> Unit) {
    if (suggestions.isEmpty()) return
    var lastMine: Boolean? = null
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        suggestions.forEach { s ->
            if (s.mine != lastMine) {
                Text(
                    stringResource(if (s.mine) R.string.send_suggestions_mine else R.string.send_suggestions_recent),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp).semantics { heading() },
                )
                lastMine = s.mine
            }
            Column(
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clip(MaterialTheme.shapes.small)
                    .clickable(enabled = enabled, role = Role.Button) { onPick(s.address) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                s.label?.let { Text(it, fontWeight = FontWeight.Medium) }
                AddressText(s.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * What will be signed, before anything is (#422): one sentence saying
 * what goes where ([sendHeadline]), the most the fee can be and the
 * total, the recipient and sender in full with Copy and the explorer;
 * network, token contract, nonce, gas and the fee footnote under
 * Details. Confirm, labelled with what moves, ignores taps for the
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
    onOpenUrl: (String) -> Unit,
    onEdit: () -> Unit,
    onConfirm: () -> Unit,
) {
    val request = quote.request
    val chain = request.chain
    val token = request.token
    val tap = rememberArmedTapGuard(quote, PromptTapGuard.SPEND_PROTECTION_MS)
    val guard = tap.guard
    val armed = tap.armed
    val amount = SendAmounts.exact(request.amount, token.decimals)
    val fee = feeText(quote.maxFee, chain)
    SectionCard(title = stringResource(R.string.send_review_title)) {
        TxReviewSummary(
            headline = sendHeadline(amount, token.symbol, request.to, request.toName, chain.name),
            fee = stringResource(R.string.send_up_to, fee),
            total = quote.nativeTotal?.let { stringResource(R.string.send_up_to, feeText(it, chain)) }
                ?: stringResource(R.string.send_total_token, amount, token.symbol, fee),
        ) {
            if (request.to.equals(request.from.address, ignoreCase = true)) {
                FieldNote(stringResource(R.string.send_self_send_note), error = false)
            }
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        CopyableAddressRow(
            label = stringResource(R.string.send_label_to),
            address = request.to,
            name = request.toName,
            explorerUrl = explorerAddressUrl(chain, request.to),
            onOpenUrl = onOpenUrl,
            below = { recipientTrust?.let { NameTrustLine(it) } },
        )
        CopyableAddressRow(
            label = stringResource(R.string.send_label_from),
            address = request.from.address,
            name = request.from.name,
            explorerUrl = explorerAddressUrl(chain, request.from.address),
            onOpenUrl = onOpenUrl,
        )
        DetailsExpander {
            ReviewRow(stringResource(R.string.send_label_network), chain.name)
            token.address?.let {
                CopyableAddressRow(
                    label = stringResource(R.string.send_label_token_contract, token.symbol),
                    address = it,
                    explorerUrl = explorerAddressUrl(chain, it),
                    onOpenUrl = onOpenUrl,
                )
            }
            ReviewRow(stringResource(R.string.send_label_nonce), quote.tx.nonce.toString(), mono = true, detail = nonceDetail(quote))
            ReviewRow(stringResource(R.string.send_label_gas), feeDetail(quote))
            request.toName?.let {
                Text(
                    stringResource(R.string.send_name_recheck_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Text(
                feeFootnote(quote.tx),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
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
    val sending = stringResource(R.string.send_sending)
    SheetButtonRow {
        OutlinedButton(onClick = onEdit, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.common_edit))
        }
        Button(
            onClick = { if (guard.accepts()) onConfirm() },
            enabled = armed && !busy,
            modifier = Modifier.heightIn(min = 48.dp).protectedPress(tap),
        ) {
            if (busy) {
                // Named, so TalkBack says what's happening rather than "Button, disabled" (W50).
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp).semantics { contentDescription = sending },
                )
            } else {
                Text(
                    stringResource(
                        if (request.from.isLedger) R.string.send_confirm_amount_ledger else R.string.send_confirm_amount,
                        amount,
                        token.symbol,
                    ),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * How a recipient name was checked (#277, #422): a name the servers
 * agreed on or a proof vouched for reads "✓ Verified name" — an
 * off-chain record a proof showed the resolver accepted says that
 * instead (#205) — with an info
 * button that opens what was checked and how; a name only one server
 * answered keeps its warning in full, since it's the user's call.
 */
@Composable
private fun NameTrustLine(trust: NameTrust) {
    if (trust.tier == TrustTier.Unverified) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            Icon(trust.tier.icon, contentDescription = null, tint = trust.tier.color, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(trust.tier.title, style = MaterialTheme.typography.labelLarge, color = trust.tier.color)
        }
        Text(trust.recipientSummary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    var about by remember { mutableStateOf(false) }
    // The tier's own green is too light to read on a light surface.
    val green = if (MaterialTheme.colorScheme.isLight) Color(0xFF15803D) else trust.tier.color
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            // A proof of an off-chain (CCIP-Read) answer shows only that the
            // resolver accepted it, not that the chain holds it (#205): said so here too.
            stringResource(if (trust.trust.offchain) R.string.send_name_proven_offchain else R.string.send_name_verified),
            style = MaterialTheme.typography.labelLarge,
            color = green,
            modifier = Modifier.weight(1f, fill = false),
        )
        IconButton(onClick = { about = true }) {
            Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.send_name_about))
        }
    }
    if (about) {
        AlertDialog(
            onDismissRequest = { about = false },
            icon = { Icon(trust.tier.icon, contentDescription = null, tint = trust.tier.color) },
            title = { Text(trust.tier.title) },
            text = { Text(trust.recipientSummary) },
            confirmButton = { TextButton(onClick = { about = false }) { Text(stringResource(R.string.common_ok)) } },
        )
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
                NameTrustLine(trust)
                when {
                    recipient is Recipients.Parsed.Invalid -> FieldNote(recipient.reason, error = true)
                    !result.trust.verified -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
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
                    TextButton(onClick = onRetry, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.common_try_again))
                    }
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
        // A problem is announced as it appears, not only found by exploring (W50).
        modifier = Modifier.padding(top = 4.dp).then(if (error) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier),
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
