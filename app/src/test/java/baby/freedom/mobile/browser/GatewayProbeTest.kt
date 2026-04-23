package baby.freedom.mobile.browser

import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Unit tests for [GatewayProbe]. A tiny raw-socket HTTP test server
 * stands in for the local bee-lite gateway — bundled rather than using
 * `com.sun.net.httpserver` because Android's `android.jar` doesn't
 * export the `com.sun.*` internals to unit tests.
 */
class GatewayProbeTest {

    private lateinit var server: StubHttpServer

    @Before
    fun setUp() {
        server = StubHttpServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.stop()
    }

    @Test
    fun `returns Ok on first 200`() = runBlocking {
        server.respondWith { _ -> Response(200) }
        val outcome = GatewayProbe().probe("${server.baseUrl}/bzz/abc")
        assertEquals(GatewayProbe.Outcome.Ok, outcome)
    }

    @Test
    fun `keeps polling through 404 then returns Ok`() = runBlocking {
        val hits = AtomicInteger(0)
        server.respondWith { _ ->
            val n = hits.incrementAndGet()
            if (n < 3) Response(404) else Response(200)
        }
        val outcome = GatewayProbe().probe(
            "${server.baseUrl}/bzz/abc",
            delaysMs = longArrayOf(0L, 50L, 50L, 50L, 50L),
        )
        assertEquals(GatewayProbe.Outcome.Ok, outcome)
        assertEquals(3, hits.get())
    }

    @Test
    fun `transient 500 same as 404`() = runBlocking {
        val hits = AtomicInteger(0)
        server.respondWith { _ ->
            val n = hits.incrementAndGet()
            if (n < 2) Response(500) else Response(200)
        }
        val outcome = GatewayProbe().probe(
            "${server.baseUrl}/bzz/abc",
            delaysMs = longArrayOf(0L, 50L, 50L),
        )
        assertEquals(GatewayProbe.Outcome.Ok, outcome)
    }

    @Test
    fun `returns Other for unexpected status`() = runBlocking {
        server.respondWith { _ -> Response(418) }
        val outcome = GatewayProbe().probe("${server.baseUrl}/bzz/abc")
        assertEquals(GatewayProbe.Outcome.Other(418), outcome)
    }

    @Test
    fun `returns NotFound when overall timeout elapses`() = runBlocking {
        server.respondWith { _ -> Response(404) }
        val outcome = GatewayProbe().probe(
            headUrl = "${server.baseUrl}/bzz/abc",
            delaysMs = longArrayOf(0L, 50L),
            attemptTimeoutMs = 500L,
            overallTimeoutMs = 300L,
        )
        assertEquals(GatewayProbe.Outcome.NotFound, outcome)
    }

    @Test
    fun `returns Unreachable when server is down`() = runBlocking {
        val deadPort = server.port
        server.stop()
        val outcome = GatewayProbe().probe(
            headUrl = "http://127.0.0.1:$deadPort/bzz/abc",
            delaysMs = longArrayOf(0L),
            attemptTimeoutMs = 1_000L,
            overallTimeoutMs = 2_000L,
            // Disable the ECONNREFUSED grace window — the server is
            // gone and never coming back, so we want the probe to
            // report that on the very first attempt.
            unreachableGraceMs = 0L,
        )
        assertTrue(
            "expected Unreachable, got $outcome",
            outcome is GatewayProbe.Outcome.Unreachable,
        )
    }

    @Test
    fun `treats ECONNREFUSED as transient within grace window`() = runBlocking {
        val port = server.port
        server.stop()

        // Bring the server back up on the same port after a short delay
        // so the probe's retry inside the grace window actually finds it.
        // Matches the "gateway socket binds a moment after NodeStatus
        // flips to Running" race we want to tolerate in production.
        val bootstrapper = launch {
            delay(150)
            server = StubHttpServer(requestedPort = port).apply {
                start()
                respondWith { _ -> Response(200) }
            }
        }

        val outcome = GatewayProbe().probe(
            headUrl = "http://127.0.0.1:$port/bzz/abc",
            delaysMs = longArrayOf(0L, 50L, 50L, 50L, 50L, 50L),
            attemptTimeoutMs = 1_000L,
            overallTimeoutMs = 5_000L,
            unreachableGraceMs = 3_000L,
        )
        bootstrapper.join()
        assertEquals(GatewayProbe.Outcome.Ok, outcome)
    }

    @Test
    fun `returns Aborted when cancelled mid-attempt`() = runBlocking {
        // Server accepts the connection then hangs without writing a
        // response. The probe blocks in HttpURLConnection.connect /
        // getResponseCode until its per-attempt timeout — cancelling
        // the coroutine mid-flight must return Aborted.
        server.respondWith { _ -> Response(hangMs = 5_000) }

        coroutineScope {
            var outcome: GatewayProbe.Outcome? = null
            val job: Job = launch {
                outcome = GatewayProbe().probe(
                    headUrl = "${server.baseUrl}/bzz/abc",
                    delaysMs = longArrayOf(0L),
                    attemptTimeoutMs = 10_000L,
                    overallTimeoutMs = 20_000L,
                )
            }
            delay(200)
            job.cancel()
            withTimeout(5_000) { job.join() }
            val seen = outcome
            assertTrue(
                "expected null or Aborted, got $seen",
                seen == null || seen == GatewayProbe.Outcome.Aborted,
            )
        }
    }

    @Test
    fun `per-attempt timeout fires and retries`() = runBlocking {
        val hits = AtomicInteger(0)
        server.respondWith { _ ->
            val n = hits.incrementAndGet()
            if (n == 1) Response(200, hangMs = 1_000) else Response(200)
        }
        val outcome = GatewayProbe().probe(
            headUrl = "${server.baseUrl}/bzz/abc",
            delaysMs = longArrayOf(0L, 50L, 50L),
            attemptTimeoutMs = 200L,
            overallTimeoutMs = 5_000L,
        )
        assertEquals(GatewayProbe.Outcome.Ok, outcome)
        assertTrue("expected >=2 hits, got ${hits.get()}", hits.get() >= 2)
    }

    @Test
    fun `sleep between attempts is abortable`() = runBlocking {
        server.respondWith { _ -> Response(404) }

        coroutineScope {
            val start = System.currentTimeMillis()
            val deferred = async {
                GatewayProbe().probe(
                    headUrl = "${server.baseUrl}/bzz/abc",
                    delaysMs = longArrayOf(0L, 5_000L),
                    attemptTimeoutMs = 1_000L,
                    overallTimeoutMs = 60_000L,
                )
            }
            delay(200)
            deferred.cancel()
            val elapsed = System.currentTimeMillis() - start
            assertTrue("probe took too long: ${elapsed}ms", elapsed < 2_000)
        }
    }
}

// --- Test server ----------------------------------------------------------

private data class Response(
    val status: Int = 200,
    val body: String = "",
    /** Pause this long before writing headers (to simulate slow peers). */
    val hangMs: Long = 0L,
)

/**
 * Minimal HTTP/1.1 server that speaks just enough to answer a HEAD or
 * GET with a status line and a Content-Length. Using raw sockets keeps
 * the tests portable across Android + JVM (avoids
 * `com.sun.net.httpserver`, which isn't on the Android unit-test
 * classpath).
 */
private class StubHttpServer(private val requestedPort: Int = 0) {
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val responder = java.util.concurrent.atomic.AtomicReference<((String) -> Response)?>(null)

    val port: Int get() = serverSocket?.localPort ?: error("server not started")
    val baseUrl: String get() = "http://127.0.0.1:$port"

    fun start() {
        val s = ServerSocket(requestedPort, 50, java.net.InetAddress.getByName("127.0.0.1"))
        serverSocket = s
        acceptThread = thread(name = "StubHttpServer-accept", isDaemon = true) {
            while (!s.isClosed) {
                val client = try {
                    s.accept()
                } catch (_: SocketException) {
                    break
                } catch (_: Throwable) {
                    break
                }
                thread(name = "StubHttpServer-client", isDaemon = true) {
                    handle(client)
                }
            }
        }
    }

    fun stop() {
        try { serverSocket?.close() } catch (_: Throwable) {}
        serverSocket = null
        acceptThread = null
    }

    fun respondWith(handler: (String) -> Response) {
        responder.set(handler)
    }

    private fun handle(client: Socket) {
        try {
            client.use {
                val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                val requestLine = reader.readLine() ?: return
                // Drain the header block so the client doesn't block on
                // us waiting for the response before it's finished
                // writing its request.
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val path = requestLine.split(' ').getOrNull(1) ?: "/"

                val handler = responder.get() ?: { _ -> Response(200) }
                val response = handler(path)

                if (response.hangMs > 0) {
                    try { Thread.sleep(response.hangMs) } catch (_: InterruptedException) { return }
                }

                writeResponse(client.getOutputStream(), response)
            }
        } catch (_: Throwable) {
            // Swallow — test-only server, no logging needed.
        }
    }

    private fun writeResponse(out: OutputStream, r: Response) {
        val bodyBytes = r.body.toByteArray(Charsets.UTF_8)
        val reason = reasonFor(r.status)
        val head = buildString {
            append("HTTP/1.1 ").append(r.status).append(' ').append(reason).append("\r\n")
            append("Content-Length: ").append(bodyBytes.size).append("\r\n")
            append("Content-Type: text/plain\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        out.write(head.toByteArray(Charsets.UTF_8))
        if (bodyBytes.isNotEmpty()) out.write(bodyBytes)
        out.flush()
    }

    private fun reasonFor(status: Int): String = when (status) {
        200 -> "OK"
        404 -> "Not Found"
        418 -> "I'm a teapot"
        500 -> "Internal Server Error"
        else -> "Unknown"
    }
}
