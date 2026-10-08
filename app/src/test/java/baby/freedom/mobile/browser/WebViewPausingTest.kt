package baby.freedom.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewPausingTest {
    @Test
    fun `the tab on screen runs while the app is`() {
        assertFalse(tabWebViewPaused(isActiveTab = true, appStarted = true, playingAudio = false))
    }

    @Test
    fun `a background tab is paused`() {
        assertTrue(tabWebViewPaused(isActiveTab = false, appStarted = true, playingAudio = false))
    }

    @Test
    fun `every tab is paused while the app is in the background`() {
        assertTrue(tabWebViewPaused(isActiveTab = true, appStarted = false, playingAudio = false))
        assertTrue(tabWebViewPaused(isActiveTab = false, appStarted = false, playingAudio = false))
    }

    @Test
    fun `a tab playing audio is never paused`() {
        for (active in listOf(true, false)) for (started in listOf(true, false)) {
            assertFalse(tabWebViewPaused(isActiveTab = active, appStarted = started, playingAudio = true))
        }
    }

    @Test
    fun `timers run while the app is on screen`() {
        for (audio in listOf(true, false)) for (openLv in listOf(true, false)) {
            assertFalse(webViewTimersPaused(appStarted = true, anyAudio = audio, openLvLive = openLv))
        }
    }

    @Test
    fun `timers pause in the background with nothing playing and no OpenLV session`() {
        assertTrue(webViewTimersPaused(appStarted = false, anyAudio = false, openLvLive = false))
    }

    @Test
    fun `timers keep running in the background for audio or an OpenLV session`() {
        assertFalse(webViewTimersPaused(appStarted = false, anyAudio = true, openLvLive = false))
        assertFalse(webViewTimersPaused(appStarted = false, anyAudio = false, openLvLive = true))
    }

    @Test
    fun `the latch calls pause and resume only on a change`() {
        val calls = mutableListOf<String>()
        val latch = PauseLatch()
        val set = { wanted: Boolean -> latch.set(wanted, { calls += "pause" }, { calls += "resume" }) }
        set(false) // starts resumed: nothing to do
        set(true)
        set(true)
        set(false)
        set(false)
        set(true)
        assertEquals(listOf("pause", "resume", "pause"), calls)
        assertTrue(latch.paused)
    }
}
