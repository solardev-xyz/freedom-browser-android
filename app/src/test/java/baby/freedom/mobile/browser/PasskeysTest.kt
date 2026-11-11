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
    fun `a device frame asked for between a request and its commit holds the incoming document`() {
        val hold = DeviceDocumentHold()
        hold.mainFrameRequested("https://a.example/")
        hold.committed("https://a.example/")
        assertEquals(false, hold.held)
        // The user goes on to B; before B commits, A (still on screen)
        // frames the gateway. The tab can't tell that frame from one of
        // B's own asked for early, so B commits held: fail closed (R5-M1).
        hold.mainFrameRequested("https://b.example/")
        hold.frameRequested("http://localhost:1633/bzz/c/")
        hold.committed("https://b.example/")
        assertEquals(true, hold.held)
        // A reload of B with no device frame asked for clears it.
        hold.mainFrameRequested("https://b.example/")
        hold.committed("https://b.example/")
        assertEquals(false, hold.held)
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
        reload.mainFrameRequested("https://a.example/")
        val ticket = reload.committed(madeLowered = true, raisedNow = true)!!
        assertEquals(true, reload.take(ticket, "https://webauthn.io/", raised = true))
        assertEquals(false, reload.take(ticket, "https://webauthn.io/", raised = true))
        // Its request and commit; the page frames the gateway, which lowers
        // the mode, and the reload's commit raises it again: no second
        // reload, or such a page would reload forever.
        reload.mainFrameRequested("https://a.example/")
        assertEquals(null, reload.committed(madeLowered = true, raisedNow = true))
        // The next raise after that is owed one again.
        reload.mainFrameRequested("https://a.example/")
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
        reload.mainFrameRequested("https://a.example/")
        assertEquals(false, reload.take(superseded, "https://webauthn.io/", raised = true))
        // A later commit's ticket replaces an earlier one.
        val first = reload.committed(madeLowered = true, raisedNow = true)!!
        reload.committed(madeLowered = false, raisedNow = true)
        assertEquals(false, reload.take(first, "https://webauthn.io/", raised = true))
        // A reload that redirects (to a new address each time, say) is
        // still the reload: its commit earns no reload of its own.
        val redirected = reload.committed(madeLowered = true, raisedNow = true)!!
        assertEquals(true, reload.take(redirected, "https://a.example/#top", raised = true))
        reload.mainFrameRequested("https://a.example/")
        reload.mainFrameRequested("https://a.example/")
        assertEquals(null, reload.committed(madeLowered = true, raisedNow = true))
        assertNotNull(reload.committed(madeLowered = true, raisedNow = true))
    }

    @Test
    fun `a document made while the app was away is reloaded once when it comes back`() {
        val reload = RaisedDocumentReload()
        // Committed while the app is away: the mode stays down, nothing yet.
        reload.mainFrameRequested("https://a.example/")
        assertEquals(null, reload.committed(madeLowered = true, raisedNow = false))
        // The app back on screen raises it: that document is owed its reload, once.
        val ticket = reload.raised()!!
        assertEquals(true, reload.take(ticket, "https://webauthn.io/", raised = true))
        assertEquals(null, reload.raised())
        // A document made with the mode up owes nothing when it comes back.
        reload.mainFrameRequested("https://a.example/")
        assertEquals(null, reload.committed(madeLowered = false, raisedNow = false))
        assertEquals(null, reload.raised())
        // The app left during that reload, and it committed while away: owed
        // again on the next return (only the user brings the app back, so
        // this can't loop).
        reload.mainFrameRequested("https://a.example/")
        reload.committed(madeLowered = true, raisedNow = true)!!.let { assertEquals(true, reload.take(it, "https://a.example/", raised = true)) }
        assertEquals(null, reload.committed(madeLowered = true, raisedNow = false))
        assertNotNull(reload.raised())
        // A view with no document has nothing to reload.
        assertEquals(false, reload.take(reload.raised()!!, null, raised = true))
        // A navigation before the reload runs drops it.
        val superseded = reload.raised()!!
        reload.mainFrameRequested("https://a.example/")
        assertEquals(false, reload.take(superseded, "https://a.example/", raised = true))
        // And one the page started while the app was away is never replaced
        // by a reload on its return.
        assertEquals(null, reload.committed(madeLowered = true, raisedNow = false))
        reload.mainFrameRequested("https://a.example/")
        assertEquals(null, reload.raised())
    }

    @Test
    fun `a hop that never leaves the page keeps the reload owed on return`() {
        val reload = RaisedDocumentReload()
        reload.mainFrameRequested("https://webauthn.io/")
        assertEquals(null, reload.committed(madeLowered = true, raisedNow = false))
        // While away, the page tries an app hop that is refused (no
        // gesture) or handed off: the page on screen is still the one owed.
        reload.mainFrameRequested("intent://scan/#Intent;scheme=zxing;end")
        reload.mainFrameRequested("otpauth://totp/x")
        reload.mainFrameRequested(null)
        val ticket = reload.raised()!!
        // Nor does such a hop drop a reload already due.
        reload.mainFrameRequested("mailto:a@example.com")
        assertEquals(true, reload.take(ticket, "https://webauthn.io/", raised = true))
        // A document it does make (here `about:blank`) still supersedes it.
        reload.mainFrameRequested("https://webauthn.io/")
        assertEquals(null, reload.committed(madeLowered = true, raisedNow = false))
        reload.mainFrameRequested("about:blank")
        assertEquals(null, reload.raised())
    }

    @Test
    fun `an app-started load heads for its own address, or the history entry it steps to`() {
        val history = mapOf(-1 to "http://localhost:1633/bzz/a/", 1 to "https://sw.example/app")
        val entry = { steps: Int -> history[steps] }
        assertEquals("https://sw.example/", Passkeys.appLoadTarget("https://sw.example/", entry))
        assertEquals("http://localhost:8080/", Passkeys.appLoadTarget("http://localhost:8080/", entry))
        // The chrome's Back / Forward: the entry it steps to.
        assertEquals("http://localhost:1633/bzz/a/", Passkeys.appLoadTarget(HISTORY_BACK_JS, entry))
        assertEquals("https://sw.example/app", Passkeys.appLoadTarget(HISTORY_FORWARD_JS, entry))
        // No entry that way: nothing.
        assertEquals(null, Passkeys.appLoadTarget(HISTORY_BACK_JS) { null })
        // Any other `javascript:` URL loads nothing.
        assertEquals(null, Passkeys.appLoadTarget("JavaScript:void(0)", entry))
        assertEquals(null, Passkeys.appLoadTarget("javascript:location='http://localhost:1633/'", entry))
    }

    /**
     * The tab's hold and reload as [PasskeyGate] drives them, without a
     * WebView: the mode is up when the document isn't held (app on screen).
     */
    private class GateModel {
        val hold = DeviceDocumentHold()
        val reload = RaisedDocumentReload()
        val raised get() = Passkeys.live(WEB_AUTHENTICATION_SUPPORT_FOR_BROWSER, true, hold.held) ==
            WEB_AUTHENTICATION_SUPPORT_FOR_BROWSER

        fun requested(url: String?) {
            hold.mainFrameRequested(url)
            reload.mainFrameRequested(url)
        }

        fun committed(url: String): Int? {
            val before = raised
            hold.committed(url)
            return reload.committed(madeLowered = !before, raisedNow = raised)
        }
    }

    @Test
    fun `a service-worker page the app loads after a hold commits clean and is reloaded once`() {
        // The tab showed a page on the device.
        val gate = GateModel()
        gate.requested("http://localhost:1633/bzz/a/")
        gate.committed("http://localhost:1633/bzz/a/")
        assertEquals(false, gate.raised)
        // The user types the address of a page a service worker answers:
        // no `shouldOverrideUrlLoading`, no interceptor; only the tab's
        // own `loadUrl` names it (R6-M1).
        gate.requested(Passkeys.appLoadTarget("https://sw.example/") { null })
        val ticket = gate.committed("https://sw.example/")
        assertEquals(true, gate.raised)
        assertNotNull(ticket)
        assertEquals(true, gate.reload.take(ticket!!, "https://sw.example/", raised = true))
        // The gate's reload goes through the tab's `reload()`, which names
        // the page's own address again: its commit earns no second reload.
        gate.requested("https://sw.example/")
        assertEquals(null, gate.committed("https://sw.example/"))
        assertEquals(true, gate.raised)
    }

    @Test
    fun `a page held by the outgoing page's device frame heals on the app's reload`() {
        val gate = GateModel()
        gate.requested("https://a.example/")
        gate.committed("https://a.example/")
        // A frames the gateway between B's request and its commit (R5-M1).
        gate.requested("https://sw.example/")
        gate.hold.frameRequested("http://localhost:1633/bzz/c/")
        assertEquals(null, gate.committed("https://sw.example/"))
        assertEquals(false, gate.raised)
        // The user's reload of B, worker-answered: named by `reload()` itself.
        gate.requested("https://sw.example/")
        assertNotNull(gate.committed("https://sw.example/"))
        assertEquals(true, gate.raised)
    }

    @Test
    fun `an app-started load still holds for a document on the device`() {
        val history = mapOf(-1 to "http://localhost:1633/bzz/a/", 1 to "https://sw.example/")
        val entry = { steps: Int -> history[steps] }
        val gate = GateModel()
        gate.requested("https://sw.example/")
        gate.committed("https://sw.example/")
        assertEquals(true, gate.raised)
        // A typed device address: down before the load starts.
        gate.requested(Passkeys.appLoadTarget("http://127.0.0.1:8080/", entry))
        assertEquals(false, gate.raised)
        gate.committed("http://127.0.0.1:8080/")
        // Forward to a clean page, then Back to a device entry: down at
        // once, before its document is made.
        gate.requested(Passkeys.appLoadTarget(HISTORY_FORWARD_JS, entry))
        gate.committed("https://sw.example/")
        assertEquals(true, gate.raised)
        gate.requested(Passkeys.appLoadTarget(HISTORY_BACK_JS, entry))
        assertEquals(false, gate.raised)
        gate.committed("http://localhost:1633/bzz/a/")
        assertEquals(false, gate.raised)
        // A `javascript:` URL that loads nothing leaves the hold as it is.
        gate.requested(Passkeys.appLoadTarget("javascript:void(0)", entry))
        assertEquals(false, gate.raised)
        // A clean app-started load whose page then frames the device holds
        // that page's commit.
        gate.requested(Passkeys.appLoadTarget("https://sw.example/", entry))
        gate.hold.frameRequested("http://localhost:1633/bzz/b/")
        assertEquals(null, gate.committed("https://sw.example/"))
        assertEquals(false, gate.raised)
    }
}
