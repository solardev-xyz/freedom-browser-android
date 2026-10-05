package baby.freedom.mobile.browser

import baby.freedom.swarm.NodeInfo
import baby.freedom.swarm.NodeStatus
import java.math.BigInteger
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChequebookTest {
    private val light = NodeInfo(status = NodeStatus.Running, accountAddress = "0xabc", walletIdentity = true, lightMode = true)
    private val chequebook = "0x" + "37".repeat(20)
    private val milli = BigInteger.TEN.pow(13) // 0.001 xBZZ

    @Test
    fun `the presets are desktop's three and two small ones, exact in PLUR`() {
        assertEquals(
            listOf("10000000000000", "100000000000000", "1000000000000000", "5000000000000000", "10000000000000000"),
            DEPOSIT_PRESETS_PLUR.map { it.toString() },
        )
        assertEquals(
            listOf("0.001 xBZZ", "0.01 xBZZ", "0.1 xBZZ", "0.5 xBZZ", "1 xBZZ"),
            DEPOSIT_PRESETS_PLUR.map(::formatBzzExact),
        )
    }

    @Test
    fun `xBZZ amounts read down, never up`() {
        assertEquals("0.001 xBZZ", formatBzz(milli))
        // The test account's balance: 0.0034393882720917 xBZZ.
        assertEquals("0.003439 xBZZ", formatBzz(BigInteger("34393882720917")))
        assertEquals("0 xBZZ", formatBzz(BigInteger.ZERO))
        assertEquals("< 0.000001 xBZZ", formatBzz(BigInteger.ONE))
        assertEquals("12.5 xBZZ", formatBzz(BigInteger("125000000000000000")))
    }

    @Test
    fun `reads the gateway's chequebook balance and the account's xBZZ`() {
        assertEquals(milli, chequebookBalanceFrom("""{"totalBalance":"10000000000000","availableBalance":"10000000000000"}"""))
        assertNull(chequebookBalanceFrom("""{"code":503,"message":"chain initializing"}"""))
        assertNull(chequebookBalanceFrom("""{"totalBalance":"-1"}"""))
        assertEquals(
            BigInteger("34393882720917"),
            walletBzzFrom("""{"bzzBalance":"34393882720917","nativeTokenBalance":"4982657111776494000","chainID":100}"""),
        )
        assertNull(walletBzzFrom("not json"))
    }

    @Test
    fun `a deposit waits for a chequebook, xBZZ to move, an amount, and a wallet identity`() {
        val ready = ChequebookState(address = chequebook, balancePlur = milli, walletPlur = BigInteger("34393882720917"))
        assertNull(depositBlockedReason(light, ready, milli))
        // Nothing read yet, or no chequebook.
        assertTrue(depositBlockedReason(light, ChequebookState(), milli)!!.startsWith("Checking"))
        assertTrue(depositBlockedReason(light, ready.copy(address = ""), milli)!!.contains("first postage stamp"))
        // No amount chosen; more than the account holds; nothing at all to move.
        assertEquals("Choose an amount.", depositBlockedReason(light, ready, null))
        assertEquals(
            "The node's account holds only 0.003439 xBZZ. Choose a smaller amount, or send xBZZ on Gnosis Chain " +
                "to the node's address first.",
            depositBlockedReason(light, ready, DEPOSIT_PRESETS_PLUR[1]),
        )
        assertTrue(depositBlockedReason(light, ready.copy(walletPlur = BigInteger.ZERO), milli)!!.contains("holds no xBZZ"))
        // The device-only key never spends; ultra-light or a stopped node has no chequebook to read.
        assertTrue(depositBlockedReason(light.copy(walletIdentity = false), ready, milli)!!.contains("wallet"))
        assertTrue(depositBlockedReason(light.copy(lightMode = false), ready, milli)!!.contains("light mode"))
        assertTrue(depositBlockedReason(light.copy(status = NodeStatus.Stopped), ready, milli)!!.startsWith("Turn on"))
        assertNull(chequebookBlockedReason(light.copy(walletIdentity = false)))
    }

    @Test
    fun `the confirmation names the amount, the chequebook in full, and the gas bound`() {
        val text = depositConfirmText(milli, chequebook)
        assertTrue(text, text.startsWith("The node moves 0.001 xBZZ from its account into its chequebook at $chequebook,"))
        assertTrue(text, text.contains("up to 0.01 xDAI of gas for the one transaction"))
        assertTrue(text, text.contains("can't be undone"))
    }

    @Test
    fun `the node page's chequebook line`() {
        assertEquals("Checking…", chequebookSummary(ChequebookState()))
        assertEquals("None yet (comes with the first postage stamp)", chequebookSummary(ChequebookState(address = "")))
        assertEquals("Checking…", chequebookSummary(ChequebookState(address = chequebook)))
        assertEquals("0.002 xBZZ", chequebookSummary(ChequebookState(address = chequebook, balancePlur = milli.shiftLeft(1))))
    }

    @Test
    fun `a deposit says what it's doing and how it ended`() {
        assertTrue(spendStatusText(StampClient.Spend.Running(StampClient.Kind.Deposit, null))!!.startsWith("Depositing"))
        assertEquals("Deposited into the chequebook.", spendStatusText(StampClient.Spend.Done(StampClient.Kind.Deposit, null)))
        assertEquals(
            "The deposit failed: the node holds only 0.001 xBZZ",
            spendStatusText(StampClient.Spend.Failed(StampClient.Kind.Deposit, null, "the node holds only 0.001 xBZZ")),
        )
        // One that ended without a clear answer isn't called a failure: it may be out.
        assertEquals(
            "The deposit didn't report back: it may already have been sent (chain transaction timed out). " +
                "Check the chequebook's balance before depositing again",
            spendStatusText(
                StampClient.spendOutcome(
                    StampClient.Kind.Deposit, null,
                    StampClient.Answer.Failed(
                        "${baby.freedom.swarm.SwarmNode.DEPOSIT_MAYBE_SENT} (chain transaction timed out). " +
                            "Check the chequebook's balance before depositing again",
                        maybeSent = true,
                    ),
                ) { throw AssertionError() },
            ),
        )
        // Told by `:node`'s flag, not by the words (#313 R1-M1): a message held
        // from another app language is still "didn't report back".
        assertEquals(
            "The deposit didn't report back: vielleicht schon gesendet",
            spendStatusText(
                StampClient.spendOutcome(
                    StampClient.Kind.Deposit, null, StampClient.Answer.Failed("vielleicht schon gesendet", maybeSent = true),
                ) { throw AssertionError() },
            ),
        )
        // And a plain failure that happens to start with those words is still a failure.
        assertEquals(
            "The deposit failed: ${baby.freedom.swarm.SwarmNode.DEPOSIT_MAYBE_SENT}",
            spendStatusText(
                StampClient.spendOutcome(
                    StampClient.Kind.Deposit, null, StampClient.Answer.Failed(baby.freedom.swarm.SwarmNode.DEPOSIT_MAYBE_SENT),
                ) { throw AssertionError() },
            ),
        )
        // Nor is one the app stopped waiting for (the node is still on it).
        assertEquals(
            "The deposit didn't report back: it may already have been sent (the node is still sending it). " +
                "The chequebook's balance shows it once it confirms",
            spendStatusText(
                StampClient.spendOutcome(
                    StampClient.Kind.Deposit, null, StampClient.Answer.Failed(StampClient.TIMED_OUT, timedOut = true),
                ) { throw AssertionError() },
            ),
        )
        assertEquals(
            "Extending the stamp failed: The node is still sending the transactions. The list shows the stamp once they confirm.",
            spendStatusText(
                StampClient.Spend.Failed(StampClient.Kind.Extend, null, StampClient.stillSendingMessage(StampClient.Kind.Extend)),
            ),
        )
        // A buy the app stopped waiting for is shown once `:node` ended it (#222 R4-F1): not as a failure.
        assertEquals(
            "The stamp purchase didn't report back: it took longer than expected, and ended without telling the app " +
                "how it went. The list shows the stamp if it was bought.",
            spendStatusText(
                StampClient.spendOutcome(StampClient.Kind.Buy, null, StampClient.Answer.Failed(StampClient.TIMED_OUT, timedOut = true)) {},
            ),
        )
        // Its outcome keeps the node page's entries reachable with the node off, as a stamp's does.
        assertTrue(stampsEntryShown(light.copy(status = NodeStatus.Stopped), StampClient.Spend.Done(StampClient.Kind.Deposit, null)))
    }

    @Test
    fun `publish setup's chequebook step says what it holds`() {
        val r = PublishReadiness(light, true, BigInteger.ONE, chequebook = chequebook, chequebookBalancePlur = milli, usableStamps = 1)
        val step = publishSteps(r).single { it.key == PublishStepKey.Chequebook }
        assertEquals("Deployed at $chequebook. Holds 0.001 xBZZ.", step.detail)
        assertEquals(StepStatus.Done, step.status)
        val unread = publishSteps(r.copy(chequebookBalancePlur = null)).single { it.key == PublishStepKey.Chequebook }
        assertEquals("Deployed at $chequebook.", unread.detail)
    }

    // Paid downloads (browsing credit): mocked ant answers for the states
    // the page shows — funded and paying, empty, switched off, lost.

    private fun swap(supported: Boolean = true, enabled: Boolean = true, paying: Boolean = false) =
        swapStatusFrom(
            JSONObject(
                """{"supported":$supported,"swap_switch":true,"swap_enabled":$enabled,"paying":$paying,""" +
                    """"chequebook":"$chequebook","persisted":false}""",
            ),
        )

    private val funded = ChequebookState(
        address = chequebook,
        balancePlur = BigInteger("110000000000000"), // 0.011 xBZZ on chain
        walletPlur = BigInteger.ZERO,
        availablePlur = BigInteger("80000000000000"), // 0.008 xBZZ left
    )

    @Test
    fun `reads ant's swap status`() {
        assertEquals(SwapStatus(supported = true, swapEnabled = false, paying = false), swap(enabled = false))
        assertEquals(SwapStatus(supported = true, swapEnabled = true, paying = true), swap(paying = true))
        // Not ant's swap status: nothing to show rather than a guess.
        assertNull(swapStatusFrom(JSONObject("""{"enabled":false}""")))
    }

    @Test
    fun `spendable credit is availableBalance, an upper bound when ant can't count its cheques`() {
        // 0.011 xBZZ on chain, nearly all of it written away in uncashed cheques.
        val f = chequebookFundsFrom("""{"totalBalance":"110000000000000","availableBalance":"9000000"}""")!!
        assertEquals(BigInteger("110000000000000"), f.totalPlur)
        assertEquals(BigInteger("9000000"), f.availablePlur)
        assertFalse(f.availableUpperBound)
        assertFalse(f.ledgerLost)
        val unsure = chequebookFundsFrom(
            """{"totalBalance":"110000000000000","availableBalance":"110000000000000",""" +
                """"availableBalanceError":"chequebook.totalPaidOut timed out"}""",
        )!!
        assertTrue(unsure.availableUpperBound)
        // An older gateway with no availableBalance: the total, as an upper bound.
        val old = chequebookFundsFrom("""{"totalBalance":"10000000000000"}""")!!
        assertEquals(milli, old.availablePlur)
        assertTrue(old.availableUpperBound)
        val lost = chequebookFundsFrom(
            """{"totalBalance":"110000000000000","availableBalance":"110000000000000",""" +
                """"availableBalanceError":"…lost…","chequeLedgerLost":"the outbound cheque ledger was unparseable…"}""",
        )!!
        assertTrue(lost.ledgerLost)
        assertTrue(lost.availableUpperBound)
        assertNull(chequebookFundsFrom("""{"code":503,"message":"chain initializing"}"""))
    }

    @Test
    fun `paying peers comes from ant's own paying flag`() {
        assertEquals(CreditStatus.Paying(low = false), creditStatus(swap(paying = true), funded))
        assertEquals("Paying peers", creditStatusText(CreditStatus.Paying(low = false)))
        // Below half the default deposit: still paying, but low.
        assertEquals(
            CreditStatus.Paying(low = true),
            creditStatus(swap(paying = true), funded.copy(availablePlur = BigInteger("4000000000000"))),
        )
        assertEquals(CreditStatus.Checking, creditStatus(null, funded))
    }

    @Test
    fun `a confirmed lost ledger keeps the credit an upper bound, though ant stops saying so`() {
        // After a confirmation ant drops chequeLedgerLost and availableBalanceError.
        val after = chequebookFundsFrom("""{"totalBalance":"110000000000000","availableBalance":"100000000000000"}""")!!
        assertFalse(after.availableUpperBound)
        val read = funded.copy(availablePlur = after.availablePlur, availableUpperBound = false)
        // Not confirmed here (or another chequebook): the figure stays exact.
        assertEquals(read, read.withConfirmedLedgers(emptySet()))
        assertEquals(read, read.withConfirmedLedgers(setOf("0x" + "11".repeat(20))))
        // Confirmed (matched case-insensitively): an upper bound for good.
        val confirmed = read.withConfirmedLedgers(setOf(chequebook.lowercase()))
        assertTrue(confirmed.availableUpperBound)
        assertTrue(confirmed.ledgerConfirmed)
        assertEquals(read.availablePlur, confirmed.availablePlur)
        val upper = read.copy(address = chequebook.uppercase().replace("0X", "0x")).withConfirmedLedgers(setOf(chequebook))
        assertTrue(upper.ledgerConfirmed)
        // Still lost (a second loss): the lost card is what shows, not this.
        assertFalse(read.copy(ledgerLost = true).withConfirmedLedgers(setOf(chequebook)).ledgerConfirmed)
        // No chequebook / not read yet: nothing to apply.
        assertEquals(ChequebookState(address = ""), ChequebookState(address = "").withConfirmedLedgers(setOf(chequebook)))
        assertEquals(ChequebookState(), ChequebookState().withConfirmedLedgers(setOf(chequebook)))
        // Paying with an upper-bound figure says it may be less; under the
        // thresholds it is surely under them.
        assertEquals(CreditStatus.Paying(low = false, uncertain = true), creditStatus(swap(paying = true), confirmed))
        assertEquals(
            CreditStatus.Paying(low = true, uncertain = true),
            creditStatus(swap(paying = true), confirmed.copy(availablePlur = BigInteger("4000000000000"))),
        )
        assertEquals(
            FreeTierReason.NoCredit,
            (creditStatus(swap(paying = true), confirmed.copy(availablePlur = BigInteger.ZERO)) as CreditStatus.FreeTier).reason,
        )
    }

    @Test
    fun `free tier says why`() {
        fun why(s: SwapStatus?, st: ChequebookState = funded) = (creditStatus(s, st) as CreditStatus.FreeTier).reason
        assertEquals(FreeTierReason.NotSupported, why(swap(supported = false, paying = true)))
        assertEquals(FreeTierReason.SwitchedOff, why(swap(enabled = false)))
        assertEquals(FreeTierReason.NoChequebook, why(swap(), ChequebookState(address = "")))
        assertEquals(FreeTierReason.LedgerLost, why(swap(), funded.copy(ledgerLost = true)))
        // Used up: below one cheque, whatever ant's last funds read said.
        assertEquals(FreeTierReason.NoCredit, why(swap(paying = true), funded.copy(availablePlur = BigInteger("8999999999"))))
        assertEquals(FreeTierReason.NoCredit, why(swap(), funded.copy(availablePlur = BigInteger.ZERO)))
        // Credit left and the switch on, but ant hasn't read the funds yet.
        assertEquals(FreeTierReason.NotSetUp, why(swap()))
        assertEquals(
            "Free tier: paying peers is switched off.",
            creditStatusText(CreditStatus.FreeTier(FreeTierReason.SwitchedOff)),
        )
        assertTrue(creditStatusText(CreditStatus.FreeTier(FreeTierReason.NoCredit)).contains("used up"))
        // Not set up covers a chequebook ant's chain check turned down too:
        // no promise it pays within a minute.
        val notSetUp = creditStatusText(CreditStatus.FreeTier(FreeTierReason.NotSetUp))
        assertTrue(notSetUp.contains("usually"))
        assertTrue(notSetUp.contains("couldn't verify the chequebook"))
    }

    @Test
    fun `a deposit beyond the node's xBZZ asks to fund the node first`() {
        val holds = funded.copy(walletPlur = milli)
        assertFalse(depositNeedsFunding(holds, milli))
        assertFalse(depositNeedsFunding(holds, null))
        assertTrue(depositNeedsFunding(holds, milli.multiply(BigInteger.TEN)))
        assertTrue(depositNeedsFunding(funded, null)) // holds nothing at all
        assertFalse(depositNeedsFunding(funded.copy(walletPlur = null), milli)) // not read yet
    }

    @Test
    fun `a lost ledger's confirmation says how it went`() {
        assertTrue(liabilityOutcomeText(StampClient.Answer.Ok(JSONObject().put("confirmed", true))).startsWith("Confirmed"))
        assertTrue(liabilityOutcomeText(StampClient.Answer.Ok(JSONObject().put("confirmed", false))).startsWith("Nothing to confirm"))
        // ant's 1 is "no loss on record": it says nothing about paying (the switch may be off).
        assertFalse(liabilityOutcomeText(StampClient.Answer.Ok(JSONObject().put("confirmed", false))).contains("pays"))
        assertEquals("Couldn't confirm: boom", liabilityOutcomeText(StampClient.Answer.Failed("boom")))
    }

    @Test
    fun `a lost ledger is recorded as confirmed before the node is asked, and a late or unseen confirmation keeps it`() = runBlocking {
        val events = mutableListOf<String>()
        val record: suspend (String) -> Unit = { events += "record $it" }
        // The node's answer didn't come in time: ant may still confirm late.
        val timedOut = confirmLostLedger(chequebook, record) {
            events += "confirm $it"
            StampClient.Answer.Failed("no answer", timedOut = true)
        }
        assertEquals(listOf("record $chequebook", "confirm $chequebook"), events)
        assertTrue(timedOut is StampClient.Answer.Failed)
        // The retry is "nothing to confirm" — the record is already there,
        // and the page reads the credit as an upper bound once the loss is gone.
        val retry = confirmLostLedger(chequebook, record) { StampClient.Answer.Ok(JSONObject().put("confirmed", false)) }
        assertTrue(retry is StampClient.Answer.Ok)
        val recorded = events.filter { it.startsWith("record") }.map { it.removePrefix("record ") }.toSet()
        val afterwards = ChequebookState(address = chequebook, availablePlur = milli).withConfirmedLedgers(recorded)
        assertTrue(afterwards.availableUpperBound)
        // A record whose confirmation didn't land is ignored while the loss is still reported.
        assertFalse(ChequebookState(address = chequebook, ledgerLost = true).withConfirmedLedgers(recorded).ledgerConfirmed)
    }

    @Test
    fun `a lost ledger isn't confirmed when its record can't be saved`() = runBlocking {
        var asked = false
        val answer = confirmLostLedger(chequebook, record = { throw java.io.IOException("disk full") }) {
            asked = true
            StampClient.Answer.Ok(JSONObject().put("confirmed", true))
        }
        assertFalse(asked)
        assertTrue(answer is StampClient.Answer.Failed)
        assertEquals(
            "Couldn't confirm: the app couldn't save a note of it, so the node wasn't asked. Try again.",
            liabilityOutcomeText(answer),
        )
    }

    @Test
    fun `the copy says the chequebook also pays for faster downloads, at measured costs, capped by the deposit`() {
        val intro = baby.freedom.mobile.l10n.Strings.get(baby.freedom.mobile.R.string.stamps_chequebook_intro)
        assertTrue(intro.contains("faster downloads"))
        assertTrue(intro.contains("never goes past what is deposited"))
        val cost = baby.freedom.mobile.l10n.Strings.get(baby.freedom.mobile.R.string.stamps_credit_cost_note)
        assertTrue(cost.contains("0.59 xBZZ per GB"))
        assertTrue(depositConfirmText(milli, chequebook).contains("faster downloads"))
    }
}
