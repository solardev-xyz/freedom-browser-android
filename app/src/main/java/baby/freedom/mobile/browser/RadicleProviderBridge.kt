package baby.freedom.mobile.browser

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import baby.freedom.mobile.data.RadicleGrantStore
import java.util.WeakHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * A consent prompt waiting on its tab (#124): [ask] is what a page on
 * [RadicleAsk.origin] wants, [respond] the user's answer. Shown by
 * [BrowserScreen] ([RadiclePromptDialog]) only while its tab is the
 * active one and its page is on screen.
 */
class RadiclePromptRequest internal constructor(val ask: RadicleAsk) {
    internal val answer = CompletableDeferred<Boolean>()

    fun respond(allowed: Boolean) {
        answer.complete(allowed)
    }
}

/**
 * The page side of the `window.radicle` provider (#124) and its channel:
 * a document-start script ([radicleProviderJs]) defines `window.radicle`
 * in every http(s) top-level document of a normal (not private) tab
 * while Radicle is turned on ([setEnabled]) — with it off (the default)
 * pages see no `window.radicle` and no channel at all, so the provider
 * isn't a way to tell this browser apart (#201 R1-F3) — and sends each request down a `WebMessageListener` channel to
 * [RadicleProvider], which answers back on it. Events (`connect`,
 * `disconnect`, `seedStatus`) go to the documents on the origin they're
 * for, on the channel they last spoke on.
 *
 * Who is asking is the platform's `sourceOrigin` for the document that
 * sent the message — never anything the page claims — and only a tab's
 * top-level document may ask: a frame can't act as the page it's
 * embedded in, nor as itself. The origin must be a secure one: https
 * (the dweb and onchain-app origins are https too) or http on loopback.
 * The repository browser's own origin ([RadUrl.HOST]) has no provider.
 *
 * Prompts ([RadiclePromptRequest]) are one at a time per tab, taken down
 * (as a refusal) when the tab starts a new document or closes. Once the
 * user refuses one, that tab's pages get no more prompts — every ask is
 * refused at once — until the user navigates the tab themselves
 * ([allowPrompts]), so a page can't hold the browser behind a loop of
 * them.
 */
object RadicleProviders {
    @Volatile
    private var provider: RadicleProvider? = null

    private val scope = MainScope()

    /** Live bridges, one per WebView; main thread only. */
    private val bridges = WeakHashMap<WebView, Bridge>()

    /** Main thread only, like everything below. */
    private val documents = HashMap<Long, Int>()

    /** Each tab's committed document's origin key, as of its [onDocumentStarted]. */
    private val committedOrigins = HashMap<Long, String?>()
    private val promptLocks = HashMap<Long, Mutex>()
    private val pending = HashMap<Long, MutableSet<RadiclePromptRequest>>()
    private val blockedTabs = HashSet<Long>()

    private class Bridge(val tab: BrowserState) {
        /** The origin and channel of the top-level document that last spoke. */
        var origin: String? = null
        var reply: JavaScriptReplyProxy? = null

        /**
         * The channel, once attached, and the script on it: the provider
         * while Radicle is on, [radicleChannelClosingJs] once it's been
         * turned off ([setEnabled]).
         */
        var channel: String? = null
        var script: ScriptHandler? = null
    }

    /** Settings → Radicle node is on; main thread. */
    private var enabled = false

    fun isSupported(): Boolean = runCatching {
        WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
    }.getOrDefault(false)

    /** Wire the provider to the grant store and the node, once per process. */
    fun init(context: Context) {
        if (provider != null) return
        val store = RadicleGrantStore.get(context)
        val p = RadicleProvider(
            grants = object : RadicleProvider.Grants {
                override suspend fun signingFor(origin: String) = store.grantFor(origin)?.let { it.signingAs.orEmpty() }
                override suspend fun connect(origin: String) = store.connect(origin)
                override suspend fun signedBefore(origin: String) = store.grantFor(origin)?.let { it.signingAs ?: it.signedAs }
                override suspend fun grantSigning(origin: String, did: String) = store.grantSigning(origin, did)
                override suspend fun revoke(origin: String) = store.revoke(origin)
            },
            node = object : RadicleProvider.Node {
                override val state get() = RadicleClient.state
                override fun unavailableReason() = RadicleClient.unavailableReason()
                override fun call(method: String, args: JSONObject, timeoutMs: Long) =
                    RadicleClient.call(method, args, timeoutMs)
                override fun seed(rid: String) = RadicleClient.seed(rid)
                override fun unseed(rid: String) = RadicleClient.unseed(rid)
            },
        )
        p.events = RadicleProvider.Events { origin, event, data ->
            scope.launch { emit(origin, event, data) }
        }
        p.start(scope)
        provider = p
    }

    /**
     * The user dropped [origin]'s grant from the Radicle page: its open
     * pages hear `disconnect`, as if they had disconnected themselves, and
     * no more `seedStatus` (#201 R1-F2).
     */
    fun revoked(origin: String) {
        provider?.forget(origin)
        scope.launch { emit(origin, "disconnect", JSONObject().put("origin", origin)) }
    }

    /**
     * The user is seeding [rid] from the Radicle page: a site's seed
     * prompt answered before that fetch's line arrives reads the node as
     * busy (#349 R5-M1). [ask] hands it to the node and says whether it
     * got there; it runs whether or not a provider exists.
     */
    fun userSeeding(rid: String, ask: () -> Boolean) {
        val provider = provider
        if (provider != null) provider.userSeeding(rid, ask) else ask()
    }

    /**
     * Track [webView] (a tab's, before its first load) and, while Radicle
     * is on, register the channel and the script on it. Nothing for a
     * private tab: its grants would have nowhere to live that the private
     * session could forget.
     */
    fun install(webView: WebView, tab: BrowserState) {
        if (tab.private || !isSupported()) return
        val bridge = Bridge(tab)
        bridges[webView] = bridge
        if (enabled) attach(webView, bridge)
    }

    /**
     * Radicle was turned on or off: add or take away the provider on every
     * tab. It applies from each tab's next document; the one on screen
     * keeps its `window.radicle`, which answers 4900 once Radicle is off
     * (#201 R2-F1). For that the channel stays registered while off — a
     * removed listener drops what the page sends, and its requests would
     * hang until the script's own timeout — and the provider script is
     * swapped for one that only takes the channel object off `window`
     * before the page's own scripts run, so later documents still see no
     * `window.radicle` and no channel.
     */
    fun setEnabled(on: Boolean) {
        if (on == enabled) return
        enabled = on
        for ((webView, bridge) in bridges.entries.toList()) {
            if (on) attach(webView, bridge) else closeChannel(webView, bridge)
        }
    }

    private fun attach(webView: WebView, bridge: Bridge) {
        val tab = bridge.tab
        try {
            val channel = bridge.channel ?: newBottomUiChannelName().also { channel ->
                WebViewCompat.addWebMessageListener(webView, channel, setOf("*")) { _, message, sourceOrigin, isMainFrame, reply ->
                    onMessage(bridge, message, sourceOrigin.toString(), isMainFrame, reply)
                }
                bridge.channel = channel
            }
            bridge.script?.remove()
            bridge.script = null
            bridge.script = WebViewCompat.addDocumentStartJavaScript(webView, radicleProviderJs(channel), setOf("*"))
        } catch (e: RuntimeException) {
            // A destroyed WebView: nothing to attach to.
            Log.w(TAG, "couldn't attach the provider to tab ${tab.id}", e)
            detach(webView, bridge)
        }
    }

    /**
     * Radicle was turned off: the next documents get only the script that
     * hides the channel; what spoke before hears no more events.
     */
    private fun closeChannel(webView: WebView, bridge: Bridge) {
        val channel = bridge.channel ?: return
        bridge.origin = null
        bridge.reply = null
        try {
            bridge.script?.remove()
            bridge.script = null
            bridge.script = WebViewCompat.addDocumentStartJavaScript(webView, radicleChannelClosingJs(channel), setOf("*"))
        } catch (e: RuntimeException) {
            Log.w(TAG, "couldn't close the provider on tab ${bridge.tab.id}", e)
            detach(webView, bridge)
        }
    }

    private fun detach(webView: WebView, bridge: Bridge) {
        runCatching { bridge.script?.remove() }
        bridge.channel?.let { channel -> runCatching { WebViewCompat.removeWebMessageListener(webView, channel) } }
        bridge.script = null
        bridge.channel = null
        bridge.origin = null
        bridge.reply = null
    }

    private fun onMessage(
        bridge: Bridge,
        message: WebMessageCompat,
        sourceOrigin: String,
        isMainFrame: Boolean,
        reply: JavaScriptReplyProxy,
    ) {
        val tab = bridge.tab
        if (message.type != WebMessageCompat.TYPE_STRING) return
        // The prompt's deadline runs from the request's arrival ([PROMPT_WAIT_MS]).
        val deadline = SystemClock.elapsedRealtime() + PROMPT_WAIT_MS
        val request = parseRadicleRequest(message.data) ?: run {
            // Readable enough to answer (`params` not an object, say): the
            // page learns now, not after its five-minute timer.
            unparsedRequestId(message.data)?.let {
                answer(reply, it, RadicleProvider.Reply.Err(RadicleProvider.INVALID_PARAMS, "Invalid request"))
            }
            return
        }
        val origin = providerOriginKey(sourceOrigin)
        if (!isMainFrame || origin == null) {
            val why = if (!isMainFrame) "window.radicle is only available to the top-level page" else "Origin not permitted"
            answer(reply, request.id, RadicleProvider.Reply.Err(RadicleProvider.UNAUTHORIZED, why))
            return
        }
        // A page from before Radicle was turned off (#201 R2-F1).
        if (!enabled) {
            answer(
                reply,
                request.id,
                RadicleProvider.Reply.Err(RadicleProvider.UNAVAILABLE, "Radicle is turned off", RadicleClient.REASON_DISABLED),
            )
            return
        }
        // Which of the tab's documents this is: not simply the current
        // one — a message from the outgoing document can arrive after the
        // tab started the next (#201 R1-F5). Judged against the document
        // the tab committed, not `view.url`: that is already the pending
        // address while a load the browser started is still on its way,
        // and the page still on screen would be taken for a stale one
        // (#201 R2-F2).
        val doc = radicleDocumentFor(
            current = documents[tab.id] ?: 0,
            origin = origin,
            committedOrigin = committedOrigins[tab.id],
        )
        if (doc != STALE_DOCUMENT) {
            bridge.origin = origin
            bridge.reply = reply
        }
        scope.launch {
            val result = try {
                val p = provider ?: throw IllegalStateException("provider not ready")
                p.request(origin, request.method, request.params) { ask ->
                    askOnTab(tab, doc, ask, deadline - SystemClock.elapsedRealtime())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "radicle request ${request.method} failed", e)
                RadicleProvider.Reply.Err(RadicleProvider.INTERNAL, "Internal error")
            }
            answer(reply, request.id, result)
        }
    }

    private fun answer(reply: JavaScriptReplyProxy, id: Long, result: RadicleProvider.Reply) {
        val body = JSONObject().put("id", id)
        when (result) {
            is RadicleProvider.Reply.Ok -> body.put("result", result.value)
            is RadicleProvider.Reply.Err -> body.put("error", result.toJson())
        }
        // The document may be gone by now; then there's nobody to tell.
        runCatching { reply.postMessage(body.toString()) }
    }

    private fun emit(origin: String, event: String, data: Any) {
        val message = JSONObject().put("event", event).put("data", data).toString()
        for (bridge in bridges.values.toList()) {
            if (bridge.origin != origin) continue
            runCatching { bridge.reply?.postMessage(message) }
        }
    }

    /**
     * Put [ask] up on [tab] and wait for the answer — false at once if the
     * tab is blocked from prompting, the document that asked ([doc]) is no
     * longer the tab's, or the tab moves on or closes while it waits; and
     * false once [waitMs] runs out (queued behind another prompt, or up
     * unanswered in a tab the user isn't looking at). The page's own
     * timer rejects its request after five minutes, and it may retry: an
     * Allow tapped after that would carry out a request the page has
     * already given up on. Running out doesn't block the tab: the
     * user refused nothing.
     */
    internal suspend fun askOnTab(tab: BrowserState, doc: Int, ask: RadicleAsk, waitMs: Long = PROMPT_WAIT_MS): Boolean {
        fun live() = (documents[tab.id] ?: 0) == doc && tab.id !in blockedTabs
        if (!live() || waitMs <= 0) return false
        val lock = promptLocks.getOrPut(tab.id) { Mutex() }
        return withTimeoutOrNull(waitMs) {
            lock.withLock {
                if (!live()) return@withLock false
                val request = RadiclePromptRequest(ask)
                pending.getOrPut(tab.id) { mutableSetOf() }.add(request)
                tab.radiclePrompt = request
                val allowed = try {
                    request.answer.await()
                } finally {
                    pending[tab.id]?.remove(request)
                    if (tab.radiclePrompt === request) tab.radiclePrompt = null
                }
                if (!allowed && live()) blockedTabs += tab.id
                allowed && live()
            }
        } ?: false
    }

    /**
     * The tab started (committed) a new document on [url] — null when it's
     * being torn down: what the old one asked is refused.
     */
    fun onDocumentStarted(tab: BrowserState, url: String?) {
        documents[tab.id] = (documents[tab.id] ?: 0) + 1
        committedOrigins[tab.id] = providerOriginKey(url)
        withdraw(tab.id)
    }

    /** The tab closed. */
    fun onTabClosed(tabId: Long) {
        withdraw(tabId)
        documents.remove(tabId)
        committedOrigins.remove(tabId)
        promptLocks.remove(tabId)
        pending.remove(tabId)
        blockedTabs.remove(tabId)
    }

    /** The user navigated [tabId] themselves: its pages may prompt again. */
    fun allowPrompts(tabId: Long) {
        blockedTabs.remove(tabId)
    }

    private fun withdraw(tabId: Long) {
        pending[tabId]?.toList()?.forEach { it.respond(false) }
    }

    /**
     * How long a prompt waits, from the request's arrival: short of the
     * page's five-minute timer by the node calls an allowed request still
     * makes — reading the identity again ([RadicleClient.READ_TIMEOUT_MS])
     * and the write ([RadicleClient.WRITE_TIMEOUT_MS]) — so what the user
     * allows is answered before the page gives up on it.
     */
    internal const val PROMPT_WAIT_MS = 300_000L - RadicleClient.READ_TIMEOUT_MS - RadicleClient.WRITE_TIMEOUT_MS - 10_000L

    private const val TAG = "RadicleProvider"
}

/** No document of the tab's: [RadicleProviders]' prompts are refused for it. */
internal const val STALE_DOCUMENT = -1

/**
 * Which of a tab's documents (its [RadicleProviders] document number) a
 * message on [origin] came from: the [current] one — unless [origin]
 * isn't that of the document the tab last committed ([committedOrigin],
 * its [providerOriginKey]). Then it's the outgoing document, still
 * talking after the tab started the next one, and it gets
 * [STALE_DOCUMENT], so its prompt never shows over (and names another
 * site than) the new page. A late message from an outgoing document on
 * the *same* origin still counts as the current one; its prompt names
 * the site that is on screen. A load that hasn't committed yet changes
 * nothing: the page on screen is still the current document.
 */
internal fun radicleDocumentFor(current: Int, origin: String, committedOrigin: String?): Int =
    if (committedOrigin == origin) current else STALE_DOCUMENT

/** One `window.radicle` request off the channel. */
internal data class RadicleRequest(val id: Long, val method: String, val params: JSONObject)

/** Parse `{"id": n, "method": "radicle_…", "params": {…}}`, or null if it isn't one. */
internal fun parseRadicleRequest(data: String?): RadicleRequest? {
    if (data == null || data.length > MAX_REQUEST_CHARS) return null
    val json = try {
        JSONObject(data)
    } catch (e: Exception) {
        return null
    } catch (e: StackOverflowError) {
        return null
    }
    val id = (json.opt("id") as? Number)?.toLong() ?: return null
    val method = json.opt("method") as? String ?: return null
    if (method.length > 64) return null
    val params = when (val p = json.opt("params")) {
        null, JSONObject.NULL -> JSONObject()
        is JSONObject -> p
        else -> return null
    }
    return RadicleRequest(id, method, params)
}

/** Bigger than any valid request (a 64 KiB body, JSON-escaped). */
private const val MAX_REQUEST_CHARS = 512 * 1024

/**
 * The provider's key for a document on [raw] (the platform's
 * `sourceOrigin`): a normalized `scheme://host[:port]` ([permissionOriginKey])
 * for a secure origin — https, or http on a loopback host — and null for
 * anything else, including the repository browser's own origin.
 *
 * Also null for a content gateway's own origin on the device (#457,
 * [isLoopbackGatewayOrigin]): every Swarm or IPFS root loaded as
 * `http://127.0.0.1:1633/bzz/<ref>/` shares that one origin, so a grant
 * given to one of them would be every root's, and any page could
 * navigate there with an attacker's hash. Content gets its provider on
 * its own per-root virtual origin ([VirtualOrigin]) instead.
 */
internal fun providerOriginKey(raw: String?): String? {
    val key = permissionOriginKey(raw) ?: return null
    if (key == RadUrl.ORIGIN) return null
    if (isLoopbackGatewayOrigin(key)) return null
    if (key.startsWith("https://")) return key
    return key.takeIf { loopbackHttpPort(key) != null }
}

/**
 * Whether [originKey] (a [permissionOriginKey]) is a content gateway's
 * own origin on the device (#457): a loopback host, `http` or `https`,
 * on the port of the embedded Swarm or IPFS gateway, or of an external
 * endpoint the user pointed at this device
 * ([Gateways.loopbackGatewayPorts]). Such an origin holds no provider
 * ([providerOriginKey]) and no site permission ([sitePermissionOriginKey]).
 *
 * Matched by port on any loopback name (`127.0.0.0/8`, `localhost`,
 * `*.localhost`, `[::1]`), since every one of them reaches the gateway.
 * That includes a subdomain gateway's per-root host
 * (`http://<cid>.ipfs.localhost:8080`, what Kubo redirects a path
 * request to): it is one root's origin, but nothing here can tell a
 * gateway that keys content by `Host` from one that serves
 * `/ipfs/<any>` on every name, so it gets no provider or permission
 * either; content keeps both on its virtual origin.
 */
internal fun isLoopbackGatewayOrigin(originKey: String): Boolean {
    val port = loopbackPort(originKey) ?: return false
    return port in Gateways.loopbackGatewayPorts()
}

/**
 * The port of [originKey] (a [permissionOriginKey]) if it's `http://` on
 * a loopback host ([isLoopbackHost]), else null.
 */
internal fun loopbackHttpPort(originKey: String): Int? =
    if (originKey.startsWith("http://")) loopbackPort(originKey) else null

/**
 * The port of [originKey] (a [permissionOriginKey]) if it's `http://` or
 * `https://` on a loopback host ([isLoopbackHost]), the scheme's default
 * when it names none; else null.
 */
internal fun loopbackPort(originKey: String): Int? {
    val (prefix, defaultPort) = when {
        originKey.startsWith("http://") -> "http://" to "80"
        originKey.startsWith("https://") -> "https://" to "443"
        else -> return null
    }
    val hostPort = originKey.removePrefix(prefix)
    val host = (
        if (hostPort.startsWith("[")) hostPort.substringAfter('[').substringBefore(']')
        else hostPort.substringBefore(':')
    ).trimEnd('.')
    if (!isLoopbackHost(host)) return null
    return hostPort.substringAfterLast(']').substringAfter(':', defaultPort).toIntOrNull()
}

/**
 * What documents get on a channel whose provider was turned off
 * ([RadicleProviders.setEnabled]): the channel object taken off `window`
 * before the page's own scripts run, as [radicleProviderJs] does, and
 * nothing else — no `window.radicle`.
 */
internal fun radicleChannelClosingJs(channel: String): String {
    require(Regex("[a-z]{8,64}").matches(channel)) { "channel must be lower-case letters" }
    return "(function () { try { delete window['$channel']; } catch (e) {} })();"
}

/**
 * The page side of [RadicleProviders]: `window.radicle` with `request()`,
 * one wrapper per method (iOS's and desktop's), and `on` /
 * `removeListener` for events. The channel object the platform puts on
 * `window` is taken off it before the page's own scripts run (#69), and
 * has a random name; subframes and non-http(s) documents get no provider.
 * Requests time out after five minutes (a prompt can sit a while).
 */
internal fun radicleProviderJs(channel: String): String {
    require(Regex("[a-z]{8,64}").matches(channel)) { "channel must be lower-case letters" }
    return """
(function () {
  var w = window, N = '$channel', port = w[N];
  if (port === undefined) return;
  try { delete w[N]; } catch (e) {}
  if (!port || typeof port.postMessage !== 'function') return;
  var proto = w.location.protocol;
  if (proto !== 'http:' && proto !== 'https:') return;
  if (w.top !== w) return;
  var send = port.postMessage.bind(port), stringify = JSON.stringify, parse = JSON.parse;
  var setT = w.setTimeout, clearT = w.clearTimeout, P = w.Promise, E = w.Error;
  var pending = new w.Map(), nextId = 0;
  var listeners = { connect: [], disconnect: [], seedStatus: [] };
  port.addEventListener('message', function (ev) {
    var msg;
    try { msg = parse(ev.data); } catch (e) { return; }
    if (!msg || typeof msg !== 'object') return;
    if (typeof msg.event === 'string') {
      var hs = listeners[msg.event];
      if (!hs) return;
      hs.slice().forEach(function (h) { try { h(msg.data); } catch (e) {} });
      return;
    }
    var p = pending.get(msg.id);
    if (!p) return;
    pending.delete(msg.id);
    clearT(p.timer);
    if (msg.error) {
      var err = new E(msg.error.message || 'Unknown error');
      err.code = msg.error.code;
      if (msg.error.data) err.data = msg.error.data;
      p.reject(err);
    } else {
      p.resolve(msg.result);
    }
  });
  function request(method, params) {
    return new P(function (resolve, reject) {
      if (typeof method !== 'string') {
        var bad = new E('method must be a string');
        bad.code = -32602;
        reject(bad);
        return;
      }
      var id = ++nextId;
      var timer = setT(function () {
        if (pending.delete(id)) reject(new E('Request timed out'));
      }, 300000);
      pending.set(id, { resolve: resolve, reject: reject, timer: timer });
      try {
        send(stringify({ id: id, method: method, params: params || {} }));
      } catch (e) {
        pending.delete(id);
        clearT(timer);
        reject(e);
      }
    });
  }
  function method(name) {
    return function (params) { return request(name, params); };
  }
  var radicle = {
    isFreedomBrowser: true,
    request: function (payload) {
      return request(payload && payload.method, payload && payload.params);
    },
    requestAccess: method('radicle_requestAccess'),
    disconnect: method('radicle_disconnect'),
    getCapabilities: method('radicle_getCapabilities'),
    getNodeStatus: method('radicle_getNodeStatus'),
    listSeededRepos: method('radicle_listSeededRepos'),
    seed: method('radicle_seed'),
    unseed: method('radicle_unseed'),
    sync: method('radicle_sync'),
    getSeedStatus: method('radicle_getSeedStatus'),
    getIdentity: method('radicle_getIdentity'),
    createIssue: method('radicle_createIssue'),
    commentIssue: method('radicle_commentIssue'),
    editIssueState: method('radicle_editIssueState'),
    commentPatch: method('radicle_commentPatch'),
    on: function (event, handler) {
      if (listeners[event] && typeof handler === 'function') listeners[event].push(handler);
      return radicle;
    },
    removeListener: function (event, handler) {
      var hs = listeners[event];
      if (hs) {
        var i = hs.indexOf(handler);
        if (i > -1) hs.splice(i, 1);
      }
      return radicle;
    },
    removeAllListeners: function (event) {
      if (event && listeners[event]) listeners[event] = [];
      return radicle;
    }
  };
  try {
    Object.defineProperty(w, 'radicle', { value: radicle, configurable: true, enumerable: false, writable: true });
  } catch (e) {}
})();
""".trimIndent()
}
