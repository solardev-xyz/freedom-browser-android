package baby.freedom.mobile.browser

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import baby.freedom.mobile.R
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * `blob:` downloads ([BlobDownloads]) in a real WebView, installed the
 * way the tabs install it. Pages are served from `shouldInterceptRequest`
 * at `http://a.test/`; page script leaves its result as JSON in
 * `window.out`.
 */
@RunWith(AndroidJUnit4::class)
@SuppressLint("SetJavaScriptEnabled")
class BlobDownloadsDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var webView: WebView
    private lateinit var downloads: BlobDownloads
    private var finished = CountDownLatch(1)

    private fun html(body: String, headers: Map<String, String> = emptyMap()) = WebResourceResponse(
        "text/html", "utf-8", 200, "OK", headers, ByteArrayInputStream(body.toByteArray()),
    )

    /** A common policy that refuses reading `blob:` URLs (`connect-src` falls back to `'self'`). */
    private val csp = mapOf(
        "Content-Security-Policy" to "default-src 'self'; script-src 'self' 'unsafe-inline'; report-uri /report",
    )

    /**
     * Counts every look the page could take at promise machinery — what
     * a site fingerprinting Freedom would watch — from before any of its
     * own code runs. The page itself uses only timers, so anything
     * counted is the reader's.
     */
    private val watcher = """
        <script>
        var seen = { reads: 0, thens: 0, violations: [] };
        document.addEventListener('securitypolicyviolation', function (e) {
          seen.violations.push(e.effectiveDirective + ' ' + e.blockedURI);
        });
        var ctor = Object.getOwnPropertyDescriptor(Promise.prototype, 'constructor');
        Object.defineProperty(Promise.prototype, 'constructor', { configurable: true, get: function () { seen.reads++; return ctor.value; } });
        Object.defineProperty(Object.prototype, 'then', { configurable: true, get: function () { seen.thens++; return undefined; } });
        var pthen = Promise.prototype.then;
        Promise.prototype.then = function (a, b) { seen.thens++; return pthen.call(this, a, b); };
        function link(blob, name) {
          var a = document.createElement('a');
          a.href = URL.createObjectURL(blob);
          if (name) a.download = name;
          document.body.appendChild(a);
          return a;
        }
        </script>
    """.trimIndent()

    private val framed = "<!doctype html><html><head>$watcher</head><body><script>var loaded = 0;" +
        "var child = location.pathname === '/csp-framed' ? '/csp-child' : '/child';" +
        "for (var i = 0; i < 2; i++) { var f = document.createElement('iframe'); f.src = child;" +
        " f.onload = function () { loaded++; }; document.body.appendChild(f); }</script></body></html>"

    private val content = "0123456789abcdef".repeat(80_000) // 1,280,000 bytes: three chunks

    private fun pages(url: String): WebResourceResponse? {
        val many = Regex("http://f(\\d+)\\.test/").matchEntire(url)
        return when {
            url == "http://a.test/" -> html("<!doctype html><html><head>$watcher</head><body></body></html>")
            url == "http://a.test/csp" -> html("<!doctype html><html><head>$watcher</head><body></body></html>", csp)
            // The main frame, then frames of 33 other origins saying hello after it.
            url == "http://a.test/many" -> html(
                "<!doctype html><html><head>$watcher</head><body><script>var loaded = 0;</script>" +
                    (0 until 33).joinToString("") { "<iframe src=\"http://f$it.test/\" onload=\"loaded++\"></iframe>" } +
                    "</body></html>",
            )
            many != null -> html("<!doctype html><p>${many.groupValues[1]}</p>")
            // A frame of b.test that says hello, then six more that say
            // hello after it and are removed (stale frames that never answer).
            url == "http://a.test/stale" -> html(
                "<!doctype html><html><head>$watcher</head><body><script>var loaded = 0, blobUrl = '';" +
                    "addEventListener('message', function (e) { blobUrl = e.data; });" +
                    "var live = document.createElement('iframe'); live.src = 'http://b.test/live';" +
                    "live.onload = function () { gone(0); }; document.body.appendChild(live);" +
                    "function gone(i) { if (i >= 6) { loaded = 1; return; }" +
                    " var f = document.createElement('iframe'); f.src = 'http://b.test/gone';" +
                    " f.onload = function () { setTimeout(function () { f.remove(); gone(i + 1); }, 100); };" +
                    " document.body.appendChild(f); }" +
                    "</script></body></html>",
            )
            url == "http://b.test/live" -> html(
                "<!doctype html><script>onmessage = function () {" +
                    " parent.postMessage(URL.createObjectURL(new Blob(['abc'])), '*'); };</script>",
            )
            url == "http://b.test/gone" -> html("<!doctype html><p>gone</p>")
            // A main frame with two same-origin iframes, each with the reader and the watcher.
            url == "http://a.test/framed" -> html(framed)
            url == "http://a.test/csp-framed" -> html(framed, csp)
            url == "http://a.test/child" -> html("<!doctype html><html><head>$watcher</head><body></body></html>")
            url == "http://a.test/csp-child" -> html("<!doctype html><html><head>$watcher</head><body></body></html>", csp)
            else -> WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }
    }

    @Before
    fun setUp() = install(BLOB_COPY_MAX_BYTES)

    /** A fresh WebView, its reader copying files up to [copyMaxBytes]. */
    private fun install(copyMaxBytes: Long) {
        instrumentation.runOnMainSync {
            if (::webView.isInitialized) webView.destroy()
            webView = WebView(instrumentation.targetContext)
            webView.settings.javaScriptEnabled = true
            downloads = BlobDownloads.install(webView, copyMaxBytes)
                ?: error("WebView lacks WEB_MESSAGE_LISTENER / DOCUMENT_START_SCRIPT")
            webView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? =
                    request?.url?.toString()?.let(::pages)

                override fun onPageFinished(view: WebView?, url: String?) {
                    finished.countDown()
                }
            }
        }
    }

    private fun load(url: String) {
        finished = CountDownLatch(1)
        instrumentation.runOnMainSync { webView.loadUrl(url) }
        assertTrue(finished.await(30, TimeUnit.SECONDS))
    }

    @After
    fun tearDown() {
        instrumentation.runOnMainSync { if (::webView.isInitialized) webView.destroy() }
    }

    private fun js(script: String): String {
        val out = AtomicReference<String>()
        val latch = CountDownLatch(1)
        instrumentation.runOnMainSync { webView.evaluateJavascript(script) { out.set(it); latch.countDown() } }
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        return out.get()
    }

    /** Run [script], which must eventually set `window.out`; returns it parsed. */
    private fun run(script: String, timeoutMs: Long = 20_000): JSONObject {
        js("window.out = undefined; $script")
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val v = js("window.out || ''")
            if (v != "\"\"" && v != "null") return JSONObject(JSONObject("{\"v\":$v}").getString("v"))
            Thread.sleep(50)
        }
        throw AssertionError("no result for: $script")
    }

    /** [BlobDownloads.prepare] for [url], as the tab does on `DownloadListener`. */
    private fun prepare(url: String): BlobSource {
        val got = AtomicReference<BlobSource>()
        val latch = CountDownLatch(1)
        instrumentation.runOnMainSync { downloads.prepare(url) { got.set(it); latch.countDown() } }
        assertTrue("prepare answers", latch.await(40, TimeUnit.SECONDS))
        return got.get()
    }

    private fun makeAndClick(revoke: Boolean): String = run(
        """
        var a = link(new Blob(['0123456789abcdef'.repeat(80000)], { type: 'application/json' }), 'export.json');
        a.click();
        ${if (revoke) "URL.revokeObjectURL(a.href);" else ""}
        window.out = JSON.stringify({ url: a.href });
        """,
    ).getString("url")

    /**
     * #408 R1-F1: a page can click a `blob:` link of its own and cancel
     * the click — no download, no prompt — and must still see nothing of
     * the reader taking hold of the file: no promise made, awaited or
     * resolved where its hooks can count it.
     */
    @Test
    fun aCancelledBlobClickLeavesNoPromiseTrace() {
        load("http://a.test/")
        val r = run(
            """
            var a = link(new Blob(['{"x":1}'], { type: 'application/json' }), 'x.json');
            a.addEventListener('click', function (e) { e.preventDefault(); });
            a.click();
            setTimeout(function () { window.out = JSON.stringify(seen); }, 1000);
            """,
        )
        assertEquals(r.toString(), 0, r.getInt("reads"))
        assertEquals(r.toString(), 0, r.getInt("thens"))
    }

    /**
     * #408 R2-F1: under a Content-Security-Policy whose `connect-src`
     * refuses `blob:` (here through `default-src 'self'`), a page that
     * clicks a `blob:` link of its own — cancelled, or leading to no
     * download — sees no `securitypolicyviolation` (and so the site gets
     * no CSP report): nothing is read until a download is offered.
     */
    @Test
    fun aBlobClickUnderCspFiresNoViolation() {
        load("http://a.test/csp")
        val r = run(
            """
            var a = link(new Blob(['{"x":1}'], { type: 'application/json' }), 'x.json');
            a.addEventListener('click', function (e) { e.preventDefault(); });
            a.click();
            var b = link(new Blob(['y'], { type: 'text/plain' }), '');
            b.addEventListener('click', function (e) { e.preventDefault(); });
            b.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
            setTimeout(function () { window.out = JSON.stringify(seen); }, 1500);
            """,
        )
        assertEquals(r.toString(), 0, r.getJSONArray("violations").length())
        assertEquals(r.toString(), 0, r.getInt("reads"))
        assertEquals(r.toString(), 0, r.getInt("thens"))
    }

    /**
     * #408 R2-F1: a download offered under such a policy fails with its
     * own reason (not "the page withdrew this file"), and a second one in
     * the same document isn't tried at all — the one refused read is all
     * the site ever sees.
     */
    @Test
    fun aBlobDownloadUnderCspFailsAsRefused() {
        load("http://a.test/csp")
        val url = makeAndClick(revoke = true)
        val source = prepare(url)
        assertEquals(DownloadNote.of(R.string.library_download_blob_refused), source.failure)
        val url2 = makeAndClick(revoke = false)
        assertEquals(DownloadNote.of(R.string.library_download_blob_refused), prepare(url2).failure)
        val seen = run("setTimeout(function () { window.out = JSON.stringify(seen); }, 500);")
        assertEquals(seen.toString(), 1, seen.getJSONArray("violations").length())
    }

    /**
     * A clicked link whose URL the page revokes right away is still held
     * and read, chunk by chunk, with its `download` name — and still with
     * no promise trace.
     */
    @Test
    fun aClickedAndRevokedBlobIsReadWhole() {
        load("http://a.test/")
        val url = makeAndClick(revoke = true)
        val source = prepare(url)
        assertNull(source.failure)
        assertEquals("export.json", source.name)
        assertEquals("application/json", source.mimeType)
        assertEquals(content.length.toLong(), source.size)
        val bytes = source.open().use { it.readBytes() }
        assertArrayEquals(content.toByteArray(), bytes)
        val seen = run("window.out = JSON.stringify(seen);")
        assertEquals(seen.toString(), 0, seen.getInt("reads"))
        assertEquals(seen.toString(), 0, seen.getInt("thens"))
    }

    /**
     * #408 R1-M2: frames of 33 other origins saying hello after the main
     * frame don't push the main frame's origin out, so its own blob is
     * still found.
     */
    @Test
    fun manyFrameOriginsDontEvictTheMainFrame() {
        load("http://a.test/many")
        run("(function w() { if (loaded >= 33) window.out = '{}'; else setTimeout(w, 50); })();")
        Thread.sleep(500)
        val url = run(
            """
            var a = link(new Blob(['abc'], { type: 'text/plain' }), '');
            window.out = JSON.stringify({ url: a.href });
            """,
        ).getString("url")
        val source = prepare(url)
        assertNull(source.failure)
        assertEquals(3L, source.size)
        assertArrayEquals("abc".toByteArray(), source.open().use { it.readBytes() })
    }

    /**
     * #408 R4-M2: frames are asked one at a time, a second each when they
     * don't answer, so six removed same-origin frames asked before the
     * one holding the file used to run out the 5 s deadline first. The
     * deadline now counts from the last frame asked: the file is found.
     */
    @Test
    fun staleSameOriginFramesDontRunOutTheDeadline() {
        load("http://a.test/stale")
        run("(function w() { if (loaded) window.out = '{}'; else setTimeout(w, 50); })();", timeoutMs = 30_000)
        Thread.sleep(500)
        val url = run(
            """
            live.contentWindow.postMessage('go', '*');
            (function w() { if (blobUrl) window.out = JSON.stringify({ url: blobUrl }); else setTimeout(w, 50); })();
            """,
        ).getString("url")
        assertTrue(url, url.startsWith("blob:http://b.test/"))
        val startedAt = System.currentTimeMillis()
        val source = prepare(url)
        val took = System.currentTimeMillis() - startedAt
        assertNull(source.failure)
        // Asked after the six stale frames (most recent first).
        assertTrue("took $took ms", took >= 5_000)
        assertNull(source.mimeType)
        assertArrayEquals("abc".toByteArray(), source.open().use { it.readBytes() })
    }

    /**
     * #408 R4-F1: an untyped blob (`new Blob([bytes])`) has no type —
     * not the `text/plain` WebView gives its copy — whether it's copied
     * at a click it was revoked in or read from its URL, so it's saved
     * under its `download` name and not as `export.zip.txt`.
     */
    @Test
    fun anUntypedBlobHasNoType() {
        load("http://a.test/")
        for (revoke in listOf(true, false)) {
            val url = run(
                """
                var a = link(new Blob([new Uint8Array([80, 75, 3, 4])]), 'export.zip');
                a.click();
                ${if (revoke) "URL.revokeObjectURL(a.href);" else ""}
                window.out = JSON.stringify({ url: a.href });
                """,
            ).getString("url")
            val source = prepare(url)
            assertNull(source.failure)
            assertEquals("export.zip", source.name)
            assertNull("revoke=$revoke", source.mimeType)
            assertEquals("application/zip", blobMimeType(source.mimeType, source.name) { if (it == "zip") "application/zip" else null })
            assertArrayEquals(byteArrayOf(80, 75, 3, 4), source.open().use { it.readBytes() })
        }
    }

    private fun waitForFrames() {
        run("(function w() { if (loaded >= 2) window.out = '{}'; else setTimeout(w, 50); })();")
        Thread.sleep(500)
    }

    /**
     * #408 R3-M1: a same-origin iframe can read the main frame's `blob:`
     * URL too, but the frame that saw the link clicked is asked first,
     * so the link's `download` name isn't lost to the iframe's answer.
     */
    @Test
    fun aSameOriginIframeDoesntTakeTheClickedName() {
        load("http://a.test/framed")
        waitForFrames()
        repeat(5) {
            val source = prepare(makeAndClick(revoke = false))
            assertNull(source.failure)
            assertEquals("export.json", source.name)
            source.release()
        }
    }

    /**
     * #408 R3-M1: under a policy refusing `blob:` reads, a download offered
     * in a page with two same-origin iframes is tried in one frame only:
     * one violation (one CSP report) across the three, not three.
     */
    @Test
    fun aRefusedBlobDownloadWithSameOriginFramesReportsOnce() {
        load("http://a.test/csp-framed")
        waitForFrames()
        val url = run(
            """
            var a = link(new Blob(['y'], { type: 'text/plain' }), '');
            window.out = JSON.stringify({ url: a.href });
            """,
        ).getString("url")
        assertEquals(DownloadNote.of(R.string.library_download_blob_refused), prepare(url).failure)
        val r = run(
            """
            setTimeout(function () {
              var all = seen.violations.concat(frames[0].seen.violations, frames[1].seen.violations);
              window.out = JSON.stringify({ violations: all });
            }, 1000);
            """,
        )
        assertEquals(r.toString(), 1, r.getJSONArray("violations").length())
    }

    /**
     * #408 R3-F1: a file over the copy limit that the page still holds
     * isn't copied — it's read in ranges from its URL — and arrives whole,
     * with its name, and no promise trace.
     */
    @Test
    fun aFileOverTheCopyLimitIsReadFromItsUrl() {
        install(copyMaxBytes = 1_000)
        load("http://a.test/")
        val source = prepare(makeAndClick(revoke = false))
        assertNull(source.failure)
        assertEquals("export.json", source.name)
        assertEquals("application/json", source.mimeType)
        assertEquals(content.length.toLong(), source.size)
        assertArrayEquals(content.toByteArray(), source.open().use { it.readBytes() })
        val seen = run("window.out = JSON.stringify(seen);")
        assertEquals(seen.toString(), 0, seen.getInt("reads"))
        assertEquals(seen.toString(), 0, seen.getInt("thens"))
    }

    /**
     * Such a file revoked by the page before it's saved really was
     * withdrawn by the page — and says so.
     */
    @Test
    fun aFileOverTheCopyLimitRevokedBeforeItsSavedFailsAsWithdrawn() {
        install(copyMaxBytes = 1_000)
        load("http://a.test/")
        val url = makeAndClick(revoke = false)
        val source = prepare(url)
        assertNull(source.failure)
        js("URL.revokeObjectURL('$url')")
        val e = assertThrows(BlobReadException::class.java) { source.open().use { it.readBytes() } }
        assertEquals(DownloadNote.of(R.string.library_download_blob_gone), e.note)
    }

    /**
     * Revoked right at its click, a file over the copy limit has only the
     * request opened at the click left, so that is copied whatever its size.
     */
    @Test
    fun aFileOverTheCopyLimitRevokedAtItsClickIsStillCopied() {
        install(copyMaxBytes = 1_000)
        load("http://a.test/")
        val source = prepare(makeAndClick(revoke = true))
        assertNull(source.failure)
        assertEquals("export.json", source.name)
        assertArrayEquals(content.toByteArray(), source.open().use { it.readBytes() })
    }

    /**
     * #408 R3-F1, the reported case: a 200 MB blob the page still holds,
     * on an in-document `<a download>`, clicked and never revoked. A
     * second copy of it fails in WebView (ERR_ABORTED), so it's read from
     * its URL: prepared with its name and size and saved whole.
     */
    @Test
    fun a200MbBlobThePageStillHoldsIsSaved() {
        load("http://a.test/")
        val mb = 1024 * 1024
        val url = run(
            """
            var u = new Uint8Array($mb);
            for (var i = 0; i < u.length; i++) u[i] = (i * 7) & 255;
            var parts = [];
            for (var j = 0; j < 200; j++) parts.push(u);
            var a = link(new Blob(parts, { type: 'application/octet-stream' }), 'big200.bin');
            a.click();
            window.out = JSON.stringify({ url: a.href });
            """,
            timeoutMs = 60_000,
        ).getString("url")
        val source = prepare(url)
        assertNull(source.failure)
        assertEquals("big200.bin", source.name)
        assertEquals(200L * mb, source.size)
        var at = 0L
        val buf = ByteArray(64 * 1024)
        source.open().use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                for (i in 0 until n) {
                    val want = ((((at + i) % mb) * 7) and 255).toByte()
                    if (buf[i] != want) throw AssertionError("byte ${at + i}: ${buf[i]} != $want")
                }
                at += n
            }
        }
        assertEquals(200L * mb, at)
    }
}
