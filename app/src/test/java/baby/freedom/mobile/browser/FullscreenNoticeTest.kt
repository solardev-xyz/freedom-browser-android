package baby.freedom.mobile.browser

import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * HTML5 fullscreen names the site that took the screen and says how to
 * leave (#467), so a page can't pass a drawn copy of the browser off as
 * the real one.
 */
class FullscreenNoticeTest {

    private fun session(top: String?): TabsState.Fullscreen? {
        val tabs = TabsState(homepage = "ens://freedom.eth")
        tabs.active.permissionOrigin = top
        tabs.enterFullscreen(tabs.active, View(null), null)
        return tabs.fullscreen
    }

    @Test
    fun `a fullscreen session names the site that asked, as the user reads it`() {
        assertEquals("video.example", session("https://video.example")?.site)
        assertEquals("http://video.example", session("http://video.example")?.site)
        val ens = VirtualOrigin.originFor(ContentRoot.Ens("vitalik.eth"))!!
        assertEquals("vitalik.eth", session(permissionOriginKey("$ens/"))?.site)
    }

    @Test
    fun `a page with no site gets a session without a name`() {
        val fs = session(null)
        assertEquals(true, fs != null)
        assertNull(fs?.site)
    }

    @Test
    fun `the site is the one fullscreen started on, not where the tab goes next`() {
        val tabs = TabsState(homepage = "ens://freedom.eth")
        tabs.active.permissionOrigin = "https://video.example"
        tabs.enterFullscreen(tabs.active, View(null), null)
        tabs.active.permissionOrigin = "https://other.example"
        assertEquals("video.example", tabs.fullscreen?.site)
    }

    @Test
    fun `the notice shows once at the start`() {
        val notice = FullscreenNotice()
        assertEquals(1, notice.shows)
        // Bars visible, then hidden as fullscreen takes the screen.
        notice.onBarsVisible(true)
        notice.onBarsVisible(false)
        notice.onResumed() // the observer catching up with a resumed screen
        assertEquals(1, notice.shows)
    }

    @Test
    fun `the notice shows again each time the bars come back`() {
        val notice = FullscreenNotice()
        notice.onBarsVisible(false)
        notice.onBarsVisible(true)
        assertEquals(2, notice.shows)
        notice.onBarsVisible(true)
        assertEquals(2, notice.shows)
        notice.onBarsVisible(false)
        notice.onBarsVisible(true)
        assertEquals(3, notice.shows)
    }

    @Test
    fun `the notice shows again when the app comes back to the front`() {
        val notice = FullscreenNotice()
        notice.onResumed()
        assertEquals(1, notice.shows)
        notice.onResumed()
        assertEquals(2, notice.shows)
    }

    @Test
    fun `a touch at the top or bottom edge, where the bars are swiped in, shows it again`() {
        // Transient bars swiped in over the page don't change the insets,
        // so the swipe's own start is what counts.
        val notice = FullscreenNotice()
        notice.onPress(y = 500f, height = 2400f, edge = 63f)
        assertEquals(1, notice.shows)
        notice.onPress(y = 2f, height = 2400f, edge = 63f)
        assertEquals(2, notice.shows)
        notice.onPress(y = 2390f, height = 2400f, edge = 63f)
        assertEquals(3, notice.shows)
        notice.onPress(y = 64f, height = 2400f, edge = 63f)
        assertEquals(3, notice.shows)
    }
}
