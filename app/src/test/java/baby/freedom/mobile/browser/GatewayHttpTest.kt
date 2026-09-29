package baby.freedom.mobile.browser

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [GatewayHttp]'s timeout bounds the whole request (#224 R3): a node that
 * accepts an upload and stops reading its body can't hold the call —
 * and the feed lock around it — past the deadline.
 */
class GatewayHttpTest {
    @Test
    fun `an upload the node stops draining fails at the deadline`() {
        ServerSocket(0, 1, LOOPBACK).use { server ->
            val accepted = mutableListOf<Socket>()
            val connected = AtomicBoolean(false)
            val acceptor = Thread {
                // Accept, then never read: the client's write fills the buffers and blocks.
                runCatching { accepted += server.accept(); connected.set(true) }
            }.apply { isDaemon = true; start() }
            val started = System.nanoTime()
            try {
                GatewayHttp.requestAt(
                    "http://127.0.0.1:${server.localPort}", "POST", "/bytes",
                    mapOf("content-type" to "application/octet-stream"), ByteArray(64 * 1024 * 1024), 1_000,
                )
                fail("a stalled upload returned")
            } catch (e: SocketTimeoutException) {
                // expected: the watchdog's deadline, not a refused connection
            } finally {
                accepted.forEach { runCatching { it.close() } }
                acceptor.join(1_000)
            }
            val ms = (System.nanoTime() - started) / 1_000_000
            assertTrue("the server never accepted the upload", connected.get())
            assertTrue("took $ms ms", ms < 5_000)
        }
    }

    @Test
    fun `an answer the node trickles fails at the deadline too`() {
        ServerSocket(0, 1, LOOPBACK).use { server ->
            val connected = AtomicBoolean(false)
            val acceptor = Thread {
                runCatching {
                    server.accept().use { s ->
                        connected.set(true)
                        val out = s.getOutputStream()
                        out.write("HTTP/1.1 200 OK\r\nContent-Length: 1000\r\n\r\n".toByteArray())
                        out.flush()
                        // One byte just inside the read timeout, over and over.
                        repeat(20) {
                            Thread.sleep(300)
                            out.write('x'.code)
                            out.flush()
                        }
                    }
                }
            }.apply { isDaemon = true; start() }
            val started = System.nanoTime()
            try {
                GatewayHttp.requestAt("http://127.0.0.1:${server.localPort}", "GET", "/chunks/x", emptyMap(), null, 1_000)
                fail("a trickled answer returned")
            } catch (e: SocketTimeoutException) {
                // expected: the watchdog's deadline, not a refused connection
            }
            val ms = (System.nanoTime() - started) / 1_000_000
            assertTrue("the server never accepted the request", connected.get())
            assertTrue("took $ms ms", ms < 3_000)
            acceptor.join(10_000)
        }
    }

    @Test
    fun `an answer read to its end in time is returned even if the deadline passes while it is handed back`() {
        ServerSocket(0, 1, LOOPBACK).use { server ->
            val acceptor = Thread {
                runCatching {
                    server.accept().use { s ->
                        s.getInputStream().read(ByteArray(4096))
                        s.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nX-Tag: 7\r\n\r\nok".toByteArray())
                            flush()
                        }
                        Thread.sleep(2_000)
                    }
                }
            }.apply { isDaemon = true; start() }
            // The watchdog fires between the body's end and the answer being returned (#224 R5).
            val answer = GatewayHttp.requestAt(
                "http://127.0.0.1:${server.localPort}", "GET", "/chunks/x", emptyMap(), null, 300,
                afterAnswer = { Thread.sleep(800) },
            )
            assertEquals(200, answer.status)
            assertEquals("ok", String(answer.body))
            assertEquals("7", answer.headers["x-tag"])
            acceptor.join(5_000)
        }
    }

    private companion object {
        /** The address the client dials. Android's `getLoopbackAddress()` is `::1`, which `127.0.0.1` never reaches (#224 R6-F2). */
        val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")
    }
}
