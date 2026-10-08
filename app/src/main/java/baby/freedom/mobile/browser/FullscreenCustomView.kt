package baby.freedom.mobile.browser

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.areSystemBarsVisible
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import baby.freedom.mobile.R
import kotlinx.coroutines.delay
import kotlin.math.abs

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
 *
 * A page can draw anything once it has the whole screen, including a
 * copy of the address bar reading another site's name. So, like
 * Chrome's "swipe to exit" bubble, a short notice names the site that
 * went fullscreen ([TabsState.Fullscreen.site]) and how to leave
 * (#467) — drawn by the app above the page's view, where the page
 * can't paint over it. It shows when the session starts and again
 * whenever the user can be newly looking at it ([FullscreenNotice]).
 * It takes no touches, so the page's own controls under it still work.
 */
@OptIn(ExperimentalLayoutApi::class)
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

    val notice = remember(session) { FullscreenNotice() }
    val barsVisible = WindowInsets.areSystemBarsVisible
    LaunchedEffect(notice, barsVisible) { notice.onBarsVisible(barsVisible) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(notice, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) notice.onResumed()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    var noticeShown by remember(notice) { mutableStateOf(false) }
    LaunchedEffect(notice, notice.shows) {
        noticeShown = true
        delay(FULLSCREEN_NOTICE_MS)
        noticeShown = false
    }

    // Transient bars (swiped in over the page) don't change the window's
    // insets, so [areSystemBarsVisible] never sees them; the swipe that
    // brings them does reach the window, though, from whichever edge the
    // bars sit at (the side, for a landscape 3-button nav bar). Watched
    // in the Initial pass and never consumed: the page still gets every
    // touch.
    val density = LocalDensity.current
    val edge = with(density) { FULLSCREEN_EDGE.toPx() }
    val swipe = with(density) { FULLSCREEN_SWIPE.toPx() }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(notice, edge, swipe) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull() ?: continue
                        val at = change.position
                        when {
                            event.type == PointerEventType.Press && !change.previousPressed ->
                                notice.onPress(
                                    at.x, at.y,
                                    size.width.toFloat(), size.height.toFloat(), edge,
                                )
                            change.pressed -> notice.onMove(at.x, at.y, swipe)
                            // A release that no MotionEvent carried is Compose's
                            // synthetic cancel: the system took the gesture.
                            else -> notice.onRelease(cancelled = event.motionEvent == null)
                        }
                    }
                }
            },
    ) {
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
        AnimatedVisibility(
            visible = noticeShown,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(horizontal = 24.dp, vertical = 24.dp),
        ) {
            FullscreenNoticeCard(session.site)
        }
    }
}

/** How long one showing of the fullscreen notice stays up. */
private const val FULLSCREEN_NOTICE_MS = 4_000L

/**
 * How far from an edge of the screen a touch counts as the start of
 * the swipe that brings the system bars back.
 */
private val FULLSCREEN_EDGE = 24.dp

/**
 * How far a touch that started at an edge must travel inward before
 * it's the swipe that brings the bars back, not a tap on the page's own
 * control there (a video scrubber along the bottom).
 */
private val FULLSCREEN_SWIPE = 16.dp

@Composable
private fun FullscreenNoticeCard(site: String?) {
    // A plain Column, not a Surface: a Surface swallows touches, and the
    // notice must not block the page's controls under it.
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .background(ComposeColor.Black.copy(alpha = 0.82f), RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(
            text = site?.let { stringResource(R.string.browser_fullscreen_site, it) }
                ?: stringResource(R.string.browser_fullscreen_page),
            color = ComposeColor.White,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(R.string.browser_fullscreen_exit),
            color = ComposeColor.White.copy(alpha = 0.8f),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * When [FullscreenCustomView]'s notice shows (#467): [shows] counts the
 * showings, and each new value puts the notice up for a few seconds.
 * Once when the session starts; again each time the user reaches for
 * the system bars, looking for a way out — a swipe in from any edge of
 * the screen, where the bars come back from (the top and bottom, or the
 * side a landscape 3-button nav bar sits at), see [onPress]; or the
 * bars actually coming back ([onBarsVisible]); and again when the app
 * comes back to the front (from Recents or another app), since whoever
 * looks at it then hasn't seen the first one.
 */
internal class FullscreenNotice {
    var shows by mutableIntStateOf(1)
        private set

    private var barsVisible: Boolean? = null
    private var resumedOnce = false

    /** The edge the touch in progress started at, if it did. */
    private var from: Edge? = null
    private var startX = 0f
    private var startY = 0f

    private enum class Edge { Left, Top, Right, Bottom }

    fun onBarsVisible(visible: Boolean) {
        if (barsVisible == false && visible) shows++
        barsVisible = visible
    }

    /**
     * A touch went down at ([x], [y]) px on a screen [width] by
     * [height] px; within [edge] px of a side it may be the start of the
     * swipe that brings the bars back. Only a press doesn't count: a tap
     * there is as likely to be on the page's own control ([onMove]).
     */
    fun onPress(x: Float, y: Float, width: Float, height: Float, edge: Float) {
        startX = x
        startY = y
        // Nearest edge, so a corner press is judged by the edge it hugs.
        from = listOf(
            Edge.Left to x,
            Edge.Top to y,
            Edge.Right to width - x,
            Edge.Bottom to height - y,
        ).filter { it.second <= edge }.minByOrNull { it.second }?.first
    }

    /**
     * The touch moved to ([x], [y]). Once one that started at an edge
     * has travelled [swipe] px inward, mostly away from that edge rather
     * than along it (a scrubber dragged sideways along the bottom), it's
     * the edge swipe, and the notice shows again.
     */
    fun onMove(x: Float, y: Float, swipe: Float) {
        val edge = from ?: return
        val dx = x - startX
        val dy = y - startY
        val (inward, along) = when (edge) {
            Edge.Left -> dx to dy
            Edge.Right -> -dx to dy
            Edge.Top -> dy to dx
            Edge.Bottom -> -dy to dx
        }
        if (inward >= swipe && inward > abs(along)) {
            from = null
            shows++
        }
    }

    /**
     * The touch ended. If the system took an edge touch away from the
     * window ([cancelled]) before it travelled far, that was the system's
     * own edge gesture.
     */
    fun onRelease(cancelled: Boolean) {
        if (cancelled && from != null) shows++
        from = null
    }

    fun onResumed() {
        // The first ON_RESUME is the observer catching up with an
        // already-resumed screen as it's added, not a return.
        if (resumedOnce) shows++
        resumedOnce = true
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
