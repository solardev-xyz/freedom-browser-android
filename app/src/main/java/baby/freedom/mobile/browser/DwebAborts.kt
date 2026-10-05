package baby.freedom.mobile.browser

import android.webkit.WebView
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * What a dweb fetch ([fetchWithRetry]) asks while it works: has the page
 * given up on the request it is answering? Then the fetch stops — its
 * connection to the gateway is cut, its retry back-off cut short — and
 * the interceptor hands back a throwaway answer at once.
 */
internal interface AbandonSignal {
    /** Has the page given up on the request? */
    val abandoned: Boolean

    /**
     * Run [action] as soon as the page gives up (at once if it already
     * has), on the thread that learns it; never under a lock of the
     * caller's. Returns the call that unregisters it.
     */
    fun onAbandon(action: () -> Unit): () -> Unit

    /** Wait [ms], or less if the page gives up meanwhile: true then. */
    fun sleep(ms: Long): Boolean
}

/**
 * Tells a tab's dweb fetches when the page has given up on them.
 *
 * WebView gives `shouldInterceptRequest` no cancellation signal. A page
 * that aborts a `fetch()`/XHR before its headers (a video player
 * scrubbing: dozens of segment loads started and aborted within a
 * second) leaves the interceptor working for nobody: WebView still calls
 * it for every aborted request — late, in order, on the few threads of
 * its worker pool — and each call waits on the gateway for headers (and
 * retries) before it returns, so the request the page does want queues
 * behind all of them. Only once a body is handed over does WebView close
 * it, which cuts the gateway connection ([DisconnectOnCloseInputStream]).
 *
 * The page knows, though: Chromium writes a Resource Timing entry for
 * every request a document finishes with — loaded, failed or aborted,
 * before its headers too. A document-start script on the dweb origins
 * ([dwebAbortsJs]) passes the URL of each to [pageDone], and that is
 * matched against the tab's interceptor calls for the same URL
 * ([begin]/[finished]):
 *
 * - a call already answered: the page is done with its answer — nothing
 *   to do (the body's own close handles a body aborted midway);
 * - else a call still working: it was abandoned before its headers —
 *   the oldest is told to stop ([Ticket.abandon]);
 * - else no call yet: WebView hasn't got round to calling the
 *   interceptor for it — the next call for the URL, within
 *   [ABANDONED_TTL_MS], is answered at once without a fetch.
 *
 * Every non-main-frame request on a virtual origin is counted, whatever
 * its method, so the counts line up with the page's entries. What only
 * ever errs towards doing the fetch anyway: a request with no entry (a
 * worker's, a frame's on a non-dweb origin, a media element's range
 * requests) just leaves an answered call that a later entry is matched
 * to first; the script leaves out entries no interceptor call made (a
 * service worker's answer, a cached one). Two calls for the same URL in
 * flight at once, one of them abandoned, are told apart by order only:
 * the one WebView called first is taken as the one the page gave up on
 * first.
 */
internal class DwebAborts(
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val ttlMs: Long = ABANDONED_TTL_MS,
) {
    /** One interceptor call, from [begin] to [finished]. */
    class Ticket internal constructor(val url: String) : AbandonSignal {
        private val latch = CountDownLatch(1)
        private val actions = ArrayList<() -> Unit>()

        @Volatile
        override var abandoned = false
            private set

        override fun onAbandon(action: () -> Unit): () -> Unit {
            val now = synchronized(actions) {
                if (abandoned) true else { actions.add(action); false }
            }
            if (now) action()
            return { synchronized(actions) { actions.remove(action) } }
        }

        override fun sleep(ms: Long): Boolean {
            if (ms <= 0) return abandoned
            return try {
                latch.await(ms, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                true
            }
        }

        internal fun abandon() {
            val run = synchronized(actions) {
                if (abandoned) return
                abandoned = true
                actions.toList().also { actions.clear() }
            }
            latch.countDown()
            for (a in run) runCatching { a() }
        }
    }

    private class Calls {
        val working = ArrayDeque<Ticket>()
        var answered = 0
        val abandonedAt = ArrayDeque<Long>() // entries ahead of their call
        fun idle() = working.isEmpty() && answered == 0 && abandonedAt.isEmpty()
    }

    private val lock = Any()
    private val byUrl = object : LinkedHashMap<String, Calls>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Calls>): Boolean =
            size > MAX_URLS && eldest.value.working.isEmpty()
    }

    /**
     * An interceptor call for [url] begins. Already [Ticket.abandoned]
     * when the page gave up on it before WebView got round to the call.
     */
    fun begin(url: String): Ticket {
        val key = keyOf(url)
        val ticket = Ticket(key)
        val gone = synchronized(lock) {
            val calls = byUrl.getOrPut(key) { Calls() }
            prune(calls)
            if (calls.abandonedAt.isNotEmpty()) {
                calls.abandonedAt.removeFirst()
                if (calls.idle()) byUrl.remove(key)
                true
            } else {
                calls.working.addLast(ticket)
                false
            }
        }
        if (gone) ticket.abandon()
        return ticket
    }

    /** [ticket]'s call has returned its answer to WebView (or thrown). */
    fun finished(ticket: Ticket) {
        synchronized(lock) {
            val calls = byUrl[ticket.url] ?: return
            if (!calls.working.remove(ticket)) return // abandoned: its entry is spent
            if (!ticket.abandoned) calls.answered = minOf(calls.answered + 1, MAX_COUNT)
            if (calls.idle()) byUrl.remove(ticket.url)
        }
    }

    /** The page is done with a request for [url] (a Resource Timing entry). */
    fun pageDone(url: String) {
        val key = keyOf(url)
        val stop: Ticket? = synchronized(lock) {
            val calls = byUrl.getOrPut(key) { Calls() }
            prune(calls)
            when {
                calls.answered > 0 -> { calls.answered--; null }
                calls.working.isNotEmpty() -> calls.working.removeFirst()
                else -> {
                    if (calls.abandonedAt.size < MAX_COUNT) calls.abandonedAt.addLast(now())
                    null
                }
            }.also { if (calls.idle()) byUrl.remove(key) }
        }
        stop?.abandon()
    }

    /** Calls in flight, for tests. */
    internal fun working(url: String): Int = synchronized(lock) { byUrl[keyOf(url)]?.working?.size ?: 0 }

    // Under [lock]: entries whose call never came are forgotten.
    private fun prune(calls: Calls) {
        val t = now()
        while (calls.abandonedAt.isNotEmpty() && t - calls.abandonedAt.first() !in 0 until ttlMs) {
            calls.abandonedAt.removeFirst()
        }
    }

    companion object {
        /**
         * How long a page's "done" waits for its interceptor call to come
         * — WebView's backlog of calls for requests the page has already
         * dropped. Past it the entry is dropped, so a stray one can't
         * stop a later request for the same URL.
         */
        const val ABANDONED_TTL_MS = 10_000L
        private const val MAX_URLS = 512
        private const val MAX_COUNT = 64

        /** The URL a request and its Resource Timing entry are matched by: no fragment. */
        fun keyOf(url: String): String = url.substringBefore('#')

        /**
         * Is [url] a request the page's entries can be matched to: a
         * subresource on a virtual dweb origin ([VirtualOrigin])?
         */
        fun tracks(url: String, mainFrame: Boolean): Boolean =
            !mainFrame && url.startsWith("https://") && VirtualOrigin.parseHostOfUrl(url) != null

        fun isSupported(): Boolean = runCatching {
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
                WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        }.getOrDefault(false)

        /**
         * Register [webView]'s channel and script, before its first load;
         * null where WebView can't (the tab's fetches then run as before).
         * Only the dweb origins get either.
         */
        fun install(webView: WebView): DwebAborts? {
            if (!isSupported()) return null
            val aborts = DwebAborts()
            val channel = newBottomUiChannelName()
            val rules = VirtualOrigin.SUFFIXES.map { "https://*.$it" }.toSet()
            runCatching {
                WebViewCompat.addWebMessageListener(webView, channel, rules) { _, message, sourceOrigin, _, _ ->
                    if (sourceOrigin.scheme != "https") return@addWebMessageListener
                    if (VirtualOrigin.parseHostOfUrl("$sourceOrigin/") == null) return@addWebMessageListener
                    if (message.type != WebMessageCompat.TYPE_STRING) return@addWebMessageListener
                    for (url in parseDoneReport(message.data ?: return@addWebMessageListener)) aborts.pageDone(url)
                }
                WebViewCompat.addDocumentStartJavaScript(webView, dwebAbortsJs(channel), rules)
            }.onFailure { return null }
            return aborts
        }
    }
}

/**
 * The URLs in a [dwebAbortsJs] report: one per line, each a subresource
 * on a virtual origin ([DwebAborts.tracks]); anything else, and anything
 * past [MAX_REPORT_URLS], is left out.
 */
internal fun parseDoneReport(data: String): List<String> =
    data.lineSequence()
        .filter { it.length <= MAX_REPORT_URL_LENGTH && DwebAborts.tracks(it, mainFrame = false) }
        .take(MAX_REPORT_URLS)
        .toList()

private const val MAX_REPORT_URLS = 1024
private const val MAX_REPORT_URL_LENGTH = 8192

/**
 * The page side of [DwebAborts]: a `PerformanceObserver` for `resource`
 * entries, set up before the page's own scripts run with the natives it
 * needs saved, that posts the URLs of the requests the document is done
 * with — those on a virtual origin only, one per line, a batch per
 * callback. Left out are entries no interceptor call made: answered by a
 * service worker (`workerStart`) or from a cache (`deliveryType`). The
 * channel object is taken off `window` at once, so the page never sees
 * it; it only exists on the dweb origins, which name this browser
 * already.
 */
internal fun dwebAbortsJs(channel: String): String {
    require(Regex("[a-z]{8,64}").matches(channel)) { "channel must be lower-case letters" }
    val suffixes = VirtualOrigin.SUFFIXES.joinToString(",") { "'.$it/'" }
    return """
(function () {
  var w = window, N = '$channel', port = w[N];
  if (port === undefined) return;
  try { delete w[N]; } catch (e) {}
  if (!port || typeof port.postMessage !== 'function') return;
  var PO = w.PerformanceObserver;
  if (typeof PO !== 'function' || !PO.prototype) return;
  var fcall = Function.prototype.call, fbind = Function.prototype.bind, gopd = Object.getOwnPropertyDescriptor,
      gpo = Object.getPrototypeOf;
  var un = function (f) { return typeof f === 'function' ? fbind.call(fcall, f) : null; };
  var prop = function (P, n) {
    var x = null;
    for (var p = P; p && !x; p = gpo(p)) x = gopd(p, n);
    return (x && un(x.get)) || function (o) { return o[n]; };
  };
  var RT = w.PerformanceResourceTiming && w.PerformanceResourceTiming.prototype;
  var send = un(port.postMessage), observe = un(PO.prototype.observe),
      list = un(w.PerformanceObserverEntryList && w.PerformanceObserverEntryList.prototype.getEntries),
      name = prop(RT, 'name'), workerStart = prop(RT, 'workerStart'), delivery = prop(RT, 'deliveryType'),
      at = un(String.prototype.indexOf), cut = un(String.prototype.substring), join = un(Array.prototype.join);
  if (!send || !observe || !list || !at || !cut || !join) return;
  var suffixes = [$suffixes];
  function dweb(u) {
    if (typeof u !== 'string' || cut(u, 0, 8) !== 'https://') return false;
    var slash = at(u, '/', 8);
    var host = (slash < 0 ? cut(u, 8) : cut(u, 8, slash)) + '/';
    for (var i = 0; i < suffixes.length; i++) {
      var s = suffixes[i], k = host.length - s.length;
      if (k > 0 && cut(host, k) === s) return true;
    }
    return false;
  }
  try {
    var po = new PO(function (l) {
      try {
        var es = list(l), done = [], n = 0;
        for (var i = 0; i < es.length; i++) {
          var e = es[i], u = name(e), d = delivery(e);
          if (workerStart(e) > 0 || d === 'cache' || d === 'navigational-prefetch' || !dweb(u)) continue;
          done[n++] = u;
        }
        if (n) send(port, join(done, '\n'));
      } catch (x) {}
    });
    observe(po, { type: 'resource' });
  } catch (e) {}
})();
"""
}
