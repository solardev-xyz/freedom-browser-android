package baby.freedom.mobile.browser

import android.webkit.WebView
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

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
                    val css = Adblock.initialCosmetics(frameOrigin, page, private)
                    reply.postMessage(if (css == null) COSMETIC_OFF else COSMETIC_CSS + css)
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
 * every 50 ms, each name once per document. It stops for good on
 * [COSMETIC_OFF]. Everything it calls is saved at document start, so a
 * page can't redirect it.
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
  var setT = w.setTimeout, MO = w.MutationObserver, Sheet = w.CSSStyleSheet, S = w.Set;
  var seen = new S(), found = [], queued = [], timer = 0, on = true, observer = null, sheets = [];
  var Doc = w.Document, desc = Doc && Object.getOwnPropertyDescriptor(Doc.prototype, 'adoptedStyleSheets');
  var getSheets = desc && desc.get, setSheets = desc && desc.set;
  function apply(css) {
    if (!css || !Sheet || !getSheets) return;
    try {
      var s = new Sheet();
      s.replaceSync(css);
      sheets.push(s);
      var cur = getSheets.call(d), next = [];
      for (var i = 0; i < cur.length; i++) next.push(cur[i]);
      for (var j = 0; j < sheets.length; j++) if (next.indexOf(sheets[j]) < 0) next.push(sheets[j]);
      setSheets.call(d, next);
    } catch (e) {}
  }
  function add(t) { if (!seen.has(t)) { seen.add(t); found.push(t); } }
  function names(el) {
    var id = el.id;
    if (typeof id === 'string' && id && !/\s/.test(id)) add('#' + id);
    var cl = el.classList;
    if (cl) for (var i = 0; i < cl.length; i++) add('.' + cl[i]);
  }
  function scan(root) {
    if (!root || root.nodeType !== 1) return;
    names(root);
    var all = root.querySelectorAll('[id],[class]');
    for (var i = 0; i < all.length; i++) names(all[i]);
  }
  function flush() {
    timer = 0;
    if (!on) return;
    var roots = queued; queued = [];
    for (var i = 0; i < roots.length; i++) scan(roots[i]);
    while (found.length) {
      var batch = found.splice(0, $COSMETIC_MAX_TOKENS);
      port.postMessage('t\n' + batch.join('\n'));
    }
  }
  function later() { if (!timer) timer = setT(flush, 50); }
  port.onmessage = function (e) {
    var m = e.data;
    if (typeof m !== 'string' || !on) return;
    if (m === '$COSMETIC_OFF') { on = false; if (observer) observer.disconnect(); queued = []; found = []; return; }
    if (m.charAt(0) !== '$COSMETIC_CSS') return;
    apply(m.substring(1));
    if (observer || !MO) return;
    observer = new MO(function (records) {
      for (var i = 0; i < records.length; i++) {
        var r = records[i];
        if (r.type === 'attributes') { if (r.target.nodeType === 1) names(r.target); }
        else for (var j = 0; j < r.addedNodes.length; j++) if (r.addedNodes[j].nodeType === 1) queued.push(r.addedNodes[j]);
      }
      later();
    });
    observer.observe(d, { childList: true, subtree: true, attributes: true, attributeFilter: ['id', 'class'] });
    if (d.documentElement) queued.push(d.documentElement);
    later();
  };
  port.postMessage('$COSMETIC_HELLO');
})();
"""
}
