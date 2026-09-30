package baby.freedom.mobile.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * [PopupProbe.discard] on a real WebView (#292 R2-M1): a window
 * `onCreateWindow` answered true for but nobody took holds the tab's one
 * pending-window slot, so its later windows are dropped; a window handed back
 * with no WebView in it — what the blocker does with a refused window,
 * and with one no probe could be set up for — frees the tab at once.
 */
@RunWith(AndroidJUnit4::class)
class PopupDiscardDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var webView: WebView? = null
    private val children = mutableListOf<WebView>()

    // Not `View.post`: the WebView is never attached, and a detached
    // view holds its posts until it is.
    private val main = Handler(Looper.getMainLooper())

    @After
    fun tearDown() {
        instrumentation.runOnMainSync {
            children.forEach { it.destroy() }
            webView?.destroy()
        }
    }

    /**
     * Opens two no-gesture windows, to a.test then b.test 500 ms later;
     * [first] settles the first, and the second is handed a WebView
     * that records the address it is asked to load. Returns that
     * address, or null if it loaded nothing in time.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun secondWindowLoads(first: (Message) -> Unit): String? {
        val calls = AtomicInteger(0)
        val loaded = CountDownLatch(1)
        val url = AtomicReference<String?>(null)
        instrumentation.runOnMainSync {
            webView = WebView(instrumentation.targetContext).apply {
                settings.javaScriptEnabled = true
                settings.javaScriptCanOpenWindowsAutomatically = true
                settings.setSupportMultipleWindows(true)
                webChromeClient = object : WebChromeClient() {
                    override fun onCreateWindow(
                        view: WebView?,
                        isDialog: Boolean,
                        isUserGesture: Boolean,
                        resultMsg: Message?,
                    ): Boolean {
                        if (resultMsg == null) return false
                        // Deferred, like the blocker's verdict.
                        if (calls.incrementAndGet() == 1) {
                            main.post { first(resultMsg) }
                            return true
                        }
                        val child = WebView(context).also { children += it }
                        child.settings.blockNetworkLoads = true
                        fun saw(u: String?) {
                            if (u != null && u != "about:blank" && url.compareAndSet(null, u)) loaded.countDown()
                        }
                        // Wherever the window's first navigation shows
                        // itself, as in [PopupProbe].
                        child.webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(v: WebView?, r: WebResourceRequest?): Boolean {
                                if (r?.isForMainFrame == true) saw(r.url.toString())
                                return true
                            }

                            override fun shouldInterceptRequest(v: WebView?, r: WebResourceRequest?): WebResourceResponse {
                                if (r?.isForMainFrame == true) saw(r.url.toString())
                                return WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                            }

                            override fun onPageStarted(v: WebView?, u: String?, favicon: Bitmap?) = saw(u)
                        }
                        main.post {
                            (resultMsg.obj as WebView.WebViewTransport).webView = child
                            resultMsg.sendToTarget()
                        }
                        return true
                    }
                }
                loadDataWithBaseURL(
                    "https://opener.test/",
                    "<script>window.open('https://a.test/');" +
                        "setTimeout(function(){window.open('https://b.test/')},500)</script>",
                    "text/html",
                    "utf-8",
                    null,
                )
            }
        }
        loaded.await(5, TimeUnit.SECONDS)
        return url.get()
    }

    @Test
    fun a_discarded_window_frees_the_tab_for_its_next() {
        assertEquals("https://b.test/", secondWindowLoads { PopupProbe.discard(it) })
    }

    @Test
    fun a_discard_after_the_transport_went_back_does_not_throw() {
        assertEquals(
            "https://b.test/",
            secondWindowLoads {
                PopupProbe.discard(it)
                PopupProbe.discard(it)
            },
        )
    }

    /**
     * The premise: Chromium keeps one pending window per tab. While the
     * first is untaken, the second's own contents are dropped ("Blocking
     * popup window creation as an outstanding popup window is still
     * pending"), and the WebView handed for it gets the stale first one.
     */
    @Test
    fun a_window_nobody_takes_holds_up_the_next() {
        assertEquals("https://a.test/", secondWindowLoads { })
    }
}
