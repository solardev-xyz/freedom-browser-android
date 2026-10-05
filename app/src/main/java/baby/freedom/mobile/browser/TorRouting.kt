package baby.freedom.mobile.browser

import android.content.Context
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import baby.freedom.mobile.R
import baby.freedom.mobile.l10n.Strings
import baby.freedom.swarm.TorInfo
import baby.freedom.swarm.TorStatus
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.IDN
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.Socket
import java.net.URI
import java.net.URL
import java.net.URLConnection
import java.text.Normalizer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory

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
     * Whether Tor runs with [external] as its client — started from the
     * Nodes page or at launch, and not stopped since. While it doesn't,
     * nothing checks the proxy, so a refused onion document hears "Tor
     * isn't running" ([CODE_NOT_RUNNING]), as with the embedded client,
     * not "no Tor client answers" ([CODE_PROXY_DOWN]) about a proxy no one
     * asked (#305 R1-M1).
     */
    @Volatile
    private var externalRunning = false

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
     * Native fetches held in [awaitOnionRoute] right now, capped at
     * [MAX_NATIVE_HELD] — counted apart from [held], so manifest discovery
     * waiting on a re-check can't take the slots a page's onion document
     * is held in (#376 R2-F2).
     */
    private val nativeHeld = AtomicInteger(0)

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
     * At most this many native onion fetches ([awaitOnionRoute]: manifest
     * discovery) wait at once, in their own pool apart from [MAX_HELD];
     * more get the answer as it stands — a refusal mid-check, which
     * discovery reads as unresolved, as before #376 R1-F1.
     */
    const val MAX_NATIVE_HELD = 4

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
     * that verdict waits for the Activity to start again ([externalIdle]);
     * [running] while Tor runs with it as its client ([externalRunning]),
     * false once stopped (or before it's started). Main thread.
     */
    fun setExternal(
        context: Context,
        proxy: SocksEndpoint?,
        confirmed: Boolean,
        unreached: Boolean = false,
        pending: Boolean = false,
        idle: Boolean = false,
        running: Boolean = false,
    ) {
        external = proxy
        externalRunning = proxy != null && running
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
    internal fun awaitExternalVerdict(timeoutMs: Long = HOLD_MS): Boolean =
        awaitExternalVerdict(timeoutMs, held, MAX_HELD)

    /** [awaitExternalVerdict], counting the wait in [slots], at most [max] at once. */
    private fun awaitExternalVerdict(timeoutMs: Long, slots: AtomicInteger, max: Int): Boolean {
        if (!awaitingExternal()) return routed != null
        if (slots.incrementAndGet() > max) {
            slots.decrementAndGet()
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
            slots.decrementAndGet()
        }
        return routed != null
    }

    /**
     * For a native onion fetch with its own deadline (manifest discovery,
     * #356): wait, within [timeoutMs] in all, for the Tor settings
     * ([awaitSettings]) and then for a pending external proxy's verdict
     * ([awaitExternalVerdict]), the way [refusalFor] holds a page's onion
     * request — so a fetch arriving while the proxy is re-checked (the
     * app just came back) is held rather than refused at once. Whether
     * onion is routed at the end. Not on the main thread.
     *
     * These waits count against their own [MAX_NATIVE_HELD], not the
     * interceptor's [MAX_HELD]: several onion-endpoint origins running
     * discovery during a re-check must not fill the slots a page's onion
     * document needs and get it the [CODE_PROXY_CHECKING] page early
     * (#376 R2-F2).
     */
    internal fun awaitOnionRoute(timeoutMs: Long): Boolean {
        if (timeoutMs <= 0) return routed != null
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        awaitSettings(minOf(timeoutMs, SETTINGS_WAIT_MS))
        val left = (deadline - System.nanoTime()) / 1_000_000
        return if (left <= 0) routed != null else awaitExternalVerdict(minOf(left, HOLD_MS), nativeHeld, MAX_NATIVE_HELD)
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
        externalRunning = false
        settingsKnown = CountDownLatch(0)
        held.set(0)
        nativeHeld.set(0)
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
        val code = documentRefusalCode()
        refusedDocument(code, req.isForMainFrame)
        return WebResourceResponse(
            "text/html", "utf-8", 503, "Tor Not Running",
            headers + (NAME_RESOLUTION_ERROR_HEADER to code),
            ByteArrayInputStream(documentRefusalHtml(uri.host.orEmpty(), code).toByteArray(Charsets.UTF_8)),
        )
    }

    /**
     * The refusal page for [host] with [code]: in external mode without the
     * embedded client's state, which says nothing about the external one
     * (a stale "Tor couldn't start" from Arti, #305 R1-M1).
     */
    internal fun documentRefusalHtml(host: String, code: String = documentRefusalCode()): String {
        val proxy = external
        return refusalHtml(host, code, if (proxy != null) TorInfo() else info, proxy, externalUnreached)
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
        !awaitingExternal() -> refusalCode(supported, enabled, external != null, externalRunning)
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
    fun openConnection(url: URL): URLConnection = openConnection(url, null)

    /**
     * [openConnection], through [route] (the system proxy selector's
     * choice when `null`) for a non-onion URL.
     */
    private fun openConnection(url: URL, route: Proxy?): URLConnection {
        if (!fetchMayReachOnion(url)) return if (route == null) url.openConnection() else url.openConnection(route)
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
     *
     * A hop onto this device is refused ([hopRefused], with a
     * [RedirectRefusedException]) before it's opened: the caller asked
     * for [url], and a server it names mustn't turn the fetch into one of
     * the node's API or another loopback service. The gateway
     * interceptor serves what it fetches here to the page, readable
     * (`Access-Control-Allow-Origin: *`), so an external gateway's `302
     * Location: http://127.0.0.1:1633/addresses` would otherwise hand a
     * page the node's addresses and wallet — past [NodeApiGuard], which
     * sees only the page's own request, and past the gateway's empty CORS
     * list, since the read happens on the page's own origin. A hop whose
     * name can't be looked up isn't followed either, but it's thrown as a
     * [RedirectUnresolvedException] — an [java.net.UnknownHostException],
     * worth retrying like any other failed lookup — not as a refusal.
     *
     * [hops], when given, is told when each hop's connection starts
     * ([HopWatcher.connecting], before every explicit `connect()`, which
     * takes in the TLS handshake and a SOCKS proxy's reply), when it is up
     * ([HopWatcher.connected]) and when its response headers are in
     * ([HopWatcher.answered]) — the stretches a header deadline should
     * time, without name lookups: those between hops, and the one the
     * connection itself would run inside `connect()`, which is done ahead
     * of [HopWatcher.connecting] ([lookUpAhead]) so the connect finds the
     * name cached. An https hop's every route then gets
     * [HopWatcher.handshakeTimeoutMs] for its TLS handshake
     * ([HandshakeTimeoutFactory]).
     */
    fun openFollowingRedirects(
        url: URL,
        body: ByteArray? = null,
        hops: HopWatcher? = null,
        configure: HttpURLConnection.(hop: URL) -> Unit,
    ): HttpURLConnection {
        var current = url
        var method: String? = null
        var payload = body
        repeat(MAX_REDIRECTS + 1) {
            // A hop off the caller's origin goes one fixed way (R4-F1):
            // through the system proxy, if one is set, and nothing else —
            // Android's HttpURLConnection otherwise retries a failed proxy
            // route directly — or straight from here, where it dials the
            // address it was judged on (pinned), not whatever a fresh
            // lookup answers a moment later, or, on https, has its
            // connected peer checked. An onion hop goes to Tor's proxy.
            // A hop to another localhost name on the gateway's own port
            // ([sameLoopbackServer]) is dialed on this device, never through
            // a network lookup of the name (#359 R2-F1).
            val local = !sameOrigin(url, current) && sameLoopbackServer(url, current)
            val route = when {
                local -> Proxy.NO_PROXY
                sameOrigin(url, current) || sameLoopbackServer(url, current) -> null
                else -> hopRoute(current)
            }
            val direct = !local && route?.type() == Proxy.Type.DIRECT
            val pinned = if (local) pinLocalhost(current) else if (direct) pin(current) else null
            // A watched hop on the system proxy has each of the selector's
            // routes dialed as a connect of its own (PR #409 R1-M1).
            val proxied = if (pinned == null && route == null && hops != null) proxyRoutes(current) else null
            val candidates: List<Pair<URL, Proxy?>> = pinned?.urls?.map { it to route }
                ?: proxied?.map { current to it }
                ?: listOf(current to route)
            var conn: HttpURLConnection? = null
            // The route [conn] goes by: the one a proxied hop dialed last.
            var connRoute: Proxy? = route
            var dialed = false // connected as a fallback-able route already
            var keep = false
            try {
                for ((i, pair) in candidates.withIndex()) {
                    val (candidate, candidateRoute) = pair
                    val c = openConnection(candidate, candidateRoute) as? HttpURLConnection
                        ?: throw IOException(Strings.get(R.string.node_fetch_not_http, current))
                    conn = c
                    connRoute = candidateRoute
                    c.configure(current)
                    pinned?.hostHeader?.let { c.setRequestProperty("Host", it) }
                    if (direct && pinned == null && c is HttpsURLConnection) {
                        c.sslSocketFactory = DevicePeerRefusingFactory(c.sslSocketFactory, current)
                    }
                    if (local && pinned == null && c is HttpsURLConnection) {
                        c.sslSocketFactory = DevicePeerRefusingFactory(c.sslSocketFactory, current, requireDevice = true)
                    }
                    hops?.handshakeTimeoutMs?.let { ms ->
                        // Each route's TLS handshake gets its own limit, so
                        // one that stalls fails there and the connection
                        // tries the next, as under main's read timeout
                        // (PR #409 R6-M1).
                        if (c is HttpsURLConnection) c.sslSocketFactory = HandshakeTimeoutFactory(c.sslSocketFactory, ms)
                    }
                    method?.let { c.requestMethod = it }
                    c.instanceFollowRedirects = false
                    if (payload != null) c.doOutput = true
                    if (i == candidates.lastIndex) break
                    // Not the last address the name resolved to (or the
                    // last of the system proxy's routes): one that can't be
                    // reached falls back to the next, as the connection's
                    // own lookup (or route selection) would have (R4-F2).
                    // A watchdog cut here ends this route only, not the
                    // attempt (PR #409 R1-M1).
                    hops?.connecting(c, if (proxied == null || candidateRoute == null) 1 else lookUpAhead(current, candidateRoute), fallback = true)
                    try {
                        c.connect()
                        if (proxied != null) proxyRouteConnected(current, candidateRoute)
                        dialed = true
                        break
                    } catch (e: IOException) {
                        // A proxy route falls through to the next only on
                        // what okhttp's route selection would (PR #409
                        // R2-M1): a certificate refused through the proxy
                        // fails the fetch, as it did on main.
                        if (proxied != null && !routeFailureRecoverable(e)) throw e
                        Log.w(TAG, "${if (proxied != null) "proxy route $candidateRoute" else "pinned address ${candidate.host}"} unreachable, trying the next: $e")
                        if (proxied != null) proxyRouteFailed(current, candidateRoute)
                        runCatching { c.disconnect() }
                        conn = null
                    }
                }
                val c = conn ?: throw IOException(Strings.get(R.string.node_fetch_not_http, current))
                if (hops != null) {
                    // The connection's own lookup of the name runs inside
                    // its connect(), so look it up first, off the clock: the
                    // connect then finds it cached and the stretch the
                    // watcher times is the connect and handshake alone, with
                    // lookup time on top, as on main (PR #409 R4-M1).
                    // How many routes the connect may try in turn, each
                    // under its own connect and handshake timeouts, as on
                    // main (PR #409 R5-M1, R6-M1); a pinned hop dials one.
                    if (!dialed) {
                        val routes = if (pinned == null) lookUpAhead(current, connRoute) else 1
                        hops.connecting(c, routes)
                        c.connect()
                        if (proxied != null) proxyRouteConnected(current, connRoute)
                    }
                    hops.connected(c)
                }
                payload?.let { bytes -> c.outputStream.use { it.write(bytes) } }
                val code = c.responseCode
                hops?.answered(c)
                val next = redirectFor(current, code, c.requestMethod, c.getHeaderField("Location"))
                if (next == null) {
                    keep = true
                    return c
                }
                val nextMethod = if (next.toGet) "GET" else c.requestMethod
                val refused = try {
                    hopRefused(url, next.url, nextMethod)
                } catch (e: RedirectUnresolvedException) {
                    Log.w(TAG, "not following a redirect whose host didn't resolve: $nextMethod ${next.url.protocol}://${next.url.authority}")
                    throw e
                }
                if (refused) {
                    Log.w(TAG, "refused a redirect onto this device: $nextMethod ${next.url.protocol}://${next.url.authority}")
                    throw RedirectRefusedException(next.url)
                }
                current = next.url
                if (next.toGet) {
                    method = "GET"
                    payload = null
                }
            } finally {
                if (!keep) runCatching { conn?.disconnect() }
            }
        }
        throw IOException(Strings.get(R.string.node_fetch_too_many_redirects))
    }

    /**
     * Look up the name [hop]'s connection would look up itself inside its
     * `connect()`, so that a [HopWatcher] timing the connect doesn't time
     * a slow resolver too: the connection, right after, finds the answer in
     * the system's cache (libcore's and netd's), as it would any name it had
     * just resolved. Only for a hop dialed straight from here — through
     * [route], or the system proxy selector's choice when `null` — since
     * through a proxy, or to Tor, the name is the proxy's to resolve and
     * mustn't be looked up on this device. Nothing for an IP literal. A
     * failed lookup is left for the connection to meet and report as it
     * always did.
     *
     * The name looked up is the one the connection will look up — OkHttp's
     * canonical host, a trailing dot and all, since the system's caches are
     * keyed by that exact string (PR #409 R5-M2).
     *
     * Best effort only: libcore keeps a positive answer for 2 s, so a
     * connect that starts later than that (or a lookup through a test
     * [resolve] that caches nothing) looks the name up again inside the
     * connect clock — the `main`-like behaviour this exists to avoid, but
     * no worse than it was before it, and the clock's limit still holds.
     *
     * Returns how many routes the connection may try in turn — each under
     * its own connect and handshake timeouts, so the connect clock scales
     * with it (PR #409 R5-M1, R6-M1): [connectRoutes] of the selector's
     * choice; one for an explicit [route] proxy (Tor's, or a cross-origin
     * hop's system proxy), which has no direct fallback; and 1 when the
     * selector fails or the lookup does.
     */
    private fun lookUpAhead(hop: URL, route: Proxy?): Int {
        if (fetchMayReachOnion(hop)) return 1
        if (route != null) return if (route.type() == Proxy.Type.DIRECT) lookUpDirect(hop) else 1
        val uri = selectorUri(hop) ?: return 1
        val selected = runCatching { proxiesFor(uri) }.getOrNull() ?: return 1
        return connectRoutes(selected) { lookUpDirect(hop) }
    }

    /**
     * The routes Android's HttpURLConnection would try in turn for [hop]
     * on the system proxy selector's choice — each proxy the selector
     * names, then "only once" a direct connection (okhttp's
     * `RouteSelector`) — as explicit routes, for [openFollowingRedirects]
     * to dial one by one, each a connect of its own; `null` when the
     * selector names no proxy (the connection dials the name directly, its
     * addresses one by one: [lookUpAhead]), for an onion hop (Tor's), or
     * when the selector can't be asked (left to the connection, as ever).
     *
     * Why not let the connection walk them itself (PR #409 R1-M1): an HTTP
     * proxy's CONNECT reply is read under the connection's read timeout —
     * the long body stall limit here — not each route's handshake limit
     * ([HandshakeTimeoutFactory] only sees the socket after the tunnel is
     * up), so a proxy that accepts TCP and never answers CONNECT left only
     * the watchdog's cut, which ended the whole connect, the direct
     * fallback with it. `main` gave that reply 10 s and then went direct.
     * Dialed one by one, a stalled proxy route is cut at its own share of
     * the clock and the next route still gets dialed. A route that failed
     * lately ([failedRoutes]) is dialed last for a while, so the stall is
     * paid once per [ROUTE_POSTPONE_MS], not by every later request, and
     * the proxy is dialed first again after it (PR #409 R2-F1, R3-F1);
     * only a failure okhttp would recover from falls through to the next
     * route ([routeFailureRecoverable], R2-M1).
     */
    private fun proxyRoutes(hop: URL): List<Proxy>? {
        if (fetchMayReachOnion(hop)) return null
        val uri = selectorUri(hop) ?: return null
        val selected = runCatching { proxiesFor(uri) }.getOrNull() ?: return null
        val proxies = selected.filter { it.type() != Proxy.Type.DIRECT }
        if (proxies.isEmpty()) return null
        // Routes that failed lately go last, for a while (PR #409 R2-F1,
        // R3-F1).
        val (fresh, failed) = (proxies + Proxy.NO_PROXY).partition { !routePostponed(hop, it) }
        return fresh + failed
    }

    /**
     * Proxy routes ([proxyRoutes]) that failed to connect, by hop and
     * route, process-wide, with when each last failed ([routeClock]). A
     * route here is dialed after the others — but only for
     * [ROUTE_POSTPONE_MS] after its failure, then first again, as
     * the selector ordered it (PR #409 R3-F1). Android builds a fresh
     * okhttp client (and `RouteDatabase`) for every `openConnection`, so
     * on `main` every new connection dialed the system proxy first again;
     * only a request answered on a pooled direct connection skipped it.
     * Postponing a failed route for good would send every later fetch to
     * that host around the user's proxy for the rest of the process after
     * one blip. Postponing it for a while keeps what R2-F1 was after — a
     * stalled proxy is paid once per window, not by every subresource
     * (≥ 16 s each on a Chromium pool thread), and the requests in between
     * reuse the pooled direct connection (its explicit `NO_PROXY` route is
     * one `Address` across requests) — and a proxy that recovered gets
     * its traffic back within [ROUTE_POSTPONE_MS]. A route that connects
     * is forgotten at once. Bounded: cleared when it reaches
     * [MAX_FAILED_ROUTES].
     */
    private val failedRoutes = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private const val MAX_FAILED_ROUTES = 256

    /** How long a failed proxy route ([failedRoutes]) is dialed last. */
    internal const val ROUTE_POSTPONE_MS = 30_000L

    /** Monotonic milliseconds for [failedRoutes]; tests swap it. */
    internal var routeClock: () -> Long = { System.nanoTime() / 1_000_000 }

    /**
     * A route's key: the hop's scheme, host and port, and the proxy's type
     * and address as configured — its host string and port, never
     * [Proxy.toString], whose address part reads differently once the
     * proxy's name was resolved (or failed to be), so a failed entry would
     * be missed (PR #409 R3-M1).
     */
    internal fun routeKey(hop: URL, route: Proxy?): String {
        val via = when {
            route == null -> "selector"
            route.type() == Proxy.Type.DIRECT -> "direct"
            else -> when (val a = route.address()) {
                is InetSocketAddress -> "${route.type()} ${a.hostString.lowercase(java.util.Locale.US)}:${a.port}"
                else -> "${route.type()} $a"
            }
        }
        return "${hop.protocol}://${hop.host.lowercase(java.util.Locale.US)}:${if (hop.port == -1) hop.defaultPort else hop.port} via $via"
    }

    /** Whether [route] to [hop] failed within the last [ROUTE_POSTPONE_MS]. */
    private fun routePostponed(hop: URL, route: Proxy?): Boolean {
        val key = routeKey(hop, route)
        val at = failedRoutes[key] ?: return false
        if (routeClock() - at in 0 until ROUTE_POSTPONE_MS) return true
        failedRoutes.remove(key, at)
        return false
    }

    private fun proxyRouteFailed(hop: URL, route: Proxy?) {
        if (failedRoutes.size >= MAX_FAILED_ROUTES) failedRoutes.clear()
        failedRoutes[routeKey(hop, route)] = routeClock()
    }

    private fun proxyRouteConnected(hop: URL, route: Proxy?) {
        failedRoutes.remove(routeKey(hop, route))
    }

    /** Forget every failed proxy route ([failedRoutes]); tests. */
    internal fun forgetFailedRoutes() = failedRoutes.clear()

    /**
     * Whether a failed connect is one okhttp's route selection moves on to
     * the next route after (`StreamAllocation.isRecoverable`), so dialing
     * the system proxy's routes one by one falls through exactly as the
     * connection's own walk did on `main` (PR #409 R2-M1): not a protocol
     * error, an interruption other than a timeout, a handshake refused
     * over its certificate, or an unverified peer.
     */
    internal fun routeFailureRecoverable(e: IOException): Boolean = when {
        e is java.net.ProtocolException -> false
        e is java.io.InterruptedIOException -> e is java.net.SocketTimeoutException
        e is javax.net.ssl.SSLHandshakeException && e.cause is java.security.cert.CertificateException -> false
        e is javax.net.ssl.SSLPeerUnverifiedException -> false
        else -> true
    }

    /**
     * How many routes Android's HttpURLConnection tries for a hop whose
     * proxy is the system selector's choice ([selected]): okhttp's
     * `RouteSelector` tries each proxy the selector named, then — "only
     * once" — a direct connection, to every address the name resolves to.
     * All-direct (or nothing named): just those [directAddresses]. With a
     * proxy: one route per proxy, plus the direct fallback counted as one,
     * since its name isn't looked up here (it's the proxy's to resolve, and
     * a lookup on this device mustn't happen just to size a clock) — a
     * fallback to a name with several addresses is held to the watchdog's
     * one-route share.
     */
    internal fun connectRoutes(selected: List<Proxy>, directAddresses: () -> Int): Int {
        val proxies = selected.count { it.type() != Proxy.Type.DIRECT }
        return if (proxies == 0) directAddresses().coerceAtLeast(1) else proxies + 1
    }

    /**
     * [lookUpAhead] for a hop dialed straight from here: how many addresses
     * its name resolves to (1 for an IP literal, or a failed lookup).
     */
    private fun lookUpDirect(hop: URL): Int {
        val raw = okHttpHost(hop.toString())?.takeIf { it.isNotEmpty() && !it.startsWith("[") } ?: return 1
        val name = runCatching { IDN.toASCII(percentDecodeUtf8(raw)) }.getOrNull()?.lowercase(java.util.Locale.US) ?: return 1
        val bare = name.trimEnd('.')
        if (bare.isEmpty() || bare.split('.').let { p -> p.size == 4 && p.all { o -> o.isNotEmpty() && o.all { it in '0'..'9' } } }) {
            return 1
        }
        return runCatching { resolve(name).size }.getOrNull()?.coerceAtLeast(1) ?: 1
    }

    /**
     * Watches the hops of [openFollowingRedirects]: [connecting] just
     * before a hop's `connect()` — which on Android's [HttpURLConnection]
     * runs the TLS handshake (and a SOCKS proxy's reply) under the read
     * timeout, and which a [HttpURLConnection.disconnect] from another
     * thread aborts — [connected] once the connection is up — a disconnect
     * then fails its blocked `responseCode` at once, which it doesn't
     * before `connect()` was called — and [answered] once its headers are
     * in. Any of them may throw to abandon the fetch.
     */
    interface HopWatcher {
        /**
         * Each route's own TLS handshake limit, set on its socket before
         * the handshake (null: the read timeout's, as ever). okhttp moves
         * on to the next route when it fires.
         */
        val handshakeTimeoutMs: Int? get() = null

        /**
         * [routes]: how many routes the connect may try in turn, each under
         * its own connect and handshake timeouts. [fallback]: if this
         * connect fails, [openFollowingRedirects] goes on to another (the
         * next pinned address, the next of the system proxy's routes), so
         * running out of time ends this connect only, not the fetch.
         */
        fun connecting(conn: HttpURLConnection, routes: Int = 1, fallback: Boolean = false)
        fun connected(conn: HttpURLConnection)
        fun answered(conn: HttpURLConnection)
    }

    /**
     * May [openFollowingRedirects], fetching [start], not follow a
     * redirect onto [to] (sent as [method])? Yes for any hop
     * [NodeApiGuard] would refuse a page — the node's own API, on any name
     * that may be this device — and for any hop off [start]'s own origin
     * onto a loopback or unspecified host (`127.0.0.0/8`, `0.0.0.0`,
     * `[::1]`, `localhost`), judged the way the WHATWG parser reads it
     * (`127.1`, `0x7f000001` and `127.0.0.%31` are all `127.0.0.1` to the
     * resolver too), or onto a DNS name that resolves to one
     * (`localtest.me`, `127.0.0.1.nip.io` on any port: [resolvesToLoopback]).
     *
     * Only a hop back onto [start]'s origin — the one the caller chose to
     * fetch — is the server's own business. Every other hop is resolved
     * again, even one within the origin of the hop before it: a name the
     * redirect chain reached is the server's, and it can rebind it to
     * `127.0.0.1` between two of its own hops (answer the first hop
     * publicly, wait out the resolver cache, then `302 /b` on the same
     * name), and the second hop's connection looks the name up afresh.
     *
     * A name that can't be looked up isn't followed, but throws
     * [RedirectUnresolvedException] rather than answering yes: it isn't
     * known to be this device, and the lookup may well work next time
     * (R6-F1).
     */
    internal fun hopRefused(start: URL, to: URL, method: String): Boolean =
        NodeApiGuard.refuses(method, to.toString()) ||
            (!sameOrigin(start, to) && !sameLoopbackServer(start, to) &&
                (NodeApiGuard.mayBeLoopback(to.toString()) || resolvesToLoopback(to)))

    /**
     * Is [to] the same server as [start], a gateway the caller chose on
     * this device: both `localhost` or `*.localhost` as written (no lookup;
     * the WHATWG reading, and every reading [to]'s connection dials), with
     * the same scheme and port? RFC 6761 reserves those names for loopback
     * and Chromium answers them so itself, so a hop between them stays with
     * the server the caller asked for. A loopback literal isn't matched to a
     * different one (`127.0.0.1` and `[::1]` or `127.0.0.2` can be different
     * listeners). A local Kubo's default subdomain redirect
     * (`http://localhost:8080/ipfs/<cid>` → `http://<cidv1>.ipfs.localhost:8080/`)
     * is one such hop (#359 R1-F1).
     *
     * Android's resolver doesn't honour that reservation, though: only
     * `localhost` itself is in the hosts file, and `<x>.localhost` goes to
     * the network's DNS, which can answer NXDOMAIN or any address it likes.
     * So [openFollowingRedirects] doesn't let such a hop's connection look
     * its name up: an http hop is dialed at the addresses `localhost`
     * resolves to ([pinLocalhost]), with no proxy, and an https one (whose
     * certificate needs the name in the URL) is refused unless its
     * connected peer is this device ([DevicePeerRefusingFactory]) — the
     * one listener on that port (#359 R2-F1). The node's own API is still
     * refused on any host ([NodeApiGuard.refuses]), and a hop onto another
     * loopback port still is too.
     */
    internal fun sameLoopbackServer(start: URL, to: URL): Boolean =
        start.protocol.equals(to.protocol, ignoreCase = true) &&
            (if (start.port == -1) start.defaultPort else start.port) == (if (to.port == -1) to.defaultPort else to.port) &&
            isLocalhostName(WhatwgHost.parse(start.toString())?.hostname) &&
            isLocalhostName(WhatwgHost.parse(to.toString())?.hostname) &&
            // And so is every reading the connection may dial ([dialedHosts]).
            dialedHosts(to).let { d -> d.isNotEmpty() && d.all(::isLocalhostName) }

    private fun isLocalhostName(host: String?): Boolean {
        val h = host?.lowercase()?.trimEnd('.') ?: return false
        return h == "localhost" || (h.endsWith(".localhost") && !h.startsWith("."))
    }

    /**
     * Does [url]'s host, a name, resolve to this device — any address
     * [isDeviceAddress] calls loopback or unspecified? A name that doesn't
     * resolve isn't followed (fail closed: a lookup that fails here and
     * answers loopback a moment later is a rebinding trick), but throws
     * [RedirectUnresolvedException], not a yes, so a caller retries it
     * like any failed lookup rather than giving up on it as a redirect
     * onto this device (R6-F1). An onion
     * host is never looked up — it goes to Tor, not the system DNS — and
     * neither is an IP literal ([NodeApiGuard.mayBeLoopback] judges those).
     *
     * Every reading of the host is looked up, not just the WHATWG one:
     * `HttpURLConnection` (OkHttp inside) dials the host it reads itself
     * ([dialedHosts]) — percent-decoded and mapped with IDNA2003
     * ([IDN.toASCII]), which deletes code points UTS-46 keeps and
     * punycodes (U+1806, ZWJ/ZWNJ) and maps `ß` to `ss`. So
     * `127.0.0.1%E1%A0%86.8.8.8.8.nip.io` is `127.0.0.xn--1-f3j.8.8.8.8.nip.io`
     * (a public address) to the WHATWG parser but `127.0.0.1.8.8.8.8.nip.io`
     * (loopback) to the connection; either reading reaching this device
     * refuses the hop. A reading that is an IP literal is judged as one,
     * with no lookup.
     *
     * This check alone doesn't bind the connection: the hop's own
     * lookup can come back different (a TTL-0 rebinding name, or a
     * cached answer that expires in between). [openFollowingRedirects]
     * therefore also dials such a hop by the address it checked ([pin]),
     * or checks an https hop's connected peer ([DevicePeerRefusingFactory]).
     */
    internal fun resolvesToLoopback(url: URL): Boolean {
        if (fetchMayReachOnion(url)) return false
        val whatwg = WhatwgHost.parse(url.toString())?.hostname?.lowercase()?.trimEnd('.') ?: return true
        val dialed = dialedHosts(url)
        // A reading that reaches the device refuses the hop even if another
        // reading didn't resolve: that one's verdict is final, not retried.
        // Only a reading a connection dials can leave the hop unresolved: the
        // WHATWG one, when it's not among them, is checked for reaching the
        // device but no connection ever looks it up, so its failing to resolve
        // says nothing about the hop (#359 R1-F2: `straße.example` is dialed
        // as `strasse.example`, whatever `xn--strae-oqa.example` answers).
        var unresolved: RedirectUnresolvedException? = null
        for (host in linkedSetOf(whatwg) + dialed) {
            try {
                if (hostReachesDevice(host, url)) return true
            } catch (e: RedirectUnresolvedException) {
                if (host in dialed) unresolved = e
            }
        }
        unresolved?.let { throw it }
        return false
    }

    /**
     * The hosts `HttpURLConnection` may dial for [url]: OkHttp's own
     * authority ([okHttpHost]) and [URL.getHost], each percent-decoded
     * and mapped with IDNA2003 the way OkHttp's `HttpUrl` does (with and
     * without unassigned code points allowed), lower-cased, trailing dots
     * dropped. A reading IDNA2003 refuses outright is one the connection
     * refuses too, so it isn't listed.
     */
    internal fun dialedHosts(url: URL): Set<String> {
        val out = linkedSetOf<String>()
        for (raw in listOfNotNull(okHttpHost(url.toString()), url.host)) {
            if (raw.isEmpty()) continue
            if (raw.startsWith("[")) {
                out += raw.lowercase()
                continue
            }
            val decoded = percentDecodeUtf8(raw)
            for (flags in intArrayOf(0, IDN.ALLOW_UNASSIGNED)) {
                runCatching { IDN.toASCII(decoded, flags) }.getOrNull()
                    ?.lowercase()?.trimEnd('.')?.takeIf { it.isNotEmpty() }
                    ?.let { out += it }
            }
        }
        return out
    }

    /**
     * Does [host] (canonical: ASCII, lower-case), a reading of [url]'s,
     * reach this device? An IP literal is judged as written
     * ([isDeviceAddress]); a name is looked up, and one that doesn't
     * resolve throws [RedirectUnresolvedException].
     */
    private fun hostReachesDevice(host: String, url: URL): Boolean {
        if (host.startsWith("[")) {
            val inner = host.removePrefix("[").substringBefore(']')
            if (inner.isEmpty() || !inner.all { it == ':' || it == '.' || it in '0'..'9' || it in 'a'..'f' }) return true
            return runCatching { isDeviceAddress(InetAddress.getByName(inner)) }.getOrDefault(true)
        }
        if (host.split('.').let { p -> p.size == 4 && p.all { o -> o.isNotEmpty() && o.length <= 3 && o.all { it in '0'..'9' } } }) {
            val octets = host.split('.').map { it.toInt() }
            if (octets.any { it > 255 }) return true
            return isDeviceAddress(InetAddress.getByAddress(octets.map { it.toByte() }.toByteArray()))
        }
        val addresses = try {
            resolve(host)
        } catch (_: Exception) {
            throw RedirectUnresolvedException(url)
        }
        if (addresses.isEmpty()) throw RedirectUnresolvedException(url)
        return addresses.any { isDeviceAddress(it) }
    }

    /** The system resolver; swapped in tests. */
    @Volatile
    internal var resolve: (String) -> Array<InetAddress> = { InetAddress.getAllByName(it) }

    /**
     * Is [address] this device: IPv4 `127.0.0.0/8` or `0.0.0.0/8`, or an
     * IPv6 address whose first 80 bits are zero (`::1`, `::`, a v4-mapped
     * or v4-compatible address, whatever it embeds) — the same set
     * [NodeApiGuard.mayBeLoopback] reads in a literal.
     */
    internal fun isDeviceAddress(address: InetAddress): Boolean {
        val b = address.address
        if (address.isLoopbackAddress || address.isAnyLocalAddress) return true
        return when (b.size) {
            4 -> b[0].toInt() == 127 || b[0].toInt() == 0
            16 -> (0 until 10).all { b[it].toInt() == 0 }
            else -> true
        }
    }

    /**
     * A hop [openFollowingRedirects] dials by address: [urls], one per
     * address its name resolved to, in the resolver's order (the next is
     * tried if one can't be connected to), with the host the connection
     * would have sent as [hostHeader].
     */
    internal class Pin(val urls: List<URL>, val hostHeader: String)

    /**
     * The one route [openFollowingRedirects] opens [url], a hop off the
     * caller's origin, through: `null` for an onion host ([openConnection]
     * sends it to Tor's SOCKS proxy), the system proxy's first non-direct
     * choice ([proxiesFor], e.g. a Wi-Fi proxy), else [Proxy.NO_PROXY].
     *
     * Only a [Proxy.Type.DIRECT] hop is pinned ([pin]) or peer-checked
     * ([DevicePeerRefusingFactory]): through a proxy the socket is the
     * proxy's (a SOCKS socket's peer is unknown, a CONNECT tunnel's is
     * the proxy, often `127.0.0.1`) and the proxy resolves the name, so
     * [hopRefused]'s lookup is all there is (R4-F1), and pinning would
     * put the address in the absolute-form request line in place of the
     * name (R4-F2). The route is passed to the connection explicitly, so
     * it can't fall back from a failed proxy to dialing directly, past
     * both checks.
     *
     * The selector is asked about [selectorUri]'s reading of the hop, which
     * holds for a `Location` `java.net.URI` rejects as written (a `|`, `{`,
     * a space — the connection itself sends those, percent-encoded); a hop
     * the selector can't be asked about, or that it fails on, isn't followed
     * ([RedirectRouteException]) rather than dialed directly past a proxy
     * the user set (R5-F1). That isn't a redirect onto this device, so it
     * isn't reported as one (#359 R1-F4).
     */
    internal fun hopRoute(url: URL): Proxy? {
        if (fetchMayReachOnion(url)) return null
        val uri = selectorUri(url) ?: throw RedirectRouteException(url)
        val proxies = try {
            proxiesFor(uri)
        } catch (_: Exception) {
            throw RedirectRouteException(url)
        }
        return proxies.firstOrNull { it.type() != Proxy.Type.DIRECT } ?: Proxy.NO_PROXY
    }

    /**
     * [url] as a `java.net.URI` for the proxy selector, which reads only its
     * scheme, host and port: the URL as written if `URI` takes it, else with
     * its path, query and fragment quoted, else just scheme, the host the
     * connection dials ([okHttpHost], percent-decoded, IDNA2003) and port.
     * `null` if none of those parse.
     */
    internal fun selectorUri(url: URL): URI? {
        runCatching { return url.toURI() }
        runCatching { return URI(url.protocol, url.userInfo, url.host, url.port, url.path, url.query, url.ref) }
        val raw = okHttpHost(url.toString())?.takeIf { it.isNotEmpty() } ?: return null
        val host = if (raw.startsWith("[")) raw else runCatching { IDN.toASCII(percentDecodeUtf8(raw)) }.getOrNull() ?: return null
        return runCatching { URI(url.protocol, null, host, url.port, null, null, null) }.getOrNull()
    }

    /** The system proxy selector's choice for a URI; swapped in tests. */
    @Volatile
    internal var proxiesFor: (URI) -> List<Proxy> = { uri ->
        ProxySelector.getDefault()?.select(uri).orEmpty().ifEmpty { listOf(Proxy.NO_PROXY) }
    }

    /**
     * Pin [hop], a redirect off the caller's origin dialed directly
     * ([hopRoute] direct), to the addresses its name resolves to now, so the
     * connection can't look the name up again and get another answer: a
     * name the redirect chain reached is the server's, and its DNS can
     * answer a public address to [hopRefused]'s lookup and `127.0.0.1` to
     * the connection's (TTL 0, or a cached answer that expires in
     * between). `null` for a hop nothing resolves for — an onion host
     * (Tor's), an IP literal — and for https, whose peer
     * [DevicePeerRefusingFactory] checks on the connected socket instead
     * (an address in the URL would break SNI and the certificate check).
     * Only called with no proxy in between, so the request line is
     * origin-form and the server sees the name in [Pin.hostHeader], not
     * the address.
     *
     * The name looked up is the one `HttpURLConnection` would dial
     * ([okHttpHost], percent-decoded, IDNA2003); it's refused
     * ([RedirectRefusedException]) if any address is this device
     * ([isDeviceAddress]), and not followed ([RedirectUnresolvedException])
     * if it doesn't resolve.
     */
    internal fun pin(hop: URL): Pin? {
        if (!hop.protocol.equals("http", ignoreCase = true) || fetchMayReachOnion(hop)) return null
        val raw = okHttpHost(hop.toString())?.takeIf { it.isNotEmpty() && !it.startsWith("[") } ?: return null
        val host = runCatching { IDN.toASCII(percentDecodeUtf8(raw)) }.getOrNull()?.lowercase() ?: return null
        val name = host.trimEnd('.')
        if (name.isEmpty() || name.split('.').let { p -> p.size == 4 && p.all { o -> o.isNotEmpty() && o.all { it in '0'..'9' } } }) {
            return null
        }
        val addresses = try {
            resolve(name)
        } catch (_: Exception) {
            throw RedirectUnresolvedException(hop)
        }
        if (addresses.isEmpty()) throw RedirectUnresolvedException(hop)
        if (addresses.any { isDeviceAddress(it) }) throw RedirectRefusedException(hop)
        val urls = addresses.map { it.hostAddress.orEmpty().substringBefore('%') }.distinct()
            .map { URL(hop.protocol, it, hop.port, hop.file) }
        val hostHeader = if (hop.port == -1 || hop.port == hop.defaultPort) host else "$host:${hop.port}"
        return Pin(urls, hostHeader)
    }

    /**
     * Pin [hop], a hop [sameLoopbackServer] keeps with a gateway on
     * `localhost`, to this device: the addresses `localhost` itself resolves
     * to (the hosts file, not the network), all of which must be this
     * device ([isDeviceAddress]), with [hop]'s own host in
     * [Pin.hostHeader] so the gateway still sees the subdomain. `null` for
     * https, whose connected peer is checked instead
     * ([DevicePeerRefusingFactory] with `requireDevice`). Refused
     * ([RedirectRefusedException]) if `localhost` answers nothing, or
     * anything that isn't this device (#359 R2-F1).
     */
    internal fun pinLocalhost(hop: URL): Pin? {
        if (!hop.protocol.equals("http", ignoreCase = true)) return null
        val addresses = runCatching { resolve("localhost") }.getOrNull().orEmpty()
        if (addresses.isEmpty() || !addresses.all { isDeviceAddress(it) }) throw RedirectRefusedException(hop)
        val host = WhatwgHost.parse(hop.toString())?.hostname?.lowercase() ?: throw RedirectRefusedException(hop)
        val urls = addresses.map { it.hostAddress.orEmpty().substringBefore('%') }.distinct()
            .map { URL(hop.protocol, it, hop.port, hop.file) }
        val hostHeader = if (hop.port == -1 || hop.port == hop.defaultPort) host else "$host:${hop.port}"
        return Pin(urls, hostHeader)
    }

    /**
     * An https hop's [SSLSocketFactory] that refuses the connection
     * ([RedirectRefusedException]) if the socket it's handed is connected
     * to this device ([isDeviceAddress]) — before the handshake, so not a
     * byte of the request is sent. The check is on the address actually
     * dialed, so no lookup can answer it differently ([pin]'s job on http).
     * With `requireDevice`, the other way round: refused unless the peer
     * is this device ([sameLoopbackServer]'s `*.localhost` hops, whose name
     * Android looks up on the network, #359 R2-F1).
     */
    private class DevicePeerRefusingFactory(
        private val delegate: SSLSocketFactory,
        private val hop: URL,
        /** Refuse a peer that *isn't* this device instead ([sameLoopbackServer]'s hops, #359 R2-F1). */
        private val requireDevice: Boolean = false,
    ) : SSLSocketFactory() {
        private fun checked(socket: Socket): Socket {
            val peer = socket.inetAddress
            if (peer == null || isDeviceAddress(peer) != requireDevice) {
                runCatching { socket.close() }
                throw RedirectRefusedException(hop)
            }
            return socket
        }

        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites
        override fun createSocket(s: Socket, host: String?, port: Int, autoClose: Boolean): Socket {
            checked(s)
            return delegate.createSocket(s, host, port, autoClose)
        }
        override fun createSocket(host: String?, port: Int): Socket = checked(delegate.createSocket(host, port))
        override fun createSocket(host: String?, port: Int, local: InetAddress?, localPort: Int): Socket =
            checked(delegate.createSocket(host, port, local, localPort))
        override fun createSocket(host: InetAddress?, port: Int): Socket = checked(delegate.createSocket(host, port))
        override fun createSocket(address: InetAddress?, port: Int, local: InetAddress?, localPort: Int): Socket =
            checked(delegate.createSocket(address, port, local, localPort))
    }

    /**
     * An https hop's [SSLSocketFactory] that gives each route's TLS
     * handshake a read timeout of [timeoutMs]: okhttp layers TLS over each
     * route's connected socket here, after setting that socket's read
     * timeout to the connection's own (the long body stall limit) and
     * before the handshake, so this is where a handshake can get a shorter
     * one. When it fires, okhttp tries the next route (a
     * `SocketTimeoutException` is recoverable), as it did under `main`'s
     * 10 s read timeout (PR #409 R6-M1). Once a route is up okhttp sets the
     * socket's timeout back to the connection's own before any request is
     * written, so the body stall limits are untouched. Equal to any other
     * with the same delegate and timeout, so an https gateway's pooled
     * connections stay reusable (okhttp keys its pool by the factory too).
     */
    private class HandshakeTimeoutFactory(
        private val delegate: SSLSocketFactory,
        private val timeoutMs: Int,
    ) : SSLSocketFactory() {
        private fun timed(socket: Socket): Socket = socket.apply { soTimeout = timeoutMs }

        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites
        override fun createSocket(s: Socket, host: String?, port: Int, autoClose: Boolean): Socket {
            timed(s)
            return timed(delegate.createSocket(s, host, port, autoClose))
        }
        override fun createSocket(host: String?, port: Int): Socket = timed(delegate.createSocket(host, port))
        override fun createSocket(host: String?, port: Int, local: InetAddress?, localPort: Int): Socket =
            timed(delegate.createSocket(host, port, local, localPort))
        override fun createSocket(host: InetAddress?, port: Int): Socket = timed(delegate.createSocket(host, port))
        override fun createSocket(address: InetAddress?, port: Int, local: InetAddress?, localPort: Int): Socket =
            timed(delegate.createSocket(address, port, local, localPort))

        override fun equals(other: Any?): Boolean =
            other is HandshakeTimeoutFactory && other.delegate == delegate && other.timeoutMs == timeoutMs

        override fun hashCode(): Int = delegate.hashCode() * 31 + timeoutMs
    }

    /**
     * [openFollowingRedirects]'s refusal of a hop onto this device
     * ([hopRefused]). A [java.net.ConnectException], so a caller treats
     * it as the server being unreachable, not as worth retrying.
     */
    class RedirectRefusedException(to: URL) :
        java.net.ConnectException(Strings.get(R.string.node_fetch_redirect_refused, "${to.protocol}://${to.authority}"))

    /**
     * [openFollowingRedirects] not following a hop whose name it couldn't
     * look up, so couldn't tell isn't this device ([hopRefused]). An
     * [java.net.UnknownHostException], so a caller retries it the way it
     * would a failed lookup of its own, rather than reporting a redirect
     * onto this device that may never have been one (R6-F1).
     */
    class RedirectUnresolvedException(to: URL) :
        java.net.UnknownHostException(Strings.get(R.string.node_fetch_redirect_unresolved, "${to.protocol}://${to.authority}"))

    /**
     * [openFollowingRedirects] not following a hop the system proxy selector
     * couldn't be asked about, or failed on ([hopRoute]): not dialed
     * directly past a proxy the user may have set. A plain [IOException] —
     * neither a redirect onto this device nor an unreachable server (#359 R1-F4).
     */
    class RedirectRouteException(to: URL) :
        IOException(Strings.get(R.string.node_fetch_redirect_no_route, "${to.protocol}://${to.authority}"))

    /** [openConnection]'s refusal of an onion URL while no Tor port is routed. */
    class RefusedException : IOException(Strings.get(R.string.node_tor_refused_fetch))

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

    /**
     * The refusal page code with no external check pending: an [external]
     * proxy's [CODE_PROXY_DOWN] only while Tor runs with it
     * ([externalRunning]) — stopped (or not started), it's "Tor isn't
     * running" as with the embedded client: nothing checked the proxy
     * (#305 R1-M1).
     */
    internal fun refusalCode(
        supported: Boolean?,
        enabled: Boolean,
        external: Boolean = false,
        externalRunning: Boolean = false,
    ): String = when {
        supported == false -> CODE_UNSUPPORTED
        !enabled -> CODE_OFF
        external && externalRunning -> CODE_PROXY_DOWN
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
    ): Pair<String, String> {
        // The copy is HTML; the proxy goes in as it always has, unescaped (an authority, host:port).
        val at = proxy?.toString() ?: Strings.get(R.string.node_tor_proxy_not_set)
        val (title, description) = when (code) {
            CODE_UNSUPPORTED -> R.string.node_tor_refusal_unsupported_title to
                Strings.get(R.string.node_tor_refusal_unsupported)
            CODE_PROXY_CHECKING -> R.string.node_tor_refusal_checking_title to
                Strings.get(R.string.node_tor_refusal_checking, at)
            CODE_PROXY_PAUSED -> R.string.node_tor_refusal_paused_title to
                Strings.get(R.string.node_tor_refusal_paused, at)
            CODE_PROXY_DOWN -> if (unreached) {
                R.string.node_tor_refusal_unreached_title to Strings.get(R.string.node_tor_refusal_unreached, at)
            } else {
                R.string.node_tor_refusal_proxy_down_title to Strings.get(R.string.node_tor_refusal_proxy_down, at)
            }
            CODE_OFF -> R.string.node_tor_refusal_off_title to Strings.get(R.string.node_tor_refusal_off)
            else -> R.string.node_tor_refusal_not_running_title to Strings.get(
                if (info.status == TorStatus.Error) {
                    R.string.node_tor_refusal_failed
                } else {
                    R.string.node_tor_refusal_not_running
                },
            )
        }
        return Strings.get(title) to description
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
internal fun percentDecodeUtf8(s: String): String {
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
