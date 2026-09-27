package baby.freedom.mobile.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TabAudioFramesTest {

    @Test
    fun `a tab is audible while any of its frames is`() {
        val frames = TabAudioFrames<String>()
        assertFalse(frames.any)
        assertTrue(frames.onReport("top", true))
        assertTrue(frames.onReport("embed", true))
        assertTrue(frames.onReport("top", false))
        assertFalse(frames.onReport("embed", false))
    }

    @Test
    fun `a silent report from a frame that never was audible changes nothing`() {
        val frames = TabAudioFrames<String>()
        frames.onReport("top", true)
        assertTrue(frames.onReport("ad", false))
        frames.clear()
        assertFalse(frames.any)
    }
}
