package baby.freedom.mobile.browser

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Scriptlets (#318) in a real WebView, through [TabScriptlets] — the
 * class the tabs use — with an engine built from fixture rules and the
 * uBlock Origin scriptlets the app ships. The pages are served from
 * `shouldInterceptRequest` on two default-port origins, `http://a.test`
 * and `http://b.test`, which also registers each document's host there
 * first, as the tab's interceptor does.
 *
 * Checks that the scriptlets run before the page's first script, in the
 * main frame and in a cross-origin frame, only on the hosts the rules
 * name, not on an allowlisted page (nor in its frames), under a strict
 * CSP without a single violation, and leave nothing of theirs on
 * `window`.
 */
@RunWith(AndroidJUnit4::class)
@SuppressLint("SetJavaScriptEnabled")
class AdblockScriptletsDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var webView: WebView
    private lateinit var tab: TabScriptlets
    private val source = FixtureSource()
    private var finished = CountDownLatch(1)

    /** The fixture's scriptlets: fixed rules, and an allowlist the test sets. */
    private inner class FixtureSource : ScriptletSource {
        val catalog = checkNotNull(
            ScriptletCatalog.parse(
                instrumentation.targetContext.assets.open("adblock/$SCRIPTLET_RESOURCES_FILE")
                    .bufferedReader().use { it.readText() },
            ),
        )
        val engine = AdblockEngine.build(
            listOf(
                FilterListText(
                    """
                    a.test,b.test##+js(set, fixtureFlag, true)
                    a.test,b.test##+js(json-prune, ad)
                    b.test##+js(trusted-replace-fetch-response, '"ad"', '"no"', /data)
                    """.trimIndent(),
                    trustedScriptlets = true,
                ),
            ),
            catalog,
        )

        @Volatile
        var allowlist: Set<String> = emptySet()

        @Volatile
        override var generation = 0

        override fun hasScriptlets(host: String) = engine.scriptletsFor(host).isNotEmpty()

        override fun script(host: String, private: Boolean): String? {
            val calls = engine.scriptletsFor(host)
            if (calls.isEmpty()) return null
            return scriptletFrameJs(host, catalog.code(calls), allowlist)
        }
    }

    private fun html(body: String, csp: Boolean = true) = WebResourceResponse(
        "text/html", "utf-8", 200, "OK",
        buildMap {
            if (csp) put("Content-Security-Policy", "default-src 'none'; script-src 'unsafe-inline'; frame-src http://b.test; connect-src 'self'")
        },
        ByteArrayInputStream(body.toByteArray()),
    )

    /**
     * The top page: its very first script records what the page's own
     * code finds before anything else of its runs, then waits for the
     * frame's report and its own fetch, and puts it all in `window.report`.
     */
    private val topPage = """
        <!doctype html><html><head><script>
        var r = { flagAtStart: String(window.fixtureFlag), violations: 0 };
        document.addEventListener('securitypolicyviolation', function () { r.violations++; });
        r.pruned = JSON.stringify(JSON.parse('{"ad":1,"keep":2}'));
        r.leaked = ['safeSelf', 'scriptletGlobals', 'setConstant', 'jsonPrune'].filter(function (n) { return n in window; });
        var pending = 2;
        function done() { if (--pending === 0) window.report = JSON.stringify(r); }
        window.addEventListener('message', function (e) { r.frame = e.data; done(); });
        fetch('/data').then(function (x) { return x.text(); }).then(function (t) { r.fetched = t; done(); });
        </script></head><body><iframe src="http://b.test/frame"></iframe></body></html>
    """.trimIndent()

    /** A cross-origin frame's first script reports what it finds to its parent. */
    private val framePage = """
        <!doctype html><script>
        var f = { flag: String(window.fixtureFlag), pruned: JSON.stringify(JSON.parse('{"ad":1,"keep":2}')) };
        fetch('/data').then(function (x) { return x.text(); }).then(function (t) {
          f.fetched = t; parent.postMessage(JSON.stringify(f), '*');
        });
        </script>
    """.trimIndent()

    @Before
    fun setUp() {
        instrumentation.runOnMainSync {
            webView = WebView(instrumentation.targetContext)
            webView.settings.javaScriptEnabled = true
            tab = checkNotNull(TabScriptlets.install(webView, private = false, source = source))
            webView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val url = request?.url?.toString() ?: return null
                    val accept = request.requestHeaders.entries.firstOrNull { it.key.equals("Accept", true) }?.value.orEmpty()
                    if (request.isForMainFrame || accept.startsWith("text/html")) tab.ensureFromNetworkThread(url)
                    return when (url) {
                        "http://a.test/" -> html(topPage)
                        "http://b.test/frame" -> html(framePage)
                        "http://b.test/" -> html("<script>document.title = 'b:' + String(window.fixtureFlag)</script>")
                        "http://c.test/" -> html("<script>document.title = 'c:' + String(window.fixtureFlag)</script>")
                        "http://a.test/data", "http://b.test/data" -> WebResourceResponse(
                            "application/json", "utf-8", 200, "OK",
                            mapOf("Access-Control-Allow-Origin" to "*"),
                            ByteArrayInputStream("{\"ad\":true}".toByteArray()),
                        )
                        else -> WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    finished.countDown()
                }
            }
        }
    }

    @After
    fun tearDown() {
        instrumentation.runOnMainSync {
            if (::tab.isInitialized) tab.close()
            if (::webView.isInitialized) webView.destroy()
        }
    }

    private fun load(url: String) {
        finished = CountDownLatch(1)
        instrumentation.runOnMainSync {
            tab.ensure(url)
            webView.loadUrl(url)
        }
        assertTrue("load $url", finished.await(30, TimeUnit.SECONDS))
    }

    private fun js(script: String): String {
        val out = AtomicReference<String>()
        val latch = CountDownLatch(1)
        instrumentation.runOnMainSync { webView.evaluateJavascript(script) { out.set(it); latch.countDown() } }
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        return out.get()
    }

    /** `window.report` once the page has filled it in. */
    private fun report(): JSONObject {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            val v = js("window.report || ''")
            if (v != "\"\"" && v != "null") return JSONObject(JSONObject("{\"v\":$v}").getString("v"))
            Thread.sleep(100)
        }
        throw AssertionError("no report: ${js("document.documentElement.outerHTML")}")
    }

    @Test
    fun scriptletsRunBeforePageScriptInTheMainFrameAndACrossOriginFrame() {
        load("http://a.test/")
        val r = report()
        // set-constant: in place before the page's first script ran.
        assertEquals("true", r.getString("flagAtStart"))
        // json-prune: the page's own JSON.parse lost the ad key.
        assertEquals("{\"keep\":2}", r.getString("pruned"))
        // No trusted-replace-fetch-response on a.test: its fetch is untouched.
        assertEquals("{\"ad\":true}", r.getString("fetched"))
        // Nothing of ours on window, and no CSP violation reported.
        assertEquals(0, r.getJSONArray("leaked").length())
        assertEquals(0, r.getInt("violations"))
        // The cross-origin frame ran its own host's calls, trusted one included.
        val frame = JSONObject(r.getString("frame"))
        assertEquals("true", frame.getString("flag"))
        assertEquals("{\"keep\":2}", frame.getString("pruned"))
        assertEquals("{\"no\":true}", frame.getString("fetched"))
        // Each with its www. twin (a.test's rules cover its subdomains).
        assertEquals(setOf("a.test", "www.a.test", "b.test", "www.b.test"), tab.hosts)
    }

    @Test
    fun aHostWithNoRulesGetsNothing() {
        load("http://c.test/")
        assertEquals("\"c:undefined\"", js("document.title"))
        assertFalse("c.test" in tab.hosts)
    }

    @Test
    fun anAllowlistedPageAndItsFramesRunNone() {
        load("http://a.test/")
        assertEquals("true", report().getString("flagAtStart"))
        source.allowlist = setOf("a.test")
        instrumentation.runOnMainSync {
            source.generation++
            tab.refresh()
        }
        load("http://a.test/")
        val r = report()
        assertEquals("undefined", r.getString("flagAtStart"))
        assertEquals("{\"ad\":1,\"keep\":2}", r.getString("pruned"))
        val frame = JSONObject(r.getString("frame"))
        // b.test isn't allowlisted, but the page it's on is.
        assertEquals("undefined", frame.getString("flag"))
        assertEquals("{\"ad\":true}", frame.getString("fetched"))
        // b.test as a page of its own still gets its scriptlets.
        load("http://b.test/")
        assertEquals("\"b:true\"", js("document.title"))
        assertNotNull(tab.hosts)
    }

    /**
     * A tab at its budget (here 3 hosts) whose oldest entry is the host
     * a document is now asked for, its twin already dropped: adding the
     * twin back must not push that host out (R2-F1). Asked for from a
     * network thread, as the interceptor does.
     */
    @Test
    fun aDocumentRequestNeverDropsItsOwnHostForItsTwin() {
        val every = object : ScriptletSource {
            override val generation = 0
            override fun hasScriptlets(host: String) = true
            override fun script(host: String, private: Boolean) = "/* $host */"
        }
        lateinit var small: TabScriptlets
        instrumentation.runOnMainSync {
            small = TabScriptlets(webView, private = false, source = every, maxHosts = 3)
            small.ensure("http://h1.test/")
            small.ensure("http://h2.test/")
        }
        // www.h1.test, the oldest, made room for h2.test's pair.
        assertEquals(setOf("h1.test", "www.h2.test", "h2.test"), small.hosts)
        small.ensureFromNetworkThread("http://h1.test/")
        val hosts = small.hosts
        assertTrue("h1.test in $hosts", "h1.test" in hosts)
        assertTrue("www.h1.test in $hosts", "www.h1.test" in hosts)
        assertEquals(3, hosts.size)
        instrumentation.runOnMainSync { small.close() }
    }
}
