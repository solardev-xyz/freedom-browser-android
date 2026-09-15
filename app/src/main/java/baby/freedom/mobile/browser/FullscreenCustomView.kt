package baby.freedom.mobile.browser

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Screen-covering host for an HTML5 fullscreen session (see
 * [TabsState.fullscreen]). Composed last in [BrowserScreen] so it
 * draws over the whole browser chrome — address bar, home overlay,
 * any settings / history page that happens to be open — and hides
 * the system bars for its lifetime (swipe from an edge peeks them
 * back in transiently). The underlying WebView stays composed:
 * Chromium moves the rendering into [TabsState.Fullscreen.view] for
 * the duration, and moves it back when the session ends, so scroll
 * position and page state survive the round trip.
 *
 * System back leaves fullscreen rather than navigating the page,
 * matching Chrome.
 */
@Composable
internal fun FullscreenCustomView(
    session: TabsState.Fullscreen,
    onExit: () -> Unit,
) {
    BackHandler(onBack = onExit)

    val hostView = LocalView.current
    DisposableEffect(hostView) {
        val window = hostView.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, hostView) }
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    AndroidView(
        factory = { context ->
            FrameLayout(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setBackgroundColor(Color.BLACK)
            }
        },
        update = { frame ->
            val view = session.view
            if (frame.childCount == 1 && frame.getChildAt(0) === view) return@AndroidView
            frame.removeAllViews()
            (view.parent as? ViewGroup)?.removeView(view)
            frame.addView(
                view,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        },
        onRelease = { frame -> frame.removeAllViews() },
        modifier = Modifier.fillMaxSize(),
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
