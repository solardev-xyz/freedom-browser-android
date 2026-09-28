package baby.freedom.mobile.chains.rpc

import baby.freedom.mobile.chains.rpc.ChainSource.COLIBRI
import baby.freedom.mobile.chains.rpc.ChainSource.DIRECT
import baby.freedom.mobile.chains.rpc.ChainSource.MYOTIS
import baby.freedom.mobile.chains.rpc.ChainSource.QUORUM
import org.junit.Assert.assertEquals
import org.junit.Test

class ChainAccessPolicyTest {
    @Test
    fun desktopDefaults() {
        for (id in listOf(1L, 100L)) {
            val p = ChainAccessPolicy.default(id)
            assertEquals(listOf(MYOTIS, COLIBRI, QUORUM, DIRECT), p.readOrder)
            assertEquals(listOf(MYOTIS, DIRECT), p.broadcastOrder)
            assertEquals(Triple(3, 2, 5_000L), Triple(p.quorumK, p.quorumM, p.timeoutMs))
        }
        val base = ChainAccessPolicy.default(8453)
        assertEquals(listOf(QUORUM, DIRECT), base.readOrder)
        assertEquals(listOf(DIRECT), base.broadcastOrder)
    }

    @Test
    fun sanitizingNeverLeavesAChainUnreadable() {
        val p = ChainAccessPolicy(
            readOrder = listOf(MYOTIS, QUORUM, QUORUM, COLIBRI),
            broadcastOrder = listOf(QUORUM, COLIBRI),
            quorumK = 0,
            quorumM = 5,
            timeoutMs = 10,
        ).sanitized(137)
        assertEquals("no light client off Ethereum/Gnosis, no repeats", listOf(QUORUM), p.readOrder)
        assertEquals("neither can broadcast", listOf(DIRECT), p.broadcastOrder)
        assertEquals(1 to 1, p.quorumK to p.quorumM)
        assertEquals(500L, p.timeoutMs)
        assertEquals(listOf(DIRECT), ChainAccessPolicy(emptyList(), emptyList()).sanitized(1).readOrder)
    }
}
