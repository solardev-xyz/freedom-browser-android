package baby.freedom.mobile.browser

import android.content.Context
import android.util.Log
import baby.freedom.mobile.data.NodeSettings
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

private const val TAG = "AppUpdates"

/**
 * A release's dotted version, `v0.6.10` → `[0, 6, 10]`. Only plain
 * numeric versions parse: a tag with a suffix (`v0.7.0-rc1`) or anything
 * else is not a release this checker compares against. Missing trailing
 * parts count as zero, so `0.7` and `0.7.0` are equal.
 */
internal data class ReleaseVersion(val parts: List<Int>) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int {
        for (i in 0 until maxOf(parts.size, other.parts.size)) {
            val c = parts.getOrElse(i) { 0 }.compareTo(other.parts.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    override fun toString(): String = parts.joinToString(".")

    companion object {
        private val PATTERN = Regex("""^[vV]?(\d{1,9}(?:\.\d{1,9}){0,3})$""")

        /** [text] (a tag or a `versionName`) as a version, or `null` if it isn't a plain one. */
        fun parse(text: String?): ReleaseVersion? {
            val m = PATTERN.matchEntire(text?.trim() ?: return null) ?: return null
            return ReleaseVersion(m.groupValues[1].split('.').map { it.toInt() })
        }
    }
}

/**
 * The latest published release, as far as this checker cares: its tag
 * and version, and the page it links to. [url] is always built from the
 * tag under [AppUpdates.RELEASES_PAGE] — never taken from the response —
 * so the notice can only ever send the user to this repository's own
 * release page.
 */
internal data class LatestRelease(val tag: String, val version: ReleaseVersion) {
    val url: String get() = AppUpdates.RELEASES_PAGE + "tag/" + tag
}

/**
 * GitHub's `GET /repos/{owner}/{repo}/releases/latest` body as the
 * release it names, or `null` when it isn't one this checker can
 * compare: unparseable JSON, no `tag_name`, a tag that isn't a plain
 * version ([ReleaseVersion]), or a draft/pre-release (the endpoint
 * never returns those, but a response that says so isn't trusted to be
 * the latest *release*).
 */
internal fun parseLatestRelease(body: String): LatestRelease? {
    val json = try {
        JSONObject(body)
    } catch (e: Exception) {
        return null
    } catch (e: StackOverflowError) {
        // A deeply nested body: refuse it rather than crash.
        return null
    }
    if (json.optBoolean("draft", false) || json.optBoolean("prerelease", false)) return null
    val tag = json.opt("tag_name") as? String ?: return null
    val version = ReleaseVersion.parse(tag) ?: return null
    return LatestRelease(tag.trim(), version)
}

/** How the last check ended. */
internal sealed interface UpdateCheckOutcome {
    /** [latest] is newer than the installed version. */
    data class Available(val latest: LatestRelease) : UpdateCheckOutcome

    /** The installed version is [latest] (or newer: a build ahead of the last release). */
    data class UpToDate(val latest: LatestRelease) : UpdateCheckOutcome

    /** The check didn't get an answer it could compare; [reason] says why, for the Settings row. */
    data class Failed(val reason: String) : UpdateCheckOutcome
}

/**
 * [latest] against [installed]: [UpdateCheckOutcome.Failed] when the
 * installed version isn't a plain one ([installed] `null`: a local build
 * named otherwise), so nothing is claimed either way.
 */
internal fun compareRelease(installed: ReleaseVersion?, latest: LatestRelease): UpdateCheckOutcome =
    when {
        installed == null -> UpdateCheckOutcome.Failed("this build's version isn't a release number")
        latest.version > installed -> UpdateCheckOutcome.Available(latest)
        else -> UpdateCheckOutcome.UpToDate(latest)
    }

/**
 * Whether a check is due at [now] after one at [lastCheck] (both epoch
 * ms): never checked, a day or more ago, or a stamp in the future (a
 * clock that was ahead when it was written and has since been
 * corrected) — which must not read as fresh forever.
 */
internal fun updateCheckDue(lastCheck: Long?, now: Long, period: Long = AppUpdates.CHECK_PERIOD_MS): Boolean =
    lastCheck == null || (now - lastCheck) !in 0 until period

/**
 * How long the schedule sleeps before it looks at the wall clock again,
 * with the last check at [lastCheck] and not yet due at [now]: until it
 * is due, but never more than [poll]. A coroutine `delay` runs on the
 * monotonic clock, which stops while the phone is in deep sleep, so one
 * long delay for "the rest of the day" could last days of wall-clock
 * time on a mostly-asleep phone; waking every [poll] of awake time (and
 * whenever the app comes to the foreground, [AppUpdates.onAppForeground])
 * re-reads the wall clock instead.
 */
internal fun updateCheckWaitMs(
    lastCheck: Long,
    now: Long,
    period: Long = AppUpdates.CHECK_PERIOD_MS,
    poll: Long = AppUpdates.POLL_MS,
): Long = (period - (now - lastCheck)).coerceIn(1L, poll)

/**
 * Claim the single check slot for a check about to start: `true`, with
 * [AppUpdateState.checking] already set, unless a check is already
 * running (or claimed) or the build is a store install. Set in the same
 * atomic step as the test, so two quick taps on **Check now**, before a
 * recomposition disables the button, start one check, not two.
 */
internal fun MutableStateFlow<AppUpdateState>.claimCheck(): Boolean {
    while (true) {
        val s = value
        if (s.store != null || s.checking) return false
        if (compareAndSet(s, s.copy(checking = true))) return true
    }
}

/**
 * The installers that are app stores: an install from one of these is
 * kept up to date by the store, so the app doesn't check GitHub
 * (Freedom isn't in any today; these are the ones it could come from).
 * Sideloads report the system package installer, a file manager, a
 * browser, or nothing (adb).
 */
internal val STORE_INSTALLERS: Map<String, String> = mapOf(
    "com.android.vending" to "Google Play",
    "org.fdroid.fdroid" to "F-Droid",
    "org.fdroid.fdroid.privileged" to "F-Droid",
    "org.fdroid.basic" to "F-Droid",
    "com.aurora.store" to "Aurora Store",
    "app.accrescent.client" to "Accrescent",
    "com.amazon.venezia" to "the Amazon Appstore",
    "com.sec.android.app.samsungapps" to "Galaxy Store",
    "com.huawei.appmarket" to "AppGallery",
    "com.xiaomi.market" to "GetApps",
)

/** The store [installer] names, or `null` when it isn't one ([STORE_INSTALLERS]). */
internal fun storeFor(installer: String?): String? = installer?.let { STORE_INSTALLERS[it] }

/** What Settings and the home notice read. */
internal data class AppUpdateState(
    /** The installed `versionName`, as shown. */
    val installedName: String = "",
    /** The store this build came from ([storeFor]); no check runs while it's set. */
    val store: String? = null,
    val checking: Boolean = false,
    /** When the last check ran (epoch ms), from disk; `null` before the first. */
    val lastCheckedAt: Long? = null,
    /** The newest release the last successful check saw, from disk. */
    val latest: LatestRelease? = null,
    /** The last check's outcome in this process; `null` before one. */
    val last: UpdateCheckOutcome? = null,
    /** The release tag whose home notice the user closed. */
    val dismissedTag: String? = null,
    /**
     * "Check for updates" (on by default) — `false` until the setting is
     * read, so an opted-out user never sees the notice flash up.
     */
    val enabled: Boolean = false,
) {
    /** The newer release, if the last successful check found one; not tied to [enabled]. */
    val available: LatestRelease?
        get() = latest?.takeIf { compareRelease(ReleaseVersion.parse(installedName), it) is UpdateCheckOutcome.Available }

    /** The release the home screen's notice announces: newer, not closed, checks on. */
    val notice: LatestRelease?
        get() = available?.takeIf { enabled && store == null && it.tag != dismissedTag }
}

/**
 * Tell the user when a newer Freedom release is out (#272). Android
 * installs are sideloaded APKs from GitHub Releases, so nothing else
 * would: once a day at most, while **Check for updates** is on (the
 * default), the app reads the latest release from GitHub's API over
 * HTTPS and compares its tag with the installed `versionName`. (The API
 * doesn't carry a `versionCode`; each release's tag is its
 * `versionName`, and both only ever go up together.)
 *
 * The request carries nothing about the user: a fixed `User-Agent`
 * (`Freedom`, instead of the platform's, which names the device model
 * and Android build), no cookies (the app installs no
 * `CookieHandler`), no cache, no token, and no redirects followed.
 * GitHub still sees the IP address and the time, as with any request.
 *
 * A newer release shows as a notice on the home screen with its version
 * and a link to its release page, until the user closes it (that
 * release's; a later one shows again) or installs it, and in Settings →
 * About, where **Check now** runs a check at any time. A build that came
 * from an app store ([STORE_INSTALLERS]) never checks: the store updates it.
 *
 * State on disk: `files/app-update/state.json` — when the last check
 * ran, the newest release it saw, and the dismissed tag.
 */
internal object AppUpdates {
    const val REPO = "solardev-xyz/freedom-browser-android"
    const val RELEASES_PAGE = "https://github.com/$REPO/releases/"
    private const val LATEST_API = "https://api.github.com/repos/$REPO/releases/latest"

    /** At most one scheduled check a day. */
    const val CHECK_PERIOD_MS = 24 * 60 * 60_000L

    /**
     * The longest the schedule sleeps (in awake time) before re-reading
     * the wall clock ([updateCheckWaitMs]).
     */
    const val POLL_MS = 60 * 60_000L

    /** Let a cold start settle before the first scheduled check. */
    private const val FIRST_DELAY_MS = 30_000L

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000

    /** The whole request, however slowly the server answers. */
    private const val TOTAL_TIMEOUT_MS = 20_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(AppUpdateState())
    val state: StateFlow<AppUpdateState> = _state.asStateFlow()

    /** One check at a time: the scheduled one and Check now share it. */
    private val mutex = Mutex()

    /** Wakes the schedule to re-read the wall clock ([onAppForeground]). */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var file: File? = null

    private var started = false

    /** Start following Settings. Idempotent; call from the activity's `onCreate`. */
    fun start(context: Context) {
        synchronized(this) {
            if (started) return
            started = true
        }
        val app = context.applicationContext
        val installer = runCatching {
            app.packageManager.getInstallSourceInfo(app.packageName).installingPackageName
        }.getOrNull()
        val versionName = runCatching {
            app.packageManager.getPackageInfo(app.packageName, 0).versionName
        }.getOrNull().orEmpty()
        val f = File(File(app.filesDir, "app-update"), "state.json")
        file = f
        scope.launch {
            val saved = readSaved(f)
            _state.update {
                it.copy(
                    installedName = versionName,
                    store = storeFor(installer),
                    lastCheckedAt = saved.checkedAt,
                    latest = saved.latest,
                    dismissedTag = saved.dismissed,
                )
            }
            if (_state.value.store != null) {
                Log.i(TAG, "installed from $installer: updates come from the store")
                return@launch
            }
            NodeSettings.get(app).checkForUpdates
                // An unreadable setting may be the user's Off: don't check.
                .catch { e ->
                    Log.w(TAG, "couldn't read Check for updates", e)
                    emit(false)
                }
                .distinctUntilChanged().collectLatest { on ->
                _state.update { it.copy(enabled = on) }
                if (!on) return@collectLatest
                delay(FIRST_DELAY_MS)
                while (true) {
                    val now = System.currentTimeMillis()
                    val last = _state.value.lastCheckedAt
                    if (!updateCheckDue(last, now)) {
                        withTimeoutOrNull(updateCheckWaitMs(checkNotNull(last), now)) { wake.receive() }
                        continue
                    }
                    if (_state.claimCheck()) checkNow()
                    // Else a Check now is running: it stamps the time, and
                    // the loop reads that stamp once the check is done.
                    else _state.first { !it.checking }
                }
            }
        }
    }

    /**
     * Settings' **Check now**: a check whatever the schedule (not for a
     * store install). A tap while a check is already running (the button
     * not yet disabled) starts nothing ([claimCheck]).
     */
    fun checkForUpdates() {
        if (!_state.claimCheck()) return
        scope.launch { checkNow() }
    }

    /**
     * The app came to the foreground: let the schedule re-read the wall
     * clock now, so a day that passed while the phone slept (and the
     * monotonic clock with it) is noticed when the user is back.
     */
    fun onAppForeground() {
        wake.trySend(Unit)
    }

    /** Close the home notice for [release]; a later release shows again. */
    fun dismiss(release: LatestRelease) {
        _state.update { it.copy(dismissedTag = release.tag) }
        scope.launch { save() }
    }

    /** One check; the caller has claimed [AppUpdateState.checking] ([claimCheck]). */
    private suspend fun checkNow(): UpdateCheckOutcome = try {
        mutex.withLock { runCheck() }
    } catch (e: CancellationException) {
        // Also when cancelled still waiting for the lock: release the claim.
        _state.update { it.copy(checking = false) }
        throw e
    }

    private suspend fun runCheck(): UpdateCheckOutcome {
        return try {
            // Stamped before the request, whatever it answers, so the
            // schedule never asks GitHub more than once a day — a check
            // that failed waits for tomorrow's, or for Check now.
            _state.update { it.copy(lastCheckedAt = System.currentTimeMillis()) }
            save()
            val outcome = when (val body = fetch()) {
                is Fetched.Body -> {
                    val latest = parseLatestRelease(body.text)
                    if (latest == null) {
                        UpdateCheckOutcome.Failed("GitHub's answer didn't name a release")
                    } else {
                        _state.update { it.copy(latest = latest) }
                        save()
                        compareRelease(ReleaseVersion.parse(_state.value.installedName), latest)
                    }
                }
                is Fetched.Error -> UpdateCheckOutcome.Failed(body.reason)
            }
            Log.i(TAG, "update check: $outcome")
            _state.update { it.copy(checking = false, last = outcome) }
            outcome
        } catch (e: CancellationException) {
            _state.update { it.copy(checking = false) }
            throw e
        } catch (e: Throwable) {
            // Nobody awaits a scheduled check: nothing may escape it.
            Log.w(TAG, "update check failed", e)
            val outcome = UpdateCheckOutcome.Failed(e.message ?: e.javaClass.simpleName)
            _state.update { it.copy(checking = false, last = outcome) }
            outcome
        }
    }

    /** The latest-release JSON, bounded as a whole by [TOTAL_TIMEOUT_MS] ([fetchLatestRelease]). */
    private suspend fun fetch(): Fetched = fetchLatestRelease(TOTAL_TIMEOUT_MS) {
        (URL(LATEST_API).openConnection() as HttpsURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("User-Agent", "Freedom")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
    }

    private class Saved(val checkedAt: Long?, val latest: LatestRelease?, val dismissed: String?)

    private fun readSaved(f: File): Saved = runCatching {
        val json = JSONObject(f.readText())
        Saved(
            checkedAt = json.optLong("checkedAt", 0L).takeIf { it > 0 },
            latest = json.optString("tag").takeIf { it.isNotEmpty() }
                ?.let { tag -> ReleaseVersion.parse(tag)?.let { LatestRelease(tag, it) } },
            dismissed = json.optString("dismissed").takeIf { it.isNotEmpty() },
        )
    }.getOrElse { Saved(null, null, null) }

    /** The state's persisted part, atomically (tmp + rename); a failure only costs the stamp. */
    private suspend fun save() = withContext(Dispatchers.IO) {
        val f = file ?: return@withContext
        runCatching { saveAppUpdateState(f) { _state.value } }
            .onFailure { Log.w(TAG, "couldn't save update state", it) }
    }
}

/** Serializes [saveAppUpdateState]'s writes, process-wide. */
private val appUpdateSaveLock = Any()

/**
 * Write [current]'s persisted part to [f], atomically (tmp + rename).
 * The snapshot is taken inside the lock, so of two overlapping saves the
 * one that renames last also read the state last: a save holding an
 * older snapshot (say, from before a dismissal) can never land on top
 * of a newer one. Throws on failure.
 */
internal fun saveAppUpdateState(f: File, current: () -> AppUpdateState) {
    synchronized(appUpdateSaveLock) {
        val s = current()
        val json = JSONObject()
        s.lastCheckedAt?.let { json.put("checkedAt", it) }
        s.latest?.let { json.put("tag", it.tag) }
        s.dismissedTag?.let { json.put("dismissed", it) }
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(f)) throw IOException("rename failed")
    }
}

/** What [fetchLatestRelease] got: the body, or why not (for the Settings row). */
internal sealed interface Fetched {
    class Body(val text: String) : Fetched
    class Error(val reason: String) : Fetched
}

/** [Fetched.Error]'s reason when the deadline, and only the deadline, ended the request. */
internal const val FETCH_TIMED_OUT = "GitHub took too long to answer"

/**
 * GET the connection [open] builds (not yet connected) and read its
 * body, bounded as a whole by [timeoutMs] through [withHardDeadline]: on
 * the deadline the connection is closed from another thread than the
 * one stuck in the read. Every failure inside the request — a refused
 * connection, a `RuntimeException` from `openConnection()` or
 * `responseCode` — is answered inside the block as its own
 * [Fetched.Error], so the only `null` [withHardDeadline] can hand back
 * is the deadline itself, and only that reads [FETCH_TIMED_OUT].
 */
internal suspend fun fetchLatestRelease(timeoutMs: Long, open: () -> HttpURLConnection): Fetched =
    withHardDeadline(timeoutMs) { guard ->
        try {
            val conn = open()
            // Refused only once the deadline has abandoned the block.
            if (!guard.register { conn.disconnect() }) return@withHardDeadline null
            try {
                val code = conn.responseCode
                if (code != HttpURLConnection.HTTP_OK) {
                    return@withHardDeadline Fetched.Error(
                        if (code == 403 || code == 429) "GitHub is limiting requests; try again later"
                        else "GitHub answered HTTP $code",
                    )
                }
                val bytes = conn.inputStream.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > MAX_RELEASE_BODY_BYTES) throw IOException("answer too large")
                    }
                    out.toByteArray()
                }
                Fetched.Body(bytes.toString(Charsets.UTF_8))
            } finally {
                conn.disconnect()
            }
        } catch (e: IOException) {
            Fetched.Error("couldn't reach GitHub")
        } catch (e: Throwable) {
            Log.w(TAG, "update check request failed", e)
            Fetched.Error("the request failed (${e.javaClass.simpleName})")
        }
    } ?: Fetched.Error(FETCH_TIMED_OUT)

/** A release body is a few KB; anything this big isn't one. */
private const val MAX_RELEASE_BODY_BYTES = 1 shl 20
