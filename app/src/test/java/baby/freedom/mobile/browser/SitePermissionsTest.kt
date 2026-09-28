package baby.freedom.mobile.browser

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SitePermissionsTest {

    @Test
    fun `origin key normalizes WebView's shapes`() {
        assertEquals("https://meet.jit.si", permissionOriginKey("https://meet.jit.si/"))
        assertEquals("https://meet.jit.si", permissionOriginKey("https://meet.jit.si"))
        assertEquals("https://example.com", permissionOriginKey("HTTPS://Example.COM:443/path?q#f"))
        assertEquals("http://example.com", permissionOriginKey("http://example.com:80/"))
        assertEquals("http://10.0.2.2:8080", permissionOriginKey("http://10.0.2.2:8080/"))
        assertEquals("https://example.com", permissionOriginKey("https://user:pw@example.com/"))
    }

    @Test
    fun `non http origins can't hold a permission`() {
        assertNull(permissionOriginKey(null))
        assertNull(permissionOriginKey(""))
        assertNull(permissionOriginKey("null"))
        assertNull(permissionOriginKey("file:///sdcard/x.html"))
        assertNull(permissionOriginKey("data:text/html,hi"))
        assertNull(permissionOriginKey("about:blank"))
        assertNull(permissionOriginKey("https://"))
        assertNull(permissionOriginKey("https://:443/"))
    }

    @Test
    fun `display strips https but keeps http and shows dweb origins as the address bar does`() {
        assertEquals("meet.jit.si", permissionOriginDisplay("https://meet.jit.si"))
        assertEquals("http://example.com", permissionOriginDisplay("http://example.com"))
        val ens = VirtualOrigin.originFor(ContentRoot.Ens("vitalik.eth"))!!
        assertEquals("vitalik.eth", permissionOriginDisplay(permissionOriginKey("$ens/")!!))
    }

    @Test
    fun `camera and microphone collapse into one phrase`() {
        assertEquals(
            "use your camera and microphone",
            describePermissionRequest(listOf(SitePermission.MICROPHONE, SitePermission.CAMERA)),
        )
        assertEquals("use your camera", describePermissionRequest(listOf(SitePermission.CAMERA)))
        assertEquals("know your location", describePermissionRequest(listOf(SitePermission.LOCATION)))
    }

    private val o = "https://meet.jit.si"
    private val av = listOf(SitePermission.CAMERA, SitePermission.MICROPHONE)

    @Test
    fun `undecided asks, stored allow grants, any block denies`() {
        val s = PermissionSession()
        assertEquals(PermissionPlan.Ask(av), planFor(o, av, emptyMap(), s))
        assertEquals(
            PermissionPlan.Grant,
            planFor(o, av, mapOf(SitePermission.CAMERA to PermissionDecision.ALLOW, SitePermission.MICROPHONE to PermissionDecision.ALLOW), s),
        )
        assertEquals(
            PermissionPlan.Deny,
            planFor(o, av, mapOf(SitePermission.MICROPHONE to PermissionDecision.DENY), s),
        )
    }

    @Test
    fun `only the undecided half is asked about`() {
        val s = PermissionSession()
        assertEquals(
            PermissionPlan.Ask(listOf(SitePermission.MICROPHONE)),
            planFor(o, av, mapOf(SitePermission.CAMERA to PermissionDecision.ALLOW), s),
        )
    }

    @Test
    fun `session decisions apply for the run, stored ones win`() {
        val s = PermissionSession()
        s.record(o, SitePermission.CAMERA, PermissionDecision.DENY, remembered = false)
        assertEquals(PermissionPlan.Deny, planFor(o, listOf(SitePermission.CAMERA), emptyMap(), s))
        assertEquals(
            PermissionPlan.Grant,
            planFor(o, listOf(SitePermission.CAMERA), mapOf(SitePermission.CAMERA to PermissionDecision.ALLOW), s),
        )
        // Other origins are untouched.
        assertEquals(
            PermissionPlan.Ask(listOf(SitePermission.CAMERA)),
            planFor("https://other.example", listOf(SitePermission.CAMERA), emptyMap(), s),
        )
    }

    @Test
    fun `a tier without embargoes never blocks on dismissals`() {
        val s = PermissionSession(embargoes = false)
        repeat(PermissionSession.DISMISS_EMBARGO_THRESHOLD * 2) {
            assertFalse(s.dismiss(o, SitePermission.CAMERA))
        }
        assertEquals(PermissionPlan.Ask(listOf(SitePermission.CAMERA)), planFor(o, listOf(SitePermission.CAMERA), emptyMap(), s))
        assertEquals(emptyList<SitePermissionEntry>(), s.entries())
    }

    @Test
    fun `three dismissals embargo the pair, an answer resets the count`() {
        val s = PermissionSession()
        val loc = SitePermission.LOCATION
        assertFalse(s.dismiss(o, loc))
        assertFalse(s.dismiss(o, loc))
        // An answer in between starts the count again…
        s.record(o, loc, PermissionDecision.ALLOW, remembered = false)
        s.revoke(o, loc)
        assertFalse(s.dismiss(o, loc))
        assertFalse(s.dismiss(o, loc))
        assertEquals(PermissionPlan.Ask(listOf(loc)), planFor(o, listOf(loc), emptyMap(), s))
        // …and the third consecutive one blocks without asking.
        assertTrue(s.dismiss(o, loc))
        assertEquals(PermissionPlan.Deny, planFor(o, listOf(loc), emptyMap(), s))
        val entry = s.entries().single()
        assertTrue(entry.embargoed)
        assertFalse(entry.remembered)
        assertEquals("Blocked after 3 dismissals (this session)", sitePermissionStateLabel(entry))
    }

    @Test
    fun `revoke lifts an embargo and a session decision`() {
        val s = PermissionSession()
        repeat(3) { s.dismiss(o, SitePermission.CAMERA) }
        s.revoke(o, SitePermission.CAMERA)
        assertEquals(PermissionPlan.Ask(listOf(SitePermission.CAMERA)), planFor(o, listOf(SitePermission.CAMERA), emptyMap(), s))
        assertTrue(s.entries().isEmpty())
    }

    @Test
    fun `a remembered answer leaves no session entry behind`() {
        val s = PermissionSession()
        s.record(o, SitePermission.CAMERA, PermissionDecision.ALLOW, remembered = true)
        assertTrue(s.entries().isEmpty())
    }

    @Test
    fun `prompt buttons ignore taps until it has been on screen for the protection period`() {
        var now = 1_000L
        val g = PromptTapGuard { now }
        // Not yet drawn: a tap can only have been aimed at the page.
        assertFalse(g.accepts())
        g.onShown()
        assertFalse(g.accepts())
        now += PromptTapGuard.PROTECTION_MS - 1
        assertFalse(g.accepts())
        assertEquals(1L, g.remainingMs())
        now += 1
        assertTrue(g.accepts())
        assertEquals(0L, g.remainingMs())
        // A later recomposition's onShown doesn't restart the clock.
        g.onShown()
        assertTrue(g.accepts())
    }

    @Test
    fun `settings snackbar only for a permission Android won't ask for again`() {
        // First refusal: a re-request shows the dialog again.
        assertFalse(androidPermissionBlockedInSettings(rationale = true, deniedBefore = false))
        assertFalse(androidPermissionBlockedInSettings(rationale = true, deniedBefore = true))
        // Dialog backed out of before ever being answered.
        assertFalse(androidPermissionBlockedInSettings(rationale = false, deniedBefore = false))
        // Denied for good.
        assertTrue(androidPermissionBlockedInSettings(rationale = false, deniedBefore = true))
    }

    @Test
    fun `android dialog waits for the requesting tab to be on screen`() = runBlocking {
        // Tab 2 is active but Settings covers it (null), then tab 1 shows.
        val onScreen = MutableStateFlow<Long?>(1L)
        val withdrawn = MutableStateFlow(false)
        onScreen.value = null
        val wait = async(start = CoroutineStart.UNDISPATCHED) {
            awaitTabOnScreen(onScreen, 2L, withdrawn)
        }
        onScreen.value = 1L
        repeat(3) { yield() }
        assertFalse("must not raise over another tab", wait.isCompleted)
        onScreen.value = null
        repeat(3) { yield() }
        assertFalse("must not raise over a panel", wait.isCompleted)
        onScreen.value = 2L
        assertTrue(withTimeout(1_000) { wait.await() })
    }

    @Test
    fun `upload camera ask launches only over the tab that asked`() = runBlocking {
        val dialogUp = MutableStateFlow(false)
        var launches = 0
        var held = false
        suspend fun ask(onScreen: Long?, launch: (suspend () -> Unit)? = { launches++; held = true }) =
            askAndroidPermissionOnScreen(Mutex(), MutableStateFlow(onScreen), 2L, dialogUp, { held }, launch)

        // Another tab, or a panel / the app in the background: nothing shown.
        assertEquals(AndroidPermissionAsk.OFF_SCREEN, ask(1L))
        assertEquals(AndroidPermissionAsk.OFF_SCREEN, ask(null))
        assertEquals(0, launches)
        // No screen to ask with.
        assertEquals(AndroidPermissionAsk.REFUSED, ask(2L, launch = null))
        // Refused: the dialog ran and the permission still isn't held.
        assertEquals(AndroidPermissionAsk.REFUSED, ask(2L, launch = { launches++ }))
        // A launch that throws is a refusal, and the dialog flag comes down.
        assertEquals(AndroidPermissionAsk.REFUSED, ask(2L, launch = { error("boom") }))
        assertFalse(dialogUp.value)
        // Allowed.
        assertEquals(AndroidPermissionAsk.GRANTED, ask(2L))
        assertEquals(2, launches)
        // Already held: no dialog.
        assertEquals(AndroidPermissionAsk.GRANTED, ask(2L))
        assertEquals(2, launches)
    }

    @Test
    fun `upload camera ask waits for a dialog already up and rechecks the screen`() = runBlocking {
        val lock = Mutex()
        val onScreen = MutableStateFlow<Long?>(2L)
        val dialogUp = MutableStateFlow(false)
        var launched = false
        lock.lock() // a site's Android dialog is up
        val ask = async(start = CoroutineStart.UNDISPATCHED) {
            askAndroidPermissionOnScreen(lock, onScreen, 2L, dialogUp, { false }, {
                assertTrue("dialog flag raised while shown", dialogUp.value)
                launched = true
            })
        }
        repeat(3) { yield() }
        assertFalse("must not launch over the other dialog", ask.isCompleted || launched)
        // The user switched to the tab switcher meanwhile.
        onScreen.value = null
        lock.unlock()
        assertEquals(AndroidPermissionAsk.OFF_SCREEN, withTimeout(1_000) { ask.await() })
        assertFalse(launched)

        onScreen.value = 2L
        assertEquals(
            AndroidPermissionAsk.REFUSED,
            askAndroidPermissionOnScreen(lock, onScreen, 2L, dialogUp, { false }, {
                assertTrue(dialogUp.value)
                launched = true
            }),
        )
        assertTrue(launched)
        assertFalse(dialogUp.value)
    }

    @Test
    fun `android dialog wait ends when the request is withdrawn`() = runBlocking {
        val onScreen = MutableStateFlow<Long?>(1L)
        val withdrawn = MutableStateFlow(false)
        val wait = async(start = CoroutineStart.UNDISPATCHED) {
            awaitTabOnScreen(onScreen, 2L, withdrawn)
        }
        withdrawn.value = true
        assertFalse(withTimeout(1_000) { wait.await() })
        // Already withdrawn beats already on screen.
        assertFalse(awaitTabOnScreen(MutableStateFlow(2L), 2L, MutableStateFlow(true)))
        // Already on screen returns at once.
        assertTrue(awaitTabOnScreen(MutableStateFlow(2L), 2L, MutableStateFlow(false)))
    }

    @Test
    fun `a prompt is superseded when another tab answers the same question`() = runBlocking {
        val o = "https://example.com"
        val cam = SitePermission.CAMERA
        val mic = SitePermission.MICROPHONE
        val session = PermissionSession()
        val wait = async(start = CoroutineStart.UNDISPATCHED) {
            awaitPromptSuperseded(o, listOf(cam, mic), session) { emptyMap() }
        }
        // Unrelated changes leave the prompt up: another origin, another
        // permission, a dismissal short of the embargo.
        session.record("https://other.example", cam, PermissionDecision.ALLOW, remembered = false)
        session.record(o, SitePermission.LOCATION, PermissionDecision.ALLOW, remembered = false)
        session.dismiss(o, cam)
        repeat(3) { yield() }
        assertFalse(wait.isCompleted)
        // Tab 1 answers half of it: this prompt asks the wrong question now.
        session.record(o, cam, PermissionDecision.ALLOW, remembered = false)
        withTimeout(1_000) { wait.await() }
    }

    @Test
    fun `a remembered answer elsewhere supersedes even once it left the session tier`() = runBlocking {
        val o = "https://example.com"
        val cam = SitePermission.CAMERA
        val session = PermissionSession()
        var stored = emptyMap<SiteCapability, PermissionDecision>()
        val wait = async(start = CoroutineStart.UNDISPATCHED) {
            awaitPromptSuperseded(o, listOf(cam), session) { stored }
        }
        // "Allow + remember": the write lands, then the session entry is
        // dropped — all before the watcher gets to run.
        session.record(o, cam, PermissionDecision.ALLOW, remembered = false)
        stored = mapOf(cam to PermissionDecision.ALLOW)
        session.record(o, cam, PermissionDecision.ALLOW, remembered = true)
        withTimeout(1_000) { wait.await() }
    }

    @Test
    fun `an embargo reached elsewhere supersedes the prompt`() = runBlocking {
        val o = "https://example.com"
        val loc = SitePermission.LOCATION
        val session = PermissionSession()
        val wait = async(start = CoroutineStart.UNDISPATCHED) {
            awaitPromptSuperseded(o, listOf(loc), session) { emptyMap() }
        }
        repeat(PermissionSession.DISMISS_EMBARGO_THRESHOLD) { session.dismiss(o, loc) }
        withTimeout(1_000) { wait.await() }
    }

    @Test
    fun `permission prompt and download offer take turns, never stack`() {
        fun turn(perm: Boolean, offer: Boolean, offerHeld: Boolean = false, android: Boolean = false) =
            modalPromptTurn(perm, offer, offerHeld, android)
        // Alone, each shows.
        assertEquals(PromptTurn.SitePermission, turn(perm = true, offer = false))
        assertEquals(PromptTurn.DownloadOffer, turn(perm = false, offer = true))
        assertEquals(PromptTurn.None, turn(perm = false, offer = false))
        // Both arriving together: the permission prompt first…
        assertEquals(PromptTurn.SitePermission, turn(perm = true, offer = true))
        // …then the offer, once the permission is answered.
        assertEquals(PromptTurn.DownloadOffer, turn(perm = false, offer = true))
        // An offer already up keeps the screen; the permission prompt waits.
        assertEquals(PromptTurn.DownloadOffer, turn(perm = true, offer = true, offerHeld = true))
        // …and comes up once the offers are answered.
        assertEquals(PromptTurn.SitePermission, turn(perm = true, offer = false, offerHeld = true))
        // Nothing of ours over Android's permission dialog.
        assertEquals(PromptTurn.None, turn(perm = false, offer = true, android = true))
        assertEquals(PromptTurn.None, turn(perm = true, offer = true, offerHeld = true, android = true))
    }
}
