package baby.freedom.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorPageTest {

    @Test
    fun `url encodes ens display and retry`() {
        val u = ErrorPage.url(
            errorCode = "swarm_content_not_found",
            displayUrl = "ens://swarm.eth/docs?q=1",
            protocol = "swarm",
            retryUrl = "bzz://deadbeef/docs?q=1",
        )
        assertTrue(u.startsWith(ErrorPage.URL))
        assertTrue(u.contains("error=swarm_content_not_found"))
        assertTrue(u.contains("url=ens%3A%2F%2Fswarm.eth%2Fdocs%3Fq%3D1"))
        assertTrue(u.contains("protocol=swarm"))
        assertTrue(u.contains("retry=bzz%3A%2F%2Fdeadbeef%2Fdocs%3Fq%3D1"))
    }

    @Test
    fun `url omits optional params when null`() {
        val u = ErrorPage.url(
            errorCode = "ERR_FAILED",
            displayUrl = "bzz://a/b",
        )
        assertTrue(u.contains("error=ERR_FAILED"))
        assertFalse("protocol must not leak when null", u.contains("protocol="))
        assertFalse("retry must not leak when null", u.contains("retry="))
    }

    @Test
    fun `isErrorPage matches query-parameterised URLs`() {
        assertTrue(
            ErrorPage.isErrorPage(
                ErrorPage.url("swarm_content_not_found", "ens://foo.eth"),
            ),
        )
        assertTrue(ErrorPage.isErrorPage(ErrorPage.URL))
    }

    @Test
    fun `isErrorPage rejects other file URLs`() {
        assertFalse(ErrorPage.isErrorPage(null))
        assertFalse(ErrorPage.isErrorPage("about:blank"))
        assertFalse(ErrorPage.isErrorPage("http://127.0.0.1:1633/bzz/abc"))
    }

    @Test
    fun `url is file scheme asset`() {
        // Contract: BrowserScreen hands the URL straight to WebView.loadUrl
        // and the path needs to be resolvable as a bundled asset.
        assertEquals("file:///android_asset/error/error.html", ErrorPage.URL)
    }
}
