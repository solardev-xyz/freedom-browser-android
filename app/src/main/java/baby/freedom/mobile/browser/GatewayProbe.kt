package baby.freedom.mobile.browser

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import kotlin.coroutines.coroutineContext

/**
 * HEAD-polls a local gateway URL until the content is retrievable, the
 * gateway is detected as unreachable, or an overall budget elapses. Used
 * to gate WebView navigation while the bee-lite node (and, eventually,
 * an embedded IPFS node) is still connecting to peers.
 *
 * Mirrors `src/main/swarm/swarm-probe.js` from the freedom-browser
 * desktop [PR #55](https://github.com/solardev-xyz/freedom-browser/pull/55)
 * — same delay schedule, same per-attempt timeout, same overall budget,
 * same outcome taxonomy. The desktop runs one probe in the main process
 * and cancels it by id; we lean on coroutine cancellation instead since
 * there's only one process to coordinate across.
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

        /** Overall timeout elapsed without a 200 response. */
        data object NotFound : Outcome

        /** HEAD returned some status other than 200/404/500. */
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
    ): Outcome {
        val started = System.currentTimeMillis()
        var attempt = 0
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

            val attemptResult = runAttempt(headUrl, attemptTimeoutMs)
            when (attemptResult) {
                is AttemptResult.Ok -> return Outcome.Ok
                is AttemptResult.TransientHttp -> {
                    // 404/500 — keep polling until the overall budget runs out.
                }
                is AttemptResult.TransientTimeout -> {
                    Log.i(TAG, "attempt timed out (${attemptTimeoutMs}ms), retrying")
                }
                is AttemptResult.Unreachable -> {
                    // The bee-lite gateway socket can take a moment to
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
                return Outcome.NotFound
            }
        }
    }

    private sealed interface AttemptResult {
        data object Ok : AttemptResult
        data object TransientHttp : AttemptResult
        data object TransientTimeout : AttemptResult
        data class Unreachable(val cause: String) : AttemptResult
        data class Other(val status: Int) : AttemptResult
        data object Aborted : AttemptResult
    }

    private suspend fun runAttempt(headUrl: String, attemptTimeoutMs: Long): AttemptResult {
        return try {
            withContext(Dispatchers.IO) {
                withTimeout(attemptTimeoutMs) {
                    val conn = (URL(headUrl).openConnection() as HttpURLConnection).apply {
                        requestMethod = "HEAD"
                        connectTimeout = attemptTimeoutMs.toInt().coerceAtLeast(1_000)
                        readTimeout = attemptTimeoutMs.toInt().coerceAtLeast(1_000)
                        instanceFollowRedirects = true
                    }
                    try {
                        conn.connect()
                        when (val status = conn.responseCode) {
                            200 -> AttemptResult.Ok
                            404, 500 -> AttemptResult.TransientHttp
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
         * Default back-off schedule between HEAD attempts. First entry is
         * zero so the first HEAD fires immediately; subsequent entries
         * stretch out to give the node time to gather peers. Matches
         * `DEFAULT_DELAYS_MS` in `swarm-probe.js`.
         */
        val DEFAULT_DELAYS_MS: LongArray = longArrayOf(0, 500, 1000, 2000, 3000)

        /**
         * Per-attempt budget. Freshly-started bee-lite nodes routinely
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
