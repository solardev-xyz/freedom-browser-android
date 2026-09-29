package baby.freedom.mobile.browser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import baby.freedom.mobile.chains.Chain
import baby.freedom.mobile.chains.rpc.ChainTrust
import baby.freedom.mobile.ui.isLight
import baby.freedom.mobile.wallet.TokenAmounts
import baby.freedom.mobile.wallet.TokenBalance
import baby.freedom.mobile.wallet.TokenRegistry
import baby.freedom.mobile.wallet.WalletAccountList
import baby.freedom.mobile.wallet.WalletAccountStore

/** How a balance was checked, in a word or two under the amount (#104). */
internal fun trustLabel(trust: ChainTrust): String = when (trust.level) {
    ChainTrust.Level.VERIFIED -> if (trust.source == baby.freedom.mobile.chains.rpc.ChainSource.QUORUM) {
        "Verified · ${trust.agreed.size} of ${trust.k} RPCs agreed"
    } else {
        "Verified · ${trust.source.label}"
    }
    ChainTrust.Level.USER_CONFIGURED -> "From your RPC"
    ChainTrust.Level.UNVERIFIED -> "Unverified · one RPC’s word"
}

/**
 * What a balance row shows: the amount (null when there's none to show)
 * and the line under it.
 */
internal data class BalanceText(val amount: String?, val detail: String, val warn: Boolean)

internal fun balanceText(balance: TokenBalance?, decimals: Int, refreshing: Boolean): BalanceText = when (balance) {
    null -> BalanceText(null, if (refreshing) "Reading…" else "Not read yet", warn = false)
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
                "Not updated: ${balance.reason}",
                warn = true,
            )
        } else {
            BalanceText(null, "Couldn’t read: ${balance.reason}", warn = true)
        }
    }
}

/**
 * The active account and the switcher (#104): every account derived so
 * far, the active one selected; tapping another switches to it. Add
 * account derives the next one, which needs the wallet open ([locked]
 * makes it unlock first).
 */
@Composable
internal fun AccountsSection(
    list: WalletAccountList,
    locked: Boolean,
    busy: Boolean,
    onSelect: (Int) -> Unit,
    onAdd: () -> Unit,
) {
    val context = LocalContext.current
    val active = list.active
    SectionCard(title = "Account") {
        Text(active.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
        SelectionContainer {
            Text(
                active.address,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Text(
            "Derivation path ${active.path}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { copyAddress(context, active.address) }) { Text("Copy address") }
        }
        if (list.accounts.size > 1) {
            HorizontalDivider()
            Spacer(Modifier.height(4.dp))
            Text(
                "Switch account",
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
                        Text(account.name, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
                        Text(
                            account.address,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
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
                        busy -> "Working…"
                        locked -> "Unlock to add an account"
                        else -> "Add account"
                    },
                )
            }
        }
    }
}

/** An opened wallet that has no account list yet (made before #104): the first unlock derives it. */
@Composable
internal fun AccountsLockedSection(locked: Boolean) {
    SectionCard(title = "Account") {
        Text(
            if (locked) "Unlock the wallet to see its accounts and their balances." else "Finding your accounts…",
            style = MaterialTheme.typography.bodyMedium,
        )
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
    SectionCard(title = "Balances") {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                if (refreshing) "Reading from the chains…" else "Read through each chain’s RPCs",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (refreshing) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                TextButton(onClick = onRefresh) { Text("Refresh") }
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

@Composable
private fun BalanceRow(symbol: String, name: String, text: BalanceText) {
    val amber = if (MaterialTheme.colorScheme.isLight) Color(0xFFB45309) else Color(0xFFF59E0B)
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(symbol, fontWeight = FontWeight.Medium)
            Text(
                name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.weight(1.4f)) {
            Text(
                text.amount ?: "—",
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.End,
            )
            Text(
                text.detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (text.warn) amber else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
            )
        }
    }
}

private fun copyAddress(context: Context, address: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Address", address))
    // Android 13+ shows its own clipboard confirmation.
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, "Address copied", Toast.LENGTH_SHORT).show()
    }
}
