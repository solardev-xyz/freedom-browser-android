package baby.freedom.mobile.browser

/**
 * The ad-blocking filter engine (#126): Adblock Plus–syntax filter lists
 * (the EasyList family) compiled into lookup tables, then asked two
 * things — "block this request?" from `shouldInterceptRequest`, and
 * "which elements to hide?" from the cosmetic script ([AdblockCosmetic]).
 * Pure Kotlin, immutable once built, safe from any thread.
 *
 * ## Network filters
 *
 * Most of the lists are `||host^` rules (≈95k of the ≈125k network rules
 * in EasyList + EasyPrivacy), so those go in a hash set probed with the
 * request host and each of its parent domains. Every other pattern is
 * indexed under one *token* — a run of `[a-z0-9%]` the pattern pins on
 * both sides — and a request only tests the filters filed under the
 * tokens of its own URL, as uBlock Origin and Brave's adblock-rust do.
 * Patterns with no usable token (a handful) are tested for every request.
 *
 * Supported: `||` / `|` anchors, `*`, `^`, `/regex/`, `@@` exceptions,
 * and the options `third-party` / `first-party` (`3p`, `1p`, `~…`),
 * `domain=` (`from=`), `match-case`, `important`, the resource types,
 * `redirect=` / `redirect-rule=` / `rewrite=abp-resource:` ([redirectFor],
 * #393), and — on exceptions — `document`, `elemhide` and `generichide`.
 * A filter with any other option (`csp=`, `removeparam`, `replace=`, …)
 * is dropped whole: honouring half of a filter is how pages break. So is
 * one that only applies to `popup` or `document` loads, since the
 * browser never blocks a top-level navigation.
 *
 * WebView doesn't say what a request is for, so [requestTypes] infers a
 * *set* of possible types (an `Accept: *` script could be a script, an
 * XHR or a beacon) and a filter's type options apply when they meet that
 * set — both for blocking and for exceptions, so an exception for a
 * script also covers the request it can't be told apart from.
 *
 * ## Cosmetic filters
 *
 * `##selector` rules hide elements. Site-specific ones (`example.com##…`)
 * are served for the frame's host and its parents. Generic ones are
 * filed under the class or id the selector starts with (`.ad-banner…`,
 * `#sidebar-ad…`) and served only once the page is seen to use that
 * class or id, so a page doesn't pay for tens of thousands of selectors;
 * the few generic selectors with no such key are served to every page.
 * `#@#` exceptions and the `$elemhide` / `$generichide` exception filters
 * are honoured; a rule's domains may be uBlock entities (`example.*`), as
 * a scriptlet's may ([scriptletKeys]). What CSS can express of the
 * extended syntax runs as CSS ([parseCosmeticSelector], #393): `:has()`,
 * `#?#` with `:-abp-has()`, `:style()`, `:remove()`. Procedural selectors
 * that need a script walking the DOM (`:has-text()`, `:upward()`,
 * `:xpath()`, `:-abp-contains()`, …), HTML filters (`##^`) and the
 * `#$#` / `#%#` extensions are skipped.
 *
 * ## Scriptlets
 *
 * `example.com##+js(name, args…)` rules (#318) are kept per host — or
 * per uBlock *entity*, `example.*` — for [scriptletsFor], which hands
 * the injector ([AdblockScriptlets]) the calls a frame on a host is to
 * run. Only scriptlets the [ScriptletCatalog] vets are kept; the
 * `trusted-*` ones only from a list built with trusted scriptlets on
 * (uBlock Origin's own, as uBlock itself does). `#@#+js(…)` exceptions
 * turn one call off on their hosts, `#@#+js()` all of them. Generic
 * scriptlets (no host to scope them to) aren't run.
 *
 * Each list's rules that can't run here — unsupported syntax or
 * options, procedural and HTML filters, unvetted scriptlets — are
 * counted ([listCounts]), for Settings.
 */
internal class AdblockEngine private constructor(
    private val plainBlockedHosts: HashSet<String>,
    private val hostFilters: HashMap<String, MutableList<NetworkFilter>>,
    private val tokenFilters: HashMap<String, MutableList<NetworkFilter>>,
    private val untokenedFilters: List<NetworkFilter>,
    private val pageExceptions: List<NetworkFilter>,
    private val specificHide: HashMap<String, MutableList<CosmeticRule>>,
    private val specificUnhide: HashMap<String, MutableSet<String>>,
    private val genericKeyed: HashMap<String, MutableList<CosmeticRule>>,
    private val genericUnkeyed: List<CosmeticRule>,
    private val scriptletRules: HashMap<String, MutableList<ScriptletRule>>,
    private val scriptletUnhide: HashMap<String, MutableSet<String>>,
    private val redirects: RedirectIndex,
    /** How many filters made it in, for Settings and the logs. */
    val filterCount: Int,
    /** Per list, in the order given to [build]: its rules, and how many of them run. */
    val listCounts: List<FilterListCounts>,
) {

    /**
     * The scriptlet calls a frame on [frameHost] (lower-case) runs, in
     * list order, each once: the host's and its parents' rules and
     * those of their uBlock entities (`example.*`), less the ones a
     * `~` domain or a `#@#+js(…)` exception excludes there. Empty when
     * an `@@…$document` / `$elemhide` exception covers the host.
     */
    fun scriptletsFor(frameHost: String): List<ScriptletCall> {
        if (scriptletRules.isEmpty() || frameHost.isEmpty()) return emptyList()
        val keys = scriptletKeys(frameHost)
        var rules: MutableList<ScriptletRule>? = null
        for (key in keys) {
            val found = scriptletRules[key] ?: continue
            (rules ?: ArrayList<ScriptletRule>().also { rules = it }).addAll(found)
        }
        val matched = rules ?: return emptyList()
        val origin = "https://$frameHost/"
        if (pageExempt(origin, frameHost, EXEMPT_DOCUMENT or EXEMPT_ELEMHIDE)) return emptyList()
        var off: MutableSet<String>? = null
        for (key in keys) scriptletUnhide[key]?.let { (off ?: HashSet<String>().also { s -> off = s }).addAll(it) }
        val disabled = off.orEmpty()
        if (ALL_SCRIPTLETS in disabled) return emptyList()
        val out = LinkedHashMap<String, ScriptletCall>()
        matched.sortBy { it.order }
        for (rule in matched) {
            val key = rule.call.key
            if (key in disabled || key in out) continue
            if (rule.excludes != null && keys.any { it in rule.excludes }) continue
            out[key] = rule.call
        }
        return out.values.toList()
    }

    /**
     * Should the subresource [url] (lower-case host [host]) be blocked,
     * loaded by a page whose top-level document is [pageUrl] on
     * [pageHost]? [types] is [requestTypes]'s set. Main-frame loads are
     * not asked: the browser never blocks a navigation.
     */
    fun shouldBlock(url: String, host: String, types: Int, pageUrl: String?, pageHost: String?): Boolean {
        val lowerUrl = url.lowercase()
        val request = Request(url, lowerUrl, host, types, pageHost, isThirdParty(host, pageHost))
        var blocked = false
        var important = false
        forEachCandidate(request, exceptions = false) { filter ->
            if (filter.matches(request)) {
                blocked = true
                if (filter.important) important = true
            }
            important // stop early only once an `important` filter settles it
        }
        if (!blocked) return false
        if (pageUrl != null && pageExempt(pageUrl, pageHost, EXEMPT_DOCUMENT)) return false
        if (important) return true
        var excepted = false
        forEachCandidate(request, exceptions = true) { filter ->
            excepted = filter.matches(request)
            excepted
        }
        return !excepted
    }

    /**
     * The redirect resource (#393) a request [shouldBlock] blocks gets
     * instead of an empty 403 — its canonical name in the
     * [ScriptletCatalog] — or `null` for the plain 403. Asked only for a
     * blocked request, so it costs nothing on the others. As in uBlock:
     * a `$redirect=` filter both blocks and names its stand-in, a
     * `$redirect-rule=` one only names it for a request something else
     * blocks; the highest `:priority` wins (the first listed on a tie);
     * an `@@…$redirect-rule` / `@@…$redirect=name` exception lifts all
     * of them, or that one, where it matches.
     */
    fun redirectFor(url: String, host: String, types: Int, pageHost: String?): String? {
        if (redirects.isEmpty()) return null
        val request = Request(url, url.lowercase(), host, types, pageHost, isThirdParty(host, pageHost))
        return redirects.resolve(request)
    }

    /**
     * Is the whole page [pageUrl] exempt from blocking by an
     * `@@…$document` exception? (The allowlist is the user's own
     * version of the same thing, checked by the caller.)
     */
    fun documentExempt(pageUrl: String, pageHost: String?): Boolean =
        pageExempt(pageUrl, pageHost, EXEMPT_DOCUMENT)

    /**
     * The CSS a frame of [frameUrl] (lower-case host [frameHost]) on the
     * page [pageUrl] / [pageHost] starts with: its host's specific rules
     * and the generic rules no class or id keys. Empty when an exception
     * filter turns hiding off there.
     */
    fun initialCosmetics(frameUrl: String, frameHost: String, pageUrl: String?, pageHost: String?): String {
        val mode = cosmeticMode(frameUrl, frameHost, pageUrl, pageHost)
        if (mode == CosmeticMode.NONE) return ""
        val keys = scriptletKeys(frameHost)
        val unhidden = unhiddenFor(keys)
        val rules = LinkedHashMap<String, CosmeticRule>()
        for (key in keys) {
            specificHide[key]?.forEach { rule ->
                if (rule.appliesTo(keys) && rule.key !in unhidden) rules.putIfAbsent(rule.key, rule)
            }
        }
        if (mode == CosmeticMode.ALL) {
            for (rule in genericUnkeyed) {
                if (rule.appliesTo(keys) && rule.key !in unhidden) rules.putIfAbsent(rule.key, rule)
            }
        }
        return cssFor(rules.values)
    }

    /**
     * The CSS for the generic rules keyed by [tokens] — `.class` and
     * `#id` names the frame's page script found in its DOM — or `""`.
     */
    fun cosmeticsForTokens(
        tokens: Collection<String>,
        frameUrl: String,
        frameHost: String,
        pageUrl: String?,
        pageHost: String?,
    ): String {
        if (cosmeticMode(frameUrl, frameHost, pageUrl, pageHost) != CosmeticMode.ALL) return ""
        var keys: List<String>? = null
        var unhidden: Set<String>? = null
        val out = LinkedHashMap<String, CosmeticRule>()
        for (token in tokens) {
            val rules = genericKeyed[token] ?: continue
            val k = keys ?: scriptletKeys(frameHost).also { keys = it }
            val skip = unhidden ?: unhiddenFor(k).also { unhidden = it }
            for (rule in rules) {
                if (rule.appliesTo(k) && rule.key !in skip) out.putIfAbsent(rule.key, rule)
            }
        }
        return cssFor(out.values)
    }

    private enum class CosmeticMode { NONE, SPECIFIC_ONLY, ALL }

    private fun cosmeticMode(frameUrl: String, frameHost: String, pageUrl: String?, pageHost: String?): CosmeticMode {
        if (pageExempt(frameUrl, frameHost, EXEMPT_DOCUMENT or EXEMPT_ELEMHIDE)) return CosmeticMode.NONE
        if (pageUrl != null && pageExempt(pageUrl, pageHost, EXEMPT_DOCUMENT or EXEMPT_ELEMHIDE)) {
            return CosmeticMode.NONE
        }
        return if (pageExempt(frameUrl, frameHost, EXEMPT_GENERICHIDE)) CosmeticMode.SPECIFIC_ONLY
        else CosmeticMode.ALL
    }

    /** The `#@#` exceptions for a frame whose [scriptletKeys] are [keys]: its host, parents and entities. */
    private fun unhiddenFor(keys: List<String>): Set<String> {
        var out: MutableSet<String>? = null
        for (key in keys) {
            specificUnhide[key]?.let { (out ?: HashSet<String>().also { s -> out = s }).addAll(it) }
        }
        return out ?: emptySet()
    }

    private fun pageExempt(url: String, host: String?, kinds: Int): Boolean {
        if (pageExceptions.isEmpty()) return false
        val h = host ?: hostOfUrl(url) ?: return false
        val request = Request(url, url.lowercase(), h, RequestType.DOCUMENT, h, thirdParty = false)
        return pageExceptions.any { it.exempts and kinds != 0 && it.matches(request, anyType = true) }
    }

    /**
     * Run [test] over the filters that could match [request] until it
     * returns true: the host table, then the token buckets, then the
     * untokened rest. [exceptions] picks which side of each table.
     */
    private inline fun forEachCandidate(
        request: Request,
        exceptions: Boolean,
        test: (NetworkFilter) -> Boolean,
    ) {
        if (!exceptions && plainBlockedHosts.isNotEmpty()) {
            var hit = false
            forEachHostSuffix(request.host) { if (it in plainBlockedHosts) hit = true }
            if (hit && test(PLAIN_HOST_BLOCK)) return
        }
        var stop = false
        forEachHostSuffix(request.host) { suffix ->
            if (stop) return@forEachHostSuffix
            hostFilters[suffix]?.forEach { f ->
                if (!stop && f.exception == exceptions && test(f)) stop = true
            }
        }
        if (stop) return
        val url = request.lowerUrl
        var i = 0
        val n = url.length
        while (i < n) {
            if (!isTokenChar(url[i])) { i++; continue }
            val start = i
            while (i < n && isTokenChar(url[i])) i++
            if (i - start < MIN_TOKEN) continue
            tokenFilters[url.substring(start, i)]?.forEach { f ->
                if (!stop && f.exception == exceptions && test(f)) stop = true
            }
            if (stop) return
        }
        for (f in untokenedFilters) {
            if (f.exception == exceptions && test(f)) return
        }
    }

    internal class Request(
        val url: String,
        val lowerUrl: String,
        val host: String,
        val types: Int,
        val pageHost: String?,
        val thirdParty: Boolean,
    )

    companion object {
        /**
         * Compile [lists] (each one list file's text) into an engine.
         * Lines that aren't filters, or use syntax this engine doesn't
         * support, are skipped — a bad line never fails the build.
         * [checkpoint] runs every [CHECKPOINT_LINES] lines, so a caller
         * can abandon a build that's no longer wanted by throwing from it.
         */
        fun build(lists: List<String>, checkpoint: () -> Unit = {}): AdblockEngine =
            build(lists.map { FilterListText(it) }, null, checkpoint)

        /**
         * [build] with scriptlets: `+js(…)` rules [catalog] vets are kept
         * ([scriptletsFor]); with no catalog, none are.
         */
        fun build(lists: List<FilterListText>, catalog: ScriptletCatalog?, checkpoint: () -> Unit = {}): AdblockEngine =
            Builder(catalog).apply { lists.forEach { addList(it, checkpoint) } }.build()

        private const val CHECKPOINT_LINES = 1024

        private const val EXEMPT_DOCUMENT = 1
        private const val EXEMPT_ELEMHIDE = 2
        private const val EXEMPT_GENERICHIDE = 4

        /** Stands for every option-less `||host^` rule in [plainBlockedHosts]. */
        private val PLAIN_HOST_BLOCK = NetworkFilter(
            exception = false, pattern = null, regex = null, hostAnchor = false,
            startAnchor = false, endAnchor = false, matchCase = false, types = RequestType.ALL_SUBRESOURCES,
            thirdParty = null, includeDomains = null, excludeDomains = null, important = false, exempts = 0,
        )

        internal const val MIN_TOKEN = 2

        /** The [scriptletUnhide] key of `#@#+js()`: every scriptlet off. */
        private const val ALL_SCRIPTLETS = ""
    }

    private class Builder(private val catalog: ScriptletCatalog?) {
        val plainBlockedHosts = HashSet<String>()
        val hostFilters = HashMap<String, MutableList<NetworkFilter>>()
        val tokenFilters = HashMap<String, MutableList<NetworkFilter>>()
        val untokened = ArrayList<NetworkFilter>()
        val pageExceptions = ArrayList<NetworkFilter>()
        val specificHide = HashMap<String, MutableList<CosmeticRule>>()
        val specificUnhide = HashMap<String, MutableSet<String>>()
        val genericKeyed = HashMap<String, MutableList<CosmeticRule>>()
        val genericUnkeyed = ArrayList<CosmeticRule>()
        val globallyUnhidden = HashSet<String>()
        val scriptletRules = HashMap<String, MutableList<ScriptletRule>>()
        val scriptletUnhide = HashMap<String, MutableSet<String>>()
        val redirects = RedirectIndex()
        val counts = ArrayList<FilterListCounts>()
        var count = 0

        /** The list [addList] is on: may it call `trusted-*` scriptlets? */
        private var trusted = false

        /** Scriptlet rules seen / kept in the list [addList] is on. */
        private var scriptletsSeen = 0
        private var scriptletsKept = 0

        /** A rule's place across every list, so a host's calls keep list order. */
        private var order = 0

        fun addList(list: FilterListText, checkpoint: () -> Unit) {
            trusted = list.trustedScriptlets
            scriptletsSeen = 0
            scriptletsKept = 0
            val text = list.text
            var rules = 0
            var used = 0
            var start = 0
            val n = text.length
            var lines = 0
            while (start < n) {
                if (++lines % CHECKPOINT_LINES == 0) checkpoint()
                var end = text.indexOf('\n', start)
                if (end < 0) end = n
                val line = text.substring(start, end).trim()
                start = end + 1
                if (line.isEmpty() || line[0] == '!' || line[0] == '[') continue
                rules++
                val added = runCatching { addLine(line) }.getOrDefault(false)
                if (added) {
                    count++
                    used++
                }
            }
            counts += FilterListCounts(rules, used, scriptletsSeen, scriptletsKept)
        }

        fun addLine(line: String): Boolean {
            val cosmetic = if (line.contains('#')) COSMETIC_RE.find(line) else null
            if (cosmetic != null) return addCosmetic(cosmetic.groupValues[1], cosmetic.groupValues[2], cosmetic.groupValues[3])
            val filter = parseNetworkFilter(line) ?: return false
            val redirect = filter.redirect
            if (redirect != null) {
                // `@@…$redirect-rule` / `@@…$redirect=name`: lifts the
                // stand-in (all of them for no name), never the block.
                if (filter.exception) {
                    val canonical = if (redirect.isEmpty()) "" else (catalog?.redirect(redirect)?.canonical ?: return false)
                    redirects.addException(filter, canonical)
                    return true
                }
                val resource = catalog?.redirect(redirect)
                if (resource != null) redirects.add(filter, resource.canonical)
                // `$redirect-rule=` only names a stand-in; `$redirect=` blocks
                // too (with the plain 403 if its stand-in isn't one we have).
                if (filter.redirectOnly) return resource != null
            }
            if (filter.exempts != 0) {
                pageExceptions += filter
                // A `$document` exception is also an ordinary exception.
                if (filter.types == 0) return true
            }
            val host = filter.plainHost()
            when {
                host != null && !filter.exception && filter.hasNoOptions() -> plainBlockedHosts += host
                host != null -> hostFilters.getOrPut(host) { ArrayList(1) } += filter
                else -> {
                    val token = filter.bestToken()
                    if (token == null) untokened += filter
                    else tokenFilters.getOrPut(token) { ArrayList(1) } += filter
                }
            }
            return true
        }

        fun addCosmetic(domainsText: String, separatorText: String, selectorText: String): Boolean {
            // `#?#` / `#@?#` (Adblock Plus's extended selectors, also
            // uBlock's) are `##` / `#@#` once the selector is one CSS can
            // take: `:-abp-has()` is `:has()`, which Chromium runs natively.
            val separator = when (separatorText) {
                "#?#" -> "##"
                "#@?#" -> "#@#"
                else -> separatorText
            }
            if (separator != "##" && separator != "#@#") return false
            val raw = selectorText.trim()
            if (raw.startsWith("+js(")) return addScriptlet(domainsText, separator == "#@#", raw)
            val parsed = parseCosmeticSelector(raw) ?: return false
            val include = ArrayList<String>()
            val exclude = ArrayList<String>()
            if (domainsText.isNotEmpty()) {
                var droppedInclude = false
                for (rawDomain in domainsText.split(',')) {
                    val d = rawDomain.trim().lowercase()
                    val negated = d.startsWith("~")
                    val name = if (negated) d.substring(1) else d
                    // A host, or a uBlock entity (`example.*`, the name under
                    // any public suffix), matched as scriptlets are
                    // ([scriptletKeys]); anything else (`/regex/`, `>>`) is
                    // one this can't match.
                    if (!isScriptletDomain(name)) {
                        if (!negated) droppedInclude = true
                        continue
                    }
                    if (negated) exclude += name else include += name
                }
                if (include.isEmpty() && exclude.isEmpty()) return false
                // A rule scoped to sites this can't match must not fall
                // back to every site but its `~` ones (#318 R6-F1).
                if (include.isEmpty() && droppedInclude) return false
            }
            // Exceptions name the rule as written (`sel:style(…)` included),
            // as uBlock compares them.
            val key = parsed.key
            if (separator == "#@#") {
                if (include.isEmpty()) {
                    if (exclude.isNotEmpty()) return false
                    globallyUnhidden += key
                } else {
                    for (d in include) specificUnhide.getOrPut(d) { HashSet() } += key
                }
                return true
            }
            val rule = CosmeticRule(parsed.selector, exclude.takeIf { it.isNotEmpty() }?.toTypedArray(), parsed.style, key)
            if (include.isNotEmpty()) {
                for (d in include) specificHide.getOrPut(d) { ArrayList(1) } += rule
            } else {
                val token = cosmeticKey(parsed.selector)
                if (token == null) genericUnkeyed += rule
                else genericKeyed.getOrPut(token) { ArrayList(1) } += rule
            }
            return true
        }

        /**
         * A `+js(…)` rule or `#@#+js(…)` exception. A rule counts as kept
         * only if it will run: parsed, a vetted scriptlet (a trusted one
         * only from a trusted list), and scoped to hosts this can match.
         */
        fun addScriptlet(domainsText: String, exception: Boolean, selector: String): Boolean {
            if (!exception) scriptletsSeen++
            val catalog = catalog ?: return false
            if (!selector.endsWith(")")) return false
            val body = selector.substring(4, selector.length - 1)
            val include = ArrayList<String>()
            val exclude = ArrayList<String>()
            if (domainsText.isNotEmpty()) {
                for (raw in domainsText.split(',')) {
                    val d = raw.trim().lowercase()
                    val negated = d.startsWith("~")
                    val name = if (negated) d.substring(1) else d
                    // `*` is "every site"; anything that isn't a host or an
                    // entity (uBlock's `>>` frame syntax, say) is one this
                    // can't match: the rule is kept to its other hosts, or
                    // dropped if it has none.
                    if (name == "*" && !negated) { include += name; continue }
                    if (!isScriptletDomain(name)) continue
                    if (negated) exclude += name else include += name
                }
            }
            if (exception) {
                if (include.isEmpty() || "*" in include) return false
                val key = if (body.isBlank()) ALL_SCRIPTLETS else (parseScriptletCall(body, catalog)?.key ?: return false)
                for (d in include) scriptletUnhide.getOrPut(d) { HashSet() } += key
                return true
            }
            // Generic (no host, or `*`): would have to run in every frame.
            if (include.isEmpty() || "*" in include) return false
            val call = parseScriptletCall(body, catalog) ?: return false
            if (!catalog.isVetted(call.name)) return false
            if (catalog.requiresTrust(call.name) && !trusted) return false
            val rule = ScriptletRule(call, exclude.takeIf { it.isNotEmpty() }?.toHashSet(), order++)
            for (d in include) scriptletRules.getOrPut(d) { ArrayList(1) } += rule
            scriptletsKept++
            return true
        }

        fun build(): AdblockEngine {
            if (globallyUnhidden.isNotEmpty()) {
                genericUnkeyed.removeAll { it.key in globallyUnhidden }
                for (rules in genericKeyed.values) rules.removeAll { it.key in globallyUnhidden }
                for (rules in specificHide.values) rules.removeAll { it.key in globallyUnhidden }
            }
            return AdblockEngine(
                plainBlockedHosts, hostFilters, tokenFilters, untokened, pageExceptions,
                specificHide, specificUnhide, genericKeyed, genericUnkeyed,
                scriptletRules, scriptletUnhide, redirects, count, counts,
            )
        }
    }
}

/**
 * The request types filter options name, as bits. WebView doesn't say
 * which one a request is, so [requestTypes] answers with a set.
 */
internal object RequestType {
    const val SCRIPT = 1
    const val IMAGE = 1 shl 1
    const val STYLESHEET = 1 shl 2
    const val OBJECT = 1 shl 3
    const val XHR = 1 shl 4
    const val SUBDOCUMENT = 1 shl 5
    const val PING = 1 shl 6
    const val MEDIA = 1 shl 7
    const val FONT = 1 shl 8
    const val WEBSOCKET = 1 shl 9
    const val OTHER = 1 shl 10
    const val DOCUMENT = 1 shl 11

    /** Everything but the top-level document: what an option-less filter covers. */
    const val ALL_SUBRESOURCES = (1 shl 11) - 1

    /** A load with a wildcard `Accept`: a script, a fetch / XHR, a beacon, media, a plugin, a worker. */
    const val UNKNOWN = SCRIPT or XHR or PING or MEDIA or OBJECT or OTHER

    internal val BY_OPTION = mapOf(
        "script" to SCRIPT,
        "image" to IMAGE,
        "stylesheet" to STYLESHEET,
        "css" to STYLESHEET,
        "object" to OBJECT,
        "object-subrequest" to OBJECT,
        "xmlhttprequest" to XHR,
        "xhr" to XHR,
        "subdocument" to SUBDOCUMENT,
        "frame" to SUBDOCUMENT,
        "ping" to PING,
        "beacon" to PING,
        "media" to MEDIA,
        "font" to FONT,
        "websocket" to WEBSOCKET,
        "other" to OTHER,
        "document" to DOCUMENT,
        "doc" to DOCUMENT,
    )
}

private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "gif", "webp", "avif", "svg", "ico", "bmp")
private val FONT_EXT = setOf("woff", "woff2", "ttf", "otf", "eot")
private val MEDIA_EXT = setOf("mp4", "webm", "mp3", "m4a", "ogg", "oga", "ogv", "wav", "m3u8", "mpd", "ts", "aac", "flac")

/**
 * What a subresource request can be, from what WebView tells us: its
 * `Accept` header (Chromium's is type-specific for stylesheets, images
 * and frames), a `Range` header (media), then the file extension of the
 * URL's path. A wildcard-`Accept` load with no telling extension could be a
 * script, a fetch, a beacon or media — [RequestType.UNKNOWN].
 */
internal fun requestTypes(url: String, headers: Map<String, String>?): Int {
    fun header(name: String) =
        headers?.entries?.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.trim()?.lowercase()
    val accept = header("Accept").orEmpty()
    when {
        accept.startsWith("text/css") -> return RequestType.STYLESHEET
        accept.startsWith("image/") -> return RequestType.IMAGE
        accept.startsWith("text/html") -> return RequestType.SUBDOCUMENT
    }
    if (header("Range") != null) return RequestType.MEDIA
    val path = url.substringBefore('#').substringBefore('?')
    val slash = path.lastIndexOf('/')
    val dot = path.lastIndexOf('.')
    if (dot > slash && slash >= 0) {
        when (val ext = path.substring(dot + 1).lowercase()) {
            "js", "mjs" -> return RequestType.SCRIPT
            "css" -> return RequestType.STYLESHEET
            "json" -> return RequestType.XHR
            in IMAGE_EXT -> return RequestType.IMAGE
            in FONT_EXT -> return RequestType.FONT
            in MEDIA_EXT -> return RequestType.MEDIA
            else -> if (ext == "html" || ext == "htm") return RequestType.UNKNOWN or RequestType.SUBDOCUMENT
        }
    }
    return RequestType.UNKNOWN
}

/**
 * Is a load of [host] from a page on [pageHost] third-party — are their
 * registrable domains different? An unknown page counts as third-party.
 */
internal fun isThirdParty(host: String, pageHost: String?): Boolean {
    if (pageHost == null) return true
    if (host == pageHost) return false
    val a = PublicSuffixList.registrableDomain(host) ?: host
    val b = PublicSuffixList.registrableDomain(pageHost) ?: pageHost
    return a != b
}

/** Call [block] with [host] and each of its parent domains (`a.b.c`, `b.c`, `c`). */
internal inline fun forEachHostSuffix(host: String, block: (String) -> Unit) {
    var start = 0
    while (true) {
        block(if (start == 0) host else host.substring(start))
        val dot = host.indexOf('.', start)
        if (dot < 0 || dot == host.length - 1) return
        start = dot + 1
    }
}

/** The lower-case host of an http(s) [url], or `null`. */
internal fun hostOfUrl(url: String): String? {
    val schemeEnd = url.indexOf("://")
    if (schemeEnd <= 0) return null
    var start = schemeEnd + 3
    var end = start
    while (end < url.length && url[end] != '/' && url[end] != '?' && url[end] != '#') end++
    val at = url.lastIndexOf('@', end - 1)
    if (at >= start) start = at + 1
    var authority = url.substring(start, end)
    authority = if (authority.startsWith("[")) {
        authority.substringBefore(']') + "]"
    } else {
        authority.substringBefore(':')
    }
    return authority.lowercase().trimEnd('.').ifEmpty { null }
}

private fun isTokenChar(c: Char) = c in 'a'..'z' || c in '0'..'9' || c == '%'

/** `domains#@#selector` / `domains##selector`, plus the extensions that are recognised only to be skipped. */
private val COSMETIC_RE = Regex("^([^/|@\"!]*?)(#@?#|#@?[?$%]#)(.+)$")

private val PROCEDURAL = listOf(
    ":-abp-", ":has-text(", ":xpath(", ":matches-", ":upward(", ":remove(", ":style(",
    ":min-text-length(", ":watch-attr(", ":others(", ":if(", ":if-not(", ":nth-ancestor(",
    ":contains(", ":matches-path(", ":remove-attr(", ":remove-class(",
)

/** A cosmetic rule's selector as CSS can take it, what it does there, and its text as written ([key]). */
internal class CosmeticSelector(val selector: String, val style: String?, val key: String)

/**
 * A `##` rule's selector as CSS can take it, or `null` when it can't be:
 * scriptlets, HTML filters (`##^`), and procedural selectors CSS has no
 * equivalent for (`:has-text()`, `:upward()`, `:xpath()`, …, which need
 * a script walking the DOM). Translated on the way:
 *
 * - `:-abp-has(…)` (Adblock Plus, `#?#`) is CSS's own `:has(…)`;
 * - a trailing `:style(declarations)` (uBlock) styles the elements with
 *   those declarations instead of hiding them — never with ones that can
 *   reach the network (`url(…)`, `image-set(…)`, `@import`) or escape
 *   the rule (`{`, `}`, `\`, comments);
 * - a trailing `:remove()` (uBlock: take the element out) hides it, which
 *   is what the page sees of it as far as layout goes.
 */
internal fun parseCosmeticSelector(raw: String): CosmeticSelector? {
    if (raw.isEmpty() || raw.length > 4096) return null
    if (raw.startsWith("+js(") || raw.startsWith("^")) return null
    // A selector may not smuggle in a declaration block or a second rule.
    if (raw.contains('{') || raw.contains('}')) return null
    var selector = if (raw.contains(":-abp-has(")) raw.replace(":-abp-has(", ":has(") else raw
    var style: String? = null
    if (selector.endsWith(":remove()")) {
        selector = selector.dropLast(":remove()".length)
    } else if (selector.endsWith(")")) {
        val at = selector.lastIndexOf(":style(")
        if (at > 0) {
            val declarations = selector.substring(at + ":style(".length, selector.length - 1).trim()
            if (!isSafeDeclarations(declarations)) return null
            style = declarations
            selector = selector.substring(0, at)
        }
    }
    selector = selector.trim()
    if (selector.isEmpty()) return null
    if (PROCEDURAL.any { selector.contains(it) }) return null
    return CosmeticSelector(selector, style, raw)
}

/** May a `:style(…)` rule set [declarations]? Only plain property values: nothing that loads, nothing that escapes the rule. */
private fun isSafeDeclarations(declarations: String): Boolean {
    if (declarations.isEmpty() || declarations.length > 1024) return false
    if (declarations.any { it == '{' || it == '}' || it == '\\' || it == '<' || it == '>' || it == '@' || it.code < 0x20 }) {
        return false
    }
    val lower = declarations.lowercase()
    return "url(" !in lower && "image-set(" !in lower && "image(" !in lower && "/*" !in lower && "expression(" !in lower
}

/**
 * The `.class` / `#id` a generic selector starts with, as the page
 * script reports it (`.ad-banner`, `#sidebar`) — or `null` when it
 * starts with anything else, or the name is escaped. Only a leading
 * compound can key a selector: whatever else it asks, it can't match
 * unless an element carries that name.
 */
internal fun cosmeticKey(selector: String): String? {
    if (selector.length < 2) return null
    val sigil = selector[0]
    if (sigil != '.' && sigil != '#') return null
    var i = 1
    while (i < selector.length) {
        val c = selector[i]
        if (c.isLetterOrDigit() || c == '-' || c == '_' || c.code > 0x7f) { i++; continue }
        if (c == '\\') return null
        break
    }
    if (i == 1) return null
    return selector.substring(0, i)
}

/** One selector per rule: an invalid selector costs only its own rule, not the batch. */
private fun cssFor(rules: Collection<CosmeticRule>): String {
    if (rules.isEmpty()) return ""
    val sb = StringBuilder(rules.size * 48)
    for (r in rules) {
        sb.append(r.selector).append('{')
        if (r.style == null) sb.append("display:none!important") else sb.append(r.style)
        sb.append("}\n")
    }
    return sb.toString()
}

/**
 * A `##` rule: hide the elements [selector] matches there, or give them
 * [style] (a `:style()` rule). [key] is the rule as written, which `#@#`
 * exceptions name. [excludeDomains] are its `~` hosts and entities.
 */
internal class CosmeticRule(
    val selector: String,
    private val excludeDomains: Array<String>?,
    val style: String? = null,
    val key: String = selector,
) {
    /** Does the rule apply on a frame whose [scriptletKeys] (host, parents, entities) are [keys]? */
    fun appliesTo(keys: List<String>): Boolean {
        val ex = excludeDomains ?: return true
        for (k in keys) if (k in ex) return false
        return true
    }
}

internal class NetworkFilter(
    val exception: Boolean,
    /** Lower-cased (unless [matchCase]) pattern without anchors; `null` for a regex or the plain-host stand-in. */
    val pattern: String?,
    val regex: Regex?,
    val hostAnchor: Boolean,
    val startAnchor: Boolean,
    val endAnchor: Boolean,
    val matchCase: Boolean,
    val types: Int,
    val thirdParty: Boolean?,
    val includeDomains: Array<String>?,
    val excludeDomains: Array<String>?,
    val important: Boolean,
    /** For page exceptions: which of `document` / `elemhide` / `generichide` it lifts. */
    val exempts: Int,
    /**
     * A `$redirect=` / `$redirect-rule=` filter's resource name as
     * written (#393), `""` on an exception naming none; else `null`.
     */
    val redirect: String? = null,
    /** `$redirect-rule=`: names a stand-in for what other filters block, blocks nothing itself. */
    val redirectOnly: Boolean = false,
    /** The `:priority` after a redirect's name (`noopjs:10`); 0 when none. */
    val redirectPriority: Int = 0,
) {
    fun hasNoOptions() = types == RequestType.ALL_SUBRESOURCES && thirdParty == null &&
        includeDomains == null && excludeDomains == null && !important && !matchCase

    /** The host of a `||host^` filter with nothing after the `^`, else `null`. */
    fun plainHost(): String? {
        val p = pattern ?: return null
        if (!hostAnchor || endAnchor || !p.endsWith("^")) return null
        val host = p.substring(0, p.length - 1)
        if (host.isEmpty() || !host.all { it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '-' }) return null
        return host
    }

    /** The longest token pinned on both sides, skipping ones nearly every URL has. */
    fun bestToken(): String? {
        val p = pattern ?: return null
        if (matchCase) return null // tokens are looked up in the lower-cased URL
        var best: String? = null
        var i = 0
        while (i < p.length) {
            if (!isTokenChar(p[i])) { i++; continue }
            val start = i
            while (i < p.length && isTokenChar(p[i])) i++
            val leftOk = if (start == 0) hostAnchor || startAnchor else p[start - 1] != '*'
            val rightOk = if (i == p.length) endAnchor else p[i] != '*'
            if (!leftOk || !rightOk || i - start < AdblockEngine.MIN_TOKEN) continue
            val token = p.substring(start, i)
            if (token in COMMON_TOKENS) continue
            if (best == null || token.length > best.length) best = token
        }
        return best
    }

    fun matches(request: AdblockEngine.Request, anyType: Boolean = false): Boolean {
        if (!anyType && types and request.types == 0) return false
        if (thirdParty != null && thirdParty != request.thirdParty) return false
        if (includeDomains != null || excludeDomains != null) {
            val page = request.pageHost ?: return false
            var included = includeDomains == null
            var excluded = false
            forEachHostSuffix(page) {
                if (includeDomains != null && it in includeDomains) included = true
                if (excludeDomains != null && it in excludeDomains) excluded = true
            }
            if (!included || excluded) return false
        }
        if (regex != null) return regex.containsMatchIn(request.url)
        val p = pattern ?: return true
        val url = if (matchCase) request.url else request.lowerUrl
        if (hostAnchor) {
            val schemeEnd = url.indexOf("://")
            if (schemeEnd < 0) return false
            val hostStart = schemeEnd + 3
            var hostEnd = hostStart
            while (hostEnd < url.length && url[hostEnd] != '/' && url[hostEnd] != '?' && url[hostEnd] != '#') hostEnd++
            // The pattern starts at a label of the host (after any userinfo).
            var pos = hostStart
            while (pos < hostEnd) {
                if (pos == hostStart || url[pos - 1] == '.' || url[pos - 1] == '@') {
                    if (matchHere(p, 0, url, pos)) return true
                }
                pos++
            }
            return false
        }
        if (startAnchor) return matchHere(p, 0, url, 0)
        val first = p.firstOrNull()
        if (first == null || first == '*' || first == '^') {
            for (pos in 0..url.length) {
                if (matchHere(p, 0, url, pos)) return true
            }
            return false
        }
        // A literal first character: jump between its occurrences.
        var pos = url.indexOf(first)
        while (pos >= 0) {
            if (matchHere(p, 0, url, pos)) return true
            pos = url.indexOf(first, pos + 1)
        }
        return false
    }

    /** ABP glob matching of `p[pi..]` against `url[ui..]`: `*` any run, `^` a separator or the end. */
    private fun matchHere(p: String, pi0: Int, url: String, ui0: Int): Boolean {
        var pi = pi0
        var ui = ui0
        while (pi < p.length) {
            when (val c = p[pi]) {
                '*' -> {
                    while (pi < p.length && p[pi] == '*') pi++
                    if (pi == p.length) return true
                    for (k in ui..url.length) {
                        if (matchHere(p, pi, url, k)) return true
                    }
                    return false
                }
                '^' -> {
                    if (ui == url.length) {
                        // `^` matches the end once; anything after it must too.
                        pi++
                        while (pi < p.length && (p[pi] == '^' || p[pi] == '*')) pi++
                        return pi == p.length
                    }
                    if (!isSeparator(url[ui])) return false
                    pi++; ui++
                }
                else -> {
                    if (ui >= url.length || url[ui] != c) return false
                    pi++; ui++
                }
            }
        }
        return !endAnchor || ui == url.length
    }
}

private fun isSeparator(c: Char): Boolean =
    !(c.isLetterOrDigit() || c == '_' || c == '-' || c == '.' || c == '%')

private val COMMON_TOKENS = setOf("http", "https", "www", "com", "js", "html", "net", "org")

/**
 * One network filter line → a [NetworkFilter], or `null` when the line
 * uses an option this engine doesn't honour or can never apply to a
 * subresource.
 */
internal fun parseNetworkFilter(line: String): NetworkFilter? {
    var text = line
    val exception = text.startsWith("@@")
    if (exception) text = text.substring(2)

    var options: String? = null
    // A regex's body may hold a `$` of its own, so a whole `/…/` has no
    // options; otherwise they follow the last `$` — and what's left may
    // still be a `/regex/` (`/pixel[0-9]+\.gif/$image`, R1-F2).
    if (!(text.length > 2 && text.startsWith("/") && text.endsWith("/"))) {
        val dollar = text.lastIndexOf('$')
        if (dollar >= 0) {
            options = text.substring(dollar + 1)
            text = text.substring(0, dollar)
        }
    }
    val isRegex = text.length > 2 && text.startsWith("/") && text.endsWith("/")

    var types = 0
    var negatedTypes = 0
    var thirdParty: Boolean? = null
    var include: MutableList<String>? = null
    var exclude: MutableList<String>? = null
    var matchCase = false
    var important = false
    var exempts = 0
    var popupOnly = false
    var redirect: String? = null
    var redirectOnly = false
    var redirectPriority = 0
    if (options != null) {
        for (raw in options.split(',')) {
            val opt = raw.trim().lowercase()
            if (opt.isEmpty()) return null
            val negated = opt.startsWith("~")
            val name = opt.removePrefix("~")
            val type = RequestType.BY_OPTION[name]
            when {
                type != null -> if (negated) negatedTypes = negatedTypes or type else types = types or type
                name == "third-party" || name == "3p" -> thirdParty = !negated
                name == "first-party" || name == "1p" -> thirdParty = negated
                name == "match-case" -> matchCase = true
                name == "important" -> important = true
                name == "popup" -> if (negated) Unit else popupOnly = true
                // #393: uBlock's `redirect` / `redirect-rule` and Adblock
                // Plus's `rewrite=abp-resource:` (its names are aliases in
                // uBlock's resources). `none` turns redirection off in
                // uBlock: not something a filter here needs to say.
                !negated && (name == "redirect" || name == "redirect-rule") -> {
                    if (!exception) return null // a bare `$redirect` only lifts, on an exception
                    redirect = ""
                    redirectOnly = true
                }
                !negated && (name.startsWith("redirect=") || name.startsWith("redirect-rule=")) -> {
                    val value = raw.trim().substringAfter('=')
                    val colon = value.lastIndexOf(':')
                    val resource = if (colon > 0) value.substring(0, colon) else value
                    if (colon > 0) redirectPriority = value.substring(colon + 1).toIntOrNull() ?: return null
                    if (resource.isEmpty() || resource == "none") return null
                    redirect = resource
                    redirectOnly = name.startsWith("redirect-rule=")
                }
                !negated && name.startsWith("rewrite=abp-resource:") -> redirect = raw.trim().substringAfter('=')
                (name == "elemhide" || name == "ehide") && exception -> exempts = exempts or 2
                (name == "generichide" || name == "ghide") && exception -> exempts = exempts or 4
                (opt.startsWith("domain=") || opt.startsWith("from=")) -> {
                    var droppedInclude = false
                    for (d in raw.trim().substringAfter('=').split('|')) {
                        val dom = d.trim().lowercase()
                        if (dom.startsWith("~")) {
                            val n = dom.substring(1)
                            if (n.isNotEmpty() && !n.endsWith(".*")) (exclude ?: ArrayList<String>().also { exclude = it }) += n
                        } else if (dom.isNotEmpty() && !dom.endsWith(".*")) {
                            (include ?: ArrayList<String>().also { include = it }) += dom
                        } else if (dom.isNotEmpty()) {
                            droppedInclude = true
                        }
                    }
                    if (include == null && exclude == null) return null
                    // `domain=google.*|~www.google.com` names only sites this can't
                    // match: dropped, not turned into a filter for every other site.
                    if (include == null && droppedInclude) return null
                }
                else -> return null // An option we don't honour: drop the whole filter.
            }
        }
    }

    // `$document` on an exception exempts the page; on a block it
    // would block a navigation, which the browser never does.
    if (types and RequestType.DOCUMENT != 0) {
        if (exception) exempts = exempts or 1
        types = types and RequestType.DOCUMENT.inv()
        if (types == 0 && negatedTypes == 0 && !exception) return null
    }
    var mask = when {
        types != 0 -> types
        negatedTypes != 0 -> RequestType.ALL_SUBRESOURCES and negatedTypes.inv()
        // Only `$popup` / `$elemhide` / … and no type: no subresource.
        popupOnly || exempts != 0 -> 0
        else -> RequestType.ALL_SUBRESOURCES
    }
    if (types != 0 && negatedTypes != 0) mask = types and negatedTypes.inv()
    if (mask == 0 && exempts == 0) return null

    var regex: Regex? = null
    var hostAnchor = false
    var startAnchor = false
    var endAnchor = false
    var pattern: String? = null
    if (isRegex) {
        val body = text.substring(1, text.length - 1)
        regex = runCatching {
            if (matchCase) Regex(body) else Regex(body, RegexOption.IGNORE_CASE)
        }.getOrNull() ?: return null
    } else {
        if (text.startsWith("||")) {
            hostAnchor = true; text = text.substring(2)
        } else if (text.startsWith("|")) {
            startAnchor = true; text = text.substring(1)
        }
        if (text.endsWith("|")) {
            endAnchor = true; text = text.substring(0, text.length - 1)
        }
        if (!matchCase) text = text.lowercase()
        // A bare `*` or empty pattern matches every URL; fine for a page
        // exception scoped by domain, never for a block.
        if (text.trim('*').isEmpty() && !exception && include == null) return null
        pattern = text
    }
    return NetworkFilter(
        exception = exception,
        pattern = pattern,
        regex = regex,
        hostAnchor = hostAnchor,
        startAnchor = startAnchor,
        endAnchor = endAnchor,
        matchCase = matchCase,
        types = mask,
        thirdParty = thirdParty,
        includeDomains = include?.toTypedArray(),
        excludeDomains = exclude?.toTypedArray(),
        important = important,
        exempts = exempts,
        redirect = redirect,
        redirectOnly = redirectOnly,
        redirectPriority = redirectPriority,
    )
}

/**
 * The `$redirect=` / `$redirect-rule=` filters (#393) and their
 * exceptions, for [AdblockEngine.redirectFor]: asked only once a
 * request is blocked. Filed under a token as the blocking filters are;
 * the token-less rest (mostly `*$script,redirect-rule=…,domain=…`)
 * under the `domain=` pages they apply on, so a blocked request walks
 * only its own page's; the few with neither in one list it always walks.
 */
internal class RedirectIndex {
    /** [order]: its place in the lists, for a priority tie. */
    private class Directive(val filter: NetworkFilter, val resource: String, val order: Int)

    private val byToken = HashMap<String, MutableList<Directive>>()

    /** Token-less directives scoped by `domain=`, under each of their pages' domains: most of them. */
    private val byPageDomain = HashMap<String, MutableList<Directive>>()
    private val untokened = ArrayList<Directive>()
    private val exceptions = ArrayList<Directive>()
    private var order = 0

    fun isEmpty() = byToken.isEmpty() && byPageDomain.isEmpty() && untokened.isEmpty()

    fun add(filter: NetworkFilter, resource: String) {
        val d = Directive(filter, resource, order++)
        val token = filter.bestToken()
        val domains = filter.includeDomains
        when {
            token != null -> byToken.getOrPut(token) { ArrayList(1) } += d
            domains != null -> for (dom in domains) byPageDomain.getOrPut(dom) { ArrayList(1) } += d
            else -> untokened += d
        }
    }

    /** [resource] `""`: every stand-in. */
    fun addException(filter: NetworkFilter, resource: String) {
        exceptions += Directive(filter, resource, -1)
    }

    fun resolve(request: AdblockEngine.Request): String? {
        var best: Directive? = null
        fun consider(d: Directive) {
            if (!d.filter.matches(request)) return
            val b = best
            if (b == null || d.filter.redirectPriority > b.filter.redirectPriority ||
                (d.filter.redirectPriority == b.filter.redirectPriority && d.order < b.order)
            ) {
                best = d
            }
        }
        val url = request.lowerUrl
        var i = 0
        val n = url.length
        while (i < n) {
            if (!isTokenChar(url[i])) { i++; continue }
            val start = i
            while (i < n && isTokenChar(url[i])) i++
            if (i - start < AdblockEngine.MIN_TOKEN) continue
            byToken[url.substring(start, i)]?.forEach(::consider)
        }
        request.pageHost?.let { page -> forEachHostSuffix(page) { byPageDomain[it]?.forEach(::consider) } }
        untokened.forEach(::consider)
        val chosen = best ?: return null
        for (e in exceptions) {
            if ((e.resource.isEmpty() || e.resource == chosen.resource) && e.filter.matches(request)) return null
        }
        return chosen.resource
    }
}
