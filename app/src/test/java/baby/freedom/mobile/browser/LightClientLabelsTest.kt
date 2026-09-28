package baby.freedom.mobile.browser

import baby.freedom.swarm.MyotisChainStatus
import baby.freedom.swarm.MyotisInfo
import baby.freedom.swarm.MyotisStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class LightClientLabelsTest {

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
            Triple(running, null, "Off"),
            Triple(running, MyotisChainStatus(1L, error = "boom"), "Failed"),
            Triple(running, serving.copy(running = false, paused = true), "Paused"),
            Triple(running, serving.copy(beaconState = "STALE_ANCHOR"), "Checkpoint too old"),
            Triple(running, serving, "Synced"),
            Triple(running, serving.copy(snapServingPeers = 0), "Synced, not serving yet"),
            Triple(running, serving.copy(beaconState = "CATCHING_UP"), "Catching up"),
            Triple(running, serving.copy(beaconState = "SYNCING"), "Syncing"),
            Triple(running, serving.copy(beaconState = "STARTING"), "Starting…"),
            Triple(running, MyotisChainStatus(1L), "Starting…"),
        )
        for ((node, chain, expected) in cases) {
            assertEquals("$node / $chain", expected, myotisChainLabel(node, chain))
        }
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
            "The built-in checkpoint is too old to sync from safely (9 sync periods old, the safe limit is 3). " +
                "Fetching a fresh checkpoint isn't supported on Android yet.",
            staleAnchorExplanation(stale),
        )
        assertEquals(
            "The built-in checkpoint is too old to sync from safely. Fetching a fresh checkpoint isn't supported on Android yet.",
            staleAnchorExplanation(serving.copy(beaconState = "STALE_ANCHOR")),
        )
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
        // ...and beside a chain that isn't serving yet it's counted, not "Syncing…".
        assertEquals("0 of 2 chains synced", label(serving.copy(snapServingPeers = 0), stale))
        assertEquals("0 of 2 chains synced", label(serving.copy(beaconState = "SYNCING"), stale))
        // A chain that failed to start doesn't hold the other one back...
        assertEquals("Synced", label(serving, MyotisChainStatus(100L, error = "boom")))
        // ...but nothing live at all is not "Synced".
        assertEquals("Syncing…", label())
        assertEquals("Off", lightClientStatusTriple(MyotisInfo()).label)
        assertEquals("Error", lightClientStatusTriple(MyotisInfo(MyotisStatus.Error)).label)
    }
}
