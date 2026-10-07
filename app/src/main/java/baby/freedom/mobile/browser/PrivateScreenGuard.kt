package baby.freedom.mobile.browser

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay

/**
 * Whether the screen may be showing something from a private session
 * (#86): the private tab itself — also while a panel (Settings, History…)
 * is over it, since panels slide in and out over the live page — the tab
 * switcher while it holds a private tab's card (title and thumbnail), or
 * the Downloads list while a private session's downloads are in it.
 *
 * The switcher holds private cards only on its Private pane (#418,
 * [switcherPrivatePane]). It covers the whole screen and comes and goes
 * without sliding, so while it shows the *Tabs* pane nothing private is
 * on screen, even with a private tab active under it: closing the
 * switcher brings that tab back in a composition that turns the flag on
 * before its first frame, as opening a private tab does.
 */
internal fun privateContentOnScreen(
    activePrivate: Boolean,
    anyPrivate: Boolean,
    switcherShown: Boolean,
    downloadsShown: Boolean,
    switcherPrivatePane: Boolean = true,
): Boolean = (anyPrivate && downloadsShown) ||
    if (switcherShown) anyPrivate && switcherPrivatePane else activePrivate

/**
 * Keeps private pages out of the Recents snapshot and out of
 * screenshots (#86). Android saves a picture of the task's last frame to
 * disk (`/data/system_ce/<user>/snapshots`) when the app leaves the
 * foreground and shows it on the Recents card; that file is outside the
 * WebView profile the private wipe covers and outlives the process.
 * While [secure], the window is `FLAG_SECURE` (no snapshot content, no
 * screenshots or casting — as Chrome does for incognito) and, on API 33+,
 * also opts out of the Recents screenshot altogether.
 *
 * Turned on straight away (in the same frame that first shows the
 * private content), but turned off only once the snackbar fade-out
 * ([SECURE_OFF_DELAY_MS]) and two more frames have gone by with
 * non-private content, so the frame that's on screen when the flag
 * drops — and any snapshot taken from it — is already the normal one,
 * never the private page (or a private download's fading notice) it
 * replaced. Leaving composition turns it off too, so a screen shown
 * after the browser in this Activity isn't left secure.
 *
 * `FLAG_SECURE` covers this window only; the other windows a private
 * page can open are made secure themselves: JavaScript dialogs
 * ([showJsDialog]) and Chromium's own `<select>` lists and
 * pickers ([PrivateWindowContext]).
 */
@Composable
internal fun PrivateScreenGuard(secure: Boolean) {
    val activity = LocalContext.current.findActivity() ?: return
    // On: synchronously as the composition that shows it is applied,
    // before that frame is drawn.
    SideEffect {
        if (secure) activity.setPrivateScreenSecure(true)
    }
    // Off: after any snackbar fade-out, two frames later. Frame 1 draws
    // the non-private content; the second callback runs only after that
    // frame was drawn and handed over. Going private again inside that
    // window cancels this.
    LaunchedEffect(activity, secure) {
        if (!secure) {
            delay(SECURE_OFF_DELAY_MS)
            withFrameNanos { }
            withFrameNanos { }
            activity.setPrivateScreenSecure(false)
        }
    }
    DisposableEffect(activity) {
        onDispose { activity.setPrivateScreenSecure(false) }
    }
}

/**
 * How long the flag outlasts private content before the two-frame wait:
 * a dismissed snackbar (a private download's notice) fades out over
 * 75 ms after it leaves the host, and must be gone from the frame too.
 */
private const val SECURE_OFF_DELAY_MS = 150L

/** [PrivateScreenGuard]'s token in [setWindowSecureFor]; other screens hold their own. */
private object PrivateContent

private fun Activity.setPrivateScreenSecure(secure: Boolean) = setWindowSecureFor(PrivateContent, secure)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
