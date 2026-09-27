package baby.freedom.mobile.browser

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
}
