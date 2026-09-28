package baby.freedom.swarm

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * [HttpCheckpointFetcher] against a real local HTTP server: status and
 * redirect rules, the body cap, and deadlines that hold even when the
 * server stalls mid-body.
 */
class HttpCheckpointFetcherTest {

    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val sockets = mutableListOf<Socket>()
    private val url = "http://127.0.0.1:${server.localPort}/x"

    /** Answer every connection with [head] (status line + headers), then [body] chunks, pausing [pauseMs] between. */
    private fun serve(head: String, body: List<String> = emptyList(), pauseMs: Long = 0) {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                synchronized(sockets) { sockets += socket }
                thread(isDaemon = true) {
                    runCatching {
                        socket.getInputStream().read(ByteArray(4096))
                        val out = socket.getOutputStream()
                        out.write((head + "\r\n\r\n").toByteArray())
                        out.flush()
                        for (chunk in body) {
                            if (pauseMs > 0) Thread.sleep(pauseMs)
                            out.write(chunk.toByteArray())
                            out.flush()
                        }
                        socket.close()
                    }
                }
            }
        }
    }

    @After
    fun close() {
        server.close()
        synchronized(sockets) { sockets.forEach { runCatching { it.close() } } }
    }

    private fun failure(block: suspend () -> Unit): MyotisCheckpointException {
        try {
            runBlocking { block() }
        } catch (e: MyotisCheckpointException) {
            return e
        }
        fail("expected a checkpoint failure")
        throw AssertionError()
    }

    @Test
    fun `a 200 body comes back whole`() {
        serve("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nConnection: close", listOf("{\"data\":", "1}"))
        val bytes = runBlocking { HttpCheckpointFetcher().get(url, 1024) }
        assertEquals("""{"data":1}""", bytes.toString(Charsets.UTF_8))
    }

    @Test
    fun `the server's Date header comes back with the body`() {
        serve(
            "HTTP/1.1 200 OK\r\nDate: Sun, 27 Sep 2026 10:00:00 GMT\r\nContent-Type: application/json\r\nConnection: close",
            listOf("{}"),
        )
        val response = runBlocking { HttpCheckpointFetcher().fetch(url, 1024) }
        assertEquals("{}", response.body.toString(Charsets.UTF_8))
        assertEquals(1_790_503_200_000L, response.serverDateMs)
    }

    @Test
    fun `a cache's Age header moves the server time forward`() {
        // Stored by a shared cache for two hours: its Date is the origin's, from when it was stored.
        serve(
            "HTTP/1.1 200 OK\r\nDate: Sun, 27 Sep 2026 10:00:00 GMT\r\nAge: 7200\r\n" +
                "Content-Type: application/json\r\nConnection: close",
            listOf("{}"),
        )
        assertEquals(1_790_503_200_000L + 7_200_000L, runBlocking { HttpCheckpointFetcher().fetch(url, 1024) }.serverDateMs)
    }

    @Test
    fun `a malformed Age header is no server time`() {
        serve(
            "HTTP/1.1 200 OK\r\nDate: Sun, 27 Sep 2026 10:00:00 GMT\r\nAge: -5\r\n" +
                "Content-Type: application/json\r\nConnection: close",
            listOf("{}"),
        )
        assertEquals(null, runBlocking { HttpCheckpointFetcher().fetch(url, 1024) }.serverDateMs)
    }

    @Test
    fun `the server clock adds Age to Date`() {
        val date = 1_790_503_200_000L
        assertEquals(date, MyotisCheckpointResponse.serverClock(date, null))
        assertEquals(date, MyotisCheckpointResponse.serverClock(date, "0"))
        assertEquals(date + 60_000L, MyotisCheckpointResponse.serverClock(date, " 060 "))
        assertEquals(date + 2_147_483_648_000L, MyotisCheckpointResponse.serverClock(date, "99999999999999999999"))
        assertEquals(null, MyotisCheckpointResponse.serverClock(date, "1.5"))
        assertEquals(null, MyotisCheckpointResponse.serverClock(date, ""))
        assertEquals(null, MyotisCheckpointResponse.serverClock(null, "60"))
        assertEquals(null, MyotisCheckpointResponse.serverClock(0L, "60"))
    }

    @Test
    fun `no Date header is no server time`() {
        serve("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nConnection: close", listOf("{}"))
        assertEquals(null, runBlocking { HttpCheckpointFetcher().fetch(url, 1024) }.serverDateMs)
    }

    @Test
    fun `a redirect is refused, not followed`() {
        serve("HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:1/elsewhere\r\nContent-Length: 0\r\nConnection: close")
        val e = failure { HttpCheckpointFetcher().get(url, 1024) }
        assertEquals(MyotisCheckpointError.Unavailable, e.error)
        assertEquals(MyotisTransportFailure.Http, e.failure)
        assertEquals(302, e.httpStatus)
    }

    @Test
    fun `a body past the cap fails, declared or streamed`() {
        serve("HTTP/1.1 200 OK\r\nContent-Length: 5000\r\nConnection: close", listOf("x".repeat(5000)))
        assertEquals(MyotisTransportFailure.BodyLimit, failure { HttpCheckpointFetcher().get(url, 1024) }.failure)
    }

    @Test
    fun `a streamed body past the cap fails`() {
        serve("HTTP/1.1 200 OK\r\nConnection: close", List(10) { "y".repeat(500) })
        assertEquals(MyotisTransportFailure.BodyLimit, failure { HttpCheckpointFetcher().get(url, 1024) }.failure)
    }

    @Test
    fun `a server trickling its body can't stretch a request past its deadline`() {
        // One byte every 300 ms never trips a 1 s read timeout, but the whole
        // request must still end at its own 1 s deadline.
        serve("HTTP/1.1 200 OK\r\nConnection: close", List(100) { "z" }, pauseMs = 300)
        val started = System.nanoTime()
        val e = failure { HttpCheckpointFetcher(requestMs = 1_000).get(url, 1024) }
        val elapsed = (System.nanoTime() - started) / 1_000_000
        assertEquals(MyotisTransportFailure.Timeout, e.failure)
        assertTrue("took $elapsed ms", elapsed < 2_500)
    }

    @Test
    fun `cancelling a stalled request returns at once`() {
        serve("HTTP/1.1 200 OK\r\nConnection: close", listOf("never"), pauseMs = 60_000)
        runBlocking {
            val call = async { HttpCheckpointFetcher().get(url, 1024) }
            delay(300)
            val started = System.nanoTime()
            withTimeout(2_000) {
                call.cancel()
                call.join()
            }
            assertTrue((System.nanoTime() - started) / 1_000_000 < 1_000)
            assertTrue(call.isCancelled)
        }
    }
}
