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
    fun `every touch before the prompt arms starts its protection period over`() {
        // #240: a page's "tap fast here" game lined up with Send must never reach it.
        var now = 1_000L
        val g = PromptTapGuard(PromptTapGuard.SPEND_PROTECTION_MS) { now }
        g.onShown()
        // Four taps a second for ten seconds: never armed, however long it goes on.
        repeat(40) {
            now += 250
            assertFalse(g.accepts())
            g.noteInput()
        }
        assertEquals(PromptTapGuard.SPEND_PROTECTION_MS, g.remainingMs())
        // The user stops: it arms a full period after the last touch, not before.
        now += PromptTapGuard.SPEND_PROTECTION_MS - 1
        assertFalse(g.accepts())
        now += 1
        assertTrue(g.accepts())
        // Armed stays armed: reading on, scrolling, then tapping doesn't disarm it.
        g.noteInput()
        assertTrue(g.accepts())
        assertEquals(0L, g.remainingMs())
    }

    @Test
    fun `a touch before the prompt is drawn doesn't start the period early`() {
        var now = 1_000L
        val g = PromptTapGuard(PromptTapGuard.SPEND_PROTECTION_MS) { now }
        g.noteInput()
        now += 5_000
        assertFalse(g.accepts())
        g.onShown()
        now += PromptTapGuard.SPEND_PROTECTION_MS - 1
        assertFalse(g.accepts())
        now += 1
        assertTrue(g.accepts())
    }

    @Test
    fun `sign and send arm later than other prompts`() {
        // #240: the old half second was a page's whole window to time a tap onto Send.
        assertTrue(PromptTapGuard.SPEND_PROTECTION_MS >= 1_000)
        var now = 0L
        val spend = PromptTapGuard(PromptTapGuard.SPEND_PROTECTION_MS) { now }
        val plain = PromptTapGuard { now }
        spend.onShown()
        plain.onShown()
        now = PromptTapGuard.PROTECTION_MS
        assertTrue(plain.accepts())
        assertFalse(spend.accepts())
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

    @Test
    fun `no page prompt shows over a full-screen panel, the download offer included`() {
        // A page's download offer (or any other prompt) while Settings, the
        // Wallet or another panel covers it waits; it used to pop up over
        // the panel (#228).
        fun covered(
            perm: Boolean = false, offer: Boolean = false, offerHeld: Boolean = false,
            radicle: Boolean = false, ethereum: Boolean = false, ethereumHeld: Boolean = false,
            swarm: Boolean = false,
        ) = modalPromptTurn(
            perm, offer, offerHeld, androidDialogUp = false,
            radicleWaiting = radicle, ethereumWaiting = ethereum, ethereumHasTurn = ethereumHeld,
            swarmWaiting = swarm, pageUncovered = false,
        )
        assertEquals(PromptTurn.None, covered(offer = true))
        assertEquals(PromptTurn.None, covered(offer = true, offerHeld = true))
        assertEquals(PromptTurn.None, covered(perm = true, offer = true))
        assertEquals(PromptTurn.None, covered(radicle = true))
        assertEquals(PromptTurn.None, covered(ethereum = true, ethereumHeld = true, offer = true))
        assertEquals(PromptTurn.None, covered(swarm = true))
        // Once the panel closes, the offer takes its turn again.
        assertEquals(PromptTurn.DownloadOffer, modalPromptTurn(false, true, false, false, pageUncovered = true))
    }

    @Test
    fun `a page's JavaScript dialog takes its turn and never stacks on a sheet`() {
        // The #246 repro: `eth_requestAccounts`, then `alert()` 100 ms later.
        // The Connect sheet already up keeps the screen; the alert waits…
        assertEquals(
            PromptTurn.Ethereum,
            modalPromptTurn(false, false, false, false, ethereumWaiting = true, ethereumHasTurn = true, jsDialogWaiting = true),
        )
        // …and shows once the sheet is answered.
        assertEquals(PromptTurn.JsDialog, modalPromptTurn(false, false, false, false, jsDialogWaiting = true))
        // Arriving together with nothing up yet: after the page's own
        // approval prompts, before the download offer.
        assertEquals(
            PromptTurn.SitePermission,
            modalPromptTurn(true, true, false, false, jsDialogWaiting = true),
        )
        assertEquals(PromptTurn.Swarm, modalPromptTurn(false, false, false, false, swarmWaiting = true, jsDialogWaiting = true))
        assertEquals(PromptTurn.JsDialog, modalPromptTurn(false, true, false, false, jsDialogWaiting = true))
        // Once up, it keeps the screen: an offer or a permission request
        // arriving behind it waits.
        assertEquals(
            PromptTurn.JsDialog,
            modalPromptTurn(true, true, false, false, jsDialogWaiting = true, jsDialogHasTurn = true),
        )
        // A waiting one isn't shown over a full-screen panel (BrowserScreen
        // answers it instead), nor over Android's permission dialog.
        assertEquals(PromptTurn.None, modalPromptTurn(false, false, false, false, pageUncovered = false, jsDialogWaiting = true))
        assertEquals(PromptTurn.None, modalPromptTurn(false, false, false, true, jsDialogWaiting = true))
    }

    @Test
    fun `a JavaScript dialog that is up keeps its turn when a panel opens under it`() {
        // An intent opening Settings while a `confirm()` is up: the dialog
        // stays, for the user to answer, rather than being taken down.
        assertEquals(
            PromptTurn.JsDialog,
            modalPromptTurn(false, false, false, false, pageUncovered = false, jsDialogWaiting = true, jsDialogHasTurn = true),
        )
        // Once answered, nothing shows over the panel.
        assertEquals(
            PromptTurn.None,
            modalPromptTurn(true, true, false, false, pageUncovered = false, jsDialogHasTurn = true),
        )
    }

    @Test
    fun `the long-press menu takes turns with the page's prompts`() {
        // Alone, it shows.
        assertEquals(PromptTurn.ContextMenu, modalPromptTurn(false, false, false, false, contextMenuWaiting = true))
        // The #246 repro: a `contextmenu` handler asks the wallet to sign as
        // the menu opens. With neither up yet the menu goes first and the
        // Sign sheet waits for it…
        assertEquals(
            PromptTurn.ContextMenu,
            modalPromptTurn(false, false, false, false, ethereumWaiting = true, contextMenuWaiting = true),
        )
        // …so once up the menu keeps the screen, whatever arrives next.
        assertEquals(
            PromptTurn.ContextMenu,
            modalPromptTurn(true, true, false, false, radicleWaiting = true, jsDialogWaiting = true, contextMenuWaiting = true),
        )
        // …and the sheet follows once the menu is gone.
        assertEquals(PromptTurn.Ethereum, modalPromptTurn(false, false, false, false, ethereumWaiting = true))
        // A sheet or dialog already up keeps the screen over a menu.
        assertEquals(
            PromptTurn.Ethereum,
            modalPromptTurn(false, false, false, false, ethereumWaiting = true, ethereumHasTurn = true, contextMenuWaiting = true),
        )
        assertEquals(
            PromptTurn.JsDialog,
            modalPromptTurn(false, false, false, false, jsDialogWaiting = true, jsDialogHasTurn = true, contextMenuWaiting = true),
        )
        // Nothing over a full-screen panel or Android's dialog.
        assertEquals(PromptTurn.None, modalPromptTurn(false, false, false, false, pageUncovered = false, contextMenuWaiting = true))
        assertEquals(PromptTurn.None, modalPromptTurn(false, false, false, true, contextMenuWaiting = true))
    }

    @Test
    fun `a long-press menu is only let in while no prompt is up`() {
        assertTrue(contextMenuAdmitted(PromptTurn.None, pageUncovered = true))
        assertTrue(contextMenuAdmitted(PromptTurn.ContextMenu, pageUncovered = true))
        // The site-permission prompt has no "has the turn" flag: the menu
        // mustn't be let in to un-show it, nor any other prompt on screen.
        for (up in PromptTurn.entries - PromptTurn.None - PromptTurn.ContextMenu) {
            assertFalse(up.name, contextMenuAdmitted(up, pageUncovered = true))
        }
    }

    @Test
    fun `a long-press menu isn't kept for when a full-screen panel closes`() {
        // Under a panel the turn is None, just as on an idle page; the
        // menu is dropped rather than left to open once the panel goes.
        for (turn in PromptTurn.entries) {
            assertFalse(turn.name, contextMenuAdmitted(turn, pageUncovered = false))
        }
        assertEquals(
            PromptTurn.None,
            modalPromptTurn(
                false, false, false, false, pageUncovered = false,
                contextMenuWaiting = contextMenuAdmitted(PromptTurn.None, pageUncovered = false),
            ),
        )
    }

    // --- The page's own Site permissions (#266) ---

    private fun entry(origin: String, p: SiteCapability, d: PermissionDecision = PermissionDecision.ALLOW, remembered: Boolean = true) =
        SitePermissionEntry(origin, p, d, remembered)

    @Test
    fun `page sheet lists the page's own decisions first, then a frame's that asked from it`() {
        val all = listOf(
            entry("https://a.example", SitePermission.LOCATION),
            entry("https://frame.example", SitePermission.CAMERA),
            entry("https://other.example", SitePermission.MICROPHONE),
            entry("https://page.example", SitePermission.CAMERA),
            entry("https://page.example", SitePermission.POPUPS, PermissionDecision.ALLOW),
        )
        val listed = pageSitePermissionEntries("https://page.example", setOf("https://frame.example"), all)
        assertEquals(
            listOf(
                "https://page.example" to SitePermission.CAMERA,
                "https://page.example" to SitePermission.POPUPS,
                "https://frame.example" to SitePermission.CAMERA,
            ),
            listed.map { it.origin to it.permission },
        )
    }

    @Test
    fun `page sheet lists nothing for a page with no site`() {
        val all = listOf(entry("https://a.example", SitePermission.LOCATION))
        assertEquals(emptyList<SitePermissionEntry>(), pageSitePermissionEntries(null, emptySet(), all))
    }

    @Test
    fun `media is in use only while granted to the document and active in the app`() {
        val granted = setOf(SitePermission.CAMERA, SitePermission.MICROPHONE, SitePermission.LOCATION)
        assertEquals(setOf(SitePermission.CAMERA), mediaInUse(granted, setOf(SitePermission.CAMERA)))
        assertEquals(emptySet<SitePermission>(), mediaInUse(setOf(SitePermission.MICROPHONE), setOf(SitePermission.CAMERA)))
        assertEquals(emptySet<SitePermission>(), mediaInUse(granted, emptySet()))
        // Location has no app-op "in use" here: never shown as such.
        assertEquals(emptySet<SitePermission>(), mediaInUse(granted, setOf(SitePermission.LOCATION)))
    }

    @Test
    fun `a camera removed while the document holds it stays noted with the document until granted again`() {
        val page = "https://page.example"
        val doc = SitePermissionBroker.DocumentPermissions(doc = 3)
            .granting(page, listOf(SitePermission.CAMERA))
        val revoked = doc.revoking(entry(page, SitePermission.CAMERA))
        // Kept on the document, not the sheet: reading it again (a
        // reopened sheet) still says the camera is held.
        assertEquals(setOf(SitePermission.CAMERA), revoked.revokedHeld)
        // Location was never a held grant; another site's camera isn't this document's.
        assertEquals(revoked, revoked.revoking(entry(page, SitePermission.LOCATION)))
        assertEquals(revoked, revoked.revoking(entry("https://other.example", SitePermission.CAMERA)))
        // A microphone the document was never given isn't held either.
        assertEquals(revoked, revoked.revoking(entry(page, SitePermission.MICROPHONE)))
        // Asked and allowed again: no longer removed.
        assertEquals(emptySet<SitePermission>(), revoked.granting(page, listOf(SitePermission.CAMERA)).revokedHeld)
    }

    @Test
    fun `in-use label names what is in use`() {
        assertNull(mediaInUseLabel(emptySet()))
        assertEquals("Camera in use", mediaInUseLabel(setOf(SitePermission.CAMERA)))
        assertEquals("Microphone in use", mediaInUseLabel(setOf(SitePermission.MICROPHONE)))
        assertEquals(
            "Camera and microphone in use",
            mediaInUseLabel(setOf(SitePermission.MICROPHONE, SitePermission.CAMERA)),
        )
    }

    @Test
    fun `a private tab's decisions read as lasting until private tabs close`() {
        val allowed = entry("https://a.example", SitePermission.CAMERA, remembered = false)
        assertEquals("Allowed (private tabs)", sitePermissionStateLabel(allowed, private = true))
        assertEquals("Allowed (this session)", sitePermissionStateLabel(allowed))
        assertEquals("Allowed", sitePermissionStateLabel(allowed.copy(remembered = true)))
        assertEquals(
            "Blocked (private tabs)",
            sitePermissionStateLabel(allowed.copy(decision = PermissionDecision.DENY), private = true),
        )
    }

    @Test
    fun `menu sub-line names each capability once`() {
        assertNull(sitePermissionsSummary(emptyList()))
        assertEquals(
            "Camera · Location",
            sitePermissionsSummary(
                listOf(
                    entry("https://a.example", SitePermission.CAMERA),
                    entry("https://a.example", SitePermission.LOCATION, PermissionDecision.DENY),
                    entry("https://frame.example", SitePermission.CAMERA),
                ),
            ),
        )
    }

    @Test
    fun `revoking in one session tier leaves the other alone`() {
        val normal = PermissionSession()
        val private = PermissionSession(embargoes = false)
        normal.record("https://a.example", SitePermission.CAMERA, PermissionDecision.ALLOW, remembered = false)
        private.record("https://a.example", SitePermission.CAMERA, PermissionDecision.ALLOW, remembered = false)
        private.revoke("https://a.example", SitePermission.CAMERA)
        assertNull(private.decisionFor("https://a.example", SitePermission.CAMERA))
        assertEquals(PermissionDecision.ALLOW, normal.decisionFor("https://a.example", SitePermission.CAMERA))
    }
}
