package baby.freedom.mobile.browser

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import baby.freedom.mobile.R
import baby.freedom.mobile.chains.Chain
import baby.freedom.mobile.l10n.Strings
import baby.freedom.mobile.wallet.FiatCurrency
import baby.freedom.mobile.wallet.FiatMath
import baby.freedom.mobile.wallet.FiatPrices
import baby.freedom.mobile.wallet.FiatQuotes
import baby.freedom.mobile.wallet.Token
import baby.freedom.mobile.wallet.TokenBalance
import java.math.BigDecimal
import java.math.BigInteger

/*
 * Approximate values in euros or dollars (#439), only with Wallet
 * settings → Show prices on: always a line of their own under the
 * crypto amount, marked "≈", and simply missing when there's no price.
 */

/**
 * The prices to show values with, or null: Show prices off (then nothing
 * is read at all), or nothing read yet. While on screen with the app in
 * the foreground, reads them again once they're older than
 * [FiatPrices.TTL_MS].
 */
@Composable
internal fun rememberFiatQuotes(): FiatQuotes? {
    val context = LocalContext.current
    val prices = remember(context) { FiatPrices.get(context) }
    val currency by prices.currency.collectAsState()
    val quotes by prices.quotes.collectAsState()
    LaunchedEffect(prices) { prices.load() }
    val lifecycle = currentLifecycle()
    LaunchedEffect(prices, currency, lifecycle) {
        if (currency == FiatCurrency.OFF) return@LaunchedEffect
        // A no-op until the last read is old enough: every screen asking shares one read.
        lifecycle.pollWhileStarted(FiatPrices.RETRY_MS) { prices.refresh() }
    }
    return quotes?.takeIf { currency != FiatCurrency.OFF && it.currency == currency }
}

/** "≈ €12.40" for [raw] of the token with [tokenKey]; null without a price for it. */
internal fun fiatText(quotes: FiatQuotes?, tokenKey: String, raw: BigInteger?, decimals: Int): String? {
    if (quotes == null || raw == null) return null
    val value = quotes.value(tokenKey, raw, decimals) ?: return null
    return FiatMath.format(value, quotes.currency, Strings.language())
}

/** [raw] wei of [chain]'s native currency: a fee, or a native send's total. */
internal fun fiatNative(quotes: FiatQuotes?, chain: Chain, raw: BigInteger?): String? =
    fiatText(quotes, "${chain.id}:native", raw, chain.decimals)

/**
 * A token send's total, [amount] of [token] plus [fee] wei of [chain]'s
 * currency, in one value — null unless both have a price.
 */
internal fun fiatTokenTotal(quotes: FiatQuotes?, token: Token, amount: BigInteger, chain: Chain, fee: BigInteger): String? {
    if (quotes == null) return null
    val a = quotes.value(token.key, amount, token.decimals) ?: return null
    val f = quotes.value("${chain.id}:native", fee, chain.decimals) ?: return null
    return FiatMath.format(a + f, quotes.currency, Strings.language())
}

/** The balance an Assets row shows: the reading, or the one before a failed read. */
internal fun shownRaw(balance: TokenBalance?): BigInteger? = when (balance) {
    is TokenBalance.Known -> balance.raw
    is TokenBalance.Failed -> balance.previous?.raw
    null -> null
}

/** An Assets row's value line: only for a balance above zero. */
internal fun assetFiat(quotes: FiatQuotes?, row: AssetRow): String? =
    shownRaw(row.balance)?.takeIf { it.signum() > 0 }?.let { fiatText(quotes, row.token.key, it, row.token.decimals) }

/**
 * The line under the home's large balance (#439): the value of every
 * asset held that has a price, added up. With one asset held, just its
 * value; with more, "in total" — or, when some have no price, for how
 * many of them it counts. Null with nothing held, or no price for any.
 */
internal fun headlineFiat(assets: WalletAssets, quotes: FiatQuotes?): String? {
    if (quotes == null) return null
    val held = assets.held
    if (held.isEmpty()) return null
    val values = held.mapNotNull { row -> shownRaw(row.balance)?.let { quotes.value(row.token.key, it, row.token.decimals) } }
    if (values.isEmpty()) return null
    val text = FiatMath.format(values.fold(BigDecimal.ZERO, BigDecimal::add), quotes.currency, Strings.language()) ?: return null
    return when {
        held.size == 1 -> text
        values.size == held.size -> Strings.get(R.string.fiat_headline_total, text)
        else -> Strings.plural(R.plurals.fiat_headline_partial, held.size, text, values.size, held.size)
    }
}

/** Show prices' choices, in the order the setting lists them. */
internal fun fiatChoiceLabel(currency: FiatCurrency): String = Strings.get(
    when (currency) {
        FiatCurrency.OFF -> R.string.fiat_setting_off
        FiatCurrency.EUR -> R.string.fiat_setting_eur
        FiatCurrency.USD -> R.string.fiat_setting_usd
    },
)
