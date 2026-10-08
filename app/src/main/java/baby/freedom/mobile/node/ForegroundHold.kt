package baby.freedom.mobile.node

import android.app.ForegroundServiceStartNotAllowedException
import android.os.Build

/**
 * Whether [NodeService] holds foreground status, and what it does when it
 * can't (#458).
 *
 * Android may refuse `startForeground`: on 12+ for a service (re)started
 * while the app is in the background (a `START_STICKY` restart after the
 * low-memory killer took `:node`), and on 15+ once the day's `dataSync`
 * budget is used up. The refusal is an exception; thrown from `onCreate`
 * it kills `:node`, Android restarts it, and it throws again until the
 * restart backoff gives up, with the node down while its setting says on.
 *
 * So a refused promotion only demotes the service, the same as the
 * budget's own `onTimeout`. The app tries to re-promote it each time it
 * comes to the foreground: that lifts a background-start refusal, but not
 * a spent `dataSync` budget. Bringing the app to the front doesn't give
 * the budget back; re-promotion stays refused ("Time limit already
 * exhausted"), and so does the `startForeground` of a service the app
 * starts afresh while on top (seen on an API 36 emulator). Until the
 * budget refills, the node runs demoted, without its notification, while
 * the app is open.
 *
 * A demoted service stays up only while the UI is bound to it: with
 * nothing bound it is an ordinary background service Android will kill
 * again, and sticky-restarting it would just hit the same refusal, so it
 * stops instead. The app starts it again as it opens, while the setting
 * is on.
 *
 * Only Android's refusal ([isRefusal], by default
 * [ForegroundServiceStartNotAllowedException]) demotes. Anything else
 * `startForeground` throws is a bug in the service's own setup (a missing
 * or invalid foreground service type, a missing permission, a bad
 * notification) and is rethrown, so it crashes loudly as before.
 */
internal class ForegroundHold(
    private val isRefusal: (RuntimeException) -> Boolean = ::isForegroundRefusal,
) {
    /** Foreground status was refused or taken away; updates to the notification are skipped. */
    @Volatile
    var demoted = false
        private set

    /** The UI is bound (between `onBind`/`onRebind` and `onUnbind`). */
    @Volatile
    var bound = false
        private set

    /**
     * Runs [startForeground]; null if the service is now in the foreground,
     * else Android's refusal (its message says why: a background start, or
     * the budget spent), which demotes it rather than throw. Any other
     * exception is rethrown.
     */
    fun promote(startForeground: () -> Unit): RuntimeException? =
        try {
            startForeground()
            demoted = false
            null
        } catch (e: RuntimeException) {
            if (!isRefusal(e)) throw e
            demoted = true
            e
        }

    /** The day's foreground budget ran out (`onTimeout`): true to stop now. */
    fun timedOut(): Boolean {
        demoted = true
        return !bound
    }

    /**
     * Bumped by every bind, unbind and app start, so an unbind's grace
     * timer ([graceOver]) acts only if nothing happened since that unbind.
     * The service's lifecycle callbacks and its timer all run on the main
     * thread.
     */
    @Volatile
    private var epoch = 0L

    fun bind() {
        bound = true
        epoch++
    }

    /** `onUnbind`; returns the token its grace timer hands to [graceOver]. */
    fun unbind(): Long {
        bound = false
        return ++epoch
    }

    /**
     * `onStartCommand`: true to stop now. A null intent is Android's sticky
     * restart, which no UI asked for; the UI's own start carries an intent
     * and binds right after, so it's never stopped here, and it voids any
     * unbind grace timer still pending (the reopened app's start can come
     * before its `onRebind`).
     */
    fun started(stickyRestart: Boolean): Boolean {
        if (!stickyRestart) epoch++
        return stickyRestart && shouldStop()
    }

    /**
     * The grace after the unbind that returned [unbindToken] ran out: true
     * to stop now. False once anything happened since (a bind, a later
     * unbind with its own timer, the app's start), so a stale timer
     * neither stops a reopened app's node nor cuts a later unbind's grace
     * short.
     */
    fun graceOver(unbindToken: Long): Boolean = unbindToken == epoch && shouldStop()

    /** A demoted service with no UI bound stops rather than wait to be killed. */
    fun shouldStop(): Boolean = demoted && !bound
}

/**
 * Android refusing foreground status: a background start on 12+, a spent
 * time budget on 15+. Not its siblings under
 * `ServiceStartNotAllowedException` such as
 * `MissingForegroundServiceTypeException`, which are setup bugs.
 */
internal fun isForegroundRefusal(e: RuntimeException): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is ForegroundServiceStartNotAllowedException
