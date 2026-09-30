package baby.freedom.mobile.browser

import baby.freedom.mobile.chains.BuiltInChains
import baby.freedom.mobile.wallet.ChainTrustsForTest
import baby.freedom.mobile.wallet.DappCall
import baby.freedom.mobile.wallet.EthTransaction
import baby.freedom.mobile.wallet.SendQuote
import baby.freedom.mobile.wallet.SendRequest
import baby.freedom.mobile.wallet.SwarmFundLabel
import baby.freedom.mobile.wallet.SwarmFunder
import baby.freedom.mobile.wallet.TokenRegistry
import baby.freedom.mobile.wallet.WalletAccount
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The fund-node review describes the transaction being signed (#242, audit
 * #229): its summary and Node row come from the plan the quote's call data
 * was built from, never from a plan priced again while the review is open.
 */
class FundNodeReviewTest {
    private val node = "0xD5572300E441b77b72bdd318BBFA7b97A1F03096"
    private val otherNode = "0x9858EfFD232B4033E47d90003D41EC34EcaEda94"
    private val nonce = ByteArray(32) { 7 }
    private val sqrt = BigInteger("272eb7ed8bfa0b5c55e8c2197", 16)
    private val amount = BigInteger("4325218560")
    private val deposit = BigInteger.TEN.pow(13)
    private val days = 7L

    private fun plan(n: String = node, perChunk: BigInteger = amount, price: BigInteger = sqrt, dep: BigInteger = deposit) =
        SwarmFunder.Plan(n, 17, perChunk, nonce, dep, price)

    /** The quote `review(p)` builds from [p]: its value, call data and label. */
    private fun quoteFor(p: SwarmFunder.Plan, d: Long = days): SendQuote {
        val gnosis = BuiltInChains.GNOSIS
        val from = WalletAccount(0, "Account 1", "0x1111111111111111111111111111111111111111")
        val data = p.calldata()
        return SendQuote(
            SendRequest(
                gnosis, TokenRegistry.native(gnosis), from, SwarmFunder.ADDRESS, p.value,
                DappCall(null, data, null, swarm = SwarmFundLabel(p.node, p.batchId, p.depth, d)),
            ),
            EthTransaction(100, BigInteger.ONE, BigInteger.valueOf(400_000), SwarmFunder.ADDRESS, p.value, data, EthTransaction.Fees.Legacy(BigInteger.ONE)),
            BigInteger.TEN.pow(18), null, 0L, ChainTrustsForTest.unverified,
        )
    }

    @Test
    fun `the review shows the plan the quote was built from`() {
        val p = plan()
        assertEquals(FundReviewRows(fundNodeSummary(p, days), node), fundReviewRows(quoteFor(p), p, days))
    }

    @Test
    fun `a plan priced again during the review never labels the quote being signed`() {
        val signed = plan()
        val q = quoteFor(signed)
        // Pricing ran again (the stamps-blocked key flipped): a new per-chunk price and pool price, same batch nonce.
        val repriced = plan(perChunk = amount.multiply(BigInteger.TWO), price = sqrt.shiftRight(1))
        assertNotEquals(fundNodeSummary(signed, days), fundNodeSummary(repriced, days))
        assertNotEquals(fundNodeSummary(repriced, days), fundReviewRows(q, repriced, days)?.summary)
        // The node restarted as another identity.
        assertNotEquals(otherNode, fundReviewRows(q, plan(n = otherNode), days)?.node)
        // The duration changed behind the review.
        assertNotEquals(fundNodeSummary(signed, 30), fundReviewRows(q, signed, 30)?.summary)
        // Only the deposit changed (the node set up its chequebook meanwhile).
        val noDeposit = plan(dep = BigInteger.ZERO)
        assertNotEquals(fundNodeSummary(noDeposit, days), fundReviewRows(q, noDeposit, days)?.summary)
    }
}
