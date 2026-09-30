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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.annotation.PluralsRes
import baby.freedom.mobile.R
import baby.freedom.mobile.l10n.Strings
import baby.freedom.mobile.wallet.SendAmounts
import baby.freedom.mobile.wallet.TokenAmounts
import baby.freedom.mobile.wallet.WalletAccount
import baby.freedom.mobile.wallet.X402
import java.math.BigInteger

/** How long an allowance granted on the sheet lasts: the choices, shortest first. */
internal enum class X402Window(@PluralsRes private val unitRes: Int, private val count: Int, val ms: Long) {
    HOUR(R.plurals.signing_x402_window_hours, 1, 60 * 60 * 1000L),
    DAY(R.plurals.signing_x402_window_days, 1, 24 * 60 * 60 * 1000L),
    WEEK(R.plurals.signing_x402_window_days, 7, 7 * 24 * 60 * 60 * 1000L),
    ;

    val label: String get() = Strings.plural(unitRes, count, count)
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
    else Strings.get(R.string.signing_x402_ledger_hurry, s)
}

/**
 * What an allowance granted on the sheet lets the site do: pay whom, how
 * much at a time and in all, for which navigations, and when it stops —
 * the payee and the per-payment amount are this payment's (#237).
 */
internal fun allowanceNote(window: X402Window, o: X402Option, account: String): String =
    Strings.get(
        R.string.signing_x402_allowance_note,
        window.label,
        o.symbol,
        o.chain.name,
        SendAmounts.exact(o.offer.amount, o.decimals),
        account,
    )

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
        val cap = cap() ?: return ambiguousAmountNote(capText) ?: Strings.get(R.string.signing_x402_cap_enter_amount, o.symbol)
        if (cap < o.offer.amount) return Strings.get(R.string.signing_x402_cap_at_least, SendAmounts.exact(o.offer.amount, o.decimals), o.symbol)
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
    Row0(stringResource(R.string.signing_x402_page), sheetText(ask.url, 2048).first, mono = true)
    ask.description?.let { Row0(stringResource(R.string.signing_x402_site_says), it) }
    if (noWallet || account == null) {
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.signing_x402_no_wallet))
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onSetUp, modifier = Modifier.fillMaxWidth().testTag("x402-setup")) { Text(stringResource(R.string.signing_x402_set_up_wallet)) }
        return
    }
    if (ask.options.isEmpty()) {
        Spacer(Modifier.height(8.dp))
        Note(stringResource(R.string.signing_x402_no_usable_offers), warn = true)
        ask.unusable.forEach { Note(stringResource(R.string.signing_x402_bullet, it), warn = true) }
        return
    }
    if (ask.options.size > 1) {
        Label(stringResource(R.string.signing_x402_pay_with))
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
                    Text(stringResource(R.string.signing_x402_option, SendAmounts.exact(o.offer.amount, o.decimals), o.symbol, o.chain.name))
                    if (!o.fundable) Note(stringResource(R.string.signing_x402_not_enough, o.symbol))
                }
            }
        }
    }
    val o = state.option ?: return
    Row0(
        stringResource(R.string.signing_x402_amount),
        "${SendAmounts.exact(o.offer.amount, o.decimals)} ${o.symbol}",
        mono = true,
        detail = if (o.listed) null else stringResource(R.string.signing_x402_unlisted_token, o.symbol, o.chain.name),
    )
    Row0(stringResource(R.string.signing_x402_network), stringResource(R.string.signing_x402_network_value, o.chain.name, o.chain.id.toString()))
    AddressRow(stringResource(R.string.signing_x402_pay_to), o.offer.payTo)
    AddressRow(stringResource(R.string.signing_x402_token_contract), o.offer.asset)
    AccountRow(account, stringResource(R.string.signing_x402_from))
    Row0(
        stringResource(R.string.signing_x402_balance),
        o.balance?.let { "${TokenAmounts.format(it, o.decimals)} ${o.symbol}" } ?: stringResource(R.string.signing_x402_balance_unreadable),
        detail = if (!o.fundable) stringResource(R.string.signing_x402_not_enough_for_payment, o.symbol) else null,
    )
    if (ask.allowanceWaitingOnUnlock && locked) {
        Spacer(Modifier.height(8.dp))
        Note(stringResource(R.string.signing_x402_allowance_waiting_unlock))
    }
    if (ask.unusable.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Note(stringResource(R.string.signing_x402_other_offers_unusable))
        ask.unusable.forEach { Note(stringResource(R.string.signing_x402_bullet, it)) }
    }
    Spacer(Modifier.height(8.dp))
    if (state.ledger) {
        Note(stringResource(R.string.signing_x402_ledger_no_auto))
        ledgerHurry(o.offer)?.let { Note(it, warn = true) }
    } else Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = state.auto, role = Role.Checkbox, onValueChange = { state.auto = it })
            .testTag("x402-auto"),
    ) {
        Checkbox(checked = state.auto, onCheckedChange = null)
        Text(stringResource(R.string.signing_x402_pay_automatically), modifier = Modifier.padding(start = 8.dp))
    }
    if (state.auto && !state.ledger) {
        OutlinedTextField(
            value = state.capText,
            onValueChange = { state.capText = it },
            label = { Text(stringResource(R.string.signing_x402_cap_label)) },
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
        Note(allowanceNote(state.window, o, accountLabel(account)))
    }
    Spacer(Modifier.height(8.dp))
    Note(stringResource(R.string.signing_x402_paying_explained))
}
