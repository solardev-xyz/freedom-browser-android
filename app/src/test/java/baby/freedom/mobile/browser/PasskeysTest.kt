package baby.freedom.mobile.browser

import androidx.webkit.WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_BROWSER
import androidx.webkit.WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_NONE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
        // Below Android 14 no WebView update helps: Settings says Android, not WebView.
        assertEquals(Passkeys.Availability.NeedsAndroid14, Passkeys.availability(true, 133, 33))
        assertEquals(Passkeys.Availability.NeedsAndroid14, Passkeys.availability(false, null, 30))
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

    @Test
    fun `browser mode only with the app on screen and nothing of the document on the device`() {
        val on = WEB_AUTHENTICATION_SUPPORT_FOR_BROWSER
        for (visible in listOf(false, true)) for (device in listOf(false, true)) {
            assertEquals(if (visible && !device) on else WEB_AUTHENTICATION_SUPPORT_NONE, Passkeys.live(on, visible, device))
            // Off stays off; no setting stays untouched.
            assertEquals(WEB_AUTHENTICATION_SUPPORT_NONE, Passkeys.live(WEB_AUTHENTICATION_SUPPORT_NONE, visible, device))
            assertEquals(null, Passkeys.live(null, visible, device))
        }
    }

    @Test
    fun `hosts of this device are on it, on any port`() {
        for (url in listOf(
            "http://localhost:1633/bzz/abc/", "http://localhost/", "https://localhost:8443/x",
            "http://abc.ipfs.localhost:8080/", "http://127.0.0.1:1633/bzz/abc/", "http://127.9.9.9/",
            "http://[::1]:5001/", "http://0.0.0.0:1633/", "blob:http://localhost:1633/0e1d",
        )) {
            assertEquals(url, true, Passkeys.onDevice(url))
        }
        for (url in listOf(
            "https://webauthn.io/", "https://app-swarmit-eth.ens.freedom.baby/", "http://127.tracker.example/",
            "http://localhost.example/", "about:blank", "data:text/html,x", "blob:https://webauthn.io/0e1d", null,
        )) {
            assertEquals(url, false, Passkeys.onDevice(url))
        }
    }

    @Test
    fun `a device frame holds the tab until a new document off the device commits`() {
        val hold = DeviceDocumentHold()
        hold.mainFrameRequested("https://evil.example/")
        hold.committed("https://evil.example/")
        assertEquals(false, hold.held)
        // The gateway framed, however it got there: held at once.
        hold.frameRequested("http://localhost:1633/bzz/b/")
        assertEquals(true, hold.held)
        // Reached without a main-frame request (a service worker's
        // answer): the frame's word still stands.
        hold.committed("https://evil.example/again")
        assertEquals(true, hold.held)
        // A new document requested and committed with no device frame.
        hold.mainFrameRequested("https://webauthn.io/")
        hold.committed("https://webauthn.io/")
        assertEquals(false, hold.held)
        // Asked for before its commit (a preload scan beating `onPageStarted`).
        hold.mainFrameRequested("https://evil.example/")
        hold.frameRequested("http://localhost:1633/bzz/b/")
        hold.committed("https://evil.example/")
        assertEquals(true, hold.held)
        // Requested, but the main frame never commits (a download, a 204): still held.
        hold.mainFrameRequested("https://webauthn.io/file")
        assertEquals(true, hold.held)
    }

    @Test
    fun `a device page stays held through documents with no origin of their own`() {
        val hold = DeviceDocumentHold()
        hold.mainFrameRequested("http://localhost:1633/bzz/a/")
        assertEquals(true, hold.held)
        hold.committed("http://localhost:1633/bzz/a/")
        // `location = URL.createObjectURL(…)`, `about:blank`: the device origin carried over.
        for (url in listOf("blob:http://localhost:1633/0e1d", "about:blank", "data:text/html,x")) {
            hold.mainFrameRequested(url)
            hold.committed(url)
            assertEquals(url, true, hold.held)
        }
        // A pop-up it opens (`window.open('')`) starts held.
        val popup = DeviceDocumentHold()
        popup.openedBy(hold)
        assertEquals(true, popup.held)
        // One from a page off the device doesn't.
        val fresh = DeviceDocumentHold()
        DeviceDocumentHold().also { it.openedBy(fresh) }.let { assertEquals(false, it.held) }
        // An http(s) document off the device ends it.
        popup.mainFrameRequested("https://webauthn.io/")
        popup.committed("https://webauthn.io/")
        assertEquals(false, popup.held)
    }

    @Test
    fun `a document the commit raised the mode for is reloaded once, never for its own reload`() {
        val reload = RaisedDocumentReload()
        // A commit that didn't raise the mode owes nothing.
        assertEquals(null, reload.committed(madeLowered = false, raisedNow = true))
        // The first clean page after a held one: one reload.
        reload.mainFrameRequested()
        val ticket = reload.committed(madeLowered = true, raisedNow = true)!!
        assertEquals(true, reload.take(ticket, "https://webauthn.io/", raised = true))
        assertEquals(false, reload.take(ticket, "https://webauthn.io/", raised = true))
        // Its request and commit; the page frames the gateway, which lowers
        // the mode, and the reload's commit raises it again: no second
        // reload, or such a page would reload forever.
        reload.mainFrameRequested()
        assertEquals(null, reload.committed(madeLowered = true, raisedNow = true))
        // The next raise after that is owed one again.
        reload.mainFrameRequested()
        assertNotNull(reload.committed(madeLowered = true, raisedNow = true))
    }

    @Test
    fun `a reload owed is dropped when the mode is down again or another document is asked for`() {
        val reload = RaisedDocumentReload()
        // A device frame lowered the mode before the reload ran: reloading
        // would make the page again without WebAuthn.
        val lowered = reload.committed(madeLowered = true, raisedNow = true)!!
        assertEquals(false, reload.take(lowered, "https://webauthn.io/", raised = false))
        // The user went on before it ran.
        val superseded = reload.committed(madeLowered = true, raisedNow = true)!!
        reload.mainFrameRequested()
        assertEquals(false, reload.take(superseded, "https://webauthn.io/", raised = true))
        // A later commit's ticket replaces an earlier one.
        val first = reload.committed(madeLowered = true, raisedNow = true)!!
        reload.committed(madeLowered = false, raisedNow = true)
        assertEquals(false, reload.take(first, "https://webauthn.io/", raised = true))
        // A reload that redirects (to a new address each time, say) is
        // still the reload: its commit earns no reload of its own.
        val redirected = reload.committed(madeLowered = true, raisedNow = true)!!
        assertEquals(true, reload.take(redirected, "https://a.example/#top", raised = true))
        reload.mainFrameRequested()
        reload.mainFrameRequested()
        assertEquals(null, reload.committed(madeLowered = true, raisedNow = true))
        assertNotNull(reload.committed(madeLowered = true, raisedNow = true))
    }

    @Test
    fun `a document made while the app was away is reloaded once when it comes back`() {
        val reload = RaisedDocumentReload()
        // Committed while the app is away: the mode stays down, nothing yet.
        reload.mainFrameRequested()
        assertEquals(null, reload.committed(madeLowered = true, raisedNow = false))
        // The app back on screen raises it: that document is owed its reload, once.
        val ticket = reload.raised()!!
        assertEquals(true, reload.take(ticket, "https://webauthn.io/", raised = true))
        assertEquals(null, reload.raised())
        // A document made with the mode up owes nothing when it comes back.
        reload.mainFrameRequested()
        assertEquals(null, reload.committed(madeLowered = false, raisedNow = false))
        assertEquals(null, reload.raised())
        // The app left during that reload, and it committed while away: owed
        // again on the next return (only the user brings the app back, so
        // this can't loop).
        reload.mainFrameRequested()
        reload.committed(madeLowered = true, raisedNow = true)!!.let { assertEquals(true, reload.take(it, "https://a.example/", raised = true)) }
        assertEquals(null, reload.committed(madeLowered = true, raisedNow = false))
        assertNotNull(reload.raised())
        // A view with no document has nothing to reload.
        assertEquals(false, reload.take(reload.raised()!!, null, raised = true))
        // A navigation before the reload runs drops it.
        val superseded = reload.raised()!!
        reload.mainFrameRequested()
        assertEquals(false, reload.take(superseded, "https://a.example/", raised = true))
        // And one the page started while the app was away is never replaced
        // by a reload on its return.
        assertEquals(null, reload.committed(madeLowered = true, raisedNow = false))
        reload.mainFrameRequested()
        assertEquals(null, reload.raised())
    }
}
