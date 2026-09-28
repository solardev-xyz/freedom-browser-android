package baby.freedom.swarm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/*
 * Stale-anchor checkpoint recovery (#195), part 2: the external checkpoint
 * quorum. Port of iOS `MyotisCheckpointQuorum.swift` (itself desktop's
 * `checkpointVote` / `checkpointQuorum`): same endpoints, same evidence
 * rules, same seat-replacement rule, same error classes.
 *
 * Trust model: each authority votes once for a `(slot, root)` and only
 * after explicitly endorsing finality — a block-root answer alone is
 * never a vote. A quorum is `threshold` agreeing votes out of
 * `participants` seats filled in stable candidate order; only
 * availability failures free a seat for the next reserve, so dissent and
 * contradictory evidence keep their seat, and the threshold is never
 * lowered because a host is missing.
 *
 * Android difference: desktop and iOS also corroborate the quorum with a
 * Colibri committee-history proof, and take the slot to vote on from the
 * checkpoint request Colibri's verifier makes. Android has no Colibri
 * verifier (see [MyotisCheckpointAcquirer]), so the slot is proposed by
 * the authorities themselves — each one's own current finalized
 * checkpoint, tried in candidate order — and the quorum then votes on it
 * exactly as it would on Colibri's.
 */

/**
 * Bounded HTTPS GET for the recovery path. Implementations must refuse
 * redirects, require status 200, cap the streamed body at `limit` bytes,
 * time out per request, and throw [MyotisCheckpointException] (verdict
 * [MyotisCheckpointError.Unavailable]) for every transport failure.
 * Injected so the quorum logic is testable offline.
 */
fun interface MyotisCheckpointFetcher {
    suspend fun get(url: String, limit: Int): ByteArray

    /**
     * [get], plus the server's clock from the response's HTTP `Date`
     * header when it sent a parsable one. Only a hint for telling a wrong
     * device clock from a really stale checkpoint — never a vote.
     */
    suspend fun fetch(url: String, limit: Int): MyotisCheckpointResponse =
        MyotisCheckpointResponse(get(url, limit), serverDateMs = null)
}

/** A fetched body and the server's `Date` header (ms since the epoch), if any. */
class MyotisCheckpointResponse(val body: ByteArray, val serverDateMs: Long?)

/**
 * [HttpURLConnection]-backed [MyotisCheckpointFetcher]: no redirects, no
 * caches, no cookies, `Accept: application/json`, [REQUEST_MS] per
 * request.
 *
 * The per-request deadline is enforced from outside the blocking read:
 * the connection runs on its own worker thread, the caller waits at most
 * [REQUEST_MS] (or until it's cancelled), and on either it returns at
 * once while a separate thread disconnects the stalled socket — so a
 * server trickling bytes just under the read timeout can't stretch an
 * attempt past its deadline, and a cancelled recovery doesn't wait on a
 * read.
 */
class HttpCheckpointFetcher(private val requestMs: Long = REQUEST_MS) : MyotisCheckpointFetcher {
    override suspend fun get(url: String, limit: Int): ByteArray = fetch(url, limit).body

    override suspend fun fetch(url: String, limit: Int): MyotisCheckpointResponse {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            useCaches = false
            connectTimeout = requestMs.toInt()
            readTimeout = requestMs.toInt()
            setRequestProperty("Accept", "application/json")
        }
        val result = CompletableDeferred<MyotisCheckpointResponse>()
        val work = workers.submit {
            try {
                result.complete(read(connection, limit))
            } catch (e: MyotisCheckpointException) {
                result.completeExceptionally(e)
            } catch (_: SocketTimeoutException) {
                result.completeExceptionally(MyotisCheckpointException.transport(MyotisTransportFailure.Timeout))
            } catch (_: Throwable) {
                // Nobody but [result] is watching this thread: nothing may escape it.
                result.completeExceptionally(MyotisCheckpointException.transport(MyotisTransportFailure.Transport))
            }
        }
        try {
            return withTimeoutOrNull(requestMs) { result.await() }
                ?: throw MyotisCheckpointException.transport(MyotisTransportFailure.Timeout)
        } finally {
            if (!result.isCompleted) {
                // Abort from a thread that isn't the one blocked in the read.
                aborter.execute { runCatching { connection.disconnect() } }
                work.cancel(true)
            }
        }
    }

    private fun read(connection: HttpURLConnection, limit: Int): MyotisCheckpointResponse {
        try {
            val status = connection.responseCode
            if (status != 200) throw MyotisCheckpointException.transport(MyotisTransportFailure.Http, status)
            val declared = connection.contentLengthLong
            if (declared > limit) throw MyotisCheckpointException.transport(MyotisTransportFailure.BodyLimit)
            val out = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (out.size() + n > limit) throw MyotisCheckpointException.transport(MyotisTransportFailure.BodyLimit)
                    out.write(buffer, 0, n)
                }
            }
            val date = connection.getHeaderFieldDate("Date", 0L).takeIf { it > 0L }
            return MyotisCheckpointResponse(out.toByteArray(), date)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val REQUEST_MS = 20_000L

        private fun daemon(name: String): ThreadFactory {
            val n = AtomicInteger()
            return ThreadFactory { r -> Thread(r, "$name-${n.incrementAndGet()}").apply { isDaemon = true } }
        }

        private val workers = Executors.newCachedThreadPool(daemon("myotis-checkpoint"))
        private val aborter = Executors.newCachedThreadPool(daemon("myotis-checkpoint-abort"))
    }
}

/**
 * One authority's proposed slot, and [clockSkewMs] — this device's clock
 * minus the authority's HTTP `Date` when it answered — if it sent one.
 */
data class MyotisCheckpointProposal(val slot: Long, val clockSkewMs: Long?)

/** What a quorum endorsed for one slot. */
data class MyotisCheckpointObservation(
    val slot: Long,
    val root: String,
    val finalizedEpoch: Long,
    val sources: List<String>,
)

/**
 * The quorum client for one chain. [onDiagnostic] gets one allow-listed
 * line per source outcome (host, slot, outcome code, elapsed ms, stage,
 * failure class, HTTP status — never a body or path); it's observation
 * only and can't affect a vote.
 */
class MyotisCheckpointQuorum(
    val network: MyotisCheckpointNetwork,
    private val fetcher: MyotisCheckpointFetcher,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val onDiagnostic: (String) -> Unit = {},
) {
    /** One authority's vote. */
    data class Vote(val source: String, val slot: Long, val root: String, val finalizedEpoch: Long)

    private suspend fun metadata(source: String, path: String, stage: MyotisCheckpointStage): JSONObject =
        timedMetadata(source, path, stage).first

    /** [metadata], plus this device's clock minus the server's `Date` (ms), when it sent one. */
    private suspend fun timedMetadata(
        source: String,
        path: String,
        stage: MyotisCheckpointStage,
    ): Pair<JSONObject, Long?> {
        try {
            val response = fetcher.fetch(source + path, MAX_METADATA_BYTES)
            val skew = response.serverDateMs?.let { nowMs() - it }
            return try {
                JSONObject(response.body.toString(Charsets.UTF_8)) to skew
            } catch (_: JSONException) {
                throw MyotisCheckpointException.transport(MyotisTransportFailure.InvalidJson)
            } catch (_: StackOverflowError) {
                // A deeply nested body: not JSON we can use, and not a crash.
                throw MyotisCheckpointException.transport(MyotisTransportFailure.InvalidJson)
            }
        } catch (e: MyotisCheckpointException) {
            throw e.at(stage)
        }
    }

    private fun fail(error: MyotisCheckpointError): Nothing = throw MyotisCheckpointException(error)

    /**
     * Desktop `checkpointVote`: the block root at [slot] and the head
     * finality checkpoints, fetched together. The root only counts once
     * the authority's finality covers the slot; when its finalized root
     * differs (the slot is older), the block's own `finalized: true` or
     * the authority's finalized-slot history must endorse exactly this
     * slot with exactly this root.
     */
    suspend fun vote(source: String, slot: Long): Vote = coroutineScope {
        val blockTask = async { metadata(source, "/eth/v1/beacon/blocks/$slot/root", MyotisCheckpointStage.BlockRoot) }
        val finalityTask = async {
            metadata(source, "/eth/v1/beacon/states/head/finality_checkpoints", MyotisCheckpointStage.Finality)
        }
        val block = blockTask.await()
        val finality = finalityTask.await()
        for (body in listOf(block, finality)) {
            if (body.has("execution_optimistic")) {
                val optimistic = body.opt("execution_optimistic") as? Boolean ?: fail(MyotisCheckpointError.Unavailable)
                if (optimistic) fail(MyotisCheckpointError.QuorumConflict)
            }
        }
        // The requested BLOCK's own finality flag. The head STATE's
        // top-level flag describes the head, never this block.
        val blockFinalized: Boolean? = if (block.has("finalized")) {
            block.opt("finalized") as? Boolean ?: fail(MyotisCheckpointError.Unavailable)
        } else null
        if (blockFinalized == false) fail(MyotisCheckpointError.Race)
        val blockEndorsed = blockFinalized == true && block.opt("execution_optimistic") == false
        // A plain Beacon API authority has no finalized-slot history:
        // without the explicit endorsement on the block it can't vote.
        if (source in network.beaconSources && !blockEndorsed) fail(MyotisCheckpointError.Unavailable)
        val root = MyotisHex.root(block.optJSONObject("data")?.opt("root")) ?: fail(MyotisCheckpointError.Unavailable)
        val finalized = finality.optJSONObject("data")?.optJSONObject("finalized")
        val epoch = MyotisHex.uint(finalized?.opt("epoch")) ?: fail(MyotisCheckpointError.Unavailable)
        val finalizedRoot = MyotisHex.root(finalized?.opt("root")) ?: fail(MyotisCheckpointError.Unavailable)
        val epochSlot = network.epochStartSlot(epoch)
        if (epochSlot == null || epochSlot > network.wallSlot(nowMs())) fail(MyotisCheckpointError.Clock)
        if (epochSlot < slot) fail(MyotisCheckpointError.Race)
        if (finalizedRoot != root) {
            if (epoch == network.epochCeil(slot)) fail(MyotisCheckpointError.QuorumConflict)
            if (!blockEndorsed) {
                val history = metadata(source, "/checkpointz/v1/beacon/slots", MyotisCheckpointStage.History)
                val slots = history.optJSONObject("data")?.opt("slots") as? JSONArray
                if (slots == null || slots.length() > MAX_HISTORY) fail(MyotisCheckpointError.Unavailable)
                val entries = (0 until slots.length()).mapNotNull { i ->
                    val entry = slots.opt(i) as? JSONObject ?: return@mapNotNull null
                    val entrySlot = MyotisHex.uint(entry.opt("slot")) ?: fail(MyotisCheckpointError.Unavailable)
                    entry.takeIf { entrySlot == slot }
                }
                if (entries.isEmpty()) fail(MyotisCheckpointError.Race)
                val historyRoot = entries.singleOrNull()?.let { MyotisHex.root(it.opt("block_root")) }
                    ?: fail(MyotisCheckpointError.QuorumConflict)
                if (historyRoot != root) fail(MyotisCheckpointError.QuorumConflict)
            }
        }
        Vote(source, slot, root, network.epochCeil(slot))
    }

    /**
     * Desktop `checkpointQuorum`. Seats fill in stable candidate order; a
     * definitive answer (a vote, or any failure but `unavailable`/`race`)
     * keeps its seat, only those two free it for the next reserve. The
     * first root group to reach `threshold` wins.
     */
    suspend fun quorum(slot: Long): MyotisCheckpointObservation {
        val results = mutableListOf<Result<Vote>>()
        var next = 0
        var occupied = 0
        val sources = network.sources
        while (next < sources.size && occupied < network.participants) {
            val candidates = sources.subList(next, minOf(sources.size, next + network.participants - occupied))
            next += candidates.size
            val batch = coroutineScope {
                candidates.map { source -> async { timedVote(source, slot) } }.awaitAll()
            }
            results += batch
            occupied += batch.count { result ->
                val code = (result.exceptionOrNull() as? MyotisCheckpointException)?.error
                result.isSuccess || (code != MyotisCheckpointError.Unavailable && code != MyotisCheckpointError.Race)
            }
            val groups = LinkedHashMap<String, MutableList<Vote>>()
            for (vote in results.mapNotNull { it.getOrNull() }) groups.getOrPut(vote.root) { mutableListOf() } += vote
            for ((root, group) in groups) {
                if (group.size >= network.threshold) {
                    return MyotisCheckpointObservation(slot, root, group[0].finalizedEpoch, group.map { it.source })
                }
            }
        }
        val roots = results.mapNotNull { it.getOrNull()?.root }.toSet()
        val failures = results.mapNotNull { (it.exceptionOrNull() as? MyotisCheckpointException)?.error }
        if (roots.size > 1 || MyotisCheckpointError.QuorumConflict in failures) fail(MyotisCheckpointError.QuorumConflict)
        if (results.isNotEmpty() && failures.size == results.size && failures.all { it == MyotisCheckpointError.Clock }) {
            fail(MyotisCheckpointError.Clock)
        }
        fail(MyotisCheckpointError.QuorumUnavailable)
    }

    private suspend fun timedVote(source: String, slot: Long): Result<Vote> {
        val started = nowMs()
        val result = try {
            Result.success(vote(source, slot))
        } catch (e: MyotisCheckpointException) {
            Result.failure(e)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Anything else is a service failure, never a verdict.
            Result.failure(MyotisCheckpointException(MyotisCheckpointError.Unavailable))
        }
        diagnostic(source, slot, started, result.exceptionOrNull() as? MyotisCheckpointException)
        return result
    }

    /**
     * Android's stand-in for the slot Colibri's verifier would ask about:
     * [source]'s own current finalized checkpoint block. The finalized
     * root comes from its finality checkpoints; its slot from the
     * Checkpointz finalized-slot history (Checkpointz authorities) or the
     * block header (plain Beacon API authorities). Only a question for
     * the quorum — the proposing authority's say-so is worth one vote,
     * cast in [quorum] like everyone else's.
     */
    suspend fun propose(source: String): MyotisCheckpointProposal {
        val started = nowMs()
        try {
            return proposeSlot(source)
        } catch (e: MyotisCheckpointException) {
            diagnostic(source, 0, started, e.at(MyotisCheckpointStage.Proposal))
            throw e
        }
    }

    private suspend fun proposeSlot(source: String): MyotisCheckpointProposal {
        val stage = MyotisCheckpointStage.Proposal
        val (finality, skew) = timedMetadata(source, "/eth/v1/beacon/states/head/finality_checkpoints", stage)
        val finalized = finality.optJSONObject("data")?.optJSONObject("finalized")
        val epoch = MyotisHex.uint(finalized?.opt("epoch")) ?: fail(MyotisCheckpointError.Unavailable)
        val root = MyotisHex.root(finalized?.opt("root")) ?: fail(MyotisCheckpointError.Unavailable)
        val epochSlot = network.epochStartSlot(epoch)
        if (epochSlot == null || epochSlot > network.wallSlot(nowMs())) fail(MyotisCheckpointError.Clock)
        val slot = if (source in network.beaconSources) {
            val header = metadata(source, "/eth/v1/beacon/headers/$root", stage).optJSONObject("data")
            if (MyotisHex.root(header?.opt("root")) != root) fail(MyotisCheckpointError.Unavailable)
            MyotisHex.uint(header?.optJSONObject("header")?.optJSONObject("message")?.opt("slot"))
        } else {
            val slots = metadata(source, "/checkpointz/v1/beacon/slots", stage)
                .optJSONObject("data")?.opt("slots") as? JSONArray
            if (slots == null || slots.length() > MAX_HISTORY) fail(MyotisCheckpointError.Unavailable)
            (0 until slots.length())
                .mapNotNull { slots.opt(it) as? JSONObject }
                .filter { MyotisHex.root(it.opt("block_root")) == root }
                .singleOrNull()
                ?.let { MyotisHex.uint(it.opt("slot")) }
        } ?: fail(MyotisCheckpointError.Unavailable)
        if (slot < 1 || slot > epochSlot) fail(MyotisCheckpointError.Unavailable)
        return MyotisCheckpointProposal(slot, skew)
    }

    private fun diagnostic(source: String, slot: Long, started: Long, error: MyotisCheckpointException?) {
        val elapsed = (nowMs() - started).coerceIn(0, MyotisCheckpointAcquirer.DEADLINE_MS)
        val parts = mutableListOf(
            "source=${runCatching { URL(source).host }.getOrNull() ?: "?"}",
            "slot=$slot",
            "outcome=${error?.error?.code ?: "vote"}",
            "${elapsed}ms",
        )
        error?.stage?.let { parts += "stage=${it.label}" }
        error?.failure?.let { parts += "failure=${it.label}" }
        error?.httpStatus?.takeIf { it in 100..599 }?.let { parts += "status=$it" }
        runCatching { onDiagnostic(parts.joinToString(" ")) }
    }

    companion object {
        const val MAX_METADATA_BYTES = 64 * 1024

        /** Most entries a finalized-slot history may list. */
        const val MAX_HISTORY = 256

        /** Most per-source diagnostic lines one acquisition hands out (desktop: 64). */
        const val MAX_DIAGNOSTICS = 64
    }
}

/**
 * Obtains a recovery checkpoint for one chain: an authority proposes its
 * current finalized checkpoint ([MyotisCheckpointQuorum.propose]), the
 * quorum votes on that slot, and the agreed `(slot, root)` becomes a
 * validated [MyotisCheckpointRecord] no more than an hour old. Proposals
 * are tried in candidate order, one per distinct slot, so one authority
 * that's down or proposing nonsense can't block recovery; a quorum
 * *conflict* ends the attempt at once (terminal, never retried
 * automatically). The whole attempt is bounded by [DEADLINE_MS].
 *
 * **No Colibri corroboration.** Desktop and iOS additionally require a
 * Colibri committee-history proof whose checkpoint request is answered
 * with the quorum's root. Android has no Colibri verifier, and the
 * desktop audit (freedom-browser
 * `docs/audits/myotis-colibri-corroboration-2026-09.md`) found the
 * quorum's checkpoint authorities to be the actual trust input Colibri
 * leans on too — the proof adds a second implementation, not a second
 * canonical-chain observation. So the trust assumption here is the same
 * external quorum without that implementation diversity; the node then
 * cross-checks the engine's own BLS-verified finalized root against the
 * record once it syncs ([MyotisRecoveryPolicy.isAnchorMismatch]).
 * Nothing calls `myotis_accept_stale_anchor` or raises the bound.
 */
class MyotisCheckpointAcquirer(
    private val fetcher: MyotisCheckpointFetcher = HttpCheckpointFetcher(),
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val deadlineMs: Long = DEADLINE_MS,
) {
    /**
     * A validated, fresh checkpoint for [network]. Throws
     * [MyotisCheckpointException]; [MyotisCheckpointError.Unavailable] when
     * the deadline runs out.
     */
    suspend fun acquire(network: MyotisNetwork, onDiagnostic: (String) -> Unit = {}): MyotisCheckpointRecord {
        val config = MyotisCheckpointNetwork.of(network)
        val budget = AtomicInteger(MyotisCheckpointQuorum.MAX_DIAGNOSTICS)
        val quorum = MyotisCheckpointQuorum(config, fetcher, nowMs) { line ->
            if (budget.getAndDecrement() > 0) onDiagnostic(line)
        }
        return withTimeoutOrNull(deadlineMs) { acquire(config, quorum) }
            ?: throw MyotisCheckpointException(MyotisCheckpointError.Unavailable)
    }

    private suspend fun acquire(config: MyotisCheckpointNetwork, quorum: MyotisCheckpointQuorum): MyotisCheckpointRecord {
        val tried = HashSet<Long>()
        var quorumError: MyotisCheckpointException? = null
        val proposalErrors = mutableListOf<MyotisCheckpointError>()
        for (source in config.sources) {
            val proposal = try {
                quorum.propose(source)
            } catch (e: MyotisCheckpointException) {
                proposalErrors += e.error
                continue
            }
            val slot = proposal.slot
            // Don't spend a quorum on a proposal that couldn't be used anyway.
            val now = nowMs()
            val slotTime = config.slotTimeMs(slot)
            if (slotTime > now) {
                proposalErrors += MyotisCheckpointError.Clock
                continue
            }
            if (now - slotTime > MyotisCheckpointRecord.MAX_AGE_MS) {
                proposalErrors += staleOrClock(now - slotTime, proposal.clockSkewMs)
                continue
            }
            if (!tried.add(slot)) continue
            val observation = try {
                quorum.quorum(slot)
            } catch (e: MyotisCheckpointException) {
                if (e.error == MyotisCheckpointError.QuorumConflict) throw e
                quorumError = e
                continue
            }
            val verifiedAt = nowMs()
            return MyotisCheckpointRecord(
                chainId = config.chainId,
                network = config.network.engineName,
                root = observation.root,
                slot = observation.slot,
                verifiedAt = verifiedAt,
                sources = observation.sources,
                finalizedEpoch = observation.finalizedEpoch,
            ).validated(config.chainId, verifiedAt, fresh = true)
        }
        quorumError?.let { throw it }
        val clockVotes = proposalErrors.count { it == MyotisCheckpointError.Clock }
        val error = when {
            proposalErrors.isNotEmpty() && clockVotes == proposalErrors.size -> MyotisCheckpointError.Clock
            // The device's clock is one fact: `threshold` authorities
            // independently placing it wrong is quorum-level evidence, even
            // with another one down or lagging.
            clockVotes >= config.threshold -> MyotisCheckpointError.Clock
            MyotisCheckpointError.Stale in proposalErrors -> MyotisCheckpointError.Stale
            else -> MyotisCheckpointError.QuorumUnavailable
        }
        throw MyotisCheckpointException(error)
    }

    /**
     * A proposal [ageMs] old by this device's clock, past
     * [MyotisCheckpointRecord.MAX_AGE_MS]. The finalized checkpoint an
     * authority serves is normally minutes old, so an "old" one is either a
     * chain-wide finality stall — [MyotisCheckpointError.Stale], and the
     * ladder asks again — or a device clock set ahead, which no amount of
     * asking fixes. The authority's HTTP `Date` tells them apart: when by
     * *its* clock the checkpoint is fresh and this device is more than
     * [CLOCK_SKEW_MS] ahead of it, it's [MyotisCheckpointError.Clock].
     * Only the classification of a proposal already refused — the `Date`
     * header never makes a checkpoint usable.
     */
    private fun staleOrClock(ageMs: Long, clockSkewMs: Long?): MyotisCheckpointError {
        clockSkewMs ?: return MyotisCheckpointError.Stale
        val ageByServer = ageMs - clockSkewMs
        return if (clockSkewMs > CLOCK_SKEW_MS && ageByServer in 0..MyotisCheckpointRecord.MAX_AGE_MS) {
            MyotisCheckpointError.Clock
        } else {
            MyotisCheckpointError.Stale
        }
    }

    companion object {
        /** Whole-attempt deadline (desktop / iOS: 90 s). */
        const val DEADLINE_MS = 90_000L

        /** How far ahead of an authority's `Date` this device must be to call a stale proposal a clock error. */
        const val CLOCK_SKEW_MS = 10L * 60_000L
    }
}
