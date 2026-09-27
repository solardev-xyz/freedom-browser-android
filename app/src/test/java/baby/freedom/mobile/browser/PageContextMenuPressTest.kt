package baby.freedom.mobile.browser

import android.webkit.WebView.HitTestResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The page's say on a long-press (#84): the menu opens only for a press
 * whose DOM `contextmenu` the page let through, never over a
 * `preventDefault()`.
 */
class PageContextMenuPressTest {

    private val pin = PageContextMenuPin(tabId = 1, pageUrl = "https://example.com/", navCounter = 3)
    private val link = PageContextTarget("https://example.com/a", "A", null)

    private fun press(at: Long = 1_000L) =
        PageContextMenuPress(pin, HitTestResult.SRC_ANCHOR_TYPE, "https://example.com/a", at)

    @Test
    fun `opens once both the href and a let-through verdict are in, in either order`() {
        press().run {
            assertNull(onHref("https://example.com/a", "A"))
            assertEquals(link, onPageVerdict(true, nowMs = 1_100L))
        }
        press().run {
            assertNull(onPageVerdict(true, nowMs = 1_100L))
            assertEquals(link, onHref("https://example.com/a", "A"))
        }
    }

    @Test
    fun `a prevented contextmenu keeps the press for the page`() {
        press().run {
            assertNull(onHref("https://example.com/a", "A"))
            assertNull(onPageVerdict(false, nowMs = 1_100L))
            // A later let-through (another frame, a forged message) can't reopen it.
            assertNull(onPageVerdict(true, nowMs = 1_200L))
        }
    }

    @Test
    fun `no verdict, or one too late to be this press's, means no menu`() {
        press().run { assertNull(onHref("https://example.com/a", "A")) }
        press().run {
            assertNull(onHref("https://example.com/a", "A"))
            assertNull(onPageVerdict(true, nowMs = 1_000L + PAGE_CONTEXT_MENU_VERDICT_WINDOW_MS + 1))
        }
    }

    @Test
    fun `opens at most once`() {
        press().run {
            onHref("https://example.com/a", "A")
            assertEquals(link, onPageVerdict(true, nowMs = 1_100L))
            assertNull(onHref("https://example.com/a", "A"))
        }
    }

    @Test
    fun `only the exact verdict messages parse`() {
        assertEquals(true, parseContextMenuVerdict("contextmenu 1"))
        assertEquals(false, parseContextMenuVerdict("contextmenu 0"))
        assertEquals(true, parseContextMenuVerdict(CONTEXT_MENU_ALLOWED))
        assertEquals(false, parseContextMenuVerdict(CONTEXT_MENU_KEPT))
        assertNull(parseContextMenuVerdict("contextmenu"))
        assertNull(parseContextMenuVerdict("""{"token":"ab","hasBottomUI":true}"""))
        assertNull(parseContextMenuVerdict(null))
    }
}
