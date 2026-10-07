package baby.freedom.mobile.browser

import androidx.compose.ui.unit.dp
import baby.freedom.mobile.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
        SiteData.markCleanup(7L, "https://example.org")
        assertEquals(false, SiteData.takeCleanup(8L, "https://example.org/", "GET"))
        assertEquals(true, SiteData.takeCleanup(7L, "https://example.org/a?b", "GET"))
        // Taken: the next load is served normally.
        assertEquals(false, SiteData.takeCleanup(7L, "https://example.org/", "GET"))
        // A tab that went elsewhere loses the mark rather than keep it for a later visit.
        SiteData.markCleanup(7L, "https://example.org")
        assertEquals(false, SiteData.takeCleanup(7L, "https://other.example/", "GET"))
        assertEquals(false, SiteData.takeCleanup(7L, "https://example.org/", "GET"))
        // A form POST on the origin (a login) is never answered with the cleanup page.
        SiteData.markCleanup(7L, "https://example.org")
        assertEquals(false, SiteData.takeCleanup(7L, "https://example.org/login", "POST"))
        assertEquals(false, SiteData.takeCleanup(7L, "https://example.org/", "GET"))
    }

    @Test
    fun `a load a service worker answered drops the cleanup mark at its commit`() {
        // The reload never reached the interceptor; its commit ends the mark,
        // so a later same-origin load isn't served the cleanup page.
        val mark = SiteData.markCleanup(9L, "https://example.org")
        assertTrue(SiteData.cleanupPending(9L, mark))
        SiteData.committed(9L, "https://example.org/")
        assertFalse(SiteData.cleanupPending(9L, mark))
        assertEquals("https://example.org", SiteData.committedOrigin(9L))
        assertEquals(false, SiteData.takeCleanup(9L, "https://example.org/", "GET"))
        // Another tab's commit leaves this one's mark alone.
        SiteData.markCleanup(9L, "https://example.org")
        SiteData.committed(10L, "https://other.example/")
        assertEquals(true, SiteData.takeCleanup(9L, "https://example.org/", "GET"))
        SiteData.tabClosed(9L)
        assertNull(SiteData.committedOrigin(9L))
    }

    @Test
    fun `dropping a mark leaves a later Delete's mark alone`() {
        val first = SiteData.markCleanup(11L, "https://example.org")
        val second = SiteData.markCleanup(11L, "https://example.org")
        assertFalse(SiteData.cleanupPending(11L, first))
        SiteData.dropCleanup(11L, first)
        assertTrue(SiteData.cleanupPending(11L, second))
        SiteData.dropCleanup(11L, second)
        assertEquals(false, SiteData.takeCleanup(11L, "https://example.org/", "GET"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a reload that never comes leaves no mark behind`() = runTest {
        // The fragment case (R2-F1): neither the page's reload nor the
        // app's starts a load. The mark must not outlive the Delete, or a
        // later same-origin visit is wiped.
        var reloads = 0
        SiteData.cleanAndReload(
            tabId = 12L, origin = "http://localhost:8721",
            clean = { true }, cleaned = { InPageCleaned.RELOADING_ITSELF }, onOrigin = { true }, reload = { reloads++ },
        )
        assertEquals(1, reloads)
        assertEquals(false, SiteData.takeCleanup(12L, "http://localhost:8721/index.html?later", "GET"))
        // A page that couldn't be asked is reloaded from here at once, and again the mark goes.
        SiteData.cleanAndReload(
            tabId = 12L, origin = "http://localhost:8721",
            clean = { false }, cleaned = { error("not asked") }, onOrigin = { true }, reload = { reloads++ },
        )
        assertEquals(2, reloads)
        assertEquals(false, SiteData.takeCleanup(12L, "http://localhost:8721/", "GET"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `the fallback reload waits for the page's clearing to end`() = runTest {
        // A slow clearing (R2-F3): the app doesn't reload over it while
        // the worker is still registered; it waits for the page's done
        // mark, then for the page's own reload.
        var done = false
        var reloadedAt = -1L
        val job = launch {
            SiteData.cleanAndReload(
                tabId = 13L, origin = "https://example.org",
                clean = { true }, cleaned = { if (done) InPageCleaned.RELOADING_ITSELF else null }, onOrigin = { true }, reload = { reloadedAt = currentTime },
            )
        }
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(-1L, reloadedAt)
        done = true
        // The page's own reload takes the mark: nothing reloads from here.
        advanceTimeBy(1_000)
        assertEquals(true, SiteData.takeCleanup(13L, "https://example.org/", "GET"))
        job.join()
        assertEquals(-1L, reloadedAt)
        // A page that never ends its clearing is reloaded once the cap has passed.
        val capped = launch {
            SiteData.cleanAndReload(
                tabId = 14L, origin = "https://example.org",
                clean = { true }, cleaned = { null }, onOrigin = { true }, reload = { reloadedAt = currentTime },
            )
        }
        val start = currentTime
        capped.join()
        assertTrue(reloadedAt - start >= SiteData.IN_PAGE_WIPE_MAX_MS)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a tab that went to another site isn't reloaded for the Delete`() = runTest {
        // R3-F1: the page couldn't be asked (its committed origin differs),
        // and the fallback reload must not hit the unrelated site.
        var reloads = 0
        SiteData.cleanAndReload(
            tabId = 15L, origin = "https://example.org",
            clean = { false }, cleaned = { error("not asked") }, onOrigin = { false }, reload = { reloads++ },
        )
        assertEquals(0, reloads)
        assertEquals(false, SiteData.takeCleanup(15L, "https://example.org/", "GET"))
        // Nor one that leaves while the page is clearing: its commit ends the attempt.
        SiteData.cleanAndReload(
            tabId = 15L, origin = "https://example.org",
            clean = { true },
            cleaned = { SiteData.committed(15L, "https://other.example/"); null },
            onOrigin = { SiteData.committedOrigin(15L) == "https://example.org" },
            reload = { reloads++ },
        )
        assertEquals(0, reloads)
        SiteData.tabClosed(15L)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a commit that cleaned nothing runs the clearing again on the new document`() = runTest {
        // R3-F2: a client redirect a service worker answered commits while
        // the page clears. The clearing died with the old document; the
        // new one, still on the site, is asked again.
        SiteData.committed(16L, "https://example.org/")
        var asks = 0
        var reloads = 0
        SiteData.cleanAndReload(
            tabId = 16L, origin = "https://example.org",
            clean = { asks++; true },
            cleaned = {
                if (asks == 1) {
                    SiteData.committed(16L, "https://example.org/next")
                    null
                } else {
                    // The second time, the page's own reload takes the mark.
                    SiteData.takeCleanup(16L, "https://example.org/next", "GET")
                    InPageCleaned.RELOADING_ITSELF
                }
            },
            onOrigin = { SiteData.committedOrigin(16L) == "https://example.org" },
            reload = { reloads++ },
        )
        assertEquals(2, asks)
        assertEquals(0, reloads)
        // A commit that keeps cutting it short is tried only so often.
        asks = 0
        SiteData.cleanAndReload(
            tabId = 16L, origin = "https://example.org",
            clean = { asks++; true },
            cleaned = { SiteData.committed(16L, "https://example.org/again"); null },
            onOrigin = { SiteData.committedOrigin(16L) == "https://example.org" },
            reload = { reloads++ },
        )
        assertEquals(SiteData.CLEANUP_ATTEMPTS, asks)
        assertEquals(0, reloads)
        // A load that took the mark for another origin isn't retried.
        asks = 0
        SiteData.cleanAndReload(
            tabId = 16L, origin = "https://example.org",
            clean = { asks++; true },
            cleaned = { SiteData.takeCleanup(16L, "https://example.org/login", "POST"); null },
            onOrigin = { true },
            reload = { reloads++ },
        )
        assertEquals(1, asks)
        assertEquals(0, reloads)
        SiteData.tabClosed(16L)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a document that won't clear is reloaded at once, not waited on`() = runTest {
        // R3-F4: an opaque (sandboxed) document answers that it didn't
        // start; the reload comes without the 15 s + 3 s wait.
        var reloadedAt = -1L
        val start = currentTime
        SiteData.cleanAndReload(
            tabId = 17L, origin = "https://example.org",
            clean = { false }, cleaned = { error("not asked") }, onOrigin = { true },
            reload = { reloadedAt = currentTime },
        )
        assertEquals(start, reloadedAt)
    }

    @Test
    fun `the marked reload is cleaned, any other outcome is told apart`() {
        val taken = SiteData.markCleanup(18L, "https://example.org")
        SiteData.takeCleanup(18L, "https://example.org/", "GET")
        assertEquals(SiteData.Outcome.CLEANED, taken.outcome)
        val post = SiteData.markCleanup(18L, "https://example.org")
        SiteData.takeCleanup(18L, "https://example.org/", "POST")
        assertEquals(SiteData.Outcome.ELSEWHERE, post.outcome)
        val committed = SiteData.markCleanup(18L, "https://example.org")
        SiteData.committed(18L, "https://example.org/")
        assertEquals(SiteData.Outcome.COMMITTED, committed.outcome)
        val dropped = SiteData.markCleanup(18L, "https://example.org")
        SiteData.dropCleanup(18L, dropped)
        assertEquals(SiteData.Outcome.DROPPED, dropped.outcome)
        val replaced = SiteData.markCleanup(18L, "https://example.org")
        SiteData.markCleanup(18L, "https://example.org")
        assertEquals(SiteData.Outcome.PENDING, replaced.outcome)
        SiteData.tabClosed(18L)
    }

    @Test
    fun `the in-page cleanup acts only on its own origin, marks it done, then reloads`() {
        val js = siteDataInPageJs("http://localhost:8720", "_k1")
        assertTrue("if (location.origin !== \"http://localhost:8720\") return false;" in js)
        // It answers whether it started, so an opaque document isn't waited on (R3-F4).
        assertTrue(js.trimEnd().endsWith("return true;\n})()"))
        assertTrue("r.unregister()" in js)
        assertTrue(js.indexOf("r.unregister()") < js.indexOf("window[\"_k1\"] = self"))
        assertTrue(js.indexOf("window[\"_k1\"] = self") < js.indexOf("if (self) location.replace(location.href);"))
        assertTrue("window[\"_k1\"]" in siteDataInPageDoneJs("_k1"))
        assertEquals(InPageCleaned.RELOADING_ITSELF, inPageCleanedOf("\"reloading\""))
        assertEquals(InPageCleaned.APP_RELOADS, inPageCleanedOf("\"app\""))
        for (r in listOf("null", "true", null, "\"other\"")) assertEquals(r, null, inPageCleanedOf(r))
        // An origin can't break out of the string it's compared with.
        assertTrue("\"a\\\"b\"" in siteDataInPageJs("a\"b", "_k"))
    }

    @Test
    fun `an address with a fragment is reloaded, not navigated to in place`() {
        // `location.replace(href)` with a #fragment is a same-document
        // navigation that loads nothing (R2-F1). The cleanup page, which
        // answered its request in place of the site, reloads itself.
        assertTrue("if (location.href.includes('#')) location.reload(); else location.replace(location.href);" in SITE_DATA_CLEANUP_HTML)
        // A real page doesn't: a script `location.reload()` resends a POST
        // page's form with no `onFormResubmission` prompt (R4-F1). It
        // leaves the reload to the app's own.
        val js = siteDataInPageJs("https://example.org", "_k")
        assertFalse(js, "location.reload" in js)
        assertTrue(js, "const self = !location.href.includes('#');" in js)
        assertTrue(js, "self ? 'reloading' : 'app'" in js)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a page that leaves the reload to the app is reloaded at once`() = runTest {
        // R4-F1: no 3 s wait for a reload the page said it won't do.
        var reloadedAt = -1L
        val start = currentTime
        SiteData.cleanAndReload(
            tabId = 19L, origin = "https://example.org",
            clean = { true }, cleaned = { InPageCleaned.APP_RELOADS }, onOrigin = { true },
            reload = { reloadedAt = currentTime },
        )
        assertEquals(start, reloadedAt)
        assertEquals(false, SiteData.takeCleanup(19L, "https://example.org/", "GET"))
        SiteData.tabClosed(19L)
    }

    @Test
    fun `loopback http is on this device, not Not secure`() {
        for (u in listOf(
            "http://localhost:8720/", "http://127.0.0.1/", "http://127.9.8.7:80/x",
            "http://[::1]:8720/", "http://app.localhost/", "HTTP://LOCALHOST./",
        )) assertEquals(u, PageConnection.Local, pageConnectionFor(u, errorPage = false, protocol = null))
        for (u in listOf(
            "http://127.tracker.example/", "http://127.0.0.1.evil.example/", "http://10.0.2.2:8720/",
            "http://example.org/", "http://localhost.example/",
        )) assertEquals(u, PageConnection.NotSecure, pageConnectionFor(u, errorPage = false, protocol = null))
        assertEquals(PageConnection.Secure, pageConnectionFor("https://localhost/", errorPage = false, protocol = null))
        assertEquals(PageConnection.ErrorPage, pageConnectionFor("http://localhost/", errorPage = true, protocol = null))
    }

    @Test
    fun `the wipe document clears what the cleanup page does and says when it is done`() {
        val html = siteDataWipeHtml("wiped-1")
        for (step in listOf("localStorage.clear()", "sessionStorage.clear()", "indexedDB.deleteDatabase",
            "caches.delete", "r.unregister()")) {
            assertTrue(step, step in html)
            assertTrue(step, step in SITE_DATA_CLEANUP_HTML)
        }
        assertTrue("document.title = 'wiped-1'" in html)
        assertFalse("location.replace" in html)
        assertTrue("location.replace(location.href)" in SITE_DATA_CLEANUP_HTML)
    }
}
