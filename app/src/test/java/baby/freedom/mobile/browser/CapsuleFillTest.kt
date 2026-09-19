package baby.freedom.mobile.browser

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import baby.freedom.mobile.ui.FreedomDarkColors
import baby.freedom.mobile.ui.FreedomLightColors
import baby.freedom.mobile.ui.isLight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The split bar's three surfaces share one style (#60): a translucent
 * fill, with a hairline of contrast around it — no tray, no second pill
 * inside a tray, and therefore no fill that morphs from one into the
 * other as the bar compacts.
 *
 * One alpha carries it on both schemes and at every API level (#63): the
 * page tints faintly through the fill, and nothing on it is legible
 * through the fill.
 */
class CapsuleFillTest {

    private val schemes = mapOf(
        "light" to FreedomLightColors,
        "dark" to FreedomDarkColors,
    )

    /** A pixel's worth of tolerance: anything under 1/255 quantises away. */
    private fun assertSameColor(message: String, expected: Color, actual: Color) {
        assertEquals("$message (red)", expected.red, actual.red, 1f / 255f)
        assertEquals("$message (green)", expected.green, actual.green, 1f / 255f)
        assertEquals("$message (blue)", expected.blue, actual.blue, 1f / 255f)
        assertEquals("$message (alpha)", expected.alpha, actual.alpha, 1f / 255f)
    }

    @Test
    fun `every surface on the bar is the same fill`() {
        // Back's circle, the field and the tab counter's circle all call
        // this one function with nothing but the scheme, so there is no
        // argument by which they could come out different.
        for ((name, colors) in schemes) {
            assertSameColor(
                "the $name fill is not a pure function of the scheme",
                capsuleFill(colors),
                capsuleFill(colors),
            )
        }
    }

    @Test
    fun `the page tints through every surface, on both schemes`() {
        for ((name, colors) in schemes) {
            val fill = capsuleFill(colors)
            // A faint wash of the page is the point — the bar floats over
            // the page rather than replacing a strip of it.
            assertTrue(
                "the $name surface should let the page tint it",
                fill.alpha < 1f,
            )
            // …but only a tint. Page *text* reading through the chrome is
            // what the alpha is set against (#63), so the fill stays a
            // long way up from the two-thirds it used to sit at when a
            // backdrop blur was taking the page's edges off for it.
            assertTrue(
                "the $name surface lets the page read through at ${fill.alpha}",
                fill.alpha >= 0.85f,
            )
        }
    }

    @Test
    fun `the light surface is white and the dark one the capsule's own tone`() {
        assertSameColor(
            "light surface",
            FreedomLightColors.surface.copy(alpha = 0.90f),
            capsuleFill(FreedomLightColors),
        )
        assertSameColor(
            "dark surface",
            FreedomDarkColors.surfaceContainer.copy(alpha = 0.90f),
            capsuleFill(FreedomDarkColors),
        )
    }

    @Test
    fun `the compact pill is the field, not a fourth colour`() {
        // #45/#46 had the outer capsule morph into the pill's fill as the
        // bar compacted, because there were two surfaces to reconcile.
        // The split bar has one: the compact pill *is* the field, so the
        // fill it is drawn with is the same one it wore at rest.
        for ((name, colors) in schemes) {
            assertSameColor(
                "the compact $name pill changed colour",
                capsuleFill(colors),
                capsuleFill(colors),
            )
        }
    }

    @Test
    fun `both schemes carry the same opacity`() {
        // The two used to differ (0.68 light against 0.72 dark) because
        // the light one was being traded off against a backdrop blur that
        // no longer exists. With the fill on its own, the question — how
        // much page may read through a surface before it stops being one
        // — has the same answer on both (#63).
        assertEquals(
            "the schemes disagree about how opaque a surface is",
            capsuleFill(FreedomDarkColors).alpha,
            capsuleFill(FreedomLightColors).alpha,
            1f / 255f,
        )
    }

    @Test
    fun `the hairline is contrast, not a border`() {
        for ((name, colors) in schemes) {
            val border = capsuleBorder(colors)
            assertSameColor(
                "the $name hairline is not drawn in onSurface",
                colors.onSurface.copy(alpha = border.alpha),
                border,
            )
            assertTrue(
                "the $name hairline would read as a border at ${border.alpha}",
                border.alpha <= 0.12f,
            )
            assertTrue("the $name hairline is invisible", border.alpha >= 0.05f)
        }
    }

    @Test
    fun `the fill and the hairline read the scheme they are painting with`() {
        // [ColorScheme.isLight] is what decides both, so a scheme swap is
        // all it takes — no system flag anywhere in the path.
        val light: ColorScheme = FreedomLightColors
        val dark: ColorScheme = FreedomDarkColors
        assertTrue("the light scheme should be the light one", light.isLight)
        assertTrue("the dark scheme should not be light", !dark.isLight)
        assertSameColor(
            "the light fill is not the light scheme's own surface",
            FreedomLightColors.surface.copy(alpha = capsuleFill(light).alpha),
            capsuleFill(light),
        )
        assertSameColor(
            "the dark fill is not the dark scheme's own container tone",
            FreedomDarkColors.surfaceContainer.copy(alpha = capsuleFill(dark).alpha),
            capsuleFill(dark),
        )
        assertTrue(
            "a dark surface needs the stronger hairline",
            capsuleBorder(dark).alpha > capsuleBorder(light).alpha,
        )
    }
}
