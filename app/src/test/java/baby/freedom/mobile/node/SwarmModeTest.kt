package baby.freedom.mobile.node

import baby.freedom.mobile.chains.BuiltInChains
import baby.freedom.swarm.SwarmNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

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
}
