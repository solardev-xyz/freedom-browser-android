package baby.freedom.mobile.browser

import baby.freedom.mobile.chains.BuiltInChains
import baby.freedom.mobile.chains.rpc.ChainSource
import baby.freedom.mobile.chains.rpc.ChainTrust
import baby.freedom.mobile.wallet.FiatCurrency
import baby.freedom.mobile.wallet.FiatQuotes
import baby.freedom.mobile.wallet.TokenBalance
import baby.freedom.mobile.wallet.TokenRegistry
import java.math.BigDecimal
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Where the wallet shows approximate values (#439): the home's line, Assets rows, fees and totals. */
class FiatValuesTest {
    private val chains = listOf(BuiltInChains.ETHEREUM, BuiltInChains.GNOSIS)
    private val all = chains.flatMap { TokenRegistry.tokens(it) }
    private fun token(symbol: String) = all.first { it.symbol == symbol }
    private val verified = ChainTrust(ChainTrust.Level.VERIFIED, ChainSource.QUORUM, listOf("a", "b"), emptyList(), listOf("a", "b", "c"), 3, 2, null)
    private fun known(raw: String) = TokenBalance.Known(BigInteger(raw), verified)
    private val zeros = all.associate { it.key to known("0") }

    private val quotes = FiatQuotes(
        FiatCurrency.EUR,
        mapOf(
            token("ETH").key to BigDecimal("2000"),
            token("xDAI").key to BigDecimal("0.8"),
            token("USDC").key to BigDecimal("0.9"),
        ),
    )

    @Test
    fun `no prices, no line`() {
        val assets = walletAssets(chains, zeros + (token("ETH").key to known("1000000000000000000")))
        assertNull(headlineFiat(assets, null))
        assertNull(assetFiat(null, assets.shown.first()))
    }

    @Test
    fun `one asset held - its value`() {
        val assets = walletAssets(chains, zeros + (token("ETH").key to known("6200000000000000")))
        assertEquals("≈ €12.40", headlineFiat(assets, quotes))
        assertEquals("≈ €12.40", assetFiat(quotes, assets.shown.first()))
    }

    @Test
    fun `several held - added up, and said when some have no price`() {
        val priced = walletAssets(
            chains,
            zeros + mapOf(token("ETH").key to known("1000000000000000000"), token("xDAI").key to known("10000000000000000000")),
        )
        assertEquals("≈ €2,008.00 in total", headlineFiat(priced, quotes))
        val partly = walletAssets(
            chains,
            zeros + mapOf(token("ETH").key to known("1000000000000000000"), token("EURe").key to known("5000000000000000000")),
        )
        assertEquals("≈ €2,000.00 for 1 of 2 assets", headlineFiat(partly, quotes))
        val none = walletAssets(chains, zeros + (token("EURe").key to known("5000000000000000000")))
        assertNull(headlineFiat(none, quotes))
    }

    @Test
    fun `a failed read keeps the value of the balance still shown`() {
        val failed = TokenBalance.Failed("down", known("1000000000000000000"))
        val assets = walletAssets(chains, zeros + (token("ETH").key to failed))
        assertEquals("≈ €2,000.00", assetFiat(quotes, assets.shown.first()))
    }

    @Test
    fun `zero rows show no value`() {
        val assets = walletAssets(chains, zeros + (token("ETH").key to known("1")))
        assertNull(assetFiat(quotes, assets.noBalance.first()))
    }

    @Test
    fun `fees in the chain's currency, a token total adds both or shows nothing`() {
        val gnosis = BuiltInChains.GNOSIS
        val fee = BigInteger("21000000000000") // 0.000021 xDAI
        assertEquals("< €0.01", fiatNative(quotes, gnosis, fee))
        assertEquals("≈ €0.80", fiatNative(quotes, gnosis, BigInteger.TEN.pow(18)))
        assertNull(fiatNative(quotes, BuiltInChains.BASE, BigInteger.TEN.pow(18)))
        val eth = BuiltInChains.ETHEREUM
        assertEquals(
            "≈ €11.00",
            fiatTokenTotal(quotes, token("USDC"), BigInteger("10000000"), eth, BigInteger("1000000000000000")),
        )
        assertNull(fiatTokenTotal(quotes, token("EURe"), BigInteger.ONE, gnosis, fee))
        assertNull(fiatTokenTotal(null, token("USDC"), BigInteger.ONE, eth, fee))
    }
}
