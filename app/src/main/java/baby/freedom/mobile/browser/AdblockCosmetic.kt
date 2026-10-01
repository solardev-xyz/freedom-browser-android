package baby.freedom.mobile.browser

import android.webkit.WebView
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Cosmetic filtering (#126): hiding the ad slots, cookie notices and
 * the like that blocking requests leaves behind, with the lists'
 * `##selector` rules ([AdblockEngine]).
 *
 * A document-start script ([adblockCosmeticJs]) in every http(s) frame
 * asks through its own message channel for the frame's first CSS
 * ([COSMETIC_HELLO]) — its host's rules plus the generic rules no class
 * or id keys — then reports the `.class` and `#id` names it finds in
 * the DOM as the page builds it ([COSMETIC_TOKENS]), and gets back the
 * generic rules keyed by them. Kotlin answers from the frame's origin as
 * the platform reports it (never a URL the page claims), with the
 * allowlist and exception filters applied, so an allowlisted site gets
 * [COSMETIC_OFF] and its frames stop listening.
 *
 * The CSS goes in as constructed stylesheets on
 * `document.adoptedStyleSheets`: not subject to the page's `style-src`
 * CSP (an inserted `<style>` can be refused, and report the refusal to
 * the site), and carrying no marker of ours. As with the bottom-UI
 * detector (#69), the channel object the platform puts on `window` is
 * taken off it before the page's own scripts run, and has a random name.
 */
internal object AdblockCosmetic {
    /** For a frame's first answer held back until the first engine build (#192). */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun isSupported(): Boolean = runCatching {
        WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
    }.getOrDefault(false)

    /**
     * Register the channel and the script on [webView], before its first
     * load. [pageUrl] is the tab's top-level document as the request
     * interceptor last saw it; [private] whether the tab is private.
     */
    fun install(webView: WebView, private: Boolean, pageUrl: () -> String?) {
        if (!isSupported()) return
        val channel = newBottomUiChannelName()
        WebViewCompat.addWebMessageListener(webView, channel, COSMETIC_ORIGIN_RULES) { _, message, sourceOrigin, _, reply ->
            if (sourceOrigin.scheme != "https" && sourceOrigin.scheme != "http") return@addWebMessageListener
            if (message.type != WebMessageCompat.TYPE_STRING) return@addWebMessageListener
            val data = message.data ?: return@addWebMessageListener
            val frameOrigin = sourceOrigin.toString()
            val page = pageUrl() ?: frameOrigin
            when {
                data == COSMETIC_HELLO -> {
                    val answer = {
                        val css = Adblock.initialCosmetics(frameOrigin, page, private)
                        reply.postMessage(if (css == null) COSMETIC_OFF else COSMETIC_CSS + css)
                    }
                    // Before the first engine build, "no engine" would
                    // answer [COSMETIC_OFF] and the frame would stop
                    // asking for good: a tab restored after process
                    // death would get no hiding at all (#192). Answer
                    // once the build lands (or its deadline passes)
                    // instead; this is the main thread, so suspend —
                    // the wait holds no thread, however many frames ask.
                    if (Adblock.firstBuildPending) {
                        scope.launch {
                            Adblock.awaitFirstBuild()
                            // The frame may be gone by now (tab closed).
                            runCatching { answer() }
                        }
                    } else {
                        answer()
                    }
                }
                data.startsWith(COSMETIC_TOKENS) -> {
                    val tokens = parseCosmeticTokens(data) ?: return@addWebMessageListener
                    val css = Adblock.cosmeticsForTokens(tokens, frameOrigin, page, private)
                    if (css.isNotEmpty()) reply.postMessage(COSMETIC_CSS + css)
                }
            }
        }
        WebViewCompat.addDocumentStartJavaScript(webView, adblockCosmeticJs(channel), COSMETIC_ORIGIN_RULES)
    }
}

/** Every origin: the rule grammar can't say "http(s) only" (see [BOTTOM_UI_ORIGIN_RULES]); the listener and script check. */
private val COSMETIC_ORIGIN_RULES: Set<String> = setOf("*")

/** A frame's first message: "what do I hide?" */
internal const val COSMETIC_HELLO = "c"

/** A frame's report of new class / id names: this, then one `.name` / `#name` per line. */
internal const val COSMETIC_TOKENS = "t\n"

/** Kotlin's answer: CSS follows (possibly none yet — keep reporting). */
internal const val COSMETIC_CSS = "1"

/** Kotlin's answer: no hiding in this frame (allowlisted, excepted, or blocking off) — stop. */
internal const val COSMETIC_OFF = "0"

/**
 * How often, in ms, a frame puts back hiding sheets the page dropped by
 * assigning `document.adoptedStyleSheets` without touching the DOM (a
 * DOM change puts them back at once).
 */
internal const val COSMETIC_RECHECK_MS = 2000

/** Most names one report may carry; the script sends bigger finds in several. */
internal const val COSMETIC_MAX_TOKENS = 1000

private const val COSMETIC_MAX_TOKEN_LENGTH = 256

/** The names a [COSMETIC_TOKENS] report carries, or `null` if it's malformed. */
internal fun parseCosmeticTokens(data: String): List<String>? {
    if (!data.startsWith(COSMETIC_TOKENS)) return null
    val tokens = data.substring(COSMETIC_TOKENS.length).split('\n')
    if (tokens.size > COSMETIC_MAX_TOKENS) return null
    return tokens.filter {
        it.length in 2..COSMETIC_MAX_TOKEN_LENGTH && (it[0] == '.' || it[0] == '#')
    }
}

/**
 * The page side: see [AdblockCosmetic]. Names are collected from the
 * document as it is parsed and changed (a `MutationObserver` over added
 * elements and `class` / `id` changes), and reported in batches at most
 * every 50 ms, each name once per document. A page that assigns
 * `document.adoptedStyleSheets` drops our sheets with its own; they are
 * put back with the next batch of DOM changes, and checked for every
 * [COSMETIC_RECHECK_MS] besides (R1-F3). Rules reach the document's own
 * tree only, not elements inside a shadow root. It stops for good on
 * [COSMETIC_OFF].
 *
 * **Nothing the page wraps later sees it** (#368, the bottom-UI
 * detector's technique, #146/#198). Every function it calls after
 * document start — in the 50 ms flush, the recheck, the
 * `MutationObserver` callback and the reply handler, all of which run
 * once the page's own scripts have — was saved at document start and is
 * called through a `Function.prototype.call` bound then:
 * `querySelectorAll`, the `id`/`classList`/`DOMTokenList.length`/
 * `Node.nodeType`/`NodeList.length`/`Document.documentElement` getters,
 * the `MutationRecord` `type`/`target`/`addedNodes` getters,
 * `MutationObserver.prototype.observe`/`disconnect`,
 * `CSSStyleSheet.prototype.replaceSync`, the `adoptedStyleSheets`
 * getter and setter, `RegExp.prototype.exec` and
 * `String.prototype.substring`, plus the timer, `MutationObserver` and
 * `CSSStyleSheet` constructors. No `Array` or `Set` method runs: its
 * lists and its set of names already reported are prototype-less
 * objects walked with index loops, and the report is built as text. The
 * two sequences the platform reads by iterating — the sheets handed to
 * the `adoptedStyleSheets` setter and `observe()`'s `attributeFilter` —
 * are prototype-less iterables whose iterators were built at document
 * start, not arrays (which would be walked through the live
 * `Array.prototype[Symbol.iterator]`), and `observe()`'s options object
 * has no prototype. The reply's `data` is read as the plain object's own
 * property, or through the saved `MessageEvent.data` getter. So a page
 * that wraps any of these catches no call and is never handed one of its
 * sheets or callbacks; what it can still see is
 * the sheets themselves in `document.adoptedStyleSheets`.
 */
internal fun adblockCosmeticJs(channel: String): String {
    require(Regex("[a-z]{8,64}").matches(channel)) { "channel must be lower-case letters" }
    return """
(function () {
  var w = window, d = document, N = '$channel', port = w[N];
  if (port === undefined) return;
  try { delete w[N]; } catch (e) {}
  if (!port || typeof port.postMessage !== 'function') return;
  var proto = w.location.protocol;
  if (proto !== 'http:' && proto !== 'https:') return;
  // Natives, saved now, before any page script (#368, as the bottom-UI
  // detector does, #146): un(f)(o, …) is f.call(o, …) through a `call`
  // bound now, so neither a wrapped method or getter nor a wrapped
  // `Function.prototype.call` sees a call made after the page has run.
  // Where the platform has no such native (never in Chromium) the live
  // property is used instead.
  var fcall = Function.prototype.call, fbind = Function.prototype.bind, gopd = Object.getOwnPropertyDescriptor,
      gpo = Object.getPrototypeOf, mk = Object.create;
  var un = function (f) { return typeof f === 'function' ? fbind.call(fcall, f) : null; };
  var method = function (P, n) {
    return (P && un(P[n])) || function (o, a, b) { return o[n](a, b); };
  };
  var prop = function (P, n, set) {
    var x = null;
    for (var p = P; p && !x; p = gpo(p)) x = gopd(p, n);
    var f = x && un(set ? x.set : x.get);
    return f || (set ? function (o, v) { o[n] = v; } : function (o) { return o[n]; });
  };
  var P = function (C) { return C && C.prototype; };
  var setT = w.setTimeout, MO = w.MutationObserver, Sheet = w.CSSStyleSheet;
  var EP = P(w.Element), MR = P(w.MutationRecord), MOP = P(MO), DOC = P(w.Document);
  var dom = {
    id: prop(EP, 'id'), classes: prop(EP, 'classList'), tokens: prop(P(w.DOMTokenList), 'length'),
    type: prop(P(w.Node), 'nodeType'), html: prop(DOC, 'documentElement'),
    all: method(EP, 'querySelectorAll'), count: prop(P(w.NodeList), 'length'),
    recType: prop(MR, 'type'), recTarget: prop(MR, 'target'), added: prop(MR, 'addedNodes'),
    observe: method(MOP, 'observe'), disconnect: method(MOP, 'disconnect'),
    replace: method(P(Sheet), 'replaceSync'), data: prop(P(w.MessageEvent), 'data'), exec: un(RegExp.prototype.exec), cut: un(String.prototype.substring)
  };
  var desc = DOC && gopd(DOC, 'adoptedStyleSheets');
  var getSheets = desc && un(desc.get), setSheets = desc && un(desc.set);
  // Lists with no prototype, and index loops: no Array or Set method runs.
  function list() { var l = mk(null); l.n = 0; return l; }
  function put(l, x) { l[l.n++] = x; }
  var seen = mk(null), found = list(), queued = list(), sheets = list(), timer = 0, on = true, observer = null, SPACE = /\s/;
  // A sequence (the sheets the setter takes, the observer's attribute
  // filter) is read by iterating it: an array would be walked through
  // Array.prototype's iterator, which the page can replace. So each is
  // its own iterable, built now, whose iterator calls nothing but these
  // closures.
  var ITER = typeof Symbol === 'function' ? Symbol.iterator : null;
  function iterable(l) {
    if (!ITER) { var a = []; for (var k = 0; k < l.n; k++) a[k] = l[k]; return a; }
    var i = 0, it = mk(null), s = mk(null);
    it.next = function () { return i < l.n ? { value: l[i++], done: false } : { value: undefined, done: true }; };
    s[ITER] = function () { i = 0; return it; };
    return s;
  }
  // observe()'s options, built now and with no prototype: the platform
  // reads every option it knows, and one missing here would be looked
  // up on Object.prototype.
  var FILTER = list(); put(FILTER, 'id'); put(FILTER, 'class');
  var moOpts = mk(null);
  moOpts.childList = true; moOpts.subtree = true; moOpts.attributes = true; moOpts.attributeFilter = iterable(FILTER);
  function keep() {
    if (!sheets.n || !getSheets || !setSheets) return;
    try {
      var cur = getSheets(d), next = list(), missing = false;
      for (var i = 0; i < cur.length; i++) put(next, cur[i]);
      for (var j = 0; j < sheets.n; j++) {
        var has = false;
        for (var k = 0; k < next.n && !has; k++) has = next[k] === sheets[j];
        if (!has) { put(next, sheets[j]); missing = true; }
      }
      if (missing) setSheets(d, iterable(next));
    } catch (e) {}
  }
  function apply(css) {
    if (!css || !Sheet || !getSheets) return;
    try {
      var s = new Sheet();
      dom.replace(s, css);
      put(sheets, s);
    } catch (e) { return; }
    keep();
  }
  function watch() { if (!on) return; keep(); setT(watch, $COSMETIC_RECHECK_MS); }
  function add(t) { if (!seen[t]) { seen[t] = true; put(found, t); } }
  function names(el) {
    var id = dom.id(el);
    if (typeof id === 'string' && id && !(dom.exec ? dom.exec(SPACE, id) : SPACE.exec(id))) add('#' + id);
    var cl = dom.classes(el);
    if (cl) for (var i = 0, n = dom.tokens(cl); i < n; i++) add('.' + cl[i]);
  }
  function element(n) { return !!n && dom.type(n) === 1; }
  function scan(root) {
    if (!element(root)) return;
    names(root);
    var all = dom.all(root, '[id],[class]');
    for (var i = 0, n = dom.count(all); i < n; i++) names(all[i]);
  }
  function flush() {
    timer = 0;
    if (!on) return;
    keep();
    var roots = queued; queued = list();
    for (var i = 0; i < roots.n; i++) scan(roots[i]);
    // Reported as built text, in batches of at most $COSMETIC_MAX_TOKENS.
    for (var s = 0; s < found.n; s += $COSMETIC_MAX_TOKENS) {
      var m = 't';
      for (var j = s; j < found.n && j < s + $COSMETIC_MAX_TOKENS; j++) m += '\n' + found[j];
      port.postMessage(m);
    }
    found = list();
  }
  function later() { if (!timer) timer = setT(flush, 50); }
  port.onmessage = function (e) {
    // An own `data` (a plain object) is read as it is, which runs
    // nothing of the page's; a real MessageEvent's through the getter
    // saved at document start.
    var m = null;
    try { var own = e ? gopd(e, 'data') : null; m = own ? own.value : e ? dom.data(e) : null; } catch (x) {}
    if (typeof m !== 'string' || !on) return;
    if (m === '$COSMETIC_OFF') { on = false; if (observer) dom.disconnect(observer); queued = list(); found = list(); return; }
    if (m[0] !== '$COSMETIC_CSS') return;
    apply(dom.cut ? dom.cut(m, 1) : m.substring(1));
    if (observer || !MO) return;
    observer = new MO(function (records) {
      for (var i = 0; i < records.length; i++) {
        var r = records[i];
        if (dom.recType(r) === 'attributes') { var t = dom.recTarget(r); if (element(t)) names(t); }
        else for (var a = dom.added(r), j = 0, n = dom.count(a); j < n; j++) if (element(a[j])) put(queued, a[j]);
      }
      later();
    });
    dom.observe(observer, d, moOpts);
    var de = dom.html(d);
    if (de) put(queued, de);
    later();
    setT(watch, $COSMETIC_RECHECK_MS);
  };
  port.postMessage('$COSMETIC_HELLO');
})();
"""
}
