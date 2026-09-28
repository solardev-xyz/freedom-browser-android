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
        assertEquals(a, p.current(referer = a))
        p.redirected("https://elsewhere.example/")
        assertEquals(a, p.current())
        p.committed("https://elsewhere.example/")
        assertEquals("https://elsewhere.example/", p.current())
    }

    @Test
    fun `the fetched page's own requests, naming it as referrer, are its before the commit`() {
        val p = onA()
        p.answered(b, replacesDocument = true, fetchedByWebView = true)
        // Same-origin: the full URL. Cross-origin: the origin alone.
        assertEquals(b, p.current(referer = b))
        assertEquals(b, p.current(referer = "http://127.0.0.1:8711/"))
        // The page on screen's, and a stranger's, stay the page on screen's.
        assertEquals(a, p.current(referer = "http://10.0.2.2:8710/other"))
        assertEquals(a, p.current(referer = "https://third.example/"))
        p.redirected("https://elsewhere.example/landing")
        assertEquals("https://elsewhere.example/landing", p.current(referer = "https://elsewhere.example/"))
        assertEquals(a, p.current(referer = b))
    }

    @Test
    fun `a same-origin destination claims only requests naming its exact URL`() {
        val p = onA()
        val next = "http://10.0.2.2:8710/next"
        p.answered(next, replacesDocument = true, fetchedByWebView = true)
        assertEquals(next, p.current(referer = next))
        assertEquals(a, p.current(referer = "http://10.0.2.2:8710/"))
    }

    @Test
    fun `a full-URL referrer from another document of the destination's origin isn't the destination's`() {
        val p = onA()
        p.answered(b, replacesDocument = true, fetchedByWebView = true)
        assertEquals(a, p.current(referer = "http://127.0.0.1:8711/frame"))
        assertEquals(b, p.current(referer = "$b#section"))
    }

    @Test
    fun `the page on screen's frame from the destination's site stays the page on screen's`() {
        // R1-F1: A embeds a frame from the site it links to; while the
        // link is still being fetched, that frame's requests are A's.
        val p = onA()
        val frame = "http://127.0.0.1:8711/frame"
        p.frameRequested(frame, referer = a)
        p.answered(b, replacesDocument = true, fetchedByWebView = true)
        // Its same-origin requests (full URL) and cross-origin ones (origin).
        assertEquals(a, p.current(referer = frame))
        assertEquals(a, p.current(referer = "http://127.0.0.1:8711/"))
        // The destination's own same-origin requests still name it exactly.
        assertEquals(b, p.current(referer = b))
        // A frame whose document is the destination's own URL: ambiguous,
        // so the page on screen's until the commit.
        p.frameRequested(b, referer = a)
        assertEquals(a, p.current(referer = b))
        // The commit forgets the old page's frames.
        p.committed(b)
        p.answered("http://127.0.0.1:8711/next", replacesDocument = true, fetchedByWebView = true)
        assertEquals("http://127.0.0.1:8711/next", p.current(referer = "http://127.0.0.1:8711/next"))
    }

    @Test
    fun `a frame first seen by its own requests stays the page on screen's`() {
        // The frame loaded before anything recorded it (a restored page):
        // its same-origin requests name it, and that's enough.
        val p = onA()
        val frame = "http://127.0.0.1:8711/frame"
        assertEquals(a, p.current(referer = frame))
        p.answered(b, replacesDocument = true, fetchedByWebView = true)
        assertEquals(a, p.current(referer = "http://127.0.0.1:8711/"))
    }

    @Test
    fun `the destination's stylesheet subresources don't make its origin a frame of the page on screen`() {
        // R2-F1 (R8): B's site.css loads a font, sending the stylesheet's
        // full URL. That request is judged against A, but must not file
        // B's origin under A's frames, or B's later cross-origin requests
        // (bare origin) would be A's until the commit.
        val p = onA()
        p.answered(b, replacesDocument = true, fetchedByWebView = true)
        assertEquals(a, p.current(referer = "http://127.0.0.1:8711/site.css"))
        assertEquals(b, p.current(referer = "http://127.0.0.1:8711/"))
        // A stylesheet from any other origin is still remembered as before.
        assertEquals(a, p.current(referer = "https://cdn.example/a.css"))
        assertEquals(a, p.current(referer = "https://cdn.example/"))
    }

    @Test
    fun `a frame the destination loads is not remembered as the page on screen's`() {
        val p = onA()
        p.answered(b, replacesDocument = true, fetchedByWebView = true)
        p.frameRequested("http://127.0.0.1:8711/inner", referer = b)
        assertEquals(b, p.current(referer = "http://127.0.0.1:8711/"))
    }

    @Test
    fun `a frame the page requested before its commit reached the UI thread is its frame after`() {
        // R3-F1 (R9): B embeds a frame from C's site; the frame's document
        // request (Referer: B) arrives before onPageStarted(B). Once B is
        // on screen and a link to C is pending, the frame's bare-origin
        // requests are still B's.
        val c = "http://10-0-2-2.nip.io:8711/c"
        val frame = "http://10-0-2-2.nip.io:8711/frame"
        for (webViewFetches in listOf(true, false)) {
            val p = onA()
            p.answered(b, replacesDocument = true, fetchedByWebView = webViewFetches)
            p.frameRequested(frame, referer = b)
            p.committed(b)
            p.answered(c, replacesDocument = true, fetchedByWebView = true)
            assertEquals(b, p.current(referer = "http://10-0-2-2.nip.io:8711/"))
            assertEquals(b, p.current(referer = frame))
            assertEquals(c, p.current(referer = c))
        }
    }

    @Test
    fun `a pending page's frames are dropped when it never commits or is superseded`() {
        val c = "http://10-0-2-2.nip.io:8711/c"
        val frame = "http://10-0-2-2.nip.io:8711/frame"
        val kept = onA()
        kept.answered(b, replacesDocument = true, fetchedByWebView = true)
        kept.frameRequested(frame, referer = b)
        kept.kept()
        kept.committed(a)
        kept.answered(c, replacesDocument = true, fetchedByWebView = true)
        assertEquals(c, kept.current(referer = "http://10-0-2-2.nip.io:8711/"))

        val superseded = onA()
        superseded.answered(b, replacesDocument = true, fetchedByWebView = true)
        superseded.frameRequested(frame, referer = b)
        superseded.answered("https://other.example/", replacesDocument = true)
        superseded.committed("https://other.example/")
        superseded.answered(c, replacesDocument = true, fetchedByWebView = true)
        assertEquals(c, superseded.current(referer = "http://10-0-2-2.nip.io:8711/"))
    }

    @Test
    fun `remembered frames are bounded`() {
        val p = onA()
        repeat(1000) { p.frameRequested("https://churn.example/f$it", referer = a) }
        p.answered("https://dest.example/", replacesDocument = true, fetchedByWebView = true)
        assertEquals("https://dest.example/", p.current(referer = "https://dest.example/"))
        p.answered("https://churn.example/x", replacesDocument = true, fetchedByWebView = true)
        assertEquals(a, p.current(referer = "https://churn.example/"))
    }

    @Test
    fun `a fetched navigation that never commits stops claiming requests`() {
        val p = onA()
        p.answered(b, replacesDocument = true, fetchedByWebView = true)
        p.kept(b)
        assertEquals(a, p.current(referer = b))
        p.answered(b, replacesDocument = true, fetchedByWebView = true)
        p.kept()
        assertEquals(a, p.current(referer = b))
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

    @Test
    fun `a same-site navigation's early cross-origin frame stays the new page's`() {
        val a1 = "https://news.example/a1"
        val a2 = "https://news.example/a2"
        val embed = "https://video.example/embed"
        val watch = "https://video.example/watch"

        val link = AdblockPage().apply { committed(a1) }
        link.answered(a2, replacesDocument = true, fetchedByWebView = true)
        link.frameRequested(embed, referer = "https://news.example/")
        // Can't tell whose: it's the page on screen's frame meanwhile.
        link.answered(watch, replacesDocument = true, fetchedByWebView = true)
        assertEquals(a1, link.current(referer = "https://video.example/"))
        link.answered(a2, replacesDocument = true, fetchedByWebView = true)
        link.frameRequested(embed, referer = "https://news.example/")
        link.committed(a2)
        link.answered(watch, replacesDocument = true, fetchedByWebView = true)
        assertEquals(a2, link.current(referer = "https://video.example/"))

        val reload = AdblockPage().apply { committed(a2) }
        reload.answered(a2, replacesDocument = true, fetchedByWebView = true)
        reload.frameRequested(embed, referer = "https://news.example/")
        reload.committed(a2)
        reload.answered(watch, replacesDocument = true, fetchedByWebView = true)
        assertEquals(a2, reload.current(referer = "https://video.example/"))
    }

    @Test
    fun `a different navigation committing doesn't adopt the pending page's frames`() {
        val p = "https://p.example/"
        val page = onA()
        page.answered(b, replacesDocument = true, fetchedByWebView = true)
        page.frameRequested("https://ads.example/frame", referer = b)
        // Back to a page restored from the back/forward cache.
        page.committed(p)
        page.answered("https://ads.example/x", replacesDocument = true, fetchedByWebView = true)
        assertEquals("https://ads.example/x", page.current(referer = "https://ads.example/"))

        // The pending navigation, at a URL the WebView normalised, still does.
        val normalised = onA()
        normalised.answered(b, replacesDocument = true, fetchedByWebView = true)
        normalised.frameRequested("https://ads.example/frame", referer = b)
        normalised.committed("$b?")
        normalised.answered("https://ads.example/x", replacesDocument = true, fetchedByWebView = true)
        assertEquals("$b?", normalised.current(referer = "https://ads.example/"))
    }

    @Test
    fun `a service worker's page adopts the frames it requested before its commit`() {
        val sw = "http://localhost:8712/a"
        val embed = "http://10-0-2-2.nip.io:8711/embed"
        val nested = "https://ads.example/inner"
        val dest = "http://10-0-2-2.nip.io:8711/stall"
        // A reload the SW answers: no answer reaches the interceptor, and
        // the frame's own request lands before onPageStarted.
        val page = AdblockPage().apply { committed(sw) }
        page.frameRequested(embed, referer = "http://localhost:8712/")
        page.frameRequested(nested, referer = "http://10-0-2-2.nip.io:8711/")
        page.committed(sw)
        page.answered(dest, replacesDocument = true, fetchedByWebView = true)
        assertEquals(sw, page.current(referer = "http://10-0-2-2.nip.io:8711/"))
        assertEquals(sw, page.current(referer = "https://ads.example/"))

        // A link the SW answers, to another page of its site.
        val link = AdblockPage().apply { committed("http://localhost:8712/start") }
        link.frameRequested(embed, referer = "http://localhost:8712/")
        link.committed(sw)
        link.answered(dest, replacesDocument = true, fetchedByWebView = true)
        assertEquals(sw, link.current(referer = "http://10-0-2-2.nip.io:8711/"))
    }

    @Test
    fun `a superseded network navigation doesn't stop a service worker's page adopting its frames`() {
        val a2 = "https://news.example/a2"
        val page = AdblockPage().apply { committed("https://news.example/a1") }
        // A link to a slow page the WebView fetches, abandoned for another
        // article the SW answers: the new navigation's start drops it.
        page.answered("https://slow.example/x", replacesDocument = true, fetchedByWebView = true)
        page.kept()
        page.frameRequested("https://video.example/embed", referer = "https://news.example/")
        page.committed(a2)
        page.answered("https://video.example/watch", replacesDocument = true, fetchedByWebView = true)
        assertEquals(a2, page.current(referer = "https://video.example/"))
    }

    @Test
    fun `a page restored from the back-forward cache doesn't adopt the outgoing page's frames`() {
        val back = "https://p.example/"
        val page = onA()
        page.frameRequested("https://ads.example/frame", referer = a)
        page.committed(back)
        page.answered("https://ads.example/x", replacesDocument = true, fetchedByWebView = true)
        assertEquals("https://ads.example/x", page.current(referer = "https://ads.example/"))

        // Nor frames from before an answered navigation.
        val answered = onA()
        answered.frameRequested("https://ads.example/frame", referer = a)
        answered.answered(b, replacesDocument = true)
        answered.committed(b)
        answered.committed(a)
        answered.answered("https://ads.example/x", replacesDocument = true, fetchedByWebView = true)
        assertEquals("https://ads.example/x", answered.current(referer = "https://ads.example/"))
    }
}
