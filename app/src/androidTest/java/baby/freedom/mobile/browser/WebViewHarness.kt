package baby.freedom.mobile.browser

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.fail
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Drives a real [WebView] through the production
 * [interceptVirtualRequest] path. The client mirrors what
 * `buildRefreshableWebView` installs — same interceptor function, so
 * every assertion in the suite covers the code the browser ships with.
 */
@SuppressLint("SetJavaScriptEnabled")
class WebViewHarness {
    lateinit var webView: WebView
        private set

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var pageFinished = CountDownLatch(1)

    /** Last main-frame HTTP error status the client observed (0 = none). */
    val lastHttpError = AtomicInteger(0)

    fun setUp() {
        instrumentation.runOnMainSync {
            webView = WebView(instrumentation.targetContext).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): WebResourceResponse? = interceptVirtualRequest(request)

                    override fun onPageFinished(view: WebView?, url: String?) {
                        pageFinished.countDown()
                    }

                    override fun onReceivedHttpError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        errorResponse: WebResourceResponse?,
                    ) {
                        if (request?.isForMainFrame == true) {
                            lastHttpError.set(errorResponse?.statusCode ?: -1)
                        }
                    }
                }
            }
        }
    }

    fun tearDown() {
        instrumentation.runOnMainSync {
            if (::webView.isInitialized) {
                webView.stopLoading()
                webView.destroy()
            }
        }
    }

    /** Load [url] and block until `onPageFinished`. */
    fun load(url: String, timeoutSeconds: Long = 60) {
        pageFinished = CountDownLatch(1)
        lastHttpError.set(0)
        instrumentation.runOnMainSync { webView.loadUrl(url) }
        if (!pageFinished.await(timeoutSeconds, TimeUnit.SECONDS)) {
            fail("page load timed out: $url")
        }
    }

    /** Evaluate [script] on the page; returns the JSON-encoded result. */
    fun js(script: String, timeoutSeconds: Long = 10): String {
        val latch = CountDownLatch(1)
        val result = AtomicReference<String>("")
        instrumentation.runOnMainSync {
            webView.evaluateJavascript(script) { value ->
                result.set(value ?: "null")
                latch.countDown()
            }
        }
        if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) {
            fail("evaluateJavascript timed out: $script")
        }
        return result.get()
    }

    /** Poll [script] until it returns `"true"` or the deadline passes. */
    fun awaitJsTrue(script: String, timeoutSeconds: Long = 30) {
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000
        while (System.currentTimeMillis() < deadline) {
            if (js(script) == "true") return
            Thread.sleep(200)
        }
        fail("condition never became true: $script (last: ${js(script)})")
    }
}
