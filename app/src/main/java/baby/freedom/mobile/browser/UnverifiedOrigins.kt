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
 * count for every tab not navigated since, [noteWorkerDocument]) and [hold]s those origins
 * until each such tab has committed its next document — however long
 * that takes: a reload that doesn't navigate (a POST result's, which
 * WebView won't resend) is followed by one that can't be refused
 * ([SweptReload]), and the hold ends only at that commit. Until then
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
 * next start — except an origin only a private tab (#86) was served
 * ([record] with `private`): naming the CIDs a private session opened in
 * a file that outlives it would be a trail of it, and nothing of the
 * private session's own storage outlives the process anyway (its
 * profile is deleted at the next start). Such an origin is swept like
 * any other while this process lasts, in the private profile's storage
 * too ([wipeWebData]).
 *
 * Profiles (#351, #360): a private tab's storage is its own profile's,
 * which neither the default profile's `WebStorage` nor a cleanup page
 * run in a normal tab reaches, and the other way round. So the pending
 * cleanups and the holds are kept per profile: a [sweep] queues one for
 * the default profile and, while a private session is live
 * ([privateSessionStarted]), one for the private profile; [takeClearFor]
 * only takes the requester's own profile's; a tab's hold is on its own
 * profile's storage. The private profile's are memory only, and go when
 * its session ends ([privateSessionEnded]) — its storage goes with it.
 * Recorded from the interceptor's IO threads, swept on the
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

    /** Origins whose next document in the default profile clears their site data. */
    private val toClear = LinkedHashSet<String>()

    /**
     * The same for the live private session's profile (#351, #360):
     * queued by a [sweep] or a private tab's [release] only while that
     * session is live, and dropped when it ends. Memory only.
     */
    private val privateToClear = LinkedHashSet<String>()

    /** A private session is live: a private tab has been put on its profile since it started. */
    private var privateLive = false

    /**
     * Origins only a private tab was served, wherever they are now
     * ([origins], [toClear], a hold that [release] puts back into
     * [toClear]): kept in memory, never persisted (see the class doc).
     * Left only when a normal tab is served one ([record]); never
     * entered by an origin a normal tab's earlier gateway left a
     * default-profile cleanup pending on.
     */
    private val privateOnly = HashSet<String>()

    /** The IPFS gateway [sweep] last saw in use; `null` until the first sweep. */
    private var current: String? = null

    /** Bumped whenever [current] changes: a [record] token from an earlier one is stale. */
    private var generation = 0L

    /** Origins held for cleanup, by holder, in the holder's profile ([hold]). */
    private class Hold(val private: Boolean, val origins: Set<String>)

    private val holds = HashMap<Any, Hold>()

    /**
     * Requesters (a tab, or `null` for a profile's service workers)
     * already served the cleanup page on an origin because another tab
     * of the same profile held it ([takeClearFor]), as (profile,
     * requester, origin); emptied whenever [holds] change.
     */
    private val clearedWhileHeld = HashSet<Triple<Boolean, Any?, String>>()

    /**
     * Recorded origins a service worker fetched a document on, with the
     * [DocumentClock] tick of the last such fetch ([noteWorkerDocument]).
     * Only ever recorded origins, so a sweep (which takes them all)
     * empties it.
     */
    private val workerDocuments = HashMap<String, Long>()

    /**
     * The tab host's reaction to a sweep: reload the tabs whose documents
     * are on the swept origins and [hold] those. Main thread, called
     * from [sweep] after the wipe.
     */
    @Volatile
    var onSweep: ((Set<String>) -> Unit)? = null

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
    fun record(gateway: String, origin: String, private: Boolean = false): Long? = synchronized(lock) {
        if (current != null && current != gateway) return null
        // A normal tab's request makes an origin a private tab had first
        // an ordinary, persisted one.
        val madePublic = !private && privateOnly.remove(origin)
        if (this.gateway != gateway || origin !in origins || madePublic) {
            this.gateway = gateway
            // Private-only just for an origin this process knows nothing
            // else of: one a normal tab's earlier gateway left a cleanup
            // pending on ([toClear], or a [hold] that [release] puts back
            // there) keeps that cleanup on disk — filtering it out of the
            // persisted list would lose the normal profile's (R1-F1).
            if (origins.add(origin) && private && origin !in toClear &&
                holds.values.none { !it.private && origin in it.origins }
            ) {
                privateOnly.add(origin)
            }
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
     * [onSweep], and forget them, queuing each one's cleanup page in the
     * default profile and, while one is live, the private session's
     * ([takeClearFor]). Returns what was swept.
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
            if (privateLive) privateToClear.addAll(all)
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
     * committed), every document requested on them is the cleanup page
     * again, so whatever the stale document writes meanwhile is cleared
     * after it's gone.
     *
     * No timeout: a hold that ran out while the stale document still
     * lived would queue its one cleanup and leave the document to write
     * after it (R6-F1). The tab host guarantees the commit instead
     * ([SweptReload] falls back to a navigation that can't be refused).
     *
     * Adds to a hold [holder] already has (two sweeps in a row, say
     * external A → external B → embedded, before the tab commits): the
     * first sweep's origins still get their cleanup page at [release].
     *
     * [private]: [holder] is a private tab (#351). Its stale document
     * writes to the private profile's storage only, so the hold serves
     * the cleanup page to that profile's requests, and [release] queues
     * it there; a normal tab's hold, the default profile's.
     */
    fun hold(holder: Any, origins: Set<String>, private: Boolean = false) {
        if (origins.isEmpty()) return
        synchronized(lock) {
            val had = holds[holder]
            holds[holder] = Hold(had?.private ?: private, had?.origins.orEmpty() + origins)
            clearedWhileHeld.clear()
        }
    }

    /**
     * [holder]'s stale document is gone (see [hold]). What it wrote
     * before going may postdate every cleanup page run so far — another
     * tab can have consumed the one-shot clear first — so each held
     * origin's next document in the holder's profile is the cleanup page
     * once more, whichever tab or frame requests it (a private holder's
     * only while its session is live: once it ends, there's no storage
     * left to clear).
     *
     * Also called for a holder that went away rather than committed —
     * its tab closed, or the tab host disposed — and it re-queues the
     * cleanup there too, deliberately: the stale document ran until
     * then, so what it wrote must still be cleared, at the price of one
     * extra cleanup page (persisted, so possibly at a later start) on
     * each held origin's next visit.
     */
    fun release(holder: Any) {
        synchronized(lock) {
            val held = holds.remove(holder) ?: return
            clearedWhileHeld.clear()
            if (!held.private) {
                if (toClear.addAll(held.origins)) persist()
            } else if (privateLive) {
                privateToClear.addAll(held.origins)
            }
        }
    }

    /**
     * A private session (#86) has started: from now on a [sweep] queues
     * a cleanup in its profile too (#351). Main thread.
     */
    fun privateSessionStarted() {
        synchronized(lock) { privateLive = true }
    }

    /**
     * The private session has ended, its profile wiped: what was pending
     * for it — cleanups, holds — goes with its storage. Main thread.
     */
    fun privateSessionEnded() {
        synchronized(lock) {
            privateLive = false
            privateToClear.clear()
            holds.values.removeAll { it.private }
            clearedWhileHeld.removeAll { it.first }
        }
    }

    /**
     * A service worker fetched a document (typically a frame's) on
     * [origin], noted once its answer is in (so an external gateway's has
     * been [record]ed) but at [tick], taken before the fetch started: a
     * slow fetch of an outgoing document's frame mustn't be placed after
     * the next document's answer. Worker fetches reach no tab's `WebViewClient`, so the
     * tab host can't tell which tab it's in: a sweep of [origin] treats
     * every tab whose document on screen was answered before this fetch
     * as having it ([takeWorkerDocuments],
     * [TabDocuments.mayHoldWorkerFetchAt]). Only an origin the external
     * gateway served is kept — nothing else is ever swept. Any thread.
     */
    fun noteWorkerDocument(origin: String, tick: Long) {
        synchronized(lock) {
            if (origin in origins) workerDocuments[origin] = maxOf(tick, workerDocuments[origin] ?: 0L)
        }
    }

    /**
     * The [swept] origins a service worker fetched a document on, with
     * the tick of the last such fetch, now handed to the tab host to
     * reload the tabs that may have them, and forgotten.
     */
    fun takeWorkerDocuments(swept: Set<String>): Map<String, Long> = synchronized(lock) {
        val taken = workerDocuments.filterKeys { it in swept }
        workerDocuments.keys.removeAll(taken.keys)
        taken
    }

    /** Origins noted by [noteWorkerDocument] and not yet taken (tests). */
    internal fun workerDocumentOrigins(): Set<String> = synchronized(lock) { workerDocuments.keys.toSet() }

    /**
     * Should the document now being requested on [origin] by [requester]
     * (the tab, `null` for a service worker) clear the origin's site data
     * first? True once per swept origin, and again while a tab [hold]s
     * it: every time for the holder itself (its hold ends at its next
     * commit), and once per hold for anyone else — a tab that already
     * released would otherwise be served the page it reloads from over
     * and over until the other tab commits. The interceptor then answers with a
     * same-origin page that clears it and reloads
     * (`SITE_DATA_CLEANUP_HTML`) — the only way to reach the DOM storage
     * and service workers [wipeWebData] can't.
     *
     * [private]: the requester is a private tab (#86), or the private
     * profile's service workers. The cleanup page runs in the
     * requester's profile and clears that profile's storage only, so
     * each profile's pending cleanup and holds are its own (#351, #360):
     * a private tab taking its cleanup leaves the default profile's
     * pending, and the other way round.
     */
    fun takeClearFor(origin: String, requester: Any? = null, private: Boolean = false): Boolean = synchronized(lock) {
        if (private) {
            if (privateToClear.remove(origin)) return true
        } else if (toClear.remove(origin)) {
            persist()
            return true
        }
        val own = holds[requester]
        if (own != null && own.private == private && origin in own.origins) return true
        holds.values.any { it.private == private && origin in it.origins } &&
            clearedWhileHeld.add(Triple(private, requester, origin))
    }

    /**
     * [sweep]'s wipe in the app: the origins' DOM storage, IndexedDB and
     * WebSQL, and the cookies page script set on them (the interceptor
     * strips cookies from gateway traffic, so only `document.cookie`
     * writes exist). In the private session's profile (#86) as well,
     * while one is live: a private tab's storage is its own, which the
     * default profile's `WebStorage`/`CookieManager` never reach. Main
     * thread.
     */
    fun wipeWebData(origins: Set<String>) {
        wipeIn(runCatching { WebStorage.getInstance() }.getOrNull(), runCatching { CookieManager.getInstance() }.getOrNull(), origins)
        if (PrivateProfile.isLive()) wipeIn(PrivateProfile.webStorage(), PrivateProfile.cookieManager(), origins)
    }

    private fun wipeIn(storage: WebStorage?, cookies: CookieManager?, origins: Set<String>) {
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
            privateToClear.clear()
            privateLive = false
            privateOnly.clear()
            holds.clear()
            clearedWhileHeld.clear()
            workerDocuments.clear()
            current = null
            generation = 0
            persist()
        }
    }

    /** Is [holder] holding any origin (tests)? */
    internal fun isHeld(holder: Any): Boolean = synchronized(lock) { holder in holds }

    /** Origins whose next document in the default, or the private, profile clears their site data (tests). */
    internal fun pendingClears(private: Boolean = false): Set<String> =
        synchronized(lock) { (if (private) privateToClear else toClear).toSet() }

    /** What [persist] writes: [origins] and [toClear] without [privateOnly]. Under [lock]. */
    private fun persisted(): Pair<Set<String>, Set<String>> =
        origins.filterTo(LinkedHashSet()) { it !in privateOnly } to
            toClear.filterTo(LinkedHashSet()) { it !in privateOnly }

    /** What the persisted list holds now: recorded origins to cleanup-pending ones (tests). */
    internal fun persistedSnapshot(): Pair<Set<String>, Set<String>> = synchronized(lock) { persisted() }

    private fun persist() {
        val (keptOrigins, keptToClear) = persisted()
        prefs?.edit()
            ?.putString(KEY_GATEWAY, gateway)
            ?.putStringSet(KEY_ORIGINS, keptOrigins)
            ?.putStringSet(KEY_TO_CLEAR, keptToClear)
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
