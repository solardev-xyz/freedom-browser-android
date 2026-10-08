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

    private fun FullscreenNotice.swipe(x0: Float, y0: Float, x1: Float, y1: Float) {
        onPress(x0, y0, width = W, height = H, edge = EDGE)
        onMove((x0 + x1) / 2, (y0 + y1) / 2, SWIPE)
        onMove(x1, y1, SWIPE)
        onRelease(cancelled = false)
    }

    private fun FullscreenNotice.tap(x: Float, y: Float) {
        onPress(x, y, width = W, height = H, edge = EDGE)
        onRelease(cancelled = false)
    }

    @Test
    fun `a swipe in from the top or bottom edge, where the bars come back, shows it again`() {
        // Transient bars swiped in over the page don't change the insets,
        // so the swipe itself is what counts.
        val notice = FullscreenNotice()
        notice.swipe(540f, 2f, 540f, 600f)
        assertEquals(2, notice.shows)
        notice.swipe(540f, 2398f, 540f, 1800f)
        assertEquals(3, notice.shows)
        // One just inside the edge is the page's own swipe.
        notice.swipe(540f, 70f, 540f, 600f)
        assertEquals(3, notice.shows)
    }

    @Test
    fun `a swipe in from the side edge, where a landscape nav bar sits, shows it again`() {
        // Landscape, 3-button nav: the bar comes back from the right (or
        // left) edge, and the press is halfway down that side.
        val notice = FullscreenNotice()
        notice.swipe(1079f, 1200f, 580f, 1200f)
        assertEquals(2, notice.shows)
        notice.swipe(0f, 1200f, 500f, 1200f)
        assertEquals(3, notice.shows)
    }

    @Test
    fun `a swipe counts once, however far it goes`() {
        val notice = FullscreenNotice()
        notice.onPress(540f, 2398f, W, H, EDGE)
        for (y in 2390 downTo 1000 step 10) notice.onMove(540f, y.toFloat(), SWIPE)
        notice.onRelease(cancelled = false)
        assertEquals(2, notice.shows)
    }

    @Test
    fun `a tap on the page's own control at the edge doesn't show it`() {
        // A video scrubber along the bottom edge: taps, and drags along it.
        val notice = FullscreenNotice()
        notice.tap(540f, 2390f)
        notice.tap(1075f, 1200f)
        notice.tap(540f, 3f)
        assertEquals(1, notice.shows)
        notice.swipe(200f, 2390f, 900f, 2370f)
        assertEquals(1, notice.shows)
        // A wobble inward that stays under the swipe distance.
        notice.onPress(540f, 2390f, W, H, EDGE)
        notice.onMove(545f, 2380f, SWIPE)
        notice.onRelease(cancelled = false)
        assertEquals(1, notice.shows)
    }

    @Test
    fun `an edge touch the system takes over shows it again`() {
        // The system's own edge gesture cancels the window's touch.
        val notice = FullscreenNotice()
        notice.onPress(1079f, 1200f, W, H, EDGE)
        notice.onMove(1070f, 1200f, SWIPE)
        notice.onRelease(cancelled = true)
        assertEquals(2, notice.shows)
        // A cancelled touch that didn't start at an edge is not the system's.
        notice.onPress(540f, 1200f, W, H, EDGE)
        notice.onRelease(cancelled = true)
        assertEquals(2, notice.shows)
    }

    private companion object {
        const val W = 1080f
        const val H = 2400f
        const val EDGE = 63f
        const val SWIPE = 42f
    }
}
