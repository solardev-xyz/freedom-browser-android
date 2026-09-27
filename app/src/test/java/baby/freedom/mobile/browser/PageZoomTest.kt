package baby.freedom.mobile.browser

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageZoomTest {

    @Test
    fun `steps are ten points, clamped to the desktop range`() {
        assertEquals(110, PageZoomLevels.step(100, zoomIn = true))
        assertEquals(90, PageZoomLevels.step(100, zoomIn = false))
        assertEquals(30, PageZoomLevels.step(40, zoomIn = false))
        assertEquals(25, PageZoomLevels.step(30, zoomIn = false))
        assertEquals(25, PageZoomLevels.step(25, zoomIn = false))
        assertEquals(35, PageZoomLevels.step(25, zoomIn = true))
        assertEquals(500, PageZoomLevels.step(500, zoomIn = true))
        assertEquals(500, PageZoomLevels.step(495, zoomIn = true))
    }

    @Test
    fun `site key is the host, without scheme or port`() {
        assertEquals("example.com", zoomSiteKey("https://Example.COM/path?q#f"))
        assertEquals("example.com", zoomSiteKey("http://example.com:8080/"))
        assertEquals(zoomSiteKey("http://example.com/"), zoomSiteKey("https://example.com/"))
        assertEquals("10.0.2.2", zoomSiteKey("http://10.0.2.2:8710/a.html"))
        assertEquals("[::1]", zoomSiteKey("http://[::1]:8080/"))
        assertEquals("[::1]", zoomSiteKey("http://[::1]/"))
        assertEquals("example.com", zoomSiteKey("https://user:pw@example.com/"))
    }

    @Test
    fun `dweb pages are keyed per virtual host`() {
        val a = "https://vitalik-eth.ens.freedom.baby/"
        val b = "https://swarm-eth.ens.freedom.baby/about"
        assertEquals("vitalik-eth.ens.freedom.baby", zoomSiteKey(a))
        assertEquals("swarm-eth.ens.freedom.baby", zoomSiteKey(b))
    }

    @Test
    fun `non sites have no key`() {
        assertNull(zoomSiteKey(null))
        assertNull(zoomSiteKey(""))
        assertNull(zoomSiteKey(ABOUT_BLANK))
        assertNull(zoomSiteKey("file:///android_asset/error/error.html?u=x"))
        assertNull(zoomSiteKey("data:text/html,hi"))
        assertNull(zoomSiteKey("blob:https://example.com/uuid"))
    }

    private class Harness(stored: Map<String, Int> = emptyMap(), deferLoad: Boolean = false) {
        val loaded = CompletableDeferred<Map<String, Int>>()
        val writes = mutableListOf<Pair<String, Int?>>()
        val zoom = PageZoom(
            scope = CoroutineScope(Dispatchers.Unconfined),
            load = { loaded.await() },
            save = { site, percent -> writes += site to percent },
        )

        init {
            if (!deferLoad) loaded.complete(stored)
        }
    }

    @Test
    fun `remembered levels load and unknown sites are at 100`() {
        val h = Harness(mapOf("a.com" to 150))
        assertEquals(150, h.zoom.levelFor("a.com"))
        assertEquals(100, h.zoom.levelFor("b.com"))
        assertEquals(100, h.zoom.levelFor(null))
    }

    @Test
    fun `in, out and reset are remembered, and 100 is stored as no entry`() {
        val h = Harness()
        h.zoom.apply("a.com", ZoomAction.In)
        h.zoom.apply("a.com", ZoomAction.In)
        assertEquals(120, h.zoom.levelFor("a.com"))
        h.zoom.apply("a.com", ZoomAction.Out)
        assertEquals(110, h.zoom.levelFor("a.com"))
        h.zoom.apply("a.com", ZoomAction.Reset)
        assertEquals(100, h.zoom.levelFor("a.com"))
        assertEquals(
            listOf("a.com" to 110, "a.com" to 120, "a.com" to 110, "a.com" to null),
            h.writes,
        )
    }

    @Test
    fun `a level is per site`() {
        val h = Harness()
        h.zoom.apply("a.com", ZoomAction.Out)
        assertEquals(90, h.zoom.levelFor("a.com"))
        assertEquals(100, h.zoom.levelFor("b.com"))
    }

    @Test
    fun `a change made before the startup read lands wins over it`() {
        val h = Harness(deferLoad = true)
        h.zoom.apply("a.com", ZoomAction.In)
        h.zoom.apply("c.com", ZoomAction.Reset)
        h.loaded.complete(mapOf("a.com" to 200, "b.com" to 80, "c.com" to 150))
        assertEquals(110, h.zoom.levelFor("a.com"))
        assertEquals(80, h.zoom.levelFor("b.com"))
        // The reset still reaches the file, though the level didn't move
        // on screen, so c.com doesn't come back at 150 next launch.
        assertEquals(100, h.zoom.levelFor("c.com"))
        assertEquals(listOf("a.com" to 110, "c.com" to null), h.writes)
    }

    @Test
    fun `stored levels outside the range are clamped`() {
        val h = Harness(mapOf("a.com" to 5, "b.com" to 9000))
        assertEquals(25, h.zoom.levelFor("a.com"))
        assertEquals(500, h.zoom.levelFor("b.com"))
    }
}
