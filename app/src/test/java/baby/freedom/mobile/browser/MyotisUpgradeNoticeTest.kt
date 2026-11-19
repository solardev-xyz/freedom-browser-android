package baby.freedom.mobile.browser

import baby.freedom.swarm.MyotisChainStatus
import baby.freedom.swarm.MyotisInfo
import baby.freedom.swarm.MyotisNetwork
import baby.freedom.swarm.MyotisRecovery
import baby.freedom.swarm.MyotisStatus
import baby.freedom.swarm.MyotisUpgradeAdvisory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The light client's fork-watch notice (#542): its wording and where it shows. */
class MyotisUpgradeNoticeTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 1_790_000_000_000L
    private val date: (Long) -> String = { "D$it" }

    private val ready = MyotisChainStatus(
        chainId = 1L,
        beaconState = "SYNCED",
        running = true,
        snapServingPeers = 1,
        elReaderAvailable = true,
    )

    private fun scheduled(atMs: Long, peers: Int = 4) =
        MyotisUpgradeAdvisory(MyotisUpgradeAdvisory.SCHEDULED, atMs / 1000, "0x6c1d9423", peers)

    private fun active(peers: Int = 3) = MyotisUpgradeAdvisory(MyotisUpgradeAdvisory.ACTIVE, 0, "0x00000000", peers)

    private fun notice(advisory: MyotisUpgradeAdvisory?, chain: MyotisChainStatus = ready, network: MyotisNetwork = MyotisNetwork.Mainnet) =
        myotisUpgradeNotice(network, chain.copy(upgradeAdvisory = advisory), now, date)

    @Test
    fun `no advisory, no notice`() {
        assertNull(notice(null))
    }

    @Test
    fun `a scheduled upgrade says when, by the day, and what to do`() {
        val at = now + 12 * day + 5_000
        assertEquals(
            "Ethereum has a network upgrade coming in 12 days (D$at) that this version of Freedom doesn't know " +
                "about. Update Freedom before then to keep verified reads.",
            notice(scheduled(at))!!.text,
        )
        val one = now + day
        assertEquals(
            "Gnosis has a network upgrade coming in 1 day (D$one) that this version of Freedom doesn't know about. " +
                "Update Freedom before then to keep verified reads.",
            notice(scheduled(one), ready.copy(chainId = 100L), MyotisNetwork.Gnosis)!!.text,
        )
        val soon = now + day - 1_000
        assertEquals(
            "Ethereum has a network upgrade coming in less than a day (D$soon) that this version of Freedom doesn't " +
                "know about. Update Freedom before then to keep verified reads.",
            notice(scheduled(soon))!!.text,
        )
        val past = now - 1_000
        assertEquals(
            "Ethereum has a network upgrade due now (D$past) that this version of Freedom doesn't know about. " +
                "Update Freedom now to keep verified reads.",
            notice(scheduled(past))!!.text,
        )
        // Time 0 is unknown, never 1970.
        assertEquals(
            "Ethereum has a network upgrade coming soon that this version of Freedom doesn't know about. " +
                "Update Freedom soon to keep verified reads.",
            notice(scheduled(0))!!.text,
        )
    }

    @Test
    fun `an active upgrade claims reads stop only when the chain's own state agrees`() {
        assertEquals(
            "Ethereum has upgraded and this version of Freedom can't follow it. Verified reads may stop until you " +
                "update Freedom.",
            notice(active())!!.text,
        )
        val stuck = ready.copy(snapServingPeers = 0)
        assertEquals(
            "Ethereum has upgraded and this version of Freedom can't follow it. The light client can't verify " +
                "Ethereum until you update Freedom; Freedom uses your other Ethereum sources meanwhile.",
            notice(active(), stuck)!!.text,
        )
        // In recovery (never ready) it says the same.
        val recovering = ready.copy(recovery = MyotisRecovery(MyotisRecovery.Phase.Checking, attempt = 1))
        assertTrue(notice(active(), recovering)!!.text.contains("can't verify Ethereum"))
    }

    @Test
    fun `the detail says how many peer networks reported it, unverified`() {
        assertEquals(
            "Reported by 3 peer networks, not verified. It doesn't change how Freedom checks answers.",
            notice(active(3))!!.detail,
        )
        assertEquals(
            "Reported by 1 peer network, not verified. It doesn't change how Freedom checks answers.",
            notice(active(1))!!.detail,
        )
    }

    @Test
    fun `only a switched-on running chain shows its notice`() {
        val eth = ready.copy(upgradeAdvisory = active())
        val gno = ready.copy(chainId = 100L, upgradeAdvisory = scheduled(now + 3 * day))
        val info = MyotisInfo(status = MyotisStatus.Running, chains = listOf(eth, gno))
        val all = MyotisNetwork.entries.toSet()
        assertEquals(
            listOf(MyotisNetwork.Mainnet, MyotisNetwork.Gnosis),
            myotisUpgradeNotices(info, all, now, date).map { it.network },
        )
        // Gnosis switched off while `:myotis` still reports it: nothing for it.
        assertEquals(
            listOf(MyotisNetwork.Mainnet),
            myotisUpgradeNotices(info, setOf(MyotisNetwork.Mainnet), now, date).map { it.network },
        )
        assertEquals(emptyList<MyotisUpgradeNotice>(), myotisUpgradeNotices(info, emptySet(), now, date))
        for (status in listOf(MyotisStatus.Stopped, MyotisStatus.Starting, MyotisStatus.Error)) {
            assertEquals(
                status.name,
                emptyList<MyotisUpgradeNotice>(),
                myotisUpgradeNotices(info.copy(status = status), all, now, date),
            )
        }
    }

    @Test
    fun `the overview row adds update Freedom to a chain with a notice`() {
        val input = NodeOverviewInput(
            myotisInfo = MyotisInfo(status = MyotisStatus.Running, chains = listOf(ready.copy(upgradeAdvisory = active()))),
            myotisRunning = setOf(MyotisNetwork.Mainnet),
        )
        val row = nodeOverviewRows(input).single { it.destination == NodeDestination.LightClient }
        assertEquals("Ethereum: Synced; update Freedom · Gnosis: Off", row.status)
        // Not a node problem: the menu stays quiet about it.
        assertEquals(false, row.problem)
        val plain = nodeOverviewRows(input.copy(myotisInfo = MyotisInfo(status = MyotisStatus.Running, chains = listOf(ready))))
            .single { it.destination == NodeDestination.LightClient }
        assertEquals("Ethereum: Synced · Gnosis: Off", plain.status)
    }
}
