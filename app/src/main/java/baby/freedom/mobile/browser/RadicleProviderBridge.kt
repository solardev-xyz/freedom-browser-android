package baby.freedom.mobile.browser

import android.content.Context
import android.util.Log
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
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
 * in every http(s) top-level document of a normal (not private) tab,
 * and sends each request down a `WebMessageListener` channel to
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
    private val promptLocks = HashMap<Long, Mutex>()
    private val pending = HashMap<Long, MutableSet<RadiclePromptRequest>>()
    private val blockedTabs = HashSet<Long>()

    private class Bridge(val tab: BrowserState) {
        /** The origin and channel of the top-level document that last spoke. */
        var origin: String? = null
        var reply: JavaScriptReplyProxy? = null
    }

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
                override suspend fun signingFor(origin: String) = store.grantFor(origin)?.signing
                override suspend fun connect(origin: String) = store.connect(origin)
                override suspend fun grantSigning(origin: String) = store.grantSigning(origin)
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
     * pages hear `disconnect`, as if they had disconnected themselves.
     */
    fun revoked(origin: String) {
        scope.launch { emit(origin, "disconnect", JSONObject().put("origin", origin)) }
    }

    /**
     * Register the channel and the script on [webView], before its first
     * load. Nothing for a private tab: its grants would have nowhere to
     * live that the private session could forget.
     */
    fun install(webView: WebView, tab: BrowserState) {
        if (tab.private || !isSupported()) return
        val bridge = Bridge(tab)
        bridges[webView] = bridge
        val channel = newBottomUiChannelName()
        WebViewCompat.addWebMessageListener(webView, channel, setOf("*")) { _, message, sourceOrigin, isMainFrame, reply ->
            if (message.type != WebMessageCompat.TYPE_STRING) return@addWebMessageListener
            val request = parseRadicleRequest(message.data) ?: return@addWebMessageListener
            val origin = providerOriginKey(sourceOrigin.toString())
            if (!isMainFrame || origin == null) {
                val why = if (!isMainFrame) "window.radicle is only available to the top-level page" else "Origin not permitted"
                answer(reply, request.id, RadicleProvider.Reply.Err(RadicleProvider.UNAUTHORIZED, why))
                return@addWebMessageListener
            }
            bridge.origin = origin
            bridge.reply = reply
            val doc = documents[tab.id] ?: 0
            scope.launch {
                val result = try {
                    val p = provider ?: throw IllegalStateException("provider not ready")
                    p.request(origin, request.method, request.params) { ask -> askOnTab(tab, doc, ask) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.w(TAG, "radicle request ${request.method} failed", e)
                    RadicleProvider.Reply.Err(RadicleProvider.INTERNAL, "Internal error")
                }
                answer(reply, request.id, result)
            }
        }
        WebViewCompat.addDocumentStartJavaScript(webView, radicleProviderJs(channel), setOf("*"))
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
     * longer the tab's, or the tab moves on or closes while it waits.
     */
    private suspend fun askOnTab(tab: BrowserState, doc: Int, ask: RadicleAsk): Boolean {
        fun live() = (documents[tab.id] ?: 0) == doc && tab.id !in blockedTabs
        if (!live()) return false
        val lock = promptLocks.getOrPut(tab.id) { Mutex() }
        return lock.withLock {
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
    }

    /** The tab started a new document: what the old one asked is refused. */
    fun onDocumentStarted(tab: BrowserState) {
        documents[tab.id] = (documents[tab.id] ?: 0) + 1
        withdraw(tab.id)
    }

    /** The tab closed. */
    fun onTabClosed(tabId: Long) {
        withdraw(tabId)
        documents.remove(tabId)
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

    private const val TAG = "RadicleProvider"
}

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
 */
internal fun providerOriginKey(raw: String?): String? {
    val key = permissionOriginKey(raw) ?: return null
    if (key == RadUrl.ORIGIN) return null
    if (key.startsWith("https://")) return key
    val host = key.removePrefix("http://").let { hostPort ->
        if (hostPort.startsWith("[")) hostPort.substringAfter('[').substringBefore(']')
        else hostPort.substringBefore(':')
    }.trimEnd('.')
    return key.takeIf { isLoopbackHost(host) }
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
