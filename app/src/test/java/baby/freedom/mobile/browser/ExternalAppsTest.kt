package baby.freedom.mobile.browser

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalAppsTest {

    @Test
    fun `scheme is parsed by hand, lower-cased, and must be valid`() {
        assertEquals("mailto", schemeOf("mailto:a@b.c"))
        assertEquals("magnet", schemeOf("MAGNET:?xt=urn:btih:abc"))
        assertEquals("ms-settings", schemeOf("ms-settings:privacy"))
        assertNull(schemeOf(null))
        assertNull(schemeOf("no-colon"))
        assertNull(schemeOf(":leading"))
        assertNull(schemeOf("1tel:123"))
        assertNull(schemeOf("te l:123"))
        assertNull(schemeOf("a".repeat(70) + ":x"))
    }

    @Test
    fun `schemes the browser handles itself are not app links`() {
        for (url in listOf(
            "https://example.com", "http://x", "about:blank", "data:text/html,hi",
            "javascript:alert(1)", "blob:https://x/1", "file:///sdcard/x",
            "bzz://abc", "ipfs://cid", "ipns://name", "ens://vitalik.eth", "web3://x", "rad:z",
        )) {
            assertNull(url, externalLinkScheme(url))
        }
        assertEquals("tel", externalLinkScheme("tel:+15551234"))
        assertEquals("intent", externalLinkScheme("intent://x#Intent;scheme=zxing;end"))
    }

    @Test
    fun `local and app-targeting android schemes are external but never allowed`() {
        assertEquals("content", externalLinkScheme("content://baby.freedom.mobile.files/x"))
        assertFalse(isExternalSchemeAllowed("content"))
        assertFalse(isExternalSchemeAllowed("android-app"))
        assertFalse(isExternalSchemeAllowed("https"))
        assertTrue(isExternalSchemeAllowed("mailto"))
        assertTrue(isExternalSchemeAllowed("zoomus"))
    }

    @Test
    fun `stored keys round-trip per scheme, and a blocked scheme can't be stored into use`() {
        assertEquals("external:mailto", ExternalScheme("mailto").key)
        assertEquals(ExternalScheme("magnet"), SiteCapability.forKey("external:magnet"))
        assertNull(SiteCapability.forKey("external:content"))
        assertNull(SiteCapability.forKey("external:https"))
        assertNull(SiteCapability.forKey("external:"))
        assertEquals(SitePermission.CAMERA, SiteCapability.forKey("camera"))
    }

    @Test
    fun `allowing one scheme for a site allows nothing else`() {
        val s = PermissionSession()
        val o = PermissionScope("https://example.com")
        s.record(o, ExternalScheme("magnet"), PermissionDecision.ALLOW, remembered = false)
        assertEquals(PermissionPlan.Grant, planFor(o, listOf(ExternalScheme("magnet")), emptyMap(), s))
        assertEquals(
            PermissionPlan.Ask(listOf(ExternalScheme("sms"))),
            planFor(o, listOf(ExternalScheme("sms")), emptyMap(), s),
        )
        assertEquals(
            PermissionPlan.Ask(listOf(ExternalScheme("magnet"))),
            planFor(PermissionScope("https://other.example"), listOf(ExternalScheme("magnet")), emptyMap(), s),
        )
    }

    @Test
    fun `prompt names the scheme`() {
        assertEquals(
            "open mailto: links in another app",
            describePermissionRequest(listOf(ExternalScheme("mailto"))),
        )
    }

    private fun verdict(
        url: String,
        main: Boolean = true,
        gesture: Boolean = true,
        tapped: Boolean = true,
        userNamedRedirect: Boolean = false,
    ): Pair<ExternalLinkVerdict, Boolean> {
        var consumed = false
        val v = externalLinkVerdict(url, main, gesture, userNamedRedirect) { consumed = true; tapped }
        return v to consumed
    }

    // #173: meet.google.com → meet.app.goo.gl → this, on a typed URL.
    private val meetIntent = "intent://meet.app.goo.gl/?link=https://meet.google.com/abc-defg-hij" +
        "#Intent;package=com.google.android.gms;action=com.google.firebase.dynamiclinks.VIEW_DYNAMIC_LINK;" +
        "scheme=https;S.browser_fallback_url=https://play.google.com/store/apps/details%3Fid%3Dx;end;"

    @Test
    fun `the redirect of a load the user named may ask without a page tap`() {
        assertEquals(
            ExternalLinkVerdict.AskUserNamed to false,
            verdict(meetIntent, gesture = false, tapped = false, userNamedRedirect = true),
        )
        // Without it, the same typed load is refused, as before (#173's blank page).
        assertEquals(ExternalLinkVerdict.Refuse to false, verdict(meetIntent, gesture = false))
    }

    @Test
    fun `a user-named redirect opens nothing from a subframe or in a blocked scheme`() {
        assertEquals(ExternalLinkVerdict.Refuse to false, verdict(meetIntent, main = false, gesture = false, userNamedRedirect = true))
        assertEquals(ExternalLinkVerdict.Refuse to false, verdict("content://x/y", userNamedRedirect = true))
        assertEquals(ExternalLinkVerdict.NotExternal to false, verdict("https://meet.google.com/x", userNamedRedirect = true))
    }

    @Test
    fun `only a main-frame navigation with a gesture and an unused tap may ask`() {
        assertEquals(ExternalLinkVerdict.Ask to true, verdict("tel:1"))
        assertEquals(ExternalLinkVerdict.Refuse to false, verdict("tel:1", main = false))
        assertEquals(ExternalLinkVerdict.Refuse to false, verdict("tel:1", gesture = false))
        assertEquals(ExternalLinkVerdict.Refuse to true, verdict("tel:1", tapped = false))
    }

    @Test
    fun `blocked schemes are refused without using the tap, browser schemes pass through`() {
        assertEquals(ExternalLinkVerdict.Refuse to false, verdict("content://x/y"))
        assertEquals(ExternalLinkVerdict.NotExternal to false, verdict("https://example.com"))
        assertEquals(ExternalLinkVerdict.NotExternal to false, verdict("bzz://abc"))
    }

    @Test
    fun `a tap buys one launch within the activation window`() {
        var now = 10_000L
        val latch = UserGestureLatch { now }
        assertNull(latch.consume())
        latch.tap()
        assertNotNull(latch.consume())
        assertNull(latch.consume())
        latch.tap()
        now += UserGestureLatch.WINDOW_MS + 1
        assertNull(latch.consume())
        latch.tap()
        now += UserGestureLatch.WINDOW_MS
        assertNotNull(latch.consume())
    }

    /** A tap as PageWebView reports it: ACTION_DOWN, then the ACTION_UP of a tap. */
    private fun UserGestureLatch.tap() {
        onInputStart()
        onInput()
    }

    /** A clock the tests move by hand, and a latch on it. */
    private companion object {
        const val PRICED = "http://localhost:8710/priced"
    }

    private class Clock(var now: Long = 10_000L) {
        val latch = UserGestureLatch { now }

        /** A tap: down now, up [holdMs] later. Returns the down time. */
        fun tap(holdMs: Long = 80): Long {
            val down = now
            latch.onInputStart(down)
            now += holdMs
            latch.onInputContinues(now)
            latch.onInput()
            return down
        }

        /**
         * The top document's word about an event at [eventTime], arriving
         * [transitMs] after the page read it; a `click` unless [isClick]
         * says it was a `pointerdown` / `keydown`.
         */
        fun topDocumentSaw(eventTime: Long, transitMs: Long = 3, isClick: Boolean = true) {
            latch.onTopDocumentInput(now - transitMs - eventTime, isClick)
        }

        /**
         * A navigation of the top frame to [PRICED] starting now, which
         * the top document says it started itself (#348 R5-F1).
         */
        fun topNavigation(): TopDocumentGesture {
            latch.onTopDocumentNavigate(PRICED)
            return latch.topDocumentGesture(PRICED)
        }
    }

    @Test
    fun `a tap the top document confirmed before the navigation is offered at once`() {
        val c = Clock()
        c.latch.onInputStart(c.now)
        c.now += 10
        c.topDocumentSaw(c.now - 10) // its pointerdown, before the finger lifts
        c.latch.onInput()
        var offered = 0
        assertTrue(c.latch.whenInTopDocument(c.latch.consume()!!) { offered++ })
        assertEquals(1, offered)
    }

    @Test
    fun `a tap the top document confirms after the navigation is offered then`() {
        val c = Clock()
        val down = c.tap()
        val id = c.latch.consume()!!
        var offered = 0
        assertTrue(c.latch.whenInTopDocument(id) { offered++ })
        assertEquals(0, offered)
        c.now += 20 // ~20 ms later on the AVD
        c.topDocumentSaw(down)
        assertEquals(1, offered)
        assertFalse(c.latch.giveUp(id))
        c.topDocumentSaw(down)
        assertEquals(1, offered)
    }

    @Test
    fun `a tap on an iframe is never offered, even one that navigates the top frame`() {
        val c = Clock()
        c.tap() // the iframe got the pointerdown; the top document heard nothing
        val id = c.latch.consume()!!
        var offered = 0
        assertTrue(c.latch.whenInTopDocument(id) { offered++ })
        assertTrue(c.latch.giveUp(id)) // CONFIRM_MS later: refused
        assertEquals(0, offered)
    }

    @Test
    fun `a top-document tap can't vouch for a later tap on an iframe`() {
        val c = Clock()
        val first = c.tap() // a tap on the top page, unused
        c.topDocumentSaw(first)
        c.latch.consume()
        c.now += 150
        c.tap() // then a tap on the iframe
        var offered = 0
        assertTrue(c.latch.whenInTopDocument(c.latch.consume()!!) { offered++ })
        assertEquals(0, offered)
    }

    @Test
    fun `a top-document tap's word that arrives late, during a later iframe tap, vouches only for itself`() {
        // R4-F1: an iframe keeps the shared renderer thread busy, so the
        // top page's pointerdown for tap 1 is heard only after tap 2 (on
        // the iframe's target=_top link) and its navigation.
        val c = Clock()
        val first = c.tap()
        c.now += 150
        c.tap() // on the iframe
        val id = c.latch.consume()!!
        var offered = 0
        assertTrue(c.latch.whenInTopDocument(id) { offered++ })
        c.now += 1_200 // the busy loop ends
        c.topDocumentSaw(first)
        assertEquals(0, offered)
        assertTrue(c.latch.giveUp(id))
    }

    // x402 (#348): whose tap a navigation that goes on carried, without using it up.

    @Test
    fun `x402 a tap the top document confirms is its own`() {
        val c = Clock()
        val down = c.tap()
        val gesture = c.topNavigation()
        c.now += 20 // its word lands just after the navigation started
        c.topDocumentSaw(down, isClick = false)
        assertTrue(runBlocking { gesture.confirmed() })
        // Not used up: an app link from the same tap is still offered.
        assertNotNull(c.latch.consume())
    }

    @Test
    fun `x402 a tap on an iframe that navigates the top frame is never confirmed`() {
        val c = Clock()
        c.tap() // the iframe got the pointerdown; the top document heard nothing
        val gesture = c.topNavigation()
        c.now += UserGestureLatch.CONFIRM_MS + 1
        assertFalse(runBlocking { gesture.confirmed() })
    }

    @Test
    fun `x402 a top-document tap can't vouch for an iframe tap still within its activation`() {
        // The iframe keeps its own tap's activation for WINDOW_MS, and can
        // navigate the top frame right after the user's next tap on the page.
        val c = Clock()
        c.tap() // on the iframe
        c.now += 2_000
        val next = c.tap() // on the top page
        c.topDocumentSaw(next)
        val gesture = c.topNavigation()
        c.now += UserGestureLatch.CONFIRM_MS + 1
        c.latch.onInputStart(c.now) // anything that settles it
        assertFalse(runBlocking { gesture.confirmed() })
    }

    @Test
    fun `x402 an iframe tap whose activation has run out doesn't count`() {
        val c = Clock()
        c.tap() // on the iframe
        c.now += INPUT_SYNC_DELAY_MS
        c.latch.onRendererCaughtUp(c.latch.latestInputId) // the renderer was idle
        c.now += UserGestureLatch.WINDOW_MS + 100
        val next = c.tap() // on the top page
        c.topDocumentSaw(next)
        assertTrue(runBlocking { c.topNavigation().confirmed() })
    }

    @Test
    fun `x402 an iframe that held the renderer in its own handler keeps its tap's activation past the native window`() {
        // The R1-F1 repro: the iframe busy-waits 6.5 s in its pointerdown
        // handler, so its activation starts then, not at the tap.
        val c = Clock()
        c.tap() // on the iframe
        val iframeTap = c.latch.latestInputId
        c.now += 6_500
        c.latch.onRendererCaughtUp(iframeTap) // the sync's echo, behind the busy loop
        c.now += 1_000 // 7.5 s after the iframe tap
        val next = c.tap() // on the top page
        c.topDocumentSaw(next)
        val gesture = c.topNavigation()
        c.now += UserGestureLatch.CONFIRM_MS + 1 // its deadline passes, unconfirmed
        assertFalse(runBlocking { gesture.confirmed() })
        // Once WINDOW_MS has passed since the renderer was past it, it's out.
        c.now += UserGestureLatch.WINDOW_MS
        val later = c.tap()
        c.topDocumentSaw(later)
        c.latch.onRendererCaughtUp(c.latch.latestInputId)
        assertTrue(runBlocking { c.topNavigation().confirmed() })
    }

    /**
     * A keyboard edit as PageWebView records it (#348 R3-F1): its start,
     * its end once Chromium has had it, then the renderer's echo.
     */
    private fun Clock.keyboardEdit(): Long {
        val at = now
        latch.onInputStart(at)
        val id = latch.latestInputId
        now += 2
        latch.onInputContinues(now, id)
        now += INPUT_SYNC_DELAY_MS
        latch.onRendererCaughtUp(id)
        return at
    }

    @Test
    fun `x402 R3-F1 typing in an iframe keeps its activation fresh past the tap into its field`() {
        val c = Clock()
        c.tap() // into the iframe's text field
        c.latch.onRendererCaughtUp(c.latch.latestInputId)
        repeat(30) { // 6 s of typing there: no keydown of it is the top document's
            c.now += 200
            c.keyboardEdit()
        }
        c.now += 500
        val next = c.tap() // a plain button on the top page
        c.topDocumentSaw(next)
        val gesture = c.topNavigation()
        c.now += UserGestureLatch.CONFIRM_MS + 1
        assertFalse(runBlocking { gesture.confirmed() })
    }

    @Test
    fun `x402 R3-F1 typing in the top document's own field is confirmed by its keydowns`() {
        val c = Clock()
        c.topDocumentSaw(c.tap()) // into the top page's text field
        repeat(30) {
            c.now += 200
            c.topDocumentSaw(c.keyboardEdit(), isClick = false)
        }
        c.now += 500
        val next = c.tap() // the site's own priced link
        c.topDocumentSaw(next)
        assertTrue(runBlocking { c.topNavigation().confirmed() })
    }

    /**
     * A hardware key press or auto-repeat as PageWebView records it
     * (#348 R4-F1): every one an input, only a fresh press armed.
     */
    private fun Clock.hardwareKey(repeatCount: Int): Long {
        val at = now
        latch.onInputStart(at, key = true)
        // Dispatched a few ms after its event time: a repeat's keydown then
        // also falls in the previous repeat's LATE_MS slack, as on the AVD.
        latch.onInputContinues(at + 5)
        if (repeatCount == 0) latch.onInput()
        now += INPUT_SYNC_DELAY_MS
        latch.onRendererCaughtUp(latch.latestInputId)
        return at
    }

    @Test
    fun `x402 R4-F1 a key held in an iframe keeps its activation fresh past the first press`() {
        val c = Clock()
        c.tap() // into the iframe's text field
        c.latch.onRendererCaughtUp(c.latch.latestInputId)
        c.now += 300
        c.hardwareKey(repeatCount = 0) // Backspace goes down there…
        repeat(150) { c.now += 50; c.hardwareKey(repeatCount = it + 1) } // …and is held 7.5 s
        c.now += 300
        val next = c.tap() // a plain button on the top page
        c.topDocumentSaw(next)
        val gesture = c.topNavigation()
        c.now += UserGestureLatch.CONFIRM_MS + 1
        assertFalse(runBlocking { gesture.confirmed() })
    }

    @Test
    fun `x402 R4-F1 a key held in the top document's own field is confirmed by its keydowns`() {
        val c = Clock()
        c.topDocumentSaw(c.tap()) // into the top page's text field
        c.now += 300
        c.topDocumentSaw(c.hardwareKey(repeatCount = 0), isClick = false)
        repeat(150) { c.now += 50; c.topDocumentSaw(c.hardwareKey(repeatCount = it + 1), isClick = false) }
        c.now += 300
        val next = c.tap() // the site's own priced link
        c.topDocumentSaw(next)
        assertTrue(runBlocking { c.topNavigation().confirmed() })
    }

    @Test
    fun `R4-F1 a held key's keydown is the latest repeat begun by its time`() {
        val c = Clock()
        val first = c.now
        c.latch.onInputStart(first, key = true)
        c.latch.onInputContinues(first + 5)
        c.now = first + 50
        val second = c.now
        c.latch.onInputStart(second, key = true)
        c.latch.onInputContinues(second + 5)
        val id = c.latch.latestInputId
        c.now = second + 4
        c.topDocumentSaw(second, transitMs = 2, isClick = false) // fits both: the second's
        var offered = 0
        assertTrue(c.latch.whenInTopDocument(id) { offered++ })
        assertEquals(1, offered)
        assertTrue(c.latch.whenInTopDocument(id - 1) { offered++ })
        assertEquals(1, offered) // the first still waits on its own keydown
    }

    @Test
    fun `R4-F1 the tie-break is for key inputs only - a tap among the candidates still fails closed`() {
        val c = Clock()
        val tap = c.tap(holdMs = 40)
        c.latch.onInputStart(c.now, key = true)
        c.latch.onInputContinues(c.now + 5)
        val key = c.latch.latestInputId
        c.now += 4
        c.topDocumentSaw(c.now - 3, transitMs = 1, isClick = false) // fits the tap's slack and the key
        var offered = 0
        assertTrue(c.latch.whenInTopDocument(key) { offered++ })
        assertTrue(c.latch.whenInTopDocument(key - 1) { offered++ })
        assertEquals(0, offered)
        assertTrue(tap > 0)
    }

    @Test
    fun `R4-F1 a held key's repeats buy no app-link launch of their own`() {
        val c = Clock()
        c.hardwareKey(repeatCount = 0)
        assertNotNull(c.latch.consume())
        repeat(10) { c.now += 50; c.hardwareKey(repeatCount = it + 1) }
        assertNull(c.latch.consume())
    }

    @Test
    fun `x402 R3-F1 a keyboard edit's end lands on it even after a later input began`() {
        val c = Clock()
        val at = c.now
        c.latch.onInputStart(at) // the edit, recorded ahead of Chromium's task
        val id = c.latch.latestInputId
        c.now += 1
        c.latch.onInputStart(c.now) // a key press lands in between
        c.latch.onInputContinues(c.now + 1)
        c.now = at + 100
        c.latch.onInputContinues(c.now, id) // the edit's end: Chromium has stamped its keydown by now
        c.topDocumentSaw(at + 90, isClick = false) // that keydown: past the key press's end, inside the edit
        var confirmed = false
        assertTrue(c.latch.whenInTopDocument(id) { confirmed = true })
        assertTrue(confirmed)
    }

    @Test
    fun `x402 an unconfirmed input the renderer was never heard past counts however old`() {
        val c = Clock()
        c.tap() // on the iframe; no echo (a renderer still stuck in it, or no detector)
        c.now += 60_000
        val next = c.tap()
        c.topDocumentSaw(next)
        val gesture = c.topNavigation()
        c.now += UserGestureLatch.CONFIRM_MS + 1
        assertFalse(runBlocking { gesture.confirmed() })
    }

    @Test
    fun `x402 an unconfirmed input dropped from the record still counts until the renderer is past it`() {
        val c = Clock()
        c.tap() // on the iframe, held up
        repeat(40) { // a burst of top-page taps, past the record's cap
            c.now += 50
            c.topDocumentSaw(c.tap())
        }
        assertFalse(runBlocking { c.topNavigation().confirmed() })
        c.latch.onRendererCaughtUp(c.latch.latestInputId)
        c.now += UserGestureLatch.WINDOW_MS + 1
        val next = c.tap()
        c.topDocumentSaw(next)
        c.latch.onRendererCaughtUp(c.latch.latestInputId)
        assertTrue(runBlocking { c.topNavigation().confirmed() })
    }

    @Test
    fun `x402 an echo can't name an input that hasn't happened`() {
        val c = Clock()
        c.latch.onRendererCaughtUp(5) // ahead of any input: ignored
        c.tap() // on the iframe
        c.now += UserGestureLatch.WINDOW_MS + 100
        val next = c.tap()
        c.topDocumentSaw(next)
        val gesture = c.topNavigation()
        c.now += UserGestureLatch.CONFIRM_MS + 1
        assertFalse(runBlocking { gesture.confirmed() })
    }

    @Test
    fun `x402 no input at all, or a word too late, confirms nothing`() {
        val c = Clock()
        assertFalse(runBlocking { c.topNavigation().confirmed() })
        val down = c.tap()
        val gesture = c.topNavigation()
        c.now += UserGestureLatch.CONFIRM_MS + 1 // a busy renderer's word
        c.topDocumentSaw(down)
        assertFalse(runBlocking { gesture.confirmed() })
    }

    @Test
    fun `x402 R5-F1 an iframe's navigation during the user's tap on the top page is never confirmed`() {
        // The iframe was tapped long ago (enough to let it navigate the top
        // frame at all), then sets top.location from its blur handler as the
        // user taps the top page: hasGesture, and the only input is the top
        // document's, confirmed — but the top document started no navigation.
        val c = Clock()
        c.tap() // into the iframe's field, long ago
        c.latch.onRendererCaughtUp(c.latch.latestInputId)
        c.now += 30_000
        val next = c.tap() // a plain button on the top page
        c.topDocumentSaw(next)
        val gesture = c.latch.topDocumentGesture(PRICED)
        c.now += UserGestureLatch.CONFIRM_MS + 1
        c.latch.onInputStart(c.now) // anything that settles it
        assertFalse(runBlocking { gesture.confirmed() })
    }

    @Test
    fun `x402 R5-F1 the top document's word on its navigation may come before or after it starts`() {
        val c = Clock()
        val down = c.tap()
        c.topDocumentSaw(down)
        val gesture = c.latch.topDocumentGesture(PRICED)
        c.now += 30
        c.latch.onTopDocumentNavigate(PRICED) // its navigate event, a task later
        assertTrue(runBlocking { gesture.confirmed() })
    }

    @Test
    fun `x402 R5-F1 a word on another URL, a stale one, or one already used vouches for nothing`() {
        val c = Clock()
        val down = c.tap()
        c.topDocumentSaw(down)
        c.latch.onTopDocumentNavigate("http://localhost:8710/elsewhere")
        val other = c.latch.topDocumentGesture(PRICED)
        c.now += UserGestureLatch.CONFIRM_MS + 1
        c.latch.onInputStart(c.now)
        assertFalse(runBlocking { other.confirmed() })

        val c2 = Clock()
        c2.topDocumentSaw(c2.tap())
        c2.latch.onTopDocumentNavigate(PRICED) // the top page's own, then cancelled by a later one
        c2.now += UserGestureLatch.CONFIRM_MS + 1
        val stale = c2.latch.topDocumentGesture(PRICED)
        c2.now += UserGestureLatch.CONFIRM_MS + 1
        c2.latch.onInputStart(c2.now)
        assertFalse(runBlocking { stale.confirmed() })

        val c3 = Clock()
        c3.topDocumentSaw(c3.tap())
        assertTrue(runBlocking { c3.topNavigation().confirmed() })
        val again = c3.latch.topDocumentGesture(PRICED) // an iframe's, right after: no word of its own
        c3.now += UserGestureLatch.CONFIRM_MS + 1
        c3.latch.onInputStart(c3.now)
        assertFalse(runBlocking { again.confirmed() })
    }

    @Test
    fun `x402 382 R1-M1 the top document's word on a navigation without a gesture is used up by it`() {
        // A timer on the top page navigates to the priced URL (no gesture);
        // a cross-origin iframe's gestured load of the same URL right after
        // must not take that word as its own.
        for (wordFirst in listOf(true, false)) {
            val c = Clock()
            c.topDocumentSaw(c.tap())
            if (wordFirst) c.latch.onTopDocumentNavigate(PRICED)
            c.latch.onTopNavigationWithoutGesture(PRICED)
            if (!wordFirst) {
                c.now += 5
                c.latch.onTopDocumentNavigate(PRICED)
            }
            c.now += 10
            val iframe = c.latch.topDocumentGesture(PRICED)
            c.now += UserGestureLatch.CONFIRM_MS + 1
            c.latch.onInputStart(c.now)
            assertFalse(runBlocking { iframe.confirmed() })
        }
        // With no such navigation, the top document's own gestured one still is.
        val c = Clock()
        c.topDocumentSaw(c.tap())
        assertTrue(runBlocking { c.topNavigation().confirmed() })
    }

    @Test
    fun `x402 R5-F2 a media key pressed into an iframe keeps the top document from vouching`() {
        // Headset play/pause reaches the focused iframe as a trusted keydown
        // and renews its activation: it is recorded, and never confirmed.
        val c = Clock()
        assertTrue(keyIsPageInput(android.view.KeyEvent.ACTION_DOWN, isModifier = false))
        c.hardwareKey(repeatCount = 0)
        c.now += 1_000
        val next = c.tap() // the top page
        c.topDocumentSaw(next)
        val gesture = c.topNavigation()
        c.now += UserGestureLatch.CONFIRM_MS + 1
        c.latch.onInputStart(c.now)
        assertFalse(runBlocking { gesture.confirmed() })
    }

    @Test
    fun `x402 an accessibility click is confirmed by the top document's click`() {
        val c = Clock()
        c.latch.onInputStart(c.now, untilConfirmed = true)
        c.latch.onInput()
        val gesture = c.topNavigation()
        c.now += 300 // Blink runs the simulated click late
        c.topDocumentSaw(c.now - 5)
        assertTrue(runBlocking { gesture.confirmed() })
    }

    @Test
    fun `nor can a later top-document tap vouch for an earlier iframe tap`() {
        val c = Clock()
        c.tap() // on the iframe
        val id = c.latch.consume()!!
        var offered = 0
        assertTrue(c.latch.whenInTopDocument(id) { offered++ })
        c.now += 150
        val next = c.tap() // the next tap, on the top page
        c.topDocumentSaw(next)
        assertEquals(0, offered)
        assertTrue(c.latch.giveUp(id))
    }

    @Test
    fun `later input doesn't drop an offer waiting on a slow redirect chain`() {
        // R4-F2: a tapped link redirects to an app scheme a second later;
        // meanwhile the user touches the page again (a scroll's start).
        val c = Clock()
        val down = c.tap()
        c.topDocumentSaw(down)
        c.now += 500
        c.latch.onInputStart(c.now) // a touch that becomes a scroll: no tap
        c.topDocumentSaw(c.now)
        c.now += 700
        var offered = 0
        assertTrue(c.latch.whenInTopDocument(c.latch.consume()!!) { offered++ })
        assertEquals(1, offered)
    }

    @Test
    fun `an offer already waiting is still run by its own input's late word`() {
        val c = Clock()
        val down = c.tap()
        val id = c.latch.consume()!!
        var offered = 0
        assertTrue(c.latch.whenInTopDocument(id) { offered++ })
        c.now += 200
        c.latch.onInputStart(c.now) // another touch begins before the word arrives
        c.topDocumentSaw(down)
        assertEquals(1, offered)
    }

    @Test
    fun `a word that fits two inputs confirms neither`() {
        val c = Clock()
        val first = c.tap(holdMs = 40)
        c.now += 20
        c.tap() // not human, but: 20 ms after the first lifted
        val id = c.latch.consume()!!
        var offered = 0
        c.topDocumentSaw(first + 50, transitMs = 0) // after tap 1's end, in tap 2
        assertTrue(c.latch.whenInTopDocument(id) { offered++ })
        assertEquals(0, offered)
    }

    @Test
    fun `an accessibility click is confirmed when a busy renderer runs it late`() {
        // R5-F1: Blink stamps the click it simulates for TalkBack when it
        // runs it, here 200 ms after the double-tap behind a long task.
        val c = Clock()
        val at = c.now
        c.latch.onInputStart(at, untilConfirmed = true)
        c.latch.onInput()
        val id = c.latch.consume()!!
        var offered = 0
        assertTrue(c.latch.whenInTopDocument(id) { offered++ })
        c.now += 200
        c.topDocumentSaw(c.now - 5)
        assertEquals(1, offered)
    }

    @Test
    fun `an accessibility click on an iframe is still refused`() {
        val c = Clock()
        c.latch.onInputStart(c.now, untilConfirmed = true)
        c.latch.onInput()
        val id = c.latch.consume()!!
        var offered = 0
        assertTrue(c.latch.whenInTopDocument(id) { offered++ })
        c.now += 300
        c.topDocumentSaw(c.now - 400) // an event before the click: not its word
        c.now += UserGestureLatch.CONFIRM_MS // the iframe got the click: no word
        assertTrue(c.latch.giveUp(id))
        assertEquals(0, offered)
    }

    @Test
    fun `a confirmed accessibility click closes, so a later tap's word is the tap's own`() {
        val c = Clock()
        c.latch.onInputStart(c.now, untilConfirmed = true)
        c.latch.onInput()
        c.now += 100
        c.topDocumentSaw(c.now - 1) // the click's own word
        c.latch.consume()
        c.now += 300
        val down = c.tap()
        var offered = 0
        assertTrue(c.latch.whenInTopDocument(c.latch.consume()!!) { offered++ })
        c.topDocumentSaw(down)
        assertEquals(1, offered)
    }

    @Test
    fun `an unconfirmed accessibility click and a later tap share a word, so neither is confirmed`() {
        // Fail closed: the word could be either's.
        val c = Clock()
        c.latch.onInputStart(c.now, untilConfirmed = true)
        c.latch.onInput()
        val click = c.latch.consume()!!
        c.now += 300
        val down = c.tap()
        val tap = c.latch.consume()!!
        var offered = 0
        assertTrue(c.latch.whenInTopDocument(click) { offered++ })
        assertTrue(c.latch.whenInTopDocument(tap) { offered++ })
        c.topDocumentSaw(down)
        assertEquals(0, offered)
    }

    @Test
    fun `only a click confirms an accessibility click, not a keydown Android never recorded`() {
        // R6-1: an accessibility click on an iframe's target=_top link,
        // then an IME keystroke (or a key repeat, or a lone modifier) in
        // the top document: a trusted keydown with no recorded input.
        val c = Clock()
        c.latch.onInputStart(c.now, untilConfirmed = true)
        c.latch.onInput()
        var offered = 0
        assertTrue(c.latch.whenInTopDocument(c.latch.consume()!!) { offered++ })
        c.now += 400
        c.topDocumentSaw(c.now - 1, isClick = false)
        assertEquals(0, offered)
        c.now += 10
        c.topDocumentSaw(c.now - 1, isClick = false, transitMs = 0) // nor a pointerdown
        assertEquals(0, offered)
    }

    @Test
    fun `a pointerdown still confirms a tap`() {
        val c = Clock()
        val down = c.tap()
        var offered = 0
        assertTrue(c.latch.whenInTopDocument(c.latch.consume()!!) { offered++ })
        c.topDocumentSaw(down, isClick = false)
        assertEquals(1, offered)
    }

    @Test
    fun `a repeated accessibility click on a busy page still gets its prompt`() {
        // R6-2: a TalkBack double-tap on a busy page seems ignored, so the
        // user double-taps again 600 ms later. Blink then runs both
        // clicks; each one's word fits both open inputs. The first
        // navigation took input 2 (the latest); the oldest open input
        // takes the first word, so the second word is input 2's.
        val c = Clock()
        val t0 = c.now
        c.latch.onInputStart(t0, untilConfirmed = true)
        c.latch.onInput()
        c.now = t0 + 600
        c.latch.onInputStart(c.now, untilConfirmed = true)
        c.latch.onInput()
        c.now = t0 + 800
        val id = c.latch.consume()!!
        var offered = 0
        assertTrue(c.latch.whenInTopDocument(id) { offered++ })
        c.now = t0 + 820
        c.topDocumentSaw(t0 + 800)
        c.now = t0 + 825
        c.topDocumentSaw(t0 + 805)
        assertEquals(1, offered)
        assertNull(c.latch.consume()) // a third navigation: nothing waits, nothing to take over
    }

    @Test
    fun `a repeated accessibility activation of another link prompts for the later link (R1-F2)`() {
        // TalkBack on a busy page: tel:A, then tel:B. Navigation A takes
        // the latch (input 2); navigation B takes over the offer waiting
        // on input 2, which the second click's word then confirms.
        val c = Clock()
        val t0 = c.now
        c.latch.onInputStart(t0, untilConfirmed = true)
        c.latch.onInput()
        c.now = t0 + 600
        c.latch.onInputStart(c.now, untilConfirmed = true)
        c.latch.onInput()
        c.now = t0 + 800
        val offered = mutableListOf<String>()
        val a = { offered += "A"; Unit }
        val idA = c.latch.consume()!!
        assertTrue(c.latch.whenInTopDocument(idA, a))
        c.now = t0 + 820
        c.topDocumentSaw(t0 + 800) // click 1's word: input 1
        c.now = t0 + 830
        val b = { offered += "B"; Unit }
        val idB = c.latch.consume()!!
        assertEquals(idA, idB)
        assertTrue(c.latch.whenInTopDocument(idB, b))
        // A's deadline no longer refuses the offer B took over.
        assertFalse(c.latch.giveUp(idA, a))
        c.now = t0 + 840
        c.topDocumentSaw(t0 + 832) // click 2's word: input 2
        assertEquals(listOf("B"), offered)
        assertFalse(c.latch.giveUp(idB, b))
    }

    @Test
    fun `a single accessibility activation's script burst still gets one launch`() {
        val c = Clock()
        c.latch.onInputStart(c.now, untilConfirmed = true)
        c.latch.onInput()
        val id = c.latch.consume()!!
        assertTrue(c.latch.whenInTopDocument(id) {})
        assertNull(c.latch.consume()) // location = 'sms:…' right after: no take-over
    }

    @Test
    fun `a take-over can't make an iframe's repeated accessibility link the top page's`() {
        // Link A on the top page (word, navigation), then link B on an
        // iframe's target=_top (navigation only). B takes over the offer
        // on input 2, which no top-document word ever confirms.
        val c = Clock()
        val t0 = c.now
        c.latch.onInputStart(t0, untilConfirmed = true)
        c.latch.onInput()
        c.now = t0 + 600
        c.latch.onInputStart(c.now, untilConfirmed = true)
        c.latch.onInput()
        c.now = t0 + 800
        var offered = 0
        val id = c.latch.consume()!!
        assertTrue(c.latch.whenInTopDocument(id) { offered++ })
        c.now = t0 + 820
        c.topDocumentSaw(t0 + 800)
        c.now = t0 + 830
        val b = { offered++; Unit }
        assertTrue(c.latch.whenInTopDocument(c.latch.consume()!!, b))
        assertEquals(0, offered)
        assertTrue(c.latch.giveUp(id, b))
        assertEquals(0, offered)
    }

    @Test
    fun `only a redirect hop of a tab's own main-frame navigation keeps the page when cancelled`() {
        assertTrue(externalLinkKeepsPage(isForMainFrame = true, isRedirect = true, popupFirstNavigation = false))
        assertFalse(externalLinkKeepsPage(isForMainFrame = true, isRedirect = false, popupFirstNavigation = false))
        assertFalse(externalLinkKeepsPage(isForMainFrame = false, isRedirect = true, popupFirstNavigation = false))
        assertFalse(externalLinkKeepsPage(isForMainFrame = true, isRedirect = true, popupFirstNavigation = true))
    }

    @Test
    fun `a later top-document accessibility click can't vouch for an earlier one on an iframe`() {
        // The oldest-open rule skips an input a navigation already took:
        // its click ran before that navigation, so a later word isn't it.
        val c = Clock()
        c.latch.onInputStart(c.now, untilConfirmed = true) // on an iframe's target=_top link
        c.latch.onInput()
        val id = c.latch.consume()!!
        var offered = 0
        assertTrue(c.latch.whenInTopDocument(id) { offered++ })
        c.now += 300
        c.latch.onInputStart(c.now, untilConfirmed = true) // then on the top page
        c.latch.onInput()
        c.now += 20
        c.topDocumentSaw(c.now - 10)
        assertEquals(0, offered)
        assertTrue(c.latch.giveUp(id))
        // And that word was the second click's.
        var second = 0
        assertTrue(c.latch.whenInTopDocument(c.latch.consume()!!) { second++ })
        assertEquals(1, second)
    }

    @Test
    fun `a word that fits no input confirms nothing`() {
        val c = Clock()
        val down = c.tap()
        c.topDocumentSaw(down - 500)
        c.topDocumentSaw(c.now + 500) // a negative age can't come from the detector, but still
        var offered = 0
        assertTrue(c.latch.whenInTopDocument(c.latch.consume()!!) { offered++ })
        assertEquals(0, offered)
    }

    @Test
    fun `the top document's word alone, with no tap, buys nothing`() {
        val c = Clock()
        c.latch.onTopDocumentInput(0, isClick = true)
        assertNull(c.latch.consume())
    }

    @Test
    fun `the top document's input message carries the event's age`() {
        assertEquals(TopDocumentInput(12L, isClick = true), parseTopDocumentInput("input click 12"))
        assertEquals(TopDocumentInput(0L, isClick = false), parseTopDocumentInput("input pointerdown 0"))
        assertEquals(TopDocumentInput(5L, isClick = false), parseTopDocumentInput("input keydown 5"))
        for (bad in listOf(
            "input", "input 12", "input click", "input click -3", "input click 1.5", "input click 12 ",
            "xinput click 1", "input click 12345678", "input keyup 3", "input  click 3", null,
        )) {
            assertNull(bad, parseTopDocumentInput(bad))
        }
    }

    @Test
    fun `an intent URL that fails to parse in any way is no link, not a crash`() {
        // Intent.parseUri throws NumberFormatException for `i.n=zz` or
        // `launchFlags=zz`, not only URISyntaxException.
        for (thrown in listOf(
            java.net.URISyntaxException("x", "bad"),
            NumberFormatException("zz"),
            IllegalArgumentException("bad"),
            IndexOutOfBoundsException(),
        )) {
            assertNull(parseIntentUrl("intent://x#Intent;scheme=foo;i.n=zz;end") { throw thrown })
        }
    }

    @Test
    fun `only a fresh press of a page key arms the latch`() {
        val down = android.view.KeyEvent.ACTION_DOWN
        val up = android.view.KeyEvent.ACTION_UP
        assertTrue(keyArmsGestureLatch(down, repeatCount = 0, isSystem = false, isModifier = false))
        // Holding a key: every auto-repeat is another ACTION_DOWN.
        assertFalse(keyArmsGestureLatch(down, repeatCount = 1, isSystem = false, isModifier = false))
        assertFalse(keyArmsGestureLatch(down, repeatCount = 30, isSystem = false, isModifier = false))
        // Volume, media, back: pressed at the device, not at the page.
        assertFalse(keyArmsGestureLatch(down, repeatCount = 0, isSystem = true, isModifier = false))
        assertFalse(keyArmsGestureLatch(down, repeatCount = 0, isSystem = false, isModifier = true))
        assertFalse(keyArmsGestureLatch(up, repeatCount = 0, isSystem = false, isModifier = false))
    }

    @Test
    fun `every press and auto-repeat of a page key is recorded as input`() {
        val down = android.view.KeyEvent.ACTION_DOWN
        val up = android.view.KeyEvent.ACTION_UP
        // R4-F1: a repeat is a trusted keydown that renews the focused frame's activation.
        assertTrue(keyIsPageInput(down, isModifier = false))
        // R5-F2: a system key too — a media key or Search reaches the focused frame.
        assertFalse(keyIsPageInput(down, isModifier = true))
        assertFalse(keyIsPageInput(up, isModifier = false))
    }

    @Test
    fun `an accessibility click arms the latch, other accessibility actions don't`() {
        val info = android.view.accessibility.AccessibilityNodeInfo::class.java
        fun action(name: String) = info.getField(name).getInt(null)
        assertTrue(accessibilityActionArmsGestureLatch(action("ACTION_CLICK")))
        for (name in listOf(
            "ACTION_FOCUS", "ACTION_ACCESSIBILITY_FOCUS", "ACTION_LONG_CLICK",
            "ACTION_SCROLL_FORWARD", "ACTION_SELECT", "ACTION_NEXT_AT_MOVEMENT_GRANULARITY",
        )) {
            assertFalse(name, accessibilityActionArmsGestureLatch(action(name)))
        }
    }

    @Test
    fun `only a tap arms the latch, not a scroll, fling or pinch`() {
        val taps = TapTracker(slopPx = 10f)
        taps.onDown(100f, 100f)
        taps.onMove(104f, 103f)
        assertTrue(taps.onUp(105f, 105f))

        taps.onDown(100f, 100f)
        taps.onMove(100f, 160f)
        // Scrolled away and back: still a scroll.
        taps.onMove(100f, 101f)
        assertFalse(taps.onUp(100f, 101f))

        // A fling whose lift lands past the slop with no move in between.
        taps.onDown(100f, 100f)
        assertFalse(taps.onUp(100f, 300f))

        taps.onDown(100f, 100f)
        taps.onCancel() // second finger down
        assertFalse(taps.onUp(100f, 100f))

        // An up with no down (touch that started elsewhere) is no tap.
        assertFalse(taps.onUp(100f, 100f))
    }

    @Test
    fun `log lines never carry the address`() {
        assertEquals("mailto:<redacted>", externalUrlForLog("mailto:someone@example.com"))
        assertEquals("unknown", externalUrlForLog("garbage"))
    }

    // R1-F1 / R1-F2 (#173): the chain a user-named load may end in an
    // app link through, hop by hop.
    @Test
    fun `a user-named chain asks for the hop that redirected, not the address typed`() {
        val chain = UserNamedChain()
        chain.started("https://a.example/go")
        chain.mainFrameRequested("https://a.example/go")
        assertEquals("https://a.example/go", chain.asker())
        // a.example is an open redirect to evil.example, which answers intent:.
        chain.redirected("https://evil.example/x")
        chain.mainFrameRequested("https://evil.example/x")
        assertEquals("https://evil.example/x", chain.asker())
    }

    @Test
    fun `a main-frame request of the page's own ends a user-named chain`() {
        // Typed /stall answered 204 (or stopped); the page on screen then
        // posts a form whose 302 goes to tel:.
        val chain = UserNamedChain()
        chain.started("http://10.0.2.2:8701/stall")
        chain.mainFrameRequested("http://10.0.2.2:8701/stall")
        chain.mainFrameRequested("http://10.0.2.2:8700/form")
        assertNull(chain.asker())
        // Nor does a later redirect start it again.
        chain.redirected("http://10.0.2.2:8700/next")
        assertNull(chain.asker())
    }

    @Test
    fun `a repeated request for the awaited hop keeps the chain`() {
        // A cold load: Chromium asked for the typed address twice.
        val chain = UserNamedChain()
        chain.started("https://meet.google.com/abc-defg-hij")
        chain.mainFrameRequested("https://meet.google.com/abc-defg-hij")
        chain.mainFrameRequested("https://meet.google.com/abc-defg-hij")
        chain.redirected("https://meet.app.goo.gl/?link=x")
        chain.mainFrameRequested("https://meet.app.goo.gl/?link=x")
        assertEquals("https://meet.app.goo.gl/?link=x", chain.asker())
    }

    @Test
    fun `a first request that isn't the named address ends the chain`() {
        val chain = UserNamedChain()
        chain.started("https://meet.google.com/abc-defg-hij")
        chain.mainFrameRequested("https://other.example/")
        assertNull(chain.asker())
    }

    @Test
    fun `a redirect hop Chromium does or doesn't re-request keeps the chain`() {
        val seen = UserNamedChain()
        seen.started("https://Meet.Google.com")
        seen.mainFrameRequested("https://meet.google.com/")
        seen.redirected("https://meet.app.goo.gl/?link=x")
        seen.mainFrameRequested("https://meet.app.goo.gl/?link=x")
        assertEquals("https://meet.app.goo.gl/?link=x", seen.asker())

        val unseen = UserNamedChain()
        unseen.started("https://meet.google.com/abc")
        unseen.mainFrameRequested("https://meet.google.com/abc")
        unseen.redirected("https://meet.app.goo.gl/?link=x")
        assertEquals("https://meet.app.goo.gl/?link=x", unseen.asker())
        // But a request that is neither hop still ends it.
        unseen.mainFrameRequested("https://page.example/post")
        assertNull(unseen.asker())
    }

    @Test
    fun `an ended chain stays ended`() {
        val chain = UserNamedChain()
        chain.started("https://a.example/")
        chain.ended()
        chain.mainFrameRequested("https://a.example/")
        assertNull(chain.asker())
    }

    // R2-F1: a service worker answers the page's form post, so no
    // main-frame request is seen — the named load's end must be.
    @Test
    fun `a named load that stops without a commit ends the chain`() {
        val chain = UserNamedChain()
        chain.started("http://localhost:8701/nc")
        chain.mainFrameRequested("http://localhost:8701/nc")
        // 204: WebView reports onPageFinished for the aborted navigation.
        chain.loadFinished("http://localhost:8701/nc", "http://localhost:8700/p")
        assertNull(chain.asker())
        // The page's SW-answered post then redirects: not the chain's.
        chain.redirected("tel:5551234")
        assertNull(chain.asker())
    }

    @Test
    fun `a named load's redirect hop that stops ends the chain`() {
        val chain = UserNamedChain()
        chain.started("https://a.example/go")
        chain.redirected("https://b.example/nc")
        chain.loadFinished("https://b.example/nc", "https://page.example/")
        assertNull(chain.asker())
    }

    @Test
    fun `the page on screen finishing its own load keeps a named chain in flight`() {
        val chain = UserNamedChain()
        chain.started("https://meet.google.com/abc")
        chain.loadFinished("https://page.example/", "https://page.example/")
        assertEquals("https://meet.google.com/abc", chain.asker())
        // But the same URL as the awaited hop is the named load's end.
        val same = UserNamedChain()
        same.started("https://page.example/")
        same.loadFinished("https://page.example/", "https://page.example/")
        assertNull(same.asker())
    }

    @Test
    fun `any other finished load ends a named chain`() {
        val chain = UserNamedChain()
        chain.started("https://meet.google.com/abc")
        chain.loadFinished("tel:5551234", "https://page.example/")
        assertNull(chain.asker())
        // With nothing committed yet (a fresh tab), nothing is on screen.
        val fresh = UserNamedChain()
        fresh.started("https://meet.google.com/abc")
        fresh.loadFinished("https://page.example/", null)
        assertNull(fresh.asker())
        // …but the blank entry of Home / a fresh tab finishing is no end.
        val home = UserNamedChain()
        home.started("https://meet.google.com/abc")
        home.loadFinished(ABOUT_BLANK, null)
        assertEquals("https://meet.google.com/abc", home.asker())
    }

    @Test
    fun `request urls compare as Chromium canonicalizes them`() {
        assertTrue(sameRequestUrl("https://Example.COM", "https://example.com/"))
        assertTrue(sameRequestUrl("http://example.com:80/a?b=1#frag", "http://example.com/a?b=1"))
        assertFalse(sameRequestUrl("https://example.com/a", "https://example.com/b"))
        assertFalse(sameRequestUrl("https://example.com:8443/", "https://example.com/"))
        assertFalse(sameRequestUrl("https://example.com/?a", "https://example.com/?b"))
    }

    private val ownLink = AppActivity("baby.freedom.mobile", "baby.freedom.mobile.IncomingLinkActivity")
    private val chrome = AppActivity("com.android.chrome", "com.google.android.apps.chrome.IntentDispatcher")
    private val resolver = AppActivity("android", "com.android.internal.app.ResolverActivity")

    @Test
    fun `a web intent with another default browser goes to it`() {
        // intent://x#Intent;scheme=https;end, Chrome the default: Chrome
        // opens it (no REQUIRE_NON_BROWSER, R2-M1).
        assertEquals(
            ExternalLaunchRoute.Direct,
            externalLaunchRoute(listOf(ownLink, chrome), chrome, "baby.freedom.mobile"),
        )
    }

    @Test
    fun `an intent Freedom is the default for is its own`() {
        assertEquals(
            ExternalLaunchRoute.Self,
            externalLaunchRoute(listOf(ownLink, chrome), ownLink, "baby.freedom.mobile"),
        )
        assertEquals(
            ExternalLaunchRoute.Self,
            externalLaunchRoute(listOf(ownLink), ownLink, "baby.freedom.mobile"),
        )
    }

    @Test
    fun `with no default the chooser leaves Freedom out`() {
        // ipfs: with another handler and no default: Android's resolver
        // would list Freedom (R2-M2).
        val other = AppActivity("org.example.ipfs", "org.example.ipfs.Open")
        assertEquals(
            ExternalLaunchRoute.ChooserExcluding(listOf(ownLink)),
            externalLaunchRoute(listOf(ownLink, other), resolver, "baby.freedom.mobile"),
        )
    }

    @Test
    fun `an intent Freedom can't take starts as it is`() {
        val zoom = AppActivity("us.zoom.videomeetings", "com.zipow.Join")
        assertEquals(
            ExternalLaunchRoute.Direct,
            externalLaunchRoute(listOf(zoom), zoom, "baby.freedom.mobile"),
        )
        // Several other apps, none Freedom: Android's resolver is fine.
        assertEquals(
            ExternalLaunchRoute.Direct,
            externalLaunchRoute(listOf(zoom, chrome), resolver, "baby.freedom.mobile"),
        )
        // Nothing visible: Android decides (and may find no app).
        assertEquals(ExternalLaunchRoute.Direct, externalLaunchRoute(emptyList(), null, "baby.freedom.mobile"))
    }
}
