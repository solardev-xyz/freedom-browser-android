package baby.freedom.mobile.browser

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import baby.freedom.mobile.ui.FreedomDarkColors
import baby.freedom.mobile.ui.FreedomLightColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The compact capsule is one colour (#45): the address field's own fill,
 * opaque, with the resting state's translucent rim gone — and gone by
 * morphing into it, not by switching off at some fraction.
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

    /** Largest single-channel difference between two fills. */
    private fun distance(a: Color, b: Color): Float = maxOf(
        kotlin.math.abs(a.red - b.red),
        kotlin.math.abs(a.green - b.green),
        kotlin.math.abs(a.blue - b.blue),
        kotlin.math.abs(a.alpha - b.alpha),
    )

    @Test
    fun `the compact capsule is the address field's colour, opaque`() {
        for ((name, colors) in schemes) {
            val fill = capsuleFill(collapse = 1f, colors = colors)
            assertSameColor(
                "compact capsule on the $name scheme is not the pill's fill",
                colors.surfaceContainerHighest,
                fill,
            )
            // Opaque: a translucent compact capsule would lerp towards
            // whatever page is under it and stop being one colour.
            assertEquals("compact capsule on $name is translucent", 1f, fill.alpha, 0.001f)
        }
    }

    @Test
    fun `the compact capsule has no second colour at its edge`() {
        // What "no rim" means in one assertion: the outer surface and the
        // pill drawn inside it are the same colour, so the inset between
        // them cannot show.
        for ((name, colors) in schemes) {
            assertSameColor(
                "rim visible on the $name scheme",
                capsuleFill(collapse = 1f, colors = colors),
                colors.surfaceContainerHighest,
            )
        }
    }

    @Test
    fun `the resting capsule is untouched`() {
        // The two-surface look at rest is the shipped one: translucent
        // `surfaceContainer` at the scheme's own alpha.
        assertSameColor(
            "resting light capsule",
            FreedomLightColors.surfaceContainer.copy(alpha = 0.94f),
            capsuleFill(collapse = 0f, colors = FreedomLightColors),
        )
        assertSameColor(
            "resting dark capsule",
            FreedomDarkColors.surfaceContainer.copy(alpha = 0.90f),
            capsuleFill(collapse = 0f, colors = FreedomDarkColors),
        )
        for ((name, colors) in schemes) {
            assertTrue(
                "the resting $name capsule should let the page through",
                capsuleFill(0f, colors).alpha < 1f,
            )
        }
    }

    @Test
    fun `the rim dissolves rather than blinking out`() {
        // Continuity is the whole ask: no threshold where the rim
        // vanishes, no frame where a *different* colour flashes. Each
        // step moves every channel by less than a tenth of the distance
        // the whole morph covers.
        for ((name, colors) in schemes) {
            val resting = capsuleFill(0f, colors)
            val compact = capsuleFill(1f, colors)
            // What one of 20 even steps would cost if the morph were
            // perfectly linear, with half a step of slack for the
            // perceptual colour space the interpolation runs in and two
            // 8-bit steps for the quantisation an sRGB [Color] does on
            // the way back out.
            val budget = 1.5f * distance(resting, compact) / 20f + 2f / 255f
            var previous = resting
            for (step in 1..20) {
                val fill = capsuleFill(step / 20f, colors)
                assertTrue(
                    "the $name fill jumped at step $step",
                    distance(previous, fill) <= budget,
                )
                previous = fill
            }
        }
    }

    @Test
    fun `the capsule grows more opaque all the way down, never less`() {
        for ((name, colors) in schemes) {
            var previous = capsuleFill(0f, colors).alpha
            for (step in 1..20) {
                val alpha = capsuleFill(step / 20f, colors).alpha
                assertTrue("the $name capsule thinned out at step $step", alpha >= previous - 1e-4f)
                previous = alpha
            }
            assertEquals("$name never reaches opaque", 1f, previous, 0.001f)
        }
    }

    @Test
    fun `an overshooting spring cannot push the fill past either end`() {
        // Both fractions come off expressive springs, which overshoot at
        // both ends — the fill has to stay inside its band regardless.
        for ((name, colors) in schemes) {
            assertSameColor(
                "$name fill past compact",
                capsuleFill(1f, colors),
                capsuleFill(1.08f, colors),
            )
            assertSameColor(
                "$name fill past resting",
                capsuleFill(0f, colors),
                capsuleFill(-0.08f, colors),
            )
        }
    }

    @Test
    fun `the fill reads the scheme it is painting with, not the system flag`() {
        // The alphas differ per scheme (a pale capsule needs more of
        // itself against a white page), and [ColorScheme.isLight] is what
        // decides — so a scheme swap is all it takes.
        val light: ColorScheme = FreedomLightColors
        val dark: ColorScheme = FreedomDarkColors
        assertTrue(
            "the light capsule should be the more opaque of the two at rest",
            capsuleFill(0f, light).alpha > capsuleFill(0f, dark).alpha,
        )
    }
}
