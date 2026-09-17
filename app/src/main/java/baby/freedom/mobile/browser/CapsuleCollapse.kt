package baby.freedom.mobile.browser

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Height of the capsule in its compact (scrolled) state. The brief
 * allows 40–44 dp; 44 dp is the top of that band, which is what keeps
 * the domain label the same size it is at rest — the compact bar says
 * less, it must not say it *smaller* (see [AddressLabel]: the label is
 * a trust surface, and a shrunken domain is a harder one to read).
 */
internal val CapsuleCompactHeight = 44.dp

/** Address pill height inside the resting (56 dp) capsule. */
internal val AddressPillHeight = 40.dp

/** Address pill height inside the compact (44 dp) capsule. */
internal val AddressPillCompactHeight = 32.dp

/**
 * Touch height of the address pill. Constant across both states and
 * independent of the pill's *drawn* height, so shrinking the capsule
 * never shrinks a tap target: the field is laid out 48 dp tall inside
 * the toolbar's (equally constant) 48 dp control row and its pill is
 * painted at the interpolated height inside that box.
 */
internal val AddressFieldTouchHeight = 48.dp

/**
 * Downward travel, in dp, that collapses the capsule. Small enough to
 * feel like a direct response to the gesture, large enough that the
 * few px of drift a tap or a long-press produces doesn't trip it.
 */
private const val COLLAPSE_THRESHOLD_DP = 24f

/**
 * Upward travel that restores it. Deliberately shorter than the
 * collapse threshold: reaching for the address bar should feel
 * immediate, and the brief's "scroll up restores" is the recovery
 * gesture — it has to be the cheaper of the two.
 */
private const val EXPAND_THRESHOLD_DP = 12f

/**
 * Within this distance of the top of the document the capsule is
 * always at rest, whatever the accumulated travel says. The head of a
 * page is where the user has not committed to reading yet, and Safari
 * does the same.
 */
private const val TOP_ZONE_DP = 24f

/**
 * A single scroll callback larger than this isn't a finger — it's a
 * fragment jump, a scroll restore on back/forward, or a JS
 * `scrollIntoView`. Those move the accumulator nowhere: the user never
 * asked for a state change, so they don't get one.
 */
private const val JUMP_DP = 160f

/**
 * Compact-on-scroll state for one tab's floating capsule.
 *
 * `android.webkit.WebView` doesn't participate in Compose's
 * nested-scroll chain — it consumes touch itself and reports nothing
 * upwards — so the collapse can't be derived the way a `LazyColumn`
 * would drive a `TopAppBarScrollBehavior`. Instead
 * [BrowserWebViewHost] feeds this class the WebView's own
 * `onScrollChanged` deltas and it turns them into one Boolean the
 * chrome animates between.
 *
 * Travel accumulates per direction and resets on a reversal, so the
 * capsule reacts to *this* flick rather than to the net distance
 * travelled since the page loaded. State lives per tab (see
 * [BrowserState.capsuleCollapse]) because scroll position does.
 */
internal class CapsuleCollapseState {

    /** `true` while the capsule should be drawn in its compact state. */
    var collapsed by mutableStateOf(false)
        private set

    /**
     * Accumulated scroll in the current direction, in dp. Positive is
     * down the page (content moving up).
     */
    private var travelDp = 0f

    /**
     * Fold one WebView scroll callback into the state.
     *
     * [density] is `displayMetrics.density` (px per dp) — thresholds
     * are expressed in dp so the gesture feels the same on every
     * screen, while the WebView reports px.
     */
    fun onScroll(scrollY: Int, oldScrollY: Int, density: Float) {
        if (density <= 0f) return
        val deltaDp = (scrollY - oldScrollY) / density
        if (deltaDp == 0f) return

        // At (or just below) the top of the document the bar is always
        // whole, so a page that jumps back to the top — reload, anchor
        // link, back to a fresh document — always presents its full
        // chrome.
        if (scrollY / density <= TOP_ZONE_DP) {
            travelDp = 0f
            collapsed = false
            return
        }

        // Not a gesture: don't let a restored scroll position or a
        // scroll-into-view decide what the chrome does.
        if (abs(deltaDp) > JUMP_DP) {
            travelDp = 0f
            return
        }

        // Direction reversal starts a fresh run, so a flick back up
        // doesn't first have to repay everything the user scrolled
        // down.
        if (travelDp != 0f && (travelDp > 0f) != (deltaDp > 0f)) travelDp = 0f
        travelDp += deltaDp

        if (!collapsed && travelDp >= COLLAPSE_THRESHOLD_DP) {
            collapsed = true
            travelDp = 0f
        } else if (collapsed && travelDp <= -EXPAND_THRESHOLD_DP) {
            collapsed = false
            travelDp = 0f
        }
    }

    /**
     * Force the capsule back to its resting state — a new navigation,
     * a tab reset, going home. The next page starts with its chrome
     * whole no matter how far the previous document was scrolled.
     */
    fun expand() {
        travelDp = 0f
        collapsed = false
    }
}
