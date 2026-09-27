package baby.freedom.mobile.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Only the load a user's submit scheduled is the one they named (#173,
 * R1-F3, R2-F2): not a load that happens to start before it, nor one
 * scheduled after it by something other than their submit.
 */
class UserNamedLoadTest {

    @Test
    fun `the load a user's submit schedules is named, once`() {
        val state = BrowserState(id = 1L)
        state.loadUrl("https://meet.google.com/abc-defg-hij", namedByUser = true)
        assertTrue(state.takeUserNamedLoad(state.pendingUrl))
        assertFalse(state.takeUserNamedLoad(state.pendingUrl))
    }

    @Test
    fun `a stray load before the scheduled one doesn't take it`() {
        val state = BrowserState(id = 1L)
        state.loadUrl("https://meet.google.com/abc-defg-hij", namedByUser = true)
        // The WebView's own blank first paint, a retry: another URL.
        assertFalse(state.takeUserNamedLoad(ABOUT_BLANK))
        // …and the stray take spent it: fail closed, never on a later load.
        assertFalse(state.takeUserNamedLoad(state.pendingUrl))
    }

    @Test
    fun `a load scheduled by anything but the user's submit is not named`() {
        val state = BrowserState(id = 1L)
        state.loadUrl("https://a.example/", namedByUser = true)
        // A second schedule (an error page, a restore) without a submit.
        state.loadUrl("https://b.example/")
        assertFalse(state.takeUserNamedLoad(state.pendingUrl))
    }

    @Test
    fun `a load after an async submit, before it resolves, is not named`() {
        // R2-F2: an ENS / bzz submit schedules its load only once the
        // name resolves. A Back tap meanwhile loads
        // `javascript:history.back()`, which must not be the named one.
        val state = BrowserState(id = 1L)
        state.loadUrl("javascript:history.back()")
        assertFalse(state.takeUserNamedLoad(state.pendingUrl))
        // The resolved load itself still is.
        state.loadUrl("ens://name.eth", namedByUser = true)
        assertTrue(state.takeUserNamedLoad(state.pendingUrl))
    }
}
