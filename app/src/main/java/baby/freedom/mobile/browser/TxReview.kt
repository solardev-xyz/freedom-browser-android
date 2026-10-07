package baby.freedom.mobile.browser

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import baby.freedom.mobile.R
import baby.freedom.mobile.chains.Chain
import baby.freedom.mobile.l10n.Strings

/*
 * The shared parts of a transaction review (#422, audit W12/W49): what a
 * user must read before approving — one sentence saying what moves where,
 * the fee and the total — on top, and everything else (nonce, gas,
 * contracts, raw data, footnotes) one tap away under Details. Built for
 * Send first; the dApp approval, Safe and Ledger sheets reuse it, so the
 * API stays three composables:
 *
 *  • [TxReviewSummary] — the hero line, then Network fee and Total.
 *  • [DetailsExpander] — a collapsed "Details" section for the rest.
 *  • [CopyableAddressRow] — a labelled address (or hash) with a 48 dp
 *    Copy button and, when the chain has one, an explorer button.
 *
 * Every value is shown in full in the text itself (never only in a
 * tooltip or cut with an ellipsis): a review is where the user checks it.
 */

/**
 * "Send 0.001 ETH to vitalik.eth (0xd8dA…6045) on Ethereum": the hero
 * line of a send's review. [amount] is already formatted (every digit
 * kept); [toName] is the name the user typed for [to], if any — the
 * address is always there too, shortened, since it is what's signed and
 * the full address is in the To row below.
 */
internal fun sendHeadline(amount: String, symbol: String, to: String, toName: String?, chainName: String): String {
    val recipient = toName?.let { Strings.get(R.string.tx_review_recipient_named, it, shortAddress(to)) } ?: shortAddress(to)
    return Strings.get(R.string.tx_review_send_headline, amount, symbol, recipient, chainName)
}

/** The explorer's page for [address] on [chain], or null when the chain has no explorer. */
internal fun explorerAddressUrl(chain: Chain, address: String): String? = explorerAddressUrl(chain.explorerUrl, address)

/** The explorer at [explorerUrl]'s page for [address]; null without an explorer. */
internal fun explorerAddressUrl(explorerUrl: String?, address: String): String? =
    explorerUrl?.trimEnd('/')?.takeIf { it.isNotEmpty() }?.let { "$it/address/$address" }

/**
 * The top of a review: [headline] (one sentence, wrapped, never cut),
 * then [fee] as "Network fee" and, when given, [total] as "Total" —
 * each with its approximate value under it when given ([feeFiat], [totalFiat]).
 * [extra] goes under them (a self-send note, a warning) and stays above
 * any [DetailsExpander] the caller puts after this.
 */
@Composable
internal fun TxReviewSummary(
    headline: String,
    fee: String?,
    total: String? = null,
    modifier: Modifier = Modifier,
    /** [fee] and [total] in euros or dollars (#439): a second, approximate line under each, with Show prices on. */
    feeFiat: String? = null,
    totalFiat: String? = null,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            headline,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.padding(top = 4.dp))
        fee?.let { SummaryLine(stringResource(R.string.send_label_network_fee), it, secondary = feeFiat) }
        total?.let { SummaryLine(stringResource(R.string.send_label_total), it, strong = true, secondary = totalFiat) }
        extra()
    }
}

/** "Network fee   up to 0.0001 ETH": label first, the value under it when both don't fit one line. */
@Composable
private fun SummaryLine(label: String, value: String, strong: Boolean = false, secondary: String? = null) {
    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 4.dp), verticalArrangement = Arrangement.Center) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Normal,
        )
        secondary?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * A "Details" section, collapsed until tapped ([initiallyExpanded] to
 * start open). The header is one 48 dp toggle TalkBack reads as
 * "Details, expanded/collapsed"; [content] is composed only while open.
 */
@Composable
internal fun DetailsExpander(
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.tx_review_details),
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    val state = stringResource(if (expanded) R.string.tx_review_details_expanded else R.string.tx_review_details_collapsed)
    Column(modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable(role = Role.Button) { expanded = !expanded }
                .semantics { stateDescription = state }
                .heightIn(min = 48.dp)
                .padding(vertical = 8.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
        }
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.fillMaxWidth()) { content() }
        }
    }
}

/**
 * [label], then [name] (when the address has one: an ENS name, an
 * account's name), then [address] in full — it's a review: the whole
 * address is what gets checked, so it is never shortened here — and
 * [detail] muted under it. Trailing: a 48 dp Copy button and, when
 * [explorerUrl] is given and [onOpenUrl] can open it, an explorer button.
 *
 * [address] can be any hex value worth copying (a transaction hash, a
 * nonce): it's laid out by [AddressText], one line or two even halves.
 */
@Composable
internal fun CopyableAddressRow(
    label: String,
    address: String,
    name: String? = null,
    explorerUrl: String? = null,
    onOpenUrl: ((String) -> Unit)? = null,
    detail: String? = null,
    below: @Composable ColumnScope.() -> Unit = {},
) {
    val context = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 4.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SelectionContainer {
                Column {
                    name?.let { Text(it, fontWeight = FontWeight.Medium) }
                    AddressText(address, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            detail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            below()
        }
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = { copyToClipboard(context, address) }) {
            Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.tx_review_copy, label))
        }
        if (explorerUrl != null && onOpenUrl != null) {
            IconButton(onClick = { onOpenUrl(explorerUrl) }) {
                Icon(
                    Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = stringResource(R.string.tx_review_open_explorer, label),
                )
            }
        }
    }
    HorizontalDivider()
}
