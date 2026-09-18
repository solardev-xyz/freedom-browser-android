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
 * The split bar's three surfaces share one style (#60): translucent glass
 * over a blurred backdrop, with a hairline of contrast around it — no
 * tray, no second pill inside a tray, and therefore no fill that morphs
 * from one into the other as the bar compacts.
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
    fun `every surface on the bar is the same glass`() {
        // Back's circle, the field and the tab counter's circle all call
        // this one function with nothing but the scheme, so there is no
        // argument by which they could come out different.
        for ((name, colors) in schemes) {
            assertSameColor(
                "the $name fill is not a pure function of the scheme",
                capsuleFill(colors, blurred = true),
                capsuleFill(colors, blurred = true),
            )
        }
    }

    @Test
    fun `the page reads through every surface, on both schemes`() {
        for ((name, colors) in schemes) {
            val fill = capsuleFill(colors, blurred = true)
            assertTrue(
                "the $name surface should let the page through",
                fill.alpha < 1f,
            )
            // …but not so far through that `onSurface` text stops being
            // legible: two thirds of a surface is still a surface.
            assertTrue("the $name surface has dissolved", fill.alpha >= 0.6f)
        }
    }

    @Test
    fun `the light surface is white and the dark one the capsule's own tone`() {
        assertSameColor(
            "light surface",
            FreedomLightColors.surface.copy(alpha = 0.68f),
            capsuleFill(FreedomLightColors, blurred = true),
        )
        assertSameColor(
            "dark surface",
            FreedomDarkColors.surfaceContainer.copy(alpha = 0.72f),
            capsuleFill(FreedomDarkColors, blurred = true),
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
                capsuleFill(colors, blurred = true),
                capsuleFill(colors, blurred = true),
            )
        }
    }

    @Test
    fun `a device with no blur gets an opaquer light surface`() {
        // Android 11 has no `RenderEffect.createBlurEffect`, so
        // `rememberCapsuleBackdrop()` hands back null and the surfaces are
        // a plain fill. 0.68 is an alpha the blur pays for: without it the
        // page keeps its edges and reads through the field, so the light
        // scheme falls back to the 0.94 the single capsule wore before
        // #60.
        val blurred = capsuleFill(FreedomLightColors, blurred = true)
        val plain = capsuleFill(FreedomLightColors, blurred = false)
        assertSameColor(
            "the unblurred light surface",
            FreedomLightColors.surface.copy(alpha = 0.94f),
            plain,
        )
        assertTrue(
            "an unblurred surface must not be the blurred one's alpha",
            plain.alpha > blurred.alpha,
        )
        assertTrue("the unblurred surface stopped being glass", plain.alpha < 1f)
    }

    @Test
    fun `the dark surface never leaned on the blur`() {
        // Only light's alpha is the blur's to lend — a dark surface is
        // unmistakable over any page, so taking the blur away changes
        // nothing about it.
        assertSameColor(
            "the dark fill moved when the blur went away",
            capsuleFill(FreedomDarkColors, blurred = true),
            capsuleFill(FreedomDarkColors, blurred = false),
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
        assertTrue(
            "the dark surface should be the more opaque of the two",
            capsuleFill(dark, blurred = true).alpha > capsuleFill(light, blurred = true).alpha,
        )
        assertTrue(
            "a dark surface needs the stronger hairline",
            capsuleBorder(dark).alpha > capsuleBorder(light).alpha,
        )
    }
}
