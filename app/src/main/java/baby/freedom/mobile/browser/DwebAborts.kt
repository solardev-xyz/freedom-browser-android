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
 * ([dwebAbortsJs]) passes each entry's URL and start time to [pageDone].
 * The start time comes as a range on this side's clock ([now]): the page's
 * `performance.now()` and `System.nanoTime()` both run on the system's
 * monotonic clock, so they differ by a constant per document, which a
 * round trip with the page brackets ([parseDoneReport]).
 *
 * An interceptor call for a request begins after the page started the
 * request, so the entry's own call — if it has begun — is one of the
 * URL's calls ([begin], answered or still working) that didn't begin
 * before the entry's start. WebView begins its calls in the order the
 * requests were made, so the earliest of those is the entry's own:
 *
 * - it began after the entry's request for certain: an answered one is
 *   simply matched; a working one was abandoned before its headers and
 *   is told to stop ([Ticket.abandon]);
 * - it may have begun just before the entry's request, inside the range:
 *   it could be another request's, still wanted — nothing is done;
 * - there is none: WebView hasn't got round to the entry's call — the
 *   next call for the URL that didn't begin before the entry's start,
 *   within [ABANDONED_TTL_MS], is answered at once without a fetch — if
 *   it began after the entry's start for certain; one that did begin
 *   before is another request's, merely slow to reach [begin], and one
 *   inside the range spends the entry but is fetched anyway. That call is then kept as answered, so should the
 *   entry have been another request's after all, this request's own
 *   entry is matched to it rather than stopping yet another call.
 *
 * An entry with no call of its own is what could misfire, so the page
 * leaves out every entry it can tell no call made — a cache hit included,
 * cross-origin too, as every tracked answer allows its timing to be read
 * ([timingAllowed]).
 *
 * So a call that began before an aborted duplicate of its URL was even
 * started is never the one cut, and anything uncertain errs towards
 * doing the fetch anyway: a call with no entry (a worker's, a frame's on
 * a non-dweb origin, a media element's range request) leaves an answered
 * record that later entries, starting after it, never match; a record
 * forgotten to keep the ledger small makes every entry that could have
 * been its own do nothing ([forgotUpTo]). One order WebView itself could
 * still get wrong: two requests for one URL whose calls it hasn't begun
 * yet, the later aborted — the earlier call is the one answered at once.
 */
internal class DwebAborts(
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val ttlMs: Long = ABANDONED_TTL_MS,
) {
    /** One interceptor call, from [begin] to [finished]; it began at [beganAt] ([now]). */
    class Ticket internal constructor(val url: String, internal val beganAt: Long) : AbandonSignal {
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
        val working = ArrayList<Ticket>()
        val answered = ArrayList<Long>() // when each answered call began, its entry still to come
        val abandoned = ArrayDeque<Ahead>() // entries ahead of their call, in arrival order
        fun idle() = working.isEmpty() && answered.isEmpty() && abandoned.isEmpty()
    }

    /**
     * An entry that came before any call it could be matched to: it came
     * at [at] ([now]), for a request started between [lo] and [hi] (with
     * the slack [pageDone] allows). A call begun before [lo] is never its
     * own; one begun by [hi] may be another request's.
     */
    private class Ahead(val at: Long, val lo: Double, val hi: Double)

    private val lock = Any()

    /**
     * Under [lock]: the latest [Ticket.beganAt] of an answered record
     * dropped to keep the ledger small. An entry whose request started
     * before it may have been that record's, so it does nothing.
     */
    private var forgotUpTo = Long.MIN_VALUE

    private val byUrl = object : LinkedHashMap<String, Calls>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Calls>): Boolean {
            if (size <= MAX_URLS || eldest.value.working.isNotEmpty()) return false
            eldest.value.answered.maxOrNull()?.let { forget(it) }
            return true
        }
    }

    /** This side's clock, the one [pageDone]'s start times are on. */
    fun clock(): Long = now()

    /**
     * An interceptor call for [url] begins; it was entered at [calledAt]
     * ([clock]). Already [Ticket.abandoned] when the page gave up on it
     * before WebView got round to the call: an entry is waiting whose
     * request certainly started before the call began. An entry whose
     * request started after it — an aborted duplicate processed while
     * this call was still in its blocking pre-[begin] work — is never
     * this call's, so it's left for the duplicate's own call (R4-M1);
     * one whose request may have started either side of it is spent on
     * it, but the call is fetched anyway, as [pageDone] does with a call
     * already begun.
     *
     * [calledAt] is read as the interceptor is entered, before any of its
     * other per-request work, some of which can block: a stamp taken
     * after that would put a live call later than it began, and an
     * aborted duplicate's entry could then take it for its own (R3-M2).
     * Never later than [now] here, and never before the request started.
     */
    fun begin(url: String, calledAt: Long = now()): Ticket {
        val key = keyOf(url)
        val ticket = Ticket(key, minOf(calledAt, now()))
        val gone = synchronized(lock) {
            val calls = byUrl.getOrPut(key) { Calls() }
            prune(calls)
            val ahead = calls.abandoned.firstOrNull { ticket.beganAt >= it.lo }
            if (ahead != null) calls.abandoned.remove(ahead)
            if (ahead != null && ticket.beganAt > ahead.hi) {
                // Kept as answered: if the entry spent here was another
                // request's after all (one no call of ours made), this
                // call's own request gets the throwaway answer and its
                // entry comes too — matched to this record, it does
                // nothing, instead of queuing an abandon for the next
                // call and passing the mistake on (R2-F2).
                answer(calls, ticket.beganAt)
                true
            } else {
                calls.working.add(ticket)
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
            if (!ticket.abandoned) answer(calls, ticket.beganAt)
            if (calls.idle()) byUrl.remove(ticket.url)
        }
    }

    /**
     * The page is done with a request for [url] (a Resource Timing entry)
     * that it started no earlier than [startedLo] and no later than
     * [startedHi], on [now]'s clock; [Double.NEGATIVE_INFINITY] for an
     * unknown lower bound.
     */
    fun pageDone(url: String, startedLo: Double, startedHi: Double) {
        if (startedHi.isNaN() || startedLo.isNaN() || startedLo > startedHi + 2 * CLOCK_SLACK_MS) return
        // [Ticket.beganAt] is whole milliseconds, rounded down: a call may
        // have begun after the request started if it's at most a
        // millisecond before the lower bound, and certainly did if it's
        // past the upper one.
        val lo = startedLo - 1 - CLOCK_SLACK_MS
        val hi = startedHi + CLOCK_SLACK_MS
        val key = keyOf(url)
        val stop: Ticket? = synchronized(lock) {
            // A dropped record may have been this entry's own call.
            if (forgotUpTo != Long.MIN_VALUE && forgotUpTo.toDouble() >= lo) return
            val calls = byUrl.getOrPut(key) { Calls() }
            prune(calls)
            // The earliest call that may have begun after the request started.
            val working = calls.working.filter { it.beganAt >= lo }.minByOrNull { it.beganAt }
            val answered = calls.answered.filter { it >= lo }.minOrNull()
            val first = listOfNotNull(working?.beganAt, answered).minOrNull()
            when {
                first == null -> {
                    if (calls.abandoned.size < MAX_COUNT) calls.abandoned.addLast(Ahead(now(), lo, hi))
                    null
                }
                first <= hi -> null // maybe another request's, still wanted
                answered == first -> { calls.answered.remove(answered); null }
                else -> working!!.also { calls.working.remove(it) }
            }.also { if (calls.idle()) byUrl.remove(key) }
        }
        stop?.abandon()
    }

    /** Calls in flight, for tests. */
    internal fun working(url: String): Int = synchronized(lock) { byUrl[keyOf(url)]?.working?.size ?: 0 }

    // Under [lock]: a call begun at [beganAt] has its answer; its entry is still to come.
    private fun answer(calls: Calls, beganAt: Long) {
        if (calls.answered.size >= MAX_COUNT) {
            val oldest = calls.answered.minOrNull()!!
            calls.answered.remove(oldest)
            forget(oldest)
        }
        calls.answered.add(beganAt)
    }

    // Under [lock].
    private fun forget(beganAt: Long) {
        if (beganAt > forgotUpTo) forgotUpTo = beganAt
    }

    // Under [lock]: entries whose call never came are forgotten.
    private fun prune(calls: Calls) {
        val t = now()
        calls.abandoned.removeAll { t - it.at !in 0 until ttlMs }
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

        /** `performance.now()`'s coarsening (0.1 ms, jittered), with room to spare. */
        internal const val CLOCK_SLACK_MS = 1.0

        /** The URL a request and its Resource Timing entry are matched by: no fragment. */
        fun keyOf(url: String): String = url.substringBefore('#')

        /**
         * Is [url] a request the page's entries can be matched to: a
         * subresource on a virtual dweb origin ([VirtualOrigin])?
         */
        fun tracks(url: String, mainFrame: Boolean): Boolean =
            !mainFrame && url.startsWith("https://") && VirtualOrigin.parseHostOfUrl(url) != null

        /**
         * [headers] with `Timing-Allow-Origin: *`, for a tracked
         * request's answer. Without it a cross-origin document's entry
         * for it is opaque: `deliveryType` reads empty, so a later
         * memory-cache hit on it — no interceptor call — reads like a
         * request the page gave up on, and its stray "done" would stop
         * the next real request for the URL (R2-F1). With it the hit
         * says `cache` and is left out ([dwebAbortsJs]); a request
         * aborted before its headers has no response, so it stays
         * opaque and is still reported.
         *
         * Timing-Allow-Origin also opens an entry's `serverTiming` to
         * a cross-origin page, which CORS never did (`Server-Timing`
         * isn't a CORS-safelisted header). So any `Server-Timing` a
         * gateway passed through is dropped from the answer (R3-M1):
         * it's the gateway's own metadata (a CDN's round-trip time to
         * the user, say), not the content's. What's left — sizes and
         * timings of a body any page may already read whole under the
         * `Access-Control-Allow-Origin: *` dweb content carries — shows
         * a page nothing new.
         */
        fun timingAllowed(headers: Map<String, String>?): Map<String, String> =
            headers.orEmpty().filterKeys {
                !it.equals(TIMING_ALLOW_ORIGIN, ignoreCase = true) &&
                    !it.equals(SERVER_TIMING, ignoreCase = true)
            } + (TIMING_ALLOW_ORIGIN to "*")

        private const val TIMING_ALLOW_ORIGIN = "Timing-Allow-Origin"
        private const val SERVER_TIMING = "Server-Timing"

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
                WebViewCompat.addWebMessageListener(webView, channel, rules) { _, message, sourceOrigin, _, reply ->
                    val at = aborts.clock()
                    if (sourceOrigin.scheme != "https") return@addWebMessageListener
                    if (VirtualOrigin.parseHostOfUrl("$sourceOrigin/") == null) return@addWebMessageListener
                    if (message.type != WebMessageCompat.TYPE_STRING) return@addWebMessageListener
                    val report = parseDoneReport(message.data ?: return@addWebMessageListener, at)
                        ?: return@addWebMessageListener
                    // The page brackets its clock against ours with this.
                    runCatching { reply.postMessage(report.reply) }
                    for (e in report.entries) aborts.pageDone(e.url, e.startedLo, e.startedHi)
                }
                WebViewCompat.addDocumentStartJavaScript(webView, dwebAbortsJs(channel), rules)
            }.onFailure { return null }
            return aborts
        }
    }
}

/** A request the page is done with, started between [startedLo] and [startedHi] on [DwebAborts]' clock. */
internal data class DoneEntry(val url: String, val startedLo: Double, val startedHi: Double)

/** A parsed [dwebAbortsJs] report, and the [reply] that tells the page when it came. */
internal class DoneReport(val entries: List<DoneEntry>, val reply: String)

/**
 * A [dwebAbortsJs] report, received at [receivedAt] on [DwebAborts]' clock.
 *
 * The first line is `sent lo hi`: the page's `performance.now()` when it
 * sent the report, and the bounds it has so far on our clock minus its
 * own (`-` for none yet), from earlier replies. Each later line is
 * `start url`, the entry's `startTime` and URL. Our clock minus the
 * page's is a constant `c`; a reply carries `receivedAt` and `sent` back,
 * and since a message arrives after it's sent, `receivedAt - sent` is an
 * upper bound on `c` and `receivedAt` minus the page's time on getting
 * the reply a lower one. Bounds that cross, a header that doesn't parse:
 * null, nothing done. Entries not on a virtual origin
 * ([DwebAborts.tracks]), and any past [MAX_REPORT_URLS], are left out.
 */
internal fun parseDoneReport(data: String, receivedAt: Long): DoneReport? {
    val lines = data.lineSequence().iterator()
    if (!lines.hasNext()) return null
    val head = lines.next().split(' ')
    if (head.size != 3) return null
    val sent = head[0].toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
    fun bound(s: String) = if (s == "-") null else s.toDoubleOrNull()?.takeIf { it.isFinite() }
    val lo = if (head[1] == "-") Double.NEGATIVE_INFINITY else bound(head[1]) ?: return null
    val pageHi = if (head[2] == "-") Double.POSITIVE_INFINITY else bound(head[2]) ?: return null
    // [receivedAt] is rounded down to the millisecond: + 1 keeps it an upper bound.
    val hi = minOf(pageHi, receivedAt + 1 - sent)
    if (lo > hi + 2 * DwebAborts.CLOCK_SLACK_MS) return null
    val entries = lines.asSequence()
        .mapNotNull { line ->
            val space = line.indexOf(' ')
            if (space < 0) return@mapNotNull null
            val start = line.substring(0, space).toDoubleOrNull()?.takeIf { it.isFinite() } ?: return@mapNotNull null
            val url = line.substring(space + 1)
            if (url.length > MAX_REPORT_URL_LENGTH || !DwebAborts.tracks(url, mainFrame = false)) return@mapNotNull null
            DoneEntry(url, start + lo, start + hi)
        }
        .take(MAX_REPORT_URLS)
        .toList()
    return DoneReport(entries, "$receivedAt ${head[0]}")
}

private const val MAX_REPORT_URLS = 1024
private const val MAX_REPORT_URL_LENGTH = 8192

/**
 * The page side of [DwebAborts]: a `PerformanceObserver` for `resource`
 * entries, set up before the page's own scripts run with the natives it
 * needs saved, that posts the start time and URL of each request the
 * document is done with — those on a virtual origin only, a batch per
 * callback, in [parseDoneReport]'s format. Each reply brackets the
 * native clock against `performance.now()`; one empty report at document
 * start gets the first. Left out are entries no interceptor call made:
 * answered by a service worker (`workerStart`) or from a cache
 * (`deliveryType`). A WebView too old to say `deliveryType` (before
 * Chromium 109) can't tell a memory-cache hit from a request, so there
 * the script does nothing. It builds its report as a string only, never
 * in a page-realm array or object, and reads entries through saved
 * getters only, so no page-defined accessor sees it. The channel object
 * is taken off `window` at once, so the page never sees it; it only
 * exists on the dweb origins, which name this browser already.
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
  var PO = w.PerformanceObserver, perf = w.performance, Perf = w.Performance, ME = w.MessageEvent;
  if (typeof PO !== 'function' || !PO.prototype || !perf || !Perf || !ME) return;
  var fcall = Function.prototype.call, fbind = Function.prototype.bind, gopd = Object.getOwnPropertyDescriptor,
      gpo = Object.getPrototypeOf;
  var un = function (f) { return typeof f === 'function' ? fbind.call(fcall, f) : null; };
  var prop = function (P, n) {
    var x = null;
    for (var p = P; p && !x; p = gpo(p)) x = gopd(p, n);
    return x ? un(x.get) : null;
  };
  var RT = w.PerformanceResourceTiming && w.PerformanceResourceTiming.prototype;
  var send = un(port.postMessage), observe = un(PO.prototype.observe), clock = un(Perf.prototype.now),
      list = un(w.PerformanceObserverEntryList && w.PerformanceObserverEntryList.prototype.getEntries),
      name = prop(RT, 'name'), start = prop(RT, 'startTime'), workerStart = prop(RT, 'workerStart'),
      delivery = prop(RT, 'deliveryType'), data = prop(ME.prototype, 'data'),
      at = un(String.prototype.indexOf), cut = un(String.prototype.substring);
  if (!send || !observe || !clock || !list || !name || !start || !workerStart || !delivery || !data || !at || !cut) return;
  var suffixes = [$suffixes], lo = '-', hi = '-';
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
  function post(body) { send(port, clock(perf) + ' ' + lo + ' ' + hi + body); }
  try {
    port.onmessage = function (ev) {
      try {
        // The platform hands the channel a plain object with its own
        // `data` (read as it is, which runs nothing of the page's); a
        // real MessageEvent's goes through the getter saved here.
        var got = clock(perf), own = ev ? gopd(ev, 'data') : null, d = own ? own.value : ev ? data(ev) : null;
        if (typeof d !== 'string') return;
        var sp = at(d, ' ');
        if (sp < 0) return;
        var t = +cut(d, 0, sp), sent = +cut(d, sp + 1), l = t - got, h = t + 1 - sent;
        if (t !== t || sent !== sent) return;
        if (lo === '-' || l > lo) lo = l;
        if (hi === '-' || h < hi) hi = h;
      } catch (x) {}
    };
    var po = new PO(function (l) {
      try {
        var es = list(l), body = '';
        for (var i = 0; i < es.length; i++) {
          var e = es[i], u = name(e), dt = delivery(e);
          if (workerStart(e) > 0 || dt === 'cache' || dt === 'navigational-prefetch' || !dweb(u)) continue;
          body += '\n' + start(e) + ' ' + u;
        }
        if (body) post(body);
      } catch (x) {}
    });
    observe(po, { type: 'resource' });
    post('');
  } catch (e) {}
})();
"""
}
