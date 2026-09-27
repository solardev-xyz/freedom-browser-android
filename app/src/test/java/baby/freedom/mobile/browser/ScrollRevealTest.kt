package baby.freedom.mobile.browser

import baby.freedom.mobile.browser.BottomChromeMode.Overlay
import baby.freedom.mobile.browser.BottomChromeMode.Reserved
import baby.freedom.mobile.browser.BottomChromeMode.Revealed
import baby.freedom.mobile.browser.ScrollRevealSlot.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import android.view.MotionEvent
import org.junit.Test

class ScrollRevealTest {

    private val h = 215f // 82 dp at 2.625 px/dp
    private val slop = 21f

    /** A slot armed at (500, 1700), overscroll reported. */
    private fun armed(): ScrollRevealSlot = ScrollRevealSlot().apply {
        assertTrue(onDown(500f, 1700f, atEnd = true, allowed = true))
        onBottomOverscroll()
    }

    /** A slot that owns the gesture (dragging), started at y = 1700 − 30. */
    private fun dragging(): ScrollRevealSlot = armed().apply {
        assertTrue(onMove(500f, 1670f, slop))
        assertEquals(Phase.Dragging, phase)
    }

    // --- trigger ----------------------------------------------------------

    @Test
    fun `a push at the end, past the slop, with overscroll, takes the gesture`() {
        val s = armed()
        assertFalse("inside the slop", s.onMove(500f, 1690f, slop))
        assertTrue(s.onMove(500f, 1670f, slop))
        assertEquals(Phase.Dragging, s.phase)
        // Only once: later moves don't "start" again.
        assertFalse(s.onMove(500f, 1600f, slop))
    }

    @Test
    fun `a fling that lands at the end doesn't reveal`() {
        val s = ScrollRevealSlot()
        assertFalse(s.onDown(500f, 1700f, atEnd = false, allowed = true))
        assertEquals(Phase.Tracking, s.phase)
        s.onMove(500f, 1400f, slop)
        // The finger lifts mid-page; the fling carries the page to its
        // end and Chromium overscrolls with no finger down.
        s.onRelease(0f, h)
        assertEquals(Phase.Idle, s.phase)
        assertFalse(s.onBottomOverscroll())
        assertFalse(s.onMove(500f, 1300f, slop))
        assertEquals(Phase.Idle, s.phase)
        // The next touch goes down at the end: that one is a push.
        assertTrue(s.onDown(500f, 1700f, atEnd = true, allowed = true))
        s.onBottomOverscroll()
        assertTrue(s.onMove(500f, 1670f, slop))
    }

    // --- mid-drag arming (#138) ---------------------------------------------

    @Test
    fun `a drag from mid-page that reaches the end and keeps going takes over`() {
        val s = ScrollRevealSlot()
        assertFalse(s.onDown(500f, 1700f, atEnd = false, allowed = true))
        for (y in 1650 downTo 1200 step 50) assertFalse(s.onMove(500f, y.toFloat(), slop))
        // The page runs out at y = 1200: Chromium's first unconsumed
        // bottom overscroll, finger still down. Arms (sample now).
        assertTrue(s.onBottomOverscroll())
        assertEquals(Phase.Armed, s.phase)
        // A second report doesn't re-arm or re-sample.
        assertFalse(s.onBottomOverscroll())
        // The next move up takes over — no second slop to cross.
        assertTrue(s.onMove(500f, 1190f, slop))
        assertEquals(Phase.Dragging, s.phase)
        // Anchored where the finger was when the page ran out: the
        // offset picks up from 0 there, following the finger.
        assertEquals(rubberBand(10f, h), s.dragOffset(1190f, h), 0.001f)
        assertEquals(0f, s.dragOffset(1200f, h), 0f)
        // …and commits like any push.
        assertEquals(Phase.Committing, s.onRelease(0.5f * h, h))
    }

    @Test
    fun `a drag armed mid-page that turns back down goes back to tracking`() {
        val s = ScrollRevealSlot()
        s.onDown(500f, 1700f, atEnd = false, allowed = true)
        s.onMove(500f, 1300f, slop)
        assertTrue(s.onBottomOverscroll())
        assertFalse(s.onMove(500f, 1350f, slop))
        assertEquals(Phase.Tracking, s.phase)
        // Up again, not at the end yet (no overscroll): the page scrolls.
        assertFalse(s.onMove(500f, 1320f, slop))
        // Reaches the end again: arms again, anchored there.
        assertTrue(s.onBottomOverscroll())
        assertTrue(s.onMove(500f, 1300f, slop))
        assertEquals(rubberBand(20f, h), s.dragOffset(1300f, h), 0.001f)
    }

    @Test
    fun `a drag from mid-page the page consumes (no overscroll) stays with the page`() {
        val s = ScrollRevealSlot()
        s.onDown(500f, 1700f, atEnd = false, allowed = true)
        for (y in 1650 downTo 900 step 50) assertFalse(s.onMove(500f, y.toFloat(), slop))
        assertEquals(Phase.Tracking, s.phase)
        s.onRelease(0f, h)
        assertEquals(Phase.Idle, s.phase)
    }

    @Test
    fun `a drag from mid-page doesn't track when a reveal isn't allowed`() {
        val s = ScrollRevealSlot()
        assertFalse(s.onDown(500f, 1700f, atEnd = false, allowed = false))
        assertEquals(Phase.Idle, s.phase)
        s.onMove(500f, 1300f, slop)
        assertFalse(s.onBottomOverscroll())
        assertFalse(s.onMove(500f, 1200f, slop))
    }

    @Test
    fun `a second finger ends the tracking`() {
        val s = ScrollRevealSlot()
        s.onDown(500f, 1700f, atEnd = false, allowed = true)
        // The host reports ACTION_POINTER_DOWN as a cancelled release.
        s.onRelease(0f, 0f, cancelled = true)
        assertFalse(s.onBottomOverscroll())
        assertFalse(s.onMove(500f, 1200f, slop))
    }

    @Test
    fun `a push at the end that first went down can still reveal when the drag comes back`() {
        val s = armed()
        // Down first: the page scrolls up, away from the end.
        assertFalse(s.onMove(500f, 1800f, slop))
        assertEquals(Phase.Tracking, s.phase)
        // Back up to the end, same finger.
        s.onMove(500f, 1700f, slop)
        assertTrue(s.onBottomOverscroll())
        assertTrue(s.onMove(500f, 1690f, slop))
    }

    @Test
    fun `a page that can't scroll counts as at the end`() {
        // The host passes `!canScrollVertically(1)`, true for a page with no range.
        val s = ScrollRevealSlot()
        assertTrue(s.onDown(500f, 1700f, atEnd = true, allowed = true))
    }

    @Test
    fun `a drag the page consumed (no overscroll) stays with the page`() {
        val s = ScrollRevealSlot()
        s.onDown(500f, 1700f, atEnd = true, allowed = true)
        assertFalse(s.onMove(500f, 1500f, slop))
        assertEquals(Phase.Armed, s.phase)
        s.onRelease(0f, h)
        assertEquals(Phase.Idle, s.phase)
    }

    @Test
    fun `overscroll from an earlier touch doesn't carry over`() {
        val s = armed()
        s.onRelease(0f, h)
        s.onDown(500f, 1700f, atEnd = true, allowed = true)
        assertFalse(s.onMove(500f, 1600f, slop))
    }

    @Test
    fun `downward or sideways first disarms`() {
        // Disarmed back to tracking: the push that armed at the down
        // no longer counts (only a fresh overscroll arms again).
        val down = armed()
        assertFalse(down.onMove(500f, 1730f, slop))
        assertEquals(Phase.Tracking, down.phase)
        assertFalse(down.onMove(500f, 1600f, slop))

        val side = armed()
        assertFalse(side.onMove(560f, 1680f, slop))
        assertEquals(Phase.Tracking, side.phase)
        assertFalse(side.onMove(560f, 1500f, slop))
    }

    @Test
    fun `not allowed means not armed`() {
        val s = ScrollRevealSlot()
        assertFalse(s.onDown(500f, 1700f, atEnd = true, allowed = false))
        s.onBottomOverscroll()
        assertFalse(s.onMove(500f, 1600f, slop))
    }

    @Test
    fun `allowed only in overlay, with the chrome idle, off the home surface`() {
        assertTrue(revealAllowed(Overlay, chromeEditing = false, keyboardVisible = false, isHome = false))
        assertFalse("reserved is already shortened", revealAllowed(Reserved, false, false, false))
        assertFalse("already revealed", revealAllowed(Revealed, false, false, false))
        assertFalse("address bar focused", revealAllowed(Overlay, true, false, false))
        assertFalse("keyboard up", revealAllowed(Overlay, false, true, false))
        assertFalse("home", revealAllowed(Overlay, false, false, true))
    }

    // --- drag, threshold, commit / spring back ------------------------------

    @Test
    fun `the rubber band follows the finger, stiffens and never passes H`() {
        assertEquals(0f, rubberBand(0f, h), 0f)
        assertEquals(0f, rubberBand(-50f, h), 0f)
        assertEquals(9.8f, rubberBand(10f, h), 0.1f) // ~1:1 at first
        assertTrue(rubberBand(h, h) < 0.64f * h)
        assertTrue(rubberBand(10_000f, h) <= h)
        var last = 0f
        for (d in 1..2000 step 10) {
            val o = rubberBand(d.toFloat(), h)
            assertTrue(o >= last)
            last = o
        }
    }

    @Test
    fun `the offset is measured from where the reveal took over`() {
        val s = dragging()
        assertEquals(0f, s.dragOffset(1670f, h), 0f)
        assertEquals(rubberBand(100f, h), s.dragOffset(1570f, h), 0.001f)
        // Back below the start: nothing, not negative.
        assertEquals(0f, s.dragOffset(1750f, h), 0f)
    }

    @Test
    fun `the commit threshold is 40 percent of H`() {
        assertFalse(revealCommits(0.39f * h, h))
        assertTrue(revealCommits(0.4f * h, h))
        assertTrue(revealCommits(h, h))
        assertFalse(revealCommits(10f, 0f))
    }

    @Test
    fun `release past the threshold commits, then reveals`() {
        val s = dragging()
        assertEquals(Phase.Committing, s.onRelease(0.5f * h, h))
        assertFalse(s.revealed)
        s.onCommitted(unscrollable = false)
        assertTrue(s.revealed)
        assertFalse(s.revealedFromUnscrollable)
    }

    @Test
    fun `release short of the threshold springs back and changes nothing`() {
        val s = dragging()
        assertEquals(Phase.SpringingBack, s.onRelease(0.3f * h, h))
        s.onCommitted(unscrollable = false) // not committing: ignored
        assertFalse(s.revealed)
        s.onSprungBack()
        assertEquals(Phase.Idle, s.phase)
    }

    @Test
    fun `a cancelled gesture springs back even past the threshold`() {
        val s = dragging()
        assertEquals(Phase.SpringingBack, s.onRelease(h, h, cancelled = true))
    }

    @Test
    fun `a new touch during the spring-back doesn't arm`() {
        val s = dragging()
        s.onRelease(0.1f * h, h)
        assertFalse(s.onDown(500f, 1700f, atEnd = true, allowed = true))
        assertEquals(Phase.SpringingBack, s.phase)
    }

    // --- restore ----------------------------------------------------------

    private fun revealed(unscrollable: Boolean = false): ScrollRevealSlot = dragging().apply {
        onRelease(h, h)
        onCommitted(unscrollable)
    }

    @Test
    fun `restores once the end is more than H away, not before`() {
        val s = revealed()
        assertFalse(s.onScroll(distanceFromEndPx = 0, scrollYPx = 3000, revealPx = 215))
        assertFalse(s.onScroll(distanceFromEndPx = 215, scrollYPx = 2785, revealPx = 215))
        assertTrue(s.onScroll(distanceFromEndPx = 216, scrollYPx = 2784, revealPx = 215))
        assertEquals(Phase.Idle, s.phase)
        // Reported once.
        assertFalse(s.onScroll(distanceFromEndPx = 900, scrollYPx = 2100, revealPx = 215))
    }

    @Test
    fun `a page that gained H or less of range restores back at the top`() {
        // short.html: 72 px of range after the reveal; the end can never
        // be more than H away.
        val s = revealed(unscrollable = true)
        assertFalse(s.onScroll(distanceFromEndPx = 40, scrollYPx = 32, revealPx = 215))
        assertTrue(s.revealed)
        assertTrue(s.onScroll(distanceFromEndPx = 72, scrollYPx = 0, revealPx = 215))
        assertEquals(Phase.Idle, s.phase)
        assertFalse(s.revealedFromUnscrollable)
    }

    @Test
    fun `restore rule`() {
        assertFalse(revealShouldRestore(distanceFromEndPx = 215, scrollYPx = 1, revealPx = 215))
        assertTrue(revealShouldRestore(distanceFromEndPx = 216, scrollYPx = 1, revealPx = 215))
        assertTrue(revealShouldRestore(distanceFromEndPx = 0, scrollYPx = 0, revealPx = 215))
    }

    // --- handover ---------------------------------------------------------

    @Test
    fun `a handover timeout from an earlier reveal is stale`() {
        val g = RevealGeneration()
        val first = g.next()
        assertTrue(g.isCurrent(first))
        // Restored and pushed again before the first timeout fired.
        val second = g.next()
        assertFalse(g.isCurrent(first))
        assertTrue(g.isCurrent(second))
    }

    @Test
    fun `handover shortfall is zero when the page gained the whole shrink`() {
        // Long article: range 2100 -> 2315, scrolled from its end.
        assertEquals(0, revealShortfall(scrollFromPx = 2100, shrunkByPx = 215, newRangePx = 2315))
        // Mid-page (end within reach, more range than needed).
        assertEquals(0, revealShortfall(scrollFromPx = 100, shrunkByPx = 215, newRangePx = 2315))
    }

    @Test
    fun `handover shortfall is what the clamp took off a page that gained less`() {
        // short.html: range 0 -> 72 (review), 0 -> 189 (AVD run).
        assertEquals(143, revealShortfall(scrollFromPx = 0, shrunkByPx = 215, newRangePx = 72))
        assertEquals(26, revealShortfall(scrollFromPx = 0, shrunkByPx = 215, newRangePx = 189))
        // vh-sized content whose range didn't grow at all.
        assertEquals(215, revealShortfall(scrollFromPx = 500, shrunkByPx = 215, newRangePx = 500))
        // Never more than the shrink, never negative.
        assertEquals(215, revealShortfall(scrollFromPx = 500, shrunkByPx = 215, newRangePx = 0))
        assertEquals(0, revealShortfall(scrollFromPx = 0, shrunkByPx = 0, newRangePx = 0))
    }

    @Test
    fun `after a restore, back at the end, nothing reveals without a fresh push`() {
        val s = revealed()
        s.onScroll(1000, 1315, 215)
        // Scrolling back down to the end, same gesture: no down, no reveal.
        assertFalse(s.onScroll(0, 2315, 215))
        s.onBottomOverscroll()
        assertFalse(s.onMove(500f, 1000f, slop))
        assertFalse(s.revealed)
    }

    @Test
    fun `revealed ignores pushes and touches until it is restored`() {
        val s = revealed()
        assertFalse(s.onDown(500f, 1700f, atEnd = true, allowed = true))
        s.onRelease(0f, h)
        assertTrue(s.revealed)
    }

    @Test
    fun `reset drops a reveal in any phase`() {
        val tracking = ScrollRevealSlot().apply { onDown(500f, 1700f, atEnd = false, allowed = true) }
        for (s in listOf(tracking, armed(), dragging(), revealed(), revealed(unscrollable = true))) {
            s.reset()
            assertEquals(Phase.Idle, s.phase)
            assertFalse(s.revealedFromUnscrollable)
        }
    }

    @Test
    fun `a page revealed from no range gains no pull-to-refresh`() {
        assertTrue(revealAdjustedScrollsDown(canScrollDown = true, revealedFromUnscrollable = false))
        assertFalse(revealAdjustedScrollsDown(canScrollDown = true, revealedFromUnscrollable = true))
        assertFalse(revealAdjustedScrollsDown(canScrollDown = false, revealedFromUnscrollable = false))
        assertTrue(revealed(unscrollable = true).revealedFromUnscrollable)
    }

    // --- strip colour ---------------------------------------------------------

    @Test
    fun `the strip takes the row's most common colour`() {
        val purple = 0xFF6750A4.toInt()
        val shadow = 0xFF5A4690.toInt()
        val row = IntArray(1080) { if (it in 400..700) shadow else purple }
        assertEquals(0x6750A4, dominantRgb(row))
        assertEquals("rgb(103, 80, 164)", rgbString(0x6750A4))
        assertEquals(0xFF6750A4.toInt(), parseRgb(rgbString(dominantRgb(row)!!)))
        assertNull(dominantRgb(IntArray(0)))
    }

    @Test
    fun `the capsule's shadow shade does not outvote the page`() {
        // Expanded capsule over a white page: its shadow's falloff has
        // one shade across most of the row, which a whole-row vote picks.
        val white = 0xFFFFFFFF.toInt()
        val shadow = 0xFFFEFEFE.toInt()
        val row = IntArray(1080) { if (it in 60..1019) shadow else white }
        assertEquals(0xFEFEFE, dominantRgb(row))
        assertEquals(0xFFFFFF, dominantRgb(revealTintPixels(row, edgePx = 19)))
        // Both ends vote: a scrollbar on one edge doesn't take it.
        val scrolled = row.copyOf().also { for (i in 1070..1079) it[i] = 0xFF888888.toInt() }
        assertEquals(0xFFFFFF, dominantRgb(revealTintPixels(scrolled, edgePx = 19)))
        assertEquals(38, revealTintPixels(row, edgePx = 19).size)
        // No room for a middle, or no edge: the whole row.
        assertEquals(10, revealTintPixels(IntArray(10), edgePx = 5).size)
        assertEquals(1080, revealTintPixels(row, edgePx = 0).size)
    }

    @Test
    fun `the tint is sampled above the navigation bar and its scrim`() {
        // 3-button nav: 2400 px window, 126 px bar, WebView to the bottom.
        assertEquals(2273, revealSampleRowY(viewTopPx = 0, viewHeightPx = 2400, windowHeightPx = 2400, navInsetPx = 126))
        // Gesture nav: the handle's inset is still skipped.
        assertEquals(2336, revealSampleRowY(viewTopPx = 0, viewHeightPx = 2400, windowHeightPx = 2400, navInsetPx = 63))
        // No inset: the view's last row, as before.
        assertEquals(2399, revealSampleRowY(viewTopPx = 0, viewHeightPx = 2400, windowHeightPx = 2400, navInsetPx = 0))
        // A view that stops above the bar: its own last row.
        assertEquals(2099, revealSampleRowY(viewTopPx = 100, viewHeightPx = 2000, windowHeightPx = 2400, navInsetPx = 126))
        // Never outside the view.
        assertEquals(100, revealSampleRowY(viewTopPx = 100, viewHeightPx = 10, windowHeightPx = 150, navInsetPx = 126))
    }

    // --- multi-touch ----------------------------------------------------------

    @Test
    fun `only the pushing finger lifting ends the drag`() {
        assertTrue(revealDragReleased(MotionEvent.ACTION_UP, 0, 0))
        assertTrue(revealDragReleased(MotionEvent.ACTION_CANCEL, 0, 0))
        // The pushing finger (index 0) lifts, a second one stays down.
        assertTrue(revealDragReleased(MotionEvent.ACTION_POINTER_UP, 0, 0))
        // The second finger lifts: the drag goes on.
        assertFalse(revealDragReleased(MotionEvent.ACTION_POINTER_UP, 1, 0))
        assertFalse(revealDragReleased(MotionEvent.ACTION_POINTER_DOWN, 1, 0))
        assertFalse(revealDragReleased(MotionEvent.ACTION_MOVE, 0, 0))
    }
}
