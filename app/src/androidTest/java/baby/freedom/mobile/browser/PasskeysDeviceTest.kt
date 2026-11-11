package baby.freedom.mobile.browser

import android.os.Build
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [Passkeys] on a real WebView (#524), as the WebView itself reports the
 * mode back: what a tab gets on this device, that each mode the decision
 * can make actually takes, and that [PasskeyGate] moves it.
 */
@RunWith(AndroidJUnit4::class)
class PasskeysDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun readBack(set: (WebView) -> Unit): Int {
        var mode = -1
        instrumentation.runOnMainSync {
            val view = WebView(instrumentation.targetContext)
            try {
                set(view)
                mode = WebSettingsCompat.getWebAuthenticationSupport(view.settings)
            } finally {
                view.destroy()
            }
        }
        return mode
    }

    @Test
    fun tabs_get_the_mode_the_decision_names_for_this_device() {
        assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_AUTHENTICATION))
        val context = instrumentation.targetContext
        val major = Passkeys.majorVersion(WebViewCompat.getCurrentWebViewPackage(context)?.versionName)
        instrumentation.runOnMainSync { PasskeyGate.appVisible(true) }
        try {
            for (private in listOf(false, true)) {
                val expected = Passkeys.mode(true, major, Build.VERSION.SDK_INT, private)
                assertEquals(expected, readBack { PasskeyGate(it.settings, Passkeys.mode(context, private)) })
            }
            // A private tab is off whatever the WebView.
            assertEquals(
                WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_NONE,
                readBack { PasskeyGate(it.settings, Passkeys.mode(context, privateTab = true)) },
            )
        } finally {
            instrumentation.runOnMainSync { PasskeyGate.appVisible(false) }
        }
    }

    @Test
    fun the_gate_drops_browser_mode_for_a_device_document_and_with_the_app_away() {
        assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_AUTHENTICATION))
        val on = WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_BROWSER
        val off = WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_NONE
        val seen = mutableListOf<Int>()
        instrumentation.runOnMainSync {
            val view = WebView(instrumentation.targetContext)
            fun mode() = WebSettingsCompat.getWebAuthenticationSupport(view.settings)
            try {
                PasskeyGate.appVisible(true)
                val gate = PasskeyGate(view.settings, on)
                PasskeyGate.register(gate)
                seen += mode()
                // A gateway iframe (#524 R1-F1): off at once, and its page stays off.
                gate.frameRequested("http://localhost:1633/bzz/abc/")
                seen += mode()
                gate.committed("https://example.com/")
                seen += mode()
                // Back only with a new document off the device.
                gate.mainFrameRequested("https://example.com/next")
                gate.committed("https://example.com/next")
                seen += mode()
                // The app away (R1-F2), and back.
                PasskeyGate.appVisible(false)
                seen += mode()
                PasskeyGate.appVisible(true)
                seen += mode()
                PasskeyGate.unregister(gate)
            } finally {
                PasskeyGate.appVisible(false)
                view.destroy()
            }
        }
        assertEquals(listOf(on, off, off, on, off, on), seen)
    }

    @Test
    fun browser_mode_and_off_both_take_on_a_real_webview() {
        assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_AUTHENTICATION))
        val above = Passkeys.MIN_WEBVIEW_MAJOR
        val on = Passkeys.mode(true, above, Build.VERSION_CODES.UPSIDE_DOWN_CAKE, privateTab = false)!!
        val off = Passkeys.mode(true, above, Build.VERSION_CODES.UPSIDE_DOWN_CAKE, privateTab = true)!!
        assertEquals(WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_BROWSER, readBack { Passkeys.set(it.settings, on) })
        assertEquals(WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_NONE, readBack { Passkeys.set(it.settings, off) })
    }
}
