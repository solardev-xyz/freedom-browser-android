package baby.freedom.mobile.node

import android.util.Log
import baby.freedom.mobile.chains.BuiltInChains
import baby.freedom.mobile.chains.rpc.ChainDataRouter
import baby.freedom.mobile.chains.rpc.ChainDataRouter.ErrorRank
import baby.freedom.mobile.chains.rpc.ChainFailure
import baby.freedom.mobile.chains.rpc.ChainRpcException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * The Swarm node's Gnosis reads, answered by the chain-data router (#273):
 * the Android side of desktop's `ant-chain-bridge.js`. The shim's chain
 * transport (`ant_jni.c`, via [baby.freedom.swarm.AntChainTransport])
 * hands every request ant makes that isn't a broadcast to [serve], which
 * reads it through [router] on Gnosis — the same source order (by
 * default Myotis, Colibri, an RPC quorum, a single RPC) and the same trust
 * labels the wallet's reads get, logged per answer by the router. Before
 * this, ant read the chain from one RPC on its own word.
 *
 * Broadcasts never get here: the shim still gates them through
 * `SpendGuard` and sends an admitted one on the node's configured RPC,
 * exactly as before.
 *
 * What ant gets back is always a JSON-RPC answer, never "can't serve":
 * the shim returns it as is, so ant never falls back to its configured
 * RPC for a read. When the router can't answer, ant gets an error — never
 * an empty result that could read as "no batch" or "no chequebook" — and
 * its code is never `-32000`, which ant would take as can't-serve and
 * replay on that one RPC. `eth_getLogs` errors are ranked the way
 * desktop's bridge ranks them ([rankLogScanError]), so ant's log scan
 * halves its window on a query-size refusal or a timeout, and gives up
 * (to retry later) on a throttle or an endpoint that's behind.
 */
internal class AntChainBridge(
    private val router: ChainDataRouter,
    private val deadlineMs: Long = DEADLINE_MS,
    private val chainId: Long = BuiltInChains.GNOSIS.id,
) {
    /** Where requests run; [close] cancels it, so a request ant is blocked on returns at once. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val active = Semaphore(MAX_ACTIVE)

    /**
     * Answer one JSON-RPC request body from ant. Blocks the calling
     * (ant's) thread for at most [deadlineMs]; never throws.
     */
    fun serve(request: String): String {
        val obj = try {
            JSONTokener(request).nextValue() as? JSONObject
        } catch (_: Exception) {
            null
        } ?: return reply(null, error(-32700, "Invalid JSON"))
        val id = obj.opt("id").takeIf { it is Number || (it is String && it.length <= 128) }
            ?: return reply(null, error(-32600, "Single JSON-RPC request with id and params required"))
        val method = obj.optString("method")
        val params = obj.opt("params") as? JSONArray
            ?: return reply(id, error(-32600, "Single JSON-RPC request with id and params required"))
        if (method !in READ_METHODS) return reply(id, error(-32601, "Method not available to the Swarm node"))
        val job = scope.async {
            withTimeoutOrNull(deadlineMs) { active.withPermit { answer(method, params) } }
                ?: error(GENERIC_ERROR, "Chain request failed: query timeout")
        }
        val body = try {
            runBlocking { job.await() }
        } catch (e: CancellationException) {
            error(GENERIC_ERROR, "Chain request failed: the Swarm node is stopping")
        } catch (e: Exception) {
            error(GENERIC_ERROR, "Chain request failed")
        }
        return reply(id, body)
    }

    /** Stop answering: requests in flight end at once with an error. */
    fun close() {
        scope.cancel()
    }

    /** `{"result": …}` or `{"error": …}` for one request. */
    private suspend fun answer(method: String, params: JSONArray): JSONObject = try {
        val r = router.request(
            chainId,
            method,
            params,
            rankError = if (method == "eth_getLogs") ::rankLogScanError else null,
        )
        JSONObject().put("result", r.result ?: JSONObject.NULL)
    } catch (e: CancellationException) {
        throw e
    } catch (e: ChainRpcException) {
        errorFor(method, e).also { Log.w(TAG, "[ant-chain] $method failed (${it.optJSONObject("error")?.optInt("code")})") }
    } catch (e: Exception) {
        Log.w(TAG, "[ant-chain] $method failed (${e.javaClass.simpleName})")
        error(GENERIC_ERROR, "Chain request failed")
    }

    companion object {
        private const val TAG = "AntChain"

        /**
         * The longest ant waits on one read — desktop's two minutes cut to
         * one: the router's own walk is bounded well inside it (a few
         * endpoint timeouts), and a node shutting down waits on reads in
         * flight ([close] ends them sooner).
         */
        const val DEADLINE_MS = 60_000L

        /** Reads running at once, desktop's cap; more wait their turn inside [DEADLINE_MS]. */
        const val MAX_ACTIVE = 8

        /** The reads ant's chain module makes (ant.h, `ant_set_chain_transport`); its broadcast never reaches here. */
        val READ_METHODS = setOf(
            "eth_call", "eth_getBalance", "eth_getLogs", "eth_getTransactionReceipt",
            "eth_getTransactionCount", "eth_blockNumber", "eth_getCode",
        )

        /**
         * ant's "I can't serve that, try the configured RPC" code (ant.h):
         * a real `-32000` from a node (geth and Nethermind's catch-all) is
         * passed on as [GENERIC_ERROR] instead.
         */
        const val ANT_CANT_SERVE = -32000

        /** The code for a failure no node gave a code for, desktop's. */
        const val GENERIC_ERROR = -32002

        private const val MAX_ERROR_MESSAGE = 500

        /**
         * ant v0.5.48's `is_range_limit_error` (crates/ant-chain/src/discover.rs):
         * its `eth_getLogs` scan halves the window only when the error
         * contains one of these, and abandons the scan on anything else.
         */
        val ANT_LOG_SCAN_SHRINK_NEEDLES = listOf(
            "block range", "range", "more than", "exceed", "too large", "10000", "limit",
            "logs matched", "response size", "up to a", "query timeout", "too many results",
        )

        fun antShrinksLogScanOn(message: String): Boolean {
            val m = message.lowercase()
            return ANT_LOG_SCAN_SHRINK_NEEDLES.any { it in m }
        }

        private val TIMEOUT_TEXT = Regex("time(?:d)?[\\s-]?out", RegexOption.IGNORE_CASE)

        /**
         * Wordings that name the query's size — its block range, result
         * count or response size — and so hold for every endpoint: desktop's
         * `RANGE_SIZE_TEXT` and `REQUEST_LIMIT_TEXT`. A bare "range" isn't
         * enough: "block range extends beyond current head block" (reth) is
         * about how far the endpoint has synced.
         */
        private val RANGE_SIZE_TEXT = Regex(
            "(?:max(?:imum)?|allowed|permitted) (?:block )?range|exceeds? (?:the )?(?:block )?range|" +
                "(?:block )?range (?:is |of )?(?:too\\b|larger|greater|wider|bigger|longer|more than|limit|exceed|limited|capped|size|span)|" +
                "(?:limited to|up to) (?:an? )?[\\w,.]+ (?:blocks? )?range",
            RegexOption.IGNORE_CASE,
        )
        private val REQUEST_LIMIT_TEXT = Regex(
            "too many (?:results|logs|blocks)|response size|logs? matched|(?:returned )?more than [\\d,]+ (?:results|logs|blocks)|" +
                "(?:max(?:imum)?|too many) (?:number of )?(?:results|logs|blocks)|result(?:s| set)? (?:size |limit|too large|exceed)",
            RegexOption.IGNORE_CASE,
        )

        /** A throttle or quota — about the endpoint, even when it names a range. Desktop's `ENDPOINT_LIMIT_TEXT`. */
        private val ENDPOINT_LIMIT_TEXT = Regex(
            "\\brate\\b|rate[\\s-]?limit|too many requests|\\b429\\b|quota|credits?\\b|daily request|capacity|requests? (?:per|limit)|throttl",
            RegexOption.IGNORE_CASE,
        )

        /** An endpoint behind the chain head: a synced one may answer. Desktop's `ENDPOINT_STATE_TEXT`. */
        private val ENDPOINT_STATE_TEXT = Regex(
            "beyond (?:the )?(?:current |latest )?(?:executed )?(?:head|latest|chain)|(?:still |is )syncing|not (?:yet )?synced|head block|latest executed block",
            RegexOption.IGNORE_CASE,
        )

        /**
         * How useful a failed `eth_getLogs` attempt is to ant — desktop's
         * `rankLogScanError`, for the router's keep-the-most-useful-error
         * rule ([ChainDataRouter.request]'s `rankError`):
         *
         * - [ErrorRank.REQUEST]: a node's error naming the query's size
         *   (`query exceeds max block range 50000`, `query returned more
         *   than 10000 results`). Ends the walk; ant halves its window.
         * - [ErrorRank.TIMEOUT]: an attempt that ran out of time. ant halves
         *   on it too, but another endpoint may still answer.
         * - [ErrorRank.HINT]: any other node error matching ant's needles
         *   that names neither a throttle nor a lagging endpoint (EIP-1474's
         *   `limit exceeded`) — maybe a range cap worded otherwise, maybe a
         *   throttle, so later endpoints are still asked.
         * - [ErrorRank.ENDPOINT]: everything else — method not found, a
         *   throttle, an endpoint behind the head, a transport failure.
         */
        fun rankLogScanError(f: ChainFailure): Int {
            if (f.timeout || TIMEOUT_TEXT.containsMatchIn(f.message)) return ErrorRank.TIMEOUT
            if (f.code == null || ENDPOINT_LIMIT_TEXT.containsMatchIn(f.message) || ENDPOINT_STATE_TEXT.containsMatchIn(f.message)) {
                return ErrorRank.ENDPOINT
            }
            if (!antShrinksLogScanOn(f.message)) return ErrorRank.ENDPOINT
            return if (RANGE_SIZE_TEXT.containsMatchIn(f.message) || REQUEST_LIMIT_TEXT.containsMatchIn(f.message)) {
                ErrorRank.REQUEST
            } else {
                ErrorRank.HINT
            }
        }

        private val URL_TEXT = Regex("\\b[a-z][a-z0-9+.-]*://[^\\s;,)]+", RegexOption.IGNORE_CASE)
        private val HEX_DATA = Regex("^0x[0-9a-fA-F]*$")

        /** A node's wording with any URL (which can carry an RPC key) and control characters taken out, capped. */
        fun sanitize(message: String): String = message
            .replace(URL_TEXT, "[url]")
            .replace(Regex("\\p{Cntrl}+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(MAX_ERROR_MESSAGE)

        /** The JSON-RPC error ant gets for a read the router couldn't answer. */
        internal fun errorFor(method: String, e: ChainRpcException): JSONObject {
            val logScan = method == "eth_getLogs"
            return when (e) {
                // A node's answer that is itself an error (a revert, bad
                // params): passed on with its wording, which ant's callers
                // read (a revert reason), and its data.
                is ChainRpcException.Rpc -> error(e.code, sanitize(e.rpcMessage), e.data)
                is ChainRpcException.AllSourcesFailed -> {
                    val kept = e.kept
                    when {
                        kept != null -> keptError(logScan, kept)
                        // Nothing ant can act on: its scan gives up (to be
                        // retried later) rather than halving on a throttle.
                        logScan -> error(GENERIC_ERROR, "Chain request failed: every chain source failed (endpoint unavailable)")
                        else -> error(GENERIC_ERROR, "Chain request failed: ${sanitize(e.message.orEmpty())}")
                    }
                }
                else -> error(GENERIC_ERROR, "Chain request failed: ${sanitize(e.message.orEmpty())}")
            }
        }

        /** The failure a ranked walk kept ([ChainRpcException.AllSourcesFailed.kept]), in words ant reacts to rightly. */
        private fun keptError(logScan: Boolean, f: ChainFailure): JSONObject {
            var detail = sanitize(f.message)
            val rank = rankLogScanError(f)
            if (logScan && rank == ErrorRank.TIMEOUT && !antShrinksLogScanOn(detail)) {
                // A timeout worded without "query timeout" still has to
                // make ant halve its window rather than give up.
                detail = "query timeout ($detail)"
            } else if (logScan && rank == ErrorRank.ENDPOINT && antShrinksLogScanOn(detail)) {
                // A throttle must not read as a range limit.
                detail = "endpoint unavailable"
            }
            return error(f.code ?: GENERIC_ERROR, "Chain request failed: $detail", f.data)
        }

        /** An `error` member; `-32000` never goes out as such (see [ANT_CANT_SERVE]). */
        internal fun error(code: Int, message: String, data: String? = null): JSONObject {
            val err = JSONObject()
                .put("code", if (code == ANT_CANT_SERVE) GENERIC_ERROR else code)
                .put("message", message)
            data?.takeIf { HEX_DATA.matches(it) }?.let { err.put("data", it) }
            return JSONObject().put("error", err)
        }

        private fun reply(id: Any?, body: JSONObject): String {
            val out = JSONObject().put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL)
            body.opt("result")?.let { out.put("result", it) }
            body.optJSONObject("error")?.let { out.put("error", it) }
            if (!out.has("result") && !out.has("error")) out.put("error", error(GENERIC_ERROR, "Chain request failed").get("error"))
            return out.toString()
        }
    }
}
