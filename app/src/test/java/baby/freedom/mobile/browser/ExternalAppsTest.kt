package baby.freedom.mobile.browser

import org.junit.Assert.assertEquals
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
        assertFalse(latch.consume())
        latch.onInput()
        assertTrue(latch.consume())
        assertFalse(latch.consume())
        latch.onInput()
        now += UserGestureLatch.WINDOW_MS + 1
        assertFalse(latch.consume())
        latch.onInput()
        now += UserGestureLatch.WINDOW_MS
        assertTrue(latch.consume())
    }

    @Test
    fun `log lines never carry the address`() {
        assertEquals("mailto:<redacted>", externalUrlForLog("mailto:someone@example.com"))
        assertEquals("unknown", externalUrlForLog("garbage"))
    }
}
