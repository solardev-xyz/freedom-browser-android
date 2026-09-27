package baby.freedom.mobile.browser

import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class PrivateTextInputTest {
    private val base = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_FULLSCREEN

    @Test
    fun privateTabFieldsAskTheKeyboardNotToLearn() {
        val options = tabImeOptions(base, private = true)
        assertEquals(
            EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
            options and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
        )
        // The action and every flag the field already set survive.
        assertEquals(base, options and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING.inv())
    }

    @Test
    fun normalTabFieldsAreLeftAlone() {
        assertEquals(base, tabImeOptions(base, private = false))
    }
}
