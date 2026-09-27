package baby.freedom.mobile.browser

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import androidx.core.content.ContextCompat
import baby.freedom.mobile.data.SitePermissionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A prompt waiting for the user: "[origin] wants to [permissions]".
 * Shown by [BrowserScreen] while it is its tab's
 * [BrowserState.permissionPrompt] and that tab is active; answered by
 * completing [answer].
 */
class PermissionPrompt internal constructor(
    val origin: String,
    val permissions: List<SitePermission>,
) {
    internal val answer = CompletableDeferred<PromptAnswer>()

    fun respond(a: PromptAnswer) {
        answer.complete(a)
    }
}

/**
 * Glue between WebView's permission callbacks, the user, Android's
 * runtime permissions, and the stores (#81). One per process.
 *
 * Flow of a request (camera/mic via `onPermissionRequest`, location via
 * `onGeolocationPermissionsShowPrompt`):
 *
 *  1. Key it by the requesting origin ([permissionOriginKey]); a
 *     non-http(s) origin or a capability we don't prompt for is denied.
 *  2. Look up remembered + session decisions ([planFor]).
 *  3. Undecided → put a [PermissionPrompt] on the tab and wait. One
 *     prompt at a time per tab: requests queue behind a per-tab lock
 *     and re-plan when they get it, so a second identical request
 *     after "Allow + remember" goes straight through instead of asking
 *     twice. A background tab's prompt waits until the user switches
 *     to it.
 *  4. Site allowed → *only now* ask Android for the runtime permission
 *     (CAMERA / RECORD_AUDIO / location) if the app doesn't hold it,
 *     via [requestAndroidPermissions] — once that tab is on screen,
 *     as for the prompt. Granted to the page only if the
 *     user allowed both the site and the app.
 *
 * A request is withdrawn — denied once, nothing recorded, prompt taken
 * down — when WebView cancels it, when the tab starts a new document,
 * or when the tab closes. Every wait re-checks the tab's document
 * generation afterwards, so an answer can never be applied to a
 * document that replaced the one that asked.
 */
class SitePermissionBroker private constructor(
    private val appContext: Context,
    private val store: SitePermissionStore,
) {
    val session = PermissionSession()
    private val scope = MainScope()

    /**
     * Installed by [BrowserScreen]: launch Android's runtime-permission
     * dialog for the given permissions and return the result. `null`
     * while no screen is composed — requests needing it are denied.
     */
    @Volatile
    var requestAndroidPermissions: (suspend (List<String>) -> Map<String, Boolean>)? = null

    /**
     * Installed by [BrowserScreen]: the site was allowed but Android
     * refused the app the permission (e.g. "Don't allow" earlier), so
     * the page got nothing — tell the user where to fix it.
     */
    @Volatile
    var onAndroidPermissionMissing: ((List<SitePermission>) -> Unit)? = null

    /**
     * Set by [BrowserScreen]: the tab whose page is what's on screen —
     * the active tab, with no full-screen panel over it — or `null`.
     * Android's runtime-permission dialog names no site, so like the
     * Freedom prompt it is only ever raised over the page that asked.
     */
    val onScreenTab = MutableStateFlow<Long?>(null)

    /**
     * `true` while Android's runtime-permission dialog is up. The
     * download-offer prompt waits on it ([modalPromptTurn]) so it isn't
     * composed underneath the system dialog and armed unseen.
     */
    val androidDialogUp = MutableStateFlow(false)

    private val androidDialogLock = Mutex()

    /** A request in flight, from arrival until it's granted or denied. */
    private class Pending(val tabId: Long, val token: Any) {
        val withdrawn = MutableStateFlow(false)
        var prompt: PermissionPrompt? = null
    }

    // All of the below is touched on the main thread only (WebView
    // callbacks and [scope] both run there).
    private val pending = mutableListOf<Pending>()
    private val documents = HashMap<Long, Int>()
    private val tabLocks = HashMap<Long, Mutex>()

    // ---------------------------------------------------------------
    // WebView callbacks
    // ---------------------------------------------------------------

    /** `WebChromeClient.onPermissionRequest` for [tab]. */
    fun onMediaRequest(tab: BrowserState, request: PermissionRequest) {
        val byPermission = request.resources.orEmpty().mapNotNull { r ->
            when (r) {
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> SitePermission.CAMERA to r
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> SitePermission.MICROPHONE to r
                // Protected media (EME) and MIDI SysEx stay denied, as
                // they were before this broker existed.
                else -> null
            }
        }
        val origin = permissionOriginKey(request.origin?.toString())
        if (byPermission.isEmpty() || origin == null) {
            request.deny()
            return
        }
        handle(
            tab = tab,
            origin = origin,
            permissions = byPermission.map { it.first },
            token = request,
            grant = { request.grant(byPermission.map { it.second }.toTypedArray()) },
            deny = { request.deny() },
        )
    }

    /** `WebChromeClient.onPermissionRequestCanceled`. */
    fun onMediaRequestCanceled(tab: BrowserState, request: PermissionRequest) =
        withdraw(tab.id) { it.token === request }

    /** `WebChromeClient.onGeolocationPermissionsShowPrompt` for [tab]. */
    fun onGeolocationRequest(
        tab: BrowserState,
        rawOrigin: String?,
        callback: GeolocationPermissions.Callback?,
    ) {
        callback ?: return
        val origin = permissionOriginKey(rawOrigin)
        if (origin == null) {
            callback.invoke(rawOrigin, false, false)
            return
        }
        // `retain = false` always: remembering is ours to do (and to
        // revoke), not WebView's own GeolocationPermissions store.
        handle(
            tab = tab,
            origin = origin,
            permissions = listOf(SitePermission.LOCATION),
            token = callback,
            grant = { callback.invoke(rawOrigin, true, false) },
            deny = { callback.invoke(rawOrigin, false, false) },
        )
    }

    /** `WebChromeClient.onGeolocationPermissionsHidePrompt`. */
    fun onGeolocationHidden(tab: BrowserState) =
        withdraw(tab.id) { it.token is GeolocationPermissions.Callback }

    /** The tab started a new document: nothing asked by the old one may land. */
    fun onDocumentStarted(tab: BrowserState) {
        documents[tab.id] = (documents[tab.id] ?: 0) + 1
        withdraw(tab.id) { true }
    }

    /**
     * The tab is gone. Its requests are withdrawn (which alone keeps
     * them from landing), and its bookkeeping dropped so a long session
     * doesn't accumulate an entry per tab ever opened.
     */
    fun onTabClosed(tabId: Long) {
        withdraw(tabId) { true }
        documents.remove(tabId)
        tabLocks.remove(tabId)
    }

    // ---------------------------------------------------------------
    // Settings
    // ---------------------------------------------------------------

    /**
     * Every decision the user can revoke: remembered ones, then this
     * run's session-only ones (including dismissal embargoes).
     */
    val entries: Flow<List<SitePermissionEntry>> =
        combine(store.all, session.version) { records, _ ->
            val stored = records.mapNotNull { r ->
                val p = SitePermission.forKey(r.permission) ?: return@mapNotNull null
                val d = PermissionDecision.fromStored(r.decision) ?: return@mapNotNull null
                SitePermissionEntry(r.origin, p, d, remembered = true)
            }
            val storedKeys = stored.map { it.origin to it.permission }.toSet()
            stored + session.entries()
                .filter { (it.origin to it.permission) !in storedKeys }
                .sortedWith(compareBy({ it.origin }, { it.permission.ordinal }))
        }

    /** Forget [entry] everywhere, so the site has to ask again. */
    fun revoke(entry: SitePermissionEntry) {
        session.revoke(entry.origin, entry.permission)
        scope.launch { store.remove(entry.origin, entry.permission.key) }
    }

    // ---------------------------------------------------------------
    // The flow
    // ---------------------------------------------------------------

    private fun handle(
        tab: BrowserState,
        origin: String,
        permissions: List<SitePermission>,
        token: Any,
        grant: () -> Unit,
        deny: () -> Unit,
    ) {
        val doc = documents[tab.id] ?: 0
        val entry = Pending(tab.id, token)
        pending += entry
        fun live() = !entry.withdrawn.value && (documents[tab.id] ?: 0) == doc
        var finished = false
        fun finish(allowed: Boolean) {
            if (finished) return
            finished = true
            pending.remove(entry)
            runCatching { if (allowed && live()) grant() else deny() }
                .onFailure { Log.w(TAG, "answering permission request failed", it) }
        }
        scope.launch {
            // Whatever goes wrong below, the page gets an answer (a deny)
            // instead of a request left hanging — and the app doesn't
            // crash on a main-thread exception.
            try {
                decide(tab, entry, origin, permissions, ::live, ::finish)
            } catch (e: CancellationException) {
                finish(false)
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "permission request failed; denying", e)
                finish(false)
            }
        }
    }

    private suspend fun decide(
        tab: BrowserState,
        entry: Pending,
        origin: String,
        permissions: List<SitePermission>,
        live: () -> Boolean,
        finish: (Boolean) -> Unit,
    ) {
        // Withdrawn before it got going (e.g. the tab closed): don't
        // re-create the closed tab's lock.
        if (!live()) return finish(false)
        val lock = tabLocks.getOrPut(tab.id) { Mutex() }
        val siteAllowed = lock.withLock {
            var allowed: Boolean? = null
            while (allowed == null) {
                if (!live()) return@withLock false
                val stored = storedDecisions(origin)
                if (!live()) return@withLock false
                allowed = when (val plan = planFor(origin, permissions, stored, session)) {
                    PermissionPlan.Deny -> false
                    PermissionPlan.Grant -> true
                    // null: settled by another tab's answer meanwhile — re-plan.
                    is PermissionPlan.Ask -> ask(tab, entry, origin, plan.undecided)
                }
            }
            allowed
        }
        if (!siteAllowed || !live()) return finish(false)
        finish(ensureAndroidPermissions(entry, permissions, live))
    }

    private suspend fun storedDecisions(origin: String): Map<SitePermission, PermissionDecision> =
        store.decisionsFor(origin).mapNotNull { (k, v) ->
            val p = SitePermission.forKey(k) ?: return@mapNotNull null
            val d = PermissionDecision.fromStored(v) ?: return@mapNotNull null
            p to d
        }.toMap()

    /**
     * Show the prompt, record the answer, and say whether the site is
     * now allowed — or `null` if the question was settled elsewhere
     * while the prompt was up and the request must re-plan.
     *
     * Per-tab locks don't serialise tabs against each other, so two
     * tabs on the same origin can each have a prompt up for the same
     * thing. When one is answered (or an embargo lands), the other is
     * taken down ([PromptAnswer.Superseded]) instead of asking the user
     * again and overwriting the first answer.
     */
    private suspend fun ask(
        tab: BrowserState,
        entry: Pending,
        origin: String,
        undecided: List<SitePermission>,
    ): Boolean? {
        val prompt = PermissionPrompt(origin, undecided)
        entry.prompt = prompt
        tab.permissionPrompt = prompt
        val answer = try {
            coroutineScope {
                val watcher = launch {
                    awaitPromptSuperseded(origin, undecided, session) { storedDecisions(origin) }
                    prompt.respond(PromptAnswer.Superseded)
                }
                try {
                    prompt.answer.await()
                } finally {
                    watcher.cancel()
                }
            }
        } finally {
            entry.prompt = null
            if (tab.permissionPrompt === prompt) tab.permissionPrompt = null
        }
        return when (answer) {
            is PromptAnswer.Allow -> {
                record(origin, undecided, PermissionDecision.ALLOW, answer.remember)
                true
            }
            is PromptAnswer.Block -> {
                record(origin, undecided, PermissionDecision.DENY, answer.remember)
                false
            }
            PromptAnswer.Dismiss -> {
                for (p in undecided) session.dismiss(origin, p)
                false
            }
            PromptAnswer.Withdrawn -> false
            PromptAnswer.Superseded -> null
        }
    }

    private suspend fun record(
        origin: String,
        permissions: List<SitePermission>,
        decision: PermissionDecision,
        remember: Boolean,
    ) {
        // The session tier takes the decision at once, so a same-origin
        // request from another tab (whose own lock doesn't wait for this
        // one) sees it while the store write is still in flight; a
        // remembered decision only leaves the session tier once the
        // store holds it.
        for (p in permissions) session.record(origin, p, decision, remembered = false)
        if (!remember) return
        // A failed write (the store logs it) leaves the decision as a
        // session one: it still applies this run and Settings shows it
        // as "this session", which is the truth.
        val written = permissions.filter { store.set(origin, it.key, decision.stored) }
        for (p in written) {
            // Unless the user revoked or re-decided it meanwhile.
            if (session.decisionFor(origin, p) == decision) {
                session.record(origin, p, decision, remembered = true)
            }
        }
    }

    /**
     * The site is allowed; make sure the app is too. Asks Android only
     * for what's missing, one system dialog at a time — and not at all
     * for a request that was withdrawn while it queued for the dialog
     * ([live] false), which is denied without a word.
     *
     * The dialog waits, like the Freedom prompt, until the requesting
     * tab's page is on screen ([onScreenTab]): a remembered Allow in a
     * background tab (or behind Settings), or from a page still running
     * while the app itself is in the background, must not pop a
     * site-less system dialog over something else. The wait happens outside the
     * dialog lock so a background request can't hold up the tab the
     * user is actually looking at.
     */
    private suspend fun ensureAndroidPermissions(
        entry: Pending,
        permissions: List<SitePermission>,
        live: () -> Boolean,
    ): Boolean {
        fun held(p: SitePermission) = p.androidPermissions.any {
            ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED
        }
        if (permissions.all(::held)) return true
        var missing: List<SitePermission>? = null
        while (missing == null) {
            if (!awaitTabOnScreen(onScreenTab, entry.tabId, entry.withdrawn)) return false
            missing = androidDialogLock.withLock {
                if (!live()) return false
                val before = permissions.filterNot(::held)
                if (before.isEmpty()) return@withLock before
                // Switched away while queued behind another dialog: wait again.
                if (onScreenTab.value != entry.tabId) return@withLock null
                val launch = requestAndroidPermissions ?: return@withLock before
                androidDialogUp.value = true
                try {
                    runCatching { launch(before.flatMap { it.androidPermissions }.distinct()) }
                        .onFailure { Log.w(TAG, "runtime permission request failed", it) }
                } finally {
                    androidDialogUp.value = false
                }
                permissions.filterNot(::held)
            }
        }
        if (missing.isEmpty()) return true
        if (live()) onAndroidPermissionMissing?.invoke(missing)
        return false
    }

    private fun withdraw(tabId: Long, match: (Pending) -> Boolean) {
        for (p in pending.toList()) {
            if (p.tabId != tabId || !match(p)) continue
            p.withdrawn.value = true
            p.prompt?.respond(PromptAnswer.Withdrawn)
        }
    }

    companion object {
        private const val TAG = "SitePermissions"

        @Volatile
        private var instance: SitePermissionBroker? = null

        fun get(context: Context): SitePermissionBroker =
            instance ?: synchronized(this) {
                instance ?: SitePermissionBroker(
                    context.applicationContext,
                    SitePermissionStore.get(context),
                ).also { instance = it }
            }
    }
}
