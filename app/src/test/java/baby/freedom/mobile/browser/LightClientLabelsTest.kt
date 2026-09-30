package baby.freedom.mobile.browser

import baby.freedom.swarm.MyotisChainStatus
import baby.freedom.swarm.MyotisInfo
import baby.freedom.swarm.MyotisNetwork
import baby.freedom.swarm.MyotisRecovery
import baby.freedom.swarm.MyotisRecoveryReason
import baby.freedom.swarm.MyotisStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class LightClientLabelsTest {

    private val checking = MyotisRecovery(MyotisRecovery.Phase.Checking, attempt = 1)
    private val restarting = MyotisRecovery(MyotisRecovery.Phase.Restarting, attempt = 1)
    private fun blocked(reason: MyotisRecoveryReason) = MyotisRecovery(MyotisRecovery.Phase.Blocked, reason = reason)

    private val serving = MyotisChainStatus(
        chainId = 1L,
        beaconState = "SYNCED",
        peerCount = 5,
        snapPeers = 3,
        snapServingPeers = 1,
        headBlock = 23_456_789L,
        finalizedBlock = 23_456_700L,
        running = true,
        elReaderAvailable = true,
    )

    @Test
    fun `chain label follows the node and the chain`() {
        val running = MyotisStatus.Running
        val cases = listOf(
            Triple(MyotisStatus.Stopped, serving, "Off"),
            Triple(MyotisStatus.Starting, null, "Starting…"),
            Triple(MyotisStatus.Error, null, "Off"),
            Triple(MyotisStatus.Error, MyotisChainStatus(1L, error = "boom"), "Failed"),
            // Just switched on beside a running chain.
            Triple(running, null, "Starting…"),
            Triple(running, MyotisChainStatus(1L, error = "boom"), "Failed"),
            Triple(running, serving.copy(running = false, paused = true), "Paused"),
            Triple(running, serving.copy(beaconState = "STALE_ANCHOR"), "Checkpoint too old"),
            Triple(running, serving.copy(beaconState = "STALE_ANCHOR", recovery = checking), "Updating checkpoint"),
            Triple(running, serving.copy(recovery = restarting), "Updating checkpoint"),
            Triple(running, serving.copy(recovery = blocked(MyotisRecoveryReason.QuorumConflict)), "Sync paused"),
            Triple(running, serving.copy(recovery = blocked(MyotisRecoveryReason.Stalled)), "Syncing slowly"),
            Triple(running, MyotisChainStatus(100L, recovery = blocked(MyotisRecoveryReason.Storage)), "Sync paused"),
            Triple(running, serving, "Synced"),
            Triple(running, serving.copy(snapServingPeers = 0), "Synced, not serving yet"),
            Triple(running, serving.copy(beaconState = "CATCHING_UP"), "Catching up"),
            Triple(running, serving.copy(beaconState = "SYNCING"), "Syncing"),
            Triple(running, serving.copy(beaconState = "STARTING"), "Starting…"),
            Triple(running, MyotisChainStatus(1L), "Starting…"),
        )
        for ((node, chain, expected) in cases) {
            assertEquals("$node / $chain", expected, myotisChainLabel(node, chain, on = true))
            // A chain switched off (#274) is off, whatever the rest is doing.
            assertEquals("$node / $chain", "Off", myotisChainLabel(node, chain, on = false))
        }
    }

    @Test
    fun `only the chains switched on show, and none on is off`() {
        val gnosis = serving.copy(chainId = 100L)
        val info = MyotisInfo(MyotisStatus.Running, listOf(serving, gnosis))
        // Ethereum just switched off, before `:myotis` dropped its row.
        val onlyGnosis = lightClientInfoFor(info, setOf(MyotisNetwork.Gnosis))
        assertEquals(listOf(100L), onlyGnosis.chains.map { it.chainId })
        assertEquals("Synced", lightClientStatusTriple(onlyGnosis).label)
        assertEquals(info, lightClientInfoFor(info, setOf(MyotisNetwork.Mainnet, MyotisNetwork.Gnosis)))
        assertEquals(MyotisInfo(), lightClientInfoFor(info, emptySet()))
        assertEquals("Off", lightClientStatusTriple(lightClientInfoFor(info, emptySet())).label)
    }

    @Test
    fun `state peers show how many serve at the head`() {
        assertEquals("3 · 1 at head", statePeersLabel(serving))
        assertEquals("0", statePeersLabel(serving.copy(snapPeers = 0, snapServingPeers = 0)))
    }

    @Test
    fun `stale anchor explains its age against the bound`() {
        val stale = serving.copy(beaconState = "STALE_ANCHOR", currentPeriod = 3692, targetPeriod = 3701, wsBoundPeriods = 3)
        assertEquals(
            "This chain's checkpoint is too old to sync from safely (9 sync periods old, the safe limit is 3).",
            staleAnchorExplanation(stale),
        )
        assertEquals("9 sync periods old, the safe limit is 3", anchorAge(stale))
        assertEquals(
            "This chain's checkpoint is too old to sync from safely.",
            staleAnchorExplanation(serving.copy(beaconState = "STALE_ANCHOR")),
        )
        assertEquals(null, anchorAge(serving))
    }

    @Test
    fun `a recovering chain is never ready and says what it's doing`() {
        assertEquals(false, serving.copy(recovery = restarting).ready)
        assertEquals("", serving.copy(recovery = restarting).notServingReason)
        assertEquals(
            "This chain's checkpoint is too old to sync from. Asking checkpoint services for a fresh one…",
            checking.message(0),
        )
        assertEquals("Fresh checkpoint agreed. Syncing from it…", restarting.message(0))
        val waiting = MyotisRecovery(
            MyotisRecovery.Phase.Waiting, reason = MyotisRecoveryReason.QuorumUnavailable, attempt = 1, nextRetryAt = 60_000L,
        )
        assertEquals(
            "Not enough checkpoint sources could confirm a recent checkpoint. Trying again in 15s.",
            waiting.message(45_000L),
        )
        assertEquals(true, waiting.canRetry)
        assertEquals(false, waiting.canRepair)
        assertEquals(false, checking.canRetry)
        assertEquals(true, blocked(MyotisRecoveryReason.Storage).canRepair)
    }

    @Test
    fun `block numbers are grouped`() {
        assertEquals("23,456,789", formatBlock(23_456_789L))
    }

    @Test
    fun `overall line is synced only once every live chain serves`() {
        val gnosis = serving.copy(chainId = 100L)
        fun label(vararg chains: MyotisChainStatus) =
            lightClientStatusTriple(MyotisInfo(MyotisStatus.Running, chains.toList())).label
        assertEquals("Synced", label(serving, gnosis))
        assertEquals("1 of 2 chains synced", label(serving, gnosis.copy(beaconState = "STALE_ANCHOR")))
        assertEquals("Syncing…", label(serving.copy(beaconState = "SYNCING"), gnosis.copy(beaconState = "SYNCING")))
        // A parked chain never counts as syncing: alone it says why...
        val stale = gnosis.copy(beaconState = "STALE_ANCHOR")
        assertEquals("Checkpoint too old", label(MyotisChainStatus(1L, error = "boom"), stale))
        assertEquals("Checkpoint too old", label(stale))
        // Recovering, or stopped for the user, it says so.
        assertEquals("Updating checkpoint", label(stale.copy(recovery = checking)))
        assertEquals("Sync paused", label(serving.copy(recovery = restarting), stale.copy(recovery = blocked(MyotisRecoveryReason.Clock))))
        assertEquals("1 of 2 chains synced", label(serving, gnosis.copy(recovery = restarting)))
        // ...and beside a chain that isn't serving yet it's counted, not "Syncing…".
        assertEquals("0 of 2 chains synced", label(serving.copy(snapServingPeers = 0), stale))
        assertEquals("0 of 2 chains synced", label(serving.copy(beaconState = "SYNCING"), stale))
        // A chain that failed to start doesn't hold the other one back...
        assertEquals("Synced", label(serving, MyotisChainStatus(100L, error = "boom")))
        // ...but nothing live at all is not "Synced": a chain switched on
        // that hasn't booted yet is still starting.
        assertEquals("Starting…", label())
        assertEquals("Off", lightClientStatusTriple(MyotisInfo()).label)
        assertEquals("Error", lightClientStatusTriple(MyotisInfo(MyotisStatus.Error)).label)
    }
}
