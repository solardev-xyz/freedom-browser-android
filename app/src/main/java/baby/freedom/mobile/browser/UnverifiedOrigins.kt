package baby.freedom.mobile.browser

import android.content.Context
import android.content.SharedPreferences
import android.webkit.CookieManager
import android.webkit.WebStorage

/**
 * Virtual origins that have received content from an external IPFS
 * gateway (#125).
 *
 * The virtual origin of a root (`https://<cid>.ipfs.freedom.baby`) is
 * the same whichever gateway serves it — the origin can't carry the
 * source: CID labels are already near the 63-char limit, and an extra
 * label would put every externally-served root on one registrable
 * domain under the Public Suffix List. So whatever an unverified
 * gateway's pages leave behind on an origin (localStorage, IndexedDB,
 * cookies) would still be there, and readable, once the user switches
 * back to the verified embedded node. This keeps the list of origins
 * the gateway reached, and [sweep] wipes them as soon as that gateway
 * is no longer the one in use: what `WebStorage` can delete (IndexedDB,
 * WebSQL, cookies) right away, and the rest (localStorage and the other
 * DOM storage `WebStorage.deleteOrigin` leaves alone) from the origin
 * itself, by a page served in place of the next document requested
 * there ([takeClearFor]), before any content runs.
 *
 * Documents the gateway already served may still be alive when the
 * switch happens — one live WebView per tab. [sweep] hands the swept
 * origins to [onSweep] (the tab host), which reloads every tab that
 * showed one (in any frame; frame documents a service worker fetched
 * count for every tab, [noteWorkerDocument]) and [hold]s those origins
 * until each such tab has committed its next document: until then
 * every document requested there is the cleanup page again
 * ([takeClearFor]), and once it has, the next one is again
 * ([release]), so storage a stale document wrote before it went is
 * cleared after it's gone.
 *
 * Service workers: the interceptor refuses a service-worker script from
 * an external gateway (see [isServiceWorkerScript]), and stamps
 * `Vary: *` on everything that gateway serves, which Cache Storage
 * refuses to store — so a worker registered while on the embedded node
 * can't keep the gateway's responses for later either. What this can't
 * reach: a navigation such a worker answers itself (from a cache filled
 * on the embedded node) never reaches the interceptor, so on an origin
 * with a worker the cleanup page only runs once the worker lets a
 * navigation through to the network. `WebStorage` has no per-origin way
 * to drop a worker, and IndexedDB and cookies are still wiped straight
 * away.
 *
 * Persisted, so a switch made while the app wasn't running (or a
 * process death between serving and switching) is still swept at the
 * next start. Recorded from the interceptor's IO threads, swept on the
 * main thread; a request that recorded against a gateway a sweep has
 * since replaced is told so ([record], [isCurrent]) and re-resolved.
 */
object UnverifiedOrigins {
    private const val PREFS = "unverified_origins"
    private const val KEY_GATEWAY = "gateway"
    private const val KEY_ORIGINS = "origins"
    private const val KEY_TO_CLEAR = "to_clear"

    private val lock = Any()
    private var prefs: SharedPreferences? = null
    private var gateway = ""
    private val origins = LinkedHashSet<String>()
    private val toClear = LinkedHashSet<String>()

    /** The IPFS gateway [sweep] last saw in use; `null` until the first sweep. */
    private var current: String? = null

    /** Bumped whenever [current] changes: a [record] token from an earlier one is stale. */
    private var generation = 0L

    /** Origins held for cleanup, by holder, with their expiry ([hold]). */
    private val holds = HashMap<Any, Pair<Set<String>, Long>>()

    /** Origins a service worker fetched a document on ([noteWorkerDocument]). */
    private val workerDocuments = LinkedHashSet<String>()

    /**
     * The tab host's reaction to a sweep: reload the tabs whose documents
     * are on the swept origins and [hold] those. Main thread, called
     * from [sweep] after the wipe.
     */
    @Volatile
    var onSweep: ((Set<String>) -> Unit)? = null

    /** Monotonic clock for [hold] expiry; a seam for tests. */
    @Volatile
    internal var clock: () -> Long = { System.nanoTime() / 1_000_000 }

    /** Load the persisted list (disk I/O). Call before the first [sweep]; idempotent. */
    fun init(context: Context) {
        if (synchronized(lock) { prefs != null }) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        synchronized(lock) {
            if (prefs != null) return
            prefs = p
            gateway = p.getString(KEY_GATEWAY, "") ?: ""
            origins.clear()
            origins.addAll(p.getStringSet(KEY_ORIGINS, emptySet()) ?: emptySet())
            toClear.clear()
            toClear.addAll(p.getStringSet(KEY_TO_CLEAR, emptySet()) ?: emptySet())
        }
    }

    /**
     * [origin] is being served content from the external IPFS gateway
     * [gateway]. Returns a token for [isCurrent], or `null` if a
     * [sweep] has already switched away from [gateway] — the caller
     * resolved its target before the switch and must resolve it again
     * rather than serve (and record) the old gateway's content.
     */
    fun record(gateway: String, origin: String): Long? = synchronized(lock) {
        if (current != null && current != gateway) return null
        if (this.gateway != gateway || origin !in origins) {
            this.gateway = gateway
            origins.add(origin)
            persist()
        }
        generation
    }

    /**
     * Is the gateway a [record] returned [token] for still the one in
     * use? `false` once a [sweep] switched away in between: the response
     * fetched meanwhile must not be served.
     */
    fun isCurrent(token: Long): Boolean = synchronized(lock) { token == generation }

    /**
     * The IPFS gateway in use is now [currentExternal] (`""` = the
     * embedded node). [apply] runs first, under the same lock [record]
     * takes, so it's where the new gateway is made the one requests
     * resolve against: a request can't record against the old gateway
     * after this sweep, nor re-resolve to it. If the recorded origins
     * came from a different gateway, hand them to [wipe] and then
     * [onSweep], and forget them. Returns what was swept.
     */
    fun sweep(
        currentExternal: String,
        apply: () -> Unit = {},
        wipe: (Set<String>) -> Unit,
    ): Set<String> {
        val swept = synchronized(lock) {
            apply()
            if (current != currentExternal) {
                current = currentExternal
                generation++
            }
            if (origins.isEmpty() || gateway == currentExternal) return emptySet()
            val all = origins.toSet()
            origins.clear()
            toClear.addAll(all)
            gateway = ""
            persist()
            all
        }
        wipe(swept)
        onSweep?.invoke(swept)
        return swept
    }

    /**
     * [holder] (a tab) still has a document on [origins] that was
     * served before a [sweep]: until [release] (its next document
     * committed) or [HOLD_MS] passes, every document requested on them
     * is the cleanup page again, so whatever the stale document writes
     * meanwhile is cleared after it's gone.
     */
    fun hold(holder: Any, origins: Set<String>) {
        if (origins.isEmpty()) return
        synchronized(lock) { holds[holder] = origins to clock() + HOLD_MS }
    }

    /**
     * [holder]'s stale document is gone (see [hold]). What it wrote
     * before going may postdate every cleanup page run so far — another
     * tab can have consumed the one-shot clear first — so each held
     * origin's next document is the cleanup page once more, whichever
     * tab or frame requests it.
     */
    fun release(holder: Any) {
        synchronized(lock) {
            val (held, _) = holds.remove(holder) ?: return
            if (toClear.addAll(held)) persist()
        }
    }

    /**
     * A service worker fetched a document (typically a frame's) on
     * [origin]. Worker fetches reach no tab's `WebViewClient`, so the
     * tab host can't tell which tab it's in: a sweep of [origin] treats
     * every tab as having it ([takeWorkerDocuments]). Any thread.
     */
    fun noteWorkerDocument(origin: String) {
        synchronized(lock) { workerDocuments.add(origin) }
    }

    /**
     * The [swept] origins a service worker fetched a document on, now
     * handed to the tab host to reload every tab for, and forgotten.
     */
    fun takeWorkerDocuments(swept: Set<String>): Set<String> = synchronized(lock) {
        val taken = workerDocuments.intersect(swept)
        workerDocuments.removeAll(taken)
        taken
    }

    /** A bound on [hold], for a tab whose reload never commits. */
    private const val HOLD_MS = 10_000L

    /**
     * Should the document now being requested on [origin] clear the
     * origin's site data first? True once per swept origin, and again
     * while a tab [hold]s it: the interceptor then answers with a
     * same-origin page that clears it and reloads
     * (`SITE_DATA_CLEANUP_HTML`) — the only way to reach the DOM storage
     * and service workers [wipeWebData] can't.
     */
    fun takeClearFor(origin: String): Boolean = synchronized(lock) {
        // An expired hold ends like a released one (see [release]).
        val now = clock()
        val expired = holds.values.filter { (_, until) -> until <= now }
        if (expired.isNotEmpty()) {
            holds.values.removeAll { (_, until) -> until <= now }
            expired.forEach { (held, _) -> toClear.addAll(held) }
            persist()
        }
        if (toClear.remove(origin)) {
            persist()
            return true
        }
        holds.values.any { (held, _) -> origin in held }
    }

    /**
     * [sweep]'s wipe in the app: the origins' DOM storage, IndexedDB and
     * WebSQL, and the cookies page script set on them (the interceptor
     * strips cookies from gateway traffic, so only `document.cookie`
     * writes exist). Main thread.
     */
    fun wipeWebData(origins: Set<String>) {
        val storage = runCatching { WebStorage.getInstance() }.getOrNull()
        val cookies = runCatching { CookieManager.getInstance() }.getOrNull()
        for (origin in origins) {
            runCatching { storage?.deleteOrigin(origin) }
            runCatching {
                cookies?.getCookie(origin)?.split(';')?.forEach { pair ->
                    val name = pair.substringBefore('=').trim()
                    if (name.isNotEmpty()) cookies.setCookie(origin, "$name=; Max-Age=0; Path=/")
                }
            }
        }
        runCatching { cookies?.flush() }
    }

    /** Recorded origins (tests). */
    internal fun snapshot(): Set<String> = synchronized(lock) { origins.toSet() }

    /** Forget everything, persisted list included (tests). */
    internal fun reset() {
        synchronized(lock) {
            gateway = ""
            origins.clear()
            toClear.clear()
            holds.clear()
            workerDocuments.clear()
            current = null
            generation = 0
            persist()
        }
    }

    /** Origins whose next document clears their site data (tests). */
    internal fun pendingClears(): Set<String> = synchronized(lock) { toClear.toSet() }

    private fun persist() {
        prefs?.edit()
            ?.putString(KEY_GATEWAY, gateway)
            ?.putStringSet(KEY_ORIGINS, origins.toSet())
            ?.putStringSet(KEY_TO_CLEAR, toClear.toSet())
            ?.apply()
    }
}

/**
 * Is this request a service worker's script (the registration's main
 * script, or an update check of it)? Chromium marks those with a
 * `Service-Worker: script` request header.
 */
internal fun isServiceWorkerScript(headers: Map<String, String>?): Boolean =
    headers?.entries?.any {
        it.key.equals("Service-Worker", ignoreCase = true) &&
            it.value.trim().equals("script", ignoreCase = true)
    } == true
