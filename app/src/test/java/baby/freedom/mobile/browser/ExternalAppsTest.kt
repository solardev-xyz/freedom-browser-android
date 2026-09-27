package baby.freedom.mobile.browser

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
        val o = "https://example.com"
        s.record(o, ExternalScheme("magnet"), PermissionDecision.ALLOW, remembered = false)
        assertEquals(PermissionPlan.Grant, planFor(o, listOf(ExternalScheme("magnet")), emptyMap(), s))
        assertEquals(
            PermissionPlan.Ask(listOf(ExternalScheme("sms"))),
            planFor(o, listOf(ExternalScheme("sms")), emptyMap(), s),
        )
        assertEquals(
            PermissionPlan.Ask(listOf(ExternalScheme("magnet"))),
            planFor("https://other.example", listOf(ExternalScheme("magnet")), emptyMap(), s),
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
    ): Pair<ExternalLinkVerdict, Boolean> {
        var consumed = false
        val v = externalLinkVerdict(url, main, gesture) { consumed = true; tapped }
        return v to consumed
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

        /** The top document's word about an event at [eventTime], arriving [transitMs] after the page read it. */
        fun topDocumentSaw(eventTime: Long, transitMs: Long = 3) {
            latch.onTopDocumentInput(now - transitMs - eventTime)
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
        c.topDocumentSaw(c.now - 2)
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
        c.latch.onTopDocumentInput(0)
        assertNull(c.latch.consume())
    }

    @Test
    fun `the top document's input message carries the event's age`() {
        assertEquals(12L, parseTopDocumentInput("input 12"))
        assertEquals(0L, parseTopDocumentInput("input 0"))
        for (bad in listOf("input", "input ", "input -3", "input 1.5", "input 12 ", "xinput 1", "input 12345678", null)) {
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
}
