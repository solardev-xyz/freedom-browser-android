package baby.freedom.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * When a finished load becomes a history entry.
 *
 * History is gated on first paint — that is how an aborted navigation
 * (Stop, or a second navigation superseding the first) is kept out of
 * the list, since Chromium fires a synthetic `onPageFinished` for a
 * document that never committed. But `onPageFinished` and
 * `onPageCommitVisible` arrive in no fixed order: on a fast page the
 * load event can land a millisecond *before* the first frame, and the
 * same gate then drops a page the user genuinely visited.
 *
 * So a not-yet-painted finish is parked rather than discarded, and
 * [visitToFlush] decides which paint, if any, turns it into a visit.
 */
class VisitRecordingTest {

    private val visit = PendingVisit(
        rawUrl = "http://127.0.0.1:8773/white.html",
        display = "127.0.0.1:8773/white.html",
        title = "white",
    )

    @Test
    fun `a page that finishes before it paints is recorded when it paints`() {
        assertEquals(visit, visitToFlush(visit, committedUrl = visit.rawUrl))
    }

    @Test
    fun `a paint belonging to another document does not adopt the parked visit`() {
        // The parked page never painted; the one that did is somebody
        // else's row and will record itself.
        assertNull(visitToFlush(visit, committedUrl = "https://example.com/other"))
    }

    @Test
    fun `a paint with nothing parked records nothing`() {
        // The ordinary order — paint first, finish second — leaves
        // nothing behind for the commit to flush.
        assertNull(visitToFlush(null, committedUrl = visit.rawUrl))
    }

    @Test
    fun `an unnamed paint flushes nothing`() {
        // No URL to match on is no evidence the parked page painted.
        assertNull(visitToFlush(visit, committedUrl = null))
    }
}
