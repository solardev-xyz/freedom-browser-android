package baby.freedom.mobile.browser

import baby.freedom.mobile.chains.BuiltInChains
import baby.freedom.mobile.chains.rpc.ChainSource
import baby.freedom.mobile.chains.rpc.ChainTrust
import baby.freedom.mobile.wallet.TokenBalance
import baby.freedom.mobile.wallet.TokenRegistry
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wallet home's Assets list and large balance (W1, W7): what's held
 * first, whatever can't be called zero after it, verified zeros
 * collapsed, the empty state only when every token is a sure zero, and
 * the headline taken from the first asset held.
 */
class WalletAssetsTest {
    private val chains = listOf(BuiltInChains.ETHEREUM, BuiltInChains.GNOSIS)
    private val eth = TokenRegistry.tokens(BuiltInChains.ETHEREUM)
    private val gno = TokenRegistry.tokens(BuiltInChains.GNOSIS)
    private val all = eth + gno

    private fun trust(level: ChainTrust.Level) =
        ChainTrust(level, ChainSource.QUORUM, listOf("a", "b"), emptyList(), listOf("a", "b", "c"), 3, 2, null)

    private val verified = trust(ChainTrust.Level.VERIFIED)
    private val unverified = trust(ChainTrust.Level.UNVERIFIED)
    private val own = trust(ChainTrust.Level.USER_CONFIGURED)

    private fun zero(t: ChainTrust = verified) = TokenBalance.Known(BigInteger.ZERO, t)
    private fun some(raw: String, t: ChainTrust = verified) = TokenBalance.Known(BigInteger(raw), t)

    private fun allZero() = all.associate { it.key to zero() }

    @Test
    fun `held assets come first in the wallet's order and verified zeros collapse`() {
        val xbzz = gno.first { it.symbol == "xBZZ" }
        val usdc = eth.first { it.symbol == "USDC" }
        val balances = allZero() + mapOf(
            xbzz.key to some("50000000000000000"),
            usdc.key to some("2500000"),
        )
        val assets = walletAssets(chains, balances)
        // Ethereum before Gnosis, as the wallet orders them: USDC, then xBZZ.
        assertEquals(listOf("USDC", "xBZZ"), assets.shown.map { it.token.symbol })
        assertEquals(all.size - 2, assets.noBalance.size)
        assertTrue(assets.noBalance.all { it.kind == AssetKind.NO_BALANCE })
        // The collapsed ones keep the wallet's order too.
        assertEquals(all.map { it.symbol }.filter { it != "USDC" && it != "xBZZ" }, assets.noBalance.map { it.token.symbol })
        assertFalse(assets.noFunds)
        assertFalse(assets.reading)
    }

    @Test
    fun `what can't be called zero stays in view, after what's held`() {
        val dai = eth.first { it.symbol == "DAI" }
        val bzz = eth.first { it.symbol == "BZZ" }
        val eure = gno.first { it.symbol == "EURe" }
        val xdai = gno.first()
        val balances = allZero() + mapOf(
            // A failed read, nothing before: unsure.
            dai.key to TokenBalance.Failed("no RPC answered", null),
            // A zero on one RPC's word: unsure.
            bzz.key to zero(unverified),
            // A failed read of something held before: still held (shown as not updated).
            eure.key to TokenBalance.Failed("no RPC answered", some("1000000000000000000")),
            // Read through the user's own RPC: a zero like any other.
            xdai.key to zero(own),
        )
        val assets = walletAssets(chains, balances)
        assertEquals(listOf("EURe", "DAI", "BZZ"), assets.shown.map { it.token.symbol })
        assertEquals(listOf(AssetKind.HELD, AssetKind.UNSURE, AssetKind.UNSURE), assets.shown.map { it.kind })
        assertTrue(assets.noBalance.any { it.token.key == xdai.key })
        assertFalse(assets.noFunds)
        // A failed read of a zero is not a zero either.
        assertEquals(AssetKind.UNSURE, assetKind(TokenBalance.Failed("x", zero())))
    }

    @Test
    fun `no funds only when every token is a sure zero`() {
        val assets = walletAssets(chains, allZero())
        assertTrue(assets.noFunds)
        assertTrue(assets.shown.isEmpty())
        assertEquals(all.size, assets.noBalance.size)
        val h = headlineBalance(assets, refreshing = false)
        assertEquals(HeadlineBalance("0", null, "No funds yet"), h)

        // One token in doubt: not "no funds", and the headline says not every balance was read.
        val doubt = walletAssets(chains, allZero() + (eth.first().key to TokenBalance.Failed("x", null)))
        assertFalse(doubt.noFunds)
        assertEquals("Couldn’t read every balance", headlineBalance(doubt, refreshing = false).note)
        assertTrue(headlineBalance(doubt, refreshing = false).warn)
    }

    @Test
    fun `nothing read yet reads as reading, not as nine unread rows`() {
        val assets = walletAssets(chains, emptyMap())
        assertTrue(assets.reading)
        assertFalse(assets.noFunds)
        assertEquals(all.size, assets.shown.size)
        assertEquals(HeadlineBalance("—", null, "Reading from the chains…"), headlineBalance(assets, refreshing = true))
        // Part read: the unread rows show after the rest.
        val part = walletAssets(chains, mapOf(gno.first().key to some("1000000000000000000")))
        assertFalse(part.reading)
        assertEquals("xDAI", part.shown.first().token.symbol)
        assertEquals(AssetKind.UNREAD, part.shown.last().kind)
    }

    @Test
    fun `the headline is the first asset held, with its network and how many more`() {
        val xdai = gno.first()
        val eure = gno.first { it.symbol == "EURe" }
        val usdc = eth.first { it.symbol == "USDC" }
        val one = walletAssets(chains, allZero() + (xdai.key to some("1500000000000000000")))
        assertEquals(HeadlineBalance("1.5", "xDAI", "on Gnosis Chain"), headlineBalance(one, refreshing = false))
        val three = walletAssets(
            chains,
            allZero() + mapOf(
                xdai.key to some("1500000000000000000"),
                eure.key to some("2000000000000000000"),
                usdc.key to some("1000000"),
            ),
        )
        assertEquals(HeadlineBalance("1", "USDC", "on Ethereum · 2 more assets"), headlineBalance(three, refreshing = false))
        // Held before, not updated now: the figure stays, flagged.
        val stale = walletAssets(chains, allZero() + (xdai.key to TokenBalance.Failed("x", some("1500000000000000000"))))
        assertEquals(HeadlineBalance("1.5", "xDAI", "on Gnosis Chain · not updated", warn = true), headlineBalance(stale, refreshing = false))
    }

    @Test
    fun `the account sheet's line is that account's headline, or that it wasn't read`() {
        assertEquals("Balance not read yet", accountBalanceLine(chains, null))
        assertEquals("Balance not read yet", accountBalanceLine(chains, emptyMap()))
        assertEquals("No funds yet", accountBalanceLine(chains, allZero()))
        assertEquals("1.5 xDAI · on Gnosis Chain", accountBalanceLine(chains, allZero() + (gno.first().key to some("1500000000000000000"))))
    }

    @Test
    fun `the Backup row says whether the phrase has been checked (W4)`() {
        assertEquals("Recovery phrase not backed up yet", walletBackupRowSubtitle(backedUp = false))
        assertEquals("Recovery phrase written down and checked", walletBackupRowSubtitle(backedUp = true))
    }

    @Test
    fun `an account's badge shows its initials, whole characters only (W6)`() {
        assertEquals("A2", avatarInitials("Account 2"))
        assertEquals("CS", avatarInitials("  cold   storage "))
        assertEquals("S", avatarInitials("Savings"))
        assertEquals("", avatarInitials("   "))
        assertEquals("\uD83D\uDE00", avatarInitials("\uD83D\uDE00 fun").take(2))
    }
}
