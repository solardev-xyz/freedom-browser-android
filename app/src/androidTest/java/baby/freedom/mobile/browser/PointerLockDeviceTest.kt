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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The pointer-lock shim (#389) in a real WebView, installed the way the
 * tabs install it ([PointerLock.install]). The page is served from
 * `shouldInterceptRequest` at `http://a.test/`, with a cross-origin frame
 * from `http://b.test/`. Each step runs page script that leaves its
 * result as JSON in `window.out`.
 */
@RunWith(AndroidJUnit4::class)
@SuppressLint("SetJavaScriptEnabled")
class PointerLockDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var webView: WebView
    private var finished = CountDownLatch(1)

    private fun html(body: String) = WebResourceResponse(
        "text/html", "utf-8", 200, "OK", emptyMap(), ByteArrayInputStream(body.toByteArray()),
    )

    /** A page that counts the pointer-lock events it gets, like a game would listen for them. */
    private val page = """
        <!doctype html><html><body><canvas id="c"></canvas><div id="h"></div>
        <iframe src="http://b.test/frame"></iframe>
        <script>
        var log = [];
        document.addEventListener('pointerlockchange', function () {
          log.push('change:' + (document.pointerLockElement ? document.pointerLockElement.id || 'el' : 'null'));
        });
        document.addEventListener('pointerlockerror', function () { log.push('error'); });
        var c = document.getElementById('c');
        </script></body></html>
    """.trimIndent()

    private val frame = """
        <!doctype html><canvas id="f"></canvas><script>
        var f = document.getElementById('f');
        f.requestPointerLock().then(function () {
          parent.postMessage(JSON.stringify({ frame: document.pointerLockElement === f }), '*');
        }, function (e) { parent.postMessage(JSON.stringify({ frame: 'rejected ' + e.name }), '*'); });
        </script>
    """.trimIndent()

    @Before
    fun setUp() {
        instrumentation.runOnMainSync {
            webView = WebView(instrumentation.targetContext)
            webView.settings.javaScriptEnabled = true
            PointerLock.install(webView)
            webView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? =
                    when (request?.url?.toString()) {
                        "http://a.test/" -> html(page)
                        "http://b.test/frame" -> html(frame)
                        else -> WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                    }

                override fun onPageFinished(view: WebView?, url: String?) {
                    finished.countDown()
                }
            }
        }
        finished = CountDownLatch(1)
        instrumentation.runOnMainSync { webView.loadUrl("http://a.test/") }
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
    private fun run(script: String): JSONObject {
        js("window.out = undefined; $script")
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val v = js("window.out || ''")
            if (v != "\"\"" && v != "null") return JSONObject(JSONObject("{\"v\":$v}").getString("v"))
            Thread.sleep(50)
        }
        throw AssertionError("no result for: $script")
    }

    @Test
    fun requestResolvesAndLocksTheElement_exitReleasesIt() {
        val r = run(
            """
            var p = c.requestPointerLock();
            var syncNull = document.pointerLockElement === null;
            p.then(function (v) {
              var after = { locked: document.pointerLockElement === c, value: v === undefined, syncNull: syncNull };
              setTimeout(function () {
                after.log = log.join();
                document.exitPointerLock();
                after.exitedNow = document.pointerLockElement === null;
                setTimeout(function () { after.logAfterExit = log.join(); window.out = JSON.stringify(after); }, 50);
              }, 50);
            }, function (e) { window.out = JSON.stringify({ rejected: String(e) }); });
            """,
        )
        assertEquals(r.toString(), true, r.optBoolean("locked"))
        assertTrue(r.toString(), r.getBoolean("value"))
        assertTrue("not locked until the request is granted", r.getBoolean("syncNull"))
        assertEquals("change:c", r.getString("log"))
        assertTrue(r.getBoolean("exitedNow"))
        assertEquals("change:c,change:null", r.getString("logAfterExit"))
    }

    @Test
    fun removingTheElementReleasesTheLock() {
        val r = run(
            """
            c.requestPointerLock().then(function () {
              c.remove();
              setTimeout(function () {
                window.out = JSON.stringify({ el: String(document.pointerLockElement), log: log.join() });
              }, 50);
            });
            """,
        )
        assertEquals("null", r.getString("el"))
        assertEquals("change:c,change:null", r.getString("log"))
    }

    @Test
    fun movingTheElementWithinTheDocumentReleasesTheLock() {
        val r = run(
            """
            var other = document.createElement('div');
            var sib = document.createElement('span');
            document.body.appendChild(other);
            c.parentNode.appendChild(sib);
            c.requestPointerLock().then(function () {
              // An unrelated removal keeps it.
              sib.remove();
              setTimeout(function () {
                var kept = log.join();
                // Removed and re-inserted in one task: Chromium releases.
                other.appendChild(c);
                setTimeout(function () {
                  var moved = log.join();
                  // And again by moving an ancestor rather than the element.
                  c.requestPointerLock().then(function () {
                    document.body.appendChild(other);
                    setTimeout(function () {
                      window.out = JSON.stringify({
                        kept: kept, moved: moved, ancestor: log.join(),
                        connected: c.isConnected, el: String(document.pointerLockElement),
                      });
                    }, 50);
                  });
                }, 50);
              }, 50);
            });
            """,
        )
        assertEquals("change:c", r.getString("kept"))
        assertEquals("change:c,change:null", r.getString("moved"))
        assertEquals("change:c,change:null,change:c,change:null", r.getString("ancestor"))
        assertTrue(r.getBoolean("connected"))
        assertEquals("null", r.getString("el"))
    }

    @Test
    fun movingTheElementIntoAnotherDocumentReleasesTheLock() {
        val r = run(
            """
            var same = document.createElement('iframe');
            document.body.appendChild(same);
            var other = same.contentDocument;
            c.requestPointerLock().then(function () {
              other.body.appendChild(c);
              setTimeout(function () {
                window.out = JSON.stringify({
                  moved: c.ownerDocument === other && c.isConnected,
                  el: String(document.pointerLockElement),
                  log: log.join(),
                });
              }, 50);
            });
            """,
        )
        assertTrue(r.toString(), r.getBoolean("moved"))
        assertEquals("null", r.getString("el"))
        assertEquals("pointerlockchange fires on adoption, not only on a read", "change:c,change:null", r.getString("log"))
    }

    @Test
    fun removingTheElementFromAShadowTreeReleasesTheLock() {
        val r = run(
            """
            var host = document.getElementById('h');
            var outer = host.attachShadow({ mode: 'closed' });
            var innerHost = document.createElement('div');
            outer.appendChild(innerHost);
            var root = innerHost.attachShadow({ mode: 'open' });
            var inner = document.createElement('canvas');
            root.appendChild(inner);
            inner.requestPointerLock().then(function () {
              inner.remove();
              setTimeout(function () {
                var first = log.join();
                // Again, removing an intermediate host from the outer shadow tree.
                root.appendChild(inner);
                inner.requestPointerLock().then(function () {
                  innerHost.remove();
                  setTimeout(function () {
                    window.out = JSON.stringify({ first: first, second: log.join() });
                  }, 50);
                });
              }, 50);
            });
            """,
        )
        assertEquals("change:h,change:null", r.getString("first"))
        assertEquals("change:h,change:null,change:h,change:null", r.getString("second"))
    }

    @Test
    fun pageGettersOnObjectPrototypeAreNeverReadAndChangeNothing() {
        val r = run(
            """
            var reads = [];
            var names = ['bubbles', 'cancelable', 'composed', 'childList', 'subtree', 'attributes',
                         'attributeOldValue', 'attributeFilter', 'characterData', 'characterDataOldValue'];
            names.forEach(function (k) {
              Object.defineProperty(Object.prototype, k, { configurable: true, get: function () {
                reads.push(k);
                return k === 'attributeFilter' ? undefined : true;
              } });
            });
            var composed;
            document.addEventListener('pointerlockchange', function (e) { composed = e.composed + ',' + e.cancelable; }, { once: true, __proto__: null });
            c.requestPointerLock().then(function () {
              setTimeout(function () {
                names.forEach(function (k) { delete Object.prototype[k]; });
                window.out = JSON.stringify({ reads: reads.join(' ; '), composed: composed, locked: document.pointerLockElement === c });
              }, 50);
            });
            """,
        )
        assertEquals("", r.getString("reads"))
        assertEquals("false,false", r.getString("composed"))
        assertTrue(r.toString(), r.getBoolean("locked"))
    }

    @Test
    fun aDisconnectedElementGetsAnErrorAndWrongDocumentError() {
        val r = run(
            """
            document.createElement('canvas').requestPointerLock().then(
              function () { window.out = JSON.stringify({ name: 'resolved' }); },
              function (e) { setTimeout(function () { window.out = JSON.stringify({ name: e.name, log: log.join(), el: String(document.pointerLockElement) }); }, 0); });
            """,
        )
        assertEquals("WrongDocumentError", r.getString("name"))
        assertEquals("error", r.getString("log"))
        assertEquals("null", r.getString("el"))
    }

    @Test
    fun aShadowTreeElementIsRetargetedToItsHost() {
        val r = run(
            """
            var host = document.getElementById('h');
            var root = host.attachShadow({ mode: 'open' });
            var inner = document.createElement('canvas');
            root.appendChild(inner);
            inner.requestPointerLock().then(function () {
              window.out = JSON.stringify({ doc: document.pointerLockElement === host, shadow: root.pointerLockElement === inner });
            });
            """,
        )
        assertTrue(r.toString(), r.getBoolean("doc"))
        assertTrue(r.toString(), r.getBoolean("shadow"))
    }

    @Test
    fun looksNativeAndSurvivesThePageWrappingWhatItUses() {
        val r = run(
            """
            var calls = 0;
            var origDispatch = EventTarget.prototype.dispatchEvent;
            EventTarget.prototype.dispatchEvent = function () { calls++; return origDispatch.apply(this, arguments); };
            var origTimeout = window.setTimeout;
            window.setTimeout = function () { calls++; return origTimeout.apply(this, arguments); };
            var origPromiseResolve = Promise.resolve.bind(Promise);
            window.Promise = function () { calls++; throw new Error('wrapped'); };
            var looks = {
              req: Function.prototype.toString.call(Element.prototype.requestPointerLock).indexOf('[native code]') >= 0,
              exit: Function.prototype.toString.call(Document.prototype.exitPointerLock).indexOf('[native code]') >= 0,
              has: 'requestPointerLock' in c,
            };
            // A foreign receiver: Chromium's own answer (a promise-returning
            // IDL method rejects with a TypeError rather than throwing).
            var illegal;
            try { illegal = Element.prototype.requestPointerLock.call({}); } catch (e) { illegal = Promise.reject(e); }
            origPromiseResolve(illegal).then(function () { return 'resolved'; }, function (e) { return e.name; }).then(function (name) {
              looks.illegal = name;
              return c.requestPointerLock();
            }).then(function () {
              looks.locked = document.pointerLockElement === c;
              looks.calls = calls;
              window.out = JSON.stringify(looks);
            });
            """,
        )
        assertTrue(r.toString(), r.getBoolean("req"))
        assertTrue(r.toString(), r.getBoolean("exit"))
        assertTrue(r.toString(), r.getBoolean("has"))
        assertEquals("TypeError", r.getString("illegal"))
        assertTrue(r.toString(), r.getBoolean("locked"))
        assertEquals("the page saw none of the shim's calls", 0, r.getInt("calls"))
    }

    @Test
    fun aSyntheticKeydownNeitherThrowsNorReleases() {
        val r = run(
            """
            var errors = [];
            window.addEventListener('error', function (e) { errors.push(String(e.message)); });
            c.requestPointerLock().then(function () {
              window.dispatchEvent(new Event('keydown'));
              document.dispatchEvent(new Event('keydown', { bubbles: true }));
              window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
              setTimeout(function () {
                window.out = JSON.stringify({ errors: errors.join(' ; '), locked: document.pointerLockElement === c, log: log.join() });
              }, 50);
            });
            """,
        )
        assertEquals("", r.getString("errors"))
        assertTrue("only a real Escape releases the lock: $r", r.getBoolean("locked"))
        assertEquals("change:c", r.getString("log"))
    }

    @Test
    fun proxyTrapLookupsNeverReachObjectPrototype() {
        val r = run(
            """
            var reads = [];
            var traps = ['get', 'set', 'has', 'deleteProperty', 'ownKeys', 'apply', 'construct', 'getPrototypeOf',
                         'setPrototypeOf', 'isExtensible', 'preventExtensions', 'getOwnPropertyDescriptor', 'defineProperty'];
            traps.forEach(function (k) {
              // Prototype-less, or defining 'set' would itself read the 'get' getter.
              Object.defineProperty(Object.prototype, k, { __proto__: null, configurable: true, get: function () { reads.push(k); return undefined; } });
            });
            reads = [];
            var touched = [];
            [Element.prototype.requestPointerLock, Document.prototype.exitPointerLock,
             Object.getOwnPropertyDescriptor(Document.prototype, 'pointerLockElement').get,
             Object.getOwnPropertyDescriptor(ShadowRoot.prototype, 'pointerLockElement').get].forEach(function (f) {
              touched.push(f.length, f.name, 'x' in f, Object.getPrototypeOf(f) === Function.prototype,
                           Object.isExtensible(f), Object.keys(f).length, Object.getOwnPropertyDescriptor(f, 'length').value);
            });
            var p = c.requestPointerLock();
            var el = document.pointerLockElement;
            traps.forEach(function (k) { delete Object.prototype[k]; });
            p.then(function () {
              window.out = JSON.stringify({ reads: reads.join(' ; '), locked: document.pointerLockElement === c });
            });
            """,
        )
        assertEquals("", r.getString("reads"))
        assertTrue(r.toString(), r.getBoolean("locked"))
    }

    @Test
    fun aCrossOriginFrameLocksItsOwnElement() {
        val r = run(
            """
            window.addEventListener('message', function (e) {
              var m = JSON.parse(e.data); m.top = String(document.pointerLockElement); window.out = JSON.stringify(m);
            });
            document.querySelector('iframe').src = 'http://b.test/frame';
            """,
        )
        assertTrue(r.toString(), r.getBoolean("frame"))
        assertEquals("null", r.getString("top"))
    }
}
