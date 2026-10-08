package baby.freedom.mobile.browser

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
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
        for (audio in listOf(true, false)) for (openLv in listOf(true, false)) for (device in listOf(true, false)) {
            assertFalse(webViewTimersPaused(appStarted = true, anyAudio = audio, openLvLive = openLv, deviceAudio = device))
        }
    }

    @Test
    fun `timers pause in the background with nothing playing and no OpenLV session`() {
        assertTrue(webViewTimersPaused(appStarted = false, anyAudio = false, openLvLive = false, deviceAudio = false))
    }

    @Test
    fun `timers keep running in the background for audio or an OpenLV session`() {
        assertFalse(webViewTimersPaused(appStarted = false, anyAudio = true, openLvLive = false, deviceAudio = false))
        assertFalse(webViewTimersPaused(appStarted = false, anyAudio = false, openLvLive = true, deviceAudio = false))
    }

    @Test
    fun `timers keep running in the background while the device plays audio no tab reports`() {
        // A detached new Audio(), Web Audio or shadow-root media: no tab's playingAudio, but a player is active.
        assertFalse(webViewTimersPaused(appStarted = false, anyAudio = false, openLvLive = false, deviceAudio = true))
    }

    @Test
    fun `a pause waits out the grace and a gap between tracks never pauses`() = runTest {
        val wanted = MutableSharedFlow<Boolean>()
        val seen = mutableListOf<Boolean>()
        val job = launch { wanted.pausedAfterGrace(30_000).collect { seen += it } }
        runCurrent()
        wanted.emit(false)
        runCurrent()
        assertEquals(listOf(false), seen)
        // Track ends: nothing plays for 800 ms, then the next one starts.
        wanted.emit(true)
        advanceTimeBy(800)
        wanted.emit(false)
        advanceTimeBy(60_000)
        assertEquals(listOf(false), seen)
        // The playlist is over: the pause lands after the grace, not before.
        wanted.emit(true)
        advanceTimeBy(29_999)
        assertEquals(listOf(false), seen)
        advanceTimeBy(2)
        assertEquals(listOf(false, true), seen)
        // Back to the app: resumed at once.
        wanted.emit(false)
        runCurrent()
        assertEquals(listOf(false, true, false), seen)
        job.cancel()
    }

    @Test
    fun `a pause wanted with no WebView lands on the first one made`() {
        val calls = mutableListOf<String>()
        val timers = ProcessTimers<String>(pause = { calls += "pause $it" }, resume = { calls += "resume $it" })
        timers.set(true, null)
        assertEquals(emptyList<String>(), calls)
        assertTrue(timers.wanted)
        assertFalse(timers.paused)
        timers.created("a")
        assertEquals(listOf("pause a"), calls)
        timers.created("b") // already paused: process-wide, once is enough
        assertEquals(listOf("pause a"), calls)
        timers.set(false, null) // resume with none left: the throwaway path
        assertEquals(listOf("pause a", "resume null"), calls)
        timers.created("c") // nothing pending
        assertEquals(listOf("pause a", "resume null"), calls)
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
