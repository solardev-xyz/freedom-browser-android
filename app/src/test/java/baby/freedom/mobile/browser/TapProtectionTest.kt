package baby.freedom.mobile.browser

import android.view.MotionEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #240: which touches a wallet confirm button refuses as another app's overlay steering the tap. */
class TapProtectionTest {
    private val android11 = 30
    private val android12 = 31

    @Test
    fun `a touch through another app's window is refused on every version`() {
        assertTrue(touchObscured(MotionEvent.FLAG_WINDOW_IS_OBSCURED, android11))
        assertTrue(touchObscured(MotionEvent.FLAG_WINDOW_IS_OBSCURED, android12))
        assertTrue(touchObscured(MotionEvent.FLAG_WINDOW_IS_OBSCURED or MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED, 36))
    }

    @Test
    fun `a partial cover is refused only where overlays can't be hidden`() {
        // Android 11: an overlay can sit over the amount while the tap point stays clear.
        assertTrue(touchObscured(MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED, android11))
        // Android 12+: other apps' overlays are hidden, so what's left is the system's or a PiP window.
        assertFalse(touchObscured(MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED, android12))
    }

    @Test
    fun `an unobscured touch is the user's`() {
        assertFalse(touchObscured(0, android11))
        assertFalse(touchObscured(0, android12))
        // Unrelated flags don't count.
        assertFalse(touchObscured(MotionEvent.FLAG_CANCELED, android11))
    }
}
