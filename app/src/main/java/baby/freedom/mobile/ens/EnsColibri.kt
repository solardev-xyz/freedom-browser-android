package baby.freedom.mobile.ens

import android.util.Log
import baby.freedom.swarm.ColibriNative
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * One `eth_call` on Ethereum mainnet, proven (#100): corpus.core's Colibri
 * stateless verifier ([ColibriNative]) checks the answer against
 * Ethereum's sync committee on this device, so it has the trust of the
 * chain's own consensus rather than of RPC servers agreeing — the tier
 * above [EnsQuorum]. The Android counterpart of desktop's
 * `colibri-resolver.js` and iOS's `ColibriENSClient`, with their
 * settings pinned the same way:
 *
 * - a remote prover ([PROVERS]) builds the proof; nothing it says is
 *   taken on trust — a wrong proof fails verification;
 * - *privacy mode basic* (PAP, [ColibriNative.VERIFY_FLAG_PAP]): the
 *   call's storage reads go to the RPC endpoints name resolution already
 *   uses (`ethRpcs`) and are checked against the proven state root, so
 *   the prover learns which storage the call touches but not the call;
 * - ZK sync-committee proofs, and a proof for `latest` older than
 *   [MAX_LATEST_AGE_SECONDS] is refused (a stale-but-valid proof would
 *   otherwise pass).
 *
 * The C core does no I/O: it lists the HTTP requests it needs, typed
 * (`prover`, `eth_rpc`, `checkpointz`, `beacon_api`), and this class
 * runs them ([serversFor]) and hands back the bytes, until the call is
 * answered. The verifier's sync-committee state persists in the app's
 * files ([ColibriNative.init]), so only the first call after install
 * (or after a library upgrade) bootstraps it.
 *
 * Cancellation-honest and bounded by the caller: cancelling [ethCall]
 * stops waiting at once, aborts its HTTP requests from another thread
 * (a read stalled on a trickling server can't hold it), and frees the
 * native context; see [fetch].
 */
internal class EnsColibri(
    private val engine: Engine,
    private val http: Http = Http.Default,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** What the verifier proved one call returned. */
    sealed class Outcome {
        /** The call's return data, `0x…`. */
        data class Returned(val data: String) : Outcome()

        /** The call reverted — itself proven — with this revert data (`0x` when none). */
        data class Reverted(val data: String) : Outcome()
    }

    /**
     * The verifier couldn't answer: a prover or network failure, or a
     * proof that didn't check out. [unreachable] when a request the core
     * asked for couldn't be served by any of its servers during the call
     * — the provers or RPCs not reachable, a problem every other call
     * would hit too — rather than a failure of this one call's own proof.
     */
    class Failure(message: String, val unreachable: Boolean = false) : Exception(message)

    /**
     * A proven answer and the provers whose proofs it came from — the
     * `prover` requests' hosts, for the trust shield ([EnsTrust.agreed]).
     */
    data class Proven(val outcome: Outcome, val provers: List<String>)

    /** Whether the verifier can run in this build ([ColibriNative.available]). */
    val available: Boolean get() = engine.available

    /**
     * Prove `eth_call({to, data}, "latest")`, with the call's storage
     * reads sent to [ethRpcs] (in order). Throws [Failure] when no proven
     * answer can be had, `CancellationException` when cancelled.
     */
    suspend fun ethCall(to: String, data: ByteArray, ethRpcs: List<String>): Proven = withContext(Dispatchers.IO) {
        if (!engine.available) throw Failure("Colibri isn't in this build")
        val params = JSONArray()
            .put(JSONObject().put("to", to).put("data", "0x" + data.toHex()))
            .put("latest")
            .toString()
        val ctx = engine.create(
            "eth_call",
            params,
            MAINNET,
            ColibriNative.PROVER_FLAG_ZK_PROOF,
            ColibriNative.VERIFY_FLAG_PAP,
            ColibriNative.PROVER_MODE_REMOTE,
        )
        if (ctx == 0L) throw Failure("the verifier refused the call")
        val provers = LinkedHashSet<String>()
        // Whether a request went unserved: then the core's own error (or
        // its running out of rounds) is the network's, not this proof's.
        var unserved = false
        try {
            engine.setMinLatestBlockTs(ctx, (clock() / 1000 - MAX_LATEST_AGE_SECONDS).coerceAtLeast(0))
            repeat(MAX_ROUNDS) {
                ensureActive()
                val status = engine.execute(ctx)?.let { runCatching { JSONObject(it) }.getOrNull() }
                    ?: throw Failure("the verifier returned no status")
                when (status.optString("status")) {
                    "success" -> return@withContext Proven(Outcome.Returned(resultHex(status)), provers.toList())
                    "revert" -> return@withContext Proven(
                        Outcome.Reverted(status.optString("data", "0x").ifEmpty { "0x" }),
                        provers.toList(),
                    )
                    "error" -> throw Failure(status.optString("error", "verification failed").take(300), unreachable = unserved)
                    "pending" -> {
                        val requests = status.optJSONArray("requests") ?: JSONArray()
                        val answers = coroutineScope {
                            (0 until requests.length()).map { i ->
                                val request = requests.getJSONObject(i)
                                async { request to serve(request, ethRpcs) }
                            }.awaitAll()
                        }
                        // Handed to the core only once all are in, and
                        // never after cancellation: a request handle
                        // dies with [ctx].
                        ensureActive()
                        for ((request, answer) in answers) {
                            // Skipping a request would leave it unanswered, and
                            // the core would just list it again next round.
                            val req = parsePtr(request.optString("req_ptr"))
                                ?: throw Failure("the verifier listed a request with no usable req_ptr")
                            when (answer) {
                                is Served.Ok -> {
                                    engine.setResponse(req, answer.body, answer.index)
                                    if (request.optString("type") == "prover") hostOf(answer.url)?.let(provers::add)
                                }
                                is Served.Failed -> {
                                    unserved = true
                                    engine.setError(req, answer.error, 0)
                                }
                            }
                        }
                    }
                    else -> throw Failure("unknown verifier status ${status.optString("status")}")
                }
            }
            throw Failure("the verifier didn't finish in $MAX_ROUNDS rounds", unreachable = unserved)
        } finally {
            engine.free(ctx)
        }
    }

    /**
     * A `req_ptr` (or a 64-bit mask) as the core prints it: the unsigned
     * decimal of a `uint64_t`. A pointer can sit above [Long.MAX_VALUE]
     * (arm64 tagged heap pointers, `0xb4…`), so it's read unsigned and
     * handed on as the same 64 bits in a `jlong`. A signed decimal is
     * taken as-is too.
     */
    internal fun parsePtr(text: String): Long? = text.trim().let { it.toULongOrNull()?.toLong() ?: it.toLongOrNull() }

    private fun resultHex(status: JSONObject): String {
        val result = status.opt("result")
        return (result as? String)?.takeIf { it.startsWith("0x") }
            ?: throw Failure("the verifier's result isn't return data")
    }

    private sealed class Served {
        class Ok(val body: ByteArray, val index: Int, val url: String) : Served()
        class Failed(val error: String) : Served()
    }

    /**
     * Run one request the core asked for: its servers in order, skipping
     * the ones its `exclude_mask` rules out (servers that already failed
     * it), until one answers 2xx.
     */
    private suspend fun serve(request: JSONObject, ethRpcs: List<String>): Served {
        request.optLong("delay", 0).takeIf { it > 0 }?.let { delay(minOf(it, MAX_DELAY_MS)) }
        val servers = serversFor(request, ethRpcs)
        val exclude = parsePtr(request.optString("exclude_mask")) ?: 0L
        val path = request.optString("url", "")
        val payload = request.optJSONObject("payload")
        val method = request.optString("method", "POST").uppercase().takeIf { it == "GET" || it == "POST" } ?: "POST"
        val ssz = request.optString("encoding") == "ssz"
        val ttl = request.optLong("ttl", 0)
        var lastError = "no server for ${request.optString("type")} requests"
        for ((index, server) in servers.withIndex()) {
            if (index < 63 && exclude and (1L shl index) != 0L) continue
            val url = if (path.isNotEmpty()) server.removeSuffix("/") + "/" + path.removePrefix("/") else server
            val headers = buildMap {
                put("accept", if (ssz) "application/octet-stream" else "application/json")
                if (payload != null) put("content-type", "application/json")
                if (ttl > 0) put("cache-control", "max-age=$ttl")
            }
            try {
                val reply = fetch(method, url, headers, payload?.toString()?.toByteArray())
                if (reply.code in 200..299) return Served.Ok(reply.body, index, url)
                lastError = "HTTP ${reply.code} from ${hostOf(url)}"
            } catch (e: IOException) {
                lastError = "${hostOf(url)}: ${e.message}"
            }
        }
        Log.i(TAG, "colibri ${request.optString("type")} request failed: $lastError")
        return Served.Failed(lastError)
    }

    /** The servers for [request]'s type, the way corpus.core's own Kotlin binding picks them. */
    internal fun serversFor(request: JSONObject, ethRpcs: List<String>): List<String> = when (request.optString("type", "eth_rpc")) {
        "prover" -> PROVERS
        // Light-client updates: the prover serves them (the binding's
        // `useProverFallback`), so no beacon node learns anything.
        "beacon_api" -> PROVERS
        "checkpointz" -> CHECKPOINTZ + BEACON_APIS
        else -> ethRpcs
    }

    /**
     * [http] on its own thread, bounded from outside: cancelling the
     * caller resumes it at once and closes the connection from yet
     * another thread, so neither a stalled read nor a blocking
     * `disconnect()` holds the resolution (or the native context) up.
     * A connection registered only after the cancel is refused on the
     * spot (and closed), so it can't run on to its own timeouts either.
     */
    private suspend fun fetch(method: String, url: String, headers: Map<String, String>, body: ByteArray?): Http.Reply =
        suspendCancellableCoroutine { cont ->
            val open = ConcurrentLinkedQueue<HttpURLConnection>()
            val cancelled = AtomicBoolean(false)
            fun closeAll() {
                aborts.launch { while (true) runCatching { (open.poll() ?: return@launch).disconnect() } }
            }
            // Register first, then check: either the canceller's sweep
            // finds the connection, or this sees the flag.
            val opened = { conn: HttpURLConnection ->
                open.add(conn)
                if (cancelled.get()) {
                    closeAll()
                    throw IOException("cancelled")
                }
            }
            val job = aborts.launch {
                val result = runCatching { http.request(method, url, headers, body, opened) }
                if (cont.isActive) cont.resume(result)
            }
            cont.invokeOnCancellation {
                cancelled.set(true)
                job.cancel()
                closeAll()
            }
        }.getOrElse { e ->
            throw e as? IOException ?: IOException(e.message ?: e.javaClass.simpleName, e)
        }

    /** The native verifier, as [EnsColibri] drives it; a seam for tests. */
    interface Engine {
        val available: Boolean
        fun create(method: String, params: String, chainId: Long, proverFlags: Int, verifyFlags: Int, proverMode: Int): Long
        fun setMinLatestBlockTs(ctx: Long, unixSeconds: Long)
        fun execute(ctx: Long): String?
        fun setResponse(req: Long, data: ByteArray, nodeIndex: Int)
        fun setError(req: Long, error: String, nodeIndex: Int)
        fun free(ctx: Long)
    }

    /**
     * [ColibriNative], set up on first use (off the main thread, from
     * [ethCall]) with its state in [statesDir] — `null` until the app has
     * said where, which leaves the tier off.
     */
    class NativeEngine(private val statesDir: () -> java.io.File?) : Engine {
        override val available: Boolean
            get() = ColibriNative.available || statesDir()?.let(ColibriNative::init) == true
        override fun create(method: String, params: String, chainId: Long, proverFlags: Int, verifyFlags: Int, proverMode: Int) =
            ColibriNative.createRpcCtx(method, params, chainId, proverFlags, verifyFlags, proverMode)
        override fun setMinLatestBlockTs(ctx: Long, unixSeconds: Long) = ColibriNative.setMinLatestBlockTs(ctx, unixSeconds)
        override fun execute(ctx: Long) = ColibriNative.execute(ctx)
        override fun setResponse(req: Long, data: ByteArray, nodeIndex: Int) = ColibriNative.setResponse(req, data, nodeIndex)
        override fun setError(req: Long, error: String, nodeIndex: Int) = ColibriNative.setError(req, error, nodeIndex)
        override fun free(ctx: Long) = ColibriNative.freeRpcCtx(ctx)
    }

    /** One blocking HTTP exchange with a binary body; a seam for tests. */
    fun interface Http {
        class Reply(val code: Int, val body: ByteArray)

        /** Perform it, handing every connection it opens to [opened] so a cancelled caller can close it. */
        fun request(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: ByteArray?,
            opened: (HttpURLConnection) -> Unit,
        ): Reply

        companion object {
            val Default = Http { method, url, headers, body, opened ->
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    // The core names every server itself; a redirect
                    // could only lead somewhere it didn't.
                    instanceFollowRedirects = false
                    for ((k, v) in headers) setRequestProperty(k, v)
                    if (body != null) doOutput = true
                }
                opened(conn)
                try {
                    if (body != null) conn.outputStream.use { it.write(body) }
                    val code = conn.responseCode
                    if (conn.contentLengthLong > MAX_RESPONSE_BYTES) throw IOException("response exceeds $MAX_RESPONSE_BYTES bytes")
                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                    Reply(code, stream?.use(::readBounded) ?: ByteArray(0))
                } finally {
                    conn.disconnect()
                }
            }

            private fun readBounded(input: java.io.InputStream): ByteArray {
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_RESPONSE_BYTES) throw IOException("response exceeds $MAX_RESPONSE_BYTES bytes")
                    out.write(buf, 0, n)
                }
                return out.toByteArray()
            }
        }
    }

    companion object {
        private const val TAG = "EnsColibri"
        private const val MAINNET = 1L

        /** Desktop's and iOS's pinned freshness window for `latest` proofs. */
        const val MAX_LATEST_AGE_SECONDS = 60L

        /**
         * corpus.core's mainnet provers — iOS's default first, then its
         * twin. A prover can only fail a call, never forge one.
         */
        val PROVERS = listOf(
            "https://mainnet1.colibri-proof.tech",
            "https://mainnet.colibri-proof.tech",
        )

        /**
         * Where the verifier gets the checkpoint it bootstraps the sync
         * committee from: corpus.core's per-chain public checkpointz
         * defaults, as every Colibri binding uses them, then beacon nodes.
         */
        val CHECKPOINTZ = listOf(
            "https://sync-mainnet.beaconcha.in",
            "https://mainnet.checkpoint.sigp.io",
            "https://mainnet-checkpoint-sync.attestant.io",
            "https://beaconstate-mainnet.chainsafe.io",
            "https://mainnet-checkpoint-sync.stakely.io",
            "https://checkpointz.pietjepuk.net",
            "https://beaconstate.ethstaker.cc",
        )
        val BEACON_APIS = listOf(
            "https://mainnet.colibri-proof.tech/consensus",
            "https://ethereum-beacon-api.publicnode.com",
        )

        /** The binding's bound on state-machine rounds. */
        private const val MAX_ROUNDS = 50
        private const val MAX_DELAY_MS = 2_000L
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 15_000

        /** A proof or a light-client update is tens to hundreds of KB. */
        private const val MAX_RESPONSE_BYTES = 8L * 1024 * 1024

        /** Where cancelled requests are closed from: never the thread blocked in them. */
        private val aborts = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        internal fun hostOf(url: String): String? = runCatching { URI(url).host }.getOrNull()
    }
}
