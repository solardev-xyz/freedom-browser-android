package baby.freedom.mobile.browser

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * A blocked window's [PopupProbe] (#261) is bound to its opener's renderer.
 * If that renderer goes away while the probe waits for the window's
 * address, WebView asks every WebView on it to handle the loss — and kills
 * the whole app if any one of them answers false, which is what a
 * `WebViewClient` without `onRenderProcessGone` does. The probe must
 * answer true and give the window up as unread, like the page's own tabs
 * do (#260). Without that, this test's process is killed outright.
 */
@RunWith(AndroidJUnit4::class)
class PopupProbeRendererGoneDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var opener: WebView? = null
    private val main = Handler(Looper.getMainLooper())

    @After
    fun tearDown() {
        instrumentation.runOnMainSync { opener?.destroy() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Test
    fun a_renderer_lost_while_a_probe_waits_leaves_the_app_running() {
        assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.GET_WEB_VIEW_RENDERER))
        assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_VIEW_RENDERER_TERMINATE))
        val probing = CountDownLatch(1)
        val reported = CountDownLatch(1)
        val openerGone = CountDownLatch(1)
        val result = AtomicReference<Pair<String?, Boolean>?>(null)
        instrumentation.runOnMainSync {
            opener = WebView(instrumentation.targetContext).apply {
                settings.javaScriptEnabled = true
                settings.javaScriptCanOpenWindowsAutomatically = true
                settings.setSupportMultipleWindows(true)
                webViewClient = object : WebViewClient() {
                    // The tab's own answer, as BrowserWebView gives it.
                    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                        openerGone.countDown()
                        return true
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onCreateWindow(
                        view: WebView?,
                        isDialog: Boolean,
                        isUserGesture: Boolean,
                        resultMsg: Message?,
                    ): Boolean {
                        if (resultMsg == null) return false
                        // Deferred, like the blocker's verdict.
                        main.post {
                            val started = PopupProbe.start(context, false, resultMsg) { url, bound, _ ->
                                result.set(url to bound)
                                reported.countDown()
                            }
                            if (started) probing.countDown()
                        }
                        return true
                    }
                }
                // A blank window that is never navigated: the probe waits
                // its whole timeout for an address.
                loadDataWithBaseURL(
                    "https://opener.test/",
                    "<script>window.w = window.open('')</script>",
                    "text/html",
                    "utf-8",
                    null,
                )
            }
        }
        assertTrue("probe never started", probing.await(5, TimeUnit.SECONDS))
        // Let Chromium bind the window to the probe.
        Thread.sleep(500)
        instrumentation.runOnMainSync {
            val process = WebViewCompat.getWebViewRenderProcess(opener!!)
            assertTrue("no renderer to terminate", process?.terminate() == true)
        }
        assertTrue(openerGone.await(5, TimeUnit.SECONDS))
        // Well inside the probe's own 5 s timeout, which would report
        // the window bound (to `about:blank`).
        assertTrue("probe never reported", reported.await(3, TimeUnit.SECONDS))
        val (url, bound) = result.get()!!
        assertNull(url)
        assertFalse(bound)
    }
}
