package baby.freedom.mobile.chains.rpc

import baby.freedom.mobile.ens.EnsColibri
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * The router's [ChainSource.COLIBRI] tier (#329): corpus.core's Colibri
 * stateless verifier ([EnsColibri], the one name resolution uses) checks
 * a remote prover's proof on this device against the chain's sync
 * committee, on Ethereum and Gnosis. With *privacy mode basic* the state
 * a read touches is fetched from the chain's own RPCs (the router's pool,
 * the user's first) and checked against the proven state root; the
 * prover learns which accounts and storage a read touches, as the RPCs
 * do.
 *
 * It answers [METHODS] — what the core can prove — and nothing else:
 * a `pending` nonce or balance (the mempool is nothing a proof covers)
 * goes to the next tier, as does a `null` receipt, transaction or block,
 * which says only that the prover didn't find it, never a proven "no".
 * `eth_blockNumber` is answered from a proven `latest` block, no older
 * than [EnsColibri.MAX_LATEST_AGE_SECONDS]. At most [maxInFlight] reads
 * at once: past that the router moves straight on rather than queueing
 * behind the verifier's one lock (which name resolution shares). Never
 * broadcasts.
 *
 * Only while [enabled]: the *Colibri proofs* switch (Settings → Name
 * resolution), which covers these reads too — off, nothing goes to the
 * prover and reads start at the quorum.
 *
 * **Back-off**, per chain, as name resolution's ([baby.freedom.mobile.ens.EnsResolver]
 * `ColibriBackoff`): a call that can't reach the chain's provers or
 * servers ([EnsColibri.Failure.unreachable], or an unexpected error),
 * or that outlasts the router's wait, makes the tier unavailable for
 * [BACKOFF_MS], doubling with each further one up to [BACKOFF_MAX_MS] —
 * so a prover that's down costs one read the tier's wait, not every
 * read. A call the router stops waiting for carries on in the
 * background (up to [backgroundMs], holding its slot) rather than being
 * cut off — on a first read that is the sync-committee bootstrap, which
 * the next read then needn't repeat — and any proof that comes in ends
 * the back-off. One call counts at most once, and a proof of this one
 * read not checking out doesn't count: it says nothing about the others.
 *
 * Only the wallet's and the Swarm node's own reads count directly. A
 * page's read ([RoutingContext.interactive]: `window.ethereum`,
 * `web3://`) is one the page chose — a call that takes the prover long
 * to prove, or never proves, under a 2 s wait — so its miss or failure
 * proves nothing about the prover, and on its own would let any site
 * take the tier away from the wallet. It only asks for a **canary**: a
 * proven `latest` block, which no page shapes, run in the background
 * (up to [backgroundMs], outside [maxInFlight], one per chain at a time
 * and at most one per [CANARY_INTERVAL_MS]). The canary failing the way
 * a wallet read would count is what backs the chain off.
 */
internal class ColibriChainSource(
    private val colibri: EnsColibri,
    /** Whether the verifier may be usable here without loading it: [isAvailable] runs on the UI thread. */
    private val present: () -> Boolean,
    /** The *Colibri proofs* switch ([ColibriReads]); read on every call. */
    private val enabled: () -> Boolean = { true },
    private val maxInFlight: Int = MAX_IN_FLIGHT,
    private val backgroundMs: Long = BACKGROUND_MS,
    /** Monotonic milliseconds: a wall-clock step mustn't end or stretch a back-off. */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    /** Where calls run, so one the router stopped waiting for can finish. */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : VerifiedChainSource {
    private val inFlight = AtomicInteger()
    private val backoffs = ConcurrentHashMap<Long, Backoff>()
    private val canaries = ConcurrentHashMap<Long, Canary>()

    override fun isAvailable(chainId: Long): Boolean = gap(chainId) == null

    override fun gap(chainId: Long): ProofTierGap? = when {
        chainId !in EnsColibri.CHAINS || !ChainAccessPolicy.supports(ChainSource.COLIBRI, chainId) ->
            ProofTierGap.NOT_SERVED
        !present() -> ProofTierGap.NOT_IN_BUILD
        !enabled() -> ProofTierGap.OFF
        backoffRemainingMs(chainId) != null -> ProofTierGap.UNREACHABLE
        else -> null
    }

    /** How much longer chain [chainId]'s reads skip the prover; `null` when it may be asked. */
    internal fun backoffRemainingMs(chainId: Long): Long? = backoffs[chainId]?.remainingMs()

    override suspend fun request(
        chainId: Long,
        method: String,
        params: JSONArray,
        rpcs: List<String>,
        context: RoutingContext,
    ): ChainDataResult {
        if (!enabled()) throw Unanswered("Colibri proofs are off")
        backoffRemainingMs(chainId)?.let { throw Unanswered("backing off for ${it}ms: the prover couldn't be reached") }
        if (method !in METHODS) throw Unanswered("Colibri doesn't prove $method")
        TAG_PARAM[method]?.let { i ->
            val tag = params.opt(i)
            if (tag == "pending") throw Unanswered("$method at \"pending\" can't be proven")
        }
        if (inFlight.incrementAndGet() > maxInFlight) {
            inFlight.decrementAndGet()
            throw Unanswered("Colibri is busy")
        }
        val backoff = backoffs.getOrPut(chainId) { Backoff() }
        // One call is one failure at most, whichever side (a missed wait
        // here, or the background call's own end) sees it first.
        val counted = AtomicBoolean(false)
        fun failed() {
            if (!counted.compareAndSet(false, true)) return
            // A page's read only asks the canary (see the class kdoc).
            if (context.interactive) canary(chainId, rpcs) else backoff.failed()
        }
        val blockNumber = method == "eth_blockNumber"
        val call = scope.async {
            try {
                withTimeoutOrNull(backgroundMs) {
                    if (blockNumber) {
                        colibri.request(chainId, "eth_getBlockByNumber", JSONArray().put("latest").put(false), rpcs)
                    } else {
                        colibri.request(chainId, method, params, rpcs)
                    }
                }.also { if (it != null) backoff.succeeded() else failed() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: EnsColibri.Failure) {
                // This read's own proof failing isn't the provers
                // being unreachable: other reads may still prove.
                if (e.unreachable) failed()
                throw e
            } catch (e: Throwable) {
                failed()
                throw e
            } finally {
                inFlight.decrementAndGet()
            }
        }
        val (status, provers) = try {
            call.await() ?: throw Unanswered("no proof within ${backgroundMs}ms")
        } catch (e: CancellationException) {
            // The router's wait ran out (or its reader went away) with
            // the call still running: a missed wait. It carries on.
            if (call.isActive) failed()
            throw e
        }
        if (status.optString("status") == "revert") {
            if (method != "eth_call") throw Unanswered("the verifier reported a revert for $method")
            throw ChainRpcException.Rpc(
                ChainRpcException.EXECUTION_REVERTED,
                "execution reverted",
                status.optString("data", "0x").ifEmpty { "0x" },
            )
        }
        val raw = status.opt("result")
        if (raw == null || raw == JSONObject.NULL) throw Unanswered("no proven answer for $method")
        val result: Any = if (blockNumber) {
            (raw as? JSONObject)?.opt("number") as? String ?: throw Unanswered("a block without a number")
        } else {
            raw
        }
        val hosts = provers.ifEmpty {
            listOfNotNull(EnsColibri.CHAINS[chainId]?.provers?.firstOrNull()?.let(EnsColibri::hostOf))
        }
        return ChainDataResult(
            result,
            ChainTrust(
                level = ChainTrust.Level.VERIFIED,
                source = ChainSource.COLIBRI,
                agreed = hosts,
                dissented = emptyList(),
                queried = hosts,
                k = 1,
                m = 1,
                block = blockOf(raw),
            ),
        )
    }

    /**
     * Check chain [chainId]'s prover with a read no page shapes — a
     * proven `latest` block — after a page's read missed or failed, and
     * back the chain off only if that fails too (see the class kdoc).
     */
    private fun canary(chainId: Long, rpcs: List<String>) {
        val c = canaries.getOrPut(chainId) { Canary() }
        if (!c.start(clock())) return
        val backoff = backoffs.getOrPut(chainId) { Backoff() }
        scope.async {
            try {
                val proven = withTimeoutOrNull(backgroundMs) {
                    colibri.request(chainId, "eth_getBlockByNumber", JSONArray().put("latest").put(false), rpcs)
                }
                if (proven != null) backoff.succeeded() else backoff.failed()
            } catch (e: CancellationException) {
                throw e
            } catch (e: EnsColibri.Failure) {
                if (e.unreachable) backoff.failed()
            } catch (_: Throwable) {
                backoff.failed()
            } finally {
                c.done()
            }
        }
    }

    /** Whether a page's miss may start one more canary for a chain (see the class kdoc). */
    private class Canary {
        private var running = false
        private var startedAt: Long? = null

        @Synchronized
        fun start(now: Long): Boolean {
            val last = startedAt
            if (running || (last != null && now - last in 0 until CANARY_INTERVAL_MS)) return false
            running = true
            startedAt = now
            return true
        }

        @Synchronized
        fun done() {
            running = false
        }
    }

    /** No proven answer; the router moves on. */
    class Unanswered(message: String) : Exception(message)

    /** One chain's back-off (see the class kdoc). */
    private inner class Backoff {
        private var failures = 0
        private var until = 0L

        @Synchronized
        fun remainingMs(): Long? {
            val left = until - clock()
            return left.takeIf { it > 0 && it <= BACKOFF_MAX_MS }
        }

        @Synchronized
        fun failed() {
            failures = (failures + 1).coerceAtMost(16)
            until = clock() + (BACKOFF_MS shl (failures - 1)).coerceAtMost(BACKOFF_MAX_MS)
        }

        @Synchronized
        fun succeeded() {
            failures = 0
            until = 0
        }
    }

    companion object {
        const val MAX_IN_FLIGHT = 4

        /** Name resolution's figures ([baby.freedom.mobile.ens.EnsResolver.COLIBRI_BACKOFF_MS]). */
        const val BACKOFF_MS = 30_000L
        const val BACKOFF_MAX_MS = 5 * 60_000L

        /** How long a call the router stopped waiting for may go on: a first sync-committee bootstrap. */
        const val BACKGROUND_MS = 60_000L

        /** The least time between two canaries for one chain: a page looping misses mustn't loop them. */
        const val CANARY_INTERVAL_MS = 10_000L

        /** What the core proves (colibri.h, `c4_get_method_support` PROOFABLE), and the router asks. */
        val METHODS = setOf(
            "eth_blockNumber", "eth_getBalance", "eth_getTransactionCount", "eth_getCode", "eth_getStorageAt",
            "eth_call", "eth_getTransactionReceipt", "eth_getTransactionByHash",
            "eth_getBlockByNumber", "eth_getBlockByHash",
        )

        /** Where each state read's block tag sits in its params. */
        private val TAG_PARAM = mapOf(
            "eth_getBalance" to 1, "eth_getTransactionCount" to 1, "eth_getCode" to 1,
            "eth_getStorageAt" to 2, "eth_call" to 1, "eth_getBlockByNumber" to 0,
        )

        /** The block a proven receipt, transaction or block is in, when it says. */
        private fun blockOf(result: Any?): Long? {
            val o = result as? JSONObject ?: return null
            val hex = (o.opt("blockNumber") ?: o.opt("number")) as? String ?: return null
            return hex.takeIf { it.startsWith("0x") && it.length in 3..17 }
                ?.let { runCatching { it.substring(2).toLong(16) }.getOrNull() }
        }
    }
}
