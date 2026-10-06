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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
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
import kotlin.math.exp
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
 * The bar's state over one load and its fade-out, advanced one frame at a
 * time by [frame]. A plain class with no clock of its own so the whole
 * curve — the instant minimum, the smooth follow, the creep, the fill and
 * the fade — is testable without Compose.
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
    private var lastFrameMs = 0L
    private var lastProgress = Int.MIN_VALUE
    private var lastChangeMs = 0L
    private var floor = 0f

    /**
     * Advance to [nowMs] (a frame time). [loading] is
     * [isCapsuleLoading]; [indeterminate] is a name resolve or gateway
     * warm-up, which has no percentage — the bar holds where it is (the
     * sweep is drawn instead) and the creep clock doesn't run.
     */
    fun frame(nowMs: Long, progress: Int, loading: Boolean, indeterminate: Boolean) {
        val dt = if (running) (nowMs - lastFrameMs).coerceAtLeast(0L).toFloat() else 0f
        lastFrameMs = nowMs
        if (loading) {
            if (!running || finishing) {
                // A new load — including one that starts while the last
                // one is still fading: back to the start, at once.
                running = true
                finishing = false
                fractionState.floatValue = CAPSULE_LOAD_MIN_FRACTION
                alphaState.floatValue = 1f
                floor = CAPSULE_LOAD_MIN_FRACTION
                lastProgress = progress
                lastChangeMs = nowMs
                return
            }
            if (progress != lastProgress || indeterminate) {
                floor = max(floor, fraction)
                lastProgress = progress
                lastChangeMs = nowMs
            }
            val target = capsuleLoadTarget(progress, floor, nowMs - lastChangeMs)
            fractionState.floatValue = approach(fraction, target, dt, CAPSULE_LOAD_FOLLOW_MS)
            return
        }
        if (!running) return
        // The load is over: fill to the end, then fade.
        finishing = true
        if (fraction < 1f) {
            val next = approach(fraction, 1f, dt, CAPSULE_LOAD_FILL_MS)
            fractionState.floatValue = if (next > 0.995f) 1f else next
            return
        }
        val nextAlpha = alpha - dt / CAPSULE_LOAD_FADE_MS
        if (nextAlpha <= 0f) {
            running = false
            finishing = false
            alphaState.floatValue = 0f
            fractionState.floatValue = 0f
        } else {
            alphaState.floatValue = nextAlpha
        }
    }

    /** Exponential follow from [from] towards [to]; never moves backwards. */
    private fun approach(from: Float, to: Float, dt: Float, tauMs: Float): Float {
        if (to <= from) return from
        return from + (to - from) * (1f - exp(-dt / tauMs))
    }
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
 * - When the load ends it fills to 100 % and fades out.
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
    val meter = remember(state) { CapsuleLoadMeter() }
    // Runs only while there is something to show: from a load's start to
    // the end of its fade. An idle bar is off the frame clock entirely.
    LaunchedEffect(meter, loading) {
        if (!loading && !meter.visible) return@LaunchedEffect
        while (true) {
            val now = withFrameMillis { it }
            meter.frame(now, state.progress, isCapsuleLoading(state), state.resolving)
            if (!isCapsuleLoading(state) && !meter.visible) break
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
