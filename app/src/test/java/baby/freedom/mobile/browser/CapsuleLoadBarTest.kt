package baby.freedom.mobile.browser

import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The address field's Chrome-style load bar (#415): progress → fraction. */
class CapsuleLoadBarTest {

    @Test
    fun `a load starts at the minimum before any progress is reported`() {
        assertEquals(CAPSULE_LOAD_MIN_FRACTION, capsuleLoadTarget(-1, 0f, 0), 0f)
        assertEquals(CAPSULE_LOAD_MIN_FRACTION, capsuleLoadTarget(0, 0f, 0), 0f)
        assertEquals(CAPSULE_LOAD_MIN_FRACTION, capsuleLoadTarget(2, 0f, 0), 0f)
    }

    @Test
    fun `reported progress maps straight through above the minimum`() {
        assertEquals(0.3f, capsuleLoadTarget(30, 0f, 0), 1e-6f)
        assertEquals(0.99f, capsuleLoadTarget(99, 0f, 0), 1e-6f)
    }

    @Test
    fun `completion is the whole bar`() {
        assertEquals(1f, capsuleLoadTarget(100, 0f, 0), 0f)
        assertEquals(1f, capsuleLoadTarget(100, 0.5f, 60_000), 0f)
    }

    @Test
    fun `never runs backwards below where it already was`() {
        assertEquals(0.6f, capsuleLoadTarget(10, 0.6f, 0), 1e-6f)
    }

    @Test
    fun `a stall holds, then creeps, and never reaches the end`() {
        val held = capsuleLoadTarget(30, 0f, CAPSULE_LOAD_CREEP_DELAY_MS)
        assertEquals(0.3f, held, 1e-6f)
        var last = held
        for (ms in listOf(3_000L, 10_000L, 30_000L, 120_000L, 3_600_000L)) {
            val next = capsuleLoadTarget(30, 0f, ms)
            assertTrue("creeps at $ms ms", next > last)
            assertTrue("never past the ceiling at $ms ms", next <= CAPSULE_LOAD_CREEP_CEILING)
            last = next
        }
        // Already past the ceiling: holds there, doesn't creep to 100 %.
        assertEquals(0.95f, capsuleLoadTarget(95, 0f, 3_600_000L), 1e-6f)
    }

    @Test
    fun `the meter shows the minimum on the very first frame`() {
        val m = CapsuleLoadMeter()
        assertFalse(m.visible)
        m.frame(1_000, progress = 0, loading = true, indeterminate = false)
        assertTrue(m.visible)
        assertEquals(1f, m.alpha, 0f)
        assertEquals(CAPSULE_LOAD_MIN_FRACTION, m.fraction, 0f)
    }

    @Test
    fun `the meter follows progress smoothly and monotonically`() {
        val m = CapsuleLoadMeter()
        var t = 0L
        m.frame(t, 0, loading = true, indeterminate = false)
        var last = m.fraction
        repeat(30) {
            t += 16
            m.frame(t, 60, loading = true, indeterminate = false)
            assertTrue(m.fraction >= last)
            assertTrue(m.fraction <= 0.6f + 1e-6f)
            last = m.fraction
        }
        // Smooth, not a jump: one frame in, it wasn't already there…
        val m2 = CapsuleLoadMeter()
        m2.frame(0, 0, loading = true, indeterminate = false)
        m2.frame(16, 60, loading = true, indeterminate = false)
        assertTrue(m2.fraction < 0.5f)
        // …and half a second later it is.
        assertEquals(0.6f, last, 0.02f)
        // Progress falling back (a redirect) doesn't pull it back.
        t += 16
        m.frame(t, 10, loading = true, indeterminate = false)
        assertTrue(m.fraction >= last)
    }

    @Test
    fun `while resolving the meter holds instead of creeping`() {
        val m = CapsuleLoadMeter()
        m.frame(0, -1, loading = true, indeterminate = true)
        var t = 0L
        while (t < 30_000) {
            t += 100
            m.frame(t, -1, loading = true, indeterminate = true)
        }
        assertEquals(CAPSULE_LOAD_MIN_FRACTION, m.fraction, 1e-6f)
    }

    @Test
    fun `a stalled load creeps on the frame clock but never fills`() {
        val m = CapsuleLoadMeter()
        var t = 0L
        m.frame(t, 20, loading = true, indeterminate = false)
        while (t < 600_000) {
            t += 100
            m.frame(t, 20, loading = true, indeterminate = false)
        }
        assertTrue(m.fraction > 0.5f)
        assertTrue(m.fraction <= CAPSULE_LOAD_CREEP_CEILING)
        assertEquals(1f, m.alpha, 0f)
    }

    @Test
    fun `when the load ends the bar fills and then fades out`() {
        val m = CapsuleLoadMeter()
        var t = 0L
        m.frame(t, 40, loading = true, indeterminate = false)
        t += 16
        m.frame(t, 40, loading = true, indeterminate = false)
        // Ended: fills to 100 % first, still fully opaque.
        var filledAt = -1L
        while (t < 2_000) {
            t += 16
            m.frame(t, -1, loading = false, indeterminate = false)
            if (m.fraction >= 1f) { filledAt = t; break }
            assertEquals(1f, m.alpha, 0f)
        }
        assertTrue("filled within 300 ms", filledAt in 1..316)
        // Then fades over ~250 ms rather than vanishing.
        t += 16
        m.frame(t, -1, loading = false, indeterminate = false)
        assertTrue(m.alpha in 0.5f..0.99f)
        assertTrue(m.visible)
        repeat(20) {
            t += 16
            m.frame(t, -1, loading = false, indeterminate = false)
        }
        assertFalse(m.visible)
        assertEquals(0f, m.fraction, 0f)
    }

    @Test
    fun `a new load during the fade starts over at the minimum`() {
        val m = CapsuleLoadMeter()
        var t = 0L
        m.frame(t, 90, loading = true, indeterminate = false)
        repeat(18) { t += 16; m.frame(t, -1, loading = false, indeterminate = false) }
        assertTrue(m.visible)
        t += 16
        m.frame(t, 0, loading = true, indeterminate = false)
        assertEquals(CAPSULE_LOAD_MIN_FRACTION, m.fraction, 0f)
        assertEquals(1f, m.alpha, 0f)
    }

    /**
     * Drives [m] the way [CapsuleLoadBar]'s loop does — the next frame
     * after the wait the meter asks for — and counts the frames drawn.
     */
    private fun drive(m: CapsuleLoadMeter, from: Long, until: Long, progress: Int): Pair<Long, Int> {
        var t = from
        var frames = 0
        while (t < until) {
            val wait = m.frame(t, progress, loading = true, indeterminate = false)
            frames++
            t += if (wait == Long.MAX_VALUE) until - t else maxOf(16L, wait)
        }
        return t to frames
    }

    @Test
    fun `a stalled load rests between creep steps instead of drawing every frame`() {
        val m = CapsuleLoadMeter()
        m.frame(0, 30, loading = true, indeterminate = false)
        val (_, frames) = drive(m, 16, 40_000, 30)
        // 40 s at 60 Hz would be 2 500 frames.
        assertTrue("drew $frames frames", frames < 400)
        // …and it still creeps, never past the ceiling.
        assertTrue(m.fraction > 0.6f)
        assertTrue(m.fraction < CAPSULE_LOAD_CREEP_CEILING)
        // A settled bar with no creep left asks for nothing at all.
        val held = CapsuleLoadMeter()
        held.frame(0, 95, loading = true, indeterminate = false)
        val (_, heldFrames) = drive(held, 16, 60_000, 95)
        assertTrue("drew $heldFrames frames", heldFrames < 60)
    }

    @Test
    fun `the creep wake-up time is where the creep reaches that value`() {
        val at = capsuleCreepReachMs(30, 0f, 0.5f)!!
        assertEquals(0.5f, capsuleLoadTarget(30, 0f, at), 1e-3f)
        assertTrue(capsuleLoadTarget(30, 0f, at - 50) < 0.5f)
        assertEquals(CAPSULE_LOAD_CREEP_DELAY_MS, capsuleCreepReachMs(30, 0f, 0.2f))
        assertEquals(null, capsuleCreepReachMs(30, 0f, CAPSULE_LOAD_CREEP_CEILING))
        assertEquals(null, capsuleCreepReachMs(100, 0f, 0.5f))
    }

    @Test
    fun `a progress report wakes a resting bar and it eases rather than jumps`() {
        val m = CapsuleLoadMeter()
        m.frame(0, 30, loading = true, indeterminate = false)
        drive(m, 16, 1_000, 30)
        val before = m.fraction
        // Woken long after its last frame by a new report.
        m.frame(1_400, 80, loading = true, indeterminate = false)
        assertTrue(m.fraction < 0.5f)
        assertTrue(m.fraction >= before)
    }

    @Test
    fun `the tab's bar carries on where it was when shown again mid-load`() {
        val tab = BrowserState(id = 1L)
        val m = tab.loadMeter
        m.attach()
        var t = 0L
        m.frame(t, 0, loading = true, indeterminate = false)
        repeat(60) { t += 16; m.frame(t, 70, loading = true, indeterminate = false) }
        val shown = m.fraction
        assertEquals(0.7f, shown, 0.01f)
        // The find bar opens and closes: one bar goes, another comes.
        m.detach()
        m.attach()
        t += 16
        m.frame(t, 70, loading = true, indeterminate = false)
        assertTrue(m.fraction >= shown)
        // Much later, still loading (the tab was in the background).
        m.detach()
        m.attach()
        t += 5_000
        m.frame(t, 75, loading = true, indeterminate = false)
        assertTrue(m.fraction >= shown)
        assertEquals(1f, m.alpha, 0f)
        assertTrue(tab.loadMeter === m)
    }

    @Test
    fun `a load that ended while no bar was on screen isn't filled later`() {
        val m = CapsuleLoadMeter()
        m.attach()
        m.frame(0, 40, loading = true, indeterminate = false)
        m.detach()
        m.attach()
        m.frame(10_000, -1, loading = false, indeterminate = false)
        assertFalse(m.visible)
    }

    @Test
    fun `a failed or stopped load fades where it stands instead of filling`() {
        val m = CapsuleLoadMeter()
        var t = 0L
        m.frame(t, 0, loading = true, indeterminate = false)
        repeat(60) { t += 16; m.frame(t, 30, loading = true, indeterminate = false) }
        val at = m.fraction
        while (m.visible && t < 2_000) {
            t += 16
            m.frame(t, -1, loading = false, indeterminate = false, failed = true)
            assertEquals(at, if (m.visible) m.fraction else at, 0f)
        }
        assertFalse(m.visible)
        assertTrue("faded within ~250 ms, took ${t - 960}", t - 960 <= 300)

        val tab = BrowserState(id = 2L)
        assertFalse(capsuleLoadFailed(tab))
        tab.showsErrorPage = true
        assertTrue(capsuleLoadFailed(tab))
        tab.showsErrorPage = false
        tab.stopProgress()
        assertTrue(capsuleLoadFailed(tab))
    }

    @Test
    fun `the lead-in is where a bar clipped to a pill reaches full thickness`() {
        // 44 px tall pill, 3 px bar: the strip is full height ~11 px in.
        val leadIn = capsuleBarLeadIn(Size(300f, 44f), 3f)
        assertEquals(22f - kotlin.math.sqrt(22f * 22f - 19f * 19f), leadIn, 1e-4f)
        assertEquals(0f, capsuleBarLeadIn(Size(300f, 0f), 3f), 0f)
    }
}
