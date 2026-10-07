package baby.freedom.mobile.browser

import androidx.compose.ui.unit.dp
import baby.freedom.mobile.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Page info (#442): the badge, the connection, and the site-data helpers. */
class PageInfoTest {

    private val swarm = ProtocolBadge(R.drawable.ic_swarm, R.string.browser_badge_via_swarm)
    private val ipfs = ProtocolBadge(R.drawable.ic_ipfs, R.string.browser_badge_via_ipfs)

    @Test
    fun `the connection follows the scheme, a dweb page its network`() {
        assertEquals(PageConnection.Secure, pageConnectionFor("https://example.org/a", false, null))
        assertEquals(PageConnection.Secure, pageConnectionFor("HTTPS://example.org", false, null))
        assertEquals(PageConnection.NotSecure, pageConnectionFor("http://example.org/", false, null))
        assertEquals(PageConnection.Swarm, pageConnectionFor("bzz://abc", false, swarm))
        assertEquals(PageConnection.Ipfs, pageConnectionFor("ipfs://bafy", false, ipfs))
        assertNull(pageConnectionFor("about:blank", false, null))
        assertNull(pageConnectionFor("", false, null))
    }

    @Test
    fun `an error page is never said to be secure, whatever address it stands for`() {
        assertEquals(PageConnection.ErrorPage, pageConnectionFor("https://example.org/", true, null))
        assertEquals(PageConnection.ErrorPage, pageConnectionFor("bzz://abc", true, swarm))
        assertTrue(PageConnection.entries.filter { it.hasCertificate } == listOf(PageConnection.Secure))
    }

    @Test
    fun `the badge is the protocol mark on dweb pages, a lock or open lock on the web, none at home`() {
        assertEquals(AddressBadge.Protocol(swarm), addressBadgeFor("bzz://abc", false, false, swarm))
        // Kept on a dweb error page: it says which network failed.
        assertEquals(AddressBadge.Protocol(ipfs), addressBadgeFor("ipfs://bafy", false, true, ipfs))
        assertEquals(
            AddressBadge.Connection(PageConnection.Secure),
            addressBadgeFor("https://example.org/", false, false, null),
        )
        assertEquals(
            AddressBadge.Connection(PageConnection.NotSecure),
            addressBadgeFor("http://example.org/", false, false, null),
        )
        assertEquals(
            AddressBadge.Connection(PageConnection.ErrorPage),
            addressBadgeFor("https://example.org/", false, true, null),
        )
        assertNull(addressBadgeFor("", true, false, null))
        assertNull(addressBadgeFor("data:text/html,hi", false, false, null))
    }

    @Test
    fun `cookies are counted per entry, nameless ones too`() {
        assertEquals(listOf("a=1", "b=2", "c"), cookieEntries("a=1; b=2; c"))
        assertEquals(emptyList<String>(), cookieEntries(null))
        assertEquals(emptyList<String>(), cookieEntries(" ; "))
    }

    @Test
    fun `the site data line says cookies, storage only when there is some, and counting until known`() {
        val fmt: (Long) -> String = { "$it B" }
        assertEquals("Counting…", siteDataLine(null, fmt))
        assertEquals("No cookies", siteDataLine(SiteDataCount(0, null), fmt))
        assertEquals("No cookies", siteDataLine(SiteDataCount(0, 0), fmt))
        assertEquals("1 cookie", siteDataLine(SiteDataCount(1, null), fmt))
        assertEquals("3 cookies · 512 B stored", siteDataLine(SiteDataCount(3, 512), fmt))
        assertEquals("No cookies · 9 B stored", siteDataLine(SiteDataCount(0, 9), fmt))
    }

    @Test
    fun `cookie domains run from the host down to its registrable domain`() {
        assertEquals(
            listOf("a.b.example.co.uk", "b.example.co.uk", "example.co.uk"),
            cookieDomains("a.b.example.co.uk", "example.co.uk"),
        )
        assertEquals(listOf("example.org"), cookieDomains("example.org", "example.org"))
        // An IP literal, or a host that is itself a public suffix: host-only cookies only.
        assertEquals(emptyList<String>(), cookieDomains("192.168.1.1", null))
        assertEquals(emptyList<String>(), cookieDomains("github.io", null))
        // A registrable domain the host isn't under is never trusted.
        assertEquals(emptyList<String>(), cookieDomains("example.org", "other.org"))
    }

    @Test
    fun `expiries cover every path, scope and name, Secure only on https`() {
        val https = siteCookieExpiries("https://a.example.org", "/x/y", listOf("sid=1", "__Host-t=2", "bare"), listOf("a.example.org", "example.org"))
        // Paths `/`, `/x`, `/x/`, `/x/y` × 3 scopes × 3 rewrites × 2 (partitioned).
        assertEquals(4 * 3 * 3 * 2, https.size)
        assertTrue("sid=; Path=/; Max-Age=0; Secure" in https)
        assertTrue("sid=; Domain=example.org; Path=/x/; Max-Age=0; Secure; Partitioned" in https)
        assertTrue("__Host-t=; Path=/; Max-Age=0; Secure" in https)
        // A nameless cookie is reached through one `=x` rewrite.
        assertTrue("=x; Path=/x/y; Max-Age=0; Secure" in https)
        assertTrue(https.none { it.startsWith("bare") })

        val http = siteCookieExpiries("http://example.org", "/", listOf("sid=1"), emptyList())
        assertEquals(listOf("sid=; Path=/; Max-Age=0"), http)

        assertEquals(emptyList<String>(), siteCookieExpiries("https://example.org", "/", emptyList(), emptyList()))
    }

    @Test
    fun `a deep page path is capped`() {
        val deep = "/" + (1..100).joinToString("/") { "d$it" }
        val e = siteCookieExpiries("https://example.org", deep, listOf("a=1"), emptyList())
        assertEquals(SITE_COOKIE_MAX_PATHS * 2, e.size)
    }

    @Test
    fun `certificate rows list only what the certificate has`() {
        val rows = certificateRows(CertFacts(emptySet(), 1L, 2L, "example.org", "R11")) { "day$it" }
        assertEquals(
            listOf(
                R.string.page_info_cert_issued_to to "example.org",
                R.string.page_info_cert_issued_by to "R11",
                R.string.page_info_cert_valid_from to "day1",
                R.string.page_info_cert_valid_until to "day2",
            ),
            rows,
        )
        assertEquals(emptyList<Pair<Int, String>>(), certificateRows(CertFacts(emptySet(), null, null, " ", null)) { "" })
    }

    @Test
    fun `the badge's tap target sits on the mark unless it would reach the menu's slot`() {
        // Field from −150 dp; the menu's slot ends 40 dp in, at −110 dp.
        val edge = (-150).dp
        // A short name: the badge is far from the slot, the target centred on it.
        assertEquals((-40).dp, addressBadgeTouchCenter(badgeCenter = (-40).dp, fieldLeadingEdge = edge))
        // A name filling the field: the badge's own edge at the slot's end
        // (−102 dp centre); the target is moved 16 dp labelwards, its start on the slot's end.
        val moved = addressBadgeTouchCenter(badgeCenter = (-102).dp, fieldLeadingEdge = edge)
        assertEquals((-86).dp, moved)
        assertEquals((-110).dp, moved - AddressBadgeTouchSize / 2f)
        assertEquals(48.dp, AddressBadgeTouchSize)
    }

    @Test
    fun `the cleanup page is served once, to the marked tab, on the marked origin only`() {
        val f = SiteData::class.java.getDeclaredField("cleanups").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val marks = f.get(SiteData) as MutableMap<Long, String>
        marks[7L] = "https://example.org"
        assertEquals(false, SiteData.takeCleanup(8L, "https://example.org/"))
        assertEquals(true, SiteData.takeCleanup(7L, "https://example.org/a?b"))
        // Taken: the next load is served normally.
        assertEquals(false, SiteData.takeCleanup(7L, "https://example.org/"))
        // A tab that went elsewhere loses the mark rather than keep it for a later visit.
        marks[7L] = "https://example.org"
        assertEquals(false, SiteData.takeCleanup(7L, "https://other.example/"))
        assertEquals(false, SiteData.takeCleanup(7L, "https://example.org/"))
    }
}
