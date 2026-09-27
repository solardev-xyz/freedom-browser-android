package baby.freedom.mobile.browser

import baby.freedom.mobile.ens.EnsResult
import baby.freedom.mobile.ens.EnsTrust
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The not-cross-checked warning's Continue and the warnings' details (#96). */
class EnsGateTest {

    @Test
    fun `a continue URL carries its gate's token and nothing else is one`() {
        val gate = EnsGate.create("swarm.eth", "bzz://aa", "ens://swarm.eth")
        val url = EnsGate.continueUrl(gate)
        assertEquals(gate.token, EnsGate.continueToken(url))
        assertNull(EnsGate.continueToken("ens://swarm.eth"))
        assertNull(EnsGate.continueToken("https://freedom-ens-continue.example/"))
        // Nothing a website could guess.
        assertEquals(32, gate.token.length)
        assertNotEquals(gate.token, EnsGate.create("swarm.eth", "bzz://aa", "ens://swarm.eth").token)
    }

    @Test
    fun `the unverified details give the whole answer, its server and block`() {
        val ref = "ab".repeat(32)
        val result = EnsResult.Ok(
            "swarm.eth", "bzz", "bzz://$ref", ref,
            trust = EnsTrust(verified = false, agreed = listOf("eth.drpc.org"), block = 21_000_000),
        )
        assertEquals(
            "Answer: bzz://$ref\nFrom: eth.drpc.org\nBlock: #21000000",
            EnsGate.unverifiedDetail(result),
        )
        val latest = result.copy(trust = EnsTrust(verified = false, agreed = listOf("1rpc.io")))
        assertTrue(EnsGate.unverifiedDetail(latest).endsWith("Block: latest"))
    }

    @Test
    fun `the conflict details list each answer with its servers`() {
        val conflict = EnsResult.Conflict(
            "swarm.eth",
            EnsResult.Conflict.Subject.RECORD,
            listOf(
                EnsResult.Conflict.Group("bzz://aa", listOf("a.test", "b.test")),
                EnsResult.Conflict.Group("no content (NO_RESOLVER)", listOf("c.test")),
            ),
            block = 7,
        )
        assertEquals(
            "Answers at block #7:\n\nbzz://aa\n  from a.test, b.test\n\nno content (NO_RESOLVER)\n  from c.test",
            EnsGate.conflictDetail(conflict),
        )
        val block = conflict.copy(subject = EnsResult.Conflict.Subject.BLOCK)
        assertTrue(EnsGate.conflictDetail(block).startsWith("Hashes reported for block #7:"))
    }
}
