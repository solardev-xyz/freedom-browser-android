package baby.freedom.mobile.browser

import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * [Passkeys] on a real WebView (#524), as the WebView itself reports the
 * mode back: what a tab gets on this device, that each mode the decision
 * can make actually takes, and that [PasskeyGate] moves it; and, read in
 * a page itself, that the first page after a held one gets WebAuthn
 * (R2-F1).
 */
@RunWith(AndroidJUnit4::class)
class PasskeysDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun readBack(set: (WebView) -> Unit): Int {
        var mode = -1
        instrumentation.runOnMainSync {
            val view = WebView(instrumentation.targetContext)
            try {
                set(view)
                mode = WebSettingsCompat.getWebAuthenticationSupport(view.settings)
            } finally {
                view.destroy()
            }
        }
        return mode
    }

    @Test
    fun tabs_get_the_mode_the_decision_names_for_this_device() {
        assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_AUTHENTICATION))
        val context = instrumentation.targetContext
        val major = Passkeys.majorVersion(WebViewCompat.getCurrentWebViewPackage(context)?.versionName)
        instrumentation.runOnMainSync { PasskeyGate.appVisible(true) }
        try {
            for (private in listOf(false, true)) {
                val expected = Passkeys.mode(true, major, Build.VERSION.SDK_INT, private)
                assertEquals(expected, readBack { PasskeyGate(it.settings, Passkeys.mode(context, private)) })
            }
            // A private tab is off whatever the WebView.
            assertEquals(
                WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_NONE,
                readBack { PasskeyGate(it.settings, Passkeys.mode(context, privateTab = true)) },
            )
        } finally {
            instrumentation.runOnMainSync { PasskeyGate.appVisible(false) }
        }
    }

    @Test
    fun the_gate_drops_browser_mode_for_a_device_document_and_with_the_app_away() {
        assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_AUTHENTICATION))
        val on = WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_BROWSER
        val off = WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_NONE
        val seen = mutableListOf<Int>()
        instrumentation.runOnMainSync {
            val view = WebView(instrumentation.targetContext)
            fun mode() = WebSettingsCompat.getWebAuthenticationSupport(view.settings)
            try {
                PasskeyGate.appVisible(true)
                val gate = PasskeyGate(view.settings, on)
                PasskeyGate.register(gate)
                seen += mode()
                // A gateway iframe (#524 R1-F1): off at once, and its page stays off.
                gate.frameRequested("http://localhost:1633/bzz/abc/")
                seen += mode()
                gate.committed("https://example.com/")
                seen += mode()
                // Back only with a new document off the device.
                gate.mainFrameRequested("https://example.com/next")
                gate.committed("https://example.com/next")
                seen += mode()
                // The app away (R1-F2), and back.
                PasskeyGate.appVisible(false)
                seen += mode()
                PasskeyGate.appVisible(true)
                seen += mode()
                PasskeyGate.unregister(gate)
            } finally {
                PasskeyGate.appVisible(false)
                view.destroy()
            }
        }
        assertEquals(listOf(on, off, off, on, off, on), seen)
    }

    @Test
    fun browser_mode_and_off_both_take_on_a_real_webview() {
        assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_AUTHENTICATION))
        val above = Passkeys.MIN_WEBVIEW_MAJOR
        val on = Passkeys.mode(true, above, Build.VERSION_CODES.UPSIDE_DOWN_CAKE, privateTab = false)!!
        val off = Passkeys.mode(true, above, Build.VERSION_CODES.UPSIDE_DOWN_CAKE, privateTab = true)!!
        assertEquals(WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_BROWSER, readBack { Passkeys.set(it.settings, on) })
        assertEquals(WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_NONE, readBack { Passkeys.set(it.settings, off) })
    }

    /**
     * R2-F1: the page that the commit raises the mode for was made while
     * it was down, and has no WebAuthn — read in the page itself, not
     * off the setting — until the gate's one reload.
     */
    @Test
    fun the_first_page_after_a_held_one_gets_webauthn_through_one_reload() {
        assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_AUTHENTICATION))
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        var reloads = true
        lateinit var view: WebView
        lateinit var gate: PasskeyGate
        instrumentation.runOnMainSync {
            PasskeyGate.appVisible(true)
            view = WebView(instrumentation.targetContext)
            view.settings.javaScriptEnabled = true
            gate = PasskeyGate(view.settings, WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_BROWSER)
            PasskeyGate.register(gate)
            view.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): WebResourceResponse? {
                    if (request.url.host != "passkeys.test") return null
                    val url = request.url.toString()
                    if (request.isForMainFrame) PasskeyGate.fromWorker(url) { gate.mainFrameRequested(url) }
                    return WebResourceResponse("text/html", "utf-8", "<!doctype html><title>t</title>".byteInputStream())
                }

                override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
                    events += "start $url"
                    if (reloads) gate.committed(url, v) else gate.committed(url)
                }

                override fun onPageFinished(v: WebView, url: String?) {
                    events += "finish $url"
                }
            }
        }
        fun api(): String {
            val answer = LinkedBlockingQueue<String>()
            instrumentation.runOnMainSync {
                view.evaluateJavascript("typeof PublicKeyCredential + ' ' + isSecureContext") { answer.add(it) }
            }
            return answer.poll(5, TimeUnit.SECONDS) ?: "none"
        }
        fun load(url: String, starts: Int) {
            instrumentation.runOnMainSync { view.loadUrl(url) }
            val deadline = SystemClock.uptimeMillis() + 10_000
            while (SystemClock.uptimeMillis() < deadline) {
                val seen = synchronized(events) { events.toList() }
                val lastStart = seen.lastIndexOf("start $url")
                if (seen.count { it == "start $url" } >= starts && seen.drop(lastStart).contains("finish $url")) break
                SystemClock.sleep(50)
            }
            // Room for a reload that shouldn't come.
            SystemClock.sleep(1_000)
        }
        try {
            load("https://passkeys.test/a", starts = 1)
            assertEquals("\"function true\"", api())
            // A gateway frame holds the tab.
            instrumentation.runOnMainSync { gate.frameRequested("http://localhost:1633/bzz/abc/") }
            // Without the reload, the clean page after it comes up without
            // WebAuthn although the mode is back up.
            reloads = false
            load("https://passkeys.test/b", starts = 1)
            assertEquals("\"undefined true\"", api())
            var mode = -1
            instrumentation.runOnMainSync { mode = WebSettingsCompat.getWebAuthenticationSupport(view.settings) }
            assertEquals(WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_BROWSER, mode)
            // With it: made twice, the second time with WebAuthn, and no more.
            instrumentation.runOnMainSync { gate.frameRequested("http://localhost:1633/bzz/abc/") }
            reloads = true
            load("https://passkeys.test/c", starts = 2)
            assertEquals(events.toString(), "\"function true\"", api())
            assertEquals(2, events.count { it == "start https://passkeys.test/c" })
        } finally {
            instrumentation.runOnMainSync {
                PasskeyGate.unregister(gate)
                PasskeyGate.appVisible(false)
                view.destroy()
            }
        }
    }
}

