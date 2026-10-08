package baby.freedom.mobile.browser

import android.content.Context
import android.webkit.WebView

// Pausing the pages nobody is looking at (#470).
//
// Making a tab's WebView GONE stops it drawing, and Chromium throttles a
// hidden page, but it doesn't stop it: its timers, polling fetches and
// sockets go on. Two levers, both from the platform:
//
// - `WebView.onPause()` per WebView: a tab that isn't the one on screen,
//   and every tab while the app is in the background. Chromium pauses
//   what it safely can for that page (animations, geolocation).
// - `WebView.pauseTimers()`: process-wide, every WebView's JavaScript
//   timers, layout and parsing. Only while the app is in the background.
//
// Neither while something is playing: a tab with audible media (#91,
// [BrowserState.playingAudio]) keeps running, and nothing is paused
// process-wide while one does, so music in a background tab goes on
// when the screen turns off and its player's timers (next track, a
// progress poll) with it. Nor process-wide while an OpenLV session
// (#113) is open: its transport is a hidden WebView of its own, whose
// keepalives would stop with everyone else's timers.

/** Should a tab's WebView be paused (`WebView.onPause`)? */
internal fun tabWebViewPaused(isActiveTab: Boolean, appStarted: Boolean, playingAudio: Boolean): Boolean =
    !playingAudio && (!isActiveTab || !appStarted)

/** Should every WebView's timers be paused (`WebView.pauseTimers`)? */
internal fun webViewTimersPaused(appStarted: Boolean, anyAudio: Boolean, openLvLive: Boolean): Boolean =
    !appStarted && !anyAudio && !openLvLive

/**
 * The on/off of something WebView only lets you set, not read back:
 * [set] calls [pause] or [resume] only when the wanted state differs
 * from the last one set. Starts resumed, as a new WebView does.
 */
internal class PauseLatch {
    var paused: Boolean = false
        private set

    fun set(wanted: Boolean, pause: () -> Unit, resume: () -> Unit) {
        if (wanted == paused) return
        paused = wanted
        if (wanted) pause() else resume()
    }
}

/**
 * The process-wide `pauseTimers`/`resumeTimers` (#470). Process-wide,
 * not per host, because the timers are: an Activity destroyed in the
 * background leaves them paused, and the next one's host resumes them.
 * Main thread.
 */
internal object WebViewTimers {
    private val latch = PauseLatch()

    /**
     * Pauses or resumes every WebView's timers. Either call is
     * process-wide, so any WebView does: [any], or (to resume with none
     * left) a throwaway one. With no WebView there's no page to pause.
     */
    fun set(paused: Boolean, any: WebView?, context: Context) {
        if (paused && any == null) return
        latch.set(
            paused,
            pause = { any?.pauseTimers() },
            resume = {
                if (any != null) {
                    any.resumeTimers()
                } else {
                    runCatching { WebView(context.applicationContext).apply { resumeTimers(); destroy() } }
                }
            },
        )
    }
}
