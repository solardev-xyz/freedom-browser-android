package baby.freedom.mobile.browser

import android.content.Context
import android.webkit.WebView
import androidx.compose.runtime.mutableStateMapOf
import androidx.webkit.ScriptHandler
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import baby.freedom.mobile.data.SiteDesktopStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The key a site's "Desktop site" choice (#180) is remembered under:
 * the registrable domain of the page's host (`meet.google.com` →
 * `google.com`, [PublicSuffixList]) — the unit Chrome's own per-site
 * "Request desktop site" exceptions use (`[*.]google.com`). Per host
 * alone the choice couldn't reach a site that only ever hands a phone
 * off: `meet.google.com/new` goes to the Meet app (#177) and
 * `meet.google.com/` to `workspace.google.com`, so there is never a
 * Meet page on screen to switch — but the Workspace page can switch
 * `google.com`. A host with no registrable domain (an IP literal,
 * `localhost`, a host that is itself a public suffix) is its own key.
 *
 * Null for anything that isn't a site ([zoomSiteKey]'s home sentinel,
 * error pages, `data:`/`blob:`) and for dweb pages: a `bzz://`,
 * `ens://` or `ipfs://` page is served from a virtual origin by our
 * own interceptor ([VirtualOrigin]), so there's no server to hand it a
 * different layout for a different user agent. The menu's row is
 * disabled on all of these, and their tabs always use the WebView's
 * own (mobile) user agent.
 */
fun desktopSiteKey(url: String?): String? = desktopSiteOf(zoomSiteKey(url))

/** [desktopSiteKey] for a page already keyed by [zoomSiteKey] (e.g. [BrowserState.zoomSite]). */
fun desktopSiteOf(zoomSite: String?): String? {
    val host = zoomSite?.takeUnless { VirtualOrigin.isVirtualHost(it) } ?: return null
    if (host.startsWith("[") || IPV4_LITERAL.matches(host)) return host
    return PublicSuffixList.registrableDomain(host.removeSuffix(".")) ?: host
}

private val IPV4_LITERAL = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

/**
 * A main-frame document at [url] committed: whether it is the one this
 * chain (the user's navigation, see [PageWebView.usersNavigation]) was
 * awaited at, and the chain is over either way (#180, R2-F1).
 */
internal fun UserNamedChain.takeCommit(url: String?): Boolean = synchronized(this) {
    val awaited = asker()
    ended()
    awaited != null && url != null && sameRequestUrl(awaited, url)
}

/**
 * A same-document step to [url] (`doUpdateVisitedHistory` with no
 * commit): the end of this chain only if it is the address the chain
 * was awaited at — the chrome's Back to a `pushState` entry, a typed
 * `#fragment` (R2-F1). The page on screen's own `replaceState` or hash
 * change while the user's navigation is in flight (stripping `utm_`
 * parameters on load) is some other address, and leaves the chain for
 * the commit it is waiting for (R3-F2).
 */
internal fun UserNamedChain.sameDocumentStep(url: String?) = synchronized(this) {
    val awaited = asker() ?: return@synchronized
    if (url != null && sameRequestUrl(awaited, url)) ended()
}

/**
 * The one re-fetch of a hop of the user's navigation whose answer came
 * back, for the other site's user agent, as a redirect (#180, R3-F1).
 *
 * A commit with the wrong user agent is fixed by a reload
 * ([PageWebView.documentStarted]); but a desktop site's *mobile* answer
 * may be a redirect elsewhere — Meet's 302 to `meet.app.goo.gl` and on
 * to an app link — and then the desktop site never commits. So the
 * redirect is cancelled and the hop asked again, with its own site's
 * user agent. Once per navigation: a redirect loop between a desktop
 * and a mobile site would otherwise swap forever, each re-fetch
 * restarting Chromium's own redirect limit.
 */
internal class RedirectCorrection {
    // Scheduled, not yet issued: dropped if anything starts first.
    private var pending: String? = null
    // Issued: the hop asked again. Its own chain (and redirects) get no
    // second one.
    private var reissued: String? = null

    /**
     * The answer to [asker] — the hop the user's navigation was awaited
     * at — is a main-frame redirect, with the user agent in place. True
     * if the hop is to be asked again: [needsOther] says its site wants
     * the other one, and this navigation had no re-fetch yet.
     */
    fun redirectAnswered(asker: String?, needsOther: (String) -> Boolean): Boolean {
        if (asker == null || reissued != null || pending != null || !needsOther(asker)) return false
        pending = asker
        return true
    }

    /** The scheduled re-fetch, now issued; null if something superseded it. */
    fun issue(): String? {
        val hop = pending ?: return null
        pending = null
        reissued = hop
        return hop
    }

    /** Whether a navigation to [url] is the re-fetch just issued. */
    fun isReissue(url: String): Boolean = reissued?.let { sameRequestUrl(it, url) } == true

    /**
     * A navigation to [url] starts. Anything but the re-fetch itself
     * supersedes a scheduled one and starts a fresh allowance.
     */
    fun navigationStarted(url: String?) {
        pending = null
        val hop = reissued ?: return
        if (url == null || !sameRequestUrl(hop, url)) reissued = null
    }

    /** A document committed, or the navigation ended without one (Stop, Stay). */
    fun ended() {
        pending = null
        reissued = null
    }
}

/**
 * The address a page's own tapped navigation started at, and the
 * `Referer` its first request went out with (#180, R4-F1/R4-F2): what
 * decides whether a hop of it whose answer was a redirect for the other
 * user agent ([RedirectCorrection]) may be asked again *from the page on
 * screen* — the only way to keep the navigation's initiator.
 *
 * The page sees what it starts: its own Navigation API `navigate`
 * listener reads the full address (query and all) and can cancel it. So
 * only the address the navigation started at is ever re-issued there,
 * never a later redirect hop: that one may be a cross-origin redirect
 * target the page was never told (an OAuth `?code=`). And only when the
 * first request's `Referer` shows the initiator was of the page's own
 * origin — a cross-origin iframe's `target=_top` link isn't announced to
 * the top document either — so a request with no `Referer` at all
 * (`rel=noreferrer`, a `no-referrer` policy, a service worker's answer
 * the interceptor never saw) isn't re-issued. The re-issue then sends
 * only that origin as `Referer` ([REISSUE_REFERRER_POLICY]): never more
 * than the original request did, whatever the document's own policy.
 *
 * [requested] runs on the interceptor's thread, the rest on the UI
 * thread.
 */
internal class PageNavigationStart {
    private var start: String? = null
    private var referer: String? = null
    private var seen = false

    /** A page's own navigation to [url] started with a user gesture. */
    @Synchronized
    fun started(url: String) {
        start = url
        referer = null
        seen = false
    }

    /** No page's tapped navigation is in flight (anything else started, or it ended). */
    @Synchronized
    fun ended() {
        start = null
        referer = null
        seen = false
    }

    /** The WebView requests [url] for the main frame, with [headers]. */
    @Synchronized
    fun requested(url: String, headers: Map<String, String>?) {
        val awaited = start ?: return
        if (!sameRequestUrl(awaited, url)) {
            ended()
            return
        }
        // Chromium may request the first address twice (a retry on a
        // fresh connection); both carry the same `Referer`.
        if (seen) return
        seen = true
        referer = headers?.entries?.firstOrNull { it.key.equals("Referer", ignoreCase = true) }?.value
    }

    /**
     * Whether the answer to [hop] may be asked again by the page whose
     * document is at [documentUrl], with [REISSUE_REFERRER_POLICY]: [hop]
     * is the address this navigation started at, and its request's
     * `Referer` was of that document's origin.
     */
    @Synchronized
    fun mayReissue(hop: String, documentUrl: String?): Boolean {
        val awaited = start ?: return false
        if (!seen || !sameRequestUrl(awaited, hop)) return false
        val origin = webOrigin(documentUrl ?: return false) ?: return false
        return webOrigin(referer ?: return false) == origin
    }
}

/**
 * The referrer policy the page's re-issue of its own navigation uses
 * ([PageNavigationStart]): the document's origin, which the original
 * request's `Referer` held at least.
 */
internal const val REISSUE_REFERRER_POLICY = "origin"

/**
 * The script that re-issues a page's own navigation to [url] from the
 * document on screen: a detached link with an explicit referrer policy
 * and target, clicked — `location.assign()` would take the document's
 * own policy (R4-F2) and a `<base target>` could send a link elsewhere.
 */
internal fun pageReissueScript(url: String): String {
    val quoted = org.json.JSONObject.quote(url)
    return "(function(){var a=document.createElement('a');a.href=$quoted;" +
        "a.referrerPolicy='$REISSUE_REFERRER_POLICY';a.target='_self';a.click()})()"
}

/** `scheme://host[:port]` of an http(s) URL, default port dropped; null otherwise. */
internal fun webOrigin(url: String): String? {
    val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase() ?: return null
    if (scheme != "http" && scheme != "https") return null
    val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    val defaultPort = if (scheme == "https") 443 else 80
    val port = uri.port.takeIf { it != -1 && it != defaultPort }
    return "$scheme://$host" + (port?.let { ":$it" } ?: "")
}

/**
 * The chrome's Back and Forward (a script step, not
 * [android.webkit.WebView.goBack], so the page's own history handling
 * sees them). [PageWebView] knows them to put the user agent of the
 * entry they step to in place first (#180).
 */
const val HISTORY_BACK_JS = "javascript:history.back();void(0);"
const val HISTORY_FORWARD_JS = "javascript:history.forward();void(0);"

/**
 * The user agent a desktop site is requested with (#180): what desktop
 * Chrome on Linux sends, so a site that serves Android a mobile-only
 * page or an app hand-off (Google Meet, #177) serves its desktop page.
 *
 * The string is desktop Chrome's reduced user agent — `X11; Linux
 * x86_64` as the platform (no `Android`, `Mobile` or `wv` token) and
 * only the major version, `Chrome/<major>.0.0.0` — at the WebView's own
 * Chromium major, so feature sniffing still matches the engine that
 * actually renders the page. The client hints ([metadata]) say the same.
 */
object DesktopUserAgent {
    /** Used if the WebView's own user agent has no `Chrome/<major>` (never, in practice). */
    internal const val FALLBACK_MAJOR = "140"

    /** The brand WebView adds to its client hints; desktop Chromium has none like it. */
    internal const val WEBVIEW_BRAND = "Android WebView"

    /** The desktop user agent for a WebView whose own one is [mobile]. */
    fun string(mobile: String): String {
        val major = Regex("""\bChrome/(\d+)""").find(mobile)?.groupValues?.get(1) ?: FALLBACK_MAJOR
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/$major.0.0.0 Safari/537.36"
    }

    /**
     * The client hints (`Sec-CH-UA-*`, `navigator.userAgentData`) that go
     * with [string], built from the WebView's own [mobile] ones: the same
     * engine versions, minus the `Android WebView` brand (desktop
     * Chromium's list is just `Chromium` and the GREASE brand), as a
     * non-mobile 64-bit x86 Linux with no device model, whose form
     * factor (`Sec-CH-UA-Form-Factors`) is `Desktop` — WebView's own says
     * `Mobile`, which `mobile ?0` on Linux would contradict. Only where
     * the WebView can take a form factor ([setFormFactors], the
     * `USER_AGENT_METADATA_FORM_FACTORS` feature): the builder throws
     * elsewhere. [kernel] is the platform version desktop Chrome reports
     * on Linux: the kernel's.
     */
    fun metadata(mobile: UserAgentMetadata, kernel: String, setFormFactors: Boolean): UserAgentMetadata =
        UserAgentMetadata.Builder(mobile)
            .setBrandVersionList(mobile.brandVersionList.filter { it.brand != WEBVIEW_BRAND })
            .setPlatform("Linux")
            .setPlatformVersion(kernel)
            .setArchitecture("x86")
            .setBitness(64)
            .setModel("")
            .setMobile(false)
            .setWow64(false)
            .apply { if (setFormFactors) setFormFactors(listOf(UserAgentMetadata.FORM_FACTOR_DESKTOP)) }
            .build()

    /**
     * The kernel version as desktop Chrome writes it on Linux, from
     * `os.version` (e.g. `6.6.30-android15-8-g1234` → `6.6.30`); empty if
     * it doesn't start with one.
     */
    fun kernelVersion(osVersion: String?): String =
        osVersion?.let { Regex("""^\d+(\.\d+)*""").find(it)?.value }.orEmpty()

    /** `navigator.platform` on desktop Chrome for Linux x86_64 — what [string] claims. */
    const val PLATFORM = "Linux x86_64"

    /**
     * Whether `navigator.platform` needs [platformScript] to agree with
     * [string]. Chromium answers it from the kernel's machine name
     * (`uname`), which is `x86_64` on an x86_64 device already — only
     * other architectures (`aarch64` on nearly every phone) need it.
     * [osArch]: the `os.arch` property.
     */
    fun needsPlatformScript(osArch: String?): Boolean = osArch != "x86_64"

    /**
     * A document-start script that makes `navigator.platform` read
     * [PLATFORM]. WebView has no setting for it. The getter stays
     * Chromium's own function behind a Proxy, so it still reads as
     * native code, still throws on a foreign receiver the same way, and
     * nothing new appears on `window` or `navigator` (no global, no
     * marker a page could find this browser by).
     */
    val platformScript: String = """
        (() => {
          const proto = Navigator.prototype;
          const d = Object.getOwnPropertyDescriptor(proto, 'platform');
          if (!d || typeof d.get !== 'function') return;
          const get = new Proxy(d.get, {
            apply(target, self, args) {
              Reflect.apply(target, self, args);
              return '$PLATFORM';
            },
          });
          Object.defineProperty(proto, 'platform', { get, set: d.set, enumerable: d.enumerable, configurable: d.configurable });
        })();
    """.trimIndent()
}

/**
 * One WebView's user agent, switched between its own (mobile) one and
 * [DesktopUserAgent]'s (#180). Captures the WebView's own string and
 * client hints before it changes anything, so switching back restores
 * exactly what the WebView sent before.
 */
internal class UserAgentSwitch(private val webView: WebView) {
    private val settings = webView.settings
    private val mobileString: String = settings.userAgentString
    private val hintsSupported = WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)
    private val mobileHints: UserAgentMetadata? =
        if (hintsSupported) runCatching { WebSettingsCompat.getUserAgentMetadata(settings) }.getOrNull() else null
    private val desktopString = DesktopUserAgent.string(mobileString)
    private val desktopHints: UserAgentMetadata? = mobileHints?.let {
        DesktopUserAgent.metadata(
            it,
            DesktopUserAgent.kernelVersion(System.getProperty("os.version")),
            setFormFactors = WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA_FORM_FACTORS),
        )
    }

    /** Whether the WebView currently sends the desktop user agent. */
    var desktop: Boolean = false
        private set

    // The `navigator.platform` script while desktop, where needed and
    // supported; applies to documents created from then on.
    private var platformScript: ScriptHandler? = null
    private val platformScriptWanted =
        DesktopUserAgent.needsPlatformScript(System.getProperty("os.arch")) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    /**
     * Send the desktop user agent if [desktop], the WebView's own if not.
     * True if that changed anything.
     */
    fun set(desktop: Boolean): Boolean {
        if (desktop == this.desktop) return false
        this.desktop = desktop
        // Hints first: WebView pairs a user-agent string with the
        // metadata in force when the string is set.
        val hints = if (desktop) desktopHints else mobileHints
        if (hints != null) runCatching { WebSettingsCompat.setUserAgentMetadata(settings, hints) }
        settings.userAgentString = if (desktop) desktopString else mobileString
        if (platformScriptWanted) {
            platformScript?.remove()
            platformScript = if (desktop) {
                runCatching {
                    WebViewCompat.addDocumentStartJavaScript(webView, DesktopUserAgent.platformScript, setOf("*"))
                }.getOrNull()
            } else {
                null
            }
        }
        return true
    }
}

/**
 * The sites "Desktop site" is on for (#180), shared by every tab, and
 * remembered like page zoom ([PageZoom]): the store is read once, at
 * startup, and merged under anything the user changed before that read
 * landed; after that it is only written, in order.
 *
 * [isDesktop] is Compose state, so the menu's checkmark follows it.
 */
class DesktopSites internal constructor(
    private val scope: CoroutineScope,
    private val load: suspend () -> Set<String>,
    private val save: suspend (site: String, desktop: Boolean) -> Unit,
    private val clear: suspend () -> Unit,
) {
    /** Sites on, as a set (Compose has no snapshot set). */
    private val sites = mutableStateMapOf<String, Unit>()

    /** Sites changed this session, which the startup read must not override. */
    private val touched = HashSet<String>()

    /** Set by [clearAll]: the startup read, if still pending, is stale. */
    private var cleared = false
    private val writes = Mutex()

    init {
        scope.launch {
            val stored = load()
            if (cleared) return@launch
            for (site in stored) if (site !in touched) sites[site] = Unit
        }
    }

    /**
     * Private tabs' choices (#86): what was switched in a private tab
     * this private session, kept in memory only and shared by the
     * private tabs alone. A site not switched there reads the
     * remembered choice, as page zoom does.
     */
    private val privateChoices = mutableStateMapOf<String, Boolean>()

    /** Whether [site] (a [desktopSiteKey]) is a desktop site; [private]: as a private tab sees it. */
    fun isDesktop(site: String?, private: Boolean = false): Boolean {
        site ?: return false
        if (private) privateChoices[site]?.let { return it }
        return site in sites
    }

    /**
     * Switch [site] between desktop and mobile and remember it — for
     * this private session only if [private]. Returns the new choice.
     */
    fun toggle(site: String, private: Boolean = false): Boolean {
        val next = !isDesktop(site, private)
        if (private) {
            // Kept even when off: it overrides a remembered "on".
            privateChoices[site] = next
            return next
        }
        touched += site
        if (next) sites[site] = Unit else sites.remove(site)
        scope.launch { writes.withLock { save(site, next) } }
        return next
    }

    /**
     * Forget every desktop site (part of "Clear cookies & site data"):
     * the file lists sites the user visited, so it goes with the rest of
     * the browsing trail.
     */
    fun clearAll() {
        cleared = true
        touched.clear()
        sites.clear()
        privateChoices.clear()
        scope.launch { writes.withLock { clear() } }
    }

    /** The private session is over (#86): its choices go with it. */
    fun clearPrivate() {
        privateChoices.clear()
    }

    companion object {
        @Volatile
        private var instance: DesktopSites? = null

        fun get(context: Context): DesktopSites =
            instance ?: synchronized(this) {
                instance ?: SiteDesktopStore.get(context).let { store ->
                    DesktopSites(MainScope(), store::load, store::set, store::clear)
                }.also { instance = it }
            }
    }
}
