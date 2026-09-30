package baby.freedom.mobile.chains.rpc

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** One endpoint's reply to one request, as the router's tiers see it. */
internal sealed interface Leg {
    /** An answer: [key] is what two endpoints must share to agree on it. */
    sealed interface Answer : Leg {
        val key: String
    }

    data class Value(val result: Any?) : Answer {
        override val key: String get() = "v:" + JsonRpc.stable(result)
    }

    /** A deterministic error ([ChainRpcException.Rpc.deterministic]) — an answer like any other. */
    data class Deterministic(val error: ChainRpcException.Rpc) : Answer {
        override val key: String get() = error.voteKey
    }

    /** The node answered with an error of its own (rate limit, …); not a vote. */
    data class NodeError(val error: ChainRpcException.Rpc) : Leg

    /** No answer: transport, status, address or envelope trouble. */
    data class Failed(val reason: String, val timeout: Boolean) : Leg
}

/**
 * The quorum tier's one request (desktop's `requestQuorum`, iOS's
 * `QuorumRun`): the same bytes to each of [urls] at once, settled as soon
 * as [m] answers agree byte for byte, failed as soon as that can't happen.
 *
 * Its legs run in [scope], not the caller's, so a verdict can stop
 * waiting without cutting them short: when a direct tier follows, a leg
 * still in flight after the quorum gave up keeps its own endpoint
 * timeout and can serve the direct tier ([directCandidate]) instead of a
 * new request to the same endpoint. [cancel] ends whatever is left and
 * files each unfinished leg as cancelled, so nothing waits on it.
 */
internal class QuorumRun(
    val urls: List<String>,
    private val m: Int,
    scope: CoroutineScope,
    call: suspend (String) -> Leg,
) {
    sealed interface Verdict {
        /** [m] endpoints gave [answer]; [dissented] answered something else. */
        data class Agreed(
            val answer: Leg.Answer,
            val agreed: List<String>,
            val dissented: List<String>,
        ) : Verdict

        data class Failed(val reason: String, val timedOut: Boolean, val nodeError: ChainRpcException.Rpc?) : Verdict
    }

    /** A single endpoint's answer the direct tier can use, with what the quorum saw. */
    data class Candidate(
        val url: String,
        val answer: Leg.Answer,
        val agreed: List<String>,
        val dissented: List<String>,
    )

    // Written by the legs themselves, in arrival order, so an answer that
    // lands while nobody is waiting (or just as a wait times out) is
    // never lost; [version] wakes whoever is.
    private val legs = LinkedHashMap<Int, Leg>()

    /** Legs [cancel] ended before they answered: never asked, as far as a later tier is concerned. */
    private val cancelled = HashSet<Int>()
    private val version = MutableStateFlow(0)
    private val jobs = urls.mapIndexed { i, url ->
        scope.launch {
            val leg = try {
                call(url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Leg.Failed(e.message ?: e.javaClass.simpleName, timeout = false)
            }
            // [cancel] may have filed this leg already; its verdict stands.
            synchronized(legs) { legs.putIfAbsent(i, leg) }
            version.update { it + 1 }
        }
    }

    private fun snapshot(): Map<Int, Leg> = synchronized(legs) { LinkedHashMap(legs) }

    /** Wait up to [waitMs] for a verdict. */
    suspend fun await(waitMs: Long): Verdict {
        withTimeoutOrNull(waitMs) {
            version.first { snapshot().let { decide(it) != null || it.size == urls.size } }
        }
        val legs = snapshot()
        decide(legs)?.let { return it }
        val pending = urls.size - legs.size
        return Verdict.Failed(
            reason = if (pending > 0) "no $m matching answers within ${waitMs}ms" else "no $m matching answers",
            timedOut = pending > 0 || legs.values.any { it is Leg.Failed && it.timeout },
            nodeError = nodeError(legs),
        )
    }

    private fun decide(legs: Map<Int, Leg>): Verdict? {
        val buckets = LinkedHashMap<String, MutableList<Int>>()
        for ((i, leg) in legs) if (leg is Leg.Answer) buckets.getOrPut(leg.key) { mutableListOf() } += i
        buckets.values.firstOrNull { it.size >= m }?.let { winner ->
            return Verdict.Agreed(
                answer = legs.getValue(winner.first()) as Leg.Answer,
                agreed = winner.map { urls[it] },
                dissented = buckets.values.flatten().filter { it !in winner }.sorted().map { urls[it] },
            )
        }
        val largest = buckets.values.maxOfOrNull { it.size } ?: 0
        if (largest + (urls.size - legs.size) < m) {
            return Verdict.Failed("$m matching answers no longer possible", timedOut = false, nodeError = nodeError(legs))
        }
        return null
    }

    private fun nodeError(legs: Map<Int, Leg>): ChainRpcException.Rpc? =
        legs.values.filterIsInstance<Leg.NodeError>().lastOrNull()?.error

    /**
     * For a direct tier after a failed quorum: the highest-priority
     * endpoint that answered — waiting for legs still in flight (each
     * bounded by its own endpoint timeout) only while none has. `null`
     * if none answers.
     */
    suspend fun directCandidate(): Candidate? {
        version.first { snapshot().let { l -> l.values.any { it is Leg.Answer } || l.size == urls.size } }
        val answered = snapshot().entries.filter { it.value is Leg.Answer }.sortedBy { it.key }
        val first = answered.firstOrNull() ?: return null
        val answer = first.value as Leg.Answer
        return Candidate(
            url = urls[first.key],
            answer = answer,
            agreed = answered.filter { (it.value as Leg.Answer).key == answer.key }.map { urls[it.key] },
            dissented = answered.filter { (it.value as Leg.Answer).key != answer.key }.map { urls[it.key] },
        )
    }

    /**
     * How every leg that has ended without an answer failed, in arrival
     * order — a node's own error or a transport failure — for a caller's
     * `rankError` ([ChainDataRouter.request]). Legs [cancel] ended aren't
     * failures of their endpoint and aren't listed.
     */
    fun failures(): List<ChainFailure> = synchronized(legs) {
        legs.filterKeys { it !in cancelled }.values.mapNotNull { leg ->
            when (leg) {
                is Leg.NodeError -> ChainFailure.of(leg.error)
                is Leg.Failed -> ChainFailure(null, leg.reason, null, leg.timeout)
                is Leg.Answer -> null
            }
        }
    }

    /** Whether some endpoint has answered ([Leg.Answer]) — what [directCandidate] would reuse. */
    fun hasAnswer(): Boolean = synchronized(legs) { legs.values.any { it is Leg.Answer } }

    /** Legs still in flight. */
    fun pending(): Int = urls.size - synchronized(legs) { legs.size }

    /**
     * The endpoints this run really asked: every one but those [cancel]
     * ended before they answered. A later tier needn't ask these again.
     */
    fun asked(): Set<String> = synchronized(legs) { urls.filterIndexed { i, _ -> i !in cancelled }.toSet() }

    fun cancel() {
        jobs.forEach { it.cancel() }
        synchronized(legs) {
            for (i in urls.indices) {
                if (i !in legs) {
                    legs[i] = Leg.Failed("cancelled", timeout = false)
                    cancelled += i
                }
            }
        }
        version.update { it + 1 }
    }
}
