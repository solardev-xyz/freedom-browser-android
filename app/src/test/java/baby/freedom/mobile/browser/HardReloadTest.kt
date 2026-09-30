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
        val bypass = CacheBypass(readCacheMode = { mode }, writeCacheMode = { mode = it; writes += it })
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
        s.bypass.pageFinished()
        assertEquals(WebSettings.LOAD_DEFAULT, s.mode)
        assertFalse(s.bypass.active)
    }

    @Test
    fun `a finish before the load commits is the replaced page stopping, not the end`() {
        val s = Settings()
        s.bypass.loadStarting(bypass = true)
        // The page on screen was still loading: its stop finishes late.
        s.bypass.pageFinished()
        assertEquals(WebSettings.LOAD_NO_CACHE, s.mode)
        s.bypass.pageStarted()
        s.bypass.pageFinished()
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
        s.bypass.pageFinished()
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
        s.bypass.pageFinished()
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

        // The next navigation is an ordinary one.
        tab.beginLoad()
        tab.handLoadToWebView()
        assertFalse(tab.takeFreshFetch(tab.webViewGeneration, "http://127.0.0.1:1633/bzz/abc/new.js"))
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
    }
}
