package baby.freedom.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pop-up blocker's rule and its per-tab notice state (#261). */
class PopupBlockerTest {
    @Test
    fun `a window opens with the user gesture or on an allowed site, and is blocked otherwise`() {
        assertTrue(popupOpens(isUserGesture = true, siteAllowed = false))
        assertTrue(popupOpens(isUserGesture = true, siteAllowed = true))
        assertTrue(popupOpens(isUserGesture = false, siteAllowed = true))
        assertFalse(popupOpens(isUserGesture = false, siteAllowed = false))
    }

    @Test
    fun `the pop-ups allow is a site capability under the desktop storage key, needing nothing from Android`() {
        assertEquals(SitePermission.POPUPS, SiteCapability.forKey("popups"))
        assertTrue(SitePermission.POPUPS.androidPermissions.isEmpty())
    }

    @Test
    fun `a blocked pop-up shows at once and gets its address when the probe reports`() {
        val popups = BlockedPopups()
        val doc = popups.document
        val id = popups.add("https://a.example", pending = true)!!
        assertEquals(listOf(BlockedPopup(id, url = null, pending = true)), popups.entries)
        assertEquals("https://a.example", popups.origin)
        popups.resolve(doc, id, "https://pay.example/checkout")
        assertEquals(listOf(BlockedPopup(id, url = "https://pay.example/checkout")), popups.entries)
    }

    @Test
    fun `a late probe report for a document the tab has left is dropped`() {
        val popups = BlockedPopups()
        val doc = popups.document
        val id = popups.add("https://a.example", pending = true)!!
        popups.startDocument()
        assertTrue(popups.entries.isEmpty())
        assertNull(popups.origin)
        popups.resolve(doc, id, "https://late.example/")
        assertTrue(popups.entries.isEmpty())
    }

    @Test
    fun `a page blocked in a loop keeps the first entries and only counts the rest`() {
        val popups = BlockedPopups()
        val ids = (1..10).map { popups.add("https://a.example", pending = false) }
        assertEquals(10, popups.count)
        assertEquals(BlockedPopups.MAX_ENTRIES, popups.entries.size)
        assertEquals(ids.take(BlockedPopups.MAX_ENTRIES), popups.entries.map { it.id })
        // Past the cap nothing is listed: no id, so no probe is started for it.
        assertTrue(ids.drop(BlockedPopups.MAX_ENTRIES).all { it == null })
        assertEquals("10 pop-ups blocked", blockedPopupsTitle(popups.count))
        assertEquals("Pop-up blocked", blockedPopupsTitle(1))
    }

    @Test
    fun `the tap guard's key moves with the rows, not with a count ticking up in a loop`() {
        // #292 R1-M2, R1-M4.
        val popups = BlockedPopups()
        val doc = popups.document
        val keys = mutableListOf(popups.layoutKey)
        val first = popups.add("https://a.example", pending = true)!!
        keys += popups.layoutKey
        popups.resolve(doc, first, "https://pay.example/checkout")
        keys += popups.layoutKey
        popups.add("https://a.example", pending = false, unread = true)
        popups.add("https://a.example", pending = false, unread = true)
        keys += popups.layoutKey
        popups.add("https://a.example", pending = false, unread = true)
        keys += popups.layoutKey // "and 1 more" appears
        // An address arriving, a new row, the "more" line: each re-arms.
        assertEquals(keys.size, keys.toSet().size)
        // A loop past that only changes the count: the guard can arm.
        val full = popups.layoutKey
        repeat(50) { popups.add("https://a.example", pending = false, unread = true) }
        assertEquals(full, popups.layoutKey)
        assertEquals(54, popups.count)
    }

    @Test
    fun `a window refused unprobed says its address wasn't read, not that it was blank`() {
        // #292 R1-M1.
        val popups = BlockedPopups()
        val doc = popups.document
        val refused = popups.add("https://a.example", pending = false, unread = true)!!
        assertEquals(
            "Its address wasn't read (too many pop-ups at once)",
            blockedPopupLabel(popups.entries.single { it.id == refused }, shown = null),
        )
        val failed = popups.add("https://a.example", pending = true)!!
        popups.unread(doc, failed)
        val entry = popups.entries.single { it.id == failed }
        assertFalse(entry.pending)
        assertTrue(entry.unread)
        // A probe that ran and saw no navigation: a genuinely blank window.
        val blank = popups.add("https://a.example", pending = true)!!
        popups.resolve(doc, blank, null)
        assertEquals(
            "A blank window (no address to open)",
            blockedPopupLabel(popups.entries.single { it.id == blank }, shown = null),
        )
        assertEquals("Reading its address…", blockedPopupLabel(BlockedPopup(9, pending = true), shown = null))
    }

    @Test
    fun `a form posted into a blocked window is named with its address, not called blank`() {
        // #292 R2-M1: a scripted `<form target=_blank method=post>`.
        val popups = BlockedPopups()
        val doc = popups.document
        val form = popups.add("https://a.example", pending = true)!!
        popups.resolve(doc, form, "https://pay.example/checkout", posted = true)
        val entry = popups.entries.single { it.id == form }
        assertTrue(entry.posted)
        assertEquals("https://pay.example/checkout", entry.url)
        assertEquals(
            "A form sent to pay.example/checkout (its data can't be sent again from here)",
            blockedPopupLabel(entry, shown = "pay.example/checkout"),
        )
        // A GET navigation stays a plain, openable address.
        val link = popups.add("https://a.example", pending = true)!!
        popups.resolve(doc, link, "https://b.example/")
        assertFalse(popups.entries.single { it.id == link }.posted)
        assertEquals("b.example", blockedPopupLabel(popups.entries.single { it.id == link }, shown = "b.example"))
    }

    @Test
    fun `opening the last entry takes the notice down, and a new document resets always-allow`() {
        val popups = BlockedPopups()
        val first = popups.add("https://a.example", pending = false)
        val second = popups.add("https://a.example", pending = false)
        popups.markAllowed()
        popups.remove(popups.entries.first { it.id == first })
        assertEquals(listOf(second), popups.entries.map { it.id })
        assertTrue(popups.allowed)
        popups.remove(popups.entries.single())
        assertTrue(popups.entries.isEmpty())
        assertFalse(popups.allowed)
        assertEquals(0, popups.count)
        popups.add("https://a.example", pending = false)
        popups.markAllowed()
        popups.startDocument()
        assertFalse(popups.allowed)
    }

    @Test
    fun `opening a listed entry doesn't count it again as one of the unlisted more`() {
        // #292 R3-M1: 5 blocked, 3 listed and "and 2 more"; Open on one row.
        val popups = BlockedPopups()
        repeat(5) { popups.add("https://a.example", pending = false) }
        assertEquals(2, popups.unlisted)
        val key = popups.layoutKey
        popups.remove(popups.entries.first())
        assertEquals(2, popups.entries.size)
        assertEquals(2, popups.unlisted)
        assertEquals(5, popups.count)
        // The row going moves what's under a finger: the guard re-arms.
        assertTrue(key != popups.layoutKey)
        // A new site, or a closed notice, starts the count afresh.
        popups.add("https://b.example", pending = false)
        assertEquals(0, popups.unlisted)
        repeat(4) { popups.add("https://b.example", pending = false) }
        assertEquals(2, popups.unlisted)
        popups.clear()
        assertEquals(0, popups.unlisted)
    }

    @Test
    fun `the popups allow is read from the session tier, and a revoke takes it away`() {
        val s = PermissionSession()
        val o = "https://a.example"
        assertNull(s.decisionFor(o, SitePermission.POPUPS))
        s.record(o, SitePermission.POPUPS, PermissionDecision.ALLOW, remembered = false)
        assertEquals(PermissionDecision.ALLOW, s.decisionFor(o, SitePermission.POPUPS))
        assertEquals(listOf(SitePermission.POPUPS), s.entries().map { it.permission })
        s.revoke(o, SitePermission.POPUPS)
        assertNull(s.decisionFor(o, SitePermission.POPUPS))
    }

    @Test
    fun `only web and dweb addresses are offered to open`() {
        assertTrue(isOpenableInTab("https://a.example/x"))
        assertTrue(isOpenableInTab("bzz://abc/"))
        assertFalse(isOpenableInTab("javascript:alert(1)"))
        assertFalse(isOpenableInTab("data:text/html,hi"))
        assertFalse(isOpenableInTab("intent://x#Intent;end"))
    }

    @Test
    fun `an address is split so its host is never the part that gets cut`() {
        // #292 R4-F1: a padded host keeps its registrable domain on screen.
        val long = "https://accounts.google.com.secure-login-verify." + "x".repeat(11) + ".evil.example/signin?next=" + "a".repeat(400)
        val address = PopupAddress.of(long)
        assertEquals("https://accounts.google.com.secure-login-verify.xxxxxxxxxxx.evil.example", address.site)
        assertTrue(address.rest.startsWith("/signin?next="))
        assertEquals(PopupAddress("pay.example", "/checkout"), PopupAddress.of("pay.example/checkout"))
        assertEquals(PopupAddress("https://b.example:8443", "?q=1#x"), PopupAddress.of("https://b.example:8443?q=1#x"))
        assertEquals(PopupAddress("bzz://abc", ""), PopupAddress.of("bzz://abc"))
        // A site past the cap loses its front: the registrable domain stays.
        val huge = PopupAddress.of("https://" + "a.".repeat(400) + "evil.example/")
        assertEquals(PopupAddress.MAX_SITE + 1, huge.site.length)
        assertTrue(huge.site.startsWith("…") && huge.site.endsWith(".evil.example"))
    }

    @Test
    fun `an address's userinfo is not shown, so it can't pose as the host`() {
        // #292 R5-M1: Chromium drops userinfo from display too.
        assertEquals(
            PopupAddress("https://evil.example", "/x"),
            PopupAddress.of("https://accounts.google.com@evil.example/x"),
        )
        assertEquals(PopupAddress("https://evil.example:8443", "?q"), PopupAddress.of("https://u:p@evil.example:8443?q"))
        // The last '@' before the path ends the userinfo; one in the path is path.
        assertEquals(PopupAddress("https://evil.example", "/@me"), PopupAddress.of("https://a@b@evil.example/@me"))
        assertEquals(PopupAddress("https://good.example", "/u@x"), PopupAddress.of("https://good.example/u@x"))
        // Padding in userinfo goes with it.
        assertEquals("https://evil.example", PopupAddress.of("https://" + "u".repeat(1000) + "@evil.example/").site)
        // No "scheme://": nothing is taken for userinfo.
        assertEquals(PopupAddress("mailto:a@b.example", ""), PopupAddress.of("mailto:a@b.example"))
    }

    @Test
    fun `a row counts as in view only while wholly inside the scrolled area`() {
        // #292 R5-F1: a row cut off at the card's edge can't be opened.
        assertTrue(FullyInView.wholly(300f, androidx.compose.ui.geometry.Rect(0f, 0f, 100f, 300f)))
        assertTrue(FullyInView.wholly(300f, androidx.compose.ui.geometry.Rect(0f, 120f, 100f, 200f)))
        assertFalse(FullyInView.wholly(300f, androidx.compose.ui.geometry.Rect(0f, 250f, 100f, 340f)))
        assertFalse(FullyInView.wholly(300f, androidx.compose.ui.geometry.Rect(0f, -20f, 100f, 60f)))
    }
}
