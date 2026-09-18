package baby.freedom.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The other half of the address bar's trust contract (#39): what the
 * committed address falls back to when the user aborts a navigation the
 * bar was already naming.
 *
 * [pendingAddressBarText] lets a user-named destination into the bar
 * before it commits. Stop cancels the navigation — so the bar has to
 * give the label back to the page that is still on screen, or the
 * capsule is left vouching for a site that never loaded while the
 * reload control beside it would fetch a different one.
 */
class StopRollbackTest {

    @Test
    fun `stopping before the commit gives the label back to the loaded page`() {
        val submitted = pendingAddressBarText(
            current = "https://news.example/article",
            submitted = "vitalik.eth",
            source = SubmitSource.User,
        )
        assertEquals("vitalik.eth", submitted)

        assertEquals(
            "https://news.example/article",
            addressBarTextAfterStop(
                committedUrl = "https://news.example/article",
                pending = submitted,
            ),
        )
    }

    @Test
    fun `stopping after the commit changes nothing`() {
        // `onPageStarted` wrote the same display string into both, so
        // the rollback is a no-op and Stop needs no "has it committed?"
        // test of its own.
        assertEquals(
            "example.com/x",
            addressBarTextAfterStop(committedUrl = "example.com/x", pending = "example.com/x"),
        )
    }

    @Test
    fun `the display form is what comes back, not the gateway URL`() {
        // `state.url` holds the same display string the bar shows (see
        // BrowserWebView.onPageStarted), so a stopped navigation away
        // from an ENS page lands back on `swarm.eth/docs` rather than
        // on the loopback gateway URL that served it.
        assertEquals(
            "swarm.eth/docs",
            addressBarTextAfterStop(committedUrl = "swarm.eth/docs", pending = "example.com"),
        )
    }

    @Test
    fun `an aborted load's finish callback may not rename the tab`() {
        // Stop (and a second navigation superseding the first) leaves
        // Chromium firing `onPageFinished` for the URL that never
        // committed, while `WebView.getUrl()` still names the page on
        // screen. Without this guard that callback lands *after* the
        // rollback above and undoes it.
        assertFalse(
            finishedLoadIsCurrent(
                finishedUrl = "http://10.0.2.2:8759/slow",
                currentUrl = "http://127.0.0.1:8759/dark.html",
            ),
        )
    }

    @Test
    fun `an ordinary finish is adopted`() {
        assertTrue(
            finishedLoadIsCurrent(
                finishedUrl = "https://example.com/x",
                currentUrl = "https://example.com/x",
            ),
        )
    }

    @Test
    fun `a WebView that says nothing is taken at face value`() {
        // Pre-guard behaviour, kept for anything that reports no
        // current URL (a fresh WebView, a synthetic callback in a test
        // double): the finish is adopted rather than silently dropped.
        assertTrue(finishedLoadIsCurrent(finishedUrl = "https://example.com", currentUrl = null))
        assertTrue(finishedLoadIsCurrent(finishedUrl = null, currentUrl = "https://example.com"))
    }

    @Test
    fun `a stopped load's late progress callback may not re-light the capsule`() {
        // The other half of the same abort: Chromium answers
        // `stopLoading()` on an uncommitted navigation with one last
        // progress callback carrying the percentage it died at — not
        // 100, and never followed by anything, since that document
        // neither commits nor finishes. Adopted, it puts the trace and
        // the Stop control back for good (#41).
        assertEquals(
            -1,
            progressForCallback(newProgress = 37, isHomeSentinel = false, aborted = true),
        )
    }

    @Test
    fun `an ordinary progress callback still draws`() {
        assertEquals(
            37,
            progressForCallback(newProgress = 37, isHomeSentinel = false, aborted = false),
        )
        // Both ends of Chromium's counter are the idle sentinel.
        assertEquals(
            -1,
            progressForCallback(newProgress = 100, isHomeSentinel = false, aborted = false),
        )
        // …and the home overlay is never a loading page.
        assertEquals(
            -1,
            progressForCallback(newProgress = 37, isHomeSentinel = true, aborted = false),
        )
    }

    @Test
    fun `stop latches the tab, a fresh navigation opens it again`() {
        val state = BrowserState(id = 1L)
        assertFalse(state.loadAborted)

        state.stopProgress()
        assertTrue(state.loadAborted)
        assertEquals(-1, state.progress)

        // Reload / a typed URL / Home all route through loadUrl, so the
        // next load's progress is drawn as normal.
        state.loadUrl("https://example.com")
        assertFalse(state.loadAborted)
    }

    @Test
    fun `a navigation Chromium performs itself opens the latch at request time`() {
        // A link tap into an ordinary page starts ticking progress
        // before `onPageStarted`, so the latch has to open in
        // `shouldOverrideUrlLoading` or the first seconds draw nothing
        // (#41). Subresources never touch it.
        assertTrue(navigationOpensStopLatch(isForMainFrame = true, detoured = false))
        assertFalse(navigationOpensStopLatch(isForMainFrame = false, detoured = false))
    }

    @Test
    fun `a detoured navigation leaves the latch to the load it may never start`() {
        // The detour hands the URL to `submit()`, which the #35 gate
        // ignores when the tab's pending probe is the user's own: a page
        // looping `location.href='ens://…'` navigates nowhere, so
        // opening the latch for it would un-latch the load the user
        // stopped and let Chromium's one late progress callback re-light
        // the trace. An accepted submit opens the latch through
        // [BrowserState.loadUrl] instead.
        assertFalse(navigationOpensStopLatch(isForMainFrame = true, detoured = true))

        val state = BrowserState(id = 1L)
        state.stopProgress()
        assertTrue(state.loadAborted)
        // The tick the gate ignores: no navigation, so no latch change.
        assertFalse(
            navigationOpensStopLatch(
                isForMainFrame = true,
                detoured = submitDetourForNavigation("ens://attacker.eth", isForMainFrame = true),
            ),
        )
        assertTrue(state.loadAborted)
        assertEquals(
            -1,
            progressForCallback(newProgress = 37, isHomeSentinel = false, aborted = state.loadAborted),
        )
        // …and the accepted submit's own load still opens it.
        state.loadUrl("ens://mysite.eth")
        assertFalse(state.loadAborted)
    }

    @Test
    fun `a first navigation with nothing committed keeps what the user asked for`() {
        // Fresh tab, stopped mid-resolve: there is no previous page for
        // the label to misdescribe, and the pending address is the only
        // thing left that says what the user wanted — and the only
        // thing reload could re-try.
        assertEquals(
            "vitalik.eth",
            addressBarTextAfterStop(committedUrl = "", pending = "vitalik.eth"),
        )
    }
}
