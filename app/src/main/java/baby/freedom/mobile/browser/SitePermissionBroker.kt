package baby.freedom.mobile.browser

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.util.Log
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import androidx.core.content.ContextCompat
import baby.freedom.mobile.data.SitePermissionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What [store] remembers for exactly [scope] (#363): a frame's request
 * reads its (origin, top-level site) pair only — never the grant its
 * origin holds as a site of its own, nor one made inside another site.
 */
internal suspend fun rememberedDecisions(
    store: SitePermissionStore,
    scope: PermissionScope,
): Map<SiteCapability, PermissionDecision> =
    store.decisionsFor(scope.origin, scope.top).mapNotNull { (k, v) ->
        val p = SiteCapability.forKey(k) ?: return@mapNotNull null
        val d = PermissionDecision.fromStored(v) ?: return@mapNotNull null
        p to d
    }.toMap()

/**
 * A prompt waiting for the user: "[scope]'s top-level site wants to
 * [permissions]" — and, for a frame from another origin (#363), "… via
 * [scope]'s origin". Always in the name of the site the user is on: a
 * frame's answer is for that site alone. Shown by [BrowserScreen] while
 * it is its tab's [BrowserState.permissionPrompt] and that tab is
 * active; answered by completing [answer].
 */
class PermissionPrompt internal constructor(
    val scope: PermissionScope,
    val permissions: List<SiteCapability>,
    /**
     * Asked from a private tab (#86): the answer lasts for the private
     * session only, so the prompt offers no "remember".
     */
    val private: Boolean = false,
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
 * Flow of a request (camera/mic/MIDI SysEx via `onPermissionRequest`, location via
 * `onGeolocationPermissionsShowPrompt`, a link to another app via
 * [onExternalLink]):
 *
 *  1. Key it by the requesting origin and the top-level site it asks
 *     from ([permissionScopeFor], #363): a frame from another origin is
 *     decided on that site, never by its origin's own grant. A
 *     non-http(s) origin or page, or a capability we don't prompt for,
 *     is denied — protected media (DRM, #267) with a notice saying why.
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

    /**
     * Private tabs' decisions (#86): their own session tier, kept apart
     * from [session] both ways — a private tab starts from nothing
     * remembered or answered in normal tabs, and what it's told applies
     * to private tabs only, is never written to the store, and isn't
     * listed in Settings. Replaced when the private session ends
     * ([onPrivateSessionEnded]). Since Settings can't show it, it never
     * embargoes: a dismissed prompt is a deny-once, nothing more. A
     * private tab's own Site permissions sheet (#266) lists it, and
     * revokes from it alone ([revokeOnTab]).
     */
    private val privateSessionState = MutableStateFlow(PermissionSession(embargoes = false))
    private val privateSession: PermissionSession get() = privateSessionState.value

    private fun sessionFor(tab: BrowserState): PermissionSession =
        if (tab.private) privateSession else session

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
     * Installed by [BrowserScreen]: the user allowed a link to another
     * app (#85), but no app on the device can open it — say so rather
     * than doing nothing.
     */
    @Volatile
    var onNoAppForLink: ((ExternalScheme) -> Unit)? = null

    /**
     * Installed by [BrowserScreen]: the page on screen asked for protected
     * media (DRM, #267), which was refused — say why its video won't
     * play ([PROTECTED_MEDIA_NOTICE]). Once per site per run.
     */
    @Volatile
    var onProtectedMediaRefused: (() -> Unit)? = null

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
    /** Tabs that are private, so a removal reaches only its own tier's documents ([noteRemoved]). */
    private val privateTabs = HashSet<Long>()
    private val tabLocks = HashMap<Long, Mutex>()

    // ---------------------------------------------------------------
    // WebView callbacks
    // ---------------------------------------------------------------

    /** `WebChromeClient.onPermissionRequest` for [tab]. */
    fun onMediaRequest(tab: BrowserState, request: PermissionRequest) {
        val resources = request.resources.orEmpty()
        val byPermission = resources.mapNotNull { r -> mediaResourcePermission(r)?.let { it to r } }
        val origin = permissionOriginKey(request.origin?.toString())
        if (resources.any(::isProtectedMediaResource)) {
            // Protected media is denied without a prompt (#267) — and so
            // is anything asked for along with it, which WebView doesn't
            // do (each kind comes as its own request).
            request.deny()
            noteProtectedMediaRefused(tab, origin)
            return
        }
        // Read at arrival, on the main thread: the request comes from the
        // committed document ([BrowserState.permissionOrigin] is set at
        // its `onPageStarted`, which also withdraws whatever the outgoing
        // one asked).
        val scope = permissionScopeFor(request.origin?.toString(), tab.permissionOrigin)
        if (byPermission.isEmpty() || scope == null) {
            request.deny()
            return
        }
        handle(
            tab = tab,
            scope = scope,
            permissions = byPermission.map { it.first },
            token = request,
            grant = { request.grant(byPermission.map { it.second }.toTypedArray()) },
            deny = { request.deny() },
        )
    }

    /**
     * Origins already told this run that their protected media was refused
     * ([onProtectedMediaRefused]), per tier: a streaming site asks on every
     * video, and the notice needs saying once. The private tier's is
     * forgotten with the private session ([onPrivateSessionEnded]).
     */
    private val protectedMediaNoticed = HashSet<String>()
    private var privateProtectedMediaNoticed = HashSet<String>()

    private fun noteProtectedMediaRefused(tab: BrowserState, origin: String?) {
        // Only over the page that asked: a background tab's request isn't
        // counted, so the notice comes when its page is on screen and asks.
        if (!isOnScreen(tab.id)) return
        // With no notice installed (between an Activity's teardown and its
        // replacement's), nothing is said, so the site isn't counted as told:
        // its next request, once one is installed, still gets the notice.
        val notice = onProtectedMediaRefused ?: return
        val noticed = if (tab.private) privateProtectedMediaNoticed else protectedMediaNoticed
        // A non-http(s) origin (a `data:` frame, say) is noticed once for all of them.
        if (!noticed.add(origin ?: "")) return
        notice()
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
        val scope = permissionScopeFor(rawOrigin, tab.permissionOrigin)
        if (scope == null) {
            callback.invoke(rawOrigin, false, false)
            return
        }
        // `retain = false` always: remembering is ours to do (and to
        // revoke), not WebView's own GeolocationPermissions store.
        handle(
            tab = tab,
            scope = scope,
            permissions = listOf(SitePermission.LOCATION),
            token = callback,
            grant = { callback.invoke(rawOrigin, true, false) },
            deny = { callback.invoke(rawOrigin, false, false) },
        )
    }

    /**
     * A page in [tab] asked to hand a link to another app (#85), already
     * vetted by [externalLinkVerdict] (blocked schemes, main frame, user
     * gesture). [origin] is the page's permission key — a main-frame
     * link, so the site's own decision; [launch] starts the app, and only
     * runs if the site is allowed [scheme] and the page that asked is
     * still the tab's document.
     */
    fun onExternalLink(tab: BrowserState, origin: String, scheme: ExternalScheme, launch: () -> Unit) {
        handle(
            tab = tab,
            scope = PermissionScope(origin),
            permissions = listOf(scheme),
            token = Any(),
            grant = launch,
            deny = {},
        )
    }

    /** `WebChromeClient.onGeolocationPermissionsHidePrompt`. */
    fun onGeolocationHidden(tab: BrowserState) =
        withdraw(tab.id) { it.token is GeolocationPermissions.Callback }

    /** The tab started a new document: nothing asked by the old one may land. */
    fun onDocumentStarted(tab: BrowserState) {
        val doc = (documents[tab.id] ?: 0) + 1
        documents[tab.id] = doc
        if (tab.private) privateTabs += tab.id else privateTabs -= tab.id
        withdraw(tab.id) { true }
        // The new document has asked for nothing and been given nothing.
        documentActivity.update { it + (tab.id to DocumentPermissions(doc)) }
    }

    /**
     * The tab is gone. Its requests are withdrawn (which alone keeps
     * them from landing), and its bookkeeping dropped so a long session
     * doesn't accumulate an entry per tab ever opened.
     */
    fun onTabClosed(tabId: Long) {
        withdraw(tabId) { true }
        documents.remove(tabId)
        privateTabs.remove(tabId)
        tabLocks.remove(tabId)
        documentActivity.update { it - tabId }
    }

    // ---------------------------------------------------------------
    // The page's own view (#266)
    // ---------------------------------------------------------------

    /**
     * What a tab's current document has to do with site permissions:
     * the [PermissionScope]s that asked for something from it ([scopes]:
     * its own, or an embedded frame's on this site), what each of them was
     * granted ([grants]: camera, microphone, location, MIDI SysEx — per
     * scope, since a frame's grant isn't the page's), and which of those
     * the user has since removed while the document still holds them
     * ([revokedHeld], per scope too) — from any tab's sheet or Settings, not only this tab's own
     * sheet ([noteRemoved]) — until the site is allowed them again, from
     * any tab of the tier ([noteAllowedAgain]). WebView can't take a
     * grant back from a live document — a camera stream runs on, a
     * location grant keeps answering the document's watches and new
     * requests without asking, and a `MIDIAccess` the page was given
     * keeps working ([stillHeldAfterRemoval]) — so the sheet keeps saying so, and
     * offering a reload, however often it's closed and reopened over
     * this document.
     * [doc] is the tab's document number ([documents]), so a sheet opened
     * over one document can tell when another has replaced it.
     */
    data class DocumentPermissions(
        val doc: Int,
        val scopes: Set<PermissionScope> = emptySet(),
        val grants: Map<PermissionScope, Set<SitePermission>> = emptyMap(),
        val revokedHeld: Map<PermissionScope, Set<SitePermission>> = emptyMap(),
    ) {
        /** Everything granted to this document, whichever of its scopes got it. */
        val granted: Set<SitePermission> get() = grants.values.flatten().toSet()

        /** What [scope] was granted in this document. */
        fun grantedTo(scope: PermissionScope): Set<SitePermission> = grants[scope].orEmpty()

        /** After [scope] gets [more] (again) in this document: no longer revoked. */
        fun granting(scope: PermissionScope, more: Collection<SitePermission>): DocumentPermissions =
            allowedAgain(scope, more).copy(
                scopes = scopes + scope,
                grants = if (more.isEmpty()) grants else grants + (scope to grantedTo(scope) + more),
            )

        /**
         * After [scope] is allowed [again] — from this document or any
         * other of its tier: what this document still holds of it is no
         * longer "removed" but allowed, so there's nothing a reload would
         * take away. Nothing is granted to this document by it.
         */
        fun allowedAgain(scope: PermissionScope, again: Collection<SitePermission>): DocumentPermissions {
            val held = revokedHeld[scope] ?: return this
            val left = held - again.toSet()
            if (left == held) return this
            return copy(revokedHeld = if (left.isEmpty()) revokedHeld - scope else revokedHeld + (scope to left))
        }

        /**
         * After [entry] is removed from the sheet over this document: what
         * its scope was given here is still held. Only that exact pair
         * (#363): removing `meet.example`'s own camera doesn't touch what
         * it was given framed by another site, nor the reverse.
         */
        fun revoking(entry: SitePermissionEntry): DocumentPermissions {
            val p = entry.permission
            val scope = entry.scope
            return if (p is SitePermission && p in grantedTo(scope)) {
                copy(revokedHeld = revokedHeld + (scope to revokedHeld[scope].orEmpty() + p))
            } else {
                this
            }
        }

        /**
         * What was removed but this document still has, given the
         * camera/microphone in use now ([inUse]): see [stillHeldAfterRemoval].
         */
        fun stillHeld(inUse: Set<SitePermission>): Set<SitePermission> =
            stillHeldAfterRemoval(revokedHeld.values.flatten().toSet(), inUse)

        /** Whether [entry]'s own scope was given it here and it's in use now. */
        fun inUse(entry: SitePermissionEntry, inUse: Set<SitePermission>): Boolean =
            entry.decision == PermissionDecision.ALLOW &&
                entry.permission in inUse && entry.permission in grantedTo(entry.scope)
    }

    private val documentActivity = MutableStateFlow<Map<Long, DocumentPermissions>>(emptyMap())

    /** Tab [tabId]'s current document's [DocumentPermissions]. */
    fun documentPermissions(tabId: Long): Flow<DocumentPermissions> =
        documentActivity.map { it[tabId] ?: DocumentPermissions(documents[tabId] ?: 0) }

    private fun noteDocument(tabId: Long, doc: Int, scope: PermissionScope, granted: Collection<SitePermission> = emptyList()) {
        if ((documents[tabId] ?: 0) != doc) return
        documentActivity.update { all ->
            val cur = all[tabId]?.takeIf { it.doc == doc } ?: DocumentPermissions(doc)
            all + (tabId to cur.granting(scope, granted))
        }
    }

    /**
     * The camera and microphone the app is using right now — Android's
     * app-op "active" state for this app's uid, the same signal behind
     * the system's own privacy indicator. Counted per op, since more than
     * one attribution can hold one at a time.
     */
    private val activeMediaCounts = HashMap<SitePermission, Int>()
    private val _activeMedia = MutableStateFlow<Set<SitePermission>>(emptySet())
    val activeMedia: StateFlow<Set<SitePermission>> = _activeMedia.asStateFlow()

    init {
        // Our own uid needs no permission to watch; a device where it
        // can't be watched simply never shows the indicator.
        runCatching {
            appContext.getSystemService(AppOpsManager::class.java)?.startWatchingActive(
                arrayOf(AppOpsManager.OPSTR_CAMERA, AppOpsManager.OPSTR_RECORD_AUDIO),
                ContextCompat.getMainExecutor(appContext),
            ) { op, uid, _, active ->
                if (uid != Process.myUid()) return@startWatchingActive
                val p = when (op) {
                    AppOpsManager.OPSTR_CAMERA -> SitePermission.CAMERA
                    AppOpsManager.OPSTR_RECORD_AUDIO -> SitePermission.MICROPHONE
                    else -> return@startWatchingActive
                }
                val n = ((activeMediaCounts[p] ?: 0) + if (active) 1 else -1).coerceAtLeast(0)
                activeMediaCounts[p] = n
                _activeMedia.update { if (n > 0) it + p else it - p }
            }
        }.onFailure { Log.w(TAG, "can't watch camera/microphone use", it) }
    }

    /**
     * The camera/microphone tab [tabId]'s document was given and the app
     * is using now ([mediaInUse]): the page's "in use" indicator.
     */
    fun mediaInUse(tabId: Long): Flow<Set<SitePermission>> =
        combine(documentPermissions(tabId), activeMedia) { d, active -> mediaInUse(d.granted, active) }

    /**
     * The decisions the Site permissions sheet lists for [tab]'s page
     * ([pageSitePermissionEntries]) — [pageOrigin]'s own and those of
     * frames embedded in it (#363) — as that tab sees them: a
     * normal tab's remembered and this-run decisions, or a private tab's
     * private-session ones only.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun pageEntries(tab: BrowserState, pageOrigin: String?): Flow<List<SitePermissionEntry>> {
        val decisions: Flow<List<SitePermissionEntry>> = if (tab.private) {
            privateSessionState.flatMapLatest { s -> s.version.map { s.entries() } }
        } else {
            entries
        }
        return decisions.map { all -> pageSitePermissionEntries(pageOrigin, sitePermissionListOrder(all)) }
    }

    /**
     * Remove [entry] from [tab]'s Site permissions sheet: from a normal
     * tab, everywhere ([revoke]); from a private tab, from the private
     * session only — the only tier a private tab's decisions are in.
     */
    fun revokeOnTab(tab: BrowserState, entry: SitePermissionEntry) {
        if (tab.private) {
            privateSession.revoke(entry.scope, entry.permission)
            noteRemoved(entry, private = true)
        } else {
            revoke(entry)
        }
    }

    /**
     * [entry] was removed from the [private] tier: every open document of
     * that tier its scope was given it in still holds it — wherever the
     * × was tapped (this tab's sheet, another tab's, Settings) — so each
     * of their sheets and menu rows says so and offers Reload.
     */
    private fun noteRemoved(entry: SitePermissionEntry, private: Boolean) {
        documentActivity.update { all ->
            revokingInDocuments(all, entry) { tabId ->
                (tabId in privateTabs) == private && all[tabId]?.doc == (documents[tabId] ?: 0)
            }
        }
    }

    /**
     * [scope] was allowed [permissions] again in the [private] tier: no
     * open document of that tier still holds them "removed" — the
     * counterpart of [noteRemoved], so a re-grant from one tab clears the
     * "kept until reload" note in every other.
     */
    private fun noteAllowedAgain(scope: PermissionScope, permissions: Collection<SitePermission>, private: Boolean) {
        if (permissions.isEmpty()) return
        documentActivity.update { all ->
            allowingAgainInDocuments(all, scope, permissions) { tabId -> (tabId in privateTabs) == private }
        }
    }

    // ---------------------------------------------------------------
    // Pop-ups (#261)
    // ---------------------------------------------------------------

    /**
     * Origins with a remembered pop-ups allow, mirrored from the store so
     * [popupsAllowed] can answer synchronously inside `onCreateWindow`.
     * Empty until the store's first read lands, and while it can't be
     * read: a pop-up is then blocked with a notice, from which the user
     * can still open it.
     */
    @Volatile
    private var storedPopupAllows: Set<String> = emptySet()

    init {
        scope.launch {
            store.all.collect { records ->
                storedPopupAllows = records
                    .filter {
                        // Pop-ups are the page's own (a frame's window
                        // counts as its page's), so only a site's own
                        // decision applies.
                        it.top == it.origin &&
                            it.permission == SitePermission.POPUPS.key &&
                            PermissionDecision.fromStored(it.decision) == PermissionDecision.ALLOW
                    }
                    .mapTo(HashSet()) { it.origin }
            }
        }
    }

    /**
     * Whether [origin]'s pages in [tab] may open pop-ups without a user
     * gesture: the user allowed it — remembered, or this run; in a
     * private tab, this private session only (nothing remembered counts
     * there, as for every other permission).
     */
    fun popupsAllowed(tab: BrowserState, origin: String?): Boolean {
        origin ?: return false
        val scope = PermissionScope(origin)
        return when (sessionFor(tab).decisionFor(scope, SitePermission.POPUPS)) {
            PermissionDecision.ALLOW -> true
            PermissionDecision.DENY -> false
            null -> !tab.private && origin in storedPopupAllows &&
                !session.beingRemovedFromStore(scope, SitePermission.POPUPS)
        }
    }

    /**
     * "Always allow pop-ups on this site" from [tab]'s blocked-pop-up
     * notice: remembered, and listed in Settings; from a private tab,
     * for the private session only. Applies at once — the session tier
     * holds it while the store write is in flight.
     */
    fun allowPopups(tab: BrowserState, origin: String) {
        val tier = sessionFor(tab)
        val site = PermissionScope(origin)
        tier.record(site, SitePermission.POPUPS, PermissionDecision.ALLOW, remembered = false)
        if (tab.private) return
        scope.launch {
            // A failed write leaves it a session decision, which Settings
            // shows as such — the truth.
            if (!store.set(origin, SitePermission.POPUPS.key, PermissionDecision.ALLOW.stored)) return@launch
            if (tier.decisionFor(site, SitePermission.POPUPS) == PermissionDecision.ALLOW) {
                storedPopupAllows = storedPopupAllows + origin
                tier.record(site, SitePermission.POPUPS, PermissionDecision.ALLOW, remembered = true)
            }
        }
    }

    /** The last private tab has closed (#86): forget its answers. */
    fun onPrivateSessionEnded() {
        privateSessionState.value = PermissionSession(embargoes = false)
        privateProtectedMediaNoticed = HashSet()
    }

    // ---------------------------------------------------------------
    // Settings
    // ---------------------------------------------------------------

    /**
     * Every decision the user can revoke, remembered and this run's
     * session-only ones (including dismissal embargoes), grouped by the
     * top-level site they were made on ([sitePermissionListOrder]): a
     * frame's decision on a site is listed under that site (#363).
     */
    val entries: Flow<List<SitePermissionEntry>> =
        combine(store.all, session.version) { records, _ ->
            val stored = records.mapNotNull { r ->
                val p = SiteCapability.forKey(r.permission) ?: return@mapNotNull null
                val d = PermissionDecision.fromStored(r.decision) ?: return@mapNotNull null
                SitePermissionEntry(r.origin, p, d, remembered = true, top = r.top)
            }
            val storedKeys = stored.map { it.scope to it.permission }.toSet()
            sitePermissionListOrder(stored + session.entries().filter { (it.scope to it.permission) !in storedKeys })
        }

    /** Forget [entry] everywhere, so the site has to ask again. */
    fun revoke(entry: SitePermissionEntry) {
        val scope = entry.scope
        session.revoke(scope, entry.permission)
        // Off at once, not only once the store's next read lands.
        if (entry.permission == SitePermission.POPUPS && !scope.embedded) {
            storedPopupAllows = storedPopupAllows - entry.origin
        }
        noteRemoved(entry, private = false)
        // Until the store write lands, what it's removing is hidden from
        // every read of the store: a request arriving meanwhile would
        // otherwise read the old Allow after the removal count was
        // bumped, and be granted what the user just removed.
        session.removingFromStore(scope, entry.permission)
        this.scope.launch {
            try {
                store.remove(entry.origin, entry.permission.key, entry.top)
            } finally {
                session.removedFromStore(scope, entry.permission)
            }
        }
    }

    // ---------------------------------------------------------------
    // The flow
    // ---------------------------------------------------------------

    private fun handle(
        tab: BrowserState,
        scope: PermissionScope,
        permissions: List<SiteCapability>,
        token: Any,
        grant: () -> Unit,
        deny: () -> Unit,
    ) {
        val doc = documents[tab.id] ?: 0
        val entry = Pending(tab.id, token)
        pending += entry
        // Listed on the page's Site permissions sheet (#266) from now on,
        // whatever the answer: a frame that asks is part of the page.
        noteDocument(tab.id, doc, scope)
        fun live() = !entry.withdrawn.value && (documents[tab.id] ?: 0) == doc
        var finished = false
        fun finish(allowed: Boolean) {
            if (finished) return
            finished = true
            pending.remove(entry)
            val granting = allowed && live()
            runCatching { if (granting) grant() else deny() }
                .onFailure { Log.w(TAG, "answering permission request failed", it) }
            if (granting) noteDocument(tab.id, doc, scope, permissions.filterIsInstance<SitePermission>())
        }
        this.scope.launch {
            // Whatever goes wrong below, the page gets an answer (a deny)
            // instead of a request left hanging — and the app doesn't
            // crash on a main-thread exception.
            try {
                decide(tab, entry, scope, permissions, ::live, ::finish)
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
        scope: PermissionScope,
        permissions: List<SiteCapability>,
        live: () -> Boolean,
        finish: (Boolean) -> Unit,
    ) {
        // Withdrawn before it got going (e.g. the tab closed): don't
        // re-create the closed tab's lock.
        if (!live()) return finish(false)
        val lock = tabLocks.getOrPut(tab.id) { Mutex() }
        val tier = sessionFor(tab)
        // Removals of what's asked for, as of the decision below: read
        // before the store is, so one landing while that read (or the
        // prompt, or Android's dialog after it) is in flight is caught.
        var removals = 0
        val siteAllowed = lock.withLock {
            var allowed: Boolean? = null
            while (allowed == null) {
                if (!live()) return@withLock false
                removals = tier.removalCount(scope, permissions)
                val stored = storedDecisionsFor(tab, scope)
                if (!live()) return@withLock false
                allowed = when (val plan = planFor(scope, permissions, stored, tier)) {
                    PermissionPlan.Deny -> false
                    PermissionPlan.Grant -> true
                    // null: settled by another tab's answer meanwhile — re-plan.
                    is PermissionPlan.Ask -> ask(tab, entry, scope, plan.undecided)
                }
            }
            allowed
        }
        // Still this document's request, and nothing it was allowed has
        // been removed since (from Settings or any sheet): the decision
        // above still stands. Checked again once Android's own dialog is
        // done — a request waiting for its tab to come on screen can wait
        // a long time, and a removal made meanwhile must win.
        fun stillAllowed() = live() && tier.removalCount(scope, permissions) == removals
        if (!siteAllowed || !stillAllowed()) return finish(false)
        finish(ensureAndroidPermissions(entry, permissions, ::stillAllowed) && stillAllowed())
    }

    /** What's remembered for [scope], as [tab] sees it: nothing, in a private tab. */
    private suspend fun storedDecisionsFor(tab: BrowserState, scope: PermissionScope): Map<SiteCapability, PermissionDecision> =
        if (tab.private) emptyMap() else session.readWithoutStoreRemovals(scope) { storedDecisions(scope) }

    private suspend fun storedDecisions(scope: PermissionScope): Map<SiteCapability, PermissionDecision> =
        rememberedDecisions(store, scope)

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
        scope: PermissionScope,
        undecided: List<SiteCapability>,
    ): Boolean? {
        val prompt = PermissionPrompt(scope, undecided, private = tab.private)
        val tier = sessionFor(tab)
        entry.prompt = prompt
        tab.permissionPrompt = prompt
        val answer = try {
            coroutineScope {
                val watcher = launch {
                    awaitPromptSuperseded(scope, undecided, tier) { storedDecisionsFor(tab, scope) }
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
                noteAllowedAgain(scope, undecided.filterIsInstance<SitePermission>(), tab.private)
                record(tier, scope, undecided, PermissionDecision.ALLOW, answer.remember && !tab.private)
                true
            }
            is PromptAnswer.Block -> {
                record(tier, scope, undecided, PermissionDecision.DENY, answer.remember && !tab.private)
                false
            }
            PromptAnswer.Dismiss -> {
                for (p in undecided) tier.dismiss(scope, p)
                false
            }
            PromptAnswer.Withdrawn -> false
            PromptAnswer.Superseded -> null
        }
    }

    private suspend fun record(
        tier: PermissionSession,
        scope: PermissionScope,
        permissions: List<SiteCapability>,
        decision: PermissionDecision,
        remember: Boolean,
    ) {
        // The session tier takes the decision at once, so a same-origin
        // request from another tab (whose own lock doesn't wait for this
        // one) sees it while the store write is still in flight; a
        // remembered decision only leaves the session tier once the
        // store holds it.
        for (p in permissions) tier.record(scope, p, decision, remembered = false)
        if (!remember) return
        // A failed write (the store logs it) leaves the decision as a
        // session one: it still applies this run and Settings shows it
        // as "this session", which is the truth.
        val written = permissions.filter { store.set(scope.origin, it.key, decision.stored, scope.top) }
        for (p in written) {
            // Unless the user revoked or re-decided it meanwhile.
            if (tier.decisionFor(scope, p) == decision) {
                tier.record(scope, p, decision, remembered = true)
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
        requested: List<SiteCapability>,
        live: () -> Boolean,
    ): Boolean {
        // Only device capabilities need anything from Android; a link to
        // another app needs nothing.
        val permissions = requested.filterIsInstance<SitePermission>()
        fun held(p: SitePermission) = androidPermissionsHeld(p) {
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

    /**
     * Whether tab [tabId]'s page is what's on screen now ([onScreenTab]):
     * the gate for anything a page's tap opens over it, e.g. an upload's
     * picker or camera ([FileChooser]).
     */
    fun isOnScreen(tabId: Long): Boolean = onScreenTab.value == tabId

    /**
     * An upload's `capture` input needs `CAMERA` before the camera app
     * can start ([FileChooser]). Asked through the same Android dialog
     * path as a site's camera request — [requestAndroidPermissions], one
     * dialog at a time, only over the tab that asked — so a refusal here
     * is recorded like any other and a later permanent one gets the
     * "Turn it on in Android settings" notice. [done] runs on the main
     * thread.
     */
    fun requestUploadCamera(tabId: Long, done: (AndroidPermissionAsk) -> Unit) {
        val permission = android.Manifest.permission.CAMERA
        scope.launch {
            val outcome = try {
                askAndroidPermissionOnScreen(
                    lock = androidDialogLock,
                    onScreenTab = onScreenTab,
                    tabId = tabId,
                    dialogUp = androidDialogUp,
                    held = {
                        ContextCompat.checkSelfPermission(appContext, permission) ==
                            PackageManager.PERMISSION_GRANTED
                    },
                    launch = requestAndroidPermissions?.let { request -> { request(listOf(permission)) } },
                )
            } catch (e: Exception) {
                Log.w(TAG, "camera permission request for upload failed", e)
                AndroidPermissionAsk.REFUSED
            }
            done(outcome)
        }
    }

    /**
     * Tell the user Android has refused the app the camera for good, if
     * it has ([onAndroidPermissionMissing] checks) — an upload's capture
     * input fell back to the picker because of it.
     */
    fun noteUploadCameraRefused() {
        onAndroidPermissionMissing?.invoke(listOf(SitePermission.CAMERA))
    }

    private fun withdraw(tabId: Long, match: (Pending) -> Boolean) {
        for (p in pending.toList()) {
            if (p.tabId != tabId || !match(p)) continue
            p.withdrawn.value = true
            p.prompt?.respond(PromptAnswer.Withdrawn)
        }
    }

    companion object {
        /**
         * [all] after [entry] is removed, for every tab [inScope] — each
         * document whose own grants include it now holds it removed
         * ([DocumentPermissions.revoking]).
         */
        internal fun revokingInDocuments(
            all: Map<Long, DocumentPermissions>,
            entry: SitePermissionEntry,
            inScope: (Long) -> Boolean,
        ): Map<Long, DocumentPermissions> =
            all.mapValues { (tabId, d) -> if (inScope(tabId)) d.revoking(entry) else d }

        /**
         * [all] after [scope] is allowed [permissions] again, for every
         * tab [inScope] ([DocumentPermissions.allowedAgain]).
         */
        internal fun allowingAgainInDocuments(
            all: Map<Long, DocumentPermissions>,
            scope: PermissionScope,
            permissions: Collection<SitePermission>,
            inScope: (Long) -> Boolean,
        ): Map<Long, DocumentPermissions> =
            all.mapValues { (tabId, d) -> if (inScope(tabId)) d.allowedAgain(scope, permissions) else d }

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
