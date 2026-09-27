package baby.freedom.mobile.browser

import android.view.MotionEvent
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
//  1. The finger pushes on at the end: either
//     - a touch goes down while the document is already at its vertical
//       end (a page that can't scroll at all counts), the finger moves
//       up past the touch slop and Chromium reports the drag as
//       unconsumed overscroll at the bottom edge; or
//     - a drag that went down mid-page carries the page to its end and
//       keeps going (#138): Chromium's first unconsumed bottom
//       overscroll while that finger is still down arms it, and the
//       next move up takes over, anchored where the finger was then.
//     Unconsumed: the page's own handlers (a map, a canvas, an inner
//     scroller) didn't take the drag. A fling that lands at the end is
//     not a push: its overscroll comes after the finger lifted, when
//     nothing is watching, so nothing arms until the next touch.
//  2. The reveal takes the gesture over (the WebView gets an
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
 * stays valid (no clamp) and nothing on screen moves. Or once they are
 * back at the top ([scrollYPx] 0), where an offset of 0 stays valid
 * too: a page that gained H or less of range from the reveal (a short
 * page) can never get H from its end, and would otherwise stay revealed
 * with its top scrolled away until the next document.
 */
internal fun revealShouldRestore(distanceFromEndPx: Int, scrollYPx: Int, revealPx: Int): Boolean =
    distanceFromEndPx > revealPx || scrollYPx <= 0

/**
 * How far short of the reveal's scroll ([scrollFromPx] + [shrunkByPx])
 * Chromium's offset ends up once the page reports its new scroll range
 * [newRangePx]: 0 when the page gained the whole shrink as range (a long
 * page), up to the whole shrink for a page that gained none. The
 * handover keeps this much of the held translation and settles it away
 * instead of jumping.
 */
internal fun revealShortfall(scrollFromPx: Int, shrunkByPx: Int, newRangePx: Int): Int =
    (scrollFromPx + shrunkByPx - newRangePx.coerceAtLeast(0)).coerceIn(0, shrunkByPx.coerceAtLeast(0))

/**
 * Numbers the reveals' handovers, so a delayed callback (the handover
 * timeout) can tell whether the handover it was posted for is still
 * the current one. A timeout left over from an earlier reveal (commit,
 * scroll up to restore, push again, all within the timeout) must not
 * end the next reveal's handover early. UI thread only.
 */
internal class RevealGeneration {
    private var current = 0

    /** A new handover starts; returns its tag. */
    fun next(): Int = ++current

    fun isCurrent(tag: Int): Boolean = tag == current
}

/**
 * Is a clamped overscroll Chromium just reported past the document's
 * *bottom* edge? [deltaY] is the unconsumed scroll delta Chromium asked
 * the view to move by (positive = towards the end). The clamp alone
 * can't tell: on a page with no scroll range, a pull down at the top
 * clamps with `canScrollVertically(1)` false just the same — that one
 * is the top edge, and must not arm a reveal (#144 review).
 */
internal fun overscrollPastEnd(deltaY: Int, clampedY: Boolean, canScrollDown: Boolean): Boolean =
    clampedY && deltaY > 0 && !canScrollDown

/** The other edge: a clamped overscroll moving towards the document's top. */
internal fun overscrollPastTop(deltaY: Int, clampedY: Boolean, canScrollUp: Boolean): Boolean =
    clampedY && deltaY < 0 && !canScrollUp

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
 * the page's bottom edge, sampled when the reveal armed (the touch
 * down at the end, or the drag reaching it) — the page as it runs out
 * under the bar (its footer's background, or `<html>`'s below a short
 * page). Anything the sampling caught that isn't page (a
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

/**
 * Does this event end a reveal drag that follows the finger at pointer
 * index [trackedIndex] (-1: not in this event)? The gesture ending, or
 * the pushing finger lifting while another stays down: the commit is
 * then decided on its offset, rather than the drag jumping to the other
 * finger's position (#70 review). Another finger landing or lifting
 * changes nothing.
 */
internal fun revealDragReleased(actionMasked: Int, actionIndex: Int, trackedIndex: Int): Boolean =
    actionMasked == MotionEvent.ACTION_UP || actionMasked == MotionEvent.ACTION_CANCEL ||
        (actionMasked == MotionEvent.ACTION_POINTER_UP && actionIndex == trackedIndex)

/**
 * The window row to sample a reveal's tint from: the page's last row
 * that isn't under the navigation bar. The bar's own rows can carry the
 * system's translucent contrast scrim (3-button navigation), which is
 * drawn into the app's window, so a sample there reads page ⊕ scrim
 * (#70 review: a blue footer came out grey). One row above the inset is
 * inside the capsule's bottom margin, where only the page is. Clamped
 * to the view, for a view that doesn't reach the navigation bar.
 */
internal fun revealSampleRowY(viewTopPx: Int, viewHeightPx: Int, windowHeightPx: Int, navInsetPx: Int): Int {
    val viewBottom = viewTopPx + viewHeightPx
    val clearBottom = minOf(viewBottom, windowHeightPx - navInsetPx.coerceAtLeast(0))
    return (clearBottom - 1).coerceIn(viewTopPx, viewBottom - 1)
}

/**
 * The pixels of a sampled row ([revealSampleRowY]) that vote on the
 * tint: the [edgePx] columns at either end, inside the capsule's side
 * margins. The row lies in the capsule's bottom margin, where the
 * capsule's shadow falls off with one shade across the capsule's whole
 * width, so over a wide (expanded) capsule that shade could outvote the
 * page (#70 review: a white page read (254, 254, 254)). The edges are
 * clear of it. The whole row for an [edgePx] that leaves no middle.
 *
 * Deliberate trade-off: only the outermost [edgePx] of the row votes,
 * so the tint is the colour at the page's *sides*, not the row's
 * dominant colour. A last band that is a centred card between body
 * gutters (a white footer card on a grey body) tints the strip with the
 * gutter grey rather than the card white. That matches what frames the
 * lifted capsule at the screen edges, and is preferred over a
 * whole-row vote the capsule's shadow can swing.
 */
internal fun revealTintPixels(row: IntArray, edgePx: Int): IntArray {
    if (edgePx <= 0 || 2 * edgePx >= row.size) return row
    return row.copyOfRange(0, edgePx) + row.copyOfRange(row.size - edgePx, row.size)
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

        /**
         * A touch went down mid-page (a reveal allowed); watching for the
         * drag to overscroll the end with the finger still down.
         */
        Tracking,

        /** A touch went down at the end, or the drag reached it; watching for a push. */
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
    private var lastX = 0f
    private var lastY = 0f
    private var startX = 0f
    private var startY = 0f
    private var overscrolled = false

    // Has the drag left the slop around the down yet? The first move out
    // of it decides the gesture's axis: a mostly sideways one is the
    // page's (a carousel, a horizontal scroller) for good.
    private var axisDecided = false

    // Armed by a drag that reached the end ([onBottomOverscroll] from
    // [Phase.Tracking]) rather than by a touch that went down there.
    private var armedMidDrag = false

    /**
     * A touch went down at ([x], [y]) (raw screen px). Arms only if the
     * document is at its end and a reveal is [allowed]; mid-page (and
     * allowed) it starts [Phase.Tracking] the drag instead, which arms
     * if the drag reaches the end. Returns true when armed here (the
     * moment to sample the page's bottom row).
     */
    fun onDown(x: Float, y: Float, atEnd: Boolean, allowed: Boolean): Boolean {
        overscrolled = false
        armedMidDrag = false
        axisDecided = false
        if (phase == Phase.Armed || phase == Phase.Tracking) phase = Phase.Idle
        if (phase != Phase.Idle || !allowed) return false
        downX = x
        downY = y
        lastX = x
        lastY = y
        phase = if (atEnd) Phase.Armed else Phase.Tracking
        return atEnd
    }

    /**
     * Chromium reported unconsumed overscroll past the bottom edge. Only
     * counts while a finger is down (Armed or Tracking): a fling's
     * overscroll comes after the finger lifted, in Idle. Returns true if
     * this arms a drag that went down mid-page (#138) — the page is at
     * its end now, the moment to sample its bottom row.
     */
    fun onBottomOverscroll(): Boolean {
        when (phase) {
            Phase.Armed -> overscrolled = true
            Phase.Tracking -> {
                phase = Phase.Armed
                armedMidDrag = true
                overscrolled = true
                // The rubber band starts from where the finger was when
                // the page ran out, so the takeover doesn't jump.
                startX = lastX
                startY = lastY
                return true
            }
            else -> {}
        }
        return false
    }

    /**
     * Chromium reported unconsumed overscroll past the *top* edge. While
     * [Phase.Tracking] (a drag that went down mid-page, or went down
     * first from the end), the drag is pulling the page down against
     * its top — on a page with no scroll range, straight from the
     * touch down. It isn't heading for the end, so it stays the page's
     * for the rest of the gesture, as before #138: otherwise its first
     * move back up would overscroll the (same) bottom edge, arm, and
     * take over a drag that was a pull down (#144 review). An at-down
     * [Phase.Armed] slot ignores it: a jitter down inside the slop
     * before a push mustn't disarm it.
     */
    fun onTopOverscroll() {
        if (phase == Phase.Tracking) dropGesture()
    }

    /**
     * The finger is at ([x], [y]). Returns true when the reveal takes the
     * gesture over *on this event*.
     *
     * Any drag whose first move out of the [slopPx] is mostly sideways
     * is a horizontal gesture and stays the page's: back to Idle, for the
     * rest of the gesture — a vertical drift in it overscrolling the end
     * doesn't re-arm (#144 review).
     *
     * Armed at the touch down: an upward, mostly vertical move past
     * [slopPx], with the page having let the drag through (overscroll).
     * A move that goes down first disarms — back to [Phase.Tracking]:
     * the same drag may still reach the end later (unless it overscrolls
     * the top, see [onTopOverscroll]). One that turns mostly sideways
     * drops to Idle.
     *
     * Armed mid-drag: the finger is long past the slop, so the next move
     * up takes over, as long as the drag since it was armed is mostly
     * vertical; one that turns mostly sideways past the slop drops to
     * Idle, and a move down (the page scrolling back up) goes back to
     * tracking.
     */
    fun onMove(x: Float, y: Float, slopPx: Float): Boolean {
        val prevY = lastY
        lastX = x
        lastY = y
        if (phase != Phase.Armed && phase != Phase.Tracking) return false
        val up = downY - y
        val side = abs(x - downX)
        if (!axisDecided && (side > slopPx || abs(up) > slopPx)) {
            axisDecided = true
            if (side > abs(up)) {
                dropGesture()
                return false
            }
        }
        if (phase != Phase.Armed) return false
        if (armedMidDrag) {
            val upSince = startY - y
            val sideSince = abs(x - startX)
            when {
                sideSince > slopPx && sideSince > abs(upSince) -> dropGesture()
                y > prevY -> disarmToTracking()
                y < prevY && upSince > sideSince -> {
                    phase = Phase.Dragging
                    return true
                }
            }
            return false
        }
        if (side > slopPx && side > abs(up)) {
            dropGesture()
            return false
        }
        if (up < -slopPx) {
            disarmToTracking()
            return false
        }
        if (up <= slopPx || !overscrolled) return false
        phase = Phase.Dragging
        startY = y
        return true
    }

    private fun disarmToTracking() {
        phase = Phase.Tracking
        armedMidDrag = false
        overscrolled = false
    }

    /** The gesture is the page's (sideways): nothing more until the next touch down. */
    private fun dropGesture() {
        phase = Phase.Idle
        armedMidDrag = false
        overscrolled = false
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
            phase != Phase.Dragging -> if (phase == Phase.Armed || phase == Phase.Tracking) Phase.Idle else phase
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
    fun onScroll(distanceFromEndPx: Int, scrollYPx: Int, revealPx: Int): Boolean {
        if (phase != Phase.Revealed || !revealShouldRestore(distanceFromEndPx, scrollYPx, revealPx)) return false
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
        armedMidDrag = false
    }
}
