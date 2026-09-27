package baby.freedom.mobile.browser

import android.webkit.WebView.HitTestResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.mozilla.javascript.Context as JsContext

/**
 * The page context menu (#84), minus the sheet that draws it: what a
 * long-press counts as, when a raised menu has gone stale, and what
 * "Search" sends for a selection.
 */
class PageContextMenuTest {

    // ---- what a long-press lands on -----------------------------------

    @Test
    fun `a link is a link, titled by its text`() {
        assertEquals(
            PageContextTarget("https://example.com/a", "Example", null),
            pageContextTargetFor(
                HitTestResult.SRC_ANCHOR_TYPE,
                extra = "https://example.com/a",
                focusHref = "https://example.com/a",
                focusTitle = " Example ",
            ),
        )
    }

    @Test
    fun `a link is decided from the hit test alone, before the href lands`() {
        assertEquals(
            PageContextTarget("https://example.com/a", null, null),
            pageContextTargetFor(HitTestResult.SRC_ANCHOR_TYPE, "https://example.com/a", null, null),
        )
    }

    @Test
    fun `an image inside a link gets both, the link from the focus href`() {
        assertEquals(
            PageContextTarget("https://example.com/page", null, "https://example.com/i.png"),
            pageContextTargetFor(
                HitTestResult.SRC_IMAGE_ANCHOR_TYPE,
                extra = "https://example.com/i.png",
                focusHref = "https://example.com/page",
                focusTitle = null,
            ),
        )
    }

    @Test
    fun `a bare image is an image`() {
        assertEquals(
            PageContextTarget(null, null, "https://x.bzz.freedom.baby/i.png"),
            pageContextTargetFor(HitTestResult.IMAGE_TYPE, "https://x.bzz.freedom.baby/i.png", null, null),
        )
    }

    @Test
    fun `a data image can be saved but not opened`() {
        val target = pageContextTargetFor(HitTestResult.IMAGE_TYPE, "data:image/png;base64,AAAA", null, null)
        assertEquals("data:image/png;base64,AAAA", target?.imageUrl)
        assertFalse(isOpenableImage(target!!.imageUrl!!))
        assertTrue(isOpenableImage("bzz://abc/i.png"))
    }

    @Test
    fun `javascript links and blob images leave the long-press to Chromium`() {
        assertNull(pageContextTargetFor(HitTestResult.SRC_ANCHOR_TYPE, "javascript:void(0)", null, null))
        assertNull(pageContextTargetFor(HitTestResult.IMAGE_TYPE, "blob:https://a.com/1234", null, null))
        // A `data:` payload that doesn't say it is an image isn't one.
        assertNull(pageContextTargetFor(HitTestResult.IMAGE_TYPE, "data:,hello", null, null))
        assertNull(pageContextTargetFor(HitTestResult.IMAGE_TYPE, "data:text/html,<b>x</b>", null, null))
        assertNull(
            pageContextTargetFor(HitTestResult.SRC_IMAGE_ANCHOR_TYPE, "blob:https://a.com/1", "javascript:x()", null),
        )
    }

    @Test
    fun `a javascript link around a real image still offers the image`() {
        assertEquals(
            PageContextTarget(null, null, "https://a.com/i.png"),
            pageContextTargetFor(HitTestResult.SRC_IMAGE_ANCHOR_TYPE, "https://a.com/i.png", "javascript:x()", "t"),
        )
    }

    @Test
    fun `a long-press is taken only when the reply cannot leave it without a menu`() {
        // Taken: whatever the href reply says, a target comes out of it.
        val certain = listOf(
            HitTestResult.SRC_ANCHOR_TYPE to "https://a.com/x",
            HitTestResult.IMAGE_TYPE to "https://a.com/i.png",
            HitTestResult.SRC_IMAGE_ANCHOR_TYPE to "https://a.com/i.png",
        )
        for ((type, extra) in certain) {
            assertTrue(pageContextMenuIsCertain(type, extra))
            for (reply in listOf(null, "", "javascript:void 0", "https://a.com/other")) {
                assertTrue("$type $extra $reply", pageContextTargetFor(type, extra, reply, null) != null)
            }
        }
        // Left to Chromium: a blob image in a link hangs on a reply that
        // may be `javascript:` (and then nothing would open).
        assertFalse(pageContextMenuIsCertain(HitTestResult.SRC_IMAGE_ANCHOR_TYPE, "blob:https://a.com/1"))
        assertFalse(pageContextMenuIsCertain(HitTestResult.SRC_ANCHOR_TYPE, "javascript:void 0"))
    }

    @Test
    fun `text, editables and unknown hits are not ours`() {
        for (type in listOf(
            HitTestResult.UNKNOWN_TYPE,
            HitTestResult.EDIT_TEXT_TYPE,
            HitTestResult.PHONE_TYPE,
            HitTestResult.EMAIL_TYPE,
            HitTestResult.GEO_TYPE,
        )) {
            assertNull(pageContextTargetFor(type, "whatever", null, null))
        }
    }

    // ---- staleness ----------------------------------------------------

    private val request = PageContextMenuRequest(
        tabId = 3,
        pageUrl = "https://a.com/",
        navCounter = 7,
        target = PageContextTarget("https://a.com/x", null, null),
    )

    @Test
    fun `a menu over the page it was raised on is live`() {
        assertFalse(pageContextMenuIsStale(request, activeTabId = 3, tabUrl = "https://a.com/", tabNavCounter = 7))
    }

    @Test
    fun `a navigation, a new load or another tab makes it stale`() {
        assertTrue(pageContextMenuIsStale(request, 3, "https://a.com/next", 7))
        assertTrue(pageContextMenuIsStale(request, 3, "https://a.com/", 8))
        assertTrue(pageContextMenuIsStale(request, 4, "https://a.com/", 7))
        // Tab closed.
        assertTrue(pageContextMenuIsStale(request, 3, null, null))
    }

    @Test
    fun `a target landing after a navigation stays pinned to the page pressed`() {
        // Pinned at the long-press; the href reply lands after the tab
        // moved on (url and navCounter both changed).
        val pin = PageContextMenuPin(tabId = 3, pageUrl = "https://a.com/", navCounter = 7)
        val late = pin.request(PageContextTarget("https://a.com/x", null, null))
        assertEquals("https://a.com/", late.pageUrl)
        assertEquals(7, late.navCounter)
        assertTrue(pageContextMenuIsStale(late, 3, "https://b.com/", 8))
        // Same-URL reload in between: still stale by navCounter.
        assertTrue(pageContextMenuIsStale(late, 3, "https://a.com/", 8))
        // Nothing happened in between: live.
        assertFalse(pageContextMenuIsStale(late, 3, "https://a.com/", 7))
    }

    // ---- search query -------------------------------------------------

    @Test
    fun `a selection is searched collapsed and trimmed`() {
        assertEquals("hello big world", searchSelectionQuery("  hello\n\tbig   world \n"))
    }

    @Test
    fun `an empty selection searches nothing`() {
        assertNull(searchSelectionQuery(null))
        assertNull(searchSelectionQuery(""))
        assertNull(searchSelectionQuery(" \n "))
    }

    @Test
    fun `a long selection is cut back to a word break inside the budget`() {
        val words = (1..400).joinToString(" ") { "word$it" }
        val query = searchSelectionQuery(words)!!
        assertTrue(query.length <= SEARCH_SELECTION_MAX)
        assertTrue(words.startsWith(query))
        assertTrue(words[query.length] == ' ')
    }

    @Test
    fun `a long selection with no breaks is cut hard`() {
        val blob = "a".repeat(3000)
        assertEquals(SEARCH_SELECTION_MAX, searchSelectionQuery(blob)!!.length)
    }

    @Test
    fun `the clamp counts code points, never splitting a surrogate pair`() {
        val emoji = "😀".repeat(2000)
        val query = searchSelectionQuery(emoji)!!
        assertEquals(SEARCH_SELECTION_MAX, query.codePointCount(0, query.length))
        assertEquals(SEARCH_SELECTION_MAX * 2, query.length)
    }

    @Test
    fun `search uses the engine chosen in Settings, like the address bar`() {
        for (engine in SearchEngines.BUILT_IN) {
            val typed = UrlParser.toUrl("freedom browser", engine.template)
            assertEquals(typed, UrlParser.searchUrl("freedom browser", engine.template))
        }
        val custom = SearchEngines.templateFor("custom", "https://s.example/find/%s")
        assertEquals("https://s.example/find/a%20b", UrlParser.searchUrl("  a b ", custom))
    }

    // ---- the selection script, against a stub DOM ---------------------

    private fun runSelectionScript(setup: String): Any? {
        val cx = JsContext.enter()
        try {
            cx.optimizationLevel = -1
            val scope = cx.initStandardObjects()
            cx.evaluateString(scope, setup, "setup", 1, null)
            return cx.evaluateString(scope, SELECTION_TEXT_SCRIPT, "script", 1, null)
        } finally {
            JsContext.exit()
        }
    }

    @Test
    fun `the script reads the page selection`() {
        val out = runSelectionScript(
            """
            var document = { activeElement: { tagName: 'BODY' } };
            var window = { getSelection: function () { return { toString: function () { return 'picked text'; } }; } };
            """,
        )
        assertEquals("picked text", JsContext.toString(out))
    }

    @Test
    fun `the script reads a focused field's own selection`() {
        val out = runSelectionScript(
            """
            var document = { activeElement: { tagName: 'TEXTAREA', type: 'textarea', value: 'abcdef', selectionStart: 1, selectionEnd: 4 } };
            var window = { getSelection: function () { return ''; } };
            """,
        )
        assertEquals("bcd", JsContext.toString(out))
    }

    @Test
    fun `the script never reads a password field`() {
        val out = runSelectionScript(
            """
            var document = { activeElement: { tagName: 'INPUT', type: 'password', value: 'hunter2', selectionStart: 0, selectionEnd: 7 } };
            var window = { getSelection: function () { return { toString: function () { return 'hunter2'; } }; } };
            """,
        )
        assertEquals("", JsContext.toString(out))
    }

    @Test
    fun `the script follows focus into an open shadow root`() {
        val out = runSelectionScript(
            """
            var inner = { tagName: 'INPUT', type: 'search', value: 'deep query', selectionStart: 5, selectionEnd: 10 };
            var document = { activeElement: { tagName: 'MY-BOX', shadowRoot: { activeElement: inner } } };
            var window = { getSelection: function () { return ''; } };
            """,
        )
        assertEquals("query", JsContext.toString(out))
    }

    // ---- acting after the sheet hides ----------------------------------

    @Test
    fun `an action runs once the hide finishes`() = runBlocking {
        val scope = CoroutineScope(Job())
        val done = CompletableDeferred<Unit>()
        var ran = false
        afterUnlessDisposed(scope, { done.await() }) { ran = true }
        assertFalse(ran)
        done.complete(Unit)
        yieldUntil { ran }
        assertTrue(ran)
        scope.cancel()
    }

    @Test
    fun `an action picked before the menu went stale never runs`() = runBlocking {
        val scope = CoroutineScope(Job())
        val hiding = CompletableDeferred<Unit>()
        var ran = false
        afterUnlessDisposed(scope, { hiding.await() }) { ran = true }
        scope.cancel() // the sheet left composition mid-hide
        scope.coroutineContext[Job]!!.join()
        assertFalse(ran)
    }

    @Test
    fun `an interrupted hide still acts while the sheet is up`() = runBlocking {
        val scope = CoroutineScope(Job())
        var ran = false
        afterUnlessDisposed(scope, { throw CancellationException("drag") }) { ran = true }
        yieldUntil { ran }
        assertTrue(ran)
        scope.cancel()
    }

    private suspend fun yieldUntil(condition: () -> Boolean) {
        withTimeout(5_000) { while (!condition()) delay(5) }
    }
}
