package baby.freedom.mobile.browser

import baby.freedom.mobile.browser.JsDialogGate.Admit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #466: a page looping `alert()` can't lock the user out of the browser. */
class JsDialogGateTest {
    private var now = 1_000_000L
    private val gate = JsDialogGate { now }

    /** The page asks, the user takes [answerMs] to answer, and the page asks again after [gapMs]. */
    private fun shown(answerMs: Long = 800, gapMs: Long = 0): Admit {
        val admit = gate.admit()
        if (admit is Admit.Show) {
            now += answerMs
            gate.settled()
        }
        now += gapMs
        return admit
    }

    @Test
    fun theFirstDialogDoesNotOfferTheBlock() {
        assertEquals(Admit.Show(offerBlock = false), gate.admit())
    }

    @Test
    fun anAlertLoopOffersTheBlockFromTheSecondDialog() {
        assertEquals(Admit.Show(offerBlock = false), shown())
        assertEquals(Admit.Show(offerBlock = true), shown())
        assertEquals(Admit.Show(offerBlock = true), shown())
    }

    @Test
    fun aDialogLongAfterTheLastIsPlain() {
        shown(gapMs = JsDialogGate.REPEAT_WINDOW_MS)
        assertEquals(Admit.Show(offerBlock = false), gate.admit())
    }

    @Test
    fun blockingRefusesEveryLaterDialogUntilTheUserNavigates() {
        shown()
        gate.admit()
        gate.block()
        repeat(100) {
            now += 10_000
            assertEquals(Admit.Refuse, gate.admit())
        }
        assertTrue(gate.blocked)

        gate.allow()
        // The looping page is still on screen until the user's load commits.
        assertTrue(gate.blocked)
        assertEquals(Admit.Refuse, gate.admit())
        gate.committed()
        assertFalse(gate.blocked)
        // A fresh start: the first dialog after it is a plain one.
        assertEquals(Admit.Show(offerBlock = false), gate.admit())
    }

    @Test
    fun aCommitTheUserDidNotStartKeepsTheBlock() {
        gate.block()
        gate.committed()
        assertTrue(gate.blocked)
        // Only the commit after the user's own navigation lifts it, once.
        gate.allow()
        gate.committed()
        assertFalse(gate.blocked)
        gate.block()
        gate.committed()
        assertTrue(gate.blocked)
    }

    @Test
    fun aFloodBlocksWithoutAsking() {
        // Dialogs answered for the page as fast as it asks (a tab out of
        // view): no user could answer these.
        repeat(JsDialogGate.FLOOD_COUNT - 1) {
            assert(gate.admit() is Admit.Show)
            gate.settled()
            now += 50
        }
        assertEquals(Admit.Refuse, gate.admit())
        assertTrue(gate.blocked)
        now += 60_000
        assertEquals(Admit.Refuse, gate.admit())
    }

    @Test
    fun aUserAnsweringAtTheirPaceIsNeverCountedAsAFlood() {
        // The fastest a user can answer: buttons wait PROTECTION_MS.
        repeat(200) {
            assert(shown(answerMs = PromptTapGuard.PROTECTION_MS) is Admit.Show)
        }
        assertFalse(gate.blocked)
    }

    @Test
    fun aClockGoingBackDoesNotOfferTheBlockOnAStaleAnswer() {
        shown()
        now -= 60_000
        assertEquals(Admit.Show(offerBlock = false), gate.admit())
    }
}
