package baby.freedom.mobile.browser

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import java.util.WeakHashMap

/**
 * Who currently wants this Activity's window `FLAG_SECURE`. Several
 * screens do — a private tab ([PrivateScreenGuard], #86) and every screen
 * that shows a recovery phrase ([SecureWindow], #75) — and each must not
 * clear the flag out from under the other, so the flag is on while any
 * holder is registered. Holders are plain tokens that don't reference the
 * Activity, so the weak map really does let a finished Activity go.
 * Main thread only.
 */
private val secureHolders = WeakHashMap<Activity, MutableSet<Any>>()

internal fun Activity.setWindowSecureFor(holder: Any, secure: Boolean) {
    val holders = secureHolders.getOrPut(this) { HashSet() }
    if (secure) holders.add(holder) else holders.remove(holder)
    val any = holders.isNotEmpty()
    if (any) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        setRecentsScreenshotEnabled(!any)
    }
}

/**
 * Makes the window `FLAG_SECURE` while this is composed: no screenshots,
 * screen recording or casting, and a blank Recents card. Put it on every
 * screen that shows a recovery phrase (#75 import, #78 show), the same
 * protection Chrome gives incognito.
 *
 * On in the same frame that first shows the screen; off two frames
 * after it leaves, so the frame on screen when the flag drops is already
 * the next screen, never the phrase.
 */
@Composable
internal fun SecureWindow() {
    val activity = LocalContext.current.findActivity() ?: return
    val view = LocalView.current
    val token = remember { Any() }
    SideEffect { activity.setWindowSecureFor(token, true) }
    DisposableEffect(activity, token) {
        onDispose {
            view.postOnAnimation {
                view.postOnAnimation { activity.setWindowSecureFor(token, false) }
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
