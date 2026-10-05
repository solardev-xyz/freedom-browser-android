package baby.freedom.mobile.browser

import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.Semaphore
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
 * in total. Those are the limits for a request holding one of the few
 * [PatientWaits] slots: past [baseHeaderTimeoutMs] / [baseBodyStallMs] a
 * wait goes on only if it can take a slot, and fails there otherwise.
 * An answer in [retryStatuses] is fetched again with the existing
 * backoff; any other is handed to the page as is.
 *
 * Why the slots: every one of these waits blocks a thread of Chromium's
 * process-wide worker pool (WebView calls `shouldInterceptRequest` and
 * reads every intercepted body on it), and that pool is small. With half
 * a dozen of its workers blocked, *every* request in *every* tab — plain
 * https sites included — queues behind them until one returns (PR #409
 * R1-F1: 6 bodies stalled 60 s froze an unrelated `https://example.com/`
 * fetch for 58 s). The base limits are what `main` always had, so any
 * number of waits can't hold the pool longer than before; only the slot
 * holders get the long patience.
 */
internal data class GatewayFetchPolicy(
    val headerTimeoutMs: Int,
    val bodyStallTimeoutMs: Int,
    val retryStatuses: Set<Int>,
    val baseHeaderTimeoutMs: Int = headerTimeoutMs,
    val baseBodyStallMs: Int = bodyStallTimeoutMs,
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
 * (`bzz-protocol.js`) as far as the shared worker pool allows: 30 s to
 * the headers, a body that may pause for up to [SUBRESOURCE_BODY_STALL_MS]
 * between bytes (a node short on peer credit stops sending for well over
 * 10 s, and a segment thrown away then is fetched — and paid for — a
 * second time), transient 5xx retried on GET/HEAD only, and a 404 passed
 * straight through: past the page's own load a 404 is almost always a
 * real "not there", and a page probing for an optional file must not wait
 * out the whole backoff for it. A media file keeps its longer 60 s header
 * wait. The waits past `main`'s own limits (10 s, 60 s for media) are
 * [PatientWaits]-gated: only a few requests at a time get them, the rest
 * time out where they always did.
 */
internal fun gatewayFetchPolicy(mainFrame: Boolean, method: String, media: Boolean): GatewayFetchPolicy {
    val base = if (media) MEDIA_READ_TIMEOUT_MS else NAVIGATION_READ_TIMEOUT_MS
    if (mainFrame) return GatewayFetchPolicy(base, base, NAVIGATION_RETRY_STATUSES)
    val idempotent = method.equals("GET", ignoreCase = true) || method.equals("HEAD", ignoreCase = true)
    return GatewayFetchPolicy(
        headerTimeoutMs = if (media) MEDIA_READ_TIMEOUT_MS else SUBRESOURCE_HEADER_TIMEOUT_MS,
        bodyStallTimeoutMs = SUBRESOURCE_BODY_STALL_MS,
        retryStatuses = if (idempotent) SUBRESOURCE_RETRY_STATUSES else emptySet(),
        baseHeaderTimeoutMs = base,
        baseBodyStallMs = base,
    )
}

// A cold Swarm node regularly answers 404 for a chunk that's still being
// fetched, and brief 5xx from the node itself resolve on retry too — for a
// navigation, which has no other chance to wait for the site to warm up.
private val NAVIGATION_RETRY_STATUSES = setOf(404, 500, 502, 503, 504)

private const val NAVIGATION_READ_TIMEOUT_MS = 10_000

/** Media reads: a Swarm chunk can take well past the navigation 10 s. */
internal const val MEDIA_READ_TIMEOUT_MS = 60_000

/**
 * Time to first byte for a subresource holding a [PatientWaits] slot, as
 * desktop's `ATTEMPT_TIMEOUT_MS`.
 */
internal const val SUBRESOURCE_HEADER_TIMEOUT_MS = 30_000

/**
 * How long a subresource body holding a [PatientWaits] slot may go silent
 * before the read fails. Bounded so a node that hangs mid-body doesn't
 * hold the request, its slot and a pool thread forever; a node that is
 * merely out of credit resumes well within it.
 */
internal const val SUBRESOURCE_BODY_STALL_MS = 120_000

/**
 * The few waits on the gateway allowed past `main`'s limits at once
 * ([GatewayFetchPolicy]). Process-wide, because the pool they'd block
 * is: WebView runs the interceptor and every intercepted body read on
 * Chromium's own worker pool, sized from the core count (at least 3
 * foreground workers, `cores - 1` on a bigger device). A third of that,
 * at least one, leaves the rest of the browser its threads however many
 * dweb reads stall; a held slot is given back as soon as its wait ends.
 */
internal class PatientWaits(slots: Int) {
    private val semaphore = Semaphore(slots)

    fun tryTake(): Boolean = semaphore.tryAcquire()

    fun give() = semaphore.release()

    companion object {
        val shared = PatientWaits(
            (maxOf(3, Runtime.getRuntime().availableProcessors() - 1) / 3).coerceAtLeast(1),
        )
    }
}

private val watchdog: ScheduledExecutorService =
    Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "gateway-wait-watchdog").apply { isDaemon = true }
    }

/**
 * Enforces [GatewayFetchPolicy]'s header limits from outside the thread
 * waiting on the headers: [HttpURLConnection.setReadTimeout] can only be
 * one value for the whole exchange, and is set to the (longer) body stall
 * limit. At [baseMs] a wait with no headers yet takes a [patience] slot
 * and goes on to [timeoutMs], or — no slot free, or no longer limit — has
 * every connection [track]ed for it (each redirect hop's) disconnected
 * from the watchdog thread. A disconnect from another thread fails the
 * blocked `responseCode` at once.
 *
 * Not covered: a DNS lookup [TorRouting.openFollowingRedirects] makes
 * before it has a connection to [track] (pinning a direct hop to an
 * external gateway, checking a redirect target isn't this device) can't
 * be interrupted, so an attempt can run past its deadline by that
 * lookup's own time. A connection tracked after the deadline is
 * disconnected there and then, so it ends as soon as the lookup returns.
 * The local node needs no lookup.
 */
internal class HeaderDeadline(
    private val baseMs: Int,
    private val timeoutMs: Int = baseMs,
    private val patience: PatientWaits = PatientWaits.shared,
) {
    private val lock = Any()
    private val connections = mutableListOf<HttpURLConnection>()
    private var done = false
    private var patient = false
    private var task: ScheduledFuture<*>? = null

    /** Did the deadline pass before the headers arrived? */
    @Volatile
    var expired = false
        private set

    /** Has this wait gone on past [baseMs] on a [patience] slot? */
    val extended: Boolean get() = synchronized(lock) { patient }

    init {
        synchronized(lock) { task = watchdog.schedule(::atBase, baseMs.toLong(), TimeUnit.MILLISECONDS) }
    }

    private fun atBase() {
        synchronized(lock) {
            if (done) return
            if (timeoutMs > baseMs && patience.tryTake()) {
                patient = true
                task = watchdog.schedule(::expire, (timeoutMs - baseMs).toLong(), TimeUnit.MILLISECONDS)
                return
            }
        }
        expire()
    }

    private fun expire() {
        val doomed = synchronized(lock) {
            if (done) return
            done = true
            expired = true
            release()
            connections.toList()
        }
        doomed.forEach { runCatching { it.disconnect() } }
    }

    fun track(conn: HttpURLConnection) {
        val late = synchronized(lock) {
            if (!done) connections += conn
            expired
        }
        if (late) runCatching { conn.disconnect() }
    }

    /** The headers are in (or the attempt is over): stop the clock, give back any slot. */
    fun headersReceived() {
        synchronized(lock) {
            if (!done) {
                done = true
                task?.cancel(false)
            }
            connections.clear()
            release()
        }
    }

    // Under [lock].
    private fun release() {
        if (patient) {
            patient = false
            patience.give()
        }
    }
}

/**
 * Enforces [GatewayFetchPolicy]'s body stall limits the same way: the
 * connection's own read timeout is the long [GatewayFetchPolicy.bodyStallTimeoutMs];
 * a read still silent after [baseMs] takes a [patience] slot and goes on
 * to that, or — no slot free — disconnects [conn], which fails the read
 * at once, as the base timeout always did. The slot is given back when
 * the read returns, whatever it returns.
 */
internal class BodyStallGuard(
    private val conn: HttpURLConnection,
    private val baseMs: Int,
    private val patience: PatientWaits = PatientWaits.shared,
) {
    private val lock = Any()
    private var readSince = -1L // System.nanoTime() when the read in flight began
    private var reads = 0L // which read that is
    private var patient = false
    private var closed = false
    private var task: ScheduledFuture<*>? = null

    /** Did a read go silent past [baseMs] with no slot free? */
    @Volatile
    var cutOff = false
        private set

    /** Has a read gone on past [baseMs] on a slot (counted for tests)? */
    @Volatile
    var extensions = 0
        private set

    fun <T> reading(block: () -> T): T {
        synchronized(lock) {
            reads++
            readSince = System.nanoTime()
            if (task == null && !closed) task = watchdog.schedule(::check, baseMs.toLong(), TimeUnit.MILLISECONDS)
        }
        try {
            return block()
        } finally {
            synchronized(lock) {
                readSince = -1L
                release()
            }
        }
    }

    private fun check() {
        synchronized(lock) {
            task = null
            if (closed || readSince < 0 || patient) return
            val waitedMs = (System.nanoTime() - readSince) / 1_000_000
            if (waitedMs < baseMs) {
                // An earlier read's check: this read has its own time left.
                task = watchdog.schedule(::check, baseMs - waitedMs, TimeUnit.MILLISECONDS)
                return
            }
            if (patience.tryTake()) {
                patient = true
                extensions++
                return
            }
            cutOff = true
        }
        runCatching { conn.disconnect() }
    }

    fun close() {
        synchronized(lock) {
            closed = true
            task?.cancel(false)
            task = null
            release()
        }
    }

    // Under [lock].
    private fun release() {
        if (patient) {
            patient = false
            patience.give()
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
 * paying for, chunks nobody will read. Every read and skip goes through
 * [stall], when given, so a silent body is held to its stall limits.
 */
internal class DisconnectOnCloseInputStream(
    body: InputStream,
    private val conn: HttpURLConnection,
    private val stall: BodyStallGuard? = null,
) : FilterInputStream(body) {
    private val ended = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    private inline fun <T> guarded(crossinline block: () -> T): T =
        if (stall == null) block() else stall.reading { block() }

    override fun read(): Int = guarded { super.read() }.also { if (it < 0) ended.set(true) }

    override fun read(b: ByteArray, off: Int, len: Int): Int =
        guarded { super.read(b, off, len) }.also { if (it < 0) ended.set(true) }

    override fun skip(n: Long): Long = guarded { super.skip(n) }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        stall?.close()
        if (ended.get()) {
            runCatching { super.close() }
        } else {
            runCatching { conn.disconnect() }
            runCatching { super.close() }
        }
    }
}
