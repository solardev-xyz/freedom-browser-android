package baby.freedom.mobile.browser

import baby.freedom.mobile.chains.BuiltInChains
import baby.freedom.mobile.chains.Chain
import baby.freedom.mobile.chains.rpc.ChainSource
import baby.freedom.mobile.chains.rpc.ChainTrust
import baby.freedom.mobile.wallet.WalletAccount
import baby.freedom.mobile.wallet.X402
import baby.freedom.mobile.wallet.ledger.LedgerKey
import java.math.BigInteger
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The x402 sheet's answer (#140) and the token facts it shows. */
class X402SheetTest {
    private val usdc = "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913"

    private fun offers(vararg amounts: String): List<X402.Offer> {
        val accepts = JSONArray()
        amounts.forEach { a ->
            accepts.put(
                JSONObject().put("scheme", "exact").put("network", "eip155:8453").put("amount", a).put("asset", usdc)
                    .put("payTo", "0x209693Bc6afc0C5328bA36FaF03C514EF312287C").put("maxTimeoutSeconds", 60)
                    .put("extra", JSONObject().put("name", "USD Coin").put("version", "2")),
            )
        }
        val json = JSONObject().put("x402Version", 2).put("accepts", accepts)
        return X402.parseRequired(Base64.getEncoder().encodeToString(json.toString().toByteArray()))!!.offers
    }

    private val account1 = WalletAccount(0, "Account 1", "0x1111111111111111111111111111111111111111")
    private val account2 = WalletAccount(1, "Account 2", "0x2222222222222222222222222222222222222222")

    private fun ask(balance: Long?, vararg amounts: String, account: WalletAccount? = account1) = X402Ask(
        url = "https://api.example/paid",
        description = null,
        account = account,
        options = offers(*amounts).map { X402Option(it, BuiltInChains.BASE, "USDC", 6, listed = true, balance = balance?.let(BigInteger::valueOf)) },
        unusable = emptyList(),
        allowanceWaitingOnUnlock = false,
    )

    @Test
    fun `Pay only from the account the sheet's figures are for`() {
        val a = ask(balance = 100_000, "10000")
        assertTrue(a.paysFrom(account1))
        assertTrue(a.paysFrom(account1.copy(address = account1.address.uppercase().replace("0X", "0x"))))
        assertFalse(a.paysFrom(account2))
        assertFalse(a.paysFrom(null))
        assertFalse(ask(balance = null, "10000", account = null).paysFrom(account1))
    }

    @Test
    fun `the first offer the balance covers is picked, and Pay answers it`() {
        val s = X402SheetState(ask(balance = 5_000, "10000", "5000"))
        assertEquals(1, s.selected)
        assertEquals(X402Choice(1, null), s.choice())
        s.select(0)
        assertNull("not enough for this one", s.choice())
    }

    @Test
    fun `an allowance pays only an offer the balance isn't known to be short of (R2-M1)`() {
        val short = ask(balance = 5_000, "10000", "5000").options
        // Both covered: the first is short, so the second is paid.
        assertEquals(1, X402Payments.autoPayOption(short) { true }?.offer?.index)
        // Only the short one covered: none is paid silently, the sheet explains.
        assertNull(X402Payments.autoPayOption(short) { it.index == 0 })
        // An unknown balance doesn't stop it.
        assertEquals(0, X402Payments.autoPayOption(ask(balance = null, "10000").options) { true }?.offer?.index)
        assertNull(X402Payments.autoPayOption(ask(balance = null, "10000").options) { false })
    }

    @Test
    fun `an unknown balance doesn't stop the payment`() {
        assertEquals(X402Choice(0, null), X402SheetState(ask(balance = null, "10000")).choice())
    }

    @Test
    fun `an account switch never pays from an allowance, the sheet goes up instead (R5-M2)`() {
        val options = ask(balance = 100_000, "10000").options
        val payer = account2.address
        assertEquals(0, X402Payments.silentPayOption(true, switched = false, payer, ledger = false, options = options) { true }?.offer?.index)
        assertNull("after a switch", X402Payments.silentPayOption(true, switched = true, payer, ledger = false, options = options) { true })
        assertNull("not the navigation's own", X402Payments.silentPayOption(false, switched = false, payer, ledger = false, options = options) { true })
        assertNull("no account", X402Payments.silentPayOption(true, switched = false, null, ledger = false, options = options) { true })
        assertNull("no allowance", X402Payments.silentPayOption(true, switched = false, payer, ledger = false, options = options) { false })
    }

    @Test
    fun `a Ledger account pays only on the sheet, never from an allowance, and grants none (#142)`() {
        val ledger = WalletAccount(
            2, "Ledger 1", "0x3333333333333333333333333333333333333333",
            LedgerKey("44'/60'/0'/0/0", "AA:BB:CC:DD:EE:FF", "Ledger Nano X"),
        )
        val a = ask(balance = 1_000_000, "10000", account = ledger)
        assertNull(X402Payments.silentPayOption(true, switched = false, ledger.address, ledger = true, options = a.options) { true })
        val s = X402SheetState(a)
        assertTrue(s.ledger)
        s.auto = true
        s.capText = "nonsense"
        assertNull(s.capProblem())
        assertEquals(X402Choice(0, null), s.choice())
    }

    @Test
    fun `an allowance starts at ten payments, must cover this one, and goes with the answer`() {
        val s = X402SheetState(ask(balance = 1_000_000, "10000"))
        s.auto = true
        assertEquals("0.1", s.capText)
        assertEquals(X402Choice(0, X402Grant(BigInteger.valueOf(100_000), X402Window.DAY.ms)), s.choice())
        s.window = X402Window.HOUR
        s.capText = "0.009"
        assertEquals("At least this payment: 0.01 USDC", s.capProblem())
        assertNull(s.choice())
        s.capText = "abc"
        assertEquals("Enter an amount of USDC", s.capProblem())
        s.capText = "0.01"
        assertEquals(X402Choice(0, X402Grant(BigInteger.valueOf(10_000), X402Window.HOUR.ms)), s.choice())
        s.auto = false
        assertEquals(X402Choice(0, null), s.choice())
    }

    @Test
    fun `a token's symbol is read as an ABI string or bytes32, and only shown if it's plain`() {
        fun word(n: Int) = n.toString(16).padStart(64, '0')
        fun padded(s: String) = s.toByteArray().joinToString("") { "%02x".format(it) }.padEnd(64, '0')
        assertEquals("USDC", X402Payments.abiSymbol("0x" + word(32) + word(4) + padded("USDC")))
        assertEquals("MKR", X402Payments.abiSymbol("0x" + padded("MKR")))
        assertEquals("USDC.e", X402Payments.abiSymbol("0x" + word(32) + word(6) + padded("USDC.e")))
        assertNull(X402Payments.abiSymbol("0x" + word(32) + word(9) + padded("USD‮COIN")))
        assertNull(X402Payments.abiSymbol("0x" + word(32) + word(40) + padded("X")))
        assertNull(X402Payments.abiSymbol("0x" + word(64) + word(4) + padded("USDC")))
        assertNull(X402Payments.abiSymbol("0x"))
        assertNull(X402Payments.abiSymbol("0xzz"))
    }

    @Test
    fun `the wallet's own tokens and x402's USDCs are known without reading the chain`() {
        assertEquals("USDC" to 6, X402Payments.knownToken(8453, usdc.lowercase()))
        assertEquals("USDC" to 6, X402Payments.knownToken(1, "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"))
        assertNull(X402Payments.knownToken(1, usdc))
    }

    private fun trust(level: ChainTrust.Level, dissented: List<String> = emptyList()) = ChainTrust(
        level, if (level == ChainTrust.Level.VERIFIED) ChainSource.QUORUM else ChainSource.DIRECT,
        listOf("a.example", "b.example"), dissented, listOf("a.example", "b.example"), 3, 2, null,
    )

    @Test
    fun `an unlisted token's decimals are shown only on verified answers on a built-in chain (R1-F1)`() {
        val verified = trust(ChainTrust.Level.VERIFIED)
        assertTrue(X402Payments.tokenReadTrusted(BuiltInChains.BASE, listOf(verified, verified)))
        // A quorum that agreed over one dissenter is still verified.
        assertTrue(X402Payments.tokenReadTrusted(BuiltInChains.BASE, listOf(verified, trust(ChainTrust.Level.VERIFIED, listOf("c.example")))))
        // One RPC's word — a public one, or the user's own — isn't enough for the amount.
        assertFalse(X402Payments.tokenReadTrusted(BuiltInChains.BASE, listOf(verified, trust(ChainTrust.Level.UNVERIFIED))))
        assertFalse(X402Payments.tokenReadTrusted(BuiltInChains.BASE, listOf(trust(ChainTrust.Level.USER_CONFIGURED), verified)))
        assertFalse(X402Payments.tokenReadTrusted(BuiltInChains.BASE, emptyList()))
        // A chain a site added (wallet_addEthereumChain) with its own RPCs: even a quorum of them is the site's word.
        val siteChain = Chain(id = 42161, name = "Arbitrum One", symbol = "ETH", rpcUrls = listOf("https://rpc.site.example", "https://rpc2.site.example"))
        assertFalse(X402Payments.tokenReadTrusted(siteChain, listOf(verified, verified)))
    }
}
