package baby.freedom.mobile.browser

import android.content.ComponentCallbacks2
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpace
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Restored tabs build their WebView only once shown (#460), and the
 * switcher's snapshots go when memory is short. The host side (which
 * tabs it attaches) reads [TabsState.defersWebView] and is exercised on
 * the device.
 */
class TabsLazyRestoreTest {

    private fun BrowserState.visit(page: String) {
        url = "https://$page.example/"
        title = page
        addressBarText = url
    }

    /** Tabs a, b, c brought back after the process was killed; b active. */
    private fun restoredThree(): TabsState {
        val before = TabsState(homepage = HOME_URL)
        before.tabs[0].visit("a")
        before.newTab().visit("b")
        before.newTab().visit("c")
        before.switchTo(1)
        val tabs = TabsState(homepage = HOME_URL)
        tabs.restoreAfterProcessDeath(before.saveForProcessDeath())
        return tabs
    }

    @Test
    fun `a cold start builds only the tab on screen`() {
        val tabs = restoredThree()
        assertEquals(listOf(true, false, true), tabs.tabs.map { tabs.defersWebView(it) })
    }

    @Test
    fun `a background restored tab gets its WebView once it's shown`() {
        val tabs = restoredThree()
        tabs.switchTo(2)
        assertFalse(tabs.defersWebView(tabs.tabs[2]))
        // Still waiting: the one left was never built either.
        assertTrue(tabs.defersWebView(tabs.tabs[0]))
    }

    @Test
    fun `a background restored tab handed a navigation gets its WebView`() {
        val tabs = restoredThree()
        val c = tabs.tabs[2]
        c.loadUrl("https://d.example/")
        assertFalse(tabs.defersWebView(c))
    }

    @Test
    fun `a tab with nothing to restore is never deferred`() {
        val tabs = restoredThree()
        val bg = tabs.newTab(activate = false)
        assertNull(bg.pendingRestore)
        assertFalse(tabs.defersWebView(bg))
    }

    @Test
    fun `a relaunch builds the background tabs only when shown`() {
        val tabs = TabsState(homepage = HOME_URL)
        tabs.tabs[0].visit("a")
        tabs.newTab().visit("b")
        tabs.parkForRelaunch { null }
        assertEquals(listOf(true, false), tabs.tabs.map { tabs.defersWebView(it) })
    }

    @Test
    fun `a tab whose renderer went away still follows its own rule`() {
        val tabs = TabsState(homepage = HOME_URL)
        tabs.tabs[0].visit("a")
        val b = tabs.newTab(activate = false).apply { visit("b") }
        tabs.rendererGone(b, crashed = false) { null }
        tabs.switchTo(1)
        // Shown: not deferred; the host's rendererGone branch decides.
        assertFalse(tabs.defersWebView(b))
    }

    /** One restored from a load the user stopped before it committed. */
    private fun restoredStoppedLoad(): TabsState {
        val before = TabsState(homepage = HOME_URL)
        before.tabs[0].visit("a")
        before.newTab().apply {
            addressBarText = "https://slow.example/"
            loadAborted = true
        }
        before.switchTo(0)
        val tabs = TabsState(homepage = HOME_URL)
        tabs.restoreAfterProcessDeath(before.saveForProcessDeath())
        assertFalse(tabs.tabs[1].pendingRestore!!.submit)
        return tabs
    }

    @Test
    fun `an unshown restored tab saved again keeps its stopped load stopped`() {
        val tabs = restoredStoppedLoad()
        val again = TabsState(homepage = HOME_URL)
        again.restoreAfterProcessDeath(tabs.saveForProcessDeath())
        val restore = again.tabs[1].pendingRestore!!
        assertEquals("https://slow.example/", restore.fallbackUrl)
        assertFalse(restore.submit)
    }

    @Test
    fun `an unshown restored tab saved again keeps its committed page`() {
        val tabs = restoredThree()
        val saved = tabs.saveForProcessDeath()
        assertEquals(listOf(true, true, true), saved.tabs.map { it.committed })
        assertEquals(
            listOf("https://a.example/", "https://b.example/", "https://c.example/"),
            saved.tabs.map { it.address },
        )
    }

    @Test
    fun `an unshown restored tab closed and reopened keeps its stopped load stopped`() {
        val tabs = restoredStoppedLoad()
        tabs.closeTab(1)
        val reopened = tabs.reopenClosedTab()!!
        val restore = reopened.pendingRestore!!
        assertEquals("https://slow.example/", restore.fallbackUrl)
        assertFalse(restore.submit)
    }

    @Test
    fun `memory pressure levels that drop the snapshots`() {
        @Suppress("DEPRECATION")
        val drops = listOf(
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
        )
        @Suppress("DEPRECATION")
        val keeps = listOf(
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN,
            // Sent on every ordinary trip to the background on API 34+.
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
        )
        for (level in drops) assertTrue("$level", TabsState.dropsThumbnails(level))
        for (level in keeps) assertFalse("$level", TabsState.dropsThumbnails(level))
    }

    @Test
    fun `memory pressure drops every snapshot, open and closed tabs alike`() {
        val tabs = TabsState(homepage = HOME_URL)
        tabs.tabs[0].apply { visit("a"); thumbnail = FakeBitmap }
        tabs.newTab().apply { visit("b"); thumbnail = FakeBitmap }
        tabs.newTab().apply { visit("c"); thumbnail = FakeBitmap }
        tabs.closeTab(2)
        tabs.trimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        tabs.trimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
        assertTrue(tabs.tabs.all { it.thumbnail != null })

        @Suppress("DEPRECATION")
        tabs.trimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        assertTrue(tabs.tabs.all { it.thumbnail == null })
        val reopened = tabs.reopenClosedTab()
        assertNotNull(reopened)
        assertNull(reopened!!.thumbnail)
    }

    private object FakeBitmap : ImageBitmap {
        override val width = 1
        override val height = 1
        override val config = ImageBitmapConfig.Rgb565
        override val hasAlpha = false
        override val colorSpace: ColorSpace = ColorSpaces.Srgb
        override fun prepareToDraw() = Unit
        override fun readPixels(
            buffer: IntArray,
            startX: Int,
            startY: Int,
            width: Int,
            height: Int,
            bufferOffset: Int,
            stride: Int,
        ) = Unit
    }
}
