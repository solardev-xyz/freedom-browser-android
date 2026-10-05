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
 * The one correction of a hop of the user's navigation that is about to
 * go out with the other site's user agent (#180) — made *before* the
 * hop is requested, never after: a hop the server already answered
 * isn't asked again, since a GET can be single-use (a magic sign-in
 * link, an email verification, an OAuth callback: the first fetch spends
 * the token and the second shows "expired", R5-F1).
 *
 * Two hops qualify. A redirect of a load of the app's to an address
 * across the desktop/mobile line: the redirect is cancelled before its
 * target is requested, and the target loaded instead with its own user
 * agent ([PageWebView.redirectCrossesUserAgent]). And the first hop of a
 * page's tapped navigation across the line: its request is answered by
 * us with a `204` before it leaves the device, and the page on screen
 * asks for it again ([PageNavigationStart], [PageWebView.pageHopRequested]).
 *
 * Once per navigation: a redirect loop between a desktop and a mobile
 * site would otherwise swap forever, each re-issue restarting Chromium's
 * own redirect limit. [issue] and [isReissue] run on the UI thread,
 * [crossing] also on the interceptor's.
 */
internal class RedirectCorrection {
    // Scheduled, not yet issued: dropped if anything starts first.
    private var pending: String? = null
    // Issued: the hop asked again. Its own chain (and redirects) get no
    // second one.
    private var reissued: String? = null
    // Whether the issued hop's navigation has started.
    private var started = false

    /** Bumped by each [issue]: which re-issue a deadline was set for. */
    var generation = 0
        private set

    /**
     * [hop] is about to be requested with the user agent in place. True
     * if it is to be asked again instead: [needsOther] says its site wants
     * the other one, and this navigation had no correction yet.
     */
    @Synchronized
    fun crossing(hop: String?, needsOther: (String) -> Boolean): Boolean {
        if (hop == null || reissued != null || pending != null || !needsOther(hop)) return false
        pending = hop
        return true
    }

    /** The scheduled re-issue, now issued; null if something superseded it. */
    @Synchronized
    fun issue(): String? {
        val hop = pending ?: return null
        pending = null
        reissued = hop
        started = false
        generation++
        return hop
    }

    /** Whether a navigation to [url] is the re-issue just issued. */
    @Synchronized
    fun isReissue(url: String): Boolean = reissued?.let { sameRequestUrl(it, url) } == true

    /**
     * Whether the re-issue [generation] names was issued and its
     * navigation never started — the page cancelled it (a Navigation API
     * `navigate` listener's `preventDefault()`), or never got the ask
     * (R5-F2).
     */
    @Synchronized
    fun neverStarted(generation: Int): Boolean =
        generation == this.generation && reissued != null && !started

    /**
     * A navigation to [url] starts. The re-issue itself is now under way;
     * anything else supersedes a scheduled one and starts a fresh
     * allowance.
     */
    @Synchronized
    fun navigationStarted(url: String?) {
        pending = null
        val hop = reissued ?: return
        if (url != null && sameRequestUrl(hop, url)) started = true else reissued = null
    }

    /** A document committed, or the navigation ended without one (Stop, Stay). */
    @Synchronized
    fun ended() {
        pending = null
        reissued = null
    }
}

/**
 * A page's own tapped navigation across the desktop/mobile line (#180):
 * whether its first request may be held back — answered with a `204`
 * before it leaves the device — and asked for again by the page on
 * screen with the right user agent. The page is the only initiator that
 * keeps the navigation's own `Sec-Fetch-Site` and SameSite cookies; a
 * load of ours would send `Sec-Fetch-Site: none` and Strict cookies to an
 * address a page picked (R1-F1).
 *
 * The page sees what it starts: its own Navigation API `navigate`
 * listener reads the full address and can cancel it. So only the address
 * the navigation started at is ever re-issued there — a redirect target
 * the page was never told (an OAuth `?code=`) isn't (R4-F1), and goes out
 * with the user agent in place. And only when the request's `Referer`
 * shows the initiator was of the page's own origin — a cross-origin
 * iframe's `target=_top` link isn't announced to the top document either
 * — so a request with no `Referer` at all (`rel=noreferrer`, a
 * `no-referrer` policy) isn't held; nor one a service worker answers,
 * which the interceptor never sees. The re-issue sends only that origin
 * as `Referer` ([REISSUE_REFERRER_POLICY]): never more than the original
 * request did (R4-F2).
 *
 * [requested] runs on the interceptor's thread, the rest on the UI
 * thread.
 */
internal class PageNavigationStart {
    private var start: String? = null
    private var documentOrigin: String? = null
    private var crosses = false
    private var seen = false

    /**
     * A page's own navigation to [url] started with a user gesture, from
     * the document at [documentUrl]. [crosses]: its site wants the other
     * user agent, and the page can be asked to re-issue it.
     */
    @Synchronized
    fun started(url: String, documentUrl: String?, crosses: Boolean) {
        start = url
        documentOrigin = documentUrl?.let(::webOrigin)
        this.crosses = crosses
        seen = false
    }

    /** No page's tapped navigation is in flight (anything else started, or it ended). */
    @Synchronized
    fun ended() {
        start = null
        documentOrigin = null
        crosses = false
        seen = false
    }

    /**
     * The WebView is about to request [url] for the main frame, with
     * [headers]. True if the request is to be held back and the page
     * asked to re-issue it: the first request of the navigation that
     * crosses the line, with a `Referer` of the document's own origin.
     */
    @Synchronized
    fun requested(url: String, headers: Map<String, String>?): Boolean {
        val awaited = start ?: return false
        if (!sameRequestUrl(awaited, url)) {
            ended()
            return false
        }
        // Chromium may request the first address twice (a retry on a
        // fresh connection): only the first is ours to decide.
        if (seen) return false
        seen = true
        if (!crosses) return false
        val origin = documentOrigin ?: return false
        val referer = headers?.entries?.firstOrNull { it.key.equals("Referer", ignoreCase = true) }?.value
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
 * How long the page gets to start its re-issue ([PageNavigationStart])
 * before the switch made for it is undone (R5-F2): the page on screen
 * may cancel it, and must not keep sending its own requests with the
 * other site's user agent.
 */
internal const val PAGE_REISSUE_START_MS = 2_000L

/**
 * The document on screen's detector channel ([bottomUiDetectorJs]),
 * which re-issues a page's own navigation (R5-F3): a detached link built
 * and clicked with DOM functions saved at document start, so no function
 * the page has wrapped since sees it. Without it — no detector in the
 * document yet, a CSP-sandboxed one — there's no re-issue, and the
 * request goes out as it is.
 */
internal interface PageReissueChannel {
    /** Whether the current document's detector can take [url] now. */
    fun ready(url: String): Boolean

    /** Asks the current document's detector to navigate to [url]; false if it can't be asked. */
    fun send(url: String): Boolean
}

/** The address a re-issue may name: http(s), nothing the message could split on. */
private val REISSUE_URL = Regex("""^https?://\S+$""", RegexOption.IGNORE_CASE)

/**
 * What Kotlin sends the current document's detector (with its [token])
 * to re-issue the page's navigation to [url]; null for an address it
 * doesn't take (see [bottomUiDetectorJs]).
 */
internal fun pageReissueRequest(token: String, url: String): String? =
    if (REISSUE_URL.matches(url)) "$PAGE_REISSUE_PREFIX$token $url" else null

internal const val PAGE_REISSUE_PREFIX = "go "

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
 * A load a restore put back over its page (#185) that has to go out with
 * the other user agent (#180), held until that page has finished.
 *
 * The put-back load normally goes in at the restored page's commit
 * without stopping it, so the page's HTML and subresources keep coming
 * in (#185 R4-F1). A user agent can't change under a page that is still
 * loading — Chromium would reload it — so a load that crosses the
 * desktop/mobile line would have to stop the page first, and leave it
 * truncated for good if that load then never commits (a `204`, a
 * download). It waits for the page's own finish instead.
 *
 * Only while nothing else has taken the tab: a load of the app's, Stop,
 * any document committing, or a navigation the user started on the page
 * meanwhile (a tapped link, a form they submitted — where they're going
 * now, #185 R2-F1) drops it ([dropped]). A navigation the page starts
 * without a gesture (a script's redirect, a `location.href` beacon
 * answered `204`) doesn't: the load was in flight over it before the
 * relaunch, and a stale one that never commits mustn't cost the user
 * their load (R2-F2).
 *
 * Nor for longer than [PUT_BACK_HOLD_MS] ([deadline], R2-F1): a page
 * whose `load` event is slow (a trickling image) or never comes (a live
 * stream, a long poll) would otherwise keep the typed address in the bar
 * over it indefinitely. Past that the load goes in anyway, stopping the
 * page, as any load that crosses the line does.
 */
internal class PutBackHold {
    private var load: (() -> Unit)? = null

    // Bumped by each [hold]: a deadline acts only on the hold it was set for.
    private var generation = 0

    val held: Boolean get() = load != null

    /** Holds [load]; returns the generation its [deadline] is to name. */
    fun hold(load: () -> Unit): Int {
        this.load = load
        return ++generation
    }

    fun dropped() {
        load = null
    }

    /**
     * The page on screen finished. True if the held load is to go in
     * now ([release]).
     */
    fun pageFinished(): Boolean = load != null

    /** The deadline set for hold [generation] passed: its load goes in, if still held. */
    fun deadline(generation: Int) {
        if (generation == this.generation) release()
    }

    /** Hands the held load to the WebView, if it's still held. */
    fun release() {
        val go = load ?: return
        load = null
        go()
    }
}

/**
 * Whether the restored page's current finish puts that page's address in
 * the bar. Not when it releases a held put-back load ([PutBackHold]): the
 * bar keeps that load's address, the one the user typed, for its flight,
 * as the deadline's release and any typed load do (R3-F1).
 */
internal fun finishShowsPageAddress(putBackGoesIn: Boolean): Boolean = !putBackGoesIn

/** The longest a [PutBackHold] waits for its page's finish (R2-F1). */
internal const val PUT_BACK_HOLD_MS = 5_000L

/** What the capsule shows while a [PutBackHold] waits: busy, not idle over the old page (R2-F1). */
internal const val PUT_BACK_HOLD_PROGRESS = 10

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
     * Forget every desktop site (part of *Delete browsing data*'s
     * *Cookies and site data*):
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
