package baby.freedom.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateJsDialogsTest {
    @Test
    fun namesThePageOrigin() {
        assertEquals(
            "The page at \"https://example.com\" says:",
            jsDialogTitle(JsDialogKind.ALERT, "https://example.com/a/b?q=1#f"),
        )
        assertEquals(
            "The page at \"http://10.0.2.2:8720\" says:",
            jsDialogTitle(JsDialogKind.PROMPT, "http://10.0.2.2:8720/x"),
        )
    }

    @Test
    fun neutralTitleWithoutHost() {
        assertEquals("This page says:", jsDialogTitle(JsDialogKind.CONFIRM, "data:text/html,hi"))
        assertEquals("This page says:", jsDialogTitle(JsDialogKind.ALERT, "about:blank"))
        assertEquals("This page says:", jsDialogTitle(JsDialogKind.ALERT, null))
        assertEquals("This page says:", jsDialogTitle(JsDialogKind.ALERT, "http://bad host/"))
    }

    @Test
    fun beforeUnloadAsksToLeave() {
        assertEquals("Leave this page?", jsDialogTitle(JsDialogKind.BEFORE_UNLOAD, "https://example.com"))
    }

    private fun request(
        kind: JsDialogKind,
        answers: MutableList<Boolean>,
        settled: MutableList<JsDialogRequest>,
    ) = JsDialogRequest(
        kind = kind, url = "https://example.com", message = "hi", defaultValue = null, secure = false,
        answer = { confirmed, _ -> answers += confirmed },
        onSettled = { settled += it },
    )

    @Test
    fun aDialogIsAnsweredOnce() {
        val answers = mutableListOf<Boolean>()
        val settled = mutableListOf<JsDialogRequest>()
        val ok = request(JsDialogKind.CONFIRM, answers, settled)
        ok.confirm()
        // The dialog's dismiss listener, then losing its turn, cancel
        // too: the page has its answer already.
        ok.cancel()
        ok.cancel()
        assertEquals(listOf(true), answers)
        assertEquals(listOf(ok), settled)

        // Cancelled first (a background tab, a closed one): OK is too late.
        val stay = request(JsDialogKind.BEFORE_UNLOAD, answers, settled)
        stay.cancel()
        stay.confirm()
        assertEquals(listOf(true, false), answers)
        assertEquals(listOf(ok, stay), settled)
        assertTrue(stay.answered)
    }
}
