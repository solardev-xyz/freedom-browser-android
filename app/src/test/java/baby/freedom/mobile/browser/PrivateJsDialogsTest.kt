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
        // Back, then being withdrawn, come too late: the page has its
        // answer already.
        ok.cancel()
        ok.withdraw()
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

    @Test
    fun aDialogNobodySawIsAnsweredForThePage() {
        val answers = mutableListOf<Boolean>()
        val settled = mutableListOf<JsDialogRequest>()
        // A background tab's `alert`/`confirm`/`prompt`: Cancel.
        for (kind in listOf(JsDialogKind.ALERT, JsDialogKind.CONFIRM, JsDialogKind.PROMPT)) {
            request(kind, answers, settled).withdraw()
        }
        assertEquals(listOf(false, false, false), answers)

        // A background tab's `beforeunload` nobody saw: Leave, so an app
        // reload (#125's sweep) isn't refused by a page out of view.
        answers.clear()
        request(JsDialogKind.BEFORE_UNLOAD, answers, settled).withdraw()
        assertEquals(listOf(true), answers)

        // One the user was shown and left: Stay, their edits kept.
        answers.clear()
        val shown = request(JsDialogKind.BEFORE_UNLOAD, answers, settled)
        shown.seen = true
        shown.withdraw()
        assertEquals(listOf(false), answers)
        assertEquals(5, settled.size)
    }

    @Test
    fun blockMoreOnlyWhenOfferedAndBeforeTheAnswer() {
        var blocks = 0
        fun req(offer: Boolean) = JsDialogRequest(
            kind = JsDialogKind.ALERT, url = "https://example.com", message = "hi", defaultValue = null,
            secure = false, answer = { _, _ -> }, offerBlock = offer, onBlock = { blocks++ },
        )
        req(offer = false).blockMore()
        assertEquals(0, blocks)
        val offered = req(offer = true)
        offered.blockMore()
        offered.confirm()
        assertEquals(1, blocks)
        // Too late once answered.
        offered.blockMore()
        assertEquals(1, blocks)
    }
}
