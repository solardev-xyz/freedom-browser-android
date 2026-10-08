package baby.freedom.mobile.browser

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/**
 * Runs [poll], then waits [periodMs], over and over, but only while this
 * lifecycle is at least STARTED (#472). Compose keeps a screen's
 * composition, and its `produceState`/`LaunchedEffect` loops, alive after
 * `onStop`, and `:node` is a foreground service, so an ungated loop goes on
 * reading the gateway and chain RPCs for as long as the app sits in the
 * background. This one stops when the app goes to the background and reads
 * again at once when it comes back. Never returns while the lifecycle lives:
 * cancel the calling coroutine to end it.
 */
internal suspend fun Lifecycle.pollWhileStarted(periodMs: Long, poll: suspend () -> Unit) {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        while (true) {
            poll()
            delay(periodMs)
        }
    }
}

/** The current composition's lifecycle, for [pollWhileStarted]; also a fitting key for the effect that polls. */
@Composable
@ReadOnlyComposable
internal fun currentLifecycle(): Lifecycle = LocalLifecycleOwner.current.lifecycle
