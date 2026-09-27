package baby.freedom.mobile.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** File naming and `data:` decoding for the image context-menu actions (#84). */
class ImageActionsTest {

    private val ext: (String) -> String? = {
        when (it) {
            "image/png" -> "png"
            "image/jpeg" -> "jpg"
            "image/webp" -> "webp"
            else -> null
        }
    }

    @Test
    fun `the URL's own file name is kept, with an extension matching the bytes`() {
        assertEquals("cat.jpg", imageFileName("https://a.com/pics/cat.jpeg?w=200#x", "image/jpeg", ext))
        assertEquals("cat.webp", imageFileName("https://a.com/pics/cat.png", "image/webp", ext))
    }

    @Test
    fun `a percent-encoded name is decoded and made filesystem-safe`() {
        assertEquals("my cat_.png", imageFileName("https://a.com/my%20cat%3F.png", "image/png", ext))
    }

    @Test
    fun `a hash or an empty path saves as image`() {
        val hash = "a".repeat(64)
        assertEquals("image.png", imageFileName("http://127.0.0.1:1633/bzz/$hash", "image/png", ext))
        assertEquals("image.png", imageFileName("https://a.com/", "image/png", ext))
        assertEquals("image.png", imageFileName("data:image/png;base64,AAAA", "image/png", ext))
    }

    @Test
    fun `an unknown mime keeps the URL's extension, or falls back to png`() {
        assertEquals("logo.svg", imageFileName("https://a.com/logo.svg", "image/x-unknown", ext))
        assertEquals("logo.png", imageFileName("https://a.com/logo", "image/x-unknown", ext))
    }

    @Test
    fun `malformed data URLs decode to nothing`() {
        assertNull(decodeDataUrl("https://a.com/x.png"))
        assertNull(decodeDataUrl("data:image/png;base64"))
        assertNull(decodeDataUrl("data:image/png;base64,"))
    }

    @Test
    fun `only images count as images`() {
        assertEquals("image/png", imageMimeFor("image/png", null, null))
        assertEquals("image/svg+xml", imageMimeFor("Image/SVG+XML; charset=utf-8", null, null))
        // A gateway's octet-stream: the bytes, then the extension, decide.
        assertEquals("image/webp", imageMimeFor("application/octet-stream", "image/webp", null))
        assertEquals("image/jpeg", imageMimeFor("application/octet-stream", null, "image/jpeg"))
        // `data:,hello` (text/plain) and an HTML error page are not images.
        assertNull(imageMimeFor("text/plain", null, null))
        assertNull(imageMimeFor("text/html", null, null))
        assertNull(imageMimeFor(null, null, "text/html"))
        assertNull(imageMimeFor(null, null, null))
    }

    @Test
    fun `cookies go only to hops on the page's own site`() {
        val page = "https://news.example.co.uk/article"
        assertTrue(sendsCookiesTo(page, "https://img.example.co.uk/x.png"))
        assertTrue(sendsCookiesTo(page, "https://example.co.uk/x.png"))
        // Another site — the redirect target of an open redirect, a CDN.
        assertFalse(sendsCookiesTo(page, "https://evil.co.uk/x.png"))
        assertFalse(sendsCookiesTo(page, "https://attacker.example/x.png"))
        // Same host, other scheme: not same-site.
        assertFalse(sendsCookiesTo(page, "http://news.example.co.uk/x.png"))
        // IP literals are their own site.
        assertTrue(sendsCookiesTo("http://10.0.2.2:8153/", "http://10.0.2.2:8153/redir"))
        assertFalse(sendsCookiesTo("http://10.0.2.2:8153/", "http://127.0.0.1:8154/x.png"))
        assertFalse(sendsCookiesTo("http://[::1]:80/", "http://[::2]/x.png"))
        // No page, or a page that isn't http(s) (a dweb display URL).
        assertFalse(sendsCookiesTo(null, "https://a.com/x.png"))
        assertFalse(sendsCookiesTo("name.eth/path", "https://a.com/x.png"))
        assertFalse(sendsCookiesTo("bzz://abc/", "https://a.com/x.png"))
    }

    @Test
    fun `a secure page's image is never refetched in cleartext`() {
        val page = "https://news.example/article"
        // https hops pass as they are.
        assertEquals("https://cdn.example/a.png", secureHopFor(page, "https://cdn.example/a.png"))
        // http hops (the src itself, or an https -> http redirect) are upgraded,
        // rest of the URL untouched, explicit ports kept except http's :80.
        assertEquals("https://x.example/a%20b.png?q=1", secureHopFor(page, "http://x.example/a%20b.png?q=1"))
        assertEquals("https://x.example:8080/a.png", secureHopFor(page, "http://x.example:8080/a.png"))
        assertEquals("https://x.example/a.png", secureHopFor(page, "http://x.example:80/a.png"))
        assertEquals("https://u@x.example/a.png", secureHopFor(page, "HTTP://u@x.example/a.png"))
        // Dweb pages (virtual https origins) and an unknown page count as secure.
        assertEquals("https://x.example/a.png", secureHopFor("bzz://abc/", "http://x.example/a.png"))
        assertEquals("https://x.example/a.png", secureHopFor("name.eth/path", "http://x.example/a.png"))
        assertEquals("https://x.example/a.png", secureHopFor(null, "http://x.example/a.png"))
        // Loopback is potentially trustworthy: the local gateway stays http.
        assertEquals("http://127.0.0.1:1633/bzz/x", secureHopFor(page, "http://127.0.0.1:1633/bzz/x"))
        assertEquals("http://localhost:8080/a.png", secureHopFor(page, "http://localhost:8080/a.png"))
        assertEquals("http://[::1]:8080/a.png", secureHopFor(page, "http://[::1]:8080/a.png"))
        assertEquals("http://127.8.9.10/a.png", secureHopFor(page, "http://127.8.9.10/a.png"))
        assertEquals("http://a.localhost/a.png", secureHopFor(page, "http://a.localhost/a.png"))
        // ...but only as an IP literal: a DNS name that starts with "127." is
        // an ordinary host and goes out over https.
        assertEquals("https://127.tracker.example/x.png", secureHopFor(page, "http://127.tracker.example/x.png"))
        assertEquals("https://127.0.0.1.evil.example/x.png", secureHopFor(page, "http://127.0.0.1.evil.example/x.png"))
        assertEquals("https://localhost.evil.example/x.png", secureHopFor(page, "http://localhost.evil.example/x.png"))
        // An http page's http images load in the clear, as they did in the page.
        assertEquals("http://x.example/a.png", secureHopFor("http://site.example/", "http://x.example/a.png"))
        // Nothing but http(s) is fetched.
        assertNull(secureHopFor(page, "ftp://x.example/a.png"))
        assertNull(secureHopFor(page, "file:///sdcard/a.png"))
        assertNull(secureHopFor(page, "not a url"))
    }

    // ---- hard deadline ------------------------------------------------

    @Test
    fun `a trickling server is cut off at the deadline, not per read`() {
        // One byte every 300 ms: every read is well inside any sane read
        // timeout, so only a hard stop ends it.
        val server = java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        val feeder = Thread {
            runCatching {
                server.accept().use { s ->
                    while (true) {
                        s.getOutputStream().write('x'.code)
                        s.getOutputStream().flush()
                        Thread.sleep(300)
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        try {
            var socket: java.net.Socket? = null
            val started = System.nanoTime()
            val result = kotlinx.coroutines.runBlocking {
                withHardDeadline(1_000) { guard ->
                    val s = java.net.Socket(server.inetAddress, server.localPort).apply { soTimeout = 20_000 }
                    socket = s
                    if (!guard.register(s)) return@withHardDeadline null
                    val input = s.getInputStream()
                    var n = 0
                    while (input.read() >= 0) n++
                    n
                }
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertNull(result)
            assertTrue("took $elapsedMs ms", elapsedMs in 900..2_500)
            // The abandoned read's socket was closed, so its thread is free.
            Thread.sleep(100)
            assertTrue(socket!!.isClosed)
        } finally {
            server.close()
            feeder.interrupt()
        }
    }

    @Test
    fun `a block that ignores the guard is still abandoned at the deadline`() {
        // Stands in for interceptVirtualRequest, whose own connections the
        // guard cannot reach.
        val release = java.util.concurrent.CountDownLatch(1)
        val started = System.nanoTime()
        val result = kotlinx.coroutines.runBlocking {
            withHardDeadline(500) {
                while (release.count > 0) {
                    runCatching { release.await() } // swallows the interrupt
                }
                "late"
            }
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        release.countDown()
        assertNull(result)
        assertTrue("took $elapsedMs ms", elapsedMs in 400..2_000)
    }

    @Test
    fun `a fetch inside the deadline returns its result, and a throwing one null`() {
        assertEquals("ok", kotlinx.coroutines.runBlocking { withHardDeadline(5_000) { "ok" } })
        assertNull(kotlinx.coroutines.runBlocking { withHardDeadline<String>(5_000) { error("boom") } })
    }

    @Test
    fun `a guard registered after abandon closes on the spot`() {
        val guard = FetchGuard()
        var closed = 0
        assertTrue(guard.register { closed++ })
        guard.abandon()
        assertEquals(1, closed)
        assertFalse(guard.register { closed++ })
        assertEquals(2, closed)
    }
}
