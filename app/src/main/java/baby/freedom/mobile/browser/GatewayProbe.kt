package baby.freedom.mobile.browser

import android.util.Log
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import kotlin.coroutines.coroutineContext

/**
 * HEAD-polls a local gateway URL until the content is retrievable, the
 * gateway is detected as unreachable, or an overall budget elapses. Used
 * to gate WebView navigation while the ant node (and, eventually,
 * an embedded IPFS node) is still connecting to peers.
 *
 * Mirrors `src/main/swarm/swarm-probe.js` from the freedom-browser
 * desktop [PR #55](https://github.com/solardev-xyz/freedom-browser/pull/55)
 * — same delay schedule, same per-attempt timeout, same overall budget,
 * same outcome taxonomy. The desktop runs one probe in the main process
 * and cancels it by id; we lean on coroutine cancellation instead since
 * there's only one process to coordinate across.
 *
 * A 404 alone is ambiguous, but ant's body is not (freedom-browser#618,
 * #175): a path the site's manifest doesn't contain is
 * `{"message":"path address not found"}`, content the node couldn't
 * retrieve a plain `Not Found`, and a miss from a starved peer pool a
 * retryable 503. A quick 404 is re-read with GET (HEAD carries no body)
 * and a missing path ends the probe as [Outcome.PathNotFound] instead of
 * polling out the whole budget on a typo or a stale deep link. An answer
 * given instantly comes from a manifest the node already holds and is
 * final; a slower one came from a fresh lookup, which for a feed-backed
 * site may have landed on an older update than the latest, so it ends
 * the probe only when the next attempt says the same.
 */
class GatewayProbe {
    sealed interface Outcome {
        /** HEAD returned 200 — the content is retrievable right now. */
        data object Ok : Outcome

        /**
         * The gateway itself refused the connection (ECONNREFUSED / DNS /
         * TLS). The caller should show a "node is not running" page
         * rather than keep polling: if the socket's gone, more HEADs
         * won't help.
         */
        data class Unreachable(val cause: String) : Outcome

        /**
         * Overall timeout elapsed without a 200 response. [lastStatus] is
         * the node's last answer (404 or a [TRANSIENT_5XX] one; `null`
         * when the last attempt got no answer in time) and [peers] its
         * connected peer count then (`null` when it didn't say), so the
         * error page can tell a node short of peers from content the
         * network lacks.
         */
        data class NotFound(val lastStatus: Int?, val peers: Int?) : Outcome

        /** The site's manifest has no such path: a typo or a stale link. */
        data object PathNotFound : Outcome

        /** HEAD returned some status other than 200, 404 or a [TRANSIENT_5XX] one. */
        data class Other(val status: Int) : Outcome

        /** Coroutine was cancelled. */
        data object Aborted : Outcome
    }

    /**
     * Poll [headUrl] with HEAD requests until one of the [Outcome]s is
     * reached. Cancellation returns [Outcome.Aborted] rather than
     * propagating — callers can distinguish "user navigated away" from
     * "content unavailable" without a try/catch around every call site.
     *
     * [delaysMs] is a back-off schedule — the Nth attempt waits
     * `delaysMs[min(N, delaysMs.lastIndex)]` before firing. The first
     * entry is zero so the first HEAD happens immediately.
     */
    suspend fun probe(
        headUrl: String,
        delaysMs: LongArray = DEFAULT_DELAYS_MS,
        attemptTimeoutMs: Long = DEFAULT_ATTEMPT_TIMEOUT_MS,
        overallTimeoutMs: Long = DEFAULT_OVERALL_TIMEOUT_MS,
        unreachableGraceMs: Long = DEFAULT_UNREACHABLE_GRACE_MS,
        // Where to ask the node for its peer count once the budget runs
        // out (ant's `/peers`); `null` asks nothing.
        peersUrl: String? = null,
        quick404Ms: Long = QUICK_404_MS,
        warm404Ms: Long = WARM_404_MS,
        sideRequestTimeoutMs: Long = SIDE_REQUEST_TIMEOUT_MS,
    ): Outcome {
        val started = System.currentTimeMillis()
        var attempt = 0
        var lastStatus: Int? = null
        // The previous attempt was a cold "path address not found" still
        // awaiting its confirmation.
        var coldMissingPath = false
        while (true) {
            try {
                coroutineContext.ensureActive()
            } catch (_: CancellationException) {
                return Outcome.Aborted
            }

            val delayMs = delaysMs[minOf(attempt, delaysMs.lastIndex)]
            if (delayMs > 0) {
                try {
                    delay(delayMs)
                } catch (_: CancellationException) {
                    return Outcome.Aborted
                }
            }
            attempt++

            // Timed on the HEAD alone, before a GET below adds its own trip.
            val attemptStarted = System.nanoTime()
            val attemptResult = runAttempt(headUrl, attemptTimeoutMs)
            val attemptMs = (System.nanoTime() - attemptStarted) / 1_000_000
            when (attemptResult) {
                is AttemptResult.Ok -> return Outcome.Ok
                is AttemptResult.TransientHttp -> {
                    // 404 or a transient 5xx — keep polling until the
                    // overall budget runs out, unless the 404 says the
                    // manifest has no such path.
                    lastStatus = attemptResult.status
                    val missingPath = attemptResult.status == 404 &&
                        attemptMs < quick404Ms &&
                        isMissingPath(headUrl, sideRequestTimeoutMs)
                    try {
                        coroutineContext.ensureActive()
                    } catch (_: CancellationException) {
                        return Outcome.Aborted
                    }
                    if (missingPath && (attemptMs < warm404Ms || coldMissingPath)) {
                        Log.i(TAG, "path not in manifest: $headUrl")
                        return Outcome.PathNotFound
                    }
                    coldMissingPath = missingPath
                }
                is AttemptResult.TransientTimeout -> {
                    Log.i(TAG, "attempt timed out (${attemptTimeoutMs}ms), retrying")
                    lastStatus = null
                    coldMissingPath = false
                }
                is AttemptResult.Unreachable -> {
                    // The ant gateway socket can take a moment to
                    // bind after the node flips to `Running`. Treat
                    // ECONNREFUSED as transient for a short grace
                    // window so that race doesn't turn into an error
                    // page on first submit. Once the grace window
                    // elapses without a bind, assume the gateway is
                    // genuinely unreachable and stop polling.
                    val elapsed = System.currentTimeMillis() - started
                    if (elapsed < unreachableGraceMs) {
                        Log.i(
                            TAG,
                            "gateway unreachable (${attemptResult.cause}), " +
                                "retrying within ${unreachableGraceMs}ms grace",
                        )
                    } else {
                        return Outcome.Unreachable(attemptResult.cause)
                    }
                }
                is AttemptResult.Other -> return Outcome.Other(attemptResult.status)
                is AttemptResult.Aborted -> return Outcome.Aborted
            }

            if (System.currentTimeMillis() - started >= overallTimeoutMs) {
                val peers = peersUrl?.let { countPeers(it, sideRequestTimeoutMs) }
                try {
                    coroutineContext.ensureActive()
                } catch (_: CancellationException) {
                    return Outcome.Aborted
                }
                Log.i(TAG, "gave up: last answer ${lastStatus ?: "none in time"}, ${peers ?: "?"} peers")
                return Outcome.NotFound(lastStatus, peers)
            }
        }
    }

    /** Whether a GET of [url] answers ant's "no such path in the manifest" 404. */
    private suspend fun isMissingPath(url: String, timeoutMs: Long): Boolean {
        val body = sideRequest(url, MISSING_PATH_BODY_BYTES, timeoutMs) ?: return false
        return missingPathBody(body)
    }

    /** The node's connected peer count from its `/peers`, or `null`. */
    private suspend fun countPeers(url: String, timeoutMs: Long): Int? {
        val body = sideRequest(url, PEERS_BODY_BYTES, timeoutMs) ?: return null
        return peerCount(body)
    }

    /**
     * A short GET beside the probe's own HEAD (a 404's body, the peer
     * count). The body, up to [maxBytes] (a longer one is cut, which
     * [missingPathBody] and [peerCount] then fail to parse), or `null` on
     * any failure.
     *
     * [timeoutMs] ([SIDE_REQUEST_TIMEOUT_MS]) bounds the whole request,
     * redirects and body included, not each step of it: a blocking
     * `HttpURLConnection` call can't be interrupted by a coroutine
     * timeout, and its own connect/read timeouts apply per hop and per
     * `read()`, so a node trickling its body (or redirecting) could hold
     * it many times over. The work runs on a thread of its own; at the
     * deadline (or when the probe is cancelled) this returns `null` at
     * once and [SideRequestCut] disconnects the connection from another
     * thread, so the worker fails too rather than read on unwatched.
     */
    private suspend fun sideRequest(url: String, maxBytes: Int, timeoutMs: Long): String? {
        val cut = SideRequestCut()
        val work = sideScope.async {
            try {
                val conn = TorRouting.openFollowingRedirects(URL(url), hops = cut) {
                    requestMethod = "GET"
                    connectTimeout = timeoutMs.toInt()
                    readTimeout = timeoutMs.toInt()
                }
                cut.reading(conn)
                try {
                    val code = conn.responseCode
                    val stream = if (code >= 400) conn.errorStream else conn.inputStream
                    stream?.use { readCapped(it, maxBytes) }
                } finally {
                    runCatching { conn.disconnect() }
                }
            } catch (_: Exception) {
                null
            }
        }
        return try {
            withTimeoutOrNull(timeoutMs) { work.await() }
        } catch (_: CancellationException) {
            // The probe itself was cancelled: no answer, and the caller
            // checks its own cancellation right after.
            null
        } finally {
            if (!work.isCompleted) {
                Log.i(TAG, "side request cut at ${timeoutMs}ms: $url")
                cut.abort()
                work.cancel()
            }
        }
    }

    /**
     * Ends a [sideRequest] from outside its blocking worker: [abort]
     * disconnects whichever connection the worker is on, on a thread of
     * its own (a `disconnect()` can block as long as the read it ends),
     * and keeps doing so until the worker moves on, since a disconnect
     * landing before a hop's `connect()` is a no-op. A hop started after
     * the abort is refused outright.
     */
    private class SideRequestCut : TorRouting.HopWatcher {
        @Volatile private var current: HttpURLConnection? = null
        @Volatile private var aborted = false

        override fun connecting(conn: HttpURLConnection, routes: Int, fallback: Boolean, tunnel: Boolean) {
            if (aborted) throw SocketTimeoutException("side request cut")
            current = conn
        }

        override fun connected(conn: HttpURLConnection) {
            current = conn
            if (aborted) disconnectAside(conn)
        }

        override fun answered(conn: HttpURLConnection) {
            current = conn
            if (aborted) disconnectAside(conn)
        }

        /** The worker reads the body of [conn]. */
        fun reading(conn: HttpURLConnection) {
            current = conn
            if (aborted) disconnectAside(conn)
        }

        fun abort() {
            aborted = true
            current?.let { disconnectAside(it) }
            // A disconnect before `connect()` is a no-op: cut again a
            // few times while the worker may still be getting there.
            sideScope.launch {
                repeat(RECUT_TIMES) {
                    delay(RECUT_MS)
                    current?.let { disconnectAside(it) }
                }
            }
        }

        private fun disconnectAside(conn: HttpURLConnection) {
            sideScope.launch { runCatching { conn.disconnect() } }
        }

        private companion object {
            const val RECUT_MS = 250L
            const val RECUT_TIMES = 20
        }
    }

    private fun readCapped(input: InputStream, maxBytes: Int): String {
        val buf = ByteArray(maxBytes)
        var n = 0
        while (n < maxBytes) {
            val r = input.read(buf, n, maxBytes - n)
            if (r < 0) break
            n += r
        }
        return String(buf, 0, n, Charsets.UTF_8)
    }

    private sealed interface AttemptResult {
        data object Ok : AttemptResult
        data class TransientHttp(val status: Int) : AttemptResult
        data object TransientTimeout : AttemptResult
        data class Unreachable(val cause: String) : AttemptResult
        data class Other(val status: Int) : AttemptResult
        data object Aborted : AttemptResult
    }

    private suspend fun runAttempt(headUrl: String, attemptTimeoutMs: Long): AttemptResult {
        return try {
            withContext(Dispatchers.IO) {
                withTimeout(attemptTimeoutMs) {
                    // Redirects followed hop by hop through TorRouting.
                    val conn = TorRouting.openFollowingRedirects(URL(headUrl)) {
                        requestMethod = "HEAD"
                        connectTimeout = attemptTimeoutMs.toInt().coerceAtLeast(1_000)
                        readTimeout = attemptTimeoutMs.toInt().coerceAtLeast(1_000)
                    }
                    try {
                        when (val status = conn.responseCode) {
                            200 -> AttemptResult.Ok
                            404, in TRANSIENT_5XX -> AttemptResult.TransientHttp(status)
                            else -> AttemptResult.Other(status)
                        }
                    } finally {
                        runCatching { conn.disconnect() }
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            AttemptResult.TransientTimeout
        } catch (_: CancellationException) {
            AttemptResult.Aborted
        } catch (e: ConnectException) {
            AttemptResult.Unreachable(e.message ?: "connection refused")
        } catch (e: UnknownHostException) {
            AttemptResult.Unreachable(e.message ?: "unknown host")
        } catch (e: SocketTimeoutException) {
            // Socket-level timeout (as opposed to the withTimeout wrap)
            // still means "this attempt didn't finish"; keep polling
            // unless we're out of overall budget.
            Log.i(TAG, "socket timed out, retrying: ${e.message}")
            AttemptResult.TransientTimeout
        } catch (e: IOException) {
            AttemptResult.Unreachable(e.message ?: e.javaClass.simpleName)
        }
    }

    companion object {
        private const val TAG = "GatewayProbe"

        /**
         * A 404 slower than this came from a retrieval that gave up, so
         * re-reading it would only repeat that wait. Desktop's
         * `QUICK_404_MS`, measured against Ant v0.5.64 on mainnet in
         * October 2026 (freedom-browser PR #623): a site the network
         * lacks took 10.8 s to answer `Not Found`, a missing path in a
         * reachable site about 2.3-2.5 s cold and under 1 ms once cached.
         */
        const val QUICK_404_MS: Long = 5_000

        /**
         * A `path address not found` faster than this came from a
         * manifest the node already held and is final on its own; a
         * slower one (a cold lookup) needs the next attempt to agree.
         * Desktop's `WARM_404_MS`, same measurements.
         */
        const val WARM_404_MS: Long = 1_000

        /**
         * Cap on each follow-up request (a 404's body, the peer count),
         * from its start to its last byte, redirects included ([sideRequest]).
         */
        const val SIDE_REQUEST_TIMEOUT_MS: Long = 5_000

        /** Where [sideRequest]'s blocking work and its cuts run, apart from the probe. */
        private val sideScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** The message Bee and ant give a manifest lookup that misses. */
        private const val MISSING_PATH_MESSAGE = "path address not found"

        private const val MISSING_PATH_BODY_BYTES = 1_024
        private const val PEERS_BODY_BYTES = 512 * 1_024

        // A double-dot path segment in any of the forms a URL parser
        // normalizes: `..`, `.%2e`, `%2e.`, `%2e%2e` (any case).
        private val DOUBLE_DOT_SEGMENT = Regex("^(\\.|%2e){2}$", RegexOption.IGNORE_CASE)

        /**
         * What to HEAD for the gateway URL [resolved]: for Swarm the
         * very page the navigation will load (`<base>/bzz/<hash><path>`,
         * query and fragment dropped — the manifest lookup ignores them),
         * so a path the manifest lacks is found out here rather than
         * after the probe let the root through (freedom-browser#172,
         * #618). A path with a double-dot segment, which could aim the
         * HEAD outside `/bzz/`, falls back to the site root, as does
         * anything [GatewayUrls] doesn't recognize. IPFS stays on its
         * root.
         */
        internal fun probeTarget(resolved: String): String {
            val base = GatewayUrls.extractBase(resolved) ?: return resolved
            if (base.protocol != "bzz") return base.prefix
            val path = resolved.substring(base.prefix.length).substringBefore('?').substringBefore('#')
            if (path.isNotEmpty() && !path.startsWith('/')) return base.prefix
            if (path.split('/', '\\').any { DOUBLE_DOT_SEGMENT.matches(it) }) return base.prefix
            return base.prefix + path
        }

        /** Whether [body] is ant's (and Bee's) JSON for a manifest path miss. */
        internal fun missingPathBody(body: String): Boolean = try {
            JSONObject(body).optString("message").lowercase() == MISSING_PATH_MESSAGE
        } catch (_: Exception) {
            false
        } catch (_: StackOverflowError) {
            false
        }

        /** The length of `/peers`' `peers` array, or `null` if [body] has none. */
        internal fun peerCount(body: String): Int? = try {
            JSONObject(body).optJSONArray("peers")?.length()
        } catch (_: Exception) {
            null
        } catch (_: StackOverflowError) {
            null
        }

        /**
         * 5xx statuses that mean "not yet", polled through like a 404.
         * Ant v0.5.56 (freedom-hq/ant#124) answers 503 when its peers
         * can't serve a chunk yet (a cold node right after start, or a
         * miss from a starved peer pool); earlier releases answered 404,
         * 500 or 502 there. Same set as `SUBRESOURCE_RETRY_STATUSES` in
         * GatewayFetchPolicy.kt and `RETRYABLE_STATUSES` in the desktop's
         * `bzz-protocol.js`, and as `swarm-probe.js` since
         * freedom-browser#489.
         */
        internal val TRANSIENT_5XX: Set<Int> = setOf(500, 502, 503, 504)

        /**
         * Default back-off schedule between HEAD attempts. First entry is
         * zero so the first HEAD fires immediately; subsequent entries
         * stretch out to give the node time to gather peers. Matches
         * `DEFAULT_DELAYS_MS` in `swarm-probe.js`.
         */
        val DEFAULT_DELAYS_MS: LongArray = longArrayOf(0, 500, 1000, 2000, 3000)

        /**
         * Per-attempt budget. Freshly-started Swarm nodes routinely
         * take 2–5 seconds to answer a feed-based `/bzz/<hash>` HEAD
         * once they have peers; a too-tight cap aborts every request
         * just as the node is about to respond. Matches the desktop's
         * 30s cap.
         */
        const val DEFAULT_ATTEMPT_TIMEOUT_MS: Long = 30_000

        /**
         * Overall probe budget. Cold nodes on mobile networks can take
         * well over a minute to resolve feed-based content, so we stay
         * generous and let the user cancel by navigating elsewhere.
         */
        const val DEFAULT_OVERALL_TIMEOUT_MS: Long = 5 * 60_000

        /**
         * How long the probe keeps retrying after ECONNREFUSED before
         * giving up with [Outcome.Unreachable]. Covers the window
         * between the embedded node reporting [baby.freedom.swarm.NodeStatus.Running]
         * and the HTTP gateway actually binding to port 1633.
         */
        const val DEFAULT_UNREACHABLE_GRACE_MS: Long = 10_000
    }
}
