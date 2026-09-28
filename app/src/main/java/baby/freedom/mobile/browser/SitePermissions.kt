package baby.freedom.mobile.browser

import android.Manifest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Site permissions (#81): which powerful web capabilities a site may
 * use, decided per origin by the user through a prompt, optionally
 * remembered, and revocable from Settings.
 *
 * This file is the pure half — the vocabulary, origin keying, and the
 * decision rules — so it can be unit-tested without a WebView. The
 * WebView/Activity glue lives in [SitePermissionBroker]; persistence in
 * [baby.freedom.mobile.data.SitePermissionStore].
 *
 * Mirrors the desktop browser's model (freedom-browser
 * `src/main/permissions/`):
 *
 *   stored decision (remembered)       → applied silently
 *   session decision (this run)        → applied silently
 *   embargoed (3 dismissals, this run) → denied silently
 *   anything else                      → prompt
 *
 * Dismissing a prompt (back / tap outside) is a deny-once that records
 * nothing, so the site may ask again — but three dismissals of the same
 * origin + permission in one run embargo the pair, exactly like
 * Chromium and the desktop browser. Allow or Block resets the count.
 *
 * Web notifications are deliberately absent: Android System WebView does
 * not implement the Notifications API at all (`window.Notification` is
 * undefined), so there is no request a site can make and nothing to
 * prompt for.
 *
 * What a site can be allowed is a [SiteCapability]: one of the device
 * capabilities below, or handing one kind of link to another app
 * ([ExternalScheme], #85). Both go through the same prompt, tiers and
 * embargo, and are listed and revoked together in Settings.
 */
sealed interface SiteCapability {
    /** Storage key; shared with the desktop browser's `permissions.json`. */
    val key: String

    /** Noun for lists ("Camera"). */
    val label: String

    /** Verb phrase for the prompt ("example.com wants to …"). */
    val phrase: String

    /**
     * Android runtime permissions backing the capability. The site is
     * granted once *any* of them is held (location: approximate is
     * enough; the user chooses precision in the system dialog). Empty
     * when the app needs nothing from Android for it.
     */
    val androidPermissions: List<String>

    companion object {
        fun forKey(key: String): SiteCapability? =
            SitePermission.forKey(key) ?: ExternalScheme.forKey(key)
    }
}

enum class SitePermission(
    override val key: String,
    override val label: String,
    override val phrase: String,
    override val androidPermissions: List<String>,
) : SiteCapability {
    CAMERA("camera", "Camera", "use your camera", listOf(Manifest.permission.CAMERA)),
    MICROPHONE("microphone", "Microphone", "use your microphone", listOf(Manifest.permission.RECORD_AUDIO)),
    LOCATION(
        "geolocation",
        "Location",
        "know your location",
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
    ),
    ;

    companion object {
        fun forKey(key: String): SitePermission? = entries.firstOrNull { it.key == key }
    }
}

enum class PermissionDecision(val stored: String) {
    ALLOW("allow"),
    DENY("deny"),
    ;

    companion object {
        fun fromStored(s: String?): PermissionDecision? = entries.firstOrNull { it.stored == s }
    }
}

/**
 * The permission-store key for a requesting origin: `scheme://host[:port]`,
 * lowercased, default ports dropped. Only http(s) origins can hold a
 * permission — anything else (`file:`, `data:`, opaque `null` origins,
 * `about:blank`) returns `null` and is denied without a prompt.
 *
 * Accepts both shapes WebView hands out: `PermissionRequest.getOrigin()`
 * (`https://meet.jit.si/`) and the geolocation prompt's origin string
 * (`https://example.com/` or without the slash).
 *
 * Dweb pages are keyed by their virtual origin
 * (`https://<label>.bzz.freedom.baby`), which is 1:1 with the content
 * root ([VirtualOrigin]) — so an ENS site keeps its permissions across
 * contenthash updates, and two Swarm roots never share one.
 */
fun permissionOriginKey(raw: String?): String? {
    val s = raw?.trim().orEmpty()
    val schemeEnd = s.indexOf("://")
    if (schemeEnd <= 0) return null
    val scheme = s.substring(0, schemeEnd).lowercase()
    if (scheme != "http" && scheme != "https") return null
    val rest = s.substring(schemeEnd + 3)
    val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
    var authority = (if (end >= 0) rest.substring(0, end) else rest).lowercase()
    // Userinfo never belongs in an origin.
    authority = authority.substringAfterLast('@')
    if (authority.isEmpty()) return null
    val defaultPort = if (scheme == "https") ":443" else ":80"
    if (authority.endsWith(defaultPort)) authority = authority.removeSuffix(defaultPort)
    if (authority.isEmpty() || authority.startsWith(":")) return null
    return "$scheme://$authority"
}

/**
 * How an origin key reads to the user: `bzz://<ref>` / `ipfs://<cid>` /
 * `name.eth` for virtual dweb origins (the same form the address bar
 * shows), the bare host for https, and the full origin for plain http
 * so an insecure site is never mistaken for its https twin.
 */
fun permissionOriginDisplay(originKey: String): String {
    VirtualOrigin.displayUrlFor(originKey)?.let { return it }
    return originKey.removePrefix("https://")
}

/**
 * "use your camera and microphone" — the prompt sentence's tail for
 * [permissions]. Camera + microphone collapse into one phrase the way
 * the desktop prompt does.
 */
fun describePermissionRequest(permissions: Collection<SiteCapability>): String {
    val unique = permissions.distinct()
    val phrases = mutableListOf<String>()
    val av = SitePermission.CAMERA in unique && SitePermission.MICROPHONE in unique
    if (av) phrases += "use your camera and microphone"
    for (p in unique) {
        if (av && (p == SitePermission.CAMERA || p == SitePermission.MICROPHONE)) continue
        phrases += p.phrase
    }
    return phrases.joinToString(" and ").ifEmpty { "use a device" }
}

/** What the user did with a prompt. */
sealed interface PromptAnswer {
    data class Allow(val remember: Boolean) : PromptAnswer
    data class Block(val remember: Boolean) : PromptAnswer

    /** Back / tap outside: deny once, count towards the embargo. */
    data object Dismiss : PromptAnswer

    /**
     * The prompt went away without the user answering it — the page
     * navigated, the request was cancelled by WebView, the tab closed.
     * Deny once, count nothing.
     */
    data object Withdrawn : PromptAnswer

    /**
     * Another tab answered the same question for the same origin while
     * this prompt was up ([awaitPromptSuperseded]): take it down and
     * re-plan, without asking the user the same thing twice.
     */
    data object Superseded : PromptAnswer
}

/** How a request should be handled before any prompt. */
sealed interface PermissionPlan {
    /** Every permission is allowed; go to the Android runtime-permission step. */
    data object Grant : PermissionPlan

    /** At least one permission is blocked (stored, session, or embargo). */
    data object Deny : PermissionPlan

    /** Ask the user about [undecided]; the rest are already allowed. */
    data class Ask(val undecided: List<SiteCapability>) : PermissionPlan
}

/**
 * Decide [permissions] for [origin] from its remembered decisions
 * ([stored]) and this run's [session]. A single block anywhere denies
 * the whole request: a `getUserMedia({audio, video})` with one half
 * blocked fails in the page regardless, and prompting for the other
 * half first would only be noise.
 */
fun planFor(
    origin: String,
    permissions: List<SiteCapability>,
    stored: Map<SiteCapability, PermissionDecision>,
    session: PermissionSession,
): PermissionPlan {
    val undecided = mutableListOf<SiteCapability>()
    for (p in permissions.distinct()) {
        when (stored[p] ?: session.decisionFor(origin, p)) {
            PermissionDecision.DENY -> return PermissionPlan.Deny
            PermissionDecision.ALLOW -> Unit
            null -> undecided += p
        }
    }
    return if (undecided.isEmpty()) PermissionPlan.Grant else PermissionPlan.Ask(undecided)
}

/**
 * One remembered or this-run decision, as listed in Settings.
 */
data class SitePermissionEntry(
    val origin: String,
    val permission: SiteCapability,
    val decision: PermissionDecision,
    /** False for a decision that lives only until the app process ends. */
    val remembered: Boolean,
    /** Blocked by the three-dismissals rule rather than by the user. */
    val embargoed: Boolean = false,
)

/**
 * This run's unremembered decisions and dismissal counts. Never
 * persisted — a process restart forgets it, like the desktop browser's
 * session tier. [entries] is Compose-observable through [version] so
 * Settings can list and revoke session decisions too (an embargo the
 * user can't see or lift would be a dead end).
 *
 * With [embargoes] false, dismissals count for nothing: each one is a
 * deny-once and the site may ask again. That's the private tabs' tier
 * (#86), which Settings doesn't list — an embargo there could be
 * neither seen nor lifted.
 */
class PermissionSession(private val embargoes: Boolean = true) {
    private data class Key(val origin: String, val permission: SiteCapability)

    private val decisions = LinkedHashMap<Key, PermissionDecision>()
    private val embargoed = HashSet<Key>()
    private val dismissals = HashMap<Key, Int>()

    /** Bumped on every change; observe it to re-read [entries]. */
    val version = kotlinx.coroutines.flow.MutableStateFlow(0)

    @Synchronized
    fun decisionFor(origin: String, permission: SiteCapability): PermissionDecision? =
        decisions[Key(origin, permission)]

    /** Record an Allow/Block answered without "remember" (or alongside a remembered one). */
    @Synchronized
    fun record(origin: String, permission: SiteCapability, decision: PermissionDecision, remembered: Boolean) {
        val k = Key(origin, permission)
        dismissals.remove(k)
        embargoed.remove(k)
        if (remembered) decisions.remove(k) else decisions[k] = decision
        version.value++
    }

    /**
     * Count a dismissal. Returns true when this one reached the
     * embargo threshold and the pair is now blocked for the run.
     */
    @Synchronized
    fun dismiss(origin: String, permission: SiteCapability): Boolean {
        if (!embargoes) return false
        val k = Key(origin, permission)
        val n = (dismissals[k] ?: 0) + 1
        dismissals[k] = n
        if (n >= DISMISS_EMBARGO_THRESHOLD) {
            dismissals.remove(k)
            decisions[k] = PermissionDecision.DENY
            embargoed += k
            version.value++
            return true
        }
        return false
    }

    /** Forget everything this run knows about the pair (decision, embargo, count). */
    @Synchronized
    fun revoke(origin: String, permission: SiteCapability) {
        val k = Key(origin, permission)
        decisions.remove(k)
        embargoed.remove(k)
        dismissals.remove(k)
        version.value++
    }

    @Synchronized
    fun entries(): List<SitePermissionEntry> = decisions.map { (k, d) ->
        SitePermissionEntry(k.origin, k.permission, d, remembered = false, embargoed = k in embargoed)
    }

    companion object {
        /** Chromium's (and the desktop browser's) dismissal embargo. */
        const val DISMISS_EMBARGO_THRESHOLD = 3
    }
}

/**
 * Suspends until a prompt asking about [undecided] for [origin] no
 * longer asks the right question — some of it was decided elsewhere
 * (another tab's prompt for the same origin answered, or an embargo
 * reached) — and returns. Re-checks on every [PermissionSession]
 * change, against the remembered decisions too ([stored]): an
 * "Allow + remember" moves from the session tier into the store, and a
 * re-check that only runs after that move must still see it.
 */
suspend fun awaitPromptSuperseded(
    origin: String,
    undecided: List<SiteCapability>,
    session: PermissionSession,
    stored: suspend () -> Map<SiteCapability, PermissionDecision>,
) {
    val asked = PermissionPlan.Ask(undecided)
    session.version.first { planFor(origin, undecided, stored(), session) != asked }
}

/**
 * Suspends until tab [tabId]'s page is the one on screen ([onScreenTab])
 * and returns `true`, or returns `false` as soon as the request is
 * [withdrawn] — whichever comes first. Gates Android's runtime-permission
 * dialog the way `BrowserScreen` gates the Freedom prompt.
 */
suspend fun awaitTabOnScreen(
    onScreenTab: StateFlow<Long?>,
    tabId: Long,
    withdrawn: StateFlow<Boolean>,
): Boolean = combine(onScreenTab, withdrawn) { shown, gone ->
    when {
        gone -> false
        shown == tabId -> true
        else -> null
    }
}.filterNotNull().first()

/** How [askAndroidPermissionOnScreen] ended. */
enum class AndroidPermissionAsk {
    /** The app holds the permission (already, or the user just allowed it). */
    GRANTED,

    /** Android refused it, or there was no way to ask. */
    REFUSED,

    /** The tab's page wasn't on screen once the dialog's turn came; nothing was shown. */
    OFF_SCREEN,
}

/**
 * Ask Android for an app permission on behalf of tab [tabId] *right now*
 * — no waiting for the tab to come back, unlike a site's request: the
 * caller is answering a tap (an upload's `capture` input) that is only
 * still meaningful while its page is on screen.
 *
 * Shares the site-permission path's rules for Android's dialog: one at
 * a time ([lock]; a request arriving while another dialog is up waits
 * its turn instead of being refused at once by Android), only over the
 * page that asked ([onScreenTab], which is `null` while a full-screen
 * panel covers the page or the app isn't resumed), with [dialogUp]
 * raised while it shows. [launch] is the bridge's launcher (which also
 * records a refusal so a later permanent one can be recognised); `null`
 * means no screen is composed to ask with.
 */
suspend fun askAndroidPermissionOnScreen(
    lock: Mutex,
    onScreenTab: StateFlow<Long?>,
    tabId: Long,
    dialogUp: MutableStateFlow<Boolean>,
    held: () -> Boolean,
    launch: (suspend () -> Unit)?,
): AndroidPermissionAsk = lock.withLock {
    if (onScreenTab.value != tabId) return@withLock AndroidPermissionAsk.OFF_SCREEN
    if (held()) return@withLock AndroidPermissionAsk.GRANTED
    if (launch == null) return@withLock AndroidPermissionAsk.REFUSED
    dialogUp.value = true
    try {
        runCatching { launch() }
    } finally {
        dialogUp.value = false
    }
    if (held()) AndroidPermissionAsk.GRANTED else AndroidPermissionAsk.REFUSED
}

/** Which modal prompt the on-screen tab shows now; see [modalPromptTurn]. */
enum class PromptTurn { None, SitePermission, DownloadOffer, Radicle }

/**
 * Orders the prompts a page can raise on its tab: the site-permission
 * prompt (#81), the download offer (#79) and the `window.radicle`
 * consent prompt (#124, [radicleWaiting]). All are modal and all are
 * the page's doing, so they never stack — one waits while the other is
 * answered, then gets its turn:
 *
 * - Whichever is already on screen keeps it ([offerHasTurn] for the
 *   offer; a waiting permission prompt is only ever un-shown by an offer
 *   that already had the turn), so a prompt never vanishes from under
 *   the user's finger for the other.
 * - When both are waiting and neither is up yet (a page asking for
 *   location and starting a download in the same task), the permission
 *   prompt goes first: it answers a live request the page is awaiting
 *   and that WebView can withdraw, while an offer waits in its queue
 *   indefinitely.
 * - Neither shows while Android's own permission dialog is up
 *   ([androidDialogUp]); the offer comes after it. The broker in turn
 *   holds that dialog while the offer has the turn, since
 *   `BrowserScreen` only reports the tab on screen when the offer
 *   doesn't.
 *
 * - The Radicle prompt keeps the turn once it has it ([radicleHasTurn])
 *   like the offer does; waiting, it comes after the permission prompt
 *   and before the offer.
 *
 * [permissionWaiting] and [radicleWaiting] are already gated on the page
 * being on screen (no full-screen panel over it, the Downloads list
 * included); the offer keeps its own rules (see `BrowserScreen`).
 */
fun modalPromptTurn(
    permissionWaiting: Boolean,
    offerWaiting: Boolean,
    offerHasTurn: Boolean,
    androidDialogUp: Boolean,
    radicleWaiting: Boolean = false,
    radicleHasTurn: Boolean = false,
): PromptTurn = when {
    androidDialogUp -> PromptTurn.None
    offerWaiting && offerHasTurn -> PromptTurn.DownloadOffer
    radicleWaiting && radicleHasTurn -> PromptTurn.Radicle
    permissionWaiting -> PromptTurn.SitePermission
    radicleWaiting -> PromptTurn.Radicle
    offerWaiting -> PromptTurn.DownloadOffer
    else -> PromptTurn.None
}

/**
 * Tap protection for the permission prompt. A page chooses *when* its
 * prompt appears (it calls `getUserMedia()` / `getCurrentPosition()`),
 * so it can ask the user to double-tap where Allow is about to render
 * and have the second tap land on the prompt. Like Chrome's permission
 * dialogs, the prompt ignores its buttons until it has been on screen
 * for [PROTECTION_MS]: long enough that a tap aimed at the page before
 * it appeared can't reach it, short enough that nobody reading it
 * notices.
 *
 * [clock] is a monotonic millisecond clock (`SystemClock.uptimeMillis`).
 */
class PromptTapGuard(private val clock: () -> Long) {
    private var shownAt: Long? = null

    /** The prompt's first frame is on screen; start the protection period. */
    fun onShown() {
        if (shownAt == null) shownAt = clock()
    }

    /** Whether a button press now is a deliberate answer. */
    fun accepts(): Boolean {
        val shown = shownAt ?: return false
        return clock() - shown >= PROTECTION_MS
    }

    /** Milliseconds until [accepts] turns true (0 once it has). */
    fun remainingMs(): Long {
        val shown = shownAt ?: return PROTECTION_MS
        return (PROTECTION_MS - (clock() - shown)).coerceAtLeast(0)
    }

    companion object {
        const val PROTECTION_MS = 500L
    }
}

/**
 * Whether Android will refuse [permission] without showing its dialog,
 * so the only way left is the app's system settings — the one case the
 * "Turn it on in Android settings" snackbar is for.
 *
 * Android has no direct query for this. After a refusal,
 * `shouldShowRequestPermissionRationale` is true when the user denied
 * once and a new request would ask again; it is false both when the
 * user has denied for good *and* when the dialog was merely backed out
 * of before ever being answered. The two false cases are told apart by
 * [deniedBefore]: a permanent denial is always preceded by a denial
 * that set the rationale flag (Android 11+ needs two denials; older
 * versions only offer "Don't ask again" on the second request).
 */
fun androidPermissionBlockedInSettings(rationale: Boolean, deniedBefore: Boolean): Boolean =
    !rationale && deniedBefore
