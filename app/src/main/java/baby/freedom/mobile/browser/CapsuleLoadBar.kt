package baby.freedom.mobile.browser

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Thickness of the load bar along the bottom of the address field —
 * Chrome's own line, thin enough to read as a highlight on the edge
 * rather than a second border.
 */
private val CapsuleLoadBarHeight = 3.dp

/**
 * Where a load starts: the bar shows this much the instant a load
 * begins, before the page has reported anything, so a tap is answered
 * at once.
 */
internal const val CAPSULE_LOAD_MIN_FRACTION = 0.05f

/**
 * How long the reported progress may sit still before the bar starts to
 * creep on its own. Ordinary web loads tick far more often than this;
 * a Swarm/IPFS fetch or a slow gateway can sit on one number for many
 * seconds.
 */
internal const val CAPSULE_LOAD_CREEP_DELAY_MS = 1_500L

/**
 * The creep closes on this fraction and never passes it, so a stalled
 * load never looks finished on its own — only the load actually ending
 * fills the bar.
 */
internal const val CAPSULE_LOAD_CREEP_CEILING = 0.9f

/**
 * Time constant of the creep: after this long stalled (past the delay)
 * the bar has covered about 63 % of the way from where it stalled to
 * [CAPSULE_LOAD_CREEP_CEILING]. Slow enough to read as "still working",
 * not as progress the page reported.
 */
private const val CAPSULE_LOAD_CREEP_TIME_CONSTANT_MS = 12_000f

/** Smoothing of the bar towards its target while loading. */
private const val CAPSULE_LOAD_FOLLOW_MS = 120f

/** Smoothing of the final fill to 100 % once the load has ended. */
private const val CAPSULE_LOAD_FILL_MS = 50f

/** Fade-out after the bar has filled. */
private const val CAPSULE_LOAD_FADE_MS = 250f

/** Width of the indeterminate sweep, as a fraction of the field. */
private const val CAPSULE_SWEEP_WIDTH = 0.3f

/** One pass of the indeterminate sweep across the field, in ms. */
private const val CAPSULE_SWEEP_PERIOD_MS = 1_400

/**
 * Where the bar should be heading for a load reporting [progress]
 * (Chromium's 0..100, `-1` before the first report): never below
 * [CAPSULE_LOAD_MIN_FRACTION], never below [floor] (what the bar already
 * showed when the progress last changed, so it never runs backwards),
 * `1` once the page reports 100 — and, once the report has sat still for
 * longer than [CAPSULE_LOAD_CREEP_DELAY_MS] ([stalledMs]), creeping from
 * there towards [CAPSULE_LOAD_CREEP_CEILING] without ever reaching it.
 */
internal fun capsuleLoadTarget(progress: Int, floor: Float, stalledMs: Long): Float {
    if (progress >= 100) return 1f
    val reported = progress.coerceIn(0, 100) / 100f
    val start = max(max(reported, floor), CAPSULE_LOAD_MIN_FRACTION).coerceAtMost(1f)
    val stalled = stalledMs - CAPSULE_LOAD_CREEP_DELAY_MS
    if (stalled <= 0L || start >= CAPSULE_LOAD_CREEP_CEILING) return start
    val reach = 1f - exp(-stalled / CAPSULE_LOAD_CREEP_TIME_CONSTANT_MS)
    return start + (CAPSULE_LOAD_CREEP_CEILING - start) * reach
}

/**
 * How far apart, as a fraction of the field, two drawn positions of the
 * bar may be before it is worth drawing again — about a dp on an
 * ordinary address field. While the bar is within this of where it is
 * heading it snaps there and rests; the slow stall creep advances in
 * steps of this size. So a stalled load redraws a few times a second
 * at most, not at the display's refresh rate (R1-F1).
 */
internal const val CAPSULE_LOAD_STEP = 0.0025f

/** A frame: the shortest wait [CapsuleLoadMeter.frame] asks for. */
private const val CAPSULE_LOAD_FRAME_MS = 16L

/**
 * A bar composed again after this long off the frame clock (the tab was
 * in the background, the bar was off screen) — or shown again after the
 * app itself was out of sight ([CapsuleLoadMeter.unwatched]) — is not
 * resumed where it left off: a load that ended unseen is not filled and
 * faded now, and one that gave way to another unseen starts over.
 */
private const val CAPSULE_LOAD_REATTACH_GAP_MS = 100L

/**
 * The frame-clock time, counted from the stall's start ([stalledMs] of
 * [capsuleLoadTarget]), at which a load stalled on [progress] with
 * [floor] creeps up to [value]; `null` if it never does (at or past the
 * ceiling, or already 100). The bar sleeps until then rather than
 * redraw a sub-pixel creep every frame.
 */
internal fun capsuleCreepReachMs(progress: Int, floor: Float, value: Float): Long? {
    if (progress >= 100 || value >= CAPSULE_LOAD_CREEP_CEILING) return null
    val reported = progress.coerceIn(0, 100) / 100f
    val start = max(max(reported, floor), CAPSULE_LOAD_MIN_FRACTION)
    if (start >= CAPSULE_LOAD_CREEP_CEILING) return null
    if (value <= start) return CAPSULE_LOAD_CREEP_DELAY_MS
    val reach = (value - start) / (CAPSULE_LOAD_CREEP_CEILING - start)
    return CAPSULE_LOAD_CREEP_DELAY_MS + ceil(-CAPSULE_LOAD_CREEP_TIME_CONSTANT_MS * ln(1f - reach)).toLong()
}

/**
 * The bar's state over one load and its fade-out, advanced one frame at a
 * time by [frame]. A plain class with no clock of its own so the whole
 * curve — the instant minimum, the smooth follow, the creep, the fill and
 * the fade — is testable without Compose.
 *
 * One per tab ([BrowserState.loadMeter]), shared by every bar that shows
 * that tab's load, so the bar moving from the address field to the find
 * bar and back (or the tab being shown again mid-load) carries on from
 * where it was instead of starting over at the minimum (R1-M1).
 *
 * [fraction] and [alpha] are snapshot state, read only in the bar's draw
 * phase: a frame invalidates the bar's drawing and nothing else.
 */
internal class CapsuleLoadMeter {
    private val fractionState = mutableFloatStateOf(0f)
    private val alphaState = mutableFloatStateOf(0f)

    /** How much of the field the bar covers, 0..1. */
    val fraction: Float get() = fractionState.floatValue

    /** The bar's opacity: 1 while loading, falling to 0 as it fades out. */
    val alpha: Float get() = alphaState.floatValue

    /** Something is on screen: a load, or a finished load still fading. */
    val visible: Boolean get() = alpha > 0f

    private var running = false
    private var finishing = false
    private var failedEnd = false
    private var lastFrameMs = 0L
    private var lastProgress = Int.MIN_VALUE
    private var lastIndeterminate = false
    private var lastChangeMs = 0L
    private var floor = 0f
    private var lastWait = 0L
    private var drivers = 0
    private var reattached = false
    private var unseen = false
    private var lastGeneration = 0

    /**
     * A bar showing this meter came on screen ([detach] when it goes).
     * The first one after none resumes the meter: see
     * [CAPSULE_LOAD_REATTACH_GAP_MS].
     */
    fun attach() {
        if (drivers++ == 0) reattached = true
    }

    fun detach() {
        drivers = (drivers - 1).coerceAtLeast(0)
    }

    /**
     * The app is back after being out of sight (stopped) with the bar
     * still attached: the frame clock stopped, but the load didn't. The
     * next frame resumes the meter the way a bar coming back on
     * screen does, so a load that ended meanwhile isn't filled and faded
     * as if the user had watched it, and the whole time away isn't
     * advanced as one frame (R2-M2).
     */
    fun unwatched() {
        unseen = true
    }

    /**
     * Advance to [nowMs] (a frame time) and say how long, in ms, the bar
     * can rest before the next frame matters: `0` for the very next
     * frame, [Long.MAX_VALUE] for "not until an input changes". A caller
     * may call more often than asked; two calls for the same frame (two
     * bars on screen for a moment) count once.
     *
     * [loading] is [isCapsuleLoading]; [indeterminate] is a name resolve
     * or gateway warm-up, which has no percentage — the bar holds where
     * it is (the sweep is drawn instead) and the creep clock doesn't run.
     * [failed] is read as the load ends: a load that failed or was
     * stopped fades out where it stands instead of filling, so it is
     * never shown as a completed load (R1-M2). [generation] is the tab's
     * [BrowserState.loadGeneration]: a change while loading is a new
     * load, started over at the minimum even if no frame ever saw the
     * last one end (R2-M1).
     */
    fun frame(
        nowMs: Long,
        progress: Int,
        loading: Boolean,
        indeterminate: Boolean,
        failed: Boolean = false,
        generation: Int = 0,
    ): Long {
        if (running && nowMs == lastFrameMs && !reattached && !unseen) return lastWait
        var dt = if (running) (nowMs - lastFrameMs).coerceAtLeast(0L).toFloat() else 0f
        // Resting, nothing moved; resuming must not count the rest as one
        // long frame and jump.
        if (lastWait > 0L) dt = min(dt, CAPSULE_LOAD_FRAME_MS.toFloat())
        if (reattached || unseen) {
            val away = unseen || nowMs - lastFrameMs > CAPSULE_LOAD_REATTACH_GAP_MS
            reattached = false
            unseen = false
            if (running && away) {
                dt = 0f
                // Ended unseen: gone. Still loading, but reporting less
                // than before: a new load took over unseen — one the
                // generation doesn't mark (a form POST, which the WebView
                // follows without asking): start it over (R2-M1).
                if (!loading || (progress in 0 until lastProgress)) hide()
            }
        }
        lastFrameMs = nowMs
        lastWait = step(nowMs, progress, loading, indeterminate, failed, dt, generation)
        return lastWait
    }

    private fun step(
        nowMs: Long,
        progress: Int,
        loading: Boolean,
        indeterminate: Boolean,
        failed: Boolean,
        dt: Float,
        generation: Int,
    ): Long {
        if (loading) {
            if (!running || finishing || generation != lastGeneration) {
                // A new load — including one that starts while the last
                // one is still fading, or that took over from it with no
                // idle frame in between: back to the start, at once.
                lastGeneration = generation
                running = true
                finishing = false
                failedEnd = false
                fractionState.floatValue = CAPSULE_LOAD_MIN_FRACTION
                alphaState.floatValue = 1f
                floor = CAPSULE_LOAD_MIN_FRACTION
                lastProgress = progress
                lastIndeterminate = indeterminate
                lastChangeMs = nowMs
                return 0L
            }
            // The creep clock restarts on a new report, and while (and
            // just after) there is no percentage to creep from.
            if (progress != lastProgress || indeterminate || lastIndeterminate) {
                floor = max(floor, fraction)
                lastProgress = progress
                lastChangeMs = nowMs
            }
            lastIndeterminate = indeterminate
            val target = capsuleLoadTarget(progress, floor, nowMs - lastChangeMs)
            if (target - fraction > 2 * CAPSULE_LOAD_STEP) {
                fractionState.floatValue = approach(fraction, target, dt, CAPSULE_LOAD_FOLLOW_MS)
                return 0L
            }
            // Close enough: settle on the target and rest until the creep
            // has moved it another step, or an input changes.
            if (target > fraction) fractionState.floatValue = target
            if (indeterminate) return Long.MAX_VALUE
            val next = capsuleCreepReachMs(progress, floor, fraction + CAPSULE_LOAD_STEP)
                ?: return Long.MAX_VALUE
            return (lastChangeMs + next - nowMs).coerceAtLeast(CAPSULE_LOAD_FRAME_MS)
        }
        if (!running) return Long.MAX_VALUE
        if (!finishing) {
            finishing = true
            failedEnd = failed
        }
        // The load is over: fill to the end — unless it failed or was
        // stopped — then fade.
        if (!failedEnd && fraction < 1f) {
            val next = approach(fraction, 1f, dt, CAPSULE_LOAD_FILL_MS)
            fractionState.floatValue = if (next > 0.995f) 1f else next
            return 0L
        }
        val nextAlpha = alpha - dt / CAPSULE_LOAD_FADE_MS
        if (nextAlpha <= 0f) {
            hide()
            return Long.MAX_VALUE
        }
        alphaState.floatValue = nextAlpha
        return 0L
    }

    private fun hide() {
        running = false
        finishing = false
        failedEnd = false
        alphaState.floatValue = 0f
        fractionState.floatValue = 0f
    }

    /** Exponential follow from [from] towards [to]; never moves backwards. */
    private fun approach(from: Float, to: Float, dt: Float, tauMs: Float): Float {
        if (to <= from) return from
        return from + (to - from) * (1f - exp(-dt / tauMs))
    }
}

/**
 * Whether the load that just ended didn't complete: it failed (the tab
 * now shows an error page) or the user stopped it.
 */
internal fun capsuleLoadFailed(state: BrowserState): Boolean = state.showsErrorPage || state.loadAborted

/** What the bar's frame loop reads from the tab; a change wakes a resting bar. */
private data class CapsuleLoadInputs(
    val progress: Int,
    val loading: Boolean,
    val resolving: Boolean,
    val generation: Int,
) {
    constructor(state: BrowserState) :
        this(state.progress, isCapsuleLoading(state), state.resolving, state.loadGeneration)
}

/**
 * Phase of the indeterminate sweep, 0..1 per pass. Its own composable so
 * the infinite transition only exists while a tab is actually resolving.
 */
@Composable
private fun rememberCapsuleSweep(): State<Float> {
    val transition = rememberInfiniteTransition(label = "capsuleSweep")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(CAPSULE_SWEEP_PERIOD_MS, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "capsuleSweepPhase",
    )
}

/**
 * Page-load progress the way Chrome for Android shows it: a thin
 * primary-coloured bar along the **bottom edge** of the field
 * [modifier] sizes it to, growing from the leading edge (the trailing
 * one in RTL), clipped to the field's own [shape] so its ends follow the
 * rounded corners, and drawn over the field's hairline border.
 *
 * - It starts at [CAPSULE_LOAD_MIN_FRACTION] the moment a load begins,
 *   follows the page's progress smoothly, and creeps slowly while the
 *   progress stalls (see [capsuleLoadTarget]), never reaching the end on
 *   its own.
 * - While a name resolves or a gateway warms up ([BrowserState.resolving])
 *   there is no percentage, so a short segment sweeps along the same line.
 * - When the load ends it fills to 100 % and fades out; a load that
 *   failed or was stopped ([capsuleLoadFailed]) fades where it stands.
 *
 * Shared by the address field and the find bar, which stands in for the
 * capsule while it is open, so a load started with the bar up still shows
 * (#83). Compose it unconditionally: it draws nothing while idle, and it
 * has to outlive the load to fade. Only the loading/idle boundary
 * ([isCapsuleLoading], derived) reaches composition; the progress itself
 * is read on the frame clock and the result in the draw phase, so a
 * ticking load never recomposes anything (#279).
 *
 * No semantics of its own: TalkBack hears the load on the address field
 * ([capsuleLoadStateDescription]), and a node here, drawn over the field,
 * would hide the field and its controls from accessibility. It takes no
 * pointer input, so the controls underneath still get every tap.
 */
@Composable
internal fun CapsuleLoadBar(state: BrowserState, shape: Shape, modifier: Modifier = Modifier) {
    val loading by remember(state) { derivedStateOf { isCapsuleLoading(state) } }
    val meter = state.loadMeter
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(meter, lifecycle) {
        meter.attach()
        // The app going out of sight stops the frame clock but not the
        // load: see [CapsuleLoadMeter.unwatched].
        // Told on the way back (ON_START after an ON_STOP), so the very
        // next frame is the one that resumes, whatever ran while stopped.
        var stopped = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> stopped = true
                Lifecycle.Event.ON_START -> if (stopped) {
                    stopped = false
                    meter.unwatched()
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            meter.detach()
        }
    }
    // Runs only while there is something to show: from a load's start to
    // the end of its fade. An idle bar is off the frame clock entirely,
    // and a bar at rest (settled on its target, or between two steps of
    // the stall creep) sleeps until the meter's next step is due or an
    // input changes, instead of drawing every frame (R1-F1).
    LaunchedEffect(meter, loading) {
        if (!loading && !meter.visible) return@LaunchedEffect
        while (true) {
            val now = withFrameMillis { it }
            val seen = CapsuleLoadInputs(state)
            val wait = meter.frame(
                now, seen.progress, seen.loading, seen.resolving, capsuleLoadFailed(state), seen.generation,
            )
            if (!seen.loading && !meter.visible) break
            if (wait > 0L) {
                withTimeoutOrNull(wait) { snapshotFlow { CapsuleLoadInputs(state) }.first { it != seen } }
            }
        }
    }
    val sweep: State<Float>? = if (loading && state.resolving) rememberCapsuleSweep() else null
    val color = MaterialTheme.colorScheme.primary
    val barPx = with(LocalDensity.current) { CapsuleLoadBarHeight.toPx() }
    Box(
        modifier = modifier.drawWithCache {
            // The outline only changes with the field's size, so the clip
            // is built once per size here, not once per frame.
            val clip = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
            val bar = min(barPx, size.height)
            val leadIn = capsuleBarLeadIn(size, bar)
            val rtl = layoutDirection == LayoutDirection.Rtl
            onDrawBehind {
                val alpha = meter.alpha
                if (alpha <= 0f) return@onDrawBehind
                val track = size.width - leadIn
                val start: Float
                val end: Float
                if (sweep != null) {
                    val head = sweep.value * (1f + CAPSULE_SWEEP_WIDTH)
                    start = (head - CAPSULE_SWEEP_WIDTH).coerceIn(0f, 1f) * size.width
                    end = head.coerceIn(0f, 1f) * size.width
                } else {
                    start = 0f
                    end = leadIn + track * meter.fraction.coerceIn(0f, 1f)
                }
                if (end <= start) return@onDrawBehind
                val left = if (rtl) size.width - end else start
                clipPath(clip) {
                    drawRect(
                        color = color,
                        topLeft = Offset(left, size.height - bar),
                        size = Size(end - start, bar),
                        alpha = alpha,
                    )
                }
            }
        },
    )
}

/**
 * How far in from the leading end the bar first reaches its full
 * thickness on a pill whose ends are semicircles of the field's height —
 * the stretch of the rounded corner where the clipped bar is only a
 * tapering sliver. The determinate bar starts its 0..1 travel here, so
 * the 5 % minimum is a visible stroke rather than a hairline lost in the
 * corner, while 100 % still reaches the far end.
 */
internal fun capsuleBarLeadIn(size: Size, bar: Float): Float {
    val radius = min(size.height, size.width) / 2f
    if (radius <= 0f || bar <= 0f) return 0f
    val rise = (radius - bar).coerceAtLeast(0f)
    return radius - sqrt(radius * radius - rise * rise)
}
