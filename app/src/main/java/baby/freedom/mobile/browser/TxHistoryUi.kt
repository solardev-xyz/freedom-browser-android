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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import baby.freedom.mobile.R
import baby.freedom.mobile.chains.Chain
import baby.freedom.mobile.l10n.Strings
import baby.freedom.mobile.l10n.pluralText
import baby.freedom.mobile.ui.isLight
import baby.freedom.mobile.wallet.IncomingTransfer
import baby.freedom.mobile.wallet.SendAmounts
import baby.freedom.mobile.wallet.TxRecord
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** How many of the latest the wallet page lists before "All transactions". */
internal const val TX_HISTORY_PREVIEW = 5

/** "Sent 0.5 xDAI": what a record moved. */
internal fun txTitle(r: TxRecord): String =
    Strings.get(R.string.wallet_history_title_sent, SendAmounts.exact(r.amount, r.tokenDecimals), r.tokenSymbol)

/** A record's status as a heading and the sentence under it. */
internal fun txStatusText(r: TxRecord): Pair<String, String> {
    // The fee is in the chain's own currency, as it was named when sent.
    val chain = Chain(id = r.chainId, name = r.chainName, symbol = r.chainSymbol, decimals = r.chainDecimals, rpcUrls = emptyList())
    // The block number as explorers write it, whatever the phone's language: chain data, like the nonce.
    val block = r.block?.let { "%,d".format(Locale.ROOT, it) }
    val fee = r.feePaid?.let { feeText(it, chain) }
    return when (r.status) {
        TxRecord.Status.PENDING ->
            Strings.get(R.string.wallet_history_status_pending) to Strings.get(R.string.wallet_history_status_pending_text)
        TxRecord.Status.CONFIRMED -> Strings.get(R.string.wallet_history_status_confirmed) to when {
            block != null && fee != null -> Strings.get(R.string.wallet_history_confirmed_block_fee, block, fee)
            block != null -> Strings.get(R.string.wallet_history_confirmed_block, block)
            fee != null -> Strings.get(R.string.wallet_history_confirmed_fee, fee)
            else -> Strings.get(R.string.wallet_history_confirmed)
        }
        TxRecord.Status.FAILED -> Strings.get(R.string.wallet_history_status_failed) to when {
            block != null && fee != null -> Strings.get(R.string.wallet_history_failed_block_fee, block, fee)
            block != null -> Strings.get(R.string.wallet_history_failed_block, block)
            fee != null -> Strings.get(R.string.wallet_history_failed_fee, fee)
            else -> Strings.get(R.string.wallet_history_failed)
        }
        TxRecord.Status.REPLACED -> Strings.get(R.string.wallet_history_status_replaced) to
            Strings.get(R.string.wallet_history_status_replaced_text, r.nonce.toString())
        TxRecord.Status.UNKNOWN -> Strings.get(R.string.wallet_history_status_unknown) to
            Strings.get(R.string.wallet_history_status_unknown_text, r.nonce.toString())
    }
}

/** The list row's second line: status, chain and when. */
internal fun txSubtitle(r: TxRecord, format: DateFormat = txDateFormat()): String =
    Strings.get(R.string.wallet_history_subtitle, txStatusText(r).first, r.chainName, format.format(Date(r.sentAt)))

internal fun txDateFormat(): DateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)

/** [records] sent from [address], newest first. */
internal fun txRecordsFrom(records: List<TxRecord>, address: String?): List<TxRecord> =
    if (address == null) emptyList() else records.filter { it.from.equals(address, ignoreCase = true) }

/**
 * Where to see everything [address] did, sent from here or not (#422):
 * each of [chains]' explorers' page for it, by the explorer's host name,
 * in [chains]' order; a chain without an explorer has none.
 */
internal fun accountExplorerLinks(chains: List<Chain>, address: String): List<Pair<String, String>> =
    chains.mapNotNull { chain ->
        val url = explorerAddressUrl(chain, address) ?: return@mapNotNull null
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return@mapNotNull null
        host to url
    }.distinctBy { it.second }

/** The block explorer's page for a record, or null when its chain had no explorer. */
internal fun explorerTxUrl(r: TxRecord): String? = r.explorerUrl?.trimEnd('/')?.let { "$it/tx/${r.hash}" }

/** The block explorer's page for a received transfer's transaction, or null when its chain had no explorer. */
internal fun explorerTxUrl(t: IncomingTransfer): String? = t.explorerUrl?.trimEnd('/')?.let { "$it/tx/${t.hash}" }

/**
 * One line of the history (#441): a send made here, or a built-in token
 * received ([IncomingTransfers][baby.freedom.mobile.wallet.IncomingTransfers]).
 */
internal sealed interface HistoryItem {
    /** When: a send's time sent, a transfer's block time. */
    val at: Long

    /** Unique across both kinds, for opening one and as a list key. */
    val key: String

    data class Sent(val record: TxRecord) : HistoryItem {
        override val at get() = record.sentAt
        override val key get() = "sent:${record.hash.lowercase()}"
    }

    data class Received(val transfer: IncomingTransfer) : HistoryItem {
        override val at get() = transfer.at
        override val key get() = "received:${transfer.key}"
    }
}

/**
 * [sent] and [received] in one list, newest first. At the same moment (a
 * send to the account itself, mined in the second it was sent) the send
 * comes first; otherwise each keeps its own list's order.
 */
internal fun historyItems(sent: List<TxRecord>, received: List<IncomingTransfer>): List<HistoryItem> =
    (sent.map { HistoryItem.Sent(it) } + received.map { HistoryItem.Received(it) })
        .sortedWith(compareByDescending<HistoryItem> { it.at }.thenBy { if (it is HistoryItem.Sent) 0 else 1 })

/** [transfers] that arrived at [address]. */
internal fun transfersTo(transfers: List<IncomingTransfer>, address: String?): List<IncomingTransfer> =
    if (address == null) emptyList() else transfers.filter { it.account.equals(address, ignoreCase = true) }

/** "Received 12.5 xBZZ". */
internal fun receivedTitle(t: IncomingTransfer): String =
    Strings.get(R.string.wallet_history_title_received, SendAmounts.exact(t.amount, t.tokenDecimals), t.tokenSymbol)

/** A received transfer's second line: "Received", chain and when. */
internal fun receivedSubtitle(t: IncomingTransfer, format: DateFormat = txDateFormat()): String =
    Strings.get(R.string.wallet_history_subtitle, Strings.get(R.string.wallet_history_status_received), t.chainName, format.format(Date(t.at)))

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
                r.toName?.let { stringResource(R.string.wallet_history_row_to_named, it, shortAddress(r.to)) }
                    ?: stringResource(R.string.wallet_history_row_to, shortAddress(r.to)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(icon, contentDescription = txStatusText(r).first, tint = tint, modifier = Modifier.size(20.dp))
    }
}

/** One received transfer in a list: amount, "Received", chain, date and sender, each wrapped rather than cut. */
@Composable
private fun ReceivedRow(t: IncomingTransfer, onOpen: (IncomingTransfer) -> Unit) {
    val tint = receivedTint()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = { onOpen(t) })
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(receivedTitle(t), fontWeight = FontWeight.Medium)
            Text(receivedSubtitle(t), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // The whole sender, not its ends: a look-alike address sending dust
            // (address poisoning) matches a known one's first and last characters.
            val dim = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            Text(stringResource(R.string.wallet_history_field_from), style = MaterialTheme.typography.labelSmall, color = dim)
            AddressText(t.from, style = MaterialTheme.typography.labelSmall, color = dim)
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.AutoMirrored.Filled.CallReceived,
            contentDescription = stringResource(R.string.wallet_history_status_received),
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun receivedTint(): Color = if (MaterialTheme.colorScheme.isLight) Color(0xFF15803D) else Color(0xFF22C55E)

@Composable
private fun HistoryRow(item: HistoryItem, onOpen: (HistoryItem) -> Unit) = when (item) {
    is HistoryItem.Sent -> TxRow(item.record) { onOpen(item) }
    is HistoryItem.Received -> ReceivedRow(item.transfer) { onOpen(item) }
}

/**
 * Under a list (#441): received ETH or xDAI isn't in it, and where to see
 * it — each explorer's page for the account. While the account's first
 * scan is still reading back ([catchingUp]), says older tokens may still
 * come in.
 */
@Composable
private fun HistoryFootnote(catchingUp: Boolean, explorers: List<Pair<String, String>>, onOpenUrl: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (catchingUp) {
            Text(
                stringResource(R.string.wallet_history_catching_up),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            stringResource(R.string.wallet_history_native_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ExplorerLinks(explorers, onOpenUrl)
    }
}

@Composable
private fun ExplorerLinks(explorers: List<Pair<String, String>>, onOpenUrl: (String) -> Unit) {
    explorers.forEach { (host, url) ->
        TextButton(onClick = { onOpenUrl(url) }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.wallet_history_explorer_activity, host))
            Spacer(Modifier.width(4.dp))
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
        }
    }
}

/**
 * The wallet page's latest activity of the active account — sends made
 * here and built-in tokens received (#441) — with a way to all of it.
 * With none yet (#422): what the list shows and doesn't (ETH or xDAI
 * received isn't in it), Receive ([onReceive]), and the account's page on
 * each explorer ([explorers], [accountExplorerLinks]); under a list, the
 * same note and links ([HistoryFootnote]).
 */
@Composable
internal fun TxHistorySection(
    records: List<HistoryItem>,
    onOpen: (HistoryItem) -> Unit,
    onShowAll: () -> Unit,
    onReceive: (() -> Unit)? = null,
    explorers: List<Pair<String, String>> = emptyList(),
    onOpenUrl: (String) -> Unit = {},
    title: String = stringResource(R.string.wallet_history_title),
    preview: Int = TX_HISTORY_PREVIEW,
    // A row above the list (the wallet home's send in progress, W1).
    top: (@Composable () -> Unit)? = null,
    catchingUp: Boolean = false,
) {
    SectionCard(title = title) {
        top?.invoke()
        if (records.isEmpty()) {
            TxHistoryEmpty(onReceive, explorers, onOpenUrl, catchingUp)
        } else {
            records.take(preview).forEach { HistoryRow(it, onOpen) }
            if (records.size > preview) {
                PageRow(
                    title = stringResource(R.string.wallet_history_all),
                    subtitle = pluralText(R.plurals.wallet_history_all_count, records.size, records.size),
                    style = PageRowStyle.Inset,
                    leadingIcon = Icons.Filled.History,
                    onClick = onShowAll,
                )
            }
            HistoryFootnote(catchingUp, explorers, onOpenUrl)
        }
    }
}

/** Nothing yet (#422): say so, and offer Receive and the explorers' full activity. */
@Composable
private fun TxHistoryEmpty(
    onReceive: (() -> Unit)?,
    explorers: List<Pair<String, String>>,
    onOpenUrl: (String) -> Unit,
    catchingUp: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(if (catchingUp) R.string.wallet_history_empty_catching_up else R.string.wallet_history_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        onReceive?.let {
            Button(onClick = it, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Filled.QrCode, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.wallet_history_receive))
            }
        }
        ExplorerLinks(explorers, onOpenUrl)
    }
}

/** All of the active account's activity — sends made here and tokens received — newest first. */
@Composable
internal fun TxHistoryPage(
    accountName: String,
    records: List<HistoryItem>,
    onOpen: (HistoryItem) -> Unit,
    onBack: () -> Unit,
    explorers: List<Pair<String, String>> = emptyList(),
    onOpenUrl: (String) -> Unit = {},
    catchingUp: Boolean = false,
) {
    BackHandler(onBack = onBack)
    FullScreenScaffold(title = stringResource(R.string.wallet_history_title), onDismiss = onBack) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item("list") {
                SectionCard(title = accountName) {
                    if (records.isEmpty()) {
                        TxHistoryEmpty(onReceive = null, explorers = explorers, onOpenUrl = onOpenUrl, catchingUp = catchingUp)
                    } else {
                        records.forEach { HistoryRow(it, onOpen) }
                        HistoryFootnote(catchingUp, explorers, onOpenUrl)
                    }
                }
            }
        }
    }
}

/**
 * One send in full (#422): the amount and where it stands first, then to
 * whom, on which network and when; who sent it, the token contract, the
 * nonce and the hash under Details, each with Copy.
 */
@Composable
internal fun TxDetailPage(r: TxRecord, onOpenUrl: (String) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val (title, text) = txStatusText(r)
    val (icon, tint) = statusIcon(r)
    val format = txDateFormat()
    FullScreenScaffold(title = stringResource(R.string.wallet_history_detail_title), onDismiss = onBack) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item("status") {
                SectionCard(title = stringResource(R.string.wallet_history_field_amount)) {
                    Text(
                        txTitle(r),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.semantics { heading() },
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, color = tint)
                            SelectionContainer { Text(text, style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    CopyableAddressRow(
                        label = stringResource(R.string.wallet_history_field_to),
                        address = r.to,
                        name = r.toName,
                        explorerUrl = explorerAddressUrl(r.explorerUrl, r.to),
                        onOpenUrl = onOpenUrl,
                    )
                    TxField(stringResource(R.string.wallet_history_field_network), r.chainName)
                    TxField(stringResource(R.string.wallet_history_field_sent), format.format(Date(r.sentAt)))
                    DetailsExpander {
                        CopyableAddressRow(
                            label = stringResource(R.string.wallet_history_field_from),
                            address = r.from,
                            explorerUrl = explorerAddressUrl(r.explorerUrl, r.from),
                            onOpenUrl = onOpenUrl,
                        )
                        r.tokenAddress?.let {
                            CopyableAddressRow(
                                label = stringResource(R.string.wallet_history_field_contract, r.tokenSymbol),
                                address = it,
                                explorerUrl = explorerAddressUrl(r.explorerUrl, it),
                                onOpenUrl = onOpenUrl,
                            )
                        }
                        CopyableAddressRow(stringResource(R.string.wallet_history_field_nonce), r.nonce.toString())
                        CopyableAddressRow(
                            label = stringResource(R.string.wallet_history_field_transaction),
                            address = r.hash,
                            explorerUrl = explorerTxUrl(r),
                            onOpenUrl = onOpenUrl,
                        )
                    }
                }
            }
            explorerTxUrl(r)?.let { url ->
                item("explorer") {
                    OutlinedButton(onClick = { onOpenUrl(url) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(
                            android.net.Uri.parse(url).host?.let { stringResource(R.string.wallet_history_view_on, it) }
                                ?: stringResource(R.string.wallet_history_view_on_explorer),
                        )
                    }
                }
            }
        }
    }
}

/**
 * One received transfer in full (#441): the amount and that it arrived
 * first, then from whom, on which network and when; the receiving
 * account, the token contract and the transaction under Details.
 */
@Composable
internal fun ReceivedDetailPage(t: IncomingTransfer, onOpenUrl: (String) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val tint = receivedTint()
    val format = txDateFormat()
    // The block number as explorers write it, whatever the phone's language.
    val block = "%,d".format(Locale.ROOT, t.block)
    FullScreenScaffold(title = stringResource(R.string.wallet_history_detail_title), onDismiss = onBack) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item("status") {
                SectionCard(title = stringResource(R.string.wallet_history_field_amount)) {
                    Text(
                        receivedTitle(t),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.semantics { heading() },
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(Icons.AutoMirrored.Filled.CallReceived, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.wallet_history_status_received),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                                color = tint,
                            )
                            SelectionContainer {
                                Text(stringResource(R.string.wallet_history_received_text, block), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    CopyableAddressRow(
                        label = stringResource(R.string.wallet_history_field_from),
                        address = t.from,
                        explorerUrl = explorerAddressUrl(t.explorerUrl, t.from),
                        onOpenUrl = onOpenUrl,
                        detail = stringResource(R.string.wallet_history_received_from_caution),
                    )
                    TxField(stringResource(R.string.wallet_history_field_network), t.chainName)
                    TxField(stringResource(R.string.wallet_history_field_received), format.format(Date(t.at)))
                    DetailsExpander {
                        CopyableAddressRow(
                            label = stringResource(R.string.wallet_history_field_to),
                            address = t.account,
                            explorerUrl = explorerAddressUrl(t.explorerUrl, t.account),
                            onOpenUrl = onOpenUrl,
                        )
                        CopyableAddressRow(
                            label = stringResource(R.string.wallet_history_field_contract, t.tokenSymbol),
                            address = t.tokenAddress,
                            explorerUrl = explorerAddressUrl(t.explorerUrl, t.tokenAddress),
                            onOpenUrl = onOpenUrl,
                        )
                        CopyableAddressRow(
                            label = stringResource(R.string.wallet_history_field_transaction),
                            address = t.hash,
                            explorerUrl = explorerTxUrl(t),
                            onOpenUrl = onOpenUrl,
                        )
                    }
                }
            }
            explorerTxUrl(t)?.let { url ->
                item("explorer") {
                    OutlinedButton(onClick = { onOpenUrl(url) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(
                            android.net.Uri.parse(url).host?.let { stringResource(R.string.wallet_history_view_on, it) }
                                ?: stringResource(R.string.wallet_history_view_on_explorer),
                        )
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
