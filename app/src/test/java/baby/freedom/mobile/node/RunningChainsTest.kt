package baby.freedom.mobile.node

import baby.freedom.swarm.MyotisNetwork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The light client's per-chain switches (#274), as `MainActivity` binds and relays them. */
class RunningChainsTest {

    @Test
    fun `unknown until the launch choice is read, then that choice`() {
        val chains = RunningChains()
        assertNull(chains.running.value)
        chains.launchWith(setOf(MyotisNetwork.Gnosis))
        assertEquals(setOf(MyotisNetwork.Gnosis), chains.running.value)
    }

    @Test
    fun `each switch starts or stops its own chain, in chain order`() {
        val chains = RunningChains()
        chains.launchWith(emptySet())
        assertEquals(emptySet<MyotisNetwork>(), chains.running.value)
        chains.set(MyotisNetwork.Gnosis, true)
        chains.set(MyotisNetwork.Mainnet, true)
        assertEquals(listOf(MyotisNetwork.Mainnet, MyotisNetwork.Gnosis), chains.running.value!!.toList())
        chains.set(MyotisNetwork.Mainnet, false)
        assertEquals(setOf(MyotisNetwork.Gnosis), chains.running.value)
        chains.set(MyotisNetwork.Gnosis, false)
        assertEquals(emptySet<MyotisNetwork>(), chains.running.value)
    }

    @Test
    fun `a switch flipped before the launch choice is read wins over it`() {
        val chains = RunningChains()
        chains.set(MyotisNetwork.Gnosis, true)
        chains.launchWith(setOf(MyotisNetwork.Mainnet))
        assertEquals(setOf(MyotisNetwork.Gnosis), chains.running.value)
    }
}
