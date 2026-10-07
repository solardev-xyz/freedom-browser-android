package baby.freedom.mobile.browser

import baby.freedom.mobile.chains.BuiltInChains
import baby.freedom.swarm.NodeInfo
import baby.freedom.swarm.NodeStatus
import baby.freedom.mobile.wallet.ChainTrustsForTest
import baby.freedom.mobile.wallet.DappCall
import baby.freedom.mobile.wallet.EthTransaction
import baby.freedom.mobile.wallet.SendQuote
import baby.freedom.mobile.wallet.SendRequest
import baby.freedom.mobile.wallet.SigningHeldException
import baby.freedom.mobile.wallet.SwarmFundLabel
import baby.freedom.mobile.wallet.SwarmFunder
import baby.freedom.mobile.wallet.TokenRegistry
import baby.freedom.mobile.wallet.WalletAccount
import java.math.BigInteger
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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
    fun `a fund that replaces a stopped send warns up front and keeps its nonce out of Details`() {
        val plain = quoteFor(plan())
        assertFalse(fundNonceUpFront(plain))
        assertNull(fundReplacesWarning(plain))
        val hash = "0x" + "cd".repeat(32)
        val replacing = plain.copy(replaces = hash)
        assertTrue(fundNonceUpFront(replacing))
        val warning = fundReplacesWarning(replacing)!!
        assertTrue(warning, warning.contains(hash))
        assertTrue(warning, warning.contains("only one of the two can go through"))
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
        // The quote is still described by the plan it was built from.
        assertEquals(fundNodeSummary(signed, days), fundReviewRows(q, signed, days)?.summary)
        // Pricing ran again (the stamps-blocked key flipped): a new per-chunk price and pool price, same batch nonce.
        val repriced = plan(perChunk = amount.multiply(BigInteger.TWO), price = sqrt.shiftRight(1))
        assertNotEquals(fundNodeSummary(signed, days), fundNodeSummary(repriced, days))
        assertNull(fundReviewRows(q, repriced, days))
        // The node restarted as another identity.
        assertNull(fundReviewRows(q, plan(n = otherNode), days))
        // The duration changed behind the review.
        assertNull(fundReviewRows(q, signed, 30))
        // Only the deposit changed (the node set up its chequebook meanwhile).
        assertNull(fundReviewRows(q, plan(dep = BigInteger.ZERO), days))
    }

    @Test
    fun `Confirm is held while the node isn't the one the review pays, or funding is blocked`() {
        assertNull(fundReviewHeld(null, node, node))
        assertNull(fundReviewHeld(null, node.lowercase(), node))
        assertEquals(FUND_REVIEW_NODE_CHANGED, fundReviewHeld(null, otherNode, node))
        // The node stopped: no funding address at all.
        assertEquals(FUND_REVIEW_NODE_CHANGED, fundReviewHeld(null, null, node))
        assertEquals("blocked", fundReviewHeld("blocked", node, node))
        assertEquals("blocked", fundReviewHeld("blocked", otherNode, node))
    }

    @Test
    fun `the Ledger's ready check re-reads the hold as well as the quote's age`() {
        var held: String? = null
        var stale = false
        val fresh = fundLedgerFresh({ held }) { stale }
        assertTrue(fresh())
        stale = true
        assertFalse(fresh())
        // The node restarted as another account while the Ledger was connecting:
        // ended with that reason, not the quote-too-old one, however old the quote.
        held = FUND_REVIEW_NODE_CHANGED
        assertEquals(FUND_REVIEW_NODE_CHANGED, assertThrows(SigningHeldException::class.java) { fresh() }.message)
        stale = false
        held = "blocked"
        assertEquals("blocked", assertThrows(SigningHeldException::class.java) { fresh() }.message)
        held = null
        assertTrue(fresh())
    }

    @Test
    fun `a send under way is held by the live node and other records, never by its own`() {
        val p = plan()
        val running = NodeInfo(status = NodeStatus.Running, accountAddress = node, walletIdentity = true, lightMode = true)
        // What SwarmFunding.noteSend records the moment this send shows Signing.
        val own = SwarmFunding.Pending(node, p.batchId, p.depth, days, null, mined = false)
        assertNull(fundSendHeld(running, null, p.batchId, node))
        assertNull(fundSendHeld(running, own, p.batchId, node))
        // Another stamp's record still holds it, as at the tap.
        val other = own.copy(batchId = "0x" + "ab".repeat(32))
        assertNotEquals(null, fundSendHeld(running, other, p.batchId, node))
        // The node restarted as another account during the Ledger's wait.
        assertEquals(FUND_REVIEW_NODE_CHANGED, fundSendHeld(running.copy(accountAddress = otherNode), own, p.batchId, node))
        // The node stopped.
        assertNotEquals(null, fundSendHeld(NodeInfo(), own, p.batchId, node))
        // The page is gone: nothing watches the node any more.
        assertEquals(FUND_PAGE_CLOSED, fundSendHeld(null, own, p.batchId, node))
    }

    @Test
    fun `the Ledger's ready check reads the live hold, not the one at the tap`() {
        val p = plan()
        val running = NodeInfo(status = NodeStatus.Running, accountAddress = node, walletIdentity = true, lightMode = true)
        // What :node's callback moves, with no recomposition (the app in the background).
        val reported = MutableStateFlow(running)
        val records = MutableStateFlow<SwarmFunding.Pending?>(null)
        val page = FundPageNode(reported)
        // The closure the page hands the Ledger, built as the review item builds it.
        val fresh = fundLedgerFresh(page.heldNow(records, p.batchId, node)) { false }
        assertTrue(fresh())
        // The send's own record appears once it shows Signing: still fine.
        val own = SwarmFunding.Pending(node, p.batchId, p.depth, days, null, mined = false)
        records.value = own
        assertTrue(fresh())
        // Another stamp's record lands while the Ledger connects.
        records.value = own.copy(batchId = "0x" + "ab".repeat(32))
        assertThrows(SigningHeldException::class.java) { fresh() }
        records.value = own
        // The node restarts as another account while the app is backgrounded.
        reported.value = running.copy(accountAddress = otherNode)
        assertEquals(FUND_REVIEW_NODE_CHANGED, assertThrows(SigningHeldException::class.java) { fresh() }.message)
        reported.value = running
        assertTrue(fresh())
        // The page is left: the hook ends with that, whatever the node says.
        page.close()
        assertEquals(FUND_PAGE_CLOSED, assertThrows(SigningHeldException::class.java) { fresh() }.message)
    }

    @Test
    fun `the page reads the node the node process reports, not a composable copy`() {
        // StampClient.node is the flow MainActivity collects and :node's callback writes.
        val before = StampClient.node.value
        try {
            val page = FundPageNode(StampClient.node)
            val moved = NodeInfo(status = NodeStatus.Running, accountAddress = otherNode, walletIdentity = true, lightMode = true)
            StampClient.node.value = moved
            assertEquals(moved, page.info)
        } finally {
            StampClient.node.value = before
        }
    }

    @Test
    fun `a plan the quote wasn't built from describes nothing`() {
        val q = quoteFor(plan())
        assertNull(fundReviewRows(q, plan(perChunk = amount.add(BigInteger.ONE)), days))
        assertNull(fundReviewRows(q, plan(n = otherNode), days))
        assertNull(fundReviewRows(q, plan(), days + 1))
        assertNull(fundReviewRows(q, SwarmFunder.Plan(node, 17, amount, ByteArray(32) { 8 }, deposit, sqrt), days))
    }
}
