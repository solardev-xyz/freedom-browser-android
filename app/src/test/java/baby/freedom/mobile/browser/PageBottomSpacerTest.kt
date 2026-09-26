package baby.freedom.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The end-of-document spacer that lets a page's last band scroll out
 * from under the floating bar (#65, interim mitigation).
 */
class PageBottomSpacerTest {

    // ---- height ------------------------------------------------------

    @Test
    fun `height is the resting bar footprint plus the navigation inset`() {
        // 48 dp slot + 10 dp margin + 24 dp gesture-nav inset at 2.625x.
        assertEquals(82, bottomSpacerDp(navInsetPx = 63, density = 2.625f))
        // 3-button navigation: 48 dp inset.
        assertEquals(106, bottomSpacerDp(navInsetPx = 126, density = 2.625f))
    }

    @Test
    fun `no navigation inset leaves just the bar`() {
        assertEquals(58, bottomSpacerDp(navInsetPx = 0, density = 2.625f))
    }

    @Test
    fun `a fractional inset rounds up rather than leaving a sliver`() {
        // 25 px / 2 = 12.5 dp → 58 + 12.5 → 71.
        assertEquals(71, bottomSpacerDp(navInsetPx = 25, density = 2f))
    }

    @Test
    fun `a nonsense density or inset does not throw or go negative`() {
        assertEquals(58, bottomSpacerDp(navInsetPx = 100, density = 0f))
        assertEquals(58, bottomSpacerDp(navInsetPx = -5, density = 2f))
    }

    // ---- gating ------------------------------------------------------

    @Test
    fun `the home sentinel gets no spacer`() {
        assertFalse(bottomSpacerApplies("about:blank"))
        assertFalse(bottomSpacerApplies(null))
        assertFalse(bottomSpacerApplies(""))
    }

    @Test
    fun `real documents do`() {
        assertTrue(bottomSpacerApplies("https://example.com/"))
        assertTrue(bottomSpacerApplies("http://127.0.0.1:1633/bzz/abc/"))
    }

    // ---- result parsing ----------------------------------------------

    @Test
    fun `results parse to css px, pending to null`() {
        assertEquals(82, parseBottomSpacerResult("82"))
        assertEquals(0, parseBottomSpacerResult("0"))
        assertNull(parseBottomSpacerResult("-1"))
        assertNull(parseBottomSpacerResult("null"))
        assertNull(parseBottomSpacerResult(null))
        assertNull(parseBottomSpacerResult("\"82\""))
    }

    // ---- slot: when does a pass run ----------------------------------

    /** A document [height] CSS px tall, as `WebView.getContentHeight()` reads it. */
    private var height = 800

    /** Runs [passes], answering each decision with [answer]; returns how many ran JS. */
    private fun BottomSpacerSlot.simulate(passes: List<() -> BottomSpacerSlot.Token?>, answer: () -> Int?): Int {
        var scripts = 0
        for (pass in passes) {
            val token = pass() ?: continue
            scripts++
            accept(token, answer(), height)
        }
        return scripts
    }

    private fun BottomSpacerSlot.loadThenTouches(touches: Int) =
        listOf({ decideOnLoad(height) }, { decideOnLoad(height) }) + List(touches) { { decideOnTouch(height) } }

    private fun BottomSpacerSlot.touches(n: Int) = List(n) { { decideOnTouch(height) } }

    @Test
    fun `a kept document runs one script, then none on touch-down`() {
        val slot = BottomSpacerSlot()
        assertEquals(1, slot.simulate(slot.loadThenTouches(5)) { 82 })
        assertEquals(SpacerState.Kept(82), slot.state)
        assertEquals(82, slot.discountCssPx)
        height = 5000 // a kept spacer is final however the page grows
        assertEquals(0, slot.simulate(slot.loadThenTouches(5)) { 82 })
    }

    @Test
    fun `a rejected document that does not grow runs one script, then none`() {
        val slot = BottomSpacerSlot()
        assertEquals(1, slot.simulate(slot.loadThenTouches(5)) { 0 })
        assertEquals(SpacerState.Rejected(800), slot.state)
        assertEquals(0, slot.discountCssPx)
    }

    @Test
    fun `a client-rendered page rejected at first paint is kept once it has grown`() {
        // First paint is a one-screen "Loading…" shell: the spacer adds no
        // scroll range. The app then renders the real content.
        val slot = BottomSpacerSlot()
        height = 863
        slot.accept(slot.decideOnLoad(height)!!, 0, height)
        assertEquals(SpacerState.Rejected(863), slot.state)
        assertNull(slot.decideOnTouch(height)) // unchanged: nothing runs
        height = 2082
        val retry = slot.decideOnTouch(height)!!
        slot.accept(retry, 82, height + 82)
        assertEquals(SpacerState.Kept(82), slot.state)
        assertEquals(1, slot.touchAttempts)
    }

    @Test
    fun `load finish retries a rejected document that has grown`() {
        val slot = BottomSpacerSlot()
        slot.accept(slot.decideOnLoad(863)!!, 0, 863)
        assertNull(slot.decideOnLoad(863))
        assertNotNull(slot.decideOnLoad(2082))
    }

    @Test
    fun `a page that keeps growing but never takes the spacer gives up after eight touches`() {
        // e.g. a full-height flex body loading images: every retry rejects.
        val slot = BottomSpacerSlot()
        slot.accept(slot.decideOnLoad(height)!!, 0, height)
        var scripts = 0
        repeat(20) {
            height += 100
            val t = slot.decideOnTouch(height) ?: return@repeat
            scripts++
            slot.accept(t, 0, height)
        }
        assertEquals(SPACER_MAX_TOUCH_ATTEMPTS, scripts)
    }

    @Test
    fun `no body at first paint is decided at load finish`() {
        val slot = BottomSpacerSlot()
        val answers = ArrayDeque(listOf(null, 82))
        assertEquals(2, slot.simulate(slot.loadThenTouches(4)) { answers.removeFirst() })
        assertEquals(SpacerState.Kept(82), slot.state)
    }

    @Test
    fun `a lock that lifts is kept on the first touch after`() {
        val slot = BottomSpacerSlot()
        val answers = ArrayDeque(listOf(null, null, null, 82))
        // commit, finish, touch (still locked), touch (unlocked) → kept; then nothing.
        assertEquals(4, slot.simulate(slot.loadThenTouches(6)) { answers.removeFirst() })
        assertEquals(SpacerState.Kept(82), slot.state)
    }

    @Test
    fun `a permanent lock gives up after its own allowance of touches`() {
        val slot = BottomSpacerSlot()
        val scripts = slot.simulate(slot.loadThenTouches(200)) { null }
        assertEquals(2 + SPACER_MAX_LOCKED_TOUCHES, scripts)
        assertEquals(SpacerState.Pending, slot.state)
        assertEquals(SPACER_MAX_LOCKED_TOUCHES, slot.lockedTouches)
        assertEquals(0, slot.touchAttempts)
        assertEquals(0, slot.discountCssPx)
    }

    @Test
    fun `a consent flow of many taps under its lock still gets the spacer after`() {
        // "Manage options": tabs, a toggle per vendor, Save — 20 taps under
        // overflow:hidden, each answered "locked". Then the lock lifts.
        val slot = BottomSpacerSlot()
        slot.simulate(listOf({ slot.decideOnLoad(height) }, { slot.decideOnLoad(height) })) { null }
        assertEquals(20, slot.simulate(slot.touches(20)) { null })
        assertEquals(0, slot.touchAttempts) // the measuring budget is untouched
        assertEquals(20, slot.lockedTouches)
        slot.accept(slot.decideOnTouch(height)!!, 82, height)
        assertEquals(SpacerState.Kept(82), slot.state)
    }

    @Test
    fun `locked taps and measuring taps are budgeted separately`() {
        // A lock that comes and goes on a page that keeps rejecting.
        val slot = BottomSpacerSlot()
        slot.accept(slot.decideOnLoad(height)!!, 0, height)
        var measured = 0
        repeat(40) { i ->
            height += 100
            val t = slot.decideOnTouch(height) ?: return@repeat
            val locked = i % 2 == 0
            if (!locked) measured++
            slot.accept(t, if (locked) null else 0, height)
        }
        assertEquals(SPACER_MAX_TOUCH_ATTEMPTS, measured)
        assertEquals(SPACER_MAX_TOUCH_ATTEMPTS, slot.touchAttempts)
    }

    @Test
    fun `a touch while a decision is in flight does not start another`() {
        val slot = BottomSpacerSlot()
        val first = slot.decideOnLoad(height)!!
        assertNull(slot.decideOnTouch(height))
        assertNull(slot.decideOnLoad(height))
        slot.accept(first, null, height)
        assertEquals(SpacerState.Pending, slot.state)
        assertEquals(0, slot.touchAttempts) // the refused touch spent nothing
    }

    @Test
    fun `an answer from a replaced document is dropped`() {
        val slot = BottomSpacerSlot()
        val stale = slot.decideOnLoad(height)!!
        slot.startDocument()
        slot.accept(stale, 82, height)
        assertEquals(SpacerState.Pending, slot.state)
        assertNotNull(slot.decideOnLoad(height))
    }

    @Test
    fun `a new document starts undecided`() {
        val slot = BottomSpacerSlot()
        slot.accept(slot.decideOnLoad(height)!!, 82, height)
        slot.simulate(slot.touches(3)) { null }
        slot.startDocument()
        assertEquals(SpacerState.Pending, slot.state)
        assertEquals(0, slot.touchAttempts)
        assertEquals(0, slot.discountCssPx)
    }

    @Test
    fun `a width change decides afresh and supersedes a pass in flight`() {
        val slot = BottomSpacerSlot()
        slot.accept(slot.decideOnLoad(height)!!, 82, height)
        val inFlight = slot.decideOnWidthChange()!!
        val rotated = slot.decideOnWidthChange()!!
        slot.accept(inFlight, 58, height)
        assertEquals(SpacerState.Kept(82, stale = true), slot.state) // superseded answer ignored
        slot.accept(rotated, 106, height)
        assertEquals(SpacerState.Kept(106), slot.state)
        // …and a rejected document gets a fresh chance too.
        slot.accept(slot.decideOnWidthChange()!!, 0, height)
        assertEquals(SpacerState.Rejected(height), slot.state)
        assertNotNull(slot.decideOnWidthChange())
    }

    @Test
    fun `a width change under a scroll lock keeps the spacer, redone once the lock lifts`() {
        // Kept page, lightbox sets body{overflow:hidden}, user rotates: the
        // pass leaves our sheet alone and answers pending.
        val slot = BottomSpacerSlot()
        slot.accept(slot.decideOnLoad(height)!!, 82, height)
        slot.accept(slot.decideOnWidthChange()!!, null, height)
        assertEquals(SpacerState.Kept(82, stale = true), slot.state)
        assertEquals(82, slot.discountCssPx) // still on the page
        slot.accept(slot.decideOnTouch(height)!!, null, height) // still locked
        slot.accept(slot.decideOnTouch(height)!!, 58, height) // unlocked
        assertEquals(SpacerState.Kept(58), slot.state)
        assertNull(slot.decideOnTouch(height)) // final again
    }

    @Test
    fun `an SPA route change re-decides only when our sheet has gone`() {
        val slot = BottomSpacerSlot()
        slot.accept(slot.decideOnLoad(height)!!, 82, height)
        val still = slot.checkOnHistoryChange()!!
        assertNull(slot.acceptPresence(still, present = true, height))
        assertEquals(SpacerState.Kept(82), slot.state)
        val gone = slot.checkOnHistoryChange()!!
        val redo = slot.acceptPresence(gone, present = false, height)!!
        assertEquals(0, slot.discountCssPx)
        slot.accept(redo, 82, height)
        assertEquals(SpacerState.Kept(82), slot.state)
    }

    @Test
    fun `a kept document checks its sheet on touch-down only once its height changed`() {
        val slot = BottomSpacerSlot()
        slot.accept(slot.decideOnLoad(height)!!, 82, height)
        assertNull(slot.checkOnTouch(height)) // unchanged: no script
        // A theme toggle assigns `adoptedStyleSheets = [theme]`: our sheet
        // and its 82 px go, with no history change to notice it.
        height -= 82
        val check = slot.checkOnTouch(height)!!
        val redo = slot.acceptPresence(check, present = false, height)!!
        assertEquals(0, slot.discountCssPx) // the discount does not outlive the sheet
        height += 82
        slot.accept(redo, 82, height)
        assertEquals(SpacerState.Kept(82), slot.state)
        assertNull(slot.checkOnTouch(height))
    }

    @Test
    fun `a kept sheet found present is not checked again until the height moves`() {
        val slot = BottomSpacerSlot()
        slot.accept(slot.decideOnLoad(height)!!, 82, height)
        height = 3000 // lazy images loaded
        val check = slot.checkOnTouch(height)!!
        assertNull(slot.acceptPresence(check, present = true, height))
        assertEquals(SpacerState.Kept(82), slot.state)
        assertNull(slot.checkOnTouch(height))
        assertNull(slot.decideOnTouch(height))
    }

    @Test
    fun `touch presence checks are for kept documents only`() {
        val slot = BottomSpacerSlot()
        assertNull(slot.checkOnTouch(height)) // pending
        slot.accept(slot.decideOnLoad(height)!!, 0, height)
        assertNull(slot.checkOnTouch(height + 500)) // rejected
    }

    @Test
    fun `history changes on undecided or rejected documents run nothing`() {
        val slot = BottomSpacerSlot()
        assertNull(slot.checkOnHistoryChange())
        slot.accept(slot.decideOnLoad(height)!!, 0, height)
        assertNull(slot.checkOnHistoryChange())
    }

    @Test
    fun `a history check from a replaced document is dropped`() {
        val slot = BottomSpacerSlot()
        slot.accept(slot.decideOnLoad(height)!!, 82, height)
        val check = slot.checkOnHistoryChange()!!
        slot.startDocument()
        assertNull(slot.acceptPresence(check, present = false, height))
    }

    @Test
    fun `nothing is measured between page start and the new document's first paint`() {
        // Address-bar load: onPageStarted fires while the old page, rejected
        // at 3000 px, is still on screen and still being scrolled.
        val slot = BottomSpacerSlot()
        slot.accept(slot.decideOnLoad(3000)!!, 0, 3000)
        slot.startDocument()
        assertNull(slot.decideOnTouch(3000)) // would measure the outgoing page
        assertNull(slot.checkOnTouch(3000))
        assertNull(slot.decideOnWidthChange())
        assertEquals(SpacerState.Pending, slot.state)
        assertEquals(0, slot.touchAttempts)
        // First paint of a 2000 px article: decided on its own measure.
        slot.accept(slot.decideOnLoad(2000)!!, 82, 2000)
        assertEquals(SpacerState.Kept(82), slot.state)
    }

    @Test
    fun `touch-downs retry again once the new document has painted`() {
        val slot = BottomSpacerSlot()
        slot.startDocument()
        assertNull(slot.decideOnTouch(height))
        slot.accept(slot.decideOnLoad(height)!!, null, height) // locked at first paint
        assertNotNull(slot.decideOnTouch(height))
    }

    @Test
    fun `a kept spacer's height is re-read after its frame, so the first touch runs nothing`() {
        // accept() got getContentHeight() from the frame before the rule.
        val slot = BottomSpacerSlot()
        val t = slot.decideOnLoad(height)!!
        slot.accept(t, 82, height)
        slot.settleKept(t, height + 82)
        assertNull(slot.checkOnTouch(height + 82))
        assertNotNull(slot.checkOnTouch(height)) // the sheet's height went: look
    }

    @Test
    fun `a late frame reading does not speak for a later decision or document`() {
        val slot = BottomSpacerSlot()
        val first = slot.decideOnLoad(height)!!
        slot.accept(first, 82, height)
        val rotated = slot.decideOnWidthChange()!!
        slot.accept(rotated, 106, height + 82)
        slot.settleKept(first, 9999) // superseded by the rotation's answer
        assertNull(slot.checkOnTouch(height + 82))
        slot.settleKept(rotated, height + 106)
        assertNull(slot.checkOnTouch(height + 106))
        slot.startDocument()
        slot.settleKept(rotated, 1234)
        assertEquals(SpacerState.Pending, slot.state)
    }

    // ---- pull-to-refresh discount ------------------------------------

    // A 2.625x device at default zoom: the WebView is 2000 px tall, i.e.
    // ~762 CSS px.
    private val scale = 2.625f
    private val viewPx = 2000
    private val viewportCss = 762

    @Test
    fun `a page scrolled only by the spacer does not count as scrolling`() {
        // Exactly one viewport of content + 82 px of measured growth: before
        // the spacer it did not scroll, so pull-to-refresh must stay unarmed.
        assertFalse(
            documentScrollsPastSpacer(
                canScrollDown = true,
                contentHeightCss = viewportCss + 82, // ceil(2000 / 2.625) + spacer
                spacerCss = 82,
                viewHeightPx = viewPx,
                scale = scale,
            ),
        )
        assertFalse(
            pullToRefreshArmed(
                scrollY = 0,
                documentScrollsDown = documentScrollsPastSpacer(
                    true, viewportCss + 82, 82, viewPx, scale,
                ),
                rootBlocksVerticalPan = false,
            ),
        )
    }

    @Test
    fun `a 100vh flex page reads as not scrolling despite getContentHeight rounding`() {
        // Measured on the freedom AVD: WebView 2264 px at 2.625x, a
        // `min-height: 100vh` flex body (862.5 CSS px) plus the 82 px
        // spacer reported contentHeight = 946.
        assertFalse(documentScrollsPastSpacer(true, 946, 82, 2264, 2.625f))
    }

    @Test
    fun `a long article still scrolls`() {
        assertTrue(documentScrollsPastSpacer(true, 5000, 82, viewPx, scale))
    }

    @Test
    fun `without a spacer the WebView's own answer stands`() {
        assertTrue(documentScrollsPastSpacer(true, 0, 0, viewPx, scale))
        assertFalse(documentScrollsPastSpacer(false, 5000, 0, viewPx, scale))
    }

    @Test
    fun `a page that cannot scroll never does`() {
        assertFalse(documentScrollsPastSpacer(false, 5000, 82, viewPx, scale))
    }

    @Test
    fun `a page a little taller than the viewport keeps scrolling`() {
        // 40 CSS px of real overflow beyond the viewport, plus the spacer.
        assertTrue(documentScrollsPastSpacer(true, viewportCss + 40 + 82, 82, viewPx, scale))
    }

    // ---- reserved mode (#66) -----------------------------------------

    private fun keptSlot(): BottomSpacerSlot {
        val slot = BottomSpacerSlot()
        slot.startDocument()
        val t = slot.decideOnLoad(3000)!!
        slot.accept(t, 82, 3000)
        return slot
    }

    @Test
    fun `reserved mode drops a kept spacer and its pull-to-refresh discount`() {
        val slot = keptSlot()
        assertEquals(82, slot.discountCssPx)
        assertTrue(slot.suspend())
        assertEquals(0, slot.discountCssPx)
        assertFalse(slot.suspend()) // already suspended: no second removal
    }

    @Test
    fun `while reserved no pass runs, whatever happens`() {
        val slot = keptSlot()
        slot.suspend()
        assertNull(slot.decideOnLoad(4000))
        assertNull(slot.decideOnTouch(5000))
        assertNull(slot.checkOnTouch(6000))
        assertNull(slot.decideOnWidthChange())
        assertNull(slot.checkOnHistoryChange())
        assertEquals(0, slot.discountCssPx)
    }

    @Test
    fun `a pass in flight when the tab goes reserved is dropped`() {
        val slot = BottomSpacerSlot()
        slot.startDocument()
        val t = slot.decideOnLoad(3000)!!
        slot.suspend()
        slot.accept(t, 82, 3000)
        assertEquals(0, slot.discountCssPx)
        slot.settleKept(t, 3082)
        assertEquals(SpacerState.Pending, slot.state)
    }

    @Test
    fun `back to overlay decides afresh, with fresh budgets`() {
        val slot = keptSlot()
        slot.suspend()
        val t = slot.resume()
        assertNotNull(t)
        slot.accept(t!!, 82, 3000)
        assertEquals(82, slot.discountCssPx)
        assertNull(slot.resume()) // not suspended
    }

    @Test
    fun `a new document is never born suspended`() {
        val slot = keptSlot()
        slot.suspend()
        slot.startDocument()
        assertFalse(slot.suspended)
        assertNotNull(slot.decideOnLoad(3000))
    }

    @Test
    fun `resuming before first paint waits for it`() {
        val slot = BottomSpacerSlot()
        slot.startDocument()
        slot.suspend()
        assertNull(slot.resume())
        assertNotNull(slot.decideOnLoad(3000))
    }
}
