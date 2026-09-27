package baby.freedom.mobile.browser

import android.Manifest

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
 */
enum class SitePermission(
    /** Storage key; shared with the desktop browser's `permissions.json`. */
    val key: String,
    /** Noun for lists ("Camera"). */
    val label: String,
    /** Verb phrase for the prompt ("example.com wants to …"). */
    val phrase: String,
    /**
     * Android runtime permissions backing the capability. The site is
     * granted once *any* of them is held (location: approximate is
     * enough; the user chooses precision in the system dialog).
     */
    val androidPermissions: List<String>,
) {
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
fun describePermissionRequest(permissions: Collection<SitePermission>): String {
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
}

/** How a request should be handled before any prompt. */
sealed interface PermissionPlan {
    /** Every permission is allowed; go to the Android runtime-permission step. */
    data object Grant : PermissionPlan

    /** At least one permission is blocked (stored, session, or embargo). */
    data object Deny : PermissionPlan

    /** Ask the user about [undecided]; the rest are already allowed. */
    data class Ask(val undecided: List<SitePermission>) : PermissionPlan
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
    permissions: List<SitePermission>,
    stored: Map<SitePermission, PermissionDecision>,
    session: PermissionSession,
): PermissionPlan {
    val undecided = mutableListOf<SitePermission>()
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
    val permission: SitePermission,
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
 */
class PermissionSession {
    private data class Key(val origin: String, val permission: SitePermission)

    private val decisions = LinkedHashMap<Key, PermissionDecision>()
    private val embargoed = HashSet<Key>()
    private val dismissals = HashMap<Key, Int>()

    /** Bumped on every change; observe it to re-read [entries]. */
    val version = kotlinx.coroutines.flow.MutableStateFlow(0)

    @Synchronized
    fun decisionFor(origin: String, permission: SitePermission): PermissionDecision? =
        decisions[Key(origin, permission)]

    /** Record an Allow/Block answered without "remember" (or alongside a remembered one). */
    @Synchronized
    fun record(origin: String, permission: SitePermission, decision: PermissionDecision, remembered: Boolean) {
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
    fun dismiss(origin: String, permission: SitePermission): Boolean {
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
    fun revoke(origin: String, permission: SitePermission) {
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
