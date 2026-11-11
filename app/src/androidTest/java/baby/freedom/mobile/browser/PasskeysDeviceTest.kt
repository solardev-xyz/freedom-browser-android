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
 * mode back: what a tab gets on this device, and that each mode the
 * decision can make actually takes.
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
        for (private in listOf(false, true)) {
            val expected = Passkeys.mode(true, major, Build.VERSION.SDK_INT, private)
            assertEquals(expected, readBack { Passkeys.apply(context, it.settings, private) })
        }
        // A private tab is off whatever the WebView.
        assertEquals(
            WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_NONE,
            readBack { Passkeys.apply(context, it.settings, privateTab = true) },
        )
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
