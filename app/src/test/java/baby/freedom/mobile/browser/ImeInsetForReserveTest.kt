package baby.freedom.mobile.browser

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #481: [BrowserScreen] feeds [contentBottomReserve] a clamped IME inset
 * instead of the raw one, so the derived value stops changing once the
 * rising keyboard passes the navigation bar. The clamp must not change
 * any reserve.
 */
class ImeInsetForReserveTest {

    @Test
    fun `the clamped inset gives the same reserve as the raw one, in every mode`() {
        val footprints = listOf(
            CapsuleHeight + CapsuleBottomMargin,
            CapsuleEditingHeight + CapsuleBottomMargin,
        )
        for (navPx in listOf(0, 1, 63, 126)) {
            for (imePx in 0..900) {
                for (mode in BottomChromeMode.entries) {
                    for (footprint in footprints) {
                        val keyboard = imePx > 0
                        assertEquals(
                            "mode=$mode nav=$navPx ime=$imePx",
                            contentBottomReserve(mode, keyboard, footprint, navPx.dp, imePx.dp),
                            contentBottomReserve(
                                mode, keyboard, footprint, navPx.dp,
                                imeInsetForReserve(imePx, navPx).dp,
                            ),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `the clamped inset stops moving once the keyboard passes the navigation bar`() {
        val nav = 126
        val seen = (nav..1200).map { imeInsetForReserve(it, nav) }.toSet()
        assertEquals(setOf(nav), seen)
        assertEquals(40, imeInsetForReserve(40, nav))
        assertEquals(0, imeInsetForReserve(0, nav))
        assertEquals(0, imeInsetForReserve(500, 0))
    }
}
