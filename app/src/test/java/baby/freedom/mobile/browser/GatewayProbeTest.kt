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
import java.net.InetSocketAddress
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
    fun `keeps polling through ant's cold-start 503 and other transient 5xx`() = runBlocking {
        // Ant v0.5.56 (freedom-hq/ant#124) answers 503 when its peers
        // can't serve a chunk yet; a 502 or 504 also means "not yet".
        // None of them may end the navigation with an error page.
        for (status in listOf(502, 503, 504)) {
            val hits = AtomicInteger(0)
            server.respondWith { _ ->
                val n = hits.incrementAndGet()
                if (n < 3) Response(status) else Response(200)
            }
            val outcome = GatewayProbe().probe(
                "${server.baseUrl}/bzz/abc",
                delaysMs = longArrayOf(0L, 50L, 50L),
            )
            assertEquals("status $status", GatewayProbe.Outcome.Ok, outcome)
            assertEquals("status $status", 3, hits.get())
        }
    }

    @Test
    fun `returns NotFound when a 503 lasts the whole budget`() = runBlocking {
        server.respondWith { _ -> Response(503) }
        val outcome = GatewayProbe().probe(
            headUrl = "${server.baseUrl}/bzz/abc",
            delaysMs = longArrayOf(0L, 50L),
            attemptTimeoutMs = 500L,
            overallTimeoutMs = 300L,
        )
        assertEquals(GatewayProbe.Outcome.NotFound(lastStatus = 503, peers = null), outcome)
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
        assertEquals(GatewayProbe.Outcome.NotFound(lastStatus = 404, peers = null), outcome)
    }

    // --- #536: ant's typed 404 and 503 -------------------------------------

    private val missingPath = """{"code":404,"message":"path address not found"}"""

    @Test
    fun `a quick path-not-found 404 ends the probe at once`() = runBlocking {
        val heads = AtomicInteger(0)
        server.respondWith { req ->
            if (req.method == "HEAD") heads.incrementAndGet()
            Response(404, body = missingPath)
        }
        val started = System.currentTimeMillis()
        val outcome = GatewayProbe().probe("${server.baseUrl}/bzz/abc/nope.html")
        assertEquals(GatewayProbe.Outcome.PathNotFound, outcome)
        assertEquals(1, heads.get())
        assertTrue(System.currentTimeMillis() - started < 2_000)
    }

    @Test
    fun `the message is matched case-insensitively, as Bee's and ant's`() = runBlocking {
        server.respondWith { _ -> Response(404, body = """{"message":"Path Address Not Found"}""") }
        assertEquals(
            GatewayProbe.Outcome.PathNotFound,
            GatewayProbe().probe("${server.baseUrl}/bzz/abc/nope.html"),
        )
    }

    @Test
    fun `a cold path-not-found ends the probe once the next attempt agrees`() = runBlocking {
        val heads = AtomicInteger(0)
        server.respondWith { req ->
            // Every HEAD slower than the warm window: a fresh lookup each time.
            if (req.method == "HEAD") {
                heads.incrementAndGet()
                Response(404, hangMs = 150L)
            } else Response(404, body = missingPath)
        }
        val outcome = GatewayProbe().probe(
            "${server.baseUrl}/bzz/abc/nope.html",
            delaysMs = longArrayOf(0L, 20L),
            warm404Ms = 100L,
        )
        assertEquals(GatewayProbe.Outcome.PathNotFound, outcome)
        assertEquals(2, heads.get())
    }

    @Test
    fun `a cold path-not-found the next attempt contradicts doesn't end the probe`() = runBlocking {
        // A feed lookup that landed on an older update than the latest:
        // the page the site just added is there on the next try.
        val heads = AtomicInteger(0)
        server.respondWith { req ->
            if (req.method == "HEAD") {
                if (heads.incrementAndGet() == 1) Response(404, hangMs = 150L) else Response(200)
            } else Response(404, body = missingPath)
        }
        val outcome = GatewayProbe().probe(
            "${server.baseUrl}/bzz/abc/new.html",
            delaysMs = longArrayOf(0L, 20L),
            warm404Ms = 100L,
        )
        assertEquals(GatewayProbe.Outcome.Ok, outcome)
    }

    @Test
    fun `a cold path-not-found then a plain Not Found starts the confirmation over`() = runBlocking {
        val heads = AtomicInteger(0)
        server.respondWith { req ->
            val n = if (req.method == "HEAD") heads.incrementAndGet() else heads.get()
            when {
                req.method == "HEAD" -> Response(404, hangMs = 150L)
                n == 2 -> Response(404, body = """{"code":404,"message":"Not Found"}""")
                else -> Response(404, body = missingPath)
            }
        }
        val outcome = GatewayProbe().probe(
            "${server.baseUrl}/bzz/abc/nope.html",
            delaysMs = longArrayOf(0L, 20L),
            warm404Ms = 100L,
        )
        assertEquals(GatewayProbe.Outcome.PathNotFound, outcome)
        // Cold miss, plain miss, cold miss, cold miss: the last two agree.
        assertEquals(4, heads.get())
    }

    @Test
    fun `a plain Not Found keeps polling - the network may still have it`() = runBlocking {
        val gets = AtomicInteger(0)
        server.respondWith { req ->
            if (req.method == "GET") gets.incrementAndGet()
            Response(404, body = """{"code":404,"message":"Not Found"}""")
        }
        val outcome = GatewayProbe().probe(
            headUrl = "${server.baseUrl}/bzz/abc",
            delaysMs = longArrayOf(0L, 50L),
            overallTimeoutMs = 300L,
        )
        assertEquals(GatewayProbe.Outcome.NotFound(lastStatus = 404, peers = null), outcome)
        assertTrue(gets.get() >= 1)
    }

    @Test
    fun `a slow 404 isn't re-read - its retrieval already gave up`() = runBlocking {
        val gets = AtomicInteger(0)
        server.respondWith { req ->
            if (req.method == "GET") {
                gets.incrementAndGet()
                Response(404, body = missingPath)
            } else Response(404, hangMs = 150L)
        }
        val outcome = GatewayProbe().probe(
            headUrl = "${server.baseUrl}/bzz/abc/nope.html",
            delaysMs = longArrayOf(0L, 20L),
            overallTimeoutMs = 400L,
            quick404Ms = 100L,
        )
        assertEquals(GatewayProbe.Outcome.NotFound(lastStatus = 404, peers = null), outcome)
        assertEquals(0, gets.get())
    }

    @Test
    fun `a 503 is never read as a missing path`() = runBlocking {
        server.respondWith { _ -> Response(503, body = missingPath) }
        val outcome = GatewayProbe().probe(
            headUrl = "${server.baseUrl}/bzz/abc/nope.html",
            delaysMs = longArrayOf(0L, 50L),
            overallTimeoutMs = 300L,
        )
        assertEquals(GatewayProbe.Outcome.NotFound(lastStatus = 503, peers = null), outcome)
    }

    @Test
    fun `a probe that runs out of time reports the node's peer count`() = runBlocking {
        server.respondWith { req ->
            if (req.path == "/peers") Response(200, body = """{"peers":[{"address":"a"},{"address":"b"},{"address":"c"}]}""")
            else Response(503)
        }
        val outcome = GatewayProbe().probe(
            headUrl = "${server.baseUrl}/bzz/abc",
            delaysMs = longArrayOf(0L, 50L),
            overallTimeoutMs = 300L,
            peersUrl = "${server.baseUrl}/peers",
        )
        assertEquals(GatewayProbe.Outcome.NotFound(lastStatus = 503, peers = 3), outcome)
    }

    @Test
    fun `a node that never answered in time is reported as such`() = runBlocking {
        server.respondWith { req ->
            if (req.path == "/peers") Response(500, body = "oops") else Response(200, hangMs = 2_000L)
        }
        val outcome = GatewayProbe().probe(
            headUrl = "${server.baseUrl}/bzz/abc",
            delaysMs = longArrayOf(0L),
            attemptTimeoutMs = 200L,
            overallTimeoutMs = 100L,
            peersUrl = "${server.baseUrl}/peers",
        )
        assertEquals(GatewayProbe.Outcome.NotFound(lastStatus = null, peers = null), outcome)
    }

    @Test
    fun `a peers body trickled under the read timeout is cut at the side request's cap`() = runBlocking {
        // One byte every 300 ms: each read() comes well inside the 1 s
        // read timeout, so only a cap on the whole request can end it.
        server.respondWith { req ->
            if (req.path == "/peers") Response(200, body = "{\"peers\":[" + "{},".repeat(50) + "{}]}", trickleMs = 300L)
            else Response(503)
        }
        val started = System.nanoTime()
        val outcome = GatewayProbe().probe(
            headUrl = "${server.baseUrl}/bzz/abc",
            delaysMs = longArrayOf(0L),
            overallTimeoutMs = 0L,
            peersUrl = "${server.baseUrl}/peers",
            sideRequestTimeoutMs = 1_000L,
        )
        val ms = (System.nanoTime() - started) / 1_000_000
        assertEquals(GatewayProbe.Outcome.NotFound(lastStatus = 503, peers = null), outcome)
        assertTrue("took $ms ms: the trickle ran on past the cap", ms < 2_000)
    }

    @Test
    fun `the probe HEADs the page itself, not the site root`() {
        val base = "http://127.0.0.1:1633/bzz/$HASH"
        assertEquals("$base/docs/a.html", GatewayProbe.probeTarget("$base/docs/a.html?x=1#top"))
        assertEquals("$base/", GatewayProbe.probeTarget("$base/"))
        assertEquals(base, GatewayProbe.probeTarget(base))
        assertEquals("$base/a%20b/", GatewayProbe.probeTarget("$base/a%20b/#f"))
        // A double dot could aim the HEAD outside /bzz/: the root instead.
        assertEquals(base, GatewayProbe.probeTarget("$base/../../health"))
        assertEquals(base, GatewayProbe.probeTarget("$base/a/.%2E/b"))
        assertEquals(base, GatewayProbe.probeTarget("$base/a/%2e%2E"))
        assertEquals(base, GatewayProbe.probeTarget("$base/a\\..\\health"))
        // A single dot, or dots inside a name, are just names.
        assertEquals("$base/a/.well-known/x..y", GatewayProbe.probeTarget("$base/a/.well-known/x..y"))
        // IPFS keeps probing its root.
        val cid = "bafybeigdyrzt5sfp7udm7hu76uh7y26nf3efuylqabf3oclgtqy55fbzdi"
        assertEquals(
            "http://127.0.0.1:5001/ipfs/$cid",
            GatewayProbe.probeTarget("http://127.0.0.1:5001/ipfs/$cid/docs/a.html"),
        )
        assertEquals("not a gateway url", GatewayProbe.probeTarget("not a gateway url"))
    }

    @Test
    fun `only a JSON 404 is read ahead for a path miss`() {
        assertTrue(GatewayProbe.missingPathType("application/json"))
        assertTrue(GatewayProbe.missingPathType("application/json; charset=utf-8"))
        assertTrue(GatewayProbe.missingPathType(" Application/JSON "))
        assertEquals(false, GatewayProbe.missingPathType("text/html; charset=utf-8"))
        assertEquals(false, GatewayProbe.missingPathType("text/plain"))
        assertEquals(false, GatewayProbe.missingPathType(""))
        assertEquals(false, GatewayProbe.missingPathType(null))
    }

    @Test
    fun `only ant's path miss and a peers list parse`() {
        assertTrue(GatewayProbe.missingPathBody(missingPath))
        assertEquals(false, GatewayProbe.missingPathBody("""{"message":"Not Found"}"""))
        assertEquals(false, GatewayProbe.missingPathBody("path address not found"))
        assertEquals(false, GatewayProbe.missingPathBody("<html>404</html>"))
        assertEquals(false, GatewayProbe.missingPathBody("[".repeat(100_000)))
        assertEquals(0, GatewayProbe.peerCount("""{"peers":[]}"""))
        assertEquals(2, GatewayProbe.peerCount("""{"peers":[{},{}]}"""))
        assertEquals(null, GatewayProbe.peerCount("""{"peers":null}"""))
        assertEquals(null, GatewayProbe.peerCount("""{"peers":[{},{}"""))
    }

    @Test
    fun `returns Unreachable when server is down`() = runBlocking {
        // A port bound but never listening: a connect is refused, and
        // nothing else on the machine can start a server on it meanwhile
        // (a port just freed by stopping [server] can be).
        val dead = Socket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
        val outcome = try {
            GatewayProbe().probe(
                headUrl = "http://127.0.0.1:${dead.localPort}/bzz/abc",
                delaysMs = longArrayOf(0L),
                attemptTimeoutMs = 1_000L,
                overallTimeoutMs = 2_000L,
                // Disable the ECONNREFUSED grace window — the server is
                // gone and never coming back, so we want the probe to
                // report that on the very first attempt.
                unreachableGraceMs = 0L,
            )
        } finally {
            dead.close()
        }
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
            if (n == 1) Response(200, hangMs = 10_000) else Response(200)
        }
        // Margins are deliberately wide: the first attempt must time out
        // (hang >> attempt timeout) and a retry must succeed within its
        // own attempt budget even on a loaded CI machine — a 200ms
        // attempt timeout flaked here whenever the JVM hiccuped past it
        // on the *successful* attempt.
        val outcome = GatewayProbe().probe(
            headUrl = "${server.baseUrl}/bzz/abc",
            delaysMs = longArrayOf(0L, 50L, 50L),
            attemptTimeoutMs = 1_500L,
            overallTimeoutMs = 30_000L,
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

private const val HASH = "c0ffee00c0ffee00c0ffee00c0ffee00c0ffee00c0ffee00c0ffee00c0ffee00"

private data class Request(val method: String, val path: String)

private data class Response(
    val status: Int = 200,
    val body: String = "",
    /** Pause this long before writing headers (to simulate slow peers). */
    val hangMs: Long = 0L,
    /** Write the body one byte at a time, this far apart. */
    val trickleMs: Long = 0L,
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
    private val responder = java.util.concurrent.atomic.AtomicReference<((Request) -> Response)?>(null)

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

    fun respondWith(handler: (Request) -> Response) {
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
                val method = requestLine.split(' ').getOrNull(0) ?: "GET"
                val path = requestLine.split(' ').getOrNull(1) ?: "/"

                val handler = responder.get() ?: { _ -> Response(200) }
                val response = handler(Request(method, path))

                if (response.hangMs > 0) {
                    try { Thread.sleep(response.hangMs) } catch (_: InterruptedException) { return }
                }

                writeResponse(client.getOutputStream(), response, isHead = method == "HEAD")
            }
        } catch (_: Throwable) {
            // Swallow — test-only server, no logging needed.
        }
    }

    private fun writeResponse(out: OutputStream, r: Response, isHead: Boolean) {
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
        if (bodyBytes.isNotEmpty() && !isHead) {
            if (r.trickleMs > 0) {
                out.flush()
                for (b in bodyBytes) {
                    Thread.sleep(r.trickleMs)
                    out.write(b.toInt())
                    out.flush()
                }
            } else {
                out.write(bodyBytes)
            }
        }
        out.flush()
    }

    private fun reasonFor(status: Int): String = when (status) {
        200 -> "OK"
        404 -> "Not Found"
        418 -> "I'm a teapot"
        500 -> "Internal Server Error"
        502 -> "Bad Gateway"
        503 -> "Service Unavailable"
        504 -> "Gateway Timeout"
        else -> "Unknown"
    }
}
