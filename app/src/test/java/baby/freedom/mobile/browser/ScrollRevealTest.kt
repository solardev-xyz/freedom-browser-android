package baby.freedom.mobile.browser

import baby.freedom.mobile.browser.BottomChromeMode.Overlay
import baby.freedom.mobile.browser.BottomChromeMode.Reserved
import baby.freedom.mobile.browser.BottomChromeMode.Revealed
import baby.freedom.mobile.browser.ScrollRevealSlot.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `a touch that goes down mid-page never arms, so a fling that lands at the end doesn't reveal`() {
        val s = ScrollRevealSlot()
        assertFalse(s.onDown(500f, 1700f, atEnd = false, allowed = true))
        // The fling carries the page to its end; Chromium overscrolls.
        s.onBottomOverscroll()
        assertFalse(s.onMove(500f, 1400f, slop))
        s.onRelease(0f, h)
        assertEquals(Phase.Idle, s.phase)
        // The next touch goes down at the end: that one is a push.
        assertTrue(s.onDown(500f, 1700f, atEnd = true, allowed = true))
        s.onBottomOverscroll()
        assertTrue(s.onMove(500f, 1670f, slop))
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
        val down = armed()
        assertFalse(down.onMove(500f, 1730f, slop))
        assertEquals(Phase.Idle, down.phase)
        assertFalse(down.onMove(500f, 1600f, slop))

        val side = armed()
        assertFalse(side.onMove(560f, 1680f, slop))
        assertEquals(Phase.Idle, side.phase)
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
        assertFalse(s.onScroll(distanceFromEndPx = 0, revealPx = 215))
        assertFalse(s.onScroll(distanceFromEndPx = 215, revealPx = 215))
        assertTrue(s.onScroll(distanceFromEndPx = 216, revealPx = 215))
        assertEquals(Phase.Idle, s.phase)
        // Reported once.
        assertFalse(s.onScroll(distanceFromEndPx = 900, revealPx = 215))
    }

    @Test
    fun `after a restore, back at the end, nothing reveals without a fresh push`() {
        val s = revealed()
        s.onScroll(1000, 215)
        // Scrolling back down to the end, same gesture: no down, no reveal.
        assertFalse(s.onScroll(0, 215))
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
        for (s in listOf(armed(), dragging(), revealed(), revealed(unscrollable = true))) {
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
}
