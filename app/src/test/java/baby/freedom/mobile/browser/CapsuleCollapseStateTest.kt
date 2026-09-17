package baby.freedom.mobile.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scroll → compact-state machine behind the floating capsule's
 * compact-on-scroll behaviour (#30).
 *
 * The contract the UI depends on: scrolling down collapses the capsule
 * after a short run, scrolling up restores it after a shorter one, the
 * top of a document always shows the whole bar, and nothing but a real
 * gesture may move the state.
 */
class CapsuleCollapseStateTest {

    /** Emulator/phone density (420 dpi ⇒ 2.625 px per dp). */
    private val density = 2.625f

    private fun px(dp: Float) = (dp * density).toInt()

    /**
     * A finger goes down and drags past the touch slop — what
     * [BrowserWebViewHost]'s touch listener reports for a real scroll
     * gesture, and what arms the state machine.
     */
    private fun CapsuleCollapseState.fingerDrags() {
        onTouchDown()
        onDragPastSlop()
    }

    /** A finger goes down and lifts again without dragging: a tap. */
    private fun CapsuleCollapseState.fingerTaps() {
        onTouchDown()
    }

    /** Feed one scroll step, returning the new absolute scrollY. */
    private fun CapsuleCollapseState.scroll(from: Int, byDp: Float): Int {
        val to = from + px(byDp)
        onScroll(to, from, density)
        return to
    }

    @Test
    fun `starts at rest`() {
        assertFalse(CapsuleCollapseState().collapsed)
    }

    @Test
    fun `scrolling down past the threshold collapses`() {
        val s = CapsuleCollapseState()
        var y = px(400f)
        // A first delta out of the top zone, then a 30 dp run.
        s.onScroll(y, 0, density)
        assertFalse("a jump into the page is not a gesture", s.collapsed)
        s.fingerDrags()
        y = s.scroll(y, 10f)
        assertFalse("10 dp is below the threshold", s.collapsed)
        s.scroll(y, 20f)
        assertTrue("30 dp of downward travel collapses", s.collapsed)
    }

    @Test
    fun `scrolling back up restores`() {
        val s = CapsuleCollapseState()
        var y = px(400f)
        s.onScroll(y, 0, density)
        s.fingerDrags()
        y = s.scroll(y, 30f)
        assertTrue(s.collapsed)

        s.fingerDrags()
        y = s.scroll(y, -6f)
        assertTrue("a 6 dp nudge up is below the restore threshold", s.collapsed)
        s.scroll(y, -10f)
        assertFalse("16 dp of upward travel restores", s.collapsed)
    }

    @Test
    fun `a reversal starts a fresh run rather than repaying the old one`() {
        val s = CapsuleCollapseState()
        var y = px(600f)
        s.onScroll(y, 0, density)
        s.fingerDrags()
        // Long read downwards…
        repeat(10) { y = s.scroll(y, 20f) }
        assertTrue(s.collapsed)
        // …then one flick up: the bar comes back immediately, it does
        // not have to undo 200 dp first.
        s.fingerDrags()
        s.scroll(y, -14f)
        assertFalse(s.collapsed)
    }

    @Test
    fun `the top of the document always shows the whole bar`() {
        val s = CapsuleCollapseState()
        var y = px(400f)
        s.onScroll(y, 0, density)
        s.fingerDrags()
        y = s.scroll(y, 30f)
        assertTrue(s.collapsed)
        // Scrolled back to the very top (in one gesture-sized step is
        // impossible, so this is the jump path plus a final small step).
        s.onScroll(px(10f), px(20f), density)
        assertFalse("within the top zone the capsule is at rest", s.collapsed)
    }

    @Test
    fun `a programmatic jump does not change the state`() {
        val s = CapsuleCollapseState()
        var y = px(400f)
        s.onScroll(y, 0, density)
        s.fingerDrags()
        // e.g. an anchor link or a restored scroll position
        y = s.scroll(y, 4000f)
        assertFalse("a 4000 dp jump is not a flick", s.collapsed)
        // …and it doesn't leave the accumulator poisoned either: a
        // normal run afterwards still behaves normally.
        y = s.scroll(y, 10f)
        assertFalse(s.collapsed)
        s.scroll(y, 20f)
        assertTrue(s.collapsed)
    }

    @Test
    fun `an animated scroll nobody asked for does not change the state`() {
        val s = CapsuleCollapseState()
        var y = px(400f)
        s.onScroll(y, 0, density)
        // No finger has touched the page: whatever is scrolling it —
        // a JS animation, a focus scroll — is not a gesture, however
        // small its steps are.
        repeat(20) { y = s.scroll(y, 8f) }
        assertFalse("160 dp of unrequested travel leaves the bar alone", s.collapsed)
    }

    @Test
    fun `a tap on a form field cannot collapse the bar behind the keyboard`() {
        val s = CapsuleCollapseState()
        var y = px(400f)
        s.onScroll(y, 0, density)
        // The user reads down the page first, then flicks back up so
        // the bar is whole again…
        s.fingerDrags()
        y = s.scroll(y, 30f)
        assertTrue(s.collapsed)
        s.fingerDrags()
        y = s.scroll(y, -20f)
        assertFalse(s.collapsed)

        // …and taps a field halfway down it. Chromium's focus scroll
        // and #25's scroll-into-view re-scroll both arrive as runs of
        // small deltas while the keyboard is coming up, where
        // [BrowserScreen] holds the capsule open — a collapse here
        // would only become visible once the IME closed again.
        s.fingerTaps()
        repeat(10) { y = s.scroll(y, 12f) }
        assertFalse("the capsule survives the keyboard round trip", s.collapsed)

        // The very next real drag still works.
        s.fingerDrags()
        s.scroll(y, 30f)
        assertTrue(s.collapsed)
    }

    @Test
    fun `a fling keeps scrolling after the finger lifts`() {
        val s = CapsuleCollapseState()
        var y = px(400f)
        s.onScroll(y, 0, density)
        // The drag itself is under the threshold; the fling it throws
        // carries the rest, and it is still the user's gesture.
        s.fingerDrags()
        y = s.scroll(y, 8f)
        assertFalse(s.collapsed)
        repeat(4) { y = s.scroll(y, 6f) }
        assertTrue("the deltas after lift-off still count", s.collapsed)
    }

    @Test
    fun `expand forces the resting state and clears the accumulator`() {
        val s = CapsuleCollapseState()
        var y = px(400f)
        s.onScroll(y, 0, density)
        s.fingerDrags()
        y = s.scroll(y, 30f)
        assertTrue(s.collapsed)

        s.expand()
        assertFalse(s.collapsed)
        // Travel accumulated before the reset must not count towards
        // the next collapse.
        s.fingerDrags()
        s.scroll(y, 10f)
        assertFalse(s.collapsed)
    }

    @Test
    fun `a new document does not inherit the previous one's gesture`() {
        val s = CapsuleCollapseState()
        var y = px(400f)
        s.onScroll(y, 0, density)
        s.fingerDrags()
        y = s.scroll(y, 10f)
        // Navigation (a link tap mid-fling, a redirect) re-opens the
        // bar; the new page's own scroll restore must not collapse it.
        s.expand()
        repeat(10) { y = s.scroll(y, 12f) }
        assertFalse("the new document starts unarmed", s.collapsed)
    }

    @Test
    fun `a zero delta is ignored`() {
        val s = CapsuleCollapseState()
        val y = px(400f)
        s.onScroll(y, 0, density)
        s.fingerDrags()
        s.onScroll(y, y, density)
        assertFalse(s.collapsed)
    }
}
