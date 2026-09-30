package baby.freedom.mobile.browser

import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Hard reload (#262) on the device's own WebView and interceptor: the
 * reload's load and its subresources reach the server even where the
 * HTTP cache would have answered, the cache is used again afterwards,
 * and a dweb document's media is fetched from the gateway again instead
 * of from the interceptor's Range buffer.
 */
@RunWith(AndroidJUnit4::class)
class HardReloadDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    /** A site that deploys a new version of its script after the first load. */
    private class Site {
        val hits = ConcurrentHashMap<String, AtomicInteger>()

        @Volatile
        var version = 1
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path ?: "/"
                    hits.getOrPut(path) { AtomicInteger() }.incrementAndGet()
                    // Both cacheable for an hour: an ordinary reload
                    // keeps the script from the cache.
                    return when (path) {
                        "/page" -> MockResponse()
                            .setHeader("Content-Type", "text/html")
                            .setHeader("Cache-Control", "max-age=3600")
                            .setBody("<!doctype html><title>page</title><script src=\"/app.js\"></script>")
                        "/app.js" -> MockResponse()
                            .setHeader("Content-Type", "text/javascript")
                            .setHeader("Cache-Control", "max-age=3600")
                            .setBody("document.title = 'v$version';")
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            start(InetAddress.getByName("127.0.0.1"), 0)
        }

        fun hitsOf(path: String) = hits[path]?.get() ?: 0
    }

    @Test
    fun a_hard_reload_reaches_the_network_where_a_reload_is_served_from_the_cache() {
        val site = Site()
        val page = "http://127.0.0.1:${site.server.port}/page"
        lateinit var view: PageWebView
        var finished = CountDownLatch(1)
        instrumentation.runOnMainSync {
            view = PageWebView(instrumentation.targetContext)
            view.settings.javaScriptEnabled = true
            // What the tab's client reports to the bypass.
            view.webViewClient = object : WebViewClient() {
                override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) {
                    view.cacheBypass.pageStarted()
                }

                override fun onPageFinished(v: WebView?, url: String?) {
                    view.cacheBypass.pageFinished()
                    finished.countDown()
                }
            }
        }
        fun load(bypass: Boolean): String {
            finished = CountDownLatch(1)
            instrumentation.runOnMainSync {
                if (bypass) view.loadBypassingCache { view.loadUrl(page) } else view.loadUrl(page)
            }
            assertTrue("page didn't finish", finished.await(20, TimeUnit.SECONDS))
            var title = ""
            instrumentation.runOnMainSync { title = view.title.orEmpty() }
            return title
        }
        try {
            assertEquals("v1", load(bypass = false))
            assertEquals(1, site.hitsOf("/app.js"))

            // The site deploys a new script; the app's Reload (a load of
            // the same address) still runs the cached one.
            site.version = 2
            assertEquals("v1", load(bypass = false))
            assertEquals(1, site.hitsOf("/app.js"))

            // Hard reload: the page and its script come from the network.
            val pageHits = site.hitsOf("/page")
            val t = load(bypass = true)
            assertEquals("v2", t)
            assertEquals(pageHits + 1, site.hitsOf("/page"))
            assertEquals(2, site.hitsOf("/app.js"))
            var mode = -1
            instrumentation.runOnMainSync { mode = view.settings.cacheMode }
            assertEquals("the cache mode is put back once the page loaded", WebSettings.LOAD_DEFAULT, mode)

            // And the cache is used again from there.
            site.version = 3
            assertEquals("v2", load(bypass = false))
            assertEquals(2, site.hitsOf("/app.js"))
        } finally {
            instrumentation.runOnMainSync {
                view.stopLoading()
                view.destroy()
            }
            site.server.shutdown()
        }
    }

    @Test
    fun a_hard_reloaded_dweb_document_fetches_its_media_from_the_gateway_again() {
        val ref = "dddd385f2493d4bcd4d3b2c1e3c1b8f7d1a09876543210fedcba98765432dddd"
        val requests = AtomicInteger()
        val cacheControl = ConcurrentHashMap<Int, String>()
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val n = requests.incrementAndGet()
                    request.getHeader("Cache-Control")?.let { cacheControl[n] = it }
                    return MockResponse()
                        .setHeader("Content-Type", "video/mp4")
                        .setBody("clip-v$n")
                }
            }
            // The embedded gateway's own address, as [FixtureGateway].
            start(InetAddress.getByName("127.0.0.1"), 1633)
        }
        val url = VirtualOrigin.toVirtualUrl("bzz://$ref")!!.trimEnd('/') + "/clip.mp4"
        fun fetch(fresh: Boolean): String {
            val response = interceptVirtualRequest(MediaRequest(url), freshFetch = { fresh })!!
            return response.data.use { String(it.readBytes()) }
        }
        try {
            assertEquals("clip-v1", fetch(fresh = false))
            // A seek, or another page's play: served from the Range buffer.
            assertEquals("clip-v1", fetch(fresh = false))
            assertEquals(1, requests.get())
            assertNull(cacheControl[1])

            // The Hard-reloaded document's first request goes to the
            // gateway, asking it past any cache of its own…
            assertEquals("clip-v2", fetch(fresh = true))
            assertEquals(2, requests.get())
            assertEquals("no-cache", cacheControl[2])
            // …and its later Range requests use what that fetch buffered.
            assertEquals("clip-v2", fetch(fresh = false))
            assertEquals(2, requests.get())
        } finally {
            server.shutdown()
        }
    }

    private class MediaRequest(private val url: String) : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame(): Boolean = false
        override fun isRedirect(): Boolean = false
        override fun hasGesture(): Boolean = false
        override fun getMethod(): String = "GET"
        override fun getRequestHeaders(): Map<String, String> = mapOf("Range" to "bytes=0-")
    }
}
