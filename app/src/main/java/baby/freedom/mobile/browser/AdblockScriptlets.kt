package baby.freedom.mobile.browser

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val TAG = "AdblockScriptlets"

/** One filter list's text for [AdblockEngine.build], and whether it may call `trusted-*` scriptlets. */
internal class FilterListText(val text: String, val trustedScriptlets: Boolean = false)

/**
 * What [AdblockEngine.build] made of one list: its [rules] (every line
 * that isn't a comment or header), how many of those it [used], and of
 * its `##+js(…)` scriptlet rules, how many there were and how many run.
 */
internal data class FilterListCounts(
    val rules: Int,
    val used: Int,
    val scriptlets: Int = 0,
    val scriptletsUsed: Int = 0,
) {
    /** The rules that can't run here. */
    val skipped: Int get() = rules - used

    operator fun plus(o: FilterListCounts) =
        FilterListCounts(rules + o.rules, used + o.used, scriptlets + o.scriptlets, scriptletsUsed + o.scriptletsUsed)
}

/** A scriptlet to call: its canonical [name] (`json-prune`, no `.js`) and [args]. */
internal class ScriptletCall(val name: String, val args: List<String>) {
    /**
     * What `#@#+js(…)` exceptions and de-duplication compare. Built on
     * demand: ≈10k rules are kept, and only a frame's few are compared.
     */
    val key: String get() = buildString {
        append(name)
        for (a in args) append('\u0000').append(a)
    }

    override fun equals(other: Any?) = other is ScriptletCall && other.key == key
    override fun hashCode() = key.hashCode()
    override fun toString() = "+js($name${args.joinToString("") { ", $it" }})"
}

/** A `+js(…)` rule filed under one of its hosts; [excludes] are its `~` hosts / entities; [order] its place in the lists. */
internal class ScriptletRule(val call: ScriptletCall, val excludes: Set<String>?, val order: Int)

/**
 * A domain a scriptlet rule can be scoped to: a lower-case ASCII host,
 * or a uBlock *entity* (`example.*` — the name under any public suffix).
 */
internal fun isScriptletDomain(name: String): Boolean {
    val host = if (name.endsWith(".*")) name.dropLast(2) else name
    if (host.isEmpty() || host.startsWith('.') || host.endsWith('.') || host.contains("..")) return false
    return host.all { it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '-' || it == '_' }
}

/**
 * The keys a frame on [host] finds its scriptlet rules under: the host
 * and each parent domain, then each of those that sits on the host's
 * registrable domain with its public suffix swapped for `*` (uBlock's
 * entities: `www.google.co.uk` → `www.google.*`, `google.*`).
 */
internal fun scriptletKeys(host: String): List<String> {
    val out = ArrayList<String>(6)
    forEachHostSuffix(host) { out += it }
    if (host.any { it in 'a'..'z' }) {
        val registrable = PublicSuffixList.registrableDomain(host)
        if (registrable != null) {
            val suffix = registrable.substringAfter('.')
            forEachHostSuffix(host) { s ->
                if (s.length >= registrable.length) out += s.dropLast(suffix.length + 1) + ".*"
            }
        }
    }
    return out
}

/**
 * The arguments of a `+js(…)` body after its name, the way uBlock
 * Origin splits them: at each `,` not escaped as `\,`, each trimmed; one
 * wrapped whole in `'…'`, `"…"` or `` `…` `` is taken literally (only
 * its escaped quote unescaped), commas and all. `null` for no name.
 */
internal fun parseScriptletArgs(body: String): List<String>? {
    val parts = ArrayList<String>()
    var i = 0
    val n = body.length
    while (true) {
        while (i < n && body[i] == ' ') i++
        val q = if (i < n) body[i] else ' '
        var arg: String? = null
        if (q == '\'' || q == '"' || q == '`') {
            // A quoted argument: up to the matching unescaped quote, if
            // only spaces then follow before the next comma or the end.
            var j = i + 1
            while (j < n && !(body[j] == q && body[j - 1] != '\\')) j++
            if (j < n) {
                var k = j + 1
                while (k < n && body[k] == ' ') k++
                if (k == n || body[k] == ',') {
                    arg = body.substring(i + 1, j).replace("\\$q", q.toString())
                    i = k
                }
            }
        }
        if (arg == null) {
            var j = i
            while (j < n && !(body[j] == ',' && (j == 0 || body[j - 1] != '\\'))) j++
            arg = body.substring(i, j).trim().replace("\\,", ",")
            i = j
        }
        parts += arg
        if (i >= n) break
        i++ // the comma
        if (i == n) {
            parts += ""
            break
        }
    }
    if (parts.isEmpty() || parts[0].isEmpty()) return null
    return parts
}

/** The call a `+js(…)` body makes, its name resolved by [catalog]; `null` if it names no scriptlet it has. */
internal fun parseScriptletCall(body: String, catalog: ScriptletCatalog): ScriptletCall? {
    val parts = parseScriptletArgs(body) ?: return null
    val name = catalog.canonical(parts[0]) ?: return null
    return ScriptletCall(name, parts.drop(1))
}

/**
 * uBlock Origin's scriptlets (#318), from the bundled `resources.json`
 * (the file desktop ships: @ghostery/adblocker's build of uBlock's
 * `src/js/resources`, pinned by `infra/adblock/vendor-lists.py`), and
 * which of them this browser runs.
 *
 * Only [VETTED] scriptlets run. Each was checked for what an injected
 * script here may do: it patches only the page API it is told to
 * (`JSON.parse`, `fetch`, a property, a timer, a text node…), leaves no
 * marker of its own on the page, inserts no `<script>` or
 * `<style>` element (either would meet the page's CSP and could report
 * us), doesn't `eval`, doesn't reach the network itself, and needs no
 * extension API. One exception, as in uBlock: `prevent-window-open` with
 * a delay argument answers a blocked popup with a decoy — a hidden 1px
 * `<iframe>`/`<object>` at the popup's URL appended to the page (a
 * request with cookies, which the network filter still sees), or with
 * `blank` the real `window.open('about:blank')` (a tab that opens, then
 * closes after the delay). The one page global set beyond what a scriptlet is told to patch
 * is `window.onerror`: `get-exception-token` (a dependency of `json-prune*`,
 * `abort-*` and `trusted-suppress-native-method`) wraps it, as in uBlock,
 * to swallow the errors the `abort-*` scriptlets throw — so on a host
 * running those, `onerror` is a (bound, native-looking) function before
 * the page's first script runs, which a page can notice. Nothing else of
 * ours is defined there (a test pins this). uBlock's debug logger (a
 * `BroadcastChannel`) only starts when a
 * `bcSecret` is set, which it never is here. Left out, and counted as
 * "can't run" in Settings: the rest, among them `trusted-click-element`
 * (extension APIs) and `trusted-create-html` (sets a page global).
 *
 * The `trusted-*` scriptlets (and the few others uBlock marks as needing
 * trust) can do anything to a page, so as in uBlock only its own list
 * may call them ([FilterListText.trustedScriptlets]).
 */
internal class ScriptletCatalog private constructor(
    private val entries: Map<String, Entry>,
    private val aliases: Map<String, String>,
) {
    internal class Entry(val name: String, val fn: String, val body: String, val deps: List<String>, val requiresTrust: Boolean)

    /** The canonical name (no `.js`) [name] — a name or an alias, with or without `.js` — stands for; `null` if none. */
    fun canonical(name: String): String? = aliases[name] ?: aliases["$name.js"]

    fun isVetted(name: String): Boolean = name in VETTED && name in entries

    fun requiresTrust(name: String): Boolean = name.startsWith("trusted-") || entries[name]?.requiresTrust == true

    /**
     * The code that makes [calls], for inside a function: every
     * dependency and scriptlet each declared once, then each call in its
     * own `try`, so one that throws (a page that froze what it patches)
     * doesn't stop the rest.
     */
    fun code(calls: List<ScriptletCall>): String {
        val deps = LinkedHashSet<String>()
        fun addDeps(names: List<String>) {
            for (d in names) {
                if (d in deps) continue
                deps += d
                entries[d]?.let { addDeps(it.deps) }
            }
        }
        val used = LinkedHashSet<String>()
        for (c in calls) if (entries.containsKey(c.name) && used.add(c.name)) addDeps(entries.getValue(c.name).deps)
        val sb = StringBuilder()
        for (d in deps) entries[d]?.let { sb.append(it.body).append(";\n") }
        for (name in used) sb.append(entries.getValue(name).body).append(";\n")
        for (c in calls) {
            val e = entries[c.name] ?: continue
            sb.append("try { ").append(e.fn).append('(')
            c.args.forEachIndexed { i, a -> if (i > 0) sb.append(", "); sb.append(jsString(a)) }
            sb.append("); } catch (e) {}\n")
        }
        return sb.toString()
    }

    companion object {
        /**
         * The scriptlets that run (canonical names): the ones the
         * bundled lists use most, and every one uBlock's YouTube rules
         * call. See the class comment for what was checked.
         */
        val VETTED: Set<String> = setOf(
            "abort-current-script", "abort-on-property-read", "abort-on-property-write", "abort-on-stack-trace",
            "adjust-setInterval", "adjust-setTimeout", "disable-newtab-links", "href-sanitizer",
            "json-prune", "json-prune-fetch-response", "json-prune-xhr-response", "m3u-prune",
            "noeval-if", "nowebrtc", "prevent-addEventListener", "prevent-bab", "prevent-fetch",
            "prevent-innerHTML", "prevent-refresh", "prevent-requestAnimationFrame", "prevent-setInterval",
            "prevent-setTimeout", "prevent-window-open", "prevent-xhr", "remove-attr", "remove-class",
            "remove-cookie", "remove-node-text", "set-constant", "set-cookie", "set-local-storage-item",
            "set-session-storage-item", "xml-prune",
            // Trusted: uBlock's own list only.
            "trusted-edit-inbound-object", "trusted-json-edit", "trusted-json-edit-fetch-response",
            "trusted-json-edit-xhr-response", "trusted-prevent-dom-bypass", "trusted-prevent-fetch",
            "trusted-prevent-xhr", "trusted-replace-argument", "trusted-replace-fetch-response",
            "trusted-replace-node-text", "trusted-replace-outbound-text", "trusted-replace-xhr-response",
            "trusted-set-constant", "trusted-suppress-native-method",
        )

        private val FN_NAME = Regex("^function\\s+([A-Za-z_$][\\w$]*)\\s*\\(")

        /** Parse a `resources.json`; `null` if it isn't one or has no scriptlets. */
        fun parse(json: String): ScriptletCatalog? = runCatching {
            val array = JSONObject(json).getJSONArray("scriptlets")
            val entries = HashMap<String, Entry>()
            val aliases = HashMap<String, String>()
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val file = o.getString("name")
                val body = o.getString("body")
                val fn = FN_NAME.find(body)?.groupValues?.get(1) ?: continue
                val depsJson = o.optJSONArray("dependencies")
                val deps = (0 until (depsJson?.length() ?: 0)).map { depsJson!!.getString(it) }
                // A dependency keeps its file name (`safe-self.fn`), as the
                // scriptlets' own lists name it; a scriptlet goes by its
                // name without `.js`.
                val name = if (file.endsWith(".js")) file.removeSuffix(".js") else file
                entries[name] = Entry(name, fn, body, deps.map { if (it.endsWith(".js")) it.removeSuffix(".js") else it }, o.optBoolean("requiresTrust", false))
                if (!file.endsWith(".js")) continue
                aliases[file] = name
                val more = o.optJSONArray("aliases")
                for (j in 0 until (more?.length() ?: 0)) aliases[more!!.getString(j)] = name
            }
            if (aliases.isEmpty()) null else ScriptletCatalog(entries, aliases)
        }.onFailure { Log.w(TAG, "scriptlet resources unreadable", it) }.getOrNull()
    }
}

/**
 * Per host, its scriptlet code (`""` for a host with none): the least
 * recently used dropped past [maxEntries] hosts or [maxChars] of code,
 * so a long session doesn't keep every host it ever met on the heap
 * until the next engine build. Not thread-safe: callers lock it.
 */
internal class ScriptletCodeCache(
    private val maxEntries: Int = MAX_ENTRIES,
    private val maxChars: Int = MAX_CHARS,
) {
    private val map = LinkedHashMap<String, String>(64, 0.75f, true)

    /** The code held now, in characters. */
    var chars = 0
        private set

    val size: Int get() = map.size

    operator fun get(host: String): String? = map[host]

    fun put(host: String, code: String) {
        map.put(host, code)?.let { chars -= it.length }
        chars += code.length
        val it = map.entries.iterator()
        while ((map.size > maxEntries || chars > maxChars) && it.hasNext()) {
            val e = it.next()
            if (e.key == host) continue
            chars -= e.value.length
            it.remove()
        }
    }

    fun clear() {
        map.clear()
        chars = 0
    }

    companion object {
        const val MAX_ENTRIES = 512
        const val MAX_CHARS = 1_000_000
    }
}

/** [s] as a JavaScript string literal; everything outside printable ASCII escaped. */
internal fun jsString(s: String): String {
    val sb = StringBuilder(s.length + 2).append('"')
    for (c in s) {
        when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c.code in 0x20..0x7e -> sb.append(c)
            else -> sb.append(String.format("\\u%04x", c.code))
        }
    }
    return sb.append('"').toString()
}

/**
 * The document-start script for frames on [host]: [code] (from
 * [ScriptletCatalog.code]) inside a function, so nothing it declares
 * lands on the page — only what the scriptlets themselves are told to
 * patch changes.
 *
 * It runs in a frame on [host] (or an `about:` frame the host's page
 * made, which shares its origin), unless the tab's top-level page is on
 * [allowlist] (entries in [normalizeAllowlistHost] form; matched as
 * [isAllowlisted] does): the frame reads the top page's origin from
 * `location.ancestorOrigins`, so a cross-origin frame on an allowlisted
 * page is left alone too. Everything it reads is read before any page
 * script has run.
 */
internal fun scriptletFrameJs(host: String, code: String, allowlist: Collection<String>): String {
    val allowed = allowlist.sorted().joinToString(",") { jsString(it) }
    return """
(function () {
  var l = location, h = l.hostname, a = l.ancestorOrigins, t = h;
  if (h !== ${jsString(host)} && l.protocol !== 'about:') return;
  if (a && a.length) {
    t = a[a.length - 1];
    var i = t.indexOf('://');
    t = i < 0 ? '' : t.substring(i + 3);
    i = t.lastIndexOf(':');
    if (i > t.lastIndexOf(']')) t = t.substring(0, i);
  } else if (h !== ${jsString(host)}) return;
  t = t.toLowerCase();
  while (t.charAt(t.length - 1) === '.') t = t.substring(0, t.length - 1);
  if (t.substring(0, 4) === 'www.') t = t.substring(4);
  var allow = [$allowed];
  for (var s = t; s; ) {
    if (allow.indexOf(s) >= 0) return;
    var d = s.indexOf('.');
    s = d < 0 ? '' : s.substring(d + 1);
  }
  var scriptletGlobals = {};
$code})();
"""
}

/**
 * The scriptlets of one tab (#318): a document-start script per host
 * the tab has loaded a document from — its pages and their frames —
 * scoped to that host's origins, so it runs before any of the page's
 * own scripts, in the main frame and in same- and cross-origin frames
 * alike, and only where the rules say.
 *
 * Why per host, and only the hosts this tab has seen: a WebView hands
 * every registered document-start script to every frame it creates,
 * whatever origin the script is for — so registering all ≈10k scriptlet
 * rules (≈1 MB of code and arguments) would put a copy in each frame of
 * each tab. A host is registered the first time the tab is seen asking
 * for a document from it, before that document can arrive:
 *
 * - [ensureFromNetworkThread] from `shouldInterceptRequest`, for a main
 *   frame or frame document — it holds the request until the script is
 *   registered (on the main thread), and the answer can only start
 *   after the request goes out, so the frame's document commits with the
 *   script already in place;
 * - [ensure] from the app's own loads (`loadUrl`, `postUrl`, reload,
 *   Back / Forward, a restored tab) and the page's navigations
 *   (`shouldOverrideUrlLoading`), which a service worker may answer
 *   without `shouldInterceptRequest` ever seeing the request.
 *
 * A redirect hop of a frame's document reaches neither callback, so
 * each host is registered with its `www.` / bare-domain twin
 * ([scriptletRedirectTwin]: `youtube.com/embed/…` → `www.youtube.com`),
 * and [noteReferer] registers a host whose document turns up in the tab
 * unannounced (seen as a subresource's `Referer`), for its next load.
 *
 * A registered host stays for the tab's later loads (Back, a reload, a
 * page its service worker serves), up to [MAX_HOSTS] and [MAX_CHARS] of
 * script, the least recently needed dropped first — one entry at a
 * time, so a host can stay while its twin goes; the host's next document
 * request ([ensureHosts]) adds the twin back, and never drops the host
 * itself to make room for it. What isn't covered, and gets its
 * scriptlets from its next load on (via [noteReferer]): a frame a
 * service worker serves (where `shouldOverrideUrlLoading` isn't asked
 * either); a frame redirected to a host other than its twin, from a host
 * this tab hasn't loaded a document from before; and a main-frame form
 * POST answered with a 307/308 to another host, a hop that keeps the
 * POST and so reaches neither `shouldOverrideUrlLoading` nor
 * `shouldInterceptRequest` either.
 *
 * A changed engine or allowlist ([Adblock.scriptletGeneration]) makes
 * every tab rebuild its scripts: the new one is added before the old one
 * goes, so a document committing in between may run both, never neither.
 */
internal class TabScriptlets(
    webView: WebView,
    private val private: Boolean,
    private val source: ScriptletSource = AdblockScriptletSource,
    private val maxHosts: Int = MAX_HOSTS,
    private val maxChars: Int = MAX_CHARS,
) {
    private val view = WeakReference(webView)
    private val main = Handler(Looper.getMainLooper())

    private class Registration(val handler: ScriptHandler, val script: String, val generation: Int)

    /** Host → its registered script; access order, for dropping the least recently needed. Main thread only. */
    private val registered = LinkedHashMap<String, Registration>(8, 0.75f, true)

    /** Host → the generation its script was built for, readable off the main thread. */
    private val current = ConcurrentHashMap<String, Int>()

    /** Hosts asked for before the first engine build landed, its wait deadline passed or not. Main thread only. */
    private val waiting = LinkedHashSet<String>()

    @Volatile
    private var closed = false

    /**
     * From `shouldInterceptRequest` (a WebView network thread): [ensure]
     * [url]'s host — and its `www.` / bare-domain twin, see
     * [scriptletRedirectTwin] — waiting for it (bounded).
     */
    fun ensureFromNetworkThread(url: String) {
        if (closed) return
        val host = scriptletHostOf(url) ?: return
        // Before the first engine build lands (a tab restored at start),
        // wait for it as the request filter does (bounded, #192).
        source.awaitFirstBuild()
        val generation = source.generation
        // The twin first, so the host asked for is the most recently
        // needed one if the tab's budget runs out.
        val hosts = listOfNotNull(scriptletRedirectTwin(host), host)
        // Past the wait's deadline with no engine yet: nothing to register
        // now, but queue them for when it lands ([refresh], R6-M2).
        if (!source.firstBuildLanded) {
            main.post { ensureHosts(hosts) }
            return
        }
        // Nothing to do if both are in place, or have no rules (or blocking is off).
        if (hosts.none { h -> current[h] != generation && (current[h] != null || source.hasScriptlets(h)) }) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            ensureHosts(hosts)
            return
        }
        val done = CountDownLatch(1)
        main.post {
            try {
                ensureHosts(hosts)
            } finally {
                done.countDown()
            }
        }
        try {
            if (!done.await(NETWORK_WAIT_MS, TimeUnit.MILLISECONDS)) Log.w(TAG, "registering $host timed out")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** On the main thread: make sure [url]'s host (and its [scriptletRedirectTwin]) has its current script, if it has rules. */
    fun ensure(url: String?) {
        if (closed || url == null) return
        val host = scriptletHostOf(url) ?: return
        ensureHosts(listOfNotNull(scriptletRedirectTwin(host), host))
    }

    /**
     * Main thread: register [hosts] — a host and its twin, the one asked
     * for last — none of them dropped to make room for another (R2-F1).
     * One already registered is marked as needed now first, so it isn't
     * the least recently needed entry when its twin is added.
     */
    private fun ensureHosts(hosts: List<String>) {
        val keep = hosts.toSet()
        hosts.forEach { registered[it] }
        hosts.forEach { ensureHost(it, keep) }
    }

    /**
     * From `shouldInterceptRequest`, for a subresource with [headers]: if
     * its `Referer` names a document (see [refererNamesDocument] — not a
     * stylesheet's font or image, whose `Referer` is the stylesheet)
     * whose host has rules but no script here, that document arrived by
     * a route nothing saw — a frame redirected to another host (WebView
     * doesn't ask `shouldInterceptRequest` about a redirect hop, nor
     * `shouldOverrideUrlLoading` about a subframe's) — so register it
     * now, for its next load: a reload, or the frame loading again.
     * Doesn't wait.
     */
    fun noteReferer(headers: Map<String, String>?) {
        if (closed || source.firstBuildPending || !refererNamesDocument(headers)) return
        val host = refererOf(headers)?.let(::scriptletHostOf) ?: return
        val generation = source.generation
        if (current[host] == generation) return
        if (current[host] == null && !source.hasScriptlets(host)) return
        main.post { ensureHost(host, setOf(host)) }
    }

    private fun ensureHost(host: String, keep: Set<String>) {
        val webView = view.get() ?: return
        if (closed) return
        val generation = source.generation
        val have = registered[host]
        if (have != null && have.generation == generation) return
        val script = source.script(host, private)
        if (script == null) {
            have?.let { drop(host) }
            // No engine yet: try again once it lands ([refresh]) — however
            // long that takes, not only within the first build's wait (R6-M2).
            if (!source.firstBuildLanded) waiting += host
            return
        }
        if (have != null && have.script == script) {
            registered[host] = Registration(have.handler, script, generation)
            current[host] = generation
            return
        }
        val handler = try {
            WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("https://$host", "http://$host"))
        } catch (e: RuntimeException) {
            // A WebView already destroyed, or a host the rule grammar refuses.
            Log.w(TAG, "can't register scriptlets for $host", e)
            return
        }
        // Added before the old one goes: see the class comment.
        have?.handler?.let { runCatching { it.remove() } }
        registered[host] = Registration(handler, script, generation)
        current[host] = generation
        trim(keep)
    }

    private fun trim(keep: Set<String>) {
        var chars = registered.values.sumOf { it.script.length }
        val it = registered.entries.iterator()
        while ((registered.size > maxHosts || chars > maxChars) && it.hasNext()) {
            val e = it.next()
            if (e.key in keep) continue
            chars -= e.value.script.length
            runCatching { e.value.handler.remove() }
            current.remove(e.key)
            it.remove()
        }
    }

    private fun drop(host: String) {
        registered.remove(host)?.let { runCatching { it.handler.remove() } }
        current.remove(host)
    }

    /** Main thread: rebuild every registered host's script for the current generation. */
    fun refresh() {
        if (closed) return
        val hosts = registered.keys.toList() + waiting
        waiting.clear()
        for (host in hosts) ensureHost(host, setOf(host))
    }

    /** The WebView is going: forget it. Main thread. */
    fun close() {
        closed = true
        registered.clear()
        current.clear()
        waiting.clear()
        synchronized(all) { all.removeAll { it.get() == null || it.get() === this } }
    }

    /** Hosts registered now, for tests and the logs. */
    val hosts: Set<String> get() = registered.keys.toSet()

    companion object {
        /** Most hosts one tab keeps scripts for. */
        const val MAX_HOSTS = 24

        /** Most script, in characters, one tab keeps registered — what each of its frames holds a copy of. */
        const val MAX_CHARS = 400_000

        /** How long a document request waits for its host's script to be registered. */
        const val NETWORK_WAIT_MS = 2_000L

        private val all = ArrayList<WeakReference<TabScriptlets>>()

        fun isSupported(): Boolean = runCatching {
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        }.getOrDefault(false)

        /** Scriptlets for [webView], a tab that is [private] or not; `null` where WebView can't run them. */
        fun install(webView: WebView, private: Boolean, source: ScriptletSource = AdblockScriptletSource): TabScriptlets? {
            if (!isSupported()) return null
            return TabScriptlets(webView, private, source).also { t -> synchronized(all) { all += WeakReference(t) } }
        }

        /** The engine or an allowlist changed: every tab rebuilds its scripts, on the main thread. */
        fun refreshAll() {
            val run = Runnable {
                val tabs = synchronized(all) {
                    all.removeAll { it.get() == null }
                    all.mapNotNull { it.get() }
                }
                tabs.forEach { it.refresh() }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) run.run() else Handler(Looper.getMainLooper()).post(run)
        }
    }
}

/**
 * Where a [TabScriptlets] gets its scripts: [AdblockScriptletSource] in
 * the app, a fixed engine in tests.
 */
internal interface ScriptletSource {
    /** Bumped whenever [script]'s answers may change. */
    val generation: Int

    /** Is there anything to run on [host] at all? Cheap; any thread. */
    fun hasScriptlets(host: String): Boolean

    /** The frame script for [host] in a tab that is [private] or not ([scriptletFrameJs]); `null` for none. */
    fun script(host: String, private: Boolean): String?

    /** The first engine build is under way (its answers aren't in yet), within its wait's deadline. */
    val firstBuildPending: Boolean get() = false

    /** The first engine build has landed (or there's none to wait for); `false` from start until then, deadline or not. */
    val firstBuildLanded: Boolean get() = true

    /** Block until the first engine build lands (bounded); a network thread only. */
    fun awaitFirstBuild() {}
}

/** The app's scriptlets: [Adblock]'s engine and allowlists. */
internal object AdblockScriptletSource : ScriptletSource {
    override val generation: Int get() = Adblock.scriptletGeneration
    override fun hasScriptlets(host: String) = Adblock.scriptletCode(host) != null
    override fun script(host: String, private: Boolean) = Adblock.scriptletScript(host, private)
    override val firstBuildPending: Boolean get() = Adblock.firstBuildPending
    override val firstBuildLanded: Boolean get() = Adblock.firstBuildLanded
    override fun awaitFirstBuild() = Adblock.awaitFirstBuildBlocking()
}

/**
 * Does a subresource request's `Referer` name the document that made it
 * ([TabScriptlets.noteReferer])? Not when CSS fetched it (`@font-face`,
 * `background`, `@import`): its `Referer` is then the stylesheet's URL —
 * often a CDN's, not a document's.
 *
 * WebView's `shouldInterceptRequest` never sees `Sec-Fetch-Dest`
 * (Chromium adds Fetch Metadata later, in the network service), so the
 * kind is read from `Accept`, which Blink sets per kind before the hook:
 * an image (`image/…`) or a stylesheet (`text/css…`) isn't trusted. A
 * font sends the same catch-all `Accept` as a script or `fetch`, so it
 * is told apart two ways. A font is a CORS request, so it carries the
 * document's `Origin`: when that names another host than the `Referer`,
 * the `Referer` is a stylesheet from elsewhere (a cross-origin stylesheet
 * sends only its bare origin, `http://e.test/`, for a font on a third
 * host) and isn't trusted. And a `Referer` whose path is a `.css` file
 * isn't trusted either (a stylesheet's font on its own host, which
 * carries the full stylesheet URL). What's left — a font from a
 * stylesheet on the document's own host, or on another host at a path
 * not ending `.css` with the font on that same host — at worst
 * registers the document's or that stylesheet's host, if it has rules,
 * for nothing. A script, frame or media request a document makes
 * either sends no `Origin` or its own, so it is still trusted. Should
 * `Sec-Fetch-Dest` ever turn up, it decides instead.
 */
internal fun refererNamesDocument(headers: Map<String, String>?): Boolean {
    fun header(name: String) =
        headers?.entries?.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.trim()?.lowercase()
    val referer = refererOf(headers) ?: return false
    header("Sec-Fetch-Dest")?.let { return it in REFERER_DOCUMENT_DESTS }
    val accept = header("Accept").orEmpty()
    if (accept.startsWith("image/") || accept.startsWith("text/css")) return false
    // A CORS request whose Origin isn't the Referer's host: a stylesheet's font from elsewhere.
    val origin = header("Origin")
    if (origin != null && origin != "null" && hostOfUrl(origin) != hostOfUrl(referer.lowercase())) return false
    val path = runCatching { java.net.URI(referer).rawPath }.getOrNull() ?: return false
    return !path.lowercase().endsWith(".css")
}

private val REFERER_DOCUMENT_DESTS = setOf(
    "script", "empty", "iframe", "frame", "embed", "object", "audio", "video", "track",
    "manifest", "worker", "sharedworker", "serviceworker",
)

/**
 * The host a document asked for on [host] most often lands on instead
 * by a redirect — the same name with `www.` added or taken off, when the
 * name without it is a registrable domain (`youtube.com/embed/…` 301s to
 * `www.youtube.com`) — or `null`. A frame's redirect hop reaches neither
 * `shouldInterceptRequest` nor `shouldOverrideUrlLoading`, so its final
 * host has to be registered with the host asked for, up front.
 */
internal fun scriptletRedirectTwin(host: String): String? {
    if (host.startsWith("www.")) {
        val bare = host.substring(4)
        return if (PublicSuffixList.registrableDomain(bare) == bare) bare else null
    }
    return if (PublicSuffixList.registrableDomain(host) == host) "www.$host" else null
}

/**
 * The host a document at [url] would get scriptlets for: an http(s)
 * URL's lower-case host, if it's a name (not an IP literal with a port
 * or IPv6 brackets the origin rules can't take) — else `null`.
 */
internal fun scriptletHostOf(url: String): String? {
    if (!url.startsWith("https://", ignoreCase = true) && !url.startsWith("http://", ignoreCase = true)) return null
    val host = hostOfUrl(url) ?: return null
    if (host.startsWith("[") || !isScriptletDomain(host)) return null
    return host
}
