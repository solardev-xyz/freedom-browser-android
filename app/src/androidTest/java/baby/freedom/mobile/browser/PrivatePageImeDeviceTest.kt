package baby.freedom.mobile.browser

import android.view.inputmethod.EditorInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A private tab's (#86) page fields ask the keyboard not to learn, like
 * the tab's address and find bars: Chromium only sets
 * [EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING] for an off-the-record
 * profile, which a WebView profile never is. Drives the real
 * [PageWebView] the browser builds for every tab.
 */
@RunWith(AndroidJUnit4::class)
class PrivatePageImeDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun imeOptionsOf(private: Boolean): Int {
        var options = 0
        instrumentation.runOnMainSync {
            val view = PageWebView(instrumentation.targetContext, private = private)
            try {
                val info = EditorInfo().apply { imeOptions = EditorInfo.IME_ACTION_GO }
                view.onCreateInputConnection(info)
                options = info.imeOptions
            } finally {
                view.destroy()
            }
        }
        return options
    }

    @Test
    fun privateTabPageFieldsAskTheKeyboardNotToLearn() {
        assertEquals(
            EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
            imeOptionsOf(private = true) and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
        )
    }

    @Test
    fun normalTabPageFieldsAreLeftAlone() {
        assertEquals(0, imeOptionsOf(private = false) and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
    }
}
