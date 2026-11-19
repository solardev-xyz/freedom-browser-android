package baby.freedom.mobile.browser

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A link inside a Swarm site to a path its manifest lacks never goes
 * through [GatewayProbe] (#536, PR #557 R1-F1): the interceptor's own
 * fetch tells ant's `path address not found` 404 apart, stops retrying
 * it, and tags it for `onReceivedHttpError` ([SWARM_PATH_NOT_FOUND_HEADER]).
 */
class SwarmPathNotFoundFetchTest {
    @Test
    fun `a warm path miss on a navigation is fetched once, not retried`() {
        serve(body = """{"message":"path address not found","code":404}""") { url, asked ->
            val started = System.nanoTime()
            fetchWithRetry(navigation(), url, url, false, gatewayFetchPolicy(mainFrame = true, media = false))
            val ms = (System.nanoTime() - started) / 1_000_000
            assertEquals(1, asked.get())
            assertTrue("took $ms ms", ms < 1_000)
        }
    }

    @Test
    fun `a cold path miss ends once the next answer agrees`() {
        // Each answer slower than WARM_404_MS: a cold manifest lookup.
        serve(body = """{"message":"Path Address Not Found"}""", hangMs = GatewayProbe.WARM_404_MS + 200) { url, asked ->
            fetchWithRetry(navigation(), url, url, false, gatewayFetchPolicy(mainFrame = true, media = false))
            assertEquals(2, asked.get())
        }
    }

    @Test
    fun `a plain Not Found on a navigation is still retried`() {
        serve(body = "Not Found") { url, asked ->
            val thread = Thread {
                fetchWithRetry(navigation(), url, url, false, gatewayFetchPolicy(mainFrame = true, media = false))
            }.apply { isDaemon = true; start() }
            // 0, 250 and 500 ms back-off: three answers by ~1 s.
            thread.join(1_200)
            assertTrue("asked ${asked.get()} times", asked.get() >= 3)
        }
    }

    @Test
    fun `the tag is ours alone - a gateway can't send it`() {
        assertTrue(swarmPathNotFoundIn(mapOf("x-swarm-path-not-found" to "1")))
        assertFalse(swarmPathNotFoundIn(mapOf("Content-Type" to "text/plain")))
        assertFalse(swarmPathNotFoundIn(null))
        assertEquals(
            mapOf("Access-Control-Allow-Origin" to "*"),
            gatewayResponseHeaders(mapOf(SWARM_PATH_NOT_FOUND_HEADER to listOf("1"))),
        )
    }

    private fun serve(body: String, hangMs: Long = 0, check: (String, AtomicInteger) -> Unit) {
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { server ->
            val asked = AtomicInteger(0)
            Thread {
                while (!server.isClosed) {
                    runCatching {
                        val s = server.accept()
                        Thread {
                            s.use {
                                val reader = it.getInputStream().bufferedReader()
                                while (reader.readLine()?.isNotEmpty() == true) Unit
                                asked.incrementAndGet()
                                if (hangMs > 0) Thread.sleep(hangMs)
                                val bytes = body.toByteArray()
                                it.getOutputStream().write(
                                    ("HTTP/1.1 404 Not Found\r\nContent-Type: application/json\r\n" +
                                        "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray() + bytes,
                                )
                            }
                        }.apply { isDaemon = true; start() }
                    }
                }
            }.apply { isDaemon = true; start() }
            check("http://127.0.0.1:${server.localPort}/bzz/$HASH/nope.html", asked)
        }
    }

    private fun navigation() = object : android.webkit.WebResourceRequest {
        override fun getUrl(): android.net.Uri? = null
        override fun isForMainFrame() = true
        override fun isRedirect() = false
        override fun hasGesture() = true
        override fun getMethod() = "GET"
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }

    private companion object {
        const val HASH = "b268072928bd99d637992eb54c2a826b83affa36f61cf495297541ac42857c35"
    }
}
