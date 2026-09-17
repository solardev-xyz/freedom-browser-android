package baby.freedom.mobile.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Closing the keyboard closes the editor.
 *
 * The capsule's editing morph is keyed on the address field's focus, and
 * the IME eats the back press that dismisses it — so without this rule a
 * user who backs out of the keyboard is left staring at a full-height,
 * control-less editor bar with no keyboard under it.
 */
class ImeDismissalTest {

    /**
     * Tapping the pill: focus lands first, the IME animates up over the
     * following frames. Bouncing focus here would make the address bar
     * untappable.
     */
    @Test
    fun `focus arriving before the keyboard does not end editing`() {
        assertFalse(
            imeDismissalEndsEditing(
                addressFocused = true,
                keyboardVisible = false,
                keyboardWasSeen = false,
            ),
        )
    }

    @Test
    fun `an open keyboard keeps the editor open`() {
        assertFalse(
            imeDismissalEndsEditing(
                addressFocused = true,
                keyboardVisible = true,
                keyboardWasSeen = true,
            ),
        )
    }

    /** System back with the address field focused: the reported bug. */
    @Test
    fun `dismissing an open keyboard ends editing`() {
        assertTrue(
            imeDismissalEndsEditing(
                addressFocused = true,
                keyboardVisible = false,
                keyboardWasSeen = true,
            ),
        )
    }

    /**
     * A keyboard raised by a form field *inside the page* goes up and
     * down without the address bar ever being involved.
     */
    @Test
    fun `a keyboard the address bar never owned ends nothing`() {
        assertFalse(
            imeDismissalEndsEditing(
                addressFocused = false,
                keyboardVisible = false,
                keyboardWasSeen = true,
            ),
        )
    }

    /**
     * Hardware keyboard: the field has focus and typing works, but no
     * IME inset ever appears, so there is nothing to have been
     * dismissed.
     */
    @Test
    fun `a session that never saw a keyboard is never ended by one`() {
        assertFalse(
            imeDismissalEndsEditing(
                addressFocused = true,
                keyboardVisible = false,
                keyboardWasSeen = false,
            ),
        )
    }
}
