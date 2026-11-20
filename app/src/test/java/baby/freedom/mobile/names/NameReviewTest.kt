package baby.freedom.mobile.names

import baby.freedom.mobile.chains.BuiltInChains
import baby.freedom.mobile.l10n.Strings
import baby.freedom.mobile.names.NameOperation.Intent
import baby.freedom.mobile.names.NameOperation.State
import baby.freedom.mobile.names.NameOperation.Step
import baby.freedom.mobile.wallet.ChainTrustsForTest
import baby.freedom.mobile.wallet.DappCall
import baby.freedom.mobile.wallet.EthTransaction
import baby.freedom.mobile.wallet.NameCallLabel
import baby.freedom.mobile.wallet.SendJournal
import baby.freedom.mobile.wallet.SendJournalCodec
import baby.freedom.mobile.wallet.SendQuote
import baby.freedom.mobile.wallet.SendRequest
import baby.freedom.mobile.wallet.SendStatus
import baby.freedom.mobile.wallet.TokenRegistry
import baby.freedom.mobile.wallet.WalletAccount
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The review's words (desktop's `presentPlan`) and its rule that it only ever describes the quote it was built from. */
class NameReviewTest {
    private val me = "0x1111111111111111111111111111111111111111"
    private val secret = ByteArray(32) { 7 }
    private val price = BigInteger("3125000000000000")

    private fun op(intent: Intent = Intent.REGISTER, step: Step = Step.COMMIT, commitQuote: BigInteger? = price) = NameOperation(
        id = "name_" + "0".repeat(24), name = "freedom.eth", space = NameSpace.ETH, intent = intent, account = me, accountIndex = 0,
        duration = NameContracts.YEAR, step = step, state = State.READY, createdAt = 0, commitQuoteWei = commitQuote,
    )

    private fun plan(o: NameOperation, data: ByteArray, value: BigInteger, quoted: BigInteger? = price) = NameRegistrar.Plan(
        o.id, 1, o.step, NameContracts.Role.CONTROLLER.address, data, value, quoted, NameContracts.Role.CONTROLLER,
    )

    private fun quote(p: NameRegistrar.Plan, name: String = "freedom.eth", from: String = me, value: BigInteger = p.value): SendQuote {
        val eth = BuiltInChains.ETHEREUM
        val request = SendRequest(
            eth, TokenRegistry.native(eth), WalletAccount(0, "Account 1", from), p.to, value,
            DappCall(null, p.data, null, name = NameCallLabel(name, stepLabel(p.step))),
        )
        val tx = EthTransaction(1, BigInteger.ONE, BigInteger.valueOf(80_000), p.to, value, p.data, EthTransaction.Fees.Legacy(BigInteger.ONE))
        return SendQuote(request, tx, BigInteger.TEN.pow(18), null, 0L, ChainTrustsForTest.unverified)
    }

    @Test
    fun `step 1 says it doesn't reserve or buy the name, and that the price is paid at step 2`() {
        val o = op()
        val p = plan(o, NameContracts.commitData(ByteArray(32)), BigInteger.ZERO)
        val r = nameReview(o, p, quote(p))!!
        assertEquals("Commit to registering freedom.eth", r.headline)
        val rows = r.rows.associate { it.label to it.value }
        assertEquals("1 of 2: commit. It costs network fees but doesn't reserve or buy the name. Registering needs a second approval.", rows["Step"])
        assertEquals("1 year (365 days)", rows["Term"])
        assertEquals("0.003125 ETH, paid at step 2, plus network fees", rows["Price"])
        assertNull("nothing is sent with a commit", rows["Price buffer"])
    }

    @Test
    fun `step 2 shows the buffer it sends, and a price that rose since the commit`() {
        val o = op(step = Step.REVEAL, commitQuote = BigInteger("3000000000000000"))
        val value = NameContracts.withBuffer(price)
        val p = plan(o, NameContracts.registerData("freedom", me, NameContracts.YEAR, secret), value)
        val rows = nameReview(o, p, quote(p))!!.rows.associate { it.label to it.value }
        assertEquals("2 of 2: register and pay for the name.", rows["Step"])
        assertEquals("0.003125 ETH, plus network fees", rows["Price"])
        assertEquals("Quoted 0.003 ETH at commit, now 0.003125 ETH", rows["Price change"])
        assertTrue(rows["Price buffer"]!!.startsWith("Sends 0.0031875 ETH: the price plus 2%"))
    }

    @Test
    fun `a renewal says it doesn't transfer the name`() {
        val o = op(intent = Intent.RENEW, step = Step.RENEW, commitQuote = null)
        val p = plan(o, NameContracts.renewData("freedom", NameContracts.YEAR), NameContracts.withBuffer(price))
        val r = nameReview(o, p, quote(p))!!
        assertEquals("Renew freedom.eth", r.headline)
        val rows = r.rows.associate { it.label to it.value }
        assertNull(rows["Step"])
        assertNotNull(rows["Ownership"])
    }

    @Test
    fun `a quote that isn't exactly the plan's transaction gets no review`() {
        val o = op(step = Step.REVEAL)
        val p = plan(o, NameContracts.registerData("freedom", me, NameContracts.YEAR, secret), NameContracts.withBuffer(price))
        assertNotNull(nameReview(o, p, quote(p)))
        assertNull(nameReview(o, p, quote(p, value = p.value + BigInteger.ONE)))
        assertNull(nameReview(o, p, quote(p, name = "other.eth")))
        assertNull(nameReview(o, p, quote(p, from = "0x2222222222222222222222222222222222222222")))
        val other = plan(o, NameContracts.registerData("freedom", me, 2 * NameContracts.YEAR, secret), p.value)
        assertNull(nameReview(o, p, quote(other)))
    }

    @Test
    fun `a name call reads back from the send journal with its label`() {
        val o = op()
        val p = plan(o, NameContracts.commitData(ByteArray(32)), BigInteger.ZERO)
        val q = quote(p)
        // The journal keeps only bytes whose hash is the one it names.
        val hash = NameContracts.hex(baby.freedom.mobile.ens.Keccak256.digest(byteArrayOf(2)))
        val send = SendJournal.Send(SendStatus(q, SendStatus.Stage.Pending, hash), EthTransaction.Signed(q.tx, "0x02", hash))
        val back = SendJournalCodec.decode(SendJournalCodec.encode(SendJournal.State(send, emptyMap())))
        assertEquals(NameCallLabel("freedom.eth", "commit"), back?.send?.status?.quote?.request?.dapp?.name)
    }

    @Test
    fun `a waiting commitment says roughly how long, and an unknown outcome says it won't be resent`() {
        val waiting = op(step = Step.REVEAL).copy(state = State.WAITING_COMMITMENT, readyAt = 1_000, expiresAt = 87_400)
        assertEquals("Commit confirmed. Step 2 can be sent in about 45 s.", operationStatus(waiting, 955))
        assertEquals("Commit confirmed. Step 2 can be sent in a moment.", operationStatus(waiting, 999))
        assertTrue(operationStatus(op().copy(state = State.DISPATCH_UNKNOWN), 0).contains("won't send it again"))
        assertNotNull(revealDeadline(waiting))
        assertNull(revealDeadline(op()))
    }

    @Test
    fun `Register on a name whose same registration is waiting says it carries on, instead of the check just vanishing`() {
        val waiting = op(step = Step.REVEAL).copy(state = State.WAITING_COMMITMENT, readyAt = 1_000, expiresAt = 87_400)
        assertEquals(
            "freedom.eth already has this registration open, so nothing new was started. " +
                "Commit confirmed. Step 2 can be sent in about 45 s. It carries on below.",
            carriedOnNotice(waiting, 955),
        )
        val renewing = op(intent = Intent.RENEW, step = Step.RENEW).copy(state = State.PENDING)
        assertEquals(
            "freedom.eth already has this renewal open, so nothing new was started. " +
                "Waiting for the transaction to be confirmed… It carries on below.",
            carriedOnNotice(renewing, 0),
        )
        assertTrue(carriedOnNotice(op().copy(state = State.DISPATCH_UNKNOWN), 0)!!.contains("won't send it again"))
        assertNull("a ready one opens its review instead", carriedOnNotice(op(), 0))
    }

    @Test
    fun `the carried-on line follows the operation as it is now, and goes once it's ready or gone`() {
        val waiting = op(step = Step.REVEAL).copy(state = State.WAITING_COMMITMENT, readyAt = 1_000, expiresAt = 87_400)
        assertTrue(carriedOnLine(waiting.id, listOf(waiting), 955)!!.contains("about 45 s"))
        // A minute on: not the 45 s it said when it was handed back.
        assertTrue(carriedOnLine(waiting.id, listOf(waiting), 999)!!.contains("in a moment"))
        assertNull("ready: its card offers the review", carriedOnLine(waiting.id, listOf(waiting.copy(state = State.READY)), 1_015))
        assertNull("dismissed", carriedOnLine(waiting.id, emptyList(), 1_015))
        // Ended: it no longer carries on, so no "already has this open … carries on below".
        for (ended in listOf(State.COMPLETED, State.FAILED, State.EXPIRED, State.STALE)) {
            assertNull("$ended", carriedOnLine(waiting.id, listOf(waiting.copy(state = ended)), 1_015))
            assertNull("$ended", carriedOnNotice(waiting.copy(state = ended), 1_015))
        }
        val renewing = op(intent = Intent.RENEW, step = Step.RENEW).copy(state = State.PENDING)
        assertNull("renewed", carriedOnLine(renewing.id, listOf(renewing.copy(state = State.COMPLETED)), 0))
        assertNull("nothing handed back", carriedOnLine(null, listOf(waiting), 955))
    }

    @Test
    fun `a commitment not seen says so once, and doesn't claim a transaction of this step went through`() {
        val missing = Strings.get(baby.freedom.mobile.R.string.namereg_state_commitment_missing)
        val unseen = op(step = Step.REVEAL).copy(state = State.VERIFICATION_NEEDED, message = missing)
        assertEquals(missing, operationStatus(unseen, 0))
        assertEquals(missing, carriedOnNotice(unseen, 0)!!.substringAfter("started. ").substringBefore(" It carries on below."))
        // A reveal mined with its effect not seen yet: that one did go through.
        val mined = unseen.copy(message = Strings.get(baby.freedom.mobile.R.string.namereg_state_verification_needed))
        assertTrue(operationStatus(mined, 0).startsWith("The transaction went through"))
        assertEquals(1, Regex("Check again in a moment").findAll(operationStatus(mined, 0)).count())
        assertTrue(operationStatus(unseen.copy(message = null), 0).startsWith("The transaction went through"))
    }
}
