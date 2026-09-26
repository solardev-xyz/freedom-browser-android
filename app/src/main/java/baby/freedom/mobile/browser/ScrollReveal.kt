package baby.freedom.mobile.browser

import kotlin.math.abs
import kotlin.math.exp

// Scroll-to-reveal (#65): the floating bar covers the last band of every
// page, and a WebView's scroll range can't be extended under it (#68,
// step 0). So only when the user is at the end of a page *and pushes on*
// does the page area shorten by the bar's footprint — the reserved-mode
// mechanism of #66, published as [BottomChromeMode.Revealed] — and the
// last band slides up above the bar.
//
// The gesture, in the WebView's own touch stream:
//
//  1. A touch goes down while the document is already at its vertical
//     end (a page that can't scroll at all counts). A fling that lands at
//     the end is not a push: the finger that flung it went down
//     mid-page, so nothing arms until the next touch.
//  2. The finger moves up past the touch slop and Chromium reports the
//     drag as unconsumed overscroll at the bottom edge — the page's own
//     handlers (a map, a canvas, an inner scroller) didn't take it. Only
//     then does the reveal take the gesture over (the WebView gets an
//     ACTION_CANCEL).
//  3. The page follows the finger with a draw-time `translationY`
//     (rubber-band resistance, clamped to the reveal height H): no
//     WebView resize per frame (#63).
//  4. Released at or past [REVEAL_COMMIT_FRACTION] of H it commits;
//     below, it springs back and nothing else changes.
//
// The handover from translation to the real resize is the WebView
// host's job (see `buildRefreshableWebView`); this file is the part of
// the decision that doesn't need a View, so it can be unit-tested.

/**
 * How far past the end (as a fraction of the reveal height) the page has
 * to have been pulled when the finger lifts for the reveal to commit.
 *
 * 0.4: with [rubberBand] that is about half a bar's height of finger
 * travel past the slop (−ln 0.6 ≈ 0.51 H, ~42 dp on a gesture-nav phone
 * with H = 82 dp). A stray wobble at the end of a read stays well under
 * it and springs back; a deliberate push crosses it without having to
 * haul the page through the stiff last third of the band.
 */
internal const val REVEAL_COMMIT_FRACTION = 0.4f

/**
 * The page's visual offset for [dragPx] of finger travel (up) past the
 * start of the reveal: `H·(1 − e^(−drag/H))`. Follows the finger 1:1 at
 * first, stiffens, and never exceeds [revealPx] (H).
 */
internal fun rubberBand(dragPx: Float, revealPx: Float): Float {
    if (revealPx <= 0f || dragPx <= 0f) return 0f
    return (revealPx * (1f - exp(-dragPx / revealPx))).coerceAtMost(revealPx)
}

/** Does a release at [offsetPx] commit the reveal? */
internal fun revealCommits(offsetPx: Float, revealPx: Float): Boolean =
    revealPx > 0f && offsetPx >= revealPx * REVEAL_COMMIT_FRACTION

/**
 * May a push at the end of this tab's page reveal it? Not in reserved
 * mode (already shortened) or while revealed, not while the address bar
 * has focus or the keyboard is up ([chromeEditing], from the chrome;
 * [keyboardVisible], from the WebView's own window insets), and not on
 * the home surface.
 */
internal fun revealAllowed(
    mode: BottomChromeMode,
    chromeEditing: Boolean,
    keyboardVisible: Boolean,
    isHome: Boolean,
): Boolean = mode == BottomChromeMode.Overlay && !chromeEditing && !keyboardVisible && !isHome

/**
 * Should a revealed page go back to overlay? Once the user has scrolled
 * up so that the end is more than [revealPx] away: growing the WebView
 * back by H then only shows more page below the fold, the scroll offset
 * stays valid (no clamp) and nothing on screen moves.
 */
internal fun revealShouldRestore(distanceFromEndPx: Int, revealPx: Int): Boolean =
    distanceFromEndPx > revealPx

/**
 * The `documentScrollsDown` input to [pullToRefreshArmed] while a page
 * is revealed. A page that could not scroll at all before the reveal
 * gains a little scroll range from it (the WebView got shorter); that
 * must not arm pull-to-refresh on a page that never had it (#56) — it
 * stays exactly as it was before the push.
 */
internal fun revealAdjustedScrollsDown(canScrollDown: Boolean, revealedFromUnscrollable: Boolean): Boolean =
    canScrollDown && !revealedFromUnscrollable

/**
 * The strip's colour for a reveal: the most common colour in a row of
 * the page's bottom edge, sampled at the touch that armed it — the page
 * as it runs out under the bar (its footer's background, or `<html>`'s
 * below a short page). Anything the sampling caught that isn't page (a
 * shadow's faint falloff, a scrollbar) loses the vote. Alpha is dropped:
 * the window's pixels are opaque. Null for an empty row.
 */
internal fun dominantRgb(pixels: IntArray): Int? {
    if (pixels.isEmpty()) return null
    val counts = HashMap<Int, Int>()
    var best = 0
    var bestCount = 0
    for (p in pixels) {
        val rgb = p and 0xFFFFFF
        val c = (counts[rgb] ?: 0) + 1
        counts[rgb] = c
        if (c > bestCount) {
            best = rgb
            bestCount = c
        }
    }
    return best
}

/** 0xRRGGBB → `rgb(r, g, b)`, the form [bottomStripArgb] reads. */
internal fun rgbString(rgb: Int): String =
    "rgb(${(rgb shr 16) and 0xFF}, ${(rgb shr 8) and 0xFF}, ${rgb and 0xFF})"

/**
 * One tab's reveal gesture and state. Single-threaded (UI thread: touch
 * events, scroll callbacks, layout).
 */
internal class ScrollRevealSlot {
    enum class Phase {
        /** Nothing going on. */
        Idle,

        /** A touch went down at the end; watching for a push. */
        Armed,

        /** The reveal owns the gesture; the page follows the finger. */
        Dragging,

        /** Released past the threshold; settling onto H, then committing. */
        Committing,

        /** Released short of the threshold; springing back. */
        SpringingBack,

        /** The page area is shortened ([BottomChromeMode.Revealed]). */
        Revealed,
    }

    var phase: Phase = Phase.Idle
        private set

    /** Did the page have no scroll range at all before it was revealed? See [revealAdjustedScrollsDown]. */
    var revealedFromUnscrollable: Boolean = false
        private set

    val revealed: Boolean get() = phase == Phase.Revealed

    private var downX = 0f
    private var downY = 0f
    private var startY = 0f
    private var overscrolled = false

    /**
     * A touch went down at ([x], [y]) (raw screen px). Arms only if the
     * document is at its end and a reveal is [allowed].
     */
    fun onDown(x: Float, y: Float, atEnd: Boolean, allowed: Boolean): Boolean {
        overscrolled = false
        if (phase == Phase.Armed) phase = Phase.Idle
        if (phase != Phase.Idle || !atEnd || !allowed) return false
        downX = x
        downY = y
        phase = Phase.Armed
        return true
    }

    /** Chromium reported unconsumed overscroll past the bottom edge during this touch. */
    fun onBottomOverscroll() {
        if (phase == Phase.Armed) overscrolled = true
    }

    /**
     * The finger is at ([x], [y]). Returns true when the reveal takes the
     * gesture over *on this event*: an upward, mostly vertical move past
     * [slopPx], with the page having let the drag through (overscroll).
     * A move that goes down or sideways first disarms.
     */
    fun onMove(x: Float, y: Float, slopPx: Float): Boolean {
        if (phase != Phase.Armed) return false
        val up = downY - y
        val side = abs(x - downX)
        if (up < -slopPx || (side > slopPx && side > abs(up))) {
            phase = Phase.Idle
            return false
        }
        if (up <= slopPx || !overscrolled) return false
        phase = Phase.Dragging
        startY = y
        return true
    }

    /** The page's offset for the finger at [y] while [Phase.Dragging]; 0 otherwise. */
    fun dragOffset(y: Float, revealPx: Float): Float =
        if (phase == Phase.Dragging) rubberBand(startY - y, revealPx) else 0f

    /**
     * The finger lifted (or the gesture was cancelled) at [offsetPx].
     * Returns the phase it settles in: [Phase.Committing] or
     * [Phase.SpringingBack] if the reveal owned the gesture, else Idle.
     */
    fun onRelease(offsetPx: Float, revealPx: Float, cancelled: Boolean = false): Phase {
        phase = when {
            phase != Phase.Dragging -> if (phase == Phase.Armed) Phase.Idle else phase
            !cancelled && revealCommits(offsetPx, revealPx) -> Phase.Committing
            else -> Phase.SpringingBack
        }
        return phase
    }

    /** The spring-back finished. */
    fun onSprungBack() {
        if (phase == Phase.SpringingBack) phase = Phase.Idle
    }

    /**
     * The page area is shortened. [unscrollable]: the document had no
     * scroll range before the reveal.
     */
    fun onCommitted(unscrollable: Boolean) {
        if (phase != Phase.Committing) return
        phase = Phase.Revealed
        revealedFromUnscrollable = unscrollable
    }

    /**
     * A scroll while revealed: true if it should go back to overlay (see
     * [revealShouldRestore]). The next reveal needs a fresh push.
     */
    fun onScroll(distanceFromEndPx: Int, revealPx: Int): Boolean {
        if (phase != Phase.Revealed || !revealShouldRestore(distanceFromEndPx, revealPx)) return false
        reset()
        return true
    }

    /**
     * Back to overlay, whatever was going on: a new document, the tab
     * entering reserved mode, a width change (rotation).
     */
    fun reset() {
        phase = Phase.Idle
        revealedFromUnscrollable = false
        overscrolled = false
    }
}
