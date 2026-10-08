package baby.freedom.mobile.browser

import android.content.Context
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.transformLatest

// Pausing the pages nobody is looking at (#470).
//
// Making a tab's WebView GONE stops it drawing, and Chromium throttles a
// hidden page, but it doesn't stop it: its timers, polling fetches and
// sockets go on. Two levers, both from the platform:
//
// - `WebView.onPause()` per WebView: a tab that isn't the one on screen,
//   and every tab while the app is in the background. Chromium pauses
//   what it safely can for that page (animations, geolocation); its
//   JavaScript and its media keep running.
// - `WebView.pauseTimers()`: process-wide, every WebView's JavaScript
//   timers, layout and parsing. This one does stop a page's playback, so
//   it's only used while the app is in the background and nothing can be
//   playing.
//
// "Nothing can be playing" can't rest on [BrowserState.playingAudio]
// (#91) alone: that only sees media elements in a page's DOM, not a
// detached `new Audio()`, Web Audio, or media in a shadow root. So the
// timers also wait for the device's own audio: while any player on the
// device is active (Android only tells an app *that* one is, not whose),
// they keep running. That errs towards running: another app's music
// keeps our timers going too, as main always did. And a player has gaps
// (a track ends, the next starts from a `setTimeout`), so the timers only
// pause once all of that has been quiet for [TIMERS_PAUSE_GRACE_MS].
// Nor are they paused while an OpenLV session (#113) is open: its
// transport is a hidden WebView of its own, whose keepalives would stop
// with everyone else's timers.

/** Should a tab's WebView be paused (`WebView.onPause`)? */
internal fun tabWebViewPaused(isActiveTab: Boolean, appStarted: Boolean, playingAudio: Boolean): Boolean =
    !playingAudio && (!isActiveTab || !appStarted)

/** Should every WebView's timers be paused (`WebView.pauseTimers`)? */
internal fun webViewTimersPaused(
    appStarted: Boolean,
    anyAudio: Boolean,
    openLvLive: Boolean,
    deviceAudio: Boolean,
): Boolean = !appStarted && !anyAudio && !openLvLive && !deviceAudio

/** How long nothing may play before the timers pause: a playlist's gap between tracks is shorter. */
internal const val TIMERS_PAUSE_GRACE_MS = 30_000L

/**
 * A wanted paused state that only takes effect once it has held for
 * [graceMs]; a resume goes through at once and cancels a pending pause.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun Flow<Boolean>.pausedAfterGrace(graceMs: Long): Flow<Boolean> =
    distinctUntilChanged()
        .transformLatest { paused ->
            if (paused) delay(graceMs)
            emit(paused)
        }
        .distinctUntilChanged()

/**
 * Whether any player on the device is active: a non-privileged app gets
 * only active players in these lists (`PlaybackActivityMonitor`). Device-wide: Android
 * hides from an app which player is whose, so this is "maybe ours".
 */
internal fun deviceAudioActive(context: Context): Flow<Boolean> {
    val audio = context.applicationContext.getSystemService(AudioManager::class.java) ?: return flowOf(false)
    return callbackFlow {
        fun report(configs: List<AudioPlaybackConfiguration>) {
            // An app is handed only the active players, anonymised.
            trySend(configs.isNotEmpty())
        }
        val callback = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>) = report(configs)
        }
        report(runCatching { audio.activePlaybackConfigurations }.getOrDefault(emptyList()))
        audio.registerAudioPlaybackCallback(callback, Handler(Looper.getMainLooper()))
        awaitClose { audio.unregisterAudioPlaybackCallback(callback) }
    }.distinctUntilChanged()
}

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
 * The process-wide timers' wanted state and what was last applied.
 * Pausing needs a WebView to call it on; a pause wanted while there is
 * none is kept, and applied by [created] for the first WebView made.
 * [resume] gets null when there's no WebView left to call it on.
 */
internal class ProcessTimers<V : Any>(
    private val pause: (V) -> Unit,
    private val resume: (V?) -> Unit,
) {
    private val latch = PauseLatch()
    var wanted: Boolean = false
        private set
    val paused: Boolean get() = latch.paused

    fun set(paused: Boolean, any: V?) {
        wanted = paused
        apply(any)
    }

    /** A WebView was just made: a pause still pending lands on it. */
    fun created(view: V) = apply(view)

    private fun apply(any: V?) {
        if (wanted && any == null) return
        latch.set(wanted, pause = { pause(any!!) }, resume = { resume(any) })
    }
}

/**
 * The process-wide `pauseTimers`/`resumeTimers` (#470). Process-wide,
 * not per host, because the timers are: an Activity destroyed in the
 * background leaves them paused, and the next one's host resumes them.
 * Main thread.
 */
internal object WebViewTimers {
    private var appContext: Context? = null

    private val timers = ProcessTimers<WebView>(
        pause = { it.pauseTimers() },
        resume = { any ->
            if (any != null) {
                any.resumeTimers()
            } else {
                // Either call is process-wide, so a throwaway WebView does.
                appContext?.let { ctx -> runCatching { WebView(ctx).apply { resumeTimers(); destroy() } } }
            }
        },
    )

    /** Pauses or resumes every WebView's timers, through [any] when there is one. */
    fun set(paused: Boolean, any: WebView?, context: Context) {
        appContext = context.applicationContext
        timers.set(paused, any)
    }

    /** Call for every WebView made, so a pause wanted while there was none still lands. */
    fun created(view: WebView) = timers.created(view)
}
