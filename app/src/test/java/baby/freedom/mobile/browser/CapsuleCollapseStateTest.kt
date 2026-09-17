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
        y = s.scroll(y, 30f)
        assertTrue(s.collapsed)

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
        // Long read downwards…
        repeat(10) { y = s.scroll(y, 20f) }
        assertTrue(s.collapsed)
        // …then one flick up: the bar comes back immediately, it does
        // not have to undo 200 dp first.
        s.scroll(y, -14f)
        assertFalse(s.collapsed)
    }

    @Test
    fun `the top of the document always shows the whole bar`() {
        val s = CapsuleCollapseState()
        var y = px(400f)
        s.onScroll(y, 0, density)
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
    fun `expand forces the resting state and clears the accumulator`() {
        val s = CapsuleCollapseState()
        var y = px(400f)
        s.onScroll(y, 0, density)
        y = s.scroll(y, 30f)
        assertTrue(s.collapsed)

        s.expand()
        assertFalse(s.collapsed)
        // Travel accumulated before the reset must not count towards
        // the next collapse.
        s.scroll(y, 10f)
        assertFalse(s.collapsed)
    }

    @Test
    fun `a zero delta is ignored`() {
        val s = CapsuleCollapseState()
        val y = px(400f)
        s.onScroll(y, 0, density)
        s.onScroll(y, y, density)
        assertFalse(s.collapsed)
    }
}
