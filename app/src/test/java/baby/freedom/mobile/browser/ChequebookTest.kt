package baby.freedom.mobile.browser

import baby.freedom.swarm.NodeInfo
import baby.freedom.swarm.NodeStatus
import java.math.BigInteger
import org.junit.Assert.assertEquals
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
}
