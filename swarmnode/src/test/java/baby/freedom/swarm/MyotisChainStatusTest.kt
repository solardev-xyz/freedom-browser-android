package baby.freedom.swarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    private fun withAdvisory(advisory: String) = synced.replace("\"elHunting\":false}", "\"elHunting\":false,\"upgradeAdvisory\":$advisory}")

    private val upstream = """{"phase":"SCHEDULED","activationTime":1791294816,"forkId":"0x6C1D9423","observedPeers":4}"""

    @Test
    fun `decodes the fork watch's upgrade advisory as upstream prints it`() {
        val s = MyotisChainStatus.decode(1L, withAdvisory(upstream))
        assertEquals(MyotisUpgradeAdvisory("SCHEDULED", 1_791_294_816L, "0x6c1d9423", 4), s.upgradeAdvisory)
        // Display only: it never touches readiness.
        assertTrue(s.ready)
        val active = MyotisChainStatus.decode(
            100L,
            withAdvisory("""{"phase":"ACTIVE","activationTime":0,"forkId":"0x00000000","observedPeers":3}"""),
        ).upgradeAdvisory!!
        assertTrue(active.active)
        assertEquals(0L, active.activationTime)
    }

    @Test
    fun `no advisory when absent or null`() {
        assertNull(MyotisChainStatus.decode(1L, synced).upgradeAdvisory)
        assertNull(MyotisChainStatus.decode(1L, withAdvisory("null")).upgradeAdvisory)
        assertNull(MyotisChainStatus.decode(1L, "{}").upgradeAdvisory)
    }

    @Test
    fun `any field off upstream's shape drops the whole advisory`() {
        val bad = listOf(
            "[]", "\"ACTIVE\"", "1", "{}",
            upstream.replace("SCHEDULED", "PENDING"),
            upstream.replace("SCHEDULED", "scheduled"),
            upstream.replace("\"phase\":\"SCHEDULED\",", ""),
            upstream.replace("1791294816", "\"1791294816\""),
            upstream.replace("1791294816", "1791294816.5"),
            upstream.replace("1791294816", "-1"),
            upstream.replace("1791294816", "253402300800"),
            upstream.replace("\"activationTime\":1791294816,", ""),
            upstream.replace("0x6C1D9423", "6C1D9423"),
            upstream.replace("0x6C1D9423", "0x6C1D942"),
            upstream.replace("0x6C1D9423", "0x6C1D9423a"),
            upstream.replace("0x6C1D9423", "0x6C1D942g"),
            upstream.replace("\"0x6C1D9423\"", "1813877795"),
            upstream.replace("\"observedPeers\":4", "\"observedPeers\":0"),
            upstream.replace("\"observedPeers\":4", "\"observedPeers\":100001"),
            upstream.replace("\"observedPeers\":4", "\"observedPeers\":4.0"),
            upstream.replace("\"observedPeers\":4", "\"observedPeers\":\"4\""),
            upstream.replace(",\"observedPeers\":4", ""),
        )
        for (advisory in bad) {
            val s = MyotisChainStatus.decode(1L, withAdvisory(advisory))
            assertNull(advisory, s.upgradeAdvisory)
            // The rest of the status still decodes.
            assertTrue(advisory, s.ready)
        }
        // The edges themselves are in range.
        val edges = upstream.replace("1791294816", "253402300799").replace("\"observedPeers\":4", "\"observedPeers\":100000")
        assertEquals(100_000, MyotisChainStatus.decode(1L, withAdvisory(edges)).upgradeAdvisory?.observedPeers)
    }
}
