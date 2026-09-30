package baby.freedom.mobile.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import baby.freedom.mobile.R
import baby.freedom.mobile.chains.Chain
import baby.freedom.mobile.chains.rpc.ChainTrust
import baby.freedom.mobile.l10n.Strings
import baby.freedom.mobile.ui.isLight
import baby.freedom.mobile.wallet.SendQuote
import baby.freedom.mobile.wallet.TokenAmounts
import baby.freedom.mobile.wallet.TokenBalance
import baby.freedom.mobile.wallet.TokenRegistry
import baby.freedom.mobile.wallet.WalletAccount
import baby.freedom.mobile.wallet.WalletAccountList
import baby.freedom.mobile.wallet.WalletAccountStore

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
    if (account.ledger != null && !account.name.contains("Ledger", ignoreCase = true)) {
        Strings.get(R.string.wallet_accounts_label_ledger, account.name)
    } else {
        account.name
    }

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

/**
 * The active account and the switcher (#104): every account derived so
 * far, the active one selected; tapping another switches to it. Add
 * account derives the next one, which needs the wallet open ([locked]
 * makes it unlock first). Ledger accounts (#142) are listed with the
 * others, marked as the Ledger's; Connect a Ledger adds one (no unlock:
 * the key stays on the device), and Remove takes the active one off the list.
 */
@Composable
internal fun AccountsSection(
    list: WalletAccountList,
    locked: Boolean,
    busy: Boolean,
    onSelect: (Int) -> Unit,
    onAdd: () -> Unit,
    onReceive: () -> Unit,
    onConnectLedger: () -> Unit,
    onRemoveLedger: (WalletAccount) -> Unit,
) {
    val context = LocalContext.current
    val active = list.active
    SectionCard(title = stringResource(R.string.wallet_accounts_account_title)) {
        Text(accountLabel(active), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
        SelectionContainer {
            AddressText(
                active.address,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Text(
            accountPathLine(active),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Wraps a whole button to the next line at a large font size, never a label inside one.
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (active.isLedger) TextButton(onClick = { onRemoveLedger(active) }, enabled = !busy) { Text(stringResource(R.string.common_remove)) }
            TextButton(onClick = onReceive) { Text(stringResource(R.string.wallet_accounts_show_qr)) }
            TextButton(onClick = { copyToClipboard(context, active.address) }) {
                Text(stringResource(R.string.common_copy_address))
            }
        }
        if (list.accounts.size > 1) {
            HorizontalDivider()
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.wallet_accounts_switch),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            list.accounts.forEach { account ->
                val selected = account.index == active.index
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = selected,
                            enabled = !busy,
                            role = Role.RadioButton,
                            onClick = { onSelect(account.index) },
                        )
                        .padding(vertical = 4.dp),
                ) {
                    RadioButton(selected = selected, onClick = null, enabled = !busy)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(accountLabel(account), fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
                        AddressText(
                            account.address,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        if (list.accounts.size < WalletAccountStore.MAX_ACCOUNTS) {
            OutlinedButton(onClick = onAdd, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(
                    when {
                        busy -> stringResource(R.string.wallet_accounts_working)
                        locked -> stringResource(R.string.wallet_accounts_unlock_to_add)
                        else -> stringResource(R.string.wallet_accounts_add)
                    },
                )
            }
            // A Ledger's accounts need no unlock: the key stays on the Ledger (#142).
            OutlinedButton(
                onClick = onConnectLedger,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("wallet-connect-ledger"),
            ) { Text(stringResource(R.string.wallet_accounts_connect_ledger)) }
        }
    }
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
 * first unlock derives it. If that failed ([failed]: the list couldn't be
 * derived or saved), says so and offers to try again rather than
 * "Finding…" forever.
 */
@Composable
internal fun AccountsLockedSection(locked: Boolean, failed: Boolean, busy: Boolean, onRetry: () -> Unit) {
    SectionCard(title = stringResource(R.string.wallet_accounts_account_title)) {
        Text(
            when {
                locked -> stringResource(R.string.wallet_accounts_locked_unlock)
                failed -> stringResource(R.string.wallet_accounts_locked_failed)
                else -> stringResource(R.string.wallet_accounts_locked_finding)
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        if (!locked && failed) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onRetry, enabled = !busy) {
                    Text(stringResource(if (busy) R.string.wallet_accounts_working else R.string.common_try_again))
                }
            }
        }
    }
}

/**
 * The active account's balances (#104) on each of [chains] (Ethereum
 * and Gnosis): the native currency and the known ERC-20s
 * ([TokenRegistry]), each with how it was checked. A failed read says
 * so; it's never shown as zero.
 */
@Composable
internal fun BalancesSection(
    chains: List<Chain>,
    balances: Map<String, TokenBalance>,
    refreshing: Boolean,
    onRefresh: () -> Unit,
) {
    SectionCard(title = stringResource(R.string.wallet_accounts_balances_title)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(
                    if (refreshing) R.string.wallet_accounts_balances_reading else R.string.wallet_accounts_balances_source,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (refreshing) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                TextButton(onClick = onRefresh) { Text(stringResource(R.string.wallet_accounts_refresh)) }
            }
        }
        for (id in TokenRegistry.WALLET_CHAIN_IDS) {
            Spacer(Modifier.height(8.dp))
            // Built-in chains can't be removed; until the store has loaded, nothing to show.
            val chain = chains.firstOrNull { it.id == id } ?: continue
            Text(chain.name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            TokenRegistry.tokens(chain).forEach { token ->
                val text = balanceText(balances[token.key], token.decimals, refreshing)
                BalanceRow(token.symbol, token.name, text)
            }
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
 */
@Composable
private fun BalanceRow(symbol: String, name: String, text: BalanceText) {
    val amber = if (MaterialTheme.colorScheme.isLight) Color(0xFFB45309) else Color(0xFFF59E0B)
    val amount = text.amount ?: "—"
    val mono = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val label = @Composable {
        Text(symbol, fontWeight = FontWeight.Medium)
        Text(
            name,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    val detail = @Composable {
        Text(
            text.detail,
            style = MaterialTheme.typography.bodySmall,
            color = if (text.warn) amber else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
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
