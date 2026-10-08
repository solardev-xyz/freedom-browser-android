package baby.freedom.mobile.node

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IpfsConfigTest {
    @Test
    fun `a relayed config wins over the stale stored copy`() = runBlocking {
        // `:node`'s DataStore read "auto" once; the user has since picked "offline".
        var storedReads = 0
        val config = IpfsConfig.forStart(IpfsConfig.relayed(lowPower = true, routingMode = "offline")) {
            storedReads++
            IpfsConfig(lowPower = true, routingMode = "auto")
        }
        assertEquals(IpfsConfig(lowPower = true, routingMode = "offline"), config)
        assertEquals(0, storedReads)
    }

    @Test
    fun `low power is relayed too`() = runBlocking {
        val config = IpfsConfig.forStart(IpfsConfig.relayed(lowPower = false, routingMode = "auto")) {
            IpfsConfig(lowPower = true, routingMode = "auto")
        }
        assertEquals(false, config.lowPower)
    }

    @Test
    fun `before any relay the start reads the stored settings`() = runBlocking {
        val config = IpfsConfig.forStart(null) { IpfsConfig(lowPower = true, routingMode = "delegated") }
        assertEquals(IpfsConfig(lowPower = true, routingMode = "delegated"), config)
    }

    @Test
    fun `a relay without a routing mode is no relay`() {
        assertNull(IpfsConfig.relayed(lowPower = true, routingMode = null))
    }

    @Test
    fun `an unknown mode is passed on for the node wrapper to map`() {
        assertEquals("autoclient", IpfsConfig.relayed(lowPower = true, routingMode = "autoclient")?.routingMode)
    }
}
