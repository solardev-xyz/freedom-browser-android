package baby.freedom.mobile.chains.rpc

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Colibri proofs switch as the router reads it (#329). */
class ColibriReadsTest {
    @After
    fun tearDown() = ColibriReads.reset()

    @Test
    fun offUntilKnownAndARelayWinsOverTheStartTimeRead() {
        ColibriReads.reset()
        // Unknown: nothing goes to the prover.
        assertFalse(ColibriReads.enabled)
        // `:node` relayed "off" before its own read of the stored "on" landed.
        ColibriReads.set(false)
        ColibriReads.seed(true)
        assertFalse(ColibriReads.enabled)
        ColibriReads.set(true)
        assertTrue(ColibriReads.enabled)
        ColibriReads.reset()
        ColibriReads.seed(true)
        assertTrue(ColibriReads.enabled)
    }
}
