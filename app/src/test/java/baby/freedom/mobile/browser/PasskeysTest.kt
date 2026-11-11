package baby.freedom.mobile.browser

import androidx.webkit.WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_BROWSER
import androidx.webkit.WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_NONE
import org.junit.Assert.assertEquals
import org.junit.Test

/** Which WebAuthn mode a tab's WebView gets (#524), and what Settings says about it. */
class PasskeysTest {
    private val floor = Passkeys.MIN_WEBVIEW_MAJOR
    private val webViews = listOf(null, 133, floor - 1, floor, floor + 5)
    private val sdks = 30..37

    @Test
    fun `normal tabs on Android 14 and later with a recent WebView get browser mode`() {
        for (sdk in 34..37) for (major in listOf(floor, floor + 5)) {
            assertEquals(WEB_AUTHENTICATION_SUPPORT_FOR_BROWSER, Passkeys.mode(true, major, sdk, privateTab = false))
        }
    }

    @Test
    fun `private tabs keep WebAuthn off everywhere`() {
        for (sdk in sdks) for (major in webViews) {
            assertEquals(WEB_AUTHENTICATION_SUPPORT_NONE, Passkeys.mode(true, major, sdk, privateTab = true))
        }
    }

    @Test
    fun `Android 13 and older keep WebAuthn off in normal tabs too`() {
        for (sdk in 30..33) for (major in webViews) {
            assertEquals(WEB_AUTHENTICATION_SUPPORT_NONE, Passkeys.mode(true, major, sdk, privateTab = false))
        }
    }

    @Test
    fun `a WebView older than the floor, or of unknown version, keeps WebAuthn off`() {
        // Such a WebView lets a dweb page's request through to Credential Manager.
        for (sdk in sdks) for (major in listOf(null, 133, floor - 1)) {
            assertEquals(WEB_AUTHENTICATION_SUPPORT_NONE, Passkeys.mode(true, major, sdk, privateTab = false))
        }
    }

    @Test
    fun `a WebView without the setting is left alone`() {
        for (sdk in sdks) for (major in webViews) for (private in listOf(false, true)) {
            assertEquals(null, Passkeys.mode(false, major, sdk, private))
        }
    }

    @Test
    fun `Settings availability agrees with the normal-tab mode`() {
        for (feature in listOf(false, true)) for (major in webViews) for (sdk in sdks) {
            val availability = Passkeys.availability(feature, major, sdk)
            val on = Passkeys.mode(feature, major, sdk, privateTab = false) == WEB_AUTHENTICATION_SUPPORT_FOR_BROWSER
            assertEquals(on, availability == Passkeys.Availability.On)
        }
        assertEquals(Passkeys.Availability.WebViewUnsupported, Passkeys.availability(false, floor, 36))
        assertEquals(Passkeys.Availability.WebViewUnsupported, Passkeys.availability(true, floor - 1, 36))
        assertEquals(Passkeys.Availability.WebViewUnsupported, Passkeys.availability(true, null, 36))
        assertEquals(Passkeys.Availability.NeedsAndroid14, Passkeys.availability(true, floor, 33))
        assertEquals(Passkeys.Availability.On, Passkeys.availability(true, floor, 34))
    }

    @Test
    fun `the WebView major version is read from its version name`() {
        assertEquals(155, Passkeys.majorVersion("155.0.8059.84"))
        assertEquals(133, Passkeys.majorVersion("133.0.6943.137"))
        assertEquals(null, Passkeys.majorVersion(null))
        assertEquals(null, Passkeys.majorVersion(""))
        assertEquals(null, Passkeys.majorVersion("dev"))
    }
}
