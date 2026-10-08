package baby.freedom.mobile.browser

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.MainThread
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cookie
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NoEncryption
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import baby.freedom.mobile.R
import baby.freedom.mobile.l10n.Strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * How the page on screen reached the phone, as Page info (#442) says it
 * and the address bar's leading mark shows it.
 */
internal enum class PageConnection(
    @StringRes val titleRes: Int,
    @StringRes val bodyRes: Int,
) {
    /** `https://`: Chromium refuses every certificate error (#259), so a page that loaded has a valid one. */
    Secure(R.string.page_info_secure_title, R.string.page_info_secure_body),
    /** `http://` to anywhere but this device. */
    NotSecure(R.string.page_info_not_secure_title, R.string.page_info_not_secure_body),
    /**
     * `http://` to a loopback host ([isLoopbackHost]: `localhost`, `::1`,
     * 127.0.0.0/8) — never on the network, and what Chromium itself
     * treats as potentially trustworthy, so not "Not secure".
     */
    Local(R.string.page_info_local_title, R.string.page_info_local_body),
    /**
     * `http://` to a `.onion` host: an onion service, reached only
     * through Tor, whose connection is encrypted end to end and whose
     * address is its key — not "Not secure" (R1-M2).
     */
    Onion(R.string.page_info_onion_title, R.string.page_info_onion_body),
    Swarm(R.string.page_info_dweb_swarm_title, R.string.page_info_dweb_body),
    Ipfs(R.string.page_info_dweb_ipfs_title, R.string.page_info_dweb_body),
    /** A dweb page whose network isn't known from its address (a raw virtual origin). */
    Dweb(R.string.page_info_dweb_title, R.string.page_info_dweb_body),
    /** An on-chain app (`web3://`, #123), read from a contract through the app's own chain access. */
    Onchain(R.string.page_info_onchain_title, R.string.page_info_onchain_body),
    /** A Radicle repository (`rad://`, #124), browsed from the embedded node. */
    Radicle(R.string.page_info_radicle_title, R.string.page_info_radicle_body),
    /** One of the browser's own error pages, standing in for a load that failed. */
    ErrorPage(R.string.page_info_error_title, R.string.page_info_error_body),
    ;

    /** A certificate can exist only for a page that came over https. */
    val hasCertificate: Boolean get() = this == Secure
}

/**
 * The connection for a page at [url] (the tab's committed address, as
 * the bar shows it): an error page is that whatever address it stands
 * for; a dweb address is its network ([protocol], the bar's own protocol
 * badge); then the URL's scheme. Null where the address is none of these
 * (`about:`, `data:`, `file:`) — the bar shows no mark there.
 */
internal fun pageConnectionFor(url: String, errorPage: Boolean, protocol: ProtocolBadge?): PageConnection? {
    val u = url.trim()
    if (u.isEmpty()) return null
    if (errorPage) return PageConnection.ErrorPage
    if (protocol != null) {
        return if (protocol.drawableRes == R.drawable.ic_ipfs) PageConnection.Ipfs else PageConnection.Swarm
    }
    if (VirtualOrigin.isVirtualUrl(u)) return PageConnection.Dweb
    // Served by the app itself, never fetched from a site's server: the
    // display forms, and the virtual origins they load as (R1-F1).
    if (OnchainAppRef.isWeb3Scheme(u) || OnchainAppRef.isUnderSuffix(u)) return PageConnection.Onchain
    if (RadUrl.isRadScheme(u) || RadUrl.isVirtualUrl(u)) return PageConnection.Radicle
    return when {
        u.startsWith("https://", ignoreCase = true) -> PageConnection.Secure
        u.startsWith("http://", ignoreCase = true) -> when {
            loopbackHostOf(u) -> PageConnection.Local
            onionHostOf(u) -> PageConnection.Onion
            else -> PageConnection.NotSecure
        }
        else -> null
    }
}

/**
 * How an external IPFS gateway (#125) figures in the IPFS page on
 * screen, for Page info (#479). Such a gateway is one server whose
 * answers aren't checked against the CID.
 */
internal sealed interface IpfsGatewayUse {
    val gateway: String

    /**
     * The document came through [gateway] (or may have: its source isn't
     * known and [gateway] is the one set now), so Page info must not
     * call the page peer-to-peer, and a name's shield only covers which
     * CID the name points to.
     */
    data class Document(override val gateway: String) : IpfsGatewayUse

    /**
     * The document came from this device's node, which checked it, but
     * [gateway] has been set since: what the page loads from now on
     * comes through it unchecked.
     */
    data class SinceLoaded(override val gateway: String) : IpfsGatewayUse
}

/**
 * [IpfsGatewayUse] for the page on screen, or null where no external
 * gateway is involved, or the page isn't IPFS content.
 *
 * [source] is where the document was fetched from
 * ([TabDocuments.committedSource]), not the current setting: switching
 * to a gateway doesn't reload a page this device's node served. Only
 * where that isn't known (a page a service worker answered) does the
 * current setting, [externalIpfsBase] (`https://ipfs.io`, or `""`),
 * stand in for it, on the cautious side.
 */
internal fun ipfsGatewayUseFor(
    connection: PageConnection?,
    url: String,
    externalIpfsBase: String,
    source: DocumentSource?,
): IpfsGatewayUse? {
    val ipfs = when (connection) {
        PageConnection.Ipfs -> true
        // A raw virtual origin: IPFS when its root is a CID or IPNS name.
        PageConnection.Dweb -> servedFromIpfs(VirtualOrigin.parseHostOfUrl(url.trim()))
        else -> false
    }
    if (!ipfs) return null
    source?.ipfsGateway?.let { return IpfsGatewayUse.Document(it) }
    if (externalIpfsBase.isEmpty()) return null
    return if (source == null) {
        IpfsGatewayUse.Document(externalIpfsBase)
    } else {
        IpfsGatewayUse.SinceLoaded(externalIpfsBase)
    }
}

/**
 * Whether [url]'s host is a `.onion` name — by the same test the Tor
 * routing applies ([isOnionHost]), so the badge and the routing can't
 * disagree (R2-M1). [url] is the address the tab committed, which
 * Chromium has already normalized (case-folded, percent-decoded), so its
 * host reads as it is.
 */
private fun onionHostOf(url: String): Boolean =
    isOnionHost(runCatching { java.net.URI(url).host }.getOrNull())

/** Whether [url]'s host is a loopback one ([isLoopbackHost]), as WHATWG parses it. */
private fun loopbackHostOf(url: String): Boolean {
    val host = runCatching { java.net.URI(url).host }.getOrNull()
        ?.lowercase()?.trimEnd('.')?.removePrefix("[")?.removeSuffix("]")
        ?.takeIf { it.isNotEmpty() } ?: return false
    return isLoopbackHost(host)
}

/**
 * The address bar's leading mark (#442): a dweb page's protocol badge
 * (Swarm hex / IPFS cube, with its name-trust shield), or for a web
 * page a lock — open for plain http — or, on an error page, an info
 * mark. Whatever it is, a tap opens Page info.
 */
internal sealed interface AddressBadge {
    data class Protocol(val badge: ProtocolBadge) : AddressBadge
    data class Connection(val connection: PageConnection) : AddressBadge
}

/** [addressBadgeFor] from plain values (tests). */
internal fun addressBadgeFor(url: String, isHome: Boolean, errorPage: Boolean, protocol: ProtocolBadge?): AddressBadge? {
    if (isHome) return null
    // A dweb address keeps its own badge, error page or not: it says
    // which network the failed load was for.
    if (protocol != null) return AddressBadge.Protocol(protocol)
    return pageConnectionFor(url, errorPage, protocol = null)?.let(AddressBadge::Connection)
}

internal fun addressBadgeFor(state: BrowserState): AddressBadge? =
    addressBadgeFor(state.url, state.isHome, state.showsErrorPage, protocolBadgeFor(state))

private val AddressBadge.Connection.icon: ImageVector
    get() = when (connection) {
        PageConnection.Secure -> Icons.Filled.Lock
        PageConnection.NotSecure -> Icons.Filled.NoEncryption
        PageConnection.Local -> Icons.Filled.PhoneAndroid
        PageConnection.Onion -> Icons.Filled.Lock
        PageConnection.ErrorPage -> Icons.Outlined.Info
        PageConnection.Swarm, PageConnection.Ipfs, PageConnection.Dweb,
        PageConnection.Onchain, PageConnection.Radicle -> Icons.Filled.Hub
    }

@StringRes
private fun badgeDescriptionRes(connection: PageConnection): Int = when (connection) {
    PageConnection.Secure -> R.string.page_info_badge_secure
    PageConnection.NotSecure -> R.string.page_info_badge_not_secure
    PageConnection.Local -> R.string.page_info_badge_local
    PageConnection.Onion -> R.string.page_info_badge_onion
    PageConnection.ErrorPage -> R.string.page_info_badge_error
    else -> connection.titleRes
}

/** What the badge is read out as; the tap's own label is "Page info". */
@Composable
internal fun addressBadgeDescription(badge: AddressBadge, trust: NameTrust?): String = when (badge) {
    is AddressBadge.Protocol -> protocolBadgeDescription(badge.badge, trust)
    is AddressBadge.Connection -> stringResource(badgeDescriptionRes(badge.connection))
}

/**
 * The mark itself, at the badge's size. Carries no semantics of its own:
 * the Page info button around it ([BottomToolbar]) says what it is.
 */
@Composable
internal fun AddressBadgeMark(badge: AddressBadge, trust: NameTrust?, modifier: Modifier = Modifier) {
    when (badge) {
        is AddressBadge.Protocol -> ProtocolBadgeMark(badge = badge.badge, trust = trust, modifier = modifier)
        is AddressBadge.Connection -> Icon(
            imageVector = badge.icon,
            contentDescription = null,
            tint = if (badge.connection == PageConnection.NotSecure) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = modifier,
        )
    }
}

// ---------------------------------------------------------------------
// Cookies and site data
// ---------------------------------------------------------------------

/** What the site has stored: its cookies, and its storage's size where WebView says. */
internal data class SiteDataCount(val cookies: Int, val bytes: Long?)

/**
 * The cookies in a `CookieManager.getCookie` header (`a=1; b=2; c`) —
 * names, and one entry per nameless cookie (`c`, serialized as its value).
 */
internal fun cookieEntries(header: String?): List<String> =
    header.orEmpty().split(';').map { it.trim() }.filter { it.isNotEmpty() }

/**
 * How Page info names [origin]'s data when it's a content gateway's own
 * origin on the device ([isLoopbackGatewayOrigin]), shared by every root
 * loaded through it: the bare `host:port` (`127.0.0.1:1633`). Null for
 * any other origin, whose data is the one site's own (#457 R6-M1).
 */
internal fun siteDataGatewayLabel(origin: String?): String? =
    origin?.takeIf(::isLoopbackGatewayOrigin)?.substringAfter("://")

/** "3 cookies · 1.2 MB stored", "No cookies", or "Counting…" while [count] is null. */
internal fun siteDataLine(count: SiteDataCount?, formatBytes: (Long) -> String): String {
    count ?: return Strings.get(R.string.page_info_site_data_counting)
    val cookies = if (count.cookies == 0) {
        Strings.get(R.string.page_info_site_data_none)
    } else {
        Strings.plural(R.plurals.page_info_site_data_cookies, count.cookies, count.cookies)
    }
    val bytes = count.bytes?.takeIf { it > 0 } ?: return cookies
    return Strings.get(R.string.page_info_site_data_with_storage, cookies, formatBytes(bytes))
}

/**
 * The `Domain=` values a cookie the page at [host] can read may have
 * been set with: [host] itself and each parent down to its registrable
 * domain ([registrable]) — none for an IP literal or a host with no
 * registrable domain, whose cookies can only be host-only.
 */
internal fun cookieDomains(host: String, registrable: String?): List<String> {
    if (registrable == null || host.isEmpty()) return emptyList()
    if (host != registrable && !host.endsWith(".$registrable")) return emptyList()
    val out = ArrayList<String>()
    var h = host
    while (true) {
        out += h
        if (h == registrable) break
        h = h.substringAfter('.')
    }
    return out
}

/** Most cookie paths one deletion writes expiries at. */
internal const val SITE_COOKIE_MAX_PATHS = 16

/**
 * The `Max-Age=0` rewrites that remove every cookie in [entries]
 * (from [cookieEntries]) from the site at [origin], read at [pagePath]:
 * at each path that path-matches it (`/`, `/a`, `/a/`, …), host-only
 * and for each of [domains] — `CookieManager` can't say which scope or
 * path a cookie was set at, and only an exact (name, domain, path)
 * match overwrites one. A nameless cookie is reached by one `=x`
 * rewrite per scope. On https every rewrite is `Secure` (a `__Secure-`
 * / `__Host-` cookie ignores one without it), once unpartitioned and
 * once `Partitioned`; plain http can't set `Secure` at all.
 */
internal fun siteCookieExpiries(
    origin: String,
    pagePath: String,
    entries: List<String>,
    domains: List<String>,
): List<String> {
    val secure = origin.startsWith("https://", ignoreCase = true)
    val pairs = entries.map { e ->
        if ('=' in e && !e.startsWith("=")) e.substringBefore('=').trim() + "=" else "=x"
    }.distinct()
    if (pairs.isEmpty()) return emptyList()
    val paths = CookieHygiene.cookiePathsMatching(pagePath.ifEmpty { "/" }).take(SITE_COOKIE_MAX_PATHS)
    val scopes = listOf("") + domains.map { "Domain=$it; " }
    val out = ArrayList<String>()
    for (path in paths) for (scope in scopes) for (pair in pairs) {
        val base = "$pair; ${scope}Path=$path; Max-Age=0"
        if (secure) {
            out += "$base; Secure"
            out += "$base; Secure; Partitioned"
        } else {
            out += base
        }
    }
    return out
}

/**
 * Counting and deleting one site's cookies and storage for Page info
 * (#442), in the tab's own profile: a private tab's live in the private
 * session's ([PrivateProfile]), never the default one.
 */
/** How a page's own clearing for Delete data ended ([siteDataInPageJs], [SiteData.cleanAndReload]). */
internal enum class InPageCleaned {
    /** It is reloading itself (`location.replace`, a GET). */
    RELOADING_ITSELF,

    /**
     * It leaves the reload to the app: its address has a fragment, which
     * no script navigation loads again without resending a POST page's
     * form (R4-F1).
     */
    APP_RELOADS,
}

internal object SiteData {
    private fun jar(private: Boolean): CookieManager? =
        if (private) PrivateProfile.cookieManager() else runCatching { CookieManager.getInstance() }.getOrNull()

    /** Main thread. */
    private fun storage(private: Boolean): WebStorage? =
        if (private) PrivateProfile.webStorage() else runCatching { WebStorage.getInstance() }.getOrNull()

    /** The cookies [pageUrl] is sent, and [origin]'s storage use. Call on the main thread. */
    suspend fun count(origin: String, pageUrl: String, private: Boolean): SiteDataCount {
        val cookies = withContext(Dispatchers.IO) {
            runCatching { jar(private)?.getCookie(pageUrl) }.getOrNull().let(::cookieEntries).size
        }
        val bytes = withTimeoutOrNull(3_000) {
            suspendCancellableCoroutine<Long?> { cont ->
                val s = storage(private)
                if (s == null) {
                    cont.resume(null)
                } else {
                    runCatching {
                        s.getUsageForOrigin(origin) { used -> if (cont.isActive) cont.resume(used) }
                    }.onFailure { if (cont.isActive) cont.resume(null) }
                }
            }
        }
        return SiteDataCount(cookies, bytes)
    }

    /** One Delete's mark on a tab: the origin its next document load clears first. */
    class CleanupMark internal constructor(val origin: String) {
        /** What became of the mark once it left the tab ([Outcome.PENDING] while it's on it). */
        @Volatile
        var outcome: Outcome = Outcome.PENDING
            internal set
    }

    /** How a [CleanupMark] ended. */
    enum class Outcome {
        /** Still on the tab, or replaced there by a later Delete's mark. */
        PENDING,

        /** A load took it and was answered with the cleanup page ([takeCleanup]). */
        CLEANED,

        /** A load took it that isn't one to clean: another origin, a POST. */
        ELSEWHERE,

        /**
         * A document committed with no request of it reaching the
         * interceptor ([committed]): one a service worker answered — a
         * client redirect or meta refresh that raced the page's clearing,
         * say. Nothing was cleaned by it (R3-F2).
         */
        COMMITTED,

        /** Its Delete dropped it, no load having taken it ([dropCleanup]). */
        DROPPED,
    }

    /** Tab id → the mark its next document load takes. */
    private val cleanups = ConcurrentHashMap<Long, CleanupMark>()

    /**
     * Mark tab [tabId]'s next document load — the reload [delete] is
     * followed by — to be answered with the site-data cleanup page
     * ([takeCleanup]) if it is a GET on [origin]. That page, run in the
     * tab itself, is what reaches the tab's own sessionStorage, and
     * whatever the old document wrote on its way out. The mark lasts that
     * one navigation: the tab's next main-frame request takes it, its
     * next commit ([committed]) drops it if no request did — a load a
     * service worker answered never reaches the interceptor — and the
     * Delete that set it drops it when it's done waiting for that reload
     * ([dropCleanup]), so a reload that never came (a page that stopped
     * it, a fragment-only navigation) can't leave it armed for a later
     * load the user didn't ask to clean (R2-F1). Returns the mark, for
     * [cleanupPending] and [dropCleanup]; it replaces any earlier one.
     */
    fun markCleanup(tabId: Long, origin: String): CleanupMark =
        CleanupMark(origin).also { cleanups[tabId] = it }

    /**
     * Should tab [tabId]'s main-frame request for [url] be answered with
     * the site-data cleanup page ([SITE_DATA_CLEANUP_HTML])? Once: the
     * tab's next main-frame request takes the mark whatever it is for,
     * and only a GET on the marked origin is answered with the page — a
     * tab that went elsewhere instead never has it turn up on a later
     * visit, and a form POST (a login) is never swallowed by it. Any
     * thread (the interceptor's).
     */
    fun takeCleanup(tabId: Long, url: String, method: String?): Boolean {
        val mark = cleanups.remove(tabId) ?: return false
        val clean = method.equals("GET", ignoreCase = true) && permissionOriginKey(url) == mark.origin
        mark.outcome = if (clean) Outcome.CLEANED else Outcome.ELSEWHERE
        return clean
    }

    /** Is [mark] still on tab [tabId], waiting for a load to take it? */
    fun cleanupPending(tabId: Long, mark: CleanupMark): Boolean = cleanups[tabId] === mark

    /** Drop [mark] from tab [tabId] if no load took it; a later Delete's mark is left alone. */
    fun dropCleanup(tabId: Long, mark: CleanupMark) {
        if (cleanups.remove(tabId, mark)) mark.outcome = Outcome.DROPPED
    }

    /**
     * The tab's half of Delete data, after [delete]: get tab [tabId] onto
     * a new document whose first load is the cleanup page ([markCleanup]),
     * its service workers unregistered first. [clean] asks the tab's own
     * document to clear and unregister, then reload itself
     * ([siteDataInPageJs]) under a done-key, and answers whether the
     * document took it on — not one on another origin, nor an opaque one
     * (a `Content-Security-Policy: sandbox` page, whose `location.origin`
     * is `"null"`), which is reloaded from here at once rather than
     * waited on (R3-F4). [cleaned] asks whether it has finished clearing
     * (`null` if not), and whether it reloads itself or leaves that to
     * [reload] — an address with a fragment, which only a script
     * `location.reload()` would load again, and that resends a POST
     * page's form unasked (R4-F1); [reload] then comes at once.
     * Only once it has — or after [IN_PAGE_WIPE_MAX_MS] at the most —
     * does the wait for its reload start, so a slow clearing (big caches,
     * many databases) isn't overtaken by a reload from here that the
     * still-registered worker would answer (R2-F3). If no load takes the
     * mark within [IN_PAGE_RELOAD_WAIT_MS] of that (a page that stops
     * it), or the page couldn't be asked, [reload] reloads the tab from
     * here — but only while [onOrigin] says the tab is still on [origin]:
     * a tab that has gone to another site meanwhile isn't reloaded, its
     * draft lost, for this one's Delete (R3-F1).
     *
     * A document that committed without any request reaching the
     * interceptor ([Outcome.COMMITTED]: a client redirect or meta refresh
     * a service worker answered, landing while the page was clearing)
     * cleaned nothing, and the clearing it cut short may not have reached
     * the worker; if the tab is still on [origin], the whole of it runs
     * again on the new document, up to [CLEANUP_ATTEMPTS] times (R3-F2).
     * Whatever happens, each mark is dropped at the end of its attempt,
     * so it can't stay armed for a later load (R2-F1).
     */
    suspend fun cleanAndReload(
        tabId: Long,
        origin: String,
        clean: suspend (doneKey: String) -> Boolean,
        cleaned: suspend (doneKey: String) -> InPageCleaned?,
        onOrigin: () -> Boolean,
        reload: () -> Unit,
        newDoneKey: () -> String = { "_" + UUID.randomUUID().toString().replace("-", "") },
    ) {
        repeat(CLEANUP_ATTEMPTS) {
            val mark = markCleanup(tabId, origin)
            try {
                val doneKey = newDoneKey()
                if (clean(doneKey)) {
                    var done: InPageCleaned? = null
                    withTimeoutOrNull(IN_PAGE_WIPE_MAX_MS) {
                        while (cleanupPending(tabId, mark) && cleaned(doneKey).also { done = it } == null) {
                            delay(CLEANUP_POLL_MS)
                        }
                    }
                    // A page that leaves the reload to the app (a fragment
                    // address, R4-F1) isn't waited on for one.
                    if (done != InPageCleaned.APP_RELOADS) {
                        withTimeoutOrNull(IN_PAGE_RELOAD_WAIT_MS) {
                            while (cleanupPending(tabId, mark)) delay(CLEANUP_POLL_MS)
                        }
                    }
                }
                if (cleanupPending(tabId, mark)) {
                    if (!onOrigin()) return
                    reload()
                    withTimeoutOrNull(FALLBACK_RELOAD_WAIT_MS) {
                        while (cleanupPending(tabId, mark)) delay(CLEANUP_POLL_MS)
                    }
                }
            } finally {
                dropCleanup(tabId, mark)
            }
            if (mark.outcome != Outcome.COMMITTED || !onOrigin()) return
        }
    }

    /** How many times [cleanAndReload] runs when a commit it didn't clean cut it short. */
    const val CLEANUP_ATTEMPTS = 2

    /** The longest [cleanAndReload] waits for the page's own clearing to end. */
    const val IN_PAGE_WIPE_MAX_MS = 15_000L

    /** How long, once the page's clearing ended, [cleanAndReload] waits for its reload to start. */
    const val IN_PAGE_RELOAD_WAIT_MS = 3_000L

    /** How long the mark waits for the reload [cleanAndReload] itself started. */
    const val FALLBACK_RELOAD_WAIT_MS = 5_000L

    private const val CLEANUP_POLL_MS = 100L

    /** Tab id → the origin ([documentOrigin]) of the document it last committed. */
    private val committedOrigins = ConcurrentHashMap<Long, String>()

    /**
     * Tab [tabId] committed a document at [url] (`onPageStarted`): a
     * mark its load didn't take — one a service worker answered — goes,
     * so it can't turn up on a later load the user didn't ask to clean.
     */
    fun committed(tabId: Long, url: String?) {
        cleanups.remove(tabId)?.outcome = Outcome.COMMITTED
        // A blob: document's is its creator's, as the page's own
        // [BrowserState.siteOrigin] is, so a Delete from one still
        // finds the tab on the site and reloads it (R1-M3). A content
        // gateway's own origin is kept: it holds no permission, but its
        // data is there to delete (#457 R5-M1).
        val origin = documentOrigin(url)
        if (origin == null) committedOrigins.remove(tabId) else committedOrigins[tabId] = origin
    }

    /** The origin of tab [tabId]'s committed document, as [committed] last heard. */
    fun committedOrigin(tabId: Long): String? = committedOrigins[tabId]

    /** Tab [tabId] closed: nothing of it is kept. */
    fun tabClosed(tabId: Long) {
        cleanups.remove(tabId)
        committedOrigins.remove(tabId)
    }

    /** How long [wipeOnOrigin]'s document gets to finish. */
    private const val WIPE_TIMEOUT_MS = 10_000L

    /**
     * Delete [origin]'s data: its cookies [pageUrl] is sent (see
     * [siteCookieExpiries]) and its storage (`WebStorage.deleteOrigin`),
     * then — through a document of the cleanup page's own run on the
     * origin in a throwaway WebView ([wipeOnOrigin]) — what only a
     * document there can reach: localStorage, IndexedDB and Cache
     * Storage. That doesn't depend on the tab's next load reaching the
     * interceptor, which a service worker's answer never does. Service
     * workers themselves are out of that document's reach (Chromium
     * refuses `getRegistrations()` in a `loadDataWithBaseURL` document:
     * "the document is in an invalid state"); the caller has the tab's
     * own document unregister them ([siteDataInPageJs]) before its
     * reload. Runs to the end once started, whatever cancels the caller.
     * Returns whether the wipe document ran to its end. Call on the
     * main thread.
     */
    suspend fun delete(context: Context, origin: String, pageUrl: String, private: Boolean): Boolean =
        withContext(NonCancellable) {
            // A private tab's data is the session's it was asked in: the
            // one live now. Every step below checks it still is — if the
            // last private tab closes meanwhile and a new one opens, the
            // new session's data is not this Delete's to clear (R2-M2).
            val session = if (private) PrivateProfile.liveProfileName() ?: return@withContext false else null
            val cm = jar(private)
            if (cm != null) {
                withContext(Dispatchers.IO) {
                    val host = hostOfUrl(pageUrl).orEmpty()
                    val domains = cookieDomains(host, PublicSuffixList.registrableDomain(host))
                    val entries = (
                        cookieEntries(runCatching { cm.getCookie(pageUrl) }.getOrNull()) +
                            cookieEntries(runCatching { cm.getCookie("$origin/") }.getOrNull())
                        ).distinct()
                    for (expiry in siteCookieExpiries(origin, CookieHygiene.pathOf(pageUrl), entries, domains)) {
                        runCatching { cm.setCookie(pageUrl, expiry) }
                    }
                    runCatching { cm.flush() }
                }
            }
            if (session != null && !PrivateProfile.isLiveSession(session)) return@withContext false
            runCatching { storage(private)?.deleteOrigin(origin) }
            wipeOnOrigin(context, origin, session)
        }

    /**
     * Run [siteDataWipeHtml] as a document on [origin], in a WebView of
     * its own on the tab's profile (a private tab's on the private
     * session [session] names, [PrivateProfile], only while it is still
     * the live one): `loadDataWithBaseURL` commits it with
     * the origin's identity without fetching anything, so no service
     * worker answers it and no page script runs beside it. The WebView
     * loads nothing from the network ([WebSettings.setBlockNetworkLoads],
     * every request refused) and is destroyed after. Whether the
     * document said it was done within [WIPE_TIMEOUT_MS]. Main thread.
     */
    @MainThread
    private suspend fun wipeOnOrigin(context: Context, origin: String, session: String?): Boolean {
        val wv = runCatching { WebView(context.applicationContext) }.getOrNull() ?: return false
        try {
            // Before anything else touches it: Chromium refuses a profile
            // change once a WebView is used. A private tab's storage is
            // never wiped through the default profile instead; and one
            // whose last tab closed while this delete ran is already gone
            // — no new session is started for it (R1-M1), and one opened
            // since is not wiped in its place (R2-M2).
            if (session != null && !runCatching { PrivateProfile.attachToLive(wv, session) }.getOrDefault(false)) return false
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            wv.settings.blockNetworkLoads = true
            val done = "wiped-" + UUID.randomUUID()
            return withTimeoutOrNull(WIPE_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    wv.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): WebResourceResponse = WebResourceResponse(
                            "text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)),
                        )
                    }
                    wv.webChromeClient = object : WebChromeClient() {
                        override fun onReceivedTitle(view: WebView?, title: String?) {
                            if (title == done && cont.isActive) cont.resume(true)
                        }
                    }
                    wv.loadDataWithBaseURL("$origin/", siteDataWipeHtml(done), "text/html", "utf-8", null)
                }
            } ?: false
        } finally {
            runCatching {
                wv.stopLoading()
                wv.destroy()
            }
        }
    }
}

// ---------------------------------------------------------------------
// The sheet
// ---------------------------------------------------------------------

/** One certificate fact, label then value, in the order the sheet lists them. */
internal fun certificateRows(cert: CertFacts, day: (Long) -> String): List<Pair<Int, String>> = listOfNotNull(
    cert.issuedTo?.takeIf { it.isNotBlank() }?.let { R.string.page_info_cert_issued_to to it },
    cert.issuedBy?.takeIf { it.isNotBlank() }?.let { R.string.page_info_cert_issued_by to it },
    cert.notBeforeMs?.let { R.string.page_info_cert_valid_from to day(it) },
    cert.notAfterMs?.let { R.string.page_info_cert_valid_until to day(it) },
)

/**
 * Page info (#442), opened from the address bar's badge: everything about
 * the page on screen in one sheet, Chrome's shape — how its name was
 * checked (a dweb name's trust, #97), the connection (with the
 * certificate behind "Certificate details"), what the site was allowed
 * or refused ([PageSitePermissionRow], the rows of the page's Site
 * permissions, #266), its ad-blocking switch (#126), and its cookies and
 * site data with a way to delete them.
 *
 * Opened over one document: the caller closes it when the tab's
 * document changes, so nothing here can act on a page the user didn't
 * open it for. A section with nothing to say is left out, so a plain
 * page shows little; detail sits behind a tap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PageInfoSheet(
    /** The site as the address bar names it (`example.org`, `vitalik.eth`). */
    site: String,
    connection: PageConnection?,
    certificate: CertFacts?,
    nameTrust: NameTrust?,
    permissions: List<SitePermissionEntry>,
    inUse: Set<SitePermission>,
    document: SitePermissionBroker.DocumentPermissions?,
    private: Boolean,
    onRevokePermission: (SitePermissionEntry) -> Unit,
    adblock: AdblockSiteState?,
    onToggleAdblock: () -> Unit,
    /** Where the site's data lives, or null where there is none to count or delete. */
    siteDataOrigin: String?,
    /** The URL whose cookies are counted and deleted (the page's own, on [siteDataOrigin]). */
    siteDataUrl: String?,
    /**
     * Set when [siteDataOrigin] is a content gateway's own origin
     * ([isLoopbackGatewayOrigin]): its data is shared by every root loaded
     * through that gateway, so the row and the Delete confirm name the
     * gateway (`127.0.0.1:1633`), not this one root ([site]) (#457 R6-M1).
     */
    siteDataGateway: String? = null,
    /**
     * How an external IPFS gateway figures in the page ([ipfsGatewayUseFor]),
     * or null: said in place of "peer-to-peer", and, where the document
     * itself came through it, after the name's trust (#479).
     */
    ipfsGateway: IpfsGatewayUse? = null,
    onDeleteSiteData: () -> Unit,
    onReload: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var confirmDelete by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                stringResource(R.string.page_info_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            // In full, wrapping: a host's tail is what a spoof hides.
            Text(
                site,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // A dweb name's trust first (#97): it's what the badge's shield says.
            if (nameTrust != null) {
                Section(stringResource(R.string.page_info_section_name)) {
                    InfoRow(
                        icon = { Icon(nameTrust.tier.icon, contentDescription = null, tint = nameTrust.tier.color) },
                        title = nameTrust.tier.title,
                        // The shield covers name → CID only; through a
                        // gateway, the page itself isn't checked (#479).
                        body = if (ipfsGateway is IpfsGatewayUse.Document) {
                            nameTrust.summary + " " +
                                stringResource(R.string.page_info_name_gateway_note, ipfsGateway.gateway)
                        } else {
                            nameTrust.summary
                        },
                    )
                    Expander(stringResource(R.string.page_info_name_details)) {
                        SelectionContainer { TrustFacts(nameTrust) }
                    }
                }
            }

            if (connection != null) {
                Section(stringResource(R.string.page_info_section_connection)) {
                    if (ipfsGateway != null) {
                        // One server, not the network, and not checked (#479).
                        val document = ipfsGateway is IpfsGatewayUse.Document
                        InfoRow(
                            icon = {
                                Icon(
                                    TrustTier.Unverified.icon,
                                    contentDescription = null,
                                    tint = TrustTier.Unverified.color,
                                )
                            },
                            title = stringResource(
                                if (document) R.string.page_info_ipfs_gateway_title else R.string.page_info_dweb_ipfs_title,
                            ),
                            body = stringResource(
                                if (document) R.string.page_info_ipfs_gateway_body else R.string.page_info_ipfs_gateway_since_body,
                                ipfsGateway.gateway,
                            ),
                        )
                    } else {
                        InfoRow(
                            icon = {
                                Icon(
                                    AddressBadge.Connection(connection).icon,
                                    contentDescription = null,
                                    tint = if (connection == PageConnection.NotSecure) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            },
                            title = stringResource(connection.titleRes),
                            body = stringResource(connection.bodyRes),
                        )
                    }
                    val rows = remember(certificate) {
                        val fmt = DateFormat.getDateInstance(DateFormat.MEDIUM)
                        certificate?.let { certificateRows(it) { ms -> fmt.format(Date(ms)) } }.orEmpty()
                    }
                    if (connection.hasCertificate && rows.isNotEmpty()) {
                        Expander(stringResource(R.string.page_info_certificate_details)) {
                            SelectionContainer {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    for ((label, value) in rows) Fact(stringResource(label), value)
                                }
                            }
                        }
                    }
                }
            }

            // Only what the site has, or asked for and was refused.
            val heldNote = stillHeldNote(document?.stillHeld(inUse).orEmpty())
            if (permissions.isNotEmpty() || heldNote != null) {
                Section(stringResource(R.string.page_info_section_permissions)) {
                    for (entry in permissions) {
                        PageSitePermissionRow(
                            entry = entry,
                            inUse = document?.inUse(entry, inUse) == true,
                            private = private,
                            onRevoke = { onRevokePermission(entry) },
                        )
                    }
                    if (heldNote != null) {
                        Text(
                            heldNote,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                        TextButton(onClick = onReload, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.library_site_permissions_reload))
                        }
                    }
                }
            }

            if (adblock != null) {
                Section(stringResource(R.string.page_info_section_adblock)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable(enabled = adblock.toggleable, role = Role.Switch, onClick = onToggleAdblock)
                            .semantics { toggleableState = ToggleableState(adblock.checked) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Shield, contentDescription = null)
                        Spacer(Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.browser_menu_block_ads))
                            adblock.note?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(checked = adblock.checked, onCheckedChange = null, enabled = adblock.toggleable)
                    }
                }
            }

            if (siteDataOrigin != null && siteDataUrl != null) {
                val context = LocalContext.current
                var count by remember(siteDataOrigin, siteDataUrl) { mutableStateOf<SiteDataCount?>(null) }
                LaunchedEffect(siteDataOrigin, siteDataUrl, private) {
                    count = SiteData.count(siteDataOrigin, siteDataUrl, private)
                }
                Section(stringResource(R.string.page_info_section_site_data)) {
                    InfoRow(
                        icon = { Icon(Icons.Filled.Cookie, contentDescription = null) },
                        title = siteDataLine(count) { android.text.format.Formatter.formatShortFileSize(context, it) },
                        body = siteDataGateway?.let { stringResource(R.string.page_info_site_data_gateway, it) },
                    )
                    OutlinedButton(
                        onClick = { confirmDelete = true },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(
                            stringResource(
                                if (siteDataGateway != null) R.string.page_info_delete_gateway_data else R.string.page_info_delete_data,
                            ),
                        )
                    }
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.page_info_delete_confirm_title, siteDataGateway ?: site)) },
            text = {
                Text(
                    stringResource(
                        when {
                            siteDataGateway != null && private -> R.string.page_info_delete_confirm_body_gateway_private
                            siteDataGateway != null -> R.string.page_info_delete_confirm_body_gateway
                            private -> R.string.page_info_delete_confirm_body_private
                            else -> R.string.page_info_delete_confirm_body
                        },
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDeleteSiteData()
                }) { Text(stringResource(R.string.page_info_delete_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    HorizontalDivider(modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(bottom = 4.dp)
            .semantics { heading() },
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
}

@Composable
private fun InfoRow(icon: @Composable () -> Unit, title: String, body: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            if (body != null) {
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A "more on demand" row: [label] with a chevron, [content] under it once opened. */
@Composable
private fun Expander(label: String, content: @Composable () -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val state = stringResource(if (open) R.string.page_info_expanded else R.string.page_info_collapsed)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = { open = !open })
            .semantics {
                role = Role.Button
                stateDescription = state
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.weight(1f),
        )
        Icon(
            if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
    }
    if (open) {
        Column(modifier = Modifier.padding(bottom = 8.dp)) { content() }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
