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
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.net.URLConnection
import java.text.Normalizer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * `.onion` routing (#143): only onion hosts go through Tor's SOCKS5
 * port — the embedded Arti client's, or an external Tor client's such as
 * Orbot (#275, Settings → Tor); everything else — clearnet, the local
 * node gateways, the dweb virtual origins — stays direct. And it fails
 * closed: with Tor off, stopped, still without a port, gone, or (external)
 * not confirmed as a Tor proxy by [TorProxy.probe], a `.onion` request is
 * refused, never resolved or connected directly.
 *
 * "Request" means the WebView's HTTP(S)/WebSocket stack and the app's own
 * native fetches. WebRTC is outside all three layers below: Chromium
 * resolves an `RTCPeerConnection`'s STUN/TURN server names itself, past
 * the proxy override and the interceptor, so a page that writes a
 * `.onion` name into its own `iceServers` sends that name to system DNS
 * (#203 R4-F1). Only the page's own chosen string leaks that way, nothing
 * of the user's browsing, and there is no WebView API to gate it.
 *
 * Three layers, all keyed on [isOnionHost]:
 *
 *  1. **The WebView's proxy override** ([ProxyController], reverse-bypass
 *     mode: the bypass list names the hosts that *use* the proxy). It is
 *     always in place from [init] on, pointing `*.onion` at the Tor port
 *     while Tor listens (external: while it's confirmed) and at
 *     [REFUSE_PORT] otherwise, so even a request
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
 * The embedded Tor side refuses anything but a `.onion` name too (see
 * freedom-mobile-ffi `src/tor.rs`), so a misrouted clearnet request can't
 * leave through a Tor exit either. An external client (Orbot) would carry
 * one out through an exit, but only onion hosts are ever sent to it.
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
    const val CODE_PROXY_DOWN = "tor_proxy_down"

    /**
     * An onion document refused while the external proxy's check is still
     * pending ([externalPending]) — the hold ran out, or [MAX_HELD] were
     * already waiting: "checking", not "no Tor client answers", and the
     * page tries again by itself ([CHECKING_REFRESH_S]) so it loads once
     * the proxy passes (#305 R2-F1).
     */
    const val CODE_PROXY_CHECKING = "tor_proxy_checking"

    /**
     * An onion document refused while the external proxy's verdict is
     * pending but nothing checks: the Activity is stopped ([externalIdle]),
     * and the check runs only once it's back. Not "still checking … loads
     * by itself", and no refresh — a background tab's page would otherwise
     * ask again every [HOLD_MS] + [CHECKING_REFRESH_S] for as long as
     * Freedom stays in the background (#305 R3-M1).
     */
    const val CODE_PROXY_PAUSED = "tor_proxy_paused"

    /** How soon the [CODE_PROXY_CHECKING] page asks again (a meta refresh, no script). */
    const val CHECKING_REFRESH_S = 5

    /** `null` until [init]; false when the WebView can't do reverse-bypass proxying. */
    @Volatile
    var supported: Boolean? = null
        private set

    /** Settings → Tor. */
    @Volatile
    private var enabled = false

    /** The embedded client's state (`:tor`); ignored in external mode. */
    @Volatile
    private var info = TorInfo()

    /** Settings → Tor → External SOCKS proxy, or `null` for the embedded client (#275). */
    @Volatile
    private var external: SocksEndpoint? = null

    /** Whether [TorProxy.probe] has confirmed [external] as a live Tor proxy. */
    @Volatile
    private var externalConfirmed = false

    /**
     * Whether [external]'s last check found a Tor client that couldn't
     * reach onion sites ([TorProxy.Watch.unreached]) — for the refusal
     * page's copy while it isn't routed (R3-F1).
     */
    @Volatile
    private var externalUnreached = false

    /**
     * Whether [external]'s verdict is pending: the Activity just came back
     * (or Tor just started) and the first check since hasn't answered, or
     * the Activity is stopped and nothing checks until it's back. Onion
     * requests meanwhile wait for that check ([awaitExternalVerdict])
     * rather than being refused at once — a link from another app, or a
     * form posted on return from an authenticator, arrives with the
     * Activity's start, before any check can have passed (#305 R1-F1).
     */
    @Volatile
    private var externalPending = false

    /**
     * Whether [externalPending]'s check isn't running: the Activity is
     * stopped, and nothing checks until it's back. Onion requests still
     * wait ([awaitExternalVerdict]) — the start may be on its way — but a
     * document refused meanwhile gets [CODE_PROXY_PAUSED], not
     * [CODE_PROXY_CHECKING] (#305 R3-M1).
     */
    @Volatile
    private var externalIdle = false

    /**
     * Open until [settingsLoaded]: Settings → Tor (on/off, the client) and
     * whether Tor starts at launch haven't been read yet. [refusalFor]
     * waits for it (bounded, [SETTINGS_WAIT_MS]), so an onion link that
     * cold-starts the app isn't judged by the defaults — "Tor is off" —
     * before the settings land and the external check starts (#305 R3-M2).
     */
    @Volatile
    private var settingsKnown = CountDownLatch(0)

    /** A bound on the [settingsKnown] wait: a DataStore read takes milliseconds. */
    private const val SETTINGS_WAIT_MS = 5_000L

    /** Notified whenever routing state changes, for [awaitExternalVerdict]. */
    private val verdictLock = Object()

    /** Requests held in [awaitExternalVerdict] right now, capped at [MAX_HELD]. */
    private val held = AtomicInteger(0)

    /**
     * How long an onion request waits for [externalPending]'s check: the
     * whole first check at its own deadlines ([TorProxy.CHECK_MAX_MS] —
     * the greeting and canary, then every probe onion, which a slow
     * circuit can take up to its full 45 s each to reach), plus a margin
     * for publishing the verdict and the WebView confirming the override.
     * A shorter hold refused a slow but working Tor mid-check (#305
     * R2-F1); past this the check is late, and the request gets the
     * [CODE_PROXY_CHECKING] page, which asks again by itself.
     */
    val HOLD_MS = TorProxy.CHECK_MAX_MS + 5_000L

    /**
     * At most this many onion requests wait at once; more are refused at
     * once, so a page firing onion requests in a loop (in the background,
     * where the wait lasts until [HOLD_MS]) can't tie up the WebView's
     * interceptor threads.
     */
    const val MAX_HELD = 8

    /**
     * The Tor SOCKS endpoint `*.onion` is routed to — set only once the
     * WebView has confirmed the override naming it — or `null` while onion
     * requests are refused. Cleared *before* an override moves away from
     * an endpoint.
     */
    @Volatile
    private var routed: SocksEndpoint? = null

    /** The endpoint the last applied (or pending) override names, or `null` for none yet; main thread. */
    private var target: SocksEndpoint? = null

    /** Bumped per override; a stale confirmation is ignored. Main thread. */
    private var generation = 0L

    /**
     * Whether the WebView has confirmed an override that doesn't name the
     * embedded client's port — [REFUSE_PORT], or an external proxy — and
     * none has been asked for since. Main thread.
     */
    private var refusing = false

    /** [afterRefusing]'s actions waiting for that confirmation. Main thread. */
    private val whenRefusing = mutableListOf<() -> Unit>()

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

    /**
     * The Tor settings are being read (see [settingsKnown]); until
     * [settingsLoaded], [refusalFor] waits for them. Main thread.
     */
    fun expectSettings() {
        if (settingsKnown.count == 0L) settingsKnown = CountDownLatch(1)
    }

    /**
     * The Tor settings are applied ([setEnabled], [setExternal], and Tor
     * started if it starts at launch). Any thread.
     */
    fun settingsLoaded() = settingsKnown.countDown()

    internal fun awaitSettings(timeoutMs: Long = SETTINGS_WAIT_MS) {
        val known = settingsKnown
        if (known.count == 0L) return
        try {
            known.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** Settings → Tor switched. Main thread. */
    fun setEnabled(context: Context, on: Boolean) {
        enabled = on
        apply(context)
        signalVerdict()
    }

    /** The `:tor` service's latest state (or Stopped once it's gone). Main thread. */
    fun onState(context: Context, state: TorInfo) {
        info = state
        apply(context)
        signalVerdict()
    }

    /**
     * Settings → Tor's client (#275): [proxy] for an external SOCKS proxy,
     * `null` for the embedded one; [confirmed] once [TorProxy.probe] found
     * a Tor client there (and while it still listens); [unreached] when
     * its last check found Tor there that couldn't reach onion sites
     * ([TorProxy.Watch.unreached]); [pending] while its verdict is
     * awaited ([externalPending]): onion requests wait for it; [idle] when
     * that verdict waits for the Activity to start again ([externalIdle]).
     * Main thread.
     */
    fun setExternal(
        context: Context,
        proxy: SocksEndpoint?,
        confirmed: Boolean,
        unreached: Boolean = false,
        pending: Boolean = false,
        idle: Boolean = false,
    ) {
        external = proxy
        externalConfirmed = proxy != null && confirmed
        externalUnreached = proxy != null && unreached
        externalPending = proxy != null && !confirmed && pending
        externalIdle = externalPending && idle
        apply(context)
        signalVerdict()
    }

    private fun signalVerdict() = synchronized(verdictLock) { verdictLock.notifyAll() }

    /**
     * Whether an onion request should wait rather than be refused now: an
     * external proxy whose check is pending ([externalPending]), or one
     * confirmed whose override the WebView hasn't confirmed yet.
     */
    private fun awaitingExternal(): Boolean =
        routed == null && enabled && supported == true && external != null &&
            (externalPending || externalConfirmed)

    /**
     * Wait, up to [timeoutMs], while [awaitingExternal]; whether onion is
     * routed at the end. At most [MAX_HELD] callers wait at once; more get
     * the answer as it stands. Not on the main thread (the verdict is
     * published there). #305 R1-F1.
     */
    internal fun awaitExternalVerdict(timeoutMs: Long = HOLD_MS): Boolean {
        if (!awaitingExternal()) return routed != null
        if (held.incrementAndGet() > MAX_HELD) {
            held.decrementAndGet()
            return routed != null
        }
        try {
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            synchronized(verdictLock) {
                while (awaitingExternal()) {
                    val left = (deadline - System.nanoTime()) / 1_000_000
                    if (left <= 0) break
                    verdictLock.wait(left)
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            held.decrementAndGet()
        }
        return routed != null
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
        external = null
        externalConfirmed = false
        externalUnreached = false
        externalPending = false
        externalIdle = false
        settingsKnown = CountDownLatch(0)
        held.set(0)
        routed = null
        target = null
        refusing = false
        whenRefusing.clear()
    }

    /**
     * Run [action] once `*.onion` no longer points at the embedded Tor
     * port in the WebView either — an override naming [REFUSE_PORT] or an
     * external proxy confirmed — or at once if it already is, or if
     * there's no override to move. For
     * letting the Tor client go (R2-F1): its port must not be freed while
     * the WebView may still send onion hostnames to it, or another app
     * binding that loopback port in the gap would receive them. An
     * override that never confirms never runs it; the caller bounds the
     * wait. Main thread.
     */
    fun afterRefusing(action: () -> Unit) {
        if (supported != true || refusing) action() else whenRefusing += action
    }

    /** The endpoint a native onion fetch may use, or `null`. */
    val routedEndpoint: SocksEndpoint? get() = routed

    /** Whether onion requests are routed to Tor right now. */
    val isRouted: Boolean get() = routed != null

    /** Whether onion requests are routed to an external proxy (#275) right now. */
    val isRoutedExternal: Boolean get() = routed != null && external != null

    /**
     * Told when a routed external proxy may have gone ([externalFailed]) or
     * an onion page got the *Tor proxy* refusal ([refusedDocument]) — both
     * page-driven, so the listener doesn't let them cut a back-off short
     * ([TorProxy.afterNudge]).
     */
    private val onExternalFailure = AtomicReference<(() -> Unit)?>(null)

    /** Listen for [externalFailed] (MainActivity, in `onCreate`), replacing any earlier listener. */
    fun setOnExternalFailure(listener: () -> Unit) = onExternalFailure.set(listener)

    /** Stop listening — only if [listener] is still the one set, so a newer Activity's isn't dropped. */
    fun clearOnExternalFailure(listener: () -> Unit) {
        onExternalFailure.compareAndSet(listener, null)
    }

    /**
     * An onion page failed to load while routed to the external proxy
     * (R1-M1): it may have stopped between re-checks. The listener checks
     * it at once rather than at the next scheduled re-check, so the next
     * try gets the *Tor proxy isn't reachable* page if it's gone.
     */
    fun externalFailed() {
        if (!isRoutedExternal) return
        Log.i(TAG, "onion load failed through socks5://$routed → re-checking it")
        onExternalFailure.get()?.invoke()
    }

    /**
     * An onion document got refusal page [code]; for the *Tor proxy* one
     * ([CODE_PROXY_DOWN], R3-F1) in a top-level document ([mainFrame]):
     * check the external proxy sooner than the next scheduled check, so
     * trying again soon can find it routed. Only the main frame — an
     * `<iframe>` any page can add in a loop is no sign the user is
     * waiting for Tor (R4-M2) — and the listener honours it only while
     * it isn't backing off, no sooner than [TorProxy.RETRY_MS] after the
     * last check, and without starting the back-off over
     * ([TorProxy.afterNudge]). Any thread (the interceptor's).
     */
    internal fun refusedDocument(code: String, mainFrame: Boolean) {
        if (code == CODE_PROXY_DOWN && mainFrame) onExternalFailure.get()?.invoke()
    }

    private fun apply(context: Context) {
        if (supported != true) return
        val desired = desiredEndpoint(supported == true, enabled, info, external, externalConfirmed)
        // Stop routing to the old endpoint before anything else: Tor is
        // stopping, moved, or the client was switched.
        if (desired != routed) routed = null
        val next = desired ?: REFUSE
        if (next == target) return
        val gen = ++generation
        refusing = false
        // Leaving the embedded port whether we refuse or go external.
        val leavesEmbedded = desired == null || external != null
        val config = proxyConfigFor(next)
        runCatching {
            // Recorded before the call, so a confirmation arriving inside
            // it is matched; reset below if the call throws, so the next
            // apply() with the same target retries.
            target = next
            setOverride(config, context) {
                if (gen != generation) return@setOverride
                if (desired != null) {
                    routed = desired
                    signalVerdict()
                    Log.i(TAG, ".onion → socks5://$desired")
                } else {
                    Log.i(TAG, ".onion refused (socks5://$REFUSE)")
                }
                if (leavesEmbedded) {
                    refusing = true
                    val waiting = whenRefusing.toList()
                    whenRefusing.clear()
                    waiting.forEach { it() }
                }
            }
        }.onFailure {
            // Keep refusing (routed stays null), and forget the target so
            // the next state update tries again.
            target = null
            Log.w(TAG, "setProxyOverride failed", it)
        }
    }

    /**
     * Onion requests while nothing is [routed]: a document gets the error
     * page in place (kept out of history by [NAME_RESOLUTION_ERROR_HEADER],
     * as a refused ENS document is), a subresource an empty 502. `null`
     * for a non-onion request, or while Tor is routed. While an external
     * proxy's check is pending it first waits for that check's verdict
     * ([awaitExternalVerdict], #305 R1-F1); a document refused with that
     * check still pending gets the [CODE_PROXY_CHECKING] page, which asks
     * again by itself (R2-F1). The interceptor's thread.
     */
    fun refusalFor(req: WebResourceRequest): WebResourceResponse? {
        val uri = req.url ?: return null
        if (!isOnionHost(uri.host)) return null
        awaitSettings()
        if (awaitExternalVerdict()) return null
        val headers = mapOf("Cache-Control" to "no-store")
        if (!isDocumentRequest(req.isForMainFrame, req.requestHeaders)) {
            return WebResourceResponse(
                "text/plain", "utf-8", 502, "Tor Not Running", headers, ByteArrayInputStream(ByteArray(0)),
            )
        }
        val proxy = external
        val code = documentRefusalCode()
        refusedDocument(code, req.isForMainFrame)
        return WebResourceResponse(
            "text/html", "utf-8", 503, "Tor Not Running",
            headers + (NAME_RESOLUTION_ERROR_HEADER to code),
            ByteArrayInputStream(
                refusalHtml(uri.host.orEmpty(), code, info, proxy, externalUnreached).toByteArray(Charsets.UTF_8),
            ),
        )
    }

    /**
     * The refusal page for an onion document refused now: while the
     * external proxy's check is still pending (the hold ran out, or
     * [MAX_HELD] were already held) [CODE_PROXY_CHECKING] — nothing has
     * said no Tor client answers yet (#305 R2-F1) — or, while nothing
     * checks because the Activity is stopped, [CODE_PROXY_PAUSED] (R3-M1);
     * else [refusalCode]'s.
     */
    internal fun documentRefusalCode(): String = when {
        !awaitingExternal() -> refusalCode(supported, enabled, external != null)
        externalIdle -> CODE_PROXY_PAUSED
        else -> CODE_PROXY_CHECKING
    }

    /**
     * Open [url] for a native fetch: a `.onion` URL through the routed Tor
     * endpoint with an explicit SOCKS proxy (the hostname goes to Tor; nothing
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
        val endpoint = routed ?: throw RefusedException()
        // By address (a literal, no lookup), where the listener binds:
        // Android's InetAddress.getLoopbackAddress() is ::1.
        return url.openConnection(Proxy(Proxy.Type.SOCKS, InetSocketAddress(endpoint.address, endpoint.port)))
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

    /** Where `*.onion` points while refused: [REFUSE_PORT] on 127.0.0.1. */
    internal val REFUSE = SocksEndpoint("127.0.0.1", REFUSE_PORT)

    /**
     * The Tor endpoint to route `*.onion` to, or `null` to refuse: only
     * with the WebView able to scope the proxy to onion hosts and Tor on
     * in Settings; then the external proxy once [TorProxy.probe] confirmed
     * it ([externalConfirmed]), or — with no external proxy set — the
     * embedded client's port while it listens (starting or running).
     */
    internal fun desiredEndpoint(
        supported: Boolean,
        enabled: Boolean,
        info: TorInfo,
        external: SocksEndpoint?,
        externalConfirmed: Boolean,
    ): SocksEndpoint? = when {
        !supported || !enabled -> null
        external != null -> external.takeIf { externalConfirmed }
        info.socksPort in 1..65535 &&
            (info.status == TorStatus.Starting || info.status == TorStatus.Running) ->
            SocksEndpoint("127.0.0.1", info.socksPort)
        else -> null
    }

    /**
     * Reverse bypass: the proxy applies *only* to the listed hosts. Both
     * spellings of an onion name, since Chromium keeps a trailing dot in
     * the host and the pattern is matched as written. SOCKS5 only (no
     * SOCKS4 fallback, which can't carry a hostname).
     */
    internal fun proxyConfigFor(endpoint: SocksEndpoint): ProxyConfig =
        ProxyConfig.Builder()
            .addProxyRule("socks5://${endpoint.authority}")
            .addBypassRule("*.onion")
            .addBypassRule("*.onion.")
            .setReverseBypassEnabled(true)
            .build()

    internal fun refusalCode(supported: Boolean?, enabled: Boolean, external: Boolean = false): String = when {
        supported == false -> CODE_UNSUPPORTED
        !enabled -> CODE_OFF
        external -> CODE_PROXY_DOWN
        else -> CODE_NOT_RUNNING
    }

    /**
     * Title and description (HTML) of the refusal page for [code]; [proxy]
     * is the external SOCKS proxy for [CODE_PROXY_DOWN], and [unreached]
     * whether its last check found Tor there that couldn't reach onion
     * sites (so not "no Tor client answers", R3-F1).
     */
    internal fun refusalCopy(
        code: String,
        info: TorInfo,
        proxy: SocksEndpoint? = null,
        unreached: Boolean = false,
    ): Pair<String, String> = when (code) {
        CODE_UNSUPPORTED -> "Tor needs a newer WebView" to
            "This is an onion site, reachable only over Tor. This device's Android System " +
            "WebView can't send just <code>.onion</code> sites through Tor, so Freedom doesn't " +
            "open them. Update Android System WebView, then try again."
        CODE_PROXY_CHECKING -> "Checking the Tor proxy" to
            "This is an onion site, reachable only over Tor. Freedom is still checking the Tor " +
            "proxy at <code>${proxy ?: "(not set)"}</code> (Settings &rarr; Tor); this page loads " +
            "by itself once it passes. Freedom never opens onion sites without Tor."
        CODE_PROXY_PAUSED -> "Tor proxy not checked yet" to
            "This is an onion site, reachable only over Tor. Freedom checks the Tor proxy at " +
            "<code>${proxy ?: "(not set)"}</code> (Settings &rarr; Tor) only while Freedom is on " +
            "screen, and hasn't since it went to the background. Try again once you're back in " +
            "Freedom. Freedom never opens onion sites without Tor."
        CODE_PROXY_DOWN -> if (unreached) {
            "Tor can't reach onion sites" to
                "This is an onion site, reachable only over Tor. The Tor client at " +
                "<code>${proxy ?: "(not set)"}</code> (Settings &rarr; Tor) answers, but couldn't " +
                "reach an onion site when Freedom last checked: its connection may be down or slow. " +
                "Freedom is checking again; try again in a moment. Freedom never opens onion sites " +
                "without Tor."
        } else "Tor proxy isn't reachable" to
            "This is an onion site, reachable only over Tor. Freedom sends onion sites to the Tor " +
            "proxy at <code>${proxy ?: "(not set)"}</code> (Settings &rarr; Tor), and no Tor client " +
            "answers there right now. Start Orbot (or your Tor app), and Tor on the Nodes page, then " +
            "try again. Freedom never opens onion sites without Tor."
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

    internal fun refusalHtml(
        host: String,
        code: String,
        info: TorInfo,
        proxy: SocksEndpoint? = null,
        unreached: Boolean = false,
    ): String {
        val (title, description) = refusalCopy(code, info, proxy, unreached)
        fun esc(t: String) = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val error = info.errorMessage?.takeIf { code == CODE_NOT_RUNNING && info.status == TorStatus.Error }
        val details = esc(host) + "\n\n" + code + (error?.let { "\n" + esc(it) } ?: "")
        return inPlaceErrorPageHtml(
            title,
            description,
            details,
            refreshSeconds = CHECKING_REFRESH_S.takeIf { code == CODE_PROXY_CHECKING },
        )
    }
}

/**
 * Whether an onion page that failed with Tor down can be sent to the
 * refusal page by `reload()`. Not one reached by a form POST (or any
 * method but GET/HEAD): its reload asks `onFormResubmission`, answered
 * "don't resend", and nothing loads (R2-F2). That one is loaded again as
 * a GET instead — the same address, which the interceptor answers with
 * the refusal page, and no form side effect repeated.
 */
internal fun onionRefusalByReload(method: String?): Boolean =
    method == null || method.equals("GET", ignoreCase = true) || method.equals("HEAD", ignoreCase = true)

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
