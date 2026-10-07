package baby.freedom.mobile.browser

import baby.freedom.mobile.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The way out of a full-screen page (#400, item 11): a ← Back arrow that
 * mirrors in RTL for pages the user navigated into, and a × Close only
 * for a page that is really a full-screen dialog.
 */
class PageExitTest {

    @Test
    fun `a page goes back by default, with an arrow that mirrors in RTL`() {
        val back = PageExit.Back
        assertEquals(R.string.common_back, back.label)
        assertEquals("AutoMirrored.Filled.ArrowBack", back.icon.name)
        assertTrue(back.icon.autoMirror)
    }

    @Test
    fun `a full-screen dialog closes with an x that doesn't mirror`() {
        val close = PageExit.Close
        assertEquals(R.string.common_close, close.label)
        assertEquals("Filled.Close", close.icon.name)
        assertFalse(close.icon.autoMirror)
    }

    @Test
    fun `there are only the two ways out`() {
        assertEquals(listOf(PageExit.Back, PageExit.Close), PageExit.entries)
    }
}
