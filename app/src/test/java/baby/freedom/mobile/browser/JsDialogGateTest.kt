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

        gate.committed(byUser = true)
        assertFalse(gate.blocked)
        // A fresh start: the first dialog after it is a plain one.
        assertEquals(Admit.Show(offerBlock = false), gate.admit())
    }

    @Test
    fun aCommitTheUserDidNotStartKeepsTheBlock() {
        gate.block()
        gate.committed(byUser = false)
        assertTrue(gate.blocked)
        assertEquals(Admit.Refuse, gate.admit())
        gate.committed(byUser = true)
        assertFalse(gate.blocked)
    }

    // R1-F1: a link the user taps on the blocked page lifts the block
    // when it lands, as their address does.
    @Test
    fun aLinkTheUserTappedLiftsTheBlockWhenItCommits() {
        val chain = UserNamedChain()
        gate.block()
        chain.started("https://loop.example/next")
        chain.redirected("https://other.example/")
        assertTrue(usersOwnCommit(usersOwn = true, chain, "https://other.example/"))
        gate.committed(usersOwnCommit(usersOwn = true, chain, "https://other.example/"))
        assertFalse(gate.blocked)
    }

    @Test
    fun aCommitIsTheUsersOnlyAtTheAddressTheirNavigationIsAwaitedAt() {
        val chain = UserNamedChain()
        chain.started("https://other.example/")
        // Not the user's (a restore, a detour, a tapless page navigation).
        assertFalse(usersOwnCommit(usersOwn = false, chain, "https://other.example/"))
        // The page's own navigation elsewhere, before theirs landed.
        assertFalse(usersOwnCommit(usersOwn = true, chain, "https://loop.example/"))
        assertFalse(usersOwnCommit(usersOwn = true, chain, null))
        assertTrue(usersOwnCommit(usersOwn = true, chain, "https://other.example"))
    }

    // R1-M1: the user's navigation never commits — a 204, Stop, a
    // download — and the looping page's own reload commits later.
    @Test
    fun aUsersNavigationThatNeverCommitsLeavesNothingForThePagesNextCommit() {
        val chain = UserNamedChain()
        gate.block()
        chain.started("https://loop.example/")
        // The 204's finish: loading stopped with the old page on screen.
        chain.loadFinished("https://loop.example/", committedUrl = "https://loop.example/")
        gate.committed(usersOwnCommit(usersOwn = true, chain, "https://loop.example/"))
        assertTrue(gate.blocked)

        // Stop (navigationDidNotLeave) or a download ends it the same way.
        chain.started("https://other.example/file.zip")
        chain.ended()
        gate.committed(usersOwnCommit(usersOwn = true, chain, "https://other.example/file.zip"))
        assertTrue(gate.blocked)

        // The page's own request for another address ends it too.
        chain.started("https://other.example/")
        chain.mainFrameRequested("https://loop.example/?again")
        gate.committed(usersOwnCommit(usersOwn = true, chain, "https://loop.example/?again"))
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
