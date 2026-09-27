package baby.freedom.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class AdblockPageTest {

    private val a = "http://10.0.2.2:8710/"
    private val b = "http://127.0.0.1:8711/page"

    private fun onA() = AdblockPage().apply { committed(a) }

    @Test
    fun `the incoming page counts from its answer until it commits`() {
        val p = onA()
        p.answered(b, replacesDocument = true)
        assertEquals(b, p.current())
        p.committed(b)
        assertEquals(b, p.current())
    }

    @Test
    fun `a navigation the WebView fetches itself leaves the page on screen until it commits`() {
        val p = onA()
        p.answered(b, replacesDocument = true, fetchedByWebView = true)
        assertEquals(a, p.current())
        p.redirected("https://elsewhere.example/")
        assertEquals(a, p.current())
        p.committed("https://elsewhere.example/")
        assertEquals("https://elsewhere.example/", p.current())
    }

    @Test
    fun `an answer that replaces nothing leaves the page on screen`() {
        val p = onA()
        p.answered("http://127.0.0.1:8711/file.zip", replacesDocument = false)
        assertEquals(a, p.current())
    }

    @Test
    fun `a navigation that became a download or was cancelled leaves the page on screen`() {
        val p = onA()
        p.answered("http://127.0.0.1:8711/file.zip", replacesDocument = true)
        p.kept()
        assertEquals(a, p.current())
    }

    @Test
    fun `an aborted finish drops only its own navigation`() {
        val p = onA()
        p.answered(b, replacesDocument = true)
        p.kept("http://other.example/204")
        assertEquals(b, p.current())
        p.kept(b)
        assertEquals(a, p.current())
    }

    @Test
    fun `a redirect moves the incoming page, never the committed one`() {
        val p = onA()
        p.redirected("https://elsewhere.example/")
        assertEquals(a, p.current())
        p.answered(b, replacesDocument = true)
        p.redirected("https://elsewhere.example/")
        assertEquals("https://elsewhere.example/", p.current())
        p.kept("https://elsewhere.example/")
        assertEquals(a, p.current())
    }
}
