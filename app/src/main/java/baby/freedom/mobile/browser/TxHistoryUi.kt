package baby.freedom.mobile.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import baby.freedom.mobile.chains.Chain
import baby.freedom.mobile.ui.isLight
import baby.freedom.mobile.wallet.SendAmounts
import baby.freedom.mobile.wallet.TxRecord
import java.text.DateFormat
import java.util.Date
import java.util.Locale

internal const val TX_HISTORY_TITLE = "Transactions"

/** How many of the latest the wallet page lists before "All transactions". */
internal const val TX_HISTORY_PREVIEW = 5

/** "Sent 0.5 xDAI": what a record moved. */
internal fun txTitle(r: TxRecord): String = "Sent ${SendAmounts.exact(r.amount, r.tokenDecimals)} ${r.tokenSymbol}"

/** A record's status as a heading and the sentence under it. */
internal fun txStatusText(r: TxRecord): Pair<String, String> {
    // The fee is in the chain's own currency, as it was named when sent.
    val chain = Chain(id = r.chainId, name = r.chainName, symbol = r.chainSymbol, decimals = r.chainDecimals, rpcUrls = emptyList())
    val block = r.block?.let { "block ${"%,d".format(Locale.ROOT, it)}" }
    val fee = r.feePaid?.let { feeText(it, chain) }
    return when (r.status) {
        TxRecord.Status.PENDING -> "Pending" to "Not mined yet. It may still go through; the explorer shows where it stands."
        TxRecord.Status.CONFIRMED -> "Confirmed" to listOfNotNull(block?.let { "Mined in $it" } ?: "Mined", fee?.let { "fee $it" }).joinToString(" · ")
        TxRecord.Status.FAILED -> "Failed on chain" to "Mined" + (block?.let { " in $it" } ?: "") +
            ", but the transfer itself failed, so nothing arrived. The network fee" + (fee?.let { " ($it)" } ?: "") + " was still paid."
        TxRecord.Status.REPLACED -> "Replaced" to "Never mined: another transaction from this account used its nonce (${r.nonce}), " +
            "so this one can’t go through any more."
        TxRecord.Status.UNKNOWN -> "Outcome unknown" to "No receipt found, and this account’s nonce (${r.nonce}) has been used " +
            "since, by this transaction or another. It was sent too long ago for the network to still say which; " +
            "the explorer shows what happened."
    }
}

/** The list row's second line: status, chain and when. */
internal fun txSubtitle(r: TxRecord, format: DateFormat = txDateFormat()): String =
    "${txStatusText(r).first} · ${r.chainName} · ${format.format(Date(r.sentAt))}"

internal fun txDateFormat(): DateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)

/** [records] sent from [address], newest first. */
internal fun txRecordsFrom(records: List<TxRecord>, address: String?): List<TxRecord> =
    if (address == null) emptyList() else records.filter { it.from.equals(address, ignoreCase = true) }

/** The block explorer's page for a record, or null when its chain had no explorer. */
internal fun explorerTxUrl(r: TxRecord): String? = r.explorerUrl?.trimEnd('/')?.let { "$it/tx/${r.hash}" }

@Composable
private fun statusIcon(r: TxRecord): Pair<ImageVector, Color> {
    val light = MaterialTheme.colorScheme.isLight
    val green = if (light) Color(0xFF15803D) else Color(0xFF22C55E)
    val amber = if (light) Color(0xFFB45309) else Color(0xFFF59E0B)
    return when (r.status) {
        TxRecord.Status.PENDING -> Icons.Filled.Schedule to amber
        TxRecord.Status.CONFIRMED -> Icons.Filled.CheckCircle to green
        TxRecord.Status.FAILED -> Icons.Filled.ErrorOutline to MaterialTheme.colorScheme.error
        TxRecord.Status.REPLACED -> Icons.Filled.SwapHoriz to MaterialTheme.colorScheme.onSurfaceVariant
        TxRecord.Status.UNKNOWN -> Icons.AutoMirrored.Filled.HelpOutline to MaterialTheme.colorScheme.onSurfaceVariant
    }
}

/** One send in a list: amount, status, chain, date and recipient, each wrapped rather than cut. */
@Composable
private fun TxRow(r: TxRecord, onOpen: (TxRecord) -> Unit) {
    val (icon, tint) = statusIcon(r)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = { onOpen(r) })
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(txTitle(r), fontWeight = FontWeight.Medium)
            Text(txSubtitle(r), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "To ${shortAddress(r.to)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(icon, contentDescription = txStatusText(r).first, tint = tint, modifier = Modifier.size(20.dp))
    }
}

/** The wallet page's latest sends from the active account, with a way to all of them. */
@Composable
internal fun TxHistorySection(records: List<TxRecord>, onOpen: (TxRecord) -> Unit, onShowAll: () -> Unit) {
    SectionCard(title = TX_HISTORY_TITLE) {
        if (records.isEmpty()) {
            Text(
                "Nothing sent from this account yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            records.take(TX_HISTORY_PREVIEW).forEach { TxRow(it, onOpen) }
            if (records.size > TX_HISTORY_PREVIEW) {
                PageRow(
                    title = "All transactions",
                    subtitle = "${records.size} sent from this account",
                    style = PageRowStyle.Inset,
                    leadingIcon = Icons.Filled.History,
                    onClick = onShowAll,
                )
            }
        }
    }
}

/** Every send from the active account, newest first. */
@Composable
internal fun TxHistoryPage(accountName: String, records: List<TxRecord>, onOpen: (TxRecord) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    FullScreenScaffold(title = TX_HISTORY_TITLE, onDismiss = onBack) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item("list") {
                SectionCard(title = "Sent from $accountName") {
                    if (records.isEmpty()) {
                        Text("Nothing sent from this account yet.", style = MaterialTheme.typography.bodyMedium)
                    }
                    records.forEach { TxRow(it, onOpen) }
                }
            }
        }
    }
}

/** One send in full: where it stands, what, to whom, on which chain, the fee, and its hash. */
@Composable
internal fun TxDetailPage(r: TxRecord, onOpenUrl: (String) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val (title, text) = txStatusText(r)
    val (icon, tint) = statusIcon(r)
    val format = txDateFormat()
    FullScreenScaffold(title = "Transaction", onDismiss = onBack) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item("status") {
                SectionCard(title = txTitle(r)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                            SelectionContainer { Text(text, style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    TxField("Amount", "${SendAmounts.exact(r.amount, r.tokenDecimals)} ${r.tokenSymbol}", mono = true)
                    TxField("To", null, address = r.to)
                    TxField("From", null, address = r.from)
                    TxField("Network", r.chainName)
                    r.tokenAddress?.let { TxField("${r.tokenSymbol} contract", null, address = it) }
                    TxField("Sent", format.format(Date(r.sentAt)))
                    TxField("Nonce", r.nonce.toString(), mono = true)
                    TxField("Transaction", r.hash, mono = true)
                }
            }
            explorerTxUrl(r)?.let { url ->
                item("explorer") {
                    OutlinedButton(onClick = { onOpenUrl(url) }, modifier = Modifier.fillMaxWidth()) {
                        Text("View on ${android.net.Uri.parse(url).host ?: "the explorer"}")
                    }
                }
            }
        }
    }
}

/** A labelled value on its own line, never cut. */
@Composable
internal fun TxField(label: String, value: String?, mono: Boolean = false, address: String? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer {
            Column {
                value?.let { Text(it, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default) }
                address?.let { AddressText(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) }
            }
        }
    }
    HorizontalDivider()
}
