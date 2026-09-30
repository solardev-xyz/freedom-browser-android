package baby.freedom.mobile.node

import baby.freedom.mobile.chains.BuiltInChains
import baby.freedom.mobile.chains.Chain
import baby.freedom.swarm.SwarmNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class SwarmModeTest {
    @Test
    fun `ultra-light takes no chain`() {
        assertEquals(SwarmNode.Mode.ULTRA_LIGHT, swarmModeFor(false, BuiltInChains.ALL))
        assertEquals("", swarmModeFor(false, BuiltInChains.ALL).gnosisRpc)
    }

    @Test
    fun `light reads Gnosis through the user's own RPC first, else its first public one`() {
        assertEquals("https://rpc.gnosischain.com", swarmModeFor(true, BuiltInChains.ALL).gnosisRpc)
        val mine = BuiltInChains.ALL.map {
            if (it.id == 100L) it.copy(userRpcUrls = listOf("https://my.gnosis.example/k")) else it
        }
        assertEquals("https://my.gnosis.example/k", swarmModeFor(true, mine).gnosisRpc)
        // No Gnosis in the list at all: the shipped one stands in.
        assertEquals("https://rpc.gnosischain.com", gnosisRpcFor(emptyList()))
        assertEquals(
            "https://rpc.gnosischain.com",
            gnosisRpcFor(listOf(BuiltInChains.GNOSIS.copy(rpcUrls = emptyList()))),
        )
    }

    @Test
    fun `the node's reads get Gnosis's whole pool, the user's RPCs included`() {
        val mine = BuiltInChains.GNOSIS.copy(userRpcUrls = listOf("https://my.gnosis.example/k"))
        assertEquals(mine, gnosisChainFor(BuiltInChains.ALL.map { if (it.id == 100L) mine else it }))
        // No Gnosis, or a Gnosis with no RPC at all: the shipped one stands in.
        assertEquals(BuiltInChains.GNOSIS, gnosisChainFor(emptyList()))
        assertEquals(BuiltInChains.GNOSIS.rpcUrls, gnosisChainFor(listOf(BuiltInChains.GNOSIS.copy(rpcUrls = emptyList()))).rpcUrls)
        // Only the user's own: kept as they are.
        val onlyMine = BuiltInChains.GNOSIS.copy(rpcUrls = emptyList(), userRpcUrls = listOf("https://my.gnosis.example/k"))
        assertEquals(onlyMine, gnosisChainFor(listOf(onlyMine)))
    }

    @Test
    fun `the boot key changes with the identity and the mode, not the address's case`() {
        val light = SwarmNode.Mode.light("https://rpc.example")
        val key = swarmBootKey("0xAbC", light)
        assertEquals(key, swarmBootKey("0xabc", light))
        assertNotEquals(key, swarmBootKey("0xabd", light))
        assertNotEquals(key, swarmBootKey("0xabc", SwarmNode.Mode.ULTRA_LIGHT))
        assertNotEquals(key, swarmBootKey("0xabc", SwarmNode.Mode.light("https://other.example")))
        assertNotEquals(swarmBootKey("", light), swarmBootKey("", SwarmNode.Mode.ULTRA_LIGHT))
    }

    @Test
    fun `a mode change restarts a running node once, like an identity change`() {
        val boot = SwarmBootIdentity()
        var restarts = 0
        val light = SwarmNode.Mode.light("https://rpc.example")
        boot.boot { swarmBootKey("0xabc", SwarmNode.Mode.ULTRA_LIGHT) to null }
        fun reload(mode: SwarmNode.Mode) = boot.restartIfStale({ swarmBootKey("0xabc", mode) }, { true }, { restarts++ })
        assertFalse(reload(SwarmNode.Mode.ULTRA_LIGHT))
        reload(light)
        // A second reload before the new launch has read: no second restart.
        assertFalse(reload(light))
        assertEquals(1, restarts)
        boot.boot { swarmBootKey("0xabc", light) to null }
        assertFalse(reload(light))
        assertEquals(1, restarts)
    }

    @Test
    fun `a chain store read error relays nothing new, so the user's Gnosis RPC isn't dropped`() = runBlocking {
        val mine = BuiltInChains.ALL.map {
            if (it.id == 100L) it.copy(userRpcUrls = listOf("https://my.gnosis.example/k")) else it
        }
        val relayed = swarmRelays(flowOf(true), flowOf(mine, null, mine)).toList()
        // One relay: the unreadable moment in between neither relays the
        // shipped RPCs nor repeats the same mode.
        assertEquals(1, relayed.size)
        assertEquals("https://my.gnosis.example/k", relayed.single().gnosisRpc)
        assertEquals(listOf("https://my.gnosis.example/k"), relayed.single().gnosis?.userRpcUrls)
        // Unreadable from the start: the mode alone, no Gnosis (`:node` keeps its own).
        assertEquals(
            listOf(SwarmRelay(true, null)),
            swarmRelays(flowOf(true), flowOf<List<Chain>?>(null)).toList(),
        )
        assertNull(SwarmRelay(true, null).gnosisRpc)
    }

    @Test
    fun `a light-mode toggle is relayed while the chain store can't be read`() = runBlocking {
        // #300 R3-M1: the toggle isn't held back until the store reads again.
        val mine = BuiltInChains.ALL.map {
            if (it.id == 100L) it.copy(userRpcUrls = listOf("https://my.gnosis.example/k")) else it
        }
        // The store reads, then errors for good; the user toggles Light on and off meanwhile.
        val chains = flow<List<Chain>?> { emit(mine); delay(20); emit(null); awaitCancellation() }
        val light = flow { emit(false); delay(100); emit(true); delay(100); emit(false) }
        val relayed = withTimeout(5_000) { swarmRelays(light, chains).take(3).toList() }
        assertEquals(listOf(false, true, false), relayed.map { it.light })
        // Still the user's RPC, the last readable list's, never the shipped one.
        assertEquals("https://my.gnosis.example/k", relayed[1].gnosisRpc)
        assertTrue(relayed.all { it.gnosis?.userRpcUrls == listOf("https://my.gnosis.example/k") })
        // Unreadable from the start: toggles still go out, with no Gnosis.
        val never = flow<List<Chain>?> { emit(null); awaitCancellation() }
        assertEquals(
            listOf(SwarmRelay(false, null), SwarmRelay(true, null)),
            withTimeout(5_000) { swarmRelays(flow { emit(false); delay(50); emit(true) }, never).take(2).toList() },
        )
    }
}
