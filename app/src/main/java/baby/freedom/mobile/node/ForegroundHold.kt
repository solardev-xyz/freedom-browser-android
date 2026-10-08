package baby.freedom.mobile.node

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
 * budget's own `onTimeout`, and the app re-promotes it when it next comes
 * to the foreground. A demoted service stays up only while the UI is bound
 * to it: with nothing bound it is an ordinary background service Android
 * will kill again, and sticky-restarting it would just hit the same
 * refusal, so it stops instead. The app starts it again as it opens, while
 * the setting is on.
 */
internal class ForegroundHold {
    /** Foreground status was refused or taken away; updates to the notification are skipped. */
    @Volatile
    var demoted = false
        private set

    /** The UI is bound (between `onBind`/`onRebind` and `onUnbind`). */
    @Volatile
    var bound = false
        private set

    /**
     * Runs [startForeground]; true if the service is now in the foreground.
     * A refusal demotes it rather than throw.
     */
    fun promote(startForeground: () -> Unit): Boolean =
        try {
            startForeground()
            demoted = false
            true
        } catch (e: RuntimeException) {
            // ForegroundServiceStartNotAllowedException (an
            // IllegalStateException), or a SecurityException.
            demoted = true
            false
        }

    /** The day's foreground budget ran out (`onTimeout`): true to stop now. */
    fun timedOut(): Boolean {
        demoted = true
        return !bound
    }

    fun bind() {
        bound = true
    }

    fun unbind() {
        bound = false
    }

    /**
     * `onStartCommand`: true to stop now. A null intent is Android's sticky
     * restart, which no UI asked for; the UI's own start carries an intent
     * and binds right after, so it's never stopped here.
     */
    fun started(stickyRestart: Boolean): Boolean = stickyRestart && shouldStop()

    /** A demoted service with no UI bound stops rather than wait to be killed. */
    fun shouldStop(): Boolean = demoted && !bound
}
