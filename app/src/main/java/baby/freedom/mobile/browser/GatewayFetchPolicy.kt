package baby.freedom.mobile.browser

import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutorService
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
 * [headerTimeoutMs] bounds each hop's wait for its response headers
 * (time to first byte, counted from that hop's connect, as the read
 * timeout always did — [HeaderDeadline]); [bodyStallTimeoutMs] bounds a single
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
 * second time), transient 5xx retried, and a 404 passed straight through: past the page's own load a 404 is almost always a
 * real "not there", and a page probing for an optional file must not wait
 * out the whole backoff for it. A media file keeps its longer 60 s header
 * wait. The waits past `main`'s own limits (10 s, 60 s for media) are
 * [PatientWaits]-gated: only a few requests at a time get them, the rest
 * time out where they always did.
 *
 * Desktop retries only GET and HEAD, but the page's method doesn't matter
 * here: the gateway is only ever sent a GET or a HEAD (`fetchOnce`; a
 * `WebResourceRequest` carries no body to forward), so every request it
 * answers is idempotent and its transient 5xx is retried whatever the
 * page asked for — as `main` did (PR #409 R3-M2).
 */
internal fun gatewayFetchPolicy(mainFrame: Boolean, media: Boolean): GatewayFetchPolicy {
    val base = if (media) MEDIA_READ_TIMEOUT_MS else NAVIGATION_READ_TIMEOUT_MS
    if (mainFrame) return GatewayFetchPolicy(base, base, NAVIGATION_RETRY_STATUSES)
    return GatewayFetchPolicy(
        headerTimeoutMs = if (media) MEDIA_READ_TIMEOUT_MS else SUBRESOURCE_HEADER_TIMEOUT_MS,
        bodyStallTimeoutMs = SUBRESOURCE_BODY_STALL_MS,
        retryStatuses = SUBRESOURCE_RETRY_STATUSES,
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
 * Runs the watchdog's disconnects, each on a thread of its own: a
 * [HttpURLConnection.disconnect] can block (an https or SOCKS hop
 * mid-handshake), and on the single [watchdog] thread one such call
 * would hold up every other deadline and stall cut in the process
 * (PR #409 R2-M2). The watchdog only decides; the cut happens here.
 */
private val disconnector: ExecutorService =
    Executors.newCachedThreadPool { r ->
        Thread(r, "gateway-wait-disconnect").apply { isDaemon = true }
    }

private fun disconnectAside(conn: HttpURLConnection, then: () -> Unit = {}) {
    disconnector.execute {
        runCatching { conn.disconnect() }
        then()
    }
}

/**
 * The connect timeout of a gateway fetch ([fetchWithRetry]): each route
 * (address) the connection tries gets this for its TCP connect, then its
 * TLS handshake bounded on its own ([HeaderDeadline], [connectStretchMs]).
 */
internal const val GATEWAY_CONNECT_TIMEOUT_MS = 5_000

/**
 * What [HeaderDeadline]'s watchdog adds to the routes' own bounds before
 * it cuts a connect ([connectStretchMs]), so each route's own socket
 * timeouts — which move the connection on to the next address, as on
 * `main` — fire first, and the watchdog is only the backstop for a stall
 * no socket timeout covers.
 */
internal const val CONNECT_GRACE_MS = 1_000

/**
 * How long [HeaderDeadline] lets a hop's `connect()` run before cutting
 * it: `(connectMs + handshakeMs) × routes + graceMs`.
 *
 * That is `main`'s per-route bound for each of [routes] (as
 * [TorRouting.connectRoutes] counts them), plus [graceMs]: on `main`, Android's
 * HttpURLConnection tried each route in turn, each with its TCP connect
 * under the connect timeout and its TLS handshake (or a SOCKS proxy's
 * reply) under the read timeout, and moved on to the next route when
 * either timed out (okhttp's `RouteException` / `isRecoverable`). Here the
 * handshake of every route still has its own limit ([handshakeMs], the
 * hop's base read timeout, set on each route's socket by
 * [TorRouting.openFollowingRedirects]), so the routes add up the same way.
 * [routes] at least 1.
 */
internal fun connectStretchMs(connectMs: Int, handshakeMs: Int, routes: Int, graceMs: Int = CONNECT_GRACE_MS): Long =
    (connectMs.toLong() + handshakeMs) * routes.coerceAtLeast(1) + graceMs

/**
 * How long [HeaderDeadline] lets one proxy route of a hop on the system
 * proxy run before its tunnel is up — the TCP connect to the proxy and its
 * reply (an HTTP proxy's answer to CONNECT, a SOCKS proxy's) —
 * `max(connectMs, replyMs) + graceMs`: 11 s, 61 s for media. On `main` the
 * reply was read under the 10 s read timeout (60 s for media), so a proxy
 * that accepts TCP and never answers held a pool thread that long, then
 * the connection went direct; [connectStretchMs]'s `connect + handshake`
 * share kept it 16 s (PR #409 R4-M2). The TLS handshake through the tunnel
 * is timed apart, from when the tunnel is up ([HeaderDeadline.tunnelled]),
 * as on `main`, where it had a read timeout of its own. The connect and
 * the reply share one wait, so a proxy slow at both — each inside its own
 * `main` limit, together past this — is left for the direct route, a
 * second earlier than the cut on `main`'s worst case.
 *
 * okhttp dials an HTTP proxy at each address its name resolves to, in
 * turn, each under its own connect timeout, so every address before the
 * last adds `connectMs + replyMs` — `main`'s per-address bound — and the
 * one that answers still gets the full 11 s: a dual-stack proxy whose
 * IPv6 address drops SYNs doesn't eat the IPv4 address's share and push
 * the hop around the user's proxy ([addresses], the proxy's own name
 * looked up ahead, off the clock, by [TorRouting.openFollowingRedirects];
 * PR #409 R5-M1).
 */
internal fun tunnelStretchMs(connectMs: Int, replyMs: Int, addresses: Int = 1, graceMs: Int = CONNECT_GRACE_MS): Long =
    (connectMs.toLong() + replyMs) * (addresses.coerceAtLeast(1) - 1) + maxOf(connectMs, replyMs) + graceMs

/**
 * Enforces [GatewayFetchPolicy]'s header limits from outside the thread
 * waiting on the headers: [HttpURLConnection.setReadTimeout] can only be
 * one value for the whole exchange, and is set to the (longer) body stall
 * limit. Watches each hop of [TorRouting.openFollowingRedirects] in two
 * stretches.
 *
 * **Connecting** ([connecting] to [connected]): Android's
 * [HttpURLConnection.connect] tries each route (each address the name
 * resolved to; a proxy, then the direct fallback) in turn: its TCP connect
 * under its own connect timeout, then an https hop's TLS handshake and a
 * SOCKS proxy's reply under the read timeout. The read timeout is the
 * long body stall limit now, so each route's TLS handshake is given
 * [baseMs] (`main`'s read timeout: 10 s, 60 s for media) of its own
 * instead ([handshakeTimeoutMs], set on each route's socket by
 * [TorRouting.openFollowingRedirects]): a route whose handshake stalls
 * fails there and the connection moves on to the next, as on `main`
 * (PR #409 R6-M1). The whole stretch is watched too: at
 * [connectStretchMs] — `(connectMs + baseMs) × routes + graceMs`, `main`'s
 * per-route bound for every route [TorRouting.openFollowingRedirects]
 * reports, plus a [CONNECT_GRACE_MS] grace — the connection is
 * disconnected from another thread, which aborts a `connect()`
 * mid-handshake, and cut again every [RECUT_MS] until it returns, in case
 * a cut lands between steps of the connect and a later one blocks again.
 * That is the backstop for what no per-route socket timeout covers: a
 * SOCKS proxy's reply and an HTTP proxy's CONNECT reply, both read under
 * the long read timeout (PR #409 R3-F1). A hop on the system proxy has
 * each of its routes (each proxy, then the direct fallback) dialed as a
 * connect of its own, so a cut there ends that route only and the next is
 * still tried, as on `main` (`fallback`, PR #409 R1-M1); only a cut of the
 * hop's last route expires the attempt. Such a proxy route is timed in two
 * stretches (`tunnel`, PR #409 R4-M2): the TCP connect and the proxy's
 * reply get [tunnelStretchMs] (11 s, close to `main`'s 10 s read timeout
 * for that reply, rather than [connectStretchMs]'s 16 s), and once the
 * tunnel is up ([tunnelled]) its TLS handshake gets [baseMs] + the grace
 * of its own. The name is looked up before this
 * stretch starts ([TorRouting.openFollowingRedirects] does it ahead
 * of [connecting], so the connect finds it cached): lookup time comes on
 * top, as on `main`, where no limit covered it. (A cut that does land
 * during the connection's own lookup isn't lost — the connection is
 * marked cancelled and fails once the lookup returns.) No slot extends
 * this stretch.
 *
 * **Headers** ([connected] to [answered]): each hop gets the same
 * [baseMs] the read timeout always gave it, and name lookups between hops
 * stay outside it — a subresource with no slot fails where it always did,
 * not earlier because its redirect hops add up (PR #409 R2-M1). At
 * [baseMs] a hop with no headers yet takes a [patience] slot and goes on
 * to [timeoutMs], or — no slot free, or no longer limit — has its
 * connection disconnected, which fails the blocked `responseCode` at once.
 * The connection is up by then, so the cut always lands (a disconnect
 * before `connect()` is a no-op, PR #409 R2-F1).
 *
 * A hop started after the attempt already expired is refused outright.
 */
internal class HeaderDeadline(
    private val baseMs: Int,
    private val timeoutMs: Int = baseMs,
    private val patience: PatientWaits = PatientWaits.shared,
    private val connectMs: Int = GATEWAY_CONNECT_TIMEOUT_MS,
    private val graceMs: Int = CONNECT_GRACE_MS,
) : TorRouting.HopWatcher {
    /** Each route's own TLS handshake limit: `main`'s read timeout. */
    override val handshakeTimeoutMs: Int get() = baseMs

    private val lock = Any()
    private var current: HttpURLConnection? = null // the hop connecting or waiting on its headers
    private var hop = 0 // which hop that is
    private var patient = false
    private var cutting = false // a connect-stretch disconnect is still running
    private var connectingFallback: Boolean? = null // set while [current] is connecting: its `fallback`
    private var connectingAddresses = 1 // [current]'s `routes` while connecting: a tunnel's proxy addresses
    private var task: ScheduledFuture<*>? = null

    /** Did a hop's deadline pass before its headers arrived? */
    @Volatile
    var expired = false
        private set

    /** Has a hop gone on past [baseMs] on a [patience] slot? */
    @Volatile
    var extended = false
        private set

    override fun connecting(conn: HttpURLConnection, routes: Int, fallback: Boolean, tunnel: Boolean) {
        synchronized(lock) {
            if (expired) throw SocketTimeoutException("no headers in time")
            stop()
            current = conn
            val mine = ++hop
            connectingFallback = fallback
            connectingAddresses = routes
            val limit = if (tunnel) tunnelStretchMs(connectMs, baseMs, routes, graceMs) else connectStretchMs(connectMs, baseMs, routes, graceMs)
            task = watchdog.schedule({ cutConnect(mine, fallback) }, limit, TimeUnit.MILLISECONDS)
        }
    }

    // A proxy route's tunnel is up: its TLS handshake gets a clock of its
    // own, [baseMs] (its socket's own limit) plus the grace (PR #409 R4-M2).
    // A handshake that fails there sends okhttp to the proxy's next
    // address, whose connect, reply and handshake come under this clock
    // too, so each address the proxy has left adds its share (each new
    // tunnel re-arms it; PR #409 R5-M1).
    override fun tunnelled(conn: HttpURLConnection) {
        synchronized(lock) {
            val fallback = connectingFallback ?: return
            if (current !== conn || cutting) return
            task?.cancel(false)
            val mine = ++hop
            val limit = baseMs.toLong() + graceMs + (connectMs.toLong() + baseMs) * (connectingAddresses.coerceAtLeast(1) - 1)
            task = watchdog.schedule({ cutConnect(mine, fallback) }, limit, TimeUnit.MILLISECONDS)
        }
    }

    override fun connected(conn: HttpURLConnection) {
        synchronized(lock) {
            if (expired) throw SocketTimeoutException("no connection in time")
            stop()
            current = conn
            val mine = ++hop
            task = watchdog.schedule({ atBase(mine) }, baseMs.toLong(), TimeUnit.MILLISECONDS)
        }
    }

    override fun answered(conn: HttpURLConnection) = headersReceived()

    // The connect stretch ran out: cut, and keep cutting until [connected],
    // the next route's [connecting] or the end of the attempt moves [hop]
    // on. A connect with a [fallback] route after it ends alone: the
    // attempt goes on to that route (PR #409 R1-M1).
    private fun cutConnect(mine: Int, fallback: Boolean) {
        val doomed = synchronized(lock) {
            if (mine != hop) return
            val conn = current ?: return
            if (!fallback) expired = true
            task = watchdog.schedule({ cutConnect(mine, fallback) }, RECUT_MS, TimeUnit.MILLISECONDS)
            if (cutting) return // the last cut is still blocked; don't pile up threads
            cutting = true
            conn
        }
        disconnectAside(doomed) { synchronized(lock) { cutting = false } }
    }

    private fun atBase(mine: Int) {
        synchronized(lock) {
            if (mine != hop || current == null) return
            if (timeoutMs > baseMs && patience.tryTake()) {
                patient = true
                extended = true
                task = watchdog.schedule({ expire(mine) }, (timeoutMs - baseMs).toLong(), TimeUnit.MILLISECONDS)
                return
            }
        }
        expire(mine)
    }

    private fun expire(mine: Int) {
        val doomed = synchronized(lock) {
            if (mine != hop) return
            val conn = current ?: return
            expired = true
            current = null
            task = null
            release()
            conn
        }
        disconnectAside(doomed)
    }

    /**
     * The page gave up on the request ([AbandonSignal]): the attempt ends
     * now — the hop connecting or waiting on its headers is cut (off this
     * thread, as every cut is), and a hop not started yet is refused.
     */
    fun abandon() {
        val doomed = synchronized(lock) {
            expired = true
            val conn = current
            stop()
            conn
        }
        doomed?.let { disconnectAside(it) }
    }

    /** The hop's headers are in (or the attempt is over): stop its clock, give back any slot. */
    fun headersReceived() {
        synchronized(lock) { stop() }
    }

    // Under [lock].
    private fun stop() {
        task?.cancel(false)
        task = null
        current = null
        connectingFallback = null
        connectingAddresses = 1
        hop++
        release()
    }

    // Under [lock].
    private fun release() {
        if (patient) {
            patient = false
            patience.give()
        }
    }

    private companion object {
        const val RECUT_MS = 250L
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
        disconnectAside(conn)
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
 *
 * The body counts as ended — closed normally, its connection kept alive
 * for the next request — once a read returns -1, but also when there is
 * no body to read ([noBody]: a HEAD, 1xx, 204 or 304 answer, which
 * WebView may close without ever reading) or every one of a known
 * [length]'s bytes has been read, since a reader that got exactly the
 * `Content-Length` needn't read on to -1 (PR #409 R2-M3).
 */
internal class DisconnectOnCloseInputStream(
    body: InputStream,
    private val conn: HttpURLConnection,
    private val stall: BodyStallGuard? = null,
    length: Long = -1L,
    noBody: Boolean = false,
) : FilterInputStream(body) {
    private val ended = AtomicBoolean(noBody || length == 0L)
    private val closed = AtomicBoolean(false)
    private var remaining = length // only the reading thread touches it

    private inline fun <T> guarded(crossinline block: () -> T): T =
        if (stall == null) block() else stall.reading { block() }

    private fun consumed(n: Long) {
        if (n < 0) {
            ended.set(true)
        } else if (remaining > 0) {
            remaining -= n
            if (remaining <= 0L) ended.set(true)
        }
    }

    override fun read(): Int = guarded { super.read() }.also { consumed(if (it < 0) -1L else 1L) }

    override fun read(b: ByteArray, off: Int, len: Int): Int =
        guarded { super.read(b, off, len) }.also { consumed(it.toLong()) }

    override fun skip(n: Long): Long = guarded { super.skip(n) }.also { consumed(it) }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        stall?.close()
        if (ended.get()) {
            runCatching { super.close() }
        } else {
            // Off this thread, as the watchdog's cuts are (PR #409 R3-M1):
            // the closing thread is one of Chromium's pool workers, and a
            // disconnect can block (an https or SOCKS hop). Disconnect
            // before closing the stream, which would otherwise try to
            // drain the rest first.
            val body = `in`
            disconnector.execute {
                runCatching { conn.disconnect() }
                runCatching { body.close() }
            }
        }
    }
}
