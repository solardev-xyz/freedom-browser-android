package baby.freedom.mobile.browser

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.webkit.WebResourceResponse
import baby.freedom.mobile.data.NodeSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

private const val TAG = "Adblock"

/**
 * The filter-list categories (#126), as on desktop and iOS: ads and
 * trackers on by default, cookie notices and other annoyances opt-in.
 * [file] is the list's name under `assets/adblock/`
 * (`infra/adblock/vendor-lists.py` refreshes them).
 */
enum class AdblockCategory(
    val key: String,
    val file: String,
    val title: String,
    val listName: String,
    val enabledByDefault: Boolean,
) {
    ADS("ads", "easylist.txt", "Block ads", "EasyList", true),
    PRIVACY("privacy", "easyprivacy.txt", "Block trackers", "EasyPrivacy", true),
    COOKIES("cookies", "fanboy-cookiemonster.txt", "Block cookie notices", "Fanboy's Cookiemonster", false),
    ANNOYANCES("annoyances", "fanboy-annoyance.txt", "Block other annoyances", "Fanboy's Annoyances", false),
}

/**
 * The allowlist's form of a site: lower-case host, no scheme, path,
 * port or trailing dot, and no leading `www.` — or `null` for input
 * that isn't a host (no dot, a space, …). Takes a URL or a bare host,
 * as the Settings field and the page menu both hand it one.
 */
internal fun normalizeAllowlistHost(input: String?): String? {
    var s = input?.trim()?.lowercase() ?: return null
    if (s.isEmpty()) return null
    if (s.contains("://")) s = hostOfUrl(s) ?: return null
    s = s.substringBefore('/').substringBefore('?').substringBefore('#')
    if (s.startsWith("[")) return null
    s = s.substringBefore(':').trimEnd('.')
    if (s.startsWith("www.")) s = s.removePrefix("www.")
    if (s.isEmpty() || !s.contains('.') || s.startsWith('.')) return null
    if (!s.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }) return null
    return s
}

/**
 * Is [host] on the [allowlist] — an entry itself or a subdomain of one?
 * `m.news.example` is covered by `news.example`; `badnews.example` is not.
 */
internal fun isAllowlisted(host: String?, allowlist: Collection<String>): Boolean {
    if (allowlist.isEmpty()) return false
    val h = normalizeAllowlistHost(host) ?: return false
    var hit = false
    forEachHostSuffix(h) { if (it in allowlist) hit = true }
    return hit
}

/**
 * The site the page menu's ad-blocking switch is for, for the tab's
 * address [url]: an ordinary http(s) page's host in allowlist form, or
 * `null` for home, dweb content (`bzz://`, `ens://`, … — the address
 * the tab shows isn't a host) and the local nodes.
 */
internal fun adblockSiteFor(url: String): String? {
    if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) return null
    val host = hostOfUrl(url) ?: return null
    if (host == "localhost" || host == "127.0.0.1" || VirtualOrigin.isVirtualHost(host)) return null
    return normalizeAllowlistHost(host)
}

/** What Settings shows about the engine. */
internal data class AdblockStatus(val loading: Boolean, val filterCount: Int)

/**
 * The ad blocker at runtime (#126): the [AdblockEngine] for the
 * categories switched on in Settings, and the per-site allowlist.
 *
 * The engine is compiled from the bundled lists off the main thread at
 * startup, and again whenever the categories change; until the first
 * build lands (about a second), requests go through unfiltered rather
 * than wait. The swap is atomic — a request sees the old engine or the
 * new one. The allowlist is read with the categories, and published
 * before the first engine, so an allowlisted site is never blocked
 * while settings load.
 *
 * Private tabs (#86) see the saved allowlist, but a site they allow is
 * kept for the private session only, in memory, like their site
 * permissions — it never reaches the settings file.
 */
internal object Adblock {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var engine: AdblockEngine? = null

    @Volatile
    private var allowlist: Set<String> = emptySet()

    @Volatile
    private var privateAllowlist: Set<String> = emptySet()

    private val _status = MutableStateFlow(AdblockStatus(loading = true, filterCount = 0))
    val status: StateFlow<AdblockStatus> = _status.asStateFlow()

    /** Bumped whenever blocking decisions may change, so the page menu re-reads its row. */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private var started = false

    /** Start following Settings. Idempotent; call from the activity's `onCreate`. */
    fun start(context: Context) {
        synchronized(this) {
            if (started) return
            started = true
        }
        val app = context.applicationContext
        val settings = NodeSettings.get(app)
        scope.launch {
            var builtFor: Set<AdblockCategory>? = null
            combine(settings.adblockCategories, settings.adblockAllowlist) { c, a -> c to a }
                .distinctUntilChanged()
                .collect { (categories, saved) ->
                    allowlist = saved.toSet()
                    _revision.value++
                    if (categories == builtFor) return@collect
                    builtFor = categories
                    _status.value = _status.value.copy(loading = true)
                    val built = withContext(Dispatchers.IO) { build(app, categories) }
                    engine = built
                    _status.value = AdblockStatus(loading = false, filterCount = built?.filterCount ?: 0)
                    _revision.value++
                }
        }
    }

    private fun build(context: Context, categories: Set<AdblockCategory>): AdblockEngine? {
        if (categories.isEmpty()) return null
        val t0 = SystemClock.elapsedRealtime()
        val texts = AdblockCategory.entries.filter { it in categories }.mapNotNull { category ->
            // A missing or unreadable list costs its own category only.
            runCatching {
                context.assets.open("adblock/${category.file}").bufferedReader().use { it.readText() }
            }.onFailure { Log.w(TAG, "list ${category.file} unreadable", it) }.getOrNull()
        }
        if (texts.isEmpty()) return null
        val built = AdblockEngine.build(texts)
        Log.i(
            TAG,
            "engine ready: ${categories.joinToString { it.key }}, ${built.filterCount} filters " +
                "in ${SystemClock.elapsedRealtime() - t0} ms",
        )
        return built
    }

    /** Is ad blocking off for [host] in a tab that is [private] or not? */
    fun isAllowlisted(host: String?, private: Boolean): Boolean =
        isAllowlisted(host, allowlist) || (private && isAllowlisted(host, privateAllowlist))

    /**
     * Allow ([allowed] true) or block ads on [host] again, from the
     * page menu of a tab that is [private] or not. Takes effect for the
     * next request at once — the caller reloads right after — and is
     * saved in the background. A private tab's allow is kept for the
     * private session only; lifting an allow saved from a normal tab
     * lifts it for good, from either.
     */
    fun setAllowlisted(context: Context, host: String, allowed: Boolean, private: Boolean) {
        val entry = normalizeAllowlistHost(host) ?: return
        if (allowed) {
            if (private) privateAllowlist = privateAllowlist + entry
            else allowlist = allowlist + entry
        } else {
            // Every entry covering the host, so the page really is blocked again.
            val covering = { list: Set<String> -> list.filter { isAllowlisted(entry, setOf(it)) }.toSet() }
            privateAllowlist = privateAllowlist - covering(privateAllowlist)
            val saved = covering(allowlist)
            allowlist = allowlist - saved
            if (saved.isNotEmpty()) {
                val settings = NodeSettings.get(context)
                scope.launch { saved.forEach { settings.removeAdblockAllowlistHost(it) } }
            }
        }
        _revision.value++
        if (allowed && !private) {
            val settings = NodeSettings.get(context)
            scope.launch { settings.addAdblockAllowlistHost(entry) }
        }
    }

    /** Settings' remove button: exactly the saved entry [site], its parents and children kept. */
    fun removeAllowlisted(context: Context, site: String) {
        allowlist = allowlist - site
        _revision.value++
        val settings = NodeSettings.get(context)
        scope.launch { settings.removeAdblockAllowlistHost(site) }
    }

    /** The last private tab has closed: forget what it allowed. */
    fun onPrivateSessionEnded() {
        privateAllowlist = emptySet()
        _revision.value++
    }

    /**
     * Should a subresource request be blocked? [pageUrl] is the tab's
     * top-level document, [private] whether the tab is private.
     * Called on WebView's IO threads for every request; never blocks a
     * main-frame load, the local nodes' gateways, or a virtual origin.
     */
    fun shouldBlock(url: String, headers: Map<String, String>?, pageUrl: String?, private: Boolean): Boolean {
        val e = engine ?: return false
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        val host = hostOfUrl(url) ?: return false
        if (isExempt(host) || Gateways.isLocalGateway(url)) return false
        val pageHost = pageUrl?.let(::hostOfUrl)
        if (pageHost != null && isAllowlisted(pageHost, private)) return false
        return e.shouldBlock(url, host, requestTypes(url, headers), pageUrl, pageHost)
    }

    /** The first CSS for a frame on [frameOrigin], or `null` when there's to be no hiding there. */
    fun initialCosmetics(frameOrigin: String, pageUrl: String?, private: Boolean): String? {
        val (e, frameHost, pageHost) = cosmeticContext(frameOrigin, pageUrl, private) ?: return null
        if (pageUrl != null && e.documentExempt(pageUrl, pageHost)) return null
        return e.initialCosmetics(frameOrigin, frameHost, pageUrl, pageHost)
    }

    /** The CSS for the generic rules keyed by [tokens] a frame on [frameOrigin] reported. */
    fun cosmeticsForTokens(tokens: Collection<String>, frameOrigin: String, pageUrl: String?, private: Boolean): String {
        val (e, frameHost, pageHost) = cosmeticContext(frameOrigin, pageUrl, private) ?: return ""
        return e.cosmeticsForTokens(tokens, frameOrigin, frameHost, pageUrl, pageHost)
    }

    private fun cosmeticContext(frameOrigin: String, pageUrl: String?, private: Boolean): Triple<AdblockEngine, String, String?>? {
        val e = engine ?: return null
        val frameHost = hostOfUrl(frameOrigin) ?: return null
        if (isExempt(frameHost)) return null
        val pageHost = pageUrl?.let(::hostOfUrl)
        if (isAllowlisted(pageHost ?: frameHost, private)) return null
        return Triple(e, frameHost, pageHost)
    }

    /** Loopback (the embedded nodes) and the virtual dweb origins are never filtered. */
    private fun isExempt(host: String): Boolean =
        host == "localhost" || host == "127.0.0.1" || host == "[::1]" || VirtualOrigin.isVirtualHost(host)

    /**
     * What a blocked request gets: an empty 403, so the page's element
     * fails the way it would behind any blocker (`onerror`, a rejected
     * cross-origin fetch) rather than loading an empty script.
     */
    fun blockedResponse(): WebResourceResponse =
        WebResourceResponse(
            "text/plain",
            "utf-8",
            403,
            "Blocked",
            mapOf("Cache-Control" to "no-store"),
            ByteArrayInputStream(ByteArray(0)),
        )
}
