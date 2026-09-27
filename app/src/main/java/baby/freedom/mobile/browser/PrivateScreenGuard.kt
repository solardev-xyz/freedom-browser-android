package baby.freedom.mobile.browser

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext

/**
 * Whether the screen may be showing something from a private session
 * (#86): the private tab itself — also while a panel (Settings, History…)
 * is over it, since panels slide in and out over the live page — the tab
 * switcher while it holds a private tab's card (title and thumbnail), or
 * the Downloads list while a private session's downloads are in it.
 */
internal fun privateContentOnScreen(
    activePrivate: Boolean,
    anyPrivate: Boolean,
    switcherShown: Boolean,
    downloadsShown: Boolean,
): Boolean = activePrivate || (anyPrivate && (switcherShown || downloadsShown))

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
 * private content), but turned off only after two frames have gone by
 * with non-private content, so the frame that's on screen when the flag
 * drops — and any snapshot taken from it — is already the normal one,
 * never the private page it replaced.
 */
@Composable
internal fun PrivateScreenGuard(secure: Boolean) {
    val activity = LocalContext.current.findActivity() ?: return
    // On: synchronously as the composition that shows it is applied,
    // before that frame is drawn.
    SideEffect {
        if (secure) activity.setPrivateScreenSecure(true)
    }
    // Off: two frames later. Frame 1 draws the non-private content; the
    // second callback runs only after that frame was drawn and handed
    // over. Going private again inside that window cancels this.
    LaunchedEffect(activity, secure) {
        if (!secure) {
            withFrameNanos { }
            withFrameNanos { }
            activity.setPrivateScreenSecure(false)
        }
    }
}

private fun Activity.setPrivateScreenSecure(secure: Boolean) {
    if (secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        setRecentsScreenshotEnabled(!secure)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
