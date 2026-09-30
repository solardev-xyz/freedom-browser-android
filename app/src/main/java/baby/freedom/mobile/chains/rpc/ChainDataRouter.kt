package baby.freedom.mobile.chains.rpc

import android.app.Application
import android.content.Context
import android.util.Log
import baby.freedom.mobile.browser.PublicSuffixList
import baby.freedom.mobile.chains.Chain
import baby.freedom.mobile.chains.RpcUrls
import baby.freedom.mobile.data.ChainStore
import baby.freedom.mobile.ens.EnsColibri
import baby.freedom.mobile.ens.EnsRpcConfig
import baby.freedom.mobile.ens.Keccak256
import baby.freedom.mobile.ens.hexToBytes
import baby.freedom.mobile.ens.toHex
import baby.freedom.swarm.ColibriNative
import java.io.File
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray

/**
 * Every chain read goes through here (#108): the Android port of
 * desktop's `chain-data-router.js` and iOS's `ChainDataRouter`. For each
 * read, the chain's [ChainAccessPolicy] names the tiers to walk, in
 * order — by default Myotis, then Colibri, then an RPC quorum, then a
 * single RPC — and the first tier with an answer wins, labelled with how
 * it was checked ([ChainTrust]):
 *
 * - **Myotis / Colibri** ([VerifiedChainSource], #329): a proof — the
 *   embedded light client ([MyotisChainSource]) when the user runs it for
 *   the chain and it's ready, then corpus.core's Colibri prover
 *   ([ColibriChainSource]). Each answers only what it can prove (a
 *   `pending` nonce, say, or a receipt it hasn't seen, moves on), and a
 *   tier that isn't available is skipped.
 * - **Quorum** ([QuorumRun]): the chain's first K RPCs from different
 *   providers ([quorumMembers]) are asked the same bytes at once; M
 *   identical answers are verified. Needs M providers. The user's own
 *   RPCs ([Chain.userRpcUrls]) lead the pool, so they take the first
 *   seats — alongside public RPCs, which see the same read, unless the
 *   user has K providers of their own.
 * - **Direct**: the first RPC that answers, unverified — or
 *   [ChainTrust.Level.USER_CONFIGURED] when it's one the user added. After a
 *   quorum that fell short, it reuses the quorum's best answer (and says
 *   who agreed and dissented) instead of asking the same RPC again, and
 *   never re-asks an RPC the quorum already asked.
 *
 * A deterministic answer — a revert with data, `-32602`, insufficient
 * funds, from any tier (or M agreeing quorum members) — is thrown as
 * [ChainRpcException.Rpc] and ends the walk: every other source would say
 * the same. Anything else moves on to the next tier;
 * [ChainRpcException.AllSourcesFailed] when none is left.
 *
 * A page-driven read ([RoutingContext.forPage]) gives each tier but the
 * last [INTERACTIVE_DEADLINE_MS] at most before falling through; the
 * wallet's own reads keep the chain's full timeout, so verification is
 * never traded away where nobody is waiting on a frame. RPCs whose
 * transport failed in the last [QUARANTINE_MS] move to the back of the
 * pool.
 *
 * Not ported yet: desktop's per-route cooldowns and its light-client
 * admission queue — here each proof source admits a few reads at once and
 * turns the rest away at once, so they move on to the next tier rather
 * than wait. Broadcasts don't go through the proof tiers yet (neither
 * source broadcasts, [VerifiedChainSource.canBroadcast]). ENS resolution keeps its own resolver
 * ([baby.freedom.mobile.ens.EnsResolver]), as on iOS.
 */
class ChainDataRouter internal constructor(
    private val chains: suspend () -> List<Chain>,
    private val transport: RpcTransport,
    private val verifiedSources: Map<ChainSource, VerifiedChainSource> = emptyMap(),
    private val policyFor: (Chain) -> ChainAccessPolicy = { ChainAccessPolicy.default(it.id) },
    private val clock: () -> Long = System::currentTimeMillis,
    /** Where quorum legs run: detached from the caller, see [QuorumRun]. */
    private val legScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    /** url → when its transport last failed. */
    private val failedAt = ConcurrentHashMap<String, Long>()

    /** The policy [chain]'s reads and broadcasts actually follow, unavailable sources included. */
    fun policy(chain: Chain): ChainAccessPolicy = policyFor(chain).sanitized(chain.id)

    /** Whether [source] can answer for [chainId] in this build right now. */
    fun isWired(source: ChainSource, chainId: Long): Boolean = when (source) {
        ChainSource.MYOTIS, ChainSource.COLIBRI -> verifiedSources[source]?.isAvailable(chainId) == true
        ChainSource.QUORUM, ChainSource.DIRECT -> true
    }

    /**
     * Read [method] ([READ_METHODS]) on chain [chainId]. Throws
     * [ChainRpcException] (or `CancellationException`), never anything else.
     *
     * [agreeOn], when given, is applied to every endpoint's answer before
     * the quorum compares them, and its output is the result: the part of
     * an answer the caller needs, so fields providers shape differently
     * (an empty `reward` present or absent, extra blob fields) don't cost
     * an agreement on the part that matters. It must not throw.
     *
     * [rankError], when given, is shown every way a source fails on the
     * walk ([ChainFailure]: each quorum member's error or transport
     * failure, each direct RPC's, a light client's) and ranks it
     * ([ErrorRank]) — desktop's `rankError` / `createErrorKeeper`, which
     * the Swarm node's log scans use ([baby.freedom.mobile.node.AntChainBridge]).
     * The walk keeps the highest-ranked failure seen (a later one replaces
     * it only by ranking strictly higher, or, timeout by timeout, as the
     * attempt that actually ended the request) and reports it as
     * [ChainRpcException.AllSourcesFailed.kept] when every source fails.
     * A failure ranked [ErrorRank.REQUEST] — one that depends on the query,
     * not the endpoint — ends the walk as soon as the tier that saw it has
     * failed: no later tier or RPC is asked, with one exception, desktop's:
     * a quorum member's answer the direct tier reuses without a new request
     * still wins — an answer beats any error. Unlike desktop, that includes
     * a member still in flight when the others' refusals failed the quorum:
     * the direct tier waits for it (within its endpoint timeout) instead of
     * handing back the refusal. On Gnosis's shipped RPCs a wide log scan
     * gets a fast refusal from two and an answer, a second later, from the
     * third; without the wait the Swarm node's scan halves, succeeds,
     * doubles and is refused again, window after window, for many minutes.
     * Every other rank falls through
     * like any failure. Without [rankError] nothing is kept or ends early.
     */
    suspend fun request(
        chainId: Long,
        method: String,
        params: JSONArray = JSONArray(),
        context: RoutingContext = RoutingContext.WALLET,
        agreeOn: ((Any?) -> Any?)? = null,
        rankError: ((ChainFailure) -> Int)? = null,
    ): ChainDataResult {
        if (method !in READ_METHODS) throw ChainRpcException.UnsupportedMethod(method)
        val chain = chain(chainId)
        val policy = policy(chain)
        val normalized = JsonRpc.normalizeParams(method, params)
        val body = JsonRpc.body(method, normalized)
        val pool = pool(chain)
        val failures = mutableListOf<String>()
        var nodeError: ChainRpcException.Rpc? = null
        var quorum: QuorumRun? = null
        val keeper = ErrorKeeper(rankError)
        val started = clock()
        try {
            for ((index, source) in policy.readOrder.withIndex()) {
                if (method in DIRECT_ONLY_METHODS && source != ChainSource.DIRECT) continue
                val hasFallback = index < policy.readOrder.lastIndex
                val waitMs = if (context.interactive && hasFallback) {
                    minOf(policy.timeoutMs, INTERACTIVE_DEADLINE_MS)
                } else {
                    policy.timeoutMs
                }
                val t0 = clock()
                val outcome: Any? = when (source) {
                    ChainSource.MYOTIS, ChainSource.COLIBRI ->
                        verified(source, chain, pool, method, normalized, waitMs, keeper)
                            .let { o -> if (agreeOn != null && o is ChainDataResult) o.copy(result = agreeOn(o.result)) else o }
                    ChainSource.QUORUM -> {
                        val members = quorumMembers(pool, policy.quorumK)
                        if (members.size < policy.quorumM) {
                            "needs ${policy.quorumM} RPC providers, the chain has ${quorumMembers(pool).size}"
                        } else {
                            val keepLegs = policy.readOrder.getOrNull(index + 1) == ChainSource.DIRECT
                            val run = QuorumRun(members, policy.quorumM, legScope) { url ->
                                // Every leg keeps the full endpoint timeout,
                                // whatever the quorum waits: a page read's
                                // shorter wait is ours, and a slow-but-healthy
                                // RPC cut off by it mustn't be quarantined as
                                // down. Legs nobody will reuse are cancelled
                                // (never counted as failures) once the quorum
                                // gives up.
                                call(url, body, policy.timeoutMs, agreeOn)
                            }
                            quorum = run
                            when (val v = run.await(waitMs)) {
                                is QuorumRun.Verdict.Agreed -> quorumResult(v, run, policy)
                                is QuorumRun.Verdict.Failed -> {
                                    v.nodeError?.let { nodeError = it }
                                    if (rankError != null) {
                                        keeper.noteQuorum(run)
                                        // Members cut off by the quorum's wait
                                        // and then cancelled. Those the direct
                                        // tier waits for instead are noted as
                                        // they really end, there.
                                        if (!keepLegs && v.timedOut && run.pending() > 0) {
                                            keeper.note(ChainFailure(null, "no answer within ${waitMs}ms", null, timeout = true))
                                        }
                                    }
                                    if (!keepLegs) run.cancel()
                                    v.reason
                                }
                            }
                        }
                    }
                    ChainSource.DIRECT -> direct(chain, pool, body, policy, quorum, agreeOn, keeper) { nodeError = it }
                }
                if (outcome is ChainDataResult) {
                    Log.i(TAG, "[chain-data] $method chain=$chainId via ${source.key} ${clock() - t0}ms " +
                        "(${outcome.trust.level.name.lowercase()}, total ${clock() - started}ms)")
                    return outcome
                }
                val reason = outcome as? String ?: "no answer"
                Log.i(TAG, "[chain-data] $method chain=$chainId ${source.key} failed after ${clock() - t0}ms: $reason")
                failures += "${source.key}: $reason"
                // A member's answer the direct tier reuses without a new
                // request beats any error, a final one included — so is one
                // still in flight, bounded by its own endpoint timeout.
                val reusable = source == ChainSource.QUORUM && policy.readOrder.getOrNull(index + 1) == ChainSource.DIRECT &&
                    quorum?.let { it.hasAnswer() || it.pending() > 0 } == true
                if (keeper.final && !reusable) {
                    Log.i(TAG, "[chain-data] $method chain=$chainId: a source refused the query itself; not asking further")
                    break
                }
            }
        } finally {
            quorum?.cancel()
        }
        throw ChainRpcException.AllSourcesFailed(failures, nodeError, kept = keeper.error)
    }

    /**
     * Send the signed [rawTransaction] (`0x…`) on chain [chainId] through
     * the policy's broadcast order. [ChainDataResult.result] is the
     * transaction hash — always the one computed here from the bytes
     * sent, never whatever the node echoed back (a `true`, a malformed
     * or foreign hash would otherwise be tracked instead). A node that already has the transaction
     * (`already known`, typically after an earlier endpoint took it but
     * timed out answering) counts as sent: the hash is the transaction's
     * own. A light client's uncertain outcome ends the walk
     * ([ChainRpcException.BroadcastUncertain]) — it may be propagating.
     * A source that never gave a verdict (timed out, dropped) may have
     * taken it too, so the walk's failure then says so
     * ([ChainRpcException.AllSourcesFailed.unanswered]), and a
     * deterministic refusal after one is reported the same way rather
     * than thrown as the whole answer.
     */
    suspend fun broadcast(chainId: Long, rawTransaction: String): ChainDataResult {
        val chain = chain(chainId)
        val policy = policy(chain)
        val raw = rawTransaction.trim()
        val hash = runCatching { "0x" + Keccak256.digest(raw.hexToBytes()).toHex() }.getOrNull()
            ?.takeIf { raw.startsWith("0x") && raw.length > 2 }
            ?: throw ChainRpcException.InvalidResponse("not a signed transaction")
        val body = JsonRpc.body("eth_sendRawTransaction", JSONArray().put(raw))
        val failures = mutableListOf<String>()
        var nodeError: ChainRpcException.Rpc? = null
        var unanswered = false
        // A node's final word ends the walk — unless an earlier source may
        // already have the transaction, when it's only one node's view.
        fun refused(e: ChainRpcException.Rpc): Nothing =
            if (unanswered) throw ChainRpcException.AllSourcesFailed(failures + "refused: ${e.message}", e, unanswered = true) else throw e
        for (source in policy.broadcastOrder) {
            when (source) {
                ChainSource.MYOTIS, ChainSource.COLIBRI -> {
                    val s = verifiedSources[source]?.takeIf { it.canBroadcast && it.isAvailable(chain.id) }
                    if (s == null) {
                        failures += "${source.key}: not available"
                        continue
                    }
                    try {
                        val sent = s.broadcast(chain.id, raw)
                        if (!sent.equals(hash, ignoreCase = true)) {
                            Log.w(TAG, "[chain-data] broadcast chain=$chainId ${source.key} answered a different hash")
                        }
                        return ChainDataResult(hash, oneOf(source, ChainTrust.Level.VERIFIED, source.key))
                    } catch (e: CancellationException) {
                        currentCoroutineContext().ensureActive()
                        unanswered = true
                        failures += "${source.key}: cancelled"
                    } catch (e: ChainRpcException.BroadcastUncertain) {
                        throw e
                    } catch (e: ChainRpcException.Rpc) {
                        if (e.deterministic) refused(e)
                        nodeError = e
                        failures += "${source.key}: ${e.message}"
                    } catch (e: Exception) {
                        unanswered = true
                        failures += "${source.key}: ${e.message}"
                    }
                }
                ChainSource.DIRECT -> for (url in pool(chain)) {
                    when (val leg = call(url, body, policy.timeoutMs)) {
                        is Leg.Value -> {
                            val echoed = (leg.result as? String)?.equals(hash, ignoreCase = true) == true
                            Log.i(TAG, "[chain-data] broadcast chain=$chainId via direct ${hostOf(url)}" +
                                if (echoed) "" else " (it answered a different hash)")
                            return ChainDataResult(hash, direct(chain, url))
                        }
                        is Leg.Deterministic -> refused(leg.error)
                        is Leg.NodeError -> {
                            if (ALREADY_KNOWN.containsMatchIn(leg.error.rpcMessage)) {
                                return ChainDataResult(hash, direct(chain, url))
                            }
                            nodeError = leg.error
                            failures += "direct ${hostOf(url)}: ${leg.error.message}"
                        }
                        is Leg.Failed -> {
                            unanswered = true
                            failures += "direct ${hostOf(url)}: ${leg.reason}"
                        }
                    }
                }
                ChainSource.QUORUM -> Unit // sanitized out: can't broadcast
            }
        }
        throw ChainRpcException.AllSourcesFailed(failures, nodeError, unanswered)
    }

    private suspend fun chain(chainId: Long): Chain =
        chains().firstOrNull { it.id == chainId } ?: throw ChainRpcException.UnknownChain(chainId)

    /**
     * The chain's RPCs in the order tiers use them: the user's own, then
     * the public ones, each group with recently failed RPCs moved last.
     * A failed RPC of the user's stays ahead of every public one — it was
     * put there on purpose, and one blip mustn't take it out of the
     * quorum for [QUARANTINE_MS].
     */
    private fun pool(chain: Chain): List<String> {
        val now = clock()
        // One entry per endpoint ([EnsRpcConfig.endpointKey]), the user's
        // own spelling kept: `https://eth.drpc.org:443` is the listed
        // `https://eth.drpc.org`, asked once and labelled as the user's.
        val all = (chain.userRpcUrls + chain.rpcUrls).distinctBy(EnsRpcConfig::endpointKey)
        return all.sortedWith(
            compareBy<String> { it !in chain.userRpcUrls }
                .thenBy { url -> failedAt[url]?.let { now - it in 0 until QUARANTINE_MS } == true },
        )
    }

    private suspend fun verified(
        source: ChainSource,
        chain: Chain,
        pool: List<String>,
        method: String,
        params: JSONArray,
        waitMs: Long,
        keeper: ErrorKeeper,
    ): Any? {
        fun failed(reason: String, timeout: Boolean = false): String =
            reason.also { keeper.note(ChainFailure(null, it, null, timeout)) }
        val s = verifiedSources[source]?.takeIf { it.isAvailable(chain.id) } ?: return failed("not available")
        return try {
            withTimeoutOrNull(waitMs) { s.request(chain.id, method, params, pool) }
                ?: failed("no answer within ${waitMs}ms", timeout = true)
        } catch (e: CancellationException) {
            // The source's own cancellation, not ours: no answer.
            currentCoroutineContext().ensureActive()
            failed("cancelled")
        } catch (e: ChainRpcException.Rpc) {
            if (e.deterministic) throw e
            keeper.note(ChainFailure.of(e))
            e.message
        } catch (e: Exception) {
            failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun quorumResult(v: QuorumRun.Verdict.Agreed, run: QuorumRun, policy: ChainAccessPolicy): ChainDataResult {
        when (val a = v.answer) {
            is Leg.Deterministic -> throw a.error
            is Leg.Value -> return ChainDataResult(
                a.result,
                ChainTrust(
                    level = ChainTrust.Level.VERIFIED,
                    source = ChainSource.QUORUM,
                    agreed = v.agreed.map(::hostOf),
                    dissented = v.dissented.map(::hostOf),
                    queried = run.urls.map(::hostOf),
                    k = run.urls.size,
                    m = policy.quorumM,
                    block = null,
                ),
            )
        }
    }

    private suspend fun direct(
        chain: Chain,
        pool: List<String>,
        body: String,
        policy: ChainAccessPolicy,
        quorum: QuorumRun?,
        agreeOn: ((Any?) -> Any?)?,
        keeper: ErrorKeeper,
        onNodeError: (ChainRpcException.Rpc) -> Unit,
    ): Any {
        quorum?.directCandidate()?.let { c ->
            Log.i(TAG, "[chain-data] direct reuses quorum member ${hostOf(c.url)}")
            return when (val a = c.answer) {
                is Leg.Deterministic -> throw a.error
                is Leg.Value -> ChainDataResult(
                    a.result,
                    ChainTrust(
                        level = levelOf(chain, c.url),
                        source = ChainSource.DIRECT,
                        agreed = c.agreed.map(::hostOf),
                        dissented = c.dissented.map(::hostOf),
                        queried = quorum.urls.map(::hostOf),
                        k = quorum.urls.size,
                        m = policy.quorumM,
                        block = null,
                    ),
                )
            }
        }
        // The members it just waited for without an answer: how they
        // failed counts like any other failure — a range cap or a timeout
        // from the one still in flight when the others' refusals failed
        // the quorum is what ends the walk or makes the caller halve.
        quorum?.let(keeper::noteQuorum)
        val asked = quorum?.asked().orEmpty()
        var last: String? = null
        // A failure about the query itself: no other RPC would answer it.
        if (keeper.final) return "a source refused the query itself"
        for (url in pool) {
            if (url in asked) continue
            when (val leg = call(url, body, policy.timeoutMs, agreeOn)) {
                is Leg.Value -> return ChainDataResult(leg.result, direct(chain, url))
                is Leg.Deterministic -> throw leg.error
                is Leg.NodeError -> {
                    onNodeError(leg.error)
                    keeper.note(ChainFailure.of(leg.error))
                    last = "${hostOf(url)}: ${leg.error.message}"
                }
                is Leg.Failed -> {
                    // No host in it: it can carry a key (a user RPC's subdomain).
                    keeper.note(ChainFailure(null, leg.reason, null, leg.timeout))
                    last = "${hostOf(url)}: ${leg.reason}"
                }
            }
            if (keeper.final) break
        }
        return last ?: if (asked.isNotEmpty()) "every RPC was asked by the quorum" else "no RPC endpoints"
    }

    private fun direct(chain: Chain, url: String) = oneOf(ChainSource.DIRECT, levelOf(chain, url), hostOf(url))

    private fun levelOf(chain: Chain, url: String) =
        if (url in chain.userRpcUrls) ChainTrust.Level.USER_CONFIGURED else ChainTrust.Level.UNVERIFIED

    private fun oneOf(source: ChainSource, level: ChainTrust.Level, host: String) = ChainTrust(
        level = level,
        source = source,
        agreed = listOf(host),
        dissented = emptyList(),
        queried = listOf(host),
        k = 1,
        m = 1,
        block = null,
    )

    /**
     * One request to one RPC, classified. Never throws but for our own
     * cancellation. Only a transport or envelope failure counts against
     * the RPC — a JSON-RPC error is the node answering per spec.
     */
    private suspend fun call(url: String, body: String, timeoutMs: Long, agreeOn: ((Any?) -> Any?)? = null): Leg {
        val text = try {
            transport.post(url, body, timeoutMs)
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            return Leg.Failed("cancelled", timeout = false)
        } catch (e: Exception) {
            // A transport that surfaces our own cancellation as an I/O
            // error isn't the RPC failing.
            currentCoroutineContext().ensureActive()
            failedAt[url] = clock()
            return Leg.Failed(e.message ?: e.javaClass.simpleName, timeout = e is RpcTimeoutException)
        }
        return when (val env = JsonRpc.parse(text)) {
            is JsonRpc.Envelope.Result -> {
                failedAt.remove(url)
                Leg.Value(if (agreeOn != null) agreeOn(env.value) else env.value)
            }
            is JsonRpc.Envelope.Error ->
                if (env.error.deterministic) Leg.Deterministic(env.error) else Leg.NodeError(env.error)
            is JsonRpc.Envelope.Malformed -> {
                failedAt[url] = clock()
                Leg.Failed(env.reason, timeout = false)
            }
        }
    }

    /**
     * How useful a failure is to a caller that ranks them
     * ([request]'s `rankError`), lowest first — desktop's `ERROR_RANK`.
     */
    object ErrorRank {
        /** Depends on the endpoint (a throttle, method not found, a transport failure); the caller can't act on it. */
        const val ENDPOINT = 0

        /** Might be about the query, might be about the endpoint: kept, but later sources are still asked. */
        const val HINT = 1

        /** The attempt ran out of time; another source or a smaller query may answer. */
        const val TIMEOUT = 2

        /** About the query itself (its size): every source would say the same, so the walk ends. */
        const val REQUEST = 3
    }

    /** The best failure one [request] has seen so far, by its caller's ranking. */
    internal class ErrorKeeper(private val rank: ((ChainFailure) -> Int)?) {
        private var kept: ChainFailure? = null
        private var keptRank = -1

        /** How many of the quorum's failures ([QuorumRun.failures], append-only) are noted already. */
        private var quorumNoted = 0

        /** Note the quorum's failures not yet noted: those that ended since the last call. */
        fun noteQuorum(run: QuorumRun) {
            if (rank == null) return
            val failures = run.failures()
            failures.drop(quorumNoted).forEach(::note)
            quorumNoted = failures.size
        }

        fun note(failure: ChainFailure) {
            val rank = rank ?: return
            val r = try {
                rank(failure)
            } catch (_: Exception) {
                ErrorRank.ENDPOINT
            }
            if (r > keptRank || (r == keptRank && r == ErrorRank.TIMEOUT)) {
                kept = failure
                keptRank = r
            }
        }

        /** A failure about the query itself has been seen: asking on is pointless. */
        val final: Boolean get() = keptRank >= ErrorRank.REQUEST

        /** The kept failure, when it's one the caller can act on. */
        val error: ChainFailure? get() = kept.takeIf { keptRank > ErrorRank.ENDPOINT }
    }

    companion object {
        private const val TAG = "ChainData"

        /** How long a page-driven read gives a tier with another behind it. */
        const val INTERACTIVE_DEADLINE_MS = 2_000L

        /** How long an RPC whose transport failed waits at the back of the pool. */
        const val QUARANTINE_MS = 10L * 60 * 1000

        /** Desktop's `READ_METHODS`. */
        val READ_METHODS = setOf(
            "eth_blockNumber", "eth_getBalance", "eth_getCode", "eth_getStorageAt",
            "eth_getTransactionCount", "eth_getBlockByNumber", "eth_getBlockByHash",
            "eth_getTransactionByHash", "eth_getTransactionReceipt", "eth_call", "eth_estimateGas",
            "eth_gasPrice", "eth_feeHistory", "eth_maxPriorityFeePerGas", "eth_getLogs",
            "eth_getFilterChanges", "eth_getFilterLogs", "eth_newFilter", "eth_newBlockFilter",
            "eth_newPendingTransactionFilter", "eth_uninstallFilter",
            "web3_clientVersion", "web3_sha3",
        )

        /**
         * Reads only a single node can answer: filters live on the node
         * that made them, and `web3_*` describes the node itself.
         */
        val DIRECT_ONLY_METHODS = setOf(
            "eth_getFilterChanges", "eth_getFilterLogs", "eth_newFilter", "eth_newBlockFilter",
            "eth_newPendingTransactionFilter", "eth_uninstallFilter", "web3_clientVersion", "web3_sha3",
        )

        /** [providerOf] for every RPC on the device itself. */
        internal const val LOOPBACK_PROVIDER = "loopback"

        private val ALREADY_KNOWN = Regex("already ?known|known transaction|already imported", RegexOption.IGNORE_CASE)

        /**
         * The first [k] of [pool] run by different providers, in order:
         * two URLs on one registrable domain (`ethereum.publicnode.com/?x`
         * and `ethereum.publicnode.com`, or `eth.drpc.org` and
         * `lb.drpc.org`) are one operator and so one vote, never two.
         */
        fun quorumMembers(pool: List<String>, k: Int = Int.MAX_VALUE): List<String> =
            pool.distinctBy(::providerOf).take(k)

        /**
         * Who runs [url], for telling quorum voters apart: its host's
         * registrable domain, or the host itself for an IP literal or a
         * host without one. The port doesn't count — two ports on one
         * machine are one operator — and every loopback spelling
         * (`localhost`, `*.localhost`, any `127.0.0.0/8` literal, `[::1]`)
         * is the one device, so the user's own node added twice can never
         * outvote a public RPC. A provider serving from more than one
         * registrable domain is one provider too ([PROVIDER_ALIASES]):
         * DRPC's keyed `lb.drpc.live` and its public `eth.drpc.org` are
         * the same operator's answer.
         *
         * Name resolution's quorum counts its voters by this too
         * ([baby.freedom.mobile.ens.EnsQuorum.voters]).
         */
        internal fun providerOf(url: String): String {
            if (RpcUrls.isLoopbackUrl(url)) return LOOPBACK_PROVIDER
            val host = try {
                URI(url).host
            } catch (_: Exception) {
                null
            }?.lowercase()?.removePrefix("[")?.removeSuffix("]")?.trimEnd('.')
                ?: return url
            val ipLiteral = ':' in host || host.all { it.isDigit() || it == '.' }
            if (ipLiteral) return host
            val domain = PublicSuffixList.registrableDomain(host) ?: host
            return PROVIDER_ALIASES[domain] ?: domain
        }

        /**
         * Registrable domains one provider serves RPCs from besides its
         * main one → that main one. Only operators with a keyed and a
         * public endpoint on different domains need an entry.
         */
        private val PROVIDER_ALIASES = mapOf("drpc.live" to "drpc.org")

        /** An RPC's host (and port), never its path or query — those can carry a key. */
        internal fun hostOf(url: String): String = try {
            URI(url).rawAuthority ?: url
        } catch (_: Exception) {
            "?"
        }

        @Volatile
        private var instance: ChainDataRouter? = null

        fun get(context: Context): ChainDataRouter =
            instance ?: synchronized(this) {
                instance ?: run {
                    val store = ChainStore.get(context)
                    ChainDataRouter(
                        chains = { store.chains.first() },
                        transport = PinnedHttpTransport(),
                        verifiedSources = verifiedSources(context),
                    )
                }.also { instance = it }
            }

        /**
         * The proof tiers (#329): the light client through this process's
         * binding to `:myotis` ([baby.freedom.mobile.node.MyotisLink]) and
         * the Colibri verifier. Colibri keeps its sync-committee state in
         * `colibri` in the app's own process — the directory name
         * resolution uses, since the verifier is set up once per process —
         * and in `colibri-<process>` elsewhere (the Swarm node's `:node`),
         * so two processes never write one directory.
         */
        internal fun verifiedSources(context: Context): Map<ChainSource, VerifiedChainSource> {
            val app = context.applicationContext
            val suffix = Application.getProcessName().substringAfter(':', "")
            val statesDir = File(app.filesDir, if (suffix.isEmpty()) "colibri" else "colibri-$suffix")
            return mapOf(
                ChainSource.MYOTIS to MyotisChainSource(),
                ChainSource.COLIBRI to ColibriChainSource(
                    EnsColibri(EnsColibri.NativeEngine { statesDir }),
                    present = { ColibriNative.available || !ColibriNative.initFailed },
                ),
            )
        }
    }
}
