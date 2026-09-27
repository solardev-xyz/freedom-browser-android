package baby.freedom.mobile.browser

import android.webkit.WebView.HitTestResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mozilla.javascript.Context as JsContext

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
        assertNull(parseContextMenuVerdict("contextmenu"))
        assertNull(parseContextMenuVerdict("""{"token":"ab","hasBottomUI":true}"""))
        assertNull(parseContextMenuVerdict(null))
    }

    // ---- the page side ------------------------------------------------

    /** Runs the verdict script against a fake window, dispatches [event], returns what it posted. */
    private fun dispatch(event: String): List<String> {
        val cx = JsContext.enter()
        try {
            cx.optimizationLevel = -1
            val scope = cx.initStandardObjects()
            cx.evaluateString(
                scope,
                """
                var posted = [], listeners = [], timers = [];
                var globalThis = this;
                this.chan = { postMessage: function(m){ posted.push(String(m)); } };
                var window = { addEventListener: function(t, f, c){ if (t === 'contextmenu' && c) listeners.push(f); } };
                function setTimeout(f){ timers.push(f); }
                """.trimIndent(),
                "setup", 1, null,
            )
            cx.evaluateString(scope, contextMenuVerdictJs("chan"), "script", 1, null)
            cx.evaluateString(
                scope,
                """
                var e = $event;
                listeners.forEach(function(f){ f(e); });
                // A page handler after ours, still within the dispatch.
                if (e.pageCancels) e.defaultPrevented = true;
                timers.forEach(function(f){ f(); });
                """.trimIndent(),
                "dispatch", 1, null,
            )
            val posted = scope.get("posted", scope) as org.mozilla.javascript.NativeArray
            return posted.map { it.toString() }
        } finally {
            JsContext.exit()
        }
    }

    @Test
    fun `the script reports a let-through press`() {
        assertEquals(listOf("contextmenu 1"), dispatch("{ isTrusted: true, defaultPrevented: false }"))
    }

    @Test
    fun `the script reads the outcome after every page handler`() {
        assertEquals(
            listOf("contextmenu 0"),
            dispatch("{ isTrusted: true, defaultPrevented: false, pageCancels: true }"),
        )
    }

    @Test
    fun `the script ignores a synthetic event`() {
        assertEquals(emptyList<String>(), dispatch("{ isTrusted: false, defaultPrevented: false }"))
    }
}
