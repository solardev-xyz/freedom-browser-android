package baby.freedom.swarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MyotisChainStatusTest {

    /** A synced, serving status as engine ABI 32 prints it (trimmed to the keys we read plus a few we don't). */
    private val synced = """
        {"running":true,"paused":false,"network":"mainnet","beaconState":"SYNCED",
         "bootstrapped":true,"peerCount":7,"lcHunting":false,"elReaderAvailable":true,
         "snapPeers":4,"snapServingPeers":2,"readyPeers":4,
         "optimisticBlockNumber":23456789,"finalizedBlockNumber":23456700,
         "executionBlockNumber":23456700,"elHunting":false}
    """.trimIndent()

    @Test
    fun `decodes the fields the node screen shows`() {
        val s = MyotisChainStatus.decode(1L, synced)
        assertEquals(1L, s.chainId)
        assertEquals("SYNCED", s.beaconState)
        assertEquals(7, s.peerCount)
        assertEquals(4, s.snapPeers)
        assertEquals(2, s.snapServingPeers)
        assertEquals(23456789L, s.headBlock)
        assertEquals(23456700L, s.finalizedBlock)
        assertTrue(s.running)
        assertTrue(s.ready)
        assertEquals("", s.notServingReason)
    }

    @Test
    fun `unknown handle, malformed body and missing keys decode to not ready`() {
        for (json in listOf("{}", "", "not json", "[]", """{"beaconState":"SYNCED","running":true}""")) {
            val s = MyotisChainStatus.decode(100L, json)
            assertFalse(json, s.ready)
            assertEquals(json, 0, s.peerCount)
        }
    }

    @Test
    fun `synced with pooled but no serving state peers is not ready and says why`() {
        val s = MyotisChainStatus.decode(1L, synced.replace("\"snapServingPeers\":2", "\"snapServingPeers\":0"))
        assertFalse(s.ready)
        assertEquals("no state peer at the head", s.notServingReason)
    }

    @Test
    fun `execution reader down or hunting is not ready`() {
        val down = MyotisChainStatus.decode(1L, synced.replace("\"elReaderAvailable\":true", "\"elReaderAvailable\":false"))
        assertFalse(down.ready)
        assertEquals("execution reader down", down.notServingReason)
        val hunting = MyotisChainStatus.decode(1L, synced.replace("\"elHunting\":false", "\"elHunting\":true"))
        assertFalse(hunting.ready)
        assertEquals("looking for peers at the head", hunting.notServingReason)
    }

    @Test
    fun `light-client hunting alone doesn't stop a synced chain serving`() {
        val s = MyotisChainStatus.decode(1L, synced.replace("\"lcHunting\":false", "\"lcHunting\":true"))
        assertTrue(s.ready)
    }

    @Test
    fun `paused or not yet synced is not ready and gives no not-serving reason`() {
        val paused = MyotisChainStatus.decode(
            1L,
            synced.replace("\"running\":true", "\"running\":false").replace("\"paused\":false", "\"paused\":true"),
        )
        assertFalse(paused.ready)
        assertEquals("", paused.notServingReason)
        val catching = MyotisChainStatus.decode(1L, synced.replace("SYNCED", "CATCHING_UP"))
        assertFalse(catching.ready)
        assertEquals("", catching.notServingReason)
    }

    @Test
    fun `stale anchor is recognised with its age and the bound`() {
        val s = MyotisChainStatus.decode(
            100L,
            synced.replace("SYNCED", "STALE_ANCHOR")
                .replace("\"peerCount\":7,", "\"peerCount\":7,\"currentPeriod\":3692,\"targetPeriod\":3701,\"wsBoundPeriods\":3,"),
        )
        assertTrue(s.staleAnchor)
        assertFalse(s.ready)
        assertEquals(3692L, s.currentPeriod)
        assertEquals(3701L, s.targetPeriod)
        assertEquals(3L, s.wsBoundPeriods)
    }
}
