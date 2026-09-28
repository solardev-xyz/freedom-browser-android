package baby.freedom.mobile.chains.rpc

import android.content.Context
import android.util.Log
import baby.freedom.mobile.chains.Chain
import baby.freedom.mobile.data.ChainStore
import baby.freedom.mobile.ens.Keccak256
import baby.freedom.mobile.ens.hexToBytes
import baby.freedom.mobile.ens.toHex
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
 * - **Myotis / Colibri** ([VerifiedChainSource]): a proof. No source is
 *   wired on Android yet (the light client is #72), so these tiers are
 *   skipped and a read on Ethereum or Gnosis starts at the quorum.
 * - **Quorum** ([QuorumRun]): the chain's first K RPCs are asked the same
 *   bytes; M identical answers are verified. Needs a pool of at least M.
 * - **Direct**: the first RPC that answers, unverified — or
 *   [ChainTrust.Level.USER_CONFIGURED] when it's one the user added
 *   ([Chain.userRpcUrls], tried before the chain's public ones). After a
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
 * Not ported yet, along with the sources they serve: desktop's per-route
 * cooldowns and the light-client / prover admission queues, which only
 * matter once Myotis or Colibri is wired and a page drives reads (the
 * dapp bridge, `web3://` apps). ENS resolution keeps its own resolver
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
     */
    suspend fun request(
        chainId: Long,
        method: String,
        params: JSONArray = JSONArray(),
        context: RoutingContext = RoutingContext.WALLET,
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
                        verified(source, chain, method, normalized, waitMs)
                    ChainSource.QUORUM -> {
                        if (pool.size < policy.quorumM) {
                            "needs ${policy.quorumM} RPCs, the chain has ${pool.size}"
                        } else {
                            val keepLegs = policy.readOrder.getOrNull(index + 1) == ChainSource.DIRECT
                            val run = QuorumRun(pool.take(policy.quorumK), policy.quorumM, legScope) { url ->
                                // A leg a direct tier may reuse keeps the full
                                // endpoint timeout, whatever the quorum waits.
                                call(url, body, if (keepLegs) policy.timeoutMs else waitMs)
                            }
                            quorum = run
                            when (val v = run.await(waitMs)) {
                                is QuorumRun.Verdict.Agreed -> quorumResult(v, run, policy)
                                is QuorumRun.Verdict.Failed -> {
                                    v.nodeError?.let { nodeError = it }
                                    if (!keepLegs) run.cancel()
                                    v.reason
                                }
                            }
                        }
                    }
                    ChainSource.DIRECT -> direct(chain, pool, body, policy, quorum) { nodeError = it }
                }
                if (outcome is ChainDataResult) {
                    Log.i(TAG, "[chain-data] $method chain=$chainId via ${source.key} ${clock() - t0}ms " +
                        "(${outcome.trust.level.name.lowercase()}, total ${clock() - started}ms)")
                    return outcome
                }
                val reason = outcome as? String ?: "no answer"
                Log.i(TAG, "[chain-data] $method chain=$chainId ${source.key} failed after ${clock() - t0}ms: $reason")
                failures += "${source.key}: $reason"
            }
        } finally {
            quorum?.cancel()
        }
        throw ChainRpcException.AllSourcesFailed(failures, nodeError)
    }

    /**
     * Send the signed [rawTransaction] (`0x…`) on chain [chainId] through
     * the policy's broadcast order. [ChainDataResult.result] is the
     * transaction hash. A node that already has the transaction
     * (`already known`, typically after an earlier endpoint took it but
     * timed out answering) counts as sent: the hash is the transaction's
     * own. A light client's uncertain outcome ends the walk
     * ([ChainRpcException.BroadcastUncertain]) — it may be propagating.
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
        for (source in policy.broadcastOrder) {
            when (source) {
                ChainSource.MYOTIS, ChainSource.COLIBRI -> {
                    val s = verifiedSources[source]?.takeIf { it.isAvailable(chain.id) }
                    if (s == null) {
                        failures += "${source.key}: not available"
                        continue
                    }
                    try {
                        val sent = s.broadcast(chain.id, raw)
                        return ChainDataResult(sent, oneOf(source, ChainTrust.Level.VERIFIED, source.key))
                    } catch (e: CancellationException) {
                        currentCoroutineContext().ensureActive()
                        failures += "${source.key}: cancelled"
                    } catch (e: ChainRpcException.BroadcastUncertain) {
                        throw e
                    } catch (e: ChainRpcException.Rpc) {
                        if (e.deterministic) throw e
                        nodeError = e
                        failures += "${source.key}: ${e.message}"
                    } catch (e: Exception) {
                        failures += "${source.key}: ${e.message}"
                    }
                }
                ChainSource.DIRECT -> for (url in pool(chain)) {
                    when (val leg = call(url, body, policy.timeoutMs)) {
                        is Leg.Value -> {
                            Log.i(TAG, "[chain-data] broadcast chain=$chainId via direct ${hostOf(url)}")
                            return ChainDataResult(leg.result ?: hash, direct(chain, url))
                        }
                        is Leg.Deterministic -> throw leg.error
                        is Leg.NodeError -> {
                            if (ALREADY_KNOWN.containsMatchIn(leg.error.rpcMessage)) {
                                return ChainDataResult(hash, direct(chain, url))
                            }
                            nodeError = leg.error
                            failures += "direct ${hostOf(url)}: ${leg.error.message}"
                        }
                        is Leg.Failed -> failures += "direct ${hostOf(url)}: ${leg.reason}"
                    }
                }
                ChainSource.QUORUM -> Unit // sanitized out: can't broadcast
            }
        }
        throw ChainRpcException.AllSourcesFailed(failures, nodeError)
    }

    private suspend fun chain(chainId: Long): Chain =
        chains().firstOrNull { it.id == chainId } ?: throw ChainRpcException.UnknownChain(chainId)

    /**
     * The chain's RPCs in the order tiers use them: the user's own, then
     * the public ones, each group with recently failed RPCs moved last.
     */
    private fun pool(chain: Chain): List<String> {
        val now = clock()
        val all = (chain.userRpcUrls + chain.rpcUrls).distinct()
        return all.sortedBy { url -> failedAt[url]?.let { now - it in 0 until QUARANTINE_MS } == true }
    }

    private suspend fun verified(
        source: ChainSource,
        chain: Chain,
        method: String,
        params: JSONArray,
        waitMs: Long,
    ): Any? {
        val s = verifiedSources[source]?.takeIf { it.isAvailable(chain.id) } ?: return "not available"
        return try {
            withTimeoutOrNull(waitMs) { s.request(chain.id, method, params) } ?: "no answer within ${waitMs}ms"
        } catch (e: CancellationException) {
            // The source's own cancellation, not ours: no answer.
            currentCoroutineContext().ensureActive()
            "cancelled"
        } catch (e: ChainRpcException.Rpc) {
            if (e.deterministic) throw e
            e.message
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
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
        val asked = quorum?.urls.orEmpty().toSet()
        var last: String? = null
        for (url in pool) {
            if (url in asked) continue
            when (val leg = call(url, body, policy.timeoutMs)) {
                is Leg.Value -> return ChainDataResult(leg.result, direct(chain, url))
                is Leg.Deterministic -> throw leg.error
                is Leg.NodeError -> {
                    onNodeError(leg.error)
                    last = "${hostOf(url)}: ${leg.error.message}"
                }
                is Leg.Failed -> last = "${hostOf(url)}: ${leg.reason}"
            }
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
    private suspend fun call(url: String, body: String, timeoutMs: Long): Leg {
        val text = try {
            transport.post(url, body, timeoutMs)
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            return Leg.Failed("cancelled", timeout = false)
        } catch (e: Exception) {
            failedAt[url] = clock()
            return Leg.Failed(e.message ?: e.javaClass.simpleName, timeout = e is RpcTimeoutException)
        }
        return when (val env = JsonRpc.parse(text)) {
            is JsonRpc.Envelope.Result -> {
                failedAt.remove(url)
                Leg.Value(env.value)
            }
            is JsonRpc.Envelope.Error ->
                if (env.error.deterministic) Leg.Deterministic(env.error) else Leg.NodeError(env.error)
            is JsonRpc.Envelope.Malformed -> {
                failedAt[url] = clock()
                Leg.Failed(env.reason, timeout = false)
            }
        }
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

        private val ALREADY_KNOWN = Regex("already known|known transaction|already imported", RegexOption.IGNORE_CASE)

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
                    ChainDataRouter(chains = { store.chains.first() }, transport = PinnedHttpTransport())
                }.also { instance = it }
            }
    }
}
