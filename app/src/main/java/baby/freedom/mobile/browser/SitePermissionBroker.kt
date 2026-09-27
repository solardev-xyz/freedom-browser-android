package baby.freedom.mobile.browser

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import androidx.core.content.ContextCompat
import baby.freedom.mobile.data.SitePermissionStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.Flow
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
 *     via [requestAndroidPermissions]. Granted to the page only if the
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

    private val androidDialogLock = Mutex()

    /** A request in flight, from arrival until it's granted or denied. */
    private class Pending(val tabId: Long, val token: Any) {
        var withdrawn = false
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

    /** The tab is gone. */
    fun onTabClosed(tabId: Long) {
        documents[tabId] = (documents[tabId] ?: 0) + 1
        withdraw(tabId) { true }
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
        fun live() = !entry.withdrawn && (documents[tab.id] ?: 0) == doc
        fun finish(allowed: Boolean) {
            pending.remove(entry)
            runCatching { if (allowed && live()) grant() else deny() }
                .onFailure { Log.w(TAG, "answering permission request failed", it) }
        }
        scope.launch {
            val lock = tabLocks.getOrPut(tab.id) { Mutex() }
            val siteAllowed = lock.withLock {
                if (!live()) return@withLock false
                val stored = store.decisionsFor(origin).mapNotNull { (k, v) ->
                    val p = SitePermission.forKey(k) ?: return@mapNotNull null
                    val d = PermissionDecision.fromStored(v) ?: return@mapNotNull null
                    p to d
                }.toMap()
                if (!live()) return@withLock false
                when (val plan = planFor(origin, permissions, stored, session)) {
                    PermissionPlan.Deny -> false
                    PermissionPlan.Grant -> true
                    is PermissionPlan.Ask -> ask(tab, entry, origin, plan.undecided)
                }
            }
            if (!siteAllowed || !live()) return@launch finish(false)
            finish(ensureAndroidPermissions(permissions))
        }
    }

    /** Show the prompt, record the answer, and say whether the site is now allowed. */
    private suspend fun ask(
        tab: BrowserState,
        entry: Pending,
        origin: String,
        undecided: List<SitePermission>,
    ): Boolean {
        val prompt = PermissionPrompt(origin, undecided)
        entry.prompt = prompt
        tab.permissionPrompt = prompt
        val answer = try {
            prompt.answer.await()
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
        }
    }

    private suspend fun record(
        origin: String,
        permissions: List<SitePermission>,
        decision: PermissionDecision,
        remember: Boolean,
    ) {
        for (p in permissions) {
            session.record(origin, p, decision, remembered = remember)
            if (remember) store.set(origin, p.key, decision.stored)
        }
    }

    /**
     * The site is allowed; make sure the app is too. Asks Android only
     * for what's missing, one system dialog at a time.
     */
    private suspend fun ensureAndroidPermissions(permissions: List<SitePermission>): Boolean {
        fun held(p: SitePermission) = p.androidPermissions.any {
            ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED
        }
        if (permissions.all(::held)) return true
        val missing = androidDialogLock.withLock {
            val before = permissions.filterNot(::held)
            if (before.isEmpty()) return@withLock before
            val launch = requestAndroidPermissions ?: return@withLock before
            runCatching { launch(before.flatMap { it.androidPermissions }.distinct()) }
                .onFailure { Log.w(TAG, "runtime permission request failed", it) }
            permissions.filterNot(::held)
        }
        if (missing.isEmpty()) return true
        onAndroidPermissionMissing?.invoke(missing)
        return false
    }

    private fun withdraw(tabId: Long, match: (Pending) -> Boolean) {
        for (p in pending.toList()) {
            if (p.tabId != tabId || !match(p)) continue
            p.withdrawn = true
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
