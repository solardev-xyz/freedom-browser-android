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
import java.net.HttpURLConnection
import java.net.IDN
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.net.URLConnection
import java.text.Normalizer

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

    /** [ProxyController.setProxyOverride]; swapped in tests. */
    internal var setOverride: (ProxyConfig, Context, Runnable) -> Unit = { config, context, done ->
        ProxyController.getInstance().setProxyOverride(config, context.mainExecutor, done)
    }

    /** Tests: pretend [init] found reverse-bypass support, with nothing applied yet. */
    internal fun resetForTest(supported: Boolean?) {
        this.supported = supported
        enabled = false
        info = TorInfo()
        routedPort = 0
        targetPort = -1
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
        val gen = ++generation
        val config = proxyConfigFor(target)
        runCatching {
            // Recorded only once the WebView took the override: after a
            // failure the next apply() with the same target retries.
            targetPort = target
            setOverride(config, context) {
                if (gen == generation && desired != 0) {
                    routedPort = desired
                    Log.i(TAG, ".onion → socks5://127.0.0.1:$desired")
                } else if (gen == generation) {
                    Log.i(TAG, ".onion refused (socks5://127.0.0.1:$REFUSE_PORT)")
                }
            }
        }.onFailure {
            // Keep refusing (routedPort stays 0), and forget the target so
            // the next state update tries again.
            targetPort = -1
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
     *
     * The host is judged the way the HTTP stack will read it, not as
     * [URL.getHost] spells it: `HttpURLConnection` re-parses the URL's
     * string and percent-decodes and IDNA-maps the host, so a redirect to
     * `http://name%2eonion/` or `http://name\u3002onion/` is still an
     * onion fetch ([fetchMayReachOnion]).
     *
     * Don't let the connection follow redirects itself — each hop has to
     * come back through here. Use [openFollowingRedirects] for that.
     */
    fun openConnection(url: URL): URLConnection {
        if (!fetchMayReachOnion(url)) return url.openConnection()
        val port = routedPort
        if (port == 0) throw RefusedException()
        // 127.0.0.1 by address, where the listener binds: Android's
        // InetAddress.getLoopbackAddress() is ::1.
        return url.openConnection(Proxy(Proxy.Type.SOCKS, InetSocketAddress(TOR_HOST, port)))
    }

    /**
     * Open [url] and send the request, following redirects by hand so
     * every hop is routed by [openConnection] — `HttpURLConnection`'s own
     * redirect following would open an onion hop off a clearnet URL
     * directly, resolving it through the system DNS.
     *
     * [configure] runs on each hop's fresh connection (method, timeouts,
     * headers), with that hop's URL; [body], if any, is written to the
     * first hop. Redirects are followed the way `HttpURLConnection` would
     * ([redirectFor]). Returns the final connection with its response
     * status read; every earlier hop is disconnected.
     */
    fun openFollowingRedirects(
        url: URL,
        body: ByteArray? = null,
        configure: HttpURLConnection.(hop: URL) -> Unit,
    ): HttpURLConnection {
        var current = url
        var method: String? = null
        var payload = body
        repeat(MAX_REDIRECTS + 1) {
            val conn = openConnection(current) as? HttpURLConnection
                ?: throw IOException("Not an HTTP URL: $current")
            var keep = false
            try {
                conn.configure(current)
                method?.let { conn.requestMethod = it }
                conn.instanceFollowRedirects = false
                payload?.let { bytes ->
                    conn.doOutput = true
                    conn.outputStream.use { it.write(bytes) }
                }
                val code = conn.responseCode
                val next = redirectFor(current, code, conn.requestMethod, conn.getHeaderField("Location"))
                if (next == null) {
                    keep = true
                    return conn
                }
                current = next.url
                if (next.toGet) {
                    method = "GET"
                    payload = null
                }
            } finally {
                if (!keep) runCatching { conn.disconnect() }
            }
        }
        throw IOException("Too many redirects")
    }

    /** [openConnection]'s refusal of an onion URL while no Tor port is routed. */
    class RefusedException : IOException("Tor isn't running: .onion addresses need Tor")

    /**
     * Whether [a] and [b] are the same origin (scheme, host, port) — a
     * redirect hop that isn't doesn't get the first request's credentials.
     */
    fun sameOrigin(a: URL, b: URL): Boolean =
        a.protocol.equals(b.protocol, ignoreCase = true) &&
            a.host.equals(b.host, ignoreCase = true) &&
            (if (a.port == -1) a.defaultPort else a.port) == (if (b.port == -1) b.defaultPort else b.port)

    /** `HttpURLConnection`'s own follow-up limit. */
    internal const val MAX_REDIRECTS = 20

    internal class Redirect(val url: URL, val toGet: Boolean)

    /**
     * Where a [code] response with [location] to a [method] request for
     * [from] redirects, or `null` to hand it to the caller as is — the
     * rules `HttpURLConnection` follows: 300–303 always (a request with
     * a body is re-sent as a bodiless GET), 307/308 only for GET/HEAD,
     * and never to another scheme (Android's `HttpURLConnection` doesn't
     * follow http ↔ https).
     */
    internal fun redirectFor(from: URL, code: Int, method: String, location: String?): Redirect? {
        if (location.isNullOrEmpty()) return null
        val safe = method == "GET" || method == "HEAD"
        when (code) {
            300, 301, 302, 303 -> Unit
            307, 308 -> if (!safe) return null
            else -> return null
        }
        val next = runCatching { URL(from, location) }.getOrNull() ?: return null
        if (!next.protocol.equals(from.protocol, ignoreCase = true)) return null
        return Redirect(next, toGet = !safe)
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

/**
 * Whether a native fetch of [url] may end up at an onion host — judged
 * on every way the host can be read, and failing towards "onion" when in
 * doubt (an onion fetch without Tor is refused; with Tor, the Tor side
 * refuses anything that isn't really an onion name).
 *
 * `HttpURLConnection` (OkHttp inside) doesn't use [URL.getHost]: it
 * re-parses `url.toString()` with its own authority rules (a `\` ends
 * the authority), then percent-decodes the host and runs it through
 * IDNA, which maps `%2e`, `。`, `．`, `｡` to a dot and drops soft
 * hyphens and joiners. So both readings of the host are checked, each
 * canonicalized ([hostMayBeOnion]).
 */
internal fun fetchMayReachOnion(url: URL): Boolean =
    hostMayBeOnion(url.host) || hostMayBeOnion(okHttpHost(url.toString()))

/**
 * The host of [url] as OkHttp's `HttpUrl` reads the authority: after the
 * scheme's slashes (either kind), up to the first `/`, `\`, `?` or `#`,
 * after the last `@`, before a port colon outside brackets. Raw, not
 * decoded; `null` without an authority.
 */
internal fun okHttpHost(url: String): String? {
    val colon = url.indexOf(':').takeIf { it > 0 } ?: return null
    var i = colon + 1
    while (i < url.length && (url[i] == '/' || url[i] == '\\')) i++
    val end = url.indexOfAny(charArrayOf('/', '\\', '?', '#'), i).let { if (it < 0) url.length else it }
    val authority = url.substring(i, end)
    val hostPort = authority.substringAfterLast('@')
    if (hostPort.startsWith("[")) return hostPort.substringBefore(']') + "]"
    return hostPort.substringBefore(':').takeIf { it.isNotEmpty() }
}

/**
 * Whether [rawHost] (as written in a URL) names an onion host once
 * canonicalized as an HTTP stack would: percent-decoded, IDNA-mapped,
 * trailing dots dropped. A host that can't be canonicalized cleanly but
 * still reads as `….onion` once everything but letters, digits, dots and
 * hyphens is stripped counts too.
 */
internal fun hostMayBeOnion(rawHost: String?): Boolean {
    if (rawHost.isNullOrEmpty() || rawHost.startsWith("[")) return false
    fun onion(h: String) = isOnionHost(h.trimEnd('.'))
    if (onion(rawHost)) return true
    val plain = rawHost.all { it.code in 0x21..0x7e } && '%' !in rawHost
    if (plain) return false
    val decoded = percentDecodeUtf8(rawHost)
    if (onion(decoded)) return true
    val idna = runCatching { IDN.toASCII(decoded, IDN.ALLOW_UNASSIGNED) }.getOrNull()
    if (idna != null && onion(idna)) return true
    // Whatever mapping the stack applies, it can't turn a host that
    // doesn't read as an onion name here into one.
    val loose = Normalizer.normalize(decoded, Normalizer.Form.NFKC)
        .lowercase()
        .map { if (it == '\u3002' || it == '\uff0e' || it == '\uff61') '.' else it }
        .filter { it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '-' }
        .joinToString("")
    return loose.trimEnd('.').endsWith(".onion")
}

/** `%XX` escapes decoded as UTF-8 bytes; a malformed escape is kept as is. */
private fun percentDecodeUtf8(s: String): String {
    val out = java.io.ByteArrayOutputStream()
    val bytes = s.toByteArray(Charsets.UTF_8)
    var i = 0
    while (i < bytes.size) {
        val b = bytes[i]
        if (b == '%'.code.toByte() && i + 2 < bytes.size) {
            val hi = Character.digit(bytes[i + 1].toInt(), 16)
            val lo = Character.digit(bytes[i + 2].toInt(), 16)
            if (hi >= 0 && lo >= 0) {
                out.write(hi * 16 + lo)
                i += 3
                continue
            }
        }
        out.write(b.toInt())
        i++
    }
    return out.toString(Charsets.UTF_8.name())
}
