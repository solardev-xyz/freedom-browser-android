package baby.freedom.mobile.chains.rpc

import baby.freedom.mobile.browser.Icu4jUts46
import baby.freedom.mobile.browser.WhatwgHost
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** [PinnedHttpTransport] against a loopback server that answers however a test says. */
class PinnedHttpTransportTest {
    @Before
    fun icu() {
        WhatwgHost.uts46 = Icu4jUts46
    }

    /** Accepts connections; [respond] gets the raw request and writes the raw response. */
    private class Server(val respond: (String, java.io.OutputStream) -> Unit) : AutoCloseable {
        val socket = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${socket.localPort}/rpc?x=1"
        val requests = mutableListOf<String>()
        val closed = CountDownLatch(1)
        val connections = AtomicInteger()

        init {
            thread(isDaemon = true) {
                while (!socket.isClosed) {
                    val s = try {
                        socket.accept()
                    } catch (_: IOException) {
                        break
                    }
                    connections.incrementAndGet()
                    thread(isDaemon = true) {
                        s.use {
                            val input = it.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                            val head = StringBuilder()
                            var len = 0
                            while (true) {
                                val line = input.readLine() ?: break
                                if (line.isEmpty()) break
                                head.append(line).append('\n')
                                if (line.startsWith("Content-Length:", true)) len = line.substringAfter(':').trim().toInt()
                            }
                            val body = CharArray(len).also { b -> var n = 0; while (n < len) n += input.read(b, n, len - n) }
                            synchronized(requests) { requests += head.toString() + "\n" + String(body) }
                            try {
                                respond(head.toString(), it.getOutputStream())
                                it.getOutputStream().flush()
                                while (it.getInputStream().read() >= 0) Unit
                            } catch (_: IOException) {
                            }
                            closed.countDown()
                        }
                    }
                }
            }
        }

        override fun close() = socket.close()
    }

    private fun http(status: String, headers: String, body: String) =
        "HTTP/1.1 $status\r\n$headers\r\n$body".toByteArray(Charsets.ISO_8859_1)

    @Test
    fun postsTheBodyAndReadsAContentLengthAnswer() = runBlocking {
        val answer = """{"jsonrpc":"2.0","id":1,"result":"0x1"}"""
        Server { _, out ->
            out.write(http("200 OK", "Content-Length: ${answer.length}\r\nConnection: close\r\n", answer))
            out.close()
        }.use { server ->
            assertEquals(answer, PinnedHttpTransport().post(server.url, """{"a":1}""", 2_000))
            val req = server.requests.single()
            assertTrue(req, req.startsWith("POST /rpc?x=1 HTTP/1.1\n"))
            assertTrue(req, "Host: 127.0.0.1:${server.socket.localPort}" in req)
            assertTrue(req, "Content-Type: application/json" in req)
            assertFalse("no cookies, no referrer", "Cookie" in req || "Referer" in req)
            assertTrue(req.endsWith("""{"a":1}"""))
        }
    }

    @Test
    fun readsChunkedAndUnframedAnswers() = runBlocking {
        Server { _, out ->
            out.write(http("200 OK", "Transfer-Encoding: chunked\r\n", "4\r\n{\"a\"\r\n3;x=y\r\n:1}\r\n0\r\n\r\n"))
            out.close()
        }.use { assertEquals("{\"a\":1}", PinnedHttpTransport().post(it.url, "{}", 2_000)) }
        Server { _, out ->
            out.write(http("100 Continue", "", ""))
            out.write(http("200 OK", "", "{\"b\":2}"))
            out.close()
        }.use { assertEquals("{\"b\":2}", PinnedHttpTransport().post(it.url, "{}", 2_000)) }
    }

    @Test
    fun refusesErrorsRedirectsAndOversizedAnswers() = runBlocking {
        for (status in listOf("429 Too Many Requests", "302 Found")) {
            Server { _, out ->
                out.write(http(status, "Location: http://10.0.0.1/\r\nContent-Length: 0\r\n", ""))
                out.close()
            }.use { server ->
                try {
                    PinnedHttpTransport().post(server.url, "{}", 2_000)
                    fail(status)
                } catch (e: IOException) {
                    assertEquals("HTTP ${status.take(3)}", e.message)
                }
                assertEquals("a redirect isn't followed", 1, server.connections.get())
            }
        }
        Server { _, out ->
            out.write(http("200 OK", "Content-Length: 100\r\n", "x".repeat(100)))
            out.close()
        }.use { server ->
            try {
                PinnedHttpTransport(maxBytes = 50).post(server.url, "{}", 2_000)
                fail()
            } catch (e: IOException) {
                assertTrue(e.message!!.contains("exceeds"))
            }
        }
    }

    @Test
    fun aStalledServerIsCutOffAtTheDeadline() = runBlocking {
        Server { _, out ->
            out.write(http("200 OK", "Content-Length: 1000\r\n", "{"))
            // …and then nothing: the server just waits for the client to hang up.
        }.use { server ->
            val started = System.currentTimeMillis()
            try {
                PinnedHttpTransport().post(server.url, "{}", 700)
                fail()
            } catch (e: RpcTimeoutException) {
                val took = System.currentTimeMillis() - started
                assertTrue("took $took", took in 600..2_000)
            }
            assertTrue("the socket is closed, not left to its own timeout", server.closed.await(2, TimeUnit.SECONDS))
        }
    }

    @Test
    fun aCancelledCallerIsLetGoAndTheSocketClosed() = runBlocking {
        Server { _, out ->
            out.write(http("200 OK", "Content-Length: 1000\r\n", "{"))
            // …and then nothing: the server just waits for the client to hang up.
        }.use { server ->
            val call = async { PinnedHttpTransport().post(server.url, "{}", 30_000) }
            delay(300)
            val started = System.currentTimeMillis()
            call.cancel()
            call.join()
            assertTrue(System.currentTimeMillis() - started < 500)
            assertTrue(server.closed.await(2, TimeUnit.SECONDS))
        }
    }

    @Test
    fun aPublicNameThatResolvesToTheLocalNetworkIsRefusedBeforeConnecting() = runBlocking {
        val lan = PinnedHttpTransport(resolve = { host ->
            assertEquals("rpc.example.org", host)
            arrayOf(InetAddress.getByAddress(host, byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34.toByte())), InetAddress.getByName("192.168.1.1"))
        })
        try {
            lan.post("https://rpc.example.org/", "{}", 2_000)
            fail()
        } catch (e: IOException) {
            assertTrue(e.message, e.message!!.contains("local-network"))
        }
        val device = PinnedHttpTransport(resolve = { arrayOf(InetAddress.getByName("127.0.0.1")) })
        try {
            device.post("https://rpc.example.org/", "{}", 2_000)
            fail()
        } catch (e: IOException) {
            assertTrue(e.message, e.message!!.contains("local-network"))
        }
    }

    @Test
    fun onlyLoopbackForALoopbackUrlAndOnlyPublicOtherwise() {
        fun ip(s: String) = InetAddress.getByName(s)
        for (public in listOf("93.184.216.34", "2606:2800:220:1:248:1893:25c8:1946", "1.1.1.1")) {
            assertTrue(public, PinnedHttpTransport.allowed(ip(public), loopbackUrl = false))
            assertFalse(public, PinnedHttpTransport.allowed(ip(public), loopbackUrl = true))
        }
        for (internal in listOf(
            "127.0.0.1", "::1", "0.0.0.0", "::", "10.1.2.3", "172.16.0.1", "192.168.0.1", "169.254.1.1",
            "100.64.0.1", "fe80::1", "fd00::1", "::ffff:10.0.0.1", "64:ff9b::a00:1", "224.0.0.1", "198.18.0.1",
        )) {
            assertFalse(internal, PinnedHttpTransport.allowed(ip(internal), loopbackUrl = false))
        }
        assertTrue(PinnedHttpTransport.allowed(ip("127.0.0.1"), loopbackUrl = true))
        assertTrue(PinnedHttpTransport.allowed(ip("::1"), loopbackUrl = true))
    }

    @Test
    fun onlyValidRpcUrlsAreSentTo() = runBlocking {
        for (bad in listOf("http://rpc.example.org/", "https://user:pw@rpc.example.org/", "ftp://x.example/", " https://a.example")) {
            try {
                PinnedHttpTransport(resolve = { fail("resolved $bad"); emptyArray() }).post(bad, "{}", 1_000)
                fail(bad)
            } catch (e: IOException) {
                assertEquals("not an RPC URL", e.message)
            }
        }
    }
}
