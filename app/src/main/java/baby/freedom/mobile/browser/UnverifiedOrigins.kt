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
 * Service workers can't be left behind this way: the interceptor
 * refuses a service-worker script from an external gateway (see
 * [isServiceWorkerScript]), since one would keep answering the origin's
 * requests from its own code after the switch.
 *
 * Persisted, so a switch made while the app wasn't running (or a
 * process death between serving and switching) is still swept at the
 * next start. Recorded from the interceptor's IO threads, swept on the
 * main thread.
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

    /** [origin] was served content from the external IPFS gateway [gateway]. */
    fun record(gateway: String, origin: String) {
        synchronized(lock) {
            if (this.gateway == gateway && origin in origins) return
            this.gateway = gateway
            origins.add(origin)
            persist()
        }
    }

    /**
     * The IPFS gateway in use is now [currentExternal] (`""` = the
     * embedded node). If the recorded origins came from a different
     * gateway, hand them to [wipe] and forget them. Returns what was
     * wiped.
     */
    fun sweep(currentExternal: String, wipe: (Set<String>) -> Unit): Set<String> {
        val swept = synchronized(lock) {
            if (origins.isEmpty() || gateway == currentExternal) return emptySet()
            val all = origins.toSet()
            origins.clear()
            toClear.addAll(all)
            gateway = ""
            persist()
            all
        }
        wipe(swept)
        return swept
    }

    /**
     * Should the document now being requested on [origin] clear the
     * origin's site data first? True once per swept origin: the
     * interceptor then answers with a same-origin page that clears it
     * and reloads (`SITE_DATA_CLEANUP_HTML`) — the only way to reach the
     * DOM storage and service workers [wipeWebData] can't.
     */
    fun takeClearFor(origin: String): Boolean = synchronized(lock) {
        if (!toClear.remove(origin)) return false
        persist()
        true
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
