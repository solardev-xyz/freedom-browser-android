package baby.freedom.mobile.browser

import org.junit.Assert.assertEquals
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
}
