package baby.freedom.mobile.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import baby.freedom.mobile.chains.Chain
import baby.freedom.mobile.data.X402Store
import baby.freedom.mobile.ui.isLight
import baby.freedom.mobile.wallet.SendAmounts
import java.text.DateFormat
import java.util.Date

internal const val X402_HISTORY_TITLE = "Site payments"

/** "0.01 USDC": a payment's amount, exactly. */
internal fun x402Amount(p: X402Store.Payment): String = "${SendAmounts.exact(p.amount, p.decimals)} ${p.symbol}"

/** A payment's status as a heading and the sentence under it. */
internal fun x402StatusText(p: X402Store.Payment): Pair<String, String> = when (p.status) {
    X402Store.Status.PENDING -> "Sent" to "Signed and sent with the page's request; the site hasn't answered yet."
    X402Store.Status.PAID -> "Paid" to "The site took the payment and showed the page."
    X402Store.Status.REFUSED -> "Refused" to "The site answered the paid request with an error" +
        (p.httpStatus?.let { " (HTTP $it)" } ?: "") + ". It may still collect the payment until the authorization runs out."
    X402Store.Status.UNCONFIRMED -> "Unconfirmed" to "The site's answer to the paid request wasn't seen. " +
        "It may have collected the payment."
}

/** A payment's second line: status, how it was approved, network and when. */
internal fun x402Subtitle(p: X402Store.Payment, chainName: String, format: DateFormat = x402DateFormat()): String =
    listOf(
        x402StatusText(p).first,
        if (p.auto) "automatic" else "approved",
        chainName,
        format.format(Date(p.at)),
    ).joinToString(" · ")

/** "0.2 of 1 USDC used · Base · from 0x6fac…b9c0 · until 3 Oct 2026, 14:00": an allowance's state, and the account it pays from. */
internal fun x402AllowanceLine(a: X402Store.Allowance, chainName: String, format: DateFormat = x402DateFormat()): String =
    "${SendAmounts.exact(a.spent, a.decimals)} of ${SendAmounts.exact(a.cap, a.decimals)} ${a.symbol} used · " +
        "$chainName · from ${shortAddress(a.account)} · until ${format.format(Date(a.expires))}"

private fun x402DateFormat(): DateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)

private fun chainName(chains: List<Chain>, id: Long) = chains.firstOrNull { it.id == id }?.name ?: "chain $id"

/**
 * The wallet page's x402 section (#140): each site allowed to pay
 * without asking — how much of its allowance it has used, on which
 * network, until when — with Revoke, and the way to the payment history.
 */
@Composable
internal fun X402Section(
    allowances: List<X402Store.Allowance>,
    payments: Int,
    chains: List<Chain>,
    onRevoke: (X402Store.Allowance) -> Unit,
    onOpenHistory: () -> Unit,
) {
    SectionCard(title = X402_HISTORY_TITLE) {
        if (allowances.isEmpty()) {
            Text(
                "No site pays automatically. A page that asks to be paid asks you here first.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        allowances.forEach { a ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("x402-allowance")) {
                Column(modifier = Modifier.weight(1f).padding(vertical = 4.dp)) {
                    Text(permissionOriginDisplay(a.origin), fontWeight = FontWeight.Medium)
                    Text(
                        x402AllowanceLine(a, chainName(chains, a.chainId)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { onRevoke(a) }, modifier = Modifier.testTag("x402-revoke")) { Text("Revoke") }
            }
        }
        PageRow(
            title = "Payment history",
            subtitle = if (payments == 0) "No payments yet" else "$payments payment${if (payments == 1) "" else "s"}",
            style = PageRowStyle.Inset,
            leadingIcon = Icons.Filled.ReceiptLong,
            onClick = onOpenHistory,
        )
    }
}

@Composable
private fun statusIcon(p: X402Store.Payment): Pair<ImageVector, Color> {
    val light = MaterialTheme.colorScheme.isLight
    val green = if (light) Color(0xFF15803D) else Color(0xFF22C55E)
    val amber = if (light) Color(0xFFB45309) else Color(0xFFF59E0B)
    return when (p.status) {
        X402Store.Status.PENDING -> Icons.Filled.Schedule to amber
        X402Store.Status.PAID -> Icons.Filled.CheckCircle to green
        X402Store.Status.REFUSED -> Icons.Filled.ErrorOutline to MaterialTheme.colorScheme.error
        X402Store.Status.UNCONFIRMED -> Icons.AutoMirrored.Filled.HelpOutline to amber
    }
}

/** Every x402 payment, newest first, each in full: nothing is cut, so it reads the same on any width. */
@Composable
internal fun X402HistoryPage(payments: List<X402Store.Payment>, chains: List<Chain>, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    FullScreenScaffold(title = "Payment history", onDismiss = onBack) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (payments.isEmpty()) {
                item("empty") {
                    SectionCard(title = X402_HISTORY_TITLE) {
                        Text("No payments yet.", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            items(payments, key = { it.id }) { p -> X402PaymentCard(p, chainName(chains, p.chainId)) }
        }
    }
}

@Composable
private fun X402PaymentCard(p: X402Store.Payment, chainName: String) {
    val (icon, tint) = statusIcon(p)
    val (title, text) = x402StatusText(p)
    SectionCard(title = permissionOriginDisplay(p.origin)) {
        Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth().testTag("x402-payment")) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("$title: ${x402Amount(p)}", fontWeight = FontWeight.Medium)
                Text(x402Subtitle(p, chainName), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(text, style = MaterialTheme.typography.bodySmall)
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Field("Page", p.url)
        Field("Paid to", p.payTo)
        Field("From", p.from)
        Field("Token", p.asset)
        Field("Authorization nonce", p.nonce)
    }
}

@Composable
private fun Field(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer { Text(value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
    }
}
