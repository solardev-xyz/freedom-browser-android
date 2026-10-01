package baby.freedom.mobile.browser

import baby.freedom.swarm.NodeInfo
import baby.freedom.swarm.NodeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class PublishSetupTest {
    private val running = NodeInfo(status = NodeStatus.Running, accountAddress = "0xabc")
    private val wallet = running.copy(walletIdentity = true)
    private val light = wallet.copy(lightMode = true)
    private val oneXdai = BigInteger.TEN.pow(18)

    private fun statuses(r: PublishReadiness) = publishSteps(r).map { it.status }

    private fun s(vararg c: Char) = c.map {
        when (it) {
            'p' -> StepStatus.Pending
            'a' -> StepStatus.Active
            'w' -> StepStatus.Waiting
            else -> StepStatus.Done
        }
    }

    @Test
    fun `steps come in the ant order`() {
        assertEquals(
            listOf(
                PublishStepKey.Identity, PublishStepKey.LightMode, PublishStepKey.Fund,
                PublishStepKey.Chequebook, PublishStepKey.Stamp,
            ),
            publishSteps(PublishReadiness(running, false)).map { it.key },
        )
    }

    @Test
    fun `a stopped, starting or failed node blocks every step`() {
        for (status in listOf(NodeStatus.Stopped, NodeStatus.Starting, NodeStatus.Error)) {
            val node = light.copy(status = status)
            assertEquals(s('p', 'p', 'p', 'p', 'p'), statuses(PublishReadiness(node, true, oneXdai, chequebook = "0x1")))
            assertTrue(publishBlockedReason(node)!!.isNotBlank())
        }
        assertNull(publishBlockedReason(running))
    }

    @Test
    fun `the device's own identity comes first`() {
        assertEquals(s('a', 'p', 'p', 'p', 'p'), statuses(PublishReadiness(running, false)))
    }

    @Test
    fun `then light mode, waiting while the node restarts into it`() {
        assertEquals(s('d', 'a', 'p', 'p', 'p'), statuses(PublishReadiness(wallet, false)))
        assertEquals(s('d', 'w', 'p', 'p', 'p'), statuses(PublishReadiness(wallet, true)))
    }

    @Test
    fun `then funding, waiting only while the balance is being read`() {
        assertEquals(s('d', 'd', 'w', 'p', 'p'), statuses(PublishReadiness(light, true)))
        assertEquals(s('d', 'd', 'a', 'p', 'p'), statuses(PublishReadiness(light, true, BigInteger.ZERO)))
        assertEquals(s('d', 'd', 'a', 'p', 'p'), statuses(PublishReadiness(light, true, xdaiUnavailable = true)))
        assertTrue(publishSteps(PublishReadiness(light, true, xdaiUnavailable = true))[2].detail.contains("can't be read"))
    }

    @Test
    fun `once funded the stamp is the one to do, and the chequebook comes with it`() {
        val funded = PublishReadiness(light, true, oneXdai, chequebook = "", usableStamps = 0)
        assertEquals(s('d', 'd', 'd', 'p', 'a'), statuses(funded))
        assertEquals(s('d', 'd', 'd', 'd', 'a'), statuses(funded.copy(chequebook = "0x" + "1".repeat(40))))
        // A stamp whose chequebook deploy didn't happen: nothing here to tap for it.
        assertEquals(s('d', 'd', 'd', 'p', 'd'), statuses(funded.copy(usableStamps = 1)))
        // Not before the node is funded.
        assertEquals(s('d', 'd', 'a', 'p', 'p'), statuses(funded.copy(xdaiWei = BigInteger.ZERO)))
        assertEquals(
            s('d', 'd', 'd', 'd', 'd'),
            statuses(funded.copy(chequebook = "0x" + "1".repeat(40), usableStamps = 2)),
        )
        assertEquals("2 usable postage batches.", publishSteps(funded.copy(usableStamps = 2))[4].detail)
    }

    @Test
    fun `each step is done on its own evidence, whatever came before`() {
        // A light, funded node run as the wallet but not yet in light mode.
        assertEquals(s('d', 'a', 'd', 'p', 'p'), statuses(PublishReadiness(wallet, false, oneXdai)))
        // A deployed chequebook counts as funded even with the xDAI spent.
        assertEquals(
            s('d', 'd', 'd', 'd', 'a'),
            statuses(PublishReadiness(light, true, BigInteger.ZERO, chequebook = "0x" + "2".repeat(40))),
        )
        // Chequebook and stamps are a light node's: stale ones from before a
        // switch back to ultra-light don't count.
        assertEquals(
            s('d', 'a', 'd', 'p', 'p'),
            statuses(PublishReadiness(wallet, false, oneXdai, chequebook = "0x" + "2".repeat(40), usableStamps = 1)),
        )
    }

    @Test
    fun `the device-only key is never offered for funding`() {
        // No address to copy and no balance read until step 1 is done.
        assertNull(fundingAddress(running))
        assertNull(fundingAddress(running.copy(lightMode = true)))
        assertNull(fundingAddress(wallet.copy(status = NodeStatus.Starting)))
        assertEquals("0xabc", fundingAddress(wallet))
        // Funds on the device key don't count, and the step says why not.
        assertEquals(s('a', 'p', 'p', 'p', 'p'), statuses(PublishReadiness(running, false, oneXdai)))
        assertEquals(
            s('a', 'd', 'p', 'p', 'p'),
            statuses(PublishReadiness(running.copy(lightMode = true), true, oneXdai)),
        )
        val fund = publishSteps(PublishReadiness(running, false))[2]
        assertTrue(fund.detail.contains("step 1"))
        assertTrue(fund.detail.contains("Don't fund"))
    }

    @Test
    fun `xDAI is shown to six decimals, rounded down`() {
        assertEquals("1 xDAI", formatXdai(oneXdai))
        assertEquals("0.123456 xDAI", formatXdai(BigInteger("123456789000000000")))
        assertEquals("< 0.000001 xDAI", formatXdai(BigInteger.ONE))
        assertEquals("0 xDAI", formatXdai(BigInteger.ZERO))
        assertEquals("12345.5 xDAI", formatXdai(BigInteger("12345500000000000000000")))
    }

    @Test
    fun `gateway bodies are read the way ant writes them`() {
        assertEquals("", chequebookFrom("""{"chequebookAddress":"0x0000000000000000000000000000000000000000"}"""))
        assertEquals(
            "0x" + "ab".repeat(20),
            chequebookFrom("""{"chequebookAddress":"0x${"ab".repeat(20)}"}"""),
        )
        assertNull(chequebookFrom("""{"chequebookAddress":"0x12"}"""))
        assertNull(chequebookFrom("not json"))
        assertEquals(0, usableStampsFrom("""{"stamps":[]}"""))
        assertEquals(1, usableStampsFrom("""{"stamps":[{"usable":true},{"usable":false},{}]}"""))
        assertNull(usableStampsFrom("""{"error":"x"}"""))
    }

    @Test
    fun `the switch's line follows the node, not just the setting`() {
        assertEquals("", swarmModeSubtitle(light, null))
        assertTrue(swarmModeSubtitle(light, true).startsWith("Connected to Gnosis Chain"))
        assertTrue(swarmModeSubtitle(wallet, false).startsWith("Browsing only"))
        assertEquals("Restarting the node in light mode…", swarmModeSubtitle(wallet, true))
        assertEquals(
            "Restarting the node in ultra-light mode…",
            swarmModeSubtitle(light.copy(status = NodeStatus.Starting, lightMode = false), false),
        )
        assertEquals("Runs in light mode when the node is on", swarmModeSubtitle(NodeInfo(), true))
        // A change whose restart waits on an unreadable identity (#357 R4-M1)
        // isn't shown as restarting; one the node already runs as reads as usual.
        assertEquals(
            "Switches to light mode once the wallet can be read. Reopen the app to try again",
            swarmModeSubtitle(wallet.copy(reloadOwed = true), true),
        )
        assertEquals(
            "Switches to ultra-light mode once the wallet can be read. Reopen the app to try again",
            swarmModeSubtitle(light.copy(status = NodeStatus.Starting, lightMode = true, reloadOwed = true), false),
        )
        assertTrue(swarmModeSubtitle(light.copy(reloadOwed = true), true).startsWith("Connected to Gnosis Chain"))
    }
}
