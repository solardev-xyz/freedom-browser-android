package baby.freedom.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The address pill's trailing slot is a fixed-size square that is always
 * reserved, so the only thing that can go wrong is *which* control it
 * holds. These cases pin the priority order that keeps the capsule
 * honest: what the user is doing beats what the network is doing.
 */
class CapsuleTrailingControlTest {

    private fun control(
        addressFocused: Boolean = false,
        addressBarEdited: Boolean = false,
        editBufferEmpty: Boolean = false,
        loading: Boolean = false,
        canReload: Boolean = true,
    ) = capsuleTrailingControl(
        addressFocused = addressFocused,
        addressBarEdited = addressBarEdited,
        editBufferEmpty = editBufferEmpty,
        loading = loading,
        canReload = canReload,
    )

    @Test
    fun `resting on a page offers reload`() {
        assertEquals(CapsuleTrailingControl.Reload, control())
    }

    @Test
    fun `home tab with no address offers nothing`() {
        assertEquals(CapsuleTrailingControl.None, control(canReload = false))
    }

    @Test
    fun `a running load offers stop`() {
        assertEquals(CapsuleTrailingControl.Stop, control(loading = true))
    }

    @Test
    fun `stop is offered even before there is anything to reload`() {
        // First navigation of a fresh tab: nothing committed yet, but the
        // resolve is already running and must be abortable.
        assertEquals(
            CapsuleTrailingControl.Stop,
            control(loading = true, canReload = false),
        )
    }

    @Test
    fun `typing offers clear`() {
        assertEquals(
            CapsuleTrailingControl.Clear,
            control(addressFocused = true, addressBarEdited = true),
        )
    }

    @Test
    fun `clear wins over stop while the user is typing`() {
        // A page loading behind the keyboard must not steal the slot the
        // user is actively using.
        assertEquals(
            CapsuleTrailingControl.Clear,
            control(addressFocused = true, addressBarEdited = true, loading = true),
        )
    }

    @Test
    fun `focused but untouched pill offers no clear`() {
        // Tapping the pill select-alls the URL; there is nothing to
        // "start over" from yet, so the slot stays empty rather than
        // inviting a destructive tap.
        assertEquals(
            CapsuleTrailingControl.None,
            control(addressFocused = true, addressBarEdited = false),
        )
    }

    @Test
    fun `cleared buffer drops back out of clear`() {
        assertEquals(
            CapsuleTrailingControl.None,
            control(addressFocused = true, addressBarEdited = true, editBufferEmpty = true),
        )
    }

    @Test
    fun `reload is never offered while the editor is open`() {
        // The editor's slot belongs to the edit; a reload button under
        // the user's thumb mid-typing is a misfire waiting to happen.
        assertEquals(
            CapsuleTrailingControl.None,
            control(addressFocused = true, addressBarEdited = false, canReload = true),
        )
    }

    @Test
    fun `after submit the slot becomes stop, not clear`() {
        // submit() resets `addressBarEdited` to dismiss the suggestions
        // panel but deliberately leaves focus alone for a beat.
        assertEquals(
            CapsuleTrailingControl.Stop,
            control(addressFocused = true, addressBarEdited = false, loading = true),
        )
    }

    @Test
    fun `loading mirrors the tab's resolve and progress state`() {
        val state = BrowserState(id = 1L)
        assertEquals(false, isCapsuleLoading(state))

        state.progress = 1
        assertEquals(true, isCapsuleLoading(state))

        state.progress = 99
        assertEquals(true, isCapsuleLoading(state))

        // `-1` is the only idle spelling: the chrome client folds both 0
        // and 100 into it, and `stopProgress()` resets to it. A load
        // that finished must not leave the edge trace lit.
        state.progress = -1
        assertEquals(false, isCapsuleLoading(state))
    }

    @Test
    fun `a freshly committed navigation counts as loading`() {
        // `onPageStarted` writes progress = 0 at navigation commit,
        // before the first percentage arrives. That window is a load,
        // not an idle tab — otherwise the edge trace goes out and the
        // trailing slot flips Stop → Reload → Stop mid-navigation.
        val state = BrowserState(id = 3L)
        state.progress = 0
        assertEquals(true, isCapsuleLoading(state))
    }

    @Test
    fun `a resolve with no percentage yet counts as loading`() {
        val state = BrowserState(id = 4L)
        state.resolving = true
        assertEquals(true, isCapsuleLoading(state))
    }

    @Test
    fun `clearing the buffer mid-load does not arm stop under the finger`() {
        // × resets `addressBarEdited` and empties the buffer while the
        // previous page is still loading behind the keyboard. The slot
        // the finger just left must not become a primary-tinted Stop.
        assertEquals(
            CapsuleTrailingControl.None,
            control(
                addressFocused = true,
                addressBarEdited = false,
                editBufferEmpty = true,
                loading = true,
            ),
        )
    }

    @Test
    fun `stopProgress clears both busy signals`() {
        val state = BrowserState(id = 2L)
        state.progress = 42
        assertEquals(true, isCapsuleLoading(state))

        state.stopProgress()
        assertEquals(false, isCapsuleLoading(state))
        assertEquals(-1, state.progress)
        assertEquals(false, state.resolving)
    }
}
