package baby.freedom.mobile.browser

import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a *page-initiated* submit is allowed to take away from the user
 * (#35).
 *
 * [PendingAddressTest] covers what such a submit may write into the
 * address bar; this is the other half — a renderer submit shares the
 * one submit path with the user's own, and everything that path tears
 * down on the way (the keyboard, the address field's focus and edit
 * buffer, the probe gating a navigation already in flight) belongs to
 * the user, not to whatever page happens to be on screen. A page
 * looping `location.href='ens://…'` fires this path on every tick.
 */
class RendererSubmitTest {

    @Test
    fun `the user's own submit closes the editor`() {
        // Hitting Go settles the destination: keyboard down, focus off
        // the field, latch reset for the next tap.
        assertTrue(submitEndsAddressEditing(SubmitSource.User))
    }

    @Test
    fun `a page's submit leaves the editor alone`() {
        // Dropping focus re-seeds the field from the tab's committed
        // address, so this teardown is what discarded a half-typed URL.
        assertFalse(submitEndsAddressEditing(SubmitSource.Renderer))
    }

    @Test
    fun `a page may not cancel the probe the user is waiting on`() {
        assertFalse(
            submitSupersedesPendingProbe(
                pending = SubmitSource.User,
                incoming = SubmitSource.Renderer,
            ),
        )
    }

    @Test
    fun `the user supersedes anything in flight, including their own`() {
        assertTrue(
            submitSupersedesPendingProbe(
                pending = SubmitSource.User,
                incoming = SubmitSource.User,
            ),
        )
        assertTrue(
            submitSupersedesPendingProbe(
                pending = SubmitSource.Renderer,
                incoming = SubmitSource.User,
            ),
        )
    }

    @Test
    fun `a page submitting on an idle tab, or over its own probe, still works`() {
        // In-page link clicks and the error page's "Try Again" are the
        // ordinary traffic on this path — nothing about them changes.
        assertTrue(submitSupersedesPendingProbe(pending = null, incoming = SubmitSource.Renderer))
        assertTrue(
            submitSupersedesPendingProbe(
                pending = SubmitSource.Renderer,
                incoming = SubmitSource.Renderer,
            ),
        )
        assertTrue(submitSupersedesPendingProbe(pending = null, incoming = SubmitSource.User))
    }

    @Test
    fun `a tab records who asked for the probe it is running`() {
        val state = BrowserState(id = 1L)
        assertNull(state.pendingProbeSource)

        val userProbe = Job()
        state.beginPendingProbe(userProbe, SubmitSource.User)
        assertEquals(SubmitSource.User, state.pendingProbeSource)
        assertSame(userProbe, state.pendingProbeJob)

        // …and forgets it once the probe is done or cancelled.
        state.finishPendingProbe(userProbe)
        assertNull(state.pendingProbeSource)
        assertNull(state.pendingProbeJob)
    }

    @Test
    fun `cancelling clears the source so the next submit starts clean`() {
        val state = BrowserState(id = 1L)
        val probe = Job()
        state.beginPendingProbe(probe, SubmitSource.User)

        state.cancelPendingProbe()
        assertTrue(probe.isCancelled)
        assertNull(state.pendingProbeSource)
        assertTrue(
            submitSupersedesPendingProbe(state.pendingProbeSource, SubmitSource.Renderer),
        )
    }

    @Test
    fun `a superseded probe's late teardown may not deregister its successor`() {
        // The cancelled coroutine's `finally` runs a beat *after* the
        // submit that cancelled it has registered its own probe. An
        // unconditional clear there would blank `pendingProbeSource`
        // while the user's fresh navigation is still resolving — and a
        // looping page's next tick would then be free to cancel it.
        val state = BrowserState(id = 1L)
        val first = Job()
        state.beginPendingProbe(first, SubmitSource.User)

        state.cancelPendingProbe()
        val second = Job()
        state.beginPendingProbe(second, SubmitSource.User)

        first.cancel()
        state.finishPendingProbe(first)

        assertSame(second, state.pendingProbeJob)
        assertEquals(SubmitSource.User, state.pendingProbeSource)
        assertFalse(
            submitSupersedesPendingProbe(state.pendingProbeSource, SubmitSource.Renderer),
        )
    }

    @Test
    fun `a page navigation still ends the probe it started itself`() {
        val state = BrowserState(id = 1L)
        val probe = Job()
        state.beginPendingProbe(probe, SubmitSource.Renderer)
        state.finishPendingProbe(probe)
        assertNull(state.pendingProbeJob)
        assertNull(state.pendingProbeSource)
    }

    // ---- what a navigation commit does to a probe (#54) -------------
    //
    // A probe outlives the submit that started it — it waits for the
    // node (up to NODE_READY_TIMEOUT_MS, longer on a cold one) and only
    // then navigates the tab. The rule for who may end one early is the
    // same as for submits: a page's probe belongs to the page, the
    // user's is the user's.

    private val probeTarget = "https://swarm.eth.ens.freedom.baby/"
    private val otherDocument = "https://example.com/"

    @Test
    fun `a page's probe dies with the page that started it`() {
        assertTrue(
            commitCancelsPendingProbe(
                probeSource = SubmitSource.Renderer,
                probeTarget = probeTarget,
                committedUrl = otherDocument,
            ),
        )
    }

    @Test
    fun `the user's probe survives a commit it did not ask for`() {
        // The user types `swarm.eth` and taps a link on the page they
        // are leaving while it resolves: the resolve is still theirs,
        // and only their next submit or Stop ends it.
        assertFalse(
            commitCancelsPendingProbe(
                probeSource = SubmitSource.User,
                probeTarget = probeTarget,
                committedUrl = otherDocument,
            ),
        )
    }

    @Test
    fun `a probe's own navigation is not the one that supersedes it`() {
        assertFalse(
            commitCancelsPendingProbe(
                probeSource = SubmitSource.Renderer,
                probeTarget = probeTarget,
                committedUrl = probeTarget,
            ),
        )
    }

    @Test
    fun `a commit on an idle tab cancels nothing`() {
        assertFalse(
            commitCancelsPendingProbe(
                probeSource = null,
                probeTarget = null,
                committedUrl = otherDocument,
            ),
        )
    }

    @Test
    fun `an unidentifiable commit still ends a page's probe`() {
        // Nothing to match on is no evidence the commit is the probe's
        // own, and the page-started probe is the one that must not
        // outlive an unknown navigation.
        assertTrue(
            commitCancelsPendingProbe(
                probeSource = SubmitSource.Renderer,
                probeTarget = null,
                committedUrl = otherDocument,
            ),
        )
        assertTrue(
            commitCancelsPendingProbe(
                probeSource = SubmitSource.Renderer,
                probeTarget = probeTarget,
                committedUrl = null,
            ),
        )
    }

    @Test
    fun `a tab remembers where its probe is headed, in loadable form`() {
        val state = BrowserState(id = 1L)
        val probe = Job()
        state.beginPendingProbe(probe, SubmitSource.Renderer, target = "ens://swarm.eth")
        // `onPageStarted` reports the URL the WebView actually loaded,
        // so the target is stored the same way.
        assertEquals(Gateways.toLoadable("ens://swarm.eth"), state.pendingProbeTarget)

        state.cancelPendingProbe()
        assertNull(state.pendingProbeTarget)
    }

    @Test
    fun `the commit that cancels a page's probe leaves the tab idle`() {
        // The whole of #54: a page starts an `ens://` probe in the gap
        // before the user's own plain http(s) Go unloads it — that path
        // registers no probe of its own, so nothing supersedes this one
        // — and then the http(s) document commits.
        val state = BrowserState(id = 1L)
        val probe = Job()
        state.beginPendingProbe(probe, SubmitSource.Renderer, target = "ens://evil.eth")

        if (commitCancelsPendingProbe(
                state.pendingProbeSource,
                state.pendingProbeTarget,
                committedUrl = otherDocument,
            )
        ) {
            state.cancelPendingProbe()
        }

        assertTrue(probe.isCancelled)
        assertNull(state.pendingProbeJob)
        assertNull(state.pendingProbeSource)
        assertNull(state.pendingProbeTarget)
    }

    @Test
    fun `landing on the blank home entry ends a page's probe too`() {
        // Home is a document like any other. The hole this closes: a
        // page runs `location.href='bzz://…'` and then `history.back()`
        // onto the blank home entry — a renderer-initiated history
        // navigation that fires no `onPageStarted` at all, only
        // `onPageFinished`. Both `about:blank` branches must cancel, or
        // the tab sits on Home with the capsule still resolving and is
        // navigated off it minutes later.
        assertTrue(
            commitCancelsPendingProbe(
                probeSource = SubmitSource.Renderer,
                probeTarget = probeTarget,
                committedUrl = "about:blank",
            ),
        )
    }

    @Test
    fun `the user's probe still survives a trip through home`() {
        // The user types `swarm.eth` and taps Home while it resolves:
        // the resolve is still theirs, and only their next submit or
        // Stop ends it.
        assertFalse(
            commitCancelsPendingProbe(
                probeSource = SubmitSource.User,
                probeTarget = probeTarget,
                committedUrl = "about:blank",
            ),
        )
    }

    @Test
    fun `the same commit leaves the user's probe running`() {
        val state = BrowserState(id = 1L)
        val probe = Job()
        state.beginPendingProbe(probe, SubmitSource.User, target = "ens://swarm.eth")

        if (commitCancelsPendingProbe(
                state.pendingProbeSource,
                state.pendingProbeTarget,
                committedUrl = otherDocument,
            )
        ) {
            state.cancelPendingProbe()
        }

        assertFalse(probe.isCancelled)
        assertSame(probe, state.pendingProbeJob)
    }
}
