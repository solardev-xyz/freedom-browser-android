package baby.freedom.mobile.browser

import baby.freedom.mobile.ens.EnsResult
import baby.freedom.mobile.ens.EnsTrust
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `a not-found says when only one server gave it`() {
        assertEquals("NO_RESOLVER", EnsGate.withTrustNote("NO_RESOLVER", EnsTrust.ASSUMED))
        assertEquals(
            "NO_RESOLVER\nNot cross-checked: only eth.drpc.org answered (block #7)",
            EnsGate.withTrustNote(
                "NO_RESOLVER",
                EnsTrust(verified = false, agreed = listOf("eth.drpc.org"), block = 7),
            ),
        )
    }

    @Test
    fun `continue goes through with the tab's token, re-runs a stale warning, drops the rest`() {
        val gate = EnsGate.create("swarm.eth", "bzz://aa", "ens://swarm.eth/p")
        val warning = ErrorPage.url(
            errorCode = "ens_unverified",
            displayUrl = "swarm.eth/p",
            protocol = "ens",
            retryUrl = "ens://swarm.eth/p",
            continueUrl = EnsGate.continueUrl(gate),
        )
        val live = EnsGate.continueUrl(gate)
        assertEquals(live, EnsGate.continueDestination(live, gate, warning))
        // Back to an old warning, or after the tab was restored: its
        // token is spent, so the navigation runs again (unapproved).
        val stale = "freedom-ens-continue:" + "0".repeat(32)
        assertEquals("ens://swarm.eth/p", EnsGate.continueDestination(stale, gate, warning))
        assertEquals("ens://swarm.eth/p", EnsGate.continueDestination(stale, null, warning))
        // A website's own link to a continue URL goes nowhere.
        assertNull(EnsGate.continueDestination(stale, gate, "https://evil.example/"))
        assertNull(EnsGate.continueDestination(stale, null, null))
        // Nor does another error page's.
        val other = ErrorPage.url("ens_not_found", "swarm.eth", retryUrl = "ens://swarm.eth")
        assertNull(EnsGate.continueDestination(stale, gate, other))
        // Not a continue URL at all.
        assertNull(EnsGate.continueDestination("ens://swarm.eth", gate, warning))
    }

    @Test
    fun `with too few endpoints enabled the warning says so`() {
        val ref = "ab".repeat(32)
        val lone = EnsTrust(verified = false, agreed = listOf("my.node"), tooFewServers = true)
        val ok = EnsResult.Ok("swarm.eth", "bzz", "bzz://$ref", ref, lone)
        assertTrue(EnsGate.unverifiedDetail(ok).contains("Settings → Name resolution → RPC providers"))
        assertTrue(EnsGate.withTrustNote("NO_RESOLVER", lone).contains("Settings → Name resolution → RPC providers"))
        // Too few *answering* isn't the user's setting to fix.
        val unlucky = lone.copy(tooFewServers = false)
        assertFalse(EnsGate.unverifiedDetail(ok.copy(trust = unlucky)).contains("Settings"))
    }

    @Test
    fun `the settings hint shows below three enabled endpoints`() {
        assertTrue(tooFewEndpointsHint(1)!!.contains("Only one endpoint"))
        assertTrue(tooFewEndpointsHint(2)!!.contains("aren't cross-checked"))
        assertNull(tooFewEndpointsHint(3))
        assertNull(tooFewEndpointsHint(8))
    }

    @Test
    fun `with Colibri on the settings hint only warns about answers it can't prove`() {
        // #100: a proven answer loads without asking, however few endpoints.
        for (hint in listOf(tooFewEndpointsHint(1, colibri = true)!!, tooFewEndpointsHint(2, colibri = true)!!, tooFewEndpointsHint(2, 4, colibri = true)!!)) {
            assertTrue(hint, hint.contains("an answer Colibri can't prove"))
            assertTrue(hint, hint.contains("you'll be asked before that name loads"))
            assertFalse(hint, hint.contains("each name"))
        }
        assertNull(tooFewEndpointsHint(3, colibri = true))
    }
}
