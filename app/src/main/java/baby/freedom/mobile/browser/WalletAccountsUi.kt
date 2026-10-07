package baby.freedom.mobile.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import baby.freedom.mobile.R
import baby.freedom.mobile.chains.Chain
import baby.freedom.mobile.chains.rpc.ChainTrust
import baby.freedom.mobile.l10n.Strings
import baby.freedom.mobile.l10n.pluralText
import baby.freedom.mobile.ui.isLight
import baby.freedom.mobile.wallet.SafeState
import baby.freedom.mobile.wallet.SendQuote
import baby.freedom.mobile.wallet.TokenAmounts
import baby.freedom.mobile.wallet.Token
import baby.freedom.mobile.wallet.FiatQuotes
import baby.freedom.mobile.wallet.TokenBalance
import baby.freedom.mobile.wallet.TokenRegistry
import baby.freedom.mobile.wallet.WalletAccount
import baby.freedom.mobile.wallet.WalletAccountList
import baby.freedom.mobile.wallet.WalletAccountStore
import baby.freedom.mobile.wallet.WalletAccounts
import kotlinx.coroutines.launch

/**
 * What the Nonce row says under the number, on both screens that review a
 * [SendQuote] — the wallet's Send page and a site's transaction sheet
 * (#215 R6-F1): how the nonce was read, and, when this send takes the
 * place of one the user stopped tracking, that only one of the two can go
 * through.
 */
internal fun nonceDetail(quote: SendQuote): String =
    quote.replaces?.let { Strings.get(R.string.wallet_accounts_nonce_detail_replaces, trustLabel(quote.nonceTrust), it) }
        ?: trustLabel(quote.nonceTrust)

/** [SendQuote.replaces] in words: the stopped send [hash] this one outbids. */
internal fun replacesNote(hash: String): String =
    Strings.get(R.string.wallet_accounts_replaces_note, hash)

/** How a balance was checked, in a word or two under the amount (#104). */
internal fun trustLabel(trust: ChainTrust): String = when (trust.level) {
    ChainTrust.Level.VERIFIED -> if (trust.source == baby.freedom.mobile.chains.rpc.ChainSource.QUORUM) {
        Strings.plural(R.plurals.wallet_accounts_trust_verified_quorum, trust.k, trust.agreed.size, trust.k)
    } else {
        Strings.get(R.string.wallet_accounts_trust_verified_source, trust.source.label)
    }
    ChainTrust.Level.USER_CONFIGURED -> Strings.get(R.string.wallet_accounts_trust_user_configured)
    ChainTrust.Level.UNVERIFIED -> Strings.get(R.string.wallet_accounts_trust_unverified)
}

/** An account's name as lists show it, with where its key is when that's a Ledger and the name doesn't say so (#142). */
internal fun accountLabel(account: WalletAccount): String =
    if (account.ledger != null && !namesLedger(account.name)) {
        Strings.get(R.string.wallet_accounts_label_ledger, account.name)
    } else {
        account.name
    }

/**
 * Whether [name] already says its key is on a Ledger: it has the brand
 * in it, or it's the default Ledger-account name ("Ledger 2") in the app
 * language, which a translation may write in its own script (#313 R1-M4).
 */
private fun namesLedger(name: String): Boolean {
    if (name.contains("Ledger", ignoreCase = true)) return true
    val sample = Strings.get(R.string.wallet_account_default_ledger_name, DEFAULT_NAME_SAMPLE)
    val number = Regex("\\p{Nd}+").findAll(sample).maxByOrNull { it.value.length } ?: return false
    val pattern = Regex.escape(sample.substring(0, number.range.first)) + "\\p{Nd}+" +
        Regex.escape(sample.substring(number.range.last + 1))
    return Regex(pattern).matches(name.trim())
}

/** A number no default name has, to find where the number goes in one. */
private const val DEFAULT_NAME_SAMPLE = 987654321

/** Where [account]'s key is and at which path — the line under its address. */
internal fun accountPathLine(account: WalletAccount): String = account.ledger?.let {
    Strings.get(R.string.wallet_accounts_path_ledger, it.deviceName.removePrefix("Ledger "), account.path)
} ?: Strings.get(R.string.wallet_accounts_path_derivation, account.path)

/**
 * What a balance row shows: the amount (null when there's none to show)
 * and the line under it.
 */
internal data class BalanceText(val amount: String?, val detail: String, val warn: Boolean)

internal fun balanceText(balance: TokenBalance?, decimals: Int, refreshing: Boolean): BalanceText = when (balance) {
    null -> BalanceText(
        null,
        Strings.get(if (refreshing) R.string.wallet_accounts_balance_reading else R.string.wallet_accounts_balance_not_read),
        warn = false,
    )
    is TokenBalance.Known -> BalanceText(
        TokenAmounts.format(balance.raw, decimals),
        trustLabel(balance.trust),
        warn = balance.trust.level == ChainTrust.Level.UNVERIFIED,
    )
    is TokenBalance.Failed -> {
        val previous = balance.previous
        if (previous != null) {
            BalanceText(
                TokenAmounts.format(previous.raw, decimals),
                Strings.get(R.string.wallet_accounts_balance_not_updated, balance.reason),
                warn = true,
            )
        } else {
            BalanceText(null, Strings.get(R.string.wallet_accounts_balance_read_failed, balance.reason), warn = true)
        }
    }
}


/** Where a token's balance puts it on the wallet home's Assets list (W7). */
internal enum class AssetKind {
    /** Something there: a reading above zero, or one that was before a read failed. */
    HELD,

    /** Read as zero, and not on one RPC's word alone: collapsed under "N tokens with no balance". */
    NO_BALANCE,

    /** Can't be called zero: the read failed (nothing above zero before), or a zero only one RPC vouches for. */
    UNSURE,

    /** Not read yet this session. */
    UNREAD,
}

internal fun assetKind(balance: TokenBalance?): AssetKind = when (balance) {
    null -> AssetKind.UNREAD
    is TokenBalance.Known -> when {
        balance.raw.signum() > 0 -> AssetKind.HELD
        balance.trust.level == ChainTrust.Level.UNVERIFIED -> AssetKind.UNSURE
        else -> AssetKind.NO_BALANCE
    }
    is TokenBalance.Failed -> if ((balance.previous?.raw?.signum() ?: 0) > 0) AssetKind.HELD else AssetKind.UNSURE
}

/** One token on the Assets list: its chain, the token and its balance as last read (null: not yet). */
internal data class AssetRow(val chain: Chain, val token: Token, val balance: TokenBalance?) {
    val kind: AssetKind get() = assetKind(balance)
}

/**
 * The Assets list (W7): [shown] — what's held first, then whatever
 * can't be called zero (a failed read, a zero on one RPC's word, not
 * read yet), each group in the wallet's own order (Ethereum, then
 * Gnosis; the native currency, then the tokens) — and [noBalance], the
 * tokens read as zero, which the list collapses.
 */
internal data class WalletAssets(val shown: List<AssetRow>, val noBalance: List<AssetRow>) {
    /** Nothing read yet: the list shows that it's reading, not nine "Not read yet" rows. */
    val reading: Boolean get() = noBalance.isEmpty() && shown.isNotEmpty() && shown.all { it.balance == null }

    /** Every token read as zero, none in doubt: the "No funds yet" empty state. */
    val noFunds: Boolean get() = shown.isEmpty() && noBalance.isNotEmpty()

    val held: List<AssetRow> get() = shown.filter { it.kind == AssetKind.HELD }
}

internal fun walletAssets(chains: List<Chain>, balances: Map<String, TokenBalance>): WalletAssets {
    val rows = TokenRegistry.WALLET_CHAIN_IDS.mapNotNull { id -> chains.firstOrNull { it.id == id } }
        .flatMap { chain -> TokenRegistry.tokens(chain).map { AssetRow(chain, it, balances[it.key]) } }
    val order = listOf(AssetKind.HELD, AssetKind.UNSURE, AssetKind.UNREAD)
    return WalletAssets(
        // sortedBy is stable: each group keeps the wallet's order.
        shown = rows.filter { it.kind != AssetKind.NO_BALANCE }.sortedBy { order.indexOf(it.kind) },
        noBalance = rows.filter { it.kind == AssetKind.NO_BALANCE },
    )
}

/**
 * The wallet home's large balance (W1): the first asset held, in the
 * wallet's order — there are no prices to add different tokens up — and
 * the line under it: on which network, and how many more are held —
 * flagged amber when that figure is stale or one RPC's word alone; or
 * "No funds yet", or that the balances are still being read or couldn't
 * all be. [amount] is "—" when there's no figure to show.
 */
internal data class HeadlineBalance(val amount: String, val symbol: String?, val note: String?, val warn: Boolean = false)

internal fun headlineBalance(assets: WalletAssets, refreshing: Boolean): HeadlineBalance {
    val held = assets.held
    val first = held.firstOrNull()
    if (first != null) {
        val balance = first.balance
        val known = balance as? TokenBalance.Known ?: (balance as TokenBalance.Failed).previous!!
        val more = held.size - 1
        val note = listOfNotNull(
            Strings.get(R.string.wallet_home_on_chain, first.chain.name),
            if (more > 0) Strings.plural(R.plurals.wallet_home_more_assets, more, more) else null,
            when {
                balance is TokenBalance.Failed -> Strings.get(R.string.wallet_home_not_updated)
                // The same caveat its Assets row carries: the largest figure on the home mustn't look settled when it isn't.
                known.trust.level == ChainTrust.Level.UNVERIFIED -> Strings.get(R.string.wallet_home_unverified)
                else -> null
            },
        ).joinToString(" · ")
        val warn = balance is TokenBalance.Failed || known.trust.level == ChainTrust.Level.UNVERIFIED
        return HeadlineBalance(TokenAmounts.format(known.raw, first.token.decimals), first.token.symbol, note, warn = warn)
    }
    return when {
        assets.noFunds -> HeadlineBalance("0", null, Strings.get(R.string.wallet_home_no_funds))
        assets.reading || (refreshing && assets.shown.any { it.kind == AssetKind.UNREAD }) ->
            HeadlineBalance("—", null, Strings.get(R.string.wallet_accounts_balances_reading))
        assets.shown.isEmpty() -> HeadlineBalance("—", null, null)
        else -> HeadlineBalance("—", null, Strings.get(R.string.wallet_home_some_unread), warn = true)
    }
}

/** The account sheet's balance line for one account (W6): its headline, or that it hasn't been read this session. */
internal fun accountBalanceLine(chains: List<Chain>, balances: Map<String, TokenBalance>?): String {
    if (balances.isNullOrEmpty()) return Strings.get(R.string.wallet_accounts_balance_not_read_yet)
    val h = headlineBalance(walletAssets(chains, balances), refreshing = false)
    return when {
        h.symbol != null -> listOfNotNull("${h.amount} ${h.symbol}", h.note).joinToString(" · ")
        else -> h.note ?: h.amount
    }
}


/**
 * The wallet home's header (W1): the account chip (name and
 * `0x1234…abcd`; a tap opens the account sheet), the large balance
 * ([headlineBalance]) and three round buttons — Send, Receive, Scan.
 * The balance shows whether the wallet is locked or not: addresses and
 * balances are public, and whatever needs the key asks for it then.
 */
@Composable
internal fun WalletHeader(
    account: WalletAccount,
    headline: HeadlineBalance,
    /** The approximate value under the balance (#439, [headlineFiat]); null with Show prices off or no price. */
    fiat: String? = null,
    enabled: Boolean,
    sendEnabled: Boolean,
    onAccounts: () -> Unit,
    onSend: () -> Unit,
    onReceive: () -> Unit,
    onScan: () -> Unit,
) {
    val amber = if (MaterialTheme.colorScheme.isLight) Color(0xFFB45309) else Color(0xFFF59E0B)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
    ) {
        AccountChip(account, enabled = enabled, onClick = onAccounts)
        Spacer(Modifier.height(16.dp))
        // The figure never breaks between two digits (amountBreaks); the symbol stays with it.
        Text(
            amountBreaks(headline.amount) + (headline.symbol?.let { " $it" } ?: ""),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("wallet-headline"),
        )
        fiat?.let {
            Text(
                it,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("wallet-headline-fiat"),
            )
        }
        headline.note?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = if (headline.warn) amber else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            RoundAction(Icons.AutoMirrored.Filled.Send, stringResource(R.string.wallet_home_send), enabled && sendEnabled, onSend, Modifier.weight(1f))
            RoundAction(Icons.Filled.QrCode, stringResource(R.string.wallet_home_receive), enabled, onReceive, Modifier.weight(1f))
            RoundAction(Icons.Filled.QrCodeScanner, stringResource(R.string.wallet_home_scan), enabled, onScan, Modifier.weight(1f))
        }
    }
}

/** One of the header's round buttons: a 56 dp circle with its label under it, one button to TalkBack. */
@Composable
private fun RoundAction(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 6.dp),
    ) {
        val alpha = if (enabled) 1f else 0.45f
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = alpha)),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = alpha))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
            textAlign = TextAlign.Center,
        )
    }
}

/** The header's account chip: who's active, as name and short address. */
@Composable
private fun AccountChip(account: WalletAccount, enabled: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClickLabel = stringResource(R.string.wallet_home_chip_action),
                onClick = onClick,
            )
            .padding(start = 6.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)
            .testTag("wallet-account-chip"),
    ) {
        AccountAvatar(account)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f, fill = false)) {
            Text(accountLabel(account), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text(
                shortAddress(account.address),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(4.dp))
        Icon(Icons.Filled.ExpandMore, contentDescription = null)
    }
}

/**
 * Up to two characters for an account's badge: the first of its first
 * word and, with more than one word, the first of its last ("Account 2"
 * → "A2", "Cold storage" → "CS", "Savings" → "S"). Whole code points, so
 * an emoji isn't cut in half.
 */
internal fun avatarInitials(name: String): String {
    val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    fun first(w: String) = w.substring(0, w.offsetByCodePoints(0, 1)).uppercase()
    return when (words.size) {
        0 -> ""
        1 -> first(words[0])
        else -> first(words[0]) + first(words.last())
    }
}

/** A round badge with the account's initials ([avatarInitials]), tinted by its address so accounts tell apart at a glance. */
@Composable
internal fun AccountAvatar(account: WalletAccount, size: Dp = 32.dp) {
    val hue = (account.address.lowercase().hashCode().toLong() and 0xffffffffL) % 360
    val color = Color.hsv(hue.toFloat(), 0.45f, if (MaterialTheme.colorScheme.isLight) 0.75f else 0.6f)
    val initial = avatarInitials(account.name)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(size).clip(CircleShape).background(color).clearAndSetSemantics {},
    ) {
        // Sized from the badge, not the font scale: it's decoration (the name beside it is the text),
        // and two letters must fit the circle at any scale.
        val fontSize = with(LocalDensity.current) { (size * 0.38f).toSp() }
        Text(initial, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = fontSize, maxLines = 1, softWrap = false)
    }
}

/**
 * The wallet home's Assets (W7), for the active account: what's held
 * first, whatever can't be called zero after it (highlighted, with why),
 * and the tokens read as zero collapsed under "N tokens with no balance".
 * With nothing held at all, "No funds yet" and Receive. A row is a
 * button: it says how its balance was checked. Pull-to-refresh is the
 * page's, and every row also offers Refresh as a TalkBack action.
 */
@Composable
internal fun AssetsSection(
    chains: List<Chain>,
    balances: Map<String, TokenBalance>,
    refreshing: Boolean,
    onReceive: () -> Unit,
    onRefresh: () -> Unit = {},
    /** Approximate values (#439), with Show prices on. */
    fiat: FiatQuotes? = null,
) {
    val assets = walletAssets(chains, balances)
    var explain by remember { mutableStateOf<AssetRow?>(null) }
    var showEmpty by rememberSaveable { mutableStateOf(false) }
    SectionCard(title = stringResource(R.string.wallet_assets_title)) {
        if (assets.reading) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp)) {
                if (refreshing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                }
                Text(
                    stringResource(if (refreshing) R.string.wallet_accounts_balances_reading else R.string.wallet_accounts_balance_not_read),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            AssetRows(assets, refreshing, showEmpty, { showEmpty = it }, { explain = it }, onRefresh, onReceive, fiat)
        }
    }
    explain?.let { row ->
        val text = balanceText(row.balance, row.token.decimals, refreshing)
        AlertDialog(
            onDismissRequest = { explain = null },
            title = { Text(stringResource(R.string.wallet_assets_trust_title, row.token.symbol, row.chain.name)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text.detail, fontWeight = FontWeight.Medium)
                    Text(stringResource(R.string.wallet_assets_trust_explain), style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = { TextButton(onClick = { explain = null }) { Text(stringResource(R.string.common_ok)) } },
        )
    }
}

/** No funds at all (W7): the header already says "No funds yet"; here, how to get some, and Receive. */
@Composable
private fun NoFunds(onReceive: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.wallet_home_no_funds_detail),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onReceive, modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(Icons.Filled.QrCode, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.wallet_home_receive))
        }
    }
}

/**
 * The account sheet (W6), from the header's chip: every account with its
 * name, short address and balance as read this session (only the active
 * account's balances are read, so the RPCs aren't handed every address
 * of the wallet together); a tap switches to it, ⓘ opens its details.
 * Add account, Connect a Ledger and the Safe accounts (#141) are at the
 * bottom. [locked]: Add account asks to unlock first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AccountSheet(
    list: WalletAccountList,
    chains: List<Chain>,
    balances: Map<String, Map<String, TokenBalance>>,
    safes: SafeState?,
    locked: Boolean,
    busy: Boolean,
    onSelect: (WalletAccount) -> Unit,
    onDetails: (WalletAccount) -> Unit,
    onAdd: () -> Unit,
    onConnectLedger: () -> Unit,
    onOpenSafe: (String) -> Unit,
    onCreateSafe: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // Slides the sheet away, then acts — never once the sheet was torn down another way meanwhile.
    fun closeThen(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion { cause ->
            onDismiss()
            if (cause == null) action()
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.testTag("wallet-account-sheet"),
        ) {
            item("title") {
                Text(
                    stringResource(R.string.wallet_accounts_sheet_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 8.dp).semantics { heading() },
                )
            }
            items(list.accounts, key = { "account-${it.index}" }) { account ->
                val selected = account.index == list.active.index
                AccountSheetRow(
                    account = account,
                    selected = selected,
                    balanceLine = accountBalanceLine(chains, balances[account.address.lowercase()]),
                    enabled = !busy,
                    onSelect = { closeThen { onSelect(account) } },
                    onDetails = { closeThen { onDetails(account) } },
                )
            }
            item("actions") {
                Column(Modifier.padding(top = 8.dp)) {
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    if (list.accounts.size < WalletAccountStore.MAX_ACCOUNTS) {
                        PageRow(
                            title = stringResource(R.string.wallet_accounts_add),
                            subtitle = stringResource(
                                if (locked) R.string.wallet_accounts_add_subtitle_locked else R.string.wallet_accounts_add_subtitle,
                            ),
                            style = PageRowStyle.Inset,
                            leadingIcon = Icons.Filled.Add,
                            enabled = !busy,
                            onClick = { closeThen(onAdd) },
                            modifier = Modifier.testTag("wallet-add-account"),
                        )
                        // A Ledger's accounts need no unlock: the key stays on the Ledger (#142).
                        PageRow(
                            title = stringResource(R.string.wallet_accounts_connect_ledger),
                            subtitle = stringResource(R.string.wallet_accounts_connect_ledger_subtitle),
                            style = PageRowStyle.Inset,
                            leadingIcon = Icons.Filled.Usb,
                            enabled = !busy,
                            onClick = { closeThen(onConnectLedger) },
                            modifier = Modifier.testTag("wallet-connect-ledger"),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    SafeAccountsSection(
                        state = safes,
                        enabled = !busy,
                        onOpen = { address -> closeThen { onOpenSafe(address) } },
                        onCreate = { closeThen(onCreateSafe) },
                    )
                }
            }
        }
    }
}

/** One account in the sheet: a tap makes it the active one; ⓘ opens its details. */
@Composable
private fun AccountSheetRow(
    account: WalletAccount,
    selected: Boolean,
    balanceLine: String,
    enabled: Boolean,
    onSelect: () -> Unit,
    onDetails: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 56.dp)
                .clip(MaterialTheme.shapes.medium)
                .let { if (selected) it.background(MaterialTheme.colorScheme.surfaceVariant) else it }
                .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
                .padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            AccountAvatar(account, size = 36.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(accountLabel(account), fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
                Text(
                    shortAddress(account.address),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(balanceLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (selected) {
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        }
        IconButton(onClick = onDetails, enabled = enabled, modifier = Modifier.testTag("wallet-account-details-${account.index}")) {
            Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.wallet_accounts_details_of, accountLabel(account)))
        }
    }
}

/**
 * One account's details (W5, W6): its name (Rename), its full address in
 * groups of four ([groupedAddress], so a large font never breaks it
 * mid-group) with Copy and Show QR code, and under Advanced, where its
 * key is and at which path, Export private key… (only for a key derived
 * on this phone, #323) and — for a Ledger's — Remove.
 */
@Composable
internal fun AccountDetailsPage(
    account: WalletAccount,
    active: Boolean,
    busy: Boolean,
    error: String?,
    onRename: (String) -> Unit,
    onUse: () -> Unit,
    onReceive: () -> Unit,
    onExportKey: () -> Unit,
    onRemoveLedger: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var renaming by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    FullScreenScaffold(title = stringResource(R.string.wallet_accounts_details_title), onDismiss = onBack) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item("name") {
                SectionCard(title = stringResource(R.string.wallet_accounts_name_section)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AccountAvatar(account, size = 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(accountLabel(account), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                            if (active) {
                                Text(
                                    stringResource(R.string.wallet_accounts_in_use),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        if (!active) TextButton(onClick = onUse, enabled = !busy) { Text(stringResource(R.string.wallet_accounts_use)) }
                        TextButton(onClick = { renaming = true }, enabled = !busy, modifier = Modifier.testTag("wallet-rename")) {
                            Text(stringResource(R.string.wallet_accounts_rename))
                        }
                    }
                }
            }
            item("address") {
                SectionCard(title = stringResource(R.string.wallet_accounts_address_section)) {
                    // In groups of four, as Receive shows it: a large font wraps it between groups only (W52).
                    Text(
                        groupedAddress(account.address),
                        style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.semantics { contentDescription = account.address },
                    )
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onReceive) { Text(stringResource(R.string.wallet_accounts_show_qr)) }
                        TextButton(onClick = { copyToClipboard(context, account.address) }) {
                            Text(stringResource(R.string.common_copy_address))
                        }
                    }
                }
            }
            item("advanced") {
                SectionCard(title = stringResource(R.string.wallet_accounts_advanced)) {
                    Text(accountPathLine(account), style = MaterialTheme.typography.bodyMedium)
                    if (account.hasLocalKey) {
                        Spacer(Modifier.height(4.dp))
                        PageRow(
                            title = stringResource(R.string.wallet_key_export),
                            subtitle = stringResource(R.string.wallet_key_export_warning),
                            style = PageRowStyle.Inset,
                            leadingIcon = Icons.Filled.Key,
                            enabled = !busy,
                            onClick = onExportKey,
                            modifier = Modifier.testTag("wallet-show-key"),
                        )
                    }
                    if (account.isLedger) {
                        PageRow(
                            title = stringResource(R.string.wallet_accounts_remove_ledger),
                            subtitle = stringResource(R.string.wallet_accounts_remove_ledger_subtitle),
                            style = PageRowStyle.Inset,
                            leadingIcon = Icons.Filled.DeleteOutline,
                            enabled = !busy,
                            onClick = onRemoveLedger,
                        )
                    }
                }
            }
            error?.let { item("error") { WalletErrorText(it) } }
        }
    }
    if (renaming) {
        RenameAccountDialog(
            current = account.name,
            onSave = {
                renaming = false
                onRename(it)
            },
            onDismiss = { renaming = false },
        )
    }
}

/** Rename an account (W6): the field says what's wrong with a name as it's typed, and Save stays off till it's fine. */
@Composable
private fun RenameAccountDialog(current: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(TextFieldValue(current, TextRange(0, current.length))) }
    // A paste longer than a name can hold was cut: say so, so the field doesn't look like it ignored it.
    var cut by remember { mutableStateOf(false) }
    val problem = WalletAccounts.accountNameProblem(name.text)
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.wallet_accounts_rename_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { v ->
                        // Never more than a name can hold: a longer paste is cut here, on a
                        // character boundary, and the field says so (what Save keeps is what shows).
                        val kept = WalletAccounts.cutAccountName(v.text)
                        cut = kept.length < v.text.length
                        name = if (cut) TextFieldValue(kept, TextRange(kept.length)) else v
                    },
                    label = { Text(stringResource(R.string.wallet_accounts_name_label)) },
                    singleLine = true,
                    isError = problem != null,
                    supportingText = {
                        Text(
                            problem ?: if (cut) {
                                stringResource(R.string.wallet_accounts_name_cut, WalletAccountStore.MAX_NAME)
                            } else {
                                stringResource(R.string.wallet_accounts_name_hint)
                            },
                        )
                    },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (problem == null) onSave(name.text) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("wallet-rename-field"),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.text) }, enabled = problem == null) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
}


/**
 * An address in monospace that never leaves a character or two alone on
 * a second line: on one line if it fits (shrunk to no less than
 * [MIN_ADDRESS_SCALE] of [style]'s size if that's what it takes),
 * otherwise as two even halves, one under the other.
 */
@Composable
internal fun AddressText(address: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    val mono = style.copy(fontFamily = FontFamily.Monospace, color = color)
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val maxWidth = constraints.maxWidth
        // Keyed on the measurer too: it's replaced when the density or font
        // scale changes, and a size fitted at the old scale would clip.
        val fitted = remember(address, mono, maxWidth, measurer) {
            fittedAddressSize(mono.fontSize, maxWidth) { size ->
                measurer.measure(address, mono.copy(fontSize = size), softWrap = false, maxLines = 1).size.width
            }
        }
        if (fitted != null) {
            Text(address, style = mono.copy(fontSize = fitted), softWrap = false, maxLines = 1)
        } else {
            val half = (address.length + 1) / 2
            Column {
                Text(address.substring(0, half), style = mono)
                Text(address.substring(half), style = mono)
            }
        }
    }
}

/**
 * The largest size from [full] down to [MIN_ADDRESS_SCALE] of it at which
 * the text, [widthAt] px wide, fits in [maxWidth] px; null if even the
 * smallest doesn't.
 */
internal fun fittedAddressSize(full: TextUnit, maxWidth: Int, widthAt: (TextUnit) -> Int): TextUnit? {
    if (maxWidth == Constraints.Infinity) return full
    val fullWidth = widthAt(full)
    if (fullWidth <= maxWidth) return full
    // A monospace line's width scales with the size: start from that
    // estimate, then step down until it really fits.
    var scale = maxWidth.toFloat() / fullWidth
    while (scale >= MIN_ADDRESS_SCALE) {
        val size = full * scale
        if (widthAt(size) <= maxWidth) return size
        scale -= 0.01f
    }
    return null
}

internal const val MIN_ADDRESS_SCALE = 0.85f

/**
 * An opened wallet that has no account list yet (made before #104): the
 * first unlock derives it, so a locked one offers Unlock here. If that
 * failed ([failed]: the list couldn't be derived or saved), says so and
 * offers to try again rather than "Finding…" forever.
 */
@Composable
internal fun AccountsLockedSection(locked: Boolean, failed: Boolean, busy: Boolean, onRetry: () -> Unit, onUnlock: () -> Unit) {
    SectionCard(title = stringResource(R.string.wallet_accounts_account_title)) {
        Text(
            when {
                locked -> stringResource(R.string.wallet_accounts_locked_unlock)
                failed -> stringResource(R.string.wallet_accounts_locked_failed)
                else -> stringResource(R.string.wallet_accounts_locked_finding)
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        if (locked) {
            Spacer(Modifier.height(8.dp))
            Button(onClick = onUnlock, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (busy) R.string.wallet_unlocking else R.string.wallet_unlock))
            }
        } else if (failed) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onRetry, enabled = !busy) {
                    Text(stringResource(if (busy) R.string.wallet_accounts_working else R.string.common_try_again))
                }
            }
        }
    }
}


/** The Assets list's rows once something has been read: [AssetsSection] without its card. */
@Composable
private fun AssetRows(
    assets: WalletAssets,
    refreshing: Boolean,
    showEmpty: Boolean,
    onShowEmpty: (Boolean) -> Unit,
    onExplain: (AssetRow) -> Unit,
    onRefresh: () -> Unit,
    onReceive: () -> Unit,
    fiat: FiatQuotes?,
) {
    if (assets.noFunds) NoFunds(onReceive)
    assets.shown.forEach { row ->
        BalanceRow(row, balanceText(row.balance, row.token.decimals, refreshing), assetFiat(fiat, row), onExplain = { onExplain(row) }, onRefresh = onRefresh)
    }
    if (assets.noBalance.isEmpty()) return
    val n = assets.noBalance.size
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.small)
            .toggleable(value = showEmpty, role = Role.Switch, onValueChange = onShowEmpty)
            .padding(vertical = 4.dp)
            .testTag("wallet-no-balance-toggle"),
    ) {
        Text(
            pluralText(R.plurals.wallet_assets_no_balance, n, n),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Icon(if (showEmpty) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
    }
    if (showEmpty) {
        assets.noBalance.forEach { row ->
            BalanceRow(row, balanceText(row.balance, row.token.decimals, refreshing), assetFiat(fiat, row), onExplain = { onExplain(row) }, onRefresh = onRefresh)
        }
    }
}

/**
 * One token's balance. The amount is never soft-wrapped mid-number (a
 * wrapped `60,562.1027` / `99` reads as a smaller amount): it sits next to
 * the symbol when it fits that column on one line, else gets the row's
 * whole width on its own line, shrunk a little if that makes it fit
 * ([fittedAddressSize]). Only a number too long even for that breaks,
 * and then only after a `,` or the `.`, which stays at the end of the
 * line so the line visibly continues ([amountBreaks]).
 *
 * How the balance was checked (W7) is a small check by the symbol when
 * it was verified; only a reading in doubt (failed, or on one RPC's word)
 * spells it out under the amount, in amber. The row is a button that
 * explains either way, and has Refresh as a TalkBack action.
 */
@Composable
private fun BalanceRow(row: AssetRow, text: BalanceText, fiat: String?, onExplain: () -> Unit, onRefresh: () -> Unit) {
    val amber = if (MaterialTheme.colorScheme.isLight) Color(0xFFB45309) else Color(0xFFF59E0B)
    val amount = text.amount ?: "—"
    val mono = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val verified = (row.balance as? TokenBalance.Known)?.trust?.level == ChainTrust.Level.VERIFIED
    val showDetail = text.warn || text.amount == null
    val refreshLabel = stringResource(R.string.wallet_assets_refresh)
    val label = @Composable {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(row.token.symbol, fontWeight = FontWeight.Medium)
            if (verified) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.Filled.Verified,
                    contentDescription = text.detail,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
            } else if (text.warn) {
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Filled.Warning, contentDescription = null, tint = amber, modifier = Modifier.size(16.dp))
            }
        }
        Text(
            stringResource(R.string.wallet_assets_token_on_chain, row.token.name, row.chain.name),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    val detail = @Composable {
        // The approximate value (#439): under the amount, never in place of it.
        fiat?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth().testTag("wallet-asset-fiat"),
            )
        }
        if (showDetail) {
            Text(
                text.detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (text.warn) amber else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.small)
            .let { if (text.warn) it.background(amber.copy(alpha = 0.08f)) else it }
            .clickable(
                role = Role.Button,
                onClickLabel = stringResource(R.string.wallet_assets_trust_action),
                onClick = onExplain,
            )
            .semantics { customActions = listOf(CustomAccessibilityAction(refreshLabel) { onRefresh(); true }) }
            .padding(vertical = 4.dp, horizontal = 4.dp),
    ) {
        val maxWidth = constraints.maxWidth
        val gap = with(density) { 12.dp.roundToPx() }
        // The amount column's share of the row next to the symbol (weights 1 : 1.4).
        val column = if (maxWidth == Constraints.Infinity) maxWidth else ((maxWidth - gap) * 1.4f / 2.4f).toInt()
        val widthAt = { size: TextUnit ->
            measurer.measure(amount, mono.copy(fontSize = size), softWrap = false, maxLines = 1).size.width
        }
        // Keyed on the measurer too: it's replaced when the density or font scale changes.
        val sideBySide = remember(amount, mono, column, measurer) {
            column == Constraints.Infinity || widthAt(mono.fontSize) <= column
        }
        if (sideBySide) {
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) { label() }
                Spacer(Modifier.width(12.dp))
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.weight(1.4f)) {
                    Text(amount, style = mono, softWrap = false, maxLines = 1)
                    detail()
                }
            }
        } else {
            val fitted = remember(amount, mono, maxWidth, measurer) {
                fittedAddressSize(mono.fontSize, maxWidth, widthAt)
            }
            Column(Modifier.fillMaxWidth()) {
                label()
                if (fitted != null) {
                    Text(
                        amount,
                        style = mono.copy(fontSize = fitted),
                        softWrap = false,
                        maxLines = 1,
                        textAlign = TextAlign.End,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(
                        amountBreaks(amount),
                        style = mono,
                        textAlign = TextAlign.End,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                detail()
            }
        }
    }
}


/**
 * [amount] with a zero-width space after every `,` and the `.`, so a
 * number too long for one line wraps only there — the separator ending
 * the line shows it goes on — never between two digits.
 */
internal fun amountBreaks(amount: String): String = buildString {
    for (c in amount) {
        append(c)
        if (c == ',' || c == '.') append('\u200B')
    }
}
