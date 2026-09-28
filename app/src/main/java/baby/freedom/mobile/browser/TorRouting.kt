package baby.freedom.mobile.browser

import android.content.Context
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import baby.freedom.swarm.TorInfo
import baby.freedom.swarm.TorStatus
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.net.URLConnection

/**
 * `.onion` routing (#143): only onion hosts go through the embedded Tor
 * client's SOCKS5 port; everything else — clearnet, the local node
 * gateways, the dweb virtual origins — stays direct. And it fails closed:
 * with Tor off, stopped, still without a port, or gone, a `.onion` request
 * is refused, never resolved or connected directly.
 *
 * Three layers, all keyed on [isOnionHost]:
 *
 *  1. **The WebView's proxy override** ([ProxyController], reverse-bypass
 *     mode: the bypass list names the hosts that *use* the proxy). It is
 *     always in place from [init] on, pointing `*.onion` at the Tor port
 *     while Tor listens and at [REFUSE_PORT] otherwise, so even a request
 *     no interceptor sees (a WebSocket) can't reach DNS. Chromium's SOCKS5
 *     sends the hostname to the proxy, so nothing resolves locally.
 *  2. **The interceptor** ([refusalFor], first thing in
 *     `interceptVirtualRequest`): while no Tor port is routed, an onion
 *     document gets a "Tor isn't running" page served in place as the
 *     entry's own response, and a subresource an empty 502.
 *  3. **Native fetches** (downloads, Save image): [openConnection] sends an
 *     onion URL through the same port with an explicit SOCKS proxy — never
 *     a `ProxySelector`, which `HttpURLConnection` would follow with a
 *     direct attempt when the proxy fails — and refuses it without one.
 *
 * The Tor side refuses anything but a `.onion` name too (see
 * freedom-mobile-ffi `src/tor.rs`), so a misrouted clearnet request can't
 * leave through a Tor exit either.
 *
 * State is written on the main thread ([setEnabled], [onState]) and read
 * from any thread (the interceptor's IO threads).
 */
object TorRouting {
    private const val TAG = "TorRouting"

    /**
     * Where `*.onion` points while no Tor port is routed: a privileged
     * port no app can listen on, so the connection is refused at once and
     * nothing — not even the hostname — goes anywhere.
     */
    const val REFUSE_PORT = 1

    /** [refusalFor]'s page codes: why the onion site wasn't opened. */
    const val CODE_OFF = "tor_off"
    const val CODE_NOT_RUNNING = "tor_not_running"
    const val CODE_UNSUPPORTED = "tor_unsupported"

    /** `null` until [init]; false when the WebView can't do reverse-bypass proxying. */
    @Volatile
    var supported: Boolean? = null
        private set

    /** Settings → Tor. */
    @Volatile
    private var enabled = false

    @Volatile
    private var info = TorInfo()

    /**
     * The Tor port `*.onion` is routed to — set only once the WebView has
     * confirmed the override naming it — or 0 while onion requests are
     * refused. Cleared *before* an override moves away from a port.
     */
    @Volatile
    private var routedPort = 0

    /** The port the last applied (or pending) override names; main thread. */
    private var targetPort = -1

    /** Bumped per override; a stale confirmation is ignored. Main thread. */
    private var generation = 0L

    /**
     * Put the refusing override in place, before any page loads. Main
     * thread; idempotent.
     */
    fun init(context: Context) {
        if (supported != null) return
        supported = WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE_REVERSE_BYPASS)
        if (supported == false) Log.w(TAG, "WebView lacks reverse-bypass proxy override; .onion is refused")
        apply(context)
    }

    /** Settings → Tor switched. Main thread. */
    fun setEnabled(context: Context, on: Boolean) {
        enabled = on
        apply(context)
    }

    /** The `:tor` service's latest state (or Stopped once it's gone). Main thread. */
    fun onState(context: Context, state: TorInfo) {
        info = state
        apply(context)
    }

    private val TOR_HOST: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

    /** The port a native onion fetch may use, or 0. */
    val port: Int get() = routedPort

    private fun apply(context: Context) {
        if (supported != true) return
        val desired = desiredPort(supported == true, enabled, info)
        // Stop routing to the old port before anything else: Tor is
        // stopping, or moved.
        if (desired != routedPort) routedPort = 0
        val target = if (desired == 0) REFUSE_PORT else desired
        if (target == targetPort) return
        targetPort = target
        val gen = ++generation
        val config = proxyConfigFor(target)
        runCatching {
            ProxyController.getInstance().setProxyOverride(config, context.mainExecutor) {
                if (gen == generation && desired != 0) {
                    routedPort = desired
                    Log.i(TAG, ".onion → socks5://127.0.0.1:$desired")
                } else if (gen == generation) {
                    Log.i(TAG, ".onion refused (socks5://127.0.0.1:$REFUSE_PORT)")
                }
            }
        }.onFailure {
            // Keep refusing: routedPort stays 0.
            Log.w(TAG, "setProxyOverride failed", it)
        }
    }

    /**
     * Onion requests while [routedPort] is 0: a document gets the error
     * page in place (kept out of history by [NAME_RESOLUTION_ERROR_HEADER],
     * as a refused ENS document is), a subresource an empty 502. `null`
     * for a non-onion request, or while Tor is routed.
     */
    fun refusalFor(req: WebResourceRequest): WebResourceResponse? {
        val uri = req.url ?: return null
        if (!isOnionHost(uri.host)) return null
        if (routedPort != 0) return null
        val headers = mapOf("Cache-Control" to "no-store")
        if (!isDocumentRequest(req.isForMainFrame, req.requestHeaders)) {
            return WebResourceResponse(
                "text/plain", "utf-8", 502, "Tor Not Running", headers, ByteArrayInputStream(ByteArray(0)),
            )
        }
        val code = refusalCode(supported, enabled)
        return WebResourceResponse(
            "text/html", "utf-8", 503, "Tor Not Running",
            headers + (NAME_RESOLUTION_ERROR_HEADER to code),
            ByteArrayInputStream(refusalHtml(uri.host.orEmpty(), code, info).toByteArray(Charsets.UTF_8)),
        )
    }

    /**
     * Open [url] for a native fetch: a `.onion` URL through the routed Tor
     * port with an explicit SOCKS proxy (the hostname goes to Tor; nothing
     * resolves here), or an [IOException] with none; anything else as
     * [URL.openConnection] would.
     */
    fun openConnection(url: URL): URLConnection {
        if (!isOnionHost(url.host)) return url.openConnection()
        val port = routedPort
        if (port == 0) throw IOException("Tor isn't running: .onion addresses need Tor")
        // 127.0.0.1 by address, where the listener binds: Android's
        // InetAddress.getLoopbackAddress() is ::1.
        return url.openConnection(Proxy(Proxy.Type.SOCKS, InetSocketAddress(TOR_HOST, port)))
    }

    /**
     * The Tor port to route `*.onion` to, or 0 to refuse: only with the
     * WebView able to scope the proxy to onion hosts, Tor on in Settings,
     * and the client listening (starting or running).
     */
    internal fun desiredPort(supported: Boolean, enabled: Boolean, info: TorInfo): Int =
        if (supported && enabled && info.socksPort in 1..65535 &&
            (info.status == TorStatus.Starting || info.status == TorStatus.Running)
        ) info.socksPort else 0

    /**
     * Reverse bypass: the proxy applies *only* to the listed hosts. Both
     * spellings of an onion name, since Chromium keeps a trailing dot in
     * the host and the pattern is matched as written. SOCKS5 only (no
     * SOCKS4 fallback, which can't carry a hostname).
     */
    internal fun proxyConfigFor(port: Int): ProxyConfig =
        ProxyConfig.Builder()
            .addProxyRule("socks5://127.0.0.1:$port")
            .addBypassRule("*.onion")
            .addBypassRule("*.onion.")
            .setReverseBypassEnabled(true)
            .build()

    internal fun refusalCode(supported: Boolean?, enabled: Boolean): String = when {
        supported == false -> CODE_UNSUPPORTED
        !enabled -> CODE_OFF
        else -> CODE_NOT_RUNNING
    }

    /** Title and description (HTML) of the refusal page for [code]. */
    internal fun refusalCopy(code: String, info: TorInfo): Pair<String, String> = when (code) {
        CODE_UNSUPPORTED -> "Tor needs a newer WebView" to
            "This is an onion site, reachable only over Tor. This device's Android System " +
            "WebView can't send just <code>.onion</code> sites through Tor, so Freedom doesn't " +
            "open them. Update Android System WebView, then try again."
        CODE_OFF -> "Tor is off" to
            "This is an onion site, reachable only over Tor. Turn on Tor in Settings &rarr; Tor " +
            "and start it, then try again. Only <code>.onion</code> sites use Tor; every other " +
            "site connects directly."
        else -> "Tor isn't running" to (
            if (info.status == TorStatus.Error) {
                "Tor couldn't start, so this onion site wasn't opened. Try starting it again " +
                    "on the Nodes page."
            } else {
                "This is an onion site, reachable only over Tor, and Tor isn't running. Start " +
                    "it on the Nodes page (or turn on <em>Start Tor at launch</em> in " +
                    "Settings &rarr; Tor), then try again. Freedom never opens onion sites without Tor."
            }
            )
    }

    internal fun refusalHtml(host: String, code: String, info: TorInfo): String {
        val (title, description) = refusalCopy(code, info)
        fun esc(t: String) = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val error = info.errorMessage?.takeIf { code == CODE_NOT_RUNNING && info.status == TorStatus.Error }
        val details = esc(host) + "\n\n" + code + (error?.let { "\n" + esc(it) } ?: "")
        return inPlaceErrorPageHtml(title, description, details)
    }
}

/**
 * Whether [host] is an onion service name: a DNS name whose last label is
 * `onion` (any case, trailing dot allowed) with a label before it — the
 * same test the Tor side applies (freedom-mobile-ffi `is_onion_host`).
 */
internal fun isOnionHost(host: String?): Boolean {
    val h = host?.lowercase()?.removeSuffix(".") ?: return false
    if (!h.endsWith(".onion")) return false
    val rest = h.removeSuffix(".onion")
    return rest.isNotEmpty() && !rest.endsWith(".")
}
