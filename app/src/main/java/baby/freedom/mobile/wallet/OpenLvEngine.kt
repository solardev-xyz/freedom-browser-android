package baby.freedom.mobile.wallet

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/** Where an OpenLV session is, as the engine sees it. */
sealed interface OpenLvLink {
    data object Connecting : OpenLvLink
    data object Connected : OpenLvLink
    data object Disconnected : OpenLvLink
    data class Failed(val message: String) : OpenLvLink
}

/** An answer to one of the browser's JSON-RPC requests: `{result}`, or `{error: {code, message}}` with an EIP-1193 code. */
sealed interface OpenLvResponse {
    /** [json] is a JSON value as org.json holds one (a String, a JSONArray, [JSONObject.NULL]…). */
    data class Result(val json: Any) : OpenLvResponse
    data class Error(val code: Int, val message: String) : OpenLvResponse
}

/**
 * The OpenLV transport (#113, open-lavatory spec 002), behind which the
 * wallet side ([OpenLvSession]) doesn't care how the protocol is spoken.
 * [start] joins the session in an `openlv://` URI as the wallet (desktop
 * Freedom hosts it); what happens next comes back through [listener],
 * tagged with the `sid` it was started with, so the caller can tell a
 * replaced session's leftovers from the current one's. iOS's
 * `OpenLVSessionEngine`.
 */
interface OpenLvEngine {
    interface Listener {
        fun onLink(sid: Int, link: OpenLvLink)

        /** [params] as the browser sent them; answer with [respond] (always — the browser waits for it). */
        fun onRequest(sid: Int, id: Int, method: String, params: JSONArray)
    }

    var listener: Listener?

    /** Leaves any session it's in and joins [uri]'s. Main thread. */
    fun start(sid: Int, uri: String)

    /** Leaves the session. Main thread. */
    fun stop()

    /** Main thread. An answer for a session no longer current is dropped. */
    fun respond(sid: Int, id: Int, response: OpenLvResponse)
}

/** One message from `assets/openlv/shim.js`. Our own code, but read strictly: anything malformed is dropped. */
internal sealed interface OpenLvShimMessage {
    data object Ready : OpenLvShimMessage
    data class Link(val sid: Int, val link: OpenLvLink) : OpenLvShimMessage
    data class Request(val sid: Int, val id: Int, val method: String, val params: JSONArray) : OpenLvShimMessage

    companion object {
        /** The most a request may carry: a typed-data payload, with room for its JSON quoting. */
        const val MAX_MESSAGE = 2 * Eip712.MAX_JSON

        fun parse(data: String): OpenLvShimMessage? {
            if (data.length > MAX_MESSAGE) return null
            val o = try {
                JSONTokener(data).nextValue() as? JSONObject
            } catch (e: JSONException) {
                null
            } catch (e: StackOverflowError) {
                null
            } ?: return null
            return when (o.optString("type")) {
                "ready" -> Ready
                "status" -> {
                    val sid = o.intOrNull("sid") ?: return null
                    val link = when (o.optString("status")) {
                        "connecting" -> OpenLvLink.Connecting
                        "connected" -> OpenLvLink.Connected
                        "disconnected" -> OpenLvLink.Disconnected
                        "failed" -> OpenLvLink.Failed(o.optString("message").take(300).ifBlank { "The connection failed." })
                        else -> return null
                    }
                    Link(sid, link)
                }
                "request" -> {
                    val sid = o.intOrNull("sid") ?: return null
                    val id = o.intOrNull("id") ?: return null
                    val method = o.optString("method").takeIf { it.isNotEmpty() && it.length <= 64 } ?: return null
                    Request(sid, id, method, o.optJSONArray("params") ?: JSONArray())
                }
                else -> null
            }
        }

        private fun JSONObject.intOrNull(name: String): Int? = (opt(name) as? Number)?.takeIf { it is Int || it is Long }?.toInt()
    }
}

/**
 * [OpenLvEngine] number one, as on iOS: a hidden WebView running the
 * openlv SDK — the very bundle desktop Freedom vendors
 * (`assets/openlv/openlv.esm.js`, @openlv/session 0.2.0, LGPL-3.0, kept
 * as its own file so it can be replaced) — behind a small shim
 * (`shim.js`). The WebView brings what the protocol needs and Android
 * has no drop-in for: WebRTC data channels, MQTT over WebSocket to the
 * signaling relay, and the SDK's crypto.
 *
 * The page is the app's own, from its assets, served by
 * [WebViewAssetLoader] at `https://appassets.androidplatform.net` (a
 * secure context, so the SDK's `crypto.subtle` works), with a CSP that
 * allows only its own scripts and WebSocket connections. It never
 * navigates, and nothing but that page can reach the message channel.
 * The WebView is never attached to a window: it's made on the first
 * [start] and kept for the next pairing (desktop shows a new code for
 * every request). No web content is ever loaded into it.
 */
class WebViewOpenLvEngine(context: Context) : OpenLvEngine {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    override var listener: OpenLvEngine.Listener? = null

    private var webView: WebView? = null
    private var reply: JavaScriptReplyProxy? = null
    private var ready = false

    /** A [start] waiting for the page to be ready. */
    private var queued: Pair<Int, String>? = null

    /** The session the page is in, or was last asked to join. */
    private var currentSid = -1
    private val bootTimeout = Runnable { bootFailed("The signing page didn’t start.") }

    override fun start(sid: Int, uri: String) {
        currentSid = sid
        if (ready) {
            post(JSONObject().put("type", "start").put("sid", sid).put("uri", uri))
            return
        }
        queued = sid to uri
        if (webView == null) boot()
    }

    override fun stop() {
        queued = null
        if (ready) post(JSONObject().put("type", "stop"))
    }

    override fun respond(sid: Int, id: Int, response: OpenLvResponse) {
        if (!ready || sid != currentSid) return
        val o = JSONObject().put("type", "response").put("sid", sid).put("id", id)
        when (response) {
            is OpenLvResponse.Result -> o.put("result", response.json)
            is OpenLvResponse.Error -> o.put("error", JSONObject().put("code", response.code).put("message", response.message))
        }
        post(o)
    }

    private fun post(message: JSONObject) {
        reply?.postMessage(message.toString())
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun boot() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            bootFailed("This phone’s Android System WebView is too old to connect to desktop Freedom. Update it and try again.")
            return
        }
        val view = WebView(app)
        webView = view
        val assets = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(app))
            .build()
        view.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url
                if (url.host == ASSET_HOST) {
                    return assets.shouldInterceptRequest(url) ?: notFound()
                }
                // The CSP allows nothing else; should anything slip past, it doesn't leave the phone.
                return notFound()
            }

            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest) = true

            override fun onRenderProcessGone(v: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (v === webView) {
                    Log.w(TAG, "signing page's renderer went away")
                    teardown() // destroys v
                    listener?.onLink(currentSid, OpenLvLink.Failed("The connection stopped unexpectedly. Scan the code again."))
                } else {
                    v.destroy()
                }
                return true
            }
        }
        WebViewCompat.addWebMessageListener(view, CHANNEL, setOf("https://$ASSET_HOST")) { _, message, origin, isMainFrame, proxy ->
            if (view !== webView || !isMainFrame || origin.toString() != "https://$ASSET_HOST") return@addWebMessageListener
            if (message.type != WebMessageCompat.TYPE_STRING) return@addWebMessageListener
            val parsed = OpenLvShimMessage.parse(message.data ?: return@addWebMessageListener) ?: return@addWebMessageListener
            onShim(parsed, proxy)
        }
        main.postDelayed(bootTimeout, BOOT_TIMEOUT_MS)
        view.loadUrl(SHELL_URL)
    }

    private fun onShim(message: OpenLvShimMessage, proxy: JavaScriptReplyProxy) {
        when (message) {
            OpenLvShimMessage.Ready -> {
                main.removeCallbacks(bootTimeout)
                reply = proxy
                ready = true
                queued?.let { (sid, uri) ->
                    queued = null
                    start(sid, uri)
                }
            }
            is OpenLvShimMessage.Link -> if (message.sid == currentSid) listener?.onLink(message.sid, message.link)
            is OpenLvShimMessage.Request -> {
                if (message.sid != currentSid) {
                    // Only a replaced session's: the shim drops the answer, but it must not hang there.
                    return
                }
                listener?.onRequest(message.sid, message.id, message.method, message.params)
            }
        }
    }

    private fun bootFailed(message: String) {
        val sid = queued?.first ?: currentSid
        teardown()
        listener?.onLink(sid, OpenLvLink.Failed(message))
    }

    private fun teardown() {
        main.removeCallbacks(bootTimeout)
        queued = null
        ready = false
        reply = null
        webView?.let { v ->
            webView = null
            v.stopLoading()
            v.destroy()
        }
    }

    private fun notFound() = WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private companion object {
        const val TAG = "OpenLv"
        const val ASSET_HOST = WebViewAssetLoader.DEFAULT_DOMAIN
        const val SHELL_URL = "https://$ASSET_HOST/assets/openlv/shell.html"
        const val CHANNEL = "freedomOpenLV"
        const val BOOT_TIMEOUT_MS = 15_000L
    }
}
