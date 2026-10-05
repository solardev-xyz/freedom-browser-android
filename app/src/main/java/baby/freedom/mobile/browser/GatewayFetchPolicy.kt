package baby.freedom.mobile.browser

import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * How patiently a request to a dweb virtual origin
 * (`https://<label>.bzz.freedom.baby/…` and the other virtual suffixes,
 * plus `bzz://…` scheme subresources) waits on the local gateway.
 *
 * [headerTimeoutMs] bounds the wait for the response headers (time to
 * first byte), connect included; [bodyStallTimeoutMs] bounds a single
 * body read — how long the body may go silent, not how long it may take
 * in total. An answer in [retryStatuses] is fetched again with the
 * existing backoff; any other is handed to the page as is.
 */
internal data class GatewayFetchPolicy(
    val headerTimeoutMs: Int,
    val bodyStallTimeoutMs: Int,
    val retryStatuses: Set<Int>,
)

/** Transient gateway errors a subresource is fetched again on. */
internal val SUBRESOURCE_RETRY_STATUSES: Set<Int> = setOf(500, 502, 503, 504)

/**
 * The [GatewayFetchPolicy] for a request.
 *
 * A **main-frame** request ([mainFrame]) keeps the navigation policy it
 * always had: headers and every body read within 10 s (60 s for a media
 * file opened as a page), and a 404 retried like a 5xx — a cold Swarm
 * node regularly answers the manifest before every chunk is retrievable,
 * and the error page should not show for a site that is merely waking up.
 *
 * **Everything else** — `fetch()`/XHR, `<img>`, `<video>`, CSS, scripts,
 * frames — gets what Freedom desktop's `bzz:` handler gives every request
 * (`bzz-protocol.js`): 30 s to the headers, a body that may pause for up
 * to [SUBRESOURCE_BODY_STALL_MS] between bytes (a node short on peer
 * credit stops sending for well over 10 s, and a segment thrown away
 * then is fetched — and paid for — a second time), transient 5xx retried
 * on GET/HEAD only, and a 404 passed straight through: past the page's
 * own load a 404 is almost always a real "not there", and a page probing
 * for an optional file must not wait out the whole backoff for it. A
 * media file keeps its longer 60 s header wait.
 */
internal fun gatewayFetchPolicy(mainFrame: Boolean, method: String, media: Boolean): GatewayFetchPolicy {
    if (mainFrame) {
        val timeout = if (media) MEDIA_READ_TIMEOUT_MS else NAVIGATION_READ_TIMEOUT_MS
        return GatewayFetchPolicy(timeout, timeout, NAVIGATION_RETRY_STATUSES)
    }
    val idempotent = method.equals("GET", ignoreCase = true) || method.equals("HEAD", ignoreCase = true)
    return GatewayFetchPolicy(
        headerTimeoutMs = if (media) MEDIA_READ_TIMEOUT_MS else SUBRESOURCE_HEADER_TIMEOUT_MS,
        bodyStallTimeoutMs = SUBRESOURCE_BODY_STALL_MS,
        retryStatuses = if (idempotent) SUBRESOURCE_RETRY_STATUSES else emptySet(),
    )
}

// A cold Swarm node regularly answers 404 for a chunk that's still being
// fetched, and brief 5xx from the node itself resolve on retry too — for a
// navigation, which has no other chance to wait for the site to warm up.
private val NAVIGATION_RETRY_STATUSES = setOf(404, 500, 502, 503, 504)

private const val NAVIGATION_READ_TIMEOUT_MS = 10_000

/** Media reads: a Swarm chunk can take well past the navigation 10 s. */
internal const val MEDIA_READ_TIMEOUT_MS = 60_000

/** Time to first byte for a subresource, as desktop's `ATTEMPT_TIMEOUT_MS`. */
internal const val SUBRESOURCE_HEADER_TIMEOUT_MS = 30_000

/**
 * How long a subresource body may go silent before the read fails.
 * Bounded only so a node that hangs mid-body doesn't hold the request
 * (and a thread) forever; a node that is merely out of credit resumes
 * well within it.
 */
internal const val SUBRESOURCE_BODY_STALL_MS = 120_000

/**
 * Enforces [GatewayFetchPolicy.headerTimeoutMs] from outside the thread
 * waiting on the headers: [HttpURLConnection.setReadTimeout] can only be
 * one value for the whole exchange, and is set to the (longer) body stall
 * limit, so the shorter header deadline disconnects every connection
 * [track]ed for it — each redirect hop's — from the [watchdog] thread
 * unless [headersReceived] came first. A disconnect from another thread
 * fails the blocked `responseCode` at once.
 */
internal class HeaderDeadline(timeoutMs: Int) {
    private val lock = Any()
    private val connections = mutableListOf<HttpURLConnection>()
    private var done = false

    /** Did the deadline pass before the headers arrived? */
    @Volatile
    var expired = false
        private set

    private val task = watchdog.schedule(Runnable {
        val doomed = synchronized(lock) {
            if (done) return@Runnable
            done = true
            expired = true
            connections.toList()
        }
        doomed.forEach { runCatching { it.disconnect() } }
    }, timeoutMs.toLong(), TimeUnit.MILLISECONDS)

    fun track(conn: HttpURLConnection) {
        val late = synchronized(lock) {
            if (!done) connections += conn
            expired
        }
        if (late) runCatching { conn.disconnect() }
    }

    /** The headers are in: from here on the body stall limit applies. */
    fun headersReceived() {
        synchronized(lock) {
            done = true
            connections.clear()
        }
        task.cancel(false)
    }

    companion object {
        private val watchdog: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "gateway-header-deadline").apply { isDaemon = true }
            }
    }
}

/**
 * A gateway response body that drops the connection when it is closed
 * before its end. WebView closes an intercepted body as soon as the page
 * no longer wants it (an aborted `fetch()`, a seek, a removed `<video>`)
 * — once its in-flight read returns, which is the next bytes or the stall
 * limit. [HttpURLConnection]'s own close would first try to drain the
 * rest to reuse the connection; [HttpURLConnection.disconnect] closes the
 * socket there and then, so the node sees the client go and cancels the
 * retrieval (ant does since freedom-hq/ant#145) instead of fetching, and
 * paying for, chunks nobody will read.
 */
internal class DisconnectOnCloseInputStream(
    body: InputStream,
    private val conn: HttpURLConnection,
) : FilterInputStream(body) {
    private val ended = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    override fun read(): Int = super.read().also { if (it < 0) ended.set(true) }

    override fun read(b: ByteArray, off: Int, len: Int): Int =
        super.read(b, off, len).also { if (it < 0) ended.set(true) }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (ended.get()) {
            runCatching { super.close() }
        } else {
            runCatching { conn.disconnect() }
            runCatching { super.close() }
        }
    }
}
