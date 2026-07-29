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
 * malicious root could set `Domain=.bzz.freedom.baby` cookies visible
 * to every other dweb site (cookie tossing).
 *
 * This sweep expires everything [CookieManager] reports for the
 * virtual suffixes and for the specific origin being navigated to.
 * It runs on navigation to any virtual origin plus periodically, and
 * stays on permanently as defense in depth even after the PSL entry
 * lands (per issue #5).
 *
 * `CookieManager` has no enumeration API, so this is best-effort by
 * construction: it can expire domain-scoped cookies (the tossing
 * vector — those are visible at the suffix level) and host cookies of
 * origins we're told about, which is exactly the set that matters.
 */
object CookieHygiene {
    private const val TAG = "CookieHygiene"

    // Single background thread: CookieManager is thread-safe, and the
    // sweep must never add latency to onPageStarted on the UI thread.
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "cookie-hygiene").apply { isDaemon = true }
    }

    /** How often the periodic sweep should run (used by the host). */
    const val SWEEP_INTERVAL_MS: Long = 60_000L

    /**
     * Asynchronously expire all cookies under the virtual suffixes, and
     * — when [navigatedUrl] is a virtual-origin URL — under its exact
     * host as well.
     */
    fun sweepAsync(navigatedUrl: String? = null) {
        executor.execute { sweepBlocking(navigatedUrl) }
    }

    internal fun sweepBlocking(navigatedUrl: String? = null) {
        val cm = runCatching { CookieManager.getInstance() }.getOrNull() ?: return
        var expired = 0
        for (suffix in VirtualOrigin.SUFFIXES) {
            expired += expireAllFor(cm, "https://$suffix/", domain = ".$suffix")
        }
        if (navigatedUrl != null && VirtualOrigin.isVirtualUrl(navigatedUrl)) {
            val host = navigatedUrl.removePrefix("https://").substringBefore('/')
            expired += expireAllFor(cm, "https://$host/", domain = null)
        }
        if (expired > 0) {
            Log.i(TAG, "expired $expired cookie(s) under virtual origins")
            runCatching { cm.flush() }
        }
    }

    /**
     * Expire every cookie [CookieManager] would send to [url]. Each is
     * rewritten with `Max-Age=0` both host-scoped and (when [domain] is
     * given) domain-scoped, since we can't see which scope the original
     * carried.
     */
    private fun expireAllFor(cm: CookieManager, url: String, domain: String?): Int {
        val cookies = runCatching { cm.getCookie(url) }.getOrNull() ?: return 0
        var count = 0
        for (cookie in cookies.split(';')) {
            val name = cookie.substringBefore('=').trim()
            if (name.isEmpty()) continue
            runCatching {
                cm.setCookie(url, "$name=; Path=/; Max-Age=0")
                if (domain != null) {
                    cm.setCookie(url, "$name=; Domain=$domain; Path=/; Max-Age=0")
                }
            }
            count++
        }
        return count
    }
}
