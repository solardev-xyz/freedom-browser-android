package baby.freedom.mobile.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import baby.freedom.mobile.wallet.SendAmounts
import baby.freedom.mobile.wallet.TokenAmounts
import baby.freedom.mobile.wallet.WalletAccount
import baby.freedom.mobile.wallet.X402
import java.math.BigInteger

/** How long an allowance granted on the sheet lasts: the choices, shortest first. */
internal enum class X402Window(val label: String, val ms: Long) {
    HOUR("1 hour", 60 * 60 * 1000L),
    DAY("1 day", 24 * 60 * 60 * 1000L),
    WEEK("7 days", 7 * 24 * 60 * 60 * 1000L),
}

/**
 * Pay may be tapped with [active] the wallet's active account: only when
 * it's the account the sheet's balances and notes were worked out for
 * ([X402Ask.account]) — a switch meanwhile gets a fresh sheet (#218 R4-F1).
 */
internal fun X402Ask.paysFrom(active: WalletAccount?): Boolean =
    account != null && active != null && active.address.equals(account.address, ignoreCase = true)

/**
 * For a Ledger account: how little time [offer] leaves to confirm on the
 * device once it shows the payment, when that's short enough to run out
 * during a review ([X402.LEDGER_CONFIRM_SECONDS]); null otherwise (#218 R1-F1).
 */
internal fun ledgerHurry(offer: X402.Offer): String? {
    val s = X402.confirmSeconds(offer)
    return if (s >= X402.LEDGER_CONFIRM_SECONDS) null
    else "The site allows only $s s to confirm once the Ledger shows the payment; after that it isn't sent."
}

/** What the user has picked on an x402 sheet: an offer, and whether (and how much) to allow paying without asking. */
internal class X402SheetState(val ask: X402Ask) {
    var selected by mutableIntStateOf(ask.options.indexOfFirst { it.fundable }.coerceAtLeast(0))
    var auto by mutableStateOf(false)
    var capText by mutableStateOf(defaultCap(ask.options.getOrNull(selected)))
    var window by mutableStateOf(X402Window.DAY)

    val option: X402Option? get() = ask.options.getOrNull(selected)

    /** The paying account's key is on a Ledger (#142): no allowance is offered. */
    val ledger: Boolean get() = ask.account?.isLedger == true

    fun select(index: Int) {
        if (index == selected) return
        selected = index
        capText = defaultCap(option)
    }

    /** The allowance typed in, in base units, or null if it isn't a number of the token. */
    fun cap(): BigInteger? = option?.let { SendAmounts.parse(capText, it.decimals) }

    /** Why the allowance can't be granted as typed, or null if it can (or none is asked for). */
    fun capProblem(): String? {
        if (!auto || ledger) return null
        val o = option ?: return null
        val cap = cap() ?: return "Enter an amount of ${o.symbol}"
        if (cap < o.offer.amount) return "At least this payment: ${SendAmounts.exact(o.offer.amount, o.decimals)} ${o.symbol}"
        return null
    }

    /** The answer the Pay button gives now, or null when it can't be given. */
    fun choice(): X402Choice? {
        val o = option ?: return null
        if (!o.fundable || capProblem() != null) return null
        // A Ledger account never pays without asking: the Ledger confirms every payment (#142).
        val grant = if (auto && !ledger) X402Grant(cap() ?: return null, window.ms) else null
        return X402Choice(selected, grant)
    }

    private companion object {
        /** Ten of this payment, as a starting point for the allowance. */
        fun defaultCap(o: X402Option?): String =
            o?.let { SendAmounts.exact(it.offer.amount * BigInteger.TEN, it.decimals) } ?: ""
    }
}

/**
 * The body of the x402 payment sheet (#140): the page, what it costs
 * (exactly, in the token, on the network), whom it pays and from which
 * account, the balance, and the choice to let the site take further
 * payments without asking up to a total, for a while.
 */
@Composable
internal fun X402PaymentBody(
    ask: X402Ask,
    state: X402SheetState,
    account: WalletAccount?,
    noWallet: Boolean,
    locked: Boolean,
    onSetUp: () -> Unit,
) {
    Row0("Page", sheetText(ask.url, 2048).first, mono = true)
    ask.description?.let { Row0("The site says", it) }
    if (noWallet || account == null) {
        Spacer(Modifier.height(8.dp))
        Text("There's no wallet on this device yet. Set one up to pay — or reject, and keep browsing without paying.")
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onSetUp, modifier = Modifier.fillMaxWidth().testTag("x402-setup")) { Text("Set up a wallet") }
        return
    }
    if (ask.options.isEmpty()) {
        Spacer(Modifier.height(8.dp))
        Note("This wallet can't make any of the payments the site offers:", warn = true)
        ask.unusable.forEach { Note("• $it", warn = true) }
        return
    }
    if (ask.options.size > 1) {
        Label("Pay with")
        ask.options.forEachIndexed { i, o ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = i == state.selected, role = Role.RadioButton, onClick = { state.select(i) })
                    .padding(vertical = 2.dp)
                    .testTag("x402-option-$i"),
            ) {
                RadioButton(selected = i == state.selected, onClick = null)
                Column(Modifier.padding(start = 8.dp)) {
                    Text("${SendAmounts.exact(o.offer.amount, o.decimals)} ${o.symbol} on ${o.chain.name}")
                    if (!o.fundable) Note("Not enough ${o.symbol}")
                }
            }
        }
    }
    val o = state.option ?: return
    Row0(
        "Amount",
        "${SendAmounts.exact(o.offer.amount, o.decimals)} ${o.symbol}",
        mono = true,
        detail = if (o.listed) null else "${o.symbol} isn't in the wallet's token list: its symbol and decimals were read from the contract below, and verified on ${o.chain.name}.",
    )
    Row0("Network", "${o.chain.name} (chain ${o.chain.id})")
    AddressRow("Pay to", o.offer.payTo)
    AddressRow("Token contract", o.offer.asset)
    AccountRow(account, "From")
    Row0(
        "Balance",
        o.balance?.let { "${TokenAmounts.format(it, o.decimals)} ${o.symbol}" } ?: "Couldn't be read",
        detail = if (!o.fundable) "Not enough ${o.symbol} for this payment." else null,
    )
    if (ask.allowanceWaitingOnUnlock && locked) {
        Spacer(Modifier.height(8.dp))
        Note("Your allowance for this site covers this payment, but the wallet is locked, so nothing is paid without you.")
    }
    if (ask.unusable.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Note("The site's other offers can't be paid:")
        ask.unusable.forEach { Note("• $it") }
    }
    Spacer(Modifier.height(8.dp))
    if (state.ledger) {
        Note("A Ledger account doesn't pay sites automatically: you confirm each payment on the Ledger.")
        ledgerHurry(o.offer)?.let { Note(it, warn = true) }
    } else Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = state.auto, role = Role.Checkbox, onValueChange = { state.auto = it })
            .testTag("x402-auto"),
    ) {
        Checkbox(checked = state.auto, onCheckedChange = null)
        Text("Pay this site automatically", modifier = Modifier.padding(start = 8.dp))
    }
    if (state.auto && !state.ledger) {
        OutlinedTextField(
            value = state.capText,
            onValueChange = { state.capText = it },
            label = { Text("Up to, in all (this payment included)") },
            suffix = { Text(o.symbol) },
            singleLine = true,
            isError = state.capProblem() != null,
            supportingText = state.capProblem()?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth().testTag("x402-cap"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            X402Window.entries.forEach { w ->
                FilterChip(
                    selected = state.window == w,
                    onClick = { state.window = w },
                    label = { Text(w.label) },
                    modifier = Modifier.testTag("x402-window-${w.name.lowercase()}"),
                )
            }
        }
        Note(
            "For ${state.window.label}, this site's pages are paid for in ${o.symbol} on ${o.chain.name} without asking, " +
                "from ${accountLabel(account)} only, up to that total — only while the wallet is unlocked, never in a private tab. " +
                "With another account active, you're asked again. " +
                "Revoke it any time on the wallet page.",
        )
    }
    Spacer(Modifier.height(8.dp))
    Note(
        "Paying signs a transfer of exactly this amount to the address above, which the site collects. " +
            "It can't be undone once the site has it.",
    )
}
