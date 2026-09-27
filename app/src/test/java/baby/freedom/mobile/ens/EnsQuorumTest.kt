package baby.freedom.mobile.ens

import baby.freedom.mobile.ens.EnsQuorum.HashVote
import baby.freedom.mobile.ens.EnsQuorum.Leg
import baby.freedom.mobile.ens.EnsQuorum.WaveVote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure vote counting behind the RPC cross-check (#96). */
class EnsQuorumTest {

    // ---- anchor block number ----

    @Test
    fun `anchor sits the safety depth below the median head`() {
        assertEquals(992L, EnsQuorum.anchorNumber(listOf(1000, 1001, 999)))
        assertEquals(993L, EnsQuorum.anchorNumber(listOf(999, 1000, 1001, 1002)))
    }

    @Test
    fun `one server lying about its head can't move the anchor`() {
        // Far in the past (to pin stale state) or the future (to make
        // the block unavailable to everyone else): the median ignores it.
        assertEquals(992L, EnsQuorum.anchorNumber(listOf(1000, 1000, 1)))
        assertEquals(992L, EnsQuorum.anchorNumber(listOf(1000, 1000, 9_999_999)))
        assertEquals(992L, EnsQuorum.anchorNumber(listOf(1000, 999, 1001, 5, 1000)))
    }

    @Test
    fun `fewer than three heads is no anchor`() {
        assertNull(EnsQuorum.anchorNumber(emptyList()))
        assertNull(EnsQuorum.anchorNumber(listOf(1000, 1000)))
    }

    @Test
    fun `the anchor never goes below genesis`() {
        assertEquals(0L, EnsQuorum.anchorNumber(listOf(3, 4, 5)))
    }

    // ---- anchor block hash ----

    @Test
    fun `a hash with a majority of everyone asked is decided before the rest answer`() {
        val vote = EnsQuorum.hashVote(mapOf("a" to "0xAA", "b" to "0xaa"), asked = 3, settled = false)
        assertEquals(HashVote.Agreed("0xaa", listOf("a", "b")), vote)
    }

    @Test
    fun `two of five is not yet decided`() {
        // Three more to come could still outvote them.
        assertNull(EnsQuorum.hashVote(mapOf("a" to "0xaa", "b" to "0xaa"), asked = 5, settled = false))
    }

    @Test
    fun `two colluding servers don't win against a larger honest group`() {
        // Five asked, all answered: the two liars were first but
        // the honest three are the majority.
        val answers = linkedMapOf("liar1" to "0xbad", "liar2" to "0xbad", "h1" to "0xaa", "h2" to "0xaa", "h3" to "0xaa")
        assertEquals(
            HashVote.Agreed("0xaa", listOf("h1", "h2", "h3")),
            EnsQuorum.hashVote(answers, asked = 5, settled = true),
        )
    }

    @Test
    fun `a plurality without a majority is a disagreement`() {
        val answers = linkedMapOf("a" to "0x1", "b" to "0x1", "c" to "0x2", "d" to "0x3", "e" to "0x4")
        val vote = EnsQuorum.hashVote(answers, asked = 5, settled = true)
        assertTrue("got $vote", vote is HashVote.Disagreed)
        assertEquals(4, (vote as HashVote.Disagreed).byHash.size)
    }

    @Test
    fun `a tie is a disagreement`() {
        val answers = linkedMapOf("a" to "0x1", "b" to "0x2")
        assertTrue(EnsQuorum.hashVote(answers, asked = 3, settled = true) is HashVote.Disagreed)
    }

    @Test
    fun `the majority counts servers that answered once all are in`() {
        // Four asked, one never answered: two of three is a majority.
        val answers = linkedMapOf("a" to "0x1", "b" to "0x1", "c" to "0x2")
        assertEquals(
            HashVote.Agreed("0x1", listOf("a", "b")),
            EnsQuorum.hashVote(answers, asked = 4, settled = true),
        )
    }

    @Test
    fun `one hash from one server is too little to decide, not a disagreement`() {
        assertEquals(HashVote.Insufficient, EnsQuorum.hashVote(mapOf("a" to "0x1"), asked = 3, settled = true))
        assertEquals(HashVote.Insufficient, EnsQuorum.hashVote(emptyMap(), asked = 3, settled = true))
    }

    // ---- the record wave ----

    private fun legs(vararg pairs: Pair<String, Leg>) = linkedMapOf(*pairs)

    private fun answer(key: String) = Leg.Answer(key)

    private val failed = Leg.Failed()

    @Test
    fun `two identical answers are verified`() {
        val vote = EnsQuorum.waveVote(legs("a" to answer("x"), "b" to answer("x")))
        assertEquals(WaveVote.Agreed("x", listOf("a", "b"), emptyList()), vote)
        assertTrue(EnsQuorum.waveDecided(legs("a" to answer("x"), "b" to answer("x"))))
    }

    @Test
    fun `a lone dissenter is outvoted and named`() {
        val vote = EnsQuorum.waveVote(legs("a" to answer("x"), "liar" to answer("y"), "b" to answer("x")))
        assertEquals(WaveVote.Agreed("x", listOf("a", "b"), listOf("liar")), vote)
    }

    @Test
    fun `one answer is unverified however many others failed`() {
        val vote = EnsQuorum.waveVote(legs("a" to answer("x"), "b" to failed, "c" to failed))
        assertEquals(WaveVote.Unverified("x", listOf("a")), vote)
        assertFalse(EnsQuorum.waveDecided(legs("a" to answer("x"), "b" to failed)))
    }

    @Test
    fun `two different answers are a conflict`() {
        val vote = EnsQuorum.waveVote(legs("a" to answer("x"), "b" to answer("y"), "c" to failed))
        assertEquals(WaveVote.Conflict(mapOf("x" to listOf("a"), "y" to listOf("b"))), vote)
    }

    @Test
    fun `a tie at the quorum is a conflict, not a coin toss`() {
        val vote = EnsQuorum.waveVote(
            legs("a" to answer("x"), "b" to answer("x"), "c" to answer("y"), "d" to answer("y")),
        )
        assertTrue("got $vote", vote is WaveVote.Conflict)
    }

    @Test
    fun `a revert is an answer like any other`() {
        // A server can't turn a real record into "no resolver" alone.
        val vote = EnsQuorum.waveVote(legs("a" to answer("revert:0x77209fe8"), "b" to answer("0xdata")))
        assertTrue("got $vote", vote is WaveVote.Conflict)
    }

    @Test
    fun `no answers is all failed, gateway-only failures flagged`() {
        assertEquals(WaveVote.AllFailed(ccip = false), EnsQuorum.waveVote(legs("a" to failed, "b" to Leg.Failed(ccip = true))))
        assertEquals(
            WaveVote.AllFailed(ccip = true),
            EnsQuorum.waveVote(legs("a" to Leg.Failed(ccip = true), "b" to Leg.Failed(ccip = true))),
        )
    }

    @Test
    fun `only a wave with no verdict is widened`() {
        assertTrue(EnsQuorum.worthWidening(WaveVote.AllFailed(ccip = false), asked = 3))
        assertTrue(EnsQuorum.worthWidening(WaveVote.Unverified("x", listOf("a")), asked = 3))
        // Every server would ask the same broken gateway.
        assertFalse(EnsQuorum.worthWidening(WaveVote.AllFailed(ccip = true), asked = 3))
        assertFalse(EnsQuorum.worthWidening(WaveVote.Agreed("x", listOf("a", "b"), emptyList()), asked = 3))
        // Every server asked answered and they disagreed: that's the verdict.
        val split = WaveVote.Conflict(mapOf("x" to listOf("a"), "y" to listOf("b"), "z" to listOf("c")))
        assertFalse(EnsQuorum.worthWidening(split, asked = 3))
    }

    @Test
    fun `a tie beside a server that gave no vote is widened`() {
        // Liar says x, honest says y, the third timed out: the rest of the
        // pool can still break the tie.
        val tie = WaveVote.Conflict(mapOf("x" to listOf("a"), "y" to listOf("b")))
        assertTrue(EnsQuorum.worthWidening(tie, asked = 3))
        assertFalse(EnsQuorum.worthWidening(tie, asked = 2))
    }
}
