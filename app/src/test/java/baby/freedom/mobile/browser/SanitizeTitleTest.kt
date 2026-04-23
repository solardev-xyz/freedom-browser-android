package baby.freedom.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class SanitizeTitleTest {

    @Test
    fun `empty title stays empty`() {
        assertEquals("", sanitizeTitle(null, "http://example.com"))
        assertEquals("", sanitizeTitle("", "http://example.com"))
    }

    @Test
    fun `real title passes through`() {
        assertEquals(
            "Hello, world",
            sanitizeTitle("Hello, world", "http://127.0.0.1:1633/bzz/abc/"),
        )
    }

    @Test
    fun `title equal to url is stripped`() {
        assertEquals(
            "",
            sanitizeTitle(
                "http://127.0.0.1:1633/bzz/abc/",
                "http://127.0.0.1:1633/bzz/abc/",
            ),
        )
    }

    @Test
    fun `title equal to url without scheme is stripped`() {
        assertEquals(
            "",
            sanitizeTitle(
                "127.0.0.1:1633/bzz/abc/",
                "http://127.0.0.1:1633/bzz/abc/",
            ),
        )
    }

    @Test
    fun `truncated auto-title is stripped`() {
        // WebView sometimes truncates long URLs in the title.
        assertEquals(
            "",
            sanitizeTitle(
                "127.0.0.1:1633/bzz/03b80b08",
                "http://127.0.0.1:1633/bzz/03b80b08353b288c08deadbeef/",
            ),
        )
    }

    @Test
    fun `title that is a superset of the url is stripped`() {
        // Defensive: some WebView builds include trailing query/fragment
        // in the auto-title that isn't in view?.url.
        assertEquals(
            "",
            sanitizeTitle(
                "127.0.0.1:1633/bzz/abc/index.html",
                "127.0.0.1:1633/bzz/abc/",
            ),
        )
    }

    @Test
    fun `title survives when url is null`() {
        assertEquals("Fallback", sanitizeTitle("Fallback", null))
    }
}
