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
        // Coalesce: at most one sweep waits behind the running one, and
        // it covers every URL asked for since. A sweep that takes longer
        // than [SWEEP_INTERVAL_MS] can then never build a backlog that
        // pushes a navigation's sweep minutes into the future (R4-F1).
        // The waiting set is bounded too (R5-F2): a page pushStating
        // distinct URLs in a loop only ever keeps the newest
        // [MAX_PENDING]; anything dropped that is still open comes back
        // with the next periodic sweep over every tab.
        val schedule = synchronized(pending) {
            addPending(pending, urls)
            (!queued).also { queued = true }
        }
        if (schedule) executor.execute {
            val snapshot = synchronized(pending) {
                queued = false
                pending.toList().also { pending.clear() }
            }
            sweepBlocking(snapshot)
        }
    }

    private val pending = LinkedHashSet<String>()

    /** Add [urls] to [set] as the newest entries, keeping at most [MAX_PENDING]. */
    internal fun addPending(set: LinkedHashSet<String>, urls: Collection<String>) {
        for (u in urls) {
            set.remove(u)
            set += u
        }
        val it = set.iterator()
        while (set.size > MAX_PENDING && it.hasNext()) {
            it.next()
            it.remove()
        }
    }
    private var queued = false

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
        // One host pass per distinct (host, path), capped like the paths.
        val covered = coveredPairs(urls)
        val paths = sweepPaths(covered)
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
        for ((host, path) in covered) {
            expired += expireAllFor(cm, "https://$host", path, domain = null)
        }
        if (expired > 0) {
            Log.i(TAG, "expired $expired cookie(s) under virtual origins")
            runCatching { cm.flush() }
        }
    }

    /**
     * The distinct (host, path) pairs [urls] cover, at most [MAX_PATHS].
     * [urls] is oldest-first (the coalesced queue appends), so on
     * overflow the *newest* pairs are kept — the page just navigated to
     * gets its host pass now, while an older one dropped here is still
     * picked up by the next periodic sweep if its tab is open (R1-F1).
     * Returned newest-first.
     */
    internal fun coveredPairs(urls: List<String>): List<Pair<String, String>> {
        val out = LinkedHashSet<Pair<String, String>>()
        for (u in urls.asReversed()) {
            if (out.size >= MAX_PATHS) break
            val host = hostToSweep(u) ?: continue
            out += host to pathOf(u)
        }
        return out.toList()
    }

    /**
     * The document paths the suffix passes read at: always `/`, then
     * the distinct paths of [covered] (newest-first), at most [MAX_PATHS].
     */
    internal fun sweepPaths(covered: List<Pair<String, String>>): List<String> =
        (listOf("/") + covered.map { it.second }).distinct().take(MAX_PATHS)

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
    internal const val MAX_PATHS = 64

    /** Upper bound on URLs waiting for the next coalesced sweep. */
    internal const val MAX_PENDING = 256

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
     * slash, and [path] itself, shallowest first. Used for tests; the
     * sweep works on [cookiePathEnds] so a deep path never materializes
     * thousands of long substrings.
     */
    internal fun cookiePathsMatching(path: String): List<String> =
        cookiePathEnds(path).map { path.substring(0, it) }

    /**
     * The candidates of [cookiePathsMatching] as end offsets into
     * [path], strictly increasing. Along this chain visibility is
     * monotone: a cookie whose `Path` is candidate `k` is sent to
     * candidate `j` exactly when `j >= k`.
     */
    internal fun cookiePathEnds(path: String): IntArray {
        if (path.isEmpty()) return intArrayOf()
        val out = ArrayList<Int>()
        fun add(end: Int) {
            if (out.isEmpty() || out.last() < end) out += end
        }
        add(1)
        var i = path.indexOf('/', 1)
        while (i > 0) {
            add(i)
            add(i + 1)
            i = path.indexOf('/', i + 1)
        }
        add(path.length)
        return out.toIntArray()
    }

    /**
     * Expire every cookie [CookieManager] would send to [origin]+[path].
     * Each is rewritten with `Max-Age=0` host-scoped (unless [hostScoped]
     * is false) and (when [domain] is given) domain-scoped, at the
     * `Path` it was actually set at — we can see neither the scope nor
     * the path, so the path is found by reading along the chain of
     * candidate paths ([cookiePathEnds]).
     *
     * Cost is bounded by the cookies present, not by how deep [path]
     * is (R4-F1): a candidate range whose two ends see the same cookies
     * holds no cookie path in between (visibility is monotone), so the
     * search bisects only ranges where some name's count grows —
     * `O(cookies × log(depth))` reads, and one expiry per cookie path
     * found, instead of every name at every candidate.
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
        val before = cookieNames(cookies)
        val ends = cookiePathEnds(path)
        if (ends.isEmpty()) return 0
        val seen = HashMap<Int, Map<String, Int>>()
        fun countsAt(j: Int): Map<String, Int> = when {
            j < 0 -> emptyMap()
            j == ends.lastIndex -> seen.getOrPut(j) { countNames(before) }
            else -> seen.getOrPut(j) {
                countNames(
                    runCatching { cm.getCookie(origin + path.substring(0, ends[j])) }
                        .getOrNull()?.let(::cookieNames).orEmpty(),
                )
            }
        }
        // Candidate index -> names whose count first grows there, i.e.
        // names with a cookie set at exactly that `Path`.
        val found = LinkedHashMap<Int, Set<String>>()
        fun search(lo: Int, hi: Int) {
            val a = countsAt(lo)
            val b = countsAt(hi)
            if (a == b) return
            if (hi == lo + 1) {
                found[hi] = b.filter { (n, c) -> c > (a[n] ?: 0) }.keys
                return
            }
            val mid = (lo + hi) ushr 1
            search(lo, mid)
            search(mid, hi)
        }
        search(-1, ends.lastIndex)
        for ((j, names) in found) {
            val p = path.substring(0, ends[j])
            // A nameless cookie (`document.cookie = 'X'` or `'=k=v'`) is
            // serialized as just its value, so its token reads like a
            // name (`X`, or `k` of `k=v`) and a `X=; Max-Age=0` rewrite
            // targets a different key (R5-F1). The cookie key is (name,
            // domain, path) and the value is irrelevant to an overwrite,
            // so one nameless `=x` rewrite per path expires whichever
            // nameless cookie sits there. (A both-empty `=` is rejected.)
            for (pair in names.map { "$it=" } + "=x") {
                for (attrs in expiryAttributes(p, domain, hostScoped)) {
                    runCatching { cm.setCookie(url, "$pair; $attrs") }
                }
            }
        }
        // Count what actually went (host-only cookies are deliberately
        // left when [hostScoped] is false).
        val left = runCatching { cm.getCookie(url) }.getOrNull()?.let(::cookieNames).orEmpty()
        return (before.size - left.size).coerceAtLeast(0)
    }

    /**
     * The attribute strings of the `Max-Age=0` rewrites that expire a
     * cookie at [path]: host-only (when [hostScoped]) and
     * [domain]-scoped, each unpartitioned and partitioned.
     *
     * Every one is `Secure` (R6-F1): Chromium drops a `__Secure-` or
     * `__Host-` prefixed cookie without it — the expiry too — so an
     * unsecured rewrite left a tossed `__Secure-t; Domain=freedom.baby`
     * in place for every origin. All swept URLs are `https://`, and a
     * `Secure` overwrite replaces a non-`Secure` cookie of the same
     * (name, domain, path) as well. A `__Host-` cookie can only exist
     * host-only at `Path=/`, which the host rewrite at the root is; the
     * other shapes are rejected for it, harmlessly. `SameSite` is not
     * part of a cookie's key, so one rewrite covers every value.
     *
     * A partitioned (CHIPS) cookie is keyed by its top-level site as
     * well, and only a `Partitioned` rewrite reaches it; [CookieManager]
     * files that under the URL's own site — the partition of a
     * top-level document on the swept origin, i.e. what another virtual
     * origin can read as a top-level page (they share the site
     * `freedom.baby` until issue #6). A partitioned cookie set by a
     * virtual origin framed inside some *other* top-level site lives in
     * that site's partition, which `CookieManager` can neither read nor
     * write; it is only visible to frames under that same top-level
     * site, so it is outside what this sweep can (or needs to) reach.
     */
    internal fun expiryAttributes(path: String, domain: String?, hostScoped: Boolean): List<String> {
        val scopes = listOfNotNull(
            if (hostScoped) "" else null,
            domain?.let { "Domain=$it; " },
        )
        return scopes.flatMap { scope ->
            val base = "${scope}Path=$path; Max-Age=0; Secure"
            listOf(base, "$base; Partitioned")
        }
    }

    private fun countNames(names: List<String>): Map<String, Int> =
        names.groupingBy { it }.eachCount()

    private fun cookieNames(header: String): List<String> =
        header.split(';').map { it.substringBefore('=').trim() }.filter { it.isNotEmpty() }
}
