package baby.freedom.mobile.browser

import android.util.Log
import android.webkit.CookieManager
import java.util.concurrent.Executors

/**
 * Belt-and-braces cookie sweep for the virtual dweb origins.
 *
 * The interceptor already strips `Cookie` / `Set-Cookie` on the only
 * network path, so the remaining channel is `document.cookie` writes
 * from page JS. Until the PSL entry for `*.{bzz,ipfs,ipns,ens}.freedom.baby`
 * propagates into users' WebView (issue #6 — months, via Chromium
 * releases), all virtual origins share one registrable domain, so a
 * malicious root could set `Domain=.bzz.freedom.baby` (or, one level
 * up, `Domain=freedom.baby`) cookies visible to every other dweb site
 * and onchain app (cookie tossing).
 *
 * This sweep expires everything [CookieManager] reports for the
 * virtual suffixes, the domain-scoped cookies of the shared base
 * domain, and for the specific origin being navigated to — read at
 * the root and at the path of every document it is told about, since
 * a cookie tossed with some other `Path` is only visible there.
 * It runs on navigation to any virtual origin, on a same-document URL
 * change (`pushState`), when a tab is brought to the front, and
 * periodically over every open tab's URL; and
 * stays on permanently as defense in depth even after the PSL entry
 * lands (per issue #5).
 *
 * Not a hard barrier: a page still running in another tab can plant a
 * cookie again between two sweeps, and a document reading
 * `document.cookie` in the same task as its own `pushState` to a new
 * path reads before the sweep for that path. Ending the vector for
 * good is the PSL entry (issue #6).
 *
 * `CookieManager` has no enumeration API, so this is best-effort by
 * construction: it can expire domain-scoped cookies (the tossing
 * vector — visible at the suffix level at the same path) and host
 * cookies of origins we're told about, at the paths of the documents
 * we're told about — exactly the cookies those documents can read.
 */
object CookieHygiene {
    private const val TAG = "CookieHygiene"

    // Single background thread: CookieManager is thread-safe, and the
    // sweep must never add latency to onPageStarted on the UI thread.
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "cookie-hygiene").apply { isDaemon = true }
    }

    /**
     * How often the periodic sweep should run (used by the host). It
     * bounds how long a cookie a background tab plants again stays
     * readable by another open document (R3-F2); each run is a handful
     * of in-memory `getCookie` reads, flushing only when it expired one.
     */
    const val SWEEP_INTERVAL_MS: Long = 15_000L

    /**
     * Asynchronously expire all cookies under the virtual suffixes and
     * the onchain apps' suffix, and — when [navigatedUrl] is a
     * virtual-origin or onchain-app URL — under its exact host as well.
     */
    fun sweepAsync(navigatedUrl: String? = null) {
        sweepAsync(listOfNotNull(navigatedUrl))
    }

    /**
     * [sweepAsync] for several documents at once — every open tab's
     * current URL for the periodic sweep, so a cookie planted at the
     * path one of them sits on is caught, not only `Path=/` ones.
     */
    fun sweepAsync(urls: Collection<String>) {
        val snapshot = urls.toList()
        executor.execute { sweepBlocking(snapshot) }
    }

    internal fun sweepBlocking(navigatedUrl: String? = null) =
        sweepBlocking(listOfNotNull(navigatedUrl))

    internal fun sweepBlocking(urls: List<String>) {
        // Private tabs (#86) keep their cookies in their own profile's jar.
        val jars = listOfNotNull(
            runCatching { CookieManager.getInstance() }.getOrNull(),
            PrivateProfile.cookieManager(),
        )
        for (cm in jars) runCatching { sweepJar(cm, urls) }
    }

    private fun sweepJar(cm: CookieManager, urls: List<String>) {
        // A cookie is only readable by a document whose path it
        // path-matches, and `document.cookie` accepts any `Path=`, so a
        // `Path=/` read misses a cookie tossed at `/swap` (R3-F1). With
        // no enumeration API, read at every path a covered document is
        // on — that is exactly the set of cookies those documents can see.
        val covered = urls.filter { hostToSweep(it) != null }
        val paths = (listOf("/") + covered.map(::pathOf)).distinct().take(MAX_PATHS)
        var expired = 0
        // Onchain apps' origins (#123) sit under the same base domain.
        for (suffix in VirtualOrigin.SUFFIXES + OnchainAppRef.SUFFIX) {
            for (path in paths) {
                expired += expireAllFor(cm, "https://$suffix", path, domain = ".$suffix")
            }
        }
        // Every virtual origin and onchain app shares the registrable
        // domain `freedom.baby` (until the PSL entry, issue #6), so a
        // `Domain=freedom.baby` cookie set by any one of them reaches all
        // of them — the same tossing vector one level up. Only the
        // domain-scoped variant is expired here: the real
        // `https://freedom.baby/` site's own host-only cookies are left
        // alone (a Domain-scoped expiry never touches a host-only cookie).
        for (path in paths) {
            expired += expireAllFor(
                cm,
                "https://${VirtualOrigin.BASE_DOMAIN}",
                path,
                domain = ".${VirtualOrigin.BASE_DOMAIN}",
                hostScoped = false,
            )
        }
        for (url in covered) {
            val host = hostToSweep(url) ?: continue
            expired += expireAllFor(cm, "https://$host", pathOf(url), domain = null)
        }
        if (expired > 0) {
            Log.i(TAG, "expired $expired cookie(s) under virtual origins")
            runCatching { cm.flush() }
        }
    }

    /**
     * The exact host whose own cookies a navigation to [url] expires: a
     * dweb virtual origin's, or an onchain app's (#123); `null` for
     * anything else.
     */
    internal fun hostToSweep(url: String?): String? = when {
        url == null -> null
        VirtualOrigin.isVirtualUrl(url) -> url.removePrefix("https://").substringBefore('/')
        else -> OnchainAppRef.parseVirtual(url)?.first?.host
    }

    /** Should a navigation to [url] sweep the jar (see [hostToSweep])? */
    fun coversNavigation(url: String?): Boolean = hostToSweep(url) != null

    /** Upper bound on distinct document paths one sweep reads at. */
    private const val MAX_PATHS = 64

    /** The path of [url] (no query or fragment), `/` when it has none. */
    internal fun pathOf(url: String): String {
        val afterScheme = url.substringAfter("://", "")
        val rest = afterScheme.substringBefore('?').substringBefore('#')
        val slash = rest.indexOf('/')
        return if (slash < 0) "/" else rest.substring(slash)
    }

    /**
     * Every cookie `Path` that path-matches [path] (RFC 6265 §5.1.4):
     * the root, each ancestor directory with and without its trailing
     * slash, and [path] itself. Expiring a name at each of them reaches
     * whichever of those paths the original was set at.
     */
    internal fun cookiePathsMatching(path: String): List<String> {
        val out = linkedSetOf("/")
        var i = path.indexOf('/', 1)
        while (i > 0) {
            out += path.substring(0, i)
            out += path.substring(0, i + 1)
            i = path.indexOf('/', i + 1)
        }
        if (path.isNotEmpty()) out += path
        return out.toList()
    }

    /**
     * Expire every cookie [CookieManager] would send to [origin]+[path].
     * Each is rewritten with `Max-Age=0` host-scoped (unless [hostScoped]
     * is false) and (when [domain] is given) domain-scoped, at every
     * `Path` that could have let it through — we can see neither the
     * scope nor the path the original carried.
     */
    private fun expireAllFor(
        cm: CookieManager,
        origin: String,
        path: String,
        domain: String?,
        hostScoped: Boolean = true,
    ): Int {
        val url = origin + path
        val cookies = runCatching { cm.getCookie(url) }.getOrNull() ?: return 0
        val names = cookieNames(cookies).distinct()
        val cookiePaths = cookiePathsMatching(path)
        for (name in names) {
            for (p in cookiePaths) {
                runCatching {
                    if (hostScoped) cm.setCookie(url, "$name=; Path=$p; Max-Age=0")
                    if (domain != null) {
                        cm.setCookie(url, "$name=; Domain=$domain; Path=$p; Max-Age=0")
                    }
                }
            }
        }
        // Count what actually went (host-only cookies are deliberately
        // left when [hostScoped] is false).
        val left = runCatching { cm.getCookie(url) }.getOrNull()?.let(::cookieNames).orEmpty()
        return (cookieNames(cookies).size - left.size).coerceAtLeast(0)
    }

    private fun cookieNames(header: String): List<String> =
        header.split(';').map { it.substringBefore('=').trim() }.filter { it.isNotEmpty() }
}
