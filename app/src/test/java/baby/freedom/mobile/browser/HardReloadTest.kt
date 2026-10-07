package baby.freedom.mobile.browser

import android.webkit.WebSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hard reload (#262): which load bypasses the HTTP cache, for how long,
 * and which of the interceptor's gateway fetches skip its own caches.
 */
class HardReloadTest {

    private class Settings(var mode: Int = WebSettings.LOAD_DEFAULT) {
        val writes = mutableListOf<Int>()

        /** The UI thread's queue behind the callbacks delivered so far. */
        val posted = ArrayDeque<Runnable>()
        val bypass = CacheBypass(
            readCacheMode = { mode },
            writeCacheMode = { mode = it; writes += it },
            post = { posted.addLast(it) },
        )

        fun runPosted() {
            while (posted.isNotEmpty()) posted.removeFirst().run()
        }
    }

    private companion object {
        const val PAGE = "http://host/page#sec"
    }

    @Test
    fun `a fragment navigation of the page on screen stays in its document`() {
        // loadUrl of these would only scroll (R3-F1): the Hard reload reloads.
        assertTrue(CacheBypass.staysInDocument("http://host/page#sec", "http://host/page#sec"))
        assertTrue(CacheBypass.staysInDocument("http://host/page", "http://host/page#sec"))
        assertTrue(CacheBypass.staysInDocument("http://host/page#a", "http://host/page#b"))
        assertTrue(CacheBypass.staysInDocument("http://host/page?q=1", "http://host/page?q=1#"))
        // These load a document.
        assertFalse(CacheBypass.staysInDocument("http://host/page", "http://host/page"))
        assertFalse(CacheBypass.staysInDocument("http://host/page#sec", "http://host/page"))
        assertFalse(CacheBypass.staysInDocument("http://host/page#sec", "http://host/other#sec"))
        assertFalse(CacheBypass.staysInDocument("http://host/page?q=1", "http://host/page?q=2#x"))
        assertFalse(CacheBypass.staysInDocument(null, "http://host/page#sec"))
    }

    @Test
    fun `a fragment navigation while the page loads doesn't end the bypass`() {
        // R3-M1: the page sets location.hash before its load event.
        val s = Settings()
        s.bypass.loadStarting(bypass = true)
        s.bypass.pageStarted()
        s.bypass.historyUpdated("http://host/page") // the commit's own
        s.runPosted()
        // The fragment navigation: its history update, and its finish
        // posted right behind it.
        s.bypass.historyUpdated(PAGE)
        s.bypass.pageFinished(PAGE)
        s.runPosted()
        assertEquals(WebSettings.LOAD_NO_CACHE, s.mode)
        // The document's own finish, at the new address.
        s.bypass.pageFinished(PAGE)
        assertEquals(WebSettings.LOAD_DEFAULT, s.mode)
    }

    @Test
    fun `a commit with no history update of its own doesn't take a fragment navigation's for it`() {
        // R4-M1: onPageStarted with no doUpdateVisitedHistory behind it;
        // the loading page then sets location.hash.
        val s = Settings()
        s.bypass.loadStarting(bypass = true)
        s.bypass.pageStarted()
        s.runPosted()
        s.bypass.historyUpdated(PAGE)
        s.bypass.pageFinished(PAGE)
        s.runPosted()
        assertEquals(WebSettings.LOAD_NO_CACHE, s.mode)
        s.bypass.pageFinished(PAGE)
        assertEquals(WebSettings.LOAD_DEFAULT, s.mode)
    }

    @Test
    fun `a refused hard reload moves on to its GET, past the cache`() {
        // R4-F1: the reload of a POST result page at a #fragment address.
        val s = Settings()
        var gets = 0
        s.bypass.loadStarting(bypass = true)
        s.bypass.reloading(Runnable { gets++; s.bypass.loadStarting(bypass = true) })
        s.bypass.reloadRefused()
        s.runPosted()
        assertEquals(1, gets)
        assertEquals(WebSettings.LOAD_NO_CACHE, s.mode)
        // Only once.
        s.bypass.reloadRefused()
        s.runPosted()
        assertEquals(1, gets)
        s.bypass.pageStarted()
        s.bypass.historyUpdated("http://host/page")
        s.bypass.pageFinished("http://host/page")
        assertEquals(WebSettings.LOAD_DEFAULT, s.mode)
    }

    @Test
    fun `a refused reload that isn't the hard reload's ends the bypass`() {
        val s = Settings()
        var gets = 0
        // Another load after the hard reload's: its refusal isn't ours.
        s.bypass.loadStarting(bypass = true)
        s.bypass.reloading(Runnable { gets++ })
        s.bypass.loadStarting(bypass = true)
        s.bypass.reloadRefused()
        s.runPosted()
        assertEquals(0, gets)
        assertEquals(WebSettings.LOAD_DEFAULT, s.mode)

        // The hard reload's reload committed: a later refusal (the page's
        // own location.reload()) isn't its either.
        s.bypass.loadStarting(bypass = true)
        s.bypass.reloading(Runnable { gets++ })
        s.bypass.pageStarted()
        s.bypass.reloadRefused()
        s.runPosted()
        assertEquals(0, gets)
        assertEquals(WebSettings.LOAD_DEFAULT, s.mode)

        // No bypass on: nothing to do.
        s.bypass.reloading(Runnable { gets++ })
        s.bypass.reloadRefused()
        s.runPosted()
        assertEquals(0, gets)
    }

    @Test
    fun `a pushState while the page loads doesn't swallow its finish`() {
        // No finish follows a pushState: the window closes behind it.
        val s = Settings()
        s.bypass.loadStarting(bypass = true)
        s.bypass.pageStarted()
        s.bypass.historyUpdated("http://host/page")
        s.runPosted()
        s.bypass.historyUpdated("http://host/route")
        s.runPosted()
        s.bypass.pageFinished("http://host/route")
        assertEquals(WebSettings.LOAD_DEFAULT, s.mode)
    }

    @Test
    fun `the reload of a fragment URL ends its bypass at its own finish`() {
        // R3-F1's path: a reload commits (onPageStarted, then its history
        // update with no fragment navigation), and finishes.
        val s = Settings()
        s.bypass.loadStarting(bypass = true)
        s.bypass.pageStarted()
        s.bypass.historyUpdated(PAGE)
        s.bypass.pageFinished(PAGE)
        assertEquals(WebSettings.LOAD_DEFAULT, s.mode)
    }

    @Test
    fun `the hard reload's load bypasses the cache until its own page has finished`() {
        val s = Settings()
        s.bypass.loadStarting(bypass = true)
        assertEquals(WebSettings.LOAD_NO_CACHE, s.mode)
        assertTrue(s.bypass.active)
        s.bypass.pageStarted()
        // Its subresources load before the finish: still bypassing.
        assertEquals(WebSettings.LOAD_NO_CACHE, s.mode)
        s.bypass.pageFinished(PAGE)
        assertEquals(WebSettings.LOAD_DEFAULT, s.mode)
        assertFalse(s.bypass.active)
    }

    @Test
    fun `a finish before the load commits is the replaced page stopping, not the end`() {
        val s = Settings()
        s.bypass.loadStarting(bypass = true)
        // The page on screen was still loading: its stop finishes late.
        s.bypass.pageFinished(PAGE)
        assertEquals(WebSettings.LOAD_NO_CACHE, s.mode)
        s.bypass.pageStarted()
        s.bypass.pageFinished(PAGE)
        assertEquals(WebSettings.LOAD_DEFAULT, s.mode)
    }

    @Test
    fun `any other load, or Stop, ends the bypass at once`() {
        val s = Settings()
        s.bypass.loadStarting(bypass = true)
        s.bypass.loadStarting(bypass = false)
        assertEquals(WebSettings.LOAD_DEFAULT, s.mode)

        s.bypass.loadStarting(bypass = true)
        s.bypass.stopped()
        assertEquals(WebSettings.LOAD_DEFAULT, s.mode)
    }

    @Test
    fun `an ordinary load leaves the cache mode alone`() {
        val s = Settings()
        s.bypass.loadStarting(bypass = false)
        s.bypass.pageStarted()
        s.bypass.pageFinished(PAGE)
        s.bypass.stopped()
        assertTrue(s.writes.isEmpty())
    }

    @Test
    fun `the mode put back is the one the WebView had`() {
        val s = Settings(mode = WebSettings.LOAD_CACHE_ELSE_NETWORK)
        s.bypass.loadStarting(bypass = true)
        // A second Hard reload before the first finished keeps the original.
        s.bypass.loadStarting(bypass = true)
        s.bypass.pageStarted()
        s.bypass.pageFinished(PAGE)
        assertEquals(WebSettings.LOAD_CACHE_ELSE_NETWORK, s.mode)
    }

    @Test
    fun `a user-agent redirect correction of the hard reload carries its bypass on`() {
        // PageWebView.redirectCrossesUserAgent's sequence (R2-M2).
        val s = Settings(mode = WebSettings.LOAD_CACHE_ELSE_NETWORK)
        s.bypass.loadStarting(bypass = true)
        // The first hop answers with a redirect to the other user agent's
        // site: it is cancelled (a finish before any commit) ...
        val carried = s.bypass.active
        s.bypass.pageFinished(PAGE)
        // ... and the corrected load goes out, still past the cache.
        s.bypass.loadStarting(bypass = carried && s.bypass.active)
        assertEquals(WebSettings.LOAD_NO_CACHE, s.mode)
        s.bypass.pageStarted()
        assertEquals(WebSettings.LOAD_NO_CACHE, s.mode)
        s.bypass.pageFinished(PAGE)
        assertEquals(WebSettings.LOAD_CACHE_ELSE_NETWORK, s.mode)

        // Stop between the redirect and the corrected load ends it for good.
        s.bypass.loadStarting(bypass = true)
        val carriedAgain = s.bypass.active
        s.bypass.stopped()
        s.bypass.loadStarting(bypass = carriedAgain && s.bypass.active)
        assertEquals(WebSettings.LOAD_CACHE_ELSE_NETWORK, s.mode)
    }

    @Test
    fun `only the load a hard reload scheduled bypasses the cache`() {
        val tab = BrowserState(id = 1L)
        tab.loadUrl("https://example.com/", bypassCache = true)
        assertTrue(tab.takeBypassCacheLoad("https://example.com/"))
        // Taken: the next load of the same URL is an ordinary one.
        assertFalse(tab.takeBypassCacheLoad("https://example.com/"))

        tab.loadUrl("https://example.com/", bypassCache = true)
        // Superseded by an ordinary load before the WebView took it.
        tab.loadUrl("https://example.org/")
        assertFalse(tab.takeBypassCacheLoad("https://example.org/"))
        assertFalse(tab.takeBypassCacheLoad("https://example.com/"))
    }

    @Test
    fun `a hard-reloaded document fetches each gateway URL afresh once`() {
        val tab = BrowserState(id = 1L)
        val target = "http://127.0.0.1:1633/bzz/abc/video.mp4"
        // No hard reload yet: every fetch may use the caches.
        assertFalse(tab.takeFreshFetch(tab.webViewGeneration, target))

        tab.beginLoad()
        tab.handLoadToWebView()
        tab.bypassCacheForHandedLoad()
        val generation = tab.webViewGeneration
        assertTrue(tab.takeFreshFetch(generation, target))
        // Its later Range requests use what that fetch buffered.
        assertFalse(tab.takeFreshFetch(generation, target))
        assertTrue(tab.takeFreshFetch(generation, "http://127.0.0.1:1633/bzz/abc/app.js"))
        // A request of the document before it keeps its caches.
        assertFalse(tab.takeFreshFetch(generation - 1, "http://127.0.0.1:1633/bzz/abc/other.css"))
        // Streamed media (nothing fresh buffered) stays fresh for the
        // document's every later request, and only its (R4-M2).
        assertTrue(tab.fetchedFresh(generation, target))
        assertFalse(tab.fetchedFresh(generation, "http://127.0.0.1:1633/bzz/abc/unfetched.mp4"))
        assertFalse(tab.fetchedFresh(generation - 1, target))

        // The next navigation is an ordinary one.
        tab.beginLoad()
        tab.handLoadToWebView()
        assertFalse(tab.takeFreshFetch(tab.webViewGeneration, "http://127.0.0.1:1633/bzz/abc/new.js"))
        assertFalse(tab.fetchedFresh(tab.webViewGeneration, target))
    }

    @Test
    fun `another tab's fetches keep their caches`() {
        val reloaded = BrowserState(id = 1L)
        val other = BrowserState(id = 2L)
        reloaded.beginLoad()
        reloaded.handLoadToWebView()
        reloaded.bypassCacheForHandedLoad()
        other.beginLoad()
        other.handLoadToWebView()
        val target = "http://127.0.0.1:1633/bzz/abc/video.mp4"
        assertFalse(other.takeFreshFetch(other.webViewGeneration, target))
        assertTrue(reloaded.takeFreshFetch(reloaded.webViewGeneration, target))
        assertFalse(other.fetchedFresh(other.webViewGeneration, target))
    }
}
