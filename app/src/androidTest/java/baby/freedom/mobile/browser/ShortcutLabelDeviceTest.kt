package baby.freedom.mobile.browser

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The launcher label's caps against the device's own BreakIterator, which
 * (unlike the JVM's) counts a ZWJ chain of pictographs as one character
 * (#401 R3-M1).
 */
@RunWith(AndroidJUnit4::class)
class ShortcutLabelDeviceTest {
    private val url = "https://example.org/"
    private val man = "👨"
    private val family = "👨‍👩‍👧"

    @Test
    fun aLongZwjChainIsCutAtTheCharCap() {
        val label = homeScreenShortcutLabel("$man‍".repeat(300) + man, url)
        val body = label.removeSuffix("…")
        assertTrue(label.endsWith("…"))
        assertTrue(body.length <= SHORTCUT_LABEL_CHARS)
        assertTrue(body.endsWith(man))
        assertFalse(body.endsWith("‍"))
    }

    @Test
    fun aLongTagRunIsCutAtTheCharCap() {
        val label = homeScreenShortcutLabel("🏴" + "󠁧".repeat(1000), url)
        assertTrue(label.endsWith("󠁧…"))
        assertTrue(label.length <= SHORTCUT_LABEL_CHARS + 1)
    }

    @Test
    fun thirtyTwoFamiliesAreThirtyTwoCharacters() {
        assertEquals(family.repeat(SHORTCUT_LABEL_MAX), homeScreenShortcutLabel(family.repeat(SHORTCUT_LABEL_MAX), url))
        assertEquals(family.repeat(SHORTCUT_LABEL_MAX) + "…", homeScreenShortcutLabel(family.repeat(SHORTCUT_LABEL_MAX) + "x", url))
    }
}
