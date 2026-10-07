package baby.freedom.mobile.browser

import android.webkit.CookieManager
import android.webkit.WebStorage
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.DateFormat
import java.util.Date
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
    /** `http://`. */
    NotSecure(R.string.page_info_not_secure_title, R.string.page_info_not_secure_body),
    Swarm(R.string.page_info_dweb_swarm_title, R.string.page_info_dweb_body),
    Ipfs(R.string.page_info_dweb_ipfs_title, R.string.page_info_dweb_body),
    /** A dweb page whose network isn't known from its address (a raw virtual origin). */
    Dweb(R.string.page_info_dweb_title, R.string.page_info_dweb_body),
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
    return when {
        u.startsWith("https://", ignoreCase = true) -> PageConnection.Secure
        u.startsWith("http://", ignoreCase = true) -> PageConnection.NotSecure
        else -> null
    }
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
        PageConnection.ErrorPage -> Icons.Outlined.Info
        PageConnection.Swarm, PageConnection.Ipfs, PageConnection.Dweb -> Icons.Filled.Hub
    }

@StringRes
private fun badgeDescriptionRes(connection: PageConnection): Int = when (connection) {
    PageConnection.Secure -> R.string.page_info_badge_secure
    PageConnection.NotSecure -> R.string.page_info_badge_not_secure
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

    /** Tab id → the origin whose data that tab's next document load clears first. */
    private val cleanups = ConcurrentHashMap<Long, String>()

    /**
     * Should tab [tabId]'s main-frame request for [url] be answered with
     * the site-data cleanup page ([SITE_DATA_CLEANUP_HTML])? Once: the
     * tab's next main-frame request takes the mark whatever it is for,
     * and only one on the marked origin is answered with the page — a
     * tab that went elsewhere instead never has it turn up on a later
     * visit. Any thread (the interceptor's).
     */
    fun takeCleanup(tabId: Long, url: String): Boolean {
        val origin = cleanups.remove(tabId) ?: return false
        return permissionOriginKey(url) == origin
    }

    /**
     * Delete [origin]'s data for tab [tabId]: its cookies [pageUrl] is
     * sent (see [siteCookieExpiries]) and its storage, here, and on the
     * tab's next load — the reload that follows — whatever else only a
     * document on the origin can reach (localStorage, sessionStorage,
     * IndexedDB, Cache Storage, service workers), through the cleanup
     * page ([takeCleanup]). Runs to the end once started, whatever
     * cancels the caller. Call on the main thread.
     */
    suspend fun delete(tabId: Long, origin: String, pageUrl: String, private: Boolean) = withContext(NonCancellable) {
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
        runCatching { storage(private)?.deleteOrigin(origin) }
        cleanups[tabId] = origin
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
                        body = nameTrust.summary,
                    )
                    Expander(stringResource(R.string.page_info_name_details)) {
                        SelectionContainer { TrustFacts(nameTrust) }
                    }
                }
            }

            if (connection != null) {
                Section(stringResource(R.string.page_info_section_connection)) {
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
                        body = null,
                    )
                    OutlinedButton(
                        onClick = { confirmDelete = true },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.page_info_delete_data)) }
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.page_info_delete_confirm_title, site)) },
            text = {
                Text(
                    stringResource(
                        if (private) R.string.page_info_delete_confirm_body_private else R.string.page_info_delete_confirm_body,
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
