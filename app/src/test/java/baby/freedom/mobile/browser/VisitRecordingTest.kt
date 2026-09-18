package baby.freedom.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    // ---- the park's lifecycle (#43) --------------------------------
    //
    // [visitToFlush] says which paint a park is redeemed by;
    // [PendingVisitSlot] says how long it may wait for one. A park that
    // outlives its own navigation is a second history row for a page the
    // user visited once — so the slot is single-shot, and every
    // navigation that starts empties it.

    @Test
    fun `a parked visit is recorded by its paint and never again`() {
        val slot = PendingVisitSlot()
        slot.park(visit)
        assertEquals(visit, slot.flush(visit.rawUrl))
        // The same document painting again (a re-commit, a second
        // `onPageCommitVisible`) has nothing left to record.
        assertNull(slot.flush(visit.rawUrl))
        assertNull(slot.pending)
    }

    @Test
    fun `a paint for another document empties the slot as well`() {
        // The parked page never painted, and this paint belongs to the
        // document that replaced it: the park is spent either way, so it
        // can't be redeemed by some later paint of its own URL.
        val slot = PendingVisitSlot()
        slot.park(visit)
        assertNull(slot.flush("https://example.com/other"))
        assertNull(slot.pending)
        assertNull(slot.flush(visit.rawUrl))
    }

    @Test
    fun `a navigation that starts drops whatever is parked`() {
        val slot = PendingVisitSlot()
        slot.park(visit)
        slot.clear()
        assertNull(slot.pending)
        assertNull(slot.flush(visit.rawUrl))
    }

    @Test
    fun `the reviewer's double-record sequence records one row per visit`() {
        // The whole of #43, callback by callback. Page A finishes before
        // it paints, the user taps Home before that paint lands, and then
        // visits A again:
        val slot = PendingVisitSlot()
        // onPageStarted(A) — nothing parked yet.
        slot.clear()
        // onPageFinished(A) before onPageCommitVisible(A): parked.
        slot.park(visit)
        assertEquals(visit, slot.pending)
        // Home. A never painted, so its park goes with it — from
        // onPageStarted(about:blank) for a Home tap, and from
        // onPageFinished(about:blank) for a back gesture onto the blank
        // entry, which gets no onPageStarted at all. (Before the fix both
        // branches returned early and left the park standing.)
        slot.clear()
        // The second visit to A. Its paint must not adopt the stale park…
        slot.clear()
        assertNull(slot.flush(visit.rawUrl))
        // …which leaves this visit's own finish as the only row it
        // writes: one visit, one history entry.
        assertNull(slot.pending)
    }

    @Test
    fun `a fast page still records when its own paint arrives`() {
        // The case the park exists for, unchanged by the lifecycle: the
        // finish beats the first frame by a millisecond and the visit is
        // recorded the moment the page is really on screen.
        val slot = PendingVisitSlot()
        slot.clear()
        slot.park(visit)
        assertEquals(visit, slot.flush(visit.rawUrl))
    }

    // ---- the committed branch's own single shot (#53) ---------------
    //
    // The park above is one row per *park*. The committed branch needed
    // the same guarantee per *row*: `onPageFinished` is not once per
    // document — a streaming load stopped mid-body that then completes
    // server-side fires it twice, with the same URL, the same
    // `getUrl()`, and the document still committed.

    private val display = visit.display
    private val other = "127.0.0.1:8773/white.html#section"

    @Test
    fun `an uncommitted document records nothing`() {
        // The aborted case the gate exists for: no paint, no row —
        // that finish is the park's business, not this branch's.
        val gate = CommittedVisitGate()
        gate.startNavigation()
        assertFalse(gate.isCommitted)
        assertFalse(gate.recordOnce(display))
    }

    @Test
    fun `a committed document records its row exactly once`() {
        val gate = CommittedVisitGate()
        gate.startNavigation()
        gate.commit()
        assertTrue(gate.isCommitted)
        assertTrue(gate.recordOnce(display))
        assertFalse(gate.recordOnce(display))
        // …and stays committed, so a second finish still takes the
        // committed branch rather than parking itself for a paint that
        // has already happened.
        assertTrue(gate.isCommitted)
    }

    @Test
    fun `the stopped-then-completed streaming load is one history row`() {
        // The whole of #53, callback by callback.
        val gate = CommittedVisitGate()
        // onPageStarted — the document begins.
        gate.startNavigation()
        // onPageCommitVisible — the body starts painting.
        gate.commit()
        // Stop mid-body: Chromium finishes the load it aborted…
        assertTrue(gate.recordOnce(display))
        // …and then the server completes the same response and a second
        // onPageFinished lands for the very same document.
        assertFalse(gate.recordOnce(display))
    }

    @Test
    fun `a same-document navigation is still a visit of its own`() {
        // `history.pushState` and hash links finish without an
        // intervening `onPageStarted`, so a bare "already recorded"
        // latch would have dropped them from history (they record today
        // — verified on the freedom AVD). A different row is a different
        // visit; only the same row twice is #53.
        val gate = CommittedVisitGate()
        gate.startNavigation()
        gate.commit()
        assertTrue(gate.recordOnce(display))
        assertTrue(gate.recordOnce(other))
        assertFalse(gate.recordOnce(other))
    }

    @Test
    fun `a re-commit of the same document does not refill the slot`() {
        // Only a navigation refills it. `onPageCommitVisible` firing
        // again for the document already on screen is not one.
        val gate = CommittedVisitGate()
        gate.startNavigation()
        gate.commit()
        assertTrue(gate.recordOnce(display))
        gate.commit()
        assertFalse(gate.recordOnce(display))
    }

    @Test
    fun `the next navigation gets a row of its own`() {
        // Reload, or a second visit to the same URL: one visit, one
        // row, every time.
        val gate = CommittedVisitGate()
        gate.startNavigation()
        gate.commit()
        assertTrue(gate.recordOnce(display))

        gate.startNavigation()
        assertFalse(gate.isCommitted)
        assertFalse(gate.recordOnce(display))
        gate.commit()
        assertTrue(gate.recordOnce(display))
    }

    @Test
    fun `a parked visit's flush spends the document's row too`() {
        // The fast page: its finish parked, its paint recorded it. A
        // second finish for that same document then finds the row
        // already written — without which #53's double row came back by
        // the other door.
        val gate = CommittedVisitGate()
        val slot = PendingVisitSlot()
        gate.startNavigation()
        // onPageFinished before the paint: parked, nothing recorded.
        assertFalse(gate.isCommitted)
        slot.park(visit)
        // onPageCommitVisible: the park becomes the document's row.
        gate.commit()
        assertEquals(visit, slot.flush(visit.rawUrl))
        assertTrue(gate.recordOnce(visit.display))
        // The streaming second finish: committed, but spent.
        assertFalse(gate.recordOnce(visit.display))
    }
}
